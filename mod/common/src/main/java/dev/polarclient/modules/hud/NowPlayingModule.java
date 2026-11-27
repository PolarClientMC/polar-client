package dev.polarclient.modules.hud;

import com.google.gson.JsonObject;
import dev.polarclient.account.LauncherBridge;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.module.HudModule;
import dev.polarclient.setting.ModeSetting;
import dev.polarclient.util.Draw;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What's playing: Minecraft's own music (track names from the game's sound files) or Spotify (the launcher reads
 * what the PC is playing). Title, artist, where it comes from, a progress bar and the time, with small equaliser bars.
 */
public class NowPlayingModule extends HudModule {
	private static final int W = 150, H = 34;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Polar now playing");
		t.setDaemon(true);
		return t;
	});

	private final ModeSetting source;
	// Spotify, from the launcher
	private volatile String spTitle, spArtist;
	private volatile float spPos, spLen;
	private volatile boolean spPlaying;
	private volatile long spAt;
	private volatile boolean busy;
	private long nextPoll;
	// Minecraft music
	private String mcTrack;
	private long mcStarted;

	public NowPlayingModule() {
		super("now_playing", "Now Playing", "The music playing now: Minecraft's own or Spotify.", false, 326, 30);
		source = add(new ModeSetting("source", "Music from", "Both", "Both", "Minecraft", "Spotify"));
	}

	@Override
	public void tick() {
		if (!isEnabled()) return;
		String t = p().musicTrack();
		if (t != null && !t.equals(mcTrack)) mcStarted = System.currentTimeMillis();
		mcTrack = t;
		long now = System.currentTimeMillis();
		if (!source.is("Minecraft") && LauncherBridge.available() && !busy && now >= nextPoll) {
			nextPoll = now + 2000;
			busy = true;
			IO.execute(this::pollSpotify);
		}
	}

	private void pollSpotify() {
		try {
			JsonObject o = LauncherBridge.polar("GET", "/polar/nowplaying", null, 4000);
			if (o.has("title") && o.get("title").isJsonPrimitive()) {
				spTitle = o.get("title").getAsString();
				spArtist = o.has("artist") && o.get("artist").isJsonPrimitive() ? o.get("artist").getAsString() : "";
				spPos = o.has("position") ? o.get("position").getAsFloat() : 0;
				spLen = o.has("duration") ? o.get("duration").getAsFloat() : 0;
				spPlaying = o.has("playing") && o.get("playing").getAsBoolean();
				spAt = System.currentTimeMillis();
			} else {
				spTitle = null;
			}
		} catch (RuntimeException e) {
			spTitle = null;
		} finally {
			busy = false;
		}
	}

	private boolean spotifyNow() {
		return !source.is("Minecraft") && spTitle != null && !spTitle.isEmpty() && spPlaying && System.currentTimeMillis() - spAt < 8000;
	}

	private boolean minecraftNow() {
		return !source.is("Spotify") && mcTrack != null;
	}

	@Override
	public boolean hasContent() {
		return spotifyNow() || minecraftNow();
	}

	@Override
	public int width(boolean preview) {
		return W;
	}

	@Override
	public int height(boolean preview) {
		return H;
	}

	@Override
	protected void draw(Gfx g, boolean preview) {
		String title, artist, from;
		float pos, len;
		boolean playing = true;
		if (spotifyNow()) {
			title = spTitle;
			artist = spArtist;
			from = "Spotify";
			pos = spPos + (spPlaying ? (System.currentTimeMillis() - spAt) / 1000f : 0);
			len = spLen;
		} else if (minecraftNow()) {
			String[] t = track(mcTrack);
			title = t[0];
			artist = t[1];
			from = "Minecraft";
			pos = (System.currentTimeMillis() - mcStarted) / 1000f;
			len = 0;
		} else {
			title = "Sweden";
			artist = "C418";
			from = "Minecraft";
			pos = 72;
			len = 215;
			playing = preview;
		}
		if (len > 0) pos = Math.min(pos, len);
		Draw.round(g, 0, 0, W, H, 3, fade(0x73000000));
		// equaliser
		float t = System.currentTimeMillis() / 1000f;
		for (int i = 0; i < 4; i++) {
			float h = playing ? 3 + 7 * (0.5f + 0.5f * (float) Math.sin(t * (5 + i * 1.7) + i * 1.3)) : 2;
			Draw.round(g, 5 + i * 3, Math.round(17 - h), 2, Math.max(1, Math.round(h)), 0, fade(from.equals("Spotify") ? 0xFF1ED760 : 0xFF5AD8FF));
		}
		int tx = 21, maxW = W - tx - 4;
		g.text(Draw.ellipsize(title, maxW), tx, 4, fade(0xFFFFFFFF), true);
		g.text(Draw.ellipsize(artist + "  ·  " + from, maxW), tx, 14, fade(0xFFAFC0D8), true);
		// progress and time
		Draw.round(g, 5, H - 6, W - 54, 2, 1, fade(0x40FFFFFF));
		if (len > 0) Draw.round(g, 5, H - 6, Math.max(1, Math.round((W - 54) * pos / len)), 2, 1, fade(0xFFFFFFFF));
		String time = len > 0 ? clock(pos) + " / " + clock(len) : clock(pos);
		g.text(time, W - 4 - Draw.width(time), H - 10, fade(0xFFAFC0D8), true);
	}

	private static String clock(float s) {
		int v = Math.max(0, Math.round(s));
		return String.format(Locale.ROOT, "%d:%02d", v / 60, v % 60);
	}

	// ------------------------------------------------------------------ Minecraft's music: file name to title and artist

	private static final Map<String, String[]> TRACKS = new HashMap<String, String[]>();

	private static void put(String artist, String... pairs) {
		for (int i = 0; i + 1 < pairs.length; i += 2) TRACKS.put(pairs[i], new String[]{pairs[i + 1], artist});
	}

	static {
		put("C418", "calm1", "Minecraft", "calm2", "Clark", "calm3", "Sweden", "hal1", "Subwoofer Lullaby", "hal2", "Living Mice",
			"hal3", "Haggstrom", "hal4", "Danny", "nuance1", "Key", "nuance2", "Oxygène", "piano1", "Dry Hands", "piano2", "Wet Hands",
			"piano3", "Mice on Venus", "creative1", "Biome Fest", "creative2", "Blind Spots", "creative3", "Haunt Muskie",
			"creative4", "Aria Math", "creative5", "Dreiton", "creative6", "Taswell", "menu1", "Mutation", "menu2", "Moog City 2",
			"menu3", "Beginning 2", "menu4", "Floating Trees", "end", "The End", "boss", "Boss", "credits", "Alpha",
			"nether1", "Concrete Halls", "nether2", "Dead Voxel", "nether3", "Warmth", "nether4", "Ballad of the Cats",
			"axolotl", "Axolotl", "dragon_fish", "Dragon Fish", "shuniji", "Shuniji");
		put("Lena Raine", "aerie", "Aerie", "firebugs", "Firebugs", "labyrinthine", "Labyrinthine", "left_to_bloom", "Left to Bloom",
			"one_more_day", "One More Day", "stand_tall", "Stand Tall", "wending", "Wending", "infinite_amethyst", "Infinite Amethyst",
			"chrysopoeia", "Chrysopoeia", "rubedo", "Rubedo", "so_below", "So Below", "ancestry", "Ancestry", "deeper", "Deeper",
			"eld_unknown", "Eld Unknown", "endless", "Endless", "featherfall", "Featherfall", "watcher", "Watcher", "puzzlebox", "Puzzlebox",
			"tears", "Tears");
		put("Kumi Tanioka", "komorebi", "komorebi", "pokopoko", "pokopoko", "yakusoku", "yakusoku", "floating_dream", "Floating Dream");
		put("Aaron Cherof", "a_familiar_room", "A Familiar Room", "bromeliad", "Bromeliad", "crescent_dunes", "Crescent Dunes",
			"echo_in_the_wind", "Echo in the Wind", "precipice", "Precipice");
		put("Amos Roddy", "below_and_above", "Below and Above", "broken_clocks", "Broken Clocks", "fireflies", "Fireflies",
			"lilypad", "Lilypad", "os_piano", "O's Piano", "an_ordinary_day", "An Ordinary Day");
	}

	/** {title, artist} for a sound path like "music/game/calm1". */
	static String[] track(String path) {
		String file = path.substring(path.lastIndexOf('/') + 1);
		String[] known = TRACKS.get(file);
		if (known != null) return known;
		String pretty = file.replace('_', ' ');
		if (!pretty.isEmpty()) pretty = Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1);
		return new String[]{pretty, "Minecraft"};
	}
}
