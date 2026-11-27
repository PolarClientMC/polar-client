package dev.polarclient.impl;

import com.mojang.blaze3d.platform.InputConstants;
import dev.polarclient.gui.KeyInput;
import dev.polarclient.gui.View;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ViewScreen extends Screen {
	public final View view;

	public ViewScreen(View view) {
		super(Component.literal(view.title()));
		this.view = view;
	}

	@Override
	protected void init() {
		view.init(width, height);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float a) {
		view.renderBackground(McGfx.of(g), mouseX, mouseY);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float a) {
		view.render(McGfx.of(g), mouseX, mouseY, a);
	}

	private static int button(int glfw) {
		switch (glfw) {
			case InputConstants.MOUSE_BUTTON_LEFT: return View.LEFT;
			case InputConstants.MOUSE_BUTTON_RIGHT: return View.RIGHT;
			case InputConstants.MOUSE_BUTTON_MIDDLE: return 2;
			default: return 10 + glfw;
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return view.mouseClicked(mouseX, mouseY, button(button));
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return view.mouseReleased(mouseX, mouseY, button(button));
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
		return view.mouseDragged(mouseX, mouseY, button(button), dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return view.mouseScrolled(x, y, scrollY);
	}

	public static KeyInput key(int key, int scancode, int modifiers) {
		KeyInput.Key k;
		if (key == InputConstants.KEY_ESCAPE) k = KeyInput.Key.ESCAPE;
		else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) k = KeyInput.Key.ENTER;
		else if (key == InputConstants.KEY_BACKSPACE) k = KeyInput.Key.BACKSPACE;
		else if (key == InputConstants.KEY_DELETE) k = KeyInput.Key.DELETE;
		else if (key == InputConstants.KEY_LEFT) k = KeyInput.Key.LEFT;
		else if (key == InputConstants.KEY_RIGHT) k = KeyInput.Key.RIGHT;
		else if (key == InputConstants.KEY_UP) k = KeyInput.Key.UP;
		else if (key == InputConstants.KEY_DOWN) k = KeyInput.Key.DOWN;
		else if (key == InputConstants.KEY_HOME) k = KeyInput.Key.HOME;
		else if (key == InputConstants.KEY_END) k = KeyInput.Key.END;
		else if (key == InputConstants.KEY_TAB) k = KeyInput.Key.TAB;
		else if (key == InputConstants.KEY_F5) k = KeyInput.Key.F5;
		else if (key == InputConstants.KEY_A) k = KeyInput.Key.A;
		else if (key == InputConstants.KEY_C) k = KeyInput.Key.C;
		else if (key == InputConstants.KEY_V) k = KeyInput.Key.V;
		else if (key == InputConstants.KEY_X) k = KeyInput.Key.X;
		else if (key == InputConstants.KEY_Z) k = KeyInput.Key.Z;
		else if (key == InputConstants.KEY_Y) k = KeyInput.Key.Y;
		else if (key == InputConstants.KEY_G) k = KeyInput.Key.G;
		else k = KeyInput.Key.OTHER;
		boolean shift = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
		boolean ctrl = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0;
		return new KeyInput(k, shift, ctrl, Keys.get(dev.polarclient.platform.PolarKey.MENU).matches(key, scancode));
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (view.keyPressed(key(key, scancode, modifiers))) return true;
		if (key == InputConstants.KEY_ESCAPE && view.closeOnEscape()) {
			view.onClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		return view.charTyped(c);
	}

	@Override
	public void tick() {
		view.tick();
	}

	@Override
	public void removed() {
		view.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return view.pausesGame();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return view.closeOnEscape();
	}

	@Override
	public void onClose() {
		view.onClose();
	}
}
