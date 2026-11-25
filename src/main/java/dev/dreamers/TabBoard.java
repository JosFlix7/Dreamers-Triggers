package dev.dreamers;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cabecera, lista vanilla, nametags, texto bajo el nombre, marcador y chat.
 * El listener se registra una sola vez.
 */
final class TabBoard implements Listener {
    private static final String BELOW = "dt_below";
    private static final String SIDE = "dt_side";
    private static final String DEFAULT_BELOW = "&c❤ %health%";
    private static final int DEFAULT_DISTANCE = 32;
    private static final String DEFAULT_TITLE = "&a🛜&f%ping% &d🎮&l%player%";
    private static final List<String> DEFAULT_HEADER = List.of(
            "&5&k--- &d&lDreamers 4.5: &6&lBetter Terranova &5&k---",
            "&bJava %server_version% &f- &aPong: &f%ping% &b/&r &aTPS: &f%tps%"
    );
    private static final List<String> DEFAULT_FOOTER = List.of(
            "&bJava/Bedrock IP: &fdreamersjosflix7.ddns.net"
    );
    private static final List<String> DEFAULT_LINES = List.of(
            "&b💡&f%player_light_level% &b⏰&f%player_world_time_24% &c☠&f%objective_score_{deaths}%",
            "&eClaimBL.: &r%dreamers_remainingclaims%",
            "&eZona de %dreamers_currentclaim_ownername%",
            "",
            "&c☠&b%objective_scorepos_{deaths}_{1}% &r%objective_entrypos_{deaths}_{1}%",
            "&c☠&b%objective_scorepos_{deaths}_{2}% &r%objective_entrypos_{deaths}_{2}%",
            "&c☠&b%objective_scorepos_{deaths}_{3}% &r%objective_entrypos_{deaths}_{3}%",
            "&c☠&b%objective_scorepos_{deaths}_{4}% &r%objective_entrypos_{deaths}_{4}%",
            "&c☠&b%objective_scorepos_{deaths}_{5}% &r%objective_entrypos_{deaths}_{5}%",
            "&c☠&b%objective_scorepos_{deaths}_{6}% &r%objective_entrypos_{deaths}_{6}%"
    );
    private static final Component PLAYERS_ONLY = Component.text("Este comando solo lo puede usar un jugador.", NamedTextColor.RED);
    private static final Component SHOWN = Component.text("El marcador ahora es visible.", NamedTextColor.GREEN);
    private static final Component HIDDEN_TEXT = Component.text("El marcador ahora está oculto.", NamedTextColor.GREEN);

    private final JavaPlugin plugin;
    private final Placeholders placeholders;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, TagText> chatTags = new ConcurrentHashMap<>();
    private final Map<UUID, String> tagSignature = new HashMap<>();
    private final Map<UUID, String> tabCache = new HashMap<>();
    private final Map<UUID, String> headerCache = new HashMap<>();
    private final Map<UUID, Integer> lineCounts = new HashMap<>();
    private final Map<UUID, Map<UUID, String>> belowState = new HashMap<>();
    private final Map<UUID, String> listNames = new HashMap<>();
    private final Map<UUID, Set<String>> listEntries = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Set<UUID> healthSoon = new HashSet<>();
    private final Set<BukkitTask> followUps = new HashSet<>();
    private Method boardHandle;
    private Method objectiveByName;
    private Method listScores;
    private Method scoreOwner;
    private Method scoreValue;
    private boolean scoresReady;
    private boolean scoresFast = true;
    private int listedTick = Integer.MIN_VALUE;
    private String listedObjective = "";
    private Collection<ListedScore> listedCache = List.of();
    private Map<String, User> users = Map.of();
    private List<Group> groups = List.of();
    private Design fallback = new Design(DEFAULT_HEADER, DEFAULT_FOOTER);
    private List<String> scoreLines = DEFAULT_LINES;
    private String scoreTitle = DEFAULT_TITLE;
    private static final String LOGIN_SECTION = "Login & Logout messages";
    private static final String DEFAULT_LOGIN = "&2+ %player%";
    private static final String DEFAULT_LOGOUT = "&4- %player%";

