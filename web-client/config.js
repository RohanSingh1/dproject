// Signalling server URL. The web client is hosted on Vercel (static), but Vercel
// can't hold WebSocket connections, so the Node server runs on Render.
// Leave empty to use the same origin (local dev: `npm start` in server/).
window.DPROJECT_SERVER = 'https://dproject-server.onrender.com';
