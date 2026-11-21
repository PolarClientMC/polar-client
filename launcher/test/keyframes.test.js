'use strict';
// Keyframe clips, state blending and springs for cosmetic models (src/renderer/keyframes.js), the wardrobe's copy of
// the mod's dev.polarclient.cosmetics.Keyframes.
const test = require('node:test');
const assert = require('node:assert/strict');
const KF = require('../src/renderer/keyframes');

const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b}`);
const MODEL = {
  animations: {
    'animation.test.idle': { loop: true, animation_length: 2, bones: { wing: { rotation: { '0.0': [0, 0, 0], '1.0': [0, 20, 0], '2.0': [0, 0, 0] }, scale: { 0: 1, 1: 1.5, 2: 1 } } } },
    walk: { length: 1, stride: false, bones: { wing: { rotation: { 0: { post: [10, 0, 0], lerp_mode: 'catmullrom' }, 0.5: { post: [30, 0, 0], lerp_mode: 'catmullrom' }, 1: [10, 0, 0] } } } },
    sneak: { length: 1, loop: 'hold_on_last_frame', bones: { wing: { position: { 0: [0, 0, 0], 0.5: { post: [0, 4, 0], lerp_mode: 'step' }, 1: [0, 8, 0] } } } },
    always: { length: 4, bones: { tail: { rotation: { 0: [0, 0, 0], 4: [0, 0, 360] } } } },
    broken: 'not a clip',
  },
  physics: [{ bone: 'tail', stiffness: 60, damping: 6, limit: 40 }, { stiffness: 5 }],
};

test('clips read Blockbench-style keys, constants, loops and Molang numbers', () => {
  const c = KF.clips(MODEL.animations);
  assert.deepEqual(Object.keys(c).sort(), ['always', 'idle', 'sneak', 'walk']);
  assert.equal(c.idle.length, 2);
  assert.equal(c.sneak.loop, false);
  assert.equal(c.walk.length, 1);
  const ch = KF.channel({ 0: ['1.5', 'q.anim_time', 2] }, 0);
  assert.deepEqual(KF.sample(ch, 0), [1.5, 0, 2], 'numbers in strings are read; expressions count as 0');
  assert.equal(KF.springs(MODEL.physics).length, 1, 'a spring needs a bone');
});

test('sampling: linear, Catmull-Rom, step, and holding before and after the keys', () => {
  const c = KF.clips(MODEL.animations);
  const idle = c.idle.bones.wing[0];
  near(KF.sample(idle, 0.5)[1], 10, 1e-9, 'linear halfway');
  near(KF.sample(idle, 1)[1], 20, 1e-9, 'on a key');
  near(KF.sample(idle, 9)[1], 0, 1e-9, 'held after the last key');
  near(KF.sample(c.walk.bones.wing[0], 0.25)[0], 21.25, 1e-9, 'Catmull-Rom');
  near(KF.sample(c.sneak.bones.wing[1], 0.75)[1], 4, 1e-9, 'step holds');
});

test('the clip follows what the player does, with stand-ins for clips a model lacks', () => {
  const c = KF.clips(MODEL.animations);
  const name = (p) => KF.pick(c, KF.state(p)).name;
  assert.equal(name({ moving: 0 }), 'idle');
  assert.equal(name({ moving: 1 }), 'walk');
  assert.equal(name({ moving: 1, sprinting: true }), 'walk');
  assert.equal(name({ moving: 0, onGround: false }), 'idle');
  assert.equal(name({ sneaking: true }), 'sneak');
  assert.equal(name({ swimming: true }), 'sneak');
  assert.equal(name({ flying: true, moving: 1 }), 'walk');
});

test('playback blends from one clip to the next and holds one-shot clips', () => {
  const c = KF.clips(MODEL.animations), r = KF.rig();
  let pose = {};
  KF.play(c, [], r, { moving: 0 }, 0, pose);
  near(pose.wing[1], 0, 1e-9, 'starts at the first frame');
  for (let i = 0; i < 60; i++) { pose = {}; KF.play(c, [], r, { moving: 0 }, 1 / 60, pose); }
  near(pose.wing[1], 20, 0.05, 'one second into idle');
  near(pose.wing[6], 1.5, 0.01, 'scale');
  pose = {};
  KF.play(c, [], r, { moving: 1 }, 1 / 60, pose);
  assert.ok(pose.wing[1] > 1 && pose.wing[1] < 20, 'walking fades idle out instead of snapping');
  for (let i = 0; i < 90; i++) { pose = {}; KF.play(c, [], r, { moving: 1 }, 1 / 60, pose); }
  near(pose.wing[1], 0, 0.2, 'idle has faded out');
  assert.ok(!r.clips.idle, 'faded clips are dropped');
  for (let i = 0; i < 120; i++) { pose = {}; KF.play(c, [], r, { sneaking: true }, 1 / 60, pose); }
  near(pose.wing[4], 8, 0.05, 'a one-shot clip holds its last frame');
});

test('springs trail behind a walk, settle, kick on a jump and stay inside their limit', () => {
  const s = KF.springs(MODEL.physics), r = KF.rig();
  let pose = {}, peak = 0;
  for (let i = 0; i < 120; i++) { pose = {}; KF.play({}, s, r, { moving: 0 }, 1 / 60, pose); }
  near(pose.tail[0], 0, 0.01, 'at rest');
  for (let i = 0; i < 300; i++) { pose = {}; KF.play({}, s, r, { moving: 1 }, 1 / 60, pose); peak = Math.max(peak, Math.abs(pose.tail[0])); }
  near(pose.tail[0], 16, 0.5, 'settles trailing behind');
  assert.ok(peak <= 40 + 1e-9, 'never past the limit');
  pose = {};
  KF.play({}, s, r, { moving: 1, onGround: false }, 1 / 60, pose);
  assert.ok(Math.abs(pose.tail[0] - 16) > 0.3, 'a jump kicks it');
  for (let i = 0; i < 10; i++) { pose = {}; KF.play({}, s, r, { moving: 0 }, 0.5, pose); }
  assert.ok(Number.isFinite(pose.tail[0]) && Math.abs(pose.tail[0]) <= 40 + 1e-9, 'long frame gaps stay steady');
  const r2 = KF.rig();
  for (let i = 0; i < 30; i++) { pose = {}; KF.play({}, s, r2, { moving: 0, bodyYaw: i * 6 }, 1 / 60, pose); }
  assert.ok(Math.abs(pose.tail[2]) > 3, 'turning swings it sideways');
});

test('full springs follow the clips with lag and overshoot, bend against a parent, and gliding picks its own clip', () => {
  const clips = KF.clips({
    idle: { length: 2, bones: { arm: { rotation: { 0: [0, 0, 0], 0.5: [0, 40, 0], 2: [0, 40, 0] } } } },
    fly: { length: 1, bones: { arm: { rotation: [0, -10, 0] } } },
    glide: { length: 1, bones: { arm: { rotation: [0, -30, 0] } } },
  });
  const s = KF.springs([
    { bone: 'arm', stiffness: 120, damping: 8, limit: 60, follow: 1, move: [0, 5, 0] },
    { bone: 'hand', stiffness: 90, damping: 7, limit: 30, follow: 1, drag: { bone: 'arm', amount: [0, 0.05, 0] } },
  ]);
  assert.deepEqual(s[0].gain, [1, 1, 1], 'full springs drive every axis');
  assert.deepEqual(KF.springs([{ bone: 't', swing: [0.5, -1] }])[0].gain, [0.5, 0, -1], 'simple springs keep "swing"');
  const r = KF.rig();
  let pose, lagged = false, peak = 0, bent = 0;
  for (let i = 0; i < 120; i++) {
    pose = {};
    KF.play(clips, s, r, { moving: 0 }, 1 / 60, pose);
    const t = (i + 1) / 60;
    if (t > 0.2 && t < 0.45 && pose.arm[1] < 40 * t / 0.5 - 1) lagged = true;
    peak = Math.max(peak, pose.arm[1]);
    bent = Math.min(bent, pose.hand[1]);
  }
  assert.ok(lagged, 'the arm trails the clip while it moves');
  assert.ok(peak > 40.5, `then overshoots it (${peak})`);
  near(pose.arm[1], 40, 0.5, 'and settles on it');
  assert.ok(bent < -1, `the hand bends back while the arm swings (${bent})`);
  near(pose.hand[1], 0, 0.3, 'and straightens once it stops');
  assert.equal(KF.pick(clips, KF.state({ gliding: true, onGround: false })).name, 'glide');
  assert.equal(KF.pick(clips, KF.state({ flying: true, onGround: false })).name, 'fly');
  assert.equal(KF.pick({ fly: clips.fly }, KF.state({ gliding: true })).name, 'fly', 'gliding falls back to fly');
});
