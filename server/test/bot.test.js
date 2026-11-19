// Polar Bot against a fake Discord: signed requests, setup, moderation, reports, tickets, giveaways (with real Polar
// codes from the Hub), Polar commands and update posts.
import test from 'node:test';
import assert from 'node:assert/strict';
import { makeCtx } from './shim.js';
import { Hub } from '../src/hub.js';
import { Bot } from '../src/discord/bot.js';
import { COMMANDS } from '../src/discord/commands.js';
import worker from '../src/index.js';

const G = '100000000000000100', APP = '100000000000000999';
const OWNER = { id: '100000000000000001', username: 'owner' };
const MOD = { id: '100000000000000002', username: 'mod' };
const ALICE = { id: '100000000000000003', username: 'alice' };
const BOB = { id: '100000000000000004', username: 'bob' };
const FOUNDER = { id: '100000000000000005', username: 'founder' };
const R = { everyone: G, founder: '200000000000000001', moderator: '200000000000000002', support: '200000000000000003', bot: '200000000000000004', pings: '200000000000000005', member: '200000000000000006' };
const CH = { general: '300000000000000001', modlog: '300000000000000002', tickets: '300000000000000003', updates: '300000000000000004', giveaways: '300000000000000005', staff: '300000000000000006' };
const MODERATE = 1n << 40n, KICK = 1n << 1n, BAN = 1n << 2n, MANAGE_MESSAGES = 1n << 13n, MANAGE_CHANNELS = 1n << 4n, MANAGE_GUILD = 1n << 5n;
const MOD_PERMS = String(MODERATE | KICK | BAN | MANAGE_MESSAGES | MANAGE_CHANNELS | (1n << 33n) | (1n << 34n));
const ADMIN = String(1n << 3n);
const EVERYONE_PERMS = String((1n << 10n) | (1n << 11n));
const NEWS_URL = 'https://news.test/news.json';
const day = 86400000;
let seq = 0n;
const snow = (ms) => String((BigInt(ms - 1420070400000) << 22n) + (seq++ % 4000000n));

// ------------------------------------------------------------------ a fake Discord

