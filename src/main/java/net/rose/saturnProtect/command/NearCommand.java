package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import org.bukkit.entity.Player;

import java.util.List;

public final class NearCommand implements SubCommand {

    private static final long NEAR_WINDOW_MILLIS = 3L * 86_400_000L;

    private final ProtectConfig config;
    private final LookupService lookups;

    public NearCommand(ProtectConfig config, LookupService lookups) {
        this.config = config;
        this.lookups = lookups;
    }

    @Override
    public String usage() {
        return "near [radius]";
    }

    @Override
    public String description() {
        return "Show all changes around you in the last 3 days";
    }

    @Override
    public String permission() {
        return Permissions.LOOKUP;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("near")
                .executes(context -> run(context, config.nearRadius()))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, Math.max(1, config.maxRadius())))
                        .executes(context -> run(context, IntegerArgumentType.getInteger(context, "radius")))));
    }

    private int run(CommandContext<CommandSourceStack> context, int radius) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.error("Only players can use /sp near."));
            return 0;
        }
        LookupQuery.Area area = QueryParser.areaAround(sender, radius, config);
        lookups.run(sender, new LookupQuery(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                System.currentTimeMillis() - NEAR_WINDOW_MILLIS, area));
        return Command.SINGLE_SUCCESS;
    }
}
