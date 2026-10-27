package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

final class DreamerTotem {
    private static NamespacedKey mark;
    private static NamespacedKey recipe;

    private DreamerTotem() {}

    static void enable(JavaPlugin plugin) {
        mark = new NamespacedKey(plugin, "dreamer_totem");
        recipe = new NamespacedKey(plugin, "dreamer_totem");
        if (Bukkit.getRecipe(recipe) == null) {
            ShapedRecipe shaped = new ShapedRecipe(recipe, create());
            shaped.shape("DAD", "TET", "DAD");
            shaped.setIngredient('D', Material.DIAMOND);
            shaped.setIngredient('A', Material.AMETHYST_SHARD);
            shaped.setIngredient('E', Material.ENDER_EYE);
            shaped.setIngredient('T', RecipeChoice.predicateChoice(
                    item -> item != null && item.getType() == Material.TOTEM_OF_UNDYING && !isDreamer(item),
                    ItemStack.of(Material.TOTEM_OF_UNDYING)
            ));
            Bukkit.addRecipe(shaped);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.discoverRecipe(recipe);
        }
    }

    static void discover(Player player) {
        if (recipe != null) {
            player.discoverRecipe(recipe);
        }
    }

    static ItemStack create() {
        ItemStack item = ItemStack.of(Material.TOTEM_OF_UNDYING);
        item.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
        item.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Tótem Dreamer", ItemRarity.EPIC.color())
                .decoration(TextDecoration.ITALIC, false));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Component.text("Popea en tu inventario sin necesidad de tenerlo en la segunda mano.", NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("ADVERTENCIA: Se prioriza el tótem que tengas en tu mano principal o secundaria antes que este tótem", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
        )));
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(mark, PersistentDataType.BOOLEAN, true));
        return item;
    }

    static boolean isDreamer(ItemStack item) {
        if (mark == null || item == null || item.getType() != Material.TOTEM_OF_UNDYING || item.isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(mark, PersistentDataType.BOOLEAN);
    }

    static boolean isAnyTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING && !item.isEmpty();
    }
}
