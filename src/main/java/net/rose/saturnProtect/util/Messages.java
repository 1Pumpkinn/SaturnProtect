package net.rose.saturnProtect.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.rose.saturnProtect.model.BlockRecord;
import net.rose.saturnProtect.model.EventAction;
import net.rose.saturnProtect.model.EventLookupResult;
import net.rose.saturnProtect.model.EventRecord;
import net.rose.saturnProtect.model.ItemLookupResult;
import net.rose.saturnProtect.model.LookupEntry;
import net.rose.saturnProtect.model.ItemRecord;
import net.rose.saturnProtect.model.LookupResult;

import java.util.List;

public final class Messages {

    private Messages() {
    }

    public static Component prefix() {
        return Component.text("SaturnProtect", NamedTextColor.AQUA)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY));
    }

    public static Component info(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GRAY));
    }

    public static Component success(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GREEN));
    }

    public static Component error(String text) {
        return prefix().append(Component.text(text, NamedTextColor.RED));
    }

    public static Component header(String detail) {
        Component header = Component.text("----- ", NamedTextColor.DARK_GRAY)
                .append(Component.text("SaturnProtect", NamedTextColor.AQUA))
                .append(Component.text(" -----", NamedTextColor.DARK_GRAY));
        if (detail != null && !detail.isEmpty()) {
            header = header.append(Component.text(" " + detail, NamedTextColor.GRAY));
        }
        return header;
    }

    public static void sendBlockHistory(Audience audience, String world, int x, int y, int z,
                                        List<BlockRecord> records) {
        String where = x + ", " + y + ", " + z;
        if (records.isEmpty()) {
            audience.sendMessage(info("No history found at " + where + "."));
            return;
        }

        audience.sendMessage(header("(" + world + " " + where + ")"));
        long now = System.currentTimeMillis();
        for (BlockRecord record : records) {
            audience.sendMessage(blockLine(now, record));
        }
    }

    public static void sendLookupPage(Audience audience, int page, int pages, LookupResult result) {
        audience.sendMessage(header("Lookup - " + result.total() + " result(s)"));
        long now = System.currentTimeMillis();
        for (LookupEntry entry : result.records()) {
            if (entry instanceof BlockRecord block) {
                audience.sendMessage(lookupLine(now, block));
            } else if (entry instanceof EventRecord event) {
                audience.sendMessage(eventLine(now, event));
            }
        }

        audience.sendMessage(pageFooter(page, pages));
    }

    public static void sendItemPage(Audience audience, int page, int pages, ItemLookupResult result) {
        audience.sendMessage(header("Items - " + result.total() + " result(s)"));
        long now = System.currentTimeMillis();
        for (ItemRecord record : result.records()) {
            audience.sendMessage(itemLine(now, record, true));
        }
        audience.sendMessage(pageFooter(page, pages));
    }

    public static void sendEventPage(Audience audience, int page, int pages, EventLookupResult result) {
        audience.sendMessage(header("Events - " + result.total() + " result(s)"));
        long now = System.currentTimeMillis();
        for (EventRecord record : result.records()) {
            audience.sendMessage(eventLine(now, record));
        }
        audience.sendMessage(pageFooter(page, pages));
    }

    public static void sendItemHistory(Audience audience, String world, int x, int y, int z, List<ItemRecord> records) {
        String where = x + ", " + y + ", " + z;
        if (records.isEmpty()) {
            audience.sendMessage(info("No container history found at " + where + "."));
            return;
        }

        audience.sendMessage(header("(container " + world + " " + where + ")"));
        long now = System.currentTimeMillis();
        for (ItemRecord record : records) {
            audience.sendMessage(itemLine(now, record, false));
        }
    }

    private static Component pageFooter(int page, int pages) {
        Component prev = page > 1
                ? Component.text("[<] ", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/sp page " + (page - 1)))
                : Component.text("[<] ", NamedTextColor.DARK_GRAY);
        Component next = page < pages
                ? Component.text(" [>]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/sp page " + (page + 1)))
                : Component.text(" [>]", NamedTextColor.DARK_GRAY);
        return prev.append(Component.text("Page " + page + "/" + pages, NamedTextColor.GRAY)).append(next);
    }

    private static Component eventLine(long now, EventRecord record) {
        String subject = record.subject() == null ? "" : record.subject().replace("\n", " | ");
        String what = switch (record.action()) {
            case LOGIN, LOGOUT -> record.action().verb();
            case CHAT, SIGN -> record.action().verb() + ": " + subject;
            case COMMAND, KILL, ENTITY_PLACE, ENTITY_BREAK -> record.action().verb() + " " + subject;
            case ENTITY_ITEM -> subject;
        };

        Component hover = Component.text(TimeFormat.absolute(record.time()) + " - " + record.world(), NamedTextColor.GRAY);
        if (record.action() == EventAction.SIGN
                && record.detail() != null && !record.detail().isBlank()) {
            hover = hover.append(Component.newline())
                    .append(Component.text("Before: " + record.detail().replace("\n", " | "), NamedTextColor.GRAY));
        }
        return Component.text(TimeFormat.ago(now - record.time()) + " ago ", NamedTextColor.GRAY)
                .append(Component.text("- ", NamedTextColor.DARK_GRAY))
                .append(Component.text(record.actor(), NamedTextColor.WHITE))
                .append(Component.text(" " + what + " ", NamedTextColor.GRAY))
                .append(Component.text("(" + record.x() + "/" + record.y() + "/" + record.z() + ")", NamedTextColor.DARK_AQUA))
                .hoverEvent(HoverEvent.showText(hover));
    }

    private static Component itemLine(long now, ItemRecord record, boolean showCoords) {
        Component line = Component.text(TimeFormat.ago(now - record.time()) + " ago ", NamedTextColor.GRAY)
                .append(Component.text("- ", NamedTextColor.DARK_GRAY))
                .append(Component.text(record.actor(), NamedTextColor.WHITE))
                .append(Component.text(" " + record.action().verb() + " " + record.amount() + "x "
                        + BlockText.name(record.itemType()), NamedTextColor.GRAY));
        if (showCoords) {
            line = line.append(Component.text(" (" + record.x() + "/" + record.y() + "/" + record.z() + ")", NamedTextColor.DARK_AQUA));
        }
        line = line.hoverEvent(HoverEvent.showText(Component.text(
                TimeFormat.absolute(record.time()) + " - " + record.world(), NamedTextColor.GRAY)));
        if (record.rolledBack()) {
            line = line.decorate(TextDecoration.STRIKETHROUGH);
        }
        return line;
    }

    private static Component lookupLine(long now, BlockRecord record) {
        Component line = Component.text(TimeFormat.ago(now - record.time()) + " ago ", NamedTextColor.GRAY)
                .append(Component.text("- ", NamedTextColor.DARK_GRAY))
                .append(Component.text(record.actor(), NamedTextColor.WHITE))
                .append(Component.text(" " + describe(record) + " ", NamedTextColor.GRAY))
                .append(Component.text("(" + record.x() + "/" + record.y() + "/" + record.z() + ")", NamedTextColor.DARK_AQUA))
                .hoverEvent(HoverEvent.showText(Component.text(
                        TimeFormat.absolute(record.time()) + " - " + record.world(), NamedTextColor.GRAY)));
        if (record.rolledBack()) {
            line = line.decorate(TextDecoration.STRIKETHROUGH);
        }
        return line;
    }

    private static Component blockLine(long now, BlockRecord record) {
        Component line = Component.text(TimeFormat.ago(now - record.time()) + " ago ", NamedTextColor.GRAY)
                .append(Component.text("- ", NamedTextColor.DARK_GRAY))
                .append(Component.text(record.actor(), NamedTextColor.WHITE))
                .append(Component.text(" " + describe(record), NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text(TimeFormat.absolute(record.time()), NamedTextColor.GRAY)));
        if (record.rolledBack()) {
            line = line.decorate(TextDecoration.STRIKETHROUGH);
        }
        return line;
    }

    private static String describe(BlockRecord record) {
        String oldName = BlockText.name(record.oldData());
        String newName = BlockText.name(record.newData());
        return switch (record.action()) {
            case BREAK -> record.action().verb() + " " + oldName;
            case PLACE -> BlockText.isAir(oldName)
                    ? record.action().verb() + " " + newName
                    : record.action().verb() + " " + newName + " (replaced " + oldName + ")";
        };
    }
}
