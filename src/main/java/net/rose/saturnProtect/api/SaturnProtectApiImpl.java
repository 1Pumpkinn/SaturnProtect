package net.rose.saturnProtect.api;

import net.rose.saturnProtect.core.ContainerUtil;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.core.RollbackService;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.EventLookupResult;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.ItemLookupResult;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LogAction;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.model.LookupResult;
import net.rose.saturnProtect.util.BlockText;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Implementation of {@link SaturnProtectAPI}. Not part of the API, get it through the services manager. */
public final class SaturnProtectApiImpl implements SaturnProtectAPI {

    private final Database database;
    private final LogService logService;
    private final RollbackService rollbacks;

    public SaturnProtectApiImpl(Database database, LogService logService, RollbackService rollbacks) {
        this.database = database;
        this.logService = logService;
        this.rollbacks = rollbacks;
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public @NotNull CompletableFuture<List<BlockRecord>> blockHistory(@NotNull Block block, int limit) {
        return database.blockHistory(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                Math.max(1, limit));
    }

    @Override
    public @NotNull CompletableFuture<List<ItemRecord>> containerHistory(@NotNull Block container, int limit) {
        // double chests are logged under their left half
        Block target = container.getState(false) instanceof Container state
                ? ContainerUtil.locate(state.getInventory())
                : null;
        if (target == null) {
            target = container;
        }
        return database.itemHistory(target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                Math.max(1, limit));
    }

    @Override
    public @NotNull CompletableFuture<LookupResult> lookupBlocks(@NotNull LookupQuery query, int offset, int limit) {
        return database.lookupBlocks(query, Math.max(0, offset), Math.max(1, limit));
    }

    @Override
    public @NotNull CompletableFuture<ItemLookupResult> lookupItems(@NotNull LookupQuery query, int offset, int limit) {
        return database.lookupItems(query, Math.max(0, offset), Math.max(1, limit));
    }

    @Override
    public @NotNull CompletableFuture<EventLookupResult> lookupEvents(@NotNull LookupQuery query, int offset, int limit) {
        return database.lookupEvents(query, Math.max(0, offset), Math.max(1, limit));
    }

    @Override
    public void logBlockChange(@NotNull String actor, @Nullable UUID actorUuid, @NotNull Location location,
                               @NotNull BlockData before, @NotNull BlockData after) {
        requireActor(actor);
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("The location has no world.");
        }
        String afterData = after.getAsString();
        LogAction action = BlockText.isAir(BlockText.name(afterData)) ? LogAction.BREAK : LogAction.PLACE;
        logService.logBlock(actor, actorUuid, location.getBlock(), action, before.getAsString(), afterData);
    }

    @Override
    public void logContainerChange(@NotNull String actor, @Nullable UUID actorUuid, @NotNull Block container,
                                   @NotNull ItemStack item, boolean added) {
        requireActor(actor);
        if (item.getType().isAir()) {
            return;
        }
        ItemStack one = item.asOne();
        logService.logItem(actor, actorUuid, container,
                added ? ItemAction.CONTAINER_ADD : ItemAction.CONTAINER_REMOVE,
                one.getType().getKey().toString(), item.getAmount(), one.serializeAsBytes());
    }

    @Override
    public boolean isRollbackBusy() {
        return rollbacks.isBusy();
    }

    @Override
    public void rollback(@NotNull CommandSender requester, @NotNull LookupQuery query) {
        rollbacks.rollback(requester, query);
    }

    @Override
    public void restore(@NotNull CommandSender requester, @NotNull LookupQuery query) {
        rollbacks.restore(requester, query);
    }

    private static void requireActor(String actor) {
        if (actor.isBlank()) {
            throw new IllegalArgumentException("The actor must not be blank.");
        }
    }
}
