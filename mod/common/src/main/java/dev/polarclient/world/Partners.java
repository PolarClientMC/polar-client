package dev.polarclient.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Polar's partner servers, from the copy of partners.json the launcher keeps (.polarclient/partners-cache.json,
 * two folders above the game folder). Read once; empty when the game wasn't started from the launcher.
 */
public final class Partners {
	private Partners() {
	}

	public static final class Partner {
		public final String name, ip, discord;

		Partner(String name, String ip, String discord) {
			this.name = name;
			this.ip = ip;
			this.discord = discord;
		}
	}

	private static List<Partner> list;

	private static synchronized List<Partner> all() {
		if (list != null) return list;
		list = new ArrayList<Partner>();
		try {
			Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath();
			Path root = game.getParent() == null || game.getParent().getParent() == null ? null : game.getParent().getParent();
			Path f = root == null ? null : root.resolve("partners-cache.json");
			if (f == null || !Files.isRegularFile(f)) return list;
			JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
			if (!e.isJsonObject() || !e.getAsJsonObject().has("partners")) return list;
			JsonArray arr = e.getAsJsonObject().getAsJsonArray("partners");
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				if (o.has("hidden") && o.get("hidden").getAsBoolean()) continue;
				String ip = str(o, "ip");
				if (ip == null) continue;
				list.add(new Partner(str(o, "name"), ip, str(o, "discord")));
			}
		} catch (Exception ignored) {
			// no launcher copy: no partner badges
		}
		return list;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static String host(String address) {
		if (address == null) return "";
		String a = address.trim().toLowerCase(Locale.ROOT);
		int colon = a.lastIndexOf(':');
		if (colon > 0 && a.indexOf(':') == colon) a = a.substring(0, colon);
		return a.endsWith(".") ? a.substring(0, a.length() - 1) : a;
	}

	/** The partner this server address belongs to (same host, or a subdomain of it), or null. */
	public static Partner forAddress(String address) {
		String h = host(address);
		if (h.isEmpty()) return null;
		for (Partner p : all()) {
			String ph = host(p.ip);
			if (h.equals(ph) || h.endsWith("." + ph)) return p;
			// play.example.net and mc.example.net are the same network
			String base = ph.startsWith("play.") ? ph.substring(5) : ph;
			if (h.equals(base) || h.endsWith("." + base)) return p;
		}
		return null;
	}
}
