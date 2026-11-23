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
        for (int r = 4; r <= radius; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r && Math.max(Math.abs(dx), Math.abs(dz)) != r - 1) continue;   // this ring only
                    int x = at.getX() + dx, z = at.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    for (int y = top; y >= top - 12 && y > level.getMinBuildHeight(); y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (kind.matches(level.getBlockState(p)) && !PaSafety.offLimits(a, p)) return p;
                    }
                }
            }
        }
        return null;
    }
}
