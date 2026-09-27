package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.result.OperationResult;

import java.util.UUID;

/** 根据 MMC 地图的公开状态设置 Multiverse 别名。 */
public interface MapAliasService {
    /**
     * 使用地图名设置别名；公开地图为 {@code &2<地图名>}，非公开地图为 {@code &3<地图名>}。
     *
     * @param mapId 要同步别名的地图 ID
     * @return 别名同步结果
     */
    OperationResult<Void> setAlias(UUID mapId);

    /**
     * 使用指定别名设置地图别名，并将去除 Minecraft 颜色代码后的别名保存为展示名。
     * 公开地图使用 {@code &2}，非公开地图使用 {@code &3}。
     *
     * @param mapId 要设置别名的地图 ID
     * @param alias 不带颜色的目标别名
     * @return 别名设置结果
     */
    OperationResult<Void> setAlias(UUID mapId, String alias);
}
