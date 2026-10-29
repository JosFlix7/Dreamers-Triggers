package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class DeathUhc implements Listener {
    static final String USAGE = "/dreamers DeathUCH <on|off|time <segundos>>";
    private static final String TITLE = "Modo UHC por muerte de un jugador";
    private static final int DEFAULT_SECONDS = 60;
    private static final int MAX_SECONDS = 86_400;

    private final JavaPlugin plugin;
    private final Map<UUID, Boolean> previousRegen = new HashMap<>();
    private BossBar bar;
    private BukkitTask task;
    private boolean enabled;
    private boolean running;
    private int seconds = DEFAULT_SECONDS;
    private long endsAt;
    private long cycleMillis;

    DeathUhc(JavaPlugin plugin) {
        this.plugin = plugin;
        enabled = plugin.getConfig().getBoolean("death-uhc.enabled", false);
        seconds = plugin.getConfig().getInt("death-uhc.seconds", DEFAULT_SECONDS);
        if (seconds < 1 || seconds > MAX_SECONDS) {
            seconds = DEFAULT_SECONDS;
        }
    }

    void shutdown() {
        stop();
    }

    void command(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> {
                if (args.length != 2) {
                    usage(sender);
                    return;
                }
                enabled = true;
                save();
                sender.sendMessage(Component.text(
                        "Modo UHC por muerte activado. Cada muerte suma " + seconds + " segundos.",
                        NamedTextColor.GREEN
                ));
            }
            case "off" -> {
                if (args.length != 2) {
                    usage(sender);
                    return;
                }
                enabled = false;
                save();
                stop();
                sender.sendMessage(Component.text("Modo UHC por muerte desactivado.", NamedTextColor.YELLOW));
            }
            case "time" -> time(sender, args);
            default -> usage(sender);
        }
    }

    private void time(CommandSender sender, String[] args) {
        if (args.length == 2) {
            sender.sendMessage(Component.text(
                    "El modo UHC suma " + seconds + " segundos por cada muerte.",
                    NamedTextColor.YELLOW
            ));
            return;
        }
        if (args.length != 3) {
            usage(sender);
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            usage(sender);
            return;
        }
        if (value < 1 || value > MAX_SECONDS) {
            sender.sendMessage(Component.text(
                    "El tiempo tiene que estar entre 1 y " + MAX_SECONDS + " segundos.",
                    NamedTextColor.RED
            ));
            return;
        }
        seconds = value;
        save();
        String message = "El modo UHC sumará " + seconds + " segundos por cada muerte.";
        if (running) {
            message += " El tiempo que ya está corriendo sigue igual.";
        }
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled) {
            return;
        }
        long extra = seconds * 1000L;
        long now = System.currentTimeMillis();
        if (!running || endsAt <= now) {
            endsAt = now + extra;
            cycleMillis = extra;
            running = true;
            applyRegen();
            showBar();
            if (task == null) {
                task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 10L);
            }
            return;
        }
        endsAt += extra;
        cycleMillis = endsAt - now;
        updateBar(now);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (running && bar != null) {
            bar.addPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bar != null) {
            bar.removePlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (running) {
            applyRegen(event.getWorld());
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        restore(event.getWorld());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!running || endsAt <= now) {
            stop();
            return;
        }
        applyRegen();
        updateBar(now);
    }

    private void showBar() {
        if (bar == null) {
            bar = Bukkit.createBossBar(TITLE, BarColor.RED, BarStyle.SOLID);
        }
        bar.setTitle(TITLE);
        bar.setVisible(true);
        for (Player player : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(player);
        }
        updateBar(System.currentTimeMillis());
    }

    private void updateBar(long now) {
        if (bar == null || cycleMillis <= 0) {
            return;
        }
        double progress = (endsAt - now) / (double) cycleMillis;
        bar.setProgress(Math.clamp(progress, 0.0, 1.0));
    }

    private void applyRegen() {
        for (World world : Bukkit.getWorlds()) {
            applyRegen(world);
        }
    }

    private void applyRegen(World world) {
        UUID id = world.getUID();
        if (!previousRegen.containsKey(id)) {
            Boolean current = world.getGameRuleValue(GameRules.NATURAL_HEALTH_REGENERATION);
            if (current == null) {
                current = GameRules.NATURAL_HEALTH_REGENERATION.getDefaultValue();
            }
            previousRegen.put(id, current == null || current);
        }
        if (!Boolean.FALSE.equals(world.getGameRuleValue(GameRules.NATURAL_HEALTH_REGENERATION))) {
            world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        }
    }

    private void restore(World world) {
        Boolean previous = previousRegen.remove(world.getUID());
        if (previous != null) {
            world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, previous);
        }
    }

    private void stop() {
        running = false;
        endsAt = 0;
        cycleMillis = 0;
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (World world : Bukkit.getWorlds()) {
            restore(world);
        }
        previousRegen.clear();
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    private void save() {
        plugin.getConfig().set("death-uhc.enabled", enabled);
        plugin.getConfig().set("death-uhc.seconds", seconds);
        plugin.saveConfig();
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Uso: " + USAGE, NamedTextColor.YELLOW));
    }
}
