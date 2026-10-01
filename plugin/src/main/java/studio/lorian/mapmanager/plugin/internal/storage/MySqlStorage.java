package studio.lorian.mapmanager.plugin.internal.storage;

import com.google.gson.Gson;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** MySQL 持久化；地图组关系使用外键，设置和出生点使用 JSON 字段。 */
public final class MySqlStorage implements MapRepository, RoleRepository, OperationRepository {
    private final StorageConfig config;
    private final Gson gson = new Gson();
    private final String url;

    public MySqlStorage(StorageConfig config) {
        this.config = config;
        this.url = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?useSSL=" + config.useSsl() + "&allowPublicKeyRetrieval=false&characterEncoding=utf8";
        try { Class.forName("com.mysql.cj.jdbc.Driver"); }
        catch (ClassNotFoundException ex) { throw new StorageException(Messages.text("errors.mysql_driver_missing"), ex); }
        inConnection(connection -> {
            String[] ddl = {
                    "CREATE TABLE IF NOT EXISTS mm_groups (id VARCHAR(100) COLLATE utf8mb4_bin PRIMARY KEY) ENGINE=InnoDB",
                    "CREATE TABLE IF NOT EXISTS mm_maps (id CHAR(36) PRIMARY KEY, world_name VARCHAR(100) NOT NULL, world_name_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL UNIQUE, group_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, display_name VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL, settings_json JSON NOT NULL, spawn_json JSON NULL, INDEX idx_maps_group_status (group_id, status), INDEX idx_maps_status (status), CONSTRAINT fk_maps_group FOREIGN KEY (group_id) REFERENCES mm_groups(id) ON DELETE RESTRICT) ENGINE=InnoDB",
                    "CREATE TABLE IF NOT EXISTS mm_group_roles (group_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, role VARCHAR(16) NOT NULL, player_id CHAR(36) NOT NULL, PRIMARY KEY (group_id, role, player_id), INDEX idx_group_roles_player (player_id), CONSTRAINT fk_roles_group FOREIGN KEY (group_id) REFERENCES mm_groups(id) ON DELETE CASCADE) ENGINE=InnoDB",
                    "CREATE TABLE IF NOT EXISTS mm_map_visitors (map_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL, PRIMARY KEY (map_id, player_id), INDEX idx_visitors_player (player_id), CONSTRAINT fk_visitors_map FOREIGN KEY (map_id) REFERENCES mm_maps(id) ON DELETE CASCADE) ENGINE=InnoDB",
                    "CREATE TABLE IF NOT EXISTS mm_operations (id CHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, world_name VARCHAR(100) NOT NULL, stage VARCHAR(32) NOT NULL, reason VARCHAR(32) NULL, INDEX idx_operations_stage (stage)) ENGINE=InnoDB"
            };
            try (Statement statement = connection.createStatement()) {
                for (String sql : ddl) statement.execute(sql);
            }
            return null;
        });
    }

