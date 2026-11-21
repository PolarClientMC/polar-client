// Keyframe animations and springs for cosmetic models: the same data and maths as the Polar mod
// (mod/common/.../cosmetics/Keyframes.java), so the wardrobe shows what players see in game.
// Format: tools/cosmetics/ANIMATIONS.md. Works in the launcher (window.PolarKeyframes) and in Node (the tests).
(function (root) {
  'use strict';
  const LINEAR = 0, SMOOTH = 1, STEP = 2;
  const FALLBACK = {
    idle: ['idle'], walk: ['walk', 'idle'], sprint: ['sprint', 'walk', 'idle'], sneak: ['sneak', 'idle'], fall: ['fall', 'idle'],
    fly: ['fly', 'fall', 'sprint', 'walk', 'idle'], glide: ['glide', 'fly', 'fall', 'sprint', 'walk', 'idle'], swim: ['swim', 'sneak', 'idle'], ride: ['ride', 'sneak', 'idle'], sleep: ['sleep', 'sneak', 'idle'],
  };
  const BLEND = 0.2, STEP_DT = 1 / 120;

  const num = (v, def) => {
    if (typeof v === 'number') return v;
    if (typeof v === 'string') { const n = Number(v.trim()); return v.trim() !== '' && Number.isFinite(n) ? n : def; }
    return def;
  };
  const vec = (v, def) => {
    if (Array.isArray(v)) return [0, 1, 2].map((i) => num(v[i], def));
    if (v == null || typeof v === 'object') return [def, def, def];
    const x = num(v, def);
    return [x, x, x];
  };
  const mode = (s) => (s === 'catmullrom' || s === 'smooth' || s === 'bezier' ? SMOOTH : s === 'step' ? STEP : LINEAR);
  const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

  function catmull(p0, p1, p2, p3, u) {
    const u2 = u * u, u3 = u2 * u;
    return 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
  }

  /** A channel: a constant ([x, y, z] or a number) or keys ({"0.5": value, ...}). */
  function channel(e, def) {
    if (e == null) return null;
    if (typeof e !== 'object' || Array.isArray(e)) { const v = vec(e, def); return { times: [0], pre: [v], post: [v], modes: [LINEAR] }; }
    const keys = Object.entries(e).map(([t, v]) => [num(t, 0), v]).sort((a, b) => a[0] - b[0]);
    const ch = { times: [], pre: [], post: [], modes: [] };
    for (const [t, v] of keys) {
      ch.times.push(t);
      if (v && typeof v === 'object' && !Array.isArray(v)) {
        const p = 'post' in v ? vec(v.post, def) : 'pre' in v ? vec(v.pre, def) : vec(v.value, def);
        ch.pre.push('pre' in v ? vec(v.pre, def) : p);
        ch.post.push(p);
        ch.modes.push(mode(v.lerp_mode || v.ease || 'linear'));
      } else {
        const p = vec(v, def);
        ch.pre.push(p);
        ch.post.push(p);
        ch.modes.push(LINEAR);
      }
    }
    return ch;
  }

  function sample(ch, t) {
    const n = ch.times.length;
    if (!n) return null;
    if (t <= ch.times[0]) return ch.pre[0];
    if (t >= ch.times[n - 1]) return ch.post[n - 1];
    let i = 0;
    while (i < n - 2 && t >= ch.times[i + 1]) i++;
    const span = ch.times[i + 1] - ch.times[i];
    const u = span <= 0 ? 1 : (t - ch.times[i]) / span;
    const a = ch.post[i], b = ch.pre[i + 1];
    if (ch.modes[i] === STEP) return a;
    if (ch.modes[i] === SMOOTH || ch.modes[i + 1] === SMOOTH) {
      const p0 = i > 0 ? ch.post[i - 1] : a, p3 = i + 2 < n ? ch.pre[i + 2] : b;
      return [0, 1, 2].map((k) => catmull(p0[k], a[k], b[k], p3[k], u));
    }
    return [0, 1, 2].map((k) => a[k] + (b[k] - a[k]) * u);
  }

  /** A model's "animations" object -> clips by state name. */
  function clips(animations) {
    const out = {};
    for (const [key, o] of Object.entries(animations || {})) {
      if (!o || typeof o !== 'object') continue;
      try {
        const name = key.slice(key.lastIndexOf('.') + 1);
        const loop = o.loop == null ? true : typeof o.loop === 'boolean' ? o.loop : o.loop !== 'once' && o.loop !== 'hold_on_last_frame';
        const c = { name, length: num(o.length ?? o.animation_length, 0), loop, speed: num(o.speed, 1), blend: Math.max(0, num(o.blend, 0)), stride: o.stride ?? (name === 'walk' || name === 'sprint'), bones: {} };
        let last = 0;
        for (const [bone, bo] of Object.entries(o.bones || {})) {
          if (!bo || typeof bo !== 'object') continue;
          const ch = [channel(bo.rotation, 0), channel(bo.position, 0), channel(bo.scale, 1)];
          for (const x of ch) if (x && x.times.length) last = Math.max(last, x.times[x.times.length - 1]);
          c.bones[bone] = ch;
        }
        if (c.length <= 0) c.length = last;
        out[name] = c;
      } catch (e) { /* a broken clip shouldn't take the cosmetic down with it */ }
    }
    return out;
  }

  const vec3 = (v, def) => (Array.isArray(v) ? [0, 1, 2].map((i) => num(v[i], def)) : v == null ? null : vec(v, def));

  /**
   * Springs. The simple kind ("swing") turns round x for walking, jumping and falling and round z for turning. The
   * full kind says per axis how each push moves it ("move", "turn", "jump", "air": [x, y, z]), can follow the clips'
   * own motion with lag and overshoot ("follow": 0..1) and can bend against another bone's turning ("drag").
   */
  function springs(list) {
    return (Array.isArray(list) ? list : []).filter((s) => s && s.bone).map((s) => {
      const sp = {
        bone: String(s.bone), stiffness: Math.max(1, num(s.stiffness, 70)), damping: Math.max(0, num(s.damping, 7)), inertia: num(s.inertia, 1),
        limit: Math.max(1, num(s.limit, 50)), follow: clamp(num(s.follow, 0), 0, 1), dragBone: null, drag: [0, 0, 0],
      };
      const full = ['move', 'turn', 'jump', 'air', 'follow', 'drag'].some((k) => s[k] != null);
      if (full) {
        sp.move = vec3(s.move, 0) || [0, 0, 0];
        sp.turn = vec3(s.turn, 0) || [0, 0, 0];
        sp.jump = vec3(s.jump, 0) || [0, 0, 0];
        sp.air = vec3(s.air, 0) || [0, 0, 0];
        sp.gain = [1, 1, 1];
      } else {
        sp.move = [1, 0, 0]; sp.turn = [0, 0, 1]; sp.jump = [1, 0, 0]; sp.air = [1, 0, 0];
        sp.gain = Array.isArray(s.swing) ? [num(s.swing[0], 1), 0, num(s.swing[1], 1)] : [1, 0, 1];
      }
      if (s.drag && typeof s.drag === 'object' && s.drag.bone) {
        sp.dragBone = String(s.drag.bone);
        sp.drag = vec3(s.drag.amount, 0) || [0, 0, 0];
      }
      return sp;
    });
  }

  /** Which state a player is in: { moving, sprinting, sneaking, onGround, flying, gliding, swimming, riding, sleeping }. */
  function state(p) {
    if (p.sleeping) return 'sleep';
    if (p.swimming) return 'swim';
    if (p.gliding) return 'glide';
    if (p.flying) return 'fly';
    if (p.riding) return 'ride';
    if (p.sneaking) return 'sneak';
    if (p.onGround === false) return 'fall';
    if (p.sprinting) return 'sprint';
    if ((p.moving || 0) > 0.2) return 'walk';
    return 'idle';
  }

  function pick(all, st) {
    for (const s of FALLBACK[st] || [st, 'idle']) if (all[s]) return all[s];
    return null;
  }

  const at = (c, t) => {
    if (c.length <= 0) return t;
    if (c.loop) { const r = t % c.length; return r < 0 ? r + c.length : r; }
    return Math.min(t, c.length);
  };
  const base = () => [0, 0, 0, 0, 0, 0, 1, 1, 1];

  function apply(c, clock, w, pose) {
    const t = at(c, clock);
    for (const [bone, ch] of Object.entries(c.bones)) {
      const out = pose[bone] || (pose[bone] = base());
      const r = ch[0] && sample(ch[0], t), p = ch[1] && sample(ch[1], t), s = ch[2] && sample(ch[2], t);
      for (let i = 0; i < 3; i++) {
        if (r) out[i] += r[i] * w;
        if (p) out[3 + i] += p[i] * w;
        if (s) out[6 + i] *= 1 + (s[i] - 1) * w;
      }
    }
  }

  function rig() {
    return { clips: {}, springs: {}, track: {}, frame: 0, lastMoving: 0, yawSpeed: 0, lastYaw: 0, hasYaw: false, onGround: true, started: false, always: 0 };
  }

  /** Advances a rig by dt seconds and adds this frame's pose into `pose` (bone -> [rx, ry, rz, px, py, pz, sx, sy, sz]). */
  function play(all, sprs, r, p, dt, pose) {
    if (Object.keys(all).length) {
      const target = pick(all, state(p));
      const blend = target && target.blend > 0 ? target.blend : BLEND; // how fast the new clip takes over
      const k = dt <= 0 ? (r.started ? 0 : 1) : 1 - Math.exp(-dt / blend);
      r.started = true;
      if (target && !r.clips[target.name]) r.clips[target.name] = [Object.keys(r.clips).length ? 0 : 1, 0];
      let total = 0;
      for (const [name, wt] of Object.entries(r.clips)) {
        const c = all[name];
        if (!c) { wt[0] = 0; continue; }
        const on = !!target && name === target.name;
        if (on && wt[0] < 0.001 && !c.loop) wt[1] = 0;
        wt[0] += ((on ? 1 : 0) - wt[0]) * k;
        wt[1] += dt * c.speed * (c.stride ? 0.55 + 0.45 * clamp(p.moving || 0, 0, 1) : 1);
        if (c.loop && c.length > 0 && wt[1] > c.length * 1000) wt[1] %= c.length;
        total += wt[0];
      }
      for (const name of Object.keys(r.clips)) if (r.clips[name][0] < 0.001 && !(target && name === target.name)) delete r.clips[name];
      for (const [name, wt] of Object.entries(r.clips)) {
        const w = total > 0 ? wt[0] / total : 0;
        if (w > 0) apply(all[name], wt[1], w, pose);
      }
      if (all.always) {
        r.always += dt * all.always.speed;
        if (all.always.loop && all.always.length > 0 && r.always > all.always.length * 1000) r.always %= all.always.length;
        apply(all.always, r.always, 1, pose);
      }
    }
    if (sprs.length) swing(sprs, r, p, dt, pose);
  }

  function swing(sprs, r, p, dt, pose) {
    const moving = clamp(p.moving || 0, 0, 1);
    const accel = dt > 0 ? clamp((moving - r.lastMoving) / dt, -6, 6) : 0;
    const yaw = p.bodyYaw || 0;
    if (r.hasYaw && dt > 0) {
      let turn = yaw - r.lastYaw;
      turn -= 360 * Math.round(turn / 360);
      r.yawSpeed += (clamp(turn / dt, -720, 720) - r.yawSpeed) * (1 - Math.exp(-dt / 0.1));
    }
    r.lastYaw = yaw;
    r.hasYaw = true;
    const ground = p.onGround !== false || p.swimming || p.riding;
    let kick = 0;
    if (r.onGround && !ground) kick = -140;
    else if (!r.onGround && ground) kick = 220;
    r.onGround = ground;
    r.lastMoving = moving;
    const flying = p.flying || p.gliding;
    const tMove = moving * (p.sprinting ? 28 : 16), tAir = ground ? 0 : flying ? 40 : -10;
    const tTurn = clamp(-r.yawSpeed * 0.06, -30, 30);
    for (const s of sprs) {
      const out = pose[s.bone] || (pose[s.bone] = base());
      // the drag: how fast the other bone turns right now (smoothed a little)
      let dv = null;
      if (s.dragBone) {
        const o = pose[s.dragBone];
        const tr = r.track[s.dragBone] || (r.track[s.dragBone] = { last: o ? [o[0], o[1], o[2]] : [0, 0, 0], vel: [0, 0, 0], frame: -1 });
        if (tr.frame !== r.frame) {
          tr.frame = r.frame;
          for (let i = 0; i < 3; i++) {
            const cur = o ? o[i] : 0;
            const v = dt > 0 ? clamp((cur - tr.last[i]) / dt, -2000, 2000) : 0;
            tr.vel[i] += (v - tr.vel[i]) * (dt > 0 ? 1 - Math.exp(-dt / 0.04) : 0);
            tr.last[i] = cur;
          }
        }
        dv = tr.vel;
      }
      const target = [0, 0, 0];
      for (let i = 0; i < 3; i++) {
        target[i] = (tMove * s.move[i] + tAir * s.air[i] + tTurn * s.turn[i]) * s.inertia + s.follow * out[i] - (dv ? dv[i] * s.drag[i] : 0);
      }
      let st = r.springs[s.bone];
      if (!st) st = r.springs[s.bone] = [target[0], 0, target[1], 0, target[2], 0, s.follow * out[0], s.follow * out[1], s.follow * out[2]];
      for (let i = 0; i < 3; i++) st[2 * i + 1] += (kick * s.jump[i] - accel * 20 * s.move[i]) * s.inertia;
      // what it follows moves at this speed: the damping works against moving differently from it, so the part keeps
      // up with the clip and only overshoots where the clip changes direction
      const tv = [0, 0, 0];
      for (let i = 0; i < 3; i++) {
        const f = s.follow * out[i];
        tv[i] = dt > 0 ? clamp((f - st[6 + i]) / dt, -3000, 3000) : 0;
        st[6 + i] = f;
      }
      for (let left = Math.min(dt, 0.1); left > 1e-5; left -= STEP_DT) {
        const h = Math.min(STEP_DT, left);
        for (let i = 0; i < 3; i++) {
          st[2 * i + 1] += (s.stiffness * (target[i] - st[2 * i]) - s.damping * (st[2 * i + 1] - tv[i])) * h;
          st[2 * i] += st[2 * i + 1] * h;
        }
      }
      for (let i = 0; i < 3; i++) {
        // at the limit (counted from what it follows) it stops dead instead of pushing on
        const rest = s.follow * out[i], off = st[2 * i] - rest;
        if (Math.abs(off) > s.limit) { st[2 * i] = rest + Math.sign(off) * s.limit; st[2 * i + 1] = 0; }
        out[i] += st[2 * i] * s.gain[i] - rest;
      }
    }
    r.frame++;
  }

  const api = { clips, springs, channel, sample, catmull, state, pick, rig, play, base };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.PolarKeyframes = api;
})(typeof window !== 'undefined' ? window : globalThis);
