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
            "trustlist"
    );
    private static final List<String> COMMENTS = List.of(
            "Comandos que puede usar quien no es op y no tiene dreamers.commands.bypass.",
            "El tabulador solo ofrece estos. El nombre va sin la barra.",
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
            config.setComments("commands", COMMENTS);
            changed = true;
        }
        if (!config.isString("commands.blocked")) {
            config.set("commands.blocked", BLOCKED);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
        Set<String> next = new HashSet<>();
        for (String entry : config.getStringList("commands.allowed")) {
            String command = label(entry);
            if (!command.isEmpty()) {
                next.add(command);
            }
        }
        allowed = Set.copyOf(next);
        String message = config.getString("commands.blocked", BLOCKED);
        blocked = message == null || message.isBlank() ? BLOCKED : message;
    }

    void reload() {
        plugin.reloadConfig();
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.updateCommands();
        }
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
        event.getCommands().removeIf(command -> !permitted(command));
    }

    private boolean permitted(String raw) {
        String command = label(raw);
        if (command.isEmpty()) {
            return false;
        }
        if (allowed.contains(command)) {
            return true;
        }
        int colon = command.lastIndexOf(':');
        return colon >= 0 && colon < command.length() - 1 && allowed.contains(command.substring(colon + 1));
    }

    private static boolean bypasses(Player player) {
        return player.isOp() || player.hasPermission(BYPASS);
    }

    private static String label(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim();
        if (token.isEmpty()) {
            return "";
        }
        int space = token.indexOf(' ');
        if (space >= 0) {
            token = token.substring(0, space);
        }
        return token.replace("/", "").toLowerCase(Locale.ROOT);
    }
}
