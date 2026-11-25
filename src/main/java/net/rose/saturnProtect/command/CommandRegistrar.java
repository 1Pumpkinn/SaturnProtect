package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LookupService;

import java.util.ArrayList;
import java.util.List;

public final class CommandRegistrar {

    private CommandRegistrar() {
    }

    /** One-word searches: {@code /sp show containers}, {@code /sp show chat}... See {@link ShowCommand}. */
    private static void addQuickSearches(SaturnProtect plugin, List<SubCommand> subs) {
        List<QuickLookupCommand> searches = new ArrayList<>();
        ProtectConfig config = plugin.protectConfig();
        LookupService lookups = plugin.lookups();
        ParamSuggester.Kind blocks = ParamSuggester.Kind.BLOCKS;
        ParamSuggester.Kind items = ParamSuggester.Kind.ITEMS;
        ParamSuggester.Kind events = ParamSuggester.Kind.EVENTS;
        searches.add(new QuickLookupCommand(config, lookups, List.of("containers", "container", "chests", "c"),
                "add,remove", "Items put in or taken out of containers", items));
        searches.add(new QuickLookupCommand(config, lookups, List.of("opens"),
                "open", "Who opened containers", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("chat"),
                "chat", "Chat messages", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("commands", "cmds"),
                "command", "Commands players ran", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("kills"),
                "kill", "Kills", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("sessions", "joins"),
                "login,logout", "Logins and logouts", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("signs"),
                "sign", "Sign edits", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("frames"),
                "entity-place,entity-break,entity-item", "Item frames, paintings and armor stands", events));
        searches.add(new QuickLookupCommand(config, lookups, List.of("drops"),
                "drop", "Dropped items", items));
        searches.add(new QuickLookupCommand(config, lookups, List.of("pickups"),
                "pickup", "Picked up items", items));
        searches.add(new QuickLookupCommand(config, lookups, List.of("breaks"),
                "break", "Broken blocks", blocks));
        searches.add(new QuickLookupCommand(config, lookups, List.of("places"),
                "place", "Placed blocks", blocks));
        subs.add(new ShowCommand(searches));
    }

    public static void register(SaturnProtect plugin) {
        List<SubCommand> subs = new ArrayList<>();
        HelpCommand help = new HelpCommand(subs);
        subs.add(help);
        subs.add(new InspectCommand(plugin.inspectors()));
        subs.add(new StatusCommand(plugin, plugin.database()));
        subs.add(new LookupCommand(plugin, plugin.protectConfig(), plugin.lookups()));
        subs.add(new NearCommand(plugin.protectConfig(), plugin.lookups()));
        subs.add(new ItemsCommand(plugin.protectConfig(), plugin.lookups()));
        subs.add(new EventsCommand(plugin.protectConfig(), plugin.lookups()));
        addQuickSearches(plugin, subs);
        subs.add(new SearchCommand(plugin));
        subs.add(new PageCommand(plugin.lookups()));
        subs.add(new RollbackCommand(plugin.protectConfig(), plugin.rollbacks(),
                "rollback", "rb", "Roll back changes (needs a time, e.g. Steve 2h)", false));
        subs.add(new RollbackCommand(plugin.protectConfig(), plugin.rollbacks(),
                "restore", "rs", "Re-apply rolled back changes (needs a time)", true));
        subs.add(new UndoCommand(plugin.rollbacks()));
        subs.add(new PurgeCommand(plugin.protectConfig(), plugin.purges(), plugin.rollbacks()));
        subs.add(new ReloadCommand(plugin));

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("saturnprotect")
                    .requires(source -> source.getSender().hasPermission(Permissions.USE))
                    .executes(context -> {
                        help.send(context.getSource().getSender());
                        return Command.SINGLE_SUCCESS;
                    });

            for (SubCommand sub : subs) {
                for (LiteralArgumentBuilder<CommandSourceStack> node : sub.nodes()) {
                    root.then(node);
                }
            }

            event.registrar().register(root.build(), "SaturnProtect main command", List.of("sp"));
        });
    }
}
