package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Distancia de visión por jugador. Puede superar la del servidor, hasta 32.
 * reset vuelve a la del servidor. No avisa al jugador afectado.
 */
final class ViewDistance implements Listener {
    static final String USAGE = "/dreamers viewdistance <jugador|@a> <2-32|reset>";
    private static final int MIN = 2;
    private static final int MAX = 32;
    private static final List<String> COMMENTS = List.of(
            "Distancia de visión por jugador. /dreamers viewdistance <jugador|@a> <2-32|reset> la cambia.",
            "Solo un operador o la consola. reset vuelve a la distancia del servidor.",
            "@a ajusta a quien ya entró, esté conectado o no. Puede superar la distancia del servidor, hasta 32.",
            "El servidor carga y envía esos chunks solo a ese jugador. No se le avisa."
    );
    private static final Component DENIED = Component.text("No puedes usar este comando.", NamedTextColor.RED);
    private static final Component UNKNOWN = Component.text("Ese jugador no ha entrado al servidor.", NamedTextColor.RED);
    private static final Component NOBODY = Component.text("Nadie ha entrado al servidor.", NamedTextColor.RED);
    private static final Component RANGE = Component.text(
            "La distancia de visión va de " + MIN + " a " + MAX + ".",
            NamedTextColor.RED
    );
    private static final Component USAGE_TEXT = Component.text("Uso: " + USAGE, NamedTextColor.YELLOW);

    private final JavaPlugin plugin;
    private final Map<UUID, Integer> chosen = new HashMap<>();

    ViewDistance(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (chosen.containsKey(player.getUniqueId())) {
                apply(player, chosen.get(player.getUniqueId()));
            }
        }
    }

    void disable() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (chosen.containsKey(player.getUniqueId())) {
                apply(player, null);
            }
        }
    }

    void reload() {
        Set<UUID> previous = new HashSet<>(chosen.keySet());
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (chosen.containsKey(id) || previous.contains(id)) {
                apply(player, chosen.get(id));
            }
        }
    }

    void command(CommandSender sender, String[] args) {
        if (!operator(sender)) {
            sender.sendMessage(DENIED);
            return;
        }
        if (args.length != 3) {
            sender.sendMessage(USAGE_TEXT);
            return;
        }
        boolean reset = args[2].equalsIgnoreCase("reset");
        Integer distance = reset ? null : distance(args[2]);
        if (!reset && distance == null) {
            sender.sendMessage(RANGE);
            return;
        }
        if (args[1].equalsIgnoreCase("@a")) {
            adjustAll(sender, distance);
            return;
        }
        OfflinePlayer target = find(args[1]);
        if (target == null) {
            sender.sendMessage(UNKNOWN);
            return;
        }
        store(target, distance);
        plugin.saveConfig();
        sender.sendMessage(report(target.getName(), distance));
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!operator(sender)) {
            return List.of();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            Set<String> names = new LinkedHashSet<>();
            if ("@a".startsWith(prefix)) {
                names.add("@a");
            }
            for (OfflinePlayer player : everyone()) {
                String name = player.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        }
        if (args.length == 3 && "reset".startsWith(args[2].toLowerCase(Locale.ROOT))) {
            return List.of("reset");
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
        Integer distance = chosen.get(event.getPlayer().getUniqueId());
        if (distance != null) {
            apply(event.getPlayer(), distance);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Integer distance = chosen.get(event.getPlayer().getUniqueId());
        if (distance != null) {
            apply(event.getPlayer(), distance);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Integer distance = chosen.get(event.getPlayer().getUniqueId());
        if (distance != null) {
            apply(event.getPlayer(), distance);
        }
    }

    private void adjustAll(CommandSender sender, Integer distance) {
        List<OfflinePlayer> targets = everyone();
        if (targets.isEmpty()) {
            sender.sendMessage(NOBODY);
            return;
        }
        if (distance == null) {
            chosen.clear();
            plugin.getConfig().set("viewdistance.players", null);
            plugin.getConfig().createSection("viewdistance.players");
            plugin.getConfig().setComments("viewdistance", COMMENTS);
        }
        for (OfflinePlayer target : targets) {
            if (distance == null) {
                Player online = target.getPlayer();
                if (online != null && online.isOnline()) {
                    apply(online, null);
                }
            } else {
                store(target, distance);
            }
        }
        plugin.saveConfig();
        sender.sendMessage(distance == null
                ? Component.text("La distancia de visión de todos volvió a la del servidor.", NamedTextColor.GREEN)
                : Component.text("La distancia de visión de todos ahora es " + distance + ".", NamedTextColor.GREEN));
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.isConfigurationSection("viewdistance.players")) {
            config.createSection("viewdistance.players");
            config.setComments("viewdistance", COMMENTS);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
        chosen.clear();
        ConfigurationSection section = config.getConfigurationSection("viewdistance.players");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                int distance = section.getInt(id + ".distance", Integer.MIN_VALUE);
                if (distance < MIN || distance > MAX) {
                    plugin.getLogger().warning("Distancia de visión ignorada para " + id + ": " + section.get(id + ".distance"));
                    continue;
                }
                chosen.put(UUID.fromString(id), distance);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Distancia de visión ignorada, el id no es un UUID: " + id);
            }
        }
    }

    private void store(OfflinePlayer target, Integer distance) {
        UUID id = target.getUniqueId();
        String path = "viewdistance.players." + id;
        FileConfiguration config = plugin.getConfig();
        if (distance == null) {
            chosen.remove(id);
            config.set(path, null);
        } else {
            chosen.put(id, distance);
            if (target.getName() != null) {
                config.set(path + ".name", target.getName());
            }
            config.set(path + ".distance", distance);
        }
        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            apply(online, distance);
        }
    }

    private void remember(Player player) {
        if (!chosen.containsKey(player.getUniqueId())) {
            return;
        }
        String path = "viewdistance.players." + player.getUniqueId() + ".name";
        if (player.getName().equals(plugin.getConfig().getString(path))) {
            return;
        }
        plugin.getConfig().set(path, player.getName());
        plugin.saveConfig();
    }

    private void apply(Player player, Integer distance) {
        try {
            if (distance == null) {
                player.setSendViewDistance(-1);
                player.setViewDistance(-1);
                return;
            }
            // La carga del jugador sustituye a la del mundo y el envío no puede pasarla.
            // Al subir, primero la carga; al bajar, primero el envío.
            int current = player.getSendViewDistance();
            if (distance >= current) {
                player.setViewDistance(distance);
                player.setSendViewDistance(distance);
            } else {
                player.setSendViewDistance(distance);
                player.setViewDistance(distance);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo ajustar la distancia de visión de " + player.getName() + ": " + exception.getMessage());
        }
    }

    private List<OfflinePlayer> everyone() {
        Map<UUID, OfflinePlayer> known = new LinkedHashMap<>();
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            known.put(player.getUniqueId(), player);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            known.putIfAbsent(player.getUniqueId(), player);
        }
        return new ArrayList<>(known.values());
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

    private static Integer distance(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < MIN || value > MAX) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Component report(String name, Integer distance) {
        String who = name == null ? "ese jugador" : name;
        if (distance == null) {
            return Component.text("La distancia de visión de " + who + " volvió a la del servidor.", NamedTextColor.GREEN);
        }
        return Component.text("La distancia de visión de " + who + " ahora es " + distance + ".", NamedTextColor.GREEN);
    }

    static boolean operator(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp();
    }
}
