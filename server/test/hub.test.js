// The server against a fake Mojang: sign-in, cosmetics, the badge, special cosmetics, codes, admin and sync.
import test from 'node:test';
import assert from 'node:assert/strict';
import { makeCtx } from './shim.js';
import { Hub, useCatalog } from '../src/hub.js';
import { md5, offlineUuid, isOfflineUuid } from '../src/offline.js';
import worker from '../src/index.js';

const CATALOG = {
  version: 1,
  items: [
    { id: 'cape_aurora', name: 'Aurora Cape', type: 'cape' },
    { id: 'cape_founder', name: 'Founder Cape', type: 'cape', special: true },
    { id: 'wings_frost', name: 'Frost Wings', type: 'wings', tint: true },
    { id: 'halo', name: 'Halo', type: 'head' },
    { id: 'pack', name: 'Backpack', type: 'back' },
    { id: 'aura_snow', name: 'Snowfall', type: 'aura' },
  ],
};
useCatalog(CATALOG);

const ALICE = { id: '0f7b6a5c4d3e2f1a0b9c8d7e6f5a4b3c', name: 'Alice' };
const BOB = { id: '11112222333344445555666677778888', name: 'Bob_2' };
const dash = (h) => `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;

/** Mojang: players "join" a server id (what the launcher does); hasJoined answers for those; names resolve. */
function fakeMojang() {
  const joined = new Set();
  const accounts = [ALICE, BOB, { id: '99990000999900009999000099990000', name: 'Carol' }];
  const state = { blocked: false, tokens: { 'mc-token-alice-0123456789': ALICE, 'mc-token-bob-01234567890': BOB } };
  globalThis.fetch = async (url, opts = {}) => {
    const u = new URL(String(url));
    if (u.hostname === 'sessionserver.mojang.com' && state.blocked) return new Response('Forbidden', { status: 403 });
    if (u.hostname === 'api.minecraftservices.com' && u.pathname === '/minecraft/profile') {
      const auth = (opts.headers && (opts.headers.Authorization || opts.headers.authorization)) || '';
      const acc = state.tokens[auth.replace(/^Bearer /, '')];
      return acc ? new Response(JSON.stringify({ id: acc.id, name: acc.name, skins: [] }), { status: 200 }) : new Response('{}', { status: 401 });
    }
    if (u.hostname === 'sessionserver.mojang.com') {
      const key = `${u.searchParams.get('username')}:${u.searchParams.get('serverId')}`;
      const acc = accounts.find((a) => a.name === u.searchParams.get('username'));
      return joined.has(key) && acc ? new Response(JSON.stringify({ id: acc.id, name: acc.name }), { status: 200 }) : new Response(null, { status: 204 });
    }
    if (u.hostname === 'api.minecraftservices.com') {
      const name = decodeURIComponent(u.pathname.split('/').pop());
      const acc = accounts.find((a) => a.name.toLowerCase() === name.toLowerCase());
      return acc ? new Response(JSON.stringify({ id: acc.id, name: acc.name }), { status: 200 }) : new Response('{}', { status: 404 });
    }
    return new Response('nope', { status: 404 });
  };
  return { join: (name, serverId) => joined.add(`${name}:${serverId}`), block: (b = true) => { state.blocked = b; } };
}

function setup(env = {}) {
  const ctx = makeCtx();
  const hub = new Hub(ctx, { TOKEN_SECRET: 'test-secret-0123456789', ADMIN_TOKEN: 'admin-pass-123456', ...env });
  const call = async (method, path, { body, token, admin, headers = {} } = {}) => {
    const h = { ...headers };
    if (token) h.Authorization = `Bearer ${token}`;
    if (admin) h.Authorization = `Bearer ${admin}`;
    const res = await hub.fetch(new Request(`https://polar.test${path}`, { method, headers: h, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body) }));
    const text = await res.text();
    return { status: res.status, data: text ? JSON.parse(text) : null, res };
  };
  return { ctx, hub, call };
}

async function signIn(call, mojang, acc) {
  const ch = await call('POST', '/v1/auth/challenge');
  mojang.join(acc.name, ch.data.challenge);
  const v = await call('POST', '/v1/auth/verify', { body: { name: acc.name, challenge: ch.data.challenge } });
  assert.equal(v.status, 200, JSON.stringify(v.data));
  return v.data.token;
}

async function goOnline(hub, token) {
  const res = await hub.fetch(new Request(`https://polar.test/v1/live?token=${encodeURIComponent(token)}`, { headers: { Upgrade: 'websocket' } }));
  assert.equal(res.status, 101);
  return res;
}

