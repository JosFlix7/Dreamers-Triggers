package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BundleSack implements Listener {
    private static final Component OPEN_HINT = Component.text(
            "Abre el saco con clic derecho en la mano principal.",
            NamedTextColor.YELLOW
    );
    private static final Component REJECTED = Component.text(
            "No puedes meter sacos ni cajas de shulker.",
            NamedTextColor.RED
    );
    private static final Component OVERFLOW = Component.text(
            "Lo que no cupo en el saco se soltó al suelo.",
            NamedTextColor.YELLOW
    );

    private final JavaPlugin plugin;
    private final NamespacedKey readyKey;
    private final NamespacedKey idKey;
    private final NamespacedKey itemsKey;
    private final NamespacedKey slotsKey;
    private final Map<UUID, Open> open = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();

    BundleSack(JavaPlugin plugin) {
        this.plugin = plugin;
        readyKey = new NamespacedKey(plugin, "bundle_sack");
        idKey = new NamespacedKey(plugin, "bundle_sack_id");
        itemsKey = new NamespacedKey(plugin, "bundle_sack_items");
        slotsKey = new NamespacedKey(plugin, "bundle_sack_slots");
    }

    void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Open sack = open.remove(player.getUniqueId());
            if (sack == null) {
                continue;
            }
            save(player, sack, true);
            if (player.getOpenInventory().getTopInventory().getHolder(false) == sack.holder) {
                player.closeInventory();
            }
        }
        opening.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    @SuppressWarnings("deprecation")
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        ItemStack used = event.getItem();
        if (!isBundle(used) || event.useItemInHand() == Event.Result.DENY) {
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
        if (opening.contains(player.getUniqueId()) || open.containsKey(player.getUniqueId())) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder) {
            return;
        }

        int slot = player.getInventory().getHeldItemSlot();
        opening.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            opening.remove(player.getUniqueId());
            if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            if (player.getInventory().getHeldItemSlot() != slot) {
                return;
            }
            ItemStack hand = player.getInventory().getItem(slot);
            if (!isBundle(hand) || player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder) {
                return;
            }
            Prepared prepared = prepare(hand);
            player.getInventory().setItem(slot, prepared.stack);
            spill(player, prepared.leftover);
            show(player, slot, prepared.id);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (vanillaBundleClick(event)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player
                    && !(player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder)) {
                player.sendActionBar(OPEN_HINT);
            }
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Open sack = open.get(player.getUniqueId());
        if (sack == null || event.getView().getTopInventory().getHolder(false) != sack.holder) {
            return;
        }
        boolean moving = movesOpenSack(event, sack);
        boolean rejected = isForbidden(entering(event));
        if (!moving && !rejected) {
            return;
        }
        event.setCancelled(true);
        if (rejected && !moving) {
            player.sendActionBar(REJECTED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Open sack = open.get(player.getUniqueId());
        if (sack == null || event.getView().getTopInventory().getHolder(false) != sack.holder) {
            return;
        }
        if (matches(event.getOldCursor(), sack.id)) {
            event.setCancelled(true);
            return;
        }
        if (!isForbidden(event.getOldCursor())) {
            return;
        }
        int top = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < top) {
                event.setCancelled(true);
                player.sendActionBar(REJECTED);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterClick(InventoryClickEvent event) {
        remember(event.getWhoClicked() instanceof Player player ? player : null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDrag(InventoryDragEvent event) {
        remember(event.getWhoClicked() instanceof Player player ? player : null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Open sack = open.get(event.getPlayer().getUniqueId());
        if (sack != null && matches(event.getMainHandItem(), sack.id)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Open sack = open.get(event.getPlayer().getUniqueId());
        if (sack != null && matches(event.getItemDrop().getItemStack(), sack.id)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Open sack = open.get(player.getUniqueId());
        if (sack == null || event.getInventory().getHolder(false) != sack.holder) {
            return;
        }
        open.remove(player.getUniqueId());
        save(player, sack, true);
        if (plugin.isEnabled()) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, SoundCategory.PLAYERS, 0.5f, 1f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Open sack = open.get(player.getUniqueId());
        if (sack == null) {
            return;
        }
        save(player, sack, false);
        ItemStack saved = locate(player, sack.id);
        if (saved == null) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        for (int index = 0; index < drops.size(); index++) {
            if (matches(drops.get(index), sack.id)) {
                drops.set(index, saved.clone());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshVisible(event.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || open.containsKey(player.getUniqueId())) {
            return;
        }
        refreshVisible(event.getInventory());
        refreshVisible(player.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refreshVisible(player.getInventory());
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerDrop(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Item entity)) {
            return;
        }
        ItemStack stack = entity.getItemStack();
        if (isBundle(stack) && stack.getPersistentDataContainer().has(readyKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemRemove(EntityRemoveEvent event) {
        EntityRemoveEvent.Cause cause = event.getCause();
        if (cause != EntityRemoveEvent.Cause.DEATH
                && cause != EntityRemoveEvent.Cause.EXPLODE
                && cause != EntityRemoveEvent.Cause.OUT_OF_WORLD
                && cause != EntityRemoveEvent.Cause.HIT
                && cause != EntityRemoveEvent.Cause.DISCARD) {
            return;
        }
        if (!(event.getEntity() instanceof Item entity)) {
            return;
        }
        ItemStack stack = entity.getItemStack();
        if (!isBundle(stack) || !stack.getPersistentDataContainer().has(readyKey, PersistentDataType.BYTE)) {
            return;
        }
        ItemStack[] stored = new ItemStack[9];
        List<ItemStack> loose = new ArrayList<>();
        read(stack, stored, loose);
        for (ItemStack content : stored) {
            if (content != null && !content.getType().isAir()) {
                loose.add(content);
            }
        }
        Location location = entity.getLocation();
        if (cause == EntityRemoveEvent.Cause.OUT_OF_WORLD) {
            location.setY(Math.max(location.getY(), entity.getWorld().getMinHeight()));
        }
        spill(location, loose);
    }

    private void refreshVisible(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && updatePreview(stack)) {
                inventory.setItem(slot, stack);
            }
        }
    }

    private boolean updatePreview(ItemStack stack) {
        if (!isBundle(stack) || !stack.getPersistentDataContainer().has(readyKey, PersistentDataType.BYTE)) {
            return false;
        }
        ItemStack[] stored = new ItemStack[9];
        read(stack, stored, new ArrayList<>());
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack content : stored) {
            if (content != null && !content.getType().isAir()) {
                items.add(content);
            }
        }
        if (previewMatches(stack, items)) {
            return false;
        }
        applyPreview(stack, items);
        return true;
    }

    private void applyPreview(ItemStack stack, List<ItemStack> items) {
        stack.unsetData(DataComponentTypes.CONTAINER);
        if (items.isEmpty()) {
            stack.unsetData(DataComponentTypes.LORE);
            revealBundleTooltip(stack);
            return;
        }
        ItemLore.Builder lore = ItemLore.lore();
        for (ItemStack item : items) {
            lore.addLine(loreLine(item));
        }
        stack.setData(DataComponentTypes.LORE, lore);
        hideBundleTooltip(stack);
    }

    private boolean previewMatches(ItemStack stack, List<ItemStack> items) {
        boolean hidden = bundleTooltipHidden(stack);
        boolean hasContainer = stack.getData(DataComponentTypes.CONTAINER) != null;
        ItemLore lore = stack.getData(DataComponentTypes.LORE);
        if (items.isEmpty()) {
            return !hasContainer && lore == null && !hidden;
        }
        if (!hidden || hasContainer || lore == null || lore.lines().size() != items.size()) {
            return false;
        }
        List<Component> lines = lore.lines();
        for (int index = 0; index < items.size(); index++) {
            if (!plain(lines.get(index)).equals(plain(loreLine(items.get(index))))) {
                return false;
            }
        }
        return true;
    }

    private static Component loreLine(ItemStack item) {
        Component line = item.displayName();
        if (item.getAmount() > 1) {
            line = line.append(Component.text(" x" + item.getAmount()));
        }
        return line.colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void hideBundleTooltip(ItemStack stack) {
        TooltipDisplay current = stack.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        Set<DataComponentType> hidden = new HashSet<>();
        boolean hideAll = false;
        if (current != null) {
            hideAll = current.hideTooltip();
            hidden.addAll(current.hiddenComponents());
        }
        hidden.add(DataComponentTypes.BUNDLE_CONTENTS);
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(hideAll).hiddenComponents(hidden)
        );
    }

    private void revealBundleTooltip(ItemStack stack) {
        TooltipDisplay current = stack.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        if (current == null || !current.hiddenComponents().contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            return;
        }
        Set<DataComponentType> hidden = new HashSet<>(current.hiddenComponents());
        hidden.remove(DataComponentTypes.BUNDLE_CONTENTS);
        if (hidden.isEmpty() && !current.hideTooltip()) {
            stack.unsetData(DataComponentTypes.TOOLTIP_DISPLAY);
            return;
        }
        stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(current.hideTooltip()).hiddenComponents(hidden)
        );
    }

    private static boolean bundleTooltipHidden(ItemStack stack) {
        TooltipDisplay display = stack.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        return display != null && display.hiddenComponents().contains(DataComponentTypes.BUNDLE_CONTENTS);
    }

    private void show(Player player, int slot, UUID id) {
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder) {
            return;
        }
        ItemStack hand = player.getInventory().getItem(slot);
        if (!matches(hand, id)) {
            return;
        }
        ItemStack[] contents = new ItemStack[9];
        read(hand, contents, new ArrayList<>());
        SackHolder holder = new SackHolder(id);
        Inventory inventory = Bukkit.createInventory(holder, 9, hand.displayName());
        holder.inventory = inventory;
        for (int index = 0; index < contents.length; index++) {
            if (contents[index] != null) {
                inventory.setItem(index, contents[index]);
            }
        }
        open.put(player.getUniqueId(), new Open(id, slot, holder));
        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
            open.remove(player.getUniqueId());
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, SoundCategory.PLAYERS, 0.5f, 1f);
    }

    private void remember(Player player) {
        if (player == null) {
            return;
        }
        Open sack = open.get(player.getUniqueId());
        if (sack == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (open.get(player.getUniqueId()) == sack) {
                save(player, sack, false);
            }
        });
    }

    private void save(Player player, Open sack, boolean dropIfMissing) {
        ItemStack[] contents = new ItemStack[9];
        List<ItemStack> rejected = new ArrayList<>();
        Inventory inventory = sack.holder.inventory;
        for (int index = 0; index < 9; index++) {
            ItemStack item = inventory.getItem(index);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (isForbidden(item)) {
                inventory.setItem(index, null);
                rejected.add(item.clone());
                continue;
            }
            contents[index] = item;
        }
        spill(player, rejected);
        if (store(player, sack, contents) || !dropIfMissing) {
            return;
        }
        List<ItemStack> loose = new ArrayList<>();
        for (ItemStack content : contents) {
            if (content != null && !content.getType().isAir()) {
                loose.add(content.clone());
            }
        }
        spill(player, loose);
    }

    private ItemStack locate(Player player, UUID id) {
        PlayerInventory inventory = player.getInventory();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matches(stack, id)) {
                return stack;
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (matches(offhand, id)) {
            return offhand;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (matches(cursor, id)) {
            return cursor;
        }
        return null;
    }

    private boolean store(Player player, Open sack, ItemStack[] contents) {
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItem(sack.slot);
        if (matches(hand, sack.id)) {
            inventory.setItem(sack.slot, written(hand, sack.id, contents));
            return true;
        }
        ItemStack[] stored = inventory.getStorageContents();
        for (int index = 0; index < stored.length; index++) {
            if (!matches(stored[index], sack.id)) {
                continue;
            }
            stored[index] = written(stored[index], sack.id, contents);
            inventory.setStorageContents(stored);
            return true;
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (matches(offhand, sack.id)) {
            inventory.setItemInOffHand(written(offhand, sack.id, contents));
            return true;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (matches(cursor, sack.id)) {
            player.setItemOnCursor(written(cursor, sack.id, contents));
            return true;
        }
        return false;
    }

    private Prepared prepare(ItemStack source) {
        ItemStack stack = source.clone();
        UUID id = id(stack);
        if (id == null) {
            id = UUID.randomUUID();
        }
        ItemStack[] contents = new ItemStack[9];
        List<ItemStack> leftover = new ArrayList<>();
        if (!stack.getPersistentDataContainer().has(readyKey, PersistentDataType.BYTE)
                && stack.getItemMeta() instanceof BundleMeta meta
                && meta.hasItems()) {
            pack(meta.getItems(), contents, leftover);
        } else {
            read(stack, contents, leftover);
        }
        if (!write(stack, id, contents)) {
            return new Prepared(source, id, List.of());
        }
        return new Prepared(stack, id, leftover);
    }

    private void pack(List<ItemStack> items, ItemStack[] contents, List<ItemStack> leftover) {
        Inventory temp = Bukkit.createInventory(null, 9);
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack copy = item.clone();
            if (isForbidden(copy)) {
                leftover.add(copy);
                continue;
            }
            leftover.addAll(temp.addItem(copy).values());
        }
        System.arraycopy(temp.getContents(), 0, contents, 0, 9);
    }

    private void read(ItemStack stack, ItemStack[] contents, List<ItemStack> rejected) {
        byte[] data = stack.getPersistentDataContainer().get(itemsKey, PersistentDataType.BYTE_ARRAY);
        if (data == null || data.length == 0) {
            return;
        }
        ItemStack[] items;
        try {
            items = ItemStack.deserializeItemsFromBytes(data);
        } catch (RuntimeException ex) {
            return;
        }
        byte[] slots = stack.getPersistentDataContainer().get(slotsKey, PersistentDataType.BYTE_ARRAY);
        for (int index = 0; index < items.length; index++) {
            ItemStack item = items[index];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (isForbidden(item)) {
                rejected.add(item.clone());
                continue;
            }
            int slot = slots != null && index < slots.length ? slots[index] & 0xFF : index;
            if (slot >= 0 && slot < contents.length && contents[slot] == null) {
                contents[slot] = item.clone();
            } else {
                rejected.add(item.clone());
            }
        }
    }

    private boolean write(ItemStack stack, UUID id, ItemStack[] contents) {
        List<ItemStack> items = new ArrayList<>();
        byte[] slots = new byte[9];
        int count = 0;
        for (int slot = 0; slot < contents.length && slot < 9; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir() || isForbidden(item)) {
                continue;
            }
            items.add(item.clone());
            slots[count] = (byte) slot;
            count++;
        }
        byte[] storedSlots = new byte[count];
        System.arraycopy(slots, 0, storedSlots, 0, count);
        boolean stored = stack.editMeta(BundleMeta.class, meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(readyKey, PersistentDataType.BYTE, (byte) 1);
            data.set(idKey, PersistentDataType.STRING, id.toString());
            if (items.isEmpty()) {
                data.remove(itemsKey);
                data.remove(slotsKey);
            } else {
                data.set(itemsKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(items));
                data.set(slotsKey, PersistentDataType.BYTE_ARRAY, storedSlots);
            }
            meta.setItems(null);
        });
        if (stored) {
            applyPreview(stack, items);
        }
        return stored;
    }

    private ItemStack written(ItemStack stack, UUID id, ItemStack[] contents) {
        ItemStack updated = stack.clone();
        write(updated, id, contents);
        return updated;
    }

    private UUID id(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        String raw = stack.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean matches(ItemStack stack, UUID id) {
        return id != null && isBundle(stack) && id.equals(id(stack));
    }

    private boolean movesOpenSack(InventoryClickEvent event, Open sack) {
        if (matches(event.getCurrentItem(), sack.id) || matches(event.getCursor(), sack.id)) {
            return true;
        }
        return event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == sack.slot;
    }

    private ItemStack entering(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (event.getClickedInventory() == top) {
                return null;
            }
            return event.getCurrentItem();
        }
        if (event.getClickedInventory() != top) {
            return null;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0 && event.getHotbarButton() < 9) {
            return event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return event.getWhoClicked().getInventory().getItemInOffHand();
        }
        return event.getCursor();
    }

    private boolean vanillaBundleClick(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        if (action == InventoryAction.PICKUP_FROM_BUNDLE
                || action == InventoryAction.PICKUP_ALL_INTO_BUNDLE
                || action == InventoryAction.PICKUP_SOME_INTO_BUNDLE
                || action == InventoryAction.PLACE_FROM_BUNDLE
                || action == InventoryAction.PLACE_ALL_INTO_BUNDLE
                || action == InventoryAction.PLACE_SOME_INTO_BUNDLE) {
            return true;
        }
        if (isBundle(event.getCursor()) && action == InventoryAction.COLLECT_TO_CURSOR) {
            return true;
        }
        return isBundle(event.getCurrentItem()) && event.getClick().isRightClick();
    }

    private static boolean isBundle(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && Tag.ITEMS_BUNDLES.isTagged(stack.getType());
    }

    private static boolean isForbidden(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return Tag.ITEMS_BUNDLES.isTagged(stack.getType()) || Tag.ITEMS_SHULKER_BOXES.isTagged(stack.getType());
    }

    private void spill(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        spill(player.getLocation(), items);
        player.sendActionBar(OVERFLOW);
    }

    private static void spill(Location location, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            location.getWorld().dropItemNaturally(location, item.clone());
        }
    }

    private static final class Open {
        private final UUID id;
        private final int slot;
        private final SackHolder holder;

        private Open(UUID id, int slot, SackHolder holder) {
            this.id = id;
            this.slot = slot;
            this.holder = holder;
        }
    }

    private static final class SackHolder implements InventoryHolder {
        private final UUID id;
        private Inventory inventory;

        private SackHolder(UUID id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Prepared(ItemStack stack, UUID id, List<ItemStack> leftover) {
    }
}
