// Moderation: warnings (with automatic timeouts), timeouts, kicks, bans (also temporary), purges, slowmode, locks and
// lockdowns, nicknames, roles, notes, case history, and member reports with one-click actions for the moderators.
// Every action gets a case number and a line in the mod log; the member gets a DM saying what happened and why.
import {
  PERM, COLOR, ephemeral, parseDuration, humanDuration, ts, snowflakeTime, userTag, clip, options, button, row, modal,
  modalValues, refuse, Refusal,
} from './util.js';

const MAX_TIMEOUT = 28 * 86400000;
const TALK = PERM.SEND | PERM.SEND_IN_THREADS | PERM.PUBLIC_THREADS | PERM.PRIVATE_THREADS;
const ACTIONS = {
  warn: ['⚠️', 'Warning', COLOR.yellow], timeout: ['⏳', 'Timeout', COLOR.orange], untimeout: ['✅', 'Timeout ended', COLOR.green],
  kick: ['👢', 'Kick', COLOR.orange], ban: ['🔨', 'Ban', COLOR.red], tempban: ['🔨', 'Temporary ban', COLOR.red],
  softban: ['🧹', 'Softban', COLOR.red], unban: ['🔓', 'Unban', COLOR.green], note: ['📝', 'Note', COLOR.grey],
  unwarn: ['✅', 'Warning removed', COLOR.green], clearwarnings: ['✅', 'Warnings cleared', COLOR.green],
};
const reports = new Map(); // user -> recent report times (to stop report spam)

const who = (u) => `<@${u.id}> (${userTag(u)})`;
const botUser = (bot) => ({ id: bot.env.DISCORD_APP_ID || '0', username: 'Polar Bot' });

/** The user and (if they're in the server) member an option points at. */
function target(x, name = 'user') {
  const id = x.data.options && options(x.data).opts[name];
  const r = x.data.resolved || {};
  const user = (r.users || {})[id] || null;
  if (!user) refuse("I couldn't find that person. Pick them from the list, or paste their user id.");
  const member = (r.members || {})[id] || null;
  return { id, user, member };
}

async function fetchMember(bot, id) {
  try {
    const m = await bot.rest.get(`/guilds/${bot.guildId()}/members/${id}`);
    return { user: m.user, member: m };
  } catch {
    try {
      return { user: await bot.rest.get(`/users/${id}`), member: null };
    } catch {
      return { user: null, member: null };
    }
  }
}

/** Checks the moderator (and the bot) may act on this person: not themselves, not the owner, not someone ranked higher. */
async function checkTarget(x, user, member, verb) {
  const bot = x.bot;
  if (!user) refuse("I couldn't find that person.");
  if (user.id === x.uid) refuse(`You can't ${verb} yourself.`);
  if (user.id === bot.env.DISCORD_APP_ID) refuse("I can't do that to myself.");
  const g = await bot.guild();
  if (user.id === g.owner_id) refuse("The server owner can't be moderated.");
  if (!member) return;
  const theirs = await bot.top(member.roles);
  if (x.uid !== g.owner_id && theirs >= await bot.top(x.member.roles)) {
    refuse(`**${userTag(user)}**'s highest role is the same as or above yours, so you can't ${verb} them.`);
  }
  if (theirs >= await bot.top((await bot.me()).roles)) {
    refuse(`**${userTag(user)}**'s highest role is above mine. Drag the **Polar Bot** role higher in Server Settings > Roles.`);
  }
}

function caseEmbed(id, action, user, mod, reason, extra = []) {
  const [icon, label, color] = ACTIONS[action];
  return {
    title: `${icon} ${label} · Case ${id}`, color,
    fields: [{ name: 'Member', value: who(user), inline: true }, { name: 'Moderator', value: who(mod), inline: true }, ...extra,
      { name: 'Reason', value: clip(reason || 'No reason given', 1000) }],
    footer: { text: `Member id ${user.id}` },
  };
}

async function notify(bot, user, action, reason, extra = '') {
  const g = await bot.guild().catch(() => ({ name: 'the server' }));
  const [icon, label, color] = ACTIONS[action];
  return bot.dm(user.id, { embeds: [{ title: `${icon} ${label} in ${g.name}`, color, description: `${extra ? `${extra}\n\n` : ''}**Reason:** ${clip(reason || 'No reason given', 1500)}`,
    footer: { text: 'If you think this was a mistake, message a moderator.' } }] });
}

const noDm = (sent) => (sent ? '' : " I couldn't DM them (their DMs are closed).");

// ------------------------------------------------------------------ the actions themselves (shared by commands and buttons)

