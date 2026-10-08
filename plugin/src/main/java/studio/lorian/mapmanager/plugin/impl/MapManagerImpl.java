package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.MapAliasService;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.api.service.MapSettingsService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.api.service.WorldVersionService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRepository;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;

import org.bukkit.Bukkit;
import java.nio.file.Path;
import studio.lorian.mapmanager.api.service.WorldGateway;
import studio.lorian.mapmanager.plugin.internal.world.WorldGatewayImpl;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** API 入口实现；启动准备完成后由主类注册到服务器服务管理器。 */
public final class MapManagerImpl implements MapManager {
    private final MapService maps;
    private final MapRoleService roles;
    private final MapAccessService access;
    private final MapAliasServiceImpl aliases;
    private final MapSettingsService settings;
    private final MapTravelService travel;
    private final WorldLifeCycleService worldLifeCycle;
    private final WorldVersionService worldVersions;
    private final WorldGateway worldDirectories = new WorldGateway() {
        private final WorldGateway delegate = new WorldGatewayImpl();

        @Override public Path worldParentDirectory() {
            return delegate.worldParentDirectory();
        }

        @Override public Path worldDirectory(String worldName) {
            // 先复用名称校验；仅此对外查询优先实际路径，生命周期内部目录边界保持不变。
            Path expected = delegate.worldDirectory(worldName);
            var loaded = Bukkit.getServer() == null ? null : Bukkit.getWorld(worldName);
            return loaded == null ? expected : loaded.getWorldFolder().toPath().toAbsolutePath().normalize();
        }
    };

    public MapManagerImpl(MapRepository repository, RoleRepository roleRepository, OperationRepository operations,
                          WorldBaseProjection permissions, PlayerGateway players,
                          Pattern blockedCreationPattern, Logger logger, WorldVersionService worldVersions,
                          java.nio.file.Path dataDirectory) {
        this.maps = new MapServiceImpl(repository);
        this.aliases = new MapAliasServiceImpl(repository);
        this.settings = new MapSettingsServiceImpl(repository, aliases);
        this.roles = new MapRoleServiceImpl(repository, roleRepository, permissions);
        this.access = new MapAccessServiceImpl(repository, roles);
        this.worldVersions = worldVersions;
        this.worldLifeCycle = new WorldLifeCycleServiceImpl(repository, roleRepository, operations,
                permissions, aliases, worldVersions, blockedCreationPattern, logger, dataDirectory);
        this.travel = new MapTravelServiceImpl(repository, access, players, worldLifeCycle);
    }

    @Override
    public MapService maps() {
        return maps;
    }

    @Override
    public MapRoleService roles() {
        return roles;
    }

    @Override
    public MapAccessService access() {
        return access;
    }

    @Override
    public MapSettingsService settings() {
        return settings;
    }

    @Override
    public MapAliasService aliases() {
        return aliases;
    }

    @Override
    public MapTravelService travel() {
        return travel;
    }

    @Override
    public WorldLifeCycleService worldLifeCycle() {
        return worldLifeCycle;
    }

    @Override
    public WorldVersionService worldVersions() {
        return worldVersions;
    }

    @Override
    public WorldGateway worldDirectories() {
        return worldDirectories;
    }

}
