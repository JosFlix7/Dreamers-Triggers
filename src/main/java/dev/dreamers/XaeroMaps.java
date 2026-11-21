package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Le dice a Xaero qué modos del minimapa puede usar cada jugador y le manda
 * la posición de los demás cuando ese jugador tiene el rastreo activo.
 * La regla queda en la configuración; si no tiene una, valen los valores por defecto.
 */
final class XaeroMaps implements Listener, PluginMessageListener {
    static final String USAGE = "/dreamers xm <jugador|@a> <cuevas> <cuevasNether> <radar> <trackPlayers> | /dreamers xm <jugador|@a> reset";
    private static final String WORLD_MAP = "xaeroworldmap:main";
    private static final String MINI_MAP = "xaerominimap:main";
    private static final int NETWORK_VERSION = 3;
    private static final long SYNC_COOLDOWN_MS = 250;
    private static final byte[] RESET = {3};
    private static final List<String> COMMENTS = List.of(
            "Xaero's Minimap. Los allow-* y track-players valen para quien no tiene regla propia.",
            "level-id identifica el servidor ante el mod; no lo cambies.",
            "/dreamers xm <jugador|@a> <cuevas> <cuevasNether> <radar> <trackPlayers> guarda true o false.",
            "@a incluye a quien ya entró, esté conectado o no. reset borra esa regla y vuelven los ajustes por defecto.",
            "track-players muestra a los jugadores en cualquier parte del mapa. El radar no los oculta: solo quita el resto de entidades."
    );

    private static final Component UNKNOWN = Component.text("Ese jugador no ha entrado al servidor.", NamedTextColor.RED);
    private static final Component NOBODY = Component.text("Nadie ha entrado al servidor.", NamedTextColor.RED);
    private static final List<String> MODES = List.of("true", "false");

    private final JavaPlugin plugin;
    private final Map<UUID, Rule> players = new HashMap<>();
    private final Map<UUID, Link> links = new HashMap<>();
    private Rule defaults = new Rule(true, true, true, true);
    private int levelId;

