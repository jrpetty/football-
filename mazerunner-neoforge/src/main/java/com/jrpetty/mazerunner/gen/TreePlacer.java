package com.jrpetty.mazerunner.gen;

import com.jrpetty.mazerunner.config.GladeTerrain;
import com.jrpetty.mazerunner.config.Noise;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Built-in Glade trees — plain base-game oak, birch and dark oak, grown at a
 * natural mix of small, medium and large sizes. Each tree is an ellipsoidal
 * vanilla-style canopy on a log trunk (birches tall and narrow, dark oaks
 * broad and low, oaks in between). Rendered column-by-column so trees that
 * straddle a chunk border still assemble seamlessly, and generated at worldgen
 * so the forest is full-grown at first sight — no saplings, no mod required.
 */
final class TreePlacer {

    /** Max horizontal canopy radius — bounds the neighbour-scan window. */
    static final int MAX_RADIUS = 3;

    private TreePlacer() {}

    /** Contributes any log/leaf blocks that trees near (wx,wz) place in this column. */
    static boolean emit(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int floorY, int maxLeafY) {
        boolean trunkHere = false;
        for (int tx = wx - MAX_RADIUS; tx <= wx + MAX_RADIUS; tx++) {
            for (int tz = wz - MAX_RADIUS; tz <= wz + MAX_RADIUS; tz++) {
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
        int trunkTop = ground + GladeTerrain.trunkHeight(tx, tz);

        int rH = horizontalRadius(species, size);
        int rV = verticalRadius(species, size);
        int cy = trunkTop + 1; // canopy centre sits just above the trunk top

        int dx = wx - tx;
        int dz = wz - tz;
        boolean center = dx == 0 && dz == 0;

        BlockState log = logFor(species);
        BlockState leaves = leavesFor(species);

        // Trunk — centre column only.
        if (center) {
            for (int y = ground + 1; y <= trunkTop && y <= maxLeafY; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), log, false);
            }
        }

        // Ellipsoidal canopy — the same field test for every column of this tree.
        int horiz = dx * dx + dz * dz;
        if (horiz > rH * rH) return center;
        int yLo = Math.max(ground + 1, cy - rV);
        int yHi = Math.min(maxLeafY, cy + rV);
        for (int y = yLo; y <= yHi; y++) {
            double h = horiz / (double) (rH * rH);
            double v = ((y - cy) * (y - cy)) / (double) (rV * rV);
            // Small deterministic wobble so the silhouette isn't a perfect egg.
            double wobble = (Noise.hash2(wx * 3 + y, wz * 3 - y, 0x1EAF) - 0.5) * 0.22;
            if (h + v > 1.0 + wobble) continue;
            if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                chunk.setBlockState(pos, leaves, false);
            }
        }
        return center;
    }

    /** Horizontal crown radius: birch narrow, dark oak broad, oak in between. */
    private static int horizontalRadius(int species, int size) {
        return switch (species) {
            case GladeTerrain.BIRCH -> 1 + Math.min(1, size);       // 1, 2, 2
            case GladeTerrain.DARK_OAK -> 2 + (size >= 1 ? 1 : 0);  // 2, 3, 3
            default -> 2 + (size >= 2 ? 1 : 0);                     // oak/apple 2, 2, 3
        };
    }

    /** Vertical crown radius: birch tall, dark oak low, oak in between. */
    private static int verticalRadius(int species, int size) {
        return switch (species) {
            case GladeTerrain.BIRCH -> 2 + size;                   // 2, 3, 4 (tall, slim)
            case GladeTerrain.DARK_OAK -> 2 + (size >= 2 ? 1 : 0); // 2, 2, 3 (broad, low)
            default -> 2 + (size >= 1 ? 1 : 0);                    // oak 2, 3, 3
        };
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
