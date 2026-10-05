package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The ground made ready for a founding, a column at a time: read from the world into the plan
 * (village/FoundingPlan, which decides what the ground is to be), and then cut down, built up,
 * cleared and dressed to it.
 *
 * <p>Only what the world put there is ever touched: earth and rock, what grows on them, and
 * water. Anything else in a column, a single plank or a torch, is somebody's building, and
 * the column is left exactly as it is — read so when the ground is walked, and looked at
 * again the moment before a block is moved, in case somebody built there since.
 *
 * <p>The blocks are set without telling their neighbours (the levelled ground is all moved
 * at once, and a hundred thousand neighbour updates would be the whole of the cost), except
 * a felled tree's logs, so the leaves left hanging off the edge of the work know to fall.
 */
public final class Terraform {

    private Terraform() {}

    /** Sent to watching players, without the neighbours' shapes worked out again. */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // ------------------------------------------------------------------ what the world put there

    /** Earth and rock, as the world made it. */
    public static boolean earth(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
            || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.NYLIUM)
            || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.SUSPICIOUS_GRAVEL) || s.is(Blocks.CLAY)
            || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SNOW_BLOCK)
            || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)
            || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.SMOOTH_BASALT)
            || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL) || s.is(Blocks.MAGMA_BLOCK)
            || s.is(Blocks.END_STONE) || s.is(Blocks.BEDROCK);
    }

    /** What grows on it, and lies on it: trees, plants, mushrooms, snow. Cleared off the levelled ground. */
    public static boolean growth(BlockState s) {
        if (s.is(BlockTags.LEAVES)) {
            // Leaves somebody set are part of what they built; a tree's are not.
            return !s.hasProperty(LeavesBlock.PERSISTENT) || !s.getValue(LeavesBlock.PERSISTENT);
        }
        if (s.is(BlockTags.LOGS)) {
            // A tree grows logs; stripped logs and bark all round are what a builder makes of them.
            String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            return !path.startsWith("stripped_") && !path.endsWith("_wood") && !path.endsWith("_hyphae");
        }
        return s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
            || s.is(Blocks.VINE) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST) || s.is(Blocks.BAMBOO)
            || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.SNOW) || s.is(Blocks.MOSS_CARPET)
            || s.is(Blocks.AZALEA) || s.is(Blocks.FLOWERING_AZALEA) || s.is(Blocks.MANGROVE_ROOTS)
            || s.is(Blocks.MANGROVE_PROPAGULE) || s.is(Blocks.BIG_DRIPLEAF) || s.is(Blocks.BIG_DRIPLEAF_STEM)
            || s.is(Blocks.SMALL_DRIPLEAF) || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.HANGING_ROOTS)
            || s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT) || s.is(Blocks.SPORE_BLOSSOM)
            || s.is(Blocks.CRIMSON_ROOTS) || s.is(Blocks.WARPED_ROOTS) || s.is(Blocks.NETHER_SPROUTS)
            || s.is(Blocks.WEEPING_VINES) || s.is(Blocks.WEEPING_VINES_PLANT) || s.is(Blocks.TWISTING_VINES)
            || s.is(Blocks.TWISTING_VINES_PLANT) || s.is(Blocks.SHROOMLIGHT) || s.is(Blocks.NETHER_WART_BLOCK)
            || s.is(Blocks.WARPED_WART_BLOCK) || s.is(Blocks.CHORUS_PLANT) || s.is(Blocks.CHORUS_FLOWER)
            || s.getBlock() instanceof net.minecraft.world.level.block.BushBlock
            || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /** Water or lava, what grows in it, and the ice on a pond. (A slab or a stair standing in water is built.) */
    static boolean wet(BlockState s) {
        if (s.is(Blocks.ICE) || s.is(Blocks.FROSTED_ICE)) return true;
        if (s.getFluidState().isEmpty()) return false;
        return s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock || s.is(Blocks.SEAGRASS)
            || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.BUBBLE_COLUMN)
            || s.is(Blocks.SEA_PICKLE) || s.is(BlockTags.CORALS) || s.is(BlockTags.WALL_CORALS);
    }

    /** Anything at all the world put there, and so never something somebody built. */
    public static boolean natural(BlockState s) {
        return s.isAir() || growth(s) || earth(s) || wet(s);
    }

    // ------------------------------------------------------------------ the surface

    /**
     * The tops a column can have, as the plan keeps them (an index into this). Rock stands for
     * every kind of stone: a face cut into it is left the stone it is.
     */
    private static final Block[] TOPS = {
        Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.COARSE_DIRT, Blocks.DIRT,
        Blocks.SAND, Blocks.RED_SAND, Blocks.SNOW_BLOCK, Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.GRAVEL,
        Blocks.TERRACOTTA, Blocks.CLAY, Blocks.END_STONE, Blocks.NETHERRACK, Blocks.SOUL_SOIL,
        Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.STONE };
    /** The index of rock in {@link #TOPS}. */
    static final byte ROCK = (byte) (TOPS.length - 1);

    /** Which of the tops this block is (0 if it is none of them). */
    static byte topOf(BlockState s) {
        if (s.is(Blocks.ROOTED_DIRT)) return 5;
        if (s.is(Blocks.SOUL_SAND)) return 16;
        if (s.is(BlockTags.TERRACOTTA) || s.is(Blocks.RED_SANDSTONE)) return 12;
        for (byte i = 1; i < ROCK; i++) if (s.is(TOPS[i])) return i;
        return earth(s) ? ROCK : 0;
    }

    /** The block for an index of {@link #TOPS}. */
    static Block top(byte i) {
        return i <= 0 || i >= TOPS.length ? Blocks.GRASS_BLOCK : TOPS[i];
    }

    /** By the block's name, as a founding keeps it. */
    static Block byName(String name, Block otherwise) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return otherwise;
        return BuiltInRegistries.BLOCK.get(id);
    }

    static String name(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).toString();
    }

    /**
     * The surface the town's ground is dressed in: whichever soil most of the dry ground in the
     * levelled square was (grass on the plains, sand in the desert, podzol in the pine woods),
     * and grass where it was all rock — the folk are going to farm it.
     */
    static Block dominant(Level level, FoundingPlan.Ground g, byte[] tops, int radius) {
        int[] count = new int[TOPS.length];
        for (int i = 0; i < tops.length; i++) {
            if (g.kind[i] != FoundingPlan.LAND || FoundingPlan.reach(g.dx(i), g.dz(i)) > radius) continue;
            if (tops[i] > 0 && tops[i] < ROCK) count[tops[i]]++;
        }
        int best = 0;
        for (int i = 1; i < ROCK; i++) if (count[i] > count[best]) best = i;
        if (best > 0) return TOPS[best] == Blocks.DIRT ? Blocks.GRASS_BLOCK : TOPS[best];
        if (level.dimension() == Level.NETHER) return Blocks.NETHERRACK;
        if (level.dimension() == Level.END) return Blocks.END_STONE;
        return Blocks.GRASS_BLOCK;
    }

    /** What lies under a surface, {@code depth} blocks down: earth under grass, sand and then sandstone under sand. */
    static BlockState under(Block surface, int depth) {
        if (surface == Blocks.SAND) return (depth >= 4 ? Blocks.SANDSTONE : Blocks.SAND).defaultBlockState();
        if (surface == Blocks.RED_SAND) return (depth >= 4 ? Blocks.RED_SANDSTONE : Blocks.RED_SAND).defaultBlockState();
        if (surface == Blocks.TERRACOTTA || surface == Blocks.END_STONE || surface == Blocks.NETHERRACK
                || surface == Blocks.SOUL_SOIL || surface == Blocks.STONE) {
            return surface.defaultBlockState();
        }
        if (surface == Blocks.CRIMSON_NYLIUM || surface == Blocks.WARPED_NYLIUM) return Blocks.NETHERRACK.defaultBlockState();
        if (surface == Blocks.GRAVEL) return (depth >= 2 ? Blocks.STONE : Blocks.GRAVEL).defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    /** A surface that a cut can be dressed in (rock is left as the stone it was). */
    private static boolean soil(Block surface) {
        return surface != Blocks.STONE;
    }

    // ------------------------------------------------------------------ reading the ground

    /**
     * Read one column into the plan: the top of its earth (through trees, plants and snow, and
     * under whatever somebody built on it), the water or lava on it, and whether anybody built
     * there. {@code tops} gets what its surface was.
     */
    static void survey(LevelChunk chunk, int minY, int x, int z, FoundingPlan.Ground g, int i, byte[] tops,
                       BlockPos.MutableBlockPos m) {
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int floor = Math.max(minY, top - 192);
        boolean built = false;
        int y = top;
        while (y > floor) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir()) { y--; continue; }
            if (wet(s) && !built) {
                boolean lava = s.getFluidState().is(FluidTags.LAVA);
                int surface = y;
                while (y > floor && wet(chunk.getBlockState(m.setY(y)))) y--;
                BlockState bed = chunk.getBlockState(m.setY(y));
                g.fluid[i] = surface;
                g.ground[i] = y;
                g.kind[i] = earth(bed) || growth(bed) ? (lava ? FoundingPlan.LAVA : FoundingPlan.WATER) : FoundingPlan.BUILT;
                tops[i] = topOf(bed);
                return;
            }
            if (growth(s)) { y--; continue; }
            if (earth(s)) {
                g.ground[i] = y;
                g.kind[i] = built ? FoundingPlan.BUILT : FoundingPlan.LAND;
                tops[i] = topOf(s);
                return;
            }
            // Water in somebody's pool, a path, a floor, a wall: built. The earth under it is still
            // looked for, so the ground round it is held to that height.
            built = true;
            y--;
        }
        // No earth at all under it (the void off an island, a shaft to nowhere): left as it is.
        g.ground[i] = y;
        g.kind[i] = built ? FoundingPlan.BUILT : FoundingPlan.OUTSIDE;
        tops[i] = 0;
    }

    /** The top of the earth here, looking through trees, plants, snow and anything set on it. Loaded ground only. */
    public static int groundY(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int floor = Math.max(level.getMinBuildHeight(), y - 192);
        while (y > floor) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (earth(s) || (wet(s) && !s.getFluidState().isEmpty() && s.getFluidState().isSource())) return y;
            y--;
        }
        return y;
    }

    /** The first thing in this column that keeps it from being worked (somebody's), by name. For the log. */
    static String inTheWay(LevelChunk chunk, int x, int z, int ground, int target) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        for (int y = top; y > Math.min(target, ground); y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (!natural(s)) return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath() + " (y=" + y + ")";
        }
        return "nothing";
    }

    /**
     * Whether a column of the levelled square wants working again: its ground off the level, a
     * hollow (air, water, something growing, sand that would fall) within five of its top, its top
     * not the town's soil where the town's soil goes, or something growing on it.
     */
    static boolean wantsWork(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int target, Block surface) {
        if (ground != target) return true;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState top = chunk.getBlockState(m.set(x, target, z));
        if (natural(top) && !top.is(Blocks.BEDROCK) && !top.is(surface)
                && (top.isAir() || wet(top) || growth(top) || (soil(surface) && topOf(top) != 0))) return true;
        for (int d = 1; d < SOLID_DEPTH; d++) {
            if (target - d < level.getMinBuildHeight()) break;
            BlockState b = chunk.getBlockState(m.set(x, target - d, z));
            if (b.is(Blocks.BEDROCK) || !natural(b)) continue;
            if (b.isAir() || wet(b) || growth(b)
                    || b.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && !under(surface, d).is(b.getBlock())) return true;
        }
        BlockState on = chunk.getBlockState(m.set(x, target + 1, z));
        return !on.isAir() && natural(on) && (growth(on) || wet(on));
    }

    // ------------------------------------------------------------------ working a column

    /** How deep the town's own ground is made solid: its top and four of earth under it. */
    public static final int SOLID_DEPTH = 5;

    /**
     * Bring one column from {@code ground} (its earth's top) to {@code target}: everything above
     * the new height cleared away, earth built up to it in the land's own soil, and a cut top
     * dressed in {@code surface} with earth under it. {@code levelled}: the column is inside the
     * town's square itself, so what grows on it is cleared even where its height is right.
     * Returns the blocks changed, or -1 if anything built stands in the way (and then nothing
     * is changed at all).
     */
    static int shape(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int target, boolean levelled,
                     Block surface) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        // Look first: one thing built in the way and the whole column is left be.
        for (int y = top; y > target; y--) {
            if (!natural(chunk.getBlockState(m.set(x, y, z)))) return -1;
        }
        for (int y = ground + 1; y <= target; y++) {
            if (!natural(chunk.getBlockState(m.set(x, y, z)))) return -1;
        }
        if (!levelled && target == ground && top <= ground) return 0;
        int changed = 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = top; y > target; y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            // A felled tree's logs tell their leaves, so what is left hanging off the edge falls.
            level.setBlock(m, air, s.is(BlockTags.LOGS) ? Block.UPDATE_ALL : QUIET);
            changed++;
        }
        for (int y = ground + 1; y <= target; y++) {
            BlockState want = y == target ? surface.defaultBlockState() : under(surface, target - y);
            level.setBlock(m.set(x, y, z), want, QUIET);
            changed++;
        }
        if (target <= ground) {
            BlockState at = chunk.getBlockState(m.set(x, target, z));
            boolean cut = target < ground;
            boolean dress;
            if (!natural(at) || at.is(Blocks.BEDROCK)) dress = false;          // something buried: left be
            else if (at.isAir() || wet(at)) dress = true;                      // a cave or a spring under a cut: closed over
            else if (!soil(surface) || at.is(surface)) dress = false;          // rock left rock; the right soil already
            else dress = cut || (levelled && topOf(at) != 0);                  // a cut, or any other earth in the square
            if (dress) {
                // Sand or gravel over a hollow would fall into it: its stone instead.
                boolean falls = surface.defaultBlockState().getBlock() instanceof net.minecraft.world.level.block.FallingBlock;
                level.setBlock(m, at.isAir() && falls ? under(surface, 4) : surface.defaultBlockState(), QUIET);
                changed++;
                for (int d = 1; d <= 2 && cut && soil(surface); d++) {
                    BlockState below = chunk.getBlockState(m.set(x, target - d, z));
                    // Rock under a cut gets earth over it; a hollow under it (somebody's cellar?) is left.
                    if (topOf(below) == ROCK && !below.is(Blocks.BEDROCK)) {
                        level.setBlock(m, under(surface, d), QUIET);
                        changed++;
                    }
                }
            }
        }
        // The town's own ground is solid five deep: its top and four of earth under it, whatever the
        // land had there. A cave, a spring, a pocket of sand over a hollow, a buried root or a stump
        // under the new top is filled in the land's own earth (dirt under grass, sandstone under
        // sand), so nothing a builder sets on it sinks, nobody steps through it into a cave, and
        // nothing falls. Stone, ore and earth already there are kept; the world's floor and
        // anything somebody buried are left be.
        if (levelled) {
            BlockState topNow = chunk.getBlockState(m.set(x, target, z));
            if (natural(topNow) && !topNow.is(Blocks.BEDROCK) && !topNow.is(surface)
                    && (topNow.isAir() || wet(topNow) || growth(topNow) || (soil(surface) && topOf(topNow) != 0))) {
                level.setBlock(m, surface.defaultBlockState(), QUIET);
                changed++;
            }
            for (int d = 1; d < SOLID_DEPTH; d++) {
                BlockState below = chunk.getBlockState(m.set(x, target - d, z));
                if (below.is(Blocks.BEDROCK) || !natural(below)) continue;
                boolean hollow = below.isAir() || wet(below) || growth(below)
                    || below.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && !under(surface, d).is(below.getBlock());
                if (!hollow) continue;
                level.setBlock(m, under(surface, d), QUIET);
                changed++;
            }
        }
        // Grass that had snow lying on it, cleared: green again (its neighbours were not told).
        BlockState now = chunk.getBlockState(m.set(x, target, z));
        if (changed > 0 && now.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
                && now.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) {
            level.setBlock(m, now.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, false), QUIET);
        }
        return changed;
    }
}
