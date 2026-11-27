package dev.polarclient.impl.mixin;

import dev.polarclient.gui.ScreenSkins;
import dev.polarclient.impl.McGfx;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Polar's look for the death, connecting and disconnected screens: draws them instead of vanilla (the buttons stay
 * vanilla buttons, in vanilla places). Screen itself is a target for screens that don't draw anything of their own;
 * the others are targets because they do. Nothing here is required, so a version that moved things only loses the look.
 */
@Mixin({Screen.class, DeathScreen.class, ConnectScreen.class, DisconnectedScreen.class})
public abstract class ScreenSkinMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void polar$skin(PoseStack graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!ScreenSkins.wants(this)) return;
		Screen s = (Screen) (Object) this;
		if (ScreenSkins.render(McGfx.of(graphics), s, s.width, s.height, polar$buttons(s), mouseX, mouseY)) ci.cancel();
	}

	@Unique
	private static List<ScreenSkins.Widget> polar$buttons(Screen s) {
		List<ScreenSkins.Widget> out = new ArrayList<ScreenSkins.Widget>();
		for (GuiEventListener l : s.children()) {
			if (!(l instanceof AbstractButton)) continue;
			AbstractButton b = (AbstractButton) l;
			if (!b.visible) continue;
			out.add(new ScreenSkins.Widget(b.x, b.y, b.getWidth(), b.getHeight(), b.getMessage().getString(), b.active));
		}
		return out;
	}
}
