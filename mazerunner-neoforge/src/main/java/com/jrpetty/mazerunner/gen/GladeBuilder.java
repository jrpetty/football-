package com.jrpetty.mazerunner.gen;

import com.jrpetty.mazerunner.config.MazeConfigData;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Procedural stand-in for the hand-authored Glade until real builds land:
 * the Box (spawn platform) at the centre, the lake and the Deadheads
 * forest/graveyard in the southwest, and grassland everywhere else.
 * Deterministic per block, so every chunk renders its own slice.
 */
final class GladeBuilder {

    // The Glade spans blocks 640..895; centre (768, 768).
    private static final int BOX_MIN = 764;
    private static final int BOX_MAX = 771;
    private static final int LAKE_CX = 785;
    private static final int LAKE_CZ = 845;
    private static final double LAKE_RX = 45;
    private static final double LAKE_RZ = 26;
    private static final int FOREST_X0 = 650, FOREST_X1 = 760;
    private static final int FOREST_Z0 = 795, FOREST_Z1 = 890;
    private static final int GRAVE_X0 = 655, GRAVE_X1 = 700;
    private static final int GRAVE_Z0 = 855, GRAVE_Z1 = 885;

    private GladeBuilder() {}

    /** 0..99 deterministic per-position hash. */
    private static int hash(int x, int z) {
        int h = x * 0x9E3779B1 ^ z * 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0x27D4EB2F;
        h ^= h >>> 15;
        return Math.floorMod(h, 100);
    }

    private static boolean inLake(int x, int z, double margin) {
        double dx = (x - LAKE_CX) / (LAKE_RX + margin);
        double dz = (z - LAKE_CZ) / (LAKE_RZ + margin);
        return dx * dx + dz * dz <= 1.0;
    }

    static void column(MazeConfigData cfg, ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz) {
        int floorY = cfg.floorY;

        // The lake — dug two deep, water to the brim.
        if (inLake(wx, wz, 0)) {
            chunk.setBlockState(pos.set(wx, floorY - 3, wz), Blocks.DIRT.defaultBlockState(), false);
            BlockState water = Blocks.WATER.defaultBlockState();
            for (int y = floorY - 2; y <= floorY; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), water, false);
            }
            return;
        }

        // The Box — the spawn platform at the exact centre of the Glade.
        if (wx >= BOX_MIN && wx <= BOX_MAX && wz >= BOX_MIN && wz <= BOX_MAX) {
            chunk.setBlockState(pos.set(wx, floorY, wz), Blocks.IRON_BLOCK.defaultBlockState(), false);
            return;
        }
        boolean pillarX = wx == BOX_MIN - 2 || wx == BOX_MAX + 2;
        boolean pillarZ = wz == BOX_MIN - 2 || wz == BOX_MAX + 2;
        if (pillarX && pillarZ) {
            BlockState pillar = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), pillar, false);
            }
            chunk.setBlockState(pos.set(wx, floorY + 4, wz), Blocks.TORCH.defaultBlockState(), false);
            return;
        }

        // The Deadheads — forest + graveyard, southwest, hugging the lake.
        boolean inForest = wx >= FOREST_X0 && wx <= FOREST_X1 && wz >= FOREST_Z0 && wz <= FOREST_Z1
            && !inLake(wx, wz, 2);
        if (inForest) {
            emitTreeBlocks(chunk, pos, wx, wz, floorY);
            if (wx >= GRAVE_X0 && wx <= GRAVE_X1 && wz >= GRAVE_Z0 && wz <= GRAVE_Z1
                && wx % 5 == 2 && wz % 5 == 2 && hash(wx, wz) < 30 && !isTrunk(wx, wz)) {
                BlockState grave = hash(wx + 1, wz) < 50
                    ? Blocks.COBBLESTONE_WALL.defaultBlockState()
                    : Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState();
                chunk.setBlockState(pos.set(wx, floorY + 1, wz), grave, false);
            }
            return;
        }

        // Grasslands — sparse tufts.
        if (hash(wx, wz) < 5) {
            chunk.setBlockState(pos.set(wx, floorY + 1, wz), Blocks.SHORT_GRASS.defaultBlockState(), false);
        }
    }

    // ------------------------------------------------------------- trees

    // Tree trunks stand on a 6-block lattice (coords ≡ 3 mod 6), thinned by hash.
    private static boolean isTrunkSite(int tx, int tz) {
        if (Math.floorMod(tx, 6) != 3 || Math.floorMod(tz, 6) != 3) return false;
        if (tx < FOREST_X0 + 2 || tx > FOREST_X1 - 2 || tz < FOREST_Z0 + 2 || tz > FOREST_Z1 - 2) return false;
        if (inLake(tx, tz, 3)) return false;
        return hash(tx * 7 + 1, tz * 7 + 3) < 45;
    }

    private static boolean isTrunk(int wx, int wz) {
        return isTrunkSite(wx, wz);
    }

    private static int trunkHeight(int tx, int tz) {
        return 4 + hash(tx, tz * 3 + 1) % 2; // 4..5
    }

    /** Emits any log/leaf blocks that trees within 2 blocks contribute to this column. */
    private static void emitTreeBlocks(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int floorY) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

        for (int tx = wx - 2; tx <= wx + 2; tx++) {
            for (int tz = wz - 2; tz <= wz + 2; tz++) {
                if (!isTrunkSite(tx, tz)) continue;
                int h = trunkHeight(tx, tz);
                int dx = wx - tx;
                int dz = wz - tz;
                if (dx == 0 && dz == 0) {
                    for (int y = floorY + 1; y <= floorY + h; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), log, false);
                    }
                    chunk.setBlockState(pos.set(wx, floorY + h + 1, wz), leaves, false);
                    return;
                }
                // canopy: radius 2 at the top two trunk levels, radius 1 above
                int ad = Math.max(Math.abs(dx), Math.abs(dz));
                if (ad <= 2 && !(Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
                    for (int y = floorY + h - 1; y <= floorY + h; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), leaves, false);
                    }
                }
                if (ad <= 1) {
                    chunk.setBlockState(pos.set(wx, floorY + h + 1, wz), leaves, false);
                }
            }
        }
    }
}
