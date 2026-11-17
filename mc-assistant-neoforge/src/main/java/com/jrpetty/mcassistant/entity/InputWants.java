package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [supply] The makings a town's trades want and cannot get: the word that reaches its producers.
 *
 * <p>A chandler wants honeycomb and string, a clothier leather, a bakery its wheat and eggs; and the town's beekeeper bottled
 * honey for the café whatever the comb fetched, its tailor spun string only for the smith's bows, its rancher culled a cow
 * only when the pen had more than four. A firm short of its makings at its bench (Enterprise.make), and a firm the town has
 * the call for but nothing to make it of (Enterprise.weigh: a clothier where there is no leather), say what they are short
 * of, and how many; and the producers who can get it see it the next time they are at their work:
 * <ul>
 * <li><b>honeycomb</b>: the beekeeper takes comb from a full hive (shears) rather than honey;</li>
 * <li><b>string</b>: the tailor spins a lock of wool into four lengths (and the watch keeps the spiders' string);</li>
 * <li><b>leather</b>: the rancher culls a cow past a breeding pair, for its hide and its beef, and the herd is bred back;</li>
 * <li><b>wheat</b> and the other crops: their price, which the want runs up, moves the town's fields (CropPlan).</li>
 * </ul>
 * A want is the last two mornings' (a firm's own word, and the look at the town's wants), so it lapses within a day or so of
 * being met. Kept in memory: a restart forgets it till the next morning's look.
 */
public final class InputWants {

    private InputWants() {}

    /** village -> item -> {the day it was last wanted, how many}. */
    private static final Map<UUID, Map<Item, long[]>> WANTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WANTS.clear();
    }

    /** Tests: a want said now (as a firm short of its makings would). */
    public static void wantForTests(UUID village, Item it, int n) {
        want(village, it, n);
    }

    /** A trade short of so many of this. */
    static void want(@Nullable UUID village, @Nullable Item it, int n) {
        if (village == null || it == null || it == Items.AIR || n <= 0) return;
        long day = Accounts.dayNow();
        long[] w = WANTS.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).computeIfAbsent(it, k -> new long[2]);
        if (w[0] < day - 1) w[1] = 0;
        w[0] = day;
        w[1] = Math.max(w[1], n);
        SupplyTrace.note(village, Stockroom.key(new ItemStack(it)), "wanted", n);
    }

    /** How many of this the town's trades want and could not get (in the last two days), nought if none. */
    public static int wanted(@Nullable UUID village, Item it) {
        if (village == null) return 0;
        Map<Item, long[]> m = WANTS.get(village);
        long[] w = m == null ? null : m.get(it);
        if (w == null || w[0] < Accounts.dayNow() - 1) return 0;
        return (int) Math.min(Integer.MAX_VALUE, w[1]);
    }

    /** A firm (or a firm the town wants) has nothing to make its ware of: what its bench is short of goes down as wanted. */
    static void shortFor(ServerLevel level, Villages.Village v, BusinessTypes.BusinessType t, String key, int n) {
        ItemStack one = Stockroom.sampleOf(key);
        if (one.isEmpty()) return;
        Bench.Hand hand = Bench.handOf(level, v, null, VillageFolkEntity.buildingFor(t.work()));
        Bench.Hand any = new Bench.Hand(Math.max(hand.skill(), 10), hand.maker(), hand.table(), hand.furnace(), hand.smoker(), hand.campfire(), hand.smithy());
        Bench.Plan plan = FirmSupply.asFirm(() -> Bench.plan(level, v, one.getItem(), 1, any));
        if (plan.ok() || plan.missing == null) return;
        want(v.id(), plan.missing, Math.max(1, plan.missingCount) * Math.max(1, n));
    }

    /**
     * Do a full hive's three combs fetch more at the market than its honey, less the bottle it goes in? Once a town has its
     * market, a beekeeper takes what pays (the café's honey or the chandler's comb), not whatever the café happens to want.
     */
    static boolean combPays(ServerLevel level, UUID village) {
        if (!MarketMaker.live(village)) return false;
        double comb = 3 * MarketMaker.bid(level, village, new ItemStack(Items.HONEYCOMB));
        double honey = MarketMaker.bid(level, village, new ItemStack(Items.HONEY_BOTTLE)) - MarketMaker.ask(level, village, new ItemStack(Items.GLASS_BOTTLE));
        return comb > honey;
    }

    /** Cows a pen keeps when it culls for hides: a breeding pair. */
    static final int COWS_KEPT = 2;

    /**
     * The rancher's cull when the town's trades want leather (VillageFolkEntity.cullNow, when the pen has nothing past its
     * usual herd): a grown cow past a breeding pair, for its hide and its beef, or null. The herd is bred back up as ever.
     */
    @Nullable
    static net.minecraft.world.entity.animal.Animal hideFor(ServerLevel level, @Nullable UUID village, net.minecraft.core.BlockPos pen, int r) {
        if (village == null || wanted(village, Items.LEATHER) <= Market.stock(level, village, s -> s.is(Items.LEATHER))) return null;
        net.minecraft.world.entity.animal.Animal a = Drover.surplus(level, pen, r, COWS_KEPT, net.minecraft.world.entity.EntityType.COW);
        return a instanceof net.minecraft.world.entity.animal.Cow ? a : null;
    }

    /** Everything wanted now, for the books and the tests: "leather 8, honeycomb 3". */
    public static String line(UUID village) {
        Map<String, Integer> out = new TreeMap<>();
        Map<Item, long[]> m = WANTS.get(village);
        if (m != null) for (Map.Entry<Item, long[]> e : m.entrySet()) {
            int n = wanted(village, e.getKey());
            if (n > 0) out.put(Stockroom.key(new ItemStack(e.getKey())).replace("minecraft:", ""), n);
        }
        if (out.isEmpty()) return "nothing";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : out.entrySet()) sb.append(e.getKey()).append(' ').append(e.getValue()).append(", ");
        return sb.substring(0, sb.length() - 2);
    }

    /** The wants as a map (tests). */
    public static Map<Item, Integer> all(UUID village) {
        Map<Item, Integer> out = new HashMap<>();
        Map<Item, long[]> m = WANTS.get(village);
        if (m != null) for (Item it : m.keySet()) if (wanted(village, it) > 0) out.put(it, wanted(village, it));
        return out;
    }
}
