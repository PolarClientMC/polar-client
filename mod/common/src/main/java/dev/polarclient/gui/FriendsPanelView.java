package dev.polarclient.gui;

import com.google.gson.JsonObject;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.TextField;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.social.PolarSocial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Friends, messages and your party, docked on the right so the game stays in view (press O). Everything comes from the
 * Polar server through the launcher; nothing here decides who is online or who is friends with whom.
 */
public class FriendsPanelView extends View {
	private static final float W = 172;
	private static final int ACCENT = 0xFF5AD8FF, MUTED = 0xFF93A8C6, DIM = 0xFF5E7290, GOOD = 0xFF5FE3A1, WARN = 0xFFFFC15E,
			BAD = 0xFFFF6B6B, GOLD = 0xFFFFD36B;
	private static final String[] STATUSES = {"online", "busy", "away", "invisible"};
	private static final String[] STATUS_NAMES = {"Online", "Busy (invites held)", "Away", "Appear offline"};

	private int tab;
	private final TextField search = new TextField(16), dm = new TextField(256), partyChat = new TextField(256);
	private float scroll;
	private boolean offlineOpen, inviteOpen;
	private String thread, threadName;
	private final List<Hit> hits = new ArrayList<Hit>();
	private final long openedAt = System.currentTimeMillis();

	private static final class Hit {
		final float x, y, w, h;
		final Runnable run;

