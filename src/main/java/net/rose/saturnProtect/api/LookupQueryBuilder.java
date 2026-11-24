package net.rose.saturnProtect.api;

import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.ItemAction;
import net.rose.saturnProtect.model.LogAction;
import net.rose.saturnProtect.model.LookupQuery;
import org.bukkit.Location;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a {@link LookupQuery}. Every filter is optional and filters of the same kind are OR-ed together, the same
 * as the comma separated values in {@code /sp lookup}. {@link #build()} refuses a query with no filter at all.
 *
 * <pre>{@code
 * LookupQuery query = api.query()
 *         .user("Steve")
 *         .block(Material.TNT)
 *         .since(Duration.ofHours(2))
 *         .around(player.getLocation(), 20)
 *         .build();
 * }</pre>
 */
public final class LookupQueryBuilder {

    private final List<String> users = new ArrayList<>();
    private final List<LogAction> actions = new ArrayList<>();
    private final List<ItemAction> itemActions = new ArrayList<>();
    private final List<EventAction> eventActions = new ArrayList<>();
    private final List<String> include = new ArrayList<>();
    private final List<String> exclude = new ArrayList<>();
    private String text;
    private long sinceMillis = -1L;
    private LookupQuery.Area area;

    LookupQueryBuilder() {
    }

    public @NotNull LookupQueryBuilder user(@NotNull String name) {
        users.add(name);
        return this;
    }

    public @NotNull LookupQueryBuilder action(@NotNull LogAction action) {
        actions.add(action);
        return this;
    }

    public @NotNull LookupQueryBuilder action(@NotNull ItemAction action) {
        itemActions.add(action);
        return this;
    }

    public @NotNull LookupQueryBuilder action(@NotNull EventAction action) {
        eventActions.add(action);
        return this;
    }

    /** Only records involving this block, item or victim entity type, e.g. {@code minecraft:tnt}. */
    public @NotNull LookupQueryBuilder include(@NotNull String id) {
        include.add(normalize(id));
        return this;
    }

    public @NotNull LookupQueryBuilder block(@NotNull Material material) {
        include.add(material.getKey().toString());
        return this;
    }

    /** Leaves out records involving this block, item or entity type. */
    public @NotNull LookupQueryBuilder exclude(@NotNull String id) {
        exclude.add(normalize(id));
        return this;
    }

    public @NotNull LookupQueryBuilder exclude(@NotNull Material material) {
        exclude.add(material.getKey().toString());
        return this;
    }

    /** Case-insensitive text inside a chat message, command, victim or sign (events only). */
    public @NotNull LookupQueryBuilder text(@Nullable String text) {
        this.text = text;
        return this;
    }

    /** Only records newer than this long ago. Defaults to 3 days like the commands. */
    public @NotNull LookupQueryBuilder since(@NotNull Duration age) {
        this.sinceMillis = System.currentTimeMillis() - age.toMillis();
        return this;
    }

    /** Only records inside the cube of {@code radius} blocks around a location. */
    public @NotNull LookupQueryBuilder around(@NotNull Location location, int radius) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("The location has no world.");
        }
        if (radius < 1) {
            throw new IllegalArgumentException("Radius must be at least 1.");
        }
        this.area = new LookupQuery.Area(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), radius);
        return this;
    }

    /** @throws IllegalStateException if no filter was added, which would match the whole database */
    public @NotNull LookupQuery build() {
        boolean filtered = !users.isEmpty() || !actions.isEmpty() || !itemActions.isEmpty() || !eventActions.isEmpty()
                || !include.isEmpty() || text != null || area != null;
        if (!filtered) {
            throw new IllegalStateException("Add at least one filter: user, action, include/block, text or around.");
        }
        long since = sinceMillis >= 0L ? sinceMillis : System.currentTimeMillis() - Duration.ofDays(3).toMillis();
        return new LookupQuery(List.copyOf(users), List.copyOf(actions), List.copyOf(itemActions),
                List.copyOf(eventActions), List.copyOf(include), List.copyOf(exclude), text, since, area);
    }

    private static String normalize(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }
}
