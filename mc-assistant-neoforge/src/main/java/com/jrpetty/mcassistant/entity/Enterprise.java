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
        VillageFolkEntity f = Accounts.actor();
        return f == null ? null : employerOf(village, f);
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

    /** The start-up a kind wants here, pennies: its premises, three days' makings, a week's wages for its hands. */
    static long startup(UUID village, BusinessType t, double inputCost, @Nullable UUID founder) {
        long premises = t.stall() ? Math.round(STALL_PENCE * MarketMaker.priceLevel(village)) : 0;
        if (founder != null && Accounts.sub(data(village), "stalls").getInt(founder.toString()) > 0 && t.stall()) premises = 0;
        long makings = Math.round(inputCost * 100 * Math.min(t.perDay(), 16) * 3);
        long wages = (long) t.startStaff() * wageOf(t.work(), village) * 100L * 7;
        return premises + makings + wages;
    }

    /** The week's wants met and not of a kind's wares, its costs, and what a new firm might make of it. */
    @Nullable
    static Opportunity weigh(ServerLevel level, Villages.Village v, BusinessType t, Map<String, Integer> lists) {
        UUID id = v.id();
        double demand = 0, supply = 0, priceSum = 0, costSum = 0, inputSum = 0, weightSum = 0;
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
            double want = w[0] + w[1] + refused7(id, key) + lists.getOrDefault(key, 0) + firmSold;
            double have = w[0] + firmSold + firmStock;
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
            if (gap > bestGap) { bestGap = gap; best = key; }
        }
        if (weightSum <= 0 || best == null) return null;
        double price = priceSum / weightSum, cost = costSum / weightSum, inputs = inputSum / weightSum;
        double gap = Math.max(0, demand - 0.9 * supply);
        int firms = 0;
        for (Business b : open(id)) {
            BusinessType k = kindOf(id, b);
            if (k != null && k.key().equals(t.key())) firms++;
        }
        double margin = cost <= 0 ? 0 : price / cost;
        double score = gap * price * (margin - 1) / (1 + firms);
        // What a new firm might make in a week: half the gap (no more than its hands can make), at its price less its
        // makings, less its rent and the wages of any hands beside the founder (the founder lives on what it makes).
        double capacity = (1 + t.startStaff()) * t.perDay() * 7 * 0.5;
        double sells = Math.min(gap * 0.5, capacity);
        double rent7 = (t.stall() ? 30 : 60) * Wealth.standing(id) / 10.0 * 7 / 100.0;
        double profit7 = sells * (price - inputs) - rent7 - t.startStaff() * wageOf(t.work(), id) * 7;
        double startup = startup(id, t, inputs, null) / 100.0;
        return new Opportunity(t, best, demand, supply, price, cost, inputs, gap, score, profit7, startup, firms);
    }

    /** One of a ware's makings at the market's ask (a seller's: its buying price, a twentieth off in bulk). */
    static double makings(ServerLevel level, UUID village, BusinessType t, ItemStack one) {
        if (t.resells()) return Businesses.storesEach(level, village, one) * 0.95;
        CostBook.Basis b = CostBook.of(one);
        double list = b.materials() + b.fuel();
        return list * MarketMaker.priceLevel(village) * (MarketMaker.live(village) ? 1 + MarketMaker.spread(village) / 2 : 1.0);
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

    /** What it could put into a business, coins: its purse and its savings, less what it keeps by. */
    static int means(VillageFolkEntity f) {
        return Math.max(0, f.purse() + Bank.balance(f) - RESERVE);
    }

    /** Why this folk may not open this kind of business, or null if it may (the nerve and the money aside). */
    @Nullable
    static String barred(UUID village, VillageFolkEntity f, BusinessType t) {
        if (f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) return "not one who could";
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder == f) return "the elder has the town to run";
        if (ownsAny(village, f.getUUID())) return "already has a business";
        if (!skilled(f, t.work())) return "has not the skill (level " + f.tradeLevel(t.work()) + " at " + t.work().label + ")";
        StationTask now = f.stationTask();
        if (now == StationTask.GUARD || now == StationTask.STORE || now == StationTask.BANK) return "the town cannot spare it from " + now.label;
        return null;
    }

    /** The best founder for an opportunity: able, bold and with the means, the boldest first. */
    @Nullable
    static VillageFolkEntity founderFor(UUID village, BusinessType t, long startupPence) {
        VillageFolkEntity best = null;
        double bestScore = -1e9;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            if (barred(village, f, t) != null) continue;
            int bold = boldness(f);
            if (bold < 3) continue;
            if (means(f) * 100L * 2 < startupPence) continue;
            double s = bold * 10 + f.tradeLevel(t.work()) + Math.min(10, means(f) / 5.0) + (f.stationTask() == t.work() ? 5 : 0);
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
        for (Business b : open(id)) {
            CompoundTag r = rec(id, b.id);
            if (r == null) continue;
            tidyStaff(level, v, b, r);
            share(level, id, b, r, day);
            sellOver(level, id, b, day);
            if (fortunes(level, v, b, r, day)) continue;
            signs(level, v, b, r);
        }
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
        if (d.contains("last") && day - d.getLong("last") < DAYS_BETWEEN) return null;
        int grown = 0;
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby()) grown++;
        if (open(id).size() + 1 > grown / FOLK_A_FIRM) return null;
        List<Opportunity> all = scan(level, v);
        d.put("scan", scanTag(all));
        for (Opportunity o : all) {
            if (o.profit7() <= 0.15 * o.startup() || o.profit7() <= 0) break;
            VillageFolkEntity f = founderFor(id, o.type(), Math.round(o.startup() * 100));
            if (f != null) return o;
        }
        return null;
    }

    static ListTag scanTag(List<Opportunity> all) {
        ListTag l = new ListTag();
        for (int i = 0; i < Math.min(8, all.size()); i++) l.add(StringTag.valueOf(all.get(i).words()));
        return l;
    }

    /** The founder says what it means to do (the morning before it does). */
    static void intend(ServerLevel level, Villages.Village v, Opportunity o, long day) {
        UUID id = v.id();
        VillageFolkEntity f = founderFor(id, o.type(), Math.round(o.startup() * 100));
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
        f.brain("means to open a " + o.type().noun() + " tomorrow: " + why);
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
        double inputs = o != null ? o.inputCost() : makings(level, id, t, Stockroom.sampleOf(t.wares().get(0)));
        long startup = startup(id, t, inputs, founder == null ? null : founder.getUUID());
        // The founder to its work: already at it, or taken up (ground for the trade found), or not at all.
        if (founder != null && founder.stationTask() != t.work() && !founder.takeUpTrade(t.work())) {
            founder.brain("could find no ground for a " + t.noun() + ", and let the idea go");
            return null;
        }
        CompoundTag d = data(id);
        int seq = d.getInt("seq") + 1;
        d.putInt("seq", seq);
        String fid = "ent-" + seq;
        String first = founder != null ? firstName(founder.displayNameCap()) : player != null ? player.getGameProfile().getName() : "The Town";
        Business b = new Business(fid, Business.Type.ENTERPRISE, t.nameFor(first));
        b.opened = day;
        b.markup = 0.30;
        b.at = founder != null && founder.stationPos() != null ? founder.stationPos() : v.centre();
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
            long want = Math.min(startup, means(founder) * 100L);
            int coins = (int) Math.min(founder.purse() - Math.min(founder.purse(), RESERVE), (want + 99) / 100);
            if (coins < (want + 99) / 100 && Bank.open(id)) {
                int fromBank = (int) Math.min(Bank.balance(founder), (want + 99) / 100 - coins);
                if (fromBank > 0) Bank.withdraw(id, Bank.state(id), founder, fromBank);
                coins = (int) Math.min(founder.purse() - Math.min(founder.purse(), RESERVE / 2), (want + 99) / 100);
            }
            if (coins > 0 && founder.spend(coins)) raised = coins * 100L;
            b.owners.add(new Stake(Holder.FOLK, founder.getUUID(), founder.displayNameCap(), Business.WHOLE));
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
        if (founder != null) {
            addStaff(id, b, r, founder);
            founder.brain("opened " + b.name + ", its own " + t.noun());
            founder.persona().remember(day, "I opened " + b.name, 4);
            FolkTalk.speak(founder, FolkTalk.pick(founder.getRandom(), b.name + " is open! Come and see.", "My own " + t.noun() + " at last. " + b.name + "!",
                "Open for trade: " + b.name + "."));
        }
        for (int i = 0; i < t.startStaff(); i++) hire(level, v, b, r, t);
        String how = founder != null ? Businesses.pence(raised) + " of " + founder.displayNameCap() + "'s own" + (lent > 0 ? " and " + Businesses.pence(lent) + " from the bank" : "")
            : "a player's charter";
        b.note(day, "opened by " + b.ownersWords() + " as a " + t.noun() + ", with " + how + (o == null ? "" : String.format(Locale.ROOT,
            ": the town wanted %.0f %s a week and had %.0f", o.demand(), Stockroom.plural(o.good()).toLowerCase(Locale.ROOT), o.supply())));
        Villages.tell(id, day, (founder != null ? founder.displayNameCap() : first) + " opened " + b.name + (t.stall() ? " on the square" : "")
            + ", a " + t.noun() + " of " + (founder != null ? "their" : "its") + " own");
        Accounts.add(d, "founded", 1);
        signs(level, v, b, r);
        return b;
    }

    /**
     * A player's charter: at the hall (or the town's heart, without one), a citizen or a friend of the town, ten coins to the
     * treasury; the firm is the player's, opened on a stall with nothing in its till, for the player to fund, staff and stock.
     */
    public static String charter(ServerLevel level, Villages.Village v, net.minecraft.server.level.ServerPlayer p, String kind) {
        BusinessType t = BusinessTypes.of(kind);
        if (t == null) return "No such kind of business. The kinds: " + String.join(", ", BusinessTypes.keys()) + ".";
        UUID id = v.id();
        BlockPos hall = Villages.builtAt(id, "hall");
        BlockPos at = hall != null ? hall : v.centre();
        if (!p.blockPosition().closerThan(at, hall != null ? 24 : 48)) return "Charters are granted at " + (hall != null ? "the hall" : "the town's heart") + ": go there first.";
        if (!Citizens.is(id, p.getUUID()) && !Standing.of(id, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            return "Only a citizen or a friend of " + Villages.name(id) + " may open a business here.";
        }
        if (Market.coinsHeld(p) < CHARTER_FEE) return "The charter costs " + CHARTER_FEE + " coins; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, CHARTER_FEE);
        com.jrpetty.mcassistant.village.Ledger.addCoins(id, CHARTER_FEE);
        CoinCensus.flow(id, CoinCensus.Flow.PLAYER_IN, CHARTER_FEE);
        TownBudget.in(id, "fees", CHARTER_FEE * 100L);
        Business b = found(level, v, t, null, p, Businesses.today(level), null);
        if (b == null) return "The charter could not be drawn up.";
        return "Your charter is granted: " + b.name + " (" + b.id + "), a " + t.noun() + ". Fund it with /village business invest " + b.id
            + " <coins>, take on a hand with /village firms hire " + b.id + ", order its makings with /village business order " + b.id
            + " <item> <qty>, and set its prices with /village firms markup " + b.id + " <percent>.";
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
        if (best == null) return null;
        if (best.stationTask() != t.work() && !best.takeUpTrade(t.work())) return null;
        addStaff(id, b, r, best);
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
            if (!f.isAlive() || t != null && f.stationTask() != t.work()) removeStaff(v.id(), r, u);
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
        return b.till + b.account + b.stockCost() - b.loan - Accounts.owed(village, Accounts.Account.firm(b));
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
        if (day - b.opened >= 3 && (r.getInt("broke") >= BROKE_DAYS || r.getInt("under") >= UNDER_DAYS)) {
            bankrupt(level, id, b, day, r.getInt("broke") >= BROKE_DAYS ? "its cash and its credit gone" : "it owed more than it was worth");
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
     */
    public static void bankrupt(ServerLevel level, UUID village, Business b, long day, String cause) {
        CompoundTag r = rec(village, b.id);
        if (r == null || r.getBoolean("closed")) return;
        String why = whyItFailed(level, village, b, cause);
        List<String> paid = new ArrayList<>(), unpaid = new ArrayList<>();
        // The stock, to the market at its bid (or the stores at cost where the market is not open).
        long fromStock = liquidate(level, village, b, day);
        // The stall, at auction to the folk with the means.
        long fromStall = auctionStall(level, village, b, r, day);
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
        // The creditors, in order.
        CompoundTag wages = Accounts.sub(r, "owedWages");
        for (String k : new ArrayList<>(wages.getAllKeys())) {
            long due = wages.getLong(k), pay = Math.min(due, b.till / 100 * 100);
            VillageFolkEntity f = null;
            try { f = Homes.loaded(village, UUID.fromString(k)); } catch (IllegalArgumentException ignored) { }
            if (pay > 0 && f != null) {
                f.earn((int) (pay / 100));
                b.till -= pay;
                paid.add("wages " + Businesses.pence(pay));
            }
            if (due - pay > 0) unpaid.add("wages " + Businesses.pence(due - pay));
            wages.remove(k);
        }
        if (b.account < 0) {
            long due = -b.account, pay = Math.min(due, b.till);
            if (pay > 0) {
                b.till -= pay;
                Businesses.toTreasury(village, pay);
                TownBudget.in(village, "taxes", 0);
                paid.add("the town " + Businesses.pence(pay));
            }
            if (due - pay > 0) unpaid.add("the town " + Businesses.pence(due - pay));
            b.account = 0;
        }
        long market = Accounts.owed(village, Accounts.Account.firm(b));
        if (market > 0) {
            long pay = Math.min(market, b.till);
            if (pay > 0) {
                b.till -= pay;
                MarketMaker.cashIn(village, pay);
                paid.add("the market " + Businesses.pence(pay));
            }
            Accounts.forgive(village, Accounts.Account.firm(b));
            if (market - pay > 0) unpaid.add("the market " + Businesses.pence(market - pay));
        }
        if (b.loan > 0) {
            int coins = (int) Math.min(b.loan, b.till) / 100;
            if (coins > 0) {
                if (Bank.open(village)) Bank.firmRepaid(village, coins);
                else Businesses.toTreasury(village, coins * 100L);
                b.till -= coins * 100L;
                b.loan -= coins * 100L;
                paid.add("the bank " + coins + "c");
            }
            if (b.loan > 0) unpaid.add("the bank " + Businesses.pence(b.loan));
            b.loan = 0;
            b.lender = "";
        }
        // What is left (if anything), to its owners.
        returnToOwners(village, b);
        close(level, village, b, r, day, "bankrupt");
        b.note(day, "bankrupt (" + cause + "): stock " + Businesses.pence(fromStock) + ", stall " + Businesses.pence(fromStall)
            + (fromPurse > 0 ? ", " + Businesses.pence(fromPurse) + " of its owner's purse" : "") + "; paid " + (paid.isEmpty() ? "nobody" : String.join(", ", paid))
            + (unpaid.isEmpty() ? "" : "; written off: " + String.join(", ", unpaid)));
        r.putString("closedWhy", why);
        r.putString("creditors", "paid " + (paid.isEmpty() ? "nobody" : String.join(", ", paid)) + (unpaid.isEmpty() ? "" : "; written off " + String.join(", ", unpaid)));
        Villages.tell(village, day, b.name + " closed: " + why);
        Accounts.add(data(village), "failed", 1);
        if (sole != null) {
            FolkTalk.speak(sole, FolkTalk.pick(sole.getRandom(), "That's the end of " + b.name + ". I'll not try that again in a hurry.",
                b.name + " is finished. " + cap(why) + "."));
            sole.persona().remember(day, b.name + " went bankrupt", -5);
        }
    }

    /** Wound up by its owner: stock sold, debts paid, what is left its owner's. */
    static void windUp(ServerLevel level, UUID village, Business b, long day, String why) {
        CompoundTag r = rec(village, b.id);
        if (r == null) return;
        liquidate(level, village, b, day);
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
            if (coins > 0 && Bank.open(village)) Bank.firmRepaid(village, coins);
            else if (coins > 0) Businesses.toTreasury(village, coins * 100L);
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
                MarketMaker.buy(level, village, Accounts.Account.firm(b), one, n, "a bankrupt's stock");
                got += b.till - before;
            } else {
                Businesses.storesIn(village, one, n, cost);
                long p = MarketMaker.outOrTreasury(village, cost);
                b.till += p;
                got += p;
            }
            Business.Day d = b.today(day);
            d.cogs += cost;
            b.allTime.cogs += cost;
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
        return String.format(Locale.ROOT, "%s (%s, %s): %s; week's profit %s on takings %s; %d hands; %s of its market; markup %s; till %s%s",
            b.name, t == null ? "?" : t.title(), b.ownersWords(), r == null ? "?" : r.getString("status"), Businesses.pence(w.net()),
            Businesses.pence(w.revenue), b.open ? hands(village, b).size() : 0, Businesses.pct(r == null ? 0 : r.getDouble("share")),
            Businesses.pct(b.markup), Businesses.pence(b.till), r != null && r.contains("closedWhy") ? " — " + r.getString("closedWhy") : "");
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
        row.putString("type", t.title() + " (its own folk's)");
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
