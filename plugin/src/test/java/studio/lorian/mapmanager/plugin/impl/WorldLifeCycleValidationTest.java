package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class WorldLifeCycleValidationTest {
    @TempDir Path directory;
    private BukkitTestServer server;
    private JsonStorage storage;
    private WorldLifeCycleServiceImpl lifecycle;
    private boolean primary = true;
    @BeforeEach void setUp() throws Exception {
        storage = new JsonStorage(directory.resolve("maps.json"));
        server = new BukkitTestServer((o, m, a) -> {
            assertEquals("isPrimaryThread", m.getName()); return primary;
        });
        lifecycle = new WorldLifeCycleServiceImpl(storage, storage, storage, null, null, null,
                Pattern.compile("(?:.*_bak|lobby|temp.*)"), Logger.getLogger("validation"), directory);
    }
    @AfterEach void tearDown() throws Exception { server.close(); }
    private void failure(OperationResult<?> result, OperationName operation, FailureReason reason) {
        var failure = assertInstanceOf(OperationResult.Failure.class, result);
        assertEquals(operation, failure.operation()); assertEquals(reason, failure.reason()); assertNotNull(failure.operationId());
        assertTrue(storage.findAll().isEmpty()); assertTrue(storage.allGroups().isEmpty()); assertTrue(storage.allOperations().isEmpty());
    }
    @Test void createAndImportRejectInvalidAndReservedNamesBeforePersistence() {
        for (String name : new String[]{null, "abc", "a".repeat(17), "Arena", "a b c", "../arena", "world", "worldbase",
                "default", "plugins", "mapmanager123", "arena_bak", "lobby", "temp-arena"}) {
            failure(lifecycle.create(new CreateMapRequest(name, null, null, null)), OperationName.CREATE, FailureReason.INVALID_INPUT);
            failure(lifecycle.importWorld(new ImportMapRequest(name, null, null, null)), OperationName.IMPORT, FailureReason.INVALID_INPUT);
        }
    }
    @Test void invalidGroupsAndLongDisplayNamesAreRejected() {
        for (String group : new String[]{"default", "worldbase", "TEAM", "a".repeat(17), "../team"}) {
            failure(lifecycle.create(new CreateMapRequest("arena", "Arena", group, null)), OperationName.CREATE, FailureReason.INVALID_INPUT);
        }
        failure(lifecycle.create(new CreateMapRequest("arena", "A".repeat(256), "team", null)), OperationName.CREATE, FailureReason.INVALID_INPUT);
    }
    @Test void invalidCreationParametersNeverLeavePendingMaps() {
        for (Map<String, String> params : List.of(Map.of("unknown", "1"), Map.of("seed", " "), Map.of("seed", "x"),
                Map.of("seed", "1".repeat(4097)), Map.of("structures", "TRUE"), Map.of("property.", "1"),
                Map.of("flat.height", "1"), Map.of("flat.height", "2", "generator-settings", "{}"))) {
            failure(lifecycle.create(new CreateMapRequest("arena", null, null, null, WorldType.FLAT, Environment.NORMAL, params)),
                    OperationName.CREATE, FailureReason.INVALID_INPUT);
        }
    }
    @Test void reservedEnvironmentIsRejectedForBothRequests() {
        failure(lifecycle.create(new CreateMapRequest("arena", null, null, null, WorldType.FLAT, Environment.SIFT, Map.of())),
                OperationName.CREATE, FailureReason.UNSUPPORTED_ENVIRONMENT);
        failure(lifecycle.importWorld(new ImportMapRequest("arena", null, null, null, WorldType.NORMAL, Environment.SIFT)),
                OperationName.IMPORT, FailureReason.UNSUPPORTED_ENVIRONMENT);
    }
    @Test void apiDeleteWithoutBackupSuffixStillChecksMapStatus() throws Exception {
        storage.saveGroup(new MapGroup("team", Set.of()));
        var map = new MapInstance(UUID.randomUUID(), "test0107", "team", "Test",
                new MapSettings(true, false, false), MapStatus.PENDING, null);
        storage.save(map);
        var result = assertInstanceOf(OperationResult.Failure.class, lifecycle.delete("test0107"));
        assertEquals(FailureReason.OPERATION_IN_PROGRESS, result.reason());
        assertEquals(map, storage.findById(map.id()));
        assertTrue(storage.allOperations().isEmpty());
    }
    @Test void offThreadLifecycleCallsAndUnsafeDeleteAreRejected() {
        primary = false;
        failure(lifecycle.create(new CreateMapRequest("arena", null, null, null)), OperationName.CREATE, FailureReason.INVALID_INPUT);
        failure(lifecycle.importWorld(new ImportMapRequest("arena", null, null, null)), OperationName.IMPORT, FailureReason.INVALID_INPUT);
        failure(lifecycle.initialize("arena"), OperationName.INITIALIZE, FailureReason.INVALID_INPUT);
        failure(lifecycle.delete("arena_bak"), OperationName.DELETE, FailureReason.INVALID_INPUT);
        failure(lifecycle.load("arena"), OperationName.LOAD, FailureReason.INVALID_INPUT);
        failure(lifecycle.recover("arena"), OperationName.RECOVER, FailureReason.INVALID_INPUT);
        primary = true;
        failure(lifecycle.delete("arena"), OperationName.DELETE, FailureReason.MAP_NOT_FOUND);
        failure(lifecycle.delete("missing_bak"), OperationName.DELETE, FailureReason.MAP_NOT_FOUND);
        failure(lifecycle.recover("arena"), OperationName.RECOVER, FailureReason.INVALID_INPUT);
        failure(lifecycle.initialize("arena"), OperationName.INITIALIZE, FailureReason.MAP_NOT_FOUND);
        failure(lifecycle.load("arena"), OperationName.LOAD, FailureReason.MAP_NOT_FOUND);
    }
}
