package studio.lorian.mapmanager.plugin.internal.storage;

import studio.lorian.mapmanager.api.model.*;
import java.util.*;

/** 持久化成功后发布不可变快照。事件读取无 JDBC、文件 I/O，也不等待写锁。 */
public final class CachedStorage implements MapRepository, RoleRepository, OperationRepository {
    private final MapRepository maps;
    private final RoleRepository roles;
    private final OperationRepository operations;
    private record Scope(String id, MapRole role) { }
    private record ReadState(StorageSnapshot snapshot, Map<UUID, MapInstance> ids, Map<String, MapInstance> names,
                             Map<String, MapGroup> groups, Map<Scope, Set<UUID>> members) { }
    private volatile ReadState reads;
    private StorageSnapshot state; // 仅写锁内访问；事件只读 reads 的不可变快照。


    public CachedStorage(MapRepository maps, RoleRepository roles, OperationRepository operations) {
        this.maps = maps; this.roles = roles; this.operations = operations;
        state = StorageSnapshot.capture(maps, roles, operations);
        state.validate();
        index();
    }
    private void publish(List<String> groups, List<MapInstance> maps, List<StorageSnapshot.Grant> grants) {
        state = new StorageSnapshot(1, List.copyOf(groups), List.copyOf(maps), List.copyOf(grants), List.of());
        index();
    }
    private void index() {
        Map<UUID, MapInstance> ids = new HashMap<>(); Map<String, MapInstance> names = new HashMap<>();
        Map<String, Set<String>> worlds = new HashMap<>(); state.groups().forEach(group -> worlds.put(group, new HashSet<>()));
        for (MapInstance map : state.maps()) {
            ids.put(map.id(), map); names.put(StorageValidation.key(map.worldName()), map); worlds.get(map.groupId()).add(map.worldName());
        }
        Map<String, MapGroup> groups = new HashMap<>(); worlds.forEach((id, values) -> groups.put(id, new MapGroup(id, values)));
        Map<Scope, Set<UUID>> members = new HashMap<>();
        for (var grant : state.grants()) members.computeIfAbsent(new Scope(grant.scopeId(), grant.role()), ignored -> new HashSet<>()).add(grant.playerId());
        members.replaceAll((scope, players) -> Set.copyOf(players));
        reads = new ReadState(state, Map.copyOf(ids), Map.copyOf(names), Map.copyOf(groups), Map.copyOf(members));
    }
    @Override public MapInstance findById(UUID id) { return id == null ? null : reads.ids().get(id); }
    @Override public MapInstance findByWorldName(String name) { return name == null ? null : reads.names().get(StorageValidation.key(name)); }
    @Override public List<MapInstance> findAll() { return reads.snapshot().maps(); }
    @Override public MapGroup findGroup(String id) { return id == null ? null : reads.groups().get(id); }
    @Override public List<MapGroup> allGroups() { return List.copyOf(reads.groups().values()); }
    @Override public Set<UUID> members(String scope, MapRole role) { return reads.members().getOrDefault(new Scope(scope, role), Set.of()); }
    @Override public synchronized void save(MapInstance map) {
        maps.save(map);
        List<MapInstance> next = new ArrayList<>(state.maps()); next.removeIf(m -> m.id().equals(map.id())); next.add(map);
        publish(state.groups(), next, state.grants());
    }
    @Override public synchronized void delete(UUID id) {
        maps.delete(id);
        publish(state.groups(), state.maps().stream().filter(m -> !m.id().equals(id)).toList(), state.grants().stream()
                .filter(g -> g.role() != MapRole.VISITOR || !g.scopeId().equals(id.toString())).toList());
    }
    @Override public synchronized void saveGroup(MapGroup group) {
        maps.saveGroup(group); List<String> next = new ArrayList<>(state.groups());
        if (!next.contains(group.id())) next.add(group.id()); publish(next, state.maps(), state.grants());
    }
    @Override public synchronized void deleteGroup(String group) {
        maps.deleteGroup(group); publish(state.groups().stream().filter(g -> !g.equals(group)).toList(), state.maps(),
                state.grants().stream().filter(g -> g.role() == MapRole.VISITOR || !g.scopeId().equals(group)).toList());
    }
    @Override public synchronized boolean add(String scope, MapRole role, UUID player) {
        boolean changed = roles.add(scope, role, player); List<StorageSnapshot.Grant> next = new ArrayList<>(state.grants());
        var grant = new StorageSnapshot.Grant(scope, role, player); if (!next.contains(grant)) next.add(grant);
        publish(state.groups(), state.maps(), next); return changed;
    }
    @Override public synchronized boolean remove(String scope, MapRole role, UUID player) {
        boolean changed = roles.remove(scope, role, player);
        publish(state.groups(), state.maps(), state.grants().stream()
                .filter(g -> !g.equals(new StorageSnapshot.Grant(scope, role, player))).toList()); return changed;
    }
    @Override public synchronized void removeScope(String scope, MapRole role) {
        roles.removeScope(scope, role); publish(state.groups(), state.maps(), state.grants().stream()
                .filter(g -> !g.scopeId().equals(scope) || g.role() != role).toList());
    }
    @Override public OperationRecord find(UUID id) { return operations.find(id); }
    @Override public List<OperationRecord> unfinished() { return operations.unfinished(); }
    @Override public List<OperationRecord> allOperations() { return operations.allOperations(); }
    @Override public void save(OperationRecord record) { operations.save(record); }
}
