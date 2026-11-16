'use strict';
// The launcher window can be closed while the game keeps running (the launcher stays open for the game). Opening
// Polar Client again, or the game closing, must not touch the closed window: Electron throws "Object has been
// destroyed" for any call on it, which players saw as "A JavaScript error occurred in the main process".
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Module = require('node:module');

function loadMain() {
  const appData = fs.mkdtempSync(path.join(os.tmpdir(), 'polar-window-'));
  const appEvents = {};
  const windows = [];
  let gameEvents = null;
  let quits = 0;
  const noop = () => {};

  class FakeWindow {
    constructor(options) {
      this.options = options;
      this.destroyed = false;
      this.minimized = false;
      this.visible = false;
      this.calls = [];
      this.handlers = {};
      const self = this;
      this.webContents = {
        send: (...args) => { self.alive(); self.calls.push(['send', ...args]); },
        setWindowOpenHandler: noop,
        on: noop,
      };
      windows.push(this);
    }
    // like Electron: every call on a destroyed window throws
    alive() { if (this.destroyed) throw new TypeError('Object has been destroyed'); }
    on(event, fn) { this.alive(); (this.handlers[event] = this.handlers[event] || []).push(fn); return this; }
    once(event, fn) { return this.on(event, fn); }
    loadFile() { this.alive(); }
    isDestroyed() { return this.destroyed; }
    isMinimized() { this.alive(); return this.minimized; }
    isMaximized() { this.alive(); return false; }
    restore() { this.alive(); this.minimized = false; this.calls.push('restore'); }
    show() { this.alive(); this.visible = true; this.calls.push('show'); }
    hide() { this.alive(); this.visible = false; }
    focus() { this.alive(); this.calls.push('focus'); }
    minimize() { this.alive(); this.minimized = true; }
    close() { this.destroy(); }
    destroy(closedEvent = true) {
      this.destroyed = true;
      if (closedEvent) for (const fn of this.handlers.closed || []) fn();
    }
    static getAllWindows() { return windows.filter((w) => !w.destroyed); }
  }

  const ipcOn = {};
  const electron = {
    app: {
      requestSingleInstanceLock: () => true, quit: () => { quits++; }, getPath: () => appData, isPackaged: false, isReady: () => true,
      getVersion: () => '1.0.0', whenReady: () => new Promise(noop), on: (event, fn) => { appEvents[event] = fn; },
    },
    BrowserWindow: FakeWindow,
    ipcMain: { handle: noop, on: (channel, fn) => { ipcOn[channel] = fn; } },
    shell: { openExternal: noop, openPath: noop },
    dialog: {},
    safeStorage: { isEncryptionAvailable: () => false, encryptString: (s) => Buffer.from(s), decryptString: (b) => b.toString() },
    protocol: { registerSchemesAsPrivileged: noop, handle: noop },
  };
  const fakeGame = {
    GameManager: class {
      constructor(options) { gameEvents = options.onEvent; this.running = false; }
    },
  };
  const fakeDiscord = { DiscordPresence: class { set() {} stop() {} }, activityFor: () => ({}) };
  const load = Module._load;
  Module._load = function (request, ...rest) {
    if (request === 'electron') return electron;
    if (request === './core/game') return fakeGame;
    if (request === './core/discord') return fakeDiscord;
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
  return {
    windows,
    emit: (event, ...args) => appEvents[event](...args),
    ipc: (channel) => ipcOn[channel]({}),
    gameEvent: (type, data) => gameEvents(type, data),
    quits: () => quits,
  };
}

test('opening Polar Client again brings the window back, even after the window was closed', () => {
  const m = loadMain();
  m.emit('second-instance');
  assert.equal(m.windows.length, 1, 'opens the window');

  const first = m.windows[0];
  first.minimized = true;
  m.emit('second-instance');
  assert.deepEqual(first.calls.filter((c) => typeof c === 'string'), ['restore', 'show', 'focus'], 'restores the open window');

  first.close();
  assert.doesNotThrow(() => m.emit('second-instance'), 'the closed window is not touched');
  assert.equal(m.windows.length, 2, 'a new window opens');
  assert.equal(m.windows[1].destroyed, false);
});

test('a window that was destroyed without its closed event is not touched either', () => {
  const m = loadMain();
  m.emit('second-instance');
  m.windows[0].destroy(false);
  assert.doesNotThrow(() => m.emit('second-instance'));
  assert.equal(m.windows.length, 2);
  assert.doesNotThrow(() => m.ipc('win:minimize'));
});

test('the game closing after the window was closed quits the launcher instead of throwing', () => {
  const m = loadMain();
  m.emit('second-instance');
  m.windows[0].close();
  assert.doesNotThrow(() => m.gameEvent('log', 'a line'));
  assert.doesNotThrow(() => m.gameEvent('exit', { code: 0 }));
  assert.equal(m.quits(), 1, 'nothing is left running without a window');
});

test('window buttons do nothing once the window is gone', () => {
  const m = loadMain();
  m.emit('second-instance');
  m.windows[0].close();
  for (const channel of ['win:minimize', 'win:maximize', 'win:close']) assert.doesNotThrow(() => m.ipc(channel), channel);
});
