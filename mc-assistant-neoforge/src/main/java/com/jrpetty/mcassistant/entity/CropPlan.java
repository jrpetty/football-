package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [supply] What a town's farmers sow, by what it fetches.
 *
 * <p>A farmer sowed back whatever it had just cut, and broke new ground in the mix of seed it happened to carry, so a town's
 * fields stayed as its first farmers laid them out whatever the market paid: its wheat never held in stock and priced at
 * twice its usual, its potatoes in a glut, and not a furrow moved. A real farmer sows what pays. Each crop is weighed by
 * what a block of it brings in a harvest at the market's bid (a potato plant gives two or three and one goes back in the
 * ground; a wheat plant one sheaf, and its seed back with some to spare), and the town's fields are shared out between the
 * crops by that, the dearer the more (a crop that pays a fifth more per block gets about three fifths more of the ground),
 * never under a tenth of the ground to any crop a farmer may grow, so the town never loses a crop for good. A farmer
 * cutting a crop that has more than its share of its ground sows the next one short of its share in its place, now and
 * then, so its fields turn over a harvest at a time: a baker's call for wheat, which runs its price up, brings wheat in
 * a few days later, and a glut of potatoes moves ground out of potatoes.
 *
 * <p>Before a town's market opens (its founders' commons) there are no prices to go by, and the farmers sow as before.
 */
public final class CropPlan {

    private CropPlan() {}

    /** One crop: its seed, what it is grown for, and what a block gives a harvest, net of the seed sown back. */
    record Crop(Item seed, Item produce, double produceNet, double seedNet) {}

    /** Wheat: a sheaf and one to three seed (one sown back); the roots: two or three (one sown back); beetroot as wheat. */
    static final List<Crop> CROPS = List.of(
        new Crop(Items.WHEAT_SEEDS, Items.WHEAT, 1.0, 0.71),
        new Crop(Items.POTATO, Items.POTATO, 1.71, 0),
        new Crop(Items.CARROT, Items.CARROT, 1.71, 0),
        new Crop(Items.BEETROOT_SEEDS, Items.BEETROOT, 1.0, 0.71));

    /** How strongly the ground follows what a block pays (shares by its value to this power). */
    static final double RESPONSE = 3.0;
    /** No crop a farmer may grow gets less of the ground than this. */
    static final double LEAST_SHARE = 0.1;
    /** The harvests remembered (as a fading tally) for what a farmer's fields hold now. */
    static final double FADE = 0.98;
    /** A crop this far over its share before its ground is turned over to another. */
    static final double SLACK = 0.04;

