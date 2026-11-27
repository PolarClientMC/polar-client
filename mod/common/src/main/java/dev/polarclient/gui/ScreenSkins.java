package dev.polarclient.gui;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.util.Draw;
import dev.polarclient.modules.world.DeathWaypointModule;
import dev.polarclient.platform.Platform;
import dev.polarclient.world.LastServer;
import dev.polarclient.world.Partners;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Polar's look for a few vanilla screens: the death screen, connecting to a server and the disconnected screen. The
 * vanilla screen stays in charge: its buttons stay where they are and still do the clicking, Polar only draws them (and
 * the text around them) in its own style, and adds a few buttons of its own (Copy, Retry) as real buttons. Each version
 * calls {@link #render} from the start of the screen's render, and {@link #extras} at the end of its init.
 */
public final class ScreenSkins {
	private ScreenSkins() {
	}

	/** A button of the vanilla screen, in GUI units. */
	public static final class Widget {
		public final float x, y, w, h;
		public final String label;
		public final boolean active;

		public Widget(float x, float y, float w, float h, String label, boolean active) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.label = label == null ? "" : label;
			this.active = active;
		}
	}

	/** A button Polar adds to the screen. {@link #press} runs it. */
	public static final class Extra {
		public final String id, label;
		public final int x, y, w, h;

		Extra(String id, String label, float x, float y, float w, float h) {
			this.id = id;
			this.label = label;
			this.x = Math.round(x);
			this.y = Math.round(y);
			this.w = Math.round(w);
			this.h = Math.round(h);
		}
	}

	private static final int BG = 0xF00B1426, LINE = 0x479CC9FF, MUTED = 0xFF93A8C6, DIM = 0xFF5E7290, ACCENT = 0xFF5AD8FF,
			GOOD = 0xFF5FE3A1, BAD = 0xFFFF6B6B, GOLD = 0xFFFFD36B;

	private static Object current;
	private static long openedAt;
	private static List<Extra> extras = Collections.emptyList();
	private static String toast;
	private static long toastAt;
	// the death being shown
	private static double deathX, deathY, deathZ;
	private static String deathDim;
	private static boolean deathKept;
	// the disconnect being shown
	private static long retryAt;

	private static Platform p() {
		return Platform.get();
	}

	/** Whether Polar draws this screen. */
	public static boolean wants(Object screen) {
		if (screen == null || Polar.modules() == null || !Polar.modules().client.customGameScreens.on()) return false;
		return p().skinKind(screen) != null;
	}

	private static void opened(Object screen, String kind) {
		if (screen == current) return;
		current = screen;
		openedAt = System.currentTimeMillis();
		toast = null;
		if ("death".equals(kind)) {
			deathX = p().playerX();
			deathY = p().playerY();
			deathZ = p().playerZ();
			deathDim = p().dimension();
			deathKept = !p().armorAndHand(true).isEmpty();
			DeathWaypointModule m = deathModule();
			if (m != null && m.isEnabled()) m.set(deathX, deathY, deathZ, deathDim);
		} else if ("disconnected".equals(kind)) {
			retryAt = System.currentTimeMillis() + 10_000;
		}
	}

	private static DeathWaypointModule deathModule() {
		return Polar.modules() == null ? null : (DeathWaypointModule) Polar.modules().byId("death_waypoint");
	}

	private static float age() {
		return (System.currentTimeMillis() - openedAt) / 1000f;
	}

	// ------------------------------------------------------------------ the buttons Polar adds

	/** The buttons Polar adds to this screen, given the vanilla ones (call after the vanilla init). */
	public static List<Extra> extras(Object screen, int width, int height, List<Widget> buttons) {
		List<Extra> out = new ArrayList<Extra>();
		if (!wants(screen) || buttons.isEmpty()) {
			extras = out;
			return out;
		}
		String kind = p().skinKind(screen);
		opened(screen, kind);
		if ("death".equals(kind)) {
			float[] c = deathCard(width, height, buttons);
			if (c != null) {
				DeathWaypointModule m = deathModule();
				if (m != null && m.isEnabled()) out.add(new Extra("death_wp", "Waypoint", c[0] + c[2] - 114, c[1] + 18, 68, 14));
				out.add(new Extra("death_copy", "Copy", c[0] + c[2] - 42, c[1] + 18, 34, 14));
			}
		} else if ("disconnected".equals(kind)) {
			Widget top = topButton(buttons), bottom = bottomButton(buttons);
			float x = top.x, w = top.w;
			boolean lost = lost(screen);
			if (lost && LastServer.address() != null) out.add(new Extra("retry", "Retry", x, top.y - 24, w, 20));
			float y = bottom.y + bottom.h + 4;
			if (lost && LastServer.address() != null) {
				out.add(new Extra("copy", "Copy details", x, y, (w - 4) / 2, 16));
				out.add(new Extra("auto", "Try again by itself", x + (w + 4) / 2, y, (w - 4) / 2, 16));
			} else {
				out.add(new Extra("copy", lost ? "Copy details" : "Copy message", x, y, w, 16));
			}
		}
		extras = out;
		return out;
	}

	/** One of Polar's buttons was clicked. */
	public static void press(String id) {
		if ("death_copy".equals(id)) {
			p().setClipboard(block(deathX) + " " + block(deathY) + " " + block(deathZ));
			say("Coordinates copied");
		} else if ("death_wp".equals(id)) {
			DeathWaypointModule m = deathModule();
			if (m == null) return;
			if (m.lastDeath() != null) {
				m.clear();
				say("Death waypoint removed");
			} else {
				m.set(deathX, deathY, deathZ, deathDim);
				say("Death waypoint set at " + block(deathX) + ", " + block(deathY) + ", " + block(deathZ));
			}
		} else if ("retry".equals(id)) {
			retry();
		} else if ("copy".equals(id)) {
			String title = p().screenTitle(current), reason = reason(current);
			p().setClipboard((title == null ? "" : title + "\n") + (reason == null ? "" : reason)
					+ (LastServer.address() != null && !p().hideServerAddresses() ? "\nServer: " + LastServer.address() : ""));
			say(lost(current) ? "Error details copied" : "Message copied");
		} else if ("auto".equals(id)) {
			Polar.modules().client.autoRetry.toggle();
			retryAt = System.currentTimeMillis() + 10_000;
			Polar.saveConfig();
		}
	}

	private static void retry() {
		String a = LastServer.address();
		if (a != null) p().joinServer(a);
	}

	private static void say(String msg) {
		toast = msg;
		toastAt = System.currentTimeMillis();
	}

	private static int block(double v) {
		return (int) Math.floor(v);
	}

	// ------------------------------------------------------------------ drawing

	/** Draws the screen in Polar's style. False to let vanilla draw it after all. */
	public static boolean render(Gfx g, Object screen, int width, int height, List<Widget> buttons, int mouseX, int mouseY) {
		if (!wants(screen)) return false;
		String kind = p().skinKind(screen);
		opened(screen, kind);
		Ui.frame();
		g.nextLayer();
		if ("death".equals(kind)) death(g, screen, width, height, buttons, mouseX, mouseY);
		else if ("connect".equals(kind)) connect(g, screen, width, height, buttons, mouseX, mouseY);
		else disconnected(g, screen, width, height, buttons, mouseX, mouseY);
		drawToast(g, width);
		return true;
	}

	private static Extra extraAt(Widget w) {
		for (Extra e : extras) {
			if (Math.abs(e.x - w.x) < 1 && Math.abs(e.y - w.y) < 1 && Math.abs(e.w - w.w) < 1) return e;
		}
		return null;
	}

	/** A button: primary (light), plain (glass) or a danger one; dimmed while the game keeps it inactive. */
	private static void button(Gfx g, Widget w, String label, boolean primary, int mx, int my, float a) {
		boolean hv = w.active && Ui.inside(mx, my, w.x, w.y, w.w, w.h);
		float k = w.active ? a : a * 0.45f;
		float r = Math.min(5, w.h / 3f);
		if (primary) {
			Ui.gradientRect(g, w.x, w.y, w.w, w.h, r, Theme.alpha(hv ? 0xFFFFFFFF : 0xFFD7EEFF, k), Theme.alpha(0xFF8FCBFF, k));
			Ui.ring(g, w.x, w.y, w.w, w.h, r, Theme.alpha(0xD9EAF6FF, k), 1f);
		} else {
			Ui.rect(g, w.x, w.y, w.w, w.h, r, Theme.alpha(hv ? 0xD91E3868 : 0xC80C1A38, k));
			Ui.ring(g, w.x, w.y, w.w, w.h, r, Theme.alpha(hv ? 0xD9CFE9FF : 0x669CC9FF, k), 1f);
		}
		UiFont f = w.h >= 18 ? (primary ? Fonts.BOLD : Fonts.BODY) : Fonts.SMALL;
		Ui.textCentered(g, Fonts.ellipsize(label, f, (int) (w.w - 8)), w.x + w.w / 2f, w.y + w.h / 2f, Theme.alpha(primary ? 0xFF0B1A33 : 0xFFFFFFFF, k), f);
	}

	private static void card(Gfx g, float x, float y, float w, float h, float a) {
		Ui.rect(g, x, y, w, h, 7, Theme.alpha(BG, a));
		Ui.gradientRect(g, x + 1, y + 1, w - 2, 16, 6, Theme.alpha(0x1ADCECFF, a), 0x00DCECFF);
		Ui.ring(g, x, y, w, h, 7, Theme.alpha(LINE, a), 1f);
	}

	private static void backdrop(Gfx g, int width, int height, int mx, int my) {
		MenuBackground.INSTANCE.render(g, width, height, mx, my);
		Ui.gradientRect(g, 0, 0, width, height, 0, 0x59040A18, 0xCC040A18);
	}

	private static Widget topButton(List<Widget> buttons) {
		Widget t = buttons.get(0);
		for (Widget w : buttons) if (w.y < t.y) t = w;
		return t;
	}

	private static Widget bottomButton(List<Widget> buttons) {
		Widget t = buttons.get(0);
		for (Widget w : buttons) if (w.y > t.y) t = w;
		return t;
	}

	private static List<String> wrap(String text, UiFont f, float w, int maxLines) {
		List<String> out = new ArrayList<String>();
		for (String para : text.split("\n")) {
			StringBuilder line = new StringBuilder();
			for (String word : para.trim().split(" +")) {
				String next = line.length() == 0 ? word : line + " " + word;
				if (Fonts.width(next, f) > w && line.length() > 0) {
					out.add(line.toString());
					line = new StringBuilder(word);
				} else {
					line = new StringBuilder(next);
				}
			}
			if (line.length() > 0) out.add(line.toString());
		}
		if (out.size() > maxLines) {
			List<String> cut = new ArrayList<String>(out.subList(0, maxLines));
			cut.set(maxLines - 1, Fonts.ellipsize(cut.get(maxLines - 1) + " ...", f, (int) w));
			return cut;
		}
		return out;
	}

	private static void drawToast(Gfx g, int width) {
		if (toast == null) return;
		float age = (System.currentTimeMillis() - toastAt) / 1000f;
		if (age > 2.2f) {
			toast = null;
			return;
		}
		float a = Math.max(0, Math.min(1, Math.min(age / 0.15f, (2.2f - age) / 0.3f)));
		float tw = Fonts.width(toast, Fonts.BODY) + 30, x = width / 2f - tw / 2, y = 8;
		Ui.rect(g, x, y, tw, 18, 9, Theme.alpha(0xEB09142C, a));
		Ui.ring(g, x, y, tw, 18, 9, Theme.alpha(0x995AD8FF, a), 1f);
		Ui.icon(g, Icon.CHECK, x + 11, y + 9, Theme.alpha(ACCENT, a), Fonts.ICON_S);
		Ui.textMid(g, toast, x + 20, y + 9, Theme.alpha(0xFFFFFFFF, a), Fonts.BODY);
	}

	// ------------------------------------------------------------------ the death screen

	/** The "where you died" card: under the buttons when there is room, else above them. {x, y, w, h} or null. */
	private static float[] deathCard(int width, int height, List<Widget> buttons) {
		Widget top = topButton(buttons), bottom = bottomButton(buttons);
		float w = 220, h = 46, x = width / 2f - w / 2;
		float below = bottom.y + bottom.h + 8;
		if (below + h <= height - 4) return new float[] {x, below, w, h};
		float above = top.y - h - 6;
		if (above >= 108) return new float[] {x, above, w, h};
		return null;
	}

	private static void death(Gfx g, Object screen, int width, int height, List<Widget> buttons, int mx, int my) {
		float k = Ui.easeOut(age() / 0.3f), cx = width / 2f;
		// the game's red wash, a little softer at the centre so the text reads
		Ui.gradientRect(g, 0, 0, width, height, 0, Theme.alpha(0x8C780A14, k), Theme.alpha(0xD2460010, k));
		Ui.glow(g, cx, height * 0.35f, width * 0.45f, height * 0.35f, Theme.alpha(0x30000000, k));
		boolean hardcore = ScreenText.firstFlag(screen);
		String title = hardcore ? "Game over!" : "You died!";
		List<String> texts = ScreenText.texts(screen);
		String cause = null, score = null;
		for (String t : texts) {
			if (t == null || t.trim().isEmpty()) continue;
			if (score == null && t.toLowerCase(Locale.ROOT).contains("score")) score = t;
			else if (cause == null && !t.equals(title)) cause = t;
		}
		// where vanilla puts them: the title doubled at y 60, the cause at 85, the score at 100
		g.push();
		g.translate(cx, 60);
		g.scale(2);
		float tw = Draw.width(title);
		g.text(title, -tw / 2f, 0, Theme.alpha(0xFFFFFFFF, k), true);
		g.pop();
		if (cause != null) {
			float cw = Draw.width(cause);
			g.text(cause, cx - cw / 2f, 85, Theme.alpha(0xFFFFE0E0, k), true);
		}
		if (score != null) {
			float sw = Draw.width(score);
			g.text(score, cx - sw / 2f, 100, Theme.alpha(0xFFFFFFFF, k), true);
		}
		List<Widget> own = new ArrayList<Widget>();
		for (Widget w : buttons) if (extraAt(w) == null) own.add(w);
		float[] c = own.isEmpty() ? null : deathCard(width, height, own);
		if (c != null) deathInfo(g, c, mx, my, k);
		Widget first = null;
		for (Widget w : buttons) if (extraAt(w) == null && (first == null || w.y < first.y)) first = w;
		for (Widget w : buttons) {
			Extra e = extraAt(w);
			if (e != null) {
				button(g, w, extraLabel(e), false, mx, my, k);
			} else {
				button(g, w, w.label, w == first, mx, my, k);
			}
		}
	}

	private static void deathInfo(Gfx g, float[] c, int mx, int my, float k) {
		float x = c[0], y = c[1], w = c[2], h = c[3];
		Ui.rect(g, x, y, w, h, 6, Theme.alpha(0x99140410, k));
		Ui.ring(g, x, y, w, h, 6, Theme.alpha(0x40FFAAAA, k), 1f);
		Ui.icon(g, Icon.PIN_DROP, x + 11, y + 11, Theme.alpha(0xFFFF8A8A, k), Fonts.ICON_S);
		String where = "Where you died" + (deathDim == null ? "" : ", " + prettyDimension(deathDim));
		Ui.textMid(g, where, x + 20, y + 11, Theme.alpha(0xFFFFC8C8, k), Fonts.SMALL);
		DeathWaypointModule m = deathModule();
		if (m != null && m.isEnabled() && m.lastDeath() != null) {
			Ui.icon(g, Icon.CHECK, x + w - 12, y + 11, Theme.alpha(GOOD, k), Fonts.ICON_S);
			Ui.textRight(g, "Saved as a waypoint", x + w - 19, y + 11, Theme.alpha(0xFFBDF5D6, k), Fonts.SMALL);
		}
		float px = x + 10;
		String[][] parts = {{"X", String.valueOf(block(deathX))}, {"Y", String.valueOf(block(deathY))}, {"Z", String.valueOf(block(deathZ))}};
		for (String[] pt : parts) {
			Ui.textMid(g, pt[0], px, y + 25, Theme.alpha(0xFFFF9A9A, k), Fonts.SMALL);
			px += Fonts.width(pt[0], Fonts.SMALL) + 3;
			Ui.textMid(g, pt[1], px, y + 25, Theme.alpha(0xFFFFFFFF, k), Fonts.BOLD);
			px += Fonts.width(pt[1], Fonts.BOLD) + 7;
		}
		if (!deathKept) {
			int left = Math.max(0, 300 - (int) age());
			String s = String.format(Locale.ROOT, "Dropped items stay on the ground for %d:%02d", left / 60, left % 60);
			Ui.textMid(g, s, x + 10, y + 38, Theme.alpha(0xFFFFC8C8, k), Fonts.SMALL);
		}
	}

	private static String prettyDimension(String d) {
		if (d == null) return "";
		String s = d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
		if (s.equals("overworld")) return "Overworld";
		if (s.equals("the_nether")) return "Nether";
		if (s.equals("the_end")) return "The End";
		s = s.replace('_', ' ');
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String extraLabel(Extra e) {
		if ("death_wp".equals(e.id)) {
			DeathWaypointModule m = deathModule();
			return m != null && m.lastDeath() != null ? "Remove waypoint" : "Set waypoint";
		}
		if ("retry".equals(e.id)) {
			boolean auto = Polar.modules().client.autoRetry.on();
			if (!auto) return "Retry";
			long left = Math.max(0, (retryAt - System.currentTimeMillis() + 999) / 1000);
			return "Retry (" + left + ")";
		}
		if ("auto".equals(e.id)) return "Try again by itself";
		return e.label;
	}

	// ------------------------------------------------------------------ connecting

	private static final String[] STEPS = {"Connecting", "Logging in", "Joining the world"};

	private static int step(Object screen) {
		int s = 0;
		for (String t : ScreenText.texts(screen)) {
			String l = t.toLowerCase(Locale.ROOT);
			if (l.contains("join") || l.contains("configur") || l.contains("loading")) s = Math.max(s, 2);
			else if (l.contains("log") || l.contains("encrypt") || l.contains("auth")) s = Math.max(s, 1);
		}
		return s;
	}

	private static float stepAge;
	private static int lastStep = -1;

	private static void connect(Gfx g, Object screen, int width, int height, List<Widget> buttons, int mx, int my) {
		backdrop(g, width, height, mx, my);
		float k = Ui.easeOut(age() / 0.25f);
		float cw = 230, ch = 112;
		Widget cancel = buttons.isEmpty() ? null : bottomButton(buttons);
		float x = width / 2f - cw / 2, y = cancel != null ? cancel.y + cancel.h + 8 - ch : height / 2f - ch / 2;
		if (y < 4) y = 4;
		card(g, x, y, cw, ch, k);
		serverHeader(g, x, y, cw, k);
		int st = step(screen);
		if (st != lastStep) {
			lastStep = st;
			stepAge = age();
		}
		float t = age();
		for (int i = 0; i < STEPS.length; i++) {
			float yy = y + 46 + i * 12;
			int col;
			if (i < st) {
				Ui.circle(g, x + 16, yy, 3.6f, Theme.alpha(GOOD, k));
				Ui.icon(g, Icon.CHECK, x + 16, yy, Theme.alpha(0xFF05301C, k), Fonts.ICON_S);
				col = 0xFFFFFFFF;
			} else if (i == st) {
				// a turning arc: three dots chasing round
				for (int d = 0; d < 3; d++) {
					double ang = t * 6 + d * 0.9;
					Ui.circle(g, x + 16 + (float) Math.cos(ang) * 3f, yy + (float) Math.sin(ang) * 3f, 1.1f - d * 0.2f, Theme.alpha(ACCENT, k));
				}
				col = 0xFFFFFFFF;
			} else {
				Ui.circle(g, x + 16, yy, 1.4f, Theme.alpha(DIM, k));
				col = DIM;
			}
			Ui.textMid(g, STEPS[i], x + 25, yy, Theme.alpha(col, k), i == st ? Fonts.BOLD : Fonts.BODY);
		}
		// each step fills its third of the bar, easing towards the end of it while it runs
		float within = 1 - (float) Math.exp(-(t - stepAge) / 2.5f);
		float prog = (st + 0.9f * within) / STEPS.length;
		Ui.rect(g, x + 12, y + ch - 36, cw - 24, 3, 1.5f, Theme.alpha(0x22FFFFFF, k));
		Ui.rect(g, x + 12, y + ch - 36, (cw - 24) * prog, 3, 1.5f, Theme.alpha(ACCENT, k));
		for (Widget w : buttons) button(g, w, w.label, false, mx, my, k);
	}

	private static void serverHeader(Gfx g, float x, float y, float cw, float k) {
		String addr = LastServer.address();
		String name = LastServer.name() != null ? LastServer.name() : addr != null ? addr : "Server";
		boolean hide = p().hideServerAddresses();
		if (hide && name.equals(addr)) name = "Server";
		Partners.Partner partner = Partners.forAddress(addr);
		Ui.gradientRect(g, x + 10, y + 10, 26, 26, 5, Theme.alpha(0xFF2B5C9E, k), Theme.alpha(0xFF15305A, k));
		Ui.ring(g, x + 10, y + 10, 26, 26, 5, Theme.alpha(0x2EFFFFFF, k), 1f);
		Ui.textCentered(g, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), x + 23, y + 23, Theme.alpha(0xFFFFFFFF, k), Fonts.TITLE);
		Ui.textMid(g, Fonts.ellipsize(name, Fonts.BOLD, (int) (cw - 110)), x + 44, y + 17, Theme.alpha(0xFFFFFFFF, k), Fonts.BOLD);
		if (addr != null && !hide && !addr.equals(name)) Ui.textMid(g, Fonts.ellipsize(addr, Fonts.SMALL, (int) (cw - 110)), x + 44, y + 28, Theme.alpha(MUTED, k), Fonts.SMALL);
		if (partner != null) Ui.textRight(g, "Partner server", x + cw - 10, y + 17, Theme.alpha(GOLD, k), Fonts.SMALL);
	}

	// ------------------------------------------------------------------ disconnected / kicked

	private static String reason(Object screen) {
		String title = p().screenTitle(screen);
		for (String t : ScreenText.texts(screen)) {
			if (t == null || t.trim().isEmpty() || t.equals(title)) continue;
			String l = t.toLowerCase(Locale.ROOT);
			if (l.startsWith("back to") || l.equals("ok") || l.equals("done")) continue;
			return t.trim();
		}
		return null;
	}

	private static final String[] LOST = {"timed out", "timeout", "refused", "connection reset", "unknown host", "unresolved",
			"internal exception", "end of stream", "no further information", "network is unreachable", "lost connection",
			"io.netty", "java.", "failed to connect", "connection closed", "can't resolve"};

	/** True when the connection broke (Retry makes sense); false when the server removed you on purpose. */
	private static boolean lost(Object screen) {
		String r = reason(screen);
		if (r == null) return true;
		String l = r.toLowerCase(Locale.ROOT);
		for (String s : LOST) if (l.contains(s)) return true;
		return false;
	}

	/** {headline, explanation} for an error, in plain words. */
	private static String[] explain(String reason) {
		String l = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
		if (l.contains("timed out") || l.contains("timeout"))
			return new String[] {"Connection timed out", "The server didn't answer in time. It may be restarting or offline, or your internet dropped for a moment."};
		if (l.contains("refused"))
			return new String[] {"Connection refused", "Nothing answered at that address. The server may be offline, or the address may be wrong."};
		if (l.contains("unknown host") || l.contains("unresolved") || l.contains("can't resolve"))
			return new String[] {"Address not found", "That address doesn't lead to a server. Check it for typos."};
		if (l.contains("reset") || l.contains("end of stream") || l.contains("internal exception") || l.contains("closed"))
			return new String[] {"The connection dropped", "The link to the server broke. The server may have restarted, or your internet had a hiccup."};
		return new String[] {"Lost connection", "The connection to the server ended unexpectedly."};
	}

	private static void disconnected(Gfx g, Object screen, int width, int height, List<Widget> buttons, int mx, int my) {
		backdrop(g, width, height, mx, my);
		float k = Ui.easeOut(age() / 0.25f);
		boolean lost = lost(screen);
		String reason = reason(screen);
		boolean auto = lost && Polar.modules().client.autoRetry.on() && LastServer.address() != null;
		if (auto && System.currentTimeMillis() >= retryAt) {
			retryAt = System.currentTimeMillis() + 10_000;
			retry();
		}
		float cw = 230;
		Widget topW = buttons.isEmpty() ? null : topButton(buttons), botW = buttons.isEmpty() ? null : bottomButton(buttons);
		List<String> lines;
		String head = null;
		if (lost) {
			String[] ex = explain(reason);
			head = ex[0];
			lines = wrap(ex[1], Fonts.SMALL, cw - 24, 3);
		} else {
			lines = wrap(reason == null ? "" : reason, Fonts.BODY, cw - 36, 8);
		}
		// header 0..40, the title at 52, then the error box and its words
		float textH = lost ? 84 + lines.size() * 9 : 72 + lines.size() * 10 + 8;
		float buttonsTop = topW != null ? topW.y : height / 2f;
		float buttonsBottom = botW != null ? botW.y + botW.h : height / 2f;
		float x = width / 2f - cw / 2;
		float y = buttonsTop - 8 - textH;
		if (y < 4) y = 4;
		float ch = buttonsBottom + 8 - y;
		card(g, x, y, cw, ch, k);
		serverHeader(g, x, y, cw, k);
		String title = lost ? "Couldn't connect" : "You were kicked";
		String vt = p().screenTitle(screen);
		if (lost && vt != null && vt.toLowerCase(Locale.ROOT).contains("lost")) title = "Connection lost";
		Ui.textMid(g, title, x + 12, y + 52, Theme.alpha(0xFFFFFFFF, k), Fonts.LARGE);
		if (lost) {
			Ui.circle(g, x + cw - 20, y + 23, 8, Theme.alpha(0x2EFF6B6B, k));
			Ui.icon(g, Icon.WARNING, x + cw - 20, y + 23, Theme.alpha(BAD, k), Fonts.ICON_M);
			float ty = y + 62;
			Ui.rect(g, x + 12, ty, cw - 24, 16, 3, Theme.alpha(0x1AFF6B6B, k));
			Ui.ring(g, x + 12, ty, cw - 24, 16, 3, Theme.alpha(0x59FF6B6B, k), 1f);
			Ui.textMid(g, head, x + 18, ty + 8, Theme.alpha(0xFFFFB8AE, k), Fonts.BOLD);
			for (int i = 0; i < lines.size(); i++) Ui.textMid(g, lines.get(i), x + 12, y + 88 + i * 9, Theme.alpha(0xFFC9D6E8, k), Fonts.SMALL);
		} else {
			Ui.textMid(g, "The server says:", x + 12, y + 66, Theme.alpha(MUTED, k), Fonts.SMALL);
			float ty = y + 72, bh = lines.size() * 10 + 8;
			Ui.rect(g, x + 12, ty, cw - 24, bh, 3, Theme.alpha(0x14FFC15E, k));
			Ui.ring(g, x + 12, ty, cw - 24, bh, 3, Theme.alpha(0x4DFFC15E, k), 1f);
			for (int i = 0; i < lines.size(); i++) Ui.textMid(g, lines.get(i), x + 18, ty + 9 + i * 10, Theme.alpha(0xFFFFE6A8, k), Fonts.BODY);
		}
		for (Widget w : buttons) {
			Extra e = extraAt(w);
			if (e == null) {
				// the game's own button: the main one when the server kicked you, the second when Retry is there
				button(g, w, w.label, !lost || LastServer.address() == null, mx, my, k);
			} else if ("auto".equals(e.id)) {
				toggle(g, w, Polar.modules().client.autoRetry.on(), mx, my, k);
			} else {
				button(g, w, extraLabel(e), "retry".equals(e.id), mx, my, k);
			}
		}
	}

	private static void toggle(Gfx g, Widget w, boolean on, int mx, int my, float k) {
		boolean hv = Ui.inside(mx, my, w.x, w.y, w.w, w.h);
		Ui.rect(g, w.x, w.y, w.w, w.h, 5, Theme.alpha(hv ? 0x991E3868 : 0x660C1A38, k));
		float sx = w.x + 6, sy = w.y + w.h / 2f - 4;
		Ui.rect(g, sx, sy, 15, 8, 4, Theme.alpha(on ? ACCENT : 0x44FFFFFF, k));
		Ui.circle(g, sx + (on ? 11 : 4), sy + 4, 3, Theme.alpha(0xFFFFFFFF, k));
		Ui.textMid(g, Fonts.ellipsize("Try again by itself", Fonts.SMALL, (int) (w.w - 28)), sx + 20, w.y + w.h / 2f, Theme.alpha(0xFFE4ECF7, k), Fonts.SMALL);
	}
}
