package dev.dreamers;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
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
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lidded;
import org.bukkit.block.Lockable;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cofres, cofres de cobre, barriles y vagonetas con tabla de botín se abren con un inventario por jugador.
 * La tabla se queda en el contenedor y cada jugador guarda su copia ahí mismo.
 */
final class PersonalLoot implements Listener {
    private static final int HALF = 27;
    private static final double REACH = 64.0;
    private static final double MINECART_SPEED = 0.4;
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final Component DENIED = Component.text("No puedes romper este contenedor de botín.", NamedTextColor.RED);
    private static final Component DENIED_SUPPORT = Component.text(
            "No puedes romper el soporte de este contenedor de botín.", NamedTextColor.RED);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, Integer> lids = new HashMap<>();
    private final Set<UUID> removing = new HashSet<>();
    private final Set<StorageMinecart> frozen = new HashSet<>();
    private final Set<NamespacedKey> warned = new HashSet<>();
    private VanillaChestLoot chestLoot;

    private boolean enabled = true;
    private boolean restricted = true;
    private boolean confirmation = true;
    private boolean replenish;
    private boolean requireEmptied = true;
    private long minutesMin = 720;
    private long minutesMax = 2880;
    private int maxRefills = 1;

    PersonalLoot(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (enabled) {
            freezeLoaded();
        }
    }

    void shutdown() {
        closeAll();
        unfreezeAll();
    }

    void reload() {
        boolean was = enabled;
        load();
        if (!enabled) {
            closeAll();
            unfreezeAll();
            return;
        }
        if (!was) {
            freezeLoaded();
        }
    }

