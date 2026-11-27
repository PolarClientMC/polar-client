package dev.polarclient.world;

import dev.polarclient.platform.Platform;

/** The last multiplayer server this session connected to (for Retry after the connection drops). */
public final class LastServer {
	private LastServer() {
	}

	private static String address, name;

	public static void tick(Platform p) {
		if (p.isSingleplayer()) return;
		String a = p.serverAddress();
		if (a == null || a.isEmpty()) return;
		address = a;
		String n = p.serverName();
		name = n == null || n.isEmpty() ? a : n;
	}

	public static String address() {
		return address;
	}

	public static String name() {
		return name;
	}
}
