package studio.lorian.mapmanager.api.model;

/** 创建或导入世界时使用的维度环境。 */
public enum Environment {
    /** 主世界环境。 */
    NORMAL,

    /** 下界环境。 */
    NETHER,

    /** 末地环境。 */
    THE_END,

    /** 为后续维度支持预留；当前服务端生命周期尚不支持。 */
    SIFT
}
