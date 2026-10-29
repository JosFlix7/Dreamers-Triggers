package dev.dreamers;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DreamersPlugin extends JavaPlugin {
    private DeathUhc deathUhc;

    @Override
    public void onEnable() {
        DreamerTotem.enable(this);
        deathUhc = new DeathUhc(this);
        getServer().getPluginManager().registerEvents(new DreamersListener(this), this);
        getServer().getPluginManager().registerEvents(deathUhc, this);
        PluginCommand dreamers = getCommand("dreamers");
        if (dreamers != null) {
            DreamersCommand command = new DreamersCommand(deathUhc);
            dreamers.setExecutor(command);
            dreamers.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (deathUhc != null) {
            deathUhc.shutdown();
        }
    }
}
