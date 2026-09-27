package studio.lorian.mapmanager.api.result;

/** MapManager 操作的稳定标识。 */
public enum OperationName {
    /** 创建并注册新地图。 */
    CREATE,
    /** 导入并注册已有世界。 */
    IMPORT,
    /** 为已有世界应用 MapManager 默认设置。 */
    INITIALIZE,
    /** 删除受管理的地图及其世界。 */
    DELETE,
    /** 将世界加载到服务器。 */
    LOAD,
    /** 将世界从服务器卸载。 */
    UNLOAD,
    /** 向玩家授予地图角色。 */
    ADD_MEMBER,
    /** 撤销玩家的地图角色。 */
    REMOVE_MEMBER,
    /** 修改地图的公开状态。 */
    SET_PUBLIC,
    /** 修改地图显示名称。 */
    SET_ALIAS,
    /** 修改地图方块物理设置。 */
    SET_PHYSICAL,
    /** 修改地图爆炸设置。 */
    SET_EXPLOSION,
    /** 修改地图出生点。 */
    SET_SPAWN,
    /** 将玩家传送到地图。 */
    TELEPORT,
    /** 将玩家移出地图。 */
    KICK
}
