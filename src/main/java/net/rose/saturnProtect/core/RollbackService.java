package net.rose.saturnProtect.core;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.db.TooManyRecordsException;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Rollback / restore / undo. Records are fetched async, collapsed to one target state per block position and
 * one net item change per container and item, then applied on the main thread a few hundred blocks per tick.
 * Blocks are applied first so a container that was broken exists again before its contents come back.
 */
public final class RollbackService {

    private static final UUID CONSOLE = new UUID(0L, 0L);

    public record BlockChange(String world, int x, int y, int z, String data) {
    }

    /** {@code delta} is how many of {@code item} (serialized, amount 1) to add (positive) or remove (negative). */
    private record ContainerChange(String world, int x, int y, int z, byte[] item, int delta) {
    }

    private record ContainerSnapshot(String world, int x, int y, int z, ItemStack[] contents) {
    }

    private record Fetched(List<BlockRecord> blocks, List<ItemRecord> items) {
    }

    private record Job(List<Long> blockIds, List<Long> itemIds,
                       List<BlockChange> blocks, List<ContainerChange> containers) {
    }

    private record UndoSession(boolean wasRollback, List<Long> blockIds, List<Long> itemIds,
                               List<BlockChange> previousBlocks, List<ContainerSnapshot> snapshots) {
    }

    private interface BlocksDone {
        void done(List<BlockChange> previous, int skipped);
    }

    private interface ContainersDone {
        void done(List<ContainerSnapshot> snapshots, int skipped, int overflow);
    }

    private interface SnapshotsDone {
        void done(int skipped);
    }

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final Database database;
    private final Map<UUID, UndoSession> undoSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public RollbackService(SaturnProtect plugin, ProtectConfig config, Database database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    public void rollback(CommandSender sender, LookupQuery query) {
        start(sender, query, false);
    }

    public void restore(CommandSender sender, LookupQuery query) {
        start(sender, query, true);
    }

    public void forget(UUID player) {
        undoSessions.remove(player);
    }

    public void undo(CommandSender sender) {
        UndoSession session = undoSessions.remove(key(sender));
        if (session == null) {
            sender.sendMessage(Messages.error("Nothing to undo."));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            undoSessions.put(key(sender), session);
            sender.sendMessage(Messages.error("Another rollback is already running, try again shortly."));
            return;
        }

        sender.sendMessage(Messages.info("Undoing the last " + (session.wasRollback() ? "rollback" : "restore")
                + " (" + session.previousBlocks().size() + " block(s), " + session.snapshots().size()
                + " container(s))..."));
        restoreSnapshots(session.snapshots(), skippedContainers ->
                applyBlocks(session.previousBlocks(), (previous, skippedBlocks) ->
                        markRolledBack(session.blockIds(), session.itemIds(), !session.wasRollback())
                                .whenComplete((ignored, error) -> {
                                    busy.set(false);
                                    if (error != null) {
                                        sender.sendMessage(Messages.error("Changes were reverted but the database update failed, check the console."));
                                        plugin.getLogger().log(Level.SEVERE, "Failed to update rolled-back flags on undo", error);
                                        return;
                                    }
                                    int skipped = skippedBlocks + skippedContainers;
                                    sender.sendMessage(Messages.success("Undo complete: " + previous.size() + " block(s) changed"
                                            + (skipped > 0 ? ", " + skipped + " skipped" : "") + "."));
                                })));
    }

    private void start(CommandSender sender, LookupQuery query, boolean restore) {
        boolean wantBlocks = !query.actions().isEmpty() || query.itemActions().isEmpty();
        boolean wantContainers = config.rollbackContainers()
                && (!query.itemActions().isEmpty() || query.actions().isEmpty());
        if (!wantBlocks && !wantContainers) {
            sender.sendMessage(Messages.error("Container rollbacks are turned off in the config."));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(Messages.error("Another rollback is already running, try again shortly."));
            return;
        }
        sender.sendMessage(Messages.info("Searching records..."));

        int max = config.rollbackMaxRecords();
        CompletableFuture<List<BlockRecord>> blockFuture = wantBlocks
                ? database.fetchForRollback(query, restore, max)
                : CompletableFuture.<List<BlockRecord>>completedFuture(List.of());
        CompletableFuture<List<ItemRecord>> itemFuture = wantContainers
                ? database.fetchContainerRecords(query, restore, max)
                : CompletableFuture.<List<ItemRecord>>completedFuture(List.of());

        blockFuture.thenCombine(itemFuture, Fetched::new).whenComplete((fetched, error) -> {
            if (error != null) {
                busy.set(false);
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof TooManyRecordsException tooMany) {
                    sender.sendMessage(Messages.error("That matches " + tooMany.count() + " records (limit " + tooMany.max()
                            + "). Narrow it with a smaller radius or time."));
                } else {
                    sender.sendMessage(Messages.error("Search failed, check the console."));
                    plugin.getLogger().log(Level.SEVERE, "Rollback search failed", cause);
                }
                return;
            }
            if (fetched.blocks().isEmpty() && fetched.items().isEmpty()) {
                busy.set(false);
                sender.sendMessage(Messages.info(restore ? "Nothing to restore." : "Nothing to roll back."));
                return;
            }

            Job job = plan(fetched, restore);
            Bukkit.getScheduler().runTask(plugin, () -> applyAndFinish(sender, restore, job));
        });
    }

