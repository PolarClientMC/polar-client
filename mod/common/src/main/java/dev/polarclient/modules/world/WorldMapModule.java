package dev.polarclient.modules.world;

import dev.polarclient.gui.WorldMapView;
import dev.polarclient.module.Category;
import dev.polarclient.module.Module;
import dev.polarclient.platform.PolarKey;
import dev.polarclient.setting.BooleanSetting;

/** A map of everywhere you've been (press M), built from the chunks the game showed you. */
public class WorldMapModule extends Module {
	public final BooleanSetting showWaypoints = add(new BooleanSetting("show_waypoints", "Waypoints on the map", true));
	public final BooleanSetting grid = add(new BooleanSetting("grid", "Chunk grid", false));

	public WorldMapModule() {
		super("world_map", "World Map", "A map of everywhere you've been. Press M. Places you haven't seen stay dark.", Category.WORLD, true);
	}

	@Override
	public void tick() {
		while (p().consumePolarKey(PolarKey.WORLD_MAP)) {
			if (isEnabled() && p().inWorld() && !p().screenOpen()) p().open(new WorldMapView(null));
		}
	}
}
