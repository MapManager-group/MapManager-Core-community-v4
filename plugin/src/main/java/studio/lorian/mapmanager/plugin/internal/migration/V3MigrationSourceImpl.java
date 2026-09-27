package studio.lorian.mapmanager.plugin.internal.migration;

/** v3 迁移数据读取端口的占位实现。 */
public final class V3MigrationSourceImpl implements V3MigrationSource {
    @Override
    public LegacySnapshot read() {
        // TODO: 只读加载 v3 的 worlds.json、groups.json 和关联设置。
        return null;
    }
}
