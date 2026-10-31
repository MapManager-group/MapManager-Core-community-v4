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
    private boolean allowed = true;
    private final List<MapRole> listedRoles = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private OperationResult<?> result;
    private String key;
    private List<Object> keyArguments;
    private int mapReads;
    private int accessReads;
    private final List<Object> messages = new ArrayList<>();
    private boolean settingFails;
    private boolean settingPartial;
    private final List<Runnable> accessChecks = new ArrayList<>();
    private MapWorldCommand worldCommand, wtp;
    private Player player;
    private BukkitTestServer server;
    @BeforeEach void setUp() throws Exception {
        World world = proxy(World.class, (o, m, a) -> "arena");
        player = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getName" -> "Builder"; case "getUniqueId" -> playerId; case "getWorld" -> world;
            case "getLocation" -> new Location(world, 10, 70, 20, 90, 15);
            case "sendMessage" -> { messages.add(a[0]); yield null; } default -> throw new AssertionError(m);
        });
        var scheduler = proxy(org.bukkit.scheduler.BukkitScheduler.class, (o, m, a) -> {
            assertEquals("runTask", m.getName()); accessChecks.add((Runnable) a[1]); return null;
        });
        server = new BukkitTestServer((o, m, a) -> switch (m.getName()) {
            case "getWorld" -> world;
            case "getOnlinePlayers" -> List.of(player);
            case "getScheduler" -> scheduler;
            default -> throw new AssertionError(m);
        });
        MapService maps = proxy(MapService.class, (o, m, a) -> {
            mapReads++;
            if (failRead) throw new StorageException("injected", null); return map;
        });
        MapAccessService access = proxy(MapAccessService.class, (o, m, a) -> {
            accessReads++;
            return m.getName().equals("accessibleMaps") ? List.of(map)
                    : new AccessDecision(allowed, role, allowed ? null : FailureReason.NO_PERMISSION);
        });
        MapSettingsService settings = proxy(MapSettingsService.class, (o, m, a) -> {
            calls.add(m.getName()); assertEquals(map.id(), a[0]); values.add(a[1]);
            return settingFails
                    ? new OperationResult.Failure<>(OperationName.SET_PHYSICAL, UUID.randomUUID(), FailureReason.STORAGE_FAILED)
                    : settingPartial ? new OperationResult.Partial<>(OperationName.SET_PUBLIC, UUID.randomUUID(), null, FailureReason.WORLD_BACKEND_FAILED)
                    : new OperationResult.Success<>(OperationName.SET_PHYSICAL, UUID.randomUUID(), null);
        });
        MapTravelService travel = proxy(MapTravelService.class, (o, m, a) -> {
            calls.add(m.getName()); assertEquals(playerId, a[0]); assertEquals(map.id(), a[1]);
            return new OperationResult.Success<>(OperationName.TELEPORT, UUID.randomUUID(), null);
        });
        OperationNotifier notifier = proxy(OperationNotifier.class, (o, m, a) -> {
            if (m.getName().equals("notifyKey")) {
                key = (String) a[1]; keyArguments = Arrays.asList((Object[]) a[2]);
            }
            else if (a[1] instanceof OperationResult<?> operationResult) result = operationResult;
            return null;
        });
        MapRoleService roles = proxy(MapRoleService.class, (o, m, a) -> {
            assertEquals("assignedMembers", m.getName());
            assertEquals(map.id(), a[0]); listedRoles.add((MapRole) a[1]); return Set.of();
        });
        worldCommand = new MapWorldCommand(null, maps, roles, access, settings, travel, null, notifier,
                new studio.lorian.mapmanager.plugin.internal.listener.MapAccessListener(null, maps, access, true));
        wtp = new MapWorldCommand("wtp", null, maps, null, access, settings, travel, null, notifier, null);
    }
    @AfterEach void tearDown() throws Exception { server.close(); }
    @Test void nameCompletionQueriesAndAuthorizesTheMapOnce() {
        assertEquals(List.of("Arena"), worldCommand.tabComplete(player, "world", new String[]{"setname", ""}));
        assertEquals(1, mapReads);
        assertEquals(1, accessReads);
    }

    @Test void closingPublicAccessSchedulesRealListenerChecksAfterSuccessOrPartialOnly() {
        worldCommand.execute(player, "world", new String[]{"public", "false"});
        assertEquals(1, accessChecks.size());
        settingPartial = true;
        worldCommand.execute(player, "world", new String[]{"public", "off"});
        assertEquals(2, accessChecks.size());
        assertInstanceOf(OperationResult.Partial.class, result);
        settingFails = true;
        worldCommand.execute(player, "world", new String[]{"public", "no"});
        assertEquals(2, accessChecks.size());
        assertInstanceOf(OperationResult.Failure.class, result);
    }

    @Test void worldTeleportAndWtpUseSameTravelService() {
        worldCommand.execute(player, "world", new String[]{"tp", "arena"});
        wtp.execute(player, "wtp", new String[]{"arena"}); assertEquals(List.of("teleport", "teleport"), calls);
        wtp.execute(player, "wtp", new String[]{});
        assertEquals(List.of("teleport", "teleport"), calls);
        assertEquals(Messages.component("command.usage_wtp"), messages.getLast());
    }
    @Test void defaultWorldTeleportAndCompletionDoNotRequireMmcRegistrationOrRole() {
        World lobby = proxy(World.class, (o, m, a) -> switch (m.getName()) {
            case "getName" -> "beachlobby";
            case "getSpawnLocation" -> new Location((World) o, 12, 75, -8, 90, 10);
            default -> throw new AssertionError(m);
        });
        List<Location> destinations = new ArrayList<>();
        boolean[] success = {true};
        Player visitor = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getName" -> "Visitor";
            case "getUniqueId" -> playerId;
            case "teleport" -> { destinations.add((Location) a[0]); yield success[0]; }
            default -> throw new AssertionError(m);
        });
        MapService maps = proxy(MapService.class, (o, m, a) -> {
            assertEquals("findByWorldName", m.getName()); return null;
        });
        MapAccessService access = proxy(MapAccessService.class, (o, m, a) -> {
            assertEquals("accessibleMaps", m.getName()); return List.of();
        });
        OperationNotifier notifier = proxy(OperationNotifier.class, (o, m, a) -> {
            result = (OperationResult<?>) a[1]; return null;
        });
        for (String name : List.of("wtp", "world")) {
            var command = new MapWorldCommand(name, null, maps, null, access, null, null, null,
                    notifier, null, () -> lobby);
            String[] args = name.equals("wtp") ? new String[]{"BEACHLOBBY"} : new String[]{"tp", "beachlobby"};
            command.execute(visitor, name, args);
            assertInstanceOf(OperationResult.Success.class, result);
            assertEquals(new Location(lobby, 12, 75, -8, 90, 10), destinations.getLast());
            assertEquals(List.of("beachlobby"), command.tabComplete(visitor, name,
                    name.equals("wtp") ? new String[]{"BEACH"} : new String[]{"tp", "BEACH"}));
            success[0] = false;
            command.execute(visitor, name, args);
            assertEquals(FailureReason.WORLD_BACKEND_FAILED, assertInstanceOf(OperationResult.Failure.class, result).reason());
            command.execute(visitor, name, name.equals("wtp") ? new String[]{"missing"} : new String[]{"tp", "missing"});
            assertEquals(FailureReason.MAP_NOT_FOUND, assertInstanceOf(OperationResult.Failure.class, result).reason());
            success[0] = true;
        }
    }

    @Test void administratorSettingCommandsRouteBooleanNameAndSpawn() {
        worldCommand.execute(player, "world", new String[]{"physics", "false"});
        assertEquals("command.physics_updated", key); assertEquals(List.of("value", false), keyArguments);
        worldCommand.execute(player, "world", new String[]{"explosion", "TRUE"});
        worldCommand.execute(player, "world", new String[]{"explosion", "TRUE", "confirm"});
        assertEquals("command.explosion_updated", key); assertEquals(List.of("value", true), keyArguments);
        worldCommand.execute(player, "world", new String[]{"public", "on"});
        assertEquals("command.public_updated", key); assertEquals(List.of("value", true), keyArguments);
        worldCommand.execute(player, "world", new String[]{"setname", "My", "Arena"});
        assertEquals("command.name_updated", key); assertEquals(List.of("value", "My Arena"), keyArguments);
        worldCommand.execute(player, "world", new String[]{"setspawn"});
        assertEquals("command.spawn_updated", key);
        assertEquals(List.of("world", "arena", "x", 10.0, "y", 70.0, "z", 20.0, "yaw", 90.0f, "pitch", 15.0f), keyArguments);
        assertEquals(List.of("setPhysical", "setExplosion", "setPublic", "setDisplayName", "setSpawn"), calls);
        assertEquals(List.of(false, true, true, "My Arena", new SpawnPoint("arena", 10, 70, 20, 90, 15)), values);
    }
    @Test void enablingRequiresPriorMatchingConfirmationAndCannotBeReplayed() {
        for (String setting : List.of("physics", "explosion")) {
            int before = calls.size();
            worldCommand.execute(player, "world", new String[]{setting, "true", "confirm"});
            assertEquals("command.setting_confirm", key); assertEquals(before, calls.size());
            worldCommand.execute(player, "world", new String[]{setting, "true", "confirm"});
            assertEquals(before + 1, calls.size()); assertEquals(true, values.getLast());
            worldCommand.execute(player, "world", new String[]{setting, "true", "confirm"});
            assertEquals(before + 1, calls.size()); assertEquals("command.setting_confirm", key);
        }
    }
    @Test void confirmationIsBoundToMapAndSetting() {
        worldCommand.execute(player, "world", new String[]{"physics", "true"});
        worldCommand.execute(player, "world", new String[]{"explosion", "true", "confirm"});
        assertTrue(calls.isEmpty());
        worldCommand.execute(player, "world", new String[]{"physics", "true"});
        map = new MapInstance(UUID.randomUUID(), map.worldName(), map.groupId(), map.displayName(), map.settings(), map.status(), null);
        worldCommand.execute(player, "world", new String[]{"physics", "true", "confirm"});
        assertTrue(calls.isEmpty()); assertEquals("command.setting_confirm", key);
    }
    @Test void confirmationRechecksAccessAndAdministratorRole() {
        worldCommand.execute(player, "world", new String[]{"physics", "true"});
        role = MapRole.BUILDER;
        worldCommand.execute(player, "world", new String[]{"physics", "true", "confirm"});
        assertEquals(FailureReason.NO_PERMISSION, assertInstanceOf(OperationResult.Failure.class, result).reason());
        role = MapRole.ADMIN; allowed = false;
        worldCommand.execute(player, "world", new String[]{"physics", "true", "confirm"});
        assertEquals(FailureReason.NO_PERMISSION, assertInstanceOf(OperationResult.Failure.class, result).reason());
        assertTrue(calls.isEmpty());
    }
    @Test void confirmationExpiresWithoutSleeping() throws Exception {
        worldCommand.execute(player, "world", new String[]{"physics", "true"});
        var field = MapWorldCommand.class.getDeclaredField("pendingSettings"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var pending = (Map<UUID, Object>) field.get(worldCommand);
        var constructor = pending.get(playerId).getClass().getDeclaredConstructor(UUID.class, OperationName.class, long.class);
        constructor.setAccessible(true);
        pending.put(playerId, constructor.newInstance(map.id(), OperationName.SET_PHYSICAL,
                System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(11)));
        worldCommand.execute(player, "world", new String[]{"physics", "true", "confirm"});
        assertTrue(calls.isEmpty()); assertEquals("command.setting_confirm", key);
    }
    @Test void confirmationCannotBeUsedByAnotherPlayerAndDisablingCancelsIt() {
        worldCommand.execute(player, "world", new String[]{"physics", "true"});
        Player other = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getName" -> "Other"; case "getUniqueId" -> UUID.nameUUIDFromBytes(new byte[]{1});
            case "getWorld" -> player.getWorld(); default -> throw new AssertionError(m);
        });
        worldCommand.execute(other, "world", new String[]{"physics", "true", "confirm"});
        assertTrue(calls.isEmpty());
        worldCommand.execute(player, "world", new String[]{"physics", "false"});
        assertEquals(List.of(false), values);
        worldCommand.execute(player, "world", new String[]{"physics", "true", "confirm"});
        assertEquals(1, calls.size()); assertEquals("command.setting_confirm", key);
    }
    @Test void failedSettingDoesNotSendSuccessTextAndInvalidConfirmationDoesNotWrite() {
        worldCommand.execute(player, "world", new String[]{"physics", "false", "confirm"});
        assertEquals("command.setting_usage", key); assertTrue(calls.isEmpty());
        key = null; settingFails = true;
        worldCommand.execute(player, "world", new String[]{"physics", "false"});
        assertNull(key); assertInstanceOf(OperationResult.Failure.class, result);
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
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{""}).containsAll(List.of("tp", "admin", "builder", "visitor")));
        assertFalse(worldCommand.tabComplete(player, "world", new String[]{""}).contains("setspawn"));
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{"physics", ""}).isEmpty());
        assertEquals(List.of("arena"), wtp.tabComplete(player, "wtp", new String[]{"ar"}));
        for (String command : List.of("admin", "builder", "visitor")) {
            assertEquals(List.of("info"), worldCommand.tabComplete(player, "world", new String[]{command, ""}));
            assertTrue(worldCommand.tabComplete(player, "world", new String[]{command, "remove", ""}).isEmpty());
            assertTrue(worldCommand.tabComplete(player, "world", new String[]{command, "info", ""}).isEmpty());
        }
        assertFalse(worldCommand.tabComplete(player, "world", new String[]{""}).contains("admins"));
        role = MapRole.ADMIN;
        assertEquals(List.of("add", "info", "remove"), worldCommand.tabComplete(player, "world", new String[]{"admin", ""}));
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{""}).contains("setspawn"));
        assertEquals(List.of("false", "info", "true"), worldCommand.tabComplete(player, "world", new String[]{"physics", ""}));
        assertEquals(List.of("confirm"), worldCommand.tabComplete(player, "world", new String[]{"physics", "true", ""}));
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{"physics", "false", ""}).isEmpty());
    }
    @Test void roleInfoListsDirectMembersForVisitorsAndValidatesAccessAndArguments() {
        role = MapRole.VISITOR;
        for (String command : List.of("admin", "builder", "visitor")) {
            worldCommand.execute(player, "world", new String[]{command, "INFO"});
            assertEquals("command.members_empty", key);
        }
        assertEquals(List.of(MapRole.ADMIN, MapRole.BUILDER, MapRole.VISITOR), listedRoles);
        worldCommand.execute(player, "world", new String[]{"admin", "info", "extra"});
        assertEquals(OperationName.LIST_MEMBERS, result.operation());
        assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class, result).reason());
        allowed = false;
        worldCommand.execute(player, "world", new String[]{"builder", "info"});
        assertEquals(FailureReason.NO_PERMISSION, assertInstanceOf(OperationResult.Failure.class, result).reason());
        assertEquals(3, listedRoles.size());
        assertTrue(worldCommand.tabComplete(player, "world", new String[]{"visitor", ""}).isEmpty());
        assertFalse(worldCommand.tabComplete(player, "world", new String[]{""}).contains("visitor"));
    }
}
