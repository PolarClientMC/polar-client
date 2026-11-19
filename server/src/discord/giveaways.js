// Giveaways: an Enter button, winners picked at random when time is up (by the bot's alarm), and, when the prize is a
// Polar cosmetic, a fresh one-use code for each winner that only they can see (the "Claim your code" button).
import catalog from '../catalog.js';
import { COLOR, PERM, ephemeral, options, button, row, parseDuration, humanDuration, ts, clip } from './util.js';

const ITEMS = new Map(catalog.items.map((i) => [i.id, i]));
const MAX_LENGTH = 60 * 86400000;
const TYPE_NAMES = { cape: 'Cape', wings: 'Wings', head: 'Hat', back: 'Back item', aura: 'Aura' };

export const itemName = (id) => (ITEMS.get(id) || { name: id }).name;

/** Cosmetics matching what someone typed, for autocomplete. */
export function searchItems(q, { specialFirst = false } = {}) {
  const s = String(q || '').toLowerCase();
  let list = catalog.items.filter((i) => !s || i.name.toLowerCase().includes(s) || i.id.includes(s));
  if (specialFirst) list = [...list.filter((i) => i.special), ...list.filter((i) => !i.special)];
  return list.slice(0, 25).map((i) => ({ name: clip(`${i.name} · ${TYPE_NAMES[i.type] || i.type}${i.special ? ' · code-only' : ''}`, 100), value: i.id }));
}

function entries(bot, id) {
  return bot.sql.exec('SELECT COUNT(*) AS n FROM entries WHERE giveaway = ?', id).one().n;
}

function giveawayMessage(bot, g, { ended = false, winners = [] } = {}) {
  const n = entries(bot, g.id);
  const lines = [];
  if (g.description) lines.push(g.description, '');
  if (g.status === 'cancelled') lines.push('This giveaway was cancelled.');
  else if (ended) lines.push(`Ended ${ts(Date.now(), 'R')}.`, winners.length ? `**Winner${winners.length > 1 ? 's' : ''}:** ${winners.map((w) => `<@${w}>`).join(', ')}` : 'Nobody entered.');
  else lines.push('Press **Enter** to join.', `Ends ${ts(g.ends, 'R')} (${ts(g.ends, 'f')})`);
  lines.push(`**Winners:** ${g.winners} · **Hosted by** <@${g.host}>`);
  if (g.role) lines.push(`**Who can enter:** members with <@&${g.role}>`);
  if (g.item) lines.push(`Each winner gets a code for **${itemName(g.item)}**.`);
  const site = bot.config().site;
  const live = !ended && g.status === 'running';
  return {
    embeds: [{ title: clip(`🎁 ${g.prize}`, 256), description: lines.join('\n'), color: live ? COLOR.ice : COLOR.grey,
      footer: { text: `Giveaway #${g.id} · ${n} ${n === 1 ? 'entry' : 'entries'}` },
      ...(g.item && site ? { thumbnail: { url: `${site}/cosmetics/thumbs/${g.item}.png` } } : {}) }],
    components: [row(button(`gw:enter:${g.id}`, live ? `Enter (${n})` : 'Ended', 1, { emoji: { name: '🎉' }, disabled: !live }))],
    allowed_mentions: { parse: [] },
  };
}

async function refresh(bot, g, extra) {
  if (!g.message) return;
  await bot.rest.patch(`/channels/${g.channel}/messages/${g.message}`, giveawayMessage(bot, g, extra)).catch((e) => console.error('giveaway edit:', e.message));
}

function pickWinners(bot, g, n) {
  const pool = bot.sql.exec('SELECT user FROM entries WHERE giveaway = ? AND user NOT IN (SELECT user FROM winners WHERE giveaway = ?)', g.id, g.id).toArray().map((r) => r.user);
  const out = [];
  while (out.length < n && pool.length) {
    const r = crypto.getRandomValues(new Uint32Array(1))[0] % pool.length;
    out.push(pool.splice(r, 1)[0]);
  }
  return out;
}

