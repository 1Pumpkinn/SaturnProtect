package net.rose.saturnProtect.core;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LogAction;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Single entry point listeners use to record things. Applies ignore rules and de-noising. */
public final class LogService {

    private final ProtectConfig config;
    private final Database database;

    public LogService(ProtectConfig config, Database database) {
        this.config = config;
        this.database = database;
    }

    public void logBlock(String actor, UUID actorUuid, Block block, LogAction action, String oldData, String newData) {
        String world = block.getWorld().getName();
        if (config.isWorldIgnored(world) || config.isUserIgnored(actor)) {
            return;
        }
        if (oldData.equals(newData)) {
            return;
        }
        database.queue(BlockRecord.create(actor, actorUuid, world,
                block.getX(), block.getY(), block.getZ(), action, oldData, newData));
    }

    public void logItem(Player player, Block block, ItemAction action, String itemType, int amount, byte[] itemData) {
        String world = block.getWorld().getName();
        if (config.isWorldIgnored(world) || config.isUserIgnored(player.getName()) || amount <= 0) {
            return;
        }
        database.queue(ItemRecord.create(player.getName(), player.getUniqueId(), world,
                block.getX(), block.getY(), block.getZ(), action, itemType, amount, itemData));
    }

    public void logItemStack(Player player, Block block, ItemAction action, ItemStack stack, int amount) {
        if (stack.getType().isAir()) {
            return;
        }
        logItem(player, block, action, stack.getType().getKey().toString(), amount, stack.asOne().serializeAsBytes());
    }

    public void logPlayerBlock(Player player, Block block, LogAction action, String oldData, String newData) {
        logBlock(player.getName(), player.getUniqueId(), block, action, oldData, newData);
    }
}
