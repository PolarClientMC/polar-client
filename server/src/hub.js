// The Hub: one Durable Object with Polar's data (its built-in SQLite database) and the open connections of the players
// who are on Polar right now. Being connected is what "online" means: those players get the Polar badge, and their
// cosmetics are shown to other Polar players.
import catalog from './catalog.js';
import { signToken, readToken, sameSecret, randomHex } from './token.js';
import { offlineUuid, isOfflineUuid, sha256Hex } from './offline.js';

export const SLOTS = ['cape', 'wings', 'head', 'back', 'aura'];
let ITEMS = new Map(catalog.items.map((i) => [i.id, i]));

/** Swaps the cosmetics catalogue (the tests use a small one). */
export function useCatalog(c) {
  ITEMS = new Map(c.items.map((i) => [i.id, i]));
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const COLOR = /^#[0-9a-f]{6}$/;
const CHALLENGE_TTL = 2 * 60 * 1000;
const TOKEN_TTL = 30 * 24 * 3600 * 1000;
const MAX_SYNC = 200 * 1024;
const MAX_LOOKUP = 200;
const MAX_SOCKETS = 4; // per player: the launcher and the game, with room for a reconnect
const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O or 1/I

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, first_seen INTEGER NOT NULL DEFAULT 0,
     last_seen INTEGER NOT NULL DEFAULT 0, banned INTEGER NOT NULL DEFAULT 0)`,
  'CREATE INDEX IF NOT EXISTS players_name ON players (name COLLATE NOCASE)',
  'CREATE TABLE IF NOT EXISTS challenges (id TEXT PRIMARY KEY, created INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS loadouts (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS grants (uuid TEXT NOT NULL, item TEXT NOT NULL, source TEXT, created INTEGER NOT NULL, PRIMARY KEY (uuid, item))',
  `CREATE TABLE IF NOT EXISTS codes (code TEXT PRIMARY KEY, item TEXT NOT NULL, uses_left INTEGER NOT NULL, uses INTEGER NOT NULL,
     note TEXT, created INTEGER NOT NULL)`,
  'CREATE TABLE IF NOT EXISTS redemptions (code TEXT NOT NULL, uuid TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (code, uuid))',
  'CREATE TABLE IF NOT EXISTS sync (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)',
  // one row per direction: state is 'friends', 'out' (uuid asked other) or 'in' (other asked uuid)
  `CREATE TABLE IF NOT EXISTS friends (uuid TEXT NOT NULL, other TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL,
     PRIMARY KEY (uuid, other))`,
  // offline profiles: the PC that first used a name keeps it with a secret key (only its SHA-256 is kept here)
  'CREATE TABLE IF NOT EXISTS offline_keys (uuid TEXT PRIMARY KEY, key_hash TEXT NOT NULL, created INTEGER NOT NULL)',
  // coins: kept only here, earned while the server sees the player in game (playing_since), changed by admins
  `CREATE TABLE IF NOT EXISTS coin_balances (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, coins REAL NOT NULL DEFAULT 0,
     managed INTEGER NOT NULL DEFAULT 0, seeded INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, playing_since INTEGER)`,
  // partnered servers managed from the admin panel
  `CREATE TABLE IF NOT EXISTS partners (id TEXT PRIMARY KEY, name TEXT NOT NULL, ip TEXT NOT NULL, description TEXT,
     tags TEXT, discord TEXT, logo TEXT, banner TEXT, created INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)`,
  // per-cosmetic coin price and code-only flag (editable from admin); no row = free for everyone
  `CREATE TABLE IF NOT EXISTS cosmetic_config (item TEXT PRIMARY KEY, price INTEGER NOT NULL DEFAULT 0, code_only INTEGER NOT NULL DEFAULT 0)`,
  // code-only profile tags shown next to the player name (icons, granted by admins)
  `CREATE TABLE IF NOT EXISTS player_tags (uuid TEXT NOT NULL, tag TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (uuid, tag))`,
  // social: the status you show friends, direct messages (relayed, kept a while for friends who were offline) and parties
  'CREATE TABLE IF NOT EXISTS social_status (uuid TEXT PRIMARY KEY, status TEXT NOT NULL)',
  `CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, recipient TEXT NOT NULL,
     text TEXT NOT NULL, created INTEGER NOT NULL, seen INTEGER NOT NULL DEFAULT 0)`,
  'CREATE INDEX IF NOT EXISTS messages_pair ON messages (recipient, sender, id)',
  'CREATE TABLE IF NOT EXISTS parties (id TEXT PRIMARY KEY, leader TEXT NOT NULL, created INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS party_members (party TEXT NOT NULL, uuid TEXT PRIMARY KEY, joined INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS party_invites (party TEXT NOT NULL, uuid TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (party, uuid))',
];
const STATUSES = new Set(['online', 'busy', 'away', 'invisible']);
const MAX_DM = 256;
const KEEP_DMS = 200; // per pair of friends
const PARTY_MAX = 8;
const PARTY_INVITE_TTL = 10 * 60 * 1000;

const SEEDED_COIN_USERS = ['sharky124321345', 'CraftyCanyon'];
const COINS_PER_HOUR = 10;
// "where" states that mean the game is open (the launcher reports 'launcher' when it isn't)
const IN_GAME = new Set(['menu', 'singleplayer', 'server', 'hidden']);
// one stretch of play counts for at most this long, so a connection the server lost track of can't pile up coins
const MAX_PLAY_STRETCH = 6 * 3600 * 1000;
const MAX_PARTNERS = 24;
const partnerStr = (v, max) => (typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, max) : '');
// Code-only name tags (icons shown to the right of the name). Extend by adding entries here.
const TAGS = [
  { id: 'dev', label: 'DEV', icon: '⌘', color: '#7dd3fc' },
  { id: 'staff', label: 'STAFF', icon: '★', color: '#fbbf24' },
  { id: 'partner', label: 'PARTNER', icon: '◆', color: '#c084fc' },
  { id: 'og', label: 'OG', icon: '●', color: '#34d399' },
  { id: 'vip', label: 'VIP', icon: '♦', color: '#fb7185' },
];
const TAG_BY_ID = new Map(TAGS.map((t) => [t.id, t]));
const partnerImage = (v) => (typeof v === 'string' && /^https:\/\/raw\.githubusercontent\.com\/[^\s"'<>()]+$/.test(v.trim()) ? v.trim() : '');
const partnerDiscord = (v) => (typeof v === 'string' && /^https:\/\/(discord\.gg|discord\.com\/invite)\/[A-Za-z0-9-]+\/?$/.test(v.trim()) ? v.trim() : '');
const partnerAddress = (v) => (typeof v === 'string' && /^[A-Za-z0-9.-]{3,253}(:\d{1,5})?$/.test(v.trim()) ? v.trim().toLowerCase() : '');
const MAX_FRIENDS = 200;

export class HttpError extends Error {
  constructor(status, message, extra = null) {
    super(message);
    this.status = status;
    this.extra = extra;
  }
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } });
}

async function body(request, max = 16 * 1024) {
  const text = await request.text();
  if (text.length > max) throw new HttpError(413, 'Too much data');
  try {
    const v = JSON.parse(text || '{}');
    if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error();
    return v;
  } catch {
    throw new HttpError(400, 'Expected JSON');
  }
}

/** Minecraft ids come with or without dashes; we keep them dashed and lowercase. */
export function dashed(id) {
  const hex = String(id || '').toLowerCase().replace(/-/g, '');
  if (!/^[0-9a-f]{32}$/.test(hex)) return null;
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const offlineFlag = (uuid) => (isOfflineUuid(uuid) ? { offline: true } : {});

export class Hub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    for (const s of SCHEMA) this.sql.exec(s);
    try {
      this.sql.exec('ALTER TABLE coin_balances ADD COLUMN playing_since INTEGER'); // tables made before coins were earned here
    } catch {
      // already there
    }
    this.limits = new Map();
    this.ensureSeedCoinUsers();
    // clients ping every so often; the runtime answers without waking this object, so idle connections cost nothing
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  ensureSeedCoinUsers() {
    const now = Date.now();
    for (const name of SEEDED_COIN_USERS) {
      const uuid = offlineUuid(name);
      this.sql.exec('INSERT OR IGNORE INTO players (uuid, name) VALUES (?, ?)', uuid, name);
      this.sql.exec(`INSERT INTO coin_balances (uuid, name, coins, managed, seeded, updated) VALUES (?, ?, 0, 1, 1, ?)
        ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, managed = 1, seeded = 1`, uuid, name, now);
    }
  }

  async fetch(request) {
    try {
      return await this.route(request, new URL(request.url));
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message, ...(e.extra || {}) }, e.status);
      console.error(e && e.stack ? e.stack : e);
      return json({ error: 'Server error' }, 500);
    }
  }

  async route(request, url) {
    const p = url.pathname, m = request.method;
    if (p === '/v1/health') return json({ ok: true, items: ITEMS.size, configured: Boolean(this.env.TOKEN_SECRET) });
    if (p === '/v1/auth/challenge' && m === 'POST') return this.challenge(request);
    if (p === '/v1/auth/verify' && m === 'POST') return this.verify(request);
    if (p === '/v1/auth/offline' && m === 'POST') return this.offlineSignIn(request);
    if (p === '/v1/live') return this.live(request, url);
    if (p === '/v1/online' && m === 'GET') return this.onlineCount();
    if (p === '/v1/partners' && m === 'GET') return json({ partners: this.adminPartnersList().partners });
    if (p === '/v1/playing' && m === 'GET') return json(this.playing(request, url));
    if (p.startsWith('/v1/admin/')) return this.admin(request, url);
    const me = await this.auth(request.headers.get('Authorization'));
    if (p === '/v1/me' && m === 'GET') return json(this.profile(me));
    if (p === '/v1/me/coins' && m === 'GET') return json(this.myCoins(me.uuid));
    if (p === '/v1/me/loadout' && m === 'PUT') return json(this.setLoadout(me, await body(request)));
    if (p === '/v1/cosmetics/buy' && m === 'POST') return json(this.buyCosmetic(me, await body(request)));
    if (p === '/v1/players' && m === 'POST') return json(this.lookup(await body(request, 64 * 1024)));
    if (p === '/v1/redeem' && m === 'POST') return json(this.redeem(me, await body(request)));
    if (p === '/v1/sync' && m === 'GET') return json(this.getSync(me));
    if (p === '/v1/sync' && m === 'PUT') return json(this.putSync(me, await request.text()));
    if (p === '/v1/friends' && m === 'GET') return json(this.friends(me));
    if (p === '/v1/friends/add' && m === 'POST') return json(await this.addFriend(me, await body(request)));
    if (p === '/v1/friends/accept' && m === 'POST') return json(this.acceptFriend(me, await body(request)));
    if (p === '/v1/friends/remove' && m === 'POST') return json(this.removeFriend(me, await body(request)));
    if (p === '/v1/friends/invite' && m === 'POST') return json(this.invite(me, await body(request)));
    if (p.startsWith('/v1/social/')) return json(await this.social(me, p.slice('/v1/social/'.length), m, request));
    throw new HttpError(404, 'Not found');
  }

  /** Allows `max` calls per `window` ms (a minute) for a key; throws 429 beyond that. */
  limit(key, max, window = 60000) {
    const now = Date.now();
    let b = this.limits.get(key);
    if (!b || now - b.start > window) {
      b = { start: now, n: 0 };
      this.limits.set(key, b);
      if (this.limits.size > 50000) this.limits.clear();
    }
    if (++b.n > max) throw new HttpError(429, window > 60000 ? 'Too many requests. Try again later.' : 'Too many requests. Wait a minute.');
  }

  ip(request) {
    return request.headers.get('CF-Connecting-IP') || 'local';
  }

  secret() {
    if (!this.env.TOKEN_SECRET) throw new HttpError(503, 'The server is missing its TOKEN_SECRET (run deploy-server.bat).');
    return this.env.TOKEN_SECRET;
  }

  // ------------------------------------------------------------------ sign-in

  /**
   * Step 1: a one-time challenge. The launcher tells Mojang "this player joins server <challenge>" with the player's
   * own Minecraft login, which never comes here.
   */
  challenge(request) {
    this.limit(`c:${this.ip(request)}`, 30);
    const id = randomHex(16);
    const now = Date.now();
    this.sql.exec('DELETE FROM challenges WHERE created < ?', now - CHALLENGE_TTL);
    this.sql.exec('INSERT INTO challenges (id, created) VALUES (?, ?)', id, now);
    return json({ challenge: id, expiresIn: CHALLENGE_TTL / 1000 });
  }

  /**
   * Step 2: we ask Mojang whether that player really joined the challenge; if so, a Polar token for 30 days.
   *
   * Mojang sometimes refuses to answer cloud servers like this one (403, 429 or 5xx from hasJoined). Then we don't
   * know who this is yet, and nothing the launcher says about itself can be trusted (anyone can send any name and
   * UUID). So we tell the launcher Mojang is unreachable ("mojang-unreachable"); it asks again with the player's
   * Minecraft access token, and we ask Minecraft's profile service whose token that is. The token is only used for
   * that one request: it is never stored or logged.
   */
  async verify(request) {
    this.limit(`v:${this.ip(request)}`, 30);
    const { name, challenge, mcToken } = await body(request);
    if (!NAME.test(String(name)) || !/^[0-9a-f]{32}$/.test(String(challenge))) throw new HttpError(400, 'Bad sign-in request');
    if (mcToken !== undefined && (typeof mcToken !== 'string' || mcToken.length < 20 || mcToken.length > 4096)) throw new HttpError(400, 'Bad sign-in request');
    const row = this.sql.exec('SELECT id FROM challenges WHERE id = ? AND created >= ?', challenge, Date.now() - CHALLENGE_TTL).toArray();
    if (!row.length) throw new HttpError(401, 'The sign-in took too long. Try again.');
    let profile = await this.hasJoined(name, challenge);
    if (profile === 'unreachable') {
      if (!mcToken) throw new HttpError(503, "Minecraft's servers didn't answer Polar. Trying another way.", { code: 'mojang-unreachable' });
      profile = await this.profileForToken(mcToken);
    }
    if (!profile) throw new HttpError(401, "Minecraft didn't confirm this sign-in. Sign in again in the launcher.");
    const uuid = dashed(profile.id);
    if (!uuid || !NAME.test(String(profile.name))) throw new HttpError(502, 'Unexpected answer from Minecraft');
    if (String(profile.name).toLowerCase() !== String(name).toLowerCase()) throw new HttpError(401, "Minecraft didn't confirm this sign-in. Sign in again in the launcher.");
    // spent only once it worked: Mojang can take a moment to see the join, so the launcher may ask twice
    this.sql.exec('DELETE FROM challenges WHERE id = ?', challenge);
    const now = Date.now();
    this.sql.exec(`INSERT INTO players (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen,
      first_seen = CASE WHEN players.first_seen = 0 THEN excluded.first_seen ELSE players.first_seen END`, uuid, profile.name, now, now);
    if (this.player(uuid).banned) throw new HttpError(403, 'This account is blocked from Polar features.');
    const expires = now + TOKEN_TTL;
    const token = await signToken(this.secret(), { u: uuid, n: profile.name, e: expires });
    return json({ token, uuid, name: profile.name, expires });
  }

  /** Mojang's answer for a join: the profile, null when the player didn't join, or 'unreachable' when Mojang won't say. */
  async hasJoined(name, challenge) {
    let res;
    try {
      res = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${challenge}`, {
        headers: { 'User-Agent': 'PolarClient/1.0', Accept: 'application/json' },
      });
    } catch {
      return 'unreachable';
    }
    if (res.status === 200) {
      try {
        return await res.json();
      } catch {
        return 'unreachable';
      }
    }
    if (res.status === 403 || res.status === 429 || res.status >= 500) return 'unreachable';
    return null;
  }

  /** Whose Minecraft access token this is, from Minecraft's own profile service: { id, name }, or null. */
  async profileForToken(mcToken) {
    let res;
    try {
      res = await fetch('https://api.minecraftservices.com/minecraft/profile', {
        headers: { Authorization: `Bearer ${mcToken}`, 'User-Agent': 'PolarClient/1.0', Accept: 'application/json' },
      });
    } catch {
      throw new HttpError(503, "Minecraft's servers can't be reached right now. Try again in a minute.");
    }
    if (res.status === 401 || res.status === 403 || res.status === 404) return null;
    if (res.status !== 200) throw new HttpError(503, "Minecraft's servers can't be reached right now. Try again in a minute.");
    try {
      const p = await res.json();
      return p && p.id && p.name ? { id: p.id, name: p.name } : null;
    } catch {
      return null;
    }
  }

  /**
   * Offline profiles have no Minecraft account to ask Mojang about. The launcher makes a random key for each one, and
   * the first PC to sign in with a name keeps it: later sign-ins with that name need the same key, so nobody else can
   * be "you" on Polar. The key moves to another PC by copying it (Accounts in the launcher).
   */
  async offlineSignIn(request) {
    const ip = this.ip(request);
    this.limit(`o:${ip}`, 30);
    const { name, key } = await body(request);
    if (!NAME.test(String(name)) || String(name).length < 3) throw new HttpError(400, 'Offline profile names are 3-16 letters, numbers or underscores.');
    if (!/^[0-9a-f]{64}$/.test(String(key))) throw new HttpError(400, 'Bad sign-in request');
    const uuid = offlineUuid(name);
    const hash = await sha256Hex(key);
    const now = Date.now();
    const taken = () => new HttpError(409, `${name} is already used on Polar from another PC. If that's you, copy its Polar key from the launcher there (Accounts, the key next to ${name}) and paste it here. Otherwise pick another name.`, { code: 'name-taken' });
    this.ctx.storage.transactionSync(() => {
      const row = this.sql.exec('SELECT key_hash FROM offline_keys WHERE uuid = ?', uuid).toArray()[0];
      if (row) {
        if (!sameSecret(row.key_hash, hash)) throw taken();
      } else {
        // "Steve" and "steve" are different players in Minecraft, but too easy to mix up on Polar
        const twin = this.sql.exec(`SELECT p.uuid FROM players p JOIN offline_keys k ON k.uuid = p.uuid
          WHERE p.name = ? COLLATE NOCASE AND p.uuid != ?`, name, uuid).toArray()[0];
        if (twin) throw taken();
        this.limit(`on:${ip}`, 20, 3600 * 1000); // new names per hour from one address
        this.sql.exec('INSERT INTO offline_keys (uuid, key_hash, created) VALUES (?, ?, ?)', uuid, hash, now);
      }
      this.sql.exec(`INSERT INTO players (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
        ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen,
        first_seen = CASE WHEN players.first_seen = 0 THEN excluded.first_seen ELSE players.first_seen END`, uuid, name, now, now);
    });
    if (this.player(uuid).banned) throw new HttpError(403, 'This account is blocked from Polar features.');
    const expires = now + TOKEN_TTL;
    const token = await signToken(this.secret(), { u: uuid, n: name, e: expires });
    return json({ token, uuid, name, expires, offline: true });
  }

  async auth(header) {
    const m = /^Bearer (.+)$/.exec(header || '');
    const t = m && (await readToken(this.secret(), m[1]));
    if (!t || !UUID.test(t.u) || !(t.e > Date.now())) throw new HttpError(401, 'Sign in again.');
    const p = this.player(t.u);
    if (!p || p.banned) throw new HttpError(403, 'This account is blocked from Polar features.');
    this.limit(`u:${t.u}`, 240);
    return { uuid: t.u, name: p.name };
  }

  player(uuid) {
    return this.sql.exec('SELECT uuid, name, first_seen, last_seen, banned FROM players WHERE uuid = ?', uuid).toArray()[0] || null;
  }

  // ------------------------------------------------------------------ presence

  /** The live connection. While it's open the player counts as on Polar: badge and cosmetics for everyone to see. */
  async live(request, url) {
    if (request.headers.get('Upgrade') !== 'websocket') throw new HttpError(426, 'Expected a WebSocket');
    const me = await this.auth(`Bearer ${url.searchParams.get('token') || ''}`);
    const tag = `u:${me.uuid}`;
    const open = this.ctx.getWebSockets(tag);
    for (const old of open.slice(0, Math.max(0, open.length - MAX_SOCKETS + 1))) {
      try {
        old.close(4000, 'Replaced by a newer connection');
      } catch {
        // already gone
      }
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [tag]);
    server.serializeAttachment({ uuid: me.uuid, since: Date.now(), where: null });
    this.sql.exec('UPDATE players SET last_seen = ? WHERE uuid = ?', Date.now(), me.uuid);
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Clients say where they are ({ type: "where", state, server }), kept for the friends list. */
  webSocketMessage(ws, message) {
    if (typeof message !== 'string' || message.length > 512) return;
    let msg;
    try {
      msg = JSON.parse(message);
    } catch {
      return;
    }
    if (msg && msg.type === 'where') {
      const a = ws.deserializeAttachment() || {};
      a.where = { state: String(msg.state || '').slice(0, 16), server: msg.server ? String(msg.server).slice(0, 100) : null };
      a.whereAt = Date.now();
      ws.serializeAttachment(a);
      if (a.uuid) this.settleCoins(a.uuid);
    }
  }

  webSocketClose(ws, code, reason) {
    const a = ws.deserializeAttachment();
    if (a && a.uuid) {
      this.sql.exec('UPDATE players SET last_seen = ? WHERE uuid = ?', Date.now(), a.uuid);
      this.settleCoins(a.uuid, ws);
    }
    try {
      ws.close(1000, 'Bye');
    } catch {
      // already closed
    }
  }

  webSocketError() {}

  online(uuid) {
    return this.ctx.getWebSockets(`u:${uuid}`).length > 0;
  }

  // ------------------------------------------------------------------ cosmetics

  cosmeticConfig(item) {
    if (!item) return { price: 0, codeOnly: false };
    const row = this.sql.exec('SELECT price, code_only FROM cosmetic_config WHERE item = ?', item.id).toArray()[0];
    const codeOnly = Boolean(item.special || (row && row.code_only));
    const rawPrice = row ? Number(row.price) : 0; // free unless an admin sets a price
    const price = codeOnly ? 0 : Math.max(0, Math.floor(Number.isFinite(rawPrice) ? rawPrice : 0));
    return { price, codeOnly };
  }

  cosmeticConfigAll() {
    const rows = this.sql.exec('SELECT item, price, code_only FROM cosmetic_config').toArray();
    const byId = new Map(rows.map((r) => [r.item, r]));
    const out = {};
    for (const item of ITEMS.values()) {
      const row = byId.get(item.id);
      const codeOnly = Boolean(item.special || (row && row.code_only));
      const rawPrice = row ? Number(row.price) : 0; // free unless an admin sets a price
      out[item.id] = { price: codeOnly ? 0 : Math.max(0, Math.floor(Number.isFinite(rawPrice) ? rawPrice : 0)), codeOnly };
    }
    return out;
  }

  owned(uuid) {
    return new Set(this.sql.exec('SELECT item FROM grants WHERE uuid = ?', uuid).toArray().map((r) => r.item));
  }

  tags(uuid) {
    return this.sql.exec('SELECT tag FROM player_tags WHERE uuid = ? ORDER BY created ASC', uuid).toArray()
      .map((r) => r.tag).filter((id) => TAG_BY_ID.has(id));
  }

  tagDefs(ids) {
    return ids.map((id) => TAG_BY_ID.get(id)).filter(Boolean).map((t) => ({ id: t.id, label: t.label, icon: t.icon, color: t.color }));
  }

  /** May this player wear the item? Code-only items need a grant; priced items need purchase; free items are open. */
  allowed(item, owned) {
    if (!item) return false;
    const cfg = this.cosmeticConfig(item);
    if (cfg.codeOnly) return owned.has(item.id);
    if (cfg.price > 0) return owned.has(item.id);
    return true;
  }

  /** What a player wears, minus anything that no longer exists or was taken back. */
  loadout(uuid, owned = this.owned(uuid)) {
    const row = this.sql.exec('SELECT data FROM loadouts WHERE uuid = ?', uuid).toArray()[0];
    let data = {};
    try {
      data = row ? JSON.parse(row.data) : {};
    } catch {
      data = {};
    }
    const slots = {};
    for (const slot of SLOTS) {
      const v = data.slots && data.slots[slot];
      const item = v && ITEMS.get(v.id);
      if (item && item.type === slot && this.allowed(item, owned)) slots[slot] = v.color ? { id: v.id, color: v.color } : { id: v.id };
    }
    const tags = this.tags(uuid);
    const wantTag = String(data.activeTag || '').trim().toLowerCase();
    const activeTag = tags.includes(wantTag) ? wantTag : '';
    return { slots, hideBadge: Boolean(data.hideBadge), activeTag };
  }

  profile(me) {
    const owned = this.owned(me.uuid);
    const tags = this.tags(me.uuid);
    const loadout = this.loadout(me.uuid, owned);
    return { uuid: me.uuid, name: me.name, loadout, activeTag: loadout.activeTag || '', owned: [...owned].filter((id) => ITEMS.has(id)), cosmeticConfig: this.cosmeticConfigAll(), tags, tagDefs: this.tagDefs(tags), allTags: TAGS };
  }

  setLoadout(me, b) {
    const owned = this.owned(me.uuid);
    const slots = {};
    const want = b.slots && typeof b.slots === 'object' ? b.slots : {};
    for (const slot of SLOTS) {
      let v = want[slot];
      if (v === undefined || v === null || v === '') continue;
      if (typeof v === 'string') v = { id: v };
      const item = ITEMS.get(String(v.id));
      if (!item || item.type !== slot) throw new HttpError(400, `There's no ${slot} called ${v.id}.`);
      if (!this.allowed(item, owned)) {
        const cfg = this.cosmeticConfig(item);
        if (cfg.codeOnly) throw new HttpError(403, `${item.name} is a special cosmetic you don't have.`);
        throw new HttpError(403, `${item.name} costs ${cfg.price} coins. Buy it in the wardrobe first.`);
      }
      const color = typeof v.color === 'string' ? v.color.toLowerCase() : '';
      slots[slot] = item.tint && COLOR.test(color) ? { id: item.id, color } : { id: item.id };
    }
    const tags = this.tags(me.uuid);
    let activeTag = String(b.activeTag == null ? (this.loadout(me.uuid, owned).activeTag || '') : b.activeTag).trim().toLowerCase();
    if (activeTag && !tags.includes(activeTag)) throw new HttpError(403, "You don't have that tag.");
    const data = { slots, hideBadge: Boolean(b.hideBadge), activeTag };
    this.sql.exec(`INSERT INTO loadouts (uuid, data, updated) VALUES (?, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET data = excluded.data, updated = excluded.updated`, me.uuid, JSON.stringify(data), Date.now());
    return { loadout: data };
  }

  buyCosmetic(me, b) {
    this.limit(`buy:${me.uuid}`, 30);
    const item = ITEMS.get(String(b.item || ''));
    if (!item) throw new HttpError(400, 'Pick a cosmetic.');
    const cfg = this.cosmeticConfig(item);
    if (cfg.codeOnly) throw new HttpError(403, `${item.name} is code-only and can't be bought with coins.`);
    if (cfg.price <= 0) throw new HttpError(400, `${item.name} is free.`);
    const owned = this.owned(me.uuid);
    if (owned.has(item.id)) throw new HttpError(409, `You already own ${item.name}.`);

    const coins = this.settleCoins(me.uuid);
    if (coins < cfg.price) throw new HttpError(402, `You need ${cfg.price} coins to buy ${item.name}. You have ${Math.floor(coins)}.`);

    const now = Date.now();
    const nextCoins = coins - cfg.price;
    const playing = this.inGame(me.uuid) ? now : null;
    this.sql.exec(`INSERT INTO coin_balances (uuid, name, coins, updated, playing_since) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET coins = excluded.coins, updated = excluded.updated, playing_since = excluded.playing_since, name = CASE WHEN coin_balances.name = '' THEN excluded.name ELSE coin_balances.name END`,
      me.uuid, me.name || me.uuid, nextCoins, now, playing);
    this.sql.exec('INSERT OR IGNORE INTO grants (uuid, item, source, created) VALUES (?, ?, ?, ?)', me.uuid, item.id, 'coins', now);
    this.tell(me.uuid, { type: 'coins', coins: Math.floor(nextCoins) });

    return { ok: true, coins: Math.floor(nextCoins), owned: [...this.owned(me.uuid)].filter((id) => ITEMS.has(id)), item: item.id };
  }

  /** Which of these players are on Polar right now, and what they wear. Asked by the game for the players it sees. */
  lookup(b) {
    const ids = [...new Set((Array.isArray(b.uuids) ? b.uuids : []).slice(0, MAX_LOOKUP).map(dashed).filter(Boolean))];
    const players = {};
    for (const uuid of ids) {
      if (!this.online(uuid)) continue;
      const l = this.loadout(uuid);
      players[uuid] = { badge: !l.hideBadge, slots: l.slots };
      if (l.activeTag) players[uuid].tags = [l.activeTag]; // left out when there's none
    }
    return { players, ttl: 120 };
  }

  redeem(me, b) {
    this.limit(`r:${me.uuid}`, 10);
    const code = String(b.code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    if (code.length < 6 || code.length > 32) throw new HttpError(400, "That doesn't look like a code.");
    return this.ctx.storage.transactionSync(() => {
      const row = this.sql.exec('SELECT code, item, uses_left FROM codes WHERE code = ?', code).toArray()[0];
      if (!row) throw new HttpError(404, "That code doesn't exist.");
      const item = ITEMS.get(row.item);
      if (!item) throw new HttpError(410, 'That cosmetic is no longer available.');
      if (this.sql.exec('SELECT 1 FROM redemptions WHERE code = ? AND uuid = ?', code, me.uuid).toArray().length) {
        throw new HttpError(409, 'You already used this code.');
      }
      if (row.uses_left <= 0) throw new HttpError(410, 'That code has been used up.');
      if (this.owned(me.uuid).has(item.id)) throw new HttpError(409, `You already have ${item.name}.`);
      const now = Date.now();
      this.sql.exec('INSERT INTO redemptions (code, uuid, created) VALUES (?, ?, ?)', code, me.uuid, now);
      this.sql.exec('INSERT OR IGNORE INTO grants (uuid, item, source, created) VALUES (?, ?, ?, ?)', me.uuid, item.id, `code:${code}`, now);
      this.sql.exec('UPDATE codes SET uses_left = uses_left - 1 WHERE code = ?', code);
      return { item: item.id, name: item.name };
    });
  }

  // ------------------------------------------------------------------ friends

  /** Sends a message to every open connection of a player (the launcher and the game). */
  tell(uuid, msg) {
    const text = JSON.stringify(msg);
    for (const ws of this.ctx.getWebSockets(`u:${uuid}`)) {
      try {
        ws.send(text);
      } catch {
        // closing
      }
    }
  }

  /**
   * For Minecraft servers (the Polar Client plugin): is this player in game with Polar Client right now, and on this
   * server? Being "on Polar" means signed in to Polar and connected, which a hacked client can't fake the way it can
   * fake its brand or plugin channels. On cracked servers players have name-based UUIDs, so a premium Polar player is
   * also found by name. Answers yes or no only: never where anyone is.
   */
  playing(request, url) {
    this.limit(`pl:${this.ip(request)}`, 240);
    const q = (k) => String(url.searchParams.get(k) || '').trim();
    const host = q('host').toLowerCase().replace(/:\d+$/, '').replace(/\.$/, '');
    const name = q('name');
    const candidates = new Set();
    const uuid = dashed(q('uuid'));
    if (uuid) candidates.add(uuid);
    if (NAME.test(name)) {
      for (const r of this.sql.exec('SELECT uuid FROM players WHERE name = ? COLLATE NOCASE LIMIT 4', name).toArray()) candidates.add(r.uuid);
    }
    for (const id of candidates) {
      if (!this.online(id) || (this.player(id) || {}).banned) continue;
      const w = this.where(id);
      if (!w || w.state !== 'server') continue;
      if (host) {
        const on = String(w.server || '').toLowerCase().replace(/:\d+$/, '').replace(/\.$/, '');
        if (on !== host && !on.endsWith(`.${host}`)) continue;
      }
      return { playing: true };
    }
    return { playing: false };
  }

  /** Where a player is right now, from the connection that reported most recently. */
  where(uuid) {
    let best = null;
    for (const ws of this.ctx.getWebSockets(`u:${uuid}`)) {
      const a = ws.deserializeAttachment() || {};
      if (a.where && (!best || (a.whereAt || 0) > best.at)) best = { at: a.whereAt || 0, where: a.where };
    }
    return best ? best.where : null;
  }

  friends(me) {
    const rows = this.sql.exec(`SELECT f.other AS uuid, f.state AS state, p.name AS name, p.last_seen AS lastSeen
      FROM friends f JOIN players p ON p.uuid = f.other WHERE f.uuid = ? ORDER BY p.name COLLATE NOCASE`, me.uuid).toArray();
    const out = { friends: [], incoming: [], outgoing: [] };
    for (const r of rows) {
      if (r.state === 'friends') {
        const status = this.status(r.uuid);
        const online = this.online(r.uuid) && status !== 'invisible';
        const unread = Object.values(this.sql.exec('SELECT COUNT(*) FROM messages WHERE recipient = ? AND sender = ? AND seen = 0', me.uuid, r.uuid).toArray()[0])[0];
        out.friends.push({ uuid: r.uuid, name: r.name, online, status: online ? status : 'offline', where: online ? this.where(r.uuid) : null,
          lastSeen: r.lastSeen, unread, ...offlineFlag(r.uuid) });
      } else {
        (r.state === 'in' ? out.incoming : out.outgoing).push({ uuid: r.uuid, name: r.name, ...offlineFlag(r.uuid) });
      }
    }
    out.friends.sort((a, b) => Number(b.online) - Number(a.online));
    return out;
  }

  state(a, b) {
    const r = this.sql.exec('SELECT state FROM friends WHERE uuid = ? AND other = ?', a, b).toArray()[0];
    return r ? r.state : null;
  }

  async addFriend(me, b) {
    this.limit(`f:${me.uuid}`, 20);
    const name = String(b.name || '').trim();
    if (!NAME.test(name)) throw new HttpError(400, 'Type a Minecraft name.');
    // a Minecraft account and an offline profile can share a name: prefer the same kind as yours, then real accounts
    const mine = isOfflineUuid(me.uuid);
    const them = this.sql.exec('SELECT uuid, name, first_seen, banned FROM players WHERE name = ? COLLATE NOCASE AND first_seen > 0', name).toArray()
      .sort((a, b) => Number(isOfflineUuid(a.uuid) !== mine) - Number(isOfflineUuid(b.uuid) !== mine))[0];
    if (!them || !them.first_seen) throw new HttpError(404, `${name} hasn't used Polar yet. Ask them to sign in to the Polar launcher first.`);
    if (them.uuid === me.uuid) throw new HttpError(400, "That's you!");
    const st = this.state(me.uuid, them.uuid);
    if (st === 'friends') throw new HttpError(409, `You're already friends with ${them.name}.`);
    if (st === 'out') throw new HttpError(409, `You already asked ${them.name}. They need to accept.`);
    const now = Date.now();
    if (st === 'in') {
      this.link(me.uuid, them.uuid, 'friends', 'friends', now);
      this.tell(them.uuid, { type: 'friends', event: 'accepted', from: { uuid: me.uuid, name: me.name } });
      return { ...this.friends(me), message: `You and ${them.name} are now friends.` };
    }
    const count = Object.values(this.sql.exec('SELECT COUNT(*) FROM friends WHERE uuid = ?', me.uuid).toArray()[0])[0];
    if (count >= MAX_FRIENDS) throw new HttpError(409, `You can have up to ${MAX_FRIENDS} friends and requests.`);
    if (them.banned) throw new HttpError(404, `${name} hasn't used Polar yet. Ask them to sign in to the Polar launcher first.`);
    this.link(me.uuid, them.uuid, 'out', 'in', now);
    this.tell(them.uuid, { type: 'friends', event: 'request', from: { uuid: me.uuid, name: me.name } });
    return { ...this.friends(me), message: `Friend request sent to ${them.name}.` };
  }

  link(a, b, stateA, stateB, now) {
    const up = `INSERT INTO friends (uuid, other, state, created) VALUES (?, ?, ?, ?)
      ON CONFLICT (uuid, other) DO UPDATE SET state = excluded.state`;
    this.sql.exec(up, a, b, stateA, now);
    this.sql.exec(up, b, a, stateB, now);
  }

  acceptFriend(me, b) {
    const other = dashed(b.uuid);
    if (!other || this.state(me.uuid, other) !== 'in') throw new HttpError(404, "That friend request isn't there any more.");
    this.link(me.uuid, other, 'friends', 'friends', Date.now());
    this.tell(other, { type: 'friends', event: 'accepted', from: { uuid: me.uuid, name: me.name } });
    return this.friends(me);
  }

  /** Unfriend, decline a request or cancel one you sent. */
  removeFriend(me, b) {
    const other = dashed(b.uuid);
    if (!other) throw new HttpError(400, 'Pick a friend.');
    this.sql.exec('DELETE FROM friends WHERE (uuid = ? AND other = ?) OR (uuid = ? AND other = ?)', me.uuid, other, other, me.uuid);
    this.tell(other, { type: 'friends', event: 'removed', from: { uuid: me.uuid, name: me.name } });
    return this.friends(me);
  }

  /** Invites a friend to the server you're on: their game and launcher show it. */
  invite(me, b) {
    this.limit(`i:${me.uuid}`, 20);
    const other = dashed(b.uuid);
    if (!other || this.state(me.uuid, other) !== 'friends') throw new HttpError(404, 'You can only invite friends.');
    if (!this.online(other) || this.status(other) === 'invisible') throw new HttpError(409, "They're not on Polar right now.");
    if (this.status(other) === 'busy') throw new HttpError(409, "They're busy right now, so invites are held. Try a message.");
    const server = String(b.server || (this.where(me.uuid) || {}).server || '').slice(0, 100);
    if (!server) throw new HttpError(400, "Join a server first, then invite your friend to it.");
    this.tell(other, { type: 'invite', from: { uuid: me.uuid, name: me.name }, server });
    return { sent: true, server };
  }

  // ------------------------------------------------------------------ social: status, messages, parties, profiles

  /** The status a player shows friends: online, busy (invites held), away or invisible (shown as offline). */
  status(uuid) {
    const r = this.sql.exec('SELECT status FROM social_status WHERE uuid = ?', uuid).toArray()[0];
    return r ? r.status : 'online';
  }

  friendsOf(uuid) {
    return this.sql.exec("SELECT other FROM friends WHERE uuid = ? AND state = 'friends'", uuid).toArray().map((r) => r.other);
  }

  async social(me, path, method, request) {
    const b = method === 'POST' ? await body(request) : {};
    if (path === 'party' && method === 'GET') return this.partyOf(me.uuid);
    if (path === 'inbox' && method === 'GET') return this.inbox(me);
    if (method !== 'POST') throw new HttpError(404, 'Not found');
    switch (path) {
      case 'status': return this.setStatus(me, b);
      case 'dm': return this.sendDm(me, b);
      case 'thread': return this.thread(me, b);
      case 'profile': return this.socialProfile(me, b);
      case 'party/invite': return this.partyInvite(me, b);
      case 'party/join': return this.partyJoin(me, b);
      case 'party/decline': return this.partyDecline(me, b);
      case 'party/leave': return this.partyLeave(me);
      case 'party/kick': return this.partyKick(me, b);
      case 'party/warp': return this.partyWarp(me, b);
      case 'party/chat': return this.partyChat(me, b);
      default: throw new HttpError(404, 'Not found');
    }
  }

  setStatus(me, b) {
    const status = String(b.status || '');
    if (!STATUSES.has(status)) throw new HttpError(400, 'Pick online, busy, away or invisible.');
    this.sql.exec('INSERT INTO social_status (uuid, status) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET status = excluded.status', me.uuid, status);
    for (const f of this.friendsOf(me.uuid)) this.tell(f, { type: 'friends', event: 'status', from: { uuid: me.uuid, name: me.name } });
    return { status };
  }

  cleanText(t, max) {
    return String(t || '').replace(/[\u0000-\u001f\u007f§]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
  }

  sendDm(me, b) {
    this.limit(`dm:${me.uuid}`, 30);
    const other = dashed(b.uuid);
    if (!other || this.state(me.uuid, other) !== 'friends') throw new HttpError(404, 'You can only message friends.');
    const text = this.cleanText(b.text, MAX_DM);
    if (!text) throw new HttpError(400, 'Type a message.');
    const now = Date.now();
    this.sql.exec('INSERT INTO messages (sender, recipient, text, created) VALUES (?, ?, ?, ?)', me.uuid, other, text, now);
    const id = Object.values(this.sql.exec('SELECT last_insert_rowid()').toArray()[0])[0];
    // keep the last KEEP_DMS of each pair
    this.sql.exec(`DELETE FROM messages WHERE ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) AND id <= (
      SELECT id FROM messages WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?) ORDER BY id DESC LIMIT 1 OFFSET ?)`,
    me.uuid, other, other, me.uuid, me.uuid, other, other, me.uuid, KEEP_DMS);
    const msg = { id, from: me.uuid, text, at: now };
    this.tell(other, { type: 'dm', from: { uuid: me.uuid, name: me.name }, id, text, at: now });
    return { message: msg };
  }

  thread(me, b) {
    const other = dashed(b.uuid);
    if (!other || this.state(me.uuid, other) !== 'friends') throw new HttpError(404, 'You can only message friends.');
    const rows = this.sql.exec(`SELECT id, sender, text, created FROM messages WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)
      ORDER BY id DESC LIMIT 60`, me.uuid, other, other, me.uuid).toArray().reverse();
    this.sql.exec('UPDATE messages SET seen = 1 WHERE recipient = ? AND sender = ? AND seen = 0', me.uuid, other);
    return { messages: rows.map((r) => ({ id: r.id, from: r.sender, text: r.text, at: r.created })) };
  }

  inbox(me) {
    const rows = this.sql.exec(`SELECT m.sender AS uuid, p.name AS name, COUNT(*) AS n, MAX(m.created) AS at FROM messages m
      JOIN players p ON p.uuid = m.sender WHERE m.recipient = ? AND m.seen = 0 GROUP BY m.sender`, me.uuid).toArray();
    return { unread: rows.map((r) => ({ uuid: r.uuid, name: r.name, count: r.n, at: r.at })) };
  }

  /** What a profile card shows: never where someone is unless you're friends, and nothing for invisible players. */
  socialProfile(me, b) {
    let uuid = dashed(b.uuid);
    if (!uuid && NAME.test(String(b.name || ''))) {
      const r = this.sql.exec('SELECT uuid FROM players WHERE name = ? COLLATE NOCASE AND first_seen > 0 ORDER BY last_seen DESC LIMIT 1', String(b.name)).toArray()[0];
      uuid = r ? r.uuid : null;
    }
    const pl = uuid ? this.player(uuid) : null;
    if (!pl || !pl.first_seen || pl.banned) return { polar: false };
    const friend = this.state(me.uuid, uuid);
    const status = this.status(uuid);
    const online = this.online(uuid) && status !== 'invisible';
    const lo = this.loadout(uuid);
    const wearing = Object.values(lo.slots || {}).map((s) => (s && ITEMS.get(s.id) ? ITEMS.get(s.id).name : null)).filter(Boolean);
    const tags = this.tagDefs(this.tags(uuid));
    return {
      polar: true, uuid, name: pl.name, since: pl.first_seen, friend: friend || null,
      online, status: online ? status : 'offline', where: friend === 'friends' && online ? this.where(uuid) : null,
      wearing, loadout: lo, tags, activeTag: lo.activeTag || '', me: uuid === me.uuid, ...offlineFlag(uuid),
    };
  }

  partyRow(uuid) {
    return this.sql.exec('SELECT party FROM party_members WHERE uuid = ?', uuid).toArray()[0] || null;
  }

  partyOf(uuid) {
    const now = Date.now();
    this.sql.exec('DELETE FROM party_invites WHERE created < ?', now - PARTY_INVITE_TTL);
    const invites = this.sql.exec(`SELECT i.party AS party, p.leader AS leader, pl.name AS name FROM party_invites i
      JOIN parties p ON p.id = i.party JOIN players pl ON pl.uuid = p.leader WHERE i.uuid = ?`, uuid).toArray()
      .map((r) => ({ party: r.party, from: { uuid: r.leader, name: r.name } }));
    const row = this.partyRow(uuid);
    if (!row) return { party: null, invites };
    const party = this.sql.exec('SELECT id, leader FROM parties WHERE id = ?', row.party).toArray()[0];
    if (!party) return { party: null, invites };
    const members = this.sql.exec(`SELECT m.uuid AS uuid, p.name AS name FROM party_members m JOIN players p ON p.uuid = m.uuid
      WHERE m.party = ? ORDER BY m.joined`, party.id).toArray().map((r) => {
      const online = this.online(r.uuid);
      return { uuid: r.uuid, name: r.name, online, leader: r.uuid === party.leader, where: online ? this.where(r.uuid) : null };
    });
    const pending = this.sql.exec('SELECT i.uuid AS uuid, p.name AS name FROM party_invites i JOIN players p ON p.uuid = i.uuid WHERE i.party = ?', party.id)
      .toArray().map((r) => ({ uuid: r.uuid, name: r.name }));
    return { party: { id: party.id, leader: party.leader, members, pending, max: PARTY_MAX }, invites };
  }

  partyMembers(id) {
    return this.sql.exec('SELECT uuid FROM party_members WHERE party = ?', id).toArray().map((r) => r.uuid);
  }

  partyChanged(id, extra = {}) {
    for (const u of this.partyMembers(id)) this.tell(u, { type: 'party', event: 'update', ...extra });
  }

  partyInvite(me, b) {
    this.limit(`pi:${me.uuid}`, 20);
    const other = dashed(b.uuid);
    if (!other || this.state(me.uuid, other) !== 'friends') throw new HttpError(404, 'You can only invite friends to your party.');
    if (this.partyRow(other)) {
      const theirs = this.partyRow(other).party, mine = this.partyRow(me.uuid);
      if (mine && mine.party === theirs) throw new HttpError(409, "They're already in your party.");
    }
    const now = Date.now();
    let row = this.partyRow(me.uuid);
    if (!row) {
      const id = randomHex(8);
      this.sql.exec('INSERT INTO parties (id, leader, created) VALUES (?, ?, ?)', id, me.uuid, now);
      this.sql.exec('INSERT INTO party_members (party, uuid, joined) VALUES (?, ?, ?)', id, me.uuid, now);
      row = { party: id };
    }
    const party = this.sql.exec('SELECT id, leader FROM parties WHERE id = ?', row.party).toArray()[0];
    if (party.leader !== me.uuid) throw new HttpError(403, 'Only the party leader can invite.');
    if (this.partyMembers(party.id).length >= PARTY_MAX) throw new HttpError(409, `A party has room for ${PARTY_MAX}.`);
    this.sql.exec('INSERT INTO party_invites (party, uuid, created) VALUES (?, ?, ?) ON CONFLICT (party, uuid) DO UPDATE SET created = excluded.created', party.id, other, now);
    this.tell(other, { type: 'party', event: 'invite', party: party.id, from: { uuid: me.uuid, name: me.name } });
    this.partyChanged(party.id);
    return this.partyOf(me.uuid);
  }

  partyJoin(me, b) {
    const id = String(b.party || '');
    const inv = this.sql.exec('SELECT created FROM party_invites WHERE party = ? AND uuid = ?', id, me.uuid).toArray()[0];
    if (!inv || inv.created < Date.now() - PARTY_INVITE_TTL) throw new HttpError(404, 'That party invite has run out.');
    if (this.partyMembers(id).length >= PARTY_MAX) throw new HttpError(409, 'That party is full.');
    if (this.partyRow(me.uuid)) this.partyLeave(me);
    this.sql.exec('DELETE FROM party_invites WHERE uuid = ? AND party = ?', me.uuid, id);
    this.sql.exec('INSERT INTO party_members (party, uuid, joined) VALUES (?, ?, ?)', id, me.uuid, Date.now());
    this.partyChanged(id, { joined: { uuid: me.uuid, name: me.name } });
    return this.partyOf(me.uuid);
  }

  partyDecline(me, b) {
    this.sql.exec('DELETE FROM party_invites WHERE uuid = ? AND party = ?', me.uuid, String(b.party || ''));
    return this.partyOf(me.uuid);
  }

  partyLeave(me) {
    const row = this.partyRow(me.uuid);
    if (!row) return this.partyOf(me.uuid);
    this.sql.exec('DELETE FROM party_members WHERE uuid = ?', me.uuid);
    const left = this.sql.exec('SELECT uuid FROM party_members WHERE party = ? ORDER BY joined', row.party).toArray();
    if (left.length < 2) {
      // a party of one is no party
      for (const r of left) this.tell(r.uuid, { type: 'party', event: 'update', disbanded: true });
      this.sql.exec('DELETE FROM party_members WHERE party = ?', row.party);
      this.sql.exec('DELETE FROM party_invites WHERE party = ?', row.party);
      this.sql.exec('DELETE FROM parties WHERE id = ?', row.party);
    } else {
      const party = this.sql.exec('SELECT leader FROM parties WHERE id = ?', row.party).toArray()[0];
      if (party && party.leader === me.uuid) this.sql.exec('UPDATE parties SET leader = ? WHERE id = ?', left[0].uuid, row.party);
      this.partyChanged(row.party, { left: { uuid: me.uuid, name: me.name } });
    }
    return this.partyOf(me.uuid);
  }

  leaderParty(me) {
    const row = this.partyRow(me.uuid);
    const party = row && this.sql.exec('SELECT id, leader FROM parties WHERE id = ?', row.party).toArray()[0];
    if (!party) throw new HttpError(404, "You're not in a party.");
    if (party.leader !== me.uuid) throw new HttpError(403, 'Only the party leader can do that.');
    return party;
  }

  partyKick(me, b) {
    const party = this.leaderParty(me);
    const other = dashed(b.uuid);
    if (!other || other === me.uuid) throw new HttpError(400, 'Pick a member.');
    const was = this.sql.exec('DELETE FROM party_members WHERE uuid = ? AND party = ?', other, party.id).rowsWritten;
    this.sql.exec('DELETE FROM party_invites WHERE uuid = ? AND party = ?', other, party.id);
    if (was) this.tell(other, { type: 'party', event: 'update', kicked: true });
    if (this.partyMembers(party.id).length < 2) return this.partyLeave(me);
    this.partyChanged(party.id);
    return this.partyOf(me.uuid);
  }

  /** "Bring the party": members get a few seconds' prompt with Stay here, so nobody is pulled out of a match. */
  partyWarp(me, b) {
    this.limit(`pw:${me.uuid}`, 6);
    const party = this.leaderParty(me);
    const server = String(b.server || (this.where(me.uuid) || {}).server || '').slice(0, 100);
    if (!server) throw new HttpError(400, 'Join a server first.');
    for (const u of this.partyMembers(party.id)) if (u !== me.uuid) this.tell(u, { type: 'party', event: 'warp', server, from: { uuid: me.uuid, name: me.name } });
    return { sent: true, server };
  }

  partyChat(me, b) {
    this.limit(`pc:${me.uuid}`, 30);
    const row = this.partyRow(me.uuid);
    if (!row) throw new HttpError(404, "You're not in a party.");
    const text = this.cleanText(b.text, MAX_DM);
    if (!text) throw new HttpError(400, 'Type a message.');
    for (const u of this.partyMembers(row.party)) this.tell(u, { type: 'party', event: 'chat', from: { uuid: me.uuid, name: me.name }, text, at: Date.now() });
    return { sent: true };
  }

  // ------------------------------------------------------------------ settings sync

  getSync(me) {
    const row = this.sql.exec('SELECT data, updated FROM sync WHERE uuid = ?', me.uuid).toArray()[0];
    return row ? { data: JSON.parse(row.data), updated: row.updated } : { data: null, updated: 0 };
  }

  putSync(me, text) {
    if (text.length > MAX_SYNC) throw new HttpError(413, 'Your settings are too big to sync.');
    let data;
    try {
      data = JSON.parse(text);
    } catch {
      throw new HttpError(400, 'Expected JSON');
    }
    const updated = Date.now();
    this.sql.exec(`INSERT INTO sync (uuid, data, updated) VALUES (?, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET data = excluded.data, updated = excluded.updated`, me.uuid, JSON.stringify(data), updated);
    return { updated };
  }

  // ------------------------------------------------------------------ admin (the /admin page)

  async admin(request, url) {
    const m = /^Bearer (.+)$/.exec(request.headers.get('Authorization') || '');
    if (!this.env.ADMIN_TOKEN || !m || !sameSecret(m[1], this.env.ADMIN_TOKEN)) {
      this.limit(`a:${this.ip(request)}`, 20);
      throw new HttpError(401, 'Wrong admin password.');
    }
    const p = url.pathname.slice('/v1/admin/'.length), method = request.method;
    if (p === 'stats' && method === 'GET') return json(this.stats());
    if (p === 'player' && method === 'GET') return json(await this.adminPlayer(url.searchParams.get('q')));
    if (p === 'grant' && method === 'POST') return json(await this.adminGrant(await body(request), true));
    if (p === 'revoke' && method === 'POST') return json(await this.adminGrant(await body(request), false));
    if (p === 'ban' && method === 'POST') return json(await this.adminBan(await body(request)));
    if (p === 'codes' && method === 'GET') return json(this.listCodes());
    if (p === 'codes' && method === 'POST') return json(this.makeCodes(await body(request)));
    if (p === 'codes' && method === 'DELETE') {
      this.sql.exec('DELETE FROM codes WHERE code = ?', String(url.searchParams.get('code') || '').toUpperCase().replace(/[^A-Z0-9]/g, ''));
      return json(this.listCodes());
    }
    if (p === 'coins' && method === 'GET') return json(this.adminCoinsList(url.searchParams.get('q') || ''));
    if (p === 'coins/set' && method === 'POST') return json(await this.adminCoinsSet(await body(request)));
    if (p === 'coins/add' && method === 'POST') return json(this.adminCoinsAdd(await body(request)));
    if (p === 'coins/remove' && method === 'POST') return json(this.adminCoinsRemove(await body(request)));
    if (p === 'partners' && method === 'GET') return json(this.adminPartnersList());
    if (p === 'partners/save' && method === 'POST') return json(this.adminPartnersSave(await body(request)));
    if (p === 'partners/delete' && method === 'POST') return json(this.adminPartnersDelete(await body(request)));
    if (p === 'cosmetics' && method === 'GET') return json(this.adminCosmeticsList());
    if (p === 'cosmetics/set' && method === 'POST') return json(this.adminCosmeticsSet(await body(request)));
    if (p === 'tags' && method === 'GET') return json({ tags: TAGS });
    if (p === 'tags/grant' && method === 'POST') return json(await this.adminTagGrant(await body(request), true));
    if (p === 'tags/revoke' && method === 'POST') return json(await this.adminTagGrant(await body(request), false));
    throw new HttpError(404, 'Not found');
  }

  /** How many players are on Polar right now (each counted once, however many games they have open). */
  onlineNow() {
    return new Set(this.ctx.getWebSockets().map((ws) => (ws.deserializeAttachment() || {}).uuid).filter(Boolean)).size;
  }

  /**
   * For the website ("12 on Polar right now"): only the number, readable from any site, and fine to cache for half a
   * minute.
   */
  onlineCount() {
    return new Response(JSON.stringify({ online: this.onlineNow() }), {
      headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'public, max-age=30' },
    });
  }

  stats() {
    const one = (q) => Object.values(this.sql.exec(q).toArray()[0])[0];
    return {
      players: one('SELECT COUNT(*) FROM players WHERE first_seen > 0'),
      offline: one('SELECT COUNT(*) FROM offline_keys'),
      online: this.onlineNow(),
      today: one(`SELECT COUNT(*) FROM players WHERE last_seen > ${Date.now() - 86400000}`),
      grants: one('SELECT COUNT(*) FROM grants'),
      codes: one('SELECT COUNT(*) FROM codes WHERE uses_left > 0'),
      items: ITEMS.size,
      special: [...ITEMS.values()].filter((i) => i.special).length,
      coinUsers: one('SELECT COUNT(*) FROM coin_balances WHERE managed = 1 OR seeded = 1'),
      partners: one('SELECT COUNT(*) FROM partners'),
    };
  }

  /** A player by name or id: from our records, or from Mojang if they never signed in to Polar. */
  async resolve(q) {
    const s = String(q || '').trim();
    const uuid = dashed(s);
    if (uuid) return this.player(uuid) || { uuid, name: s, first_seen: 0, last_seen: 0, banned: 0 };
    if (!NAME.test(s)) throw new HttpError(400, 'Type a Minecraft name or id.');
    // real Minecraft accounts first; an offline profile with the same name can be picked by its id
    const known = this.sql.exec(`SELECT uuid, name, first_seen, last_seen, banned FROM players WHERE name = ? COLLATE NOCASE
      ORDER BY substr(uuid, 15, 1) = '3', first_seen = 0`, s).toArray()[0];
    if (known) return known;
    const res = await fetch(`https://api.minecraftservices.com/minecraft/profile/lookup/name/${encodeURIComponent(s)}`);
    if (res.status !== 200) throw new HttpError(404, `No Minecraft account is called ${s}.`);
    const prof = await res.json();
    const id = dashed(prof.id);
    if (!id) throw new HttpError(502, 'Unexpected answer from Minecraft');
    this.sql.exec('INSERT OR IGNORE INTO players (uuid, name) VALUES (?, ?)', id, prof.name || s);
    return this.player(id);
  }

  async adminPlayer(q) {
    const p = await this.resolve(q);
    const grants = this.sql.exec('SELECT item, source, created FROM grants WHERE uuid = ? ORDER BY created DESC', p.uuid).toArray();
    const tags = this.tags(p.uuid);
    return { uuid: p.uuid, name: p.name, offline: isOfflineUuid(p.uuid), signedIn: p.first_seen > 0, lastSeen: p.last_seen, banned: Boolean(p.banned), online: this.online(p.uuid), grants, items: grants.map((g) => g.item), loadout: this.loadout(p.uuid), tags, tagDefs: this.tagDefs(tags), allTags: TAGS };
  }

  async adminGrant(b, give) {
    const item = ITEMS.get(String(b.item || ''));
    if (!item) throw new HttpError(400, 'Pick a cosmetic.');
    const p = await this.resolve(b.player || b.uuid || b.name || b.q);
    if (give) this.sql.exec('INSERT OR IGNORE INTO grants (uuid, item, source, created) VALUES (?, ?, ?, ?)', p.uuid, item.id, 'admin', Date.now());
    else this.sql.exec('DELETE FROM grants WHERE uuid = ? AND item = ?', p.uuid, item.id);
    return this.adminPlayer(p.uuid);
  }

  async adminTagGrant(b, give) {
    const tag = String(b.tag || '').trim().toLowerCase();
    if (!TAG_BY_ID.has(tag)) throw new HttpError(400, 'Pick a tag.');
    const p = await this.resolve(b.player || b.uuid || b.name || b.q);
    if (give) this.sql.exec('INSERT OR IGNORE INTO player_tags (uuid, tag, created) VALUES (?, ?, ?)', p.uuid, tag, Date.now());
    else this.sql.exec('DELETE FROM player_tags WHERE uuid = ? AND tag = ?', p.uuid, tag);
    this.tell(p.uuid, { type: 'tags', tags: this.tags(p.uuid) });
    return this.adminPlayer(p.uuid);
  }

  async adminBan(b) {
    const p = await this.resolve(b.player || b.uuid || b.name || b.q);
    this.sql.exec('UPDATE players SET banned = ? WHERE uuid = ?', b.banned ? 1 : 0, p.uuid);
    if (b.banned) {
      for (const ws of this.ctx.getWebSockets(`u:${p.uuid}`)) {
        try {
          ws.close(4003, 'Blocked');
        } catch {
          // already gone
        }
      }
    }
    return this.adminPlayer(p.uuid);
  }

  listCodes() {
    return { codes: this.sql.exec('SELECT code, item, uses_left, uses, note, created FROM codes ORDER BY created DESC LIMIT 300').toArray() };
  }

  makeCodes(b) {
    const item = ITEMS.get(String(b.item || ''));
    if (!item) throw new HttpError(400, 'Pick a cosmetic.');
    const uses = Math.min(100000, Math.max(1, Math.floor(Number(b.uses) || 1)));
    const count = Math.min(100, Math.max(1, Math.floor(Number(b.count) || 1)));
    const note = String(b.note || '').slice(0, 80);
    const made = [];
    for (let i = 0; i < count; i++) {
      const bytes = crypto.getRandomValues(new Uint8Array(8));
      const raw = Array.from(bytes, (x) => CODE_CHARS[x % CODE_CHARS.length]).join('');
      const code = `POLAR${raw}`;
      this.sql.exec('INSERT INTO codes (code, item, uses_left, uses, note, created) VALUES (?, ?, ?, ?, ?, ?)', code, item.id, uses, uses, note, Date.now());
      made.push(code);
    }
    return { made: made.map((c) => `${c.slice(0, 5)}-${c.slice(5, 9)}-${c.slice(9)}`), ...this.listCodes() };
  }

  // ------------------------------------------------------------------ coins

  /** Is the game open for this player? Only the server's own view counts (what the live connections last said). */
  inGame(uuid, closing = null) {
    return this.ctx.getWebSockets(`u:${uuid}`).some((ws) => ws !== closing && ws.readyState !== 2 && ws.readyState !== 3
      && IN_GAME.has(((ws.deserializeAttachment() || {}).where || {}).state));
  }

  /**
   * Adds what was earned since the last check (10 an hour while the game is open) and starts or stops the clock.
   * Coins only ever change here and in the admin panel: nothing a player's PC sends sets a balance.
   */
  settleCoins(uuid, closing = null) {
    const now = Date.now();
    const playing = this.inGame(uuid, closing);
    const row = this.sql.exec('SELECT coins, playing_since FROM coin_balances WHERE uuid = ?', uuid).toArray()[0];
    if (!row && !playing) return 0;
    let coins = row ? Number(row.coins) || 0 : 0;
    const since = row ? row.playing_since : null;
    if (since) coins += (Math.min(Math.max(0, now - since), MAX_PLAY_STRETCH) / 3600000) * COINS_PER_HOUR;
    const next = playing ? now : null;
    if (!row) {
      const name = (this.player(uuid) || {}).name || uuid;
      this.sql.exec('INSERT INTO coin_balances (uuid, name, coins, updated, playing_since) VALUES (?, ?, 0, ?, ?)', uuid, name, now, next);
    } else if (since || next) {
      this.sql.exec('UPDATE coin_balances SET coins = ?, playing_since = ?, updated = ? WHERE uuid = ?', coins, next, now, uuid);
    }
    if (since) this.tell(uuid, { type: 'coins', coins: Math.floor(coins) });
    return coins;
  }

  /** GET /v1/me/coins: your balance, read from the server. */
  myCoins(uuid) {
    const coins = this.settleCoins(uuid);
    return { coins: Math.floor(coins), perHour: COINS_PER_HOUR, earning: this.inGame(uuid) };
  }

  // ------------------------------------------------------------------ admin coins & partners

  adminCoinsList(q = '') {
    this.ensureSeedCoinUsers();
    const s = String(q || '').trim().toLowerCase();
    let rows;
    if (s) {
      const like = `%${s.replace(/[%_]/g, '')}%`;
      rows = this.sql.exec(`SELECT c.uuid, c.name, c.coins, c.managed, c.seeded, p.first_seen, p.last_seen, p.banned
        FROM coin_balances c LEFT JOIN players p ON p.uuid = c.uuid
        WHERE lower(c.name) LIKE ? OR lower(c.uuid) LIKE ?
        ORDER BY c.seeded DESC, c.managed DESC, c.name COLLATE NOCASE LIMIT 100`, like, like).toArray();
      if (!rows.length) {
        // try resolving by player name/id and include if found
        try {
          // best-effort: search players table too
          const pRows = this.sql.exec(`SELECT p.uuid, p.name, p.first_seen, p.last_seen, p.banned, COALESCE(c.coins,0) AS coins, COALESCE(c.managed,0) AS managed, COALESCE(c.seeded,0) AS seeded
            FROM players p LEFT JOIN coin_balances c ON c.uuid = p.uuid
            WHERE lower(p.name) LIKE ? OR lower(p.uuid) LIKE ? ORDER BY p.last_seen DESC LIMIT 50`, like, like).toArray();
          rows = pRows;
        } catch {}
      }
    } else {
      rows = this.sql.exec(`SELECT c.uuid, c.name, c.coins, c.managed, c.seeded, p.first_seen, p.last_seen, p.banned
        FROM coin_balances c LEFT JOIN players p ON p.uuid = c.uuid
        WHERE c.managed = 1 OR c.seeded = 1 OR c.coins != 0
        ORDER BY c.seeded DESC, c.managed DESC, c.name COLLATE NOCASE LIMIT 200`).toArray();
    }
    const users = rows.map((r) => ({
      uuid: r.uuid,
      name: r.name || (this.player(r.uuid) || {}).name || r.uuid,
      coins: this.online(r.uuid) ? this.settleCoins(r.uuid) : Number(r.coins || 0), // live for players in game
      managed: Boolean(r.managed),
      seeded: Boolean(r.seeded),
      offline: isOfflineUuid(r.uuid),
      banned: Boolean(r.banned),
      signedIn: Number(r.first_seen || 0) > 0,
      lastSeen: Number(r.last_seen || 0),
      online: this.online(r.uuid),
    }));
    return { users };
  }

  async adminCoinsSet(b) {
    this.ensureSeedCoinUsers();
    const rawCoins = Number(b.coins);
    if (!Number.isFinite(rawCoins) || rawCoins < 0) throw new HttpError(400, 'Enter a valid coin amount.');
    let uuid = dashed(b.uuid || b.player || '');
    let name = String(b.name || '').trim();
    if (!uuid) {
      const p = await this.resolve(b.player || b.name);
      uuid = p.uuid;
      if (!name) name = p.name;
    } else {
      const p = this.player(uuid);
      if (p && !name) name = p.name;
    }
    if (!name) name = String(b.player || uuid).slice(0, 16);
    const now = Date.now();
    this.sql.exec('INSERT OR IGNORE INTO players (uuid, name) VALUES (?, ?)', uuid, name);
    const existing = this.sql.exec('SELECT managed, seeded, name FROM coin_balances WHERE uuid = ?', uuid).toArray()[0];
    const managed = existing ? existing.managed : (SEEDED_COIN_USERS.some((n) => offlineUuid(n) === uuid) ? 1 : 1);
    const seeded = existing ? existing.seeded : (SEEDED_COIN_USERS.some((n) => offlineUuid(n) === uuid) ? 1 : 0);
    const keepName = existing && existing.name ? existing.name : name;
    // a player in game keeps earning from now on, on top of the new balance
    const since = this.inGame(uuid) ? now : null;
    this.sql.exec(`INSERT INTO coin_balances (uuid, name, coins, managed, seeded, updated, playing_since) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET coins = excluded.coins, updated = excluded.updated, playing_since = excluded.playing_since,
        name = CASE WHEN coin_balances.name = '' THEN excluded.name ELSE coin_balances.name END`,
      uuid, keepName, rawCoins, managed ? 1 : 0, seeded ? 1 : 0, now, since);
    this.tell(uuid, { type: 'coins', coins: Math.floor(rawCoins) });
    return this.adminCoinsList('');
  }

  adminCoinsAdd(b) {
    this.ensureSeedCoinUsers();
    const name = String(b.name || '').trim();
    if (!NAME.test(name) || name.length < 3) throw new HttpError(400, 'Use 3-16 letters, numbers or underscores for the username.');
    const uuid = offlineUuid(name);
    const now = Date.now();
    this.sql.exec('INSERT OR IGNORE INTO players (uuid, name) VALUES (?, ?)', uuid, name);
    const seeded = SEEDED_COIN_USERS.includes(name) ? 1 : 0;
    this.sql.exec(`INSERT INTO coin_balances (uuid, name, coins, managed, seeded, updated) VALUES (?, ?, 0, 1, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, managed = 1, seeded = CASE WHEN coin_balances.seeded = 1 THEN 1 ELSE excluded.seeded END`, uuid, name, seeded, now);
    return this.adminCoinsList('');
  }

  adminCoinsRemove(b) {
    this.ensureSeedCoinUsers();
    const uuid = dashed(b.uuid || '');
    if (!uuid) throw new HttpError(400, 'Pick a user.');
    const row = this.sql.exec('SELECT seeded FROM coin_balances WHERE uuid = ?', uuid).toArray()[0];
    if (row && row.seeded) throw new HttpError(400, 'Default users cannot be removed.');
    this.sql.exec('DELETE FROM coin_balances WHERE uuid = ?', uuid);
    return this.adminCoinsList('');
  }

  cleanPartner(p) {
    if (!p || typeof p !== 'object') return null;
    const name = partnerStr(p.name, 40);
    const ip = partnerAddress(p.ip);
    if (!name || !ip) return null;
    const id = partnerStr(p.id, 40).toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    return {
      id, name, ip,
      description: partnerStr(p.description, 220),
      tags: (Array.isArray(p.tags) ? p.tags : String(p.tags || '').split(',')).map((t) => partnerStr(t, 20)).filter(Boolean).slice(0, 5),
      discord: partnerDiscord(p.discord),
      logo: partnerImage(p.logo),
      banner: partnerImage(p.banner),
    };
  }

  adminPartnersList() {
    const rows = this.sql.exec('SELECT id, name, ip, description, tags, discord, logo, banner, created, updated FROM partners ORDER BY updated DESC, created DESC, name COLLATE NOCASE').toArray();
    const partners = rows.map((r) => {
      let tags = [];
      try { tags = JSON.parse(r.tags || '[]'); } catch { tags = []; }
      return { id: r.id, name: r.name, ip: r.ip, description: r.description || '', tags: Array.isArray(tags) ? tags : [], discord: r.discord || '', logo: r.logo || '', banner: r.banner || '' };
    });
    return { partners };
  }

  adminPartnersSave(b) {
    const cleaned = this.cleanPartner(b);
    if (!cleaned) throw new HttpError(400, 'Enter a valid partner name and address. Logo/banner must be raw.githubusercontent.com URLs and Discord must be a discord.gg/discord.com invite link.');
    const count = Object.values(this.sql.exec('SELECT COUNT(*) FROM partners').toArray()[0])[0];
    const exists = this.sql.exec('SELECT 1 FROM partners WHERE id = ?', cleaned.id).toArray().length;
    if (!exists && count >= MAX_PARTNERS) throw new HttpError(409, `You can have up to ${MAX_PARTNERS} partnered servers.`);
    const now = Date.now();
    this.sql.exec(`INSERT INTO partners (id, name, ip, description, tags, discord, logo, banner, created, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET name=excluded.name, ip=excluded.ip, description=excluded.description, tags=excluded.tags, discord=excluded.discord, logo=excluded.logo, banner=excluded.banner, updated=excluded.updated`,
      cleaned.id, cleaned.name, cleaned.ip, cleaned.description, JSON.stringify(cleaned.tags), cleaned.discord, cleaned.logo, cleaned.banner, now, now);
    return this.adminPartnersList();
  }

  adminPartnersDelete(b) {
    const id = partnerStr(b.id, 40).toLowerCase().replace(/[^a-z0-9-]+/g, '-');
    if (!id) throw new HttpError(400, 'Pick a partnered server.');
    this.sql.exec('DELETE FROM partners WHERE id = ?', id);
    return this.adminPartnersList();
  }

  adminCosmeticsList() {
    const cfg = this.cosmeticConfigAll();
    const items = [...ITEMS.values()].map((item) => ({
      id: item.id,
      name: item.name,
      type: item.type,
      rarity: item.rarity || 'common',
      collection: item.collection || '',
      catalogSpecial: Boolean(item.special),
      ...cfg[item.id],
    }));
    return { items };
  }

  adminCosmeticsSet(b) {
    const item = ITEMS.get(String(b.item || ''));
    if (!item) throw new HttpError(400, 'Pick a cosmetic.');
    const codeOnly = Boolean(item.special || b.codeOnly);
    const rawPrice = Number(b.price);
    const price = codeOnly ? 0 : Math.max(0, Math.min(1000000, Math.floor(Number.isFinite(rawPrice) ? rawPrice : 0)));
    this.sql.exec(`INSERT INTO cosmetic_config (item, price, code_only) VALUES (?, ?, ?)
      ON CONFLICT (item) DO UPDATE SET price = excluded.price, code_only = excluded.code_only`, item.id, price, codeOnly ? 1 : 0);
    return this.adminCosmeticsList();
  }
}