test('sign-in: Mojang confirms the challenge once; tokens are checked', async () => {
  const mojang = fakeMojang();
  const { call } = setup();
  assert.equal((await call('GET', '/v1/health')).data.ok, true);
  const ch = await call('POST', '/v1/auth/challenge');
  assert.match(ch.data.challenge, /^[0-9a-f]{32}$/);
  // without joining, Mojang says no
  assert.equal((await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge } })).status, 401);
  // a "no" doesn't spend the challenge (Mojang can be a moment late), but a "yes" does
  mojang.join('Alice', ch.data.challenge);
  assert.equal((await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge } })).status, 200);
  assert.equal((await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge } })).status, 401);
  const token = await signIn(call, mojang, ALICE);
  const me = await call('GET', '/v1/me', { token });
  assert.equal(me.status, 200);
  assert.equal(me.data.uuid, dash(ALICE.id));
  assert.equal(me.data.name, 'Alice');
  assert.deepEqual(me.data.loadout, { slots: {}, hideBadge: false, activeTag: '' });
  // a tampered or missing token is refused
  const [b, s] = token.split('.');
  const forged = `${Buffer.from(JSON.stringify({ u: dash(BOB.id), n: 'Bob_2', e: Date.now() + 1e9 })).toString('base64url')}.${s}`;
  assert.equal((await call('GET', '/v1/me', { token: forged })).status, 401);
  assert.equal((await call('GET', '/v1/me', { token: `${b}.x${s}` })).status, 401);
  assert.equal((await call('GET', '/v1/me')).status, 401);
  assert.equal((await call('POST', '/v1/auth/verify', { body: { name: 'bad name!', challenge: 'x' } })).status, 400);
});

test('sign-in when Mojang blocks the server: the launcher\'s word is never trusted, the Minecraft token is checked', async () => {
  const mojang = fakeMojang();
  const { call } = setup();
  mojang.block();
  const ch = await call('POST', '/v1/auth/challenge');
  mojang.join('Alice', ch.data.challenge);
  // a launcher claiming to be Alice, with a made-up profile, gets nothing
  let r = await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge, profile: { id: ALICE.id, name: 'Alice' } } });
  assert.equal(r.status, 503);
  assert.equal(r.data.code, 'mojang-unreachable');
  // someone else's token can't sign in as Alice
  r = await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge, mcToken: 'mc-token-bob-01234567890' } });
  assert.equal(r.status, 401);
  // a made-up token is refused
  r = await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge, mcToken: 'not-a-real-token-at-all' } });
  assert.equal(r.status, 401);
  // Alice's own token works, once
  r = await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge, mcToken: 'mc-token-alice-0123456789' } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal(r.data.uuid, dash(ALICE.id));
  r = await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge, mcToken: 'mc-token-alice-0123456789' } });
  assert.equal(r.status, 401);
  // with Mojang answering again, a profile sent by the launcher is still ignored
  mojang.block(false);
  const ch2 = await call('POST', '/v1/auth/challenge');
  r = await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch2.data.challenge, profile: { id: ALICE.id, name: 'Alice' } } });
  assert.equal(r.status, 401);
});

test('a server without its secret says so instead of handing out tokens', async () => {
  const mojang = fakeMojang();
  const { call } = setup({ TOKEN_SECRET: '' });
  assert.equal((await call('GET', '/v1/health')).data.configured, false);
  const ch = await call('POST', '/v1/auth/challenge');
  mojang.join('Alice', ch.data.challenge);
  assert.equal((await call('POST', '/v1/auth/verify', { body: { name: 'Alice', challenge: ch.data.challenge } })).status, 503);
});

test('loadouts: free cosmetics, slots checked, colours only where allowed, special ones need a grant', async () => {
  const mojang = fakeMojang();
  const { call } = setup();
  const token = await signIn(call, mojang, ALICE);
  let r = await call('PUT', '/v1/me/loadout', { token, body: { slots: { cape: 'cape_aurora', wings: { id: 'wings_frost', color: '#5CF2D6' }, head: { id: 'halo', color: '#ff0000' } }, hideBadge: false } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.deepEqual(r.data.loadout.slots, { cape: { id: 'cape_aurora' }, wings: { id: 'wings_frost', color: '#5cf2d6' }, head: { id: 'halo' } });
  assert.equal((await call('PUT', '/v1/me/loadout', { token, body: { slots: { head: 'cape_aurora' } } })).status, 400, 'wrong slot');
  assert.equal((await call('PUT', '/v1/me/loadout', { token, body: { slots: { cape: 'nope' } } })).status, 400);
  r = await call('PUT', '/v1/me/loadout', { token, body: { slots: { cape: 'cape_founder' } } });
  assert.equal(r.status, 403);
  assert.match(r.data.error, /special/);
  assert.equal((await call('PUT', '/v1/me/loadout', { token, body: 'not json' })).status, 400);
});

test('the badge and cosmetics show only for players on Polar right now', async () => {
  const mojang = fakeMojang();
  const { hub, call, ctx } = setup();
  const alice = await signIn(call, mojang, ALICE);
  const bob = await signIn(call, mojang, BOB);
  await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: { cape: 'cape_aurora', aura: 'aura_snow' } } });
  await call('PUT', '/v1/me/loadout', { token: bob, body: { slots: { back: 'pack' }, hideBadge: true } });
  const ask = () => call('POST', '/v1/players', { token: bob, body: { uuids: [ALICE.id, dash(BOB.id), 'not-a-uuid', '00000000000000000000000000000000'] } });
  assert.deepEqual((await ask()).data.players, {}, 'nobody online yet');
  const live = await goOnline(hub, alice);
  await goOnline(hub, bob);
  let players = (await ask()).data.players;
  assert.deepEqual(players[dash(ALICE.id)], { badge: true, slots: { cape: { id: 'cape_aurora' }, aura: { id: 'aura_snow' } } });
  assert.deepEqual(players[dash(BOB.id)], { badge: false, slots: { back: { id: 'pack' } } }, 'Bob hid his badge');
  assert.equal(Object.keys(players).length, 2);
  // where they are, for the friends list later
  hub.webSocketMessage(ctx.sockets[0], JSON.stringify({ type: 'where', state: 'server', server: 'play.example.net' }));
  assert.deepEqual(ctx.sockets[0].deserializeAttachment().where, { state: 'server', server: 'play.example.net' });
  // Alice closes the game
  ctx.sockets.filter((s) => s.tags.includes(`u:${dash(ALICE.id)}`)).forEach((s) => {
    s.readyState = 3;
    hub.webSocketClose(s, 1000, '');
  });
  players = (await ask()).data.players;
  assert.equal(players[dash(ALICE.id)], undefined);
  assert.ok(live.webSocket, 'the client end of the socket comes back');
  // too many connections for one player: the oldest are closed
  for (let i = 0; i < 6; i++) await goOnline(hub, alice);
  assert.equal(ctx.getWebSockets(`u:${dash(ALICE.id)}`).length, 4);
  // a plain request isn't an upgrade
  assert.equal((await call('GET', `/v1/live?token=${alice}`)).status, 426);
});

