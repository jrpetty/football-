package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lie of the land.
 * <ul>
 * <li><b>Where to found a village.</b> Settlers look about them before they put down a
 *     camp: of the ground within a long stone's throw, the flattest dry piece wins.</li>
 * <li><b>Levelling the town.</b> Once there is a village, its people level the ground of the
 *     town a little every day: knolls cut down, hollows filled, to the level of the square,
 *     so that houses and streets stand true. Only earth, sand, gravel and plain stone are
 *     dug, never what anybody built, a field or a tree, and never more than six blocks up or
 *     down: a hill stays a hill, it just stops at the edge of town. What is cut from rock
 *     goes into the stores as cobblestone.</li>
 * </ul>
 */
public final class Land {

    private Land() {}

    /** The most a cell is cut down or built up to the town's level. */
    public static final int MOST = 6;
    /** Cells looked at, and changes made, per village per pass. */
    static final int LOOK = 220, CHANGES = 10;

    // ------------------------------------------------------------------ where to found

    /** The ground at a column: the first free block above it, looking through trees. */
    @Nullable
    public static BlockPos surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        BlockPos at = new BlockPos(x, y, z);
        int guard = 0;
        while (guard++ < 24) {
            BlockState below = level.getBlockState(at.below());
            boolean plant = below.canBeReplaced() && below.getFluidState().isEmpty() && !below.isAir();
            if (below.is(BlockTags.LOGS) || below.is(BlockTags.LEAVES) || plant) at = at.below();
            else break;
        }
        return at;
    }

    /** How rough a piece of ground is: the spread of heights over ±16, sampled every four blocks.
     *  Max value if any of it is water or not loaded. */
    public static int roughness(ServerLevel level, BlockPos centre) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        long sum = 0, n = 0;
        int[] ys = new int[81];
        int k = 0;
        for (int dx = -16; dx <= 16; dx += 4) {
            for (int dz = -16; dz <= 16; dz += 4) {
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) return Integer.MAX_VALUE;
                BlockPos s = surface(level, x, z);
                if (s == null) return Integer.MAX_VALUE;
                if (!level.getBlockState(s.below()).getFluidState().isEmpty() && Math.abs(dx) <= 8 && Math.abs(dz) <= 8) {
                    return Integer.MAX_VALUE;                                       // a pond in the middle of it
                }
                ys[k++] = s.getY();
                lo = Math.min(lo, s.getY());
                hi = Math.max(hi, s.getY());
                sum += s.getY();
                n++;
            }
        }
        double mean = sum / (double) Math.max(1, n);
        double dev = 0;
        for (int i = 0; i < k; i++) dev += Math.abs(ys[i] - mean);
        dev /= Math.max(1, k);
        return (hi - lo) * 4 + (int) Math.round(dev * 6);
    }

    /** The flattest dry ground within reach of here (here itself if nothing is better). */
    @Nullable
    public static BlockPos flattest(ServerLevel level, BlockPos around, int reach) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dx = -reach; dx <= reach; dx += 8) {
            for (int dz = -reach; dz <= reach; dz += 8) {
                int x = around.getX() + dx, z = around.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos g = surface(level, x, z);
                if (g == null) continue;
                BlockState under = level.getBlockState(g.below());
                if (under.is(Blocks.WATER) || under.isAir() || under.is(BlockTags.LEAVES)) continue;
                int r = roughness(level, g);
                if (r == Integer.MAX_VALUE) continue;
                int score = r * 4 + (int) Math.sqrt(dx * dx + dz * dz) / 4;     // flat first, near second
                if (score < bestScore) { bestScore = score; best = g; }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ levelling the town

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> MOVED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CURSOR.clear();
        MOVED.clear();
        LAST.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 41) return;
        com.jrpetty.mcassistant.Guard.run("land", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    level(level, v, LOOK, CHANGES);
                    Grow.tick(level, v);
                    Waterfront.tick(level, v);
                }
            }
        });
    }

    /** The town's radius for levelling: its streets and lots as they stand, never more than 48. */
    static int reach(UUID village) {
        return Math.min(48, Villages.townReach(village));
    }

    /**
     * One pass of levelling: look at so many cells of the town, change at most so many blocks.
     * Returns the blocks changed.
     */
    public static int level(ServerLevel level, Villages.Village v, int look, int changes) {
        UUID id = v.id();
        int r = reach(id);
        int side = 2 * r + 1, cells = side * side;
        int target = v.centre().getY() - 1;                       // the square's ground
        int cursor = CURSOR.getOrDefault(id, 0);
        int done = 0;
        // Fields, mines, pens and fishing grounds are worked as they lie: left alone.
        java.util.List<int[]> zones = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.workZone() == null) continue;
            switch (a.stationTask()) {
                case FARM, MINE, RANCH, FISH -> {
                    BlockPos zc = a.workZone().center();
                    zones.add(new int[]{ zc.getX(), zc.getZ(), a.workZone().radius() + 2 });
                }
                default -> { }
            }
        }
        for (int i = 0; i < look && done < changes; i++) {
            int c = (cursor + i) % cells;
            int x = v.centre().getX() - r + c % side, z = v.centre().getZ() - r + c / side;
            if (!level.isLoaded(new BlockPos(x, target, z))) continue;
            boolean worked = false;
            for (int[] zn : zones) if (Math.abs(x - zn[0]) <= zn[2] && Math.abs(z - zn[1]) <= zn[2]) { worked = true; break; }
            if (worked) continue;
            done += cell(level, v, x, z, target);
        }
        int next = (cursor + look) % cells;
        // A full sweep with nothing left to do: say so once.
        int moved = MOVED.merge(id, done, Integer::sum);
        if (next < cursor) {
            if (moved == 0 && LAST.getOrDefault(id, -1L) > 0) {
                Villages.tell(id, level.getDayTime() / 24000L, "the ground of the town was levelled, from the square to the last street");
                LAST.put(id, -1L);
            } else if (moved > 0) {
                LAST.put(id, level.getGameTime());
            }
            MOVED.put(id, 0);
        }
        CURSOR.put(id, next);
        return done;
    }

    /** One column: a block cut off the top, or a block of earth laid on it. Returns blocks changed. */
    static int cell(ServerLevel level, Villages.Village v, int x, int z, int target) {
        BlockPos top = surface(level, x, z);
        if (top == null) return 0;
        BlockPos ground = top.below();
        int diff = ground.getY() - target;
        if (diff == 0 || Math.abs(diff) > MOST) return 0;
        if (inABuilding(v.id(), ground)) return 0;
        BlockState g = level.getBlockState(ground);
        if (!earth(g) || level.getBlockEntity(ground) != null) return 0;
        // Never next to water (a cut would drain it into the town), never under anything placed.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.getFluidState(ground.relative(d)).isEmpty() || !level.getFluidState(top.relative(d)).isEmpty()) return 0;
        }
        BlockState above = level.getBlockState(top);
        if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) return 0;
        if (diff > 0) {
            // Cut: the plant on top goes, then the block; the one beneath shows grass if it is earth.
            if (!above.isAir()) level.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(ground, Blocks.AIR.defaultBlockState(), 3);
            BlockState under = level.getBlockState(ground.below());
            if (under.is(Blocks.DIRT)) level.setBlock(ground.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            if (g.is(BlockTags.BASE_STONE_OVERWORLD) || g.is(Blocks.COBBLESTONE)) {
                ItemStack spoil = Market.intoStores(level, v.id(), new ItemStack(Items.COBBLESTONE));
                if (!spoil.isEmpty()) { /* the stores are full: the rubble is carted off */ }
            }
            dust(level, ground, g);
            return 1;
        }
        // Fill: earth on top, the old top buried.
        if (!above.isAir()) level.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
        if (g.is(Blocks.GRASS_BLOCK)) level.setBlock(ground, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(top, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        dust(level, top, Blocks.DIRT.defaultBlockState());
        return 1;
    }

    private static void dust(ServerLevel level, BlockPos at, BlockState s) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5,
            6, 0.3, 0.2, 0.3, 0.05);
        if (level.getRandom().nextInt(4) == 0) {
            level.playSound(null, at, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 0.4F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        }
    }

    /** What may be dug or built up: plain earth, sand, gravel and stone. */
    static boolean earth(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL)
            || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MYCELIUM) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.BASE_STONE_OVERWORLD)
            || s.is(Blocks.SANDSTONE) || s.is(Blocks.TERRACOTTA);
    }

    /** Within (or right beside) something the village built, or is building. */
    static boolean inABuilding(UUID village, BlockPos at) {
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(village).entrySet()) {
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(e.getKey());
            int r = Math.max(Math.max(half[0], half[1]), e.getValue().radius()) + 2;
            BlockPos c = e.getValue().anchor();
            if (Math.abs(at.getX() - c.getX()) <= r && Math.abs(at.getZ() - c.getZ()) <= r) return true;
        }
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.structure().equals("fortify")) {
                int d = Math.max(Math.abs(at.getX() - b.anchor().getX()), Math.abs(at.getZ() - b.anchor().getZ()));
                if (d >= Watch.R - 1 && d <= Watch.R + 1) return true;
                continue;
            }
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
            int r = Math.max(half[0], half[1]) + 1;
            if (Math.abs(at.getX() - b.anchor().getX()) <= r && Math.abs(at.getZ() - b.anchor().getZ()) <= r) return true;
        }
        return false;
    }
}
