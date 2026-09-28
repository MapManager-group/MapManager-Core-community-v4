package studio.lorian.mapmanager.api.model;

import java.util.Map;
import java.util.UUID;

/** 创建世界并将其注册为 MMC 地图的请求。
 *
 * @param worldName 要创建的服务器世界唯一名称
 * @param displayName 面向玩家显示的地图名称
 * @param groupId 管理员和建筑师共用的地图组标识
 * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
 * @param worldType 请求世界后端使用的生成类型
 * @param parameters 可选的创建参数键值对；支持 flat.height、seed、structures、biome、generator、generator-settings 和 property. 前缀的 Multiverse 世界属性；SIFT、NETHER、END 当前复用 FLAT 的生成参数处理，NETHER/END 仅改变环境
 */
public record CreateMapRequest(
        String worldName,
        String displayName,
        String groupId,
        UUID ownerId,
        WorldType worldType,
        Map<String, String> parameters
) {
    /**
     * 创建平坦世界的请求，不附加生成参数。
     *
     * @param worldName 要创建的服务器世界唯一名称
     * @param displayName 面向玩家显示的地图名称
     * @param groupId 管理员和建筑师共用的地图组标识
     * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
     */
    public CreateMapRequest(String worldName, String displayName, String groupId, UUID ownerId) {
        this(worldName, displayName, groupId, ownerId, WorldType.FLAT, Map.of());
    }

    /**
     * 创建指定世界类型且不附加生成参数的请求。
     *
     * @param worldName 要创建的服务器世界唯一名称
     * @param displayName 面向玩家显示的地图名称
     * @param groupId 管理员和建筑师共用的地图组标识
     * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
     * @param worldType 请求世界后端使用的生成类型
     */
    public CreateMapRequest(String worldName, String displayName, String groupId, UUID ownerId, WorldType worldType) {
        this(worldName, displayName, groupId, ownerId, worldType, Map.of());
    }

    /** 复制创建参数，避免调用方在执行期间修改。 */
    public CreateMapRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
