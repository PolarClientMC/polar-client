package dev.polarclient.impl;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** polarclient:presence, an empty message that marks a player as using Polar Client to servers that listen for it. */
public record PolarPresencePayload() implements CustomPacketPayload {
	public static final Type<PolarPresencePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("polarclient", "presence"));
	public static final StreamCodec<FriendlyByteBuf, PolarPresencePayload> CODEC = StreamCodec.unit(new PolarPresencePayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
