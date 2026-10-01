package studio.lorian.mapmanager.plugin.internal.storage;

import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;

import java.util.List;
import java.util.UUID;

/** Internal port. JSON and database implementations stay in the plugin artifact. */
public interface MapRepository {
    MapInstance findById(UUID mapId);
    MapInstance findByWorldName(String worldName);
    List<MapInstance> findAll();
    default List<MapGroup> allGroups() {
        return findAll().stream().map(MapInstance::groupId).distinct().map(this::findGroup).toList();
    }
    MapGroup findGroup(String groupId);
    void save(MapInstance map);
    void delete(UUID mapId);
    void saveGroup(MapGroup group);
    void deleteGroup(String groupId);
}
