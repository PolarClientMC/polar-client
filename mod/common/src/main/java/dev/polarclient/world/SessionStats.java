package dev.polarclient.world;

import dev.polarclient.platform.Platform;

import java.util.Locale;

/**
 * What happened since joining this world or server: play time, kills, deaths and coins earned. Kept whether or
 * not the matching HUD mods are on (the pause menu shows it).
 * <p>
 * The session ends when you leave: a different server or world, or more than {@link #GAP_MS} out of a world. A
 * short gap on the same address (a server network moving you from the lobby to a game, which on 1.20.2+ briefly
 * unloads the world) keeps the session going.
 */
public final class SessionStats {
	private SessionStats() {
	}

	static final long GAP_MS = 10_000;
	private static final long SAME_DEATH_MS = 3_000;

	private static long joinedAt = -1, leftAt = -1, lastDeathAt;
	private static int kills, deaths, coins, session;
	private static boolean wasDead;
	private static String where;

	/** Every client tick. */
	public static void tick(Platform p) {
		long now = System.currentTimeMillis();
		if (!p.inWorld()) {
			if (joinedAt >= 0 && leftAt < 0) leftAt = now;
			return;
		}
		String here = p.isSingleplayer() ? "singleplayer" : String.valueOf(p.serverAddress()).toLowerCase(Locale.ROOT);
		boolean newSession = joinedAt < 0 || (leftAt >= 0 && (now - leftAt > GAP_MS || !here.equals(where)));
		if (newSession) reset(now);
		leftAt = -1;
		where = here;
		boolean dead = p.hasPlayer() && p.health() <= 0;
		if (dead && !wasDead) died(now);
		wasDead = dead;
	}

	private static void reset(long now) {
		joinedAt = now;
		kills = deaths = coins = 0;
		lastDeathAt = 0;
		wasDead = false;
		session++;
	}

	/** Changes each time a new session starts (mods that keep their own counts reset with it). */
	public static int session() {
		return session;
	}

	/** A death counts once, whether we saw it from health reaching 0, a death message, or both. */
	private static void died(long now) {
		if (now - lastDeathAt > SAME_DEATH_MS) deaths++;
		lastDeathAt = now;
	}

	/** System messages: death messages naming you as the killer are kills; ones about you are deaths. */
	public static void onGameMessage(Platform p, String text) {
		if (joinedAt < 0 || !p.inWorld()) return;
		String me = p.userName();
		if (isKill(text, me)) kills++;
		else if (isOwnDeath(text, me)) died(System.currentTimeMillis());
	}

	// ---- reading death messages (shared with the Kill Counter mod) ----

	/** Words in a death message that come right before the killer's name. */
	private static final String[] KILLER_BEFORE = { "by ", "fighting ", "escape ", "trying to hurt ", "same world as ", "because of " };

	/** Messages that say "by You" but aren't deaths: parties, friends, teleports, moderation. */
	private static final String[] NOT_DEATH = { "invite", "party", "friend", "request", "teleport", "kicked", "banned", "muted", "promoted", "given",
			"sent ", "accepted", "joined", "trade" };

	/** How death messages about you continue after your name (vanilla wording, plus common server wording). */
	private static final String[] DEATH_AFTER = { " died", " fell", " drowned", " burned", " burnt", " blew up", " hit the ground", " starved",
			" suffocated", " experienced kinetic energy", " froze", " went up in flames", " walked into", " tried to swim in lava",
			" withered away", " discovered the floor was lava", " didn't want to live", " left the confines", " went off with a bang",
			" has died", " has been killed", " is dead" };

	/** "Steve was slain by You" and the like. Ignores your own deaths, player chat and longer names that start with yours. */
	public static boolean isKill(String text, String me) {
		if (text == null || me == null || me.isEmpty()) return false;
		String t = text.trim();
		String lower = t.toLowerCase(Locale.ROOT), meL = me.toLowerCase(Locale.ROOT);
		if (t.startsWith("<") || startsWithName(lower, meL)) return false;
		for (String w : NOT_DEATH) if (lower.contains(w)) return false;
		for (String w : KILLER_BEFORE) {
			int at = 0;
			while ((at = lower.indexOf(w + meL, at)) >= 0) {
				int end = at + w.length() + meL.length();
				boolean wordStart = at == 0 || !nameChar(lower.charAt(at - 1));
				boolean wordEnd = end >= lower.length() || !nameChar(lower.charAt(end));
				if (wordStart && wordEnd && !chatBefore(lower, at)) return true;
				at = end;
			}
		}
		return false;
	}

	/** "You was slain by Steve", "You fell into the void." and the like. */
	public static boolean isOwnDeath(String text, String me) {
		if (text == null || me == null || me.isEmpty()) return false;
		String lower = text.trim().toLowerCase(Locale.ROOT), meL = me.toLowerCase(Locale.ROOT);
		if (!startsWithName(lower, meL)) return false;
		String rest = lower.substring(meL.length());
		if (rest.startsWith(" was ")) {
			// "was slain", "was shot", "was killed"... but not "was promoted" or "was given": a killer or a cause must follow
			return rest.contains(" by ") || rest.contains("death") || rest.contains("killed") || rest.contains("void") || rest.contains("lightning")
					|| rest.contains("squashed") || rest.contains("squished") || rest.contains("impaled") || rest.contains("obliterated");
		}
		for (String w : DEATH_AFTER) if (rest.startsWith(w)) return true;
		return false;
	}

	private static boolean startsWithName(String lower, String meL) {
		return lower.startsWith(meL) && (lower.length() == meL.length() || !nameChar(lower.charAt(meL.length())));
	}

	/** A ':' or '»' before the match means it's someone typing in chat ("[VIP] Steve: killed by You lol"). */
	private static boolean chatBefore(String lower, int at) {
		String head = lower.substring(0, at);
		return head.contains(": ") || head.contains("»") || head.contains(" > ");
	}

	private static boolean nameChar(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}

	public static void addCoins(int n) {
		if (n > 0 && joinedAt >= 0) coins += n;
	}

	public static long playMillis() {
		if (joinedAt < 0) return 0;
		return (leftAt >= 0 ? leftAt : System.currentTimeMillis()) - joinedAt;
	}

	public static int kills() {
		return kills;
	}

	public static int deaths() {
		return deaths;
	}

	public static int coins() {
		return coins;
	}

	/** "1h 24m", "12m", "40s". */
	public static String playTime() {
		long s = playMillis() / 1000;
		if (s < 60) return s + "s";
		long m = s / 60;
		return m < 60 ? m + "m" : (m / 60) + "h " + (m % 60) + "m";
	}
}
