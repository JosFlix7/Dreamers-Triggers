package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class InventorySee implements Listener {
    private static final Component DENIED = Component.text("No puedes usar este comando.", NamedTextColor.RED);
    private static final Component UNKNOWN = Component.text("Ese jugador no existe.", NamedTextColor.RED);
    private static final Component ONLY_PLAYER = Component.text("Solo un jugador puede abrir el inventario.", NamedTextColor.RED);
    private static final int MAIN = 36;
    private static final int PERSONAL = 45;

    private final JavaPlugin plugin;
    private final OfflineItems files;
    private final NamespacedKey fillerKey;
    private final ItemStack filler;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Store> stores = new HashMap<>();
    private BukkitTask task;

    InventorySee(JavaPlugin plugin) {
        this.plugin = plugin;
        files = new OfflineItems(plugin);
        fillerKey = new NamespacedKey(plugin, "inventory_filler");
        filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> {
            meta.displayName(Component.text(" "));
            meta.setHideTooltip(true);
            meta.getPersistentDataContainer().set(fillerKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Session session : List.copyOf(sessions.values())) {
            push(session);
            if (session.viewer.isOnline()) {
                session.viewer.closeInventory();
            }
        }
        sessions.clear();
        for (Store store : stores.values()) {
            if (Bukkit.getPlayer(store.id) == null) {
                files.write(store.id, store.name, store.items);
            }
        }
        stores.clear();
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return false;
        }
        boolean ender = args[0].equalsIgnoreCase("enderchest");
        if (!ender && !args[0].equalsIgnoreCase("inventory")) {
            return false;
        }
        if (!operator(sender)) {
            sender.sendMessage(DENIED);
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(ONLY_PLAYER);
            return true;
        }
        if (args.length != 2) {
            String usage = ender ? "/dreamers enderchest <jugador>" : "/dreamers inventory <jugador>";
            sender.sendMessage(Component.text("Uso: " + usage, NamedTextColor.YELLOW));
            return true;
        }
        OfflinePlayer target = find(args[1]);
        if (target == null) {
            sender.sendMessage(UNKNOWN);
            return true;
        }
        open(viewer, target, ender);
        return true;
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!operator(sender) || args.length != 2) {
            return List.of();
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (OfflinePlayer player : everyone()) {
            String name = player.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    static boolean operator(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp();
    }

    private void open(Player viewer, OfflinePlayer target, boolean ender) {
        Player live = target.getPlayer();
        boolean online = live != null && live.isOnline();
        Store store = null;
        if (!online) {
            store = stores.get(target.getUniqueId());
            if (store == null) {
                OfflineItems.Snapshot snapshot = files.read(target.getUniqueId(), target.getName());
                if (snapshot == null) {
                    viewer.sendMessage(Component.text("No se pudo leer el inventario de " + target.getName() + ".", NamedTextColor.RED));
                    return;
                }
                store = new Store(target.getUniqueId(), target.getName(), snapshot);
                stores.put(target.getUniqueId(), store);
            }
        }
        Session previous = sessions.remove(viewer.getUniqueId());
        if (previous != null) {
            push(previous);
        }
        Session session = new Session(viewer, target.getUniqueId(), target.getName(), ender, online, store);
        Holder holder = new Holder(session);
        session.inventory = plugin.getServer().createInventory(holder, ender ? 27 : 54, Component.text(title(session)));
        holder.inventory = session.inventory;
        sessions.put(viewer.getUniqueId(), session);
        if (previous != null) {
            release(previous);
        }
        if (store != null) {
            stores.put(target.getUniqueId(), store);
        }
        for (int slot = 0; slot < session.inventory.getSize(); slot++) {
            if (cell(session, slot).kind() == Kind.BLOCKED) {
                session.inventory.setItem(slot, filler.clone());
            }
        }
        viewer.openInventory(session.inventory);
        syncPersonal(session);
        pull(session);
        arm();
    }

    private void tick() {
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.viewer.isOnline() || session.viewer.getOpenInventory().getTopInventory() != session.inventory) {
                continue;
            }
            Player live = Bukkit.getPlayer(session.target);
            if (live != null && !session.online) {
                session.online = true;
                session.store = null;
                stores.remove(session.target);
                push(session);
                session.dirty = false;
                retitle(session);
            }
            boolean changed = syncPersonal(session);
            if (changed) {
                pull(session);
                session.dirty = false;
            } else if (session.dirty) {
                if (push(session)) {
                    session.dirty = false;
                    session.warned = false;
                } else {
                    warn(session);
                }
            } else {
                pull(session);
            }
        }
        if (!stores.isEmpty()) {
            int now = Bukkit.getCurrentTick();
            stores.entrySet().removeIf(entry -> {
                if (watching(entry.getKey()) || now <= entry.getValue().offlineTick || now < entry.getValue().nextWrite) {
                    return false;
                }
                Store store = entry.getValue();
                if (Bukkit.getPlayer(store.id) != null) {
                    return false;
                }
                if (files.write(store.id, store.name, store.items)) {
                    return true;
                }
                store.nextWrite = now + 20;
                return false;
            });
        }
        if (sessions.isEmpty() && stores.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void arm() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Session session = session(event.getView().getTopInventory());
        if (session == null) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < session.inventory.getSize()) {
            Cell cell = cell(session, raw);
            if (cell.kind() == Kind.BLOCKED || cell.result()) {
                if (cell.result()) {
                    takeResult(session, event);
                }
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterClick(InventoryClickEvent event) {
        Session session = session(event.getView().getTopInventory());
        if (session != null) {
            session.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Session session = session(event.getView().getTopInventory());
        if (session == null) {
            return;
        }
        int size = session.inventory.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < size) {
                Cell cell = cell(session, raw);
                if (cell.kind() == Kind.BLOCKED || cell.result()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDrag(InventoryDragEvent event) {
        Session session = session(event.getView().getTopInventory());
        if (session != null) {
            session.dirty = true;
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.inventory != event.getInventory()) {
            return;
        }
        sessions.remove(player.getUniqueId());
        if (!push(session)) {
            warn(session);
        }
        release(session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Session viewing = sessions.remove(player.getUniqueId());
        if (viewing != null) {
            push(viewing);
            release(viewing);
        }
        List<Session> open = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.target.equals(player.getUniqueId())) {
                open.add(session);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        for (Session session : open) {
            push(session);
            session.dirty = false;
            session.online = false;
        }
        Store store = stores.get(player.getUniqueId());
        if (store == null) {
            store = new Store(player.getUniqueId(), player.getName(), capture(player));
            stores.put(player.getUniqueId(), store);
        } else {
            copy(player, store.items);
        }
        store.offlineTick = Bukkit.getCurrentTick();
        arm();
        for (Session session : open) {
            session.store = store;
            retitle(session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean viewing = watching(id);
        Store store = stores.get(id);
        if (!viewing && store != null) {
            apply(player, store.items);
        }
        for (Session session : sessions.values()) {
            if (!session.target.equals(id)) {
                continue;
            }
            session.online = true;
            session.store = null;
            push(session);
            session.dirty = false;
            retitle(session);
        }
        stores.remove(id);
    }

    private boolean push(Session session) {
        if (session.online) {
            Player target = Bukkit.getPlayer(session.target);
            if (target != null) {
                pushLive(session, target);
                return true;
            }
            session.online = false;
        }
        return pushOffline(session);
    }

    private void pushLive(Session session, Player target) {
        if (session.ender) {
            Inventory ender = target.getEnderChest();
            for (int slot = 0; slot < 27; slot++) {
                set(ender, slot, clean(session.inventory.getItem(slot)));
            }
            return;
        }
        PlayerInventory inventory = target.getInventory();
        for (int slot = 0; slot < MAIN; slot++) {
            set(inventory, slot, clean(session.inventory.getItem(slot)));
        }
        setGear(inventory, EquipmentSlot.FEET, clean(session.inventory.getItem(36)));
        setGear(inventory, EquipmentSlot.LEGS, clean(session.inventory.getItem(37)));
        setGear(inventory, EquipmentSlot.CHEST, clean(session.inventory.getItem(38)));
        setGear(inventory, EquipmentSlot.HEAD, clean(session.inventory.getItem(39)));
        setGear(inventory, EquipmentSlot.OFF_HAND, clean(session.inventory.getItem(40)));
        ItemStack shown = session.inventory.getItem(41);
        if (!filler(shown)) {
            ItemStack cursor = clean(shown);
            if (!same(target.getItemOnCursor(), cursor)) {
                target.setItemOnCursor(cursor);
            }
        }
        setGear(inventory, EquipmentSlot.BODY, clean(session.inventory.getItem(42)));
        setGear(inventory, EquipmentSlot.SADDLE, clean(session.inventory.getItem(43)));
        Inventory menu = session.personalMenu;
        Inventory open = target.getOpenInventory().getTopInventory();
        if (menu == null || open != menu) {
            return;
        }
        for (int index = 0; index < session.personal.length; index++) {
            Cell cell = session.personal[index];
            if (cell == null || cell.kind() != Kind.PERSONAL || cell.result()) {
                continue;
            }
            set(menu, cell.index(), clean(session.inventory.getItem(PERSONAL + index)));
        }
    }

    private boolean pushOffline(Session session) {
        Store store = session.store;
        if (store == null) {
            return false;
        }
        if (session.ender) {
            for (int slot = 0; slot < 27; slot++) {
                store.items.ender[slot] = clean(session.inventory.getItem(slot));
            }
        } else {
            for (int slot = 0; slot < MAIN; slot++) {
                store.items.storage[slot] = clean(session.inventory.getItem(slot));
            }
            wear(store.items.equipment, EquipmentSlot.FEET, clean(session.inventory.getItem(36)));
            wear(store.items.equipment, EquipmentSlot.LEGS, clean(session.inventory.getItem(37)));
            wear(store.items.equipment, EquipmentSlot.CHEST, clean(session.inventory.getItem(38)));
            wear(store.items.equipment, EquipmentSlot.HEAD, clean(session.inventory.getItem(39)));
            wear(store.items.equipment, EquipmentSlot.OFF_HAND, clean(session.inventory.getItem(40)));
            wear(store.items.equipment, EquipmentSlot.BODY, clean(session.inventory.getItem(42)));
            wear(store.items.equipment, EquipmentSlot.SADDLE, clean(session.inventory.getItem(43)));
        }
        return save(store);
    }

    private boolean save(Store store) {
        int now = Bukkit.getCurrentTick();
        if (now <= store.offlineTick) {
            return true;
        }
        if (now < store.nextWrite || Bukkit.getPlayer(store.id) != null) {
            return false;
        }
        if (files.write(store.id, store.name, store.items)) {
            return true;
        }
        store.nextWrite = now + 20;
        return false;
    }

    private static void wear(java.util.EnumMap<EquipmentSlot, ItemStack> equipment, EquipmentSlot slot, ItemStack item) {
        if (item == null || item.isEmpty()) {
            equipment.remove(slot);
        } else {
            equipment.put(slot, item);
        }
    }

    private void pull(Session session) {
        if (session.online) {
            Player target = Bukkit.getPlayer(session.target);
            if (target == null) {
                return;
            }
            if (session.ender) {
                Inventory ender = target.getEnderChest();
                for (int slot = 0; slot < 27; slot++) {
                    show(session, slot, ender.getItem(slot), false);
                }
                return;
            }
            PlayerInventory inventory = target.getInventory();
            for (int slot = 0; slot < MAIN; slot++) {
                show(session, slot, inventory.getItem(slot), false);
            }
            show(session, 36, inventory.getItem(EquipmentSlot.FEET), false);
            show(session, 37, inventory.getItem(EquipmentSlot.LEGS), false);
            show(session, 38, inventory.getItem(EquipmentSlot.CHEST), false);
            show(session, 39, inventory.getItem(EquipmentSlot.HEAD), false);
            show(session, 40, inventory.getItem(EquipmentSlot.OFF_HAND), false);
            show(session, 41, target.getItemOnCursor(), false);
            show(session, 42, inventory.getItem(EquipmentSlot.BODY), false);
            show(session, 43, inventory.getItem(EquipmentSlot.SADDLE), false);
            show(session, 44, null, true);
            Inventory menu = session.personalMenu;
            for (int index = 0; index < 9; index++) {
                Cell cell = session.personal[index];
                if (cell == null || cell.kind() != Kind.PERSONAL || menu == null) {
                    show(session, PERSONAL + index, null, true);
                } else {
                    show(session, PERSONAL + index, menu.getItem(cell.index()), false);
                }
            }
            return;
        }
        Store store = session.store;
        if (store == null) {
            return;
        }
        if (session.ender) {
            for (int slot = 0; slot < 27; slot++) {
                show(session, slot, store.items.ender[slot], false);
            }
            return;
        }
        for (int slot = 0; slot < MAIN; slot++) {
            show(session, slot, store.items.storage[slot], false);
        }
        show(session, 36, store.items.equipment.get(EquipmentSlot.FEET), false);
        show(session, 37, store.items.equipment.get(EquipmentSlot.LEGS), false);
        show(session, 38, store.items.equipment.get(EquipmentSlot.CHEST), false);
        show(session, 39, store.items.equipment.get(EquipmentSlot.HEAD), false);
        show(session, 40, store.items.equipment.get(EquipmentSlot.OFF_HAND), false);
        show(session, 41, null, true);
        show(session, 42, store.items.equipment.get(EquipmentSlot.BODY), false);
        show(session, 43, store.items.equipment.get(EquipmentSlot.SADDLE), false);
        show(session, 44, null, true);
        for (int index = 0; index < 9; index++) {
            show(session, PERSONAL + index, null, true);
        }
    }

    private boolean syncPersonal(Session session) {
        if (session.ender) {
            return false;
        }
        Resolved resolved = resolve(session);
        if (resolved.key.equals(session.personalKey)) {
            return false;
        }
        session.personalKey = resolved.key;
        session.personal = resolved.cells;
        session.personalMenu = resolved.menu;
        return true;
    }

    private Resolved resolve(Session session) {
        if (!session.online) {
            return Resolved.NONE;
        }
        Player target = Bukkit.getPlayer(session.target);
        if (target == null) {
            return Resolved.NONE;
        }
        Inventory top = target.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Holder || !personalMenu(top.getType())) {
            return Resolved.NONE;
        }
        Cell[] cells = new Cell[9];
        int size = top.getSize();
        int result = resultSlot(top.getType(), size);
        if (result == 0 && size > 9) {
            for (int index = 0; index < 9; index++) {
                cells[index] = Cell.personal(index + 1, false);
            }
        } else if (result == 0) {
            int matrix = Math.min(8, Math.max(0, size - 1));
            for (int index = 0; index < matrix; index++) {
                cells[index] = Cell.personal(index + 1, false);
            }
            if (matrix < 9) {
                cells[matrix] = Cell.personal(0, true);
            }
        } else {
            int count = Math.min(9, size);
            for (int index = 0; index < count; index++) {
                cells[index] = Cell.personal(index, index == result);
            }
        }
        return new Resolved(top.getType().name() + ":" + size + ":" + System.identityHashCode(top), cells, top);
    }

    private void takeResult(Session session, InventoryClickEvent event) {
        if (!(session.personalMenu instanceof CraftingInventory crafting) || crafting.getType() != InventoryType.CRAFTING) {
            return;
        }
        ClickType click = event.getClick();
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        boolean plain = click == ClickType.LEFT || click == ClickType.RIGHT;
        if (!shift && !plain) {
            return;
        }
        ItemStack result = crafting.getResult();
        if (empty(result)) {
            return;
        }
        Player viewer = session.viewer;
        ItemStack giving = result.clone();
        if (shift) {
            if (!fits(viewer, giving)) {
                return;
            }
            viewer.getInventory().addItem(giving);
        } else {
            ItemStack cursor = viewer.getItemOnCursor();
            if (empty(cursor)) {
                viewer.setItemOnCursor(giving);
            } else if (cursor.isSimilar(giving) && cursor.getAmount() + giving.getAmount() <= cursor.getMaxStackSize()) {
                cursor.setAmount(cursor.getAmount() + giving.getAmount());
            } else {
                return;
            }
        }
        ItemStack[] matrix = crafting.getMatrix();
        for (int index = 0; index < matrix.length; index++) {
            ItemStack item = matrix[index];
            if (empty(item)) {
                continue;
            }
            if (item.getAmount() <= 1) {
                matrix[index] = null;
            } else {
                ItemStack next = item.clone();
                next.setAmount(item.getAmount() - 1);
                matrix[index] = next;
            }
        }
        crafting.setMatrix(matrix);
        crafting.setResult(null);
    }

    private void apply(Player player, OfflineItems.Snapshot snapshot) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < MAIN; slot++) {
            inventory.setItem(slot, clone(snapshot.storage[slot]));
        }
        for (EquipmentSlot slot : OfflineItems.WORN) {
            inventory.setItem(slot, clone(snapshot.equipment.get(slot)));
        }
        Inventory ender = player.getEnderChest();
        for (int slot = 0; slot < ender.getSize() && slot < snapshot.ender.length; slot++) {
            ender.setItem(slot, clone(snapshot.ender[slot]));
        }
    }

    private static OfflineItems.Snapshot capture(Player player) {
        OfflineItems.Snapshot snapshot = new OfflineItems.Snapshot();
        copy(player, snapshot);
        return snapshot;
    }

    private static void copy(Player player, OfflineItems.Snapshot snapshot) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < MAIN; slot++) {
            snapshot.storage[slot] = clone(inventory.getItem(slot));
        }
        for (EquipmentSlot slot : OfflineItems.WORN) {
            ItemStack item = clone(inventory.getItem(slot));
            if (item == null) {
                snapshot.equipment.remove(slot);
            } else {
                snapshot.equipment.put(slot, item);
            }
        }
        Inventory ender = player.getEnderChest();
        for (int slot = 0; slot < snapshot.ender.length && slot < ender.getSize(); slot++) {
            snapshot.ender[slot] = clone(ender.getItem(slot));
        }
    }

    private Cell cell(Session session, int slot) {
        if (session.ender) {
            return slot >= 0 && slot < 27 ? Cell.main(slot) : Cell.blocked();
        }
        if (slot >= 0 && slot < MAIN) {
            return Cell.main(slot);
        }
        return switch (slot) {
            case 36 -> Cell.gear(EquipmentSlot.FEET);
            case 37 -> Cell.gear(EquipmentSlot.LEGS);
            case 38 -> Cell.gear(EquipmentSlot.CHEST);
            case 39 -> Cell.gear(EquipmentSlot.HEAD);
            case 40 -> Cell.gear(EquipmentSlot.OFF_HAND);
            case 41 -> session.online ? Cell.cursor() : Cell.blocked();
            case 42 -> Cell.gear(EquipmentSlot.BODY);
            case 43 -> Cell.gear(EquipmentSlot.SADDLE);
            default -> {
                if (slot >= PERSONAL && slot < PERSONAL + 9) {
                    Cell personal = session.personal[slot - PERSONAL];
                    yield personal == null ? Cell.blocked() : personal;
                }
                yield Cell.blocked();
            }
        };
    }

    private void show(Session session, int slot, ItemStack item, boolean blocked) {
        ItemStack next = blocked ? filler.clone() : clone(item);
        if (same(session.inventory.getItem(slot), next)) {
            return;
        }
        session.inventory.setItem(slot, next);
    }

    private ItemStack clean(ItemStack item) {
        if (empty(item) || filler(item)) {
            return null;
        }
        return item.clone();
    }

    private boolean filler(ItemStack item) {
        return !empty(item) && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(fillerKey, PersistentDataType.BYTE);
    }

    private void warn(Session session) {
        if (session.warned || !session.viewer.isOnline()) {
            session.warned = true;
            return;
        }
        session.warned = true;
        session.viewer.sendMessage(Component.text("No se pudo guardar el inventario de " + session.name + ".", NamedTextColor.RED));
    }

    @SuppressWarnings("deprecation")
    private void retitle(Session session) {
        if (!session.viewer.isOnline()) {
            return;
        }
        if (session.viewer.getOpenInventory().getTopInventory() == session.inventory) {
            session.viewer.getOpenInventory().setTitle(title(session));
        }
    }

    private void release(Session session) {
        if (session.store == null || watching(session.target)) {
            return;
        }
        if (Bukkit.getCurrentTick() <= session.store.offlineTick || Bukkit.getPlayer(session.target) != null) {
            return;
        }
        if (save(session.store)) {
            stores.remove(session.target);
        }
    }

    private boolean watching(UUID target) {
        for (Session session : sessions.values()) {
            if (session.target.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private Session session(Inventory inventory) {
        if (inventory.getHolder() instanceof Holder holder && holder.session.inventory == inventory) {
            return holder.session;
        }
        return null;
    }

    private OfflinePlayer find(String name) {
        Player exact = plugin.getServer().getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        OfflinePlayer loose = null;
        for (OfflinePlayer player : everyone()) {
            String known = player.getName();
            if (known == null) {
                continue;
            }
            if (known.equals(name)) {
                return player;
            }
            if (known.equalsIgnoreCase(name)) {
                loose = player;
            }
        }
        return loose;
    }

    private List<OfflinePlayer> everyone() {
        Map<UUID, OfflinePlayer> known = new HashMap<>();
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            if (player.getName() != null && !new UUID(0L, 0L).equals(player.getUniqueId())) {
                known.put(player.getUniqueId(), player);
            }
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            known.put(player.getUniqueId(), player);
        }
        return new ArrayList<>(known.values());
    }

    private static void set(Inventory inventory, int slot, ItemStack item) {
        if (!same(inventory.getItem(slot), item)) {
            inventory.setItem(slot, item);
        }
    }

    private static void setGear(PlayerInventory inventory, EquipmentSlot slot, ItemStack item) {
        if (!same(inventory.getItem(slot), item)) {
            inventory.setItem(slot, item);
        }
    }

    private static boolean fits(Player player, ItemStack stack) {
        int left = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (empty(slot)) {
                return true;
            }
            if (slot.isSimilar(stack)) {
                left -= max - slot.getAmount();
                if (left <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean personalMenu(InventoryType type) {
        return switch (type) {
            case CRAFTING, WORKBENCH, ENCHANTING, ANVIL, SMITHING, SMITHING_NEW, GRINDSTONE, LOOM, CARTOGRAPHY, STONECUTTER, MERCHANT -> true;
            default -> false;
        };
    }

    private static int resultSlot(InventoryType type, int size) {
        return switch (type) {
            case CRAFTING, WORKBENCH -> 0;
            case ENCHANTING -> -1;
            case ANVIL, GRINDSTONE, CARTOGRAPHY, STONECUTTER, MERCHANT, LOOM, SMITHING, SMITHING_NEW -> size - 1;
            default -> -1;
        };
    }

    private static String title(Session session) {
        String text = (session.ender ? "Enderchest de " : "Inventario de ") + session.name;
        if (!session.online) {
            text += " (desconectado)";
        }
        return text;
    }

    private static ItemStack clone(ItemStack item) {
        return empty(item) ? null : item.clone();
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.isEmpty() || item.getType().isAir();
    }

    private static boolean same(ItemStack left, ItemStack right) {
        boolean leftEmpty = empty(left);
        boolean rightEmpty = empty(right);
        if (leftEmpty || rightEmpty) {
            return leftEmpty && rightEmpty;
        }
        return left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    private enum Kind {
        MAIN,
        GEAR,
        CURSOR,
        PERSONAL,
        BLOCKED
    }

    private record Cell(Kind kind, int index, boolean result) {
        static Cell main(int index) {
            return new Cell(Kind.MAIN, index, false);
        }

        static Cell gear(EquipmentSlot slot) {
            return new Cell(Kind.GEAR, slot.ordinal(), false);
        }

        static Cell cursor() {
            return new Cell(Kind.CURSOR, 0, false);
        }

        static Cell personal(int index, boolean result) {
            return new Cell(Kind.PERSONAL, index, result);
        }

        static Cell blocked() {
            return new Cell(Kind.BLOCKED, 0, false);
        }
    }

    private record Resolved(String key, Cell[] cells, Inventory menu) {
        private static final Resolved NONE = new Resolved("none", new Cell[9], null);
    }

    private static final class Store {
        private final UUID id;
        private final String name;
        private final OfflineItems.Snapshot items;
        private int offlineTick;
        private int nextWrite;

        private Store(UUID id, String name, OfflineItems.Snapshot items) {
            this.id = id;
            this.name = name;
            this.items = items;
        }
    }

    private static final class Session {
        private final Player viewer;
        private final UUID target;
        private final String name;
        private final boolean ender;
        private Inventory inventory;
        private boolean online;
        private Store store;
        private boolean dirty;
        private boolean warned;
        private String personalKey = "";
        private Cell[] personal = new Cell[9];
        private Inventory personalMenu;

        private Session(Player viewer, UUID target, String name, boolean ender, boolean online, Store store) {
            this.viewer = viewer;
            this.target = target;
            this.name = name == null ? "jugador" : name;
            this.ender = ender;
            this.online = online;
            this.store = store;
        }
    }

    private static final class Holder implements InventoryHolder {
        private final Session session;
        private Inventory inventory;

        private Holder(Session session) {
            this.session = session;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
