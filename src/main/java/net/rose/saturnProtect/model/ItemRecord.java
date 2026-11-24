package net.rose.saturnProtect.model;

import java.util.UUID;

/**
 * One item movement. {@code itemData} is the item serialized with {@code ItemStack#serializeAsBytes}
 * (amount 1); it is only filled when writing, lookups leave it null.
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
        byte[] itemData
) {

    public static ItemRecord create(String actor, UUID actorUuid, String world, int x, int y, int z,
                                    ItemAction action, String itemType, int amount, byte[] itemData) {
        return new ItemRecord(0L, System.currentTimeMillis(), actor, actorUuid, world, x, y, z,
                action, itemType, amount, itemData);
    }
}
