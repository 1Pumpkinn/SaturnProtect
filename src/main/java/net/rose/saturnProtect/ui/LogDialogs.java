package net.rose.saturnProtect.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.core.LookupService;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.EventLookupResult;
import net.rose.saturnProtect.model.EventRecord;
import net.rose.saturnProtect.model.ItemLookupResult;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LogAction;
import net.rose.saturnProtect.model.LookupEntry;
import net.rose.saturnProtect.model.LookupResult;
import net.rose.saturnProtect.util.BlockText;
import net.rose.saturnProtect.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Paged dialog views of the block, item and event logs. Each page is one dialog; Prev / Next re-run the
 * player's last lookup through {@link LookupService} and open the next dialog, so paging, permissions and
 * errors behave exactly like {@code /sp page}. "In chat" prints the current page as normal chat lines, because
 * a dialog blocks movement and sometimes you want to keep walking around.
 * <p>
 * Dialogs have no hover tooltips on their text, so everything that used to live in a hover (exact time, world)
 * is printed on a second line under each entry. Main thread only.
 */
public final class LogDialogs {

    private static final int BUTTON_WIDTH = 100;
    private static final int TEXT_WIDTH = 280;

    private static final TextColor SEPARATOR = NamedTextColor.DARK_GRAY;
    private static final TextColor COORDS = NamedTextColor.DARK_AQUA;

    private LogDialogs() {
    }

    // ------------------------------------------------------------------ public entry points

    public static void blocks(SaturnProtect plugin, LookupService lookups, Player player,
                              int page, int pages, LookupResult result) {
        long now = System.currentTimeMillis();
        List<DialogBody> body = new ArrayList<>();
        for (LookupEntry entry : result.records()) {
            if (entry instanceof BlockRecord block) {
                body.add(blockEntry(now, block));
            } else if (entry instanceof EventRecord event) {
                body.add(eventEntry(now, event));
            }
        }
        open(plugin, lookups, player, "Block log", result.total(), page, pages, body);
    }

    public static void items(SaturnProtect plugin, LookupService lookups, Player player,
                             int page, int pages, ItemLookupResult result) {
        long now = System.currentTimeMillis();
        List<DialogBody> body = new ArrayList<>();
        for (ItemRecord record : result.records()) {
            body.add(itemEntry(now, record));
        }
        open(plugin, lookups, player, "Item log", result.total(), page, pages, body);
    }

    public static void events(SaturnProtect plugin, LookupService lookups, Player player,
                              int page, int pages, EventLookupResult result) {
        long now = System.currentTimeMillis();
        List<DialogBody> body = new ArrayList<>();
        for (EventRecord record : result.records()) {
            body.add(eventEntry(now, record));
        }
        open(plugin, lookups, player, "Event log", result.total(), page, pages, body);
    }

    // ------------------------------------------------------------------ dialog frame

