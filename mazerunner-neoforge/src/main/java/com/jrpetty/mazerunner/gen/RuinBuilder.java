package com.jrpetty.mazerunner.gen;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeStructures;
import com.jrpetty.mazerunner.config.Noise;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.core.Direction;

/**
 * Procedural ruins inside plaza clearings. Each renderer only writes blocks
 * that fall inside the chunk being generated, so structures spanning chunk
 * borders assemble seamlessly. Chests and spawners are placed (and filled) by
 * the runtime on chunk load — the shells built here just leave room for them.
 */
final class RuinBuilder {

    private RuinBuilder() {}

    static void render(MazeConfigData cfg, MazeStructures.Plaza plaza, ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        switch (plaza.type()) {
            case MazeStructures.TYPE_SHELTER -> shelter(cfg, plaza, chunk, cp, pos);
            case MazeStructures.TYPE_PILLARS -> pillars(cfg, plaza, chunk, cp, pos);
            case MazeStructures.TYPE_DUNGEON -> dungeon(cfg, plaza, chunk, cp, pos);
            default -> tower(cfg, plaza, chunk, cp, pos);
        }
    }

    private static boolean inChunk(ChunkPos cp, int x, int z) {
        return x >= cp.getMinBlockX() && x <= cp.getMaxBlockX()
            && z >= cp.getMinBlockZ() && z <= cp.getMaxBlockZ();
    }

    private static void set(ChunkAccess chunk, ChunkPos cp, BlockPos.MutableBlockPos pos,
            int x, int y, int z, BlockState state) {
        if (inChunk(cp, x, z)) chunk.setBlockState(pos.set(x, y, z), state, false);
    }

    /** Weathered masonry mix for ruin walls. */
    private static BlockState masonry(int x, int y, int z) {
        double r = Noise.hash3(x, y, z, 0x8055EE);
        if (r < 0.35) return Blocks.COBBLESTONE.defaultBlockState();
        if (r < 0.60) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (r < 0.82) return Blocks.STONE_BRICKS.defaultBlockState();
        return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    }

    // ------------------------------------------------------------- shelter

    /** A roofless runner shelter: broken walls, a doorway, a lit campfire. */
    private static void shelter(MazeConfigData cfg, MazeStructures.Plaza plaza, ChunkAccess chunk,
            ChunkPos cp, BlockPos.MutableBlockPos pos) {
        int ax = plaza.anchorX();
        int az = plaza.anchorZ();
        int y0 = cfg.wallBaseY;
        for (int x = ax - 5; x <= ax + 5; x++) {
            for (int z = az - 4; z <= az + 4; z++) {
                boolean edge = x == ax - 5 || x == ax + 5 || z == az - 4 || z == az + 4;
                if (!edge) {
                    if (Noise.hash2(x, z, 0x501F) < 0.3) {
                        set(chunk, cp, pos, x, cfg.floorY, z, Blocks.COARSE_DIRT.defaultBlockState());
                    }
                    continue;
                }
                if (x >= ax - 1 && x <= ax && z == az - 4) continue; // doorway (north)
                int height = 1 + (int) (Noise.hash2(x, z, 0x77E1) * 3); // 1..3, ruined
                for (int y = y0; y < y0 + height; y++) {
                    set(chunk, cp, pos, x, y, z, masonry(x, y, z));
                }
            }
        }
        set(chunk, cp, pos, ax - 2, y0, az + 1, Blocks.CAMPFIRE.defaultBlockState());
    }

    // ------------------------------------------------------------- pillars

