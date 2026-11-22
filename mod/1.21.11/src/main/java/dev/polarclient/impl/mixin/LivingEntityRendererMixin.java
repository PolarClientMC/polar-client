package dev.polarclient.impl.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.polarclient.cosmetics.Cosmetics;
import dev.polarclient.cosmetics.RenderBatch;
import dev.polarclient.impl.CosmeticsRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Polar auras: drawn where the player stands, before the player's own turning is applied. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"))
	private void polar$aura(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (!(state instanceof AvatarRenderState avatar) || state.isInvisible) return;
		UUID id = CosmeticsRender.uuid(avatar.id);
		if (id == null) return;
		for (RenderBatch b : Cosmetics.aura(id, state.x, state.y, state.z, net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().yRot(), net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().xRot())) CosmeticsRender.submit(poseStack, collector, b, state.lightCoords);
	}
	/** Your own name above your head in third person (F5), like everyone else sees it (not with F1's hidden HUD).
	 *  Other players' names are left to the game and the server: never show a name the server hides. */
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void polar$ownName(LivingEntity entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) return;
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player && !mc.options.hideGui && !mc.options.getCameraType().isFirstPerson()) cir.setReturnValue(true);
	}
}
