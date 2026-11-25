package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LookupService;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.LookupQuery;
import net.rose.saturnProtect.ui.Dialogs;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The one search command. What gets searched follows from the actions given: {@code break|place|kill} for
 * blocks, {@code add|remove|drop|pickup} (or {@code container}) for items, and {@code open|login|logout|
 * chat|command|sign|entity-*} for events. Actions can be written bare ({@code /sp l Steve 2h container}) or as
 * {@code a:container}. With no action it searches blocks. Without any arguments a player gets the search form.
 * {@code /sp items} and {@code /sp events} still work but are hidden from help, and {@link QuickLookupCommand}
 * has one-word versions of the common searches ({@code /sp show containers}, {@code /sp show chat}...).
 */
public final class LookupCommand implements SubCommand {

    /** Which log a query reads. */
    public enum Kind {
        BLOCKS, ITEMS, EVENTS
    }

    private final SaturnProtect plugin;
    private final ProtectConfig config;
    private final LookupService lookups;

    public LookupCommand(SaturnProtect plugin, ProtectConfig config, LookupService lookups) {
        this.plugin = plugin;
        this.config = config;
        this.lookups = lookups;
    }

    @Override
    public String usage() {
        return "lookup | l [player] [time] [radius] [action] [b:block e:exclude]";
    }

    @Override
    public String description() {
        return "Search anything, in any order: /sp l Steve 2h 10 container. Actions: break place kill container drop open login chat command sign. No arguments opens a form";
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
        return literal(name)
                .executes(context -> run(context.getSource().getSender(), ""))
                .then(Commands.argument(ParamSuggester.ARGUMENT, StringArgumentType.greedyString())
                        .suggests(ParamSuggester.provider(config, ParamSuggester.Kind.ANY))
                        .executes(this::run));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        return run(context.getSource().getSender(), StringArgumentType.getString(context, ParamSuggester.ARGUMENT));
    }

    private int run(CommandSender sender, String params) {
        if (params.isBlank() && sender instanceof Player player) {
            Dialogs.openSearch(plugin, player);
            return Command.SINGLE_SUCCESS;
        }
        return search(sender, params, config, lookups);
    }

    /** Parses {@code params}, works out which log they ask for and shows the first page. */
    static int search(CommandSender sender, String params, ProtectConfig config, LookupService lookups) {
        try {
            LookupQuery query = QueryParser.parse(sender, params, config);
            switch (route(query)) {
                case ITEMS -> lookups.runItems(sender, query);
                case EVENTS -> lookups.runEvents(sender, query);
                case BLOCKS -> lookups.run(sender, query);
            }
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Messages.error(ex.getMessage()));
            return 0;
        }
    }

    /**
     * Decides which log a query reads from its actions. Kills count as block lookups, since they are listed
     * together with block changes.
     *
     * @throws IllegalArgumentException if the actions belong to more than one kind
     */
    public static Kind route(LookupQuery query) {
        boolean blocks = !query.actions().isEmpty();
        boolean items = !query.itemActions().isEmpty();
        boolean events = query.text() != null
                || query.eventActions().stream().anyMatch(action -> action != EventAction.KILL);

        int kinds = (blocks ? 1 : 0) + (items ? 1 : 0) + (events ? 1 : 0);
        if (kinds > 1) {
            throw new IllegalArgumentException("Search one kind at a time: blocks (break, place, kill), "
                    + "containers (add, remove, drop, pickup) or events (open, login, logout, chat, command, sign, entity-*).");
        }
        return items ? Kind.ITEMS : events ? Kind.EVENTS : Kind.BLOCKS;
    }
}
