package studio.lorian.mapmanager.plugin.internal.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读解析 v3 的两个 JSON 文件；任何不一致都会阻止导入。 */
public final class V3MigrationSourceImpl implements V3MigrationSource {
    private final Path directory;

    public V3MigrationSourceImpl(Path directory) { this.directory = directory; }

    @Override public LegacySnapshot read() {
        JsonObject worlds = object(directory.resolve("worlds.json"));
        JsonObject groups = object(directory.resolve("groups.json"));
        List<MapInstance> maps = new ArrayList<>();
        List<LegacyRoleGrant> roles = new ArrayList<>();
        Map<String, Set<String>> actualWorlds = new HashMap<>();
        Set<String> normalizedNames = new HashSet<>();
        for (var entry : worlds.entrySet()) {
            String name = entry.getKey();
            requireName(name, "世界名");
            if (!normalizedNames.add(name.toLowerCase(Locale.ROOT))) throw invalid("重复世界名: " + name);
            JsonObject node = asObject(entry.getValue(), "世界 " + name);
            String group = string(node, "group");
            requireName(group, "地图组 ID");
            if (!groups.has(group)) throw invalid("世界 " + name + " 引用缺失地图组 " + group);
            actualWorlds.computeIfAbsent(group, ignored -> new HashSet<>()).add(name);
            UUID id = UUID.nameUUIDFromBytes(("mapmanager-v3:" + name.toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8));
            maps.add(new MapInstance(id, name, group, name,
                    new MapSettings(bool(node, "physical", true), bool(node, "exploded", false), false),
                    MapStatus.ACTIVE, null));
            for (String player : names(node, "visitors")) roles.add(new LegacyRoleGrant(name, MapRole.VISITOR, player, null));
        }
        for (var entry : groups.entrySet()) {
            String id = entry.getKey();
            requireName(id, "地图组 ID");
            JsonObject group = asObject(entry.getValue(), "地图组 " + id);
            if (!names(group, "worlds").equals(actualWorlds.getOrDefault(id, Set.of())))
                throw invalid("地图组世界列表与 worlds.json 不一致: " + id);
            for (String player : names(group, "admins")) roles.add(new LegacyRoleGrant(id, MapRole.ADMIN, player, null));
            for (String player : names(group, "builders")) roles.add(new LegacyRoleGrant(id, MapRole.BUILDER, player, null));
        }
        return new LegacySnapshot(maps, groups.keySet(), roles, List.of());
    }

    private static JsonObject object(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root instanceof JsonNull ? new JsonObject() : asObject(root, file.getFileName().toString());
        } catch (IOException ex) { throw new IllegalStateException("读取 v3 数据失败: " + file, ex); }
    }
    private static JsonObject asObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) throw invalid(label + " 不是 JSON 对象");
        return value.getAsJsonObject();
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid("缺失或无效字段: " + key);
        return value.getAsString();
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid("无效布尔字段: " + key);
        return value.getAsBoolean();
    }
    private static Set<String> names(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) return Set.of();
        if (!value.isJsonArray()) throw invalid("无效成员列表: " + key);
        Set<String> result = new HashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalid("无效成员: " + key);
            String name = element.getAsString();
            if (key.equals("worlds")) requireName(name, "世界名");
            else if (!name.matches("[A-Za-z0-9_]{3,16}")) throw invalid("无效玩家名: " + name);
            if (!result.add(name)) throw invalid("重复条目: " + name);
        }
        return result;
    }
    private static void requireName(String name, String label) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("无效" + label + ": " + name);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("v3 数据无效: " + message); }
}
