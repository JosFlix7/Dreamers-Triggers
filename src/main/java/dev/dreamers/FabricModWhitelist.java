package dev.dreamers;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Fabric Mod Whitelist. Vanilla entra. Un operador entra con cualquier cliente.
 * Fabric entra si mandó su lista y cada mod está en Mods admitidos.
 */
final class FabricModWhitelist implements Listener, PluginMessageListener {
    static final String CHANNEL = "dreamers:mods";
    private static final String ROOT = "fabric-mod-whitelist";
    private static final Pattern MOD_ID = Pattern.compile("^[a-z][a-z0-9_\\-]{0,63}$");
    private static final long WAIT_MS = 3000L;
    private static final Set<String> ALWAYS = Set.of("minecraft", "java", "fabricloader", "mixinextras");
    private static final List<String> COMMENTS = List.of(
            "Fabric Mod Whitelist. Con enabled en true solo entran vanilla, un operador,",
            "o Fabric si cada mod que manda está en Mods admitidos.",
            "La versión no importa y no hace falta traerlos todos.",
            "Un Fabric que no manda la lista sale. Otro cargador también.",
            "minecraft, java, fabricloader y mixinextras se admiten siempre.",
            "Los mods empaquetados dentro de otro jar no se revisan.",
            "dreamers-mod-sender tiene que quedarse: es el mod que manda la lista."
    );

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, Report> reports = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile Set<String> allowed = Set.of();

    FabricModWhitelist(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        load();
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void disable() {
        reports.clear();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
    }

    void reload() {
        load();
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.contains(ROOT + ".enabled")) {
            config.set(ROOT + ".enabled", true);
            changed = true;
        }
        if (!config.isList(ROOT + ".allowed")) {
            config.set(ROOT + ".allowed", defaultAllowed());
            config.setComments(ROOT + ".allowed", List.of("Mods admitidos"));
            changed = true;
        }
        if (changed) {
            config.setComments(ROOT, COMMENTS);
            plugin.saveConfig();
        }
        enabled = config.getBoolean(ROOT + ".enabled", true);
        Set<String> next = new HashSet<>(ALWAYS);
        for (String entry : config.getStringList(ROOT + ".allowed")) {
            if (entry == null) {
                continue;
            }
            String id = entry.trim().toLowerCase(Locale.ROOT);
            if (MOD_ID.matcher(id).matches()) {
                next.add(id);
            }
        }
        allowed = Set.copyOf(next);
    }

    private List<String> defaultAllowed() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return List.of();
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml.getStringList(ROOT + ".allowed");
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo leer la lista blanca de mods por defecto.");
            return List.of();
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, org.bukkit.entity.Player player, byte[] message) {
    }

    @Override
    public void onPluginMessageReceived(String channel, PlayerConnection connection, byte[] message) {
        if (!CHANNEL.equals(channel) || !(connection instanceof PlayerConfigurationConnection configuration)) {
            return;
        }
        UUID id = configuration.getProfile().getId();
        if (id != null) {
            reports.put(id, parse(message));
        }
    }

    @EventHandler
    public void onInitial(PlayerConnectionInitialConfigureEvent event) {
        UUID id = event.getConnection().getProfile().getId();
        if (id != null) {
            reports.remove(id);
        }
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        if (!enabled) {
            return;
        }
        PlayerConfigurationConnection connection = event.getConnection();
        UUID id = connection.getProfile().getId();
        if (id != null && plugin.getServer().getOfflinePlayer(id).isOp()) {
            return;
        }
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (true) {
            if (!connection.isConnected()) {
                return;
            }
            String brand = connection.getClientBrandName();
            Report report = id == null ? null : reports.get(id);
            if (vanilla(brand)) {
                return;
            }
            if (brand != null && !fabric(brand)) {
                deny(connection, id, "Solo se puede entrar con vanilla o con Fabric.");
                return;
            }
            if (fabric(brand) && report != null) {
                if (!report.valid()) {
                    deny(connection, id, "El cliente de Fabric no envió una lista de mods válida.");
                    return;
                }
                List<String> extra = extra(report.mods());
                if (!extra.isEmpty()) {
                    deny(connection, id, "Mods no admitidos: " + text(extra));
                    return;
                }
                return;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (connection.getClientBrandName() == null) {
            deny(connection, id, "No se pudo identificar el cliente.");
            return;
        }
        deny(connection, id, "El cliente de Fabric no envió la lista de mods.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reports.remove(event.getPlayer().getUniqueId());
    }

    private void deny(PlayerConfigurationConnection connection, UUID id, String message) {
        String name = connection.getProfile().getName();
        plugin.getLogger().info((name == null ? "Un jugador" : name) + " no entró: " + message);
        if (id != null) {
            reports.remove(id);
        }
        if (connection.isConnected()) {
            connection.disconnect(Component.text(message, NamedTextColor.RED));
        }
    }

    private List<String> extra(Set<String> mods) {
        Set<String> admitted = allowed;
        List<String> extra = new ArrayList<>();
        for (String id : mods) {
            if (!admitted.contains(id)) {
                extra.add(id);
            }
        }
        extra.sort(String::compareTo);
        return extra;
    }

    private static String text(List<String> extra) {
        int shown = Math.min(extra.size(), 12);
        String joined = String.join(", ", extra.subList(0, shown));
        if (extra.size() > shown) {
            joined = joined + " y " + (extra.size() - shown) + " más";
        }
        return joined;
    }

    private static Report parse(byte[] message) {
        String text = new String(message, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) {
            return Report.invalid();
        }
        Set<String> mods = new HashSet<>();
        for (String line : text.split("\n", -1)) {
            String id = line.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            if (!MOD_ID.matcher(id).matches()) {
                return Report.invalid();
            }
            mods.add(id);
        }
        if (!mods.contains("fabricloader")) {
            return Report.invalid();
        }
        return new Report(Set.copyOf(mods), true);
    }

    private static boolean vanilla(String brand) {
        List<String> tokens = tokens(brand);
        return !tokens.isEmpty() && tokens.stream().allMatch("vanilla"::equals);
    }

    private static boolean fabric(String brand) {
        List<String> tokens = tokens(brand);
        return tokens.contains("fabric")
                && tokens.stream().allMatch(token -> token.equals("fabric") || token.equals("vanilla"));
    }

    private static List<String> tokens(String brand) {
        if (brand == null || brand.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : brand.split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private record Report(Set<String> mods, boolean valid) {
        private static Report invalid() {
            return new Report(Set.of(), false);
        }
    }
}