function fakeDiscord() {
  const D = {
    calls: [], webhooks: [], dms: [], files: [],
    guild: { id: G, name: 'Polar Client', owner_id: OWNER.id, roles: [
      { id: G, name: '@everyone', position: 0, permissions: EVERYONE_PERMS },
      { id: R.member, name: 'Member', position: 1, permissions: '0' }, { id: R.pings, name: 'Update Pings', position: 2, permissions: '0' },
      { id: R.support, name: 'Support Team', position: 3, permissions: '0' }, { id: R.moderator, name: 'Moderator', position: 4, permissions: MOD_PERMS },
      { id: R.founder, name: 'Founder', position: 5, permissions: ADMIN }, { id: R.bot, name: 'Polar Bot', position: 6, permissions: ADMIN, managed: true }] },
    members: {
      [OWNER.id]: { user: OWNER, roles: [] }, [MOD.id]: { user: MOD, roles: [R.moderator] }, [ALICE.id]: { user: ALICE, roles: [R.member] },
      [BOB.id]: { user: BOB, roles: [R.member] }, [FOUNDER.id]: { user: FOUNDER, roles: [R.founder] }, [APP]: { user: { id: APP, username: 'Polar Bot', bot: true }, roles: [R.bot] },
    },
    bans: {},
    channels: {
      '300000000000000000': { id: '300000000000000000', type: 0, name: '📣・discord-updates', guild_id: G, permission_overwrites: [{ id: G, type: 0, allow: '0', deny: String(1n << 10n) }] },
      [CH.general]: { id: CH.general, type: 0, name: '💬・general', guild_id: G, permission_overwrites: [] },
      [CH.modlog]: { id: CH.modlog, type: 0, name: '📋・mod-log', guild_id: G, permission_overwrites: [{ id: G, type: 0, allow: '0', deny: String(1n << 10n) }] },
      [CH.tickets]: { id: CH.tickets, type: 0, name: '🎫・tickets', guild_id: G, permission_overwrites: [{ id: G, type: 0, allow: String(1n << 38n), deny: String(1n << 11n) }] },
      [CH.updates]: { id: CH.updates, type: 5, name: '🆕・updates', guild_id: G, permission_overwrites: [{ id: G, type: 0, allow: '0', deny: String(1n << 11n) }] },
      [CH.giveaways]: { id: CH.giveaways, type: 0, name: '🎁・giveaways', guild_id: G, permission_overwrites: [] },
      [CH.staff]: { id: CH.staff, type: 0, name: '🛡️・staff-chat', guild_id: G, permission_overwrites: [{ id: G, type: 0, allow: '0', deny: String(1n << 10n) }] },
    },
    messages: {},
    commands: null,
    ids: 400000000000000000n,
  };
  const id = () => String(D.ids++);
  const msgs = (c) => (D.messages[c] = D.messages[c] || []);
  const json = (data, status = 200) => (data === null ? new Response(null, { status: 204 }) : Response.json(data, { status }));
  const err = (status, message) => Response.json({ message, code: 0 }, { status });

  async function handle(method, path, body) {
    let m;
    const is = (re) => (m = re.exec(path));
    if (is(/^\/applications\/(\d+)\/guilds\/(\d+)\/commands$/) && method === 'PUT') {
      D.commands = body;
      return json(body.map((c) => ({ ...c, id: id() })));
    }
    if (is(/^\/guilds\/(\d+)$/)) return json(D.guild);
    if (is(/^\/guilds\/\d+\/channels$/)) return json(Object.values(D.channels));
    if (is(/^\/guilds\/\d+\/members\/(\d+)$/)) {
      const mem = D.members[m[1]];
      if (!mem) return err(404, 'Unknown Member');
      if (method === 'GET') return json(mem);
      if (method === 'PATCH') {
        Object.assign(mem, body);
        return json(mem);
      }
      if (method === 'DELETE') {
        delete D.members[m[1]];
        return json(null);
      }
    }
    if (is(/^\/guilds\/\d+\/members\/(\d+)\/roles\/(\d+)$/)) {
      const mem = D.members[m[1]];
      if (method === 'PUT') mem.roles = [...new Set([...mem.roles, m[2]])];
      else mem.roles = mem.roles.filter((r) => r !== m[2]);
      return json(null);
    }
    if (is(/^\/guilds\/\d+\/bans(\?.*)?$/)) return json(Object.values(D.bans));
    if (is(/^\/guilds\/\d+\/bans\/(\d+)$/)) {
      if (method === 'GET') return D.bans[m[1]] ? json(D.bans[m[1]]) : err(404, 'Unknown Ban');
      if (method === 'PUT') {
        const u = (D.members[m[1]] || {}).user || { id: m[1], username: `user${m[1].slice(-3)}` };
        D.bans[m[1]] = { user: u, reason: null, delete: body.delete_message_seconds };
        delete D.members[m[1]];
        return json(null);
      }
      if (method === 'DELETE') {
        if (!D.bans[m[1]]) return err(404, 'Unknown Ban');
        delete D.bans[m[1]];
        return json(null);
      }
    }
    if (is(/^\/users\/@me\/channels$/)) {
      if (body.recipient_id === BOB.id) return err(403, 'Cannot send messages to this user'); // Bob has DMs closed
      return json({ id: `9${body.recipient_id}`, type: 1 });
    }
    if (is(/^\/channels\/(9\d+)\/messages$/)) {
      D.dms.push({ to: m[1].slice(1), ...body });
      return json({ id: id() });
    }
    if (is(/^\/channels\/(\d+)$/)) {
      const c = D.channels[m[1]];
      if (!c) return err(404, 'Unknown Channel');
      if (method === 'GET') return json(c);
      Object.assign(c, body);
      return json(c);
    }
    if (is(/^\/channels\/(\d+)\/permissions\/(\d+)$/)) {
      const c = D.channels[m[1]];
      c.permission_overwrites = c.permission_overwrites.filter((o) => o.id !== m[2]);
      if (method === 'PUT') c.permission_overwrites.push({ id: m[2], ...body });
      return json(null);
    }
    if (is(/^\/channels\/(\d+)\/threads$/)) {
      const t = { id: id(), type: body.type, name: body.name, parent_id: m[1], guild_id: G, members: [] };
      D.channels[t.id] = t;
      return json(t);
    }
    if (is(/^\/channels\/(\d+)\/thread-members\/(\d+)$/)) {
      const t = D.channels[m[1]];
      if (method === 'PUT') t.members.push(m[2]);
      else t.members = t.members.filter((x) => x !== m[2]);
      return json(null);
    }
    if (is(/^\/channels\/(\d+)\/messages\/bulk-delete$/)) {
      const ids = new Set(body.messages);
      D.messages[m[1]] = msgs(m[1]).filter((x) => !ids.has(x.id));
      return json(null);
    }
    if (is(/^\/channels\/(\d+)\/messages\/(\d+)\/crosspost$/)) return json({ id: m[2] });
    if (is(/^\/channels\/(\d+)\/messages\/(\d+)$/)) {
      const list = msgs(m[1]);
      const msg = list.find((x) => x.id === m[2]);
      if (!msg) return err(404, 'Unknown Message');
      if (method === 'GET') return json(msg);
      if (method === 'DELETE') {
        D.messages[m[1]] = list.filter((x) => x !== msg);
        return json(null);
      }
      Object.assign(msg, body);
      return json(msg);
    }
    if (is(/^\/channels\/(\d+)\/messages(\?.*)?$/)) {
      if (method === 'GET') return json([...msgs(m[1])].reverse().slice(0, 100));
      const msg = { id: id(), channel_id: m[1], author: { id: APP, username: 'Polar Bot', bot: true }, timestamp: new Date().toISOString(), ...body };
      msgs(m[1]).push(msg);
      return json(msg);
    }
    if (is(/^\/webhooks\/(\d+)\/([^/]+)(\/messages\/@original)?$/)) {
      D.webhooks.push({ method, token: m[2], ...body });
      return json({ id: id(), ...body });
    }
    return err(404, `fake Discord has no ${method} ${path}`);
  }

  D.fetch = async (url, init = {}) => {
    const u = new URL(String(url));
    if (u.href === NEWS_URL) return Response.json(D.news || { items: [] });
    if (u.hostname === 'cdn.test') return new Response('PNGDATA', { headers: { 'Content-Type': 'image/png' } });
    if (u.hostname !== 'discord.com') throw new Error(`unexpected fetch ${u.href}`);
    const path = u.pathname.replace('/api/v10', '') + u.search;
    let body = init.body;
    if (body instanceof FormData) {
      const files = [];
      for (const [k, v] of body.entries()) if (k.startsWith('files[')) files.push({ name: v.name, text: await v.text() });
      D.files.push(...files);
      body = JSON.parse(body.get('payload_json'));
    } else if (typeof body === 'string') {
      body = JSON.parse(body);
    }
    D.calls.push([init.method || 'GET', path.split('?')[0], body]);
    if (u.pathname.startsWith('/api/v10/webhooks') ? false : !String((init.headers || {}).Authorization || '').startsWith('Bot ')) return err(401, 'Unauthorized');
    return handle(init.method || 'GET', path, body);
  };
  return D;
}

