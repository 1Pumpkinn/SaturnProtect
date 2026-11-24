package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.LogAction;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.Inventory;

public final class BlockListener implements Listener {

    private static final String AIR = "minecraft:air";
    private static final String WATER = "minecraft:water[level=0]";

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final LogService logService;

    public BlockListener(SaturnProtect plugin, ProtectConfig config, LogService logService) {
        this.plugin = plugin;
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.logBlockBreak()) {
            return;
        }
        Block block = event.getBlock();
        logBrokenContainer(event.getPlayer(), block);
        BlockData data = block.getBlockData();
        String after = (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) ? WATER : AIR;
        logService.logPlayerBlock(event.getPlayer(), block, LogAction.BREAK, data.getAsString(), after);
    }

    /** Contents are still in the container at MONITOR, so log them as removed to make a rollback restore them. */
    private void logBrokenContainer(Player player, Block block) {
        if (!config.logContainers() || !(block.getState(false) instanceof Container container)) {
            return;
        }
        Inventory inventory = container instanceof Chest chest ? chest.getBlockInventory() : container.getInventory();
        logService.logContents(player, block, inventory.getContents());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!config.logBlockPlace()) {
            return;
        }
        Player player = event.getPlayer();

        // beds, doors, etc. place more than one block
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                Block block = replaced.getBlock();
                logService.logPlayerBlock(player, block, LogAction.PLACE,
                        replaced.getBlockData().getAsString(), block.getBlockData().getAsString());
            }
            return;
        }

        Block placed = event.getBlockPlaced();
        logService.logPlayerBlock(player, placed, LogAction.PLACE,
                event.getBlockReplacedState().getBlockData().getAsString(), placed.getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        logBucket(event.getPlayer(), event.getBlock(), LogAction.PLACE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        logBucket(event.getPlayer(), event.getBlock(), LogAction.BREAK);
    }

    /** The world changes after the event, so read the real result one tick later. */
    private void logBucket(Player player, Block block, LogAction action) {
        if (!config.logBuckets()) {
            return;
        }
        String before = block.getBlockData().getAsString();
        Bukkit.getScheduler().runTask(plugin, () ->
                logService.logPlayerBlock(player, block, action, before, block.getBlockData().getAsString()));
    }
}
