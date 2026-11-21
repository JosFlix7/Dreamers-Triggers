package dev.dreamers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Terrenos de columna completa, claimblocks y el índice por chunk.
 * Lo ajustable se lee de config.yml. Los datos viven en claims.yml.
 */
final class Claims implements Listener {
    static final String ADMIN_PERMISSION = "dreamers.claims.admin";
    private static final int MAX_SIDE = 5000;

    enum Level {
        NONE,
        ACCESS,
        CONTAINER,
        BUILD,
        PERMISSION;

        boolean allows(Level needed) {
            return needed != NONE && ordinal() >= needed.ordinal();
        }

        String save() {
            return switch (this) {
                case ACCESS -> "access";
                case CONTAINER -> "container";
                case BUILD -> "build";
                case PERMISSION -> "permission";
                case NONE -> "none";
            };
        }

        String label() {
            return switch (this) {
                case ACCESS -> "acceso";
                case CONTAINER -> "contenedores";
                case BUILD -> "construcción";
                case PERMISSION -> "permisos";
                case NONE -> "nada";
            };
        }

        static Level parse(String raw) {
            if (raw == null) {
                return NONE;
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "access", "acceso" -> ACCESS;
                case "container", "contenedor", "contenedores" -> CONTAINER;
                case "build", "trust", "construccion", "construcción" -> BUILD;
                case "permission", "permiso", "permisos" -> PERMISSION;
                default -> NONE;
            };
        }
    }

    static final class Land {
        final UUID id;
        final UUID world;
        final UUID owner;
        final boolean admin;
        int minX;
        int maxX;
        int minZ;
        int maxZ;
        Level everyone = Level.NONE;
        final Map<UUID, Level> trust = new HashMap<>();

        Land(UUID id, UUID world, UUID owner, boolean admin) {
            this.id = id;
            this.world = world;
            this.owner = owner;
            this.admin = admin;
        }

        void set(int x1, int z1, int x2, int z2) {
            minX = Math.min(x1, x2);
            maxX = Math.max(x1, x2);
            minZ = Math.min(z1, z2);
            maxZ = Math.max(z1, z2);
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean corner(int x, int z) {
            boolean edgeX = x == minX || x == maxX;
            boolean edgeZ = z == minZ || z == maxZ;
            return edgeX && edgeZ;
        }

        int width() {
            return maxX - minX + 1;
        }

        int length() {
            return maxZ - minZ + 1;
        }

        int area() {
            long area = (long) width() * length();
            return area > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) area;
        }
    }

    private static final class Balance {
        int accrued;
        int seconds;
        String name = "";
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Balance> balances = new HashMap<>();
    private final Map<UUID, Integer> used = new HashMap<>();
    private final List<Land> lands = new ArrayList<>();
    private final Map<UUID, Map<Long, List<Land>>> index = new HashMap<>();
    private BukkitTask task;
    private volatile int initialBlocks = 50;
    private volatile int minimumSide = 5;
    private volatile int borderSeconds = 10;
    private volatile int combatSeconds = 15;
    private volatile int accrualMinutes = 60;
    private volatile int accrualBlocks = 100;
    private volatile int maximum = 8000;
    private volatile Set<String> enabledWorlds = Set.of("dreamers4");

    Claims(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
    }

    static boolean admin(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION);
    }

