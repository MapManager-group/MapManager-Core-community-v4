package studio.lorian.mapmanager.plugin.internal.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.types.InheritanceNode;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class DefaultWorldProjectionTest {
    private InheritanceNode node(String group, String world) {
        var contexts = proxy(ImmutableContextSet.class, (o, m, a) -> {
            assertEquals("getValues", m.getName());
            return world == null ? Set.of() : Set.of(world);
        });
        return proxy(InheritanceNode.class, (o, m, a) -> switch (m.getName()) {
            case "getGroupName" -> group;
            case "getContexts" -> contexts;
            default -> throw new AssertionError(m);
        });
    }

    @Test void removesOldDefaultWorldGrantAndDoesNotRecreateItForSharedGroup() throws Exception {
        List<Node> nodes = new ArrayList<>(List.of(node("worldbase", "lobby"), node("worldbase", "arena")));
        var data = proxy(NodeMap.class, (o, m, a) -> {
            if (m.getName().equals("add")) nodes.add((Node) a[0]);
            else if (m.getName().equals("remove")) nodes.remove(a[0]);
            else throw new AssertionError(m);
            return DataMutateResult.SUCCESS;
        });
        var group = proxy(Group.class, (o, m, a) -> switch (m.getName()) {
            case "data" -> data;
            case "getNodes" -> List.copyOf(nodes);
            default -> throw new AssertionError(m);
        });
        var manager = proxy(GroupManager.class, (o, m, a) -> switch (m.getName()) {
            case "loadGroup" -> CompletableFuture.completedFuture(Optional.of(group));
            case "saveGroup" -> CompletableFuture.completedFuture(null);
            default -> throw new AssertionError(m);
        });
        var registry = proxy(NodeBuilderRegistry.class, (o, m, a) -> {
            String[] values = new String[2];
            return proxy(InheritanceNode.Builder.class, (builder, method, arguments) -> switch (method.getName()) {
                case "group" -> { values[0] = (String) arguments[0]; yield builder; }
                case "withContext" -> { values[1] = (String) arguments[1]; yield builder; }
                case "build" -> node(values[0], values[1]);
                default -> throw new AssertionError(method);
            });
        });
        var lp = proxy(LuckPerms.class, (o, m, a) -> switch (m.getName()) {
            case "getGroupManager" -> manager;
            case "getNodeBuilderRegistry" -> registry;
            default -> throw new AssertionError(m);
        });
        var provider = LuckPermsProvider.class.getDeclaredField("instance");
        provider.setAccessible(true);
        Object previous = provider.get(null);
        try {
            provider.set(null, lp);
            assertTrue(new WorldBaseProjectionImpl(lp, "lobby"::equals)
                    .ensureGroupInheritance("team", Set.of("lobby", "arena")).join());
            assertFalse(nodes.stream().anyMatch(n -> n.getContexts().getValues("world").contains("lobby")));
            assertTrue(nodes.stream().anyMatch(n -> n.getContexts().getValues("world").contains("arena")));
        } finally { provider.set(null, previous); }
    }
}
