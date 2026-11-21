package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pala, palo, permisos y el antigrief de los terrenos.
 */
final class ClaimWorld implements Listener {
    private static final Component PLAYERS_ONLY = Component.text("Este comando solo lo puede usar un jugador.", NamedTextColor.RED);
    private static final Component DENIED = Component.text("No puedes usar este comando.", NamedTextColor.RED);
    private static final Component NONE = Component.text("Aquí no hay ningún terreno.", NamedTextColor.RED);
    private static final Component NOT_YOURS = Component.text("Este terreno no es tuyo.", NamedTextColor.RED);
    private static final Component NO_MANAGE = Component.text("No puedes cambiar los permisos de este terreno.", NamedTextColor.RED);
    private static final Component BLOCKED = Component.text("No puedes hacer eso en este terreno.", NamedTextColor.RED);
    private static final Component NO_ENTRY = Component.text("No puedes entrar a ese terreno.", NamedTextColor.RED);
    private static final Component WORLD_OFF = Component.text("En este mundo no se usan terrenos.", NamedTextColor.RED);
    private static final Component TOO_SMALL = Component.text("Ese terreno no llega al lado mínimo.", NamedTextColor.RED);
    private static final Component TOO_BIG = Component.text("Ese terreno es demasiado grande.", NamedTextColor.RED);
    private static final Component OUTSIDE = Component.text("Ese terreno se sale del borde del mundo.", NamedTextColor.RED);
    private static final Component OVERLAP = Component.text("Ese terreno se solapa con otro.", NamedTextColor.RED);
    private static final Component OCCUPIED = Component.text("Ese punto ya está dentro de un terreno.", NamedTextColor.RED);
    private static final Component NEED_BLOCKS = Component.text("No te alcanzan los claimblocks.", NamedTextColor.RED);
    private static final Component UNKNOWN = Component.text("Ese jugador no ha entrado al servidor.", NamedTextColor.RED);
    private static final Component OFFLINE = Component.text("Ese jugador no está conectado.", NamedTextColor.RED);
    private static final Component SELF = Component.text("No puedes dártela a ti mismo.", NamedTextColor.RED);
    private static final Component NOT_PET = Component.text("Mira a una mascota tuya.", NamedTextColor.RED);
    private static final Component OWNER_ALREADY = Component.text("El dueño ya puede hacer de todo aquí.", NamedTextColor.RED);
    private static final Component NO_CLAIMS = Component.text("No tienes terrenos.", NamedTextColor.YELLOW);
    private static final Component CONFIRM = Component.text(
            "Repite el comando en los próximos 30 segundos para abandonar todos tus terrenos.",
            NamedTextColor.YELLOW
    );
    private static final long CONFIRM_MILLIS = 30_000L;
    private static final long MARK_MILLIS = 60_000L;
    private static final int BORDER_STEP = 10;

    private final JavaPlugin plugin;
    private final Claims claims;
    private final Map<UUID, Marker> markers = new HashMap<>();
    private final Set<UUID> adminMode = new HashSet<>();
    private final Map<UUID, Long> confirm = new HashMap<>();
    private final Map<UUID, Long> told = new HashMap<>();
    private final Map<UUID, Show> showing = new HashMap<>();

