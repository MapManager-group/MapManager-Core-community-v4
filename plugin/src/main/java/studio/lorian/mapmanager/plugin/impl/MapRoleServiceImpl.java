package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** MMC 持久角色规则；组角色对该组地图生效。 */
public final class MapRoleServiceImpl implements MapRoleService {
    private final MapRepository maps;
    private final RoleRepository roles;
    public MapRoleServiceImpl(MapRepository maps, RoleRepository roles) { this.maps = maps; this.roles = roles; }
    private String scope(MapInstance map, MapRole role) {
        return role == MapRole.VISITOR ? map.id().toString() : map.groupId();
    }
    @Override public Set<UUID> members(UUID mapId, MapRole role) {
        MapInstance map = maps.findById(mapId);
        if (map == null || role == null) return Set.of();
        Set<UUID> result = new HashSet<>(roles.members(scope(map, role), role));
        if (role == MapRole.BUILDER || role == MapRole.VISITOR) result.addAll(roles.members(map.groupId(), MapRole.ADMIN));
        if (role == MapRole.VISITOR) result.addAll(roles.members(map.groupId(), MapRole.BUILDER));
        return Set.copyOf(result);
    }
    @Override public boolean hasRole(UUID mapId, UUID playerId, MapRole role) {
        return playerId != null && members(mapId, role).contains(playerId);
    }
    private OperationResult<Void> change(UUID mapId, UUID playerId, MapRole role, boolean add) {
        OperationName operation = add ? OperationName.ADD_MEMBER : OperationName.REMOVE_MEMBER;
        UUID id = UUID.randomUUID();
        if (mapId == null || playerId == null || role == null)
            return new OperationResult.Failure<>(operation, id, FailureReason.INVALID_INPUT);
        try {
            MapInstance map = maps.findById(mapId);
            if (map == null) return new OperationResult.Failure<>(operation, id, FailureReason.MAP_NOT_FOUND);
            String scope = scope(map, role);
            if (add) roles.add(scope, role, playerId); else roles.remove(scope, role, playerId);
            if (role != MapRole.VISITOR)
                return new OperationResult.Partial<>(operation, id, null, FailureReason.PERMISSION_SYNC_FAILED);
            return new OperationResult.Success<>(operation, id, null);
        } catch (StorageException ex) {
            return new OperationResult.Failure<>(operation, id, FailureReason.STORAGE_FAILED);
        }
    }
    @Override public OperationResult<Void> addMember(UUID mapId, UUID playerId, MapRole role) {
        return change(mapId, playerId, role, true);
    }
    @Override public OperationResult<Void> removeMember(UUID mapId, UUID playerId, MapRole role) {
        return change(mapId, playerId, role, false);
    }
}
