package studio.lorian.mapmanager.plugin.internal.migration;

import studio.lorian.mapmanager.api.model.MapInstance;

import java.util.List;

public record LegacySnapshot(
        List<MapInstance> maps,
        List<LegacyRoleGrant> roles,
        List<String> warnings
) {
    public LegacySnapshot {
        maps = List.copyOf(maps);
        roles = List.copyOf(roles);
        warnings = List.copyOf(warnings);
    }
}
