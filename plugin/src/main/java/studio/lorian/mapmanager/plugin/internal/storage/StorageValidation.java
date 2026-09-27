package studio.lorian.mapmanager.plugin.internal.storage;

import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.util.Locale;
import java.util.Objects;

final class StorageValidation {
    private StorageValidation() { }
    static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    static void groupId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException(Messages.text("errors.invalid_group_id"));
    }
    static void map(MapInstance map) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(map.id());
        if (map.worldName() == null || !map.worldName().matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException(Messages.text("errors.invalid_world_name"));
        groupId(map.groupId());
        if (map.displayName() == null || map.displayName().isBlank() || map.displayName().length() > 255)
            throw new IllegalArgumentException(Messages.text("errors.invalid_display_name"));
        Objects.requireNonNull(map.settings());
        Objects.requireNonNull(map.status());
        SpawnPoint spawn = map.spawn();
        if (spawn != null && (!map.worldName().equals(spawn.worldName()) || !Double.isFinite(spawn.x())
                || !Double.isFinite(spawn.y()) || !Double.isFinite(spawn.z())
                || !Float.isFinite(spawn.yaw()) || !Float.isFinite(spawn.pitch())))
            throw new IllegalArgumentException(Messages.text("errors.invalid_spawn"));
    }
}