test('the website can read how many players are on Polar right now, and nothing else', async () => {
  const mojang = fakeMojang();
  const { hub, call } = setup();
  let r = await call('GET', '/v1/online');
  assert.deepEqual(r.data, { online: 0 });
  assert.equal(r.res.headers.get('Access-Control-Allow-Origin'), '*');
  const alice = await signIn(call, mojang, ALICE);
  const bob = await signIn(call, mojang, BOB);
  await goOnline(hub, alice);
  await goOnline(hub, alice); // a second game open counts once
  await goOnline(hub, bob);
  r = await call('GET', '/v1/online');
  assert.deepEqual(r.data, { online: 2 });
  assert.equal((await call('POST', '/v1/online')).status, 401, 'only reading it is open');
});

test('admin: password, special cosmetics, codes, blocking', async () => {
  const mojang = fakeMojang();
  const { hub, call } = setup();
  const admin = 'admin-pass-123456';
  assert.equal((await call('GET', '/v1/admin/stats', { admin: 'wrong' })).status, 401);
  assert.equal((await call('GET', '/v1/admin/stats')).status, 401);
  const alice = await signIn(call, mojang, ALICE);
  let stats = (await call('GET', '/v1/admin/stats', { admin })).data;
  assert.equal(stats.players, 1);
  assert.equal(stats.special, 1);
  // give Alice the founder cape by name; she can wear it; take it back and it drops off
  let p = await call('POST', '/v1/admin/grant', { admin, body: { player: 'alice', item: 'cape_founder' } });
  assert.equal(p.status, 200, JSON.stringify(p.data));
  assert.deepEqual(p.data.grants.map((g) => g.item), ['cape_founder']);
  assert.equal((await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: { cape: 'cape_founder' } } })).status, 200);
  assert.deepEqual((await call('GET', '/v1/me', { token: alice })).data.owned, ['cape_founder']);
  await call('POST', '/v1/admin/revoke', { admin, body: { player: dash(ALICE.id), item: 'cape_founder' } });
  assert.deepEqual((await call('GET', '/v1/me', { token: alice })).data.loadout.slots, {}, 'taken back');
  // a player who never used Polar can be given something before they sign in
  p = await call('POST', '/v1/admin/grant', { admin, body: { player: 'Carol', item: 'cape_founder' } });
  assert.equal(p.data.signedIn, false);
  assert.equal((await call('POST', '/v1/admin/grant', { admin, body: { player: 'Nobody_Here', item: 'cape_founder' } })).status, 404);
  // codes: one use each
  const made = await call('POST', '/v1/admin/codes', { admin, body: { item: 'cape_founder', uses: 1, count: 2, note: 'giveaway' } });
  assert.equal(made.data.made.length, 2);
  assert.match(made.data.made[0], /^POLAR-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  const [c1] = made.data.made;
  let r = await call('POST', '/v1/redeem', { token: alice, body: { code: c1.toLowerCase().replace(/-/g, ' ') } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal(r.data.name, 'Founder Cape');
  assert.equal((await call('POST', '/v1/redeem', { token: alice, body: { code: c1 } })).status, 409);
  const bob = await signIn(call, mojang, BOB);
  r = await call('POST', '/v1/redeem', { token: bob, body: { code: c1 } });
  assert.equal(r.status, 410);
  assert.match(r.data.error, /used up/);
  assert.equal((await call('POST', '/v1/redeem', { token: bob, body: { code: 'POLAR-NOPE-NOPE' } })).status, 404);
  const codes = (await call('GET', '/v1/admin/codes', { admin })).data.codes;
  assert.deepEqual(codes.map((c) => c.uses_left).sort(), [0, 1]);
  assert.equal((await call('DELETE', `/v1/admin/codes?code=${encodeURIComponent(made.data.made[1])}`, { admin })).data.codes.length, 1);
  // blocking closes the live connection and refuses the token
  await goOnline(hub, bob);
  p = await call('POST', '/v1/admin/ban', { admin, body: { player: 'Bob_2', banned: true } });
  assert.equal(p.data.banned, true);
  assert.equal(p.data.online, false);
  assert.equal((await call('GET', '/v1/me', { token: bob })).status, 403);
  stats = (await call('GET', '/v1/admin/stats', { admin })).data;
  assert.equal(stats.grants, 2);
});

test('friends: requests, accepting, who is online and where, invites, removing', async () => {
  const mojang = fakeMojang();
  const { hub, call, ctx } = setup();
  const alice = await signIn(call, mojang, ALICE);
  const bob = await signIn(call, mojang, BOB);
  const A = dash(ALICE.id), B = dash(BOB.id);
  assert.equal((await call('POST', '/v1/friends/add', { token: alice, body: { name: 'Carol' } })).status, 404, "Carol never used Polar");
  assert.equal((await call('POST', '/v1/friends/add', { token: alice, body: { name: 'alice' } })).status, 400, 'yourself');
  await goOnline(hub, bob);
  let r = await call('POST', '/v1/friends/add', { token: alice, body: { name: 'bob_2' } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.deepEqual(r.data.outgoing, [{ uuid: B, name: 'Bob_2' }]);
  const bobSocket = ctx.sockets.find((s) => s.tags.includes(`u:${B}`));
  assert.equal(JSON.parse(bobSocket.sent.at(-1)).event, 'request', 'Bob hears about it live');
  assert.equal((await call('POST', '/v1/friends/add', { token: alice, body: { name: 'Bob_2' } })).status, 409, 'asked already');
  r = await call('GET', '/v1/friends', { token: bob });
  assert.deepEqual(r.data.incoming, [{ uuid: A, name: 'Alice' }]);
  assert.equal((await call('POST', '/v1/friends/accept', { token: alice, body: { uuid: B } })).status, 404, "Alice can't accept her own request");
  r = await call('POST', '/v1/friends/accept', { token: bob, body: { uuid: A } });
  assert.equal(r.status, 200);
  assert.equal(r.data.friends[0].name, 'Alice');
  assert.equal(r.data.friends[0].online, false);
  // Alice comes online and joins a server; Bob sees where she is
  await goOnline(hub, alice);
  const aliceSocket = ctx.sockets.find((s) => s.tags.includes(`u:${A}`));
  hub.webSocketMessage(aliceSocket, JSON.stringify({ type: 'where', state: 'server', server: 'play.example.net' }));
  r = await call('GET', '/v1/friends', { token: bob });
  assert.deepEqual(r.data.friends[0].where, { state: 'server', server: 'play.example.net' });
  assert.equal(r.data.friends[0].online, true);
  // Alice invites Bob to her server
  r = await call('POST', '/v1/friends/invite', { token: alice, body: { uuid: B } });
  assert.deepEqual(r.data, { sent: true, server: 'play.example.net' });
  assert.deepEqual(JSON.parse(bobSocket.sent.at(-1)), { type: 'invite', from: { uuid: A, name: 'Alice' }, server: 'play.example.net' });
  // asking someone who already asked you makes you friends straight away
  const mojang2 = fakeMojang();
  const carol = await signIn(call, mojang2, { id: '99990000999900009999000099990000', name: 'Carol' });
  await call('POST', '/v1/friends/add', { token: carol, body: { name: 'Alice' } });
  r = await call('POST', '/v1/friends/add', { token: alice, body: { name: 'Carol' } });
  assert.match(r.data.message, /now friends/);
  assert.equal(r.data.friends.length, 2);
  // unfriend
  r = await call('POST', '/v1/friends/remove', { token: bob, body: { uuid: A } });
  assert.deepEqual(r.data.friends, []);
  assert.equal((await call('POST', '/v1/friends/invite', { token: alice, body: { uuid: B } })).status, 404);
  assert.equal((await call('GET', '/v1/friends', { token: alice })).data.friends.length, 1);
});

const KEY1 = 'a'.repeat(64), KEY2 = 'b'.repeat(64);
const offline = (call, name, key, ip = '9.9.9.9') => call('POST', '/v1/auth/offline', { body: { name, key }, headers: { 'CF-Connecting-IP': ip } });

test('offline profiles: Minecraft\'s offline ids, signed in by name alone', async () => {
  const mojang = fakeMojang();
  const { hub, call, ctx } = setup();
  // the id the game uses for an offline player: UUIDv3 of "OfflinePlayer:<name>" (Java's UUID.nameUUIDFromBytes)
  assert.equal(offlineUuid('AdminCanyon'), 'fb446c12-1f9c-30fd-8582-e13de6c79718');
  assert.equal(Buffer.from(md5(new TextEncoder().encode('message digest'))).toString('hex'), 'f96b697d7cb7938d525a2f31aaf161d0');
  assert.equal(isOfflineUuid(offlineUuid('Alice')), true);
  assert.equal(isOfflineUuid(dash(ALICE.id)), false);

  let r = await offline(call, 'AdminCanyon', KEY1);
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal(r.data.uuid, offlineUuid('AdminCanyon'));
  assert.equal(r.data.offline, true);
  const tok = r.data.token;
  assert.equal((await call('GET', '/v1/me', { token: tok })).data.name, 'AdminCanyon');
  // no keys: any PC can use the name (old launchers' keys are ignored), and the id stays the same
  r = await offline(call, 'AdminCanyon', KEY2);
  assert.equal(r.status, 200);
  assert.equal(r.data.uuid, offlineUuid('AdminCanyon'));
  assert.equal((await call('POST', '/v1/auth/offline', { body: { name: 'AdminCanyon' }, headers: { 'CF-Connecting-IP': '9.9.9.9' } })).status, 200, 'no key at all');
  assert.equal((await offline(call, 'ab', KEY1)).status, 400, 'too short');

  // cosmetics and the badge, like any player
  assert.equal((await call('PUT', '/v1/me/loadout', { token: tok, body: { slots: { cape: 'cape_aurora' } } })).status, 200);
  await goOnline(hub, tok);
  const alice = await signIn(call, mojang, ALICE);
  r = await call('POST', '/v1/players', { token: alice, body: { uuids: [offlineUuid('AdminCanyon')] } });
  assert.deepEqual(r.data.players[offlineUuid('AdminCanyon')], { badge: true, slots: { cape: { id: 'cape_aurora' } } });

  // friends: an offline profile called Alice as well as the real Alice
  const fakeAlice = (await offline(call, 'Alice', KEY2)).data.token;
  r = await call('POST', '/v1/friends/add', { token: tok, body: { name: 'alice' } });
  assert.deepEqual(r.data.outgoing, [{ uuid: offlineUuid('Alice'), name: 'Alice', offline: true }], 'offline profiles find offline profiles first');
  r = await call('POST', '/v1/friends/add', { token: alice, body: { name: 'AdminCanyon' } });
  assert.deepEqual(r.data.outgoing, [{ uuid: offlineUuid('AdminCanyon'), name: 'AdminCanyon', offline: true }], 'the real Alice finds the only AdminCanyon');
  r = await call('POST', '/v1/friends/accept', { token: tok, body: { uuid: dash(ALICE.id) } });
  assert.deepEqual(r.data.friends.map((f) => [f.name, Boolean(f.offline)]), [['Alice', false]]);
  assert.equal((await call('GET', '/v1/friends', { token: alice })).data.friends[0].offline, true);
  const bob = await signIn(call, mojang, BOB);
  r = await call('POST', '/v1/friends/add', { token: bob, body: { name: 'Alice' } });
  assert.deepEqual(r.data.outgoing, [{ uuid: dash(ALICE.id), name: 'Alice' }], 'Minecraft accounts find Minecraft accounts first');
  assert.ok(fakeAlice);

  // admin: the real account first, the offline one by its id; offline profiles counted
  const admin = 'admin-pass-123456';
  assert.equal((await call('GET', '/v1/admin/player?q=Alice', { admin })).data.uuid, dash(ALICE.id));
  r = await call('GET', `/v1/admin/player?q=${offlineUuid('Alice')}`, { admin });
  assert.equal(r.data.offline, true);
  assert.equal((await call('GET', '/v1/admin/stats', { admin })).data.offline, 2);
  // blocked offline profiles can't sign in again
  await call('POST', '/v1/admin/ban', { admin, body: { player: offlineUuid('AdminCanyon'), banned: true } });
  assert.equal((await offline(call, 'AdminCanyon', KEY1)).status, 403);
});

test('offline profiles: new names per address are limited', async () => {
  const { call } = setup();
  let last;
  for (let i = 0; i < 21; i++) last = await offline(call, `Player${i}x`, KEY1, i < 18 ? '7.7.7.7' : '7.7.7.7');
  assert.equal(last.status, 429);
  assert.equal((await offline(call, 'Player0x', KEY1, '7.7.7.7')).status, 200, 'names you already have still sign in');
  assert.equal((await offline(call, 'Someone', KEY1, '8.8.8.8')).status, 200);
});

test('settings sync keeps one copy per player', async () => {
  const mojang = fakeMojang();
  const { call } = setup();
  const alice = await signIn(call, mojang, ALICE);
  assert.deepEqual((await call('GET', '/v1/sync', { token: alice })).data, { data: null, updated: 0 });
  assert.equal((await call('PUT', '/v1/sync', { token: alice, body: { mods: { zoom: true }, hud: [1, 2] } })).status, 200);
  assert.deepEqual((await call('GET', '/v1/sync', { token: alice })).data.data, { mods: { zoom: true }, hud: [1, 2] });
  assert.equal((await call('PUT', '/v1/sync', { token: alice, body: 'x'.repeat(300 * 1024) })).status, 413);
});

test('requests per minute are limited', async () => {
  const { call } = setup();
  let last;
  for (let i = 0; i < 31; i++) last = await call('POST', '/v1/auth/challenge', { headers: { 'CF-Connecting-IP': '1.2.3.4' } });
  assert.equal(last.status, 429);
  assert.equal((await call('POST', '/v1/auth/challenge', { headers: { 'CF-Connecting-IP': '5.6.7.8' } })).status, 200);
});

test('the worker sends /v1/ to the hub and everything else to the static files', async () => {
  const seen = [];
  const env = {
    HUB: { idFromName: (n) => n, get: (id) => ({ fetch: async (r) => (seen.push(['hub', id, new URL(r.url).pathname]), new Response('hub')) }) },
    ASSETS: { fetch: async (r) => (seen.push(['assets', new URL(r.url).pathname]), new Response('file')) },
  };
  await worker.fetch(new Request('https://polar.test/v1/me'), env);
  await worker.fetch(new Request('https://polar.test/cosmetics/catalog.json'), env);
  assert.deepEqual(seen, [['hub', 'polar', '/v1/me'], ['assets', '/cosmetics/catalog.json']]);
});

test('coins: only the server adds them (10 an hour while the game is open) and only admins change them', async () => {
  const mojang = fakeMojang();
  const { hub, call, ctx } = setup();
  const realNow = Date.now;
  let now = realNow();
  Date.now = () => now;
  try {
    const alice = await signIn(call, mojang, ALICE);
    assert.equal((await call('GET', '/v1/me/coins', { token: alice })).data.coins, 0);
    await goOnline(hub, alice);
    const sock = ctx.sockets.find((s) => s.tags.includes(`u:${dash(ALICE.id)}`));
    // in the launcher: no coins
    hub.webSocketMessage(sock, JSON.stringify({ type: 'where', state: 'launcher' }));
    now += 3600 * 1000;
    assert.equal((await call('GET', '/v1/me/coins', { token: alice })).data.coins, 0);
    // in game for 90 minutes: 15
    hub.webSocketMessage(sock, JSON.stringify({ type: 'where', state: 'server', server: 'play.example.net' }));
    now += 90 * 60 * 1000;
    let me = (await call('GET', '/v1/me/coins', { token: alice })).data;
    assert.deepEqual([me.coins, me.earning], [15, true]);
    // closing the game stops the clock, and the player is told the new balance
    now += 30 * 60 * 1000;
    sock.readyState = 3;
    hub.webSocketClose(sock, 1000, '');
    now += 5 * 3600 * 1000;
    me = (await call('GET', '/v1/me/coins', { token: alice })).data;
    assert.deepEqual([me.coins, me.earning], [20, false]);
    // nothing a player sends sets coins
    for (const [m, p] of [['POST', '/v1/me/coins'], ['PUT', '/v1/me/coins'], ['POST', '/v1/admin/coins/set']]) {
      const r = await call(m, p, { token: alice, body: { coins: 999999 } });
      assert.ok(r.status >= 400, `${m} ${p} refused`);
    }
    assert.equal((await call('GET', '/v1/me/coins', { token: alice })).data.coins, 20);
    // the admin sets it
    const set = await call('POST', '/v1/admin/coins/set', { admin: 'admin-pass-123456', body: { uuid: ALICE.id, coins: 500 } });
    assert.equal(set.status, 200);
    assert.equal((await call('GET', '/v1/me/coins', { token: alice })).data.coins, 500);
    // a stretch the server lost track of counts for at most 6 hours
    await goOnline(hub, alice);
    const sock2 = ctx.sockets.filter((s) => s.tags.includes(`u:${dash(ALICE.id)}`) && s.readyState === 1)[0];
    hub.webSocketMessage(sock2, JSON.stringify({ type: 'where', state: 'menu' }));
    now += 48 * 3600 * 1000;
    assert.equal((await call('GET', '/v1/me/coins', { token: alice })).data.coins, 560);
    assert.ok(sock2.sent.some((m) => JSON.parse(m).type === 'coins'), 'the launcher hears about new coins');
  } finally {
    Date.now = realNow;
  }
});

test('cosmetic prices: free until an admin prices one, buying takes coins once, special ones are never for sale', async () => {
  const mojang = fakeMojang();
  const { call } = setup();
  const admin = 'admin-pass-123456';
  const alice = await signIn(call, mojang, ALICE);
  // nothing priced yet: every non-special cosmetic stays free, so nobody's outfit changes on deploy
  let r = await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: { cape: 'cape_aurora' } } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  const me = (await call('GET', '/v1/me', { token: alice })).data;
  assert.deepEqual(me.cosmeticConfig.cape_aurora, { price: 0, codeOnly: false });
  assert.equal(me.cosmeticConfig.cape_founder.codeOnly, true);
  assert.equal((await call('POST', '/v1/cosmetics/buy', { token: alice, body: { item: 'cape_aurora' } })).status, 400, 'free items are not sold');
  // only the admin sets prices; a special item can't be given a price
  assert.equal((await call('POST', '/v1/admin/cosmetics/set', { token: alice, body: { item: 'cape_aurora', price: 0 } })).status, 401);
  assert.equal((await call('POST', '/v1/admin/cosmetics/set', { admin, body: { item: 'cape_aurora', price: 300 } })).status, 200);
  const founder = (await call('POST', '/v1/admin/cosmetics/set', { admin, body: { item: 'cape_founder', price: 5 } })).data.items.find((i) => i.id === 'cape_founder');
  assert.deepEqual([founder.price, founder.codeOnly], [0, true]);
  r = await call('POST', '/v1/cosmetics/buy', { token: alice, body: { item: 'cape_founder' } });
  assert.equal(r.status, 403);
  // priced now: wearing it needs buying, and buying needs the coins
  assert.equal((await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: { cape: 'cape_aurora' } } })).status, 403);
  r = await call('POST', '/v1/cosmetics/buy', { token: alice, body: { item: 'cape_aurora' } });
  assert.equal(r.status, 402);
  await call('POST', '/v1/admin/coins/set', { admin, body: { uuid: ALICE.id, coins: 500 } });
  r = await call('POST', '/v1/cosmetics/buy', { token: alice, body: { item: 'cape_aurora' } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal(r.data.coins, 200);
  assert.ok(r.data.owned.includes('cape_aurora'));
  assert.equal((await call('POST', '/v1/cosmetics/buy', { token: alice, body: { item: 'cape_aurora' } })).status, 409, 'bought once');
  assert.equal((await call('GET', '/v1/me/coins', { token: alice })).data.coins, 200);
  assert.equal((await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: { cape: 'cape_aurora' } } })).status, 200);
});

