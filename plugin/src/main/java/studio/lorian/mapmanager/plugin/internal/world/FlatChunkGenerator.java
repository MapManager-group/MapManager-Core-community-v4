package studio.lorian.mapmanager.plugin.internal.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.HeightMap;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/** Generates flat layers, optionally placing the surface at an absolute world Y coordinate. */
public final class FlatChunkGenerator extends ChunkGenerator {
    private static final String ID_PREFIX = "flat:";
    private final List<Layer> layers;
    private final Integer surfaceY;
    private final String biomeId;

    public FlatChunkGenerator(String generatorId) {
        if (generatorId == null || !generatorId.startsWith(ID_PREFIX)) {
            throw new IllegalArgumentException("Unsupported MapManager generator id");
        }
        String encoded = generatorId.substring(ID_PREFIX.length());
        try {
            String settings = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            JsonObject input = JsonParser.parseString(settings).getAsJsonObject();
            this.surfaceY = input.has("surface_y") ? Integer.valueOf(input.get("surface_y").getAsString()) : null;
            this.layers = surfaceY == null ? parseLayers(settings) : List.of();
            this.biomeId = input.has("biome") ? input.get("biome").getAsString() : "minecraft:plains";
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid MapManager flat generator settings", exception);
        }
    }

    public static String generatorId(String settings) {
        JsonElement input = JsonParser.parseString(settings);
        if (input.isJsonObject() && input.getAsJsonObject().has("surface_y"))
            Integer.parseInt(input.getAsJsonObject().get("surface_y").getAsString());
        else parseLayers(settings);
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
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        if (surfaceY == null) return null;
        // This hook receives the target dimension before Paper starts generating chunks.
        validateSurface(surfaceY, worldInfo.getMinHeight(), worldInfo.getMaxHeight());
        Biome biome = Registry.BIOME.get(NamespacedKey.fromString(biomeId.toLowerCase(java.util.Locale.ROOT)));
        if (biome == null) throw new IllegalArgumentException(Messages.text("errors.flat_biome_invalid", "biome", biomeId));
        return new BiomeProvider() {
            @Override public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) { return biome; }
            @Override public List<Biome> getBiomes(WorldInfo worldInfo) { return List.of(biome); }
        };
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int y = chunkData.getMinHeight();
        int maxHeight = chunkData.getMaxHeight();
        for (Layer layer : layers(y, maxHeight)) {
            int endY = (int) Math.min(maxHeight, (long) y + layer.height());
            if (endY > y) chunkData.setRegion(0, y, 0, 16, endY, 16, layer.material());
            y = endY;
            if (y >= maxHeight) break;
        }
    }

    private List<Layer> layers(int minHeight, int maxHeight) {
        if (surfaceY == null) return layers;
        validateSurface(surfaceY, minHeight, maxHeight);
        List<Layer> result = new ArrayList<>(3);
        if (surfaceY > minHeight) result.add(new Layer(Material.BEDROCK, 1));
        if (surfaceY > minHeight + 1) result.add(new Layer(Material.DIRT, surfaceY - minHeight - 1));
        result.add(new Layer(Material.GRASS_BLOCK, 1));
        return result;
    }

    public static void validateSurface(int surfaceY, int minHeight, int maxHeight) {
        if (surfaceY < minHeight || surfaceY >= maxHeight)
            throw new IllegalArgumentException(Messages.text("errors.flat_surface_height_range",
                    "height", surfaceY, "min", minHeight, "max", maxHeight - 1));
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        if (surfaceY != null) {
            validateSurface(surfaceY, worldInfo.getMinHeight(), worldInfo.getMaxHeight());
            return surfaceY + 1;
        }
        long top = (long) worldInfo.getMinHeight() + layers.stream().mapToLong(Layer::height).sum();
        return (int) Math.min(worldInfo.getMaxHeight(), top);
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        if (surfaceY != null) {
            validateSurface(surfaceY, world.getMinHeight(), world.getMaxHeight());
            return new Location(world, 0.5, surfaceY + 1.0, 0.5);
        }
        long surface = (long) world.getMinHeight() + layers.stream().mapToLong(Layer::height).sum();
        int y = (int) Math.max(world.getMinHeight() + 1L, Math.min(world.getMaxHeight() - 1L, surface));
        return new Location(world, 0.5, y, 0.5);
    }

    private record Layer(Material material, int height) {}
}
