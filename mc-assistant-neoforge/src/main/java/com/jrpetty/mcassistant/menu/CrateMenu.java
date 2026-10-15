package com.jrpetty.mcassistant.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * [workitems] A shipping crate opened: its nine stacks in a three-by-three, as a dispenser's (the game's own screen draws
 * it), and nothing put in that would not go into a crate: no crate in a crate, no shulker box.
 */
public class CrateMenu extends DispenserMenu {

    public CrateMenu(int id, Inventory inventory, Container crate) {
        super(id, inventory, crate);
        for (int i = 0; i < 9; i++) {
            Slot was = slots.get(i);
            Slot crated = new Slot(crate, was.getContainerSlot(), was.x, was.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return fits(stack);
                }
            };
            crated.index = was.index;
            slots.set(i, crated);
        }
    }

    /** May this go into a crate? Not another crate, nor anything else that will not go into a container. */
    public static boolean fits(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (stack.getItem() instanceof BlockItem b && b.getBlock() instanceof com.jrpetty.mcassistant.block.ShippingCrateBlock) return false;
        return stack.getItem().canFitInsideContainerItems();
    }
}
