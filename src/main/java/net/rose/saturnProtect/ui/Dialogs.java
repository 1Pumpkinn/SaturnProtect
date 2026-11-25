package net.rose.saturnProtect.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.command.LookupCommand;
import net.rose.saturnProtect.command.QueryParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Paper dialogs (the native pop-up forms) for the two places a form beats typing: building a lookup, and
 * confirming a purge. Both only collect input and then run the normal {@code /sp} command as the player, so
 * permissions, parsing and error messages stay in one place. Paged log results live in {@link LogDialogs}.
 */
public final class Dialogs {

    private static final int BUTTON_WIDTH = 130;
    private static final int FIELD_WIDTH = 300;

    private Dialogs() {
    }

    /** What the search form holds. Kept so a mistake reopens the form filled in instead of wiping it. */
    private record Form(String user, String time, String radius, String action, String block,
                        String exclude) {
    }

    /** The lookup form behind {@code /sp search}. Main thread only. */
    public static void openSearch(SaturnProtect plugin, Player player) {
        Form blank = new Form("", "3d", String.valueOf(plugin.protectConfig().defaultRadius()),
                "", "", "");
        openSearch(plugin, player, blank, null);
    }

    private static void openSearch(SaturnProtect plugin, Player player, Form form, String error) {
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).build();

        ActionButton search = ActionButton.create(Component.text("Search", NamedTextColor.GREEN),
                Component.text("Run the lookup"), BUTTON_WIDTH,
                DialogAction.customClick((view, audience) -> {
                    Form submitted = new Form(
                            orEmpty(view.getText("user")),
                            orEmpty(view.getText("time")),
                            orEmpty(view.getText("radius")),
                            orEmpty(view.getText("action")),
                            orEmpty(view.getText("block")),
                            orEmpty(view.getText("exclude")));
                    // validate and run on the main thread: parsing reads the player's location
                    Bukkit.getScheduler().runTask(plugin, () -> submit(plugin, player, submitted));
                }, once));
        ActionButton cancel = ActionButton.create(Component.text("Cancel", NamedTextColor.GRAY), null, BUTTON_WIDTH, null);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "Searches blocks, containers, chat and more. Leave a field empty to skip it. The actions decide what is searched.",
                NamedTextColor.GRAY), FIELD_WIDTH));
        if (error != null) {
            body.add(DialogBody.plainMessage(Component.text("\u26A0 " + error, NamedTextColor.RED), FIELD_WIDTH));
        }

        List<DialogInput> inputs = List.of(
                DialogInput.text("user", label("Players", "comma separated"))
                        .width(FIELD_WIDTH).initial(form.user()).maxLength(64).build(),
                DialogInput.text("time", label("How far back", "3d, 12h, 1w2d"))
                        .width(FIELD_WIDTH).initial(form.time()).maxLength(16).build(),
                DialogInput.text("radius", label("Radius", "a number, or global"))
                        .width(FIELD_WIDTH).initial(form.radius()).maxLength(8).build(),
                DialogInput.text("action", label("Actions", "optional: break, place, add, remove, open, chat..."))
                        .width(FIELD_WIDTH).initial(form.action()).maxLength(64).build(),
                DialogInput.text("block", label("Only these blocks or items", "optional, e.g. tnt,lava"))
                        .width(FIELD_WIDTH).initial(form.block()).maxLength(64).build(),
                DialogInput.text("exclude", label("Skip these blocks or items", "optional, e.g. grass_block"))
                        .width(FIELD_WIDTH).initial(form.exclude()).maxLength(64).build());

        Component title = Component.text("SaturnProtect", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text("  Search", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.confirmation(search, cancel)));
        player.showDialog(dialog);
    }

    /**
     * Asks a player to confirm something destructive. {@code onConfirm} runs on the main thread when they click
     * Yes. Main thread only.
     */
    public static void confirm(SaturnProtect plugin, Player player, String title, List<String> lines,
                               Runnable onConfirm) {
        List<DialogBody> body = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            // the first line is the point, the rest is the small print
            body.add(DialogBody.plainMessage(Component.text(lines.get(i),
                    i == 0 ? NamedTextColor.WHITE : NamedTextColor.GRAY), FIELD_WIDTH));
        }
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).build();

        ActionButton yes = ActionButton.create(Component.text("Yes, delete", NamedTextColor.RED),
                Component.text("This cannot be undone"), BUTTON_WIDTH,
                DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, onConfirm), once));
        ActionButton no = ActionButton.create(Component.text("Keep my records", NamedTextColor.GREEN),
                null, BUTTON_WIDTH, null);

        Component heading = Component.text("\u26A0 ", NamedTextColor.GOLD)
                .append(Component.text(title, NamedTextColor.RED, TextDecoration.BOLD));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(heading).canCloseWithEscape(true).body(body).build())
                .type(DialogType.confirmation(yes, no)));
        player.showDialog(dialog);
    }

    // ------------------------------------------------------------------ search submit

    /** Checks the form like the real command would; a mistake reopens the form with the reason on top. */
    private static void submit(SaturnProtect plugin, Player player, Form form) {
        if (!player.isOnline()) {
            return;
        }
        String params;
        try {
            params = params(
                    param("u", form.user()),
                    param("t", form.time()),
                    param("r", form.radius()),
                    param("a", form.action()),
                    param("b", form.block()),
                    param("e", form.exclude()));
            if (params.isEmpty()) {
                throw new IllegalArgumentException("Fill in at least one field.");
            }
            // same check the command does, so a mix of kinds shows up here and not in chat
            LookupCommand.route(QueryParser.parse(player, params, plugin.protectConfig()));
        } catch (IllegalArgumentException ex) {
            openSearch(plugin, player, form, ex.getMessage());
            return;
        }
        player.performCommand("sp lookup " + params);
    }

    // ------------------------------------------------------------------ small helpers

    /** A field label: the name in white, an optional hint after it in gray. */
    private static Component label(String name, String hint) {
        Component label = Component.text(name, NamedTextColor.WHITE);
        return hint == null ? label : label.append(Component.text("  " + hint, NamedTextColor.GRAY));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** {@code key:value}, or null if the field was left empty. Spaces would split one value into two parameters. */
    private static String param(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches(".*\\s.*")) {
            throw new IllegalArgumentException("No spaces inside a field, use commas to separate values.");
        }
        return key + ":" + trimmed;
    }

    private static String params(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null) {
                present.add(part);
            }
        }
        return String.join(" ", present);
    }
}
