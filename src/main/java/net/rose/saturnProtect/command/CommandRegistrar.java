package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;

import java.util.ArrayList;
import java.util.List;

public final class CommandRegistrar {

    private CommandRegistrar() {
    }

    public static void register(SaturnProtect plugin) {
        List<SubCommand> subs = new ArrayList<>();
        HelpCommand help = new HelpCommand(subs);
        subs.add(help);
        subs.add(new InspectCommand(plugin.inspectors()));
        subs.add(new StatusCommand(plugin, plugin.database()));
        subs.add(new LookupCommand(plugin.protectConfig(), plugin.lookups()));
        subs.add(new NearCommand(plugin.protectConfig(), plugin.lookups()));
        subs.add(new ItemsCommand(plugin.protectConfig(), plugin.lookups()));
        subs.add(new EventsCommand(plugin.protectConfig(), plugin.lookups()));
        subs.add(new PageCommand(plugin.lookups()));
        subs.add(new RollbackCommand(plugin.protectConfig(), plugin.rollbacks(),
                "rollback", "rb", "Roll back changes (needs t:, e.g. u:Steve t:2h)", false));
        subs.add(new RollbackCommand(plugin.protectConfig(), plugin.rollbacks(),
                "restore", "rs", "Re-apply rolled back changes (needs t:)", true));
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
