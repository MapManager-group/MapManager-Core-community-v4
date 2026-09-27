package studio.lorian.mapmanager.api.model;

/** 玩家在地图访问和管理中的角色。
 *
 * <p>{@link #ADMIN} 和 {@link #BUILDER} 的范围是地图组；{@link #VISITOR} 仅对单张地图生效，且不授予建造权限。
 */
public enum MapRole {
    /** 管理地图组成员和设置，同时拥有建筑师权限。 */
    ADMIN,

    /** 可进入地图组中的地图，并获得 LP {@code worldbase} 继承的权限。 */
    BUILDER,

    /** 可进入单独授权的地图，但不获得建造权限。 */
    VISITOR
}
