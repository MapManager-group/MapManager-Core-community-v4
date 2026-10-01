package studio.lorian.mapmanager.plugin.internal.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class V3MigrationRecoveryTest {
    @TempDir Path directory;
    private Path source() throws Exception {
        Path source = directory.resolve("v3"); Files.createDirectories(source);
        Files.writeString(source.resolve("worlds.json"), "{\"arena\":{\"group\":\"team\",\"visitors\":[\"Guest\"]}}");
        Files.writeString(source.resolve("groups.json"), "{\"team\":{\"worlds\":[\"arena\"],\"admins\":[\"Alice\"],\"builders\":[\"Bob\"]}}");
        return source;
    }
    private PlayerGateway players = new PlayerGateway() {
        @Override public CompletableFuture<UUID> resolveId(String name) { return CompletableFuture.completedFuture(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        @Override public boolean isOnline(UUID id) { return false; }
        @Override public String currentWorld(UUID id) { return null; }
        @Override public boolean teleport(UUID id, SpawnPoint spawn) { return false; }
        @Override public boolean disconnect(UUID id, String reason) { return false; }
    };
    @Test void dryRunReportsDiffsWithoutWritingDestinationOrCompletionMarker() throws Exception {
        Path target = directory.resolve("v4"); var storage = new JsonStorage(target.resolve("maps.json"));
        new V3Migration(source(), target, storage, storage, players, map -> map).migrate(true).get(3, TimeUnit.SECONDS);
        assertTrue(storage.findAll().isEmpty()); assertTrue(storage.allGroups().isEmpty());
        assertTrue(Files.exists(target.resolve("v3-migration-report.json")));
        assertFalse(Files.exists(target.resolve("v3-migration-plan.json"))); assertFalse(Files.exists(target.resolve("v3-migration.done")));
        assertFalse(Files.exists(target.resolve("v3-source-backup")));
    }
    @Test void partialWriteResumesFromFrozenPlanWithoutReResolvingUsers() throws Exception {
        Path source = source(), target = directory.resolve("v4"); var storage = new JsonStorage(target.resolve("maps.json"));
        RoleRepository failing = new RoleRepository() {
            int writes;
            @Override public Set<UUID> members(String scope, MapRole role) { return storage.members(scope, role); }
            @Override public boolean add(String scope, MapRole role, UUID player) {
                if (++writes == 2) throw new StorageException("Injected failure", null); return storage.add(scope, role, player);
            }
            @Override public boolean remove(String scope, MapRole role, UUID player) { return storage.remove(scope, role, player); }
            @Override public void removeScope(String scope, MapRole role) { storage.removeScope(scope, role); }
        };
        assertThrows(ExecutionException.class, () -> new V3Migration(source, target, storage, failing, players, map -> map).migrate().get(3, TimeUnit.SECONDS));
        assertEquals(1, storage.findAll().size()); assertFalse(Files.exists(target.resolve("v3-migration.done")));
        assertTrue(Files.exists(target.resolve("v3-target-backup.json")));
        var resumed = new V3Migration(source, target, storage, storage, players, map -> { throw new AssertionError("Prepared plan must be reused"); });
        resumed.migrate().get(3, TimeUnit.SECONDS);
        StorageSnapshot plan = StorageSnapshot.read(target.resolve("v3-migration-plan.json"));
        assertTrue(StorageBackendMigration.same(plan, StorageSnapshot.capture(storage, storage, storage)));
        assertTrue(Files.exists(target.resolve("v3-migration.done")));
    }
    @Test void sourceChangeAfterPartialMigrationIsRejected() throws Exception {
        Path source = source(), target = directory.resolve("v4"); var storage = new JsonStorage(target.resolve("maps.json"));
        new V3Migration(source, target, storage, storage, players, map -> map).migrate().get(3, TimeUnit.SECONDS);
        Files.delete(target.resolve("v3-migration.done")); Files.writeString(source.resolve("worlds.json"), "{}");
        StorageSnapshot before = StorageSnapshot.capture(storage, storage, storage);
        assertThrows(ExecutionException.class, () -> new V3Migration(source, target, storage, storage, players, map -> map).migrate().get(3, TimeUnit.SECONDS));
        assertTrue(StorageBackendMigration.same(before, StorageSnapshot.capture(storage, storage, storage)));
    }
    @Test void preflightReportsConflictAndLeavesOriginalDataUnchanged() throws Exception {
        Path target = directory.resolve("v4"), source = source(); var storage = new JsonStorage(target.resolve("maps.json"));
        storage.saveGroup(new MapGroup("team", Set.of()));
        storage.save(new MapInstance(UUID.randomUUID(), "arena", "team", "existing", new MapSettings(true, true, true), MapStatus.ACTIVE, null));
        StorageSnapshot before = StorageSnapshot.capture(storage, storage, storage);
        assertThrows(ExecutionException.class, () -> new V3Migration(source, target, storage, storage, players, map -> map).migrate(true).get(3, TimeUnit.SECONDS));
        assertTrue(Files.exists(target.resolve("v3-migration-report.json")));
        assertTrue(StorageBackendMigration.same(before, StorageSnapshot.capture(storage, storage, storage)));
    }
}
