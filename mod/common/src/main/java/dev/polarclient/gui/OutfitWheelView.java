package dev.polarclient.gui;

import dev.polarclient.cosmetics.Cosmetics;
import dev.polarclient.cosmetics.Wardrobe;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.ui.Fonts;
import dev.polarclient.gui.ui.Icon;
import dev.polarclient.gui.ui.Theme;
import dev.polarclient.gui.ui.Ui;
import dev.polarclient.platform.PolarKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hold G: your outfits round a wheel. Point at one and let go of G (or click) to wear it; the centre saves the look
 * you have on. It doesn't pause the game.
 */
public class OutfitWheelView extends View {
	private static final float R_IN = 30, R_OUT = 92;
	private final long openedAt = System.currentTimeMillis();
	private int hovered = -2; // -1 = centre
	private boolean sawHeld, done;

	public OutfitWheelView(Object parent) {
		super(parent);
		if (!Wardrobe.loaded()) Wardrobe.load(null);
	}

	@Override
	public String title() {
		return "Outfits";
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	public void renderBackground(Gfx g, int mouseX, int mouseY) {
	}

	@Override
	public void removed() {
		Cosmetics.endPreview();
	}

	private int at(double mx, double my, int n) {
		double dx = mx - width / 2.0, dy = my - height / 2.0, d = Math.sqrt(dx * dx + dy * dy);
		if (d < R_IN) return -1;
		if (d > R_OUT + 30 || n == 0) return -2;
		double a = Math.toDegrees(Math.atan2(dx, -dy));
		if (a < 0) a += 360;
		return (int) ((a + 180.0 / n) % 360 / (360.0 / n));
	}

	@Override
	public void tick() {
		int held = p().polarKeyHeldRaw(PolarKey.OUTFITS);
		if (held == 1) sawHeld = true;
		// let go of G: wear what's under the mouse
		if (held == 0 && sawHeld && !done) choose(hovered);
	}

	@Override
	public void render(Gfx g, int mx, int my, float partial) {
		Ui.frame();
		List<Wardrobe.Outfit> outfits = Wardrobe.outfits();
		int n = outfits.size();
		float k = Ui.easeOut((System.currentTimeMillis() - openedAt) / 160f);
		float cx = width / 2f, cy = height / 2f;
		int h = at(mx, my, n);
		if (h != hovered) {
			hovered = h;
			if (h >= 0) Cosmetics.preview(outfits.get(h).slots);
			else Cosmetics.endPreview();
		}
		Ui.circle(g, cx, cy, (R_OUT + 14) * k, Theme.alpha(0xB0091328, k));
		for (int i = 0; i < n; i++) {
			double a = Math.toRadians(i * 360.0 / n);
			float ox = cx + (float) Math.sin(a) * (R_IN + R_OUT) / 2 * k, oy = cy - (float) Math.cos(a) * (R_IN + R_OUT) / 2 * k;
			boolean on = i == hovered;
			Ui.circle(g, ox, oy, on ? 25 : 22, on ? 0xFF2A5C9A : 0xD00C1A38);
			if (on) Ui.circle(g, ox, oy, 25, 0x335AD8FF);
			Ui.icon(g, Icon.CHECKROOM, ox, oy - 6, on ? 0xFFFFFFFF : 0xFF8CC8FF, Fonts.ICON_S);
			Ui.textCentered(g, Fonts.ellipsize(outfits.get(i).name, Fonts.SMALL, 46), ox, oy + 5, 0xFFFFFFFF, Fonts.SMALL);
			Ui.textCentered(g, outfits.get(i).slots.size() + " items", ox, oy + 13, 0xFF93A8C6, Fonts.SMALL);
		}
		boolean centre = hovered == -1;
		Ui.circle(g, cx, cy, R_IN - 4, centre ? 0xFF2A5C9A : 0xE0102040);
		if (n == 0) {
			Ui.textCentered(g, "No outfits yet", cx, cy - 4, 0xFFFFFFFF, Fonts.SMALL);
			Ui.textCentered(g, "Click to save", cx, cy + 5, 0xFF93A8C6, Fonts.SMALL);
		} else if (hovered >= 0) {
			Ui.textCentered(g, "Let go", cx, cy - 4, 0xFFFFFFFF, Fonts.SMALL);
			Ui.textCentered(g, "to wear", cx, cy + 5, 0xFF93A8C6, Fonts.SMALL);
		} else {
			Ui.icon(g, Icon.ADD, cx, cy - 5, n >= Wardrobe.MAX_OUTFITS ? 0xFF5E7290 : 0xFFFFFFFF, Fonts.ICON_S);
			Ui.textCentered(g, n >= Wardrobe.MAX_OUTFITS ? "Wheel full" : "Save this one", cx, cy + 6, 0xFF93A8C6, Fonts.SMALL);
		}
		Ui.textCentered(g, "Point at an outfit and let go of the key, or click it. Esc to cancel.", cx, cy + R_OUT + 26, 0xFFB8C8DE, Fonts.SMALL);
	}

	private void choose(int i) {
		done = true;
		List<Wardrobe.Outfit> outfits = Wardrobe.outfits();
		if (i == -1) {
			if (Wardrobe.addOutfit(Wardrobe.worn())) dev.polarclient.util.Popups.show("Saved this look as an outfit", 0xFF5AD8FF, 2000);
		} else if (i >= 0 && i < outfits.size()) {
			Map<String, String> next = new LinkedHashMap<String, String>();
			for (Map.Entry<String, String> e : outfits.get(i).slots.entrySet()) if (Wardrobe.canWear(e.getValue())) next.put(e.getKey(), e.getValue());
			final String name = outfits.get(i).name;
			Wardrobe.wear(next, ok -> {
				if (ok) dev.polarclient.util.Popups.show("Wearing " + name, 0xFF5AD8FF, 2000);
			});
		}
		onClose();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		int i = at(mx, my, Wardrobe.outfits().size());
		if (i == -2) onClose();
		else choose(i);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		return false;
	}
}
