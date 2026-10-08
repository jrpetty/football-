package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-trade] The trade deals between towns, and how they are carried out.
 *
 * <p><b>A deal</b> is what an envoy and the other town's leader shook on (TradeTalks): so many of the seller's
 * goods each delivery, for so many of the buyer's and so much coin, every few days for three weeks, and a
 * tenth of the price owed for every delivery a side falls short on. It is kept in both towns' ledgers, so it
 * outlasts a restart, with how it has gone: deliveries made against those due, each side's shortfalls, what
 * each has gained by its own prices, and what either owes the other.
 *
 * <p><b>Deliveries go by caravan</b> (Caravans), each way in turn. The carrier sets out in the morning with its
 * own town's goods — the agreed goods and no others, never more than agreed, and never more than the town can
 * spare (Budget.spare: its own needs first) — and, if its town is the one paying, the coin, carried in its
 * purse. At the other town it unloads the goods, the other town loads its own goods for the way back, and
 * the coin changes hands there and then, in person. Home again, it unloads and the coin goes into its own
 * treasury. Nothing goes chest to chest: whatever the config says about sharing goods, a deal's goods are
 * walked there.
 *
 * <p><b>Specialising.</b> A town whose bread comes in from a partner, reliably, needs fewer hands in its own
 * fields; a town that has promised its partner stone needs more in the mine. So each morning the leader leans
 * the trades' shares (Villages.target): down for what comes in, by how much of the town's use the deliveries
 * cover and how reliably they have come; up for what goes out, the more where the land lives by that trade
 * (Homeland). Never past two thirds or seven fifths of the usual share, a step at a time toward the
 * specialist and all at once back from it — and never fewer farmers in a town on short commons or hungry. If
 * the bread stops coming, the fields fill again the next morning.
 *
 * <p><b>Spoken for.</b> What a town has promised its partner is not its glut: the off-map traders do not buy
 * it (Market.trade, sellSurplus), and the fields and the mine are not cut back for it (Villages.weighGluts).
 */
public final class TradeDeals {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private TradeDeals() {}

    /** Three short deliveries in a row and a deal is broken. */
    static final int BREAKS = 3;
    /** How far a trade's share may lean, and how fast toward the specialist. */
    static final double LEAN_LO = 0.65, LEAN_HI = 1.4, LEAN_STEP = 0.05;

    // ------------------------------------------------------------------ the deal

    /** One standing deal: kept as a line in both towns' ledgers (deal/&lt;the other town&gt;). */
    static final class Deal {
        UUID seller, buyer, envoy;
        Item x;
        int qx;
        @Nullable Item y;
        int qy, coin, every, penalty;
        long struck, ends, next;
        double price, sGain, bGain;
        int due, made, sShort, bShort, sRun, bRun, sOwes, bOwes;
        double sGot, bGot;
        boolean sellerSends = true, onRoad, broken;
        int outLoaded, backLoaded;
        /** The last three deliveries of each side's goods, in the hundred of what was agreed. */
        String sRecent = "", bRecent = "";

        UUID other(UUID v) { return v.equals(seller) ? buyer : seller; }
        boolean sells(UUID v) { return v.equals(seller); }
        @Nullable Item sends(UUID v) { return sells(v) ? x : y; }
        int sendsEach(UUID v) { return sells(v) ? qx : qy; }
        @Nullable Item gets(UUID v) { return sells(v) ? y : x; }
        int getsEach(UUID v) { return sells(v) ? qy : qx; }

        String format() {
            return String.join("|", "v1", seller.toString(), buyer.toString(), envoy.toString(), id(x), Integer.toString(qx),
                y == null ? "" : id(y), Integer.toString(qy), Integer.toString(coin), Integer.toString(every), Integer.toString(penalty),
                Long.toString(struck), Long.toString(ends), Long.toString(next), num(price), num(sGain), num(bGain),
                Integer.toString(due), Integer.toString(made), Integer.toString(sShort), Integer.toString(bShort),
                Integer.toString(sRun), Integer.toString(bRun), Integer.toString(sOwes), Integer.toString(bOwes),
                num(sGot), num(bGot), sellerSends ? "s" : "b", onRoad ? "1" : "0", broken ? "1" : "0",
                Integer.toString(outLoaded), Integer.toString(backLoaded), sRecent, bRecent);
        }

        @Nullable
        static Deal parse(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 34 || !p[0].equals("v1")) return null;
            try {
                Deal d = new Deal();
                d.seller = UUID.fromString(p[1]);
                d.buyer = UUID.fromString(p[2]);
                d.envoy = UUID.fromString(p[3]);
                d.x = TradeBook.itemOf(p[4]);
                if (d.x == null) return null;
                d.qx = Integer.parseInt(p[5]);
                d.y = p[6].isEmpty() ? null : TradeBook.itemOf(p[6]);
                d.qy = Integer.parseInt(p[7]);
                d.coin = Integer.parseInt(p[8]);
                d.every = Math.max(1, Integer.parseInt(p[9]));
                d.penalty = Integer.parseInt(p[10]);
                d.struck = Long.parseLong(p[11]);
                d.ends = Long.parseLong(p[12]);
                d.next = Long.parseLong(p[13]);
                d.price = Double.parseDouble(p[14]);
                d.sGain = Double.parseDouble(p[15]);
                d.bGain = Double.parseDouble(p[16]);
                d.due = Integer.parseInt(p[17]);
                d.made = Integer.parseInt(p[18]);
                d.sShort = Integer.parseInt(p[19]);
                d.bShort = Integer.parseInt(p[20]);
                d.sRun = Integer.parseInt(p[21]);
                d.bRun = Integer.parseInt(p[22]);
                d.sOwes = Integer.parseInt(p[23]);
                d.bOwes = Integer.parseInt(p[24]);
                d.sGot = Double.parseDouble(p[25]);
                d.bGot = Double.parseDouble(p[26]);
                d.sellerSends = p[27].equals("s");
                d.onRoad = p[28].equals("1");
                d.broken = p[29].equals("1");
                d.outLoaded = Integer.parseInt(p[30]);
                d.backLoaded = Integer.parseInt(p[31]);
                d.sRecent = p[32];
                d.bRecent = p[33];
                return d;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    static String id(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------ keeping them

    /** The deals each town is in, read from its ledger and kept till one changes. */
    private static final Map<UUID, List<Deal>> CACHE = new ConcurrentHashMap<>();
    /** The trades' leans by town (the specialising), kept in the ledger too. */
    private static final Map<UUID, Map<StationTask, Double>> LEAN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CACHE.clear();
        LEAN.clear();
        TradeBook.resetForTests();
        TradeTalks.resetForTests();
    }

    static List<Deal> deals(UUID village) {
        return CACHE.computeIfAbsent(village, v -> {
            List<Deal> out = new ArrayList<>();
            for (Map.Entry<String, String> e : Ledger.notes(v).entrySet()) {
                if (!e.getKey().startsWith("deal/")) continue;
                Deal d = Deal.parse(e.getValue());
                if (d != null) out.add(d);
            }
            return out;
        });
    }

    @Nullable
    static Deal deal(UUID a, UUID b) {
        for (Deal d : deals(a)) if (d.other(a).equals(b)) return d;
        return null;
    }

    /** Is there a standing deal between these two? */
    public static boolean live(UUID a, UUID b) {
        return deal(a, b) != null;
    }

    static void save(Deal d) {
        String line = d.format();
        Ledger.note(d.seller, "deal/" + d.buyer, line);
        Ledger.note(d.buyer, "deal/" + d.seller, line);
        CACHE.remove(d.seller);
        CACHE.remove(d.buyer);
    }

    private static void drop(Deal d) {
        Ledger.forget(d.seller, "deal/" + d.buyer);
        Ledger.forget(d.buyer, "deal/" + d.seller);
        CACHE.remove(d.seller);
        CACHE.remove(d.buyer);
    }

    // ------------------------------------------------------------------ the record: talks, deals past, the day's log

    private static final String SEP = "~";

    private static List<String> list(UUID village, String key) {
        String s = Ledger.note(village, key);
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String one : s.split(SEP)) if (!one.isEmpty()) out.add(one);
        return out;
    }

    private static void push(UUID village, String key, String line, int most) {
        List<String> all = list(village, key);
        all.add(0, line.replace(SEP, "-"));
        while (all.size() > most) all.remove(all.size() - 1);
        Ledger.note(village, key, String.join(SEP, all));
    }

    /** Into the town's trade log (the gazette's, and the page's): "day|what". */
    static void log(UUID village, long day, String what) {
        push(village, "trade.log", day + "|" + what, 16);
    }

    // ------------------------------------------------------------------ striking one

