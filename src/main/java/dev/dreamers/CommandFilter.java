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
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CommandFilter implements Listener {
    private static final String BYPASS = "dreamers.commands.bypass";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String BLOCKED = "&cEste comando está bloqueado.";
    private static final List<String> DEFAULT_ALLOWED = List.of(
            "tell",
            "msg",
            "me",
            "abandonclaim",
            "unclaim",
            "trust",
            "untrust",
            "containertrust",
            "accesstrust",
            "permissiontrust",
            "unlockdrops",
            "abandonallclaims",
            "givepet",
            "trustlist",
            "dreamers armor"
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
        if (entries.stream().noneMatch(entry -> "dreamers armor".equals(allowedEntry(entry)))) {
            entries.add("dreamers armor");
            config.set("commands.allowed", entries);
            changed = true;
        }
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
