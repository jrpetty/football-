package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;

/**
 * [redstone] A hopper into any face of the storehouse: the goods go into the store.
 *
 * <p>A storehouse is a cube of twenty-seven units, and only its door unit is the store (the only one with a block
 * entity, the store's chest of goods). A hopper run into any of the others (the engineer's sorter lays its overflow
 * line into the middle of the cube's near face) found nothing there to fill, and stood full for ever. So every formed
 * unit but the door takes what a hopper (or a dropper) gives it and passes it straight to the door's store, by the
 * store's own insert (which never fills: it grows). It gives nothing out: the town's goods leave the store by its
 * keeper's hand, not down a hopper. The door itself is left as it always was, a plain container, so nothing that
 * already worked with it changes.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class StoreIntake implements IItemHandler {

    private final Level level;
    private final BlockPos door;

    private StoreIntake(Level level, BlockPos door) {
        this.level = level;
        this.door = door;
    }

    @SubscribeEvent
    public static void onCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, be, side) -> of(level, pos, state),
            McAssistantMod.STOREHOUSE.get());
    }

    /** The intake of a formed unit that is not the door, or null (the door, and a unit not in a cube, are as they were). */
    @Nullable
    static IItemHandler of(Level level, BlockPos pos, BlockState s) {
        if (level == null || level.isClientSide) return null;
        if (!StorehouseBlock.isFormed(s) || StorehouseBlock.isDoor(s)) return null;
        return new StoreIntake(level, StorehouseBlock.door(pos, s));
    }

    @Nullable
    private StorehouseBlockEntity store() {
        if (!level.isLoaded(door)) return null;
        return level.getBlockEntity(door) instanceof StorehouseBlockEntity sb && sb.isStore() ? sb : null;
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        StorehouseBlockEntity store = store();
        if (store == null) return stack;
        if (simulate) return ItemStack.EMPTY;                    // the store never fills (it grows)
        return store.insert(stack);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return store() != null;
    }
}
