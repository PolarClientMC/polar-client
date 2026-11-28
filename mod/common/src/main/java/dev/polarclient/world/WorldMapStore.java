package dev.polarclient.world;

import dev.polarclient.Log;
import dev.polarclient.platform.Platform;
import net.fabricmc.loader.api.FabricLoader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What the world map knows: the map colours of every chunk you have been near, kept per world and dimension and saved
 * under config/polarclient-maps. Only chunks the game had loaded around you are recorded, so places you haven't been
 * stay dark (fair on servers).
 */
public final class WorldMapStore {
	private WorldMapStore() {
	}

	/** One chunk: 16 x 16 shaded colours (0 = nothing) and heights. Never changed once stored (a re-read makes a new one). */
	public static final class Tile {
		public final int[] argb = new int[256];
		public final short[] height = new short[256];
		long readAt;
	}

	private static final int READS_PER_TICK = 4, REFRESHES_PER_TICK = 1, RADIUS = 8, MAX_TILES = 24000;
	private static final long REFRESH_MS = 15_000, SAVE_MS = 120_000;

	private static final Map<String, Map<Long, Tile>> MAPS = new HashMap<String, Map<Long, Tile>>();
	private static final Map<String, Boolean> DIRTY = new HashMap<String, Boolean>();
	private static String world;
	private static long savedAt = System.currentTimeMillis();
	private static int[] rgb = new int[256], height = new int[256], water = new int[256];
	private static volatile int revision;

	public static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	/** Goes up whenever a tile changes, so the map knows to redraw. */
	public static int revision() {
		return revision;
	}

	/** The tiles of a world's dimension, loading them from disk the first time. Never null. */
	public static Map<Long, Tile> of(String worldId, String dimension) {
		final String dim = dimKey(dimension);
		String k = worldId + "|" + dim;
		Map<Long, Tile> m = MAPS.get(k);
		if (m == null) {
			final Map<Long, Tile> fresh = new ConcurrentHashMap<Long, Tile>();
			m = fresh;
			MAPS.put(k, m);
			// read the saved map off the game thread; what's been seen since joining wins
			DISK.execute(() -> {
				Map<Long, Tile> saved = load(worldId, dim);
				for (Map.Entry<Long, Tile> e : saved.entrySet()) fresh.putIfAbsent(e.getKey(), e.getValue());
				revision++;
			});
		}
		return m;
	}

	/** Reading and writing map files happens here, never on the game thread (a big map took a moment to save). */
	private static final ExecutorService DISK = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Polar world map");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	/**
	 * One name per dimension, however it's spelled: the game says "overworld", the map screen asks for
	 * "minecraft:overworld". Mixing the two left the map empty (recorded under one name, looked up under the other).
	 */
	static String dimKey(String dimension) {
		if (dimension == null) return "";
		return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
	}

	/** Whether anything was ever recorded there. */
	public static boolean explored(String worldId, String dimension) {
		return !of(worldId, dimension).isEmpty();
	}