    private static final Map<UUID, Map<Item, Double>> PLAN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PLANNED_AT = new ConcurrentHashMap<>();
    /** Each farmer's fields, as its harvests show them: a fading tally of the crops it cut, by their seed. */
    private static final Map<UUID, Map<Item, Double>> FIELDS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PLAN.clear();
        PLANNED_AT.clear();
        FIELDS.clear();
    }

    @Nullable
    static Crop cropOf(Item seed) {
        for (Crop c : CROPS) if (c.seed() == seed) return c;
        return null;
    }

    /** What a block of this crop brings its farmer a harvest at the market's bid, coin. */
    static double perBlock(ServerLevel level, UUID village, Crop c) {
        double v = MarketMaker.bid(level, village, new ItemStack(c.produce())) * c.produceNet();
        if (c.seedNet() > 0) v += MarketMaker.bid(level, village, new ItemStack(c.seed())) * c.seedNet();
        return Math.max(0.0001, v);
    }

    /** The town's plan: each crop's share of the ground, by what a block of it pays (looked at again every minute). Empty
     *  before the market opens. */
    public static Map<Item, Double> plan(ServerLevel level, UUID village) {
        if (village == null || !MarketMaker.live(village)) return Map.of();
        long now = level.getGameTime();
        Long at = PLANNED_AT.get(village);
        Map<Item, Double> was = PLAN.get(village);
        if (was != null && at != null && now - at < 1200 && now >= at) return was;
        Map<Item, Double> raw = new LinkedHashMap<>();
        double sum = 0;
        for (Crop c : CROPS) {
            double w = Math.pow(perBlock(level, village, c), RESPONSE);
            raw.put(c.seed(), w);
            sum += w;
        }
        Map<Item, Double> out = new LinkedHashMap<>();
        if (sum <= 0) return Map.of();
        // The floor, and the rest shared by value.
        double floorTotal = LEAST_SHARE * CROPS.size();
        for (Map.Entry<Item, Double> e : raw.entrySet()) out.put(e.getKey(), LEAST_SHARE + (1 - floorTotal) * e.getValue() / sum);
        PLAN.put(village, out);
        PLANNED_AT.put(village, now);
        return out;
    }

    /** The plan among the crops this farmer may grow, shares renormalised. */
    static Map<Item, Double> planFor(ServerLevel level, UUID village, Predicate<Item> mayGrow) {
        Map<Item, Double> p = plan(level, village);
        if (p.isEmpty()) return p;
        Map<Item, Double> out = new HashMap<>();
        double sum = 0;
        for (Map.Entry<Item, Double> e : p.entrySet()) if (mayGrow.test(e.getKey())) { out.put(e.getKey(), e.getValue()); sum += e.getValue(); }
        if (sum <= 0) return Map.of();
        for (Map.Entry<Item, Double> e : out.entrySet()) e.setValue(e.getValue() / sum);
        return out;
    }

    /** This farmer's fields as its harvests show them: each crop's share, and the tally behind it. */
    static Map<Item, Double> fields(UUID farmer) {
        Map<Item, Double> t = FIELDS.get(farmer);
        if (t == null) return Map.of();
        double sum = 0;
        for (double x : t.values()) sum += x;
        Map<Item, Double> out = new HashMap<>();
        if (sum <= 0) return out;
        for (Map.Entry<Item, Double> e : t.entrySet()) out.put(e.getKey(), e.getValue() / sum);
        return out;
    }

    static double tallied(UUID farmer) {
        Map<Item, Double> t = FIELDS.get(farmer);
        double sum = 0;
        if (t != null) for (double x : t.values()) sum += x;
        return sum;
    }

    /**
     * A crop cut (FarmGoal): the seed to sow back in its place. Its own, unless it has more than its share of the farmer's
     * ground and the farmer carries the seed of a crop short of its share, when now and then (as far over its share as it
     * is) the other goes in. {@code carries}: whether the farmer has a seed in hand.
     */
    public static Item sowBack(AssistantEntity farmer, Item own, Predicate<Item> mayGrow, Predicate<Item> carries) {
        if (!(farmer.level() instanceof ServerLevel level) || cropOf(own) == null) return own;
        UUID village = farmer.ownerId();
        Map<Item, Double> t = FIELDS.computeIfAbsent(farmer.getUUID(), k -> new ConcurrentHashMap<>());
        for (Map.Entry<Item, Double> e : t.entrySet()) e.setValue(e.getValue() * FADE);
        t.merge(own, 1.0, Double::sum);
        if (village == null || tallied(farmer.getUUID()) < 12) return own;
        Map<Item, Double> plan = planFor(level, village, mayGrow);
        if (plan.isEmpty() || !plan.containsKey(own)) return own;
        Map<Item, Double> now = fields(farmer.getUUID());
        double over = now.getOrDefault(own, 0.0) - plan.get(own);
        // Half of what is over its share at the most, a harvest: a field turns over in a few harvests, not all at once.
        if (over <= SLACK || farmer.getRandom().nextDouble() >= Math.min(0.5, over / Math.max(0.01, now.getOrDefault(own, 0.0)))) return own;
        Item best = null;
        double most = SLACK;
        for (Map.Entry<Item, Double> e : plan.entrySet()) {
            if (e.getKey() == own || !carries.test(e.getKey())) continue;
            double shortBy = e.getValue() - now.getOrDefault(e.getKey(), 0.0);
            if (shortBy > most) { most = shortBy; best = e.getKey(); }
        }
        if (best == null) return own;
        // The tally moves with the field: one block less of the old crop, one more of the new.
        t.merge(own, -1.0, Double::sum);
        t.merge(best, 1.0, Double::sum);
        SupplyTrace.note(village, Stockroom.key(new ItemStack(best)), "sown in place of " + Stockroom.key(new ItemStack(own)).replace("minecraft:", ""), 1);
        return best;
    }

    /**
     * New ground (FarmGoal): which of the seeds in hand to sow, the crop furthest short of its share of this farmer's fields
     * (as its harvests show them, and what it has sown this round); null to go by the old way (no plan yet).
     */
    @Nullable
    public static Item forNewGround(AssistantEntity farmer, Map<Item, Integer> sownThisRound, Predicate<Item> mayGrow, Predicate<Item> carries) {
        if (!(farmer.level() instanceof ServerLevel level) || farmer.ownerId() == null) return null;
        Map<Item, Double> plan = planFor(level, farmer.ownerId(), mayGrow);
        if (plan.isEmpty()) return null;
        Map<Item, Double> t = new HashMap<>(FIELDS.getOrDefault(farmer.getUUID(), Map.of()));
        for (Map.Entry<Item, Integer> e : sownThisRound.entrySet()) t.merge(e.getKey(), (double) e.getValue(), Double::sum);
        double sum = 0;
        for (double x : t.values()) sum += x;
        Item best = null;
        double most = -1e9;
        for (Map.Entry<Item, Double> e : plan.entrySet()) {
            if (!carries.test(e.getKey())) continue;
            double have = sum <= 0 ? 0 : t.getOrDefault(e.getKey(), 0.0) / sum;
            double shortBy = e.getValue() - have;
            if (shortBy > most) { most = shortBy; best = e.getKey(); }
        }
        return best;
    }

    /** The plan in a line, for the books and the tests: "wheat 34% (0.31c a block), potato 22% ...". */
    public static String line(ServerLevel level, UUID village) {
        Map<Item, Double> p = plan(level, village);
        if (p.isEmpty()) return "no plan (the market is not open)";
        StringBuilder sb = new StringBuilder();
        for (Crop c : CROPS) {
            sb.append(Stockroom.key(new ItemStack(c.produce())).replace("minecraft:", "")).append(' ')
                .append(Math.round(p.getOrDefault(c.seed(), 0.0) * 100)).append("% (")
                .append(String.format(java.util.Locale.ROOT, "%.2f", perBlock(level, village, c))).append("c a block), ");
        }
        return sb.toString();
    }
}
