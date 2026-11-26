package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Jugador, servidor, objetivos y terrenos. Un token desconocido se deja igual.
 */
final class Placeholders {
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final long MIB = 1_048_576L;
    private static final long WORLD_COUNT_MILLIS = 60_000L;
    private static final int TICKS_AT_MIDNIGHT = 18000;
    private static final int TICKS_PER_DAY = 24000;
    private static final int TICKS_PER_HOUR = 1000;
    private static final double TICKS_PER_MINUTE = 1000.0D / 60.0D;
    private static final DateTimeFormatter HOUR_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");
    private static final BlockFace[] RADIAL = {
            BlockFace.NORTH,
            BlockFace.NORTH_EAST,
            BlockFace.EAST,
            BlockFace.SOUTH_EAST,
            BlockFace.SOUTH,
            BlockFace.SOUTH_WEST,
            BlockFace.WEST,
            BlockFace.NORTH_WEST
    };

    private final JavaPlugin plugin;
    private final Claims claims;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Locale timeLocale = Locale.getDefault();
    private final Map<String, DateTimeFormatter> formats = new HashMap<>();
    private final Set<String> badFormats = new HashSet<>();
    private final Runtime runtime = Runtime.getRuntime();
    private Objective rankedObjective;
    private List<Ranked> rankedScores = List.of();
    private int rankedTick = Integer.MIN_VALUE;
    private Method boardHandle;
    private Method objectiveByName;
    private Method listScores;
    private Method scoreOwner;
    private Method scoreValue;
    private boolean scoresReady;
    private boolean scoresFast = true;
    private long worldCountsAt;
    private int cachedChunks;
    private int cachedLiving;
    private int cachedEntities;

