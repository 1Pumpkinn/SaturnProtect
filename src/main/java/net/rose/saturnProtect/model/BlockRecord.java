package net.rose.saturnProtect.model;

import java.util.UUID;

/**
 * One block change. {@code oldData} / {@code newData} are full block data strings
 * (e.g. {@code minecraft:oak_stairs[facing=north]}) so a record can be rolled back or restored.
 */
public record BlockRecord(
        long id,
        long time,
        String actor,
        UUID actorUuid,
        String world,
        int x,
        int y,
        int z,
        LogAction action,
        String oldData,
        String newData,
        boolean rolledBack
) {

    public static BlockRecord create(String actor, UUID actorUuid, String world, int x, int y, int z,
                                     LogAction action, String oldData, String newData) {
        return new BlockRecord(0L, System.currentTimeMillis(), actor, actorUuid, world, x, y, z,
                action, oldData, newData, false);
    }
}
