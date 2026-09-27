package studio.lorian.mapmanager.plugin.internal.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapSettings;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

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
            requireName(name, "world");
            if (!normalizedNames.add(name.toLowerCase(Locale.ROOT))) throw invalid("duplicate_world", "world", name);
            JsonObject node = asObject(entry.getValue(), name);
            String group = string(node, "group");
            requireName(group, "group");
            if (!groups.has(group)) throw invalid("missing_group", "world", name, "group", group);
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
            requireName(id, "group");
            JsonObject group = asObject(entry.getValue(), id);
            if (!names(group, "worlds").equals(actualWorlds.getOrDefault(id, Set.of())))
                throw invalid("worlds_mismatch", "group", id);
            for (String player : names(group, "admins")) roles.add(new LegacyRoleGrant(id, MapRole.ADMIN, player, null));
            for (String player : names(group, "builders")) roles.add(new LegacyRoleGrant(id, MapRole.BUILDER, player, null));
        }
        return new LegacySnapshot(maps, groups.keySet(), roles, List.of());
    }

    private static JsonObject object(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root instanceof JsonNull ? new JsonObject() : asObject(root, file.getFileName().toString());
        } catch (IOException ex) { throw new IllegalStateException(Messages.text("migration.read_failed", "file", file), ex); }
    }
    private static JsonObject asObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) throw invalid("not_object", "label", label);
        return value.getAsJsonObject();
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid("invalid_field", "field", key);
        return value.getAsString();
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid("invalid_boolean", "field", key);
        return value.getAsBoolean();
    }
    private static Set<String> names(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) return Set.of();
        if (!value.isJsonArray()) throw invalid("invalid_list", "field", key);
        Set<String> result = new HashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalid("invalid_member", "field", key);
            String name = element.getAsString();
            if (key.equals("worlds")) requireName(name, "world");
            else if (!name.matches("[A-Za-z0-9_]{3,16}")) throw invalid("invalid_player", "player", name);
            if (!result.add(name)) throw invalid("duplicate_entry", "entry", name);
        }
        return result;
    }
    private static void requireName(String name, String label) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,100}"))
            throw invalid(label.equals("world") ? "invalid_world" : "invalid_group", "value", name);
    }
    private static IllegalArgumentException invalid(String key, Object... arguments) {
        return new IllegalArgumentException(Messages.text("migration." + key, arguments));
    }
}
