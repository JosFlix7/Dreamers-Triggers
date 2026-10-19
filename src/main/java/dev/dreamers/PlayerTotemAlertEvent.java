package dev.dreamers;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Se dispara solo cuando un jugador consume un tótem.
 * DiscordSRV puede escucharlo en alerts.yml con el trigger
 * {@code dev.dreamers.PlayerTotemAlertEvent}. {@link #getMessage()} es la misma
 * frase que el chat, ya en texto plano.
 */
public final class PlayerTotemAlertEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String message;

    PlayerTotemAlertEvent(Player player, String message) {
        this.player = player;
        this.message = message;
    }

    public Player getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