    XaeroMaps(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, WORLD_MAP);
        messenger.registerOutgoingPluginChannel(plugin, MINI_MAP);
        messenger.registerIncomingPluginChannel(plugin, WORLD_MAP, this);
        messenger.registerIncomingPluginChannel(plugin, MINI_MAP, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sendRules(player);
            greet(player);
        }
    }

    void disable() {
        for (Link link : links.values()) {
            if (link.task != null) {
                link.task.cancel();
            }
        }
        links.clear();
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, WORLD_MAP, this);
        messenger.unregisterIncomingPluginChannel(plugin, MINI_MAP, this);
        messenger.unregisterOutgoingPluginChannel(plugin, WORLD_MAP);
        messenger.unregisterOutgoingPluginChannel(plugin, MINI_MAP);
    }

    void reload() {
        load();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sendRules(player);
            resetViewer(player);
            greet(player);
        }
    }

    void command(CommandSender sender, String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("reset")) {
            reset(sender, args[1]);
            return;
        }
        if (args.length != 6) {
            usage(sender);
            return;
        }
        Boolean cave = bool(args[2]);
        Boolean nether = bool(args[3]);
        Boolean radar = bool(args[4]);
        Boolean track = bool(args[5]);
        if (cave == null || nether == null || radar == null || track == null) {
            sender.sendMessage(Component.text("Uso: " + USAGE + ". Cada modo es true o false.", NamedTextColor.YELLOW));
            return;
        }
        Rule rule = new Rule(cave, nether, radar, track);
        if (args[1].equalsIgnoreCase("@a")) {
            applyAll(sender, rule);
            return;
        }
        OfflinePlayer target = KnownPlayers.find(plugin.getServer(), args[1]);
        if (target == null) {
            sender.sendMessage(UNKNOWN);
            return;
        }
        store(target, rule);
        plugin.saveConfig();
        boolean online = push(target);
        String name = target.getName() == null ? args[1] : target.getName();
        String saved = online ? "Quedó guardado y se aplicó." : "Quedó guardado y se aplicará cuando entre.";
        sender.sendMessage(Component.text("Xaero de " + name + ": " + rule.text() + " " + saved, NamedTextColor.GREEN));
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if ("@a".startsWith(prefix)) {
                names.add("@a");
            }
            names.addAll(KnownPlayers.names(sender.getServer(), args[1]));
            return List.copyOf(names);
        }
        if (args.length == 3) {
            return starting(List.of("true", "false", "reset"), args[2]);
        }
        if (args.length >= 4 && args.length <= 6 && !args[2].equalsIgnoreCase("reset")) {
            return starting(MODES, args[args.length - 1]);
        }
        return List.of();
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent event) {
        String channel = event.getChannel();
        if (!WORLD_MAP.equals(channel) && !MINI_MAP.equals(channel)) {
            return;
        }
        Player player = event.getPlayer();
        send(player, channel);
        player.sendPluginMessage(plugin, channel, handshakePacket());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        send(player);
        resetViewer(player);
        syncNow(player);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!WORLD_MAP.equals(channel) && !MINI_MAP.equals(channel)) {
            return;
        }
        Integer version = handshakeVersion(message);
        if (version == null) {
            return;
        }
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> acceptHandshake(player, channel, version));
            return;
        }
        acceptHandshake(player, channel, version);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasExplicitlyChangedPosition()) {
            return;
        }
        schedule(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                syncNow(player);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PotionEffectType type = event.getModifiedType();
        if (type != null && !type.equals(PotionEffectType.INVISIBILITY)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                syncNow(player);
            }
        });
    }

    @EventHandler
    public void onHide(PlayerHideEntityEvent event) {
        if (event.getEntity() instanceof Player subject) {
            later(subject, event.getPlayer());
        }
    }

    @EventHandler
    public void onShow(PlayerShowEntityEvent event) {
        if (event.getEntity() instanceof Player subject) {
            later(subject, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Player subject) {
            later(subject, event.getPlayer());
        }
    }

    @EventHandler
    public void onUntrack(PlayerUntrackEntityEvent event) {
        if (event.getEntity() instanceof Player subject) {
            later(subject, event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Link link = links.remove(player.getUniqueId());
        if (link != null && link.task != null) {
            link.task.cancel();
        }
        byte[] remove = playerPacket(player, true);
        if (link != null) {
            for (UUID viewerId : link.shown) {
                Player viewer = plugin.getServer().getPlayer(viewerId);
                if (viewer != null) {
                    sendTrack(viewer, remove);
                }
            }
        }
        for (Link other : links.values()) {
            other.shown.remove(player.getUniqueId());
        }
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.contains("xaero.level-id")) {
            levelId = resolveLevelId();
            config.set("xaero.level-id", levelId);
            config.setComments("xaero", COMMENTS);
            changed = true;
        } else {
            levelId = config.getInt("xaero.level-id");
        }
        if (!config.contains("xaero.allow-cave-mode")) {
            config.set("xaero.allow-cave-mode", true);
            changed = true;
        }
        if (!config.contains("xaero.allow-nether-cave-mode")) {
            config.set("xaero.allow-nether-cave-mode", true);
            changed = true;
        }
        if (!config.contains("xaero.allow-radar")) {
            config.set("xaero.allow-radar", true);
            changed = true;
        }
        if (!config.contains("xaero.track-players")) {
            config.set("xaero.track-players", true);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
        defaults = new Rule(
                config.getBoolean("xaero.allow-cave-mode", true),
                config.getBoolean("xaero.allow-nether-cave-mode", true),
                config.getBoolean("xaero.allow-radar", true),
                config.getBoolean("xaero.track-players", true)
        );
        players.clear();
        ConfigurationSection section = config.getConfigurationSection("xaero.players");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                players.put(UUID.fromString(id), new Rule(
                        section.getBoolean(id + ".allow-cave-mode", defaults.cave()),
                        section.getBoolean(id + ".allow-nether-cave-mode", defaults.nether()),
                        section.getBoolean(id + ".allow-radar", defaults.radar()),
                        section.getBoolean(id + ".track-players", defaults.track())
                ));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Regla de Xaero ignorada, el id no es un UUID: " + id);
            }
        }
    }

    private int resolveLevelId() {
        Integer file = readIdFile(new File(plugin.getServer().getWorldContainer(), "xaeromap.txt"));
        if (file != null) {
            return file;
        }
        Integer old = readOldPlugin();
        if (old != null) {
            return old;
        }
        int created = new Random().nextInt();
        plugin.getLogger().warning("No había id de Xaero; se creó uno nuevo. Los mapas de los jugadores pueden reiniciarse.");
        writeIdFile(created);
        return created;
    }

    private Integer readOldPlugin() {
        File parent = plugin.getDataFolder().getParentFile();
        if (parent == null) {
            return null;
        }
        File file = new File(parent, "XaeroMapPlugin-Paper/config.yml");
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return yaml.contains("level-id") ? yaml.getInt("level-id") : null;
    }

    private static Integer readIdFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            String line = Files.readString(file.toPath()).lines().findFirst().orElse("");
            String[] parts = line.trim().split(":", 2);
            if (parts.length != 2 || !parts[0].equals("id")) {
                return null;
            }
            return Integer.parseInt(parts[1].trim());
        } catch (IOException | NumberFormatException ex) {
            return null;
        }
    }

    private void writeIdFile(int id) {
        File file = new File(plugin.getServer().getWorldContainer(), "xaeromap.txt");
        if (file.exists()) {
            return;
        }
        try {
            Files.writeString(file.toPath(), "id:" + id);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo escribir xaeromap.txt: " + ex.getMessage());
        }
    }

    private void send(Player player) {
        send(player, WORLD_MAP);
        send(player, MINI_MAP);
    }

    private void send(Player player, String channel) {
        sendRules(player, channel);
        player.sendPluginMessage(plugin, channel, levelPacket(levelId));
    }

    private void sendRules(Player player) {
        sendRules(player, WORLD_MAP);
        sendRules(player, MINI_MAP);
    }

    private void sendRules(Player player, String channel) {
        Rule rule = players.getOrDefault(player.getUniqueId(), defaults);
        player.sendPluginMessage(plugin, channel, rulesPacket(rule));
    }

    private void applyAll(CommandSender sender, Rule rule) {
        List<OfflinePlayer> targets = KnownPlayers.everyone(plugin.getServer());
        if (targets.isEmpty()) {
            sender.sendMessage(NOBODY);
            return;
        }
        for (OfflinePlayer target : targets) {
            store(target, rule);
            push(target);
        }
        plugin.saveConfig();
        sender.sendMessage(Component.text(
                "Xaero de todos: " + rule.text() + " Quedó guardado y se aplicó a quien está conectado.",
                NamedTextColor.GREEN
        ));
    }

    private void reset(CommandSender sender, String name) {
        if (name.equalsIgnoreCase("@a")) {
            if (players.isEmpty() && KnownPlayers.everyone(plugin.getServer()).isEmpty()) {
                sender.sendMessage(NOBODY);
                return;
            }
            Set<UUID> previous = new HashSet<>(players.keySet());
            players.clear();
            FileConfiguration config = plugin.getConfig();
            config.set("xaero.players", null);
            config.createSection("xaero.players");
            config.setComments("xaero", COMMENTS);
            plugin.saveConfig();
            for (UUID id : previous) {
                Player online = plugin.getServer().getPlayer(id);
                if (online != null && online.isOnline()) {
                    push(online);
                }
            }
            sender.sendMessage(Component.text("El Xaero de todos volvió a los ajustes por defecto.", NamedTextColor.GREEN));
            return;
        }
        OfflinePlayer target = KnownPlayers.find(plugin.getServer(), name);
        if (target == null) {
            sender.sendMessage(UNKNOWN);
            return;
        }
        players.remove(target.getUniqueId());
        plugin.getConfig().set("xaero.players." + target.getUniqueId(), null);
        plugin.saveConfig();
        boolean online = push(target);
        String shown = target.getName() == null ? name : target.getName();
        String saved = online ? "Quedó aplicado." : "Se aplicará cuando entre.";
        sender.sendMessage(Component.text(
                "Xaero de " + shown + " volvió a los ajustes por defecto. " + saved,
                NamedTextColor.GREEN
        ));
    }

    private void store(OfflinePlayer target, Rule rule) {
        UUID id = target.getUniqueId();
        players.put(id, rule);
        String path = "xaero.players." + id;
        FileConfiguration config = plugin.getConfig();
        if (target.getName() != null) {
            config.set(path + ".name", target.getName());
        }
        config.set(path + ".allow-cave-mode", rule.cave());
        config.set(path + ".allow-nether-cave-mode", rule.nether());
        config.set(path + ".allow-radar", rule.radar());
        config.set(path + ".track-players", rule.track());
    }

    private boolean push(OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online == null || !online.isOnline()) {
            return false;
        }
        push(online);
        return true;
    }

    private void push(Player player) {
        sendRules(player);
        resetViewer(player);
        syncNow(player);
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Uso: " + USAGE, NamedTextColor.YELLOW));
    }

    private static List<String> starting(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static Boolean bool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    private static byte[] rulesPacket(Rule rule) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(4);
            out.writeByte(10);
            writeBoolean(out, "cm", rule.cave());
            writeBoolean(out, "ncm", rule.nether());
            writeBoolean(out, "r", rule.radar());
            out.writeByte(0);
            return raw.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeBoolean(DataOutputStream out, String key, boolean value) throws IOException {
        out.writeByte(1);
        out.writeUTF(key);
        out.writeByte(value ? 1 : 0);
    }

    private void greet(Player player) {
        var listening = player.getListeningPluginChannels();
        if (listening.contains(MINI_MAP)) {
            player.sendPluginMessage(plugin, MINI_MAP, handshakePacket());
        }
        if (listening.contains(WORLD_MAP)) {
            player.sendPluginMessage(plugin, WORLD_MAP, handshakePacket());
        }
    }

    private void acceptHandshake(Player player, String channel, int version) {
        if (!player.isOnline()) {
            return;
        }
        if (version != NETWORK_VERSION) {
            plugin.getLogger().warning("Xaero de " + player.getName() + " usa la versión de red " + version
                    + "; el rastreo de jugadores pide la " + NETWORK_VERSION + ".");
            return;
        }
        Link link = link(player);
        if (MINI_MAP.equals(channel)) {
            link.minimap = true;
        }
        String preferred = link.minimap ? MINI_MAP : WORLD_MAP;
        if (link.channel != null && !link.channel.equals(preferred)) {
            player.sendPluginMessage(plugin, link.channel, RESET);
        }
        link.channel = preferred;
        player.sendPluginMessage(plugin, preferred, RESET);
        UUID viewerId = player.getUniqueId();
        for (Link other : links.values()) {
            other.shown.remove(viewerId);
        }
        syncNow(player);
    }

    private void later(Player subject, Player viewer) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (subject.isOnline() && viewer.isOnline()) {
                updatePair(subject, viewer);
            }
        });
    }

    private void schedule(Player player) {
        Link link = link(player);
        if (link.task != null) {
            return;
        }
        long remaining = SYNC_COOLDOWN_MS - (System.currentTimeMillis() - link.lastSync);
        if (remaining <= 0) {
            runSync(player);
            return;
        }
        long ticks = Math.max(1, Math.ceilDiv(remaining, 50));
        link.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Link current = links.get(player.getUniqueId());
            if (current != null) {
                current.task = null;
            }
            if (player.isOnline()) {
                runSync(player);
            }
        }, ticks);
    }

    private void syncNow(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Link link = link(player);
        if (link.task != null) {
            link.task.cancel();
            link.task = null;
        }
        runSync(player);
    }

    private void runSync(Player player) {
        Link link = links.get(player.getUniqueId());
        if (link == null || !player.isOnline()) {
            return;
        }
        link.lastSync = System.currentTimeMillis();
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            updatePair(player, other);
            updatePair(other, player);
        }
    }

    private void resetViewer(Player viewer) {
        Link link = links.get(viewer.getUniqueId());
        if (link != null && link.channel != null) {
            viewer.sendPluginMessage(plugin, link.channel, RESET);
        }
        UUID viewerId = viewer.getUniqueId();
        for (Link other : links.values()) {
            other.shown.remove(viewerId);
        }
    }

    private void updatePair(Player subject, Player viewer) {
        if (subject == viewer || !subject.isOnline() || !viewer.isOnline()) {
            return;
        }
        Link subjectLink = link(subject);
        boolean show = shouldShow(subject, viewer);
        boolean was = subjectLink.shown.contains(viewer.getUniqueId());
        if (show) {
            sendTrack(viewer, playerPacket(subject, false));
            subjectLink.shown.add(viewer.getUniqueId());
        } else if (was) {
            sendTrack(viewer, playerPacket(subject, true));
            subjectLink.shown.remove(viewer.getUniqueId());
        }
    }

    private boolean shouldShow(Player subject, Player viewer) {
        Link viewerLink = links.get(viewer.getUniqueId());
        if (viewerLink == null || viewerLink.channel == null || !shareable(subject, viewer)) {
            return false;
        }
        Rule rule = players.getOrDefault(viewer.getUniqueId(), defaults);
        if (rule.track()) {
            return true;
        }
        // El flag r apaga el radar entero, jugadores incluidos. Con el radar y el
        // rastreo apagados, se reponen solo los que el cliente ya recibe.
        return !rule.radar() && subject.isTrackedBy(viewer);
    }

    private boolean shareable(Player subject, Player viewer) {
        if (subject.getGameMode() == GameMode.SPECTATOR || subject.isInvisible()) {
            return false;
        }
        if (subject.hasPotionEffect(PotionEffectType.INVISIBILITY) || vanished(subject)) {
            return false;
        }
        return viewer.canSee(subject);
    }

    @SuppressWarnings("deprecation") // Los plugins de vanish siguen publicando esta metadata.
    private static boolean vanished(Player player) {
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private void sendTrack(Player viewer, byte[] packet) {
        Link link = links.get(viewer.getUniqueId());
        if (link == null || link.channel == null) {
            return;
        }
        viewer.sendPluginMessage(plugin, link.channel, packet);
    }

    private Link link(Player player) {
        return links.computeIfAbsent(player.getUniqueId(), ignored -> new Link());
    }

    private static Integer handshakeVersion(byte[] message) {
        if (message.length < 5 || message[0] != 1) {
            return null;
        }
        return ByteBuffer.wrap(message, 1, Integer.BYTES).getInt();
    }

    private static byte[] handshakePacket() {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(1);
            out.writeInt(NETWORK_VERSION);
            return raw.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] playerPacket(Player player, boolean remove) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(2);
            out.writeByte(10);
            writeBoolean(out, "r", remove);
            writeUuid(out, player.getUniqueId());
            if (!remove) {
                writeDouble(out, "x", player.getX());
                writeDouble(out, "y", player.getY());
                writeDouble(out, "z", player.getZ());
                writeString(out, "d", player.getWorld().getKey().toString());
            }
            out.writeByte(0);
            return raw.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeByte(11);
        out.writeUTF("i");
        out.writeInt(4);
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        out.writeInt((int) (most >> 32));
        out.writeInt((int) most);
        out.writeInt((int) (least >> 32));
        out.writeInt((int) least);
    }

    private static void writeDouble(DataOutputStream out, String key, double value) throws IOException {
        out.writeByte(6);
        out.writeUTF(key);
        out.writeDouble(value);
    }

    private static void writeString(DataOutputStream out, String key, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(key);
        out.writeUTF(value);
    }

    private static byte[] levelPacket(int id) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(0);
            out.writeInt(id);
            return raw.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Rule(boolean cave, boolean nether, boolean radar, boolean track) {
        private String text() {
            return "cuevas " + word(cave) + ", cuevas del Nether " + word(nether)
                    + ", radar " + word(radar) + ", jugadores en el mapa " + word(track) + ".";
        }

        private static String word(boolean value) {
            return value ? "sí" : "no";
        }
    }

    private static final class Link {
        private boolean minimap;
        private String channel;
        private BukkitTask task;
        private long lastSync;
        private final Set<UUID> shown = new HashSet<>();
    }
}
