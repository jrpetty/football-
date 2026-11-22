package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32] The supply chains lean the town's shares of hands: what runs short gets the hands that make it.
 *
 * <p>The fuel for the smelters comes from the mine's coal and from the woodcutters' logs (burnt into charcoal), and a town's
 * share of woodcutters was its size and its land's and nothing else: a Stone Age town at no logs for four days running, its
 * coal levels worked out and its smelters burning wood, kept the same two or three woodcutters it had at twenty folk. Now,
 * looked at once a minute (from Villages.weighGluts):
 * <ul>
 * <li><b>Fuel short</b> (the stores' coal under the floor the town keeps, Fuel) and timber short (logs under what the
 *     builders keep, half the houses' want): half as many woodcutters again; with no logs at all, twice as many.</li>
 * <li><b>Timber short alone</b> (under the builders' keep): a quarter more woodcutters.</li>
 * <li><b>Ore waiting</b> at the smelters (iron ore in the stores past two smelters' loads while the age wants iron): a
 *     smelter more.</li>
 * </ul>
 * Back to the plain shares as soon as the stores come right.
 */
public final class SupplyLean {

    private SupplyLean() {}

    private static final Map<UUID, Map<AssistantEntity.StationTask, Double>> LEAN = new ConcurrentHashMap<>();
    /** Why, for the books and the tests. */
    private static final Map<UUID, String> WHY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LEAN.clear();
        WHY.clear();
    }

    /** The lean on this trade's share (1: none). */
    public static double lean(@Nullable UUID village, AssistantEntity.StationTask trade) {
        Map<AssistantEntity.StationTask, Double> m = village == null ? null : LEAN.get(village);
        return m == null ? 1.0 : m.getOrDefault(trade, 1.0);
    }

    /** The extra hands this trade wants (added to the share), for the smelters' ore. */
    public static double extra(@Nullable UUID village, AssistantEntity.StationTask trade) {
        return trade == AssistantEntity.StationTask.SMELT && lean(village, trade) > 1.0 ? 1.0 : 0.0;
    }

    @Nullable
    public static String why(UUID village) {
        return WHY.get(village);
    }

    /** Weigh the town's chains afresh (Villages.weighGluts, once a minute, with the age's wants just read: is it short of iron). */
    public static void weigh(ServerLevel level, UUID village, boolean ironShort) {
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        int folk = Math.max(1, Villages.headcount(village));
        Map<AssistantEntity.StationTask, Double> m = new EnumMap<>(AssistantEntity.StationTask.class);
        StringBuilder why = new StringBuilder();
        int logs = Market.stock(level, village, s -> s.is(ItemTags.LOGS));
        int keep = Math.max(64, com.jrpetty.mcassistant.village.VillageMath.timberWanted(folk) / 2);
        boolean fuelLow = Villages.ageOf(village) != Villages.Age.WOOD && Fuel.low(level, village);
        if (logs < keep) {
            double w = fuelLow ? (logs <= 0 ? 2.0 : 1.5) : 1.25;
            m.put(AssistantEntity.StationTask.WOOD, w);
            why.append("timber ").append(logs).append(" of ").append(keep).append(fuelLow ? ", fuel low" : "").append(": woodcutters x").append(w);
        }
        if (ironShort) {
            int ore = Market.stock(level, village, SmelterFuel.IRON_ORE);
            if (ore >= 64) {
                m.put(AssistantEntity.StationTask.SMELT, 1.01);
                if (why.length() > 0) why.append("; ");
                why.append(ore).append(" iron ore waiting: a smelter more");
            }
        }
        LEAN.put(village, m);
        if (why.length() > 0) WHY.put(village, why.toString()); else WHY.remove(village);
    }
}
