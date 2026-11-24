package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.LogAction;
import net.rose.saturnProtect.util.BlockText;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs blocks that pistons push, pull or destroy, under {@code #piston}.
 * <p>
 * Each affected position gets one record with its state before and after the move, so a rollback puts every
 * block back where it started. The piston head is deliberately not logged: it only exists while the piston is
 * extended, so the position it occupies is recorded as air.
 */
public final class PistonListener implements Listener {

    private static final String ACTOR = "#piston";
    private static final String AIR = "minecraft:air";

    private static final class Change {
        private final Block block;
        private final String before;
        private String after;

        private Change(Block block, String before, String after) {
            this.block = block;
            this.before = before;
            this.after = after;
        }
    }

    private final ProtectConfig config;
    private final LogService logService;

    public PistonListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        if (!config.logPistons()) {
            return;
        }
        Block piston = event.getBlock();
        BlockFace direction = piston.getBlockData() instanceof Directional directional
                ? directional.getFacing()
                : event.getDirection();
        log(piston, event.getBlocks(), direction, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        // a normal piston retracting pulls nothing
        if (!config.logPistons() || event.getBlocks().isEmpty()) {
            return;
        }
        Block piston = event.getBlock();
        // pulled blocks travel towards the piston, which is the opposite of the way it faces. Derived from the
        // piston's own block data rather than getDirection(), which has meant different things over the years.
        BlockFace direction = piston.getBlockData() instanceof Directional directional
                ? directional.getFacing().getOppositeFace()
                : event.getDirection();
        log(piston, event.getBlocks(), direction, false);
    }

    /** The event fires before anything moves, so every block still has its pre-move state here. */
    private void log(Block piston, List<Block> moved, BlockFace direction, boolean extending) {
        Map<Block, Change> changes = new LinkedHashMap<>();

        // every moved block leaves its position empty...
        for (Block block : moved) {
            changes.put(block, new Change(block, snapshot(block), AIR));
        }
        // ...unless another moved block lands there. A destination that was not vacated keeps whatever was there
        // before as its old state, which is also how blocks a piston crushes (torches, flowers) get recorded.
        for (Block block : moved) {
            Block target = block.getRelative(direction);
            String data = block.getBlockData().getAsString();
            Change existing = changes.get(target);
            if (existing != null) {
                existing.after = data;
            } else {
                changes.put(target, new Change(target, snapshot(target), data));
            }
        }

        // an extending piston that pushes nothing can still crush the block right in front of it
        if (extending && moved.isEmpty()) {
            Block front = piston.getRelative(direction);
            if (!front.getType().isAir() && front.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                changes.put(front, new Change(front, snapshot(front), AIR));
            }
        }

        for (Change change : changes.values()) {
            LogAction action = BlockText.isAir(BlockText.name(change.after)) ? LogAction.BREAK : LogAction.PLACE;
            logService.logBlock(ACTOR, null, change.block, action, change.before, change.after);
        }
    }

    /** A piston head or a block mid-move is not a real block, so it counts as air. */
    private static String snapshot(Block block) {
        Material type = block.getType();
        if (type == Material.PISTON_HEAD || type == Material.MOVING_PISTON) {
            return AIR;
        }
        return block.getBlockData().getAsString();
    }
}
