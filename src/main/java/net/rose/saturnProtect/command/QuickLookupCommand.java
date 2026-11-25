package net.rose.saturnProtect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LookupService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * A one-word search for one kind of action: {@code /sp show containers}, {@code /sp show chat Steve 1h}. It is
 * {@code /sp lookup} with {@code a:<actions>} already filled in, so every other parameter works the same, and
 * it searches straight away instead of opening the search form. Hidden from {@code /sp help}, {@link ShowCommand} groups them
 * together on one line instead.
 */
public final class QuickLookupCommand implements SubCommand {

    private final ProtectConfig config;
    private final LookupService lookups;
    private final List<String> names;
    private final String actions;
    private final String description;
    private final ParamSuggester.Kind kind;

    /**
     * @param names       the command name first, then its aliases
     * @param actions     what goes after {@code a:}, e.g. {@code add,remove}
     * @param kind        which log it reads, so tab completion offers blocks, items or entities after {@code b:}
     */
    public QuickLookupCommand(ProtectConfig config, LookupService lookups, List<String> names, String actions,
                              String description, ParamSuggester.Kind kind) {
        this.config = config;
        this.lookups = lookups;
        this.names = List.copyOf(names);
        this.actions = actions;
        this.description = description;
        this.kind = kind;
    }

    @Override
    public String usage() {
        return String.join(" | ", names) + " [player] [time] [radius]";
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public String permission() {
        return Permissions.LOOKUP;
    }

    /** The command name without aliases, for the one line list in help. */
    public String name() {
        return names.get(0);
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        List<LiteralArgumentBuilder<CommandSourceStack>> nodes = new ArrayList<>();
        for (String name : names) {
            nodes.add(literal(name)
                    .executes(context -> run(context.getSource().getSender(), ""))
                    .then(Commands.argument(ParamSuggester.ARGUMENT, StringArgumentType.greedyString())
                            .suggests(ParamSuggester.quickProvider(config, kind))
                            .executes(this::run)));
        }
        return nodes;
    }

    private int run(CommandContext<CommandSourceStack> context) {
        return run(context.getSource().getSender(), StringArgumentType.getString(context, ParamSuggester.ARGUMENT));
    }

    private int run(CommandSender sender, String params) {
        return LookupCommand.search(sender, "a:" + actions + " " + params, config, lookups);
    }
}
