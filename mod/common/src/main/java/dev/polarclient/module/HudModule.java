package dev.polarclient.module;

import com.google.gson.JsonObject;
import dev.polarclient.gfx.Gfx;
import dev.polarclient.setting.NumberSetting;

/**
 * A module drawn on the HUD that can be dragged and scaled in the HUD editor.
 * Positions are stored as a gap from the edge (or centre) the element is pinned to, so elements keep their place
 * on any screen size and grow away from the edge they're pinned to.
 */
public abstract class HudModule extends Module {
	/** Reference screen size used for the default layout (GUI-scaled 1080p at scale 4). */
	public static final double REF_W = 480, REF_H = 270;

	public final NumberSetting scale;
	/** How see-through the element is (text and box), 10-100%. */
	public final NumberSetting opacity;

	private final double defaultX, defaultY;
	/**
	 * Position: the element's anchor point (ax, ay in {0, 0.5, 1} of its own box) sits at ax * screen width + ox,
	 * ay * screen height + oy. So an element pinned to the right keeps the same gap to the right edge on any screen,
	 * and grows away from the edge it is pinned to.
	 */
	private float ax, ay;
	private double ox, oy;
	/** The anchor was picked by hand in the HUD editor (otherwise it follows where the element sits). */
	private boolean manualAnchor;
	/** Name of the group this element moves with in the HUD editor, or null. */
	private String group;
	private int lastX, lastY, lastW, lastH;
	/** Set until the first render, when the default spot is converted into an edge-anchored position. */
	private boolean needsDefaultPlacement;
	/** Old configs stored fractions of the screen; converted on the first render, when the screen size is known. */
	private double legacyFx = -1, legacyFy;
	private String legacyAx, legacyAy;

	protected HudModule(String id, String name, String description, boolean enabledByDefault, double refX, double refY) {
		this(id, name, description, Category.HUD, enabledByDefault, refX, refY);
	}

	protected HudModule(String id, String name, String description, Category category, boolean enabledByDefault, double refX, double refY) {
		super(id, name, description, category, enabledByDefault);
		this.scale = add(new NumberSetting("scale", "Scale", 1.0, 0.5, 2.5, 0.05, "x"));
		this.opacity = add(new NumberSetting("opacity", "Opacity", 100, 10, 100, 5, "%"));
		this.defaultX = refX;
		this.defaultY = refY;
		resetPosition();
	}

	/** Unscaled size of the element. {@code preview} is true in the HUD editor (show sample data). */
	public abstract int width(boolean preview);

	public abstract int height(boolean preview);

	/** Draws the element with its top-left corner at (0, 0), unscaled. */
	protected abstract void draw(Gfx g, boolean preview);

	/** Whether there is anything to show outside the editor (e.g. no potion effects = hidden). */
	public boolean hasContent() {
		return true;
	}

	/** Called as {@link #render} starts and ends, so a module can work out what it shows once per frame. */
	protected void beginRender(boolean preview) {
	}

	protected void endRender() {
	}

