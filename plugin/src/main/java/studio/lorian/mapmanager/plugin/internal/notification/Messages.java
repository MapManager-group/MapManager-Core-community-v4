package studio.lorian.mapmanager.plugin.internal.notification;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 插件通知和诊断文本；自定义语言缺项时回退到内置中文。 */
public final class Messages {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static YamlConfiguration defaults;
    private static YamlConfiguration selected;

    private Messages() { }

    public static void load(JavaPlugin plugin) throws IOException {
        selected = null;
        try (var stream = plugin.getResource("langs/zh_CN.yml")) {
            if (stream == null) throw new IOException("Missing bundled langs/zh_CN.yml");
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        String language = plugin.getConfig().getString("language", "zh_CN");
        if (language == null || !language.matches("[A-Za-z]{2,8}_[A-Za-z]{2,8}"))
            throw new IllegalArgumentException(text("language.invalid_code"));
        Path directory = plugin.getDataFolder().toPath().resolve("langs");
        Files.createDirectories(directory);
        for (String builtIn : new String[]{"zh_CN", "en_US"}) {
            Path target = directory.resolve(builtIn + ".yml");
            if (!Files.exists(target)) plugin.saveResource("langs/" + builtIn + ".yml", false);
        }
        Path file = directory.resolve(language + ".yml");
        if (!Files.isRegularFile(file)) throw new IOException(text("language.missing_file", "file", file));
        YamlConfiguration custom = new YamlConfiguration();
        try { custom.load(file.toFile()); }
        catch (InvalidConfigurationException ex) { throw new IOException(text("language.invalid_yaml", "file", file), ex); }
        selected = custom;
    }

    public static Component component(String key, Object... arguments) {
        String template = selected == null ? null : selected.getString(key);
        if (template == null && defaults != null) template = defaults.getString(key);
        if (template == null) return Component.text(key);
        if (arguments.length % 2 != 0) throw new IllegalArgumentException(text("language.invalid_arguments"));
        TagResolver.Builder placeholders = TagResolver.builder();
        for (int i = 0; i < arguments.length; i += 2)
            placeholders.resolver(Placeholder.unparsed(String.valueOf(arguments[i]), String.valueOf(arguments[i + 1])));
        return MINI_MESSAGE.deserialize(template, placeholders.build());
    }

    public static String text(String key, Object... arguments) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, arguments));
    }
}
