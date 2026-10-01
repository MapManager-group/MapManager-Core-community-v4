package studio.lorian.mapmanager.plugin.internal.permission;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** LuckPerms projection for build permissions inherited from worldbase. */
public interface WorldBaseProjection {
    /** 确保 worldbase 组存在；返回值表示本次是否创建了该组。 */
    CompletableFuture<Boolean> ensureWorldBase();
    CompletableFuture<Boolean> ensureGroupInheritance(String groupId, Set<String> worldNames);
    boolean setBuilderAccess(String groupId, UUID playerId, boolean enabled);
    boolean removeGroup(String groupId);
    boolean removeWorldInheritance(String groupId, String worldName);
    /** 是否已有持久化等待任务；MMC 修改已生效，LP 会在后台完成。 */
    default boolean pending() { return false; }
}
