package studio.lorian.mapmanager.plugin.internal.permission;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** LuckPerms projection for build permissions inherited from worldbase. */
public interface WorldBaseProjection {
    /** 确保 worldbase 组存在；返回值表示本次是否创建了该组。 */
    CompletableFuture<Boolean> ensureWorldBase();
    boolean ensureGroupInheritance(String groupId, Set<String> worldNames);
    boolean setBuilderAccess(String groupId, UUID playerId, boolean enabled);
    boolean removeGroup(String groupId);
}
