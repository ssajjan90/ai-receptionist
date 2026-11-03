#!/usr/bin/env node
// A local WhatsApp conversation simulator for exercising the full tenant setup +
// AI-reply pipeline without a real WhatsApp Business number or Meta sandbox.
//
// What it does:
//  - Serves a one-page UI (Setup wizard + Chat) at http://localhost:4000
//  - Proxies the Setup wizard's calls to the real backend (register, verify-otp,
//    onboarding, WhatsApp connect) so the browser never needs CORS or a JWT of its own
//  - Builds a correctly-shaped, HMAC-signed Meta webhook payload for each chat message
//    and POSTs it to the backend's real /webhooks/whatsapp endpoint
//  - Stands in for the WhatsApp Graph API: the backend's outbound AI replies
//    (WhatsAppNotificationService) land on this server's /:phoneNumberId/messages
//    endpoint, which the Chat panel polls to render as bot bubbles
//
// Setup:
//  1. Run the backend locally (mvn spring-boot:run, or docker compose up) with
//     WHATSAPP_API_URL=http://localhost:4000 so outbound sends land here instead of
//     the real Meta API. WHATSAPP_APP_SECRET must match this script's default
//     ("test-app-secret", i.e. application.yml's own local default) unless you
//     override both with the same value.
//  2. node scripts/whatsapp-simulator.js
//  3. Open http://localhost:4000
//
// Uses only Node's built-in modules — no npm install required.

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.SIMULATOR_PORT || 4000;
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8081';
const WHATSAPP_APP_SECRET = process.env.WHATSAPP_APP_SECRET || 'test-app-secret';

// phoneNumberId -> [{ to, body, at }]
const outbox = new Map();

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(payload);
}

