package dev.dreamers;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DreamersPlugin extends JavaPlugin {
    private DeathUhc deathUhc;
    private DreamersListener listener;
    private BundleSack bundles;
    private boolean discord;

    @Override
    public void onEnable() {
        DreamerTotem.enable(this);
        discord = getServer().getPluginManager().getPlugin("DiscordSRV") != null;
        if (discord) {
            SpanishTexts.enable(this);
            PackTexts.enable(this);
            DiscordBridge.enable();
        }
        deathUhc = new DeathUhc(this);
        listener = new DreamersListener(this);
        bundles = new BundleSack(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(deathUhc, this);
        getServer().getPluginManager().registerEvents(bundles, this);
        PluginCommand dreamers = getCommand("dreamers");
        if (dreamers != null) {
            DreamersCommand command = new DreamersCommand(deathUhc);
            dreamers.setExecutor(command);
            dreamers.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (discord) {
            DiscordBridge.disable();
            PackTexts.disable();
            SpanishTexts.disable();
        }
        if (listener != null) {
            listener.shutdown();
        }
        if (deathUhc != null) {
            deathUhc.shutdown();
        }
        if (bundles != null) {
            bundles.shutdown();
        }
    }
}
