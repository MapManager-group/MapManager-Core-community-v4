package studio.lorian.mapmanager.api.model;

/** 创建或导入世界时使用的环境或生成器类型。
 *
 * <p>{@link #VOID} 需要安装兼容的虚空世界生成器。
 */
public enum WorldType {
    /** 平坦地形。 */
    FLAT,

    /** 标准主世界地形。 */
    NORMAL,

    /** 虚空地形，由 VoidGen 插件提供。 */
    VOID,

    /** 下界环境。 */
    NETHER,

    /** 末地环境。 */
    END
}
