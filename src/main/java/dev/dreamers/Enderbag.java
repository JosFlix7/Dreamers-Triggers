package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Enderbag {
    static final Key MODEL = Key.key("dreamers", "enderbag");
    private static final Component NAME = NameColor.named("Enderbag", ItemRarity.EPIC.color());

    private static JavaPlugin plugin;
    private static NamespacedKey recipe;
    private static NamespacedKey sackItems;
    private static final Set<UUID> opening = new HashSet<>();
    private static final Set<UUID> viewing = new HashSet<>();
    private static final Map<UUID, Pending> pending = new HashMap<>();

    private Enderbag() {
    }

    static void enable(JavaPlugin plugin) {
        Enderbag.plugin = plugin;
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
        if (player.getOpenInventory().getTopInventory().getType() != InventoryType.ENDER_CHEST) {
            return;
        }
        viewing.add(player.getUniqueId());
        playBundle(player, Sound.ITEM_BUNDLE_DROP_CONTENTS);
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
            Player player = event.getPlayer();
            silencePlace(player);
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
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
            if (plugin == null || !plugin.isEnabled()) {
                return;
            }
            UUID id = player.getUniqueId();
            if (opening.contains(id) || viewing.contains(id)) {
                return;
            }
            int slot = player.getInventory().getHeldItemSlot();
            opening.add(id);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                opening.remove(id);
                if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                    return;
                }
                if (player.getInventory().getHeldItemSlot() != slot) {
                    return;
                }
                if (!current(player.getInventory().getItem(slot)) || viewing.contains(id)) {
                    return;
                }
                open(player);
            });
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChestClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player player) || !bagOpen(player)) {
                return;
            }
            arm(player, triedToInsert(event));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChestDrag(InventoryDragEvent event) {
            if (!(event.getWhoClicked() instanceof Player player) || !bagOpen(player)) {
                return;
            }
            arm(player, triedToInsert(event));
        }

        @EventHandler
        public void onChestClose(InventoryCloseEvent event) {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            if (!viewing.remove(player.getUniqueId())) {
                return;
            }
            pending.remove(player.getUniqueId());
            if (event.getInventory().getType() == InventoryType.ENDER_CHEST && plugin != null && plugin.isEnabled()) {
                playBundle(player, Sound.ITEM_BUNDLE_REMOVE_ONE);
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            opening.remove(id);
            viewing.remove(id);
            pending.remove(id);
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

    private static boolean bagOpen(Player player) {
        return viewing.contains(player.getUniqueId())
                && player.getOpenInventory().getTopInventory().getType() == InventoryType.ENDER_CHEST;
    }

    private static void silencePlace(Player player) {
        player.stopSound(Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS);
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.stopSound(Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS);
            }
        });
    }

    private static void playBundle(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 0.8f, 1f);
    }

    private static void arm(Player player, boolean attemptInsert) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        Pending current = pending.get(id);
        if (current != null) {
            current.attemptInsert |= attemptInsert;
            return;
        }
        Pending created = new Pending(copy(player.getEnderChest()), attemptInsert);
        pending.put(id, created);
        plugin.getServer().getScheduler().runTask(plugin, () -> flush(player, id, created));
    }

    private static void flush(Player player, UUID id, Pending change) {
        if (pending.get(id) != change) {
            return;
        }
        pending.remove(id);
        if (!player.isOnline() || !bagOpen(player)) {
            return;
        }
        playChange(player, change.before, player.getEnderChest().getContents(), change.attemptInsert);
    }

    private static void playChange(Player player, ItemStack[] before, ItemStack[] after, boolean attemptInsert) {
        int added = 0;
        int removed = 0;
        int length = Math.max(before.length, after.length);
        for (int index = 0; index < length; index++) {
            ItemStack oldItem = index < before.length ? before[index] : null;
            ItemStack newItem = index < after.length ? after[index] : null;
            int oldAmount = amount(oldItem);
            int newAmount = amount(newItem);
            if (oldAmount > 0 && newAmount > 0 && oldItem.isSimilar(newItem)) {
                if (newAmount > oldAmount) {
                    added += newAmount - oldAmount;
                } else {
                    removed += oldAmount - newAmount;
                }
            } else {
                removed += oldAmount;
                added += newAmount;
            }
        }
        if (added > 0) {
            playBundle(player, Sound.ITEM_BUNDLE_INSERT);
        } else if (removed > 0) {
            playBundle(player, Sound.ITEM_BUNDLE_REMOVE_ONE);
        } else if (attemptInsert) {
            playBundle(player, Sound.ITEM_BUNDLE_INSERT_FAIL);
        }
    }

    private static boolean triedToInsert(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && clicked != null && clicked != top) {
            return amount(event.getCurrentItem()) > 0;
        }
        if (clicked != top || amount(event.getCursor()) <= 0) {
            return false;
        }
        ClickType click = event.getClick();
        return click == ClickType.LEFT || click == ClickType.RIGHT;
    }

    private static boolean triedToInsert(InventoryDragEvent event) {
        if (amount(event.getOldCursor()) <= 0) {
            return false;
        }
        int top = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < top) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack[] copy(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        ItemStack[] copied = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            copied[index] = item == null || item.getType().isAir() ? null : item.clone();
        }
        return copied;
    }

    private static int amount(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? 0 : stack.getAmount();
    }

    private static final class Pending {
        private final ItemStack[] before;
        private boolean attemptInsert;

        private Pending(ItemStack[] before, boolean attemptInsert) {
            this.before = before;
            this.attemptInsert = attemptInsert;
        }
    }
}
