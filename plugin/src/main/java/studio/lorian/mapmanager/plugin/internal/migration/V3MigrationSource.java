package studio.lorian.mapmanager.plugin.internal.migration;

/** Reads v3 files and metadata; the application layer owns dry-run and migration orchestration. */
public interface V3MigrationSource {
    LegacySnapshot read();
}
