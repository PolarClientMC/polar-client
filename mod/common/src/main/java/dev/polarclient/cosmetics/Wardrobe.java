package dev.polarclient.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.polarclient.Log;
import dev.polarclient.social.PolarSocial;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What you own and wear, as the Polar server says (it decides prices, what's yours and the coins), plus your saved
 * outfits (kept on this PC). The game only asks; buying and wearing are checked again by the server.
 */
public final class Wardrobe {
	private Wardrobe() {
	}

	public static final String[] SLOTS = {"cape", "wings", "head", "back", "aura"};
	public static final int MAX_OUTFITS = 6;

	public static final class Outfit {
		public String name;
		public final Map<String, String> slots = new LinkedHashMap<String, String>();

		public Outfit(String name) {
			this.name = name;
		}
	}

	private static volatile boolean loaded, loading;
	private static volatile Map<String, String> worn = Collections.emptyMap();
	private static volatile Set<String> owned = Collections.emptySet();
	private static volatile Map<String, int[]> config = Collections.emptyMap(); // id -> {price, codeOnly}
	private static volatile boolean hideBadge;
	private static List<Outfit> outfits;

	public static boolean loaded() {
		return loaded;
	}

	/** Reads /v1/me again (on opening the wardrobe or the wheel). */
	public static void load(final Runnable done) {
		if (loading) return;
		loading = true;
		PolarSocial.call("GET", "/v1/me", null, r -> {
			loading = false;
			if (r == null) return;
			read(r);
			loaded = true;
			if (done != null) done.run();
		});
	}

	private static void read(JsonObject r) {
		Map<String, String> w = new LinkedHashMap<String, String>();
		if (r.has("loadout") && r.get("loadout").isJsonObject()) {
			JsonObject lo = r.getAsJsonObject("loadout");
			hideBadge = lo.has("hideBadge") && lo.get("hideBadge").getAsBoolean();
			if (lo.has("slots") && lo.get("slots").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : lo.getAsJsonObject("slots").entrySet()) {
					if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("id")) w.put(e.getKey(), e.getValue().getAsJsonObject().get("id").getAsString());
				}
			}
		}
		worn = Collections.unmodifiableMap(w);
		Set<String> o = new HashSet<String>();
		if (r.has("owned") && r.get("owned").isJsonArray()) for (JsonElement e : r.getAsJsonArray("owned")) o.add(e.getAsString());
		owned = Collections.unmodifiableSet(o);
		Map<String, int[]> c = new java.util.HashMap<String, int[]>();
		if (r.has("cosmeticConfig") && r.get("cosmeticConfig").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("cosmeticConfig").entrySet()) {
				JsonObject v = e.getValue().getAsJsonObject();
				c.put(e.getKey(), new int[] {v.has("price") ? v.get("price").getAsInt() : 0, v.has("codeOnly") && v.get("codeOnly").getAsBoolean() ? 1 : 0});
			}
		}
		config = Collections.unmodifiableMap(c);
	}

	public static Map<String, String> worn() {
		return worn;
	}

	public static int price(String id) {
		int[] c = config.get(id);
		return c == null ? 0 : c[0];
	}

	/** Special collection items: never sold, only given. */
	public static boolean special(String id) {
		int[] c = config.get(id);
		return c != null && c[1] == 1;
	}

	/** Whether you may wear it: free, bought or given. */
	public static boolean canWear(String id) {
		if (owned.contains(id)) return true;
		return !special(id) && price(id) <= 0;
	}

	public static boolean owns(String id) {
		return owned.contains(id);
	}

	/** The look with one item put on, keeping the slot rules: wings and back items share your back. */
	public static Map<String, String> with(Map<String, String> look, String slot, String id) {
		Map<String, String> m = new LinkedHashMap<String, String>(look);
		if (id == null) {
			m.remove(slot);
			return m;
		}
		m.put(slot, id);
		if ("wings".equals(slot)) m.remove("back");
		if ("back".equals(slot)) m.remove("wings");
		return m;
	}

	/** Saves what you wear (only what you may wear is sent). */
	public static void wear(Map<String, String> look, final Consumer<Boolean> done) {
		JsonObject slots = new JsonObject();
		final Map<String, String> sent = new LinkedHashMap<String, String>();
		for (Map.Entry<String, String> e : look.entrySet()) {
			if (e.getValue() == null || !canWear(e.getValue())) continue;
			slots.addProperty(e.getKey(), e.getValue());
			sent.put(e.getKey(), e.getValue());
		}
		JsonObject b = new JsonObject();
		b.add("slots", slots);
		b.addProperty("hideBadge", hideBadge);
		PolarSocial.call("PUT", "/v1/me/loadout", b, r -> {
			boolean ok = r != null && r.has("loadout");
			if (ok) {
				worn = Collections.unmodifiableMap(sent);
				Cosmetics.refreshMine();
			}
			if (done != null) done.accept(ok);
		});
	}

	public static void buy(final String id, final Consumer<Boolean> done) {
		JsonObject b = new JsonObject();
		b.addProperty("item", id);
		PolarSocial.call("POST", "/v1/cosmetics/buy", b, r -> {
			boolean ok = r != null && !r.has("error");
			if (ok) {
				Set<String> o = new HashSet<String>(owned);
				o.add(id);
				owned = Collections.unmodifiableSet(o);
				PolarSocial.refreshSoon();
			}
			if (done != null) done.accept(ok);
		});
	}

	// ------------------------------------------------------------------ outfits (this PC)

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("polarclient-outfits.json");
	}

	@SuppressWarnings("deprecation")
	public static synchronized List<Outfit> outfits() {
		if (outfits != null) return outfits;
		outfits = new ArrayList<Outfit>();
		try {
			Path f = file();
			if (Files.exists(f)) {
				JsonElement root = new JsonParser().parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
				if (root.isJsonArray()) {
					for (JsonElement e : root.getAsJsonArray()) {
						if (!e.isJsonObject() || outfits.size() >= MAX_OUTFITS) continue;
						JsonObject o = e.getAsJsonObject();
						Outfit out = new Outfit(o.has("name") ? o.get("name").getAsString() : "Outfit");
						if (o.has("slots") && o.get("slots").isJsonObject()) {
							for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("slots").entrySet()) out.slots.put(s.getKey(), s.getValue().getAsString());
						}
						outfits.add(out);
					}
				}
			}
		} catch (Exception e) {
			Log.error("Couldn't read outfits", e);
		}
		return outfits;
	}

	public static synchronized void saveOutfits() {
		JsonArray arr = new JsonArray();
		for (Outfit o : outfits()) {
			JsonObject j = new JsonObject();
			j.addProperty("name", o.name);
			JsonObject s = new JsonObject();
			for (Map.Entry<String, String> e : o.slots.entrySet()) s.addProperty(e.getKey(), e.getValue());
			j.add("slots", s);
			arr.add(j);
		}
		try {
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			Files.createDirectories(file().getParent());
			Files.write(file(), gson.toJson(arr).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			Log.error("Couldn't save outfits", e);
		}
	}

	/** Saves a look as a new outfit ("Outfit N"); false when there are already {@link #MAX_OUTFITS}. */
	public static synchronized boolean addOutfit(Map<String, String> look) {
		List<Outfit> l = outfits();
		if (l.size() >= MAX_OUTFITS) return false;
		Outfit o = new Outfit("Outfit " + (l.size() + 1));
		o.slots.putAll(look);
		l.add(o);
		saveOutfits();
		return true;
	}
}
