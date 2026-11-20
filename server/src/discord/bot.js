// Polar Bot: the Polar Client Discord server's bot, as one Durable Object. Discord sends every command, button and
// form to the Worker (/discord/interactions); the Worker checks Discord's signature and hands it here. The bot keeps
// its data (cases, tickets, giveaways, settings) in this object's SQLite database and uses its alarm for timed work:
// giveaways ending, temporary bans running out, and posting launcher updates from news.json into #updates.
import { Rest, DiscordError } from './rest.js';
import { COMMANDS } from './commands.js';
import { ephemeral, Refusal, COLOR, has, PERM, refuse, clip, userTag } from './util.js';
import * as moderation from './moderation.js';
import * as tickets from './tickets.js';
import * as giveaways from './giveaways.js';
import * as polar from './polar.js';
import * as roles from './roles.js';
import { sameSecret } from '../token.js';

const MODULES = [moderation, tickets, giveaways, polar];
const NEWS_EVERY = 15 * 60 * 1000;
const CACHE_MS = 60 * 1000;

const SCHEMA = [
  'CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL)',
  `CREATE TABLE IF NOT EXISTS cases (id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT NOT NULL, user_tag TEXT, mod TEXT NOT NULL,
     mod_tag TEXT, action TEXT NOT NULL, reason TEXT, duration INTEGER, created INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1)`,
  'CREATE INDEX IF NOT EXISTS cases_user ON cases (user)',
  'CREATE TABLE IF NOT EXISTS tempbans (user TEXT PRIMARY KEY, until INTEGER NOT NULL, case_id INTEGER)',
  `CREATE TABLE IF NOT EXISTS tickets (id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT, user TEXT NOT NULL, subject TEXT,
     status TEXT NOT NULL, claimed_by TEXT, created INTEGER NOT NULL, closed INTEGER, closed_by TEXT)`,
  `CREATE TABLE IF NOT EXISTS giveaways (id INTEGER PRIMARY KEY AUTOINCREMENT, channel TEXT NOT NULL, message TEXT, prize TEXT NOT NULL,
     description TEXT, item TEXT, winners INTEGER NOT NULL, ends INTEGER NOT NULL, host TEXT NOT NULL, role TEXT,
     status TEXT NOT NULL, created INTEGER NOT NULL)`,
  'CREATE TABLE IF NOT EXISTS entries (giveaway INTEGER NOT NULL, user TEXT NOT NULL, PRIMARY KEY (giveaway, user))',
  `CREATE TABLE IF NOT EXISTS winners (giveaway INTEGER NOT NULL, user TEXT NOT NULL, code TEXT, round INTEGER NOT NULL,
     PRIMARY KEY (giveaway, user))`,
  'CREATE TABLE IF NOT EXISTS news (id TEXT PRIMARY KEY, posted INTEGER NOT NULL)',
  roles.SCHEMA,
];

/** One interaction: who did what, and how to answer it. */
export class Interaction {
  constructor(bot, i) {
    this.bot = bot;
    this.i = i;
    this.data = i.data || {};
    this.member = i.member || null;
    this.user = (i.member && i.member.user) || i.user || {};
    this.work = null;
  }

  get uid() { return this.user.id; }
  get tag() { return userTag(this.user); }
  can(bit) { return Boolean(this.member) && has(this.member.permissions, bit); }

  need(bit, what) {
    if (!this.can(bit)) refuse(`You need the **${what}** permission for that.`);
  }

  /** Answers "thinking…" now and does `work` after; work's return value (a message) replaces the answer. */
  defer(work, { ephemeral: hidden = true } = {}) {
    this.work = work;
    return { type: 5, data: { flags: hidden ? 64 : 0 } };
  }

  edit(data) {
    return this.bot.rest.patch(`/webhooks/${this.i.application_id}/${this.i.token}/messages/@original`, { allowed_mentions: { parse: [] }, ...data }, { auth: false });
  }

  followup(data) {
    return this.bot.rest.post(`/webhooks/${this.i.application_id}/${this.i.token}`, { allowed_mentions: { parse: [] }, ...data }, { auth: false });
  }
}

