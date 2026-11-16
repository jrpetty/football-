package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [econ-1b] The town's budget, once its market is open: what comes in, what goes out, and the works it can afford.
 *
 * <p><b>Coming in.</b> The taxes the council sets (TaxBook: the head tax, the share of wages, the toll, the war levy, and
 * now the <b>profits tax</b>, a share of every private business's week's profit taken at its weekly reckoning before its
 * owners draw); the rents the businesses pay for the town's buildings; and the market's surplus: the storehouse is the
 * town's own enterprise, so whatever it holds over its float (its cash and the town's own IOUs to it alike) is the town's.
 * Each morning the IOUs are set against what the market holds over its float, and once a week its spare cash goes to the
 * treasury.
 *
 * <p><b>Going out.</b> The departments' spending (Accounts: the works, the watch, the army, the relief, the hall...), each
 * in its own book.
 *
 * <p><b>Living within its means.</b> A department short of coin still writes the market an IOU (nothing is ever refused
 * for want of money, so a town never deadlocks), but a town that owes its market more than it can carry (more than half
 * a week's revenue, and never less than ten coins) puts off its works that can wait: the café, the fountain, the park,
 * the theatre, the town's ornaments. What a town lives by always goes ahead: its stores, its homes and beds, its food,
 * its well, its walls and watch, and the buildings its age asks for. And the council, at its next sitting, is moved to
 * raise a tax (the profits tax first) until the IOUs are paid. Kept with the town's accounts (Accounts).
 */
public final class TownBudget {

    private TownBudget() {}

    /** The least IOU a town carries before it puts off its works, pennies; and the share of a week's revenue it may owe. */
    static final long LEAST_LIMIT = 1000;
    static final double WEEK_SHARE = 0.5;
    /** The days of the budget kept. */
    static final int DAYS = 14;

    /**
     * What a town always builds, whatever it owes: its stores, homes and beds, its food, water and health, its defences,
     * the buildings its age asks for before the next (so its age never waits on its purse), and its dead.
     */
    static final Set<String> ESSENTIAL = Set.of(
        "storage", "storehouse", "shelter", "house", "house2", "flats", "well", "granary", "farm", "allotments", "orchard", "windmill",
        "watermill", "fortify", "watchtower", "barracks", "gateway", "smeltery", "hall", "workshop", "market", "lighthouse", "chapel",
        "graveyard", "infirmary", "firestation", "stockade", "guesthouse", "dock", "harbour");

    /** Is this project one the town builds whatever it owes? (A war footing's defences and a requested one are kept too.) */
    public static boolean essential(String project) {
        if (project == null) return true;
        String p = project.toLowerCase(Locale.ROOT);
        if (ESSENTIAL.contains(p)) return true;
        return p.startsWith("house") || p.contains("wall") || p.contains("farm") || p.contains("barn");
    }

    static CompoundTag books(UUID village) {
        return Accounts.sub(Accounts.town(village), "budget");
    }

    // ------------------------------------------------------------------ in and out

    /** Money in to the town, so many pennies, of a kind ("taxes", "profits", "rents", "market", "fees"): today's book. */
    public static void in(UUID village, String kind, long p) {
        if (village == null || p <= 0) return;
        CompoundTag t = today(village);
        Accounts.add(Accounts.sub(t, "in"), kind, p);
        Accounts.add(Accounts.sub(books(village), "inAll"), kind, p);
        Accounts.dirty();
    }

    /** Today's book, opened if need be (the last fortnight kept, oldest first). */
    static CompoundTag today(UUID village) {
        CompoundTag b = books(village);
        long day = Accounts.dayNow();
        ListTag days = Accounts.list(b, "days");
        if (!days.isEmpty()) {
            CompoundTag last = days.getCompound(days.size() - 1);
            if (last.getLong("day") == day) return last;
        }
        CompoundTag t = new CompoundTag();
        t.putLong("day", day);
        days.add(t);
        while (days.size() > DAYS) days.remove(0);
        Accounts.dirty();
        return t;
    }

    /** The last seven days' money in, pennies (today's so far among it). */
    public static long revenue7(UUID village) {
        long n = 0, day = Accounts.dayNow();
        for (Tag e : Accounts.list(books(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= 7) continue;
            CompoundTag in = t.getCompound("in");
            for (String k : in.getAllKeys()) n += in.getLong(k);
        }
        return n;
    }

    /** The last seven days' money in by kind, pennies. */
    static java.util.Map<String, Long> revenue7ByKind(UUID village) {
        java.util.Map<String, Long> out = new java.util.TreeMap<>();
        long day = Accounts.dayNow();
        for (Tag e : Accounts.list(books(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= 7) continue;
            CompoundTag in = t.getCompound("in");
            for (String k : in.getAllKeys()) out.merge(k, in.getLong(k), Long::sum);
        }
        return out;
    }

    /** The departments' spending over the last seven days, pennies: [econ-1d] booked as it is spent (net of what was handed
     *  back), today's among it. (It was taken from a snapshot of their books each morning, a day behind the IOUs it ran up,
     *  and a test's look before the morning saw it two days behind: the ten-day test's "IOUs up 467c on 146c spent".) */
    public static long spent7(UUID village) {
        long n = 0, day = Accounts.dayNow();
        for (Tag e : Accounts.list(books(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= 7) continue;
            n += t.contains("live") ? t.getLong("live") : t.getLong("spent");
        }
        return n;
    }

    /** [econ-1d] Today's spending by the departments so far, pennies. */
    public static long spentToday(UUID village) {
        return today(village).getLong("live");
    }

    /** [econ-1d] A department spent (or was handed back) so many pennies (Accounts.deptBook): today's book, live. The
     *  market's own trade is not the town's spending. */
    static void spent(UUID village, Accounts.Dept d, long spent, long in) {
        if (village == null || d == Accounts.Dept.MARKET || (spent == 0 && in == 0) || !MarketMaker.live(village)) return;
        Accounts.add(today(village), "live", spent - in);
        Accounts.dirty();
        if (spent > 0) check(village);
    }

    /** What the town may owe its market before it puts off its works: half a week's revenue, ten coins at least. */
    public static long limit(UUID village) {
        return Math.max(LEAST_LIMIT, Math.round(revenue7(village) * WEEK_SHARE));
    }

    /** Does the town owe its market more than it can carry (and its market is open)? */
    public static boolean owesTooMuch(UUID village) {
        return village != null && MarketMaker.live(village) && Accounts.debts(village) > limit(village);
    }

    /**
     * [econ-1d] How tight the town's money is: 0 at ease; 1, careful (it owes its market over half what it can carry, or has
     * spent today over its day's share of its week's revenue and what it may still borrow): the works that can wait are put
     * off and the buildings go up plain (no lanterns, carpets, flowers or bookshelves bought for them); 2, short (it owes
     * more than it can carry): besides, one building crew at a time on what it cannot do without. Its essentials (stores,
     * homes and beds, food, water, walls, the age's buildings) always go ahead, so it never deadlocks.
     */
    public static int pressure(UUID village) {
        if (village == null || !MarketMaker.live(village)) return 0;
        long owed = Accounts.debts(village), lim = limit(village);
        if (owed > lim) return 2;
        if (owed * 2 > lim || spentToday(village) > allowance(village)) return 1;
        return 0;
    }

    /** [econ-1d] What the works may spend in a day: a day's share of the week's revenue, and of what it may still borrow. */
    public static long allowance(UUID village) {
        long room = Math.max(0, limit(village) - Accounts.debts(village));
        return Math.max(LEAST_LIMIT / 2, revenue7(village) / 7 + room / 4);
    }

    /** [econ-1d] What the works may always spend in a day, whatever the town owes, pennies: its essentials go ahead, at a pace. */
    static final long WORKS_FLOOR = 2500;

    /**
     * [econ-1d] Short (it owes its market more than it can carry) and the works have spent today what the town can find for
     * them (a day's share of its week's revenue, and twenty-five coins at the least): its builders fetch no more for the day.
     * Its essential buildings still go up every day, at the pace its money allows; nothing waits for ever.
     */
    public static boolean worksWait(@javax.annotation.Nullable UUID village) {
        if (village == null || pressure(village) < 2) return false;
        long today = Accounts.deptBook(village, Accounts.Dept.WORKS)[0];
        return today > Math.max(WORKS_FLOOR, revenue7(village) / 7);
    }

    /** [econ-1d] Careful with its money: the buildings go up plain (Villages' decorations not bought). */
    public static boolean frugal(@javax.annotation.Nullable UUID village) {
        return pressure(village) >= 1;
    }

    /** [econ-1d] Short: one building crew at a time (Villages.crewsAllowed). */
    public static boolean oneCrew(@javax.annotation.Nullable UUID village) {
        return pressure(village) >= 2;
    }

    /** [econ-1d] A decoration a careful town still buys for a building: a bed, a door, a torch, a window, the hay of a barn. */
    public static boolean plainNeed(String part) {
        return switch (part) {
            case "BED", "DOOR", "TORCH", "WINDOW", "HAY", "WATER", "CAMPFIRE", "SMOKER" -> true;
            default -> false;
        };
    }

    /** Is this project put off for want of money? Never an essential one, never in a town whose market is not open. */
    public static boolean defers(UUID village, String project) {
        return !essential(project) && pressure(village) >= 1;               // [econ-1d] careful, not only short
    }

    /** [econ-1d] The last pressure each town was at (the works put off the moment it tightens, not the next morning). */
    private static final java.util.Map<UUID, Integer> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * [econ-1d] Looked at as the town spends (each IOU and each department's spending): the moment it tightens, the works that
     * can wait are put off for the day, as the morning's look does, without waiting for the morning.
     */
    static void check(UUID village) {
        int now = pressure(village);
        Integer was = LAST.put(village, now);
        if (was != null && now <= was) return;
        if (now >= 1) putOff(village);
    }

    /** The works that can wait put off for a day. Returns what was put off. */
    static List<String> putOff(UUID village) {
        List<String> put = new ArrayList<>();
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long until = (server == null ? 0 : server.overworld().getGameTime()) + 24000L;
        for (String p : Villages.projectsWanted(village)) {
            if (essential(p)) continue;
            Villages.defer(village, p, until, "put off: the town owes its market " + Businesses.pence(Accounts.debts(village))
                + " and keeps its money for its essentials");
            put.add(p);
        }
        return put;
    }

    public static void resetForTests() {
        LAST.clear();
    }

    /**
     * The profits tax on a business's week (Businesses.draw, before its owners draw): its share of the week's profit, out
     * of its till into the treasury (owed to the town, as its rent, if the till is short), in its books as a tax, in the
     * council's books and the town's budget; the town's IOUs to its market paid first out of it. Returns the pennies taxed.
     */
    static long profitsTax(ServerLevel level, UUID village, Business b, long weekNet, long day) {
        if (b.town() || b.mirror() || !MarketMaker.live(village)) return 0;
        int pct = TaxBook.profitsPercent(village);
        long already = instalments(village).getLong(b.id);                  // [econ-1d] what it paid on account this week
        instalments(village).remove(b.id);
        long owed = Math.max(0, weekNet) * Math.max(0, pct) / 100;
        if (already > owed) {
            // [econ-1d] Paid more on account than the week came to (it lost at the end): the rest handed back.
            long back = Businesses.fromTreasury(village, already - owed);
            b.till += back;
            b.today(day).taxes -= back;
            b.allTime.taxes -= back;
            Businesses.dirty();
            return already - back;
        }
        if (weekNet <= 0 || pct <= 0) return already;
        long tax = owed - already;
        if (tax <= 0) return already;
        long paid = Businesses.payOut(level, village, b, tax, day);
        Businesses.toTreasury(village, paid);
        Business.Day d = b.today(day);
        d.taxes += tax;
        b.allTime.taxes += tax;
        b.note(day, "paid " + Businesses.pence(tax) + " profits tax (" + pct + "% of the week's " + Businesses.pence(weekNet) + ")");
        CompoundTag bk = books(village);
        long carry = bk.getLong("profitsCarry") + paid;
        int coins = (int) (carry / 100);
        bk.putLong("profitsCarry", carry - coins * 100L);
        TaxBook.book(village, day, "profits", coins);
        in(village, "profits", paid);
        Accounts.payIous(village, paid, "the profits tax");             // [econ-1d] (how)
        Businesses.dirty();
        return tax + already;                                                 // [econ-1d] the week's whole tax
    }

    /** [econ-1d] Each business's profits tax paid on account this week, pennies. */
    static CompoundTag instalments(UUID village) {
        return Accounts.sub(books(village), "instalments");
    }

    /**
     * [econ-1d] The profits tax as it is earned (Businesses' morning, the days between its weekly reckonings): its share of
     * the week's profit so far, less what it has paid on account this week, out of its till while the till has it (never
     * on its credit). Settled at the week's end (profitsTax): a town lives on its revenue day by day, and a farm's
     * harvest money sat a week in its till untaxed while the town wrote IOUs. Returns the pennies paid.
     */
    static long instalment(ServerLevel level, UUID village, Business b, long day) {
        if (b.town() || b.mirror() || !MarketMaker.live(village) || "storehouse".equals(b.id)) return 0;
        int pct = TaxBook.profitsPercent(village);
        if (pct <= 0) return 0;
        int days = (int) Math.floorMod(day - b.opened - 6, 7L);
        if (days <= 0 || day - b.opened < 2) return 0;
        long net = b.last(day - 1, days).net();
        long paid = instalments(village).getLong(b.id);
        long due = net * pct / 100 - paid;
        long pay = Math.min(due, b.till - 200);                              // what its till can spare (two coins kept for change)
        if (pay < 10) return 0;
        b.till -= pay;
        Businesses.toTreasury(village, pay);
        instalments(village).putLong(b.id, paid + pay);
        Business.Day d = b.today(day);
        d.taxes += pay;
        b.allTime.taxes += pay;
        CompoundTag bk = books(village);
        long carry = bk.getLong("profitsCarry") + pay;
        int coins = (int) (carry / 100);
        bk.putLong("profitsCarry", carry - coins * 100L);
        TaxBook.book(village, day, "profits", coins);
        in(village, "profits", pay);
        Accounts.payIous(village, pay, "the profits tax");
        Businesses.dirty();
        return pay;
    }

    /**
     * [econ-1d] The business rates on a business's week (Businesses.draw, before the profits tax): so many coins a week for
     * its premises (twice for a firm in larger premises), profit or none, out of its till into the treasury (owed to the
     * town, as its rent, if the till is short); in its books as a tax, in the council's books and the town's budget, the
     * town's IOUs paid first out of it. Returns the pennies charged.
     */
    static long rates(ServerLevel level, UUID village, Business b, long day) {
        if (b.town() || b.mirror() || !MarketMaker.live(village) || "storehouse".equals(b.id)) return 0;
        int coins = TaxBook.ratesCoins(village);
        if (coins <= 0) return 0;
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null && r.getInt("premises") > 0) coins *= 2;
        long due = coins * 100L;
        long paid = Businesses.payOut(level, village, b, due, day);
        Businesses.toTreasury(village, paid);
        Business.Day d = b.today(day);
        d.taxes += due;
        b.allTime.taxes += due;
        b.note(day, "paid " + Businesses.pence(due) + " in business rates");
        TaxBook.book(village, day, "rates", (int) (paid / 100));
        in(village, "business rates", paid);
        Accounts.payIous(village, paid, "the business rates");
        Businesses.dirty();
        return due;
    }

    /**
     * [econ-1d] A household's rent for one of the town's houses, so many coins into the treasury (Homes.tenants): money in to
     * the town like any other, in its budget, and its IOUs to the market paid first out of it (it was only counted as the
     * treasury's coin before, so the town's biggest landlord's income never showed in its week nor paid what it owed).
     */
    static void houseRent(UUID village, int coins) {
        if (village == null || coins <= 0 || !MarketMaker.live(village)) return;
        in(village, "house rents", coins * 100L);
        Accounts.payIous(village, coins * 100L, "house rents");
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The budget's morning (MarketMaker.morning, after the IOUs are set against the market's holdings): yesterday's
     * spending by the departments put in its book; and, the town owing more than it can carry, its works that can wait put
     * off for the day (told once, when it starts).
     */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag b = books(id);
        long all = 0;
        for (Accounts.Dept d : Accounts.Dept.values()) {
            if (d == Accounts.Dept.MARKET) continue;
            all += Accounts.deptBook(id, d)[2];
        }
        long was = b.contains("spentAll") ? b.getLong("spentAll") : all;
        today(id).putLong("spent", Math.max(0, all - was));
        b.putLong("spentAll", all);
        payDown(level, v, day);                                              // [econ-1d] what the treasury can spare, off the IOUs
        LAST.put(id, pressure(id));
        boolean short_ = pressure(id) >= 1;                                  // [econ-1d] careful, not only short
        List<String> put = short_ ? putOff(id) : new ArrayList<>();
        if (short_ && !b.getBoolean("short")) {
            Villages.tell(id, day, "the council put off the works that can wait" + (put.isEmpty() ? "" : " (" + String.join(", ", put) + ")")
                + ": the town owes its market " + Businesses.pence(Accounts.debts(id)) + " on its IOUs, " + (owesTooMuch(id) ? "more than it can carry"
                : "over half what it can carry") + "; its stores,"
                + " homes, food and walls go ahead");
            Accounts.add(b, "shortDays", 1);
        } else if (!short_ && b.getBoolean("short")) {
            Villages.tell(id, day, "the town's IOUs to its market are down to " + Businesses.pence(Accounts.debts(id)) + ": its works go ahead again");
        }
        if (short_) Accounts.add(b, "deferredDays", 1);
        b.putInt("shortRun", owesTooMuch(id) ? b.getInt("shortRun") + 1 : 0);   // [econ-1d] mornings running past what it can carry
        b.putBoolean("short", short_);
        Accounts.dirty();
    }

    /** [econ-1d] The days of the wage bill the treasury keeps before it pays its IOUs down out of what it holds. */
    static final int KEEPS_DAYS = 2;

    /**
     * [econ-1d] Each morning: what the treasury holds over two days of its own wage bill goes to pay its IOUs to the market,
     * oldest first (the market wants coin to buy the next harvest with, not the town's paper). A town that pays its way
     * pays its debts down when it can, not only out of each tax as it comes in.
     */
    static long payDown(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        long owed = Accounts.debts(id);
        if (owed <= 0) return 0;
        long keep = (long) Math.max(1, Market.wageBill(id)) * KEEPS_DAYS * 100L;
        long spare = Businesses.treasuryPence(id) - keep;
        if (spare <= 0) return 0;
        long paid = Accounts.payIous(id, Math.min(owed, spare), "the treasury's spare coin");
        if (paid >= 500) Villages.tell(id, day, "the treasury paid " + Businesses.pence(paid) + " off what the town owed its market");
        return paid;
    }

    /**
     * [econ-1d] Should the council be called together on the town's money before its weekly day: it has owed its market more
     * than it can carry three mornings running, and has not sat on the taxes for four days.
     */
    static boolean callsCouncil(UUID village, long day, long lastSat) {
        if (!owesTooMuch(village)) return false;
        CompoundTag b = books(village);
        return b.getInt("shortRun") >= 3 && day + 1 - lastSat >= 4;
    }

    // ------------------------------------------------------------------ in words

    /** The budget in a line, for the Market page and /village market. */
    public static String line(UUID village) {
        if (!MarketMaker.live(village)) return "";
        java.util.Map<String, Long> in = revenue7ByKind(village);
        List<String> parts = new ArrayList<>();
        for (java.util.Map.Entry<String, Long> e : in.entrySet()) parts.add(e.getKey() + " " + Businesses.pence(e.getValue()));
        return "The town's week: in " + Businesses.pence(revenue7(village)) + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")")
            + ", spent " + Businesses.pence(spent7(village)) + "; IOUs " + Businesses.pence(Accounts.debts(village)) + " of the "
            + Businesses.pence(limit(village)) + " it can carry" + switch (pressure(village)) {      // [econ-1d] how tight
                case 2 -> ": short, the works that can wait put off, its buildings plain, one crew at a time";
                case 1 -> ": careful, the works that can wait put off, its buildings plain";
                default -> "";
            } + "; profits tax " + TaxBook.profitsPercent(village) + "%";
    }

    /** Tests: the profits tax on a business's week's profit, now. */
    public static long profitsTaxForTests(ServerLevel level, UUID village, Business b, long weekNet) {
        return profitsTax(level, village, b, weekNet, level.getDayTime() / 24000L);
    }

    /** Tests: is the town short (owing more than it can carry) now. */
    public static boolean shortForTests(UUID village) {
        return owesTooMuch(village);
    }

    /** Tests: the budget's morning now. */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        morning(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the days the town has put its works off. */
    public static long deferredDaysForTests(UUID village) {
        return books(village).getLong("deferredDays");
    }
}
