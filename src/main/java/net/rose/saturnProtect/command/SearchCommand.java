package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.ui.Dialogs;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** Opens the lookup form. The same searches as lookup, items and events, without remembering the syntax. */
public final class SearchCommand implements SubCommand {

    private final SaturnProtect plugin;

    public SearchCommand(SaturnProtect plugin) {
        this.plugin = plugin;
    }

    @Override
    public String usage() {
        return "search | s";
    }

    @Override
    public String description() {
        return "Open a form to build a lookup";
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
        return List.of(build("search"), build("s"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return literal(name).executes(context -> {
            CommandSender sender = context.getSource().getSender();
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Messages.error("Only players can open the form, use /sp lookup from the console."));
                return 0;
            }
            Dialogs.openSearch(plugin, player);
            return Command.SINGLE_SUCCESS;
        });
    }
}
