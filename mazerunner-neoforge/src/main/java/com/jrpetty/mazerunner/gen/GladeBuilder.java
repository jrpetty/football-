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
 * Renders the Glade's natural terrain: flowing hills, an irregular sandy/muddy
 * lake with a mixed bed, an oak/birch/dark-oak/apple forest, meadow foliage,
 * and the movie-style Box elevator at the centre (which is also world spawn).
 * Each column renders independently, so chunks assemble seamlessly.
 */
final class GladeBuilder {

    private GladeBuilder() {}

    static void column(MazeConfigData cfg, ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz) {
        int floorY = cfg.floorY;

        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        if (GladeTerrain.inLake(wx, wz)) {
            int depth = GladeTerrain.lakeDepth(wx, wz);
            int bedY = floorY - depth;
            BlockState bed = switch (GladeTerrain.bedMaterial(wx, wz)) {
                case GladeTerrain.BED_SAND -> sand;
                case GladeTerrain.BED_CLAY -> Blocks.CLAY.defaultBlockState();
                case GladeTerrain.BED_GRAVEL -> Blocks.GRAVEL.defaultBlockState();
                default -> dirt;
            };
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
        if (forest && TreePlacer.emit(chunk, pos, wx, wz, floorY)) return;

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
            chunk.setBlockState(pos.set(wx, surfaceY + 1, wz), Blocks.TALL_GRASS.defaultBlockState(), false);
            chunk.setBlockState(pos.set(wx, surfaceY + 2, wz),
                Blocks.TALL_GRASS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), false);
        }
    }

    // ------------------------------------------------------------- elevator

    /**
     * The Box: a 5×5 iron platform with a grate floor, an iron-bar cage with a
     * 3-wide north opening, corner posts, and a chain cable rising from the
     * centre (with head-room for the spawning player). Decorative only.
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
                chunk.setBlockState(pos.set(wx, floorY + 5, wz), Blocks.LANTERN.defaultBlockState(), false);
            } else if (!(dz == -2 && Math.abs(dx) <= 1)) { // leave a 3-wide north doorway
                for (int y = floorY + 1; y <= floorY + 3; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), Blocks.IRON_BARS.defaultBlockState(), false);
                }
                chunk.setBlockState(pos.set(wx, floorY + 4, wz), Blocks.IRON_BLOCK.defaultBlockState(), false);
            }
            return true;
        }

        // interior: metal grate underfoot; the cable hangs from the ceiling with head-room
        chunk.setBlockState(pos.set(wx, floorY + 1, wz),
            Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState(), false);
        if (dx == 0 && dz == 0) {
            for (int y = floorY + 4; y <= floorY + 7; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.CHAIN.defaultBlockState(), false);
            }
        }
        return true;
    }
}