    private void load() {
        var config = plugin.getConfig();
        boolean missing = !config.contains("unique-loot.auto-replenish.require-emptied");
        enabled = config.getBoolean("unique-loot.enabled", true);
        restricted = config.getBoolean("unique-loot.destroying.restricted", true);
        confirmation = config.getBoolean("unique-loot.destroying.confirmation", true);
        replenish = config.getBoolean("unique-loot.auto-replenish.enabled", false);
        requireEmptied = config.getBoolean("unique-loot.auto-replenish.require-emptied", true);
        minutesMin = Math.min(10_000_000L, Math.max(0L, config.getLong("unique-loot.auto-replenish.minutes-min", 720)));
        minutesMax = Math.min(10_000_000L, Math.max(minutesMin, config.getLong("unique-loot.auto-replenish.minutes-max", 2880)));
        int refills = config.getInt("unique-loot.auto-replenish.max-refills", 1);
        maxRefills = refills < 0 ? Integer.MAX_VALUE : refills;
        if (missing) {
            plugin.saveConfig();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !container(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (!wants(player) || !anyLoot(halves(block)) || blocked(halves(block)) || lockedOut(player, halves(block))) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Location anchor = center(block);
        plugin.getServer().getScheduler().runTask(plugin, () -> openBlock(player, block, anchor));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!enabled || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof StorageMinecart minecart) || !isLootMinecart(minecart)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        event.setCancelled(true);
        Location anchor = minecart.getLocation().clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> openMinecart(player, minecart, anchor));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        finish(player, event.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        finish(event.getPlayer(), null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!enabled || !sessions.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        away(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!enabled) {
            return;
        }
        Location to = event.getTo();
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (to != null && session != null && tooFar(to, session)) {
            event.getPlayer().closeInventory();
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Session session = session(event.getWhoClicked(), event.getView());
        if (session != null) {
            pullShared(session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClickAfter(InventoryClickEvent event) {
        Session session = session(event.getWhoClicked(), event.getView());
        if (session != null) {
            pushShared(session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Session session = session(event.getWhoClicked(), event.getView());
        if (session != null) {
            pullShared(session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragAfter(InventoryDragEvent event) {
        Session session = session(event.getWhoClicked(), event.getView());
        if (session != null) {
            pushShared(session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        // Paper puede vaciar la tabla antes del evento. Se cancela por el tipo de contenedor y se vuelve a poner.
        if (!enabled || !vanillaHolder(event.getInventoryHolder())) {
            return;
        }
        event.setCancelled(true);
        restore(event.getInventoryHolder(), event.getLootTable());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (removing.contains(player.getUniqueId())) {
            return;
        }
        Block block = event.getBlock();
        if (protectedChest(block)) {
            guard(event, player, DENIED, () -> {
                if (protectedChest(block)) {
                    confirmed(player, () -> player.breakBlock(block));
                }
            });
            return;
        }
        StorageMinecart minecart = lootMinecartOf(block);
        if (minecart == null) {
            return;
        }
        guard(event, player, DENIED_SUPPORT, () -> confirmed(player, () -> {
            player.breakBlock(block);
            if (minecart.isValid()) {
                destroyMinecart(minecart);
            }
        }));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        strip(event.blockList());
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        strip(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonHits(event.getBlock(), event.getBlocks(), true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonHits(event.getBlock(), event.getBlocks(), false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (enabled && isLootMinecartBase(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (!enabled || canDestroy(event.getPlayer())) {
            return;
        }
        if (isLootMinecartBase(event.getBlock()) || isLootMinecartBase(event.getBlockClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (enabled && isLootMinecartBase(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) {
        if (enabled && isLootMinecartBase(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (enabled && (lootInventory(event.getSource()) || lootInventory(event.getDestination()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTransport(ItemTransportingEntityValidateTargetEvent event) {
        if (enabled && isLootChest(event.getBlock())) {
            event.setAllowed(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!enabled) {
            return;
        }
        Block block = event.getBlock();
        split(block);
        plugin.getServer().getScheduler().runTask(plugin, () -> split(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (!enabled || !(event.getVehicle() instanceof StorageMinecart minecart) || !isLootMinecart(minecart)) {
            return;
        }
        Player player = event.getAttacker() instanceof Player attacker ? attacker : null;
        if (player != null && removing.contains(player.getUniqueId())) {
            return;
        }
        if (player != null && canDestroy(player) && !confirmation) {
            return;
        }
        event.setCancelled(true);
        if (player == null || !canDestroy(player)) {
            if (player != null) {
                player.sendActionBar(DENIED);
            }
            return;
        }
        confirm(player, () -> {
            if (minecart.isValid() && isLootMinecart(minecart)) {
                confirmed(player, () -> destroyMinecart(minecart));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCollision(VehicleEntityCollisionEvent event) {
        if (enabled && isLootMinecart(event.getVehicle())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (enabled && event.getEntity() instanceof StorageMinecart minecart && isLootMinecart(minecart)) {
            freeze(minecart);
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof StorageMinecart minecart) {
            frozen.remove(minecart);
        }
    }

    private void openBlock(Player player, Block block, Location anchor) {
        if (!plugin.isEnabled() || !enabled || !player.isOnline() || player.isDead()) {
            return;
        }
        List<Block> halves = halves(block);
        if (!anyLoot(halves) || blocked(halves) || lockedOut(player, halves) || !player.getWorld().equals(anchor.getWorld())
                || player.getLocation().distanceSquared(anchor) > REACH) {
            return;
        }
        List<Part> parts = new ArrayList<>(halves.size());
        int offset = 0;
        for (Block half : halves) {
            parts.add(new Part(isLootChest(half), offset, HALF, half, null));
            offset += HALF;
        }
        Component title = containerTitle(block.getType());
        show(player, parts, offset, title, anchor, null, halves);
    }

    private void openMinecart(Player player, StorageMinecart minecart, Location anchor) {
        if (!plugin.isEnabled() || !enabled || !player.isOnline() || player.isDead() || !minecart.isValid() || !isLootMinecart(minecart)) {
            return;
        }
        if (!player.getWorld().equals(minecart.getWorld()) || player.getLocation().distanceSquared(minecart.getLocation()) > REACH) {
            return;
        }
        Part part = new Part(true, 0, HALF, null, minecart);
        if (show(player, List.of(part), HALF, Component.translatable("container.chest"), anchor, Sound.BLOCK_CHEST_CLOSE, List.of())) {
            play(minecart.getLocation(), Sound.BLOCK_CHEST_OPEN);
        }
    }

    private boolean show(Player player, List<Part> parts, int size, Component title, Location anchor, Sound closeSound, List<Block> lidBlocks) {
        LootView holder = new LootView();
        Inventory inventory = plugin.getServer().createInventory(holder, size, title);
        holder.inventory = inventory;
        for (Part part : parts) {
            if (part.personal) {
                place(inventory, part, items(part, player));
            } else {
                copyShared(part, inventory, true);
            }
        }
        InventoryView opened = player.openInventory(inventory);
        if (opened == null || opened.getTopInventory().getHolder() != holder) {
            return false;
        }
        sessions.put(player.getUniqueId(), new Session(holder, parts, anchor.clone(), closeSound));
        for (Block block : lidBlocks) {
            lid(block, true);
        }
        return true;
    }

    private ItemStack[] items(Part part, Player player) {
        if (part.minecart != null) {
            return resolve(part.minecart.getPersistentDataContainer(), part.minecart.getLocation(), player, part.minecart.getLootTable());
        }
        BlockState state = part.block.getState(false);
        if (!(state instanceof Lootable lootable) || !(state instanceof TileState tile)) {
            return blank();
        }
        ItemStack[] generated = resolve(tile.getPersistentDataContainer(), center(part.block), player, lootable.getLootTable());
        persist(state);
        return generated;
    }

    private ItemStack[] resolve(PersistentDataContainer data, Location origin, Player player, LootTable table) {
        UUID id = player.getUniqueId();
        ItemStack[] saved = read(data, id);
        int count = data.getOrDefault(refillsKey(id), PersistentDataType.INTEGER, 0);
        int version = data.getOrDefault(versionKey(id), PersistentDataType.INTEGER, 0);
        // Una generación fallida dejó el inventario vacío y lo guardó. Esas copias se vuelven a tirar una vez.
        boolean retryEmpty = saved != null && empty(saved) && version < 1 && genericTable(table);
        if (saved != null && !retryEmpty && !due(data, id, saved, count)) {
            return saved;
        }
        ItemStack[] generated = generate(origin, player, table);
        if (generated == null) {
            return blank();
        }
        writeItems(data, id, generated);
        data.set(versionKey(id), PersistentDataType.INTEGER, 1);
        if (replenish) {
            boolean first = saved == null || retryEmpty;
            writeRefill(data, id, first ? 0 : count >= Integer.MAX_VALUE ? count : count + 1);
        }
        return generated;
    }

    private boolean due(PersistentDataContainer data, UUID id, ItemStack[] saved, int count) {
        if (!replenish || count >= maxRefills) {
            return false;
        }
        Long at = data.get(refillAtKey(id), PersistentDataType.LONG);
        if (at == null) {
            writeRefill(data, id, count);
            return false;
        }
        return System.currentTimeMillis() >= at && (!requireEmptied || empty(saved));
    }

    private void writeRefill(PersistentDataContainer data, UUID id, int count) {
        data.set(refillsKey(id), PersistentDataType.INTEGER, count);
        if (!replenish || count >= maxRefills) {
            data.remove(refillAtKey(id));
            return;
        }
        long span = minutesMax - minutesMin;
        long minutes = span <= 0 ? minutesMin : minutesMin + ThreadLocalRandom.current().nextLong(span + 1);
        data.set(refillAtKey(id), PersistentDataType.LONG, System.currentTimeMillis() + minutes * 60_000L);
    }

    private ItemStack[] generate(Location origin, Player player, LootTable table) {
        if (table == null || origin.getWorld() == null) {
            return blank();
        }
        try {
            Inventory temp = plugin.getServer().createInventory(null, HALF);
            float luck = 0f;
            AttributeInstance attribute = player.getAttribute(Attribute.LUCK);
            if (attribute != null) {
                luck = (float) attribute.getValue();
            }
            // El tipo genérico, el que queda cuando la tabla no trae "type", exige todos los parámetros.
            // Un cofre de vanilla solo usa el conjunto chest: origen y, si cabe, el jugador.
            chestLoot().fill(temp, origin, player, luck, table);
            ItemStack[] items = blank();
            for (int slot = 0; slot < HALF; slot++) {
                items[slot] = copy(temp.getItem(slot));
            }
            return items;
        } catch (RuntimeException | ReflectiveOperationException exception) {
            if (warned.add(table.getKey())) {
                Throwable cause = exception instanceof InvocationTargetException wrapped && wrapped.getCause() != null
                        ? wrapped.getCause() : exception;
                String message = cause.getMessage();
                if (message == null || message.isBlank()) {
                    message = cause.getClass().getSimpleName();
                }
                plugin.getLogger().warning("No se pudo generar el botín " + table.getKey() + ": " + message);
            }
            return null;
        }
    }

    private boolean genericTable(LootTable table) {
        if (table == null) {
            return false;
        }
        try {
            return chestLoot().generic(table);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private VanillaChestLoot chestLoot() throws ReflectiveOperationException {
        VanillaChestLoot loaded = chestLoot;
        if (loaded == null) {
            loaded = VanillaChestLoot.load();
            chestLoot = loaded;
        }
        return loaded;
    }

    private void finish(Player player, Inventory inventory) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (inventory != null && inventory.getHolder() != session.holder) {
            return;
        }
        sessions.remove(player.getUniqueId());
        save(session, player.getUniqueId());
        for (Part part : session.parts) {
            if (part.block != null) {
                lid(part.block, false);
            }
        }
        if (session.closeSound != null) {
            play(session.anchor, session.closeSound);
        }
    }

    private void save(Session session, UUID id) {
        Inventory view = session.holder.inventory;
        for (Part part : session.parts) {
            if (part.personal) {
                store(part, id, take(view, part));
            } else {
                copyShared(part, view, false);
            }
        }
    }

    private void store(Part part, UUID id, ItemStack[] items) {
        if (part.minecart != null) {
            if (part.minecart.isValid()) {
                writeItems(part.minecart.getPersistentDataContainer(), id, items);
            }
            return;
        }
        if (part.block == null || !part.block.getWorld().isChunkLoaded(part.block.getX() >> 4, part.block.getZ() >> 4)) {
            return;
        }
        BlockState state = part.block.getState(false);
        if (!(state instanceof TileState tile) || !(state instanceof Lootable)) {
            return;
        }
        writeItems(tile.getPersistentDataContainer(), id, items);
        persist(state);
    }

    private void pullShared(Session session) {
        for (Part part : session.parts) {
            if (!part.personal) {
                copyShared(part, session.holder.inventory, true);
            }
        }
    }

    private void pushShared(Session session) {
        for (Part part : session.parts) {
            if (!part.personal) {
                copyShared(part, session.holder.inventory, false);
            }
        }
    }

    private void copyShared(Part part, Inventory view, boolean intoView) {
        Inventory real = halfInventory(part.block);
        if (real == null) {
            return;
        }
        for (int slot = 0; slot < part.size && slot < real.getSize(); slot++) {
            if (intoView) {
                ItemStack stack = real.getItem(slot);
                view.setItem(part.offset + slot, stack == null || stack.isEmpty() ? null : stack.clone());
            } else {
                ItemStack stack = view.getItem(part.offset + slot);
                real.setItem(slot, stack == null || stack.isEmpty() ? null : stack.clone());
            }
        }
    }

    private Inventory halfInventory(Block block) {
        if (block == null) {
            return null;
        }
        BlockState state = block.getState(false);
        if (state instanceof Chest chest) {
            return chest.getBlockInventory();
        }
        if (state instanceof org.bukkit.block.Container container) {
            return container.getInventory();
        }
        return null;
    }

    private void place(Inventory view, Part part, ItemStack[] items) {
        for (int slot = 0; slot < part.size; slot++) {
            ItemStack stack = slot < items.length ? items[slot] : null;
            view.setItem(part.offset + slot, stack == null || stack.isEmpty() ? null : stack.clone());
        }
    }

    private ItemStack[] take(Inventory view, Part part) {
        ItemStack[] items = blank();
        for (int slot = 0; slot < part.size; slot++) {
            items[slot] = copy(view.getItem(part.offset + slot));
        }
        return items;
    }

    private void lid(Block block, boolean open) {
        String key = block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        int count = lids.getOrDefault(key, 0);
        if (open) {
            if (count == 0) {
                animate(block, true);
            }
            lids.put(key, count + 1);
            return;
        }
        if (count <= 1) {
            lids.remove(key);
            animate(block, false);
            return;
        }
        lids.put(key, count - 1);
    }

    private void animate(Block block, boolean open) {
        if (!container(block.getType())) {
            return;
        }
        BlockState state = block.getState(false);
        if (state instanceof Lidded lidded && state instanceof TileState tile && !tile.isSnapshot()) {
            if (open) {
                lidded.open();
            } else if (lidded.isOpen()) {
                lidded.close();
            }
            return;
        }
        play(center(block), lidSound(block.getType(), open));
    }

    private void away(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && tooFar(player.getLocation(), session)) {
            player.closeInventory();
        }
    }

    private boolean tooFar(Location at, Session session) {
        for (Part part : session.parts) {
            Location origin = null;
            if (part.minecart != null) {
                if (part.minecart.isValid()) {
                    origin = part.minecart.getLocation();
                }
            } else if (part.block != null && part.block.getWorld().equals(at.getWorld())) {
                origin = center(part.block);
            }
            if (origin != null && origin.getWorld() != null && origin.getWorld().equals(at.getWorld()) && at.distanceSquared(origin) <= REACH) {
                return false;
            }
        }
        return true;
    }

    private void guard(BlockBreakEvent event, Player player, Component denied, Runnable destroy) {
        if (canDestroy(player) && !confirmation) {
            return;
        }
        event.setCancelled(true);
        if (!canDestroy(player)) {
            player.sendActionBar(denied);
            return;
        }
        confirm(player, destroy);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void confirm(Player player, Runnable destroy) {
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("Botín único"))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("¿Romper este contenedor de botín? Su botín no se suelta."), 200)))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Romper"), null, 100,
                                DialogAction.customClick((view, audience) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (player.isOnline()) {
                                        destroy.run();
                                    }
                                }), ClickCallback.Options.builder().uses(1).build())),
                        ActionButton.create(Component.text("Cancelar"), null, 100,
                                DialogAction.customClick((view, audience) -> {
                                }, ClickCallback.Options.builder().uses(1).build())))));
        player.showDialog(dialog);
    }

    private void confirmed(Player player, Runnable destroy) {
        removing.add(player.getUniqueId());
        try {
            destroy.run();
        } finally {
            removing.remove(player.getUniqueId());
        }
    }

    private void strip(List<Block> blocks) {
        if (!enabled || !restricted) {
            return;
        }
        blocks.removeIf(block -> protectedChest(block) || lootMinecartOf(block) != null);
    }

    private boolean pistonHits(Block piston, List<Block> blocks, boolean extending) {
        if (!enabled || !restricted || !(piston.getBlockData() instanceof Directional directional)) {
            return false;
        }
        BlockFace facing = directional.getFacing();
        BlockFace movement = extending ? facing : facing.getOppositeFace();
        for (Block block : blocks) {
            if (minecartCell(block) || minecartCell(block.getRelative(movement))) {
                return true;
            }
        }
        return extending && minecartCell(piston.getRelative(facing));
    }

    private boolean minecartCell(Block block) {
        return isLootMinecartBase(block) || lootMinecartOn(block) != null;
    }

    private void split(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data) || data.getType() == Type.SINGLE) {
            return;
        }
        BlockFace face = data.getType() == Type.LEFT ? turn(data.getFacing(), 1) : turn(data.getFacing(), 3);
        Block other = block.getRelative(face);
        if (!isLootChest(block) && !isLootChest(other)) {
            return;
        }
        data.setType(Type.SINGLE);
        block.setBlockData(data, false);
        if (other.getBlockData() instanceof org.bukkit.block.data.type.Chest partner && partner.getType() != Type.SINGLE) {
            partner.setType(Type.SINGLE);
            other.setBlockData(partner, false);
        }
    }

    private void restore(InventoryHolder holder, LootTable table) {
        if (table == null) {
            return;
        }
        if (holder instanceof BlockState state) {
            BlockState live = state.getBlock().getState(false);
            if (live instanceof Lootable lootable && lootable.getLootTable() == null) {
                lootable.setLootTable(table);
                persist(live);
            }
            return;
        }
        if (holder instanceof Lootable lootable && lootable.getLootTable() == null) {
            lootable.setLootTable(table);
        }
    }

    private void destroyMinecart(StorageMinecart minecart) {
        frozen.remove(minecart);
        Location location = minecart.getLocation();
        minecart.remove();
        if (location.getWorld() != null) {
            location.getWorld().dropItemNaturally(location, new ItemStack(Material.CHEST_MINECART));
        }
    }

    private void freezeLoaded() {
        for (World world : plugin.getServer().getWorlds()) {
            for (StorageMinecart minecart : world.getEntitiesByClass(StorageMinecart.class)) {
                if (isLootMinecart(minecart)) {
                    freeze(minecart);
                }
            }
        }
    }

    private void freeze(StorageMinecart minecart) {
        minecart.setMaxSpeed(0);
        frozen.add(minecart);
    }

    private void unfreezeAll() {
        for (StorageMinecart minecart : frozen) {
            if (minecart.isValid()) {
                minecart.setMaxSpeed(MINECART_SPEED);
            }
        }
        frozen.clear();
    }

    private void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (sessions.containsKey(player.getUniqueId())) {
                player.closeInventory();
            }
        }
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                finish(player, null);
            } else {
                sessions.remove(id);
            }
        }
    }

    private boolean wants(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return !player.isSneaking() || (main.isEmpty() && off.isEmpty());
    }

    private boolean canDestroy(Player player) {
        return !restricted || player.getGameMode() == GameMode.CREATIVE;
    }

    private boolean protectedChest(Block block) {
        if (isLootChest(block)) {
            return true;
        }
        Block other = partner(block);
        return other != null && isLootChest(other);
    }

    private boolean isLootChest(Block block) {
        if (!container(block.getType())) {
            return false;
        }
        BlockState state = block.getState(false);
        return (state instanceof Chest || state instanceof Barrel) && state instanceof Lootable lootable && lootable.getLootTable() != null;
    }

    private boolean isLootMinecart(Entity entity) {
        return entity instanceof StorageMinecart minecart && minecart.getLootTable() != null;
    }

    private boolean isLootMinecartBase(Block block) {
        return restricted && lootMinecartOf(block) != null;
    }

    private StorageMinecart lootMinecartOf(Block block) {
        StorageMinecart on = lootMinecartOn(block);
        return on != null ? on : lootMinecartOn(block.getRelative(BlockFace.UP));
    }

    private StorageMinecart lootMinecartOn(Block rail) {
        if (!Tag.RAILS.isTagged(rail.getType())) {
            return null;
        }
        Location center = center(rail);
        for (Entity entity : rail.getWorld().getNearbyEntities(center, 0.5, 0.5, 0.5)) {
            if (entity instanceof StorageMinecart minecart && isLootMinecart(minecart) && minecart.getLocation().getBlock().equals(rail)) {
                return minecart;
            }
        }
        return null;
    }

    private boolean lootInventory(Inventory inventory) {
        return heldLoot(inventory.getHolder(false));
    }

    private boolean vanillaHolder(InventoryHolder holder) {
        if (holder instanceof DoubleChest doubleChest) {
            return vanillaHolder(doubleChest.getLeftSide(false)) || vanillaHolder(doubleChest.getRightSide(false));
        }
        return holder instanceof Chest || holder instanceof Barrel || holder instanceof StorageMinecart;
    }

    private boolean heldLoot(InventoryHolder holder) {
        if (holder instanceof DoubleChest doubleChest) {
            return heldLoot(doubleChest.getLeftSide(false)) || heldLoot(doubleChest.getRightSide(false));
        }
        return (holder instanceof Chest || holder instanceof Barrel || holder instanceof StorageMinecart)
                && holder instanceof Lootable lootable && lootable.getLootTable() != null;
    }

    private boolean anyLoot(List<Block> halves) {
        for (Block half : halves) {
            if (isLootChest(half)) {
                return true;
            }
        }
        return false;
    }

    private boolean blocked(List<Block> halves) {
        for (Block half : halves) {
            BlockState state = half.getState(false);
            if (state instanceof Chest chest && chest.isBlocked() && !isLootChest(half.getRelative(BlockFace.UP))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation") // getLock sigue siendo la cerradura; la API nueva solo tiene setLockItem.
    private boolean lockedOut(Player player, List<Block> halves) {
        for (Block half : halves) {
            BlockState state = half.getState(false);
            if (state instanceof Lockable lockable && lockable.isLocked() && !holdsKey(player, lockable.getLock())) {
                return true;
            }
        }
        return false;
    }

    private boolean holdsKey(Player player, String lock) {
        if (lock == null || lock.isEmpty()) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            Component name = meta.customName();
            if (name != null && lock.equals(PLAIN.serialize(name))) {
                return true;
            }
        }
        return false;
    }

    private List<Block> halves(Block block) {
        Block other = partner(block);
        if (other == null) {
            return List.of(block);
        }
        if (block.getBlockData() instanceof org.bukkit.block.data.type.Chest data && data.getType() == Type.RIGHT) {
            return List.of(other, block);
        }
        return List.of(block, other);
    }

    private Block partner(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data) || data.getType() == Type.SINGLE) {
            return null;
        }
        BlockFace face = data.getType() == Type.LEFT ? turn(data.getFacing(), 1) : turn(data.getFacing(), 3);
        Block other = block.getRelative(face);
        if (!(other.getBlockData() instanceof org.bukkit.block.data.type.Chest)) {
            return null;
        }
        return other;
    }

    private static BlockFace turn(BlockFace face, int quarters) {
        int index = 0;
        for (int i = 0; i < HORIZONTAL.length; i++) {
            if (HORIZONTAL[i] == face) {
                index = i;
            }
        }
        return HORIZONTAL[(index + quarters) % HORIZONTAL.length];
    }

    private static boolean container(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL || copperChest(type);
    }

    private static boolean copperChest(Material type) {
        return switch (type) {
            case COPPER_CHEST, EXPOSED_COPPER_CHEST, WEATHERED_COPPER_CHEST, OXIDIZED_COPPER_CHEST,
                    WAXED_COPPER_CHEST, WAXED_EXPOSED_COPPER_CHEST, WAXED_WEATHERED_COPPER_CHEST,
                    WAXED_OXIDIZED_COPPER_CHEST -> true;
            default -> false;
        };
    }

    private static Component containerTitle(Material type) {
        if (type == Material.BARREL) {
            return Component.translatable("container.barrel");
        }
        if (copperChest(type)) {
            return Component.translatable("container.copper_chest");
        }
        return Component.translatable("container.chest");
    }

    private static Sound lidSound(Material type, boolean open) {
        if (type == Material.BARREL) {
            return open ? Sound.BLOCK_BARREL_OPEN : Sound.BLOCK_BARREL_CLOSE;
        }
        if (type == Material.OXIDIZED_COPPER_CHEST || type == Material.WAXED_OXIDIZED_COPPER_CHEST) {
            return open ? Sound.BLOCK_COPPER_CHEST_OXIDIZED_OPEN : Sound.BLOCK_COPPER_CHEST_OXIDIZED_CLOSE;
        }
        if (type == Material.WEATHERED_COPPER_CHEST || type == Material.WAXED_WEATHERED_COPPER_CHEST) {
            return open ? Sound.BLOCK_COPPER_CHEST_WEATHERED_OPEN : Sound.BLOCK_COPPER_CHEST_WEATHERED_CLOSE;
        }
        if (copperChest(type)) {
            return open ? Sound.BLOCK_COPPER_CHEST_OPEN : Sound.BLOCK_COPPER_CHEST_CLOSE;
        }
        return open ? Sound.BLOCK_CHEST_OPEN : Sound.BLOCK_CHEST_CLOSE;
    }

    private Session session(HumanEntity entity, InventoryView view) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || view.getTopInventory().getHolder() != session.holder) {
            return null;
        }
        return session;
    }

    private ItemStack[] read(PersistentDataContainer data, UUID id) {
        byte[] raw = data.get(itemsKey(id), PersistentDataType.BYTE_ARRAY);
        if (raw == null) {
            return null;
        }
        ItemStack[] stored = ItemStack.deserializeItemsFromBytes(raw);
        ItemStack[] items = blank();
        for (int slot = 0; slot < HALF; slot++) {
            items[slot] = copy(slot < stored.length ? stored[slot] : null);
        }
        return items;
    }

    private void writeItems(PersistentDataContainer data, UUID id, ItemStack[] items) {
        ItemStack[] stored = blank();
        for (int slot = 0; slot < HALF; slot++) {
            stored[slot] = copy(slot < items.length ? items[slot] : null);
        }
        data.set(itemsKey(id), PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(stored));
    }

    private NamespacedKey itemsKey(UUID id) {
        return new NamespacedKey(plugin, id.toString());
    }

    private NamespacedKey versionKey(UUID id) {
        return new NamespacedKey(plugin, id + "_v");
    }

    private NamespacedKey refillAtKey(UUID id) {
        return new NamespacedKey(plugin, id + "_at");
    }

    private NamespacedKey refillsKey(UUID id) {
        return new NamespacedKey(plugin, id + "_n");
    }

    private static ItemStack[] blank() {
        ItemStack[] items = new ItemStack[HALF];
        for (int slot = 0; slot < HALF; slot++) {
            items[slot] = ItemStack.empty();
        }
        return items;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.empty() : stack.clone();
    }

    private static boolean empty(ItemStack[] items) {
        for (ItemStack stack : items) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void persist(BlockState state) {
        if (state instanceof TileState tile && tile.isSnapshot()) {
            state.update(false, false);
        }
    }

    private static Location center(Block block) {
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    private static void play(Location location, Sound sound) {
        if (location.getWorld() != null) {
            location.getWorld().playSound(location, sound, SoundCategory.BLOCKS, 0.5f, 1f);
        }
    }

    private record Part(boolean personal, int offset, int size, Block block, StorageMinecart minecart) {
    }

    /**
     * Tira el botín con el conjunto de parámetros de un cofre, que es el que usa el juego al abrirlo.
     * {@code LootTable.fillInventory} usa el conjunto de la propia tabla y falla si esa tabla no trae tipo.
     */
    private static final class VanillaChestLoot {
        private final Method getHandle;
        private final Method getParamSet;
        private final Method withLuck;
        private final Method withParameter;
        private final Method withOptionalParameter;
        private final Method create;
        private final Method fill;
        private final Method inventory;
        private final Method randomCreate;
        private final Constructor<?> builder;
        private final Constructor<?> vec;
        private final Object originKey;
        private final Object entityKey;
        private final Object chestSet;
        private final Object allParams;
        private final Object defaultSet;

        private VanillaChestLoot(
                Method getHandle,
                Method getParamSet,
                Method withLuck,
                Method withParameter,
                Method withOptionalParameter,
                Method create,
                Method fill,
                Method inventory,
                Method randomCreate,
                Constructor<?> builder,
                Constructor<?> vec,
                Object originKey,
                Object entityKey,
                Object chestSet,
                Object allParams,
                Object defaultSet) {
            this.getHandle = getHandle;
            this.getParamSet = getParamSet;
            this.withLuck = withLuck;
            this.withParameter = withParameter;
            this.withOptionalParameter = withOptionalParameter;
            this.create = create;
            this.fill = fill;
            this.inventory = inventory;
            this.randomCreate = randomCreate;
            this.builder = builder;
            this.vec = vec;
            this.originKey = originKey;
            this.entityKey = entityKey;
            this.chestSet = chestSet;
            this.allParams = allParams;
            this.defaultSet = defaultSet;
        }

        static VanillaChestLoot load() throws ReflectiveOperationException {
            Class<?> lootTable = Class.forName("net.minecraft.world.level.storage.loot.LootTable");
            Class<?> paramsBuilder = Class.forName("net.minecraft.world.level.storage.loot.LootParams$Builder");
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> contextKey = Class.forName("net.minecraft.util.context.ContextKey");
            Class<?> keySet = Class.forName("net.minecraft.util.context.ContextKeySet");
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            Class<?> params = Class.forName("net.minecraft.world.level.storage.loot.parameters.LootContextParams");
            Class<?> sets = Class.forName("net.minecraft.world.level.storage.loot.parameters.LootContextParamSets");
            Class<?> randomSource = Class.forName("net.minecraft.util.RandomSource");
            Method fill = null;
            for (Method method : lootTable.getMethods()) {
                if ("fill".equals(method.getName()) && method.getParameterCount() == 4) {
                    fill = method;
                    break;
                }
            }
            if (fill == null) {
                throw new NoSuchMethodException("LootTable.fill");
            }
            return new VanillaChestLoot(
                    Class.forName("org.bukkit.craftbukkit.CraftLootTable").getMethod("getHandle"),
                    lootTable.getMethod("getParamSet"),
                    paramsBuilder.getMethod("withLuck", float.class),
                    paramsBuilder.getMethod("withParameter", contextKey, Object.class),
                    paramsBuilder.getMethod("withOptionalParameter", contextKey, Object.class),
                    paramsBuilder.getMethod("create", keySet),
                    fill,
                    Class.forName("org.bukkit.craftbukkit.inventory.CraftInventory").getMethod("getInventory"),
                    randomSource.getMethod("create", long.class),
                    paramsBuilder.getConstructor(serverLevel),
                    vec3.getConstructor(double.class, double.class, double.class),
                    params.getField("ORIGIN").get(null),
                    params.getField("THIS_ENTITY").get(null),
                    sets.getField("CHEST").get(null),
                    sets.getField("ALL_PARAMS").get(null),
                    lootTable.getField("DEFAULT_PARAM_SET").get(null));
        }

        boolean generic(LootTable table) throws ReflectiveOperationException {
            Object set = getParamSet.invoke(getHandle.invoke(table));
            return allParams.equals(set) || defaultSet.equals(set);
        }

        void fill(Inventory target, Location origin, Player player, float luck, LootTable table) throws ReflectiveOperationException {
            Object handle = getHandle.invoke(table);
            Object level = origin.getWorld().getClass().getMethod("getHandle").invoke(origin.getWorld());
            Object params = builder.newInstance(level);
            params = withLuck.invoke(params, luck);
            params = withParameter.invoke(params, originKey, vec.newInstance(origin.getX(), origin.getY(), origin.getZ()));
            Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);
            params = withOptionalParameter.invoke(params, entityKey, nmsPlayer);
            params = create.invoke(params, chestSet);
            Object random = randomCreate.invoke(null, ThreadLocalRandom.current().nextLong());
            fill.invoke(handle, inventory.invoke(target), params, random, true);
        }
    }

    private static final class LootView implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Session(LootView holder, List<Part> parts, Location anchor, Sound closeSound) {
    }
}
