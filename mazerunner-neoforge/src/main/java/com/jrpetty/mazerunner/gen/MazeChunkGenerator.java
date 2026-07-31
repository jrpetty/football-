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
import com.jrpetty.mazerunner.config.MazeWalls;
import com.jrpetty.mazerunner.config.Noise;
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
 * a 96×96-chunk square — floor at y60, unbreakable relief walls up to the y85
 * build limit (no ceiling) — with the 16×16-chunk Glade at the centre and
 * seven exit pads just outside the border. Everything beyond is void. Toggle
 * points, Glade doors and exit gaps generate CLOSED; the runtime opens them.
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

        int floorY = cfg.floorY;
        // A 16-block chunk can span several small cells, so decide per block.
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                int cx = Math.floorDiv(wx, cfg.cellSize);
                int cz = Math.floorDiv(wz, cfg.cellSize);
                if (!cfg.inGrid(cx, cz)) continue; // void outside the maze square

                chunk.setBlockState(pos.set(wx, cfg.bedrockY, wz), bedrock, false);

                if (cfg.inGlade(cx, cz)) {
                    if (MazeWalls.gladeProtrusion(cfg, wx, wz)) {
                        for (int y = cfg.bedrockY + 1; y <= floorY; y++) {
                            chunk.setBlockState(pos.set(wx, y, wz), dirt, false);
                        }
                        for (int y = cfg.wallBaseY; y <= cfg.wallTopY; y++) {
                            chunk.setBlockState(pos.set(wx, y, wz), WallPalette.stateAt(cfg, wx, y, wz), false);
                        }
                    } else {
                        GladeBuilder.column(cfg, chunk, pos, wx, wz);
                    }
                    continue;
                }

                for (int y = cfg.bedrockY + 1; y < floorY; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), dirt, false);
                }
                boolean floorOpen = MazeStructures.isOpen(cfg, wx, wz);
                chunk.setBlockState(pos.set(wx, floorY, wz), floorOpen ? grass : dirt, false);
                for (int y = cfg.wallBaseY; y <= cfg.wallTopY; y++) {
                    if (MazeWalls.solid(cfg, wx, y, wz)) {
                        chunk.setBlockState(pos.set(wx, y, wz), WallPalette.stateAt(cfg, wx, y, wz), false);
                    }
                }
            }
        }

        // Greenery pass — after all walls in the chunk exist (methods self-guard).
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                gladeWallCoat(chunk, pos, wx, wz);
                ivy(chunk, pos, wx, wz);
            }
        }

        for (ExitPad pad : pads) {
            pad.emit(cfg, chunk, pos);
        }
    }

    // ------------------------------------------------------------- ivy

    private static final int[] DX = { 0, 1, 0, -1 };
    private static final int[] DZ = { -1, 0, 1, 0 };

    /**
     * Cascading ivy on the wall faces bordering an open maze column: clustered
     * hanging strands, tapering, biome-tinted. Movable-segment faces stay bare
     * so nothing floats when doors, toggles or exit gates shift.
     */
    private void ivy(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int wx, int wz) {
        int cx = Math.floorDiv(wx, cfg.cellSize);
        int cz = Math.floorDiv(wz, cfg.cellSize);
        if (!cfg.inGrid(cx, cz) || cfg.inGlade(cx, cz)) return;
        if (MazeWalls.solid(cfg, wx, cfg.wallBaseY, wz)) return; // this column is wall, not air

        int flags = 0;
        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        for (int dir = 0; dir < 4; dir++) {
            int nx = wx + DX[dir];
            int nz = wz + DZ[dir];
            if (!isSolidWall(nx, nz)) continue;
            if (isMovableFace(nx, nz)) continue;
            boolean normalX = DX[dir] != 0;
            int run = normalX ? wz : wx;
            int line = normalX ? nx : nz;
            int[] span = WallStyle.ivySpan(run, line, cfg.wallBaseY, cfg.wallTopY);
            if (span == null) continue;
            flags |= faceBit(dir);
            start = Math.min(start, span[0]);
            end = Math.max(end, span[1]);
        }
        if (flags == 0) return;

        BlockState vine = vineState(flags);
        int cap = WallStyle.greeneryTopY(cfg.wallTopY); // leave the crest bare — no climbing out
        for (int y = Math.max(cfg.wallBaseY, start); y <= Math.min(cap, end); y++) {
            if (chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                chunk.setBlockState(pos, vine, false);
            }
        }
    }

    /**
     * Thick, natural overgrowth on the walls that enclose the Glade: mangrove
     * leaf bushes for volume with cascading vines filling around them, up most
     * of the wall, with organic gaps. Movable (door) faces stay bare.
     */
    private void gladeWallCoat(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int gx, int gz) {
        int cx = Math.floorDiv(gx, cfg.cellSize);
        int cz = Math.floorDiv(gz, cfg.cellSize);
        if (!cfg.inGlade(cx, cz)) return;
        if (MazeWalls.gladeProtrusion(cfg, gx, gz)) return; // this column is a wall panel

        int flags = 0;
        int run = 0;
        int line = 0;
        for (int dir = 0; dir < 4; dir++) {
            int nx = gx + DX[dir];
            int nz = gz + DZ[dir];
            if (!MazeWalls.solidWithGlade(cfg, nx, cfg.wallBaseY, nz)) continue;
            if (isMovableFace(nx, nz)) continue;
            boolean normalX = DX[dir] != 0;
            flags |= faceBit(dir);
            run = normalX ? gz : gx;
            line = normalX ? nx : nz;
        }
        if (flags == 0) return;

        double cover = Noise.fbm2(run, line * 7 + 11, 14, 0x9E15);
        if (cover < 0.12) return; // an occasional bare stretch of wall

        // Leaf clumps have full collision, so they stop below the crest too —
        // otherwise they'd form a staircase up the Glade wall.
        int topJ = WallStyle.greeneryTopY(cfg.wallTopY) - (int) (Noise.hash2(run, line, 0x3131) * 8);
        BlockState leaves = Blocks.MANGROVE_LEAVES.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        BlockState vine = vineState(flags);

        for (int y = cfg.wallBaseY; y <= topJ; y++) {
            if (!chunk.getBlockState(pos.set(gx, y, gz)).isAir()) continue;
            if (Noise.hash3(gx, y, gz, 0x0DA9) < 0.15) continue; // organic gaps
            double leaf = Noise.fbm2(run, y, 5, 0x51A7);
            if (leaf > 0.55 && cover > 0.4) {
                chunk.setBlockState(pos, leaves, false); // bushy clump
            } else {
                chunk.setBlockState(pos, vine, false);
            }
        }
    }

    private static int faceBit(int dir) {
        return switch (dir) {
            case 0 -> 1; // north
            case 1 -> 2; // east
            case 2 -> 4; // south
            default -> 8; // west
        };
    }

    /** Vanilla vine attached on the given faces (bit 1=N,2=E,4=S,8=W). */
    private static BlockState vineState(int flags) {
        return Blocks.VINE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.VineBlock.NORTH, (flags & 1) != 0)
            .setValue(net.minecraft.world.level.block.VineBlock.EAST, (flags & 2) != 0)
            .setValue(net.minecraft.world.level.block.VineBlock.SOUTH, (flags & 4) != 0)
            .setValue(net.minecraft.world.level.block.VineBlock.WEST, (flags & 8) != 0);
    }

    private boolean isSolidWall(int wallX, int wallZ) {
        return MazeWalls.solid(cfg, wallX, cfg.wallBaseY, wallZ);
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
        int max = cfg.gridCells * cfg.cellSize;
        int half = cfg.corridorWidth() / 2 + 1; // pad half-width around the corridor
        int depth = cfg.cellSize + 2;           // how far the platform reaches outward
        // Only the single fixed exit gets a pad and a portal.
        for (MazeConfigData.ExitDef exit : List.of(cfg.fixedExit())) {
            int midX = exit.cellX() * cfg.cellSize + cfg.cellSize / 2;
            int midZ = exit.cellZ() * cfg.cellSize + cfg.cellSize / 2;
            switch (exit.facing()) {
                case "north" -> pads.add(new ExitPad(exit.id(),
                    midX - half, -depth, midX + half, -1, exit.portalX(), exit.portalZ()));
                case "south" -> pads.add(new ExitPad(exit.id(),
                    midX - half, max, midX + half, max + depth - 1, exit.portalX(), exit.portalZ()));
                case "west" -> pads.add(new ExitPad(exit.id(),
                    -depth, midZ - half, -1, midZ + half, exit.portalX(), exit.portalZ()));
                default -> pads.add(new ExitPad(exit.id(),
                    max, midZ - half, max + depth - 1, midZ + half, exit.portalX(), exit.portalZ()));
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
        return 80; // matches the mazerunner:maze dimension type (min_y 0, height 80)
    }

    @Override
    public int getSeaLevel() {
        return cfg.floorY;
    }

    @Override
    public int getMinY() {
        return 0;
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