    private static void open(SaturnProtect plugin, LookupService lookups, Player player, String name,
                             long total, int page, int pages, List<DialogBody> entries) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text()
                .append(Component.text(total + " result" + (total == 1 ? "" : "s"), NamedTextColor.WHITE))
                .append(Component.text("  \u00B7  ", SEPARATOR))
                .append(Component.text("page " + page + " of " + pages, NamedTextColor.GRAY))
                .build(), TEXT_WIDTH + 24));
        body.addAll(entries);

        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).build();
        List<ActionButton> buttons = new ArrayList<>();

        if (page > 1) {
            buttons.add(ActionButton.create(Component.text("\u00AB Prev", NamedTextColor.AQUA),
                    Component.text("Go to page " + (page - 1)), BUTTON_WIDTH,
                    DialogAction.customClick((view, audience) ->
                            Bukkit.getScheduler().runTask(plugin, () -> lookups.page(player, page - 1)), once)));
        }
        if (page < pages) {
            buttons.add(ActionButton.create(Component.text("Next \u00BB", NamedTextColor.AQUA),
                    Component.text("Go to page " + (page + 1)), BUTTON_WIDTH,
                    DialogAction.customClick((view, audience) ->
                            Bukkit.getScheduler().runTask(plugin, () -> lookups.page(player, page + 1)), once)));
        }
        buttons.add(ActionButton.create(Component.text("In chat", NamedTextColor.YELLOW),
                Component.text("Close this and print the page in chat"), BUTTON_WIDTH,
                DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> lookups.pageInChat(player, page)), once)));

        ActionButton close = ActionButton.create(Component.text("Close"), null, BUTTON_WIDTH, null);

        Component title = Component.text("SaturnProtect", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text("  " + name, NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons, close, buttons.size())));
        player.showDialog(dialog);
    }

    // ------------------------------------------------------------------ entries

    private static DialogBody blockEntry(long now, BlockRecord record) {
        String oldName = BlockText.name(record.oldData());
        String newName = BlockText.name(record.newData());

        boolean placed = record.action() == LogAction.PLACE;
        String shown = placed ? newName : oldName;

        Component what = Component.text(record.action().verb() + " ", placed ? NamedTextColor.GREEN : NamedTextColor.RED)
                .append(Component.text(BlockText.pretty(shown), NamedTextColor.WHITE));
        if (placed && !BlockText.isAir(oldName)) {
            what = what.append(Component.text(" (replaced " + BlockText.pretty(oldName) + ")", NamedTextColor.GRAY));
        }

        Component text = entryText(record.actor(), what, now, record.time(), record.world(),
                record.x(), record.y(), record.z(), record.rolledBack(), null);
        return withIcon(icon(shown), 1, text);
    }

    private static DialogBody itemEntry(long now, ItemRecord record) {
        String name = BlockText.name(record.itemType());
        Component what = Component.text(record.action().verb() + " ", NamedTextColor.GOLD)
                .append(Component.text(record.amount() + "x ", NamedTextColor.WHITE))
                .append(Component.text(BlockText.pretty(name), NamedTextColor.WHITE));

        Component text = entryText(record.actor(), what, now, record.time(), record.world(),
                record.x(), record.y(), record.z(), record.rolledBack(), null);
        Material icon = icon(name);
        return withIcon(icon, Math.max(1, Math.min(record.amount(), 99)), text);
    }

    private static DialogBody eventEntry(long now, EventRecord record) {
        String subject = record.subject() == null ? "" : record.subject().replace("\n", " | ");
        EventAction action = record.action();
        TextColor color = switch (action) {
            case LOGIN -> NamedTextColor.GREEN;
            case LOGOUT -> NamedTextColor.RED;
            case CHAT -> NamedTextColor.AQUA;
            case COMMAND -> NamedTextColor.YELLOW;
            case KILL -> NamedTextColor.RED;
            case SIGN -> NamedTextColor.GOLD;
            case ENTITY_PLACE, ENTITY_BREAK, ENTITY_ITEM -> NamedTextColor.LIGHT_PURPLE;
        };

        Component what = switch (action) {
            case LOGIN, LOGOUT -> Component.text(action.verb(), color);
            case CHAT, SIGN -> Component.text(action.verb() + ": ", color)
                    .append(Component.text(subject, NamedTextColor.WHITE));
            case COMMAND, KILL, ENTITY_PLACE, ENTITY_BREAK -> Component.text(action.verb() + " ", color)
                    .append(Component.text(subject, NamedTextColor.WHITE));
            case ENTITY_ITEM -> Component.text(subject, color);
        };

        Component extra = null;
        if (action == EventAction.SIGN && record.detail() != null && !record.detail().isBlank()) {
            extra = Component.text("Before: " + record.detail().replace("\n", " | "), NamedTextColor.GRAY);
        }

        Component text = entryText(record.actor(), what, now, record.time(), record.world(),
                record.x(), record.y(), record.z(), false, extra);
        return DialogBody.plainMessage(text, TEXT_WIDTH);
    }

    /**
     * Two lines: who did what, then when and where in dim colors. An optional third line carries extra detail.
     * Rolled back records are struck through and tagged.
     */
    private static Component entryText(String actor, Component what, long now, long time, String world,
                                       int x, int y, int z, boolean rolledBack, Component extra) {
        Component first = Component.text(actor, NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text(" ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(what.decoration(TextDecoration.BOLD, false));

        Component second = Component.text(TimeFormat.ago(now - time) + " ago", NamedTextColor.GRAY)
                .append(Component.text("  \u00B7  ", SEPARATOR))
                .append(Component.text(x + ", " + y + ", " + z, COORDS))
                .append(Component.text("  \u00B7  ", SEPARATOR))
                .append(Component.text(world, NamedTextColor.DARK_GRAY));
        if (rolledBack) {
            second = second.append(Component.text("  rolled back", NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
            first = first.decorate(TextDecoration.STRIKETHROUGH);
        }

        Component text = first.appendNewline().append(second);
        if (extra != null) {
            text = text.appendNewline().append(extra);
        }
        return text;
    }

    /** The entry with the block or item drawn next to it, or plain text when there is nothing to draw. */
    private static DialogBody withIcon(Material icon, int amount, Component text) {
        PlainMessageDialogBody description = DialogBody.plainMessage(text, TEXT_WIDTH);
        if (icon == null) {
            return description;
        }
        return DialogBody.item(new ItemStack(icon, amount))
                .description(description)
                .showDecorations(amount > 1)
                .showTooltip(false)
                .build();
    }

    /** The item that best stands in for a block or item name, or null for air and things with no item form. */
    private static Material icon(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            return null;
        }
        if (material == Material.WATER) {
            return Material.WATER_BUCKET;
        }
        if (material == Material.LAVA) {
            return Material.LAVA_BUCKET;
        }
        if (material.isAir() || !material.isItem()) {
            return null;
        }
        return material;
    }
}
