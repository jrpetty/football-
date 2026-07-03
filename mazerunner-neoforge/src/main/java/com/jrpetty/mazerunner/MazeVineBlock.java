package com.jrpetty.mazerunner;

import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wall ivy: looks and behaves like a vanilla vine (climbable, breaks when its
 * wall vanishes) but never random-ticks — so it never spreads. The generated
 * greenery pattern stays exactly as authored.
 */
public class MazeVineBlock extends VineBlock {

    public MazeVineBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return false;
    }
}