/** Ends a giveaway (or rerolls one): picks winners, makes their codes, and announces them. */
async function finish(bot, g, { reroll = 0 } = {}) {
  const n = reroll || g.winners;
  const won = pickWinners(bot, g, n);
  const round = (bot.sql.exec('SELECT MAX(round) AS r FROM winners WHERE giveaway = ?', g.id).one().r || 0) + 1;
  for (const user of won) {
    let code = null;
    if (g.item) {
      try {
        const res = await bot.hub('/v1/admin/codes', { method: 'POST', body: JSON.stringify({ item: g.item, uses: 1, count: 1, note: `Discord giveaway #${g.id}` }) });
        code = res.made[0];
      } catch (e) {
        console.error('giveaway code:', e.message);
      }
    }
    bot.sql.exec('INSERT OR REPLACE INTO winners (giveaway, user, code, round) VALUES (?, ?, ?, ?)', g.id, user, code, round);
  }
  if (!reroll) {
    bot.sql.exec("UPDATE giveaways SET status = 'ended' WHERE id = ?", g.id);
    g.status = 'ended';
  }
  const all = bot.sql.exec('SELECT user FROM winners WHERE giveaway = ? AND round = ?', g.id, round).toArray().map((r) => r.user);
  await refresh(bot, g, { ended: true, winners: reroll ? bot.sql.exec('SELECT user FROM winners WHERE giveaway = ?', g.id).toArray().map((r) => r.user) : all });
  const text = won.length
    ? `🎉 ${reroll ? 'New winner' : 'Congratulations'}${won.length > 1 ? 's' : ''}: ${won.map((w) => `<@${w}>`).join(', ')}! You won **${g.prize}**.`
      + (g.item ? '\nPress **Claim your code** below to get it. Only winners can see their code.' : '')
    : `Nobody ${reroll ? 'else ' : ''}entered **${g.prize}**, so there's no winner.`;
  await bot.rest.post(`/channels/${g.channel}/messages`, {
    content: text, allowed_mentions: { users: won },
    ...(g.message ? { message_reference: { message_id: g.message, fail_if_not_exists: false } } : {}),
    ...(won.length && g.item ? { components: [row(button(`gw:claim:${g.id}`, 'Claim your code', 3, { emoji: { name: '🎁' } }))] } : {}),
  }).catch((e) => console.error('giveaway announce:', e.message));
  await bot.log({ title: reroll ? `🎁 Giveaway #${g.id} rerolled` : `🎁 Giveaway #${g.id} ended`, color: COLOR.ice,
    fields: [{ name: 'Prize', value: clip(g.prize, 200), inline: true }, { name: 'Entries', value: String(entries(bot, g.id)), inline: true },
      { name: 'Winners', value: won.length ? won.map((w) => `<@${w}>`).join(', ') : 'none' }] });
  return won;
}

export async function endDue(bot, now) {
  for (const g of bot.sql.exec("SELECT * FROM giveaways WHERE status = 'running' AND ends <= ?", now).toArray()) await finish(bot, g);
}

const byId = (bot, id) => bot.sql.exec('SELECT * FROM giveaways WHERE id = ?', Number(id)).toArray()[0];

