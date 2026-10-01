package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.api.service.*;
import studio.lorian.mapmanager.plugin.internal.notification.*;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;
import studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class MapWorldCommandTest {
    private final UUID playerId = UUID.randomUUID();
    private MapInstance map = new MapInstance(UUID.randomUUID(), "arena", "team", "Arena", new MapSettings(true, false, false), MapStatus.ACTIVE, null);
    private MapRole role = MapRole.ADMIN;
    private boolean failRead;
    private final List<String> calls = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private OperationResult<?> result;
    private String key;
    private MapWorldCommand worldCommand, wtp;
    private Player player;
    private BukkitTestServer server;
    @BeforeEach void setUp() throws Exception {
        World world = proxy(World.class, (o, m, a) -> "arena");
        player = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getName" -> "Builder"; case "getUniqueId" -> playerId; case "getWorld" -> world;
            case "getLocation" -> new Location(world, 10, 70, 20, 90, 15);
            case "sendMessage" -> null; default -> throw new AssertionError(m);
        });
        server = new BukkitTestServer((o, m, a) -> { assertEquals("getWorld", m.getName()); return world; });
        MapService maps = proxy(MapService.class, (o, m, a) -> {
            if (failRead) throw new StorageException("injected", null); return map;
        });
        MapAccessService access = proxy(MapAccessService.class, (o, m, a) ->
                m.getName().equals("accessibleMaps") ? List.of(map) : new AccessDecision(true, role, null));
        MapSettingsService settings = proxy(MapSettingsService.class, (o, m, a) -> {
            calls.add(m.getName()); assertEquals(map.id(), a[0]); values.add(a[1]);
            return new OperationResult.Success<>(OperationName.SET_PHYSICAL, UUID.randomUUID(), null);
        });
        MapTravelService travel = proxy(MapTravelService.class, (o, m, a) -> {
            calls.add(m.getName()); assertEquals(playerId, a[0]); assertEquals(map.id(), a[1]);
            return new OperationResult.Success<>(OperationName.TELEPORT, UUID.randomUUID(), null);
        });
        OperationNotifier notifier = proxy(OperationNotifier.class, (o, m, a) -> {
            if (m.getName().equals("notifyKey")) key = (String) a[1];
            else if (a[1] instanceof OperationResult<?> operationResult) result = operationResult;
            return null;
        });
        worldCommand = new MapWorldCommand(null, maps, null, access, settings, travel, null, notifier, null);
        wtp = new MapWorldCommand("wtp", null, maps, null, access, settings, travel, null, notifier, null);
    }
    @AfterEach void tearDown() throws Exception { server.close(); }
    @Test void worldTeleportAndWtpUseSameTravelService() {
        worldCommand.execute(player, "world", new String[]{"tp", "arena"});
        wtp.execute(player, "wtp", new String[]{"arena"}); assertEquals(List.of("teleport", "teleport"), calls);
        wtp.execute(player, "wtp", new String[]{});
        assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class, result).reason());
    }
    @Test void administratorSettingCommandsRouteBooleanNameAndSpawn() {
        worldCommand.execute(player, "world", new String[]{"physics", "false"});
        worldCommand.execute(player, "world", new String[]{"explosion", "TRUE"});
        worldCommand.execute(player, "world", new String[]{"public", "on"});
        worldCommand.execute(player, "world", new String[]{"setname", "My", "Arena"});
        worldCommand.execute(player, "world", new String[]{"setspawn"});
        assertEquals(List.of("setPhysical", "setExplosion", "setPublic", "setDisplayName", "setSpawn"), calls);
        assertEquals(List.of(false, true, true, "My Arena", new SpawnPoint("arena", 10, 70, 20, 90, 15)), values);
    }
    @Test void nonAdministratorCannotWriteSettings() {
        role = MapRole.BUILDER;
        for (String[] args : List.of(new String[]{"physics", "false"}, new String[]{"explosion", "true"},
                new String[]{"public", "true"}, new String[]{"setname", "Other"}, new String[]{"setspawn"})) {
            worldCommand.execute(player, "world", args);
            assertEquals(FailureReason.NO_PERMISSION, assertInstanceOf(OperationResult.Failure.class, result).reason());
        }
        assertTrue(calls.isEmpty());
    }
    @Test void infoAndInvalidSettingValuesDoNotWrite() {
        worldCommand.execute(player, "world", new String[]{"physics", "info"}); assertEquals("command.setting_info", key);
        worldCommand.execute(player, "world", new String[]{"public", "status"}); assertEquals("command.setting_info", key);
        worldCommand.execute(player, "world", new String[]{"explosion", "invalid"}); assertEquals("command.setting_usage", key);
        worldCommand.execute(player, "world", new String[]{"public", "invalid"});
        assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class, result).reason());
        assertTrue(calls.isEmpty());
    }
    @Test void missingMapAndStorageFailureAreReportedAndCompletionFailsClosed() {
        map = null; worldCommand.execute(player, "world", new String[]{"tp", "missing"});
        assertEquals(FailureReason.MAP_NOT_FOUND, assertInstanceOf(OperationResult.Failure.class, result).reason());
        failRead = true; worldCommand.execute(player, "world", new String[]{"physics", "false"});
        assertEquals(FailureReason.STORAGE_FAILED, assertInstanceOf(OperationResult.Failure.class, result).reason());
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{""}).isEmpty()); assertTrue(calls.isEmpty());
    }
    @Test void completionFiltersManagementCommandsByRole() {
        role = MapRole.VISITOR;
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{""}).containsAll(List.of("tp", "admins", "builders", "visitors")));
        assertFalse(worldCommand.tabComplete(player, "world", new String[]{""}).contains("setspawn"));
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{"physics", ""}).isEmpty());
        assertEquals(List.of("arena"), wtp.tabComplete(player, "wtp", new String[]{"ar"}));
        role = MapRole.ADMIN; assertTrue(worldCommand.tabComplete(player, "world", new String[]{""}).contains("setspawn"));
        assertEquals(List.of("false", "info", "true"), worldCommand.tabComplete(player, "world", new String[]{"physics", ""}));
    }
}
