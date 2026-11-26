package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business.Holder;
import com.jrpetty.mcassistant.entity.Business.Lot;
import com.jrpetty.mcassistant.entity.Business.Stake;
import com.jrpetty.mcassistant.entity.BusinessTypes.BusinessType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-1b] Entrepreneurs: folk who see a want in the town and open a business of their own to meet it; and what becomes
 * of those businesses, for some fail and some thrive.
 *
 * <p><b>Seeing the want.</b> Each morning, after the day's prices are reckoned, the town's wants are looked over kind by
 * kind (BusinessTypes, as the town's age and size allow): for each, the demand for its wares (what was sold, asked for and
 * not there, and refused as too dear over the week, at every counter, and what the households have on their shopping
 * lists), against what is already being supplied (sold, and held by firms of the kind); the cost of making one (its
 * makings at the market's ask, and its maker's time at the going wage) against what it fetches. The gap is the demand not
 * met; the margin is the price over the cost. A new entrant can hope for half the gap; less its premises' rent and its
 * hands' wages, that is the week's profit it might make.
 *
 * <p><b>Who opens it.</b> A grown folk who is not the elder and owns no business but its own homestead, with the skill
 * for the trade (level five, or a knack for it) and the nerve: a Merchant at heart is bold (three), a Visionary (two), a
 * Haggler (one), one who cares for good wages and trade (two); a Homebody or one who puts safety first is not (less two).
 * It must put up half the start-up itself (its premises, three days' makings and a week's wages for any hands) out of its
 * purse and its savings, keeping twelve coins by; the bank may lend the rest. It opens when the week's profit it might make
 * is over three twentieths of the start-up and its nerve is three or more. At most one new business a town each three days,
 * and one for every four grown folk.
 *
 * <p><b>Opening.</b> The founder speaks of it the day before ("I've a mind to open a toolshop: there's no picks to be had").
 * The next morning, if it still adds up, it opens: the founder works it, at its trade, a sign with its name and its price
 * goes up by its stall, the chronicle and the board have it, hands are taken on from the trade or from the hands the town
 * can spare, and its prices are its costs and its markup (Businesses: its books are a business's like any other).
 *
 * <p><b>Thriving.</b> A firm making over a quarter of its capital in profit a week, for a fortnight, with the want still not
 * met, takes on another hand; then larger premises; then opens a second branch, a new firm of the same owner.
 *
 * <p><b>Failing.</b> Cash gone for a week with no credit left, or worth less than it owes (or the business books' own
 * winding up): it is bankrupt. Its stock is sold to the market at the bid, its stall at auction to the folk with the means,
 * and what that brings and its till pay its creditors in order: the wages owed its hands, its taxes and rent, the market's
 * trade credit, the bank. A sole trader's purse is taken for what is left down to six coins. Its hands are laid off, its
 * premises go back, and the chronicle says why ("Wren's Tools closed: it sold too dear and too little").
 *
 * <p><b>Giving up.</b> A firm whose return is below what money earns at the bank for four weeks is wound up by its owner:
 * its stock sold, its debts paid, what is left its own.
 *
 * <p>Kept with the town's accounts (Accounts): each firm's kind, founder, hands, premises and record; the firm's own books
 * are a Business's (Businesses).
 */
public final class Enterprise {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();   // [econ51] the founding decisions

    private Enterprise() {}

    /** A sole trader's purse is never taken under this in a bankruptcy. */
    static final int PURSE_LEFT = 6;
    /** What a founder keeps by of its own, in coin, whatever it puts in. */
    static final int RESERVE = 12;
    /** A stall on the square, pennies (a hamlet's: the town's price level on top). */
    static final long STALL_PENCE = 800;
    /** A player's charter at the hall, coins. */
    public static final int CHARTER_FEE = 10;
    /** Days between foundings in a town; grown folk to a firm. */
    static final int DAYS_BETWEEN = 3, FOLK_A_FIRM = 4;
    /** The share of the market under which a firm cuts its markup, and how long it must stay there. */
    static final double LOW_SHARE = 0.10;
    static final int LOW_SHARE_DAYS = 7;
    /** Expansion: profit over this of capital a week, so many days in a row. */
    static final double GOOD_RETURN = 0.25;
    static final int GOOD_DAYS = 14;
    /** Giving up: return under the bank's deposit rate so many days. */
    static final int POOR_DAYS = 28;
    /** Bankrupt: no cash and no credit so many days; worth under nought so many. */
    static final int BROKE_DAYS = 7, UNDER_DAYS = 3;

    // ------------------------------------------------------------------ the records

    static CompoundTag data(UUID village) {
        return Accounts.sub(Accounts.town(village), "enterprise");
    }

    static CompoundTag firms(UUID village) {
        return Accounts.sub(data(village), "firms");
    }

    @Nullable
    static CompoundTag rec(UUID village, String id) {
        CompoundTag f = firms(village);
        return f.contains(id, Tag.TAG_COMPOUND) ? f.getCompound(id) : null;
    }

    /** Is this business an entrepreneur's firm? */
    public static boolean isFirm(@Nullable Business b) {
        return b != null && b.type == Business.Type.ENTERPRISE;
    }

    /** [econ-1d] Is any of these (stock keys) among the firm's wares? (A firm of no known kind: yes, as before.) */
    static boolean sellsAny(UUID village, Business b, java.util.Collection<String> keys) {
        BusinessType t = kindOf(village, b);
        if (t == null) return true;
        for (String k : keys) if (t.wares().contains(k)) return true;
        return false;
    }

    /** The kind of business a firm is. */
    @Nullable
    public static BusinessType kindOf(UUID village, Business b) {
        CompoundTag r = rec(village, b.id);
        return r == null ? null : BusinessTypes.of(r.getString("type"));
    }

    /** Every entrepreneur's firm of the town, open or closed. */
    public static List<Business> all(UUID village) {
        List<Business> out = new ArrayList<>();
        for (Business b : Businesses.all(village)) if (isFirm(b)) out.add(b);
        return out;
    }

    /** The firms trading now. */
    public static List<Business> open(UUID village) {
        List<Business> out = new ArrayList<>();
        for (Business b : all(village)) if (b.open) out.add(b);
        return out;
    }

    /** Does this folk own any business other than its own homestead (open)? */
    public static boolean ownsAny(UUID village, UUID folk) {
        for (Business b : Businesses.all(village)) {
            if (b.type.size == Business.Size.YARD || !b.open) continue;
            if (b.ownedBy(folk)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the hands

    /** Folk to the firm it works for ("village|id"), from the records; rebuilt a town at a time when first asked. */
    private static final Map<UUID, String> STAFF = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> INDEXED = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        STAFF.clear();
        FirmSupply.resetForTests();                       // [econ-1d]
        INDEXED.clear();
        REFUSED_NOW.clear();
    }

    static void index(UUID village) {
        if (!INDEXED.add(village)) return;
        CompoundTag fs = firms(village);
        for (String id : fs.getAllKeys()) {
            CompoundTag r = fs.getCompound(id);
            if (r.getBoolean("closed")) continue;
            for (Tag t : r.getList("staff", Tag.TAG_INT_ARRAY)) STAFF.put(NbtUtils.loadUUID(t), village + "|" + id);
        }
    }

    static void reindex(UUID village) {
        STAFF.values().removeIf(s -> s.startsWith(village + "|"));
        INDEXED.remove(village);
        index(village);
    }

    /** The firm this folk works for, if it is at the firm's work (Businesses.employer asks first). */
    @Nullable
    public static Business employerOf(@Nullable UUID village, VillageFolkEntity f) {
        if (village == null) return null;
        index(village);
        String at = STAFF.get(f.getUUID());
        if (at == null || !at.startsWith(village + "|")) return null;
        String id = at.substring(at.indexOf('|') + 1);
        Business b = Businesses.of(village, id);
        if (b == null || !b.open) return null;
        BusinessType t = kindOf(village, b);
        if (t == null || f.stationTask() != t.work()) return null;
        return b;
    }

    /** The firm whose hand is at work in this tick (a craft turn, a bench's batch), if one is. */
    @Nullable
    static Business atWork(UUID village) {
        if (offDuty) return null;                                       // [econ-1c] at the town's wants, not the firm's
        VillageFolkEntity f = Accounts.actor();
        return f == null ? null : employerOf(village, f);
    }

    /** [econ-1c] A firm's hand at a turn of the town's own work (Crafts.now, its firm's shelf full): not the firm's making. */
    private static boolean offDuty;

    static void offDuty(boolean on) {
        offDuty = on;
    }

    /** A firm's hands and its owner stay at its work: the town's own sums do not move them to another trade. */
    public static boolean keepsTrade(VillageFolkEntity f) {
        return f.ownerId() != null && employerOf(f.ownerId(), f) != null;
    }

    static List<UUID> staffOf(CompoundTag r) {
        List<UUID> out = new ArrayList<>();
        for (Tag t : r.getList("staff", Tag.TAG_INT_ARRAY)) out.add(NbtUtils.loadUUID(t));
        return out;
    }

    static void addStaff(UUID village, Business b, CompoundTag r, VillageFolkEntity f) {
        ListTag l = r.getList("staff", Tag.TAG_INT_ARRAY);
        for (Tag t : l) if (NbtUtils.loadUUID(t).equals(f.getUUID())) return;
        l.add(NbtUtils.createUUID(f.getUUID()));
        r.put("staff", l);
        STAFF.put(f.getUUID(), village + "|" + b.id);
        Accounts.dirty();
    }

    static void removeStaff(UUID village, CompoundTag r, UUID who) {
        ListTag l = r.getList("staff", Tag.TAG_INT_ARRAY);
        l.removeIf(t -> NbtUtils.loadUUID(t).equals(who));
        r.put("staff", l);
        STAFF.remove(who);
        Accounts.dirty();
    }

    /** Its hands at work today (alive, in town, at the firm's work). */
    public static List<VillageFolkEntity> hands(UUID village, Business b) {
        List<VillageFolkEntity> out = new ArrayList<>();
        CompoundTag r = rec(village, b.id);
        if (r == null) return out;
        for (UUID u : staffOf(r)) {
            VillageFolkEntity f = Homes.loaded(village, u);
            if (f != null && f.isAlive() && employerOf(village, f) == b) out.add(f);
        }
        return out;
    }

    /** A hand paid short (Businesses.paysHand): owed it, and paid first if the firm goes bankrupt. */
    static void wageOwed(UUID village, Business b, VillageFolkEntity f, long p) {
        if (!isFirm(b) || p <= 0) return;
        CompoundTag r = rec(village, b.id);
        if (r == null) return;
        Accounts.add(Accounts.sub(r, "owedWages"), f.getUUID().toString(), p);
        Accounts.dirty();
    }

    // ------------------------------------------------------------------ making and catching

    /** A firm's hand's catch or harvest (MarketMaker.output): the firm's own stock, at its standard cost, to sell on. */
    static boolean output(ServerLevel level, UUID village, Business firm, ItemStack s) {
        if (!isFirm(firm) || s.isEmpty()) return false;
        long n = s.getCount();
        long cost = Math.round(CostBook.of(s).total() * 100 * n);
        String key = Stockroom.key(s);
        firm.stock.computeIfAbsent(key, k -> new Lot()).add(n, cost);
        long day = Businesses.today(level);
        firm.today(day).line(key).made += n;
        firm.allTime.line(key).made += n;
        firm.cards.put(key, n + " brought in by its hands: " + Businesses.pence(cost) + " at the standard cost, day " + (day + 1));
        Businesses.dirty();
        return true;
    }

    // ------------------------------------------------------------------ [econ-1c] the shelf

    /** A firm's shelf: so many of a ware at the most, whatever the want. */
    static final int MOST_ON_SHELF = 12;

    /** [biz30d] A cheap ware's shelf holds up to so many coins' worth of its makings, and never more than this many. */
    static final double SHELF_COINS = 30.0;
    static final int MOST_ON_SHELF_CHEAP = 32;

    /**
     * [biz30d] The most a firm keeps of a ware: twelve, or for a cheap one (a loaf) thirty coins' worth of its makings, to
     * thirty-two. A bakery with the town's call for forty loaves a day kept twelve: sold out by mid-morning, its baker at the
     * town's tables or its works till its shelf was short again, and the town's folk ate raw potatoes meanwhile.
     */
    static int shelfMost(ServerLevel level, UUID village, String key) {
        ItemStack one = Stockroom.sampleOf(key);
        double each = one.isEmpty() ? 0 : CostBook.atMarket(level, village, one);
        if (each <= 0.01) return MOST_ON_SHELF;
        return (int) Math.max(MOST_ON_SHELF, Math.min(MOST_ON_SHELF_CHEAP, Math.floor(SHELF_COINS / each)));
    }

    /**
     * What a firm's shelf should hold of each of its wares, set each morning: three days of the week's want of it (what sold
     * at every counter and every firm, what was asked for and not there, refused as too dear, and the households' shopping
     * lists), and one over; two of its first ware when nothing is wanted yet, for the folk to see. Kept in its record.
     */
    static void targets(ServerLevel level, UUID village, Business b, CompoundTag r, Map<String, Integer> lists) {
        BusinessType t = kindOf(village, b);
        if (t == null) return;
        CompoundTag tg = new CompoundTag();
        boolean any = false;
        for (String key : t.wares()) {
            if (Stockroom.sampleOf(key).isEmpty()) continue;
            int[] w = Stockroom.week7(level, village, key);
            double want = w[0] + w[1] + refused7(village, key) + lists.getOrDefault(key, 0) + firmsSold7(level, village, key);
            if (want <= 0) continue;
            int n = (int) Math.min(shelfMost(level, village, key), Math.ceil(want * 3 / 7.0) + 1);
            tg.putInt(key, n);
            any = true;
        }
        if (!any && !t.wares().isEmpty()) tg.putInt(t.wares().get(0), 2);
        r.put("targets", tg);
        Accounts.dirty();
    }

    /**
     * [econ-1e] Is this folk a hand of a firm whose shelf is short of what it makes, so its turns go to its firm's bench before
     * the town's tables, counters and sinks? A bakery's baker is a cook, and the town's dining rooms and its café's ovens
     * claimed a cook's time first: a bakery with the town's call for two hundred loaves a week made eight a day. A hand
     * whose firm's shelf is full serves the town as before.
     */
    public static boolean benchFirst(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return false;
        Business b = employerOf(village, f);
        if (b == null || !b.open) return false;
        BusinessType t = kindOf(village, b);
        CompoundTag r = rec(village, b.id);
        if (t == null || r == null || t.resells() || t.work() != f.stationTask()) return false;
        CompoundTag tg = r.getCompound("targets");
        if (r.contains("short") && tg.size() <= 1) return false;        // nothing to make it of: the town's work meanwhile
        for (String key : tg.getAllKeys()) if (held(b, key) < tg.getInt(key)) return true;
        return false;
    }

    /**
     * [econ-1e] Does this firm already ask more than the going price for its main ware (what it sold most of this week, else
     * its first)? A firm losing money raised its markup a tenth every third day of it; one already dearer than the stores
     * only sold less for it (a bakery at three fifths over the going price, its trade gone to the cheap carrots): a firm
     * over what the market bears looks to selling more, not dearer. (A firm of the town's own kinds only.)
     */
    static boolean overGoing(ServerLevel level, UUID village, Business b) {
        if (!isFirm(b)) return false;
        BusinessType t = kindOf(village, b);
        if (t == null || t.wares().isEmpty()) return false;
        String main = null;
        long most = -1;
        for (Map.Entry<String, Business.Line> e : b.last(Businesses.today(level) - 1, 7).lines.entrySet()) {
            if (t.wares().contains(e.getKey()) && e.getValue().units > most) { most = e.getValue().units; main = e.getKey(); }
        }
        if (main == null) main = t.wares().get(0);
        ItemStack one = Stockroom.sampleOf(main);
        if (one.isEmpty()) return false;
        return Sellers.priceOf(level, village, b, one) > Market.askEach(level, village, one) * 1.001;
    }

    /**
     * [econ-1e] Is this one of the wares a maker firm makes itself (a bakery's bread)? Its morning's orders bought them of the
     * market at its ask whenever its hands had made none that week (short of wheat a day or two), and sold them on at its
     * own price: a bakery's cost of goods came to nine tenths of its takings, its "bread" bought in from the town's cook.
     */
    static boolean makesItself(UUID village, Business b, String key) {
        if (!isFirm(b)) return false;
        BusinessType t = kindOf(village, b);
        return t != null && !t.resells() && t.wares().contains(key);
    }

    /**
     * [econ-1e] Does this firm's trade bring its wares in (a fish stall's catch), rather than make them at a bench? A maker
     * firm's own wares are booked as its bench makes them (Businesses.made); what its hand brings to the stores from a turn
     * at the town's work (a bakery's cook's ladders and lanterns, the café's bread) is the town's, and had gone on the
     * bakery's shelf at the cost book's price.
     */
    static boolean catches(UUID village, Business b) {
        BusinessType t = kindOf(village, b);
        return t != null && Privatisation.producer(t.work());
    }

    /** What the firm holds of a ware. */
    static long held(Business b, String key) {
        Lot l = b.stock.get(key);
        return l == null ? 0 : l.qty;
    }

    /**
     * A firm's hand at its bench (Crafts.now, before the town's own wants): the firm's wares, the one its shelf is shortest
     * of first, made out of the stores at the market's price (its makings bought by the firm: Businesses.crafted), the piece
     * on its shelf in the stores to sell at its own price. What was made, or null (its shelf full, or nothing to make it of).
     */
    @Nullable
    public static String make(ServerLevel level, Villages.Village v, VillageFolkEntity f, Business b) {
        BusinessType t = kindOf(v.id(), b);
        CompoundTag r = rec(v.id(), b.id);
        if (t == null || r == null || t.resells()) return null;
        CompoundTag tg = r.getCompound("targets");
        Accounts.add(r, "turns", 1);                                      // [econ-1e] its hands' turns at its bench (the books, the tests)
        List<String> order = new ArrayList<>();
        for (String key : tg.getAllKeys()) if (held(b, key) < tg.getInt(key)) order.add(key);
        if (order.isEmpty()) return null;
        order.sort(Comparator.comparingLong((String k) -> held(b, k) - tg.getInt(k)));
        Bench.Hand hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(t.work()));
        for (String key : order) {
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            // [econ-1d] A batch, not a piece: a baker bakes a tray of loaves at a time (up to four, as far as its shelf is
            // short), and a firm with a town's worth of call for its wares and one pair of hands made five loaves a day of
            // the fifty wanted. A batch it cannot make is tried a piece at a time.
            int batch = (int) Math.max(1, Math.min(BATCH + Temperament.outputShift(v.id(), b), tg.getInt(key) - held(b, key)));   // [econ51] its owner's character
            Bench.Plan plan = FirmSupply.asFirm(() -> Bench.plan(level, v, one.getItem(), batch, hand));   // [econ-1d] a paying firm: the keeps eased
            if (!plan.ok() && batch > 1) plan = FirmSupply.asFirm(() -> Bench.plan(level, v, one.getItem(), 1, hand));
            if (!plan.ok()) {
                // [biz30] A ware no bench makes (a fish stall's catch, a tavern's cider, an apothecary's draughts): bought in at the
                // market each morning as a shop buys its stock, or its shelf never held a thing.
                if ("a way to make it".equals(plan.shortOf)) { Accounts.sub(r, "buyIn").putInt(key, 1); Accounts.dirty(); }
                r.putString("short", Stockroom.nameOf(key) + ": short of " + plan.shortOf);
                InputWants.want(v.id(), plan.missing, Math.max(1, plan.missingCount) * batch);   // [supply] the word to its producers
                continue;
            }
            ItemStack out = Bench.make(level, v, plan, f, hand);
            if (out.isEmpty()) continue;
            FirmSupply.drew(v.id(), plan.takes);                              // [econ-1d] off the day's ration of the age's reserve
            r.remove("short");
            Accounts.add(r, "madeHere", out.getCount());
            Accounts.dirty();
            return Bench.words(out.getItem(), out.getCount()) + " for " + b.name;
        }
        return null;
    }

    /** A shop that buys in to sell on: what its shelf is short of, bought at the market (on its credit if its till is short). */
    static void restock(ServerLevel level, UUID village, Business b, CompoundTag r) {
        BusinessType t = kindOf(village, b);
        CompoundTag buyIn = r.getCompound("buyIn");                         // [biz30] a maker's wares no bench makes
        if (t == null || !t.resells() && buyIn.isEmpty() || !MarketMaker.live(village)) return;
        CompoundTag tg = r.getCompound("targets");
        for (String key : tg.getAllKeys()) {
            if (!t.resells() && !buyIn.contains(key)) continue;
            long short_ = tg.getInt(key) - held(b, key);
            if (short_ <= 0) continue;
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            Businesses.OrderResult res = Businesses.order(level, village, b, one, short_, true);
            if (res.ok()) Accounts.add(r, "boughtIn", res.qty());
        }
    }

    // ------------------------------------------------------------------ demand

    /** Refusals as too dear, by the ware, for the scan (PriceIndex.refused): today's, rolled into the week each morning. */
    private static final Map<String, Integer> REFUSED_NOW = new ConcurrentHashMap<>();

    static void refused(UUID village, ItemStack what, int n) {
        if (village == null || what.isEmpty() || n <= 0) return;
        REFUSED_NOW.merge(village + "|" + Stockroom.key(what), n, Integer::sum);
    }

    /** The week's refusals of a ware (the last seven mornings' and today's). */
    static int refused7(UUID village, String key) {
        int n = REFUSED_NOW.getOrDefault(village + "|" + key, 0);
        CompoundTag d = Accounts.sub(data(village), "refused");
        for (int x : d.getIntArray(key)) n += x;
        return n;
    }

    /** The morning: today's refusals into the week's books. */
    static void rollRefused(UUID village) {
        CompoundTag d = Accounts.sub(data(village), "refused");
        java.util.Set<String> keys = new java.util.HashSet<>(d.getAllKeys());
        String pre = village + "|";
        for (String k : REFUSED_NOW.keySet()) if (k.startsWith(pre)) keys.add(k.substring(pre.length()));
        for (String key : keys) {
            int[] a = d.getIntArray(key);
            int[] b = new int[7];
            for (int i = 0; i < 6 && i < a.length; i++) b[i + 1] = a[i];
            Integer now = REFUSED_NOW.remove(pre + key);
            b[0] = now == null ? 0 : now;
            boolean any = false;
            for (int x : b) any |= x > 0;
            if (any) d.putIntArray(key, b); else d.remove(key);
        }
        Accounts.dirty();
    }

    /** What every firm sold of a ware over the week (units), the open and the shut. */
    static long firmsSold7(ServerLevel level, UUID village, String key) {
        long day = Businesses.today(level), n = 0;
        for (Business b : all(village)) {
            Business.Line l = b.last(day, 7).lines.get(key);
            if (l != null) n += Math.max(0, l.units);
        }
        return n;
    }

    /** One opportunity: a kind, its demand and supply over the week, price and cost each, the gap, score and profit. */
    public record Opportunity(BusinessType type, String good, double demand, double supply, double price, double unitCost, double inputCost,
                              double gap, double score, double profit7, double startup, int firms) {
        public double margin() {
            return unitCost <= 0 ? 0 : price / unitCost;
        }

        public String words() {
            return String.format(Locale.ROOT, "%-13s demand %.0f supply %.0f gap %.0f, %s at %.2fc against %.2fc to make (margin %.2f), %d firms: score %.1f, a week's profit %.2fc on a start-up of %.2fc",
                type.key(), demand, supply, gap, good, price, unitCost, margin(), firms, score, profit7, startup);
        }
    }

    /** The going wage of a trade here, coins a day. */
    static int wageOf(StationTask t, UUID village) {
        return Math.max(1, JobWorth.rate(t, village));
    }

    /** A firm's day's rent, pennies: a stall, a room, larger premises; at the town's standing. */
    public static long rentPence(UUID village, Business b) {
        CompoundTag r = rec(village, b.id);
        int stage = r == null ? 0 : r.getInt("premises");
        BusinessType t = r == null ? null : BusinessTypes.of(r.getString("type"));
        long base = stage >= 1 ? 120 : t != null && !t.stall() ? 60 : 30;
        return base * Wealth.standing(village) / 10;
    }

    /** The start-up a kind wants here, pennies: its premises, three days' makings (for the trade it expects: half the want
     *  not met, no more than its hands can make), a week's wages for its hands. */
    static long startup(UUID village, BusinessType t, double inputCost, @Nullable UUID founder) {
        return startup(village, t, inputCost, founder, Math.min(t.perDay(), 16));
    }

    static long startup(UUID village, BusinessType t, double inputCost, @Nullable UUID founder, double perDay) {
        long premises = t.stall() ? Math.round(STALL_PENCE * MarketMaker.priceLevel(village)) : 0;
        if (founder != null && Accounts.sub(data(village), "stalls").getInt(founder.toString()) > 0 && t.stall()) premises = 0;
        long makings = Math.round(inputCost * 100 * Math.max(1, Math.min(perDay, Math.min(t.perDay(), 16))) * 3);
        long wages = (long) t.startStaff() * wageOf(t.work(), village) * 100L * 7;
        return premises + makings + wages;
    }

    /** The week's wants met and not of a kind's wares, its costs, and what a new firm might make of it. */
    @Nullable
    static Opportunity weigh(ServerLevel level, Villages.Village v, BusinessType t, Map<String, Integer> lists) {
        UUID id = v.id();
        double demand = 0, supply = 0, priceSum = 0, costSum = 0, inputSum = 0, weightSum = 0, undercut = 0;
        String best = null;
        double bestGap = -1;
        long day = Businesses.today(level);
        for (String key : t.wares()) {
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            int[] w = Stockroom.week7(level, id, key);
            long firmSold = firmsSold7(level, id, key);
            long firmStock = 0;
            for (Business b : open(id)) {
                Lot l = b.stock.get(key);
                if (l != null) firmStock += l.qty;
            }
            // What the town's own counter has on its shelves meets the shopping lists as well as anybody's.
            long onShelf = Math.min(Market.stock(level, id, Stockroom.matcher(key)) + Businesses.inShop(level, id, key), 4L * Math.max(1, lists.getOrDefault(key, 0)));
            double want = w[0] + w[1] + refused7(id, key) + lists.getOrDefault(key, 0) + firmSold;
            double have = w[0] + firmSold + firmStock + onShelf;
            double price = Market.askEach(level, id, one);
            double inputs = makings(level, id, t, one);
            double labour = wageOf(t.work(), id) / Math.max(1.0, t.perDay());
            double cost = inputs + labour;
            demand += want;
            supply += have;
            double weight = Math.max(1, want);
            priceSum += price * weight;
            costSum += cost * weight;
            inputSum += inputs * weight;
            weightSum += weight;
            double gap = want - 0.9 * have;
            // [econ-1e] The town's own counter selling a ware in a firm's week's worth (half what its hands can make), at what
            // it costs to make and its maker's time and two fifths over: a trader with its own bench can undercut it and take a
            // share of a market already served (a quarter, say), as a private café takes the baked potatoes from a town counter
            // selling them at three times their potato. The want not met was all a firm looked at, and a town's fattest trades
            // were never anybody's business.
            if (!t.resells() && w[0] >= t.perDay() * 3.5 && cost > 0 && price >= 1.4 * cost && firmSold < w[0] / 2) {
                undercut += 0.25 * w[0];
                if (best == null || 0.25 * w[0] > bestGap) { bestGap = Math.max(bestGap, 0.25 * w[0]); best = key; }
            }
            if (gap > bestGap) { bestGap = gap; best = key; }
        }
        if (weightSum <= 0 || best == null) return null;
        double price = priceSum / weightSum, cost = costSum / weightSum, inputs = inputSum / weightSum;
        double gap = Math.max(0, demand - 0.9 * supply) + undercut;      // [econ-1e] (and the share it can take by undercutting)
        int firms = 0;
        for (Business b : open(id)) {
            BusinessType k = kindOf(id, b);
            if (k != null && k.key().equals(t.key())) firms++;
        }
        double margin = cost <= 0 ? 0 : price / cost;
        double score = gap * price * (margin - 1) / (1 + firms);
        // [econ-1c] Nothing to make it of: a maker whose wares the market holds no makings for (no leather in the town for a
        // clothier) would open to stand idle. Only where the want is worth having is it looked into.
        if (score > 0 && !t.resells() && !makeable(level, v, t)) {
            InputWants.shortFor(level, v, t, best, (int) Math.max(1, Math.min(16, Math.ceil(gap / 7))));   // [supply] the word to its producers
            score = 0;
        }
        // What a new firm might make in a week: half the score (a new entrant's capture), less its rent and the wages of any
        // hands beside the founder (the founder lives on what it makes).
        double rent7 = (t.stall() ? 30 : 60) * Wealth.standing(id) / 10.0 * 7 / 100.0;
        double profit7 = score * 0.5 - rent7 - t.startStaff() * wageOf(t.work(), id) * 7;
        double startup = startup(id, t, inputs, null, gap * 0.5 / 7) / 100.0;
        return new Opportunity(t, best, demand, supply, price, cost, inputs, gap, score, profit7, startup, firms);
    }

    /** [econ-1c] Can one of its wares be made out of the stores now (by a stand-in hand at its trade's bench)? */
    static boolean makeable(ServerLevel level, Villages.Village v, BusinessType t) {
        Bench.Hand hand = Bench.handOf(level, v, null, VillageFolkEntity.buildingFor(t.work()));
        Bench.Hand any = new Bench.Hand(Math.max(hand.skill(), 10), hand.maker(), hand.table(), hand.furnace(), hand.smoker(), hand.campfire(), hand.smithy());
        for (String key : t.wares()) {
            ItemStack one = Stockroom.sampleOf(key);
            if (!one.isEmpty() && FirmSupply.asFirm(() -> Bench.plan(level, v, one.getItem(), 1, any)).ok()) return true;   // [econ-1d] as a paying firm
        }
        return false;
    }

    /** One of a ware's makings at the market's ask (a seller's: its buying price, a twentieth off in bulk). */
    static double makings(ServerLevel level, UUID village, BusinessType t, ItemStack one) {
        if (t.resells()) return Businesses.storesEach(level, village, one) * 0.95;
        return CostBook.atMarket(level, village, one);                     // [econ-1e] its makings at what the town's market asks today
    }

    /** The households' shopping lists, ware by ware (Needs). */
    static Map<String, Integer> lists(ServerLevel level, UUID village) {
        Map<String, Integer> out = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            try {
                for (Needs.Want w : Needs.of(level, f)) out.merge(Stockroom.key(w.sample()), w.count(), Integer::sum);
            } catch (RuntimeException e) {
                // a list that cannot be made today is no want
            }
        }
        return out;
    }

    /** The town's opportunities this morning, the best first. */
    public static List<Opportunity> scan(ServerLevel level, Villages.Village v) {
        List<Opportunity> out = new ArrayList<>();
        Map<String, Integer> lists = lists(level, v.id());
        for (BusinessType t : BusinessTypes.allowed(v.id())) {
            Opportunity o = weigh(level, v, t, lists);
            if (o != null) out.add(o);
        }
        out.sort(Comparator.comparingDouble(Opportunity::profit7).reversed());
        return out;
    }

    // ------------------------------------------------------------------ the founder

    /** How bold a folk is in business: a Merchant three, a Visionary two, a Haggler one, wages and trade two; a Homebody less two. */
    public static int boldness(VillageFolkEntity f) {
        int b = 0;
        if (f.persona().rolled()) {
            Values.Value top = Values.top(f);
            if (top == Values.Value.WEALTH) b += 3;
            if (top == Values.Value.PROGRESS) b += 2;
            if (top == Values.Value.SAFETY) b -= 2;
            int[] w = Values.of(f);
            if (top != Values.Value.WEALTH && w[Values.Value.WEALTH.ordinal()] >= 60) b += 2;
        }
        if (f.knacks().has(FolkSkills.Knack.HAGGLER)) b += 1;
        if (Quirks.has(f, Quirks.Quirk.HOMEBODY)) b -= 2;
        return b;
    }

    /** Has it the skill for this trade: level five, or a knack for it. */
    static boolean skilled(VillageFolkEntity f, StationTask work) {
        if (f.tradeLevel(work) >= 5) return true;
        for (FolkSkills.Knack k : FolkSkills.Knack.values()) if (k.trades.contains(work) && f.knacks().has(k)) return true;
        return false;
    }

    /**
     * [econ-1c] Has it the skill for this trade as the town reckons skill: a master's (level five, or a knack), or nobody in
     * the town better at it than itself. A young town has no masters: its best hand at a trade is the one folk go to (a
     * tailor of three where nobody else has sewn a stitch), and where nobody has done the work, whoever takes it up learns
     * it on the job.
     */
    static boolean skilled(UUID village, VillageFolkEntity f, StationTask work) {
        return skilled(f, work) || f.tradeLevel(work) >= bestElse(village, work, f);
    }

    /** The best level at a trade among the town's grown folk other than this one (nought if nobody has done it). */
    static int bestElse(UUID village, StationTask work, @Nullable VillageFolkEntity not) {
        int best = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == not || f.isBaby() || !f.isAlive()) continue;
            best = Math.max(best, f.tradeLevel(work));
        }
        return best;
    }

    /**
     * [econ-1c] A founder without the skill takes on one who has it: the most practised free folk at the trade (not an owner,
     * not another firm's, not one the town cannot spare), who is skilled as the town reckons it. Null if there is none.
     */
    @Nullable
    static VillageFolkEntity skilledHand(UUID village, BusinessType t, VillageFolkEntity founder) {
        index(village);
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == founder || f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) continue;
            if (STAFF.containsKey(f.getUUID()) || ownsAny(village, f.getUUID()) || TownJobs.busy(f)) continue;
            StationTask now = f.stationTask();
            if (now == StationTask.GUARD || now == StationTask.STORE || now == StationTask.BANK || now == StationTask.SCOUT) continue;
            if (Orders.elderOf(village) == f || !skilled(village, f, t.work())) continue;
            if (best == null || f.tradeLevel(t.work()) > best.tradeLevel(t.work())) best = f;
        }
        if (best == null) best = LabourMarket.outbid(village, t.work(), founder);   // [labour] none free: outbid another employer for one
        return best;
    }

    /** [econ-1c] The skilled hand's first days' wages, pennies: the rest it is paid out of the takings. */
    static long handStake(UUID village, BusinessType t) {
        return 3L * wageOf(t.work(), village) * 100L;
    }

    /** What it could put into a business, coins: its purse and its savings, less what it keeps by ([econ-1c] two days' keep
     *  at its own wage, four coins at the least and twelve at the most: a labourer's twelve coins were a fortnight's pay). */
    static int means(VillageFolkEntity f) {
        int keep = Math.max(4, Math.min(RESERVE, 2 * Wealth.wage(f)));
        return Math.max(0, f.purse() + Bank.balance(f) + FolkCredit.clubBalance(f) - keep);   // [capital] its savings at the club too
    }

    /**
     * [econ-1d] What a founder could put in, coins: its purse and savings as means has it, and what its own homestead (a
     * farm, a mine, a boat it owns alone) holds over the float its till keeps, the profits it has made and not yet drawn. A
     * farmer whose harvest money sat in its farm's till had no means to open a bakery with, however rich its farm.
     */
    static int founderMeans(UUID village, VillageFolkEntity f) {
        return means(f) + (int) (homesteadSpare(village, f) / 100);
    }

    /** [econ-1d] What the businesses this folk owns alone (its homestead) hold over their tills' floats, pennies. */
    static long homesteadSpare(UUID village, VillageFolkEntity f) {
        long n = 0, day = Accounts.dayNow();
        for (Business b : Businesses.all(village)) {
            if (!b.open || b.town() || b.mirror() || b.type.size != Business.Size.YARD || b.owners.size() != 1 || !b.ownedBy(f.getUUID())) continue;
            n += Math.max(0, b.till - Businesses.floatOf(b, day));
        }
        return n;
    }

    /** [econ-1d] So many pennies drawn out of this folk's homestead tills over their floats into a new firm (its drawings). */
    static long drawFromHomestead(UUID village, VillageFolkEntity f, long want, long day) {
        long got = 0;
        for (Business b : Businesses.all(village)) {
            if (got >= want) break;
            if (!b.open || b.town() || b.mirror() || b.type.size != Business.Size.YARD || b.owners.size() != 1 || !b.ownedBy(f.getUUID())) continue;
            long take = Math.min(want - got, Math.max(0, b.till - Businesses.floatOf(b, day)));
            if (take <= 0) continue;
            b.till -= take;
            Business.Day d = b.today(day);
            d.drawings += take;
            b.allTime.drawings += take;
            b.note(day, "its owner drew " + Businesses.pence(take) + " to put into a business of its own");
            got += take;
        }
        Businesses.dirty();
        return got;
    }

    /** [econ-1d] The most a firm's hand makes of a ware at a turn of its bench. */
    static final int BATCH = 4;

    /** [econ-1c] Partners at most, and what a founder must put up itself whatever its partners do, coins. */
    static final int MOST_PARTNERS = 2, OWN_LEAST = 2;

    /**
     * [econ-1c] Who would put money in for a share: grown folk with savings (three coins or more to spare), not timid (a
     * Homebody, or one who puts safety first, keeps its coin), owning no business, neither the founder nor its skilled hand
     * nor the elder; the most to spare first. Each puts in up to half what it could.
     */
    static List<VillageFolkEntity> partnersFor(UUID village, VillageFolkEntity founder, @Nullable VillageFolkEntity hand) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == founder || f == hand || f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) continue;
            if (Orders.elderOf(village) == f || ownsAny(village, f.getUUID()) || boldness(f) < 0 || means(f) < 3) continue;
            out.add(f);
        }
        out.sort(Comparator.comparingInt((VillageFolkEntity f) -> -means(f)).thenComparing(VillageFolkEntity::getUUID));
        return out.size() > MOST_PARTNERS ? new ArrayList<>(out.subList(0, MOST_PARTNERS)) : out;
    }

    /** What a partner puts in, coins: half what it could. */
    static int partnerMoney(VillageFolkEntity p) {
        return means(p) / 2;
    }

    /** What a founder could raise, coins: its own and its partners'. */
    static int raisable(UUID village, VillageFolkEntity f, @Nullable VillageFolkEntity hand) {
        int n = founderMeans(village, f);                                  // [econ-1d] its homestead's spare too
        for (VillageFolkEntity p : partnersFor(village, f, hand)) n += partnerMoney(p);
        return n;
    }

    /** [econ-1c] The nerve it takes: three; two for a sure thing (a week's profit over half the start-up). */
    static int nerveFor(double profit7, double startup) {
        return profit7 >= 0.5 * startup ? 2 : 3;
    }

    /** [econ-1c] Its own share of the start-up it must put up: half; a third for a sure thing (the town's credit the rest). */
    static double shareFor(double profit7, double startup) {
        return profit7 >= 0.5 * startup ? 1.0 / 3.0 : 0.5;
    }

    /** Why this folk may not open this kind of business, or null if it may (the nerve and the money aside). */
    @Nullable
    static String barred(UUID village, VillageFolkEntity f, BusinessType t) {
        if (f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) return "not one who could";
        if (Regimes.businessBarred(village) != null) return "no private business in a planned state";   // [featcivic]
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder == f) return "the elder has the town to run";
        if (ownsAny(village, f.getUUID())) {                                // [biz30] a second, a third, as its success carries it (FirmBranches)
            String why = f.level() instanceof ServerLevel sl ? FirmBranches.whyNot(sl, village, f) : "already has a business";
            if (why != null) return why;
        }
        // [econ-1c] The skill as the town reckons it; without it, a skilled hand to take on.
        if (!skilled(village, f, t.work()) && skilledHand(village, t, f) == null)
            return "has not the skill (level " + f.tradeLevel(t.work()) + " at " + t.work().label + ") and nobody free who has";
        StationTask now = f.stationTask();
        if (now == StationTask.GUARD || now == StationTask.STORE || now == StationTask.BANK) return "the town cannot spare it from " + now.label;
        return null;
    }

    /** The best founder for an opportunity: able, bold and with the means, the boldest first. */
    @Nullable
    static VillageFolkEntity founderFor(UUID village, BusinessType t, long startupPence) {
        return founderFor(village, t, startupPence, 0);
    }

    /** [econ-1c] As above, the nerve and the share it puts up by how sure a thing it is (a week's profit, pennies); one
     *  without the skill puts up its skilled hand's first days' wages besides. */
    @Nullable
    static VillageFolkEntity founderFor(UUID village, BusinessType t, long startupPence, long profit7Pence) {
        return founderFor(village, t, startupPence, profit7Pence, 0);
    }

    /**
     * [econ51] As above, its character counted (Temperament: ambition above all, the nerve it adds), and {@code ease} the
     * mornings a want worth meeting has gone unmet for want of anybody to open it, halved (at most two): each such morning
     * a little less nerve and a little less of its own money asked of whoever takes it on.
     */
    @Nullable
    static VillageFolkEntity founderFor(UUID village, BusinessType t, long startupPence, long profit7Pence, int ease) {
        VillageFolkEntity best = null;
        double bestScore = -1e9;
        int nerve = Math.max(ease > 0 ? 0 : 1, nerveFor(profit7Pence, startupPence) + EconCycle.nerveShift(village) - ease);   // [econ4] bolder in a boom, warier in a slump
        double share = shareFor(profit7Pence, startupPence) * (1.0 - 0.25 * ease);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            if (barred(village, f, t) != null) continue;
            int bold = boldness(f) + Temperament.drive(f);                  // [econ51] its character: ambition the most of it
            boolean ambitious = Temperament.ambitious(f);
            if (bold < nerve) continue;                                     // [econ51] ambition's +3 the most of the nerve
            boolean own = skilled(village, f, t.work());
            long need = startupPence + (own ? 0 : handStake(village, t));
            // [econ-1c] Its own coin and its partners': a founder short of the money finds folk to go in with it.
            if (founderMeans(village, f) < OWN_LEAST || raisable(village, f, own ? null : skilledHand(village, t, f)) * 100L < share * need) continue;   // [econ-1d] (founderMeans)
            double s = bold * 10 + (ambitious ? 20 : 0) + f.tradeLevel(t.work()) + Math.min(10, founderMeans(village, f) / 5.0) + (f.stationTask() == t.work() ? 5 : 0) + (own ? 5 : 0);
            if (s > bestScore || s == bestScore && best != null && f.getUUID().compareTo(best.getUUID()) < 0) {
                best = f;
                bestScore = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The firms' morning (Businesses.morning, after every business's own): the hands seen to, each firm's share of its
     * market, its prices, its fortunes (bankrupt, thriving, giving up), its stock over what it sells sold to the market; then
     * yesterday's intention carried out, and the town's wants looked over for the next.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!MarketMaker.live(id) && !FOUND_IN_PLAIN_FOR_TESTS) return;
        index(id);
        rollRefused(id);
        Map<String, Integer> wants = open(id).isEmpty() ? Map.of() : lists(level, id);
        for (Business b : open(id)) {
            CompoundTag r = rec(id, b.id);
            if (r == null) continue;
            // [econ-1c] Before the court since yesterday and not heard at the hall: the council hears it on the square.
            if (r.contains("petition")) {
                if (r.getLong("petitionDay") < day) hear(level, id, b, r, day, "by the council on the square");
                continue;
            }
            if (MyBusinesses.paused(level, id, b, r)) continue;               // [wpb] its owner away and no manager: paused safely
            tidyStaff(level, v, b, r);
            share(level, id, b, r, day);
            sellOver(level, id, b, day);
            if (fortunes(level, v, b, r, day)) continue;
            targets(level, id, b, r, wants);              // [econ-1c] what its shelf should hold of each ware
            restock(level, id, b, r);                     // [econ-1c] a shop's stock bought in at the market
            signs(level, v, b, r);
        }
        FirmBranches.weekly(id, day);                                       // [biz30] its good weeks tallied
        FirmBranches.closeLosers(level, id, day);                           // [biz30] an owner's losing branch shut
        CompoundTag d = data(id);
        if (d.contains("intent", Tag.TAG_COMPOUND)) {
            CompoundTag in = d.getCompound("intent");
            d.remove("intent");
            if (in.getLong("day") < day) carryOut(level, v, in, day);
        }
        if (!AUTO_FOR_TESTS) return;
        Opportunity o = mayFound(level, v, day);
        if (o != null) intend(level, v, o, day);
    }

    /** Tests: the scan off (only what a test asks for opens), or on. */
    private static volatile boolean AUTO_FOR_TESTS = true;
    private static volatile boolean FOUND_IN_PLAIN_FOR_TESTS = false;

    public static void autoForTests(boolean on) {
        AUTO_FOR_TESTS = on;
    }

    /** May the town see a business opened this morning: the limits, and the best opportunity with a founder for it. */
    @Nullable
    static Opportunity mayFound(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag d = data(id);
        if (d.contains("last") && d.getLong("last") > day) d.putLong("last", day - DAYS_BETWEEN);   // [econ-1c] a clock set back before it was kept
        if (d.contains("last") && day - d.getLong("last") < DAYS_BETWEEN) return null;
        int grown = 0;
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby()) grown++;
        if (open(id).size() + 1 > grown / FOLK_A_FIRM) return null;
        List<Opportunity> all = scan(level, v);
        ListTag tag = scanTag(all);
        Opportunity pick = null;
        CompoundTag unmet = Accounts.sub(d, "unmet");                        // [econ51] mornings each want went unmet for a founder
        for (Opportunity o : all) {
            if (o.profit7() <= 0.15 * o.startup() || o.profit7() <= 0) break;
            int ease = ease(unmet, o.type().key());
            VillageFolkEntity f = founderFor(id, o.type(), Math.round(o.startup() * 100), Math.round(o.profit7() * 100), ease);
            tag.add(StringTag.valueOf("  " + o.type().key() + " is worth opening: " + (f != null ? f.displayNameCap() + " (" + Temperament.words(f) + ") means to"
                : founders(id, o) + (ease > 0 ? "; waited " + unmet.getInt(o.type().key()) + " mornings" : ""))));
            if (f != null) { pick = o; break; }
            unmet.putInt(o.type().key(), unmet.getInt(o.type().key()) + 1);
        }
        d.put("scan", tag);
        Accounts.dirty();
        return pick;
    }

    /** [econ51] How far a want long unmet eases what is asked of a founder: a step each morning it went unmet, two at most. */
    static int ease(CompoundTag unmet, String type) {
        return Math.min(2, unmet.getInt(type));
    }

    /** Why nobody opens a business worth opening: how many have the skill, the nerve, the means. */
    static String founders(UUID village, Opportunity o) {
        int skilled = 0, bold = 0, means = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || barred(village, f, o.type()) != null) continue;
            skilled++;
            if (boldness(f) + Temperament.drive(f) < nerveFor(o.profit7(), o.startup())) continue;   // [econ51] its character
            bold++;
            boolean own = skilled(village, f, o.type().work());
            long need = Math.round(o.startup() * 100) + (own ? 0 : handStake(village, o.type()));
            if (founderMeans(village, f) >= OWN_LEAST && raisable(village, f, own ? null : skilledHand(village, o.type(), f)) * 100L >= shareFor(o.profit7(), o.startup()) * need) means++;   // [econ-1d]
        }
        return "nobody to open it (" + skilled + " free with the skill, " + bold + " of them bold enough, " + means + " with the means)";
    }

    static ListTag scanTag(List<Opportunity> all) {
        ListTag l = new ListTag();
        for (int i = 0; i < Math.min(8, all.size()); i++) l.add(StringTag.valueOf(all.get(i).words()));
        return l;
    }

    /** The founder says what it means to do (the morning before it does). */
    static void intend(ServerLevel level, Villages.Village v, Opportunity o, long day) {
        UUID id = v.id();
        VillageFolkEntity f = founderFor(id, o.type(), Math.round(o.startup() * 100), Math.round(o.profit7() * 100),
            ease(Accounts.sub(data(id), "unmet"), o.type().key()));
        if (f == null) return;
        CompoundTag in = new CompoundTag();
        in.putString("type", o.type().key());
        in.putUUID("founder", f.getUUID());
        in.putLong("day", day);
        data(id).put("intent", in);
        Accounts.dirty();
        String ware = Stockroom.plural(o.good()).toLowerCase(Locale.ROOT);
        int[] w = Stockroom.week7(level, id, o.good());
        String why = w[1] > 0 ? "there's no " + ware + " to be had" : "folk are crying out for " + ware;
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I've a mind to open a " + o.type().noun() + ": " + why + ".",
            "A " + o.type().noun() + " of my own, that's what this town wants. " + cap(why) + ".",
            "I've been doing my sums. A " + o.type().noun() + " would pay: " + why + "."));
        f.brain("means to open a " + o.type().noun() + " tomorrow: " + why + " (" + Temperament.words(f) + ")");
        LOG.info("[MCA-FIRM] {}: {} ({}) means to open a {}: {}", Villages.name(id), f.displayNameCap(), Temperament.words(f), o.type().noun(), why);
        f.persona().remember(day, "I made up my mind to open a " + o.type().noun(), 2);
    }

    /** Yesterday's intention: opened, if it still adds up. */
    static void carryOut(ServerLevel level, Villages.Village v, CompoundTag in, long day) {
        UUID id = v.id();
        BusinessType t = BusinessTypes.of(in.getString("type"));
        if (t == null || !in.hasUUID("founder")) return;
        VillageFolkEntity f = Homes.loaded(id, in.getUUID("founder"));
        if (f == null || barred(id, f, t) != null) return;
        Opportunity o = weigh(level, v, t, lists(level, id));
        if (o == null || o.profit7() <= 0.15 * o.startup()) {
            FolkTalk.speak(f, "I've thought better of that business. The sums don't add up after all.");
            return;
        }
        found(level, v, t, f, null, day, o);
    }

    // ------------------------------------------------------------------ founding

    /**
     * A business opened: its books, its owner and capital, its premises (a stall paid for, or a room let), its founder at
     * its work, its sign, its hands, the chronicle. {@code player}: a player's charter (Holder.PLAYER, no folk founder).
     * Returns the firm, or null if it could not be opened (no ground for its work, the money short).
     */
    @Nullable
    public static Business found(ServerLevel level, Villages.Village v, BusinessType t, @Nullable VillageFolkEntity founder,
                                 @Nullable net.minecraft.server.level.ServerPlayer player, long day, @Nullable Opportunity o) {
        UUID id = v.id();
        // [biz30] An owner opening another: it keeps to its first, and a hand of the branch's own keeps this one.
        int ownedBefore = founder == null ? 0 : FirmBranches.owned(id, founder.getUUID()).size();
        boolean branch = ownedBefore > 0;
        double inputs = o != null ? o.inputCost() : makings(level, id, t, Stockroom.sampleOf(t.wares().get(0)));
        long startup = startup(id, t, inputs, founder == null ? null : founder.getUUID(), o != null ? o.gap() * 0.5 / 7 : 1);
        // [econ-1c] A founder without the skill takes on one who has it, and puts up its first days' wages.
        VillageFolkEntity skilledHand = founder != null && !skilled(id, founder, t.work()) ? skilledHand(id, t, founder) : null;
        if (founder != null && !skilled(id, founder, t.work()) && skilledHand == null) return null;
        if (skilledHand != null) startup += handStake(id, t);
        // The founder to its work: already at it, or taken up (ground for the trade found), or not at all.
        if (founder != null && !branch && founder.stationTask() != t.work() && !founder.takeUpTrade(t.work())) {
            founder.brain("could find no ground for a " + t.noun() + ", and let the idea go");
            return null;
        }
        CompoundTag d = data(id);
        int seq = d.getInt("seq") + 1;
        d.putInt("seq", seq);
        String fid = "ent-" + seq;
        String first = founder != null ? firstName(founder.displayNameCap()) : player != null ? player.getGameProfile().getName() : "The Town";
        Business b = new Business(fid, Business.Type.ENTERPRISE, FirmBranches.nameFor(id, t.nameFor(first)));   // [biz30] "No. 2" for a second of the name
        b.opened = day;
        b.markup = 0.30;
        b.at = founder != null && !branch && founder.stationPos() != null ? founder.stationPos() : v.centre();
        Businesses.town(id).biz.put(fid, b);
        CompoundTag r = new CompoundTag();
        r.putString("type", t.key());
        r.putLong("day", day);
        if (founder != null) r.putUUID("founder", founder.getUUID());
        if (player != null) r.putUUID("player", player.getUUID());
        r.putInt("premises", 0);
        r.putDouble("rep", 0.5);
        r.putString("status", "new");
        if (b.at != null) r.putLong("at", b.at.asLong());
        firms(id).put(fid, r);
        // The capital: the founder's own (its purse, then its savings), the bank the rest it is short of.
        long raised = 0;
        if (founder != null) {
            long want = Math.min(startup, founderMeans(id, founder) * 100L);   // [econ-1d] (its homestead's spare too)
            int keep = Math.max(4, Math.min(RESERVE, 2 * Wealth.wage(founder)));   // [econ-1c] what it keeps by (means)
            int coins = (int) Math.min(founder.purse() - Math.min(founder.purse(), keep), (want + 99) / 100);
            if (coins < (want + 99) / 100 && Bank.open(id)) {
                int fromBank = (int) Math.min(Bank.balance(founder), (want + 99) / 100 - coins);
                if (fromBank > 0) Bank.withdraw(id, Bank.state(id), founder, fromBank);
                coins = (int) Math.min(founder.purse() - Math.min(founder.purse(), keep / 2), (want + 99) / 100);
            }
            if (coins < (want + 99) / 100 && FolkCredit.clubBalance(founder) > 0) {      // [capital] its savings at the club
                FolkCredit.drawOut(id, founder, (int) ((want + 99) / 100 - coins));
                coins = (int) Math.min(founder.purse() - Math.min(founder.purse(), keep / 2), (want + 99) / 100);
            }
            if (coins > 0 && founder.spend(coins)) raised = coins * 100L;
            if (raised < want) raised += drawFromHomestead(id, founder, want - raised, day);   // [econ-1d] the rest out of its homestead's till
            // [econ-1c] Short of the start-up: partners put in for a share (the founder keeps the most of it, at least half
            // and one part, for the idea and the work).
            List<Stake> partners = new ArrayList<>();
            List<Long> put = new ArrayList<>();
            if (raised < startup) {
                for (VillageFolkEntity p : partnersFor(id, founder, skilledHand)) {
                    if (raised >= startup) break;
                    int c = (int) Math.min(partnerMoney(p), (startup - raised + 99) / 100);
                    if (c > p.purse()) FolkCredit.drawOut(id, p, c - p.purse());   // [capital] its savings at the club
                    if (c <= 0 || !p.spend(c)) continue;
                    raised += c * 100L;
                    partners.add(new Stake(Holder.FOLK, p.getUUID(), p.displayNameCap(), 0));
                    put.add(c * 100L);
                    p.brain("put " + c + " coins into " + founder.displayNameCap() + "'s " + t.noun() + " for a share");
                    FolkTalk.speak(p, FolkTalk.pick(p.getRandom(), "I've put " + c + " coins into " + firstName(founder.displayNameCap()) + "'s " + t.noun() + ". We'll see!",
                        "A share in " + firstName(founder.displayNameCap()) + "'s " + t.noun() + "? Go on, then."));
                }
            }
            if (partners.isEmpty()) {
                b.owners.add(new Stake(Holder.FOLK, founder.getUUID(), founder.displayNameCap(), Business.WHOLE));
            } else {
                long all = Math.max(1, raised);
                int mine = (int) Math.max(Business.WHOLE / 2 + 1, Math.round(Business.WHOLE * (double) (raised - put.stream().mapToLong(Long::longValue).sum()) / all));
                b.owners.add(new Stake(Holder.FOLK, founder.getUUID(), founder.displayNameCap(), mine));
                long others = put.stream().mapToLong(Long::longValue).sum();
                int left = Business.WHOLE - mine;
                for (int i = 0; i < partners.size(); i++) {
                    int parts = i == partners.size() - 1 ? left : (int) Math.round((Business.WHOLE - mine) * (double) put.get(i) / Math.max(1, others));
                    left -= parts;
                    Stake s0 = partners.get(i);
                    b.owners.add(new Stake(s0.holder(), s0.who(), s0.name(), parts));
                }
            }
        } else if (player != null) {
            b.owners.add(new Stake(Holder.PLAYER, player.getUUID(), player.getGameProfile().getName(), Business.WHOLE));
        }
        b.till += raised;
        Business.Day bd = b.today(day);
        bd.capital += raised;
        b.allTime.capital += raised;
        long lent = 0;
        if (founder != null && raised < startup) lent = Businesses.bankLends(id, b, startup - raised);
        r.putLong("capital", raised + lent);
        // The premises: a stall is bought of the town (its fixtures the firm's own), a room let.
        if (t.stall()) {
            long stall = Math.round(STALL_PENCE * MarketMaker.priceLevel(id));
            CompoundTag stalls = Accounts.sub(d, "stalls");
            if (founder != null && stalls.getInt(founder.getUUID().toString()) > 0) {
                stalls.putInt(founder.getUUID().toString(), stalls.getInt(founder.getUUID().toString()) - 1);
                b.note(day, "set up on the stall " + founder.displayNameCap() + " already owned");
            } else {
                long paid = Businesses.payOut(level, id, b, stall, day);
                Businesses.toTreasury(id, paid);
                TownBudget.in(id, "fees", paid);
                bd.other += stall;
                b.allTime.other += stall;
            }
            r.putLong("fixtures", stall);
        }
        d.putLong("last", day);
        Accounts.dirty();
        Businesses.dirty();
        if (founder != null && branch) {                                     // [biz30] its owner stays at its first; a keeper for this
            r.putBoolean("branch", true);
            founder.brain("opened " + b.name + ", another " + t.noun() + " of its own");
            founder.persona().remember(day, "I opened " + b.name + ", my business number " + (ownedBefore + 1), 4);
            FolkTalk.speak(founder, FirmBranches.opened(b, t, ownedBefore + 1, t.stall()));
            if (skilledHand == null) hire(level, v, b, r, t);
        } else if (founder != null) {
            addStaff(id, b, r, founder);
            founder.brain("opened " + b.name + ", its own " + t.noun());
            founder.persona().remember(day, "I opened " + b.name, 4);
            FolkTalk.speak(founder, FolkTalk.pick(founder.getRandom(), b.name + " is open! Come and see.", "My own " + t.noun() + " at last. " + b.name + "!",
                "Open for trade: " + b.name + "."));
        }
        if (skilledHand != null && hireThis(level, v, b, r, t, skilledHand) != null) {
            b.note(day, skilledHand.displayNameCap() + " taken on for its skill at " + t.work().label + " (level " + skilledHand.tradeLevel(t.work()) + ")");
        }
        for (int i = 0; i < t.startStaff(); i++) hire(level, v, b, r, t);
        targets(level, id, b, r, lists(level, id));                         // [econ-1c] its shelf from its first day
        restock(level, id, b, r);
        String how = founder != null ? Businesses.pence(raised) + " of " + founder.displayNameCap() + "'s own" + (lent > 0 ? " and " + Businesses.pence(lent) + " from the bank" : "")
            : "a player's charter";
        b.note(day, "opened by " + b.ownersWords() + " as a " + t.noun() + ", with " + how + (o == null ? "" : String.format(Locale.ROOT,
            ": the town wanted %.0f %s a week and had %.0f", o.demand(), Stockroom.plural(o.good()).toLowerCase(Locale.ROOT), o.supply())));
        if (founder != null) {                                              // [econ51] who, and the character that did it
            r.putString("character", Temperament.words(founder));
            Accounts.sub(d, "unmet").remove(t.key());
            LOG.info("[MCA-FIRM] {}: {} ({}) opened {}, a {}", Villages.name(id), founder.displayNameCap(), r.getString("character"), b.name, t.noun());
        }
        Villages.tell(id, day, (founder != null ? founder.displayNameCap() + " (" + r.getString("character") + ")" : first) + " opened " + b.name + (t.stall() ? " on the square" : "")
            + ", a " + t.noun() + " of " + (founder != null ? "their" : "its") + " own");
        Accounts.add(d, "founded", 1);
        if (founder != null) FolkAction.mark(founder, FolkAction.Mark.IDEA);   // [anim] the bright idea over its head
        signs(level, v, b, r);
        Premises.linkFirm(level, id, b, true);                              // [wpb] WP-C's register of premises (a stub till then)
        return b;
    }

    /**
     * A player's charter: at the hall (or the town's heart, without one), a citizen or a friend of the town, ten coins to the
     * treasury; the firm is the player's, opened on a stall with nothing in its till, for the player to fund, staff and stock.
     */
    public static String charter(ServerLevel level, Villages.Village v, net.minecraft.server.level.ServerPlayer p, String kind) {
        BusinessType t = BusinessTypes.of(kind);
        if (t == null) return "No such kind of business. The kinds: " + String.join(", ", BusinessTypes.keys()) + ".";
        if (!Government.allowsPrivateFirms(v.id())) return "The state owns the firms in " + Villages.name(v.id()) + ": no private charter is granted here.";   // [wpb]
        UUID id = v.id();
        String planned = Regimes.businessBarred(id);                       // [featcivic] a planned state: the state runs them all
        if (planned != null) return planned;
        BlockPos hall = Villages.builtAt(id, "hall");
        BlockPos at = hall != null ? hall : v.centre();
        if (!p.blockPosition().closerThan(at, hall != null ? 24 : 48)) return "Charters are granted at " + (hall != null ? "the hall" : "the town's heart") + ": go there first.";
        if (!Citizens.is(id, p.getUUID()) && !Standing.of(id, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            return "Only a citizen or a friend of " + Villages.name(id) + " may open a business here.";
        }
        if (Market.coinsHeld(p) < CHARTER_FEE) return "The charter costs " + CHARTER_FEE + " coins; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, CHARTER_FEE);
        com.jrpetty.mcassistant.village.Ledger.addCoins(id, CHARTER_FEE);
        CoinCensus.playerIn(id, CHARTER_FEE, p, PlayerFinance.Cat.FEES, "a firm's charter");
        TownBudget.in(id, "fees", CHARTER_FEE * 100L);
        Business b = found(level, v, t, null, p, Businesses.today(level), null);
        if (b == null) return "The charter could not be drawn up.";
        return "Your charter is granted: " + b.name + ", a " + t.noun() + ". Run it from Your businesses (press U, then Your businesses): "   // [wpb] no commands
            + "put coin in its till (Money), take on a hand (Hands), order its makings (Stock) and set its prices (Prices).";
    }

    static String firstName(String n) {
        int sp = n.indexOf(' ');
        return sp > 0 ? n.substring(0, sp) : n;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * One more hand for a firm: one of the trade not yet any firm's, else one the town can spare (between trades, or from a
     * trade with hands over), taken up at the firm's work. Returns it, or null.
     */
    @Nullable
    static VillageFolkEntity hire(ServerLevel level, Villages.Village v, Business b, CompoundTag r, BusinessType t) {
        UUID id = v.id();
        index(id);
        VillageFolkEntity best = null;
        double bestScore = -1e9;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) continue;
            if (STAFF.containsKey(f.getUUID()) || b.ownedBy(f.getUUID()) || ownsAny(id, f.getUUID())) continue;
            if (TownJobs.busy(f)) continue;
            if (Hospitality.keepsTrade(f)) continue;                        // [biz30] not the barkeep from behind its bar (live Ravenscar: a new bakery took the tavern's only barkeep)
            StationTask now = f.stationTask();
            double s;
            if (now == t.work()) s = 100;
            else if (now == StationTask.NONE) s = 60;
            else if (now == StationTask.GUARD || now == StationTask.STORE || now == StationTask.BANK || now == StationTask.SCOUT) continue;
            else if (Villages.overStaffed(id, now)) s = 30;
            else continue;
            s += f.tradeLevel(t.work());
            if (s > bestScore) { bestScore = s; best = f; }
        }
        if (best == null) LabourMarket.firmShort(id, b);                    // [labour] nobody to be had: an opening posted on the Jobs page
        return best == null ? null : hireThis(level, v, b, r, t, best);
    }

    /** This one taken on as a hand at the firm's work. Returns it, or null (no ground for the work). */
    @Nullable
    static VillageFolkEntity hireThis(ServerLevel level, Villages.Village v, Business b, CompoundTag r, BusinessType t, VillageFolkEntity best) {
        UUID id = v.id();
        if (best.stationTask() != t.work() && !best.takeUpTrade(t.work())) return null;
        LabourMarket.beforeTakenOn(id, b, best);                            // [labour] off another firm's books first
        addStaff(id, b, r, best);
        LabourMarket.takenOn(level, v, b, best);                            // [labour] its terms: the firm's offer, or outbidding its employer
        long day = Businesses.today(level);
        b.note(day, best.displayNameCap() + " taken on as a hand");
        best.brain("taken on at " + b.name);
        FolkTalk.speak(best, FolkTalk.pick(best.getRandom(), "I'm working at " + b.name + " now. Steady wages!", "Taken on at " + b.name + ". Best get to it."));
        Villages.tell(id, day, best.displayNameCap() + " was taken on at " + b.name);
        return best;
    }

    /** Hands who died, left town or left the firm's work are off its books (the founder stays its owner). */
    static void tidyStaff(ServerLevel level, Villages.Village v, Business b, CompoundTag r) {
        BusinessType t = BusinessTypes.of(r.getString("type"));
        for (UUID u : staffOf(r)) {
            VillageFolkEntity f = Homes.loaded(v.id(), u);
            if (f == null) {
                if (Villages.folkOf(v.id()).stream().noneMatch(a -> a.getUUID().equals(u))) removeStaff(v.id(), r, u);
                continue;
            }
            if (!f.isAlive() || t != null && f.stationTask() != t.work()) { removeStaff(v.id(), r, u); continue; }
            // [econ51] A quarrelsome owner's hands walk out on it now and then (its founder, the owner, stays), and it takes on
            // another, new to the bench.
            if (r.hasUUID("founder") && !u.equals(r.getUUID("founder")) && t != null && level.getRandom().nextDouble() < Temperament.quitChance(v.id(), b)) {
                removeStaff(v.id(), r, u);
                f.brain("walked out of " + b.name + ": no working for that one");
                long day = Businesses.today(level);
                VillageFolkEntity boss = Temperament.owner(v.id(), b);
                b.note(day, f.displayNameCap() + " walked out" + (boss == null ? "" : " on " + boss.displayNameCap() + " (" + Temperament.words(boss) + ")"));
                Accounts.add(r, "quits", 1);
                hire(level, v, b, r, t);
            }
        }
    }

    // ------------------------------------------------------------------ competition

    /** A firm's share of what its wares sold in the town over the week (by units: every counter and every firm). */
    public static double shareOf(ServerLevel level, UUID village, Business b) {
        BusinessType t = kindOf(village, b);
        if (t == null) return 0;
        long day = Businesses.today(level);
        Business.Day w = b.last(day, 7);
        double mine = 0, all = 0;
        for (String key : t.wares()) {
            Business.Line l = w.lines.get(key);
            mine += l == null ? 0 : Math.max(0, l.units);
            all += Stockroom.week7(level, village, key)[0] + firmsSold7(level, village, key);
        }
        return all <= 0 ? 0 : mine / all;
    }

    /** The share's books, its reputation, and a markup cut for a firm long under a tenth of its market. */
    static void share(ServerLevel level, UUID village, Business b, CompoundTag r, long day) {
        double s = shareOf(level, village, b);
        r.putDouble("share", s);
        // Its reputation: its hands' skill, its age, its stock-outs.
        BusinessType t = kindOf(village, b);
        double skill = 0;
        List<VillageFolkEntity> hands = hands(village, b);
        for (VillageFolkEntity f : hands) skill += t == null ? 0 : f.tradeLevel(t.work());
        skill = hands.isEmpty() ? 0 : skill / hands.size();
        int outs = r.getInt("outs");
        double rep = 0.35 + 0.25 * Math.min(1, skill / 20.0) + 0.2 * Math.min(1, (day - b.opened) / 28.0) - 0.03 * Math.min(10, outs);
        r.putDouble("rep", Math.max(0, Math.min(1, rep)));
        r.putInt("outs", Math.max(0, outs - 1));
        if (day - b.opened < 2) return;
        // [econ-1d] Sold out again and again, with a good share of its market: more call for its wares than it can make, and its
        // price goes up a little (a twentieth on its markup, every third day at most), as a busy shop's would.
        if (outs >= 3 && s >= 3 * LOW_SHARE && b.markup < Businesses.MOST_MARKUP && day - r.getLong("raised") >= 3) {
            double was = b.markup;
            b.markup = Math.min(Businesses.MOST_MARKUP, b.markup + 0.05);
            r.putLong("raised", day);
            b.note(day, "prices up: markup " + Businesses.pct(was) + " -> " + Businesses.pct(b.markup) + ", sold out " + outs
                + " times with " + Businesses.pct(s) + " of its market: more wanted than it can make");
        }
        if (s < LOW_SHARE) r.putInt("lowShare", r.getInt("lowShare") + 1);
        else r.putInt("lowShare", 0);
        if (r.getInt("lowShare") >= LOW_SHARE_DAYS && b.markup > Businesses.LEAST_MARKUP) {
            double was = b.markup;
            b.markup = Math.max(Businesses.LEAST_MARKUP, b.markup - 0.05);
            r.putInt("lowShare", 0);
            b.note(day, "prices down: markup " + Businesses.pct(was) + " -> " + Businesses.pct(b.markup) + ", its share of the market "
                + Businesses.pct(s) + " for a week: the folk were buying elsewhere");
            Accounts.add(r, "cuts", 1);
        }
        Accounts.dirty();
    }

    /** A stock-out: a buyer came for its ware and it had none (Sellers). */
    static void stockOut(UUID village, Business b) {
        CompoundTag r = rec(village, b.id);
        if (r != null) r.putInt("outs", r.getInt("outs") + 1);
    }

    /** What it holds over five days' sales (and a few) sold to the market at the bid: a firm does not sit on its stock. */
    static void sellOver(ServerLevel level, UUID village, Business b, long day) {
        if (!MarketMaker.live(village)) return;
        if (day - b.opened < 7) return;                                    // [econ-1d] a new firm's shelf given its first week to sell
        Business.Day w = b.last(day - 1, 7);
        for (Map.Entry<String, Lot> e : new ArrayList<>(b.stock.entrySet())) {
            Lot lot = e.getValue();
            if (lot.qty <= 0) continue;
            Business.Line l = w.lines.get(e.getKey());
            long sold = l == null ? 0 : Math.max(0, l.units - l.made);
            long keep = Math.max(4, sold * 5 / 7);
            long over = lot.qty - keep;
            if (over < 4) continue;
            ItemStack one = Stockroom.sampleOf(e.getKey());
            if (one.isEmpty()) continue;
            if (Market.stock(level, village, Stockroom.matcher(e.getKey())) < over) continue;   // not there to sell
            long cost = lot.take(over);
            long price = MarketMaker.buy(level, village, Accounts.Account.firm(b), one, over, "sold over to the market");
            Business.Day d = b.today(day);
            d.cogs += cost;
            b.allTime.cogs += cost;
            Business.Line dl = d.line(e.getKey());
            dl.cogs += cost;
            dl.made -= over;                                                   // (the market's books say bought: not made here)
            Business.Line al = b.allTime.line(e.getKey());
            al.cogs += cost;
            al.made -= over;
            if (price > 0) b.note(day, "sold " + over + " " + Stockroom.plural(e.getKey()).toLowerCase(Locale.ROOT) + " over to the market for "
                + Businesses.pence(price) + " (they had cost " + Businesses.pence(cost) + ")");
        }
    }

    // ------------------------------------------------------------------ fortunes

    /** What it is worth, pennies: its till, what the town owes it, its stock at cost, less its loan and what it owes. */
    public static long equity(UUID village, Business b) {
        return b.till + b.account + b.stockCost() + Capital.value(village, b) - b.loan - Accounts.owed(village, Accounts.Account.firm(b));   // [econ-1c] its kit
    }

    /** Thriving, struggling, bankrupt, giving up. Returns true if it closed. */
    static boolean fortunes(ServerLevel level, Villages.Village v, Business b, CompoundTag r, long day) {
        UUID id = v.id();
        Business.Day w = b.last(day - 1, 7);
        long net = w.net();
        long eq = equity(id, b);
        long capital = Math.max(500, r.getLong("capital"));
        // Cash gone, credit gone.
        boolean broke = b.till < 100 && b.account <= -Businesses.creditLimit(b, day);
        r.putInt("broke", broke ? r.getInt("broke") + 1 : 0);
        r.putInt("under", eq < 0 ? r.getInt("under") + 1 : 0);
        boolean player = BizGuide.playerOwned(b);                           // [biz25] a player's new firm: a grace, and a warning first
        if (player) BizGuide.firmWarning(level, id, b, r, day);
        int stay = Temperament.staying(id, b);                              // [econ51] its owner's character: given longer, or less
        if (day - b.opened >= (player ? BizGuide.FIRM_GRACE : 3) && (r.getInt("broke") >= Math.max(2, BROKE_DAYS + stay) || r.getInt("under") >= Math.max(1, UNDER_DAYS + stay))) {
            // [econ-1c] Its creditors take it to the court, which hears it at its next sitting (hear).
            petition(level, id, b, r, day, r.getInt("broke") >= BROKE_DAYS ? "its cash and its credit gone" : "it owed more than it was worth");
            return true;
        }
        // Thriving: a quarter of its capital a week, the want still unmet.
        boolean good = day - b.opened >= 7 && net > GOOD_RETURN * capital;
        r.putInt("good", good ? r.getInt("good") + 1 : 0);
        String status = good ? "thriving" : net < 0 && day - b.opened >= 3 ? "struggling" : day - b.opened < 7 ? "new" : "trading";
        r.putString("status", status);
        if (r.getInt("good") >= GOOD_DAYS) {
            r.putInt("good", 0);
            BusinessType t = kindOf(id, b);
            Opportunity o = t == null ? null : weigh(level, v, t, lists(level, id));
            if (o != null && o.gap() > 0) expand(level, v, b, r, t, day);
        }
        // Giving up: its return under what the bank pays on savings, four weeks.
        double weekly = capital <= 0 ? 0 : net / (double) Math.max(capital, Math.max(1, eq));
        boolean poor = day - b.opened >= 7 && weekly < Bank.DEPOSIT_BP / 10000.0;
        r.putInt("poor", poor ? r.getInt("poor") + 1 : 0);
        if (r.getInt("poor") >= POOR_DAYS) {
            windUp(level, id, b, day, "it paid its owner less than money earns at the bank");
            return true;
        }
        Accounts.dirty();
        return false;
    }

    /** Thriving: another hand; then larger premises; then a second branch. */
    static void expand(ServerLevel level, Villages.Village v, Business b, CompoundTag r, BusinessType t, long day) {
        UUID id = v.id();
        int stage = r.getInt("stage");
        if (stage == 0) {
            VillageFolkEntity f = hire(level, v, b, r, t);
            if (f != null) {
                r.putInt("stage", 1);
                Villages.tell(id, day, b.name + " is doing well: it took on " + f.displayNameCap());
            }
        } else if (stage == 1) {
            r.putInt("stage", 2);
            r.putInt("premises", 1);
            b.note(day, "moved to larger premises: trade is good");
            Villages.tell(id, day, b.name + " is doing well: it moved to larger premises");
            hire(level, v, b, r, t);
        } else if (stage == 2 && r.hasUUID("founder")) {
            VillageFolkEntity owner = Homes.loaded(id, r.getUUID("founder"));
            if (owner == null) return;
            r.putInt("stage", 3);
            Business branch = branch(level, v, b, t, owner, day);
            if (branch != null) Villages.tell(id, day, owner.displayNameCap() + " opened a second " + t.noun() + ", " + branch.name);
        }
        Accounts.dirty();
    }

    /** A second branch: a new firm of the same owner, its capital out of the first's till, a hand taken on to work it. */
    @Nullable
    static Business branch(ServerLevel level, Villages.Village v, Business first, BusinessType t, VillageFolkEntity owner, long day) {
        UUID id = v.id();
        long capital = Math.min(first.till / 2, 3000);
        if (capital < 500) return null;
        CompoundTag d = data(id);
        int seq = d.getInt("seq") + 1;
        d.putInt("seq", seq);
        String fid = "ent-" + seq;
        Business b = new Business(fid, Business.Type.ENTERPRISE, first.name + " (the second)");
        b.opened = day;
        b.markup = first.markup;
        b.at = first.at;
        b.owners.add(new Stake(Holder.FOLK, owner.getUUID(), owner.displayNameCap(), Business.WHOLE));
        first.till -= capital;
        b.till += capital;
        b.today(day).capital += capital;
        b.allTime.capital += capital;
        Businesses.town(id).biz.put(fid, b);
        CompoundTag r = new CompoundTag();
        r.putString("type", t.key());
        r.putLong("day", day);
        r.putUUID("founder", owner.getUUID());
        r.putString("branchOf", first.id);
        r.putLong("capital", capital);
        r.putString("status", "new");
        firms(id).put(fid, r);
        first.note(day, "put " + Businesses.pence(capital) + " into a second branch, " + b.name);
        if (hire(level, v, b, r, t) == null) b.note(day, "opened with nobody to work it yet");
        Businesses.dirty();
        Accounts.dirty();
        return b;
    }

    /**
     * Bankrupt: stock to the market at the bid, the stall at auction, the till and what they brought to its creditors in
     * order (wages owed, taxes and rent owed the town, the market's trade credit, the bank), a sole trader's purse taken for
     * what is left down to six coins, its hands laid off, its premises given back, and the chronicle told why.
     *
     * <p>[econ-1c] Declared at a hearing (hear): the leader's court at the hall, or the council on the square. Where the town
     * holds its auction on market day (Auctions), the stall goes under the hammer there with the town's other lots, and
     * what it brings pays the creditors still owed, in the same order (stallSold); a town too small for an auction sells it
     * there and then to the folk with the means, as before.
     */
    public static void bankrupt(ServerLevel level, UUID village, Business b, long day, String cause) {
        bankrupt(level, village, b, day, cause, null);
    }

    public static void bankrupt(ServerLevel level, UUID village, Business b, long day, String cause, @Nullable String heardBy) {
        CompoundTag r = rec(village, b.id);
        if (r == null || r.getBoolean("closed")) return;
        String why = whyItFailed(level, village, b, cause);
        r.remove("petition");
        Premises.onBankrupt(level, village, b);                             // [wpb] WP-C: its firm-held premises to its creditors first
        // The stock, to the market at its bid (or the stores at cost where the market is not open).
        long fromStock = liquidate(level, village, b, day);
        long fromKit = FirmKit.sell(level, village, b, day);              // [econ-1d] its kit sold to the market, not written off
        // The stall: at the town's auction on market day, or (a town with no auction) to the folk with the means now.
        boolean toAuction = r.getLong("fixtures") > 0 && Auctions.holds(village);
        long fromStall = toAuction ? 0 : auctionStall(level, village, b, r, day);
        // A sole trader answers with its purse, down to six coins.
        long fromPurse = 0;
        VillageFolkEntity sole = soleTrader(village, b);
        long owing = owing(village, b, r);
        if (sole != null && owing > b.till) {
            int coins = (int) Math.min(Math.max(0, sole.purse() - PURSE_LEFT), (owing - b.till + 99) / 100);
            if (coins > 0 && sole.spend(coins)) {
                fromPurse = coins * 100L;
                b.till += fromPurse;
            }
        }
        // The creditors, in order: their claims taken over by the receiver, paid as far as the till runs.
        ListTag claims = claims(village, b, r);
        List<String> paid = new ArrayList<>(), unpaid = new ArrayList<>();
        payClaims(village, b, claims, paid);
        for (Tag t : claims) unpaid.add(claimWords((CompoundTag) t));
        if (!toAuction) FolkCredit.writeOffClaims(village, b, claims, day);   // [capital] what the lender is never paid: a default
        Capital.refunded(village, b, Capital.value(village, b));          // [econ-1c] its kit goes with its hands (written off)
        if (toAuction) {
            CompoundTag rc = new CompoundTag();
            rc.putLong("worth", r.getLong("fixtures"));
            rc.put("claims", claims);
            rc.putLong("day", day);
            r.put("receiver", rc);
            r.putLong("fixtures", 0);
            if (b.till > 0) returnToOwners(village, b);                   // (odd pennies the wages could not take)
        } else {
            returnToOwners(village, b);                                   // what is left (if anything), to its owners
        }
        close(level, village, b, r, day, "bankrupt");
        Bourse.wiped(village, b, day);                                     // [capital] its shareholders wiped out, after its creditors
        String court = heardBy == null ? "" : " (heard " + heardBy + ")";
        b.note(day, "bankrupt" + court + " (" + cause + "): stock " + Businesses.pence(fromStock) + (fromKit > 0 ? ", kit " + Businesses.pence(fromKit) : "") + ", stall " + (toAuction ? "to the auction on market day"
            : Businesses.pence(fromStall)) + (fromPurse > 0 ? ", " + Businesses.pence(fromPurse) + " of its owner's purse" : "") + "; paid "
            + (paid.isEmpty() ? "nobody" : String.join(", ", paid)) + (unpaid.isEmpty() ? "" : toAuction ? "; still owed: " + String.join(", ", unpaid)
            : "; written off: " + String.join(", ", unpaid)));
        r.putString("closedWhy", why);
        r.putString("creditors", "paid " + (paid.isEmpty() ? "nobody" : String.join(", ", paid)) + (unpaid.isEmpty() ? ""
            : toAuction ? "; owed " + String.join(", ", unpaid) + " till its stall is sold" : "; written off " + String.join(", ", unpaid)));
        if (heardBy != null) r.putString("heard", heardBy);
        Villages.tell(village, day, b.name + " closed: " + why + (heardBy == null ? "" : "; " + heardBy + " declared it bankrupt")
            + (toAuction ? "; its stall goes under the hammer at the next market day's auction" : ""));
        Accounts.add(data(village), "failed", 1);
        if (sole != null) {
            FolkTalk.speak(sole, FolkTalk.pick(sole.getRandom(), "That's the end of " + b.name + ". I'll not try that again in a hurry.",
                b.name + " is finished. " + cap(why) + "."));
            sole.persona().remember(day, b.name + " went bankrupt", -5);
        }
    }

    /**
     * [econ-1c] A bankrupt's creditors' claims, in the order they are paid (the wages owed its hands, what it owes the town,
     * the market's trade credit, the bank's loan), taken over by the receiver: the firm's own books cleared of them.
     */
    static ListTag claims(UUID village, Business b, CompoundTag r) {
        ListTag out = new ListTag();
        CompoundTag wages = Accounts.sub(r, "owedWages");
        for (String k : new ArrayList<>(wages.getAllKeys())) {
            if (wages.getLong(k) > 0) out.add(claim("wages", k, wages.getLong(k)));
            wages.remove(k);
        }
        if (b.account < 0) out.add(claim("town", "", -b.account));
        b.account = 0;
        long market = Accounts.owed(village, Accounts.Account.firm(b));
        if (market > 0) out.add(claim("market", "", market));
        Accounts.forgive(village, Accounts.Account.firm(b));
        if (b.loan > 0) out.add(claim("bank", b.lender, b.loan));        // [capital] who lent it (the bank, the savings club)
        b.loan = 0;
        b.lender = "";
        Businesses.dirty();
        return out;
    }

    static CompoundTag claim(String kind, String who, long amt) {
        CompoundTag c = new CompoundTag();
        c.putString("kind", kind);
        c.putString("who", who);
        c.putLong("amt", amt);
        return c;
    }

    static String claimWords(CompoundTag c) {
        String kind = c.getString("kind");
        String to = switch (kind) {
            case "wages" -> "wages";
            case "town" -> "the town";
            case "market" -> "the market";
            default -> "club".equals(c.getString("who")) ? "the savings club" : "the bank";   // [capital]
        };
        return to + " " + Businesses.pence(c.getLong("amt"));
    }

    /** The claims paid in order out of the till as far as it runs: what is paid comes off them, the paid ones off the list. */
    static void payClaims(UUID village, Business b, ListTag claims, List<String> paid) {
        for (int i = 0; i < claims.size(); i++) {
            CompoundTag c = claims.getCompound(i);
            long due = c.getLong("amt"), pay;
            String kind = c.getString("kind");
            switch (kind) {
                case "wages" -> {
                    pay = Math.min(due, b.till / 100 * 100);
                    VillageFolkEntity f = null;
                    try { f = Homes.loaded(village, UUID.fromString(c.getString("who"))); } catch (IllegalArgumentException ignored) { }
                    if (f == null) pay = 0;
                    if (pay > 0) {
                        f.earn((int) (pay / 100));
                        b.till -= pay;
                        paid.add("wages " + Businesses.pence(pay));
                    }
                }
                case "town" -> {
                    pay = Math.min(due, b.till);
                    if (pay > 0) {
                        b.till -= pay;
                        Businesses.toTreasury(village, pay);
                        paid.add("the town " + Businesses.pence(pay));
                    }
                }
                case "market" -> {
                    pay = Math.min(due, b.till);
                    if (pay > 0) {
                        b.till -= pay;
                        MarketMaker.cashIn(village, pay);
                        paid.add("the market " + Businesses.pence(pay));
                    }
                }
                default -> {
                    int coins = (int) (Math.min(due, b.till) / 100);
                    pay = coins * 100L;
                    if (coins > 0) {
                        FolkCredit.claimPaid(village, c.getString("who"), coins);   // [capital] the bank, or the savings club
                        b.till -= pay;
                        paid.add(("club".equals(c.getString("who")) ? "the savings club " : "the bank ") + coins + "c");
                    }
                }
            }
            c.putLong("amt", due - pay);
        }
        claims.removeIf(t -> ((CompoundTag) t).getLong("amt") <= 0);
        Businesses.dirty();
        Accounts.dirty();
    }

    // ------------------------------------------------------------------ [econ-1c] the hearing and the receiver

    /**
     * Insolvent: its creditors take it to the court. It trades no more (it is frozen), and its case is heard at the next
     * sitting: the leader's court at the hall that morning (Court), or, where there is none or it did not sit, the council
     * on the square the next morning.
     */
    static void petition(ServerLevel level, UUID village, Business b, CompoundTag r, long day, String cause) {
        if (r.contains("petition")) return;
        r.putString("petition", cause);
        r.putLong("petitionDay", day);
        r.putString("status", "before the court");
        b.note(day, "its creditors took it to the court: " + cause);
        Villages.tell(village, day, b.name + "'s creditors took it to the court: " + cause);
        Accounts.dirty();
    }

    /** Its creditors to the court (Businesses.review's winding up of a firm). */
    static void petitionFor(ServerLevel level, UUID village, Business b, long day, String cause) {
        CompoundTag r = rec(village, b.id);
        if (r == null) bankrupt(level, village, b, day, cause);
        else petition(level, village, b, r, day, cause);
    }

    /** The case heard: declared bankrupt, by whoever sat. */
    static void hear(ServerLevel level, UUID village, Business b, CompoundTag r, long day, String heardBy) {
        String cause = r.getString("petition");
        bankrupt(level, village, b, day, cause.isEmpty() ? "insolvent" : cause, heardBy);
    }

    /** The leader's court has sat down at the hall (Court.hold): the bankruptcies before it heard. */
    public static void hearings(ServerLevel level, UUID village, VillageFolkEntity judge) {
        long day = Businesses.today(level);
        for (Business b : open(village)) {
            CompoundTag r = rec(village, b.id);
            if (r == null || !r.contains("petition")) continue;
            hear(level, village, b, r, day, "at the hall by " + judge.displayNameCap());
            FolkTalk.speak(judge, "The court finds " + b.name + " bankrupt. Its stock to the market, its creditors paid in their order.");
        }
    }

    /** One stall in the receiver's hands, for the auction: the firm, its name, what its fixtures are worth (coins). */
    public record StallLot(String firm, String name, int worth) {}

    /** The stalls of bankrupt firms waiting for the auction (Auctions.open). */
    public static List<StallLot> stallLots(UUID village) {
        List<StallLot> out = new ArrayList<>();
        CompoundTag fs = firms(village);
        for (String id : fs.getAllKeys()) {
            CompoundTag r = fs.getCompound(id);
            if (!r.contains("receiver", Tag.TAG_COMPOUND)) continue;
            Business b = Businesses.of(village, id);
            out.add(new StallLot(id, b == null ? id : b.name, (int) Math.max(1, r.getCompound("receiver").getLong("worth") / 100)));
        }
        return out;
    }

    /** A bankrupt's stall sold at auction: the coin (already out of the winner's purse) to the receiver, its creditors still
     *  owed paid in order, the rest to its owners; a folk winner owns a stall to set up on. */
    public static void stallSold(ServerLevel level, UUID village, String firm, int coins, @Nullable VillageFolkEntity winner, String winnerName) {
        stallSold(level, village, firm, coins, winner == null ? null : winner.getUUID(), winnerName);
    }

    /** A player won it: its held bid to the receiver; the stall its own (for a charter: Enterprise.charter). */
    public static void stallSoldToPlayer(ServerLevel level, UUID village, String firm, int coins, UUID player, String name) {
        stallSold(level, village, firm, coins, player, name);
    }

    /** Is this folk an owner of the firm a stall is of? */
    public static boolean ownedStall(VillageFolkEntity f, String firm) {
        Business b = f.ownerId() == null ? null : Businesses.of(f.ownerId(), firm);
        return b != null && b.ownedBy(f.getUUID());
    }

    static void stallSold(ServerLevel level, UUID village, String firm, int coins, @Nullable UUID winner, String winnerName) {
        Business b = Businesses.of(village, firm);
        CompoundTag r = rec(village, firm);
        if (b == null || r == null || !r.contains("receiver", Tag.TAG_COMPOUND)) {
            Businesses.toTreasury(village, coins * 100L);                // (nobody to have it: the town's)
            return;
        }
        long day = Businesses.today(level);
        CompoundTag rc = r.getCompound("receiver");
        ListTag claims = rc.getList("claims", Tag.TAG_COMPOUND);
        b.till += coins * 100L;
        List<String> paid = new ArrayList<>();
        payClaims(village, b, claims, paid);
        List<String> off = new ArrayList<>();
        for (Tag t : claims) off.add(claimWords((CompoundTag) t));
        FolkCredit.writeOffClaims(village, b, claims, day);                // [capital]
        returnToOwners(village, b);
        r.remove("receiver");
        if (winner != null) {
            CompoundTag stalls = Accounts.sub(data(village), "stalls");
            stalls.putInt(winner.toString(), stalls.getInt(winner.toString()) + 1);
        }
        String words = "its stall sold at auction to " + winnerName + " for " + coins + " coins: paid " + (paid.isEmpty() ? "nobody" : String.join(", ", paid))
            + (off.isEmpty() ? "" : "; written off " + String.join(", ", off));
        b.note(day, words);
        r.putString("creditors", r.getString("creditors") + "; " + words);
        Villages.tell(village, day, b.name + "'s stall was sold at auction to " + winnerName + " for " + coins + " coins, for its creditors");
        Accounts.dirty();
        Businesses.dirty();
    }

    /** No bid for a bankrupt's stall: tried again next market day; after two, it goes back to the town, its creditors'
     *  claims written off. */
    public static void stallUnsold(ServerLevel level, UUID village, String firm) {
        CompoundTag r = rec(village, firm);
        Business b = Businesses.of(village, firm);
        if (r == null || b == null || !r.contains("receiver", Tag.TAG_COMPOUND)) return;
        CompoundTag rc = r.getCompound("receiver");
        rc.putInt("tries", rc.getInt("tries") + 1);
        if (rc.getInt("tries") < 2) return;
        List<String> off = new ArrayList<>();
        for (Tag t : rc.getList("claims", Tag.TAG_COMPOUND)) off.add(claimWords((CompoundTag) t));
        r.remove("receiver");
        long day = Businesses.today(level);
        FolkCredit.writeOffClaims(village, b, rc.getList("claims", Tag.TAG_COMPOUND), day);   // [capital]
        String words = "its stall found no buyer at two auctions and went back to the town" + (off.isEmpty() ? "" : "; written off " + String.join(", ", off));
        b.note(day, words);
        r.putString("creditors", r.getString("creditors") + "; " + words);
        Villages.tell(village, day, b.name + "'s stall found no buyer and went back to the town");
        Accounts.dirty();
    }
    /** Wound up by its owner: stock sold, debts paid, what is left its owner's. */
    static void windUp(ServerLevel level, UUID village, Business b, long day, String why) {
        CompoundTag r = rec(village, b.id);
        if (r == null) return;
        liquidate(level, village, b, day);
        FirmKit.sell(level, village, b, day);                             // [econ-1d] its kit sold, not left with its hands
        auctionStall(level, village, b, r, day);
        if (b.account < 0) {
            long pay = Math.min(-b.account, b.till);
            b.till -= pay;
            Businesses.toTreasury(village, pay);
            b.account += pay;
        }
        long market = Accounts.owed(village, Accounts.Account.firm(b));
        if (market > 0) {
            long pay = Math.min(market, b.till);
            b.till -= pay;
            MarketMaker.cashIn(village, pay);
            Accounts.forgive(village, Accounts.Account.firm(b));
        }
        if (b.loan > 0) {
            int coins = (int) Math.min(b.loan, b.till) / 100;
            if (coins > 0) FolkCredit.claimPaid(village, b.lender, coins);   // [capital] the bank, or the savings club
            b.till -= coins * 100L;
            b.loan = 0;
        }
        returnToOwners(village, b);
        close(level, village, b, r, day, "closed");
        r.putString("closedWhy", why);
        b.note(day, "wound up by its owner: " + why);
        Villages.tell(village, day, b.ownersWords() + " closed " + b.name + ": " + why);
        Accounts.add(data(village), "closedByChoice", 1);
    }

    /** The stock sold: to the market at the bid (to the stores at cost before the market is open). Returns the pennies. */
    static long liquidate(ServerLevel level, UUID village, Business b, long day) {
        long got = 0;
        for (Map.Entry<String, Lot> e : new ArrayList<>(b.stock.entrySet())) {
            Lot lot = e.getValue();
            if (lot.qty <= 0) continue;
            ItemStack one = Stockroom.sampleOf(e.getKey());
            long n = lot.qty;
            long cost = lot.take(n);
            if (one.isEmpty()) continue;
            if (MarketMaker.live(village)) {
                long before = b.till;
                MarketMaker.buy(level, village, Accounts.Account.firm(b), one, n, "a bankrupt's stock");   // its takings booked there
                got += b.till - before;
                Business.Day d = b.today(day);
                d.cogs += cost;
                b.allTime.cogs += cost;
            } else {
                Businesses.storesIn(village, one, n, cost);
                long p = MarketMaker.outOrTreasury(village, cost);
                b.till += p;
                got += p;
                // [biz30] the takings booked with their cost (the till had the coin and the books only the cost: they parted)
                Business.Day d = b.today(day);
                d.cogs += cost;
                b.allTime.cogs += cost;
                d.revenue += p;
                b.allTime.revenue += p;
            }
        }
        b.stock.clear();
        Businesses.dirty();
        return got;
    }

    /** The stall's fixtures at auction: the folk with the most means bids up to four fifths of their worth. Returns the pennies. */
    static long auctionStall(ServerLevel level, UUID village, Business b, CompoundTag r, long day) {
        long worth = r.getLong("fixtures");
        if (worth <= 0) return 0;
        r.putLong("fixtures", 0);
        VillageFolkEntity best = null;
        int bid = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || b.ownedBy(f.getUUID())) continue;
            int can = Math.min(f.purse() / 2, (int) (worth * 4 / 5 / 100));
            if (can > bid) { bid = can; best = f; }
        }
        if (best == null || bid < Math.max(1, worth / 400)) return 0;
        if (!best.spend(bid)) return 0;
        b.till += bid * 100L;
        CompoundTag stalls = Accounts.sub(data(village), "stalls");
        stalls.putInt(best.getUUID().toString(), stalls.getInt(best.getUUID().toString()) + 1);
        Villages.tell(village, day, best.displayNameCap() + " bought " + b.name + "'s stall at auction for " + bid + " coins");
        Accounts.dirty();
        return bid * 100L;
    }

    /** What a firm owes all told, pennies: wages, the town, the market, the bank. */
    static long owing(UUID village, Business b, CompoundTag r) {
        long n = 0;
        CompoundTag w = r.getCompound("owedWages");
        for (String k : w.getAllKeys()) n += w.getLong(k);
        return n + Math.max(0, -b.account) + Accounts.owed(village, Accounts.Account.firm(b)) + b.loan;
    }

    @Nullable
    static VillageFolkEntity soleTrader(UUID village, Business b) {
        if (b.owners.size() != 1 || b.owners.get(0).holder() != Holder.FOLK || b.owners.get(0).who() == null) return null;
        return Homes.loaded(village, b.owners.get(0).who());
    }

    /** What is left in the till, to its owners by their stakes (a player's kept for it to take). */
    static void returnToOwners(UUID village, Business b) {
        long left = b.till;
        if (left <= 0) return;
        for (Stake s : b.owners) {
            long share = left * s.parts() / Business.WHOLE / 100 * 100;
            if (share <= 0) continue;
            if (s.holder() == Holder.FOLK && s.who() != null) {
                VillageFolkEntity f = Homes.loaded(village, s.who());
                if (f != null) { f.earn((int) (share / 100)); b.till -= share; }
            } else if (s.holder() == Holder.PLAYER && s.who() != null) {
                b.ownersDue.merge(s.who(), share, Long::sum);
                b.till -= share;
            }
        }
        Businesses.toTreasury(village, b.till);
        b.till = 0;
    }

    /** Closed: its hands laid off, its premises back, its sign taken down, its books kept. */
    static void close(ServerLevel level, UUID village, Business b, CompoundTag r, long day, String status) {
        Premises.linkFirm(level, village, b, false);                        // [wpb] WP-C's register: its premises released
        for (UUID u : staffOf(r)) {
            VillageFolkEntity f = Homes.loaded(village, u);
            if (f != null && !b.ownedBy(u)) {
                f.brain("laid off: " + b.name + " closed");
                FolkTalk.speak(f, "Laid off. " + b.name + " has shut its doors.");
            }
            STAFF.remove(u);
        }
        r.put("staff", new ListTag());
        r.putBoolean("closed", true);
        r.putString("status", status);
        r.putLong("closedDay", day);
        b.open = false;
        b.closedDay = day;
        b.shortTime.clear();
        unsign(level, r);
        Businesses.dirty();
        Accounts.dirty();
    }

    /** Why it failed, in the town's words. */
    static String whyItFailed(ServerLevel level, UUID village, Business b, String cause) {
        long day = Businesses.today(level);
        Business.Day w = b.last(day, 14);
        double share = shareOf(level, village, b);
        long costs = w.wages + w.rent + w.other + w.interest;
        if (b.markup >= 0.6 || share < LOW_SHARE && w.revenue > 0) return "it sold too dear and too little";
        if (w.revenue <= 0) return "there was no call for its wares";
        if (costs > w.revenue - w.cogs) return "its costs ran ahead of its takings";
        return cause;
    }

    // ------------------------------------------------------------------ signs

    /** The sign by its stall: its name, its trade, and its price for its first ware. */
    static void signs(ServerLevel level, Villages.Village v, Business b, CompoundTag r) {
        BusinessType t = kindOf(v.id(), b);
        if (t == null || !b.open) return;
        BlockPos at = r.contains("sign") ? BlockPos.of(r.getLong("sign")) : null;
        if (at == null || !(level.getBlockEntity(at) instanceof SignBlockEntity)) {
            at = signSpot(level, v, b);
            if (at == null) return;
            level.setBlock(at.below(), Blocks.OAK_FENCE.defaultBlockState(), 3);
            level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(Direction.NORTH)), 3);
            r.putLong("sign", at.asLong());
            Accounts.dirty();
        }
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity s)) return;
        ItemStack one = Stockroom.sampleOf(t.wares().get(0));
        String price = one.isEmpty() ? "" : Stockroom.nameOf(t.wares().get(0)) + " " + String.format(Locale.ROOT, "%.2fc", Sellers.priceOf(level, v.id(), b, one));
        SignText text = new SignText().setMessage(0, Component.literal(fit(b.name))).setMessage(1, Component.literal(fit(t.title())))
            .setMessage(2, Component.literal(fit(price))).setMessage(3, Component.literal(fit(b.ownersWords())));
        s.setText(text, true);
        s.setText(text, false);
        s.setWaxed(true);
    }

    static String fit(String s) {
        return s.length() > 15 ? s.substring(0, 15) : s;
    }

    /** Where the sign goes: open ground by its stall (the square), the town letting it be put there. */
    @Nullable
    static BlockPos signSpot(ServerLevel level, Villages.Village v, Business b) {
        BlockPos near = b.at != null ? b.at : v.centre();
        for (int rad = 2; rad <= 8; rad++) {
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                BlockPos p = Battlefields.surface(level, near.offset((int) Math.round(Math.cos(a) * rad), 0, (int) Math.round(Math.sin(a) * rad)));
                if (p == null || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                if (!TownBlocks.mayPlace(level, p, TownBlocks.Why.CIVIC)) continue;
                return p.above();
            }
        }
        return null;
    }

    static void unsign(ServerLevel level, CompoundTag r) {
        if (!r.contains("sign")) return;
        BlockPos at = BlockPos.of(r.getLong("sign"));
        if (level.getBlockEntity(at) instanceof SignBlockEntity) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            if (level.getBlockState(at.below()).is(Blocks.OAK_FENCE)) level.setBlock(at.below(), Blocks.AIR.defaultBlockState(), 3);
        }
        r.remove("sign");
    }

    // ------------------------------------------------------------------ in words

    /** One firm in a line: its name, owner, trade, week's profit, hands, share and how it stands. */
    public static String line(ServerLevel level, UUID village, Business b) {
        CompoundTag r = rec(village, b.id);
        BusinessType t = kindOf(village, b);
        long day = Businesses.today(level);
        Business.Day w = b.last(day, 7);
        return String.format(Locale.ROOT, "%s (%s, %s): %s; week's profit %s on takings %s; %d hands; %s of its market; markup %s; till %s; kit %s; made %d%s%s",
            b.name, t == null ? "?" : t.title(), b.ownersWords(), r == null ? "?" : r.getString("status"), Businesses.pence(w.net()),
            Businesses.pence(w.revenue), b.open ? hands(village, b).size() : 0, Businesses.pct(r == null ? 0 : r.getDouble("share")),
            Businesses.pct(b.markup), Businesses.pence(b.till), Businesses.pence(Capital.value(village, b)), r == null ? 0 : r.getLong("madeHere"),   // [econ-1c]
            r != null && r.contains("short") ? " (" + r.getString("short") + ")" : "",
            r != null && r.contains("closedWhy") ? " — " + r.getString("closedWhy") : "");
    }

    /** The town's firms in words (/village firms). */
    public static String words(ServerLevel level, UUID village) {
        StringBuilder sb = new StringBuilder("The businesses of " + Villages.name(village) + " opened by their own folk:\n");
        List<Business> all = all(village);
        if (all.isEmpty()) sb.append("  none yet.\n");
        for (Business b : all) sb.append("  ").append(line(level, village, b)).append("\n");
        CompoundTag d = data(village);
        sb.append("Opened ").append(d.getInt("founded")).append(", bankrupt ").append(d.getInt("failed")).append(", wound up by choice ")
            .append(d.getInt("closedByChoice")).append(".\n");
        if (d.contains("intent", Tag.TAG_COMPOUND)) {
            CompoundTag in = d.getCompound("intent");
            VillageFolkEntity f = in.hasUUID("founder") ? Homes.loaded(village, in.getUUID("founder")) : null;
            BusinessType t = BusinessTypes.of(in.getString("type"));
            if (f != null && t != null) sb.append(f.displayNameCap()).append(" means to open a ").append(t.noun()).append(" tomorrow.\n");
        }
        ListTag scan = d.getList("scan", Tag.TAG_STRING);
        if (!scan.isEmpty()) {
            sb.append("The last look at the town's wants:\n");
            for (Tag e : scan) sb.append("  ").append(e.getAsString()).append("\n");
        }
        return sb.toString();
    }

    /** The firm's line for the Businesses page: {status, trade, share, profit7}. */
    static void pageRow(ServerLevel level, UUID village, Business b, CompoundTag row) {
        CompoundTag r = rec(village, b.id);
        BusinessType t = kindOf(village, b);
        if (r == null || t == null) return;
        row.putString("type", t.title() + " (its own folk's" + (r.getString("character").isEmpty() ? "" : "; its founder " + r.getString("character")) + ")");
        row.putString("status", r.getString("status"));
        row.putDouble("share", r.getDouble("share"));
        row.putString("why", r.getString("closedWhy"));
    }

    /** "Owns Wren's Tools (a toolsmith's, thriving)", for a folk's card. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "";
        for (Business b : open(village)) {
            if (!b.ownedBy(f.getUUID())) continue;
            CompoundTag r = rec(village, b.id);
            BusinessType t = kindOf(village, b);
            return "Founded " + b.name + (t == null ? "" : ", a " + t.noun()) + (r == null ? "" : ", " + r.getString("status"));
        }
        return "";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a firm founded now by this folk (null: the best founder), whatever the town's wants. */
    @Nullable
    public static Business foundForTests(ServerLevel level, Villages.Village v, String type, @Nullable VillageFolkEntity f) {
        BusinessType t = BusinessTypes.of(type);
        if (t == null) return null;
        return found(level, v, t, f, null, Businesses.today(level), null);
    }

    /** Tests: the firms' morning now (the market's openness not asked). */
    public static void morningForTests(ServerLevel level, Villages.Village v, boolean plain) {
        FOUND_IN_PLAIN_FOR_TESTS = plain;
        try {
            morning(level, v, Businesses.today(level));
        } finally {
            FOUND_IN_PLAIN_FOR_TESTS = false;
        }
    }

    /** Tests: the opportunity for a kind now. */
    @Nullable
    public static Opportunity weighForTests(ServerLevel level, Villages.Village v, String type) {
        BusinessType t = BusinessTypes.of(type);
        return t == null ? null : weigh(level, v, t, lists(level, v.id()));
    }

    /** Tests: a firm's record. */
    @Nullable
    public static CompoundTag recordForTests(UUID village, Business b) {
        return rec(village, b.id);
    }

    /** Tests: the intention announced (its kind's key and the founder), or null. */
    @Nullable
    public static String intentForTests(UUID village) {
        CompoundTag d = data(village);
        if (!d.contains("intent", Tag.TAG_COMPOUND)) return null;
        CompoundTag in = d.getCompound("intent");
        return in.getString("type") + "|" + (in.hasUUID("founder") ? in.getUUID("founder") : "");
    }

    /** Tests: the counts {founded, failed, closed by choice}. */
    public static int[] countsForTests(UUID village) {
        CompoundTag d = data(village);
        return new int[]{ d.getInt("founded"), d.getInt("failed"), d.getInt("closedByChoice") };
    }

    /** Tests: the index of hands forgotten (as after a reload), to be rebuilt from the records. */
    public static void forgetIndexForTests() {
        STAFF.clear();
        INDEXED.clear();
    }

    /** Tests: a firm's fortunes reckoned now (bankrupt, thriving...). */
    public static boolean fortunesForTests(ServerLevel level, Villages.Village v, Business b) {
        CompoundTag r = rec(v.id(), b.id);
        return r != null && fortunes(level, v, b, r, Businesses.today(level));
    }

    /** [econ-1e] Tests: a firm in a line for the long tests' logs: its turns at the bench, what it made, its main ware's price
     *  against the going price, its makings at the market and its worth (makings, time and margin), its hands. */
    public static String debugLineForTests(ServerLevel level, UUID village, Business b) {
        CompoundTag r = rec(village, b.id);
        BusinessType t = kindOf(village, b);
        if (r == null || t == null) return b.name + ": no record";
        StringBuilder sb = new StringBuilder(b.name).append(" [").append(t.key()).append("] ").append(r.getString("status"))
            .append(": turns ").append(r.getLong("turns")).append(", made ").append(r.getLong("madeHere")).append(", sales ").append(r.getLong("sales"))
            .append(", markup ").append(Businesses.pct(b.markup)).append(", till ").append(Businesses.pence(b.till)).append(", capital ")
            .append(Businesses.pence(r.getLong("capital"))).append(", week net ").append(Businesses.pence(b.last(Businesses.today(level) - 1, 7).net()))
            .append(r.contains("short") ? ", short: " + r.getString("short") : "").append("; targets ").append(r.getCompound("targets"));
        for (String key : t.wares()) {
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            Lot l = b.stock.get(key);
            if ((l == null || l.qty <= 0) && !b.allTime.lines.containsKey(key)) continue;
            sb.append(String.format(Locale.ROOT, "; %s held %d, its price %.2fc, going %.2fc, makings at the market %.2fc, worth %.2fc, lot each %.2fc, sold all time %d",
                key.replace("minecraft:", ""), l == null ? 0 : l.qty, Sellers.priceOf(level, village, b, one), Market.askEach(level, village, one),
                CostBook.atMarket(level, village, one), ValueAdded.worth(village, one) * MarketMaker.priceLevel(village), l == null || l.qty <= 0 ? 0 : l.each() / 100.0,
                b.allTime.lines.containsKey(key) ? b.allTime.lines.get(key).units : 0));
        }
        // [biz30d] Its week's books, line by line, its last orders, and each hand's pay as made up: where its money went.
        Business.Day wk = b.last(Businesses.today(level) - 1, 7);
        sb.append(String.format(Locale.ROOT, "; WEEK rev %s cogs %s wages %s rent %s other %s taxes %s interest %s losses %s bought %s",
            Businesses.pence(wk.revenue), Businesses.pence(wk.cogs), Businesses.pence(wk.wages), Businesses.pence(wk.rent), Businesses.pence(wk.other),
            Businesses.pence(wk.taxes), Businesses.pence(wk.interest), Businesses.pence(wk.losses), Businesses.pence(wk.bought)));
        for (Map.Entry<String, Business.Line> e : wk.lines.entrySet()) {
            Business.Line l = e.getValue();
            sb.append(String.format(Locale.ROOT, " [%s u%d rev %s cogs %s made %d in %d missed %d]", e.getKey().replace("minecraft:", ""), l.units,
                Businesses.pence(l.revenue), Businesses.pence(l.cogs), l.made, l.in, l.missed));
        }
        StringBuilder ob = new StringBuilder();
        for (int i = Math.max(0, b.orders.size() - 4); i < b.orders.size(); i++) {
            Business.Order o = b.orders.get(i);
            ob.append(' ').append(o.day()).append(':').append(o.qty()).append(' ').append(o.item().replace("minecraft:", "")).append(" of ").append(o.supplier())
                .append(' ').append(Businesses.pence(o.total()));
        }
        if (ob.length() > 0) sb.append("; ORDERS").append(ob);
        for (VillageFolkEntity f : hands(village, b)) sb.append("; PAY ").append(f.displayNameCap()).append(' ').append(Wealth.breakdown(f));
        for (VillageFolkEntity f : hands(village, b)) {
            sb.append("; hand ").append(f.displayNameCap()).append(' ').append(f.stationTask().name().toLowerCase(Locale.ROOT)).append(" lv")
                .append(f.tradeLevel(f.stationTask())).append(benchFirst(f) ? " bench-first" : "").append(f.stationTask() == StationTask.COOK ? " role " + Hospitality.role(f) : "")
                .append(": ").append(f.brainNoteForBooks());
        }
        return sb.toString();
    }

    /**
     * [biz30d] The log, once a minute by day: a firm's hand that has had no turn at its bench for two minutes, and what it
     * is at instead (a bakery whose one baker baked nothing for a week went to the court with its shelf bare).
     */
    static void idleHandLog(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return;
        long t = level.getDayTime() % 24000L;
        if (t < 1000L || t > 11000L) return;
        Business b = employerOf(village, f);
        if (b == null) return;
        int since = Crafts.sinceTurn(f);
        if (since >= 0 && since < 2400) return;
        WorkZone z = f.workZone();
        com.mojang.logging.LogUtils.getLogger().info("[MCA-FIRMHAND] {}: {} of {}: no turn at its bench for {}s: {}; missing {}; zone {}, {} blocks off; break {}; lent {}",
            Villages.name(village), f.displayNameCap(), b.name, since < 0 ? "ever" : since / 20, f.brainNoteForBooks(), f.missingEssentials(),
            z == null ? "none" : z.containsColumn(f.blockPosition()) ? "in" : "out", z == null ? -1 : (int) Math.sqrt(z.center().distSqr(f.blockPosition())),
            f.onBreakNow(), f.lentZone() != null);
    }

    /** Tests: a hand taken on now. */
    @Nullable
    public static VillageFolkEntity hireForTests(ServerLevel level, Villages.Village v, Business b) {
        CompoundTag r = rec(v.id(), b.id);
        BusinessType t = kindOf(v.id(), b);
        return r == null || t == null ? null : hire(level, v, b, r, t);
    }

    /** Tests: this folk on a firm's books as its hand, whatever its trade was. */
    public static void staffForTests(UUID village, Business b, VillageFolkEntity f) {
        CompoundTag r = rec(village, b.id);
        if (r != null) addStaff(village, b, r, f);
    }

    /** [econ-1c] Tests: can one of a kind's wares be made out of the stores now, and why not ("" if it can). */
    public static String makeableForTests(ServerLevel level, Villages.Village v, String type) {
        BusinessType t = BusinessTypes.of(type);
        if (t == null) return "no such kind";
        if (makeable(level, v, t)) return "";
        Bench.Hand hand = Bench.handOf(level, v, null, VillageFolkEntity.buildingFor(t.work()));
        StringBuilder sb = new StringBuilder("hand " + hand + ":");
        for (String key : t.wares()) {
            ItemStack one = Stockroom.sampleOf(key);
            if (!one.isEmpty()) sb.append(' ').append(key).append(" short of ").append(FirmSupply.asFirm(() -> Bench.plan(level, v, one.getItem(), 1,
                new Bench.Hand(Math.max(hand.skill(), 10), hand.maker(), hand.table(), hand.furnace(), hand.smoker(), hand.campfire(), hand.smithy()))).shortOf).append(';');
        }
        return sb.toString();
    }

    /** [econ-1c] Tests: the firm's creditors take it to the court now. */
    public static void petitionForTests(ServerLevel level, UUID village, Business b, String cause) {
        petitionFor(level, village, b, Businesses.today(level), cause);
    }

    /** [econ-1c] Tests: the stalls this folk owns, to set up on. */
    public static int stallsForTests(UUID village, VillageFolkEntity f) {
        return Accounts.sub(data(village), "stalls").getInt(f.getUUID().toString());
    }

    /** Tests: the firm's share and markup books reckoned now. */
    public static void shareForTests(ServerLevel level, UUID village, Business b) {
        CompoundTag r = rec(village, b.id);
        if (r != null) share(level, village, b, r, Businesses.today(level));
    }

    /** Tests: expansion now (as if a fortnight thriving). */
    public static void expandForTests(ServerLevel level, Villages.Village v, Business b) {
        CompoundTag r = rec(v.id(), b.id);
        BusinessType t = kindOf(v.id(), b);
        if (r != null && t != null) expand(level, v, b, r, t, Businesses.today(level));
    }

    /** Tests: debts run up: wages owed a hand, the town (rent and taxes), the market's trade credit, the bank's loan. */
    public static void debtsForTests(UUID village, Business b, @Nullable VillageFolkEntity hand, long wages, long town, long market, long bank) {
        if (hand != null) wageOwed(village, b, hand, wages);
        b.account -= town;
        Accounts.owe(village, Accounts.Account.firm(b), market, "tests");
        b.loan += bank;
        if (bank > 0) b.lender = "bank";
        Businesses.dirty();
    }

    /** Tests: so many of a ware in the firm's stock, at so many pennies each (the goods themselves the caller's to put in the stores). */
    public static void stockForTests(Business b, ItemStack one, long n, long each) {
        b.stock.computeIfAbsent(Stockroom.key(one), k -> new Lot()).add(n, n * each);
        Businesses.dirty();
    }
}
