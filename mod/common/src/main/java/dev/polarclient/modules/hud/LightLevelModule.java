package dev.polarclient.modules.hud;

import dev.polarclient.module.Category;
import dev.polarclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

/** Block light where you stand; mobs spawn at 0. */
public class LightLevelModule extends TextHudModule {
	public LightLevelModule() {
		super("light_level", "Light Level", "Block light at your feet, and whether mobs can spawn there.", Category.WORLD, false, 4, 232);
	}

	@Override
	public String unavailableReason() {
		return p().minecraftVersion().startsWith("1.8") ? "Light Level isn't on Minecraft 1.8.9 yet." : null;
	}

	@Override
	public boolean hasContent() {
		return p().hasPlayer() && p().blockLight() >= 0;
	}

	@Override
	protected List<String> lines(boolean preview) {
		int light = p().hasPlayer() ? p().blockLight() : 7;
		if (light < 0) light = 7;
		List<String> l = new ArrayList<String>(2);
		l.add("Light " + light);
		l.add(light == 0 ? "§cMobs can spawn here" : "§7Too bright for mobs");
		return l;
	}
}
