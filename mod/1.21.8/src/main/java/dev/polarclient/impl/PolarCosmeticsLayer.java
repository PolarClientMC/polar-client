package dev.polarclient.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.polarclient.cosmetics.Cosmetics;
import dev.polarclient.cosmetics.RenderBatch;
import dev.polarclient.cosmetics.WingPose;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;

import java.util.List;
import java.util.UUID;

public final class PolarCosmeticsLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
	public PolarCosmeticsLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource buffer, int light, PlayerRenderState state, float yRot, float xRot) {
		if (state.isInvisible) return;
		UUID id = CosmeticsRender.uuid(state.id);
		if (id == null) return;
		WingPose pose = CosmeticsRender.pose(state.id, Math.min(1f, state.walkAnimationSpeed));
		for (int i = 0; i < 2; i++) {
			boolean head = i == 1;
			List<RenderBatch> batches = Cosmetics.models(id, head, pose);
			if (batches.isEmpty()) continue;
			poseStack.pushPose();
			PlayerModel model = this.getParentModel();
			(head ? model.head : model.body).translateAndRotate(poseStack);
			for (RenderBatch b : batches) CosmeticsRender.submit(poseStack, buffer, b, light);
			poseStack.popPose();
		}
	}
}
