package dev.polarclient.modules.world;

import dev.polarclient.Polar;
import dev.polarclient.module.Category;
import dev.polarclient.module.TextHudModule;

import java.util.List;
import java.util.Locale;

/** Where you last died, and how far away it is. */
public class DeathWaypointModule extends TextHudModule {
	private double dx, dy, dz;
	private String dim;
	private boolean has, wasDead;
	/** Ticks the spot has been near enough to you to be loaded (items only age while loaded; they last 6000). */
	private int loadedTicks;
	private boolean leftOnce, arrived;

	public DeathWaypointModule() {
		super("death_waypoint", "Death Waypoints", "Remember where you last died.", Category.WORLD, true, 4, 220);
	}

	@Override
	public void tick() {
		if (!p().hasPlayer()) {
			wasDead = false;
			return;
		}
		boolean dead = p().health() <= 0;
		if (dead && !wasDead) {
			dx = p().playerX();
			dy = p().playerY();
			dz = p().playerZ();
			dim = p().dimension();
			has = true;
			loadedTicks = 0;
			leftOnce = false;
			arrived = false;
		}
		wasDead = dead;
		if (has && !dead && dim != null && dim.equals(p().dimension())) {
			double d = Math.sqrt(Math.pow(dx - p().playerX(), 2) + Math.pow(dz - p().playerZ(), 2));
			if (d < 128) loadedTicks++;
			if (d > 8) leftOnce = true;
			if (leftOnce && d <= 4) arrived = true;
			// back where you died and away again: the items are yours, the waypoint has done its job
			if (arrived && d > 24 && Polar.modules().waypoints.removeAfterVisit.on()) has = false;
		}
	}

	/** The last death in this session, or null: {x, y, z}. */
	public double[] lastDeath() {
		return has ? new double[] {dx, dy, dz} : null;
	}

	/** Forgets the last death (the death screen's Remove). */
	public void clear() {
		has = false;
	}

	/** Seconds until the dropped items despawn (they age only while the spot is loaded), 0 when gone. */
	public int despawnSeconds() {
		return Math.max(0, (6000 - loadedTicks) / 20);
	}

	/** Whether you've walked away from the spot since dying (so the way-back arrow makes sense). */
	public boolean leftSpot() {
		return leftOnce;
	}

	/** Puts the death waypoint back (the death screen's Set waypoint). */
	public void set(double x, double y, double z, String dimension) {
		dx = x;
		dy = y;
		dz = z;
		dim = dimension;
		has = true;
	}

	public String lastDeathDimension() {
		return dim;
	}

	@Override
	public boolean hasContent() {
		return has && p().hasPlayer() && p().health() > 0 && dim != null && dim.equals(p().dimension());
	}

	@Override
	protected List<String> lines(boolean preview) {
		if (!has) return one("Last death: 120, 64, -48  (35 m)");
		double d = Math.sqrt(Math.pow(dx - p().playerX(), 2) + Math.pow(dz - p().playerZ(), 2));
		return one(String.format(Locale.ROOT, "Last death: %d, %d, %d  (%.0f m)", (int) Math.floor(dx), (int) Math.floor(dy), (int) Math.floor(dz), d));
	}
}
