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
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DreamersListener implements Listener {
    private static final Sound DEATH_SOUND = Sound.sound(
            Key.key("minecraft", "terranova.death"),
            Sound.Source.AMBIENT,
            1f,
            1f
    );
    private static final Sound TOTEM_SOUND = Sound.sound(
            Key.key("minecraft", "terranova.totem"),
            Sound.Source.PLAYER,
            1f,
            1f
    );
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(500),
            Duration.ofMillis(3500),
            Duration.ofMillis(1000)
    );

    private final Map<UUID, DamageSource> lastDamage = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.isDead()) {
            return;
        }
        String type = event.getDamageSource().getDamageType().getKey().getKey();
        if (type.equals("out_of_world") || type.equals("generic_kill")) {
            return;
        }
        if (player.getHealth() + player.getAbsorptionAmount() > event.getFinalDamage()) {
            return;
        }
        if (DreamerTotem.isAnyTotem(player.getInventory().getItemInMainHand())
                || DreamerTotem.isAnyTotem(player.getInventory().getItemInOffHand())) {
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
        player.setHealth(1.0);
        player.clearActivePotionEffects();
        player.setAbsorptionAmount(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        player.getInventory().setItem(slot, null);
        player.incrementStatistic(Statistic.USE_ITEM, Material.TOTEM_OF_UNDYING);
        player.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
        announce(player, totem, event.getDamageSource());
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
        if (hand == null) {
            return;
        }

        ItemStack totem = player.getEquipment() == null
                ? ItemStack.empty()
                : player.getEquipment().getItem(hand);
        announce(player, totem, lastDamage.remove(player.getUniqueId()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        DreamerTotem.discover(event.getPlayer());
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
        player.playSound(TOTEM_SOUND, Sound.Emitter.self());
        Bukkit.getPluginManager().callEvent(new PlayerTotemAlertEvent(player, plain(message)));
    }

    private static String plain(Component component) {
        Component rendered = GlobalTranslator.render(component, Locale.forLanguageTag("es-ES"));
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDamage.remove(event.getPlayer().getUniqueId());
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
}
