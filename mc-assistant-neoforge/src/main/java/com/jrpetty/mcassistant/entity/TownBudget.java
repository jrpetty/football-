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

    /** The departments' spending over the last seven days, pennies (from each morning's snapshot of their books). */
    public static long spent7(UUID village) {
        long n = 0, day = Accounts.dayNow();
        for (Tag e : Accounts.list(books(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= 7) continue;
            n += t.getLong("spent");
        }
        return n;
    }

    /** What the town may owe its market before it puts off its works: half a week's revenue, ten coins at least. */
    public static long limit(UUID village) {
        return Math.max(LEAST_LIMIT, Math.round(revenue7(village) * WEEK_SHARE));
    }

    /** Does the town owe its market more than it can carry (and its market is open)? */
    public static boolean owesTooMuch(UUID village) {
        return village != null && MarketMaker.live(village) && Accounts.debts(village) > limit(village);
    }

    /** Is this project put off for want of money? Never an essential one, never in a town whose market is not open. */
    public static boolean defers(UUID village, String project) {
        return !essential(project) && owesTooMuch(village);
    }

    /**
     * The profits tax on a business's week (Businesses.draw, before its owners draw): its share of the week's profit, out
     * of its till into the treasury (owed to the town, as its rent, if the till is short), in its books as a tax, in the
     * council's books and the town's budget; the town's IOUs to its market paid first out of it. Returns the pennies taxed.
     */
    static long profitsTax(ServerLevel level, UUID village, Business b, long weekNet, long day) {
        if (weekNet <= 0 || b.town() || b.mirror() || !MarketMaker.live(village)) return 0;
        int pct = TaxBook.profitsPercent(village);
        if (pct <= 0) return 0;
        long tax = weekNet * pct / 100;
        if (tax <= 0) return 0;
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
        Accounts.payIous(village, paid);
        Businesses.dirty();
        return tax;
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
        boolean short_ = owesTooMuch(id);
        List<String> put = new ArrayList<>();
        if (short_) {
            for (String p : Villages.projectsWanted(id)) {
                if (essential(p)) continue;
                Villages.defer(id, p, level.getGameTime() + 24000L, "put off: the town owes its market " + Businesses.pence(Accounts.debts(id))
                    + " and has the money for its essentials only");
                put.add(p);
            }
        }
        if (short_ && !b.getBoolean("short")) {
            Villages.tell(id, day, "the council put off the works that can wait" + (put.isEmpty() ? "" : " (" + String.join(", ", put) + ")")
                + ": the town owes its market " + Businesses.pence(Accounts.debts(id)) + " on its IOUs, more than it can carry; its stores,"
                + " homes, food and walls go ahead");
            Accounts.add(b, "shortDays", 1);
        } else if (!short_ && b.getBoolean("short")) {
            Villages.tell(id, day, "the town's IOUs to its market are down to " + Businesses.pence(Accounts.debts(id)) + ": its works go ahead again");
        }
        if (short_) Accounts.add(b, "deferredDays", 1);
        b.putBoolean("short", short_);
        Accounts.dirty();
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
            + Businesses.pence(limit(village)) + " it can carry" + (owesTooMuch(village) ? ": works that can wait are put off" : "")
            + "; profits tax " + TaxBook.profitsPercent(village) + "%";
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
