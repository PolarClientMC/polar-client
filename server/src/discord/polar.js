// Polar Client commands (download, FAQ, server status, cosmetics, redeem codes), the bot's settings, /help, and the
// update posts: new entries in the launcher's news feed are posted in the updates channel.
import { COLOR, PERM, ephemeral, say, options, clip, row, parseDuration, humanDuration, modal, modalValues } from './util.js';
import { FAQ, COMMANDS } from './commands.js';
import { ITEMS, TYPE_NAMES, searchItems, itemName } from './giveaways.js';

export const DEFAULT_NEWS_URL = 'https://raw.githubusercontent.com/PolarClientMC/polar-client/main/news.json';

const site = (bot) => bot.config().site || bot.env.PUBLIC_URL || '';

async function news(bot) {
  return bot.cached('news', async () => {
    const res = await fetch(bot.env.NEWS_URL || DEFAULT_NEWS_URL, { headers: { 'User-Agent': 'PolarBot' }, cf: { cacheTtl: 120 } });
    if (!res.ok) throw new Error(`news ${res.status}`);
    const data = await res.json();
    return Array.isArray(data) ? data : data.items || [];
  });
}

/** Posts news-feed entries the bot hasn't posted yet (quiet: only remember them, the first time). */
export async function postNews(bot, { quiet = false } = {}) {
  const cfg = bot.config();
  bot.setConfig({ newsCheckedAt: Date.now() });
  let items;
  try {
    bot.forget('news');
    items = await news(bot);
  } catch (e) {
    console.error('news:', e.message);
    return 0;
  }
  const seen = new Set(bot.sql.exec('SELECT id FROM news').toArray().map((r) => r.id));
  const fresh = items.filter((n) => n && n.id && !seen.has(String(n.id))).sort((a, b) => String(a.date || '').localeCompare(String(b.date || '')));
  let posted = 0;
  for (const n of fresh) {
    if (!quiet && cfg.updates) {
      try {
        const msg = await bot.rest.post(`/channels/${cfg.updates}/messages`, {
          content: cfg.updatePing ? `<@&${cfg.updatePing}>` : '',
          allowed_mentions: { roles: cfg.updatePing ? [cfg.updatePing] : [] },
          embeds: [{ title: clip(`${n.tag === 'Update' ? '🆕 ' : '📰 '}${n.title || 'News'}`, 256), description: clip(n.body || '', 4000), color: COLOR.ice,
            ...(site(bot) ? { url: site(bot) } : {}), ...(n.date ? { footer: { text: n.date } } : {}) }],
          components: site(bot) ? [row({ type: 2, style: 5, label: 'Get Polar Client', url: site(bot) })] : [],
        });
        posted++;
        // announcement channels: publish it so servers that follow the channel get it too
        await bot.rest.post(`/channels/${cfg.updates}/messages/${msg.id}/crosspost`).catch(() => {});
      } catch (e) {
        console.error('news post:', e.message);
        break; // try the rest next time
      }
    }
    bot.sql.exec('INSERT OR IGNORE INTO news (id, posted) VALUES (?, ?)', String(n.id), Date.now());
  }
  return posted;
}

function itemEmbed(bot, item) {
  return {
    title: item.name, color: item.special ? COLOR.purple : COLOR.ice,
    description: `${TYPE_NAMES[item.type] || item.type}${item.special ? ' · **code-only**: given away in events and giveaways' : ' · free in the launcher wardrobe'}`,
    ...(site(bot) ? { thumbnail: { url: `${site(bot)}/cosmetics/thumbs/${item.id}.png` } } : {}),
    footer: { text: 'Wear it from Cosmetics in the Polar Client launcher' },
  };
}

const HELP = {
  everyone: ['download', 'faq', 'status', 'online', 'cosmetic', 'report', 'help'],
};

