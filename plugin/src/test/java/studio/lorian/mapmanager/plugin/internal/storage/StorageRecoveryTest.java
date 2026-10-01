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
        var store = StorageBackendMigration.openAndMigrate(config, directory);
        Path plan = directory.resolve("storage-transfer.json");
        StorageSnapshot.capture(store, store, store).write(plan);
        StorageBackendMigration.openAndMigrate(config, directory);
        assertTrue(java.nio.file.Files.exists(plan)); // 未完成迁移的冻结计划不能丢弃。
        java.nio.file.Files.writeString(directory.resolve("storage-transfer-completed.txt"), "JSON\n");
        StorageBackendMigration.openAndMigrate(config, directory);
        assertFalse(java.nio.file.Files.exists(plan));
        assertFalse(java.nio.file.Files.exists(directory.resolve("storage-transfer-completed.txt")));
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
        assertThrows(IllegalArgumentException.class, () -> cached.deleteGroup("team"));
        assertNotNull(cached.findGroup("team")); assertEquals(Set.of(player), cached.members("team", MapRole.BUILDER));
        cached.removeScope("team", MapRole.BUILDER); cached.deleteGroup("team");
        assertTrue(cached.members("team", MapRole.BUILDER).isEmpty());
        assertTrue(new CachedStorage(disk, disk, disk).allGroups().isEmpty());
    }
}