async function doWarn(x, user, member, reason, { silent = false } = {}) {
  const bot = x.bot;
  const id = bot.addCase({ user, mod: x.user, action: 'warn', reason });
  const count = bot.sql.exec("SELECT COUNT(*) AS n FROM cases WHERE user = ? AND action = 'warn' AND active = 1", user.id).one().n;
  const sent = silent ? true : await notify(bot, user, 'warn', reason, `This is warning ${count}.`);
  await bot.log(caseEmbed(id, 'warn', user, x.user, reason, [{ name: 'Active warnings', value: String(count), inline: true }]));
  let extra = '';
  const esc = bot.config().escalation || {};
  if (esc.enabled !== false && member && count % (esc.at || 3) === 0) {
    const ms = Math.min(MAX_TIMEOUT, (esc.ms || 3600000) * 2 ** (count / (esc.at || 3) - 1));
    const why = `Automatic: ${count} warnings`;
    try {
      await bot.rest.patch(`/guilds/${bot.guildId()}/members/${user.id}`, { communication_disabled_until: new Date(Date.now() + ms).toISOString() }, { reason: why });
      const tid = bot.addCase({ user, mod: botUser(bot), action: 'timeout', reason: why, duration: ms });
      await bot.log(caseEmbed(tid, 'timeout', user, botUser(bot), why, [{ name: 'Length', value: humanDuration(ms), inline: true }]));
      if (!silent) await notify(bot, user, 'timeout', why, `You can talk again ${ts(Date.now() + ms, 'R')}.`);
      extra = ` That's ${count} warnings, so they're timed out for ${humanDuration(ms)} (case ${tid}).`;
    } catch (e) {
      extra = ` They have ${count} warnings, but I couldn't time them out (${e.message}).`;
    }
  }
  return `⚠️ Warned ${who(user)}. Case ${id}; they have ${count} active warning${count > 1 ? 's' : ''}.${noDm(sent)}${extra}`;
}

async function doTimeout(x, user, ms, reason) {
  const bot = x.bot;
  await bot.rest.patch(`/guilds/${bot.guildId()}/members/${user.id}`, { communication_disabled_until: new Date(Date.now() + ms).toISOString() }, { reason: reason || `By ${x.tag}` });
  const id = bot.addCase({ user, mod: x.user, action: 'timeout', reason, duration: ms });
  const sent = await notify(bot, user, 'timeout', reason, `You can talk again ${ts(Date.now() + ms, 'R')}.`);
  await bot.log(caseEmbed(id, 'timeout', user, x.user, reason, [{ name: 'Length', value: humanDuration(ms), inline: true }]));
  return `⏳ Timed out ${who(user)} for ${humanDuration(ms)}. Case ${id}.${noDm(sent)}`;
}

// ------------------------------------------------------------------ commands

