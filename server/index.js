const express = require('express');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const http = require('http');
const os = require('os');
const path = require('path');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Detect the LAN IP so QR codes point to the right address on mobile
function getLanIP() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const iface of ifaces) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address;
    }
  }
  return 'localhost';
}
const LAN_IP = getLanIP();

// Allow the Vercel-hosted web client to call the API cross-origin
app.use((req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*');
  next();
});

// Serve the web client statically
app.use(express.static(path.join(__dirname, '../web-client')));

// sessions: Map<sessionId, { browser: ws | null, phone: ws | null }>
const sessions = new Map();

// Generate a new session ID — returns joinUrl using LAN IP so phones can reach it
app.get('/api/session', (req, res) => {
  const id = crypto.randomUUID().slice(0, 8).toUpperCase();
  sessions.set(id, { browser: null, phone: null });
  const PORT = process.env.PORT || 3000;
  const joinUrl = `http://${LAN_IP}:${PORT}/?s=${id}`;
  console.log(`[session] Created: ${id}  join: ${joinUrl}`);
  res.json({ sessionId: id, joinUrl });
});

// Health check
app.get('/api/health', (_, res) => res.json({ ok: true }));

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const sessionId = url.searchParams.get('session');
  const role = url.searchParams.get('role'); // 'browser' | 'phone'

  if (!sessionId || !sessions.has(sessionId)) {
    ws.send(JSON.stringify({ type: 'error', msg: 'Invalid session' }));
    ws.close();
    return;
  }

  if (role !== 'browser' && role !== 'phone') {
    ws.send(JSON.stringify({ type: 'error', msg: 'Invalid role' }));
    ws.close();
    return;
  }

  const session = sessions.get(sessionId);
  session[role] = ws;
  console.log(`[ws] ${role} joined session ${sessionId}`);

  // Notify the other peer that someone joined
  const other = role === 'browser' ? 'phone' : 'browser';
  if (session[other]?.readyState === 1) {
    session[other].send(JSON.stringify({ type: 'peer-joined', role }));
    ws.send(JSON.stringify({ type: 'peer-joined', role: other }));
  }

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data); } catch { return; }

    const peer = role === 'browser' ? session.phone : session.browser;

    // Relay signalling (offer, answer, ICE) and control events straight through
    if (peer?.readyState === 1) {
      peer.send(JSON.stringify({ ...msg, from: role }));
    }
  });

  ws.on('close', () => {
    console.log(`[ws] ${role} left session ${sessionId}`);
    session[role] = null;
    const peer = role === 'browser' ? session.phone : session.browser;
    if (peer?.readyState === 1) {
      peer.send(JSON.stringify({ type: 'peer-left', role }));
    }
    // Clean up session if both gone
    if (!session.browser && !session.phone) {
      sessions.delete(sessionId);
      console.log(`[session] Removed: ${sessionId}`);
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`D.project server running on http://localhost:${PORT}`));
