#!/usr/bin/env node
'use strict';
// Polar Bot's "online" light. Discord only shows a bot as online while a program holds a live connection to its
// Gateway, so this small program does just that (and sets "Playing Polar Client"). Everything the bot actually does
// (commands, buttons, tickets, giveaways) keeps running on Cloudflare; if this stops, the bot only looks offline.
//
// Runs on a free bot host (bot-hosting.net and the like), on Render, or on your PC.
// Settings (environment variables, or the token in token.txt next to this file):
//   DISCORD_BOT_TOKEN   the bot's token (required; or paste it into token.txt)
//   STATUS_TEXT         what it's "playing" (default: Polar Client)
//   STATUS_TYPE         0 Playing, 2 Listening to, 3 Watching, 5 Competing in (default 0)
//   PORT                Render sets this; a tiny web page answers there so Render (and Polar Bot's pings) see it's up
// Node 22+ has everything built in; on older Node it uses the "ws" package from package.json (hosts install it).
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

let WS = globalThis.WebSocket;
if (!WS) {
  try {
    WS = require('ws');
  } catch {
    WS = null;
  }
}
function readToken() {
  if (process.env.DISCORD_BOT_TOKEN) return process.env.DISCORD_BOT_TOKEN.trim();
  try {
    return fs.readFileSync(path.join(__dirname, 'token.txt'), 'utf8').replace(/^\uFEFF/, '').trim();
  } catch {
    return '';
  }
}
const TOKEN = readToken();
const API = process.env.DISCORD_API || 'https://discord.com/api/v10';
const STATUS_TEXT = process.env.STATUS_TEXT || 'Polar Client';
const STATUS_TYPE = Number(process.env.STATUS_TYPE || 0);
const PORT = process.env.PORT ? Number(process.env.PORT) : null;
const SELF_URL = process.env.RENDER_EXTERNAL_URL || process.env.SELF_URL || '';
const UA = 'DiscordBot (https://polar-client.polarclientminecraft.workers.dev, 1.0) PolarPresence';
const FATAL = { 4004: 'The bot token is wrong. Reset it on the Bot page and set the new one.', 4010: 'Invalid shard.',
  4011: 'Sharding required.', 4012: 'Invalid API version.', 4013: 'Invalid intents.', 4014: 'Disallowed intents.' };

const s = { ws: null, url: null, resumeUrl: null, sessionId: null, seq: null, beat: null, acked: true, ready: false, user: null,
  since: null, attempts: 0, stopping: false, closedByUs: false };

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

if (!WS) {
  console.error(`Node.js ${process.version} has no WebSocket built in: run "npm install" here first, or use Node.js 22 or newer.`);
  process.exit(1);
}
if (!TOKEN || /PASTE/i.test(TOKEN)) {
  console.error('No bot token: paste it into token.txt (or set DISCORD_BOT_TOKEN), then start again.');
  process.exit(1);
}

