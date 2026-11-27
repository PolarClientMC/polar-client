package dev.polarclient.gui;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.modules.world.DeathWaypointModule;
import dev.polarclient.modules.world.WaypointsModule;
import dev.polarclient.modules.world.WorldMapModule;
import dev.polarclient.world.Waypoint;
import dev.polarclient.world.WaypointStore;
import dev.polarclient.world.WorldMapStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The world map: everything recorded in {@link WorldMapStore}, with your arrow and your waypoints. Drag to pan, scroll
 * to zoom around the mouse, right-click to add a waypoint there. Unexplored ground stays dark.
 */
public class WorldMapView extends View {
	private static final float[] ZOOMS = {0.25f, 0.5f, 1, 2, 4, 8};
	private static final String[] DIMS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
	private static final String[] DIM_NAMES = {"Overworld", "Nether", "The End"};
	private static final float PANEL_W = 148;
	private static final int ACCENT = 0xFF5AD8FF, MUTED = 0xFF93A8C6, DEATH = 0xFFFF6B6B;

	private final String world;
	private int dim;
	private double cx, cz;
	private int zoomIndex = 3;
	private boolean dragging, dragged;
	private double downX, downY;
	private Object texture;
	private int texW, texH, builtRevision = -1, builtOx = Integer.MIN_VALUE, builtOz, builtBpt, builtDim = -1;
	private long builtAt;
	private int[] pixels = new int[0];
	private Waypoint selected;
	private boolean deathSelected;
	private float listScroll;
	private String toast;
	private long toastAt;
	private Runnable undo;
	private final List<Hit> hits = new ArrayList<Hit>();

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

	public WorldMapView(Object parent) {
		super(parent);
		world = p().worldId();
		String d = p().dimension();
		dim = 0;
		for (int i = 0; i < DIMS.length; i++) if (DIMS[i].equals(d) || DIMS[i].endsWith(":" + d)) dim = i;
		centreOnPlayer();
	}

	@Override
	public String title() {
		return "World Map";
	}

	private WorldMapModule module() {
		return Polar.modules().worldMap;
	}

	private boolean here() {
		String d = p().dimension();
		return d != null && (DIMS[dim].equals(d) || DIMS[dim].endsWith(":" + d));
	}

	private void centreOnPlayer() {
		cx = p().playerX();
		cz = p().playerZ();
	}

	private float zoom() {
		return ZOOMS[zoomIndex];
	}

	private float sx(double bx) {
		return (float) (width / 2f + (bx - cx) * zoom());
	}

	private float sy(double bz) {
		return (float) (height / 2f + (bz - cz) * zoom());
	}

	private double bx(double screenX) {
		return cx + (screenX - width / 2f) / zoom();
	}

	private double bz(double screenY) {
		return cz + (screenY - height / 2f) / zoom();
	}

	// ------------------------------------------------------------------ the picture

	private void rebuild() {
		float z = zoom();
		int bpt = z >= 1 ? 1 : Math.round(1 / z);
		int tw = (int) Math.ceil(width / z / bpt) + 2, th = (int) Math.ceil(height / z / bpt) + 2;
		int ox = (int) Math.floor(bx(0) / bpt) * bpt, oz = (int) Math.floor(bz(0) / bpt) * bpt;
		int rev = WorldMapStore.revision();
		long now = System.currentTimeMillis();
		boolean moved = ox != builtOx || oz != builtOz || bpt != builtBpt || dim != builtDim;
		if (!moved && (rev == builtRevision || now - builtAt < 500) && texture != null) return;
		if (texture == null || tw != texW || th != texH) {
			if (texture != null) p().destroyTexture(texture);
			texture = p().createTexture("world_map", tw, th);
			texW = tw;
			texH = th;
		}
		if (texture == null) return;
		if (pixels.length != tw * th) pixels = new int[tw * th];
		Map<Long, WorldMapStore.Tile> tiles = WorldMapStore.of(world, DIMS[dim]);
		WorldMapStore.Tile t = null;
		long tk = Long.MIN_VALUE;
		for (int j = 0; j < th; j++) {
			int bz = oz + j * bpt;
			for (int i = 0; i < tw; i++) {
				int bx = ox + i * bpt;
				long k = WorldMapStore.key(bx >> 4, bz >> 4);
				if (k != tk) {
					tk = k;
					t = tiles.get(k);
				}
				pixels[j * tw + i] = t == null ? 0 : t.argb[(bz & 15) * 16 + (bx & 15)];
			}
		}
		p().uploadTexture(texture, pixels);
		builtOx = ox;
		builtOz = oz;
		builtBpt = bpt;
		builtDim = dim;
		builtRevision = rev;
		builtAt = now;
	}

