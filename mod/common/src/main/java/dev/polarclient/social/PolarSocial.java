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
		/** online, busy, away or offline. */
		public String status = "offline";
		/** The server they're on (only when they're on one), for Join. */
		public String server;
		public int unread;
		public long lastSeen;

		Friend(String uuid, String name, boolean online, String where) {
			this.uuid = uuid;
			this.name = name;
			this.online = online;
			this.where = where;
		}
	}

	/** Someone asking to be friends, or a request you sent. */
	public static final class Request {
		public final String uuid, name;

		Request(String uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}
	}

	/** One direct message. */
	public static final class Message {
		public final boolean mine;
		public final String text;
		public final long at;

		Message(boolean mine, String text, long at) {
			this.mine = mine;
			this.text = text;
			this.at = at;
		}
	}

	public static final class Member {
		public final String uuid, name, server;
		public final boolean online, leader;

		Member(String uuid, String name, boolean online, boolean leader, String server) {
			this.uuid = uuid;
			this.name = name;
			this.online = online;
			this.leader = leader;
			this.server = server;
		}
	}

	public static final class Party {
		public final String id, leader;
		public final List<Member> members;
		public final List<Request> pending;

		Party(String id, String leader, List<Member> members, List<Request> pending) {
			this.id = id;
			this.leader = leader;
			this.members = members;
			this.pending = pending;
		}
	}

	/** A party invite you got: {party id, who from}. */
	public static final class PartyInvite {
		public final String party, from;

		PartyInvite(String party, String from) {
			this.party = party;
			this.from = from;
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
	private static long nextFriends, nextCoins, nextParty;
	private static volatile List<Request> incoming = Collections.emptyList(), outgoing = Collections.emptyList();
	private static volatile String myStatus = "online";
	private static final Map<String, List<Message>> THREADS = new HashMap<String, List<Message>>();
	private static volatile Party party;
	private static volatile List<PartyInvite> partyInvites = Collections.emptyList();
	/** Set while the friends panel is open, so the party is kept fresh. */
	public static volatile boolean panelOpen;

	/** Every client tick (cheap: the network work runs on a background thread). */
	public static void tick() {
		if (!LauncherBridge.available() || busy) return;
		long now = System.currentTimeMillis();
		final boolean doFriends = now >= nextFriends, doCoins = now >= nextCoins, doParty = now >= nextParty;
		if (!doFriends && !doCoins && !doParty) return;
		if (doFriends) nextFriends = now + (panelOpen ? 5000 : FRIENDS_EVERY);
		if (doCoins) nextCoins = now + COINS_EVERY;
		if (doParty) nextParty = now + (panelOpen ? 5000 : 30000);
		busy = true;
		IO.execute(() -> {
			try {
				if (doCoins) refreshCoins();
				if (doFriends) refreshFriends();
				if (doParty) refreshParty();
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
		else if ("dm".equals(type)) {
			JsonObject from = ev.has("from") && ev.get("from").isJsonObject() ? ev.getAsJsonObject("from") : new JsonObject();
			String uuid = str(from, "uuid"), name = str(from, "name");
			String text = str(ev, "text");
			synchronized (THREADS) {
				List<Message> t = THREADS.get(uuid);
				if (t != null) t.add(new Message(false, text, System.currentTimeMillis()));
			}
			for (Friend f : friends) if (f.uuid.equals(uuid) && !uuid.equals(openThread)) f.unread++;
			if (!uuid.equals(openThread)) SocialToasts.show(SocialToasts.Kind.MESSAGE, name, text, null);
		} else if ("party".equals(type)) {
			nextParty = 0;
			String event = str(ev, "event");
			JsonObject from = ev.has("from") && ev.get("from").isJsonObject() ? ev.getAsJsonObject("from") : new JsonObject();
			String name = str(from, "name");
			if ("invite".equals(event)) SocialToasts.show(SocialToasts.Kind.PARTY, name, "invited you to their party", str(ev, "party"));
			else if ("warp".equals(event)) SocialToasts.warp(name, str(ev, "server"));
			else if ("chat".equals(event)) SocialToasts.partyChat(name, str(ev, "text"));
			else if (ev.has("kicked")) SocialToasts.show(SocialToasts.Kind.INFO, "Party", "You were removed from the party", null);
			else if (ev.has("disbanded")) SocialToasts.show(SocialToasts.Kind.INFO, "Party", "The party has ended", null);
			else if (ev.has("joined") && ev.get("joined").isJsonObject()) SocialToasts.show(SocialToasts.Kind.INFO, str(ev.getAsJsonObject("joined"), "name"), "joined the party", null);
			else if (ev.has("left") && ev.get("left").isJsonObject()) SocialToasts.show(SocialToasts.Kind.INFO, str(ev.getAsJsonObject("left"), "name"), "left the party", null);
		}
	}

	/** The friend whose messages are open right now (so theirs don't count as unread), or null. */
	public static volatile String openThread;

	/** Runs a Polar server call off the game thread, then {@code done} on the game thread with the answer (or null). */
	public static void call(final String method, final String path, final JsonObject body, final java.util.function.Consumer<JsonObject> done) {
		IO.execute(() -> {
			JsonObject r;
			String err = null;
			try {
				r = api(method, path, body);
			} catch (RuntimeException e) {
				r = null;
				err = e.getMessage();
			}
			final JsonObject res = r;
			final String error = err;
			dev.polarclient.platform.Platform p = dev.polarclient.platform.Platform.get();
			if (p != null) p.execute(() -> {
				if (res == null && error != null) SocialToasts.show(SocialToasts.Kind.INFO, "Polar", plainError(error), null);
				if (done == null) return;
				try {
					done.accept(res);
				} catch (RuntimeException e) {
					// an answer we didn't expect must never take the game down
					dev.polarclient.Log.error("Polar couldn't use the answer to " + method + " " + path, e);
				}
			});
		});
	}

	private static String plainError(String e) {
		if (e == null || e.isEmpty()) return "Something went wrong";
		return e.length() > 90 ? e.substring(0, 90) + "..." : e;
	}

	public static void refreshSoon() {
		nextFriends = 0;
		nextParty = 0;
	}

	public static String myStatus() {
		return myStatus;
	}

	public static void setStatus(String status) {
		myStatus = status;
		JsonObject b = new JsonObject();
		b.addProperty("status", status);
		call("POST", "/v1/social/status", b, null);
	}

	public static List<Request> incoming() {
		return incoming;
	}

	public static List<Request> outgoing() {
		return outgoing;
	}

	/** The loaded messages with a friend, or null until {@link #loadThread} has answered. */
	public static List<Message> thread(String uuid) {
		synchronized (THREADS) {
			List<Message> t = THREADS.get(uuid);
			return t == null ? null : new ArrayList<Message>(t);
		}
	}

	public static void loadThread(final String uuid, final String myUuid) {
		JsonObject b = new JsonObject();
		b.addProperty("uuid", uuid);
		call("POST", "/v1/social/thread", b, r -> {
			if (r == null || !r.has("messages")) return;
			List<Message> list = new ArrayList<Message>();
			for (JsonElement e : r.getAsJsonArray("messages")) {
				if (!e.isJsonObject()) continue;
				JsonObject m = e.getAsJsonObject();
				list.add(new Message(!uuid.equals(str(m, "from")), str(m, "text"), m.has("at") ? m.get("at").getAsLong() : 0));
			}
			synchronized (THREADS) {
				THREADS.put(uuid, list);
			}
			for (Friend f : friends) if (f.uuid.equals(uuid)) f.unread = 0;
		});
	}

	public static void sendMessage(final String uuid, final String text) {
		synchronized (THREADS) {
			List<Message> t = THREADS.get(uuid);
			if (t == null) THREADS.put(uuid, t = new ArrayList<Message>());
			t.add(new Message(true, text, System.currentTimeMillis()));
		}
		JsonObject b = new JsonObject();
		b.addProperty("uuid", uuid);
		b.addProperty("text", text);
		call("POST", "/v1/social/dm", b, null);
	}

	public static void partyChat(String text) {
		JsonObject b = new JsonObject();
		b.addProperty("text", text);
		call("POST", "/v1/social/party/chat", b, null);
	}

	public static int unreadTotal() {
		int n = 0;
		for (Friend f : friends) n += f.unread;
		return n;
	}

	public static Party party() {
		return party;
	}

	public static List<PartyInvite> partyInvites() {
		return partyInvites;
	}

	/** Party actions: invite, join, decline, leave, kick, warp, chat. The party is read again after. */
	public static void partyAction(String action, JsonObject body) {
		call("POST", "/v1/social/party/" + action, body == null ? new JsonObject() : body, r -> {
			if (r != null && r.has("invites")) readParty(r);
		});
	}

	private static void refreshParty() {
		try {
			readParty(api("GET", "/v1/social/party", null));
		} catch (RuntimeException ignored) {
			// try again next time
		}
	}

	private static void readParty(JsonObject o) {
		if (o == null) return;
		List<PartyInvite> inv = new ArrayList<PartyInvite>();
		if (o.has("invites") && o.get("invites").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("invites")) {
				if (!e.isJsonObject()) continue;
				JsonObject i = e.getAsJsonObject();
				inv.add(new PartyInvite(str(i, "party"), i.has("from") && i.get("from").isJsonObject() ? str(i.getAsJsonObject("from"), "name") : ""));
			}
		}
		partyInvites = Collections.unmodifiableList(inv);
		if (!o.has("party") || !o.get("party").isJsonObject()) {
			party = null;
			return;
		}
		JsonObject p = o.getAsJsonObject("party");
		List<Member> members = new ArrayList<Member>();
		for (JsonElement e : p.getAsJsonArray("members")) {
			JsonObject m = e.getAsJsonObject();
			String server = null;
			if (m.has("where") && m.get("where").isJsonObject() && "server".equals(str(m.getAsJsonObject("where"), "state"))) server = str(m.getAsJsonObject("where"), "server");
			members.add(new Member(str(m, "uuid"), str(m, "name"), m.has("online") && m.get("online").getAsBoolean(), m.has("leader") && m.get("leader").getAsBoolean(), server));
		}
		List<Request> pending = new ArrayList<Request>();
		if (p.has("pending")) for (JsonElement e : p.getAsJsonArray("pending")) pending.add(new Request(str(e.getAsJsonObject(), "uuid"), str(e.getAsJsonObject(), "name")));
		party = new Party(str(p, "id"), str(p, "leader"), Collections.unmodifiableList(members), Collections.unmodifiableList(pending));
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
				Friend fr = new Friend(str(f, "uuid"), str(f, "name"), f.has("online") && f.get("online").getAsBoolean(), where);
				fr.status = f.has("status") ? str(f, "status") : fr.online ? "online" : "offline";
				if (f.has("where") && f.get("where").isJsonObject() && "server".equals(str(f.getAsJsonObject("where"), "state"))) fr.server = str(f.getAsJsonObject("where"), "server");
				fr.unread = f.has("unread") && f.get("unread").isJsonPrimitive() ? f.get("unread").getAsInt() : 0;
				fr.lastSeen = f.has("lastSeen") && f.get("lastSeen").isJsonPrimitive() ? f.get("lastSeen").getAsLong() : 0;
				if (fr.uuid.equals(openThread)) fr.unread = 0;
				list.add(fr);
			}
			incoming = requests(o, "incoming");
			outgoing = requests(o, "outgoing");
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

	private static List<Request> requests(JsonObject o, String k) {
		List<Request> out = new ArrayList<Request>();
		if (o.has(k) && o.get(k).isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray(k)) {
				if (e.isJsonObject()) out.add(new Request(str(e.getAsJsonObject(), "uuid"), str(e.getAsJsonObject(), "name")));
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** Friends actions: add (by name), accept, remove, invite (to your server). Friends are read again after. */
	public static void friendAction(String action, JsonObject body) {
		call("POST", "/v1/friends/" + action, body, r -> {
			if (r != null && r.has("message")) SocialToasts.show(SocialToasts.Kind.INFO, "Friends", str(r, "message"), null);
			else if (r != null && "invite".equals(action) && r.has("sent")) SocialToasts.show(SocialToasts.Kind.INFO, "Invite sent", "to " + str(r, "server"), null);
			nextFriends = 0;
		});
	}

	static String str(JsonObject o, String k) {
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