    private static Job plan(Fetched fetched, boolean restore) {
        // records arrive oldest-first for rollback and newest-first for restore, so the first one
        // seen at a position is the one that decides the final state there
        Map<String, BlockRecord> targets = new LinkedHashMap<>();
        List<Long> blockIds = new ArrayList<>(fetched.blocks().size());
        for (BlockRecord record : fetched.blocks()) {
            blockIds.add(record.id());
            targets.putIfAbsent(record.world() + ':' + record.x() + ':' + record.y() + ':' + record.z(), record);
        }
        List<BlockChange> blocks = new ArrayList<>(targets.size());
        for (BlockRecord record : targets.values()) {
            blocks.add(new BlockChange(record.world(), record.x(), record.y(), record.z(),
                    restore ? record.newData() : record.oldData()));
        }

        // container changes are additive, so every record for the same container and item is summed
        Map<String, ContainerChange> merged = new LinkedHashMap<>();
        List<Long> itemIds = new ArrayList<>(fetched.items().size());
        for (ItemRecord record : fetched.items()) {
            itemIds.add(record.id());
            if (record.itemData() == null) {
                continue;
            }
            int moved = record.action() == ItemAction.CONTAINER_ADD ? record.amount() : -record.amount();
            int delta = restore ? moved : -moved;
            String key = record.world() + ':' + record.x() + ':' + record.y() + ':' + record.z()
                    + '|' + Base64.getEncoder().encodeToString(record.itemData());
            merged.merge(key,
                    new ContainerChange(record.world(), record.x(), record.y(), record.z(), record.itemData(), delta),
                    (a, b) -> new ContainerChange(a.world(), a.x(), a.y(), a.z(), a.item(), a.delta() + b.delta()));
        }
        List<ContainerChange> containers = new ArrayList<>();
        for (ContainerChange change : merged.values()) {
            if (change.delta() != 0) {
                containers.add(change);
            }
        }
        return new Job(blockIds, itemIds, blocks, containers);
    }

    private void applyAndFinish(CommandSender sender, boolean restore, Job job) {
        String label = restore ? "Restore" : "Rollback";
        sender.sendMessage(Messages.info(label + " started: " + job.blocks().size() + " block position(s) and "
                + job.containers().size() + " container change(s) from "
                + (job.blockIds().size() + job.itemIds().size()) + " record(s)..."));
        long started = System.nanoTime();

        applyBlocks(job.blocks(), (previous, skippedBlocks) ->
                applyContainers(job.containers(), (snapshots, skippedContainers, overflow) ->
                        markRolledBack(job.blockIds(), job.itemIds(), !restore).whenComplete((ignored, error) -> {
                            busy.set(false);
                            if (error != null) {
                                sender.sendMessage(Messages.error("Changes were applied but the database update failed, check the console."));
                                plugin.getLogger().log(Level.SEVERE, "Failed to update rolled-back flags", error);
                                return;
                            }
                            undoSessions.put(key(sender), new UndoSession(!restore, job.blockIds(), job.itemIds(),
                                    previous, snapshots));
                            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                            int skipped = skippedBlocks + skippedContainers;
                            sender.sendMessage(Messages.success(String.format(Locale.ROOT,
                                    "%s complete: %d block(s) and %d container(s) changed%s in %.2fs. Use /sp undo to revert.",
                                    label, previous.size(), snapshots.size(),
                                    skipped > 0 ? ", " + skipped + " skipped" : "", seconds)));
                            if (overflow > 0) {
                                sender.sendMessage(Messages.info(overflow + " item(s) did not fit in their container and were not restored."));
                            }
                        })));
    }

    private CompletableFuture<Void> markRolledBack(List<Long> blockIds, List<Long> itemIds, boolean rolledBack) {
        return CompletableFuture.allOf(
                database.setRolledBack(blockIds, rolledBack),
                database.setItemsRolledBack(itemIds, rolledBack));
    }

