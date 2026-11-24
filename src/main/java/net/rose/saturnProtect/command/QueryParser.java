package net.rose.saturnProtect.command;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.LogAction;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.TimeParser;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses CoreProtect-style parameters: {@code u:Name,Other t:3d r:10 a:break b:stone,dirt e:grass_block m:text}.
 */
public final class QueryParser {

    private static final String DEFAULT_TIME = "3d";

    private QueryParser() {
    }

    /** @throws IllegalArgumentException with a player-readable message */
    public static LookupQuery parse(CommandSender sender, String input, ProtectConfig config) {
        return parse(sender, input, config, false);
    }

    /** @param requireTime when true (rollbacks), an explicit {@code t:} must be given */
    public static LookupQuery parse(CommandSender sender, String input, ProtectConfig config, boolean requireTime) {
        List<String> users = new ArrayList<>();
        List<LogAction> actions = new ArrayList<>();
        List<ItemAction> itemActions = new ArrayList<>();
        List<EventAction> eventActions = new ArrayList<>();
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        String time = null;
        String radius = null;
        String text = null;

        for (String token : input.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                throw new IllegalArgumentException("Invalid parameter '" + token + "'. Use key:value, e.g. u:Steve t:2d r:10");
            }
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1);

            switch (key) {
                case "u", "user" -> users.addAll(split(value));
                case "t", "time" -> time = value;
                case "r", "radius" -> radius = value;
                case "a", "action" -> {
                    for (String name : split(value)) {
                        addAction(name, actions, itemActions, eventActions);
                    }
                }
                case "b", "include" -> include.addAll(normalizeBlocks(value));
                case "e", "exclude" -> exclude.addAll(normalizeBlocks(value));
                case "m", "msg", "text" -> text = value;
                default -> throw new IllegalArgumentException("Unknown parameter '" + key + "'. Valid: u, t, r, a, b, e, m");
            }
        }

        if (time == null) {
            if (requireTime) {
                throw new IllegalArgumentException("A time is required here, e.g. t:3d");
            }
            time = DEFAULT_TIME;
        }
        long since = System.currentTimeMillis() - TimeParser.parseMillis(time);

        LookupQuery.Area area = null;
        if (radius != null) {
            if (!radius.equalsIgnoreCase("global")) {
                area = areaAround(sender, parseRadius(radius, config), config);
            }
        } else if (sender instanceof Player) {
            area = areaAround(sender, config.defaultRadius(), config);
        }

        boolean hasFilter = !users.isEmpty() || !actions.isEmpty() || !itemActions.isEmpty() || !eventActions.isEmpty()
                || !include.isEmpty() || text != null || area != null;
        if (!hasFilter) {
            throw new IllegalArgumentException("Add at least one filter: u:, r:, a:, b: or m:");
        }
        return new LookupQuery(List.copyOf(users), List.copyOf(actions), List.copyOf(itemActions),
                List.copyOf(eventActions), List.copyOf(include), List.copyOf(exclude), text, since, area);
    }

    public static LookupQuery.Area areaAround(CommandSender sender, int radius, ProtectConfig config) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("Console must use r:global or filter by user/block.");
        }
        Location location = player.getLocation();
        return new LookupQuery.Area(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), radius);
    }

    private static int parseRadius(String value, ProtectConfig config) {
        int radius;
        try {
            radius = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Radius must be a number or 'global': " + value);
        }
        if (radius < 1 || radius > config.maxRadius()) {
            throw new IllegalArgumentException("Radius must be between 1 and " + config.maxRadius() + ".");
        }
        return radius;
    }

    private static void addAction(String name, List<LogAction> blockActions, List<ItemAction> itemActions,
                                  List<EventAction> eventActions) {
        for (LogAction action : LogAction.values()) {
            if (action.name().equalsIgnoreCase(name)) {
                blockActions.add(action);
                return;
            }
        }
        for (ItemAction action : ItemAction.values()) {
            if (action.alias().equalsIgnoreCase(name)) {
                itemActions.add(action);
                return;
            }
        }
        for (EventAction action : EventAction.values()) {
            if (action.alias().equalsIgnoreCase(name)) {
                eventActions.add(action);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown action '" + name + "'. Valid: break, place, add, remove, drop, pickup, "
                + "login, logout, chat, command, kill, sign");
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static List<String> normalizeBlocks(String value) {
        List<String> blocks = new ArrayList<>();
        for (String part : split(value)) {
            String id = part.toLowerCase(Locale.ROOT);
            blocks.add(id.contains(":") ? id : "minecraft:" + id);
        }
        return blocks;
    }
}