	@Override
	public void removed() {
		if (texture != null) p().destroyTexture(texture);
		texture = null;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY, float partial) {
		Ui.frame();
		hits.clear();
		g.fill(0, 0, width, height, 0xFF060B16);
		boolean explored = world != null && WorldMapStore.explored(world, DIMS[dim]);
		if (explored) {
			rebuild();
			if (texture != null) {
				float z = zoom();
				g.blitHandle(texture, sx(builtOx), sy(builtOz), texW * builtBpt * z, texH * builtBpt * z);
			}
			if (module().grid.on() && zoom() >= 1) drawGrid(g);
			if (module().showWaypoints.on()) drawWaypoints(g, mouseX, mouseY);
			if (here()) drawPlayer(g);
		} else {
			String name = DIM_NAMES[dim];
			Ui.icon(g, Icon.MAP, width / 2f + PANEL_W / 2, height / 2f - 14, MUTED, Fonts.ICON_L);
			Ui.textCentered(g, here() ? "Walk around and the map fills in" : "You haven't been to " + (dim == 0 ? "the Overworld" : dim == 1 ? "the Nether" : "The End") + " here yet",
					width / 2f + PANEL_W / 2, height / 2f + 6, 0xFFFFFFFF, Fonts.BOLD);
			Ui.textCentered(g, "Only places you have seen are drawn, so the map stays fair on servers.", width / 2f + PANEL_W / 2, height / 2f + 18, MUTED, Fonts.SMALL);
			if (name == null) return;
		}
		drawPanel(g, mouseX, mouseY);
		drawTop(g, mouseX, mouseY);
		drawReadout(g, mouseX, mouseY, explored);
		drawToast(g);
	}

	private void drawGrid(Gfx g) {
		float z = zoom();
		int x0 = (int) Math.floor(bx(0) / 16) * 16, z0 = (int) Math.floor(bz(0) / 16) * 16;
		for (int x = x0; sx(x) < width; x += 16) g.fill(Math.round(sx(x)), 0, Math.round(sx(x)) + 1, height, 0x22FFFFFF);
		for (int zz = z0; sy(zz) < height; zz += 16) g.fill(0, Math.round(sy(zz)), width, Math.round(sy(zz)) + 1, 0x22FFFFFF);
		if (z < 0) return;
	}

	private void drawPlayer(Gfx g) {
		float x = sx(p().playerX()), y = sy(p().playerZ());
		Ui.circle(g, x, y, 7, 0x55000000);
		g.push();
		g.translate(x, y);
		g.rotate(p().playerYaw() + 180);
		g.push();
		g.scale(1.15f);
		Ui.icon(g, Icon.NAVIGATION, 0, 0, 0xFF000000, Fonts.ICON_M_FILL);
		g.pop();
		Ui.icon(g, Icon.NAVIGATION, 0, 0, 0xFFFFFFFF, Fonts.ICON_M_FILL);
		g.pop();
	}

