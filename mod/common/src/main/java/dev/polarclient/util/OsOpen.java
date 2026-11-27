package dev.polarclient.util;

import dev.polarclient.Log;

import java.io.File;
import java.util.Locale;

/** Opens a web page or a folder with the system's own handler (works the same on every Minecraft version). */
public final class OsOpen {
	private OsOpen() {
	}

	public static void url(String url) {
		if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
		run(url);
	}

	public static void folder(File dir) {
		if (dir == null) return;
		if (!dir.exists() && !dir.mkdirs()) return;
		run(dir.getAbsolutePath());
	}

	private static void run(String target) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] cmd;
		if (os.contains("win")) cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", target};
		else if (os.contains("mac")) cmd = new String[]{"open", target};
		else cmd = new String[]{"xdg-open", target};
		try {
			new ProcessBuilder(cmd).start();
		} catch (Exception e) {
			Log.warn("Couldn't open " + target, e);
		}
	}
}