	/** {@code argb} with this element's opacity applied. */
	protected int fade(int argb) {
		float o = opacity.getFloat() / 100f;
		if (o >= 0.999f) return argb;
		int a = Math.max(4, Math.round((argb >>> 24) * o));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public final void render(Gfx g, int screenW, int screenH, boolean preview) {
		if (!preview && !hasContent()) return;
		beginRender(preview);
		try {
			place(g, screenW, screenH, preview);
		} finally {
			endRender();
		}
	}

	/** Size on screen right now (scale applied). */
	public int scaledWidth(boolean preview) {
		return Math.max(1, (int) Math.ceil(width(preview) * scale.getFloat()));
	}

	public int scaledHeight(boolean preview) {
		return Math.max(1, (int) Math.ceil(height(preview) * scale.getFloat()));
	}

	private void place(Gfx g, int screenW, int screenH, boolean preview) {
		float s = scale.getFloat();
		int w = scaledWidth(preview), h = scaledHeight(preview);
		lastW = w;
		lastH = h;
		if (needsDefaultPlacement) {
			needsDefaultPlacement = false;
			setPosition((int) Math.round(defaultX / REF_W * screenW), (int) Math.round(defaultY / REF_H * screenH), screenW, screenH);
		} else if (legacyFx >= 0) {
			// fx was the anchored edge as a fraction of the screen
			double ex = legacyFx * screenW, ey = legacyFy * screenH;
			int x = (int) Math.round("CENTER".equals(legacyAx) ? ex - w / 2.0 : "END".equals(legacyAx) ? ex - w : ex);
			int y = (int) Math.round("CENTER".equals(legacyAy) ? ey - h / 2.0 : "END".equals(legacyAy) ? ey - h : ey);
			legacyFx = -1;
			setPosition(x, y, screenW, screenH);
		}
		int x = (int) Math.round(Math.max(0, Math.min(screenW - w, ax * screenW + ox - ax * w)));
		int y = (int) Math.round(Math.max(0, Math.min(screenH - h, ay * screenH + oy - ay * h)));
		lastX = x;
		lastY = y;
		g.push();
		g.translate(x, y);
		g.scale(s);
		draw(g, preview);
		g.pop();
	}

	/** Moves the element so its top-left is at (x, y). Unless the anchor was picked by hand, re-picks it from where the element now sits. */
	public void setPosition(int x, int y, int screenW, int screenH) {
		int w = Math.max(1, lastW), h = Math.max(1, lastH);
		x = Math.max(0, Math.min(screenW - w, x));
		y = Math.max(0, Math.min(screenH - h, y));
		if (!manualAnchor) {
			double cx = x + w / 2.0, cy = y + h / 2.0;
			ax = cx < screenW / 3.0 ? 0 : cx > screenW * 2 / 3.0 ? 1 : 0.5f;
			ay = cy < screenH / 3.0 ? 0 : cy > screenH * 2 / 3.0 ? 1 : 0.5f;
		}
		moveTo(x, y, screenW, screenH);
	}

	/** Moves the element so its top-left is at (x, y), keeping its anchor (no clamping: groups clamp as a whole). */
	public void moveTo(double x, double y, int screenW, int screenH) {
		int w = Math.max(1, lastW), h = Math.max(1, lastH);
		ox = x + ax * w - ax * screenW;
		oy = y + ay * h - ay * screenH;
		lastX = (int) Math.round(x);
		lastY = (int) Math.round(y);
	}

	/** Pins the element to a side, corner or the centre (each 0, 0.5 or 1), keeping where it is on screen. */
	public void setAnchor(float ax, float ay, boolean manual, int screenW, int screenH) {
		int x = lastX, y = lastY;
		this.ax = ax;
		this.ay = ay;
		this.manualAnchor = manual;
		moveTo(x, y, screenW, screenH);
	}

	public float anchorX() {
		return ax;
	}

	public float anchorY() {
		return ay;
	}

	public boolean manualAnchor() {
		return manualAnchor;
	}

	public String group() {
		return group;
	}

	public void setGroup(String group) {
		this.group = group;
	}

	public void resetPosition() {
		ax = ay = 0;
		ox = defaultX;
		oy = defaultY;
		manualAnchor = false;
		group = null;
		legacyFx = -1;
		needsDefaultPlacement = true;
	}

	/** Everything the HUD editor's undo needs about the position (settings are kept by the editor). */
	public double[] layoutState() {
		return new double[]{ax, ay, ox, oy, manualAnchor ? 1 : 0, needsDefaultPlacement ? 1 : 0};
	}

	public void restoreLayout(double[] st, String group) {
		ax = (float) st[0];
		ay = (float) st[1];
		ox = st[2];
		oy = st[3];
		manualAnchor = st[4] != 0;
		needsDefaultPlacement = st[5] != 0;
		legacyFx = -1;
		this.group = group;
	}

	public int lastX() {
		return lastX;
	}

	public int lastY() {
		return lastY;
	}

	public int lastWidth() {
		return lastW;
	}

	public int lastHeight() {
		return lastH;
	}

	public boolean contains(double mx, double my) {
		return mx >= lastX && mx < lastX + lastW && my >= lastY && my < lastY + lastH;
	}

	@Override
	public JsonObject save() {
		JsonObject o = super.save();
		JsonObject pos = new JsonObject();
		pos.addProperty("v", 2);
		pos.addProperty("ax", ax);
		pos.addProperty("ay", ay);
		pos.addProperty("ox", ox);
		pos.addProperty("oy", oy);
		pos.addProperty("manual", manualAnchor);
		if (group != null) pos.addProperty("group", group);
		o.add("position", pos);
		return o;
	}

	@Override
	public void load(JsonObject o) {
		super.load(o);
		if (o.has("position") && o.get("position").isJsonObject()) {
			JsonObject pos = o.getAsJsonObject("position");
			try {
				if (pos.has("v")) {
					ax = pos.get("ax").getAsFloat();
					ay = pos.get("ay").getAsFloat();
					ox = pos.get("ox").getAsDouble();
					oy = pos.get("oy").getAsDouble();
					manualAnchor = pos.has("manual") && pos.get("manual").getAsBoolean();
					group = pos.has("group") ? pos.get("group").getAsString() : null;
					legacyFx = -1;
				} else {
					// before the anchored layout: fractions of the screen, converted on the first render
					legacyFx = pos.get("x").getAsDouble();
					legacyFy = pos.get("y").getAsDouble();
					legacyAx = pos.get("anchorX").getAsString();
					legacyAy = pos.get("anchorY").getAsString();
					group = null;
					manualAnchor = false;
				}
				needsDefaultPlacement = false;
			} catch (RuntimeException e) {
				resetPosition();
			}
		}
	}
}