	private List<Waypoint> waypointsHere() {
		List<Waypoint> out = new ArrayList<Waypoint>();
		for (Waypoint w : WaypointStore.of(world)) if (w.dim != null && (w.dim.equals(DIMS[dim]) || DIMS[dim].endsWith(":" + w.dim))) out.add(w);
		final double px = p().playerX(), pz = p().playerZ();
		Collections.sort(out, (a, b) -> Double.compare(Math.hypot(a.x - px, a.z - pz), Math.hypot(b.x - px, b.z - pz)));
		return out;
	}

	private double[] deathHere() {
		DeathWaypointModule m = (DeathWaypointModule) Polar.modules().byId("death_waypoint");
		if (m == null || !m.isEnabled()) return null;
		double[] d = m.lastDeath();
		String dd = m.lastDeathDimension();
		if (d == null || dd == null || !(DIMS[dim].equals(dd) || DIMS[dim].endsWith(":" + dd))) return null;
		return d;
	}

	private void marker(Gfx g, float x, float y, int color, String glyph, boolean sel) {
		if (x < -10 || y < -10 || x > width + 10 || y > height + 10) return;
		if (sel) Ui.circle(g, x, y, 9, Theme.alpha(color, 0.35f));
		Ui.circle(g, x, y, 6.5f, 0xFF0B1426);
		Ui.circle(g, x, y, 5.5f, color);
		Ui.icon(g, glyph, x, y, 0xFF0B1426, Fonts.ICON_S);
	}

	private void drawWaypoints(Gfx g, int mx, int my) {
		for (final Waypoint w : waypointsHere()) {
			float x = sx(w.x + 0.5), y = sy(w.z + 0.5);
			marker(g, x, y, w.color, WaypointIcons.glyph(w.icon), w == selected);
			if (zoom() >= 1 || w == selected) {
				int tw = Fonts.width(w.name, Fonts.SMALL);
				Ui.rect(g, x - tw / 2f - 4, y + 8, tw + 8, 11, 3, 0xC00B1426);
				Ui.textCentered(g, w.name, x, y + 13.5f, 0xFFFFFFFF, Fonts.SMALL);
			}
			hits.add(new Hit(x - 7, y - 7, 14, 14, () -> select(w)));
		}
		double[] d = deathHere();
		if (d != null) {
			float x = sx(d[0]), y = sy(d[2]);
			marker(g, x, y, DEATH, Icon.SKULL, deathSelected);
			hits.add(new Hit(x - 7, y - 7, 14, 14, this::selectDeath));
		}
	}

	private void select(Waypoint w) {
		selected = w;
		deathSelected = false;
	}

	private void selectDeath() {
		selected = null;
		deathSelected = true;
	}

	private void panel(Gfx g, float x, float y, float w, float h) {
		Ui.rect(g, x, y, w, h, 6, 0xE6091328);
		Ui.ring(g, x, y, w, h, 6, 0x479CC9FF, 1f);
	}

	private boolean button(Gfx g, String id, String icon, String label, float x, float y, float w, float h, int mx, int my, boolean on, Runnable run) {
		boolean hv = Ui.inside(mx, my, x, y, w, h);
		Ui.rect(g, x, y, w, h, 4, on ? 0xE02A5C9A : hv ? 0xE01E3868 : 0xD00C1A38);
		Ui.ring(g, x, y, w, h, 4, hv || on ? 0xB3CFE9FF : 0x669CC9FF, 1f);
		if (icon != null && label == null) {
			Ui.icon(g, icon, x + w / 2, y + h / 2, 0xFFFFFFFF, Fonts.ICON_S);
		} else if (icon != null) {
			Ui.icon(g, icon, x + 9, y + h / 2, 0xFFDCEBFF, Fonts.ICON_S);
			Ui.textMid(g, label, x + 17, y + h / 2, 0xFFFFFFFF, Fonts.SMALL);
		} else {
			Ui.textCentered(g, label, x + w / 2, y + h / 2, 0xFFFFFFFF, Fonts.SMALL);
		}
		hits.add(new Hit(x, y, w, h, run));
		return hv;
	}

