package net.rose.saturnProtect.core;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Runs lookups and remembers each sender's last query so /sp page works. */
public final class LookupService {

    private static final UUID CONSOLE = new UUID(0L, 0L);

    private enum Kind {
        BLOCKS, ITEMS, EVENTS
    }

    private record LastLookup(LookupQuery query, Kind kind) {
    }

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final Database database;
    private final Map<UUID, LastLookup> lastQueries = new ConcurrentHashMap<>();

    public LookupService(SaturnProtect plugin, ProtectConfig config, Database database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    /** Block change lookup. */
    public void run(CommandSender sender, LookupQuery query) {
        lastQueries.put(key(sender), new LastLookup(query, Kind.BLOCKS));
        show(sender, query, Kind.BLOCKS, 1);
    }

    /** Container / drop / pickup lookup. */
    public void runItems(CommandSender sender, LookupQuery query) {
        lastQueries.put(key(sender), new LastLookup(query, Kind.ITEMS));
        show(sender, query, Kind.ITEMS, 1);
    }

    /** Session, chat, command, kill and sign lookup. */
    public void runEvents(CommandSender sender, LookupQuery query) {
        lastQueries.put(key(sender), new LastLookup(query, Kind.EVENTS));
        show(sender, query, Kind.EVENTS, 1);
    }

    public void page(CommandSender sender, int page) {
        LastLookup last = lastQueries.get(key(sender));
        if (last == null) {
            sender.sendMessage(Messages.error("Run a lookup first, then use /sp page <number>."));
            return;
        }
        show(sender, last.query(), last.kind(), page);
    }

    public void forget(UUID player) {
        lastQueries.remove(player);
    }

    private void show(CommandSender sender, LookupQuery query, Kind kind, int page) {
        int pageSize = config.pageSize();
        int offset = (page - 1) * pageSize;

        if (kind == Kind.EVENTS) {
            database.lookupEvents(query, offset, pageSize).whenComplete((result, error) -> {
                int pages = pagesOrReport(sender, error, result == null ? 0 : result.total(), page, pageSize);
                if (pages > 0) {
                    Messages.sendEventPage(sender, page, pages, result);
                }
            });
        } else if (kind == Kind.ITEMS) {
            database.lookupItems(query, offset, pageSize).whenComplete((result, error) -> {
                int pages = pagesOrReport(sender, error, result == null ? 0 : result.total(), page, pageSize);
                if (pages > 0) {
                    Messages.sendItemPage(sender, page, pages, result);
                }
            });
        } else {
            database.lookupBlocks(query, offset, pageSize).whenComplete((result, error) -> {
                int pages = pagesOrReport(sender, error, result == null ? 0 : result.total(), page, pageSize);
                if (pages > 0) {
                    Messages.sendLookupPage(sender, page, pages, result);
                }
            });
        }
    }

    /** @return the page count, or -1 after telling the sender why there is nothing to show */
    private int pagesOrReport(CommandSender sender, Throwable error, long total, int page, int pageSize) {
        if (error != null) {
            sender.sendMessage(Messages.error("Lookup failed, check the console."));
            plugin.getLogger().log(Level.SEVERE, "Lookup failed", error);
            return -1;
        }
        if (total == 0) {
            sender.sendMessage(Messages.info("No results found."));
            return -1;
        }
        int pages = (int) Math.ceil(total / (double) pageSize);
        if (page > pages) {
            sender.sendMessage(Messages.error("Page " + page + " does not exist. There are " + pages + " page(s)."));
            return -1;
        }
        return pages;
    }

    private static UUID key(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }
}
