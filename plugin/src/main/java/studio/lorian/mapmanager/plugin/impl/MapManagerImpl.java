package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.api.service.MapSettingsService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;

/** API 入口的占位实现；完成业务实现后再由主类注册。 */
public final class MapManagerImpl implements MapManager {
    private final MapService maps;
    private final MapRoleService roles;
    private final MapAccessService access;
    private final MapSettingsService settings;
    private final MapTravelService travel = new MapTravelServiceImpl();
    private final WorldLifeCycleService worldLifeCycle = new WorldLifeCycleServiceImpl();

    public MapManagerImpl(MapRepository repository, RoleRepository roleRepository) {
        this.maps = new MapServiceImpl(repository);
        this.settings = new MapSettingsServiceImpl(repository);
        this.roles = new MapRoleServiceImpl(repository, roleRepository);
        this.access = new MapAccessServiceImpl(repository, roles);
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
    public MapTravelService travel() {
        return travel;
    }

    @Override
    public WorldLifeCycleService worldLifeCycle() {
        return worldLifeCycle;
    }
}
