package net.rose.saturnProtect.api;

import net.rose.saturnProtect.model.EventLookupResult;
import net.rose.saturnProtect.model.ItemLookupResult;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.model.LookupResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SaturnProtect's public API. Add SaturnProtect to your {@code softdepend} (or {@code depend}) and fetch it with
 * {@link #get()}.
 *
 * <p><b>Threading.</b> Lookups run on SaturnProtect's database thread and return futures that complete on that
 * thread, so hop back to the main thread ({@code Bukkit.getScheduler().runTask}) before touching the world.
 * Methods that read the world ({@code containerHistory}, the {@code log*} methods that take a {@link Block})
 * must be called on the main thread. Logging itself is queued and written in the background.
 *
 * <p><b>Actors.</b> Pass a player's name for player actions. For your own plugin use a name starting with
 * {@code #}, like {@code #MyPlugin}, which is the convention for non-player actors and can never clash with a
 * real player. Rollbacks and lookups match actors by that name, so {@code u:#MyPlugin} works.
 *
 * <p>The record types in {@code net.rose.saturnProtect.model} are part of this API.
 */
public interface SaturnProtectAPI {

    /** Bumped when this interface changes in a way that breaks callers. */
    int API_VERSION = 1;

    /** @return the API, or null if SaturnProtect is not installed or failed to start */
    static @Nullable SaturnProtectAPI get() {
        RegisteredServiceProvider<SaturnProtectAPI> provider =
                Bukkit.getServicesManager().getRegistration(SaturnProtectAPI.class);
        return provider == null ? null : provider.getProvider();
    }

    int apiVersion();

    /** A builder for the lookups below. */
    default @NotNull LookupQueryBuilder query() {
        return new LookupQueryBuilder();
    }

    // ---- lookups ----------------------------------------------------------------------------------------------

    /** Block changes at one position, newest first. */
    @NotNull CompletableFuture<List<BlockRecord>> blockHistory(@NotNull Block block, int limit);

    /**
     * Item transactions of one container, newest first. Either half of a double chest gives the same history.
     * Main thread only.
     */
    @NotNull CompletableFuture<List<ItemRecord>> containerHistory(@NotNull Block container, int limit);

    /** Block changes plus player kills, newest first, like {@code /sp lookup}. */
    @NotNull CompletableFuture<LookupResult> lookupBlocks(@NotNull LookupQuery query, int offset, int limit);

    /** Container, drop and pickup records, like {@code /sp items}. */
    @NotNull CompletableFuture<ItemLookupResult> lookupItems(@NotNull LookupQuery query, int offset, int limit);

    /** Sessions, chat, commands, kills, signs, item frames and armor stands, like {@code /sp events}. */
    @NotNull CompletableFuture<EventLookupResult> lookupEvents(@NotNull LookupQuery query, int offset, int limit);

    // ---- logging ----------------------------------------------------------------------------------------------

    /**
     * Records a block changing from {@code before} to {@code after}. It counts as a break if {@code after} is
     * air and as a placement otherwise. Nothing is logged if the two are identical, or if the world or the actor
     * is on the ignore list in config.yml.
     *
     * @param actorUuid the actor's UUID if it is a player, otherwise null
     */
    void logBlockChange(@NotNull String actor, @Nullable UUID actorUuid, @NotNull Location location,
                        @NotNull BlockData before, @NotNull BlockData after);

    /**
     * Records items going into or coming out of a container, so rollbacks can restore them.
     * The stack's amount is the amount moved. Main thread only.
     */
    void logContainerChange(@NotNull String actor, @Nullable UUID actorUuid, @NotNull Block container,
                            @NotNull ItemStack item, boolean added);

    // ---- rollbacks --------------------------------------------------------------------------------------------

    /** True while a rollback, restore or undo is running. Only one can run at a time. */
    boolean isRollbackBusy();

    /**
     * Rolls back what the query matches, exactly like {@code /sp rollback}. Progress and the result are sent to
     * {@code requester} ({@code Bukkit.getConsoleSender()} is fine), and the requester can {@code /sp undo}
     * it. Does nothing but tell the requester if {@link #isRollbackBusy()}. Main thread only.
     */
    void rollback(@NotNull CommandSender requester, @NotNull LookupQuery query);

    /** Re-applies rolled back changes, exactly like {@code /sp restore}. Main thread only. */
    void restore(@NotNull CommandSender requester, @NotNull LookupQuery query);
}
