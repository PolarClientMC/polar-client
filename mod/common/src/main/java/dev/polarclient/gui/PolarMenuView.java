package dev.polarclient.gui;

import com.google.gson.JsonObject;
import dev.polarclient.Polar;
import dev.polarclient.config.ClientData;
import dev.polarclient.config.ConfigManager;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Icons;
import dev.polarclient.gui.ui.TextField;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.module.Category;
import dev.polarclient.module.Module;
import dev.polarclient.util.Draw;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Right Shift menu: a frosted-glass window with a sidebar (Mods, HUD Editor, Settings, Cosmetics, Profiles), an
 * aurora banner over the page header, category chips and a grid of mod cards with switches.
 * <p>
 * It is laid out in its own units (a 560 x 292 window) and drawn at a whole number of screen pixels per unit picked
 * from the window size, so it keeps the same proportions at every GUI scale.
 */
public class PolarMenuView extends View {
	private enum Page { MODS, HUD_EDITOR, SETTINGS, COSMETICS, PROFILES }

	private static final String[] NAV_LABELS = {"Mods", "HUD Editor", "Settings", "Cosmetics", "Profiles"};
	private static final String[] NAV_ICONS = {Icon.MODS, Icon.GRID, Icon.SETTINGS, Icon.CHECKROOM, Icon.STACKS};

	static final float DW = 584, DH = 322, R = 12;
	private static final float SIDE_W = 132, SIDE_MIN = 44;
	private static final float NAV_X = 8, NAV_Y = 54, NAV_H = 22, NAV_STEP = 26;
	/** Where the pages below the header start. */
	private static final float BANNER_H = 46;
	private static final float PAD = 16;
	private static final float CHIP_Y = 48, CHIP_H = 18;
	private static final float GRID_Y = 76, CARD_H = 62, CARD_GAP_X = 8, CARD_GAP_Y = 8;
	private static final float SW_W = 20, SW_H = 11;
	private static final float DRAWER_W = 236;
	private static final int ACCENT = 0xFF5AD8FF;

	/** Each category's tint (icon tiles, category labels). */
	private static int tint(Category c) {
		switch (c) {
			case HUD: return 0xFF5AD8FF;
			case VISUAL: return 0xFFA88BFF;
			case COMBAT: return 0xFFFF7A85;
			case UTILITY: return 0xFFFFC46B;
			case WORLD: return 0xFF5FE3A1;
			case SOCIAL: return 0xFF7CB8FF;
			case PERFORMANCE: return 0xFF6BF0D8;
			case ACCESSIBILITY: return 0xFFFFA8D6;
			default: return 0xFFFFD36B;
		}
	}
	private static final String PEAKS = "textures/gui/menu/side_peaks.png";

	private static Page lastPage = Page.MODS;
	private static boolean collapsed;
	private static boolean onlyEnabled;
	private static Category lastFilter;
	private static boolean lastFavorites;

	private Page page = lastPage;
	private Category filter = lastFilter;
	private boolean favoritesOnly = lastFavorites;
	private TextField search;
	private TextField profileName;
	private SettingsView moduleSettings;
	private SettingsView clientSettings;
	private Module pendingModule;
	private String toast;
	private long toastAt;
	private ClientData.Profile confirmDelete;

	// layout (GUI units): window origin and units -> GUI scale
	private float ox, oy, zoom = 1;
	private float side = SIDE_W;
	private float scroll, scrollTarget, chipScroll, chipScrollTarget, chipContentW;
	private long openedAt, pageChangedAt;
	private int pageDirection = 1;
	private final Map<Module, Float> hoverAnim = new HashMap<Module, Float>();
	private final Map<Module, Float> onAnim = new HashMap<Module, Float>();
	private final float[] navHover = new float[NAV_LABELS.length];
	private float navSel = -1, pillX = -1, pillW, drawerK;
	private Module drawerModule;
	private final Map<Module, float[]> cardPos = new HashMap<Module, float[]>();
	private Module hovered;

	public PolarMenuView(Object parent) {
		super(parent);
		if (page == Page.HUD_EDITOR) page = Page.MODS;
	}

	/** Opens on the Cosmetics page (the pause menu's Wardrobe tile). */
	public static PolarMenuView cosmetics(Object parent) {
		PolarMenuView v = new PolarMenuView(parent);
		v.page = Page.COSMETICS;
		return v;
	}

	/** Opens straight into one module's settings (right-click in the HUD editor). */
	public PolarMenuView(Object parent, Module module) {
		this(parent);
		this.page = Page.MODS;
		this.pendingModule = module;
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		layout();
		String previous = search != null ? search.value() : "";
		search = new TextField(32);
		search.setTextColor(0xFFFFFFFF);
		search.setValue(previous);
		search.setListener(v -> scrollTarget = 0);
		search.visible = false;
		String prevName = profileName != null ? profileName.value() : "";
		profileName = new TextField(24);
		profileName.setTextColor(0xFFFFFFFF);
		profileName.setValue(prevName);
		profileName.visible = false;

		if (moduleSettings != null) {
			Module m = moduleSettings.module();
			moduleSettings = null;
			openModule(m);
		}
		clientSettings = null;
		if (pendingModule != null) {
			openModule(pendingModule);
			pendingModule = null;
		}
	}

