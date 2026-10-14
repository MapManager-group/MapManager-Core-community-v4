package studio.lorian.mapmanager.plugin;

import java.util.function.Predicate;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
import studio.lorian.mapmanager.plugin.internal.command.MapAdminCommand;
import studio.lorian.mapmanager.plugin.internal.listener.MapAccessListener;
import studio.lorian.mapmanager.plugin.internal.listener.MapSettingsListener;
import studio.lorian.mapmanager.plugin.internal.migration.V3Migration;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifierImpl;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import studio.lorian.mapmanager.plugin.internal.player.PlayerGatewayImpl;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjectionImpl;
import studio.lorian.mapmanager.plugin.internal.permission.QueuedWorldBaseProjection;
import studio.lorian.mapmanager.plugin.internal.storage.StorageBackendMigration;
import studio.lorian.mapmanager.plugin.internal.storage.JsonStorage;
import studio.lorian.mapmanager.plugin.internal.storage.MySqlStorage;
import studio.lorian.mapmanager.plugin.internal.storage.StorageConfig;
import studio.lorian.mapmanager.plugin.internal.world.FlatChunkGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Paper 插件入口；加载配置和选定的持久化后端。 */
public final class MapManagerPlugin extends JavaPlugin {
    private MapManagerImpl api;
    private QueuedWorldBaseProjection projection;

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
            boolean configChanged;
            try { configChanged = completeConfig(); }
            catch (Exception error) {
                try { Messages.load(this); } catch (Exception ignored) { }
                throw error;
            }
            Messages.load(this);
            if (configChanged) {
                reloadConfig();
                Messages.load(this);
            }
            StorageConfig config = StorageConfig.load(getConfig());
            Pattern deletionPattern = Pattern.compile(getConfig().getString(
                    "deletion.allowed-world-name-regex", ".*_bak$"));
            Pattern blockedCreationPattern = Pattern.compile(getConfig().getString(
                    "creation.blocked-world-name-regex", ".*_bak$"));
            Path directory = getDataFolder().toPath();
            Files.createDirectories(directory);
            String selected = StorageBackendMigration.identity(config);
            var repository = StorageBackendMigration.openAndMigrate(getConfig(), directory);
            LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
            if (luckPerms == null) throw new IllegalStateException(Messages.text("startup.luckperms_unavailable"));
            var defaultWorldName = new java.util.concurrent.atomic.AtomicReference<String>(
                    getServer().getWorlds().stream().findFirst().map(org.bukkit.World::getName).orElse(null));
            Predicate<String> defaultWorld = name -> name != null && name.equalsIgnoreCase(defaultWorldName.get());
            projection = new QueuedWorldBaseProjection(new WorldBaseProjectionImpl(luckPerms, defaultWorld),
                    directory.resolve("permission-sync.json"), getLogger());
            var players = new PlayerGatewayImpl(this);
            var notifier = new OperationNotifierImpl();
            var worldVersions = new WorldVersionServiceImpl(getServer().getWorldContainer().toPath());
            api = new MapManagerImpl(repository, repository, repository, projection, players,
                    blockedCreationPattern, getLogger(), worldVersions, directory, defaultWorld);
            MapAccessListener accessListener = new MapAccessListener(this, api.maps(), api.access(),
                    getConfig().getBoolean("access-control.strict-map-entry", true), defaultWorld);
            getServer().getPluginManager().registerEvents(accessListener, this);
            MapSettingsListener settingsListener = new MapSettingsListener(repository, defaultWorld);
            getServer().getPluginManager().registerEvents(settingsListener, this);
            settingsListener.registerEntityPhysics(this);
            getLogger().info(Messages.text("startup.storage_loaded", "backend", selected));
            Path oldDirectory = getDataFolder().getParentFile().toPath().resolve("MapManager-Core");
            var migrator = new V3Migration(oldDirectory, directory, repository, repository, players, luckPerms);
            Runnable refresh = () -> {
                ((studio.lorian.mapmanager.plugin.impl.WorldLifeCycleServiceImpl) api.worldLifeCycle()).recoverAll();
                var history = projection.knownMembers();
                var groups = new java.util.HashSet<>(history.keySet());
                repository.allGroups().forEach(group -> groups.add(group.id()));
                for (String groupId : groups) {
                    var group = repository.findGroup(groupId);
                    var members = new java.util.HashSet<>(repository.members(groupId, studio.lorian.mapmanager.api.model.MapRole.ADMIN));
                    members.addAll(repository.members(groupId, studio.lorian.mapmanager.api.model.MapRole.BUILDER));
                    if (group != null) projection.ensureGroupInheritance(groupId, group.worldNames());
                    var tracked = new java.util.HashSet<>(history.getOrDefault(groupId, java.util.Set.of()));
                    tracked.addAll(members);
                    for (var member : tracked) projection.setBuilderAccess(groupId, member, group != null && members.contains(member));
                    if (group == null) projection.removeGroup(groupId);
                }
                accessListener.checkOnlinePlayers();
            };
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
                    var fallback = org.mvplugins.multiverse.core.MultiverseCoreApi.get().getWorldManager().getDefaultWorld().getOrNull();
                    if (fallback != null) defaultWorldName.set(fallback.getName());
                    migrator.migrate(getConfig().getBoolean("migration.dry-run", false)).whenComplete((message, error) -> {
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
                    try {
                    getLogger().info(migration.join());
                    refresh.run();
                    Bukkit.getCommandMap().register("mapmanager", new MapWorldCommand(this,
                            api.maps(), api.roles(), api.access(), api.settings(), api.travel(), players,
                            notifier, accessListener));
                    Bukkit.getCommandMap().register("mapmanager", new MapWorldCommand("wtp", this,
                            api.maps(), api.roles(), api.access(), api.settings(), api.travel(), players,
                            notifier, accessListener));
                    Bukkit.getServicesManager().register(MapManager.class, api, this, ServicePriority.Normal);
                    Bukkit.getCommandMap().register("mapmanager", new MapAdminCommand(this, repository,
                            api.worldLifeCycle(), migrator, notifier, refresh, players, deletionPattern));
                    } catch (RuntimeException exception) {
                        getLogger().severe(Messages.text("startup.failed", "error", exception.getMessage()));
                        getServer().getPluginManager().disablePlugin(this);
                    }
                });
            });
        } catch (Exception ex) {
            getLogger().severe(Messages.text("startup.failed", "error", ex.getMessage()));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private boolean completeConfig() throws IOException, InvalidConfigurationException {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        String contents = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        boolean changed = contents.startsWith("!MapManagerConfig");
        if (changed) {
            int endOfTag = contents.indexOf('\n');
            contents = endOfTag < 0 ? "" : contents.substring(endOfTag + 1);
        }
        config.loadFromString(contents);
        if (Files.exists(getDataFolder().toPath().resolve("v3-migration.done"))
                && config.contains("global", true)) {
            config.set("global", null);
            changed = true;
        }

        try (var resource = getResource("config.yml")) {
            if (resource == null) throw new IOException("Bundled config.yml is missing");
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            defaults.load(new InputStreamReader(resource, StandardCharsets.UTF_8));

            for (String path : defaults.getKeys(true)) {
                boolean section = defaults.isConfigurationSection(path);
                if (config.contains(path, true)) {
                    if (config.isConfigurationSection(path) != section)
                        throw new IllegalArgumentException("Configuration path has an incompatible type: " + path);
                    continue;
                }
                if (section) config.createSection(path);
                else config.set(path, defaults.get(path));
                config.setComments(path, defaults.getComments(path));
                config.setInlineComments(path, defaults.getInlineComments(path));
                changed = true;
            }
            if (changed) config.save(configFile);
            return changed;
        }
    }

    @Override
    public void onDisable() {
        if (api != null) Bukkit.getServicesManager().unregister(MapManager.class, api);
        if (projection != null) projection.close();
        projection = null;
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