// ------------------------------------------------------------------ the bot with a real Hub behind it

async function setup() {
  const D = fakeDiscord();
  globalThis.fetch = D.fetch;
  const keys = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const pub = Buffer.from(await crypto.subtle.exportKey('raw', keys.publicKey)).toString('hex');
  const env = { TOKEN_SECRET: 'test-secret-0123456789', ADMIN_TOKEN: 'admin-pass-123456', DISCORD_PUBLIC_KEY: pub, DISCORD_BOT_TOKEN: 'bot-token',
    DISCORD_APP_ID: APP, DISCORD_GUILD_ID: G, NEWS_URL };
  const hub = new Hub(makeCtx(), env);
  const botCtx = makeCtx();
  const bot = new Bot(botCtx, env);
  env.HUB = { idFromName: (n) => n, get: () => ({ fetch: (r) => hub.fetch(r) }) };
  env.BOT = { idFromName: (n) => n, get: () => ({ fetch: (r) => bot.fetch(r) }) };
  env.ASSETS = { fetch: () => new Response('asset') };
  let n = 0;
  const sign = async (body, k = keys.privateKey) => {
    const t = String(Math.floor(Date.now() / 1000));
    const sig = Buffer.from(await crypto.subtle.sign('Ed25519', k, new TextEncoder().encode(t + body))).toString('hex');
    return { 'X-Signature-Ed25519': sig, 'X-Signature-Timestamp': t };
  };
  const post = async (payload, headers) => {
    const body = JSON.stringify(payload);
    return worker.fetch(new Request('https://polar.test/discord/interactions', { method: 'POST', body, headers: headers || await sign(body) }), env);
  };

  /** Sends an interaction as a member and waits for any background work. */
  async function send(type, data, { as = MOD, channel = CH.general, message } = {}) {
    const mem = D.members[as.id] || { roles: [] };
    const perms = as.id === OWNER.id ? ADMIN : as.id === MOD.id ? MOD_PERMS : as.id === FOUNDER.id ? ADMIN : EVERYONE_PERMS;
    const token = `tok${++n}`;
    const res = await post({ id: snow(Date.now()), application_id: APP, type, token, guild_id: G, channel_id: channel, data, message,
      member: { user: as, roles: mem.roles, permissions: perms, joined_at: '2026-01-01T00:00:00Z' } });
    const out = await res.json();
    await Promise.all([...bot.pending]);
    const edits = D.webhooks.filter((w) => w.token === token);
    return { res: out, edit: edits.find((w) => w.method === 'PATCH'), followups: edits.filter((w) => w.method === 'POST') };
  }

  /** A slash command: options as [name, value] pairs; users get resolved like Discord does. */
  function slash(name, opts = [], who = {}, subName = null) {
    const users = {}, members = {}, roles = {};
    const options = opts.map(([k, v, type]) => {
      const t = type || (D.members[v] || D.bans[v] ? 6 : typeof v === 'number' ? 4 : typeof v === 'boolean' ? 5 : 3);
      if (t === 6) {
        const mem = D.members[v];
        users[v] = mem ? mem.user : (D.bans[v] || { user: { id: v, username: 'gone' } }).user;
        if (mem) members[v] = { roles: mem.roles, joined_at: '2026-02-01T00:00:00Z', communication_disabled_until: mem.communication_disabled_until || null, permissions: '0' };
      }
      if (t === 8) roles[v] = D.guild.roles.find((r) => r.id === v);
      return { name: k, type: t, value: v };
    });
    const data = { name, type: 1, options: subName ? [{ type: 1, name: subName, options }] : options, resolved: { users, members, roles } };
    return send(2, data, who);
  }
  return { D, env, bot, hub, botCtx, post, sign, send, slash, keys };
}

const ready = async () => {
  const s = await setup();
  const res = await worker.fetch(new Request('https://polar.test/discord/admin/setup', { method: 'POST', body: JSON.stringify({ guild: G }),
    headers: { Authorization: 'Bearer admin-pass-123456' } }), s.env);
  s.setupResult = await res.json();
  return s;
};

// ------------------------------------------------------------------ tests

