package net.rose.saturnProtect.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.LogAction;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tab completion for the {@code key:value} parameters of {@code /sp lookup}, {@code items}, {@code events},
 * {@code rollback}, {@code restore} and {@code purge}. Those commands take one greedy string, so Brigadier
 * can't complete it on its own: this works out which space separated token the cursor is in and completes only
 * that token.
 * <p>
 * Pressing tab on an empty token offers the keys that are still unused ({@code u:}, {@code t:}, ...), and
 * pressing it after a key offers values: players, time presets, radii, actions, blocks. Comma lists
 * ({@code u:Steve,Al<tab>}) complete the segment after the last comma and skip values already in the list.
 */
public final class ParamSuggester {

    /** Which command is asking, since each accepts different keys and actions. */
    public enum Kind {
        BLOCKS, ITEMS, EVENTS, ROLLBACK, PURGE
    }

    private static final List<String> TIME_PRESETS = List.of("30s", "5m", "30m", "1h", "6h", "12h", "1d", "3d", "1w", "2w", "30d");
    private static final List<String> TIME_UNITS = List.of("s", "m", "h", "d", "w");
    private static final List<Integer> RADIUS_PRESETS = List.of(5, 10, 20, 50, 100, 250, 500);
    private static final int MAX_PLAYER_SUGGESTIONS = 100;

    private static volatile List<String> blockNames;
    private static volatile List<String> itemNames;
    private static volatile List<String> entityNames;

    private ParamSuggester() {
    }

    public static SuggestionProvider<CommandSourceStack> provider(ProtectConfig config, Kind kind) {
        return (context, builder) -> suggest(config, kind, builder).buildFuture();
    }

    // ------------------------------------------------------------------ token handling

    /** @return the builder holding the suggestions: an offset copy, so it is that one that must be built */
    private static SuggestionsBuilder suggest(ProtectConfig config, Kind kind, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int space = remaining.lastIndexOf(' ');
        String finished = remaining.substring(0, space + 1);
        String token = remaining.substring(space + 1);
        // from here on suggestions replace only the token being typed, not the whole argument
        SuggestionsBuilder out = builder.createOffset(builder.getStart() + space + 1);

        int colon = token.indexOf(':');
        if (colon < 0) {
            suggestKeys(kind, finished, token, out);
        } else {
            suggestValues(config, kind, token, colon, out);
        }
        return out;
    }

    private static void suggestKeys(Kind kind, String finished, String token, SuggestionsBuilder out) {
        Set<String> used = new HashSet<>();
        for (String done : finished.trim().split("\\s+")) {
            if (done.isEmpty()) {
                continue;
            }
            int colon = done.indexOf(':');
            used.add(colon > 0 ? canonicalKey(done.substring(0, colon)) : done.toLowerCase(Locale.ROOT));
        }

        String lower = token.toLowerCase(Locale.ROOT);
        for (String key : keysFor(kind)) {
            // plain words (vacuum, confirm) are offered as they are, parameters get their colon
            boolean word = key.length() > 1;
            if (used.contains(key) || !key.startsWith(lower)) {
                continue;
            }
            out.suggest(word ? key : key + ":");
        }
    }

    private static List<String> keysFor(Kind kind) {
        return switch (kind) {
            case BLOCKS, ITEMS, ROLLBACK -> List.of("u", "t", "r", "a", "b", "e");
            case EVENTS -> List.of("u", "t", "r", "a", "b", "e", "m");
            case PURGE -> List.of("t", "vacuum", "confirm");
        };
    }

