package net.rose.saturnProtect.model;

public enum EventAction {
    LOGIN(0, "logged in", "login"),
    LOGOUT(1, "logged out", "logout"),
    CHAT(2, "said", "chat"),
    COMMAND(3, "ran", "command"),
    KILL(4, "killed", "kill"),
    SIGN(5, "edited a sign", "sign"),
    /** An item frame, glow item frame, painting or armor stand was placed. */
    ENTITY_PLACE(6, "placed", "entity-place"),
    /** An item frame, glow item frame, painting or armor stand was broken. */
    ENTITY_BREAK(7, "broke", "entity-break"),
    /** An item was put in or taken out of an item frame, or put on or taken off an armor stand. */
    ENTITY_ITEM(8, "changed", "entity-item"),
    /** A player opened a chest, barrel, furnace, hopper, minecart chest... what they moved is logged as item actions. */
    CONTAINER_OPEN(9, "opened", "open");

    private final int id;
    private final String verb;
    private final String alias;

    EventAction(int id, String verb, String alias) {
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

    public static EventAction fromId(int id) {
        for (EventAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown event action id: " + id);
    }
}
