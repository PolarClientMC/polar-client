package dev.polarclient.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rewards players who play with Polar Client (/polarclient).
 *
 * A client's brand and plugin channels are whatever the client says they are, so they prove nothing. Instead the
 * plugin asks the Polar server, which only says yes for a player who is signed in to Polar Client, connected, and in
 * game on a server right now (and on this server, when server-address is set).
 */
public final class PolarPlugin extends JavaPlugin {
	private static final Pattern PLAYING = Pattern.compile("\"playing\"\\s*:\\s*true");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	/** Players whose check is still running, so a quick second /polarclient can't claim twice. */
	private final Set<UUID> checking = new HashSet<>();
	private File claimsFile;
	private YamlConfiguration claims;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadClaims();
		getLogger().info("PolarClient plugin enabled.");
	}

	@Override
	public void onDisable() {
		saveClaims();
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.getName().equalsIgnoreCase("polarclient")) return false;
		if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("polarclient.admin")) {
			reloadConfig();
			sender.sendMessage(color("&b&lPolar Client &7> &aConfig reloaded."));
			return true;
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage(color("&cOnly players can use this command."));
			return true;
		}
		UUID id = player.getUniqueId();
		boolean oneTime = getConfig().getBoolean("one-time", true);
		if (oneTime && hasClaimed(id)) {
			player.sendMessage(message("already-claimed-message", "&eYou already claimed this reward."));
			return true;
		}
		if (!checking.add(id)) return true;
		player.sendMessage(message("checking-message", "&7Checking with Polar..."));
		String name = player.getName();
		http.sendAsync(request(id, name), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) ->
			Bukkit.getScheduler().runTask(this, () -> {
				checking.remove(id);
				if (!player.isOnline()) return;
				if (err != null || res.statusCode() != 200) {
					if (err != null) getLogger().warning("Couldn't reach Polar: " + err.getMessage());
					else getLogger().warning("Polar answered " + res.statusCode());
					player.sendMessage(message("unavailable-message", "&cCouldn't reach Polar right now. Try again in a minute."));
					return;
				}
				if (!PLAYING.matcher(res.body()).find()) {
					player.sendMessage(message("not-using-message", "&cPlay with Polar Client to claim this. Get it at &fhttps://discord.gg/3HxaywjbT4"));
					return;
				}
				if (oneTime && hasClaimed(id)) {
					player.sendMessage(message("already-claimed-message", "&eYou already claimed this reward."));
					return;
				}
				if (oneTime) {
					markClaimed(id);
					saveClaims();
				}
				giveRewards(player);
				getLogger().info(name + " claimed the Polar Client reward.");
				player.sendMessage(message("claimed-message", "&aYou claimed &e1M coins&a!"));
			}));
		return true;
	}

	/** GET {polar-api}/v1/playing?uuid=..&name=..&host=.. */
	private HttpRequest request(UUID id, String name) {
		String api = getConfig().getString("polar-api", "https://polar-client.polarclientminecraft.workers.dev").replaceAll("/+$", "");
		String host = getConfig().getString("server-address", "").trim();
		String query = "uuid=" + enc(id.toString()) + "&name=" + enc(name) + (host.isEmpty() ? "" : "&host=" + enc(host));
		return HttpRequest.newBuilder(URI.create(api + "/v1/playing?" + query))
			.timeout(Duration.ofSeconds(10))
			.header("Accept", "application/json")
			.header("User-Agent", "PolarClient-Plugin/" + getPluginMeta().getVersion())
			.GET()
			.build();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private void giveRewards(Player player) {
		for (String cmd : getConfig().getStringList("rewards")) {
			if (cmd == null || cmd.isBlank()) continue;
			Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
		}
	}

	private Component message(String key, String fallback) {
		return color(getConfig().getString(key, fallback));
	}

	private static Component color(String s) {
		return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
	}

	// ------------------------------------------------------------ who claimed (claims.yml)

	private void loadClaims() {
		if (!getDataFolder().exists()) getDataFolder().mkdirs();
		claimsFile = new File(getDataFolder(), "claims.yml");
		if (!claimsFile.exists()) {
			try {
				claimsFile.createNewFile();
			} catch (IOException e) {
				getLogger().warning(e.getMessage());
			}
		}
		claims = YamlConfiguration.loadConfiguration(claimsFile);
	}

	private boolean hasClaimed(UUID id) {
		return claims.getStringList("claimed").contains(id.toString());
	}

	private void markClaimed(UUID id) {
		List<String> list = new ArrayList<>(claims.getStringList("claimed"));
		if (!list.contains(id.toString())) list.add(id.toString());
		claims.set("claimed", list);
	}

	private void saveClaims() {
		if (claims == null || claimsFile == null) return;
		try {
			claims.save(claimsFile);
		} catch (IOException e) {
			getLogger().warning("Couldn't save claims.yml: " + e.getMessage());
		}
	}
}
