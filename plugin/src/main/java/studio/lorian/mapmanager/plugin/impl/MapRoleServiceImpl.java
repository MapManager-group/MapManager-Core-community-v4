package studio.lorian.mapmanager.plugin.impl;

import java.util.function.Predicate;

import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** MMC 持久角色规则；组角色对该组地图生效。 */
public final class MapRoleServiceImpl implements MapRoleService {
    private final Predicate<String> defaultWorld;
    private final MapRepository maps;
    private final RoleRepository roles;
    private final WorldBaseProjection permissions;
    public MapRoleServiceImpl(MapRepository maps, RoleRepository roles, WorldBaseProjection permissions) {
        this(maps, roles, permissions, world -> false);
    }

    public MapRoleServiceImpl(MapRepository maps, RoleRepository roles, WorldBaseProjection permissions, Predicate<String> defaultWorld) {
        this.defaultWorld = defaultWorld;
        this.maps = maps;
        this.roles = roles;
        this.permissions = permissions;
    }
    private String scope(MapInstance map, MapRole role) {
        return role == MapRole.VISITOR ? map.id().toString() : map.groupId();
    }
    @Override public Set<UUID> assignedMembers(UUID mapId, MapRole role) {
        MapInstance map = maps.findById(mapId);
        if (map == null || defaultWorld.test(map.worldName()) || role == null) return Set.of();
        return Set.copyOf(roles.members(scope(map, role), role));
    }
    @Override public Set<UUID> members(UUID mapId, MapRole role) {
        MapInstance map = maps.findById(mapId);
        if (map == null || defaultWorld.test(map.worldName()) || role == null) return Set.of();
        Set<UUID> result = new HashSet<>(roles.members(scope(map, role), role));
        if (role == MapRole.BUILDER || role == MapRole.VISITOR) result.addAll(roles.members(map.groupId(), MapRole.ADMIN));
        if (role == MapRole.VISITOR) result.addAll(roles.members(map.groupId(), MapRole.BUILDER));
        return Set.copyOf(result);
    }
    @Override public boolean hasRole(UUID mapId, UUID playerId, MapRole role) {
        MapInstance map = maps.findById(mapId);
        if (map == null || defaultWorld.test(map.worldName()) || playerId == null || role == null) return false;
        if (roles.members(map.groupId(), MapRole.ADMIN).contains(playerId)) return true;
        if (role != MapRole.ADMIN && roles.members(map.groupId(), MapRole.BUILDER).contains(playerId)) return true;
        return role == MapRole.VISITOR && roles.members(map.id().toString(), MapRole.VISITOR).contains(playerId);
    }
    private synchronized OperationResult<Void> change(UUID mapId, UUID playerId, MapRole role, boolean add) {
        OperationName operation = add ? OperationName.ADD_MEMBER : OperationName.REMOVE_MEMBER;
        UUID id = UUID.randomUUID();
        if (mapId == null || playerId == null || role == null)
            return new OperationResult.Failure<>(operation, id, FailureReason.INVALID_INPUT);
        try {
            MapInstance map = maps.findById(mapId);
            if (map == null) return new OperationResult.Failure<>(operation, id, FailureReason.MAP_NOT_FOUND);
            if (defaultWorld.test(map.worldName()) || map.status() != MapStatus.ACTIVE)
                return new OperationResult.Failure<>(operation, id, FailureReason.ACCESS_DENIED);
            String scope = scope(map, role);
            if (add) roles.add(scope, role, playerId); else roles.remove(scope, role, playerId);
            if (role != MapRole.VISITOR) {
                // MMC 已写入；此后的读取或 LP 错误必须报告 Partial，避免将已生效的修改误报为失败。
                boolean enabled;
                Set<String> worldNames;
                try {
                    enabled = roles.members(scope, MapRole.ADMIN).contains(playerId)
                            || roles.members(scope, MapRole.BUILDER).contains(playerId);
                    var group = maps.findGroup(scope);
                    worldNames = group == null ? Set.of(map.worldName()) : group.worldNames();
                } catch (StorageException exception) {
                    return new OperationResult.Partial<>(operation, id, null, FailureReason.STORAGE_FAILED);
                }
                // 两种组角色共用同一个 LP 地图组；撤销其中一种时保留另一种的建造权限。
                try {
                    var sync = permissions.ensureGroupInheritance(scope, worldNames);
                    if (sync.isDone() && !sync.join())
                        return new OperationResult.Partial<>(operation, id, null, FailureReason.PERMISSION_SYNC_FAILED);
                    if (!permissions.setBuilderAccess(scope, playerId, enabled))
                        return new OperationResult.Partial<>(operation, id, null, FailureReason.PERMISSION_SYNC_FAILED);
                } catch (RuntimeException exception) {
                    return new OperationResult.Partial<>(operation, id, null, FailureReason.PERMISSION_SYNC_FAILED);
                }
                if (permissions.pending())
                    return new OperationResult.Partial<>(operation, id, null, FailureReason.PERMISSION_SYNC_PENDING);
            }
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
