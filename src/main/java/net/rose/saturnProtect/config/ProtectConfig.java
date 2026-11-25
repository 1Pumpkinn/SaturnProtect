package net.rose.saturnProtect.config;

import net.rose.saturnProtect.util.TimeParser;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Typed view over config.yml. Can be reloaded in place with {@link #reload(FileConfiguration)}, so everything
 * that holds a reference to it sees new values without being rebuilt. Fields are volatile because the
 * database thread and the main thread both read them.
 */
public final class ProtectConfig {

    private static final long DEFAULT_PURGE_MIN_AGE = 86_400_000L;

    private volatile FileConfiguration config;
    private volatile Set<String> ignoredWorlds;
    private volatile Set<String> ignoredUsers;
    private volatile Set<String> ignoredCommands;

    public ProtectConfig(FileConfiguration config) {
        load(config);
    }

    /** Swaps in freshly loaded configuration. */
    public void reload(FileConfiguration fresh) {
        load(fresh);
    }

    private void load(FileConfiguration source) {
        this.ignoredWorlds = lower(source.getStringList("ignore.worlds"));
        this.ignoredUsers = lower(source.getStringList("ignore.users"));
        this.ignoredCommands = lower(source.getStringList("logging.ignored-commands"));
        this.config = source;
    }

    private static Set<String> lower(List<String> input) {
        return input.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public String databaseFile() {
        return config.getString("database.file", "data.db");
    }

    public long flushIntervalMs() {
        return Math.max(100L, config.getLong("writer.flush-interval-ms", 1000L));
    }

    public int batchSize() {
        return Math.max(1, config.getInt("writer.batch-size", 500));
    }

    public int inspectLimit() {
        return Math.max(1, config.getInt("inspector.max-results", 10));
    }

    /** Most entries the inspector dialog shows for one spot. A dialog scrolls, so this can be higher than chat. */
    public int inspectDialogLimit() {
        return Math.max(1, config.getInt("inspector.dialog-max-results", 40));
    }

    /** Show the inspector's result as a dialog with the full history of the spot instead of chat lines. */
    public boolean dialogInspect() {
        return config.getBoolean("ui.dialog-inspect", true);
    }

    public int pageSize() {
        return Math.max(1, config.getInt("lookup.page-size", 10));
    }

    /** Show lookup results to players as a paged dialog instead of chat lines. The console always gets chat. */
    public boolean dialogLogs() {
        return config.getBoolean("ui.dialog-logs", true);
    }

    public int defaultRadius() {
        return Math.max(1, config.getInt("lookup.default-radius", 10));
    }

    public int nearRadius() {
        return Math.max(1, config.getInt("lookup.near-radius", 5));
    }

    public int maxRadius() {
        return Math.max(1, config.getInt("lookup.max-radius", 100));
    }

    public int rollbackMaxRecords() {
        return Math.max(1, config.getInt("rollback.max-records", 250_000));
    }

    public int rollbackBlocksPerTick() {
        return Math.max(1, config.getInt("rollback.blocks-per-tick", 500));
    }

    public boolean logBlockBreak() {
        return config.getBoolean("logging.block-break", true);
    }

    public boolean logBlockPlace() {
        return config.getBoolean("logging.block-place", true);
    }

    public boolean logBuckets() {
        return config.getBoolean("logging.buckets", true);
    }

    public boolean logContainers() {
        return config.getBoolean("logging.containers", true);
    }

    public boolean logContainerOpens() {
        return config.getBoolean("logging.container-opens", true);
    }

    public boolean logItemDrops() {
        return config.getBoolean("logging.item-drops", true);
    }

    public boolean logItemPickups() {
        return config.getBoolean("logging.item-pickups", false);
    }

    public boolean logKills() {
        return config.getBoolean("logging.kills", true);
    }

    public boolean logPlayerDeaths() {
        return config.getBoolean("logging.player-deaths", true);
    }

    public boolean logSessions() {
        return config.getBoolean("logging.sessions", true);
    }

    public boolean logChat() {
        return config.getBoolean("logging.chat", true);
    }

    public boolean logCommands() {
        return config.getBoolean("logging.commands", true);
    }

    public boolean logSigns() {
        return config.getBoolean("logging.sign-edits", true);
    }

    public boolean logExplosions() {
        return config.getBoolean("logging.explosions", true);
    }

    public boolean logFireBurn() {
        return config.getBoolean("logging.fire-burn", true);
    }

    public boolean logFireIgnite() {
        return config.getBoolean("logging.fire-ignite", true);
    }

    public boolean logFluidReplace() {
        return config.getBoolean("logging.fluid-replace", true);
    }

    public boolean logFluidFlow() {
        return config.getBoolean("logging.fluid-flow", false);
    }

    public boolean logTreeGrowth() {
        return config.getBoolean("logging.tree-growth", true);
    }

    public boolean logPistons() {
        return config.getBoolean("logging.pistons", true);
    }

    public boolean logEntityBlockChanges() {
        return config.getBoolean("logging.entity-block-changes", true);
    }

    public boolean logItemFrames() {
        return config.getBoolean("logging.item-frames", true);
    }

    public boolean logArmorStands() {
        return config.getBoolean("logging.armor-stands", true);
    }

    /** Newest data {@code /sp purge} is allowed to delete, as an age. Falls back to 24 hours if unreadable. */
    public long purgeMinAgeMillis() {
        try {
            return TimeParser.parseMillis(config.getString("purge.minimum-age", "24h"));
        } catch (IllegalArgumentException ex) {
            return DEFAULT_PURGE_MIN_AGE;
        }
    }

    public int lavaAttributionRadius() {
        return Math.max(1, config.getInt("logging.lava-attribution.radius", 10));
    }

    public long lavaAttributionMillis() {
        return Math.max(1L, config.getLong("logging.lava-attribution.minutes", 30L)) * 60_000L;
    }

    public boolean rollbackContainers() {
        return config.getBoolean("rollback.containers", true);
    }

    /** @param label the command name without slash or namespace, any case */
    public boolean isCommandIgnored(String label) {
        return ignoredCommands.contains(label.toLowerCase(Locale.ROOT));
    }

    public boolean isWorldIgnored(String world) {
        return ignoredWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    public boolean isUserIgnored(String user) {
        return ignoredUsers.contains(user.toLowerCase(Locale.ROOT));
    }
}
