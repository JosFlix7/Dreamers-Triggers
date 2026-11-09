package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.block.ShulkerBox;
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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
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
    private static final Component REJECTED_SACK = Component.text(
            "Solo puedes meter un saco si está vacío.",
            NamedTextColor.RED
    );
    private static final Component REJECTED_SHULKER = Component.text(
            "No puedes meter cajas de shulker.",
            NamedTextColor.RED
    );
    private static final Component OVERFLOW = Component.text(
            "Lo que no cupo en el saco se soltó al suelo.",
            NamedTextColor.YELLOW
    );
    private static final Component MEDIUM_NAME = tierName("Saco Mediano", ItemRarity.UNCOMMON);
    private static final Component LARGE_NAME = tierName("Saco grande", ItemRarity.RARE);
    private static final Component SHULKER_NAME = tierName("Saco Shulker", ItemRarity.EPIC);

    private final JavaPlugin plugin;
    private final NamespacedKey readyKey;
    private final NamespacedKey idKey;
    private final NamespacedKey itemsKey;
    private final NamespacedKey slotsKey;
    private final NamespacedKey sizeKey;
    private final NamespacedKey mediumKey;
    private final NamespacedKey largeKey;
    private final NamespacedKey shulkerKey;
    private final Map<UUID, Open> open = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();

    BundleSack(JavaPlugin plugin) {
        this.plugin = plugin;
        readyKey = new NamespacedKey(plugin, "bundle_sack");
        idKey = new NamespacedKey(plugin, "bundle_sack_id");
        itemsKey = new NamespacedKey(plugin, "bundle_sack_items");
        slotsKey = new NamespacedKey(plugin, "bundle_sack_slots");
        sizeKey = new NamespacedKey(plugin, "bundle_sack_size");
        mediumKey = new NamespacedKey(plugin, "sack_medium");
        largeKey = new NamespacedKey(plugin, "sack_large");
        shulkerKey = new NamespacedKey(plugin, "sack_shulker");
        registerSackRecipes();
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
            if (Enderbag.is(hand)) {
                Enderbag.open(player);
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (swapSackOntoSlot(event)) {
            return;
        }
        Open sack = open.get(player.getUniqueId());
        if (sack != null
                && event.getView().getTopInventory().getHolder(false) == sack.holder
                && chestInsteadOfBundle(event, player, sack)) {
            return;
        }
        if (vanillaBundleClick(event)) {
            event.setCancelled(true);
            if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder)) {
                player.sendActionBar(OPEN_HINT);
            }
            return;
        }
        if (sack == null || event.getView().getTopInventory().getHolder(false) != sack.holder) {
            return;
        }
        boolean moving = movesOpenSack(event, sack);
        ItemStack incoming = entering(event);
        boolean rejected = isForbidden(incoming);
        if (moving || rejected) {
            event.setCancelled(true);
            if (rejected && !moving) {
                player.sendActionBar(rejection(incoming));
                playBundle(player, Sound.ITEM_BUNDLE_INSERT_FAIL);
            }
            return;
        }
        sack.previous = copy(sack.holder.inventory);
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
        if (isForbidden(event.getOldCursor())) {
            int top = event.getView().getTopInventory().getSize();
            for (int raw : event.getRawSlots()) {
                if (raw < top) {
                    event.setCancelled(true);
                    player.sendActionBar(rejection(event.getOldCursor()));
                    playBundle(player, Sound.ITEM_BUNDLE_INSERT_FAIL);
                    return;
                }
            }
        }
        sack.previous = copy(sack.holder.inventory);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            playContentsChange(player);
            remember(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            playContentsChange(player);
            remember(player);
        }
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
            playBundle(player, Sound.ITEM_BUNDLE_REMOVE_ONE);
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
        Player player = event.getPlayer();
        Enderbag.discover(player);
        player.discoverRecipe(mediumKey);
        player.discoverRecipe(largeKey);
        player.discoverRecipe(shulkerKey);
        refreshCarried(player);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof ShapedRecipe shaped)) {
            return;
        }
        Upgrade upgrade = upgradeFor(shaped.getKey());
        if (upgrade == null) {
            return;
        }
        ItemStack source = sackIn(event.getInventory().getMatrix(), upgrade.from);
        event.getInventory().setResult(source == null ? null : upgrade(source, upgrade));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getRecipe() instanceof ShapedRecipe shaped)) {
            return;
        }
        Upgrade upgrade = upgradeFor(shaped.getKey());
        if (upgrade == null) {
            return;
        }
        ItemStack source = sackIn(event.getInventory().getMatrix(), upgrade.from);
        if (source == null) {
            event.setCancelled(true);
            return;
        }
        ItemStack result = upgrade(source, upgrade);
        event.setCurrentItem(result);
        event.getInventory().setResult(result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || open.containsKey(player.getUniqueId())) {
            return;
        }
        refreshVisible(event.getInventory());
        refreshCarried(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refreshCarried(player);
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
        ItemStack[] stored = new ItemStack[capacity(stack)];
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

    private void refreshCarried(Player player) {
        refreshVisible(player.getInventory());
        ItemStack cursor = player.getItemOnCursor();
        if (refreshStack(cursor)) {
            player.setItemOnCursor(cursor);
        }
    }

    private void refreshVisible(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
        if (stack != null && refreshStack(stack)) {
            inventory.setItem(slot, stack);
        }
        }
    }

    private boolean updatePreview(ItemStack stack) {
        if (!isBundle(stack) || Enderbag.is(stack)) {
            return false;
        }
        boolean changed = applyAppearance(stack);
        if (!stack.getPersistentDataContainer().has(readyKey, PersistentDataType.BYTE)) {
            return changed;
        }
        ItemStack[] stored = new ItemStack[capacity(stack)];
        read(stack, stored, new ArrayList<>());
        if (previewMatches(stack, stored)) {
            return changed;
        }
        applyPreview(stack, stored);
        return true;
    }

    private void applyPreview(ItemStack stack, ItemStack[] contents) {
        stack.unsetData(DataComponentTypes.LORE);
        if (!occupied(contents)) {
            stack.unsetData(DataComponentTypes.CONTAINER);
            revealBundleTooltip(stack);
            return;
        }
        stack.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(containerSlots(contents)));
        hideBundleTooltip(stack);
    }

    private static boolean occupied(ItemStack[] contents) {
        for (ItemStack content : contents) {
            if (content != null && !content.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private List<ItemStack> containerSlots(ItemStack[] contents) {
        List<ItemStack> slots = new ArrayList<>(contents.length);
        for (ItemStack content : contents) {
            slots.add(content == null || content.getType().isAir() ? ItemStack.empty() : content.clone());
        }
        return slots;
    }

    private boolean containerMatches(ItemStack stack, ItemStack[] contents) {
        ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
        List<ItemStack> occupied = new ArrayList<>();
        for (ItemStack content : contents) {
            if (content != null && !content.getType().isAir()) {
                occupied.add(content);
            }
        }
        if (occupied.isEmpty()) {
            return container == null;
        }
        if (container == null) {
            return false;
        }
        List<ItemStack> actual = new ArrayList<>();
        for (ItemStack content : container.contents()) {
            if (content != null && !content.getType().isAir()) {
                actual.add(content);
            }
        }
        if (actual.size() != occupied.size()) {
            return false;
        }
        for (int index = 0; index < occupied.size(); index++) {
            if (!sameOccupant(occupied.get(index), actual.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOccupant(ItemStack expected, ItemStack actual) {
        return expected.getAmount() == actual.getAmount() && expected.isSimilar(actual);
    }

    private boolean previewMatches(ItemStack stack, ItemStack[] contents) {
        if (stack.getData(DataComponentTypes.LORE) != null) {
            return false;
        }
        boolean hidden = bundleTooltipHidden(stack);
        if (!occupied(contents)) {
            return containerMatches(stack, contents) && !hidden;
        }
        return containerMatches(stack, contents) && hidden;
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
        int size = capacity(hand);
        ItemStack[] contents = new ItemStack[size];
        read(hand, contents, new ArrayList<>());
        SackHolder holder = new SackHolder(id);
        Inventory inventory = Bukkit.createInventory(holder, size, hand.displayName());
        holder.inventory = inventory;
        for (int index = 0; index < contents.length; index++) {
            if (contents[index] != null) {
                inventory.setItem(index, contents[index]);
            }
        }
        refreshVisible(inventory);
        open.put(player.getUniqueId(), new Open(id, slot, holder));
        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
            open.remove(player.getUniqueId());
            return;
        }
        playBundle(player, Sound.ITEM_BUNDLE_DROP_CONTENTS);
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
        Inventory inventory = sack.holder.inventory;
        int size = inventory.getSize();
        ItemStack[] contents = new ItemStack[size];
        List<ItemStack> rejected = new ArrayList<>();
        for (int index = 0; index < size; index++) {
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

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || !isBundle(result)) {
            return;
        }
        ItemStack next = result.clone();
        if (refreshStack(next)) {
            event.setResult(next);
        }
    }

    private Prepared prepare(ItemStack source) {
        ItemStack stack = source.clone();
        applyAppearance(stack);
        UUID id = id(stack);
        if (id == null) {
            id = UUID.randomUUID();
        }
        ItemStack[] contents = new ItemStack[capacity(stack)];
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
        Inventory temp = Bukkit.createInventory(null, contents.length);
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
        System.arraycopy(temp.getContents(), 0, contents, 0, contents.length);
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
        byte[] slots = new byte[contents.length];
        int count = 0;
        for (int slot = 0; slot < contents.length; slot++) {
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
            data.set(sizeKey, PersistentDataType.BYTE, (byte) contents.length);
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
            applyPreview(stack, contents);
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

    private boolean isForbidden(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (Tag.ITEMS_SHULKER_BOXES.isTagged(stack.getType())) {
            return true;
        }
        return Tag.ITEMS_BUNDLES.isTagged(stack.getType()) && !isEmptySack(stack);
    }

    private boolean isEmptySack(ItemStack stack) {
        if (!stack.getPersistentDataContainer().has(readyKey, PersistentDataType.BYTE)) {
            return !(stack.getItemMeta() instanceof BundleMeta meta) || !meta.hasItems();
        }
        byte[] data = stack.getPersistentDataContainer().get(itemsKey, PersistentDataType.BYTE_ARRAY);
        return data == null || data.length == 0;
    }

    private boolean swapSackOntoSlot(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        if (action != InventoryAction.PICKUP_ALL_INTO_BUNDLE && action != InventoryAction.PICKUP_SOME_INTO_BUNDLE) {
            return false;
        }
        if (!isBundle(event.getCursor()) || !(event.getWhoClicked() instanceof Player player)) {
            return false;
        }
        Inventory clicked = event.getClickedInventory();
        ItemStack current = event.getCurrentItem();
        if (clicked == null || current == null || current.getType().isAir()) {
            return false;
        }
        Open sack = open.get(player.getUniqueId());
        if (sack != null && event.getView().getTopInventory().getHolder(false) == sack.holder) {
            if (clicked == event.getView().getTopInventory()) {
                return false;
            }
            if (matches(event.getCursor(), sack.id) || matches(current, sack.id)) {
                return false;
            }
        }
        ItemStack cursor = event.getCursor().clone();
        ItemStack occupied = current.clone();
        int slot = event.getSlot();
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            clicked.setItem(slot, cursor);
            player.setItemOnCursor(occupied);
        });
        return true;
    }

    private boolean chestInsteadOfBundle(InventoryClickEvent event, Player player, Open sack) {
        if (event.getClickedInventory() != event.getView().getTopInventory() || !vanillaBundleClick(event)) {
            return false;
        }
        event.setCancelled(true);
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (isForbidden(cursor)) {
            player.sendActionBar(rejection(cursor));
            playBundle(player, Sound.ITEM_BUNDLE_INSERT_FAIL);
            return true;
        }
        if (matches(cursor, sack.id) || matches(current, sack.id)) {
            return true;
        }
        ItemStack newCursor;
        ItemStack newSlot;
        if (event.getClick() == ClickType.RIGHT) {
            if (cursor == null || cursor.getType().isAir()) {
                if (current == null || current.getType().isAir()) {
                    return true;
                }
                newCursor = current.clone();
                newCursor.setAmount(1);
                newSlot = current.clone();
                newSlot.setAmount(current.getAmount() - 1);
            } else if (current == null || current.getType().isAir() || current.isSimilar(cursor)) {
                int have = current == null || current.getType().isAir() ? 0 : current.getAmount();
                int limit = cursor.getMaxStackSize();
                if (have >= limit) {
                    return true;
                }
                newSlot = have == 0 ? cursor.clone() : current.clone();
                newSlot.setAmount(have + 1);
                newCursor = cursor.clone();
                newCursor.setAmount(cursor.getAmount() - 1);
            } else {
                return true;
            }
        } else if (event.getClick() == ClickType.LEFT) {
            newCursor = current == null || current.getType().isAir() ? null : current.clone();
            newSlot = cursor == null || cursor.getType().isAir() ? null : cursor.clone();
        } else {
            return true;
        }
        if (newCursor != null && newCursor.getType().isAir()) {
            newCursor = null;
        }
        if (newSlot != null && newSlot.getAmount() <= 0) {
            newSlot = null;
        }
        sack.previous = copy(sack.holder.inventory);
        int slot = event.getSlot();
        Inventory clicked = event.getClickedInventory();
        ItemStack placeCursor = newCursor;
        ItemStack placeSlot = newSlot;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || open.get(player.getUniqueId()) != sack) {
                return;
            }
            clicked.setItem(slot, placeSlot);
            player.setItemOnCursor(placeCursor);
            playContentsChange(player);
            remember(player);
        });
        return true;
    }

    private void playContentsChange(Player player) {
        Open sack = open.get(player.getUniqueId());
        if (sack == null || sack.previous == null) {
            return;
        }
        ItemStack[] before = sack.previous;
        sack.previous = null;
        ItemStack[] after = sack.holder.inventory.getContents();
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
        }
    }

    private static void playBundle(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 0.8f, 1f);
    }

    private static ItemStack[] copy(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        ItemStack[] copied = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            copied[index] = item == null ? null : item.clone();
        }
        return copied;
    }

    private static int amount(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? 0 : stack.getAmount();
    }

    private static Component rejection(ItemStack stack) {
        if (stack != null && Tag.ITEMS_BUNDLES.isTagged(stack.getType())) {
            return REJECTED_SACK;
        }
        return REJECTED_SHULKER;
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

    private void registerSackRecipes() {
        Bukkit.removeRecipe(mediumKey);
        Bukkit.removeRecipe(largeKey);
        Bukkit.removeRecipe(shulkerKey);

        ShapedRecipe medium = new ShapedRecipe(mediumKey, tierItem(18, false));
        medium.shape("DCD", "DSD", "DDD");
        medium.setIngredient('D', Material.DIAMOND);
        medium.setIngredient('C', Material.CHEST);
        medium.setIngredient('S', sackChoice(9, ItemStack.of(Material.BUNDLE)));
        Bukkit.addRecipe(medium);

        ShapedRecipe large = new ShapedRecipe(largeKey, tierItem(27, false));
        large.shape("CDC", "DSD", "DDD");
        large.setIngredient('D', Material.DIAMOND);
        large.setIngredient('C', Material.CHEST);
        large.setIngredient('S', sackChoice(18, tierItem(18, false)));
        Bukkit.addRecipe(large);

        ShapedRecipe shulker = new ShapedRecipe(shulkerKey, tierItem(54, true));
        shulker.shape("DHD", "DSD", "DDD");
        shulker.setIngredient('D', Material.DIAMOND);
        shulker.setIngredient('H', emptyShulker());
        shulker.setIngredient('S', sackChoice(27, tierItem(27, false)));
        Bukkit.addRecipe(shulker);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.discoverRecipe(mediumKey);
            player.discoverRecipe(largeKey);
            player.discoverRecipe(shulkerKey);
            refreshCarried(player);
        }
    }

    private RecipeChoice sackChoice(int size, ItemStack example) {
        return RecipeChoice.predicateChoice(
                stack -> isBundle(stack) && !Enderbag.is(stack) && capacity(stack) == size,
                example
        );
    }

    private static RecipeChoice emptyShulker() {
        return RecipeChoice.predicateChoice(BundleSack::isEmptyShulker, ItemStack.of(Material.SHULKER_BOX));
    }

    private static boolean isEmptyShulker(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !Tag.ITEMS_SHULKER_BOXES.isTagged(stack.getType())) {
            return false;
        }
        ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack item : container.contents()) {
                if (item != null && !item.getType().isAir()) {
                    return false;
                }
            }
        }
        if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack item : box.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    private Upgrade upgradeFor(NamespacedKey key) {
        if (mediumKey.equals(key)) {
            return new Upgrade(9, 18, false);
        }
        if (largeKey.equals(key)) {
            return new Upgrade(18, 27, false);
        }
        if (shulkerKey.equals(key)) {
            return new Upgrade(27, 54, true);
        }
        return null;
    }

    private ItemStack sackIn(ItemStack[] matrix, int size) {
        if (matrix == null) {
            return null;
        }
        for (ItemStack item : matrix) {
            if (isBundle(item) && !Enderbag.is(item) && capacity(item) == size) {
                return item;
            }
        }
        return null;
    }

    private ItemStack upgrade(ItemStack source, Upgrade upgrade) {
        ItemStack result = source.clone();
        result.setAmount(1);
        result.editMeta(meta -> meta.getPersistentDataContainer().set(sizeKey, PersistentDataType.BYTE, (byte) upgrade.to));
        applyAppearance(result);
        if (upgrade.glint) {
            result.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return result;
    }

    private ItemStack tierItem(int size, boolean glint) {
        ItemStack stack = ItemStack.of(Material.BUNDLE);
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(sizeKey, PersistentDataType.BYTE, (byte) size));
        applyAppearance(stack);
        if (glint) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private boolean refreshStack(ItemStack stack) {
        return updatePreview(stack)
                | ReinforcedNetherite.tint(stack)
                | DreamerTotem.tint(stack)
                | Enderbag.tint(stack);
    }

    private boolean applyAppearance(ItemStack stack) {
        if (!isBundle(stack) || Enderbag.is(stack)) {
            return false;
        }
        ItemRarity rarity = tierRarity(capacity(stack));
        if (rarity == null) {
            return false;
        }
        boolean changed = false;
        if (stack.getData(DataComponentTypes.RARITY) != rarity) {
            stack.setData(DataComponentTypes.RARITY, rarity);
            changed = true;
        }
        Component current = stack.getData(DataComponentTypes.CUSTOM_NAME);
        Component next = current == null || isTierName(current)
                ? nameFor(capacity(stack))
                : NameColor.paint(current, rarity.color());
        if (next != null && !next.equals(current)) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, next);
            changed = true;
        }
        return changed;
    }

    private static ItemRarity tierRarity(int size) {
        return switch (size) {
            case 18 -> ItemRarity.UNCOMMON;
            case 27 -> ItemRarity.RARE;
            case 54 -> ItemRarity.EPIC;
            default -> null;
        };
    }

    private static Component nameFor(int size) {
        return switch (size) {
            case 18 -> MEDIUM_NAME;
            case 27 -> LARGE_NAME;
            case 54 -> SHULKER_NAME;
            default -> null;
        };
    }

    private static Component tierName(String text, ItemRarity rarity) {
        return NameColor.named(text, rarity.color());
    }

    private int capacity(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 9;
        }
        Byte stored = stack.getPersistentDataContainer().get(sizeKey, PersistentDataType.BYTE);
        if (stored == null) {
            return 9;
        }
        int size = stored & 0xFF;
        if (size == 9 || size == 18 || size == 27 || size == 54) {
            return size;
        }
        return 9;
    }

    private static boolean isTierName(Component name) {
        String text = PlainTextComponentSerializer.plainText().serialize(name);
        return text.equals("Saco Mediano") || text.equals("Saco grande") || text.equals("Saco Shulker");
    }

    private record Upgrade(int from, int to, boolean glint) {
    }

    private static final class Open {
        private final UUID id;
        private final int slot;
        private final SackHolder holder;
        private ItemStack[] previous;

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
