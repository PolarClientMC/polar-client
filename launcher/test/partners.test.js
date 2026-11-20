'use strict';
// Partner servers: partners.json is cleaned (bad addresses, links and images dropped) and loaded live, from the saved
// copy, or from the copy shipped inside the launcher.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const partners = require('../src/core/partners');

const RAW = 'https://raw.githubusercontent.com/PolarClientMC/polar-client/main/partners';

test('partners keeps well-formed servers in order and drops what the launcher must not show', () => {
  const list = partners.clean({ partners: [
    { id: 'haze', name: 'HazeSMP', ip: 'Play.HazeSMP.net', logo: `${RAW}/haze/logo.png`, banner: 'https://evil.example/x.png',
      description: '  Economy   SMP  ', tags: ['SMP', '', 'Economy', 'a', 'b', 'c', 'd'], discord: 'https://discord.gg/abc123' },
    { name: 'No address' },
    { name: 'Bad address', ip: 'play.example.com; rm -rf /' },
    { id: 'haze', name: 'Duplicate', ip: 'dup.example.com' },
    { name: 'Hidden', ip: 'hidden.example.com', hidden: true },
    { name: 'Second Server', ip: 'mc.second.net:25566', discord: 'javascript:alert(1)' },
  ] });
  assert.deepEqual(list.map((p) => p.id), ['haze', 'second-server']);
  const [haze, second] = list;
  assert.equal(haze.ip, 'play.hazesmp.net');
  assert.equal(haze.logo, `${RAW}/haze/logo.png`);
  assert.equal(haze.banner, '', 'images only from raw.githubusercontent.com');
  assert.equal(haze.description, 'Economy SMP');
  assert.deepEqual(haze.tags, ['SMP', 'Economy', 'a', 'b', 'c']);
  assert.equal(haze.discord, 'https://discord.gg/abc123');
  assert.equal(second.ip, 'mc.second.net:25566');
  assert.equal(second.discord, '', 'only Discord invite links');
});

test('partners load live, then from the saved copy, then from the shipped copy', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'partners-'));
  const bundled = path.join(dir, 'bundled.json');
  const cache = path.join(dir, 'cache.json');
  fs.writeFileSync(bundled, JSON.stringify({ partners: [{ name: 'Shipped', ip: 'shipped.example.com' }] }));
  const realFetch = global.fetch;
  try {
    global.fetch = async () => new Response(JSON.stringify({ partners: [{ name: 'Live', ip: 'live.example.com' }] }), { status: 200 });
    let r = await partners.load({ url: 'https://example.com/partners.json', cacheFile: cache, bundledFile: bundled });
    assert.deepEqual([r.source, r.partners[0].name], ['live', 'Live']);
    global.fetch = async () => { throw new Error('offline'); };
    r = await partners.load({ url: 'https://example.com/partners.json', cacheFile: cache, bundledFile: bundled });
    assert.deepEqual([r.source, r.partners[0].name], ['cache', 'Live']);
    // every partner removed online: the launcher shows none (not the old shipped list)
    global.fetch = async () => new Response(JSON.stringify({ partners: [] }), { status: 200 });
    r = await partners.load({ url: 'https://example.com/partners.json', cacheFile: cache, bundledFile: bundled });
    assert.deepEqual([r.source, r.partners.length], ['live', 0]);
    fs.rmSync(cache);
    r = await partners.load({ url: '', cacheFile: cache, bundledFile: bundled });
    assert.deepEqual([r.source, r.partners[0].name], ['bundled', 'Shipped']);
  } finally {
    global.fetch = realFetch;
  }
  // the copy shipped with the launcher and the one published in the repo are valid and agree
  const shipped = partners.clean(JSON.parse(fs.readFileSync(path.join(__dirname, '../src/partners.json'), 'utf8')));
  const published = partners.clean(JSON.parse(fs.readFileSync(path.join(__dirname, '../../partners.json'), 'utf8')));
  assert.ok(shipped.length > 0);
  assert.deepEqual(shipped, published);
  for (const p of published) {
    for (const url of [p.logo, p.banner].filter(Boolean)) {
      const file = path.join(__dirname, '../..', url.slice(`${RAW.replace(/\/partners$/, '')}/`.length));
      assert.ok(fs.existsSync(file), `${url} is in the repo`);
    }
  }
});
