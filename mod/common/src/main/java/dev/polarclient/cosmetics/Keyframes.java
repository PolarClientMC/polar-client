package dev.polarclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyframe animations for cosmetic models, in the shape Blockbench exports them (Bedrock animation JSON), plus
 * springs for parts that should swing on their own (tails, ribbons, chains). The launcher's wardrobe viewer
 * (launcher/src/renderer/cosmetics3d.js) plays the same data the same way; tools/cosmetics/ANIMATIONS.md describes the
 * format.
 *
 * <pre>
 * "animations": {
 *   "idle":   { "length": 2.4, "loop": true, "bones": { "wing_l": { "rotation": { "0": [0, 0, 0], "1.2": { "post": [0, 18, 0], "lerp_mode": "catmullrom" }, "2.4": [0, 0, 0] } } } },
 *   "walk": {...}, "sprint": {...}, "sneak": {...}, "fall": {...}, "fly": {...}, "swim": {...}, "ride": {...}, "sleep": {...},
 *   "always": {...}   (plays on top of whichever of the others is on)
 * },
 * "physics": [ { "bone": "tail", "stiffness": 70, "damping": 7, "inertia": 1, "limit": 50, "swing": [1, 1] },
 *              { "bone": "hand_l", "follow": 1, "drag": { "bone": "arm_l", "amount": [0, 0.05, 0.08] }, "move": [0, 6, 0], "turn": [0, 1, 0], "jump": [0, 0, 1], "air": [0, 0, -0.5] } ]
 * </pre>
 * Values are in the model's own space (pixels, degrees, y down), like its bones.
 */
final class Keyframes {
	private Keyframes() {
	}

	static final byte LINEAR = 0, SMOOTH = 1, STEP = 2;
	static final int ROT = 0, POS = 1, SCALE = 2;
	/** The states a player can be in, and which clip stands in when a model has no clip of its own for one. */
	static final String[] STATES = {"idle", "walk", "sprint", "sneak", "fall", "fly", "glide", "swim", "ride", "sleep"};
	private static final Map<String, String[]> FALLBACK = new HashMap<>();

	static {
		FALLBACK.put("idle", new String[] {"idle"});
		FALLBACK.put("walk", new String[] {"walk", "idle"});
		FALLBACK.put("sprint", new String[] {"sprint", "walk", "idle"});
		FALLBACK.put("sneak", new String[] {"sneak", "idle"});
		FALLBACK.put("fall", new String[] {"fall", "idle"});
		FALLBACK.put("fly", new String[] {"fly", "fall", "sprint", "walk", "idle"});
		FALLBACK.put("glide", new String[] {"glide", "fly", "fall", "sprint", "walk", "idle"});
		FALLBACK.put("swim", new String[] {"swim", "sneak", "idle"});
		FALLBACK.put("ride", new String[] {"ride", "sneak", "idle"});
		FALLBACK.put("sleep", new String[] {"sleep", "sneak", "idle"});
	}

	/** One animated property of one bone: keys in time order. */
	static final class Channel {
		final float[] times;
		final float[][] pre, post; // the value arriving at a key and the value leaving it (the same unless split)
		final byte[] modes; // how to get from each key to the next

		Channel(float[] times, float[][] pre, float[][] post, byte[] modes) {
			this.times = times;
			this.pre = pre;
			this.post = post;
			this.modes = modes;
		}

		/** The value at t seconds (held before the first key and after the last). */
		void sample(float t, float[] out) {
			int n = times.length;
			if (n == 0) return;
			if (t <= times[0]) {
				copy(pre[0], out);
				return;
			}
			if (t >= times[n - 1]) {
				copy(post[n - 1], out);
				return;
			}
			int i = 0;
			while (i < n - 2 && t >= times[i + 1]) i++;
			float span = times[i + 1] - times[i];
			float u = span <= 0 ? 1 : (t - times[i]) / span;
			float[] a = post[i], b = pre[i + 1];
			byte mode = modes[i];
			if (mode == STEP) {
				copy(a, out);
			} else if (mode == SMOOTH || modes[i + 1] == SMOOTH) { // like Blockbench: smooth if either end asks for it
				float[] p0 = i > 0 ? post[i - 1] : a, p3 = i + 2 < n ? pre[i + 2] : b;
				for (int k = 0; k < 3; k++) out[k] = catmull(p0[k], a[k], b[k], p3[k], u);
			} else {
				for (int k = 0; k < 3; k++) out[k] = a[k] + (b[k] - a[k]) * u;
			}
		}

