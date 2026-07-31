package com.jrpetty.mazerunner.gen;

import java.util.List;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeLandmarks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Turns {@link MazeLandmarks} placement decisions into blocks. Each landmark
 * fills a single corridor column, so the corridor always stays walkable, and
 * carries its section's accent colour — every landmark in the same eighth of
 * the maze shares a hue, giving runners something to navigate by.
 */
final class LandmarkBuilder {

    /** One weathered accent per compass section. */
    private static final List<BlockState> ACCENTS = List.of(
        Blocks.RED_TERRACOTTA.defaultBlockState(),
        Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
        Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
        Blocks.LIME_TERRACOTTA.defaultBlockState(),
        Blocks.CYAN_TERRACOTTA.defaultBlockState(),
        Blocks.BLUE_TERRACOTTA.defaultBlockState(),
        Blocks.PURPLE_TERRACOTTA.defaultBlockState(),
        Blocks.WHITE_TERRACOTTA.defaultBlockState());

    private LandmarkBuilder() {}

    /** Emits whatever landmark occupies this column, if any. */
    static void emit(MazeConfigData cfg, ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            int wx, int wz, int floorY) {
        int type = MazeLandmarks.typeFor(cfg, wx, wz);
        if (type == MazeLandmarks.NONE) return;

        BlockState accent = ACCENTS.get(MazeLandmarks.accentFor(cfg, wx, wz));
        switch (type) {
            // A runner who didn't make it back.
            case MazeLandmarks.BONES -> {
                chunk.setBlockState(pos.set(wx, floorY, wz), accent, false);
                chunk.setBlockState(pos.set(wx, floorY + 1, wz),
                    Blocks.BONE_BLOCK.defaultBlockState(), false);
            }
            // Something has been sitting here a long time.
            case MazeLandmarks.WEBS -> {
                chunk.setBlockState(pos.set(wx, floorY + 1, wz),
                    Blocks.COBWEB.defaultBlockState(), false);
                chunk.setBlockState(pos.set(wx, floorY + 2, wz),
                    Blocks.COBWEB.defaultBlockState(), false);
            }
            // A light left burning by whoever came before.
            case MazeLandmarks.LANTERN -> {
                chunk.setBlockState(pos.set(wx, floorY, wz), accent, false);
                chunk.setBlockState(pos.set(wx, floorY + 1, wz),
                    Blocks.LANTERN.defaultBlockState(), false);
            }
            // The Maze grinding itself apart.
            case MazeLandmarks.RUBBLE -> {
                chunk.setBlockState(pos.set(wx, floorY, wz),
                    Blocks.COBBLESTONE.defaultBlockState(), false);
                chunk.setBlockState(pos.set(wx, floorY + 1, wz),
                    Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(), false);
            }
            // A deliberate marker — the tallest, most visible landmark.
            case MazeLandmarks.CAIRN -> {
                chunk.setBlockState(pos.set(wx, floorY + 1, wz), accent, false);
                chunk.setBlockState(pos.set(wx, floorY + 2, wz), accent, false);
                chunk.setBlockState(pos.set(wx, floorY + 3, wz),
                    Blocks.TORCH.defaultBlockState(), false);
            }
            default -> { }
        }
    }
}
