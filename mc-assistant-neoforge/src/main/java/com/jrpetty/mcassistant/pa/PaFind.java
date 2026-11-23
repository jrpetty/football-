package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * [pa33] Looking further afield, as a player would: when there is nothing of a kind within reach (no trees round
 * here), where is the nearest? Only loaded ground is looked at (nothing is generated or loaded for it), from the
 * surface down a dozen blocks, nearest rings first, and never on ground that is off limits to it.
 */
public final class PaFind {

    private PaFind() {}

    @Nullable
    public static BlockPos nearest(PlayerAssistantEntity a, GatherGoal.Kind kind, int radius) {
        if (!(a.level() instanceof ServerLevel level)) return null;
        BlockPos at = a.blockPosition();
        for (int r = 4; r <= radius; r++) {
            // the ring r blocks out, every column of it (a tree is one column wide)
            for (int i = -r; i <= r; i++) {
                int[][] cells = {{i, -r}, {i, r}, {-r, i}, {r, i}};
                for (int[] c : cells) {
                    if (Math.abs(i) == r && c[0] != i) continue;               // corners once
                    BlockPos p = column(level, a, kind, at.getX() + c[0], at.getZ() + c[1]);
                    if (p != null) return p;
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos column(ServerLevel level, PlayerAssistantEntity a, GatherGoal.Kind kind, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int y = top; y >= top - 12 && y > level.getMinBuildHeight(); y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (kind.matches(level.getBlockState(p)) && !PaSafety.offLimits(a, p)) return p;
        }
        return null;
    }
}
