package dev.polarclient.gui;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.module.HudModule;
import dev.polarclient.module.TextHudModule;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.setting.ColorSetting;
import dev.polarclient.setting.ModeSetting;
import dev.polarclient.setting.Setting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HUD editor. Drag elements (they snap to an 8 px grid, to each other, to the screen edges and centre, and to
 * the hotbar), Shift-click or drag a box to pick several and Ctrl+G to group them, resize with the corner handle,
 * and style each element in the side panel. Each element is pinned to an edge, corner or the centre, so the layout
 * holds on any screen; the screen menu previews other sizes. Presets, undo/redo (Ctrl+Z / Ctrl+Y) and a history list.
 */
public class HudEditorView extends View {
	private static final int ACC = 0xFF5AD8FF, GUIDE = 0xFFFF7AC6, SCREEN_GUIDE = 0xF27FE2FF, GOLD = 0xFFFFD36B, WARN = 0xFFFF8A7A;
	private static final int GLASS = 0xE6081124, LINE = 0x24A0C8FF, TEXT = 0xFFF2F7FF, MUTED = 0xFF93A8C6, DIM = 0xFF5E7290;
	private static final float SNAP = 4, GRID = 8;
	/** Box colours (alpha comes from the element's own setting) and border names, as the side panel offers them. */
	private static final int[] BOX_BG = {0x000000, 0x0B1A33, 0x0E4A6B, 0x2A1B4D};
	private static final String[] BORDERS = {"Ice", "White", "Gold", "Pink"};

	private static final String[][] SCREENS = {
		{"Your screen", "", "0", "0"}, {"Laptop", "1366 × 768", "683", "384"}, {"Ultrawide", "2560 × 1080", "853", "360"},
		{"5:4 monitor", "1280 × 1024", "427", "341"}, {"GUI scale 2", "1920 × 1080", "960", "540"}};

	private static boolean grid = true, snapElements = true;

	// selection and dragging
	private final List<HudModule> sel = new ArrayList<HudModule>();
	private HudModule hover;
	private enum Drag { NONE, MOVE, MARQUEE, SCALE, SLIDER }
	private Drag drag = Drag.NONE;
	private boolean moved;
	private float dragX0, dragY0, marqueeX1, marqueeY1;
	private final Map<HudModule, float[]> dragStart = new HashMap<HudModule, float[]>();
	private final List<HudModule> marqueeBase = new ArrayList<HudModule>();
	private HudModule scaling;
	private double scaleStart;
	private int scaleW0;
	private String sliderId;
	private float sliderX, sliderW, sliderMin, sliderMax;
	private final List<float[]> guides = new ArrayList<float[]>(); // {axis 0=x 1=y, at, from, to, screen?1:0}
	private long lastClickAt;
	private HudModule lastClicked;
	private final Set<HudModule> hiddenHere = new HashSet<HudModule>();
	private List<HudModule> inspected = new ArrayList<HudModule>();

	// the screen size being edited, and where it sits (GUI px)
	private int screenI;
	private float fx, fy, fk = 1, sw, sh;
	private boolean frameReady;

	// chrome
	private boolean layersOpen, panelsHidden;
	private String menu;
	private long menuAt;
	private String toast;
	private long toastAt;
	private long hintUntil = System.currentTimeMillis() + 12000;
	private float chrome = 1, inspectorK, layersK;
	private final List<Zone> zones = new ArrayList<Zone>();
	private final List<float[]> panels = new ArrayList<float[]>();
	private final Map<String, float[]> toolRects = new HashMap<String, float[]>();
	private final Map<String, Float> anim = new HashMap<String, Float>();

	// history
	private final List<Snapshot> history = new ArrayList<Snapshot>();
	private int hp = -1;
	private long lastNudgeAt;

	private static final class Zone {
		final float x, y, w, h;
		final Runnable run;

