package dev.dreamers;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CommandFilter implements Listener {
    private static final String BYPASS = "dreamers.commands.bypass";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String BLOCKED = "&cEste comando está bloqueado.";
    private static final List<String> DEFAULT_ALLOWED = List.of(
            "tell",
            "msg",
            "me",
            "dreamers unclaim",
            "dreamers trust",
            "dreamers untrust",
            "dreamers containertrust",
            "dreamers accesstrust",
            "dreamers permissiontrust",
            "dreamers unlockdrops",
            "dreamers abandonallclaims",
            "dreamers givepet",
            "dreamers trustlist",
            "dreamers armor",
            "dreamers scoreboard_info"
    );
    private static final List<String> REQUIRED = List.of(
            "dreamers unclaim",
            "dreamers trust",
            "dreamers untrust",
            "dreamers containertrust",
            "dreamers accesstrust",
            "dreamers permissiontrust",
            "dreamers unlockdrops",
            "dreamers abandonallclaims",
            "dreamers givepet",
            "dreamers trustlist",
            "dreamers armor",
            "dreamers scoreboard_info"
    );
    private static final Map<String, String> REPLACED = Map.ofEntries(
            Map.entry("unclaim", "dreamers unclaim"),
            Map.entry("trust", "dreamers trust"),
            Map.entry("untrust", "dreamers untrust"),
            Map.entry("containertrust", "dreamers containertrust"),
            Map.entry("accesstrust", "dreamers accesstrust"),
            Map.entry("permissiontrust", "dreamers permissiontrust"),
            Map.entry("unlockdrops", "dreamers unlockdrops"),
            Map.entry("abandonallclaims", "dreamers abandonallclaims"),
            Map.entry("givepet", "dreamers givepet"),
            Map.entry("trustlist", "dreamers trustlist")
    );
    private static final List<String> COMMENTS = List.of(
            "Comandos que puede usar quien no es op y no tiene dreamers.commands.bypass.",
            "El tabulador solo ofrece estos. El nombre va sin la barra.",
            "Un subcomando se escribe aparte: dreamers armor solo deja ese.",
            "minecraft:tell cuenta como tell. blocked acepta códigos de color &."
    );

    private final JavaPlugin plugin;
    private Set<String> allowed = Set.of();
    private String blocked = BLOCKED;

    CommandFilter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.isList("commands.allowed")) {
            config.set("commands.allowed", DEFAULT_ALLOWED);
            changed = true;
        }
        List<String> entries = new ArrayList<>(config.getStringList("commands.allowed"));
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String entry : entries) {
            String key = allowedEntry(entry);
            if (key.isEmpty() || "abandonclaim".equals(key)) {
                changed = true;
                continue;
            }
            String replacement = REPLACED.get(key);
            if (replacement != null) {
                if (!replacement.equals(key)) {
                    changed = true;
                }
                kept.add(replacement);
            } else {
                String trimmed = entry.trim();
                if (!trimmed.equals(entry)) {
                    changed = true;
                }
                kept.add(trimmed);
            }
        }
        for (String required : REQUIRED) {
            if (kept.add(required)) {
                changed = true;
            }
        }
        entries = new ArrayList<>(kept);
        config.set("commands.allowed", entries);
        if (!config.isString("commands.blocked")) {
            config.set("commands.blocked", BLOCKED);
            changed = true;
        }
        if (changed) {
            config.setComments("commands", COMMENTS);
            plugin.saveConfig();
        }
        Set<String> next = new HashSet<>();
        for (String entry : entries) {
            String command = allowedEntry(entry);
            if (!command.isEmpty()) {
                next.add(command);
            }
        }
        allowed = Set.copyOf(next);
        String message = config.getString("commands.blocked", BLOCKED);
        blocked = message == null || message.isBlank() ? BLOCKED : message;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    void reload() {
        plugin.reloadConfig();
        load();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player) || permitted(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(LEGACY.deserialize(blocked));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent event) {
        if (bypasses(event.getPlayer())) {
            return;
        }
        event.getCommands().removeIf(command -> !offered(command));
    }

    private boolean permitted(String raw) {
        String[] parts = words(raw);
        if (parts.length == 0) {
            return false;
        }
        String command = base(parts[0]);
        if (command.isEmpty()) {
            return false;
        }
        if (allowed.contains(command)) {
            return true;
        }
        return parts.length >= 2 && allowed.contains(command + " " + base(parts[1]));
    }

    private boolean offered(String raw) {
        String command = base(raw);
        if (command.isEmpty()) {
            return false;
        }
        if (allowed.contains(command)) {
            return true;
        }
        String prefix = command + " ";
        for (String entry : allowed) {
            if (entry.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bypasses(Player player) {
        return player.isOp() || player.hasPermission(BYPASS);
    }

    private static String allowedEntry(String raw) {
        String[] parts = words(raw);
        if (parts.length == 0) {
            return "";
        }
        String command = base(parts[0]);
        if (command.isEmpty() || parts.length == 1) {
            return command;
        }
        String sub = base(parts[1]);
        if (sub.isEmpty()) {
            return command;
        }
        return command + " " + sub;
    }

    private static String[] words(String raw) {
        if (raw == null) {
            return new String[0];
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }

    private static String base(String token) {
        if (token == null) {
            return "";
        }
        String command = token.trim();
        if (command.isEmpty()) {
            return "";
        }
        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        command = command.replace("/", "").toLowerCase(Locale.ROOT);
        int colon = command.lastIndexOf(':');
        if (colon >= 0 && colon < command.length() - 1) {
            command = command.substring(colon + 1);
        }
        return command;
    }
}
