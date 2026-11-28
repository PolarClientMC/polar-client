package dev.polarclient.modules.hud;

import dev.polarclient.module.Category;
import dev.polarclient.module.TextHudModule;
import dev.polarclient.setting.BooleanSetting;
import dev.polarclient.setting.TextSetting;
import dev.polarclient.util.Popups;

import java.util.List;

/** Kills this session, read from death messages that name you. */
public class KillCounterModule extends TextHudModule {
	private final BooleanSetting popup = add(new BooleanSetting("popup", "Show a kill message", true));
	private final TextSetting popupText = add(new TextSetting("popup_text", "Kill message", "Eliminated {player}", 48));
	private final BooleanSetting sound = add(new BooleanSetting("sound", "Kill sound", true));
	private int kills;

	public KillCounterModule() {
		super("kill_counter", "Kill Counter", "Track kills in this session.", Category.COMBAT, false, 4, 180);
		popupText.visibleWhen(() -> popup.on());
	}

	private int session = -1;

	public int kills() {
		return kills;
	}

	@Override
	public void onGameMessage(String text, boolean overlay) {
		if (!isEnabled() || overlay) return;
		if (!dev.polarclient.world.SessionStats.isKill(text, p().userName())) return;
		kills++;
		String victim = text.split(" ")[0];
		if (popup.on()) Popups.show(popupText.get().replace("{player}", victim), 0xFFFF6B6B, 2200);
		if (sound.on()) p().playAlert(0, 1.2f);
	}

	@Override
	public void tick() {
		// same session rules as the pause menu: a lobby-to-game move on a server network keeps the count
		int now = dev.polarclient.world.SessionStats.session();
		if (now != session) {
			session = now;
			kills = 0;
		}
	}

	@Override
	protected List<String> lines(boolean preview) {
		return one("Kills: " + (preview && kills == 0 ? 3 : kills));
	}
}
