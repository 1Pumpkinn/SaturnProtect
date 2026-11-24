package net.rose.saturnProtect.model;

import java.util.List;

/**
 * A parsed lookup. Empty lists mean "no filter". {@code actions} apply to block lookups, {@code itemActions}
 * to item lookups; {@code includeBlocks}/{@code excludeBlocks} hold block ids or item types. {@code area} is null for a global search.
 */
public record LookupQuery(
        List<String> users,
        List<LogAction> actions,
        List<ItemAction> itemActions,
        List<String> includeBlocks,
        List<String> excludeBlocks,
        long sinceMillis,
        Area area
) {

    public record Area(String world, int x, int y, int z, int radius) {
    }
}