		Hit(float x, float y, float w, float h, Runnable run) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.run = run;
		}
	}

	public FriendsPanelView(Object parent) {
		this(parent, 0);
	}

	public FriendsPanelView(Object parent, int tab) {
		super(parent);
		this.tab = tab;
		for (TextField f : new TextField[] {search, dm, partyChat}) f.setTextColor(0xFFFFFFFF);
		PolarSocial.panelOpen = true;
		PolarSocial.refreshSoon();
	}

	/** Opens straight on a conversation. */
	public static FriendsPanelView messages(Object parent, String uuid, String name) {
		FriendsPanelView v = new FriendsPanelView(parent, 1);
		v.openThread(uuid, name);
		return v;
	}

	@Override
	public String title() {
		return "Friends";
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	public void removed() {
		PolarSocial.panelOpen = false;
		PolarSocial.openThread = null;
	}

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
	}

	private float x0() {
		return width - W - 6;
	}

	private String myServer() {
		return p().isSingleplayer() ? null : p().serverAddress();
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(Gfx g, int mouseX, int mouseY, float partial) {
		Ui.frame();
		hits.clear();
		float k = Ui.easeOut((System.currentTimeMillis() - openedAt) / 220f);
		float x = x0() + (1 - k) * 30, y = 6, h = height - 12;
		Ui.rect(g, x, y, W, h, 7, Theme.alpha(0xF0091328, k));
		Ui.gradientRect(g, x + 1, y + 1, W - 2, 18, 6, 0x1ADCECFF, 0x00DCECFF);
		Ui.ring(g, x, y, W, h, 7, 0x479CC9FF, 1f);
		if (!dev.polarclient.account.LauncherBridge.available()) {
			Ui.icon(g, Icon.GROUPS, x + W / 2, y + h / 2 - 16, MUTED, Fonts.ICON_L);
			Ui.textCentered(g, "Start the game from the Polar launcher", x + W / 2, y + h / 2 + 4, 0xFFFFFFFF, Fonts.SMALL);
			Ui.textCentered(g, "to see your friends here.", x + W / 2, y + h / 2 + 14, MUTED, Fonts.SMALL);
			return;
		}
		header(g, x, y, mouseX, mouseY);
		tabs(g, x, y + 40, mouseX, mouseY);
		float top = y + 60, bottom = y + h - 6;
		if (tab == 0) friendsTab(g, x, top, bottom, mouseX, mouseY);
		else if (tab == 1) messagesTab(g, x, top, bottom, mouseX, mouseY);
		else partyTab(g, x, top, bottom, mouseX, mouseY);
	}

	private void header(Gfx g, float x, float y, int mx, int my) {
		UUID me = p().userId();
		g.playerHead(me, p().userName(), x + 9, y + 9, 20);
		Ui.textMid(g, Fonts.ellipsize(p().userName(), Fonts.BOLD, (int) (W - 70)), x + 35, y + 15, 0xFFFFFFFF, Fonts.BOLD);
		String st = PolarSocial.myStatus();
		int si = indexOf(st);
		boolean hv = Ui.inside(mx, my, x + 33, y + 21, W - 60, 11);
		Ui.circle(g, x + 38, y + 26.5f, 2.5f, statusColor(st));
		Ui.textMid(g, STATUS_NAMES[si] + (hv ? "  (change)" : ""), x + 44, y + 26.5f, hv ? 0xFFFFFFFF : MUTED, Fonts.SMALL);
		hits.add(new Hit(x + 33, y + 21, W - 60, 11, () -> PolarSocial.setStatus(STATUSES[(si + 1) % STATUSES.length])));
		boolean ch = Ui.inside(mx, my, x + W - 22, y + 8, 14, 14);
		Ui.icon(g, Icon.CLOSE, x + W - 15, y + 15, ch ? 0xFFFFFFFF : MUTED, Fonts.ICON_S);
		hits.add(new Hit(x + W - 22, y + 8, 14, 14, this::onClose));
	}

	private static int indexOf(String status) {
		for (int i = 0; i < STATUSES.length; i++) if (STATUSES[i].equals(status)) return i;
		return 0;
	}

	private static int statusColor(String s) {
		if ("online".equals(s)) return GOOD;
		if ("busy".equals(s)) return BAD;
		if ("away".equals(s)) return WARN;
		return DIM;
	}

	private void tabs(Gfx g, float x, float y, int mx, int my) {
		PolarSocial.Party party = PolarSocial.party();
		String[] names = {"Friends", "Messages", "Party"};
		int[] counts = {PolarSocial.onlineFriends(), PolarSocial.unreadTotal(), party == null ? PolarSocial.partyInvites().size() : party.members.size()};
		float tw = (W - 12) / 3f;
		for (int i = 0; i < 3; i++) {
			final int t = i;
			float tx = x + 6 + i * tw;
			boolean on = tab == i, hv = Ui.inside(mx, my, tx, y, tw, 15);
			if (on) Ui.rect(g, tx + 1, y, tw - 2, 15, 4, 0xFF2A5C9A);
			else if (hv) Ui.rect(g, tx + 1, y, tw - 2, 15, 4, 0x332A5C9A);
			String label = names[i];
			float lw = Fonts.width(label, Fonts.SMALL);
			boolean badge = counts[i] > 0 && (i == 1 || i == 2 && party == null);
			String c = counts[i] > 0 ? String.valueOf(counts[i]) : "";
			float cw = c.isEmpty() ? 0 : Fonts.width(c, Fonts.SMALL) + 6;
			float lx = tx + tw / 2 - (lw + cw) / 2;
			Ui.textMid(g, label, lx, y + 7.5f, on ? 0xFFFFFFFF : 0xFFB8C8DE, Fonts.SMALL);
			if (!c.isEmpty()) {
				if (badge) {
					Ui.rect(g, lx + lw + 3, y + 3, cw, 9, 4.5f, i == 1 ? BAD : GOLD);
					Ui.textCentered(g, c, lx + lw + 3 + cw / 2, y + 7.5f, 0xFF0B1426, Fonts.SMALL);
				} else {
					Ui.textMid(g, c, lx + lw + 5, y + 7.5f, MUTED, Fonts.SMALL);
				}
			}
			hits.add(new Hit(tx, y, tw, 15, () -> {
				tab = t;
				scroll = 0;
				if (t != 1) closeThread();
			}));
		}
	}

	private void field(Gfx g, TextField f, String hint, float x, float y, float w) {
		Ui.rect(g, x, y, w, 14, 4, f.focused() ? 0xF0123056 : 0xE00B1426);
		Ui.ring(g, x, y, w, 14, 4, f.focused() ? 0xFF8CC8FF : 0x663A5A86, 1f);
		f.setBounds(Math.round(x + 5), Math.round(y + 3), Math.round(w - 10));
		f.visible = true;
		if (f.value().isEmpty() && !f.focused()) g.text(hint, Math.round(x + 5), Math.round(y + 3), 0xFF6F819A, false);
		f.render(g);
	}

	private boolean smallButton(Gfx g, String icon, String label, float x, float y, float w, int mx, int my, int color, Runnable run) {
		boolean hv = Ui.inside(mx, my, x, y, w, 12);
		Ui.rect(g, x, y, w, 12, 3, hv ? Theme.alpha(color, 0.35f) : Theme.alpha(color, 0.18f));
		if (label == null) Ui.icon(g, icon, x + w / 2, y + 6, hv ? 0xFFFFFFFF : color, Fonts.ICON_S);
		else Ui.textCentered(g, label, x + w / 2, y + 6, hv ? 0xFFFFFFFF : 0xFFE4ECF7, Fonts.SMALL);
		hits.add(new Hit(x, y, w, 12, run));
		return hv;
	}

	private float groupTitle(Gfx g, String text, float x, float y) {
		Ui.textMid(g, text, x + 9, y + 5, DIM, Fonts.SMALL);
		return y + 11;
	}

	// ------------------------------------------------------------------ friends

	private void friendsTab(Gfx g, final float x, float top, float bottom, int mx, int my) {
		field(g, search, "Search or add a friend", x + 6, top, W - 12);
		float y0 = top + 19;
		String q = search.value().trim().toLowerCase(Locale.ROOT);
		g.scissor(Math.round(x), Math.round(y0), Math.round(x + W), Math.round(bottom));
		float y = y0 - scroll;
		boolean inside = my >= y0 && my < bottom;
		List<PolarSocial.Request> in = PolarSocial.incoming();
		if (!in.isEmpty()) {
			y = groupTitle(g, "Friend requests", x, y);
			for (final PolarSocial.Request r : in) {
				g.playerHead(null, r.name, x + 9, y + 3, 11);
				Ui.textMid(g, Fonts.ellipsize(r.name, Fonts.BODY, 70), x + 25, y + 8.5f, 0xFFFFFFFF, Fonts.BODY);
				if (inside) {
					smallButton(g, Icon.CHECK, null, x + W - 44, y + 3, 16, mx, my, GOOD, () -> act("accept", r.uuid));
					smallButton(g, Icon.CLOSE, null, x + W - 25, y + 3, 16, mx, my, BAD, () -> act("remove", r.uuid));
				}
				y += 18;
			}
		}
		List<PolarSocial.Friend> here = new ArrayList<PolarSocial.Friend>(), online = new ArrayList<PolarSocial.Friend>(), offline = new ArrayList<PolarSocial.Friend>();
		String mine = myServer();
		for (PolarSocial.Friend f : PolarSocial.friends()) {
			if (!q.isEmpty() && !f.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			if (!f.online) offline.add(f);
			else if (mine != null && f.server != null && f.server.equalsIgnoreCase(mine)) here.add(f);
			else online.add(f);
		}
		if (!here.isEmpty()) {
			y = groupTitle(g, "Here with you", x, y);
			for (PolarSocial.Friend f : here) y = friendRow(g, f, x, y, mx, my, inside, true);
		}
		if (!online.isEmpty()) {
			y = groupTitle(g, "Online", x, y);
			for (PolarSocial.Friend f : online) y = friendRow(g, f, x, y, mx, my, inside, false);
		}
		if (!offline.isEmpty()) {
			boolean open = offlineOpen || !q.isEmpty();
			Ui.icon(g, open ? Icon.EXPAND : Icon.CHEVRON_RIGHT, x + 11, y + 5, DIM, Fonts.ICON_S);
			Ui.textMid(g, "Offline (" + offline.size() + ")", x + 18, y + 5, DIM, Fonts.SMALL);
			if (inside) hits.add(new Hit(x + 4, y, W - 8, 11, () -> offlineOpen = !offlineOpen));
			y += 12;
			if (open) for (PolarSocial.Friend f : offline) y = friendRow(g, f, x, y, mx, my, inside, false);
		}
		if (PolarSocial.friends().isEmpty() && in.isEmpty()) {
			Ui.textCentered(g, PolarSocial.friendsKnown() ? "No friends yet" : "Loading friends...", x + W / 2, y + 14, 0xFFFFFFFF, Fonts.BODY);
			if (PolarSocial.friendsKnown()) Ui.textCentered(g, "Type a name above and press Enter.", x + W / 2, y + 26, MUTED, Fonts.SMALL);
			y += 34;
		} else if (!q.isEmpty() && here.isEmpty() && online.isEmpty() && offline.isEmpty()) {
			Ui.textCentered(g, "Press Enter to add " + search.value().trim(), x + W / 2, y + 12, MUTED, Fonts.SMALL);
			y += 24;
		}
		for (PolarSocial.Request r : PolarSocial.outgoing()) {
			if (r == PolarSocial.outgoing().get(0)) y = groupTitle(g, "Waiting for them", x, y);
			Ui.textMid(g, r.name, x + 12, y + 6, MUTED, Fonts.SMALL);
			final String u = r.uuid;
			if (inside) smallButton(g, null, "Cancel", x + W - 44, y, 36, mx, my, MUTED, () -> act("remove", u));
			y += 14;
		}
		g.endScissor();
		clampScroll(y + scroll - y0, bottom - y0);
	}

	private float friendRow(Gfx g, final PolarSocial.Friend f, float x, float y, int mx, int my, boolean inside, boolean hereWithYou) {
		float h = 20;
		boolean hv = inside && Ui.inside(mx, my, x + 4, y, W - 8, h);
		if (hv) Ui.rect(g, x + 4, y, W - 8, h, 4, 0x22FFFFFF);
		UUID id = null;
		try {
			id = UUID.fromString(f.uuid);
		} catch (IllegalArgumentException ignored) {
			// offline profile ids are still uuids; leave it to the name
		}
		g.playerHead(id, f.name, x + 9, y + 4, 12);
		Ui.circle(g, x + 20, y + 15, 2.4f, 0xFF091328);
		Ui.circle(g, x + 20, y + 15, 1.8f, statusColor(f.status));
		Ui.textMid(g, Fonts.ellipsize(f.name, Fonts.BODY, hv ? 62 : 110), x + 26, y + 7, f.online ? 0xFFFFFFFF : 0xFF9AAAC0, Fonts.BODY);
		String sub;
		if (!f.online) sub = lastSeen(f.lastSeen);
		else if ("busy".equals(f.status)) sub = "Busy";
		else if ("away".equals(f.status)) sub = "Away";
		else if (hereWithYou) sub = "On this server";
		else if (f.server != null) sub = p().hideServerAddresses() ? "On a server" : f.server;
		else if ("singleplayer".equals(f.where)) sub = "In singleplayer";
		else sub = "In the menus";
		Ui.textMid(g, Fonts.ellipsize(sub, Fonts.SMALL, hv ? 62 : 110), x + 26, y + 15, MUTED, Fonts.SMALL);
		if (f.unread > 0 && !hv) {
			String c = String.valueOf(f.unread);
			float cw = Fonts.width(c, Fonts.SMALL) + 6;
			Ui.rect(g, x + W - 12 - cw, y + 5.5f, cw, 9, 4.5f, BAD);
			Ui.textCentered(g, c, x + W - 12 - cw / 2, y + 10, 0xFF0B1426, Fonts.SMALL);
		}
		if (hv) {
			float bx = x + W - 10;
			bx -= 16;
			smallButton(g, Icon.CHAT, null, bx, y + 4, 14, mx, my, ACCENT, () -> openThread(f.uuid, f.name));
			String mine = myServer();
			if (f.online && mine != null && !hereWithYou && !"busy".equals(f.status)) {
				bx -= 16;
				smallButton(g, Icon.ADD, null, bx, y + 4, 14, mx, my, GOOD, () -> act("invite", f.uuid));
			}
			if (f.online && f.server != null && !hereWithYou) {
				bx -= 16;
				final String s = f.server;
				smallButton(g, Icon.PLAY, null, bx, y + 4, 14, mx, my, GOLD, () -> p().joinServer(s));
			}
			hits.add(new Hit(x + 4, y, bx - x - 6, h, () -> p().open(new ProfileCardView(p().currentScreen(), f.uuid, f.name))));
		}
		return y + h + 1;
	}

	private static String lastSeen(long t) {
		if (t <= 0) return "Offline";
		long m = (System.currentTimeMillis() - t) / 60000;
		if (m < 2) return "Seen just now";
		if (m < 60) return "Seen " + m + " min ago";
		long hrs = m / 60;
		if (hrs < 24) return "Seen " + hrs + (hrs == 1 ? " hour" : " hours") + " ago";
		long d = hrs / 24;
		return "Seen " + d + (d == 1 ? " day" : " days") + " ago";
	}

	private void act(String action, String uuid) {
		JsonObject b = new JsonObject();
		b.addProperty("uuid", uuid);
		if ("invite".equals(action) && myServer() != null) b.addProperty("server", myServer());
		PolarSocial.friendAction(action, b);
	}

	private void clampScroll(float contentH, float viewH) {
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
	}

	// ------------------------------------------------------------------ messages

	private void openThread(String uuid, String name) {
		tab = 1;
		thread = uuid;
		threadName = name;
		PolarSocial.openThread = uuid;
		PolarSocial.loadThread(uuid, null);
		dm.setValue("");
		dm.setFocused(true);
	}

	private void closeThread() {
		thread = null;
		PolarSocial.openThread = null;
		dm.setFocused(false);
	}

	private void messagesTab(Gfx g, float x, float top, float bottom, int mx, int my) {
		if (thread != null) {
			threadView(g, x, top, bottom, mx, my);
			return;
		}
		List<PolarSocial.Friend> list = new ArrayList<PolarSocial.Friend>(PolarSocial.friends());
		java.util.Collections.sort(list, (a, b) -> a.unread != b.unread ? b.unread - a.unread : Boolean.compare(b.online, a.online));
		g.scissor(Math.round(x), Math.round(top), Math.round(x + W), Math.round(bottom));
		float y = top - scroll;
		boolean inside = my >= top && my < bottom;
		for (final PolarSocial.Friend f : list) {
			boolean hv = inside && Ui.inside(mx, my, x + 4, y, W - 8, 18);
			if (hv) Ui.rect(g, x + 4, y, W - 8, 18, 4, 0x22FFFFFF);
			g.playerHead(null, f.name, x + 9, y + 3, 12);
			Ui.textMid(g, Fonts.ellipsize(f.name, Fonts.BODY, 100), x + 26, y + 9, f.unread > 0 ? 0xFFFFFFFF : 0xFFB8C8DE, f.unread > 0 ? Fonts.BOLD : Fonts.BODY);
			if (f.unread > 0) {
				String c = String.valueOf(f.unread);
				float cw = Fonts.width(c, Fonts.SMALL) + 6;
				Ui.rect(g, x + W - 12 - cw, y + 4.5f, cw, 9, 4.5f, BAD);
				Ui.textCentered(g, c, x + W - 12 - cw / 2, y + 9, 0xFF0B1426, Fonts.SMALL);
			}
			if (inside) hits.add(new Hit(x + 4, y, W - 8, 18, () -> openThread(f.uuid, f.name)));
			y += 19;
		}
		if (list.isEmpty()) {
			Ui.textCentered(g, "Add friends to message them.", x + W / 2, y + 12, MUTED, Fonts.SMALL);
			y += 24;
		}
		g.endScissor();
		clampScroll(y + scroll - top, bottom - top);
	}

	private void threadView(Gfx g, float x, float top, float bottom, int mx, int my) {
		boolean bh = Ui.inside(mx, my, x + 4, top - 2, 60, 14);
		Ui.icon(g, Icon.CHEVRON_LEFT, x + 11, top + 5, bh ? 0xFFFFFFFF : MUTED, Fonts.ICON_S);
		Ui.textMid(g, Fonts.ellipsize(threadName, Fonts.BOLD, (int) (W - 30)), x + 19, top + 5, 0xFFFFFFFF, Fonts.BOLD);
		hits.add(new Hit(x + 4, top - 2, W - 8, 14, this::closeThread));
		float listTop = top + 14, inputY = bottom - 14;
		field(g, dm, "Message " + threadName, x + 6, inputY, W - 12);
		List<PolarSocial.Message> msgs = PolarSocial.thread(thread);
		g.scissor(Math.round(x), Math.round(listTop), Math.round(x + W), Math.round(inputY - 4));
		if (msgs == null) {
			Ui.textCentered(g, "Loading...", x + W / 2, listTop + 20, MUTED, Fonts.SMALL);
		} else if (msgs.isEmpty()) {
			Ui.textCentered(g, "Say hi to " + threadName, x + W / 2, listTop + 20, MUTED, Fonts.SMALL);
			Ui.textCentered(g, "Messages work on any server.", x + W / 2, listTop + 31, DIM, Fonts.SMALL);
		} else {
			// newest at the bottom, just above the box
			float y = inputY - 6 + scroll;
			for (int i = msgs.size() - 1; i >= 0 && y > listTop - 40; i--) {
				PolarSocial.Message m = msgs.get(i);
				List<String> lines = wrap(m.text, Fonts.SMALL, 104);
				float bw = 0;
				for (String l : lines) bw = Math.max(bw, Fonts.width(l, Fonts.SMALL));
				bw += 12;
				float bh2 = lines.size() * 9 + 6;
				y -= bh2;
				float bx = m.mine ? x + W - 8 - bw : x + 8;
				Ui.rect(g, bx, y, bw, bh2, 5, m.mine ? 0xFF8FCBFF : 0xFF1E3868);
				for (int j = 0; j < lines.size(); j++) Ui.textMid(g, lines.get(j), bx + 6, y + 7.5f + j * 9, m.mine ? 0xFF0B1A33 : 0xFFFFFFFF, Fonts.SMALL);
				y -= 3;
			}
		}
		g.endScissor();
	}

	static List<String> wrap(String text, UiFont f, float w) {
		List<String> out = new ArrayList<String>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			while (Fonts.width(word, f) > w && word.length() > 1) {
				// a very long word: break it
				int n = word.length();
				while (n > 1 && Fonts.width(word.substring(0, n), f) > w) n--;
				if (line.length() > 0) {
					out.add(line.toString());
					line = new StringBuilder();
				}
				out.add(word.substring(0, n));
				word = word.substring(n);
			}
			String next = line.length() == 0 ? word : line + " " + word;
			if (Fonts.width(next, f) > w && line.length() > 0) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (line.length() > 0) out.add(line.toString());
		return out;
	}

	// ------------------------------------------------------------------ party

	private void partyTab(Gfx g, float x, float top, float bottom, int mx, int my) {
		PolarSocial.Party party = PolarSocial.party();
		float chatY = bottom - 14;
		float listBottom = party != null ? chatY - 4 : bottom;
		g.scissor(Math.round(x), Math.round(top), Math.round(x + W), Math.round(listBottom));
		float y = top - scroll;
		boolean inside = my >= top && my < listBottom;
		for (final PolarSocial.PartyInvite inv : PolarSocial.partyInvites()) {
			Ui.rect(g, x + 6, y, W - 12, 30, 5, 0x30FFD36B);
			Ui.textMid(g, Fonts.ellipsize(inv.from + " invited you", Fonts.BODY, (int) (W - 24)), x + 12, y + 8, 0xFFFFE6A8, Fonts.BODY);
			if (inside) {
				smallButton(g, null, "Join party", x + 12, y + 15, 60, mx, my, GOLD, () -> {
					JsonObject b = new JsonObject();
					b.addProperty("party", inv.party);
					PolarSocial.partyAction("join", b);
				});
				smallButton(g, null, "No thanks", x + 76, y + 15, 56, mx, my, MUTED, () -> {
					JsonObject b = new JsonObject();
					b.addProperty("party", inv.party);
					PolarSocial.partyAction("decline", b);
				});
			}
			y += 35;
		}
		final String meId = String.valueOf(p().userId());
		boolean leader = party != null && meId.equalsIgnoreCase(party.leader);
		String mine = myServer();
		if (party == null) {
			Ui.textMid(g, "You're not in a party", x + 9, y + 6, 0xFFFFFFFF, Fonts.BOLD);
			Ui.textMid(g, "Invite friends to play together across servers.", x + 9, y + 17, MUTED, Fonts.SMALL);
			y += 28;
			y = inviteList(g, x, y, mx, my, inside, null);
		} else {
			Ui.textMid(g, "Party", x + 9, y + 6, 0xFFFFFFFF, Fonts.BOLD);
			Ui.textRight(g, party.members.size() + " of 8", x + W - 10, y + 6, MUTED, Fonts.SMALL);
			y += 14;
			for (final PolarSocial.Member m : party.members) {
				boolean hv = inside && Ui.inside(mx, my, x + 4, y, W - 8, 18);
				if (hv) Ui.rect(g, x + 4, y, W - 8, 18, 4, 0x22FFFFFF);
				g.playerHead(null, m.name, x + 9, y + 3, 12);
				Ui.textMid(g, Fonts.ellipsize(m.name, Fonts.BODY, 80), x + 26, y + 9, m.online ? 0xFFFFFFFF : 0xFF9AAAC0, Fonts.BODY);
				float nx = x + 26 + Math.min(80, Fonts.width(m.name, Fonts.BODY)) + 4;
				if (m.leader) Ui.icon(g, Icon.STAR, nx + 3, y + 9, GOLD, Fonts.ICON_S);
				boolean away = m.online && mine != null && (m.server == null || !m.server.equalsIgnoreCase(mine)) && !meId.equalsIgnoreCase(m.uuid);
				if (!m.online) Ui.textRight(g, "Offline", x + W - 10, y + 9, DIM, Fonts.SMALL);
				else if (away && !(hv && leader)) Ui.textRight(g, "Not here", x + W - 10, y + 9, WARN, Fonts.SMALL);
				if (hv && leader && !m.leader) {
					smallButton(g, null, "Kick", x + W - 38, y + 3, 30, mx, my, BAD, () -> {
						JsonObject b = new JsonObject();
						b.addProperty("uuid", m.uuid);
						PolarSocial.partyAction("kick", b);
					});
				}
				y += 19;
			}
			for (PolarSocial.Request r : party.pending) {
				Ui.textMid(g, r.name, x + 26, y + 6, DIM, Fonts.SMALL);
				Ui.textRight(g, "Invited", x + W - 10, y + 6, DIM, Fonts.SMALL);
				y += 13;
			}
			y += 4;
			if (leader && mine != null) {
				final String server = mine;
				boolean hv = inside && Ui.inside(mx, my, x + 6, y, W - 12, 16);
				Ui.gradientRect(g, x + 6, y, W - 12, 16, 4, hv ? 0xFFFFFFFF : 0xFFD7EEFF, 0xFF8FCBFF);
				Ui.textCentered(g, "Bring the party here", x + W / 2, y + 8, 0xFF0B1A33, Fonts.BOLD);
				if (inside) hits.add(new Hit(x + 6, y, W - 12, 16, () -> {
					JsonObject b = new JsonObject();
					b.addProperty("server", server);
					PolarSocial.partyAction("warp", b);
				}));
				y += 20;
			}
			if (inside) {
				if (leader) smallButton(g, null, inviteOpen ? "Done inviting" : "Invite friends", x + 6, y, (W - 16) / 2, mx, my, ACCENT, () -> inviteOpen = !inviteOpen);
				smallButton(g, null, "Leave party", leader ? x + 10 + (W - 16) / 2 : x + 6, y, leader ? (W - 16) / 2 : W - 12, mx, my, BAD, () -> PolarSocial.partyAction("leave", null));
			}
			y += 18;
			if (inviteOpen && leader) y = inviteList(g, x, y, mx, my, inside, party);
		}
		g.endScissor();
		clampScroll(y + scroll - top, listBottom - top);
		if (party != null) field(g, partyChat, "Message the party", x + 6, chatY, W - 12);
	}

	private float inviteList(Gfx g, float x, float y, int mx, int my, boolean inside, PolarSocial.Party party) {
		int shown = 0;
		for (final PolarSocial.Friend f : PolarSocial.friends()) {
			if (!f.online) continue;
			if (party != null) {
				boolean in = false;
				for (PolarSocial.Member m : party.members) if (m.uuid.equalsIgnoreCase(f.uuid)) in = true;
				for (PolarSocial.Request r : party.pending) if (r.uuid.equalsIgnoreCase(f.uuid)) in = true;
				if (in) continue;
			}
			g.playerHead(null, f.name, x + 9, y + 3, 12);
			Ui.textMid(g, Fonts.ellipsize(f.name, Fonts.BODY, 90), x + 26, y + 9, 0xFFFFFFFF, Fonts.BODY);
			if (inside) smallButton(g, null, "Invite", x + W - 44, y + 3, 36, mx, my, GOOD, () -> {
				JsonObject b = new JsonObject();
				b.addProperty("uuid", f.uuid);
				PolarSocial.partyAction("invite", b);
			});
			y += 19;
			shown++;
		}
		if (shown == 0) {
			Ui.textMid(g, "No friends online to invite.", x + 9, y + 6, DIM, Fonts.SMALL);
			y += 14;
		}
		return y;
	}

	// ------------------------------------------------------------------ input

	private TextField active() {
		if (tab == 0) return search;
		if (tab == 1 && thread != null) return dm;
		if (tab == 2 && PolarSocial.party() != null) return partyChat;
		return null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx < x0()) {
			onClose(); // a click on the game closes the panel
			return true;
		}
		TextField f = active();
		if (f != null && f.mouseClicked(mx, my)) return true;
		if (f != null) f.setFocused(false);
		if (button != LEFT) return true;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
				p().playClickSound();
				h.run.run();
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (tab == 1 && thread != null) scroll = Math.max(0, scroll + (float) amount * 18);
		else scroll = Math.max(0, scroll - (float) amount * 18);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		TextField f = active();
		if (f != null && f.focused()) {
			if (key.is(KeyInput.Key.ESCAPE)) {
				f.setFocused(false);
				return true;
			}
			if (key.confirm()) {
				submit(f);
				return true;
			}
			return f.keyPressed(key);
		}
		if (key.is(KeyInput.Key.ESCAPE) && tab == 1 && thread != null) {
			closeThread();
			return true;
		}
		if (key.menuKey) {
			onClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char c) {
		TextField f = active();
		if (f != null && !f.focused() && c != ' ' && tab == 0) f.setFocused(true);
		return f != null && f.focused() && f.charTyped(c);
	}

	private void submit(TextField f) {
		String text = f.value().trim();
		if (text.isEmpty()) return;
		if (f == search) {
			for (PolarSocial.Friend fr : PolarSocial.friends()) if (fr.name.equalsIgnoreCase(text)) return;
			JsonObject b = new JsonObject();
			b.addProperty("name", text);
			PolarSocial.friendAction("add", b);
			search.setValue("");
		} else if (f == dm && thread != null) {
			PolarSocial.sendMessage(thread, text);
			dm.setValue("");
			scroll = 0;
		} else if (f == partyChat) {
			PolarSocial.partyChat(text);
			partyChat.setValue("");
		}
	}
}
