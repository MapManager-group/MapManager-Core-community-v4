package studio.lorian.mapmanager.api.model;

import java.util.UUID;

/** 创建世界并将其注册为 MMC 地图的请求。
 *
 * @param worldName 要创建的服务器世界唯一名称
 * @param displayName 面向玩家显示的地图名称
 * @param groupId 管理员和建筑师共用的地图组标识
 * @param ownerId 首位地图管理员的 UUID
 * @param worldType 请求世界后端使用的生成类型
 */
public record CreateMapRequest(
        String worldName,
        String displayName,
        String groupId,
        UUID ownerId,
        WorldType worldType
) {
}
