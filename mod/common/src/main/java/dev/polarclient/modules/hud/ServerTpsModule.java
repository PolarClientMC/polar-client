package dev.polarclient.modules.hud;

import dev.polarclient.module.Category;
import dev.polarclient.module.TextHudModule;
import dev.polarclient.platform.TabListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's ticks per second, when it shares them: in singleplayer from the built-in server, on servers from the
 * tab list header or footer (many servers print "TPS: 19.9" there). Otherwise it says the server keeps it private.
 */
public class ServerTpsModule extends TextHudModule {
	private static final Pattern TPS = Pattern.compile("(?i)\\btps\\b[^0-9]{0,8}(\\*?[0-9]{1,2}(?:[.,][0-9]{1,2})?)");
	private float tps = -1, mspt = -1;
	private long nextRead;

	public ServerTpsModule() {
		super("server_tps", "Server TPS", "Server ticks per second, when the server shares it.", Category.UTILITY, false, 4, 120);
	}

	@Override
	public boolean hasContent() {
		return p().inWorld();
	}

	private void read() {
		long now = System.currentTimeMillis();
		if (now < nextRead) return;
		nextRead = now + 1000;
		tps = mspt = -1;
		if (p().isSingleplayer()) {
			float ms = p().localTickMillis();
			if (ms > 0) {
				mspt = ms;
				tps = Math.min(20f, 1000f / Math.max(50f, ms));
			}
			return;
		}
		TabListView tab = p().tabList();
		if (tab == null) return;
		String text = String.valueOf(p().plain(tab.header)) + "\n" + String.valueOf(p().plain(tab.footer));
		Matcher m = TPS.matcher(text);
		if (m.find()) {
			try {
				tps = Math.min(20f, Float.parseFloat(m.group(1).replace("*", "").replace(',', '.')));
				mspt = tps > 0 ? Math.max(50f, 1000f / tps) : -1;
			} catch (NumberFormatException ignored) {
				tps = -1;
			}
		}
	}

	@Override
	protected List<String> lines(boolean preview) {
		if (!p().inWorld()) {
			List<String> l = new ArrayList<String>(2);
			l.add("TPS §a19.9");
			l.add("§750.2 ms/tick");
			return l;
		}
		read();
		if (tps < 0) return one("§7This server keeps its TPS private");
		String c = tps >= 18 ? "§a" : tps >= 14 ? "§e" : "§c";
		List<String> l = new ArrayList<String>(2);
		l.add("TPS " + c + String.format(Locale.ROOT, "%.1f", tps));
		if (mspt > 0) l.add("§7" + String.format(Locale.ROOT, "%.1f", mspt) + " ms/tick");
		return l;
	}
}