    Placeholders(JavaPlugin plugin, Claims claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    String apply(String text, Player player) {
        return apply(text, player, 0);
    }

    private String apply(String text, Player player, int depth) {
        if (text == null || text.isEmpty() || depth > 4 || text.indexOf('%') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf('%', cursor);
            if (start < 0) {
                out.append(text, cursor, text.length());
                break;
            }
            int end = text.indexOf('%', start + 1);
            if (end < 0) {
                out.append(text, cursor, text.length());
                break;
            }
            out.append(text, cursor, start);
            String token = text.substring(start + 1, end);
            String value = resolve(token, player, depth);
            if (value == null) {
                out.append('%').append(token).append('%');
            } else {
                out.append(value);
            }
            cursor = end + 1;
        }
        return out.toString();
    }

    private String resolve(String token, Player player, int depth) {
        if (token.isEmpty()) {
            return null;
        }
        String shortcut = shortcut(token, player);
        if (shortcut != null || isShortcut(token)) {
            return shortcut;
        }
        if (token.regionMatches(true, 0, "dreamers_", 0, "dreamers_".length())) {
            return dreamers(token.substring("dreamers_".length()), player);
        }
        if (token.startsWith("player_")) {
            return playerToken(player, token.substring("player_".length()));
        }
        if (token.startsWith("server_")) {
            return serverToken(player, token.substring("server_".length()), depth);
        }
        if (token.startsWith("objective_")) {
            return objective(player, token.substring("objective_".length()));
        }
        return null;
    }

    private static boolean isShortcut(String token) {
        return token.equals("player")
                || token.equals("world")
                || token.equals("ping")
                || token.equals("health")
                || token.equals("tps");
    }

    private static String shortcut(String token, Player player) {
        return switch (token) {
            case "player" -> player == null ? "" : player.getName();
            case "world" -> player == null ? "" : player.getWorld().getName();
            case "ping" -> player == null ? "0" : Integer.toString(player.getPing());
            case "health" -> player == null ? "0" : Integer.toString(ceilHealth(player));
            case "tps" -> tpsOne();
            default -> null;
        };
    }

    static int ceilHealth(Player player) {
        double health = player.getHealth();
        if (health <= 0.0D) {
            return 0;
        }
        return Math.max(0, (int) Math.ceil(health));
    }

    private String dreamers(String token, Player player) {
        if (token.equalsIgnoreCase("remainingclaims")) {
            return player == null ? "0" : Integer.toString(claims.remaining(player.getUniqueId()));
        }
        if (!token.equalsIgnoreCase("currentclaim_ownername")) {
            return null;
        }
        if (player == null) {
            return "nadie";
        }
        Claims.Land land = claims.at(player.getLocation());
        return land == null ? "nadie" : claims.ownerName(land);
    }

    private String playerToken(Player player, String id) {
        if (id.startsWith("colored_ping_")) {
            return pingOf(Bukkit.getPlayer(id.substring("colored_ping_".length())), true);
        }
        if (id.startsWith("ping_")) {
            return pingOf(Bukkit.getPlayer(id.substring("ping_".length())), false);
        }
        if (player == null) {
            return "";
        }
        if (id.startsWith("has_permission_")) {
            String permission = id.substring("has_permission_".length());
            return permission.isEmpty() ? bool(false) : bool(player.hasPermission(permission));
        }
        if (id.startsWith("has_potioneffect_")) {
            String name = id.substring("has_potioneffect_".length());
            PotionEffectType effect = potion(name);
            return bool(effect != null && player.hasPotionEffect(effect));
        }
        if (id.startsWith("item_in_hand_level_")) {
            return enchantLevel(itemInHand(player), id.substring("item_in_hand_level_".length()));
        }
        if (id.startsWith("item_in_offhand_level_")) {
            return enchantLevel(player.getInventory().getItemInOffHand(), id.substring("item_in_offhand_level_".length()));
        }
        if (id.startsWith("locale")) {
            String locale = localeOf(player);
            String tag = locale.replace('_', '-');
            Locale parsed = Locale.forLanguageTag(tag);
            switch (id) {
                case "locale":
                    return locale;
                case "locale_country":
                    return parsed.getCountry();
                case "locale_display_country":
                    return parsed.getDisplayCountry();
                case "locale_display_name":
                    return parsed.getDisplayName();
                case "locale_short":
                    int cut = locale.indexOf('_');
                    if (cut < 0) {
                        cut = locale.indexOf('-');
                    }
                    return cut < 0 ? locale : locale.substring(0, cut);
                default:
                    break;
            }
        }
        return switch (id) {
            case "name" -> player.getName();
            case "uuid" -> player.getUniqueId().toString();
            case "has_played_before" -> bool(player.hasPlayedBefore());
            case "online" -> bool(player.isOnline());
            case "is_whitelisted" -> bool(player.isWhitelisted());
            case "is_banned" -> bool(player.isBanned());
            case "is_op" -> bool(player.isOp());
            case "first_played", "first_join" -> Long.toString(player.getFirstPlayed());
            case "first_played_formatted", "first_join_date" -> DATE.format(java.time.Instant.ofEpochMilli(player.getFirstPlayed()).atZone(zone));
            case "last_played", "last_join" -> Long.toString(player.getLastSeen());
            case "last_played_formatted", "last_join_date" -> DATE.format(java.time.Instant.ofEpochMilli(player.getLastSeen()).atZone(zone));
            case "bed_x" -> exact(bed(player), Location::getX);
            case "bed_y" -> exact(bed(player), Location::getY);
            case "bed_z" -> exact(bed(player), Location::getZ);
            case "bed_world" -> bed(player) == null || bed(player).getWorld() == null ? "" : bed(player).getWorld().getName();
            case "absorption" -> Integer.toString((int) player.getAbsorptionAmount());
            case "has_empty_slot" -> bool(player.getInventory().firstEmpty() > -1);
            case "empty_slots" -> Integer.toString(emptySlots(player));
            case "server", "servername" -> "now available in the server expansion";
            case "displayname" -> plain(player.displayName());
            case "list_name" -> player.playerListName() == null ? player.getName() : plain(player.playerListName());
            case "gamemode" -> player.getGameMode().name();
            case "direction" -> directionName(direction(player));
            case "direction_xz" -> xzDirection(player);
            case "world" -> player.getWorld().getName();
            case "world_type" -> worldType(player.getWorld().getEnvironment());
            case "x" -> Integer.toString(player.getLocation().getBlockX());
            case "x_long" -> String.valueOf(player.getLocation().getX());
            case "y" -> Integer.toString(player.getLocation().getBlockY());
            case "y_long" -> String.valueOf(player.getLocation().getY());
            case "z" -> Integer.toString(player.getLocation().getBlockZ());
            case "z_long" -> String.valueOf(player.getLocation().getZ());
            case "yaw" -> String.valueOf(player.getLocation().getYaw());
            case "pitch" -> String.valueOf(player.getLocation().getPitch());
            case "biome" -> biomeName(player);
            case "biome_capitalized" -> capitalizedBiome(player);
            case "light_level" -> Integer.toString(player.getLocation().getBlock().getLightLevel());
            case "ip" -> address(player);
            case "allow_flight" -> bool(player.getAllowFlight());
            case "can_pickup_items" -> bool(player.getCanPickupItems());
            case "compass_x" -> block(player.getCompassTarget(), Location::getBlockX);
            case "compass_y" -> block(player.getCompassTarget(), Location::getBlockY);
            case "compass_z" -> block(player.getCompassTarget(), Location::getBlockZ);
            case "compass_world" -> player.getCompassTarget() == null || player.getCompassTarget().getWorld() == null
                    ? ""
                    : player.getCompassTarget().getWorld().getName();
            case "block_underneath" -> String.valueOf(player.getLocation().subtract(0, 1, 0).getBlock().getType());
            case "custom_name" -> player.customName() == null ? player.getName() : plain(player.customName());
            case "exp" -> String.valueOf(player.getExp());
            case "current_exp" -> Integer.toString(totalExperience(player));
            case "total_exp" -> Integer.toString(player.getTotalExperience());
            case "exp_to_level" -> Integer.toString(player.getExpToLevel());
            case "level" -> Integer.toString(player.getLevel());
            case "fly_speed" -> String.valueOf(player.getFlySpeed());
            case "food_level" -> Integer.toString(player.getFoodLevel());
            case "health" -> String.valueOf(player.getHealth());
            case "health_rounded" -> Long.toString(Math.round(player.getHealth()));
            case "health_scale" -> String.valueOf(player.getHealthScale());
            case "has_health_boost" -> bool(player.hasPotionEffect(PotionEffectType.HEALTH_BOOST));
            case "health_boost" -> player.getHealthScale() > 20.0D ? Double.toString(player.getHealthScale() - 20.0D) : "0";
            case "item_in_hand" -> String.valueOf(itemInHand(player).getType());
            case "item_in_hand_name" -> itemName(itemInHand(player));
            case "item_in_hand_data" -> itemData(itemInHand(player));
            case "item_in_hand_durability" -> Integer.toString(remainingDurability(itemInHand(player)));
            case "item_in_offhand" -> String.valueOf(player.getInventory().getItemInOffHand().getType());
            case "item_in_offhand_name" -> itemName(player.getInventory().getItemInOffHand());
            case "item_in_offhand_data" -> itemData(player.getInventory().getItemInOffHand());
            case "item_in_offhand_durability" -> Integer.toString(remainingDurability(player.getInventory().getItemInOffHand()));
            case "last_damage" -> String.valueOf(player.getLastDamage());
            case "max_health" -> String.valueOf(maxHealth(player));
            case "max_health_rounded" -> Long.toString(Math.round(maxHealth(player)));
            case "max_air" -> Integer.toString(player.getMaximumAir());
            case "max_no_damage_ticks" -> Integer.toString(player.getMaximumNoDamageTicks());
            case "no_damage_ticks" -> Integer.toString(player.getNoDamageTicks());
            case "armor_helmet_name" -> itemName(player.getInventory().getHelmet());
            case "armor_helmet_data" -> armorData(player.getInventory().getHelmet());
            case "armor_helmet_durability" -> Integer.toString(remainingDurability(player.getInventory().getHelmet()));
            case "armor_chestplate_name" -> itemName(player.getInventory().getChestplate());
            case "armor_chestplate_data" -> armorData(player.getInventory().getChestplate());
            case "armor_chestplate_durability" -> Integer.toString(remainingDurability(player.getInventory().getChestplate()));
            case "armor_leggings_name" -> itemName(player.getInventory().getLeggings());
            case "armor_leggings_data" -> armorData(player.getInventory().getLeggings());
            case "armor_leggings_durability" -> Integer.toString(remainingDurability(player.getInventory().getLeggings()));
            case "armor_boots_name" -> itemName(player.getInventory().getBoots());
            case "armor_boots_data" -> armorData(player.getInventory().getBoots());
            case "armor_boots_durability" -> Integer.toString(remainingDurability(player.getInventory().getBoots()));
            case "ping" -> Integer.toString(player.getPing());
            case "colored_ping" -> coloredPing(player.getPing());
            case "time" -> Long.toString(player.getPlayerTime());
            case "time_offset" -> Long.toString(player.getPlayerTimeOffset());
            case "remaining_air" -> Integer.toString(player.getRemainingAir());
            case "saturation" -> String.valueOf(player.getSaturation());
            case "sleep_ticks" -> Integer.toString(player.getSleepTicks());
            case "thunder_duration" -> Integer.toString(player.getWorld().getThunderDuration());
            case "ticks_lived" -> Integer.toString(player.getTicksLived());
            case "seconds_lived" -> Integer.toString(player.getTicksLived() / 20);
            case "minutes_lived" -> Integer.toString(player.getTicksLived() / 20 / 60);
            case "walk_speed" -> String.valueOf(player.getWalkSpeed());
            case "weather_duration" -> Integer.toString(player.getWorld().getWeatherDuration());
            case "world_time" -> Long.toString(player.getWorld().getTime());
            case "world_time_12" -> format12(player.getWorld().getTime());
            case "world_time_24" -> ticksToTime(player.getWorld().getTime());
            case "is_flying" -> bool(player.isFlying());
            case "is_sleeping" -> bool(player.isSleeping());
            case "is_conversing" -> bool(player.isConversing());
            case "is_dead" -> bool(player.isDead());
            case "is_sneaking" -> bool(player.isSneaking());
            case "is_sprinting" -> bool(player.isSprinting());
            case "is_leashed" -> bool(player.isLeashed());
            case "is_inside_vehicle" -> bool(player.isInsideVehicle());
            default -> null;
        };
    }

    private String serverToken(Player player, String params, int depth) {
        switch (params) {
            case "online":
                return Integer.toString(Bukkit.getOnlinePlayers().size());
            case "max_players":
                return Integer.toString(Bukkit.getMaxPlayers());
            case "unique_joins":
                return Integer.toString(Bukkit.getOfflinePlayers().length);
            case "name":
                return "A Minecraft Server";
            case "variant":
                return io.papermc.paper.ServerBuildInfo.buildInfo().brandName();
            case "version":
                return Bukkit.getMinecraftVersion();
            case "build":
                return serverBuild();
            case "version_build":
            case "version_full":
                return Bukkit.getMinecraftVersion() + "-" + serverBuild();
            case "ram_used":
                return Long.toString((runtime.totalMemory() - runtime.freeMemory()) / MIB);
            case "ram_free":
                return Long.toString(runtime.freeMemory() / MIB);
            case "ram_total":
                return Long.toString(runtime.totalMemory() / MIB);
            case "ram_max":
                return Long.toString(runtime.maxMemory() / MIB);
            case "tps":
                return allTps(this::coloredTps);
            case "uptime":
                return formatSeconds(TimeUnit.MILLISECONDS.toSeconds(ManagementFactory.getRuntimeMXBean().getUptime()));
            case "total_chunks":
                refreshWorldCounts();
                return Integer.toString(cachedChunks);
            case "total_living_entities":
                refreshWorldCounts();
                return Integer.toString(cachedLiving);
            case "total_entities":
                refreshWorldCounts();
                return Integer.toString(cachedEntities);
            case "has_whitelist":
                return bool(Bukkit.hasWhitelist());
            default:
                break;
        }
        if (params.startsWith("tps_")) {
            return tpsType(params.substring("tps_".length()));
        }
        if (params.startsWith("online_")) {
            return Integer.toString(onlineIn(params.substring("online_".length())));
        }
        if (params.startsWith("time_")) {
            return formatNow(params.substring("time_".length()));
        }
        if (params.startsWith("countdown_raw_")) {
            return countdown(player, params.substring("countdown_raw_".length()), true, false, depth);
        }
        if (params.startsWith("countdown_")) {
            return countdown(player, params.substring("countdown_".length()), true, true, depth);
        }
        if (params.startsWith("countup_raw_")) {
            return countdown(player, params.substring("countup_raw_".length()), false, false, depth);
        }
        if (params.startsWith("countup_")) {
            return countdown(player, params.substring("countup_".length()), false, true, depth);
        }
        return null;
    }

    private String objective(Player player, String identifier) {
        if (identifier.startsWith("scorepos_")) {
            return rankValue(identifier.substring("scorepos_".length()), true);
        }
        if (identifier.startsWith("entrypos_")) {
            return rankValue(identifier.substring("entrypos_".length()), false);
        }
        if (identifier.startsWith("score_")) {
            return scoreValue(player, identifier.substring("score_".length()));
        }
        return null;
    }

    private String scoreValue(Player player, String rest) {
        if (rest.startsWith("{")) {
            Piece objectiveName = braces(rest, 0);
            if (objectiveName == null) {
                return null;
            }
            if (objectiveName.end() == rest.length()) {
                return readScore(objectiveName.text(), player == null ? "" : player.getName(), player);
            }
            if (objectiveName.end() >= rest.length() || rest.charAt(objectiveName.end()) != '_') {
                return null;
            }
            int entryStart = objectiveName.end() + 1;
            if (entryStart < rest.length() && rest.charAt(entryStart) == '{') {
                Piece entry = braces(rest, entryStart);
                if (entry == null || entry.end() != rest.length()) {
                    return null;
                }
                return readScore(objectiveName.text(), entry.text(), player);
            }
            return readScore(objectiveName.text(), rest.substring(entryStart), player);
        }
        int split = rest.lastIndexOf('_');
        if (split < 0) {
            return readScore(rest, player == null ? "" : player.getName(), player);
        }
        return readScore(rest.substring(0, split), rest.substring(split + 1), player);
    }

    private String rankValue(String rest, boolean score) {
        Piece objectiveName = braces(rest, 0);
        if (objectiveName == null) {
            return null;
        }
        int rank = 1;
        if (objectiveName.end() != rest.length()) {
            if (!rest.startsWith("_{", objectiveName.end())) {
                return null;
            }
            Piece rankPiece = braces(rest, objectiveName.end() + 1);
            if (rankPiece == null || rankPiece.end() != rest.length()) {
                return null;
            }
            try {
                rank = Integer.parseInt(rankPiece.text());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        Objective objective = objective(objectiveName.text());
        if (objective == null) {
            return unknownObjective(objectiveName.text());
        }
        List<Ranked> scores = ranking(objective);
        if (scores.isEmpty()) {
            return score ? "0" : "";
        }
        int index = rank >= 1 && rank <= scores.size() ? rank - 1 : 0;
        Ranked chosen = scores.get(index);
        return score ? Integer.toString(chosen.value()) : chosen.entry();
    }

    private String readScore(String objectiveName, String entry, Player viewer) {
        Objective objective = objective(objectiveName);
        if (objective == null) {
            return unknownObjective(objectiveName);
        }
        String wanted = scoreEntry(entry, viewer);
        if (wanted.isEmpty()) {
            return "0";
        }
        Player online = playerNamed(wanted);
        if (online != null) {
            // El marcador guarda el nombre del perfil. EvStats consulta ese texto.
            wanted = online.getName();
        }
        int value = entryScore(objective, wanted);
        if (value == 0 && online != null && deathCount(objective)) {
            value = online.getStatistic(Statistic.DEATHS);
        }
        if (value == 0 && online != null && healthTracked(objective)) {
            value = ceilHealth(online);
        }
        return Integer.toString(value);
    }

    private int entryScore(Objective objective, String wanted) {
        Integer exact = null;
        int best = 0;
        boolean loose = false;
        for (Ranked score : ranking(objective)) {
            String tracked = score.entry();
            if (wanted.equals(tracked)) {
                exact = score.value();
            } else if (sameEntry(tracked, wanted)) {
                best = Math.max(best, score.value());
                loose = true;
            }
        }
        if (exact != null) {
            return exact;
        }
        return loose ? best : 0;
    }

    private static boolean deathCount(Objective objective) {
        return Criteria.DEATH_COUNT.equals(objective.getTrackedCriteria());
    }

    private static boolean healthTracked(Objective objective) {
        return Criteria.HEALTH.equals(objective.getTrackedCriteria());
    }

    private static String scoreEntry(String entry, Player viewer) {
        String plain = plainEntry(entry);
        if (plain.equalsIgnoreCase("player") && viewer != null) {
            return viewer.getName();
        }
        return plain;
    }

    private static Player playerNamed(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    private static boolean sameEntry(String tracked, String wanted) {
        if (tracked == null || wanted == null || wanted.isEmpty()) {
            return false;
        }
        return tracked.equalsIgnoreCase(wanted) || plainEntry(tracked).equalsIgnoreCase(wanted);
    }

    private static String plainEntry(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if ((current == '&' || current == '§') && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '#' && i + 7 < raw.length() && hex(raw, i + 2, 6)) {
                    i += 7;
                    continue;
                }
                if ((next == 'x' || next == 'X') && i + 13 < raw.length() && repeatedHex(raw, i + 2)) {
                    i += 13;
                    continue;
                }
                i++;
                continue;
            }
            if (current > ' ') {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static boolean repeatedHex(String raw, int start) {
        for (int i = 0; i < 6; i++) {
            int at = start + i * 2;
            char mark = raw.charAt(at);
            if ((mark != '&' && mark != '§') || !hexDigit(raw.charAt(at + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hex(String raw, int start, int count) {
        for (int i = 0; i < count; i++) {
            if (!hexDigit(raw.charAt(start + i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hexDigit(char current) {
        return (current >= '0' && current <= '9')
                || (current >= 'a' && current <= 'f')
                || (current >= 'A' && current <= 'F');
    }

    private static Objective objective(String name) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null || name == null || name.isBlank()) {
            return null;
        }
        Scoreboard board = manager.getMainScoreboard();
        Objective exact = board.getObjective(name);
        if (exact != null) {
            return exact;
        }
        for (Objective candidate : board.getObjectives()) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    private List<Ranked> ranking(Objective objective) {
        int tick = Bukkit.getCurrentTick();
        if (objective.equals(rankedObjective) && tick == rankedTick) {
            return rankedScores;
        }
        List<Ranked> scores = listed(objective);
        if (scores == null) {
            scores = rankingFromBoard(objective);
        }
        scores.sort(Comparator.comparingInt(Ranked::value).reversed().thenComparing(Ranked::entry));
        rankedObjective = objective;
        rankedScores = scores;
        rankedTick = tick;
        return scores;
    }

    private List<Ranked> listed(Objective objective) {
        Scoreboard scoreboard = objective.getScoreboard();
        if (scoreboard == null) {
            return new ArrayList<>();
        }
        if (!scoresFast) {
            return null;
        }
        try {
            if (!scoresReady) {
                bindScores();
                scoresReady = true;
            }
            Object board = boardHandle.invoke(scoreboard);
            Object nmsObjective = objectiveByName.invoke(board, objective.getName());
            if (nmsObjective == null) {
                return new ArrayList<>();
            }
            Collection<?> found = (Collection<?>) listScores.invoke(board, nmsObjective);
            List<Ranked> scores = new ArrayList<>(found.size());
            for (Object entry : found) {
                String owner = (String) scoreOwner.invoke(entry);
                Object raw = scoreValue.invoke(entry);
                if (owner == null || !(raw instanceof Integer value)) {
                    continue;
                }
                scores.add(new Ranked(owner, value));
            }
            return scores;
        } catch (ReflectiveOperationException exception) {
            scoresFast = false;
            plugin.getLogger().warning(
                    "No se pudieron leer las puntuaciones de un objetivo sin recorrer el tablero: "
                            + exception.getMessage()
            );
            return null;
        }
    }

    private void bindScores() throws ReflectiveOperationException {
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

    private static List<Ranked> rankingFromBoard(Objective objective) {
        Scoreboard board = objective.getScoreboard();
        List<Ranked> scores = new ArrayList<>();
        if (board == null) {
            return scores;
        }
        for (String entry : board.getEntries()) {
            Score score = objective.getScore(entry);
            if (!score.isScoreSet() || score.getEntry() == null) {
                continue;
            }
            scores.add(new Ranked(score.getEntry(), score.getScore()));
        }
        return scores;
    }

    private static String unknownObjective(String name) {
        return "&cUnknown objective: " + name;
    }

    private static Piece braces(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '{') {
            return null;
        }
        int depth = 1;
        for (int i = start + 1; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return new Piece(text.substring(start + 1, i), i + 1);
            }
        }
        return null;
    }

    private String tpsType(String type) {
        if (type.isEmpty()) {
            return allTps(this::coloredTps);
        }
        return switch (type) {
            case "1", "one" -> roundTps(tpsAt(0));
            case "5", "five" -> roundTps(tpsAt(1));
            case "15", "fifteen" -> roundTps(tpsAt(2));
            case "1_colored", "one_colored" -> coloredTps(tpsAt(0));
            case "5_colored", "five_colored" -> coloredTps(tpsAt(1));
            case "15_colored", "fifteen_colored" -> coloredTps(tpsAt(2));
            case "percent" -> allTps(this::coloredPercent);
            case "1_percent", "one_percent" -> percent(tpsAt(0));
            case "5_percent", "five_percent" -> percent(tpsAt(1));
            case "15_percent", "fifteen_percent" -> percent(tpsAt(2));
            case "1_percent_colored", "one_percent_colored" -> coloredPercent(tpsAt(0));
            case "5_percent_colored", "five_percent_colored" -> coloredPercent(tpsAt(1));
            case "15_percent_colored", "fifteen_percent_colored" -> coloredPercent(tpsAt(2));
            default -> null;
        };
    }

    private String allTps(java.util.function.Function<Double, String> formatter) {
        double[] values = Bukkit.getTPS();
        StringJoiner joiner = new StringJoiner("&7, ");
        for (double value : values) {
            joiner.add(formatter.apply(value));
        }
        return joiner.toString();
    }

    private static String tpsOne() {
        double tps = tpsAt(0);
        long rounded = Math.min(Math.round(tps), 20L);
        return (tps > 20.0D ? "*" : "") + rounded;
    }

    private static double tpsAt(int index) {
        double[] tps = Bukkit.getTPS();
        return index >= 0 && index < tps.length ? tps[index] : 0.0D;
    }

    private static String roundTps(double tps) {
        double capped = Math.min(Math.round(tps), 20.0D);
        return (tps > 20.0D ? "*" : "") + capped;
    }

    private static String tpsColor(double tps) {
        if (tps > 18.0D) {
            return "&a";
        }
        if (tps > 16.0D) {
            return "&e";
        }
        return "&c";
    }

    private String coloredTps(double tps) {
        return tpsColor(tps) + roundTps(tps);
    }

    private static String percent(double tps) {
        double value = Math.min(Math.round(100.0D / 20.0D * tps), 100.0D);
        return (tps > 20.0D ? "*" : "") + value + "%";
    }

    private String coloredPercent(double tps) {
        return tpsColor(tps) + percent(tps);
    }

    private void refreshWorldCounts() {
        long now = System.currentTimeMillis();
        if (worldCountsAt != 0L && now - worldCountsAt < WORLD_COUNT_MILLIS) {
            return;
        }
        int chunks = 0;
        int living = 0;
        int entities = 0;
        for (World world : Bukkit.getWorlds()) {
            chunks += world.getLoadedChunks().length;
            living += world.getLivingEntities().size();
            entities += world.getEntities().size();
        }
        cachedChunks = chunks;
        cachedLiving = living;
        cachedEntities = entities;
        worldCountsAt = now;
    }

    private static int onlineIn(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return -1;
        }
        World world = Bukkit.getWorld(worldName);
        return world == null ? -1 : world.getPlayers().size();
    }

    private static String serverBuild() {
        var build = io.papermc.paper.ServerBuildInfo.buildInfo().buildNumber();
        return build.isPresent() ? Integer.toString(build.getAsInt()) : "Unknown";
    }

    private String formatNow(String pattern) {
        if (pattern.trim().isEmpty()) {
            return null;
        }
        DateTimeFormatter formatter = formatter(pattern);
        if (formatter == null) {
            return null;
        }
        return ZonedDateTime.now(zone).format(formatter);
    }

    private String countdown(Player player, String args, boolean down, boolean formatted, int depth) {
        String resolved = apply(args, player, depth + 1);
        DateTimeFormatter formatter;
        String date;
        if (resolved.contains("_")) {
            String[] parts = resolved.split("_");
            if (parts.length != 2) {
                return "invalid format and time";
            }
            formatter = formatter(parts[0]);
            date = parts[1];
        } else {
            formatter = formatter("MM/dd/yy HH:mm:ss");
            date = resolved;
        }
        if (formatter == null) {
            return "invalid date format";
        }
        try {
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime other = ZonedDateTime.parse(date, formatter.withZone(zone));
            if (other.isEqual(now)) {
                return "0";
            }
            if ((down && other.isBefore(now)) || (!down && other.isAfter(now))) {
                return "0";
            }
            long seconds = down ? now.until(other, ChronoUnit.SECONDS) : other.until(now, ChronoUnit.SECONDS);
            return formatted ? formatSeconds(seconds) : Long.toString(seconds);
        } catch (DateTimeParseException exception) {
            return "invalid date";
        }
    }

    private DateTimeFormatter formatter(String pattern) {
        if (badFormats.contains(pattern)) {
            return null;
        }
        DateTimeFormatter existing = formats.get(pattern);
        if (existing != null) {
            return existing;
        }
        try {
            DateTimeFormatter created = DateTimeFormatter.ofPattern(pattern, timeLocale);
            formats.put(pattern, created);
            return created;
        } catch (IllegalArgumentException | DateTimeException exception) {
            badFormats.add(pattern);
            return null;
        }
    }

    private static String formatSeconds(long time) {
        long seconds = time;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long weeks = days / 7;
        seconds %= 60;
        minutes %= 60;
        hours %= 24;
        days %= 7;
        StringJoiner joiner = new StringJoiner(" ");
        appendUnit(joiner, weeks, "w");
        appendUnit(joiner, days, "d");
        appendUnit(joiner, hours, "h");
        appendUnit(joiner, minutes, "m");
        appendUnit(joiner, seconds, "s");
        return joiner.toString();
    }

    private static void appendUnit(StringJoiner joiner, long value, String unit) {
        if (value > 0) {
            joiner.add(value + unit);
        }
    }

    private static String pingOf(Player target, boolean colored) {
        if (target == null) {
            return "0";
        }
        int ping = target.getPing();
        return colored ? coloredPing(ping) : Integer.toString(ping);
    }

    private static String coloredPing(int ping) {
        String color = ping > 100 ? "&c" : ping > 50 ? "&e" : "&a";
        return color + ping;
    }

    private static String enchantLevel(ItemStack item, String name) {
        if (name.isEmpty()) {
            return "0";
        }
        Enchantment enchantment = enchantment(name);
        if (enchantment == null || item == null) {
            return "0";
        }
        return Integer.toString(item.getEnchantmentLevel(enchantment));
    }

    private static Enchantment enchantment(String name) {
        NamespacedKey namespaced = key(name);
        if (namespaced == null) {
            return null;
        }
        return io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                .get(namespaced);
    }

    private static PotionEffectType potion(String name) {
        NamespacedKey key = key(name);
        return key == null ? null : Registry.EFFECT.get(key);
    }

    private static NamespacedKey key(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String text = name.toLowerCase(Locale.ROOT);
        try {
            return text.indexOf(':') >= 0 ? NamespacedKey.fromString(text) : NamespacedKey.minecraft(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static double maxHealth(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0D : attribute.getValue();
    }

    private static String biomeName(Player player) {
        return player.getLocation().getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT);
    }

    private static String bool(boolean value) {
        return value ? "true" : "false";
    }

    private static String plain(Component component) {
        return component == null ? "" : LEGACY.serialize(component);
    }

    private static String localeOf(Player player) {
        Locale locale = player.locale();
        return locale == null ? "" : locale.toString();
    }

    private static Location bed(Player player) {
        return player.getRespawnLocation();
    }

    private static String exact(Location location, java.util.function.ToDoubleFunction<Location> axis) {
        return location == null ? "" : String.valueOf(axis.applyAsDouble(location));
    }

    private static String block(Location location, java.util.function.ToIntFunction<Location> axis) {
        return location == null ? "" : Integer.toString(axis.applyAsInt(location));
    }

    private static String address(Player player) {
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return "";
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    private static String worldType(World.Environment environment) {
        if (environment == World.Environment.NETHER) {
            return "Nether";
        }
        if (environment == World.Environment.THE_END) {
            return "The End";
        }
        if (environment == World.Environment.NORMAL) {
            return "Overworld";
        }
        return "";
    }

    private static BlockFace direction(Player player) {
        return RADIAL[Math.round(player.getLocation().getYaw() / 45.0F) & 0x7].getOppositeFace();
    }

    private static String directionName(BlockFace face) {
        return switch (face) {
            case NORTH -> "N";
            case NORTH_EAST -> "NE";
            case EAST -> "E";
            case SOUTH_EAST -> "SE";
            case SOUTH -> "S";
            case SOUTH_WEST -> "SW";
            case WEST -> "W";
            case NORTH_WEST -> "NW";
            default -> "";
        };
    }

    private static String xzDirection(Player player) {
        double rotation = player.getLocation().getYaw();
        if (rotation < 0.0D) {
            rotation += 360.0D;
        }
        if (Math.abs(rotation) <= 45.0D || Math.abs(rotation - 360.0D) <= 45.0D) {
            return "+Z";
        }
        if (Math.abs(rotation - 90.0D) <= 45.0D) {
            return "-X";
        }
        if (Math.abs(rotation - 180.0D) <= 45.0D) {
            return "-Z";
        }
        if (Math.abs(rotation - 270.0D) <= 45.0D) {
            return "+X";
        }
        return "";
    }

    private static String capitalizedBiome(Player player) {
        String[] words = biomeName(player).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private static String ticksToTime(long ticks) {
        ticks = ticks - TICKS_AT_MIDNIGHT + TICKS_PER_DAY;
        long hours = ticks / TICKS_PER_HOUR;
        ticks -= hours * TICKS_PER_HOUR;
        long mins = (long) Math.floor(ticks / TICKS_PER_MINUTE);
        if (hours >= 24) {
            hours -= 24;
        }
        return (hours < 10 ? "0" + hours : Long.toString(hours)) + ":" + (mins < 10 ? "0" + mins : Long.toString(mins));
    }

    private static String format12(long ticks) {
        try {
            return LocalTime.parse(ticksToTime(ticks), HOUR_24).format(HOUR_12);
        } catch (DateTimeParseException exception) {
            return ticksToTime(ticks);
        }
    }

    private static ItemStack itemInHand(Player player) {
        return player.getInventory().getItemInMainHand();
    }

    private static String itemName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomName()) {
            return "";
        }
        Component name = meta.customName();
        return name == null ? "" : LEGACY.serialize(name);
    }

    private static String itemData(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "0";
        }
        return Integer.toString(itemDamage(item));
    }

    private static String armorData(ItemStack item) {
        return item == null ? "0" : Integer.toString(itemDamage(item));
    }

    private static int remainingDurability(ItemStack item) {
        if (item == null) {
            return 0;
        }
        return item.getType().getMaxDurability() - itemDamage(item);
    }

    private static int itemDamage(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            return damageable.getDamage();
        }
        return 0;
    }

    private static int emptySlots(Player player) {
        int slots = 0;
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getContents()) {
            if (item == null) {
                slots++;
            }
        }
        String version = Bukkit.getBukkitVersion();
        if (!version.contains("1.7") && !version.contains("1.8")) {
            ItemStack offhand = inventory.getItemInOffHand();
            if (offhand == null || offhand.getType() == Material.AIR) {
                slots--;
            }
            if (inventory.getHelmet() == null) {
                slots--;
            }
            if (inventory.getChestplate() == null) {
                slots--;
            }
            if (inventory.getLeggings() == null) {
                slots--;
            }
            if (inventory.getBoots() == null) {
                slots--;
            }
        }
        return slots;
    }

    private static int totalExperience(Player player) {
        int experience = Math.round(experienceAtLevel(player.getLevel()) * player.getExp());
        int level = player.getLevel();
        while (level > 0) {
            level--;
            experience += experienceAtLevel(level);
        }
        return Math.max(experience, 0);
    }

    private static int experienceAtLevel(int level) {
        if (level <= 15) {
            return (level << 1) + 7;
        }
        if (level <= 30) {
            return (level * 5) - 38;
        }
        return (level * 9) - 158;
    }

    private record Ranked(String entry, int value) {
    }

    private record Piece(String text, int end) {
    }
}
