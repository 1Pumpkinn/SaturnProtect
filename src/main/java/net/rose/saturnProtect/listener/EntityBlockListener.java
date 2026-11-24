package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.LogAction;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

import java.util.UUID;

/**
 * Blocks that an entity changes: endermen picking up and placing blocks, withers and ravagers breaking them,
 * sand and gravel starting to fall and landing, sheep eating grass, farmland being trampled, silverfish hiding in
 * stone. Logged under {@code #entity_type}, or under the player when a player is the entity.
 * <p>
 * Sand placed by a player is logged twice more: once as {@code #falling_block} when it turns into an entity and
 * once when it lands. That is what happened, and it lets a rollback put the block back wherever it ended up.
 */
public final class EntityBlockListener implements Listener {

    private static final String WATER = "minecraft:water[level=0]";

    private final ProtectConfig config;
    private final LogService logService;

    public EntityBlockListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!config.logEntityBlockChanges()) {
            return;
        }

        Block block = event.getBlock();
        BlockData before = block.getBlockData();
        BlockData to = event.getBlockData();
        boolean removed = to.getMaterial().isAir();

        // a waterlogged block that disappears leaves its water behind
        String after = removed && before instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
                ? WATER
                : to.getAsString();

        Entity entity = event.getEntity();
        String actor;
        UUID uuid = null;
        if (entity instanceof Player player) {
            actor = player.getName();
            uuid = player.getUniqueId();
        } else {
            actor = "#" + entity.getType().getKey().getKey();
        }

        logService.logBlock(actor, uuid, block, removed ? LogAction.BREAK : LogAction.PLACE,
                before.getAsString(), after);
    }
}
