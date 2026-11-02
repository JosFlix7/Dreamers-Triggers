package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Enderbag {
    static final Key MODEL = Key.key("dreamers", "enderbag");

    private static NamespacedKey recipe;
    private static NamespacedKey sackItems;

    private Enderbag() {
    }

    static void enable(JavaPlugin plugin) {
        recipe = new NamespacedKey(plugin, "enderbag");
        sackItems = new NamespacedKey(plugin, "bundle_sack_items");
        Bukkit.removeRecipe(recipe);
        ShapedRecipe shaped = new ShapedRecipe(recipe, item());
        shaped.shape("DED", "DSD", "DDD");
        shaped.setIngredient('D', Material.DIAMOND);
        shaped.setIngredient('E', Material.ENDER_CHEST);
        shaped.setIngredient('S', emptySack());
        Bukkit.addRecipe(shaped);
        plugin.getServer().getPluginManager().registerEvents(new CraftListener(), plugin);
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

    private static RecipeChoice emptySack() {
        return RecipeChoice.predicateChoice(Enderbag::accepts, ItemStack.of(Material.BUNDLE));
    }

    private static boolean accepts(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !Tag.ITEMS_BUNDLES.isTagged(stack.getType()) || is(stack)) {
            return false;
        }
        if (stack.getItemMeta() instanceof BundleMeta bundle && bundle.hasItems()) {
            return false;
        }
        if (sackItems == null) {
            return true;
        }
        byte[] stored = stack.getPersistentDataContainer().get(sackItems, PersistentDataType.BYTE_ARRAY);
        return stored == null || stored.length == 0;
    }

    private static boolean isEnderbagRecipe(Recipe crafted) {
        return recipe != null && crafted instanceof ShapedRecipe shaped && recipe.equals(shaped.getKey());
    }

    private static final class CraftListener implements Listener {
        @EventHandler
        public void onPrepare(PrepareItemCraftEvent event) {
            if (!isEnderbagRecipe(event.getRecipe())) {
                return;
            }
            event.getInventory().setResult(matrixAccepts(event.getInventory().getMatrix()) ? item() : null);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onCraft(CraftItemEvent event) {
            if (!isEnderbagRecipe(event.getRecipe())) {
                return;
            }
            if (!matrixAccepts(event.getInventory().getMatrix())) {
                event.setCancelled(true);
                return;
            }
            ItemStack result = item();
            event.setCurrentItem(result);
            event.getInventory().setResult(result);
        }

        private static boolean matrixAccepts(ItemStack[] matrix) {
            if (matrix == null) {
                return false;
            }
            for (ItemStack stack : matrix) {
                if (accepts(stack)) {
                    return true;
                }
            }
            return false;
        }
    }
}