    @FunctionalInterface private interface SqlTask<T> { T run(Connection connection) throws SQLException; }
    private <T> T inConnection(SqlTask<T> task) {
        try (Connection connection = DriverManager.getConnection(url, config.username(), config.password())) {
            return task.run(connection);
        } catch (SQLException ex) { throw new StorageException(Messages.text("errors.mysql_failed"), ex); }
    }
    private MapInstance readMap(ResultSet rows) throws SQLException {
        try {
            MapInstance map = new MapInstance(UUID.fromString(rows.getString("id")), rows.getString("world_name"),
                    rows.getString("group_id"), rows.getString("display_name"),
                    gson.fromJson(rows.getString("settings_json"), MapSettings.class),
                    MapStatus.valueOf(rows.getString("status")),
                    gson.fromJson(rows.getString("spawn_json"), SpawnPoint.class));
            StorageValidation.map(map);
            return map;
        } catch (RuntimeException ex) { throw new StorageException(Messages.text("errors.mysql_map_corrupt"), ex); }
    }
    private MapInstance one(String sql, String argument) {
        return inConnection(connection -> {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, argument);
                try (ResultSet rows = query.executeQuery()) { return rows.next() ? readMap(rows) : null; }
            }
        });
    }
    @Override public MapInstance findById(UUID id) { return one("SELECT * FROM mm_maps WHERE id=?", id.toString()); }
    @Override public MapInstance findByWorldName(String name) {
        return name == null ? null : one("SELECT * FROM mm_maps WHERE world_name_key=?", StorageValidation.key(name));
    }
    @Override public List<MapInstance> findAll() {
        return inConnection(connection -> {
            List<MapInstance> maps = new ArrayList<>();
            try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM mm_maps")) {
                while (rows.next()) maps.add(readMap(rows));
            }
            return List.copyOf(maps);
        });
    }
    @Override public MapGroup findGroup(String id) {
        return inConnection(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT id FROM mm_groups WHERE id=?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) { if (!rows.next()) return null; }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT world_name FROM mm_maps WHERE group_id=?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    Set<String> names = new java.util.HashSet<>();
                    while (rows.next()) names.add(rows.getString(1));
                    return new MapGroup(id, names);
                }
            }
        });
    }
    @Override public List<MapGroup> allGroups() {
        return inConnection(connection -> {
            List<MapGroup> groups = new ArrayList<>();
            try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT id FROM mm_groups")) {
                while (rows.next()) groups.add(findGroup(rows.getString(1)));
            }
            return List.copyOf(groups);
        });
    }
    @Override public void save(MapInstance map) {
        StorageValidation.map(map);
        inConnection(connection -> {
            boolean exists;
            try (PreparedStatement check = connection.prepareStatement("SELECT id FROM mm_maps WHERE id=?")) {
                check.setString(1, map.id().toString());
                try (ResultSet rows = check.executeQuery()) { exists = rows.next(); }
            }
            String sql = exists
                    ? "UPDATE mm_maps SET world_name=?,world_name_key=?,group_id=?,display_name=?,status=?,settings_json=?,spawn_json=? WHERE id=?"
                    : "INSERT INTO mm_maps (world_name,world_name_key,group_id,display_name,status,settings_json,spawn_json,id) VALUES (?,?,?,?,?,?,?,?)";
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, map.worldName());
                query.setString(2, StorageValidation.key(map.worldName()));
                query.setString(3, map.groupId());
                query.setString(4, map.displayName());
                query.setString(5, map.status().name());
                query.setString(6, gson.toJson(map.settings()));
                query.setString(7, map.spawn() == null ? null : gson.toJson(map.spawn()));
                query.setString(8, map.id().toString());
                query.executeUpdate();
            }
            return null;
        });
    }
    @Override public void delete(UUID id) { update("DELETE FROM mm_maps WHERE id=?", id.toString()); }
    @Override public void deleteGroup(String groupId) { update("DELETE FROM mm_groups WHERE id=?", groupId); }
    @Override public void saveGroup(MapGroup group) {
        StorageValidation.groupId(group.id());
        inConnection(connection -> {
            try (PreparedStatement query = connection.prepareStatement("INSERT INTO mm_groups (id) VALUES (?) ON DUPLICATE KEY UPDATE id=id")) {
                query.setString(1, group.id());
                query.executeUpdate();
            }
            return null;
        });
    }
    private int update(String sql, String... args) {
        return inConnection(connection -> {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) query.setString(i + 1, args[i]);
                return query.executeUpdate();
            }
        });
    }
    private String roleTable(MapRole role) { return role == MapRole.VISITOR ? "mm_map_visitors" : "mm_group_roles"; }
    private String scopeColumn(MapRole role) { return role == MapRole.VISITOR ? "map_id" : "group_id"; }
    @Override public Set<UUID> members(String scopeId, MapRole role) {
        return inConnection(connection -> {
            String sql = "SELECT player_id FROM " + roleTable(role) + " WHERE " + scopeColumn(role) + "=?"
                    + (role == MapRole.VISITOR ? "" : " AND role=?");
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, scopeId);
                if (role != MapRole.VISITOR) query.setString(2, role.name());
                try (ResultSet rows = query.executeQuery()) {
                    Set<UUID> result = new java.util.HashSet<>();
                    while (rows.next()) result.add(UUID.fromString(rows.getString(1)));
                    return Set.copyOf(result);
                }
            }
        });
    }
    @Override public boolean add(String scopeId, MapRole role, UUID playerId) {
        String sql = role == MapRole.VISITOR
                ? "INSERT INTO mm_map_visitors (map_id,player_id) VALUES (?,?)"
                : "INSERT INTO mm_group_roles (group_id,player_id,role) VALUES (?,?,?)";
        return inConnection(connection -> {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, scopeId);
                query.setString(2, playerId.toString());
                if (role != MapRole.VISITOR) query.setString(3, role.name());
                try { return query.executeUpdate() > 0; }
                catch (SQLException ex) { if (ex.getErrorCode() == 1062) return false; throw ex; }
            }
        });
    }
    @Override public boolean remove(String scopeId, MapRole role, UUID playerId) {
        String sql = "DELETE FROM " + roleTable(role) + " WHERE " + scopeColumn(role) + "=? AND player_id=?"
                + (role == MapRole.VISITOR ? "" : " AND role=?");
        return update(sql, role == MapRole.VISITOR ? new String[]{scopeId, playerId.toString()}
                : new String[]{scopeId, playerId.toString(), role.name()}) > 0;
    }
    @Override public void removeScope(String scopeId, MapRole role) {
        String sql = "DELETE FROM " + roleTable(role) + " WHERE " + scopeColumn(role) + "=?"
                + (role == MapRole.VISITOR ? "" : " AND role=?");
        update(sql, role == MapRole.VISITOR ? new String[]{scopeId} : new String[]{scopeId, role.name()});
    }
    private OperationRecord readOperation(ResultSet rows) throws SQLException {
        String reason = rows.getString("reason");
        return new OperationRecord(UUID.fromString(rows.getString("id")), OperationName.valueOf(rows.getString("name")),
                rows.getString("world_name"), OperationStage.valueOf(rows.getString("stage")),
                reason == null ? null : FailureReason.valueOf(reason));
    }
    @Override public OperationRecord find(UUID id) {
        return inConnection(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM mm_operations WHERE id=?")) {
                query.setString(1, id.toString());
                try (ResultSet rows = query.executeQuery()) { return rows.next() ? readOperation(rows) : null; }
            }
        });
    }
    @Override public List<OperationRecord> unfinished() {
        return inConnection(connection -> {
            List<OperationRecord> result = new ArrayList<>();
            try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM mm_operations WHERE stage NOT IN ('COMPLETED','FAILED')")) {
                while (rows.next()) result.add(readOperation(rows));
            }
            return List.copyOf(result);
        });
    }
    @Override public List<OperationRecord> allOperations() {
        return inConnection(connection -> {
            List<OperationRecord> result = new ArrayList<>();
            try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM mm_operations")) {
                while (rows.next()) result.add(readOperation(rows));
            }
            return List.copyOf(result);
        });
    }
    @Override public void save(OperationRecord record) {
        update("INSERT INTO mm_operations (id,name,world_name,stage,reason) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),world_name=VALUES(world_name),stage=VALUES(stage),reason=VALUES(reason)",
                record.id().toString(), record.name().name(), record.worldName(), record.stage().name(),
                record.reason() == null ? null : record.reason().name());
    }
}
