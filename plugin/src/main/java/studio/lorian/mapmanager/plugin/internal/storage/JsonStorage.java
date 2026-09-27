package studio.lorian.mapmanager.plugin.internal.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.lorian.mapmanager.api.model.MapGroup;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 一个 JSON 文件保存地图、组、成员与操作；每次修改通过临时文件原子替换。 */
public final class JsonStorage implements MapRepository, RoleRepository, OperationRepository {
    private static final int FORMAT = 1;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private State state;

    private record Grant(String scopeId, MapRole role, UUID playerId) { }
    private record Document(int version, List<MapInstance> maps, List<String> groups,
                            List<Grant> grants, List<OperationRecord> operations) { }
    private static final class State {
        final Map<UUID, MapInstance> maps = new LinkedHashMap<>();
        final Set<String> groups = new java.util.LinkedHashSet<>();
        final Set<Grant> grants = new java.util.LinkedHashSet<>();
        final Map<UUID, OperationRecord> operations = new LinkedHashMap<>();
        State copy() {
            State next = new State();
            next.maps.putAll(maps);
            next.groups.addAll(groups);
            next.grants.addAll(grants);
            next.operations.putAll(operations);
            return next;
        }
    }

    public JsonStorage(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Document document = gson.fromJson(reader, Document.class);
                if (document == null || document.version() != FORMAT || document.maps() == null
                        || document.groups() == null || document.grants() == null || document.operations() == null) {
                    throw new IOException(Messages.text("errors.json_format"));
                }
                State loaded = new State();
                loaded.groups.addAll(document.groups());
                for (MapInstance map : document.maps()) {
                    StorageValidation.map(map);
                    if (loaded.maps.putIfAbsent(map.id(), map) != null) throw new IOException(Messages.text("errors.duplicate_map_id"));
                }
                for (Grant grant : document.grants()) {
                    if (grant == null || grant.role() == null || grant.scopeId() == null || grant.playerId() == null)
                        throw new IOException(Messages.text("errors.invalid_role_record"));
                    loaded.grants.add(grant);
                }
                for (OperationRecord operation : document.operations()) {
                    if (operation == null || operation.id() == null) throw new IOException(Messages.text("errors.invalid_operation_record"));
                    loaded.operations.put(operation.id(), operation);
                }
                validate(loaded);
                state = loaded;
            } catch (RuntimeException ex) {
                throw new IOException(Messages.text("errors.json_read_failed"), ex);
            }
        } else {
            state = new State();
            write(state);
        }
    }

    private void validate(State value) {
        Set<String> names = new java.util.HashSet<>();
        for (MapInstance map : value.maps.values()) {
            if (!value.groups.contains(map.groupId()) || !names.add(StorageValidation.key(map.worldName())))
                throw new IllegalArgumentException(Messages.text("errors.group_or_world_conflict", "world", map.worldName()));
        }
        for (Grant grant : value.grants) {
            boolean valid = grant.role() == MapRole.VISITOR
                    ? value.maps.containsKey(UUID.fromString(grant.scopeId()))
                    : value.groups.contains(grant.scopeId());
            if (!valid) throw new IllegalArgumentException(Messages.text("errors.role_scope_missing", "scope", grant.scopeId()));
        }
    }

    private void write(State value) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "maps-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(new Document(FORMAT, new ArrayList<>(value.maps.values()),
                        new ArrayList<>(value.groups), new ArrayList<>(value.grants),
                        new ArrayList<>(value.operations.values())), writer);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private synchronized <T> T change(Function<State, T> mutation) {
        State next = state.copy();
        T result = mutation.apply(next);
        validate(next);
        try { write(next); } catch (IOException ex) { throw new StorageException(Messages.text("errors.json_write_failed"), ex); }
        state = next;
        return result;
    }

    @Override public synchronized MapInstance findById(UUID id) { return state.maps.get(id); }
    @Override public synchronized MapInstance findByWorldName(String name) {
        if (name == null) return null;
        return state.maps.values().stream().filter(map -> StorageValidation.key(map.worldName()).equals(StorageValidation.key(name)))
                .findFirst().orElse(null);
    }
    @Override public synchronized List<MapInstance> findAll() { return List.copyOf(state.maps.values()); }
    @Override public synchronized MapGroup findGroup(String id) {
        if (!state.groups.contains(id)) return null;
        return new MapGroup(id, state.maps.values().stream().filter(map -> map.groupId().equals(id))
                .map(MapInstance::worldName).collect(Collectors.toSet()));
    }
    @Override public void save(MapInstance map) {
        StorageValidation.map(map);
        change(next -> { next.maps.put(map.id(), map); return null; });
    }
    @Override public void delete(UUID id) {
        change(next -> {
            next.maps.remove(id);
            next.grants.removeIf(grant -> grant.role() == MapRole.VISITOR && grant.scopeId().equals(id.toString()));
            return null;
        });
    }
    @Override public void saveGroup(MapGroup group) {
        Objects.requireNonNull(group);
        StorageValidation.groupId(group.id());
        change(next -> { next.groups.add(group.id()); return null; });
    }
    @Override public void deleteGroup(String groupId) {
        change(next -> { next.groups.remove(groupId); return null; });
    }
    @Override public synchronized Set<UUID> members(String scopeId, MapRole role) {
        return state.grants.stream().filter(grant -> grant.scopeId().equals(scopeId) && grant.role() == role)
                .map(Grant::playerId).collect(Collectors.toUnmodifiableSet());
    }
    @Override public boolean add(String scopeId, MapRole role, UUID playerId) {
        return change(next -> next.grants.add(new Grant(scopeId, role, playerId)));
    }
    @Override public boolean remove(String scopeId, MapRole role, UUID playerId) {
        return change(next -> next.grants.remove(new Grant(scopeId, role, playerId)));
    }
    @Override public void removeScope(String scopeId, MapRole role) {
        change(next -> { next.grants.removeIf(grant -> grant.scopeId().equals(scopeId) && grant.role() == role); return null; });
    }
    @Override public synchronized OperationRecord find(UUID id) { return state.operations.get(id); }
    @Override public synchronized List<OperationRecord> unfinished() {
        return state.operations.values().stream().filter(record -> record.stage() != OperationStage.COMPLETED
                && record.stage() != OperationStage.FAILED).toList();
    }
    @Override public void save(OperationRecord record) {
        Objects.requireNonNull(record);
        change(next -> { next.operations.put(record.id(), record); return null; });
    }
}