test('name tags: only admins grant them, players pick one they have, other players see the one picked', async () => {
  const mojang = fakeMojang();
  const { hub, call } = setup();
  const admin = 'admin-pass-123456';
  const alice = await signIn(call, mojang, ALICE);
  const bob = await signIn(call, mojang, BOB);
  assert.equal((await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: {}, activeTag: 'dev' } })).status, 403, 'not granted');
  assert.equal((await call('POST', '/v1/admin/tags/grant', { token: alice, body: { uuid: ALICE.id, tag: 'dev' } })).status, 401, 'players cannot grant');
  assert.equal((await call('POST', '/v1/admin/tags/grant', { admin, body: { uuid: ALICE.id, tag: 'nope' } })).status, 400);
  const g = await call('POST', '/v1/admin/tags/grant', { admin, body: { uuid: ALICE.id, tag: 'dev' } });
  assert.deepEqual(g.data.tags, ['dev']);
  let r = await call('PUT', '/v1/me/loadout', { token: alice, body: { slots: {}, activeTag: 'DEV' } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal(r.data.loadout.activeTag, 'dev');
  await goOnline(hub, alice);
  const ask = async () => (await call('POST', '/v1/players', { token: bob, body: { uuids: [ALICE.id] } })).data.players[dash(ALICE.id)];
  assert.deepEqual((await ask()).tags, ['dev']);
  // taken back: it disappears even though Alice still has it picked
  await call('POST', '/v1/admin/tags/revoke', { admin, body: { uuid: ALICE.id, tag: 'dev' } });
  assert.equal((await ask()).tags, undefined);
});

