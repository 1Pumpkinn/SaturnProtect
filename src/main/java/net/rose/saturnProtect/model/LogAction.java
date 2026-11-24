package net.rose.saturnProtect.model;

public enum LogAction {
    BREAK(0, "broke"),
    PLACE(1, "placed");

    private final int id;
    private final String verb;

    LogAction(int id, String verb) {
        this.id = id;
        this.verb = verb;
    }

    public int id() {
        return id;
    }

    public String verb() {
        return verb;
    }

    public static LogAction fromId(int id) {
        for (LogAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown log action id: " + id);
    }

    public static LogAction fromName(String name) {
        for (LogAction action : values()) {
            if (action.name().equalsIgnoreCase(name)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown action '" + name + "'. Valid: break, place");
    }
}
