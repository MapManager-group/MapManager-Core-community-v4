package studio.lorian.mapmanager.plugin.internal.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGatewayImplTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void usesDimensionsDirectoryWhenPrimaryWorldHasDimensions() throws IOException {
        writeLevelName("world");
        Path dimensions = Files.createDirectories(
                temporaryDirectory.resolve("world/dimensions/minecraft"));

        WorldGatewayImpl gateway = new WorldGatewayImpl(temporaryDirectory);

        assertEquals(dimensions, gateway.worldParentDirectory());
        assertEquals(dimensions.resolve("arena"), gateway.worldDirectory("arena"));
    }

    @Test
    void fallsBackToServerDirectoryWhenDimensionsAreAbsent() throws IOException {
        writeLevelName("world");

        assertEquals(temporaryDirectory, new WorldGatewayImpl(temporaryDirectory).worldParentDirectory());
    }

    @Test
    void rejectsNamesThatCouldEscapeWorldParentDirectory() throws IOException {
        writeLevelName("world");
        WorldGatewayImpl gateway = new WorldGatewayImpl(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> gateway.worldDirectory("../outside"));
        assertThrows(IllegalArgumentException.class, () -> gateway.worldDirectory("a/b"));
        assertThrows(IllegalArgumentException.class, () -> gateway.worldDirectory(null));
    }

    @Test
    void normalizedParentAndChildRemainUnderServerDirectory() throws IOException {
        writeLevelName("world");
        Path dimensions = Files.createDirectories(
                temporaryDirectory.resolve("world/dimensions/minecraft"));
        Path worldFolder = new WorldGatewayImpl(temporaryDirectory).worldDirectory("arena-1");

        assertTrue(worldFolder.startsWith(dimensions));
        assertEquals(dimensions.resolve("arena-1"), worldFolder);
    }

    private void writeLevelName(String levelName) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("level-name", levelName);
        try (var output = Files.newOutputStream(temporaryDirectory.resolve("server.properties"))) {
            properties.store(output, null);
        }
    }
}
