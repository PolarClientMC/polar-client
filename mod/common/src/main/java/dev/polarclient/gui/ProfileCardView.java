package dev.polarclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.social.PolarSocial;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A player's Polar profile card (P while looking at them, or a click on a name): who they are and what to do. */
public class ProfileCardView extends View {
	private static final float W = 230, H = 150;
	private static final int ACCENT = 0xFF5AD8FF, MUTED = 0xFF93A8C6, GOOD = 0xFF5FE3A1, WARN = 0xFFFFC15E, BAD = 0xFFFF6B6B;

	private final String uuid, name;
	private JsonObject profile;
	private boolean failed;
	private final long openedAt = System.currentTimeMillis();
	private final List<float[]> hitRects = new ArrayList<float[]>();
	private final List<Runnable> hitRuns = new ArrayList<Runnable>();

	public ProfileCardView(Object parent, String uuid, String name) {
		super(parent);
		this.uuid = uuid;
		this.name = name;
		JsonObject b = new JsonObject();
		if (uuid != null) b.addProperty("uuid", uuid);
		if (name != null) b.addProperty("name", name);
		if (!dev.polarclient.account.LauncherBridge.available()) {
			failed = true;
			return;
		}
		PolarSocial.call("POST", "/v1/social/profile", b, r -> {
			if (r == null) failed = true;
			else profile = r;
		});
	}

	@Override
	public String title() {
		return "Profile";
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
		g.fill(0, 0, width, height, 0x66020814);
	}

