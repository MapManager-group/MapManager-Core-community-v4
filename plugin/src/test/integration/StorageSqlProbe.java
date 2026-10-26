package mmc.probe;

import org.bukkit.configuration.file.YamlConfiguration;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 仅操作参数中指定的独立测试数据库；不包含生产删除或清库操作。 */
public final class StorageSqlProbe {
    private static int checks;
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); checks++; }
    private static StorageConfig mysql(int port, String database) {
        return new StorageConfig(StorageConfig.Type.MYSQL, "127.0.0.1", port, database, "root", "", false);
    }
    private static YamlConfiguration config(String type, int port, String database, String from, String original) {
        var config = new YamlConfiguration(); config.set("storage.type", type); config.set("storage.migrate-from", from);
        for (String section : List.of("storage.mysql.", "storage.migration-source.mysql.")) {
            config.set(section + "host", "127.0.0.1"); config.set(section + "port", port);
            config.set(section + "database", section.equals("storage.mysql.") ? database : original);
            config.set(section + "username", "root"); config.set(section + "password", ""); config.set(section + "use-ssl", false);
        }
        return config;
    }
    private static void restart(Path directory) throws Exception {
        Path file = directory.resolve("config.yml");
        String original = Files.readString(file);
        var saved = YamlConfiguration.loadConfiguration(file.toFile());
        check("".equals(saved.getString("storage.migrate-from")), "restart loads cleared source from disk");
        var store = StorageBackendMigration.openAndMigrate(saved, directory);
        var expected = StorageSnapshot.read(directory.resolve("restart-expected.json"));
        check(StorageBackendMigration.same(expected, StorageSnapshot.capture(store, store, store)), "restart preserves MySQL snapshot despite changed JSON source");
        check(original.equals(Files.readString(file)), "restart does not rewrite configuration");
        check(!Files.exists(directory.resolve("storage-transfer.json"))
                && !Files.exists(directory.resolve("storage-transfer-completed.txt")), "restart creates no active migration plan");
        try (var files = Files.list(directory)) {
            check(files.filter(path -> path.getFileName().toString().startsWith("storage-transfer-")).count() == 1,
                    "restart does not create another migration backup");
        }
        System.out.println("MYSQL_RESTART_PASS checks=" + checks);
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--restart")) { restart(Path.of(args[1])); return; }
        int port = Integer.parseInt(args[0]); String prefix = args[1]; Path directory = Path.of(args[2]);
        if (!prefix.matches("mmctodo_[a-zA-Z0-9_]+")) throw new IllegalArgumentException("Use a unique mmctodo_ test database prefix");
        String first = prefix + "_a", second = prefix + "_b", conflict = prefix + "_c";
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false", "root", "");
             Statement statement = connection.createStatement()) {
            for (String name : List.of(first, second, conflict)) statement.execute("CREATE DATABASE " + name);
        }
        CachedStorage json = StorageBackendMigration.openAndMigrate(config("json", port, first, "", first), directory);
        json.saveGroup(new MapGroup("team", Set.of())); json.saveGroup(new MapGroup("empty", Set.of()));
        MapInstance map = new MapInstance(UUID.randomUUID(), "arena", "team", "Arena", new MapSettings(false, true, true), MapStatus.ACTIVE,
                new SpawnPoint("arena", 2, 70, -3, 90, 12));
        json.save(map); UUID builder = UUID.randomUUID(), visitor = UUID.randomUUID();
        json.add("team", MapRole.ADMIN, builder); json.add(map.id().toString(), MapRole.VISITOR, visitor);
        json.save(new OperationRecord(UUID.randomUUID(), OperationName.CREATE, "arena", OperationStage.COMPLETED, null));
        json.save(new OperationRecord(UUID.randomUUID(), OperationName.LOAD, "arena", OperationStage.FAILED, FailureReason.WORLD_BACKEND_FAILED));
        StorageSnapshot expected = StorageSnapshot.capture(json, json, json);
        var migrationConfig = config("mysql", port, first, "json", first);
        migrationConfig.set("language", "en_US");
        migrationConfig.save(directory.resolve("config.yml").toFile());
        CachedStorage sql = StorageBackendMigration.openAndMigrate(migrationConfig, directory);
        check("".equals(migrationConfig.getString("storage.migrate-from")), "successful transfer clears in-memory source");
        var savedConfig = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        check("".equals(savedConfig.getString("storage.migrate-from")), "successful transfer persists cleared source");
        check("mysql".equals(savedConfig.getString("storage.type")) && "en_US".equals(savedConfig.getString("language")),
                "configuration retains backend and unrelated settings");
        expected.write(directory.resolve("restart-expected.json"));
        Path jsonFile = directory.resolve("maps.json");
        String originalJson = Files.readString(jsonFile);
        json.saveGroup(new MapGroup("sourceonly", Set.of()));
        try {
            for (int i = 0; i < 2; i++) {
                var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), StorageSqlProbe.class.getName(), "--restart", directory.toString())
                        .inheritIO().start();
                check(child.waitFor() == 0, "fresh JVM restart " + (i + 1));
            }
        } finally {
            Files.writeString(jsonFile, originalJson);
        }
        check(StorageBackendMigration.same(expected, StorageSnapshot.capture(sql, sql, sql)), "JSON -> MySQL full snapshot");
        check(sql.findByWorldName("ARENA").equals(map), "case insensitive query");
        check(sql.findGroup("empty") != null, "empty group retained");
        check(sql.members(map.id().toString(), MapRole.VISITOR).equals(Set.of(visitor)), "map visitors retained");
        sql = StorageBackendMigration.openAndMigrate(config("mysql", port, first, "", first), directory);
        check(StorageBackendMigration.same(expected, StorageSnapshot.capture(sql, sql, sql)), "MySQL reopen");

        var partial = new MySqlStorage(mysql(port, second)); partial.saveGroup(new MapGroup("team", Set.of())); partial.save(map);
        partial.add("team", MapRole.ADMIN, builder); // 注入只写一部分的中断。
        var resumed = StorageBackendMigration.openAndMigrate(config("mysql", port, second, "mysql", first), directory);
        check(StorageBackendMigration.same(expected, StorageSnapshot.capture(resumed, resumed, resumed)), "MySQL -> MySQL target and interrupted writes");
        check(Files.readString(directory.resolve("storage-backend.txt")).trim().equals(StorageBackendMigration.identity(mysql(port, second))), "backend marker only changes after verification");

        var bad = new MySqlStorage(mysql(port, conflict)); bad.saveGroup(new MapGroup("team", Set.of()));
        bad.save(new MapInstance(UUID.randomUUID(), "arena", "team", "Conflict", map.settings(), MapStatus.ACTIVE, null));
        StorageSnapshot before = StorageSnapshot.capture(bad, bad, bad);
        try { StorageBackendMigration.openAndMigrate(config("mysql", port, conflict, "mysql", second), directory); throw new AssertionError("Conflict accepted"); }
        catch (IllegalStateException expectedFailure) { checks++; }
        check(StorageBackendMigration.same(before, StorageSnapshot.capture(bad, bad, bad)), "conflicting target unchanged");
        check(Files.readString(directory.resolve("storage-backend.txt")).trim().equals(StorageBackendMigration.identity(mysql(port, second))), "failed transfer retains marker");

        var changedSource = new MySqlStorage(mysql(port, second));
        changedSource.save(new MapInstance(map.id(), map.worldName(), map.groupId(), "Changed", map.settings(), map.status(), map.spawn()));
        try { StorageBackendMigration.openAndMigrate(config("mysql", port, conflict, "mysql", second), directory); throw new AssertionError("Changed source accepted"); }
        catch (IllegalStateException expectedFailure) { checks++; }
        changedSource.save(map);
        CachedStorage returned = StorageBackendMigration.openAndMigrate(config("json", port, first, "mysql", second), directory);
        check(StorageBackendMigration.same(expected, StorageSnapshot.capture(returned, returned, returned)), "MySQL -> JSON complete snapshot");
        check(Files.readString(directory.resolve("storage-backend.txt")).trim().equals("JSON"), "JSON marker restored");

        check(!changedSource.add("team", MapRole.ADMIN, builder), "duplicate role is idempotent");
        try { changedSource.deleteGroup("team"); throw new AssertionError("Referenced group deleted"); }
        catch (StorageException expectedFailure) { checks++; }
        changedSource.delete(map.id()); check(changedSource.members(map.id().toString(), MapRole.VISITOR).isEmpty(), "foreign key removes visitors");
        changedSource.removeScope("team", MapRole.ADMIN); changedSource.deleteGroup("team"); check(changedSource.findGroup("team") == null, "empty group cleanup");
        Files.writeString(directory.resolve("mysql-report.json"), "{\"passed\":true,\"checks\":" + checks + ",\"databases\":[\"" + first + "\",\"" + second + "\",\"" + conflict + "\"]}");
        System.out.println("MYSQL_PROBE_PASS checks=" + checks + " report=" + directory.resolve("mysql-report.json"));
    }
}
