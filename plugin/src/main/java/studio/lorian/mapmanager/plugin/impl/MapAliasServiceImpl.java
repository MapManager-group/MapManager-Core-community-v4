package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.Bukkit;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapAliasService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.util.UUID;
import java.util.Objects;

/** 从 MMC 地图记录计算颜色并设置 Multiverse 别名。 */
public final class MapAliasServiceImpl implements MapAliasService {
    private final MapRepository maps;

    public MapAliasServiceImpl(MapRepository maps) {
        this.maps = maps;
    }

    @Override
    public OperationResult<Void> setAlias(UUID mapId) {
        return setAliasInternal(mapId, null);
    }

    @Override
    public OperationResult<Void> setAlias(UUID mapId, String alias) {
        if (alias == null || alias.isBlank())
            return new OperationResult.Failure<>(OperationName.SET_ALIAS, UUID.randomUUID(), FailureReason.INVALID_INPUT);
        return setAliasInternal(mapId, alias);
    }

    /** 读取 Multiverse 中已有的别名，去掉最前面的颜色代码。 */
    String currentAlias(MapInstance map) {
        var world = MultiverseCoreApi.get().getWorldManager().getWorld(map.worldName()).getOrNull();
        if (world == null) throw new IllegalStateException(Messages.text("errors.mv_world_missing", "world", map.worldName()));
        String alias = world.getAlias();
        String text = alias == null ? "" : stripColor(alias);
        return text.isBlank() ? map.displayName() : text;
    }

    /** 移除 legacy 和 RGB 格式码，确保存储中只保留别名正文。 */
    static String stripColor(String alias) {
        return alias.replaceAll("(?i)(?:[&§][0-9a-fk-orx])|(?:&#[0-9a-f]{6})", "");
    }

    private OperationResult<Void> setAliasInternal(UUID mapId, String requestedAlias) {
        UUID operationId = UUID.randomUUID();
        if (mapId == null || !Bukkit.isPrimaryThread())
            return new OperationResult.Failure<>(OperationName.SET_ALIAS, operationId, FailureReason.INVALID_INPUT);
        try {
            MapInstance map = maps.findById(mapId);
            if (map == null)
                return new OperationResult.Failure<>(OperationName.SET_ALIAS, operationId, FailureReason.MAP_NOT_FOUND);
            var manager = MultiverseCoreApi.get().getWorldManager();
            var world = manager.getWorld(map.worldName()).getOrNull();
            if (world == null)
                return new OperationResult.Failure<>(OperationName.SET_ALIAS, operationId, FailureReason.WORLD_NOT_FOUND);
            String alias = requestedAlias == null ? map.worldName() : stripColor(requestedAlias);
            if (alias.isBlank() || alias.length() > 255)
                return new OperationResult.Failure<>(OperationName.SET_ALIAS, operationId, FailureReason.INVALID_INPUT);
            String color = map.settings().isPublic() ? "&2" : "&3";
            world.setAlias(color + alias).get();
            manager.saveWorldsConfig().get();
            if (!Objects.equals(map.displayName(), alias)) {
                try {
                    maps.save(new MapInstance(map.id(), map.worldName(), map.groupId(), alias,
                            map.settings(), map.status(), map.spawn()));
                } catch (StorageException exception) {
                    return new OperationResult.Partial<>(OperationName.SET_ALIAS, operationId, null,
                            FailureReason.STORAGE_FAILED);
                }
            }
            return new OperationResult.Success<>(OperationName.SET_ALIAS, operationId, null);
        } catch (StorageException exception) {
            return new OperationResult.Failure<>(OperationName.SET_ALIAS, operationId, FailureReason.STORAGE_FAILED);
        } catch (RuntimeException exception) {
            return new OperationResult.Failure<>(OperationName.SET_ALIAS, operationId, FailureReason.WORLD_BACKEND_FAILED);
        }
    }
}
