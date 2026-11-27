package dev.polarclient.gui;

/**
 * Anchor points of the baked Christmas sprites (textures/gui/christmas). Generated from the art bake;
 * coordinates are in each sprite's own drawing units.
 */
final class ChristmasArt {
	private ChristmasArt() {
	}

	static final float TREE_W = 520f, TREE_H = 760f;
	static final float[] TREE_STAR = {260f, 60.99f}, TREE_BASE = {260f, 721.8f};
	/** Lights on the tree's visible side: x, y, colour index. */
	static final float[][] TREE_BULBS = {
		{319.5f, 623.81f, 0f},
		{254.64f, 625.97f, 1f},
		{182.55f, 604.34f, 2f},
		{132.09f, 597.18f, 3f},
		{121.27f, 569.1f, 0f},
		{413.17f, 511.9f, 1f},
		{364.53f, 504.95f, 2f},
		{324.9f, 510.68f, 3f},
		{269.04f, 495.13f, 0f},
		{213.18f, 494.77f, 1f},
		{175.34f, 489.41f, 2f},
		{155.5f, 463.84f, 3f},
		{148.29f, 455.24f, 0f},
		{375.32f, 400.2f, 0f},
		{348.31f, 392.52f, 1f},
		{308.67f, 398.25f, 2f},
		{265.43f, 384.5f, 3f},
		{231.19f, 383.42f, 0f},
		{205.96f, 379.85f, 1f},
		{189.74f, 358.57f, 2f},
		{182.52f, 349.98f, 3f},
		{350.09f, 290.28f, 3f},
		{332.08f, 280.1f, 0f},
		{314.07f, 285.12f, 1f},
		{270.83f, 271.36f, 2f},
		{249.21f, 272.08f, 3f},
		{223.98f, 253.31f, 0f},
		{220.37f, 249.01f, 1f},
		{195.14f, 245.43f, 2f},
		{213.15f, 225.23f, 3f},
		{315.86f, 182.87f, 2f},
		{306.85f, 170.19f, 3f},
		{288.84f, 175.2f, 0f},
		{276.22f, 158.22f, 1f},
		{254.6f, 158.94f, 2f},
		{241.99f, 141.96f, 3f}
	};
	static final float PRESENTS_W = 560f, PRESENTS_H = 300f;
	static final float[] PRESENTS_BASE = {280f, 244.09f};
	static final float SNOWMAN_W = 300f, SNOWMAN_H = 440f;
	static final float[] SNOWMAN_BASE = {150f, 411.11f};
	static final float SLEIGH_W = 460f, SLEIGH_H = 220f;
	static final float[] SLEIGH_NOSE = {23.73f, 113.23f}, SLEIGH_LANTERN = {276.8f, 82.46f};
	static final float HAT_W = 220f, HAT_H = 200f;
	/** Opaque box of the hat sprite: x0, y0, x1, y1. */
	static final float[] HAT_BOX = {17f, 26f, 189f, 193f};
	/** Button sprites (glass + snow + icicles): the button's own rect starts at (OX, OY) inside the W x H sprite, in GUI px. */
	static final float BTN_OX = 7.5f, BTN_OY = 11.5f, BTN_W = 187f, BTN_H = 44f;
	static final float BOUGH_W = 220f, BOUGH_H = 26f;
	/** The bough's spine (where the baubles hang), 65 samples left to right, in GUI px inside the bough sprite. */
	static final float[][] BOUGH_SPINE = {
		{0f, 12.29f},
		{3.44f, 12.37f},
		{6.88f, 12.44f},
		{10.31f, 12.51f},
		{13.75f, 12.57f},
		{17.19f, 12.62f},
		{20.62f, 12.66f},
		{24.06f, 12.7f},
		{27.5f, 12.72f},
		{30.94f, 12.74f},
		{34.38f, 12.74f},
		{37.81f, 12.73f},
		{41.25f, 12.72f},
		{44.69f, 12.69f},
		{48.12f, 12.66f},
		{51.56f, 12.61f},
		{55f, 12.56f},
		{58.44f, 12.5f},
		{61.88f, 12.43f},
		{65.31f, 12.35f},
		{68.75f, 12.27f},
		{72.19f, 12.18f},
		{75.62f, 12.09f},
		{79.06f, 11.99f},
		{82.5f, 11.89f},
		{85.94f, 11.79f},
		{89.38f, 11.69f},
		{92.81f, 11.59f},
		{96.25f, 11.49f},
		{99.69f, 11.39f},
		{103.12f, 11.29f},
		{106.56f, 11.2f},
		{110f, 11.11f},
		{113.44f, 11.03f},
		{116.88f, 10.96f},
		{120.31f, 10.89f},
		{123.75f, 10.83f},
		{127.19f, 10.78f},
		{130.62f, 10.73f},
		{134.06f, 10.7f},
		{137.5f, 10.68f},
		{140.94f, 10.66f},
		{144.38f, 10.66f},
		{147.81f, 10.67f},
		{151.25f, 10.68f},
		{154.69f, 10.71f},
		{158.12f, 10.74f},
		{161.56f, 10.79f},
		{165f, 10.84f},
		{168.44f, 10.9f},
		{171.88f, 10.97f},
		{175.31f, 11.05f},
		{178.75f, 11.13f},
		{182.19f, 11.22f},
		{185.62f, 11.31f},
		{189.06f, 11.41f},
		{192.5f, 11.51f},
		{195.94f, 11.61f},
		{199.38f, 11.71f},
		{202.81f, 11.81f},
		{206.25f, 11.91f},
		{209.69f, 12.01f},
		{213.12f, 12.11f},
		{216.56f, 12.2f},
		{220f, 12.29f}
	};
}
