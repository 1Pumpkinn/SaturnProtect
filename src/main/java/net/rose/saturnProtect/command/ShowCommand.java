package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Groups the one-word searches under {@code /sp show}, so {@code /sp} only completes to one entry instead of a
 * dozen: {@code /sp show containers Steve 1h}. Without arguments it lists them, each one clickable.
 */
public final class ShowCommand implements SubCommand {

    private final List<QuickLookupCommand> searches;

    public ShowCommand(List<QuickLookupCommand> searches) {
        this.searches = List.copyOf(searches);
    }

    @Override
    public String usage() {
        return "show <what> [player] [time] [radius]";
    }

    @Override
    public String description() {
        return "Quick searches: containers, chat, kills... /sp show lists them all";
    }

    @Override
    public String permission() {
        return Permissions.LOOKUP;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        LiteralArgumentBuilder<CommandSourceStack> show = literal("show").executes(context -> {
            send(context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        });
        for (QuickLookupCommand search : searches) {
            for (LiteralArgumentBuilder<CommandSourceStack> node : search.nodes()) {
                show.then(node);
            }
        }
        return List.of(show);
    }

    private void send(CommandSender sender) {
        sender.sendMessage(Messages.header("Show"));
        for (QuickLookupCommand search : searches) {
            sender.sendMessage(Component.text("/sp show ", NamedTextColor.AQUA)
                    .append(Component.text(search.usage(), NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(search.description(), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.suggestCommand("/sp show " + search.name() + " ")));
        }
    }
}
