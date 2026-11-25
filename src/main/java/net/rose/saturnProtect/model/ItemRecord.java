package net.rose.saturnProtect.model;

import java.util.UUID;

/**
 * One item movement. {@code itemData} is the item serialized with {@code ItemStack#serializeAsBytes}
 * (amount 1); it is only filled when writing and when fetching for a rollback, lookups leave it null.
 * {@code container} is the key of the container an add/remove happened in ({@code chest}, {@code double_chest},
 * {@code chest_minecart}...). It is null for drops and pickups and for rows logged before it was recorded.
 */
public record ItemRecord(
        long id,
        long time,
        String actor,
        UUID actorUuid,
        String world,
        int x,
        int y,
        int z,
        ItemAction action,
        String itemType,
        int amount,
        byte[] itemData,
        String container,
        boolean rolledBack
) {

    public static ItemRecord create(String actor, UUID actorUuid, String world, int x, int y, int z,
                                    ItemAction action, String itemType, int amount, byte[] itemData,
                                    String container) {
        return new ItemRecord(0L, System.currentTimeMillis(), actor, actorUuid, world, x, y, z,
                action, itemType, amount, itemData, container, false);
    }
}