    void enable() {
        loadSettings();
        loadData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            remember(player);
        }
        restartTask();
    }

    void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }

    void reload() {
        loadSettings();
        reloadBalances();
        restartTask();
    }

    int minimumSide() {
        return minimumSide;
    }

    int borderSeconds() {
        return borderSeconds;
    }

    int combatSeconds() {
        return combatSeconds;
    }

    int maxSide() {
        return MAX_SIDE;
    }

    boolean enabled(World world) {
        return world != null && enabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    synchronized int remaining(UUID player) {
        Balance balance = balances.get(player);
        if (balance == null) {
            return 0;
        }
        return Math.max(0, balance.accrued - used.getOrDefault(player, 0));
    }

    synchronized int ownedCount(UUID player) {
        int count = 0;
        for (Land land : lands) {
            if (!land.admin && player.equals(land.owner)) {
                count++;
            }
        }
        return count;
    }

    synchronized UUID findPlayer(String name) {
        for (Map.Entry<UUID, Balance> entry : balances.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    synchronized String name(UUID player) {
        if (player == null) {
            return "Administración";
        }
        Balance balance = balances.get(player);
        if (balance != null && balance.name != null && !balance.name.isBlank()) {
            return balance.name;
        }
        String offline = plugin.getServer().getOfflinePlayer(player).getName();
        return offline == null || offline.isBlank() ? "Desconocido" : offline;
    }

    synchronized String ownerName(Land land) {
        if (land.admin) {
            return "Administración";
        }
        return name(land.owner);
    }

    synchronized Land at(Location location) {
        if (location == null || location.getWorld() == null || !enabled(location.getWorld())) {
            return null;
        }
        Map<Long, List<Land>> chunks = index.get(location.getWorld().getUID());
        if (chunks == null) {
            return null;
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        List<Land> list = chunks.get(chunkKey(x >> 4, z >> 4));
        if (list == null) {
            return null;
        }
        for (Land land : list) {
            if (land.contains(x, z)) {
                return land;
            }
        }
        return null;
    }

    synchronized boolean allows(Player player, Land land, Level needed) {
        if (needed == Level.NONE) {
            return true;
        }
        if (land.admin && player.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        if (!land.admin && player.getUniqueId().equals(land.owner)) {
            return true;
        }
        Level have = land.trust.getOrDefault(player.getUniqueId(), Level.NONE);
        if (have == Level.NONE) {
            have = land.everyone;
        }
        return have.allows(needed);
    }

    synchronized boolean canManage(Player player, Land land) {
        return allows(player, land, Level.PERMISSION);
    }

    boolean canResize(Player player, Land land) {
        if (land.admin) {
            return player.hasPermission(ADMIN_PERMISSION);
        }
        return player.getUniqueId().equals(land.owner);
    }

    synchronized Land findOverlap(World world, int x1, int z1, int x2, int z2, Land ignore) {
        if (world == null) {
            return null;
        }
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        Map<Long, List<Land>> chunks = index.get(world.getUID());
        if (chunks == null) {
            return null;
        }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                List<Land> list = chunks.get(chunkKey(cx, cz));
                if (list == null) {
                    continue;
                }
                for (Land land : list) {
                    if (land == ignore) {
                        continue;
                    }
                    if (land.minX <= maxX && land.maxX >= minX && land.minZ <= maxZ && land.maxZ >= minZ) {
                        return land;
                    }
                }
            }
        }
        return null;
    }

    synchronized Land create(World world, UUID owner, boolean admin, int x1, int z1, int x2, int z2) {
        Land land = new Land(UUID.randomUUID(), world.getUID(), admin ? null : owner, admin);
        land.set(x1, z1, x2, z2);
        lands.add(land);
        index(land);
        recompute(land.owner);
        save();
        return land;
    }

    synchronized boolean resize(Land land, int x1, int z1, int x2, int z2) {
        int oldMinX = land.minX;
        int oldMaxX = land.maxX;
        int oldMinZ = land.minZ;
        int oldMaxZ = land.maxZ;
        unindex(land);
        land.set(x1, z1, x2, z2);
        if (collides(land)) {
            land.minX = oldMinX;
            land.maxX = oldMaxX;
            land.minZ = oldMinZ;
            land.maxZ = oldMaxZ;
            index(land);
            return false;
        }
        index(land);
        recompute(land.owner);
        save();
        return true;
    }

    synchronized void remove(Land land) {
        unindex(land);
        lands.remove(land);
        recompute(land.owner);
        save();
    }

    synchronized int abandonOwned(UUID owner) {
        List<Land> owned = new ArrayList<>();
        for (Land land : lands) {
            if (!land.admin && owner.equals(land.owner)) {
                owned.add(land);
            }
        }
        for (Land land : owned) {
            unindex(land);
            lands.remove(land);
        }
        recompute(owner);
        if (!owned.isEmpty()) {
            save();
        }
        return owned.size();
    }

    synchronized void setTrust(Land land, UUID player, Level level) {
        if (level == Level.NONE) {
            land.trust.remove(player);
        } else {
            land.trust.put(player, level);
        }
        save();
    }

    synchronized void setPublic(Land land, Level level) {
        land.everyone = level == null ? Level.NONE : level;
        save();
    }

    synchronized void clearTrust(Land land) {
        land.trust.clear();
        land.everyone = Level.NONE;
        save();
    }

    synchronized Map<UUID, Level> trustOf(Land land) {
        return Map.copyOf(land.trust);
    }

    synchronized Level publicOf(Land land) {
        return land.everyone;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::accrue, 1200L, 1200L);
    }

    private synchronized void accrue() {
        if (accrualBlocks <= 0 || accrualMinutes <= 0 || maximum == 0) {
            return;
        }
        int need = accrualMinutes * 60;
        boolean unlimited = maximum < 0;
        boolean changed = false;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Balance balance = balances.get(player.getUniqueId());
            if (balance == null) {
                continue;
            }
            if (!unlimited && balance.accrued >= maximum) {
                balance.seconds = 0;
                continue;
            }
            balance.seconds += 60;
            while (balance.seconds >= need && (unlimited || balance.accrued < maximum)) {
                balance.seconds -= need;
                int grant = accrualBlocks;
                if (!unlimited) {
                    grant = Math.min(accrualBlocks, maximum - balance.accrued);
                }
                long sum = (long) balance.accrued + grant;
                if (sum >= Integer.MAX_VALUE) {
                    balance.accrued = Integer.MAX_VALUE;
                    balance.seconds = 0;
                    changed = true;
                    break;
                }
                balance.accrued = (int) sum;
                changed = true;
            }
            if (!unlimited && balance.accrued >= maximum) {
                balance.seconds = 0;
            }
        }
        if (changed) {
            save();
        }
    }

    private synchronized void remember(Player player) {
        Balance balance = balances.get(player.getUniqueId());
        if (balance == null) {
            balance = new Balance();
            int granted = Math.max(0, initialBlocks);
            if (maximum >= 0) {
                granted = Math.min(granted, maximum);
            }
            balance.accrued = granted;
            balance.name = player.getName();
            balances.put(player.getUniqueId(), balance);
            save();
            return;
        }
        if (!player.getName().equals(balance.name)) {
            balance.name = player.getName();
            save();
        }
    }

    private void loadSettings() {
        FileConfiguration config = plugin.getConfig();
        boolean rewrite = !config.isConfigurationSection("claims");
        initialBlocks = nonNegative(config.getInt("claims.initial-blocks", 50), 50);
        minimumSide = Math.max(1, config.getInt("claims.minimum-side", 5));
        borderSeconds = Math.max(1, config.getInt("claims.border-seconds", 10));
        combatSeconds = Math.max(1, config.getInt("claims.combat-seconds", 15));
        accrualMinutes = Math.max(1, config.getInt("claims.accrual.minutes", 60));
        accrualBlocks = Math.max(0, config.getInt("claims.accrual.blocks", 100));
        int configuredMaximum = config.getInt("claims.accrual.maximum", 8000);
        maximum = configuredMaximum == -1 ? -1 : Math.max(0, configuredMaximum);
        if (config.contains("claims.disabled-worlds")) {
            config.set("claims.disabled-worlds", null);
            rewrite = true;
        }
        if (!config.contains("claims.enabled-worlds")) {
            config.set("claims.enabled-worlds", List.of("Dreamers4"));
            rewrite = true;
        }
        Set<String> worlds = new java.util.HashSet<>();
        for (String world : config.getStringList("claims.enabled-worlds")) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.toLowerCase(Locale.ROOT));
            }
        }
        enabledWorlds = Set.copyOf(worlds);
        if (rewrite) {
            plugin.saveConfig();
        }
    }

    private static int nonNegative(int value, int fallback) {
        return value < 0 ? fallback : value;
    }

    private synchronized void reloadBalances() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                if (!players.contains(key + ".accrued")) {
                    continue;
                }
                Integer accrued = asAccrued(players.get(key + ".accrued"));
                if (accrued == null) {
                    plugin.getLogger().warning("Se ignoró el accrued de un jugador de terrenos porque no es un número.");
                    continue;
                }
                Balance balance = balances.get(id);
                if (balance == null) {
                    balance = new Balance();
                    balance.name = players.getString(key + ".name", "");
                    balance.seconds = Math.max(0, players.getInt(key + ".seconds"));
                    balances.put(id, balance);
                }
                balance.accrued = accrued;
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Se ignoró un jugador de terrenos con id inválido.");
            }
        }
    }

    private static Integer asAccrued(Object raw) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        long value = number.longValue();
        if (value < 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? null : (int) value;
    }

    private void loadData() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    Balance balance = new Balance();
                    balance.name = players.getString(key + ".name", "");
                    balance.accrued = Math.max(0, players.getInt(key + ".accrued"));
                    balance.seconds = Math.max(0, players.getInt(key + ".seconds"));
                    balances.put(id, balance);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Se ignoró un jugador de terrenos con id inválido.");
                }
            }
        }
        ConfigurationSection claims = yaml.getConfigurationSection("claims");
        if (claims != null) {
            for (String key : claims.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    UUID world = UUID.fromString(claims.getString(key + ".world", ""));
                    boolean admin = claims.getBoolean(key + ".admin");
                    UUID owner = null;
                    if (!admin) {
                        String rawOwner = claims.getString(key + ".owner");
                        if (rawOwner == null || rawOwner.isBlank()) {
                            continue;
                        }
                        owner = UUID.fromString(rawOwner);
                    }
                    Land land = new Land(id, world, owner, admin);
                    land.set(
                            claims.getInt(key + ".x1"),
                            claims.getInt(key + ".z1"),
                            claims.getInt(key + ".x2"),
                            claims.getInt(key + ".z2")
                    );
                    if (land.width() < 1 || land.length() < 1) {
                        continue;
                    }
                    land.everyone = Level.parse(claims.getString(key + ".public"));
                    ConfigurationSection trust = claims.getConfigurationSection(key + ".trust");
                    if (trust != null) {
                        for (String trusted : trust.getKeys(false)) {
                            Level level = Level.parse(trust.getString(trusted));
                            if (level == Level.NONE) {
                                continue;
                            }
                            land.trust.put(UUID.fromString(trusted), level);
                        }
                    }
                    lands.add(land);
                    index(land);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Se ignoró un terreno con datos inválidos.");
                }
            }
        }
        recomputeAll();
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Terrenos y claimblocks. Lo ajustable está en config.yml, sección claims.",
                "El accrued de cada jugador se puede cambiar aquí; /dreamers reload lo vuelve a leer."
        ));
        ConfigurationSection players = yaml.createSection("players");
        for (Map.Entry<UUID, Balance> entry : balances.entrySet()) {
            ConfigurationSection section = players.createSection(entry.getKey().toString());
            section.set("name", entry.getValue().name);
            section.set("accrued", entry.getValue().accrued);
            section.set("seconds", entry.getValue().seconds);
        }
        ConfigurationSection claims = yaml.createSection("claims");
        for (Land land : lands) {
            ConfigurationSection section = claims.createSection(land.id.toString());
            section.set("world", land.world.toString());
            section.set("x1", land.minX);
            section.set("z1", land.minZ);
            section.set("x2", land.maxX);
            section.set("z2", land.maxZ);
            section.set("admin", land.admin);
            if (land.owner != null) {
                section.set("owner", land.owner.toString());
            }
            if (land.everyone != Level.NONE) {
                section.set("public", land.everyone.save());
            }
            if (!land.trust.isEmpty()) {
                ConfigurationSection trust = section.createSection("trust");
                for (Map.Entry<UUID, Level> grant : land.trust.entrySet()) {
                    trust.set(grant.getKey().toString(), grant.getValue().save());
                }
            }
        }
        try {
            if (plugin.getDataFolder().mkdirs() || plugin.getDataFolder().isDirectory()) {
                yaml.save(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudieron guardar los terrenos.");
        }
    }

    private void recompute(UUID owner) {
        if (owner == null) {
            return;
        }
        int total = 0;
        for (Land land : lands) {
            if (!land.admin && owner.equals(land.owner)) {
                long sum = (long) total + land.area();
                total = sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            }
        }
        if (total == 0) {
            used.remove(owner);
        } else {
            used.put(owner, total);
        }
    }

    private void recomputeAll() {
        used.clear();
        for (Land land : lands) {
            recompute(land.owner);
        }
    }

    private boolean collides(Land land) {
        Map<Long, List<Land>> chunks = index.get(land.world);
        if (chunks == null) {
            return false;
        }
        for (int cx = land.minX >> 4; cx <= land.maxX >> 4; cx++) {
            for (int cz = land.minZ >> 4; cz <= land.maxZ >> 4; cz++) {
                List<Land> list = chunks.get(chunkKey(cx, cz));
                if (list == null) {
                    continue;
                }
                for (Land other : list) {
                    if (other != land && other.minX <= land.maxX && other.maxX >= land.minX
                            && other.minZ <= land.maxZ && other.maxZ >= land.minZ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void index(Land land) {
        Map<Long, List<Land>> chunks = index.computeIfAbsent(land.world, ignored -> new HashMap<>());
        for (int cx = land.minX >> 4; cx <= land.maxX >> 4; cx++) {
            for (int cz = land.minZ >> 4; cz <= land.maxZ >> 4; cz++) {
                chunks.computeIfAbsent(chunkKey(cx, cz), ignored -> new ArrayList<>()).add(land);
            }
        }
    }

    private void unindex(Land land) {
        Map<Long, List<Land>> chunks = index.get(land.world);
        if (chunks == null) {
            return;
        }
        for (int cx = land.minX >> 4; cx <= land.maxX >> 4; cx++) {
            for (int cz = land.minZ >> 4; cz <= land.maxZ >> 4; cz++) {
                long key = chunkKey(cx, cz);
                List<Land> list = chunks.get(key);
                if (list == null) {
                    continue;
                }
                list.remove(land);
                if (list.isEmpty()) {
                    chunks.remove(key);
                }
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }
}
