package net.rose.saturnProtect.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Paper dialogs (the native pop-up forms) for the two places a form beats typing: building a lookup, and
 * confirming a purge. Both only collect input and then run the normal {@code /sp} command as the player, so
 * permissions, parsing and error messages stay in one place.
 */
public final class Dialogs {

    private static final int BUTTON_WIDTH = 120;

    private Dialogs() {
    }

    /** The lookup form behind {@code /sp search}. Main thread only. */
    public static void openSearch(SaturnProtect plugin, Player player) {
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).build();

        ActionButton search = ActionButton.create(Component.text("Search", NamedTextColor.GREEN),
                Component.text("Run the lookup"), BUTTON_WIDTH,
                DialogAction.customClick((view, audience) -> {
                    String type = view.getText("type");
                    String command = "sp " + (type == null ? "lookup" : type);
                    String params;
                    try {
                        params = params(
                                param("u", view.getText("user")),
                                param("t", view.getText("time")),
                                param("r", view.getText("radius")),
                                param("a", view.getText("action")),
                                param("b", view.getText("block")));
                    } catch (IllegalArgumentException ex) {
                        audience.sendMessage(Messages.error(ex.getMessage()));
                        return;
                    }
                    if (params.isEmpty()) {
                        audience.sendMessage(Messages.error("Fill in at least one field."));
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command + " " + params));
                }, once));
        ActionButton cancel = ActionButton.create(Component.text("Cancel"), null, BUTTON_WIDTH, null);

        List<DialogInput> inputs = List.of(
                DialogInput.text("user", Component.text("Player(s), comma separated")).initial("").maxLength(64).build(),
                DialogInput.text("time", Component.text("How far back (3d, 12h, 1w2d)")).initial("3d").maxLength(16).build(),
                DialogInput.text("radius", Component.text("Radius, or 'global'"))
                        .initial(String.valueOf(plugin.protectConfig().defaultRadius())).maxLength(8).build(),
                DialogInput.singleOption("type", Component.text("Search in"), List.of(
                        SingleOptionDialogInput.OptionEntry.create("lookup", Component.text("Blocks and kills"), true),
                        SingleOptionDialogInput.OptionEntry.create("items", Component.text("Containers and drops"), false),
                        SingleOptionDialogInput.OptionEntry.create("events", Component.text("Chat, commands, sessions, frames"), false))).build(),
                DialogInput.text("action", Component.text("Actions (optional, e.g. break,place)")).initial("").maxLength(64).build(),
                DialogInput.text("block", Component.text("Blocks or items (optional, e.g. tnt,lava)")).initial("").maxLength(64).build());

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("SaturnProtect lookup"))
                        .body(List.of(DialogBody.plainMessage(Component.text("Leave a field empty to skip it."))))
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
        for (String line : lines) {
            body.add(DialogBody.plainMessage(Component.text(line)));
        }
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).build();

        ActionButton yes = ActionButton.create(Component.text("Yes, delete", NamedTextColor.RED),
                Component.text("This cannot be undone"), BUTTON_WIDTH,
                DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, onConfirm), once));
        ActionButton no = ActionButton.create(Component.text("Cancel"), null, BUTTON_WIDTH, null);

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.RED)).body(body).build())
                .type(DialogType.confirmation(yes, no)));
        player.showDialog(dialog);
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
