package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.core.InspectorManager;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class InspectCommand implements SubCommand {

    private final InspectorManager inspectors;

    public InspectCommand(InspectorManager inspectors) {
        this.inspectors = inspectors;
    }

    @Override
    public String usage() {
        return "inspect | i";
    }

    @Override
    public String description() {
        return "Toggle the block inspector";
    }

    @Override
    public String permission() {
        return Permissions.INSPECT;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("inspect").executes(this::run), literal("i").executes(this::run));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.error("Only players can use the inspector."));
            return 0;
        }

        boolean enabled = inspectors.toggle(player.getUniqueId());
        player.sendMessage(Messages.info(enabled
                ? "Inspector enabled. Left-click a block, or right-click to inspect the block next to it."
                : "Inspector disabled."));
        return Command.SINGLE_SUCCESS;
    }
}