test('partners added in the admin panel are listed for every launcher', async () => {
  const { call } = setup();
  assert.equal((await call('POST', '/v1/admin/partners/save', { body: { name: 'X', ip: 'x.net' } })).status, 401, 'needs the password');
  const saved = await call('POST', '/v1/admin/partners/save', { admin: 'admin-pass-123456', body: { name: 'HazeSMP', ip: 'Play.HazeSMP.net', tags: 'SMP, Economy', discord: 'https://discord.gg/5RajxZQfH', logo: 'https://evil.example/x.png' } });
  assert.equal(saved.status, 200);
  const pub = await call('GET', '/v1/partners');
  assert.deepEqual(pub.data.partners.map((p) => [p.id, p.ip, p.logo, p.tags.join(',')]), [['hazesmp', 'play.hazesmp.net', '', 'SMP,Economy']]);
  await call('POST', '/v1/admin/partners/delete', { admin: 'admin-pass-123456', body: { id: 'hazesmp' } });
  assert.equal((await call('GET', '/v1/partners')).data.partners.length, 0);
});

test('servers can ask whether a player is in game with Polar on their server, and learn nothing else', async () => {
  const mojang = fakeMojang();
  const { ctx, hub, call } = setup();
  const token = await signIn(call, mojang, ALICE);
  const ask = (q) => call('GET', `/v1/playing?${new URLSearchParams(q)}`);
  assert.deepEqual((await ask({ uuid: ALICE.id })).data, { playing: false }, 'signed in but not connected');
  await goOnline(hub, token);
  const sock = ctx.sockets[ctx.sockets.length - 1];
  hub.webSocketMessage(sock, JSON.stringify({ type: 'where', state: 'menu' }));
  assert.deepEqual((await ask({ uuid: ALICE.id })).data, { playing: false }, 'in the menus is not on a server');
  hub.webSocketMessage(sock, JSON.stringify({ type: 'where', state: 'server', server: 'mc.hazesmp.net:25565' }));
  assert.deepEqual((await ask({ uuid: ALICE.id })).data, { playing: true });
  assert.deepEqual((await ask({ uuid: dash(ALICE.id), host: 'hazesmp.net' })).data, { playing: true }, 'subdomains and ports count');
  assert.deepEqual((await ask({ uuid: ALICE.id, host: 'mc.hazesmp.net' })).data, { playing: true });
  assert.deepEqual((await ask({ uuid: ALICE.id, host: 'othersmp.net' })).data, { playing: false }, 'on another server');
  assert.deepEqual((await ask({ uuid: ALICE.id, host: 'zesmp.net' })).data, { playing: false });
  // cracked servers know players by name only (their UUID is name-based)
  assert.deepEqual((await ask({ uuid: '00000000000000000000000000000001', name: 'alice', host: 'hazesmp.net' })).data, { playing: true });
  assert.deepEqual((await ask({ name: 'Bob_2' })).data, { playing: false });
  assert.deepEqual((await ask({})).data, { playing: false });
  const r = await ask({ uuid: ALICE.id });
  assert.deepEqual(Object.keys(r.data), ['playing'], 'only yes or no');
});

