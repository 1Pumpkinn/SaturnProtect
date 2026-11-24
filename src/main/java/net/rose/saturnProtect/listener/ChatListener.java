package net.rose.saturnProtect.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.EventAction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class ChatListener implements Listener {

    private static final int MAX_LENGTH = 1000;

    private final ProtectConfig config;
    private final LogService logService;

    public ChatListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.logChat()) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        logService.logPlayerEvent(event.getPlayer(), EventAction.CHAT, limit(message), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!config.logCommands()) {
            return;
        }
        String message = event.getMessage();
        if (config.isCommandIgnored(label(message))) {
            return;
        }
        logService.logPlayerEvent(event.getPlayer(), EventAction.COMMAND, limit(message), null);
    }

    /** {@code /minecraft:Tp @a} -> {@code tp} */
    private static String label(String message) {
        String body = message.startsWith("/") ? message.substring(1) : message;
        int space = body.indexOf(' ');
        String label = space >= 0 ? body.substring(0, space) : body;
        int colon = label.indexOf(':');
        return (colon >= 0 ? label.substring(colon + 1) : label).toLowerCase(Locale.ROOT);
    }

    private static String limit(String text) {
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }
}
