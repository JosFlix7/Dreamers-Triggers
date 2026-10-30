package dev.dreamers;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AchievementMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.DeathMessagePreProcessEvent;
import github.scarsz.discordsrv.objects.MessageFormat;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

final class DiscordBridge {
    private static final DiscordBridge LISTENER = new DiscordBridge();

    private DiscordBridge() {}

    static void enable() {
        DiscordSRV.api.subscribe(LISTENER);
    }

    static void disable() {
        DiscordSRV.api.unsubscribe(LISTENER);
    }

    @Subscribe
    public void onDeath(DeathMessagePreProcessEvent event) {
        if (!(event.getTriggeringBukkitEvent() instanceof PlayerDeathEvent death)) {
            return;
        }
        String spanish = SpanishTexts.plain(death.deathMessage());
        if (!spanish.isBlank()) {
            event.setDeathMessage(spanish);
        }
    }

    @Subscribe
    public void onAdvancement(AchievementMessagePreProcessEvent event) {
        if (!(event.getTriggeringBukkitEvent() instanceof PlayerAdvancementDoneEvent done)) {
            return;
        }
        AdvancementDisplay display = done.getAdvancement().getDisplay();
        if (display == null) {
            return;
        }
        String title = PackTexts.render(display.title());
        if (title.isBlank()) {
            title = event.getAchievementName();
        }
        String description = PackTexts.render(display.description());
        MessageFormat format = copy(event.getMessageFormat());
        String line = "**%username% " + action(display.frame()) + " " + markdown(title) + "!**";
        if (!description.isBlank()) {
            line += "\n*" + markdown(description) + "*";
        }
        format.setDescription(line);
        event.setMessageFormat(format);
        event.setAchievementName(title);
    }

    private static String action(AdvancementDisplay.Frame frame) {
        return switch (frame) {
            case CHALLENGE -> "ha completado el desafío";
            case GOAL -> "ha alcanzado el objetivo";
            default -> "ha conseguido el progreso";
        };
    }

    private static String markdown(String text) {
        return text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
    }

    private static MessageFormat copy(MessageFormat source) {
        MessageFormat copy = new MessageFormat();
        copy.setContent(source.getContent());
        copy.setAuthorName(source.getAuthorName());
        copy.setAuthorUrl(source.getAuthorUrl());
        copy.setAuthorImageUrl(source.getAuthorImageUrl());
        copy.setThumbnailUrl(source.getThumbnailUrl());
        copy.setTitle(source.getTitle());
        copy.setTitleUrl(source.getTitleUrl());
        copy.setDescription(source.getDescription());
        copy.setImageUrl(source.getImageUrl());
        copy.setFooterText(source.getFooterText());
        copy.setFooterIconUrl(source.getFooterIconUrl());
        copy.setTimestamp(source.getTimestamp());
        copy.setColorRaw(source.getColorRaw());
        copy.setFields(source.getFields());
        copy.setUseWebhooks(source.isUseWebhooks());
        copy.setWebhookAvatarUrl(source.getWebhookAvatarUrl());
        copy.setWebhookName(source.getWebhookName());
        return copy;
    }
}
