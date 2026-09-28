// D.project — browser-side client
// Handles: session creation, WebRTC negotiation, video display, input relay

// Local/LAN: same origin as the page. Deployed: the Render server set in config.js.
const isLocal = ['localhost', '127.0.0.1'].includes(location.hostname) || /^\d+\.\d+\.\d+\.\d+$/.test(location.hostname);
const SERVER_HTTP = (!isLocal && window.DPROJECT_SERVER) || location.origin;
const SERVER_WS   = SERVER_HTTP.replace(/^http/, 'ws');

const RTC_CONFIG = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ],
};

// ── DOM refs ──────────────────────────────────────────────────────────────────
const screens = {
  home:    document.getElementById('screen-home'),
  phone:   document.getElementById('screen-phone'),
  control: document.getElementById('screen-control'),
};

const ui = {
  btnGenerate:    document.getElementById('btn-generate'),
  sessionBox:     document.getElementById('session-box'),
  sessionLink:    document.getElementById('session-link'),
  btnCopy:        document.getElementById('btn-copy'),
  qrPlaceholder:  document.getElementById('qr-placeholder'),
  btnPhoneStart:  document.getElementById('btn-phone-start'),
  phoneStatus:    document.getElementById('phone-status'),
  activeSessionId:document.getElementById('active-session-id'),
  btnDisconnect:  document.getElementById('btn-disconnect'),
  remoteVideo:    document.getElementById('remote-video'),
  inputOverlay:   document.getElementById('input-overlay'),
  btnBack:        document.getElementById('btn-back'),
  btnHome:        document.getElementById('btn-home'),
  btnRecents:     document.getElementById('btn-recents'),
  textInput:      document.getElementById('text-input'),
  btnSendText:    document.getElementById('btn-send-text'),
};

// ── State ─────────────────────────────────────────────────────────────────────
let ws = null;
let pc = null;          // RTCPeerConnection
let sessionId = null;
let role = null;        // 'browser' | 'phone'
let phoneNativeSize = { w: 1080, h: 2400 }; // updated when phone sends it

// ── Routing: detect if this is a phone join link ──────────────────────────────
function detectRole() {
  const params = new URLSearchParams(location.search);
  const sid = params.get('s');
  const view = params.get('view');
  if (sid) {
    // Opened on the phone via its join link
    sessionId = sid;
    role = 'phone';
    showScreen('phone');
  } else if (view) {
    // Opened from the dashboard to watch an already-connected device
    sessionId = view;
    role = 'browser';
    showScreen('control');
    ui.activeSessionId.textContent = sessionId;
    connectWS();
  } else {
    role = 'browser';
    showScreen('home');
  }
}

function showScreen(name) {
  Object.values(screens).forEach(s => s.classList.remove('active'));
  screens[name].classList.add('active');
}

// ── Session creation (browser side) ──────────────────────────────────────────
ui.btnGenerate.addEventListener('click', async () => {
  const res = await fetch(`${SERVER_HTTP}/api/session`);
  const { sessionId: sid, joinUrl: lanJoinUrl } = await res.json();
  sessionId = sid;

  // If browser is on localhost, use the LAN IP the server detected.
  // If browser is on a public tunnel/domain, use that origin — the phone can reach it.
  const joinUrl = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
    ? lanJoinUrl
    : `${location.origin}/?s=${sid}`;

  ui.sessionLink.value = joinUrl;
  ui.sessionBox.classList.remove('hidden');

  renderQR(joinUrl);
  connectWS();
});

ui.btnCopy.addEventListener('click', () => {
  navigator.clipboard.writeText(ui.sessionLink.value);
  ui.btnCopy.textContent = 'Copied!';
  setTimeout(() => (ui.btnCopy.textContent = 'Copy'), 1500);
});

// ── Simple QR using canvas (no library dep) ───────────────────────────────────
async function renderQR(text) {
  // Use a free QR API as fallback (avoids bundling a QR lib)
  const img = document.createElement('img');
  img.src = `https://api.qrserver.com/v1/create-qr-code/?size=150x150&data=${encodeURIComponent(text)}`;
  img.alt = 'QR Code';
  img.style.borderRadius = '6px';
  ui.qrPlaceholder.innerHTML = '';
  ui.qrPlaceholder.appendChild(img);
}

// ── Phone side ────────────────────────────────────────────────────────────────
ui.btnPhoneStart.addEventListener('click', () => {
  ui.phoneStatus.textContent = 'Connecting…';
  connectWS();
});