	private void drawPanel(Gfx g, int mx, int my) {
		float x = 6, y = 6, w = PANEL_W - 12, h = height - 12;
		panel(g, x, y, w, h);
		Ui.icon(g, Icon.MAP, x + 12, y + 13, ACCENT, Fonts.ICON_M);
		Ui.textMid(g, "World Map", x + 22, y + 13, 0xFFFFFFFF, Fonts.BOLD);
		float by = y + 26;
		if (here()) {
			button(g, "add", Icon.ADD, "Add a waypoint here", x + 6, by, w - 12, 15, mx, my, false, this::addHere);
			by += 20;
		}
		Ui.textMid(g, "Waypoints", x + 8, by + 4, MUTED, Fonts.SMALL);
		by += 10;
		boolean hasSel = selected != null || deathSelected;
		float listBottom = y + h - (hasSel ? 92 : 6);
		g.scissor(Math.round(x), Math.round(by), Math.round(x + w), Math.round(listBottom));
		float ry = by - listScroll;
		final double px = p().playerX(), pz = p().playerZ();
		double[] d = deathHere();
		if (d != null) {
			ry = row(g, x, ry, w, DEATH, Icon.SKULL, "Last death", here() ? Math.hypot(d[0] - px, d[2] - pz) : -1, deathSelected, mx, my, by, listBottom, () -> {
				selectDeath();
				cx = d[0];
				cz = d[2];
			});
		}
		List<Waypoint> list = waypointsHere();
		for (final Waypoint wp : list) {
			ry = row(g, x, ry, w, wp.color, WaypointIcons.glyph(wp.icon), wp.name, here() ? Math.hypot(wp.x - px, wp.z - pz) : -1, wp == selected, mx, my, by, listBottom, () -> {
				select(wp);
				cx = wp.x + 0.5;
				cz = wp.z + 0.5;
			});
		}
		if (d == null && list.isEmpty()) Ui.textMid(g, "None yet. Right-click the map to add one.", x + 8, by + 8, MUTED, Fonts.SMALL);
		g.endScissor();
		float maxScroll = Math.max(0, ry + listScroll - listBottom);
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		if (hasSel) drawCard(g, x + 4, y + h - 88, w - 8, mx, my);
	}

	private float row(Gfx g, float x, float y, float w, int color, String glyph, String name, double dist, boolean sel, int mx, int my,
					  float top, float bottom, Runnable run) {
		float h = 16;
		if (y + h > top && y < bottom) {
			boolean hv = Ui.inside(mx, my, x + 4, y, w - 8, h) && my >= top && my < bottom;
			if (sel || hv) Ui.rect(g, x + 4, y, w - 8, h, 4, sel ? 0x553A78C8 : 0x2AFFFFFF);
			Ui.circle(g, x + 13, y + h / 2, 5, color);
			Ui.icon(g, glyph, x + 13, y + h / 2, 0xFF0B1426, Fonts.ICON_S);
			Ui.textMid(g, Fonts.ellipsize(name, Fonts.BODY, (int) (w - 60)), x + 22, y + h / 2, 0xFFFFFFFF, Fonts.BODY);
			if (dist >= 0) Ui.textRight(g, WaypointsModule.formatDistance(dist), x + w - 8, y + h / 2, MUTED, Fonts.SMALL);
			if (my >= top && my < bottom) hits.add(new Hit(x + 4, y, w - 8, h, run));
		}
		return y + h + 2;
	}

