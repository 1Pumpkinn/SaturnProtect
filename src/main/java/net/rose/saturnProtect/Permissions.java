package net.rose.saturnProtect;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

public final class Permissions {

    public static final String USE = "saturnprotect.use";
    public static final String INSPECT = "saturnprotect.inspect";
    public static final String STATUS = "saturnprotect.status";
    public static final String LOOKUP = "saturnprotect.lookup";
    public static final String ROLLBACK = "saturnprotect.rollback";

    private Permissions() {
    }

    public static void register() {
        PluginManager manager = Bukkit.getPluginManager();
        add(manager, USE, "Use /sp and view help");
        add(manager, INSPECT, "Use the block inspector");
        add(manager, STATUS, "View SaturnProtect database status");
        add(manager, LOOKUP, "Search logged changes");
        add(manager, ROLLBACK, "Roll back, restore and undo changes");
    }

    private static void add(PluginManager manager, String node, String description) {
        if (manager.getPermission(node) == null) {
            manager.addPermission(new Permission(node, description, PermissionDefault.OP));
        }
    }
}
