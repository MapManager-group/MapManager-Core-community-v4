package studio.lorian.mapmanager.plugin.impl;

import java.util.function.Predicate;
import org.bukkit.generator.WorldInfo;
import org.mvplugins.multiverse.core.utils.result.Attempt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.DeleteWorldOptions;
import org.mvplugins.multiverse.core.world.options.LoadWorldOptions;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.Environment;
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
import studio.lorian.mapmanager.plugin.internal.storage.RecoveryJournal;
import studio.lorian.mapmanager.plugin.internal.world.WorldGatewayImpl;
import studio.lorian.mapmanager.plugin.internal.world.FlatChunkGenerator;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
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
    private final Predicate<String> defaultWorld;
    private final MapRepository maps;
    private final RoleRepository roles;
    private final OperationRepository operations;
    private final WorldBaseProjection permissions;
    private final MapAliasService aliases;
    private final WorldVersionService worldVersions;
    private final WorldGateway worldGateway;
    private final Pattern blockedCreationPattern;
    private final Logger logger;
    private final RecoveryJournal journal;

    public WorldLifeCycleServiceImpl(MapRepository maps, RoleRepository roles, OperationRepository operations,
                                     WorldBaseProjection permissions, MapAliasService aliases,
                                     WorldVersionService worldVersions,
                                     Pattern blockedCreationPattern, Logger logger, Path dataDirectory) {
        this(maps, roles, operations, permissions, aliases, worldVersions, blockedCreationPattern, logger, dataDirectory, world -> false);
    }

    public WorldLifeCycleServiceImpl(MapRepository maps, RoleRepository roles, OperationRepository operations,
                                     WorldBaseProjection permissions, MapAliasService aliases,
                                     WorldVersionService worldVersions,
                                     Pattern blockedCreationPattern, Logger logger, Path dataDirectory, Predicate<String> defaultWorld) {
        this.defaultWorld = defaultWorld;
        this.maps = maps;
        this.roles = roles;
        this.operations = operations;
        this.permissions = permissions;
        this.aliases = aliases;
        this.worldVersions = worldVersions;
        this.worldGateway = new WorldGatewayImpl();
        this.blockedCreationPattern = blockedCreationPattern;
        this.logger = logger;
        this.journal = new RecoveryJournal(dataDirectory.resolve("world-recovery"));
    }

    @Override
    public synchronized OperationResult<MapInstance> create(CreateMapRequest request) {
        // 先校验 MMC 输入和生成器参数，避免留下无法执行的 PENDING 记录。
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || request == null || !validMapInput(request.worldName(),
                request.groupId(), request.displayName()) || request.worldType() == null
                || request.environment() == null
                || !validParameters(request.parameters()))
            return new OperationResult.Failure<>(OperationName.CREATE, operationId, FailureReason.INVALID_INPUT);
        if (request.environment() == Environment.SIFT)
            return new OperationResult.Failure<>(OperationName.CREATE, operationId,
                    FailureReason.UNSUPPORTED_ENVIRONMENT);
        if (request.worldType() == WorldType.VOID && !Bukkit.getPluginManager().isPluginEnabled("VoidGen"))
            return new OperationResult.Failure<>(OperationName.CREATE, operationId, FailureReason.WORLD_BACKEND_FAILED);
        final CreateWorldOptions options;
        try {
            options = options(request);
        } catch (IllegalArgumentException exception) {
            return new OperationResult.Failure<>(OperationName.CREATE, operationId, FailureReason.INVALID_INPUT);
        }
        // 世界生成方式只属于创建流程；其余注册步骤与导入共用。
        return registerWorld(OperationName.CREATE, operationId, request.worldName(), request.groupId(),
                request.displayName(), request.ownerId(), request, null, () -> {
                    var result = MultiverseCoreApi.get().getWorldManager().createWorld(options);
                    if (result.isFailure())
                        logger.warning(Messages.text("world.create_failed", "world", request.worldName(),
                                "reason", result.getFailureReason()));
                    return result;
                });
    }

    /**
     * 记录失败，并在已有地图记录时返回其待处理快照。
     */
    private OperationResult<MapInstance> failed(UUID id, String name, FailureReason reason, OperationName operation) {
        return failed(id, name, reason, operation, null);
    }

    private OperationResult<MapInstance> failed(UUID id, String name, FailureReason reason, OperationName operation, String detail) {
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
        return map == null ? new OperationResult.Failure<>(operation, id, reason, detail)
                : new OperationResult.Partial<>(operation, id, map, reason, detail);
    }

    private OperationResult<MapInstance> backendFailed(UUID id, String name, OperationName operation, Attempt<?, ?> result) {
        String detail = result.getFailureMessage().formatted();
        // Multiverse stores the original exception message in {error}; its raw fallback is only "Failed!".
        String[] replacements = result.getFailureMessage().getReplacements();
        for (int i = 0; i < replacements.length; i += 2) {
            if ("{error}".equals(replacements[i])) {
                detail = replacements[i + 1];
                break;
            }
        }
        return failed(id, name, FailureReason.WORLD_BACKEND_FAILED, operation, detail);
    }

    static String flatSurfaceError(CreateMapRequest request, WorldInfo world) {
        if (request == null || request.worldType() != WorldType.FLAT || !request.parameters().containsKey("flat.height"))
            return null;
        try {
            FlatChunkGenerator.validateSurface(Integer.parseInt(request.parameters().get("flat.height")),
                    world.getMinHeight(), world.getMaxHeight());
            return null;
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
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
        return worldName != null && !defaultWorld.test(worldName) && worldName.matches("[a-z0-9_-]{4,16}")
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
     * 按生成器类型、维度环境和键值参数构造 Multiverse 创建选项。
     */
    static CreateWorldOptions options(CreateMapRequest request) {
        if (request == null || request.worldType() == null || request.environment() == null)
            throw new IllegalArgumentException("World type and environment are required");
        Map<String, String> params = request.parameters();
        WorldType type = request.worldType();
        World.Environment environment = bukkitEnvironment(request.environment());
        boolean otherDimensionEnvironment = otherDimensionEnvironment(request.environment());
        CreateWorldOptions options = CreateWorldOptions.worldName(request.worldName()).doFolderCheck(true)
                .generateStructures(Boolean.parseBoolean(params.getOrDefault("structures", "false")));
        if (params.containsKey("structures") && !Set.of("true", "false").contains(params.get("structures")))
            throw new IllegalArgumentException(Messages.text("errors.structures_boolean"));
        if (params.containsKey("flat.height") && type != WorldType.FLAT)
            throw new IllegalArgumentException(Messages.text("errors.flat_only"));
        if (params.containsKey("flat.height") && params.containsKey("generator-settings"))
            throw new IllegalArgumentException(Messages.text("errors.flat_generator_conflict"));
        if (params.containsKey("seed")) options.seed(Long.parseLong(params.get("seed")));
        if (params.containsKey("biome")) options.biome(biomeProvider(params.get("biome")));
        if (params.containsKey("generator")) options.generator(params.get("generator"));
        if (params.containsKey("generator-settings")) options.generatorSettings(params.get("generator-settings"));
        Map<String, String> properties = new HashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("property.")) properties.put(key.substring("property.".length()), value);
        });
        if (!properties.isEmpty()) options.worldPropertyStrings(properties);
        switch (type) {
            case FLAT -> {
                options.worldType(org.bukkit.WorldType.FLAT).environment(environment);
                String flatSettings = params.get("generator-settings");
                int flatHeightLimit = otherDimensionEnvironment ? 254 : 320;
                if (params.containsKey("flat.height")) {
                    int surfaceY = Integer.parseInt(params.get("flat.height"));
                    // Keep vanilla flat settings valid while the plugin resolves the actual world bounds.
                    JsonObject settings = JsonParser.parseString(defaultFlatSettings()).getAsJsonObject();
                    settings.addProperty("surface_y", surfaceY);
                    settings.addProperty("biome", params.getOrDefault("biome", "minecraft:plains"));
                    flatSettings = settings.toString();
                }
                if (otherDimensionEnvironment || params.containsKey("flat.height")) {
                    if (flatSettings == null) flatSettings = defaultFlatSettings();
                    // Absolute surface Y needs the actual world bounds, which are only available during generation.
                    String generatorId = FlatChunkGenerator.generatorId(flatSettings);
                    if (!params.containsKey("flat.height")) {
                        long layerHeight = FlatChunkGenerator.totalHeight(flatSettings);
                        if (layerHeight < 2 || layerHeight > flatHeightLimit)
                            throw new IllegalArgumentException(Messages.text("errors.flat_dimension_height_range"));
                    }
                    options.generatorSettings(flatSettings);
                    if (!params.containsKey("biome")) {
                        String settingsBiome = flatSettingsBiome(flatSettings);
                        options.biome(biomeProvider(settingsBiome == null ? "minecraft:plains" : settingsBiome));
                    }
                    if (!params.containsKey("generator")) {
                        options.generator("MapManager-Core:" + generatorId);
                        if (params.containsKey("flat.height")) options.biome(null);
                    }
                } else if (flatSettings != null) {
                    options.generatorSettings(flatSettings);
                }
            }
            case NORMAL -> options.worldType(org.bukkit.WorldType.NORMAL).environment(environment);
            case VOID -> {
                options.worldType(org.bukkit.WorldType.FLAT).environment(environment)
                        .generator(params.getOrDefault("generator", "VoidGen:{}"));
            }
        }
        return options;
    }

    private static boolean otherDimensionEnvironment(Environment environment) {
        return environment == Environment.NETHER || environment == Environment.THE_END || environment == Environment.SIFT;
    }

    private static World.Environment bukkitEnvironment(Environment environment) {
        return switch (environment) {
            case NORMAL -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case THE_END -> World.Environment.THE_END;
            case SIFT -> throw new IllegalArgumentException("SIFT environment is not supported yet");
        };
    }

    private static String biomeProvider(String biome) {
        NamespacedKey key = NamespacedKey.fromString(biome);
        if (key == null) return biome;
        if (Bukkit.getServer() == null) {
            return key.getNamespace().equals("minecraft") ? "@single:" + key : biome;
        }
        if (Registry.BIOME.get(key) != null) return "@single:" + key;
        if (key.getNamespace().equals("minecraft"))
            throw new IllegalArgumentException("Unknown Minecraft biome: " + key);
        return biome;
    }

    private static String flatSettingsBiome(String settings) {
        JsonObject json = JsonParser.parseString(settings).getAsJsonObject();
        return json.has("biome") && json.get("biome").isJsonPrimitive()
                ? json.get("biome").getAsString() : null;
    }

    private static String defaultFlatSettings() {
        JsonObject settings = new JsonObject();
        JsonArray layers = new JsonArray();
        for (var layer : List.of(Map.entry("minecraft:bedrock", 1), Map.entry("minecraft:dirt", 2),
                Map.entry("minecraft:grass_block", 1))) {
            JsonObject jsonLayer = new JsonObject();
            jsonLayer.addProperty("block", layer.getKey());
            jsonLayer.addProperty("height", layer.getValue());
            layers.add(jsonLayer);
        }
        settings.add("layers", layers);
        settings.addProperty("biome", "minecraft:plains");
        return settings.toString();
    }

    @Override
    public synchronized OperationResult<MapInstance> importWorld(ImportMapRequest request) {
        // 导入仅接收服务端根目录中的现有世界；先校验名称和文件，再写入 MMC 数据。
        UUID operationId = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || request == null || request.worldType() == null
                || request.environment() == null
                || !validMapInput(request.worldName(), request.groupId(), request.displayName()))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId, FailureReason.INVALID_INPUT);
        if (request.environment() == Environment.SIFT)
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId,
                    FailureReason.UNSUPPORTED_ENVIRONMENT);
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
        // 环境和导入时的生成器配置由 MV 保存；其余注册步骤与创建共用。
        if (request.worldType() == WorldType.VOID && !Bukkit.getPluginManager().isPluginEnabled("VoidGen"))
            return new OperationResult.Failure<>(OperationName.IMPORT, operationId, FailureReason.WORLD_BACKEND_FAILED);
        ImportWorldOptions options = importOptions(request);
        return registerWorld(OperationName.IMPORT, operationId, name, request.groupId(), request.displayName(),
                request.ownerId(), null, request, () -> {
                    var result = MultiverseCoreApi.get().getWorldManager().importWorld(options);
                    if (result.isFailure()) logger.warning(Messages.text("world.import_failed", "world", name,
                            "reason", result.getFailureReason()));
                    return result;
                });
    }

    /** 构造导入时使用的环境和生成器配置；已有区块数据不会被替换。 */
    static ImportWorldOptions importOptions(ImportMapRequest request) {
        if (request == null || request.worldType() == null || request.environment() == null)
            throw new IllegalArgumentException("World type and environment are required");
        ImportWorldOptions options = ImportWorldOptions.worldName(request.worldName()).doFolderCheck(true)
                .environment(bukkitEnvironment(request.environment()));
        switch (request.worldType()) {
            case NORMAL -> options.generator(null);
            case FLAT -> {
                String settings = defaultFlatSettings();
                options.generator("MapManager-Core:" + FlatChunkGenerator.generatorId(settings))
                        .generatorSettings(settings).biome(biomeProvider(flatSettingsBiome(settings)));
            }
            case VOID -> options.generator("VoidGen:{}");
        }
        return options;
    }

    /**
     * 创建和导入共用的 MMC 注册流程；世界操作由调用方提供，失败时保留 PENDING 快照。
     */
    private OperationResult<MapInstance> registerWorld(OperationName operation, UUID operationId, String name,
                                                       String requestedGroup, String requestedAlias, UUID ownerId,
                                                       CreateMapRequest createRequest, ImportMapRequest importRequest,
                                                       Supplier<? extends Attempt<LoadedMultiverseWorld, ?>> worldAction) {
        String groupId = blank(requestedGroup) ? name : requestedGroup;
        String alias = blank(requestedAlias) ? name : MapAliasServiceImpl.stripColor(requestedAlias);
        if (alias.isBlank() || alias.length() > 255)
            return new OperationResult.Failure<>(operation, operationId, FailureReason.INVALID_INPUT);
        MapInstance pending = new MapInstance(UUID.randomUUID(), name, groupId, alias,
                new MapSettings(false, false, false), MapStatus.PENDING, null);
        try {
            var worldManager = MultiverseCoreApi.get().getWorldManager();
            // 拒绝已由 MMC、Bukkit 或 Multiverse 管理的同名世界。
            if (maps.findByWorldName(name) != null || Bukkit.getWorld(name) != null || worldManager.isWorld(name))
                return new OperationResult.Failure<>(operation, operationId, FailureReason.ALREADY_EXISTS);

            // 完整请求先落盘；在任何外部副作用前可确定性恢复初始所有者和生成器。
            journal.save(new RecoveryJournal.Plan(operationId, operation, pending, createRequest, importRequest,
                    null, null, null, null, null, Set.of()));
            operations.save(new OperationRecord(operationId, operation, name, OperationStage.STARTED, null));
            if (maps.findGroup(groupId) == null) maps.saveGroup(new MapGroup(groupId, Set.of()));
            maps.save(pending);
            var backendResult = worldAction.get();
            if (backendResult.isFailure()) return backendFailed(operationId, name, operation, backendResult);
            LoadedMultiverseWorld world = backendResult.get();
            String heightError = flatSurfaceError(createRequest, world.getBukkitWorld().get());
            if (heightError != null) return failed(operationId, name, FailureReason.INVALID_INPUT, operation, heightError);
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
            var sync = permissions.ensureGroupInheritance(groupId, maps.findGroup(groupId).worldNames());
            if ((sync.isDone() && !sync.join())
                    || (ownerId != null && !permissions.setBuilderAccess(groupId, ownerId, true)))
                return failed(operationId, name, FailureReason.PERMISSION_SYNC_FAILED, operation);

            // MMC 是准入权威；LP 已入持久队列，不阻塞主线程等待投影。
            MapInstance active = new MapInstance(pending.id(), name, groupId, alias, pending.settings(), MapStatus.ACTIVE, spawn);
            maps.save(active);
            operations.save(new OperationRecord(operationId, operation, name, OperationStage.COMPLETED, null));
            journal.remove(name);
            if (permissions.pending()) return new OperationResult.Partial<>(operation, operationId, active, FailureReason.PERMISSION_SYNC_PENDING);
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
        // 将初始化后的世界和 gamerule 落盘；现代 Paper 维度共用主世界 level.dat。
        bukkitWorld.save();
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
        if (defaultWorld.test(worldName))
            return new OperationResult.Failure<>(OperationName.INITIALIZE, operationId, FailureReason.ACCESS_DENIED);
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
            // 先关闭物理和爆炸，再重放世界设置；不重建角色或改变地图状态。
            operations.save(new OperationRecord(operationId, OperationName.INITIALIZE, worldName, OperationStage.STARTED, null));
            map = new MapInstance(map.id(), map.worldName(), map.groupId(), map.displayName(),
                    new MapSettings(false, false, map.settings().isPublic()), map.status(), map.spawn());
            maps.save(map);
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
        if (defaultWorld.test(worldName))
            return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.ACCESS_DENIED);
        try {
            MapInstance map = maps.findByWorldName(worldName);
            if (map == null) return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.MAP_NOT_FOUND);
            if (map.status() != MapStatus.ACTIVE && map.status() != MapStatus.DELETING)
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.INVALID_MAP_STATE);

            if (Bukkit.getWorlds().stream().findFirst().map(World::getName).filter(map.worldName()::equals).isPresent())
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.ACCESS_DENIED);
            World loaded = Bukkit.getWorld(map.worldName());
            if (loaded != null && !loaded.getPlayers().isEmpty())
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.WORLD_HAS_PLAYERS);

            RecoveryJournal.Plan plan = journal.read(worldName);
            if (plan != null && (!plan.map().id().equals(map.id()) || plan.operation() != OperationName.DELETE))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.RECOVERY_CONFLICT);
            var manager = MultiverseCoreApi.get().getWorldManager();
            var defaultWorld = manager.getDefaultWorld().getOrNull();
            if (defaultWorld != null && defaultWorld.getName().equals(map.worldName()))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.ACCESS_DENIED);

            Path folder = worldGateway.worldDirectory(map.worldName());
            Path parent = worldGateway.worldParentDirectory();
            if (!folder.normalize().getParent().equals(parent.normalize()) || Files.isSymbolicLink(folder))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.INVALID_INPUT);
            boolean worldExists = manager.isWorld(map.worldName());
            boolean folderExists = Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS);
            if (map.status() == MapStatus.ACTIVE && (!worldExists || !folderExists
                    || !worldVersions.isCompatible(folder)))
                return new OperationResult.Failure<>(OperationName.DELETE, id, FailureReason.WORLD_NOT_FOUND);
            if (plan == null) {
                var mv = manager.getWorld(worldName).getOrNull();
                Set<UUID> members = new java.util.HashSet<>(roles.members(map.groupId(), MapRole.ADMIN));
                members.addAll(roles.members(map.groupId(), MapRole.BUILDER));
                plan = new RecoveryJournal.Plan(id, OperationName.DELETE, map, null, null,
                        mv == null ? null : mv.getEnvironment().name(), mv == null ? null : mv.getGenerator(),
                        mv == null ? null : mv.getGeneratorSettings(), RecoveryJournal.identity(folder),
                        RecoveryJournal.directoryIdentity(folder), Set.copyOf(members));
                journal.save(plan);
            }
            if (folderExists) verifyFolder(plan, folder);
            operations.save(new OperationRecord(id, OperationName.DELETE, map.worldName(), OperationStage.STARTED, null));
            if (map.status() != MapStatus.DELETING)
                maps.save(new MapInstance(map.id(), map.worldName(), map.groupId(), map.displayName(),
                        map.settings(), MapStatus.DELETING, map.spawn()));
            if (worldExists && !folderExists) {
                var world = manager.getWorld(map.worldName()).getOrNull();
                if (world == null || manager.removeWorld(RemoveWorldOptions.world(world)).isFailure())
                    return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
            } else if (worldExists) {
                var world = manager.getWorld(map.worldName()).getOrNull();
                if (world == null) return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
                var result = manager.deleteWorld(DeleteWorldOptions.world(world));
                if (result.isFailure()) {
                    logger.warning(Messages.text("world.delete_failed", "world", map.worldName(),
                            "reason", result.getFailureReason()));
                    return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
                }
            }
            if (!manager.isWorld(map.worldName()) && Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                verifyFolder(plan, folder);
                deleteRemainder(folder);
            }
            if (manager.isWorld(map.worldName()) || Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
                return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
            operations.save(new OperationRecord(id, OperationName.DELETE, map.worldName(), OperationStage.WORLD_CHANGED, null));

            // 清理任务先落持久队列，再移除 MMC 数据；LP 在后台完成。
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
            completePlan(plan);
            if (permissions.pending()) return new OperationResult.Partial<>(OperationName.DELETE, id, null, FailureReason.PERMISSION_SYNC_PENDING);
            return new OperationResult.Success<>(OperationName.DELETE, id, null);
        } catch (StorageException exception) {
            logger.severe(Messages.text("world.delete_storage_failed", "world", worldName, "error", exception.getMessage()));
            return deleteFailed(id, FailureReason.STORAGE_FAILED);
        } catch (RuntimeException exception) {
            logger.warning(Messages.text("world.delete_operation_failed", "world", worldName, "error", exception.getMessage()));
            return deleteFailed(id, FailureReason.WORLD_BACKEND_FAILED);
        }
    }

    @Override
    public synchronized OperationResult<MapInstance> load(String worldName) {
        UUID id = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,100}"))
            return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.INVALID_INPUT);
        if (defaultWorld.test(worldName))
            return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.ACCESS_DENIED);
        try {
            MapInstance map = maps.findByWorldName(worldName);
            if (map == null) return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.MAP_NOT_FOUND);
            if (map.status() != MapStatus.ACTIVE) return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.ACCESS_DENIED);
            Path folder = worldGateway.worldDirectory(worldName);
            if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
                return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.WORLD_NOT_FOUND);
            if (!worldVersions.isCompatible(folder))
                return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.WORLD_VERSION_INCOMPATIBLE);
            var manager = MultiverseCoreApi.get().getWorldManager();
            var world = manager.getWorld(worldName).getOrNull();
            if (world == null) return new OperationResult.Failure<>(OperationName.LOAD, id, FailureReason.WORLD_NOT_FOUND);
            operations.save(new OperationRecord(id, OperationName.LOAD, worldName, OperationStage.STARTED, null));
            var loaded = manager.getLoadedWorld(worldName).getOrNull();
            if (loaded == null) loaded = manager.loadWorld(LoadWorldOptions.world(world).doFolderCheck(true)).getOrNull();
            if (loaded == null) return failed(id, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.LOAD);
            operations.save(new OperationRecord(id, OperationName.LOAD, worldName, OperationStage.COMPLETED, null));
            return new OperationResult.Success<>(OperationName.LOAD, id, map);
        } catch (StorageException error) { return failed(id, worldName, FailureReason.STORAGE_FAILED, OperationName.LOAD); }
        catch (RuntimeException error) { return failed(id, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.LOAD); }
    }

    @Override
    public synchronized OperationResult<MapInstance> recover(String worldName) {
        UUID id = UUID.randomUUID();
        if (!Bukkit.isPrimaryThread() || worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,100}"))
            return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.INVALID_INPUT);
        if (defaultWorld.test(worldName))
            return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.ACCESS_DENIED);
        try {
            RecoveryJournal.Plan plan = journal.read(worldName);
            if (plan == null) return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.INVALID_INPUT);
            MapInstance map = maps.findByWorldName(worldName);
            id = plan.operationId();
            if (map != null && !map.id().equals(plan.map().id()))
                return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.ALREADY_EXISTS);
            if (plan.operation() == OperationName.DELETE) {
                if (map != null) {
                    OperationResult<Void> result = delete(worldName);
                    if (result instanceof OperationResult.Failure<?> failure)
                        return new OperationResult.Failure<>(OperationName.RECOVER, result.operationId(), failure.reason());
                    if (result instanceof OperationResult.Partial<?> partial)
                        return new OperationResult.Partial<>(OperationName.RECOVER, result.operationId(), null, partial.reason());
                    return new OperationResult.Success<>(OperationName.RECOVER, result.operationId(), null);
                }
                // MMC 地图已移除但空组/操作记录未完成的断点。
                if (MultiverseCoreApi.get().getWorldManager().isWorld(worldName)
                        || Files.exists(worldGateway.worldDirectory(worldName), LinkOption.NOFOLLOW_LINKS))
                    return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.WORLD_BACKEND_FAILED);
                if (maps.findAll().stream().noneMatch(m -> m.groupId().equals(plan.map().groupId()))) {
                    for (UUID member : plan.members()) permissions.setBuilderAccess(plan.map().groupId(), member, false);
                    permissions.removeGroup(plan.map().groupId());
                    roles.removeScope(plan.map().groupId(), MapRole.ADMIN);
                    roles.removeScope(plan.map().groupId(), MapRole.BUILDER);
                    maps.deleteGroup(plan.map().groupId());
                } else permissions.removeWorldInheritance(plan.map().groupId(), worldName);
                completePlan(plan);
                return new OperationResult.Success<>(OperationName.RECOVER, id, null);
            }
            if (plan.operation() != OperationName.CREATE && plan.operation() != OperationName.IMPORT)
                return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.INVALID_INPUT);
            if (map != null && map.status() == MapStatus.ACTIVE) {
                completePlan(plan); return new OperationResult.Success<>(OperationName.RECOVER, id, map);
            }
            if (map != null && map.status() != MapStatus.PENDING)
                return new OperationResult.Failure<>(OperationName.RECOVER, id, FailureReason.OPERATION_IN_PROGRESS);
            if (maps.findGroup(plan.map().groupId()) == null) maps.saveGroup(new MapGroup(plan.map().groupId(), Set.of()));
            if (map == null) { map = plan.map(); maps.save(map); }
            var manager = MultiverseCoreApi.get().getWorldManager();
            var world = manager.getLoadedWorld(worldName).getOrNull();
            if (world == null && manager.isWorld(worldName)) {
                var result = manager.loadWorld(LoadWorldOptions.world(manager.getWorld(worldName).get()));
                if (result.isFailure()) return backendFailed(plan.operationId(), worldName, plan.operation(), result);
                world = result.get();
            }
            if (world == null && !manager.isWorld(worldName)) {
                Path folder = worldGateway.worldDirectory(worldName);
                if (plan.imported() != null && !Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
                    folder = Path.of("").toAbsolutePath().normalize().resolve(worldName);
                if (Files.isSymbolicLink(folder)) throw new IllegalArgumentException(Messages.text("maintenance.recovery_invalid"));
                if (Files.exists(folder)) {
                    if (!worldVersions.isCompatible(folder))
                        return failed(plan.operationId(), worldName, FailureReason.WORLD_VERSION_INCOMPATIBLE, plan.operation());
                    ImportWorldOptions options;
                    if (plan.create() != null) {
                        var created = options(plan.create());
                        options = ImportWorldOptions.worldName(worldName).environment(created.environment())
                                .generator(created.generator()).generatorSettings(created.generatorSettings()).biome(created.biome());
                    } else if (plan.imported() != null) options = importOptions(plan.imported());
                    else throw new IllegalArgumentException(Messages.text("maintenance.recovery_invalid"));
                    var result = manager.importWorld(options);
                    if (result.isFailure()) return backendFailed(plan.operationId(), worldName, plan.operation(), result);
                    world = result.get();
                } else if (plan.create() != null) {
                    var result = manager.createWorld(options(plan.create()));
                    if (result.isFailure()) return backendFailed(plan.operationId(), worldName, plan.operation(), result);
                    world = result.get();
                }
            }
            if (world == null) return failed(plan.operationId(), worldName, FailureReason.WORLD_BACKEND_FAILED, plan.operation());
            String heightError = flatSurfaceError(plan.create(), world.getBukkitWorld().get());
            if (heightError != null) return failed(plan.operationId(), worldName, FailureReason.INVALID_INPUT, plan.operation(), heightError);
            initializeWorld(world, map.id(), map.displayName());
            UUID owner = plan.create() != null ? plan.create().ownerId() : plan.imported() != null ? plan.imported().ownerId() : null;
            if (owner != null) roles.add(map.groupId(), MapRole.ADMIN, owner);
            var location = world.getSpawnLocation();
            SpawnPoint spawn = location == null ? null : new SpawnPoint(worldName, location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), location.getPitch());
            var sync = permissions.ensureGroupInheritance(map.groupId(), maps.findGroup(map.groupId()).worldNames());
            if (sync.isDone() && !sync.join()) return failed(plan.operationId(), worldName, FailureReason.PERMISSION_SYNC_FAILED, plan.operation());
            Set<UUID> builders = new java.util.HashSet<>(roles.members(map.groupId(), MapRole.ADMIN));
            builders.addAll(roles.members(map.groupId(), MapRole.BUILDER));
            for (UUID builder : builders) if (!permissions.setBuilderAccess(map.groupId(), builder, true))
                return failed(plan.operationId(), worldName, FailureReason.PERMISSION_SYNC_FAILED, plan.operation());
            MapInstance active = new MapInstance(map.id(), worldName, map.groupId(), map.displayName(), map.settings(), MapStatus.ACTIVE, spawn);
            maps.save(active); completePlan(plan);
            return permissions.pending() ? new OperationResult.Partial<>(OperationName.RECOVER, id, active, FailureReason.PERMISSION_SYNC_PENDING)
                    : new OperationResult.Success<>(OperationName.RECOVER, id, active);
        } catch (StorageException error) { return failed(id, worldName, FailureReason.STORAGE_FAILED, OperationName.RECOVER); }
        catch (RuntimeException error) {
            logger.warning(Messages.text("world.operation_failed", "operation", Messages.text("operations.RECOVER"),
                    "world", worldName, "error", error.getMessage()));
            return failed(id, worldName, FailureReason.WORLD_BACKEND_FAILED, OperationName.RECOVER);
        }
    }

    public void recoverAll() {
        for (RecoveryJournal.Plan plan : journal.all()) {
            if (defaultWorld.test(plan.map().worldName())) continue;
            OperationResult<MapInstance> result = recover(plan.map().worldName());
            if (result instanceof OperationResult.Failure<?> || result instanceof OperationResult.Partial<?> partial
                    && partial.reason() != FailureReason.PERMISSION_SYNC_PENDING)
                logger.warning(Messages.text("maintenance.recovery_needed", "world", plan.map().worldName()));
        }
        for (OperationRecord record : operations.unfinished()) {
            if (defaultWorld.test(record.worldName())) continue;
            if (record.name() == OperationName.INITIALIZE || record.name() == OperationName.LOAD) {
                OperationResult<MapInstance> result = record.name() == OperationName.INITIALIZE
                        ? initialize(record.worldName()) : load(record.worldName());
                FailureReason reason = result instanceof OperationResult.Failure<?> failure ? failure.reason()
                        : result instanceof OperationResult.Partial<?> partial ? partial.reason() : null;
                operations.save(new OperationRecord(record.id(), record.name(), record.worldName(),
                        reason == null ? OperationStage.COMPLETED : OperationStage.FAILED, reason));
            }
            else if (journal.read(record.worldName()) != null) continue;
            else if (record.name() == OperationName.CREATE || record.name() == OperationName.IMPORT || record.name() == OperationName.DELETE) {
                MapInstance map = maps.findByWorldName(record.worldName());
                if ((map == null && record.name() == OperationName.DELETE) || (map != null && map.status() == MapStatus.ACTIVE && record.name() != OperationName.DELETE))
                    operations.save(new OperationRecord(record.id(), record.name(), record.worldName(), OperationStage.COMPLETED, null));
                else logger.warning(Messages.text("maintenance.recovery_needed", "world", record.worldName()));
            }
        }
    }
    private void completePlan(RecoveryJournal.Plan plan) {
        operations.save(new OperationRecord(plan.operationId(), plan.operation(), plan.map().worldName(), OperationStage.COMPLETED, null));
        journal.remove(plan.map().worldName());
    }
    static void verifyFolder(RecoveryJournal.Plan plan, Path folder) {
        String directoryId = RecoveryJournal.directoryIdentity(folder), worldId = RecoveryJournal.identity(folder);
        if (Files.isSymbolicLink(folder) || (plan.directoryIdentity() == null && plan.worldIdentity() == null)
                || (plan.directoryIdentity() != null && !plan.directoryIdentity().equals(directoryId))
                || (plan.worldIdentity() != null && worldId != null && !plan.worldIdentity().equals(worldId)))
            throw new IllegalArgumentException(Messages.text("maintenance.recovery_invalid"));
    }
    private static void deleteRemainder(Path folder) {
        try {
            Files.walkFileTree(folder, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory, java.io.IOException error) throws java.io.IOException {
                    if (error != null) throw error;
                    Files.delete(directory); return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException error) { throw new StorageException(Messages.text("maintenance.recovery_save_failed"), error); }
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
