package dev.polarclient.gui;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;

import java.time.LocalDate;
import java.time.Month;

/**
 * The Christmas dress for the main menu: a voxel tree with live lights and a star (click it), presents, a snowman,
 * light strings along both shores, Santa's sleigh across the sky every ten seconds, a garland along the top edge,
 * a Santa hat on the logo, ice-glass buttons with snow, and the shortcuts hung as baubles from a pine bough.
 * The art is baked into textures/gui/christmas; this class only places it and adds the light.
 * On from December 1 to January 6 ("Auto"), or always / never via Polar Settings > Seasonal main menu.
 */
public final class Christmas {
	private Christmas() {
	}

	static final String TREE = tex("tree"), PRESENTS = tex("presents"), SNOWMAN = tex("snowman"), HAT = tex("hat"),
		BOUGH = tex("bough"), BOW = tex("bow"), HOLLY = tex("holly"), RAY = tex("ray");
	static final String[] SLEIGH = {tex("sleigh0"), tex("sleigh1")};
	static final String[] BAUBLE = {tex("bauble0"), tex("bauble1"), tex("bauble2"), tex("bauble3")};
	static final String[] BULB = {tex("bulb0"), tex("bulb1"), tex("bulb2"), tex("bulb3")};
	/** Button sprites: [button][0 = idle, 1 = hover]; button 0 is the red primary one. */
	static final String[][] BUTTON = {
		{tex("btn_primary0"), tex("btn_primary1")},
		{tex("btn_glass0_0"), tex("btn_glass0_1")},
		{tex("btn_glass1_0"), tex("btn_glass1_1")}};

	static final int[] BULBS = {0xFFFF4B5E, 0xFFFFC94D, 0xFF4DE38A, 0xFF6FD3FF};
	static final int[] ORNAMENT = {0xFFE8384F, 0xFFF5C451, 0xFF3E9A5E, 0xFF5AD8FF};

	private static String tex(String name) {
		return "textures/gui/christmas/" + name + ".png";
	}

	private static final long START = System.nanoTime();
	private static final float SLEIGH_T = 8, SLEIGH_EVERY = 10, SLEIGH_FIRST = 2;
	private static float starBurst = -99;
	/** Where the tree's star was last drawn, in GUI px (for the click); radius 0 = not drawn. */
	private static float starX, starY, starR;
	/** The star's position in scene texture px, from the last near-layer draw. */
	static float starTexX, starTexY;

	/** Whether the menu wears its Christmas dress right now. */
	public static boolean active() {
		String mode = Polar.modules() == null ? "Auto" : Polar.modules().client.seasonal.get();
		if ("Always".equals(mode)) return true;
		if ("Off".equals(mode)) return false;
		LocalDate d = LocalDate.now();
		return d.getMonth() == Month.DECEMBER || (d.getMonth() == Month.JANUARY && d.getDayOfMonth() <= 6);
	}

	static float time() {
		return (System.nanoTime() - START) / 1e9f;
	}

	// ------------------------------------------------------------------ drawing helpers

	/** A filled rectangle with float position and size (any transform). */
	static void rect(Gfx g, float x, float y, float w, float h, int argb) {
		if ((argb >>> 24) == 0) return;
		g.push();
		g.translate(x, y);
		g.scale(w, h);
		g.fill(0, 0, 1, 1, argb);
		g.pop();
	}

