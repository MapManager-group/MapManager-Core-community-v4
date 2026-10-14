package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;
import studio.lorian.mapmanager.plugin.internal.storage.JsonStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapServicesTest {
    @TempDir
    Path temporaryDirectory;

    private JsonStorage storage;
    private FakeWorldBaseProjection permissions;
    private MapRoleServiceImpl roles;
    private MapAccessServiceImpl access;
    private MapSettingsServiceImpl settings;
    private MapInstance arena;
    private MapInstance arena2;
    private MapInstance publicMap;
    private MapInstance pendingMap;

    @BeforeEach
    void setUp() throws IOException {
        storage = new JsonStorage(temporaryDirectory.resolve("maps.json"));
        permissions = new FakeWorldBaseProjection();
        storage.saveGroup(new MapGroup("team", Set.of()));
        storage.saveGroup(new MapGroup("public-group", Set.of()));
        arena = map("arena", "team", new MapSettings(true, false, false), MapStatus.ACTIVE);
        arena2 = map("arena2", "team", new MapSettings(false, true, false), MapStatus.ACTIVE);
        publicMap = map("public", "public-group", new MapSettings(true, false, true), MapStatus.ACTIVE);
        pendingMap = map("pending", "public-group", new MapSettings(true, false, true), MapStatus.PENDING);
        storage.save(arena);
        storage.save(arena2);
        storage.save(publicMap);
        storage.save(pendingMap);
        roles = new MapRoleServiceImpl(storage, storage, permissions);
        access = new MapAccessServiceImpl(storage, roles);
        settings = new MapSettingsServiceImpl(storage, new MapAliasServiceImpl(storage));
    }

    @Test
    void defaultWorldAllowsEntryWithoutRolesAndKeepsSharedGroupOnOtherMaps() {
        UUID player = UUID.randomUUID();
        roles.addMember(arena2.id(), player, MapRole.ADMIN);
        var defaultRoles = new MapRoleServiceImpl(storage, storage, permissions, "arena"::equals);
        var defaultAccess = new MapAccessServiceImpl(storage, defaultRoles, "arena"::equals);
        assertTrue(defaultAccess.decide(UUID.randomUUID(), arena.id()).allowed());
        assertNull(defaultAccess.decide(player, arena.id()).effectiveRole());
        assertFalse(defaultRoles.hasRole(arena.id(), player, MapRole.ADMIN));
        assertTrue(defaultRoles.members(arena.id(), MapRole.BUILDER).isEmpty());
        assertTrue(defaultRoles.assignedMembers(arena.id(), MapRole.ADMIN).isEmpty());
        assertTrue(defaultRoles.hasRole(arena2.id(), player, MapRole.ADMIN));
        assertFalse(defaultAccess.administratedMaps(player).contains(arena));
        assertTrue(defaultAccess.administratedMaps(player).contains(arena2));
        var denied = assertInstanceOf(OperationResult.Failure.class,
                defaultRoles.addMember(arena.id(), UUID.randomUUID(), MapRole.BUILDER));
        assertEquals(FailureReason.ACCESS_DENIED, denied.reason());
        var defaultSettings = new MapSettingsServiceImpl(storage, new MapAliasServiceImpl(storage), "arena"::equals);
        assertInstanceOf(OperationResult.Failure.class, defaultSettings.setPhysical(arena.id(), false));
        assertInstanceOf(OperationResult.Failure.class, defaultSettings.setExplosion(arena.id(), true));
        assertEquals(arena, storage.findById(arena.id()));
    }

    @Test
    void defaultWorldRejectsManagementAndIgnoresHistoricalMmcSpawn() throws Exception {
        var predicate = (java.util.function.Predicate<String>) "arena"::equals;
        var world = studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy(org.bukkit.World.class,
                (o, m, a) -> switch (m.getName()) {
                    case "getName" -> "arena";
                    case "getSpawnLocation" -> new org.bukkit.Location((org.bukkit.World) o, 1, 64, 2);
                    default -> throw new AssertionError(m);
                });
        try (var server = new studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer((o, m, a) -> switch (m.getName()) {
            case "isPrimaryThread" -> true;
            case "getWorld" -> world;
            default -> throw new AssertionError(m);
        })) {
            var aliases = new MapAliasServiceImpl(storage, predicate);
            var settings = new MapSettingsServiceImpl(storage, aliases, predicate);
            assertEquals(FailureReason.ACCESS_DENIED,
                    assertInstanceOf(OperationResult.Failure.class, aliases.setAlias(arena.id(), "renamed")).reason());
            assertEquals(FailureReason.ACCESS_DENIED,
                    assertInstanceOf(OperationResult.Failure.class, settings.setPublic(arena.id(), true)).reason());
            assertInstanceOf(OperationResult.Failure.class,
                    settings.setSpawn(arena.id(), new SpawnPoint("arena", 3, 64, 3, 0, 0)));
            var lifecycle = new WorldLifeCycleServiceImpl(storage, storage, storage, permissions, aliases, null,
                    java.util.regex.Pattern.compile(".*_bak$"), java.util.logging.Logger.getAnonymousLogger(), temporaryDirectory, predicate);
            for (var result : java.util.List.of(lifecycle.initialize("arena"), lifecycle.load("arena"),
                    lifecycle.recover("arena"), lifecycle.delete("arena"))) {
                assertEquals(FailureReason.ACCESS_DENIED, assertInstanceOf(OperationResult.Failure.class, result).reason());
            }
            storage.save(new MapInstance(arena.id(), arena.worldName(), arena.groupId(), arena.displayName(),
                    arena.settings(), arena.status(), new SpawnPoint("arena", 99, 100, 99, 0, 0)));
            assertNull(settings.getSpawn(arena.id()));
            var players = studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy(
                    studio.lorian.mapmanager.api.service.PlayerGateway.class, (o, m, a) -> switch (m.getName()) {
                        case "isOnline" -> true;
                        case "teleport" -> { assertEquals(new SpawnPoint("arena", 1, 64, 2, 0, 0), a[1]); yield true; }
                        default -> throw new AssertionError(m);
                    });
            var access = new MapAccessServiceImpl(storage, roles, predicate);
            assertInstanceOf(OperationResult.Success.class,
                    new MapTravelServiceImpl(storage, access, players, lifecycle, predicate).teleport(UUID.randomUUID(), arena.id()));
        }
    }

    @Test
    void appliesGroupRoleInheritanceAndKeepsVisitorsMapScoped() {
        UUID adminId = UUID.randomUUID();
        UUID builderId = UUID.randomUUID();
        UUID visitorId = UUID.randomUUID();

        assertInstanceOf(OperationResult.Success.class, roles.addMember(arena.id(), adminId, MapRole.ADMIN));
        assertInstanceOf(OperationResult.Success.class, roles.addMember(arena.id(), builderId, MapRole.BUILDER));
        assertInstanceOf(OperationResult.Success.class, roles.addMember(arena.id(), visitorId, MapRole.VISITOR));

        assertTrue(roles.hasRole(arena2.id(), adminId, MapRole.ADMIN));
        assertTrue(roles.hasRole(arena2.id(), adminId, MapRole.BUILDER));
        assertTrue(roles.hasRole(arena2.id(), adminId, MapRole.VISITOR));
        assertTrue(roles.hasRole(arena2.id(), builderId, MapRole.VISITOR));
        assertFalse(roles.hasRole(arena2.id(), visitorId, MapRole.VISITOR));
        assertEquals(Set.of(adminId), roles.assignedMembers(arena2.id(), MapRole.ADMIN));
    }

    @Test
    void accessRequiresActiveMapAndHonorsRolesAndPublicSetting() {
        UUID adminId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        roles.addMember(arena.id(), adminId, MapRole.ADMIN);

        assertEquals(MapRole.ADMIN, access.decide(adminId, arena.id()).effectiveRole());
        assertFalse(access.decide(guestId, arena.id()).allowed());
        assertTrue(access.decide(guestId, publicMap.id()).allowed());
        assertNull(access.decide(guestId, publicMap.id()).effectiveRole());
        assertFalse(access.decide(guestId, pendingMap.id()).allowed());
        assertEquals(FailureReason.INVALID_INPUT, access.decide(null, arena.id()).denialReason());
        assertEquals(FailureReason.MAP_NOT_FOUND, access.decide(guestId, UUID.randomUUID()).denialReason());
    }

    @Test
    void removingOneGroupRolePreservesBuildProjectionWhileOtherRoleRemains() {
        UUID playerId = UUID.randomUUID();
        roles.addMember(arena.id(), playerId, MapRole.ADMIN);
        roles.addMember(arena.id(), playerId, MapRole.BUILDER);

        assertInstanceOf(OperationResult.Success.class, roles.removeMember(arena.id(), playerId, MapRole.ADMIN));
        assertTrue(permissions.lastBuilderAccess);
        assertTrue(roles.hasRole(arena2.id(), playerId, MapRole.BUILDER));

        assertInstanceOf(OperationResult.Success.class, roles.removeMember(arena2.id(), playerId, MapRole.BUILDER));
        assertFalse(permissions.lastBuilderAccess);
    }

    @Test
    void reportsPartialWhenPermissionProjectionFailsAfterMmcGrant() {
        UUID playerId = UUID.randomUUID();
        permissions.builderAccessResult = false;

        OperationResult<Void> result = roles.addMember(arena.id(), playerId, MapRole.BUILDER);

        OperationResult.Partial<?> partial = assertInstanceOf(OperationResult.Partial.class, result);
        assertEquals(FailureReason.PERMISSION_SYNC_FAILED, partial.reason());
        assertTrue(roles.hasRole(arena.id(), playerId, MapRole.BUILDER));
    }

    @Test
    void settingsWritesOnlyRequestedPhysicalOrExplosionField() {
        OperationResult<Void> physicalResult = settings.setPhysical(arena.id(), false);
        OperationResult<Void> explosionResult = settings.setExplosion(arena.id(), true);
        MapInstance updated = storage.findById(arena.id());

        assertInstanceOf(OperationResult.Success.class, physicalResult);
        assertInstanceOf(OperationResult.Success.class, explosionResult);
        assertEquals(new MapSettings(false, true, false), updated.settings());
        assertEquals(arena.displayName(), updated.displayName());
        assertEquals(arena.status(), updated.status());
        assertEquals(arena.spawn(), updated.spawn());
        assertEquals(arena2.settings(), storage.findById(arena2.id()).settings());
    }

    @Test
    void settingsRejectNullOrUnknownMapIds() {
        OperationResult<Void> nullId = settings.setPhysical(null, false);
        OperationResult<Void> unknownId = settings.setExplosion(UUID.randomUUID(), true);

        assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class, nullId).reason());
        assertEquals(FailureReason.MAP_NOT_FOUND, assertInstanceOf(OperationResult.Failure.class, unknownId).reason());
        assertEquals(arena.settings(), storage.findById(arena.id()).settings());
    }

    @Test
    void mapManagerPublishesEveryApiService() {
        var manager = new MapManagerImpl(storage, storage, storage, permissions,
                new NoopPlayerGateway(),
                java.util.regex.Pattern.compile(".*_bak$"), java.util.logging.Logger.getLogger("test"),
                new NoopWorldVersionService(), temporaryDirectory);

        assertNotNull(manager.maps());
        assertNotNull(manager.roles());
        assertNotNull(manager.access());
        assertNotNull(manager.settings());
        assertNotNull(manager.aliases());
        assertNotNull(manager.travel());
        assertNotNull(manager.worldLifeCycle());
        assertNotNull(manager.worldVersions());
        assertNotNull(manager.worldDirectories());
        assertEquals(4, manager.maps().listMaps().size());
    }

    @Test
    void publicDirectoryQueryUsesActualLoadedFolderAndDoesNotCreateUnloadedFolders() throws Exception {
        Path actual = temporaryDirectory.resolve("actual-save-location");
        var world = studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy(org.bukkit.World.class,
                (o, m, a) -> switch (m.getName()) {
                    case "getName" -> "world";
                    case "getWorldFolder" -> actual.toFile();
                    default -> throw new AssertionError(m);
                });
        try (var server = new studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer((o, m, a) -> switch (m.getName()) {
            case "getWorlds" -> java.util.List.of(world);
            case "getWorld" -> "arena".equals(a[0]) ? world : null;
            default -> throw new AssertionError(m);
        })) {
            var manager = new MapManagerImpl(storage, storage, storage, permissions, new NoopPlayerGateway(),
                    java.util.regex.Pattern.compile(".*_bak$"), java.util.logging.Logger.getLogger("test"),
                    new NoopWorldVersionService(), temporaryDirectory);
            assertEquals(actual, manager.worldDirectories().worldDirectory("arena"));
            String unloaded = "directory-probe-" + UUID.randomUUID();
            Path expected = new studio.lorian.mapmanager.plugin.internal.world.WorldGatewayImpl().worldDirectory(unloaded);
            assertEquals(expected, manager.worldDirectories().worldDirectory(unloaded));
            assertFalse(java.nio.file.Files.exists(expected));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> manager.worldDirectories().worldDirectory("../escape"));
        }
    }

    @Test
    void effectiveMemberListsDeduplicateAndKeepDirectGrantsSeparate() {
        UUID admin = UUID.randomUUID(), builder = UUID.randomUUID(), visitor = UUID.randomUUID();
        roles.addMember(arena.id(), admin, MapRole.ADMIN);
        roles.addMember(arena.id(), admin, MapRole.BUILDER);
        roles.addMember(arena.id(), builder, MapRole.BUILDER);
        roles.addMember(arena.id(), visitor, MapRole.VISITOR);
        assertEquals(Set.of(admin, builder, visitor), roles.members(arena.id(), MapRole.VISITOR));
        assertEquals(Set.of(admin, builder), roles.members(arena2.id(), MapRole.VISITOR));
        assertEquals(Set.of(visitor), roles.assignedMembers(arena.id(), MapRole.VISITOR));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> roles.members(arena.id(), MapRole.ADMIN).clear());
    }

    @Test
    void revokedGroupRolesFallBackToMapVisitorAndPublicAccess() {
        UUID player = UUID.randomUUID();
        roles.addMember(arena.id(), player, MapRole.BUILDER);
        roles.addMember(arena.id(), player, MapRole.VISITOR);
        roles.removeMember(arena.id(), player, MapRole.BUILDER);
        assertEquals(MapRole.VISITOR, access.decide(player, arena.id()).effectiveRole());
        assertFalse(access.decide(player, arena2.id()).allowed());
        roles.removeMember(arena.id(), player, MapRole.VISITOR);
        assertFalse(access.decide(player, arena.id()).allowed());
        assertTrue(access.decide(player, publicMap.id()).allowed());
    }

    @Test
    void accessListsExcludeInactiveMapsEvenForAdministrators() {
        UUID player = UUID.randomUUID();
        roles.addMember(publicMap.id(), player, MapRole.ADMIN);
        assertEquals(Set.of(publicMap), Set.copyOf(access.accessibleMaps(player)));
        assertEquals(java.util.List.of(publicMap), access.administratedMaps(player));
        assertTrue(access.accessibleMaps(null).isEmpty());
        assertTrue(access.administratedMaps(UUID.randomUUID()).isEmpty());
    }

    @Test
    void allInactiveStatusesRejectRoleChangesWithoutWriting() {
        UUID player = UUID.randomUUID();
        for (MapStatus status : MapStatus.values()) {
            if (status == MapStatus.ACTIVE) continue;
            MapInstance inactive = new MapInstance(arena.id(), arena.worldName(), arena.groupId(), arena.displayName(),
                    arena.settings(), status, arena.spawn());
            storage.save(inactive);
            assertEquals(FailureReason.ACCESS_DENIED, assertInstanceOf(OperationResult.Failure.class,
                    roles.addMember(arena.id(), player, MapRole.ADMIN)).reason());
            assertEquals(FailureReason.ACCESS_DENIED, assertInstanceOf(OperationResult.Failure.class,
                    roles.removeMember(arena.id(), player, MapRole.ADMIN)).reason());
            assertTrue(roles.assignedMembers(arena.id(), MapRole.ADMIN).isEmpty());
        }
    }

    @Test
    void groupProjectionFailureAndPendingQueuePreserveMmcGrant() {
        UUID player = UUID.randomUUID();
        permissions.inheritanceResult = false;
        assertEquals(FailureReason.PERMISSION_SYNC_FAILED, assertInstanceOf(OperationResult.Partial.class,
                roles.addMember(arena.id(), player, MapRole.ADMIN)).reason());
        assertTrue(roles.hasRole(arena.id(), player, MapRole.ADMIN));
        permissions.inheritanceResult = true; permissions.pending = true;
        assertEquals(FailureReason.PERMISSION_SYNC_PENDING, assertInstanceOf(OperationResult.Partial.class,
                roles.removeMember(arena.id(), player, MapRole.ADMIN)).reason());
        assertFalse(roles.hasRole(arena.id(), player, MapRole.ADMIN));
    }

    @Test
    void visitorChangesNeverCallPermissionProjection() {
        permissions.inheritanceResult = false; permissions.builderAccessResult = false; permissions.pending = true;
        UUID player = UUID.randomUUID();
        assertInstanceOf(OperationResult.Success.class, roles.addMember(arena.id(), player, MapRole.VISITOR));
        assertInstanceOf(OperationResult.Success.class, roles.removeMember(arena.id(), player, MapRole.VISITOR));
        assertFalse(roles.hasRole(arena.id(), player, MapRole.VISITOR));
    }

    private static MapInstance map(String worldName, String groupId, MapSettings settings, MapStatus status) {
        return new MapInstance(UUID.randomUUID(), worldName, groupId, worldName, settings, status,
                new SpawnPoint(worldName, 0, 64, 0, 0, 0));
    }

    private static final class FakeWorldBaseProjection implements WorldBaseProjection {
        private boolean builderAccessResult = true;
        private boolean lastBuilderAccess;
        private boolean inheritanceResult = true;
        private boolean pending;
        @Override public boolean pending() { return pending; }

        @Override
        public CompletableFuture<Boolean> ensureWorldBase() {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> ensureGroupInheritance(String groupId, Set<String> worldNames) {
            return CompletableFuture.completedFuture(inheritanceResult);
        }

        @Override
        public boolean setBuilderAccess(String groupId, UUID playerId, boolean enabled) {
            lastBuilderAccess = enabled;
            return builderAccessResult;
        }

        @Override
        public boolean removeGroup(String groupId) {
            return true;
        }

        @Override
        public boolean removeWorldInheritance(String groupId, String worldName) {
            return true;
        }
    }

    private static final class NoopPlayerGateway implements studio.lorian.mapmanager.api.service.PlayerGateway {
        @Override
        public CompletableFuture<UUID> resolveId(String playerName) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isOnline(UUID playerId) {
            return false;
        }

        @Override
        public String currentWorld(UUID playerId) {
            return null;
        }

        @Override
        public boolean teleport(UUID playerId, SpawnPoint point) {
            return false;
        }

        @Override
        public boolean disconnect(UUID playerId, String reason) {
            return false;
        }
    }

    private static final class NoopWorldVersionService
            implements studio.lorian.mapmanager.api.service.WorldVersionService {
        @Override
        public int serverDataVersion() {
            return 0;
        }

        @Override
        public java.util.OptionalInt mapDataVersion(Path worldFolder) {
            return java.util.OptionalInt.empty();
        }

        @Override
        public boolean isCompatible(Path worldFolder) {
            return false;
        }

        @Override
        public boolean isCompatible(String worldName) {
            return false;
        }
    }
}
