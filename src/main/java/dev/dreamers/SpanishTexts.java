package dev.dreamers;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import net.kyori.adventure.translation.Translator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpanishTexts {
    private static final Pattern ENTRY = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern ARGUMENT = Pattern.compile("%(?:(\\d+)\\$)?s");
    private static final Locale LOCALE = Translator.parseLocale("es_mx");
    private static final Key NAME = Key.key("dreamers", "es_mx");

    private static TranslationStore.StringBased<MessageFormat> store;

    private SpanishTexts() {}

    static void enable(JavaPlugin plugin) {
        disable();
        try (InputStream input = plugin.getResource("lang/es_mx.json")) {
            if (input == null) {
                plugin.getLogger().warning("No está el archivo de español de México. Discord seguirá en inglés.");
                return;
            }
            Map<String, MessageFormat> formats = new HashMap<>();
            Matcher matcher = ENTRY.matcher(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            while (matcher.find()) {
                formats.put(unescape(matcher.group(1)), messageFormat(unescape(matcher.group(2))));
            }
            store = TranslationStore.messageFormat(NAME);
            store.registerAll(LOCALE, formats);
            GlobalTranslator.translator().addSource(store);
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo leer el español de México: " + exception.getMessage());
        }
    }

    static void disable() {
        if (store != null) {
            GlobalTranslator.translator().removeSource(store);
            store = null;
        }
    }

    static String plain(Component component) {
        if (component == null || store == null) {
            return "";
        }
        Component rendered = GlobalTranslator.render(component, LOCALE);
        if (missing(rendered)) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }

    private static boolean missing(Component component) {
        if (component instanceof TranslatableComponent) {
            return true;
        }
        for (Component child : component.children()) {
            if (missing(child)) {
                return true;
            }
        }
        return false;
    }

    private static MessageFormat messageFormat(String template) {
        StringBuilder builder = new StringBuilder(template.length());
        Matcher matcher = ARGUMENT.matcher(template);
        int cursor = 0;
        int automatic = 0;
        while (matcher.find()) {
            builder.append(escape(template.substring(cursor, matcher.start())));
            int index = matcher.group(1) == null ? automatic++ : Integer.parseInt(matcher.group(1)) - 1;
            builder.append('{').append(index).append('}');
            cursor = matcher.end();
        }
        builder.append(escape(template.substring(cursor)));
        return new MessageFormat(builder.toString(), LOCALE);
    }

    private static String escape(String text) {
        return text.replace("%%", "%").replace("'", "''").replace("{", "'{'").replace("}", "'}'");
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                builder.append(current);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        builder.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }
}
