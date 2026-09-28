// D.project — device dashboard
// Lists every live session (device), shows status, and opens a viewer per device.

const isLocal = ['localhost', '127.0.0.1'].includes(location.hostname) || /^\d+\.\d+\.\d+\.\d+$/.test(location.hostname);
const SERVER_HTTP = (!isLocal && window.DPROJECT_SERVER) || location.origin;

const el = {
  gate:       document.getElementById('gate'),
  keyInput:   document.getElementById('key-input'),
  btnUnlock:  document.getElementById('btn-unlock'),
  gateError:  document.getElementById('gate-error'),
  dash:       document.getElementById('dash'),
  dashSub:    document.getElementById('dash-sub'),
  btnAdd:     document.getElementById('btn-add'),
  btnLock:    document.getElementById('btn-lock'),
  addBox:     document.getElementById('add-box'),
  addLink:    document.getElementById('add-link'),
  btnAddCopy: document.getElementById('btn-add-copy'),
  addQr:      document.getElementById('add-qr'),
  grid:       document.getElementById('device-grid'),
  empty:      document.getElementById('empty'),
};

let dashKey = null;
let pollTimer = null;

// ── Key gate ──────────────────────────────────────────────────────────────────
// The key is kept only in this tab (sessionStorage), never sent anywhere but our server.
function boot() {
  const saved = sessionStorage.getItem('dp_key');
  if (saved) { dashKey = saved; unlock(true); }
}

el.btnUnlock.addEventListener('click', () => tryKey(el.keyInput.value.trim()));
el.keyInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') el.btnUnlock.click(); });

async function tryKey(key) {
  if (!key) return;
  el.gateError.textContent = 'Checking…';
  const ok = await fetchSessions(key);
  if (ok) {
    dashKey = key;
    try { sessionStorage.setItem('dp_key', key); } catch {}
    unlock(false);
  }
}

function unlock(silent) {
  el.gate.classList.remove('active');
  el.dash.classList.add('active');
  startPolling();
  if (!silent) el.gateError.textContent = '';
}

el.btnLock.addEventListener('click', () => {
  try { sessionStorage.removeItem('dp_key'); } catch {}
  dashKey = null;
  if (pollTimer) clearInterval(pollTimer);
  el.dash.classList.remove('active');
  el.gate.classList.add('active');
  el.keyInput.value = '';
});

// ── Data ──────────────────────────────────────────────────────────────────────
// Returns true on success, false (and shows an error) otherwise.
async function fetchSessions(key) {
  try {
    const res = await fetch(`${SERVER_HTTP}/api/sessions`, { headers: { 'x-dashboard-key': key } });
    if (res.status === 401) { el.gateError.textContent = 'Wrong key.'; return false; }
    if (res.status === 503) { el.gateError.textContent = 'Server has no DASHBOARD_KEY set.'; return false; }
    if (!res.ok) { el.gateError.textContent = `Server error (${res.status}).`; return false; }
    const data = await res.json();
    render(data.sessions || []);
    return true;
  } catch (err) {
    el.gateError.textContent = 'Cannot reach the server.';
    return false;
  }
}

function startPolling() {
  fetchSessions(dashKey);
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(() => fetchSessions(dashKey), 3000);
}

// ── Render ──────────────────────────────────────────────────────────────────────
function render(sessions) {
  const online = sessions.filter(s => s.phoneConnected).length;
  el.dashSub.textContent = `${sessions.length} device${sessions.length === 1 ? '' : 's'} · ${online} online`;
  el.empty.classList.toggle('hidden', sessions.length > 0);

  // Sort: connected phones first, then most recently created.
  sessions.sort((a, b) =>
    (b.phoneConnected - a.phoneConnected) || (b.createdAt - a.createdAt));

  el.grid.innerHTML = '';
  for (const s of sessions) el.grid.appendChild(card(s));
}

function card(s) {
  const div = document.createElement('div');
  div.className = 'device-card';

  const online = s.phoneConnected;
  const since = s.phoneSince ? timeAgo(s.phoneSince) : '—';
  const screen = s.screen ? `${s.screen.w}×${s.screen.h}` : 'unknown';

  div.innerHTML = `
    <div class="top">
      <span class="dot ${online ? 'on' : 'off'}"></span>
      <span class="device-name">${esc(s.device || 'Unknown device')}</span>
    </div>
    <div class="device-meta">
      Session <span class="card-id">${esc(s.id)}</span><br/>
      Status: <span>${online ? 'Online' : 'Offline'}</span> · connected ${since}<br/>
      Screen: <span>${screen}</span>${s.viewerConnected ? '<br/>Being viewed now' : ''}
    </div>
    <div class="card-actions">
      <button class="open" ${online ? '' : 'disabled'}>Open</button>
    </div>`;

  const openBtn = div.querySelector('.open');
  openBtn.addEventListener('click', () => {
    window.open(`index.html?view=${encodeURIComponent(s.id)}`, '_blank');
  });
  return div;
}

// ── Add a device ────────────────────────────────────────────────────────────────
el.btnAdd.addEventListener('click', async () => {
  if (!el.addBox.classList.contains('hidden')) {
    el.addBox.classList.add('hidden');
    return;
  }
  const res = await fetch(`${SERVER_HTTP}/api/session`);
  const { sessionId, joinUrl } = await res.json();
  // On a deployed dashboard the server's joinUrl points at its own host; the phone
  // should open the public web client instead.
  const link = isLocal ? joinUrl : `${location.origin}/?s=${sessionId}`;
  el.addLink.value = link;
  el.addQr.innerHTML = `<img src="https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=${encodeURIComponent(link)}" alt="QR" style="border-radius:6px" />`;
  el.addBox.classList.remove('hidden');
});

el.btnAddCopy.addEventListener('click', () => {
  navigator.clipboard.writeText(el.addLink.value);
  el.btnAddCopy.textContent = 'Copied!';
  setTimeout(() => (el.btnAddCopy.textContent = 'Copy'), 1500);
});

// ── Helpers ─────────────────────────────────────────────────────────────────────
function esc(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function timeAgo(ts) {
  const secs = Math.round((Date.now() - ts) / 1000);
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  return `${hrs}h ago`;
}

boot();
