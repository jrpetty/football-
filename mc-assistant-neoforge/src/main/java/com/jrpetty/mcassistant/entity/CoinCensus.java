package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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
        FOUNDERS("the founders' own coin, brought with them", 1),          // [econ-1a]
        OUTPUT("the world's pay for the town's work (Market.takings)", 1),
        EXPORT("traders buying the town's goods", 1),
        PLAYER_IN("players paying in", 1),
        VISITOR_IN("visitors' and ships' coin", 1),
        UPKEEP("the town's upkeep, out of town", -1),
        IMPORT("goods bought from traders", -1),
        PLAYER_OUT("paid out to players", -1),
        VISITOR_OUT("visitors' and ships' coin taken away", -1),
        KEPT("coin put away as a keepsake (a coin in a folk's pack, not its purse)", -1);   // [econ-1a]

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
    /** [econ-1a] The last count's drift, pennies (0 before a second count). */
    private static final Map<UUID, Long> DRIFT = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ [econ-1a] the probe (tests and the admin's hunt for drift)

    @Nullable private static volatile UUID PROBE;
    private static long probeCount, probeNet;
    private static boolean probed;
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Watch one town's coin from moment to moment (null: stop): any change no booked flow explains is logged with where. */
    public static void probeForTests(@Nullable UUID village) {
        probeForTests(village, false);
    }

    /** As probeForTests, and round every folk's own tick too ({@code folk}: dearer, but it says whose tick it was). */
    public static void probeForTests(@Nullable UUID village, boolean folk) {
        PROBE = village;
        PROBE_FOLK = folk;
        probed = false;
    }

    private static volatile boolean PROBE_FOLK;

    public static boolean probing() {
        return PROBE != null;
    }

    public static boolean probingFolk() {
        return PROBE != null && PROBE_FOLK;
    }

    /** A look at the probed town's coin here: what changed since the last look that no flow explains, logged with where. */
    public static void probe(String where) {
        UUID id = PROBE;
        if (id == null) return;
        long c = count(id), n = netFlowPence(id);
        if (probed && c - probeCount != n - probeNet) {
            LOG.info("[econ-1a] coin drift {} in {} at {}: count {} -> {}, flows {} -> {}", Businesses.pence((c - probeCount) - (n - probeNet)),
                Villages.name(id), where, probeCount, c, probeNet, n);
        }
        probeCount = c;
        probeNet = n;
        probed = true;
    }

    /**
     * A quick look round a folk's own tick (the probe's cheap half): its purse, the treasury, the businesses' tills, the
     * bank's vault, the market's change and the flows booked: {sum, flows}.
     */
    public static long[] quick(UUID village, VillageFolkEntity f) {
        long n = f.purse() * 100L + Businesses.treasuryPence(village) + Bank.cash(village) * 100L + Accounts.changeHeld(village)
            + PoorBox.coins(village) * 100L + Homes.savedTotal(village) * 100L + TownWonders.heldFor(village) * 100L + PublicFund.held(village) * 100L
            + HousingMarket.escrowForTests(village) * 100L;
        for (Business b : Businesses.all(village)) if (!b.mirror()) n += b.till;
        return new long[]{ n, netFlowPence(village) };
    }

    private static final Map<UUID, String> SPENT = new ConcurrentHashMap<>();

    /** The probe: where a folk's purse was last spent from (a few frames up the stack). */
    public static void spent(VillageFolkEntity f, int coins) {
        String where = StackWalker.getInstance().walk(fr -> fr.skip(2).limit(6)
            .map(x -> x.getClassName().substring(x.getClassName().lastIndexOf('.') + 1) + "." + x.getMethodName() + ":" + x.getLineNumber())
            .collect(java.util.stream.Collectors.joining(" < ")));
        SPENT.put(f.getUUID(), coins + " from " + where);
    }

    private static final List<String> TOOK = new java.util.ArrayList<>();

    /** The probe: where the treasury was last drawn on (a few frames up the stack). */
    public static void treasuryTook(UUID village, int coins) {
        if (!village.equals(PROBE)) return;
        String where = StackWalker.getInstance().walk(fr -> fr.skip(2).limit(5)
            .map(x -> x.getClassName().substring(x.getClassName().lastIndexOf('.') + 1) + "." + x.getMethodName() + ":" + x.getLineNumber())
            .collect(java.util.stream.Collectors.joining(" < ")));
        TOOK.add(coins + " from " + where);
        while (TOOK.size() > 6) TOOK.remove(0);
    }

    /** The folk's tick over: what its purse, the treasury and the tills came to against before, unexplained, logged. */
    public static void quickAfter(UUID village, VillageFolkEntity f, long[] before, String doing) {
        long[] now = quick(village, f);
        long d = (now[0] - before[0]) - (now[1] - before[1]);
        if (d != 0) LOG.info("[econ-1a] coin moved {} in {}'s tick ({}) in {}, its purse now {}; last spent {}; the treasury drawn on {}",
            Businesses.pence(d), f.getName().getString(), doing, Villages.name(village), f.purse(), SPENT.remove(f.getUUID()), TOOK);
        else SPENT.remove(f.getUUID());
        TOOK.clear();
    }

    /** [econ-1a] The last morning's drift, pennies: coin that came or went by no booked way. */
    public static long lastDrift(UUID village) {
        return DRIFT.getOrDefault(village, 0L);
    }

    public static void resetForTests() {
        FLOWS.clear();
        LAST.clear();
        DRIFT.clear();
        AWAY.clear();                                                   // [econ-1e]
    }

    // ------------------------------------------------------------------ [econ-1e] folk out of the loaded world

    /**
     * [econ-1e] Each town's folk out of the loaded world, and their purses as they went: a cave team out on its trip, a
     * wagon's crew out of sight on the road, a hand at a far field whose ground was let go, a whole town while nobody is
     * near it. The count took the purses of the folk it could see, so a team of three walking out past the loaded ground
     * with three coins between them was three coins short in the morning's count, and three coins over the morning they
     * walked back in: drift that was nobody's. Their purses are counted as they left them until they are back in the world.
     * Kept in the town's notes, so a restart with folk out of the world counts them still.
     */
    private static final Map<UUID, Map<UUID, Integer>> AWAY = new ConcurrentHashMap<>();

    /** [econ-1e] A folk of this town went out of the loaded world (its chunk let go, another dimension) with so many coins. */
    public static void away(@Nullable UUID village, UUID folk, int coins) {
        if (village == null || folk == null) return;
        Map<UUID, Integer> m = awayOf(village);
        Integer was = m.put(folk, Math.max(0, coins));
        if (was == null || was != Math.max(0, coins)) saveAway(village, m);
    }

    /** [econ-1e] A folk back in the world (filed on its town's register again): counted by its purse, not as it left. */
    public static void back(UUID folk) {
        if (folk == null || AWAY.isEmpty()) return;
        for (Map.Entry<UUID, Map<UUID, Integer>> e : AWAY.entrySet()) {
            if (e.getValue().remove(folk) != null) saveAway(e.getKey(), e.getValue());
        }
    }

    /** [econ-1e] The purses of this town's folk out of the world, pennies (any of them in the world now counted there). */
    static long awayPence(UUID village, java.util.Set<UUID> here) {
        Map<UUID, Integer> m = awayOf(village);
        long n = 0;
        for (Map.Entry<UUID, Integer> e : m.entrySet()) if (!here.contains(e.getKey())) n += e.getValue() * 100L;
        return n;
    }

    private static Map<UUID, Integer> awayOf(UUID village) {
        return AWAY.computeIfAbsent(village, k -> {
            Map<UUID, Integer> m = new ConcurrentHashMap<>();
            String note = Ledger.note(k, "census.away");
            if (note != null && !note.isEmpty()) {
                for (String part : note.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    try {
                        m.put(UUID.fromString(part.substring(0, eq)), Integer.parseInt(part.substring(eq + 1)));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            return m;
        });
    }

    private static void saveAway(UUID village, Map<UUID, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Ledger.note(village, "census.away", sb.toString());
    }

    /** So many coins came into the town (or went out of it) this way. */
    public static void flow(@Nullable UUID village, Flow f, long coins) {
        if (village == null || coins <= 0) return;
        FLOWS.computeIfAbsent(village, k -> new EnumMap<>(Flow.class)).merge(f, coins, Long::sum);
    }

    // ------------------------------------------------------------------ [health] the players' coin

    /** [health] A player's coin into one of the town's holders (its treasury, a till, a folk's purse, the bank's vault). */
    public static void playerIn(@Nullable UUID village, long coins) {
        flow(village, Flow.PLAYER_IN, coins);
    }

    /** [health] Coin out of one of the town's holders into a player's pack. */
    public static void playerOut(@Nullable UUID village, long coins) {
        flow(village, Flow.PLAYER_OUT, coins);
    }

    /** [health] The town whose count holds this folk's purse: a visitor's, the town it is visiting; a folk's, its own. */
    @Nullable
    public static UUID purseTown(@Nullable VillageFolkEntity f) {
        if (f == null) return null;
        UUID visiting = Visitors.townOf(f);
        return visiting != null ? visiting : f.ownerId();
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
        java.util.Set<UUID> here = new java.util.HashSet<>();                       // [econ-1e]
        long carried = 0;                                                           // [health]
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) {
            purses += f.purse() * 100L;
            here.add(f.getUUID());
            if (f.trip() != null) carried += Math.max(0, f.trip().purse) * 100L;   // [health]
        }
        out.put("purses", purses);
        out.put("carried by the town's caravans and wagons", carried);  // [health] a trip's purse: road money, takings going home
        out.put("purses of folk out of the loaded world", awayPence(village, here));   // [econ-1e] a cave team away, a wagon's crew
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
        out.put("change held for folk at the market", Accounts.changeHeld(village));   // [econ-1a] their odd pennies
        long visitors = 0;                                                               // [econ-1a] visitors in town, their purses
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) for (VillageFolkEntity f : Visitors.inTown(server.overworld(), village, null)) visitors += f.purse() * 100L;
        out.put("visitors' purses", visitors);
        out.put("the wonder fund", TownWonders.heldFor(village) * 100L);
        out.put("the statue fund", PublicFund.held(village) * 100L);
        out.put("held for a house going up", HousingMarket.escrowForTests(village) * 100L);
        out.put("haul contracts' escrow", HaulContracts.escrowed(village) * 100L);
        // [health] The rent a player's tenants have paid, waiting for the player at the treasury (or the bank's round).
        long rentDue = 0;
        for (Map.Entry<String, String> e : Commerce.notesStarting(village, "rentdue/")) rentDue += Homes.parse(e.getValue()) * 100L;
        out.put("players' rent waiting to be collected", rentDue);
        out.put("bounties the watch holds", Outlaws.held(village) * 100L);   // [health] on outlaws' heads, and won, not yet claimed
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
        DRIFT.put(id, had ? drift : 0L);                                // [econ-1a]
        if (id.equals(PROBE)) { probeCount = now; probeNet = 0; probed = true; }
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
        // [econ-1a] What is owed, not coin: shown apart (the market's store credit to producers, what folk and businesses
        // owe it, and the town's IOUs to it).
        sb.append("Owed (not coin): the market owes producers ").append(Businesses.pence(MarketMaker.storeCredit(village)))
            .append(" in store credit; folk and businesses owe it ").append(Businesses.pence(Accounts.owedAll(village)))
            .append("; the town's IOUs to it ").append(Businesses.pence(Accounts.debts(village))).append("\n");
        long net = netFlowPence(village);
        sb.append("Flows booked since the morning: ").append(Businesses.pence(net)).append("\n");
        sb.append("Last morning: ").append(line(village));
        return sb.toString();
    }
}
