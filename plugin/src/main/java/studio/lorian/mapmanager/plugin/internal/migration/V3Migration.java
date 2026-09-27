package studio.lorian.mapmanager.plugin.internal.migration;

import net.luckperms.api.LuckPerms;
import org.bukkit.Location;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 启动时导入 v3 元数据；只读取旧文件与 LP，且只写 MMC 仓储。 */
public final class V3Migration {
    private final Path source;
    private final Path destination;
    private final MapRepository maps;
    private final RoleRepository roles;
    private final PlayerGateway players;
    private final LuckPerms luckPerms;

    public V3Migration(Path source, Path destination, MapRepository maps, RoleRepository roles,
                       PlayerGateway players, LuckPerms luckPerms) {
        this.source = source;
        this.destination = destination;
        this.maps = maps;
        this.roles = roles;
        this.players = players;
        this.luckPerms = luckPerms;
    }

    /** 无源文件或已有完成标记时立即结束；错误会使 future 失败。 */
    public CompletableFuture<String> migrate() {
        Path marker = destination.resolve("v3-migration.done");
        if (Files.exists(marker)) return CompletableFuture.completedFuture("v3 数据此前已迁移");
        boolean worlds = Files.isRegularFile(source.resolve("worlds.json"));
        boolean groups = Files.isRegularFile(source.resolve("groups.json"));
        if (!worlds && !groups) return CompletableFuture.completedFuture("未发现 v3 数据");
        if (!worlds || !groups) return CompletableFuture.failedFuture(
                new IllegalStateException("v3 worlds.json 与 groups.json 必须同时存在"));

        LegacySnapshot snapshot;
        try { snapshot = new V3MigrationSourceImpl(source).read(); }
        catch (RuntimeException ex) { return CompletableFuture.failedFuture(ex); }

        var defaultGroup = luckPerms.getGroupManager().getGroup("default");
        if (defaultGroup == null && !snapshot.maps().isEmpty()) return CompletableFuture.failedFuture(
                new IllegalStateException("无法读取 LP default 组，不能判断 v3 地图公开状态"));
        List<MapInstance> imported = snapshot.maps().stream().map(map -> {
            String node = "multiverse.access." + map.worldName().toLowerCase(Locale.ROOT);
            boolean isPublic = defaultGroup.getNodes().stream().anyMatch(value -> value.getKey().equals(node) && value.getValue());
            var mvWorld = MultiverseCoreApi.get().getWorldManager().getWorld(map.worldName()).getOrNull();
            String alias = mvWorld == null ? null : mvWorld.getColourlessAlias();
            String displayName = alias == null || alias.isBlank() ? map.worldName() : alias;
            Location location = mvWorld == null ? null : mvWorld.getSpawnLocation();
            SpawnPoint spawn = location == null || location.getWorld() == null
                    || !location.getWorld().getName().equals(map.worldName()) ? null
                    : new SpawnPoint(map.worldName(), location.getX(), location.getY(), location.getZ(),
                            location.getYaw(), location.getPitch());
            return new MapInstance(map.id(), map.worldName(), map.groupId(), displayName,
                    new MapSettings(map.settings().physical(), map.settings().explosion(), isPublic), map.status(), spawn);
        }).toList();
        Map<String, CompletableFuture<UUID>> identities = new HashMap<>();
        for (LegacyRoleGrant grant : snapshot.roles())
            identities.computeIfAbsent(grant.playerName().toLowerCase(Locale.ROOT), ignored -> players.resolveId(grant.playerName()));

        return CompletableFuture.allOf(identities.values().toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    for (var entry : identities.entrySet()) if (entry.getValue().join() == null)
                        throw new IllegalStateException("无法解析 v3 玩家 UUID: " + entry.getKey());
                    preflight(imported, snapshot);
                    backup();
                    for (String group : snapshot.groups()) maps.saveGroup(new MapGroup(group, java.util.Set.of()));
                    for (MapInstance map : imported) if (maps.findById(map.id()) == null) maps.save(map);
                    for (LegacyRoleGrant grant : snapshot.roles()) {
                        String scope = grant.role() == MapRole.VISITOR
                                ? imported.stream().filter(map -> map.worldName().equals(grant.worldName()))
                                    .findFirst().orElseThrow().id().toString() : grant.worldName();
                        roles.add(scope, grant.role(), identities.get(grant.playerName().toLowerCase(Locale.ROOT)).join());
                    }
                    try { Files.writeString(marker, "v3 migration completed\n"); }
                    catch (IOException ex) { throw new IllegalStateException("迁移完成标记写入失败", ex); }
                    return "v3 迁移完成: " + imported.size() + " 张地图, " + snapshot.groups().size()
                            + " 个组, " + snapshot.roles().size() + " 条角色记录";
                });
    }

    private void preflight(List<MapInstance> imported, LegacySnapshot snapshot) {
        for (String group : snapshot.groups()) {
            MapGroup existingGroup = maps.findGroup(group);
            if (existingGroup != null && existingGroup.worldNames().stream().anyMatch(world -> imported.stream()
                    .noneMatch(map -> map.worldName().equals(world) && map.groupId().equals(group))))
                throw new IllegalStateException("v4 地图组已包含其他地图: " + group);
        }
        for (MapInstance map : imported) {
            MapInstance existing = maps.findByWorldName(map.worldName());
            if (existing != null && !existing.equals(map))
                throw new IllegalStateException("v4 地图已存在且与 v3 数据冲突: " + map.worldName());
            existing = maps.findById(map.id());
            if (existing != null && !existing.equals(map))
                throw new IllegalStateException("v4 地图 ID 冲突: " + map.worldName());
        }
    }

    private void backup() {
        Path backup = destination.resolve("v3-source-backup");
        try {
            Files.createDirectories(backup);
            for (String name : List.of("worlds.json", "groups.json")) {
                Path original = source.resolve(name);
                Path copy = backup.resolve(name);
                if (!Files.exists(copy)) Files.copy(original, copy);
                else if (Files.mismatch(original, copy) != -1)
                    throw new IllegalStateException("v3 源文件在迁移重试前发生变化: " + name);
            }
        } catch (IOException ex) { throw new IllegalStateException("备份 v3 源文件失败", ex); }
    }
}
