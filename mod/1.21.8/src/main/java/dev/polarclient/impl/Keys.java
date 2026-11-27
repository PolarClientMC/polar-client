package dev.polarclient.impl;

import com.mojang.blaze3d.platform.InputConstants;
import dev.polarclient.Polar;
import dev.polarclient.platform.PolarKey;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

import java.util.EnumMap;
import java.util.Map;

/** Polar Client key binds - all rebindable in Options > Controls > Key Binds under "Polar Client". */
public final class Keys {
	private static final String CATEGORY = "key.categories." + Polar.MOD_ID;
	private static final Map<PolarKey, KeyMapping> KEYS = new EnumMap<>(PolarKey.class);

	private Keys() {
	}

	static void register() {
		add(PolarKey.MENU, InputConstants.KEY_RSHIFT);
		add(PolarKey.HUD_EDITOR, InputConstants.UNKNOWN.getValue());
		add(PolarKey.ZOOM, InputConstants.KEY_C);
		add(PolarKey.FREELOOK, InputConstants.KEY_LALT);
		add(PolarKey.COPY_COORDS, InputConstants.KEY_J);
		add(PolarKey.ADD_WAYPOINT, InputConstants.KEY_B);
		add(PolarKey.WAYPOINTS, InputConstants.UNKNOWN.getValue());
		add(PolarKey.HITBOXES, InputConstants.KEY_H);
		add(PolarKey.JOIN_INVITE, InputConstants.KEY_Y);
		add(PolarKey.WORLD_MAP, InputConstants.KEY_M);
		add(PolarKey.FRIENDS, InputConstants.KEY_O);
		add(PolarKey.PROFILE, InputConstants.KEY_P);
		add(PolarKey.OUTFITS, InputConstants.KEY_G);
		add(PolarKey.TOGGLE_COSMETICS, InputConstants.UNKNOWN.getValue());
	}

	private static void add(PolarKey key, int defaultKey) {
		KEYS.put(key, KeyBindingHelper.registerKeyBinding(new KeyMapping(key.translationKey(), defaultKey, CATEGORY)));
	}

	public static KeyMapping get(PolarKey key) {
		return KEYS.get(key);
	}
}
