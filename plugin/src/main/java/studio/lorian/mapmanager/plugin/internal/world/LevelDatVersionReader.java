package studio.lorian.mapmanager.plugin.internal.world;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

/** 从压缩的 level.dat NBT 中读取 Data.DataVersion。 */
public final class LevelDatVersionReader {
    private LevelDatVersionReader() {}

    /** 读取地图目录中的数据版本；文件损坏或字段缺失时返回空值。 */
    public static OptionalInt read(Path worldFolder) {
        if (worldFolder == null) return OptionalInt.empty();
        Path levelDat = worldFolder.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) return OptionalInt.empty();
        try {
            var namedTag = NBTUtil.read(levelDat.toFile());
            if (!(namedTag.getTag() instanceof CompoundTag root) || !root.containsKey("Data"))
                return OptionalInt.empty();
            Object dataValue = root.get("Data");
            if (!(dataValue instanceof CompoundTag data) || !data.containsKey("DataVersion"))
                return OptionalInt.empty();
            return OptionalInt.of(data.getInt("DataVersion"));
        } catch (IOException | RuntimeException exception) {
            return OptionalInt.empty();
        }
    }
}
