package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.result.OperationResult;

import java.util.Set;
import java.util.UUID;

/**
 * 查询和修改玩家在地图上的角色。
 *
 * <p>{@link MapRole#ADMIN} 和 {@link MapRole#BUILDER} 按地图组管理；
 * {@link MapRole#VISITOR} 按单张地图管理。
 */
public interface MapRoleService {
    /**
     * 查询在指定地图上具有该有效角色的玩家。
     *
     * @param mapId 要查询成员的地图标识
     * @param role 要查询的角色
     * @return 玩家 UUID 集合，不会为 {@code null}
     */
    Set<UUID> members(UUID mapId, MapRole role);

    /**
     * 检查玩家在指定地图上是否具有该有效角色。
     *
     * @param mapId 要检查的地图标识
     * @param playerId 要检查的玩家 UUID
     * @param role 要检查的角色
     * @return 玩家在该地图上具有此角色时返回 {@code true}
     */
    boolean hasRole(UUID mapId, UUID playerId, MapRole role);

    /**
     * 向玩家授予角色。
     *
     * @param mapId 用于确定角色作用范围的地图标识
     * @param playerId 接收角色的玩家 UUID
     * @param role 要授予的角色
     * @return 操作结果；包含尚待完成的权限投影状态
     */
    OperationResult<Void> addMember(UUID mapId, UUID playerId, MapRole role);

    /**
     * 撤销玩家的角色。
     *
     * @param mapId 用于确定角色作用范围的地图标识
     * @param playerId 被撤销角色的玩家 UUID
     * @param role 要撤销的角色
     * @return 操作结果；包含尚待完成的权限投影状态
     */
    OperationResult<Void> removeMember(UUID mapId, UUID playerId, MapRole role);
}
