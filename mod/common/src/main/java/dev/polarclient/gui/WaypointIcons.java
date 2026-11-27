package dev.polarclient.gui;

import dev.polarclient.gui.ui.Icon;

/** The glyph for each waypoint icon name (see Waypoint#ICONS). */
public final class WaypointIcons {
	private WaypointIcons() {
	}

	public static String glyph(String icon) {
		if (icon == null) return Icon.BOOKMARK;
		switch (icon) {
			case "home": return Icon.HOME;
			case "star": return Icon.STAR;
			case "mine": return Icon.STACKS;
			case "portal": return Icon.FLARE;
			case "heart": return Icon.FAVORITE;
			case "chest": return Icon.INVENTORY;
			case "gem": return Icon.TOKEN;
			case "tree": return Icon.FOREST;
			case "village": return Icon.LANDSCAPE;
			case "skull": return Icon.SKULL;
			default: return Icon.BOOKMARK;
		}
	}
}
