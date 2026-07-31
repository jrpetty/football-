package com.jrpetty.mazerunner.gen;

import com.jrpetty.mazerunner.config.GladeTerrain;
import com.jrpetty.mazerunner.config.TreeShape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Built-in Glade trees — plain base-game oak, birch and dark oak, grown at a
 * natural mix of small, medium and large sizes. The crown geometry lives in
 * {@link TreeShape} (pure maths, unit-tested); this class only maps it onto
 * real blocks. Rendered column-by-column so trees that straddle a chunk border
 * still assemble seamlessly, and generated at worldgen so the forest is
 * full-grown at first sight — no saplings, no mod required.
 */
final class TreePlacer {

    private TreePlacer() {}

    /** Contributes any log/leaf blocks that trees near (wx,wz) place in this column. */
    static boolean emit(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int floorY, int maxLeafY) {
        boolean trunkHere = false;
        int r = TreeShape.MAX_RADIUS;
        for (int tx = wx - r; tx <= wx + r; tx++) {
            for (int tz = wz - r; tz <= wz + r; tz++) {
                if (!GladeTerrain.isTrunkSite(tx, tz)) continue;
                if (contribute(chunk, pos, wx, wz, tx, tz, floorY, maxLeafY)) trunkHere = true;
            }
        }
        return trunkHere;
    }

    /** Renders the slice of the tree rooted at (tx,tz) that falls in column (wx,wz). */
    private static boolean contribute(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int tx, int tz, int floorY, int maxLeafY) {
        int species = GladeTerrain.speciesAt(tx, tz);
        int size = GladeTerrain.treeSize(tx, tz);
        int ground = floorY + GladeTerrain.heightAt(tx, tz);
        int trunkHeight = GladeTerrain.trunkHeight(tx, tz);

        int dx = wx - tx;
        int dz = wz - tz;
        boolean center = dx == 0 && dz == 0;

        // Trunk — centre column only.
        if (center) {
            BlockState log = logFor(species);
            for (int y = ground + 1; y <= ground + trunkHeight && y <= maxLeafY; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), log, false);
            }
        }

        int rH = TreeShape.horizontalRadius(species, size);
        int rV = TreeShape.verticalRadius(species, size);
        if (Math.max(Math.abs(dx), Math.abs(dz)) > rH) return center;

        int cy = TreeShape.crownCenterY(ground, trunkHeight);
        BlockState leaves = leavesFor(species);
        int yLo = Math.max(ground + 1, cy - rV);
        int yHi = Math.min(maxLeafY, cy + rV);
        for (int y = yLo; y <= yHi; y++) {
            if (!TreeShape.inCrown(dx, dz, y, cy, rH, rV, wx, wz)) continue;
            if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                chunk.setBlockState(pos, leaves, false);
            }
        }
        return center;
    }

    private static BlockState logFor(int species) {
        return switch (species) {
            case GladeTerrain.BIRCH -> Blocks.BIRCH_LOG.defaultBlockState();
            case GladeTerrain.DARK_OAK -> Blocks.DARK_OAK_LOG.defaultBlockState();
            default -> Blocks.OAK_LOG.defaultBlockState();
        };
    }

    private static BlockState leavesFor(int species) {
        BlockState base = switch (species) {
            case GladeTerrain.BIRCH -> Blocks.BIRCH_LEAVES.defaultBlockState();
            case GladeTerrain.DARK_OAK -> Blocks.DARK_OAK_LEAVES.defaultBlockState();
            default -> Blocks.OAK_LEAVES.defaultBlockState();
        };
        return base.setValue(LeavesBlock.PERSISTENT, true);
    }
}
