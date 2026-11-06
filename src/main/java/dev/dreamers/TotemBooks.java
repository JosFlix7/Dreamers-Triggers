package dev.dreamers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
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
        replaceInventory(event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            replaceInventory(player.getInventory());
            replaceCursor(player);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                replaceInventory(event.getInventory());
                replaceInventory(player.getInventory());
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        replaceInventory(event.getPlayer().getInventory());
        replaceCursor(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        List<ItemStack> totems = expand(current);
        if (totems != null && event.getClickedInventory() != null) {
            event.getClickedInventory().setItem(event.getSlot(), totems.get(0));
            giveRest(event.getClickedInventory(), totems, event.getWhoClicked().getLocation());
            event.setCurrentItem(totems.get(0));
        }
        List<ItemStack> cursor = expand(event.getCursor());
        if (cursor != null && event.getWhoClicked() instanceof Player player) {
            event.getView().setCursor(cursor.get(0));
            giveRest(player.getInventory(), cursor, player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        ItemStack incoming = event.getCursor();
        if (incoming == null || incoming.getType().isAir()) {
            incoming = event.getCurrentItem();
        }
        List<ItemStack> totems = expand(incoming);
        if (totems == null) {
            return;
        }
        event.getView().setCursor(totems.get(0));
        if (event.getWhoClicked() instanceof Player player) {
            giveRest(player.getInventory(), totems, player.getLocation());
        }
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
            Map<Integer, ItemStack> leftover = inventory.addItem(totems.get(index));
            if (location == null || location.getWorld() == null) {
                continue;
            }
            for (ItemStack extra : leftover.values()) {
                location.getWorld().dropItemNaturally(location, extra);
            }
        }
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
        if (!(stack.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            return null;
        }
        if (has(meta, NUTRI)) {
            return DreamerTotem.nutri();
        }
        if (has(meta, FERREO)) {
            return DreamerTotem.ferreo();
        }
        if (has(meta, YUNQUE)) {
            return DreamerTotem.yunque();
        }
        if (has(meta, UNDEAD)) {
            return DreamerTotem.undead();
        }
        if (has(meta, DREAMER)) {
            return DreamerTotem.dreamer();
        }
        return null;
    }

    private static boolean has(EnchantmentStorageMeta meta, NamespacedKey key) {
        Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
        return enchantment != null && meta.hasStoredEnchant(enchantment);
    }
}