async function proxyToBackend(res, method, path, body, authHeader) {
  const headers = { 'Content-Type': 'application/json' };
  if (authHeader) headers.Authorization = authHeader;
  try {
    const backendRes = await fetch(BACKEND_URL + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await backendRes.text();
    res.writeHead(backendRes.status, { 'Content-Type': 'application/json' });
    res.end(text);
  } catch (err) {
    sendJson(res, 502, {
      success: false,
      errorCode: 'SIMULATOR_BACKEND_UNREACHABLE',
      message: `Could not reach backend at ${BACKEND_URL}${path}: ${err.message}`,
    });
  }
}

function signPayload(rawBody) {
  const mac = crypto.createHmac('sha256', WHATSAPP_APP_SECRET);
  mac.update(rawBody);
  return 'sha256=' + mac.digest('hex');
}

async function sendCustomerMessage(res, { phoneNumberId, customerPhone, text }) {
  const payload = {
    object: 'whatsapp_business_account',
    entry: [
      {
        id: 'simulator-entry',
        changes: [
          {
            field: 'messages',
            value: {
              messaging_product: 'whatsapp',
              metadata: { phone_number_id: phoneNumberId },
              messages: [
                {
                  id: 'wamid.sim.' + crypto.randomUUID(),
                  from: customerPhone,
                  type: 'text',
                  text: { body: text },
                },
              ],
            },
          },
        ],
      },
    ],
  };
  const rawBody = JSON.stringify(payload);
  const signature = signPayload(rawBody);

  try {
    const backendRes = await fetch(BACKEND_URL + '/webhooks/whatsapp', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Hub-Signature-256': signature,
      },
      body: rawBody,
    });
    sendJson(res, 200, { ok: backendRes.ok, backendStatus: backendRes.status });
  } catch (err) {
    sendJson(res, 502, { ok: false, error: `Could not reach backend: ${err.message}` });
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  // Mock WhatsApp Graph API — POST {WHATSAPP_API_URL}/{phoneNumberId}/messages.
  // WhatsAppNotificationService sends the AI's replies here when WHATSAPP_API_URL
  // points at this server; capture them for the Chat panel to poll.
  const graphMatch = url.pathname.match(/^\/([^/]+)\/messages$/);
  if (req.method === 'POST' && graphMatch) {
    const phoneNumberId = graphMatch[1];
    try {
      const body = await readJsonBody(req);
      const list = outbox.get(phoneNumberId) || [];
      list.push({ to: body.to, body: body.text && body.text.body, at: Date.now() });
      outbox.set(phoneNumberId, list);
      sendJson(res, 200, { messaging_product: 'whatsapp' });
    } catch (err) {
      sendJson(res, 400, { error: 'invalid body' });
    }
    return;
  }

  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(HTML);
    return;
  }

  if (req.method === 'GET' && url.pathname === '/api/poll') {
    const phoneNumberId = url.searchParams.get('phoneNumberId') || '';
    const from = parseInt(url.searchParams.get('from') || '0', 10);
    const list = outbox.get(phoneNumberId) || [];
    sendJson(res, 200, { messages: list.slice(from), total: list.length });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/api/send-customer-message') {
    try {
      const body = await readJsonBody(req);
      await sendCustomerMessage(res, body);
    } catch (err) {
      sendJson(res, 400, { ok: false, error: err.message });
    }
    return;
  }

  if (req.method === 'POST' && url.pathname.startsWith('/api/proxy/')) {
    try {
      const body = await readJsonBody(req);
      const backendPath = url.pathname.replace('/api/proxy', '') + url.search;
      await proxyToBackend(res, 'POST', backendPath, body, req.headers.authorization);
    } catch (err) {
      sendJson(res, 400, { success: false, message: err.message });
    }
    return;
  }

  if (req.method === 'PUT' && url.pathname.startsWith('/api/proxy/')) {
    try {
      const body = await readJsonBody(req);
      const backendPath = url.pathname.replace('/api/proxy', '') + url.search;
      await proxyToBackend(res, 'PUT', backendPath, body, req.headers.authorization);
    } catch (err) {
      sendJson(res, 400, { success: false, message: err.message });
    }
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

server.listen(PORT, () => {
  console.log(`WhatsApp simulator running at http://localhost:${PORT}`);
  console.log(`Proxying to backend at ${BACKEND_URL}`);
  console.log(`Signing webhook payloads with WHATSAPP_APP_SECRET=${WHATSAPP_APP_SECRET === 'test-app-secret' ? '(default) test-app-secret' : '(custom, from env)'}`);
  console.log('Make sure the backend is running with WHATSAPP_API_URL=http://localhost:' + PORT);
});

const HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>WhatsApp Simulator — ai-receptionist</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root {
    color-scheme: light dark;
    --bg: #f4f5f7; --panel: #ffffff; --border: #dde1e6; --text: #1a1d21;
    --muted: #6b7280; --accent: #128C7E; --accent-dark: #0e6b60;
    --bubble-customer: #e9edf1; --bubble-bot: #d9fdd3; --error: #c0392b;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #16181c; --panel: #202329; --border: #33373f; --text: #ecedee;
      --muted: #9aa0a6; --bubble-customer: #2c2f36; --bubble-bot: #1f4d3a;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: var(--bg); color: var(--text);
  }
  header {
    padding: 14px 20px; background: var(--accent); color: #fff;
    font-weight: 600; font-size: 15px;
  }
  header span { opacity: .85; font-weight: 400; font-size: 13px; }
  .layout { display: grid; grid-template-columns: 380px 1fr; gap: 16px; padding: 16px; align-items: start; }
  @media (max-width: 860px) { .layout { grid-template-columns: 1fr; } }
  .panel {
    background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
    padding: 16px; margin-bottom: 16px;
  }
  .panel h2 { margin: 0 0 12px; font-size: 14px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); }
  .step { margin-bottom: 14px; padding-bottom: 14px; border-bottom: 1px solid var(--border); }
  .step:last-child { border-bottom: none; margin-bottom: 0; padding-bottom: 0; }
  .step-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; display: flex; align-items: center; gap: 6px; }
  .step-status { font-size: 11px; padding: 1px 7px; border-radius: 10px; background: var(--border); color: var(--muted); }
  .step-status.done { background: var(--accent); color: #fff; }
  label { display: block; font-size: 12px; color: var(--muted); margin: 8px 0 3px; }
  input, textarea, select {
    width: 100%; padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px;
    background: transparent; color: var(--text); font-size: 13px; font-family: inherit;
  }
  button {
    background: var(--accent); color: #fff; border: none; border-radius: 6px;
    padding: 8px 14px; font-size: 13px; font-weight: 600; cursor: pointer; margin-top: 10px;
  }
  button:hover { background: var(--accent-dark); }
  button:disabled { opacity: .5; cursor: not-allowed; }
  button.secondary { background: transparent; color: var(--accent); border: 1px solid var(--accent); }
  .hint { font-size: 11px; color: var(--muted); margin-top: 4px; }
  .msg { font-size: 12px; margin-top: 8px; padding: 6px 8px; border-radius: 6px; }
  .msg.ok { background: rgba(18,140,126,.12); color: var(--accent); }
  .msg.err { background: rgba(192,57,43,.12); color: var(--error); }
  .kb-list { font-size: 12px; color: var(--muted); margin-top: 6px; }
  .kb-row { display: flex; gap: 6px; margin-top: 6px; }
  .kb-row input { flex: 1; }
  .chat-panel { display: flex; flex-direction: column; height: calc(100vh - 100px); }
  .chat-header { display: flex; gap: 10px; align-items: end; flex-wrap: wrap; margin-bottom: 10px; }
  .chat-header > div { flex: 1; min-width: 160px; }
  .chat-log { flex: 1; overflow-y: auto; background: var(--panel); border: 1px solid var(--border);
    border-radius: 10px; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
  .bubble { max-width: 70%; padding: 8px 12px; border-radius: 12px; font-size: 13px; line-height: 1.4; white-space: pre-wrap; }
  .bubble.customer { align-self: flex-end; background: var(--bubble-bot); border-bottom-right-radius: 3px; }
  .bubble.bot { align-self: flex-start; background: var(--bubble-customer); border-bottom-left-radius: 3px; }
  .bubble .meta { font-size: 10px; color: var(--muted); margin-top: 3px; }
  .composer { display: flex; gap: 8px; margin-top: 10px; }
  .composer input { flex: 1; }
  .composer button { margin-top: 0; }
  .empty { color: var(--muted); font-size: 13px; text-align: center; margin-top: 40px; }
</style>
</head>
<body>
<header>WhatsApp Simulator <span>&mdash; local ai-receptionist test harness</span></header>
<div class="layout">
  <div>
    <div class="panel">
      <h2>Setup</h2>

      <div class="step" id="step-register">
        <div class="step-title">1. Register tenant <span class="step-status" id="status-register">pending</span></div>
        <label>Business name</label><input id="businessName" value="Suresh Stores">
        <label>Owner name</label><input id="ownerName" value="Suresh">
        <label>Owner phone (E.164)</label><input id="ownerPhone" value="">
        <label>Business phone (E.164)</label><input id="businessPhone" value="">
        <label>Email</label><input id="email" value="">
        <label>Password</label><input id="password" type="password" value="password123">
        <button onclick="doRegister()">Register</button>
        <div class="msg" id="msg-register"></div>
      </div>

      <div class="step" id="step-otp">
        <div class="step-title">2. Verify OTP <span class="step-status" id="status-otp">pending</span></div>
        <div class="hint">Check the backend's console/log output for a line like "Sending OTP 123456 to +91...".</div>
        <label>OTP</label><input id="otp" placeholder="6-digit code">
        <button onclick="doVerifyOtp()">Verify</button>
        <button class="secondary" onclick="doResendOtp()">Resend OTP</button>
        <div class="msg" id="msg-otp"></div>
      </div>

      <div class="step" id="step-onboarding">
        <div class="step-title">3. Complete onboarding <span class="step-status" id="status-onboarding">pending</span></div>
        <label>Shop name</label><input id="shopName" value="Suresh Stores">
        <label>Location</label><input id="location" value="Bengaluru">
        <label>Business hours</label><input id="businessHours" value="9am - 9pm">
        <label>Sample product name / price</label>
        <div class="kb-row"><input id="prod1Name" value="Masala Tea"><input id="prod1Price" value="25"></div>
        <label>Sample FAQ question / answer</label>
        <div class="kb-row"><input id="faq1Q" value="What are your hours?"><input id="faq1A" value="9am to 9pm daily"></div>
        <button onclick="doOnboarding()">Complete onboarding</button>
        <div class="msg" id="msg-onboarding"></div>
      </div>

      <div class="step" id="step-connect">
        <div class="step-title">4. Connect WhatsApp <span class="step-status" id="status-connect">pending</span></div>
        <label>WABA ID</label><input id="wabaId" value="sim-waba-id">
        <label>Phone number ID</label><input id="phoneNumberId" value="sim-phone-id">
        <button onclick="doConnect()">Connect</button>
        <div class="msg" id="msg-connect"></div>
      </div>

      <div class="step">
        <div class="step-title">Add another KB entry (optional)</div>
        <select id="kbType"><option value="FAQ">FAQ</option><option value="PRODUCT">PRODUCT</option><option value="SERVICE">SERVICE</option></select>
        <label>Question (FAQ) / Product or service name</label><input id="kbQOrName">
        <label>Answer / Price</label><input id="kbAOrPrice">
        <button onclick="doAddKbEntry()">Add</button>
        <div class="msg" id="msg-kb"></div>
      </div>
    </div>
  </div>

  <div class="chat-panel">
    <div class="panel" style="margin-bottom:10px;">
      <div class="chat-header">
        <div><label>Customer phone (E.164)</label><input id="customerPhone" value="+919876500001"></div>
        <div><label>Chatting with phoneNumberId</label><input id="chatPhoneNumberId" value="sim-phone-id"></div>
        <button class="secondary" onclick="clearChat()">Clear</button>
      </div>
    </div>
    <div class="chat-log" id="chatLog"><div class="empty">No messages yet. Complete Setup, then send a message below.</div></div>
    <div class="composer">
      <input id="chatInput" placeholder="Type a customer message..." onkeydown="if(event.key==='Enter') sendChat()">
      <button onclick="sendChat()">Send</button>
    </div>
  </div>
</div>

<script>
let tenantId = null;
let jwt = null;
let pollTimer = null;
let seenCount = 0;

function rand6() { return Math.floor(100000 + Math.random() * 900000); }
function e164(prefix) { return prefix + rand6() + rand6().toString().slice(0,4); }
document.getElementById('ownerPhone').value = e164('+9198');
document.getElementById('businessPhone').value = e164('+9197');
document.getElementById('email').value = 'owner' + Date.now() + '@example.com';

function setStatus(step, ok) {
  const el = document.getElementById('status-' + step);
  el.textContent = ok ? 'done' : 'pending';
  el.classList.toggle('done', ok);
}
function showMsg(step, text, ok) {
  const el = document.getElementById('msg-' + step);
  el.textContent = text;
  el.className = 'msg ' + (ok ? 'ok' : 'err');
}

async function proxy(method, path, body, auth) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) headers.Authorization = 'Bearer ' + auth;
  const res = await fetch('/api/proxy' + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json();
  if (!res.ok || json.success === false) {
    throw new Error(json.message || json.errorCode || ('HTTP ' + res.status));
  }
  return json.data;
}

async function doRegister() {
  try {
    const data = await proxy('POST', '/v1/tenants/register', {
      businessName: document.getElementById('businessName').value,
      ownerName: document.getElementById('ownerName').value,
      ownerPhone: document.getElementById('ownerPhone').value,
      businessPhone: document.getElementById('businessPhone').value,
      email: document.getElementById('email').value,
      password: document.getElementById('password').value,
    });
    showMsg('register', 'Registered. Status: ' + data.status + '. Check backend logs for the OTP.', true);
    setStatus('register', true);
  } catch (e) { showMsg('register', e.message, false); }
}

async function doVerifyOtp() {
  try {
    const data = await proxy('POST', '/v1/tenants/verify-otp', {
      ownerPhone: document.getElementById('ownerPhone').value,
      otp: document.getElementById('otp').value,
    });
    jwt = data.jwt; tenantId = data.tenantId;
    showMsg('otp', 'Verified. tenantId=' + tenantId, true);
    setStatus('otp', true);
  } catch (e) { showMsg('otp', e.message, false); }
}

async function doResendOtp() {
  try {
    await proxy('POST', '/v1/tenants/resend-otp?ownerPhone=' + encodeURIComponent(document.getElementById('ownerPhone').value));
    showMsg('otp', 'New OTP sent — check backend logs.', true);
  } catch (e) { showMsg('otp', e.message, false); }
}

async function doOnboarding() {
  if (!tenantId || !jwt) return showMsg('onboarding', 'Verify OTP first.', false);
  try {
    await proxy('PUT', '/v1/tenants/' + tenantId + '/onboarding', {
      shopName: document.getElementById('shopName').value,
      location: document.getElementById('location').value,
      businessHours: document.getElementById('businessHours').value,
      preferredLanguage: 'en',
      topProducts: [{ productName: document.getElementById('prod1Name').value, price: document.getElementById('prod1Price').value }],
      commonFaqs: [{ question: document.getElementById('faq1Q').value, answer: document.getElementById('faq1A').value }],
    }, jwt);
    showMsg('onboarding', 'Onboarding complete.', true);
    setStatus('onboarding', true);
  } catch (e) { showMsg('onboarding', e.message, false); }
}

async function doConnect() {
  if (!tenantId || !jwt) return showMsg('connect', 'Verify OTP first.', false);
  try {
    const phoneNumberId = document.getElementById('phoneNumberId').value;
    await proxy('POST', '/v1/tenants/' + tenantId + '/whatsapp/connect', {
      wabaId: document.getElementById('wabaId').value,
      phoneNumberId,
      displayPhoneNumber: document.getElementById('businessPhone').value,
    }, jwt);
    showMsg('connect', 'Connected. Ready to chat.', true);
    setStatus('connect', true);
    document.getElementById('chatPhoneNumberId').value = phoneNumberId;
    startPolling();
  } catch (e) { showMsg('connect', e.message, false); }
}

async function doAddKbEntry() {
  if (!tenantId || !jwt) return showMsg('kb', 'Verify OTP first.', false);
  const type = document.getElementById('kbType').value;
  const qOrName = document.getElementById('kbQOrName').value;
  const aOrPrice = document.getElementById('kbAOrPrice').value;
  const body = type === 'FAQ'
    ? { type, question: qOrName, answer: aOrPrice }
    : { type, productName: qOrName, price: aOrPrice };
  try {
    await proxy('POST', '/v1/tenants/' + tenantId + '/knowledge-base', body, jwt);
    showMsg('kb', 'Added.', true);
    document.getElementById('kbQOrName').value = '';
    document.getElementById('kbAOrPrice').value = '';
  } catch (e) { showMsg('kb', e.message, false); }
}

function appendBubble(kind, text) {
  const log = document.getElementById('chatLog');
  const empty = log.querySelector('.empty');
  if (empty) empty.remove();
  const div = document.createElement('div');
  div.className = 'bubble ' + kind;
  div.textContent = text;
  const meta = document.createElement('div');
  meta.className = 'meta';
  meta.textContent = new Date().toLocaleTimeString();
  div.appendChild(meta);
  log.appendChild(div);
  log.scrollTop = log.scrollHeight;
}

async function sendChat() {
  const input = document.getElementById('chatInput');
  const text = input.value.trim();
  if (!text) return;
  const phoneNumberId = document.getElementById('chatPhoneNumberId').value;
  const customerPhone = document.getElementById('customerPhone').value;
  appendBubble('customer', text);
  input.value = '';
  await fetch('/api/send-customer-message', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumberId, customerPhone, text }),
  });
  startPolling();
}

function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    const phoneNumberId = document.getElementById('chatPhoneNumberId').value;
    if (!phoneNumberId) return;
    const res = await fetch('/api/poll?phoneNumberId=' + encodeURIComponent(phoneNumberId) + '&from=' + seenCount);
    const data = await res.json();
    for (const m of data.messages) appendBubble('bot', m.body || '(empty reply)');
    seenCount = data.total;
  }, 1200);
}

function clearChat() {
  document.getElementById('chatLog').innerHTML = '<div class="empty">No messages yet.</div>';
  seenCount = 0;
}

startPolling();
</script>
</body>
</html>`;
