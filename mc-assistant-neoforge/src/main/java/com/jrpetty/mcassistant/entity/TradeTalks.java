package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-trade] The bargaining between a town's envoy and the other town's leader, before the other town's board.
 *
 * <p>The envoy brings its town's offer list (what it has to spare: TradeBook's surpluses) and its want list
 * (what it is short of). The host's leader holds them up against its own books: what of the envoy's goods
 * is it short of, and what of its own surplus does the envoy's town want? If neither has anything the other
 * wants there is nothing to talk about. Otherwise there is a lot on the table: so much of one town's surplus
 * each delivery (the seller's goods), paid for by so much of the other's surplus back (the buyer's goods),
 * and coin to make up the rest.
 *
 * <p><b>Each side reckons the deal by its own prices.</b> One of a thing is worth to a town what it costs
 * there today (PriceIndex), and more when the town is short of it (TradeBook.worth). Stone is cheap in the
 * mining town and dear in the farming town that has none; bread the other way round. That difference is
 * the whole of what there is to gain, and a deal is only struck where both sides gain by their own
 * reckoning: between the least the seller would take and the most the buyer would give — the room to
 * agree. Where there is no such room there is no deal, however the talk goes.
 *
 * <p><b>They bargain in rounds.</b> Each opens on its own side of the room (or past it), and each round
 * gives up part of the gap between them. How far a leader reaches at first and how much it gives each
 * round is its temper (Envoys.Temper) and how the two towns stand (Diplomacy): a shrewd leader opens hard
 * and gives little, a warm or open-handed one opens near the middle and gives a lot, a prickly one may
 * walk out; friends give more, rivals less, and two elders who get on meet sooner. When they meet (or are
 * near enough to split what is left) the deal is struck: what goes each way, how much each delivery, how
 * often, for how many weeks, the price, and what a missed delivery costs. Every round is written down.
 */
public final class TradeTalks {

    private TradeTalks() {}

    /** A deal's deliveries: every three days (four to a far town), for three weeks. */
    static final int EVERY = 3, WEEKS = 3;
    /** How many rounds before they split what is left between them, or give it up. */
    static final int ROUNDS = 10;
    /** At most three stacks of a thing on one caravan. */
    static final int STACKS = 3;

    // ------------------------------------------------------------------ the leaders

    /**
     * How a leader bargains: how far past the middle of the room it reaches at first (a share of the room;
     * below nothing it opens inside it), how much of the gap it gives up each round, and whether it will
     * walk out on talks that are going nowhere.
     */
    public record Manner(Envoys.Temper temper, double reach, double give, boolean walks) {}

    private static final Map<UUID, Envoys.Temper> TEMPERS = new ConcurrentHashMap<>();

    /** Tests: this town's leader bargains with this temper, whoever leads it. */
    public static void temperForTests(UUID village, @Nullable Envoys.Temper t) {
        if (t == null) TEMPERS.remove(village); else TEMPERS.put(village, t);
    }

    public static void resetForTests() {
        TEMPERS.clear();
    }

    static Envoys.Temper temper(UUID village) {
        Envoys.Temper t = TEMPERS.get(village);
        return t != null ? t : Envoys.temper(village);
    }

    /** A leader's manner at the table: its temper, the two towns' terms, and whether the two elders get on. */
    public static Manner manner(Envoys.Temper t, int relation, int chemistry) {
        double reach = switch (t) {
            case SHREWD -> 0.35;
            case PRICKLY -> 0.30;
            case WARY -> 0.25;
            case STEADY -> 0.15;
            case CURIOUS -> 0.10;
            case EASY -> 0.0;
            case FRIENDLY -> -0.05;
            case WARM -> -0.15;
            case GENEROUS -> -0.25;
        };
        double give = switch (t) {
            case SHREWD -> 0.12;
            case PRICKLY -> 0.15;
            case WARY -> 0.18;
            case STEADY -> 0.30;
            case CURIOUS -> 0.32;
            case EASY -> 0.40;
            case FRIENDLY -> 0.38;
            case WARM -> 0.45;
            case GENEROUS -> 0.50;
        };
        // Friends meet each other sooner, allies sooner still; neighbours on bad terms each hold out.
        if (relation >= Diplomacy.ALLIANCE) { reach -= 0.10; give += 0.10; }
        else if (relation >= Diplomacy.FRIENDLY) { reach -= 0.05; give += 0.05; }
        else if (relation <= Diplomacy.UNEASY) { reach += 0.10; give -= 0.05; }
        reach -= 0.05 * chemistry;
        give += 0.04 * chemistry;
        boolean walks = t == Envoys.Temper.PRICKLY && relation < Diplomacy.FRIENDLY;
        return new Manner(t, reach, Math.max(0.06, Math.min(0.6, give)), walks);
    }

    // ------------------------------------------------------------------ the table

    /** So many of the buyer's goods each delivery, and so much coin. */
    public record Terms(int qy, int coin) {}

    /**
     * What is on the table: so many of the seller's goods (x) each delivery, paid for with up to so many of the
     * buyer's (y, if the buyer has anything the seller wants) and coin. Each good as each side reckons one of it:
     * sx and sy the seller's worth of one x and one y, bx and by the buyer's.
     *
     * <p>The bargain is over the price {@code p}: what the buyer gives for the lot, in coin, its own goods
     * reckoned at the middle of the two towns' worths of them (what both can see is fair between them). It is
     * paid in the buyer's goods as far as they go ({@code yMax}), and in coin after that, up to what the
     * buyer's treasury can find each delivery ({@code coinCap}).
     */
    public record Table(UUID seller, UUID buyer, Item x, int qx, @Nullable Item y, int yMax,
                        double sx, double bx, double sy, double by, double coinCap) {

        /** What one of the buyer's goods is reckoned at between them. */
        double mid() { return y == null ? 1.0 : (sy + by) / 2.0; }

        /** The most the buyer's goods can come to. */
        double goodsCap() { return y == null ? 0 : yMax * mid(); }

        /** What a price comes to: the buyer's goods as far as they go, then coin. */
        public Terms terms(double p) {
            int qy = y == null || yMax <= 0 ? 0 : (int) Math.min(yMax, Math.floor(Math.max(0, p) / mid()));
            int coin = (int) Math.max(0, Math.round(p - qy * mid()));
            return new Terms(qy, coin);
        }

        /** What the seller gains by its own reckoning: what it gets, less what it gives. */
        public double gainSeller(Terms t) { return t.qy() * sy + t.coin() - qx * sx; }

        /** What the buyer gains by its own reckoning. */
        public double gainBuyer(Terms t) { return qx * bx - t.qy() * by - t.coin(); }

        /** The least price that leaves the seller {@code g} better off. */
        double sellerFloor(double g) {
            double need = g + qx * sx;
            if (y == null || yMax <= 0) return need;
            double p = need * mid() / sy;
            return p <= goodsCap() ? p : goodsCap() + (need - yMax * sy);
        }

        /** The most price that leaves the buyer {@code g} better off. */
        double buyerCeiling(double g) {
            double room = qx * bx - g;
            if (y == null || yMax <= 0) return room;
            double p = room * mid() / by;
            return p <= goodsCap() ? p : goodsCap() + (room - yMax * by);
        }

        /** The most the buyer can pay at all: its goods, and the coin it can find. */
        double canPay() { return goodsCap() + coinCap; }
    }

    /** One offer across the table: which round, who made it, at what price, and what that comes to. */
    public record Offer(int round, boolean byEnvoy, double p, Terms terms) {}

    public enum End {
        DEAL("struck a deal"), NOTHING("found nothing to trade"), NO_ROOM("could find no price good for both"),
        WALKED("walked out of the talks"), STUCK("could not agree");

        public final String words;
        End(String words) { this.words = words; }
    }

    /**
     * The whole of one negotiation: who sat at the table, what was on it, every offer, how it ended, and (for a
     * deal) the settled price and terms, what each side gains by its own prices each delivery, how far each
     * gave ground (a share of the room between them), and the deal's schedule and penalty.
     */
    public record Talk(UUID envoyTown, UUID hostTown, @Nullable Table table, List<Offer> offers, End end,
                       @Nullable Terms settled, double price, double gainSeller, double gainBuyer,
                       double sellerGave, double buyerGave, double lo, double hi,
                       Manner seller, Manner buyer, int every, int weeks, int penalty, String why, boolean renewal) {

        public boolean deal() { return end == End.DEAL && settled != null && table != null; }
        public boolean envoySells() { return table != null && table.seller().equals(envoyTown); }
        public double gainEnvoy() { return envoySells() ? gainSeller : gainBuyer; }
        public double gainHost() { return envoySells() ? gainBuyer : gainSeller; }
        /** How much ground the host's leader gave, as a share of the room to agree. */
        public double hostGave() { return envoySells() ? buyerGave : sellerGave; }
        public double envoyGave() { return envoySells() ? sellerGave : buyerGave; }
        public Manner hostManner() { return envoySells() ? buyer : seller; }
    }

    // ------------------------------------------------------------------ laying the table

    /** How many of a thing a town would put on each delivery, for what it can spare and its rate (TradeBook). */
    static int offerEach(TradeBook.Entry o, int every) {
        double steady = (o.made() - o.used()) * every;
        double n = Math.max(o.spare() / 3.0, steady);
        n = Math.min(n, o.spare());
        return (int) Math.min(n, STACKS * new ItemStack(o.item()).getMaxStackSize());
    }

    /** How many of a thing a town short of it wants each delivery: a third of its shortfall, or what its use outruns its making by. */
    static int wantEach(TradeBook.Entry w, int every) {
        double steady = (w.used() - w.made()) * every;
        return (int) Math.max(w.ware().lot, Math.max(w.want() / 3.0, steady));
    }

    static int lots(int n, int lot) {
        return lot <= 1 ? n : n / lot * lot;
    }

    /** The table for these two, the seller selling: its best surplus the buyer is short of, and the buyer's best back. */
    @Nullable
    static Table lay(ServerLevel level, TradeBook.Book s, TradeBook.Book b, int every) {
        Item x = null;
        int qx = 0;
        double sx = 0, bx = 0, bestX = 0;
        for (TradeBook.Entry e : s.surpluses()) {
            TradeBook.Entry want = b.get(e.ware());
            if (want.status() != TradeBook.Status.SHORT) continue;
            int n = lots(Math.min(offerEach(e, every), wantEach(want, every)), e.ware().lot);
            if (n < e.ware().lot) continue;
            double vs = TradeBook.worth(level, s.town(), e.item()), vb = TradeBook.worth(level, b.town(), e.item());
            double gain = (vb - vs) * n;
            if (gain > bestX) { bestX = gain; x = e.item(); qx = n; sx = vs; bx = vb; }
        }
        if (x == null) return null;
        TradeBook.Ware xw = TradeBook.Ware.of(new ItemStack(x));
        Item y = null;
        int yMax = 0;
        double sy = 0, by = 0, bestY = 0;
        for (TradeBook.Entry e : b.surpluses()) {
            if (e.ware() == xw) continue;
            TradeBook.Entry want = s.get(e.ware());
            if (want.status() != TradeBook.Status.SHORT) continue;
            int n = lots(Math.min(offerEach(e, every), wantEach(want, every)), e.ware().lot);
            if (n < e.ware().lot) continue;
            double vs = TradeBook.worth(level, s.town(), e.item()), vb = TradeBook.worth(level, b.town(), e.item());
            double gain = (vs - vb) * n;
            if (gain > bestY) { bestY = gain; y = e.item(); yMax = n; sy = vs; by = vb; }
        }
        // The coin the buyer can find each delivery: a third of what it has over two days' wages.
        double coinCap = Math.max(0, Ledger.coins(b.town()) - 2 * Market.wageBill(b.town())) / 3.0;
        Table t = new Table(s.town(), b.town(), x, qx, y, yMax, sx, bx, sy, by, coinCap);
        // A buyer that cannot pay for the whole lot (its own goods run out, and it has no coin to spare) is offered
        // a smaller one, a lot at a time, till what it can pay covers what the seller must have: a poor hamlet still
        // gets its forty loaves, where it could never find the coin for eighty.
        int lot = Math.max(1, xw == null ? 1 : xw.lot);
        while (t.qx() > lot && t.sellerFloor(margin(t.qx() * t.sx())) > t.canPay() - 0.25) {
            t = new Table(t.seller(), t.buyer(), x, t.qx() - lot, y, yMax, sx, bx, sy, by, coinCap);
        }
        return t;
    }

    // ------------------------------------------------------------------ the bargaining

    /** Sit the two down: the envoy's town and the host's, with their books and their leaders. Nothing changes yet. */
    public static Talk negotiate(ServerLevel level, UUID envoyTown, UUID hostTown) {
        TradeBook.Book e = TradeBook.of(level, envoyTown), h = TradeBook.of(level, hostTown);
        Villages.Village ev = Villages.get(envoyTown), hv = Villages.get(hostTown);
        int every = ev != null && hv != null && Diplomacy.apart(ev, hv) > 400 ? EVERY + 1 : EVERY;
        int r = Ledger.relation(envoyTown, hostTown);
        int chem = Envoys.chemistry(envoyTown, hostTown);
        Manner me = manner(temper(envoyTown), r, chem), mh = manner(temper(hostTown), r, chem);
        boolean renewal = TradeDeals.live(envoyTown, hostTown);
        // The envoy brings its own surplus to sell, if the host wants any of it. If that comes to nothing (the host
        // could not pay what the envoy's town must have), the other way round: the envoy buys the host's surplus.
        Table sell = lay(level, e, h, every), buy = lay(level, h, e, every);
        if (sell == null && buy == null) {
            return new Talk(envoyTown, hostTown, null, List.of(), End.NOTHING, null, 0, 0, 0, 0, 0, 0, 0, me, mh, every, WEEKS, 0,
                "neither has anything the other is short of", renewal);
        }
        if (sell != null) {
            Talk k = bargain(sell, envoyTown, hostTown, me, mh, every, renewal);
            if (k.end() != End.NO_ROOM || buy == null) return k;
        }
        return bargain(buy, envoyTown, hostTown, mh, me, every, renewal);
    }

    /** The least a side will deal for: a twenty-fifth of what the goods are worth to it, a quarter of a coin at least. */
    static double margin(double worth) {
        return Math.max(0.25, 0.04 * worth);
    }

    /**
     * The bargaining itself, offer by offer, on a table already laid. The seller wants a high price and the
     * buyer a low one; neither goes past what leaves it better off by its own reckoning.
     */
    public static Talk bargain(Table t, UUID envoyTown, UUID hostTown, Manner ms, Manner mb, int every, boolean renewal) {
        boolean envoySells = t.seller().equals(envoyTown);
        double floor = t.sellerFloor(margin(t.qx() * t.sx()));
        double ceiling = Math.min(t.buyerCeiling(margin(t.qx() * t.bx())), t.canPay());
        List<Offer> offers = new ArrayList<>();
        double room = ceiling - floor;
        if (room < 0.25) {
            String why = t.qx() * t.bx() <= t.qx() * t.sx() ? "the goods are worth no more to the one than to the other"
                : t.canPay() < floor ? "the buyer cannot pay what the seller must have" : "there is no price good for both";
            return new Talk(envoyTown, hostTown, t, offers, End.NO_ROOM, null, 0, 0, 0, 0, 0, floor, ceiling, ms, mb, every, WEEKS, 0, why, renewal);
        }
        double a = floor + room * (1 + ms.reach()), b = floor - room * mb.reach();
        // However low a hard bargainer opens, it offers something: half what the seller must have, and a lot of its
        // own goods at the least (nobody opens with "nothing at all").
        TradeBook.Ware yw = t.y() == null ? null : TradeBook.Ware.of(new ItemStack(t.y()));
        double lot = yw == null ? 1.0 : t.mid() * yw.lot;
        b = Math.max(b, Math.min(floor, Math.max(floor * 0.5, lot)));
        double a0 = a, b0 = b;
        // The envoy speaks first: its town's offer.
        offers.add(new Offer(0, envoySells, envoySells ? a : b, t.terms(envoySells ? a : b)));
        offers.add(new Offer(0, !envoySells, envoySells ? b : a, t.terms(envoySells ? b : a)));
        End end = null;
        String why = "";
        for (int k = 1; k <= ROUNDS && a > b; k++) {
            double gap = a - b;
            // A prickly leader that sees the other still far off after a few rounds takes it as an insult.
            if (k >= 3 && gap > 0.25 * room && (ms.walks() || mb.walks())) {
                end = End.WALKED;
                boolean sellerWalks = ms.walks() && (!mb.walks() || !envoySells);
                why = (sellerWalks == envoySells ? "the envoy" : "the host's leader") + " walked out, " + Math.round(gap * 100 / room)
                    + "% of the room still between them";
                break;
            }
            a = Math.max(floor, a - ms.give() * gap);
            b = Math.min(ceiling, b + mb.give() * gap);
            offers.add(new Offer(k, envoySells, envoySells ? a : b, t.terms(envoySells ? a : b)));
            offers.add(new Offer(k, !envoySells, envoySells ? b : a, t.terms(envoySells ? b : a)));
        }
        if (end == null && a > b && a - b > 0.15 * room) {
            end = End.STUCK;
            why = "after " + ROUNDS + " rounds they were still " + Math.round((a - b) * 100 / room) + "% of the room apart";
        }
        if (end != null) {
            return new Talk(envoyTown, hostTown, t, offers, end, null, 0, 0, 0, (a0 - a) / room, (b - b0) / room, floor, ceiling,
                ms, mb, every, WEEKS, 0, why, renewal);
        }
        // They meet (or near enough, and split what is left between them).
        double p = Math.max(floor, Math.min(ceiling, (a + b) / 2.0));
        Terms terms = t.terms(p);
        // Whole loaves and whole coins: if the rounding has left either side no better off, a coin either way.
        for (int i = 0; i < 3 && (t.gainSeller(terms) <= 0 || t.gainBuyer(terms) <= 0); i++) {
            if (t.gainSeller(terms) <= 0 && terms.coin() + 1 <= t.coinCap()) terms = new Terms(terms.qy(), terms.coin() + 1);
            else if (t.gainBuyer(terms) <= 0 && terms.coin() > 0) terms = new Terms(terms.qy(), terms.coin() - 1);
            else if (t.gainBuyer(terms) <= 0 && terms.qy() > 0) terms = new Terms(terms.qy() - 1, terms.coin());
        }
        double gs = t.gainSeller(terms), gb = t.gainBuyer(terms);
        if (gs <= 0 || gb <= 0) {
            return new Talk(envoyTown, hostTown, t, offers, End.NO_ROOM, null, p, gs, gb, (a0 - p) / room, (p - b0) / room, floor, ceiling,
                ms, mb, every, WEEKS, 0, "whole loaves and coins left no room between them", renewal);
        }
        // Each delivery a side falls short, it owes a tenth of the delivery's price (a coin at least).
        int penalty = (int) Math.max(1, Math.round(p * 0.1));
        return new Talk(envoyTown, hostTown, t, offers, End.DEAL, terms, p, gs, gb, (a0 - p) / room, (p - b0) / room, floor, ceiling,
            ms, mb, every, WEEKS, penalty, "", renewal);
    }

    // ------------------------------------------------------------------ in words

    static String name(Item it) {
        return new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    /** "192 cobblestone". */
    static String lot(Item it, int n) {
        return n + " " + name(it);
    }

    /** What a price comes to: "80 bread", "80 bread and 4 coin", "12 coin". */
    public static String paid(Table t, Terms tm) {
        List<String> parts = new ArrayList<>();
        if (tm.qy() > 0 && t.y() != null) parts.add(lot(t.y(), tm.qy()));
        if (tm.coin() > 0 || parts.isEmpty()) parts.add(tm.coin() + (tm.coin() == 1 ? " coin" : " coin"));
        return String.join(" and ", parts);
    }

    static String often(int every) {
        return "every " + every + " days";
    }

    /** "128 cobblestone every 3 days for 80 bread and 4 coin, for 3 weeks". */
    public static String terms(Talk k) {
        Table t = k.table();
        if (t == null || k.settled() == null) return "";
        return lot(t.x(), t.qx()) + " " + often(k.every()) + " for " + paid(t, k.settled()) + ", for " + k.weeks() + " weeks";
    }

    /**
     * The negotiation as the chronicle has it, the way a player would tell it: "Ashford's envoy offered 128
     * cobblestone every 3 days for 120 bread; Brindle's elder offered 40 bread; then 101 against 76; they settled
     * at 84 bread and 2 coin, for 3 weeks".
     */
    public static String story(Talk k) {
        Table t = k.table();
        String en = Villages.name(k.envoyTown()), hn = Villages.name(k.hostTown());
        if (t == null) return en + "'s envoy came to " + hn + " to talk trade, but " + k.why();
        StringBuilder sb = new StringBuilder();
        List<Offer> o = k.offers();
        if (o.isEmpty()) {
            return en + "'s envoy offered " + (k.envoySells() ? lot(t.x(), t.qx()) : "to buy " + lot(t.x(), t.qx())) + " in " + hn
                + ", but " + k.why();
        }
        Offer first = o.get(0), reply = o.size() > 1 ? o.get(1) : null;
        if (k.envoySells()) sb.append(en).append("'s envoy offered ").append(lot(t.x(), t.qx())).append(' ').append(often(k.every()))
            .append(" for ").append(paid(t, first.terms()));
        else sb.append(en).append("'s envoy offered ").append(paid(t, first.terms())).append(' ').append(often(k.every()))
            .append(" for ").append(lot(t.x(), t.qx()));
        if (reply != null) sb.append("; ").append(hn).append("'s ").append(Homeland.leaderTitle(k.hostTown())).append(k.envoySells() ? " offered " : " asked ")
            .append(paid(t, reply.terms()));
        List<String> mid = new ArrayList<>();
        for (int i = 2; i + 1 < o.size() && mid.size() < 3; i += 2) mid.add(short_(t, o.get(i).terms()) + " against " + short_(t, o.get(i + 1).terms()));
        if (!mid.isEmpty()) sb.append("; then ").append(String.join(", ", mid));
        if (o.size() > 8) sb.append(", and so on, ").append(o.size() / 2 - 1).append(" rounds");
        if (k.deal()) sb.append("; they settled at ").append(paid(t, k.settled())).append(", for ").append(k.weeks()).append(" weeks");
        else sb.append("; no deal — ").append(k.why());
        return sb.toString();
    }

    /** An offer in a word or two: "101" (of the buyer's goods), or "80+4c". */
    static String short_(Table t, Terms tm) {
        if (t.y() == null || tm.qy() == 0) return tm.coin() + "c";
        return tm.coin() > 0 ? tm.qy() + "+" + tm.coin() + "c" : Integer.toString(tm.qy());
    }

    // ------------------------------------------------------------------ at the audience

    /**
     * The audience, staged (Assemblies' ENVOY script): the envoy's lists, the host's reading of them, the first
     * offers and the counter-offers, said by the envoy and the host's leader before the board, and the deal
     * shaken on (or not) — the deal itself made when that line is said. Returns false if there is nobody to talk.
     */
    static boolean audience(ServerLevel level, UUID host, VillageFolkEntity envoy, Caravans.Trip trip,
                            List<Assemblies.Line> s, RandomSource r) {
        UUID from = trip.from;
        UUID guest = envoy.getUUID();
        Talk k = negotiate(level, from, host);
        long day = level.getDayTime() / 24000L;
        TradeBook.Book eb = TradeBook.of(level, from), hb = TradeBook.of(level, host);
        String fn = Villages.name(from), elder = Villages.elderName(from);
        String sender = elder.isEmpty() ? "The folk of " + fn : "Elder " + elder + " of " + fn;
        // The envoy's lists.
        List<String> has = new ArrayList<>(), lacks = new ArrayList<>();
        for (TradeBook.Entry e : eb.surpluses()) has.add(e.itemName());
        for (TradeBook.Entry e : eb.shortages()) lacks.add(e.ware().word);
        String lists = (has.isEmpty() ? "We've little to spare" : "We've " + TradeBook.join(has.subList(0, Math.min(3, has.size()))) + " to spare")
            + (lacks.isEmpty() ? "." : ", and we're short of " + TradeBook.join(lacks.subList(0, Math.min(3, lacks.size()))) + ".");
        s.add(new Assemblies.Line(guest, (k.renewal() ? sender + " would talk over our deal again. " : sender + " wants to trade. ")
            + lists, '?', null));
        // The host's reading of them against its own books.
        Table t = k.table();
        if (t == null) {
            s.add(new Assemblies.Line(null, "We've nothing you want, and want nothing you have. There's no deal to be had today.", '?',
                () -> TradeDeals.noDeal(level, k, day, trip)));
            s.add(new Assemblies.Line(guest, FolkTalk.pick(r, "Another time, then.", "I'll tell them. Perhaps after the harvest."), ' ',
                () -> Envoys.heard(guest)));
            return true;
        }
        String goods = name(t.x());
        TradeBook.Entry hostView = hb.get(TradeBook.Ware.of(new ItemStack(t.x())));
        if (k.envoySells()) {
            s.add(new Assemblies.Line(null, "We're short of " + hostView.ware().word + " here" + (t.y() == null ? "" : ", and we've "
                + name(t.y()) + " over") + ". Let's hear your terms.", '?', null));
        } else {
            s.add(new Assemblies.Line(null, "We've " + goods + " to spare, it's true. What would you give for it?", '?', null));
        }
        // The offers, the first two rounds of them spoken; the rest go by in the murmur.
        List<Offer> o = k.offers();
        for (int i = 0; i < o.size() && i < 4; i++) {
            Offer of = o.get(i);
            boolean envoySpeaks = of.byEnvoy();
            String said = offerWords(k, of, i, r);
            s.add(new Assemblies.Line(envoySpeaks ? guest : null, said, i % 2 == 0 ? '?' : ' ', null));
        }
        if (o.size() > 4 && k.end() != End.WALKED) {
            s.add(new Assemblies.Line(null, "Back and forth it went — " + (o.size() / 2 - 1) + " rounds of it.", '?', null));
        }
        switch (k.end()) {
            case DEAL -> {
                String deal = terms(k);
                String said = (k.hostManner().temper() == Envoys.Temper.SHREWD ? "Very well. " : FolkTalk.pick(r, "Done, then! ", "Agreed! "))
                    + capital(deal) + ". A delivery short costs " + k.penalty() + " coin, and three in a row end it. Shake on it!";
                s.add(new Assemblies.Line(null, said, '!', () -> TradeDeals.strike(level, k, day, trip)));
                s.add(new Assemblies.Line(guest, FolkTalk.pick(r, "Gladly! They'll be pleased at home.", "Done! I'll tell them at once."), '!',
                    () -> Envoys.heard(guest)));
            }
            case WALKED -> {
                boolean hostWalks = k.hostManner().walks();
                s.add(new Assemblies.Line(hostWalks ? null : guest, hostWalks ? "Enough! I'll not be robbed in my own square. Good day."
                    : "Then we're wasting each other's time. Good day!", '?', () -> TradeDeals.noDeal(level, k, day, trip)));
                s.add(new Assemblies.Line(guest, "I'll take that home, then.", ' ', () -> Envoys.heard(guest)));
            }
            default -> {
                s.add(new Assemblies.Line(null, k.end() == End.STUCK ? "We're too far apart. Come back when your " + goods + " is cheaper."
                    : "I can't make it pay for both of us — " + k.why() + ".", '?', () -> TradeDeals.noDeal(level, k, day, trip)));
                s.add(new Assemblies.Line(guest, FolkTalk.pick(r, "So be it. I'll tell them.", "Another time, perhaps."), ' ',
                    () -> Envoys.heard(guest)));
            }
        }
        return true;
    }

    /** What is said across the table for one offer, as the one making it would put it. */
    static String offerWords(Talk k, Offer of, int i, RandomSource r) {
        Table t = k.table();
        String price = paid(t, of.terms());
        String goods = lot(t.x(), t.qx());
        boolean seller = of.byEnvoy() == k.envoySells();
        Envoys.Temper tm = seller ? k.seller().temper() : k.buyer().temper();
        if (i == 0) {
            return seller ? capital(goods) + " " + often(k.every()) + ", for " + price + "."
                : "We'd give " + price + " " + often(k.every()) + " for " + goods + ".";
        }
        if (i == 1) {
            String jab = tm == Envoys.Temper.SHREWD || tm == Envoys.Temper.PRICKLY ? FolkTalk.pick(r, "Ha! ", "That much? ") : "";
            return seller ? jab + "For " + goods + "? I'd want " + price + "."
                : jab + "I'll give " + price + ", and that's fair.";
        }
        if (seller) return tm == Envoys.Temper.SHREWD ? price + ". Not a crumb less." : FolkTalk.pick(r, "Meet me at " + price + ".", price + ", then.");
        return tm == Envoys.Temper.SHREWD ? price + ", and not a crumb more." : FolkTalk.pick(r, price + ". Let's be fair to each other.", price + ", then.");
    }

    /**
     * Nobody came to hear the envoy (Envoys.waitThere): the host's leader's answer by word of mouth, the
     * same bargaining done between the two of them, and what comes of it.
     */
    static Envoys.Answer answer(ServerLevel level, UUID host, UUID from, VillageFolkEntity envoy, Caravans.Trip t) {
        Talk k = negotiate(level, from, host);
        long day = level.getDayTime() / 24000L;
        if (k.deal()) {
            return new Envoys.Answer(true, "Done: " + terms(k) + ".", () -> TradeDeals.strike(level, k, day, t));
        }
        return new Envoys.Answer(false, "No deal — " + k.why() + ".", () -> TradeDeals.noDeal(level, k, day, t));
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
