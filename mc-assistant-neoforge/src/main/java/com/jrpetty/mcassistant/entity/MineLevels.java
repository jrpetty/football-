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
        if (w.spent == 0) return;
        List<MineWorks.Lvl> firsts = new ArrayList<>(plan);
        boolean mountain = MineWorks.mountain(level, w), badlands = MineWorks.badlands(level, w);
        for (MineWorks.Lvl first : firsts) {
            if (!spent(w, first.id())) continue;
            List<Integer> at = places(w, firsts, first, floor, mountain, badlands);
            for (int k = 1; k <= at.size(); k++) {
                int id = id(first.id(), k);
                int sides = at.get(k - 1);
                plan.add(new MineWorks.Lvl(id, name(id), sides, w.top - 3 * sides));
                if (!spent(w, id)) break;
            }
        }
        plan.sort(Comparator.comparingInt(MineWorks.Lvl::sides));
    }

    /** Is this level worked out: every drift on it closed and every branch off them cut? (Its landing is cut.) */
    static boolean workedOut(ServerLevel level, MineWorks.Works w, MineWorks.Lvl l, List<MineWorks.Drift> here) {
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
        Villages.tell(w.village, day, "the miners worked out " + l.name() + " of the mine (Y" + l.y() + ")"
            + (next != null ? "; " + next.name() + " is to be opened off the shaft at Y" + next.y()
            : "; there is no more of its " + ore(base(l.id())).word + " within the shaft's reach"));
        com.mojang.logging.LogUtils.getLogger().info("[MCA-WORKS] {}: {} worked out ({} drifts){}", Villages.name(w.village), l.name(),
            countOn(w, l.id()), next != null ? "; next " + next.name() + " at Y" + next.y() : "; no further level of its ore");
    }

    private static int countOn(MineWorks.Works w, int id) {
        int n = 0;
        for (MineWorks.Drift d : w.drifts) if (d.level == id) n++;
        return n;
    }
}
