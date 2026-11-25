package net.rose.saturnProtect.core;

import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import org.bukkit.block.Block;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
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

    /**
     * The entity a container inventory belongs to: chest and hopper minecarts, chest boats and the chests of
     * donkeys, mules and llamas. Null for anything else, including block containers.
     */
    public static Entity locateEntity(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof StorageMinecart || holder instanceof HopperMinecart
                || holder instanceof ChestBoat || holder instanceof AbstractChestedHorse) {
            return (Entity) holder;
        }
        return null;
    }
}
