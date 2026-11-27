package dev.polarclient.world;

import dev.polarclient.platform.Platform;

import java.util.Locale;

/**
 * What happened since joining this world or server: play time, kills, deaths and coins earned. Kept whether or
 * not the matching HUD mods are on (the pause menu shows it). Resets when you leave.
 */
public final class SessionStats {
	private SessionStats() {
	}

	private static long joinedAt = -1;
	private static int kills, deaths, coins;
	private static boolean wasDead;

	/** Every client tick. */
	public static void tick(Platform p) {
		if (!p.inWorld()) {
			joinedAt = -1;
			kills = deaths = coins = 0;
			wasDead = false;
			return;
		}
		if (joinedAt < 0) joinedAt = System.currentTimeMillis();
		boolean dead = p.hasPlayer() && p.health() <= 0;
		if (dead && !wasDead) deaths++;
		wasDead = dead;
	}

	/** Death messages that name you as the killer count as kills (same rule as the Kill Counter mod). */
	public static void onGameMessage(Platform p, String text) {
		String me = p.userName();
		if (me == null || me.isEmpty() || text.startsWith(me) || text.startsWith("<")) return;
		String lower = text.toLowerCase(Locale.ROOT), meL = me.toLowerCase(Locale.ROOT);
		if (lower.contains("by " + meL) || lower.contains("fighting " + meL)) kills++;
	}

	public static void addCoins(int n) {
		if (n > 0) coins += n;
	}

	public static long playMillis() {
		return joinedAt < 0 ? 0 : System.currentTimeMillis() - joinedAt;
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
