package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapTravelService;

import java.util.UUID;

/** 地图传送服务的占位实现。 */
public final class MapTravelServiceImpl implements MapTravelService {
    @Override
    public OperationResult<Void> teleport(UUID playerId, UUID mapId) {
        // TODO: 检查准入、按需加载地图并传送玩家。
        return null;
    }

    @Override
    public OperationResult<Void> kickFromMap(UUID operatorId, UUID playerId, UUID mapId) {
        // TODO: 检查管理权限并将玩家送至安全世界。
        return null;
    }
}
