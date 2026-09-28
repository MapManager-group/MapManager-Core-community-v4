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
