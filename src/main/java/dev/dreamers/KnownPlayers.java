package dev.dreamers;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Jugadores que ya entraron al servidor, conectados o no.
 * El tabulador de un jugador los ofrece todos.
 */
final class KnownPlayers {
    private KnownPlayers() {
    }

    static List<String> names(Server server, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (OfflinePlayer player : everyone(server)) {
            String name = player.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    static OfflinePlayer find(Server server, String name) {
        Player exact = server.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : server.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        OfflinePlayer loose = null;
        for (OfflinePlayer player : everyone(server)) {
            String known = player.getName();
            if (known == null) {
                continue;
            }
            if (known.equals(name)) {
                return player;
            }
            if (known.equalsIgnoreCase(name)) {
                loose = player;
            }
        }
        return loose;
    }

    static List<OfflinePlayer> everyone(Server server) {
        Map<UUID, OfflinePlayer> known = new LinkedHashMap<>();
        for (OfflinePlayer player : server.getOfflinePlayers()) {
            if (player.getName() == null || new UUID(0L, 0L).equals(player.getUniqueId())) {
                continue;
            }
            known.put(player.getUniqueId(), player);
        }
        for (Player player : server.getOnlinePlayers()) {
            known.put(player.getUniqueId(), player);
        }
        return new ArrayList<>(known.values());
    }
}
