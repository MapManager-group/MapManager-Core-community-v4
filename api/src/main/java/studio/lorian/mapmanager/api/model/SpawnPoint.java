package studio.lorian.mapmanager.api.model;

/**
 * 与 Bukkit 世界对象无关的地图出生点表示。
 *
 * @param worldName 出生点所在的世界名称
 * @param x X 坐标
 * @param y Y 坐标
 * @param z Z 坐标
 * @param yaw 水平方向角度，单位为度
 * @param pitch 垂直方向角度，单位为度
 */
public record SpawnPoint(
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
