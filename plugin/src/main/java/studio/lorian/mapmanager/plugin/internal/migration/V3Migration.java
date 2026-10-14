package studio.lorian.mapmanager.plugin.internal.migration;

import net.luckperms.api.LuckPerms;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import studio.lorian.mapmanager.plugin.internal.storage.RoleRepository;
import studio.lorian.mapmanager.plugin.internal.storage.OperationRepository;
import studio.lorian.mapmanager.plugin.internal.storage.StorageSnapshot;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 导入 v3 元数据；成功写入完成标记后清理旧配置。 */
public final class V3Migration {
    private final Path source;
    private final Path destination;
    private final MapRepository maps;
    private final RoleRepository roles;
    private final PlayerGateway players;
    private final java.util.function.UnaryOperator<MapInstance> metadata;

    public V3Migration(Path source, Path destination, MapRepository maps, RoleRepository roles,
                       PlayerGateway players, LuckPerms luckPerms) {
        this.source = source;
        this.destination = destination;
        this.maps = maps;
        this.roles = roles;
        this.players = players;
        this.metadata = map -> enrich(map, luckPerms);
    }
    V3Migration(Path source, Path destination, MapRepository maps, RoleRepository roles,
                PlayerGateway players, java.util.function.UnaryOperator<MapInstance> metadata) {
        this.source = source; this.destination = destination; this.maps = maps;
        this.roles = roles; this.players = players; this.metadata = metadata;
    }

    /** 无源文件或已有完成标记时立即结束；错误会使 future 失败。 */
    public CompletableFuture<String> migrate() { return migrate(false); }

    private CompletableFuture<String> running;

    /** 预检只生成报告，不备份源文件、不写入目标仓储或完成标记。 */
    public synchronized CompletableFuture<String> migrate(boolean dryRun) {
        if (running != null && !running.isDone()) return CompletableFuture.failedFuture(
                new IllegalStateException(Messages.text("reasons.OPERATION_IN_PROGRESS")));
        running = prepareAndApply(dryRun);
        return running;
    }

    private CompletableFuture<String> prepareAndApply(boolean dryRun) {
        Path marker = destination.resolve("v3-migration.done");
        if (Files.exists(marker)) return CompletableFuture.completedFuture(Messages.text("migration.done_before"));
        boolean worlds = Files.isRegularFile(source.resolve("worlds.json"));
        boolean groups = Files.isRegularFile(source.resolve("groups.json"));
        if (!worlds && !groups) return CompletableFuture.completedFuture(Messages.text("migration.no_data"));
        if (!worlds || !groups) return CompletableFuture.failedFuture(
                new IllegalStateException(Messages.text("migration.files_missing")));

        Path plan = destination.resolve("v3-migration-plan.json");
        if (Files.exists(plan)) return CompletableFuture.supplyAsync(() -> {
            backup(); // 校验源文件仍与首次执行时相同。
            try { return apply(StorageSnapshot.read(plan), dryRun); }
            catch (IOException error) { throw new IllegalStateException(Messages.text("migration.read_failed", "file", plan), error); }
        });

        LegacySnapshot snapshot;
        try { snapshot = new V3MigrationSourceImpl(source).read(); }
        catch (RuntimeException ex) { return CompletableFuture.failedFuture(ex); }

        List<MapInstance> imported = snapshot.maps().stream().map(metadata).toList();
        Map<String, CompletableFuture<UUID>> identities = new HashMap<>();
        for (LegacyRoleGrant grant : snapshot.roles())
            identities.computeIfAbsent(grant.playerName().toLowerCase(Locale.ROOT), ignored -> players.resolveId(grant.playerName()));

        return CompletableFuture.allOf(identities.values().toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    for (var entry : identities.entrySet()) if (entry.getValue().join() == null)
                        throw new IllegalStateException(Messages.text("migration.uuid_missing", "player", entry.getKey()));
                    List<StorageSnapshot.Grant> grants = snapshot.roles().stream().map(grant -> {
                        String scope = grant.role() == MapRole.VISITOR
                                ? imported.stream().filter(map -> map.worldName().equals(grant.worldName()))
                                    .findFirst().orElseThrow().id().toString() : grant.worldName();
                        return new StorageSnapshot.Grant(scope, grant.role(),
                                identities.get(grant.playerName().toLowerCase(Locale.ROOT)).join());
                    }).toList();
                    StorageSnapshot prepared = new StorageSnapshot(1, List.copyOf(snapshot.groups()), imported, grants, List.of());
                    prepared.validate();
                    report(prepared);
                    prepared.preflight(maps, roles, (OperationRepository) maps, false);
                    if (!dryRun) {
                        backup();
                        try {
                            StorageSnapshot.capture(maps, roles, (OperationRepository) maps)
                                    .write(destination.resolve("v3-target-backup.json"));
                            prepared.write(plan);
                        } catch (IOException error) { throw new IllegalStateException(Messages.text("migration.backup_failed"), error); }
                    }
                    return apply(prepared, dryRun);
                });
    }

    private static MapInstance enrich(MapInstance map, LuckPerms luckPerms) {
        var defaultGroup = luckPerms.getGroupManager().getGroup("default");
        if (defaultGroup == null) throw new IllegalStateException(Messages.text("migration.default_group_missing"));
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
    }

    private String apply(StorageSnapshot prepared, boolean dryRun) {
        report(prepared);
        prepared.preflight(maps, roles, (OperationRepository) maps, false);
        if (dryRun) return Messages.text("maintenance.dry_run", "file", destination.resolve("v3-migration-report.json"));
        prepared.apply(maps, roles, (OperationRepository) maps, false);
        try { StorageSnapshot.writeJson(destination.resolve("v3-migration.done"), "v3 migration completed\n"); }
        catch (IOException error) { throw new IllegalStateException(Messages.text("migration.marker_failed"), error); }
        removeLegacyConfig();
        return Messages.text("migration.done", "maps", prepared.maps().size(), "groups", prepared.groups().size(),
                "roles", prepared.grants().size());
    }

    private void removeLegacyConfig() {
        Path file = destination.resolve("config.yml");
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        try { config.load(file.toFile()); }
        catch (IOException | InvalidConfigurationException error) {
            throw new IllegalStateException(Messages.text("migration.config_cleanup_failed", "file", file), error);
        }
        if (!config.contains("global", true)) return;
        config.set("global", null);
        try { config.save(file.toFile()); }
        catch (IOException error) {
            throw new IllegalStateException(Messages.text("migration.config_cleanup_failed", "file", file), error);
        }
    }

    private void report(StorageSnapshot prepared) {
        var report = new java.util.LinkedHashMap<String, Object>();
        report.put("maps", prepared.maps().stream().map(map -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            MapInstance existing = maps.findByWorldName(map.worldName());
            row.put("world", map.worldName()); row.put("before", existing); row.put("after", map);
            row.put("action", Messages.text(existing == null ? "maintenance.report_add"
                    : existing.equals(map) ? "maintenance.report_equal" : "maintenance.report_conflict"));
            return row;
        }).toList());
        report.put("groups", prepared.groups()); report.put("grants", prepared.grants());
        report.put("before", StorageSnapshot.capture(maps, roles, (OperationRepository) maps));
        try { StorageSnapshot.writeJson(destination.resolve("v3-migration-report.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (IOException error) { throw new IllegalStateException(Messages.text("migration.backup_failed"), error); }
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
                    throw new IllegalStateException(Messages.text("migration.source_changed", "file", name));
            }
        } catch (IOException ex) { throw new IllegalStateException(Messages.text("migration.backup_failed"), ex); }
    }
}