		private static void copy(float[] v, float[] out) {
			out[0] = v[0];
			out[1] = v[1];
			out[2] = v[2];
		}
	}

	/** Catmull-Rom through p1 and p2 (u 0..1), as Blockbench's "catmullrom" keyframes. */
	static float catmull(float p0, float p1, float p2, float p3, float u) {
		float u2 = u * u, u3 = u2 * u;
		return 0.5f * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
	}

	/** One named animation: a channel per bone and property. */
	static final class Clip {
		String name;
		float length;
		boolean loop = true;
		/** Plays faster or slower (1 = as made). */
		float speed = 1;
		/** How quickly this clip takes over when its state comes on (seconds; 0 = the usual BLEND). */
		float blend;
		/** Walk-like clips: play slower when barely moving, so the steps match the legs. */
		boolean stride;
		final Map<String, Channel[]> bones = new HashMap<>();

		/** Time inside the clip for a clock that has run t seconds. */
		float at(float t) {
			if (length <= 0) return t;
			if (loop) {
				float r = t % length;
				return r < 0 ? r + length : r;
			}
			return Math.min(t, length);
		}
	}

	/**
	 * A part that swings on its own: a damped spring pulled by how the player moves. The simple kind ("swing") turns
	 * round x for walking, jumping and falling and round z for turning. The full kind says per axis how each push moves
	 * it ("move", "turn", "jump", "air": {x, y, z}), can follow the clips' own motion with lag and overshoot ("follow",
	 * 0..1) and can bend against another bone's turning ("drag").
	 */
	static final class Spring {
		String bone;
		float stiffness = 70, damping = 7, inertia = 1, limit = 50, follow;
		float[] move = {1, 0, 0}, turn = {0, 0, 1}, jump = {1, 0, 0}, air = {1, 0, 0};
		/** How much of the swing reaches each axis (the simple kind's "swing"; -1 flips it). */
		float[] gain = {1, 0, 1};
		/** Bends against this bone's turning speed (degrees per degree a second, per axis), or none. */
		String dragBone;
		float[] drag = {0, 0, 0};
	}

