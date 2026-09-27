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
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRepository;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;

import java.util.logging.Logger;

/** API 入口的占位实现；完成业务实现后再由主类注册。 */
public final class MapManagerImpl implements MapManager {
    private final MapService maps;
    private final MapRoleService roles;
    private final MapAccessService access;
    private final MapAliasServiceImpl aliases;
    private final MapSettingsService settings;
    private final MapTravelService travel;
    private final WorldLifeCycleService worldLifeCycle;

    public MapManagerImpl(MapRepository repository, RoleRepository roleRepository, OperationRepository operations,
                          WorldBaseProjection permissions, PlayerGateway players, Logger logger) {
        this.maps = new MapServiceImpl(repository);
        this.aliases = new MapAliasServiceImpl(repository);
        this.settings = new MapSettingsServiceImpl(repository, aliases);
        this.roles = new MapRoleServiceImpl(repository, roleRepository);
        this.access = new MapAccessServiceImpl(repository, roles);
        this.worldLifeCycle = new WorldLifeCycleServiceImpl(repository, roleRepository, operations, permissions, aliases, logger);
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
}
