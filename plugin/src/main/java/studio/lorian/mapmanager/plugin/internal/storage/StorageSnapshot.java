package studio.lorian.mapmanager.plugin.internal.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 内部备份/迁移格式；先校验完整快照，再按幂等步骤写入，允许中断后重试。 */
public record StorageSnapshot(int version, List<String> groups, List<MapInstance> maps,
                              List<Grant> grants, List<OperationRecord> operations) {
    public record Grant(String scopeId, MapRole role, UUID playerId) { }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static StorageSnapshot capture(MapRepository maps, RoleRepository roles, OperationRepository operations) {
        List<Grant> grants = new ArrayList<>();
        for (MapGroup group : maps.allGroups()) for (MapRole role : List.of(MapRole.ADMIN, MapRole.BUILDER))
            for (UUID player : roles.members(group.id(), role)) grants.add(new Grant(group.id(), role, player));
        for (MapInstance map : maps.findAll()) for (UUID player : roles.members(map.id().toString(), MapRole.VISITOR))
            grants.add(new Grant(map.id().toString(), MapRole.VISITOR, player));
        return new StorageSnapshot(1, maps.allGroups().stream().map(MapGroup::id).toList(), maps.findAll(),
                List.copyOf(grants), operations.allOperations());
    }

    public void validate() {
        if (version != 1 || groups == null || maps == null || grants == null || operations == null)
            throw new IllegalArgumentException(Messages.text("errors.json_format"));
        Set<String> groupIds = new HashSet<>(), names = new HashSet<>();
        Set<UUID> ids = new HashSet<>(), operationIds = new HashSet<>();
        for (String group : groups) {
            StorageValidation.groupId(group);
            if (!groupIds.add(group)) throw new IllegalArgumentException(Messages.text("errors.json_format"));
        }
        for (MapInstance map : maps) {
            StorageValidation.map(map);
            if (!ids.add(map.id()) || !names.add(StorageValidation.key(map.worldName())) || !groupIds.contains(map.groupId()))
                throw new IllegalArgumentException(Messages.text("errors.group_or_world_conflict", "world", map.worldName()));
        }
        for (Grant grant : grants) {
            if (grant == null || grant.scopeId() == null || grant.playerId() == null || grant.role() == null
                    || (grant.role() == MapRole.VISITOR ? !ids.contains(UUID.fromString(grant.scopeId()))
                    : !groupIds.contains(grant.scopeId())))
                throw new IllegalArgumentException(Messages.text("errors.invalid_role_record"));
        }
        for (OperationRecord operation : operations) {
            if (operation == null || operation.id() == null || operation.name() == null || operation.stage() == null
                    || operation.worldName() == null || !operationIds.add(operation.id()))
                throw new IllegalArgumentException(Messages.text("errors.invalid_operation_record"));
        }
    }

    /** strict 用于后端迁移：目标必须为空或是本快照的精确子集，不覆盖任何已有记录。 */
    public void preflight(MapRepository target, RoleRepository roles, OperationRepository operations, boolean strict) {
        validate();
        for (MapInstance map : maps) {
            MapInstance byId = target.findById(map.id()), byName = target.findByWorldName(map.worldName());
            if ((byId != null && !byId.equals(map)) || (byName != null && !byName.equals(map)))
                throw new IllegalStateException(Messages.text("migration.map_conflict", "world", map.worldName()));
        }
        for (String group : groups) {
            MapGroup existing = target.findGroup(group);
            if (existing != null && existing.worldNames().stream().anyMatch(world -> maps.stream()
                    .noneMatch(map -> map.worldName().equals(world) && map.groupId().equals(group))))
                throw new IllegalStateException(Messages.text("migration.group_conflict", "group", group));
        }
        for (OperationRecord operation : this.operations) {
            OperationRecord old = operations.find(operation.id());
            if (old != null && !old.equals(operation)) throw new IllegalStateException(Messages.text("maintenance.target_conflict"));
        }
        if (strict) {
            StorageSnapshot current = capture(target, roles, operations);
            if (!groups.containsAll(current.groups()) || !maps.containsAll(current.maps())
                    || !grants.containsAll(current.grants()) || !this.operations.containsAll(current.operations()))
                throw new IllegalStateException(Messages.text("maintenance.target_conflict"));
        }
    }

    public void apply(MapRepository target, RoleRepository roles, OperationRepository operations, boolean strict) {
        preflight(target, roles, operations, strict);
        for (String group : groups) if (target.findGroup(group) == null) target.saveGroup(new MapGroup(group, Set.of()));
        for (MapInstance map : maps) if (target.findById(map.id()) == null) target.save(map);
        for (Grant grant : grants) if (!roles.members(grant.scopeId(), grant.role()).contains(grant.playerId()))
            roles.add(grant.scopeId(), grant.role(), grant.playerId());
        for (OperationRecord operation : this.operations) if (operations.find(operation.id()) == null) operations.save(operation);
    }

    public void write(Path file) throws IOException { validate(); writeJson(file, GSON.toJson(this)); }
    public static StorageSnapshot read(Path file) throws IOException {
        StorageSnapshot snapshot = GSON.fromJson(Files.readString(file), StorageSnapshot.class);
        if (snapshot == null) throw new IOException(Messages.text("errors.json_format"));
        snapshot.validate();
        return snapshot;
    }
    public static void writeJson(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "mmc-", ".tmp");
        try { Files.writeString(temp, text); Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
}
