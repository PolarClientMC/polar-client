package dev.polarclient.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.polarclient.account.LauncherBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player's Polar account as the game sees it: coin balance and friends, read from the Polar server through the
 * launcher (the launcher holds the sign-in, the game never does). Refreshed in the background; live changes arrive as
 * launcher events. Everything here is what the server says: the game never sets a balance itself.
 */
public final class PolarSocial {
	private PolarSocial() {
	}

	public static final class Friend {
		public final String uuid, name, where;
		public final boolean online;

		Friend(String uuid, String name, boolean online, String where) {
			this.uuid = uuid;
			this.name = name;
			this.online = online;
			this.where = where;
		}
	}

	/** A short note for the HUD ("Steve is online", "+10"). */
	public static final class Note {
		public final String text;
		public final long at;

		Note(String text) {
			this.text = text;
			this.at = System.currentTimeMillis();
		}
	}

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Polar social");
		t.setDaemon(true);
		return t;
	});
	private static final long FRIENDS_EVERY = 20000, COINS_EVERY = 60000;

	private static volatile int coins = -1;
	private static volatile long coinsDeltaAt;
	private static volatile int coinsDelta;
	private static volatile List<Friend> friends = Collections.emptyList();
	private static volatile boolean friendsKnown;
	private static volatile Note friendNote;
	private static volatile boolean busy;
	private static long nextFriends, nextCoins;

	/** Every client tick (cheap: the network work runs on a background thread). */
	public static void tick() {
		if (!LauncherBridge.available() || busy) return;
		long now = System.currentTimeMillis();
		final boolean doFriends = now >= nextFriends, doCoins = now >= nextCoins;
		if (!doFriends && !doCoins) return;
		if (doFriends) nextFriends = now + FRIENDS_EVERY;
		if (doCoins) nextCoins = now + COINS_EVERY;
		busy = true;
		IO.execute(() -> {
			try {
				if (doCoins) refreshCoins();
				if (doFriends) refreshFriends();
			} finally {
				busy = false;
			}
		});
	}

	/** A Polar server message the launcher passed on ({@code coins}, {@code friends}). */
	public static void onEvent(JsonObject ev) {
		String type = ev.has("type") ? ev.get("type").getAsString() : "";
		if ("coins".equals(type) && ev.has("coins")) setCoins(ev.get("coins").getAsInt());
		else if ("friends".equals(type)) nextFriends = 0;
	}

	/** A call to the Polar server through the launcher; only the routes the launcher allows. */
	public static JsonObject api(String method, String path, JsonObject body) {
		JsonObject req = new JsonObject();
		req.addProperty("method", method);
		req.addProperty("path", path);
		if (body != null) req.add("body", body);
		return LauncherBridge.polar("POST", "/polar/api", req.toString(), 8000);
	}

	private static void refreshCoins() {
		try {
			JsonObject o = api("GET", "/v1/me/coins", null);
			if (o.has("coins")) setCoins(o.get("coins").getAsInt());
		} catch (RuntimeException ignored) {
			// not signed in to Polar, or the server is away: keep what we had
		}
	}

	private static void setCoins(int n) {
		int before = coins;
		coins = n;
		if (before >= 0 && n > before) {
			coinsDelta = n - before;
			coinsDeltaAt = System.currentTimeMillis();
			dev.polarclient.world.SessionStats.addCoins(n - before);
		}
	}

	private static void refreshFriends() {
		try {
			JsonObject o = api("GET", "/v1/friends", null);
			if (!o.has("friends")) return;
			List<Friend> list = new ArrayList<Friend>();
			JsonArray arr = o.getAsJsonArray("friends");
			for (JsonElement e : arr) {
				if (!e.isJsonObject()) continue;
				JsonObject f = e.getAsJsonObject();
				String where = null;
				if (f.has("where") && f.get("where").isJsonObject()) {
					JsonObject w = f.getAsJsonObject("where");
					where = w.has("server") && w.get("server").isJsonPrimitive() ? w.get("server").getAsString()
						: w.has("state") && w.get("state").isJsonPrimitive() ? w.get("state").getAsString() : null;
				}
				list.add(new Friend(str(f, "uuid"), str(f, "name"), f.has("online") && f.get("online").getAsBoolean(), where));
			}
			// "X is online" / "X went offline" when it changes (not on the first load)
			if (friendsKnown) {
				Map<String, Boolean> was = new HashMap<String, Boolean>();
				for (Friend f : friends) was.put(f.uuid, f.online);
				for (Friend f : list) {
					Boolean before = was.get(f.uuid);
					if (before != null && before != f.online) friendNote = new Note(f.name + (f.online ? " is online" : " went offline"));
				}
			}
			friends = Collections.unmodifiableList(list);
			friendsKnown = true;
		} catch (RuntimeException ignored) {
			// try again next time
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	/** Coin balance, or -1 while unknown (not signed in to Polar, or not started from the launcher). */
	public static int coins() {
		return coins;
	}

	/** Coins just earned (for the "+N" pop), or 0 when nothing came in during the last {@code ms}. */
	public static int recentCoins(long ms) {
		return System.currentTimeMillis() - coinsDeltaAt < ms ? coinsDelta : 0;
	}

	public static long recentCoinsAt() {
		return coinsDeltaAt;
	}

	public static boolean friendsKnown() {
		return friendsKnown;
	}

	public static List<Friend> friends() {
		return friends;
	}

	public static int onlineFriends() {
		int n = 0;
		for (Friend f : friends) if (f.online) n++;
		return n;
	}

	/** The latest online/offline change, or null. */
	public static Note friendNote() {
		return friendNote;
	}
}