	private void drawCard(Gfx g, float x, float y, float w, int mx, int my) {
		Ui.rect(g, x, y, w, 84, 5, 0xF0102040);
		Ui.ring(g, x, y, w, 84, 5, 0x669CC9FF, 1f);
		String name;
		int bx, by, bz, color;
		if (deathSelected) {
			double[] d = deathHere();
			if (d == null) {
				deathSelected = false;
				return;
			}
			name = "Last death";
			bx = (int) Math.floor(d[0]);
			by = (int) Math.floor(d[1]);
			bz = (int) Math.floor(d[2]);
			color = DEATH;
		} else {
			name = selected.name;
			bx = selected.x;
			by = selected.y;
			bz = selected.z;
			color = selected.color;
		}
		Ui.rect(g, x + 6, y + 6, 3, 12, 1.5f, color);
		Ui.textMid(g, Fonts.ellipsize(name, Fonts.BOLD, (int) (w - 20)), x + 13, y + 12, 0xFFFFFFFF, Fonts.BOLD);
		Ui.textMid(g, bx + ", " + by + ", " + bz, x + 8, y + 26, 0xFFDCEBFF, Fonts.BODY);
		if (here()) Ui.textRight(g, WaypointsModule.formatDistance(Math.hypot(bx - p().playerX(), bz - p().playerZ())) + " away", x + w - 8, y + 26, MUTED, Fonts.SMALL);
		final int fx = bx, fy = by, fz = bz;
		float bw = (w - 16) / 3f;
		if (!deathSelected) {
			final Waypoint w0 = selected;
			// the beam switch
			boolean on = w0.visible;
			boolean hv = Ui.inside(mx, my, x + 6, y + 36, w - 12, 15);
			Ui.textMid(g, "Light beam", x + 8, y + 43.5f, hv ? 0xFFFFFFFF : 0xFFDCEBFF, Fonts.SMALL);
			Ui.rect(g, x + w - 26, y + 39.5f, 18, 9, 4.5f, on ? ACCENT : 0x44FFFFFF);
			Ui.circle(g, x + w - 26 + (on ? 13.5f : 4.5f), y + 44, 3.4f, 0xFFFFFFFF);
			hits.add(new Hit(x + 6, y + 36, w - 12, 15, () -> {
				w0.visible = !w0.visible;
				WaypointStore.save();
			}));
			button(g, "edit", null, "Edit", x + 6, y + 60, bw, 16, mx, my, false, () -> p().open(new WaypointsView(p().currentScreen())));
			button(g, "copy", null, "Copy", x + 8 + bw, y + 60, bw, 16, mx, my, false, () -> copy(fx, fy, fz));
			button(g, "delete", null, "Delete", x + 10 + 2 * bw, y + 60, bw, 16, mx, my, false, () -> delete(w0));
		} else {
			bw = (w - 14) / 2f;
			button(g, "copy", null, "Copy", x + 6, y + 60, bw, 16, mx, my, false, () -> copy(fx, fy, fz));
			button(g, "remove", null, "Remove", x + 8 + bw, y + 60, bw, 16, mx, my, false, () -> {
				DeathWaypointModule m = (DeathWaypointModule) Polar.modules().byId("death_waypoint");
				final double[] d = m.lastDeath();
				final String dd = m.lastDeathDimension();
				m.clear();
				deathSelected = false;
				say("Death waypoint removed", () -> m.set(d[0], d[1], d[2], dd));
			});
		}
	}

	private void copy(int x, int y, int z) {
		p().setClipboard(x + " " + y + " " + z);
		say("Coordinates copied", null);
	}

	private void delete(final Waypoint w) {
		final List<Waypoint> list = WaypointStore.of(world);
		final int at = list.indexOf(w);
		list.remove(w);
		WaypointStore.save();
		selected = null;
		say("Deleted " + w.name, () -> {
			list.add(Math.max(0, Math.min(at, list.size())), w);
			WaypointStore.save();
			select(w);
		});
	}

	private void addHere() {
		WaypointsModule m = Polar.modules().waypoints;
		int before = WaypointStore.of(world).size();
		m.addHere();
		List<Waypoint> l = WaypointStore.of(world);
		if (l.size() > before) select(l.get(l.size() - 1));
	}

