package studio.lorian.mapmanager.api.model;

import java.util.UUID;

/**
 * MapManager 所管理世界的不可变快照。
 *
 * <p>此模型不依赖 Bukkit 或 Multiverse 世界对象。访客成员通过 {@code MapRoleService} 查询。
 *
 * @param id 稳定的地图标识
 * @param worldName 服务器世界名称
 * @param groupId 管理员和建筑师成员关系使用的地图组标识
 * @param displayName 面向玩家显示的地图名称
 * @param settings 地图自身的公开状态、方块物理和爆炸设置
 * @param status 地图的持久生命周期状态
 * @param spawn 地图出生点；未设置时为 {@code null}
 */
public record MapInstance(
        UUID id,
        String worldName,
        String groupId,
        String displayName,
        MapSettings settings,
        MapStatus status,
        SpawnPoint spawn
) {
}
