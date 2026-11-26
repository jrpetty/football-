package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [capital] The town's bonds: its public debt, sold to those with coin to spare instead of running ever more IOUs at its
 * market.
 *
 * <p><b>Why.</b> A town short of coin buys what its works and its watch need of its market on IOUs, and the market,
 * paying its producers in coin for what it buys of them, runs out of cash and pays them in store credit: the town's
 * deficit was carried by its farmers and miners without their asking. A bond is a loan the lender chooses: so many coins
 * for a fortnight at so much a week, paid by the treasury every seventh day and repaid at the end (or, the town still
 * short, rolled over at the day's rate).
 *
 * <p><b>The sale.</b> Each morning before the wages the treasurer looks at what the town owes its market over a quarter
 * of what it can carry, and at what yesterday's wages wanted over what the treasury holds. That much it offers in bonds,
 * as far as its room goes: never more out than two weeks' revenue (twenty coins at least), nor so much that a week's
 * coupons come to over a fifth of a week's revenue. The savings club (or the bank) takes first, out of what its savers
 * deposited and nobody borrowed; then the firms with a till over their float; then the careful folk with coin over their
 * week's keep. What it raises pays the IOUs down (the market has the coin, and pays its producers their store credit) and
 * finds the essential wages.
 *
 * <p><b>The rate.</b> One in the hundred a week, and a point and a half more for every week's revenue the town owes in
 * all (its IOUs and its bonds together); a town behind with a coupon pays a point more. A town deep in debt pays dear
 * for it, as it should, and its coupons are part of its spending.
 *
 * <p>Players buy bonds too (/village capital bonds buy), and their coupons and repayments wait for them at the hall
 * (counted by the coin census) till they collect them.
 */
public final class TownBonds {

    private TownBonds() {}

    /** A bond's term, days. */
    static final int TERM = 14;
    /** The week's coupon in hundredths of a percent: the least, a week's revenue owed's worth, the most. */
    static final int LEAST_BP = 100, BP_A_WEEK = 150, MOST_BP = 900, ARREARS_BP = 100;
    /** The most out at once: so many weeks' revenue, and never under so many pennies. */
    static final double ROOM_WEEKS = 2.0;
    static final long LEAST_ROOM = 2000;
    /** A week's coupons at most this share of a week's revenue. */
    static final double COUPON_SHARE = 0.2;
    /** The IOUs the town keeps at its market (a quarter of what it can carry): the rest it funds with bonds. */
    static final double FLOATING = 0.25;
    /** The least a bond sale is worth the treasurer's trouble, pennies. */
    static final long LEAST_SALE = 300;

    /** Yesterday's wage bill and what the treasury held for it, by town (Market.payWages): pennies. */
    private static final Map<UUID, long[]> PAYDAY = new java.util.concurrent.ConcurrentHashMap<>();

    static CompoundTag book(UUID village) {
        return CapitalBook.part(village, "bonds");
    }

    static ListTag bonds(UUID village) {
        return CapitalBook.list(book(village), "list");
    }

    // ------------------------------------------------------------------ the figures

    /** All the town owes on its bonds, pennies (their face). */
    public static long outstanding(UUID village) {
        long n = 0;
        for (Tag e : bonds(village)) n += ((CompoundTag) e).getLong("face");
        return n;
    }

    /** The coupons it is behind with, pennies. */
    public static long arrears(UUID village) {
        long n = 0;
        for (Tag e : bonds(village)) {
            long owed = ((CompoundTag) e).getLong("owed");
            if (owed >= 100) n += owed;                                    // (a purse's coupon under a coin waits till it is a coin)
        }
        return n;
    }

    /** Tests: the coupons run up on the bonds and not yet paid, pennies (a purse's under a coin among them). */
    public static long owedForTests(UUID village) {
        long n = 0;
        for (Tag e : bonds(village)) n += ((CompoundTag) e).getLong("owed");
        return n;
    }

    /** The town's revenue in a week (TownBudget.week), pennies, never under a coin. */
    static long week(UUID village) {
        return Math.max(100, TownBudget.week(village));
    }

    /** The week's rate on a new bond, hundredths of a percent: the town's debt (IOUs and bonds) against its week's revenue. */
    public static int rateBp(UUID village) {
        double weeks = (Accounts.debts(village) + outstanding(village)) / (double) Math.max(1000, week(village));
        int bp = LEAST_BP + (int) Math.round(BP_A_WEEK * Math.min(5.0, weeks)) + (arrears(village) > 0 ? ARREARS_BP : 0);
        return Math.min(MOST_BP, bp);
    }

    /** The most the town may have out in bonds today, pennies. */
    public static long ceiling(UUID village) {
        long w = week(village);
        long byRevenue = Math.max(LEAST_ROOM, Math.round(ROOM_WEEKS * w));
        long byCoupons = Math.max(LEAST_ROOM, Math.round(COUPON_SHARE * w * 10000.0 / Math.max(1, rateBp(village))));
        return Math.min(byRevenue, byCoupons);
    }

    /** What it may still sell, pennies. */
    public static long room(UUID village) {
        return Math.max(0, ceiling(village) - outstanding(village));
    }

    /** The week's coupons on what is out, pennies. */
    public static long weekCoupons(UUID village) {
        long n = 0;
        for (Tag e : bonds(village)) {
            CompoundTag b = (CompoundTag) e;
            n += b.getLong("face") * b.getInt("bp") / 10000;
        }
        return n;
    }

    /** Market.payWages: the morning's wage bill on the treasury and what it held to pay it, coins. */
    public static void payday(UUID village, int bill, int purse) {
        PAYDAY.put(village, new long[]{ bill * 100L, purse * 100L });
    }

    /** What the treasury fell short of yesterday's wages by, given what it holds now, pennies. */
    static long wageShort(UUID village) {
        long[] p = PAYDAY.get(village);
        if (p == null || p[0] <= 0) return 0;
        long purse = Businesses.treasuryPence(village) - Market.saved(village, 0) * 100L;
        return Math.max(0, p[0] - purse);
    }

    // ------------------------------------------------------------------ the sale

    /** Before the wages: the IOUs over a quarter of what it can carry funded, and the essential wages found. */
    static void beforeWages(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag bk = book(id);
        if (bk.getLong("soldOn") == day + 1) return;
        bk.putLong("soldOn", day + 1);
        long overIous = Math.max(0, Accounts.debts(id) - Math.round(FLOATING * TownBudget.limit(id)));
        long wages = wageShort(id);
        long want = Math.min(room(id), overIous + wages);
        if (want < LEAST_SALE) return;
        Sale s = sell(level, v, want, day);
        if (s.raised <= 0) {
            if (want >= 1000) bk.putString("why", "offered " + Businesses.pence(want) + " of bonds on day " + (day + 1) + ": nobody had the coin to spare");
            CapitalBook.dirty();
            return;
        }
        long toIous = Math.min(s.raised, overIous);
        long paid = toIous > 0 ? Accounts.payIous(id, toIous, "bonds sold") : 0;
        CompoundTag w = weekBook(id, day);
        Accounts.add(w, "sold", s.raised);
        Accounts.add(w, "funded", paid);
        bk.putString("why", "");
        CapitalBook.tell(id, day, "the town sold " + Businesses.pence(s.raised) + " of its bonds at " + pct(rateBp(id)) + " a week to "
            + String.join(", ", s.buyers) + (paid > 0 ? ": " + Businesses.pence(paid) + " of its IOUs to the market paid off" : "")
            + (s.raised > paid ? (paid > 0 ? ", the rest" : ": it") + " toward the wages" : ""));
        CapitalBook.dirty();
    }

    /** A bond sale's takings and its buyers. */
    static final class Sale {
        long raised;
        final List<String> buyers = new ArrayList<>();
    }

    /** So many pennies of bonds offered: the club or the bank first, then the firms with spare coin, then the careful folk. */
    static Sale sell(ServerLevel level, Villages.Village v, long want, long day) {
        UUID id = v.id();
        Sale s = new Sale();
        int bp = rateBp(id);
        // The savings club (or the bank), out of what its savers left with it and nobody borrowed.
        long inst = FolkCredit.forBonds(id, want, bp);
        if (inst > 0) {
            issue(id, FolkCredit.bondHolderKind(id), FolkCredit.bondHolderKind(id), FolkCredit.lenderName(id), inst, bp, day);
            Businesses.toTreasury(id, inst);
            s.raised += inst;
            s.buyers.add(FolkCredit.lenderName(id) + " " + Businesses.pence(inst));
        }
        // The firms with a till over their float, a third of what they can spare.
        for (Business b : Businesses.all(id)) {
            if (s.raised >= want) break;
            if (b.town() || b.mirror() || !b.open || "storehouse".equals(b.id)) continue;
            long spare = b.till - Businesses.floatOf(b, day) - 1000;
            if (spare < 300 || b.account < 0 || b.loan > 0) continue;
            long take = Math.min(want - s.raised, spare / 3 / 100 * 100);
            if (take < 100) continue;
            b.till -= take;
            Businesses.toTreasury(id, take);
            issue(id, "firm", b.id, b.name, take, bp, day);
            b.note(day, "bought " + Businesses.pence(take) + " of the town's bonds at " + pct(bp) + " a week");
            s.raised += take;
            s.buyers.add(b.name + " " + Businesses.pence(take));
            Businesses.dirty();
        }
        // The careful folk, with coin over their week's keep: half of what they would save.
        int folkN = 0;
        long folkP = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (s.raised >= want) break;
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            int thrift = Bank.thrift(f);
            if (thrift < 1 || Homes.rentsAndWantsToOwn(f)) continue;
            if (Enterprise.boldness(f) >= 3) continue;                       // an entrepreneur at heart keeps its coin for a venture
            int over = f.purse() - CapitalBook.keep(id, f);
            if (over < 4) continue;
            int coins = (int) Math.min((want - s.raised + 99) / 100, Math.max(1, over * Bank.share(thrift) / 200));
            if (coins <= 0 || !f.spend(coins)) continue;
            Businesses.toTreasury(id, coins * 100L);
            issue(id, "folk", f.getUUID().toString(), f.displayNameCap(), coins * 100L, bp, day);
            s.raised += coins * 100L;
            folkN++;
            folkP += coins * 100L;
            if (level.getRandom().nextInt(5) == 0) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "A town bond. Safer than under the bed, and it pays.",
                "I've lent the town a few coins. It pays " + pct(bp) + " a week.", "Bought a bond at the hall. The town's good for it."));
            f.persona().remember(day, "lent the town " + coins + " coin on a bond", 1);
        }
        if (folkN > 0) s.buyers.add(folkN + (folkN == 1 ? " saver " : " savers ") + Businesses.pence(folkP));
        return s;
    }

    /** A bond written: who holds it, its face, its rate, its day; due a term on. */
    static CompoundTag issue(UUID village, String kind, String who, String name, long face, int bp, long day) {
        CompoundTag bk = book(village);
        int seq = bk.getInt("seq") + 1;
        bk.putInt("seq", seq);
        CompoundTag b = new CompoundTag();
        b.putInt("id", seq);
        b.putString("kind", kind);
        b.putString("who", who);
        b.putString("name", name);
        b.putLong("face", face);
        b.putInt("bp", bp);
        b.putLong("day", day);
        b.putLong("due", day + TERM);
        bonds(village).add(b);
        Accounts.add(bk, "soldAll", face);
        CapitalBook.dirty();
        return b;
    }

    // ------------------------------------------------------------------ the coupons and the repayments

    /** The morning (CapitalBook.morning): each bond's week's coupon on its seventh day, and the bonds due repaid or rolled over. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        ListTag l = bonds(id);
        if (l.isEmpty()) return;
        CompoundTag w = weekBook(id, day);
        long coupons = 0, repaid = 0, rolled = 0;
        long spare = spare(id);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag b = l.getCompound(i);
            long owed = b.getLong("owed");
            long age = day - b.getLong("day");
            if (age > 0 && age % 7 == 0 && b.getLong("couponOn") != day) {
                b.putLong("couponOn", day);
                owed += b.getLong("face") * b.getInt("bp") / 10000;
            }
            // A folk's purse and the bank's vault take whole coins: the odd pennies wait on the bond (a claim, not coin).
            long payable = wholeCoins(b.getString("kind")) ? Math.min(owed, Businesses.treasuryPence(id)) / 100 * 100 : owed;
            if (payable > 0) {
                long got = Businesses.fromTreasury(id, payable);
                if (got > 0) {
                    Accounts.deptBook(id, Accounts.Dept.HALL, got, 0);   // the town's spending, its coupons among it
                    pay(level, id, b, got, day, "coupon");
                    coupons += got;
                    spare -= got;
                }
                owed -= got;
            }
            b.putLong("owed", owed);
        }
        // Due: repaid if the treasury can spare it (the oldest first), else rolled over at the day's rate.
        int bp = rateBp(id);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag b = l.getCompound(i);
            if (b.getLong("due") > day) continue;
            long face = b.getLong("face");
            if (spare >= face && b.getLong("owed") < 100) {
                long got = Businesses.fromTreasury(id, face);
                if (wholeCoins(b.getString("kind"))) {
                    long back = got % 100;
                    if (back > 0) Businesses.toTreasury(id, back);
                    got -= back;
                }
                b.putLong("face", face - got);
                if (got >= face) b.putLong("owed", 0);                    // the odd pennies of its last coupon forgiven with it
                pay(level, id, b, got, day, "repaid");
                repaid += got;
                spare -= got;
            } else {
                b.putLong("due", day + TERM);
                b.putInt("bp", bp);
                rolled += face;
            }
        }
        l.removeIf(t -> ((CompoundTag) t).getLong("face") <= 0 && ((CompoundTag) t).getLong("owed") <= 0);
        Accounts.add(w, "coupons", coupons);
        Accounts.add(w, "repaid", repaid);
        Accounts.add(w, "rolled", rolled);
        if (repaid >= 500) CapitalBook.tell(id, day, "the town repaid " + Businesses.pence(repaid) + " of its bonds out of its revenue");
        CapitalBook.dirty();
    }

    /** What the treasury can spare for repaying bonds: what it holds over two days' wages and its savings, pennies. */
    static long spare(UUID village) {
        long[] p = PAYDAY.get(village);
        long bill = p == null ? 0 : p[0];
        return Businesses.treasuryPence(village) - Market.saved(village, 0) * 100L - 2 * bill - 1000;
    }

    /** A bond's coupon or repayment to its holder: a folk's purse (whole coins, the odd pennies carried), a firm's till, the
     *  club's or the bank's vault, a player's due at the hall. */
    static void pay(ServerLevel level, UUID village, CompoundTag b, long p, long day, String what) {
        if (p <= 0) return;
        b.putLong("paid", b.getLong("paid") + ("coupon".equals(what) ? p : 0));
        String kind = b.getString("kind"), who = b.getString("who");
        switch (kind) {
            case "folk" -> {
                VillageFolkEntity f = null;
                try { f = Homes.loaded(village, UUID.fromString(who)); } catch (IllegalArgumentException ignored) { }
                if (f == null) {
                    // Away (or gone): the treasury keeps it for the bond's holder, on the bond.
                    Businesses.toTreasury(village, p);
                    b.putLong("owed", b.getLong("owed") + ("coupon".equals(what) ? p : 0));
                    if (!"coupon".equals(what)) b.putLong("face", b.getLong("face") + p);
                    return;
                }
                f.earn((int) (p / 100));                                      // whole coins (wholeCoins)
            }
            case "firm" -> {
                Business f = Businesses.of(village, who);
                if (f == null || !f.open) {
                    Businesses.toTreasury(village, p);
                    return;
                }
                f.till += p;
                if ("coupon".equals(what)) {
                    f.today(day).interest -= p;
                    f.allTime.interest -= p;
                }
                Businesses.dirty();
            }
            case "club", "bank" -> FolkCredit.bondIncome(village, kind, p, "coupon".equals(what));
            case "player" -> {
                Accounts.add(Accounts.sub(book(village), "due"), who, p);
                PlayerFinance.accrued(who, village, p, "coupon".equals(what) ? PlayerFinance.Cat.COUPONS : PlayerFinance.Cat.CASHED);   // [purse20]
                CapitalBook.dirty();
            }
            default -> Businesses.toTreasury(village, p);
        }
    }

    /** Does this holder take whole coins only (a folk's purse, the bank's vault)? */
    static boolean wholeCoins(String kind) {
        return "folk".equals(kind) || "bank".equals(kind);
    }

    /** The coupons and repayments waiting at the hall for players, pennies (a holder the coin census counts). */
    public static long playersDue(UUID village) {
        long n = 0;
        CompoundTag d = Accounts.sub(book(village), "due");
        for (String k : d.getAllKeys()) n += d.getLong(k);
        return n;
    }

    /** This week's book: sold, funded, coupons, repaid, rolled (pennies). */
    static CompoundTag weekBook(UUID village, long day) {
        CompoundTag bk = book(village);
        CompoundTag w = Accounts.sub(bk, "week");
        long from = day - Math.floorMod(day, 7);
        if (w.getLong("from") != from + 1) {
            bk.put("lastWeek", w.copy());
            w = new CompoundTag();
            w.putLong("from", from + 1);
            bk.put("week", w);
        }
        return w;
    }

    // ------------------------------------------------------------------ holders

    /** The bonds held by each holder, by name: face pennies. */
    public static Map<String, Long> holders(UUID village) {
        Map<String, Long> out = new TreeMap<>();
        for (Tag e : bonds(village)) {
            CompoundTag b = (CompoundTag) e;
            out.merge(b.getString("name"), b.getLong("face"), Long::sum);
        }
        return out;
    }

    /** What this folk holds in the town's bonds, pennies. */
    public static long heldBy(UUID village, UUID who) {
        long n = 0;
        String k = who.toString();
        for (Tag e : bonds(village)) if (k.equals(((CompoundTag) e).getString("who"))) n += ((CompoundTag) e).getLong("face");
        return n;
    }

    /** The folk card's line, or "". */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "";
        long held = heldBy(id, f.getUUID());
        return held <= 0 ? "" : "Holds " + Businesses.pence(held) + " of the town's bonds";
    }

    // ------------------------------------------------------------------ players

    /** A player buys so many coins of the town's bonds at the day's rate: its coin into the treasury, the IOUs paid first. */
    public static String playerBuys(ServerLevel level, Player p, UUID village, int coins) {
        if (!MarketMaker.live(village)) return "The town sells no bonds till its market opens.";
        long room = room(village);
        int n = (int) Math.min(coins, room / 100);
        if (n <= 0) return "The town has all the bonds out it can carry (" + Businesses.pence(outstanding(village)) + "); none to sell you today.";
        n = Math.min(n, Market.coinsHeld(p));
        if (n <= 0) return "You've no coin on you for a bond.";
        long day = level.getDayTime() / 24000L;
        int bp = rateBp(village);
        Market.payOut(p, n);
        CoinCensus.playerIn(village, n, p, PlayerFinance.Cat.INVESTED, "town bonds");
        Businesses.toTreasury(village, n * 100L);
        issue(village, "player", p.getUUID().toString(), p.getName().getString(), n * 100L, bp, day);
        long paid = Accounts.debts(village) > 0 ? Accounts.payIous(village, Math.min(n * 100L, Accounts.debts(village)), "bonds sold") : 0;
        Accounts.add(weekBook(village, day), "sold", n * 100L);
        Accounts.add(weekBook(village, day), "funded", paid);
        CapitalBook.tell(village, day, p.getName().getString() + " bought " + n + " coin of the town's bonds at " + pct(bp) + " a week");
        return "You lent the town " + n + Homes.coins(n) + " on a bond at " + pct(bp) + " a week, repaid in " + TERM + " days (or rolled over at the day's rate). "
            + "Coupons wait for you at the hall: collect them on the Town Hall's Money tab.";
    }

    /** A player takes what waits for it at the hall: coupons and repayments, whole coins. */
    public static String playerCollects(Player p, UUID village) {
        CompoundTag d = Accounts.sub(book(village), "due");
        String k = p.getUUID().toString();
        long due = d.getLong(k);
        int coins = (int) (due / 100);
        if (coins <= 0) return "Nothing waits for you on the town's bonds" + (due > 0 ? " (" + due + " hundredths, short of a coin)" : "") + ".";
        d.putLong(k, due - coins * 100L);
        if (d.getLong(k) <= 0) d.remove(k);
        Dealings.giveCoins(p, coins);
        PlayerFinance.collected(p, village, coins);                   // [purse20] booked by kind (coupons, title rent, shares sold), counted
        CapitalBook.dirty();
        return "The hall paid you " + coins + Homes.coins(coins) + " on your bonds.";
    }

    /** The player's bonds in words. */
    public static String playerLine(Player p, UUID village) {
        long held = heldBy(village, p.getUUID());
        long due = Accounts.sub(book(village), "due").getLong(p.getUUID().toString());
        return "Your bonds: " + Businesses.pence(held) + " lent to the town" + (due > 0 ? "; " + Businesses.pence(due) + " waiting for you at the hall" : "") + ".";
    }

    // ------------------------------------------------------------------ words

    static String pct(int bp) {
        return String.format(Locale.ROOT, "%.1f%%", bp / 100.0);
    }

    /** The town's debt in a line. */
    public static String line(UUID village) {
        return "Bonds " + Businesses.pence(outstanding(village)) + " out at " + pct(rateBp(village)) + " a week (room " + Businesses.pence(room(village))
            + " of " + Businesses.pence(ceiling(village)) + "), coupons " + Businesses.pence(weekCoupons(village)) + " a week"
            + (arrears(village) > 0 ? ", " + Businesses.pence(arrears(village)) + " behind" : "") + "; IOUs " + Businesses.pence(Accounts.debts(village));
    }

    /** The bonds for the Capital page. */
    static CompoundTag report(UUID village) {
        CompoundTag t = new CompoundTag();
        t.putLong("out", outstanding(village));
        t.putInt("bp", rateBp(village));
        t.putLong("room", room(village));
        t.putLong("ceiling", ceiling(village));
        t.putLong("coupons", weekCoupons(village));
        t.putLong("arrears", arrears(village));
        t.putLong("ious", Accounts.debts(village));
        t.putLong("limit", TownBudget.limit(village));
        t.putLong("load", TownBudget.load(village));                       // [econ51] what weighs on its works
        t.putLong("week", TownBudget.week(village));
        t.putLong("soldAll", book(village).getLong("soldAll"));
        t.put("thisWeek", book(village).getCompound("week").copy());
        t.put("lastWeek", book(village).getCompound("lastWeek").copy());
        t.putString("why", book(village).getString("why"));
        ListTag hs = new ListTag();
        for (Map.Entry<String, Long> e : holders(village).entrySet()) {
            CompoundTag h = new CompoundTag();
            h.putString("name", e.getKey());
            h.putLong("p", e.getValue());
            hs.add(h);
        }
        t.put("holders", hs);
        return t;
    }

    @Nullable
    static CompoundTag forTests(UUID village, int i) {
        ListTag l = bonds(village);
        return i < l.size() ? l.getCompound(i) : null;
    }

    public static CompoundTag reportForTests(UUID village) {
        return report(village);
    }

    @Nullable
    public static CompoundTag firstForTests(UUID village) {
        return forTests(village, 0);
    }

    public static int countForTests(UUID village) {
        return bonds(village).size();
    }

    public static void beforeWagesForTests(ServerLevel level, Villages.Village v) {
        book(v.id()).remove("soldOn");
        beforeWages(level, v, level.getDayTime() / 24000L);
    }

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    public static void paydayForTests(UUID village, int bill, int purse) {
        payday(village, bill, purse);
    }

    static void resetForTests() {
        PAYDAY.clear();
    }
}
