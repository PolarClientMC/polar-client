package dev.polarclient.modules;

import dev.polarclient.module.Category;
import dev.polarclient.module.Module;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.setting.ModeSetting;

/** Client-wide options. Always on; shown as a settings card in the menu. */
public class ClientSettingsModule extends Module {
	public final ModeSetting accent = add(new ModeSetting("accent", "Accent color", "Ice", Theme.ACCENT_NAMES));
	public final BooleanSetting amoled = add(new BooleanSetting("amoled", "Pure black (AMOLED) menus", true));
	public final BooleanSetting customMainMenu = add(new BooleanSetting("custom_main_menu", "Polar main menu", true));
	public final BooleanSetting customServerList = add(new BooleanSetting("custom_server_list", "Polar server list", true));
	public final BooleanSetting customPauseMenu = add(new BooleanSetting("custom_pause_menu", "Polar pause menu", true));
	public final BooleanSetting customGameScreens = add(new BooleanSetting("custom_game_screens", "Polar death and disconnect screens", true));
	public final BooleanSetting chestSort = add(new BooleanSetting("chest_sort", "Sort button on chests and shulker boxes", true));
	public final BooleanSetting autoRetry = add(new BooleanSetting("auto_retry", "Retry lost connections by itself", false));
	public final BooleanSetting snow = add(new BooleanSetting("snow", "Snowfall on the main menu", true));
	public final ModeSetting seasonal = add(new ModeSetting("seasonal", "Seasonal main menu (Christmas)", "Auto", "Auto", "Always", "Off"));
	public final BooleanSetting windowTitle = add(new BooleanSetting("window_title", "\"Polar Client\" window title", true));
	public final BooleanSetting hideHudInDebug = add(new BooleanSetting("hide_hud_in_debug", "Hide mods while F3 is open", true));
	public final BooleanSetting menuBlur = add(new BooleanSetting("menu_blur", "Blur behind the mod menu", true));
	public final ModeSetting menuSize = add(new ModeSetting("menu_size", "Mod menu size", "Auto", "Small", "Auto", "Large"));
	public final BooleanSetting reducedMotion = add(new BooleanSetting("reduced_motion", "Reduced motion (no menu animations)", false));

	public ClientSettingsModule() {
		super("client", "Polar Settings", "Theme, main menu, window title and menu options.", Category.UTILITY, true);
	}

	@Override
	public void tick() {
		Ui.reducedMotion = reducedMotion.on();
	}

	@Override
	public boolean canToggle() {
		return false;
	}

	@Override
	public void setEnabled(boolean enabled) {
		super.setEnabled(true);
	}
}
