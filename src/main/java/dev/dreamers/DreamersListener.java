package dev.dreamers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.translation.GlobalTranslator;
import io.papermc.paper.registry.keys.tags.EntityTypeTagKeys;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DreamersListener implements Listener {
    private static final Sound DEATH_SOUND = Sound.sound(
            Key.key("dreamers", "dreamers.effect_fortnite"),
            Sound.Source.AMBIENT,
            1f,
            1f
    );
    private static final Sound BELL_SOUND = Sound.sound(
            Key.key("dreamers", "dreamers.effect_bell"),
            Sound.Source.PLAYER,
            1f,
            1f
    );
    private static final Sound RESPAWN_SOUND = Sound.sound(
            Key.key("dreamers", "dreamers.effect_rbd"),
            Sound.Source.NEUTRAL,
            1f,
            1f
    );
    private static final Sound DREAMER_SOUND = Sound.sound(
            Key.key("dreamers", "dreamers.totem"),
            Sound.Source.PLAYER,
            1f,
            1f
    );
    private static final Sound UNDEAD_SOUND = Sound.sound(
            Key.key("minecraft", "item.totem.use"),
            Sound.Source.PLAYER,
            1f,
            0.75f
    );
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(500),
            Duration.ofMillis(3500),
            Duration.ofMillis(1000)
    );

    private final JavaPlugin plugin;
    private final Map<UUID, DamageSource> lastDamage = new ConcurrentHashMap<>();

    DreamersListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity.isDead()) {
            return;
        }
        String type = event.getDamageSource().getDamageType().getKey().getKey();
        if (type.equals("out_of_world") || type.equals("generic_kill")) {
            return;
        }
        if (entity.getHealth() + entity.getAbsorptionAmount() > event.getFinalDamage()) {
            return;
        }

        Held held = heldTotem(entity);
        if (held != null) {
            DreamerTotem.Kind kind = DreamerTotem.kind(held.totem());
            if (kind == DreamerTotem.Kind.PLAIN || kind == DreamerTotem.Kind.NUTRI) {
                return;
            }
            event.setCancelled(true);
            consume(entity, held.slot());
            activate(entity, kind, held.totem(), event.getDamageSource());
            return;
        }

        if (!(entity instanceof Player player)) {
            return;
        }
        ItemStack[] storage = player.getInventory().getStorageContents();
        int slot = -1;
        for (int index = 0; index < storage.length; index++) {
            if (DreamerTotem.isDreamer(storage[index])) {
                slot = index;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        ItemStack totem = storage[slot].clone();
        event.setCancelled(true);
        consumeStorage(player, slot);
        activate(player, DreamerTotem.Kind.DREAMER, totem, event.getDamageSource());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastDamage.put(player.getUniqueId(), event.getDamageSource());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        lastDamage.remove(player.getUniqueId());

        Component subtitle = event.deathMessage() == null ? Component.empty() : event.deathMessage();
        Title title = Title.title(
                Component.text("Fallen Player", NamedTextColor.RED),
                subtitle,
                TITLE_TIMES
        );
        for (Player online : player.getServer().getOnlinePlayers()) {
            online.showTitle(title);
            online.playSound(DEATH_SOUND, Sound.Emitter.self());
        }
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isCancelled()) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null || player.getEquipment() == null) {
            return;
        }
        ItemStack totem = player.getEquipment().getItem(hand);
        DreamerTotem.Kind kind = DreamerTotem.kind(totem);
        announce(player, totem, lastDamage.remove(player.getUniqueId()));
        if (kind == DreamerTotem.Kind.PLAIN) {
            player.playSound(BELL_SOUND, Sound.Emitter.self());
        } else if (kind == DreamerTotem.Kind.NUTRI) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 4));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUndeadSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !isZombieOrSkeleton(entity)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= 0.15) {
            return;
        }
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItem(EquipmentSlot.OFF_HAND, DreamerTotem.undead());
        equipment.setDropChance(EquipmentSlot.OFF_HAND, 0f);
    }

    @EventHandler
    public void onUndeadDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !isZombieOrSkeleton(entity)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < 0.05) {
            event.getDrops().add(DreamerTotem.undead());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.playSound(RESPAWN_SOUND, Sound.Emitter.self());
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        DreamerTotem.discover(event.getPlayer());
        DreamerTotem.refreshCarried(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDamage.remove(event.getPlayer().getUniqueId());
    }

    private void activate(LivingEntity entity, DreamerTotem.Kind kind, ItemStack totem, DamageSource source) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        double restored = maxHealth == null ? 1.0 : Math.min(1.0, maxHealth.getValue());
        if (restored > 0) {
            entity.setHealth(restored);
        }
        entity.setAbsorptionAmount(0);
        entity.clearActivePotionEffects();
        if (kind == DreamerTotem.Kind.UNDEAD && entity instanceof Player player) {
            DreamerTotem.applyUndeadPlayerEffects(player);
        } else {
            DreamerTotem.applyStandardEffects(entity);
        }
        totemParticles(entity);
        if (entity instanceof Player player) {
            player.incrementStatistic(Statistic.USE_ITEM, Material.TOTEM_OF_UNDYING);
            announce(player, totem, source);
            player.playSound(kind == DreamerTotem.Kind.UNDEAD ? UNDEAD_SOUND : DREAMER_SOUND, Sound.Emitter.self());
        } else {
            Sound sound = kind == DreamerTotem.Kind.UNDEAD ? UNDEAD_SOUND : DREAMER_SOUND;
            entity.getWorld().playSound(sound, entity.getLocation());
        }
        if (kind == DreamerTotem.Kind.UNDEAD && isUndead(entity)) {
            witherAround(entity);
        }
    }

    private void witherAround(LivingEntity source) {
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (ticks++ >= 20 || !source.isValid()) {
                    cancel();
                    return;
                }
                for (LivingEntity nearby : source.getWorld().getNearbyLivingEntities(source.getLocation(), 1.5, 1.5, 1.5)) {
                    if (nearby.getUniqueId().equals(source.getUniqueId())) {
                        continue;
                    }
                    nearby.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static void totemParticles(LivingEntity entity) {
        entity.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING,
                entity.getLocation().add(0, 1, 0),
                80,
                0.5,
                0.8,
                0.5,
                0.2
        );
    }

    private static void announce(Player player, ItemStack totem, DamageSource source) {
        Component message = Component.text()
                .append(player.displayName().color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .append(totemAction(totem))
                .append(Component.space())
                .append(Component.text("(", NamedTextColor.YELLOW))
                .append(TotemCauses.describe(source))
                .append(Component.text(")", NamedTextColor.YELLOW))
                .build();
        player.getServer().sendMessage(message);
        Bukkit.getPluginManager().callEvent(new PlayerTotemAlertEvent(player, plain(message)));
    }

    private static String plain(Component component) {
        Component rendered = GlobalTranslator.render(component, Locale.forLanguageTag("es-ES"));
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }

    private static Component totemAction(ItemStack totem) {
        Component customName = totem.getType() == Material.TOTEM_OF_UNDYING
                ? totem.getData(DataComponentTypes.CUSTOM_NAME)
                : null;
        if (customName == null) {
            return Component.text(" toteó pótem!", NamedTextColor.YELLOW);
        }
        return Component.text(" consumió ", NamedTextColor.YELLOW)
                .append(customName)
                .append(Component.text("!", NamedTextColor.YELLOW));
    }

    private static boolean isUndead(LivingEntity entity) {
        return Registry.ENTITY_TYPE.getTagValues(EntityTypeTagKeys.UNDEAD).contains(entity.getType());
    }

    private static boolean isZombieOrSkeleton(LivingEntity entity) {
        return Registry.ENTITY_TYPE.getTagValues(EntityTypeTagKeys.ZOMBIES).contains(entity.getType())
                || Registry.ENTITY_TYPE.getTagValues(EntityTypeTagKeys.SKELETONS).contains(entity.getType());
    }

    private static Held heldTotem(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return null;
        }
        ItemStack main = equipment.getItem(EquipmentSlot.HAND);
        if (DreamerTotem.isTotem(main)) {
            return new Held(EquipmentSlot.HAND, main.clone());
        }
        ItemStack off = equipment.getItem(EquipmentSlot.OFF_HAND);
        if (DreamerTotem.isTotem(off)) {
            return new Held(EquipmentSlot.OFF_HAND, off.clone());
        }
        return null;
    }

    private static void consume(LivingEntity entity, EquipmentSlot slot) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        ItemStack current = equipment.getItem(slot);
        if (!DreamerTotem.isTotem(current)) {
            return;
        }
        if (current.getAmount() <= 1) {
            equipment.setItem(slot, ItemStack.empty());
        } else {
            current.setAmount(current.getAmount() - 1);
            equipment.setItem(slot, current);
        }
    }

    private static void consumeStorage(Player player, int slot) {
        ItemStack current = player.getInventory().getItem(slot);
        if (!DreamerTotem.isTotem(current)) {
            return;
        }
        if (current.getAmount() <= 1) {
            player.getInventory().setItem(slot, null);
        } else {
            current.setAmount(current.getAmount() - 1);
        }
    }

    private record Held(EquipmentSlot slot, ItemStack totem) {}
}
