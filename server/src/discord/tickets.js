// Tickets: a button opens a short form; the bot makes a private thread only the member and the staff can see. Staff
// can claim it; the member or staff close it, which locks the thread and puts a transcript in the mod log.
import { COLOR, PERM, ephemeral, options, button, row, modal, modalValues, userTag, clip, ts } from './util.js';

const MAX_OPEN_PER_HOUR = 3;

export function panelMessage() {
  return {
    embeds: [{
      title: '🎫 Need private help?', color: COLOR.ice,
      description: 'Open a ticket for things you can\'t post in public: account problems, reporting a member, ban appeals, '
        + 'or questions about a cosmetic code.\n\nOnly you and the staff can see your ticket.\n\n'
        + 'For normal questions the support forum is quicker.',
    }],
    components: [row(button('ticket:open', 'Open a ticket', 1, { emoji: { name: '🎫' } }))],
  };
}

const ticketIn = (bot, channel) => bot.sql.exec("SELECT * FROM tickets WHERE thread = ? AND status = 'open'", channel).toArray()[0];

function ticketMessage(t, opener, details, claimedBy = null) {
  const fields = [{ name: 'Opened by', value: `<@${opener.id}> (${userTag(opener)})`, inline: true }, { name: 'Opened', value: ts(t.created, 'R'), inline: true }];
  if (claimedBy) fields.push({ name: 'Looked after by', value: `<@${claimedBy}>`, inline: true });
  const buttons = [];
  if (!claimedBy) buttons.push(button(`ticket:claim:${t.id}`, 'Claim', 1, { emoji: { name: '🙋' } }));
  buttons.push(button(`ticket:close:${t.id}`, 'Close', 4, { emoji: { name: '🔒' } }));
  return {
    embeds: [{ title: clip(`🎫 Ticket ${t.id}: ${t.subject}`, 256), description: clip(details || '(no details)', 3000), color: COLOR.ice, fields,
      footer: { text: 'Staff will answer here. Press Close when it\'s sorted.' } }],
    components: [row(...buttons)],
  };
}

async function closeTicket(x, t, reason) {
  const bot = x.bot;
  const cfg = bot.config();
  // the transcript: up to 300 messages, oldest first
  let msgs = [];
  let before = '';
  for (let page = 0; page < 3; page++) {
    const batch = await bot.rest.get(`/channels/${t.thread}/messages?limit=100${before}`).catch(() => []);
    msgs = msgs.concat(batch);
    if (batch.length < 100) break;
    before = `&before=${batch[batch.length - 1].id}`;
  }
  msgs.reverse();
  const lines = msgs.map((m) => {
    const when = new Date(Date.parse(m.timestamp)).toISOString().replace('T', ' ').slice(0, 16);
    const files = (m.attachments || []).map((a) => ` [file: ${a.url}]`).join('');
    const embeds = (m.embeds || []).map((e) => ` [${[e.title, e.description].filter(Boolean).join(': ')}]`).join('');
    return `[${when} UTC] ${userTag(m.author)}: ${m.content || ''}${embeds}${files}`;
  });
  const text = `Ticket ${t.id}: ${t.subject}\nOpened by ${t.user} on ${new Date(t.created).toISOString()}\nClosed by ${userTag(x.user)}${reason ? `: ${reason}` : ''}\n\n${lines.join('\n')}\n`;
  await bot.rest.post(`/channels/${t.thread}/messages`, { embeds: [{ color: COLOR.grey, title: '🔒 Ticket closed',
    description: `Closed by <@${x.uid}>${reason ? `\n**Reason:** ${clip(reason, 500)}` : ''}` }], allowed_mentions: { parse: [] } }).catch(() => {});
  bot.sql.exec("UPDATE tickets SET status = 'closed', closed = ?, closed_by = ? WHERE id = ?", Date.now(), x.uid, t.id);
  await bot.rest.patch(`/channels/${t.thread}`, { archived: true, locked: true }, { reason: `Ticket closed by ${x.tag}` }).catch(() => {});
  if (cfg.modlog) {
    await bot.rest.req('POST', `/channels/${cfg.modlog}/messages`, {
      body: { embeds: [{ title: `🎫 Ticket ${t.id} closed`, color: COLOR.grey, timestamp: new Date().toISOString(),
        fields: [{ name: 'Subject', value: clip(t.subject, 200) }, { name: 'Opened by', value: `<@${t.user}>`, inline: true },
          { name: 'Closed by', value: `<@${x.uid}>`, inline: true }, { name: 'Messages', value: String(msgs.length), inline: true },
          ...(reason ? [{ name: 'Reason', value: clip(reason, 500) }] : []), { name: 'Thread', value: `<#${t.thread}>` }] }], allowed_mentions: { parse: [] } },
      files: [{ name: `ticket-${t.id}.txt`, data: text }],
    }).catch((e) => console.error('ticket transcript:', e.message));
  }
  if (x.uid !== t.user) {
    await bot.dm(t.user, { embeds: [{ color: COLOR.grey, title: `Your ticket was closed`, description: `**${clip(t.subject, 200)}**${reason ? `\n${clip(reason, 500)}` : ''}\n\nOpen a new one any time.` }] });
  }
  return `🔒 Closed ticket ${t.id}.`;
}

