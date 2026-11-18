package dev.dreamers;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DreamersPlugin extends JavaPlugin {
    private DeathUhc deathUhc;
    private DreamersListener listener;
    private BundleSack bundles;
    private TooltipServer tooltips;
    private CommandFilter commands;
    private AppleSkinSync appleSkin;
    private XaeroMaps xaero;
    private ArmorVisibility armor;
    private FabricModWhitelist mods;
    private boolean discord;

    @Override
    public void onEnable() {
        installConfig();
        DreamerTotem.enable(this);
        discord = getServer().getPluginManager().getPlugin("DiscordSRV") != null;
        if (discord) {
            SpanishTexts.enable(this);
            PackTexts.enable(this);
            DiscordBridge.enable();
        }
        deathUhc = new DeathUhc(this);
        listener = new DreamersListener(this);
        Enderbag.enable(this);
        ReinforcedNetherite.enable(this);
        bundles = new BundleSack(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(deathUhc, this);
        getServer().getPluginManager().registerEvents(bundles, this);
        getServer().getPluginManager().registerEvents(new WardenLoot(), this);
        getServer().getPluginManager().registerEvents(new NetheriteHostiles(), this);
        getServer().getPluginManager().registerEvents(new XpBottling(), this);
        getServer().getPluginManager().registerEvents(new FastLeaves(), this);
        tooltips = new TooltipServer(this);
        tooltips.enable();
        commands = new CommandFilter(this);
        getServer().getPluginManager().registerEvents(commands, this);
        commands.load();
        appleSkin = new AppleSkinSync(this);
        appleSkin.enable();
        xaero = new XaeroMaps(this);
        xaero.enable();
        armor = new ArmorVisibility(this);
        armor.enable();
        mods = new FabricModWhitelist(this);
        mods.enable();
        PluginCommand dreamers = getCommand("dreamers");
        if (dreamers != null) {
            DreamersCommand command = new DreamersCommand(deathUhc, commands, xaero, new CustomItems(bundles), armor, mods);
            dreamers.setExecutor(command);
            dreamers.setTabCompleter(command);
        }
    }

    @Override
    public void saveConfig() {
        mergeBaseConfig();
        super.saveConfig();
    }

    private void installConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            saveResource("config.yml", false);
        }
        reloadConfig();
    }

    private void mergeBaseConfig() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration base = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            copyMissing(base, getConfig());
        } catch (IOException exception) {
            getLogger().warning("No se pudo completar el config con las opciones base.");
        }
    }

    private static void copyMissing(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            if (!to.contains(key, true)) {
                if (from.isConfigurationSection(key)) {
                    ConfigurationSection created = to.createSection(key);
                    copyComments(from, to, key);
                    copyMissing(from.getConfigurationSection(key), created);
                } else {
                    Object value = from.get(key);
                    to.set(key, value instanceof List<?> list ? new ArrayList<>(list) : value);
                    copyComments(from, to, key);
                }
            } else if (from.isConfigurationSection(key) && to.isConfigurationSection(key)) {
                copyMissing(from.getConfigurationSection(key), to.getConfigurationSection(key));
            }
        }
    }

    private static void copyComments(ConfigurationSection from, ConfigurationSection to, String key) {
        List<String> comments = from.getComments(key);
        if (comments != null && !comments.isEmpty()) {
            to.setComments(key, comments);
        }
        List<String> inline = from.getInlineComments(key);
        if (inline != null && !inline.isEmpty()) {
            to.setInlineComments(key, inline);
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
        if (tooltips != null) {
            tooltips.disable();
        }
        if (appleSkin != null) {
            appleSkin.disable();
        }
        if (xaero != null) {
            xaero.disable();
        }
        if (armor != null) {
            armor.disable();
        }
        if (mods != null) {
            mods.disable();
        }
    }
}
