package studio.lorian.mapmanager.plugin.internal.notification;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {
    private YamlConfiguration previousDefaults;
    private YamlConfiguration previousSelected;
    private YamlConfiguration english;

    @BeforeEach
    void loadLanguageResources() throws Exception {
        previousDefaults = (YamlConfiguration) field("defaults").get(null);
        previousSelected = (YamlConfiguration) field("selected").get(null);
        YamlConfiguration chinese = load("/langs/zh_CN.yml");
        english = load("/langs/en_US.yml");
        field("defaults").set(null, chinese);
        field("selected").set(null, english);
    }

    @AfterEach
    void restoreMessagesState() throws Exception {
        field("defaults").set(null, previousDefaults);
        field("selected").set(null, previousSelected);
    }

    @Test
    void resolvesEnglishTextAndKeepsPlaceholderValuesUnparsed() {
        assertEquals("Create succeeded. Operation ID: abc",
                Messages.text("result.success", "operation", "Create", "operation_id", "abc"));

        String safe = plain(Messages.component("command.member_added",
                "player", "<red>Alice", "role", "admin"));
        assertTrue(safe.contains("<red>Alice"));
        assertTrue(Messages.text("command.usage_wtp").contains("<map>"));
        assertEquals("The SIFT environment is not supported yet",
                Messages.text("reasons.UNSUPPORTED_ENVIRONMENT"));
    }

    @Test
    void fallsBackToChineseWhenSelectedLanguageKeyIsMissing() {
        english.set("command.kicked", null);

        String message = Messages.text("command.kicked", "operator", "Admin", "world", "demo");

        assertTrue(message.contains("你已被 Admin 移出地图 demo"), message);
    }

    @Test
    void rejectsAnOddNumberOfPlaceholderArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> Messages.component("result.success", "operation"));
    }

    @Test
    void bothLanguagesContainEveryResultCodeAndTheSameLeafKeys() throws Exception {
        var chinese = load("/langs/zh_CN.yml");
        var chineseKeys = chinese.getKeys(true).stream().filter(key -> !chinese.isConfigurationSection(key)).collect(java.util.stream.Collectors.toSet());
        var englishKeys = english.getKeys(true).stream().filter(key -> !english.isConfigurationSection(key)).collect(java.util.stream.Collectors.toSet());
        assertEquals(chineseKeys, englishKeys);
        for (var reason : studio.lorian.mapmanager.api.result.FailureReason.values()) {
            assertTrue(chinese.isString("reasons." + reason), reason.toString());
            assertTrue(english.isString("reasons." + reason), reason.toString());
        }
        for (var operation : studio.lorian.mapmanager.api.result.OperationName.values()) {
            assertTrue(chinese.isString("operations." + operation), operation.toString());
            assertTrue(english.isString("operations." + operation), operation.toString());
        }
    }

    @Test
    void notifierFormatsEveryResultWithOperationIdAndReason() throws Exception {
        java.util.List<Component> sent = new java.util.ArrayList<>();
        var console = studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy(
                org.bukkit.command.ConsoleCommandSender.class, (o, m, a) -> { sent.add((Component) a[0]); return null; });
        try (var server = new studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer((o, m, a) -> {
            assertEquals("getConsoleSender", m.getName()); return console;
        })) {
            var notifier = new OperationNotifierImpl(); var id = java.util.UUID.randomUUID();
            notifier.notify(NotificationTarget.console(), new studio.lorian.mapmanager.api.result.OperationResult.Success<>(
                    studio.lorian.mapmanager.api.result.OperationName.CREATE, id, null));
            notifier.notify(NotificationTarget.console(), new studio.lorian.mapmanager.api.result.OperationResult.Failure<>(
                    studio.lorian.mapmanager.api.result.OperationName.CREATE, id, studio.lorian.mapmanager.api.result.FailureReason.STORAGE_FAILED));
            notifier.notify(NotificationTarget.console(), new studio.lorian.mapmanager.api.result.OperationResult.Partial<>(
                    studio.lorian.mapmanager.api.result.OperationName.CREATE, id, null, studio.lorian.mapmanager.api.result.FailureReason.PERMISSION_SYNC_PENDING));
            assertEquals(3, sent.size());
            for (Component message : sent) { assertTrue(plain(message).contains(id.toString())); assertTrue(plain(message).contains("Create")); }
            assertTrue(plain(sent.get(0)).contains("succeeded"));
            assertTrue(plain(sent.get(1)).contains(Messages.text("reasons.STORAGE_FAILED")));
            assertTrue(plain(sent.get(2)).contains(Messages.text("reasons.PERMISSION_SYNC_PENDING")));
        }
    }

    @Test
    void notifierRoutesOnlinePlayersAndOfflineFallbackToLog() throws Exception {
        java.util.List<Component> sent = new java.util.ArrayList<>(); java.util.List<String> logged = new java.util.ArrayList<>();
        var player = studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy(org.bukkit.entity.Player.class,
                (o, m, a) -> { sent.add((Component) a[0]); return null; });
        var logger = java.util.logging.Logger.getLogger("notifier-test-" + java.util.UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logged.add(record.getMessage()); }
            public void flush() { } public void close() { }
        });
        try (var server = new studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer((o, m, a) -> switch (m.getName()) {
            case "getPlayerExact" -> a[0].equals("Online") ? player : null;
            case "getLogger" -> logger; default -> throw new AssertionError(m);
        })) {
            var notifier = new OperationNotifierImpl();
            notifier.notify(NotificationTarget.player("Online"), "<red>literal");
            notifier.notify(NotificationTarget.player("Offline"), Component.text("fallback"));
            notifier.notify(NotificationTarget.logOnly(), Component.text("log only"));
            assertEquals(java.util.List.of(Component.text("<red>literal")), sent);
            assertEquals(java.util.List.of("Offline: fallback", "log only"), logged);
        }
    }

    private static YamlConfiguration load(String resource) throws Exception {
        try (InputStream stream = MessagesTest.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing test resource " + resource);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = Messages.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