export const commands = {
  async giveaway(x) {
    x.need(PERM.MANAGE_EVENTS, 'Manage Events');
    const bot = x.bot;
    const { sub, opts } = options(x.data);
    if (sub === 'start') {
      const ms = parseDuration(opts.duration);
      if (!ms || ms < 60000) return ephemeral('Give a length like `30m`, `1d` or `1w`.');
      if (ms > MAX_LENGTH) return ephemeral('Giveaways can run for up to 60 days.');
      if (opts.cosmetic && !ITEMS.has(opts.cosmetic)) return ephemeral("I don't know that cosmetic. Pick one from the list.");
      const channel = opts.channel || bot.config().giveaways || x.i.channel_id;
      const g = bot.sql.exec(`INSERT INTO giveaways (channel, prize, description, item, winners, ends, host, role, status, created)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'running', ?) RETURNING *`, channel, opts.prize, opts.description || null, opts.cosmetic || null,
      opts.winners || 1, Date.now() + ms, x.uid, opts.required_role || null, Date.now()).one();
      return x.defer(async () => {
        try {
          const msg = await bot.rest.post(`/channels/${channel}/messages`, giveawayMessage(bot, g));
          bot.sql.exec('UPDATE giveaways SET message = ? WHERE id = ?', msg.id, g.id);
        } catch (e) {
          bot.sql.exec('DELETE FROM giveaways WHERE id = ?', g.id);
          throw e;
        }
        await bot.schedule();
        return `🎁 Giveaway #${g.id} is running in <#${channel}> for ${humanDuration(ms)}.`;
      });
    }
    if (sub === 'list') {
      const rows = bot.sql.exec("SELECT * FROM giveaways WHERE status = 'running' ORDER BY ends").toArray();
      if (!rows.length) return ephemeral('No giveaways are running.');
      return ephemeral(rows.map((g) => `**#${g.id}** ${clip(g.prize, 60)} · <#${g.channel}> · ends ${ts(g.ends, 'R')} · ${entries(bot, g.id)} entries`).join('\n'));
    }
    const g = byId(bot, opts.id);
    if (!g) return ephemeral(`There's no giveaway #${opts.id}.`);
    if (sub === 'end') {
      if (g.status !== 'running') return ephemeral(`Giveaway #${g.id} isn't running.`);
      return x.defer(async () => {
        const won = await finish(bot, g);
        await bot.schedule();
        return `Ended giveaway #${g.id}: ${won.length ? won.map((w) => `<@${w}>`).join(', ') : 'no entries'}.`;
      });
    }
    if (sub === 'reroll') {
      if (g.status !== 'ended') return ephemeral(`Giveaway #${g.id} hasn't ended yet. Use /giveaway end first.`);
      return x.defer(async () => {
        const won = await finish(bot, g, { reroll: opts.winners || 1 });
        return won.length ? `New winner${won.length > 1 ? 's' : ''}: ${won.map((w) => `<@${w}>`).join(', ')}.` : 'Everyone who entered has already won.';
      });
    }
    if (sub === 'cancel') {
      if (g.status !== 'running') return ephemeral(`Giveaway #${g.id} isn't running.`);
      bot.sql.exec("UPDATE giveaways SET status = 'cancelled' WHERE id = ?", g.id);
      g.status = 'cancelled';
      return x.defer(async () => {
        await refresh(bot, g, { ended: true });
        await bot.schedule();
        return `Cancelled giveaway #${g.id}.`;
      });
    }
    return ephemeral('Unknown giveaway command.');
  },
};

export const components = {
  async gw(x, [action, id]) {
    const bot = x.bot;
    const g = byId(bot, id);
    if (!g) return ephemeral('That giveaway is gone.');
    if (action === 'claim') {
      const w = bot.sql.exec('SELECT * FROM winners WHERE giveaway = ? AND user = ?', g.id, x.uid).toArray()[0];
      if (!w) return ephemeral('Only the winners can claim this. Better luck next time!');
      if (!w.code) return ephemeral("Your code couldn't be made automatically. A moderator will send it to you; open a ticket if they haven't.");
      return ephemeral(`🎁 Your code for **${itemName(g.item)}**:\n## \`${w.code}\`\nRedeem it in the Polar Client launcher: **Cosmetics > Redeem**. It works once, so don't share it.`);
    }
    if (g.status !== 'running' || g.ends <= Date.now()) return ephemeral('This giveaway has ended.');
    if (action === 'enter') {
      if (g.role && !(x.member.roles || []).includes(g.role)) return ephemeral(`Only members with <@&${g.role}> can enter this one.`);
      const before = bot.sql.exec('INSERT OR IGNORE INTO entries (giveaway, user) VALUES (?, ?)', g.id, x.uid).rowsWritten;
      if (!before) {
        return ephemeral("You're already in. Want out?", { components: [row(button(`gw:leave:${g.id}`, 'Leave the giveaway', 4))] });
      }
      x.work = async () => {
        await x.followup({ content: "🎉 You're in! Good luck.", flags: 64 });
        return null;
      };
      return { type: 7, data: giveawayMessage(bot, g) }; // the button shows the new count
    }
    if (action === 'leave') {
      bot.sql.exec('DELETE FROM entries WHERE giveaway = ? AND user = ?', g.id, x.uid);
      x.work = async () => {
        await refresh(bot, g);
        return null;
      };
      return { type: 7, data: { content: 'You left the giveaway.', components: [] } };
    }
    return ephemeral('Unknown giveaway button.');
  },
};

export const autocomplete = {
  giveaway(x) {
    const { sub, focused, opts } = options(x.data);
    if (focused === 'cosmetic') return searchItems(opts.cosmetic, { specialFirst: true });
    const status = sub === 'reroll' ? 'ended' : 'running';
    return x.bot.sql.exec('SELECT * FROM giveaways WHERE status = ? ORDER BY id DESC LIMIT 25', status).toArray()
      .map((g) => ({ name: clip(`#${g.id} · ${g.prize}${status === 'running' ? ` · ${entries(x.bot, g.id)} entries` : ''}`, 100), value: g.id }));
  },
};

export { finish, ITEMS, TYPE_NAMES };
