package studio.lorian.mapmanager.plugin.internal.storage;

import org.bukkit.configuration.file.FileConfiguration;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

/** 插件内部的持久化配置。 */
public record StorageConfig(Type type, String host, int port, String database,
                            String username, String password, boolean useSsl) {
    public enum Type { JSON, MYSQL }

    public static StorageConfig load(FileConfiguration config) {
        String rawType = config.getString("storage.type", "json");
        Type type = switch (rawType.toLowerCase(java.util.Locale.ROOT)) {
            case "json" -> Type.JSON;
            case "mysql" -> Type.MYSQL;
            default -> throw new IllegalArgumentException(Messages.text("errors.storage_type"));
        };
        String base = "storage.mysql.";
        String host = config.getString(base + "host", "").trim();
        int port = config.getInt(base + "port", 3306);
        String database = config.getString(base + "database", "").trim();
        String username = config.getString(base + "username", "").trim();
        String password = config.getString(base + "password", "");
        if (type == Type.MYSQL && (host.isBlank() || database.isBlank() || username.isBlank()
                || port < 1 || port > 65535 || !database.matches("[A-Za-z0-9_]+"))) {
            throw new IllegalArgumentException(Messages.text("errors.mysql_config"));
        }
        return new StorageConfig(type, host, port, database, username, password,
                config.getBoolean(base + "use-ssl", true));
    }
}
