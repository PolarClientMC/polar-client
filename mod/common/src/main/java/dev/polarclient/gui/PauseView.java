package dev.polarclient.gui;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.platform.TabListView;
import dev.polarclient.util.Draw;
import dev.polarclient.util.OsOpen;
import dev.polarclient.world.Partners;
import dev.polarclient.world.SessionStats;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Polar's pause menu (Esc). Left: the logo, where you are, Back to game, the vanilla screens, leave, and quick
 * tiles. Right: cards for the server or world, this session's numbers. The world stays visible behind, blurred
 * and darker on the left. Drawn in design units (a 640x360 reference) scaled to whole screen pixels.
 */
public class PauseView extends View {
	private static final String POLAR_DISCORD = "https://discord.gg/3HxaywjbT4";
	private static final float PAD = 26, LW = 214, RW = 214, BH = 24, GAP = 6, R = 6;
	private static final float REF_W = 600, REF_H = 340;

	private final long openedAt = System.currentTimeMillis();
	private float zoom = 1, dw, dh;
	private final List<Hit> hits = new ArrayList<Hit>();
	private final Map<String, Float> hover = new HashMap<String, Float>();
	private String toast;
	private long toastAt;
	private float snowT;
	private long lastFrame = System.nanoTime();
	private static final int FLAKES = 70;
	private final float[] fx = new float[FLAKES], fy = new float[FLAKES], fz = new float[FLAKES], fp = new float[FLAKES];

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

	public PauseView() {
		super(null);
		java.util.Random r = new java.util.Random(9);
		for (int i = 0; i < FLAKES; i++) {
			fx[i] = r.nextFloat();
			fy[i] = r.nextFloat();
			fz[i] = r.nextFloat();
			fp[i] = r.nextFloat() * 6.28f;
		}
	}

