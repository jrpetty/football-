package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [market] Production follows prices: a trade whose goods are dear in the town wants a hand or two more, and one whose
 * goods are a glut a hand fewer, so the leader's shares (Villages.target) lean toward what the town is short of and away
 * from what it cannot sell. The lean is the square root of the trade's goods' price against their worth (PriceIndex), a
 * little dearer or cheaper being no reason to move anybody: from {@link #LEAST} to {@link #MOST}. The Prices page says
 * which trades lean and why.
 */
public final class MarketLean {

    private MarketLean() {}

    /** The most a trade's share leans down for cheap goods, and up for dear ones. */
    static final double LEAST = 0.85, MOST = 1.25;
    /** A lean nearer the share than this is no lean (the page does not mention it). */
    static final double SMALL = 0.04;

    /** What each trade brings the stores, by the board's names for it. */
    private static final Map<StationTask, List<String>> GOODS = Map.of(
        StationTask.FARM, List.of("Wheat", "Bread", "Carrots", "Potatoes", "Beetroot", "Baked potato"),
        StationTask.FISH, List.of("Raw fish", "Cod", "Salmon"),
        StationTask.RANCH, List.of("Wool", "Leather", "Raw beef", "Beef", "Mutton", "Pork", "Chicken", "Eggs"),
        StationTask.HUNT, List.of("Leather", "Feathers", "Raw beef"),
        StationTask.WOOD, List.of("Logs", "Planks"),
        StationTask.MINE, List.of("Cobblestone", "Coal", "Raw iron"),
        StationTask.SMELT, List.of("Iron", "Charcoal", "Glass", "Copper"),
        StationTask.BEEKEEP, List.of("Honey", "Honeycomb"));

    /** How far this trade's share leans with the prices of what it makes: 1 for none, or no prices reckoned yet. */
    public static double lean(@Nullable UUID village, StationTask trade) {
        if (village == null) return 1.0;
        List<String> goods = GOODS.get(trade);
        if (goods == null) return 1.0;
        double f = factor(village, goods);
        if (f <= 0) return 1.0;
        return Math.max(LEAST, Math.min(MOST, Math.sqrt(f)));
    }

    /** The mean factor of these goods' prices in the town today, or 0 if none is reckoned. */
    private static double factor(UUID village, List<String> goods) {
        PriceIndex.Town t = PriceIndex.town(village);
        if (t.day < 0) return 0;
        double sum = 0;
        int n = 0;
        for (String g : goods) {
            PriceIndex.Line l = t.lines.get("g:" + g);
            if (l == null) continue;
            sum += l.factor;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** The page's line: which trades lean, which way and why ("farmers +18% (their goods dear)"), or empty. */
    public static String line(UUID village) {
        List<String> up = new ArrayList<>(), down = new ArrayList<>();
        for (StationTask t : GOODS.keySet()) {
            double l = lean(village, t);
            if (Math.abs(l - 1.0) < SMALL) continue;
            String w = t.label + " " + (l > 1 ? "+" : "") + Math.round((l - 1) * 100) + "%";
            (l > 1 ? up : down).add(w);
        }
        up.sort(null);
        down.sort(null);
        if (up.isEmpty() && down.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("The trades lean with the prices:");
        if (!up.isEmpty()) sb.append(" more hands for what is dear (").append(String.join(", ", up)).append(")");
        if (!down.isEmpty()) sb.append(up.isEmpty() ? "" : ";").append(" fewer for what is cheap (").append(String.join(", ", down)).append(")");
        return sb.append('.').toString().toLowerCase(Locale.ROOT).replace("the trades", "The trades");
    }
}
