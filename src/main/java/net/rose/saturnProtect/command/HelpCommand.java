package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public final class HelpCommand implements SubCommand {

    private final List<SubCommand> all;

    public HelpCommand(List<SubCommand> all) {
        this.all = all;
    }

    @Override
    public String usage() {
        return "help";
    }

    @Override
    public String description() {
        return "Show this help";
    }

    @Override
    public String permission() {
        return Permissions.USE;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("help").executes(context -> {
            send(context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        }));
    }

    public void send(CommandSender sender) {
        sender.sendMessage(Messages.header("Help"));
        List<String> quick = new ArrayList<>();
        for (SubCommand sub : all) {
            if (!sender.hasPermission(sub.permission())) {
                continue;
            }
            if (sub instanceof QuickLookupCommand search) {
                quick.add(search.name());
            }
            if (sub.hidden()) {
                continue;
            }
            sender.sendMessage(Component.text("/sp ", NamedTextColor.AQUA)
                    .append(Component.text(sub.usage(), NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(sub.description(), NamedTextColor.GRAY)));
        }
        if (!quick.isEmpty()) {
            sender.sendMessage(Component.text("/sp ", NamedTextColor.AQUA)
                    .append(Component.text("<" + String.join("|", quick) + "> [player] [time] [radius]", NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("One word searches, e.g. /sp containers Steve 1h", NamedTextColor.GRAY)));
        }
    }
}
