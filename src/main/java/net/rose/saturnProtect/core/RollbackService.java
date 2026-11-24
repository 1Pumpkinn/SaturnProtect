package net.rose.saturnProtect.core;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.db.TooManyRecordsException;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Rollback / restore / undo. Records are fetched async, collapsed to one target state per position,
 * then applied on the main thread a few hundred blocks per tick.
 */
public final class RollbackService {

    private static final UUID CONSOLE = new UUID(0L, 0L);

    public record BlockChange(String world, int x, int y, int z, String data) {
    }

    private record UndoSession(boolean wasRollback, List<Long> ids, List<BlockChange> previous) {
    }

    private interface ApplyDone {
        void done(List<BlockChange> previous, int skipped);
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
                + " (" + session.previous().size() + " block(s))..."));
        apply(session.previous(), (previous, skipped) ->
                database.setRolledBack(session.ids(), !session.wasRollback()).whenComplete((ignored, error) -> {
                    busy.set(false);
                    if (error != null) {
                        sender.sendMessage(Messages.error("Blocks were reverted but the database update failed, check the console."));
                        plugin.getLogger().log(Level.SEVERE, "Failed to update rolled-back flags on undo", error);
                        return;
                    }
                    sender.sendMessage(Messages.success("Undo complete: " + previous.size() + " block(s) changed"
                            + (skipped > 0 ? ", " + skipped + " skipped" : "") + "."));
                }));
    }

    private void start(CommandSender sender, LookupQuery query, boolean restore) {
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(Messages.error("Another rollback is already running, try again shortly."));
            return;
        }
        sender.sendMessage(Messages.info("Searching records..."));

        database.fetchForRollback(query, restore, config.rollbackMaxRecords()).whenComplete((records, error) -> {
            if (error != null) {
                busy.set(false);
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (cause instanceof TooManyRecordsException tooMany) {
                    sender.sendMessage(Messages.error("That matches " + tooMany.count() + " records (limit " + tooMany.max()
                            + "). Narrow it with a smaller radius or time."));
                } else {
                    sender.sendMessage(Messages.error("Search failed, check the console."));
                    plugin.getLogger().log(Level.SEVERE, "Rollback search failed", cause);
                }
                return;
            }
            if (records.isEmpty()) {
                busy.set(false);
                sender.sendMessage(Messages.info(restore ? "Nothing to restore." : "Nothing to roll back."));
                return;
            }

            // records arrive oldest-first for rollback and newest-first for restore, so the first one
            // seen at a position is the one that decides the final state there
            Map<String, BlockRecord> targets = new LinkedHashMap<>();
            List<Long> ids = new ArrayList<>(records.size());
            for (BlockRecord record : records) {
                ids.add(record.id());
                targets.putIfAbsent(record.world() + ':' + record.x() + ':' + record.y() + ':' + record.z(), record);
            }
            List<BlockChange> changes = new ArrayList<>(targets.size());
            for (BlockRecord record : targets.values()) {
                changes.add(new BlockChange(record.world(), record.x(), record.y(), record.z(),
                        restore ? record.newData() : record.oldData()));
            }

            Bukkit.getScheduler().runTask(plugin, () -> applyAndFinish(sender, restore, ids, changes));
        });
    }

    private void applyAndFinish(CommandSender sender, boolean restore, List<Long> ids, List<BlockChange> changes) {
        String label = restore ? "Restore" : "Rollback";
        sender.sendMessage(Messages.info(label + " started: " + changes.size() + " position(s) from "
                + ids.size() + " record(s)..."));
        long started = System.nanoTime();

        apply(changes, (previous, skipped) ->
                database.setRolledBack(ids, !restore).whenComplete((ignored, error) -> {
                    busy.set(false);
                    if (error != null) {
                        sender.sendMessage(Messages.error("Blocks were changed but the database update failed, check the console."));
                        plugin.getLogger().log(Level.SEVERE, "Failed to update rolled-back flags", error);
                        return;
                    }
                    undoSessions.put(key(sender), new UndoSession(!restore, ids, previous));
                    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                    sender.sendMessage(Messages.success(String.format(Locale.ROOT,
                            "%s complete: %d block(s) changed%s in %.2fs. Use /sp undo to revert.",
                            label, previous.size(), skipped > 0 ? ", " + skipped + " skipped" : "", seconds)));
                }));
    }

    /** Applies changes on the main thread in per-tick batches, recording what each block was before. */
    private void apply(List<BlockChange> changes, ApplyDone done) {
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

    private static UUID key(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }
}