    ClaimWorld(JavaPlugin plugin, Claims claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void disable() {
        for (UUID id : new ArrayList<>(showing.keySet())) {
            revert(id, true);
        }
    }

    boolean command(CommandSender sender, String[] args) {
        String name = args[0].toLowerCase(Locale.ROOT);
        return switch (name) {
            case "unclaim" -> {
                unclaim(sender, args);
                yield true;
            }
            case "trust" -> {
                grant(sender, args, Claims.Level.BUILD);
                yield true;
            }
            case "containertrust" -> {
                grant(sender, args, Claims.Level.CONTAINER);
                yield true;
            }
            case "accesstrust" -> {
                grant(sender, args, Claims.Level.ACCESS);
                yield true;
            }
            case "permissiontrust" -> {
                grant(sender, args, Claims.Level.PERMISSION);
                yield true;
            }
            case "untrust" -> {
                untrust(sender, args);
                yield true;
            }
            case "trustlist" -> {
                trustList(sender, args);
                yield true;
            }
            case "abandonallclaims" -> {
                abandonAll(sender, args);
                yield true;
            }
            case "givepet" -> {
                givePet(sender, args);
                yield true;
            }
            case "adminclaims" -> {
                adminClaims(sender, args);
                yield true;
            }
            case "deleteclaim" -> {
                deleteClaim(sender, args);
                yield true;
            }
            default -> false;
        };
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        boolean trust = switch (name) {
            case "trust", "containertrust", "accesstrust", "permissiontrust" -> true;
            default -> false;
        };
        if (trust || name.equals("untrust")) {
            return names(args[1], name.equals("untrust"));
        }
        if (name.equals("givepet")) {
            return names(args[1], false);
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTool(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        ItemStack item = event.getItem();
        if (block == null || item == null) {
            return;
        }
        Player player = event.getPlayer();
        if (item.getType() == Material.GOLDEN_SHOVEL) {
            stop(event);
            shovel(player, block);
        } else if (item.getType() == Material.STICK) {
            stop(event);
            stick(player, block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            Claims.Land land = claims.at(block.getLocation());
            if (land == null) {
                return;
            }
            Claims.Level needed = block.getType() == Material.FARMLAND ? Claims.Level.BUILD : Claims.Level.ACCESS;
            if (!claims.allows(player, land, needed)) {
                stop(event);
                tell(player);
            }
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Claims.Level needed = interaction(block, event.getItem());
        if (needed != null && deny(player, block.getLocation(), needed)) {
            stop(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Claims.Level needed = crop(event.getBlock().getType()) ? Claims.Level.CONTAINER : Claims.Level.BUILD;
        if (deny(event.getPlayer(), event.getBlock().getLocation(), needed)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Claims.Level needed = crop(event.getBlock().getType()) ? Claims.Level.CONTAINER : Claims.Level.BUILD;
        if (deny(event.getPlayer(), event.getBlock().getLocation(), needed)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        protectUse(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = ClaimDrops.combatant(event.getDamager());
        Entity victim = event.getEntity();
        if (victim instanceof Player player && attacker != null && !attacker.equals(player)) {
            if (claims.at(player.getLocation()) != null || claims.at(attacker.getLocation()) != null) {
                event.setCancelled(true);
            }
            return;
        }
        if (attacker != null && victim instanceof Tameable tameable) {
            UUID owner = tameable.getOwnerUniqueId();
            if (owner != null && !owner.equals(attacker.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
        Claims.Land land = claims.at(victim.getLocation());
        if (land == null) {
            return;
        }
        if (attacker == null) {
            if (!(victim instanceof Tameable tameable && tameable.getOwnerUniqueId() != null) && mobProof(victim)) {
                event.setCancelled(true);
            }
            return;
        }
        if (mobProof(victim) || victim instanceof Tameable || victim instanceof Vehicle) {
            if (!claims.allows(attacker, land, Claims.Level.BUILD)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        splash(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingering(LingeringPotionSplashEvent event) {
        ProjectileSource source = event.getEntity().getShooter();
        if (!(source instanceof Player shooter)) {
            return;
        }
        Claims.Land land = claims.at(event.getAreaEffectCloud().getLocation());
        if (land != null && !claims.allows(shooter, land, Claims.Level.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        ProjectileSource source = event.getEntity().getSource();
        if (!(source instanceof Player shooter)) {
            return;
        }
        event.getAffectedEntities().removeIf(hit -> affected(shooter, hit));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> claims.at(block.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> claims.at(block.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (claims.at(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE && event.getNewState().getType() != Material.FIRE) {
            return;
        }
        if (claims.at(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Claims.Land land = claims.at(event.getBlock().getLocation());
        if (land == null) {
            return;
        }
        if (event.getIgnitingEntity() instanceof Player player && claims.allows(player, land, Claims.Level.BUILD)) {
            return;
        }
        event.setCancelled(true);
        if (event.getIgnitingEntity() instanceof Player player) {
            tell(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        guardPiston(event, event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        guardPiston(event, event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Claims.Land to = claims.at(event.getToBlock().getLocation());
        if (to != null && to != claims.at(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Directional directional)) {
            return;
        }
        Block target = event.getBlock().getRelative(directional.getFacing());
        Claims.Land to = claims.at(target.getLocation());
        if (to != null && to != claims.at(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        Location source = location(event.getSource());
        Location destination = location(event.getDestination());
        if (source == null || destination == null) {
            return;
        }
        if (claims.at(source) != claims.at(destination)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (bucket(event.getPlayer(), event.getBlockClicked(), event.getBlockFace())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (bucket(event.getPlayer(), event.getBlockClicked(), event.getBlockFace())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (deny(event.getPlayer(), event.getEntity().getLocation(), Claims.Level.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceEntity(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && deny(player, event.getEntity().getLocation(), Claims.Level.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.FallingBlock || event.getEntity() instanceof org.bukkit.entity.TNTPrimed) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            if (deny(player, event.getBlock().getLocation(), Claims.Level.BUILD)) {
                event.setCancelled(true);
            }
            return;
        }
        if (claims.at(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWither(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_WITHER
                && claims.at(event.getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && deny(player, event.getEntity().getLocation(), Claims.Level.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            Player attacker = ClaimDrops.combatant(byEntity.getRemover());
            if (attacker != null) {
                if (deny(attacker, event.getEntity().getLocation(), Claims.Level.BUILD)) {
                    event.setCancelled(true);
                }
                return;
            }
        }
        if (claims.at(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (deny(event.getPlayer(), event.getHarvestedBlock().getLocation(), Claims.Level.CONTAINER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation(), Claims.Level.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        if (deny(event.getPlayer(), event.getBed().getLocation(), Claims.Level.ACCESS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (deny(event.getPlayer(), event.getLectern().getLocation(), Claims.Level.CONTAINER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmor(PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked().getLocation(), Claims.Level.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (deny(event.getPlayer(), event.getEntity().getLocation(), Claims.Level.CONTAINER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (deny(event.getPlayer(), event.getEntity().getLocation(), Claims.Level.CONTAINER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (deny(event.getPlayer(), event.getEntity().getLocation(), Claims.Level.CONTAINER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        Claims.Land land = claims.at(event.getTo());
        if (land != null && !claims.allows(event.getPlayer(), land, Claims.Level.ACCESS)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(NO_ENTRY);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        markers.remove(id);
        adminMode.remove(id);
        confirm.remove(id);
        told.remove(id);
        revert(id, true);
    }

    private void shovel(Player player, Block block) {
        if (!claims.enabled(block.getWorld())) {
            player.sendMessage(WORLD_OFF);
            return;
        }
        int x = block.getX();
        int z = block.getZ();
        Claims.Land here = claims.at(block.getLocation());
        Marker marker = markers.get(player.getUniqueId());
        if (marker != null && System.currentTimeMillis() > marker.expires) {
            marker = null;
            markers.remove(player.getUniqueId());
            revert(player.getUniqueId(), true);
        }
        if (marker == null) {
            if (here != null) {
                if (here.corner(x, z) && claims.canResize(player, here)) {
                    int fixedX = x == here.minX ? here.maxX : here.minX;
                    int fixedZ = z == here.minZ ? here.maxZ : here.minZ;
                    markers.put(player.getUniqueId(), new Marker(block.getWorld().getUID(), x, z, false, here, fixedX, fixedZ));
                    showDiamond(player, block);
                    player.sendMessage(Component.text("Esquina tomada. Marca la nueva esquina opuesta.", NamedTextColor.YELLOW));
                    return;
                }
                player.sendMessage(OCCUPIED);
                return;
            }
            boolean admin = adminMode.contains(player.getUniqueId()) && Claims.admin(player);
            markers.put(player.getUniqueId(), new Marker(block.getWorld().getUID(), x, z, admin, null, x, z));
            showDiamond(player, block);
            player.sendMessage(Component.text(
                    "Primera esquina en " + x + ", " + z + ". Marca la opuesta con la pala.",
                    NamedTextColor.YELLOW
            ));
            return;
        }
        if (!marker.world.equals(block.getWorld().getUID())) {
            markers.remove(player.getUniqueId());
            revert(player.getUniqueId(), true);
            player.sendMessage(Component.text("Las dos esquinas tienen que estar en el mismo mundo.", NamedTextColor.RED));
            return;
        }
        finish(player, block.getWorld(), marker, x, z);
    }

    private void finish(Player player, World world, Marker marker, int x, int z) {
        if (marker.x == x && marker.z == z) {
            player.sendMessage(Component.text("Marca la esquina opuesta.", NamedTextColor.YELLOW));
            return;
        }
        int width = Math.abs(x - marker.fixedX) + 1;
        int length = Math.abs(z - marker.fixedZ) + 1;
        if (width < claims.minimumSide() || length < claims.minimumSide()) {
            player.sendMessage(TOO_SMALL);
            return;
        }
        if (width > claims.maxSide() || length > claims.maxSide()) {
            player.sendMessage(TOO_BIG);
            return;
        }
        long area = (long) width * length;
        if (area > Integer.MAX_VALUE) {
            player.sendMessage(TOO_BIG);
            return;
        }
        if (!inside(world, x, z) || !inside(world, marker.fixedX, marker.fixedZ)) {
            player.sendMessage(OUTSIDE);
            return;
        }
        Claims.Land overlap = claims.findOverlap(world, x, z, marker.fixedX, marker.fixedZ, marker.resizing);
        if (overlap != null) {
            player.sendMessage(OVERLAP);
            return;
        }
        if (marker.resizing != null) {
            int difference = (int) area - marker.resizing.area();
            if (!marker.resizing.admin && difference > claims.remaining(marker.resizing.owner)) {
                player.sendMessage(NEED_BLOCKS);
                return;
            }
            if (!claims.resize(marker.resizing, x, z, marker.fixedX, marker.fixedZ)) {
                player.sendMessage(OVERLAP);
                return;
            }
            markers.remove(player.getUniqueId());
            if (marker.resizing.admin) {
                player.sendMessage(Component.text("Terreno ajustado a " + width + "×" + length + ".", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text(
                        "Terreno ajustado a " + width + "×" + length + ". Te quedan "
                                + claims.remaining(marker.resizing.owner) + " claimblocks.",
                        NamedTextColor.GREEN
                ));
            }
            show(player, marker.resizing);
            return;
        }
        if (marker.admin && !Claims.admin(player)) {
            markers.remove(player.getUniqueId());
            player.sendMessage(DENIED);
            return;
        }
        if (!marker.admin && area > claims.remaining(player.getUniqueId())) {
            player.sendMessage(NEED_BLOCKS);
            return;
        }
        Claims.Land land = claims.create(world, player.getUniqueId(), marker.admin, x, z, marker.fixedX, marker.fixedZ);
        markers.remove(player.getUniqueId());
        if (land.admin) {
            player.sendMessage(Component.text(
                    "Terreno de administración creado, de " + width + "×" + length + ".",
                    NamedTextColor.GREEN
            ));
        } else {
            player.sendMessage(Component.text(
                    "Terreno de " + width + "×" + length + " creado. Te quedan " + claims.remaining(player.getUniqueId()) + " claimblocks.",
                    NamedTextColor.GREEN
            ));
        }
        show(player, land);
    }

    private void stick(Player player, Location location) {
        if (!claims.enabled(location.getWorld())) {
            player.sendMessage(WORLD_OFF);
            return;
        }
        Claims.Land land = claims.at(location);
        if (land == null) {
            player.sendMessage(NONE);
            return;
        }
        show(player, land);
        player.sendMessage(Component.text("Este terreno es de " + claims.ownerName(land) + ".", NamedTextColor.YELLOW));
    }

    private void showDiamond(Player player, Block block) {
        Location spot = block.getLocation();
        revert(player.getUniqueId(), true);
        Show show = schedule(player, List.of(spot), MARK_MILLIS / 50L);
        BlockData diamond = Material.DIAMOND_BLOCK.createBlockData();
        Runnable paint = () -> {
            if (!player.isOnline() || showing.get(player.getUniqueId()) != show) {
                return;
            }
            player.sendBlockChange(spot, diamond);
        };
        plugin.getServer().getScheduler().runTaskLater(plugin, paint, 1L);
        plugin.getServer().getScheduler().runTaskLater(plugin, paint, 3L);
    }

    private void show(Player player, Claims.Land land) {
        World world = plugin.getServer().getWorld(land.world);
        if (world == null) {
            return;
        }
        int hint = player.getLocation().getBlockY() - 1;
        hint = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, hint));
        List<Location> spots = outline(world, land, hint);
        revert(player.getUniqueId(), true);
        BlockData corner = Material.GLOWSTONE.createBlockData();
        BlockData edge = Material.GOLD_BLOCK.createBlockData();
        for (Location spot : spots) {
            boolean cornerSpot = land.corner(spot.getBlockX(), spot.getBlockZ());
            player.sendBlockChange(spot, cornerSpot ? corner : edge);
        }
        schedule(player, spots, claims.borderSeconds() * 20L);
    }

    private Show schedule(Player player, List<Location> spots, long ticks) {
        Show show = new Show(spots);
        show.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (showing.get(player.getUniqueId()) == show) {
                revert(player.getUniqueId(), false);
            }
        }, ticks);
        showing.put(player.getUniqueId(), show);
        return show;
    }

    private void revert(UUID id, boolean cancelTask) {
        Show show = showing.remove(id);
        if (show == null) {
            return;
        }
        if (cancelTask && show.task != null) {
            show.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || !player.isOnline()) {
            return;
        }
        for (Location spot : show.spots) {
            if (spot.getWorld() == null) {
                continue;
            }
            player.sendBlockChange(spot, spot.getBlock().getBlockData());
        }
    }

    private List<Location> outline(World world, Claims.Land land, int hintY) {
        List<Location> spots = new ArrayList<>();
        addCorner(spots, world, land, land.minX, land.minZ, 1, 1, hintY);
        addCorner(spots, world, land, land.maxX, land.minZ, -1, 1, hintY);
        addCorner(spots, world, land, land.minX, land.maxZ, 1, -1, hintY);
        addCorner(spots, world, land, land.maxX, land.maxZ, -1, -1, hintY);
        int limitX = land.maxX - BORDER_STEP / 2;
        int limitZ = land.maxZ - BORDER_STEP / 2;
        for (int x = land.minX + BORDER_STEP; x < limitX; x += BORDER_STEP) {
            spots.add(surfaceAt(world, x, land.minZ, hintY));
            spots.add(surfaceAt(world, x, land.maxZ, hintY));
        }
        for (int z = land.minZ + BORDER_STEP; z < limitZ; z += BORDER_STEP) {
            spots.add(surfaceAt(world, land.minX, z, hintY));
            spots.add(surfaceAt(world, land.maxX, z, hintY));
        }
        return spots;
    }

    private void addCorner(List<Location> spots, World world, Claims.Land land, int x, int z, int dx, int dz, int hintY) {
        spots.add(surfaceAt(world, x, z, hintY));
        if (!land.corner(x + dx, z)) {
            spots.add(surfaceAt(world, x + dx, z, hintY));
        }
        if (!land.corner(x, z + dz)) {
            spots.add(surfaceAt(world, x, z + dz, hintY));
        }
    }

    private Location surfaceAt(World world, int x, int z, int hintY) {
        int min = world.getMinHeight();
        int max = world.getMaxHeight() - 1;
        int hint = Math.max(min, Math.min(max, hintY));
        if (visibleSurface(world, x, hint, z)) {
            return new Location(world, x, hint, z);
        }
        Block here = world.getBlockAt(x, hint, z);
        if (!open(here)) {
            for (int y = hint + 1; y <= max; y++) {
                if (visibleSurface(world, x, y, z)) {
                    return new Location(world, x, y, z);
                }
            }
        } else {
            for (int y = hint - 1; y >= min; y--) {
                if (visibleSurface(world, x, y, z)) {
                    return new Location(world, x, y, z);
                }
            }
            for (int y = hint + 1; y <= max; y++) {
                if (visibleSurface(world, x, y, z)) {
                    return new Location(world, x, y, z);
                }
            }
        }
        return new Location(world, x, hint, z);
    }

    private static boolean visibleSurface(World world, int x, int y, int z) {
        if (y >= world.getMaxHeight() - 1) {
            return false;
        }
        Block ground = world.getBlockAt(x, y, z);
        if (ground.getType().isAir()) {
            return false;
        }
        Block above = ground.getRelative(BlockFace.UP);
        if (!open(above)) {
            return false;
        }
        return !open(ground) || fluid(ground.getType());
    }

    private static boolean open(Block block) {
        Material type = block.getType();
        return type.isAir() || fluid(type) || block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    private static boolean fluid(Material type) {
        return type == Material.WATER || type == Material.LAVA;
    }

    private void unclaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /dreamers unclaim", NamedTextColor.YELLOW));
            return;
        }
        Claims.Land land = claims.at(player.getLocation());
        if (land == null) {
            player.sendMessage(NONE);
            return;
        }
        if (!claims.canResize(player, land)) {
            player.sendMessage(NOT_YOURS);
            return;
        }
        boolean admin = land.admin;
        claims.remove(land);
        if (admin) {
            player.sendMessage(Component.text("Eliminaste el terreno de administración.", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text(
                "Abandonaste tu terreno. Te quedan " + claims.remaining(player.getUniqueId()) + " claimblocks.",
                NamedTextColor.GREEN
        ));
    }

    private void deleteClaim(CommandSender sender, String[] args) {
        if (!Claims.admin(sender)) {
            sender.sendMessage(DENIED);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /dreamers deleteclaim", NamedTextColor.YELLOW));
            return;
        }
        Claims.Land land = claims.at(player.getLocation());
        if (land == null) {
            player.sendMessage(NONE);
            return;
        }
        boolean admin = land.admin;
        UUID owner = land.owner;
        String name = claims.ownerName(land);
        claims.remove(land);
        if (admin) {
            player.sendMessage(Component.text("Eliminaste el terreno de administración.", NamedTextColor.GREEN));
            return;
        }
        int left = owner == null ? 0 : claims.remaining(owner);
        player.sendMessage(Component.text("Eliminaste el terreno de " + name + ".", NamedTextColor.GREEN));
        Player online = owner == null ? null : plugin.getServer().getPlayer(owner);
        if (online != null && !online.equals(player)) {
            online.sendMessage(Component.text(
                    "Un administrador eliminó tu terreno. Te quedan " + left + " claimblocks.",
                    NamedTextColor.YELLOW
            ));
        }
    }

    private void adminClaims(CommandSender sender, String[] args) {
        if (!Claims.admin(sender)) {
            sender.sendMessage(DENIED);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /dreamers adminclaims", NamedTextColor.YELLOW));
            return;
        }
        if (adminMode.remove(player.getUniqueId())) {
            player.sendMessage(Component.text("La pala vuelve a crear terrenos normales.", NamedTextColor.GREEN));
            return;
        }
        adminMode.add(player.getUniqueId());
        player.sendMessage(Component.text("La pala ahora crea terrenos de administración.", NamedTextColor.GREEN));
    }

    private void grant(CommandSender sender, String[] args, Claims.Level level) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Uso: /dreamers " + args[0].toLowerCase(Locale.ROOT) + " <jugador|public>", NamedTextColor.YELLOW));
            return;
        }
        Claims.Land land = managed(player);
        if (land == null) {
            return;
        }
        String action = switch (level) {
            case ACCESS -> "usar puertas, botones y camas";
            case CONTAINER -> "usar contenedores y cultivos";
            case BUILD -> "construir";
            case PERMISSION -> "gestionar los permisos";
            case NONE -> "entrar";
        };
        if (args[1].equalsIgnoreCase("public")) {
            claims.setPublic(land, level);
            player.sendMessage(Component.text("Todos pueden " + action + " en este terreno.", NamedTextColor.GREEN));
            return;
        }
        UUID target = resolve(args[1]);
        if (target == null) {
            player.sendMessage(UNKNOWN);
            return;
        }
        if (!land.admin && target.equals(land.owner)) {
            player.sendMessage(OWNER_ALREADY);
            return;
        }
        claims.setTrust(land, target, level);
        player.sendMessage(Component.text(claims.name(target) + " ahora puede " + action + " en este terreno.", NamedTextColor.GREEN));
    }

    private void untrust(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Uso: /dreamers untrust <jugador|public|all>", NamedTextColor.YELLOW));
            return;
        }
        Claims.Land land = managed(player);
        if (land == null) {
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            claims.clearTrust(land);
            player.sendMessage(Component.text("Quitaste todos los permisos de este terreno.", NamedTextColor.GREEN));
            return;
        }
        if (args[1].equalsIgnoreCase("public")) {
            claims.setPublic(land, Claims.Level.NONE);
            player.sendMessage(Component.text("Todos ya no tienen permisos en este terreno.", NamedTextColor.GREEN));
            return;
        }
        UUID target = resolve(args[1]);
        if (target == null) {
            player.sendMessage(UNKNOWN);
            return;
        }
        claims.setTrust(land, target, Claims.Level.NONE);
        player.sendMessage(Component.text(claims.name(target) + " ya no tiene permisos en este terreno.", NamedTextColor.GREEN));
    }

    private void trustList(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /dreamers trustlist", NamedTextColor.YELLOW));
            return;
        }
        Claims.Land land = claims.at(player.getLocation());
        if (land == null) {
            player.sendMessage(NONE);
            return;
        }
        player.sendMessage(Component.text("Terreno de " + claims.ownerName(land) + ".", NamedTextColor.YELLOW));
        Claims.Level everyone = claims.publicOf(land);
        Map<UUID, Claims.Level> trust = claims.trustOf(land);
        if (everyone == Claims.Level.NONE && trust.isEmpty()) {
            player.sendMessage(Component.text("Nadie más tiene permiso.", NamedTextColor.GRAY));
            return;
        }
        if (everyone != Claims.Level.NONE) {
            player.sendMessage(Component.text("Todos: " + everyone.label(), NamedTextColor.GRAY));
        }
        trust.entrySet().stream()
                .sorted((left, right) -> claims.name(left.getKey()).compareToIgnoreCase(claims.name(right.getKey())))
                .forEach(entry -> player.sendMessage(Component.text(
                        claims.name(entry.getKey()) + ": " + entry.getValue().label(),
                        NamedTextColor.GRAY
                )));
    }

    private void abandonAll(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /dreamers abandonallclaims", NamedTextColor.YELLOW));
            return;
        }
        if (claims.ownedCount(player.getUniqueId()) == 0) {
            confirm.remove(player.getUniqueId());
            player.sendMessage(NO_CLAIMS);
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = confirm.get(player.getUniqueId());
        if (previous == null || now - previous > CONFIRM_MILLIS) {
            confirm.put(player.getUniqueId(), now);
            player.sendMessage(CONFIRM);
            return;
        }
        confirm.remove(player.getUniqueId());
        int count = claims.abandonOwned(player.getUniqueId());
        player.sendMessage(Component.text(
                "Abandonaste " + count + " terrenos. Te quedan " + claims.remaining(player.getUniqueId()) + " claimblocks.",
                NamedTextColor.GREEN
        ));
    }

    private void givePet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Uso: /dreamers givepet <jugador>", NamedTextColor.YELLOW));
            return;
        }
        RayTraceResult trace = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                6,
                entity -> entity instanceof Tameable && entity != player
        );
        Entity looking = trace == null ? null : trace.getHitEntity();
        if (!(looking instanceof Tameable tameable) || tameable.getOwnerUniqueId() == null
                || !tameable.getOwnerUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(NOT_PET);
            return;
        }
        Player recipient = online(args[1]);
        if (recipient == null) {
            player.sendMessage(OFFLINE);
            return;
        }
        if (recipient.equals(player)) {
            player.sendMessage(SELF);
            return;
        }
        tameable.setOwner(recipient);
        player.sendMessage(Component.text("Le diste tu mascota a " + recipient.getName() + ".", NamedTextColor.GREEN));
        recipient.sendMessage(Component.text(player.getName() + " te dio una mascota.", NamedTextColor.YELLOW));
    }

    private Claims.Land managed(Player player) {
        Claims.Land land = claims.at(player.getLocation());
        if (land == null) {
            player.sendMessage(NONE);
            return null;
        }
        if (!claims.canManage(player, land)) {
            player.sendMessage(NO_MANAGE);
            return null;
        }
        return land;
    }

    private UUID resolve(String name) {
        OfflinePlayer known = KnownPlayers.find(plugin.getServer(), name);
        if (known != null) {
            return known.getUniqueId();
        }
        return claims.findPlayer(name);
    }

    private Player online(String name) {
        Player exact = plugin.getServer().getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private List<String> names(String prefix, boolean all) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> options = new LinkedHashSet<>();
        if ("public".startsWith(lower)) {
            options.add("public");
        }
        if (all && "all".startsWith(lower)) {
            options.add("all");
        }
        options.addAll(KnownPlayers.names(plugin.getServer(), prefix));
        return List.copyOf(options);
    }

    private boolean deny(Player player, Location location, Claims.Level needed) {
        Claims.Land land = claims.at(location);
        if (land == null || claims.allows(player, land, needed)) {
            return false;
        }
        tell(player);
        return true;
    }

    private void tell(Player player) {
        long now = System.currentTimeMillis();
        Long last = told.get(player.getUniqueId());
        if (last != null && now - last < 1500) {
            return;
        }
        told.put(player.getUniqueId(), now);
        player.sendMessage(BLOCKED);
    }

    private void protectUse(Player player, Entity entity, Cancellable event) {
        if (entity instanceof Monster && !(entity instanceof Tameable)) {
            return;
        }
        Claims.Land land = claims.at(entity.getLocation());
        if (land == null) {
            return;
        }
        Claims.Level needed = Claims.Level.BUILD;
        if (entity instanceof AbstractVillager || entity instanceof Animals || entity instanceof Tameable || entity instanceof Vehicle) {
            needed = Claims.Level.CONTAINER;
        }
        if (!claims.allows(player, land, needed)) {
            event.setCancelled(true);
            tell(player);
        }
    }

    private void splash(PotionSplashEvent event) {
        ProjectileSource source = event.getPotion().getShooter();
        if (!(source instanceof Player shooter)) {
            return;
        }
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (affected(shooter, hit)) {
                event.setIntensity(hit, 0);
            }
        }
    }

    private boolean affected(Player shooter, Entity hit) {
        if (hit instanceof Player target) {
            if (target.equals(shooter)) {
                return false;
            }
            return claims.at(shooter.getLocation()) != null || claims.at(target.getLocation()) != null;
        }
        Claims.Land land = claims.at(hit.getLocation());
        if (land == null) {
            return hit instanceof Tameable tameable && tameable.getOwnerUniqueId() != null
                    && !tameable.getOwnerUniqueId().equals(shooter.getUniqueId());
        }
        if (hit instanceof Tameable tameable && tameable.getOwnerUniqueId() != null
                && !tameable.getOwnerUniqueId().equals(shooter.getUniqueId())) {
            return true;
        }
        return (mobProof(hit) || hit instanceof Vehicle) && !claims.allows(shooter, land, Claims.Level.BUILD);
    }

    private static boolean mobProof(Entity victim) {
        if (victim instanceof Tameable tameable && tameable.getOwnerUniqueId() != null) {
            return false;
        }
        return victim instanceof Animals || victim instanceof AbstractVillager
                || victim instanceof Hanging || victim instanceof ArmorStand || victim instanceof Vehicle;
    }

    private void guardPiston(Cancellable event, Block piston, BlockFace direction, List<Block> blocks) {
        Claims.Land origin = claims.at(piston.getLocation());
        for (Block block : blocks) {
            if (claims.at(block.getLocation()) != origin
                    || claims.at(block.getRelative(direction).getLocation()) != origin) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean bucket(Player player, Block clicked, BlockFace face) {
        return deny(player, clicked.getLocation(), Claims.Level.BUILD)
                || deny(player, clicked.getRelative(face).getLocation(), Claims.Level.BUILD);
    }

    private static Location location(Inventory inventory) {
        return inventory.getLocation();
    }

    private Claims.Level interaction(Block block, ItemStack item) {
        Material type = block.getType();
        if (crop(type)) {
            return Claims.Level.CONTAINER;
        }
        if (access(type)) {
            return Claims.Level.ACCESS;
        }
        if (sign(type) || redstone(type) || type == Material.CAKE || Tag.FLOWER_POTS.isTagged(type) || Tag.CANDLES.isTagged(type)) {
            return Claims.Level.BUILD;
        }
        if (container(type)) {
            return Claims.Level.CONTAINER;
        }
        if (item != null) {
            Material held = item.getType();
            if (held == Material.BONE_MEAL || held == Material.BRUSH
                    || Tag.ITEMS_HOES.isTagged(held) || Tag.ITEMS_AXES.isTagged(held)) {
                return Claims.Level.BUILD;
            }
        }
        if (block.getState() instanceof TileState) {
            return Claims.Level.BUILD;
        }
        return null;
    }

    private static boolean crop(Material type) {
        return Tag.CROPS.isTagged(type)
                || type == Material.PUMPKIN
                || type == Material.MELON
                || type == Material.SUGAR_CANE
                || type == Material.CACTUS
                || type == Material.BAMBOO
                || type == Material.BAMBOO_SAPLING
                || type == Material.SWEET_BERRY_BUSH
                || type == Material.NETHER_WART
                || type == Material.COCOA
                || type == Material.KELP
                || type == Material.KELP_PLANT
                || type == Material.CAVE_VINES
                || type == Material.CAVE_VINES_PLANT
                || type == Material.PITCHER_CROP
                || type == Material.TORCHFLOWER_CROP;
    }

    private static boolean access(Material type) {
        return Tag.DOORS.isTagged(type)
                || Tag.TRAPDOORS.isTagged(type)
                || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type)
                || Tag.PRESSURE_PLATES.isTagged(type)
                || Tag.BEDS.isTagged(type)
                || type == Material.LEVER
                || type == Material.TRIPWIRE
                || type == Material.BELL
                || type == Material.RESPAWN_ANCHOR;
    }

    private static boolean container(Material type) {
        if (Tag.SHULKER_BOXES.isTagged(type) || Tag.ANVIL.isTagged(type) || Tag.CAMPFIRES.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL, ENDER_CHEST, HOPPER, DROPPER, DISPENSER,
                    FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, LECTERN, CRAFTER,
                    DECORATED_POT, CHISELED_BOOKSHELF, CRAFTING_TABLE, ENCHANTING_TABLE,
                    GRINDSTONE, SMITHING_TABLE, LOOM, CARTOGRAPHY_TABLE, STONECUTTER,
                    BEACON, COMPOSTER, JUKEBOX, BEEHIVE, BEE_NEST -> true;
            default -> false;
        };
    }

    private static boolean sign(Material type) {
        return Tag.ALL_SIGNS.isTagged(type);
    }

    private static boolean redstone(Material type) {
        return type == Material.REPEATER
                || type == Material.COMPARATOR
                || type == Material.DAYLIGHT_DETECTOR
                || type == Material.NOTE_BLOCK
                || type == Material.REDSTONE_WIRE
                || type == Material.TRIPWIRE_HOOK
                || type == Material.SCULK_SENSOR
                || type == Material.CALIBRATED_SCULK_SENSOR
                || type == Material.SCULK_SHRIEKER;
    }

    private static boolean inside(World world, int x, int z) {
        return world.getWorldBorder().isInside(new Location(world, x + 0.5, world.getMinHeight(), z + 0.5));
    }

    private static void stop(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private static final class Marker {
        private final UUID world;
        private final int x;
        private final int z;
        private final long expires;
        private final boolean admin;
        private final Claims.Land resizing;
        private final int fixedX;
        private final int fixedZ;

        private Marker(UUID world, int x, int z, boolean admin, Claims.Land resizing, int fixedX, int fixedZ) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.expires = System.currentTimeMillis() + MARK_MILLIS;
            this.admin = admin;
            this.resizing = resizing;
            this.fixedX = fixedX;
            this.fixedZ = fixedZ;
        }
    }

    private static final class Show {
        private final List<Location> spots;
        private BukkitTask task;

        private Show(List<Location> spots) {
            this.spots = spots;
        }
    }
}
