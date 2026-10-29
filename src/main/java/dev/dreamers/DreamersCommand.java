package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

final class DreamersCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ACTIONS = List.of("on", "off", "time");

    private final DeathUhc uhc;

    DreamersCommand(DeathUhc uhc) {
        this.uhc = uhc;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("DeathUCH")) {
            sender.sendMessage(Component.text("Uso: " + DeathUhc.USAGE, NamedTextColor.YELLOW));
            return true;
        }
        uhc.command(sender, args);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("dreamers.deathuhc")) {
            return List.of();
        }
        if (args.length == 1) {
            return startsWith("DeathUCH", args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("DeathUCH")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private static List<String> startsWith(String option, String typed) {
        return option.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT)) ? List.of(option) : List.of();
    }
}
