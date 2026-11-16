'use strict';
// Profiles: what Play starts. Every offered version has a built-in profile that uses that version's own game folder
// (so nothing moves for people who had the launcher before profiles), and you can make your own: a name, a colour,
// a Minecraft version, its own game folder (worlds, mods, packs, settings) or that version's folder, its own mods
// from the Mods page, and memory or Java arguments that differ from the launcher's.
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const catalog = require('./catalog');

const MAX_PROFILES = 40;
const MAX_NAME = 24;
/** Colours a profile can have (its tile in the picker). */
const COLORS = ['#3b82f6', '#22c1ee', '#14b8a6', '#22c55e', '#eab308', '#f97316', '#ef4444', '#ec4899', '#a855f7', '#64748b'];
/** Files a new own-folder profile copies from its version's folder, so your keys, servers and HUD come along. */
const CARRY_OVER = ['options.txt', 'servers.dat', 'config/polarclient.json', 'config/polarclient-waypoints.json'];

const builtInId = (version) => `v-${version}`;

function builtIn(v, meta = {}) {
  return { id: builtInId(v.id), name: `Minecraft ${v.id}`, version: v.id, builtIn: true, folder: 'version', color: null, memoryGb: null,
    jvmArgs: null, optionalMods: null, created: 0, lastPlayed: meta.lastPlayed || 0 };
}

/** A safe, readable folder name: "PvP 1.8.9!" -> "pvp-1-8-9". */
function slug(name) {
  return String(name).toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 24) || 'profile';
}

function cleanName(raw) {
  const name = String(raw == null ? '' : raw).replace(/[\u0000-\u001f]/g, '').trim().replace(/\s+/g, ' ').slice(0, MAX_NAME);
  if (!name) throw new Error('Give the profile a name.');
  return name;
}

function cleanMemory(v) {
  if (v === null || v === undefined || v === '' || v === false) return null;
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) return null;
  return Math.max(1, Math.min(64, n));
}

class Profiles {
  /** settings(): the launcher's settings, for the version picked before profiles existed and the default mod list. */
  constructor(rootDir, { settings } = {}) {
    this.rootDir = rootDir;
    this.file = path.join(rootDir, 'profiles.json');
    this.settings = settings || (() => ({}));
  }

  read() {
    let data = null;
    try {
      data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch {
      // none yet: start on the version picked before profiles existed
    }
    if (!data || typeof data !== 'object') data = {};
    const custom = (Array.isArray(data.profiles) ? data.profiles : []).filter((p) => p && typeof p.id === 'string' && /^p-[a-z0-9-]+$/.test(p.id));
    for (const p of custom) {
      if (!catalog.VERSIONS.some((v) => v.id === p.version)) p.version = catalog.DEFAULT_VERSION;
      if (p.folder !== 'version') p.folder = 'own';
    }
    const meta = data.builtIn && typeof data.builtIn === 'object' ? data.builtIn : {};
    let selected = typeof data.selected === 'string' ? data.selected : builtInId(catalog.versionInfo(this.settings().gameVersion).id);
    return { selected, profiles: custom, builtIn: meta };
  }

  /** Written in one go (no await between reading and writing), so two changes at once can't undo each other. */
  async write(data) {
    fs.mkdirSync(this.rootDir, { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ selected: data.selected, profiles: data.profiles, builtIn: data.builtIn }, null, 2));
    fs.renameSync(tmp, this.file);
  }

  /** Every profile: yours first (most recently played first), then one per version. */
  all(data = this.read()) {
    const mine = [...data.profiles].sort((a, b) => (b.lastPlayed || 0) - (a.lastPlayed || 0) || (b.created || 0) - (a.created || 0));
    return [...mine, ...catalog.VERSIONS.map((v) => builtIn(v, data.builtIn[v.id]))];
  }

  get(id, data = this.read()) {
    return this.all(data).find((p) => p.id === id) || null;
  }