export class Bot {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    for (const s of SCHEMA) this.sql.exec(s);
    this.rest = new Rest(env.DISCORD_BOT_TOKEN, env.DISCORD_API || undefined);
    this.cache = new Map();
    this.pending = new Set(); // background work still running (the tests wait for it)
    this.handlers = { commands: {}, components: {}, modals: {}, autocomplete: {} };
    for (const m of MODULES) for (const k of Object.keys(this.handlers)) Object.assign(this.handlers[k], m[k] || {});
  }

  // ------------------------------------------------------------------ requests
  async fetch(request) {
    const url = new URL(request.url);
    try {
      const origin = request.headers.get('X-Polar-Site');
      if (origin && /^https:\/\/[a-z0-9.-]+$/i.test(origin) && this.config().site !== origin) this.setConfig({ site: origin });
      if (url.pathname === '/interaction') return Response.json(await this.interaction(await request.json()));
      if (url.pathname.startsWith('/discord/admin/')) return await this.admin(request, url.pathname.slice('/discord/admin/'.length));
    } catch (e) {
      console.error(e && e.stack ? e.stack : e);
      return Response.json({ error: e.message || 'Server error' }, { status: e.status || 500 });
    }
    return Response.json({ error: 'Not found' }, { status: 404 });
  }

  async interaction(raw) {
    const x = new Interaction(this, raw);
    let res;
    try {
      res = await this.route(x);
    } catch (e) {
      res = this.failure(e, raw.type);
    }
    if (x.work) this.background(x);
    return res || ephemeral('Done.');
  }

  failure(e, type) {
    if (type === 4) return { type: 8, data: { choices: [] } };
    if (e instanceof Refusal) return ephemeral(e.message);
    if (e instanceof DiscordError) {
      console.error(e.message);
      if (e.status === 403) return ephemeral("Discord didn't let me do that. Check that the **Polar Bot** role is above the roles involved (Server Settings > Roles) and that I can see this channel.");
      return ephemeral(`Discord said no: ${clip(e.data && e.data.message, 200)}`);
    }
    console.error(e && e.stack ? e.stack : e);
    return ephemeral('Something went wrong on my side. Try again in a moment.');
  }

  /** Runs deferred work after the "thinking…" answer went out, then puts its result in that answer. */
  background(x) {
    const run = (async () => {
      try {
        const out = await x.work();
        if (out) await x.edit(typeof out === 'string' ? { content: out } : out);
      } catch (e) {
        const r = this.failure(e, 2);
        await x.edit({ content: r.data.content }).catch(() => {});
      }
    })();
    this.pending.add(run);
    run.finally(() => this.pending.delete(run));
    if (this.ctx.waitUntil) this.ctx.waitUntil(run);
  }

  async route(x) {
    const { i } = x;
    if (!i.guild_id) return ephemeral('Use me in the Polar Client server.');
    const cfg = this.config();
    if (cfg.guild && i.guild_id !== cfg.guild) return ephemeral('This bot only works in the Polar Client server.');
    const h = this.handlers;
    if (i.type === 2) {
      const fn = h.commands[x.data.name];
      return fn ? fn(x) : ephemeral('I don\'t know that command any more.');
    }
    if (i.type === 4) {
      const fn = h.autocomplete[x.data.name];
      return { type: 8, data: { choices: fn ? (await fn(x)).slice(0, 25) : [] } };
    }
    const [prefix] = String(x.data.custom_id || '').split(':');
    if (i.type === 3) {
      const fn = h.components[prefix];
      return fn ? fn(x, x.data.custom_id.split(':').slice(1)) : ephemeral('That button is from an older version of the bot.');
    }
    if (i.type === 5) {
      const fn = h.modals[prefix];
      return fn ? fn(x, x.data.custom_id.split(':').slice(1)) : ephemeral('That form is from an older version of the bot.');
    }
    return ephemeral('Not something I can handle.');
  }

  // ------------------------------------------------------------------ settings
  config() {
    const out = { staff: [], escalation: { enabled: true, at: 3, ms: 3600000 } };
    for (const r of this.sql.exec('SELECT key, value FROM config').toArray()) {
      try {
        out[r.key] = JSON.parse(r.value);
      } catch {
        // ignore a bad value
      }
    }
    return out;
  }

  setConfig(patch) {
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === null) this.sql.exec('DELETE FROM config WHERE key = ?', k);
      else this.sql.exec('INSERT INTO config (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value', k, JSON.stringify(v));
    }
  }

  guildId() {
    return this.config().guild || this.env.DISCORD_GUILD_ID;
  }

  async cached(key, load) {
    const c = this.cache.get(key);
    if (c && Date.now() - c.at < CACHE_MS) return c.value;
    const value = await load();
    this.cache.set(key, { at: Date.now(), value });
    return value;
  }

  forget(key) { this.cache.delete(key); }

  async guild() { return this.cached('guild', () => this.rest.get(`/guilds/${this.guildId()}`)); }
  async roles() { return new Map((await this.guild()).roles.map((r) => [r.id, r])); }
  async me() { return this.cached('me', () => this.rest.get(`/guilds/${this.guildId()}/members/${this.env.DISCORD_APP_ID}`)); }

  /** The position of someone's highest role (0 for none). */
  async top(roleIds) {
    const roles = await this.roles();
    return Math.max(0, ...(roleIds || []).map((r) => (roles.get(r) || { position: 0 }).position));
  }

  isStaff(member) {
    if (!member) return false;
    if (has(member.permissions, PERM.MODERATE) || has(member.permissions, PERM.MANAGE_THREADS)) return true;
    const staff = new Set(this.config().staff || []);
    return (member.roles || []).some((r) => staff.has(r));
  }

  // ------------------------------------------------------------------ cases and the mod log
  addCase({ user, mod, action, reason, duration = null }) {
    const row = this.sql.exec(`INSERT INTO cases (user, user_tag, mod, mod_tag, action, reason, duration, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
      user.id, userTag(user), mod.id, userTag(mod), action, reason || null, duration, Date.now()).one();
    return row.id;
  }

  /** Posts in the mod log channel (if there is one). Never fails the command that called it. */
  async log(embed, components) {
    const ch = this.config().modlog;
    if (!ch) return null;
    try {
      return await this.rest.post(`/channels/${ch}/messages`, { embeds: [{ timestamp: new Date().toISOString(), ...embed }], components, allowed_mentions: { parse: [] } });
    } catch (e) {
      console.error('mod log:', e.message);
      return null;
    }
  }

  /** Sends someone a DM; false if they don't accept DMs from the server. */
  async dm(userId, message) {
    try {
      const ch = await this.rest.post('/users/@me/channels', { recipient_id: userId });
      await this.rest.post(`/channels/${ch.id}/messages`, { allowed_mentions: { parse: [] }, ...message });
      return true;
    } catch {
      return false;
    }
  }

  /** A call to the Polar server (the Hub), as the admin. */
  async hub(path, init = {}) {
    const stub = this.env.HUB.get(this.env.HUB.idFromName('polar'));
    const res = await stub.fetch(new Request(`https://hub${path}`, {
      ...init, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.env.ADMIN_TOKEN || ''}`, ...(init.headers || {}) },
    }));
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Refusal(data.error || `The Polar server said ${res.status}.`);
    return data;
  }

  // ------------------------------------------------------------------ timed work
  async schedule() {
    const next = [Date.now() + NEWS_EVERY];
    const g = this.sql.exec("SELECT MIN(ends) AS t FROM giveaways WHERE status = 'running'").one().t;
    const b = this.sql.exec('SELECT MIN(until) AS t FROM tempbans').one().t;
    if (g) next.push(g);
    if (b) next.push(b);
    if (roles.hasRoleJob(this)) next.push(Date.now() + 1000); // a role change for many members: next batch in a second
    const when = Math.max(Date.now() + 1000, Math.min(...next));
    const cur = await this.ctx.storage.getAlarm();
    if (!cur || cur > when || cur < Date.now()) await this.ctx.storage.setAlarm(when);
  }

  async alarm() {
    const now = Date.now();
    try {
      await roles.runRoleJobs(this);
    } catch (e) {
      console.error('alarm roles:', e && e.stack ? e.stack : e);
    }
    try {
      await giveaways.endDue(this, now);
      await moderation.expireBans(this, now);
      const cfg = this.config();
      if (!cfg.newsCheckedAt || now - cfg.newsCheckedAt >= NEWS_EVERY - 5000) await polar.postNews(this);
    } catch (e) {
      console.error('alarm:', e && e.stack ? e.stack : e);
    } finally {
      try {
        await this.ctx.storage.deleteAlarm();
        await this.schedule();
      } catch (e) {
        console.error('alarm schedule:', e && e.message);
        await this.ctx.storage.setAlarm(Date.now() + 5 * 60 * 1000);
      }
    }
  }

  // ------------------------------------------------------------------ setup (run by deploy-server.bat)
  async admin(request, what) {
    const m = /^Bearer (.+)$/.exec(request.headers.get('Authorization') || '');
    if (!this.env.ADMIN_TOKEN || !m || !sameSecret(m[1], this.env.ADMIN_TOKEN)) return Response.json({ error: 'Wrong admin password.' }, { status: 401 });
    if (what === 'setup' && request.method === 'POST') return Response.json(await this.setup(await request.json().catch(() => ({}))));
    return Response.json({ error: 'Not found' }, { status: 404 });
  }

  /** Registers the commands, finds the channels and roles by name, posts the ticket button and starts the timer. */
  async setup({ guild } = {}) {
    const gid = String(guild || this.env.DISCORD_GUILD_ID || '');
    if (!/^\d{5,25}$/.test(gid)) throw new Refusal('No server id.');
    if (!this.env.DISCORD_APP_ID || !this.env.DISCORD_BOT_TOKEN) throw new Refusal('The bot token is missing (run deploy-server.bat --discord).');
    this.setConfig({ guild: gid });
    this.cache.clear();
    const done = [];
    const registered = await this.rest.put(`/applications/${this.env.DISCORD_APP_ID}/guilds/${gid}/commands`, COMMANDS);
    done.push(`${registered.length} commands`);
    const [channels, g] = await Promise.all([this.rest.get(`/guilds/${gid}/channels`), this.guild()]);
    const cfg = this.config();
    const find = (re, types = [0, 5]) => (channels.find((c) => types.includes(c.type) && re.test(c.name)) || {}).id;
    const patch = {};
    // names like "📋・mod-log" or "updates" (not "discord-updates")
    const want = { announcements: /^\W*announcements?$/i, modlog: /^\W*mod-?logs?$/i, tickets: /^\W*tickets?$/i, updates: /^\W*updates$/i, giveaways: /^\W*giveaways?$/i, reports: /^\W*reports?$/i };
    for (const [k, re] of Object.entries(want)) if (!cfg[k] && find(re)) patch[k] = find(re);
    if (!cfg.staff || !cfg.staff.length) {
      patch.staff = g.roles.filter((r) => /^(founder|developer|admin|administrator|moderator|mod|support team|support|staff)$/i.test(r.name)).map((r) => r.id);
    }
    // the Member role (everyone in the community gets it); made here if the server doesn't have one yet
    let member = g.roles.find((r) => /^members?$/i.test(r.name) && !r.managed);
    if (!member) {
      member = await this.rest.post(`/guilds/${gid}/roles`, { name: 'Member', color: 0x94a3b8, hoist: false, mentionable: false, permissions: '0' },
        { reason: 'Polar Bot setup: the Member role' });
      this.forget('guild');
      done.push('made the Member role');
    }
    if (cfg.memberRole !== member.id) patch.memberRole = member.id;
    if (cfg.updatePing === undefined) {
      const ping = g.roles.find((r) => /^update pings?$/i.test(r.name));
      if (ping) patch.updatePing = ping.id;
    }
    this.setConfig(patch);
    const now = this.config();
    for (const k of Object.keys(want)) if (now[k]) done.push(`${k}: #${(channels.find((c) => c.id === now[k]) || {}).name || now[k]}`);
    if (now.tickets && !now.ticketPanel) {
      const msg = await this.rest.post(`/channels/${now.tickets}/messages`, tickets.panelMessage());
      this.setConfig({ ticketPanel: { channel: now.tickets, message: msg.id } });
      done.push('posted the ticket button');
    }
    await polar.postNews(this, { quiet: !now.newsSeeded }); // the first time, only remember what was already out
    this.setConfig({ newsSeeded: true });
    await this.schedule();
    return { ok: true, done };
  }
}

/** Shared by the modules: a member's mention with their name, for logs. */
export const who = (u) => `<@${u.id}> (${userTag(u)})`;
export { COLOR };
