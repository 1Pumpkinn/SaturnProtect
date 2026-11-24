package net.rose.saturnProtect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.rose.saturnProtect.Permissions;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.PurgeService;
import net.rose.saturnProtect.core.RollbackService;
import net.rose.saturnProtect.util.Messages;
import net.rose.saturnProtect.util.TimeFormat;
import net.rose.saturnProtect.util.TimeParser;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * {@code /sp purge t:30d} shows what would be deleted; adding {@code confirm} deletes it. Adding {@code vacuum}
 * also shrinks the database file afterwards.
 */
public final class PurgeCommand implements SubCommand {

    private final ProtectConfig config;
    private final PurgeService purges;
    private final RollbackService rollbacks;

    public PurgeCommand(ProtectConfig config, PurgeService purges, RollbackService rollbacks) {
        this.config = config;
        this.purges = purges;
        this.rollbacks = rollbacks;
    }

    @Override
    public String usage() {
        return "purge <t:time> [vacuum] [confirm]";
    }

    @Override
    public String description() {
        return "Delete records older than t: (shows a preview until you add confirm)";
    }

    @Override
    public String permission() {
        return Permissions.PURGE;
    }

    @Override
    public List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(literal("purge")
                .then(Commands.argument("params", StringArgumentType.greedyString()).executes(this::run)));
    }

    private int run(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String params = StringArgumentType.getString(context, "params");
        try {
            String time = null;
            boolean confirm = false;
            boolean vacuum = false;
            for (String token : params.trim().split("\\s+")) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.equals("confirm")) {
                    confirm = true;
                } else if (lower.equals("vacuum")) {
                    vacuum = true;
                } else if (lower.startsWith("t:")) {
                    time = token.substring(2);
                } else if (lower.startsWith("time:")) {
                    time = token.substring(5);
                } else {
                    throw new IllegalArgumentException("Unknown option '" + token + "'. Use t:<time>, vacuum, confirm.");
                }
            }
            if (time == null || time.isEmpty()) {
                throw new IllegalArgumentException("Say how much to keep, e.g. /sp purge t:30d deletes everything older than 30 days.");
            }

            long age = TimeParser.parseMillis(time);
            long minimum = config.purgeMinAgeMillis();
            if (age < minimum) {
                throw new IllegalArgumentException("Purge only deletes records older than " + TimeFormat.ago(minimum)
                        + " (purge.minimum-age in config.yml).");
            }
            if (rollbacks.isBusy()) {
                throw new IllegalArgumentException("A rollback is running, wait for it to finish before purging.");
            }
            if (purges.isRunning()) {
                throw new IllegalArgumentException("A purge is already running.");
            }

            long cutoff = System.currentTimeMillis() - age;
            if (confirm) {
                purges.purge(sender, cutoff, vacuum);
            } else {
                purges.preview(sender, cutoff, "/sp purge t:" + time + (vacuum ? " vacuum" : "") + " confirm");
            }
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Messages.error(ex.getMessage()));
            return 0;
        }
    }
}
