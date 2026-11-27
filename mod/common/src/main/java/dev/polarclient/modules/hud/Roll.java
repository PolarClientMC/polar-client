package dev.polarclient.modules.hud;

/** A number that rolls toward its new value instead of jumping (for counters). */
final class Roll {
	private float shown = Float.NaN;
	private long last = System.nanoTime();

	/** The value to show this frame for a target of {@code target}. */
	int next(int target) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - last) / 1e9f);
		last = now;
		if (Float.isNaN(shown) || Math.abs(target - shown) > 100000) shown = target;
		shown += (target - shown) * (1 - (float) Math.exp(-8 * dt));
		if (Math.abs(target - shown) < 0.5f) shown = target;
		return Math.round(shown);
	}
}
