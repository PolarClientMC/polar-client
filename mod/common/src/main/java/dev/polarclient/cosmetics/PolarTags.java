package dev.polarclient.cosmetics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Polar name tags: the badges shown after a player's name (above their head and in the tab list), given by admins.
 * Each is drawn from the polarclient:tags font, made by tools/cosmetics/tags.py together with the list of tags
 * ({@link PolarTagList}): a row of animation frames per tag, so the shine can sweep across it.
 */
public final class PolarTags {
	public static final class Def {
		public final String id, label, anim;
		/** The first glyph of the tag's frames in the polarclient:tags font. */
		public final int glyph;
		public final int frames;
		/** Its main colour (for places that can't draw the badge, and older text-only spots). */
		public final int color;
		/** A plain fallback symbol. */
		public final String icon = "◆";

		public Def(String id, String label, int glyph, int frames, String anim, int color) {
			this.id = id;
			this.label = label;
			this.glyph = glyph;
			this.frames = frames;
			this.anim = anim;
			this.color = color;
		}

		/**
		 * The glyph to draw now. "shine": a shine sweeps across every 3.2 s, then it rests on its last frame; "blink"
		 * and "rainbow" loop all the time. seed spreads players out so their tags don't all flash together.
		 */
		public String frame(long now, int seed) {
			int f;
			long t = now + (seed & 0xFFFF) * 37L;
			if ("shine".equals(anim)) {
				long c = Math.floorMod(t, 3200L);
				f = c < (frames / 2) * 70L ? (int) (c / 70) : frames - 1;
			} else {
				f = (int) Math.floorMod(t / 90, (long) frames);
			}
			return String.valueOf((char) (glyph + f));
		}
	}

	private static final Map<String, Def> DEFS = new LinkedHashMap<>();

	static {
		PolarTagList.register();
	}

	static void add(Def d) {
		DEFS.put(d.id, d);
	}

	public static Def get(String id) {
		return DEFS.get(id);
	}

	public static Map<String, Def> all() {
		return Collections.unmodifiableMap(DEFS);
	}

	private PolarTags() {
	}
}
