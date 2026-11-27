package dev.polarclient.impl.mixin;

import dev.polarclient.gui.ScreenSkins;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Polar's own buttons on the death and disconnected screens (Copy, Remove waypoint, Retry), as real buttons. */
@Mixin({DeathScreen.class, DisconnectedScreen.class})
public abstract class ScreenSkinInitMixin extends Screen {
	@Unique
	private final List<Button> polar$extras = new ArrayList<Button>();

	protected ScreenSkinInitMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"), require = 0)
	private void polar$init(CallbackInfo ci) {
		polar$extras.clear();
		List<ScreenSkins.Widget> own = new ArrayList<ScreenSkins.Widget>();
		for (GuiEventListener l : children()) {
			if (!(l instanceof AbstractButton)) continue;
			AbstractButton b = (AbstractButton) l;
			if (b.visible) own.add(new ScreenSkins.Widget(b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.getMessage().getString(), b.active));
		}
		for (ScreenSkins.Extra e : ScreenSkins.extras(this, width, height, own)) {
			final String id = e.id;
			Button b = Button.builder(Component.literal(e.label), b -> ScreenSkins.press(id)).bounds(e.x, e.y, e.w, e.h).build();
			polar$extras.add(addRenderableWidget(b));
		}
	}

	/** Some screens only move their buttons when the window resizes: move Polar's with them. */
	@Inject(method = "repositionElements", at = @At("TAIL"), require = 0)
	private void polar$moved(CallbackInfo ci) {
		for (Button b : polar$extras) removeWidget(b);
		polar$extras.clear();
		polar$init(null);
	}
}
