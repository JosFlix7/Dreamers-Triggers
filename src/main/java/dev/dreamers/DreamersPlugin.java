package dev.dreamers;

import org.bukkit.plugin.java.JavaPlugin;

public final class DreamersPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new DreamersListener(), this);
    }
}
