package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Clic derecho en la mesa de encantamientos con una botella de cristal:
 * quita 7 puntos de experiencia, gasta la botella y da una botella de experiencia.
 */
final class XpBottling implements Listener {
    private static final int COST = 7;
    private static final Component NOT_ENOUGH = Component.text(
            "No tienes suficiente experiencia para guardarla en una botella.",
            NamedTextColor.RED
    );

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENCHANTING_TABLE) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.GLASS_BOTTLE) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (player.calculateTotalExperiencePoints() < COST) {
            player.sendMessage(NOT_ENOUGH);
            return;
        }
        player.giveExp(-COST);
        int left = held.getAmount() - 1;
        if (left <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(left);
        }
        player.getInventory().addItem(ItemStack.of(Material.EXPERIENCE_BOTTLE)).values()
                .forEach(extra -> player.getWorld().dropItem(player.getLocation(), extra));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL_DRAGONBREATH, 1.0f, 1.25f);
    }
}
