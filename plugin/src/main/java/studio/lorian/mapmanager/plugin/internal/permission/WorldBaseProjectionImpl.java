package studio.lorian.mapmanager.plugin.internal.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.context.DefaultContextKeys;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** LuckPerms worldbase 初始化及权限投影实现。 */
public final class WorldBaseProjectionImpl implements WorldBaseProjection {
    private static final String WORLD_BASE = "worldbase";
    private final LuckPerms luckPerms;
    private CompletableFuture<Boolean> worldBaseFuture;

    public WorldBaseProjectionImpl(LuckPerms luckPerms) {
        this.luckPerms = Objects.requireNonNull(luckPerms);
    }

    @Override
    public synchronized CompletableFuture<Boolean> ensureWorldBase() {
        if (worldBaseFuture != null) return worldBaseFuture;
        GroupManager manager = luckPerms.getGroupManager();
        worldBaseFuture = manager.loadGroup(WORLD_BASE).thenCompose(existing -> {
            if (existing.isPresent()) return CompletableFuture.completedFuture(false);
            return manager.createAndLoadGroup(WORLD_BASE).thenCompose(group -> {
                group.data().add(InheritanceNode.builder("default").build());
                return manager.saveGroup(group).thenApply(ignored -> true);
            });
        });
        return worldBaseFuture;
    }

    @Override
    public CompletableFuture<Boolean> ensureGroupInheritance(String groupId, Set<String> worldNames) {
        if (groupId == null || groupId.equals("default") || groupId.equals(WORLD_BASE))
            throw new IllegalArgumentException("地图组不能使用 LuckPerms 基础组名称");
        GroupManager manager = luckPerms.getGroupManager();
        return ensureWorldBase().thenCompose(ignored -> manager.loadGroup(groupId)).thenCompose(existing -> existing
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> manager.createAndLoadGroup(groupId)))
                .thenCompose(group -> {
                    group.data().add(InheritanceNode.builder("default").build());
                    for (String worldName : worldNames) {
                        group.data().add(InheritanceNode.builder(WORLD_BASE)
                                .withContext(DefaultContextKeys.WORLD_KEY, worldName).build());
                    }
                    return manager.saveGroup(group).thenApply(ignored -> true);
                });
    }

    @Override
    public boolean setBuilderAccess(String groupId, UUID playerId, boolean enabled) {
        if (groupId == null || groupId.equals("default") || groupId.equals(WORLD_BASE))
            throw new IllegalArgumentException("地图组不能使用 LuckPerms 基础组名称");
        var manager = luckPerms.getUserManager();
        var user = manager.loadUser(playerId).join();
        var node = InheritanceNode.builder(groupId).build();
        if (enabled) user.data().add(node);
        else user.data().remove(node);
        manager.saveUser(user).join();
        return true;
    }

    @Override
    public boolean removeGroup(String groupId) {
        // TODO: 清理地图权限组投影。
        return false;
    }
}
