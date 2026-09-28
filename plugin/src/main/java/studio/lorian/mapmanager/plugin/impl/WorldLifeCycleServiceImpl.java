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
import org.mvplugins.multiverse.core.world.options.DeleteWorldOptions;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.api.service.WorldVersionService;
import studio.lorian.mapmanager.api.service.WorldGateway;
import studio.lorian.mapmanager.api.service.MapAliasService;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRecord;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationStage;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;
import studio.lorian.mapmanager.plugin.internal.world.WorldGatewayImpl;

import java.util.Map;
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 世界生命周期服务；创建流程由这里统一编排。
 */
public final class WorldLifeCycleServiceImpl implements WorldLifeCycleService {
    private static final Pattern RESERVED_NAME_PATTERN = Pattern.compile(
            "(?:cache|config|world|log|test|overworld|default|worldbase|apply|public|private|"
                    + "(?:plugin|version|admin|mapmanager|builder|visitor|lib|mmc).*)");
    private final MapRepository maps;
    private final RoleRepository roles;
    private final OperationRepository operations;
    private final WorldBaseProjection permissions;
    private final MapAliasService aliases;
    private final WorldVersionService worldVersions;
    private final WorldGateway worldGateway;
    private final Pattern deletionPattern;
    private final Pattern blockedCreationPattern;
    private final Logger logger;

