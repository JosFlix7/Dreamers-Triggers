package dev.dreamers;

import org.bukkit.Material;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

final class WardenLoot implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Warden) || event.getDrops().isEmpty()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < 0.5) {
            event.getDrops().add(ItemStack.of(Material.REINFORCED_DEEPSLATE));
        }
    }
}
