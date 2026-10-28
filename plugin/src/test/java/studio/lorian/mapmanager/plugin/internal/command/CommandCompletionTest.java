package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.plugin.impl.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class CommandCompletionTest {
    @TempDir Path directory;

    @Test void actualAccessRulesHidePrivateMapsAndIncludeUnloadedTargets() throws Exception {
        var backend = new JsonStorage(directory.resolve("maps.json"));
        var store = new CachedStorage(backend, backend, backend);
        store.saveGroup(new MapGroup("team", Set.of()));
        store.saveGroup(new MapGroup("other", Set.of()));
        store.saveGroup(new MapGroup("empty", Set.of()));
        UUID playerId = UUID.randomUUID();
        for (String name : List.of("arena", "unloaded", "public", "private", "pending")) {
            store.save(new MapInstance(UUID.randomUUID(), name, name.equals("arena") || name.equals("unloaded") ? "team" : "other",
                    "Arena", new MapSettings(false, false, name.equals("public")),
                    name.equals("pending") ? MapStatus.PENDING : MapStatus.ACTIVE, null));
        }
        store.add("team", MapRole.ADMIN, playerId);
        var maps = new MapServiceImpl(store);
        var roles = new MapRoleServiceImpl(store, store, null);
        var access = new MapAccessServiceImpl(store, roles);
        var world = proxy(World.class, (o, m, a) -> {
            assertEquals("getName", m.getName()); return "arena";
        });
        var player = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getWorld" -> world;
            case "getUniqueId" -> playerId;
            default -> throw new AssertionError(m);
        });
        try (var server = new BukkitTestServer((o, m, a) -> { throw new AssertionError("Completion must not query loaded worlds: " + m); })) {
            var command = new MapWorldCommand(null, maps, roles, access, null, null, null, null, null);
            var wtp = new MapWorldCommand("wtp", null, maps, roles, access, null, null, null, null, null);
            assertEquals(List.of("arena", "public", "unloaded"), command.tabComplete(player, "world", new String[]{"tp", ""}));
            assertEquals(List.of("unloaded"), wtp.tabComplete(player, "wtp", new String[]{"UN"}));
            assertTrue(command.tabComplete(player, "world", new String[]{""}).contains("help"));
            assertTrue(command.tabComplete(player, "world", new String[]{"public", ""}).containsAll(List.of("on", "off", "enable", "disable", "yes", "no", "status")));
            assertEquals(List.of("Arena"), command.tabComplete(player, "world", new String[]{"setname", ""}));
            store.remove("team", MapRole.ADMIN, playerId);
            assertFalse(command.tabComplete(player, "world", new String[]{""}).contains("setname"));
            assertTrue(command.tabComplete(player, "world", new String[]{"public", ""}).isEmpty());
            assertEquals(List.of("public"), wtp.tabComplete(player, "wtp", new String[]{""}));
        }
        var admin = new MapAdminCommand(null, store, null, null, null, () -> {}, null, java.util.regex.Pattern.compile(".*_bak$"));
        var console = proxy(org.bukkit.command.ConsoleCommandSender.class, (o, m, a) -> null);
        assertEquals(List.of("group=empty"), admin.tabComplete(console, "mapadmin", new String[]{"create", "arena", "group=e"}));
    }
}
