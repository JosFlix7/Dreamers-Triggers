package dev.dreamers;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PackTexts implements Listener {
    private static final PackTexts LISTENER = new PackTexts();
    private static final Pattern TRANSLATE = Pattern.compile("\"translate\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern LOCALE_NAME = Pattern.compile("[a-z]{2,3}(?:_[a-z0-9]+)?");
    private static final int MAX_JSON = 2 * 1024 * 1024;

    private static JavaPlugin plugin;
    private static volatile Map<String, Map<String, String>> index = Map.of();

    private PackTexts() {}

    static void enable(JavaPlugin source) {
        disable();
        plugin = source;
        Bukkit.getPluginManager().registerEvents(LISTENER, source);
        reload();
    }

    static void disable() {
        HandlerList.unregisterAll(LISTENER);
        index = Map.of();
        plugin = null;
    }

    @EventHandler
    public void onResources(ServerResourcesReloadedEvent event) {
        reload();
    }

    static String render(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        append(builder, component);
        return builder.toString().trim();
    }

    private static void reload() {
        JavaPlugin current = plugin;
        if (current == null) {
            return;
        }
        Set<String> keys = new HashSet<>();
        Map<String, Map<String, String>> found = new HashMap<>();
        List<File> packs = packs();
        for (File pack : packs) {
            try {
                if (pack.isDirectory()) {
                    collectDirectory(pack, pack, keys, true);
                } else {
                    readZip(pack, keys, null);
                }
            } catch (IOException exception) {
                current.getLogger().warning("No se pudieron leer los logros de " + pack.getName() + ": " + exception.getMessage());
            }
        }
        for (File pack : packs) {
            try {
                if (pack.isDirectory()) {
                    collectDirectory(pack, pack, keys, false, found);
                } else {
                    readZip(pack, keys, found);
                }
            } catch (IOException exception) {
                current.getLogger().warning("No se pudieron leer los idiomas de " + pack.getName() + ": " + exception.getMessage());
            }
        }
        Map<String, Map<String, String>> frozen = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : found.entrySet()) {
            frozen.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        index = Map.copyOf(frozen);
        current.getLogger().info("Idiomas de logros: " + index.size() + " textos en datapacks.");
    }

    private static List<File> packs() {
        List<File> packs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            File datapacks = new File(world.getWorldFolder(), "datapacks");
            if (!datapacks.isDirectory()) {
                continue;
            }
            try {
                String canonical = datapacks.getCanonicalPath();
                if (!seen.add(canonical)) {
                    continue;
                }
            } catch (IOException exception) {
                continue;
            }
            addPacks(datapacks, packs, 0);
        }
        return packs;
    }

    private static void addPacks(File directory, List<File> packs, int depth) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (new File(child, "pack.mcmeta").isFile() || new File(child, "data").isDirectory()) {
                    packs.add(child);
                } else if (depth < 2) {
                    addPacks(child, packs, depth + 1);
                }
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                packs.add(child);
            }
        }
    }

    private static void readZip(File file, Set<String> keys, Map<String, Map<String, String>> found) throws IOException {
        try (ZipFile zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getSize() > MAX_JSON) {
                    continue;
                }
                String name = normalize(entry.getName());
                if (found == null) {
                    if (!isAdvancement(name)) {
                        continue;
                    }
                    String json = read(zip.getInputStream(entry));
                    if (json != null) {
                        collectKeys(json, keys);
                    }
                } else {
                    String locale = locale(name);
                    if (locale == null || !isLanguage(name)) {
                        continue;
                    }
                    String json = read(zip.getInputStream(entry));
                    if (json != null) {
                        keep(json, locale, keys, found);
                    }
                }
            }
        }
    }

    private static void collectDirectory(File root, File file, Set<String> keys, boolean advancements) throws IOException {
        collectDirectory(root, file, keys, advancements, null);
    }

    private static void collectDirectory(
            File root,
            File file,
            Set<String> keys,
            boolean advancements,
            Map<String, Map<String, String>> found
    ) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collectDirectory(root, child, keys, advancements, found);
            }
            return;
        }
        if (file.length() <= 0 || file.length() > MAX_JSON) {
            return;
        }
        String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        if (advancements) {
            if (isAdvancement(relative)) {
                String json = read(file.toPath());
                if (json != null) {
                    collectKeys(json, keys);
                }
            }
            return;
        }
        String locale = locale(relative);
        if (locale != null && isLanguage(relative) && found != null) {
            String json = read(file.toPath());
            if (json != null) {
                keep(json, locale, keys, found);
            }
        }
    }

    private static void collectKeys(String json, Set<String> keys) {
        Matcher matcher = TRANSLATE.matcher(json);
        while (matcher.find()) {
            keys.add(SpanishTexts.unescape(matcher.group(1)));
        }
    }

    private static void keep(String json, String locale, Set<String> keys, Map<String, Map<String, String>> found) {
        for (Map.Entry<String, String> entry : SpanishTexts.entries(json).entrySet()) {
            if (entry.getValue().isBlank() || !keys.contains(entry.getKey())) {
                continue;
            }
            found.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).putIfAbsent(locale, entry.getValue());
        }
    }

    private static void append(StringBuilder builder, Component component) {
        if (component instanceof TextComponent text) {
            builder.append(text.content());
        } else if (component instanceof TranslatableComponent translatable) {
            builder.append(translate(translatable));
        } else {
            try {
                builder.append(PlainTextComponentSerializer.plainText().serialize(component.children(List.of())));
            } catch (RuntimeException ignored) {
                builder.append("");
            }
        }
        for (Component child : component.children()) {
            append(builder, child);
        }
    }

    private static String translate(TranslatableComponent component) {
        Object[] arguments = new Object[component.arguments().size()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = render(component.arguments().get(i).asComponent());
        }
        String vanilla = SpanishTexts.vanilla(component.key(), arguments);
        if (vanilla != null) {
            return vanilla;
        }
        String template = template(component.key());
        if (template != null) {
            return SpanishTexts.apply(template, arguments);
        }
        String fallback = component.fallback();
        return fallback == null ? "" : fallback;
    }

    private static String template(String key) {
        Map<String, String> locales = index.get(key);
        if (locales == null || locales.isEmpty()) {
            return null;
        }
        String spanish = firstMatch(locales, PackTexts::spanish, "es_mx", "es_es");
        if (spanish != null) {
            return spanish;
        }
        String english = firstMatch(locales, PackTexts::english, "en_us", "en_gb");
        if (english != null) {
            return english;
        }
        if (locales.size() == 1) {
            return locales.values().iterator().next();
        }
        String best = null;
        for (String locale : locales.keySet()) {
            if (best == null || locale.compareTo(best) < 0) {
                best = locale;
            }
        }
        return best == null ? null : locales.get(best);
    }

    private static String firstMatch(Map<String, String> locales, Predicate<String> test, String... preferred) {
        for (String name : preferred) {
            String value = locales.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        String best = null;
        for (Map.Entry<String, String> entry : locales.entrySet()) {
            if (entry.getValue().isBlank() || !test.test(entry.getKey())) {
                continue;
            }
            if (best == null || entry.getKey().compareTo(best) < 0) {
                best = entry.getKey();
            }
        }
        return best == null ? null : locales.get(best);
    }

    private static boolean spanish(String locale) {
        return locale.equals("es") || locale.startsWith("es_");
    }

    private static boolean english(String locale) {
        return locale.equals("en") || locale.startsWith("en_");
    }

    private static boolean isAdvancement(String name) {
        return name.endsWith(".json")
                && name.startsWith("data/")
                && !name.contains("/tags/")
                && (name.contains("/advancement/") || name.contains("/advancements/"));
    }

    private static boolean isLanguage(String name) {
        return name.startsWith("assets/") && name.contains("/lang/") && locale(name) != null;
    }

    private static String locale(String name) {
        int slash = name.lastIndexOf('/');
        String file = slash < 0 ? name : name.substring(slash + 1);
        if (!file.endsWith(".json")) {
            return null;
        }
        String locale = file.substring(0, file.length() - ".json".length()).toLowerCase(Locale.ROOT);
        return LOCALE_NAME.matcher(locale).matches() ? locale : null;
    }

    private static String normalize(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }

    private static String read(Path path) throws IOException {
        try (InputStream input = java.nio.file.Files.newInputStream(path)) {
            return read(input);
        }
    }

    private static String read(InputStream input) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(MAX_JSON + 1);
            if (bytes.length == 0 || bytes.length > MAX_JSON) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
