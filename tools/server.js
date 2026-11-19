#!/usr/bin/env node
'use strict';
// Puts the Polar server (capes, cosmetics, badge, friends, settings sync) on Cloudflare's free plan. Run through
// deploy-server.bat:
//   node tools/server.js                         sign in to Cloudflare (first time), deploy, set the secrets, check it
//   node tools/server.js --new-admin-password    the same, with a fresh admin password
//   node tools/server.js --discord               the same, then sets up Polar Bot for the Discord server (discord-bot.bat)
// The first run opens Cloudflare in your browser to sign in. Everything after that is automatic.
const { spawnSync, spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');

const ROOT = path.resolve(__dirname, '..');
const SERVER = path.join(ROOT, 'server');
const PKG = path.join(ROOT, 'launcher', 'package.json');
const PASSWORD_FILE = path.join(SERVER, 'admin-password.txt');
const WRANGLER = ['--yes', 'wrangler@4'];
const WIN = process.platform === 'win32';

function say(s = '') {
  console.log(s);
}

function fail(msg) {
  console.error(`\n${msg}`);
  process.exit(1);
}

/** Like ask(), but what's typed (a token) isn't shown. */
function askHidden(question) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    let shown = false;
    rl._writeToOutput = (s) => {
      if (!shown) {
        shown = true;
        rl.output.write(s);
      }
    };
    rl.question(question, (a) => {
      rl.close();
      process.stdout.write('\n');
      resolve((a || '').trim());
    });
  });
}

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (a) => {
    rl.close();
    resolve((a || '').trim());
  }));
}

/** Runs wrangler. capture: return its output instead of showing it; input: text for its stdin. */
function wrangler(args, { capture = false, input } = {}) {
  const r = spawnSync('npx', [...WRANGLER, ...args], {
    cwd: SERVER, encoding: 'utf8', shell: WIN, input,
    stdio: capture || input !== undefined ? 'pipe' : 'inherit',
    env: { ...process.env, WRANGLER_SEND_METRICS: 'false' },
  });
  if (r.error) fail(`Couldn't run wrangler (Cloudflare's tool): ${r.error.message}`);
  return { ok: r.status === 0, out: `${r.stdout || ''}${r.stderr || ''}` };
}

/** Runs wrangler showing its output live (so its questions can be answered) while also keeping a copy. */
function wranglerLive(args) {
  return new Promise((resolve) => {
    const child = spawn('npx', [...WRANGLER, ...args], { cwd: SERVER, shell: WIN, stdio: ['inherit', 'pipe', 'pipe'],
      env: { ...process.env, WRANGLER_SEND_METRICS: 'false' } });
    let out = '';
    child.stdout.on('data', (d) => { out += d; process.stdout.write(d); });
    child.stderr.on('data', (d) => { out += d; process.stderr.write(d); });
    child.on('error', (e) => fail(`Couldn't run wrangler (Cloudflare's tool): ${e.message}`));
    child.on('close', (code) => resolve({ ok: code === 0, out }));
  });
}

function checkNode() {
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 20) fail(`Your Node.js is ${process.versions.node}; Cloudflare's tool needs 20 or newer. Get the LTS from https://nodejs.org and run this again.`);
}

async function signIn() {
  say('Checking your Cloudflare sign-in…');
  let who = wrangler(['whoami'], { capture: true });
  if (/not authenticated|wrangler login/i.test(who.out) || !who.ok) {
    say("\nYour browser will open so you can sign in to Cloudflare and allow access. (Make a free account there first if you haven't.)");
    const r = wrangler(['login']);
    if (!r.ok) fail("Cloudflare sign-in didn't finish. Run deploy-server.bat again.");
    who = wrangler(['whoami'], { capture: true });
    if (/not authenticated/i.test(who.out)) fail("Still not signed in to Cloudflare. Run deploy-server.bat again.");
  }
  const email = /associated with the email ([^\s]+)/i.exec(who.out);
  say(email ? `Signed in to Cloudflare as ${email[1].replace(/\.$/, '')}.` : 'Signed in to Cloudflare.');
}

