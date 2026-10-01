package studio.lorian.mapmanager.api;

import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.MapAliasService;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.api.service.MapSettingsService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.api.service.WorldVersionService;

/**
 * MapManager 对外公开 API 的统一入口。
 *
 * <p>具体实现由 plugin 模块提供，并注册到服务器服务管理器。此接口只公开业务服务；存储端口和后端细节由 plugin 模块内部管理。
 */
public interface MapManager {
    /**
     * 返回地图和地图组查询服务。
     *
     * @return 地图查询服务
     */
    MapService maps();

    /**
     * 返回地图角色成员管理服务。
     *
     * @return 地图角色服务
     */
    MapRoleService roles();

    /**
     * 返回 MMC 地图准入判断及可访问地图查询服务。
     *
     * @return 地图准入服务
     */
    MapAccessService access();

    /**
     * 返回单地图设置服务。
     *
     * @return 地图设置服务
     */
    MapSettingsService settings();

    /**
     * 返回按地图公开状态同步 Multiverse 别名的服务。
     *
     * @return 地图别名服务
     */
    MapAliasService aliases();

    /**
     * 返回地图传送及玩家移出服务。
     *
     * @return 地图传送服务
     */
    MapTravelService travel();

    /**
     * 返回世界创建、导入、初始化、删除、按需加载和恢复服务。
     *
     * @return 世界生命周期服务
     */
    WorldLifeCycleService worldLifeCycle();

    /**
     * 返回地图文件版本查询服务。
     *
     * @return 地图版本服务
     */
    WorldVersionService worldVersions();
}
