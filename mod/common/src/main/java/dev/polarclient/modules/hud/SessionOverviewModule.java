package dev.polarclient.modules.hud;

import dev.polarclient.module.TextHudModule;
import dev.polarclient.world.SessionStats;

import java.util.ArrayList;
import java.util.List;

/** Play time, coins earned and kills since joining (the same numbers as the pause menu). */
public class SessionOverviewModule extends TextHudModule {
	private final Roll coins = new Roll(), kills = new Roll();

	public SessionOverviewModule() {
		super("session_overview", "Session Overview", "Play time, coins earned and kills since you joined.", false, 4, 4);
	}

	@Override
	public boolean hasContent() {
		return p().inWorld();
	}

	@Override
	protected List<String> lines(boolean preview) {
		boolean live = p().inWorld();
		List<String> l = new ArrayList<String>(3);
		l.add("Played " + (live ? SessionStats.playTime() : "1h 12m"));
		l.add("Coins +" + (live ? coins.next(SessionStats.coins()) : 40));
		l.add("Kills " + (live ? kills.next(SessionStats.kills()) : 3));
		return l;
	}
}
