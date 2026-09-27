package studio.lorian.mapmanager.plugin.internal.notification;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Field;
import java.nio.file.Path;

/** 可直接运行的语言回退、MiniMessage 和安全占位符检查。 */
public final class MessagesCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("plugin/src/main/resources/langs");
        YamlConfiguration chinese = YamlConfiguration.loadConfiguration(root.resolve("zh_CN.yml").toFile());
        YamlConfiguration english = YamlConfiguration.loadConfiguration(root.resolve("en_US.yml").toFile());
        field("defaults").set(null, chinese);
        field("selected").set(null, english);
        String success = plain(Messages.component("result.success", "operation", "Create", "operation_id", "abc"));
        assert success.equals("Create succeeded. Operation ID: abc") : success;
        assert Messages.text("command.usage_wtp").contains("<map>");
        String safe = plain(Messages.component("command.member_added", "player", "<red>Alice", "role", "admin"));
        assert safe.contains("<red>Alice") : safe;
        english.set("command.kicked", null);
        String fallback = plain(Messages.component("command.kicked", "operator", "Admin", "world", "demo"));
        assert fallback.contains("你已被 Admin 移出地图 demo") : fallback;
        System.out.println("Messages check passed");
    }

    private static Field field(String name) throws Exception {
        Field field = Messages.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
