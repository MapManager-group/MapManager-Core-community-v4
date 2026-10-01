package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.api.service.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class MapTravelServiceImplTest {
    private final UUID playerId = UUID.randomUUID();
    private MapInstance map;
    private World world;
    private boolean primary = true, online = true, allowed = true, teleportSucceeds = true;
    private boolean failStorage, failAccess, materializeLoadedWorld = true;
    private MapRole effectiveRole = MapRole.ADMIN;
    private FailureReason denial;
    private OperationResult<MapInstance> loadResult;
    private int loads, teleports;
    private SpawnPoint destination;
    private final Map<UUID, Player> players = new HashMap<>();
    private MapTravelServiceImpl travel;
    private BukkitTestServer server;

    @BeforeEach void setUp() throws Exception {
        map = new MapInstance(UUID.randomUUID(), "arena", "team", "Arena", new MapSettings(true, false, false),
                MapStatus.ACTIVE, new SpawnPoint("arena", 10, 70, 20, 90, 15));
        world = world("arena");
        server = new BukkitTestServer((object, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> primary;
            case "getWorld" -> world;
            case "getPlayer" -> players.get(args[0]);
            default -> throw new AssertionError(method);
        });
        MapRepository maps = proxy(MapRepository.class, (object, method, args) -> {
            if (failStorage) throw new StorageException("injected", null);
            return map;
        });
        MapAccessService access = proxy(MapAccessService.class, (object, method, args) -> {
            if (failAccess) throw new StorageException("injected", null);
            return new AccessDecision(allowed, effectiveRole, denial);
        });
        PlayerGateway gateway = proxy(PlayerGateway.class, (object, method, args) -> switch (method.getName()) {
            case "isOnline" -> online;
            case "teleport" -> { teleports++; destination = (SpawnPoint) args[1]; yield teleportSucceeds; }
            default -> throw new AssertionError(method);
        });
        WorldLifeCycleService lifecycle = proxy(WorldLifeCycleService.class, (object, method, args) -> {
            assertEquals("load", method.getName()); assertEquals("arena", args[0]); loads++;
            if (materializeLoadedWorld && loadResult instanceof OperationResult.Success<?>) world = world("arena");
            return loadResult;
        });
        travel = new MapTravelServiceImpl(maps, access, gateway, lifecycle);
    }
    @AfterEach void tearDown() throws Exception { server.close(); }
    private World world(String name) {
        return proxy(World.class, (object, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getSpawnLocation" -> new Location((World) object, 0.5, 64, 0.5, 30, 10);
            default -> throw new AssertionError(method);
        });
    }
    private void failure(FailureReason reason) {
        var result = assertInstanceOf(OperationResult.Failure.class, travel.teleport(playerId, UUID.randomUUID()));
        assertEquals(reason, result.reason()); assertEquals(OperationName.TELEPORT, result.operation());
        assertNotNull(result.operationId()); assertEquals(0, teleports);
    }
    @Test void rejectsNullAndOffThreadRequestsBeforeSideEffects() {
        assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class,
                travel.teleport(null, map.id())).reason());
        assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class,
                travel.teleport(playerId, null)).reason());
        primary = false; failure(FailureReason.INVALID_INPUT); assertEquals(0, loads);
    }
    @Test void offlinePlayerDoesNotLoadWorld() { online = false; failure(FailureReason.PLAYER_NOT_FOUND); assertEquals(0, loads); }
    @Test void missingMapIsReported() { map = null; failure(FailureReason.MAP_NOT_FOUND); }
    @Test void repositoryAndAccessReadFailuresAreReported() {
        failStorage = true; failure(FailureReason.STORAGE_FAILED);
        failStorage = false; failAccess = true; failure(FailureReason.STORAGE_FAILED);
    }
    @Test void deniedAccessDoesNotLoadWorldAndPreservesReason() {
        world = null; allowed = false; denial = FailureReason.NO_PERMISSION; failure(denial);
        denial = null; failure(FailureReason.ACCESS_DENIED); assertEquals(0, loads);
    }
    @Test void loadedMapUsesPersistedSpawnWithoutLoadingAgain() {
        assertInstanceOf(OperationResult.Success.class, travel.teleport(playerId, map.id()));
        assertEquals(map.spawn(), destination); assertEquals(1, teleports); assertEquals(0, loads);
    }
    @Test void absentSpawnUsesWorldSpawnAndRotation() {
        map = new MapInstance(map.id(), map.worldName(), map.groupId(), map.displayName(), map.settings(), map.status(), null);
        assertInstanceOf(OperationResult.Success.class, travel.teleport(playerId, map.id()));
        assertEquals(new SpawnPoint("arena", 0.5, 64, 0.5, 30, 10), destination);
    }
    @Test void permittedUnloadedMapLoadsBeforeTeleport() {
        world = null; loadResult = new OperationResult.Success<>(OperationName.LOAD, UUID.randomUUID(), map);
        assertInstanceOf(OperationResult.Success.class, travel.teleport(playerId, map.id()));
        assertEquals(1, loads); assertEquals(map.spawn(), destination);
    }
    @Test void loadFailureAndPartialStopTeleportAndPreserveReason() {
        world = null; loadResult = new OperationResult.Failure<>(OperationName.LOAD, UUID.randomUUID(), FailureReason.WORLD_VERSION_INCOMPATIBLE);
        failure(FailureReason.WORLD_VERSION_INCOMPATIBLE);
        loadResult = new OperationResult.Partial<>(OperationName.LOAD, UUID.randomUUID(), map, FailureReason.WORLD_BACKEND_FAILED);
        failure(FailureReason.WORLD_BACKEND_FAILED); assertEquals(2, loads);
    }
    @Test void successfulLoadWithoutBukkitWorldStillStopsTeleport() {
        world = null; materializeLoadedWorld = false;
        loadResult = new OperationResult.Success<>(OperationName.LOAD, UUID.randomUUID(), map);
        failure(FailureReason.WORLD_NOT_FOUND); assertEquals(1, loads);
    }
    @Test void spawnInDifferentWorldIsRejected() { world = world("other"); failure(FailureReason.WORLD_BACKEND_FAILED); }
    @Test void failedPlayerTeleportIsReported() {
        teleportSucceeds = false;
        assertEquals(FailureReason.WORLD_BACKEND_FAILED, assertInstanceOf(OperationResult.Failure.class,
                travel.teleport(playerId, map.id())).reason()); assertEquals(1, teleports);
    }
    @Test void kickRejectsMissingPlayersDifferentWorldAndNonAdministrator() {
        UUID operator = UUID.randomUUID();
        assertEquals(FailureReason.PLAYER_NOT_FOUND, assertInstanceOf(OperationResult.Failure.class,
                travel.kickFromMap(operator, playerId)).reason());
        players.put(operator, proxy(Player.class, (o, m, a) -> world));
        World other = world("other"); players.put(playerId, proxy(Player.class, (o, m, a) -> other));
        assertEquals(FailureReason.PLAYER_NOT_IN_MAP, assertInstanceOf(OperationResult.Failure.class,
                travel.kickFromMap(operator, playerId)).reason());
        players.put(playerId, proxy(Player.class, (o, m, a) -> world)); effectiveRole = MapRole.BUILDER;
        assertEquals(FailureReason.NO_PERMISSION, assertInstanceOf(OperationResult.Failure.class,
                travel.kickFromMap(operator, playerId)).reason());
    }
}
