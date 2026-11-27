package dev.polarclient.modules.world;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.gui.WaypointIcons;
import dev.polarclient.gui.WaypointsView;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.module.Category;
import dev.polarclient.module.Module;
import dev.polarclient.platform.PolarKey;
import dev.polarclient.setting.ActionSetting;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.setting.NumberSetting;
import dev.polarclient.util.Draw;
import dev.polarclient.util.Popups;
import dev.polarclient.world.Waypoint;
import dev.polarclient.world.WaypointStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Named spots shown in the world with a light beam, a label and the distance. */
public class WaypointsModule extends Module {
	private static final int SAMPLES = 28;
	public final ActionSetting addHere = add(new ActionSetting("add_here", "Add waypoint here", "Or press the \"Add Waypoint Here\" key (B).", "Add", this::addHere));
	public final ActionSetting manage = add(new ActionSetting("manage", "Manage waypoints", "Rename, recolour, hide or delete waypoints.", "Open", () -> p().open(new WaypointsView(p().currentScreen()))));
	public final BooleanSetting beams = add(new BooleanSetting("beams", "Light beams", true));
	public final NumberSetting beamHeight = add(new NumberSetting("beam_height", "Beam height", 160, 16, 384, 16, " blocks"));
	public final BooleanSetting labels = add(new BooleanSetting("labels", "Names", true));
	public final BooleanSetting distance = add(new BooleanSetting("distance", "Distance", true));
	public final NumberSetting maxDistance = add(new NumberSetting("max_distance", "Hide beyond (0 = never)", 0, 0, 10000, 100, " m"));
	public final BooleanSetting showDeath = add(new BooleanSetting("show_death", "Show last death", true));
	public final BooleanSetting edgeArrows = add(new BooleanSetting("edge_arrows", "Arrows at the screen edge for waypoints out of view", true));
	public final BooleanSetting deathArrow = add(new BooleanSetting("death_arrow", "Arrow back to where you died", true));
	public final BooleanSetting removeAfterVisit = add(new BooleanSetting("remove_after_visit", "Remove the death waypoint once you've been back", true));
	public final BooleanSetting hideWhenDespawned = add(new BooleanSetting("hide_when_despawned", "Hide the arrow when the items have despawned", true));

	public WaypointsModule() {
		super("waypoints", "Waypoints", "Mark spots in the world with beams you can see from far away. Press B to add one.", Category.WORLD, true);
		beamHeight.visibleWhen(beams::on);
	}

	@Override
	public void tick() {
		while (p().consumePolarKey(PolarKey.ADD_WAYPOINT)) {
			if (isEnabled() && p().hasPlayer()) addHere();
		}
		while (p().consumePolarKey(PolarKey.WAYPOINTS)) {
			if (isEnabled() && !p().screenOpen()) p().open(new WaypointsView(null));
		}
	}

	/** A new waypoint at the player's feet, named "Waypoint N", in the next colour. */
	public void addHere() {
		String world = p().worldId();
		if (world == null || !p().hasPlayer()) {
			Popups.show("Join a world to add waypoints", 0xFFFF6B6B, 2500);
			return;
		}
		List<Waypoint> list = WaypointStore.of(world);
		if (list.size() >= WaypointStore.MAX_PER_WORLD) {
			Popups.show("This world already has " + WaypointStore.MAX_PER_WORLD + " waypoints", 0xFFFF6B6B, 2500);
			return;
		}
		int n = list.size() + 1;
		int color = WaypointsView.COLORS[(n - 1) % WaypointsView.COLORS.length];
		Waypoint w = new Waypoint("Waypoint " + n, (int) Math.floor(p().playerX()), (int) Math.floor(p().playerY()), (int) Math.floor(p().playerZ()), p().dimension(), color);
		list.add(w);
		WaypointStore.save();
		Popups.show("Added " + w.name + " at " + w.x + ", " + w.y + ", " + w.z, color, 2500);
	}

	private static final class Marker {
		final String name;
		final double x, y, z, dist;
		final int color;
		String glyph = Icon.BOOKMARK;

