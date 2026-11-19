package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [links12] Rival towns: what two neighbours have to gain from each other and what they stand to lose to each other,
 * weighed once a day (Diplomacy.daily), and what comes of it.
 *
 * <p><b>Interests, not chance.</b> Each pair of neighbours keeps a rivalry, 0 to 100, pushed up by real grievances and
 * pulled down by real gains:
 * <ul>
 * <li><b>The ground between them.</b> Lands that crowd each other with no border agreed (Bonds), an open dispute over
 *     the land (BorderDisputes) or claims that overlap (Borders).</li>
 * <li><b>Lost trade.</b> Both selling the same goods to the same third town (each takes custom from the other), a
 *     partner that dropped one town's deal while it deals with the other, or the market's own word that one undercut the
 *     other ({@link #lostTrade}).</li>
 * <li><b>Settlers poached.</b> A skilled hand, or anybody, gone over to the other town for its wages ({@link #poached}).</li>
 * <li><b>Renown.</b> Two towns near each other in renown (Villages.renown) vie for the same name in the land.</li>
 * <li><b>Trade gained</b> pulls the other way: what the deals between them earned both sides (TradeDeals' gains, and the
 *     market's word of goods carried between them, {@link #traded}).</li>
 * </ul>
 * A rivalry eases a little every day it is not fed.
 *
 * <p><b>What comes of it.</b> A rivalry of forty and more makes them <em>rivals</em>: their relation cools a point or
 * two a day, each lays a <em>tariff</em> on the other's goods (valued a tenth less at the bargaining, TradeTalks) and a
 * <em>toll</em> on the other's loads along its half of the road (Routes). At sixty, with relations gone sour (uneasy or
 * worse), the town that has lost the more <em>embargoes</em> the other: no talks, no caravans, no deals between them
 * (the deal they had is ended) until the rivalry falls back under thirty-five or relations mend. Good trade warms
 * relations as surely: a steady trade signs a trade pact between friends, and a rich one between close friends with a
 * pact makes them allies. War stays rare: it still takes a feud, a grievance and a captain who likes his chances
 * (WarAndPeace); a rivalry only makes the feud likelier.
 *
 * <p>Everything is kept in the first town's notes (Ledger), so it outlasts a restart.
 */
public final class Rivalry {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Rivalry() {}

    /** Rivals from here; an embargo from here (relations uneasy or worse); an embargo lifted under this. */
    public static final int RIVAL = 40, EMBARGO = 60, LIFT = 35, PARTED = 20;
    static final String NOTE = "rv/";

    /** How two towns stand, for the Region page and the talk. */
    public enum Stance {
        ALLY("allies", 0xFF23803A), FRIENDLY("friendly", 0xFF2E6FBF), NEUTRAL("neutral", 0xFF5A5A5A),
        RIVAL("rivals", 0xFFB7791F), HOSTILE("hostile", 0xFFB83227);

        public final String words;
        public final int colour;
        Stance(String words, int colour) { this.words = words; this.colour = colour; }
    }

    /** A pair's rivalry, as kept. */
    static final class State {
        int score;
        double trade, lost, poach, seen, tradeT, lostT, poachT, lostFirst, lostSecond;
        int embargo;             // 0 none; 1 laid by the first town, 2 by the second
        long day = -1, since;
        String why = "";

        String encode() {
            return String.join(",", Integer.toString(score), f(trade), f(lost), f(poach), f(seen), f(tradeT), f(lostT), f(poachT),
                f(lostFirst), f(lostSecond), Integer.toString(embargo), Long.toString(day), Long.toString(since), why.replace(',', ';'));
        }

        static State decode(@Nullable String s) {
            State st = new State();
            if (s == null || s.isEmpty()) return st;
            String[] p = s.split(",", 14);
            try {
                st.score = Integer.parseInt(p[0]);
                st.trade = Double.parseDouble(p[1]);
                st.lost = Double.parseDouble(p[2]);
                st.poach = Double.parseDouble(p[3]);
                st.seen = Double.parseDouble(p[4]);
                st.tradeT = Double.parseDouble(p[5]);
                st.lostT = Double.parseDouble(p[6]);
                st.poachT = Double.parseDouble(p[7]);
                st.lostFirst = Double.parseDouble(p[8]);
                st.lostSecond = Double.parseDouble(p[9]);
                st.embargo = Integer.parseInt(p[10]);
                st.day = Long.parseLong(p[11]);
                st.since = Long.parseLong(p[12]);
                st.why = p.length > 13 ? p[13] : "";
            } catch (RuntimeException ignored) { }
            return st;
        }

        private static String f(double d) { return String.format(Locale.ROOT, "%.2f", d); }
    }

    static UUID first(UUID x, UUID y) { return x.compareTo(y) < 0 ? x : y; }

    static State state(UUID x, UUID y) {
        UUID a = first(x, y), b = a.equals(x) ? y : x;
        return State.decode(Ledger.note(a, NOTE + b));
    }

    static void save(UUID x, UUID y, State s) {
        UUID a = first(x, y), b = a.equals(x) ? y : x;
        Ledger.note(a, NOTE + b, s.encode());
    }

    // ------------------------------------------------------------------ what the rest of the game tells it

    /** [econ, TradeDeals] Goods carried and paid for between two towns, worth so many coins to them both together. */
    public static void traded(@Nullable UUID x, @Nullable UUID y, double worth) {
        if (x == null || y == null || x.equals(y) || worth <= 0) return;
        State s = state(x, y);
        s.tradeT += worth;
        save(x, y, s);
    }

    /** [econ, the market] One town lost custom to the other (undercut, or a partner gone over), worth so many coins. */
    public static void lostTrade(@Nullable UUID loser, @Nullable UUID winner, double worth) {
        if (loser == null || winner == null || loser.equals(winner) || worth <= 0) return;
        State s = state(loser, winner);
        s.lostT += worth;
        if (first(loser, winner).equals(loser)) s.lostFirst += worth;
        else s.lostSecond += worth;
        save(loser, winner, s);
    }

    /** [JobSeekers.hired] A hand taken on by another town: a skilled one (or one to a rival) is a hand poached. */
    public static void poached(@Nullable UUID from, @Nullable UUID to, int level) {
        if (from == null || to == null || from.equals(to)) return;
        State s = state(from, to);
        double w = level >= 10 ? 1.0 : s.score >= RIVAL ? 0.6 : 0.25;
        s.poachT += w;
        if (first(from, to).equals(from)) s.lostFirst += w * 4;
        else s.lostSecond += w * 4;
        save(from, to, s);
    }

    // ------------------------------------------------------------------ what it means

    public static int score(UUID x, UUID y) { return state(x, y).score; }

    public static boolean rivals(UUID x, UUID y) { return state(x, y).score >= RIVAL && !Envoys.allied(x, y); }

    /** Trade between these two shut by an embargo. */
    public static boolean embargoed(@Nullable UUID x, @Nullable UUID y) {
        return x != null && y != null && !x.equals(y) && state(x, y).embargo != 0;
    }

    /** Who laid the embargo between these two, or null. */
    @Nullable
    public static UUID embargoBy(UUID x, UUID y) {
        State s = state(x, y);
        if (s.embargo == 0) return null;
        UUID a = first(x, y);
        return s.embargo == 1 ? a : a.equals(x) ? y : x;
    }

    /** [Railways.wantsLink] A strong trade between two: enough to lay a railway for, pact or no pact. */
    public static boolean strongTrade(UUID x, UUID y) {
        return state(x, y).trade >= 24 && !embargoed(x, y);
    }

    /** The weekly run of trade between them (coins' worth), as the books keep it. */
    public static double tradeRun(UUID x, UUID y) { return state(x, y).trade; }

    /** [TradeTalks] How a buyer values a rival's goods at the bargaining: a tenth less under its tariff. */
    public static double worth(@Nullable UUID buyer, @Nullable UUID seller) {
        if (buyer == null || seller == null || buyer.equals(seller)) return 1.0;
        return rivals(buyer, seller) ? 0.9 : 1.0;
    }

    /** [Routes] The toll a load pays a rival for the use of its half of the road: a coin every two hundred blocks. */
    public static int toll(UUID x, UUID y, int length) {
        return rivals(x, y) ? Math.max(1, length / 200) : 0;
    }

    public static Stance stance(UUID x, UUID y) {
        int r = Ledger.relation(x, y);
        if (Wars.atWar(x, y) || r <= Diplomacy.FEUD) return Stance.HOSTILE;
        if (Envoys.allied(x, y) || r >= Diplomacy.ALLIANCE) return Stance.ALLY;
        if (state(x, y).score >= RIVAL) return Stance.RIVAL;
        if (r >= Diplomacy.FRIENDLY) return Stance.FRIENDLY;
        return Stance.NEUTRAL;
    }

    /** What the rivalry is about, in words ("" if nothing). */
    public static String why(UUID x, UUID y) { return state(x, y).why; }

    // ------------------------------------------------------------------ the day

    /**
     * [Diplomacy.daily] Once a day for a pair of neighbours: the rivalry weighed, news of rivals made or parted, an embargo
     * laid or lifted, a pact or an alliance out of good trade. Returns what it does to their relation today.
     */
    public static int daily(ServerLevel level, Villages.Village va, Villages.Village vb, long day, int r) {
        UUID x = va.id(), y = vb.id();
        State s = state(x, y);
        if (s.day == day) return 0;
        s.day = day;
        UUID a = first(x, y), b = a.equals(x) ? y : x;
        // Trade gained: the deal's gains (both sides, by their own prices) since the last look, and the market's word.
        TradeDeals.Deal d = TradeDeals.deal(x, y);
        double total = d == null ? 0 : Math.max(0, d.sGot) + Math.max(0, d.bGot);
        double gained = total >= s.seen ? total - s.seen : total;
        s.seen = total;
        s.trade = s.trade * 6.0 / 7.0 + gained + s.tradeT;
        s.tradeT = 0;
        // Lost trade: custom taken at a third town's market, and what the market said.
        int vying = vying(x, y);
        s.lost = s.lost * 6.0 / 7.0 + s.lostT + vying * 3.0;
        s.lostT = 0;
        s.poach = s.poach * 6.0 / 7.0 + s.poachT;
        s.poachT = 0;
        boolean crowded = Diplomacy.apart(va, vb) < Diplomacy.CROWDED || Borders.contested(x, y);
        boolean agreed = Bonds.border(x, y);
        boolean disputed = BorderDisputes.open(x, y) != null || Borders.disputed(x, y);
        boolean renown = renownClose(x, y);
        int push = 0;
        List<String> why = new ArrayList<>();
        if (crowded && !agreed) { push += 3; why.add("the ground between them"); }
        if (disputed) { push += 2; if (!why.contains("the ground between them")) why.add("the ground between them"); }
        int lostPush = (int) Math.min(6, s.lost / 4);
        if (lostPush > 0) { push += lostPush; why.add("trade lost to each other"); }
        int poachPush = (int) Math.min(4, s.poach * 2);
        if (poachPush > 0) { push += poachPush; why.add("folk poached"); }
        if (renown) { push += 1; why.add("renown"); }
        int pull = (int) Math.min(6, s.trade / 8) + (Envoys.allied(x, y) ? 2 : 0) + (agreed ? 1 : 0);
        int before = s.score;
        s.score = Math.max(0, Math.min(100, s.score + push - pull - 1));
        if (!why.isEmpty()) s.why = String.join(" and ", why);
        else if (s.score < PARTED) s.why = "";
        String an = Villages.name(x), bn = Villages.name(y);
        if (before < RIVAL && s.score >= RIVAL && !Envoys.allied(x, y)) {
            Region.event(x, y, day, an + " and " + bn + " are rivals now, over " + s.why + "; each lays a tariff on the other's goods");
        } else if (before >= RIVAL && s.score < PARTED) {
            Region.event(x, y, day, "the rivalry between " + an + " and " + bn + " has cooled; the tariffs are lifted");
        }
        // The relation: good trade warms it, a rivalry cools it.
        int lean = 0;
        if (s.trade >= 20) lean += 2;
        else if (s.trade >= 6) lean += 1;
        if (s.score >= EMBARGO) lean -= 2;
        else if (s.score >= RIVAL) lean -= 1;
        // An embargo, laid by the town that has lost the more; lifted once the rivalry eases or relations mend.
        if (s.embargo == 0 && s.score >= EMBARGO && r <= Diplomacy.UNEASY && !Envoys.allied(x, y)) {
            s.embargo = s.lostFirst >= s.lostSecond ? 1 : 2;
            s.since = day;
            UUID by = s.embargo == 1 ? a : b, on = by.equals(a) ? b : a;
            TradeDeals.endFor(x, y);
            Region.event(x, y, day, Villages.name(by) + " laid an embargo on " + Villages.name(on) + " over " + s.why
                + ": no trade between them while it lasts");
            LOG.info("[MCA-RIVALRY] {} embargoes {} (rivalry {}, relation {})", Villages.name(by), Villages.name(on), s.score, r);
        } else if (s.embargo != 0 && (s.score < LIFT || r > 0 || Envoys.allied(x, y))) {
            UUID by = s.embargo == 1 ? a : b, on = by.equals(a) ? b : a;
            s.embargo = 0;
            s.since = day;
            Region.event(x, y, day, Villages.name(by) + " lifted its embargo on " + Villages.name(on));
        }
        // Good trade between friends: a pact, and between close friends with a pact an alliance.
        if (s.embargo == 0 && s.score < RIVAL && s.trade >= 10 && r >= Diplomacy.FRIENDLY && !Envoys.pact(x, y)) {
            Envoys.sign(x, y, day);
            Region.event(x, y, day, an + " and " + bn + " signed a trade pact: the trade between them has done both good");
        } else if (s.embargo == 0 && s.score < PARTED && s.trade >= 20 && r >= Diplomacy.ALLIANCE - 5 && Envoys.pact(x, y)
                && !Envoys.allied(x, y) && !Wars.atWar(x, y)) {
            Envoys.swear(x, y, day);
            Region.event(x, y, day, an + " and " + bn + " swore an alliance, their trade the making of both");
        }
        save(x, y, s);
        return lean;
    }

    /** How many third towns both these two sell the same goods to: custom each takes from the other. */
    static int vying(UUID x, UUID y) {
        int n = 0;
        for (TradeDeals.Deal dx : TradeDeals.deals(x)) {
            UUID c = dx.other(x);
            if (c.equals(y)) continue;
            TradeDeals.Deal dy = TradeDeals.deal(y, c);
            if (dy != null && dx.sends(x) != null && dx.sends(x) == dy.sends(y)) n++;
        }
        return n;
    }

    /** Two towns of like renown (within a quarter of each other, and of some name): rivals for the same name. */
    static boolean renownClose(UUID x, UUID y) {
        int rx = Villages.renown(x), ry = Villages.renown(y);
        int hi = Math.max(rx, ry);
        return hi >= 20 && Math.abs(rx - ry) * 4 <= hi;
    }

    // ------------------------------------------------------------------ for the tests

    public static void setForTests(UUID x, UUID y, int score, double trade, int embargo) {
        State s = state(x, y);
        s.score = score;
        s.trade = trade;
        s.embargo = embargo;
        save(x, y, s);
    }

    public static int dailyForTests(ServerLevel level, UUID x, UUID y, long day) {
        Villages.Village vx = Villages.get(x), vy = Villages.get(y);
        return vx == null || vy == null ? 0 : daily(level, vx, vy, day, Ledger.relation(x, y));
    }
}
