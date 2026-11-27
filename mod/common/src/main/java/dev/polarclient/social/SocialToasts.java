package dev.polarclient.social;

import dev.polarclient.platform.Platform;

import java.util.ArrayList;
import java.util.List;

/** Small social cards for the top-left corner (messages, party news) and the party-warp countdown. */
public final class SocialToasts {
	private SocialToasts() {
	}

	public enum Kind { MESSAGE, PARTY, INFO }

	public static final class Toast {
		public final Kind kind;
		public final String who, text, data;
		public final long at = System.currentTimeMillis();

		Toast(Kind kind, String who, String text, String data) {
			this.kind = kind;
			this.who = who;
			this.text = text;
			this.data = data;
		}
	}

	public static final long LIFE = 6000;
	private static final List<Toast> TOASTS = new ArrayList<Toast>();
	private static volatile String warpServer, warpFrom;
	private static volatile long warpAt;
	public static final long WARP_MS = 5000;

	public static synchronized void show(Kind kind, String who, String text, String data) {
		TOASTS.add(new Toast(kind, who == null ? "" : who, text == null ? "" : text, data));
		while (TOASTS.size() > 3) TOASTS.remove(0);
	}

	/** The cards still showing, oldest first. */
	public static synchronized List<Toast> live() {
		long now = System.currentTimeMillis();
		for (int i = TOASTS.size() - 1; i >= 0; i--) if (now - TOASTS.get(i).at > LIFE) TOASTS.remove(i);
		return new ArrayList<Toast>(TOASTS);
	}

	/** The party leader is bringing everyone to a server: join in 5 s unless you choose to stay. */
	public static void warp(String from, String server) {
		Platform p = Platform.get();
		if (server == null || server.isEmpty() || p == null) return;
		String here = p.serverAddress();
		if (here != null && here.equalsIgnoreCase(server)) return;
		warpFrom = from;
		warpServer = server;
		warpAt = System.currentTimeMillis();
	}

	public static String warpServer() {
		return warpServer;
	}

	public static String warpFrom() {
		return warpFrom;
	}

	/** Seconds left before the party warp, or -1 when none is pending. */
	public static float warpLeft() {
		if (warpServer == null) return -1;
		return Math.max(0, (WARP_MS - (System.currentTimeMillis() - warpAt)) / 1000f);
	}

	public static void stayHere() {
		if (warpServer != null) show(Kind.INFO, "Party", "You stayed here", null);
		warpServer = null;
	}

	/** Every tick: go when the countdown ends. */
	public static void tick(Platform p) {
		String s = warpServer;
		if (s == null) return;
		if (System.currentTimeMillis() - warpAt >= WARP_MS) {
			warpServer = null;
			p.joinServer(s);
		}
	}

	public static void partyChat(String from, String text) {
		Platform p = Platform.get();
		if (p != null) p.execute(() -> p.chatLine("§d[Party]§r §b" + from + "§r: " + text));
	}
}