test('the command list is one Discord accepts', () => {
  const names = new Set();
  for (const c of COMMANDS) {
    assert.ok(!names.has(`${c.type}:${c.name}`), `duplicate ${c.name}`);
    names.add(`${c.type}:${c.name}`);
    if (c.type === 1) {
      assert.match(c.name, /^[-_a-z0-9]{1,32}$/, c.name);
      assert.ok(c.description.length >= 1 && c.description.length <= 100, `${c.name}: description ${c.description.length}`);
    } else {
      assert.ok(c.name.length <= 32 && !c.description);
    }
    const walk = (opts, where) => {
      assert.ok(opts.length <= 25, where);
      let optional = false;
      for (const o of opts) {
        assert.match(o.name, /^[-_a-z0-9]{1,32}$/, `${where} ${o.name}`);
        assert.ok(o.description.length >= 1 && o.description.length <= 100, `${where} ${o.name}: ${o.description.length}`);
        if (o.type === 1) walk(o.options || [], `${where} ${o.name}`);
        else {
          if (o.required) assert.ok(!optional, `${where}: required option ${o.name} after an optional one`);
          else optional = true;
          if (o.choices) {
            assert.ok(o.choices.length <= 25 && o.choices.every((ch) => ch.name.length <= 100));
            assert.ok(!o.autocomplete, `${where} ${o.name}: choices and autocomplete`);
          }
        }
      }
    };
    walk(c.options || [], c.name);
    assert.ok(JSON.stringify(c).length < 8000, `${c.name} is too big`);
  }
  assert.ok(COMMANDS.filter((c) => c.type === 1).length <= 100);
  assert.ok(COMMANDS.filter((c) => c.type === 2).length <= 5 && COMMANDS.filter((c) => c.type === 3).length <= 5);
});

test('only requests Discord signed get through, and the PING check answers', async () => {
  const s = await setup();
  const body = JSON.stringify({ type: 1 });
  assert.equal((await s.post({ type: 1 }, {})).status, 401, 'no signature');
  const other = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  assert.equal((await s.post({ type: 1 }, await s.sign(body, other.privateKey))).status, 401, 'signed by someone else');
  const tampered = await s.sign(body);
  const bad = await worker.fetch(new Request('https://polar.test/discord/interactions', { method: 'POST', body: JSON.stringify({ type: 2 }), headers: tampered }), s.env);
  assert.equal(bad.status, 401, 'body changed after signing');
  const ok = await s.post({ type: 1 });
  assert.deepEqual(await ok.json(), { type: 1 });
  const noKey = await worker.fetch(new Request('https://polar.test/discord/interactions', { method: 'POST', body }), { ...s.env, DISCORD_PUBLIC_KEY: '' });
  assert.equal(noKey.status, 503);
});

test('setup registers the commands, finds the channels and roles, and posts the ticket button', async () => {
  const s = await ready();
  assert.equal(s.setupResult.ok, true, JSON.stringify(s.setupResult));
  assert.equal(s.D.commands.length, COMMANDS.length);
  const cfg = s.bot.config();
  assert.equal(cfg.modlog, CH.modlog);
  assert.equal(cfg.tickets, CH.tickets);
  assert.equal(cfg.updates, CH.updates);
  assert.equal(cfg.giveaways, CH.giveaways);
  assert.deepEqual(new Set(cfg.staff), new Set([R.founder, R.moderator, R.support]));
  assert.equal(cfg.updatePing, R.pings);
  assert.equal(cfg.site, 'https://polar.test');
  const panel = s.D.messages[CH.tickets][0];
  assert.equal(panel.components[0].components[0].custom_id, 'ticket:open');
  assert.ok(s.botCtx.storage.alarm > Date.now(), 'the timer runs');
  const wrong = await worker.fetch(new Request('https://polar.test/discord/admin/setup', { method: 'POST', body: '{}', headers: { Authorization: 'Bearer nope' } }), s.env);
  assert.equal(wrong.status, 401);
  // a second setup doesn't post another button
  await worker.fetch(new Request('https://polar.test/discord/admin/setup', { method: 'POST', body: JSON.stringify({ guild: G }), headers: { Authorization: 'Bearer admin-pass-123456' } }), s.env);
  assert.equal(s.D.messages[CH.tickets].length, 1);
});

