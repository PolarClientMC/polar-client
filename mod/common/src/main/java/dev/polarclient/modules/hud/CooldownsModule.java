package dev.polarclient.modules.hud;

import dev.polarclient.gfx.Gfx;
import dev.polarclient.module.Category;
import dev.polarclient.module.HudModule;
import dev.polarclient.platform.ItemKind;
import dev.polarclient.platform.ItemView;
import dev.polarclient.util.Draw;

import java.util.Locale;

/**
 * Item cooldowns under the crosshair: ender pearl, wind charge (1.21+), shield and chorus fruit, each with the seconds
 * left and a bar that empties. Shown only while something is cooling down. Read from the game's own cooldowns.
 */
public class CooldownsModule extends HudModule {
	private static final ItemKind[] KINDS = {ItemKind.ENDER_PEARL, ItemKind.WIND_CHARGE, ItemKind.SHIELD, ItemKind.CHORUS_FRUIT};
	private static final String[] NAMES = {"Pearl", "Wind charge", "Shield", "Chorus"};
	/** Full cooldown of each, in seconds. */
	private static final float[] SECONDS = {1f, 0.5f, 5f, 1f};
	private static final int W = 104, ROW = 14;
	private final float[] cool = new float[KINDS.length];

	public CooldownsModule() {
		super("item_cooldowns", "Item Cooldowns", "Pearl and wind charge cooldown timers under the crosshair.", Category.COMBAT, false, 188, 150);
	}

	@Override
	public String unavailableReason() {
		return p().hasAttackCooldown() ? null : "Item cooldowns came in Minecraft 1.9.";
	}

	@Override
	protected void beginRender(boolean preview) {
		boolean any = false;
		for (int i = 0; i < KINDS.length; i++) {
			cool[i] = p().hasPlayer() ? p().itemCooldown(KINDS[i]) : 0;
			if (cool[i] > 0) any = true;
		}
		if (!any && preview) {
			cool[0] = 0.6f;
			cool[1] = 0.35f;
		}
	}

	private int active() {
		int n = 0;
		for (float f : cool) if (f > 0) n++;
		return n;
	}

	@Override
	public int width(boolean preview) {
		return W;
	}

	@Override
	public int height(boolean preview) {
		return Math.max(1, active()) * ROW - 2;
	}

	@Override
	public boolean hasContent() {
		if (!p().hasPlayer()) return false;
		for (ItemKind k : KINDS) if (p().itemCooldown(k) > 0) return true;
		return false;
	}

	@Override
	protected void draw(Gfx g, boolean preview) {
		int y = 0;
		for (int i = 0; i < KINDS.length; i++) {
			float f = cool[i];
			if (f <= 0) continue;
			Draw.round(g, 0, y, W, ROW - 2, 3, fade(0x73000000));
			ItemView icon = p().itemOf(KINDS[i]);
			if (icon != null) {
				g.push();
				g.translate(1, y);
				g.scale(0.75f);
				g.item(icon, 0, 0);
				g.pop();
			}
			g.text(NAMES[i], 15, y + 2, fade(0xFFFFFFFF), true);
			String s = String.format(Locale.ROOT, "%.1fs", f * SECONDS[i]);
			g.text(s, W - 3 - Draw.width(s), y + 2, fade(0xFFFFD36B), true);
			Draw.round(g, 15, y + ROW - 4, W - 18, 1, 0, fade(0x40FFFFFF));
			Draw.round(g, 15, y + ROW - 4, Math.max(1, Math.round((W - 18) * f)), 1, 0, fade(0xFF5AD8FF));
			y += ROW;
		}
	}
}
