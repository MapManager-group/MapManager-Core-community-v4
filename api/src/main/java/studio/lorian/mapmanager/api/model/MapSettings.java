package studio.lorian.mapmanager.api.model;

/**
 * 单张地图的游戏规则设置。
 *
 * @param physical 是否启用方块物理和更新
 * @param explosion 爆炸是否可以破坏方块
 * @param isPublic 是否允许任何玩家以访客身份进入
 */
public record MapSettings(boolean physical, boolean explosion, boolean isPublic) {
}