    /** A mossy court of broken tuff pillars. */
    private static void pillars(MazeConfigData cfg, MazeStructures.Plaza plaza, ChunkAccess chunk,
            ChunkPos cp, BlockPos.MutableBlockPos pos) {
        int ax = plaza.anchorX();
        int az = plaza.anchorZ();
        for (int x = ax - 9; x <= ax + 9; x++) {
            for (int z = az - 9; z <= az + 9; z++) {
                if (Noise.hash2(x, z, 0xF100) < 0.35) {
                    set(chunk, cp, pos, x, cfg.floorY, z,
                        Noise.hash2(x, z, 0x2E2E) < 0.5
                            ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                            : Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
                }
            }
        }
        for (int px = -1; px <= 1; px++) {
            for (int pz = -1; pz <= 1; pz++) {
                int x = ax + px * 7;
                int z = az + pz * 7;
                int height = 3 + (int) (Noise.hash2(x, z, 0xB0B0) * 6); // 3..8
                for (int y = cfg.wallBaseY; y < cfg.wallBaseY + height; y++) {
                    BlockState s = Noise.hash3(x, y, z, 0x7077) < 0.5
                        ? Blocks.TUFF.defaultBlockState()
                        : Blocks.POLISHED_TUFF.defaultBlockState();
                    set(chunk, cp, pos, x, y, z, s);
                }
            }
        }
    }

    // ------------------------------------------------------------- dungeon

    /** A surface ruin over a buried room: ladder shaft down, spawner and loot below. */
    private static void dungeon(MazeConfigData cfg, MazeStructures.Plaza plaza, ChunkAccess chunk,
            ChunkPos cp, BlockPos.MutableBlockPos pos) {
        int ax = plaza.anchorX();
        int az = plaza.anchorZ();
        int roomTop = cfg.floorY - 1;    // 59: ceiling slab
        int roomFloor = cfg.floorY - 4;  // 56: floor slab

        for (int x = ax - 5; x <= ax + 5; x++) {
            for (int z = az - 5; z <= az + 5; z++) {
                boolean edge = x == ax - 5 || x == ax + 5 || z == az - 5 || z == az + 5;
                for (int y = roomFloor; y <= roomTop; y++) {
                    BlockState s;
                    if (y == roomFloor || y == roomTop || edge) {
                        s = masonry(x, y, z);
                    } else {
                        s = Blocks.AIR.defaultBlockState(); // hollow interior (56+1..59-1 → y57..58)
                    }
                    set(chunk, cp, pos, x, y, z, s);
                }
            }
        }
        // interior air is y57..58; loot/spawner sit on the y56 floor (placed by runtime at y57)

        // shaft down: hole through the ceiling and surface, ladder on its north wall
        int sx = ax;
        int sz = az - 4;
        set(chunk, cp, pos, sx, cfg.floorY, sz, Blocks.AIR.defaultBlockState());
        set(chunk, cp, pos, sx, roomTop, sz, Blocks.AIR.defaultBlockState());
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int y = roomFloor + 1; y <= cfg.floorY; y++) {
            set(chunk, cp, pos, sx, y, sz - 1, masonry(sx, y, sz - 1)); // wall the ladder hangs on
            set(chunk, cp, pos, sx, y, sz, ladder);
        }
        // surface marker: broken rim around the hole
        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 } }) {
            if (Noise.hash2(sx + d[0], sz + d[1], 0xD1D1) < 0.7) {
                set(chunk, cp, pos, sx + d[0], cfg.wallBaseY, sz + d[1],
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------- tower

    /** A collapsed round watchtower with rubble spill. */
    private static void tower(MazeConfigData cfg, MazeStructures.Plaza plaza, ChunkAccess chunk,
            ChunkPos cp, BlockPos.MutableBlockPos pos) {
        int ax = plaza.anchorX();
        int az = plaza.anchorZ();
        for (int x = ax - 8; x <= ax + 8; x++) {
            for (int z = az - 8; z <= az + 8; z++) {
                double dist = Math.sqrt((x - ax) * (x - ax) + (z - az) * (z - az));
                if (dist >= 4.2 && dist <= 5.4) {
                    // broken ring wall: height varies around the circumference
                    double angle = Math.atan2(z - az, x - ax);
                    int height = 2 + (int) (Noise.value2(Math.cos(angle) * 40 + ax,
                        Math.sin(angle) * 40 + az, 11, 0x70E7) * 8);
                    if (angle > 0.3 && angle < 1.1) height = 0; // collapsed doorway gap
                    for (int y = cfg.wallBaseY; y < cfg.wallBaseY + height; y++) {
                        set(chunk, cp, pos, x, y, z, masonry(x, y, z));
                    }
                } else if (dist < 4.2 && Noise.hash2(x, z, 0x5EED) < 0.25) {
                    set(chunk, cp, pos, x, cfg.floorY, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                } else if (dist > 5.4 && dist < 8 && Noise.hash2(x, z, 0x60CB) < 0.12) {
                    set(chunk, cp, pos, x, cfg.wallBaseY, z, Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
    }
}
