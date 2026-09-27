package studio.lorian.mapmanager.api.model;

/** 地图记录的持久生命周期状态。 */
public enum MapStatus {
    /** 世界创建或导入尚未完成。 */
    PENDING,
    /** 地图可正常访问。 */
    ACTIVE,
    /** 地图已归档，不接受常规访问。 */
    ARCHIVED,
    /** 世界与数据正在删除。 */
    DELETING,
    /** 删除完成后保留的历史记录。 */
    DELETED
}