	/** Reads a model's "animations" object; missing or broken clips are skipped. */
	static Map<String, Clip> clips(JsonElement e) {
		Map<String, Clip> out = new HashMap<>();
		if (e == null || !e.isJsonObject()) return out;
		for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
			if (!en.getValue().isJsonObject()) continue;
			try {
				Clip c = clip(en.getKey(), en.getValue().getAsJsonObject());
				if (c != null) out.put(c.name, c);
			} catch (RuntimeException ignored) {
				// a broken clip shouldn't take the cosmetic down with it
			}
		}
		return out;
	}

	private static Clip clip(String key, JsonObject o) {
		Clip c = new Clip();
		// Blockbench names them "animation.<model>.<state>"; the last part is what counts
		c.name = key.substring(key.lastIndexOf('.') + 1);
		c.length = num(o, "length", num(o, "animation_length", 0));
		JsonElement loop = o.get("loop");
		c.loop = loop == null || loop.isJsonNull() || (loop.isJsonPrimitive() && (loop.getAsJsonPrimitive().isBoolean() ? loop.getAsBoolean() : !"once".equals(loop.getAsString()) && !"hold_on_last_frame".equals(loop.getAsString())));
		c.speed = num(o, "speed", 1);
		c.blend = Math.max(0, num(o, "blend", 0));
		c.stride = o.has("stride") ? o.get("stride").getAsBoolean() : "walk".equals(c.name) || "sprint".equals(c.name);
		JsonObject bones = o.has("bones") && o.get("bones").isJsonObject() ? o.getAsJsonObject("bones") : null;
		float last = 0;
		if (bones != null) {
			for (Map.Entry<String, JsonElement> b : bones.entrySet()) {
				if (!b.getValue().isJsonObject()) continue;
				JsonObject bo = b.getValue().getAsJsonObject();
				Channel[] ch = {channel(bo.get("rotation"), 0), channel(bo.get("position"), 0), channel(bo.get("scale"), 1)};
				for (Channel x : ch) if (x != null && x.times.length > 0) last = Math.max(last, x.times[x.times.length - 1]);
				c.bones.put(b.getKey(), ch);
			}
		}
		if (c.length <= 0) c.length = last;
		return c;
	}

	/** A channel: a constant ([x, y, z] or a number) or keys ({"0.5": value, ...}). */
	static Channel channel(JsonElement e, float def) {
		if (e == null || e.isJsonNull()) return null;
		if (!e.isJsonObject()) {
			float[] v = vec(e, def);
			return new Channel(new float[] {0}, new float[][] {v}, new float[][] {v}, new byte[] {LINEAR});
		}
		List<Map.Entry<String, JsonElement>> keys = new ArrayList<>(e.getAsJsonObject().entrySet());
		int n = keys.size();
		float[] times = new float[n];
		float[][] pre = new float[n][], post = new float[n][];
		byte[] modes = new byte[n];
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
			times[i] = parse(keys.get(i).getKey());
		}
		Arrays.sort(order, (a, b) -> Float.compare(times[a], times[b]));
		float[] st = new float[n];
		for (int k = 0; k < n; k++) {
			int i = order[k];
			st[k] = times[i];
			JsonElement v = keys.get(i).getValue();
			if (v.isJsonObject()) {
				JsonObject vo = v.getAsJsonObject();
				float[] p = vo.has("post") ? vec(vo.get("post"), def) : vo.has("pre") ? vec(vo.get("pre"), def) : vec(vo.get("value"), def);
				pre[k] = vo.has("pre") ? vec(vo.get("pre"), def) : p;
				post[k] = p;
				modes[k] = mode(vo.has("lerp_mode") ? vo.get("lerp_mode").getAsString() : vo.has("ease") ? vo.get("ease").getAsString() : "linear");
			} else {
				pre[k] = post[k] = vec(v, def);
				modes[k] = LINEAR;
			}
		}
		return new Channel(st, pre, post, modes);
	}

	static byte mode(String s) {
		if ("catmullrom".equals(s) || "smooth".equals(s) || "bezier".equals(s)) return SMOOTH;
		if ("step".equals(s)) return STEP;
		return LINEAR;
	}

	private static float[] vec(JsonElement e, float def) {
		float[] v = {def, def, def};
		if (e == null || e.isJsonNull()) return v;
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (int i = 0; i < 3 && i < a.size(); i++) v[i] = val(a.get(i), def);
		} else if (e.isJsonPrimitive()) {
			float x = val(e, def);
			v[0] = v[1] = v[2] = x;
		}
		return v;
	}

	/** A number, or a Blockbench (Molang) expression that is just a number; anything else counts as the default. */
	private static float val(JsonElement e, float def) {
		if (!e.isJsonPrimitive()) return def;
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isNumber()) return p.getAsFloat();
		return parse(p.getAsString(), def);
	}

	private static float parse(String s) {
		return parse(s, 0);
	}

	private static float parse(String s, float def) {
		try {
			return Float.parseFloat(s.trim());
		} catch (RuntimeException e) {
			return def;
		}
	}

	private static float num(JsonObject o, String k, float def) {
		JsonElement e = o.get(k);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
		return val(e, def);
	}

	static List<Spring> springs(JsonElement e) {
		List<Spring> out = new ArrayList<>();
		if (e == null || !e.isJsonArray()) return out;
		for (JsonElement x : e.getAsJsonArray()) {
			if (!x.isJsonObject()) continue;
			JsonObject o = x.getAsJsonObject();
			Spring s = new Spring();
			s.bone = o.has("bone") ? o.get("bone").getAsString() : "";
			s.stiffness = Math.max(1, num(o, "stiffness", s.stiffness));
			s.damping = Math.max(0, num(o, "damping", s.damping));
			s.inertia = num(o, "inertia", s.inertia);
			s.limit = Math.max(1, num(o, "limit", s.limit));
			s.follow = clamp(num(o, "follow", 0), 0, 1);
			if (o.has("move") || o.has("turn") || o.has("jump") || o.has("air") || o.has("follow") || o.has("drag")) {
				s.move = vec3(o.get("move"));
				s.turn = vec3(o.get("turn"));
				s.jump = vec3(o.get("jump"));
				s.air = vec3(o.get("air"));
				s.gain = new float[] {1, 1, 1};
			} else if (o.has("swing") && o.get("swing").isJsonArray()) {
				JsonArray a = o.getAsJsonArray("swing");
				if (a.size() > 0) s.gain[0] = val(a.get(0), 1);
				if (a.size() > 1) s.gain[2] = val(a.get(1), 1);
			}
			if (o.has("drag") && o.get("drag").isJsonObject()) {
				JsonObject d = o.getAsJsonObject("drag");
				if (d.has("bone")) {
					s.dragBone = d.get("bone").getAsString();
					s.drag = vec3(d.get("amount"));
				}
			}
			if (!s.bone.isEmpty()) out.add(s);
		}
		return out;
	}

	/** [x, y, z] (or one number for all three), 0 where missing. */
	private static float[] vec3(JsonElement e) {
		return e == null || e.isJsonNull() ? new float[3] : vec(e, 0);
	}

	/** Which state a player is in, for picking the clip. */
	static String state(WingPose p) {
		if (p.sleeping) return "sleep";
		if (p.swimming) return "swim";
		if (p.gliding) return "glide";
		if (p.flying) return "fly";
		if (p.riding) return "ride";
		if (p.sneaking) return "sneak";
		if (!p.onGround) return "fall";
		if (p.sprinting) return "sprint";
		if (p.moving > 0.2f) return "walk";
		return "idle";
	}

	/** The clip a model plays for a state (its own, or the nearest stand-in), or null if it has none of them. */
	static Clip pick(Map<String, Clip> clips, String state) {
		String[] chain = FALLBACK.get(state);
		if (chain == null) chain = new String[] {state, "idle"};
		for (String s : chain) {
			Clip c = clips.get(s);
			if (c != null) return c;
		}
		return null;
	}

	/** How quickly one clip hands over to the next (seconds). */
	static final float BLEND = 0.2f;

	/**
	 * One player's playback of one model: which clips are on and how strongly, their clocks, and the springs. Kept
	 * per player and slot; a new item starts a new one.
	 */
	static final class Rig {
		String item;
		final Map<String, float[]> clips = new HashMap<>(); // name -> {weight, time}
		final Map<String, float[]> springs = new HashMap<>(); // bone -> {angle, speed} for x, y and z, then what it last followed
		final Map<String, float[]> track = new HashMap<>(); // bone -> {last x, y, z, speed x, y, z, frame}: for drag
		float lastMoving, yawSpeed, lastYaw, always;
		int frame;
		boolean hasYaw, onGround = true, started;
	}

	/**
	 * Advances a player's rig by dt seconds and adds this frame's pose into pose (bone -> {rx, ry, rz, px, py, pz, sx,
	 * sy, sz}; scale multiplies, the rest adds).
	 */
	static void play(Map<String, Clip> all, List<Spring> springs, Rig rig, WingPose p, float dt, Map<String, float[]> pose) {
		if (!all.isEmpty()) {
			Clip target = pick(all, state(p));
			float blend = target != null && target.blend > 0 ? target.blend : BLEND; // how fast the new clip takes over
			float k = dt <= 0 ? (rig.started ? 0 : 1) : 1 - (float) Math.exp(-dt / blend);
			rig.started = true;
			if (target != null && !rig.clips.containsKey(target.name)) rig.clips.put(target.name, new float[] {rig.clips.isEmpty() ? 1 : 0, 0});
			float total = 0;
			for (Map.Entry<String, float[]> e : rig.clips.entrySet()) {
				float[] wt = e.getValue();
				boolean on = target != null && e.getKey().equals(target.name);
				Clip c = all.get(e.getKey());
				if (c == null) {
					wt[0] = 0;
					continue;
				}
				if (on && wt[0] < 0.001f && !c.loop) wt[1] = 0; // a one-shot clip starts over each time it comes on
				wt[0] += ((on ? 1 : 0) - wt[0]) * k;
				float rate = c.speed * (c.stride ? 0.55f + 0.45f * clamp(p.moving, 0, 1) : 1);
				wt[1] += dt * rate;
				if (c.loop && c.length > 0 && wt[1] > c.length * 1000) wt[1] %= c.length;
				total += wt[0];
			}
			final String keep = target == null ? "" : target.name;
			rig.clips.entrySet().removeIf(en -> en.getValue()[0] < 0.001f && !en.getKey().equals(keep));
			float[] v = new float[3];
			for (Map.Entry<String, float[]> e : rig.clips.entrySet()) {
				float w = total > 0 ? e.getValue()[0] / total : 0;
				if (w <= 0) continue;
				apply(all.get(e.getKey()), e.getValue()[1], w, pose, v);
			}
			Clip always = all.get("always");
			if (always != null) {
				rig.always += dt * always.speed;
				if (always.loop && always.length > 0 && rig.always > always.length * 1000) rig.always %= always.length;
				apply(always, rig.always, 1, pose, v);
			}
		}
		if (!springs.isEmpty()) swing(springs, rig, p, dt, pose);
	}

	private static void apply(Clip c, float clock, float w, Map<String, float[]> pose, float[] v) {
		if (c == null) return;
		float t = c.at(clock);
		for (Map.Entry<String, Channel[]> b : c.bones.entrySet()) {
			float[] out = pose.get(b.getKey());
			if (out == null) pose.put(b.getKey(), out = base());
			Channel[] ch = b.getValue();
			if (ch[ROT] != null) {
				ch[ROT].sample(t, v);
				for (int i = 0; i < 3; i++) out[i] += v[i] * w;
			}
			if (ch[POS] != null) {
				ch[POS].sample(t, v);
				for (int i = 0; i < 3; i++) out[3 + i] += v[i] * w;
			}
			if (ch[SCALE] != null) {
				ch[SCALE].sample(t, v);
				for (int i = 0; i < 3; i++) out[6 + i] *= 1 + (v[i] - 1) * w;
			}
		}
	}

	/** A pose with nothing moved: no turn, no shift, full size. */
	static float[] base() {
		return new float[] {0, 0, 0, 0, 0, 0, 1, 1, 1};
	}

	/** Small steps keep the springs steady when the frame rate drops. */
	private static final float STEP_DT = 1 / 120f;

	/**
	 * Springs: each part is pulled towards where the player's movement would leave it (trailing behind a walk or a
	 * turn, lifted while airborne) and overshoots and settles like something hanging. Angles are in degrees.
	 */
	private static void swing(List<Spring> springs, Rig rig, WingPose p, float dt, Map<String, float[]> pose) {
		float moving = clamp(p.moving, 0, 1);
		float accel = dt > 0 ? clamp((moving - rig.lastMoving) / dt, -6, 6) : 0; // starting or stopping
		if (rig.hasYaw && dt > 0) {
			float turn = p.bodyYaw - rig.lastYaw;
			turn -= 360 * Math.round(turn / 360);
			float speed = clamp(turn / dt, -720, 720);
			rig.yawSpeed += (speed - rig.yawSpeed) * (1 - (float) Math.exp(-dt / 0.1f));
		}
		rig.lastYaw = p.bodyYaw;
		rig.hasYaw = true;
		boolean ground = p.onGround || p.swimming || p.riding;
		float kick = 0;
		if (rig.onGround && !ground) kick = -140; // jumped: the part lags down and forward
		else if (!rig.onGround && ground) kick = 220; // landed: it swings on
		rig.onGround = ground;
		rig.lastMoving = moving;
		boolean flying = p.flying || p.gliding;
		float tMove = moving * (p.sprinting ? 28 : 16), tAir = ground ? 0 : flying ? 40 : -10;
		float tTurn = clamp(-rig.yawSpeed * 0.06f, -30, 30);
		float[] target = new float[3], tv = new float[3];
		for (Spring s : springs) {
			float[] out = pose.get(s.bone);
			if (out == null) pose.put(s.bone, out = base());
			// the drag: how fast the other bone turns right now (smoothed a little)
			float[] dv = null;
			if (s.dragBone != null) {
				float[] o = pose.get(s.dragBone);
				float[] tr = rig.track.get(s.dragBone);
				if (tr == null) {
					rig.track.put(s.dragBone, tr = new float[7]);
					if (o != null) System.arraycopy(o, 0, tr, 0, 3);
					tr[6] = -1;
				}
				if (tr[6] != rig.frame) {
					tr[6] = rig.frame;
					for (int i = 0; i < 3; i++) {
						float cur = o != null ? o[i] : 0;
						float v = dt > 0 ? clamp((cur - tr[i]) / dt, -2000, 2000) : 0;
						tr[3 + i] += (v - tr[3 + i]) * (dt > 0 ? 1 - (float) Math.exp(-dt / 0.04f) : 0);
						tr[i] = cur;
					}
				}
				dv = tr;
			}
			for (int i = 0; i < 3; i++) {
				target[i] = (tMove * s.move[i] + tAir * s.air[i] + tTurn * s.turn[i]) * s.inertia + s.follow * out[i] - (dv != null ? dv[3 + i] * s.drag[i] : 0);
			}
			float[] st = rig.springs.get(s.bone);
			if (st == null) rig.springs.put(s.bone, st = new float[] {target[0], 0, target[1], 0, target[2], 0, s.follow * out[0], s.follow * out[1], s.follow * out[2]});
			for (int i = 0; i < 3; i++) st[2 * i + 1] += (kick * s.jump[i] - accel * 20 * s.move[i]) * s.inertia;
			// what it follows moves at this speed: the damping works against moving differently from it, so the part
			// keeps up with the clip and only overshoots where the clip changes direction
			for (int i = 0; i < 3; i++) {
				float f = s.follow * out[i];
				tv[i] = dt > 0 ? clamp((f - st[6 + i]) / dt, -3000, 3000) : 0;
				st[6 + i] = f;
			}
			for (float left = Math.min(dt, 0.1f); left > 1e-5f; left -= STEP_DT) {
				float h = Math.min(STEP_DT, left);
				for (int i = 0; i < 3; i++) {
					st[2 * i + 1] += (s.stiffness * (target[i] - st[2 * i]) - s.damping * (st[2 * i + 1] - tv[i])) * h;
					st[2 * i] += st[2 * i + 1] * h;
				}
			}
			for (int i = 0; i < 3; i++) {
				// at the limit (counted from what it follows) it stops dead instead of pushing on
				float rest = s.follow * out[i], off = st[2 * i] - rest;
				if (Math.abs(off) > s.limit) {
					st[2 * i] = rest + Math.signum(off) * s.limit;
					st[2 * i + 1] = 0;
				}
				out[i] += st[2 * i] * s.gain[i] - rest;
			}
		}
		rig.frame++;
	}

	static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}
}
