package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.EventAction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class SessionListener implements Listener {

    private final ProtectConfig config;
    private final LogService logService;

    public SessionListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (config.logSessions()) {
            logService.logPlayerEvent(event.getPlayer(), EventAction.LOGIN, "", null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (config.logSessions()) {
            logService.logPlayerEvent(event.getPlayer(), EventAction.LOGOUT, "", null);
        }
    }
}
