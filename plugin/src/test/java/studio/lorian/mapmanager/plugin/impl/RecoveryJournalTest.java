package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.plugin.internal.storage.RecoveryJournal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryJournalTest {
    @TempDir Path directory;
    private RecoveryJournal.Plan plan(Path folder) {
        MapInstance map = new MapInstance(UUID.randomUUID(), "arena_bak", "team", "Arena", new MapSettings(true, false, false), MapStatus.PENDING, null);
        CreateMapRequest request = new CreateMapRequest(map.worldName(), map.displayName(), map.groupId(), UUID.randomUUID(), WorldType.FLAT,
                Environment.THE_END, Map.of("flat.height", "64"));
        return new RecoveryJournal.Plan(UUID.randomUUID(), OperationName.CREATE, map, request, null,
                null, null, null, RecoveryJournal.identity(folder), RecoveryJournal.directoryIdentity(folder), Set.of());
    }
    @Test void originalOwnerGeneratorEnvironmentAndMapIdentitySurviveRestart() throws Exception {
        var plan = plan(directory); var journal = new RecoveryJournal(directory.resolve("journal")); journal.save(plan);
        assertEquals(plan, new RecoveryJournal(directory.resolve("journal")).read(plan.map().worldName()));
        assertEquals(List.of(plan), journal.all()); journal.remove(plan.map().worldName()); assertTrue(journal.all().isEmpty());
    }
    @Test void deletionRecoveryRejectsReplacedDirectoryAndModifiedWorldIdentity() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("arena_bak")); Files.write(folder.resolve("uid.dat"), new byte[16]);
        var plan = plan(folder); WorldLifeCycleServiceImpl.verifyFolder(plan, folder);
        byte[] other = new byte[16]; other[0] = 1; Files.write(folder.resolve("uid.dat"), other);
        assertThrows(IllegalArgumentException.class, () -> WorldLifeCycleServiceImpl.verifyFolder(plan, folder));
        Files.move(folder, directory.resolve("original")); Files.createDirectory(folder); Files.write(folder.resolve("uid.dat"), new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> WorldLifeCycleServiceImpl.verifyFolder(plan, folder));
    }
    @Test void symbolicLinksAndPathTraversalCannotBecomeRecoveryTargets() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("arena_bak")); var plan = plan(folder);
        Path link = directory.resolve("link"); Files.createSymbolicLink(link, folder);
        assertThrows(IllegalArgumentException.class, () -> WorldLifeCycleServiceImpl.verifyFolder(plan, link));
        var journal = new RecoveryJournal(directory.resolve("journal"));
        assertThrows(IllegalArgumentException.class, () -> journal.read("../world"));
    }
}
