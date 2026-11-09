package dev.dreamers;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.LeavesDecayEvent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Las hojas que ya se están desintegrando se rompen junto con las vecinas,
 * también en diagonal, y sueltan lo mismo que al pudrirse.
 */
final class FastLeaves implements Listener {
    private static final BlockFace[] AROUND = Arrays.stream(BlockFace.values())
            .filter(face -> face != BlockFace.SELF)
            .toArray(BlockFace[]::new);

    private boolean spreading;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (Tag.LOGS.isTagged(block.getType()) || Tag.LEAVES.isTagged(block.getType())) {
            spread(block, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        Block block = event.getBlock();
        if (!decaying(block)) {
            return;
        }
        event.setCancelled(true);
        spread(block, true);
    }

    private void spread(Block start, boolean breakStart) {
        if (spreading) {
            return;
        }
        spreading = true;
        try {
            Set<Long> seen = new HashSet<>();
            ArrayDeque<Block> pending = new ArrayDeque<>();
            seen.add(key(start));
            if (breakStart) {
                start.breakNaturally();
            }
            pending.add(start);
            while (!pending.isEmpty()) {
                Block current = pending.removeFirst();
                for (BlockFace face : AROUND) {
                    Block next = current.getRelative(face);
                    if (!seen.add(key(next)) || !decaying(next)) {
                        continue;
                    }
                    next.breakNaturally();
                    pending.add(next);
                }
            }
        } finally {
            spreading = false;
        }
    }

    private static boolean decaying(Block block) {
        if (!(block.getBlockData() instanceof Leaves leaves) || leaves.isPersistent()) {
            return false;
        }
        return leaves.getDistance() >= leaves.getMaximumDistance();
    }

    private static long key(Block block) {
        return (((long) block.getX() & 0x3FFFFFFL) << 38)
                | (((long) block.getY() & 0xFFFL) << 26)
                | ((long) block.getZ() & 0x3FFFFFFL);
    }
}
