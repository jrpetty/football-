package com.jrpetty.mcassistant.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-1e] What a made thing is worth in a town today: what goes into it at the town's going prices, its maker's time at
 * the going wage, and a normal margin on top: the value its maker adds.
 *
 * <p>The town's prices were reckoned ware by ware, each by what is short and what is plenty (PriceIndex), the made thing
 * apart from what it is made of. Bread's going price came out barely over the three wheat in it at the market's ask, so a
 * bakery buying its wheat and paying its baker lost on every loaf it sold at the going price (a twenty-day town's only
 * bakery had nineteen in twenty of its town's bread trade and lost fifty-five coins on it). In a real market a made thing's
 * price settles where its makers make a living: its makings, the labour and time it takes, and a normal return on the
 * work, or nobody would make it. So a made thing's going price is never under that (PriceIndex.each): its makings at the
 * town's own going prices today (wheat dear, bread dear with it), its maker's time at the going wage over what one pair of
 * hands makes of it in a day (a baker some thirty loaves, a toolsmith a dozen tools, a clothier eight coats: the firms'
 * own day's work, BusinessTypes; the cost book's for the rest), the fuel, and a margin of fifteen in the hundred. Scarce,
 * it goes above that by the town's supply and demand as before; a glut can still push it a fifth under, as a crowded
 * market does to the makers in it.
 *
 * <p>In PriceIndex's coin (a hamlet's, before the town's price level). Worked out once a minute a thing a town, once its
 * market is open (the founders' commons keep the old prices).
 */
public final class ValueAdded {

    private ValueAdded() {}

    /** The normal return on a maker's work, over its makings and its time. */
    static final double MARGIN = 0.15;
    /** Into a glut a made thing's price may go this far under its makings, time and margin, and no further. */
    static final double GLUT_LEAST = 0.8;
    /** How many steps back a made thing's makings are themselves priced as made (a pick's sticks of planks of logs). */
    static final int DEPTH = 4;
    /** How long a worked-out worth is kept, ticks. */
    static final long FRESH = 1200;

    private static final Map<String, double[]> MEMO = new ConcurrentHashMap<>();
    private static final ThreadLocal<int[]> DEEP = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile Map<String, BusinessTypes.BusinessType> MAKERS;

    public static void resetForTests() {
        MEMO.clear();
    }

    /** Is this way of having a thing a making (a recipe at a bench, a furnace, the stonecutter, the smithing table)? */
    static boolean made(CostBook.Basis b) {
        if (b.parts().isEmpty()) return false;
        String how = b.how();
        return how.startsWith("crafted") || how.startsWith("cooked") || how.startsWith("smelted") || how.startsWith("cut")
            || how.startsWith("forged");
    }

    /**
     * One of this made thing's worth in this town today, PriceIndex coin: its makings at the going prices, its maker's time
     * and fuel, and the margin. Nought for a thing not made (gathered, bought in, a coin).
     */
    public static double worth(UUID village, ItemStack one) {
        if (village == null || one.isEmpty() || Market.isCoin(one) || one.is(Items.GOLD_INGOT)) return 0;
        if (!MarketMaker.live(village)) return 0;                       // the founders' commons: the old prices
        Item item = one.getItem();
        CostBook.Basis b = CostBook.of(item);
        if (!made(b)) return 0;
        String key = village + "|" + b.id();
        long now = now();
        double[] m = MEMO.get(key);
        if (m != null && now >= 0 && now >= (long) m[0] && now - (long) m[0] < FRESH) return m[1];
        int[] deep = DEEP.get();
        if (deep[0] >= DEPTH) return 0;                                 // far enough back: its own price stands
        deep[0]++;
        double w;
        try {
            double makings = 0;
            for (CostBook.Part p : b.parts()) {
                Item part = CostBook.itemOf(p.id());
                if (part == null || part == Items.AIR) { makings += p.total(); continue; }
                makings += p.n() * PriceIndex.each(null, village, new ItemStack(part));
            }
            w = (Math.max(0, makings) + b.fuel() + labour(village, b, one)) * (1 + MARGIN);
        } finally {
            deep[0]--;
        }
        if (MEMO.size() > 8192) MEMO.clear();
        MEMO.put(key, new double[]{ now, w });
        return w;
    }

    /**
     * Its maker's time a piece, PriceIndex coin, with the workshop's share: the town's going wage for the trade that makes it
     * (over its price level: PriceIndex's coin is a hamlet's) over what a hand makes of it in a day, as a firm of that trade
     * works (a bakery thirty-two loaves); else the cost book's own.
     */
    static double labour(UUID village, CostBook.Basis b, ItemStack one) {
        BusinessTypes.BusinessType t = makerOf(one);
        if (t == null || t.perDay() <= 0) return b.labour() + b.overhead();
        double wage;
        try {
            wage = Math.max(1, JobWorth.rate(t.work(), village)) / Math.max(0.1, MarketMaker.priceLevel(village));
        } catch (RuntimeException e) {
            wage = CostBook.dayWage(t.work());
        }
        return wage / t.perDay() * (1 + CostBook.SHOP_OVERHEAD);
    }

    /** The firm kind that makes this ware for sale, or null. */
    static BusinessTypes.BusinessType makerOf(ItemStack one) {
        Map<String, BusinessTypes.BusinessType> m = MAKERS;
        if (m == null) {
            m = new HashMap<>();
            for (BusinessTypes.BusinessType t : BusinessTypes.ALL) {
                if (t.resells()) continue;
                for (String w : t.goods()) m.putIfAbsent(w, t);
            }
            MAKERS = m;
        }
        return m.get(Stockroom.key(one));
    }

    /**
     * A made thing's going price against its worth: never under its makings, time and margin (scarce, over it by the town's
     * own reckoning), a glut taking it down a fifth at most. {@code indexed}: the town's price by supply and demand;
     * {@code factor}: today's price against the usual.
     */
    static double floor(double indexed, double worth, double factor) {
        if (worth <= 0) return indexed;
        return Math.max(indexed, worth * Math.max(GLUT_LEAST, factor));
    }

    private static long now() {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? -1 : server.overworld().getGameTime();
    }
}
