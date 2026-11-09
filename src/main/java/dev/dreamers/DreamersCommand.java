package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DreamersCommand implements CommandExecutor, TabCompleter {
    private static final String USAGE = "/dreamers DeathUCH <on|off|time> | /dreamers reload | "
            + XaeroMaps.USAGE + " | " + CustomItems.USAGE;
    private static final List<String> ACTIONS = List.of("on", "off", "time");
    private static final List<String> BOOLEANS = List.of("true", "false");

    private final DeathUhc uhc;
    private final CommandFilter commands;
    private final XaeroMaps xaero;
    private final CustomItems items;

    DreamersCommand(DeathUhc uhc, CommandFilter commands, XaeroMaps xaero, CustomItems items) {
        this.uhc = uhc;
        this.commands = commands;
        this.xaero = xaero;
        this.items = items;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (args.length != 1) {
                usage(sender);
                return true;
            }
            commands.reload();
            uhc.reload();
            xaero.reload();
            sender.sendMessage(Component.text("La configuración se ha recargado correctamente.", NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("xm")) {
            xaero.command(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            items.give(sender, args);
            return true;
        }
        if (!args[0].equalsIgnoreCase("DeathUCH")) {
            usage(sender);
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
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(List.of("DeathUCH", "reload", "xm"));
            if (CustomItems.canGive(sender)) {
                options.add("give");
            }
            return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("give")) {
            return items.complete(sender, args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("DeathUCH")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("xm")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return sender.getServer().getOnlinePlayers().stream()
                        .map(player -> player.getName())
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length >= 3 && args.length <= 5) {
                String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                return BOOLEANS.stream().filter(value -> value.startsWith(prefix)).toList();
            }
        }
        return List.of();
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Uso: " + USAGE, NamedTextColor.YELLOW));
    }
}
