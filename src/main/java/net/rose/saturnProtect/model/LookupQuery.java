package net.rose.saturnProtect.model;

import java.util.List;

/**
 * A parsed lookup. Empty lists mean "no filter". {@code actions} apply to block lookups, {@code itemActions}
 * to item lookups and {@code eventActions} to event lookups; {@code includeBlocks}/{@code excludeBlocks} hold
 * block ids, item types or victim entity types. {@code text} is a case-insensitive substring of an event's
 * subject (chat message, command, victim, sign text) and is null when not given. {@code area} is null for a
 * global search.
 */
public record LookupQuery(
        List<String> users,
        List<LogAction> actions,
        List<ItemAction> itemActions,
        List<EventAction> eventActions,
        List<String> includeBlocks,
        List<String> excludeBlocks,
        String text,
        long sinceMillis,
        Area area
) {

    public record Area(String world, int x, int y, int z, int radius) {
    }
}
