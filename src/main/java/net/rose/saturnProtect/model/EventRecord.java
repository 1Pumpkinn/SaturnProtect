package net.rose.saturnProtect.model;

import java.util.UUID;

/**
 * One non-block event. {@code subject} is the victim for kills, the message for chat and commands
 * and the new text for sign edits. {@code detail} is the victim entity type for kills and the old text
 * for sign edits. Both are empty or null when they do not apply.
 */
public record EventRecord(
        long id,
        long time,
        String actor,
        UUID actorUuid,
        String world,
        int x,
        int y,
        int z,
        EventAction action,
        String subject,
        String detail
) implements LookupEntry {

    public static EventRecord create(String actor, UUID actorUuid, String world, int x, int y, int z,
                                     EventAction action, String subject, String detail) {
        return new EventRecord(0L, System.currentTimeMillis(), actor, actorUuid, world, x, y, z,
                action, subject, detail);
    }
}