    public WorldLifeCycleServiceImpl(MapRepository maps, RoleRepository roles, OperationRepository operations,
                                     WorldBaseProjection permissions, MapAliasService aliases,
                                     WorldVersionService worldVersions, Pattern deletionPattern,
                                     Pattern blockedCreationPattern, Logger logger) {
        this.maps = maps;
        this.roles = roles;
        this.operations = operations;
        this.permissions = permissions;
        this.aliases = aliases;
        this.worldVersions = worldVersions;
        this.worldGateway = new WorldGatewayImpl();
        this.deletionPattern = deletionPattern;
        this.blockedCreationPattern = blockedCreationPattern;
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
                        logger.warning(Messages.text("world.create_failed", "world", request.worldName(),
                                "reason", result.getFailureReason()));
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
            logger.severe(Messages.text("world.operation_save_failed", "error", exception.getMessage()));
        }
        MapInstance map;
        try {
            map = maps.findByWorldName(name);
        } catch (Exception exception) {
            logger.severe(Messages.text("world.failed_state_read_failed", "error", exception.getMessage()));
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
    private boolean validMapInput(String worldName, String groupId, String displayName) {
        return worldName != null && worldName.matches("[a-z0-9_-]{4,16}")
                && !blockedWorldName(worldName)
                && (blank(groupId) || groupId.matches("[a-z0-9_-]{1,16}"))
                && !Set.of("default", "worldbase").contains(blank(groupId) ? worldName : groupId)
                && (blank(displayName) || displayName.length() <= 255);
    }

    /** 内置与配置的正则分别检查完整地图名，任一匹配即禁止创建或导入。 */
    private boolean blockedWorldName(String name) {
        return RESERVED_NAME_PATTERN.matcher(name).matches() || blockedCreationPattern.matcher(name).matches();
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
    static CreateWorldOptions options(CreateMapRequest request) {
        Map<String, String> params = request.parameters();
        WorldType type = request.worldType();
        CreateWorldOptions options = CreateWorldOptions.worldName(request.worldName()).doFolderCheck(true)
                .generateStructures(Boolean.parseBoolean(params.getOrDefault("structures", "false")));
        if (params.containsKey("structures") && !Set.of("true", "false").contains(params.get("structures")))
            throw new IllegalArgumentException(Messages.text("errors.structures_boolean"));
        if (params.containsKey("flat.height")
                && !Set.of(WorldType.FLAT, WorldType.SIFT, WorldType.NETHER, WorldType.END).contains(type))
            throw new IllegalArgumentException(Messages.text("errors.flat_only"));
        if (params.containsKey("flat.height") && params.containsKey("generator-settings"))
            throw new IllegalArgumentException(Messages.text("errors.flat_generator_conflict"));
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
            case FLAT, SIFT, NETHER, END -> {
                World.Environment environment = switch (type) {
                    case NETHER -> World.Environment.NETHER;
                    case END -> World.Environment.THE_END;
                    default -> World.Environment.NORMAL;
                };
                options.worldType(org.bukkit.WorldType.FLAT).environment(environment);
                if (params.containsKey("flat.height")) {
                    int height = Integer.parseInt(params.get("flat.height"));
                    if (height < 2 || height > 320) throw new IllegalArgumentException(Messages.text("errors.flat_height_range"));
                    JsonObject settings = new JsonObject();
                    JsonArray layers = new JsonArray();
                    JsonObject bedrockLayer = new JsonObject();
                    bedrockLayer.addProperty("block", "minecraft:bedrock");
                    bedrockLayer.addProperty("height", 1);
                    layers.add(bedrockLayer);
                    if (height > 2) {
                        JsonObject dirtLayer = new JsonObject();
                        dirtLayer.addProperty("block", "minecraft:dirt");
                        dirtLayer.addProperty("height", height - 2);
                        layers.add(dirtLayer);
                    }
                    JsonObject grassLayer = new JsonObject();
                    grassLayer.addProperty("block", "minecraft:grass_block");
                    grassLayer.addProperty("height", 1);
                    layers.add(grassLayer);
                    settings.add("layers", layers);
                    settings.addProperty("biome", params.getOrDefault("biome", "minecraft:plains"));
                    options.generatorSettings(settings.toString());
                }
            }
            case NORMAL -> options.worldType(org.bukkit.WorldType.NORMAL).environment(World.Environment.NORMAL);
            case VOID -> {
                if (!Bukkit.getPluginManager().isPluginEnabled("VoidGen"))
                    throw new IllegalArgumentException(Messages.text("errors.voidgen_required"));
                options.worldType(org.bukkit.WorldType.FLAT).environment(World.Environment.NORMAL)
                        .generator(params.getOrDefault("generator", "VoidGen:{}"));
            }
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
        if (!worldVersions.isCompatible(folder))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId,
                    FailureReason.WORLD_VERSION_INCOMPATIBLE);
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
                    if (result.isFailure()) logger.warning(Messages.text("world.import_failed", "world", name,
                            "reason", result.getFailureReason()));
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
                logger.warning(Messages.text("world.initialize_failed", "world", name, "error", exception.getMessage()));
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
            logger.severe(Messages.text("world.storage_failed", "operation", Messages.text("operations." + operation),
                    "world", name, "error", exception.getMessage()));
            return failed(operationId, name, FailureReason.STORAGE_FAILED, operation);
        } catch (Exception exception) {
            logger.severe(Messages.text("world.operation_failed", "operation", Messages.text("operations." + operation),
                    "world", name, "error", exception.getMessage()));
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
            throw new IllegalStateException(Messages.text("errors.mv_alias_failed", "world", world.getName()));
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
        if (bukkitWorld == null) throw new IllegalStateException(Messages.text("errors.bukkit_world_unloaded", "world", world.getName()));
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
            throw new IllegalStateException(Messages.text("errors.gamerule_failed", "rule", rule.getName()));
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
            logger.severe(Messages.text("world.pending_read_failed", "world", worldName, "error", exception.getMessage()));
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
            logger.severe(Messages.text("world.init_record_failed", "world", worldName, "error", exception.getMessage()));
            return failed(operationId, worldName, FailureReason.STORAGE_FAILED, OperationName.INITIALIZE);
        } catch (RuntimeException exception) {
            logger.warning(Messages.text("world.initialize_failed", "world", worldName, "error", exception.getMessage()));
            return failed(operationId, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.INITIALIZE);
        }
    }

