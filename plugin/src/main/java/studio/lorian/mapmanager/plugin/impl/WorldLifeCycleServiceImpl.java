package studio.lorian.mapmanager.plugin.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.model.WorldLoadState;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.api.service.MapAliasService;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRecord;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationStage;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.util.Map;
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.function.Supplier;

/**
 * 世界生命周期服务；创建流程由这里统一编排。
 */
public final class WorldLifeCycleServiceImpl implements WorldLifeCycleService {
    private final MapRepository maps;
    private final RoleRepository roles;
    private final OperationRepository operations;
    private final WorldBaseProjection permissions;
    private final MapAliasService aliases;
    private final Logger logger;

    public WorldLifeCycleServiceImpl(MapRepository maps, RoleRepository roles, OperationRepository operations,
                                     WorldBaseProjection permissions, MapAliasService aliases, Logger logger) {
        this.maps = maps;
        this.roles = roles;
        this.operations = operations;
        this.permissions = permissions;
        this.aliases = aliases;
        this.logger = logger;
    }

    @Override
    public synchronized OperationResult<MapInstance> create(CreateMapRequest request) {
        // 先校验 MMC 输入和生成器参数，避免留下无法执行的 PENDING 记录。
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || request == null || !validMapInput(request.worldName(),
                request.groupId(), request.displayName()) || request.worldType() == null
                || !validParameters(request.parameters()))
            return new OperationResult.Failure<>(OperationName.CREATE, operationId, FailureReason.INVALID_INPUT);
        final CreateWorldOptions options;
        try {
            options = options(request);
        } catch (IllegalArgumentException exception) {
            return new OperationResult.Failure<>(OperationName.CREATE, operationId, FailureReason.INVALID_INPUT);
        }
        // 世界生成方式只属于创建流程；其余注册步骤与导入共用。
        return registerWorld(OperationName.CREATE, operationId, request.worldName(), request.groupId(),
                request.displayName(), request.ownerId(), () -> {
                    var result = MultiverseCoreApi.get().getWorldManager().createWorld(options);
                    if (result.isFailure())
                        logger.warning("创建世界失败 " + request.worldName() + ": " + result.getFailureReason());
                    return result.getOrNull();
                });
    }

    /**
     * 记录失败，并在已有地图记录时返回其待处理快照。
     */
    private OperationResult<MapInstance> failed(UUID id, String name, FailureReason reason, OperationName operation) {
        try {
            operations.save(new OperationRecord(id, operation, name, OperationStage.FAILED, reason));
        } catch (Exception exception) {
            logger.severe("保存世界操作失败记录失败: " + exception.getMessage());
        }
        MapInstance map;
        try {
            map = maps.findByWorldName(name);
        } catch (Exception exception) {
            logger.severe("读取失败地图状态失败: " + exception.getMessage());
            map = null;
        }
        return map == null ? new OperationResult.Failure<>(operation, id, reason)
                : new OperationResult.Partial<>(operation, id, map, reason);
    }

    /**
     * 判断可选文本是否缺省。
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 创建和导入共用的地图名、组名与显示名限制。
     */
    private static boolean validMapInput(String worldName, String groupId, String displayName) {
        return worldName != null && worldName.matches("[a-z0-9_-]{4,16}")
                && (blank(groupId) || groupId.matches("[a-z0-9_-]{1,16}"))
                && !Set.of("default", "worldbase").contains(blank(groupId) ? worldName : groupId)
                && (blank(displayName) || displayName.length() <= 255);
    }

    /**
     * 检查创建专用参数的键名和基本长度，具体数值由生成器选项解析。
     */
    private static boolean validParameters(Map<String, String> parameters) {
        if (parameters == null) return false;
        for (var entry : parameters.entrySet()) {
            if (!Set.of("flat.height", "seed", "structures", "biome", "generator", "generator-settings")
                    .contains(entry.getKey()) && !entry.getKey().matches("property\\.[a-zA-Z0-9_-]{1,80}"))
                return false;
            if (entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 4096)
                return false;
        }
        return true;
    }

    /**
     * 按世界类型和键值参数构造 Multiverse 创建选项。
     */
    private static CreateWorldOptions options(CreateMapRequest request) {
        Map<String, String> params = request.parameters();
        WorldType type = request.worldType();
        CreateWorldOptions options = CreateWorldOptions.worldName(request.worldName()).doFolderCheck(true)
                .generateStructures(Boolean.parseBoolean(params.getOrDefault("structures", "false")));
        if (params.containsKey("structures") && !Set.of("true", "false").contains(params.get("structures")))
            throw new IllegalArgumentException("structures 必须是 true 或 false");
        if (params.containsKey("flat.height") && type != WorldType.FLAT)
            throw new IllegalArgumentException("flat.height 仅支持 FLAT 世界");
        if (params.containsKey("flat.height") && params.containsKey("generator-settings"))
            throw new IllegalArgumentException("flat.height 与 generator-settings 不能同时设置");
        if (params.containsKey("seed")) options.seed(Long.parseLong(params.get("seed")));
        if (params.containsKey("biome")) options.biome(params.get("biome"));
        if (params.containsKey("generator")) options.generator(params.get("generator"));
        if (params.containsKey("generator-settings")) options.generatorSettings(params.get("generator-settings"));
        Map<String, String> properties = new HashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("property.")) properties.put(key.substring("property.".length()), value);
        });
        if (!properties.isEmpty()) options.worldPropertyStrings(properties);
        switch (type) {
            case FLAT -> {
                options.worldType(org.bukkit.WorldType.FLAT).environment(World.Environment.NORMAL);
                if (params.containsKey("flat.height")) {
                    int height = Integer.parseInt(params.get("flat.height"));
                    if (height < 1 || height > 320) throw new IllegalArgumentException("flat.height 超出 1-320");
                    JsonObject settings = new JsonObject();
                    JsonArray layers = new JsonArray();
                    JsonObject layer = new JsonObject();
                    layer.addProperty("block", "minecraft:stone");
                    layer.addProperty("height", height);
                    layers.add(layer);
                    settings.add("layers", layers);
                    settings.addProperty("biome", params.getOrDefault("biome", "minecraft:plains"));
                    options.generatorSettings(settings.toString());
                }
            }
            case NORMAL -> options.worldType(org.bukkit.WorldType.NORMAL).environment(World.Environment.NORMAL);
            case VOID -> {
                if (!Bukkit.getPluginManager().isPluginEnabled("VoidGen"))
                    throw new IllegalArgumentException("VOID 世界需要 VoidGen");
                options.worldType(org.bukkit.WorldType.FLAT).environment(World.Environment.NORMAL)
                        .generator(params.getOrDefault("generator", "VoidGen:{}"));
            }
            case NETHER -> options.environment(World.Environment.NETHER).worldType(org.bukkit.WorldType.NORMAL);
            case END -> options.environment(World.Environment.THE_END).worldType(org.bukkit.WorldType.NORMAL);
        }
        return options;
    }

    @Override
    public synchronized OperationResult<MapInstance> importWorld(ImportMapRequest request) {
        // 导入仅接收服务端根目录中的现有世界；先校验名称和文件，再写入 MMC 数据。
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || request == null || request.worldType() == null
                || !validMapInput(request.worldName(), request.groupId(), request.displayName()))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId, FailureReason.INVALID_INPUT);
        String name = request.worldName();
        Path root = Path.of("").toAbsolutePath().normalize();
        Path folder = root.resolve(name);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId, FailureReason.WORLD_NOT_FOUND);
        if (!Files.isRegularFile(folder.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId, FailureReason.INVALID_INPUT);
        // 环境和虚空生成器只属于导入流程；其余注册步骤与创建共用。
        if (request.worldType() == WorldType.VOID && !Bukkit.getPluginManager().isPluginEnabled("VoidGen"))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId, FailureReason.WORLD_BACKEND_FAILED);
        ImportWorldOptions options = ImportWorldOptions.worldName(name).doFolderCheck(true)
                .environment(switch (request.worldType()) {
                    case NETHER -> World.Environment.NETHER;
                    case END -> World.Environment.THE_END;
                    default -> World.Environment.NORMAL;
                });
        if (request.worldType() == WorldType.VOID) options.generator("VoidGen:{}");
        return registerWorld(OperationName.IMPORT, operationId, name, request.groupId(), request.displayName(),
                request.ownerId(), () -> {
                    var result = MultiverseCoreApi.get().getWorldManager().importWorld(options);
                    if (result.isFailure()) logger.warning("导入世界失败 " + name + ": " + result.getFailureReason());
                    return result.getOrNull();
                });
    }

    /**
     * 创建和导入共用的 MMC 注册流程；世界操作由调用方提供，失败时保留 PENDING 快照。
     */
    private OperationResult<MapInstance> registerWorld(OperationName operation, UUID operationId, String name,
                                                       String requestedGroup, String requestedAlias, UUID ownerId,
                                                       Supplier<LoadedMultiverseWorld> worldAction) {
        String groupId = blank(requestedGroup) ? name : requestedGroup;
        String alias = blank(requestedAlias) ? name : MapAliasServiceImpl.stripColor(requestedAlias);
        if (alias.isBlank() || alias.length() > 255)
            return new OperationResult.Failure<>(operation, operationId, FailureReason.INVALID_INPUT);
        MapInstance pending = new MapInstance(UUID.randomUUID(), name, groupId, alias,
                new MapSettings(true, false, false), MapStatus.PENDING, null);
        try {
            var worldManager = MultiverseCoreApi.get().getWorldManager();
            // 拒绝已由 MMC、Bukkit 或 Multiverse 管理的同名世界。
            if (maps.findByWorldName(name) != null || Bukkit.getWorld(name) != null || worldManager.isWorld(name))
                return new OperationResult.Failure<>(operation, operationId, FailureReason.ALREADY_EXISTS);

            // 先保存操作、地图组和 PENDING 地图，再调用 Multiverse 改动世界。
            operations.save(new OperationRecord(operationId, operation, name, OperationStage.STARTED, null));
            if (maps.findGroup(groupId) == null) maps.saveGroup(new MapGroup(groupId, Set.of()));
            maps.save(pending);
            LoadedMultiverseWorld world = worldAction.get();
            if (world == null) return failed(operationId, name, FailureReason.WORLD_BACKEND_FAILED, operation);
            operations.save(new OperationRecord(operationId, operation, name, OperationStage.WORLD_CHANGED, null));

            // 世界就绪后先应用 v3 的 MV 和 gamerule 初始化设置，失败时保持 PENDING。
            try {
                initializeWorld(world, pending.id(), requestedAlias);
            } catch (RuntimeException exception) {
                logger.warning("初始化世界失败 " + name + ": " + exception.getMessage());
                return failed(operationId, name, FailureReason.WORLD_BACKEND_FAILED, operation);
            }

            // 记录出生点，并写入首位管理员的 MMC 角色。
            Location location = world.getSpawnLocation();
            SpawnPoint spawn = location == null ? null : new SpawnPoint(name, location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), location.getPitch());
            pending = new MapInstance(pending.id(), name, groupId, alias, pending.settings(), MapStatus.PENDING, spawn);
            maps.save(pending);
            if (ownerId != null) roles.add(groupId, MapRole.ADMIN, ownerId);
            operations.save(new OperationRecord(operationId, operation, name, OperationStage.DATA_SAVED, null));

            // 地图组在对应世界继承 worldbase；首位管理员继承地图组。
            if (!permissions.ensureGroupInheritance(groupId, Set.of(name)).join()
                    || (ownerId != null && !permissions.setBuilderAccess(groupId, ownerId, true)))
                return failed(operationId, name, FailureReason.PERMISSION_SYNC_FAILED, operation);

            // MMC 数据与 LP 投影均完成后，地图才允许转为 ACTIVE。
            MapInstance active = new MapInstance(pending.id(), name, groupId, alias, pending.settings(), MapStatus.ACTIVE, spawn);
            maps.save(active);
            operations.save(new OperationRecord(operationId, operation, name, OperationStage.COMPLETED, null));
            return new OperationResult.Success<>(operation, operationId, active);
        } catch (StorageException exception) {
            logger.severe(operation + " 地图存储失败 " + name + ": " + exception.getMessage());
            return failed(operationId, name, FailureReason.STORAGE_FAILED, operation);
        } catch (Exception exception) {
            logger.severe(operation + " 地图操作失败 " + name + ": " + exception.getMessage());
            return failed(operationId, name, FailureReason.INTERNAL_ERROR, operation);
        }
    }

    /**
     * 应用 v3 的世界初始化设置；所有创建、导入和手动初始化均调用此方法。
     */
    private void initializeWorld(LoadedMultiverseWorld world, UUID mapId, String requestedAlias) {
        // 创建或导入时未指定别名则使用地图名；手动初始化使用当前保存的别名。
        OperationResult<Void> aliasResult = blank(requestedAlias)
                ? aliases.setAlias(mapId) : aliases.setAlias(mapId, requestedAlias);
        if (!(aliasResult instanceof OperationResult.Success<?>))
            throw new IllegalStateException("Multiverse 别名同步失败: " + world.getName());
        world.setDifficulty(Difficulty.PEACEFUL).get();
        world.setAutoLoad(true).get();
        world.setKeepSpawnInMemory(false).get();
        world.setGameMode(GameMode.CREATIVE).get();

        // 普通生物分类禁用生成；保留 MISC，避免影响盔甲架等非生物实体。
        for (SpawnCategory category : new SpawnCategory[]{SpawnCategory.ANIMAL, SpawnCategory.WATER_ANIMAL,
                SpawnCategory.WATER_AMBIENT, SpawnCategory.MONSTER, SpawnCategory.WATER_UNDERGROUND_CREATURE,
                SpawnCategory.AMBIENT, SpawnCategory.AXOLOTL}) {
            world.getEntitySpawnConfig().getSpawnCategoryConfig(category).setSpawn(false).get();
        }
        world.getEntitySpawnConfig().getSpawnCategoryConfig(SpawnCategory.MISC).setSpawn(true).get();

        World bukkitWorld = world.getBukkitWorld().getOrNull();
        if (bukkitWorld == null) throw new IllegalStateException("Bukkit 世界未加载: " + world.getName());
        setRule(bukkitWorld, GameRule.RANDOM_TICK_SPEED, 0);
        setRule(bukkitWorld, GameRule.DO_FIRE_TICK, false);
        setRule(bukkitWorld, GameRule.DO_WEATHER_CYCLE, false);
        setRule(bukkitWorld, GameRule.DO_DAYLIGHT_CYCLE, false);
        setRule(bukkitWorld, GameRule.MOB_GRIEFING, false);
        setRule(bukkitWorld, GameRule.DO_MOB_SPAWNING, false);
        setRule(bukkitWorld, GameRule.ANNOUNCE_ADVANCEMENTS, false);
        MultiverseCoreApi.get().getWorldManager().saveWorldsConfig().get();
    }

    /**
     * 校验 gamerule 已被 Bukkit 接受。
     */
    private static <T> void setRule(World world, GameRule<T> rule, T value) {
        if (!world.setGameRule(rule, value))
            throw new IllegalStateException("无法设置 gamerule: " + rule.getName());
    }

    @Override
    public synchronized OperationResult<MapInstance> initialize(String worldName) {
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,100}"))
            return new OperationResult.Failure<>(OperationName.INITIALIZE, operationId, FailureReason.INVALID_INPUT);
        MapInstance map;
        try {
            map = maps.findByWorldName(worldName);
        } catch (StorageException exception) {
            logger.severe("读取待初始化地图失败 " + worldName + ": " + exception.getMessage());
            return new OperationResult.Failure<>(OperationName.INITIALIZE, operationId, FailureReason.STORAGE_FAILED);
        }
        if (map == null)
            return new OperationResult.Failure<>(OperationName.INITIALIZE, operationId, FailureReason.MAP_NOT_FOUND);
        try {
            var world = MultiverseCoreApi.get().getWorldManager().getLoadedWorld(worldName).getOrNull();
            if (world == null)
                return new OperationResult.Failure<>(OperationName.INITIALIZE, operationId, FailureReason.WORLD_NOT_FOUND);
            // 手动初始化仅重放 MV 和 gamerule 设置，不重建角色或改变地图状态。
            operations.save(new OperationRecord(operationId, OperationName.INITIALIZE, worldName, OperationStage.STARTED, null));
            initializeWorld(world, map.id(), map.displayName());
            operations.save(new OperationRecord(operationId, OperationName.INITIALIZE, worldName, OperationStage.COMPLETED, null));
            return new OperationResult.Success<>(OperationName.INITIALIZE, operationId, map);
        } catch (StorageException exception) {
            logger.severe("保存初始化记录失败 " + worldName + ": " + exception.getMessage());
            return failed(operationId, worldName, FailureReason.STORAGE_FAILED, OperationName.INITIALIZE);
        } catch (RuntimeException exception) {
            logger.warning("初始化世界失败 " + worldName + ": " + exception.getMessage());
            return failed(operationId, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.INITIALIZE);
        }
    }

    @Override
    public OperationResult<Void> delete(String worldName) {
        // TODO: 按阶段删除世界与 MMC 数据。
        return null;
    }

    @Override
    public synchronized OperationResult<MapInstance> load(String worldName) {
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || worldName == null || worldName.isBlank())
            return new OperationResult.Failure<>(OperationName.LOAD, operationId, FailureReason.INVALID_INPUT);
        MapInstance map;
        try {
            map = maps.findByWorldName(worldName);
        } catch (StorageException exception) {
            return new OperationResult.Failure<>(OperationName.LOAD, operationId, FailureReason.STORAGE_FAILED);
        }
        if (map == null) return new OperationResult.Failure<>(OperationName.LOAD, operationId, FailureReason.MAP_NOT_FOUND);
        if (map.status() != MapStatus.ACTIVE)
            return new OperationResult.Failure<>(OperationName.LOAD, operationId, FailureReason.ACCESS_DENIED);
        try {
            var manager = MultiverseCoreApi.get().getWorldManager();
            var existing = manager.getLoadedWorld(worldName).getOrNull();
            if (existing != null) return new OperationResult.Success<>(OperationName.LOAD, operationId, map);
            if (!manager.isWorld(worldName))
                return new OperationResult.Failure<>(OperationName.LOAD, operationId, FailureReason.WORLD_NOT_FOUND);
            operations.save(new OperationRecord(operationId, OperationName.LOAD, worldName, OperationStage.STARTED, null));
            var loaded = manager.loadWorld(worldName);
            if (loaded.isFailure()) {
                logger.warning("加载世界失败 " + worldName + ": " + loaded.getFailureReason());
                return failed(operationId, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.LOAD);
            }
            operations.save(new OperationRecord(operationId, OperationName.LOAD, worldName, OperationStage.WORLD_CHANGED, null));
            operations.save(new OperationRecord(operationId, OperationName.LOAD, worldName, OperationStage.COMPLETED, null));
            return new OperationResult.Success<>(OperationName.LOAD, operationId, map);
        } catch (StorageException exception) {
            return failed(operationId, worldName, FailureReason.STORAGE_FAILED, OperationName.LOAD);
        } catch (RuntimeException exception) {
            logger.warning("加载世界失败 " + worldName + ": " + exception.getMessage());
            return failed(operationId, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.LOAD);
        }
    }

    @Override
    public OperationResult<Void> scheduleUnload(String worldName) {
        // TODO: 安排延迟卸载任务。
        return null;
    }

    @Override
    public OperationResult<Void> cancelScheduledUnload(String worldName) {
        // TODO: 取消延迟卸载任务。
        return null;
    }

    @Override
    public WorldLoadState state(String worldName) {
        // TODO: 查询世界实际加载状态。
        return null;
    }
}
