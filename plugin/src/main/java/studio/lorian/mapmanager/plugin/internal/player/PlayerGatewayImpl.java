package studio.lorian.mapmanager.plugin.internal.player;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.service.PlayerGateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Paper 玩家查询实现；Mojang 查询仅在异步线程执行。 */
public final class PlayerGatewayImpl implements PlayerGateway {
    private final JavaPlugin plugin;

    public PlayerGatewayImpl(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @Override
    public CompletableFuture<UUID> resolveId(String playerName) {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{3,16}")) {
            return CompletableFuture.completedFuture(null);
        }
        if (Bukkit.isPrimaryThread()) return resolveOnMainThread(playerName);
        CompletableFuture<UUID> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> resolveOnMainThread(playerName)
                    .whenComplete((id, error) -> {
                        if (error == null) result.complete(id);
                        else result.completeExceptionally(error);
                    }));
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    private CompletableFuture<UUID> resolveOnMainThread(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return CompletableFuture.completedFuture(online.getUniqueId());

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerName);
        if (cached != null) return CompletableFuture.completedFuture(cached.getUniqueId());

        PlayerProfile profile = Bukkit.createProfile(playerName);
        profile.completeFromCache();
        if (profile.getId() != null) return CompletableFuture.completedFuture(profile.getId());

        if (!readOnlineMode(Path.of("").toAbsolutePath().normalize())) {
            UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(offlineId);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return profile.complete(false, true) ? profile.getId() : null;
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("查询 Mojang UUID 失败: " + ex.getMessage());
                return null;
            }
        });
    }

    private boolean readOnlineMode(Path serverDirectory) {
        Path paperGlobal = serverDirectory.resolve("config/paper-global.yml");
        if (Files.isRegularFile(paperGlobal)) {
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.load(paperGlobal.toFile());
                if (config.getBoolean("proxies.velocity.enabled")
                        && config.isBoolean("proxies.velocity.online-mode")) {
                    return config.getBoolean("proxies.velocity.online-mode");
                }
                if (plugin.getServer().spigot().getSpigotConfig().getBoolean("settings.bungeecord")
                        && config.isBoolean("proxies.bungee-cord.online-mode")) {
                    return config.getBoolean("proxies.bungee-cord.online-mode");
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("读取 paper-global.yml 失败，改用 server.properties: " + ex.getMessage());
            }
        }
        Path propertiesFile = serverDirectory.resolve("server.properties");
        if (Files.isRegularFile(propertiesFile)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
                String value = properties.getProperty("online-mode");
                if ("true".equalsIgnoreCase(value)) return true;
                if ("false".equalsIgnoreCase(value)) return false;
            } catch (IOException ex) {
                plugin.getLogger().warning("读取 server.properties 失败，改用服务器运行配置: " + ex.getMessage());
            }
        }
        return plugin.getServer().getOnlineMode();
    }

    @Override public boolean isOnline(UUID playerId) { return Bukkit.getPlayer(playerId) != null; }
    @Override public String currentWorld(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? null : player.getWorld().getName();
    }
    @Override public boolean teleport(UUID playerId, SpawnPoint point) {
        if (!Bukkit.isPrimaryThread() || playerId == null || point == null
                || !Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())
                || !Float.isFinite(point.yaw()) || !Float.isFinite(point.pitch())) return false;
        Player player = Bukkit.getPlayer(playerId);
        org.bukkit.World world = Bukkit.getWorld(point.worldName());
        if (player == null || world == null) return false;
        return player.teleport(new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
    }
    @Override public boolean disconnect(UUID playerId, String reason) {
        // TODO: 安全落点策略完成时实现。
        return false;
    }
}