		Zone(float x, float y, float w, float h, Runnable run) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.run = run;
		}
	}

	/** The whole HUD layout and style at one moment (for undo). */
	private static final class Snapshot {
		final String label;
		final Map<HudModule, Object[]> state = new LinkedHashMap<HudModule, Object[]>();

		Snapshot(String label) {
			this.label = label;
		}
	}

	public HudEditorView(Object parent) {
		super(parent);
	}

	@Override
	public String title() {
		return "Polar HUD Editor";
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	public boolean closeOnEscape() {
		return false;
	}

	@Override
	protected void init() {
		if (history.isEmpty()) commit("Opened the HUD editor");
	}

	@Override
	public void onClose() {
		Polar.saveConfig();
		p().show(parent);
	}

	// ------------------------------------------------------------------ model

	private List<HudModule> shown() {
		List<HudModule> out = new ArrayList<HudModule>();
		for (HudModule m : Polar.modules().hud()) if (m.isEnabled()) out.add(m);
		return out;
	}

	/** Units: each group as one list, each lone element as a list of one. */
	private List<List<HudModule>> units(List<HudModule> mods) {
		List<List<HudModule>> out = new ArrayList<List<HudModule>>();
		Set<String> seen = new HashSet<String>();
		for (HudModule m : mods) {
			if (m.group() == null) out.add(single(m));
			else if (seen.add(m.group())) out.add(members(m.group()));
		}
		return out;
	}

	private List<HudModule> members(String group) {
		List<HudModule> out = new ArrayList<HudModule>();
		for (HudModule m : shown()) if (group.equals(m.group())) out.add(m);
		return out;
	}

	private List<HudModule> expand(HudModule m) {
		return m.group() == null ? single(m) : members(m.group());
	}

	private static List<HudModule> single(HudModule m) {
		List<HudModule> one = new ArrayList<HudModule>();
		one.add(m);
		return one;
	}

	private static float[] bbox(List<HudModule> list) {
		float l = 1e9f, t = 1e9f, r = -1e9f, b = -1e9f;
		for (HudModule m : list) {
			l = Math.min(l, m.lastX());
			t = Math.min(t, m.lastY());
			r = Math.max(r, m.lastX() + m.lastWidth());
			b = Math.max(b, m.lastY() + m.lastHeight());
		}
		return new float[]{l, t, r, b};
	}

	/** Re-picks a unit's anchor from which third of the screen it sits in (unless it was pinned by hand). */
	private void autoAnchor(List<HudModule> unit) {
		for (HudModule m : unit) if (m.manualAnchor()) return;
		float[] bb = bbox(unit);
		float cx = (bb[0] + bb[2]) / 2, cy = (bb[1] + bb[3]) / 2;
		float ax = cx < sw / 3 ? 0 : cx > sw * 2 / 3 ? 1 : 0.5f, ay = cy < sh / 3 ? 0 : cy > sh * 2 / 3 ? 1 : 0.5f;
		for (HudModule m : unit) m.setAnchor(ax, ay, false, Math.round(sw), Math.round(sh));
	}

	private String label(List<HudModule> mods) {
		if (mods.isEmpty()) return "nothing";
		List<List<HudModule>> us = units(mods);
		if (us.size() == 1) {
			List<HudModule> u = us.get(0);
			if (u.size() > 1 && u.get(0).group() != null) {
				String n = u.get(0).group();
				return n.matches("Group \\d+") ? n : n + " group";
			}
			return u.get(0).name();
		}
		return mods.size() + " elements";
	}

	private void group() {
		if (sel.size() < 2) {
			say("Pick two or more elements to group them");
			return;
		}
		Set<String> taken = new HashSet<String>();
		for (HudModule m : Polar.modules().hud()) if (m.group() != null) taken.add(m.group());
		int n = 1;
		while (taken.contains("Group " + n)) n++;
		String name = "Group " + n;
		for (HudModule m : sel) m.setGroup(name);
		cleanGroups();
		autoAnchor(new ArrayList<HudModule>(sel));
		commit("Grouped " + sel.size() + " elements");
		say("Grouped as " + name + ". They move together now.");
	}

	private void ungroup() {
		boolean any = false;
		for (HudModule m : sel) {
			if (m.group() != null) {
				any = true;
				m.setGroup(null);
			}
		}
		if (!any) {
			say("Nothing here is grouped");
			return;
		}
		for (List<HudModule> u : units(sel)) autoAnchor(u);
		commit("Ungrouped");
		say("Ungrouped");
	}

	/** A group of one is no group. */
	private void cleanGroups() {
		Map<String, Integer> count = new HashMap<String, Integer>();
		for (HudModule m : shown()) if (m.group() != null) count.put(m.group(), count.containsKey(m.group()) ? count.get(m.group()) + 1 : 1);
		for (HudModule m : Polar.modules().hud()) {
			if (m.group() != null && (!count.containsKey(m.group()) || count.get(m.group()) < 2)) m.setGroup(null);
		}
	}

	private void hideSelection() {
		if (sel.isEmpty()) return;
		String n = label(sel);
		for (HudModule m : sel) {
			m.setEnabled(false);
			hiddenHere.add(m);
		}
		sel.clear();
		cleanGroups();
		commit("Hid " + n);
		say("Hid " + n + ". Turn it back on in Elements.");
	}

	// ------------------------------------------------------------------ history

	private Snapshot snapshot(String label) {
		Snapshot s = new Snapshot(label);
		for (HudModule m : Polar.modules().hud()) {
			List<Setting<?>> settings = m.settings();
			Object[] values = new Object[settings.size()];
			boolean[] chroma = new boolean[settings.size()];
			for (int i = 0; i < values.length; i++) {
				values[i] = settings.get(i).get();
				if (settings.get(i) instanceof ColorSetting) chroma[i] = ((ColorSetting) settings.get(i)).chroma();
			}
			s.state.put(m, new Object[]{m.isEnabled(), m.layoutState(), m.group(), values, chroma});
		}
		return s;
	}

	@SuppressWarnings("unchecked")
	private void restore(Snapshot s) {
		for (Map.Entry<HudModule, Object[]> e : s.state.entrySet()) {
			HudModule m = e.getKey();
			Object[] st = e.getValue();
			List<Setting<?>> settings = m.settings();
			Object[] values = (Object[]) st[3];
			boolean[] chroma = (boolean[]) st[4];
			for (int i = 0; i < values.length && i < settings.size(); i++) {
				((Setting<Object>) settings.get(i)).set(values[i]);
				if (settings.get(i) instanceof ColorSetting) ((ColorSetting) settings.get(i)).setChroma(chroma[i]);
			}
			m.restoreLayout((double[]) st[1], (String) st[2]);
			m.setEnabled((Boolean) st[0]);
		}
		List<HudModule> keep = new ArrayList<HudModule>();
		for (HudModule m : sel) if (m.isEnabled()) keep.add(m);
		sel.clear();
		sel.addAll(keep);
	}

	private void commit(String label) {
		while (history.size() > hp + 1) history.remove(history.size() - 1);
		history.add(snapshot(label));
		if (history.size() > 60) history.remove(0);
		hp = history.size() - 1;
	}

	private void undo() {
		if (hp <= 0) {
			say("Nothing to undo");
			return;
		}
		String what = history.get(hp).label;
		hp--;
		restore(history.get(hp));
		say("Undid: " + what);
	}

	private void redo() {
		if (hp >= history.size() - 1) {
			say("Nothing to redo");
			return;
		}
		hp++;
		restore(history.get(hp));
		say("Redid: " + history.get(hp).label);
	}

	private void jump(int i) {
		hp = i;
		restore(history.get(i));
		say("Back to: " + history.get(i).label);
	}

	// ------------------------------------------------------------------ presets

	/** id -> {ax, ay, inset x, inset y, scale}; insets are from the pinned edge (or offsets from the centre). */
	private static Map<String, float[]> preset(String name) {
		Map<String, float[]> p = new LinkedHashMap<String, float[]>();
		if (name.equals("Minimal")) {
			p.put("fps", new float[]{0, 0, 6, 6, 1});
			p.put("coordinates", new float[]{0, 0, 6, 22, 1});
			p.put("armor_status", new float[]{0.5f, 1, 118, 2, 1});
			p.put("potion_effects", new float[]{1, 0, 6, 6, 1});
		} else if (name.equals("PvP")) {
			p.put("fps", new float[]{0, 0, 6, 6, 1});
			p.put("cps", new float[]{0, 0, 6, 22, 1});
			p.put("ping", new float[]{0, 0, 6, 38, 1});
			p.put("keystrokes", new float[]{0, 1, 6, 6, 1.25f});
			p.put("armor_status", new float[]{0.5f, 1, 118, 2, 1});
			p.put("potion_effects", new float[]{1, 0, 6, 6, 1});
			p.put("scoreboard", new float[]{1, 0.5f, 4, 0, 1});
		} else if (name.equals("Streamer")) {
			p.put("watermark", new float[]{0, 0, 8, 8, 1.5f});
			p.put("clock", new float[]{1, 0, 8, 8, 1.25f});
			p.put("fps", new float[]{0, 0, 8, 36, 1});
			p.put("ping", new float[]{0, 0, 8, 52, 1});
			p.put("keystrokes", new float[]{1, 1, 8, 8, 1});
			p.put("potion_effects", new float[]{1, 0, 8, 30, 1});
			p.put("scoreboard", new float[]{1, 0.5f, 6, 0, 1});
		} else if (name.equals("Builder")) {
			p.put("coordinates", new float[]{0, 0, 6, 6, 1.15f});
			p.put("compass", new float[]{0.5f, 0, 0, 6, 1.2f});
			p.put("clock", new float[]{1, 0, 6, 6, 1});
			p.put("fps", new float[]{0, 0, 6, 24, 1});
			p.put("armor_status", new float[]{0.5f, 1, 118, 2, 1});
			p.put("scoreboard", new float[]{1, 0.5f, 4, 0, 1});
		}
		return p;
	}

	private static String presetNote(String name) {
		if (name.equals("Minimal")) return "FPS, coordinates, armor and effects";
		if (name.equals("PvP")) return "Keystrokes, CPS, ping and armor";
		if (name.equals("Streamer")) return "Big watermark and clock, clean corners";
		return "Coordinates, compass and clock";
	}

	private void applyPreset(String name) {
		Map<String, float[]> p = preset(name);
		int w = Math.round(sw), h = Math.round(sh);
		NullGfx measure = new NullGfx(w, h);
		for (HudModule m : Polar.modules().hud()) {
			float[] v = p.get(m.id());
			m.setGroup(null);
			if (v == null) {
				m.setEnabled(false);
				continue;
			}
			m.setEnabled(true);
			m.resetSettings();
			m.scale.set((double) v[4]);
			m.restoreLayout(new double[]{v[0], v[1], 0, 0, 0, 0}, null);
			m.render(measure, w, h, true); // so the size used below is this element's real size
			int ew = m.lastWidth(), eh = m.lastHeight();
			float dx = v[0] == 0.5f ? v[2] : v[2] * (v[0] == 0 ? 1 : -1), dy = v[1] == 0.5f ? v[3] : v[3] * (v[1] == 0 ? 1 : -1);
			m.moveTo(v[0] * (w - ew) + dx, v[1] * (h - eh) + dy, w, h);
		}
		if (name.equals("PvP")) makeGroup("Stats", "fps", "cps", "ping");
		sel.clear();
	}

	private void makeGroup(String name, String... ids) {
		List<HudModule> list = new ArrayList<HudModule>();
		for (HudModule m : Polar.modules().hud()) {
			for (String id : ids) if (m.id().equals(id) && m.isEnabled()) list.add(m);
		}
		if (list.size() < 2) return;
		for (HudModule m : list) m.setGroup(name);
		autoAnchor(list);
	}

	private void resetToDefault() {
		for (HudModule m : Polar.modules().hud()) {
			m.resetSettings();
			m.resetPosition();
			m.setEnabled(m.enabledByDefault());
		}
		sel.clear();
	}

	// ------------------------------------------------------------------ frame

	private float a(String id, float target, float speed) {
		Float v = anim.get(id);
		float nv = v == null ? target : Ui.approach(v, target, speed);
		anim.put(id, nv);
		return nv;
	}

	private void updateFrame() {
		boolean real = screenI == 0;
		float tw = real ? width : Float.parseFloat(SCREENS[screenI][2]), th = real ? height : Float.parseFloat(SCREENS[screenI][3]);
		float k = real ? 1 : Math.min((width - 40) / tw, (height - 96) / th);
		float tx = real ? 0 : (width - tw * k) / 2, ty = real ? 0 : 40 + (height - 96 - th * k) / 2;
		if (!frameReady) {
			sw = tw;
			sh = th;
			fk = k;
			fx = tx;
			fy = ty;
			frameReady = true;
		}
		sw = Ui.approach(sw, tw, 9f);
		sh = Ui.approach(sh, th, 9f);
		fk = Ui.approach(fk, k, 9f);
		fx = Ui.approach(fx, tx, 9f);
		fy = Ui.approach(fy, ty, 9f);
	}

	private float toFrameX(double mx) {
		return (float) ((mx - fx) / fk);
	}

	private float toFrameY(double my) {
		return (float) ((my - fy) / fk);
	}

	private float sx(float x) {
		return fx + x * fk;
	}

	private float sy(float y) {
		return fy + y * fk;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
		if (!p().inWorld()) MenuBackground.INSTANCE.render(g, width, height, mouseX, mouseY);
		g.fill(0, 0, width, height, screenI == 0 ? 0x38040A16 : 0xCC040A16);
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY, float partial) {
		Ui.frame();
		updateFrame();
		zones.clear();
		panels.clear();
		int iw = Math.round(sw), ih = Math.round(sh);
		boolean real = screenI == 0 && Math.abs(fk - 1) < 0.01f && Math.abs(fx) < 0.5f;
		boolean dragging = drag == Drag.MOVE && moved;
		float smx = toFrameX(mouseX), smy = toFrameY(mouseY);

		// ---- the screen being edited
		if (!real) {
			for (int k = 4; k >= 1; k--) Ui.rect(g, fx - k * 3, fy - k * 2 + 4, sw * fk + k * 6, sh * fk + k * 4, k * 3, 0x14000000);
			g.fill(Math.round(fx), Math.round(fy), Math.round(fx + sw * fk), Math.round(fy + sh * fk), 0xFF0E1B33);
			g.gradient(Math.round(fx), Math.round(fy), Math.round(fx + sw * fk), Math.round(fy + sh * fk), 0x401E3C64, 0x00000000);
		}
		g.push();
		g.translate(fx, fy);
		g.scale(fk);
		g.scissor(0, 0, iw, ih);
		float ga = a("grid", grid ? (dragging ? 0.5f : 0.2f) : 0, 10f);
		if (ga > 0.01f) drawGrid(g, iw, ih, ga);
		if (!real) drawVanillaHud(g, iw, ih);
		List<HudModule> mods = shown();
		if (drag == Drag.NONE || drag == Drag.SLIDER) hover = null;
		for (HudModule m : mods) {
			m.render(g, iw, ih, true);
			if (drag == Drag.NONE && Ui.inside(smx, smy, 0, 0, iw, ih) && m.contains(smx, smy)) hover = m;
		}
		g.endScissor();
		g.pop();

		// ---- outlines, groups, selection, guides (in screen space so lines stay thin)
		Set<HudModule> overlaps = overlaps(mods);
		for (HudModule m : mods) {
			boolean isSel = sel.contains(m);
			float hv = a("hv" + m.id(), hover == m && !isSel ? 1 : 0, 18f);
			if (hv > 0.01f) dashedRect(g, sx(m.lastX()) - 1.5f, sy(m.lastY()) - 1.5f, m.lastWidth() * fk + 3, m.lastHeight() * fk + 3, Theme.alpha(0xFFC8EBFF, 0.8f * hv), 2, 1.6f);
			if (overlaps.contains(m) && !dragging) outline(g, sx(m.lastX()) - 1, sy(m.lastY()) - 1, m.lastWidth() * fk + 2, m.lastHeight() * fk + 2, 0xD9FF8A7A);
		}
		drawGroups(g, mods);
		drawSelection(g, dragging);
		for (float[] gl : guides) {
			int c = gl[4] > 0 ? SCREEN_GUIDE : GUIDE;
			if (gl[0] == 0) Christmas.rect(g, sx(gl[1]) - 0.25f, sy(gl[2]), 0.5f, (gl[3] - gl[2]) * fk, c);
			else Christmas.rect(g, sx(gl[2]), sy(gl[1]) - 0.25f, (gl[3] - gl[2]) * fk, 0.5f, c);
		}
		if (drag == Drag.MARQUEE) {
			float l = Math.min(dragX0, marqueeX1), t = Math.min(dragY0, marqueeY1), w = Math.abs(marqueeX1 - dragX0), h = Math.abs(marqueeY1 - dragY0);
			Christmas.rect(g, sx(l), sy(t), w * fk, h * fk, 0x1A5AD8FF);
			outline(g, sx(l), sy(t), w * fk, h * fk, 0xCC5AD8FF);
		}
		if (!real) {
			outline(g, fx, fy, sw * fk, sh * fk, 0x80A0D7FF);
			if (screenI != 0) drawPreviewCaption(g, overlaps.size());
		}

		// ---- editor tools (they fade while you drag)
		boolean busy = dragging || drag == Drag.SCALE || drag == Drag.MARQUEE;
		chrome = Ui.approach(chrome, busy ? 0.12f : panelsHidden ? 0 : 1, 12f);
		if (chrome > 0.05f) {
			drawLayers(g, mouseX, mouseY);
			drawInspector(g, mouseX, mouseY);
			drawToolbar(g, mouseX, mouseY);
		}
		if (panelsHidden) Ui.textRight(g, "Tab shows the editor tools", width - 10, height - 10, 0x99DCE8F6, Fonts.SMALL);
		if (menu != null) drawMenu(g, mouseX, mouseY);
		drawToast(g);
		if (mods.isEmpty()) Ui.textCentered(g, "No HUD mods are on. Pick a preset or turn some on in Elements.", width / 2f, height / 2f - 40, TEXT, Fonts.BODY);
	}

	/** Alpha used for the tools (they fade while you drag). */
	private int ca(int argb) {
		return Theme.alpha(argb, ((argb >>> 24) / 255f) * Math.min(1, chrome));
	}

	/** One tile of the dot grid (8 x 8 dots), made for the current GUI scale and drawn over and over. */
	private static Object gridTile;
	private static int gridTileScale, gridTileDot;
	private static final int DOTS_PER_TILE = 8;

	private void drawGrid(Gfx g, int w, int h, float alpha) {
		int s = Math.max(1, Math.round(p().guiScale() * fk));
		int c = Theme.alpha(0xFFC8E6FF, alpha);
		int d = Math.max(1, Math.round(s * 0.6f));
		Object tile = gridTile(s, d);
		g.push();
		g.scale(1f / s);
		if (tile != null) {
			// ~130 textured quads instead of one fill per dot (8,000+ at GUI scale 2 on a 1080p screen)
			int t = 8 * s * DOTS_PER_TILE;
			for (int x = 0; x < w * s; x += t) for (int y = 0; y < h * s; y += t) g.blitHandle(tile, x, y, t, t, c);
		} else {
			for (int x = 8; x < w; x += 8) {
				for (int y = 8; y < h; y += 8) {
					int px = x * s - d / 2, py = y * s - d / 2;
					g.fill(px, py, px + d, py + d, c);
				}
			}
		}
		g.pop();
	}

	private Object gridTile(int s, int d) {
		if (gridTile != null && gridTileScale == s && gridTileDot == d) return gridTile;
		int t = 8 * s * DOTS_PER_TILE;
		if (gridTile != null) p().destroyTexture(gridTile);
		gridTile = p().createTexture("hud_grid", t, t);
		gridTileScale = s;
		gridTileDot = d;
		if (gridTile == null) return null;
		int[] px = new int[t * t];
		// a dot centred on every grid point; the ones on the tile's edges wrap round so tiles join up
		for (int i = 0; i < DOTS_PER_TILE; i++) {
			for (int j = 0; j < DOTS_PER_TILE; j++) {
				int cx = i * 8 * s - d / 2, cy = j * 8 * s - d / 2;
				for (int a = 0; a < d; a++) {
					for (int b = 0; b < d; b++) {
						int x = Math.floorMod(cx + a, t), y = Math.floorMod(cy + b, t);
						px[y * t + x] = 0xFFFFFFFF;
					}
				}
			}
		}
		p().uploadTexture(gridTile, px);
		return gridTile;
	}

	/** In a preview the real hotbar isn't in the frame: draw its outline so you can see what you're working around. */
	private void drawVanillaHud(Gfx g, int w, int h) {
		float x = w / 2f - 91, y = h - 22;
		Ui.rect(g, x, y, 182, 22, 2, 0x66000000);
		Ui.ring(g, x, y, 182, 22, 2, 0x33FFFFFF, 1f);
		for (int i = 1; i < 9; i++) Christmas.rect(g, x + i * 20 + 1, y + 2, 0.6f, 18, 0x26FFFFFF);
		Christmas.rect(g, x, h - 29, 182, 2, 0x804CD964);
		for (int i = 0; i < 10; i++) {
			Ui.circle(g, x + 4 + i * 8, h - 35, 3, 0xCCE0404A);
			Ui.circle(g, x + 182 - 4 - i * 8, h - 35, 3, 0xCCC8783A);
		}
	}

	private Set<HudModule> overlaps(List<HudModule> mods) {
		Set<HudModule> out = new HashSet<HudModule>();
		for (int i = 0; i < mods.size(); i++) {
			HudModule a = mods.get(i);
			for (int j = i + 1; j < mods.size(); j++) {
				HudModule b = mods.get(j);
				if (a.lastX() < b.lastX() + b.lastWidth() && a.lastX() + a.lastWidth() > b.lastX()
					&& a.lastY() < b.lastY() + b.lastHeight() && a.lastY() + a.lastHeight() > b.lastY()) {
					out.add(a);
					out.add(b);
				}
			}
		}
		return out;
	}

	/** Line width of one screen pixel... two, so it reads at high GUI scales. */
	private float px() {
		return 2f / Math.max(1, p().guiScale());
	}

	private void outline(Gfx g, float x, float y, float w, float h, int c) {
		float t = px();
		Christmas.rect(g, x, y, w, t, c);
		Christmas.rect(g, x, y + h - t, w, t, c);
		Christmas.rect(g, x, y, t, h, c);
		Christmas.rect(g, x + w - t, y, t, h, c);
	}

	private void dashedRect(Gfx g, float x, float y, float w, float h, int c, float dash, float gap) {
		float t = px();
		for (float d = 0; d < w; d += dash + gap) {
			float len = Math.min(dash, w - d);
			Christmas.rect(g, x + d, y, len, t, c);
			Christmas.rect(g, x + d, y + h - t, len, t, c);
		}
		for (float d = 0; d < h; d += dash + gap) {
			float len = Math.min(dash, h - d);
			Christmas.rect(g, x, y + d, t, len, c);
			Christmas.rect(g, x + w - t, y + d, t, len, c);
		}
	}

	private void dashedLine(Gfx g, float x0, float y0, float x1, float y1, int c) {
		float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 1) return;
		for (float d = 0; d < len; d += 4) {
			float e = Math.min(d + 2, len);
			Christmas.line(g, x0 + dx * d / len, y0 + dy * d / len, x0 + dx * e / len, y0 + dy * e / len, px(), c);
		}
	}

	private void drawGroups(Gfx g, List<HudModule> mods) {
		Set<String> done = new HashSet<String>();
		for (HudModule m : mods) {
			String gname = m.group();
			if (gname == null || !done.add(gname)) continue;
			List<HudModule> mem = members(gname);
			boolean on = false;
			for (HudModule x : mem) if (sel.contains(x) || hover == x) on = true;
			float ga = a("gf" + gname, on ? 1 : 0, 14f);
			if (ga < 0.01f) continue;
			float[] bb = bbox(mem);
			float l = sx(bb[0]) - 3.5f, t = sy(bb[1]) - 3.5f, r = sx(bb[2]) + 3.5f, b = sy(bb[3]) + 3.5f;
			dashedRect(g, l, t, r - l, b - t, Theme.alpha(GOLD, 0.85f * ga), 3, 2);
			float tw = Fonts.width(gname, Fonts.SMALL) + 8, ly = t - 10 < 0 ? b + 1 : t - 10;
			Ui.rect(g, l, ly, tw, 9, 2.5f, Theme.alpha(0xF2FFD36B, ga));
			Ui.textMid(g, gname, l + 4, ly + 4.5f, Theme.alpha(0xFF2A1E00, ga), Fonts.SMALL);
		}
	}

	/** Corner handle for resizing: on the side away from the pinned edge, when one lone element is selected. */
	private float[] handle() {
		List<List<HudModule>> us = units(sel);
		if (us.size() != 1 || us.get(0).size() != 1) return null;
		HudModule m = us.get(0).get(0);
		float hx = m.anchorX() == 1 ? m.lastX() : m.lastX() + m.lastWidth(), hy = m.anchorY() == 1 ? m.lastY() : m.lastY() + m.lastHeight();
		return new float[]{hx, hy};
	}

	private void drawSelection(Gfx g, boolean dragging) {
		if (sel.isEmpty()) return;
		for (HudModule m : sel) outline(g, sx(m.lastX()) - 1, sy(m.lastY()) - 1, m.lastWidth() * fk + 2, m.lastHeight() * fk + 2, 0xF25AD8FF);
		float[] all = bbox(sel);
		if (!dragging) {
			// a dashed line from each unit to the edge it is pinned to, with a diamond on the edge
			for (List<HudModule> u : units(sel)) {
				float[] bb = bbox(u);
				HudModule e0 = u.get(0);
				float pxx = bb[0] + (bb[2] - bb[0]) * e0.anchorX(), pyy = bb[1] + (bb[3] - bb[1]) * e0.anchorY();
				float ex = sw * e0.anchorX(), ey = sh * e0.anchorY();
				dashedLine(g, sx(pxx), sy(pyy), sx(ex), sy(ey), 0xB35AD8FF);
				float dx = Math.max(sx(0) + 3, Math.min(sx(sw) - 3, sx(ex))), dy = Math.max(sy(0) + 3, Math.min(sy(sh) - 3, sy(ey)));
				g.push();
				g.translate(dx, dy);
				g.rotate(45);
				Christmas.rect(g, -2.2f, -2.2f, 4.4f, 4.4f, ACC);
				g.pop();
				Ui.circle(g, sx(pxx), sy(pyy), 1.6f, 0xFFFFFFFF);
			}
		}
		if (sel.size() > 1) outline(g, sx(all[0]) - 3, sy(all[1]) - 3, (all[2] - all[0]) * fk + 6, (all[3] - all[1]) * fk + 6, 0x595AD8FF);
		float[] hd = handle();
		if (hd != null) {
			Ui.rect(g, sx(hd[0]) - 2.4f, sy(hd[1]) - 2.4f, 4.8f, 4.8f, 1, ACC);
			Ui.rect(g, sx(hd[0]) - 1.6f, sy(hd[1]) - 1.6f, 3.2f, 3.2f, 0.6f, 0xFFFFFFFF);
		}
		// name and position readout
		String name = label(sel);
		String extra = drag == Drag.SCALE && scaling != null ? "   " + Math.round(scaling.scale.get() * 100) + "%"
			: dragging ? "   x " + Math.round(all[0]) + "  y " + Math.round(all[1]) : "";
		String s = Character.toUpperCase(name.charAt(0)) + name.substring(1) + extra;
		float pw = Fonts.width(s, Fonts.BOLD) + 8, ph = 11;
		float py = sy(all[1]) - 4 - ph < 0 ? sy(all[3]) + 4 : sy(all[1]) - 4 - ph;
		float pxx = Math.max(0, Math.min(width - pw, sx(all[0]) - 1));
		Ui.rect(g, pxx, py, pw, ph, 3, ACC);
		Ui.textMid(g, s, pxx + 4, py + ph / 2f, 0xFF05203A, Fonts.BOLD);
	}

	private void drawPreviewCaption(Gfx g, int overlapCount) {
		String[] scr = SCREENS[screenI];
		float cy = fy + sh * fk + 14;
		String s1 = "Previewing " + scr[0] + ", " + scr[1];
		String s2 = overlapCount > 0 ? overlapCount + " elements overlap on this size" : "Everything fits";
		float w1 = Fonts.width(s1, Fonts.BOLD), w = w1 + Fonts.width(s2, Fonts.SMALL) + 34, x = width / 2f - w / 2;
		Ui.rect(g, x, cy - 8, w, 16, 8, GLASS);
		Ui.ring(g, x, cy - 8, w, 16, 8, LINE, 1f);
		Ui.textMid(g, s1, x + 10, cy, TEXT, Fonts.BOLD);
		Ui.circle(g, x + 18 + w1, cy, 2.4f, overlapCount > 0 ? WARN : 0xFF5FE3A1);
		Ui.textMid(g, s2, x + 24 + w1, cy, overlapCount > 0 ? 0xFFFFC2B8 : 0xFFBDF5D6, Fonts.SMALL);
	}

	// ------------------------------------------------------------------ panels

	private void glass(Gfx g, float x, float y, float w, float h, float r) {
		for (int k = 3; k >= 1; k--) Ui.rect(g, x - k * 2, y - k * 1.5f + 3, w + k * 4, h + k * 3, r + k * 2, ca(0x12000000));
		Ui.rect(g, x, y, w, h, r, ca(GLASS));
		Ui.ring(g, x, y, w, h, r, ca(0x40A0D7FF), 1f);
		zones.add(new Zone(x, y, w, h, null));
	}

	/** Panels go on the side away from what you're editing. */
	private boolean panelsLeft() {
		if (sel.isEmpty()) return false;
		float cx = 0;
		for (HudModule m : sel) cx += m.lastX() + m.lastWidth() / 2f;
		cx /= sel.size();
		return sx(cx) > width / 2f;
	}

	private void drawLayers(Gfx g, int mx, int my) {
		layersK = Ui.approach(layersK, layersOpen ? 1 : 0, 14f);
		if (layersK < 0.01f) return;
		boolean left = !panelsLeft();
		float pw = 132;
		List<Object> rows = new ArrayList<Object>();
		Set<HudModule> done = new HashSet<HudModule>();
		for (HudModule m : Polar.modules().hud()) {
			if (!(m.isEnabled() || hiddenHere.contains(m)) || done.contains(m)) continue;
			if (m.group() != null && m.isEnabled()) {
				rows.add(m.group());
				for (HudModule x : members(m.group())) {
					rows.add(new Object[]{x, true});
					done.add(x);
				}
			} else {
				rows.add(new Object[]{m, false});
				done.add(m);
			}
		}
		float ph = Math.min(height - 64, 30 + (rows.size() + 2) * 15 + 6);
		float x = a("lpx", left ? 10 : width - pw - 10, 12f), y = 52;
		g.push();
		g.translate((1 - layersK) * (left ? -10 : 10), 0);
		glass(g, x, y, pw, ph, 10);
		panels.add(new float[]{x, y, pw, ph});
		Ui.textMid(g, "Elements", x + 10, y + 13, ca(TEXT), Fonts.BOLD);
		Ui.textRight(g, shown().size() + " shown", x + pw - 10, y + 13, ca(MUTED), Fonts.SMALL);
		float yy = y + 26, rx = x + 5, rw = pw - 10, bottom = y + ph - 36;
		for (Object r : rows) {
			if (yy + 14 > bottom) break;
			if (r instanceof String) {
				final List<HudModule> mem = members((String) r);
				boolean on = sel.containsAll(mem);
				boolean hv = Ui.inside(mx, my, rx, yy, rw, 14);
				if (on || hv) Ui.rect(g, rx, yy, rw, 14, 4, ca(on ? 0x24FFD36B : 0x0DFFFFFF));
				Ui.icon(g, Icon.WIDGETS, rx + 9, yy + 7, ca(GOLD), Fonts.ICON_S);
				Ui.textMid(g, (String) r, rx + 18, yy + 7, ca(0xFFFFE6A8), Fonts.BOLD);
				zones.add(new Zone(rx, yy, rw, 14, () -> {
					if (!p().shiftDown()) sel.clear();
					for (HudModule m : mem) if (!sel.contains(m)) sel.add(m);
				}));
				yy += 15;
				continue;
			}
			final HudModule m = (HudModule) ((Object[]) r)[0];
			boolean inGroup = (Boolean) ((Object[]) r)[1];
			boolean on = sel.contains(m), vis = m.isEnabled();
			boolean hv = Ui.inside(mx, my, rx, yy, rw, 14);
			if (on || hv) Ui.rect(g, rx, yy, rw, 14, 4, ca(on ? 0x295AD8FF : 0x0DFFFFFF));
			float ind = inGroup ? 10 : 0;
			if (inGroup) Christmas.rect(g, rx + 8.5f, yy, 0.8f, 14, ca(0x59FFD36B));
			Ui.textMid(g, Fonts.ellipsize(m.name(), Fonts.BODY, Math.round(rw - 30 - ind)), rx + 8 + ind, yy + 7, ca(vis ? (on ? 0xFFFFFFFF : 0xFFD6E1EF) : DIM), Fonts.BODY);
			float ex = rx + rw - 9;
			boolean eh = Ui.inside(mx, my, ex - 6, yy, 12, 14);
			Ui.icon(g, vis ? Icon.EYE : Icon.VISIBILITY_OFF, ex, yy + 7, ca(vis ? (eh ? 0xFFFFFFFF : 0xFF9FB3CE) : DIM), Fonts.ICON_S);
			zones.add(new Zone(rx, yy, rw - 14, 14, () -> {
				if (!m.isEnabled()) return;
				if (!p().shiftDown()) sel.clear();
				if (!sel.contains(m)) sel.add(m);
			}));
			zones.add(new Zone(ex - 6, yy, 12, 14, () -> {
				m.setEnabled(!m.isEnabled());
				if (!m.isEnabled()) {
					sel.remove(m);
					hiddenHere.add(m);
				}
				cleanGroups();
				commit((m.isEnabled() ? "Showed " : "Hid ") + m.name());
			}));
			yy += 15;
		}
		yy = y + ph - 34;
		Ui.icon(g, Icon.LOCK, rx + 9, yy + 7, ca(DIM), Fonts.ICON_S);
		Ui.textMid(g, "Hotbar and hearts", rx + 18, yy + 7, ca(DIM), Fonts.BODY);
		yy += 16;
		boolean ah = Ui.inside(mx, my, rx, yy, rw, 14);
		Ui.icon(g, Icon.ADD, rx + 9, yy + 7, ca(ah ? 0xFFFFFFFF : ACC), Fonts.ICON_S);
		Ui.textMid(g, "More HUD mods", rx + 18, yy + 7, ca(ah ? 0xFFFFFFFF : ACC), Fonts.BODY);
		zones.add(new Zone(rx, yy, rw, 14, () -> p().open(new PolarMenuView(p().currentScreen()))));
		g.pop();
	}

	private static final String[] ANCHOR_NAMES = {"top left", "top", "top right", "left", "center", "right", "bottom left", "bottom", "bottom right"};

	private static String anchorName(float ax, float ay) {
		return ANCHOR_NAMES[Math.round(ay * 2) * 3 + Math.round(ax * 2)];
	}

	private void drawInspector(Gfx g, int mx, int my) {
		inspectorK = Ui.approach(inspectorK, sel.isEmpty() ? 0 : 1, 14f);
		if (!sel.isEmpty()) inspected = new ArrayList<HudModule>(sel);
		if (inspectorK < 0.01f || inspected.isEmpty()) return;
		final List<HudModule> list = inspected;
		final HudModule e0 = list.get(0);
		boolean left = panelsLeft();
		float pw = 160;
		BooleanSetting bg = bool(e0, "background"), shadow = bool(e0, "shadow");
		ColorSetting bgc = color(e0, "background_color");
		ModeSetting border = mode(e0, "border");
		float ph = 40 + 52 + (bg != null ? 17 : 0) + (border != null ? 17 : 0) + (shadow != null ? 17 : 0) + 3 + 40 + 22 + 22;
		float x = a("ipx", left ? 10 : width - pw - 10, 12f), y = Math.max(8, Math.min(52, height - ph - 8));
		g.push();
		g.translate((1 - inspectorK) * (left ? -10 : 10), 0);
		glass(g, x, y, pw, ph, 10);
		panels.add(new float[]{x, y, pw, ph});
		final float P = x + 12, RW = pw - 24;
		final String title = label(list);
		Ui.textMid(g, Fonts.ellipsize(Character.toUpperCase(title.charAt(0)) + title.substring(1), Fonts.LARGE, Math.round(RW)), P, y + 15, ca(TEXT), Fonts.LARGE);
		Ui.textMid(g, "Pinned to the " + anchorName(e0.anchorX(), e0.anchorY()) + (e0.manualAnchor() ? "" : ", automatic"), P, y + 27, ca(MUTED), Fonts.SMALL);
		float yy = y + 40;
		yy = slider(g, "size", "Size", (float) (double) e0.scale.get(), 0.5f, 2.5f, Math.round(e0.scale.get() * 100) + "%", P, RW, yy, mx, my);
		yy = slider(g, "op", "Opacity", (float) (double) e0.opacity.get(), 10, 100, Math.round(e0.opacity.get()) + "%", P, RW, yy, mx, my);
		if (bg != null) {
			final boolean on = bg.on();
			yy = toggleRow(g, "Background", on, P, RW, yy, mx, my, () -> {
				for (HudModule m : list) if (bool(m, "background") != null) bool(m, "background").set(!on);
				commit((on ? "Turned off" : "Turned on") + " the background of " + title);
			});
			int cur = bgc == null ? -1 : bgc.get() & 0xFFFFFF;
			for (int i = 0; i < BOX_BG.length; i++) {
				final int rgb = BOX_BG[i];
				float cx = P + RW - 30 - (BOX_BG.length - 1 - i) * 12, cy = yy - 17 + 8;
				swatch(g, cx, cy, 0xFF000000 | rgb, cur == rgb, on);
				zones.add(new Zone(cx - 5.5f, cy - 5.5f, 11, 11, () -> {
					for (HudModule m : list) {
						ColorSetting c = color(m, "background_color");
						if (c != null) c.set((c.get() & 0xFF000000) | rgb);
						if (bool(m, "background") != null) bool(m, "background").set(true);
					}
					commit("Changed the background of " + title);
				}));
			}
		}
		if (border != null) {
			final boolean on = !border.is("None");
			yy = toggleRow(g, "Border", on, P, RW, yy, mx, my, () -> {
				for (HudModule m : list) if (mode(m, "border") != null) mode(m, "border").set(on ? "None" : "Ice");
				commit((on ? "Turned off" : "Turned on") + " the border of " + title);
			});
			for (int i = 0; i < BORDERS.length; i++) {
				final String b = BORDERS[i];
				float cx = P + RW - 30 - (BORDERS.length - 1 - i) * 12, cy = yy - 17 + 8;
				swatch(g, cx, cy, TextHudModule.BORDER_COLORS[i], border.is(b), on);
				zones.add(new Zone(cx - 5.5f, cy - 5.5f, 11, 11, () -> {
					for (HudModule m : list) if (mode(m, "border") != null) mode(m, "border").set(b);
					commit("Changed the border of " + title);
				}));
			}
		}
		if (shadow != null) {
			final boolean on = shadow.on();
			yy = toggleRow(g, "Text shadow", on, P, RW, yy, mx, my, () -> {
				for (HudModule m : list) if (bool(m, "shadow") != null) bool(m, "shadow").set(!on);
				commit((on ? "Turned off" : "Turned on") + " text shadow for " + title);
			});
		}
		// pin picker
		yy += 3;
		Christmas.rect(g, P, yy - 3, RW, 0.6f, ca(LINE));
		Ui.textMid(g, "Pin to", P, yy + 8, ca(0xFFE4ECF7), Fonts.BODY);
		float gx = P + RW - 44, gy = yy, cell = 12;
		Ui.rect(g, gx - 3, gy - 1, cell * 3 + 6, cell * 3 + 2, 4, ca(0xCC040A18));
		Ui.ring(g, gx - 3, gy - 1, cell * 3 + 6, cell * 3 + 2, 4, ca(LINE), 1f);
		for (int j = 0; j < 3; j++) {
			for (int i = 0; i < 3; i++) {
				final float ax = i * 0.5f, ay = j * 0.5f;
				float cx = gx + i * cell + cell / 2, cy = gy + j * cell + cell / 2;
				boolean on = e0.anchorX() == ax && e0.anchorY() == ay, hv = Ui.inside(mx, my, cx - 6, cy - 6, 12, 12);
				if (on) Ui.glow(g, cx, cy, 8, 8, ca(0x4D5AD8FF));
				Ui.circle(g, cx, cy, on ? 3 : 1.7f, ca(on ? ACC : hv ? 0xFFFFFFFF : 0xFF6C819F));
				zones.add(new Zone(cx - 6, cy - 6, 12, 12, () -> {
					for (List<HudModule> u : units(list)) for (HudModule m : u) m.setAnchor(ax, ay, true, Math.round(sw), Math.round(sh));
					commit("Pinned " + title + " to the " + anchorName(ax, ay));
					say("Pinned to the " + anchorName(ax, ay) + ". It stays there on any screen size.");
				}));
			}
		}
		Ui.textMid(g, "Keeps its place", P, yy + 19, ca(MUTED), Fonts.SMALL);
		Ui.textMid(g, "on any screen", P, yy + 27, ca(MUTED), Fonts.SMALL);
		yy += 40;
		final boolean auto = !e0.manualAnchor();
		yy = toggleRow(g, "Pick the nearest edge", auto, P, RW, yy, mx, my, () -> {
			for (List<HudModule> u : units(list)) {
				for (HudModule m : u) m.setAnchor(m.anchorX(), m.anchorY(), auto, Math.round(sw), Math.round(sh));
				if (!auto) autoAnchor(u);
			}
			commit(auto ? "Fixed pin for " + title : "Automatic pin for " + title);
		});
		yy += 5;
		// actions
		boolean multi = units(list).size() > 1, grouped = false;
		for (HudModule m : list) if (m.group() != null) grouped = true;
		final boolean firstOn = multi || grouped, doGroup = multi;
		float bw = (RW - 8) / 3;
		String[] labels = {multi || !grouped ? "Group" : "Ungroup", "Hide", "Reset"};
		for (int i = 0; i < 3; i++) {
			float bx = P + i * (bw + 4);
			boolean hv = Ui.inside(mx, my, bx, yy, bw, 17);
			Ui.rect(g, bx, yy, bw, 17, 5, ca(hv ? 0xF21E3C68 : 0xF2102242));
			Ui.ring(g, bx, yy, bw, 17, 5, ca(i == 0 && firstOn ? 0x80FFD36B : LINE), 1f);
			Ui.textCentered(g, labels[i], bx + bw / 2, yy + 8.5f, ca(i == 0 && !firstOn ? DIM : 0xFFFFFFFF), Fonts.BODY);
			final int idx = i;
			zones.add(new Zone(bx, yy, bw, 17, () -> {
				if (idx == 0) {
					if (doGroup) group();
					else if (firstOn) ungroup();
					else say("Pick two or more elements to group them");
				} else if (idx == 1) {
					hideSelection();
				} else {
					for (HudModule m : list) resetStyle(m);
					commit("Reset the style of " + title);
				}
			}));
		}
		g.pop();
	}

	private static void resetStyle(HudModule m) {
		for (String id : new String[]{"scale", "opacity", "background", "background_color", "border", "shadow", "text_color"}) {
			Setting<?> s = setting(m, id);
			if (s != null) s.reset();
		}
	}

	private static Setting<?> setting(HudModule m, String id) {
		for (Setting<?> s : m.settings()) if (s.id().equals(id)) return s;
		return null;
	}

	private static BooleanSetting bool(HudModule m, String id) {
		Setting<?> s = setting(m, id);
		return s instanceof BooleanSetting ? (BooleanSetting) s : null;
	}

	private static ColorSetting color(HudModule m, String id) {
		Setting<?> s = setting(m, id);
		return s instanceof ColorSetting ? (ColorSetting) s : null;
	}

	private static ModeSetting mode(HudModule m, String id) {
		Setting<?> s = setting(m, id);
		return s instanceof ModeSetting ? (ModeSetting) s : null;
	}

	private void swatch(Gfx g, float cx, float cy, int argb, boolean on, boolean enabled) {
		float al = enabled ? 1 : 0.35f;
		Ui.circle(g, cx, cy, 4.3f, ca(Theme.alpha(0xFFFFFFFF, 0.35f * al)));
		Ui.circle(g, cx, cy, 3.9f, ca(Theme.alpha(0xFF000000 | argb, al)));
		if (on) Ui.ring(g, cx - 5.6f, cy - 5.6f, 11.2f, 11.2f, 5.6f, ca(Theme.alpha(ACC, al)), 1.1f);
	}

	private float slider(Gfx g, final String id, String label, float v, final float min, final float max, String value,
						 final float P, final float RW, float yy, int mx, int my) {
		Ui.textMid(g, label, P, yy + 5, ca(0xFFE4ECF7), Fonts.BODY);
		Ui.textRight(g, value, P + RW, yy + 5, ca(ACC), Fonts.BOLD);
		float ty = yy + 15, f = a("isl" + id, (v - min) / (max - min), 20f);
		boolean hv = Ui.inside(mx, my, P - 3, ty - 6, RW + 6, 12);
		Ui.rect(g, P, ty - 1.5f, RW, 3, 1.5f, ca(0x2EA0C8FF));
		Ui.gradientRect(g, P, ty - 1.5f, Math.max(3, RW * f), 3, 1.5f, ca(0xFF3FA9E8), ca(0xFF7FE2FF));
		Ui.glow(g, P + RW * f, ty, 9, 9, ca(Theme.alpha(ACC, hv ? 0.4f : 0.22f)));
		Ui.circle(g, P + RW * f, ty, hv ? 4.4f : 3.8f, ca(0xFFFFFFFF));
		zones.add(new Zone(P - 4, ty - 7, RW + 8, 14, () -> {
			drag = Drag.SLIDER;
			sliderId = id;
			sliderX = P;
			sliderW = RW;
			sliderMin = min;
			sliderMax = max;
		}));
		return yy + 26;
	}

	private void applySlider(double mx) {
		float f = Math.max(0, Math.min(1, (float) ((mx - sliderX) / sliderW)));
		double v = sliderMin + f * (sliderMax - sliderMin);
		for (HudModule m : inspected) {
			if (sliderId.equals("size")) m.scale.set(Math.round(v * 20) / 20.0);
			else m.opacity.set((double) (Math.round(v / 5) * 5));
		}
	}

	private float toggleRow(Gfx g, String label, boolean on, float P, float RW, float yy, int mx, int my, Runnable flip) {
		boolean hv = Ui.inside(mx, my, P - 4, yy, RW + 8, 16);
		if (hv) Ui.rect(g, P - 4, yy, RW + 8, 16, 5, ca(0x0AFFFFFF));
		Ui.textMid(g, label, P, yy + 8, ca(0xFFE4ECF7), Fonts.BODY);
		float k = on ? 1 : 0, x = P + RW - 20, y = yy + 2.5f;
		if (on) Ui.glow(g, x + 10, y + 5.5f, 16, 9, ca(0x405AD8FF));
		Ui.rect(g, x, y, 20, 11, 5.5f, ca(Theme.lerp(0xE6283650, 0xFF46BEEB, k)));
		Ui.circle(g, x + 5.5f + 9 * k, y + 5.5f, 4.1f, ca(on ? 0xFFFFFFFF : 0xFFAEBBD0));
		zones.add(new Zone(P - 4, yy, RW + 8, 16, flip));
		return yy + 17;
	}

	// ------------------------------------------------------------------ toolbar and menus

	private static final String[] TOOLS = {"layers", "|", "preset", "|", "grid", "magnet", "|", "undo", "redo", "history", "|", "screen", "|", "done"};

	private void drawToolbar(Gfx g, int mx, int my) {
		String[] scr = SCREENS[screenI];
		float h2 = 28, y0 = screenI == 0 ? Math.round(height / 2f + 30) : 10;
		float fl = 8, fr = width - 8;
		for (float[] pr : panels) {
			if (pr[1] < y0 + h2 && pr[1] + pr[3] > y0) {
				if (pr[0] + pr[2] / 2 < width / 2f) fl = Math.max(fl, pr[0] + pr[2] + 8);
				else fr = Math.min(fr, pr[0] - 8);
			}
		}
		boolean compact = measure(false, scr[0]) > fr - fl;
		float total = measure(compact, scr[0]);
		float x0 = Math.round(a("tbx", Math.max(8, Math.min(width - total - 8, (fl + fr) / 2 - total / 2)), 12f));
		glass(g, x0, y0, total, h2, 10);
		float x = x0 + 8, y = y0 + 4;
		String tip = null;
		float tipX = 0;
		for (String it : TOOLS) {
			if (it.equals("|")) {
				Christmas.rect(g, x + 3, y + 4, 0.7f, 12, ca(LINE));
				x += 9;
				continue;
			}
			float w = itemW(it, compact, scr[0]);
			boolean hv = Ui.inside(mx, my, x, y, w, 20) && menu == null;
			toolRects.put(it, new float[]{x, y, w});
			if (it.equals("done")) {
				Ui.gradientRect(g, x, y, w, 20, 6, ca(hv ? 0xFF9FEBFF : 0xFF7FE2FF), ca(0xFF3FA9E8));
				Ui.textCentered(g, "Done", x + w / 2, y + 10, ca(0xFF05203A), Fonts.BOLD);
				zones.add(new Zone(x, y, w, 20, this::onClose));
				x += w + 3;
				continue;
			}
			boolean on = it.equals("layers") ? layersOpen : it.equals("grid") ? grid : it.equals("magnet") ? snapElements
				: it.equals("screen") ? screenI != 0 : it.equals(menu);
			if (on) {
				Ui.rect(g, x, y, w, 20, 6, ca(0x295AD8FF));
				Ui.ring(g, x, y, w, 20, 6, ca(0x805AD8FF), 1f);
			}
			if (hv) Ui.rect(g, x, y, w, 20, 6, ca(0x12FFFFFF));
			boolean can = !(it.equals("undo") && hp <= 0) && !(it.equals("redo") && hp >= history.size() - 1);
			int col = ca(!can ? DIM : on || hv ? 0xFFFFFFFF : 0xCCD2DEEE);
			boolean labelled = !compact && (it.equals("preset") || it.equals("screen"));
			Ui.icon(g, toolIcon(it), x + (labelled ? 10 : w / 2f), y + 10, col, Fonts.ICON_M);
			if (labelled) {
				Ui.textMid(g, it.equals("preset") ? "Presets" : scr[0], x + 19, y + 10, col, Fonts.BODY);
				Ui.icon(g, Icon.EXPAND, x + w - 8, y + 10.5f, col, Fonts.ICON_S);
			}
			if (it.equals("history") && history.size() > 1) {
				String n = String.valueOf(hp);
				float nw = Fonts.width(n, Fonts.SMALL) + 4;
				Ui.rect(g, x + 14, y + 1, nw, 7, 3.5f, ca(ACC));
				Ui.textCentered(g, n, x + 14 + nw / 2, y + 4.5f, ca(0xFF05203A), Fonts.SMALL);
			}
			final String id = it;
			zones.add(new Zone(x, y, w, 20, () -> toolClick(id)));
			if (hv) {
				tip = toolTip(it, compact);
				tipX = x + w / 2;
			}
			x += w + 3;
		}
		float ha = Math.max(0, Math.min(1, (hintUntil - System.currentTimeMillis()) / 1000f)) * (screenI == 0 ? 1 : 0);
		if (ha > 0.01f && panels.isEmpty()) {
			Ui.textCentered(g, "Drag to move. Shift-click or drag a box to pick several, then Ctrl+G to group them.", width / 2f, y0 + h2 + 12,
				ca(Theme.alpha(0xFFE6F0FA, 0.9f * ha)), Fonts.SMALL);
		}
		if (tip != null) {
			float tw = Fonts.width(tip, Fonts.SMALL) + 12;
			float tx = Math.max(4, Math.min(width - tw - 4, tipX - tw / 2));
			Ui.rect(g, tx, y0 + h2 + 2, tw, 14, 4, 0xF5040A18);
			Ui.textCentered(g, tip, tx + tw / 2, y0 + h2 + 9, 0xFFE4ECF7, Fonts.SMALL);
		}
	}

	private static String toolIcon(String it) {
		if (it.equals("layers")) return Icon.LAYERS;
		if (it.equals("preset")) return Icon.DASHBOARD;
		if (it.equals("grid")) return Icon.GRID_ON;
		if (it.equals("magnet")) return Icon.LINK;
		if (it.equals("undo")) return Icon.RESET;
		if (it.equals("redo")) return Icon.REFRESH;
		if (it.equals("history")) return Icon.HISTORY;
		return Icon.SCREENSHOT;
	}

	private String toolTip(String it, boolean compact) {
		if (it.equals("layers")) return "Elements";
		if (it.equals("preset")) return compact ? "Presets" : null;
		if (it.equals("grid")) return grid ? "Snap to grid: on (G)" : "Snap to grid: off (G)";
		if (it.equals("magnet")) return snapElements ? "Snap to elements: on. Hold Ctrl to move freely" : "Snap to elements: off";
		if (it.equals("undo")) return hp > 0 ? "Undo: " + history.get(hp).label + " (Ctrl+Z)" : "Nothing to undo";
		if (it.equals("redo")) return hp < history.size() - 1 ? "Redo: " + history.get(hp + 1).label + " (Ctrl+Y)" : "Nothing to redo";
		if (it.equals("history")) return "History";
		if (it.equals("screen")) return compact ? "Preview: " + SCREENS[screenI][0] : null;
		return null;
	}

	private static float itemW(String it, boolean compact, String screenLabel) {
		if (it.equals("preset")) return compact ? 22 : 70;
		if (it.equals("screen")) return compact ? 22 : Fonts.width(screenLabel, Fonts.BODY) + 32;
		if (it.equals("done")) return 46;
		return 22;
	}

	private static float measure(boolean compact, String screenLabel) {
		float t = 8;
		for (String it : TOOLS) t += it.equals("|") ? 9 : itemW(it, compact, screenLabel) + 3;
		return t + 5;
	}

	private void toolClick(String id) {
		if (id.equals("layers")) layersOpen = !layersOpen;
		else if (id.equals("grid")) {
			grid = !grid;
			say(grid ? "Snap to grid on" : "Snap to grid off");
		} else if (id.equals("magnet")) {
			snapElements = !snapElements;
			say(snapElements ? "Snap to other elements on" : "Snap to other elements off");
		} else if (id.equals("undo")) undo();
		else if (id.equals("redo")) redo();
		else {
			menu = id.equals(menu) ? null : id;
			menuAt = System.currentTimeMillis();
		}
	}

	private void drawMenu(Gfx g, int mx, int my) {
		List<String[]> items = new ArrayList<String[]>(); // label, sub, check, dim, action
		if (menu.equals("preset")) {
			for (String n : new String[]{"Minimal", "PvP", "Streamer", "Builder"}) items.add(new String[]{n, presetNote(n), "0", "0", "preset:" + n});
			items.add(null);
			items.add(new String[]{"Reset to default", "The layout Polar ships with", "0", "0", "default"});
		} else if (menu.equals("screen")) {
			for (int i = 0; i < SCREENS.length; i++) {
				String sub = i == 0 ? width + " × " + height + " GUI, what you play on" : SCREENS[i][1];
				items.add(new String[]{SCREENS[i][0], sub, i == screenI ? "1" : "0", "0", "screen:" + i});
			}
		} else {
			for (int i = history.size() - 1; i >= 0 && items.size() < 12; i--) {
				items.add(new String[]{history.get(i).label, i == hp ? "You are here" : i > hp ? "Undone" : "", i == hp ? "1" : "0", i > hp ? "1" : "0", "jump:" + i});
			}
			if (history.size() > 12) items.add(new String[]{(history.size() - 12) + " older steps", "", "0", "1", ""});
		}
		float iw = 170, ih = 24, mh = 8;
		for (String[] it : items) mh += it == null ? 7 : ih;
		float[] br = toolRects.containsKey(menu) ? toolRects.get(menu) : new float[]{width / 2f, height / 2f, 20};
		boolean below = br[1] + 28 + mh < height - 8;
		float k = Ui.easeOut((System.currentTimeMillis() - menuAt) / 160f);
		float x = Math.max(8, Math.min(width - iw - 8, br[0])), y = (below ? br[1] + 28 : br[1] - 8 - mh) + (1 - k) * (below ? -4 : 4);
		Ui.rect(g, x, y, iw, mh, 9, 0xF5081124);
		Ui.ring(g, x, y, iw, mh, 9, 0x40A0D7FF, 1f);
		zones.add(new Zone(x, y, iw, mh, null));
		float yy = y + 4;
		for (final String[] it : items) {
			if (it == null) {
				Christmas.rect(g, x + 8, yy + 3, iw - 16, 0.6f, LINE);
				yy += 7;
				continue;
			}
			boolean hv = Ui.inside(mx, my, x + 4, yy, iw - 8, ih - 2);
			if (hv) Ui.rect(g, x + 4, yy, iw - 8, ih - 2, 6, 0x1F5AD8FF);
			boolean dim = it[3].equals("1"), check = it[2].equals("1");
			Ui.textMid(g, Fonts.ellipsize(it[0], check ? Fonts.BOLD : Fonts.BODY, Math.round(iw - 34)), x + 12, yy + (it[1].isEmpty() ? 11 : 8), dim ? DIM : 0xFFFFFFFF, check ? Fonts.BOLD : Fonts.BODY);
			if (!it[1].isEmpty()) Ui.textMid(g, it[1], x + 12, yy + 16.5f, dim ? 0xFF4B5D78 : MUTED, Fonts.SMALL);
			if (check) Ui.icon(g, Icon.CHECK, x + iw - 14, yy + 11, ACC, Fonts.ICON_S);
			zones.add(new Zone(x + 4, yy, iw - 8, ih - 2, () -> {
				menuAction(it[4]);
				menu = null;
			}));
			yy += ih;
		}
	}

	private void menuAction(String a) {
		if (a.startsWith("preset:")) {
			String n = a.substring(7);
			applyPreset(n);
			commit("Applied the " + n + " preset");
			say(n + " layout applied. Undo brings yours back.");
		} else if (a.equals("default")) {
			resetToDefault();
			commit("Reset to default");
			say("Back to the default layout");
		} else if (a.startsWith("screen:")) {
			screenI = Integer.parseInt(a.substring(7));
		} else if (a.startsWith("jump:")) {
			jump(Integer.parseInt(a.substring(5)));
		}
	}

	private void say(String msg) {
		toast = msg;
		toastAt = System.currentTimeMillis();
	}

	private void drawToast(Gfx g) {
		if (toast == null) return;
		float age = (System.currentTimeMillis() - toastAt) / 1000f;
		if (age > 2.2f) {
			toast = null;
			return;
		}
		float al = Math.max(0, Math.min(1, Math.min(age / 0.15f, (2.2f - age) / 0.3f)));
		float w = Fonts.width(toast, Fonts.BODY) + 32, x = width / 2f - w / 2, y = height - 58 + (1 - al) * 6;
		Ui.rect(g, x, y, w, 18, 9, Theme.alpha(0xF5091428, al));
		Ui.ring(g, x, y, w, 18, 9, Theme.alpha(0x8C5AD8FF, al), 1f);
		Ui.icon(g, Icon.CHECK, x + 12, y + 9, Theme.alpha(ACC, al), Fonts.ICON_S);
		Ui.textMid(g, toast, x + 21, y + 9, Theme.alpha(0xFFFFFFFF, al), Fonts.BODY);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		// tools first (the topmost was added last)
		if (chrome > 0.5f || menu != null) {
			for (int i = zones.size() - 1; i >= 0; i--) {
				Zone z = zones.get(i);
				if (Ui.inside(mx, my, z.x, z.y, z.w, z.h)) {
					if (z.run != null && button == LEFT) {
						p().playClickSound();
						z.run.run();
						if (drag == Drag.SLIDER) applySlider(mx);
					}
					return true;
				}
			}
		}
		if (menu != null) {
			menu = null;
			return true;
		}
		float smx = toFrameX(mx), smy = toFrameY(my);
		if (!Ui.inside(smx, smy, 0, 0, sw, sh)) {
			sel.clear();
			return true;
		}
		HudModule hit = null;
		List<HudModule> mods = shown();
		for (int i = mods.size() - 1; i >= 0; i--) {
			if (mods.get(i).contains(smx, smy)) {
				hit = mods.get(i);
				break;
			}
		}
		if (button == RIGHT) {
			if (hit != null) p().open(new PolarMenuView(p().currentScreen(), hit));
			return true;
		}
		if (button != LEFT) return false;
		// the corner handle
		float[] hd = handle();
		if (hd != null && Math.abs(smx - hd[0]) < 4.5f / fk && Math.abs(smy - hd[1]) < 4.5f / fk) {
			scaling = sel.get(0);
			scaleStart = scaling.scale.get();
			scaleW0 = scaling.lastWidth();
			dragX0 = smx;
			drag = Drag.SCALE;
			return true;
		}
		boolean shift = p().shiftDown();
		if (hit == null) {
			if (!shift) sel.clear();
			marqueeBase.clear();
			marqueeBase.addAll(sel);
			dragX0 = marqueeX1 = smx;
			dragY0 = marqueeY1 = smy;
			drag = Drag.MARQUEE;
			return true;
		}
		long now = System.currentTimeMillis();
		boolean doubleClick = hit == lastClicked && now - lastClickAt < 350;
		lastClicked = hit;
		lastClickAt = now;
		// a double-click picks one element inside its group
		List<HudModule> ids = doubleClick ? single(hit) : expand(hit);
		if (shift) {
			if (sel.containsAll(ids)) {
				sel.removeAll(ids);
				return true;
			}
			for (HudModule m : ids) if (!sel.contains(m)) sel.add(m);
		} else if (doubleClick || !sel.contains(hit)) {
			sel.clear();
			sel.addAll(ids);
		}
		hintUntil = Math.min(hintUntil, now + 1500);
		dragStart.clear();
		for (HudModule m : sel) dragStart.put(m, new float[]{m.lastX(), m.lastY()});
		dragX0 = smx;
		dragY0 = smy;
		moved = false;
		drag = Drag.MOVE;
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
		float smx = toFrameX(mx), smy = toFrameY(my);
		int w = Math.round(sw), h = Math.round(sh);
		if (drag == Drag.SLIDER) {
			applySlider(mx);
			return true;
		}
		if (drag == Drag.SCALE) {
			float dir = scaling.anchorX() == 1 ? -1 : 1;
			float base = (float) (scaleW0 / scaleStart);
			float nw = Math.max(base * 0.5f, Math.min(base * 2.5f, scaleW0 + (smx - dragX0) * dir));
			scaling.scale.set(Math.round(nw / base * 20) / 20.0);
			return true;
		}
		if (drag == Drag.MARQUEE) {
			marqueeX1 = smx;
			marqueeY1 = smy;
			float l = Math.min(dragX0, smx), r = Math.max(dragX0, smx), t = Math.min(dragY0, smy), b = Math.max(dragY0, smy);
			sel.clear();
			sel.addAll(marqueeBase);
			for (HudModule m : shown()) {
				if (m.lastX() < r && m.lastX() + m.lastWidth() > l && m.lastY() < b && m.lastY() + m.lastHeight() > t) {
					for (HudModule x : expand(m)) if (!sel.contains(x)) sel.add(x);
				}
			}
			return true;
		}
		if (drag == Drag.MOVE) {
			float dx = smx - dragX0, dy = smy - dragY0;
			if (!moved && dx * dx + dy * dy < 2.25f) return true;
			moved = true;
			float[] d = snapMove(dx, dy, p().controlDown());
			for (HudModule m : sel) {
				float[] st = dragStart.get(m);
				if (st != null) m.moveTo(st[0] + d[0], st[1] + d[1], w, h);
			}
			return true;
		}
		return false;
	}

	/** Snaps the moved selection: other elements, the hotbar and the screen first, then the grid. Returns {dx, dy}. */
	private float[] snapMove(float dx, float dy, boolean free) {
		float l = 1e9f, t = 1e9f, r = -1e9f, b = -1e9f;
		for (HudModule m : sel) {
			float[] st = dragStart.get(m);
			if (st == null) continue;
			l = Math.min(l, st[0] + dx);
			t = Math.min(t, st[1] + dy);
			r = Math.max(r, st[0] + dx + m.lastWidth());
			b = Math.max(b, st[1] + dy + m.lastHeight());
		}
		float bw = r - l, bh = b - t;
		guides.clear();
		// keep it on screen
		float cx = Math.max(0, Math.min(sw - bw, l)) - l, cy = Math.max(0, Math.min(sh - bh, t)) - t;
		l += cx;
		r += cx;
		t += cy;
		b += cy;
		if (!free) {
			float th = SNAP / Math.max(0.5f, fk);
			List<float[]> targets = new ArrayList<float[]>();
			if (snapElements) {
				for (HudModule m : shown()) {
					if (sel.contains(m)) continue;
					targets.add(new float[]{m.lastX(), m.lastY(), m.lastX() + m.lastWidth(), m.lastY() + m.lastHeight()});
				}
			}
			targets.add(new float[]{sw / 2 - 91, sh - 33, sw / 2 + 91, sh}); // the hotbar and hearts
			float[] bx = axis(new float[]{l, (l + r) / 2, r}, targets, true, th);
			float[] by = axis(new float[]{t, (t + b) / 2, b}, targets, false, th);
			if (bx != null) {
				cx += bx[0];
				l += bx[0];
				r += bx[0];
			} else if (grid) {
				float gd = Math.round(l / GRID) * GRID - l;
				cx += gd;
				l += gd;
				r += gd;
			}
			if (by != null) {
				cy += by[0];
				t += by[0];
				b += by[0];
			} else if (grid) {
				float gd = Math.round(t / GRID) * GRID - t;
				cy += gd;
				t += gd;
				b += gd;
			}
			if (bx != null) guides.add(new float[]{0, bx[1], bx[2] < 0 ? 0 : Math.min(t, bx[3]) - 4, bx[2] < 0 ? sh : Math.max(b, bx[4]) + 4, bx[2] < 0 ? 1 : 0});
			if (by != null) guides.add(new float[]{1, by[1], by[2] < 0 ? 0 : Math.min(l, by[3]) - 4, by[2] < 0 ? sw : Math.max(r, by[4]) + 4, by[2] < 0 ? 1 : 0});
		}
		return new float[]{dx + cx, dy + cy};
	}

	/** Best snap on one axis: {delta, line, target index or -1 for the screen, target's cross-axis start, end}. */
	private float[] axis(float[] mine, List<float[]> targets, boolean x, float th) {
		float[] best = null;
		for (int i = 0; i < targets.size(); i++) {
			float[] tg = targets.get(i);
			float[] vals = x ? new float[]{tg[0], (tg[0] + tg[2]) / 2, tg[2]} : new float[]{tg[1], (tg[1] + tg[3]) / 2, tg[3]};
			for (float v : vals) {
				for (float m : mine) {
					float d = v - m;
					if (Math.abs(d) < th && (best == null || Math.abs(d) < Math.abs(best[0]) - 0.01f)) {
						best = new float[]{d, v, i, x ? tg[1] : tg[0], x ? tg[3] : tg[2]};
					}
				}
			}
		}
		float size = x ? sw : sh;
		for (float v : new float[]{0, size / 2, size}) {
			for (float m : mine) {
				float d = v - m;
				if (Math.abs(d) < th && (best == null || Math.abs(d) < Math.abs(best[0]) - 0.01f)) best = new float[]{d, v, -1, 0, 0};
			}
		}
		return best;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		Drag was = drag;
		drag = Drag.NONE;
		guides.clear();
		if (was == Drag.MOVE && moved) {
			for (List<HudModule> u : units(sel)) autoAnchor(u);
			commit("Moved " + label(sel));
		} else if (was == Drag.SCALE && scaling != null && scaling.scale.get() != scaleStart) {
			commit("Resized " + scaling.name());
		} else if (was == Drag.SLIDER) {
			Snapshot last = hp >= 0 ? history.get(hp) : null;
			commit((sliderId.equals("size") ? "Resized " : "Changed opacity of ") + label(inspected));
			if (last != null && same(last, history.get(hp))) {
				history.remove(hp);
				hp--;
			}
		}
		scaling = null;
		return false;
	}

	/** Whether two snapshots hold the same layout (a slider that was clicked but not moved). */
	private static boolean same(Snapshot a, Snapshot b) {
		for (Map.Entry<HudModule, Object[]> e : a.state.entrySet()) {
			Object[] x = e.getValue(), y = b.state.get(e.getKey());
			if (y == null || !x[0].equals(y[0]) || !java.util.Arrays.equals((double[]) x[1], (double[]) y[1])
				|| !java.util.Arrays.equals((Object[]) x[3], (Object[]) y[3])) return false;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double amount) {
		float smx = toFrameX(x), smy = toFrameY(y);
		for (HudModule m : shown()) {
			if (m.contains(smx, smy)) {
				m.scale.set(m.scale.get() + (amount > 0 ? 0.05 : -0.05));
				commit("Resized " + m.name());
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		boolean mod = key.control;
		if (mod && key.is(KeyInput.Key.Z)) {
			if (key.shift) redo();
			else undo();
			return true;
		}
		if (mod && key.is(KeyInput.Key.Y)) {
			redo();
			return true;
		}
		if (mod && key.is(KeyInput.Key.G)) {
			if (key.shift) ungroup();
			else group();
			return true;
		}
		if (mod && key.is(KeyInput.Key.A)) {
			sel.clear();
			sel.addAll(shown());
			return true;
		}
		if (key.is(KeyInput.Key.ESCAPE)) {
			if (menu != null) menu = null;
			else if (!sel.isEmpty()) sel.clear();
			else onClose();
			return true;
		}
		if (key.is(KeyInput.Key.TAB)) {
			panelsHidden = !panelsHidden;
			return true;
		}
		if (!mod && key.is(KeyInput.Key.G)) {
			grid = !grid;
			say(grid ? "Snap to grid on" : "Snap to grid off");
			return true;
		}
		if ((key.is(KeyInput.Key.DELETE) || key.is(KeyInput.Key.BACKSPACE)) && !sel.isEmpty()) {
			hideSelection();
			return true;
		}
		int dx = key.is(KeyInput.Key.LEFT) ? -1 : key.is(KeyInput.Key.RIGHT) ? 1 : 0, dy = key.is(KeyInput.Key.UP) ? -1 : key.is(KeyInput.Key.DOWN) ? 1 : 0;
		if ((dx != 0 || dy != 0) && !sel.isEmpty()) {
			int st = key.shift ? 8 : 1;
			for (HudModule m : sel) m.moveTo(m.lastX() + dx * st, m.lastY() + dy * st, Math.round(sw), Math.round(sh));
			long now = System.currentTimeMillis();
			String lbl = "Nudged " + label(sel);
			// a run of nudges is one step
			if (now - lastNudgeAt < 600 && hp >= 0 && history.get(hp).label.equals(lbl)) history.set(hp, snapshot(lbl));
			else commit(lbl);
			lastNudgeAt = now;
			return true;
		}
		if (key.menuKey) {
			p().open(new PolarMenuView(p().currentScreen()));
			return true;
		}
		return false;
	}

	/** A Gfx that draws nothing (to measure a module before its first real frame). */
	private static final class NullGfx implements Gfx {
		private final int w, h;

		NullGfx(int w, int h) {
			this.w = w;
			this.h = h;
		}

		public int width() { return w; }
		public int height() { return h; }
		public int guiScale() { return Math.max(1, p().guiScale()); }
		public void push() { }
		public void pop() { }
		public void translate(float x, float y) { }
		public void scale(float sx, float sy) { }
		public void rotate(float degrees) { }
		public void fill(int x0, int y0, int x1, int y1, int argb) { }
		public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) { }
		public void blit(String texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) { }
		public void blitHandle(Object texture, float x, float y, float w, float h) { }
		public void text(String s, float x, float y, int argb, dev.polarclient.gfx.UiFont font, boolean shadow) { }
		public void richText(Object component, float x, float y, int maxWidth, int maxLines, int lineHeight, int argb) { }
		public void richLine(Object component, float x, float y, int argb, boolean shadow) { }
		public void scissor(int x0, int y0, int x1, int y1) { }
		public void endScissor() { }
		public void nextLayer() { }
		public void item(dev.polarclient.platform.ItemView item, int x, int y) { }
		public void itemDecorations(dev.polarclient.platform.ItemView item, int x, int y) { }
		public void effectIcon(dev.polarclient.platform.EffectView effect, int x, int y, int size, float alpha) { }
		public void playerHead(java.util.UUID id, String name, float x, float y, float size) { }
		public void blurBackground() { }
	}
}
