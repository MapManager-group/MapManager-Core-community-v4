package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.service.AccessDecision;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;

import java.util.List;
import java.util.UUID;

/** 只根据 MMC 持久状态计算地图准入。 */
public final class MapAccessServiceImpl implements MapAccessService {
    private final MapRepository maps;
    private final MapRoleService roles;
    public MapAccessServiceImpl(MapRepository maps, MapRoleService roles) { this.maps = maps; this.roles = roles; }
    @Override public AccessDecision decide(UUID playerId, UUID mapId) {
        if (playerId == null || mapId == null) return new AccessDecision(false, null, FailureReason.INVALID_INPUT);
        MapInstance map = maps.findById(mapId);
        if (map == null) return new AccessDecision(false, null, FailureReason.MAP_NOT_FOUND);
        if (map.status() != MapStatus.ACTIVE) return new AccessDecision(false, null, FailureReason.ACCESS_DENIED);
        if (roles.hasRole(mapId, playerId, MapRole.ADMIN)) return new AccessDecision(true, MapRole.ADMIN, null);
        if (roles.hasRole(mapId, playerId, MapRole.BUILDER)) return new AccessDecision(true, MapRole.BUILDER, null);
        if (roles.hasRole(mapId, playerId, MapRole.VISITOR)) return new AccessDecision(true, MapRole.VISITOR, null);
        if (map.settings().isPublic()) return new AccessDecision(true, null, null);
        return new AccessDecision(false, null, FailureReason.ACCESS_DENIED);
    }
    @Override public List<MapInstance> accessibleMaps(UUID playerId) {
        return maps.findAll().stream().filter(map -> decide(playerId, map.id()).allowed()).toList();
    }
    @Override public List<MapInstance> administratedMaps(UUID playerId) {
        return maps.findAll().stream().filter(map -> map.status() == MapStatus.ACTIVE
                && roles.hasRole(map.id(), playerId, MapRole.ADMIN)).toList();
    }
}
