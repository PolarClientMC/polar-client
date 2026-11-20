'use strict';
// Partner servers: a small JSON file you publish (partners.json in the GitHub repo), shown on Home and on the
// Partners page. Fetched at start-up, kept for offline use, and the copy shipped inside the launcher is the fallback,
// so adding or removing a partner never needs a new launcher version.
//
// partners.json: { "partners": [ { "id", "name", "ip", "logo", "banner", "description", "tags", "discord" } ] }
// logo and banner are https://raw.githubusercontent.com/... links (the only image host the launcher allows besides itself).
const fsp = require('node:fs/promises');
const { request } = require('./http');

const MAX_PARTNERS = 24;
const str = (v, max) => (typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, max) : '');
const image = (v) => (typeof v === 'string' && /^https:\/\/raw\.githubusercontent\.com\/[^\s"'<>()]+$/.test(v.trim()) ? v.trim() : '');
const discord = (v) => (typeof v === 'string' && /^https:\/\/(discord\.gg|discord\.com\/invite)\/[A-Za-z0-9-]+\/?$/.test(v.trim()) ? v.trim() : '');
// a Minecraft server address: host[:port], letters, digits, dots and dashes
const address = (v) => (typeof v === 'string' && /^[A-Za-z0-9.-]{3,253}(:\d{1,5})?$/.test(v.trim()) ? v.trim().toLowerCase() : '');

/** Keeps only well-formed partners, in the file's order: { id, name, ip, logo, banner, description, tags, discord }. */
function clean(data) {
  const raw = data && Array.isArray(data.partners) ? data.partners : Array.isArray(data) ? data : [];
  const out = [];
  const seen = new Set();
  for (const p of raw) {
    if (!p || typeof p !== 'object' || p.hidden === true) continue;
    const name = str(p.name, 40);
    const ip = address(p.ip);
    if (!name || !ip) continue;
    const id = str(p.id, 40).toLowerCase().replace(/[^a-z0-9-]+/g, '-') || name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    if (seen.has(id)) continue;
    seen.add(id);
    out.push({
      id, name, ip,
      logo: image(p.logo),
      banner: image(p.banner),
      description: str(p.description, 220),
      tags: (Array.isArray(p.tags) ? p.tags : []).map((t) => str(t, 20)).filter(Boolean).slice(0, 5),
      discord: discord(p.discord),
    });
    if (out.length >= MAX_PARTNERS) break;
  }
  return out;
}

async function readPartners(file) {
  try {
    return clean(JSON.parse(await fsp.readFile(file, 'utf8')));
  } catch {
    return null;
  }
}

/** Live list when the URL answers, else the last copy saved, else the bundled file. Returns { partners, source }. */
async function load({ url, cacheFile, bundledFile }) {
  if (url) {
    try {
      const res = await request(url, { timeout: 8000, headers: { Accept: 'application/json' } });
      // an empty live list is real too (every partner removed), as long as the file itself is well-formed
      const data = JSON.parse(await res.text());
      if (data && Array.isArray(data.partners)) {
        const partners = clean(data);
        await fsp.writeFile(cacheFile, JSON.stringify({ partners }, null, 2)).catch(() => {});
        return { partners, source: 'live' };
      }
    } catch {
      // offline or not published yet
    }
  }
  const cached = await readPartners(cacheFile);
  if (cached) return { partners: cached, source: 'cache' };
  return { partners: (await readPartners(bundledFile)) || [], source: 'bundled' };
}

module.exports = { load, clean };
