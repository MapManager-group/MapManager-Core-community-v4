package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.result.OperationResult;

/** 编排地图元数据与世界后端操作，统一管理世界生命周期。 */
public interface WorldLifeCycleService {
    /**
     * 创建世界并将其注册为 MMC 地图。
     *
     * @param request 创建参数、生成器类型、维度环境和初始所有者
     * @return 创建后的地图，或操作失败结果
     */
    OperationResult<MapInstance> create(CreateMapRequest request);

    /**
     * 导入服务端根目录下包含 {@code level.dat} 的已有世界，并将其注册为 MMC 地图。
     *
     * @param request 导入参数、生成器类型、维度环境和初始所有者
     * @return 导入后的地图，或操作失败结果
     */
    OperationResult<MapInstance> importWorld(ImportMapRequest request);

    /**
     * 为已加载的 MMC 地图重放 Multiverse 属性、按公开状态设置别名、生物生成限制及 gamerule 默认设置。
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

    /** 加载已登记且 ACTIVE 的世界；仅主线程调用，目录必须存在。
     * @param worldName 已登记的世界名称
     * @return 已加载地图或失败结果
     */
    OperationResult<MapInstance> load(String worldName);

    /** 按持久请求恢复创建/导入或已确认的删除；仅主线程调用。
     * @param worldName 有完整恢复请求的世界名称
     * @return 恢复后的地图；删除完成时值为 null
     */
    OperationResult<MapInstance> recover(String worldName);
}
