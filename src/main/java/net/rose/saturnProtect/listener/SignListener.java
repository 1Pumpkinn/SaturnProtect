package net.rose.saturnProtect.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.EventAction;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.List;

public final class SignListener implements Listener {

    private final ProtectConfig config;
    private final LogService logService;

    public SignListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!config.logSigns()) {
            return;
        }
        Block block = event.getBlock();
        String before = block.getState(false) instanceof Sign sign ? join(sign.getSide(event.getSide()).lines()) : "";
        String after = join(event.lines());
        if (before.equals(after)) {
            return;
        }
        logService.logPlayerEvent(event.getPlayer(), block, EventAction.SIGN, after, before);
    }

    private static String join(List<Component> lines) {
        StringBuilder text = new StringBuilder();
        for (Component line : lines) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return text.toString().isBlank() ? "" : text.toString();
    }
}
