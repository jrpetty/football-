package com.jrpetty.mazerunner.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jrpetty.mazerunner.ModBlocks;
import com.jrpetty.mazerunner.ModWorldgen;
import com.jrpetty.mazerunner.WallPalette;
import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeConfigs;
import com.jrpetty.mazerunner.config.MazeStructures;
import com.jrpetty.mazerunner.config.WallStyle;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.server.level.WorldGenRegion;

/**
 * Generates the authored Maze Runner world from {@code maze_config_v2.json}:
 * a 96×96-chunk square — floor at y60, unbreakable walls y61..100, a barrier
 * ceiling at y101 — with the 16×16-chunk Glade at the centre and seven exit
 * pads just outside the border. Everything beyond is void. Toggle points,
 * Glade doors and exit gaps generate CLOSED; the runtime opens them per day.
 */
public class MazeChunkGenerator extends ChunkGenerator {

    public static final MapCodec<MazeChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            RegistryOps.retrieveElement(ModWorldgen.GLADE_BIOME)
        ).apply(instance, instance.stable(MazeChunkGenerator::new)));

    private final MazeConfigData cfg;
    private final List<ExitPad> pads;

    public MazeChunkGenerator(Holder.Reference<Biome> biome) {
        super(new FixedBiomeSource(biome));
        this.cfg = MazeConfigs.get();
        this.pads = buildPads(cfg);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ------------------------------------------------------------- terrain

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {
        buildChunk(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    private void buildChunk(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState barrier = Blocks.BARRIER.defaultBlockState();

        boolean inGrid = cfg.inGrid(cp.x, cp.z);

        if (inGrid) {
            int floorY = cfg.floorY;
            boolean glade = cfg.inGlade(cp.x, cp.z);
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = cp.getMinBlockX() + lx;
                    int wz = cp.getMinBlockZ() + lz;
                    chunk.setBlockState(pos.set(wx, floorY - 5, wz), bedrock, false);

                    boolean open = MazeStructures.isOpen(cfg, wx, wz);
                    if (glade) {
                        GladeBuilder.column(cfg, chunk, pos, wx, wz);
                    } else {
                        for (int y = floorY - 4; y < floorY; y++) {
                            chunk.setBlockState(pos.set(wx, y, wz), dirt, false);
                        }
                        chunk.setBlockState(pos.set(wx, floorY, wz), open ? grass : dirt, false);
                        if (!open) {
                            for (int y = cfg.wallBaseY; y <= cfg.wallTopY; y++) {
                                chunk.setBlockState(pos.set(wx, y, wz),
                                    WallPalette.stateAt(cfg, wx, y, wz), false);
                            }
                        }
                    }

                    if (open) greenery(chunk, pos, wx, wz);
                    chunk.setBlockState(pos.set(wx, cfg.wallTopY + 1, wz), barrier, false);
                }
            }

            // A plaza ruin overlapping this chunk renders its slice here.
            MazeStructures.Plaza plaza = MazeStructures.plazaAtCell(cfg, cp.x, cp.z);
            if (plaza != null) RuinBuilder.render(cfg, plaza, chunk);
        }

        // Exit pads live just outside the grid (and their barrier roof).
        for (ExitPad pad : pads) {
            pad.emit(cfg, chunk, pos);
        }
    }

    // ------------------------------------------------------------- greenery

    private static final int[] DX = { 0, 1, 0, -1 };
    private static final int[] DZ = { -1, 0, 1, 0 };

    /**
     * Ivy runs and mangrove-moss clumps on the wall faces bordering this open
     * column — noticeable, never a full coat. Faces of movable segments
     * (toggles, doors, exit gates) stay bare so nothing floats when they move.
     */
    private void greenery(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int wx, int wz) {
        for (int dir = 0; dir < 4; dir++) {
            int nx = wx + DX[dir];
            int nz = wz + DZ[dir];
            if (!cfg.inGrid(Math.floorDiv(nx, cfg.cellSize), Math.floorDiv(nz, cfg.cellSize))) continue;
            if (MazeStructures.isOpen(cfg, nx, nz)) continue; // neighbour isn't a wall
            if (isMovableFace(nx, nz)) continue;

            int[] moss = WallStyle.mossClump(nx, nz, dir);
            if (moss != null) {
                BlockState leaves = Blocks.MANGROVE_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                int start = cfg.wallBaseY + moss[0];
                for (int y = start; y < start + moss[1] && y <= cfg.wallTopY; y++) {
                    if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                        chunk.setBlockState(pos, leaves, false);
                    }
                }
            }

            int[] run = WallStyle.vineRun(nx, nz, dir);
            if (run != null) {
                BlockState vine = ModBlocks.MAZE_VINE.get().defaultBlockState()
                    .setValue(vineFace(dir), true);
                int start = cfg.wallBaseY + run[0];
                for (int y = start; y < start + run[1] && y <= cfg.wallTopY; y++) {
                    if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                        chunk.setBlockState(pos, vine, false);
                    }
                }
            }
        }
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty vineFace(int dir) {
        return switch (dir) {
            case 0 -> net.minecraft.world.level.block.VineBlock.NORTH;
            case 1 -> net.minecraft.world.level.block.VineBlock.EAST;
            case 2 -> net.minecraft.world.level.block.VineBlock.SOUTH;
            default -> net.minecraft.world.level.block.VineBlock.WEST;
        };
    }

    /** True if the wall column belongs to a toggle point, Glade door, or exit gate. */
    private boolean isMovableFace(int wallX, int wallZ) {
        for (MazeConfigData.StateBox box : cfg.boxesIn(wallX >> 4, wallZ >> 4)) {
            if (wallX >= box.box().x0() && wallX <= box.box().x1()
                && wallZ >= box.box().z0() && wallZ <= box.box().z1()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- exit pads

    /** The escape platform outside the border wall in front of one exit. */
    record ExitPad(String exitId, int x0, int z0, int x1, int z1, int portalX, int portalZ) {

        void emit(MazeConfigData cfg, ChunkAccess chunk, BlockPos.MutableBlockPos pos) {
            ChunkPos cp = chunk.getPos();
            int cx0 = Math.max(x0, cp.getMinBlockX());
            int cx1 = Math.min(x1, cp.getMaxBlockX());
            int cz0 = Math.max(z0, cp.getMinBlockZ());
            int cz1 = Math.min(z1, cp.getMaxBlockZ());
            if (cx0 > cx1 || cz0 > cz1) return;

            BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
            BlockState barrier = Blocks.BARRIER.defaultBlockState();
            int floorY = cfg.floorY + 1; // pad floor one step above the maze floor

            for (int x = cx0; x <= cx1; x++) {
                for (int z = cz0; z <= cz1; z++) {
                    chunk.setBlockState(pos.set(x, floorY - 1, z), stone, false);
                    chunk.setBlockState(pos.set(x, floorY, z), stone, false);
                    // rim wall on the pad's outer edges (the maze-facing side stays open)
                    boolean gridSide = closestToGrid(cfg, x, z);
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (edge && !gridSide) {
                        for (int y = floorY + 1; y <= floorY + 4; y++) {
                            chunk.setBlockState(pos.set(x, y, z), WallPalette.stateAt(cfg, x, y, z), false);
                        }
                    }
                    chunk.setBlockState(pos.set(x, cfg.wallTopY + 1, z), barrier, false);
                }
            }

            if (portalX >= cx0 && portalX <= cx1 && portalZ >= cz0 && portalZ <= cz1) {
                chunk.setBlockState(pos.set(portalX, floorY + 1, portalZ),
                    ModBlocks.EXIT_PORTAL.get().defaultBlockState(), false);
            }
        }

        private boolean closestToGrid(MazeConfigData cfg, int x, int z) {
            int max = cfg.gridCells * cfg.cellSize - 1;
            if (z1 < 0) return z == z1;          // pad north of grid → south edge faces maze
            if (z0 > max) return z == z0;        // pad south of grid → north edge
            if (x1 < 0) return x == x1;          // west pad → east edge
            return x == x0;                      // east pad → west edge
        }
    }

    private static List<ExitPad> buildPads(MazeConfigData cfg) {
        List<ExitPad> pads = new ArrayList<>();
        int max = cfg.gridCells * cfg.cellSize; // 1536
        for (MazeConfigData.ExitDef exit : cfg.exits.values()) {
            int stripX0 = exit.cellX() * cfg.cellSize + 4;
            int stripZ0 = exit.cellZ() * cfg.cellSize + 4;
            switch (exit.facing()) {
                case "north" -> pads.add(new ExitPad(exit.id(),
                    stripX0 - 3, -10, stripX0 + 10, -1, exit.portalX(), exit.portalZ()));
                case "south" -> pads.add(new ExitPad(exit.id(),
                    stripX0 - 3, max, stripX0 + 10, max + 9, exit.portalX(), exit.portalZ()));
                case "west" -> pads.add(new ExitPad(exit.id(),
                    -10, stripZ0 - 3, -1, stripZ0 + 10, exit.portalX(), exit.portalZ()));
                default -> pads.add(new ExitPad(exit.id(),
                    max, stripZ0 - 3, max + 9, stripZ0 + 10, exit.portalX(), exit.portalZ()));
            }
        }
        return pads;
    }

    // ------------------------------------------------------------- inert steps

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
            BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk,
            GenerationStep.Carving step) {}

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
            RandomState randomState, ChunkAccess chunk) {}

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
            StructureManager structureManager) {}

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    // ------------------------------------------------------------- shape info

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        return cfg.floorY;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
            RandomState randomState) {
        if (!cfg.inGrid(Math.floorDiv(x, cfg.cellSize), Math.floorDiv(z, cfg.cellSize))) {
            return level.getMinBuildHeight();
        }
        return MazeStructures.isOpen(cfg, x, z) ? cfg.floorY + 1 : cfg.wallTopY + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        int minY = level.getMinBuildHeight();
        BlockState[] states = new BlockState[cfg.floorY - minY + 1];
        for (int i = 0; i < states.length; i++) {
            states[i] = Blocks.STONE.defaultBlockState();
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Maze Runner section " + cfg.sectionOf(pos.getX(), pos.getZ()));
    }
}
