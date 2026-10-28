package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.Accounts.Account;
import com.jrpetty.mcassistant.entity.Accounts.Dept;
import com.jrpetty.mcassistant.entity.Accounts.Kind;
import com.jrpetty.mcassistant.entity.Accounts.Purpose;
import com.jrpetty.mcassistant.entity.Accounts.Resolved;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-1a] The town's storehouse as its market: the one place every good in the town changes hands, at a price.
 *
 * <p><b>It owns what is in the stores, and deals with everybody.</b> Whatever a producer brings in (the farmer's wheat, the
 * miner's ore, the fisher's catch, the smelter's ingots) it buys, at its bid, and pays into the producer's account, its
 * business's till; whatever anybody takes out (a smith's iron, a builder's stone, a household's supper) it sells, at its
 * ask, charged to the taker's account (Accounts: whose account it is). Its prices are the town's going price (PriceIndex,
 * moved by what is short and what is plenty) less half its spread to buy, plus half to sell. The spread pays its staff
 * (the storekeepers and the couriers, who carry the producers' loads in: its wages are theirs), and narrows as its trade
 * grows: its staff's week over its week's trade, and two in the hundred, never under four nor over fifteen. Short of coin
 * it bids less (down to half, when its cash is under two days' buying), it never turns a producer away: what its cash will
 * not run to the treasury lends it (the market's lender of last resort), and what the treasury cannot it owes the producer
 * as store credit, paid first when coin comes in. Staple food, timber and stone it never buys under seven tenths of their
 * usual worth while it has coin. Its glut the passing traders buy (Market.trade, sellSurplus): the town's exports.
 *
 * <p><b>Who still takes free,</b> on purpose: the founders, until the town's Market Opening Day (the founders' commons:
 * free, but counted); a counter's sale (already paid at the counter: Stockroom.sold); a business's own stock (bonded in the
 * stores in its name, or made at its own bench: its books are its own); the market's own staff about its business; gifts
 * and theft. The town's own works, its watch, its army, its relief and its mint buy like anybody, out of the treasury, so
 * the cost of the town's public works shows in its books; in an emergency a department short of coin writes the market an
 * IOU (Accounts).
 *
 * <p><b>Market Opening Day.</b> The first morning, from the fourth day on, that the storehouse stands and has its keeper
 * (or the tenth day, with or without): the commons' stock becomes the market's, the treasury puts up its float (a third of
 * what it holds, or three days' buying), and every trade becomes a business: a young town's founders each get the title to
 * their field, holt, boat or claim and a little working capital; an old town's trades start as the town's own businesses,
 * sold to their own hands over a fortnight (Privatisation). From then on the town no longer turns its output into coin out
 * of nothing (Market.takings): output earns money only when the market buys it.
 *
 * <p>Live in a world being played; the other tests of the mod run in plain towns, where nothing is charged and nothing
 * changes, unless a test of this asks (liveForTests).
 */
public final class MarketMaker {

    private MarketMaker() {}

    /** The spread's floor, ceiling, and what is added to its staff's share of the trade. */
    static final double LEAST_SPREAD = 0.04, MOST_SPREAD = 0.15, SPREAD_OVER = 0.02;
    /** The least a staple fetches while the market has coin, of its usual worth. */
    static final double STAPLE_FLOOR = 0.7;
    /** The days before a town's market can open, and the day it opens with or without its storehouse. */
    static final long OPENS_FROM = 4, OPENS_BY = 10;
    /** A hand-back within this long is refunded at what was paid (no spread lost). */
    static final long REFUND_TICKS = 24000L;

    // ------------------------------------------------------------------ is it live

    private static volatile boolean liveForTests;
    /** The tests' plain towns (Villages.resetForTests sets it, as Identity's): no market unless a test of it asks. Its own
     *  flag, so a test that makes a town's identity live (Identity.liveForTests) does not open a market it never meant to. */
    private static volatile boolean plain;

    /** Is the market at work at all: in a world being played, or in a test of it. */
    public static boolean enabled() {
        return liveForTests || !plain;
    }

    /** Is this town's market open (past its Market Opening Day)? */
    public static boolean live(@Nullable UUID village) {
        return village != null && enabled() && openedDay(village) >= 0;
    }

    /** Before the town's Market Opening Day: the founders' commons. */
    public static boolean commons(@Nullable UUID village) {
        return village != null && enabled() && openedDay(village) < 0;
    }

    /** The day the town's market opened, or -1. */
    public static long openedDay(UUID village) {
        CompoundTag m = market(village);
        return m.contains("opened") ? m.getLong("opened") : -1;
    }

    static CompoundTag market(UUID village) {
        return Accounts.sub(Accounts.town(village), "market");
    }

    public static void liveForTests(boolean on) {
        liveForTests = on;
    }

    public static void resetForTests() {
        // A world opening or closing runs the tests' resets too (SessionReset): there the market stays at work.
        plain = !com.jrpetty.mcassistant.SessionReset.opening();
        liveForTests = false;
        RECENT.clear();
        PENDING.clear();
        PAID_NOW.clear();
        OUTPUT_NOW.clear();
        PRICE_FOR_TESTS.clear();
    }

    // ------------------------------------------------------------------ prices

    private static final Map<String, Double> PRICE_FOR_TESTS = new ConcurrentHashMap<>();

    /** Tests: the mid price of a ware fixed (null: as it goes). */
    public static void midForTests(ItemStack one, @Nullable Double each) {
        if (each == null) PRICE_FOR_TESTS.remove(Stockroom.key(one));
        else PRICE_FOR_TESTS.put(Stockroom.key(one), each);
    }

    /**
     * The going price of one of this, in coin: the town's price index (by supply and demand), at the town's price level. A
     * town pays its hands more than a hamlet (half as much again in a village, twice in a town: Wealth.standing), and its
     * goods fetch more in proportion, so a producer's day still pays its hand's day wherever it works.
     */
    public static double mid(ServerLevel level, UUID village, ItemStack one) {
        Double forced = PRICE_FOR_TESTS.get(Stockroom.key(one));
        if (forced != null) return forced;
        return Math.max(0.0001, PriceIndex.each(level, village, one.copyWithCount(1)) * priceLevel(village));
    }

    /** The town's price level: its standing over a hamlet's. */
    public static double priceLevel(UUID village) {
        return Wealth.standing(village) / 10.0;
    }

    /** The spread: its staff's wages over its week's trade, and two in the hundred; four in the hundred to fifteen. */
    public static double spread(UUID village) {
        long[] w = week(village);
        long turnover = w[0] + w[1];
        if (turnover <= 0) return MOST_SPREAD;
        double s = w[2] / (double) turnover + SPREAD_OVER;
        return Math.max(LEAST_SPREAD, Math.min(MOST_SPREAD, s));
    }

    /** Short of coin it bids less: its cash over two days' buying, a half at the least. */
    public static double cashFactor(UUID village) {
        long[] w = week(village);
        double perDay = w[0] / 7.0;
        if (perDay <= 0) return 1.0;
        return Math.max(0.5, Math.min(1.0, cash(village) / (2 * perDay)));
    }

    /** What the market asks for one of this, in coin. */
    public static double ask(ServerLevel level, UUID village, ItemStack one) {
        return mid(level, village, one) * (1 + spread(village) / 2);
    }

    /** What the market pays for one of this, in coin: never a staple under seven tenths of its usual worth while it has coin. */
    public static double bid(ServerLevel level, UUID village, ItemStack one) {
        double b = mid(level, village, one) * (1 - spread(village) / 2) * cashFactor(village);
        if (staple(one) && cash(village) > 0 && !PRICE_FOR_TESTS.containsKey(Stockroom.key(one))) {
            b = Math.max(b, STAPLE_FLOOR * PriceIndex.usual(one.copyWithCount(1)) * priceLevel(village));
        }
        return Math.min(b, ask(level, village, one));
    }

    /** Food, timber and stone: what the town lives and builds on. */
    static boolean staple(ItemStack one) {
        Economy.Kind k = Economy.kindOf(one);
        return k == Economy.Kind.FOOD || k == Economy.Kind.TIMBER || k == Economy.Kind.STONE;
    }

    // ------------------------------------------------------------------ its cash

    /** The storehouse's business: the market's books and its till (its cash). */
    @Nullable
    static Business house(UUID village) {
        Business b = Businesses.of(village, "storehouse");
        if (b == null) {
            net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                Businesses.ensure(server.overworld(), village);
                b = Businesses.of(village, "storehouse");
            }
        }
        return b;
    }

    /** The market's cash, pennies. */
    public static long cash(UUID village) {
        Business b = Businesses.of(village, "storehouse");
        return b == null ? 0 : b.till;
    }

    /** Coin into the market's cash (into the treasury where the town has no storehouse business yet). */
    public static void cashIn(UUID village, long p) {
        if (p <= 0) return;
        Business b = house(village);
        if (b == null) {
            Businesses.toTreasury(village, p);
            return;
        }
        b.till += p;
        Businesses.dirty();
    }

    /**
     * Coin out of the market's cash, so many pennies: what it is short of the treasury lends it (booked as the treasury's
     * float, repaid out of its surplus). Returns the pennies found; the caller owes the rest as store credit.
     */
    public static long cashOut(UUID village, long p) {
        if (p <= 0) return 0;
        Business b = house(village);
        if (b == null) return Businesses.fromTreasury(village, p);
        long got = Math.min(p, Math.max(0, b.till));
        b.till -= got;
        if (got < p) {
            long lent = Businesses.fromTreasury(village, p - got);
            if (lent > 0) Accounts.add(market(village), "advanced", lent);
            got += lent;
        }
        Businesses.dirty();
        return got;
    }

    /** Where the stores' coin goes when the market is not live: the treasury, as business-books built it. */
    static void inOrTreasury(UUID village, long p) {
        if (live(village)) cashIn(village, p); else Businesses.toTreasury(village, p);
    }

    static long outOrTreasury(UUID village, long p) {
        return live(village) ? cashOut(village, p) : Businesses.fromTreasury(village, p);
    }

    /** What the market owes producers as store credit, pennies. */
    public static long storeCredit(UUID village) {
        long n = 0;
        CompoundTag c = Accounts.sub(market(village), "credit");
        for (String k : c.getAllKeys()) n += c.getLong(k);
        return n;
    }

    /** The treasury's float lent to the market and not yet repaid, pennies. */
    public static long advanced(UUID village) {
        return market(village).getLong("advanced");
    }

    // ------------------------------------------------------------------ its day books

    /** Today's book, opened if need be: the week's totals rolled on. */
    static CompoundTag day(UUID village) {
        CompoundTag m = market(village);
        long day = Accounts.dayNow();
        if (m.getLong("day") != day || !m.contains("today", Tag.TAG_COMPOUND)) {
            if (m.contains("today", Tag.TAG_COMPOUND)) {
                CompoundTag was = m.getCompound("today");
                ListTag week = Accounts.list(m, "week");
                week.add(new net.minecraft.nbt.LongArrayTag(new long[]{ m.getLong("day"), was.getLong("bought"), was.getLong("sold"),
                    was.getLong("staff"), was.getLong("exports") }));
                while (week.size() > 7) week.remove(0);
            }
            m.putLong("day", day);
            m.put("today", new CompoundTag());
            Accounts.dirty();
        }
        return m.getCompound("today");
    }

    /** The last seven days' totals (yesterday back), with today's so far: {bought, sold, staff, exports}. */
    static long[] week(UUID village) {
        CompoundTag m = market(village);
        long[] out = new long[4];
        long day = Accounts.dayNow();
        for (Tag e : Accounts.list(m, "week")) {
            long[] a = ((net.minecraft.nbt.LongArrayTag) e).getAsLongArray();
            if (a.length < 5 || day - a[0] > 7) continue;
            for (int i = 0; i < 4; i++) out[i] += a[i + 1];
        }
        if (m.getLong("day") == day) {
            CompoundTag t = m.getCompound("today");
            out[0] += t.getLong("bought");
            out[1] += t.getLong("sold");
            out[2] += t.getLong("staff");
            out[3] += t.getLong("exports");
        }
        return out;
    }

    /** A trade in the day's book: so many of a ware bought or sold, for so many pennies. */
    static void book(UUID village, String key, long n, long p, boolean bought) {
        CompoundTag t = day(village);
        Accounts.add(t, bought ? "bought" : "sold", p);
        CompoundTag goods = Accounts.sub(t, "goods");
        long[] g = goods.contains(key) ? java.util.Arrays.copyOf(goods.getLongArray(key), 4) : new long[4];
        g[bought ? 0 : 2] += n;
        g[bought ? 1 : 3] += p;
        goods.putLongArray(key, g);
        CompoundTag all = Accounts.sub(market(village), "all");
        Accounts.add(all, bought ? "bought" : "sold", p);
        Accounts.dirty();
    }

    /** A tally of how takes were met today (charged, commons, free, own, straggler...). */
    static void tally(UUID village, String how, long n) {
        Accounts.add(Accounts.sub(day(village), "how"), how, n);
        Accounts.dirty();
    }

    /** Today's tallies, by how. */
    public static Map<String, Long> tallies(UUID village) {
        Map<String, Long> out = new java.util.TreeMap<>();
        CompoundTag h = Accounts.sub(day(village), "how");
        for (String k : h.getAllKeys()) out.put(k, h.getLong(k));
        return out;
    }

    // ------------------------------------------------------------------ the recent takes (for the hand-backs) and the counters

    /** One take, for a hand-back: so many left to give back, what each cost (pennies), who paid, when. */
    static final class Take {
        long qty;
        final double each;
        final Account account;
        final long tick;

        Take(long qty, double each, Account account, long tick) {
            this.qty = qty;
            this.each = each;
            this.account = account;
            this.tick = tick;
        }
    }

    /** By "village|hand-or-account|ware": the takes of the last day, oldest first. */
    private static final Map<String, ArrayDeque<Take>> RECENT = new HashMap<>();
    /** This tick's charged takes, by "village|ware", for a counter's sale in the same tick to reverse. */
    private static final Map<String, List<Take>> PENDING = new HashMap<>();
    private static long pendingTick = -1;
    /** This tick's counter sales not yet matched by a take, by "village|ware". */
    private static final Map<String, Long> PAID_NOW = new HashMap<>();
    /** This tick's output bought from producers, by "village|ware": the same goods given into the stores are not bought twice. */
    private static final Map<String, Long> OUTPUT_NOW = new HashMap<>();

    private static void freshTick(ServerLevel level) {
        long now = level.getGameTime();
        if (now != pendingTick) {
            pendingTick = now;
            PENDING.clear();
            PAID_NOW.clear();
            OUTPUT_NOW.clear();
            if (RECENT.size() > 4096) RECENT.values().removeIf(q -> q.isEmpty() || now - q.peekLast().tick > REFUND_TICKS);
        }
    }

    private static void remember(String key, Take t) {
        ArrayDeque<Take> q = RECENT.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.add(t);
        while (q.size() > 32) q.poll();
    }

    /** Give back up to {@code n} against the takes kept under this key: refunded at what was paid. Returns how many. */
    private static long refund(ServerLevel level, UUID village, String key, long n, String what) {
        ArrayDeque<Take> q = RECENT.get(key);
        if (q == null || q.isEmpty() || n <= 0) return 0;
        long now = level.getGameTime();
        long done = 0, refunded = 0;
        Iterator<Take> it = q.iterator();
        while (it.hasNext() && done < n) {
            Take t = it.next();
            if (now - t.tick > REFUND_TICKS || t.qty <= 0) {
                it.remove();
                continue;
            }
            long k = Math.min(t.qty, n - done);
            t.qty -= k;
            done += k;
            long p = Math.round(t.each * k);
            if (p > 0 && !t.account.market()) {
                long got = cashOut(village, p);
                Accounts.credit(level, village, t.account, got, what);
                if (got < p) owe(village, t.account, p - got);
                refundBooks(level, village, t.account, p);
                refunded += p;
            }
            if (t.qty <= 0) it.remove();
        }
        if (done > 0) {
            book(village, key.substring(key.lastIndexOf('|') + 1), -done, -refunded, false);
            tally(village, "handed back", done);
        }
        return done;
    }

    /** A refund in a business's books: its purchase undone. */
    private static void refundBooks(ServerLevel level, UUID village, Account a, long p) {
        if (a.kind() != Kind.FIRM) return;
        Business b = Businesses.of(village, a.key());
        if (b == null) return;
        Business.Day d = b.today(Businesses.today(level));
        d.other -= p;
        b.allTime.other -= p;
        Businesses.dirty();
    }

    // ------------------------------------------------------------------ the choke points

    /**
     * Goods out of the stores (TownWork.takeFromStores, Crafts.takeOne, drawFrom, the withdrawal at the chest, the caravans,
     * the workshop's fitting, the armoury): sold to whoever's account it is, at the ask. The founders' commons, a counter's
     * sale, a business's own stock and the market's own staff take free; nobody's account is a straggler.
     */
    public static void taken(ServerLevel level, @Nullable UUID village, ItemStack st, int k) {
        if (village == null || k <= 0 || st.isEmpty() || !enabled()) return;
        freshTick(level);
        Resolved r = Accounts.resolve(village, st);
        String key = Stockroom.key(st);
        if (r.account().kind() == Kind.UNATTRIBUTED) Accounts.straggler(level, village, key, k);
        else if (r.system()) Accounts.system(level, village, key, k);
        if (!live(village)) {
            tally(village, "commons", k);
            Accounts.add(Accounts.sub(market(village), "commonsAll"), key, k);
            return;
        }
        if (r.account().kind() == Kind.FREE) {
            tally(village, "free: " + r.account().key(), k);
            return;
        }
        if (r.account().market() || r.account().kind() == Kind.COMMONS) {
            tally(village, "the market's own", k);
            return;
        }
        // Already paid at a counter this tick.
        String now = village + "|" + key;
        Long paid = PAID_NOW.get(now);
        if (paid != null && paid > 0) {
            long m = Math.min(paid, k);
            PAID_NOW.put(now, paid - m);
            k -= (int) m;
            tally(village, "free: paid at a counter", m);
            if (k <= 0) return;
        }
        // A business's own stock, bonded in the stores in its name.
        if (r.account().kind() == Kind.FIRM) {
            Business b = Businesses.of(village, r.account().key());
            if (b != null && !b.town()) {
                Business.Lot lot = b.stock.get(key);
                if (lot != null && lot.qty >= k) {
                    tally(village, "free: its own stock", k);
                    return;
                }
            }
        }
        double each = ask(level, village, st);
        long price = Math.max(1, Math.round(each * 100 * k));
        String what = k + " " + Businesses.nameOf(key).toLowerCase(Locale.ROOT);
        long got = Accounts.debit(level, village, r.account(), price, r.purpose(), what);
        cashIn(village, got);
        book(village, key, k, price, false);
        tally(village, "charged to " + kindWords(r.account()), k);
        booksOfBuyer(level, village, r.account(), key, k, price);
        // One record of the take, for a counter's sale this tick to reverse and a hand-back within the day to refund: the
        // same record, so what one gives back the other cannot give back again.
        Take t = new Take(k, price / (double) k, r.account(), level.getGameTime());
        PENDING.computeIfAbsent(now, x -> new ArrayList<>()).add(t);
        VillageFolkEntity hand = Accounts.actor();
        if (hand != null) remember(village + "|" + hand.getUUID() + "|" + key, t);
        else remember(village + "|" + r.account().id() + "|" + key, t);
    }

    /**
     * What a maker took out of its own workshop chest (WorkStock), which the couriers stocked from the storehouse: a
     * business's own (its making books it), but a maker of the town's own with no business yet buys it of the market there
     * and then, on the works account, as it would at the stores.
     */
    public static void fromWorkshop(ServerLevel level, @Nullable UUID village, List<ItemStack> own) {
        if (village == null || own.isEmpty() || !live(village)) return;
        for (ItemStack s : own) {
            if (s.isEmpty()) continue;
            Resolved r = Accounts.resolve(village, s);
            if (r.account().kind() == Kind.DEPT && !r.account().market() && !r.system()) taken(level, village, s, s.getCount());
        }
    }

    static String kindWords(Account a) {
        return switch (a.kind()) {
            case FIRM -> "a business";
            case FOLK -> "a household";
            case DEPT -> a.deptOf() == null ? "the town" : a.deptOf().words;
            default -> a.kind().name().toLowerCase(Locale.ROOT);
        };
    }

    /** A business's purchase in its books: its running costs, and a line in its order book from the storehouse. */
    static void booksOfBuyer(ServerLevel level, UUID village, Account a, String key, long n, long p) {
        if (a.kind() != Kind.FIRM) return;
        Business b = Businesses.of(village, a.key());
        if (b == null) return;
        long day = Businesses.today(level);
        Business.Day d = b.today(day);
        d.other += p;
        b.allTime.other += p;
        // (Not in its lines' use: that is what its own reordering of bonded stock goes by, and this was bought and used.)
        // One line an order: the same ware the same day folded into it, so the book shows the day's buying, not every pick.
        Business.Order last = b.orders.isEmpty() ? null : b.orders.get(b.orders.size() - 1);
        if (last != null && last.day() == day && last.item().equals(key) && last.supplier().equals(FROM)) {
            long qty = last.qty() + n, total = last.total() + p;
            b.orders.set(b.orders.size() - 1, new Business.Order(day, FROM, key, qty, Math.round(total * 100.0 / qty), 0, total));
        } else {
            b.orders.add(new Business.Order(day, FROM, key, n, Math.round(p * 100.0 / n), 0, p));
            while (b.orders.size() > 40) b.orders.remove(0);
        }
        Businesses.dirty();
    }

    /** The supplier's name in a business's order book for what it bought at the storehouse's ask. */
    public static final String FROM = "the storehouse (market)";

    /**
     * A counter's sale (Stockroom.sold): the buyer has paid at the counter, so a take of the same ware this tick is reversed
     * (its charge refunded), and one still to come this tick is free. One hook for every counter in the town.
     */
    public static void paidAtCounter(ServerLevel level, @Nullable UUID village, ItemStack what, int n) {
        if (village == null || n <= 0 || what.isEmpty() || !live(village)) return;
        freshTick(level);
        String now = village + "|" + Stockroom.key(what);
        long left = n;
        List<Take> takes = PENDING.get(now);
        if (takes != null) {
            for (Take t : takes) {
                if (left <= 0) break;
                long k = Math.min(t.qty, left);
                if (k <= 0) continue;
                t.qty -= k;
                left -= k;
                long p = Math.round(t.each * k);
                long got = cashOut(village, p);
                Accounts.credit(level, village, t.account, got, "a counter's sale");
                if (got < p) owe(village, t.account, p - got);
                refundBooks(level, village, t.account, p);
                if (t.account.kind() == Kind.UNATTRIBUTED) Accounts.unstraggle(level, village);
                book(village, Stockroom.key(what), -k, -p, false);
                tally(village, "free: paid at a counter", k);
            }
        }
        if (left > 0) PAID_NOW.merge(now, left, Long::sum);
    }

    /** The market owes an account (a refund it could not find the coin for): store credit. */
    static void owe(UUID village, Account a, long p) {
        if (p <= 0) return;
        Accounts.add(Accounts.sub(market(village), "credit"), a.id(), p);
        Accounts.dirty();
    }

    /**
     * Goods into the stores by any other way than a producer's own (TownWork.give, Market.intoStores, a hand-back by a
     * system): a hand-back of what was taken within the day refunded at what was paid; the town's own output (its works,
     * a maker of the town's own with no business yet) bought at the bid; anything else is the market's (a stray, a gift).
     */
    public static void given(ServerLevel level, @Nullable UUID village, ItemStack st, int k) {
        if (village == null || k <= 0 || st.isEmpty() || !enabled()) return;
        if (!live(village)) {
            tally(village, "commons in", k);
            return;
        }
        freshTick(level);
        Resolved r = Accounts.resolve(village, st);
        if (r.account().kind() == Kind.FREE || r.account().market() || r.account().kind() == Kind.COMMONS) return;
        String key = Stockroom.key(st);
        // The producer's output bought this tick, now going into the stores: not bought twice.
        String now = village + "|" + key;
        Long out = OUTPUT_NOW.get(now);
        if (out != null && out > 0) {
            long m = Math.min(out, k);
            OUTPUT_NOW.put(now, out - m);
            k -= (int) m;
            if (k <= 0) return;
        }
        String what = Businesses.nameOf(key).toLowerCase(Locale.ROOT) + " handed back";
        VillageFolkEntity hand = Accounts.actor();
        long back = 0;
        if (hand != null) back += refund(level, village, village + "|" + hand.getUUID() + "|" + key, k, what);
        if (back < k) back += refund(level, village, village + "|" + r.account().id() + "|" + key, k - back, what);
        long rest = k - back;
        if (rest <= 0) return;
        if (r.system()) {
            // The towns' own round with no scope of its own (a system tidying, consolidating, putting back): nobody's
            // output to buy; the goods are the market's.
            tally(village, "given in by the town's systems", rest);
        } else if (r.account().kind() == Kind.DEPT) {
            buy(level, village, r.account(), st, rest, "the town's own");
        } else if (r.account().kind() == Kind.UNATTRIBUTED) {
            tally(village, "given in by nobody", rest);
        } else {
            tally(village, "given in", rest);
        }
    }

    /**
     * A producer's hand brings its load in (DepositGoal, the courier's hand-over at a far field: before Economy.produced):
     * what it took out of the stores in the last day and brings back is refunded to whoever paid. Returns how many.
     */
    public static long deposited(VillageFolkEntity hand, ItemStack st, int n) {
        UUID village = hand.ownerId();
        if (village == null || n <= 0 || st.isEmpty() || !live(village) || !(hand.level() instanceof ServerLevel level)) return 0;
        freshTick(level);
        String key = Stockroom.key(st);
        long back = refund(level, village, village + "|" + hand.getUUID() + "|" + key, n, Businesses.nameOf(key).toLowerCase(Locale.ROOT) + " handed back");
        if (back > 0) {
            BACK_HAND = hand.getUUID();
            BACK_KEY = key;
            BACK_N = back;
            BACK_TICK = level.getGameTime();
        }
        return back;
    }

    /** The hand-back just refunded (deposited), for the output that follows it in the same tick to leave out. */
    @Nullable private static UUID BACK_HAND;
    private static String BACK_KEY = "";
    private static long BACK_N, BACK_TICK = -1;

    /**
     * A producer's fresh output, as Economy.produced recognises it (its trade's own work, not what it was given): bought by
     * the market at the bid and paid into the producer's business (its own, or the town's for a town-run trade). A maker
     * with a business of its own is paid by its own books (Businesses.toStores) and is left out here.
     */
    public static void output(VillageFolkEntity f, StationTask trade, ItemStack s) {
        UUID village = f.ownerId();
        if (village == null || s.isEmpty() || !live(village) || !(f.level() instanceof ServerLevel level)) return;
        freshTick(level);
        String key = Stockroom.key(s);
        long n = s.getCount();
        if (f.getUUID().equals(BACK_HAND) && key.equals(BACK_KEY) && BACK_TICK == level.getGameTime()) {
            long m = Math.min(n, BACK_N);
            BACK_N -= m;
            n -= m;
        }
        if (n <= 0) return;
        Business firm = Businesses.employer(village, f);
        if (firm != null && Businesses.sellsToStores(firm.type)) return;              // its business's making, booked there
        Account seller = firm != null && !firm.mirror() && !firm.id.equals("storehouse") ? Account.firm(firm) : Account.dept(Dept.WORKS);
        buy(level, village, seller, s, n, "brought in");
        OUTPUT_NOW.merge(village + "|" + key, n, Long::sum);
    }

    /**
     * The market buys so many of this from an account at its bid: out of its cash (the treasury lending it what it is
     * short of, store credit past that), into the seller's account; the stores' own books take the goods in at what they
     * cost the market, and the seller's books the sale.
     */
    static long buy(ServerLevel level, UUID village, Account seller, ItemStack st, long n, String how) {
        String key = Stockroom.key(st);
        double each = bid(level, village, st);
        long price = Math.max(0, Math.round(each * 100 * n));
        if (price <= 0) {
            tally(village, "worth nothing to the market", n);
            return 0;
        }
        long got = cashOut(village, price);
        Accounts.credit(level, village, seller, got, how);
        if (got < price) owe(village, seller, price - got);
        book(village, key, n, price, true);
        Businesses.storesIn(village, st.copyWithCount(1), n, price);
        tally(village, "bought of " + kindWords(seller), n);
        if (seller.kind() == Kind.FIRM) {
            Business b = Businesses.of(village, seller.key());
            if (b != null) {
                long day = Businesses.today(level);
                Business.Day d = b.today(day);
                d.revenue += price;
                d.toTown += price;
                Business.Line l = d.line(key);
                l.units += n;
                l.made += n;
                l.revenue += price;
                b.allTime.revenue += price;
                b.allTime.toTown += price;
                Business.Line all = b.allTime.line(key);
                all.units += n;
                all.made += n;
                all.revenue += price;
                Businesses.dirty();
            }
        }
        Business house = house(village);
        if (house != null) {
            long day = Businesses.today(level);
            house.today(day).bought += price;
            house.allTime.bought += price;
            Business.Order last = house.orders.isEmpty() ? null : house.orders.get(house.orders.size() - 1);
            String from = seller.words(village);
            if (last != null && last.day() == day && last.item().equals(key) && last.supplier().equals(from)) {
                long qty = last.qty() + n, total = last.total() + price;
                house.orders.set(house.orders.size() - 1, new Business.Order(day, from, key, qty, Math.round(total * 100.0 / qty), 0, total));
            } else {
                house.orders.add(new Business.Order(day, from, key, n, Math.round(price * 100.0 / n), 0, price));
                while (house.orders.size() > 40) house.orders.remove(0);
            }
        }
        return price;
    }

    // ------------------------------------------------------------------ the market's staff

    /**
     * Payday (Market.payWages, for each hand before the treasury pays it): the storekeepers and the couriers are the
     * market's staff, paid out of its cash as far as its spread has earned it this week (the treasury pays the rest, as
     * the town's own staff), the town's wage tax off as anybody's. Returns the coins paid, or -1.
     */
    public static int paysHand(ServerLevel level, Villages.Village v, VillageFolkEntity f, int wage) {
        if (!live(v.id()) || wage <= 0) return -1;
        StationTask t = f.stationTask();
        if (t != StationTask.STORE && t != StationTask.HAUL) return -1;
        // Out of what its spread has earned this week: what it has not earned, the treasury pays them, as the town's own.
        long[] w = week(v.id());
        long earned = Math.round((w[0] + w[1]) * spread(v.id()) / 2);
        if (w[2] + wage * 100L > earned) return -1;
        int tax = Market.taxOn(f, wage);
        long got = cashOut(v.id(), wage * 100L);
        int paid = (int) (got / 100);
        if (got % 100 > 0) cashIn(v.id(), got % 100);
        int taxed = Math.min(tax, paid);
        if (taxed > 0) {
            Businesses.toTreasury(v.id(), taxed * 100L);
            Accounts.taxIn(v.id(), taxed * 100L);
        }
        f.paid(paid - taxed);
        Purchases.payday(level, f);
        Accounts.payday(level, f);
        Accounts.add(day(v.id()), "staff", paid * 100L);
        Accounts.dirty();
        return paid - taxed;
    }

    // ------------------------------------------------------------------ exports

    /** The passing traders paid for the market's glut (Market.trade, sellSurplus): into its cash, booked as its exports. */
    public static void exported(UUID village, int coins) {
        if (coins <= 0) return;
        cashIn(village, coins * 100L);
        Accounts.add(day(village), "exports", coins * 100L);
        Accounts.add(day(village), "sold", coins * 100L);
        Accounts.dirty();
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The market's morning (Market.tick, before the businesses'): Market Opening Day when it comes; then store credit paid
     * out of its cash, the businesses' overdrafts settled, its week's surplus over its float to the treasury, and the
     * privatisation programme's day.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!enabled() || TownLod.away(id)) return;
        if (openedDay(id) < 0) {
            if (mayOpen(level, v, day)) open(level, v, day);
            return;
        }
        day(id);
        // The treasury's float lent to the market and the town's IOUs to it, set against each other (both the town's).
        long adv = advanced(id);
        if (adv > 0) {
            long netted = Accounts.netDebts(id, adv);
            if (netted > 0) market(id).putLong("advanced", adv - netted);
        }
        payCredit(level, id);
        for (Business b : Businesses.all(id)) Accounts.settleFirm(level, id, b);
        long opened = openedDay(id);
        if (day > opened && (day - opened) % 7 == 0) surplus(level, id, day);
        Privatisation.morning(level, v, day);
    }

    /** May the market open this morning: from the fourth day, the storehouse standing with its keeper; or the tenth. */
    static boolean mayOpen(ServerLevel level, Villages.Village v, long day) {
        long age = day - foundedDay(v.id(), day);
        if (age < OPENS_FROM) return false;
        if (age >= OPENS_BY) return true;
        if (Storehouses.storeFor(level, v.id()) == null) return false;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.STORE) return true;
        return false;
    }

    /** The day the town was founded (its chronicle's first day; else the day the market first saw it), kept in its notes. */
    static long foundedDay(UUID village, long day) {
        CompoundTag m = market(village);
        if (!m.contains("first")) {
            long f = FoundingDay.founded(village);
            m.putLong("first", f >= 0 && f <= day ? f : day);
            Accounts.dirty();
        }
        return m.getLong("first");
    }

    /**
     * Market Opening Day: the commons' stock the market's own, the treasury's float put up, every trade a business (a young
     * town's founders their homesteads; an old town's trades the town's, to be sold to their hands), and the town told.
     */
    static void open(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag m = market(id);
        m.putLong("opened", day);
        long young = day - foundedDay(id, day);
        Businesses.ensure(level, id);
        // The float: a third of the treasury, or three days' buying (what its producers make in a day at their wages).
        long expect = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && Privatisation.producer(f.stationTask())) expect += Wealth.wage(f) * 130L;
        }
        long want = Math.max(Businesses.treasuryPence(id) / 3, 3 * expect);
        long put = Businesses.fromTreasury(id, Math.min(want, Businesses.treasuryPence(id) * 2 / 3));
        cashIn(id, put);
        m.putLong("float", put);
        int commons = 0;
        CompoundTag c = Accounts.sub(m, "commonsAll");
        for (String k : c.getAllKeys()) commons += (int) c.getLong(k);
        Accounts.dirty();
        Villages.tell(id, day, "Market Opening Day: the storehouse opened as the town's market, buying what every producer brings and selling to all;"
            + " the treasury put up " + Businesses.pence(put) + " to start it" + (commons > 0 ? ", and the commons' stock, " + commons
            + " things taken free since the founding, is the market's now" : ""));
        if (young <= OPENS_BY) Privatisation.homesteads(level, v, day);
        else Privatisation.begin(level, v, day);
    }

    /** Store credit paid out of the market's cash over a day's float, oldest owed first. */
    static void payCredit(ServerLevel level, UUID village) {
        CompoundTag c = Accounts.sub(market(village), "credit");
        if (c.isEmpty()) return;
        long spare = cash(village) - Math.max(1000, week(village)[0] / 7);
        for (String k : new ArrayList<>(c.getAllKeys())) {
            if (spare <= 0) break;
            long owed = c.getLong(k);
            long pay = Math.min(owed, spare);
            Account a = accountOf(k);
            if (a == null) {
                c.remove(k);
                continue;
            }
            long got = cashOut(village, pay);
            Accounts.credit(level, village, a, got, "store credit paid");
            spare -= got;
            if (owed - got <= 0) c.remove(k); else c.putLong(k, owed - got);
        }
        Accounts.dirty();
    }

    /** An account from the books' key ("firm:cafe", "folk:<uuid>", "dept:WORKS"). */
    @Nullable
    static Account accountOf(String id) {
        int i = id.indexOf(':');
        if (i < 0) return null;
        String kind = id.substring(0, i), key = id.substring(i + 1);
        try {
            return switch (kind) {
                case "firm" -> new Account(Kind.FIRM, null, key);
                case "folk" -> new Account(Kind.FOLK, UUID.fromString(key), "");
                case "dept" -> new Account(Kind.DEPT, null, key);
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The week's surplus: what the market holds over its float (three days' buying, the store credit it owes and the
     * treasury's float it was lent first repaid) goes to the treasury, a public enterprise's profit.
     */
    static void surplus(ServerLevel level, UUID village, long day) {
        long[] w = week(village);
        long keep = Math.max(1000, w[0] * 3 / 7) + storeCredit(village);
        long over = cash(village) - keep;
        if (over <= 0) return;
        Business house = house(village);
        if (house == null) return;
        // The town owes its market on its IOUs, and the market owes the town its surplus: the one set against the other
        // first (both are the town's), and only what is over paid in coin.
        long netted = Accounts.netDebts(village, over);
        if (netted > 0) {
            Villages.tell(village, day, "the market set " + Businesses.pence(netted) + " of its surplus against what the town owed it on its IOUs");
            over -= netted;
            Accounts.add(Accounts.sub(market(village), "all"), "netted", netted);
        }
        if (over <= 0) return;
        house.till -= over;
        Businesses.toTreasury(village, over);
        CompoundTag m = market(village);
        long adv = m.getLong("advanced");
        m.putLong("advanced", Math.max(0, adv - over));
        Accounts.add(Accounts.sub(m, "all"), "surplus", over);
        Accounts.dirty();
        Businesses.dirty();
        Villages.tell(village, day, "the market paid the treasury its week's surplus, " + Businesses.pence(over)
            + (adv > 0 ? " (" + Businesses.pence(Math.min(adv, over)) + " of it repaying the float the treasury lent it)" : ""));
    }

    // ------------------------------------------------------------------ the producers' viability

    /** One producing trade's day at the market's prices: its staple, how many a hand brings in, the bid less its inputs,
     *  its day's takings, the going wage, and the takings over the wage. */
    public record Viable(StationTask trade, String item, double perDay, double bid, double inputs, double day, int wage, double ratio) {
        public String words() {
            return String.format(Locale.ROOT, "%-6s %-14s %5.0f a day at %s (inputs %s): %s a day against a wage of %d, %.2fx",
                trade.name(), item, perDay, Businesses.coins(bid), Businesses.coins(inputs), Businesses.coins(day), wage, ratio);
        }
    }

    /**
     * Can every producing trade pay its hand's wage at the market's prices? For each, its staple: what a hand brings in a day
     * (CostBook: a farmer some ninety-six wheat, a miner a couple of hundred cobble or two dozen raw iron), at the market's
     * bid, less what its work uses up at the ask (the smelter's ore and coal), against the trade's going wage here. Read
     * before the market charges anybody: no producing trade may be unable to pay its wages at fair prices.
     */
    public static List<Viable> viability(ServerLevel level, UUID village) {
        List<Viable> out = new ArrayList<>();
        Object[][] rows = {
            { StationTask.FARM, Items.WHEAT }, { StationTask.FARM, Items.CARROT }, { StationTask.WOOD, Items.OAK_LOG },
            { StationTask.MINE, Items.COBBLESTONE }, { StationTask.MINE, Items.RAW_IRON }, { StationTask.MINE, Items.COAL },
            { StationTask.FISH, Items.COD }, { StationTask.RANCH, Items.WHITE_WOOL }, { StationTask.HUNT, Items.BEEF },
            { StationTask.SMELT, Items.IRON_INGOT } };
        for (Object[] r : rows) {
            StationTask t = (StationTask) r[0];
            net.minecraft.world.item.Item it = (net.minecraft.world.item.Item) r[1];
            ItemStack one = new ItemStack(it);
            double perDay;
            double inputs = 0;
            if (t == StationTask.SMELT) {
                // A furnace a hand keeps going: an ingot of a raw iron and an eighth of a coal.
                perDay = CostBook.SMELTS_A_DAY / 2;
                inputs = ask(level, village, new ItemStack(Items.RAW_IRON)) + ask(level, village, new ItemStack(Items.COAL)) / 8;
            } else {
                perDay = CostBook.perDay(it);
            }
            double b = bid(level, village, one);
            double day = perDay * (b - inputs);
            int wage = Math.max(1, JobWorth.rate(t, village));
            out.add(new Viable(t, Businesses.nameOf(Stockroom.key(one)), perDay, b, inputs, day, wage, day / wage));
        }
        return out;
    }

    // ------------------------------------------------------------------ the mint

    /**
     * The town mints gold it has bought (Market.mint): the mint buys the ingot at the market's ask out of the treasury,
     * and strikes nine coins of it into the treasury. Returns the pennies it paid the market.
     */
    public static long mintBuys(ServerLevel level, UUID village, int bars) {
        if (bars <= 0 || !live(village)) return 0;
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        long p = Math.round(ask(level, village, gold) * 100 * bars);
        long got = Accounts.debit(level, village, Account.dept(Dept.MINT), p, Purpose.WORK, bars + " gold ingot" + (bars == 1 ? "" : "s"));
        cashIn(village, got);
        book(village, Stockroom.key(gold), bars, p, false);
        return got;
    }

    /** What the mint gives for a gold ingot brought to it, and keeps: nine coins struck, one the mint's (seigniorage). */
    public static final int STRUCK = Market.COINS_PER_GOLD, SEIGNIORAGE = 1;

    /**
     * Free coinage: anybody (a player at the town's counter) brings gold and has it struck into coin, nine to the ingot,
     * the mint keeping one. The ingots are the mint's (into the stores, the market's stock at what the mint paid); the
     * coin struck is new money, booked as the mint's. Returns the coins handed over.
     */
    public static int strike(ServerLevel level, UUID village, int ingots) {
        if (ingots <= 0) return 0;
        int coins = ingots * (STRUCK - SEIGNIORAGE);
        Businesses.toTreasury(village, ingots * SEIGNIORAGE * 100L);
        Accounts.deptBook(village, Dept.MINT, 0, ingots * SEIGNIORAGE * 100L);
        CoinCensus.flow(village, CoinCensus.Flow.MINT, (long) ingots * STRUCK);
        CoinCensus.flow(village, CoinCensus.Flow.PLAYER_OUT, coins);
        Accounts.add(Accounts.sub(market(village), "all"), "struck", (long) ingots * STRUCK);
        Accounts.dirty();
        return coins;
    }

    /**
     * A player brings gold to the town's counter: the mint (in the hall, from the Stone Age) strikes it, eight coins to the
     * ingot for the player and one for the mint; the ingots are the mint's bullion, sold to the market at its bid.
     */
    public static String mintForPlayer(ServerLevel level, Villages.Village v, net.minecraft.server.level.ServerPlayer p) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) {
            return "There's no mint in " + Villages.name(id) + " yet: the hall strikes coin from the Stone Age on.";
        }
        ItemStack hand = p.getMainHandItem();
        int n = hand.getCount();
        if (n <= 0 || !hand.is(Items.GOLD_INGOT)) return "Bring gold ingots to have them struck.";
        ItemStack bars = hand.copyWithCount(n);
        hand.shrink(n);
        int coins = strike(level, id, n);
        try (Accounts.Scope mint = Accounts.as(Dept.MINT, Purpose.WORK)) {
            ItemStack left = Market.intoStores(level, id, bars);
            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, v.centre().above(), left);
        }
        ItemStack c = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), coins);
        if (!p.getInventory().add(c)) p.drop(c, false);
        return "The mint struck your " + n + " gold ingot" + (n == 1 ? "" : "s") + " into " + coins + " coins (" + (STRUCK - SEIGNIORAGE)
            + " to the ingot; the mint keeps one).";
    }

    // ------------------------------------------------------------------ in words, and the board's page

    /** The ware's books today: {bought n, bought p, sold n, sold p}. */
    static Map<String, long[]> goodsToday(UUID village) {
        Map<String, long[]> out = new HashMap<>();
        CompoundTag g = Accounts.sub(day(village), "goods");
        for (String k : g.getAllKeys()) out.put(k, java.util.Arrays.copyOf(g.getLongArray(k), 4));
        return out;
    }

    /** The goods the board shows: today's most traded, then the town's usual staples, twenty at most. */
    static List<String> boardGoods(UUID village) {
        Map<String, long[]> today = goodsToday(village);
        List<String> keys = new ArrayList<>(today.keySet());
        keys.sort((a, b) -> Long.compare(today.get(b)[1] + today.get(b)[3], today.get(a)[1] + today.get(a)[3]));
        for (Market.Good g : Market.GOODS) {
            if (keys.size() >= 20) break;
            ItemStack s = sampleOfGood(g);
            if (s.isEmpty()) continue;
            String k = Stockroom.key(s);
            if (!keys.contains(k)) keys.add(k);
        }
        return keys.size() > 20 ? keys.subList(0, 20) : keys;
    }

    static ItemStack sampleOfGood(Market.Good g) {
        for (net.minecraft.world.item.Item it : new net.minecraft.world.item.Item[]{ Items.WHEAT, Items.BREAD, Items.OAK_LOG, Items.COBBLESTONE,
            Items.COAL, Items.IRON_INGOT, Items.RAW_IRON, Items.COD, Items.BEEF, Items.WHITE_WOOL, Items.CARROT, Items.POTATO, Items.GOLD_INGOT,
            Items.OAK_PLANKS, Items.LEATHER, Items.APPLE, Items.STONE_BRICKS, Items.TORCH, Items.STICK, Items.GLASS }) {
            ItemStack s = new ItemStack(it);
            if (g.what().test(s)) return s;
        }
        return ItemStack.EMPTY;
    }

    /** The Market page of the town's books: the storehouse's bid and ask for the goods most traded, its stock, today's trades. */
    public static CompoundTag page(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        out.putBoolean("enabled", enabled());
        out.putBoolean("live", live(village));
        out.putLong("opened", openedDay(village));
        out.putString("state", stateLine(level, village));
        double s = spread(village);
        out.putDouble("spread", s);
        out.putLong("cash", cash(village));
        out.putDouble("cashFactor", cashFactor(village));
        out.putLong("credit", storeCredit(village));
        out.putLong("owed", Accounts.owedAll(village));
        out.putLong("debts", Accounts.debts(village));
        out.putLong("advanced", advanced(village));
        long[] w = week(village);
        out.putLong("week_bought", w[0]);
        out.putLong("week_sold", w[1]);
        out.putLong("week_staff", w[2]);
        out.putLong("week_exports", w[3]);
        CompoundTag t = day(village);
        out.putLong("today_bought", t.getLong("bought"));
        out.putLong("today_sold", t.getLong("sold"));
        ListTag rows = new ListTag();
        Map<String, long[]> today = goodsToday(village);
        for (String key : boardGoods(village)) {
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            CompoundTag r = new CompoundTag();
            r.putString("key", key);
            r.putString("name", Businesses.nameOf(key));
            r.putDouble("bid", bid(level, village, one));
            r.putDouble("ask", ask(level, village, one));
            r.putInt("stock", Market.stock(level, village, Stockroom.matcher(key)));
            long[] g = today.getOrDefault(key, new long[4]);
            r.putLong("bq", g[0]);
            r.putLong("sq", g[2]);
            rows.add(r);
        }
        out.put("rows", rows);
        ListTag how = new ListTag();
        for (Map.Entry<String, Long> e : tallies(village).entrySet()) how.add(net.minecraft.nbt.StringTag.valueOf(e.getValue() + " " + e.getKey()));
        out.put("how", how);
        out.putLong("stragglers", Accounts.stragglersToday(level, village));
        ListTag prog = new ListTag();
        for (String line : Privatisation.lines(level, village)) prog.add(net.minecraft.nbt.StringTag.valueOf(line));
        out.put("programme", prog);
        ListTag ious = new ListTag();
        for (String line : Accounts.debtLines(village)) ious.add(net.minecraft.nbt.StringTag.valueOf(line));
        out.put("ious", ious);
        out.putString("census", CoinCensus.line(village));
        return out;
    }

    /** Where the market stands, in a line. */
    static String stateLine(ServerLevel level, UUID village) {
        if (!enabled()) return "The town's goods are its own: no market (a plain town).";
        long opened = openedDay(village);
        if (opened < 0) {
            return "The founders' commons: everything in the stores is everybody's, free (counted), until Market Opening Day"
                + " (from day " + (OPENS_FROM + 1) + ", once the storehouse stands with its keeper; by day " + (OPENS_BY + 1) + " in any case).";
        }
        return "Open since day " + (opened + 1) + ": buying at the bid, selling at the ask, a spread of "
            + String.format(Locale.ROOT, "%.1f%%", spread(village) * 100) + "; cash " + Businesses.pence(cash(village))
            + (cashFactor(village) < 1 ? String.format(Locale.ROOT, " (short: bidding %.0f%%)", cashFactor(village) * 100) : "");
    }

    /** The market in words (/village market). */
    public static String words(ServerLevel level, UUID village) {
        StringBuilder sb = new StringBuilder("The market of " + Villages.name(village) + ": " + stateLine(level, village) + "\n");
        long[] w = week(village);
        sb.append("This week: bought ").append(Businesses.pence(w[0])).append(" of the producers, sold ").append(Businesses.pence(w[1]))
            .append(" (exports ").append(Businesses.pence(w[3])).append("), its staff paid ").append(Businesses.pence(w[2])).append("\n");
        sb.append("Owes producers ").append(Businesses.pence(storeCredit(village))).append(" in store credit; owed ")
            .append(Businesses.pence(Accounts.owedAll(village))).append(" by folk and businesses and ").append(Businesses.pence(Accounts.debts(village)))
            .append(" on the town's IOUs; the treasury's float ").append(Businesses.pence(advanced(village))).append("\n");
        Map<String, long[]> today = goodsToday(village);
        for (String key : boardGoods(village)) {
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            long[] g = today.getOrDefault(key, new long[4]);
            sb.append(String.format(Locale.ROOT, "  %-16s bid %s ask %s  stock %d  today bought %d sold %d%n", Businesses.nameOf(key),
                Businesses.coins(bid(level, village, one)), Businesses.coins(ask(level, village, one)),
                Market.stock(level, village, Stockroom.matcher(key)), g[0], g[2]));
        }
        sb.append("Today's takes: ").append(tallies(village)).append("\n");
        for (String l : Privatisation.lines(level, village)) sb.append(l).append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ tests

    /**
     * Tests: a take out of the stores (TownWork.take) as it would be in a folk's own tick ({@code as}) or under an explicit
     * account ({@code acc}, over the folk), or neither (a straggler). True if the stores had them.
     */
    public static boolean takeForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity as, @Nullable Account acc, Purpose p,
                                       java.util.function.Predicate<ItemStack> what, int n) {
        int d = as != null ? Accounts.enter(as) : Accounts.depthForTests();
        Accounts.Scope s = acc != null ? Accounts.as(acc, p) : null;
        try {
            return TownWork.take(level, v, what, n);
        } finally {
            if (s != null) s.close();
            Accounts.leave(d);
        }
    }

    /** Tests: a give into the stores (TownWork.give), as takeForTests. */
    public static void giveForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity as, @Nullable Account acc, Purpose p, ItemStack stack) {
        int d = as != null ? Accounts.enter(as) : Accounts.depthForTests();
        Accounts.Scope s = acc != null ? Accounts.as(acc, p) : null;
        try {
            TownWork.give(level, v, stack);
        } finally {
            if (s != null) s.close();
            Accounts.leave(d);
        }
    }

    /** Tests: whose account a take of this would be, in this folk's tick. */
    public static Resolved resolveForTests(UUID village, VillageFolkEntity as, ItemStack what) {
        int d = Accounts.enter(as);
        try {
            return Accounts.resolve(village, what);
        } finally {
            Accounts.leave(d);
        }
    }

    /** Tests: the producer viability table now. */
    public static List<Viable> viabilityForTests(ServerLevel level, UUID village) {
        return viability(level, village);
    }

    /** Tests: open the town's market now (Market Opening Day, whatever the day). */
    public static void openForTests(ServerLevel level, Villages.Village v) {
        foundedDay(v.id(), Businesses.today(level));
        open(level, v, Businesses.today(level));
    }

    /** Tests: the market's books' day closed and the morning run now. */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        morning(level, v, Businesses.today(level));
    }

    /** Tests: the week's surplus paid now. */
    public static void surplusForTests(ServerLevel level, UUID village) {
        surplus(level, village, Businesses.today(level));
    }
}
