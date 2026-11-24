package net.rose.saturnProtect.model;

public enum EventAction {
    LOGIN(0, "logged in", "login"),
    LOGOUT(1, "logged out", "logout"),
    CHAT(2, "said", "chat"),
    COMMAND(3, "ran", "command"),
    KILL(4, "killed", "kill"),
    SIGN(5, "edited a sign", "sign");

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