// ── WebSocket + WebRTC ────────────────────────────────────────────────────────
function connectWS() {
  ws = new WebSocket(`${SERVER_WS}?session=${sessionId}&role=${role}`);

  ws.onopen = () => {
    console.log(`[ws] connected as ${role}`);
    // Phone announces itself so it shows up on the dashboard.
    if (role === 'phone') {
      send({
        type: 'device-info',
        name: describeDevice(),
        w: window.screen.width,
        h: window.screen.height,
      });
    }
  };

  ws.onmessage = async (e) => {
    const msg = JSON.parse(e.data);
    await handleSignal(msg);
  };

  ws.onerror = (e) => console.error('[ws] error', e);
  ws.onclose = () => console.log('[ws] closed');
}

async function handleSignal(msg) {
  switch (msg.type) {
    case 'peer-joined':
      if (role === 'browser') {
        // Phone joined → start WebRTC as offerer
        await startPeerConnection(true);
      } else {
        // Browser joined → wait for offer
        await startPeerConnection(false);
        ui.phoneStatus.textContent = 'Browser connected. Starting stream…';
      }
      break;

    case 'offer':
      if (role === 'phone') {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: msg.sdp }));
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        send({ type: 'answer', sdp: answer.sdp });
      }
      break;

    case 'answer':
      if (role === 'browser') {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.sdp }));
      }
      break;

    case 'ice':
      if (msg.candidate && pc) {
        await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
      }
      break;

    // Control events (received on phone side)
    case 'tap':      handlePhoneTap(msg); break;
    case 'swipe':    handlePhoneSwipe(msg); break;
    case 'key':      handlePhoneKey(msg); break;
    case 'nav':      handlePhoneNav(msg); break;
    case 'text':     handlePhoneText(msg); break;
    case 'native-size':
      phoneNativeSize = { w: msg.w, h: msg.h };
      break;

    case 'peer-left':
      console.log('Peer disconnected');
      if (role === 'browser') showScreen('home');
      break;

    case 'error':
      console.error('[server]', msg.msg);
      break;
  }
}

async function startPeerConnection(isOfferer) {
  pc = new RTCPeerConnection(RTC_CONFIG);

  pc.onicecandidate = ({ candidate }) => {
    if (candidate) send({ type: 'ice', candidate });
  };

  if (role === 'browser') {
    // Browser: receive video track
    pc.ontrack = (e) => {
      ui.remoteVideo.srcObject = e.streams[0];
      showScreen('control');
      ui.activeSessionId.textContent = sessionId;
      setupInputOverlay();
    };
    // Browser is offerer
    if (isOfferer) {
      const offer = await pc.createOffer({ offerToReceiveVideo: true, offerToReceiveAudio: false });
      await pc.setLocalDescription(offer);
      send({ type: 'offer', sdp: offer.sdp });
    }
  }

  if (role === 'phone') {
    // Phone: add camera stream as demo (real screen capture needs native app)
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
      stream.getTracks().forEach(t => pc.addTrack(t, stream));
      const { width, height } = stream.getVideoTracks()[0].getSettings();
      send({ type: 'native-size', w: width || 1080, h: height || 2400 });
      ui.phoneStatus.textContent = 'Streaming your screen…';
    } catch (err) {
      // Fall back to camera if screen share denied (mobile)
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
      stream.getTracks().forEach(t => pc.addTrack(t, stream));
      ui.phoneStatus.textContent = 'Streaming camera (screen share not available)…';
    }
  }
}

function send(obj) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

// Best-effort human label for the phone from its user-agent.
function describeDevice() {
  const ua = navigator.userAgent;

  // Device name. iOS puts the device first (iPhone/iPad); Android puts the model
  // after "Android <ver>;" and may append a "Build/..." token we trim off.
  let device;
  if (/iPhone/.test(ua)) device = 'iPhone';
  else if (/iPad/.test(ua)) device = 'iPad';
  else if (/iPod/.test(ua)) device = 'iPod';
  else {
    const android = ua.match(/Android[^;]*;\s*([^;)]+)/);
    if (android) device = android[1].replace(/\bBuild\/.*/i, '').trim();
    else {
      const paren = ua.match(/\(([^)]+)\)/);
      device = paren ? paren[1].split(';').map(s => s.trim()).filter(Boolean).pop() : (navigator.platform || 'Unknown');
    }
  }

  // Browser. Order matters: Edge and Chrome-for-iOS masquerade as others.
  let browser = 'Browser';
  if (/EdgiOS|EdgA|Edg/.test(ua)) browser = 'Edge';
  else if (/CriOS|Chrome/.test(ua)) browser = 'Chrome';
  else if (/FxiOS|Firefox/.test(ua)) browser = 'Firefox';
  else if (/Version\/.*Safari|Safari/.test(ua)) browser = 'Safari';

  return `${device || 'Unknown'} · ${browser}`;
}

