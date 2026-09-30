package studio.lorian.mapmanager.plugin;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.plugin.impl.MapManagerImpl;
import studio.lorian.mapmanager.plugin.impl.WorldVersionServiceImpl;
import studio.lorian.mapmanager.plugin.internal.command.MapWorldCommand;
import studio.lorian.mapmanager.plugin.internal.listener.MapAccessListener;
import studio.lorian.mapmanager.plugin.internal.listener.MapSettingsListener;
import studio.lorian.mapmanager.plugin.internal.migration.V3Migration;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifierImpl;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import studio.lorian.mapmanager.plugin.internal.player.PlayerGatewayImpl;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjectionImpl;
import studio.lorian.mapmanager.plugin.internal.storage.JsonStorage;
import studio.lorian.mapmanager.plugin.internal.storage.MySqlStorage;
import studio.lorian.mapmanager.plugin.internal.storage.StorageConfig;
import studio.lorian.mapmanager.plugin.internal.world.FlatChunkGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Paper 插件入口；加载配置和选定的持久化后端。 */
public final class MapManagerPlugin extends JavaPlugin {
    private MapManagerImpl api;

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (id == null || !id.startsWith("flat:")) return null;
        try {
            return new FlatChunkGenerator(id);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Invalid flat generator settings for world " + worldName + ": "
                    + exception.getMessage());
            return null;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Messages.load(this);
            StorageConfig config = StorageConfig.load(getConfig());
            Pattern deletionPattern = Pattern.compile(getConfig().getString(
                    "deletion.allowed-world-name-regex", ".*_bak$"));
            Pattern blockedCreationPattern = Pattern.compile(getConfig().getString(
                    "creation.blocked-world-name-regex", ".*_bak$"));
            Path directory = getDataFolder().toPath();
            Files.createDirectories(directory);
            Path marker = directory.resolve("storage-backend.txt");
            String selected = config.type() == StorageConfig.Type.JSON ? "JSON"
                    : "MYSQL:" + config.host() + ":" + config.port() + "/" + config.database();
            if (Files.exists(marker) && !Files.readString(marker, StandardCharsets.UTF_8).trim().equals(selected)) {
                throw new IllegalStateException(Messages.text("startup.backend_changed"));
            }
            var repository = config.type() == StorageConfig.Type.JSON
                    ? new JsonStorage(directory.resolve("maps.json")) : new MySqlStorage(config);
            if (!Files.exists(marker)) Files.writeString(marker, selected + "\n", StandardCharsets.UTF_8);
            LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
            if (luckPerms == null) throw new IllegalStateException(Messages.text("startup.luckperms_unavailable"));
            var projection = new WorldBaseProjectionImpl(luckPerms);
            var players = new PlayerGatewayImpl(this);
            var notifier = new OperationNotifierImpl();
            var worldVersions = new WorldVersionServiceImpl(getServer().getWorldContainer().toPath());
            api = new MapManagerImpl(repository, repository, repository, projection, players,
                    deletionPattern, blockedCreationPattern, getLogger(), worldVersions);
            MapAccessListener accessListener = new MapAccessListener(this, api.maps(), api.access(),
                    getConfig().getBoolean("access-control.strict-map-entry", true));
            getServer().getPluginManager().registerEvents(accessListener, this);
            getServer().getPluginManager().registerEvents(new MapSettingsListener(repository), this);
            getLogger().info(Messages.text("startup.storage_loaded", "backend", selected));
            Path oldDirectory = getDataFolder().getParentFile().toPath().resolve("MapManager-Core");
            CompletableFuture<String> migration = new CompletableFuture<>();
            CompletableFuture<Boolean> worldBase = projection.ensureWorldBase();
            migration.whenComplete((message, error) -> {
                if (error != null && isEnabled()) {
                    getServer().getScheduler().runTask(this, () -> {
                        if (!isEnabled()) return;
                        getLogger().severe(Messages.text("startup.migration_failed", "error", error.getMessage()));
                        getServer().getPluginManager().disablePlugin(this);
                    });
                }
            });
            worldBase.whenComplete((created, error) -> {
                if (error != null) {
                    getLogger().severe(Messages.text("startup.worldbase_failed", "error", error.getMessage()));
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this,
                                () -> getServer().getPluginManager().disablePlugin(this));
                    }
                } else {
                    getLogger().info(Messages.text(created ? "startup.worldbase_created" : "startup.worldbase_exists"));
                }
            });
            whenPluginEnabled("Multiverse-Core", () -> {
                try {
                    new V3Migration(oldDirectory, directory, repository, repository, players, luckPerms)
                            .migrate().whenComplete((message, error) -> {
                                if (error != null) migration.completeExceptionally(error);
                                else migration.complete(message);
                            });
                } catch (Exception exception) {
                    migration.completeExceptionally(exception);
                }
            });
            CompletableFuture.allOf(migration, worldBase).whenComplete((ignored, error) -> {
                if (error != null || !isEnabled()) return;
                getServer().getScheduler().runTask(this, () -> {
                    if (!isEnabled()) return;
                    getLogger().info(migration.join());
                    accessListener.checkOnlinePlayers();
                    Bukkit.getCommandMap().register("mapmanager", new MapWorldCommand(this,
                            api.maps(), api.roles(), api.access(), api.settings(), api.travel(), api.worldLifeCycle(), players,
                            notifier, deletionPattern, accessListener));
                    Bukkit.getCommandMap().register("mapmanager", new MapWorldCommand("wtp", this,
                            api.maps(), api.roles(), api.access(), api.settings(), api.travel(), api.worldLifeCycle(), players,
                            notifier, deletionPattern, accessListener));
                    Bukkit.getServicesManager().register(MapManager.class, api, this, ServicePriority.Normal);
                });
            });
            getLogger().warning(Messages.text("startup.incomplete"));
        } catch (Exception ex) {
            getLogger().severe(Messages.text("startup.failed", "error", ex.getMessage()));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (api != null) Bukkit.getServicesManager().unregister(MapManager.class, api);
        api = null;
    }

    private void whenPluginEnabled(String pluginName, Runnable action) {
        Plugin dependency = getServer().getPluginManager().getPlugin(pluginName);
        if (dependency != null && dependency.isEnabled()) {
            action.run();
            return;
        }
        Listener listener = new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (!event.getPlugin().getName().equalsIgnoreCase(pluginName)) return;
                HandlerList.unregisterAll(this);
                action.run();
            }
        };
        getServer().getPluginManager().registerEvents(listener, this);
    }
}
