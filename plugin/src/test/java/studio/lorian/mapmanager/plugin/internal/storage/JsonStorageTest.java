package studio.lorian.mapmanager.plugin.internal.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.OperationName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsMapsGroupsRolesAndOperationsAcrossReopen() throws IOException {
        Path file = temporaryDirectory.resolve("maps.json");
        JsonStorage storage = new JsonStorage(file);
        MapInstance map = map("arena", "team");
        UUID adminId = UUID.randomUUID();
        UUID visitorId = UUID.randomUUID();
        OperationRecord operation = new OperationRecord(UUID.randomUUID(), OperationName.CREATE,
                "arena", OperationStage.DATA_SAVED, null);

        storage.saveGroup(new MapGroup("team", Set.of()));
        storage.save(map);
        assertTrue(storage.add("team", MapRole.ADMIN, adminId));
        assertTrue(storage.add(map.id().toString(), MapRole.VISITOR, visitorId));
        storage.save(operation);

        JsonStorage reopened = new JsonStorage(file);
        assertEquals(map, reopened.findById(map.id()));
        assertEquals(map, reopened.findByWorldName("ARENA"));
        assertEquals(Set.of("arena"), reopened.findGroup("team").worldNames());
        assertEquals(Set.of(adminId), reopened.members("team", MapRole.ADMIN));
        assertEquals(Set.of(visitorId), reopened.members(map.id().toString(), MapRole.VISITOR));
        assertEquals(operation, reopened.find(operation.id()));
        assertEquals(java.util.List.of(operation), reopened.unfinished());
    }

    @Test
    void rejectsInvalidMapWithoutReplacingPersistedState() throws IOException {
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("maps.json"));
        MapInstance original = map("arena", "team");
        storage.saveGroup(new MapGroup("team", Set.of()));
        storage.save(original);
        MapInstance invalid = new MapInstance(original.id(), original.worldName(), original.groupId(),
                original.displayName(), original.settings(), original.status(),
                new SpawnPoint("other", 0, 64, 0, 0, 0));

        assertThrows(IllegalArgumentException.class, () -> storage.save(invalid));
        assertEquals(original, storage.findById(original.id()));
        assertEquals(original, new JsonStorage(temporaryDirectory.resolve("maps.json")).findById(original.id()));
    }

    @Test
    void deletingMapRemovesItsVisitorGrantsAndPreservesGroupRoles() throws IOException {
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("maps.json"));
        MapInstance first = map("arena", "team");
        MapInstance second = map("arena2", "team");
        UUID adminId = UUID.randomUUID();
        UUID visitorId = UUID.randomUUID();
        storage.saveGroup(new MapGroup("team", Set.of()));
        storage.save(first);
        storage.save(second);
        storage.add("team", MapRole.ADMIN, adminId);
        storage.add(first.id().toString(), MapRole.VISITOR, visitorId);

        storage.delete(first.id());

        assertEquals(Set.of(adminId), storage.members("team", MapRole.ADMIN));
        assertTrue(storage.members(first.id().toString(), MapRole.VISITOR).isEmpty());
        assertEquals(Set.of("arena2"), storage.findGroup("team").worldNames());
        assertNotNull(storage.findById(second.id()));
    }

    @Test
    void refusesDeletingGroupStillUsedByMap() throws IOException {
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("maps.json"));
        MapInstance map = map("arena", "team");
        storage.saveGroup(new MapGroup("team", Set.of()));
        storage.save(map);

        assertThrows(IllegalArgumentException.class, () -> storage.deleteGroup("team"));
        assertNotNull(storage.findGroup("team"));
        assertNotNull(storage.findById(map.id()));
    }

    @Test
    void repeatedRoleGrantIsIdempotentAndCorruptFileIsRejected() throws IOException {
        Path file = temporaryDirectory.resolve("maps.json");
        JsonStorage storage = new JsonStorage(file);
        storage.saveGroup(new MapGroup("team", Set.of()));
        UUID playerId = UUID.randomUUID();

        assertTrue(storage.add("team", MapRole.BUILDER, playerId));
        assertFalse(storage.add("team", MapRole.BUILDER, playerId));
        Files.writeString(file, "not-json");
        assertThrows(IOException.class, () -> new JsonStorage(file));
    }

    private static MapInstance map(String world, String group) {
        return new MapInstance(UUID.randomUUID(), world, group, world,
                new MapSettings(true, false, false), MapStatus.ACTIVE,
                new SpawnPoint(world, 10.5, 64, -3.25, 90, 0));
    }
}
