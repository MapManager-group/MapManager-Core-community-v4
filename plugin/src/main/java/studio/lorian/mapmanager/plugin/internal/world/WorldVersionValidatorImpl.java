package studio.lorian.mapmanager.plugin.internal.world;

/** 世界版本校验端口的占位实现。 */
public final class WorldVersionValidatorImpl implements WorldVersionValidator {
    @Override
    public boolean canImport(String worldName) {
        // TODO: 检查世界目录及版本是否兼容。
        return false;
    }
}
