package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.VillageMath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [mine8] What the town's mine is dug for, now: each ore's stock in the town's stores against what the town uses (the
 * age's wants, the smiths' and the firms' wants, the fuel), and where in the ground each ore lies (the depths this game
 * puts it at). The works send their miners to the levels where what the town is short of lies: a town of the Diamond Age
 * short of coal sends a miner back up to the coal; the rest keep on where they are wanted. A mix, never all of them to
 * one ore: each miner sent to an ore takes some of the want off it for the next.
 *
 * <p>Before this a miner's depth was the age's question alone (VillageFolkEntity.seekTheSeam): down to the iron from the
 * Stone Age, the diamonds from the Diamond Age. A town past the Iron Age that ran out of coal for its smiths and its
 * torches never sent anybody back up for it, and nobody ever dug the copper, the redstone or the lapis its trades asked
 * for unless they met them on the way.
 */
public final class MineDemand {

    private MineDemand() {}

    /** The ores, the pick each wants (1 wood, 2 stone, 3 iron), and the forms the stores count. */
    public enum Ore {
        COAL("coal", 1, s -> s.is(Items.COAL) || s.is(Items.COAL_ORE) || s.is(Items.DEEPSLATE_COAL_ORE), s -> s.is(Items.COAL_BLOCK)),
        COPPER("copper", 2, s -> s.is(Items.COPPER_INGOT) || s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE),
            s -> s.is(Items.COPPER_BLOCK) || s.is(Items.RAW_COPPER_BLOCK)),
        IRON("iron", 2, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE),
            s -> s.is(Items.IRON_BLOCK) || s.is(Items.RAW_IRON_BLOCK)),
        LAPIS("lapis", 2, s -> s.is(Items.LAPIS_LAZULI) || s.is(Items.LAPIS_ORE) || s.is(Items.DEEPSLATE_LAPIS_ORE), s -> s.is(Items.LAPIS_BLOCK)),
        GOLD("gold", 3, s -> s.is(Items.GOLD_INGOT) || s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE) || s.is(Items.DEEPSLATE_GOLD_ORE),
            s -> s.is(Items.GOLD_BLOCK) || s.is(Items.RAW_GOLD_BLOCK)),
        REDSTONE("redstone", 3, s -> s.is(Items.REDSTONE) || s.is(Items.REDSTONE_ORE) || s.is(Items.DEEPSLATE_REDSTONE_ORE), s -> s.is(Items.REDSTONE_BLOCK)),
        DIAMOND("diamond", 3, s -> s.is(Items.DIAMOND) || s.is(Items.DIAMOND_ORE) || s.is(Items.DEEPSLATE_DIAMOND_ORE), s -> s.is(Items.DIAMOND_BLOCK)),
        EMERALD("emerald", 3, s -> s.is(Items.EMERALD) || s.is(Items.EMERALD_ORE) || s.is(Items.DEEPSLATE_EMERALD_ORE), s -> s.is(Items.EMERALD_BLOCK));

        public final String word;
        public final int tier;
        final Predicate<ItemStack> one, nine;

        Ore(String word, int tier, Predicate<ItemStack> one, Predicate<ItemStack> nine) {
            this.word = word;
            this.tier = tier;
            this.one = one;
            this.nine = nine;
        }
    }

    /**
     * How rich the ground is in this ore at this height, nought to one: the shapes this game lays each ore out in (coal in
     * a broad band peaking near ninety-six; copper peaking near forty-eight; iron near sixteen and again high in the
     * mountains; lapis about nought; gold about minus sixteen and up the badlands; redstone thick under minus thirty-two;
     * diamonds thicker the deeper, below about minus fifty; emeralds in the mountains only).
     */
    public static double rich(Ore o, int y, boolean mountain, boolean badlands) {
        return switch (o) {
            case COAL -> Math.max(tri(y, 0, 96, 192), y >= 136 ? 0.6 : 0);
            case COPPER -> tri(y, -16, 48, 112);
            case IRON -> Math.max(Math.max(tri(y, -24, 16, 56), y >= -64 && y <= 72 ? 0.15 : 0), mountain ? tri(y, 80, 232, 384) : 0);
            case LAPIS -> Math.max(tri(y, -32, 0, 32), y >= -64 && y <= 64 ? 0.2 : 0);
            case GOLD -> Math.max(tri(y, -64, -16, 32), badlands && y >= 32 && y <= 256 ? 0.6 : 0);
            case REDSTONE -> Math.max(tri(y, -96, -64, -32) * 1.0 + (y <= -32 ? 0.3 : 0), y <= 15 ? 0.25 : 0);
            case DIAMOND -> tri(y, -144, -64, 16);
            case EMERALD -> mountain ? tri(y, -16, 236, 480) : 0;
        };
    }

    private static double tri(int y, int lo, int peak, int hi) {
        if (y <= lo || y >= hi) return 0;
        return y <= peak ? (y - lo) / (double) (peak - lo) : (hi - y) / (double) (hi - peak);
    }

    /** What the town holds of an ore, and what it wants: [stock, need]. Looked at once a minute. */
    public record Want(int stock, int need) {
        public double shortage() { return need <= 0 ? 0 : Math.max(0, need - stock) / (double) need; }
        public boolean short_() { return need > 0 && stock < need; }
    }

    private static final Map<UUID, Map<Ore, Want>> WANTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WANTS_AT = new ConcurrentHashMap<>();
    /** Tests: a stock read as given instead of from the stores. */
    private static final Map<UUID, Map<Ore, Integer>> STOCK_FOR_TESTS = new ConcurrentHashMap<>();

    /** Each ore's stock against the town's need. */
    public static Map<Ore, Want> wants(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        Long at = WANTS_AT.get(village);
        Map<Ore, Want> w = WANTS.get(village);
        if (w != null && at != null && now - at < 1200 && now >= at) return w;
        w = new EnumMap<>(Ore.class);
        int folk = Math.max(1, Math.min(Villages.headcount(village), Villages.AGE_FOLK));
        Villages.Age age = Villages.ageOf(village);
        boolean iron = age.ordinal() >= Villages.Age.IRON.ordinal(), diamond = age.ordinal() >= Villages.Age.DIAMOND.ordinal();
        java.util.Set<Ore> ages = java.util.EnumSet.noneOf(Ore.class);   // [econ32b] the ores the age itself asks for
        for (Ore o : Ore.values()) {
            int stock = stock(level, village, o);
            int need = switch (o) {
                // Fuel for the smelters, the smiths and the torches down the mine: more once iron is smelted every day.
                case COAL -> VillageMath.coalWanted(folk) * (iron ? 2 : 1) + InputWants.wanted(village, Items.COAL);
                case IRON -> (age.ordinal() >= Villages.Age.STONE.ordinal() ? VillageMath.ironWanted(folk) : 0)
                    + InputWants.wanted(village, Items.IRON_INGOT) + InputWants.wanted(village, Items.RAW_IRON);
                // The lightning rods, the smiths' copperware and the spyglasses.
                case COPPER -> (age.ordinal() >= Villages.Age.STONE.ordinal() ? 16 + folk / 2 : 0) + InputWants.wanted(village, Items.COPPER_INGOT)
                    + InputWants.wanted(village, Items.RAW_COPPER);
                case GOLD -> (iron ? 8 + folk / 4 : 0) + InputWants.wanted(village, Items.GOLD_INGOT) + InputWants.wanted(village, Items.RAW_GOLD);
                case LAPIS -> (iron ? 8 + folk / 6 : 0) + InputWants.wanted(village, Items.LAPIS_LAZULI);
                case REDSTONE -> (iron ? 8 + folk / 3 : 0) + InputWants.wanted(village, Items.REDSTONE);
                case DIAMOND -> (diamond ? VillageMath.diamondsWanted(folk) : 0) + InputWants.wanted(village, Items.DIAMOND);
                case EMERALD -> InputWants.wanted(village, Items.EMERALD);
            };
            // The age itself asking for it (Villages.needs): short whatever the sums say.
            Villages.Task t = o == Ore.COAL ? Villages.Task.COAL : o == Ore.IRON ? Villages.Task.IRON : o == Ore.DIAMOND ? Villages.Task.DIAMOND : null;
            if (t != null && !STOCK_FOR_TESTS.containsKey(village) && Fuel.ageWants(level, village, t)) {
                need = Math.max(need, stock + Math.max(8, need / 2));
                // [econ32b] What the age still asks for, whole: the Diamond Age asks twice the Iron Age's iron, and the works
                // reckoned the Iron Age's (86 wanted, 54 in) while the age waited on 140 more.
                need = Math.max(need, stock + AgeOres.missing(level, village, t));
                ages.add(o);
            }
            w.put(o, new Want(stock, need));
        }
        AgeOres.put(village, ages);                                  // [econ32b]
        WANTS.put(village, w);
        WANTS_AT.put(village, now);
        return w;
    }

    static int stock(ServerLevel level, UUID village, Ore o) {
        Map<Ore, Integer> t = STOCK_FOR_TESTS.get(village);
        if (t != null) return t.getOrDefault(o, 0);
        return Market.stock(level, village, o.one) + 9 * Market.stock(level, village, o.nine);
    }

    /** Tests: read these stocks instead of the stores (null: the stores again). */
    public static void stockForTests(UUID village, @Nullable Map<Ore, Integer> stock) {
        if (stock == null) STOCK_FOR_TESTS.remove(village); else STOCK_FOR_TESTS.put(village, new EnumMap<>(stock));
        WANTS_AT.remove(village);
        PLAN_AT.remove(village);
    }

    // ------------------------------------------------------------------ who goes where

    /** Each miner's level, as the town last shared them out, and when. */
    private static final Map<UUID, Map<UUID, Integer>> PLAN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PLAN_AT = new ConcurrentHashMap<>();
    /** The ore that sent each miner where it is (the most of the want it went for), for the books. */
    private static final Map<UUID, Map<UUID, Ore>> FOR = new ConcurrentHashMap<>();
    /** The ores the town last turned miners to for want of them, and the day it last said so for each. */
    private static final Map<UUID, Map<Ore, Long>> TOLD = new ConcurrentHashMap<>();

    /** The level this miner works, of these: shared out among the town's miners by what the town is short of, a mix. */
    static MineWorks.Lvl levelFor(ServerLevel level, MineWorks.Works w, List<MineWorks.Lvl> plan, VillageFolkEntity f) {
        UUID v = w.village;
        long now = level.getGameTime();
        Long at = PLAN_AT.get(v);
        Map<UUID, Integer> p = PLAN.get(v);
        if (p == null || at == null || now - at >= 1200 || now < at || !p.containsKey(f.getUUID())) {
            p = share(level, w, plan);
            PLAN.put(v, p);
            PLAN_AT.put(v, now);
        }
        Integer id = p.get(f.getUUID());
        for (MineWorks.Lvl l : plan) if (id != null && l.id() == id) return l;
        return byDepth(plan, f);
    }

    /** May a miner with a pick of this tier work this level at all: a wooden pick the top level only; the deep level an iron one. */
    static boolean mayWork(List<MineWorks.Lvl> plan, MineWorks.Lvl l, int tier) {
        if (tier < 2) return l == plan.get(0);
        return !(MineLevels.base(l.id()) == MineWorks.DEEP && tier < 3);   // [growth24] the deep level's further levels too
    }

    /** As the age would have it (the depth VillageFolkEntity.seekTheSeam set its plot to): the nearest level it may work. */
    static MineWorks.Lvl byDepth(List<MineWorks.Lvl> plan, VillageFolkEntity f) {
        int tier = f.pickTierCarried();
        int depth = f.workZone() != null ? f.workZone().depth() : 16;
        MineWorks.Lvl best = null;
        for (MineWorks.Lvl l : plan) {
            if (!mayWork(plan, l, tier)) continue;
            if (best == null || Math.abs(l.y() - depth) < Math.abs(best.y() - depth)) best = l;
        }
        return best != null ? best : plan.get(0);
    }

    /**
     * The town's miners shared out over the levels: each in turn (a fixed order) to the level where most of what the town
     * is still short of lies, of the ores its pick will take; each one sent takes some of the want off the ores of its
     * level, so the next goes for the next shortage. With nothing short, each goes where the age sends it.
     */
    private static Map<UUID, Integer> share(ServerLevel level, MineWorks.Works w, List<MineWorks.Lvl> plan) {
        UUID v = w.village;
        Map<Ore, Want> wants = wants(level, v);
        Map<Ore, Double> left = new EnumMap<>(Ore.class);
        for (Ore o : Ore.values()) left.put(o, wants.get(o).shortage());
        for (Ore o : AgeOres.of(v)) left.put(o, 1.0);                 // [econ32b] the age's own ore first, then the trades'
        boolean mountain = MineWorks.mountain(level, w), badlands = MineWorks.badlands(level, w);
        List<VillageFolkEntity> miners = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.MINE) miners.add(f);
        }
        miners.sort(Comparator.comparing(AssistantEntity::getUUID));
        Map<UUID, Integer> out = new HashMap<>();
        Map<UUID, Ore> why = new HashMap<>();
        Map<Ore, Integer> sent = new EnumMap<>(Ore.class);
        for (VillageFolkEntity f : miners) {
            int tier = f.pickTierCarried();
            MineWorks.Lvl best = null;
            double bestScore = 0.15;            // less than this is no want worth a miner: where the age sends it
            Ore bestOre = null;
            for (MineWorks.Lvl l : plan) {
                if (!mayWork(plan, l, tier) || MineLevels.spent(w, l.id())) continue;   // [growth24] never to a level worked out
                double score = 0, top = 0;
                Ore most = null;
                for (Ore o : Ore.values()) {
                    if (o.tier > Math.max(1, tier)) continue;
                    double s = left.get(o) * rich(o, l.y(), mountain, badlands);
                    score += s;
                    if (s > top) { top = s; most = o; }
                }
                if (score > bestScore) { bestScore = score; best = l; bestOre = most; }
            }
            if (best == null) best = byDepth(plan, f);
            out.put(f.getUUID(), best.id());
            if (bestOre != null) {
                why.put(f.getUUID(), bestOre);
                sent.merge(bestOre, 1, Integer::sum);
                for (Ore o : Ore.values()) left.put(o, left.get(o) * (1.0 - 0.85 * rich(o, best.y(), mountain, badlands)));
            }
        }
        FOR.put(v, why);
        chronicle(level, v, wants, sent);
        return out;
    }

    /** The town's news: miners turned to an ore for want of it, once in three days an ore. */
    private static void chronicle(ServerLevel level, UUID v, Map<Ore, Want> wants, Map<Ore, Integer> sent) {
        long day = level.getDayTime() / 24000L;
        Map<Ore, Long> told = TOLD.computeIfAbsent(v, k -> new EnumMap<>(Ore.class));
        for (Map.Entry<Ore, Integer> e : sent.entrySet()) {
            Want want = wants.get(e.getKey());
            if (!want.short_() || want.shortage() < 0.25) continue;
            Long last = told.get(e.getKey());
            if (last != null && day - last < 3 && day >= last) continue;
            told.put(e.getKey(), day);
            int n = e.getValue();
            Villages.tell(v, day, "the town turned " + (n == 1 ? "a miner" : n + " miners") + " to the " + e.getKey().word
                + ": the stores ran short (" + want.stock() + " of the " + want.need() + " it wants)");
        }
    }

    /** The ore that sent this miner to its level, or null (where the age sends it). */
    @Nullable
    public static Ore sentFor(UUID village, UUID miner) {
        Map<UUID, Ore> m = FOR.get(village);
        return m == null ? null : m.get(miner);
    }

    /** Tests and the books: share the miners out afresh on the next look. */
    public static void reshare(UUID village) {
        PLAN_AT.remove(village);
        WANTS_AT.remove(village);
    }

    public static void forgetForTests(UUID village) {
        PLAN.remove(village);
        PLAN_AT.remove(village);
        WANTS.remove(village);
        WANTS_AT.remove(village);
        FOR.remove(village);
        TOLD.remove(village);
        STOCK_FOR_TESTS.remove(village);
        AgeOres.forgetForTests(village);                             // [econ32b]
    }

    /** Each ore's stock against its need, the level it lies richest in of those planned, and who works there. */
    static List<String> lines(ServerLevel level, MineWorks.Works w, List<MineWorks.Lvl> plan) {
        List<String> out = new ArrayList<>();
        Map<Ore, Want> wants = wants(level, w.village);
        boolean mountain = MineWorks.mountain(level, w), badlands = MineWorks.badlands(level, w);
        Map<UUID, Integer> p = PLAN.getOrDefault(w.village, Map.of());
        for (Ore o : Ore.values()) {
            Want want = wants.get(o);
            if (want.need() == 0 && want.stock() == 0) continue;
            if (o == Ore.EMERALD && !mountain && want.need() == 0) continue;
            MineWorks.Lvl best = null;
            double r = 0.05;
            for (MineWorks.Lvl l : plan) {
                if (MineLevels.spent(w, l.id())) continue;                      // [growth24]
                double x = rich(o, l.y(), mountain, badlands);
                if (x > r) { r = x; best = l; }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(cap(o.word)).append(": ").append(want.stock()).append(" in the stores");
            if (want.need() > 0) sb.append(" of ").append(want.need()).append(" wanted").append(want.short_() ? " (short)" : "");
            if (best == null) sb.append("; none of the levels lies where it is found");
            else {
                List<String> who = new ArrayList<>();
                for (AssistantEntity a : Villages.folkOf(w.village)) {
                    Integer id = p.get(a.getUUID());
                    if (id != null && id == best.id()) who.add(a.displayNameCap() + (sentFor(w.village, a.getUUID()) == o ? " (for it)" : ""));
                }
                sb.append("; richest in ").append(best.name()).append(" (Y").append(best.y()).append(")")
                    .append(who.isEmpty() ? ", nobody on it" : ": " + String.join(", ", who));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
