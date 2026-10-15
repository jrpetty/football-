package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [war] The ground a war is fought on, and what an army puts on it: the battlefield its Captains choose, the
 * camps, the ram before a gate, and the cairn left when it is over.
 *
 * <p><b>The field.</b> [field] Flat open ground between the two towns, room for both lines, the gap between them
 * and the ranks behind (48 blocks along by 40 across), off anybody's town, fields and buildings: every second
 * column of it looked at, and a field is fit only with its ground within three blocks of height all over, no hole
 * or drop more than two blocks under its own level (a ravine, a pit, a cave open to the sky, a cliff), no tree, no
 * water and nothing built on it. The Captains look near the road's middle first (45 fields), then wider (64 more),
 * and take the flattest fit one nearest the middle; with none fit, the least bad (rougher, a tree or two, still no
 * chasm); with not even that, the edge of the larger town's fields, ground the town made flat. Each line's ground
 * is trampled clear of brush (tall grass, ferns, saplings; never a tree) when the battle is fought in full. Its
 * name is the nearer town's ("Ashby Field"). On it each side may find
 * a rise behind its own line (for its archers), cover on a flank (trees: for a hidden reserve), and off to one
 * side a hill to watch from, which the players are told of. With the ground unknown (nobody near, its chunks
 * asleep) it is the middle of the road, and the battle is reckoned on paper anyway.
 *
 * <p><b>The camp.</b> Behind its line: a tent to every four (nine wool on four fence poles, out of the army's
 * supply), a campfire, the war banner planted by it. Struck at dawn, every block of it back in the supply.
 *
 * <p><b>The ram.</b> Three logs out of the supply, a beam laid on the road before the gate.
 *
 * <p><b>The field after.</b> Where the lines met, a scatter of the grass trampled to bare earth, grown over again
 * three days later (the winner's books keep where).
 *
 * <p><b>The cairn.</b> Where the lines met: five cobblestones out of the winner's stores, and a sign
 * ("The Battle of / Ashby Field / day 140 / Ashby v Brook").
 */
public final class Battlefields {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Battlefields() {}

    /** The wool and the poles of a tent. */
    static final int TENT_WOOL = 9, TENT_POLES = 4;
    /** The logs of a ram. */
    static final int RAM_LOGS = 3;
    /** How far apart the two lines draw up, centre to centre. */
    static final int GAP = 24;
    /**
     * The field's size, looked over: so far either way along the line between the armies (the lines a gap apart, the
     * ranks, the archers and the Captain behind each) and so far either way across it (the frontage of a line of
     * forty in two or three ranks, its wings and reserve).
     */
    static final int HALF_LONG = 24, HALF_WIDE = 20;
    /** How far apart the columns looked at stand (blocks): fine enough that no ravine or pit two blocks wide slips between. */
    static final int LOOK_STEP = 2;
    /** A fit field: its ground within so many blocks of height over the whole of it, and no hole or drop deeper than so many under its own level. */
    static final int MAX_RANGE = 3, MAX_DROP = 2;
    /** The least bad, when no fit field is to be found after the wide search: rougher, a tree or two and a puddle allowed, still no chasm. */
    static final int LOOSE_RANGE = 6, LOOSE_DROP = 3, LOOSE_TREES = 6, LOOSE_WET = 4;
    /** Past the larger town's own reach, so far again to the edge of its fields (the last resort: flat ground the town made). */
    static final int FIELDS_OUT = 28;
    /** A field's corner from its middle (blocks): a town's reach plus this is too near the town to fight in. */
    static final int FIELD_RADIUS = (int) Math.ceil(Math.sqrt(HALF_LONG * HALF_LONG + HALF_WIDE * HALF_WIDE));
    /** How far behind its front rank each line's ground is cleared of brush. */
    static final int BRUSH_BEHIND = 6;

    /**
     * Where the Captains look, in turn, as {t, across}: t the share of the road from the first town's side, across the
     * blocks off it to the first side's right. The near ring first (the road's middle and either side of it); the wide
     * ring only when none of those will do. 45 and 64 looks: the most a war's choice of field costs (once a campaign).
     */
    static final double[][] NEAR = ring(0.30, 0.70, 0.05, 0, 14, -14, 28, -28);
    static final double[][] WIDE = ring(0.15, 0.85, 0.10, 42, -42, 56, -56, 70, -70, 84, -84);

    private static double[][] ring(double from, double to, double step, double... across) {
        List<double[]> out = new ArrayList<>();
        int n = (int) Math.round((to - from) / step);
        for (int i = 0; i <= n; i++) for (double a : across) out.add(new double[]{ from + i * step, a });
        return out.toArray(new double[0][]);
    }

    /** The ground chosen: its middle, the way from the first town's side to the second's, what is on it. */
    public record Ground(BlockPos centre, double ax, double az, @Nullable BlockPos riseA, @Nullable BlockPos riseB,
                         @Nullable BlockPos coverA, @Nullable BlockPos coverB, @Nullable BlockPos view, String name, boolean known) {

        /** Perpendicular to the way between the lines (to the first side's right as it faces the second). */
        public double px() { return -az; }
        public double pz() { return ax; }

        /** A spot on the field: {@code along} towards the second side, {@code across} to the first side's right. */
        public BlockPos at(double along, double across) {
            return BlockPos.containing(centre.getX() + 0.5 + ax * along + px() * across, centre.getY(), centre.getZ() + 0.5 + az * along + pz() * across);
        }
    }

    /**
     * What a look over a field found: the lowest, highest and middle (median) height of its ground, how deep its deepest
     * hole goes under that middle, how many of the columns looked at stand under a tree, in water or on anything a
     * hand made (a crop, a floor, a wall), and how far the ground strays from its middle on average.
     */
    record Survey(int lo, int hi, int median, int deepest, int trees, int wet, int built, double spread) {
        static final Survey UNFIT = new Survey(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 99);

        int range() { return hi - lo; }

        /** Flat open ground: no hole, no drop, no tree, no water, nothing built. */
        boolean fit() { return range() <= MAX_RANGE && deepest <= MAX_DROP && trees == 0 && wet == 0 && built == 0; }

        /** Rough, but a battle could be fought on it. */
        boolean bearable() { return range() <= LOOSE_RANGE && deepest <= LOOSE_DROP && trees <= LOOSE_TREES && wet <= LOOSE_WET && built == 0; }

        double badness() { return range() * 4.0 + spread * 6.0 + deepest * 3.0 + trees * 3.0 + wet * 4.0; }
    }

    /** A field looked at: its middle (x, z), its survey, and its score (lower is better: rough ground, off the road's middle). */
    record Look(double x, double z, Survey survey, double score) {}

    /**
     * The battlefield between these towns, chosen by its Captains from the ground they can see (its chunks to hand):
     * the flattest open field near the road's middle, else wider off it; failing any fit field, the least bad; failing
     * even that, the edge of the larger town's fields. With none of it to look at, the road's middle, unknown.
     */
    static Ground choose(ServerLevel level, Villages.Village a, Villages.Village b) {
        return choose(level, a, b, false);
    }

    /** As {@link #choose(ServerLevel, Villages.Village, Villages.Village)}; {@code load}: ground not yet made is made to look at (an admin's scenario, never a tick). */
    static Ground choose(ServerLevel level, Villages.Village a, Villages.Village b, boolean load) {
        BlockPos ea = Roads.exit(a.centre(), b.centre()), eb = Roads.exit(b.centre(), a.centre());
        double[] axis = axis(ea, eb);
        int[] looked = { 0 };
        Look pick = pick(level, ea, eb, load, looked);
        BlockPos centre;
        if (pick != null) {
            centre = surface(level, BlockPos.containing(pick.x(), 0, pick.z()));
        } else if (looked[0] > 0) {
            Villages.Village big = Villages.headcount(a.id()) >= Villages.headcount(b.id()) ? a : b, other = big == a ? b : a;
            centre = surface(level, driestBeside(level, big, other, load));
            LOG.info("[MCA-WAR] no field fit to fight on between {} and {} ({} looked at): the driest, levellest ground beside {}'s fields at {}",
                Villages.name(a.id()), Villages.name(b.id()), looked[0], Villages.name(big.id()), centre.toShortString());
        } else {
            BlockPos mid = BlockPos.containing((ea.getX() + eb.getX()) / 2.0, a.centre().getY(), (ea.getZ() + eb.getZ()) / 2.0);
            return new Ground(mid, axis[0], axis[1], null, null, null, null, null, nearerName(mid, a, b), false);
        }
        return ground(level, centre, axis[0], axis[1], nearerName(centre, a, b));
    }

    /** Tests: the field the Captains would choose between these two ends of a road (the ground made if need be), or null if none will do. */
    @Nullable
    public static Ground chooseForTests(ServerLevel level, BlockPos from, BlockPos to) {
        Look pick = pick(level, from, to, true, new int[1]);
        if (pick == null) return null;
        double[] axis = axis(from, to);
        return ground(level, surface(level, BlockPos.containing(pick.x(), 0, pick.z())), axis[0], axis[1], "Test Field");
    }

    /** Tests: is a field centred here, facing from {@code from} to {@code to}, fit to fight on? */
    public static boolean fitForTests(ServerLevel level, BlockPos centre, BlockPos from, BlockPos to) {
        double[] axis = axis(from, to);
        Survey s = survey(level, centre.getX() + 0.5, centre.getZ() + 0.5, axis[0], axis[1], true);
        return s != null && s.fit();
    }

    /**
     * The best field between the road's two ends: the fit field scoring best in the near ring, else in the wide ring;
     * failing both, the least bad of all the bearable ones; null if none is even that. {@code looked[0]}: how many
     * fields had their ground to hand (none: the choice is made blind).
     */
    @Nullable
    static Look pick(ServerLevel level, BlockPos ea, BlockPos eb, boolean load, int[] looked) {
        double dx = eb.getX() - ea.getX(), dz = eb.getZ() - ea.getZ();
        double[] axis = axis(ea, eb);
        Look bestLoose = null;
        for (double[][] ring : new double[][][]{ NEAR, WIDE }) {
            Look bestFit = null;
            for (double[] c : ring) {
                double t = c[0], across = c[1];
                double cx = ea.getX() + 0.5 + dx * t - axis[1] * across, cz = ea.getZ() + 0.5 + dz * t + axis[0] * across;
                if (nearTown(level, cx, cz)) continue;
                Survey s = survey(level, cx, cz, axis[0], axis[1], load);
                if (s == null) continue;
                looked[0]++;
                if (!s.bearable()) continue;
                Look l = new Look(cx, cz, s, s.badness() + Math.abs(t - 0.5) * 20.0 + Math.abs(across) / 14.0 * 2.0);
                if (s.fit() && (bestFit == null || l.score() < bestFit.score())) bestFit = l;
                if (bestLoose == null || l.score() < bestLoose.score()) bestLoose = l;
            }
            if (bestFit != null) return bestFit;
        }
        return bestLoose;
    }

    /**
     * The ground of a field centred here, its lines facing along {@code ax, az}: every second column of it looked at.
     * Null if its ground is not to hand (and not to be made); {@link Survey#UNFIT} as soon as it plainly will not do.
     */
    @Nullable
    static Survey survey(ServerLevel level, double cx, double cz, double ax, double az, boolean load) {
        int[] heights = new int[(2 * HALF_LONG / LOOK_STEP + 1) * (2 * HALF_WIDE / LOOK_STEP + 1)];
        int n = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, trees = 0, wet = 0, built = 0;
        for (int i = -HALF_LONG; i <= HALF_LONG; i += LOOK_STEP) {
            for (int j = -HALF_WIDE; j <= HALF_WIDE; j += LOOK_STEP) {
                int x = Mth.floor(cx + ax * i - az * j), z = Mth.floor(cz + az * i + ax * j);
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    if (!load) return null;
                    level.getChunk(x >> 4, z >> 4);
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState top = level.getBlockState(new BlockPos(x, y - 1, z));
                if (!top.getFluidState().isEmpty() || top.is(BlockTags.ICE)) wet++;
                else if (top.is(BlockTags.LOGS) || level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > y) trees++;
                else if (!naturalGround(top)) built++;
                heights[n++] = y;
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                if (hi - lo > LOOSE_RANGE + LOOSE_DROP || trees > LOOSE_TREES || wet > LOOSE_WET || built > 0) return Survey.UNFIT;
            }
        }
        int[] sorted = java.util.Arrays.copyOf(heights, n);
        java.util.Arrays.sort(sorted);
        int median = sorted[n / 2];
        double stray = 0;
        for (int k = 0; k < n; k++) stray += Math.abs(heights[k] - median);
        return new Survey(lo, hi, median, median - lo, trees, wet, built, stray / n);
    }

    /** The ground the world laid down (earth, sand, stone, snow, a road's path), not a crop, a floor or a wall. */
    static boolean naturalGround(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.TERRACOTTA)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.SNOW) || st.is(Blocks.POWDER_SNOW)
            || st.is(Blocks.CLAY) || st.is(Blocks.CALCITE) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE);
    }

    /** Is a field centred here on (or against) a town's own ground? */
    static boolean nearTown(ServerLevel level, double x, double z) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            double r = Villages.townReach(v.id()) + FIELD_RADIUS, dx = v.centre().getX() - x, dz = v.centre().getZ() - z;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    /**
     * The last resort, looked over rather than taken blind: just past the edge of either town's fields (the larger
     * first), on sixteen bearings from the side towards the other, the driest and then the levellest ground found; the
     * edge towards the other if none of it can be looked at. (A town across a lake from its foe must not fight in it.)
     */
    static BlockPos driestBeside(ServerLevel level, Villages.Village big, Villages.Village other, boolean load) {
        BlockPos best = null;
        long bestScore = Long.MAX_VALUE;
        for (Villages.Village v : List.of(big, other)) {
            Villages.Village foe = v == big ? other : big;
            double[] toward = axis(v.centre(), foe.centre());
            double out = Villages.townReach(v.id()) + FIELDS_OUT + HALF_LONG;
            for (int k = 0; k < 16; k++) {
                int turn = (k + 1) / 2 * (k % 2 == 0 ? 1 : -1);                 // 0, -1, 1, -2, 2...: towards the foe first
                double ang = Math.atan2(toward[1], toward[0]) + Math.PI * 2 * turn / 16;
                double ax = Math.cos(ang), az = Math.sin(ang);
                double cx = v.centre().getX() + 0.5 + ax * out, cz = v.centre().getZ() + 0.5 + az * out;
                if (nearTown(level, cx, cz)) continue;                          // on some town's own ground
                long score = dryScore(level, cx, cz, ax, az, load);
                if (score < bestScore) {
                    bestScore = score;
                    best = BlockPos.containing(cx, v.centre().getY(), cz);
                }
                if (score == 0) return best;
            }
        }
        return best != null ? best : edgeOfFields(big, other);
    }

    /** How bad this ground is to fight on, worst first: its wet columns, then its height's spread, then its trees (0 is
     *  dry, level and clear); Long.MAX_VALUE if it cannot be looked at. Every fourth column of the field looked at. */
    static long dryScore(ServerLevel level, double cx, double cz, double ax, double az, boolean load) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, wet = 0, trees = 0;
        for (int i = -HALF_LONG; i <= HALF_LONG; i += LOOK_STEP * 2) {
            for (int j = -HALF_WIDE; j <= HALF_WIDE; j += LOOK_STEP * 2) {
                int x = Mth.floor(cx + ax * i - az * j), z = Mth.floor(cz + az * i + ax * j);
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    if (!load) return Long.MAX_VALUE;
                    level.getChunk(x >> 4, z >> 4);
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState top = level.getBlockState(new BlockPos(x, y - 1, z));
                if (!top.getFluidState().isEmpty() || top.is(BlockTags.ICE)) wet++;
                else if (top.is(BlockTags.LOGS) || level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > y) trees++;
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        return wet * 1_000_000L + Math.max(0, hi - lo - MAX_RANGE) * 1_000L + trees;
    }

    /** Tests: the last resort's ground between these two towns (their ground made if need be). */
    public static BlockPos driestForTests(ServerLevel level, Villages.Village big, Villages.Village other) {
        return surface(level, driestBeside(level, big, other, true));
    }

    /** The edge of the larger town's fields, on the side towards the other, unlooked at (nothing of the ground to hand). */
    static BlockPos edgeOfFields(Villages.Village big, Villages.Village other) {
        double[] axis = axis(big.centre(), other.centre());
        double out = Villages.townReach(big.id()) + FIELDS_OUT + HALF_LONG;
        return BlockPos.containing(big.centre().getX() + 0.5 + axis[0] * out, big.centre().getY(), big.centre().getZ() + 0.5 + axis[1] * out);
    }

    /** The unit way from one spot to another, {ax, az} (east if they stand together). */
    static double[] axis(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        return len < 1.0 ? new double[]{ 1, 0 } : new double[]{ dx / len, dz / len };
    }

    static String nearerName(BlockPos p, Villages.Village a, Villages.Village b) {
        return Villages.name((p.distSqr(a.centre()) <= p.distSqr(b.centre()) ? a : b).id()) + " Field";
    }

    /** A known field here: its rises, its cover and its hill to watch from looked for round it. */
    static Ground ground(ServerLevel level, BlockPos centre, double ax, double az, String name) {
        return new Ground(centre, ax, az, rise(level, centre, ax, az, -1), rise(level, centre, ax, az, 1), cover(level, centre, ax, az, -1),
            cover(level, centre, ax, az, 1), view(level, centre, ax, az), name, true);
    }

    /** The brush trampled flat where the lines stand and run in: tall grass, ferns, dead bushes and saplings, never a tree. How many went. */
    static int clearBrush(ServerLevel level, Ground g) {
        int cleared = 0, reach = GAP / 2 + BRUSH_BEHIND;
        for (int along = -reach; along <= reach; along++) {
            for (int across = -HALF_WIDE; across <= HALF_WIDE; across++) {
                BlockPos p = g.at(along, across);
                if (!level.isLoaded(p)) continue;
                BlockPos at = new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
                if (brush(level.getBlockState(at))) {
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);         // a tall plant's top half goes with it
                    cleared++;
                }
            }
        }
        return cleared;
    }

    static boolean brush(BlockState st) {
        return st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN) || st.is(Blocks.DEAD_BUSH)
            || st.is(BlockTags.SAPLINGS);
    }

    /** The pictures: the field here, its way from the first town's side to the second's given, what is round it looked over. */
    static Ground groundAt(ServerLevel level, BlockPos at, double ax, double az, String name) {
        return ground(level, surface(level, at), ax, az, name);
    }

    static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    /** A rise behind one side's line ({@code side} -1 the first town's, 1 the second's), three blocks or more over the field. */
    @Nullable
    static BlockPos rise(ServerLevel level, BlockPos centre, double ax, double az, int side) {
        BlockPos best = null;
        int top = centre.getY() + 2;
        for (int back = 16; back <= 28; back += 4) {
            for (int across = -16; across <= 16; across += 8) {
                BlockPos p = BlockPos.containing(centre.getX() + ax * side * back - az * across, 0, centre.getZ() + az * side * back + ax * across);
                if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
                BlockPos s = surface(level, p);
                if (s.getY() > top) {
                    top = s.getY();
                    best = s;
                }
            }
        }
        return best;
    }

    /** Trees on one side's flank, near enough to hide a reserve behind. */
    @Nullable
    static BlockPos cover(ServerLevel level, BlockPos centre, double ax, double az, int side) {
        for (int across : new int[]{ 22, -22, 28, -28 }) {
            for (int back = 4; back <= 12; back += 4) {
                int x = Mth.floor(centre.getX() + ax * side * back - az * across), z = Mth.floor(centre.getZ() + az * side * back + ax * across);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > ground + 2) {
                    // Behind the trees, on the field's side of them.
                    double in = across > 0 ? -5 : 5;
                    return surface(level, BlockPos.containing(x - az * in, 0, z + ax * in));
                }
            }
        }
        return null;
    }

    /** A hill off to one side to watch the field from: five blocks or more over it, thirty to fifty out. */
    @Nullable
    static BlockPos view(ServerLevel level, BlockPos centre, double ax, double az) {
        BlockPos best = null;
        int top = centre.getY() + 4;
        for (int across = -50; across <= 50; across += 10) {
            if (Math.abs(across) < 30) continue;
            for (int along = -16; along <= 16; along += 8) {
                int x = Mth.floor(centre.getX() + ax * along - az * across), z = Mth.floor(centre.getZ() + az * along + ax * across);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y > top) {
                    top = y;
                    best = new BlockPos(x, y, z);
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the camp

    /** A camp pitched: every block it put down (to take up again exactly), and its banner's cloth. */
    public static final class Camp {
        final List<BlockPos> wool = new ArrayList<>(), poles = new ArrayList<>();
        @Nullable BlockPos fire, banner;
        ItemStack bannerItem = ItemStack.EMPTY;
        final BlockPos centre;

        Camp(BlockPos centre) {
            this.centre = centre;
        }

        public BlockPos centre() { return centre; }
        public int tents() { return poles.size() / 2; }
        public boolean fire() { return fire != null; }
    }

    /**
     * Pitch the army's camp here, its tents in a ring round the fire, their doors to it, facing {@code ax, az}
     * (towards the enemy): out of its supply, as far as it goes; on open ground only.
     */
    static Camp pitch(ServerLevel level, Armies.Army army, BlockPos centre, double ax, double az, @Nullable ItemStack banner) {
        Camp c = new Camp(centre);
        if (!level.isLoaded(centre)) return c;
        BlockPos fire = surface(level, centre);
        if (army.carried(Items.CAMPFIRE) > 0 && open(level, fire, 0) && army.use(Items.CAMPFIRE, 1)) {
            level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
            c.fire = fire;
        }
        if (banner != null && banner.getItem() instanceof BannerItem bi) {
            BlockPos at = surface(level, fire.offset(Mth.floor(ax * 2.0), 0, Mth.floor(az * 2.0)));
            if (open(level, at, 1)) {
                int rot = RotationSegment.convertToSegment(Armies.yawTo(ax, az) + 180.0F);
                level.setBlock(at, BannerBlock.byColor(bi.getColor()).defaultBlockState().setValue(BannerBlock.ROTATION, rot), 3);
                if (level.getBlockEntity(at) instanceof BannerBlockEntity be) {
                    be.applyComponentsFromItemStack(banner);
                    be.setChanged();
                }
                c.banner = at;
                c.bannerItem = banner.copy();
            }
        }
        int tents = Math.min(army.carried(Items.WHITE_WOOL) / TENT_WOOL, army.carried(Items.OAK_FENCE) / TENT_POLES);
        tents = Math.min(tents, Math.max(1, (army.fighters.size() + 3) / 4));
        for (int i = 0; i < tents; i++) {
            double ang = Math.PI * 2 * i / Math.max(1, tents) + 0.4;
            double ox = Math.cos(ang) * 7.0, oz = Math.sin(ang) * 7.0;
            BlockPos at = surface(level, centre.offset(Mth.floor(ox), 0, Mth.floor(oz)));
            Direction door = Direction.getNearest(-ox, 0, -oz);
            if (tent(level, at, door, c)) {
                army.use(Items.WHITE_WOOL, TENT_WOOL);
                army.use(Items.OAK_FENCE, TENT_POLES);
            }
        }
        return c;
    }

    /** Air over solid, dry ground for {@code height} blocks above this spot (the town's leave asked by the caller). */
    static boolean openGround(ServerLevel level, BlockPos at, int height) {
        BlockState under = level.getBlockState(at.below());
        if (!under.isFaceSturdy(level, at.below(), Direction.UP) || !under.getFluidState().isEmpty()) return false;
        for (int y = 0; y <= height; y++) if (!level.getBlockState(at.above(y)).canBeReplaced()) return false;
        return true;
    }

    /**
     * Air over solid, dry ground for {@code height} blocks above this spot (and the spot itself), and a town's leave to
     * set something down there (TownBlocks: never into a building, a doorway or a road).
     */
    static boolean open(ServerLevel level, BlockPos at, int height) {
        return TownBlocks.mayPlace(level, at, TownBlocks.Why.OTHER) && openGround(level, at, height);
    }

    /** One tent, its ridge running in the door's line: two poles each end, the ridge and the sloping sides. */
    static boolean tent(ServerLevel level, BlockPos at, Direction door, Camp c) {
        Direction side = door.getClockWise();
        List<BlockPos> poles = new ArrayList<>(), wool = new ArrayList<>();
        for (int k = -1; k <= 1; k++) {
            BlockPos mid = at.relative(door, k);
            if (k != 0) {
                poles.add(mid);
                poles.add(mid.above());
            }
            wool.add(mid.above(2));
            wool.add(mid.relative(side).above());
            wool.add(mid.relative(side.getOpposite()).above());
        }
        for (int k = -1; k <= 1; k++) {
            for (int s = -1; s <= 1; s++) if (!open(level, at.relative(door, k).relative(side, s), 2)) return false;
        }
        BlockState pole = Blocks.OAK_FENCE.defaultBlockState(), cloth = Blocks.WHITE_WOOL.defaultBlockState();
        for (BlockPos p : poles) level.setBlock(p, pole, 3);
        for (BlockPos p : wool) level.setBlock(p, cloth, 3);
        c.poles.addAll(poles);
        c.wool.addAll(wool);
        return true;
    }

    /** Strike the camp: every block of it taken up (if it is still as it was put) and back into the supply; its banner back. */
    static ItemStack strike(ServerLevel level, Armies.Army army, @Nullable Camp c) {
        if (c == null) return ItemStack.EMPTY;
        for (BlockPos p : c.wool) if (takeUp(level, p, Blocks.WHITE_WOOL)) army.supply.merge(Items.WHITE_WOOL, 1, Integer::sum);
        for (BlockPos p : c.poles) if (takeUp(level, p, Blocks.OAK_FENCE)) army.supply.merge(Items.OAK_FENCE, 1, Integer::sum);
        if (c.fire != null && takeUp(level, c.fire, Blocks.CAMPFIRE)) army.supply.merge(Items.CAMPFIRE, 1, Integer::sum);
        ItemStack banner = ItemStack.EMPTY;
        if (c.banner != null && level.isLoaded(c.banner) && level.getBlockState(c.banner).getBlock() instanceof BannerBlock) {
            level.setBlock(c.banner, Blocks.AIR.defaultBlockState(), 3);
            banner = c.bannerItem;
        }
        c.wool.clear();
        c.poles.clear();
        c.fire = null;
        c.banner = null;
        return banner;
    }

    static boolean takeUp(ServerLevel level, BlockPos p, net.minecraft.world.level.block.Block was) {
        if (!level.isLoaded(p) || !level.getBlockState(p).is(was)) return false;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        return true;
    }

    // ------------------------------------------------------------------ the ram

    /** The ram, three logs laid end to end on the road, pointing at the gate. Where its head lies, or null (no logs). */
    @Nullable
    static List<BlockPos> ram(ServerLevel level, Armies.Army army, BlockPos gate, Direction out) {
        if (army.carried(Items.OAK_LOG) < RAM_LOGS || !level.isLoaded(gate)) return null;
        List<BlockPos> beam = new ArrayList<>();
        for (int k = 2; k < 2 + RAM_LOGS; k++) {
            BlockPos p = surface(level, gate.relative(out, k));
            if (!level.getBlockState(p).canBeReplaced() || !TownBlocks.mayPlace(level, p, TownBlocks.Why.OTHER)) return null;
            beam.add(p);
        }
        army.use(Items.OAK_LOG, RAM_LOGS);
        BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, out.getAxis());
        for (BlockPos p : beam) level.setBlock(p, log, 3);
        return beam;
    }

    /** The ram taken apart: its logs back into the supply. */
    static void unram(ServerLevel level, Armies.Army army, @Nullable List<BlockPos> beam) {
        if (beam == null) return;
        for (BlockPos p : beam) if (takeUp(level, p, Blocks.OAK_LOG)) army.supply.merge(Items.OAK_LOG, 1, Integer::sum);
        beam.clear();
    }

    // ------------------------------------------------------------------ the field after the battle

    /** How long a battlefield's trampled ground is left before it grows over. */
    static final int SCAR_DAYS = 3;
    /** The most of a field trampled bare (blocks). */
    static final int SCARS = 24;

    /**
     * Where the lines met, trampled: a scatter of the grass along the clash worn to bare earth, written in the
     * winner's books (Ledger "war.scars/day") to be grassed over again in three days (tidy).
     */
    static void trample(ServerLevel level, UUID town, Ground g, long day, java.util.Random rng) {
        List<String> worn = new ArrayList<>();
        for (int k = 0; k < SCARS * 2 && worn.size() < SCARS; k++) {
            BlockPos p = surface(level, g.at(rng.nextGaussian() * 3.0, (rng.nextDouble() - 0.5) * 18.0)).below();
            if (!level.isLoaded(p) || !level.getBlockState(p).is(Blocks.GRASS_BLOCK) || !TownBlocks.mayPlace(level, p, TownBlocks.Why.OTHER)) continue;
            level.setBlock(p, (rng.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState(), 3);
            if (level.getBlockState(p.above()).is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES)) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
            worn.add(Long.toString(p.asLong()));
        }
        if (!worn.isEmpty()) com.jrpetty.mcassistant.village.Ledger.note(town, "war.scars/" + day, String.join(",", worn));
    }

    /** Once a day: a battlefield's ground trampled three days and more ago grassed over again, where it is still bare. */
    static void tidy(ServerLevel level, UUID town, long day) {
        for (java.util.Map.Entry<String, String> n : com.jrpetty.mcassistant.village.Ledger.notes(town).entrySet()) {
            if (!n.getKey().startsWith("war.scars/") || day - WarBooks.num(n.getKey().substring(10), day) < SCAR_DAYS) continue;
            boolean all = true;
            for (String one : n.getValue().split(",")) {
                BlockPos p = BlockPos.of(WarBooks.num(one, 0));
                if (!level.isLoaded(p)) { all = false; continue; }
                BlockState st = level.getBlockState(p);
                if (st.is(Blocks.COARSE_DIRT) || st.is(Blocks.DIRT)) level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            }
            if (all) com.jrpetty.mcassistant.village.Ledger.forget(town, n.getKey());
        }
    }

    // ------------------------------------------------------------------ the cairn

    /**
     * The battle marked where the lines met, off the road: a cairn of five cobblestones and a sign, out of the
     * winner's stores. Where its sign stands, or null (nothing to build it of, or no open ground).
     */
    @Nullable
    static BlockPos cairn(ServerLevel level, Villages.Village winner, Ground g, String[] lines) {
        BlockPos at = surface(level, g.at(0, 9));
        if (!level.isLoaded(at) || !open(level, at, 2) || !open(level, at.relative(Direction.EAST), 1)) return null;
        if (!TownWork.take(level, winner, s -> s.is(Items.COBBLESTONE), 5)) return null;
        boolean sign = TownWork.take(level, winner, s -> s.is(net.minecraft.tags.ItemTags.SIGNS), 1)
            || TownWork.take(level, winner, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 2);
        if (!sign) {
            TownWork.give(level, winner, new ItemStack(Items.COBBLESTONE, 5));
            return null;
        }
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        level.setBlock(at, cobble, 3);
        level.setBlock(at.above(), cobble, 3);
        level.setBlock(at.relative(Direction.EAST), cobble, 3);
        level.setBlock(at.relative(Direction.WEST), Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
        level.setBlock(at.relative(Direction.NORTH), Blocks.COBBLESTONE_SLAB.defaultBlockState(), 3);
        BlockPos post = at.above(2);
        int rot = RotationSegment.convertToSegment(Armies.yawTo(-g.px(), -g.pz()) + 180.0F);
        level.setBlock(post, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), 3);
        if (level.getBlockEntity(post) instanceof SignBlockEntity s) {
            SignText text = new SignText();
            for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
            s.setText(text, true);
            s.setText(text, false);
            s.setWaxed(true);
        }
        return post;
    }
}
