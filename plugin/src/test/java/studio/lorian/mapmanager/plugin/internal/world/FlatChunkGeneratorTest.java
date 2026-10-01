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
        for (String invalid : List.of("not-json", "[]", "{}", "{\"layers\":[]}", "{\"layers\":[1]}",
                "{\"layers\":[{\"block\":\"stone\"}]}", "{\"layers\":[{\"block\":\"missing_block\",\"height\":1}]}",
                "{\"layers\":[{\"block\":\"diamond_sword\",\"height\":1}]}",
                "{\"layers\":[{\"block\":\"stone\",\"height\":0}]}",
                "{\"layers\":[{\"block\":\"stone\",\"height\":-1}]}")) {
            assertThrows(IllegalArgumentException.class, () -> FlatChunkGenerator.generatorId(invalid), invalid);
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
