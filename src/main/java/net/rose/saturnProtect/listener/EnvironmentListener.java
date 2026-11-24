package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.LogAction;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.UUID;

/** Changes caused by the world rather than by a player's hands: explosions, fire, fluids and growth. */
public final class EnvironmentListener implements Listener {

    private static final String AIR = "minecraft:air";
    private static final String WATER = "minecraft:water[level=0]";

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final LogService logService;

    public EnvironmentListener(SaturnProtect plugin, ProtectConfig config, LogService logService) {
        this.plugin = plugin;
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!config.logExplosions()) {
            return;
        }
        Entity entity = event.getEntity();
        String actor = "#" + entity.getType().getKey().getKey();
        UUID uuid = null;

        Player culprit = null;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            culprit = source;
        } else if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            culprit = shooter;
        }
        if (culprit != null) {
            actor = culprit.getName();
            uuid = culprit.getUniqueId();
        }
        logDestroyed(actor, uuid, event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!config.logExplosions()) {
            return;
        }
        String actor = "#" + event.getExplodedBlockState().getType().getKey().getKey();
        logDestroyed(actor, null, event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!config.logFireBurn()) {
            return;
        }
        Block block = event.getBlock();
        String before = block.getBlockData().getAsString();
        Bukkit.getScheduler().runTask(plugin, () ->
                logService.logBlock("#fire", null, block, LogAction.BREAK, before, block.getBlockData().getAsString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (!config.logFireIgnite() || player == null) {
            return;
        }
        Block block = event.getBlock();
        logService.logPlayerBlock(player, block, LogAction.PLACE, block.getBlockData().getAsString(),
                Material.FIRE.createBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        boolean replace = config.logFluidReplace();
        boolean flow = config.logFluidFlow();
        if (!replace && !flow) {
            return;
        }
        Material fluid = event.getBlock().getType();
        if (fluid != Material.WATER && fluid != Material.LAVA) {
            return;
        }
        Block target = event.getToBlock();
        if (target.isLiquid()) {
            return;
        }
        boolean air = target.getType().isAir();
        if (air ? !flow : !replace) {
            return;
        }

        String actor = fluid == Material.LAVA ? "#lava" : "#water";
        LogAction action = air ? LogAction.PLACE : LogAction.BREAK;
        String before = target.getBlockData().getAsString();
        Bukkit.getScheduler().runTask(plugin, () ->
                logService.logBlock(actor, null, target, action, before, target.getBlockData().getAsString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        if (!config.logTreeGrowth()) {
            return;
        }
        Player player = event.getPlayer();
        String actor = player != null ? player.getName() : "#growth";
        UUID uuid = player != null ? player.getUniqueId() : null;
        for (BlockState state : event.getBlocks()) {
            Block block = state.getBlock();
            logService.logBlock(actor, uuid, block, LogAction.PLACE,
                    block.getBlockData().getAsString(), state.getBlockData().getAsString());
        }
    }

    private void logDestroyed(String actor, UUID uuid, List<Block> blocks) {
        for (Block block : blocks) {
            BlockData data = block.getBlockData();
            if (data.getMaterial().isAir()) {
                continue;
            }
            if (config.logContainers() && block.getState(false) instanceof Container container) {
                logService.logContents(actor, uuid, block, container.getInventory().getContents());
            }
            String after = data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged() ? WATER : AIR;
            logService.logBlock(actor, uuid, block, LogAction.BREAK, data.getAsString(), after);
        }
    }
}
