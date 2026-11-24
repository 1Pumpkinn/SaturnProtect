package net.rose.saturnProtect.model;

public enum ItemAction {
    CONTAINER_ADD(0, "added", "add"),
    CONTAINER_REMOVE(1, "removed", "remove"),
    DROP(2, "dropped", "drop"),
    PICKUP(3, "picked up", "pickup");

    private final int id;
    private final String verb;
    private final String alias;

    ItemAction(int id, String verb, String alias) {
        this.id = id;
        this.verb = verb;
        this.alias = alias;
    }

    public int id() {
        return id;
    }

    public String verb() {
        return verb;
    }

    /** Name used in {@code a:} lookup parameters. */
    public String alias() {
        return alias;
    }

    public static ItemAction fromId(int id) {
        for (ItemAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown item action id: " + id);
    }
}
