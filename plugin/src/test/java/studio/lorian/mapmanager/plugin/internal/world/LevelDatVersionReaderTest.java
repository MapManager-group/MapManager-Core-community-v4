package studio.lorian.mapmanager.plugin.internal.world;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelDatVersionReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void dimensionFoldersReadSharedLevelDatWithoutTreatingMissingFoldersAsWorlds() throws IOException {
        CompoundTag root = new CompoundTag(), data = new CompoundTag();
        data.putInt("DataVersion", 4903); root.put("Data", data);
        NBTUtil.write(root, temporaryDirectory.resolve("level.dat").toFile());
        Path dimension = temporaryDirectory.resolve("dimensions/minecraft/arena"); Files.createDirectories(dimension);
        assertEquals(OptionalInt.of(4903), LevelDatVersionReader.read(dimension));
        assertEquals(OptionalInt.empty(), LevelDatVersionReader.read(dimension.resolveSibling("missing")));
        Path link = dimension.resolveSibling("linked"); Files.createSymbolicLink(link, dimension);
        assertEquals(OptionalInt.empty(), LevelDatVersionReader.read(link));
    }

    @Test
    void readsDataVersionFromCompressedLevelDat() throws IOException {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", 3955);
        root.put("Data", data);
        NBTUtil.write(root, temporaryDirectory.resolve("level.dat").toFile());

        assertEquals(OptionalInt.of(3955), LevelDatVersionReader.read(temporaryDirectory));
    }

    @Test
    void missingOrInvalidLevelDatReturnsEmpty() throws IOException {
        assertEquals(OptionalInt.empty(), LevelDatVersionReader.read(temporaryDirectory));
        assertEquals(OptionalInt.empty(), LevelDatVersionReader.read(null));

        Files.writeString(temporaryDirectory.resolve("level.dat"), "not an NBT file");
        assertEquals(OptionalInt.empty(), LevelDatVersionReader.read(temporaryDirectory));
    }

    @Test
    void missingDataCompoundOrVersionFieldReturnsEmpty() throws IOException {
        CompoundTag root = new CompoundTag();
        root.putString("Data", "not-a-compound");
        NBTUtil.write(root, temporaryDirectory.resolve("level.dat").toFile());
        assertTrue(LevelDatVersionReader.read(temporaryDirectory).isEmpty());

        CompoundTag data = new CompoundTag();
        root.put("Data", data);
        NBTUtil.write(root, temporaryDirectory.resolve("level.dat").toFile());
        assertTrue(LevelDatVersionReader.read(temporaryDirectory).isEmpty());
    }
}
