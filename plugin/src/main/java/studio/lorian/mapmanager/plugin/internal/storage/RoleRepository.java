package studio.lorian.mapmanager.plugin.internal.storage;

import studio.lorian.mapmanager.api.model.MapRole;

import java.util.Set;
import java.util.UUID;

/** ADMIN/BUILDER use group scope; VISITOR uses map scope. */
public interface RoleRepository {
    Set<UUID> members(String scopeId, MapRole role);
    boolean add(String scopeId, MapRole role, UUID playerId);
    boolean remove(String scopeId, MapRole role, UUID playerId);
    void removeScope(String scopeId, MapRole role);
}
