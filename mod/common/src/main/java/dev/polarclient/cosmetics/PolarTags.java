package dev.polarclient.cosmetics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PolarTags {
	public static final class Def {
		public final String id;
		public final String icon;
		public final int color;
		public Def(String id, String icon, int color) { this.id = id; this.icon = icon; this.color = color; }
	}

	private static final Map<String, Def> DEFS = new LinkedHashMap<>();
	static {
		reg("dev", "⌘", 0x7DD3FC);
		reg("staff", "★", 0xFBBF24);
		reg("partner", "◆", 0xC084FC);
		reg("og", "●", 0x34D399);
		reg("vip", "♦", 0xFB7185);
	}

	private static void reg(String id, String icon, int color) { DEFS.put(id, new Def(id, icon, color)); }
	public static Def get(String id) { return DEFS.get(id); }
	public static Map<String, Def> all() { return Collections.unmodifiableMap(DEFS); }
	private PolarTags() {}
}