	private void addAt(double bx, double bz) {
		List<Waypoint> list = WaypointStore.of(world);
		if (world == null || list.size() >= WaypointStore.MAX_PER_WORLD) {
			say("This world already has " + WaypointStore.MAX_PER_WORLD + " waypoints", null);
			return;
		}
		int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
		int y = (int) Math.floor(p().playerY());
		WorldMapStore.Tile t = WorldMapStore.of(world, DIMS[dim]).get(WorldMapStore.key(x >> 4, z >> 4));
		if (t != null && t.argb[(z & 15) * 16 + (x & 15)] != 0) y = t.height[(z & 15) * 16 + (x & 15)] + 1;
		int n = list.size() + 1;
		Waypoint w = new Waypoint("Waypoint " + n, x, y, z, DIMS[dim], WaypointsView.COLORS[(n - 1) % WaypointsView.COLORS.length]);
		list.add(w);
		WaypointStore.save();
		select(w);
		say("Added " + w.name + " at " + x + ", " + y + ", " + z, null);
	}

	private void drawTop(Gfx g, int mx, int my) {
		// right side: layers and zoom
		float rx = width - 6 - 18;
		button(g, "close", Icon.CLOSE, null, rx, 6, 18, 16, mx, my, false, this::onClose);
		rx -= 22;
		if (here()) {
			button(g, "me", Icon.GPS, null, rx, 6, 18, 16, mx, my, false, this::centreOnPlayer);
			rx -= 22;
		}
		button(g, "out", null, "-", rx, 6, 18, 16, mx, my, false, () -> zoomAt(-1, width / 2f, height / 2f));
		rx -= 20;
		button(g, "in", null, "+", rx, 6, 18, 16, mx, my, false, () -> zoomAt(1, width / 2f, height / 2f));
		rx -= 22;
		WorldMapModule m = module();
		button(g, "grid", Icon.GRID_ON, null, rx, 6, 18, 16, mx, my, m.grid.on(), () -> {
			m.grid.toggle();
			Polar.saveConfig();
		});
		rx -= 20;
		button(g, "wps", Icon.PIN_DROP, null, rx, 6, 18, 16, mx, my, m.showWaypoints.on(), () -> {
			m.showWaypoints.toggle();
			Polar.saveConfig();
		});
		// dimension tabs, centred over the map
		float tw = 58, total = tw * 3 + 4, ty = 6;
		float tx = Math.max(PANEL_W + 2, Math.min(PANEL_W + (width - PANEL_W) / 2f - total / 2, rx - 8 - total));
		Ui.rect(g, tx - 2, ty - 2, total + 4, 20, 6, 0xE6091328);
		for (int i = 0; i < 3; i++) {
			final int d = i;
			float x = tx + i * (tw + 2);
			boolean hv = Ui.inside(mx, my, x, ty, tw, 16);
			if (i == dim) Ui.rect(g, x, ty, tw, 16, 4, 0xFF2A5C9A);
			else if (hv) Ui.rect(g, x, ty, tw, 16, 4, 0x332A5C9A);
			Ui.textCentered(g, DIM_NAMES[i], x + tw / 2, ty + 8, i == dim ? 0xFFFFFFFF : 0xFFB8C8DE, Fonts.SMALL);
			hits.add(new Hit(x, ty, tw, 16, () -> {
				dim = d;
				if (here()) centreOnPlayer();
				else {
					cx = 0;
					cz = 0;
				}
				selected = null;
				deathSelected = false;
			}));
		}
	}

