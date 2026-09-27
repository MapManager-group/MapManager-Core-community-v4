package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;

import java.util.List;
import java.util.UUID;

/** 查询 MMC 管理的地图及其所属地图组。 */
public interface MapService {
    /**
     * 根据稳定标识查找地图。
     *
     * @param mapId 地图标识
     * @return 找到的地图；不存在时返回 {@code null}
     */
    MapInstance findById(UUID mapId);

    /**
     * 根据服务器世界名称查找地图。
     *
     * @param worldName 要查找的世界名称
     * @return 找到的地图；该世界未由 MMC 管理时返回 {@code null}
     */
    MapInstance findByWorldName(String worldName);

    /**
     * 查询 MMC 管理的全部地图。
     *
     * @return 不可变的地图快照列表，不会为 {@code null}
     */
    List<MapInstance> listMaps();

    /**
     * 根据稳定标识查找地图组。
     *
     * @param groupId 地图组标识
     * @return 找到的地图组；不存在时返回 {@code null}
     */
    MapGroup findGroup(String groupId);

    /**
     * 查询属于指定地图组的地图。
     *
     * @param groupId 地图组标识
     * @return 地图组内的地图列表，不会为 {@code null}
     */
    List<MapInstance> listGroupMaps(String groupId);
}
