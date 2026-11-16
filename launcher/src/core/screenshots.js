'use strict';
// Lists the screenshots of every version's game folder and every profile's own folder (F2 in game), newest first.
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const catalog = require('./catalog');

function dirFor(rootDir, versionId) {
  return path.join(catalog.gameDirFor(rootDir, versionId), 'screenshots');
}

/** The folders to look in: every version's, plus your profiles' own folders ([{ key, dir, version, profile }]). */
function folders(rootDir, extra = []) {
  const out = catalog.VERSIONS.map((v) => ({ key: v.id, dir: dirFor(rootDir, v.id), version: v.id, profile: null }));
  for (const f of extra) if (f && /^p-[a-z0-9-]+$/.test(f.key)) out.push({ ...f, dir: path.join(f.dir, 'screenshots') });
  return out;
}

async function list(rootDir, extra = []) {
  const out = [];
  for (const f of folders(rootDir, extra)) {
    let names = [];
    try {
      names = await fsp.readdir(f.dir);
    } catch {
      continue;
    }
    for (const name of names) {
      if (!/\.(png|jpe?g)$/i.test(name)) continue;
      try {
        const st = await fsp.stat(path.join(f.dir, name));
        if (st.isFile()) out.push({ id: `${f.key}/${name}`, version: f.version, profile: f.profile, name, size: st.size, mtime: st.mtimeMs });
      } catch {
        // deleted meanwhile
      }
    }
  }
  return out.sort((a, b) => b.mtime - a.mtime);
}

/** Absolute path for an id from {@link list}; null if it isn't a screenshot in a game folder (no ../ tricks). */
function resolve(rootDir, id, extra = []) {
  if (typeof id !== 'string') return null;
  const slash = id.indexOf('/');
  if (slash < 0) return null;
  const key = id.slice(0, slash), name = id.slice(slash + 1);
  const f = folders(rootDir, extra).find((x) => x.key === key);
  if (!f) return null;
  if (path.basename(name) !== name || !/\.(png|jpe?g)$/i.test(name)) return null;
  const file = path.join(f.dir, name);
  return fs.existsSync(file) ? file : null;
}

module.exports = { list, resolve, dirFor };
