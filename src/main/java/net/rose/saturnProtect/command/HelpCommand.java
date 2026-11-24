package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

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
        for (SubCommand sub : all) {
            if (!sender.hasPermission(sub.permission())) {
                continue;
            }
            sender.sendMessage(Component.text("/sp ", NamedTextColor.AQUA)
                    .append(Component.text(sub.usage(), NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(sub.description(), NamedTextColor.GRAY)));
        }
    }
}