		Marker(String name, double x, double y, double z, int color, double dist) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
			this.color = color;
			this.dist = dist;
		}
	}

	@Override
	public void renderOverlay(Gfx g) {
		if (!p().inWorld() || !p().hasPlayer()) return;
		String world = p().worldId(), dim = p().dimension();
		double px = p().playerX(), py = p().playerY(), pz = p().playerZ();
		List<Marker> markers = new ArrayList<Marker>();
		for (Waypoint w : WaypointStore.of(world)) {
			if (!w.visible || dim == null || !dim.equals(w.dim)) continue;
			Marker mk = new Marker(w.name, w.x + 0.5, w.y, w.z + 0.5, w.color, w.distanceTo(px, py, pz));
			mk.glyph = WaypointIcons.glyph(w.icon);
			markers.add(mk);
		}
		if (showDeath.on()) {
			DeathWaypointModule death = (DeathWaypointModule) Polar.modules().byId("death_waypoint");
			double[] d = death == null ? null : death.lastDeath();
			if (d != null && dim != null && dim.equals(death.lastDeathDimension())) {
				double dist = Math.sqrt((d[0] - px) * (d[0] - px) + (d[1] - py) * (d[1] - py) + (d[2] - pz) * (d[2] - pz));
				Marker mk = new Marker("Last death", Math.floor(d[0]) + 0.5, Math.floor(d[1]), Math.floor(d[2]) + 0.5, 0xFFFF6B6B, dist);
				mk.glyph = Icon.SKULL;
				markers.add(mk);
			}
		}
		double max = maxDistance.get();
		Collections.sort(markers, (a, b) -> Double.compare(b.dist, a.dist));
		List<float[]> placed = new ArrayList<float[]>();
		for (Marker m : markers) {
			if (max > 0 && m.dist > max) continue;
			if (beams.on()) drawBeam(g, m);
			if (!drawLabel(g, m) && edgeArrows.on()) edgeArrow(g, m, placed);
		}
		if (deathArrow.on()) deathCard(g);
	}

	/** Degrees from where you're facing to (x, z): 0 ahead, 90 to the right. */
	private float bearing(double x, double z) {
		double a = Math.toDegrees(Math.atan2(z - p().playerZ(), x - p().playerX())) - (p().playerYaw() + 90);
		a = ((a % 360) + 540) % 360 - 180;
		return (float) a;
	}

	/** A waypoint out of view: a dot in its colour on the screen's edge, on the side it lies, with an arrow. */
	private void edgeArrow(Gfx g, Marker m, List<float[]> placed) {
		float rel = bearing(m.x, m.z);
		double r = Math.toRadians(rel);
		float dx = (float) Math.sin(r), dy = (float) -Math.cos(r);
		float cx = g.width() / 2f, cy = g.height() / 2f, inset = 16;
		float k = Math.min(Math.abs(dx) > 1e-4 ? (cx - inset) / Math.abs(dx) : 1e9f, Math.abs(dy) > 1e-4 ? (cy - inset) / Math.abs(dy) : 1e9f);
		float x = cx + dx * k, y = cy + dy * k;
		// stack arrows that would sit on each other, along the edge
		boolean side = Math.abs(dx * k) >= cx - inset - 0.5f;
		for (int tries = 0; tries < 12; tries++) {
			boolean clash = false;
			for (float[] q : placed) if (Math.abs(q[0] - x) < 13 && Math.abs(q[1] - y) < 13) clash = true;
			if (!clash) break;
			if (side) y += 14;
			else x += 14;
		}
		placed.add(new float[] {x, y});
		Ui.circle(g, x, y, 6.5f, 0xC00B1426);
		Ui.circle(g, x, y, 5.5f, m.color);
		Ui.icon(g, m.glyph, x, y, 0xFF0B1426, UiFont.ICON_S);
		g.push();
		g.translate(x + dx * 10, y + dy * 10);
		g.rotate(rel);
		Ui.icon(g, Icon.NAVIGATION, 0, 0, m.color, UiFont.ICON_S);
		g.pop();
	}

	/** After dying: a card at the top with an arrow back to the spot, how far it is and how long the items last. */
	private void deathCard(Gfx g) {
		DeathWaypointModule death = (DeathWaypointModule) Polar.modules().byId("death_waypoint");
		if (death == null || !death.isEnabled() || !p().hasPlayer() || p().health() <= 0 || p().screenOpen()) return;
		double[] d = death.lastDeath();
		String dim = p().dimension();
		if (d == null || dim == null || !dim.equals(death.lastDeathDimension()) || !death.leftSpot()) return;
		int left = death.despawnSeconds();
		if (left == 0 && hideWhenDespawned.on()) return;
		double dist = Math.sqrt(Math.pow(d[0] - p().playerX(), 2) + Math.pow(d[2] - p().playerZ(), 2));
		String main, sub;
		if (dist <= 4) {
			main = "You're there";
			sub = left > 0 ? "Your items are around you" : "Your items have despawned";
		} else {
			main = "Back to where you died  " + formatDistance(dist);
			sub = left > 0 ? String.format(Locale.ROOT, "Items despawn in %d:%02d", left / 60, left % 60) : "Your items have despawned";
		}
		int w = Math.max(Draw.width(main), Draw.width(sub)) + 34, h = 24, x = g.width() / 2 - w / 2, y = 4;
		Draw.round(g, x, y, w, h, 5, 0xC80B1426);
		g.fill(x + 3, y + h - 1, x + w - 3, y + h, 0xFFFF6B6B);
		float ax = x + 13, ay = y + h / 2f;
		g.push();
		g.translate(ax, ay);
		if (dist > 4) g.rotate(bearing(d[0], d[2]));
		Ui.icon(g, dist > 4 ? Icon.NAVIGATION : Icon.CHECK, 0, 0, 0xFFFF8A8A, UiFont.ICON_M);
		g.pop();
		g.text(main, x + 25, y + 3, 0xFFFFFFFF, true);
		g.text(sub, x + 25, y + 13, 0xFFFFC8C8, true);
	}

	private void drawBeam(Gfx g, Marker m) {
		double top = m.y + beamHeight.get();
		float[] prev = null;
		for (int i = 0; i <= SAMPLES; i++) {
			double y = m.y + (top - m.y) * i / SAMPLES;
			float[] cur = p().project(m.x, y, m.z);
			if (prev != null && cur != null) {
				float depth = Math.max(0.5f, (prev[2] + cur[2]) / 2f);
				float w = Math.max(1f, Math.min(7f, 0.3f * 0.9f * g.height() / depth));
				float fade = 1f - i / (float) SAMPLES;
				int color = Draw.alpha(m.color, Math.round(150 * fade * fade + 20));
				int x0 = Math.round((prev[0] + cur[0]) / 2f - w / 2f);
				int y0 = Math.round(Math.min(prev[1], cur[1])), y1 = Math.round(Math.max(prev[1], cur[1]));
				if (y1 > y0) g.fill(x0, y0, Math.round(x0 + w), y1, color);
			}
			prev = cur;
		}
	}

	/** False when the waypoint is out of view (nothing drawn). */
	private boolean drawLabel(Gfx g, Marker m) {
		float[] s = p().project(m.x, m.y + 1.0, m.z);
		if (s == null || s[0] < 0 || s[0] > g.width() || s[1] < -20 || s[1] > g.height() + 20) return false;
		int x = Math.round(s[0]), y = Math.round(s[1]);
		// the waypoint's icon in its colour
		Ui.circle(g, x, y, 5.5f, 0xC00B1426);
		Ui.circle(g, x, y, 4.5f, m.color);
		Ui.icon(g, m.glyph, x, y, 0xFF0B1426, UiFont.ICON_S);
		String text = (labels.on() ? m.name : "") + (distance.on() ? (labels.on() ? "  " : "") + formatDistance(m.dist) : "");
		if (text.isEmpty()) return true;
		int tw = Draw.width(text);
		int bx = x - tw / 2 - 4, by = y - 18;
		Draw.round(g, bx, by, tw + 8, 12, 3, 0xB00B1426);
		g.fill(bx + 2, by + 11, bx + tw + 6, by + 12, m.color);
		g.text(text, bx + 4, by + 2, 0xFFFFFFFF, true);
		return true;
	}

	public static String formatDistance(double d) {
		return d >= 1000 ? String.format(Locale.ROOT, "%.1fkm", d / 1000) : Math.round(d) + "m";
	}
}
