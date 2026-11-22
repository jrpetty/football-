package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * [growth24] A level of the mine worked out, and the next level of its ore opened off the same shaft.
 *
 * <p>The works had five levels and no more (MineWorks.plan): the coal, the copper, the iron, the gold and the deep level,
 * each a grid of drifts and branches inside the mine's ground. The ground is set aside once, round the first face, and a
 * level's drifts reach its edge, the caves and the water in a few weeks. The four-hundred-day run's town cut its coal
 * level out by day ten (322 blocks of tunnel), its copper level by day thirty-five, its iron level by day forty-five
 * (2,686 blocks, 382 ore of every kind), its gold level by day seventy-five and its deep level by day one hundred and
 * forty; from then on its eight miners came up every shift with "Nothing free to cut in the works just now", and the town
 * sat in the Iron Age for two hundred and forty-five days with no iron and no coal in its stores.
 *
 * <p>A real mine worked out at one level opens another under (or over) it in the same ground. So now: a level is
 * <em>worked out</em> when every drift on it is closed and every branch cut (nothing left to start, nothing left to
 * finish, nobody at a face); it is marked so for good, and the next level of the same ore is planned off the shaft, two
 * turns of the stair (six blocks) above or below the last, where the ground is still rich in that ore (the shapes in
 * MineDemand.rich): the coal level's under it, the iron level's at 22, 10, 28, 4..., the deep level's over it. A level of
 * one ore never comes nearer another ore's level than its own, so the levels of two ores never meet. Up to
 * {@link #MOST} more a level, each opened when the one before it is worked out. The miners are shared out over the
 * levels not yet worked out (MineDemand.share), and a worked-out level is never looked over again for work.
 */
public final class MineLevels {

    private MineLevels() {}

    /** The levels the works were first planned with (MineWorks.NAMES); a further level's id is its first's plus five a turn. */
    static final int BASES = 5;
    /** At most this many further levels for each first one (ids up to 29: the works keep them in bits of an int). */
    public static final int MOST = 5;
    /** A further level is opened only where the ground is at least this rich in its ore (nought to one). */
    static final double RICH_ENOUGH = 0.3;
    /** Two levels of one ore lie at least this many sides of the shaft apart (three blocks a side): four of rock between. */
    static final int APART = 2;

    private static final String[] ORDINAL = { "", "second ", "third ", "fourth ", "fifth ", "sixth " };

    static int base(int id) {
        return Math.floorMod(id, BASES);
    }

    static int nth(int id) {
        return id / BASES;
    }

    static int id(int base, int nth) {
        return base + BASES * nth;
    }

    /** "the iron level", "the second iron level". */
    static String name(int id) {
        int k = id < 0 ? 0 : nth(id);
        String first = MineWorks.NAMES[base(id)];
        if (k == 0 || k >= ORDINAL.length) return first;
        return "the " + ORDINAL[k] + first.substring(4);
    }

    /** The ore a level is cut for. */
    static MineDemand.Ore ore(int base) {
        return switch (base) {
            case MineWorks.COAL -> MineDemand.Ore.COAL;
            case MineWorks.COPPER -> MineDemand.Ore.COPPER;
            case MineWorks.IRON -> MineDemand.Ore.IRON;
            case MineWorks.GOLD -> MineDemand.Ore.GOLD;
            default -> MineDemand.Ore.DIAMOND;
        };
    }

    static boolean spent(MineWorks.Works w, int id) {
        return id >= 0 && id < 32 && (w.spent & (1 << id)) != 0;
    }

    /**
     * Where the further levels of this first level go, in the order they are opened, as sides of the shaft down: two sides
     * at a time out from it, the richer way first (the nearer the top when as rich), never higher than four sides under
     * the top nor under the floor, never where the ground is poor in its ore, and always nearer its own first level than
     * any other first level by more than a side.
     */
    static List<Integer> places(MineWorks.Works w, List<MineWorks.Lvl> firsts, MineWorks.Lvl first, int floor, boolean mountain, boolean badlands) {
        List<Integer> out = new ArrayList<>();
        MineDemand.Ore o = ore(first.id());
        for (int j = 1; j <= 16 && out.size() < MOST; j++) {
            int up = first.sides() - APART * j, down = first.sides() + APART * j;
            double ru = ok(w, firsts, first, up, floor) ? MineDemand.rich(o, w.top - 3 * up, mountain, badlands) : -1;
            double rd = ok(w, firsts, first, down, floor) ? MineDemand.rich(o, w.top - 3 * down, mountain, badlands) : -1;
            if (ru < RICH_ENOUGH && rd < RICH_ENOUGH) {
                if (ru < 0 && rd < 0 && j > 2) break;         // off the ground's ends both ways, or into another ore's
                continue;
            }
            if (ru >= rd) {
                out.add(up);
                if (rd >= RICH_ENOUGH && out.size() < MOST) out.add(down);
            } else {
                out.add(down);
                if (ru >= RICH_ENOUGH && out.size() < MOST) out.add(up);
            }
        }
        return out;
    }

    private static boolean ok(MineWorks.Works w, List<MineWorks.Lvl> firsts, MineWorks.Lvl first, int sides, int floor) {
        if (sides < 4 || w.top - 3 * sides < floor) return false;
        int mine = Math.abs(sides - first.sides());
        for (MineWorks.Lvl o : firsts) {
            if (o.id() == first.id()) continue;
            if (Math.abs(sides - o.sides()) <= mine + 1) return false;
        }
        return true;
    }

    /** The plan's further levels: for each level worked out, the next of its ore (and so on while those are worked out). */
    static void extend(ServerLevel level, MineWorks.Works w, List<MineWorks.Lvl> plan, int floor) {
        if (w.spent == 0 && w.rooms < (1 << BASES)) return;
        List<MineWorks.Lvl> firsts = new ArrayList<>(plan);
        boolean mountain = MineWorks.mountain(level, w), badlands = MineWorks.badlands(level, w);
        for (MineWorks.Lvl first : firsts) {
            if (!spent(w, first.id()) && (w.rooms & (1 << id(first.id(), 1))) == 0) continue;
            List<Integer> at = places(w, firsts, first, floor, mountain, badlands);
            for (int k = 1; k <= at.size(); k++) {
                int id = id(first.id(), k);
                // The next opened once the one before is worked out; and one opened stays (its ground widened, MineReach,
                // the one before may be worked again).
                if (!spent(w, id(first.id(), k - 1)) && (w.rooms & (1 << id)) == 0) break;
                int sides = at.get(k - 1);
                plan.add(new MineWorks.Lvl(id, name(id), sides, w.top - 3 * sides));
            }
        }
        plan.sort(Comparator.comparingInt(MineWorks.Lvl::sides));
    }

    // ------------------------------------------------------------------ [econ32] a level gone barren

    /** Blocks of drift cut on each level since its last ore, by town. Kept in memory (a restart starts the count again). */
    private static final java.util.Map<java.util.UUID, long[]> CUT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Blocks of drift cut on a level since the last ore came out of it before it is given up as barren (the deep level more:
     *  its ore is scarce). The iron level of a local run gave an ore in seven blocks of tunnel on the whole. */
    static final int BARREN_BLOCKS = 400, BARREN_DEEP = 800;

    /** So many blocks of drift cut on this level, with so much ore out of them (MineWorks.done): ore starts the count again. */
    static void cut(MineWorks.Works w, int id, int blocks, int ore) {
        if (id < 0 || id >= BASES * (MOST + 1)) return;
        long[] c = CUT.computeIfAbsent(w.village, k -> new long[BASES * (MOST + 1)]);
        synchronized (c) {
            if (ore > 0) c[id] = 0;
            else c[id] += Math.max(0, blocks);
        }
    }

    /** Blocks of drift cut on this level since its last ore. */
    static int sinceOre(MineWorks.Works w, int id) {
        long[] c = CUT.get(w.village);
        if (c == null || id < 0 || id >= BASES * (MOST + 1)) return 0;
        return (int) c[id];
    }

    /**
     * Has this level gone barren: hundreds of blocks of drift cut on it since the last ore came out of it? The local run's
     * Iron Age town's iron level had 2,775 blocks of tunnel and 393 ore in all, and brought "ore this week 0" for days with
     * eight miners on it: the ore near the shaft had been taken, and the drifts ran on through bare rock towards the edges of
     * the ground, ninety blocks out, while the second iron level waited for every one of them to reach an edge. A barren
     * level is worked out as surely as one cut to its ends: the next level of its ore is opened.
     */
    static boolean barren(MineWorks.Works w, MineWorks.Lvl l) {
        if (l.id() < 0 || l.id() >= w.oreWeek.length) return false;
        return sinceOre(w, l.id()) >= (base(l.id()) == MineWorks.DEEP ? BARREN_DEEP : BARREN_BLOCKS);
    }

    /** The week's count for this level started again (it was found barren and marked worked out). */
    static void clearCut(MineWorks.Works w, int id) {
        long[] c = CUT.get(w.village);
        if (c != null && id >= 0 && id < BASES * (MOST + 1)) synchronized (c) { c[id] = 0; }
    }

    /** Tests: so many blocks cut on this level this week. */
    public static void cutForTests(net.minecraft.server.level.ServerLevel level, java.util.UUID village, int id, int blocks) {
        MineWorks.Works w = MineWorks.works(level, village);
        if (w != null) cut(w, id, blocks, 0);
    }

    public static void forgetCutForTests() {
        CUT.clear();
    }

    /** Is this level worked out: every drift on it closed and every branch off them cut? (Its landing is cut.) */
    static boolean workedOut(ServerLevel level, MineWorks.Works w, MineWorks.Lvl l, List<MineWorks.Drift> here) {
        // [econ32b] Never before it has been dug: a level with no drift on it, or none cut a block, is laid out first. Four
        // levels of a long run were "worked out (0 drifts)" the moment they opened, their landing walls refused (the plan's
        // reach run out over the mine: MineWorks.inGround), and the next of their ore opened on top of them.
        int dug = 0;
        for (MineWorks.Drift d : here) dug += d.path.length();
        if (dug == 0) return false;
        for (MineWorks.Drift d : here) {
            if (d.closed == '-') return false;
            if (MineWorks.pendingBranches(level, w, d, l) > 0) return false;
        }
        return true;
    }

    /** The level marked worked out for good, and the town told where the next of its ore is to be cut. */
    static void markSpent(ServerLevel level, MineWorks.Works w, MineWorks.Lvl l) {
        if (spent(w, l.id()) || l.id() >= 32) return;
        w.spent |= 1 << l.id();
        MineWorks.save(w);
        MineDemand.reshare(w.village);
        MineWorks.Lvl next = MineWorks.lvl(MineWorks.plan(level, w), id(base(l.id()), nth(l.id()) + 1));
        long day = level.getDayTime() / 24000L;
        if (next == null && !MineReach.widen(level, w.village).isEmpty()) {
            // [growth24] Every level of its ore worked out: the ground widened, and the drifts stopped at its old edge driven on.
            int reopened = reopenAtEdge(w, base(l.id()));
            Villages.tell(w.village, day, "the miners worked out " + l.name() + " of the mine (Y" + l.y() + "), the last of its "
                + ore(base(l.id())).word + "; with the ground widened, " + reopened + " drifts stopped at its old edge are driven on");
            MineDemand.reshare(w.village);
            return;
        }
        Villages.tell(w.village, day, "the miners worked out " + l.name() + " of the mine (Y" + l.y() + ")"
            + (next != null ? "; " + next.name() + " is to be opened off the shaft at Y" + next.y()
            : "; there is no more of its " + ore(base(l.id())).word + " within the shaft's reach"));
        com.mojang.logging.LogUtils.getLogger().info("[MCA-WORKS] {}: {} worked out ({} drifts){}", Villages.name(w.village), l.name(),
            countOn(w, l.id()), next != null ? "; next " + next.name() + " at Y" + next.y() : "; no further level of its ore");
        if (next == null) MineSites.exhausted(level, w, base(l.id()));   // [econ32] the ground widened no more: a new mine for the iron or the diamonds
    }

    /** Tests: the depths (Y) the further levels of this first level would be opened at, in order. */
    public static List<Integer> placesForTests(ServerLevel level, java.util.UUID village, int first) {
        List<Integer> out = new ArrayList<>();
        MineWorks.Works w = MineWorks.works(level, village);
        if (w == null) return out;
        int spent = w.spent;
        w.spent = 0;
        List<MineWorks.Lvl> firsts = MineWorks.plan(level, w);
        w.spent = spent;
        MineWorks.Lvl f = MineWorks.lvl(firsts, first);
        if (f == null) return out;
        for (int s : places(w, firsts, f, level.getMinBuildHeight() + 14, MineWorks.mountain(level, w), MineWorks.badlands(level, w))) out.add(w.top - 3 * s);
        return out;
    }

    /**
     * Tests: this level cut to its ends, as its miners would leave it worked out: a drift (closed at the edge) from every
     * wall of its landing nobody has started from, every drift on it closed, every branch off them done. Its landing is cut.
     */
    public static void cutOutForTests(ServerLevel level, java.util.UUID village, int id) {
        MineWorks.Works w = MineWorks.works(level, village);
        MineWorks.Lvl l = w == null ? null : MineWorks.lvl(MineWorks.plan(level, w), id);
        if (l == null) return;
        net.minecraft.core.BlockPos r = MineWorks.roomMiddle(w, l);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            int ox = r.getX() + 2 * d.getStepX(), oz = r.getZ() + 2 * d.getStepZ();
            boolean taken = false;
            for (MineWorks.Drift o : w.drifts) if (o.level == id && o.ox == ox && o.oz == oz) taken = true;
            if (taken) continue;
            MineWorks.Drift n = new MineWorks.Drift();
            n.id = w.nextId++;
            n.level = id;
            n.ox = ox;
            n.oz = oz;
            n.heading = d.get2DDataValue();
            w.drifts.add(n);
        }
        for (MineWorks.Drift d : w.drifts) {
            if (d.level != id) continue;
            if (d.closed == '-') d.closed = 'e';
            for (int a = MineWorks.BRANCH_EVERY; a < d.path.length() - 1; a += MineWorks.BRANCH_EVERY) {
                if (!MineWorks.anchor(d, a)) continue;
                for (int side : new int[]{ 1, -1 }) {
                    int[] b = d.branch(a, side);
                    if (b == null) d.branches.add(new int[]{ a, side, 0, 1 }); else b[3] = 1;
                }
            }
        }
        MineWorks.save(w);
    }

    /** Tests: this level marked worked out, quietly (no news, no widening): as the miners leave a level they have done with. */
    public static void spendForTests(ServerLevel level, java.util.UUID village, int id) {
        MineWorks.Works w = MineWorks.works(level, village);
        if (w == null) return;
        w.spent |= 1 << id;
        MineWorks.save(w);
    }

    /** Tests: this level found worked out by a miner, now (MineWorks.driftTask's end): marked, the next opened or the ground widened. */
    public static void markSpentForTests(ServerLevel level, java.util.UUID village, int id) {
        MineWorks.Works w = MineWorks.works(level, village);
        MineWorks.Lvl l = w == null ? null : MineWorks.lvl(MineWorks.plan(level, w), id);
        if (l != null) markSpent(level, w, l);
    }

    /** Tests: the closed mark of each drift on this level (as chars). */
    public static String closedForTests(ServerLevel level, java.util.UUID village, int id) {
        MineWorks.Works w = MineWorks.works(level, village);
        StringBuilder sb = new StringBuilder();
        if (w != null) for (MineWorks.Drift d : w.drifts) if (d.level == id) sb.append(d.closed);
        return sb.toString();
    }

    /** Tests: is this level marked worked out? */
    public static boolean spentForTests(ServerLevel level, java.util.UUID village, int id) {
        MineWorks.Works w = MineWorks.works(level, village);
        return w != null && spent(w, id);
    }

    /** The drifts of this ore's levels stopped at the edge of the ground opened again, and those levels no longer worked out. */
    static int reopenAtEdge(MineWorks.Works w, int base) {
        int n = 0;
        for (MineWorks.Drift d : w.drifts) {
            if (base(d.level) != base || d.closed != 'e') continue;
            d.closed = '-';
            d.blocked = false;
            d.turns = 0;
            d.bends = 0;
            w.spent &= ~(1 << d.level);
            n++;
        }
        MineWorks.save(w);
        return n;
    }

    private static int countOn(MineWorks.Works w, int id) {
        int n = 0;
        for (MineWorks.Drift d : w.drifts) if (d.level == id) n++;
        return n;
    }
}
