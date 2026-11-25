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

public final class EventsCommand implements SubCommand {

    private final ProtectConfig config;
    private final LookupService lookups;

    public EventsCommand(ProtectConfig config, LookupService lookups) {
        this.config = config;
        this.lookups = lookups;
    }

    @Override
    public String usage() {
        return "events | ev [<player> <time> <radius>] [a:login|logout|chat|command|kill|sign|entity-* b:victim|entity m:text]";
    }

    @Override
    public String description() {
        return "Search sessions, chat, commands, kills, signs, item frames and armor stands";
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public String permission() {
        return Permissions.LOOKUP;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(build("events"), build("ev"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return literal(name).executes(context -> run(context.getSource().getSender(), "")).then(Commands.argument(ParamSuggester.ARGUMENT, StringArgumentType.greedyString())
                .suggests(ParamSuggester.provider(config, ParamSuggester.Kind.EVENTS))
                .executes(this::run));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        return run(context.getSource().getSender(), StringArgumentType.getString(context, ParamSuggester.ARGUMENT));
    }

    private int run(CommandSender sender, String params) {
        try {
            LookupQuery query = QueryParser.parse(sender, params, config);
            if (!query.actions().isEmpty() || !query.itemActions().isEmpty()) {
                throw new IllegalArgumentException("Block and item actions have their own commands: /sp lookup and /sp items.");
            }
            lookups.runEvents(sender, query);
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Messages.error(ex.getMessage()));
            return 0;
        }
    }
}
