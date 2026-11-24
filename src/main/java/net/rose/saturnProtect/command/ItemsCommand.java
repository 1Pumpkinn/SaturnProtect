package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LookupService;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ItemsCommand implements SubCommand {

    private final ProtectConfig config;
    private final LookupService lookups;

    public ItemsCommand(ProtectConfig config, LookupService lookups) {
        this.config = config;
        this.lookups = lookups;
    }

    @Override
    public String usage() {
        return "items <u:user t:time r:radius a:add|remove|drop|pickup b:item>";
    }

    @Override
    public String description() {
        return "Search container, drop and pickup history";
    }

    @Override
    public String permission() {
        return Permissions.LOOKUP;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("items").then(
                Commands.argument("params", StringArgumentType.greedyString()).executes(this::run)));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String params = StringArgumentType.getString(context, "params");
        try {
            LookupQuery query = QueryParser.parse(sender, params, config);
            if (!query.actions().isEmpty()) {
                throw new IllegalArgumentException("break/place are block actions, use /sp lookup for those.");
            }
            lookups.runItems(sender, query);
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Messages.error(ex.getMessage()));
            return 0;
        }
    }
}
