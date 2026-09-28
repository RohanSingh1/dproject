# D.project — Remote Phone Control

Control your Android phone from any browser. Generate a link, open it on your phone, and your phone's screen streams live to your browser. Taps, swipes, keyboard input and nav gestures relay back to the phone in real time.

## Architecture

```
Browser (web-client)
  │  WebSocket (signalling)
  ▼
Node.js Server (server/)
  │  WebSocket (signalling)
  ▼
Android App (android/)
  ├── MediaProjection → screen frames → WebRTC video track → browser
  └── AccessibilityService ← tap/swipe/key commands ← browser
```

## Quick Start

### 1. Server
```bash
cd server
npm install
npm start
# Runs on http://localhost:3000
```

### 2. Web client
Served automatically by the server at `http://localhost:3000`.

### 3. Android app
1. Open `android/` in Android Studio
2. Replace `your-server.com` in `StreamingService.kt` with your deployed server URL
3. Build & install on your phone (min Android 8.0 / API 26)
4. Grant permissions when prompted:
   - **Screen capture** — appears when you tap Connect
   - **Accessibility** — tap the button in the app, enable "D.project" in Settings

### 4. Use it
1. Open `http://localhost:3000` in your browser
2. Click **Generate Link** — a session ID and QR appear
3. Scan the QR on your phone OR paste the session ID into the Android app
4. Your phone screen appears in the browser — click to tap, drag to swipe

## Device Dashboard
Open `/dashboard.html` to see all your connected devices in one place: online/offline status, device name, screen size, and when each connected. Click **Open** on any online device to control it in a new tab.

- The dashboard is protected by a key. Set `DASHBOARD_KEY` on the server (Render → Environment).
- The key is entered once per browser tab and stored only in that tab (`sessionStorage`). It is sent only to your own server, in the `x-dashboard-key` header.
- Use **+ Add device** to generate a join link/QR for enrolling a new phone.

## Deployment
- Deploy `server/` to Railway or Render (free tier)
- Update `SERVER_WS` / `SERVER_HTTP` in `web-client/client.js` OR serve the web client from the same server (already configured)
- Update `serverUrl` in `android/.../StreamingService.kt`

## Tech Stack
- **Server**: Node.js + Express + ws
- **Web client**: Vanilla JS + WebRTC API (no framework)
- **Android**: Kotlin + MediaProjection + AccessibilityService + WebRTC (Google pre-built AAR) + OkHttp

## Permissions Used
| Permission | Why |
|---|---|
| `INTERNET` | WebSocket + WebRTC |
| `FOREGROUND_SERVICE` | Keep streaming alive in background |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ requirement |
| Accessibility Service | Replay taps/swipes from browser |
| Screen Capture (runtime) | MediaProjection — user grants per session |

## Known Limits
- Screen-share on mobile browsers is patchy; use the APK for reliable capture
- Accessibility Service cannot be on the Play Store (use sideload / direct APK install)
- High-motion content (video) will lag on slow connections — tune WebRTC bitrate in `WebRTCManager` if needed