export const commands = {
  async warn(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user, member } = target(x);
    const { reason, silent } = options(x.data).opts;
    await checkTarget(x, user, member, 'warn');
    return x.defer(() => doWarn(x, user, member, reason, { silent }));
  },

  async warnings(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user } = target(x);
    const rows = x.bot.sql.exec("SELECT * FROM cases WHERE user = ? AND action = 'warn' AND active = 1 ORDER BY id DESC LIMIT 20", user.id).toArray();
    if (!rows.length) return ephemeral(`${who(user)} has no active warnings.`);
    return ephemeral('', { embeds: [{ title: `Warnings for ${userTag(user)}`, color: COLOR.yellow,
      description: rows.map((r) => `\`#${r.id}\` ${ts(r.created, 'd')} · ${clip(r.reason, 120)} · by ${r.mod_tag}`).join('\n'),
      footer: { text: `${rows.length} active · remove one with /unwarn` } }] });
  },

  async unwarn(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { case: n, reason } = options(x.data).opts;
    const c = x.bot.sql.exec("SELECT * FROM cases WHERE id = ? AND action = 'warn'", n).toArray()[0];
    if (!c) return ephemeral(`Case ${n} isn't a warning.`);
    if (!c.active) return ephemeral(`Case ${n} was already removed.`);
    x.bot.sql.exec('UPDATE cases SET active = 0 WHERE id = ?', n);
    const user = { id: c.user, username: c.user_tag };
    const id = x.bot.addCase({ user, mod: x.user, action: 'unwarn', reason: reason || `Removed warning ${n}` });
    await x.bot.log(caseEmbed(id, 'unwarn', user, x.user, reason || `Removed warning ${n}`));
    return ephemeral(`✅ Removed warning ${n} from ${who(user)}.`);
  },

  async clearwarnings(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user } = target(x);
    const { reason } = options(x.data).opts;
    const n = x.bot.sql.exec("UPDATE cases SET active = 0 WHERE user = ? AND action = 'warn' AND active = 1", user.id).rowsWritten;
    if (!n) return ephemeral(`${who(user)} has no active warnings.`);
    const id = x.bot.addCase({ user, mod: x.user, action: 'clearwarnings', reason: reason || `Cleared ${n} warnings` });
    await x.bot.log(caseEmbed(id, 'clearwarnings', user, x.user, reason || `Cleared ${n} warnings`));
    return ephemeral(`✅ Cleared ${n} warning${n > 1 ? 's' : ''} from ${who(user)}.`);
  },

  async timeout(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user, member } = target(x);
    const { duration, reason } = options(x.data).opts;
    const ms = parseDuration(duration);
    if (!ms || ms < 5000) return ephemeral('Give a length like `10m`, `1h` or `2d`.');
    if (ms > MAX_TIMEOUT) return ephemeral('Discord allows timeouts of up to 28 days. Use /ban with a duration for longer.');
    if (!member) return ephemeral(`${who(user)} isn't in the server.`);
    if (BigInt(member.permissions || 0) & PERM.ADMIN) return ephemeral("Discord doesn't let anyone time out an administrator.");
    await checkTarget(x, user, member, 'time out');
    return x.defer(() => doTimeout(x, user, ms, reason));
  },

  async untimeout(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user, member } = target(x);
    const { reason } = options(x.data).opts;
    if (!member) return ephemeral(`${who(user)} isn't in the server.`);
    if (!member.communication_disabled_until || Date.parse(member.communication_disabled_until) < Date.now()) return ephemeral(`${who(user)} isn't timed out.`);
    await checkTarget(x, user, member, 'end the timeout of');
    return x.defer(async () => {
      await x.bot.rest.patch(`/guilds/${x.bot.guildId()}/members/${user.id}`, { communication_disabled_until: null }, { reason: reason || `By ${x.tag}` });
      const id = x.bot.addCase({ user, mod: x.user, action: 'untimeout', reason });
      await x.bot.log(caseEmbed(id, 'untimeout', user, x.user, reason));
      return `✅ Ended the timeout of ${who(user)}. Case ${id}.`;
    });
  },

  async kick(x) {
    x.need(PERM.KICK, 'Kick Members');
    const { user, member } = target(x);
    const { reason } = options(x.data).opts;
    if (!member) return ephemeral(`${who(user)} isn't in the server.`);
    await checkTarget(x, user, member, 'kick');
    return x.defer(async () => {
      const sent = await notify(x.bot, user, 'kick', reason, 'You can join again with an invite.');
      await x.bot.rest.del(`/guilds/${x.bot.guildId()}/members/${user.id}`, { reason: reason || `By ${x.tag}` });
      const id = x.bot.addCase({ user, mod: x.user, action: 'kick', reason });
      await x.bot.log(caseEmbed(id, 'kick', user, x.user, reason));
      return `👢 Kicked ${who(user)}. Case ${id}.${noDm(sent)}`;
    });
  },

  async ban(x) {
    x.need(PERM.BAN, 'Ban Members');
    const { user, member } = target(x);
    const { reason, duration, delete_messages: del = 0 } = options(x.data).opts;
    const ms = duration ? parseDuration(duration) : null;
    if (duration && (!ms || ms < 60000)) return ephemeral('Give a ban length like `1d` or `2w`, or leave it empty to ban for good.');
    await checkTarget(x, user, member, 'ban');
    return x.defer(async () => {
      const bot = x.bot;
      const sent = member ? await notify(bot, user, ms ? 'tempban' : 'ban', reason, ms ? `The ban ends ${ts(Date.now() + ms, 'R')}.` : '') : true;
      await bot.rest.put(`/guilds/${bot.guildId()}/bans/${user.id}`, { delete_message_seconds: del }, { reason: reason || `By ${x.tag}` });
      bot.forget('bans');
      const id = bot.addCase({ user, mod: x.user, action: ms ? 'tempban' : 'ban', reason, duration: ms });
      if (ms) {
        bot.sql.exec('INSERT INTO tempbans (user, until, case_id) VALUES (?, ?, ?) ON CONFLICT (user) DO UPDATE SET until = excluded.until, case_id = excluded.case_id', user.id, Date.now() + ms, id);
        await bot.schedule();
      } else {
        bot.sql.exec('DELETE FROM tempbans WHERE user = ?', user.id);
      }
      await bot.log(caseEmbed(id, ms ? 'tempban' : 'ban', user, x.user, reason, ms ? [{ name: 'Length', value: humanDuration(ms), inline: true }] : []));
      return `🔨 Banned ${who(user)}${ms ? ` for ${humanDuration(ms)}` : ''}. Case ${id}.${noDm(sent)}`;
    });
  },

  async unban(x) {
    x.need(PERM.BAN, 'Ban Members');
    const { user: raw, reason } = options(x.data).opts;
    const id = String(raw || '').replace(/[^0-9]/g, '');
    if (!/^\d{15,25}$/.test(id)) return ephemeral('Pick someone from the list, or paste their user id.');
    return x.defer(async () => {
      const bot = x.bot;
      let ban;
      try {
        ban = await bot.rest.get(`/guilds/${bot.guildId()}/bans/${id}`);
      } catch {
        return `<@${id}> isn't banned.`;
      }
      await bot.rest.del(`/guilds/${bot.guildId()}/bans/${id}`, { reason: reason || `By ${x.tag}` });
      bot.forget('bans');
      bot.sql.exec('DELETE FROM tempbans WHERE user = ?', id);
      const cid = bot.addCase({ user: ban.user, mod: x.user, action: 'unban', reason });
      await bot.log(caseEmbed(cid, 'unban', ban.user, x.user, reason));
      return `🔓 Unbanned ${who(ban.user)}. Case ${cid}.`;
    });
  },

  async softban(x) {
    x.need(PERM.BAN, 'Ban Members');
    const { user, member } = target(x);
    const { reason } = options(x.data).opts;
    await checkTarget(x, user, member, 'softban');
    return x.defer(async () => {
      const bot = x.bot;
      const sent = member ? await notify(bot, user, 'softban', reason, 'You were removed and your recent messages were deleted. You can join again with an invite.') : true;
      await bot.rest.put(`/guilds/${bot.guildId()}/bans/${user.id}`, { delete_message_seconds: 86400 }, { reason: `Softban: ${reason || x.tag}` });
      await bot.rest.del(`/guilds/${bot.guildId()}/bans/${user.id}`, { reason: 'Softban (unban)' });
      const id = bot.addCase({ user, mod: x.user, action: 'softban', reason });
      await bot.log(caseEmbed(id, 'softban', user, x.user, reason));
      return `🧹 Softbanned ${who(user)}: removed, with a day of messages deleted. Case ${id}.${noDm(sent)}`;
    });
  },

  async purge(x) {
    x.need(PERM.MANAGE_MESSAGES, 'Manage Messages');
    const { amount, user, contains, bots, links, attachments } = options(x.data).opts;
    const channel = x.i.channel_id;
    return x.defer(async () => {
      const bot = x.bot;
      const msgs = await bot.rest.get(`/channels/${channel}/messages?limit=${Math.min(100, Math.max(1, amount))}`);
      const cutoff = Date.now() - 14 * 86400000 + 60000; // Discord only bulk-deletes messages under 14 days old
      const text = contains && contains.toLowerCase();
      const pick = msgs.filter((m) => !m.pinned && snowflakeTime(m.id) > cutoff
        && (!user || m.author.id === user) && (!bots || m.author.bot) && (!text || String(m.content || '').toLowerCase().includes(text))
        && (!links || /https?:\/\/\S+/i.test(m.content || '')) && (!attachments || (m.attachments && m.attachments.length) || (m.embeds || []).some((e) => e.image)));
      if (!pick.length) return 'No messages matched.';
      if (pick.length === 1) await bot.rest.del(`/channels/${channel}/messages/${pick[0].id}`, { reason: `Purge by ${x.tag}` });
      else await bot.rest.post(`/channels/${channel}/messages/bulk-delete`, { messages: pick.map((m) => m.id) }, { reason: `Purge by ${x.tag}` });
      const filters = [user && `from <@${user}>`, text && `containing "${clip(contains, 50)}"`, bots && 'from bots', links && 'with links', attachments && 'with files'].filter(Boolean);
      await bot.log({ title: '🧹 Purge', color: COLOR.grey, fields: [{ name: 'Channel', value: `<#${channel}>`, inline: true },
        { name: 'Deleted', value: String(pick.length), inline: true }, { name: 'Moderator', value: who(x.user), inline: true },
        ...(filters.length ? [{ name: 'Only', value: filters.join(', ') }] : [])] });
      const old = msgs.filter((m) => snowflakeTime(m.id) <= cutoff).length;
      return `🧹 Deleted ${pick.length} message${pick.length > 1 ? 's' : ''}.${old ? ` ${old} older than 14 days were left (Discord doesn't allow deleting those in bulk).` : ''}`;
    });
  },

  async slowmode(x) {
    x.need(PERM.MANAGE_CHANNELS, 'Manage Channels');
    const { delay, channel } = options(x.data).opts;
    const secs = /^\d+$/.test(String(delay).trim()) ? Number(delay) : Math.round((parseDuration(delay) || -1000) / 1000);
    if (secs < 0 || secs > 21600) return ephemeral('Give a delay from `0` (off) to `6h`.');
    const ch = channel || x.i.channel_id;
    await x.bot.rest.patch(`/channels/${ch}`, { rate_limit_per_user: secs }, { reason: `Slowmode by ${x.tag}` });
    return ephemeral(secs ? `🐢 Slowmode in <#${ch}>: one message every ${humanDuration(secs * 1000)}.` : `Slowmode is off in <#${ch}>.`);
  },

  async lock(x) {
    x.need(PERM.MANAGE_CHANNELS, 'Manage Channels');
    const { channel, reason } = options(x.data).opts;
    const ch = channel || x.i.channel_id;
    return x.defer(async () => {
      const done = await lockChannel(x.bot, ch, reason, x);
      return done ? `🔒 Locked <#${ch}>.` : `<#${ch}> is already locked.`;
    });
  },

  async unlock(x) {
    x.need(PERM.MANAGE_CHANNELS, 'Manage Channels');
    const ch = options(x.data).opts.channel || x.i.channel_id;
    return x.defer(async () => ((await unlockChannel(x.bot, ch, x)) ? `🔓 Unlocked <#${ch}>.` : `<#${ch}> wasn't locked.`));
  },

  async lockdown(x) {
    x.need(PERM.MANAGE_CHANNELS, 'Manage Channels');
    const { sub, opts } = options(x.data);
    const bot = x.bot;
    if (sub === 'start') {
      return x.defer(async () => {
        const guild = await bot.guild();
        const base = BigInt((guild.roles.find((r) => r.id === guild.id) || {}).permissions || 0);
        const channels = await bot.rest.get(`/guilds/${guild.id}/channels`);
        const locked = [];
        for (const c of channels.filter((c) => [0, 5, 15].includes(c.type))) {
          const o = (c.permission_overwrites || []).find((p) => p.id === guild.id) || { allow: '0', deny: '0' };
          const eff = (base & ~BigInt(o.deny)) | BigInt(o.allow);
          if ((eff & PERM.VIEW) && (eff & PERM.SEND)) {
            if (await lockChannel(bot, c.id, opts.reason || 'Server lockdown', x, { quiet: true, channel: c })) locked.push(c.id);
          }
        }
        bot.setConfig({ lockdown: [...new Set([...(bot.config().lockdown || []), ...locked])] });
        for (const id of locked) {
          await bot.rest.post(`/channels/${id}/messages`, { embeds: [{ color: COLOR.red, title: '🔒 Server lockdown',
            description: `Chat is paused while the moderators sort something out.${opts.reason ? `\n**Reason:** ${clip(opts.reason, 300)}` : ''}` }] }).catch(() => {});
        }
        await bot.log({ title: '🔒 Lockdown started', color: COLOR.red, fields: [{ name: 'Moderator', value: who(x.user), inline: true },
          { name: 'Channels', value: String(locked.length), inline: true }, { name: 'Reason', value: opts.reason || 'No reason given' }] });
        return `🔒 Locked ${locked.length} channel${locked.length === 1 ? '' : 's'}. End it with \`/lockdown end\`.`;
      });
    }
    return x.defer(async () => {
      const list = bot.config().lockdown || [];
      if (!list.length) return "There's no lockdown on.";
      let n = 0;
      for (const id of list) {
        if (await unlockChannel(bot, id, x, { quiet: true }).catch(() => false)) {
          n++;
          await bot.rest.post(`/channels/${id}/messages`, { embeds: [{ color: COLOR.green, title: '🔓 Lockdown over', description: 'You can chat again. Thanks for waiting.' }] }).catch(() => {});
        }
      }
      bot.setConfig({ lockdown: null });
      await bot.log({ title: '🔓 Lockdown ended', color: COLOR.green, fields: [{ name: 'Moderator', value: who(x.user), inline: true }, { name: 'Channels', value: String(n), inline: true }] });
      return `🔓 Unlocked ${n} channel${n === 1 ? '' : 's'}.`;
    });
  },

  async nick(x) {
    x.need(PERM.MANAGE_NICKNAMES, 'Manage Nicknames');
    const { user, member } = target(x);
    const { nickname } = options(x.data).opts;
    if (!member) return ephemeral(`${who(user)} isn't in the server.`);
    if (user.id !== x.uid) await checkTarget(x, user, member, 'rename');
    await x.bot.rest.patch(`/guilds/${x.bot.guildId()}/members/${user.id}`, { nick: nickname || null }, { reason: `By ${x.tag}` });
    return ephemeral(nickname ? `Renamed ${who(user)} to **${clip(nickname, 32)}**.` : `Reset ${who(user)}'s nickname.`);
  },

  async role(x) {
    x.need(PERM.MANAGE_ROLES, 'Manage Roles');
    const { sub, opts } = options(x.data);
    const { user, member } = target(x);
    const role = ((x.data.resolved || {}).roles || {})[opts.role];
    if (!member) return ephemeral(`${who(user)} isn't in the server.`);
    if (!role || role.managed || role.id === x.i.guild_id) return ephemeral("That role is managed by Discord or another app, so it can't be given by hand.");
    const g = await x.bot.guild();
    if (x.uid !== g.owner_id && role.position >= await x.bot.top(x.member.roles)) return ephemeral(`**${role.name}** is the same as or above your highest role.`);
    if (role.position >= await x.bot.top((await x.bot.me()).roles)) return ephemeral(`**${role.name}** is above my highest role. Drag the **Polar Bot** role above it in Server Settings > Roles.`);
    const adding = sub === 'add';
    if (adding === member.roles.includes(role.id)) return ephemeral(`${who(user)} ${adding ? 'already has' : "doesn't have"} **${role.name}**.`);
    const path = `/guilds/${x.bot.guildId()}/members/${user.id}/roles/${role.id}`;
    if (adding) await x.bot.rest.put(path, undefined, { reason: `By ${x.tag}` });
    else await x.bot.rest.del(path, { reason: `By ${x.tag}` });
    await x.bot.log({ title: adding ? '➕ Role given' : '➖ Role taken', color: COLOR.purple, fields: [{ name: 'Member', value: who(user), inline: true },
      { name: 'Role', value: `<@&${role.id}>`, inline: true }, { name: 'Moderator', value: who(x.user), inline: true }] });
    return ephemeral(`${adding ? 'Gave' : 'Took'} **${role.name}** ${adding ? 'to' : 'from'} ${who(user)}.`);
  },

  async note(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user } = target(x);
    const { note } = options(x.data).opts;
    const id = x.bot.addCase({ user, mod: x.user, action: 'note', reason: note });
    await x.bot.log(caseEmbed(id, 'note', user, x.user, note));
    return ephemeral(`📝 Noted on ${who(user)}'s history (case ${id}). They aren't told.`);
  },

  history(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    return historyReply(x.bot, target(x).user);
  },

  case(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const n = options(x.data).opts.number;
    const c = x.bot.sql.exec('SELECT * FROM cases WHERE id = ?', n).toArray()[0];
    if (!c) return ephemeral(`There's no case ${n}.`);
    const e = caseEmbed(c.id, c.action, { id: c.user, username: c.user_tag }, { id: c.mod, username: c.mod_tag }, c.reason,
      [...(c.duration ? [{ name: 'Length', value: humanDuration(c.duration), inline: true }] : []), { name: 'When', value: ts(c.created), inline: true }]);
    if (!c.active) e.footer.text += ' · removed';
    return ephemeral('', { embeds: [e] });
  },

  async reason(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { case: n, reason } = options(x.data).opts;
    const c = x.bot.sql.exec('SELECT * FROM cases WHERE id = ?', n).toArray()[0];
    if (!c) return ephemeral(`There's no case ${n}.`);
    if (c.mod !== x.uid && !x.can(PERM.MANAGE_GUILD)) return ephemeral('Only the moderator who made a case (or an admin) can change its reason.');
    x.bot.sql.exec('UPDATE cases SET reason = ? WHERE id = ?', reason, n);
    await x.bot.log({ title: `✏️ Case ${n} reason changed`, color: COLOR.grey, fields: [{ name: 'Before', value: clip(c.reason || 'No reason given', 1000) },
      { name: 'Now', value: clip(reason, 1000) }, { name: 'By', value: who(x.user) }] });
    return ephemeral(`Updated case ${n}.`);
  },

  async userinfo(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user, member } = target(x);
    const bot = x.bot;
    const warns = bot.sql.exec("SELECT COUNT(*) AS n FROM cases WHERE user = ? AND action = 'warn' AND active = 1", user.id).one().n;
    const total = bot.sql.exec("SELECT COUNT(*) AS n FROM cases WHERE user = ? AND action != 'note'", user.id).one().n;
    const fields = [{ name: 'Account made', value: `${ts(snowflakeTime(user.id), 'D')} (${ts(snowflakeTime(user.id), 'R')})`, inline: true }];
    if (member) {
      fields.push({ name: 'Joined', value: member.joined_at ? `${ts(Date.parse(member.joined_at), 'D')} (${ts(Date.parse(member.joined_at), 'R')})` : 'unknown', inline: true });
      const roles = (member.roles || []).map((r) => `<@&${r}>`);
      fields.push({ name: `Roles (${roles.length})`, value: clip(roles.join(' ') || 'none', 1000) });
      if (member.communication_disabled_until && Date.parse(member.communication_disabled_until) > Date.now()) {
        fields.push({ name: 'Timed out', value: `until ${ts(Date.parse(member.communication_disabled_until))}`, inline: true });
      }
    } else {
      fields.push({ name: 'In the server', value: 'No', inline: true });
    }
    fields.push({ name: 'Warnings', value: String(warns), inline: true }, { name: 'Cases', value: `${total} (see /history)`, inline: true });
    const avatar = user.avatar ? `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png?size=128` : null;
    return ephemeral('', { embeds: [{ title: userTag(user), description: `<@${user.id}> · id ${user.id}${user.bot ? ' · bot' : ''}`, color: COLOR.blue, fields,
      ...(avatar ? { thumbnail: { url: avatar } } : {}) }] });
  },

  async report(x) {
    const { user } = target(x);
    const { reason } = options(x.data).opts;
    if (!user || user.id === x.uid) return ephemeral("You can't report yourself.");
    throttleReports(x.uid);
    return x.defer(async () => {
      await postReport(x, { user, reason, channel: x.i.channel_id });
      return '🚩 Thanks. Only the moderators can see your report.';
    });
  },

  // right-click a member > Apps > Warn / Moderation history
  Warn(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const u = ((x.data.resolved || {}).users || {})[x.data.target_id];
    return modal(`warn:${x.data.target_id}`, clip(`Warn ${userTag(u)}`, 45), [['reason', 'Reason (they see it)', 2, true, 400]]);
  },

  'Moderation history'(x) {
    x.need(PERM.MODERATE, 'Timeout Members');
    return historyReply(x.bot, ((x.data.resolved || {}).users || {})[x.data.target_id] || { id: x.data.target_id, username: 'that member' });
  },

  // right-click a message > Apps > Report message
  'Report message'(x) {
    const m = ((x.data.resolved || {}).messages || {})[x.data.target_id];
    if (!m) return ephemeral("I can't see that message.");
    if (m.author.id === x.uid) return ephemeral("You can't report your own message.");
    return modal(`repmsg:${m.channel_id || x.i.channel_id}:${m.id}`, 'Report message', [['reason', 'What is wrong with it? (optional)', 2, false, 400]]);
  },
};