test('social: status, messages, profiles and parties', async () => {
  const mojang = fakeMojang();
  const { hub, call, ctx } = setup();
  const alice = await signIn(call, mojang, ALICE);
  const bob = await signIn(call, mojang, BOB);
  const A = dash(ALICE.id), B = dash(BOB.id);
  await call('POST', '/v1/friends/add', { token: alice, body: { name: 'Bob_2' } });
  await call('POST', '/v1/friends/accept', { token: bob, body: { uuid: A } });
  await goOnline(hub, alice);
  await goOnline(hub, bob);
  const bobSocket = ctx.sockets.find((s) => s.tags.includes(`u:${B}`));
  const aliceSocket = ctx.sockets.find((s) => s.tags.includes(`u:${A}`));
  hub.webSocketMessage(aliceSocket, JSON.stringify({ type: 'where', state: 'server', server: 'play.example.net' }));

  // status: invisible shows as offline, busy holds invites
  assert.equal((await call('POST', '/v1/social/status', { token: alice, body: { status: 'nope' } })).status, 400);
  await call('POST', '/v1/social/status', { token: alice, body: { status: 'invisible' } });
  let r = await call('GET', '/v1/friends', { token: bob });
  assert.equal(r.data.friends[0].online, false);
  assert.equal(r.data.friends[0].where, null, 'invisible hides where you are');
  await call('POST', '/v1/social/status', { token: bob, body: { status: 'busy' } });
  await call('POST', '/v1/social/status', { token: alice, body: { status: 'online' } });
  assert.equal((await call('POST', '/v1/friends/invite', { token: alice, body: { uuid: B } })).status, 409, 'busy holds invites');
  r = await call('GET', '/v1/friends', { token: alice });
  assert.equal(r.data.friends[0].status, 'busy');

  // messages: friends only, relayed live, unread until the thread is read
  r = await call('POST', '/v1/social/dm', { token: alice, body: { uuid: B, text: '  hi§c there  ' } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal(r.data.message.text, 'hi c there', 'formatting codes and extra spaces go');
  const live = JSON.parse(bobSocket.sent.at(-1));
  assert.equal(live.type, 'dm');
  assert.equal(live.from.name, 'Alice');
  assert.equal((await call('POST', '/v1/social/dm', { token: alice, body: { uuid: B, text: '   ' } })).status, 400);
  assert.equal((await call('POST', '/v1/social/dm', { token: alice, body: { uuid: dash('99990000999900009999000099990000'), text: 'x' } })).status, 404);
  r = await call('GET', '/v1/social/inbox', { token: bob });
  assert.deepEqual(r.data.unread.map((u) => [u.name, u.count]), [['Alice', 1]]);
  assert.equal((await call('GET', '/v1/friends', { token: bob })).data.friends[0].unread, 1);
  r = await call('POST', '/v1/social/thread', { token: bob, body: { uuid: A } });
  assert.deepEqual(r.data.messages.map((m) => [m.from, m.text]), [[A, 'hi c there']]);
  assert.equal((await call('GET', '/v1/social/inbox', { token: bob })).data.unread.length, 0, 'read now');

  // profiles: by name, where only for friends
  r = await call('POST', '/v1/social/profile', { token: bob, body: { name: 'alice' } });
  assert.equal(r.data.polar, true);
  assert.equal(r.data.name, 'Alice');
  assert.deepEqual(r.data.where, { state: 'server', server: 'play.example.net' });
  assert.equal((await call('POST', '/v1/social/profile', { token: bob, body: { name: 'Nobody' } })).data.polar, false);

  // parties: invite (makes the party), join, warp, chat, leave
  r = await call('POST', '/v1/social/party/invite', { token: alice, body: { uuid: B } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  const partyId = r.data.party.id;
  assert.equal(r.data.party.members.length, 1);
  assert.deepEqual(r.data.party.pending.map((m) => m.name), ['Bob_2']);
  const inv = JSON.parse(bobSocket.sent.at(-1));
  assert.equal(inv.event, 'invite');
  r = await call('GET', '/v1/social/party', { token: bob });
  assert.equal(r.data.invites[0].from.name, 'Alice');
  assert.equal((await call('POST', '/v1/social/party/join', { token: bob, body: { party: 'nope' } })).status, 404);
  r = await call('POST', '/v1/social/party/join', { token: bob, body: { party: partyId } });
  assert.deepEqual(r.data.party.members.map((m) => [m.name, m.leader]), [['Alice', true], ['Bob_2', false]]);
  assert.equal((await call('POST', '/v1/social/party/warp', { token: bob, body: {} })).status, 403, 'only the leader brings the party');
  r = await call('POST', '/v1/social/party/warp', { token: alice, body: {} });
  assert.deepEqual(r.data, { sent: true, server: 'play.example.net' });
  assert.deepEqual(JSON.parse(bobSocket.sent.at(-1)).server, 'play.example.net');
  await call('POST', '/v1/social/party/chat', { token: bob, body: { text: 'gg' } });
  assert.equal(JSON.parse(aliceSocket.sent.at(-1)).text, 'gg');
  // the leader leaves: a party of one is no party
  r = await call('POST', '/v1/social/party/leave', { token: alice });
  assert.equal(r.data.party, null);
  assert.equal((await call('GET', '/v1/social/party', { token: bob })).data.party, null);
  assert.equal((await call('GET', '/v1/social/nothing', { token: bob })).status, 404);
});
