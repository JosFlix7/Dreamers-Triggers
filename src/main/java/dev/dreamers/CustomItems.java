package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

final class CustomItems {
    static final String USAGE = "/dreamers give <jugador> <objeto> <cantidad>";
    private static final int MAX = 99;

    private final List<Spec> specs;

    CustomItems(BundleSack sacks) {
        specs = List.of(
                spec("nutritotem", DreamerTotem::nutri),
                spec("totem_ferreo", DreamerTotem::ferreo),
                spec("totem_yunque", DreamerTotem::yunque),
                spec("totem_dreamer", DreamerTotem::dreamer),
                spec("totem_no_muertos", DreamerTotem::undead),
                spec("enderbag", Enderbag::item),
                spec("saco_mediano", () -> sacks.customSack(18, false)),
                spec("saco_grande", () -> sacks.customSack(27, false)),
                spec("saco_shulker", () -> sacks.customSack(54, true)),
                spec("reforzada_casco", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_HELMET)),
                spec("reforzada_pechera", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_CHESTPLATE)),
                spec("reforzada_pantalones", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_LEGGINGS)),
                spec("reforzada_botas", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_BOOTS)),
                spec("reforzada_hacha", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_AXE)),
                spec("reforzada_azada", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_HOE)),
                spec("reforzada_pico", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_PICKAXE)),
                spec("reforzada_pala", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_SHOVEL)),
                spec("reforzada_espada", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_SWORD)),
                spec("reforzada_lanza", () -> ReinforcedNetherite.reinforced(Material.NETHERITE_SPEAR))
        );
    }

    static boolean canGive(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp();
    }

    void give(CommandSender sender, String[] args) {
        if (!canGive(sender)) {
            sender.sendMessage(Component.text("No puedes usar este comando.", NamedTextColor.RED));
            return;
        }
        if (args.length != 4) {
            sender.sendMessage(Component.text("Uso: " + USAGE, NamedTextColor.YELLOW));
            return;
        }
        Player target = player(sender, args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Ese jugador no está conectado.", NamedTextColor.RED));
            return;
        }
        Spec spec = find(args[2]);
        if (spec == null) {
            sender.sendMessage(Component.text("Ese objeto no existe.", NamedTextColor.RED));
            return;
        }
        int count;
        try {
            count = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("La cantidad tiene que estar entre 1 y " + MAX + ".", NamedTextColor.RED));
            return;
        }
        if (count < 1 || count > MAX) {
            sender.sendMessage(Component.text("La cantidad tiene que estar entre 1 y " + MAX + ".", NamedTextColor.RED));
            return;
        }
        ItemStack template = spec.create.get();
        if (template == null || template.getType().isAir()) {
            sender.sendMessage(Component.text("Ese objeto no existe.", NamedTextColor.RED));
            return;
        }
        boolean dropped = hand(target, template, count);
        sender.sendMessage(Component.text("Le diste " + count + " ", NamedTextColor.GREEN)
                .append(template.displayName().colorIfAbsent(NamedTextColor.GREEN))
                .append(Component.text(" a " + target.getName() + ".", NamedTextColor.GREEN)));
        if (dropped) {
            sender.sendMessage(Component.text("Lo que no cupo en el inventario se soltó al suelo.", NamedTextColor.YELLOW));
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!canGive(sender)) {
            return List.of();
        }
        if (args.length == 2) {
            return starting(online(sender), args[1]);
        }
        if (args.length == 3) {
            return starting(specs.stream().map(spec -> spec.id).toList(), args[2]);
        }
        if (args.length == 4) {
            return starting(List.of("1"), args[3]);
        }
        return List.of();
    }

    private static boolean hand(Player player, ItemStack template, int count) {
        int max = Math.max(1, template.getMaxStackSize());
        int left = count;
        boolean dropped = false;
        while (left > 0) {
            ItemStack stack = template.clone();
            int amount = Math.min(max, left);
            stack.setAmount(amount);
            left -= amount;
            for (ItemStack extra : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), extra);
                dropped = true;
            }
        }
        return dropped;
    }

    private Spec find(String id) {
        for (Spec spec : specs) {
            if (spec.id.equalsIgnoreCase(id)) {
                return spec;
            }
        }
        return null;
    }

    private static Player player(CommandSender sender, String name) {
        for (Player online : sender.getServer().getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    private static List<String> online(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Player online : sender.getServer().getOnlinePlayers()) {
            names.add(online.getName());
        }
        return names;
    }

    private static List<String> starting(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static Spec spec(String id, Supplier<ItemStack> create) {
        return new Spec(id, create);
    }

    private record Spec(String id, Supplier<ItemStack> create) {
    }
}