function historyReply(bot, user) {
  const rows = bot.sql.exec('SELECT * FROM cases WHERE user = ? ORDER BY id DESC LIMIT 25', user.id).toArray();
  if (!rows.length) return ephemeral(`${who(user)} has a clean record.`);
  const lines = rows.map((r) => {
    const [icon, label] = ACTIONS[r.action] || ['•', r.action];
    const line = `\`#${r.id}\` ${icon} ${label}${r.duration ? ` (${humanDuration(r.duration)})` : ''} · ${clip(r.reason || 'no reason', 90)} · ${r.mod_tag} · ${ts(r.created, 'R')}`;
    return r.active ? line : `~~${line}~~`;
  });
  return ephemeral('', { embeds: [{ title: `History of ${userTag(user)}`, color: COLOR.blue, description: clip(lines.join('\n'), 4000),
    footer: { text: `${rows.length} case${rows.length > 1 ? 's' : ''}${rows.length === 25 ? ' (newest 25)' : ''} · struck through = removed` } }] });
}

// ------------------------------------------------------------------ locking

async function lockChannel(bot, id, reason, x, { quiet = false, channel = null } = {}) {
  const c = channel || await bot.rest.get(`/channels/${id}`);
  const everyone = c.guild_id || bot.guildId();
  const o = (c.permission_overwrites || []).find((p) => p.id === everyone);
  const deny = BigInt(o ? o.deny : 0), allow = BigInt(o ? o.allow : 0);
  if ((deny & PERM.SEND) === PERM.SEND) return false;
  const locks = bot.config().locks || {};
  const staff = bot.config().staff || [];
  const added = [];
  // staff keep talking: give their roles an explicit allow where they don't have an overwrite yet
  for (const r of staff) {
    if ((c.permission_overwrites || []).some((p) => p.id === r)) continue;
    await bot.rest.put(`/channels/${id}/permissions/${r}`, { type: 0, allow: String(PERM.SEND | PERM.SEND_IN_THREADS), deny: '0' }, { reason: 'Lock: staff can still talk' }).catch(() => {});
    added.push(r);
  }
  await bot.rest.put(`/channels/${id}/permissions/${everyone}`, { type: 0, allow: String(allow & ~TALK), deny: String(deny | TALK) }, { reason: `Locked by ${x.tag}${reason ? `: ${reason}` : ''}` });
  locks[id] = { had: o ? { allow: o.allow, deny: o.deny } : null, added };
  bot.setConfig({ locks });
  if (!quiet) {
    await bot.rest.post(`/channels/${id}/messages`, { embeds: [{ color: COLOR.red, title: '🔒 This channel is locked', description: reason ? `**Reason:** ${clip(reason, 300)}` : 'Only staff can talk here for now.' }] }).catch(() => {});
    await bot.log({ title: '🔒 Channel locked', color: COLOR.red, fields: [{ name: 'Channel', value: `<#${id}>`, inline: true }, { name: 'Moderator', value: who(x.user), inline: true }, { name: 'Reason', value: reason || 'No reason given' }] });
  }
  return true;
}

