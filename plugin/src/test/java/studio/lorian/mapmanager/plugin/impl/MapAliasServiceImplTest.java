package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapAliasServiceImplTest {
    @Test
    void stripsLegacySectionAndRgbColorCodesButKeepsAliasText() {
        assertEquals("Arena Red", MapAliasServiceImpl.stripColor("&3Arena &#ff0000Red§c"));
        assertEquals("Map", MapAliasServiceImpl.stripColor("§x§1§2§3§4§5§6Map"));
    }
}