  /** The profile Play starts (falls back to the default version's profile if the saved one is gone). */
  active(data = this.read()) {
    return this.get(data.selected, data) || builtIn(catalog.versionInfo(catalog.DEFAULT_VERSION));
  }

  /** The game folder of a profile: its own one under profiles/, or its version's folder. */
  dirOf(p) {
    if (p.folder === 'own' && !p.builtIn) return path.join(this.rootDir, 'profiles', p.dirName || p.id);
    return catalog.gameDirFor(this.rootDir, p.version);
  }

  /** Creates the profile's folder. A new own folder starts with your options, servers and Polar HUD of that version. */
  async prepareDir(p) {
    if (p.folder !== 'own' || p.builtIn) return catalog.prepareGameDir(this.rootDir, p.version);
    const dir = this.dirOf(p);
    const fresh = !fs.existsSync(dir);
    await fsp.mkdir(dir, { recursive: true });
    if (fresh) {
      const from = catalog.gameDirFor(this.rootDir, p.version);
      const main = catalog.gameDirFor(this.rootDir, catalog.DEFAULT_VERSION);
      for (const rel of CARRY_OVER) {
        let src = path.join(from, rel);
        if (!fs.existsSync(src) && rel === 'servers.dat') src = path.join(main, rel); // servers.dat is the same in every version
        if (!fs.existsSync(src)) continue;
        await fsp.mkdir(path.dirname(path.join(dir, rel)), { recursive: true });
        await fsp.copyFile(src, path.join(dir, rel)).catch(() => {});
      }
    }
    return dir;
  }

  /** True when a profile's own folder has single-player worlds in it. */
  hasWorlds(p) {
    try {
      return fs.readdirSync(path.join(this.dirOf(p), 'saves'), { withFileTypes: true }).some((e) => e.isDirectory());
    } catch {
      return false;
    }
  }

  /** Mods-page mods of a profile: its own list, or the launcher's for the built-in profiles. */
  modsOf(p) {
    return Array.isArray(p.optionalMods) ? p.optionalMods : this.settings().optionalMods || [];
  }

  async select(id) {
    const data = this.read();
    if (!this.get(id, data)) throw new Error('That profile is gone.');
    data.selected = id;
    await this.write(data);
    return this.active(data);
  }

  /** Makes a profile, or (with fields.id) changes one of yours. Returns it. */
  async save(fields = {}) {
    const data = this.read();
    const editing = fields.id ? data.profiles.find((p) => p.id === fields.id) : null;
    if (fields.id && !editing) throw new Error(fields.id.startsWith('v-') ? "The version profiles can't be changed. Make your own profile instead." : 'That profile is gone.');
    const name = cleanName(fields.name !== undefined ? fields.name : editing && editing.name);
    const clash = data.profiles.find((p) => p.name.toLowerCase() === name.toLowerCase() && p !== editing);
    if (clash) throw new Error(`You already have a profile called ${clash.name}.`);
    const version = fields.version !== undefined ? String(fields.version) : editing ? editing.version : catalog.DEFAULT_VERSION;
    if (!catalog.VERSIONS.some((v) => v.id === version)) throw new Error(`Minecraft ${version} isn't offered by this launcher.`);
    const color = COLORS.includes(fields.color) ? fields.color : editing ? editing.color : COLORS[data.profiles.length % COLORS.length];
    const folder = fields.folder === 'version' || fields.folder === 'own' ? fields.folder : editing ? editing.folder : 'own';
    const memoryGb = fields.memoryGb !== undefined ? cleanMemory(fields.memoryGb) : editing ? editing.memoryGb : null;
    const jvmArgs = fields.jvmArgs !== undefined ? (String(fields.jvmArgs || '').trim().slice(0, 1000) || null) : editing ? editing.jvmArgs : null;
    if (editing) {
      if (editing.folder === 'own' && version !== editing.version && this.hasWorlds(editing)) {
        // a world saved by a newer version can break in an older one, so a folder with worlds only moves up
        const older = catalog.VERSIONS.findIndex((v) => v.id === version) > catalog.VERSIONS.findIndex((v) => v.id === editing.version);
        if (older) throw new Error(`${editing.name} has worlds from Minecraft ${editing.version}, which can break in ${version}. Make a new profile for ${version} instead.`);
      }
      Object.assign(editing, { name, version, color, folder, memoryGb, jvmArgs });
      if (fields.optionalMods !== undefined) editing.optionalMods = Array.isArray(fields.optionalMods) ? [...new Set(fields.optionalMods.map(String))] : null;
      await this.write(data);
      return { ...editing };
    }
    if (data.profiles.length >= MAX_PROFILES) throw new Error(`You can have up to ${MAX_PROFILES} profiles.`);
    const id = `p-${crypto.randomBytes(4).toString('hex')}`;
    const p = {
      id, name, version, color, folder, memoryGb, jvmArgs,
      optionalMods: Array.isArray(fields.optionalMods) ? [...new Set(fields.optionalMods.map(String))] : [...this.modsOf(this.active(data))],
      dirName: `${slug(name)}-${id.slice(2)}`,
      created: Date.now(), lastPlayed: 0,
    };
    data.profiles.push(p);
    if (fields.select !== false) data.selected = id;
    await this.write(data);
    return { ...p };
  }

