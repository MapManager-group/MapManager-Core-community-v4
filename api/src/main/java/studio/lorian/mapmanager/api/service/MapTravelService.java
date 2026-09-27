package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.result.OperationResult;

import java.util.UUID;

/** 提供地图传送和将玩家移出地图的操作，对应 v3 的相关命令。 */
public interface MapTravelService {
    /**
     * 检查 MMC 准入权限后，将玩家传送到目标地图的出生点。
     *
     * @param playerId 要传送的玩家 UUID
     * @param mapId 目标地图标识
     * @return 操作结果；无准入权限时返回对应失败原因
     */
    OperationResult<Void> teleport(UUID playerId, UUID mapId);

    /**
     * 将玩家从地图送回已配置的安全位置。
     *
     * @param operatorId 执行移出操作的玩家 UUID
     * @param playerId 要移出的玩家 UUID
     * @param mapId 要将玩家移出的地图标识
     * @return 操作结果
     */
    OperationResult<Void> kickFromMap(UUID operatorId, UUID playerId, UUID mapId);
}
