package net.rose.saturnProtect.core;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Deletes old log records. One purge at a time, and nothing is deleted without a preview first. */
public final class PurgeService {

    private final SaturnProtect plugin;
    private final Database database;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PurgeService(SaturnProtect plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Tells the sender how much a purge would delete, without deleting anything.
     *
     * @param rerun the exact command that does it, shown to the sender
     */
    public void preview(CommandSender sender, long cutoffMillis, String rerun) {
        if (running.get()) {
            sender.sendMessage(Messages.error("A purge is already running."));
            return;
        }
        database.countOlderThan(cutoffMillis).whenComplete((counts, error) -> {
            if (error != null) {
                sender.sendMessage(Messages.error("Could not count records, check the console."));
                plugin.getLogger().log(Level.SEVERE, "Purge preview failed", error);
                return;
            }
            if (counts.total() == 0L) {
                sender.sendMessage(Messages.info("Nothing is old enough to purge."));
                return;
            }
            sender.sendMessage(Messages.header("Purge"));
            sender.sendMessage(Messages.info("This would delete " + describe(counts) + "."));
            sender.sendMessage(Messages.error("Deleted records are gone for good and can no longer be rolled back."));
            sender.sendMessage(Messages.info("To go ahead, run: " + rerun));
        });
    }

    public void purge(CommandSender sender, long cutoffMillis, boolean vacuum) {
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(Messages.error("A purge is already running."));
            return;
        }
        sender.sendMessage(Messages.info("Purging" + (vacuum ? " and compacting" : "")
                + ", this can take a while on a big database. Logging carries on in the meantime."));
        long started = System.nanoTime();

        database.purge(cutoffMillis)
                .thenCompose(purged -> database.compact(vacuum).thenApply(ignored -> purged))
                .whenComplete((purged, error) -> {
                    running.set(false);
                    if (error != null) {
                        sender.sendMessage(Messages.error("The purge failed part way, check the console."));
                        plugin.getLogger().log(Level.SEVERE, "Purge failed", error);
                        return;
                    }
                    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                    sender.sendMessage(Messages.success(String.format(Locale.ROOT, "Purged %s in %.1fs.%s",
                            describe(purged), seconds,
                            vacuum ? "" : " Disk space is reused by new records; add 'vacuum' to shrink the file.")));
                });
    }

    private static String describe(Database.Purged counts) {
        return counts.blocks() + " block, " + counts.items() + " item and " + counts.events() + " event record(s)";
    }
}
