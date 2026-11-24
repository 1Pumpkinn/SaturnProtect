package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.ContainerUtil;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.ItemAction;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Logs container changes by comparing the contents when a player opens a container with the
 * contents when they close it. Net changes only: moving an item out and back in logs nothing.
 */
public final class ContainerListener implements Listener {

    private static final class Tally {
        private final String type;
        private final byte[] data;
        private int count;

        private Tally(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private record Snapshot(Block block, Map<String, Tally> contents) {
    }

    private final ProtectConfig config;
    private final LogService logService;
    // main thread only
    private final Map<UUID, Snapshot> open = new HashMap<>();

    public ContainerListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!config.logContainers() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Block block = ContainerUtil.locate(event.getInventory());
        if (block == null) {
            return;
        }
        open.put(player.getUniqueId(), new Snapshot(block, tally(event.getInventory().getContents())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Snapshot snapshot = open.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }

        Map<String, Tally> before = snapshot.contents();
        Map<String, Tally> after = tally(event.getInventory().getContents());

        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            int was = before.containsKey(key) ? before.get(key).count : 0;
            int now = after.containsKey(key) ? after.get(key).count : 0;
            int delta = now - was;
            if (delta == 0) {
                continue;
            }
            Tally reference = after.containsKey(key) ? after.get(key) : before.get(key);
            logService.logItem(player, snapshot.block(),
                    delta > 0 ? ItemAction.CONTAINER_ADD : ItemAction.CONTAINER_REMOVE,
                    reference.type, Math.abs(delta), reference.data);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId());
    }

    /** Groups identical items (ignoring stack size) and sums their amounts. */
    private static Map<String, Tally> tally(ItemStack[] items) {
        Map<String, Tally> result = new HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            byte[] data = item.asOne().serializeAsBytes();
            String key = Base64.getEncoder().encodeToString(data);
            Tally tally = result.computeIfAbsent(key, k -> new Tally(item.getType().getKey().toString(), data));
            tally.count += item.getAmount();
        }
        return result;
    }
}
