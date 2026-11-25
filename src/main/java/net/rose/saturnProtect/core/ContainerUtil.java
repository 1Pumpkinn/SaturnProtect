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

    /**
     * The key of the container an inventory belongs to: {@code chest}, {@code double_chest}, {@code barrel},
     * {@code chest_minecart}... Null if it is not a container this plugin tracks.
     */
    public static String key(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest) {
            return "double_chest";
        }
        Block block = locate(inventory);
        if (block != null) {
            return key(block);
        }
        Entity entity = locateEntity(inventory);
        return entity == null ? null : entity.getType().getKey().getKey();
    }

    /** The key of the container a block is, e.g. {@code barrel}. */
    public static String key(Block block) {
        return block.getType().getKey().getKey();
    }
}