async function unlockChannel(bot, id, x, { quiet = false } = {}) {
  const locks = bot.config().locks || {};
  const c = await bot.rest.get(`/channels/${id}`);
  const everyone = c.guild_id || bot.guildId();
  const lock = locks[id];
  const o = (c.permission_overwrites || []).find((p) => p.id === everyone);
  if (!lock && !(o && (BigInt(o.deny) & PERM.SEND))) return false;
  if (lock && lock.had) await bot.rest.put(`/channels/${id}/permissions/${everyone}`, { type: 0, ...lock.had }, { reason: `Unlocked by ${x.tag}` });
  else if (lock) await bot.rest.del(`/channels/${id}/permissions/${everyone}`, { reason: `Unlocked by ${x.tag}` });
  else await bot.rest.put(`/channels/${id}/permissions/${everyone}`, { type: 0, allow: o.allow, deny: String(BigInt(o.deny) & ~TALK) }, { reason: `Unlocked by ${x.tag}` });
  for (const r of (lock && lock.added) || []) await bot.rest.del(`/channels/${id}/permissions/${r}`).catch(() => {});
  delete locks[id];
  bot.setConfig({ locks });
  if (!quiet) {
    await bot.rest.post(`/channels/${id}/messages`, { embeds: [{ color: COLOR.green, title: '🔓 Unlocked', description: 'You can talk here again.' }] }).catch(() => {});
    await bot.log({ title: '🔓 Channel unlocked', color: COLOR.green, fields: [{ name: 'Channel', value: `<#${id}>`, inline: true }, { name: 'Moderator', value: who(x.user), inline: true }] });
  }
  return true;
}

