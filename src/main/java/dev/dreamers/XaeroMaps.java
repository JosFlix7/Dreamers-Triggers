package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Le dice a Xaero qué modos del minimapa puede usar cada jugador.
 * La regla queda en la configuración; si no tiene una, valen los valores por defecto.
 */
final class XaeroMaps implements Listener {
    static final String USAGE = "/dreamers xm <jugador> <cuevas> <cuevasNether> <radar>";
    private static final String WORLD_MAP = "xaeroworldmap:main";
    private static final String MINI_MAP = "xaerominimap:main";
    private static final List<String> COMMENTS = List.of(
            "Xaero's Minimap. Los allow-* valen para quien no tiene regla propia.",
            "level-id identifica el servidor ante el mod; no lo cambies.",
            "/dreamers xm <jugador> <cuevas> <cuevasNether> <radar> guarda true o false para ese jugador."
    );

    private final JavaPlugin plugin;
    private final Map<UUID, Rule> players = new HashMap<>();
    private Rule defaults = new Rule(true, true, true);
    private int levelId;

    XaeroMaps(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, WORLD_MAP);
        messenger.registerOutgoingPluginChannel(plugin, MINI_MAP);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            send(player);
        }
    }

    void disable() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, WORLD_MAP);
        messenger.unregisterOutgoingPluginChannel(plugin, MINI_MAP);
    }

    void reload() {
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            send(player);
        }
    }

    void command(CommandSender sender, String[] args) {
        if (args.length != 5) {
            sender.sendMessage(Component.text("Uso: " + USAGE, NamedTextColor.YELLOW));
            return;
        }
        Boolean cave = bool(args[2]);
        Boolean nether = bool(args[3]);
        Boolean radar = bool(args[4]);
        if (cave == null || nether == null || radar == null) {
            sender.sendMessage(Component.text("Uso: " + USAGE + ". Cada modo es true o false.", NamedTextColor.YELLOW));
            return;
        }
        OfflinePlayer target = find(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Ese jugador no ha entrado al servidor.", NamedTextColor.RED));
            return;
        }
        Rule rule = new Rule(cave, nether, radar);
        players.put(target.getUniqueId(), rule);
        String name = target.getName() == null ? args[1] : target.getName();
        FileConfiguration config = plugin.getConfig();
        String path = "xaero.players." + target.getUniqueId();
        config.set(path + ".name", name);
        config.set(path + ".allow-cave-mode", rule.cave());
        config.set(path + ".allow-nether-cave-mode", rule.nether());
        config.set(path + ".allow-radar", rule.radar());
        plugin.saveConfig();
        boolean online = target.isOnline() && target.getPlayer() != null;
        if (online) {
            send(target.getPlayer());
        }
        String saved = online ? "Quedó guardado y se aplicó." : "Quedó guardado y se aplicará cuando entre.";
        sender.sendMessage(Component.text("Xaero de " + name + ": " + rule.text() + " " + saved, NamedTextColor.GREEN));
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent event) {
        String channel = event.getChannel();
        if (!WORLD_MAP.equals(channel) && !MINI_MAP.equals(channel)) {
            return;
        }
        send(event.getPlayer(), channel);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        send(event.getPlayer());
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.contains("xaero.level-id")) {
            levelId = resolveLevelId();
            config.set("xaero.level-id", levelId);
            config.setComments("xaero", COMMENTS);
            changed = true;
        } else {
            levelId = config.getInt("xaero.level-id");
        }
        if (!config.contains("xaero.allow-cave-mode")) {
            config.set("xaero.allow-cave-mode", true);
            changed = true;
        }
        if (!config.contains("xaero.allow-nether-cave-mode")) {
            config.set("xaero.allow-nether-cave-mode", true);
            changed = true;
        }
        if (!config.contains("xaero.allow-radar")) {
            config.set("xaero.allow-radar", true);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
        defaults = new Rule(
                config.getBoolean("xaero.allow-cave-mode", true),
                config.getBoolean("xaero.allow-nether-cave-mode", true),
                config.getBoolean("xaero.allow-radar", true)
        );
        players.clear();
        ConfigurationSection section = config.getConfigurationSection("xaero.players");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                players.put(UUID.fromString(id), new Rule(
                        section.getBoolean(id + ".allow-cave-mode", defaults.cave()),
                        section.getBoolean(id + ".allow-nether-cave-mode", defaults.nether()),
                        section.getBoolean(id + ".allow-radar", defaults.radar())
                ));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Regla de Xaero ignorada, el id no es un UUID: " + id);
            }
        }
    }

    private int resolveLevelId() {
        Integer file = readIdFile(new File(plugin.getServer().getWorldContainer(), "xaeromap.txt"));
        if (file != null) {
            return file;
        }
        Integer old = readOldPlugin();
        if (old != null) {
            return old;
        }
        int created = new Random().nextInt();
        plugin.getLogger().warning("No había id de Xaero; se creó uno nuevo. Los mapas de los jugadores pueden reiniciarse.");
        writeIdFile(created);
        return created;
    }

    private Integer readOldPlugin() {
        File parent = plugin.getDataFolder().getParentFile();
        if (parent == null) {
            return null;
        }
        File file = new File(parent, "XaeroMapPlugin-Paper/config.yml");
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return yaml.contains("level-id") ? yaml.getInt("level-id") : null;
    }

    private static Integer readIdFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            String line = Files.readString(file.toPath()).lines().findFirst().orElse("");
            String[] parts = line.trim().split(":", 2);
            if (parts.length != 2 || !parts[0].equals("id")) {
                return null;
            }
            return Integer.parseInt(parts[1].trim());
        } catch (IOException | NumberFormatException ex) {
            return null;
        }
    }

    private void writeIdFile(int id) {
        File file = new File(plugin.getServer().getWorldContainer(), "xaeromap.txt");
        if (file.exists()) {
            return;
        }
        try {
            Files.writeString(file.toPath(), "id:" + id);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo escribir xaeromap.txt: " + ex.getMessage());
        }
    }

    private void send(Player player) {
        send(player, WORLD_MAP);
        send(player, MINI_MAP);
    }

    private void send(Player player, String channel) {
        Rule rule = players.getOrDefault(player.getUniqueId(), defaults);
        player.sendPluginMessage(plugin, channel, rulesPacket(rule));
        player.sendPluginMessage(plugin, channel, levelPacket(levelId));
    }

    private OfflinePlayer find(String name) {
        Player exact = plugin.getServer().getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        OfflinePlayer loose = null;
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            String known = player.getName();
            if (known == null) {
                continue;
            }
            if (known.equals(name)) {
                return player;
            }
            if (known.equalsIgnoreCase(name)) {
                loose = player;
            }
        }
        return loose;
    }

    private static Boolean bool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    private static byte[] rulesPacket(Rule rule) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(4);
            out.writeByte(10);
            writeBoolean(out, "cm", rule.cave());
            writeBoolean(out, "ncm", rule.nether());
            writeBoolean(out, "r", rule.radar());
            out.writeByte(0);
            return raw.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeBoolean(DataOutputStream out, String key, boolean value) throws IOException {
        out.writeByte(1);
        out.writeUTF(key);
        out.writeByte(value ? 1 : 0);
    }

    private static byte[] levelPacket(int id) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(0);
            out.writeInt(id);
            return raw.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Rule(boolean cave, boolean nether, boolean radar) {
        private String text() {
            return "cuevas " + word(cave) + ", cuevas del Nether " + word(nether) + ", radar " + word(radar) + ".";
        }

        private static String word(boolean value) {
            return value ? "sí" : "no";
        }
    }
}
