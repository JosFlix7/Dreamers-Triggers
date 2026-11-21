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
 * Fabric Mod Whitelist. Entran los clientes de Clients. Un operador entra con cualquiera.
 * A los de Check mods se les revisa la lista: cada mod tiene que estar en Mods admitidos.
 */
final class FabricModWhitelist implements Listener, PluginMessageListener {
    static final String CHANNEL = "dreamers:mods";
    private static final String ROOT = "fabric-mod-whitelist";
    private static final Pattern MOD_ID = Pattern.compile("^[a-z][a-z0-9_\\-]{0,63}$");
    private static final Pattern CLIENT = Pattern.compile("^[a-z0-9][a-z0-9_\\-.]{0,63}$");
    private static final long WAIT_MS = 3000L;
    private static final List<String> DEFAULT_CLIENTS = List.of("vanilla", "fabric", "horizon");
    private static final List<String> DEFAULT_CHECK = List.of("fabric");
    private static final Set<String> ALWAYS = Set.of("minecraft", "java", "fabricloader", "mixinextras");
    private static final List<String> COMMENTS = List.of(
            "Fabric Mod Whitelist. Con enabled en true solo entra un operador o un cliente de Clients.",
            "Check mods revisa la lista de esos clientes: sale quien no la manda o trae un mod que no está admitido.",
            "La versión no importa y no hace falta traerlos todos.",
            "minecraft, java, fabricloader y mixinextras se admiten siempre.",
            "Los mods empaquetados dentro de otro jar no se revisan.",
            "dreamers-mod-sender tiene que quedarse: es el mod que manda la lista."
    );

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, Report> reports = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile List<String> clients = List.of();
    private volatile Set<String> clientSet = Set.of();
    private volatile Set<String> checkMods = Set.of();
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
        if (!config.isList(ROOT + ".clients")) {
            config.set(ROOT + ".clients", DEFAULT_CLIENTS);
            config.setComments(ROOT + ".clients", List.of("Clients"));
            changed = true;
        }
        if (!config.isList(ROOT + ".check-mods")) {
            config.set(ROOT + ".check-mods", DEFAULT_CHECK);
            config.setComments(ROOT + ".check-mods", List.of("Check mods"));
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
        List<String> clientNames = names(config.getStringList(ROOT + ".clients"));
        clients = clientNames;
        clientSet = Set.copyOf(clientNames);
        checkMods = Set.copyOf(names(config.getStringList(ROOT + ".check-mods")));
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
        List<String> admitted = clients;
        Set<String> admittedSet = clientSet;
        Set<String> checked = checkMods;
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (true) {
            if (!connection.isConnected()) {
                if (id != null) {
                    reports.remove(id);
                }
                return;
            }
            List<String> brand = tokens(connection.getClientBrandName());
            Report report = id == null ? null : reports.get(id);
            if (!brand.isEmpty() && !admitted(brand, admittedSet)) {
                deny(connection, id, admittedClients(admitted));
                return;
            }
            if (!brand.isEmpty() && !checks(brand, checked)) {
                return;
            }
            if (!brand.isEmpty() && report != null) {
                if (!valid(brand, report)) {
                    deny(connection, id, "El cliente no envió una lista de mods válida.");
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
        if (tokens(connection.getClientBrandName()).isEmpty()) {
            deny(connection, id, "No se pudo identificar el cliente.");
            return;
        }
        deny(connection, id, "El cliente no envió la lista de mods.");
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
        return new Report(Set.copyOf(mods), true);
    }

    private static List<String> names(List<String> entries) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String name = entry.trim().toLowerCase(Locale.ROOT);
            if (CLIENT.matcher(name).matches() && seen.add(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static boolean admitted(List<String> brand, Set<String> clients) {
        for (String token : brand) {
            if (!clients.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean checks(List<String> brand, Set<String> checkMods) {
        for (String token : brand) {
            if (checkMods.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(List<String> brand, Report report) {
        return report.valid() && (!brand.contains("fabric") || report.mods().contains("fabricloader"));
    }

    private static String admittedClients(List<String> clients) {
        if (clients.isEmpty()) {
            return "Este cliente no está admitido.";
        }
        if (clients.size() == 1) {
            return "Solo se puede entrar con " + clients.get(0) + ".";
        }
        String head = String.join(", ", clients.subList(0, clients.size() - 1));
        return "Solo se puede entrar con " + head + " o " + clients.get(clients.size() - 1) + ".";
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
