package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;

import java.util.List;
import java.util.UUID;

/** 从所选存储后端读取地图快照。 */
public final class MapServiceImpl implements MapService {
    private final MapRepository maps;
    public MapServiceImpl(MapRepository maps) { this.maps = maps; }
    @Override public MapInstance findById(UUID id) { return maps.findById(id); }
    @Override public MapInstance findByWorldName(String name) { return maps.findByWorldName(name); }
    @Override public List<MapInstance> listMaps() { return maps.findAll(); }
    @Override public MapGroup findGroup(String id) { return maps.findGroup(id); }
    @Override public List<MapInstance> listGroupMaps(String id) {
        return maps.findAll().stream().filter(map -> map.groupId().equals(id)).toList();
    }
}
