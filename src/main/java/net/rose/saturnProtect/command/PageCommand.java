package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.core.LookupService;

import java.util.List;

public final class PageCommand implements SubCommand {

    private final LookupService lookups;

    public PageCommand(LookupService lookups) {
        this.lookups = lookups;
    }

    @Override
    public String usage() {
        return "page <number>";
    }

    @Override
    public String description() {
        return "Go to a page of your last lookup";
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
        return List.of(literal("page").then(Commands.argument("number", IntegerArgumentType.integer(1))
                .executes(context -> {
                    lookups.page(context.getSource().getSender(), IntegerArgumentType.getInteger(context, "number"));
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