	private static String s(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	@Override
	public void render(Gfx g, int mx, int my, float partial) {
		Ui.frame();
		hitRects.clear();
		hitRuns.clear();
		float k = Ui.easeOut((System.currentTimeMillis() - openedAt) / 200f);
		float x = width / 2f - W / 2, y = height / 2f - H / 2;
		Ui.rect(g, x, y, W, H, 8, Theme.alpha(0xF5091328, k));
		Ui.gradientRect(g, x + 1, y + 1, W - 2, 30, 7, 0x2A5AD8FF, 0x005AD8FF);
		Ui.ring(g, x, y, W, H, 8, Theme.alpha(0x669CC9FF, k), 1f);
		String shown = profile != null && !s(profile, "name").isEmpty() ? s(profile, "name") : name;
		UUID id = null;
		try {
			id = UUID.fromString(profile != null && !s(profile, "uuid").isEmpty() ? s(profile, "uuid") : uuid);
		} catch (RuntimeException ignored) {
			// by name only
		}
		// the face, big, on a soft ice disc
		Ui.circle(g, x + 40, y + 46, 30, 0x221E5B95);
		g.playerHead(id != null ? id : FriendsPanelView.headId(null, shown), shown, x + 16, y + 22, 48);
		float tx = x + 80;
		Ui.textMid(g, Fonts.ellipsize(shown == null ? "Player" : shown, Fonts.LARGE, (int) (W - 96)), tx, y + 20, 0xFFFFFFFF, Fonts.LARGE);
		boolean ch = Ui.inside(mx, my, x + W - 20, y + 6, 14, 14);
		Ui.icon(g, Icon.CLOSE, x + W - 13, y + 13, ch ? 0xFFFFFFFF : MUTED, Fonts.ICON_S);
		hit(x + W - 20, y + 6, 14, 14, this::onClose);
		if (profile == null) {
			Ui.textMid(g, failed ? "Couldn't load this profile" : "Loading...", tx, y + 36, MUTED, Fonts.SMALL);
			return;
		}
		if (!profile.has("polar") || !profile.get("polar").isJsonPrimitive() || !profile.get("polar").getAsBoolean()) {
			Ui.textMid(g, "Not on Polar Client", tx, y + 36, MUTED, Fonts.BODY);
			Ui.textMid(g, "They don't use Polar, so there's", tx, y + 52, 0xFF7C8DA6, Fonts.SMALL);
			Ui.textMid(g, "no profile to show.", tx, y + 61, 0xFF7C8DA6, Fonts.SMALL);
			return;
		}
		// the PolarTag they show, and what it means
		float ly = y + 33;
		JsonObject tag = activeTag();
		if (tag != null) {
			String label = s(tag, "label");
			int c = parse(s(tag, "color"), ACCENT);
			float bw = Fonts.width(label, Fonts.SMALL) + 10;
			Ui.rect(g, tx, ly - 5, bw, 10, 3, Theme.alpha(c, 0.25f));
			Ui.textMid(g, label, tx + 5, ly, c, Fonts.SMALL);
			String means = s(tag, "means");
			Ui.textMid(g, means.isEmpty() ? meaning(s(tag, "id")) : means, tx + bw + 5, ly, MUTED, Fonts.SMALL);
			ly += 12;
		}
		String status = s(profile, "status");
		int sc = "online".equals(status) ? GOOD : "busy".equals(status) ? BAD : "away".equals(status) ? WARN : 0xFF5E7290;
		Ui.circle(g, tx + 2.5f, ly, 2.5f, sc);
		String where = "";
		if (profile.has("where") && profile.get("where").isJsonObject()) {
			JsonObject w = profile.getAsJsonObject("where");
			if ("server".equals(s(w, "state"))) where = p().hideServerAddresses() ? "  on a server" : "  on " + s(w, "server");
			else if ("singleplayer".equals(s(w, "state"))) where = "  in singleplayer";
		}
		String st = "online".equals(status) ? "Online" : "busy".equals(status) ? "Busy" : "away".equals(status) ? "Away" : "Offline";
		Ui.textMid(g, Fonts.ellipsize(st + where, Fonts.SMALL, (int) (W - 100)), tx + 8, ly, 0xFFDCEBFF, Fonts.SMALL);
		ly += 11;
		long since = profile.has("since") && profile.get("since").isJsonPrimitive() ? profile.get("since").getAsLong() : 0;
		if (since > 0) {
			Ui.textMid(g, "Polar player since " + new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(new Date(since)), tx, ly, MUTED, Fonts.SMALL);
			ly += 11;
		}
		List<String> wearing = new ArrayList<String>();
		if (profile.has("wearing") && profile.get("wearing").isJsonArray()) for (JsonElement e : profile.getAsJsonArray("wearing")) if (e.isJsonPrimitive()) wearing.add(e.getAsString());
		Ui.textMid(g, "Wearing", x + 16, y + 84, 0xFF5E7290, Fonts.SMALL);
		Ui.textMid(g, Fonts.ellipsize(wearing.isEmpty() ? "No cosmetics" : String.join(", ", wearing), Fonts.SMALL, (int) (W - 32)), x + 16, y + 94, 0xFFDCEBFF, Fonts.SMALL);

		// what you can do
		boolean me = profile.has("me") && profile.get("me").isJsonPrimitive() && profile.get("me").getAsBoolean();
		String friend = s(profile, "friend");
		final String theirId = s(profile, "uuid");
		float by = y + H - 30, bw3 = (W - 36) / 3f;
		if (me) {
			button(g, "Open wardrobe", x + 12, by, W - 24, mx, my, true, () -> p().open(new WardrobeView(p().currentScreen())));
		} else if ("friends".equals(friend)) {
			String server = profile.has("where") && profile.get("where").isJsonObject() && "server".equals(s(profile.getAsJsonObject("where"), "state"))
					? s(profile.getAsJsonObject("where"), "server") : null;
			final String fs = server;
			button(g, "Message", x + 12, by, bw3, mx, my, true, () -> p().open(FriendsPanelView.messages(null, theirId, s(profile, "name"))));
			if (fs != null && !fs.equalsIgnoreCase(p().serverAddress())) button(g, "Join", x + 18 + bw3, by, bw3, mx, my, false, () -> p().joinServer(fs));
			else button(g, "Invite", x + 18 + bw3, by, bw3, mx, my, false, () -> {
				JsonObject b = new JsonObject();
				b.addProperty("uuid", theirId);
				PolarSocial.friendAction("invite", b);
			});
			button(g, "Party invite", x + 24 + 2 * bw3, by, bw3, mx, my, false, () -> {
				JsonObject b = new JsonObject();
				b.addProperty("uuid", theirId);
				PolarSocial.partyAction("invite", b);
			});
		} else if ("out".equals(friend)) {
			button(g, "Friend request sent", x + 12, by, W - 24, mx, my, false, () -> { });
		} else {
			button(g, "in".equals(friend) ? "Accept friend request" : "Add friend", x + 12, by, W - 24, mx, my, true, () -> {
				JsonObject b = new JsonObject();
				if ("in".equals(friend)) {
					b.addProperty("uuid", theirId);
					PolarSocial.friendAction("accept", b);
				} else {
					b.addProperty("name", s(profile, "name"));
					PolarSocial.friendAction("add", b);
				}
				profile.addProperty("friend", "in".equals(friend) ? "friends" : "out");
			});
		}
	}

	private JsonObject activeTag() {
		String active = s(profile, "activeTag");
		if (!profile.has("tags") || !profile.get("tags").isJsonArray()) return null;
		JsonArray tags = profile.getAsJsonArray("tags");
		JsonObject first = null;
		for (JsonElement e : tags) {
			if (!e.isJsonObject()) continue;
			JsonObject t = e.getAsJsonObject();
			if (first == null) first = t;
			if (s(t, "id").equals(active)) return t;
		}
		return active.isEmpty() ? null : first;
	}

	private static String meaning(String id) {
		switch (id) {
			case "dev": return "Makes Polar Client";
			case "staff": return "Polar staff";
			case "partner": return "Runs a partner server";
			case "og": return "One of the first players";
			case "vip": return "Polar VIP";
			default: return "";
		}
	}

	private static int parse(String hex, int fallback) {
		try {
			return 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private void hit(float x, float y, float w, float h, Runnable r) {
		hitRects.add(new float[] {x, y, w, h});
		hitRuns.add(r);
	}

	private void button(Gfx g, String label, float x, float y, float w, int mx, int my, boolean primary, Runnable run) {
		boolean hv = Ui.inside(mx, my, x, y, w, 18);
		if (primary) Ui.gradientRect(g, x, y, w, 18, 3, hv ? 0xFFFFFFFF : 0xFFD7EEFF, 0xFF8FCBFF);
		else Ui.rect(g, x, y, w, 18, 3, hv ? 0xE01E3868 : 0xD00C1A38);
		Ui.ring(g, x, y, w, 18, 3, primary ? 0xE6EAF6FF : 0x809CC9FF, 1f);
		Ui.textCentered(g, Fonts.ellipsize(label, Fonts.SMALL, (int) (w - 6)), x + w / 2, y + 9, primary ? 0xFF0B1A33 : 0xFFFFFFFF, Fonts.SMALL);
		hit(x, y, w, 18, run);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		float x = width / 2f - W / 2, y = height / 2f - H / 2;
		if (!Ui.inside(mx, my, x, y, W, H)) {
			onClose();
			return true;
		}
		for (int i = hitRects.size() - 1; i >= 0; i--) {
			float[] r = hitRects.get(i);
			if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
				p().playClickSound();
				hitRuns.get(i).run();
				return true;
			}
		}
		return true;
	}
}
