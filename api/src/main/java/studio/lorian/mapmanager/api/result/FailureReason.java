package studio.lorian.mapmanager.api.result;

/** 操作失败时使用的稳定机器可读原因代码。 */
public enum FailureReason {
    /** 请求的 MMC 地图记录不存在。 */
    MAP_NOT_FOUND,
    /** 世界后端中不存在请求的世界。 */
    WORLD_NOT_FOUND,
    /** 地图 level.dat 数据版本高于服务器，或版本信息无效。 */
    WORLD_VERSION_INCOMPATIBLE,
    /** 已存在同名地图或世界。 */
    ALREADY_EXISTS,
    /** 请求中有一个或多个参数无效。 */
    INVALID_INPUT,
    /** 请求的维度环境尚未实现。 */
    UNSUPPORTED_ENVIRONMENT,
    /** 无法解析请求中的玩家。 */
    PLAYER_NOT_FOUND,
    /** 玩家不在要操作的地图内。 */
    PLAYER_NOT_IN_MAP,
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
    /** MMC 修改已持久化；LuckPerms 投影已入持久队列，正在后台同步或等待重试。 */
    PERMISSION_SYNC_PENDING,
    /** 同一资源上已有其他操作正在执行。 */
    OPERATION_IN_PROGRESS,
    /** 地图状态不允许执行请求的操作。 */
    INVALID_MAP_STATE,
    /** 地图内仍有在线玩家，不能删除。 */
    WORLD_HAS_PLAYERS,
    /** 恢复请求的操作类型或地图身份与当前操作冲突。 */
    RECOVERY_CONFLICT,
    /** 发生未预期的错误。 */
    INTERNAL_ERROR
}
