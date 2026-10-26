package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import studio.lorian.mapmanager.plugin.internal.world.WorldGatewayImpl;
import studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class FailedCreationRetryTest {
    @TempDir Path directory;
    private BukkitTestServer server;
    private CachedStorage storage;
    private RecoveryJournal journal;
    private WorldLifeCycleServiceImpl lifecycle;

    @BeforeEach void setUp() throws Exception {
        var json = new JsonStorage(directory.resolve("maps.json"));
        storage = new CachedStorage(json, json, json);
        journal = new RecoveryJournal(directory.resolve("world-recovery"));
        var primary = BukkitTestServer.proxy(org.bukkit.World.class, (o, m, a) -> {
            assertEquals("getName", m.getName());
            return "world";
        });
        server = new BukkitTestServer((o, m, a) -> {
            assertEquals("getWorlds", m.getName());
            return List.of(primary);
        });
        lifecycle = new WorldLifeCycleServiceImpl(storage, storage, storage, null, null, null,
                Pattern.compile("(?!)"), Logger.getLogger("retry"), directory);
        var gateway = WorldLifeCycleServiceImpl.class.getDeclaredField("worldGateway");
        gateway.setAccessible(true);
        gateway.set(lifecycle, new WorldGatewayImpl(directory));
    }

    @AfterEach void tearDown() throws Exception { server.close(); }

    private RecoveryJournal.Plan failedPlan(String name) {
        storage.saveGroup(new MapGroup("team", Set.of()));
        var map = new MapInstance(UUID.randomUUID(), name, "team", name,
                new MapSettings(false, false, false), MapStatus.PENDING, null);
        storage.save(map);
        var request = new CreateMapRequest(name, name, "team", UUID.randomUUID());
        var plan = new RecoveryJournal.Plan(UUID.randomUUID(), OperationName.CREATE, map, request, null,
                null, null, null, null, null, Set.of());
        journal.save(plan);
        storage.save(new OperationRecord(plan.operationId(), OperationName.CREATE, name,
                OperationStage.FAILED, FailureReason.WORLD_BACKEND_FAILED));
        return plan;
    }

    private void assertRetained(RecoveryJournal.Plan plan) {
        assertEquals(plan.map(), storage.findByWorldName(plan.map().worldName()));
        assertEquals(plan, journal.read(plan.map().worldName()));
    }

    @Test void failedCreationReleasesNameAndRecoveryWithoutChangingSharedGroup() throws Exception {
        var plan = failedPlan("arena");
        UUID member = UUID.randomUUID();
        storage.add("team", MapRole.ADMIN, member);
        var other = new MapInstance(UUID.randomUUID(), "other", "team", "Other", plan.map().settings(), MapStatus.ACTIVE, null);
        storage.save(other);
        assertTrue(lifecycle.discardFailedCreation(plan, name -> false));
        assertNull(storage.findByWorldName("arena"));
        assertNull(journal.read("arena"));
        assertEquals(OperationStage.FAILED, storage.find(plan.operationId()).stage());
        assertEquals(Set.of("other"), storage.findGroup("team").worldNames());
        assertEquals(Set.of(member), storage.members("team", MapRole.ADMIN));
        var retried = new MapInstance(UUID.randomUUID(), "arena", "team", "Retry", plan.map().settings(), MapStatus.PENDING, null);
        storage.save(retried);
        assertEquals(retried, storage.findByWorldName("arena"));
        assertEquals(retried, new JsonStorage(directory.resolve("maps.json")).findByWorldName("arena"));
    }

    @Test void existingDirectoryFileAndDanglingSymlinkPreserveRecovery() throws Exception {
        var plan = failedPlan("arena");
        Path folder = directory.resolve("arena");
        Files.createDirectory(folder);
        assertFalse(lifecycle.discardFailedCreation(plan, name -> false));
        assertRetained(plan);
        Files.delete(folder);
        Files.writeString(folder, "keep");
        assertFalse(lifecycle.discardFailedCreation(plan, name -> false));
        assertRetained(plan);
        Files.delete(folder);
        Files.createSymbolicLink(folder, directory.resolve("missing"));
        assertFalse(lifecycle.discardFailedCreation(plan, name -> false));
        assertRetained(plan);
    }

    @Test void stagingDirectoryAndRegisteredWorldPreserveRecovery() throws Exception {
        Path staging = Files.createTempDirectory(Path.of("").toAbsolutePath(), "retry-");
        try {
            var plan = failedPlan(staging.getFileName().toString());
            assertFalse(lifecycle.discardFailedCreation(plan, name -> false));
            assertRetained(plan);
        } finally {
            Files.delete(staging);
        }
        var registered = failedPlan("arena");
        assertFalse(lifecycle.discardFailedCreation(registered, name -> true));
        assertRetained(registered);
    }

    @Test void interruptedAndCompletedOperationsAreNeverDiscarded() {
        var plan = failedPlan("arena");
        for (var stage : List.of(OperationStage.STARTED, OperationStage.WORLD_CHANGED,
                OperationStage.DATA_SAVED, OperationStage.COMPLETED)) {
            storage.save(new OperationRecord(plan.operationId(), OperationName.CREATE, "arena", stage, null));
            assertFalse(lifecycle.discardFailedCreation(plan, name -> false));
            assertRetained(plan);
        }
    }

    @Test void activeOrDifferentMapIdentityCannotBeDiscarded() {
        var plan = failedPlan("arena");
        for (var map : List.of(
                new MapInstance(plan.map().id(), "arena", "team", "Arena", plan.map().settings(), MapStatus.ACTIVE, null),
                new MapInstance(plan.map().id(), "arena", "team", "Arena", plan.map().settings(), MapStatus.PENDING,
                        new SpawnPoint("arena", 0, 65, 0, 0, 0)),
                new MapInstance(UUID.randomUUID(), "arena", "team", "Arena", plan.map().settings(), MapStatus.PENDING, null))) {
            storage.delete(plan.map().id());
            storage.save(map);
            assertFalse(lifecycle.discardFailedCreation(plan, name -> false));
            assertEquals(map, storage.findByWorldName("arena"));
            assertEquals(plan, journal.read("arena"));
        }
    }

    @Test void importAndMissingFailureRecordDoNotReleaseNames() {
        var plan = failedPlan("arena");
        var imported = new RecoveryJournal.Plan(plan.operationId(), OperationName.IMPORT, plan.map(), null,
                new ImportMapRequest("arena", "Arena", "team", null), null, null, null, null, null, Set.of());
        assertFalse(lifecycle.discardFailedCreation(imported, name -> false));
        var unrecorded = new RecoveryJournal.Plan(UUID.randomUUID(), OperationName.CREATE, plan.map(), plan.create(),
                null, null, null, null, null, null, Set.of());
        assertFalse(lifecycle.discardFailedCreation(unrecorded, name -> false));
        assertRetained(plan);
    }

    @Test void interruptedCleanupWithMapAlreadyRemovedCanFinish() {
        var plan = failedPlan("arena");
        storage.delete(plan.map().id());
        assertTrue(lifecycle.discardFailedCreation(plan, name -> false));
        assertNull(journal.read("arena"));
        assertNull(storage.findByWorldName("arena"));
    }
}
