package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.MapInstance;
import java.util.List;
import java.util.UUID;

/**
 * 判断玩家能否进入地图，并查询该玩家可访问的地图。
 *
 * <p>准入判断依据 MMC 角色数据和地图公开状态。LuckPerms 与 Multiverse 的准入权限节点不作为地图准入依据。
 */
public interface MapAccessService {
    /**
     * 判断玩家是否可以进入指定地图。
     *
     * @param playerId 玩家的 UUID
     * @param mapId 目标地图的稳定标识
     * @return 包含有效角色或拒绝原因的准入判断
     */
    AccessDecision decide(UUID playerId, UUID mapId);

    /**
     * 查询玩家可以进入的地图，包括公开地图。
     *
     * @param playerId 玩家的 UUID
     * @return 可访问地图列表，不会为 {@code null}
     */
    List<MapInstance> accessibleMaps(UUID playerId);

    /**
     * 查询玩家具有管理员角色的地图。
     *
     * @param playerId 玩家的 UUID
     * @return 玩家管理的地图列表，不会为 {@code null}
     */
    List<MapInstance> administratedMaps(UUID playerId);
}
