'use strict';
// Profiles: each one starts its own Minecraft version, with its own game folder, mods and memory if you want.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Module = require('node:module');
const { Profiles } = require('../src/core/profiles');
const catalog = require('../src/core/catalog');
const screenshots = require('../src/core/screenshots');

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'polar-profiles-'));

test('every version has a profile, and the version picked before profiles is the active one', () => {
  const root = tmp();
  const p = new Profiles(root, { settings: () => ({ gameVersion: '1.8.9', optionalMods: ['sodium'] }) });
  const all = p.all();
  assert.deepEqual(all.map((x) => x.version), catalog.VERSIONS.map((v) => v.id));
  assert.ok(all.every((x) => x.builtIn && x.folder === 'version'));
  assert.equal(p.active().id, 'v-1.8.9');
  assert.equal(p.dirOf(p.active()), catalog.gameDirFor(root, '1.8.9'), 'the version profiles keep the folders they always had');
  assert.deepEqual(p.modsOf(p.active()), ['sodium'], "the version profiles use the launcher's mod list");
});

test('a new profile gets its own folder with your options, servers and Polar HUD of that version', async () => {
  const root = tmp();
  const from = catalog.gameDirFor(root, '1.21.11');
  fs.mkdirSync(path.join(from, 'config'), { recursive: true });
  fs.writeFileSync(path.join(from, 'options.txt'), 'fov:0.5');
  fs.writeFileSync(path.join(from, 'servers.dat'), 'servers');
  fs.writeFileSync(path.join(from, 'config', 'polarclient.json'), '{"hud":1}');
  fs.mkdirSync(path.join(from, 'saves', 'World'), { recursive: true });
  const p = new Profiles(root, { settings: () => ({ optionalMods: ['sodium', 'iris'] }) });
  const made = await p.save({ name: '  Survival   SMP ', version: '1.21.11' });
  assert.equal(made.name, 'Survival SMP');
  assert.equal(made.folder, 'own');
  assert.deepEqual(made.optionalMods, ['sodium', 'iris'], 'starts with the mods that were on');
  assert.equal(p.active().id, made.id, 'a new profile is picked');
  const dir = await p.prepareDir(p.active());
  assert.ok(dir.startsWith(path.join(root, 'profiles') + path.sep));
  assert.equal(fs.readFileSync(path.join(dir, 'options.txt'), 'utf8'), 'fov:0.5');
  assert.equal(fs.readFileSync(path.join(dir, 'servers.dat'), 'utf8'), 'servers');
  assert.equal(fs.readFileSync(path.join(dir, 'config', 'polarclient.json'), 'utf8'), '{"hud":1}');
  assert.ok(!fs.existsSync(path.join(dir, 'saves')), 'worlds are not copied');
  // changing it later doesn't copy again over what you changed
  fs.writeFileSync(path.join(dir, 'options.txt'), 'fov:1');
  await p.prepareDir(p.active());
  assert.equal(fs.readFileSync(path.join(dir, 'options.txt'), 'utf8'), 'fov:1');
});

