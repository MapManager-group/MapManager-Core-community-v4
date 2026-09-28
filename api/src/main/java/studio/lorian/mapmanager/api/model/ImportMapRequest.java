package studio.lorian.mapmanager.api.model;

import java.util.UUID;

/** 将服务端根目录下已有的世界目录注册为 MMC 地图的请求；该目录必须包含 {@code level.dat}。
 *
 * @param worldName 要导入的现有服务器世界名称
 * @param displayName 面向玩家显示的地图名称
 * @param groupId 管理员和建筑师共用的地图组标识
 * @param ownerId 首位地图管理员的 UUID
 * @param worldType 世界后端导入时使用的环境类型
 */
public record ImportMapRequest(
        String worldName,
        String displayName,
        String groupId,
        UUID ownerId,
        WorldType worldType
) {
    /**
     * 按平坦世界环境导入现有地图。
     *
     * @param worldName 要导入的现有服务器世界名称
     * @param displayName 面向玩家显示的地图名称
     * @param groupId 管理员和建筑师共用的地图组标识
     * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
     */
    public ImportMapRequest(String worldName, String displayName, String groupId, UUID ownerId) {
        this(worldName, displayName, groupId, ownerId, WorldType.FLAT);
    }
}
