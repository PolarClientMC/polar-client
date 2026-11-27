package dev.polarclient.hooks;

import dev.polarclient.platform.ItemView;
import dev.polarclient.platform.Platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts the open chest or shulker box by moving stacks with ordinary clicks, a few each tick, as a player would. It only
 * swaps stacks of different items, so nothing is ever merged or split and Undo can put every stack back. Each step looks
 * at the box as it is now, so if the server disagrees with a click the sorter simply stops.
 */
public final class BoxSorter {
	private BoxSorter() {
	}

	private static final int SWAPS_PER_TICK = 3;

	/** What each slot should hold when done (null = empty), compared by item only. */
	private static List<ItemView> target;
	/** The box as it was before the last sort, for Undo. */
	private static List<ItemView> before;
	private static long sortedAt;
	private static boolean undoing;

	public static boolean running() {
		return target != null;
	}

	/** Seconds since the last sort finished (Undo is offered for 5), or -1. */
	public static float sinceSort() {
		return before == null || running() ? -1 : (System.currentTimeMillis() - sortedAt) / 1000f;
	}

	public static void sort() {
		Platform p = Platform.get();
		final List<ItemView> live = p.boxSlots();
		if (live == null || running() || p.carryingItem()) return;
		List<ItemView> items = new ArrayList<ItemView>();
		for (ItemView i : live) if (i != null) items.add(i);
		final Platform fp = p;
		Collections.sort(items, new Comparator<ItemView>() {
			@Override
			public int compare(ItemView a, ItemView b) {
				int c = fp.itemKey(a).compareTo(fp.itemKey(b));
				return c != 0 ? c : b.count - a.count;
			}
		});
		List<ItemView> t = new ArrayList<ItemView>(items);
		while (t.size() < live.size()) t.add(null);
		before = new ArrayList<ItemView>(live);
		target = t;
		undoing = false;
	}

	public static void undo() {
		if (before == null || running()) return;
		target = before;
		before = null;
		undoing = true;
	}

	public static void cancel() {
		target = null;
		before = null;
	}

	private static boolean same(Platform p, ItemView a, ItemView b) {
		if (a == null || b == null) return a == b;
		return p.sameItem(a, b);
	}

	public static void tick(Platform p) {
		if (target == null) return;
		List<ItemView> live = p.boxSlots();
		if (live == null || live.size() != target.size() || p.carryingItem()) {
			target = null;
			return;
		}
		for (int done = 0; done < SWAPS_PER_TICK; done++) {
			int i = 0;
			while (i < live.size() && same(p, live.get(i), target.get(i))) i++;
			if (i == live.size()) {
				// finished
				target = null;
				sortedAt = System.currentTimeMillis();
				if (undoing) before = null;
				return;
			}
			ItemView want = target.get(i);
			int j = -1;
			for (int k = i + 1; k < live.size(); k++) {
				ItemView have = live.get(k);
				// a slot that holds what i wants and isn't already where it belongs
				if (same(p, have, want) && !same(p, have, target.get(k))) {
					j = k;
					break;
				}
			}
			if (j < 0) {
				for (int k = i + 1; k < live.size() && j < 0; k++) if (same(p, live.get(k), want)) j = k;
			}
			if (j < 0) {
				target = null;
				return;
			}
			if (live.get(i) == null) {
				p.clickSlot(j, 0);
				p.clickSlot(i, 0);
			} else if (want == null) {
				p.clickSlot(i, 0);
				p.clickSlot(j, 0);
			} else {
				p.clickSlot(j, 0);
				p.clickSlot(i, 0);
				p.clickSlot(j, 0);
			}
			if (p.carryingItem()) {
				// something didn't go as planned: stop with the stack back in the box if there is room
				for (int k = 0; k < live.size(); k++) {
					List<ItemView> now = p.boxSlots();
					if (now != null && k < now.size() && now.get(k) == null) {
						p.clickSlot(k, 0);
						break;
					}
				}
				target = null;
				return;
			}
			live = p.boxSlots();
			if (live == null) {
				target = null;
				return;
			}
		}
	}
}
