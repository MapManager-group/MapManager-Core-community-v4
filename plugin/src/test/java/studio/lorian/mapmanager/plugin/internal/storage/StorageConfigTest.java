package studio.lorian.mapmanager.plugin.internal.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageConfigTest {
    @Test
    void defaultsToJsonWithoutMysqlCredentials() {
        StorageConfig config = StorageConfig.load(new YamlConfiguration());

        assertEquals(StorageConfig.Type.JSON, config.type());
        assertEquals(3306, config.port());
    }

    @Test
    void parsesMysqlAndTrimsConnectionIdentifiers() {
        YamlConfiguration source = mysqlConfig();
        source.set("storage.mysql.host", " db.example ");
        source.set("storage.mysql.database", " map_manager ");
        source.set("storage.mysql.username", " mmc ");
        source.set("storage.mysql.password", " secret ");
        source.set("storage.mysql.port", 3307);
        source.set("storage.mysql.use-ssl", false);

        StorageConfig config = StorageConfig.load(source);

        assertEquals(StorageConfig.Type.MYSQL, config.type());
        assertEquals("db.example", config.host());
        assertEquals("map_manager", config.database());
        assertEquals("mmc", config.username());
        assertEquals(" secret ", config.password());
        assertEquals(3307, config.port());
        assertFalse(config.useSsl());
    }

    @Test
    void rejectsUnsupportedBackendType() {
        YamlConfiguration source = new YamlConfiguration();
        source.set("storage.type", "sqlite");

        assertThrows(IllegalArgumentException.class, () -> StorageConfig.load(source));
    }

    @Test
    void rejectsMysqlWithBlankRequiredValues() {
        for (String field : new String[]{"host", "database", "username"}) {
            YamlConfiguration source = mysqlConfig();
            source.set("storage.mysql." + field, " ");

            assertThrows(IllegalArgumentException.class, () -> StorageConfig.load(source), field);
        }
    }

    @Test
    void rejectsMysqlWithOutOfRangePortOrUnsafeDatabaseName() {
        for (int port : new int[]{0, 65536}) {
            YamlConfiguration source = mysqlConfig();
            source.set("storage.mysql.port", port);

            assertThrows(IllegalArgumentException.class, () -> StorageConfig.load(source), "port " + port);
        }

        YamlConfiguration invalidDatabase = mysqlConfig();
        invalidDatabase.set("storage.mysql.database", "map-manager");
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.load(invalidDatabase));

        YamlConfiguration validBoundary = mysqlConfig();
        validBoundary.set("storage.mysql.port", 65535);
        assertEquals(65535, StorageConfig.load(validBoundary).port());
    }

    private static YamlConfiguration mysqlConfig() {
        YamlConfiguration source = new YamlConfiguration();
        source.set("storage.type", "mysql");
        source.set("storage.mysql.host", "localhost");
        source.set("storage.mysql.database", "mapmanager");
        source.set("storage.mysql.username", "mapmanager");
        source.set("storage.mysql.port", 3306);
        return source;
    }
}
