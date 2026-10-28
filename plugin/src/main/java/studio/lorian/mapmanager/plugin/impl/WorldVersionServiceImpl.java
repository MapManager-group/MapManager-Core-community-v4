package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.Bukkit;
import studio.lorian.mapmanager.api.service.WorldVersionService;
import studio.lorian.mapmanager.plugin.internal.world.LevelDatVersionReader;

import java.nio.file.Path;
import java.util.OptionalInt;

/** 基于 Bukkit 服务器数据版本校验导入地图的实现。 */
public final class WorldVersionServiceImpl implements WorldVersionService {
    private final Path worldContainer;

    public WorldVersionServiceImpl(Path worldContainer) {
        this.worldContainer = worldContainer.toAbsolutePath().normalize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int serverDataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
    }

    @Override
    public OptionalInt mapDataVersion(Path worldFolder) {
        return LevelDatVersionReader.read(worldFolder);
    }

    @Override
    public boolean isCompatible(Path worldFolder) {
        OptionalInt mapVersion = mapDataVersion(worldFolder);
        return mapVersion.isPresent() && mapVersion.getAsInt() <= serverDataVersion();
    }

    @Override
    public boolean isCompatible(String worldName) {
        if (worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,100}")) return false;
        Path folder = worldContainer.resolve(worldName);
        if (isCompatible(folder)) return true;
        return isCompatible(new studio.lorian.mapmanager.plugin.internal.world.WorldGatewayImpl().worldParentDirectory().resolve(worldName));
    }
}
