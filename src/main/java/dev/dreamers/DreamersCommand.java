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
    private static final String CLAIM_USAGE = "/dreamers unclaim | /dreamers trust <jugador|public> | "
            + "/dreamers untrust <jugador|public|all> | /dreamers containertrust <jugador|public> | "
            + "/dreamers accesstrust <jugador|public> | /dreamers permissiontrust <jugador|public> | "
            + "/dreamers trustlist | /dreamers abandonallclaims | /dreamers unlockdrops | /dreamers givepet <jugador>";
    private static final String USAGE = "/dreamers armor <true|false> [jugador] | /dreamers scoreboard_info | "
            + "/dreamers DeathUCH <on|off|time> | /dreamers reload | " + XaeroMaps.USAGE + " | " + CustomItems.USAGE
            + " | " + ViewDistance.USAGE + " | " + CLAIM_USAGE + " | /dreamers adminclaims | /dreamers deleteclaim | "
            + "/dreamers inventory <jugador> | /dreamers enderchest <jugador>";
    private static final String PLAYER_USAGE = "/dreamers armor <true|false> | /dreamers scoreboard_info | " + CLAIM_USAGE;
    private static final List<String> CLAIM_ACTIONS = List.of(
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
    private static final List<String> CLAIM_ADMIN = List.of("adminclaims", "deleteclaim");
    private static final Component DENIED = Component.text("No puedes usar este comando.", NamedTextColor.RED);
    private static final List<String> ACTIONS = List.of("on", "off", "time");

    private final DeathUhc uhc;
    private final CommandFilter commands;
    private final XaeroMaps xaero;
    private final CustomItems items;
    private final ArmorVisibility armor;
    private final FabricModWhitelist mods;
    private final PersonalLoot loot;
    private final ViewDistance viewDistance;
    private final Claims claims;
    private final ClaimWorld claimWorld;
    private final ClaimDrops claimDrops;
    private final InventorySee inventories;
    private final TabBoard tab;

    DreamersCommand(DeathUhc uhc, CommandFilter commands, XaeroMaps xaero, CustomItems items, ArmorVisibility armor, FabricModWhitelist mods, PersonalLoot loot, ViewDistance viewDistance, Claims claims, ClaimWorld claimWorld, ClaimDrops claimDrops, InventorySee inventories, TabBoard tab) {
        this.uhc = uhc;
        this.commands = commands;
        this.xaero = xaero;
        this.items = items;
        this.armor = armor;
        this.mods = mods;
        this.loot = loot;
        this.viewDistance = viewDistance;
        this.claims = claims;
        this.claimWorld = claimWorld;
        this.claimDrops = claimDrops;
        this.inventories = inventories;
        this.tab = tab;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("armor")) {
            armor.command(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("scoreboard_info")) {
            if (args.length != 1) {
                usage(sender);
                return true;
            }
            tab.command(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            items.give(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("viewdistance")) {
            viewDistance.command(sender, args);
            return true;
        }
        if (inventories.command(sender, args)) {
            return true;
        }
        if (claimWorld.command(sender, args) || claimDrops.command(sender, args)) {
            return true;
        }
        if (!sender.hasPermission("dreamers.deathuhc")) {
            sender.sendMessage(DENIED);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (args.length != 1) {
                usage(sender);
                return true;
            }
            commands.reload();
            claims.reload();
            viewDistance.reload();
            loot.reload();
            uhc.reload();
            xaero.reload();
            armor.reload();
            mods.reload();
            tab.reload();
            sender.sendMessage(Component.text("La configuración se ha recargado correctamente.", NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("xm")) {
            xaero.command(sender, args);
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
        boolean admin = sender.hasPermission("dreamers.deathuhc");
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if (admin) {
                options.add("DeathUCH");
                options.add("reload");
                options.add("xm");
                if (CustomItems.canGive(sender)) {
                    options.add("give");
                }
            }
            if (ViewDistance.operator(sender)) {
                options.add("viewdistance");
            }
            if (InventorySee.operator(sender)) {
                options.add("inventory");
                options.add("enderchest");
            }
            options.add("armor");
            options.add("scoreboard_info");
            options.addAll(CLAIM_ACTIONS);
            if (Claims.admin(sender)) {
                options.addAll(CLAIM_ADMIN);
            }
            return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (isClaim(args[0])) {
            return claimWorld.complete(sender, args);
        }
        if (args[0].equalsIgnoreCase("armor")) {
            return armor.complete(sender, args);
        }
        if (args[0].equalsIgnoreCase("scoreboard_info")) {
            return List.of();
        }
        if (args[0].equalsIgnoreCase("viewdistance")) {
            return viewDistance.complete(sender, args);
        }
        if (args[0].equalsIgnoreCase("inventory") || args[0].equalsIgnoreCase("enderchest")) {
            return inventories.complete(sender, args);
        }
        if (!admin) {
            return List.of();
        }
        if (args[0].equalsIgnoreCase("give")) {
            return items.complete(sender, args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("DeathUCH")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("xm")) {
            return xaero.complete(sender, args);
        }
        return List.of();
    }

    private static boolean isClaim(String name) {
        return CLAIM_ACTIONS.contains(name.toLowerCase(Locale.ROOT)) || CLAIM_ADMIN.contains(name.toLowerCase(Locale.ROOT));
    }

    private static void usage(CommandSender sender) {
        String text = sender.hasPermission("dreamers.deathuhc") ? USAGE : PLAYER_USAGE;
        sender.sendMessage(Component.text("Uso: " + text, NamedTextColor.YELLOW));
    }
}
