package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.WorldType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLifeCycleServiceImplTest {
    @Test
    void siftCreationReusesFlatWorldGenerationOptions() {
        CreateWorldOptions flat = options(WorldType.FLAT);
        CreateWorldOptions sift = options(WorldType.SIFT);

        assertFlatGenerationOptions(flat, sift);
        assertEquals(World.Environment.NORMAL, sift.environment());
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
        assertEquals(World.Environment.NETHER, nether.environment());
        assertEquals(World.Environment.THE_END, end.environment());
    }

    private static void assertFlatGenerationOptions(CreateWorldOptions flat, CreateWorldOptions generated) {
        assertEquals(flat.worldType(), generated.worldType());
        assertEquals(flat.seed(), generated.seed());
        assertEquals(flat.generateStructures(), generated.generateStructures());
        assertEquals(flat.biome(), generated.biome());
        assertEquals(flat.generator(), generated.generator());
        assertEquals(flat.generatorSettings(), generated.generatorSettings());
        assertEquals(flat.worldPropertyStrings(), generated.worldPropertyStrings());
    }

    private static CreateWorldOptions options(WorldType type) {
        return WorldLifeCycleServiceImpl.options(new CreateMapRequest(
                "sift-test", "SIFT Test", "sift-test", null, type,
                Map.of("flat.height", "64", "seed", "42", "structures", "true",
                        "biome", "minecraft:desert", "generator", "test:generator",
                        "property.keep-spawn-in-memory", "true")));
    }
}
