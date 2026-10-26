package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.Environment;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.plugin.internal.world.FlatChunkGenerator;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLifeCycleServiceImplTest {
    @Test
    void creationCombinesEachGeneratorWithEveryImplementedEnvironment() {
        for (Environment environment : implementedEnvironments()) {
            CreateWorldOptions flat = createOptions(WorldType.FLAT, environment);
            assertEquals(org.bukkit.WorldType.FLAT, flat.worldType());
            assertEquals(toBukkit(environment), flat.environment());
            if (environment == Environment.NORMAL) {
                assertNull(flat.generator());
            } else {
                assertFlatGenerator(flat.generator());
            }

            CreateWorldOptions normal = createOptions(WorldType.NORMAL, environment);
            assertEquals(org.bukkit.WorldType.NORMAL, normal.worldType());
            assertEquals(toBukkit(environment), normal.environment());
            assertNull(normal.generator());

            CreateWorldOptions voidWorld = createOptions(WorldType.VOID, environment);
            assertEquals(org.bukkit.WorldType.FLAT, voidWorld.worldType());
            assertEquals(toBukkit(environment), voidWorld.environment());
            assertEquals("VoidGen:{}", voidWorld.generator());
        }
    }

    @Test
    void importCombinesEachGeneratorWithEveryImplementedEnvironment() {
        for (Environment environment : implementedEnvironments()) {
            ImportWorldOptions flat = importOptions(WorldType.FLAT, environment);
            assertEquals(toBukkit(environment), flat.environment());
            assertFlatGenerator(flat.generator());
            assertEquals("@single:minecraft:plains", flat.biome());

            ImportWorldOptions normal = importOptions(WorldType.NORMAL, environment);
            assertEquals(toBukkit(environment), normal.environment());
            assertNull(normal.generator());

            ImportWorldOptions voidWorld = importOptions(WorldType.VOID, environment);
            assertEquals(toBukkit(environment), voidWorld.environment());
            assertEquals("VoidGen:{}", voidWorld.generator());
        }
    }

    @Test
    void siftEnvironmentIsReservedUntilSupported() {
        assertThrows(IllegalArgumentException.class, () -> createOptions(WorldType.FLAT, Environment.SIFT));
        assertThrows(IllegalArgumentException.class, () -> importOptions(WorldType.FLAT, Environment.SIFT));
    }

    @Test
    void flatHeightStoresAbsoluteSurfaceForRuntimeWorldBounds() {
        for (Environment environment : implementedEnvironments()) {
            for (int y : new int[]{-2000, -64, -60, 0, 309, 2000}) {
                var options = createOptions(WorldType.FLAT, environment, y);
                assertFlatGenerator(options.generator());
                assertEquals(4, FlatChunkGenerator.totalHeight(options.generatorSettings()));
                assertEquals(y, com.google.gson.JsonParser.parseString(options.generatorSettings())
                        .getAsJsonObject().get("surface_y").getAsInt());
                assertEquals("@single:minecraft:plains", options.biome());
            }
        }
    }

    @Test
    void flatHeightIsRejectedForOtherGeneratorTypes() {
        for (WorldType type : new WorldType[]{WorldType.NORMAL, WorldType.VOID}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorldLifeCycleServiceImpl.options(new CreateMapRequest("test", "Test", "test", null,
                            type, Environment.NORMAL, Map.of("flat.height", "64"))));
        }
    }

    @Test
    void customFlatSettingsRespectTheSelectedDimensionHeight() {
        CreateMapRequest request = new CreateMapRequest("sift-test", "SIFT Test", "sift-test", null,
                WorldType.FLAT, Environment.THE_END,
                Map.of("generator-settings", "{\"layers\":[{\"block\":\"stone\",\"height\":255}]}"));

        assertThrows(IllegalArgumentException.class, () -> WorldLifeCycleServiceImpl.options(request));
    }

    private static Environment[] implementedEnvironments() {
        return new Environment[]{Environment.NORMAL, Environment.NETHER, Environment.THE_END};
    }

    private static World.Environment toBukkit(Environment environment) {
        return switch (environment) {
            case NORMAL -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case THE_END -> World.Environment.THE_END;
            case SIFT -> throw new IllegalArgumentException("SIFT is not supported yet");
        };
    }

    private static CreateWorldOptions createOptions(WorldType type, Environment environment) {
        return WorldLifeCycleServiceImpl.options(new CreateMapRequest(
                "combo-test", "Combo Test", "combo-test", null, type, environment, Map.of()));
    }

    private static CreateWorldOptions createOptions(WorldType type, Environment environment, int height) {
        return WorldLifeCycleServiceImpl.options(new CreateMapRequest(
                "combo-test", "Combo Test", "combo-test", null, type, environment,
                Map.of("flat.height", Integer.toString(height))));
    }

    private static ImportWorldOptions importOptions(WorldType type, Environment environment) {
        return WorldLifeCycleServiceImpl.importOptions(new ImportMapRequest(
                "combo-test", "Combo Test", "combo-test", null, type, environment));
    }

    private static void assertFlatGenerator(String generator) {
        assertTrue(generator.startsWith("MapManager-Core:flat:"));
        new FlatChunkGenerator(generator.substring("MapManager-Core:".length()));
    }
}
