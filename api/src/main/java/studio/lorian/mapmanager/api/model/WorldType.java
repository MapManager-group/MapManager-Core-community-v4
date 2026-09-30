package studio.lorian.mapmanager.api.model;

/** 创建或导入世界时使用的地形生成器类型。
 *
 * <p>维度环境由 {@link Environment} 单独指定。
 * <p>{@link #VOID} 需要安装兼容的虚空世界生成器。
 */
public enum WorldType {
    /** 平坦地形。 */
    FLAT,

    /** 原版标准地形生成器。 */
    NORMAL,

    /** 虚空地形，由 VoidGen 插件提供。 */
    VOID
}
