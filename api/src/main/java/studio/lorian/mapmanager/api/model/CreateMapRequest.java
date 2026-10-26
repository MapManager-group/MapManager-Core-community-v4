package studio.lorian.mapmanager.api.model;

import java.util.Map;
import java.util.UUID;

/** 创建世界并将其注册为 MMC 地图的请求。
 *
 * @param worldName 要创建的服务器世界唯一名称
 * @param displayName 面向玩家显示的地图名称
 * @param groupId 管理员和建筑师共用的地图组标识
 * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
 * @param worldType 请求世界后端使用的地形生成器类型
 * @param environment 请求世界后端使用的维度环境
 * @param parameters 可选的创建参数键值对；支持 flat.height、seed、structures、biome、generator、generator-settings 和 property. 前缀的 Multiverse 世界属性；flat.height 仅适用于 FLAT，表示表层方块的绝对 Y 坐标，生成时按世界实际 minHeight（含）和 maxHeight（不含）校验
 */
public record CreateMapRequest(
        String worldName,
        String displayName,
        String groupId,
        UUID ownerId,
        WorldType worldType,
        Environment environment,
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
        this(worldName, displayName, groupId, ownerId, WorldType.FLAT, Environment.NORMAL, Map.of());
    }

    /**
     * 创建指定生成器类型的请求，维度环境默认为主世界。
     *
     * @param worldName 要创建的服务器世界唯一名称
     * @param displayName 面向玩家显示的地图名称
     * @param groupId 管理员和建筑师共用的地图组标识
     * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
     * @param worldType 请求世界后端使用的地形生成器类型
     */
    public CreateMapRequest(String worldName, String displayName, String groupId, UUID ownerId, WorldType worldType) {
        this(worldName, displayName, groupId, ownerId, worldType, Environment.NORMAL, Map.of());
    }

    /**
     * 兼容旧版请求构造器；维度环境默认为主世界。
     *
     * @param worldName 要创建的服务器世界唯一名称
     * @param displayName 面向玩家显示的地图名称
     * @param groupId 管理员和建筑师共用的地图组标识
     * @param ownerId 首位地图管理员的 UUID；可为 {@code null}
     * @param worldType 请求世界后端使用的地形生成器类型
     * @param parameters 创建参数键值对
     */
    public CreateMapRequest(String worldName, String displayName, String groupId, UUID ownerId,
                            WorldType worldType, Map<String, String> parameters) {
        this(worldName, displayName, groupId, ownerId, worldType, Environment.NORMAL, parameters);
    }

    /** 复制创建参数，避免调用方在执行期间修改。 */
    public CreateMapRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