// ------------------------------------------------------------------ reports

function throttleReports(uid) {
  const now = Date.now();
  const recent = (reports.get(uid) || []).filter((t) => now - t < 10 * 60000);
  if (recent.length >= 3) refuse('You sent a few reports just now. The moderators will look at them; wait a bit before sending more.');
  recent.push(now);
  reports.set(uid, recent);
  if (reports.size > 5000) reports.clear();
}

async function postReport(x, { user, reason, channel, message = null }) {
  const bot = x.bot;
  const cfg = bot.config();
  const to = cfg.reports || cfg.modlog;
  if (!to) refuse("The moderators haven't set a reports channel yet. Message one of them instead.");
  const fields = [{ name: 'Reported', value: who(user), inline: true }, { name: 'By', value: who(x.user), inline: true },
    { name: 'Where', value: `<#${channel}>`, inline: true }];
  if (message) fields.push({ name: 'Message', value: clip(message.content || (message.attachments && message.attachments.length ? '(a file)' : '(no text)'), 1000) });
  fields.push({ name: 'Reason', value: clip(reason || 'No reason given', 1000) });
  const buttons = [];
  if (message) buttons.push(button(`rep:del:${channel}:${message.id}`, 'Delete message', 4));
  buttons.push(button(`rep:warn:${user.id}`, 'Warn', 2), button(`rep:to:${user.id}`, 'Timeout 1h', 2), button('rep:done', 'Dismiss', 3));
  if (message) buttons.push({ type: 2, style: 5, label: 'Go to message', url: `https://discord.com/channels/${x.i.guild_id}/${channel}/${message.id}` });
  const staffPing = (cfg.staff || []).slice(0, 3).map((r) => `<@&${r}>`).join(' ');
  await bot.rest.post(`/channels/${to}/messages`, {
    content: cfg.reports && staffPing ? staffPing : '',
    allowed_mentions: { roles: cfg.reports ? (cfg.staff || []).slice(0, 3) : [] },
    embeds: [{ title: '🚩 Report', color: COLOR.red, fields, timestamp: new Date().toISOString() }],
    components: [row(...buttons)],
  });
}

