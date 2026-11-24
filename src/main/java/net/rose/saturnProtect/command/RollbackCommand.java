package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.RollbackService;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Used for both /sp rollback and /sp restore; they take the same parameters. */
public final class RollbackCommand implements SubCommand {

    private final ProtectConfig config;
    private final RollbackService service;
    private final String name;
    private final String alias;
    private final String description;
    private final boolean restore;

    public RollbackCommand(ProtectConfig config, RollbackService service,
                           String name, String alias, String description, boolean restore) {
        this.config = config;
        this.service = service;
        this.name = name;
        this.alias = alias;
        this.description = description;
        this.restore = restore;
    }

    @Override
    public String usage() {
        return name + " | " + alias + " <t:time + filters>";
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String permission() {
        return Permissions.ROLLBACK;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(build(name), build(alias));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return literal(literal).then(Commands.argument("params", StringArgumentType.greedyString()).executes(this::run));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String params = StringArgumentType.getString(context, "params");
        try {
            LookupQuery query = QueryParser.parse(sender, params, config, true);
            if (!query.itemActions().isEmpty()) {
                throw new IllegalArgumentException("Only block changes can be rolled back (a:break / a:place).");
            }
            if (restore) {
                service.restore(sender, query);
            } else {
                service.rollback(sender, query);
            }
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Messages.error(ex.getMessage()));
            return 0;
        }
    }
}
