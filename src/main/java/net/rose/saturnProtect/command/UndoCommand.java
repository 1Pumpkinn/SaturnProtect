package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.core.RollbackService;

import java.util.List;

public final class UndoCommand implements SubCommand {

    private final RollbackService service;

    public UndoCommand(RollbackService service) {
        this.service = service;
    }

    @Override
    public String usage() {
        return "undo";
    }

    @Override
    public String description() {
        return "Revert your last rollback or restore";
    }

    @Override
    public String permission() {
        return Permissions.ROLLBACK;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("undo").executes(context -> {
            service.undo(context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        }));
    }
}
