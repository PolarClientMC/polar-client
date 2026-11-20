// Partner servers: a featured strip on Home and big cards on the Partners page. The list comes from partners.json
// (polar.partners(): live, the saved copy or the one shipped with the launcher); player counts are pinged live, like the
// Servers page. Join starts the game straight into the partner's server (app.js's launch()).
// Each partner: { id, name, ip, logo, banner, description, tags, discord }.
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const FAV_KEY = 'polar.partnerFavs';
  const favs = new Set((() => { try { return JSON.parse(localStorage.getItem(FAV_KEY) || '[]'); } catch (e) { return []; } })());
  const saveFavs = () => { try { localStorage.setItem(FAV_KEY, JSON.stringify([...favs])); } catch (e) { /* ignore */ } };

  const el = (tag, attrs = {}, ...kids) => {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') n.className = v;
      else if (k === 'style') n.setAttribute('style', v);
      else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) n.setAttribute(k, v === true ? '' : v);
    }
    for (const c of kids.flat()) if (c != null) n.append(c.nodeType ? c : document.createTextNode(String(c)));
    return n;
  };
  const icon = (id) => {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    u.setAttribute('href', `#${id}`);
    s.append(u);
    return s;
  };
  const join = (p) => { if (typeof launch === 'function') launch(p.ip, p.name); };
  const copyIp = (p, btn) => navigator.clipboard.writeText(p.ip).then(() => {
    if (typeof toast === 'function') toast(`Copied ${p.ip}`);
    if (btn) { btn.classList.add('done'); setTimeout(() => btn.classList.remove('done'), 1200); }
  }).catch(() => {});
  const openDiscord = (p) => { try { polar.openLink(p.discord); } catch (e) { /* bridge missing */ } };
  // live player counts: ip -> { at, online, max, up }
  const pings = new Map();
  const PING_EVERY = 60000;
  const online = (p) => {
    const r = pings.get(p.ip);
    if (!r) return el('span', { class: 'p-online off' }, el('i'), 'Checking…');
    if (!r.up) return el('span', { class: 'p-online off' }, el('i'), 'Offline');
    return el('span', { class: 'p-online', title: 'Players online' }, el('i'), `${r.online.toLocaleString()}/${r.max.toLocaleString()}`);
  };
  // a logo that fails to load (offline, or not uploaded yet) becomes the server's first letter
  const logo = (p, cls) => {
    const letter = el('span', { class: `${cls} p-letter` }, p.name.slice(0, 1).toUpperCase());
    if (!p.logo) return letter;
    const img = el('img', { class: cls, src: p.logo, alt: '' });
    img.addEventListener('error', () => img.replaceWith(letter), { once: true });
    return img;
  };
  const banner = (p, cls, ...kids) => el('div', { class: `${cls}${p.banner ? '' : ' no-banner'}`, style: p.banner ? `background-image:url('${p.banner}')` : false }, ...kids);

  /** Home: a wide strip per partner (banner fading into the glass), with Join. */
  function homeRow(p) {
    return el('article', { class: 'pmini' },
      banner(p, 'pmini-banner'),
      logo(p, 'pmini-logo'),
      el('div', { class: 'pmini-info' },
        el('span', { class: 'p-badge' }, icon('i-star'), 'PARTNER'),
        el('b', {}, p.name),
        el('span', { class: 'pmini-ip' }, p.ip)),
      p.description ? el('p', { class: 'pmini-desc' }, p.description) : el('span', { class: 'pmini-desc' }),
      online(p),
      el('button', { class: 'pmini-join', onclick: () => join(p) }, icon('i-play'), 'JOIN'));
  }

  /** Partners page: the big card. */
  function card(p) {
    const fav = el('button', { class: `pcard-fav${favs.has(p.id) ? ' on' : ''}`, title: 'Favourite', 'aria-pressed': String(favs.has(p.id)) }, icon('i-star'));
    fav.addEventListener('click', () => {
      if (favs.has(p.id)) favs.delete(p.id); else favs.add(p.id);
      saveFavs();
      fav.classList.toggle('on', favs.has(p.id));
      fav.setAttribute('aria-pressed', String(favs.has(p.id)));
      render(); // favourites go first, on Home too
    });
    const copy = el('button', { class: 'btn ghost', onclick: (e) => copyIp(p, e.currentTarget) }, icon('i-copy'), 'Copy IP');
    return el('article', { class: 'pcard' },
      banner(p, 'pcard-banner', fav),
      el('div', { class: 'pcard-head' },
        logo(p, 'pcard-logo'),
        el('div', { class: 'pcard-title' }, el('b', {}, p.name), el('span', {}, p.ip)),
        online(p)),
      p.description ? el('p', { class: 'pcard-desc' }, p.description) : null,
      el('div', { class: 'pcard-tags' }, (p.tags || []).map((t) => el('span', {}, t))),
      el('div', { class: 'pcard-actions' },
        el('button', { class: 'btn primary', onclick: () => join(p) }, icon('i-play'), 'Join'),
        copy,
        p.discord ? el('button', { class: 'btn ghost', onclick: () => openDiscord(p) }, icon('i-chat'), 'Discord') : null));
  }

  let list = [];
  const ordered = () => [...list].sort((a, b) => Number(favs.has(b.id)) - Number(favs.has(a.id)));

  function render() {
    const shown = ordered();
    const panel = $('partnersPanel');
    const mini = $('partnerMini');
    const cards = $('partnerCards');
    if (panel && mini) {
      mini.replaceChildren(...shown.slice(0, 3).map(homeRow));
      panel.hidden = !shown.length;
    }
    if (cards) {
      cards.replaceChildren(...shown.map(card),
        el('div', { class: 'partner-slot' }, el('span', { class: 'plus' }, icon('i-plus')), el('b', {}, 'Your server could be here'),
          el('span', {}, 'Partner servers show up here and on Home for every Polar player.')));
      document.body.classList.toggle('has-partners', shown.length > 0);
    }
  }

  async function pingOne(p) {
    const cur = pings.get(p.ip);
    if (cur && Date.now() - cur.at < PING_EVERY - 1000) return;
    let r;
    try {
      r = await polar.servers.ping(p.ip);
    } catch (e) {
      r = { online: false };
    }
    const players = (r && r.players) || {};
    pings.set(p.ip, { at: Date.now(), up: Boolean(r && r.online), online: Number(players.online) || 0, max: Number(players.max) || 0 });
    render();
  }
  const visible = () => ['page-home', 'page-partners'].some((id) => { const pg = $(id); return pg && pg.classList.contains('active'); });
  const pingAll = () => { if (!document.hidden && visible()) list.forEach(pingOne); };

  async function load() {
    try {
      const r = await polar.partners();
      list = (r && r.partners) || [];
    } catch (e) {
      list = [];
    }
    render();
    pingAll();
  }

  // ping again when Home or Partners is opened, and every minute while one of them is on screen
  document.addEventListener('click', (e) => { if (e.target.closest('.nav-item[data-page="home"], .nav-item[data-page="partners"], [data-goto="partners"]')) setTimeout(pingAll, 50); });
  setInterval(pingAll, PING_EVERY);
  document.addEventListener('visibilitychange', pingAll);
  window.polarPartners = { reload: load };
  load();
})();