	/**
	 * Whether a vanilla pause screen shows its menu (F3+Esc pauses without one, and keeps vanilla's).
	 * Read by type, not name: the flag is PauseScreen's only boolean field in every version.
	 */
	public static boolean showsMenu(Object pauseScreen) {
		try {
			for (Field f : pauseScreen.getClass().getDeclaredFields()) {
				if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);
					return f.getBoolean(pauseScreen);
				}
			}
		} catch (Exception ignored) {
			// fall through
		}
		return true;
	}

	/** Called by each version's screen swap: the Polar pause menu replaces vanilla's when this returns true. */
	public static boolean replaces(Object pauseScreen) {
		return Polar.modules() != null && Polar.modules().client.customPauseMenu.on() && p().hasPolarPauseMenu() && showsMenu(pauseScreen);
	}

	@Override
	public String title() {
		return "Game Menu";
	}

	private void layout() {
		int gs = Math.max(1, p().guiScale());
		int px = (int) Math.floor(Math.min(width * gs / REF_W, height * gs / REF_H));
		zoom = px >= 1 ? Math.min(1f, px / (float) gs) : Math.min(width / REF_W, height / REF_H);
		dw = width / zoom;
		dh = height / zoom;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
		g.blurBackground();
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY, float partial) {
		Ui.frame();
		layout();
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		float age = (System.currentTimeMillis() - openedAt) / 1000f;
		float k = Ui.easeOut(age / 0.35f);
		float mx = mouseX / zoom, my = mouseY / zoom;
		hits.clear();

		g.push();
		g.scale(zoom);
		Ui.setZoom(zoom);
		try {
			// shade: darker on the left where the menu is, the world still readable on the right
			float mid = dw * 0.45f;
			Ui.hGradient(g, 0, 0, mid, dh, Theme.alpha(0xFF030814, 0.78f * k), Theme.alpha(0xFF030814, 0.45f * k));
			Ui.hGradient(g, mid, 0, dw - mid, dh, Theme.alpha(0xFF030814, 0.45f * k), Theme.alpha(0xFF030814, 0.55f * k));
			if (Polar.modules().client.snow.on()) drawSnow(g, dt, k);
			drawLeft(g, mx, my, k);
			drawRight(g, mx, my, age, k);
			drawToast(g);
		} finally {
			Ui.setZoom(1f);
			g.pop();
		}
	}

	private float hov(String id, float mx, float my, float x, float y, float w, float h) {
		boolean on = Ui.inside(mx, my, x, y, w, h);
		Float v = hover.get(id);
		float nv = Ui.approach(v == null ? 0 : v, on ? 1 : 0, 16f);
		hover.put(id, nv);
		return nv;
	}

	private void say(String msg) {
		toast = msg;
		toastAt = System.currentTimeMillis();
	}

	private void glassPanel(Gfx g, float x, float y, float w, float h, float r, float a) {
		Ui.rect(g, x, y, w, h, r, Theme.alpha(0xFF09142C, a));
		Ui.gradientRect(g, x + 1, y + 1, w - 2, 14, Math.max(0, r - 1), 0x1ADCECFF, 0x00DCECFF);
		Ui.ring(g, x, y, w, h, r, 0x479CC9FF, 1f);
	}

	private void bigButton(Gfx g, String id, String icon, String label, float x, float y, float w, boolean primary, boolean danger,
						   float mx, float my, Runnable run) {
		float hv = hov(id, mx, my, x, y, w, BH);
		if (primary) {
			for (int k = 4; k >= 1; k--) {
				float e = k * 1.5f;
				Ui.rect(g, x - e, y - e, w + 2 * e, BH + 2 * e, R + e, Theme.alpha(0xFF7CC4FF, (0.05f + 0.04f * hv) * (5 - k) / 4f));
			}
			Ui.gradientRect(g, x, y, w, BH, R, Theme.mix(0xFFD7EEFF, 0xFFFFFFFF, 0.3f * hv), 0xFF8FCBFF);
			Ui.ring(g, x, y, w, BH, R, 0xD9EAF6FF, 1.2f);
		} else {
			int base = danger ? 0xB82C0E18 : 0xB80C1A38, hi = danger ? 0xD9581626 : 0xD9162C56;
			Ui.rect(g, x, y, w, BH, R, Theme.lerp(base, hi, hv));
			int edge = danger ? Theme.alpha(0xFFFF8C96, 0.35f + 0.4f * hv) : Theme.lerp(0x669CC9FF, 0xD9CFE9FF, hv);
			Ui.ring(g, x, y, w, BH, R, edge, 1.2f);
		}
		int fg = primary ? 0xFF0B1A33 : danger ? 0xFFFFD9DD : 0xFFFFFFFF;
		float midY = y + BH / 2f;
		boolean full = w > 120;
		Ui.icon(g, icon, x + (full ? 16 : 13), midY, fg, Fonts.ICON_M);
		Ui.textMid(g, label, x + (full ? 30 : 26), midY, fg, primary ? Fonts.BOLD : Fonts.BODY);
		if (full) Ui.icon(g, Icon.CHEVRON_RIGHT, x + w - 12 + 1.5f * hv, midY, Theme.alpha(fg, 0.8f), Fonts.ICON_M);
		if (primary) Ui.textRight(g, "Esc", x + w - 24, midY, Theme.alpha(fg, 0.55f), Fonts.SMALL);
		hits.add(new Hit(x, y, w, BH, run));
	}

	private void smallButton(Gfx g, String id, String icon, String label, float x, float y, float w, float mx, float my, Runnable run) {
		float hv = hov(id, mx, my, x, y, w, 18);
		Ui.rect(g, x, y, w, 18, 5, Theme.lerp(0xCC102042, 0xCC1E3868, hv));
		Ui.ring(g, x, y, w, 18, 5, Theme.lerp(0x4D9CC9FF, 0xB39CC9FF, hv), 1f);
		Ui.icon(g, icon, x + 10, y + 9, 0xFFDCEBFF, Fonts.ICON_S);
		Ui.textMid(g, label, x + 19, y + 9, 0xFFFFFFFF, Fonts.SMALL);
		hits.add(new Hit(x, y, w, 18, run));
	}

	private void drawLeft(Gfx g, float mx, float my, float k) {
		boolean sp = p().isSingleplayer();
		float lx = PAD - (1 - k) * 24;
		float lh = 30, lw = lh * Draw.WORDMARK_ASPECT;
		Draw.texture(g, Draw.WORDMARK, Math.round(lx - 2), Math.round(PAD - 2), Math.round(lw), Math.round(lh));
		Ui.textMid(g, "Game paused", lx, PAD + 42, 0xFFFFFFFF, Fonts.LARGE);
		Ui.textMid(g, where(sp), lx, PAD + 55, 0xFF9DB4D6, Fonts.SMALL);

		final Object me = p().currentScreen();
		float y = PAD + 68, half = (LW - GAP) / 2;
		bigButton(g, "back", Icon.PLAY, "Back to game", lx, y, LW, true, false, mx, my, this::onClose);
		y += BH + GAP;
		bigButton(g, "options", Icon.SETTINGS, "Options", lx, y, half, false, false, mx, my, () -> p().openOptions(me));
		bigButton(g, "mods", Icon.MODS, "Polar mods", lx + half + GAP, y, half, false, false, mx, my, () -> p().open(new PolarMenuView(me)));
		y += BH + GAP;
		bigButton(g, "adv", Icon.TROPHY, "Advancements", lx, y, half, false, false, mx, my, () -> {
			if (!p().openAdvancements(me)) say("Advancements aren't available here");
		});
		bigButton(g, "stats", Icon.BAR_CHART, "Statistics", lx + half + GAP, y, half, false, false, mx, my, () -> {
			if (!p().openStatistics(me)) say("Statistics aren't available here");
		});
		y += BH + GAP;
		if (sp && p().canOpenToLan()) {
			bigButton(g, "lan", Icon.LAN, "Open to LAN", lx, y, LW, false, false, mx, my, () -> p().openToLan(me));
			y += BH + GAP;
		}
		y += 10;
		bigButton(g, "quit", Icon.POWER, sp ? "Save and quit to title" : "Disconnect", lx, y, LW, false, true, mx, my, () -> p().leaveWorld());
		y += BH + 16;

		// quick tiles
		String[][] tiles = {{Icon.DASHBOARD, "Edit HUD"}, {Icon.CHECKROOM, "Wardrobe"}, {Icon.CAMERA, "Screenshots"}};
		int n = tiles.length;
		float tw = (LW - GAP * (n - 1)) / n, th = 40;
		for (int i = 0; i < n; i++) {
			float x = lx + i * (tw + GAP);
			float hv = hov("tile" + i, mx, my, x, y, tw, th);
			glassPanel(g, x, y, tw, th, 7, 0.6f + 0.2f * hv);
			if (hv > 0.01f) Ui.ring(g, x, y, tw, th, 7, Theme.alpha(0xFFCFE9FF, 0.6f * hv), 1.2f);
			Ui.icon(g, tiles[i][0], x + tw / 2, y + 15 - 1.5f * hv, 0xFFFFFFFF, Fonts.ICON_M);
			Ui.textCentered(g, tiles[i][1], x + tw / 2, y + 30, 0xFFDCE8FA, Fonts.SMALL);
			final int idx = i;
			hits.add(new Hit(x, y, tw, th, () -> tile(idx, me)));
		}

		// footer links
		float fy = dh - PAD + 6;
		String[] links = {"Send feedback", "Report a bug"};
		float x = lx;
		for (int i = 0; i < links.length; i++) {
			float w = Fonts.width(links[i], Fonts.SMALL);
			float hv = hov("f" + i, mx, my, x, fy - 6, w, 12);
			int c = hv > 0.5f ? 0xFFFFFFFF : 0xFF8FA6C8;
			Ui.textMid(g, links[i], x, fy, c, Fonts.SMALL);
			Christmas.rect(g, x, fy + 5, w, 0.6f, hv > 0.5f ? 0xFFFFFFFF : 0x808FA6C8);
			hits.add(new Hit(x, fy - 6, w, 12, () -> OsOpen.url(POLAR_DISCORD)));
			x += w + 14;
		}
	}

	private void tile(int i, Object me) {
		if (i == 0) p().open(new HudEditorView(me));
		else if (i == 1) p().open(new WardrobeView(me));
		else {
			try {
				OsOpen.folder(FabricLoader.getInstance().getGameDir().resolve("screenshots").toFile());
				say("Opened your screenshots folder");
			} catch (Exception e) {
				say("Couldn't open the screenshots folder");
			}
		}
	}

	private String where(boolean sp) {
		if (sp) {
			String w = p().worldName();
			return w == null ? "Singleplayer" : "Singleplayer world: " + w;
		}
		return "Playing on " + serverLabel();
	}

	private String serverLabel() {
		String name = p().serverName(), addr = p().serverAddress();
		if (name != null && !name.trim().isEmpty() && !name.equals("Minecraft Server")) return name;
		Partners.Partner pt = Partners.forAddress(addr);
		if (pt != null && pt.name != null) return pt.name;
		return addr == null || addr.isEmpty() || p().hideServerAddresses() ? "a server" : addr;
	}

	private void drawRight(Gfx g, float mx, float my, float age, float k) {
		boolean sp = p().isSingleplayer();
		float rx = dw - PAD - RW;
		float y = PAD;
		y = card(g, 0, age, k, rx, y, sp ? 52 : 96, sp ? 0 : 1, mx, my);
		card(g, 1, age, k, rx, y, 66, 2, mx, my);
	}

	/** Draws card {@code kind} (0 world, 1 server, 2 session) sliding in after the ones before it; returns the next y. */
	private float card(Gfx g, int i, float age, float k, float x, float y, float h, int kind, float mx, float my) {
		float e = Ui.easeOut(Math.max(0, Math.min(1, (age - i * 0.06f) / 0.3f))) * k;
		if (e <= 0.01f) return y + h + 10;
		g.push();
		g.translate((1 - e) * 16, 0);
		glassPanel(g, x, y, RW, h, 8, 0.66f * e);
		if (kind == 0) worldCard(g, x, y, e);
		else if (kind == 1) serverCard(g, x, y, e, mx - (1 - e) * 16, my);
		else sessionCard(g, x, y, e);
		g.pop();
		return y + h + 10;
	}

	private void worldCard(Gfx g, float x, float y, float a) {
		Ui.gradientRect(g, x + 10, y + 10, 32, 16, 5, Theme.alpha(0xFF3E9A5E, a), Theme.alpha(0xFF2C7A4A, a));
		Ui.gradientRect(g, x + 10, y + 26, 32, 16, 5, Theme.alpha(0xFF6B4A2E, a), Theme.alpha(0xFF4A3220, a));
		String name = p().worldName();
		Ui.textMid(g, name == null ? "Singleplayer" : name, x + 52, y + 19, Theme.alpha(0xFFFFFFFF, a), Fonts.BOLD);
		Ui.textMid(g, p().dimension() == null ? "" : prettyDimension(p().dimension()), x + 52, y + 31, Theme.alpha(0xFF9DB4D6, a), Fonts.SMALL);
	}

	private static String prettyDimension(String d) {
		String s = d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
		s = s.replace('_', ' ');
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private void serverCard(Gfx g, float x, float y, float a, float mx, float my) {
		final String addr = p().serverAddress();
		boolean hide = p().hideServerAddresses();
		Partners.Partner partner = Partners.forAddress(addr);
		String name = serverLabel();
		// a lettered tile in place of the server icon
		Ui.gradientRect(g, x + 10, y + 10, 34, 34, 6, Theme.alpha(0xFF2B5C9E, a), Theme.alpha(0xFF15305A, a));
		Ui.ring(g, x + 10, y + 10, 34, 34, 6, Theme.alpha(0x2EFFFFFF, a), 1f);
		String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
		Ui.textCentered(g, letter, x + 27, y + 27, Theme.alpha(0xFFFFFFFF, a), Fonts.TITLE);
		Ui.textMid(g, Fonts.ellipsize(name, Fonts.BOLD, partner != null ? 90 : 150), x + 52, y + 18, Theme.alpha(0xFFFFFFFF, a), Fonts.BOLD);
		if (partner != null) {
			float bx = x + 56 + Math.min(90, Fonts.width(name, Fonts.BOLD)), bw = Fonts.width("Partner", Fonts.SMALL) + 16;
			Ui.rect(g, bx, y + 13, bw, 10, 5, Theme.alpha(0x295AD8FF, a));
			Ui.ring(g, bx, y + 13, bw, 10, 5, Theme.alpha(0xB35AD8FF, a), 0.8f);
			Ui.icon(g, Icon.STAR, bx + 7, y + 18, Theme.alpha(0xFF5AD8FF, a), Fonts.ICON_S);
			Ui.textMid(g, "Partner", bx + 12, y + 18, Theme.alpha(0xFF5AD8FF, a), Fonts.SMALL);
		}
		if (!hide && addr != null) Ui.textMid(g, addr, x + 52, y + 30, Theme.alpha(0xFF9DB4D6, a), Fonts.SMALL);
		int ms = p().ping();
		if (ms >= 0) {
			pingBars(g, x + 52, y + 43, ms, a);
			Ui.textMid(g, ms + " ms", x + 67, y + 39.5f, Theme.alpha(0xFFDCE8FA, a), Fonts.SMALL);
		}
		TabListView tab = p().tabList();
		if (tab != null && !tab.entries.isEmpty()) {
			Ui.icon(g, Icon.GROUPS, x + 104, y + 39.5f, Theme.alpha(0xFF9DB4D6, a), Fonts.ICON_S);
			Ui.textMid(g, tab.entries.size() + " online", x + 111, y + 39.5f, Theme.alpha(0xFFDCE8FA, a), Fonts.SMALL);
		}
		Christmas.rect(g, x + 10, y + 54, RW - 20, 1, Theme.alpha(0x249CC9FF, a));
		float bw3 = (RW - 26) / 2;
		if (!hide && addr != null) {
			smallButton(g, "copy", Icon.COPY, "Copy address", x + 10, y + 64, bw3, mx, my, () -> {
				p().setClipboard(addr);
				say("Copied " + addr);
			});
		}
		if (partner != null && partner.discord != null) {
			final String discord = partner.discord;
			smallButton(g, "discord", Icon.CHAT, "Server Discord", x + 16 + bw3, y + 64, bw3, mx, my, () -> OsOpen.url(discord));
		}
	}

	private void pingBars(Gfx g, float x, float y, int ms, float a) {
		int lvl = ms <= 0 ? 0 : ms < 60 ? 4 : ms < 120 ? 3 : ms < 220 ? 2 : 1;
		int col = lvl >= 3 ? 0xFF3DDC84 : lvl == 2 ? 0xFFFFC857 : 0xFFFF5A5A;
		for (int i = 0; i < 4; i++) {
			float bh = 3 + i * 2;
			Christmas.rect(g, x + i * 3.2f, y - bh, 2.2f, bh, Theme.alpha(i < lvl ? col : 0x2EFFFFFF, a));
		}
	}

	private void sessionCard(Gfx g, float x, float y, float a) {
		Ui.textMid(g, "This session", x + 10, y + 12, Theme.alpha(0xFFFFFFFF, a), Fonts.BOLD);
		float cw = (RW - 20) / 2;
		stat(g, x + 10, y + 20, Icon.CLOCK, SessionStats.playTime(), "Play time", 0xFF9FD4FF, a);
		stat(g, x + 10 + cw, y + 20, Icon.TOKEN, "+" + SessionStats.coins(), "Coins earned", 0xFFFFD36B, a);
		stat(g, x + 10, y + 42, Icon.SWORDS, String.valueOf(SessionStats.kills()), "Kills", 0xFFFF8F8F, a);
		stat(g, x + 10 + cw, y + 42, Icon.SKULL, String.valueOf(SessionStats.deaths()), "Deaths", 0xFFC9D6EA, a);
	}

	private void stat(Gfx g, float x, float y, String icon, String value, String label, int c, float a) {
		Ui.icon(g, icon, x + 8, y + 9, Theme.alpha(c, a), Fonts.ICON_M);
		Ui.textMid(g, value, x + 18, y + 6.5f, Theme.alpha(0xFFFFFFFF, a), Fonts.BOLD);
		Ui.textMid(g, label, x + 18, y + 15.5f, Theme.alpha(0xFF9DB4D6, a), Fonts.SMALL);
	}

	private void drawSnow(Gfx g, float dt, float k) {
		snowT += dt;
		for (int i = 0; i < FLAKES; i++) {
			float z = fz[i];
			fy[i] += (0.012f + z * 0.035f) * dt;
			if (fy[i] > 1.05f) {
				fy[i] = -0.05f;
				fx[i] = (fx[i] * 7.31f + 0.37f) % 1f;
			}
			float x = fx[i] * dw + (float) Math.sin(snowT * (0.5f + z) + fp[i]) * (4 + z * 8), y = fy[i] * dh;
			if (z > 0.9f) Ui.glow(g, x, y, 2.4f, 2.4f, Theme.alpha(0xFFFFFFFF, 0.35f * k));
			else Ui.circle(g, x, y, 0.35f + z * 0.75f, Theme.alpha(0xFFFFFFFF, (0.25f + z * 0.4f) * k));
		}
	}

	private void drawToast(Gfx g) {
		if (toast == null) return;
		float age = (System.currentTimeMillis() - toastAt) / 1000f;
		if (age > 2.2f) {
			toast = null;
			return;
		}
		float a = Math.max(0, Math.min(1, Math.min(age / 0.15f, (2.2f - age) / 0.3f)));
		float tw = Fonts.width(toast, Fonts.BODY) + 34, x = dw / 2 - tw / 2, y = PAD - 8 - (1 - a) * 8;
		Ui.rect(g, x, y, tw, 20, 10, Theme.alpha(0xEB09142C, a));
		Ui.ring(g, x, y, tw, 20, 10, Theme.alpha(0x995AD8FF, a), 1f);
		Ui.icon(g, Icon.CHECK, x + 12, y + 10, Theme.alpha(0xFF5AD8FF, a), Fonts.ICON_S);
		Ui.textMid(g, toast, x + 21, y + 10, Theme.alpha(0xFFFFFFFF, a), Fonts.BODY);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != LEFT) return false;
		float mx = (float) (mouseX / zoom), my = (float) (mouseY / zoom);
		for (Hit h : hits) {
			if (Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
				p().playClickSound();
				h.run.run();
				return true;
			}
		}
		return false;
	}
}
