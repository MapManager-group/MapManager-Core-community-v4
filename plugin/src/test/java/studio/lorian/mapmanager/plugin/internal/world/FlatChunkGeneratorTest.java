package studio.lorian.mapmanager.plugin.internal.world;

import org.bukkit.*;
import org.bukkit.generator.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class FlatChunkGeneratorTest {
    private static final String SETTINGS = "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
            + "{\"block\":\"minecraft:dirt\",\"height\":62},{\"block\":\"minecraft:grass_block\",\"height\":1}]}";
    private record Region(int bottom, int top, Material material) { }
    @Test void generatorIdRoundTripPreservesLayersAndTotalHeight() {
        String id = FlatChunkGenerator.generatorId(SETTINGS);
        assertTrue(id.startsWith("flat:")); assertEquals(64, FlatChunkGenerator.totalHeight(SETTINGS));
        List<Region> regions = generate(new FlatChunkGenerator(id), -64, 320);
        assertEquals(List.of(new Region(-64, -63, Material.BEDROCK), new Region(-63, -1, Material.DIRT),
                new Region(-1, 0, Material.GRASS_BLOCK)), regions);
    }
    @Test void layersAreClippedToChunkHeight() {
        assertEquals(List.of(new Region(0, 1, Material.BEDROCK), new Region(1, 32, Material.DIRT)),
                generate(new FlatChunkGenerator(FlatChunkGenerator.generatorId(SETTINGS)), 0, 32));
    }
    @Test void rejectsMalformedSettingsAndInvalidMaterialsOrHeights() {
        for (String invalid : List.of("not-json", "{broken", "null", "[]", "{}", "{\"layers\":[]}", "{\"layers\":[1]}",
                "{\"layers\":[{\"block\":\"stone\"}]}", "{\"layers\":[{\"block\":\"missing_block\",\"height\":1}]}",
                "{\"layers\":[{\"block\":\"diamond_sword\",\"height\":1}]}",
                "{\"layers\":[{\"block\":\"stone\",\"height\":0}]}",
                "{\"layers\":[{\"block\":\"stone\",\"height\":-1}]}")) {
            assertThrows(IllegalArgumentException.class, () -> FlatChunkGenerator.totalHeight(invalid), invalid);
            String id = FlatChunkGenerator.generatorId(invalid);
            assertThrows(IllegalArgumentException.class, () -> new FlatChunkGenerator(id), invalid);
        }
        for (String id : new String[]{null, "normal:abc", "flat:%%%"})
            assertThrows(IllegalArgumentException.class, () -> new FlatChunkGenerator(id));
    }
    @Test void baseHeightAndSpawnUseMinimumHeightAndRespectCeiling() {
        var generator = new FlatChunkGenerator(FlatChunkGenerator.generatorId(SETTINGS));
        WorldInfo info = proxy(WorldInfo.class, (o, m, a) -> switch (m.getName()) {
            case "getMinHeight" -> -64; case "getMaxHeight" -> -32; default -> throw new AssertionError(m);
        });
        assertEquals(-32, generator.getBaseHeight(info, new Random(0), 1, 1, HeightMap.WORLD_SURFACE));
        World world = proxy(World.class, (o, m, a) -> switch (m.getName()) {
            case "getMinHeight" -> -64; case "getMaxHeight" -> -32; default -> throw new AssertionError(m);
        });
        Location spawn = generator.getFixedSpawnLocation(world, new Random(0));
        assertSame(world, spawn.getWorld()); assertEquals(-33, spawn.getY());
        assertEquals(0.5, spawn.getX()); assertEquals(0.5, spawn.getZ());
    }
    @Test void absoluteSurfaceUsesActualWorldBoundsAndSurvivesGeneratorReload() {
        for (int y : new int[]{-64, -60, 0, 309, 319}) {
            var generator = surface(y);
            var regions = generate(generator, -64, 320);
            assertEquals(new Region(y, y + 1, Material.GRASS_BLOCK), regions.getLast());
            if (y > -64) assertEquals(new Region(-64, -63, Material.BEDROCK), regions.getFirst());
            if (y > -63) assertEquals(new Region(-63, y, Material.DIRT), regions.get(1));
            assertEquals(y + 1, generator.getBaseHeight(info(-64, 320), new Random(), 0, 0, HeightMap.WORLD_SURFACE));
            World world = proxy(World.class, (o, m, a) -> switch (m.getName()) {
                case "getMinHeight" -> -64; case "getMaxHeight" -> 320; default -> throw new AssertionError(m);
            });
            assertEquals(y + 1.0, generator.getFixedSpawnLocation(world, new Random()).getY());
        }
        for (int y : new int[]{-2000, 2000}) {
            var generator = surface(y);
            var regions = generate(generator, -2032, 2032);
            assertEquals(new Region(-2032, -2031, Material.BEDROCK), regions.getFirst());
            assertEquals(new Region(y, y + 1, Material.GRASS_BLOCK), regions.getLast());
            assertEquals(y + 1, generator.getBaseHeight(info(-2032, 2032), new Random(), 0, 0, HeightMap.WORLD_SURFACE));
        }
    }
    @Test void absoluteSurfaceOutsideActualWorldRangeIsRejectedWithoutClipping() {
        for (int y : new int[]{-2000, -65, 320, 2000}) {
            var generator = surface(y);
            assertThrows(IllegalArgumentException.class, () -> generate(generator, -64, 320));
            assertThrows(IllegalArgumentException.class,
                    () -> generator.getBaseHeight(info(-64, 320), new Random(), 0, 0, HeightMap.WORLD_SURFACE));
            World world = proxy(World.class, (o, m, a) -> switch (m.getName()) {
                case "getMinHeight" -> -64; case "getMaxHeight" -> 320; default -> throw new AssertionError(m);
            });
            assertThrows(IllegalArgumentException.class, () -> generator.getFixedSpawnLocation(world, new Random()));
        }
        assertEquals(List.of(new Region(0, 1, Material.GRASS_BLOCK)), generate(surface(0), 0, 256));
        assertThrows(IllegalArgumentException.class, () -> generate(surface(-60), 0, 256));
    }
    @Test void targetHeightIsValidatedBeforePreparingBiomeProviderAndChunks() throws Exception {
        for (int y : new int[]{-65, 320, 2000}) {
            assertThrows(IllegalArgumentException.class, () -> surface(y).getDefaultBiomeProvider(info(-64, 320)));
        }
        try (var server = new studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer((o, m, a) -> {
            if (m.getName().equals("getRegistry")) return proxy(org.bukkit.Registry.class, (registry, method, args) -> null);
            throw new AssertionError(m);
        })) {
            var provider = surface(-60).getDefaultBiomeProvider(info(-64, 320));
            assertEquals(org.bukkit.block.Biome.PLAINS, provider.getBiome(info(-64, 320), 0, -60, 0));
            assertEquals(List.of(org.bukkit.block.Biome.PLAINS), provider.getBiomes(info(-64, 320)));
            var custom = new FlatChunkGenerator(FlatChunkGenerator.generatorId("{\"surface_y\":0,\"biome\":\"DESERT\"}"));
            assertEquals(org.bukkit.block.Biome.DESERT, custom.getDefaultBiomeProvider(info(-64, 320)).getBiome(info(-64, 320), 0, 0, 0));
        }
    }

    private FlatChunkGenerator surface(int y) {
        return new FlatChunkGenerator(FlatChunkGenerator.generatorId("{\"surface_y\":" + y + "}"));
    }
    private WorldInfo info(int min, int max) {
        return proxy(WorldInfo.class, (o, m, a) -> switch (m.getName()) {
            case "getMinHeight" -> min; case "getMaxHeight" -> max; default -> throw new AssertionError(m);
        });
    }
    private List<Region> generate(FlatChunkGenerator generator, int min, int max) {
        List<Region> regions = new ArrayList<>();
        ChunkGenerator.ChunkData data = proxy(ChunkGenerator.ChunkData.class, (o, m, a) -> switch (m.getName()) {
            case "getMinHeight" -> min; case "getMaxHeight" -> max;
            case "setRegion" -> {
                assertEquals(0, a[0]); assertEquals(0, a[2]); assertEquals(16, a[3]); assertEquals(16, a[5]);
                regions.add(new Region((int) a[1], (int) a[4], (Material) a[6])); yield null;
            }
            default -> throw new AssertionError(m);
        });
        generator.generateNoise(null, new Random(0), -2, 3, data); return regions;
    }
}