function workersUrl(text) {
  const m = /https:\/\/[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev/i.exec(text);
  return m ? m[0] : null;
}

async function deploy() {
  say('\nUploading the Polar server…');
  const r = await wranglerLive(['deploy']);
  if (!r.ok) {
    if (/workers\.dev subdomain|register a workers\.dev/i.test(r.out)) {
      say('\nCloudflare needs a workers.dev name for your account first (it becomes part of the server address).');
      say('Open https://dash.cloudflare.com, go to Workers & Pages, pick a subdomain, then run deploy-server.bat again.');
    }
    fail('The upload failed (see above).');
  }
  let url = workersUrl(r.out);
  while (!url) {
    const typed = await ask('\nPaste the https://….workers.dev address Cloudflare showed above: ');
    url = workersUrl(typed);
  }
  return url;
}

function secretNames() {
  const r = wrangler(['secret', 'list', '--format', 'json'], { capture: true });
  try {
    const start = r.out.indexOf('[');
    return new Set(JSON.parse(r.out.slice(start, r.out.lastIndexOf(']') + 1)).map((s) => s.name));
  } catch {
    return new Set();
  }
}

function putSecret(name, value) {
  const r = wrangler(['secret', 'put', name], { input: `${value}\n` });
  if (!r.ok) fail(`Couldn't save ${name} on Cloudflare:\n${r.out}`);
}

function newPassword() {
  const words = ['ice', 'snow', 'polar', 'frost', 'aurora', 'glacier', 'north', 'storm', 'pine', 'comet', 'nova', 'tundra', 'drift', 'flake', 'crystal', 'orbit'];
  const pick = () => words[crypto.randomInt(words.length)];
  return `${pick()}-${pick()}-${pick()}-${crypto.randomInt(1000, 9999)}`;
}

async function secrets(newAdmin) {
  say('\nSetting up the server secrets…');
  const have = secretNames();
  if (!have.has('TOKEN_SECRET')) {
    // signs players' Polar logins; stays the same on later deploys so nobody gets signed out
    putSecret('TOKEN_SECRET', crypto.randomBytes(32).toString('base64url'));
    say('  made the sign-in key');
  } else {
    say('  sign-in key: already set');
  }
  let password = !newAdmin && fs.existsSync(PASSWORD_FILE) ? fs.readFileSync(PASSWORD_FILE, 'utf8').trim() : '';
  if (!password || !have.has('ADMIN_TOKEN')) {
    password = password || newPassword();
    putSecret('ADMIN_TOKEN', password);
    fs.writeFileSync(PASSWORD_FILE, `${password}\n`);
    say('  saved the admin password');
  } else {
    say('  admin password: already set');
  }
  return password;
}

function rememberUrl(url) {
  const pkg = JSON.parse(fs.readFileSync(PKG, 'utf8'));
  pkg.polar = pkg.polar || {};
  if (pkg.polar.serverUrl === url) return false;
  pkg.polar.serverUrl = url;
  fs.writeFileSync(PKG, `${JSON.stringify(pkg, null, 2)}\n`);
  return true;
}

async function healthy(url) {
  // a brand-new workers.dev address can take a minute to start answering
  for (let i = 0; i < Number(process.env.POLAR_HEALTH_TRIES || 20); i++) {
    try {
      const res = await fetch(`${url}/v1/health`, { signal: AbortSignal.timeout(8000) });
      if (res.ok) {
        const h = await res.json();
        if (h.configured) return h;
      }
    } catch {
      // not up yet
    }
    if (i === 2) say('  (a new address can take a minute to come online…)');
    await new Promise((r) => setTimeout(r, 4000));
  }
  return null;
}

// ---------------------------------------------------------------- Polar Bot (Discord)

const DISCORD = 'https://discord.com/api/v10';
const ICON = path.join(ROOT, 'branding', 'png', 'polar-icon-512.png');

async function discordApi(token, method, p, body) {
  for (let i = 0; i < 4; i++) {
    const res = await fetch(DISCORD + p, { method, headers: { Authorization: `Bot ${token}`, 'Content-Type': 'application/json',
      'User-Agent': 'DiscordBot (https://polar-client.polarclientminecraft.workers.dev, 1.0) PolarSetup' }, body: body ? JSON.stringify(body) : undefined });
    if (res.status === 429) {
      const j = await res.json().catch(() => ({}));
      await new Promise((r) => setTimeout(r, (Number(j.retry_after) || 2) * 1000 + 200));
      continue;
    }
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) {
      const err = new Error((data && data.message) || `Discord said ${res.status}`);
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }
  throw new Error('Discord kept rate-limiting; wait a minute and run this again.');
}

async function discordBot(url, password) {
  say('\nPolar Bot (the Discord server\'s bot)');
  say('  Use the bot you made at https://discord.com/developers/applications (the one already in your server).');
  say('  Bot page > Reset Token > Copy, then paste it here. It is saved on Cloudflare only, not on this PC.\n');
  const token = await askHidden('  Bot token (hidden while you paste): ');
  if (!token) fail('No token, so Polar Bot was not set up.');
  let app;
  try {
    app = await discordApi(token, 'GET', '/oauth2/applications/@me');
  } catch (e) {
    fail(e.status === 401 ? 'Discord says that token is wrong. Press Reset Token on the Bot page, copy the new one, and run this again.' : `Couldn't reach Discord: ${e.message}`);
  }
  const guilds = await discordApi(token, 'GET', '/users/@me/guilds');
  if (!guilds.length) fail('The bot is in no server. Add it to your Polar Client server first (OAuth2 > URL Generator > bot + Administrator).');
  let guild = guilds[0];
  if (guilds.length > 1) {
    guilds.forEach((g, i) => say(`  ${i + 1}. ${g.name}`));
    const n = Number(await ask('  Which server is the Polar Client one? Number: '));
    guild = guilds[n - 1];
    if (!guild) fail('No such server.');
  }
  say(`  Bot: ${app.name} · server: ${guild.name}`);
  say('  Saving the bot settings on Cloudflare…');
  putSecret('DISCORD_BOT_TOKEN', token);
  putSecret('DISCORD_PUBLIC_KEY', app.verify_key);
  putSecret('DISCORD_APP_ID', app.id);
  putSecret('DISCORD_GUILD_ID', guild.id);

  // wait until the Worker has the key (a request without a signature is then refused with 401, not 503)
  const endpoint = `${url}/discord/interactions`;
  for (let i = 0; i < 30; i++) {
    const r = await fetch(endpoint, { method: 'POST', body: '{}' }).catch(() => null);
    if (r && r.status === 401) break;
    await new Promise((res) => setTimeout(res, 3000));
  }
  say('  Pointing Discord at the bot…');
  let lastErr = null;
  for (let i = 0; i < 6; i++) {
    try {
      // Discord checks the address answers properly before it accepts it
      await discordApi(token, 'PATCH', '/applications/@me', { interactions_endpoint_url: endpoint,
        description: "Polar Client's own bot: moderation, tickets, giveaways and Polar commands. Type /help." });
      lastErr = null;
      break;
    } catch (e) {
      lastErr = e;
      await new Promise((res) => setTimeout(res, 5000));
    }
  }
  if (lastErr) fail(`Discord didn't accept the bot's address (${lastErr.message}). Wait a minute and run discord-bot.bat again.`);
  try {
    const me = await discordApi(token, 'GET', '/users/@me');
    const patch = {};
    if (me.username !== 'Polar Bot') patch.username = 'Polar Bot';
    if (!me.avatar && fs.existsSync(ICON)) patch.avatar = `data:image/png;base64,${fs.readFileSync(ICON).toString('base64')}`;
    if (Object.keys(patch).length) await discordApi(token, 'PATCH', '/users/@me', patch);
  } catch {
    // the name and picture are nice to have (Discord limits how often they change)
  }
  say('  Registering the commands and finding the channels…');
  const res = await fetch(`${url}/discord/admin/setup`, { method: 'POST', headers: { Authorization: `Bearer ${password}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ guild: guild.id }) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) fail(`The bot couldn't finish its setup: ${data.error || res.status}`);
  for (const d of data.done || []) say(`    ${d}`);
  say('\nPolar Bot is ready. In your server, type /help to see its commands.');
  say('Settings: /config show. Server owners and admins can change who sees which command in Server Settings > Integrations > Polar Bot.');
}

async function main() {
  checkNode();
  const newAdmin = process.argv.includes('--new-admin-password');
  say('Polar server: deploy to Cloudflare (free plan)\n');
  await signIn();
  const url = await deploy();
  const password = await secrets(newAdmin);
  const changed = rememberUrl(url);
  say(`\nChecking ${url} …`);
  const h = await healthy(url);
  say('');
  say(h ? `The Polar server is online with ${h.items} cosmetics.` : "The server was uploaded but isn't answering yet. Give it a few minutes, then open the address below.");
  say('');
  say(`  Server:          ${url}`);
  say(`  Admin page:      ${url}/admin`);
  say(`  Admin password:  ${password}   (also saved in server\\admin-password.txt; keep it private)`);
  say('');
  if (changed) {
    say('The launcher now points at this server. Run publish.bat and then release.bat so players get it in the next update.');
  } else {
    say('The launcher already points at this server.');
  }
  if (process.argv.includes('--discord')) await discordBot(url, password);
}

main().catch((e) => fail(e && e.stack ? e.stack : String(e)));
