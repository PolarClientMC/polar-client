package dev.polarclient.hooks;

import dev.polarclient.Polar;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.gui.KeyInput;
import dev.polarclient.gui.ui.TextField;
import dev.polarclient.module.ModuleManager;
import dev.polarclient.platform.ItemView;
import dev.polarclient.platform.Platform;
import dev.polarclient.util.Draw;

import java.util.List;
import java.util.Locale;

/**
 * Inventory search and the shulker box preview. Each version calls these from its container-screen hooks; positions
 * are GUI units on the screen.
 */
public final class InventoryHooks {
	private static final int FIELD_W = 84;
	private static final TextField FIELD = new TextField(40);
	private static Object screen;
	private static String query = "";

	static {
		FIELD.setTextColor(0xFFFFFFFF);
		FIELD.setListener(v -> query = v.trim().toLowerCase(Locale.ROOT));
	}

	private InventoryHooks() {
	}

	private static boolean searchOn() {
		ModuleManager m = Polar.modules();
		return m != null && m.inventorySearch.isEnabled();
	}

	private static boolean previewOn() {
		ModuleManager m = Polar.modules();
		return m != null && m.shulkerPreview.isEnabled() && m.shulkerPreview.unavailableReason() == null;
	}

	/**
	 * Draws the search box, dims slots that don't match and shows the shulker preview. Call after the slots and before
	 * (or instead of nothing after) the tooltip. {@code hovered} is the item under the mouse when nothing is carried.
	 */
	public static void render(Gfx g, Object current, int left, int top, int imageW, int imageH, int[] xs, int[] ys, ItemView[] items,
			ItemView hovered, int mouseX, int mouseY) {
		boolean search = searchOn(), preview = previewOn() && hovered != null;
		String box = Platform.get().boxKind();
		boolean sort = box != null && Polar.modules().client.chestSort.on();
		if (!search && !preview && !sort && box == null) return;
		g.nextLayer();
		if (current != sortScreen) {
			sortScreen = current;
			BoxSorter.cancel();
		}
		if (box != null) boxLook(g, box, left, top, imageW, imageH);
		SORT_BTN[2] = 0;
		UNDO_BTN[2] = 0;
		if (sort) {
			int bx = search ? left + imageW - FIELD_W - 16 : left + imageW - 14, by = top - 15;
			if (by < 2) by = top + imageH + 3;
			drawSort(g, bx, by, mouseX, mouseY);
		}
		if (search) {
			if (current != screen) {
				screen = current;
				FIELD.setFocused(false);
				if (!Polar.modules().inventorySearch.keep.on()) {
					FIELD.setValue("");
					query = "";
				}
			}
			if (!query.isEmpty()) {
				for (int i = 0; i < items.length; i++) {
					if (!matches(items[i])) g.fill(xs[i], ys[i], xs[i] + 16, ys[i] + 16, 0xC8101622);
				}
			}
			int fx = left + imageW - FIELD_W, fy = top - 15;
			if (fy < 2) fy = top + imageH + 3;
			Draw.round(g, fx, fy, FIELD_W, 13, 4, FIELD.focused() ? 0xF0123056 : 0xE00B1426);
			Draw.round(g, fx, fy + 12, FIELD_W, 1, 0, FIELD.focused() ? 0xFF8CC8FF : 0xFF3A5A86);
			FIELD.setBounds(fx + 4, fy + 3, FIELD_W - 8);
			FIELD.visible = true;
			if (FIELD.value().isEmpty() && !FIELD.focused()) g.text("Search...", fx + 4, fy + 3, 0xFF7C8DA6, false);
			FIELD.render(g);
		}
		if (preview) {
			List<ItemView> inside = Platform.get().containerContents(hovered);
			if (inside != null) drawPreview(g, inside, mouseX, mouseY);
		}
	}

	private static Object sortScreen;
	/** x, y, w, h of the sort button and of Undo (w 0 when not shown). */
	private static final int[] SORT_BTN = new int[4], UNDO_BTN = new int[4];

	/** A thin tinted edge on shulker boxes and ender chests, and a word on what makes them special. */
	private static void boxLook(Gfx g, String box, int left, int top, int w, int h) {
		int c;
		String note;
		if ("shulker".equals(box)) {
			c = 0xFFC9A0F0;
			note = "Keeps its items when broken";
		} else if ("ender".equals(box)) {
			c = 0xFF3FE0C0;
			note = "Only you can open this";
		} else {
			return;
		}
		int e = (c & 0xFFFFFF) | 0x99000000;
		g.fill(left, top - 1, left + w, top, e);
		g.fill(left, top + h, left + w, top + h + 1, e);
		g.fill(left - 1, top, left, top + h, e);
		g.fill(left + w, top, left + w + 1, top + h, e);
		// beside vanilla's "Inventory" label, right-aligned so it never covers it
		int nw = Draw.width(note);
		if (nw < w - 70) g.text(note, left + w - 8 - nw, top + h - 94, (c & 0xFFFFFF) | 0xD0000000, false);
	}

