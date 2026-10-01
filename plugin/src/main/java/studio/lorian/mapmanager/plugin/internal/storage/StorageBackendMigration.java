package studio.lorian.mapmanager.plugin.internal.storage;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/** 显式启动迁移：源目标指纹校验、只读备份、幂等恢复，最后更新后端标记。 */
public final class StorageBackendMigration {
    private StorageBackendMigration() { }
    public static String identity(StorageConfig config) {
        return config.type() == StorageConfig.Type.JSON ? "JSON" : "MYSQL:" + config.host() + ":" + config.port() + "/" + config.database();
    }
    public static CachedStorage open(StorageConfig config, Path directory) throws IOException {
        if (config.type() == StorageConfig.Type.JSON) {
            var storage = new JsonStorage(directory.resolve("maps.json")); return new CachedStorage(storage, storage, storage);
        }
        var storage = new MySqlStorage(config); return new CachedStorage(storage, storage, storage);
    }
    public static CachedStorage openAndMigrate(FileConfiguration configuration, Path directory) throws IOException {
        StorageConfig selected = StorageConfig.load(configuration);
        Path marker = directory.resolve("storage-backend.txt");
        String previous = Files.exists(marker) ? Files.readString(marker).trim() : null;
        String next = identity(selected);
        if (previous == null || previous.equals(next)) {
            CachedStorage target = open(selected, directory);
            if (previous == null) StorageSnapshot.writeJson(marker, next + "\n");
            finishBackup(directory, next);
            return target;
        }
        String sourceType = configuration.getString("storage.migrate-from", "");
        if (!Set.of("json", "mysql").contains(sourceType))
            throw new IllegalStateException(Messages.text("startup.backend_changed"));
        YamlConfiguration sourceConfiguration = new YamlConfiguration();
        sourceConfiguration.set("storage.type", sourceType);
        var sourceSection = configuration.getConfigurationSection("storage.migration-source.mysql");
        if (sourceSection != null) sourceSection.getValues(false).forEach((key, value) ->
                sourceConfiguration.set("storage.mysql." + key, value));
        StorageConfig source = StorageConfig.load(sourceConfiguration);
        if (!identity(source).equals(previous)) throw new IllegalStateException(Messages.text("maintenance.source_mismatch"));
        CachedStorage old = open(source, directory);
        StorageSnapshot snapshot = StorageSnapshot.capture(old, old, old);
        Path backup = directory.resolve("storage-transfer.json");
        if (Files.exists(backup)) {
            StorageSnapshot saved = StorageSnapshot.read(backup);
            // 中断后源仍须与备份一致；集合顺序不构成数据变更。
            if (!same(saved, snapshot)) throw new IllegalStateException(Messages.text("maintenance.source_mismatch"));
        } else snapshot.write(backup);
        CachedStorage target = open(selected, directory);
        snapshot.apply(target, target, target, true);
        if (!same(snapshot, StorageSnapshot.capture(target, target, target)))
            throw new IllegalStateException(Messages.text("maintenance.target_conflict"));
        StorageSnapshot.writeJson(directory.resolve("storage-transfer-completed.txt"), next + "\n");
        StorageSnapshot.writeJson(marker, next + "\n");
        // 保留已完成备份，下一次切换使用新的活动计划。
        finishBackup(directory, next);
        return target;
    }
    private static void finishBackup(Path directory, String selected) throws IOException {
        Path completed = directory.resolve("storage-transfer-completed.txt");
        if (!Files.exists(completed) || !Files.readString(completed).trim().equals(selected)) return;
        Path backup = directory.resolve("storage-transfer.json");
        if (Files.exists(backup)) Files.move(backup, directory.resolve("storage-transfer-" + java.util.UUID.randomUUID() + ".json"));
        Files.deleteIfExists(completed);
    }
    public static boolean same(StorageSnapshot first, StorageSnapshot second) {
        return Set.copyOf(first.groups()).equals(Set.copyOf(second.groups()))
                && Set.copyOf(first.maps()).equals(Set.copyOf(second.maps()))
                && Set.copyOf(first.grants()).equals(Set.copyOf(second.grants()))
                && Set.copyOf(first.operations()).equals(Set.copyOf(second.operations()));
    }
}
