package studio.lorian.mapmanager.plugin.internal.migration;

import studio.lorian.mapmanager.api.model.MapRole;

import java.util.UUID;

public record LegacyRoleGrant(String worldName, MapRole role, String playerName, UUID playerId) {
}
