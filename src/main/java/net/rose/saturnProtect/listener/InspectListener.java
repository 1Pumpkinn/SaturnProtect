package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.InspectorManager;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.util.Messages;
import net.rose.saturnProtect.core.ContainerUtil;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.logging.Level;

public final class InspectListener implements Listener {

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final Database database;
    private final InspectorManager inspectors;

    public InspectListener(SaturnProtect plugin, ProtectConfig config, Database database, InspectorManager inspectors) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.inspectors = inspectors;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!inspectors.isActive(player.getUniqueId())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Block target = switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> clicked;
            case RIGHT_CLICK_BLOCK -> clicked.getRelative(event.getBlockFace());
            default -> null;
        };
        if (target == null) {
            return;
        }

        event.setCancelled(true);
        if (!player.hasPermission(Permissions.INSPECT)) {
            inspectors.remove(player.getUniqueId());
            player.sendMessage(Messages.error("You no longer have permission to inspect."));
            return;
        }

        // right-clicking a container shows its item transactions instead of the block next to it
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            Block container = containerAt(clicked);
            if (container != null) {
                inspectContainer(player, container);
                return;
            }
        }
        inspect(player, target);
    }

    private static Block containerAt(Block block) {
        return block.getState(false) instanceof Container container ? ContainerUtil.locate(container.getInventory()) : null;
    }

    // The interact event already shows the history; these only stop the block from changing.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (inspectors.isActive(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (inspectors.isActive(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inspectors.remove(event.getPlayer().getUniqueId());
        plugin.lookups().forget(event.getPlayer().getUniqueId());
        plugin.rollbacks().forget(event.getPlayer().getUniqueId());
    }

    private void inspectContainer(Player player, Block block) {
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        database.itemHistory(world, x, y, z, config.inspectLimit()).whenComplete((records, error) -> {
            if (error != null) {
                player.sendMessage(Messages.error("Lookup failed, check the console."));
                plugin.getLogger().log(Level.SEVERE, "Container inspect failed", error);
                return;
            }
            Messages.sendItemHistory(player, world, x, y, z, records);
        });
    }

    private void inspect(Player player, Block block) {
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        database.blockHistory(world, x, y, z, config.inspectLimit()).whenComplete((records, error) -> {
            if (error != null) {
                player.sendMessage(Messages.error("Lookup failed, check the console."));
                plugin.getLogger().log(Level.SEVERE, "Block inspect failed", error);
                return;
            }
            Messages.sendBlockHistory(player, world, x, y, z, records);
        });
    }
}