	/** Picks how many screen pixels one menu unit gets, then centres the window. */
	private void layout() {
		int gs = Math.max(1, p().guiScale());
		float screenW = width * gs, screenH = height * gs;
		int px = (int) Math.floor(Math.min(screenW * 0.94f / DW, screenH * 0.92f / DH));
		String size = Polar.modules().client.menuSize.get();
		if (size.equals("Small")) px -= 1;
		else if (size.equals("Large") && (px + 1) * DW <= screenW * 0.98f && (px + 1) * DH <= screenH * 0.98f) px += 1;
		if (px < 1) {
			zoom = Math.min(screenW * 0.98f / DW, screenH * 0.98f / DH) / gs; // very small window: fractional fallback
		} else {
			zoom = px / (float) gs;
		}
		ox = (width - DW * zoom) / 2f;
		oy = (height - DH * zoom) / 2f;
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	public void onClose() {
		lastPage = page == Page.HUD_EDITOR ? Page.MODS : page;
		lastFilter = filter;
		lastFavorites = favoritesOnly;
		Polar.saveConfig();
		p().show(parent);
	}

	private float mx(double x) {
		return (float) ((x - ox) / zoom);
	}

	private float my(double y) {
		return (float) ((y - oy) / zoom);
	}

	// ------------------------------------------------------------------ geometry (menu units)

	private float contentX() {
		return side;
	}

	private float contentW() {
		return DW - side;
	}

	private int columns() {
		return 3;
	}

	private float cardW() {
		int cols = columns();
		return (contentW() - PAD * 2 - (cols - 1) * CARD_GAP_X) / cols;
	}

	private float gridX() {
		return contentX() + PAD;
	}

	private List<Module> visibleModules() {
		String q = search.value().trim().toLowerCase(Locale.ROOT);
		ClientData data = Polar.config() == null ? null : Polar.config().data();
		List<Module> out = new ArrayList<Module>();
		for (Module m : Polar.modules().all()) {
			if (m == Polar.modules().client) continue; // lives on the Settings page
			if (filter != null && m.category() != filter) continue;
			if (favoritesOnly && (data == null || !data.isFavorite(m.id()))) continue;
			if (onlyEnabled && !(m.canToggle() && m.isEnabled())) continue;
			if (!q.isEmpty() && !m.name().toLowerCase(Locale.ROOT).contains(q) && !m.description().toLowerCase(Locale.ROOT).contains(q)) continue;
			out.add(m);
		}
		return out;
	}

	private int enabledCount() {
		int n = 0;
		for (Module m : Polar.modules().all()) if (m != Polar.modules().client && m.canToggle() && m.isEnabled()) n++;
		return n;
	}

	private float gridBottom() {
		return DH - 10;
	}

	private float maxScroll() {
		int n = visibleModules().size(), cols = columns();
		int rows = (n + cols - 1) / cols;
		float content = rows * (CARD_H + CARD_GAP_Y) - CARD_GAP_Y;
		return Math.max(0, content - (gridBottom() - GRID_Y));
	}

	private void setPage(Page p) {
		if (p == Page.HUD_EDITOR) {
			lastPage = page;
			Polar.saveConfig();
			p().open(new HudEditorView(p().currentScreen()));
			return;
		}
		if (p == page && moduleSettings == null) return;
		pageDirection = p.ordinal() >= page.ordinal() ? 1 : -1;
		closeModule();
		page = p;
		scroll = scrollTarget = 0;
		confirmDelete = null;
		pageChangedAt = System.currentTimeMillis();
		if (p == Page.SETTINGS && clientSettings == null) clientSettings = new SettingsView(Polar.modules().client);
	}

	private void openModule(Module m) {
		closeModule();
		page = Page.MODS;
		moduleSettings = new SettingsView(m);
		drawerModule = m;
	}

	private void closeModule() {
		if (moduleSettings != null) {
			moduleSettings.dispose();
			moduleSettings = null;
		}
	}

	private void showToast(String text) {
		toast = text;
		toastAt = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ palette (the reference look, tinted by the accent)

	private static final class Pal {
		final int accent, highlight, chip, switchOn, glass, sideGlass, line, cardFill, cardBorder, text, muted, dim;

		Pal(Theme t) {
			accent = t.primary();
			highlight = Theme.mix(accent, 0xFF40E0FF, 0.45f);
			chip = Theme.mix(accent, 0xFF4FB0FF, 0.45f);
			switchOn = Theme.mix(accent, 0xFF0B5FD0, 0.78f);
			glass = Theme.alpha(Theme.mix(0xFF061328, accent, 0.03f), 0.90f);
			sideGlass = Theme.alpha(Theme.mix(0xFF030D1D, accent, 0.02f), 0.55f);
			line = Theme.alpha(Theme.mix(0xFF143259, accent, 0.10f), 0.9f);
			cardFill = Theme.alpha(Theme.mix(0xFF07172E, accent, 0.03f), 0.80f);
			cardBorder = Theme.mix(0xFF1B3B60, accent, 0.10f);
			text = 0xFFF1F6FC;
			muted = 0xFFA8B7CB;
			dim = 0xFF6F819A;
		}
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
		if (!p().inWorld()) MenuBackground.INSTANCE.render(g, width, height, mouseX, mouseY);
		if (Polar.modules().client.menuBlur.on()) g.blurBackground();
		g.fill(0, 0, width, height, 0x66020814);
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY, float a) {
		Ui.frame();
		layout();
		Theme t = Theme.current();
		Pal c = new Pal(t);
		float mx = mx(mouseX), my = my(mouseY);
		side = Ui.approach(side, collapsed ? SIDE_MIN : SIDE_W, 16f);
		float open = Ui.easeOut((System.currentTimeMillis() - openedAt) / 220f);

		g.push();
		g.translate(ox, oy);
		g.scale(zoom);
		Ui.setZoom(zoom);
		try {
			float s = 0.95f + 0.05f * open;
			g.translate(DW / 2f, DH / 2f);
			g.scale(s);
			g.translate(-DW / 2f, -DH / 2f);
			drawWindow(g, c);
			drawSidebar(g, mx, my, c, t);

			float slide = (1f - Ui.easeOut((System.currentTimeMillis() - pageChangedAt) / 260f)) * 14f * pageDirection;
			g.scissor(Math.round(contentX()) + 1, 1, Math.round(DW) - 1, Math.round(DH) - 1);
			g.push();
			g.translate(slide, 0);
			search.visible = false;
			profileName.visible = false;
			boolean drawerOpen = moduleSettings != null;
			float pmx = drawerOpen ? -1 : mx, pmy = drawerOpen ? -1 : my;
			if (page == Page.MODS) drawMods(g, pmx, pmy, c, t);
			else if (page == Page.SETTINGS) drawClientSettings(g, pmx, pmy, c, t);
			else if (page == Page.COSMETICS) drawCosmetics(g, mx, my, c, t);
			else if (page == Page.PROFILES) drawProfiles(g, mx, my, c, t);
			g.pop();
			drawerK = Ui.approach(drawerK, drawerOpen ? 1f : 0f, 14f);
			if (drawerK > 0.01f && drawerModule != null) drawDrawer(g, mx, my, c, t);
			g.endScissor();
			drawToast(g, c);
		} finally {
			Ui.setZoom(1f);
			g.pop();
		}
	}

	private void drawWindow(Gfx g, Pal c) {
		// a soft drop shadow, the frosted glass body, and a rim that catches the light at two corners
		for (int k = 4; k >= 1; k--) Ui.rect(g, -k * 2f, -k * 1.5f + 4, DW + k * 4f, DH + k * 3f, R + k * 2f, Theme.alpha(0xFF000000, 0.07f));
		Ui.rect(g, 0, 0, DW, DH, R, 0xDB081124);
		Ui.ring(g, 0, 0, DW, DH, R, 0x33789AE6, 1.2f);
		Ui.hGradient(g, R, 0, DW * 0.4f, 0.7f, 0x8CA0D7FF, 0x00A0D7FF);
		Ui.hGradient(g, DW * 0.6f, DH - 0.7f, DW * 0.4f - R, 0.7f, 0x00A0D7FF, 0x52A0D7FF);
	}

	private void drawSidebar(Gfx g, float mx, float my, Pal c, Theme t) {
		float w = side;
		float expand = Math.max(0, Math.min(1, (side - SIDE_MIN) / (SIDE_W - SIDE_MIN)));
		Ui.rect(g, 0.5f, 0.5f, w - 0.5f, DH - 1, R - 0.5f, 0, 0, R - 0.5f, 0xB3060D1C);
		Ui.vline(g, w - 0.5f, 1, DH - 2, 1, 0x1FA0C8FF);

		// mountains along the bottom, fading into the panel
		float peaksH = 62;
		g.scissor(1, Math.round(DH - peaksH), Math.round(w), Math.round(DH - 1));
		g.blit(PEAKS, 0, DH - peaksH, w, peaksH, 0, 0, Math.min(1, w / SIDE_W), 1, 0xB3FFFFFF);
		g.endScissor();
		g.gradient(1, Math.round(DH - 70), Math.round(w), Math.round(DH - R), 0x00060D1C, 0xD9060D1C);
		Ui.rect(g, 1, DH - R, w - 1, R - 1, 0, 0, 0, R - 1, 0xD9060D1C);

		// logo: the round badge, then Polar / Client
		float lx = 22 - 6 * expand, ly = 24;
		Ui.glow(g, lx, ly, 22, 22, Theme.alpha(ACCENT, 0.22f));
		Ui.circle(g, lx, ly, 13, Theme.alpha(ACCENT, 0.8f));
		Ui.circle(g, lx, ly, 12, 0xFF0A1830);
		Draw.texture(g, Draw.MARK_LARGE, Math.round(lx - 10.5f), Math.round(ly - 10.5f), 21, 21);
		if (expand > 0.05f) {
			int a = Math.round(255 * expand);
			Ui.textMid(g, "Polar", lx + 19, ly - 4, Draw.alpha(c.text, a), Fonts.LARGE);
			Ui.textMid(g, "Client", lx + 19.5f, ly + 7, Draw.alpha(0xFF8FB4E6, a), Fonts.SMALL);
		}

		// navigation with a sliding highlight
		Page active = page;
		float iw = w - NAV_X * 2;
		float target = NAV_Y + active.ordinal() * NAV_STEP;
		navSel = navSel < 0 ? target : Ui.approach(navSel, target, 16f);
		Ui.rect(g, NAV_X, navSel, iw, NAV_H, 7, 0x1F5AD8FF);
		Ui.ring(g, NAV_X, navSel, iw, NAV_H, 7, 0x735AD8FF, 1f);
		Ui.rect(g, NAV_X + 1, navSel + 5, 1.6f, NAV_H - 10, 0.8f, ACCENT);
		for (int i = 0; i < NAV_LABELS.length; i++) {
			float iy = NAV_Y + i * NAV_STEP;
			boolean on = Page.values()[i] == active;
			boolean hover = Ui.inside(mx, my, NAV_X, iy, iw, NAV_H);
			navHover[i] = Ui.approach(navHover[i], hover && !on ? 1f : 0f, 18f);
			if (navHover[i] > 0.01f) Ui.rect(g, NAV_X, iy, iw, NAV_H, 7, Theme.alpha(0xFFFFFFFF, 0.05f * navHover[i]));
			float iconX = NAV_X + 14 - (1 - expand) * (NAV_X + 14 - w / 2f);
			Ui.icon(g, NAV_ICONS[i], iconX, iy + NAV_H / 2f, on ? 0xFFFFFFFF : Theme.alpha(0xFFD2DEEE, 0.75f + 0.25f * navHover[i]), on ? Fonts.ICON_M_FILL : Fonts.ICON_M);
			if (expand > 0.3f) {
				int la = Math.round(255 * Math.min(1, (expand - 0.3f) / 0.7f));
				Ui.textMid(g, NAV_LABELS[i], NAV_X + 28, iy + NAV_H / 2f, Draw.alpha(on ? 0xFFFFFFFF : 0xFFC9D6E8, la), on ? Fonts.BOLD : Fonts.BODY);
			}
		}

		// account
		if (expand > 0.2f) {
			float ay = DH - 44;
			int a = Math.round(255 * expand);
			Ui.rect(g, NAV_X, ay, iw, 30, 8, Draw.alpha(0xFF0A162E, Math.round(a * 0.85f)));
			Ui.ring(g, NAV_X, ay, iw, 30, 8, Draw.alpha(0xFFA0C8FF, Math.round(a * 0.12f)), 1f);
			g.playerHead(p().userId(), p().userName(), NAV_X + 6, ay + 6, 18);
			String name = p().userName() == null ? "Player" : p().userName();
			Ui.textMid(g, Fonts.ellipsize(name, Fonts.BOLD, Math.round(iw - 34)), NAV_X + 29, ay + 11, Draw.alpha(0xFFFFFFFF, a), Fonts.BOLD);
			Ui.textMid(g, "Polar Client " + Polar.version(), NAV_X + 29, ay + 21, Draw.alpha(0xFFB9C9DE, a), Fonts.SMALL);
		}

		// fold / unfold
		float[] cb = collapseButton();
		boolean ch = Ui.inside(mx, my, cb[0] - 8, cb[1] - 8, 16, 16);
		if (ch) Ui.circle(g, cb[0], cb[1], 8, 0x12FFFFFF);
		if (expand > 0.5f || collapsed) Ui.icon(g, collapsed ? Icon.CHEVRON_RIGHT : Icon.CHEVRON_LEFT, cb[0], cb[1], 0xFF9FB3CE, Fonts.ICON_M);
	}

	/** Centre of the fold button: top right of the sidebar, or at the bottom when folded. */
	private float[] collapseButton() {
		return collapsed ? new float[]{side / 2f, DH - 14} : new float[]{side - 14, 24};
	}

	private void drawHeader(Gfx g, String title, String subtitle, Pal c) {
		float x = contentX() + PAD;
		Ui.textMid(g, title, x, 22, c.text, Fonts.TITLE);
		if (subtitle != null) Ui.textMid(g, subtitle, x, 36, 0xFF93A8C6, Fonts.SMALL);
	}

	private float[] searchBox() {
		return new float[]{DW - PAD - 150, 14, 150, 20};
	}

	private void drawSearch(Gfx g, float mx, float my, Pal c) {
		float[] b = searchBox();
		float sx = b[0], sy = b[1], sw = b[2], sh = b[3];
		boolean focused = search.focused();
		boolean hover = Ui.inside(mx, my, sx, sy, sw, sh);
		if (focused) Ui.glow(g, sx + sw / 2f, sy + sh / 2f, sw * 0.6f, 14, 0x145AD8FF);
		Ui.rect(g, sx, sy, sw, sh, sh / 2f, 0xCC040A18);
		Ui.ring(g, sx, sy, sw, sh, sh / 2f, focused ? 0xE65AD8FF : hover ? 0x54A0C8FF : 0x2EA0C8FF, 1.2f);
		Ui.icon(g, Icon.SEARCH, sx + 11, sy + sh / 2f, focused ? ACCENT : 0xFF9FB3CE, Fonts.ICON_S);
		if (search.value().isEmpty() && !focused) {
			Ui.textMid(g, "Search mods", sx + 20, sy + sh / 2f, 0xFF7F93B0, Fonts.BODY);
			Ui.ring(g, sx + sw - 18, sy + 5, 11, 10, 3, 0x4DA0C8FF, 1f);
			Ui.textCentered(g, "/", sx + sw - 12.5f, sy + 10, 0xFF8FA6C8, Fonts.SMALL);
		}
		search.visible = true;
		search.setBounds(Math.round(sx + 20), Math.round(sy + 6), Math.round(sw - 28));
		search.render(g);
	}

	private void drawSwitch(Gfx g, float x, float y, float onF, boolean hover, Pal c) {
		if (onF > 0.5f) Ui.glow(g, x + SW_W / 2f, y + SW_H / 2f, 16, 9, Theme.alpha(ACCENT, 0.25f * onF));
		int track = Theme.lerp(0xE6283650, 0xFF46BEEB, onF);
		Ui.rect(g, x, y, SW_W, SW_H, SW_H / 2f, track);
		Ui.ring(g, x, y, SW_W, SW_H, SW_H / 2f, Theme.alpha(0xFFFFFFFF, hover ? 0.22f : 0.12f), 0.8f);
		float kx = x + SW_H / 2f + (SW_W - SW_H) * onF;
		Ui.circle(g, kx, y + SW_H / 2f + 0.4f, SW_H / 2f - 1.2f, 0x40000000);
		Ui.circle(g, kx, y + SW_H / 2f, SW_H / 2f - 1.4f, Theme.lerp(0xFFAEBBD0, 0xFFFFFFFF, onF));
	}

	// ------------------------------------------------------------------ Mods page

	private int toggleableCount() {
		int n = 0;
		for (Module m : Polar.modules().all()) if (m != Polar.modules().client) n++;
		return n;
	}

	/** The "Only on" switch left of the search box: {x, y, w, h}. */
	private float[] onlyOnBox() {
		float w = Fonts.width("Only on", Fonts.BODY) + 30;
		return new float[]{searchBox()[0] - 12 - w, 14, w, 20};
	}

	private void drawMods(Gfx g, float mx, float my, Pal c, Theme t) {
		drawHeader(g, "Mods", toggleableCount() + " built in, " + enabledCount() + " on", c);
		drawSearch(g, mx, my, c);
		float[] ob = onlyOnBox();
		boolean oh = Ui.inside(mx, my, ob[0], ob[1], ob[2], ob[3]);
		Ui.textMid(g, "Only on", ob[0], ob[1] + ob[3] / 2f, oh ? 0xFFFFFFFF : 0xFFC9D6E8, Fonts.BODY);
		drawSwitch(g, ob[0] + ob[2] - 21, ob[1] + 4.5f, onlyEnabled ? 1 : 0, oh, c);
		drawChips(g, mx, my, c);

		scroll = Ui.approach(scroll, scrollTarget, 14f);
		float top = GRID_Y, bottom = gridBottom();
		boolean mouseInGrid = Ui.inside(mx, my, contentX(), top - 3, contentW(), bottom - top + 3);
		g.scissor(Math.round(contentX()) + 1, Math.round(top - 6), Math.round(DW) - 1, Math.round(bottom));
		List<Module> mods = visibleModules();
		int cols = columns();
		float cw = cardW(), sx = gridX();
		hovered = null;
		if (mods.isEmpty()) {
			float cx = contentX() + contentW() / 2f;
			Ui.icon(g, Icon.SEARCH, cx, top + 50, 0xFF5E7290, Fonts.ICON_L);
			String msg = onlyEnabled ? "None of these mods are on" : favoritesOnly ? "Star a mod to pin it here" : "No mods match \u201C" + search.value() + "\u201D";
			Ui.textCentered(g, msg, cx, top + 72, c.muted, Fonts.BODY);
			if (!search.value().isEmpty()) Ui.textCentered(g, "Press Esc to clear the search", cx, top + 84, c.dim, Fonts.SMALL);
		}
		java.util.Set<Module> shown = new java.util.HashSet<Module>();
		for (int i = 0; i < mods.size(); i++) {
			Module m = mods.get(i);
			shown.add(m);
			float tx = sx + (i % cols) * (cw + CARD_GAP_X);
			float ty = top + (i / cols) * (CARD_H + CARD_GAP_Y) - scroll;
			// cards glide to their new place when the list changes, and fade in when they appear
			float[] pos = cardPos.get(m);
			if (pos == null) {
				pos = new float[]{tx, ty + 8, 0};
				cardPos.put(m, pos);
			}
			pos[0] = Ui.approach(pos[0], tx, 14f);
			pos[1] = Ui.approach(pos[1], ty, 14f);
			pos[2] = Ui.approach(pos[2], 1f, 12f);
			float x = pos[0], y = pos[1];
			if (y + CARD_H < top - 8 || y > bottom + 8) continue;
			boolean hover = mouseInGrid && Ui.inside(mx, my, x, y, cw, CARD_H);
			if (hover) hovered = m;
			drawCard(g, m, x, y, cw, pos[2], hover, mx, my, c);
		}
		cardPos.keySet().retainAll(shown);
		g.endScissor();
		float max = maxScroll();
		if (scroll > 1) g.gradient(Math.round(contentX()) + 1, Math.round(top - 6), Math.round(DW) - 1, Math.round(top + 4), 0xFF081124, 0x00081124);
		if (scroll < max - 1) g.gradient(Math.round(contentX()) + 1, Math.round(bottom - 12), Math.round(DW) - 1, Math.round(bottom + 2), 0x00081124, 0xF2081124);
		if (max > 0) {
			float h = bottom - top, thumb = Math.max(20, h * h / (h + max));
			Ui.rect(g, DW - 6, top + (h - thumb) * (scroll / max), 2, thumb, 1, Theme.alpha(0xFFA0C8FF, mouseInGrid ? 0.5f : 0.25f));
		}
	}

	private static final class Chip {
		final String label, icon;
		final Category category;
		final boolean favorites;

		Chip(String label, String icon, Category category, boolean favorites) {
			this.label = label;
			this.icon = icon;
			this.category = category;
			this.favorites = favorites;
		}
	}

	private List<Chip> chips() {
		List<Chip> out = new ArrayList<Chip>();
		out.add(new Chip("All", null, null, false));
		out.add(new Chip("Favorites", Icon.STAR, null, true));
		for (Category cat : Category.values()) {
			if (chipCount(cat, false) > 0) out.add(new Chip(cat.label, null, cat, false));
		}
		return out;
	}

	private int chipCount(Category cat, boolean favorites) {
		ClientData data = Polar.config() == null ? null : Polar.config().data();
		int n = 0;
		for (Module m : Polar.modules().all()) {
			if (m == Polar.modules().client) continue;
			if (favorites) {
				if (data != null && data.isFavorite(m.id())) n++;
			} else if (cat == null || m.category() == cat) n++;
		}
		return n;
	}

	private int chipCount(Chip chip) {
		return chipCount(chip.category, chip.favorites);
	}

	private boolean selected(Chip chip) {
		return chip.favorites ? favoritesOnly : !favoritesOnly && filter == chip.category;
	}

	private float chipW(Chip chip) {
		return 18 + (chip.icon != null ? 11 : 0) + Fonts.width(chip.label, Fonts.BODY) + 5 + Fonts.width(String.valueOf(chipCount(chip)), Fonts.SMALL);
	}

	private float chipsRight() {
		return DW - PAD;
	}

	private void drawChips(Gfx g, float mx, float my, Pal c) {
		float left = gridX(), right = chipsRight();
		List<Chip> chips = chips();
		float total = 0;
		for (Chip chip : chips) total += chipW(chip) + 5;
		chipContentW = total - 5;
		float maxS = Math.max(0, chipContentW - (right - left));
		chipScrollTarget = Math.max(0, Math.min(maxS, chipScrollTarget));
		chipScroll = Ui.approach(chipScroll, chipScrollTarget, 16f);
		g.scissor(Math.round(left) - 2, Math.round(CHIP_Y - 4), Math.round(right) + 2, Math.round(CHIP_Y + CHIP_H + 4));
		float mid = CHIP_Y + CHIP_H / 2f;
		// chip backs, then the sliding pill under the selected one, then the labels
		float x = left - chipScroll, selX = 0, selW = 0;
		for (Chip chip : chips) {
			float w = chipW(chip);
			Ui.rect(g, x, CHIP_Y, w, CHIP_H, CHIP_H / 2f, 0xBF0C1A32);
			Ui.ring(g, x, CHIP_Y, w, CHIP_H, CHIP_H / 2f, 0x24A0C8FF, 0.8f);
			if (selected(chip)) {
				selX = x;
				selW = w;
			}
			x += w + 5;
		}
		if (pillX < 0) {
			pillX = selX;
			pillW = selW;
		}
		pillX = Ui.approach(pillX, selX, 16f);
		pillW = Ui.approach(pillW, selW, 16f);
		Ui.glow(g, pillX + pillW / 2f, mid, pillW * 0.6f, CHIP_H, 0x1F5AD8FF);
		Ui.gradientRect(g, pillX, CHIP_Y, pillW, CHIP_H, CHIP_H / 2f, 0xFF7FE2FF, 0xFF3FA9E8);
		x = left - chipScroll;
		for (Chip chip : chips) {
			float w = chipW(chip);
			boolean sel = selected(chip);
			boolean hover = !sel && Ui.inside(mx, my, x, CHIP_Y, w, CHIP_H) && mx < right;
			if (hover) Ui.rect(g, x, CHIP_Y, w, CHIP_H, CHIP_H / 2f, 0x0FFFFFFF);
			float tx = x + 9;
			if (chip.icon != null) {
				g.push();
				g.translate(tx + 4, mid);
				g.scale(0.75f);
				Ui.icon(g, chip.icon, 0, 0, sel ? 0xFF06243A : 0xFFFFD36B, Fonts.ICON_M_FILL);
				g.pop();
				tx += 11;
			}
			Ui.textMid(g, chip.label, tx, mid, sel ? 0xFF05203A : 0xFFD6E1EF, Fonts.BODY);
			Ui.textMid(g, String.valueOf(chipCount(chip)), tx + Fonts.width(chip.label, Fonts.BODY) + 5, mid + 0.3f, sel ? 0xB305203A : 0xFF7F93B0, Fonts.SMALL);
			x += w + 5;
		}
		g.endScissor();
		if (maxS > 0) {
			if (chipScroll < maxS - 1) {
				Ui.hGradient(g, right - 30, CHIP_Y - 2, 32, CHIP_H + 4, 0x00081124, 0xFF081124);
				arrow(g, right - 8, mid, Icon.CHEVRON_RIGHT, mx, my);
			}
			if (chipScroll > 1) {
				Ui.hGradient(g, left - 2, CHIP_Y - 2, 32, CHIP_H + 4, 0xFF081124, 0x00081124);
				arrow(g, left + 8, mid, Icon.CHEVRON_LEFT, mx, my);
			}
		}
	}

	private void arrow(Gfx g, float x, float y, String icon, float mx, float my) {
		boolean hv = Ui.inside(mx, my, x - 7, y - 7, 14, 14);
		Ui.circle(g, x, y, 7, hv ? 0xFF1E3C64 : 0xFF122442);
		Ui.ring(g, x - 7, y - 7, 14, 14, 7, 0x4DA0C8FF, 0.8f);
		Ui.icon(g, icon, x, y, 0xFFE2EAF4, Fonts.ICON_S);
	}

	private void drawCard(Gfx g, Module m, float x, float y, float w, float alpha, boolean hover, float mx, float my, Pal c) {
		Float prevHover = hoverAnim.get(m);
		float hv = Ui.approach(prevHover == null ? 0f : prevHover, hover ? 1f : 0f, 16f);
		hoverAnim.put(m, hv);
		boolean on = m.canToggle() && m.isEnabled();
		Float prevOn = onAnim.get(m);
		float onF = Ui.approach(prevOn == null ? (on ? 1f : 0f) : prevOn, on ? 1f : 0f, 14f);
		onAnim.put(m, onF);
		String why = m.unavailableReason();
		ClientData data = Polar.config() == null ? null : Polar.config().data();
		boolean fav = data != null && data.isFavorite(m.id());
		int tint = tint(m.category());
		float a = alpha;

		g.push();
		g.translate(0, -hv * 1.2f + (1 - a) * 6);
		// lift: a soft shadow under the hovered card
		if (hv > 0.02f) Ui.glow(g, x + w / 2f, y + CARD_H / 2f + 5, w * 0.6f, CARD_H * 0.7f, Theme.alpha(0xFF000000, 0.3f * hv * a));
		Ui.gradientRect(g, x, y, w, CARD_H, 9, Theme.alpha(Theme.lerp(0xFF10203C, 0xFF182E52, hv), 0.85f * a),
			Theme.alpha(Theme.lerp(0xFF0A162C, 0xFF10203E, hv), 0.85f * a));
		int edge = onF > 0.5f ? Theme.alpha(ACCENT, (0.35f + 0.35f * hv) * a) : Theme.alpha(0xFFA0C8FF, (0.13f + 0.25f * hv) * a);
		Ui.ring(g, x, y, w, CARD_H, 9, edge, 1f);
		Ui.gradientRect(g, x + 1, y + 1, w - 2, 10, 8, Theme.alpha(0xFFFFFFFF, 0.05f * a), 0x00FFFFFF);

		// icon tile in the category's colour, brighter when the mod is on
		float ix = x + 10, iy = y + 10, is = 22;
		Ui.gradientRect(g, ix, iy, is, is, 6.5f, Theme.alpha(tint, (0.18f + 0.22f * onF) * a), Theme.alpha(tint, (0.06f + 0.1f * onF) * a));
		Ui.ring(g, ix, iy, is, is, 6.5f, Theme.alpha(tint, (0.25f + 0.35f * onF) * a), 0.8f);
		if (onF > 0.3f) Ui.glow(g, ix + is / 2f, iy + is / 2f, 16, 16, Theme.alpha(tint, 0.16f * onF * a));
		Ui.icon(g, Icons.of(m), ix + is / 2f, iy + is / 2f, Theme.alpha(onF > 0.5f ? 0xFFFFFFFF : tint, (onF > 0.5f ? 1f : 0.85f) * a),
			onF > 0.5f ? Fonts.ICON_M_FILL : Fonts.ICON_M);

		int fg = why != null ? c.dim : c.text;
		Ui.textMid(g, Fonts.ellipsize(m.name(), Fonts.BOLD, Math.round(w - is - 18 - 34)), ix + is + 8, y + 16, Theme.alpha(fg, a), Fonts.BOLD);
		Ui.textMid(g, m.category().label, ix + is + 8, y + 26, Theme.alpha(tint, 0.85f * a), Fonts.SMALL);
		String desc = why != null ? why : m.description();
		List<String> lines = wrap(desc, Fonts.SMALL, w - 52, 2);
		for (int i = 0; i < lines.size(); i++) {
			Ui.textMid(g, lines.get(i), x + 10, y + 42 + i * 8.6f, Theme.alpha(why != null ? c.dim : 0xFF93A8C6, a), Fonts.SMALL);
		}
		if (m.canToggle() && why == null) {
			float swx = x + w - 30, swy = y + 41;
			drawSwitch(g, swx, swy, onF, hover && Ui.inside(mx, my, swx - 3, swy - 3, 26, 17), c);
		}

		// settings (gear) and favourite (star)
		float gx = x + w - 12, gy = y + 13, stX = gx - 14;
		boolean gearHover = hover && Ui.inside(mx, my, gx - 6, gy - 6, 12, 12);
		Ui.icon(g, Icon.SETTINGS, gx, gy, Theme.alpha(gearHover ? 0xFFFFFFFF : 0xFF8CA0BE, (0.45f + 0.55f * Math.max(hv, gearHover ? 1 : 0)) * a), Fonts.ICON_S);
		boolean starHover = hover && Ui.inside(mx, my, stX - 6, gy - 6, 12, 12);
		if (fav) {
			g.push();
			g.translate(stX, gy);
			g.scale(0.75f);
			Ui.icon(g, Icon.STAR, 0, 0, Theme.alpha(0xFFFFD36B, a), Fonts.ICON_M_FILL);
			g.pop();
		} else if (hv > 0.02f) {
			Ui.icon(g, Icon.STAR, stX, gy, Theme.alpha(0xFFFFD36B, (starHover ? 0.9f : 0.45f) * hv * a), Fonts.ICON_S);
		}
		g.pop();
	}

	/** Word-wraps text to at most {@code maxLines} lines (the last one gets "..." if it doesn't fit). */
	static List<String> wrap(String text, UiFont font, float maxW, int maxLines) {
		List<String> out = new ArrayList<String>();
		String[] words = text.split(" ");
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			String next = line.length() == 0 ? words[i] : line + " " + words[i];
			if (Fonts.width(next, font) <= maxW || line.length() == 0) {
				line = new StringBuilder(next);
			} else {
				out.add(line.toString());
				line = new StringBuilder(words[i]);
				if (out.size() == maxLines - 1) {
					StringBuilder rest = new StringBuilder(line);
					for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
					out.add(Fonts.ellipsize(rest.toString(), font, (int) maxW));
					return out;
				}
			}
		}
		if (line.length() > 0) out.add(Fonts.ellipsize(line.toString(), font, (int) maxW));
		return out;
	}

	// ------------------------------------------------------------------ settings pages

	/** The settings drawer: slides in from the right over the cards. */
	private float drawerX() {
		return DW - DRAWER_W * Ui.easeOut(drawerK);
	}

	private void drawDrawer(Gfx g, float mx, float my, Pal c, Theme t) {
		Module m = drawerModule;
		float k = drawerK, x = drawerX(), w = DRAWER_W, P = x + 16, rw = w - 32;
		g.fill(Math.round(contentX()), 0, Math.round(DW), Math.round(DH), Theme.alpha(0xFF020610, 0.45f * k));
		for (int i = 1; i <= 4; i++) g.fill(Math.round(x - i * 3), 0, Math.round(x - (i - 1) * 3), Math.round(DH), Theme.alpha(0xFF000000, 0.08f * (5 - i) / 4f * k));
		Ui.rect(g, x, 0, w + R, DH, 0, R, R, 0, 0xFF091328);
		Ui.vline(g, x, 0, DH, 1, 0x40A0D7FF);
		int tint = tint(m.category());
		Ui.gradientRect(g, P, 16, 28, 28, 8, Theme.alpha(tint, 0.35f), Theme.alpha(tint, 0.1f));
		Ui.icon(g, Icons.of(m), P + 14, 30, 0xFFFFFFFF, Fonts.ICON_M_FILL);
		Ui.textMid(g, Fonts.ellipsize(m.name(), Fonts.LARGE, Math.round(rw - 56)), P + 36, 25, c.text, Fonts.LARGE);
		Ui.textMid(g, m.category().label, P + 36, 37, Theme.alpha(tint, 0.9f), Fonts.SMALL);
		ClientData data = Polar.config() == null ? null : Polar.config().data();
		boolean fav = data != null && data.isFavorite(m.id());
		float cx = x + w - 18;
		boolean closeHover = Ui.inside(mx, my, cx - 8, 14, 16, 16), starHover = Ui.inside(mx, my, cx - 26, 14, 16, 16);
		Ui.circle(g, cx, 22, 8, closeHover ? 0x1FFFFFFF : 0x0AFFFFFF);
		Ui.icon(g, Icon.CLOSE, cx, 22, 0xFFC9D6E8, Fonts.ICON_S);
		Ui.circle(g, cx - 18, 22, 8, starHover ? 0x1FFFFFFF : 0x0AFFFFFF);
		Ui.icon(g, Icon.STAR, cx - 18, 22, fav ? 0xFFFFD36B : 0xFFC9D6E8, Fonts.ICON_S);
		String why = m.unavailableReason();
		List<String> dl = wrap(why != null ? why : m.description(), Fonts.SMALL, rw, 2);
		for (int i = 0; i < dl.size(); i++) Ui.textMid(g, dl.get(i), P, 58 + i * 9, why != null ? c.dim : 0xFF93A8C6, Fonts.SMALL);
		float ry = 78;
		if (m.canToggle() && why == null) {
			Ui.rect(g, P, ry, rw, 26, 8, 0x125AD8FF);
			Ui.ring(g, P, ry, rw, 26, 8, 0x405AD8FF, 1f);
			Ui.textMid(g, "Enabled", P + 10, ry + 13, 0xFFFFFFFF, Fonts.BOLD);
			drawSwitch(g, P + rw - 30, ry + 7.5f, m.isEnabled() ? 1 : 0, Ui.inside(mx, my, P, ry, rw, 26), c);
			ry += 38;
		}
		float resetW = Fonts.width("Reset", Fonts.SMALL) + 20;
		boolean resetHover = Ui.inside(mx, my, P + rw - resetW, ry - 6, resetW, 13);
		Ui.textMid(g, "Settings", P, ry, 0xFFC9D6E8, Fonts.BOLD);
		if (!m.settings().isEmpty()) {
			Ui.icon(g, Icon.RESET, P + rw - resetW + 6, ry, resetHover ? 0xFFFFFFFF : 0xFF8FA6C8, Fonts.ICON_S);
			Ui.textMid(g, "Reset", P + rw - resetW + 13, ry, resetHover ? 0xFFFFFFFF : 0xFF8FA6C8, Fonts.SMALL);
		}
		ry += 10;
		if (m.settings().isEmpty()) {
			Ui.textMid(g, "This mod has no settings.", P, ry + 8, c.muted, Fonts.SMALL);
		} else if (moduleSettings != null) {
			moduleSettings.render(g, P - 4, ry, rw + 8, DH - ry - 10, Math.round(mx), Math.round(my), t);
		}
	}

	private void drawClientSettings(Gfx g, float mx, float my, Pal c, Theme t) {
		drawHeader(g, "Settings", "Theme, menus and how Polar Client looks.", c);
		if (clientSettings == null) clientSettings = new SettingsView(Polar.modules().client);
		clientSettings.render(g, contentX() + 14, BANNER_H + 8, contentW() - 28, DH - BANNER_H - 14, Math.round(mx), Math.round(my), t);
	}

	// ------------------------------------------------------------------ Cosmetics

	private static float buttonW(String label, String icon) {
		return label.isEmpty() ? 22 : Fonts.width(label, Fonts.BODY) + (icon != null ? 30 : 20);
	}

	private float[] button(Gfx g, String label, String icon, float x, float y, float mx, float my, Pal c, boolean primary) {
		float w = buttonW(label, icon), h = 17;
		boolean hover = Ui.inside(mx, my, x, y, w, h);
		if (primary) {
			Ui.gradientRect(g, x, y, w, h, h / 2f, Theme.mix(c.chip, 0xFFFFFFFF, hover ? 0.22f : 0.12f), Theme.mix(c.chip, 0xFF2A7BD8, 0.25f));
		} else {
			Ui.rect(g, x, y, w, h, h / 2f, hover ? Theme.mix(c.cardBorder, c.highlight, 0.4f) : 0xFF1A3150);
			Ui.rect(g, x + 0.6f, y + 0.6f, w - 1.2f, h - 1.2f, h / 2f - 0.6f, hover ? 0xFF13243C : 0xFF0D1C32);
		}
		float tx = x + 10;
		if (icon != null) {
			Ui.icon(g, icon, label.isEmpty() ? x + w / 2f : x + 12, y + h / 2f, 0xFFFFFFFF, Fonts.ICON_M);
			tx = x + 20;
		}
		if (!label.isEmpty()) Ui.textMid(g, label, tx, y + h / 2f, 0xFFFFFFFF, Fonts.BODY);
		return new float[]{x, y, w, h};
	}

	private float[] skinButton, profileSaveButton, exportButton, importButton;

	private float[] wardrobeButton;

	private void drawCosmetics(Gfx g, float mx, float my, Pal c, Theme t) {
		drawHeader(g, "Cosmetics", "Your skin, cape and how other players see you.", c);
		float x = gridX(), y = GRID_Y - 18, w = contentW() - PAD - 7.6f, h = 82;
		Ui.rect(g, x, y, w, h, 6.4f, c.cardBorder);
		Ui.rect(g, x + 0.65f, y + 0.65f, w - 1.3f, h - 1.3f, 5.8f, 0xF0081931);
		float head = 46;
		Ui.glow(g, x + 14 + head / 2f, y + 18 + head / 2f, head * 0.8f, head * 0.8f, Theme.alpha(c.highlight, 0.18f));
		g.playerHead(p().userId(), p().userName(), x + 14, y + 18, head);
		String name = p().userName();
		Ui.text(g, name, x + 74, y + 22, c.text, Fonts.TITLE);
		Ui.text(g, "Signed in on Minecraft " + p().minecraftVersion(), x + 74, y + 39, c.muted, Fonts.SMALL);
		skinButton = button(g, "Skin layers", Icon.CHECKROOM, x + 74, y + 51, mx, my, c, true);

		float ny = y + h + 8;
		Ui.rect(g, x, ny, w, 46, 6.4f, c.cardBorder);
		Ui.rect(g, x + 0.65f, ny + 0.65f, w - 1.3f, 46 - 1.3f, 5.8f, 0xF0081931);
		Ui.icon(g, Icon.AUTO_AWESOME, x + 16, ny + 15, c.highlight, Fonts.ICON_M);
		Ui.text(g, "Polar wardrobe", x + 28, ny + 12.5f, c.text, Fonts.BOLD);
		Ui.text(g, "Capes, wings, hats, back items and auras. Try them on in game; only you see what you try.", x + 28, ny + 24, c.muted, Fonts.SMALL);
		Ui.text(g, "Skins are changed on the Cosmetics page of the Polar launcher.", x + 28, ny + 32.5f, c.muted, Fonts.SMALL);
		wardrobeButton = button(g, "Open wardrobe", Icon.CHECKROOM, x + w - 92, ny + 8, mx, my, c, true);
	}

	// ------------------------------------------------------------------ Profiles

	private final List<float[]> profileRows = new ArrayList<float[]>();

	private void drawProfiles(Gfx g, float mx, float my, Pal c, Theme t) {
		drawHeader(g, "Profiles", "Save which mods are on, their settings and your HUD layout.", c);
		ClientData data = Polar.config().data();
		float x = gridX(), w = contentW() - PAD - 7.6f;
		// new profile: name box + Save, then Export / Import on the right
		float y = CHIP_Y;
		float fw = 120, fh = 17;
		boolean focused = profileName.focused();
		Ui.rect(g, x, y, fw, fh, fh / 2f, focused ? c.highlight : c.cardBorder);
		Ui.rect(g, x + 0.6f, y + 0.6f, fw - 1.2f, fh - 1.2f, fh / 2f - 0.6f, 0xEE061226);
		if (profileName.value().isEmpty() && !focused) Ui.textMid(g, "New profile name", x + 9, y + fh / 2f, 0xFF7F92AB, Fonts.BODY);
		profileName.visible = true;
		profileName.setBounds(Math.round(x + 9), Math.round(y + 4.5f), Math.round(fw - 16));
		profileName.render(g);
		profileSaveButton = button(g, "Save current", Icon.SAVE, x + fw + 5, y, mx, my, c, true);
		float ix = x + w - buttonW("Import", Icon.PASTE);
		importButton = button(g, "Import", Icon.PASTE, ix, y, mx, my, c, false);
		ix -= buttonW("Export", Icon.COPY) + 5;
		exportButton = button(g, "Export", Icon.COPY, ix, y, mx, my, c, false);

		profileRows.clear();
		float ry = GRID_Y - 2 - scroll;
		scroll = Ui.approach(scroll, scrollTarget, 18f);
		g.scissor(Math.round(contentX()) + 1, Math.round(GRID_Y - 4), Math.round(DW) - 1, Math.round(DH) - 2);
		if (data.profiles().isEmpty()) {
			Ui.textCentered(g, "No profiles yet", contentX() + contentW() / 2f, GRID_Y + 30, c.text, Fonts.BOLD);
			Ui.textCentered(g, "Type a name and press Save current to keep your setup.", contentX() + contentW() / 2f, GRID_Y + 44, c.muted, Fonts.SMALL);
		}
		SimpleDateFormat fmt = new SimpleDateFormat("d MMM, HH:mm", Locale.ROOT);
		for (ClientData.Profile pr : data.profiles()) {
			float rh = 30;
			boolean active = ClientData.sameName(pr.name, data.activeProfile());
			boolean hover = Ui.inside(mx, my, x, ry, w, rh);
			Ui.rect(g, x, ry, w, rh, 6, active ? Theme.mix(c.cardBorder, c.highlight, 0.6f) : hover ? Theme.mix(c.cardBorder, c.highlight, 0.3f) : c.cardBorder);
			Ui.rect(g, x + 0.65f, ry + 0.65f, w - 1.3f, rh - 1.3f, 5.4f, active ? 0xF00B2242 : 0xF0081931);
			Ui.icon(g, Icon.STACKS, x + 14, ry + rh / 2f, active ? c.highlight : 0xFFB5C4D6, Fonts.ICON_M);
			Ui.text(g, pr.name, x + 26, ry + 8, c.text, Fonts.BOLD);
			String sub = pr.enabledCount() + " mods on" + (pr.savedAt > 0 ? "  ·  saved " + fmt.format(new Date(pr.savedAt)) : "");
			Ui.text(g, sub, x + 26, ry + 18.5f, c.muted, Fonts.SMALL);
			if (active) {
				float bw = Fonts.width("Active", Fonts.SMALL) + 10, bx = x + 30 + Fonts.width(pr.name, Fonts.BOLD);
				Ui.rect(g, bx, ry + 7, bw, 9, 4.5f, Theme.alpha(c.chip, 0.25f));
				Ui.textMid(g, "Active", bx + 5, ry + 11.5f, c.highlight, Fonts.SMALL);
			}
			// Load / Update / Delete
			boolean confirming = confirmDelete == pr;
			String delLabel = confirming ? "Delete?" : "";
			String delIcon = confirming ? null : Icon.DELETE;
			float bx = x + w - 8 - buttonW(delLabel, delIcon);
			float[] del = button(g, delLabel, delIcon, bx, ry + 6.5f, mx, my, c, false);
			bx -= buttonW("Update", null) + 4;
			float[] upd = button(g, "Update", null, bx, ry + 6.5f, mx, my, c, false);
			bx -= buttonW("Load", null) + 4;
			float[] load = button(g, "Load", null, bx, ry + 6.5f, mx, my, c, true);
			profileRows.add(new float[]{load[0], load[1], load[2], load[3], upd[0], upd[2], del[0], del[2]});
			ry += rh + 5;
		}
		g.endScissor();
	}

	private float profilesMaxScroll() {
		int n = Polar.config().data().profiles().size();
		return Math.max(0, n * 35 - (DH - GRID_Y - 4));
	}

	private void drawToast(Gfx g, Pal c) {
		if (toast == null) return;
		long age = System.currentTimeMillis() - toastAt;
		if (age > 2600) {
			toast = null;
			return;
		}
		float a = Math.min(1, Math.min(age / 150f, (2600 - age) / 300f));
		float w = Fonts.width(toast, Fonts.BODY) + 24, h = 16, x = contentX() + (contentW() - w) / 2f, y = DH - 24;
		Ui.rect(g, x, y, w, h, h / 2f, Theme.alpha(c.highlight, 0.8f * a));
		Ui.rect(g, x + 0.6f, y + 0.6f, w - 1.2f, h - 1.2f, h / 2f - 0.6f, Theme.alpha(0xFF0B1B33, a));
		Ui.textCentered(g, toast, x + w / 2f, y + h / 2f, Theme.alpha(0xFFFFFFFF, a), Fonts.BODY);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double rawX, double rawY, int button) {
		float mx = mx(rawX), my = my(rawY);
		if (search.visible && search.mouseClicked(mx, my)) return true;
		if (profileName.visible && profileName.mouseClicked(mx, my)) return true;
		if (!Ui.inside(mx, my, 0, 0, DW, DH)) {
			onClose();
			return true;
		}
		// sidebar
		if (mx < side) {
			for (int i = 0; i < NAV_LABELS.length; i++) {
				float iy = NAV_Y + i * NAV_STEP;
				if (Ui.inside(mx, my, NAV_X, iy, side - NAV_X * 2, NAV_H)) {
					p().playClickSound();
					setPage(Page.values()[i]);
					return true;
				}
			}
			float[] cb = collapseButton();
			if (Ui.inside(mx, my, cb[0] - 8, cb[1] - 8, 16, 16)) {
				collapsed = !collapsed;
				return true;
			}
			return true;
		}
		if (moduleSettings != null) {
			if (mx < drawerX()) {
				closeModule();
				return true;
			}
			return clickModuleSettings(mx, my, button);
		}
		if (page == Page.MODS) return clickMods(mx, my, button);
		if (page == Page.SETTINGS && clientSettings != null) return clientSettings.mouseClicked(mx, my, button);
		if (page == Page.COSMETICS) return clickCosmetics(mx, my);
		if (page == Page.PROFILES) return clickProfiles(mx, my);
		return false;
	}

	private static boolean in(float[] b, float mx, float my) {
		return b != null && Ui.inside(mx, my, b[0], b[1], b[2], b[3]);
	}

	private boolean clickModuleSettings(float mx, float my, int button) {
		Module m = moduleSettings.module();
		float x = drawerX(), P = x + 16, rw = DRAWER_W - 32, cx = x + DRAWER_W - 18;
		if (Ui.inside(mx, my, cx - 8, 14, 16, 16)) {
			closeModule();
			return true;
		}
		if (Ui.inside(mx, my, cx - 26, 14, 16, 16)) {
			Polar.config().data().toggleFavorite(m.id());
			return true;
		}
		float ry = 78;
		if (m.canToggle() && m.unavailableReason() == null) {
			if (Ui.inside(mx, my, P, ry, rw, 26)) {
				m.toggle();
				p().playClickSound();
				return true;
			}
			ry += 38;
		}
		float resetW = Fonts.width("Reset", Fonts.SMALL) + 20;
		if (!m.settings().isEmpty() && Ui.inside(mx, my, P + rw - resetW, ry - 6, resetW, 13)) {
			m.resetSettings();
			moduleSettings.refreshTextBoxes();
			showToast(m.name() + " settings reset");
			return true;
		}
		return moduleSettings.mouseClicked(mx, my, button);
	}

	private boolean clickMods(float mx, float my, int button) {
		float[] ob = onlyOnBox();
		if (Ui.inside(mx, my, ob[0], ob[1], ob[2], ob[3])) {
			onlyEnabled = !onlyEnabled;
			scroll = scrollTarget = 0;
			p().playClickSound();
			return true;
		}
		float left = gridX(), right = chipsRight();
		if (my >= CHIP_Y && my < CHIP_Y + CHIP_H && mx >= left - 4 && mx < right + 2) {
			if (chipContentW > right - left) {
				float maxS = chipContentW - (right - left);
				if (mx > right - 16 && chipScroll < maxS - 1) {
					chipScrollTarget += 120;
					return true;
				}
				if (mx < left + 16 && chipScroll > 1) {
					chipScrollTarget -= 120;
					return true;
				}
			}
			float x = left - chipScroll;
			for (Chip chip : chips()) {
				float w = chipW(chip);
				if (mx >= x && mx < x + w) {
					if (chip.favorites) {
						favoritesOnly = !favoritesOnly;
					} else {
						favoritesOnly = false;
						filter = chip.category;
					}
					scroll = scrollTarget = 0;
					p().playClickSound();
					return true;
				}
				x += w + 5;
			}
			return true;
		}
		// cards: click toggles, the gear (or right-click) opens settings, the star pins it
		float top = GRID_Y, bottom = gridBottom();
		if (!Ui.inside(mx, my, contentX(), top - 3, contentW(), bottom - top + 3)) return false;
		List<Module> mods = visibleModules();
		int cols = columns();
		float cw = cardW(), sx = gridX();
		for (int i = 0; i < mods.size(); i++) {
			float x = sx + (i % cols) * (cw + CARD_GAP_X);
			float y = top + (i / cols) * (CARD_H + CARD_GAP_Y) - scroll;
			if (!Ui.inside(mx, my, x, y, cw, CARD_H)) continue;
			Module m = mods.get(i);
			float gx = x + cw - 12, gy = y + 13;
			if (Ui.inside(mx, my, gx - 14 - 6, gy - 6, 12, 12)) {
				Polar.config().data().toggleFavorite(m.id());
				showToast(Polar.config().data().isFavorite(m.id()) ? m.name() + " added to favorites" : m.name() + " removed from favorites");
				return true;
			}
			boolean gear = Ui.inside(mx, my, gx - 6, gy - 6, 12, 12);
			if (button == RIGHT || gear || !m.canToggle() || m.unavailableReason() != null) openModule(m);
			else {
				m.toggle();
				p().playClickSound();
			}
			return true;
		}
		return false;
	}

	private boolean clickCosmetics(float mx, float my) {
		if (in(wardrobeButton, mx, my)) {
			p().open(new WardrobeView(p().currentScreen()));
			return true;
		}
		if (in(skinButton, mx, my)) {
			p().openSkinOptions(p().currentScreen());
			return true;
		}
		return false;
	}

	private boolean clickProfiles(float mx, float my) {
		ConfigManager config = Polar.config();
		ClientData data = config.data();
		if (in(profileSaveButton, mx, my)) {
			String name = ClientData.cleanName(profileName.value());
			if (name.isEmpty()) name = data.freeName("Profile");
			if (data.profile(name) == null && data.profiles().size() >= ClientData.MAX_PROFILES) {
				showToast("You can keep up to " + ClientData.MAX_PROFILES + " profiles");
				return true;
			}
			data.put(name, config.snapshot());
			data.setActiveProfile(name);
			profileName.setValue("");
			profileName.setFocused(false);
			Polar.saveConfig();
			showToast("Saved " + name);
			return true;
		}
		if (in(exportButton, mx, my)) {
			String name = data.activeProfile() != null ? data.activeProfile() : "My setup";
			p().setClipboard(config.export(name));
			showToast("Copied your setup to the clipboard");
			return true;
		}
		if (in(importButton, mx, my)) {
			String text = p().clipboard();
			JsonObject mods = ConfigManager.parseExport(text);
			if (mods == null) {
				showToast("The clipboard doesn't hold a Polar profile");
				return true;
			}
			String name = ClientData.cleanName(ConfigManager.exportName(text));
			if (name.isEmpty() || data.profile(name) != null) name = data.freeName("Imported");
			if (data.profiles().size() >= ClientData.MAX_PROFILES) {
				showToast("You can keep up to " + ClientData.MAX_PROFILES + " profiles");
				return true;
			}
			config.apply(mods);
			data.put(name, config.snapshot());
			data.setActiveProfile(name);
			Polar.saveConfig();
			showToast("Imported " + name);
			return true;
		}
		List<ClientData.Profile> list = data.profiles();
		for (int i = 0; i < profileRows.size() && i < list.size(); i++) {
			float[] r = profileRows.get(i);
			ClientData.Profile pr = list.get(i);
			if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
				config.apply(pr.modules);
				data.setActiveProfile(pr.name);
				confirmDelete = null;
				Polar.saveConfig();
				showToast("Loaded " + pr.name);
				return true;
			}
			if (Ui.inside(mx, my, r[4], r[1], r[5], r[3])) {
				data.put(pr.name, config.snapshot());
				data.setActiveProfile(pr.name);
				confirmDelete = null;
				Polar.saveConfig();
				showToast("Updated " + pr.name);
				return true;
			}
			if (Ui.inside(mx, my, r[6], r[1], r[7], r[3])) {
				if (confirmDelete == pr) {
					data.remove(pr);
					confirmDelete = null;
					Polar.saveConfig();
					showToast("Deleted " + pr.name);
				} else {
					confirmDelete = pr;
				}
				return true;
			}
		}
		confirmDelete = null;
		return false;
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		SettingsView v = activeSettings();
		return v != null && v.mouseDragged(mx(x));
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		SettingsView v = activeSettings();
		if (v != null) v.mouseReleased();
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double amount) {
		float mx = mx(x), my = my(y);
		SettingsView v = activeSettings();
		if (v != null) return v.mouseScrolled(mx, my, amount);
		if (page == Page.MODS) {
			if (my >= CHIP_Y - 2 && my < CHIP_Y + CHIP_H + 2 && mx < chipsRight()) {
				chipScrollTarget -= (float) amount * 30;
				return true;
			}
			scrollTarget = (float) Math.max(0, Math.min(maxScroll(), scrollTarget - amount * 24));
		} else if (page == Page.PROFILES) {
			scrollTarget = (float) Math.max(0, Math.min(profilesMaxScroll(), scrollTarget - amount * 24));
		}
		return true;
	}

