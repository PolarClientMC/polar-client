package dev.polarclient.modules.hud;

import dev.polarclient.gfx.Gfx;
import dev.polarclient.module.Category;
import dev.polarclient.module.HudModule;
import dev.polarclient.platform.ItemKind;
import dev.polarclient.platform.ItemView;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.util.Draw;

import java.util.Locale;

/** Arrows left (and how many are spectral), as an extra slot next to the hotbar. */
public class ArrowCounterModule extends HudModule {
	private final BooleanSetting onlyBow;
	private int arrows, spectral;

	public ArrowCounterModule() {
		super("arrow_counter", "Arrow Counter", "Arrows left, next to your hotbar.", Category.COMBAT, false, 337, 248);
		onlyBow = add(new BooleanSetting("only_bow", "Only while holding a bow or crossbow", false));
	}

	@Override
	protected void beginRender(boolean preview) {
		if (p().hasPlayer()) {
			spectral = p().countItems(ItemKind.SPECTRAL_ARROW);
			arrows = p().countItems(ItemKind.ARROW) + p().countItems(ItemKind.TIPPED_ARROW) + spectral;
		} else {
			arrows = 48;
			spectral = 12;
		}
	}

	@Override
	public boolean hasContent() {
		if (!p().hasPlayer()) return false;
		if (!onlyBow.on()) return true;
		ItemView held = p().heldItem();
		String name = held == null ? "" : String.valueOf(p().itemName(held)).toLowerCase(Locale.ROOT);
		return name.contains("bow");
	}

	@Override
	public int width(boolean preview) {
		return 22;
	}

	@Override
	public int height(boolean preview) {
		return 22;
	}

	@Override
	protected void draw(Gfx g, boolean preview) {
		Draw.round(g, 0, 0, 22, 22, 3, fade(0x73000000));
		ItemView icon = p().itemOf(arrows > 0 && spectral == arrows ? ItemKind.SPECTRAL_ARROW : ItemKind.ARROW);
		if (icon != null) g.item(icon, 3, 3);
		if (arrows == 0) {
			Draw.centered(g, "None", 11, 7, fade(0xFFFF8A7A), true);
			return;
		}
		String n = String.valueOf(arrows);
		g.text(n, 21 - Draw.width(n), 13, fade(0xFFFFFFFF), true);
		if (spectral > 0 && spectral < arrows) g.text(String.valueOf(spectral), 2, 1, fade(0xFFFFD36B), true);
	}
}
