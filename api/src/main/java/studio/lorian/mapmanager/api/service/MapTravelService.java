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
     * 由当前地图组管理员将在线玩家送至 Multiverse 默认世界的出生点。
     * 只有操作者和目标玩家都位于操作者当前的 MMC 地图时才会执行。
     *
     * @param operatorId 执行移出操作的玩家 UUID
     * @param playerId 要移出的玩家 UUID
     * @return 操作结果
     */
    OperationResult<Void> kickFromMap(UUID operatorId, UUID playerId);
}
