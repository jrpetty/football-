package com.jrpetty.mazerunner.gen;

import com.jrpetty.mazerunner.config.GladeTerrain;
import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.Noise;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Renders the Glade's natural terrain: flowing hills (up to +5), the sandy
 * lake in the southwest, the forest quarter in the northeast, meadow foliage,
 * and the (non-functional, movie-style) Box elevator at the exact centre.
 * Everything is a pure function of block coordinates, so each chunk renders
 * its own slice independently.
 */
final class GladeBuilder {

    private GladeBuilder() {}

    /** Renders the full column at (wx, wz), from bedrock support to foliage. */
    static void column(MazeConfigData cfg, ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz) {
        int floorY = cfg.floorY; // 60

        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        if (GladeTerrain.inLake(wx, wz)) {
            int depth = GladeTerrain.lakeDepth(wx, wz);
            int bedY = floorY - depth;
            BlockState bed = GladeTerrain.isSandy(wx, wz) ? sand : dirt;
            for (int y = floorY - 4; y < bedY; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), dirt, false);
            }
            chunk.setBlockState(pos.set(wx, bedY, wz), bed, false);
            for (int y = bedY + 1; y <= floorY; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), water, false);
            }
            return;
        }

        int raise = GladeTerrain.heightAt(wx, wz);
        int surfaceY = floorY + raise;
        boolean sandyShore = GladeTerrain.isSandy(wx, wz) && raise == 0;
        for (int y = floorY - 4; y < surfaceY; y++) {
            chunk.setBlockState(pos.set(wx, y, wz), dirt, false);
        }
        chunk.setBlockState(pos.set(wx, surfaceY, wz), sandyShore ? sand : grass, false);

        if (elevatorColumn(chunk, pos, wx, wz, floorY)) return;

        boolean forest = GladeTerrain.inForest(wx, wz);
        if (forest && emitTreeBlocks(chunk, pos, wx, wz, floorY)) return;

        // Meadow foliage on grass only.
        if (sandyShore) return;
        int flower = GladeTerrain.flowerAt(wx, wz);
        if (flower > 0) {
            BlockState state = switch (flower) {
                case 1 -> Blocks.POPPY.defaultBlockState();
                case 2 -> Blocks.DANDELION.defaultBlockState();
                case 3 -> Blocks.OXEYE_DAISY.defaultBlockState();
                case 4 -> Blocks.CORNFLOWER.defaultBlockState();
                default -> Blocks.AZURE_BLUET.defaultBlockState();
            };
            chunk.setBlockState(pos.set(wx, surfaceY + 1, wz), state, false);
        } else if (GladeTerrain.grassAt(wx, wz, forest)) {
            BlockState cover = forest && Noise.hash2(wx, wz, 0xFE12) < 0.4
                ? Blocks.FERN.defaultBlockState()
                : Blocks.SHORT_GRASS.defaultBlockState();
            chunk.setBlockState(pos.set(wx, surfaceY + 1, wz), cover, false);
        } else if (GladeTerrain.tallGrassAt(wx, wz)) {
            // two-part plant: lower + upper halves
            chunk.setBlockState(pos.set(wx, surfaceY + 1, wz), Blocks.TALL_GRASS.defaultBlockState(), false);
            chunk.setBlockState(pos.set(wx, surfaceY + 2, wz),
                Blocks.TALL_GRASS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), false);
        }
    }

    // ------------------------------------------------------------- elevator

    /**
     * The Box: a 5×5 iron platform with a metal grate, caged corners of iron
     * bars, and a cable of chains rising from the middle — the movie elevator,
     * purely decorative. Returns true if this column belongs to it.
     */
    private static boolean elevatorColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int floorY) {
        int c = GladeTerrain.CENTER;
        int dx = wx - c;
        int dz = wz - c;
        int ad = Math.max(Math.abs(dx), Math.abs(dz));
        if (ad > 2) return false;

        chunk.setBlockState(pos.set(wx, floorY, wz), Blocks.IRON_BLOCK.defaultBlockState(), false);

        if (ad == 2) {
            boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
            if (corner) {
                for (int y = floorY + 1; y <= floorY + 4; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), Blocks.IRON_BLOCK.defaultBlockState(), false);
                }
            } else if (dz != -2 || Math.abs(dx) > 1) {
                // bars around the rim, leaving a 3-wide opening on the north side
                for (int y = floorY + 1; y <= floorY + 3; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), Blocks.IRON_BARS.defaultBlockState(), false);
                }
                chunk.setBlockState(pos.set(wx, floorY + 4, wz), Blocks.IRON_BLOCK.defaultBlockState(), false);
            }
            return true;
        }

        // interior: metal grate underfoot, the cable overhead at dead centre
        chunk.setBlockState(pos.set(wx, floorY + 1, wz),
            Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState(), false);
        if (dx == 0 && dz == 0) {
            for (int y = floorY + 3; y <= floorY + 8; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.CHAIN.defaultBlockState(), false);
            }
        }
        return true;
    }

    // ------------------------------------------------------------- trees

    /** Oak/birch forest on a jittered lattice; heights follow the terrain. Returns true if trunk here. */
    private static boolean emitTreeBlocks(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int floorY) {
        for (int tx = wx - 2; tx <= wx + 2; tx++) {
            for (int tz = wz - 2; tz <= wz + 2; tz++) {
                if (!GladeTerrain.isTrunkSite(tx, tz)) continue;
                boolean birch = GladeTerrain.isBirch(tx, tz);
                BlockState log = (birch ? Blocks.BIRCH_LOG : Blocks.OAK_LOG).defaultBlockState();
                BlockState leaves = (birch ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES)
                    .defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
                int ground = floorY + GladeTerrain.heightAt(tx, tz);
                int h = GladeTerrain.trunkHeight(tx, tz);
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
                int wide = birch ? 1 : 2;
                if (ad <= wide && !(Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
                    for (int y = ground + h - 1; y <= ground + h; y++) {
                        if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                            chunk.setBlockState(pos, leaves, false);
                        }
                    }
                }
                if (ad <= 1 && chunk.getBlockState(pos.set(wx, ground + h + 1, wz)).isAir()) {
                    chunk.setBlockState(pos, leaves, false);
                }
            }
        }
        return false;
    }
}