async function gatewayUrl() {
  for (let i = 0; ; i++) {
    try {
      const res = await fetch(`${API}/gateway/bot`, { headers: { Authorization: `Bot ${TOKEN}`, 'User-Agent': UA } });
      if (res.status === 401) fatal('The bot token is wrong. Reset it on the Bot page and set the new one.');
      if (res.ok) {
        const d = await res.json();
        if (d.session_start_limit && d.session_start_limit.remaining === 0) {
          log(`Discord's daily connection limit is used up; waiting ${Math.ceil(d.session_start_limit.reset_after / 60000)} minutes.`);
          await sleep(d.session_start_limit.reset_after + 1000);
          continue;
        }
        return d.url;
      }
      log(`Discord answered ${res.status}; trying again.`);
    } catch (e) {
      log(`Can't reach Discord (${e.message}); trying again.`);
    }
    await sleep(Math.min(60000, 2000 * 2 ** i));
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function fatal(msg) {
  console.error(msg);
  process.exit(1);
}

function send(op, d) {
  if (s.ws && s.ws.readyState === 1) s.ws.send(JSON.stringify({ op, d }));
}

function heartbeat() {
  if (!s.acked) {
    log('Discord stopped answering heartbeats; reconnecting.');
    drop(4009);
    return;
  }
  s.acked = false;
  send(1, s.seq);
}

function presence() {
  return { since: null, activities: [{ name: STATUS_TEXT, type: STATUS_TYPE }], status: 'online', afk: false };
}

function onPayload(p) {
  if (p.s !== null && p.s !== undefined) s.seq = p.s;
  if (p.op === 10) { // Hello: start beating, then sign in (or carry on the old session)
    clearInterval(s.beat);
    clearTimeout(s.firstBeat);
    const every = p.d.heartbeat_interval;
    s.acked = true;
    s.firstBeat = setTimeout(() => {
      heartbeat();
      s.beat = setInterval(heartbeat, every);
    }, Math.floor(every * Math.random()));
    if (s.resuming && s.sessionId) send(6, { token: TOKEN, session_id: s.sessionId, seq: s.seq });
    else send(2, { token: TOKEN, intents: 0, properties: { os: process.platform, browser: 'polar-bot', device: 'polar-bot' }, presence: presence() });
  } else if (p.op === 11) {
    s.acked = true;
  } else if (p.op === 1) {
    send(1, s.seq);
  } else if (p.op === 7) { // Discord asks us to reconnect
    drop(4000);
  } else if (p.op === 9) { // the session can't be carried on
    if (!p.d) {
      s.sessionId = null;
      s.seq = null;
    }
    setTimeout(() => drop(4000), 1000 + Math.random() * 4000);
  } else if (p.op === 0) {
    if (p.t === 'READY') {
      s.sessionId = p.d.session_id;
      s.resumeUrl = p.d.resume_gateway_url;
      s.user = p.d.user;
      s.ready = true;
      s.since = Date.now();
      s.attempts = 0;
      log(`Online as ${p.d.user.username} (Playing ${STATUS_TEXT}).`);
    } else if (p.t === 'RESUMED') {
      s.ready = true;
      s.attempts = 0;
      log('Back online.');
    }
  }
}

/** Closes the connection on purpose (a 4000 code keeps the session resumable); the close handler reconnects. */
function drop(code) {
  if (!s.ws) return;
  s.closedByUs = true;
  try {
    s.ws.close(code);
  } catch {
    // already closed
  }
}

async function connect() {
  if (!s.url) s.url = await gatewayUrl();
  s.resuming = Boolean(s.sessionId && s.resumeUrl);
  const base = s.resuming ? s.resumeUrl : s.url;
  const ws = new WS(`${base.replace(/\/$/, '')}/?v=10&encoding=json`);
  s.ws = ws;
  s.closedByUs = false;
  ws.addEventListener('message', (ev) => {
    try {
      onPayload(JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString()));
    } catch (e) {
      log(`Bad message from Discord: ${e.message}`);
    }
  });
  ws.addEventListener('close', (ev) => {
    if (s.ws !== ws) return;
    clearInterval(s.beat);
    clearTimeout(s.firstBeat);
    s.ready = false;
    if (s.stopping) return;
    if (FATAL[ev.code]) fatal(`Discord closed the connection: ${FATAL[ev.code]}`);
    if ([4007, 4009].includes(ev.code) || (!s.closedByUs && [1000, 1001].includes(ev.code))) {
      s.sessionId = null; // start a new session
      s.seq = null;
    }
    const wait = Math.min(60000, 1000 * 2 ** s.attempts++) + Math.random() * 1000;
    log(`Disconnected (${ev.code}${ev.reason ? `: ${ev.reason}` : ''}); reconnecting in ${Math.round(wait / 1000)}s.`);
    if (s.attempts > 3) s.url = null; // ask Discord for a fresh address
    setTimeout(() => connect().catch((e) => log(e.message)), wait);
  });
  ws.addEventListener('error', () => {}); // a close event follows
}

// A tiny web page: Render needs something listening, and its free plan sleeps without visits, so Polar Bot (and this
// program itself) visit it every few minutes.
if (PORT !== null) {
  http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ online: s.ready, bot: s.user && s.user.username, since: s.since }));
  }).listen(PORT, () => log(`Status page on port ${PORT}.`));
}
if (SELF_URL) setInterval(() => fetch(SELF_URL).catch(() => {}), 10 * 60 * 1000).unref();

function stop() {
  s.stopping = true;
  try {
    if (s.ws) s.ws.close(1000);
  } catch {
    // closing anyway
  }
  setTimeout(() => process.exit(0), 300);
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);

connect().catch((e) => fatal(e.message));
