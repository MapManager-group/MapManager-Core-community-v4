package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.notification.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import net.kyori.adventure.text.Component;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class MapAdminCommandTest {
    @TempDir Path directory;
    private final List<String> calls = new ArrayList<>();
    private final List<Object> requests = new ArrayList<>();
    private String code;
    private String lastKey;
    private final List<String> keys = new ArrayList<>();
    private OperationResult<?> lastResult;
    private final OperationNotifier notifier = new OperationNotifier() {
        public void notify(NotificationTarget target, OperationResult<?> result) { lastResult = result; }
        public void notify(NotificationTarget target, String message) { }
        public void notify(NotificationTarget target, Component message) { }
        public void notifyKey(NotificationTarget target, String key, Object... arguments) {
            lastKey = key; keys.add(key);
            if (key.equals("command.delete_confirm")) code = (String) arguments[3];
        }
    };
    private <T> T sender(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{type}, (proxy, method, args) -> null));
    }
    private MapAdminCommand command(CachedStorage store) {
        var lifecycle = (WorldLifeCycleService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{WorldLifeCycleService.class},
                (proxy, method, args) -> {
                    calls.add(method.getName()); requests.add(args[0]);
                    return new OperationResult.Success<>(OperationName.CREATE, UUID.randomUUID(), null);
                });
        return new MapAdminCommand(null, store, lifecycle, null, notifier, () -> {}, null, Pattern.compile(".*_bak$"));
    }
    private CachedStorage store() throws Exception {
        var storage = new JsonStorage(directory.resolve("maps.json")); return new CachedStorage(storage, storage, storage);
    }
    @Test void helpListsAllMaintenanceCommandsAndSupportsSingleTopic() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"help"});
        for (String topic : List.of("create", "import", "init", "delete", "load", "recover", "operations", "migration", "storage"))
            assertTrue(keys.contains("maintenance.help_" + topic));
        keys.clear();
        command.execute(console, "mapadmin", new String[]{"help", "CREATE"});
        assertEquals(List.of("maintenance.help_header", "maintenance.help_create", "maintenance.help_options"), keys);
        keys.clear();
        command.execute(console, "mapadmin", new String[]{"help", "delete"});
        assertEquals(List.of("maintenance.help_header", "maintenance.help_delete"), keys);
        assertTrue(calls.isEmpty());
    }

    @Test void recoveryCompletionIncludesJournalsWithoutMapRecords() throws Exception {
        assertTrue(MapAdminCommand.recoveryWorldNames(directory.resolve("missing")).isEmpty());
        java.nio.file.Files.writeString(directory.resolve("deleted_bak.json"), "{}");
        java.nio.file.Files.writeString(directory.resolve("arena.json"), "{}");
        java.nio.file.Files.writeString(directory.resolve("ignore.tmp"), "{}");
        java.nio.file.Files.createDirectory(directory.resolve("not-a-request.json"));
        assertEquals(Set.of("deleted_bak", "arena"), Set.copyOf(MapAdminCommand.recoveryWorldNames(directory)));
    }

    @Test void lifecycleRoutesMoveToAdminAndImportStillRejectsParameters() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"create", "arena", "flat", "nether", "flat.height=64"});
        var create = (CreateMapRequest) requests.getFirst();
        assertEquals(Environment.NETHER, create.environment()); assertEquals("64", create.parameters().get("flat.height"));
        command.execute(console, "mapadmin", new String[]{"import", "arena", "normal", "the_end"});
        assertEquals(Environment.THE_END, ((ImportMapRequest) requests.get(1)).environment());
        command.execute(console, "mapadmin", new String[]{"import", "arena", "flat.height=64"});
        assertEquals(2, calls.size());
        command.execute(console, "mapadmin", new String[]{"init", "arena"});
        assertEquals(List.of("create", "importWorld", "initialize"), calls);
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{""}).containsAll(List.of("create", "import", "init", "delete")));
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"import", "arena", "flat.height="}).isEmpty());
        var world = new MapWorldCommand(null, null, null, null, null, null, null, notifier, null);
        for (String verb : List.of("create", "import", "init", "delete")) world.execute(console, "world", new String[]{verb, "arena"});
        assertEquals(3, calls.size()); assertTrue(world.tabComplete(console, "world", new String[]{""}).isEmpty());
    }
    @Test void deletionRequiresSingleUseCodeAndSameMapIdentity() throws Exception {
        var store = store(); store.saveGroup(new MapGroup("team", Set.of()));
        var map = new MapInstance(UUID.randomUUID(), "arena_bak", "team", "Arena", new MapSettings(true, false, false), MapStatus.ACTIVE, null);
        store.save(map); var command = command(store); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"delete", "arena_bak"});
        assertNotNull(code); assertEquals(6, code.length()); assertTrue(calls.isEmpty());
        command.execute(console, "mapadmin", new String[]{"delete", "confirm"}); assertTrue(calls.isEmpty());
        command.execute(console, "mapadmin", new String[]{"delete", code}); assertEquals(List.of("delete"), calls);
        command.execute(console, "mapadmin", new String[]{"delete", code}); assertEquals(1, calls.size());
        command.execute(console, "mapadmin", new String[]{"delete", "arena_bak"});
        store.delete(map.id()); store.save(new MapInstance(UUID.randomUUID(), "arena_bak", "team", "Other", map.settings(), MapStatus.ACTIVE, null));
        command.execute(console, "mapadmin", new String[]{"delete", code}); assertEquals(1, calls.size());
    }
    @Test void playersAndCommandBlocksCannotExecuteOrCompleteMaintenanceCommands() throws Exception {
        var command = command(store());
        for (Class<? extends CommandSender> type : List.of(CommandSender.class, Player.class, BlockCommandSender.class)) {
            var sender = sender(type);
            for (String verb : List.of("create", "import", "init", "delete", "load", "recover", "operations"))
                command.execute(sender, "mapadmin", new String[]{verb, "arena"});
            assertTrue(calls.isEmpty()); assertTrue(command.tabComplete(sender, "mapadmin", new String[]{""}).isEmpty());
        }
    }
    @Test void rconCanExecuteLifecycleCommandsAndCompleteMaintenanceCommands() throws Exception {
        var store = store(); store.saveGroup(new MapGroup("team", Set.of()));
        store.save(new MapInstance(UUID.randomUUID(), "arena_bak", "team", "Arena",
                new MapSettings(true, false, false), MapStatus.ACTIVE, null));
        var command = command(store); var rcon = sender(RemoteConsoleCommandSender.class);
        for (String verb : List.of("create", "import", "init", "load", "recover"))
            assertTrue(command.execute(rcon, "mapadmin", new String[]{verb, "arena"}));
        command.execute(rcon, "mapadmin", new String[]{"delete", "arena_bak"});
        assertNotNull(code);
        command.execute(rcon, "mapadmin", new String[]{"delete", code});
        assertEquals(List.of("create", "importWorld", "initialize", "load", "recover", "delete"), calls);
        assertEquals(command.tabComplete(sender(ConsoleCommandSender.class), "mapadmin", new String[]{""}),
                command.tabComplete(rcon, "mapadmin", new String[]{""}));
        assertEquals(List.of("dry-run"), command.tabComplete(rcon, "mapadmin", new String[]{"migration", "dry"}));
    }
    @Test void malformedAndDuplicateOptionsNeverInvokeLifecycle() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        for (String[] args : List.of(
                new String[]{"create"}, new String[]{"create", "arena", "bad-type"},
                new String[]{"create", "arena", "flat", "bad-environment"},
                new String[]{"create", "arena", "alias="},
                new String[]{"create", "arena", "alias=A", "alias=B"},
                new String[]{"create", "arena", "group=A", "group=B"},
                new String[]{"create", "arena", "admin=A", "admin=B"},
                new String[]{"create", "arena", "seed=1", "seed=2"},
                new String[]{"import", "arena", "seed=1"}, new String[]{"init"},
                new String[]{"init", "arena", "extra"})) {
            command.execute(console, "mapadmin", args);
            assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class, lastResult).reason(), Arrays.toString(args));
        }
        assertTrue(calls.isEmpty());
    }
    @Test void omittedOptionsUseDefaultsAndValuesPreserveEqualsSigns() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"CREATE", "arena", "alias=A=B", "group=team"});
        var request = (CreateMapRequest) requests.getFirst();
        assertEquals(WorldType.FLAT, request.worldType()); assertEquals(Environment.NORMAL, request.environment());
        assertEquals("A=B", request.displayName()); assertEquals("team", request.groupId());
        command.execute(console, "mapadmin", new String[]{"import", "arena"});
        var imported = (ImportMapRequest) requests.get(1);
        assertEquals(WorldType.FLAT, imported.worldType()); assertEquals(Environment.NORMAL, imported.environment());
    }
    @Test void loadAndRecoverRouteAndBadArityShowsUsage() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"load", "arena"});
        command.execute(console, "mapadmin", new String[]{"recover", "arena"});
        assertEquals(List.of("load", "recover"), calls);
        for (String[] args : List.of(new String[]{"load"}, new String[]{"recover", "arena", "extra"},
                new String[]{"storage", "export", "../escape"}, new String[]{"migration", "unknown"})) {
            command.execute(console, "mapadmin", args); assertEquals("maintenance.usage", lastKey);
        }
        assertEquals(2, calls.size());
    }
    @Test void deletionRejectsUnsafeNameAndInactiveMap() throws Exception {
        var store = store(); store.saveGroup(new MapGroup("team", Set.of()));
        store.save(new MapInstance(UUID.randomUUID(), "arena_bak", "team", "Arena", new MapSettings(true, false, false), MapStatus.PENDING, null));
        var command = command(store); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"delete", "arena"});
        assertEquals(FailureReason.ACCESS_DENIED, assertInstanceOf(OperationResult.Failure.class, lastResult).reason());
        command.execute(console, "mapadmin", new String[]{"delete", "missing_bak"});
        assertEquals(FailureReason.MAP_NOT_FOUND, assertInstanceOf(OperationResult.Failure.class, lastResult).reason());
        command.execute(console, "mapadmin", new String[]{"delete", "arena_bak"});
        assertEquals(FailureReason.INVALID_MAP_STATE, assertInstanceOf(OperationResult.Failure.class, lastResult).reason());
        assertNull(code); assertTrue(calls.isEmpty());
    }
    @Test void expiredConfirmationCannotDeleteAndIsConsumed() throws Exception {
        var store = store(); store.saveGroup(new MapGroup("team", Set.of()));
        store.save(new MapInstance(UUID.randomUUID(), "arena_bak", "team", "Arena", new MapSettings(true, false, false), MapStatus.ACTIVE, null));
        var command = command(store); var console = sender(ConsoleCommandSender.class);
        command.execute(console, "mapadmin", new String[]{"delete", "arena_bak"});
        // Move the recorded issue time instead of waiting ten seconds in the test.
        var field = MapAdminCommand.class.getDeclaredField("pendingDeletion"); field.setAccessible(true);
        Object pending = field.get(command);
        var constructor = pending.getClass().getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var id = pending.getClass().getDeclaredMethod("mapId"); id.setAccessible(true);
        field.set(command, constructor.newInstance(id.invoke(pending), "arena_bak", code,
                System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(11)));
        command.execute(console, "mapadmin", new String[]{"delete", code});
        assertEquals("command.delete_expired", lastKey); assertNull(field.get(command)); assertTrue(calls.isEmpty());
    }
    @Test void completionUsesCommittedTypeAndLeavesFileNamesToTheOperator() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "normal", ""}).contains("nether"));
        assertFalse(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "normal", ""}).contains("flat.height="));
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "group=team", ""}).contains("flat.height="));
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "invalid", ""}).isEmpty());
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"storage", "export", ""}).isEmpty());
    }

    @Test void completionSeparatesCreationAndImportAndHidesUsedKeys() throws Exception {
        var command = command(store()); var console = sender(ConsoleCommandSender.class);
        assertEquals(List.of("flat", "flat.height="), command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "FL"}));
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"import", "arena", ""}).contains("alias="));
        assertFalse(command.tabComplete(console, "mapadmin", new String[]{"import", "arena", "flat", "normal", ""}).contains("seed="));
        var options = command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "flat", "normal", "seed=1", ""});
        assertFalse(options.contains("seed=")); assertTrue(options.contains("flat.height="));
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", ""}).contains("alias="));
        assertFalse(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "flat", ""}).contains("sift"));
        assertFalse(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "normal", "normal", ""}).contains("flat.height="));
        assertFalse(command.tabComplete(console, "mapadmin", new String[]{"create", "arena", "flat", "flat.height=64", ""}).contains("generator-settings="));
        assertTrue(command.tabComplete(console, "mapadmin", new String[]{"storage", "export", ""}).isEmpty());
        assertEquals(List.of("structures=false", "structures=true"), command.tabComplete(console, "mapadmin",
                new String[]{"create", "arena", "flat", "normal", "structures="}));
    }
}
