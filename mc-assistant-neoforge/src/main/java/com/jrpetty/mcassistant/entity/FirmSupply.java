package com.jrpetty.mcassistant.entity;

import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * [econ-1d] The town's reserves and a paying firm.
 *
 * <p>The stores keep things back from anybody making to sell (Bench.keeps): the builders' working timber and stone, the
 * smith's last bars, the seed corn, and, while the age asks for it, every last log, stone or bar there is. That was right
 * when every maker was the town's own and made out of the town's goods for nothing; a private firm pays the market's ask
 * for its makings, and a market that keeps every bar of iron back from a toolsmith with coin in its hand (Aster's Tools,
 * "short of 2 iron ingots", losing forty-five coins a week on takings of under one) is not a market. So a firm at its own
 * bench, buying its makings, is held back less:
 * <ul>
 * <li><b>The town's working stock</b> (what the builders, the smith and the fields use day by day): kept at half for a
 *     paying firm, the town's own need covered first and the rest sold at the ask.</li>
 * <li><b>What the age asks for</b> (all of it, while it is short): a paying firm may still buy a ration of it, an eighth of
 *     what the stores hold and four at least, a day, all the firms of the town together: the age waits a little, the firm
 *     works, the market's price for it rises with the call on it.</li>
 * </ul>
 * The rest of the time (the town's own makers, its cook, its storekeeper) the keeps stand as they were.
 */
public final class FirmSupply {

    private FirmSupply() {}

    /** The share of the working keeps a paying firm leaves the town. */
    static final double WORKING_SHARE = 0.5;
    /** The age's reserve a paying firm may draw on a day: this share of what is held, at least LEAST_RATION. */
    static final int RATION_PART = 8, LEAST_RATION = 4;

    private static final ThreadLocal<Boolean> PAYING = ThreadLocal.withInitial(() -> false);
    /** Today's draw on the age's reserve by the firms, by "village|item": {day, n}. */
    private static final Map<String, long[]> DRAWN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DRAWN.clear();
    }

    /** Is a paying firm at its bench now? */
    static boolean paying() {
        return PAYING.get();
    }

    /** Work out (or make) on a paying firm's account: the keeps eased while it runs. */
    static <T> T asFirm(Supplier<T> work) {
        boolean was = PAYING.get();
        PAYING.set(true);
        try {
            return work.get();
        } finally {
            PAYING.set(was);
        }
    }

    /**
     * What is kept back of a pile from a paying firm, given what the town keeps from its own makers ({@code keep}, the
     * whole pile when the age asks for all of it).
     */
    static int keep(UUID village, Item it, int held, int keep, boolean forTheAge) {
        if (!forTheAge) return (int) Math.ceil(keep * WORKING_SHARE);
        int ration = Math.max(LEAST_RATION, held / RATION_PART);
        long[] d = DRAWN.get(village + "|" + it);
        long today = Accounts.dayNow();
        int left = (int) Math.max(0, ration - (d != null && d[0] == today ? d[1] : 0));
        return Math.max(0, Math.min(keep, held - left));
    }

    /** A firm's making took these out of the stores: off the day's ration of the age's reserve. */
    static void drew(UUID village, Map<Item, Integer> takes) {
        if (village == null || takes == null) return;
        long today = Accounts.dayNow();
        if (DRAWN.size() > 4096) DRAWN.clear();
        for (Map.Entry<Item, Integer> e : takes.entrySet()) {
            long[] d = DRAWN.computeIfAbsent(village + "|" + e.getKey(), k -> new long[]{ today, 0 });
            if (d[0] != today) { d[0] = today; d[1] = 0; }
            d[1] += e.getValue();
        }
    }
}