    private String belowFormat = DEFAULT_BELOW;
    private int belowDistance = DEFAULT_DISTANCE;
    private boolean loginMessages = true;
    private String loginMessage = DEFAULT_LOGIN;
    private String logoutMessage = DEFAULT_LOGOUT;
    private BukkitTask task;
    private boolean running;

    TabBoard(JavaPlugin plugin, Claims claims) {
        this.plugin = plugin;
        this.placeholders = new Placeholders(claims);
    }

    void enable() {
        load();
        running = true;
        start();
        refreshAll();
    }

    void reload() {
        running = false;
        cancelTasks();
        load();
        clearCaches();
        running = true;
        start();
        refreshAll();
    }

    void disable() {
        running = false;
        cancelTasks();
        restore();
        boards.clear();
        chatTags.clear();
        listNames.clear();
        listEntries.clear();
        clearCaches();
        hidden.clear();
    }

    void command(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return;
        }
        boolean nowHidden;
        if (hidden.remove(player.getUniqueId())) {
            nowHidden = false;
        } else {
            hidden.add(player.getUniqueId());
            nowHidden = true;
        }
        saveHidden();
        applySidebar(player);
        sender.sendMessage(nowHidden ? HIDDEN_TEXT : SHOWN);
    }

    private void load() {
        ensureConfig();
        FileConfiguration config = plugin.getConfig();
        loadDesigns(config);
        String below = config.getString("tab.belowname");
        belowFormat = below == null ? DEFAULT_BELOW : below;
        int distance = config.getInt("tab.belowname-distance", DEFAULT_DISTANCE);
        belowDistance = distance < 0 ? DEFAULT_DISTANCE : distance;
        String title = config.getString("scoreboard.title");
        scoreTitle = title == null ? DEFAULT_TITLE : title;
        scoreLines = config.isList("scoreboard.lines") ? List.copyOf(config.getStringList("scoreboard.lines")) : DEFAULT_LINES;
        hidden.clear();
        for (String raw : config.getStringList("scoreboard.hidden")) {
            try {
                hidden.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // Una entrada que no es un UUID no oculta a nadie.
            }
        }
        loadUsers();
        loadSessions(config);
    }

    private void loadSessions(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection(LOGIN_SECTION);
        if (section == null) {
            plugin.saveConfig();
            section = plugin.getConfig().getConfigurationSection(LOGIN_SECTION);
        }
        if (section == null) {
            loginMessages = true;
            loginMessage = DEFAULT_LOGIN;
            logoutMessage = DEFAULT_LOGOUT;
            return;
        }
        loginMessages = section.getBoolean("enabled", true);
        String login = section.getString("login");
        String logout = section.getString("logout");
        loginMessage = login == null || login.isBlank() ? DEFAULT_LOGIN : login;
        logoutMessage = logout == null || logout.isBlank() ? DEFAULT_LOGOUT : logout;
    }

    private void ensureConfig() {
        FileConfiguration config = plugin.getConfig();
        if (config.isConfigurationSection("tab")
                && config.isConfigurationSection("scoreboard")
                && config.isConfigurationSection(LOGIN_SECTION)) {
            return;
        }
        plugin.saveConfig();
    }

    private void loadDesigns(FileConfiguration config) {
        List<Group> next = new ArrayList<>();
        Design base = new Design(DEFAULT_HEADER, DEFAULT_FOOTER);
        ConfigurationSection designs = config.getConfigurationSection("tab.designs");
        if (designs != null) {
            for (String key : designs.getKeys(false)) {
                if (!key.equalsIgnoreCase("default")) {
                    continue;
                }
                ConfigurationSection section = designs.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                base = new Design(List.copyOf(section.getStringList("header")), footerLines(section, List.of()));
            }
            for (String key : designs.getKeys(false)) {
                if (key.equalsIgnoreCase("default")) {
                    continue;
                }
                ConfigurationSection section = designs.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                Set<String> worlds = new HashSet<>();
                for (String world : section.getStringList("worlds")) {
                    if (world != null && !world.isBlank()) {
                        worlds.add(world.toLowerCase(Locale.ROOT));
                    }
                }
                next.add(new Group(worlds, new Design(
                        List.copyOf(section.getStringList("header")),
                        footerLines(section, base.footer())
                )));
            }
        }
        groups = List.copyOf(next);
        fallback = base;
    }

    // getStringList no distingue footer: [] de un footer sin lista.
    private static List<String> footerLines(ConfigurationSection section, List<String> whenEmpty) {
        Object raw = section.get("footer");
        if (!(raw instanceof List<?> lines)) {
            return List.of();
        }
        if (lines.isEmpty()) {
            return whenEmpty;
        }
        return List.copyOf(section.getStringList("footer"));
    }

    private void loadUsers() {
        File file = new File(plugin.getDataFolder(), "users.yml");
        if (!file.exists()) {
            plugin.saveResource("users.yml", false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        // El separador de Bukkit es el punto, y el de Geyser también. Con otro separador la clave se queda entera.
        yaml.options().pathSeparator('\0');
        try {
            yaml.loadFromString(Files.readString(file.toPath()));
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("users.yml no se pudo leer: " + exception.getMessage());
            return;
        }
        Map<String, User> next = new HashMap<>();
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            next.put(key.toLowerCase(Locale.ROOT), new User(
                    text(section, "tabprefix"),
                    text(section, "tagprefix"),
                    text(section, "tabsuffix"),
                    text(section, "tagsuffix")
            ));
        }
        users = Map.copyOf(next);
    }

    private static String text(ConfigurationSection section, String key) {
        String value = section.getString(key, "");
        return value == null ? "" : value;
    }

    private void saveHidden() {
        List<String> stored = new ArrayList<>();
        for (UUID id : hidden) {
            stored.add(id.toString());
        }
        plugin.getConfig().set("scoreboard.hidden", stored);
        plugin.saveConfig();
    }

    private void start() {
        cancelTasks();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 10L, 10L);
    }

    private void cancelTasks() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BukkitTask pending : followUps) {
            pending.cancel();
        }
        followUps.clear();
        healthSoon.clear();
    }

    private void later(long delay, Runnable action) {
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            followUps.remove(holder[0]);
            if (!running) {
                return;
            }
            action.run();
        }, delay);
        followUps.add(holder[0]);
    }

    private void clearCaches() {
        tagSignature.clear();
        tabCache.clear();
        headerCache.clear();
        lineCounts.clear();
        belowState.clear();
    }

    private void restore() {
        ScoreboardManager manager = plugin.getServer().getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard main = manager.getMainScoreboard();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.setScoreboard(main);
            player.playerListName(null);
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private void refreshAll() {
        if (!running) {
            return;
        }
        List<Player> online = online();
        Map<UUID, Tag> tags = new HashMap<>();
        for (Player target : online) {
            tags.put(target.getUniqueId(), resolveTag(target));
        }
        for (Player viewer : online) {
            if (board(viewer) == null) {
                continue;
            }
            applyHeader(viewer);
            applySidebar(viewer);
            applyTab(viewer, tags.get(viewer.getUniqueId()));
            for (Player target : online) {
                applyTeam(viewer, target, tags.get(target.getUniqueId()));
                applyBelow(viewer, target);
            }
            mirrorList(viewer);
        }
        for (Map.Entry<UUID, Tag> entry : tags.entrySet()) {
            Tag tag = entry.getValue();
            tagSignature.put(entry.getKey(), tag.tagPrefix() + "\0" + tag.tagSuffix());
        }
    }

    private Tag resolveTag(Player player) {
        User user = users.get(player.getName().toLowerCase(Locale.ROOT));
        if (user == null) {
            return new Tag("", "");
        }
        return new Tag(
                placeholders.apply(user.tagPrefix(), player),
                placeholders.apply(user.tagSuffix(), player)
        );
    }

    private void applyHeader(Player player) {
        Design design = designFor(player.getWorld().getName());
        StringBuilder key = new StringBuilder();
        for (String line : design.header()) {
            key.append(placeholders.apply(line, player)).append('\n');
        }
        key.append('\0');
        for (String line : design.footer()) {
            key.append(placeholders.apply(line, player)).append('\n');
        }
        String text = key.toString();
        if (text.equals(headerCache.get(player.getUniqueId()))) {
            return;
        }
        headerCache.put(player.getUniqueId(), text);
        player.sendPlayerListHeaderAndFooter(lines(design.header(), player), lines(design.footer(), player));
    }

    private Component lines(List<String> lines, Player player) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            String line = lines.get(i);
            if (line == null || line.isEmpty()) {
                continue;
            }
            out = out.append(Placeholders.LEGACY.deserialize(placeholders.apply(line, player)));
        }
        return out;
    }

    private Design designFor(String world) {
        String key = world.toLowerCase(Locale.ROOT);
        for (Group group : groups) {
            if (group.worlds().contains(key)) {
                return group.design();
            }
        }
        return fallback;
    }

    private void applySidebar(Player player) {
        Scoreboard board = board(player);
        if (board == null) {
            return;
        }
        Objective side = board.getObjective(SIDE);
        if (side == null) {
            return;
        }
        side.displayName(Placeholders.LEGACY.deserialize(placeholders.apply(scoreTitle, player)));
        for (int i = 0; i < scoreLines.size(); i++) {
            String resolved = placeholders.apply(scoreLines.get(i), player);
            Score score = side.getScore(lineEntry(i));
            score.setScore(scoreLines.size() - i);
            score.customName(resolved.isEmpty() ? Component.space() : Placeholders.LEGACY.deserialize(resolved));
            score.numberFormat(NumberFormat.blank());
        }
        Integer previous = lineCounts.get(player.getUniqueId());
        int old = previous == null ? 0 : previous;
        for (int i = scoreLines.size(); i < old; i++) {
            Score extra = side.getScore(lineEntry(i));
            if (extra.isScoreSet()) {
                extra.resetScore();
            }
        }
        lineCounts.put(player.getUniqueId(), scoreLines.size());
        if (hidden.contains(player.getUniqueId())) {
            if (side.getDisplaySlot() == DisplaySlot.SIDEBAR) {
                board.clearSlot(DisplaySlot.SIDEBAR);
            }
        } else if (side.getDisplaySlot() != DisplaySlot.SIDEBAR) {
            side.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
    }

    private static String lineEntry(int index) {
        return "\u00A7" + index;
    }

    private void applyTab(Player player, Tag tag) {
        if (tag == null) {
            return;
        }
        chatTags.put(player.getUniqueId(), new TagText(tag.tagPrefix(), tag.tagSuffix(), player.getName()));
        // El nombre de la lista va completo. El equipo solo alimenta el nametag,
        // porque con el nombre puesto el cliente no le suma el prefijo del equipo.
        User user = users.get(player.getName().toLowerCase(Locale.ROOT));
        String prefix = user == null ? "" : placeholders.apply(user.tabPrefix(), player);
        String suffix = user == null ? "" : placeholders.apply(user.tabSuffix(), player);
        String tab = prefix + player.getName() + suffix;
        if (tab.equals(tabCache.get(player.getUniqueId()))) {
            return;
        }
        tabCache.put(player.getUniqueId(), tab);
        player.playerListName(Placeholders.LEGACY.deserialize(tab));
    }

    private void applyTeam(Player viewer, Player target, Tag tag) {
        if (tag == null) {
            return;
        }
        Scoreboard board = boards.get(viewer.getUniqueId());
        if (board == null) {
            return;
        }
        String name = target.getName();
        Team team = board.getTeam(name);
        boolean created = team == null;
        if (created) {
            team = board.registerNewTeam(name);
            team.addEntry(name);
        }
        String signature = tag.tagPrefix() + "\0" + tag.tagSuffix();
        if (!created && signature.equals(tagSignature.get(target.getUniqueId()))) {
            return;
        }
        team.prefix(Placeholders.LEGACY.deserialize(tag.tagPrefix()));
        team.suffix(Placeholders.LEGACY.deserialize(tag.tagSuffix()));
        team.color(lastColor(tag.tagPrefix()));
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS);
    }

    private static NamedTextColor lastColor(String text) {
        NamedTextColor color = null;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) != '&') {
                continue;
            }
            char code = Character.toLowerCase(text.charAt(i + 1));
            if (code == 'r') {
                color = null;
                continue;
            }
            NamedTextColor next = colorCode(code);
            if (next != null) {
                color = next;
            }
        }
        return color;
    }

    private static NamedTextColor colorCode(char code) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private void applyBelow(Player viewer, Player target) {
        Scoreboard board = boards.get(viewer.getUniqueId());
        if (board == null) {
            return;
        }
        Objective objective = board.getObjective(BELOW);
        if (objective == null) {
            return;
        }
        Map<UUID, String> shown = belowState.computeIfAbsent(viewer.getUniqueId(), id -> new HashMap<>());
        if (!near(viewer, target)) {
            if (shown.remove(target.getUniqueId()) != null) {
                Score score = objective.getScore(target.getName());
                if (score.isScoreSet()) {
                    score.resetScore();
                }
            }
            return;
        }
        String text = placeholders.apply(belowFormat, target);
        if (text.equals(shown.get(target.getUniqueId()))) {
            return;
        }
        shown.put(target.getUniqueId(), text);
        Score score = objective.getScore(target.getName());
        score.setScore(0);
        Component component = text.isEmpty() ? Component.empty() : Placeholders.LEGACY.deserialize(text);
        score.numberFormat(NumberFormat.fixed(component));
    }

    private boolean near(Player viewer, Player target) {
        if (viewer.getWorld() != target.getWorld()) {
            return false;
        }
        double max = (double) belowDistance * belowDistance;
        return viewer.getLocation().distanceSquared(target.getLocation()) <= max;
    }

    private void mirrorList(Player viewer) {
        Scoreboard board = boards.get(viewer.getUniqueId());
        if (board == null) {
            return;
        }
        ScoreboardManager manager = plugin.getServer().getScoreboardManager();
        if (manager == null) {
            return;
        }
        Objective source = manager.getMainScoreboard().getObjective(DisplaySlot.PLAYER_LIST);
        dropLegacyHearts(board, source == null ? null : source.getName());
        if (source == null) {
            clearList(viewer, board);
            return;
        }
        String name = source.getName();
        String previous = listNames.get(viewer.getUniqueId());
        if (previous != null && !previous.equals(name)) {
            clearList(viewer, board);
        }
        Objective mirror = board.getObjective(name);
        RenderType render = source.getRenderType();
        Component title = source.displayName();
        if (mirror == null) {
            mirror = board.registerNewObjective(name, Criteria.DUMMY, title, render);
        } else {
            if (mirror.getRenderType() != render) {
                mirror.setRenderType(render);
            }
            if (!mirror.displayName().equals(title)) {
                mirror.displayName(title);
            }
        }
        if (mirror.getDisplaySlot() != DisplaySlot.PLAYER_LIST) {
            mirror.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        }
        listNames.put(viewer.getUniqueId(), name);
        copyScores(viewer.getUniqueId(), source, mirror);
    }

    private void dropLegacyHearts(Scoreboard board, String keep) {
        Objective legacy = board.getObjective("dt_hearts");
        if (legacy != null && !"dt_hearts".equals(keep)) {
            legacy.unregister();
        }
    }

    private void clearList(Player viewer, Scoreboard board) {
        UUID id = viewer.getUniqueId();
        Objective shown = board.getObjective(DisplaySlot.PLAYER_LIST);
        if (shown != null) {
            shown.unregister();
        }
        String name = listNames.remove(id);
        if (name != null) {
            Objective leftover = board.getObjective(name);
            if (leftover != null) {
                leftover.unregister();
            }
        }
        listEntries.remove(id);
    }

    private void copyScores(UUID id, Objective source, Objective mirror) {
        Set<String> next = new HashSet<>();
        Collection<ListedScore> listed = listedScores(source);
        if (listed == null) {
            copyScoresFromBoard(id, source, mirror);
            return;
        }
        for (ListedScore listedScore : listed) {
            next.add(listedScore.entry());
            Score copy = mirror.getScore(listedScore.entry());
            if (!copy.isScoreSet() || copy.getScore() != listedScore.value()) {
                copy.setScore(listedScore.value());
            }
        }
        forgetMissing(id, mirror, next);
    }

    private void copyScoresFromBoard(UUID id, Objective source, Objective mirror) {
        Set<String> next = new HashSet<>();
        Scoreboard main = source.getScoreboard();
        for (String entry : List.copyOf(main.getEntries())) {
            Score score = scoreOf(main, source, entry);
            if (score == null) {
                continue;
            }
            next.add(entry);
            int value = score.getScore();
            Score copy = mirror.getScore(entry);
            if (!copy.isScoreSet() || copy.getScore() != value) {
                copy.setScore(value);
            }
        }
        forgetMissing(id, mirror, next);
    }

    private void forgetMissing(UUID id, Objective mirror, Set<String> next) {
        Set<String> previous = listEntries.computeIfAbsent(id, ignored -> new HashSet<>());
        for (String entry : List.copyOf(previous)) {
            if (next.contains(entry)) {
                continue;
            }
            Score copy = mirror.getScore(entry);
            if (copy.isScoreSet()) {
                copy.resetScore();
            }
        }
        previous.clear();
        previous.addAll(next);
    }

    private Collection<ListedScore> listedScores(Objective source) {
        if (!scoresFast) {
            return null;
        }
        int tick = plugin.getServer().getCurrentTick();
        String name = source.getName();
        if (listedTick == tick && name.equals(listedObjective)) {
            return listedCache;
        }
        try {
            if (!scoresReady) {
                bindListedScores();
                scoresReady = true;
            }
            Object board = boardHandle.invoke(source.getScoreboard());
            Object objective = objectiveByName.invoke(board, name);
            Collection<ListedScore> out = List.of();
            if (objective != null) {
                Collection<?> found = (Collection<?>) listScores.invoke(board, objective);
                List<ListedScore> scores = new ArrayList<>(found.size());
                for (Object entry : found) {
                    String owner = (String) scoreOwner.invoke(entry);
                    if (owner == null) {
                        continue;
                    }
                    scores.add(new ListedScore(owner, (Integer) scoreValue.invoke(entry)));
                }
                out = scores;
            }
            listedTick = tick;
            listedObjective = name;
            listedCache = out;
            return out;
        } catch (ReflectiveOperationException exception) {
            scoresFast = false;
            plugin.getLogger().warning(
                    "No se pudieron leer las puntuaciones de la lista sin recorrer el tablero: "
                            + exception.getMessage()
            );
            return null;
        }
    }

    private void bindListedScores() throws ReflectiveOperationException {
        Class<?> craftBoard = Class.forName("org.bukkit.craftbukkit.scoreboard.CraftScoreboard");
        boardHandle = craftBoard.getMethod("getHandle");
        Class<?> nmsBoard = boardHandle.getReturnType();
        objectiveByName = nmsBoard.getMethod("getObjective", String.class);
        Class<?> nmsObjective = Class.forName("net.minecraft.world.scores.Objective");
        listScores = nmsBoard.getMethod("listPlayerScores", nmsObjective);
        Class<?> entry = Class.forName("net.minecraft.world.scores.PlayerScoreEntry");
        scoreOwner = entry.getMethod("owner");
        scoreValue = entry.getMethod("value");
    }

    private static Score scoreOf(Scoreboard board, Objective objective, String entry) {
        String name = objective.getName();
        for (Score score : board.getScores(entry)) {
            Objective owner = score.getObjective();
            if (owner != null && name.equals(owner.getName()) && score.isScoreSet()) {
                return score;
            }
        }
        return null;
    }

    private void holdList(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        Objective mirror = board.getObjective(DisplaySlot.PLAYER_LIST);
        if (mirror == null) {
            return;
        }
        mirror.setDisplaySlot(null);
        mirror.setDisplaySlot(DisplaySlot.PLAYER_LIST);
    }

    private Scoreboard board(Player player) {
        Scoreboard scoreboard = boards.get(player.getUniqueId());
        if (scoreboard == null) {
            ScoreboardManager manager = plugin.getServer().getScoreboardManager();
            if (manager == null) {
                return null;
            }
            scoreboard = manager.getNewScoreboard();
            Objective below = scoreboard.registerNewObjective(BELOW, Criteria.DUMMY, Component.empty(), RenderType.INTEGER);
            below.setDisplaySlot(DisplaySlot.BELOW_NAME);
            Objective side = scoreboard.registerNewObjective(SIDE, Criteria.DUMMY, Component.empty(), RenderType.INTEGER);
            side.numberFormat(NumberFormat.blank());
            boards.put(player.getUniqueId(), scoreboard);
        }
        if (player.getScoreboard() != scoreboard) {
            player.setScoreboard(scoreboard);
        }
        return scoreboard;
    }

    private List<Player> online() {
        return List.copyOf(plugin.getServer().getOnlinePlayers());
    }

    private void queueHealth(Player player) {
        if (!running || !healthSoon.add(player.getUniqueId())) {
            return;
        }
        later(1L, () -> {
            healthSoon.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            for (Player viewer : online()) {
                applyBelow(viewer, player);
                mirrorList(viewer);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!running) {
            return;
        }
        if (loginMessages) {
            event.joinMessage(sessionMessage(loginMessage, event.getPlayer()));
        }
        refreshAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (running && loginMessages) {
            event.quitMessage(sessionMessage(logoutMessage, player));
        }
        UUID id = player.getUniqueId();
        boards.remove(id);
        chatTags.remove(id);
        tagSignature.remove(id);
        tabCache.remove(id);
        headerCache.remove(id);
        lineCounts.remove(id);
        belowState.remove(id);
        listNames.remove(id);
        listEntries.remove(id);
        healthSoon.remove(id);
        for (Map<UUID, String> shown : belowState.values()) {
            shown.remove(id);
        }
        String name = player.getName();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(id)) {
                continue;
            }
            Scoreboard board = boards.get(viewer.getUniqueId());
            if (board == null) {
                continue;
            }
            Team team = board.getTeam(name);
            if (team != null) {
                team.unregister();
            }
            board.resetScores(name);
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        if (!running) {
            return;
        }
        refreshAll();
        holdList(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!running) {
            return;
        }
        Player player = event.getPlayer();
        refreshAll();
        holdList(player);
        later(1L, () -> {
            if (!player.isOnline()) {
                return;
            }
            refreshAll();
            holdList(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (running && event.getEntity() instanceof Player player) {
            queueHealth(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (running && event.getEntity() instanceof Player player) {
            queueHealth(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!running) {
            return;
        }
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            TagText tag = chatTags.get(source.getUniqueId());
            String prefix = tag == null ? "" : tag.prefix();
            String suffix = tag == null ? "" : tag.suffix();
            String name = tag == null ? source.getName() : tag.name();
            return Placeholders.LEGACY.deserialize("<" + prefix + name + suffix + "&r> ").append(message);
        }));
    }

    private Component sessionMessage(String format, Player player) {
        String text = placeholders.apply(format, player);
        return text.isEmpty() ? Component.empty() : Placeholders.LEGACY.deserialize(text);
    }

    private record ListedScore(String entry, int value) {
    }

    private record User(String tabPrefix, String tagPrefix, String tabSuffix, String tagSuffix) {
    }

    private record Tag(String tagPrefix, String tagSuffix) {
    }

    private record TagText(String prefix, String suffix, String name) {
    }

    private record Design(List<String> header, List<String> footer) {
    }

    private record Group(Set<String> worlds, Design design) {
    }
}
