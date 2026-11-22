package dev.polarclient.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.polarclient.cosmetics.RenderBatch;
import dev.polarclient.cosmetics.PolarTags;
import dev.polarclient.cosmetics.WingPose;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.UUID;

/** Draws Polar cosmetics and the Polar badge. */
public final class CosmeticsRender {
		private static final ResourceLocation BADGE_FONT = ResourceLocation.fromNamespaceAndPath("polarclient", "badge");
	private static final int FULL_BRIGHT = 0xF000F0;

	private CosmeticsRender() {
	}

	public static UUID uuid(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Entity e = mc.level.getEntity(entityId);
		return e instanceof Player ? e.getUUID() : null;
	}

	public static WingPose pose(int entityId, float moving) {
		WingPose p = WingPose.walking(moving);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return p;
		Entity e = mc.level.getEntity(entityId);
		if (!(e instanceof Player)) return p;
		Player pl = (Player) e;
		p.sprinting = pl.isSprinting();
		p.sneaking = pl.isCrouching();
		p.onGround = pl.onGround();
		p.flying = pl.getAbilities().flying;
		p.gliding = pl.isFallFlying();
		p.swimming = pl.isSwimming();
		p.riding = pl.isPassenger();
		p.sleeping = pl.isSleeping();
		p.elytra = pl.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
		p.bodyYaw = pl.yBodyRot;
		Entity cam = mc.getCameraEntity();
		p.distanceSq = cam == null || cam == pl ? 0 : cam.distanceToSqr(pl);
		return p;
	}

	private static RenderType type(RenderBatch b) {
		ResourceLocation t = (ResourceLocation) b.texture;
		switch (b.layer) {
			case RenderBatch.CUTOUT:
				return RenderType.entityCutout(t);
			case RenderBatch.TRANSLUCENT:
				return RenderType.itemEntityTranslucentCull(t);
			case RenderBatch.GLOW:
				return RenderType.eyes(t);
			default:
				return RenderType.entityTranslucent(t);
		}
	}

	public static void submit(PoseStack poseStack, MultiBufferSource buffer, RenderBatch b, int light) {
		if (!(b.texture instanceof ResourceLocation) || b.vertices() == 0) return;
		int l = b.layer == RenderBatch.GLOW ? FULL_BRIGHT : light;
		VertexConsumer vc = buffer.getBuffer(type(b));
		PoseStack.Pose pose = poseStack.last();
		for (int i = 0; i < b.vertices(); i++) {
			vc.addVertex(pose, b.x(i), b.y(i), b.z(i)).setColor(b.color(i)).setUv(b.u(i), b.v(i))
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(l).setNormal(pose, b.nx(i), b.ny(i), b.nz(i));
		}
	}

	public static Component badged(Component name) {
		Component badge = Component.literal("\uE000").withStyle(s -> s.withFont(BADGE_FONT).withColor(ChatFormatting.WHITE).withBold(false).withItalic(false));
		return Component.empty().append(badge).append(" ").append(name);
	}

	/** Append code-only Polar name tags on the right side of the name. */
	public static Component tagged(Component name, UUID uuid) {
		List<String> tags = dev.polarclient.cosmetics.Cosmetics.tags(uuid);
		if (tags.isEmpty()) return name;
		Component out = name.copy();
		for (String id : tags) {
			PolarTags.Def def = PolarTags.get(id);
			if (def == null) continue;
			out = out.copy().append(Component.literal(" " + def.icon).withStyle(s -> s.withColor(def.color).withBold(false).withItalic(false)));
		}
		return out;
	}
}
