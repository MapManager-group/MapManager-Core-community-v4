package studio.lorian.mapmanager.plugin.impl;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.AccessDecision;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.util.UUID;

/** 执行准入检查、按需加载地图并传送玩家。 */
public final class MapTravelServiceImpl implements MapTravelService {
    private final MapRepository maps;
    private final MapAccessService access;
    private final PlayerGateway players;
    private final WorldLifeCycleService lifeCycle;

    public MapTravelServiceImpl(MapRepository maps, MapAccessService access, PlayerGateway players,
                                WorldLifeCycleService lifeCycle) {
        this.maps = maps;
        this.access = access;
        this.players = players;
        this.lifeCycle = lifeCycle;
    }

    @Override
    public OperationResult<Void> teleport(UUID playerId, UUID mapId) {
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || playerId == null || mapId == null)
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.INVALID_INPUT);
        if (!players.isOnline(playerId))
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.PLAYER_NOT_FOUND);

        final MapInstance map;
        try {
            map = maps.findById(mapId);
        } catch (StorageException exception) {
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.STORAGE_FAILED);
        }
        if (map == null)
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.MAP_NOT_FOUND);

        final AccessDecision decision;
        try {
            decision = access.decide(playerId, mapId);
        } catch (StorageException exception) {
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.STORAGE_FAILED);
        }
        if (!decision.allowed())
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId,
                    decision.denialReason() == null ? FailureReason.ACCESS_DENIED : decision.denialReason());

        OperationResult<MapInstance> loadResult = lifeCycle.load(map.worldName());
        if (loadResult instanceof OperationResult.Failure<MapInstance> failure)
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, failure.reason());
        if (!(loadResult instanceof OperationResult.Success<MapInstance>))
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.WORLD_BACKEND_FAILED);

        World world = Bukkit.getWorld(map.worldName());
        if (world == null)
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.WORLD_NOT_FOUND);
        SpawnPoint spawn = map.spawn();
        if (spawn == null) {
            var location = world.getSpawnLocation();
            spawn = new SpawnPoint(world.getName(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        } else if (!world.getName().equalsIgnoreCase(spawn.worldName())) {
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId,
                    FailureReason.WORLD_BACKEND_FAILED);
        }
        if (!players.teleport(playerId, spawn))
            return new OperationResult.Failure<>(OperationName.TELEPORT, operationId, FailureReason.WORLD_BACKEND_FAILED);
        return new OperationResult.Success<>(OperationName.TELEPORT, operationId, null);
    }

    @Override
    public OperationResult<Void> kickFromMap(UUID operatorId, UUID playerId) {
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || operatorId == null || playerId == null)
            return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.INVALID_INPUT);
        Player operator = Bukkit.getPlayer(operatorId);
        Player target = Bukkit.getPlayer(playerId);
        if (operator == null || target == null)
            return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.PLAYER_NOT_FOUND);
        if (!operator.getWorld().equals(target.getWorld()))
            return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.PLAYER_NOT_IN_MAP);

        final MapInstance map;
        final AccessDecision decision;
        try {
            map = maps.findByWorldName(operator.getWorld().getName());
            if (map == null)
                return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.MAP_NOT_FOUND);
            decision = access.decide(operatorId, map.id());
        } catch (StorageException exception) {
            return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.STORAGE_FAILED);
        }
        if (!decision.allowed())
            return new OperationResult.Failure<>(OperationName.KICK, operationId,
                    decision.denialReason() == null ? FailureReason.ACCESS_DENIED : decision.denialReason());
        if (decision.effectiveRole() != MapRole.ADMIN)
            return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.NO_PERMISSION);
        try {
            var defaultWorld = MultiverseCoreApi.get().getWorldManager().getDefaultWorld().getOrNull();
            if (defaultWorld == null || map.worldName().equals(defaultWorld.getName()))
                return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.WORLD_BACKEND_FAILED);
            var location = defaultWorld.getSpawnLocation();
            if (location == null || location.getWorld() == null
                    || !defaultWorld.getName().equals(location.getWorld().getName()))
                return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.WORLD_BACKEND_FAILED);
            if (!target.teleport(location))
                return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.WORLD_BACKEND_FAILED);
            return new OperationResult.Success<>(OperationName.KICK, operationId, null);
        } catch (RuntimeException exception) {
            return new OperationResult.Failure<>(OperationName.KICK, operationId, FailureReason.WORLD_BACKEND_FAILED);
        }
    }
}
