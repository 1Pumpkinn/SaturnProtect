package net.rose.saturnProtect.db;

import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.ItemLookupResult;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LogAction;
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

    private static final String INSERT_BLOCK = """
            INSERT INTO sp_block (time, actor, actor_uuid, world, x, y, z, action, old_data, new_data, rolled_back)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""";

    private static final String INSERT_ITEM = """
            INSERT INTO sp_item (time, actor, actor_uuid, world, x, y, z, action, item_type, amount, item_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_ITEM_COLUMNS =
            "SELECT id, time, actor, actor_uuid, world, x, y, z, action, item_type, amount FROM sp_item";

    private static final String SELECT_BLOCK_COLUMNS =
            "SELECT id, time, actor, actor_uuid, world, x, y, z, action, old_data, new_data, rolled_back FROM sp_block";

    public record Stats(long blockRows, long itemRows, int queued, long fileBytes) {
    }

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final Path file;
    private final ConcurrentLinkedQueue<BlockRecord> pendingBlocks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ItemRecord> pendingItems = new ConcurrentLinkedQueue<>();
    private final ScheduledThreadPoolExecutor worker;
    private Connection connection;

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
                        item_data BLOB
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_item_pos ON sp_item (world, x, y, z, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_item_time ON sp_item (time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_item_actor ON sp_item (actor, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_block_pos ON sp_block (world, x, y, z, time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_block_time ON sp_block (time)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sp_block_actor ON sp_block (actor, time)");
        }

        long interval = config.flushIntervalMs();
        worker.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Queue a block record; it is written asynchronously. Safe to call from any thread. */
    public void queue(BlockRecord record) {
        pendingBlocks.add(record);
    }

    /** Queue an item record; it is written asynchronously. Safe to call from any thread. */
    public void queue(ItemRecord record) {
        pendingItems.add(record);
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
                        records.add(mapItem(rs));
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
                                records.add(mapItem(rs));
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

    public CompletableFuture<LookupResult> lookupBlocks(LookupQuery query, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flushPending();
            Where where = buildWhere(query, null);
            try {
                long total;
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sp_block" + where.sql())) {
                    bind(count, where.params(), 1);
                    try (ResultSet rs = count.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0L;
                    }
                }

                List<BlockRecord> records = new ArrayList<>();
                if (total > 0 && offset < total) {
                    String sql = SELECT_BLOCK_COLUMNS + where.sql() + " ORDER BY time DESC, id DESC LIMIT ? OFFSET ?";
                    try (PreparedStatement select = connection.prepareStatement(sql)) {
                        int next = bind(select, where.params(), 1);
                        select.setInt(next, limit);
                        select.setInt(next + 1, offset);
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next()) {
                                records.add(mapBlock(rs));
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
        return CompletableFuture.runAsync(() -> {
            flushPending();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sp_block SET rolled_back = ? WHERE id = ?")) {
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
                long size = Files.exists(file) ? Files.size(file) : 0L;
                return new Stats(rows, itemRows, pendingBlocks.size() + pendingItems.size(), size);
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

    private static ItemRecord mapItem(ResultSet rs) throws SQLException {
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
                null
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