// ── Browser input overlay → send control events to phone ─────────────────────
function setupInputOverlay() {
  const overlay = ui.inputOverlay;
  const video = ui.remoteVideo;

  function videoRect() {
    const vr = video.getBoundingClientRect();
    // Account for letter-boxing inside the video element
    const vidAspect = video.videoWidth / video.videoHeight;
    const boxAspect = vr.width / vr.height;
    let vw, vh, vx, vy;
    if (vidAspect > boxAspect) {
      vw = vr.width; vh = vr.width / vidAspect;
      vx = vr.left; vy = vr.top + (vr.height - vh) / 2;
    } else {
      vh = vr.height; vw = vr.height * vidAspect;
      vy = vr.top; vx = vr.left + (vr.width - vw) / 2;
    }
    return { vx, vy, vw, vh };
  }

  function toPhoneCoords(clientX, clientY) {
    const { vx, vy, vw, vh } = videoRect();
    const rx = (clientX - vx) / vw;
    const ry = (clientY - vy) / vh;
    return {
      x: Math.round(rx * phoneNativeSize.w),
      y: Math.round(ry * phoneNativeSize.h),
    };
  }

  // Resize overlay to match video element
  const resizeObserver = new ResizeObserver(() => {
    overlay.width = overlay.offsetWidth;
    overlay.height = overlay.offsetHeight;
  });
  resizeObserver.observe(overlay);

  // Tap
  let pointerDown = null;
  overlay.addEventListener('pointerdown', (e) => {
    pointerDown = { x: e.clientX, y: e.clientY, t: Date.now() };
  });

  overlay.addEventListener('pointerup', (e) => {
    if (!pointerDown) return;
    const dx = e.clientX - pointerDown.x;
    const dy = e.clientY - pointerDown.y;
    const dt = Date.now() - pointerDown.t;
    const dist = Math.hypot(dx, dy);

    if (dist < 10 && dt < 400) {
      // Tap
      const coords = toPhoneCoords(e.clientX, e.clientY);
      send({ type: 'tap', ...coords });
    } else {
      // Swipe
      const start = toPhoneCoords(pointerDown.x, pointerDown.y);
      const end = toPhoneCoords(e.clientX, e.clientY);
      send({ type: 'swipe', x1: start.x, y1: start.y, x2: end.x, y2: end.y, duration: dt });
    }
    pointerDown = null;
  });

  // Key events
  document.addEventListener('keydown', (e) => {
    if (document.activeElement === ui.textInput) return;
    send({ type: 'key', key: e.key, code: e.code });
  });
}

// ── Phone-side: receive and log control events (real dispatch is in native app) ─
function handlePhoneTap(msg) {
  console.log('[phone] tap', msg.x, msg.y);
  // Native Android app performs: dispatchGesture(tap at x,y)
}
function handlePhoneSwipe(msg) {
  console.log('[phone] swipe', msg.x1, msg.y1, '→', msg.x2, msg.y2);
  // Native Android app performs: dispatchGesture(swipe path)
}
function handlePhoneKey(msg) {
  console.log('[phone] key', msg.key);
}
function handlePhoneNav(msg) {
  console.log('[phone] nav', msg.action);
  // back | home | recents
}
function handlePhoneText(msg) {
  console.log('[phone] text', msg.text);
  // Native Android app: inputText via AccessibilityNodeInfo
}

// ── Nav buttons (browser side) ────────────────────────────────────────────────
ui.btnBack.addEventListener('click', () => send({ type: 'nav', action: 'back' }));
ui.btnHome.addEventListener('click', () => send({ type: 'nav', action: 'home' }));
ui.btnRecents.addEventListener('click', () => send({ type: 'nav', action: 'recents' }));

ui.btnSendText.addEventListener('click', () => {
  const text = ui.textInput.value.trim();
  if (!text) return;
  send({ type: 'text', text });
  ui.textInput.value = '';
});

ui.textInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') ui.btnSendText.click();
});

ui.btnDisconnect.addEventListener('click', () => {
  pc?.close();
  ws?.close();
  // Opened from the dashboard → go back to it. Otherwise reset to home.
  if (new URLSearchParams(location.search).get('view')) {
    location.href = 'dashboard.html';
    return;
  }
  showScreen('home');
  ui.sessionBox.classList.add('hidden');
});

// ── Boot ──────────────────────────────────────────────────────────────────────
detectRole();
