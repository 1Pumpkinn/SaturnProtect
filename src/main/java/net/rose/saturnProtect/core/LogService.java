package net.rose.saturnProtect.core;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.EventRecord;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LogAction;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
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

    public void logItem(String actor, UUID actorUuid, Block block, ItemAction action, String itemType,
                        int amount, byte[] itemData) {
        String world = block.getWorld().getName();
        if (config.isWorldIgnored(world) || config.isUserIgnored(actor) || amount <= 0) {
            return;
        }
        database.queue(ItemRecord.create(actor, actorUuid, world,
                block.getX(), block.getY(), block.getZ(), action, itemType, amount, itemData));
    }

    /**
     * A death in lava. Looks up who placed the nearest recent lava and logs the kill under that player;
     * if nobody did, logs {@code #lava} when {@code fallback} is set. Safe to call from the main thread.
     */
    public void logLavaDeath(String victimName, String victimType, String world, int x, int y, int z,
                             boolean attribute, boolean fallback) {
        long since = System.currentTimeMillis() - config.lavaAttributionMillis();
        database.findLavaPlacer(world, x, y, z, config.lavaAttributionRadius(), since).whenComplete((placer, error) -> {
            if (error == null && placer != null && attribute) {
                logEvent(placer.name(), placer.uuid(), world, x, y, z, EventAction.KILL,
                        victimName + " via lava", victimType);
            } else if (fallback) {
                logEvent("#lava", null, world, x, y, z, EventAction.KILL, victimName, victimType);
            }
        });
    }

    public void logItemStack(Player player, Block block, ItemAction action, ItemStack stack, int amount) {
        if (stack.getType().isAir()) {
            return;
        }
        logItem(player, block, action, stack.getType().getKey().toString(), amount, stack.asOne().serializeAsBytes());
    }

    public void logEvent(String actor, UUID actorUuid, String world, int x, int y, int z,
                         EventAction action, String subject, String detail) {
        if (config.isWorldIgnored(world) || config.isUserIgnored(actor)) {
            return;
        }
        database.queue(EventRecord.create(actor, actorUuid, world, x, y, z, action, subject, detail));
    }

    /** Logs an event at the player's current position. Safe to call from any thread. */
    public void logPlayerEvent(Player player, EventAction action, String subject, String detail) {
        Location location = player.getLocation();
        logEvent(player.getName(), player.getUniqueId(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action, subject, detail);
    }

    public void logPlayerEvent(Player player, Block block, EventAction action, String subject, String detail) {
        logEvent(player.getName(), player.getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), action, subject, detail);
    }

    /** Logs everything in a container as removed, used when the container block is broken. */
    public void logContents(Player player, Block block, ItemStack[] contents) {
        logContents(player.getName(), player.getUniqueId(), block, contents);
    }

    public void logContents(String actor, UUID actorUuid, Block block, ItemStack[] contents) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, ItemStack> samples = new HashMap<>();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack one = item.asOne();
            String key = Base64.getEncoder().encodeToString(one.serializeAsBytes());
            counts.merge(key, item.getAmount(), Integer::sum);
            samples.putIfAbsent(key, one);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            ItemStack sample = samples.get(entry.getKey());
            logItem(actor, actorUuid, block, ItemAction.CONTAINER_REMOVE, sample.getType().getKey().toString(),
                    entry.getValue(), sample.serializeAsBytes());
        }
    }

    public void logPlayerBlock(Player player, Block block, LogAction action, String oldData, String newData) {
        logBlock(player.getName(), player.getUniqueId(), block, action, oldData, newData);
    }
}
