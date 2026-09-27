package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.WorldLoadState;
import studio.lorian.mapmanager.api.result.OperationResult;

/** 编排地图元数据与世界后端操作，统一管理世界生命周期。 */
public interface WorldLifeCycleService {
    /**
     * 创建世界并将其注册为 MMC 地图。
     *
     * @param request 创建参数和初始所有者
     * @return 创建后的地图，或操作失败结果
     */
    OperationResult<MapInstance> create(CreateMapRequest request);

    /**
     * 导入已有世界并将其注册为 MMC 地图。
     *
     * @param request 导入参数和初始所有者
     * @return 导入后的地图，或操作失败结果
     */
    OperationResult<MapInstance> importWorld(ImportMapRequest request);

    /**
     * 为已加载的现有世界应用 MMC 默认设置。
     *
     * @param worldName 服务器世界名称
     * @return 初始化后的地图，或操作失败结果
     */
    OperationResult<MapInstance> initialize(String worldName);

    /**
     * 删除受管理的地图，并请求世界后端删除对应世界。
     *
     * @param worldName 服务器世界名称
     * @return 操作结果
     */
    OperationResult<Void> delete(String worldName);

    /**
     * 在受管理的世界尚未加载时加载该世界。
     *
     * @param worldName 服务器世界名称
     * @return 地图快照，或操作失败结果
     */
    OperationResult<MapInstance> load(String worldName);

    /**
     * 将当前未使用的世界加入延迟卸载计划。
     *
     * @param worldName 服务器世界名称
     * @return 操作结果
     */
    OperationResult<Void> scheduleUnload(String worldName);

    /**
     * 取消已安排的世界延迟卸载任务。
     *
     * @param worldName 服务器世界名称
     * @return 操作结果；失败表示世界或待执行任务不存在
     */
    OperationResult<Void> cancelScheduledUnload(String worldName);

    /**
     * 获取世界当前的运行时加载状态。
     *
     * @param worldName 服务器世界名称
     * @return 当前加载状态
     */
    WorldLoadState state(String worldName);
}
