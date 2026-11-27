package dev.polarclient.gui;

import dev.polarclient.Polar;
import dev.polarclient.cosmetics.Cosmetics;
import dev.polarclient.cosmetics.Wardrobe;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.social.PolarSocial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wardrobe in game. You see yourself in the world (the camera turns round to face you while it's open), wearing
 * what you pick. Owned and free items go on straight away; anything else is only tried on, just for you, until you
 * buy it. The server checks every purchase and every outfit again.
 */
public class WardrobeView extends View {
	private static final String[] TABS = {"cape", "wings", "head", "back", "aura"};
	private static final String[] TAB_NAMES = {"Capes", "Wings", "Hats", "Back", "Auras"};
	private static final float LW = 118, RW = 198;
	private static final int ACCENT = 0xFF5AD8FF, MUTED = 0xFF93A8C6, DIM = 0xFF5E7290, GOOD = 0xFF5FE3A1, GOLD = 0xFFFFD36B, BAD = 0xFFFF6B6B;

	private int tab;
	private boolean shop;
	private int shopFilter;
	private float scroll;
	/** What you see on yourself: what you wear, plus anything being tried on. */
	private Map<String, String> look = new LinkedHashMap<String, String>();
	private String trying, tryingSlot;
	private boolean confirmBuy;
	private int oldPerspective = -1;
	private String toast;
	private long toastAt;
	private final List<float[]> rects = new ArrayList<float[]>();
	private final List<Runnable> runs = new ArrayList<Runnable>();

	public WardrobeView(Object parent) {
		super(parent);
		if (Wardrobe.loaded()) look.putAll(Wardrobe.worn());
		Wardrobe.load(() -> {
			if (trying == null) {
				look = new LinkedHashMap<String, String>(Wardrobe.worn());
				Cosmetics.preview(look);
			}
		});
	}

	@Override
	public String title() {
		return "Wardrobe";
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	protected void init() {
		if (oldPerspective < 0 && p().inWorld() && p().hasPlayer()) {
			oldPerspective = p().perspective();
			p().setPerspective(2); // face yourself
		}
		Cosmetics.preview(look);
	}

	@Override
	public void removed() {
		Cosmetics.endPreview();
		if (oldPerspective >= 0) p().setPerspective(oldPerspective);
		oldPerspective = -1;
	}

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
		if (!p().inWorld()) MenuBackground.INSTANCE.render(g, width, height, mouseX, mouseY);
	}

	private void hit(float x, float y, float w, float h, Runnable r) {
		rects.add(new float[] {x, y, w, h});
		runs.add(r);
	}

	private void say(String s) {
		toast = s;
		toastAt = System.currentTimeMillis();
	}

	private static String nameOf(String id) {
		for (Cosmetics.Entry e : Cosmetics.entries()) if (e.id.equals(id)) return e.name;
		return id;
	}

	// ------------------------------------------------------------------ drawing

	private void panel(Gfx g, float x, float y, float w, float h) {
		Ui.rect(g, x, y, w, h, 7, 0xEE091328);
		Ui.gradientRect(g, x + 1, y + 1, w - 2, 16, 6, 0x1ADCECFF, 0x00DCECFF);
		Ui.ring(g, x, y, w, h, 7, 0x479CC9FF, 1f);
	}

	@Override
	public void render(Gfx g, int mx, int my, float partial) {
		Ui.frame();
		rects.clear();
		runs.clear();
		// soft shade at the sides so the panels read, the middle (you) left clear
		Ui.hGradient(g, 0, 0, width * 0.4f, height, 0x99030814, 0x00030814);
		Ui.hGradient(g, width * 0.6f, 0, width * 0.4f, height, 0x00030814, 0x99030814);
		if (!dev.polarclient.account.LauncherBridge.available() || !Cosmetics.signedIn()) {
			float cw = 230, x = width / 2f - cw / 2, y = height / 2f - 34;
			panel(g, x, y, cw, 68);
			Ui.icon(g, Icon.CHECKROOM, x + 18, y + 20, ACCENT, Fonts.ICON_M);
			Ui.textMid(g, "Wardrobe", x + 32, y + 20, 0xFFFFFFFF, Fonts.BOLD);
			Ui.textMid(g, "Sign in to Polar in the launcher, then start", x + 14, y + 40, MUTED, Fonts.SMALL);
			Ui.textMid(g, "the game from it to pick your cosmetics.", x + 14, y + 50, MUTED, Fonts.SMALL);
			return;
		}
		left(g, mx, my);
		right(g, mx, my);
		if (!p().inWorld()) {
			Ui.textCentered(g, "Join a world to see it on you", width / 2f, height / 2f, MUTED, Fonts.SMALL);
		}
		tryBar(g, mx, my);
		if (confirmBuy) confirm(g, mx, my);
		drawToast(g);
	}

	private void left(Gfx g, int mx, int my) {
		float x = 6, y = 6, h = height - 12;
		panel(g, x, y, LW, h);
		Ui.icon(g, Icon.CHECKROOM, x + 12, y + 13, ACCENT, Fonts.ICON_M);
		Ui.textMid(g, "Wardrobe", x + 22, y + 13, 0xFFFFFFFF, Fonts.BOLD);
		int coins = PolarSocial.coins();
		Ui.icon(g, Icon.TOKEN, x + 12, y + 29, GOLD, Fonts.ICON_S);
		Ui.textMid(g, coins >= 0 ? coins + " coins" : "Coins: ...", x + 20, y + 29, 0xFFFFE6A8, Fonts.SMALL);
		Ui.textMid(g, "Outfits", x + 8, y + 46, DIM, Fonts.SMALL);
		float oy = y + 53;
		List<Wardrobe.Outfit> outfits = Wardrobe.outfits();
		for (int i = 0; i < outfits.size(); i++) {
			final Wardrobe.Outfit o = outfits.get(i);
			final int idx = i;
			boolean hv = Ui.inside(mx, my, x + 4, oy, LW - 8, 18);
			if (hv) Ui.rect(g, x + 4, oy, LW - 8, 18, 4, 0x22FFFFFF);
			Ui.textMid(g, Fonts.ellipsize(o.name, Fonts.BODY, (int) (LW - 40)), x + 10, oy + 6, 0xFFFFFFFF, Fonts.BODY);
			Ui.textMid(g, o.slots.size() + (o.slots.size() == 1 ? " item" : " items"), x + 10, oy + 14, MUTED, Fonts.SMALL);
			if (hv) {
				boolean dh = Ui.inside(mx, my, x + LW - 20, oy + 3, 12, 12);
				Ui.icon(g, Icon.DELETE, x + LW - 14, oy + 9, dh ? BAD : MUTED, Fonts.ICON_S);
				hit(x + 4, oy, LW - 26, 18, () -> wearOutfit(o));
				hit(x + LW - 20, oy + 3, 12, 12, () -> {
					Wardrobe.outfits().remove(idx);
					Wardrobe.saveOutfits();
					say("Outfit deleted");
				});
			}
			oy += 20;
		}
		if (outfits.isEmpty()) {
			Ui.textMid(g, "Save looks you like and", x + 8, oy + 4, MUTED, Fonts.SMALL);
			Ui.textMid(g, "switch with the wheel (G).", x + 8, oy + 13, MUTED, Fonts.SMALL);
			oy += 22;
		}
		if (outfits.size() < Wardrobe.MAX_OUTFITS) {
			boolean hv = Ui.inside(mx, my, x + 6, oy + 2, LW - 12, 14);
			Ui.rect(g, x + 6, oy + 2, LW - 12, 14, 4, hv ? 0xE01E3868 : 0xD00C1A38);
			Ui.ring(g, x + 6, oy + 2, LW - 12, 14, 4, 0x669CC9FF, 1f);
			Ui.icon(g, Icon.ADD, x + 14, oy + 9, 0xFFDCEBFF, Fonts.ICON_S);
			Ui.textMid(g, "Save this look", x + 22, oy + 9, 0xFFFFFFFF, Fonts.SMALL);
			hit(x + 6, oy + 2, LW - 12, 14, () -> {
				Wardrobe.addOutfit(Wardrobe.worn());
				say("Saved as an outfit");
			});
		}
		// footer: other players' cosmetics
		float fy = y + h - 30;
		Ui.textMid(g, "Other players' cosmetics", x + 8, fy + 4, DIM, Fonts.SMALL);
		dev.polarclient.cosmetics.PolarCosmeticsModule cm = Polar.modules().cosmetics;
		String mode = cm.othersMode.get();
		boolean hv = Ui.inside(mx, my, x + 6, fy + 10, LW - 12, 14);
		Ui.rect(g, x + 6, fy + 10, LW - 12, 14, 4, hv ? 0xE01E3868 : 0xD00C1A38);
		Ui.textMid(g, mode, x + 12, fy + 17, 0xFFFFFFFF, Fonts.SMALL);
		Ui.icon(g, Icon.CHEVRON_RIGHT, x + LW - 14, fy + 17, MUTED, Fonts.ICON_S);
		hit(x + 6, fy + 10, LW - 12, 14, cm::cycleOthers);
	}

	private void right(Gfx g, int mx, int my) {
		float x = width - RW - 6, y = 6, h = height - 12;
		panel(g, x, y, RW, h);
		// Wardrobe / Shop
		float hw = (RW - 12) / 2f;
		for (int i = 0; i < 2; i++) {
			final boolean s = i == 1;
			float tx = x + 6 + i * hw;
			boolean on = shop == s;
			if (on) Ui.rect(g, tx, y + 6, hw, 15, 4, 0xFF2A5C9A);
			Ui.textCentered(g, s ? "Coin shop" : "My cosmetics", tx + hw / 2, y + 13.5f, on ? 0xFFFFFFFF : 0xFFB8C8DE, Fonts.SMALL);
			hit(tx, y + 6, hw, 15, () -> {
				shop = s;
				scroll = 0;
			});
		}
		// type tabs (the shop has "Everything" first)
		int n = shop ? 6 : 5;
		float tw = (RW - 12) / n;
		for (int i = 0; i < n; i++) {
			final int t = i;
			String label = shop ? (i == 0 ? "All" : TAB_NAMES[i - 1]) : TAB_NAMES[i];
			boolean on = shop ? shopFilter == i : tab == i;
			float tx = x + 6 + i * tw;
			boolean hv = Ui.inside(mx, my, tx, y + 25, tw, 13);
			if (on) Ui.rect(g, tx + 1, y + 25, tw - 2, 13, 3, 0x553A78C8);
			else if (hv) Ui.rect(g, tx + 1, y + 25, tw - 2, 13, 3, 0x22FFFFFF);
			Ui.textCentered(g, label, tx + tw / 2, y + 31.5f, on ? 0xFFFFFFFF : MUTED, Fonts.SMALL);
			hit(tx, y + 25, tw, 13, () -> {
				if (shop) shopFilter = t;
				else tab = t;
				scroll = 0;
			});
		}
		float top = y + 42, bottom = y + h - (trying != null ? 38 : 6);
		List<Cosmetics.Entry> items = new ArrayList<Cosmetics.Entry>();
		for (Cosmetics.Entry e : Cosmetics.entries()) {
			if (shop) {
				if (Wardrobe.owns(e.id) || Wardrobe.special(e.id) || Wardrobe.price(e.id) <= 0) continue;
				if (shopFilter > 0 && !e.type.equals(TABS[shopFilter - 1])) continue;
			} else if (!e.type.equals(TABS[tab])) {
				continue;
			}
			items.add(e);
		}
		g.scissor(Math.round(x), Math.round(top), Math.round(x + RW), Math.round(bottom));
		float cw = (RW - 16) / 2f, chh = 34;
		int i = 0;
		boolean inside = my >= top && my < bottom;
		if (!shop) {
			// "None" first
			card(g, x + 6, top - scroll, cw, chh, null, TABS[tab], mx, my, inside);
			i = 1;
		}
		for (Cosmetics.Entry e : items) {
			float cx = x + 6 + (i % 2) * (cw + 4), cy = top - scroll + (i / 2) * (chh + 4);
			if (cy + chh > top - 40 && cy < bottom + 40) card(g, cx, cy, cw, chh, e, e.type, mx, my, inside);
			i++;
		}
		if (shop && items.isEmpty()) {
			Ui.textCentered(g, Wardrobe.loaded() ? "Nothing for sale here right now" : "Loading...", x + RW / 2, top + 20, MUTED, Fonts.SMALL);
			i = 2;
		}
		float contentH = ((i + 1) / 2) * (chh + 4);
		if (shop) {
			// how coins come in, kept general until the earning rules are final
			float by = top - scroll + contentH + 4;
			Ui.rect(g, x + 6, by, RW - 12, 40, 5, 0x22FFD36B);
			Ui.textMid(g, "Earning coins", x + 12, by + 8, GOLD, Fonts.SMALL);
			Ui.textMid(g, "You get coins for time played with Polar,", x + 12, by + 19, 0xFFDCEBFF, Fonts.SMALL);
			Ui.textMid(g, "and from events. Your balance is kept safe", x + 12, by + 27, 0xFFDCEBFF, Fonts.SMALL);
			Ui.textMid(g, "on the Polar server.", x + 12, by + 35, 0xFFDCEBFF, Fonts.SMALL);
			contentH += 48;
		}
		g.endScissor();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - (bottom - top))));
		if (!Cosmetics.catalogReady()) Ui.textCentered(g, "Loading the cosmetics...", x + RW / 2, top + 50, MUTED, Fonts.SMALL);
	}

	private void card(Gfx g, float x, float y, float w, float h, final Cosmetics.Entry e, final String slot, int mx, int my, boolean inside) {
		String id = e == null ? null : e.id;
		boolean on = id == null ? !look.containsKey(slot) : id.equals(look.get(slot));
		boolean worn = id == null ? !Wardrobe.worn().containsKey(slot) : id.equals(Wardrobe.worn().get(slot));
		boolean hv = inside && Ui.inside(mx, my, x, y, w, h);
		Ui.rect(g, x, y, w, h, 5, on ? 0xF02A5C9A : hv ? 0xE01E3868 : 0xD00C1A38);
		Ui.ring(g, x, y, w, h, 5, on ? 0xFFCFE9FF : 0x479CC9FF, 1f);
		Ui.icon(g, iconFor(slot), x + 11, y + h / 2, on ? 0xFFFFFFFF : ACCENT, Fonts.ICON_S);
		Ui.textMid(g, Fonts.ellipsize(e == null ? "None" : e.name, Fonts.BODY, (int) (w - 24)), x + 20, y + 12, 0xFFFFFFFF, Fonts.BODY);
		String sub;
		int sc;
		if (e == null) {
			sub = worn ? "Wearing nothing" : "Take it off";
			sc = MUTED;
		} else if (worn) {
			sub = "Wearing";
			sc = GOOD;
		} else if (Wardrobe.owns(id)) {
			sub = "Yours";
			sc = GOOD;
		} else if (Wardrobe.special(id)) {
			sub = "Special: not sold";
			sc = 0xFFC08BFF;
		} else if (Wardrobe.price(id) > 0) {
			sub = Wardrobe.price(id) + " coins";
			sc = GOLD;
		} else {
			sub = "Free";
			sc = MUTED;
		}
		Ui.textMid(g, sub, x + 20, y + 23, sc, Fonts.SMALL);
		if (inside) hit(x, y, w, h, () -> pick(slot, id));
	}

	private static String iconFor(String slot) {
		switch (slot) {
			case "cape": return Icon.FLARE;
			case "wings": return Icon.AUTO_AWESOME;
			case "head": return Icon.STAR;
			case "back": return Icon.INVENTORY;
			default: return Icon.SNOWFLAKE;
		}
	}

	private void pick(String slot, String id) {
		confirmBuy = false;
		if (id == null || Wardrobe.canWear(id)) {
			trying = null;
			final Map<String, String> next = Wardrobe.with(Wardrobe.worn(), slot, id);
			look = next;
			Cosmetics.preview(look);
			Wardrobe.wear(next, ok -> {
				if (ok) say(id == null ? "Taken off" : "Wearing " + nameOf(id));
			});
		} else {
			// try it on: only you see it
			trying = id;
			tryingSlot = slot;
			look = Wardrobe.with(Wardrobe.worn(), slot, id);
			Cosmetics.preview(look);
		}
	}

	private void wearOutfit(Wardrobe.Outfit o) {
		trying = null;
		confirmBuy = false;
		Map<String, String> next = new LinkedHashMap<String, String>();
		for (Map.Entry<String, String> e : o.slots.entrySet()) if (Wardrobe.canWear(e.getValue())) next.put(e.getKey(), e.getValue());
		look = next;
		Cosmetics.preview(look);
		final String name = o.name;
		Wardrobe.wear(next, ok -> {
			if (ok) say("Wearing " + name);
		});
	}

	private void tryBar(Gfx g, int mx, int my) {
		if (trying == null) return;
		int coins = PolarSocial.coins(), price = Wardrobe.price(trying);
		float w = RW - 12, x = width - RW, y = height - 12 - 32;
		Ui.rect(g, x, y, w, 26, 5, 0xF0102040);
		Ui.ring(g, x, y, w, 26, 5, 0x80FFD36B, 1f);
		Ui.textMid(g, "Trying on", x + 8, y + 8, MUTED, Fonts.SMALL);
		Ui.textMid(g, Fonts.ellipsize(nameOf(trying), Fonts.BOLD, 80), x + 8, y + 18, 0xFFFFFFFF, Fonts.BOLD);
		float bx = x + w - 4;
		bx -= 44;
		button(g, "Take off", bx, y + 5, 40, mx, my, false, () -> {
			trying = null;
			look = new LinkedHashMap<String, String>(Wardrobe.worn());
			Cosmetics.preview(look);
		});
		bx -= 62;
		if (Wardrobe.special(trying)) {
			Ui.textRight(g, "Not sold", bx + 58, y + 13, 0xFFC08BFF, Fonts.SMALL);
		} else if (coins >= 0 && coins < price) {
			Ui.textRight(g, "Need " + (price - coins) + " more", bx + 58, y + 13, GOLD, Fonts.SMALL);
		} else {
			button(g, "Buy for " + price, bx, y + 5, 58, mx, my, true, () -> confirmBuy = true);
		}
	}

	private void confirm(Gfx g, int mx, int my) {
		g.fill(0, 0, width, height, 0x88020814);
		int coins = PolarSocial.coins(), price = Wardrobe.price(trying);
		float w = 230, h = 84, x = width / 2f - w / 2, y = height / 2f - h / 2;
		Ui.rect(g, x, y, w, h, 8, 0xFA091328);
		Ui.ring(g, x, y, w, h, 8, 0x995AD8FF, 1f);
		Ui.textMid(g, "Buy " + nameOf(trying) + "?", x + 12, y + 14, 0xFFFFFFFF, Fonts.BOLD);
		Ui.textMid(g, price + " coins. You'll have " + Math.max(0, coins - price) + " left.", x + 12, y + 30, 0xFFFFE6A8, Fonts.SMALL);
		Ui.textMid(g, "It's yours on every Polar PC.", x + 12, y + 40, MUTED, Fonts.SMALL);
		final String id = trying, slot = tryingSlot;
		button(g, "Cancel", x + w - 132, y + h - 24, 56, mx, my, false, () -> confirmBuy = false);
		button(g, "Buy", x + w - 70, y + h - 24, 58, mx, my, true, () -> {
			confirmBuy = false;
			Wardrobe.buy(id, ok -> {
				if (!ok) return;
				say("Bought " + nameOf(id));
				trying = null;
				Map<String, String> next = Wardrobe.with(Wardrobe.worn(), slot, id);
				look = next;
				Cosmetics.preview(look);
				Wardrobe.wear(next, null);
			});
		});
	}

	private void button(Gfx g, String label, float x, float y, float w, int mx, int my, boolean primary, Runnable run) {
		boolean hv = Ui.inside(mx, my, x, y, w, 16);
		if (primary) Ui.gradientRect(g, x, y, w, 16, 3, hv ? 0xFFFFFFFF : 0xFFD7EEFF, 0xFF8FCBFF);
		else Ui.rect(g, x, y, w, 16, 3, hv ? 0xE01E3868 : 0xD00C1A38);
		Ui.ring(g, x, y, w, 16, 3, primary ? 0xE6EAF6FF : 0x809CC9FF, 1f);
		Ui.textCentered(g, label, x + w / 2, y + 8, primary ? 0xFF0B1A33 : 0xFFFFFFFF, Fonts.SMALL);
		hit(x, y, w, 16, run);
	}

	private void drawToast(Gfx g) {
		if (toast == null) return;
		float age = (System.currentTimeMillis() - toastAt) / 1000f;
		if (age > 2.2f) {
			toast = null;
			return;
		}
		float a = Math.max(0, Math.min(1, Math.min(age / 0.15f, (2.2f - age) / 0.3f)));
		float tw = Fonts.width(toast, Fonts.BODY) + 30, x = width / 2f - tw / 2, y = 8;
		Ui.rect(g, x, y, tw, 18, 9, Theme.alpha(0xEB09142C, a));
		Ui.ring(g, x, y, tw, 18, 9, Theme.alpha(0x995AD8FF, a), 1f);
		Ui.icon(g, Icon.CHECK, x + 11, y + 9, Theme.alpha(ACCENT, a), Fonts.ICON_S);
		Ui.textMid(g, toast, x + 20, y + 9, Theme.alpha(0xFFFFFFFF, a), Fonts.BODY);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT) return false;
		if (confirmBuy) {
			// only the dialog's own buttons count while it's up
			for (int i = runs.size() - 1; i >= Math.max(0, runs.size() - 2); i--) {
				float[] r = rects.get(i);
				if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
					p().playClickSound();
					runs.get(i).run();
					return true;
				}
			}
			return true;
		}
		for (int i = runs.size() - 1; i >= 0; i--) {
			float[] r = rects.get(i);
			if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
				p().playClickSound();
				runs.get(i).run();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scroll = Math.max(0, scroll - (float) amount * 20);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		if (key.is(KeyInput.Key.ESCAPE) && confirmBuy) {
			confirmBuy = false;
			return true;
		}
		return false;
	}
}