test('names are checked, mods are kept per profile, and a profile can share its version folder', async () => {
  const root = tmp();
  const p = new Profiles(root, { settings: () => ({ optionalMods: [] }) });
  await assert.rejects(p.save({ name: '   ' }), /name/);
  const pvp = await p.save({ name: 'PvP', version: '1.8.9', memoryGb: 3 });
  await assert.rejects(p.save({ name: 'pvp' }), /already have a profile called PvP/);
  await assert.rejects(p.save({ name: 'X', version: '1.2.3' }), /isn't offered/);
  await p.setMods(pvp.id, ['zoom', 'zoom', 'freelook']);
  assert.deepEqual(p.modsOf(p.get(pvp.id)), ['zoom', 'freelook']);
  assert.equal(p.get(pvp.id).memoryGb, 3);
  const shaders = await p.save({ name: 'Shaders', version: '26.3', folder: 'version', memoryGb: 200 });
  assert.equal(shaders.memoryGb, 64, 'memory is kept sensible');
  assert.equal(p.dirOf(p.get(shaders.id)), catalog.gameDirFor(root, '26.3'));
  const edited = await p.save({ id: pvp.id, name: 'PvP 1.8', memoryGb: null });
  assert.equal(edited.name, 'PvP 1.8');
  assert.equal(edited.memoryGb, null);
  assert.equal(edited.dirName, pvp.dirName, 'renaming keeps the folder');
  await assert.rejects(p.save({ id: 'v-26.3', name: 'Mine' }), /can't be changed/);
  // listed first, before the versions
  assert.deepEqual(p.all().slice(0, 2).map((x) => x.name).sort(), ['PvP 1.8', 'Shaders']);
});

test("a profile with worlds can't move to an older version, but can move up", async () => {
  const root = tmp();
  const p = new Profiles(root, { settings: () => ({}) });
  const smp = await p.save({ name: 'SMP', version: '1.21.10' });
  const dir = await p.prepareDir(p.get(smp.id));
  await p.save({ id: smp.id, version: '1.16.5' }); // no worlds yet: fine
  await p.save({ id: smp.id, version: '1.21.10' });
  fs.mkdirSync(path.join(dir, 'saves', 'My World'), { recursive: true });
  await assert.rejects(p.save({ id: smp.id, version: '1.8.9' }), /worlds from Minecraft 1.21.10/);
  assert.equal((await p.save({ id: smp.id, version: '1.21.11' })).version, '1.21.11');
});

test('removing a profile keeps its folder unless you ask, and never touches a version folder', async () => {
  const root = tmp();
  const p = new Profiles(root, { settings: () => ({}) });
  const a = await p.save({ name: 'A', version: '1.8.9' });
  const dirA = await p.prepareDir(p.get(a.id));
  fs.writeFileSync(path.join(dirA, 'note.txt'), 'x');
  const res = await p.remove(a.id);
  assert.equal(res.keptFolder, dirA);
  assert.ok(fs.existsSync(path.join(dirA, 'note.txt')));
  assert.equal(p.active().id, 'v-1.8.9', 'the version profile takes over');
  const b = await p.save({ name: 'B', version: '1.8.9' });
  const dirB = await p.prepareDir(p.get(b.id));
  await p.remove(b.id, { withFolder: true });
  assert.ok(!fs.existsSync(dirB));
  const c = await p.save({ name: 'C', version: '26.3', folder: 'version' });
  const shared = await p.prepareDir(p.get(c.id));
  fs.writeFileSync(path.join(shared, 'keep.txt'), 'x');
  await p.remove(c.id, { withFolder: true });
  assert.ok(fs.existsSync(path.join(shared, 'keep.txt')), "a version's folder is never deleted");
  await assert.rejects(p.remove('v-26.3'), /can't be removed/);
});

test('duplicating a version profile makes your own copy of it', async () => {
  const root = tmp();
  const p = new Profiles(root, { settings: () => ({ optionalMods: ['sodium'] }) });
  const one = await p.duplicate('v-1.21.11');
  const two = await p.duplicate('v-1.21.11');
  assert.equal(one.name, 'My 1.21.11');
  assert.equal(two.name, 'My 1.21.11 2');
  assert.equal(one.folder, 'own');
  assert.deepEqual(one.optionalMods, ['sodium']);
  const three = await p.duplicate(one.id);
  assert.equal(three.name, 'My 1.21.11 copy');
});

test("screenshots include your profiles' own folders", async () => {
  const root = tmp();
  const p = new Profiles(root, { settings: () => ({}) });
  const a = await p.save({ name: 'A', version: '1.8.9' });
  const dir = await p.prepareDir(p.get(a.id));
  fs.mkdirSync(path.join(dir, 'screenshots'));
  fs.writeFileSync(path.join(dir, 'screenshots', 'shot.png'), 'png');
  const extra = [{ key: a.id, dir, version: a.version, profile: a.name }];
  const list = await screenshots.list(root, extra);
  assert.equal(list.length, 1);
  assert.equal(list[0].profile, 'A');
  assert.equal(screenshots.resolve(root, list[0].id, extra), path.join(dir, 'screenshots', 'shot.png'));
  assert.equal(screenshots.resolve(root, `${a.id}/../../x.png`, extra), null);
});

// ---------------------------------------------------------------- through the launcher itself

function loadMain() {
  const appData = fs.mkdtempSync(path.join(os.tmpdir(), 'polar-main-'));
  const handlers = {};
  const launches = [];
  const noop = () => {};
  class FakeWindow {
    constructor() { this.webContents = { send: noop, setWindowOpenHandler: noop, on: noop }; }
    on() { return this; } once() { return this; } loadFile() {} isDestroyed() { return false; } isMinimized() { return false; }
    restore() {} show() {} hide() {} focus() {} static getAllWindows() { return []; }
  }
  const electron = {
    app: { requestSingleInstanceLock: () => true, quit: noop, getPath: () => appData, isPackaged: false, isReady: () => true, getVersion: () => '1.0.0', whenReady: () => new Promise(noop), on: noop },
    BrowserWindow: FakeWindow,
    ipcMain: { handle: (channel, fn) => { handlers[channel] = fn; }, on: noop },
    shell: { openExternal: noop, openPath: async () => '' },
    dialog: {},
    safeStorage: { isEncryptionAvailable: () => false, encryptString: (s) => Buffer.from(s), decryptString: (b) => b.toString() },
    protocol: { registerSchemesAsPrivileged: noop, handle: noop },
    nativeImage: {}, clipboard: {},
  };
  const fakeGame = {
    GameManager: class {
      constructor() { this.running = false; }
      async launch(settings, account) { launches.push({ settings, account }); return { pid: 1, version: settings.gameVersion }; }
      kill() {}
    },
  };
  const load = Module._load;
  Module._load = function (request, ...rest) {
    if (request === 'electron') return electron;
    if (request === './core/game') return fakeGame;
    if (request === './core/discord') return { DiscordPresence: class { set() {} stop() {} }, activityFor: () => ({}) };
    if (request === './core/bridge') return { startBridge: async () => ({ env: {}, close() {} }) };
    return load.call(this, request, ...rest);
  };
  const mainPath = require.resolve('../src/main.js');
  delete require.cache[mainPath];
  try {
    require(mainPath);
  } finally {
    Module._load = load;
    delete require.cache[mainPath];
  }
  const call = async (channel, ...args) => {
    const r = await handlers[channel]({}, ...args);
    if (!r.ok) throw new Error(r.error);
    return r.data;
  };
  return { call, launches, root: path.join(appData, '.polarclient') };
}

test('Play starts the picked profile: its version, folder, mods and memory', async () => {
  const m = loadMain();
  await m.call('accounts:addOffline', 'Tester');
  await m.call('settings:set', { memoryGb: 4, jvmArgs: '-Dbase=1', optionalMods: ['sodium'] });
  const made = await m.call('profiles:save', { name: 'PvP', version: '1.8.9', memoryGb: 3, jvmArgs: '-Dpvp=1' });
  assert.equal(made.profiles.selected, made.profile.id);
  assert.equal(made.versions.selected, '1.8.9', 'the version follows the profile');
  // the Mods page now changes this profile's mods, not the launcher's
  await m.call('settings:set', { optionalMods: ['zoom'] });
  assert.deepEqual((await m.call('settings:get')).optionalMods, ['zoom']);
  await m.call('game:launch');
  const { settings } = m.launches[0];
  assert.equal(settings.gameVersion, '1.8.9');
  assert.equal(settings.memoryGb, 3);
  assert.equal(settings.jvmArgs, '-Dpvp=1');
  assert.deepEqual(settings.optionalMods, ['zoom']);
  assert.ok(settings.gameDir.startsWith(path.join(m.root, 'profiles') + path.sep), 'its own folder');
  assert.ok(fs.existsSync(settings.gameDir));
});

test('switching back to a version uses its folder and the launcher mod list again', async () => {
  const m = loadMain();
  await m.call('accounts:addOffline', 'Tester');
  await m.call('settings:set', { optionalMods: ['sodium'] });
  await m.call('profiles:save', { name: 'PvP', version: '1.8.9' });
  await m.call('settings:set', { optionalMods: ['zoom'] });
  const back = await m.call('profiles:select', 'v-26.3');
  assert.equal(back.versions.selected, '26.3');
  assert.deepEqual((await m.call('settings:get')).optionalMods, ['sodium'], "the profile's mods didn't leak into the launcher's");
  await m.call('game:launch');
  const { settings } = m.launches[0];
  assert.equal(settings.gameVersion, '26.3');
  assert.equal(settings.gameDir, catalog.gameDirFor(m.root, '26.3'));
  assert.deepEqual(settings.optionalMods, ['sodium']);
  // the old version picker still works and picks the version's profile
  await m.call('versions:select', '1.16.5');
  assert.equal((await m.call('profiles:list')).selected, 'v-1.16.5');
});
