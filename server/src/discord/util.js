// Small helpers shared by the bot's commands: permissions, durations, embeds and replies.

/** Permission bits (https://discord.com/developers/docs/topics/permissions). */
export const PERM = {
  KICK: 1n << 1n, BAN: 1n << 2n, ADMIN: 1n << 3n, MANAGE_CHANNELS: 1n << 4n, MANAGE_GUILD: 1n << 5n, VIEW: 1n << 10n,
  SEND: 1n << 11n, MANAGE_MESSAGES: 1n << 13n, MENTION_EVERYONE: 1n << 17n, MANAGE_NICKNAMES: 1n << 27n, MANAGE_ROLES: 1n << 28n,
  MANAGE_EVENTS: 1n << 33n, MANAGE_THREADS: 1n << 34n, PUBLIC_THREADS: 1n << 35n, PRIVATE_THREADS: 1n << 36n,
  SEND_IN_THREADS: 1n << 38n, MODERATE: 1n << 40n,
};

export function has(perms, bit) {
  const p = BigInt(perms || 0);
  return (p & PERM.ADMIN) === PERM.ADMIN || (p & bit) === bit;
}

export const COLOR = { ice: 0x5ce1ff, blue: 0x2f8cff, green: 0x22c55e, yellow: 0xfacc15, orange: 0xf97316, red: 0xef4444, purple: 0x8b7bff, grey: 0x64748b };
export const EPHEMERAL = 64;

/** A reply only the person who used the command sees. */
export function ephemeral(content, extra = {}) {
  return { type: 4, data: { content, flags: EPHEMERAL, allowed_mentions: { parse: [] }, ...extra } };
}

export function say(content, extra = {}) {
  return { type: 4, data: { content, allowed_mentions: { parse: [] }, ...extra } };
}

/** "10m", "1h30m", "2d", "1w" -> milliseconds (null if it doesn't read as a duration). */
export function parseDuration(text) {
  const s = String(text || '').trim().toLowerCase().replace(/\s+/g, '');
  if (!s) return null;
  const units = { s: 1000, m: 60000, h: 3600000, d: 86400000, w: 604800000 };
  let total = 0;
  let rest = s;
  const re = /^(\d+(?:\.\d+)?)(s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?|d|days?|w|weeks?)/;
  while (rest) {
    const m = re.exec(rest);
    if (!m) return null;
    total += Number(m[1]) * units[m[2][0]];
    rest = rest.slice(m[0].length);
  }
  return total > 0 ? Math.round(total) : null;
}

/** 5400000 -> "1 hour 30 minutes". */
export function humanDuration(ms) {
  const parts = [];
  let left = Math.round(ms / 1000);
  for (const [name, size] of [['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60], ['second', 1]]) {
    const n = Math.floor(left / size);
    if (n) parts.push(`${n} ${name}${n > 1 ? 's' : ''}`);
    left -= n * size;
    if (parts.length === 2) break;
  }
  return parts.join(' ') || '0 seconds';
}

/** Discord's timestamp markup: shows in each reader's own time zone. style R = "in 2 hours", f = date and time. */
export const ts = (ms, style = 'f') => `<t:${Math.floor(ms / 1000)}:${style}>`;

/** When a Discord id (snowflake) was made. */
export const snowflakeTime = (id) => Number((BigInt(id) >> 22n) + 1420070400000n);

export const userTag = (u) => (u ? (u.discriminator && u.discriminator !== '0' ? `${u.username}#${u.discriminator}` : u.username) : 'unknown');

export function clip(text, max) {
  const s = String(text == null ? '' : text);
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

/** Options of a slash command (and its subcommand) as a flat { name: value } map. */
export function options(data) {
  let opts = data.options || [];
  let sub = null;
  let group = null;
  if (opts[0] && opts[0].type === 2) {
    group = opts[0].name;
    opts = opts[0].options || [];
  }
  if (opts[0] && opts[0].type === 1) {
    sub = opts[0].name;
    opts = opts[0].options || [];
  }
  const out = {};
  let focused = null;
  for (const o of opts) {
    out[o.name] = o.value;
    if (o.focused) focused = o.name;
  }
  return { sub, group, opts: out, focused };
}

export function button(custom_id, label, style = 2, extra = {}) {
  return { type: 2, custom_id, label, style, ...extra };
}

export const row = (...components) => ({ type: 1, components });

/** A modal (a little form) with text inputs: [id, label, style (1 short, 2 paragraph), required, max, value]. */
export function modal(custom_id, title, inputs) {
  return {
    type: 9,
    data: {
      custom_id, title,
      // each text input sits in a Label (type 18), the way Discord lays out modals now
      components: inputs.map(([id, label, style = 1, required = true, max = 1000, value]) => ({
        type: 18, label,
        component: { type: 4, custom_id: id, style, required, max_length: max, ...(value ? { value } : {}) },
      })),
    },
  };
}

/** The values typed into a submitted modal, as { id: text } (Labels or the older action rows). */
export function modalValues(data) {
  const out = {};
  const walk = (list) => {
    for (const c of list || []) {
      if (!c) continue;
      if (c.custom_id && typeof c.value === 'string') out[c.custom_id] = c.value;
      if (c.component) walk([c.component]);
      if (c.components) walk(c.components);
    }
  };
  walk(data.components);
  return out;
}

export class Refusal extends Error {} // a "no" for the user, shown to them as is
export const refuse = (msg) => { throw new Refusal(msg); };
