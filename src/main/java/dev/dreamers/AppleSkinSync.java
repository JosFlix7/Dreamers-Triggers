package dev.dreamers;

import org.bukkit.GameRules;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manda a AppleSkin 26.3 la saturación, el agotamiento y si el mundo
 * regenera solo. El cliente sin el mod no está suscrito y no recibe nada.
 */
final class AppleSkinSync implements Listener {
    private static final String SATURATION = "appleskin:saturation";
    private static final String EXHAUSTION = "appleskin:exhaustion";
    private static final String REGENERATION = "appleskin:natural_regeneration";
    private static final float EXHAUSTION_STEP = 0.01F;

    private final JavaPlugin plugin;
    private final Map<UUID, Float> saturation = new HashMap<>();
    private final Map<UUID, Float> exhaustion = new HashMap<>();
    private final Map<UUID, Boolean> regeneration = new HashMap<>();
    private BukkitTask task;

    AppleSkinSync(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, SATURATION);
        messenger.registerOutgoingPluginChannel(plugin, EXHAUSTION);
        messenger.registerOutgoingPluginChannel(plugin, REGENERATION);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        saturation.clear();
        exhaustion.clear();
        regeneration.clear();
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, SATURATION);
        messenger.unregisterOutgoingPluginChannel(plugin, EXHAUSTION);
        messenger.unregisterOutgoingPluginChannel(plugin, REGENERATION);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sync(player);
        }
    }

    private void sync(Player player) {
        UUID id = player.getUniqueId();
        Set<String> channels = player.getListeningPluginChannels();
        float nextSaturation = player.getSaturation();
        if (channels.contains(SATURATION)) {
            Float previous = saturation.get(id);
            if (previous == null || previous != nextSaturation) {
                player.sendPluginMessage(plugin, SATURATION, floatBytes(nextSaturation));
                saturation.put(id, nextSaturation);
            }
        } else {
            saturation.remove(id);
        }

        float nextExhaustion = player.getExhaustion();
        if (channels.contains(EXHAUSTION)) {
            Float previous = exhaustion.get(id);
            if (previous == null || Math.abs(previous - nextExhaustion) >= EXHAUSTION_STEP) {
                player.sendPluginMessage(plugin, EXHAUSTION, floatBytes(nextExhaustion));
                exhaustion.put(id, nextExhaustion);
            }
        } else {
            exhaustion.remove(id);
        }

        boolean regenerates = regenerates(player);
        if (channels.contains(REGENERATION)) {
            Boolean previous = regeneration.get(id);
            if (previous == null) {
                regeneration.put(id, regenerates);
                if (!regenerates) {
                    player.sendPluginMessage(plugin, REGENERATION, byteBytes(false));
                }
            } else if (previous != regenerates) {
                player.sendPluginMessage(plugin, REGENERATION, byteBytes(regenerates));
                regeneration.put(id, regenerates);
            }
        } else {
            regeneration.remove(id);
        }
    }

    private static boolean regenerates(Player player) {
        Boolean value = player.getWorld().getGameRuleValue(GameRules.NATURAL_HEALTH_REGENERATION);
        return value == null || value;
    }

    private void forget(UUID id) {
        saturation.remove(id);
        exhaustion.remove(id);
        regeneration.remove(id);
    }

    private static byte[] floatBytes(float value) {
        return ByteBuffer.allocate(Float.BYTES).putFloat(value).array();
    }

    private static byte[] byteBytes(boolean value) {
        return new byte[] {(byte) (value ? 1 : 0)};
    }
}
