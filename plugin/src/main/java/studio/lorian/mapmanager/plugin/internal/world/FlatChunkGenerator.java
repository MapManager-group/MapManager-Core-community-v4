package studio.lorian.mapmanager.plugin.internal.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.HeightMap;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/** Supplies flat block layers when a flat generator is used with a non-normal environment. */
public final class FlatChunkGenerator extends ChunkGenerator {
    private static final String ID_PREFIX = "flat:";
    private final List<Layer> layers;

    public FlatChunkGenerator(String generatorId) {
        if (generatorId == null || !generatorId.startsWith(ID_PREFIX)) {
            throw new IllegalArgumentException("Unsupported MapManager generator id");
        }
        String encoded = generatorId.substring(ID_PREFIX.length());
        try {
            String settings = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            this.layers = parseLayers(settings);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid MapManager flat generator settings", exception);
        }
    }

    public static String generatorId(String settings) {
        parseLayers(settings);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(settings.getBytes(StandardCharsets.UTF_8));
        return ID_PREFIX + encoded;
    }

    public static long totalHeight(String settings) {
        return parseLayers(settings).stream().mapToLong(Layer::height).sum();
    }

    private static List<Layer> parseLayers(String settings) {
        try {
            JsonElement parsed = JsonParser.parseString(settings);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("layers")) {
                throw new IllegalArgumentException("Flat generator settings require a layers array");
            }
            JsonArray input = parsed.getAsJsonObject().getAsJsonArray("layers");
            if (input.isEmpty()) throw new IllegalArgumentException("Flat generator layers cannot be empty");
            List<Layer> result = new ArrayList<>(input.size());
            for (JsonElement element : input) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Invalid flat generator layer");
                JsonObject layer = element.getAsJsonObject();
                if (!layer.has("block") || !layer.has("height"))
                    throw new IllegalArgumentException("Flat generator layers require block and height");
                String block = layer.get("block").getAsString();
                int height = layer.get("height").getAsInt();
                Material material = Material.matchMaterial(block);
                if (material == null || !material.isBlock() || height < 1) {
                    throw new IllegalArgumentException("Invalid flat generator layer: " + block);
                }
                result.add(new Layer(material, height));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) throw exception;
            throw new IllegalArgumentException("Invalid flat generator settings", exception);
        }
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int y = chunkData.getMinHeight();
        int maxHeight = chunkData.getMaxHeight();
        for (Layer layer : layers) {
            int endY = (int) Math.min(maxHeight, (long) y + layer.height());
            if (endY > y) chunkData.setRegion(0, y, 0, 16, endY, 16, layer.material());
            y = endY;
            if (y >= maxHeight) break;
        }
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        long top = (long) worldInfo.getMinHeight() + layers.stream().mapToLong(Layer::height).sum();
        return (int) Math.min(worldInfo.getMaxHeight(), top);
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        long surface = (long) world.getMinHeight() + layers.stream().mapToLong(Layer::height).sum();
        int y = (int) Math.max(world.getMinHeight() + 1L, Math.min(world.getMaxHeight() - 1L, surface));
        return new Location(world, 0.5, y, 0.5);
    }

    private record Layer(Material material, int height) {}
}
