package net.rose.saturnProtect.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which players currently have the block inspector toggled on. */
public final class InspectorManager {

    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    /** @return true if the inspector is now enabled for the player */
    public boolean toggle(UUID player) {
        if (active.remove(player)) {
            return false;
        }
        active.add(player);
        return true;
    }

    public boolean isActive(UUID player) {
        return active.contains(player);
    }

    public void remove(UUID player) {
        active.remove(player);
    }
}
