package studio.lorian.mapmanager.plugin.impl;

import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapSettingsService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.util.UUID;
import java.util.function.Function;

/** 修改 MMC 持久化的单地图设置。 */
public final class MapSettingsServiceImpl implements MapSettingsService {
    private final MapRepository maps;
    public MapSettingsServiceImpl(MapRepository maps) { this.maps = maps; }

    private synchronized OperationResult<Void> change(UUID id, OperationName operation,
                                                       Function<MapInstance, MapInstance> edit) {
        UUID operationId = UUID.randomUUID();
        if (id == null) return new OperationResult.Failure<>(operation, operationId, FailureReason.INVALID_INPUT);
        try {
            MapInstance old = maps.findById(id);
            if (old == null) return new OperationResult.Failure<>(operation, operationId, FailureReason.MAP_NOT_FOUND);
            maps.save(edit.apply(old));
            return new OperationResult.Success<>(operation, operationId, null);
        } catch (IllegalArgumentException ex) {
            return new OperationResult.Failure<>(operation, operationId, FailureReason.INVALID_INPUT);
        } catch (StorageException ex) {
            return new OperationResult.Failure<>(operation, operationId, FailureReason.STORAGE_FAILED);
        }
    }
    private MapInstance with(MapInstance old, String name, MapSettings settings, SpawnPoint spawn) {
        return new MapInstance(old.id(), old.worldName(), old.groupId(), name, settings, old.status(), spawn);
    }
    @Override public OperationResult<Void> setPublic(UUID id, boolean value) {
        return change(id, OperationName.SET_PUBLIC, old -> with(old, old.displayName(),
                new MapSettings(old.settings().physical(), old.settings().explosion(), value), old.spawn()));
    }
    @Override public OperationResult<Void> setDisplayName(UUID id, String name) {
        return change(id, OperationName.SET_ALIAS, old -> with(old, name, old.settings(), old.spawn()));
    }
    @Override public OperationResult<Void> setPhysical(UUID id, boolean value) {
        return change(id, OperationName.SET_PHYSICAL, old -> with(old, old.displayName(),
                new MapSettings(value, old.settings().explosion(), old.settings().isPublic()), old.spawn()));
    }
    @Override public OperationResult<Void> setExplosion(UUID id, boolean value) {
        return change(id, OperationName.SET_EXPLOSION, old -> with(old, old.displayName(),
                new MapSettings(old.settings().physical(), value, old.settings().isPublic()), old.spawn()));
    }
    @Override public SpawnPoint getSpawn(UUID id) {
        MapInstance map = maps.findById(id);
        return map == null ? null : map.spawn();
    }
    @Override public OperationResult<Void> setSpawn(UUID id, SpawnPoint spawn) {
        return change(id, OperationName.SET_SPAWN, old -> with(old, old.displayName(), old.settings(), spawn));
    }
}
