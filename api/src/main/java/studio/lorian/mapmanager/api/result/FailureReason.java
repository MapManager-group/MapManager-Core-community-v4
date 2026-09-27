package studio.lorian.mapmanager.api.result;

/** 操作失败时使用的稳定机器可读原因代码。 */
public enum FailureReason {
    /** 请求的 MMC 地图记录不存在。 */
    MAP_NOT_FOUND,
    /** 世界后端中不存在请求的世界。 */
    WORLD_NOT_FOUND,
    /** 已存在同名地图或世界。 */
    ALREADY_EXISTS,
    /** 请求中有一个或多个参数无效。 */
    INVALID_INPUT,
    /** 无法解析请求中的玩家。 */
    PLAYER_NOT_FOUND,
    /** 操作者没有执行该操作的权限。 */
    NO_PERMISSION,
    /** 玩家无权进入请求的地图。 */
    ACCESS_DENIED,
    /** 世界后端拒绝了操作或执行失败。 */
    WORLD_BACKEND_FAILED,
    /** 所选存储后端读取或写入数据失败。 */
    STORAGE_FAILED,
    /** LuckPerms 角色或权限投影同步失败。 */
    PERMISSION_SYNC_FAILED,
    /** 同一资源上已有其他操作正在执行。 */
    OPERATION_IN_PROGRESS,
    /** 发生未预期的错误。 */
    INTERNAL_ERROR
}
