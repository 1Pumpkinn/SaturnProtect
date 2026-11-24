package net.rose.saturnProtect.core;

import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ContainerUtil {

    private ContainerUtil() {
    }

    /**
     * The block a container inventory belongs to, or null if it is not a block container.
     * Double chests always resolve to their left half so both halves share one history.
     */
    public static Block locate(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest doubleChest) {
            return doubleChest.getLeftSide(false) instanceof BlockInventoryHolder left ? left.getBlock() : null;
        }
        return holder instanceof BlockInventoryHolder blockHolder ? blockHolder.getBlock() : null;
    }
}
