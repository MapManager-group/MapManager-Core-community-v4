package studio.lorian.mapmanager.plugin.internal.migration;

import studio.lorian.mapmanager.api.model.MapInstance;

import java.util.List;
import java.util.Set;

public record LegacySnapshot(
        List<MapInstance> maps,
        Set<String> groups,
        List<LegacyRoleGrant> roles,
        List<String> warnings
) {
    public LegacySnapshot {
        maps = List.copyOf(maps);
        groups = Set.copyOf(groups);
        roles = List.copyOf(roles);
        warnings = List.copyOf(warnings);
    }
}
