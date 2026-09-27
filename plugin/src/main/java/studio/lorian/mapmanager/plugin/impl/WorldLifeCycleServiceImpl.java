package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.WorldLoadState;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;

/** 世界生命周期服务的占位实现。 */
public final class WorldLifeCycleServiceImpl implements WorldLifeCycleService {
    @Override
    public OperationResult<MapInstance> create(CreateMapRequest request) {
        // TODO: 调用世界后端创建世界并保存地图记录。
        return null;
    }

    @Override
    public OperationResult<MapInstance> importWorld(ImportMapRequest request) {
        // TODO: 校验并导入已有世界，保存地图记录。
        return null;
    }

    @Override
    public OperationResult<MapInstance> initialize(String worldName) {
        // TODO: 为已有世界应用 MMC 默认设置。
        return null;
    }

    @Override
    public OperationResult<Void> delete(String worldName) {
        // TODO: 按阶段删除世界与 MMC 数据。
        return null;
    }

    @Override
    public OperationResult<MapInstance> load(String worldName) {
        // TODO: 按需加载世界。
        return null;
    }

    @Override
    public OperationResult<Void> scheduleUnload(String worldName) {
        // TODO: 安排延迟卸载任务。
        return null;
    }

    @Override
    public OperationResult<Void> cancelScheduledUnload(String worldName) {
        // TODO: 取消延迟卸载任务。
        return null;
    }

    @Override
    public WorldLoadState state(String worldName) {
        // TODO: 查询世界实际加载状态。
        return null;
    }
}