	private void drawReadout(Gfx g, int mx, int my, boolean explored) {
		if (mx < PANEL_W) return;
		int x = (int) Math.floor(bx(mx)), z = (int) Math.floor(bz(my));
		String s;
		WorldMapStore.Tile t = explored ? WorldMapStore.of(world, DIMS[dim]).get(WorldMapStore.key(x >> 4, z >> 4)) : null;
		int i = (z & 15) * 16 + (x & 15);
		if (t != null && t.argb[i] != 0) s = "X " + x + "   Y " + t.height[i] + "   Z " + z;
		else s = "X " + x + "   Z " + z + "   Not explored";
		float w = Fonts.width(s, Fonts.SMALL) + 16, cxs = PANEL_W + (width - PANEL_W) / 2f;
		Ui.rect(g, cxs - w / 2, height - 22, w, 15, 5, 0xE6091328);
		Ui.textCentered(g, s, cxs, height - 14.5f, 0xFFDCEBFF, Fonts.SMALL);
	}

	private void say(String msg, Runnable undoIt) {
		toast = msg;
		toastAt = System.currentTimeMillis();
		undo = undoIt;
	}

	private void drawToast(Gfx g) {
		if (toast == null) return;
		float age = (System.currentTimeMillis() - toastAt) / 1000f, life = undo != null ? 5f : 2.2f;
		if (age > life) {
			toast = null;
			undo = null;
			return;
		}
		float a = Math.max(0, Math.min(1, Math.min(age / 0.15f, (life - age) / 0.3f)));
		float uw = undo != null ? Fonts.width("Undo", Fonts.BOLD) + 14 : 0;
		float tw = Fonts.width(toast, Fonts.BODY) + 30 + uw, x = PANEL_W + (width - PANEL_W) / 2f - tw / 2, y = 30;
		Ui.rect(g, x, y, tw, 18, 9, Theme.alpha(0xEB09142C, a));
		Ui.ring(g, x, y, tw, 18, 9, Theme.alpha(0x995AD8FF, a), 1f);
		Ui.icon(g, Icon.CHECK, x + 11, y + 9, Theme.alpha(ACCENT, a), Fonts.ICON_S);
		Ui.textMid(g, toast, x + 20, y + 9, Theme.alpha(0xFFFFFFFF, a), Fonts.BODY);
		if (undo != null) {
			float ux = x + tw - uw - 4;
			Ui.textMid(g, "Undo", ux + 6, y + 9, Theme.alpha(ACCENT, a), Fonts.BOLD);
			final Runnable u = undo;
			hits.add(new Hit(ux, y, uw, 18, () -> {
				u.run();
				toast = null;
				undo = null;
			}));
		}
	}

	// ------------------------------------------------------------------ input

	private void zoomAt(int dir, double mx, double my) {
		int ni = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + dir));
		if (ni == zoomIndex) return;
		double bx = bx(mx), bz = bz(my);
		zoomIndex = ni;
		// keep the block under the mouse where it is
		cx = bx - (mx - width / 2f) / zoom();
		cz = bz - (my - height / 2f) / zoom();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (mx < PANEL_W) {
			listScroll -= amount * 18;
			if (listScroll < 0) listScroll = 0;
			return true;
		}
		zoomAt(amount > 0 ? 1 : -1, mx, my);
		return true;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == LEFT) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
					p().playClickSound();
					h.run.run();
					return true;
				}
			}
			if (mx >= PANEL_W) {
				dragging = true;
				dragged = false;
				downX = mx;
				downY = my;
			}
			return true;
		}
		if (button == RIGHT && mx >= PANEL_W && world != null) {
			addAt(bx(mx), bz(my));
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (!dragging) return false;
		if (Math.abs(mx - downX) + Math.abs(my - downY) > 2) dragged = true;
		cx -= dx / zoom();
		cz -= dy / zoom();
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (dragging && !dragged) {
			selected = null;
			deathSelected = false;
		}
		dragging = false;
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		if (key.is(KeyInput.Key.UP)) cz -= 32 / zoom();
		else if (key.is(KeyInput.Key.DOWN)) cz += 32 / zoom();
		else if (key.is(KeyInput.Key.LEFT)) cx -= 32 / zoom();
		else if (key.is(KeyInput.Key.RIGHT)) cx += 32 / zoom();
		else return false;
		return true;
	}
}
