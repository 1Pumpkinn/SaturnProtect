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
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class LookupCommand implements SubCommand {

    private final ProtectConfig config;
    private final LookupService lookups;

    public LookupCommand(ProtectConfig config, LookupService lookups) {
        this.config = config;
        this.lookups = lookups;
    }

    @Override
    public String usage() {
        return "lookup | l <u:user t:time r:radius a:break|place|kill b:block e:exclude>";
    }

    @Override
    public String description() {
        return "Search block changes and kills (e.g. u:Steve t:2d r:10 a:break)";
    }

    @Override
    public String permission() {
        return Permissions.LOOKUP;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(build("lookup"), build("l"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return literal(name).then(Commands.argument("params", StringArgumentType.greedyString())
                .suggests(ParamSuggester.provider(config, ParamSuggester.Kind.BLOCKS))
                .executes(this::run));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String params = StringArgumentType.getString(context, "params");
        try {
            LookupQuery query = QueryParser.parse(sender, params, config);
            if (!query.itemActions().isEmpty()) {
                throw new IllegalArgumentException("add/remove/drop/pickup are item actions, use /sp items for those.");
            }
            boolean otherEvents = query.eventActions().stream().anyMatch(action -> action != EventAction.KILL);
            if (otherEvents || query.text() != null) {
                throw new IllegalArgumentException("login/logout/chat/command/sign/entity-* are events, use /sp events for those.");
            }
            lookups.run(sender, query);
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Messages.error(ex.getMessage()));
            return 0;
        }
    }
}
