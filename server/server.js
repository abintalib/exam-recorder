// Local control server + admin dashboard for testing.
// Later: swap state/commands for Firebase (or AWS) and makeUploadUrl() for an S3 presigned URL.
// Run: node server.js   (no dependencies)

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = process.env.PORT || 8080;
const UPLOAD_DIR = path.join(__dirname, 'uploads');
const OFFLINE_AFTER_MS = 10_000;
// Shared secret the Noon exam system sends with start/stop calls
const EXAM_TOKEN = process.env.EXAM_TOKEN || 'change-me';

const state = {
  devices: {}, // id -> { id, code, studentId, status, detail, pendingUploads, lastUpload, lastSeen }
  session: null, // { id, name, examPackage, recording, startedAt, stoppedAt }
};

const safe = (s) => String(s || '').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80);

function slug(name) {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const ts = `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
  return `${safe(name).toLowerCase() || 'session'}-${ts}`;
}

// Mock "S3": the tablet PUTs the file back to this server.
// S3 version: return a presigned PUT URL for Bucket + key.
function makeUploadUrl(key, host) {
  return `http://${host}/upload/${key}`;
}

function desiredFor(device) {
  const s = state.session;
  if (!s || !s.recording) return { action: 'idle' };
  return {
    action: 'record',
    sessionId: s.id,
    studentId: device.studentId || `unassigned-${device.code}`,
    examPackage: s.examPackage || '',
  };
}

function readJson(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      try { resolve(JSON.parse(body || '{}')); } catch { resolve({}); }
    });
  });
}

function send(res, code, data, type = 'application/json') {
  res.writeHead(code, { 'Content-Type': type });
  res.end(type === 'application/json' ? JSON.stringify(data) : data);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const parts = url.pathname.split('/').filter(Boolean);

  // ---- Admin ----
  if (req.method === 'GET' && url.pathname === '/') {
    return send(res, 200, fs.readFileSync(path.join(__dirname, 'dashboard.html')), 'text/html');
  }
  if (req.method === 'GET' && url.pathname === '/api/state') {
    const now = Date.now();
    const devices = Object.values(state.devices).map((d) => ({ ...d, online: now - d.lastSeen < OFFLINE_AFTER_MS }));
    return send(res, 200, { session: state.session, devices });
  }
  if (req.method === 'POST' && url.pathname === '/api/assign') {
    const { deviceId, studentId } = await readJson(req);
    if (!state.devices[deviceId]) return send(res, 404, { error: 'unknown device' });
    state.devices[deviceId].studentId = safe(studentId) || null;
    return send(res, 200, { ok: true });
  }
  if (req.method === 'POST' && url.pathname === '/api/session/start') {
    const { name, examPackage } = await readJson(req);
    state.session = { id: slug(name), name: name || 'Session', examPackage: (examPackage || '').trim(), recording: true, startedAt: Date.now() };
    console.log('START', state.session.id);
    return send(res, 200, state.session);
  }
  if (req.method === 'POST' && url.pathname === '/api/session/stop') {
    if (state.session) { state.session.recording = false; state.session.stoppedAt = Date.now(); }
    console.log('STOP', state.session && state.session.id);
    return send(res, 200, state.session || {});
  }

  // ---- Noon exam system webhook ----
  // POST /api/exam/start {examId, examName}  /  POST /api/exam/stop {examId}
  // Header: X-Exam-Token: <EXAM_TOKEN>
  if (req.method === 'POST' && (url.pathname === '/api/exam/start' || url.pathname === '/api/exam/stop')) {
    if (req.headers['x-exam-token'] !== EXAM_TOKEN) return send(res, 401, { error: 'bad token' });
    const { examId, examName } = await readJson(req);
    if (!examId) return send(res, 400, { error: 'examId required' });
    if (url.pathname.endsWith('/start')) {
      state.session = { id: safe(examId), name: examName || String(examId), examPackage: '', recording: true, startedAt: Date.now(), source: 'exam' };
      console.log('EXAM START', state.session.id);
    } else if (state.session && state.session.id === safe(examId)) {
      state.session.recording = false;
      state.session.stoppedAt = Date.now();
      console.log('EXAM STOP', state.session.id);
    }
    return send(res, 200, { ok: true, session: state.session });
  }

  // ---- Tablet ----
  // POST /api/devices/:id/heartbeat  {code, status, detail, pendingUploads, lastUpload}
  if (req.method === 'POST' && parts[0] === 'api' && parts[1] === 'devices' && parts[3] === 'heartbeat') {
    const id = safe(parts[2]);
    const body = await readJson(req);
    const d = (state.devices[id] ||= { id, studentId: null });
    Object.assign(d, {
      code: safe(body.code),
      status: body.status || 'idle',
      detail: body.detail || '',
      pendingUploads: body.pendingUploads || 0,
      lastUpload: body.lastUpload || d.lastUpload || null,
      lastSeen: Date.now(),
    });
    return send(res, 200, desiredFor(d));
  }
  // POST /api/devices/:id/upload-url  {studentId, sessionId, fileName}
  if (req.method === 'POST' && parts[0] === 'api' && parts[1] === 'devices' && parts[3] === 'upload-url') {
    const { studentId, sessionId, fileName } = await readJson(req);
    const key = `${safe(studentId)}/${safe(sessionId)}/${safe(fileName)}`;
    return send(res, 200, { key, url: makeUploadUrl(key, req.headers.host) });
  }

  // ---- Mock S3 ----
  if (req.method === 'PUT' && parts[0] === 'upload' && parts.length === 4) {
    const dest = path.join(UPLOAD_DIR, ...parts.slice(1).map(safe));
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const out = fs.createWriteStream(dest);
    req.pipe(out);
    out.on('finish', () => { console.log('UPLOADED', dest, fs.statSync(dest).size, 'bytes'); send(res, 200, { ok: true }); });
    out.on('error', (e) => send(res, 500, { error: e.message }));
    return;
  }

  send(res, 404, { error: 'not found' });
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`Dashboard: http://localhost:${PORT}`);
  ips.forEach((ip) => console.log(`Tablets use: http://${ip}:${PORT}`));
});
