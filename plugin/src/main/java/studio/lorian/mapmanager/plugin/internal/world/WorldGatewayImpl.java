package studio.lorian.mapmanager.plugin.internal.world;

import studio.lorian.mapmanager.api.service.WorldGateway;
import org.bukkit.Bukkit;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 地图存储目录定位实现。 */
public final class WorldGatewayImpl implements WorldGateway {
    private final Path serverDirectory;

    public WorldGatewayImpl() {
        this(Path.of("").toAbsolutePath().normalize());
    }

    public WorldGatewayImpl(Path serverDirectory) {
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
    }

    @Override
    public Path worldParentDirectory() {
        Path propertiesFile = serverDirectory.resolve("server.properties");
        String levelName = null;
        if (Files.isRegularFile(propertiesFile)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
                levelName = properties.getProperty("level-name");
            } catch (IOException | IllegalArgumentException ex) {
                levelName = null;
            }
        }
        if (levelName != null) levelName = levelName.trim();
        if (levelName == null || levelName.isBlank() || levelName.equals(".") || levelName.equals("..")
                || levelName.contains("/") || levelName.contains("\\")) {
            var worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) throw new IllegalStateException(Messages.text("errors.no_primary_world"));
            levelName = worlds.get(0).getName();
        }
        Path dimensions = serverDirectory.resolve(levelName).resolve("dimensions/minecraft");
        return Files.isDirectory(dimensions) ? dimensions : serverDirectory;
    }

    @Override
    public Path worldDirectory(String worldName) {
        if (worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException(Messages.text("errors.invalid_world_name"));
        }
        return worldParentDirectory().resolve(worldName);
    }

}
