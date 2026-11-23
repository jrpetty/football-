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
            var st = level.getBlockState(p);
            if (y > a.getBlockY() + 3) continue;                          // [pa33c-2b] the top of a cliff it can't climb: lower down the face
            if (!kind.matches(st) || PaSafety.offLimits(a, p) || a.isUnreachable(p)) continue;
            if (a.sparesBuilds() && (st.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || kind == GatherGoal.Kind.LOGS && !tree(level, p))) continue;
            return p;
        }
        return null;
    }

    /** A log with a crown of leaves over it: a tree, not a beam in somebody's house. */
    static boolean tree(ServerLevel level, BlockPos pos) {
        BlockPos top = pos;
        for (int i = 0; i < 40 && level.getBlockState(top.above()).is(net.minecraft.tags.BlockTags.LOGS); i++) top = top.above();
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-3, -1, -3), top.offset(3, 3, 3))) {
            if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) return true;
        }
        return false;
    }
}