export const commands = {
  async ticket(x) {
    const { sub, opts } = options(x.data);
    const bot = x.bot;
    if (sub === 'panel') {
      x.need(PERM.MANAGE_GUILD, 'Manage Server');
      const channel = opts.channel || x.i.channel_id;
      const msg = await bot.rest.post(`/channels/${channel}/messages`, panelMessage());
      bot.setConfig({ tickets: channel, ticketPanel: { channel, message: msg.id } });
      return ephemeral(`Posted the ticket button in <#${channel}>. Tickets open as private threads there.`);
    }
    const t = ticketIn(bot, x.i.channel_id);
    if (!t) return ephemeral('Use this inside an open ticket.');
    if (!bot.isStaff(x.member)) return ephemeral('Only staff can do that.');
    if (sub === 'add' || sub === 'remove') {
      const id = opts.user;
      if (sub === 'add') await bot.rest.put(`/channels/${t.thread}/thread-members/${id}`);
      else await bot.rest.del(`/channels/${t.thread}/thread-members/${id}`);
      return { type: 4, data: { content: `${sub === 'add' ? 'Added' : 'Removed'} <@${id}> ${sub === 'add' ? 'to' : 'from'} this ticket.`, allowed_mentions: { parse: [] } } };
    }
    if (sub === 'rename') {
      await bot.rest.patch(`/channels/${t.thread}`, { name: clip(`ticket-${t.id}-${opts.name}`, 100) });
      return ephemeral('Renamed.');
    }
    if (sub === 'close') return x.defer(() => closeTicket(x, t, opts.reason));
    return ephemeral('Unknown ticket command.');
  },
};

