package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import studio.lorian.mapmanager.plugin.internal.world.FlatChunkGenerator;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.WorldType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLifeCycleServiceImplTest {
    @Test
    void siftCreationReusesFlatWorldGenerationOptions() {
        CreateWorldOptions flat = options(WorldType.FLAT);
        CreateWorldOptions sift = options(WorldType.SIFT);

        assertFlatGenerationOptions(flat, sift);
        assertEquals(World.Environment.NORMAL, sift.environment());
        assertEquals(flat.generator(), sift.generator());
        assertTrue(sift.generatorSettings().contains("minecraft:bedrock"));
        assertTrue(sift.generatorSettings().contains("minecraft:grass_block"));
    }

    @Test
    void netherAndEndReuseFlatGenerationOptionsWithTheirOwnEnvironments() {
        CreateWorldOptions flat = options(WorldType.FLAT);
        CreateWorldOptions nether = options(WorldType.NETHER);
        CreateWorldOptions end = options(WorldType.END);

        assertFlatGenerationOptions(flat, nether);
        assertFlatGenerationOptions(flat, end);
        assertNotNull(nether.generator());
        assertNotNull(end.generator());
        assertTrue(nether.generator().startsWith("MapManager-Core:flat:"));
        assertTrue(end.generator().startsWith("MapManager-Core:flat:"));
        new FlatChunkGenerator(nether.generator().substring("MapManager-Core:".length()));
        new FlatChunkGenerator(end.generator().substring("MapManager-Core:".length()));
        assertEquals(World.Environment.NETHER, nether.environment());
        assertEquals(World.Environment.THE_END, end.environment());
    }

    @Test
    void netherAndEndRejectFlatLayersAboveTheirSafeBuildHeight() {
        assertThrows(IllegalArgumentException.class, () -> options(WorldType.NETHER, 255));
        assertThrows(IllegalArgumentException.class, () -> options(WorldType.END, 255));
    }

    @Test
    void netherAndEndRejectCustomGeneratorSettingsOutsideTheirHeightRange() {
        CreateMapRequest request = new CreateMapRequest("sift-test", "SIFT Test", "sift-test", null,
                WorldType.NETHER, Map.of("generator-settings", "{\"layers\":[{\"block\":\"stone\",\"height\":255}]}"));

        assertThrows(IllegalArgumentException.class, () -> WorldLifeCycleServiceImpl.options(request));
    }

    private static void assertFlatGenerationOptions(CreateWorldOptions flat, CreateWorldOptions generated) {
        assertEquals(flat.worldType(), generated.worldType());
        assertEquals(flat.seed(), generated.seed());
        assertEquals(flat.generateStructures(), generated.generateStructures());
        assertEquals(flat.biome(), generated.biome());
        assertEquals(flat.generatorSettings(), generated.generatorSettings());
        assertEquals(flat.worldPropertyStrings(), generated.worldPropertyStrings());
    }

    private static CreateWorldOptions options(WorldType type) {
        return options(type, 64);
    }

    private static CreateWorldOptions options(WorldType type, int height) {
        return WorldLifeCycleServiceImpl.options(new CreateMapRequest(
                "sift-test", "SIFT Test", "sift-test", null, type,
                Map.of("flat.height", Integer.toString(height), "seed", "42", "structures", "true",
                        "biome", "minecraft:desert",
                        "property.keep-spawn-in-memory", "true")));
    }
}
