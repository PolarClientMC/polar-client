package dev.polarclient.impl.mixin;

import dev.polarclient.cosmetics.Cosmetics;
import dev.polarclient.impl.PolarCosmeticsLayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void polar$addLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
		((LivingEntityRendererInvoker) this).polar$addLayer(new PolarCosmeticsLayer((PlayerRenderer) (Object) this));
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V", at = @At("TAIL"))
	private void polar$cape(AbstractClientPlayer entity, PlayerRenderState state, float partialTicks, CallbackInfo ci) {
		Object tex = Cosmetics.capeTexture(entity.getUUID());
		if (!(tex instanceof ResourceLocation id)) return;
		PlayerSkin s = state.skin;
		state.skin = new PlayerSkin(s.texture(), s.textureUrl(), id, id, s.model(), s.secure());
	}
}
