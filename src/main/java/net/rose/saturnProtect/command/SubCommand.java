package net.rose.saturnProtect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;

public interface SubCommand {

    /** Shown in /sp help, e.g. {@code inspect | i}. */
    String usage();

    String description();

    String permission();

    /** Hidden commands work but are left out of /sp help, e.g. old names kept so nobody's habits break. */
    default boolean hidden() {
        return false;
    }

    /** One node per name/alias. Use {@link #literal(String)} so the permission check is applied. */
    List<LiteralArgumentBuilder<CommandSourceStack>> nodes();

    default LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name).requires(source -> source.getSender().hasPermission(permission()));
    }
}
