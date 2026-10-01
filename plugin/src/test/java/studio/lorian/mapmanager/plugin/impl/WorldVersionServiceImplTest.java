package studio.lorian.mapmanager.plugin.impl;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;
import org.bukkit.UnsafeValues;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer;
import java.nio.file.*;
import java.util.OptionalInt;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class WorldVersionServiceImplTest {
    @TempDir Path directory;
    private BukkitTestServer server;
    private WorldVersionServiceImpl versions;
    @BeforeEach void setUp() throws Exception {
        UnsafeValues unsafe = proxy(UnsafeValues.class, (o, m, a) -> {
            assertEquals("getDataVersion", m.getName()); return 4903;
        });
        server = new BukkitTestServer((o, m, a) -> {
            assertEquals("getUnsafe", m.getName()); return unsafe;
        });
        versions = new WorldVersionServiceImpl(directory);
    }
    @AfterEach void tearDown() throws Exception { server.close(); }
    @Test void acceptsOlderAndEqualVersionsButRejectsFutureVersion() throws Exception {
        for (int version : new int[]{4902, 4903, 4904}) {
            writeVersion(directory, version);
            assertEquals(OptionalInt.of(version), versions.mapDataVersion(directory));
            assertEquals(version <= 4903, versions.isCompatible(directory));
        }
        assertEquals(4903, versions.serverDataVersion());
    }
    @Test void invalidOrMissingNbtIsIncompatible() throws Exception {
        assertFalse(versions.isCompatible(directory)); assertFalse(versions.isCompatible((Path) null));
        Files.writeString(directory.resolve("level.dat"), "broken"); assertFalse(versions.isCompatible(directory));
    }
    @Test void resolvesValidNameWithinConfiguredContainer() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("arena")); writeVersion(folder, 4903);
        assertTrue(versions.isCompatible("arena"));
        assertFalse(versions.isCompatible((String) null)); assertFalse(versions.isCompatible(" "));
        assertFalse(versions.isCompatible("../outside"));
    }
    private void writeVersion(Path folder, int version) throws Exception {
        CompoundTag root = new CompoundTag(), data = new CompoundTag();
        data.putInt("DataVersion", version); root.put("Data", data);
        NBTUtil.write(root, folder.resolve("level.dat").toFile());
    }
}
