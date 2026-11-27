package dev.polarclient.modules.hud;

import dev.polarclient.account.LauncherBridge;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.module.TextHudModule;
import dev.polarclient.social.PolarSocial;

import java.util.List;
import java.util.Locale;

/** Your Polar coin balance; "+N" floats up when coins come in. The balance is the server's, never the game's. */
public class CoinsCounterModule extends TextHudModule {
	private static final long POP = 1600;
	private final Roll roll = new Roll();

	public CoinsCounterModule() {
		super("coins_counter", "Coins Counter", "Your Polar coin balance, with +N when you earn some.", false, 400, 4);
	}

	@Override
	public String unavailableReason() {
		return LauncherBridge.available() ? null : "Start the game from the Polar launcher to see your coins.";
	}

	@Override
	public boolean hasContent() {
		return PolarSocial.coins() >= 0;
	}

	@Override
	protected List<String> lines(boolean preview) {
		int c = PolarSocial.coins();
		int shown = c >= 0 ? roll.next(c) : 1240;
		// leave room for the coin icon drawn in front
		return one("   " + String.format(Locale.ROOT, "%,d", shown));
	}

	@Override
	protected void draw(Gfx g, boolean preview) {
		super.draw(g, preview);
		int h = height(preview), x = background.on() ? PAD_X : 0;
		g.text(Icon.TOKEN, x - 1, h / 2f - 4.5f, fade(0xFFFFD36B), UiFont.ICON_S, false);
		int got = PolarSocial.recentCoins(POP);
		float age = (System.currentTimeMillis() - PolarSocial.recentCoinsAt()) / (float) POP;
		if (preview && got == 0) {
			got = 10;
			age = 0.3f;
		}
		if (got > 0 && age < 1) {
			int a = Math.max(4, Math.round(255 * (1 - age)));
			String s = "+" + got;
			// floats up beside the box (left of it, so it never leaves the screen at the top edge)
			drawText(g, s, -textWidth(s) - 4, Math.round(h / 2f - 4 - 8 * age), (a << 24) | 0x7CF29A, shadow.on());
		}
	}
}
