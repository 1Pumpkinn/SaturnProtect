package net.rose.saturnProtect.model;

import java.util.List;

/**
 * Everything logged at one spot, for the inspector dialog: block changes, items put in or taken out of a
 * container, and events tied to the spot (container opens, sign edits, item frames and armor stands). Each list
 * is newest first.
 */
public record PositionHistory(List<BlockRecord> blocks, List<ItemRecord> items, List<EventRecord> events) {
}
