package net.rose.saturnProtect.db;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.EventLookupResult;
import net.rose.saturnProtect.model.EventRecord;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.ItemLookupResult;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.PositionHistory;
import net.rose.saturnProtect.model.LogAction;
import net.rose.saturnProtect.model.LookupEntry;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.model.LookupResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite storage. Everything that touches the JDBC connection runs on a single worker thread,
 * so the connection is never used concurrently. Writes are queued and flushed in batches;
 * reads flush the queue first so lookups always see the latest data.
 */
public final class Database {

    private static final int MAX_PER_FLUSH = 20_000;
    private static final int PURGE_CHUNK = 20_000;
    private static final String[] PURGE_TABLES = {"sp_block", "sp_item", "sp_event"};

    private static final String INSERT_BLOCK = """
            INSERT INTO sp_block (time, actor, actor_uuid, world, x, y, z, action, old_data, new_data, rolled_back)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""";

    private static final String INSERT_ITEM = """
            INSERT INTO sp_item (time, actor, actor_uuid, world, x, y, z, action, item_type, amount, item_data, container)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_EVENT = """
            INSERT INTO sp_event (time, actor, actor_uuid, world, x, y, z, action, subject, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_ITEM_COLUMNS =
            "SELECT id, time, actor, actor_uuid, world, x, y, z, action, item_type, amount, container, rolled_back FROM sp_item";

    private static final String SELECT_ITEM_ROLLBACK_COLUMNS =
            "SELECT id, time, actor, actor_uuid, world, x, y, z, action, item_type, amount, container, rolled_back, item_data FROM sp_item";

    private static final String SELECT_EVENT_COLUMNS =
            "SELECT id, time, actor, actor_uuid, world, x, y, z, action, subject, detail FROM sp_event";

    private static final String SELECT_BLOCK_COLUMNS =
            "SELECT id, time, actor, actor_uuid, world, x, y, z, action, old_data, new_data, rolled_back FROM sp_block";

    public record Stats(long blockRows, long itemRows, long eventRows, int queued, long fileBytes) {
    }

    /** Row counts per table, used for what a purge would delete or did delete. */
    public record Purged(long blocks, long items, long events) {

        public long total() {
            return blocks + items + events;
        }
    }

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final Path file;
    private final ConcurrentLinkedQueue<BlockRecord> pendingBlocks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ItemRecord> pendingItems = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<EventRecord> pendingEvents = new ConcurrentLinkedQueue<>();
    private final ScheduledThreadPoolExecutor worker;
    private Connection connection;
    private ScheduledFuture<?> flushTask;

    public Database(SaturnProtect plugin, ProtectConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = plugin.getDataFolder().toPath().resolve(config.databaseFile());
        this.worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "SaturnProtect-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sp_block (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        actor_uuid TEXT,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action INTEGER NOT NULL,
                        old_data TEXT NOT NULL,
                        new_data TEXT NOT NULL,
                        rolled_back INTEGER NOT NULL DEFAULT 0
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sp_item (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        actor_uuid TEXT,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action INTEGER NOT NULL,
                        item_type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        item_data BLOB,
                        rolled_back INTEGER NOT NULL DEFAULT 0
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sp_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        actor_uuid TEXT,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action INTEGER NOT NULL,
                        subject TEXT NOT NULL DEFAULT '',
                        detail TEXT
                    )""");
            addColumnIfMissing(statement, "sp_item", "rolled_back", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "sp_item", "container", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_event_pos ON sp_event (world, x, y, z, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_event_time ON sp_event (time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_event_actor ON sp_event (actor, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_item_pos ON sp_item (world, x, y, z, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_item_time ON sp_item (time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_item_actor ON sp_item (actor, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_block_pos ON sp_block (world, x, y, z, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_block_time ON sp_block (time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_block_actor ON sp_block (actor, time)");
        }

        scheduleFlush();
    }

    /** Applies a changed {@code writer.flush-interval-ms}. Call after the config was reloaded. */
    public void reloadSettings() {
        scheduleFlush();
    }

    private synchronized void scheduleFlush() {
        if (worker.isShutdown()) {
            return;
        }
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        long interval = config.flushIntervalMs();
        flushTask = worker.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Queue a block record; it is written asynchronously. Safe to call from any thread. */
    public void queue(BlockRecord record) {
        pendingBlocks.add(record);
    }

    /** Queue an item record; it is written asynchronously. Safe to call from any thread. */
    public void queue(ItemRecord record) {
        pendingItems.add(record);
    }

    /** Queue an event record; it is written asynchronously. Safe to call from any thread. */
    public void queue(EventRecord record) {
        pendingEvents.add(record);
    }

    public CompletableFuture<List<ItemRecord>> itemHistory(String world, int x, int y, int z, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            String sql = SELECT_ITEM_COLUMNS
                    + " WHERE world = ? AND x = ? AND y = ? AND z = ? ORDER BY time DESC, id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, world);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                statement.setInt(5, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    List<ItemRecord> records = new ArrayList<>();
                    while (rs.next()) {
                        records.add(mapItem(rs, false));
                    }
                    return records;
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public CompletableFuture<ItemLookupResult> lookupItems(LookupQuery query, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            Where where = buildItemWhere(query);
            try {
                long total;
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sp_item" + where.sql())) {
                    bind(count, where.params(), 1);
                    try (ResultSet rs = count.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0L;
                    }
                }

                List<ItemRecord> records = new ArrayList<>();
                if (total > 0 && offset < total) {
                    String sql = SELECT_ITEM_COLUMNS + where.sql() + " ORDER BY time DESC, id DESC LIMIT ? OFFSET ?";
                    try (PreparedStatement select = connection.prepareStatement(sql)) {
                        int next = bind(select, where.params(), 1);
                        select.setInt(next, limit);
                        select.setInt(next + 1, offset);
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next()) {
                                records.add(mapItem(rs, false));
                            }
                        }
                    }
                }
                return new ItemLookupResult(total, records);
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public CompletableFuture<EventLookupResult> lookupEvents(LookupQuery query, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            Where where = buildEventWhere(query);
            try {
                long total;
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sp_event" + where.sql())) {
                    bind(count, where.params(), 1);
                    try (ResultSet rs = count.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0L;
                    }
                }

                List<EventRecord> records = new ArrayList<>();
                if (total > 0 && offset < total) {
                    String sql = SELECT_EVENT_COLUMNS + where.sql() + " ORDER BY time DESC, id DESC LIMIT ? OFFSET ?";
                    try (PreparedStatement select = connection.prepareStatement(sql)) {
                        int next = bind(select, where.params(), 1);
                        select.setInt(next, limit);
                        select.setInt(next + 1, offset);
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next()) {
                                records.add(mapEvent(rs));
                            }
                        }
                    }
                }
                return new EventLookupResult(total, records);
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    /**
     * Container changes a rollback (restore = false) or restore (restore = true) would touch, with the
     * serialized item included. Only add/remove rows qualify; drops and pickups are never rolled back.
     *
     * @throws TooManyRecordsException (wrapped in the future) if more than {@code max} rows match
     */
    public CompletableFuture<List<ItemRecord>> fetchContainerRecords(LookupQuery query, boolean restore, int max) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            Where where = buildContainerWhere(query, restore);
            try {
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sp_item" + where.sql())) {
                    bind(count, where.params(), 1);
                    try (ResultSet rs = count.executeQuery()) {
                        long total = rs.next() ? rs.getLong(1) : 0L;
                        if (total > max) {
                            throw new TooManyRecordsException(total, max);
                        }
                    }
                }

                try (PreparedStatement select = connection.prepareStatement(
                        SELECT_ITEM_ROLLBACK_COLUMNS + where.sql() + " ORDER BY time ASC, id ASC")) {
                    bind(select, where.params(), 1);
                    try (ResultSet rs = select.executeQuery()) {
                        List<ItemRecord> records = new ArrayList<>();
                        while (rs.next()) {
                            records.add(mapItem(rs, true));
                        }
                        return records;
                    }
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public CompletableFuture<List<BlockRecord>> blockHistory(String world, int x, int y, int z, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            String sql = SELECT_BLOCK_COLUMNS
                    + " WHERE world = ? AND x = ? AND y = ? AND z = ? ORDER BY time DESC, id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, world);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                statement.setInt(5, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    List<BlockRecord> records = new ArrayList<>();
                    while (rs.next()) {
                        records.add(mapBlock(rs));
                    }
                    return records;
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    /**
     * Everything logged at a spot. Block changes are read at the block itself; container moves and spot bound
     * events at {@code content}, which differs for a double chest where both halves share the left half's
     * history. Drops, pickups, chat, kills and sessions are left out, they are logged where the player stands,
     * not where something happened to a block.
     */
    public CompletableFuture<PositionHistory> positionHistory(String world, int bx, int by, int bz,
                                                              int cx, int cy, int cz, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            String where = " WHERE world = ? AND x = ? AND y = ? AND z = ?";
            String order = " ORDER BY time DESC, id DESC LIMIT ?";
            String itemActions = " AND action IN (" + ItemAction.CONTAINER_ADD.id() + ", "
                    + ItemAction.CONTAINER_REMOVE.id() + ")";
            String eventActions = " AND action IN (" + EventAction.SIGN.id() + ", " + EventAction.ENTITY_PLACE.id()
                    + ", " + EventAction.ENTITY_BREAK.id() + ", " + EventAction.ENTITY_ITEM.id() + ", "
                    + EventAction.CONTAINER_OPEN.id() + ")";
            try {
                List<BlockRecord> blocks = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(SELECT_BLOCK_COLUMNS + where + order)) {
                    bindSpot(statement, world, bx, by, bz, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            blocks.add(mapBlock(rs));
                        }
                    }
                }
                List<ItemRecord> items = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        SELECT_ITEM_COLUMNS + where + itemActions + order)) {
                    bindSpot(statement, world, cx, cy, cz, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            items.add(mapItem(rs, false));
                        }
                    }
                }
                List<EventRecord> events = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        SELECT_EVENT_COLUMNS + where + eventActions + order)) {
                    bindSpot(statement, world, cx, cy, cz, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            events.add(mapEvent(rs));
                        }
                    }
                }
                return new PositionHistory(blocks, items, events);
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    private static void bindSpot(PreparedStatement statement, String world, int x, int y, int z, int limit)
            throws SQLException {
        statement.setString(1, world);
        statement.setInt(2, x);
        statement.setInt(3, y);
        statement.setInt(4, z);
        statement.setInt(5, limit);
    }

    /**
     * Block changes and player kills in one list, newest first. Blocks are searched unless only {@code a:kill}
     * was given, and kills are searched unless only block actions were given.
     */
    public CompletableFuture<LookupResult> lookupBlocks(LookupQuery query, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            boolean blocks = !query.actions().isEmpty() || query.eventActions().isEmpty();
            boolean kills = query.eventActions().contains(EventAction.KILL)
                    || (query.eventActions().isEmpty() && query.actions().isEmpty());

            List<String> parts = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (blocks) {
                Where where = buildWhere(query, null);
                parts.add("SELECT 0 AS kind, id, time, actor, actor_uuid, world, x, y, z, action, "
                        + "old_data AS a, new_data AS b, rolled_back FROM sp_block" + where.sql());
                params.addAll(where.params());
            }
            if (kills) {
                Where where = buildEventWhere(query);
                parts.add("SELECT 1 AS kind, id, time, actor, actor_uuid, world, x, y, z, action, "
                        + "subject AS a, detail AS b, 0 AS rolled_back FROM sp_event" + where.sql()
                        + " AND action = " + EventAction.KILL.id());
                params.addAll(where.params());
            }
            if (parts.isEmpty()) {
                // only actions that live in other tables were asked for, e.g. a:entity-place
                return new LookupResult(0L, List.of());
            }
            String union = String.join(" UNION ALL ", parts);

            try {
                long total;
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM (" + union + ")")) {
                    bind(count, params, 1);
                    try (ResultSet rs = count.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0L;
                    }
                }

                List<LookupEntry> records = new ArrayList<>();
                if (total > 0 && offset < total) {
                    String sql = "SELECT * FROM (" + union + ") ORDER BY time DESC, kind ASC, id DESC LIMIT ? OFFSET ?";
                    try (PreparedStatement select = connection.prepareStatement(sql)) {
                        int next = bind(select, params, 1);
                        select.setInt(next, limit);
                        select.setInt(next + 1, offset);
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next()) {
                                records.add(mapEntry(rs));
                            }
                        }
                    }
                }
                return new LookupResult(total, records);
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public record Placer(String name, UUID uuid) {
    }

    /** The player who most recently placed lava closest to a position, or null if there is none. */
    public CompletableFuture<Placer> findLavaPlacer(String world, int x, int y, int z, int radius, long sinceMillis) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            String sql = "SELECT actor, actor_uuid FROM sp_block WHERE world = ? AND time >= ? AND action = ? "
                    + "AND actor_uuid IS NOT NULL AND new_data LIKE 'minecraft:lava%' "
                    + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? "
                    + "ORDER BY ((x - ?) * (x - ?) + (y - ?) * (y - ?) + (z - ?) * (z - ?)) ASC, time DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, world);
                statement.setLong(i++, sinceMillis);
                statement.setInt(i++, LogAction.PLACE.id());
                statement.setInt(i++, x - radius);
                statement.setInt(i++, x + radius);
                statement.setInt(i++, y - radius);
                statement.setInt(i++, y + radius);
                statement.setInt(i++, z - radius);
                statement.setInt(i++, z + radius);
                for (int coordinate : new int[]{x, x, y, y, z, z}) {
                    statement.setInt(i++, coordinate);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Placer(rs.getString("actor"), UUID.fromString(rs.getString("actor_uuid")));
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    /**
     * Rows a rollback (restore = false) or restore (restore = true) would touch.
     * Rollback gets oldest-first rows that are not yet rolled back; restore gets newest-first
     * rows that are rolled back.
     *
     * @throws TooManyRecordsException (wrapped in the future) if more than {@code max} rows match
     */
    public CompletableFuture<List<BlockRecord>> fetchForRollback(LookupQuery query, boolean restore, int max) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            Where where = buildWhere(query, restore);
            try {
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sp_block" + where.sql())) {
                    bind(count, where.params(), 1);
                    try (ResultSet rs = count.executeQuery()) {
                        long total = rs.next() ? rs.getLong(1) : 0L;
                        if (total > max) {
                            throw new TooManyRecordsException(total, max);
                        }
                    }
                }

                String order = restore ? " ORDER BY time DESC, id DESC" : " ORDER BY time ASC, id ASC";
                try (PreparedStatement select = connection.prepareStatement(SELECT_BLOCK_COLUMNS + where.sql() + order)) {
                    bind(select, where.params(), 1);
                    try (ResultSet rs = select.executeQuery()) {
                        List<BlockRecord> records = new ArrayList<>();
                        while (rs.next()) {
                            records.add(mapBlock(rs));
                        }
                        return records;
                    }
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public CompletableFuture<Void> setRolledBack(List<Long> ids, boolean rolledBack) {
        return setFlag("sp_block", ids, rolledBack);
    }

    public CompletableFuture<Void> setItemsRolledBack(List<Long> ids, boolean rolledBack) {
        return setFlag("sp_item", ids, rolledBack);
    }

    private CompletableFuture<Void> setFlag(String table, List<Long> ids, boolean rolledBack) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            flushPending();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table + " SET rolled_back = ? WHERE id = ?")) {
                    int inBatch = 0;
                    for (long id : ids) {
                        statement.setInt(1, rolledBack ? 1 : 0);
                        statement.setLong(2, id);
                        statement.addBatch();
                        if (++inBatch >= config.batchSize()) {
                            statement.executeBatch();
                            inBatch = 0;
                        }
                    }
                    if (inBatch > 0) {
                        statement.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // nothing more we can do
                }
                throw new CompletionException(ex);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // nothing more we can do
                }
            }
        }, worker);
    }

    /** How many rows older than {@code beforeMillis} a purge would delete. */
    public CompletableFuture<Purged> countOlderThan(long beforeMillis) {
        return CompletableFuture.supplyAsync(() -> {
            long[] counts = new long[PURGE_TABLES.length];
            try {
                for (int i = 0; i < PURGE_TABLES.length; i++) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM " + PURGE_TABLES[i] + " WHERE time < ?")) {
                        statement.setLong(1, beforeMillis);
                        try (ResultSet rs = statement.executeQuery()) {
                            counts[i] = rs.next() ? rs.getLong(1) : 0L;
                        }
                    }
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
            return new Purged(counts[0], counts[1], counts[2]);
        }, worker);
    }

    /**
     * Deletes every row older than {@code beforeMillis}. Works in chunks, and each chunk is its own task on the
     * database thread, so queued writes and lookups get a turn between chunks instead of waiting for the whole purge.
     */
    public CompletableFuture<Purged> purge(long beforeMillis) {
        return purgeNext(beforeMillis, new long[PURGE_TABLES.length]);
    }

    private CompletableFuture<Purged> purgeNext(long beforeMillis, long[] totals) {
        return CompletableFuture.supplyAsync(() -> {
            long deleted = 0L;
            try {
                for (int i = 0; i < PURGE_TABLES.length; i++) {
                    String table = PURGE_TABLES[i];
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                                    + " WHERE time < ? LIMIT ?)")) {
                        statement.setLong(1, beforeMillis);
                        statement.setInt(2, PURGE_CHUNK);
                        int removed = statement.executeUpdate();
                        totals[i] += removed;
                        deleted += removed;
                    }
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
            return deleted;
        }, worker).thenCompose(deleted -> deleted > 0L
                ? purgeNext(beforeMillis, totals)
                : CompletableFuture.completedFuture(new Purged(totals[0], totals[1], totals[2])));
    }

    /**
     * Shrinks the write-ahead log, and with {@code vacuum} also rewrites the database file so deleted rows give
     * their disk space back. VACUUM needs free disk space about the size of the database and can take a while.
     */
    public CompletableFuture<Void> compact(boolean vacuum) {
        return CompletableFuture.runAsync(() -> {
            try (Statement statement = connection.createStatement()) {
                if (vacuum) {
                    statement.execute("VACUUM");
                }
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public CompletableFuture<Stats> stats() {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sp_block")) {
                long rows = rs.next() ? rs.getLong(1) : 0L;
                long itemRows = 0L;
                try (ResultSet items = statement.executeQuery("SELECT COUNT(*) FROM sp_item")) {
                    if (items.next()) {
                        itemRows = items.getLong(1);
                    }
                }
                long eventRows = 0L;
                try (ResultSet events = statement.executeQuery("SELECT COUNT(*) FROM sp_event")) {
                    if (events.next()) {
                        eventRows = events.getLong(1);
                    }
                }
                long size = Files.exists(file) ? Files.size(file) : 0L;
                return new Stats(rows, itemRows, eventRows,
                        pendingBlocks.size() + pendingItems.size() + pendingEvents.size(), size);
            } catch (SQLException | IOException ex) {
                throw new CompletionException(ex);
            }
        }, worker);
    }

    public void close() {
        if (!worker.isShutdown()) {
            worker.schedule(this::flushSafely, 0, TimeUnit.MILLISECONDS);
            worker.shutdown();
            try {
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Timed out while flushing log entries on shutdown");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to close the database", ex);
        }
    }

    private void flushSafely() {
        try {
            flushPending();
        } catch (Throwable throwable) {
            // never let an exception cancel the periodic flush task
            plugin.getLogger().log(Level.SEVERE, "Failed to flush log entries", throwable);
        }
    }

    /** Must only be called from the worker thread. */
    private void flushPending() {
        flushBlocks();
        flushItems();
        flushEvents();
    }

    private static void addColumnIfMissing(Statement statement, String table, String column, String definition)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void flushBlocks() {
        if (connection == null || pendingBlocks.isEmpty()) {
            return;
        }

        List<BlockRecord> batch = new ArrayList<>();
        BlockRecord record;
        while (batch.size() < MAX_PER_FLUSH && (record = pendingBlocks.poll()) != null) {
            batch.add(record);
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_BLOCK)) {
                int inBatch = 0;
                for (BlockRecord entry : batch) {
                    statement.setLong(1, entry.time());
                    statement.setString(2, entry.actor());
                    statement.setString(3, entry.actorUuid() == null ? null : entry.actorUuid().toString());
                    statement.setString(4, entry.world());
                    statement.setInt(5, entry.x());
                    statement.setInt(6, entry.y());
                    statement.setInt(7, entry.z());
                    statement.setInt(8, entry.action().id());
                    statement.setString(9, entry.oldData());
                    statement.setString(10, entry.newData());
                    statement.addBatch();
                    if (++inBatch >= config.batchSize()) {
                        statement.executeBatch();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    statement.executeBatch();
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write " + batch.size() + " log entries", ex);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // nothing more we can do
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // nothing more we can do
            }
        }
    }

    private void flushItems() {
        if (connection == null || pendingItems.isEmpty()) {
            return;
        }

        List<ItemRecord> batch = new ArrayList<>();
        ItemRecord record;
        while (batch.size() < MAX_PER_FLUSH && (record = pendingItems.poll()) != null) {
            batch.add(record);
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_ITEM)) {
                int inBatch = 0;
                for (ItemRecord entry : batch) {
                    statement.setLong(1, entry.time());
                    statement.setString(2, entry.actor());
                    statement.setString(3, entry.actorUuid() == null ? null : entry.actorUuid().toString());
                    statement.setString(4, entry.world());
                    statement.setInt(5, entry.x());
                    statement.setInt(6, entry.y());
                    statement.setInt(7, entry.z());
                    statement.setInt(8, entry.action().id());
                    statement.setString(9, entry.itemType());
                    statement.setInt(10, entry.amount());
                    statement.setBytes(11, entry.itemData());
                    statement.setString(12, entry.container());
                    statement.addBatch();
                    if (++inBatch >= config.batchSize()) {
                        statement.executeBatch();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    statement.executeBatch();
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write " + batch.size() + " item log entries", ex);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // nothing more we can do
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // nothing more we can do
            }
        }
    }

    private void flushEvents() {
        if (connection == null || pendingEvents.isEmpty()) {
            return;
        }

        List<EventRecord> batch = new ArrayList<>();
        EventRecord record;
        while (batch.size() < MAX_PER_FLUSH && (record = pendingEvents.poll()) != null) {
            batch.add(record);
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
                int inBatch = 0;
                for (EventRecord entry : batch) {
                    statement.setLong(1, entry.time());
                    statement.setString(2, entry.actor());
                    statement.setString(3, entry.actorUuid() == null ? null : entry.actorUuid().toString());
                    statement.setString(4, entry.world());
                    statement.setInt(5, entry.x());
                    statement.setInt(6, entry.y());
                    statement.setInt(7, entry.z());
                    statement.setInt(8, entry.action().id());
                    statement.setString(9, entry.subject() == null ? "" : entry.subject());
                    statement.setString(10, entry.detail());
                    statement.addBatch();
                    if (++inBatch >= config.batchSize()) {
                        statement.executeBatch();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    statement.executeBatch();
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write " + batch.size() + " event log entries", ex);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // nothing more we can do
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // nothing more we can do
            }
        }
    }

    private record Where(String sql, List<Object> params) {
    }

    /** @param rolledBack null = any, otherwise only rows with that rolled-back state */
    private static Where buildWhere(LookupQuery query, Boolean rolledBack) {
        StringBuilder sql = new StringBuilder(" WHERE time >= ?");
        List<Object> params = new ArrayList<>();
        params.add(query.sinceMillis());

        if (rolledBack != null) {
            sql.append(" AND rolled_back = ?");
            params.add(rolledBack ? 1 : 0);
        }

        if (!query.users().isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < query.users().size(); i++) {
                sql.append(i == 0 ? "" : " OR ").append("actor = ? COLLATE NOCASE");
                params.add(query.users().get(i));
            }
            sql.append(")");
        }

        if (!query.actions().isEmpty()) {
            sql.append(" AND action IN (");
            for (int i = 0; i < query.actions().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(query.actions().get(i).id());
            }
            sql.append(")");
        }

        LookupQuery.Area area = query.area();
        if (area != null) {
            sql.append(" AND world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            params.add(area.world());
            params.add(area.x() - area.radius());
            params.add(area.x() + area.radius());
            params.add(area.y() - area.radius());
            params.add(area.y() + area.radius());
            params.add(area.z() - area.radius());
            params.add(area.z() + area.radius());
        }

        if (!query.includeBlocks().isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < query.includeBlocks().size(); i++) {
                sql.append(i == 0 ? "" : " OR ").append(blockMatch());
                addBlockParams(params, query.includeBlocks().get(i));
            }
            sql.append(")");
        }

        for (String excluded : query.excludeBlocks()) {
            sql.append(" AND NOT ").append(blockMatch());
            addBlockParams(params, excluded);
        }

        return new Where(sql.toString(), params);
    }

    private static Where buildItemWhere(LookupQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE time >= ?");
        List<Object> params = new ArrayList<>();
        params.add(query.sinceMillis());

        if (!query.users().isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < query.users().size(); i++) {
                sql.append(i == 0 ? "" : " OR ").append("actor = ? COLLATE NOCASE");
                params.add(query.users().get(i));
            }
            sql.append(")");
        }

        if (!query.itemActions().isEmpty()) {
            sql.append(" AND action IN (");
            for (int i = 0; i < query.itemActions().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(query.itemActions().get(i).id());
            }
            sql.append(")");
        }

        LookupQuery.Area area = query.area();
        if (area != null) {
            sql.append(" AND world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            params.add(area.world());
            params.add(area.x() - area.radius());
            params.add(area.x() + area.radius());
            params.add(area.y() - area.radius());
            params.add(area.y() + area.radius());
            params.add(area.z() - area.radius());
            params.add(area.z() + area.radius());
        }

        if (!query.includeBlocks().isEmpty()) {
            sql.append(" AND item_type IN (");
            for (int i = 0; i < query.includeBlocks().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(query.includeBlocks().get(i));
            }
            sql.append(")");
        }

        for (String excluded : query.excludeBlocks()) {
            sql.append(" AND item_type <> ?");
            params.add(excluded);
        }

        return new Where(sql.toString(), params);
    }

    private static Where buildContainerWhere(LookupQuery query, boolean rolledBack) {
        Where base = buildItemWhere(query);
        List<Object> params = new ArrayList<>(base.params());
        params.add(rolledBack ? 1 : 0);
        return new Where(base.sql() + " AND rolled_back = ? AND action IN (0, 1)", params);
    }

    private static Where buildEventWhere(LookupQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE time >= ?");
        List<Object> params = new ArrayList<>();
        params.add(query.sinceMillis());

        if (!query.users().isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < query.users().size(); i++) {
                sql.append(i == 0 ? "" : " OR ").append("actor = ? COLLATE NOCASE");
                params.add(query.users().get(i));
            }
            sql.append(")");
        }

        if (!query.eventActions().isEmpty()) {
            sql.append(" AND action IN (");
            for (int i = 0; i < query.eventActions().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(query.eventActions().get(i).id());
            }
            sql.append(")");
        }

        LookupQuery.Area area = query.area();
        if (area != null) {
            sql.append(" AND world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            params.add(area.world());
            params.add(area.x() - area.radius());
            params.add(area.x() + area.radius());
            params.add(area.y() - area.radius());
            params.add(area.y() + area.radius());
            params.add(area.z() - area.radius());
            params.add(area.z() + area.radius());
        }

        if (!query.includeBlocks().isEmpty()) {
            sql.append(" AND detail IN (");
            for (int i = 0; i < query.includeBlocks().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(query.includeBlocks().get(i));
            }
            sql.append(")");
        }

        for (String excluded : query.excludeBlocks()) {
            sql.append(" AND (detail IS NULL OR detail <> ?)");
            params.add(excluded);
        }

        if (query.text() != null) {
            sql.append(" AND subject LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(query.text()) + "%");
        }

        return new Where(sql.toString(), params);
    }

    /** Matches a block id with or without block state, in either the old or the new data. */
    private static String blockMatch() {
        return "(old_data = ? OR old_data LIKE ? ESCAPE '\\' OR new_data = ? OR new_data LIKE ? ESCAPE '\\')";
    }

    private static void addBlockParams(List<Object> params, String blockId) {
        String prefix = escapeLike(blockId) + "[%";
        params.add(blockId);
        params.add(prefix);
        params.add(blockId);
        params.add(prefix);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** @return the next free parameter index */
    private static int bind(PreparedStatement statement, List<Object> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            statement.setObject(index++, param);
        }
        return index;
    }

    private static ItemRecord mapItem(ResultSet rs, boolean withData) throws SQLException {
        String uuid = rs.getString("actor_uuid");
        return new ItemRecord(
                rs.getLong("id"),
                rs.getLong("time"),
                rs.getString("actor"),
                uuid == null ? null : UUID.fromString(uuid),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                ItemAction.fromId(rs.getInt("action")),
                rs.getString("item_type"),
                rs.getInt("amount"),
                withData ? rs.getBytes("item_data") : null,
                rs.getString("container"),
                rs.getInt("rolled_back") != 0
        );
    }

    private static LookupEntry mapEntry(ResultSet rs) throws SQLException {
        String uuid = rs.getString("actor_uuid");
        UUID actorUuid = uuid == null ? null : UUID.fromString(uuid);
        if (rs.getInt("kind") == 0) {
            return new BlockRecord(
                    rs.getLong("id"), rs.getLong("time"), rs.getString("actor"), actorUuid, rs.getString("world"),
                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                    LogAction.fromId(rs.getInt("action")),
                    rs.getString("a"), rs.getString("b"), rs.getInt("rolled_back") != 0);
        }
        return new EventRecord(
                rs.getLong("id"), rs.getLong("time"), rs.getString("actor"), actorUuid, rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                EventAction.fromId(rs.getInt("action")),
                rs.getString("a"), rs.getString("b"));
    }

    private static EventRecord mapEvent(ResultSet rs) throws SQLException {
        String uuid = rs.getString("actor_uuid");
        return new EventRecord(
                rs.getLong("id"),
                rs.getLong("time"),
                rs.getString("actor"),
                uuid == null ? null : UUID.fromString(uuid),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                EventAction.fromId(rs.getInt("action")),
                rs.getString("subject"),
                rs.getString("detail")
        );
    }

    private static BlockRecord mapBlock(ResultSet rs) throws SQLException {
        String uuid = rs.getString("actor_uuid");
        return new BlockRecord(
                rs.getLong("id"),
                rs.getLong("time"),
                rs.getString("actor"),
                uuid == null ? null : UUID.fromString(uuid),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                LogAction.fromId(rs.getInt("action")),
                rs.getString("old_data"),
                rs.getString("new_data"),
                rs.getInt("rolled_back") != 0
        );
    }
}
