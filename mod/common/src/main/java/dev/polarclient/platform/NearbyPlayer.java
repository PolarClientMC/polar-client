package dev.polarclient.platform;

import java.util.UUID;

/** Another player the game is showing near you (never you, an invisible player or a spectator). */
public final class NearbyPlayer {
	public final UUID id;
	public final String name;
	public final double x, y, z;
	public final boolean sneaking;

	public NearbyPlayer(UUID id, String name, double x, double y, double z, boolean sneaking) {
		this.id = id;
		this.name = name == null ? "" : name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.sneaking = sneaking;
	}
}
