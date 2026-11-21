package dev.polarclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * An aura: particles around a player, simulated here (the same rules as the launcher's wardrobe viewer) and drawn as
 * sprites that face the camera (or lie flat on the ground). Positions are world coordinates, so particles that fall
 * or drift stay behind as you walk; swirling ones circle wherever you are. An aura can have several layers, each its
 * own emitter with its own settings, drawn from the same sprite strip.
 */
final class Aura {
	/** The settings from the catalogue (ranges are {min, max}). */
	static final class Settings {
		float rate = 6;
		float[] life = {1.5f, 2.5f}, size = {0.08f, 0.14f}, radius = {0.3f, 0.7f}, height = {0, 1.9f};
		float[] vx = {0, 0}, vy = {0, 0}, vz = {0, 0}, spin = {0, 0}, swirl = {0, 0};
		float gravity;
		boolean shrink, glow, upright;
		int[] colors = {0xFFFFFFFF};
		int frames = 1;
		/** More particles for each block walked (a trail left behind; only while on the ground). */
		float perBlock;
		/** Lying flat on the ground instead of facing the camera (ripples, footprints). */
		boolean flat;
		/** Play the layer's frames in order over each particle's life (a flipbook) instead of one picked at random. */
		boolean anim;
		/** Mirror the sprite so it faces the way it moves (fish). */
		boolean face;
		/** Size at the end of its life, times the start (0 = as shrink says). */
		float grow;
		/** Fading in and out, as shares of the life. */
		float fadeIn = 0.2f, fadeOut = 1 / 3f;
		/** Opacity (0..1). */
		float alpha = 1;
		/** Swaying side to side: {blocks, times a second}. */
		float[] wobble = {0, 0};
		/** The frames of the strip this layer uses: first, and how many. */
		int frameStart, frameCount;
		/** Several emitters in one aura; each layer starts from the aura's own settings. */
		final List<Settings> layers = new ArrayList<>();

		Settings(JsonObject o) {
			this(o, null);
		}

		private Settings(JsonObject o, Settings base) {
			if (base != null) copy(base);
			rate = f(o, "rate", rate);
			life = range(o, "life", life);
			size = range(o, "size", size);
			radius = range(o, "radius", radius);
			height = range(o, "height", height);
			vx = range(o, "vx", vx);
			vy = range(o, "vy", vy);
			vz = range(o, "vz", vz);
			spin = range(o, "spin", spin);
			swirl = range(o, "swirl", swirl);
			gravity = f(o, "gravity", gravity);
			shrink = b(o, "shrink", shrink);
			glow = b(o, "glow", glow);
			upright = b(o, "upright", upright); // flames: never turned
			frames = Math.max(1, (int) f(o, "frames", frames));
			perBlock = Math.max(0, f(o, "perBlock", perBlock));
			flat = b(o, "flat", flat);
			anim = b(o, "anim", anim);
			face = b(o, "face", face);
			grow = Math.max(0, f(o, "grow", grow));
			alpha = Math.max(0, Math.min(1, f(o, "alpha", alpha)));
			if (o.has("fade")) {
				float[] fd = range(o, "fade", new float[] {fadeIn, fadeOut});
				fadeIn = Math.max(0.001f, fd[0]);
				fadeOut = Math.max(0.001f, fd[1]);
			}
			wobble = range(o, "wobble", wobble);
			if (o.has("frame")) {
				float[] fr = range(o, "frame", new float[] {0, frames});
				frameStart = (int) fr[0];
				frameCount = (int) fr[1];
			}
			if (frameStart < 0 || frameStart >= frames) frameStart = 0;
			if (frameCount <= 0 || frameStart + frameCount > frames) frameCount = frames - frameStart;
			if (o.has("colors") && o.get("colors").isJsonArray()) {
				JsonArray a = o.getAsJsonArray("colors");
				List<Integer> list = new ArrayList<>();
				for (JsonElement e : a) {
					int c = Cosmetics.parseColor(e.getAsString());
					if (c != 0) list.add(c);
				}
				if (!list.isEmpty()) {
					colors = new int[list.size()];
					for (int i = 0; i < colors.length; i++) colors[i] = list.get(i);
				}
			}
			if (base == null && o.has("layers") && o.get("layers").isJsonArray()) {
				for (JsonElement e : o.getAsJsonArray("layers")) {
					if (e.isJsonObject()) layers.add(new Settings(e.getAsJsonObject(), this));
				}
			}
		}

		private void copy(Settings b) {
			rate = b.rate;
			life = b.life;
			size = b.size;
			radius = b.radius;
			height = b.height;
			vx = b.vx;
			vy = b.vy;
			vz = b.vz;
			spin = b.spin;
			swirl = b.swirl;
			gravity = b.gravity;
			shrink = b.shrink;
			glow = b.glow;
			upright = b.upright;
			colors = b.colors;
			frames = b.frames;
			perBlock = b.perBlock;
			flat = b.flat;
			anim = b.anim;
			face = b.face;
			grow = b.grow;
			fadeIn = b.fadeIn;
			fadeOut = b.fadeOut;
			alpha = b.alpha;
			wobble = b.wobble;
		}

		/** True if any layer (or the aura itself) glows, or doesn't: which batches it needs. */
		boolean any(boolean wantGlow) {
			if (layers.isEmpty()) return glow == wantGlow;
			for (Settings l : layers) if (l.glow == wantGlow) return true;
			return false;
		}

		private static float f(JsonObject o, String k, float def) {
			JsonElement e = o.get(k);
			return e == null || e.isJsonNull() ? def : e.getAsFloat();
		}

		private static boolean b(JsonObject o, String k, boolean def) {
			JsonElement e = o.get(k);
			return e == null || e.isJsonNull() ? def : e.getAsBoolean();
		}

		private static float[] range(JsonObject o, String k, float[] def) {
			JsonElement e = o.get(k);
			if (e == null || e.isJsonNull()) return def;
			if (e.isJsonArray()) {
				JsonArray a = e.getAsJsonArray();
				return new float[] {a.get(0).getAsFloat(), a.get(a.size() - 1).getAsFloat()};
			}
			float v = e.getAsFloat();
			return new float[] {v, v};
		}
	}

	private static final class Particle {
		double x, y, z;
		float vx, vy, vz, age, life, size, spin, rot, swirl, ang, rad, height, phase, wdir;
		int color, frame;
	}

	private static final int MAX = 160;
	private final Random random = new Random();
	private final List<Particle> particles = new ArrayList<>();
	/** One for each layer of a layered aura. */
	private final List<Aura> layers = new ArrayList<>();
	private float spawn;
	private double lastX, lastY, lastZ;
	private boolean hasLast;
	private float vertical;

	private float rand(float[] r) {
		return r[0] + random.nextFloat() * (r[1] - r[0]);
	}

	/** Moves the particles on by dt seconds; (cx, cy, cz) is where the player's feet are now. */
	void step(Settings s, float dt, double cx, double cy, double cz) {
		if (!s.layers.isEmpty()) {
			while (layers.size() < s.layers.size()) layers.add(new Aura());
			for (int i = 0; i < s.layers.size(); i++) layers.get(i).step(s.layers.get(i), dt, cx, cy, cz);
			return;
		}
		// how far the player walked since last time: trails spawn by the block (not while jumping or falling,
		// and not across a teleport)
		double moved = 0;
		if (hasLast && dt > 0) {
			double dx = cx - lastX, dz = cz - lastZ;
			moved = Math.sqrt(dx * dx + dz * dz);
			vertical += ((float) Math.abs(cy - lastY) / dt - vertical) * Math.min(1, dt * 12);
			if (moved > 3) moved = 0;
		}
		lastX = cx;
		lastY = cy;
		lastZ = cz;
		hasLast = true;
		spawn += dt * s.rate;
		if (vertical < 0.6f) spawn += (float) moved * s.perBlock;
		while (spawn >= 1 && particles.size() < MAX) {
			spawn -= 1;
			Particle p = new Particle();
			p.ang = random.nextFloat() * (float) (Math.PI * 2);
			p.rad = rand(s.radius);
			p.height = rand(s.height);
			p.x = cx + Math.cos(p.ang) * p.rad;
			p.y = cy + p.height;
			p.z = cz + Math.sin(p.ang) * p.rad;
			p.vx = rand(s.vx);
			p.vy = rand(s.vy);
			p.vz = rand(s.vz);
			p.life = rand(s.life);
			p.size = rand(s.size);
			p.spin = rand(s.spin) * (float) (Math.PI / 180);
			p.rot = s.upright ? 0 : random.nextFloat() * 6.28f;
			p.swirl = rand(s.swirl);
			p.color = s.colors[random.nextInt(s.colors.length)];
			p.frame = s.frameStart + random.nextInt(s.frameCount);
			p.phase = random.nextFloat() * 6.28f;
			p.wdir = random.nextFloat() * 6.28f;
			particles.add(p);
		}
		if (spawn > 1) spawn = 1;
		for (Iterator<Particle> it = particles.iterator(); it.hasNext();) {
			Particle p = it.next();
			p.age += dt;
			if (p.age >= p.life) {
				it.remove();
				continue;
			}
			p.vy -= s.gravity * dt;
			if (p.swirl != 0) {
				p.ang += p.swirl * dt;
				p.height += p.vy * dt;
				p.x = cx + Math.cos(p.ang) * p.rad;
				p.z = cz + Math.sin(p.ang) * p.rad;
				p.y = cy + p.height;
			} else {
				p.x += p.vx * dt;
				p.y += p.vy * dt;
				p.z += p.vz * dt;
			}
			p.rot += p.spin * dt;
		}
	}

	boolean empty() {
		if (!layers.isEmpty()) {
			for (Aura a : layers) if (!a.empty()) return false;
			return true;
		}
		return particles.isEmpty();
	}

	/**
	 * Sprites, positioned relative to (ox, oy, oz) (the player's render position): the renderer draws them from there
	 * without turning. camYaw/camPitch: where the camera looks, in degrees (Minecraft's convention). Glowing layers go
	 * into glow, the rest into other.
	 */
	void emit(Settings s, double ox, double oy, double oz, float camYaw, float camPitch, RenderBatch glow, RenderBatch other) {
		if (!s.layers.isEmpty()) {
			for (int i = 0; i < s.layers.size() && i < layers.size(); i++) layers.get(i).emit(s.layers.get(i), ox, oy, oz, camYaw, camPitch, glow, other);
			return;
		}
		RenderBatch out = s.glow ? glow : other;
		if (out == null) return;
		double yaw = Math.toRadians(camYaw), pitch = Math.toRadians(camPitch);
		float rx = (float) -Math.cos(yaw), ry = 0, rz = (float) -Math.sin(yaw);
		float fx = (float) (-Math.sin(yaw) * Math.cos(pitch)), fy = (float) -Math.sin(pitch), fz = (float) (Math.cos(yaw) * Math.cos(pitch));
		float ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
		float fw = 1f / s.frames;
		for (Particle p : particles) {
			float t = p.age / p.life;
			float alpha = Math.min(1, t / s.fadeIn) * Math.min(1, (1 - t) / s.fadeOut) * s.alpha;
			float size = p.size * (s.grow > 0 ? 1 + (s.grow - 1) * t : s.shrink ? 1 - t * 0.6f : 1);
			float cr = (float) Math.cos(p.rot), sr = (float) Math.sin(p.rot);
			float wob = s.wobble[0] == 0 ? 0 : s.wobble[0] * (float) Math.sin(p.phase + 6.2832f * s.wobble[1] * p.age);
			float px = (float) (p.x - ox) + wob * (float) Math.cos(p.wdir), py = (float) (p.y - oy), pz = (float) (p.z - oz) + wob * (float) Math.sin(p.wdir);
			int a = Math.round(alpha * ((p.color >>> 24) & 255));
			int color;
			if (s.glow) { // added light: fade by dimming
				int r = Math.round(((p.color >> 16) & 255) * alpha), g = Math.round(((p.color >> 8) & 255) * alpha), b = Math.round((p.color & 255) * alpha);
				color = 0xFF000000 | r << 16 | g << 8 | b;
			} else {
				color = a << 24 | (p.color & 0xFFFFFF);
			}
			int frame = s.anim ? s.frameStart + Math.min(s.frameCount - 1, (int) (t * s.frameCount)) : p.frame;
			float u0 = frame * fw, u1 = u0 + fw;
			if (s.face) {
				// moving left on screen: mirror it, so it faces where it goes
				float mvx = p.swirl != 0 ? -(float) Math.sin(p.ang) * p.swirl : p.vx, mvz = p.swirl != 0 ? (float) Math.cos(p.ang) * p.swirl : p.vz;
				if (mvx * rx + mvz * rz < 0) {
					float t0 = u0;
					u0 = u1;
					u1 = t0;
				}
			}
			float[][] corners = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
			float[] us = {u0, u1, u1, u0}, vs = {0, 0, 1, 1};
			for (int k = 0; k < 4; k++) {
				float dx = corners[k][0], dy = corners[k][1];
				float qx = dx * cr - dy * sr, qy = dx * sr + dy * cr;
				if (s.flat) { // lying on the ground, facing up (wound like a sprite seen from straight above)
					out.vertex(px - qx * size, py, pz + qy * size, us[k], vs[k], 0, 1, 0, color);
				} else {
					out.vertex(px + (rx * qx + ux * qy) * size, py + (ry * qx + uy * qy) * size, pz + (rz * qx + uz * qy) * size, us[k], vs[k], -fx, -fy, -fz, color);
				}
			}
		}
	}
}
