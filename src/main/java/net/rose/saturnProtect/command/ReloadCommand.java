package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ReloadCommand implements SubCommand {

    private final SaturnProtect plugin;

    public ReloadCommand(SaturnProtect plugin) {
        this.plugin = plugin;
    }

    @Override
    public String usage() {
        return "reload";
    }

    @Override
    public String description() {
        return "Reload config.yml without restarting";
    }

    @Override
    public String permission() {
        return Permissions.RELOAD;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("reload").executes(context -> {
            CommandSender sender = context.getSource().getSender();
            String databaseBefore = plugin.protectConfig().databaseFile();
            plugin.reload();
            sender.sendMessage(Messages.success("Configuration reloaded."));
            if (!databaseBefore.equals(plugin.protectConfig().databaseFile())) {
                sender.sendMessage(Messages.info("database.file changed, that only takes effect after a restart."));
            }
            return Command.SINGLE_SUCCESS;
        }));
    }
}
