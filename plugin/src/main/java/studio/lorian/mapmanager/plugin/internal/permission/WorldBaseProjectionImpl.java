package studio.lorian.mapmanager.plugin.internal.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** LuckPerms worldbase 初始化及权限投影实现。 */
public final class WorldBaseProjectionImpl implements WorldBaseProjection {
    private static final String WORLD_BASE = "worldbase";
    private final LuckPerms luckPerms;

    public WorldBaseProjectionImpl(LuckPerms luckPerms) {
        this.luckPerms = Objects.requireNonNull(luckPerms);
    }

    @Override
    public CompletableFuture<Boolean> ensureWorldBase() {
        GroupManager manager = luckPerms.getGroupManager();
        return manager.loadGroup(WORLD_BASE).thenCompose(existing -> {
            if (existing.isPresent()) return CompletableFuture.completedFuture(false);
            return manager.createAndLoadGroup(WORLD_BASE).thenCompose(group -> {
                group.data().add(InheritanceNode.builder("default").build());
                return manager.saveGroup(group).thenApply(ignored -> true);
            });
        });
    }

    @Override
    public boolean ensureGroupInheritance(String groupId, Set<String> worldNames) {
        // TODO: 建立地图权限组对 worldbase 的世界上下文继承。
        return false;
    }

    @Override
    public boolean setBuilderAccess(String groupId, UUID playerId, boolean enabled) {
        // TODO: 同步建筑师或管理员的 worldbase 权限投影。
        return false;
    }

    @Override
    public boolean removeGroup(String groupId) {
        // TODO: 清理地图权限组投影。
        return false;
    }
}
