package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.SaturnProtect;
import net.rose.saturnProtect.db.Database;
import net.rose.saturnProtect.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class StatusCommand implements SubCommand {

    private final SaturnProtect plugin;
    private final Database database;

    public StatusCommand(SaturnProtect plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String usage() {
        return "status";
    }

    @Override
    public String description() {
        return "Show database size and queue status";
    }

    @Override
    public String permission() {
        return Permissions.STATUS;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("status").executes(this::run));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        database.stats().whenComplete((stats, error) -> {
            if (error != null) {
                sender.sendMessage(Messages.error("Could not read status, check the console."));
                plugin.getLogger().log(Level.SEVERE, "Status query failed", error);
                return;
            }
            sender.sendMessage(Messages.header("Status"));
            sender.sendMessage(Messages.info("Block records: " + stats.blockRows()));
            sender.sendMessage(Messages.info("Item records: " + stats.itemRows()));
            sender.sendMessage(Messages.info("Queued writes: " + stats.queued()));
            sender.sendMessage(Messages.info(String.format(Locale.ROOT, "Database size: %.2f MB",
                    stats.fileBytes() / 1_048_576.0)));
        });
        return Command.SINGLE_SUCCESS;
    }
}
