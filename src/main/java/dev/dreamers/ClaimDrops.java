package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Combate entre jugadores, muerte si sales a tiempo, y drops que solo recoge el dueño.
 */
final class ClaimDrops implements Listener {
    private static final Component PLAYERS_ONLY = Component.text("Este comando solo lo puede usar un jugador.", NamedTextColor.RED);
    private static final Component NONE = Component.text("No tienes drops protegidos.", NamedTextColor.YELLOW);
    private static final Component UNLOCKED = Component.text("Los demás ya pueden recoger tus drops.", NamedTextColor.GREEN);

    private final JavaPlugin plugin;
    private final Claims claims;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, UUID> opponent = new HashMap<>();
    private final Set<UUID> forcing = new HashSet<>();

    ClaimDrops(JavaPlugin plugin, Claims claims) {
        this.plugin = plugin;
        this.claims = claims;
        this.ownerKey = new NamespacedKey(plugin, "drop_owner");
    }

    void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    boolean command(CommandSender sender, String[] args) {
        if (!args[0].equalsIgnoreCase("unlockdrops")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /dreamers unlockdrops", NamedTextColor.YELLOW));
            return true;
        }
        int unlocked = unlock(player.getUniqueId());
        player.sendMessage(unlocked == 0 ? NONE : UNLOCKED);
        return true;
    }

    static Player combatant(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        return null;
    }

    static boolean participating(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = combatant(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (!participating(victim) || !participating(attacker)) {
            return;
        }
        long until = System.currentTimeMillis() + claims.combatSeconds() * 1000L;
        combat.put(victim.getUniqueId(), until);
        combat.put(attacker.getUniqueId(), until);
        opponent.put(victim.getUniqueId(), attacker.getUniqueId());
        opponent.put(attacker.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        combat.remove(id);
        opponent.remove(id);
        List<ItemStack> expected = copies(event.getDrops());
        if (expected.isEmpty()) {
            return;
        }
        Location location = player.getLocation().clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> mark(location, id, expected));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player && forcing.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!fighting(id)) {
            combat.remove(id);
            opponent.remove(id);
            return;
        }
        combat.remove(id);
        UUID killerId = opponent.remove(id);
        List<ItemStack> snapshot = snapshot(player);
        Location location = player.getLocation().clone();
        forcing.add(id);
        try {
            Player killer = killerId == null ? null : plugin.getServer().getPlayer(killerId);
            if (killer != null) {
                player.setKiller(killer);
            }
            if (!player.isDead() && player.getHealth() > 0) {
                player.setHealth(0);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo aplicar la muerte por salida de " + player.getName() + ".");
        } finally {
            forcing.remove(id);
        }
        if (hasItems(player)) {
            dropAll(player, location);
            return;
        }
        if (snapshot.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            mark(location, id, snapshot);
            if (ownedNearby(location, id) == 0) {
                dropStacks(location, id, snapshot);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        UUID owner = ownerOf(event.getItem());
        if (owner == null) {
            return;
        }
        if (event.getEntity() instanceof Player player && player.getUniqueId().equals(owner)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (ownerOf(event.getItem()) != null) {
            event.setCancelled(true);
        }
    }

    private boolean fighting(UUID player) {
        Long until = combat.get(player);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() <= until) {
            return true;
        }
        combat.remove(player);
        opponent.remove(player);
        return false;
    }

    private int unlock(UUID owner) {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!owner.equals(ownerOf(item))) {
                    continue;
                }
                clear(item);
                count++;
            }
        }
        return count;
    }

    private int mark(Location location, UUID owner, List<ItemStack> expected) {
        World world = location.getWorld();
        if (world == null || expected.isEmpty()) {
            return 0;
        }
        List<ItemStack> pending = new ArrayList<>(expected);
        int tagged = 0;
        for (Entity entity : world.getNearbyEntities(location, 8, 8, 8, candidate -> candidate instanceof Item)) {
            Item item = (Item) entity;
            if (ownerOf(item) != null) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            for (int index = 0; index < pending.size(); index++) {
                if (!pending.get(index).isSimilar(stack)) {
                    continue;
                }
                tag(item, owner);
                pending.remove(index);
                tagged++;
                break;
            }
        }
        return tagged;
    }

    private int ownedNearby(Location location, UUID owner) {
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (Entity entity : world.getNearbyEntities(location, 8, 8, 8, candidate -> candidate instanceof Item)) {
            if (owner.equals(ownerOf((Item) entity))) {
                count++;
            }
        }
        return count;
    }

    private void dropAll(Player player, Location location) {
        List<ItemStack> stacks = snapshot(player);
        int experience = player.getTotalExperience();
        player.getInventory().clear();
        player.setLevel(0);
        player.setExp(0);
        player.setTotalExperience(0);
        dropStacks(location, player.getUniqueId(), stacks);
        if (experience > 0 && location.getWorld() != null) {
            location.getWorld().spawn(location, ExperienceOrb.class, orb -> orb.setExperience(Math.min(experience, 100)));
        }
    }

    private void dropStacks(Location location, UUID owner, List<ItemStack> stacks) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            tag(world.dropItemNaturally(location, stack.clone()), owner);
        }
    }

    private static List<ItemStack> snapshot(Player player) {
        return copies(java.util.Arrays.asList(player.getInventory().getContents()));
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                copies.add(stack.clone());
            }
        }
        return copies;
    }

    private static boolean hasItems(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    private void tag(Item item, UUID owner) {
        String id = owner.toString();
        item.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, id);
        ItemStack stack = item.getItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        item.setItemStack(stack);
    }

    private void clear(Item item) {
        item.getPersistentDataContainer().remove(ownerKey);
        ItemStack stack = item.getItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(ownerKey);
        stack.setItemMeta(meta);
        item.setItemStack(stack);
    }

    private UUID ownerOf(Item item) {
        String raw = null;
        ItemMeta meta = item.getItemStack().getItemMeta();
        if (meta != null) {
            raw = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        }
        if (raw == null) {
            raw = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        }
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
