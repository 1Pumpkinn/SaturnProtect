package net.rose.saturnProtect.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class ProtectConfig {

    private final FileConfiguration config;
    private final Set<String> ignoredWorlds;
    private final Set<String> ignoredUsers;
    private final Set<String> ignoredCommands;

    public ProtectConfig(FileConfiguration config) {
        this.config = config;
        this.ignoredWorlds = lower(config.getStringList("ignore.worlds"));
        this.ignoredUsers = lower(config.getStringList("ignore.users"));
        this.ignoredCommands = lower(config.getStringList("logging.ignored-commands"));
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

    public int pageSize() {
        return Math.max(1, config.getInt("lookup.page-size", 10));
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