	private SettingsView activeSettings() {
		if (moduleSettings != null) return moduleSettings;
		return page == Page.SETTINGS && moduleSettings == null ? clientSettings : null;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		SettingsView v = activeSettings();
		boolean typing = search.focused() || profileName.focused() || (v != null && v.typing());
		if (search.keyPressed(key)) return true;
		if (profileName.keyPressed(key)) return true;
		if (v != null && v.keyPressed(key)) return true;
		if (key.is(KeyInput.Key.ESCAPE) && moduleSettings != null) {
			closeModule();
			return true;
		}
		if (key.is(KeyInput.Key.ESCAPE) && page == Page.MODS && !search.value().isEmpty()) {
			search.setValue("");
			search.setFocused(false);
			scroll = scrollTarget = 0;
			return true;
		}
		if (!typing && key.menuKey) {
			onClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (search.charTyped(ch)) {
			scroll = scrollTarget = 0;
			return true;
		}
		// on the Mods page, typing anywhere searches ("/" just focuses the box)
		SettingsView typingIn = activeSettings();
		if (page == Page.MODS && moduleSettings == null && !profileName.focused() && (typingIn == null || !typingIn.typing())
			&& (ch == '/' || Character.isLetterOrDigit(ch))) {
			search.setFocused(true);
			if (ch != '/') search.charTyped(ch);
			scroll = scrollTarget = 0;
			return true;
		}
		if (profileName.charTyped(ch)) return true;
		SettingsView v = activeSettings();
		return v != null && v.charTyped(ch);
	}
}
