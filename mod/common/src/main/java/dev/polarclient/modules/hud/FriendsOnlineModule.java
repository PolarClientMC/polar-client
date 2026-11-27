package dev.polarclient.modules.hud;

import dev.polarclient.account.LauncherBridge;
import dev.polarclient.module.Category;
import dev.polarclient.module.TextHudModule;
import dev.polarclient.social.PolarSocial;

import java.util.ArrayList;
import java.util.List;

/** How many Polar friends are online, with a short note when one comes online or leaves. */
public class FriendsOnlineModule extends TextHudModule {
	private static final long NOTE_FOR = 5000;

	public FriendsOnlineModule() {
		super("friends_online", "Friends Online", "How many of your Polar friends are online.", Category.SOCIAL, false, 4, 44);
	}

	@Override
	public String unavailableReason() {
		return LauncherBridge.available() ? null : "Start the game from the Polar launcher to see your friends.";
	}

	@Override
	public boolean hasContent() {
		return PolarSocial.friendsKnown();
	}

	@Override
	protected List<String> lines(boolean preview) {
		List<String> l = new ArrayList<String>(2);
		int n = PolarSocial.friendsKnown() ? PolarSocial.onlineFriends() : preview ? 3 : 0;
		l.add("§a●§r " + (n == 1 ? "1 friend online" : n + " friends online"));
		PolarSocial.Note note = PolarSocial.friendNote();
		if (note != null && System.currentTimeMillis() - note.at < NOTE_FOR) l.add("§7" + note.text);
		else if (preview && !PolarSocial.friendsKnown()) l.add("§7Steve is online");
		return l;
	}
}