export const components = {
  async ticket(x, [action, id]) {
    const bot = x.bot;
    if (action === 'open') {
      const open = bot.sql.exec("SELECT * FROM tickets WHERE user = ? AND status = 'open'", x.uid).toArray()[0];
      if (open && open.thread) return ephemeral(`You already have an open ticket: <#${open.thread}>`);
      const recent = bot.sql.exec('SELECT COUNT(*) AS n FROM tickets WHERE user = ? AND created > ?', x.uid, Date.now() - 3600000).one().n;
      if (recent >= MAX_OPEN_PER_HOUR) return ephemeral('You opened a few tickets in the last hour. Wait a bit, or keep talking in your last one.');
      return modal('ticketnew', 'Open a ticket', [['subject', 'What is it about?', 1, true, 80], ['details', 'Tell us more', 2, true, 1500]]);
    }
    const t = bot.sql.exec('SELECT * FROM tickets WHERE id = ?', Number(id)).toArray()[0];
    if (!t || t.status !== 'open') return ephemeral('This ticket is already closed.');
    if (action === 'claim') {
      if (!bot.isStaff(x.member)) return ephemeral('Only staff can claim tickets.');
      if (t.claimed_by) return ephemeral(`<@${t.claimed_by}> is already looking after this ticket.`);
      bot.sql.exec('UPDATE tickets SET claimed_by = ? WHERE id = ?', x.uid, t.id);
      const embed = { ...((x.i.message && x.i.message.embeds) || [{}])[0] };
      embed.fields = [...(embed.fields || []), { name: 'Looked after by', value: `<@${x.uid}>`, inline: true }];
      x.work = async () => {
        await bot.rest.post(`/channels/${t.thread}/messages`, { content: `🙋 <@${x.uid}> is looking after this ticket.`, allowed_mentions: { parse: [] } });
        return null;
      };
      return { type: 7, data: { embeds: [embed], components: [row(button(`ticket:close:${t.id}`, 'Close', 4, { emoji: { name: '🔒' } }))] } };
    }
    if (action === 'close') {
      if (x.uid !== t.user && !bot.isStaff(x.member)) return ephemeral('Only the person who opened it or staff can close a ticket.');
      return modal(`ticketclose:${t.id}`, 'Close the ticket', [['reason', 'Why? (optional)', 1, false, 300]]);
    }
    return ephemeral('Unknown ticket button.');
  },
};

export const modals = {
  async ticketnew(x) {
    const bot = x.bot;
    const { subject, details } = modalValues(x.data);
    const open = bot.sql.exec("SELECT * FROM tickets WHERE user = ? AND status = 'open'", x.uid).toArray()[0];
    if (open && open.thread) return ephemeral(`You already have an open ticket: <#${open.thread}>`);
    const parent = bot.config().tickets || x.i.channel_id;
    const t = bot.sql.exec("INSERT INTO tickets (user, subject, status, created) VALUES (?, ?, 'opening', ?) RETURNING *", x.uid, clip(subject, 80), Date.now()).one();
    return x.defer(async () => {
      try {
        const name = clip(`ticket-${t.id}-${x.user.username || 'member'}`.toLowerCase().replace(/[^a-z0-9-]+/g, '-'), 100);
        const thread = await bot.rest.post(`/channels/${parent}/threads`, { name, type: 12, invitable: false, auto_archive_duration: 10080 }, { reason: `Ticket ${t.id} for ${x.tag}` });
        await bot.rest.put(`/channels/${thread.id}/thread-members/${x.uid}`);
        bot.sql.exec("UPDATE tickets SET thread = ?, status = 'open' WHERE id = ?", thread.id, t.id);
        const staff = (bot.config().staff || []).slice(0, 5);
        await bot.rest.post(`/channels/${thread.id}/messages`, { content: `<@${x.uid}> ${staff.map((r) => `<@&${r}>`).join(' ')}`.trim(),
          allowed_mentions: { users: [x.uid], roles: staff }, ...ticketMessage(t, x.user, details) });
        await bot.log({ title: `🎫 Ticket ${t.id} opened`, color: COLOR.ice, fields: [{ name: 'By', value: `<@${x.uid}> (${x.tag})`, inline: true },
          { name: 'Thread', value: `<#${thread.id}>`, inline: true }, { name: 'Subject', value: clip(subject, 200) }] });
        return `🎫 Your ticket is open: <#${thread.id}>`;
      } catch (e) {
        bot.sql.exec('DELETE FROM tickets WHERE id = ?', t.id);
        throw e;
      }
    });
  },

  async ticketclose(x, [id]) {
    const t = x.bot.sql.exec("SELECT * FROM tickets WHERE id = ? AND status = 'open'", Number(id)).toArray()[0];
    if (!t) return ephemeral('This ticket is already closed.');
    if (x.uid !== t.user && !x.bot.isStaff(x.member)) return ephemeral('Only the person who opened it or staff can close a ticket.');
    const { reason } = modalValues(x.data);
    return x.defer(() => closeTicket(x, t, reason));
  },
};