    @Override
    public synchronized OperationResult<Void> delete(String worldName) {
        UUID id = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || worldName == null || !worldName.matches("[a-z0-9_-]{4,16}"))
            return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.INVALID_INPUT);
        if (!deletionPattern.matcher(worldName).matches())
            return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.ACCESS_DENIED);
        try {
            MapInstance map = maps.findByWorldName(worldName);
            if (map == null) return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.MAP_NOT_FOUND);
            if (map.status() != MapStatus.ACTIVE && map.status() != MapStatus.DELETING)
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.OPERATION_IN_PROGRESS);

            var manager = MultiverseCoreApi.get().getWorldManager();
            var defaultWorld = manager.getDefaultWorld().getOrNull();
            if (defaultWorld != null && defaultWorld.getName().equals(map.worldName()))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.ACCESS_DENIED);
            if (Bukkit.getWorlds().stream().findFirst().map(World::getName).filter(map.worldName()::equals).isPresent())
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.ACCESS_DENIED);
            World loaded = Bukkit.getWorld(map.worldName());
            if (loaded != null && !loaded.getPlayers().isEmpty())
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.OPERATION_IN_PROGRESS);

            Path folder = worldGateway.worldDirectory(map.worldName());
            Path parent = worldGateway.worldParentDirectory();
            if (!folder.normalize().getParent().equals(parent.normalize()) || Files.isSymbolicLink(folder))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.INVALID_INPUT);
            boolean worldExists = manager.isWorld(map.worldName());
            boolean folderExists = Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS);
            if (map.status() == MapStatus.ACTIVE && (!worldExists || !folderExists
                    || !Files.isRegularFile(folder.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.WORLD_NOT_FOUND);
            if (map.status() == MapStatus.DELETING && (worldExists != folderExists))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.WORLD_BACKEND_FAILED);

            operations.save(new OperationRecord(id, OperationName.DELETE, map.worldName(), OperationStage.STARTED, null));
            if (map.status() != MapStatus.DELETING)
                maps.save(new MapInstance(map.id(), map.worldName(), map.groupId(), map.displayName(),
                        map.settings(), MapStatus.DELETING, map.spawn()));
            if (worldExists) {
                var world = manager.getWorld(map.worldName()).getOrNull();
                if (world == null) return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
                var result = manager.deleteWorld(DeleteWorldOptions.world(world));
                if (result.isFailure()) {
                    logger.warning(Messages.text("world.delete_failed", "world", map.worldName(),
                            "reason", result.getFailureReason()));
                    return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
                }
            }
            if (manager.isWorld(map.worldName()) || Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
                return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
            operations.save(new OperationRecord(id, OperationName.DELETE, map.worldName(), OperationStage.WORLD_CHANGED, null));

            // 先清除 LP 投影，再清除 MMC 数据；任一步失败都保留 DELETING 以供重试。
            boolean lastMap = maps.findAll().stream().noneMatch(other -> !other.id().equals(map.id())
                    && other.groupId().equals(map.groupId()));
            try {
                if (lastMap) {
                    Set<UUID> members = new java.util.HashSet<>(roles.members(map.groupId(), MapRole.ADMIN));
                    members.addAll(roles.members(map.groupId(), MapRole.BUILDER));
                    for (UUID member : members)
                        if (!permissions.setBuilderAccess(map.groupId(), member, false))
                            return deleteFailed(id, FailureReason.PERMISSION_SYNC_FAILED);
                    if (!permissions.removeGroup(map.groupId()))
                        return deleteFailed(id, FailureReason.PERMISSION_SYNC_FAILED);
                } else if (!permissions.removeWorldInheritance(map.groupId(), map.worldName())) {
                    return deleteFailed(id, FailureReason.PERMISSION_SYNC_FAILED);
                }
            } catch (RuntimeException exception) {
                logger.warning(Messages.text("world.projection_cleanup_failed", "world", map.worldName(),
                        "error", exception.getMessage()));
                return deleteFailed(id, FailureReason.PERMISSION_SYNC_FAILED);
            }

            if (lastMap) {
                roles.removeScope(map.groupId(), MapRole.ADMIN);
                roles.removeScope(map.groupId(), MapRole.BUILDER);
            }
            maps.delete(map.id());
            if (lastMap) maps.deleteGroup(map.groupId());
            operations.save(new OperationRecord(id, OperationName.DELETE, map.worldName(), OperationStage.COMPLETED, null));
            return new OperationResult.Success<>(OperationName.DELETE, id, null);
        } catch (StorageException exception) {
            logger.severe(Messages.text("world.delete_storage_failed", "world", worldName, "error", exception.getMessage()));
            return deleteFailed(id, FailureReason.STORAGE_FAILED);
        } catch (RuntimeException exception) {
            logger.warning(Messages.text("world.delete_operation_failed", "world", worldName, "error", exception.getMessage()));
            return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
        }
    }

    private OperationResult<Void> deleteFailed(UUID id, FailureReason reason) {
        try {
            OperationRecord record = operations.find(id);
            if (record != null) operations.save(new OperationRecord(id, OperationName.DELETE,
                    record.worldName(), OperationStage.FAILED, reason));
        } catch (RuntimeException exception) {
            logger.severe(Messages.text("world.delete_record_failed", "error", exception.getMessage()));
        }
        return new OperationResult.Failure<>(OperationName.DELETE, id, reason);
    }
}