    /** The deal shaken on at the audience (TradeTalks): written into both towns' books, the pact signed. */
    static void strike(ServerLevel level, TradeTalks.Talk k, long day, @Nullable Caravans.Trip trip) {
        if (!k.deal()) {
            noDeal(level, k, day, trip);
            return;
        }
        TradeTalks.Table t = k.table();
        TradeTalks.Terms tm = k.settled();
        Deal old = deal(t.seller(), t.buyer());
        String was = null;
        if (old != null) {
            double before = old.price / Math.max(1, old.qx), now = k.price() / Math.max(1, t.qx());
            was = Math.abs(now - before) <= before * 0.1 && old.x == t.x() ? "renewed on much the same terms"
                : "renegotiated: the price moved from " + Budget.priceWords(before) + " to " + Budget.priceWords(now) + " a " + TradeTalks.name(t.x());
            close(level, old, "renewed", day);
        }
        Deal d = new Deal();
        d.seller = t.seller();
        d.buyer = t.buyer();
        d.envoy = k.envoyTown();
        d.x = t.x();
        d.qx = t.qx();
        d.y = tm.qy() > 0 ? t.y() : null;
        d.qy = d.y == null ? 0 : tm.qy();
        d.coin = tm.coin();
        d.every = k.every();
        d.penalty = k.penalty();
        d.struck = day;
        d.ends = day + 7L * k.weeks();
        d.next = day;
        d.price = k.price();
        d.sGain = k.gainSeller();
        d.bGain = k.gainBuyer();
        if (old != null && old.onRoad && old.x == d.x && old.seller.equals(d.seller)) {
            // A caravan of the old deal still on the road: the new one takes over its delivery as it stands.
            d.onRoad = true;
            d.outLoaded = old.outLoaded;
            d.backLoaded = old.backLoaded;
            d.sellerSends = old.sellerSends;
            d.due = 1;
            d.next = day + d.every;
        }
        save(d);
        UUID a = k.envoyTown(), b = k.hostTown();
        if (!Envoys.pact(a, b)) Envoys.sign(a, b, day);
        // Warmer for it: the more so when neither did badly out of it.
        double share = (k.price() - k.lo()) / Math.max(0.01, k.hi() - k.lo());
        Ledger.relate(a, b, share > 0.2 && share < 0.8 ? 6 : 4);
        String terms = TradeTalks.terms(k);
        Bonds.remember(a, b, day, 3, "we struck a trade deal: " + terms);
        String story = TradeTalks.story(k) + (was == null ? "" : " (" + was + ")");
        Villages.tell(a, day, story);
        Villages.tell(b, day, story);
        Identity.event(a, Identity.Ev.DEAL, day);                       // [identity] toward Merchant Princes, and a mercantile town
        Identity.event(b, Identity.Ev.DEAL, day);
        for (UUID v : new UUID[]{ a, b }) {
            push(v, "talks", day + "|" + story + "|" + gainWords(k, v), 8);
            log(v, day, "a deal with " + Villages.name(d.other(v)) + ": " + dealWords(d, v));
        }
        if (trip != null) {
            trip.outcome = "a deal — " + (k.envoySells() ? "we send " + terms : "they send " + terms);
        }
        Market.assemblyNews(a, "We've a trade deal with " + Villages.name(b) + ": " + dealWords(d, a) + ".");
        LOG.info("[MCA-TRADE] deal {} -> {}: {} (seller +{}, buyer +{} by their own prices; {} rounds)", Villages.name(a), Villages.name(b),
            terms, num(k.gainSeller()), num(k.gainBuyer()), k.offers().size() / 2 - 1);
    }

    /** No deal: written down all the same, and the two towns a little warmer (or, for a walk-out, cooler). */
    static void noDeal(ServerLevel level, TradeTalks.Talk k, long day, @Nullable Caravans.Trip trip) {
        UUID a = k.envoyTown(), b = k.hostTown();
        int delta = k.end() == TradeTalks.End.WALKED ? -3 : 1;
        Ledger.relate(a, b, delta);
        if (k.end() == TradeTalks.End.WALKED) Bonds.remember(a, b, day, -2, "the trade talks ended in a walk-out");
        String story = TradeTalks.story(k);
        for (UUID v : new UUID[]{ a, b }) {
            Villages.tell(v, day, story);
            push(v, "talks", day + "|" + story + "|", 8);
        }
        Ledger.note(a, "talks.last/" + b, Long.toString(day));
        Ledger.note(b, "talks.last/" + a, Long.toString(day));
        if (trip != null) trip.outcome = "no deal — " + k.why();
        LOG.info("[MCA-TRADE] no deal {} -> {}: {} ({})", Villages.name(a), Villages.name(b), k.end(), k.why());
    }

    static String gainWords(TradeTalks.Talk k, UUID v) {
        boolean seller = k.table() != null && k.table().seller().equals(v);
        double us = seller ? k.gainSeller() : k.gainBuyer(), them = seller ? k.gainBuyer() : k.gainSeller();
        return "+" + Math.round(us) + " coin a delivery to us, +" + Math.round(them) + " to them, each by its own prices";
    }

    /** Should these two talk trade (Envoys.choose)? No deal between them and something to trade, or a deal near its end. */
    public static boolean talksDue(ServerLevel level, UUID a, UUID b, long day) {
        String last = Ledger.note(a, "talks.last/" + b);
        try {
            if (last != null && !last.isEmpty() && day - Long.parseLong(last) < 4) return false;
        } catch (NumberFormatException ignored) {
            // talk again
        }
        Deal d = deal(a, b);
        if (d != null) return d.ends - day <= 2;
        return TradeBook.complementary(level, a, b);
    }

    // ------------------------------------------------------------------ the day

    private static final Map<UUID, Long> MORNING = new ConcurrentHashMap<>();