	/** A straight line of the given width from (x0, y0) to (x1, y1). */
	static void line(Gfx g, float x0, float y0, float x1, float y1, float width, int argb) {
		float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len <= 0) return;
		g.push();
		g.translate(x0, y0);
		g.rotate((float) Math.toDegrees(Math.atan2(dy, dx)));
		rect(g, 0, -width / 2, len + width * 0.3f, width, argb);
		g.pop();
	}

	/** A sprite whose drawing box (w x h) has its anchor (ax, ay) at (x, y), scaled by s. */
	static void sprite(Gfx g, String tex, float x, float y, float w, float h, float ax, float ay, float s, int argb) {
		g.blit(tex, x - ax * s, y - ay * s, w * s, h * s, 0, 0, 1, 1, argb);
	}

	private static float clamp(float v) {
		return Math.max(0, Math.min(1, v));
	}

	private static float lerp(float a, float b, float k) {
		return a + (b - a) * k;
	}

	// ------------------------------------------------------------------ background, near layer (texture px of the 1920x1080 scene)

	private static final float TREE_X = 205, TREE_BASE = 858, PRESENTS_X = 262, PRESENTS_BASE = 878, SNOWMAN_X = 1738, SNOWMAN_BASE = 856;

	/** Light strings, tree, presents and snowman on the shores. Called inside the near layer's transform. */
	static void drawNear(Gfx g, float t) {
		lightString(g, 4, 786, 300, 748, 16, 11, t, 7, 0);
		lightString(g, 300, 748, 545, 712, 12, 9, t, 6, 11);
		lightString(g, 1378, 708, 1640, 748, 12, 9, t, 6, 20);
		lightString(g, 1640, 748, 1918, 790, 16, 10, t, 7, 29);

		float burst = t - starBurst;
		float ts = 500 / ChristmasArt.TREE_H;
		float tx = TREE_X - ChristmasArt.TREE_BASE[0] * ts, ty = TREE_BASE - ChristmasArt.TREE_BASE[1] * ts;
		MenuBackground.glow(g, TREE_X, TREE_BASE - 230, 260, 300, Theme.alpha(0xFFFFB45A, 0.07f + 0.02f * (float) Math.sin(t * 1.3)));
		g.blit(TREE, tx, ty, ChristmasArt.TREE_W * ts, ChristmasArt.TREE_H * ts);
		sprite(g, PRESENTS, PRESENTS_X, PRESENTS_BASE, ChristmasArt.PRESENTS_W, ChristmasArt.PRESENTS_H,
			ChristmasArt.PRESENTS_BASE[0], ChristmasArt.PRESENTS_BASE[1], 0.46f, 0xFFFFFFFF);
		sprite(g, SNOWMAN, SNOWMAN_X, SNOWMAN_BASE, ChristmasArt.SNOWMAN_W, ChristmasArt.SNOWMAN_H,
			ChristmasArt.SNOWMAN_BASE[0], ChristmasArt.SNOWMAN_BASE[1], 0.62f, 0xFFFFFFFF);

		// live light over the baked bulbs: twinkle and bloom (all on at once while the star bursts)
		float[][] bulbs = ChristmasArt.TREE_BULBS;
		for (int i = 0; i < bulbs.length; i++) {
			float tw = burst < 1.2f ? 1 : 0.35f + 0.65f * Math.max(0, (float) Math.sin(t * 2.2 + i * 2.1) * 0.5f + 0.5f * (float) Math.sin(t * 3.7 + i * 0.9));
			float bx = tx + bulbs[i][0] * ts, by = ty + bulbs[i][1] * ts;
			MenuBackground.glow(g, bx, by, 34, 34, Theme.alpha(BULBS[(int) bulbs[i][2] & 3], 0.36f * tw));
			MenuBackground.glow(g, bx, by, 8, 8, Theme.alpha(0xFFFFFFFF, 0.25f * tw));
		}
		float sx = tx + ChristmasArt.TREE_STAR[0] * ts, sy = ty + ChristmasArt.TREE_STAR[1] * ts;
		float pulse = 0.85f + 0.15f * (float) Math.sin(t * 2.2) + (burst < 1.5f ? (1 - burst / 1.5f) * 1.6f : 0);
		MenuBackground.glow(g, sx, sy, 90 * pulse, 90 * pulse, Theme.alpha(0xFFFFC14D, 0.32f));
		MenuBackground.glow(g, sx, sy, 26, 26, Theme.alpha(0xFFFFF0C0, 0.55f));
		g.push();
		g.translate(sx, sy);
		g.rotate((float) Math.toDegrees(t * 0.15));
		for (int r = 0; r < 6; r++) {
			g.rotate(60);
			g.blit(RAY, -3, -120 * pulse, 6, 120 * pulse, 0, 0, 1, 1, 0x59FFD678);
		}
		g.pop();
		if (burst >= 0 && burst < 2.2f) {
			float a = clamp(1 - burst / 2.2f);
			for (int i = 0; i < 30; i++) {
				double ang = i * 2.399;
				float d = (130 + (i * 37 % 100)) * burst;
				float gx = sx + (float) Math.cos(ang) * d, gy = sy + (float) Math.sin(ang) * d * 0.8f + 70 * burst * burst;
				MenuBackground.glow(g, gx, gy, 14, 14, Theme.alpha(0xFFFFC14D, 0.6f * a));
				MenuBackground.glow(g, gx, gy, 4, 4, Theme.alpha(0xFFFFFFFF, 0.8f * a));
			}
		}
		starTexX = sx;
		starTexY = sy;
	}

	/** MenuBackground tells where the star landed on screen after drawing the near layer. */
	static void starOnScreen(float x, float y, float radius) {
		starX = x;
		starY = y;
		starR = radius;
	}

	/** Lights up the tree when its star is clicked. */
	public static boolean click(double mx, double my) {
		if (starR <= 0) return false;
		double dx = mx - starX, dy = my - starY;
		if (dx * dx + dy * dy > starR * starR) return false;
		starBurst = time();
		return true;
	}

	/** A string of lights hanging between two points, chasing. */
	private static void lightString(Gfx g, float x0, float y0, float x1, float y1, float sag, int n, float t, float size, int off) {
		float px = x0, py = y0;
		for (int k = 1; k <= 24; k++) {
			float f = k / 24f, x = lerp(x0, x1, f), y = lerp(y0, y1, f) + sag * 4 * f * (1 - f);
			line(g, px, py, x, y, 2, 0xD9142032);
			px = x;
			py = y;
		}
		for (int k = 0; k < n; k++) {
			float f = (k + 0.5f) / n, x = lerp(x0, x1, f), y = lerp(y0, y1, f) + sag * 4 * f * (1 - f) + 5;
			bulb(g, x, y, k + off, t, size);
		}
	}

	private static void bulb(Gfx g, float x, float y, int i, float t, float size) {
		int c = BULBS[i & 3];
		float tw = 0.55f + 0.45f * (float) (Math.sin(t * 3.1 + i * 1.9) * Math.sin(t * 1.7 + i * 0.7));
		float ch = 0.35f + 0.65f * Math.max(0, (float) Math.sin(t * 4 - i * 0.9));
		float a = Math.max(0.15f, Math.min(1, tw * ch));
		MenuBackground.glow(g, x, y, size * 5, size * 5, Theme.alpha(c, 0.42f * a));
		rect(g, x - size / 2, y - size / 2, size, size, Theme.alpha(c, 0.55f + 0.45f * a));
		rect(g, x - size / 2, y - size / 2, size / 2, size / 2, Theme.alpha(0xFFFFFFFF, 0.55f * a));
	}

	// ------------------------------------------------------------------ background, far layer: Santa's sleigh

	/** Santa crosses the sky right to left, lowest between the logo and the buttons. */
	private static float[] sleighPath(float p) {
		float e = (p - 0.5f) * 2;
		return new float[]{lerp(2250, -420, p), 398 - 70 * e * e};
	}

	static void drawSleigh(Gfx g, float t) {
		if (t < SLEIGH_FIRST) return;
		float age = (t - SLEIGH_FIRST) % SLEIGH_EVERY;
		if (age > SLEIGH_T) return;
		float p = age / SLEIGH_T;
		float bob = (float) Math.sin(age * 2.6) * 4;
		float[] pos = sleighPath(p), next = sleighPath(Math.min(1, p + 0.01f));
		float x = pos[0], y = pos[1] + bob;
		double tilt = Math.atan2(next[1] - pos[1], next[0] - x) + Math.PI + Math.cos(age * 2.6) * 0.04;
		float scale = 0.5f, sw = ChristmasArt.SLEIGH_W * scale, sh = ChristmasArt.SLEIGH_H * scale;
		// magic dust from the runners: specks dropped at earlier moments, drifting down and fading
		for (int i = 1; i < 110; i++) {
			float te = age - i * 0.03f;
			if (te < 0) break;
			float[] e = sleighPath(te / SLEIGH_T);
			float dtt = age - te, a = clamp(1 - dtt / 3.2f);
			float jx = (float) Math.sin(i * 12.9898) * 14, jy = (float) Math.cos(i * 78.233) * 9;
			float gx = e[0] + sw * 0.36f + jx, gy = e[1] + (float) Math.sin(te * 2.6) * 4 + sh * 0.18f + jy + 22 * dtt * dtt;
			int c = i % 3 == 0 ? 0xFF9FE3FF : 0xFFFFD36B;
			MenuBackground.glow(g, gx, gy, 11, 11, Theme.alpha(c, 0.6f * a));
			MenuBackground.glow(g, gx, gy, 2.5f, 2.5f, Theme.alpha(0xFFFFFFFF, 0.5f * a));
			if (i % 5 == 0) {
				float tw = 0.5f + 0.5f * (float) Math.sin(age * 18 + i), len = 7 * tw * a;
				int col = Theme.alpha(0xFFFFF6D8, a);
				rect(g, gx - len, gy - 0.7f, len * 2, 1.4f, col);
				rect(g, gx - 0.7f, gy - len, 1.4f, len * 2, col);
			}
		}
		g.push();
		g.translate(x, y);
		g.rotate((float) Math.toDegrees(tilt));
		g.blit(SLEIGH[(int) (age * 6) % 2], -sw / 2, -sh / 2, sw, sh);
		float np = 0.8f + 0.2f * (float) Math.sin(age * 6);
		float nx = -sw / 2 + ChristmasArt.SLEIGH_NOSE[0] * scale, ny = -sh / 2 + ChristmasArt.SLEIGH_NOSE[1] * scale;
		MenuBackground.glow(g, nx, ny, 22 * np, 22 * np, Theme.alpha(0xFFFF2A3E, 0.65f));
		MenuBackground.glow(g, nx, ny, 4, 4, Theme.alpha(0xFFFFFFFF, 0.8f));
		MenuBackground.glow(g, -sw / 2 + ChristmasArt.SLEIGH_LANTERN[0] * scale, -sh / 2 + ChristmasArt.SLEIGH_LANTERN[1] * scale, 24, 24,
			Theme.alpha(0xFFFFB050, 0.5f));
		g.pop();
	}

	// ------------------------------------------------------------------ screen space

	/** Three swags of lights hanging from the top edge, swaying a little. */
	static void drawGarland(Gfx g, int w, float t) {
		int swags = 3, n = 12;
		for (int s = 0; s < swags; s++) {
			float x0 = w * s / (float) swags - 6, x1 = w * (s + 1) / (float) swags + 6;
			float sag = 20 + 4 * (float) Math.sin(t * 0.9 + s * 1.7);
			float px = x0, py = -2;
			for (int k = 1; k <= 30; k++) {
				float f = k / 30f, x = lerp(x0, x1, f), y = -2 + sag * 4 * f * (1 - f);
				line(g, px, py, x, y, 1.1f, 0xE60A121E);
				px = x;
				py = y;
			}
			for (int k = 1; k < n; k++) {
				float f = k / (float) n, x = lerp(x0, x1, f), y = -2 + sag * 4 * f * (1 - f) + 3.2f;
				int i = s * n + k, c = BULBS[i & 3];
				float a = 0.35f + 0.65f * Math.max(0, (float) Math.sin(t * 2.6 - i * 0.55));
				g.blit(BULB[i & 3], x - 3, y - 4.6f, 6, 8, 0, 0, 1, 1, Theme.alpha(0xFFFFFFFF, 0.7f + 0.3f * a));
				Ui.glow(g, x, y + 0.5f, 11, 11, Theme.alpha(c, 0.4f * a));
				Ui.glow(g, x, y, 3, 3, Theme.alpha(0xFFFFFFFF, 0.5f * a));
			}
		}
	}

	/** A Santa hat on the logo's P, swaying from its brim. */
	static void drawLogoHat(Gfx g, float x, float y, float lw, float lh, float t) {
		float[] box = ChristmasArt.HAT_BOX;
		float sc = lw * 0.2f / (box[2] - box[0]);
		g.push();
		g.translate(x + lw * 0.125f, y + lh * 0.19f);
		g.rotate((float) Math.toDegrees(-0.2 + 0.03 * Math.sin(t * 1.4)));
		g.blit(HAT, -(box[0] + (box[2] - box[0]) * 0.42f) * sc, -(box[3] - (box[3] - box[1]) * 0.12f) * sc,
			ChristmasArt.HAT_W * sc, ChristmasArt.HAT_H * sc);
		g.pop();
	}

	static void holly(Gfx g, float x, float y, float s, float t) {
		Ui.glow(g, x, y - s, 4 * s, 4 * s, Theme.alpha(0xFFFF3B4E, 0.35f + 0.15f * (float) Math.sin(t * 2)));
		g.blit(HOLLY, x - 8 * s, y - 4 * s, 16 * s, 8 * s);
	}

	/** The hat tipped on the account card's head. */
	static void accountHat(Gfx g, float cardX, float cardY) {
		g.push();
		g.translate(cardX + 2, cardY + 1);
		g.rotate((float) Math.toDegrees(-0.35));
		g.blit(HAT, -4, -12, 22, 20);
		g.pop();
	}

	/** One of the menu's three buttons in ice glass, cross-fading to its hover art. */
	static void button(Gfx g, int index, float x, float y, float hover) {
		String[] pair = BUTTON[Math.min(index, 2)];
		float bx = x - ChristmasArt.BTN_OX, by = y - ChristmasArt.BTN_OY;
		g.blit(pair[0], bx, by, ChristmasArt.BTN_W, ChristmasArt.BTN_H);
		if (hover > 0.01f) g.blit(pair[1], bx, by, ChristmasArt.BTN_W, ChristmasArt.BTN_H, 0, 0, 1, 1, Theme.alpha(0xFFFFFFFF, hover));
	}

	/** A point on the bough's spine, f in 0..1, in GUI px inside the bough sprite. */
	static float[] spine(float f) {
		float[][] s = ChristmasArt.BOUGH_SPINE;
		float p = Math.max(0, Math.min(1, f)) * (s.length - 1);
		int i = Math.min(s.length - 2, (int) p);
		float k = p - i;
		return new float[]{lerp(s[i][0], s[i + 1][0], k), lerp(s[i][1], s[i + 1][1], k)};
	}

	/** The pine bough the shortcuts hang from, with warm micro-lights wound through it. */
	static void bough(Gfx g, float bx, float by, float t) {
		g.blit(BOUGH, bx, by, ChristmasArt.BOUGH_W, ChristmasArt.BOUGH_H);
		for (int i = 0; i < 9; i++) {
			float[] p = spine(0.06f + i * 0.11f);
			float a = 0.35f + 0.65f * Math.max(0, (float) Math.sin(t * 2.4 - i * 1.3));
			Ui.glow(g, bx + p[0], by + p[1] - 1, 7, 7, Theme.alpha(0xFFFFC27A, 0.45f * a));
			Ui.glow(g, bx + p[0], by + p[1] - 1, 1.6f, 1.6f, Theme.alpha(0xFFFFFFFF, 0.9f * a));
		}
	}

	static void bow(Gfx g, float x, float y, float s) {
		g.blit(BOW, x - 8 * s, y - 4 * s, 16 * s, 14 * s);
	}
}
