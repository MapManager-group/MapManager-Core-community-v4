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
    private org.bukkit.World loadedWorld;
    @BeforeEach void setUp() throws Exception {
        storage = new JsonStorage(directory.resolve("maps.json"));
        server = new BukkitTestServer((o, m, a) -> {
            return switch (m.getName()) {
                case "isPrimaryThread" -> primary;
                case "getWorlds" -> List.of();
                case "getWorld" -> loadedWorld;
                default -> throw new AssertionError(m.getName());
            };
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
    @Test void registrationFailuresIdentifyTheRejectedFieldWithoutPersistence() {
        String nameError = studio.lorian.mapmanager.plugin.internal.notification.Messages.text("errors.world_name", "value", "sit");
        for (var result : List.of(lifecycle.create(new CreateMapRequest("sit", null, null, null)),
                lifecycle.importWorld(new ImportMapRequest("sit", null, null, null)))) {
            assertEquals(nameError, result.detail());
            failure(result, result.operation(), FailureReason.INVALID_INPUT);
        }
        for (var input : List.of(
                new String[]{"arena_bak", null, null, "errors.blocked_world"},
                new String[]{"arena", "TEAM", null, "errors.group_id"},
                new String[]{"arena", "default", null, "errors.reserved_group"},
                new String[]{"arena", null, "A".repeat(256), "errors.alias"},
                new String[]{"arena", null, "&a", "errors.alias"})) {
            var result = lifecycle.importWorld(new ImportMapRequest(input[0], input[2], input[1], null));
            String value = input[3].equals("errors.blocked_world") ? input[0] : input[1];
            assertEquals(studio.lorian.mapmanager.plugin.internal.notification.Messages.text(input[3], "value", value), result.detail());
            failure(result, OperationName.IMPORT, FailureReason.INVALID_INPUT);
        }
        for (var entry : Map.of("seed", "errors.seed", "flat.height", "errors.flat_height").entrySet()) {
            var result = lifecycle.create(new CreateMapRequest("arena", null, null, null,
                    WorldType.FLAT, Environment.NORMAL, Map.of(entry.getKey(), "bad")));
            assertEquals(studio.lorian.mapmanager.plugin.internal.notification.Messages.text(entry.getValue(), "value", "bad"), result.detail());
            failure(result, OperationName.CREATE, FailureReason.INVALID_INPUT);
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
                Map.of("flat.height", "1.5"), Map.of("flat.height", "2", "generator-settings", "{}"))) {
            failure(lifecycle.create(new CreateMapRequest("arena", null, null, null, WorldType.FLAT, Environment.NORMAL, params)),
                    OperationName.CREATE, FailureReason.INVALID_INPUT);
        }
    }
    @Test void malformedFlatJsonReturnsInvalidInputWithoutPendingData() {
        for (String settings : List.of("{broken", "[]", "null", "{\"layers\":false}",
                "{\"surface_y\":\"invalid\",\"layers\":[{\"block\":\"stone\",\"height\":4}]}"))
            failure(lifecycle.create(new CreateMapRequest("arena", null, null, null, WorldType.FLAT, Environment.NETHER,
                    Map.of("generator-settings", settings))), OperationName.CREATE, FailureReason.INVALID_INPUT);
    }

    @Test void backendFailureKeepsTargetHeightDetailAndPendingState() throws Exception {
        String detail = "flat.height=-60 is outside the world's actual available Y range 0 to 255";
        var backend = org.mvplugins.multiverse.core.utils.result.Attempt.failure(
                org.mvplugins.multiverse.core.world.reasons.WorldCreatorFailureReason.BUKKIT_CREATION_FAILED,
                org.mvplugins.multiverse.core.locale.message.MessageReplacement.Replace.ERROR.with(new IllegalArgumentException(detail)))
                .transform(org.mvplugins.multiverse.core.world.reasons.CreateFailureReason.WORLD_CREATOR_FAILED);
        var method = WorldLifeCycleServiceImpl.class.getDeclaredMethod("backendFailed",
                UUID.class, String.class, OperationName.class, org.mvplugins.multiverse.core.utils.result.Attempt.class);
        method.setAccessible(true);
        UUID id = UUID.randomUUID();
        var result = (OperationResult<?>) method.invoke(lifecycle, id, "arena", OperationName.CREATE, backend);
        assertEquals(detail, result.detail());
        assertInstanceOf(OperationResult.Failure.class, result);
        storage.saveGroup(new MapGroup("team", Set.of()));
        var pending = new MapInstance(UUID.randomUUID(), "arena", "team", "Arena",
                new MapSettings(false, false, false), MapStatus.PENDING, null);
        storage.save(pending);
        result = (OperationResult<?>) method.invoke(lifecycle, id, "arena", OperationName.CREATE, backend);
        var partial = assertInstanceOf(OperationResult.Partial.class, result);
        assertEquals(detail, partial.detail());
        assertEquals(pending, partial.value());
        assertEquals(OperationStage.FAILED, storage.allOperations().iterator().next().stage());
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
        assertEquals(FailureReason.INVALID_MAP_STATE, result.reason());
        assertEquals(map, storage.findById(map.id()));
        assertTrue(storage.allOperations().isEmpty());
    }
    @Test void deletionWithPlayersIsRejectedBeforeBackendOrStorageChanges() {
        var map = activeMap();
        var player = BukkitTestServer.proxy(org.bukkit.entity.Player.class,
                (o, m, a) -> { throw new AssertionError(m.getName()); });
        loadedWorld = BukkitTestServer.proxy(org.bukkit.World.class, (o, m, a) -> {
            assertEquals("getPlayers", m.getName());
            return List.of(player);
        });
        assertDeleteRejected(map, FailureReason.WORLD_HAS_PLAYERS);
    }
    @Test void deletionRejectsConflictingRecoveryTypeAndMapIdentity() {
        var map = activeMap();
        var journal = new RecoveryJournal(directory.resolve("world-recovery"));
        for (OperationName operation : List.of(OperationName.CREATE, OperationName.IMPORT, OperationName.DELETE)) {
            var recorded = operation == OperationName.DELETE
                    ? new MapInstance(UUID.randomUUID(), map.worldName(), map.groupId(), map.displayName(),
                            map.settings(), map.status(), map.spawn()) : map;
            var plan = new RecoveryJournal.Plan(UUID.randomUUID(), operation, recorded,
                    operation == OperationName.CREATE ? new CreateMapRequest(map.worldName(), null, "team", null) : null,
                    operation == OperationName.IMPORT ? new ImportMapRequest(map.worldName(), null, "team", null) : null,
                    null, null, null, null, null, Set.of());
            journal.save(plan);
            assertDeleteRejected(map, FailureReason.RECOVERY_CONFLICT);
            assertEquals(plan, journal.read(map.worldName()));
        }
    }
    private MapInstance activeMap() {
        storage.saveGroup(new MapGroup("team", Set.of()));
        var map = new MapInstance(UUID.randomUUID(), "test0107", "team", "Test",
                new MapSettings(false, false, false), MapStatus.ACTIVE, null);
        storage.save(map);
        return map;
    }
    private void assertDeleteRejected(MapInstance map, FailureReason reason) {
        var result = assertInstanceOf(OperationResult.Failure.class, lifecycle.delete(map.worldName()));
        assertEquals(OperationName.DELETE, result.operation());
        assertEquals(reason, result.reason());
        assertNotNull(result.operationId());
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