    /** Every few seconds (Caravans' round): trips kept, each town's morning, and the deals' caravans due. */
    public static void tick(ServerLevel level) {
        TradeTrips.keep(level);
        long dayTime = level.getDayTime(), t = dayTime % 24000L, day = dayTime / 24000L;
        if (t < 1000L || t > 11000L) return;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Long done = MORNING.get(v.id());
            if (done == null) {
                String kept = Ledger.note(v.id(), "trade.morning");
                try { done = kept == null || kept.isEmpty() ? -1L : Long.parseLong(kept); } catch (NumberFormatException e) { done = -1L; }
            }
            if (done >= day) { MORNING.put(v.id(), done); continue; }
            MORNING.put(v.id(), day);
            Ledger.note(v.id(), "trade.morning", Long.toString(day));
            Guard.run("trade morning", () -> morning(level, v, day));
        }
        if (t > 7000L) return;                                   // the caravans set out in the morning
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            for (Deal d : new ArrayList<>(deals(v.id()))) {
                if (!d.seller.equals(v.id())) continue;          // each deal once, from the seller's side
                Guard.run("trade deal", () -> run(level, d, day));
            }
        }
    }

    /** One deal's day: torn up, run out, lost on the road, missed, or a caravan due to set out. */
    static void run(ServerLevel level, Deal d, long day) {
        Villages.Village s = Villages.get(d.seller), b = Villages.get(d.buyer);
        if (s == null || b == null) { drop(d); return; }
        if (!Envoys.pact(d.seller, d.buyer)) { close(level, d, "torn up when the two fell out", day); return; }
        boolean road = Caravans.between(d.seller, d.buyer) || TradeTrips.pending(d.seller, d.buyer);
        if (d.onRoad && !road) {
            // The caravan never came home: lost on the road, and what it carried with it.
            d.onRoad = false;
            UUID sender = d.sellerSends ? d.buyer : d.seller;          // (the turn had passed on when it set out)
            fellShort(level, d, sender, day, "its caravan was lost on the road");
            recent(d, true, 0);
            recent(d, false, 0);
            save(d);
            return;
        }
        if (road) return;
        if (day >= d.ends) { close(level, d, "ran its term", day); return; }
        if (day < d.next) return;
        if (day >= d.next + d.every) {
            // Overdue a whole round and nothing sent: the sender's caravan never went.
            UUID sender = d.sellerSends ? d.seller : d.buyer;
            d.due++;
            fellShort(level, d, sender, day, "no caravan came");
            recent(d, true, 0);
            recent(d, false, 0);
            d.next += d.every;
            d.sellerSends = !d.sellerSends;
            if (d.broken) close(level, d, "broken", day); else save(d);
            return;
        }
        setOut(level, d, day);
    }

    /** A deal's delivery sets out: the sender's own goods (as agreed, as it can spare) and, if it pays, the coin. */
    static boolean setOut(ServerLevel level, Deal d, long day) {
        UUID from = d.sellerSends ? d.seller : d.buyer, to = d.other(from);
        Villages.Village fv = Villages.get(from), tv = Villages.get(to);
        if (fv == null || tv == null) return false;
        VillageFolkEntity carrier = Caravans.choose(fv);
        if (carrier == null) return false;
        Item item = d.sends(from);
        int agreed = d.sendsEach(from);
        int loaded = 0;
        carrier.clearQueue();
        if (item != null && agreed > 0) {
            int can = Math.min(agreed, Math.max(0, Budget.spare(level, from, new ItemStack(item))));
            for (ItemStack s : Caravans.takeOut(level, fv, st -> st.is(item) && !st.isEnchanted(), can)) {
                ItemStack left = carrier.insertGiven(s);
                loaded += s.getCount() - left.getCount();
                if (!left.isEmpty()) Market.intoStores(level, from, left);
            }
        }
        // The coin the payer owes goes with its own caravan, in the carrier's purse.
        int carry = from.equals(d.buyer) ? d.coin + d.bOwes : d.sOwes;
        int carried = carry > 0 ? Ledger.takeCoins(from, carry) : 0;
        Caravans.Trip t = new Caravans.Trip(from, to, Caravans.way(fv, tv));
        t.trade = true;
        t.deal = from + ">" + to;
        t.purse = carried;
        t.gainedTick = carrier.tickCount;
        carrier.trip(t);
        Riding.packFor(level, fv, carrier);
        Caravans.roadMoney(level, fv, carrier, tv);
        d.outLoaded = loaded;
        d.backLoaded = 0;
        d.onRoad = true;
        d.due++;
        d.next = Math.max(d.next + d.every, day + 1);
        d.sellerSends = !d.sellerSends;
        save(d);
        TradeBook.Ware w = item == null ? null : TradeBook.Ware.of(new ItemStack(item));
        if (w != null) TradeBook.moved(from, w, -loaded);
        TradeBook.forget(from);
        String tn = Villages.name(to);
        String what = item == null || loaded == 0 ? "" : TradeTalks.lot(item, loaded);
        String coins = carried > 0 ? carried + " coin" : "";
        String cargo = what.isEmpty() ? coins : coins.isEmpty() ? what : what + " and " + coins;
        Villages.tell(from, day, "a caravan set out for " + tn + (cargo.isEmpty() ? " to fetch what our deal brings us" : " with " + cargo)
            + ", as our deal with them asks" + (item != null && loaded < agreed ? " (only " + loaded + " of the " + agreed + " could be spared)" : ""));
        FolkTalk.speak(carrier, item != null && loaded > 0 ? "Off to " + tn + " with the " + TradeTalks.name(item) + " we owe them!"
            : "Off to " + tn + " to fetch what they owe us!");
        LOG.info("[MCA-TRADE] delivery {} -> {}: {} of {} {}, {} coin carried", Villages.name(from), tn, loaded, agreed,
            item == null ? "-" : TradeTalks.name(item), carried);
        return true;
    }

    /**
     * A deal's caravan at the end of a leg (Caravans.arrive). Out at the other town: the agreed goods off its
     * back (no more than agreed), the other town's goods on for the way home, the coin paid in person, the
     * delivery booked. Home: the other town's goods into the stores and the coin into the treasury. Returns
     * whether it was a deal's to handle.
     */
    static boolean exchange(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        Deal d = deal(t.from, t.to);
        if (d == null) return false;
        long day = level.getDayTime() / 24000L;
        UUID sender = t.from, other = t.to;
        if (!t.back) {
            Villages.Village here = Villages.get(other);
            if (here == null) return false;
            Item sent = d.sends(sender), back = d.sends(other);
            int agreedOut = d.sendsEach(sender), agreedBack = d.sendsEach(other);
            // What each side's goods are worth to each town, by its own prices before they change hands: what it was
            // worth to the farm town to get the stone is the stone's worth to it while it had none.
            double sx = TradeBook.worth(level, d.seller, d.x), bx = TradeBook.worth(level, d.buyer, d.x);
            double sy = d.y == null ? 0 : TradeBook.worth(level, d.seller, d.y), by = d.y == null ? 0 : TradeBook.worth(level, d.buyer, d.y);
            // The sender's goods: what it loaded for the deal, and no more.
            int delivered = sent == null ? 0 : unload(level, f, other, sent, Math.min(d.outLoaded, agreedOut));
            // This town's goods for the way back, as it can spare them.
            int loaded = 0;
            if (back != null && agreedBack > 0) {
                int can = Math.min(agreedBack, Math.max(0, Budget.spare(level, other, new ItemStack(back))));
                for (ItemStack s : Caravans.takeOut(level, here, st -> st.is(back) && !st.isEnchanted(), can)) {
                    ItemStack left = f.insertGiven(s);
                    loaded += s.getCount() - left.getCount();
                    if (!left.isEmpty()) Market.intoStores(level, other, left);
                }
            }
            d.backLoaded = loaded;
            int dx = sender.equals(d.seller) ? delivered : loaded, dy = sender.equals(d.seller) ? loaded : delivered;
            double fx = d.qx > 0 ? Math.min(1.0, dx / (double) d.qx) : 1.0;
            double fy = d.y == null || d.qy <= 0 ? 1.0 : Math.min(1.0, dy / (double) d.qy);
            // The coin: the buyer's, pro rata to the seller's goods that came; less (or more) what either owes.
            int toSeller = (int) Math.round(d.coin * fx) + d.bOwes;
            int toBuyer = d.sOwes;
            int net = toSeller - toBuyer;
            UUID payer = net >= 0 ? d.buyer : d.seller, payee = d.other(payer);
            int owed = Math.abs(net), paid = 0;
            if (owed > 0) {
                if (payer.equals(other)) {
                    // This town pays: out of its treasury into the carrier's purse, to be carried home.
                    paid = Ledger.takeCoins(payer, owed);
                    t.purse += paid;
                    t.earned += paid;
                } else {
                    // The carrier's town pays: out of the purse it carried here.
                    paid = Math.min(t.purse, owed);
                    t.purse -= paid;
                    Ledger.addCoins(payee, paid);
                    Economy.sold(payee, paid);
                }
                Economy.spent(payer, paid);
            }
            d.bOwes = 0;
            d.sOwes = 0;
            if (paid < owed) {
                if (payer.equals(d.buyer)) d.bOwes = owed - paid; else d.sOwes = owed - paid;
            }
            int toSellerNow = payee.equals(d.seller) ? paid : -paid;
            // Shortfalls: a side that delivered under four fifths of what it agreed owes for it.
            boolean sGood = fx >= 0.8, bGood = fy >= 0.8;
            if (!sGood) fellShort(level, d, d.seller, day, "it sent only " + dx + " of the " + d.qx + " " + TradeTalks.name(d.x));
            else d.sRun = 0;
            if (!bGood && d.y != null) fellShort(level, d, d.buyer, day, "it sent only " + dy + " of the " + d.qy + " " + TradeTalks.name(d.y));
            else d.bRun = 0;
            recent(d, true, fx);
            recent(d, false, fy);
            // What each side gained this time, by its own prices.
            d.sGot += dy * sy + toSellerNow - dx * sx;
            d.bGot += dx * bx - dy * by - toSellerNow;
            d.made++;
            save(d);
            if (sent != null && delivered > 0) {
                TradeBook.Ware w = TradeBook.Ware.of(new ItemStack(sent));
                if (w != null) TradeBook.moved(other, w, delivered);
                if (w == TradeBook.Ware.FOOD) Leader.foodIn(other, new ItemStack(sent, delivered));   // the leader's books: food in
            }
            if (back != null && loaded > 0) {
                TradeBook.Ware w = TradeBook.Ware.of(new ItemStack(back));
                if (w != null) TradeBook.moved(other, w, -loaded);
            }
            TradeBook.forget(other);
            if (sGood && bGood) Ledger.relate(sender, other, 1);
            String sn = Villages.name(sender);
            String came = sent == null || delivered == 0 ? "nothing" : TradeTalks.lot(sent, delivered);
            String went = back == null || loaded == 0 ? "" : TradeTalks.lot(back, loaded);
            String coin = paid <= 0 ? "" : payer.equals(other) ? "we paid " + paid + " coin" : "they paid us " + paid + " coin";
            Villages.tell(other, day, "the caravan from " + sn + " brought " + came + " by our deal"
                + (went.isEmpty() ? "" : "; we sent " + went + " back") + (coin.isEmpty() ? "" : "; " + coin));
            log(other, day, "from " + sn + ": " + came + (went.isEmpty() ? "" : ", " + went + " back") + (coin.isEmpty() ? "" : ", " + coin));
            FolkTalk.speak(f, delivered > 0 ? "Here's your " + TradeTalks.name(sent) + ", as agreed!" : "Nothing to bring this time, I'm sorry.");
            LOG.info("[MCA-TRADE] exchange at {}: {} in, {} back, {} coin {} (owed {})", Villages.name(other), came, went.isEmpty() ? "nothing" : went,
                paid, payer.equals(other) ? "into the purse" : "out of the purse", owed);
            return true;
        }
        // Home again with the other town's goods and the coin.
        Item back = d.sends(other);
        int got = back == null ? 0 : unload(level, f, sender, back, d.backLoaded);
        if (back != null && got > 0) {
            TradeBook.Ware w = TradeBook.Ware.of(new ItemStack(back));
            if (w != null) TradeBook.moved(sender, w, got);
            if (w == TradeBook.Ware.FOOD) Leader.foodIn(sender, new ItemStack(back, got));
        }
        int coin = t.purse;
        if (coin > 0) {
            Ledger.addCoins(sender, coin);
            if (t.earned > 0) Economy.sold(sender, Math.min(coin, t.earned));
        }
        t.purse = 0;
        t.earned = 0;
        d.onRoad = false;
        d.outLoaded = 0;
        d.backLoaded = 0;
        TradeBook.forget(sender);
        String on = Villages.name(other);
        String what = back == null || got == 0 ? "" : TradeTalks.lot(back, got);
        String cargo = what.isEmpty() ? (coin > 0 ? coin + " coin" : "") : coin > 0 ? what + " and " + coin + " coin" : what;
        Villages.tell(sender, day, "our caravan came home from " + on + (cargo.isEmpty() ? " empty-handed" : " with " + cargo) + ", by our deal");
        log(sender, day, "home from " + on + ": " + (cargo.isEmpty() ? "nothing" : cargo));
        if (d.broken) close(level, d, "broken", day); else save(d);
        return true;
    }

    /** Off the carrier's back and into a town's stores: up to {@code n} of the item. Returns how many went in. */
    static int unload(ServerLevel level, VillageFolkEntity f, UUID village, Item item, int n) {
        int moved = 0;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size() && moved < n; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int k = Math.min(n - moved, s.getCount());
            ItemStack left = Market.intoStores(level, village, s.copyWithCount(k));
            int in = k - left.getCount();
            s.shrink(in);
            moved += in;
            if (in < k) break;                                   // the stores are full: the rest goes home again
        }
        return moved;
    }

    /** A side fell short: it owes the penalty, the other town thinks less of it, and three in a row break the deal. */
    static void fellShort(ServerLevel level, Deal d, UUID who, long day, String why) {
        boolean seller = who.equals(d.seller);
        if (seller) { d.sShort++; d.sRun++; d.sOwes += d.penalty; } else { d.bShort++; d.bRun++; d.bOwes += d.penalty; }
        UUID other = d.other(who);
        Ledger.relate(who, other, -2);
        String wn = Villages.name(who), on = Villages.name(other);
        Villages.tell(other, day, wn + " fell short on our deal — " + why + "; it owes us " + d.penalty + " coin for it");
        Villages.tell(who, day, "we fell short on our deal with " + on + " — " + why + "; we owe them " + d.penalty + " coin for it");
        log(other, day, wn + " fell short: " + why);
        log(who, day, "we fell short with " + on + ": " + why);
        if ((seller ? d.sRun : d.bRun) >= BREAKS) d.broken = true;
    }

    /** The last three deliveries of a side's goods, in the hundred of what was agreed. */
    static void recent(Deal d, boolean sellers, double frac) {
        List<String> r = new ArrayList<>();
        String now = sellers ? d.sRecent : d.bRecent;
        for (String p : now.split(",")) if (!p.isEmpty()) r.add(p);
        r.add(Integer.toString((int) Math.round(Math.max(0, Math.min(1, frac)) * 100)));
        while (r.size() > 3) r.remove(0);
        if (sellers) d.sRecent = String.join(",", r); else d.bRecent = String.join(",", r);
    }

    /** How reliably a side's goods have come: the last three deliveries, nothing before the first. */
    static double reliability(String recent) {
        double sum = 0;
        int n = 0;
        for (String p : recent.split(",")) {
            if (p.isEmpty()) continue;
            try { sum += Math.min(100, Integer.parseInt(p)) / 100.0; n++; } catch (NumberFormatException ignored) { }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** A deal at an end: its term run, renewed, broken or torn up. Into both towns' past deals. */
    static void close(ServerLevel level, Deal d, String why, long day) {
        drop(d);
        boolean good = d.due > 0 && d.made >= d.due * 0.8 && !d.broken;
        for (UUID v : new UUID[]{ d.seller, d.buyer }) {
            UUID o = d.other(v);
            String line = "the deal with " + Villages.name(o) + " " + (why.equals("broken") ? "was broken" : why) + ": " + d.made + " of "
                + d.due + " deliveries made, " + signed(d.sells(v) ? d.sGot : d.bGot) + " coin to us by our prices";
            if (!why.equals("renewed")) Villages.tell(v, day, line);
            push(v, "deals.past", "day " + d.struck + "–" + day + ": with " + Villages.name(o) + ", " + dealWords(d, v) + " — " + d.made + " of "
                + d.due + " made, " + signed(d.sells(v) ? d.sGot : d.bGot) + " coin to us; " + why, 6);
            log(v, day, line);
        }
        if (why.equals("ran its term") && good) {
            Ledger.relate(d.seller, d.buyer, 3);
            Bonds.remember(d.seller, d.buyer, day, 3, "our trade deal ran its term, every delivery kept");
        } else if (why.equals("broken")) {
            UUID breaker = d.sRun >= BREAKS ? d.seller : d.buyer;
            Ledger.relate(d.seller, d.buyer, -8);
            Bonds.remember(d.seller, d.buyer, day, -4, Villages.name(breaker) + " broke its trade deal");
        }
        int owed = d.sOwes + d.bOwes;
        if (owed > 0 && !why.equals("renewed")) Ledger.relate(d.seller, d.buyer, -3);   // a debt left unpaid at the end
        LOG.info("[MCA-TRADE] deal {} / {} closed: {} ({} of {} made)", Villages.name(d.seller), Villages.name(d.buyer), why, d.made, d.due);
    }

    static String signed(double v) {
        long n = Math.round(v);
        return (n >= 0 ? "+" : "") + n;
    }

    /** The deal as one town has it: "128 cobblestone out, 80 bread and 4 coin in, every 3 days till day 33". */
    static String dealWords(Deal d, UUID v) {
        boolean seller = d.sells(v);
        String goodsOut = seller ? TradeTalks.lot(d.x, d.qx) : d.y == null ? "" : TradeTalks.lot(d.y, d.qy);
        String goodsIn = seller ? (d.y == null ? "" : TradeTalks.lot(d.y, d.qy)) : TradeTalks.lot(d.x, d.qx);
        String coin = d.coin > 0 ? d.coin + " coin" : "";
        String out = seller ? goodsOut : join(goodsOut, coin);
        String in = seller ? join(goodsIn, coin) : goodsIn;
        return (out.isEmpty() ? "" : out + " out, ") + (in.isEmpty() ? "nothing" : in) + " in, every " + d.every + " days till day " + d.ends;
    }

    /** The settled price: "a price of 1 coin for 6 a cobblestone, a delivery short 2 coin". */
    static String priceWords(Deal d) {
        return "a price of " + Budget.priceWords(d.price / Math.max(1, d.qx)) + " a " + TradeTalks.name(d.x) + ", " + d.penalty + " coin a delivery short";
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " and " + b;
    }

    // ------------------------------------------------------------------ specialising

    /** How much a trade's share leans this way for the town's deals (Villages.target). 1 for none. */
    public static double lean(@Nullable UUID village, StationTask trade) {
        if (village == null) return 1.0;
        Map<StationTask, Double> m = LEAN.computeIfAbsent(village, TradeDeals::readLean);
        Double l = m.get(trade);
        if (l == null) return 1.0;
        // Never fewer food-makers than usual in a town gone hungry or on short commons since the morning: whatever
        // the deals say, the fields come first the moment the larder does.
        boolean food = trade == StationTask.FARM || trade == StationTask.FISH || trade == StationTask.HUNT;
        if (l < 1.0 && food && (Market.hungry(village) || Leader.widening(village))) return 1.0;
        return l;
    }

    private static Map<StationTask, Double> readLean(UUID village) {
        Map<StationTask, Double> m = new EnumMap<>(StationTask.class);
        String s = Ledger.note(village, "trade.lean");
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split(",")) {
            String[] kv = part.split(":", 2);
            try { m.put(StationTask.valueOf(kv[0]), Double.parseDouble(kv[1])); } catch (RuntimeException ignored) { }
        }
        return m;
    }

    /** How much the land backs a trade a town exports by: wholly where it lives by it, half on poor ground. */
    static double landBoost(double lean) {
        return lean >= 1.2 ? 1.0 : lean >= 0.95 ? 0.75 : 0.5;
    }

    /**
     * The morning's lean of each trade, from the town's deals: down for what comes in (by the share of the
     * town's use the deliveries cover, and how reliably they have come), up for what goes out (by its share of
     * what the town makes, as the land backs it). A step at a time toward the specialist; all at once back.
     * Never fewer farmers on short commons, in a famine or hungry.
     */
    static void leanMorning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Map<StationTask, Double> up = new EnumMap<>(StationTask.class), down = new EnumMap<>(StationTask.class);
        TradeBook.Book book = TradeBook.of(level, id);
        List<String> why = new ArrayList<>();
        for (Deal d : deals(id)) {
            Item in = d.gets(id), out = d.sends(id);
            int qin = d.getsEach(id), qout = d.sendsEach(id);
            if (in != null && qin > 0) {
                TradeBook.Ware w = TradeBook.Ware.of(new ItemStack(in));
                if (w != null) {
                    double perDay = qin / (double) d.every;
                    double rho = reliability(d.sells(id) ? d.bRecent : d.sRecent);
                    double use = Math.max(perDay, book.get(w).used());
                    double cover = perDay * rho / Math.max(0.5, use);
                    down.merge(w.trade, Math.min(0.35, 0.6 * cover), Double::sum);
                    if (rho > 0) why.add(w.word + " from " + Villages.name(d.other(id)));
                }
            }
            if (out != null && qout > 0) {
                TradeBook.Ware w = TradeBook.Ware.of(new ItemStack(out));
                if (w != null) {
                    double perDay = qout / (double) d.every;
                    double share = perDay / Math.max(perDay, Math.max(0.5, book.get(w).made()));
                    up.merge(w.trade, Math.min(0.35, 0.6 * share) * landBoost(Homeland.lean(id, w.trade)), Double::sum);
                }
            }
        }
        Map<StationTask, Double> cur = LEAN.computeIfAbsent(id, TradeDeals::readLean);
        java.util.Set<StationTask> trades = java.util.EnumSet.noneOf(StationTask.class);
        trades.addAll(up.keySet());
        trades.addAll(down.keySet());
        trades.addAll(cur.keySet());
        Leader.Plan plan = Leader.plan(id);
        boolean lean = plan == Leader.Plan.SHORT || plan == Leader.Plan.FAMINE || Market.hungry(id);
        StringBuilder note = new StringBuilder();
        Map<StationTask, Double> next = new EnumMap<>(StationTask.class);
        for (StationTask tr : trades) {
            double target = Math.max(LEAN_LO, Math.min(LEAN_HI, 1.0 + up.getOrDefault(tr, 0.0) - down.getOrDefault(tr, 0.0)));
            boolean food = tr == StationTask.FARM || tr == StationTask.FISH || tr == StationTask.HUNT;
            if (food && lean) target = Math.max(1.0, target);
            double c = cur.getOrDefault(tr, 1.0);
            double n;
            if (Math.abs(target - 1.0) > Math.abs(c - 1.0) + 1e-9 && (c - 1.0) * (target - 1.0) >= 0) {
                n = c + Math.max(-LEAN_STEP, Math.min(LEAN_STEP, target - c));    // toward the specialist, a step a day
            } else {
                n = target;                                                       // back from it, at once
            }
            n = Math.round(n * 100) / 100.0;
            if (Math.abs(n - 1.0) < 0.005) continue;
            next.put(tr, n);
            if (note.length() > 0) note.append(',');
            note.append(tr.name()).append(':').append(num(n));
            // The first time a trade leans far, the leader says why.
            String said = Ledger.note(id, "lean.said/" + tr.name());
            if (Math.abs(n - 1.0) >= 0.1 && (said == null || said.isEmpty())) {
                Ledger.note(id, "lean.said/" + tr.name(), Long.toString(day));
                String trade = tr.title.toLowerCase(Locale.ROOT);
                Villages.tell(id, day, n < 1 ? "with " + (why.isEmpty() ? "the deals" : TradeBook.join(why)) + " coming in, the leader wants fewer hands at work as a " + trade
                    : "with the deals to keep, the leader wants more hands at work as a " + trade);
            } else if (Math.abs(n - 1.0) < 0.1 && said != null && !said.isEmpty()) {
                Ledger.note(id, "lean.said/" + tr.name(), "");
            }
        }
        // Back to the usual share where nothing leans it any more (the food back in the fields first of all).
        Double farmWas = cur.get(StationTask.FARM), farmNow = next.get(StationTask.FARM);
        if (farmWas != null && farmWas <= 0.9 && (farmNow == null || farmNow >= farmWas + 0.1)) {
            Villages.tell(id, day, lean ? "on short commons, the leader sent the hands back to the fields, deal or no deal"
                : "the deliveries stopped coming, and the leader sent the hands back to the fields");
        }
        LEAN.put(id, next);
        Ledger.note(id, "trade.lean", note.toString());
        // The specialising over the days, for the books: the leans, and the hands at each.
        int farmers = 0, miners = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.stationTask() == StationTask.FARM) farmers++;
            if (a.stationTask() == StationTask.MINE) miners++;
        }
        push(id, "trade.spec", day + ":" + Math.round(lean(id, StationTask.FARM) * 100) + ":" + Math.round(lean(id, StationTask.MINE) * 100)
            + ":" + farmers + ":" + miners, 40);
    }

    /** The town's morning: its book's rates, and the trades' leans. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        TradeBook.morning(level, v);
        leanMorning(level, v, day);
    }

    // ------------------------------------------------------------------ spoken for

    /** What the town has promised its partners of this market good: the next two deliveries' worth. */
    public static int spokenFor(UUID village, Market.Good g) {
        int n = 0;
        for (Deal d : deals(village)) {
            Item out = d.sends(village);
            if (out != null && g.what().test(new ItemStack(out))) n += 2 * d.sendsEach(village);
        }
        return n;
    }

    /** What the town has promised its partners of this kind of goods (Villages.weighGluts): the next two deliveries' worth. */
    public static int spokenFor(UUID village, Villages.Task task) {
        int n = 0;
        for (Deal d : deals(village)) {
            Item out = d.sends(village);
            TradeBook.Ware w = out == null ? null : TradeBook.Ware.of(new ItemStack(out));
            if (w != null && w.task == task) n += 2 * d.sendsEach(village);
        }
        return n;
    }

    // ------------------------------------------------------------------ the pact caravans without a deal

    /**
     * A pact caravan with no deal behind it (a pact a player brokered or chartered): what the other town is short
     * of, as far as this one can spare it (Budget.spare: its own needs first), a stack of each at most, four kinds.
     */
    static List<ItemStack> pactLoad(ServerLevel level, Villages.Village from, UUID to) {
        List<ItemStack> out = new ArrayList<>();
        TradeBook.Book theirs = TradeBook.of(level, to), ours = TradeBook.of(level, from.id());
        int kinds = 0;
        for (TradeBook.Entry want : theirs.shortages()) {
            if (kinds >= 4) break;
            TradeBook.Entry have = ours.get(want.ware());
            if (have.status() != TradeBook.Status.SURPLUS) continue;
            Item it = have.item();
            int n = Math.min(64, Math.min(Math.max(0, Budget.spare(level, from.id(), new ItemStack(it))), Math.max(want.ware().lot, want.want())));
            if (n < want.ware().lot) continue;
            List<ItemStack> got = Caravans.takeOut(level, from, s -> s.is(it) && !s.isEnchanted(), n);
            if (!got.isEmpty()) kinds++;
            out.addAll(got);
        }
        TradeBook.forget(from.id());
        return out;
    }

    /** What one of a thing fetches between two pact towns with no deal: halfway between the two towns' own prices. */
    static double pactPrice(ServerLevel level, UUID buyer, UUID seller, ItemStack s) {
        return (PriceIndex.each(level, buyer, s) + PriceIndex.each(level, seller, s)) / 2.0;
    }

    /**
     * Goods loaded at the other town for the way home (Caravans.arrive): bought there and then, out of the coin the
     * carrier holds — the colony's family price, or the two pact towns' middle price. What the purse cannot pay
     * for stays where it is. (Bought at home, from afar, it put coin into the other town's treasury with nobody
     * carrying it there.)
     */
    static void buyForHome(ServerLevel level, VillageFolkEntity f, Caravans.Trip t, Villages.Village there, Villages.Village home,
                           ItemStack s, double familyRate) {
        Market.Good g = Market.goodFor(s);
        double unit = g == null ? 0 : t.trade ? pactPrice(level, home.id(), there.id(), s.copyWithCount(1)) : g.value() * familyRate;
        int n = s.getCount();
        if (unit > 0) n = Math.min(n, (int) Math.floor(t.purse / unit));
        if (n < s.getCount()) Market.intoStores(level, there.id(), s.copyWithCount(s.getCount() - n));
        if (n <= 0) return;
        ItemStack left = f.insertGiven(s.copyWithCount(n));
        int moved = n - left.getCount();
        if (!left.isEmpty()) Market.intoStores(level, there.id(), left);
        int price = (int) Math.min(t.purse, Math.round(unit * moved));
        if (price <= 0) return;
        t.purse -= price;
        Ledger.addCoins(there.id(), price);
        Economy.sold(there.id(), price);
        Economy.spent(home.id(), price);
        Villages.tell(there.id(), level.getDayTime() / 24000L, Villages.name(home.id()) + "'s caravan bought " + moved + " "
            + (g == null ? "goods" : g.name().toLowerCase(Locale.ROOT)) + " off us for " + price + " coin, paid in person");
    }

    // ------------------------------------------------------------------ telling it

    /** The board's line: what the town is good at and short of, and its deals. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID id) {
        List<Deal> ds = deals(id);
        // Only where there is somebody to trade with: a town with no neighbour has no use for the line.
        if (ds.isEmpty() && Diplomacy.neighboursOf(id).isEmpty()) return null;
        TradeBook.Book b = TradeBook.of(level, id);
        if (ds.isEmpty() && b.surpluses().isEmpty() && b.shortages().isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Trade: ").append(TradeBook.summary(b));
        for (Deal d : ds) {
            if (sb.length() > 260) break;
            sb.append("; with ").append(Villages.name(d.other(id))).append(", ").append(dealWords(d, id)).append(" (")
                .append(d.made).append(" of ").append(d.due).append(" made)");
        }
        return sb.append('.').toString();
    }

    /** The gazette's Trade section: yesterday's deals and deliveries, and the deals standing. */
    public static String gazette(ServerLevel level, Villages.Village v, long day) {
        StringBuilder sb = new StringBuilder("§lTrade§r");
        int n = 0;
        for (String e : list(v.id(), "trade.log")) {
            String[] p = e.split("\\|", 2);
            if (p.length < 2) continue;
            long d;
            try { d = Long.parseLong(p[0]); } catch (NumberFormatException ex) { continue; }
            if (d != day - 1 || n >= 3) continue;
            sb.append('\n').append(TradeBook.capital(p[1])).append('.');
            n++;
        }
        List<Deal> ds = deals(v.id());
        if (n == 0) sb.append(ds.isEmpty() ? "\nNo deals with the neighbours." : "\nNo caravans yesterday.");
        for (Deal d : ds) {
            if (sb.length() > 600) break;
            sb.append("\nWith ").append(Villages.name(d.other(v.id()))).append(": ").append(dealWords(d, v.id())).append(" (")
                .append(d.made).append(" of ").append(d.due).append(" made).");
        }
        return sb.toString();
    }

    /** The trade page in words (/village trade): the book, the deals, the talks, the specialising. */
    public static List<String> page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        TradeBook.Book b = TradeBook.of(level, id);
        out.add("TRADE " + Villages.name(id) + " (" + Homeland.of(id).kind + "): " + TradeBook.summary(b) + ".");
        for (TradeBook.Ware w : TradeBook.Ware.values()) out.add("  " + TradeBook.line(b.get(w)));
        List<Deal> ds = deals(id);
        out.add(ds.isEmpty() ? "Deals: none standing." : "Deals:");
        for (Deal d : ds) {
            boolean s = d.sells(id);
            out.add("  with " + Villages.name(d.other(id)) + ": " + dealWords(d, id) + " (" + priceWords(d) + "); " + d.made + " of " + d.due + " deliveries made"
                + ", short " + (s ? d.sShort : d.bShort) + " (them " + (s ? d.bShort : d.sShort) + "); "
                + signed(s ? d.sGot : d.bGot) + " coin to us by our prices so far (" + signed(s ? d.sGain : d.bGain) + " a delivery expected)"
                + ((s ? d.sOwes : d.bOwes) > 0 ? "; we owe " + (s ? d.sOwes : d.bOwes) : "") + ((s ? d.bOwes : d.sOwes) > 0 ? "; they owe " + (s ? d.bOwes : d.sOwes) : "")
                + (d.onRoad ? "; a caravan on the road" : "; next due day " + d.next));
        }
        List<String> leans = new ArrayList<>();
        for (StationTask tr : new StationTask[]{ StationTask.FARM, StationTask.MINE, StationTask.WOOD, StationTask.RANCH, StationTask.FISH }) {
            double l = lean(id, tr);
            if (Math.abs(l - 1.0) >= 0.005) leans.add(tr.title.toLowerCase(Locale.ROOT) + "s ×" + num(l));
        }
        out.add("Specialising: " + (leans.isEmpty() ? "none — the usual shares" : String.join(", ", leans)) + ".");
        List<String> spec = list(id, "trade.spec");
        if (!spec.isEmpty()) {
            List<String> days = new ArrayList<>();
            for (int i = Math.min(spec.size(), 6) - 1; i >= 0; i--) {
                String[] p = spec.get(i).split(":");
                if (p.length >= 5) days.add("day " + p[0] + " " + p[3] + " farmers/" + p[4] + " miners");
            }
            out.add("  over the days: " + String.join("; ", days) + ".");
        }
        List<String> talks = list(id, "talks");
        out.add(talks.isEmpty() ? "Talks: none yet." : "Talks:");
        for (String t : talks) {
            String[] p = t.split("\\|", 3);
            out.add("  day " + p[0] + ": " + (p.length > 1 ? p[1] : "") + (p.length > 2 && !p[2].isEmpty() ? " (" + p[2] + ")" : ""));
        }
        for (String past : list(id, "deals.past")) out.add("  past: " + past);
        return out;
    }

    /** The trade page for the town's books (CityScreen's Trade tab). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        TradeBook.Book b = TradeBook.of(level, id);
        out.putString("summary", TradeBook.summary(b));
        out.putString("land", Homeland.of(id).kind);
        ListTag wares = new ListTag();
        for (TradeBook.Ware w : TradeBook.Ware.values()) {
            TradeBook.Entry e = b.get(w);
            CompoundTag c = new CompoundTag();
            c.putString("ware", w.word);
            c.putString("item", e.itemName());
            c.putInt("held", e.held());
            c.putInt("keep", e.keep());
            c.putInt("spare", e.spare());
            c.putInt("want", e.want());
            c.putFloat("made", (float) e.made());
            c.putFloat("used", (float) e.used());
            c.putFloat("days", (float) e.days());
            c.putFloat("land", (float) e.land());
            c.putString("landWords", TradeBook.landWords(e.land()));
            c.putFloat("price", (float) e.price());
            c.putFloat("worth", (float) e.worth());
            c.putString("status", e.status().name());
            wares.add(c);
        }
        out.put("wares", wares);
        ListTag deals = new ListTag();
        for (Deal d : deals(id)) {
            boolean s = d.sells(id);
            CompoundTag c = new CompoundTag();
            c.putString("partner", Villages.name(d.other(id)));
            c.putString("terms", dealWords(d, id) + " (" + priceWords(d) + ")");
            c.putInt("made", d.made);
            c.putInt("due", d.due);
            c.putInt("shortUs", s ? d.sShort : d.bShort);
            c.putInt("shortThem", s ? d.bShort : d.sShort);
            c.putInt("gained", (int) Math.round(s ? d.sGot : d.bGot));
            c.putInt("expected", (int) Math.round(s ? d.sGain : d.bGain));
            c.putInt("weOwe", s ? d.sOwes : d.bOwes);
            c.putInt("theyOwe", s ? d.bOwes : d.sOwes);
            c.putLong("ends", d.ends);
            c.putLong("next", d.next);
            c.putBoolean("road", d.onRoad);
            c.putInt("penalty", d.penalty);
            deals.add(c);
        }
        out.put("deals", deals);
        ListTag talks = new ListTag();
        for (String t : list(id, "talks")) {
            String[] p = t.split("\\|", 3);
            talks.add(StringTag.valueOf(clip("Day " + p[0] + ": " + (p.length > 1 ? p[1] : "") + (p.length > 2 && !p[2].isEmpty() ? " (" + p[2] + ")" : ""))));
        }
        out.put("talks", talks);
        ListTag past = new ListTag();
        for (String p : list(id, "deals.past")) past.add(StringTag.valueOf(clip(p)));
        out.put("past", past);
        ListTag logs = new ListTag();
        for (String p : list(id, "trade.log")) logs.add(StringTag.valueOf(clip("Day " + p.replaceFirst("\\|", ": "))));
        out.put("log", logs);
        // The specialising over the days, oldest first.
        List<String> spec = list(id, "trade.spec");
        int n = spec.size();
        int[] days = new int[n], farm = new int[n], mine = new int[n], farmers = new int[n], miners = new int[n];
        for (int i = 0; i < n; i++) {
            String[] p = spec.get(n - 1 - i).split(":");
            if (p.length < 5) continue;
            days[i] = (int) Annals.parse(p[0]);
            farm[i] = (int) Annals.parse(p[1]);
            mine[i] = (int) Annals.parse(p[2]);
            farmers[i] = (int) Annals.parse(p[3]);
            miners[i] = (int) Annals.parse(p[4]);
        }
        out.put("spec_days", new IntArrayTag(days));
        out.put("spec_farm", new IntArrayTag(farm));
        out.put("spec_mine", new IntArrayTag(mine));
        out.put("spec_farmers", new IntArrayTag(farmers));
        out.put("spec_miners", new IntArrayTag(miners));
        CompoundTag leans = new CompoundTag();
        for (StationTask tr : new StationTask[]{ StationTask.FARM, StationTask.MINE, StationTask.WOOD, StationTask.RANCH, StationTask.FISH }) {
            leans.putInt(tr.title.toLowerCase(Locale.ROOT), (int) Math.round(lean(id, tr) * 100));
        }
        out.put("lean", leans);
        return out;
    }

    private static String clip(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    // ------------------------------------------------------------------ for the commands and the tests

    /** The deal between these two in words for this town, or null. */
    @Nullable
    public static String words(UUID a, UUID b) {
        Deal d = deal(a, b);
        return d == null ? null : dealWords(d, a);
    }

    /** {agreed of the seller's goods, agreed of the buyer's, coin, made, due, short seller, short buyer}, or null. */
    @Nullable
    public static int[] numbersForTests(UUID a, UUID b) {
        Deal d = deal(a, b);
        return d == null ? null : new int[]{ d.qx, d.qy, d.coin, d.made, d.due, d.sShort, d.bShort };
    }

    /** {seller's gain so far, buyer's gain so far} by their own prices, or null. */
    @Nullable
    public static double[] gainsForTests(UUID a, UUID b) {
        Deal d = deal(a, b);
        return d == null ? null : new double[]{ d.sGot, d.bGot };
    }

    @Nullable
    public static UUID sellerForTests(UUID a, UUID b) {
        Deal d = deal(a, b);
        return d == null ? null : d.seller;
    }

    @Nullable
    public static Item[] goodsForTests(UUID a, UUID b) {
        Deal d = deal(a, b);
        return d == null ? null : new Item[]{ d.x, d.y };
    }

    /** Strike the deal this talk came to, now (the tests; the stage). */
    public static void strikeForTests(ServerLevel level, TradeTalks.Talk k) {
        strike(level, k, level.getDayTime() / 24000L, null);
    }

    /** Send the deal's next delivery now, whatever the day; returns the carrier, or null. */
    @Nullable
    public static VillageFolkEntity sendNowForTests(ServerLevel level, UUID a, UUID b) {
        Deal d = deal(a, b);
        if (d == null) return null;
        UUID from = d.sellerSends ? d.seller : d.buyer;
        if (!setOut(level, d, level.getDayTime() / 24000L)) return null;
        for (AssistantEntity x : Villages.folkOf(from)) {
            if (x instanceof VillageFolkEntity f && f.trip() != null && f.trip().deal != null && f.trip().to.equals(d.other(from))) return f;
        }
        return null;
    }

    /** Book deliveries as though they had come (each in the hundred of what was agreed), for the specialising. */
    public static void deliveredForTests(UUID a, UUID b, int sellerPct, int buyerPct) {
        Deal d = deal(a, b);
        if (d == null) return;
        recent(d, true, sellerPct / 100.0);
        recent(d, false, buyerPct / 100.0);
        d.made++;
        d.due++;
        save(d);
    }

    /** The town's morning of the trades' leans, now. */
    public static void leanForTests(ServerLevel level, Villages.Village v) {
        leanMorning(level, v, level.getDayTime() / 24000L);
    }

    public static void forgetLeanForTests(UUID village) {
        LEAN.remove(village);
        Ledger.note(village, "trade.lean", "");
    }

    /** Tests: the carrier's trip written down, taken out of its head as a restart does, and given back. */
    public static boolean restartTripForTests(ServerLevel level, VillageFolkEntity f) {
        return TradeTrips.restartForTests(level, f);
    }

    /** {coin in the carrier's purse, of it earned for goods}, or null with no trip. */
    @Nullable
    public static int[] purseForTests(VillageFolkEntity f) {
        Caravans.Trip t = f.trip();
        return t == null ? null : new int[]{ t.purse, t.earned };
    }

    // ------------------------------------------------------------------ the commands (/village trade)

    /** The neighbour the words name, or the nearest. */
    @Nullable
    static Villages.Village partner(UUID village, String text) {
        return Diplomacy.meant(village, text);
    }

    /** /village trade now: the two leaders bargain at once, without the walk; every round, and the deal if there is one. */
    public static List<String> talkNow(ServerLevel level, Villages.Village v, String text) {
        List<String> out = new ArrayList<>();
        Villages.Village o = partner(v.id(), text);
        if (o == null) { out.add("TRADE-NOW no neighbour near enough to trade with"); return out; }
        if (!Ledger.knowEachOther(v.id(), o.id())) Ledger.relate(v.id(), o.id(), 0);
        TradeTalks.Talk k = TradeTalks.negotiate(level, v.id(), o.id());
        out.add("TRADE-NOW " + Villages.name(v.id()) + " -> " + Villages.name(o.id()) + ": " + k.end().words
            + (k.table() == null ? "" : String.format(Locale.ROOT, " (room %.1f to %.1f; seller %s, buyer %s)", k.lo(), k.hi(),
            k.seller().temper().name().toLowerCase(Locale.ROOT), k.buyer().temper().name().toLowerCase(Locale.ROOT))));
        for (TradeTalks.Offer of : k.offers()) {
            out.add("  round " + of.round() + ", " + (of.byEnvoy() ? "the envoy" : "the host") + ": " + TradeTalks.paid(k.table(), of.terms())
                + String.format(Locale.ROOT, " (%.1f)", of.p()));
        }
        out.add("  " + TradeTalks.story(k));
        if (k.deal()) {
            strike(level, k, level.getDayTime() / 24000L, null);
            out.add("  DEAL " + TradeTalks.terms(k) + String.format(Locale.ROOT, "; the seller +%.1f, the buyer +%.1f a delivery, each by its own prices",
                k.gainSeller(), k.gainBuyer()));
        } else {
            noDeal(level, k, level.getDayTime() / 24000L, null);
        }
        return out;
    }

    /** /village trade talk: the elder sends an envoy to talk trade, now. */
    public static String sendEnvoy(ServerLevel level, Villages.Village v, String text) {
        Villages.Village o = partner(v.id(), text);
        if (o == null) return "TRADE-TALK no neighbour near enough to trade with";
        boolean sent = Envoys.send(level, v, o, Envoys.Errand.TRADE, level.getDayTime() / 24000L);
        return "TRADE-TALK " + (sent ? "an envoy set out from " + Villages.name(v.id()) + " for " + Villages.name(o.id())
            : "nobody free to go from " + Villages.name(v.id()));
    }

    /** /village trade deliver: the next delivery of each of the town's deals sets out now. */
    public static List<String> deliverNow(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Deal d : new ArrayList<>(deals(v.id()))) {
            UUID o = d.other(v.id());
            if (Caravans.between(d.seller, d.buyer)) { out.add("TRADE-DELIVER a caravan is on the road to " + Villages.name(o) + " already"); continue; }
            VillageFolkEntity c = sendNowForTests(level, d.seller, d.buyer);
            out.add("TRADE-DELIVER " + (c == null ? "no carrier free for " + Villages.name(o)
                : c.displayNameCap() + " set out for " + Villages.name(c.trip().to) + " " + c.blockPosition().toShortString()));
        }
        if (out.isEmpty()) out.add("TRADE-DELIVER no deals standing");
        return out;
    }

    // ------------------------------------------------------------------ the stage (operators, for the pictures)

    /** Who keeps the neighbour's heart awake for the stage (ChunkLoad). */
    private static final UUID STAGE = UUID.nameUUIDFromBytes("mca-trade-stage".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** The town here and its nearest neighbour, or null with why in {@code out}. */
    @Nullable
    private static Villages.Village[] stagePair(ServerLevel level, BlockPos at, List<String> out) {
        Villages.Village host = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (host == null) { out.add("TRADE-STAGE no town near"); return null; }
        List<Villages.Village> ns = Diplomacy.neighboursOf(host.id());
        if (ns.isEmpty()) { out.add("TRADE-STAGE no neighbour for " + Villages.name(host.id())); return null; }
        return new Villages.Village[]{ host, ns.get(0) };
    }

    /** The neighbour's envoy come to this town about trade, or null. */
    @Nullable
    private static VillageFolkEntity stageEnvoy(Villages.Village host, Villages.Village partner) {
        for (AssistantEntity a : Villages.folkOf(partner.id())) {
            if (a instanceof VillageFolkEntity f && f.trip() != null && f.trip().errand() == Envoys.Errand.TRADE && f.trip().to.equals(host.id())) return f;
        }
        return null;
    }

    /**
     * /village trade stage: the town here and its nearest neighbour made a pair worth trading. If neither has
     * anything the other is short of (two new hamlets), goods are set down for the pictures in chests at each
     * heart, as the game tests stock them: bread and wheat here, stone there. Then an envoy from the neighbour,
     * come to offer its stone, before this town's board, its leader out to hear it and the bell rung: the
     * bargaining is the audience's own (follow it with /village trade audience). Says where each stands.
     */
    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        Villages.Village[] pair = stagePair(level, at, out);
        if (pair == null) return out;
        Villages.Village host = pair[0], partner = pair[1];
        long day = level.getDayTime() / 24000L;
        out.add("TRADE-STAGE " + Villages.name(host.id()) + " hears " + Villages.name(partner.id()));
        // The neighbour's heart kept awake while it is looked at: its stores, its folk, its envoy.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) level.getChunk((partner.centre().getX() >> 4) + dx, (partner.centre().getZ() >> 4) + dz);
        }
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, STAGE, partner.centre(), 3, true);
        if (!Ledger.knowEachOther(host.id(), partner.id())) Ledger.relate(host.id(), partner.id(), 0);
        int r = Ledger.relation(host.id(), partner.id());
        if (r < Diplomacy.FRIENDLY - 15) Ledger.relate(host.id(), partner.id(), Diplomacy.FRIENDLY - 15 - r);
        if (!live(host.id(), partner.id()) && !TradeBook.complementary(level, host.id(), partner.id())) {
            List<ItemStack> food = new ArrayList<>(), stone = new ArrayList<>();
            for (int i = 0; i < 20; i++) food.add(new ItemStack(net.minecraft.world.item.Items.BREAD, 64));
            for (int i = 0; i < 5; i++) food.add(new ItemStack(net.minecraft.world.item.Items.WHEAT, 64));
            for (int i = 0; i < 50; i++) stone.add(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
            int a = setDown(level, host, food), b = setDown(level, partner, stone);
            out.add("STOCKED " + Villages.name(host.id()) + ": 1280 bread and 320 wheat in " + a + " chest(s); "
                + Villages.name(partner.id()) + ": 3200 cobblestone in " + b + " chest(s)");
        }
        Villages.forgetStock();
        for (UUID v : new UUID[]{ host.id(), partner.id() }) {
            TradeBook.forget(v);
            out.add("BOOK " + Villages.name(v) + ": " + TradeBook.summary(TradeBook.of(level, v)));
        }
        // The envoy, before this town's board.
        VillageFolkEntity envoy = stageEnvoy(host, partner);
        if (envoy == null && Envoys.send(level, partner, host, Envoys.Errand.TRADE, day)) envoy = stageEnvoy(host, partner);
        if (envoy == null) {
            out.add("ENVOY none: nobody free to go from " + Villages.name(partner.id()));
            return out;
        }
        if (Envoys.visiting(envoy) == null && !envoy.trip().homeward()) Caravans.arriveForTests(level, envoy);   // there, and asking to be heard
        BlockPos board = VillageBoards.lectern(host.id());
        net.minecraft.core.Direction facing = VillageBoards.facingOf(host.id());
        if (board == null) board = host.centre();
        if (facing == null) facing = net.minecraft.core.Direction.SOUTH;
        BlockPos stand = board.relative(facing, 4);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, stand.getX(), stand.getZ());
        envoy.moveTo(stand.getX() + 0.5, y, stand.getZ() + 0.5, facing.getOpposite().toYRot(), 0.0F);
        envoy.getNavigation().stop();
        out.add("BOARD " + board.getX() + " " + board.getY() + " " + board.getZ() + " facing " + facing.getName());
        VillageFolkEntity elder = Envoys.leader(host.id());
        if (elder != null) {
            BlockPos near = board.relative(facing, 2);
            int ey = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
            elder.moveTo(near.getX() + 0.5, ey, near.getZ() + 0.5, facing.toYRot(), 0.0F);
            elder.getNavigation().stop();
        }
        out.addAll(audience(level, at));
        return out;
    }

    /**
     * /village trade audience: how the audience before this town's board stands (the bell called, gathering, the
     * line reached), where the envoy and the leader stand, and the deal once it is shaken on. If nothing is under
     * way and the envoy is waiting, the bell is rung for it; another gathering under way is let finish first.
     */
    public static List<String> audience(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        Villages.Village[] pair = stagePair(level, at, out);
        if (pair == null) return out;
        Villages.Village host = pair[0], partner = pair[1];
        VillageFolkEntity envoy = stageEnvoy(host, partner);
        if (envoy != null && Envoys.visiting(envoy) != null && Assemblies.now(host.id()) == null) Assemblies.tick(level, host);
        boolean home = envoy != null && envoy.trip() != null && envoy.trip().homeward();
        out.add("AUDIENCE " + Assemblies.debug(host.id()) + (home ? "; the envoy is on its way home" : ""));
        if (envoy != null) {
            BlockPos e = envoy.blockPosition();
            out.add("ENVOY " + e.getX() + " " + e.getY() + " " + e.getZ() + " " + envoy.displayNameCap());
        }
        VillageFolkEntity elder = Envoys.leader(host.id());
        if (elder != null) {
            BlockPos p = elder.blockPosition();
            out.add("ELDER " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + elder.displayNameCap());
        }
        String w = words(host.id(), partner.id());
        if (w != null) out.add("DEAL " + w);
        return out;
    }

    /**
     * /village trade road [plan]: the deal's next delivery from this town sets out now (whichever town's turn it
     * was: for the picture, this one goes first) and is set down a third of the way to the neighbour. With
     * {@code plan}, only where that will be, so the camera can be there first.
     */
    public static List<String> road(ServerLevel level, BlockPos at, boolean plan) {
        List<String> out = new ArrayList<>();
        Villages.Village[] pair = stagePair(level, at, out);
        if (pair == null) return out;
        Villages.Village host = pair[0], partner = pair[1];
        List<BlockPos> way = Caravans.way(host, partner);
        if (way.isEmpty()) { out.add("CARAVAN none: no way between them"); return out; }
        BlockPos spot = way.get((int) Math.round((way.size() - 1) * 0.35)), ahead = way.get((int) Math.round((way.size() - 1) * 0.6));
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
        if (plan) {
            out.add("ROAD " + spot.getX() + " " + y + " " + spot.getZ());
            out.add("TOWARD " + ahead.getX() + " " + y + " " + ahead.getZ());
            return out;
        }
        Deal d = deal(host.id(), partner.id());
        if (d == null) { out.add("CARAVAN none: no deal between " + Villages.name(host.id()) + " and " + Villages.name(partner.id())); return out; }
        d.sellerSends = d.seller.equals(host.id());
        if (!setOut(level, d, level.getDayTime() / 24000L)) { out.add("CARAVAN none: nobody free to carry it"); return out; }
        VillageFolkEntity carrier = null;
        for (AssistantEntity a : Villages.folkOf(host.id())) {
            if (a instanceof VillageFolkEntity f && f.trip() != null && f.trip().deal != null && f.trip().to.equals(partner.id())) carrier = f;
        }
        if (carrier == null) { out.add("CARAVAN none: the carrier went astray"); return out; }
        Caravans.Trip t = carrier.trip();
        carrier.moveTo(spot.getX() + 0.5, y, spot.getZ() + 0.5, carrier.getYRot(), 0.0F);
        t.at = Math.max(t.at, t.way.indexOf(spot) + 1);
        Riding.bringAlong(carrier, level, spot.getX() + 0.5, y, spot.getZ() + 0.5);
        Item item = d.sends(host.id());
        out.add("CARAVAN " + spot.getX() + " " + y + " " + spot.getZ() + " " + carrier.displayNameCap() + " carrying "
            + (item == null ? "the coin" : carrier.countCarried(s -> s.is(item)) + " " + TradeTalks.name(item)));
        out.add("TOWARD " + ahead.getX() + " " + y + " " + ahead.getZ());
        out.add("DEAL " + words(host.id(), partner.id()));
        return out;
    }

    /** Goods set down for the pictures in chests of the town's stores round its heart. Returns how many chests. */
    private static int setDown(ServerLevel level, Villages.Village v, List<ItemStack> goods) {
        int n = 0, chests = 0;
        BlockPos c = v.centre();
        for (int r = 4; r <= 14 && n < goods.size(); r++) {
            for (int dx = -r; dx <= r && n < goods.size(); dx += 2) {
                for (int dz : new int[]{ -r, r }) {
                    if (n >= goods.size()) break;
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    BlockPos p = new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (Math.abs(p.getY() - c.getY()) > 6 || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()
                            || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                            || !level.getFluidState(p.below()).isEmpty()) continue;
                    level.setBlock(p, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
                    ZoneChests.mark(level, p);
                    if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container box)) continue;
                    for (int i = 0; i < box.getContainerSize() && n < goods.size(); i++) box.setItem(i, goods.get(n++));
                    box.setChanged();
                    chests++;
                }
            }
        }
        return chests;
    }

    /** A spot along a caravan's way: the waypoint this far along (0 to 1). */
    @Nullable
    static BlockPos along(Caravans.Trip t, double frac) {
        if (t.way.isEmpty()) return null;
        return t.way.get(Math.max(0, Math.min(t.way.size() - 1, (int) Math.round((t.way.size() - 1) * frac))));
    }
}