/** Marks a report as handled: adds who did what and takes away the button that was used (or all of them). */
function handled(x, text, { remove = null, all = false } = {}) {
  const msg = x.i.message || {};
  const embed = { ...(msg.embeds || [{}])[0] };
  embed.fields = [...(embed.fields || []), { name: '✅ Handled', value: `${text} · <@${x.uid}> ${ts(Date.now(), 'R')}` }];
  const components = all ? [] : (msg.components || []).map((r) => ({ ...r, components: r.components.filter((c) => !remove || !String(c.custom_id || '').startsWith(remove)) }))
    .filter((r) => r.components.length);
  if (all) embed.color = COLOR.grey;
  return { type: 7, data: { embeds: [embed], components, allowed_mentions: { parse: [] } } };
}

export const components = {
  async rep(x, [action, a, b]) {
    const bot = x.bot;
    if (!bot.isStaff(x.member)) return ephemeral('Only staff can act on reports.');
    if (action === 'done') return handled(x, 'Dismissed', { all: true });
    if (action === 'del') {
      x.need(PERM.MANAGE_MESSAGES, 'Manage Messages');
      try {
        await bot.rest.del(`/channels/${a}/messages/${b}`, { reason: `Report handled by ${x.tag}` });
      } catch (e) {
        if (e.status !== 404) throw e;
      }
      return handled(x, 'Message deleted', { remove: 'rep:del' });
    }
    const { user, member } = await fetchMember(bot, a);
    if (action === 'warn') {
      x.need(PERM.MODERATE, 'Timeout Members');
      await checkTarget(x, user, member, 'warn');
      return modal(`repwarn:${a}`, clip(`Warn ${userTag(user)}`, 45), [['reason', 'Reason (they see it)', 2, true, 400]]);
    }
    if (action === 'to') {
      x.need(PERM.MODERATE, 'Timeout Members');
      if (!member) return ephemeral(`${who(user || { id: a })} isn't in the server any more.`);
      await checkTarget(x, user, member, 'time out');
      const why = ((x.i.message.embeds || [{}])[0].fields || []).find((f) => f.name === 'Reason');
      const done = handled(x, 'Timed out for 1 hour', { remove: 'rep:to' });
      x.work = async () => {
        const result = await doTimeout(x, user, 3600000, `Reported: ${clip(why ? why.value : '', 300)}`);
        await x.followup({ content: result, flags: 64 }).catch(() => {});
        return done.data;
      };
      return { type: 6 }; // "working on it"; the report updates when it's done
    }
    return ephemeral('Unknown report action.');
  },
};

