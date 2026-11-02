package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Enderbag {
    static final Key MODEL = Key.key("dreamers", "enderbag");

    private static NamespacedKey recipe;
    private static NamespacedKey sackReady;

    private Enderbag() {
    }

    static void enable(JavaPlugin plugin) {
        recipe = new NamespacedKey(plugin, "enderbag");
        sackReady = new NamespacedKey(plugin, "bundle_sack");
        Bukkit.removeRecipe(recipe);
        ShapedRecipe shaped = new ShapedRecipe(recipe, item());
        shaped.shape("E", "S");
        shaped.setIngredient('E', Material.ENDER_CHEST);
        shaped.setIngredient('S', plainSack());
        Bukkit.addRecipe(shaped);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.discoverRecipe(recipe);
        }
    }

    static void discover(Player player) {
        if (recipe != null) {
            player.discoverRecipe(recipe);
        }
    }

    static boolean is(ItemStack stack) {
        return stack != null
                && stack.getType() == Material.BUNDLE
                && MODEL.equals(stack.getData(DataComponentTypes.ITEM_MODEL));
    }

    static void open(Player player) {
        player.openInventory(player.getEnderChest());
    }

    private static ItemStack item() {
        ItemStack stack = ItemStack.of(Material.BUNDLE);
        stack.setData(
                DataComponentTypes.CUSTOM_NAME,
                Component.text("Enderbag", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)
        );
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.BUNDLE_CONTENTS)
        );
        return stack;
    }

    private static RecipeChoice plainSack() {
        Predicate<ItemStack> plain = stack -> {
            if (stack == null || stack.getType() != Material.BUNDLE || is(stack)) {
                return false;
            }
            if (sackReady != null && stack.getPersistentDataContainer().has(sackReady, PersistentDataType.BYTE)) {
                return false;
            }
            return !(stack.getItemMeta() instanceof BundleMeta bundle) || !bundle.hasItems();
        };
        return RecipeChoice.predicateChoice(plain, ItemStack.of(Material.BUNDLE));
    }
}
