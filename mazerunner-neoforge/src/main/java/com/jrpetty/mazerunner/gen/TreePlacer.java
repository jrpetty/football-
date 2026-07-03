package com.jrpetty.mazerunner.gen;

import com.jrpetty.mazerunner.config.GladeTerrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Built-in Glade trees — oak, birch, dark oak and apple-oak — placed on a
 * jittered lattice within the forest mask, each following the terrain height
 * of its own trunk. If the Dynamic Trees mod is installed, its species can be
 * substituted at these sites (see {@code MazeCompat}); the built-in trees are
 * the always-available fallback.
 */
final class TreePlacer {

    private TreePlacer() {}

    /** Contributes any log/leaf blocks that trees near (wx,wz) place in this column. */
    static boolean emit(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int wx, int wz, int floorY) {
        for (int tx = wx - 3; tx <= wx + 3; tx++) {
            for (int tz = wz - 3; tz <= wz + 3; tz++) {
                if (!GladeTerrain.isTrunkSite(tx, tz)) continue;
                if (contribute(chunk, pos, wx, wz, tx, tz, floorY)) return true;
            }
        }
        return false;
    }

    private static boolean contribute(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int tx, int tz, int floorY) {
        int species = GladeTerrain.speciesAt(tx, tz);

        // If Dynamic Trees is installed, plant its sapling and let it grow.
        BlockState dtSapling = MazeCompat.sapling(species);
        if (dtSapling != null) {
            if (wx == tx && wz == tz) {
                int ground = floorY + GladeTerrain.heightAt(tx, tz);
                chunk.setBlockState(pos.set(wx, ground + 1, wz), dtSapling, false);
                return true;
            }
            return false; // DT grows the canopy itself
        }

        BlockState log = logFor(species);
        BlockState leaves = leavesFor(species);
        int ground = floorY + GladeTerrain.heightAt(tx, tz);
        int h = GladeTerrain.trunkHeight(tx, tz);
        int radius = species == GladeTerrain.DARK_OAK ? 3
            : species == GladeTerrain.BIRCH ? 1 : 2;

        int dx = wx - tx;
        int dz = wz - tz;

        if (dx == 0 && dz == 0) {
            for (int y = ground + 1; y <= ground + h; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), log, false);
            }
            chunk.setBlockState(pos.set(wx, ground + h + 1, wz), leaves, false);
            return true;
        }

        int ad = Math.max(Math.abs(dx), Math.abs(dz));
        if (ad > radius) return false;

        // Rounded canopy: widest at the top two trunk levels, tapering above.
        boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
        if (!corner) {
            for (int y = ground + h - 1; y <= ground + h; y++) {
                if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                    chunk.setBlockState(pos, leaves, false);
                }
            }
        }
        if (ad <= Math.max(1, radius - 1)
            && chunk.getBlockState(pos.set(wx, ground + h + 1, wz)).isAir()) {
            chunk.setBlockState(pos, leaves, false);
        }
        return false;
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