    private static void suggestValues(ProtectConfig config, Kind kind, String token, int colon,
                                      SuggestionsBuilder out) {
        String key = canonicalKey(token.substring(0, colon));
        String value = token.substring(colon + 1);

        boolean list = key.equals("u") || key.equals("a") || key.equals("b") || key.equals("e");
        int lastComma = list ? value.lastIndexOf(',') : -1;
        String prefix = token.substring(0, colon + 1 + lastComma + 1);
        String segment = value.substring(lastComma + 1).toLowerCase(Locale.ROOT);

        Set<String> chosen = new HashSet<>();
        if (lastComma >= 0) {
            for (String part : value.substring(0, lastComma).split(",")) {
                chosen.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }

        for (String candidate : candidates(config, kind, key, segment)) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.startsWith(segment) && !lower.equals(segment) && !chosen.contains(lower)) {
                out.suggest(prefix + candidate);
            }
        }
    }

    private static List<String> candidates(ProtectConfig config, Kind kind, String key, String segment) {
        return switch (key) {
            case "u" -> players(segment);
            case "t" -> times(segment);
            case "r" -> kind == Kind.PURGE ? List.of() : radii(config);
            case "a" -> actions(kind);
            case "b", "e" -> kind == Kind.EVENTS ? entities() : blocksOrItems(kind);
            default -> List.of();
        };
    }

    /** {@code user} and {@code u} are the same parameter, so a key typed either way counts as used. */
    private static String canonicalKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "user" -> "u";
            case "time" -> "t";
            case "radius" -> "r";
            case "action" -> "a";
            case "include" -> "b";
            case "exclude" -> "e";
            case "msg", "text" -> "m";
            default -> key.toLowerCase(Locale.ROOT);
        };
    }

    // ------------------------------------------------------------------ value sources

    /** Online players first, then everyone who has played before once at least one letter is typed. */
    private static List<String> players(String segment) {
        Set<String> names = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        if (!segment.isEmpty()) {
            String lower = segment.toLowerCase(Locale.ROOT);
            Set<String> offline = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                String name = player.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    offline.add(name);
                    if (offline.size() >= MAX_PLAYER_SUGGESTIONS) {
                        break;
                    }
                }
            }
            names.addAll(offline);
        }
        return new ArrayList<>(names);
    }

    /** Presets when nothing is typed, then the unit letters once a number is there: {@code 5<tab>} gives 5s 5m 5h... */
    private static List<String> times(String segment) {
        if (segment.isEmpty()) {
            return TIME_PRESETS;
        }
        if (Character.isDigit(segment.charAt(segment.length() - 1))) {
            List<String> withUnits = new ArrayList<>();
            for (String unit : TIME_UNITS) {
                withUnits.add(segment + unit);
            }
            return withUnits;
        }
        return TIME_PRESETS;
    }

    private static List<String> radii(ProtectConfig config) {
        int max = Math.max(1, config.maxRadius());
        Set<Integer> numbers = new TreeSet<>(RADIUS_PRESETS);
        numbers.add(config.defaultRadius());
        numbers.add(max);
        List<String> out = new ArrayList<>();
        for (int number : numbers) {
            if (number >= 1 && number <= max) {
                out.add(String.valueOf(number));
            }
        }
        out.add("global");
        return out;
    }

    private static List<String> actions(Kind kind) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case BLOCKS -> {
                for (LogAction action : LogAction.values()) {
                    out.add(action.name().toLowerCase(Locale.ROOT));
                }
                out.add(EventAction.KILL.alias());
            }
            case ITEMS -> {
                for (ItemAction action : ItemAction.values()) {
                    out.add(action.alias());
                }
            }
            case EVENTS -> {
                for (EventAction action : EventAction.values()) {
                    out.add(action.alias());
                }
            }
            case ROLLBACK -> {
                for (LogAction action : LogAction.values()) {
                    out.add(action.name().toLowerCase(Locale.ROOT));
                }
                out.add(ItemAction.CONTAINER_ADD.alias());
                out.add(ItemAction.CONTAINER_REMOVE.alias());
            }
            case PURGE -> {
            }
        }
        return out;
    }

    private static List<String> blocksOrItems(Kind kind) {
        if (kind == Kind.PURGE) {
            return List.of();
        }
        if (kind == Kind.BLOCKS) {
            return blockNames();
        }
        // items and rollbacks (which also restore container contents) can name any item
        return itemNames();
    }

    private static List<String> blockNames() {
        List<String> names = blockNames;
        if (names == null) {
            names = Arrays.stream(Material.values())
                    .filter(material -> !material.name().startsWith("LEGACY_"))
                    .filter(Material::isBlock)
                    .filter(material -> !material.isAir())
                    .map(material -> material.name().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList();
            blockNames = names;
        }
        return names;
    }

    private static List<String> itemNames() {
        List<String> names = itemNames;
        if (names == null) {
            names = Arrays.stream(Material.values())
                    .filter(material -> !material.name().startsWith("LEGACY_"))
                    .filter(material -> material.isItem() || material.isBlock())
                    .filter(material -> !material.isAir())
                    .map(material -> material.name().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList();
            itemNames = names;
        }
        return names;
    }

    private static List<String> entities() {
        List<String> names = entityNames;
        if (names == null) {
            names = Arrays.stream(EntityType.values())
                    .filter(type -> type != EntityType.UNKNOWN)
                    .map(type -> type.name().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList();
            entityNames = names;
        }
        return names;
    }
}
