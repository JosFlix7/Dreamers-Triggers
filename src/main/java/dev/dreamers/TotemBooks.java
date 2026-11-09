package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

final class TotemBooks implements Listener {
    private static final NamespacedKey NUTRI = new NamespacedKey("dreamers", "nutritotem");
    private static final NamespacedKey FERREO = new NamespacedKey("dreamers", "totem_ferreo");
    private static final NamespacedKey YUNQUE = new NamespacedKey("dreamers", "totem_yunque");
    private static final NamespacedKey UNDEAD = new NamespacedKey("dreamers", "totem_of_undeads");
    private static final NamespacedKey DREAMER = new NamespacedKey("dreamers", "dreamers_totem");

    private final JavaPlugin plugin;

    TotemBooks(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onLoot(LootGenerateEvent event) {
        List<ItemStack> next = new ArrayList<>();
        boolean changed = false;
        for (ItemStack stack : event.getLoot()) {
            List<ItemStack> totems = expand(stack);
            if (totems == null) {
                next.add(stack);
                continue;
            }
            changed = true;
            next.addAll(totems);
        }
        if (changed) {
            event.setLoot(next);
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Inventory opened = event.getInventory();
        replaceInventory(opened);
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory carried = player.getInventory();
        if (opened != carried) {
            replaceInventory(carried);
        }
        replaceCursor(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            replaceInventory(opened);
            if (opened != carried) {
                replaceInventory(carried);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        replaceInventory(event.getPlayer().getInventory());
        replaceCursor(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack currentTotem = fromBook(current);
        ItemStack cursorTotem = fromBook(cursor);
        if (currentTotem == null && cursorTotem == null) {
            return;
        }
        event.setCancelled(true);
        if (currentTotem != null && event.getClickedInventory() != null) {
            int amount = Math.max(1, current.getAmount());
            if (event.isShiftClick()) {
                event.getClickedInventory().setItem(event.getSlot(), null);
                placeCopies(player, currentTotem, amount);
            } else if (cursorTotem != null) {
                event.getClickedInventory().setItem(event.getSlot(), one(cursorTotem));
                player.setItemOnCursor(one(currentTotem));
                placeCopies(player, currentTotem, amount - 1);
                placeCopies(player, cursorTotem, Math.max(1, cursor.getAmount()) - 1);
            } else if (cursor == null || cursor.getType().isAir()) {
                event.getClickedInventory().setItem(event.getSlot(), null);
                player.setItemOnCursor(one(currentTotem));
                placeCopies(player, currentTotem, amount - 1);
            } else {
                event.getClickedInventory().setItem(event.getSlot(), cursor.clone());
                player.setItemOnCursor(one(currentTotem));
                placeCopies(player, currentTotem, amount - 1);
            }
            return;
        }
        int amount = Math.max(1, cursor.getAmount());
        player.setItemOnCursor(one(cursorTotem));
        placeCopies(player, cursorTotem, amount - 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        ItemStack placed = event.getCursor();
        ItemStack totem = fromBook(placed);
        if (totem == null) {
            return;
        }
        int amount = Math.max(1, placed.getAmount());
        event.setCursor(one(totem));
        if (amount > 1 && event.getWhoClicked() instanceof Player player) {
            placeCopies(player, totem, amount - 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !plugin.isEnabled()) {
            return;
        }
        replaceCursor(player);
        replaceInventory(player.getInventory());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            replaceCursor(player);
            replaceInventory(player.getInventory());
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        List<ItemStack> totems = expand(event.getItem());
        if (totems == null) {
            return;
        }
        event.setItem(totems.get(0));
        giveRest(event.getDestination(), totems, locationOf(event.getDestination()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        List<ItemStack> totems = expand(event.getItem().getItemStack());
        if (totems == null) {
            return;
        }
        event.getItem().setItemStack(totems.get(0));
        for (int index = 1; index < totems.size(); index++) {
            player.getWorld().dropItemNaturally(player.getLocation(), totems.get(index));
        }
    }

    private static void replaceInventory(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            List<ItemStack> totems = expand(stack);
            if (totems == null) {
                continue;
            }
            inventory.setItem(slot, totems.get(0));
            giveRest(inventory, totems, locationOf(inventory));
        }
    }

    private static void replaceCursor(Player player) {
        List<ItemStack> totems = expand(player.getItemOnCursor());
        if (totems == null) {
            return;
        }
        player.setItemOnCursor(totems.get(0));
        giveRest(player.getInventory(), totems, player.getLocation());
    }

    private static void giveRest(Inventory inventory, List<ItemStack> totems, Location location) {
        for (int index = 1; index < totems.size(); index++) {
            place(inventory, totems.get(index), location);
        }
    }

    private static void placeCopies(Player player, ItemStack totem, int amount) {
        for (int index = 0; index < amount; index++) {
            place(player.getInventory(), one(totem), player.getLocation());
        }
    }

    private static void place(Inventory inventory, ItemStack stack, Location location) {
        Map<Integer, ItemStack> leftover = inventory.addItem(stack);
        if (location == null || location.getWorld() == null) {
            return;
        }
        for (ItemStack extra : leftover.values()) {
            location.getWorld().dropItemNaturally(location, extra);
        }
    }

    private static ItemStack one(ItemStack totem) {
        ItemStack copy = totem.clone();
        copy.setAmount(1);
        return copy;
    }

    private static Location locationOf(Inventory inventory) {
        if (inventory.getHolder(false) instanceof org.bukkit.block.BlockState state) {
            return state.getLocation();
        }
        if (inventory.getHolder(false) instanceof Player player) {
            return player.getLocation();
        }
        return null;
    }

    private static List<ItemStack> expand(ItemStack stack) {
        ItemStack totem = fromBook(stack);
        if (totem == null) {
            return null;
        }
        int amount = Math.max(1, stack.getAmount());
        List<ItemStack> copies = new ArrayList<>(amount);
        for (int index = 0; index < amount; index++) {
            copies.add(totem.clone());
        }
        return copies;
    }

    private static ItemStack fromBook(ItemStack stack) {
        if (stack == null || stack.getType() != Material.ENCHANTED_BOOK) {
            return null;
        }
        ItemEnchantments stored = stack.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored == null) {
            return null;
        }
        int best = Integer.MAX_VALUE;
        for (Map.Entry<Enchantment, Integer> entry : stored.enchantments().entrySet()) {
            Integer level = entry.getValue();
            if (level == null || level <= 0) {
                continue;
            }
            int rank = rank(entry.getKey().getKey());
            if (rank < best) {
                best = rank;
            }
        }
        return totem(best);
    }

    private static int rank(NamespacedKey key) {
        if (NUTRI.equals(key)) {
            return 0;
        }
        if (FERREO.equals(key)) {
            return 1;
        }
        if (YUNQUE.equals(key)) {
            return 2;
        }
        if (UNDEAD.equals(key)) {
            return 3;
        }
        if (DREAMER.equals(key)) {
            return 4;
        }
        return Integer.MAX_VALUE;
    }

    private static ItemStack totem(int rank) {
        return switch (rank) {
            case 0 -> DreamerTotem.nutri();
            case 1 -> DreamerTotem.ferreo();
            case 2 -> DreamerTotem.yunque();
            case 3 -> DreamerTotem.undead();
            case 4 -> DreamerTotem.dreamer();
            default -> null;
        };
    }
}
