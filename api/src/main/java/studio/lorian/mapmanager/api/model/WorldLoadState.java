package studio.lorian.mapmanager.api.model;

/** 世界当前的运行时加载状态。 */
public enum WorldLoadState {
    /** 世界目录或已注册的世界均不存在。 */
    ABSENT,

    /** 世界存在，但当前尚未加载。 */
    UNLOADED,

    /** 世界已由服务器加载。 */
    LOADED
}
