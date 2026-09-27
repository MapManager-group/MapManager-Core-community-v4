package studio.lorian.mapmanager.plugin;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.plugin.impl.MapManagerImpl;
import studio.lorian.mapmanager.plugin.internal.command.MapWorldCommand;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifierImpl;
import studio.lorian.mapmanager.plugin.internal.player.PlayerGatewayImpl;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjectionImpl;
import studio.lorian.mapmanager.plugin.internal.storage.JsonStorage;
import studio.lorian.mapmanager.plugin.internal.storage.MySqlStorage;
import studio.lorian.mapmanager.plugin.internal.storage.StorageConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Paper 插件入口；加载配置和选定的持久化后端。 */
public final class MapManagerPlugin extends JavaPlugin {
    private MapManagerImpl api;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            StorageConfig config = StorageConfig.load(getConfig());
            Path directory = getDataFolder().toPath();
            Files.createDirectories(directory);
            Path marker = directory.resolve("storage-backend.txt");
            String selected = config.type() == StorageConfig.Type.JSON ? "JSON"
                    : "MYSQL:" + config.host() + ":" + config.port() + "/" + config.database();
            if (Files.exists(marker) && !Files.readString(marker, StandardCharsets.UTF_8).trim().equals(selected)) {
                throw new IllegalStateException("存储后端已变更；请先显式迁移数据，再更改 storage.type");
            }
            var repository = config.type() == StorageConfig.Type.JSON
                    ? new JsonStorage(directory.resolve("maps.json")) : new MySqlStorage(config);
            if (!Files.exists(marker)) Files.writeString(marker, selected + "\n", StandardCharsets.UTF_8);
            LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
            if (luckPerms == null) throw new IllegalStateException("LuckPerms API 服务不可用");
            var projection = new WorldBaseProjectionImpl(luckPerms);
            var players = new PlayerGatewayImpl(this);
            var notifier = new OperationNotifierImpl();
            api = new MapManagerImpl(repository, repository, repository, projection, players, getLogger());
            Bukkit.getCommandMap().register("mapmanager", new MapWorldCommand(this,
                    api.maps(), api.roles(), api.travel(), api.worldLifeCycle(), players, notifier));
            Bukkit.getCommandMap().register("mapmanager", new MapWorldCommand("wtp", this,
                    api.maps(), api.roles(), api.travel(), api.worldLifeCycle(), players, notifier));
            getLogger().info("地图存储已加载: " + selected);
            projection.ensureWorldBase().whenComplete((created, error) -> {
                if (error != null) {
                    getLogger().severe("初始化 LuckPerms worldbase 权限组失败: " + error.getMessage());
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this,
                                () -> getServer().getPluginManager().disablePlugin(this));
                    }
                } else {
                    getLogger().info(created ? "已创建并初始化 LuckPerms worldbase 权限组"
                            : "LuckPerms worldbase 权限组已存在");
                }
            });
            getLogger().warning("其他地图生命周期和准入监听仍未实现，尚未发布完整 API 服务。");
        } catch (Exception ex) {
            getLogger().severe("MapManager 启动初始化失败: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        api = null;
    }
}
