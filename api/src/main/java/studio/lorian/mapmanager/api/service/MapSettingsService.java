package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.OperationResult;

import java.util.UUID;

/** 修改单张地图的公开状态、显示名称、方块物理、爆炸和出生点。 */
public interface MapSettingsService {
    /**
     * 设置是否允许任意玩家以访客身份进入地图，并刷新 Multiverse 别名颜色。
     *
     * @param mapId 要修改的地图标识
     * @param isPublic {@code true} 表示允许公开访客进入
     * @return 操作结果
     */
    OperationResult<Void> setPublic(UUID mapId, boolean isPublic);

    /**
     * 修改面向玩家显示的地图名称，并刷新 Multiverse 别名。
     *
     * @param mapId 要修改的地图标识
     * @param displayName 新的显示名称
     * @return 操作结果
     */
    OperationResult<Void> setDisplayName(UUID mapId, String displayName);

    /**
     * 设置地图是否启用方块物理和更新。
     *
     * @param mapId 要修改的地图标识
     * @param enabled {@code true} 表示启用方块物理
     * @return 操作结果
     */
    OperationResult<Void> setPhysical(UUID mapId, boolean enabled);

    /**
     * 设置地图中的爆炸是否可以破坏方块。
     *
     * @param mapId 要修改的地图标识
     * @param enabled {@code true} 表示允许爆炸破坏方块
     * @return 操作结果
     */
    OperationResult<Void> setExplosion(UUID mapId, boolean enabled);

    /**
     * 获取地图已配置的出生点。
     *
     * @param mapId 要查询的地图标识
     * @return 出生点；未配置或不可用时返回 {@code null}
     */
    SpawnPoint getSpawn(UUID mapId);

    /**
     * 设置地图出生点。
     *
     * @param mapId 要修改的地图标识
     * @param spawn 新出生点
     * @return 操作结果
     */
    OperationResult<Void> setSpawn(UUID mapId, SpawnPoint spawn);

}
