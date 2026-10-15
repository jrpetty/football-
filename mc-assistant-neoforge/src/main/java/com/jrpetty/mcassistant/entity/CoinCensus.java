package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [business-books] The town's coin, counted to the penny, wherever it is: the treasury (and its odd pennies), the folk's
 * purses, what the households have put by for their houses, the bank's vault, every business's till, the players' stall
 * tills and the poor box. Coin only moves between these; it comes into a town or leaves it only by the flows named here
 * (the mint, the world paying for the town's work, the traders, the players, the upkeep that goes out of town for good),
 * each booked where it happens ({@link #flow}). Each morning the count is set against yesterday's and the day's flows:
 * what is left over is drift, coin that came or went by a way nobody booked, and the town's books show it.
 *
 * <p>One census for the whole economy: a later round adds its own holders (escrows, store credit) and flows here.
 */
public final class CoinCensus {

    private CoinCensus() {}

    /** The ways coin comes into a town (+) or goes out of it (-). */
    public enum Flow {
        MINT("gold minted into coin", 1),
        OUTPUT("the world's pay for the town's work (Market.takings)", 1),
        EXPORT("traders buying the town's goods", 1),
        PLAYER_IN("players paying in", 1),
        VISITOR_IN("visitors' and ships' coin", 1),
        UPKEEP("the town's upkeep, out of town", -1),
        IMPORT("goods bought from traders", -1),
        PLAYER_OUT("paid out to players", -1),
        VISITOR_OUT("visitors' and ships' coin taken away", -1);

        public final String words;
        public final int sign;

        Flow(String words, int sign) {
            this.words = words;
            this.sign = sign;
        }
    }

    /** The flows booked since the last morning's count, by the town (coins). */
    private static final Map<UUID, EnumMap<Flow, Long>> FLOWS = new ConcurrentHashMap<>();
    /** The last count's flows and drift, for the books. */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        FLOWS.clear();
        LAST.clear();
    }

    /** So many coins came into the town (or went out of it) this way. */
    public static void flow(@Nullable UUID village, Flow f, long coins) {
        if (village == null || coins <= 0) return;
        FLOWS.computeIfAbsent(village, k -> new EnumMap<>(Flow.class)).merge(f, coins, Long::sum);
    }

    /** The flows since the last count: in minus out, in pennies. */
    public static long netFlowPence(UUID village) {
        long n = 0;
        EnumMap<Flow, Long> m = FLOWS.get(village);
        if (m != null) for (Map.Entry<Flow, Long> e : m.entrySet()) n += e.getKey().sign * e.getValue() * 100;
        return n;
    }

    /** Every holder of the town's coin, in pennies, in the order the books show them. */
    public static Map<String, Long> holders(UUID village) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("treasury", Ledger.coins(village) * 100L + Businesses.townPence(village));
        long purses = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) purses += f.purse() * 100L;
        out.put("purses", purses);
        out.put("put by for houses", Homes.savedTotal(village) * 100L);
        out.put("the bank's vault", Bank.cash(village) * 100L);
        long tills = 0, due = 0;
        for (Business b : Businesses.all(village)) {
            if (!b.mirror()) tills += b.till;
            for (long d : b.ownersDue.values()) due += d;
        }
        out.put("business tills", tills);
        out.put("players' drawings not yet taken", due);
        long stalls = 0;
        for (PlayerStalls.Stall st : PlayerStalls.all(village)) stalls += st.till * 100L;
        out.put("players' stall tills", stalls);
        out.put("the poor box", PoorBox.coins(village) * 100L);
        return out;
    }

    /** All the town's coin, in pennies. */
    public static long count(UUID village) {
        long n = 0;
        for (long v : holders(village).values()) n += v;
        return n;
    }

    /**
     * The morning count (Businesses.morning): today's coin against the last count and what the booked flows explain; the
     * flows cleared for the new day. Kept in the town's notes, so a restart keeps the last count.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        long now = count(id);
        long net = netFlowPence(id);
        String last = Ledger.note(id, "census.last");
        long drift = 0;
        boolean had = false;
        if (last != null && last.contains("|")) {
            try {
                long was = Long.parseLong(last.substring(last.indexOf('|') + 1));
                drift = now - was - net;
                had = true;
            } catch (NumberFormatException ignored) { }
        }
        Ledger.note(id, "census.last", day + "|" + now);
        StringBuilder sb = new StringBuilder("Day " + (day + 1) + ": " + Businesses.pence(now) + " in all");
        EnumMap<Flow, Long> m = FLOWS.remove(id);
        if (m != null && !m.isEmpty()) {
            sb.append("; since the last count");
            for (Map.Entry<Flow, Long> e : m.entrySet()) sb.append(e.getKey().sign > 0 ? ", +" : ", -").append(e.getValue()).append(" ").append(e.getKey().words);
        }
        if (had) sb.append(drift == 0 ? "; every coin accounted for" : "; drift " + Businesses.pence(drift) + " (came or went by a way not yet booked)");
        LAST.put(id, sb.toString());
        Ledger.note(id, "census.line", sb.toString());
    }

    /** The last morning's count in words. */
    public static String line(UUID village) {
        String s = LAST.get(village);
        if (s == null) s = Ledger.note(village, "census.line");
        return s == null ? "Not counted yet: the first count is at the next morning." : s;
    }

    /** The census in words, holder by holder (the admin's audit, /village business audit). */
    public static String page(UUID village) {
        StringBuilder sb = new StringBuilder("The coin of " + Villages.name(village) + ":\n");
        for (Map.Entry<String, Long> e : holders(village).entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(Businesses.pence(e.getValue())).append("\n");
        sb.append("  in all: ").append(Businesses.pence(count(village))).append("\n");
        long net = netFlowPence(village);
        sb.append("Flows booked since the morning: ").append(Businesses.pence(net)).append("\n");
        sb.append("Last morning: ").append(line(village));
        return sb.toString();
    }
}
