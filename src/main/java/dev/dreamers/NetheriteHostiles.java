package dev.dreamers;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.concurrent.ThreadLocalRandom;

final class NetheriteHostiles implements Listener {
    private static final double RANGE = 128;
    private static final double EXTRA_PLAYER = 0.15;
    private static final double RESISTANCE_PER_PIECE = 0.125;
    private static final double STRENGTH_PER_PIECE = 0.0625;
    private static final PotionEffect RESISTANCE = new PotionEffect(
            PotionEffectType.RESISTANCE,
            PotionEffect.INFINITE_DURATION,
            0,
            false,
            false,
            false
    );
    private static final PotionEffect STRENGTH = new PotionEffect(
            PotionEffectType.STRENGTH,
            PotionEffect.INFINITE_DURATION,
            0,
            false,
            false,
            false
    );

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Enemy enemy)) {
            return;
        }
        World world = enemy.getWorld();
        if (world.getPlayerCount() == 0) {
            return;
        }
        double pieces = weightedPieces(world, enemy.getX(), enemy.getY(), enemy.getZ());
        if (pieces <= 0) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < pieces * RESISTANCE_PER_PIECE) {
            enemy.addPotionEffect(RESISTANCE);
        }
        if (random.nextDouble() < pieces * STRENGTH_PER_PIECE) {
            enemy.addPotionEffect(STRENGTH);
        }
    }

    private static double weightedPieces(World world, double x, double y, double z) {
        BoundingBox area = new BoundingBox(x - RANGE, y - RANGE, z - RANGE, x + RANGE, y + RANGE, z + RANGE);
        int highest = 0;
        int sum = 0;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || !player.getBoundingBox().overlaps(area)) {
                continue;
            }
            int worn = wornNetherite(player);
            if (worn == 0) {
                continue;
            }
            sum += worn;
            if (worn > highest) {
                highest = worn;
            }
        }
        if (highest == 0) {
            return 0;
        }
        return highest + EXTRA_PLAYER * (sum - highest);
    }

    private static int wornNetherite(Player player) {
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int worn = 0;
        if (isNetherite(equipment.getHelmet())) {
            worn++;
        }
        if (isNetherite(equipment.getChestplate())) {
            worn++;
        }
        if (isNetherite(equipment.getLeggings())) {
            worn++;
        }
        if (isNetherite(equipment.getBoots())) {
            worn++;
        }
        return worn;
    }

    private static boolean isNetherite(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return switch (stack.getType()) {
            case NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS -> true;
            default -> false;
        };
    }
}