	private static void drawSort(Gfx g, int x, int y, int mx, int my) {
		boolean hv = mx >= x && mx < x + 13 && my >= y && my < y + 13;
		boolean busy = BoxSorter.running();
		Draw.round(g, x, y, 13, 13, 4, hv ? 0xF0183A66 : 0xE00B1426);
		Draw.round(g, x, y + 12, 13, 1, 0, busy ? 0xFF5AD8FF : 0xFF3A5A86);
		// three bars, longest first: "put in order"
		int c = busy ? 0xFF5AD8FF : 0xFFE4ECF7;
		g.fill(x + 3, y + 3, x + 10, y + 4, c);
		g.fill(x + 3, y + 6, x + 8, y + 7, c);
		g.fill(x + 3, y + 9, x + 6, y + 10, c);
		SORT_BTN[0] = x;
		SORT_BTN[1] = y;
		SORT_BTN[2] = 13;
		SORT_BTN[3] = 13;
		float since = BoxSorter.sinceSort();
		if (since >= 0 && since < 5) {
			String label = "Sorted  Undo";
			int tw = Draw.width(label) + 8, ux = x - tw - 3;
			Draw.round(g, ux, y, tw, 13, 4, 0xF00B1426);
			g.text("Sorted", ux + 4, y + 3, 0xFFB8C8DE, false);
			int u = ux + 4 + Draw.width("Sorted  ");
			boolean uh = mx >= u - 2 && mx < ux + tw && my >= y && my < y + 13;
			g.text("Undo", u, y + 3, uh ? 0xFFFFFFFF : 0xFF5AD8FF, false);
			g.fill(u, y + 11, u + Draw.width("Undo"), y + 12, uh ? 0xFFFFFFFF : 0x995AD8FF);
			UNDO_BTN[0] = u - 2;
			UNDO_BTN[1] = y;
			UNDO_BTN[2] = ux + tw - u + 2;
			UNDO_BTN[3] = 13;
		} else if (hv && !busy) {
			String tip = "Sort this box";
			int tw = Draw.width(tip) + 8;
			Draw.round(g, x + 6 - tw / 2, y - 14, tw, 12, 3, 0xF0091328);
			g.text(tip, x + 10 - tw / 2, y - 12, 0xFFFFFFFF, false);
		}
	}

	private static boolean in(int[] r, double mx, double my) {
		return r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	private static boolean matches(ItemView item) {
		if (item == null) return false;
		Platform p = Platform.get();
		String name = p.itemName(item);
		if (name != null && name.toLowerCase(Locale.ROOT).contains(query)) return true;
		if (Polar.modules().inventorySearch.insideShulkers.on()) {
			List<ItemView> inside = p.containerContents(item);
			if (inside != null) {
				for (ItemView in : inside) {
					String n = in == null ? null : p.itemName(in);
					if (n != null && n.toLowerCase(Locale.ROOT).contains(query)) return true;
				}
			}
		}
		return false;
	}

	private static void drawPreview(Gfx g, List<ItemView> inside, int mouseX, int mouseY) {
		int cols = 9, rows = Math.max(3, (inside.size() + cols - 1) / cols);
		int w = cols * 18 + 8, h = rows * 18 + 8;
		int x = Math.max(2, Math.min(g.width() - w - 2, mouseX + 8));
		int y = mouseY - h - 16;
		if (y < 2) {
			// no room above the mouse: to its left, level with it
			x = mouseX - w - 14;
			y = Math.max(2, Math.min(g.height() - h - 2, mouseY - h / 2));
			if (x < 2) x = Math.min(g.width() - w - 2, mouseX + 14);
		}
		Draw.round(g, x - 1, y - 1, w + 2, h + 2, 5, 0xFF2A4A72);
		Draw.round(g, x, y, w, h, 4, 0xF20B1426);
		for (int i = 0; i < cols * rows; i++) {
			int sx = x + 4 + (i % cols) * 18, sy = y + 4 + (i / cols) * 18;
			g.fill(sx, sy, sx + 18, sy + 18, (i + i / cols) % 2 == 0 ? 0x22FFFFFF : 0x14FFFFFF);
			ItemView item = i < inside.size() ? inside.get(i) : null;
			if (item != null) {
				g.item(item, sx + 1, sy + 1);
				g.itemDecorations(item, sx + 1, sy + 1);
			}
		}
	}

	public static boolean typing() {
		return searchOn() && FIELD.focused();
	}

	/** True when the click was on the search box (focus it). Clicks elsewhere unfocus it and go on to the game. */
	public static boolean mouseClicked(double mx, double my) {
		if (in(UNDO_BTN, mx, my)) {
			Platform.get().playClickSound();
			BoxSorter.undo();
			return true;
		}
		if (in(SORT_BTN, mx, my)) {
			Platform.get().playClickSound();
			BoxSorter.sort();
			return true;
		}
		if (!searchOn() || !FIELD.visible) return false;
		return FIELD.mouseClicked(mx, my);
	}

	/** While typing in the search box every key belongs to it (E, number keys, Q). Escape and Enter stop typing. */
	public static boolean keyPressed(KeyInput k) {
		if (!typing() && searchOn() && FIELD.visible && k.control && k.is(KeyInput.Key.F)) {
			FIELD.setFocused(true);
			return true;
		}
		if (!typing()) return false;
		if (k.is(KeyInput.Key.ESCAPE) || k.confirm()) {
			FIELD.setFocused(false);
			return true;
		}
		FIELD.keyPressed(k);
		return true;
	}

	public static boolean charTyped(char c) {
		return typing() && FIELD.charTyped(c);
	}

	/** The container screen closed. */
	public static void closed() {
		BoxSorter.cancel();
		sortScreen = null;
		FIELD.setFocused(false);
		FIELD.visible = false;
	}
}
