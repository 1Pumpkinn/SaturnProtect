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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses CoreProtect-style parameters: {@code u:Name,Other t:3d r:10 a:break b:stone,dirt e:grass_block m:text}.
 * <p>
 * Keys are optional. A plain word is sorted by what it looks like, in any order: {@code 2h} is a time,
 * {@code 10} or {@code global} a radius, an action name ({@code container}, {@code chat}, {@code kills}...)
 * an action, and anything else a player. So {@code Steve 2h 10}, {@code 2h Steve} and {@code container 1h}
 * all work. A player whose name is also an action word or looks like a time can still be given as
 * {@code u:name}. {@code -} and {@code *} are ignored, they are only kept so older habits keep working.
 */
public final class QueryParser {

    private static final String DEFAULT_TIME = "3d";

    private static final Pattern TIME = Pattern.compile("(?i)(\\d+[wdhms])+");
    private static final Pattern RADIUS = Pattern.compile("(?i)\\d+|global");

    /** Action names that expand to several actions, and alternative spellings of single ones. */
    private static final Map<String, String> ACTION_WORDS = Map.ofEntries(
            Map.entry("container", "add,remove"),
            Map.entry("containers", "add,remove"),
            Map.entry("chest", "add,remove"),
            Map.entry("chests", "add,remove"),
            Map.entry("cont", "add,remove"),
            Map.entry("session", "login,logout"),
            Map.entry("sessions", "login,logout"),
            Map.entry("frame", "entity-place,entity-break,entity-item"),
            Map.entry("frames", "entity-place,entity-break,entity-item"),
            Map.entry("breaks", "break"),
            Map.entry("broke", "break"),
            Map.entry("places", "place"),
            Map.entry("placed", "place"),
            Map.entry("adds", "add"),
            Map.entry("added", "add"),
            Map.entry("removes", "remove"),
            Map.entry("removed", "remove"),
            Map.entry("take", "remove"),
            Map.entry("took", "remove"),
            Map.entry("drops", "drop"),
            Map.entry("dropped", "drop"),
            Map.entry("pickups", "pickup"),
            Map.entry("opens", "open"),
            Map.entry("opened", "open"),
            Map.entry("kills", "kill"),
            Map.entry("killed", "kill"),
            Map.entry("signs", "sign"),
            Map.entry("commands", "command"),
            Map.entry("cmd", "command"),
            Map.entry("cmds", "command"),
            Map.entry("chats", "chat"),
            Map.entry("say", "chat"),
            Map.entry("messages", "chat"),
            Map.entry("join", "login"),
            Map.entry("joins", "login"),
            Map.entry("logins", "login"),
            Map.entry("leave", "logout"),
            Map.entry("quit", "logout"),
            Map.entry("logouts", "logout"));

    /** What a plain (key-less) word stands for. */
    enum Slot {
        SKIP, ACTION, TIME, RADIUS, USER
    }

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
            String key;
            String value;
            if (colon < 0) {
                Slot slot = classify(token);
                if (slot == Slot.SKIP) {
                    continue;
                }
                key = switch (slot) {
                    case ACTION -> "a";
                    case TIME -> "t";
                    case RADIUS -> "r";
                    default -> "u";
                };
                if ((slot == Slot.TIME && time != null) || (slot == Slot.RADIUS && radius != null)) {
                    throw new IllegalArgumentException("'" + token + "' is a second "
                            + (slot == Slot.TIME ? "time" : "radius") + ". If it is a player name, write u:" + token);
                }
                value = token;
            } else if (colon == 0 || colon == token.length() - 1) {
                throw new IllegalArgumentException("Invalid parameter '" + token + "'. Use plain words like Steve 2h 10 container, or key:value like a:break");
            } else {
                key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                value = token.substring(colon + 1);
            }

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

    /** Sorts a plain word into what it stands for: time, radius, action or player. */
    static Slot classify(String token) {
        if (token.equals("-") || token.equals("*")) {
            return Slot.SKIP;
        }
        if (resolveAction(token) != null) {
            return Slot.ACTION;
        }
        if (TIME.matcher(token).matches()) {
            return Slot.TIME;
        }
        if (RADIUS.matcher(token).matches()) {
            return Slot.RADIUS;
        }
        return Slot.USER;
    }

    /** True if {@code word} names an action, in any of its spellings. */
    static boolean isActionWord(String word) {
        return resolveAction(word) != null;
    }

    /** The plain action names a word stands for ({@code container} is add and remove), or null if it is none. */
    private static List<String> resolveAction(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        String mapped = ACTION_WORDS.get(lower);
        if (mapped != null) {
            return List.of(mapped.split(","));
        }
        for (LogAction action : LogAction.values()) {
            if (action.name().equalsIgnoreCase(lower)) {
                return List.of(lower);
            }
        }
        for (ItemAction action : ItemAction.values()) {
            if (action.alias().equalsIgnoreCase(lower)) {
                return List.of(lower);
            }
        }
        for (EventAction action : EventAction.values()) {
            if (action.alias().equalsIgnoreCase(lower)) {
                return List.of(lower);
            }
        }
        return null;
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
        List<String> resolved = resolveAction(name);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown action '" + name + "'. Valid: break, place, container, add, remove, drop, pickup, "
                    + "open, login, logout, chat, command, kill, sign, entity-place, entity-break, entity-item");
        }
        for (String single : resolved) {
            for (LogAction action : LogAction.values()) {
                if (action.name().equalsIgnoreCase(single)) {
                    blockActions.add(action);
                }
            }
            for (ItemAction action : ItemAction.values()) {
                if (action.alias().equalsIgnoreCase(single)) {
                    itemActions.add(action);
                }
            }
            for (EventAction action : EventAction.values()) {
                if (action.alias().equalsIgnoreCase(single)) {
                    eventActions.add(action);
                }
            }
        }
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
