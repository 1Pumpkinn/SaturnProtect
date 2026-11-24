package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.ItemAction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

public final class ItemListener implements Listener {

    private final ProtectConfig config;
    private final LogService logService;

    public ItemListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!config.logItemDrops()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack stack = event.getItemDrop().getItemStack();
        logService.logItemStack(player, player.getLocation().getBlock(), ItemAction.DROP, stack, stack.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!config.logItemPickups() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        int picked = stack.getAmount() - event.getRemaining();
        if (picked <= 0) {
            return;
        }
        logService.logItemStack(player, player.getLocation().getBlock(), ItemAction.PICKUP, stack, picked);
    }
}
