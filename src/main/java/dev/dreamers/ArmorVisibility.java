package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Oculta casco, pechera, pantalones y botas mientras siguen equipados.
 * Cualquier otra cosa en esos huecos se queda visible. Al quitar una pieza, recupera su modelo.
 * Al recibir daño, la armadura se ve durante cinco segundos.
 */
final class ArmorVisibility implements Listener {
    private static final String USAGE = "/dreamers armor <true|false>";
    private static final String ADMIN_USAGE = "/dreamers armor <true|false> [jugador]";
    private static final int REVEAL_SECONDS = 5;
    private static final long REVEAL_TICKS = REVEAL_SECONDS * 20L;
    private static final String RESET = "*";
    private static final Key HIDDEN_ASSET = Key.key("minecraft", "air");
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };
    private static final List<String> BOOLEANS = List.of("true", "false");
    private static final List<String> COMMENTS = List.of(
            "Visibilidad de la armadura. true se ve y false se oculta.",
            "/dreamers armor <true|false> cambia la propia. Un operador puede indicar el jugador.",
            "Con la armadura oculta, al recibir daño se ve durante 5 segundos."
    );
    private static final Component OTHERS = Component.text(
            "No puedes cambiar la armadura de otro jugador.",
            NamedTextColor.RED
    );
    private static final Component UNKNOWN = Component.text(
            "Ese jugador no ha entrado al servidor.",
            NamedTextColor.RED
    );

    private final JavaPlugin plugin;
    private final Map<UUID, Boolean> chosen = new HashMap<>();
    private final Map<UUID, BukkitTask> reveals = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private NamespacedKey marker;
    private BukkitTask refresher;
    private boolean running;
    private boolean applying;

    ArmorVisibility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        marker = new NamespacedKey(plugin, "hidden_armor");
        load();
        running = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refresher = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20L, 20L);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sanitize(player, false);
        }
    }

    void disable() {
        running = false;
        if (refresher != null) {
            refresher.cancel();
            refresher = null;
        }
        cancelReveals();
        pending.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sanitize(player, true);
        }
    }

    void reload() {
        cancelReveals();
        pending.clear();
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sanitize(player, false);
        }
    }

    void command(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage(sender);
            return;
        }
        Boolean visible = bool(args[1]);
        if (visible == null) {
            usage(sender);
            return;
        }
        if (args.length == 3 && !operator(sender)) {
            sender.sendMessage(OTHERS);
            return;
        }
        if (!(sender instanceof Player) && args.length < 3) {
            usage(sender);
            return;
        }
        OfflinePlayer target;
        if (args.length == 3) {
            target = find(args[2]);
            if (target == null) {
                sender.sendMessage(UNKNOWN);
                return;
            }
        } else {
            target = (Player) sender;
        }
        store(target, visible);
        report(sender, target, visible);
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return starting(args[1], BOOLEANS);
        }
        if (args.length == 3 && operator(sender)) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return sender.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDamage() <= 0 || !hidden(player)) {
            return;
        }
        reveal(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipment(EntityEquipmentChangedEvent event) {
        if (applying || !(event.getEntity() instanceof Player player) || !armorChanged(event.getEquipmentChanges())) {
            return;
        }
        later(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item dropped = event.getItemDrop();
        ItemStack stack = dropped.getItemStack();
        if (restore(stack)) {
            dropped.setItemStack(stack);
        }
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        if (restore(stack)) {
            event.getItem().setItemStack(stack);
        }
        if (event.getEntity() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            later(event.getEntity());
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            restore(drop);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rememberName(event.getPlayer());
        later(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelReveal(event.getPlayer().getUniqueId());
        pending.remove(event.getPlayer().getUniqueId());
        sanitize(event.getPlayer(), true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        later(event.getPlayer());
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.isConfigurationSection("armor.players")) {
            config.createSection("armor.players");
            config.setComments("armor", COMMENTS);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
        chosen.clear();
        ConfigurationSection section = config.getConfigurationSection("armor.players");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                if (!section.contains(id + ".visible")) {
                    continue;
                }
                chosen.put(UUID.fromString(id), section.getBoolean(id + ".visible"));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Visibilidad de armadura ignorada, el id no es un UUID: " + id);
            }
        }
    }

    private void store(OfflinePlayer target, boolean visible) {
        chosen.put(target.getUniqueId(), visible);
        FileConfiguration config = plugin.getConfig();
        String path = "armor.players." + target.getUniqueId();
        if (target.getName() != null) {
            config.set(path + ".name", target.getName());
        }
        config.set(path + ".visible", visible);
        plugin.saveConfig();
        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            cancelReveal(online.getUniqueId());
            sanitize(online, false);
        }
    }

    private void rememberName(Player player) {
        if (!chosen.containsKey(player.getUniqueId())) {
            return;
        }
        String path = "armor.players." + player.getUniqueId() + ".name";
        if (player.getName().equals(plugin.getConfig().getString(path))) {
            return;
        }
        plugin.getConfig().set(path, player.getName());
        plugin.saveConfig();
    }

    private void report(CommandSender sender, OfflinePlayer target, boolean visible) {
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        String name = target.getName() == null ? "ese jugador" : target.getName();
        if (self && visible) {
            sender.sendMessage(Component.text("Tu armadura ahora es visible.", NamedTextColor.GREEN));
            return;
        }
        if (self) {
            sender.sendMessage(Component.text(
                    "Tu armadura ahora está oculta. Al recibir daño se ve durante " + REVEAL_SECONDS + " segundos.",
                    NamedTextColor.GREEN
            ));
            return;
        }
        if (!target.isOnline()) {
            sender.sendMessage(Component.text(
                    "La armadura de " + name + " quedó guardada y se aplicará cuando entre.",
                    NamedTextColor.GREEN
            ));
            return;
        }
        String state = visible
                ? "ahora es visible."
                : "ahora está oculta. Al recibir daño se ve durante " + REVEAL_SECONDS + " segundos.";
        sender.sendMessage(Component.text("La armadura de " + name + " " + state, NamedTextColor.GREEN));
    }

    private void reveal(Player player) {
        cancelReveal(player.getUniqueId());
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reveals.remove(player.getUniqueId());
            if (running && player.isOnline() && hidden(player)) {
                sanitize(player, false);
            }
        }, REVEAL_TICKS);
        reveals.put(player.getUniqueId(), task);
        sanitize(player, false);
    }

    private void later(Player player) {
        if (!running || applying || !player.isOnline() || !pending.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (running && player.isOnline()) {
                sanitize(player, false);
            }
        });
    }

    private void sweep() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sanitize(player, false);
        }
    }

    private void sanitize(Player player, boolean forceVisible) {
        if (marker == null) {
            return;
        }
        applying = true;
        try {
            boolean conceal = !forceVisible && concealed(player);
            PlayerInventory inventory = player.getInventory();
            restoreStorage(inventory);
            ItemStack offhand = inventory.getItemInOffHand();
            if (restore(offhand)) {
                inventory.setItemInOffHand(offhand);
            }
            ItemStack cursor = player.getItemOnCursor();
            if (restore(cursor)) {
                player.setItemOnCursor(cursor);
            }
            Inventory open = player.getOpenInventory().getTopInventory();
            if (open != inventory) {
                restoreLoose(open);
            }
            restoreLoose(player.getEnderChest());
            for (EquipmentSlot slot : ARMOR) {
                ItemStack worn = inventory.getItem(slot);
                if (worn == null || worn.isEmpty()) {
                    continue;
                }
                boolean changed = conceal && wearableArmor(worn.getType()) ? hide(worn) : restore(worn);
                if (changed) {
                    inventory.setItem(slot, worn);
                }
            }
        } finally {
            applying = false;
        }
    }

    private boolean hide(ItemStack item) {
        if (marked(item)) {
            Equippable current = item.getData(DataComponentTypes.EQUIPPABLE);
            if (current == null || HIDDEN_ASSET.equals(current.assetId())) {
                return false;
            }
            item.setData(DataComponentTypes.EQUIPPABLE, current.toBuilder().assetId(HIDDEN_ASSET).build());
            return true;
        }
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            return false;
        }
        String stored = item.isDataOverridden(DataComponentTypes.EQUIPPABLE)
                ? asset(equippable.assetId())
                : RESET;
        item.editPersistentDataContainer(data -> data.set(marker, PersistentDataType.STRING, stored));
        item.setData(DataComponentTypes.EQUIPPABLE, equippable.toBuilder().assetId(HIDDEN_ASSET).build());
        return true;
    }

    private boolean restore(ItemStack item) {
        if (!marked(item)) {
            return false;
        }
        String stored = item.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        item.editPersistentDataContainer(data -> data.remove(marker));
        if (stored == null || RESET.equals(stored)) {
            item.resetData(DataComponentTypes.EQUIPPABLE);
            return true;
        }
        Equippable current = item.getData(DataComponentTypes.EQUIPPABLE);
        if (current == null) {
            return true;
        }
        Key original = stored.isEmpty() ? null : key(stored);
        if (!stored.isEmpty() && original == null) {
            item.resetData(DataComponentTypes.EQUIPPABLE);
            return true;
        }
        item.setData(DataComponentTypes.EQUIPPABLE, current.toBuilder().assetId(original).build());
        return true;
    }

    private boolean marked(ItemStack item) {
        return item != null
                && !item.isEmpty()
                && marker != null
                && item.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }

    private void restoreStorage(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (restore(item)) {
                inventory.setItem(index, item);
            }
        }
    }

    private void restoreLoose(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (restore(item)) {
                inventory.setItem(index, item);
            }
        }
    }

    private boolean hidden(Player player) {
        return Boolean.FALSE.equals(chosen.get(player.getUniqueId()));
    }

    private boolean concealed(Player player) {
        return hidden(player) && !reveals.containsKey(player.getUniqueId());
    }

    private void cancelReveals() {
        for (BukkitTask task : reveals.values()) {
            task.cancel();
        }
        reveals.clear();
    }

    private void cancelReveal(UUID player) {
        BukkitTask task = reveals.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    private void usage(CommandSender sender) {
        String text = operator(sender) ? ADMIN_USAGE : USAGE;
        sender.sendMessage(Component.text("Uso: " + text, NamedTextColor.YELLOW));
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
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
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

    private static boolean operator(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp();
    }

    private static boolean wearableArmor(Material type) {
        String name = type.name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    private static boolean armorChanged(Map<EquipmentSlot, ?> changes) {
        for (EquipmentSlot slot : ARMOR) {
            if (changes.containsKey(slot)) {
                return true;
            }
        }
        return false;
    }

    private static String asset(Key key) {
        return key == null ? "" : key.asString();
    }

    private static Key key(String stored) {
        if (stored.isEmpty()) {
            return null;
        }
        int colon = stored.indexOf(':');
        if (colon <= 0 || colon >= stored.length() - 1) {
            return null;
        }
        return Key.key(stored.substring(0, colon), stored.substring(colon + 1));
    }

    private static Boolean bool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static List<String> starting(String prefix, List<String> options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
