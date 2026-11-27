package dev.polarclient.cosmetics;

import dev.polarclient.module.Category;
import dev.polarclient.module.Module;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.setting.NumberSetting;

/**
 * Polar cosmetics in game: capes, wings, hats, back items and auras that Polar players picked in the launcher's
 * wardrobe, and the Polar badge next to their names. Only other Polar players see them.
 */
public class PolarCosmeticsModule extends Module {
	private final BooleanSetting others = add(new BooleanSetting("show_others", "Show other players' cosmetics", true));
	/** Shown / Hidden in fights / Hidden. "Hidden in fights" starts at the first hit given or taken and ends 10 s after. */
	public final dev.polarclient.setting.ModeSetting othersMode = add(new dev.polarclient.setting.ModeSetting("others_mode", "Other players' cosmetics",
			"Shown", "Shown", "Hidden in fights", "Hidden"));
	private long lastHurt;
	private final BooleanSetting own = add(new BooleanSetting("show_own", "Show my cosmetics", true));
	private final BooleanSetting auras = add(new BooleanSetting("auras", "Show auras", true));
	private final BooleanSetting badges = add(new BooleanSetting("badges", "Show the Polar badge next to names", true));
	private final BooleanSetting animateWings = add(new BooleanSetting("animate_wings", "Animate wings", true));
	private final BooleanSetting wingParticles = add(new BooleanSetting("wing_particles", "Wing particles", true));
	private final NumberSetting wingSize = add(new NumberSetting("wing_size", "Wing size", 100, 80, 120, 5, "%"));

	public PolarCosmeticsModule() {
		super("polar_cosmetics", "Polar Cosmetics", "Capes, wings, hats and auras from the Polar wardrobe.", Category.CUSTOMIZATION, true);
	}

	boolean showOthers() {
		return others.on();
	}

	/** Whether other players' cosmetics are drawn right now (the old on/off switch still counts as Hidden). */
	boolean showOthersNow() {
		if (!others.on()) return false;
		String m = othersMode.get();
		if ("Hidden".equals(m)) return false;
		if ("Hidden in fights".equals(m)) {
			long since = Math.min(dev.polarclient.util.CombatTracker.msSinceLastHit(), System.currentTimeMillis() - lastHurt);
			return since > 10_000;
		}
		return true;
	}

	/** The Controls key: Shown, Hidden in fights, Hidden, round again. */
	public void cycleOthers() {
		String[] modes = {"Shown", "Hidden in fights", "Hidden"};
		String m = othersMode.get();
		int i = 0;
		for (int k = 0; k < modes.length; k++) if (modes[k].equals(m)) i = k;
		othersMode.set(modes[(i + 1) % modes.length]);
		if (!others.on()) others.toggle();
		dev.polarclient.Polar.saveConfig();
		dev.polarclient.util.Popups.show("Other players' cosmetics: " + othersMode.get(), 0xFF5AD8FF, 2000);
	}

	boolean showOwn() {
		return own.on();
	}

	boolean showAuras() {
		return auras.on();
	}

	boolean showBadges() {
		return badges.on();
	}

	/** Off: wings hold their resting pose (their glow and colours still move). */
	boolean animateWings() {
		return animateWings.on();
	}

	boolean wingParticles() {
		return wingParticles.on();
	}

	/** How big wings are drawn, 0.8 .. 1.2. */
	float wingScale() {
		return (float) (wingSize.get() / 100.0);
	}

	@Override
	public void tick() {
		if (p().hasPlayer() && p().damageTilt() > 0) lastHurt = System.currentTimeMillis();
		while (p().consumePolarKey(dev.polarclient.platform.PolarKey.TOGGLE_COSMETICS)) cycleOthers();
		while (p().consumePolarKey(dev.polarclient.platform.PolarKey.OUTFITS)) {
			if (isEnabled() && p().inWorld() && !p().screenOpen() && dev.polarclient.account.LauncherBridge.available()) p().open(new dev.polarclient.gui.OutfitWheelView(null));
		}
		Cosmetics.tick();
	}
}
