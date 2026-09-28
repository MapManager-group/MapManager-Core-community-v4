package studio.lorian.mapmanager.api.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiModelContractTest {
    @Test
    void createAndImportRequestsDefaultToFlatWorlds() {
        CreateMapRequest create = new CreateMapRequest("arena", "Arena", "team", null);
        ImportMapRequest importRequest = new ImportMapRequest("arena", "Arena", "team", null);

        assertEquals(WorldType.FLAT, create.worldType());
        assertEquals(Map.of(), create.parameters());
        assertEquals(WorldType.FLAT, importRequest.worldType());
    }

    @Test
    void createRequestCopiesParametersAndTreatsNullAsEmpty() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("seed", "42");
        CreateMapRequest request = new CreateMapRequest(
                "arena", "Arena", "team", null, WorldType.NORMAL, parameters);

        parameters.put("seed", "99");
        assertEquals("42", request.parameters().get("seed"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.parameters().put("structures", "false"));
        assertEquals(Map.of(), new CreateMapRequest(
                "arena", "Arena", "team", null, WorldType.NORMAL, null).parameters());
    }

    @Test
    void mapGroupCopiesAndProtectsWorldNames() {
        Set<String> worlds = new HashSet<>(Set.of("arena"));
        MapGroup group = new MapGroup("team", worlds);

        worlds.add("arena2");
        assertEquals(Set.of("arena"), group.worldNames());
        assertThrows(UnsupportedOperationException.class,
                () -> group.worldNames().add("arena3"));
    }
}