  /** Copies a profile's settings into a new one of yours (a new own folder, or the same version folder). */
  async duplicate(id) {
    const data = this.read();
    const src = this.get(id, data);
    if (!src) throw new Error('That profile is gone.');
    let name = src.builtIn ? `My ${src.version}` : `${src.name} copy`.slice(0, MAX_NAME);
    for (let i = 2; data.profiles.some((p) => p.name.toLowerCase() === name.toLowerCase()); i++) name = `${(src.builtIn ? `My ${src.version}` : src.name).slice(0, MAX_NAME - 4)} ${i}`;
    return this.save({ name, version: src.version, color: src.color || undefined, folder: src.builtIn ? 'own' : src.folder,
      memoryGb: src.memoryGb, jvmArgs: src.jvmArgs, optionalMods: [...this.modsOf(src)] });
  }

  /** Removes one of your profiles. withFolder also deletes its own game folder (worlds included). */
  async remove(id, { withFolder = false } = {}) {
    const data = this.read();
    const p = data.profiles.find((x) => x.id === id);
    if (!p) throw new Error(id.startsWith('v-') ? "The version profiles can't be removed." : 'That profile is gone.');
    let folder = null;
    if (p.folder === 'own') {
      folder = this.dirOf(p);
      const base = path.join(this.rootDir, 'profiles') + path.sep;
      if (withFolder && folder.startsWith(base) && fs.existsSync(folder)) {
        await fsp.rm(folder, { recursive: true, force: true });
        folder = null;
      }
    }
    const now = this.read(); // read again: deleting a big folder takes a while
    now.profiles = now.profiles.filter((x) => x.id !== id);
    if (now.selected === id) now.selected = builtInId(p.version);
    await this.write(now);
    return { removed: p.name, keptFolder: folder && fs.existsSync(folder) ? folder : null, selected: this.active(now) };
  }

  async setMods(id, list) {
    const data = this.read();
    const p = data.profiles.find((x) => x.id === id);
    if (!p) return false;
    p.optionalMods = [...new Set((list || []).map(String))];
    await this.write(data);
    return true;
  }

  async played(id) {
    const data = this.read();
    const p = data.profiles.find((x) => x.id === id);
    if (p) p.lastPlayed = Date.now();
    else {
      const b = this.get(id, data);
      if (b) data.builtIn[b.version] = { ...(data.builtIn[b.version] || {}), lastPlayed: Date.now() };
    }
    await this.write(data);
  }
}

module.exports = { Profiles, COLORS, MAX_PROFILES, MAX_NAME, CARRY_OVER, builtInId, slug };
