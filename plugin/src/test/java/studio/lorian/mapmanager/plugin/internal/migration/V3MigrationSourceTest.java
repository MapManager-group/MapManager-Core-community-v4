package studio.lorian.mapmanager.plugin.internal.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.MapRole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3MigrationSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsLegacyMapsGroupsSettingsAndRoleScopes() throws IOException {
        Files.writeString(temporaryDirectory.resolve("worlds.json"), """
                {"TestWorld":{"group":"artists","exploded":true,"physical":false,"visitors":["Visitor123"]}}
                """);
        Files.writeString(temporaryDirectory.resolve("groups.json"), """
                {"artists":{"worlds":["TestWorld"],"admins":["Owner123"],"builders":["Owner123"]}}
                """);

        LegacySnapshot snapshot = new V3MigrationSourceImpl(temporaryDirectory).read();

        assertEquals(1, snapshot.maps().size());
        assertEquals("artists", snapshot.maps().getFirst().groupId());
        assertTrue(snapshot.maps().getFirst().settings().explosion());
        assertFalse(snapshot.maps().getFirst().settings().physical());
        assertTrue(snapshot.groups().contains("artists"));
        assertTrue(snapshot.roles().stream().anyMatch(role ->
                role.role() == MapRole.VISITOR && role.worldName().equals("TestWorld")
                        && role.playerName().equals("Visitor123")));
        assertEquals(3, snapshot.roles().size());
    }

    @Test
    void rejectsMismatchBetweenGroupWorldsAndMapRecords() throws IOException {
        Files.writeString(temporaryDirectory.resolve("worlds.json"), """
                {"TestWorld":{"group":"artists"}}
                """);
        Files.writeString(temporaryDirectory.resolve("groups.json"), """
                {"artists":{"worlds":["OtherWorld"]}}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new V3MigrationSourceImpl(temporaryDirectory).read());

        assertFalse(error.getMessage().isBlank());
    }
}