test('warnings: DM, case, mod log, and an automatic timeout at the third', async () => {
  const s = await ready();
  const first = await s.slash('warn', [['user', ALICE.id], ['reason', 'Spamming']]);
  assert.equal(first.res.type, 5, 'answers "thinking" first');
  assert.match(first.edit.content, /Warned <@\d+> \(alice\)\. Case 1; they have 1 active warning\./);
  assert.match(s.D.dms.at(-1).embeds[0].description, /Spamming/);
  assert.match(s.D.messages[CH.modlog].at(-1).embeds[0].title, /Warning · Case 1/);
  await s.slash('warn', [['user', ALICE.id], ['reason', 'Again']]);
  const third = await s.slash('warn', [['user', ALICE.id], ['reason', 'And again']]);
  assert.match(third.edit.content, /3 warnings, so they're timed out for 1 hour/);
  assert.ok(Date.parse(s.D.members[ALICE.id].communication_disabled_until) > Date.now() + 3500000);
  const list = await s.slash('warnings', [['user', ALICE.id]]);
  assert.match(list.res.data.embeds[0].description, /#1.*Spamming/s);
  const removed = await s.slash('unwarn', [['case', 1]]);
  assert.match(removed.res.data.content, /Removed warning 1/);
  const hist = await s.slash('history', [['user', ALICE.id]]);
  assert.match(hist.res.data.embeds[0].description, /~~`#1`/, 'removed warnings are struck through');
  // Bob has DMs closed: the warning still happens and the mod is told
  const bob = await s.slash('warn', [['user', BOB.id], ['reason', 'Rude']]);
  assert.match(bob.edit.content, /couldn't DM them/);
});

test("moderators can't act on themselves, the owner, higher roles, or without permission", async () => {
  const s = await ready();
  assert.match((await s.slash('warn', [['user', MOD.id], ['reason', 'x']])).res.data.content, /yourself/);
  assert.match((await s.slash('warn', [['user', OWNER.id], ['reason', 'x']])).res.data.content, /owner can't be moderated/);
  assert.match((await s.slash('kick', [['user', FOUNDER.id]])).res.data.content, /same as or above yours/);
  const sneaky = await s.slash('ban', [['user', BOB.id]], { as: ALICE });
  assert.match(sneaky.res.data.content, /You need the \*\*Ban Members\*\* permission/);
  assert.ok(s.D.members[BOB.id], 'Bob is still here');
  // the bot's own role is below the target: it says how to fix that
  s.D.guild.roles.find((r) => r.id === R.bot).position = 1;
  s.bot.cache.clear();
  assert.match((await s.slash('timeout', [['user', ALICE.id], ['duration', '1h']], { as: OWNER })).res.data.content, /above mine\. Drag the \*\*Polar Bot\*\* role higher/);
});

test('timeout, kick, ban (also temporary, ended by the timer), unban and softban', async () => {
  const s = await ready();
  assert.match((await s.slash('timeout', [['user', ALICE.id], ['duration', 'soon']])).res.data.content, /Give a length/);
  assert.match((await s.slash('timeout', [['user', ALICE.id], ['duration', '30d']])).res.data.content, /28 days/);
  const to = await s.slash('timeout', [['user', ALICE.id], ['duration', '2h'], ['reason', 'Cool off']]);
  assert.match(to.edit.content, /Timed out .* for 2 hours\. Case 1/);
  s.D.members[ALICE.id].communication_disabled_until = new Date(Date.now() + 7200000).toISOString();
  assert.match((await s.slash('untimeout', [['user', ALICE.id]])).edit.content, /Ended the timeout/);
  assert.equal(s.D.members[ALICE.id].communication_disabled_until, null);

  const ban = await s.slash('ban', [['user', ALICE.id], ['reason', 'Cheating'], ['duration', '7d'], ['delete_messages', 86400]]);
  assert.match(ban.edit.content, /Banned .* for 1 week/);
  assert.equal(s.D.bans[ALICE.id].delete, 86400);
  assert.match(s.D.dms.find((d) => d.to === ALICE.id && /Temporary ban/.test(d.embeds[0].title)).embeds[0].description, /Cheating/, 'told before the ban');
  assert.equal(s.bot.sql.exec('SELECT COUNT(*) AS n FROM tempbans').one().n, 1);
  s.bot.sql.exec('UPDATE tempbans SET until = ?', Date.now() - 1000);
  await s.bot.alarm();
  assert.equal(s.D.bans[ALICE.id], undefined, 'the timer lifted the ban');
  assert.match(s.D.messages[CH.modlog].at(-1).embeds[0].title, /Unban/);
  assert.ok(s.botCtx.storage.alarm > Date.now(), 'and set itself again');

  await s.slash('ban', [['user', BOB.id], ['reason', 'Raid']]);
  const ac = await s.send(4, { name: 'unban', type: 1, options: [{ name: 'user', type: 3, value: 'bo', focused: true }] });
  assert.deepEqual(ac.res.data.choices.map((c) => c.value), [BOB.id]);
  assert.match((await s.slash('unban', [['user', BOB.id, 3]])).edit.content, /Unbanned/);
  assert.match((await s.slash('unban', [['user', BOB.id, 3]])).edit.content, /isn't banned/);

  s.D.members[BOB.id] = { user: BOB, roles: [R.member] };
  assert.match((await s.slash('softban', [['user', BOB.id]])).edit.content, /Softbanned/);
  assert.equal(s.D.bans[BOB.id], undefined);
  assert.equal(s.D.members[BOB.id], undefined);
  s.D.members[BOB.id] = { user: BOB, roles: [R.member] };
  assert.match((await s.slash('kick', [['user', BOB.id], ['reason', 'Bye']])).edit.content, /Kicked/);
  assert.equal(s.D.members[BOB.id], undefined);
  const info = await s.slash('userinfo', [['user', ALICE.id, 6]]);
  assert.ok(info.res.data.embeds[0].fields.some((f) => f.name === 'Cases'));
  assert.match((await s.slash('case', [['number', 1]])).res.data.embeds[0].title, /Timeout · Case 1/);
  assert.match((await s.slash('reason', [['case', 1], ['reason', 'Better reason']])).res.data.content, /Updated case 1/);
});

test('purge only deletes what matches, and slowmode, lock and unlock work', async () => {
  const s = await ready();
  const list = s.D.messages[CH.general] = [];
  const add = (author, content, extra = {}) => list.push({ id: snow(Date.now() - (extra.age || 0)), channel_id: CH.general, author, content, attachments: [], embeds: [], ...extra });
  add(ALICE, 'hello', { age: 20 * day });
  add(ALICE, 'buy cheap stuff https://spam.example');
  add(BOB, 'hi');
  add(ALICE, 'pinned rules', { pinned: true });
  add(ALICE, 'more spam https://spam.example');
  const p = await s.slash('purge', [['amount', 50], ['user', ALICE.id], ['links', true]]);
  assert.match(p.edit.content, /Deleted 2 messages\. 1 older than 14 days/);
  assert.deepEqual(s.D.messages[CH.general].map((m) => m.content), ['hello', 'hi', 'pinned rules']);
  assert.match((await s.slash('slowmode', [['delay', '30s']])).res.data.content, /one message every 30 seconds/);
  assert.equal(s.D.channels[CH.general].rate_limit_per_user, 30);
  assert.match((await s.slash('slowmode', [['delay', '9h']])).res.data.content, /from `0` \(off\) to `6h`/);

  assert.match((await s.slash('lock', [['reason', 'Calm down']])).edit.content, /Locked/);
  const ow = s.D.channels[CH.general].permission_overwrites;
  assert.ok(BigInt(ow.find((o) => o.id === G).deny) & (1n << 11n), 'members can\'t send');
  assert.ok(ow.find((o) => o.id === R.moderator), 'staff can still talk');
  assert.match((await s.slash('lock', [])).edit.content, /already locked/);
  assert.match((await s.slash('unlock', [])).edit.content, /Unlocked/);
  assert.deepEqual(s.D.channels[CH.general].permission_overwrites, [], 'back exactly as it was');

  const ld = await s.slash('lockdown', [], {}, 'start');
  assert.match(ld.edit.content, /Locked 2 channels/, 'general and giveaways; not staff-only or read-only ones');
  assert.ok(!s.D.channels[CH.updates].permission_overwrites.some((o) => o.id === R.moderator));
  const end = await s.slash('lockdown', [], {}, 'end');
  assert.match(end.edit.content, /Unlocked 2 channels/);
  assert.deepEqual(s.D.channels[CH.giveaways].permission_overwrites, []);
});

test('reports go to the moderators with one-click actions', async () => {
  const s = await ready();
  s.D.messages[CH.general] = [{ id: snow(Date.now()), channel_id: CH.general, author: BOB, content: 'nasty words', attachments: [], embeds: [] }];
  const target = s.D.messages[CH.general][0];
  const menu = await s.send(2, { name: 'Report message', type: 3, target_id: target.id, resolved: { messages: { [target.id]: target } } }, { as: ALICE });
  assert.equal(menu.res.type, 9, 'asks why in a form');
  const sent = await s.send(5, { custom_id: menu.res.data.custom_id, components: [{ type: 1, components: [{ type: 4, custom_id: 'reason', value: 'Insults' }] }] }, { as: ALICE });
  assert.match(sent.edit.content, /Thanks/);
  const report = s.D.messages[CH.modlog].at(-1);
  assert.match(report.embeds[0].title, /Report/);
  assert.ok(report.embeds[0].fields.some((f) => f.value === 'nasty words'));
  const buttons = report.components[0].components.map((c) => c.custom_id || c.url);
  assert.ok(buttons.includes(`rep:del:${CH.general}:${target.id}`));

  const nope = await s.send(3, { custom_id: `rep:to:${BOB.id}`, component_type: 2 }, { as: ALICE, channel: CH.modlog, message: report });
  assert.match(nope.res.data.content, /Only staff/);
  const del = await s.send(3, { custom_id: `rep:del:${CH.general}:${target.id}`, component_type: 2 }, { channel: CH.modlog, message: report });
  assert.equal(del.res.type, 7);
  assert.equal(s.D.messages[CH.general].length, 0, 'message deleted');
  assert.ok(!del.res.data.components[0].components.some((c) => (c.custom_id || '').startsWith('rep:del')));
  const to = await s.send(3, { custom_id: `rep:to:${BOB.id}`, component_type: 2 }, { channel: CH.modlog, message: report });
  assert.equal(to.res.type, 6);
  assert.ok(Date.parse(s.D.members[BOB.id].communication_disabled_until) > Date.now());
  assert.match(to.edit.embeds[0].fields.at(-1).value, /Timed out for 1 hour/);
  // /report, and the limit on how many one person can send
  for (let i = 0; i < 2; i++) await s.slash('report', [['user', BOB.id], ['reason', `again ${i}`]], { as: ALICE });
  const spam = await s.slash('report', [['user', BOB.id], ['reason', 'more']], { as: ALICE });
  assert.match(spam.res.data.content, /wait a bit/);
});

test('tickets: open in a private thread, claim, add someone, close with a transcript', async () => {
  const s = await ready();
  const open = await s.send(3, { custom_id: 'ticket:open', component_type: 2 }, { as: ALICE, channel: CH.tickets });
  assert.equal(open.res.type, 9);
  assert.equal(open.res.data.components[0].type, 18, 'inputs sit in Labels');
  const form = { custom_id: 'ticketnew', components: [{ type: 18, id: 1, component: { type: 4, id: 2, custom_id: 'subject', value: 'Lost my cape' } },
    { type: 18, id: 3, component: { type: 4, id: 4, custom_id: 'details', value: 'It vanished after the update' } }] };
  const made = await s.send(5, form, { as: ALICE, channel: CH.tickets });
  assert.match(made.edit.content, /Your ticket is open: <#(\d+)>/);
  const thread = /<#(\d+)>/.exec(made.edit.content)[1];
  assert.equal(s.D.channels[thread].type, 12, 'a private thread');
  assert.deepEqual(s.D.channels[thread].members, [ALICE.id]);
  const first = s.D.messages[thread][0];
  assert.match(first.content, new RegExp(`<@${ALICE.id}>.*<@&`));
  assert.match(first.embeds[0].title, /Ticket 1: Lost my cape/);
  assert.match((await s.send(3, { custom_id: 'ticket:open', component_type: 2 }, { as: ALICE, channel: CH.tickets })).res.data.content, /already have an open ticket/);

  assert.match((await s.send(3, { custom_id: 'ticket:claim:1', component_type: 2 }, { as: ALICE, channel: thread, message: first })).res.data.content, /Only staff/);
  const claim = await s.send(3, { custom_id: 'ticket:claim:1', component_type: 2 }, { channel: thread, message: first });
  assert.equal(claim.res.type, 7);
  assert.ok(claim.res.data.embeds[0].fields.some((f) => f.name === 'Looked after by'));
  await s.slash('ticket', [['user', BOB.id]], { channel: thread }, 'add');
  assert.deepEqual(s.D.channels[thread].members, [ALICE.id, BOB.id]);

  s.D.messages[thread].push({ id: snow(Date.now()), author: ALICE, content: 'thanks!', timestamp: new Date().toISOString(), attachments: [], embeds: [] });
  const close = await s.send(5, { custom_id: 'ticketclose:1', components: [{ type: 1, components: [{ type: 4, custom_id: 'reason', value: 'Fixed' }] }] }, { as: ALICE, channel: thread });
  assert.match(close.edit.content, /Closed ticket 1/);
  assert.equal(s.D.channels[thread].archived, true);
  assert.equal(s.D.channels[thread].locked, true);
  const transcript = s.D.files.find((f) => f.name === 'ticket-1.txt');
  assert.match(transcript.text, /alice: thanks!/);
  assert.match(transcript.text, /Lost my cape/);
  assert.match((await s.send(3, { custom_id: 'ticket:close:1', component_type: 2 }, { channel: thread })).res.data.content, /already closed/);
});

test('giveaways: enter, end on time, winners get their own Polar code, reroll', async () => {
  const s = await ready();
  const ac = await s.send(4, { name: 'giveaway', type: 1, options: [{ type: 1, name: 'start', options: [{ name: 'cosmetic', type: 3, value: 'revon', focused: true }] }] });
  assert.ok(ac.res.data.choices.some((c) => c.value === 'cape_revon'));
  const start = await s.slash('giveaway', [['prize', "Revon's Cape"], ['duration', '1h'], ['winners', 1], ['cosmetic', 'cape_revon']], {}, 'start');
  assert.match(start.edit.content, /Giveaway #1 is running in <#\d+> for 1 hour/);
  const msg = s.D.messages[CH.giveaways][0];
  assert.match(msg.components[0].components[0].label, /Enter \(0\)/);
  const enter = await s.send(3, { custom_id: 'gw:enter:1', component_type: 2 }, { as: ALICE, channel: CH.giveaways, message: msg });
  assert.equal(enter.res.type, 7);
  assert.match(enter.res.data.components[0].components[0].label, /Enter \(1\)/);
  assert.match(enter.followups[0].content, /You're in/);
  assert.match((await s.send(3, { custom_id: 'gw:enter:1', component_type: 2 }, { as: ALICE, channel: CH.giveaways, message: msg })).res.data.content, /already in/);
  await s.send(3, { custom_id: 'gw:enter:1', component_type: 2 }, { as: BOB, channel: CH.giveaways, message: msg });
  assert.ok(s.botCtx.storage.alarm <= Date.now() + 3600000 + 2000, 'the timer is set for the end');

  s.bot.sql.exec('UPDATE giveaways SET ends = ?', Date.now() - 1);
  await s.bot.alarm();
  const announce = s.D.messages[CH.giveaways].at(-1);
  assert.match(announce.content, /Congratulations: <@(\d+)>! You won \*\*Revon's Cape\*\*/);
  const winner = /<@(\d+)>/.exec(announce.content)[1];
  const loser = winner === ALICE.id ? BOB : ALICE;
  assert.equal(msg.components[0].components[0].disabled, true, 'the Enter button is off');
  const claim = await s.send(3, { custom_id: 'gw:claim:1', component_type: 2 }, { as: winner === ALICE.id ? ALICE : BOB, channel: CH.giveaways });
  const code = /`(POLAR-[A-Z0-9-]+)`/.exec(claim.res.data.content)[1];
  assert.equal(claim.res.data.flags, 64, 'only the winner sees it');
  const codes = s.hub.sql.exec('SELECT * FROM codes').toArray();
  assert.equal(codes.length, 1);
  assert.equal(codes[0].item, 'cape_revon');
  assert.equal(code.replace(/-/g, ''), codes[0].code);
  assert.match((await s.send(3, { custom_id: 'gw:claim:1', component_type: 2 }, { as: loser, channel: CH.giveaways })).res.data.content, /Only the winners/);
  const reroll = await s.slash('giveaway', [['id', 1]], {}, 'reroll');
  assert.match(reroll.edit.content, new RegExp(`New winner: <@${loser.id}>`));
  assert.match((await s.slash('giveaway', [['id', 1]], {}, 'reroll')).edit.content, /already won/);
  assert.match((await s.slash('giveaway', [['prize', 'x'], ['duration', '1h']], { as: ALICE }, 'start')).res.data.content, /Manage Events/);
});

test('Polar commands: download, faq, status, online, cosmetics, codes and help', async () => {
  const s = await ready();
  const dl = await s.slash('download');
  assert.equal(dl.res.data.components[0].components[0].url, 'https://polar.test');
  assert.match((await s.slash('faq', [['question', 'free']])).res.data.embeds[0].title, /free/);
  const st = await s.slash('status');
  assert.match(st.res.data.embeds[0].title, /online/);
  assert.match((await s.slash('online')).res.data.content, /\*\*0\*\* people/);
  const cos = await s.slash('cosmetic', [['name', 'head_revon_crown']], { as: ALICE });
  assert.equal(cos.res.data.embeds[0].title, "Revon's Crown");
  assert.match(cos.res.data.embeds[0].description, /code-only/);
  const codes = await s.slash('code', [['cosmetic', 'wings_revon'], ['count', 3]], { as: OWNER });
  assert.equal(codes.edit.content.match(/POLAR-/g).length, 3);
  assert.equal(s.hub.sql.exec("SELECT COUNT(*) AS n FROM codes WHERE item = 'wings_revon'").one().n, 3);
  assert.match((await s.slash('code', [['cosmetic', 'wings_revon']])).res.data.content, /Manage Server/, 'moderators can\'t make codes');
  const helpMember = await s.slash('help', [], { as: ALICE });
  const helpMod = await s.slash('help');
  assert.equal(helpMember.res.data.embeds[0].fields.length, 2, 'members only see their commands');
  assert.ok(helpMod.res.data.embeds[0].fields.length > 2);
  const cfg = await s.slash('config', [['setting', 'reports'], ['channel', CH.staff, 7]], { as: OWNER }, 'channel');
  assert.match(cfg.res.data.content, /reports/);
  assert.equal(s.bot.config().reports, CH.staff);
});

test('new launcher updates in the news feed are posted in #updates once, with the update ping', async () => {
  const s = await setup();
  s.D.news = { items: [{ id: 'release-1.0.12', tag: 'Update', title: 'Polar Client 1.0.12', body: 'Old news', date: '2026-11-16' }] };
  await worker.fetch(new Request('https://polar.test/discord/admin/setup', { method: 'POST', body: JSON.stringify({ guild: G }), headers: { Authorization: 'Bearer admin-pass-123456' } }), s.env);
  assert.equal((s.D.messages[CH.updates] || []).length, 0, "what was already out isn't posted");
  s.D.news.items.unshift({ id: 'release-1.0.13', tag: 'Update', title: 'Polar Client 1.0.13', body: 'Profiles!', date: '2026-11-17' });
  s.bot.setConfig({ newsCheckedAt: 0 });
  await s.bot.alarm();
  const posted = s.D.messages[CH.updates];
  assert.equal(posted.length, 1);
  assert.equal(posted[0].content, `<@&${R.pings}>`);
  assert.match(posted[0].embeds[0].title, /1\.0\.13/);
  assert.ok(s.D.calls.some(([m, p]) => m === 'POST' && p.endsWith('/crosspost')), 'published to followers');
  s.bot.setConfig({ newsCheckedAt: 0 });
  await s.bot.alarm();
  assert.equal(s.D.messages[CH.updates].length, 1, 'only once');
});

test('/announce posts a titled announcement with a picture and a ping, as the bot', async () => {
  const s = await ready();
  s.D.channels['300000000000000009'] = { id: '300000000000000009', type: 5, name: '📢・announcements', guild_id: G, permission_overwrites: [] };
  assert.match((await s.slash('announce', [], { as: ALICE })).res.data.content, /Manage Messages/);
  const data = { name: 'announce', type: 1, options: [{ name: 'channel', type: 7, value: '300000000000000009' }, { name: 'ping', type: 8, value: R.pings }, { name: 'image', type: 11, value: '77' }],
    resolved: { attachments: { 77: { id: '77', filename: 'Shot.PNG', content_type: 'image/png', size: 1000, url: 'https://cdn.test/shot.png' } } } };
  const form = await s.send(2, data);
  assert.equal(form.res.type, 9);
  const sent = await s.send(5, { custom_id: 'announce', components: [{ type: 18, component: { type: 4, custom_id: 'title', value: 'Profiles are here' } },
    { type: 18, component: { type: 4, custom_id: 'text', value: 'Make as many as you like.\n**Try it!**' } }] });
  assert.match(sent.edit.content, /Posted in <#300000000000000009>\.$/);
  const msg = s.D.messages['300000000000000009'][0];
  assert.equal(msg.content, `<@&${R.pings}>`);
  assert.deepEqual(msg.allowed_mentions, { roles: [R.pings] });
  assert.equal(msg.embeds[0].title, 'Profiles are here');
  assert.equal(msg.embeds[0].image.url, 'attachment://image.png');
  assert.ok(s.D.files.some((f) => f.name === 'image.png' && f.text === 'PNGDATA'));
  assert.ok(s.D.calls.some(([m, p]) => m === 'POST' && p.endsWith('/crosspost')));
  // moderators without the @everyone permission can't ping everybody
  const ev = await s.send(2, { name: 'announce', type: 1, options: [{ name: 'ping', type: 8, value: G }] });
  assert.match(ev.res.data.content, /Mention @everyone/);
  const owner = await s.send(2, { name: 'announce', type: 1, options: [{ name: 'ping', type: 8, value: G }] }, { as: OWNER });
  assert.equal(owner.res.type, 9);
});