export const modals = {
  async warn(x, [id]) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user, member } = await fetchMember(x.bot, id);
    await checkTarget(x, user, member, 'warn');
    const { reason } = modalValues(x.data);
    return x.defer(() => doWarn(x, user, member, reason));
  },

  async repwarn(x, [id]) {
    x.need(PERM.MODERATE, 'Timeout Members');
    const { user, member } = await fetchMember(x.bot, id);
    await checkTarget(x, user, member, 'warn');
    const { reason } = modalValues(x.data);
    const done = handled(x, `Warned (${clip(reason, 80)})`, { remove: 'rep:warn' });
    x.work = async () => {
      const result = await doWarn(x, user, member, reason);
      await x.followup({ content: result, flags: 64 }).catch(() => {});
      return done.data;
    };
    return { type: 6 };
  },

  async repmsg(x, [channel, id]) {
    throttleReports(x.uid);
    const { reason } = modalValues(x.data);
    return x.defer(async () => {
      let message;
      try {
        message = await x.bot.rest.get(`/channels/${channel}/messages/${id}`);
      } catch {
        return 'That message is gone already.';
      }
      await postReport(x, { user: message.author, reason, channel, message });
      return '🚩 Thanks. Only the moderators can see your report.';
    });
  },
};

export const autocomplete = {
  async unban(x) {
    const { opts } = options(x.data);
    const q = String(opts.user || '').toLowerCase();
    if (!x.can(PERM.BAN)) return [];
    const bans = await x.bot.cached('bans', () => x.bot.rest.get(`/guilds/${x.bot.guildId()}/bans?limit=1000`));
    return bans.filter((b) => !q || userTag(b.user).toLowerCase().includes(q) || b.user.id.startsWith(q))
      .slice(0, 25).map((b) => ({ name: clip(`${userTag(b.user)} · ${b.reason || 'no reason'}`, 100), value: b.user.id }));
  },
};

/** Lifts temporary bans that ran out (called by the bot's alarm). */
export async function expireBans(bot, now) {
  for (const b of bot.sql.exec('SELECT * FROM tempbans WHERE until <= ?', now).toArray()) {
    let user = { id: b.user, username: b.user };
    try {
      const ban = await bot.rest.get(`/guilds/${bot.guildId()}/bans/${b.user}`);
      user = ban.user;
      await bot.rest.del(`/guilds/${bot.guildId()}/bans/${b.user}`, { reason: `Temporary ban (case ${b.case_id}) ran out` });
    } catch (e) {
      if (e.status !== 404) {
        console.error('unban:', e.message);
        continue; // try again next time
      }
    }
    bot.sql.exec('DELETE FROM tempbans WHERE user = ?', b.user);
    const id = bot.addCase({ user, mod: botUser(bot), action: 'unban', reason: `Temporary ban (case ${b.case_id}) ran out` });
    await bot.log(caseEmbed(id, 'unban', user, botUser(bot), `Temporary ban (case ${b.case_id}) ran out`));
  }
}

export { Refusal, checkTarget, fetchMember };
