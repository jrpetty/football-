package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [econ4] The town's trade cycle and what its council does about it.
 *
 * <p>Each morning (Market.tick, before the wages): the statistics taken (EconIndex), the treasury seen to (its reserve:
 * below), the market's merchants sent out (EconRegion), and the cycle stepped (CycleModel: employment, the wage share and
 * the firms' debt moving one another; the town's own employment and new loans blended in; the shocks of the day). Its
 * <b>phase</b> (boom, slowing, slump, recovering) is told in the chronicle when it turns, and the folk talk of it.
 *
 * <p><b>The council reacts</b> as a central bank and a government would:
 * <ul>
 * <li><b>Its rate</b> (CycleModel's rule: up as hands grow scarce and prices run up, down in a slump). The town's lenders
 *     lend over it: the savings club's rate moves with it (FolkCredit.rateBp), so credit is dear in a boom and cheap in a
 *     slump. Told when it moves by half a point.</li>
 * <li><b>Its spending</b>: in a slump its works go ahead on more credit (what the town may owe its market a quarter
 *     higher: TownBudget.limit), in a boom on less.</li>
 * <li><b>Its taxes</b>: a slump with the treasury sound moves the council to ease a tax; a boom with prices running up, to
 *     raise one (TaxBook.motion).</li>
 * <li><b>The minimum wage</b>: the prices up an eighth since it was last set, in a boom or as it slows, it is raised a coin.</li>
 * </ul>
 * Investment follows the times too: in a boom a folk with less nerve opens a business, in a slump it takes more
 * (Enterprise.founderFor).
 *
 * <p><b>The treasury's reserve.</b> A growing town's treasury ran to nothing by its thirtieth day (long-6's runs): every tax
 * and rent as it came in went to pay the IOUs its works had written its market for the houses of its new folk, the
 * treasury's own wages were paid short, and the market sat on its week's surplus till market day. The town keeps two days
 * of its wage bill in hand now: short of it in the morning, the market pays the treasury its surplus over its float
 * there and then (both are the town's; the IOUs it is owed are set against it at the week's end as before), and three
 * mornings running short, the council is called together on its taxes without waiting for its day.
 */
public final class EconCycle {

    private EconCycle() {}

    private static volatile boolean onForTests = false;
    private static volatile boolean plainOff = false;
    private static volatile boolean fiscalOff = false;

    /** Tests: the cycle at work in the tests' plain towns (off there unless a test asks). */
    public static void liveForTests(boolean on) {
        onForTests = on;
    }

    /** Tests: the treasury's reserve kept (true, as in play) or not (the old way). */
    public static void fiscalForTests(boolean on) {
        fiscalOff = !on;
    }

    public static void resetForTests() {
        onForTests = false;
        fiscalOff = false;
        plainOff = !com.jrpetty.mcassistant.SessionReset.opening();
    }

    static boolean enabled() {
        return onForTests || !plainOff;
    }

    static CompoundTag state(UUID village) {
        return Accounts.sub(EconRegion.data(village), "cycle");
    }

    static CycleModel model(CompoundTag s) {
        if (!s.contains("emp")) return new CycleModel();
        CycleModel m = new CycleModel(s.getDouble("emp"), s.getDouble("ws"), s.getDouble("debt"), s.getDouble("rate"), s.getDouble("out"));
        m.inflation = s.getDouble("infl");
        m.growth = s.getDouble("g");
        m.profit = s.getDouble("prof");
        m.invest = s.getDouble("inv");
        return m;
    }

    static void save(CompoundTag s, CycleModel m) {
        s.putDouble("emp", m.employment);
        s.putDouble("ws", m.wageShare);
        s.putDouble("debt", m.debt);
        s.putDouble("rate", m.rate);
        s.putDouble("out", m.output);
        s.putDouble("infl", m.inflation);
        s.putDouble("g", m.growth);
        s.putDouble("prof", m.profit);
        s.putDouble("inv", m.invest);
        Accounts.dirty();
    }

    /** The phase the town is in (null before the cycle has been reckoned once). */
    @Nullable
    public static CycleModel.Phase phase(@Nullable UUID village) {
        if (village == null || !MarketMaker.live(village)) return null;
        CompoundTag s = state(village);
        if (!s.contains("phase")) return null;
        int i = s.getInt("phase");
        return i >= 0 && i < CycleModel.Phase.values().length ? CycleModel.Phase.values()[i] : null;
    }

    // ------------------------------------------------------------------ the morning

    /** The morning (Market.tick, before CapitalBook.beforeWages and the wages). */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!enabled() || TownLod.away(id) || !MarketMaker.live(id)) return;
        if (!EconIndex.record(level, v, day)) return;
        if (!fiscalOff) fiscal(level, v, day);
        EconRegion.morning(level, v, day);
        step(level, v, day);
    }

    /** The cycle a day on: the shocks, the town's own numbers, the step, the phase, the council. */
    static void step(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag s = state(id);
        CycleModel m = model(s);
        m.inflation = EconIndex.inflationWeek(id);
        m.shock = shocks(level, id, day, s);
        // The town's own employment, where its labour market counts it and it has hands enough to count.
        int grown = 0;
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby()) grown++;
        if (grown >= 6 && LabourMarket.live(id)) m.observe(1 - EconIndex.last(id, "unemp", 0) / 1000.0, 0.15);
        // New loans the town's lenders made since yesterday: the firms' debt grows by them, against a month's output.
        long lent = FolkCredit.lentLately(id) / 100;
        long lentWas = s.contains("lent") ? s.getLong("lent") : lent;
        s.putLong("lent", lent);
        long out7 = 0;
        for (int i = 0; i < 7; i++) out7 += EconIndex.back(id, "output", i, 0);
        if (lent > lentWas) m.credit(Math.min(0.1, (lent - lentWas) / Math.max(50.0, out7 * 4.0)));
        double empWas = m.employment;
        m.step();
        CycleModel.Phase was = phase(id);
        CycleModel.Phase now = CycleModel.phase(m.employment, m.employment - empWas, was);
        s.putInt("phase", now.ordinal());
        save(s, m);
        if (was != now) turned(id, day, was, now, m);
        council(level, v, day, s, m, now);
        EconIndex.recordCycle(id, m.employment, now.ordinal(), policyBp(m));
    }

    /** The chronicle has the turn of the times. */
    static void turned(UUID id, long day, @Nullable CycleModel.Phase was, CycleModel.Phase now, CycleModel m) {
        CompoundTag s = state(id);
        Accounts.add(Accounts.sub(s, "turns"), now.name(), 1);
        ListTag log = s.getList("log", Tag.TAG_COMPOUND);
        CompoundTag e = new CompoundTag();
        e.putLong("day", day);
        e.putInt("phase", now.ordinal());
        log.add(e);
        while (log.size() > 24) log.remove(0);
        s.put("log", log);
        if (was == null) return;
        String town = Villages.name(id);
        String line = switch (now) {
            case BOOM -> "good times in " + town + ": trade is brisk, the firms are taking on hands and borrowing to grow, and there is work for all who want it";
            case SLOWING -> "trade in " + town + " is slowing: wages and prices have run up, the firms' profits are thinner and the lenders warier";
            case SLUMP -> "hard times in " + town + ": trade has fallen off, firms are laying hands off and some cannot pay their debts";
            case RECOVERING -> "trade in " + town + " is picking up again: the worst of the hard times is past, and the firms are hiring";
        };
        Villages.tell(id, day, line);
    }

    // ------------------------------------------------------------------ the shocks

    /** A shock to the town's productivity: so much (a year's growth, -0.04 a raid), for so many days, and why. */
    public static void shock(UUID village, String kind, String words, double size, int days, long day) {
        CompoundTag s = state(village);
        CompoundTag sh = Accounts.sub(s, "shocks");
        if (sh.contains(kind) && sh.getCompound(kind).getLong("until") >= day) return;   // already under it
        CompoundTag k = new CompoundTag();
        k.putDouble("size", size);
        k.putLong("until", day + days);
        k.putString("words", words);
        sh.put(kind, k);
        Accounts.dirty();
        Villages.tell(village, day, words);
    }

    /** A new trade route opened (EconRegion's first deal with a partner): a lift to the town's trade. */
    static void newRoute(UUID village, UUID partner, long day) {
        CompoundTag s = state(village);
        CompoundTag known = Accounts.sub(s, "routes");
        if (known.contains(partner.toString())) return;
        known.putLong(partner.toString(), day);
        shock(village, "route", "a new trade route: " + Villages.name(village) + "'s merchants are buying of " + Villages.name(partner), 0.02, 5, day);
    }

    /** The shocks in force today, summed; the day's real shocks looked for first. */
    static double shocks(ServerLevel level, UUID id, long day, CompoundTag s) {
        if (Droughts.on(id)) shock(id, "harvest", "the harvest is failing in the drought: food will be dear", -0.03, 4, day);
        if (Raids.raided(id)) shock(id, "raid", "the raid has cost the town its trade for a few days", -0.04, 3, day);
        if (!Wars.enemies(id).isEmpty()) shock(id, "war", "the war is costing the town's trade", -0.02, 2, day);
        // The mine's vein worked out, or the harvest failed: yesterday's ore or food against the town's usual.
        int ore = 0, food = 0;
        for (Map.Entry<String, int[]> e : Economy.yesterdayItems(id).entrySet()) {
            String k = e.getKey();
            if (k.contains("raw_") || k.endsWith("_ore") || k.equals("minecraft:coal") || k.equals("minecraft:diamond")) ore += e.getValue()[0];
            if (k.equals("minecraft:wheat") || k.equals("minecraft:potato") || k.equals("minecraft:carrot") || k.equals("minecraft:beetroot")) food += e.getValue()[0];
        }
        double oreAvg = s.getDouble("oreAvg"), foodAvg = s.getDouble("foodAvg");
        if (oreAvg >= 16 && ore < oreAvg * 0.3) shock(id, "vein", "the mine's vein has run out: the miners are bringing up little till they find another", -0.02, 5, day);
        if (foodAvg >= 64 && food < foodAvg * 0.35) shock(id, "harvest", "the harvest has failed: food will be dear for a while", -0.03, 4, day);
        s.putDouble("oreAvg", oreAvg <= 0 ? ore : oreAvg * 0.8 + ore * 0.2);
        s.putDouble("foodAvg", foodAvg <= 0 ? food : foodAvg * 0.8 + food * 0.2);
        double sum = 0;
        CompoundTag sh = Accounts.sub(s, "shocks");
        for (String k : sh.getAllKeys()) {
            CompoundTag x = sh.getCompound(k);
            if (x.getLong("until") >= day) sum += x.getDouble("size");
        }
        return Math.max(-0.08, Math.min(0.05, sum));
    }

    /** The shocks in force, in words. */
    public static String shocksLine(UUID id, long day) {
        CompoundTag sh = Accounts.sub(state(id), "shocks");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String k : sh.getAllKeys()) {
            CompoundTag x = sh.getCompound(k);
            if (x.getLong("until") >= day) parts.add(x.getString("words") + " (till day " + (x.getLong("until") + 1) + ")");
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ the council

    /** The council's rate over its neutral, as the lenders' margin a week, hundredths of a percent. */
    static int policyBp(CycleModel m) {
        return (int) Math.round((m.rate - CycleModel.NEUTRAL) * 5000);
    }

    /** The council's rate as a margin on the town's lending, a week, hundredths of a percent (0 before the cycle runs). */
    public static int policyBp(@Nullable UUID village) {
        if (village == null || !enabled() || !MarketMaker.live(village)) return 0;
        CompoundTag s = state(village);
        return s.contains("rate") ? policyBp(model(s)) : 0;
    }

    /** The council's rate, a week, in the hundred (the lenders' base). */
    public static double rateWeekPercent(UUID village) {
        CompoundTag s = state(village);
        double r = s.contains("rate") ? s.getDouble("rate") : CycleModel.NEUTRAL;
        return r * 100;
    }

    /** How much more nerve a folk wants to open a business in these times: -1 in a boom, +1 in a slump. */
    public static int nerveShift(@Nullable UUID village) {
        CycleModel.Phase p = phase(village);
        return p == CycleModel.Phase.BOOM ? -1 : p == CycleModel.Phase.SLUMP ? 1 : 0;
    }

    /** What the town's works may borrow, against its usual: more in a slump, less in a boom. */
    public static double spendFactor(@Nullable UUID village) {
        CycleModel.Phase p = phase(village);
        if (p == null) return 1.0;
        return switch (p) {
            case SLUMP -> 1.25;
            case RECOVERING -> 1.1;
            case BOOM -> 0.85;
            case SLOWING -> 1.0;
        };
    }

    /** Which way the times lean the council on its taxes: -1 ease (a slump, the treasury sound), +1 raise (a boom, prices running up). */
    public static int taxLean(@Nullable UUID village) {
        CycleModel.Phase p = phase(village);
        if (p == null) return 0;
        if (p == CycleModel.Phase.SLUMP) return -1;
        if (p == CycleModel.Phase.BOOM && EconIndex.inflationWeek(village) > 0.04) return 1;
        return 0;
    }

    /** The council's morning: its rate told when it moves, the minimum wage raised with the prices. */
    static void council(ServerLevel level, Villages.Village v, long day, CompoundTag s, CycleModel m, CycleModel.Phase now) {
        UUID id = v.id();
        int bp = (int) Math.round(m.rate * 10000);
        int told = s.contains("toldBp") ? s.getInt("toldBp") : bp;
        if (!s.contains("toldBp")) s.putInt("toldBp", bp);
        if (Math.abs(bp - told) >= 50) {
            s.putInt("toldBp", bp);
            Villages.tell(id, day, String.format(Locale.ROOT, "the council %s the town's rate to %.1f in the hundred a year (%s): the lenders lend at %.1f in the hundred a week",
                bp > told ? "raised" : "cut", bp / 100.0, bp > told ? (now == CycleModel.Phase.BOOM ? "to cool the boom" : "the prices running up")
                    : "to ease the hard times", FolkCredit.rateBp(id) / 100.0));
            Accounts.add(s, bp > told ? "raised" : "cut", 1);
        }
        // The minimum wage, weekly: raised a coin when prices are up an eighth since it was set, the hands scarce.
        if (day % 7 == 0 && LabourMarket.live(id) && (now == CycleModel.Phase.BOOM || now == CycleModel.Phase.SLOWING)) {
            long cpi = EconIndex.cpi(id), at = s.contains("cpiAtWage") ? s.getLong("cpiAtWage") : cpi;
            if (!s.contains("cpiAtWage")) s.putLong("cpiAtWage", cpi);
            if (cpi >= at * 9 / 8) {
                int to = Math.max(LabourMarket.minimumWage(id), LabourMarket.councilMinimum(id)) + 1;
                LabourMarket.setMinimum(id, to, "the council, the prices up " + Math.round((cpi / (double) at - 1) * 100) + " in the hundred");
                s.putLong("cpiAtWage", cpi);
                Accounts.add(s, "wageRaised", 1);
            }
        }
        Accounts.dirty();
    }

    // ------------------------------------------------------------------ the treasury's reserve

    /** The days of the wage bill the treasury keeps in hand. */
    static final int RESERVE_DAYS = 2;

    /**
     * The treasury's reserve: short of two days' wages in the morning, the market's surplus over its float paid in there
     * and then; three mornings short, the council called on its taxes. Returns the pennies brought in.
     */
    static long fiscal(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag s = state(id);
        long reserve = Math.max(1000, Market.wageBill(id) * 100L * RESERVE_DAYS);
        long have = Businesses.treasuryPence(id);
        if (have >= reserve) {
            s.putInt("shortRun", 0);
            return 0;
        }
        long over = MarketMaker.cash(id) - MarketMaker.keep(id);
        long in = 0;
        if (over >= 100) {
            long p = Math.min(over, reserve - have) / 100 * 100;
            if (p > 0) {
                in = MarketMaker.cashOut(id, p);
                Businesses.toTreasury(id, in);
                TownBudget.in(id, "market", in);
                Accounts.add(Accounts.sub(MarketMaker.market(id), "all"), "surplus", in);
                Accounts.add(s, "early", in);
                if (in >= 500) Villages.tell(id, day, "the market paid the treasury " + Businesses.pence(in) + " of its surplus early, to keep the wages paid");
            }
        }
        if (Businesses.treasuryPence(id) >= reserve) {
            s.putInt("shortRun", 0);
        } else {
            s.putInt("shortRun", s.getInt("shortRun") + 1);
            Accounts.add(s, "shortDays", 1);
            if (s.getInt("shortRun") >= 3 && day - s.getLong("called") >= 4 && CivicLand.live()) {
                s.putLong("called", day);
                String said = TaxBook.sit(level, v, day, "the council, called together on the empty treasury,");
                Accounts.add(s, "calls", 1);
                Villages.tell(id, day, "the treasury has been short of its wages three mornings running: " + said);
            }
        }
        Accounts.dirty();
        return in;
    }

    // ------------------------------------------------------------------ talk

    /** What a folk says when asked of prices, trade or the times (FolkTalk); null if not asked of them. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || text == null) return null;
        String low = text.toLowerCase(Locale.ROOT);
        boolean asked = low.contains("prices") || low.contains("hard times") || low.contains("the times") || low.contains("economy")
            || low.contains("how's trade") || low.contains("hows trade") || low.contains("how is trade") || low.contains("inflation") || low.contains("boom");
        if (!asked) return null;
        CycleModel.Phase ph = phase(id);
        if (ph == null) return "Prices? We've no market of our own yet to speak of: we share what we have.";
        double infl = EconIndex.inflationWeek(id);
        String prices = infl > 0.03 ? "Everything's dearer than it was a week back, " + Math.round(infl * 100) + " in the hundred, and the wages chase it."
            : infl < -0.03 ? "Things are cheaper than a week ago; good for the purse, hard on the makers."
            : "Prices are steady enough.";
        boolean out = f.stationTask() == AssistantEntity.StationTask.NONE;
        String times = switch (ph) {
            case BOOM -> "Good times! Work for anyone who wants it, and the firms are borrowing to grow.";
            case SLOWING -> "Trade's slowing. The firms are warier, and the lenders more so.";
            case SLUMP -> out ? "Hard times. I've no work, and few are hiring." : "Hard times. I'm glad of my place; others have been laid off.";
            case RECOVERING -> "It's picking up again. The worst is behind us, I think.";
        };
        String rate = String.format(Locale.ROOT, " The council has the rate at %.1f in the hundred.", rateWeekPercent(id));
        return times + " " + prices + rate;
    }

    /** One line on the town's times, for its status and books. */
    public static String line(UUID id) {
        CycleModel.Phase ph = phase(id);
        if (ph == null) return "";
        return String.format(Locale.ROOT, "The times: %s; prices %s%.1f%% this week (index %.1f); the council's rate %.1f%%", ph.words,
            EconIndex.inflationWeek(id) >= 0 ? "+" : "", EconIndex.inflationWeek(id) * 100, EconIndex.cpi(id) / 100.0, rateWeekPercent(id));
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the cycle's state, as {employment, wage share, debt, rate, profit, invest}. */
    public static double[] stateForTests(UUID village) {
        CycleModel m = model(state(village));
        return new double[]{ m.employment, m.wageShare, m.debt, m.rate, m.profit, m.invest };
    }

    /** Tests: the cycle a day on now, whatever the day (the harness's own days). */
    public static void stepForTests(ServerLevel level, Villages.Village v, long day) {
        step(level, v, day);
    }

    /** Tests: the treasury's reserve seen to now. */
    public static long fiscalForTests(ServerLevel level, Villages.Village v, long day) {
        return fiscal(level, v, day);
    }

    /** Tests: the council's counts {raised, cut, wageRaised, calls, shortDays}. */
    public static long[] councilForTests(UUID village) {
        CompoundTag s = state(village);
        return new long[]{ s.getLong("raised"), s.getLong("cut"), s.getLong("wageRaised"), s.getLong("calls"), s.getLong("shortDays") };
    }

    /** Tests: the treasury's coin, for the harness. */
    public static int treasuryForTests(UUID village) {
        return Ledger.coins(village);
    }
}