	public static void tick(Platform p) {
		String w = p.inWorld() && p.hasPlayer() ? p.worldId() : null;
		if (w == null || !w.equals(world)) {
			saveAll();
			if (w == null || !w.equals(world)) {
				MAPS.clear();
				DIRTY.clear();
			}
			world = w;
		}
		if (w == null) return;
		String dim = p.dimension();
		if (dim == null || dim.isEmpty()) return;
		dim = dimKey(dim);
		Map<Long, Tile> tiles = of(w, dim);
		int scanY = p.dimensionHasCeiling() ? (int) Math.floor(p.playerY()) + 1 : Integer.MIN_VALUE;
		int pcx = (int) Math.floor(p.playerX()) >> 4, pcz = (int) Math.floor(p.playerZ()) >> 4;
		long now = System.currentTimeMillis();
		int reads = 0, refreshes = 0;
		boolean changed = false;
		outer:
		for (int ring = 0; ring <= RADIUS; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
					if (reads >= READS_PER_TICK && refreshes >= REFRESHES_PER_TICK) break outer;
					int cx = pcx + dx, cz = pcz + dz;
					long k = key(cx, cz);
					Tile t = tiles.get(k);
					boolean missing = t == null;
					if (missing ? reads >= READS_PER_TICK : now - t.readAt < REFRESH_MS || refreshes >= REFRESHES_PER_TICK) continue;
					if (!p.mapChunk(cx, cz, scanY, rgb, height, water)) continue;
					Tile fresh = new Tile();
					shade(fresh);
					fresh.readAt = now;
					tiles.put(k, fresh);
					if (missing) reads++;
					else refreshes++;
					changed = true;
				}
			}
		}
		if (changed) {
			revision++;
			DIRTY.put(w + "|" + dim, Boolean.TRUE);
			if (tiles.size() > MAX_TILES) prune(tiles, pcx, pcz);
		}
		if (now - savedAt > SAVE_MS) saveAll();
	}

	/** Shades like a vanilla map: lighter where the ground rises to the north, darker where it falls; deep water darker. */
	private static void shade(Tile t) {
		for (int i = 0; i < 256; i++) {
			int c = rgb[i];
			t.height[i] = (short) height[i];
			if (c == 0) {
				t.argb[i] = 0;
				continue;
			}
			int bx = i & 15, bz = i >> 4, checker = (bx + bz) & 1;
			double d;
			int light;
			if (water[i] > 0) {
				d = water[i] * 0.1 + checker * 0.2;
				light = d < 0.5 ? 255 : d > 0.9 ? 180 : 220;
			} else {
				int north = bz > 0 ? height[i - 16] : height[i];
				d = (height[i] - north) * 0.8 + (checker - 0.5) * 0.4;
				light = d > 0.6 ? 255 : d < -0.6 ? 180 : 220;
			}
			int r = (c >> 16 & 255) * light / 255, g = (c >> 8 & 255) * light / 255, b = (c & 255) * light / 255;
			t.argb[i] = 0xFF000000 | r << 16 | g << 8 | b;
		}
	}

	private static void prune(Map<Long, Tile> tiles, int pcx, int pcz) {
		// drop the farthest quarter
		long[] keys = new long[tiles.size()];
		double[] d = new double[keys.length];
		int n = 0;
		for (long k : tiles.keySet()) {
			int cx = (int) (k >> 32), cz = (int) k;
			keys[n] = k;
			d[n++] = Math.hypot(cx - pcx, cz - pcz);
		}
		double[] sorted = d.clone();
		java.util.Arrays.sort(sorted);
		double cut = sorted[(int) (sorted.length * 0.75)];
		for (int i = 0; i < n; i++) if (d[i] > cut) tiles.remove(keys[i]);
	}

	// ------------------------------------------------------------------ disk

	private static Path file(String worldId, String dimension) {
		String safe = (worldId + "_" + dimension).replaceAll("[^A-Za-z0-9._-]", "_");
		return FabricLoader.getInstance().getConfigDir().resolve("polarclient-maps").resolve(safe + ".map");
	}

	private static Map<Long, Tile> load(String worldId, String dimension) {
		Map<Long, Tile> m = new HashMap<Long, Tile>();
		Path f = file(worldId, dimension);
		if (!Files.exists(f)) return m;
		try (InputStream raw = Files.newInputStream(f); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw, 1 << 16)))) {
			if (in.readInt() != 1) return m;
			int n = in.readInt();
			for (int i = 0; i < n && i < MAX_TILES; i++) {
				long k = in.readLong();
				Tile t = new Tile();
				for (int j = 0; j < 256; j++) t.argb[j] = in.readInt();
				for (int j = 0; j < 256; j++) t.height[j] = in.readShort();
				m.put(k, t);
			}
		} catch (Exception e) {
			Log.error("Couldn't read the world map " + f.getFileName(), e);
		}
		return m;
	}

	public static void saveAll() {
		savedAt = System.currentTimeMillis();
		for (Iterator<Map.Entry<String, Boolean>> it = DIRTY.entrySet().iterator(); it.hasNext(); ) {
			final String k = it.next().getKey();
			it.remove();
			final Map<Long, Tile> tiles = MAPS.get(k);
			int bar = k.lastIndexOf('|');
			if (tiles == null || bar < 0) continue;
			final Path f = file(k.substring(0, bar), k.substring(bar + 1));
			DISK.execute(() -> write(f, tiles));
		}
	}

	private static void write(Path f, Map<Long, Tile> tiles) {
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			// tiles never change once stored, so this can read them while the game keeps adding new ones
			java.util.List<Map.Entry<Long, Tile>> list = new java.util.ArrayList<Map.Entry<Long, Tile>>(tiles.entrySet());
			try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(raw, 1 << 16)))) {
				out.writeInt(1);
				out.writeInt(list.size());
				for (Map.Entry<Long, Tile> e : list) {
					out.writeLong(e.getKey());
					for (int c : e.getValue().argb) out.writeInt(c);
					for (short h : e.getValue().height) out.writeShort(h);
				}
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			Log.error("Couldn't save the world map", e);
		}
	}

}
