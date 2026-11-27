package dev.polarclient.gui;

import dev.polarclient.platform.Platform;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the texts and flags a vanilla screen holds (a death cause, a disconnect reason) by type rather than by name,
 * so it works on every Minecraft version, obfuscated or not.
 */
final class ScreenText {
	private ScreenText() {
	}

	/** Every text field of the screen's own class (and one level into records it holds), in declaration order. */
	static List<String> texts(Object screen) {
		List<String> out = new ArrayList<String>();
		if (screen == null) return out;
		Platform p = Platform.get();
		for (Field f : screen.getClass().getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
			try {
				f.setAccessible(true);
				Object v = f.get(screen);
				if (v == null || v instanceof String) continue;
				String t = p.plain(v);
				if (t != null) {
					out.add(t);
					continue;
				}
				// e.g. DisconnectionDetails(reason, report, ...): look one level in, skipping screens and the like
				if (v.getClass().getName().startsWith("java.")) continue;
				// never into another screen (the one to go back to): its texts aren't this screen's
				Class<?> up = screen.getClass().getSuperclass();
				if (up != null && up != Object.class && up.isInstance(v)) continue;
				for (Field g : v.getClass().getDeclaredFields()) {
					if (Modifier.isStatic(g.getModifiers()) || g.getType().isPrimitive()) continue;
					try {
						g.setAccessible(true);
						String t2 = p.plain(g.get(v));
						if (t2 != null) out.add(t2);
					} catch (Exception ignored) {
						// skip it
					}
				}
			} catch (Exception ignored) {
				// skip it
			}
		}
		return out;
	}

	/** The first boolean field of the screen's own class (DeathScreen's "hardcore"), or false. */
	static boolean firstFlag(Object screen) {
		if (screen == null) return false;
		for (Field f : screen.getClass().getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) continue;
			try {
				f.setAccessible(true);
				return f.getBoolean(screen);
			} catch (Exception ignored) {
				return false;
			}
		}
		return false;
	}
}
