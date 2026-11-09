package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import io.papermc.paper.datacomponent.item.BundleContents;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Enderbag {
    static final Key MODEL = Key.key("dreamers", "enderbag");
    private static final Component NAME = NameColor.named("Enderbag", ItemRarity.EPIC.color());

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
            replaceInventory(player.getInventory());
            replaceCursor(player);
        }
    }

    static void discover(Player player) {
        if (recipe != null) {
            player.discoverRecipe(recipe);
        }
    }

    static boolean is(ItemStack stack) {
        return current(stack) || legacy(stack);
    }

    private static boolean current(ItemStack stack) {
        return stack != null
                && stack.getType() == Material.ENDER_CHEST
                && MODEL.equals(stack.getData(DataComponentTypes.ITEM_MODEL));
    }

    private static boolean legacy(ItemStack stack) {
        return stack != null
                && stack.getType() == Material.BUNDLE
                && MODEL.equals(stack.getData(DataComponentTypes.ITEM_MODEL));
    }

    static void open(Player player) {
        player.openInventory(player.getEnderChest());
    }

    static ItemStack item() {
        ItemStack stack = ItemStack.of(Material.ENDER_CHEST);
        stack.setData(DataComponentTypes.CUSTOM_NAME, NAME);
        stack.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        return stack;
    }

    private static ItemStack migrate(ItemStack stack) {
        if (!legacy(stack)) {
            return null;
        }
        ItemStack next = item();
        next.setAmount(stack.getAmount());
        Component name = stack.getData(DataComponentTypes.CUSTOM_NAME);
        if (name != null) {
            next.setData(DataComponentTypes.CUSTOM_NAME, NameColor.paint(name, ItemRarity.EPIC.color()));
        }
        return next;
    }

    static boolean tint(ItemStack stack) {
        if (!current(stack)) {
            return false;
        }
        boolean changed = false;
        if (stack.getData(DataComponentTypes.RARITY) != ItemRarity.EPIC) {
            stack.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
            changed = true;
        }
        Component currentName = stack.getData(DataComponentTypes.CUSTOM_NAME);
        Component next = currentName == null ? NAME : NameColor.paint(currentName, ItemRarity.EPIC.color());
        if (!next.equals(currentName)) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, next);
            changed = true;
        }
        return changed;
    }

    private static RecipeChoice emptySack() {
        return RecipeChoice.predicateChoice(Enderbag::accepts, ItemStack.of(Material.BUNDLE));
    }

    private static boolean accepts(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !Tag.ITEMS_BUNDLES.isTagged(stack.getType()) || is(stack) || legacy(stack)) {
            return false;
        }
        if (bundleHasItems(stack)) {
            return false;
        }
        if (sackItems == null) {
            return true;
        }
        byte[] stored = stack.getPersistentDataContainer().get(sackItems, PersistentDataType.BYTE_ARRAY);
        return stored == null || stored.length == 0;
    }

    private static boolean bundleHasItems(ItemStack stack) {
        BundleContents contents = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        return contents != null && !contents.contents().isEmpty();
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

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        @SuppressWarnings("deprecation")
        public void onUse(PlayerInteractEvent event) {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            if (!current(event.getItem())) {
                return;
            }
            event.setUseItemInHand(Event.Result.DENY);
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            Player player = event.getPlayer();
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                return;
            }
            Block clicked = event.getClickedBlock();
            BlockType clickedType = clicked == null ? null : clicked.getType().asBlockType();
            if (action == Action.RIGHT_CLICK_BLOCK
                    && clickedType != null
                    && clickedType.isInteractable()
                    && !player.isSneaking()) {
                return;
            }
            event.setUseInteractedBlock(Event.Result.DENY);
            open(player);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDispense(BlockDispenseEvent event) {
            if (current(event.getItem())) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            replaceInventory(event.getPlayer().getInventory());
            replaceCursor(event.getPlayer());
        }

        @EventHandler
        public void onOpen(InventoryOpenEvent event) {
            Inventory opened = event.getInventory();
            replaceInventory(opened);
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            if (opened != player.getInventory()) {
                replaceInventory(player.getInventory());
            }
            replaceCursor(player);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            ItemStack current = migrate(event.getCurrentItem());
            if (current != null) {
                event.setCurrentItem(current);
            }
            ItemStack cursor = migrate(event.getCursor());
            if (cursor != null) {
                event.getView().setCursor(cursor);
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPickup(EntityPickupItemEvent event) {
            ItemStack next = migrate(event.getItem().getItemStack());
            if (next != null) {
                event.getItem().setItemStack(next);
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onMove(InventoryMoveItemEvent event) {
            ItemStack next = migrate(event.getItem());
            if (next != null) {
                event.setItem(next);
            }
        }
    }

    private static void replaceInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack next = migrate(inventory.getItem(slot));
            if (next != null) {
                inventory.setItem(slot, next);
            }
        }
    }

    private static void replaceCursor(Player player) {
        ItemStack next = migrate(player.getItemOnCursor());
        if (next != null) {
            player.setItemOnCursor(next);
        }
    }
}