    /** Applies changes on the main thread in per-tick batches, recording what each block was before. */
    private void applyBlocks(List<BlockChange> changes, BlocksDone done) {
        if (changes.isEmpty()) {
            done.done(List.of(), 0);
            return;
        }
        int perTick = config.rollbackBlocksPerTick();
        new BukkitRunnable() {
            private int index = 0;
            private int skipped = 0;
            private final List<BlockChange> previous = new ArrayList<>();

            @Override
            public void run() {
                int end = Math.min(index + perTick, changes.size());
                for (; index < end; index++) {
                    BlockChange change = changes.get(index);
                    World world = Bukkit.getWorld(change.world());
                    if (world == null) {
                        skipped++;
                        continue;
                    }

                    BlockData target;
                    try {
                        target = Bukkit.createBlockData(change.data());
                    } catch (IllegalArgumentException ex) {
                        skipped++;
                        continue;
                    }

                    Block block = world.getBlockAt(change.x(), change.y(), change.z());
                    String current = block.getBlockData().getAsString();
                    if (current.equals(change.data())) {
                        continue;
                    }
                    previous.add(new BlockChange(change.world(), change.x(), change.y(), change.z(), current));
                    block.setBlockData(target, false);
                }

                if (index >= changes.size()) {
                    cancel();
                    done.done(previous, skipped);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Adds or removes items container by container, keeping the old contents so the change can be undone. */
    private void applyContainers(List<ContainerChange> changes, ContainersDone done) {
        if (changes.isEmpty()) {
            done.done(List.of(), 0, 0);
            return;
        }
        Map<String, List<ContainerChange>> byPosition = new LinkedHashMap<>();
        for (ContainerChange change : changes) {
            byPosition.computeIfAbsent(change.world() + ':' + change.x() + ':' + change.y() + ':' + change.z(),
                    k -> new ArrayList<>()).add(change);
        }
        List<List<ContainerChange>> groups = new ArrayList<>(byPosition.values());
        int perTick = Math.max(1, config.rollbackBlocksPerTick() / 10);

        new BukkitRunnable() {
            private int index = 0;
            private int skipped = 0;
            private int overflow = 0;
            private final List<ContainerSnapshot> snapshots = new ArrayList<>();

            @Override
            public void run() {
                int end = Math.min(index + perTick, groups.size());
                for (; index < end; index++) {
                    List<ContainerChange> group = groups.get(index);
                    ContainerChange first = group.get(0);
                    Inventory inventory = inventoryAt(first.world(), first.x(), first.y(), first.z());
                    if (inventory == null) {
                        skipped++;
                        continue;
                    }

                    snapshots.add(new ContainerSnapshot(first.world(), first.x(), first.y(), first.z(),
                            copy(inventory.getContents())));
                    for (ContainerChange change : group) {
                        int leftover = applyChange(inventory, change);
                        if (leftover < 0) {
                            skipped++;
                        } else {
                            overflow += leftover;
                        }
                    }
                }

                if (index >= groups.size()) {
                    cancel();
                    done.done(snapshots, skipped, overflow);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void restoreSnapshots(List<ContainerSnapshot> snapshots, SnapshotsDone done) {
        if (snapshots.isEmpty()) {
            done.done(0);
            return;
        }
        int perTick = Math.max(1, config.rollbackBlocksPerTick() / 10);

        new BukkitRunnable() {
            private int index = 0;
            private int skipped = 0;

            @Override
            public void run() {
                int end = Math.min(index + perTick, snapshots.size());
                for (; index < end; index++) {
                    ContainerSnapshot snapshot = snapshots.get(index);
                    Inventory inventory = inventoryAt(snapshot.world(), snapshot.x(), snapshot.y(), snapshot.z());
                    if (inventory == null) {
                        skipped++;
                        continue;
                    }
                    try {
                        inventory.setContents(copy(snapshot.contents()));
                    } catch (IllegalArgumentException ex) {
                        skipped++;
                    }
                }

                if (index >= snapshots.size()) {
                    cancel();
                    done.done(skipped);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** @return items that did not fit, or -1 if the stored item could not be read */
    private static int applyChange(Inventory inventory, ContainerChange change) {
        ItemStack base;
        try {
            base = ItemStack.deserializeBytes(change.item());
        } catch (RuntimeException ex) {
            return -1;
        }

        int remaining = Math.abs(change.delta());
        int leftover = 0;
        int maxStack = Math.max(1, base.getMaxStackSize());
        while (remaining > 0) {
            int part = Math.min(remaining, maxStack);
            remaining -= part;
            ItemStack stack = base.clone();
            stack.setAmount(part);
            if (change.delta() > 0) {
                for (ItemStack rest : inventory.addItem(stack).values()) {
                    leftover += rest.getAmount();
                }
            } else {
                inventory.removeItem(stack);
            }
        }
        return leftover;
    }

    private static Inventory inventoryAt(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return world.getBlockAt(x, y, z).getState(false) instanceof Container container ? container.getInventory() : null;
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static UUID key(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }
}