export const commands = {
  download(x) {
    const url = site(x.bot);
    return say('', { embeds: [{ title: '⬇️ Download Polar Client', color: COLOR.ice, url: url || undefined,
      description: 'Free, for Windows, macOS and Linux.\n\n**1.** Download and run the installer.\n**2.** Sign in with your Microsoft account.\n**3.** Pick a version or profile next to Play, and press **Play**.' }],
    components: url ? [row({ type: 2, style: 5, label: 'Get Polar Client', url })] : [] });
  },

  faq(x) {
    const [q, a] = FAQ[options(x.data).opts.question] || ['?', 'I don\'t know that one.'];
    return say('', { embeds: [{ title: q, description: a, color: COLOR.blue }] });
  },

  async status(x) {
    const bot = x.bot;
    const fields = [];
    let up = false;
    try {
      const h = await bot.hub('/v1/health', { method: 'GET' });
      up = Boolean(h.ok);
      fields.push({ name: 'Cosmetics', value: String(h.items), inline: true });
      const o = await bot.hub('/v1/online', { method: 'GET' });
      fields.push({ name: 'On Polar now', value: String(o.online), inline: true });
    } catch {
      // shown as down
    }
    try {
      const latest = (await news(bot)).find((n) => n.tag === 'Update') || null;
      if (latest) fields.push({ name: 'Latest update', value: `${latest.title}${latest.date ? ` (${latest.date})` : ''}` });
    } catch {
      // no news
    }
    return say('', { embeds: [{ title: up ? '🟢 Polar is online' : '🔴 The Polar server isn\'t answering', color: up ? COLOR.green : COLOR.red, fields,
      footer: { text: up ? 'Cosmetics, friends and settings sync are working.' : 'The game still works; cosmetics and friends come back when the server does.' } }] });
  },

  async online(x) {
    try {
      const o = await x.bot.hub('/v1/online', { method: 'GET' });
      return say(`🟢 **${o.online}** ${o.online === 1 ? 'person is' : 'people are'} on Polar Client right now.`);
    } catch {
      return say("I can't reach the Polar server right now.");
    }
  },

  cosmetic(x) {
    const item = ITEMS.get(options(x.data).opts.name);
    if (!item) return ephemeral("I don't know that cosmetic. Pick one from the list.");
    return say('', { embeds: [itemEmbed(x.bot, item)] });
  },

  // /announce: remembers where and whom to ping, then asks for the title and text in a form
  announce(x) {
    x.need(PERM.MANAGE_MESSAGES, 'Manage Messages');
    const { channel, ping, image } = options(x.data).opts;
    const file = image ? ((x.data.resolved || {}).attachments || {})[image] : null;
    if (file && !/^image\//.test(file.content_type || '')) return ephemeral('The picture has to be an image file (PNG, JPG, GIF or WebP).');
    if (file && file.size > MAX_IMAGE) return ephemeral('That picture is over 8 MB. Use a smaller one.');
    if (ping === x.i.guild_id && !x.can(PERM.MENTION_EVERYONE)) return ephemeral('You need the **Mention @everyone** permission to ping everybody.');
    x.bot.setConfig({ [`announce:${x.uid}`]: { channel: channel || x.bot.config().announcements || x.i.channel_id, ping: ping || null,
      image: file ? { url: file.url, name: file.filename, type: file.content_type } : null, at: Date.now() } });
    return modal('announce', 'Announcement', [['title', 'Title', 1, true, 250], ['text', 'Text (you can use **bold** and links)', 2, true, 4000]]);
  },

  async code(x) {
    x.need(PERM.MANAGE_GUILD, 'Manage Server');
    const { cosmetic, count = 1, uses = 1, note } = options(x.data).opts;
    if (!ITEMS.has(cosmetic)) return ephemeral("I don't know that cosmetic. Pick one from the list.");
    return x.defer(async () => {
      const res = await x.bot.hub('/v1/admin/codes', { method: 'POST', body: JSON.stringify({ item: cosmetic, count, uses, note: note || `Discord: ${x.tag}` }) });
      await x.bot.log({ title: '🎟️ Codes made', color: COLOR.purple, fields: [{ name: 'Cosmetic', value: itemName(cosmetic), inline: true },
        { name: 'Codes', value: `${res.made.length} × ${uses} use${uses > 1 ? 's' : ''}`, inline: true }, { name: 'By', value: `<@${x.uid}>`, inline: true }] });
      return `🎟️ ${res.made.length} code${res.made.length > 1 ? 's' : ''} for **${itemName(cosmetic)}** (${uses} use${uses > 1 ? 's' : ''} each). Only you can see this:\n\`\`\`\n${res.made.join('\n')}\n\`\`\`Players redeem them in the launcher: Cosmetics > Redeem.`;
    });
  },

  config(x) {
    x.need(PERM.MANAGE_GUILD, 'Manage Server');
    const bot = x.bot;
    const { sub, opts } = options(x.data);
    if (sub === 'channel') {
      bot.setConfig({ [opts.setting]: opts.channel });
      return ephemeral(`Done: ${opts.setting} → <#${opts.channel}>.`);
    }
    if (sub === 'staff') {
      const staff = new Set(bot.config().staff || []);
      if (opts.remove) staff.delete(opts.role);
      else staff.add(opts.role);
      bot.setConfig({ staff: [...staff] });
      return ephemeral(`Staff roles: ${[...staff].map((r) => `<@&${r}>`).join(', ') || 'none'}.`);
    }
    if (sub === 'updateping') {
      bot.setConfig({ updatePing: opts.role || null });
      return ephemeral(opts.role ? `Update posts ping <@&${opts.role}>.` : 'Update posts ping nobody.');
    }
    if (sub === 'escalation') {
      const cur = bot.config().escalation || {};
      const ms = opts.duration ? parseDuration(opts.duration) : cur.ms || 3600000;
      if (!ms || ms < 60000 || ms > 28 * 86400000) return ephemeral('Give a length from `1m` to `28d`, like `1h`.');
      bot.setConfig({ escalation: { enabled: opts.enabled, at: opts.at || cur.at || 3, ms } });
      const e = bot.config().escalation;
      return ephemeral(e.enabled ? `Every ${e.at} warnings, members are timed out: first for ${humanDuration(e.ms)}, then twice as long each time.` : 'Automatic timeouts are off.');
    }
    const c = bot.config();
    const ch = (id) => (id ? `<#${id}>` : 'not set');
    const e = c.escalation || {};
    return ephemeral('', { embeds: [{ title: 'Polar Bot settings', color: COLOR.blue, fields: [
      { name: 'Mod log', value: ch(c.modlog), inline: true }, { name: 'Reports', value: c.reports ? ch(c.reports) : 'the mod log', inline: true },
      { name: 'Tickets', value: ch(c.tickets), inline: true }, { name: 'Update posts', value: ch(c.updates), inline: true },
      { name: 'Giveaways', value: ch(c.giveaways), inline: true }, { name: 'Update ping', value: c.updatePing ? `<@&${c.updatePing}>` : 'nobody', inline: true },
      { name: 'Staff roles', value: (c.staff || []).map((r) => `<@&${r}>`).join(' ') || 'none' },
      { name: 'Automatic timeouts', value: e.enabled === false ? 'off' : `every ${e.at || 3} warnings, from ${humanDuration(e.ms || 3600000)}` },
    ] }] });
  },

  help(x) {
    const mine = COMMANDS.filter((c) => c.type === 1 && (!c.default_member_permissions || x.can(BigInt(c.default_member_permissions))));
    const pub = mine.filter((c) => HELP.everyone.includes(c.name));
    const staff = mine.filter((c) => !HELP.everyone.includes(c.name));
    const list = (cs) => cs.map((c) => `\`/${c.name}\` ${c.description}`).join('\n');
    const fields = [{ name: 'For everyone', value: list(pub) }];
    chunk(staff, 10).forEach((cs, i) => fields.push({ name: i ? 'More for staff' : 'For staff (you can use these)', value: clip(list(cs), 1024) }));
    fields.push({ name: 'Also', value: 'Right-click a message > **Apps > Report message** to report it privately.' });
    return ephemeral('', { embeds: [{ title: '🐻‍❄️ Polar Bot', color: COLOR.ice, fields }] });
  },
};

function chunk(a, n) {
  const out = [];
  for (let i = 0; i < a.length; i += n) out.push(a.slice(i, i + n));
  return out;
}

const MAX_IMAGE = 8 * 1024 * 1024;

export const modals = {
  async announce(x) {
    x.need(PERM.MANAGE_MESSAGES, 'Manage Messages');
    const bot = x.bot;
    const key = `announce:${x.uid}`;
    const a = bot.config()[key];
    if (!a || Date.now() - a.at > 30 * 60000) return ephemeral('That form is too old. Run /announce again.');
    const { title, text } = modalValues(x.data);
    return x.defer(async () => {
      const embed = { title: clip(title, 256), description: clip(text, 4000), color: COLOR.ice, footer: { text: 'Polar Client' }, timestamp: new Date().toISOString() };
      const files = [];
      if (a.image) {
        const res = await fetch(a.image.url);
        if (res.ok) {
          const name = `image.${(/\.(png|jpe?g|gif|webp)$/i.exec(a.image.name || '') || [0, 'png'])[1].toLowerCase()}`;
          files.push({ name, data: await res.arrayBuffer(), type: a.image.type || 'image/png' });
          embed.image = { url: `attachment://${name}` };
        }
      }
      const everyone = a.ping === x.i.guild_id;
      const body = { content: a.ping ? (everyone ? '@everyone' : `<@&${a.ping}>`) : '', embeds: [embed],
        allowed_mentions: a.ping ? (everyone ? { parse: ['everyone'] } : { roles: [a.ping] }) : { parse: [] } };
      const msg = await bot.rest.req('POST', `/channels/${a.channel}/messages`, { body, files });
      bot.setConfig({ [key]: null });
      // announcement channels: publish it so servers that follow the channel get it too
      await bot.rest.post(`/channels/${a.channel}/messages/${msg.id}/crosspost`).catch(() => {});
      await bot.log({ title: '📢 Announcement posted', color: COLOR.ice, fields: [{ name: 'Channel', value: `<#${a.channel}>`, inline: true },
        { name: 'By', value: `<@${x.uid}>`, inline: true }, { name: 'Title', value: clip(title, 200) }] });
      return `📢 Posted in <#${a.channel}>.${a.image && !files.length ? " The picture couldn't be loaded, so it went without one." : ''}`;
    });
  },
};

export const autocomplete = {
  code: (x) => searchItems(options(x.data).opts.cosmetic, { specialFirst: true }),
  cosmetic: (x) => searchItems(options(x.data).opts.name),
};
