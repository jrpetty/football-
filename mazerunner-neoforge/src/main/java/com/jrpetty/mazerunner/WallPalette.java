package com.jrpetty.mazerunner;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.WallStyle;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps a wall block position to its weathered palette variant. Used by the
 * generator, the chunk-load snap, and the wall animator alike, so walls look
 * identical whether they were generated, snapped, or animated into place.
 */
public final class WallPalette {

    private WallPalette() {}

    public static BlockState stateAt(MazeConfigData cfg, int x, int y, int z) {
        int band = WallStyle.bandAt(x, y, z, cfg.wallBaseY, cfg.wallTopY);
        return ModBlocks.WALL_PALETTE.get(band).get().defaultBlockState();
    }
}
