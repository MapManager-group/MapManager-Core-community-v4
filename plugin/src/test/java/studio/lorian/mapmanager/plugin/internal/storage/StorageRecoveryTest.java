package studio.lorian.mapmanager.plugin.internal.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.OperationName;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class StorageRecoveryTest {
    @TempDir Path directory;
    private MapInstance map(String name) { return new MapInstance(UUID.randomUUID(), name, "team", name,
            new MapSettings(true, false, false), MapStatus.ACTIVE, null); }
    private JsonStorage storage(String name) throws Exception { return new JsonStorage(directory.resolve(name)); }
    @Test void transferPreservesEmptyGroupsCompletedOperationsAndCanResume() throws Exception {
        JsonStorage source = storage("source.json"), target = storage("target.json");
        source.saveGroup(new MapGroup("team", Set.of())); source.saveGroup(new MapGroup("empty", Set.of()));
        MapInstance map = map("arena"); source.save(map);
        UUID builder = UUID.randomUUID(), visitor = UUID.randomUUID(), op = UUID.randomUUID();
        source.add("team", MapRole.BUILDER, builder); source.add(map.id().toString(), MapRole.VISITOR, visitor);
        source.save(new OperationRecord(op, OperationName.CREATE, "arena", OperationStage.COMPLETED, null));
        StorageSnapshot snapshot = StorageSnapshot.capture(source, source, source);
        snapshot.write(directory.resolve("backup.json")); snapshot = StorageSnapshot.read(directory.resolve("backup.json"));
        target.saveGroup(new MapGroup("team", Set.of())); target.save(map); // 模拟中断。
        snapshot.apply(target, target, target, true); snapshot.apply(target, target, target, true);
        JsonStorage reopened = storage("target.json");
        assertTrue(StorageBackendMigration.same(snapshot, StorageSnapshot.capture(reopened, reopened, reopened)));
        assertNotNull(reopened.findGroup("empty")); assertEquals(OperationStage.COMPLETED, reopened.find(op).stage());
    }
    @Test void allConflictsAreCheckedBeforeAnyWrite() throws Exception {
        JsonStorage source = storage("source.json"), target = storage("target.json");
        source.saveGroup(new MapGroup("team", Set.of())); source.save(map("first")); source.save(map("arena"));
        target.saveGroup(new MapGroup("team", Set.of())); target.save(map("arena"));
        StorageSnapshot before = StorageSnapshot.capture(target, target, target);
        assertThrows(IllegalStateException.class, () -> StorageSnapshot.capture(source, source, source).apply(target, target, target, true));
        assertTrue(StorageBackendMigration.same(before, StorageSnapshot.capture(target, target, target)));
        assertNull(target.findByWorldName("first"));
    }
    @Test void orphanGrantsAndExtraTargetRecordsAreRejected() throws Exception {
        JsonStorage target = storage("target.json"); target.saveGroup(new MapGroup("unexpected", Set.of()));
        StorageSnapshot empty = new StorageSnapshot(1, List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> empty.apply(target, target, target, true));
        StorageSnapshot invalid = new StorageSnapshot(1, List.of(), List.of(),
                List.of(new StorageSnapshot.Grant("missing", MapRole.ADMIN, UUID.randomUUID())), List.of());
        assertThrows(IllegalArgumentException.class, invalid::validate);
    }
    @Test void cachedEventReadsNeverTouchBackendAndFailedWritesDoNotPublish() throws Exception {
        JsonStorage disk = storage("source.json"); disk.saveGroup(new MapGroup("team", Set.of()));
        MapInstance map = map("arena"); disk.save(map); UUID builder = UUID.randomUUID(); disk.add("team", MapRole.BUILDER, builder);
        AtomicBoolean forbidden = new AtomicBoolean();
        Object backend = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{MapRepository.class, RoleRepository.class, OperationRepository.class},
                (proxy, method, args) -> {
                    if (forbidden.get()) throw new StorageException("Backend must not be called", null);
                    try { return method.invoke(disk, args); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        CachedStorage cached = new CachedStorage((MapRepository) backend, (RoleRepository) backend, (OperationRepository) backend);
        forbidden.set(true);
        assertEquals(map, cached.findByWorldName("ARENA")); assertEquals(map, cached.findById(map.id()));
        assertEquals(Set.of(builder), cached.members("team", MapRole.BUILDER)); assertEquals(Set.of("arena"), cached.findGroup("team").worldNames());
        assertEquals(1, cached.findAll().size()); assertEquals(1, cached.allGroups().size());
        assertThrows(StorageException.class, () -> cached.delete(map.id())); assertEquals(map, cached.findById(map.id()));
    }
    @Test void cachePublishesRoleChangesAndDeletionImmediately() throws Exception {
        JsonStorage disk = storage("source.json"); CachedStorage cached = new CachedStorage(disk, disk, disk);
        cached.saveGroup(new MapGroup("team", Set.of())); MapInstance map = map("arena"); cached.save(map);
        UUID visitor = UUID.randomUUID(); cached.add(map.id().toString(), MapRole.VISITOR, visitor);
        assertEquals(Set.of(visitor), cached.members(map.id().toString(), MapRole.VISITOR));
        cached.delete(map.id()); assertNull(cached.findById(map.id())); assertTrue(cached.members(map.id().toString(), MapRole.VISITOR).isEmpty());
        cached.deleteGroup("team"); assertTrue(cached.allGroups().isEmpty());
    }
    @Test void backendChangeWithoutExplicitSourceDoesNotOpenTarget() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("storage-backend.txt"), "MYSQL:original:3306/mmc\n");
        var config = new org.bukkit.configuration.file.YamlConfiguration(); config.set("storage.type", "json");
        assertThrows(IllegalStateException.class, () -> StorageBackendMigration.openAndMigrate(config, directory));
        assertFalse(java.nio.file.Files.exists(directory.resolve("maps.json")));
    }
    @Test void completedTransferIsArchivedAfterMarkerCrashButPendingPlanIsRetained() throws Exception {
        var config = new org.bukkit.configuration.file.YamlConfiguration(); config.set("storage.type", "json");
        config.set("storage.migrate-from", "mysql");
        config.set("language", "en_US");
        config.save(directory.resolve("config.yml").toFile());
        var store = StorageBackendMigration.openAndMigrate(config, directory);
        Path plan = directory.resolve("storage-transfer.json");
        StorageSnapshot.capture(store, store, store).write(plan);
        StorageBackendMigration.openAndMigrate(config, directory);
        assertTrue(java.nio.file.Files.exists(plan)); // 未完成迁移的冻结计划不能丢弃。
        assertEquals("mysql", config.getString("storage.migrate-from"));
        assertEquals("mysql", org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(directory.resolve("config.yml").toFile()).getString("storage.migrate-from"));
        java.nio.file.Files.writeString(directory.resolve("storage-transfer-completed.txt"), "JSON\n");
        StorageBackendMigration.openAndMigrate(config, directory);
        assertFalse(java.nio.file.Files.exists(plan));
        assertFalse(java.nio.file.Files.exists(directory.resolve("storage-transfer-completed.txt")));
        assertEquals("", config.getString("storage.migrate-from"));
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals("", saved.getString("storage.migrate-from"));
        assertEquals("en_US", saved.getString("language"));
        assertEquals("json", saved.getString("storage.type"));
        String contents = java.nio.file.Files.readString(directory.resolve("config.yml"));
        StorageBackendMigration.openAndMigrate(saved, directory);
        assertEquals(contents, java.nio.file.Files.readString(directory.resolve("config.yml")));
        try (var files = java.nio.file.Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("storage-transfer-")).count());
        }
    }
    @Test void mismatchedCompletionAndFailedMigrationKeepSourceSetting() throws Exception {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("storage.type", "json");
        config.set("storage.migrate-from", "mysql");
        config.set("storage.migration-source.mysql.host", "different");
        config.set("storage.migration-source.mysql.database", "mmc");
        config.set("storage.migration-source.mysql.username", "root");
        Path file = directory.resolve("config.yml");
        config.save(file.toFile());
        String original = java.nio.file.Files.readString(file);
        Path completed = directory.resolve("storage-transfer-completed.txt");
        java.nio.file.Files.writeString(completed, "MYSQL:other:3306/mmc\n");
        StorageBackendMigration.openAndMigrate(config, directory);
        assertEquals("mysql", config.getString("storage.migrate-from"));
        assertEquals(original, java.nio.file.Files.readString(file));
        assertTrue(java.nio.file.Files.exists(completed));
        java.nio.file.Files.writeString(directory.resolve("storage-backend.txt"), "MYSQL:original:3306/mmc\n");
        assertThrows(IllegalStateException.class, () -> StorageBackendMigration.openAndMigrate(config, directory));
        assertEquals("mysql", config.getString("storage.migrate-from"));
        assertEquals(original, java.nio.file.Files.readString(file));
    }
    @Test void failedConfigSaveRetainsCompletionAndCanFinishAfterRestart() throws Exception {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("storage.type", "json");
        config.set("storage.migrate-from", "mysql");
        Path file = directory.resolve("config.yml");
        config.save(file.toFile());
        var store = StorageBackendMigration.openAndMigrate(config, directory);
        Path plan = directory.resolve("storage-transfer.json");
        StorageSnapshot.capture(store, store, store).write(plan);
        Path completed = directory.resolve("storage-transfer-completed.txt");
        java.nio.file.Files.writeString(completed, "JSON\n");
        Path original = directory.resolve("original.yml");
        java.nio.file.Files.move(file, original);
        java.nio.file.Files.createDirectory(file);
        java.nio.file.Files.writeString(file.resolve("block"), "prevent replacement");
        assertThrows(java.io.IOException.class, () -> StorageBackendMigration.openAndMigrate(config, directory));
        assertEquals("mysql", config.getString("storage.migrate-from"));
        assertTrue(java.nio.file.Files.exists(completed));
        assertFalse(java.nio.file.Files.exists(plan));
        java.nio.file.Files.delete(file.resolve("block"));
        java.nio.file.Files.delete(file);
        java.nio.file.Files.move(original, file);
        var restarted = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        StorageBackendMigration.openAndMigrate(restarted, directory);
        assertEquals("", restarted.getString("storage.migrate-from"));
        assertEquals("", org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile())
                .getString("storage.migrate-from"));
        assertFalse(java.nio.file.Files.exists(completed));
        try (var files = java.nio.file.Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("storage-transfer-")).count());
        }
    }
    @Test void cacheReindexesRenamedAndRegroupedMapsWithoutChangingOldSnapshots() throws Exception {
        JsonStorage disk = storage("source.json"); CachedStorage cached = new CachedStorage(disk, disk, disk);
        cached.saveGroup(new MapGroup("team", Set.of())); cached.saveGroup(new MapGroup("other", Set.of()));
        MapInstance original = map("arena"); cached.save(original);
        var previousMaps = cached.findAll(); var previousGroup = cached.findGroup("team");
        MapInstance moved = new MapInstance(original.id(), "renamed", "other", "Renamed", original.settings(), original.status(), null);
        cached.save(moved);
        assertNull(cached.findByWorldName("arena")); assertEquals(moved, cached.findByWorldName("RENAMED"));
        assertTrue(cached.findGroup("team").worldNames().isEmpty()); assertEquals(Set.of("renamed"), cached.findGroup("other").worldNames());
        assertEquals(List.of(original), previousMaps); assertEquals(Set.of("arena"), previousGroup.worldNames());
        assertThrows(UnsupportedOperationException.class, () -> previousMaps.clear());
    }
    @Test void cacheScopeRemovalAndGroupDeletionPreserveOtherRoles() throws Exception {
        JsonStorage disk = storage("source.json"); CachedStorage cached = new CachedStorage(disk, disk, disk);
        cached.saveGroup(new MapGroup("team", Set.of())); UUID player = UUID.randomUUID();
        assertTrue(cached.add("team", MapRole.ADMIN, player)); assertFalse(cached.add("team", MapRole.ADMIN, player));
        cached.add("team", MapRole.BUILDER, player); var old = cached.members("team", MapRole.ADMIN);
        cached.removeScope("team", MapRole.ADMIN); assertTrue(cached.members("team", MapRole.ADMIN).isEmpty());
        assertEquals(Set.of(player), old); assertEquals(Set.of(player), cached.members("team", MapRole.BUILDER));
        assertThrows(UnsupportedOperationException.class, () -> old.clear());
        cached.deleteGroup("team");
        assertNull(cached.findGroup("team"));
        assertTrue(cached.members("team", MapRole.BUILDER).isEmpty());
        assertTrue(new CachedStorage(disk, disk, disk).allGroups().isEmpty());
    }
}
