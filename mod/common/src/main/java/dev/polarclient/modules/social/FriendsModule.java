package dev.polarclient.modules.social;

import dev.polarclient.gfx.Gfx;
import dev.polarclient.gfx.UiFont;
import dev.polarclient.gui.FriendsPanelView;
import dev.polarclient.gui.ProfileCardView;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.module.Category;
import dev.polarclient.module.Module;
import dev.polarclient.platform.PolarKey;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.social.SocialToasts;
import dev.polarclient.util.Draw;

import java.util.List;

/** Friends, messages and parties in game: the panel (O), profile cards (P) and the little cards in the corner. */
public class FriendsModule extends Module {
	public final BooleanSetting popups = add(new BooleanSetting("popups", "Message and party pop-ups", true));

	public FriendsModule() {
		super("friends_panel", "Friends and Parties", "Your Polar friends, messages and party in game. Press O; look at a player and press P for their profile.",
				Category.SOCIAL, true);
	}

	@Override
	public void tick() {
		SocialToasts.tick(p());
		while (p().consumePolarKey(PolarKey.FRIENDS)) {
			if (SocialToasts.warpLeft() >= 0) {
				SocialToasts.stayHere(); // O during the party countdown means "stay here"
				continue;
			}
			if (isEnabled() && !p().screenOpen()) p().open(new FriendsPanelView(null));
		}
		while (p().consumePolarKey(PolarKey.PROFILE)) {
			if (!isEnabled() || p().screenOpen()) continue;
			String[] who = p().crosshairPlayer();
			if (who != null) p().open(new ProfileCardView(null, who[0], who[1]));
		}
	}

	@Override
	public void renderOverlay(Gfx g) {
		float warp = SocialToasts.warpLeft();
		int y = 4;
		if (warp >= 0) {
			String key = p().polarKeyName(PolarKey.FRIENDS);
			String main = SocialToasts.warpFrom() + " is bringing the party to " + (p().hideServerAddresses() ? "another server" : SocialToasts.warpServer());
			String sub = "Joining in " + (int) Math.ceil(warp) + (key != null ? ". Press " + key + " to stay here." : ".");
			y = card(g, y, 0xFFFFD36B, Icon.GROUPS, main, sub, 1 - warp / (SocialToasts.WARP_MS / 1000f));
		}
		if (!popups.on() || !isEnabled()) return;
		List<SocialToasts.Toast> list = SocialToasts.live();
		String key = p().polarKeyName(PolarKey.FRIENDS);
		for (SocialToasts.Toast t : list) {
			String main, sub;
			int c;
			String icon;
			if (t.kind == SocialToasts.Kind.MESSAGE) {
				main = t.who;
				sub = t.text;
				c = 0xFF5AD8FF;
				icon = Icon.CHAT;
			} else if (t.kind == SocialToasts.Kind.PARTY) {
				main = t.who + " " + t.text;
				sub = key != null ? "Press " + key + " and open Party to join" : "Open Friends > Party to join";
				c = 0xFFFFD36B;
				icon = Icon.GROUPS;
			} else {
				main = t.who;
				sub = t.text;
				c = 0xFF93A8C6;
				icon = Icon.NOTIFICATIONS;
			}
			float age = (System.currentTimeMillis() - t.at) / 1000f;
			float a = Math.min(1, Math.min(age / 0.2f, (SocialToasts.LIFE / 1000f - age) / 0.4f));
			if (a <= 0) continue;
			y = card(g, y, c, icon, main, sub, -1);
		}
	}

	/** A small card at the top left; returns the y below it. */
	private int card(Gfx g, int y, int color, String icon, String main, String sub, float progress) {
		int maxW = 200;
		String m = Draw.ellipsize(main, maxW - 30), s = Draw.ellipsize(sub, maxW - 30);
		int w = Math.max(Draw.width(m), Draw.width(s)) + 30, h = 24, x = 4;
		Draw.round(g, x, y, w, h, 4, 0xD80B1426);
		g.fill(x, y + 3, x + 2, y + h - 3, color);
		Ui.icon(g, icon, x + 12, y + h / 2f, color, UiFont.ICON_S);
		g.text(m, x + 22, y + 3, 0xFFFFFFFF, true);
		g.text(s, x + 22, y + 13, 0xFFB8C8DE, true);
		if (progress >= 0) g.fill(x + 2, y + h - 1, x + 2 + Math.round((w - 4) * Math.min(1, progress)), y + h, color);
		return y + h + 3;
	}
}
