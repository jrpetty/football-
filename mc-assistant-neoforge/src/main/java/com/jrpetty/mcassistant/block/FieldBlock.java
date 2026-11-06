package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [fields] A block of the fields and the pens that holds something (FieldBlockEntity): it shows what is in it in its
 * state (the eggs in the box, the feed in the trough, the fish in the trap), says what may be put in it, and spills it
 * when it is broken.
 */
public interface FieldBlock {

    /** The block's look brought up to what it holds now. */
    void refresh(Level level, BlockPos pos);

    /** May this be put in it (by a hopper, or a hand)? */
    boolean accepts(ItemStack stack);

    /** What it holds, spilled where it stood when it goes (not when only its state changes). */
    static void spill(BlockState state, Level level, BlockPos pos, BlockState now) {
        if (state.is(now.getBlock())) return;
        if (level.getBlockEntity(pos) instanceof FieldBlockEntity be) {
            Containers.dropContents(level, pos, be);
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
        }
    }
}
