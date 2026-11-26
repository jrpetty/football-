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
        "terrace", "terrace2", "terrace3", "terrace4", "terrace5",                                           // [house34] a terrace is homes
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

    /** [econ-1d] What a town may owe its market whatever its revenue, a head of its grown folk, pennies (a few days' wages). */
    static final long LEAST_A_HEAD = 200;

    /** What the town may owe its market before it puts off its works: half a week's revenue; ten coins at least, [econ-1d] and
     *  two coins a grown folk (a young town of twenty, its revenue still to come in, put its houses off at ten coins owed). */
    public static long limit(UUID village) {
        return Math.round(Math.max(Math.max(LEAST_LIMIT, LEAST_A_HEAD * grown(village)), Math.round(week(village) * WEEK_SHARE))
            * EconCycle.spendFactor(village));                              // [econ4] more in a slump, less in a boom
    }

    /**
     * [econ-1e] The town's week's revenue: the last seven days', or, its market open fewer days than that, what those days
     * brought in at a week's rate. The day after its Market Opening Day a town's "week" was one day's taxes, and it could
     * carry a seventh of what it would a week on: it put off its houses on the strength of a week it had not had yet.
     */
    static long week(UUID village) {
        long opened = MarketMaker.openedDay(village);
        if (opened < 0) return revenue7(village);
        // [biz30d] At a week's rate over the last fortnight (the days it has been open, if fewer): the market's share comes in
        // lumps, as it sets the town's IOUs against what it holds over its float, and a week without one halved what the
        // town could carry overnight (ent15: 448c on day 14, 165c on day 17, 470c on day 20), and the bound with it.
        long days = Math.max(1, Math.min(DAYS, Accounts.dayNow() - opened + 1));
        return revenueOver(village, days) * 7 / days;
    }

    /** [biz30d] The last so many days' money in, pennies (today's so far among it). */
    static long revenueOver(UUID village, long days) {
        long n = 0, day = Accounts.dayNow();
        for (Tag e : Accounts.list(books(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= days) continue;
            CompoundTag in = t.getCompound("in");
            for (String k : in.getAllKeys()) n += in.getLong(k);
        }
        return n;
    }

    /** [econ-1d] The town's grown folk. */
    static int grown(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isBaby()) n++;
        return n;
    }

    /** Does the town owe more than it can carry (and its market is open)? [econ51] Its IOUs and the bonds it sold to pay
     *  them off alike (the load): an IOU paid with a bond is still owed. */
    public static boolean owesTooMuch(UUID village) {
        return village != null && MarketMaker.live(village) && load(village) > limit(village);
    }

    /**
     * [econ51] The debt that weighs on the works, pennies (DebtBrake.load): its IOUs to its market, and its bonds over a
     * week's revenue. The IOUs alone are not the debt: each morning the treasurer sells bonds to pay off all but a quarter
     * of what it can carry, so a brake on the IOUs alone let go every morning and the bonds climbed on (a long run's town
     * owed four thousand coins in bonds and six on its IOUs, and was "at ease").
     */
    public static long load(UUID village) {
        if (village == null) return 0;
        return com.jrpetty.mcassistant.village.DebtBrake.load(Accounts.debts(village), TownBonds.outstanding(village), week(village));
    }

    /** [econ51] The least the works may spend in a day whatever is owed, pennies: two coins, and a quarter coin a grown folk. */
    static final long LEAST_DAY = 200, LEAST_DAY_A_HEAD = 25;

    /**
     * [econ51] A day's revenue for the brake, pennies: the week's at a day's rate, or the last three days' if they were
     * leaner (the market's share comes in only while its cash is over its float, and a fortnight's average let the works go
     * on spending at last week's rate for a week after it stopped).
     */
    static long dayRevenue(UUID village) {
        long opened = MarketMaker.openedDay(village);
        long open = opened < 0 ? 7 : Math.max(1, Math.min(DAYS, Accounts.dayNow() - opened + 1));
        long recent = Math.min(3, open);
        long lately = revenueOver(village, recent) / recent;
        return Math.min(week(village) / 7, lately);
    }

    /** [econ51] What the works may spend today, pennies (Long.MAX_VALUE: no bound): DebtBrake.allowance. */
    public static long allowanceToday(UUID village) {
        if (village == null || !MarketMaker.live(village)) return Long.MAX_VALUE;
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? -1 : server.overworld().getGameTime();
        long[] c = ALLOWANCE.get(village);
        if (c != null && now >= 0 && now >= c[0] && now - c[0] < 20) return c[1];
        long a = com.jrpetty.mcassistant.village.DebtBrake.allowance(load(village), limit(village), dayRevenue(village),
            Math.max(LEAST_DAY, LEAST_DAY_A_HEAD * grown(village)));
        if (ALLOWANCE.size() > 1024) ALLOWANCE.clear();
        ALLOWANCE.put(village, new long[]{ now, a });
        return a;
    }

    private static final java.util.Map<UUID, long[]> ALLOWANCE = new java.util.concurrent.ConcurrentHashMap<>();

    /** [econ51] What the works may still spend today, pennies (Long.MAX_VALUE: no bound). */
    public static long worksRoom(@javax.annotation.Nullable UUID village) {
        if (village == null) return Long.MAX_VALUE;
        Integer forced = PRESSURE_FOR_TESTS.get(village);
        if (forced != null && forced < 2) return Long.MAX_VALUE;
        long a = allowanceToday(village);
        if (a == Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0, a - Accounts.deptBook(village, Accounts.Dept.WORKS)[0]);
    }

    /**
     * [econ51] How many of this thing the stores may let the town's works have now (the rest waits for tomorrow's money):
     * all of them, unless the works pay for it (a builder's load, the town's small works out of the stores) while the town
     * owes more than it can carry; then as many as the day's allowance left will buy at the market's ask. The tools of the
     * town's own hands are never held back (they earn the revenue that pays the debt), nor what another account pays for.
     * Read before the take (AssistantEntity.drawFrom, TownWork.takeFromStores, Crafts.takeOne), so nothing is taken that
     * the town cannot pay for on the day: the stores are not emptied onto paper.
     */
    public static int mayTake(net.minecraft.server.level.ServerLevel level, @javax.annotation.Nullable UUID village, net.minecraft.world.item.ItemStack st, int n) {
        if (village == null || n <= 0 || st.isEmpty() || !MarketMaker.live(village)) return n;
        long room = worksRoom(village);
        if (room == Long.MAX_VALUE) return n;
        Accounts.Account a = Accounts.resolve(village, st).account();
        if (a.kind() != Accounts.Kind.DEPT || a.deptOf() != Accounts.Dept.WORKS) return n;
        if (Capital.durable(st)) return n;
        if (room <= 0) return 0;
        double each = Math.max(1.0, MarketMaker.ask(level, village, st.copyWithCount(1)) * 100.0);
        return com.jrpetty.mcassistant.village.DebtBrake.fits(room, each, n);
    }

    /**
     * [econ-1d] How tight the town's money is: 0 at ease; 1, careful (it owes its market over half what it can carry, or has
     * spent today over its day's share of its week's revenue and what it may still borrow): the works that can wait are put
     * off and the buildings go up plain (no lanterns, carpets, flowers or bookshelves bought for them); 2, short (it owes
     * more than it can carry): besides, one building crew at a time on what it cannot do without. Its essentials (stores,
     * homes and beds, food, water, walls, the age's buildings) always go ahead, so it never deadlocks.
     */
    public static int pressure(UUID village) {
        Integer forced = village == null ? null : PRESSURE_FOR_TESTS.get(village);   // [growth22] tests
        if (forced != null) return forced;
        if (village == null || !MarketMaker.live(village)) return 0;
        // Looked at as the town spends (every take on its works): reckoned at most once a second a town.
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? -1 : server.overworld().getGameTime();
        long[] c = PRESSURE.get(village);
        if (c != null && now >= 0 && now >= c[0] && now - c[0] < 20) return (int) c[1];
        long owed = load(village), lim = limit(village);                 // [econ51] its bonds over a week's revenue too
        int p = owed > lim ? 2 : owed * 2 > lim || spentToday(village) > allowance(village) ? 1 : 0;
        if (PRESSURE.size() > 1024) PRESSURE.clear();
        PRESSURE.put(village, new long[]{ now, p });
        return p;
    }

    private static final java.util.Map<UUID, long[]> PRESSURE = new java.util.concurrent.ConcurrentHashMap<>();
    /** [growth22] Tests: a town's money held this tight (null: as it is). */
    private static final java.util.Map<UUID, Integer> PRESSURE_FOR_TESTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void pressureForTests(UUID village, @javax.annotation.Nullable Integer p) {
        if (p == null) PRESSURE_FOR_TESTS.remove(village); else PRESSURE_FOR_TESTS.put(village, p);
    }

    /** [econ-1d] What the works may spend in a day: a day's share of the week's revenue, and of what it may still borrow. */
    public static long allowance(UUID village) {
        long room = Math.max(0, limit(village) - load(village));            // [econ51] (the load)
        return Math.max(LEAST_LIMIT / 2, revenue7(village) / 7 + room / 4);
    }

    /**
     * [econ-1d] The dearest building block a careful town buys for its walls, coin each: three times the market's cobblestone
     * or planks, whichever is dearer (no bricks or stone bricks at four times the price: a careful town's houses go up of
     * cobblestone and planks, a wall of plain stone as good to sleep behind).
     */
    public static double dearBlock(ServerLevel level, UUID village) {
        double cobble = MarketMaker.ask(level, village, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
        double planks = MarketMaker.ask(level, village, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS));
        return 3 * Math.max(cobble, planks);
    }

    /**
     * [econ-1e] The dearest building block a town at ease buys for its walls, coin each: ten times its cobblestone or planks. A
     * builder loading up for a house at ease took four hundred coins of bricks in one go, the clay scarce and the brick dear,
     * for walls as good of stone at a fortieth of the price: no town, however easy, builds so.
     */
    public static double dearestBlock(ServerLevel level, UUID village) {
        return dearBlock(level, village) * 10.0 / 3.0;
    }

    /**
     * [econ-1d] Short (it owes more than it can carry) and the works have spent today the half of the day's allowance that is
     * not kept for its homes and food ([econ51] DebtBrake: a share of a day's revenue, the rest to pay its debts down): its
     * builders fetch no more for the day for what is not core. Its homes and food go on with the rest of the allowance;
     * nothing waits for ever.
     */
    public static boolean worksWait(@javax.annotation.Nullable UUID village) {
        if (village == null || pressure(village) < 2) return false;
        // [econ51] The day's allowance (DebtBrake), its first half for the homes and food alone: what is not core waits once
        // the works have spent that much (it was three fifths of a day's revenue, ten coins at the least, for anything not core).
        long a = allowanceToday(village);
        if (a == Long.MAX_VALUE) return false;
        return Accounts.deptBook(village, Accounts.Dept.WORKS)[0] * 2 >= a;
    }

    /**
     * [econ-1e] What a town builds at the pace of its hands whatever it owes: the next house (and the flats), its stores and
     * well, what feeds it (the fields, the barns, the granary, the mills), and the buildings its age asks for before the next.
     * A town in debt defers its luxuries and its grand works, not the roof over the next family: with every building held to
     * three fifths of a day's revenue, a ten-day town grew to twenty-six folk where it had grown to forty-three, a house
     * every third day. Its walls, watch and the rest of its essentials still wait for the day's money when it is short.
     */
    static final Set<String> CORE = Set.of(
        "storage", "storehouse", "shelter", "house", "house2", "flats", "well", "granary", "farm", "allotments", "orchard", "windmill",
        "terrace", "terrace2", "terrace3", "terrace4", "terrace5",                                           // [house34]
        "watermill", "fortify", "smeltery", "hall", "workshop", "watchtower", "market", "lighthouse", "chapel", "gateway");

    /** [econ-1e] Is this project one a short town still builds every day at its hands' pace (homes, food, its age's)? */
    public static boolean core(String project) {
        if (project == null) return true;
        String p = project.toLowerCase(Locale.ROOT);
        return CORE.contains(p) || p.startsWith("house") || p.contains("farm") || p.contains("barn");
    }

    /** [econ-1e] Its builders wait for tomorrow's money for this project: short, the day's money for the works spent, and not a
     *  home, food or its age's building; or the town at its borrowing's end (halted), whatever it is. */
    public static boolean worksWait(@javax.annotation.Nullable UUID village, String project) {
        if (worksRoom(village) <= 0) return true;                           // [econ51] the day's allowance spent: tomorrow
        return worksWait(village) && !core(project);
    }

    /** [econ-1e] Past this many times what it can carry, a town starts nothing new on its works till its revenue brings the IOUs down. */
    static final double HALT = 1.6;                                        // [capital] (was 2: what is in hand finished inside twice what it can carry)

    /**
     * [econ-1e] At its borrowing's end: owing its market more than [capital] one and three fifths of what it can carry (twice,
     * before: the works already in hand, finished as the stores allow, took a ten-day town past twice what it could carry). A town borrows to house
     * its folk, within reason: past that its builders start nothing (its homes too) and its storeys and lecterns wait, till
     * its rents and taxes and its market's surplus bring the IOUs back down; what is half built is finished as the stores
     * allow. A ten-day town that built every house as fast as its hands could ran its IOUs to three times what it could carry.
     */
    public static boolean halted(@javax.annotation.Nullable UUID village) {
        if (village == null || !MarketMaker.live(village)) return false;
        // [econ51] Deep in debt and the day's allowance spent: no longer a wall at the line, past which a town started nothing
        // for days on end (the brake's slope holds its spending to its means; what it starts waits for the day's money).
        return load(village) > HALT * limit(village) && worksRoom(village) <= 0;
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
        PRESSURE.clear();
        ALLOWANCE.clear();
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
        PRESSURE.remove(id);
        ALLOWANCE.remove(id);                                                // [econ51] the new day's allowance
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
        return weekLine(village) + ". " + owingLine(village);
    }

    /** [econ51] The town's week in a line: what came in, by kind, and what its departments spent. */
    public static String weekLine(UUID village) {
        if (!MarketMaker.live(village)) return "";
        java.util.Map<String, Long> in = revenue7ByKind(village);
        List<String> parts = new ArrayList<>();
        for (java.util.Map.Entry<String, Long> e : in.entrySet()) parts.add(e.getKey() + " " + Businesses.pence(e.getValue()));
        return "The town's week: in " + Businesses.pence(revenue7(village)) + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")")
            + ", spent " + Businesses.pence(spent7(village)) + "; profits tax " + TaxBook.profitsPercent(village) + "%";
    }

    /**
     * [econ51] What the town owes, in plain words, for the board (the Market page; the mouse over it says what an IOU is): its
     * IOUs (the notes its treasury leaves its market for what its works took with no coin to pay), the bonds that count
     * with them, what it can carry, and what that does to its builders today. "IOUs 367.23c of the 78.24c it can carry:
     * short, ..." told a player nothing it could use.
     */
    public static String owingLine(UUID village) {
        if (!MarketMaker.live(village)) return "";
        long ious = Accounts.debts(village), load = load(village), carry = limit(village);
        String s = "Owed to its own market on IOUs: " + Businesses.pence(ious);
        if (load > ious) s += ", and " + Businesses.pence(load - ious) + " of bonds past a week's takings";
        s += "; it can carry " + Businesses.pence(carry);
        return s + switch (pressure(village)) {
            case 2 -> {
                long a = allowanceToday(village);
                yield a == Long.MAX_VALUE ? ": over it, one building at a time"
                    : ": over it, so the builders get " + Businesses.pence(a) + " today and the rest pays the debt off";
            }
            case 1 -> ": the extras put off and the buildings plain until it is down";
            default -> ": nothing to worry about";
        };
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
