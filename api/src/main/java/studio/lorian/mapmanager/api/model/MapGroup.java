package studio.lorian.mapmanager.api.model;

import java.util.Set;

/**
 * 共享管理员和建筑师的一组地图的不可变快照。
 *
 * <p>成员角色通过 {@code MapRoleService} 查询。创建此记录时会复制世界名称集合。
 *
 * @param id 稳定的地图组标识
 * @param worldNames 当前属于该组的世界名称
 */
public record MapGroup(String id, Set<String> worldNames) {
    /**
     * 创建地图组快照，并复制世界名称集合使其不可变。
     *
     * @param id 稳定的地图组标识
     * @param worldNames 当前属于该组的世界名称
     */
    public MapGroup {
        worldNames = Set.copyOf(worldNames);
    }
}
