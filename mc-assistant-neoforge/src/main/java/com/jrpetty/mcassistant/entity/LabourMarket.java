package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.BusinessTypes.BusinessType;
import com.jrpetty.mcassistant.entity.LabourBook.Application;
import com.jrpetty.mcassistant.entity.LabourBook.Contract;
import com.jrpetty.mcassistant.entity.LabourBook.DayRecord;
import com.jrpetty.mcassistant.entity.LabourBook.Offer;
import com.jrpetty.mcassistant.entity.LabourBook.State;
import com.jrpetty.mcassistant.entity.LabourBook.Town;
import com.jrpetty.mcassistant.entity.LabourBook.Vacancy;
import com.jrpetty.mcassistant.entity.LabourBook.Verdict;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [labour] The labour market (economy batch 2): hands and employers meeting at a wage.
 *
 * <p><b>Employers.</b> Every firm its folk (or a player) opened; the market (the storehouse's keepers and couriers); the
 * watch; and the town itself for every trade it still runs (the fields, the mines, the smithy, the shop...). A producer
 * who owns its homestead, and a founder working its own firm, is its own master: self-employed, no terms with anybody.
 * The posts the town fills by its own appointing (the bank, the infirmary, the caves, the ferry...) are filled so, as
 * before, and paid as their trade's going rate.
 *
 * <p><b>Openings.</b> Each morning every employer posts what it is short of: a firm the hands it wanted and found
 * nobody for (or lost to a better offer); the town, the market and the watch the hands each trade is under its share of
 * the town's shape (Villages.share), and the first hand of a trade it has grown into. Each opening carries the trade, the
 * employer's wage offer (in coin for a journeyman, and as a multiple of what the job is worth here: JobWorth), the level
 * it wants and whether it takes an apprentice, and the hours.
 *
 * <p><b>Wages set by the market.</b> An employer with an opening nobody applied for raises its offer (a sixteenth a day,
 * an eighth for the town's essential work); one with more applicants than openings lowers it (three in the hundred); an
 * employer with no opening lets its offer ease back to the going rate, and the offer for a trade with hands over its
 * share falls. Never under the living wage and the council's minimum wage, never over what the employer can afford: a
 * firm by its margin (a week's takings less its makings, rent and costs, a hand), the town by its budget's pressure (and
 * for its essential work, out of the budget, up to twice the worth). A hand's pay is its own terms (the offer it was taken
 * on at, raised at the weekly review to the employer's going offer; cut only by a firm losing money, a tenth a week);
 * the town's <b>wage index</b> is the average of the terms actually paid at each trade, smoothed day to day, and it is
 * the going rate the rest of the economy reads (JobWorth's scale: the posted rates, the rents, the notices to other
 * towns) where it used to read the trade's scarcity. JobWorth's relativities (the value, the difficulty, a hand's skill)
 * are the starting point and the fallback.
 *
 * <p><b>Workers choose.</b> Each morning every grown folk with terms it could leave, and every folk out of work, weighs
 * the openings it could take: the wage it would be paid there at its own level (an apprentice's three quarters while it
 * is new to the trade), the walk from its home, the employer's name (a firm's reputation), its own liking for the trade
 * (what it knows, its nature, its quirks; never a trade it fears). One in work applies only for more than it has by a
 * tenth and its loyalty (three in the hundred a week of service, at most twelve). The employer picks by skill and
 * experience; with two or more applicants, and the town holding interviews, it holds one at the hall (Interviews, the
 * post "job:N") and the panel picks. One in work gives a day's notice and moves the next morning; its employer may keep
 * it with a counter-offer if it can afford to and the hand is skilled or the last at essential work.
 *
 * <p><b>Lay-offs and unemployment.</b> A firm struggling for want of takings lays off its newest hand (one every three
 * days); a town trade two hands over its share two mornings running lets its newest go (one a day for the town, never its
 * last hand, never the fields in a famine nor the watch in a raid); a hand whose workplace is gone, or whose firm closed,
 * is laid off. The chronicle has it, the folk remembers it and feels it. One out of work draws poor relief from the
 * treasury each morning (half the living wage, while its purse and savings are under four days' living), looks for work,
 * is taken on at once by any opening it fits, and after half a day with none the town places it where it is shortest of
 * hands (the old way).
 *
 * <p><b>Skill and training.</b> A hand new to a trade (under level three) is taken on as an apprentice at three quarters
 * of the offer; working beside a skilled hand of its employer (level ten) it is taught, and at level five it is a
 * journeyman on the full offer. Experience raises a hand's worth (JobWorth's skill curve) and so what it asks.
 *
 * <p><b>Essentials never starve.</b> An essential opening (the fields or the water in a famine or with nobody at them, the
 * watch in a raid or with nobody on it) pays more out of the budget, and if nobody has taken it by the next morning the
 * elder calls a hand to it: one out of work first, else one from a trade over its share. The elder's orders (Orders)
 * still move hands as the emergency lever.
 *
 * <p>Live only where the market is (MarketMaker.live): a plain town keeps the elder's share-out. Coin only moves between
 * accounts (wages from tills and the treasury, relief from the treasury), so the coin census stays at nought drift.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class LabourMarket {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private LabourMarket() {}

    /** The offer's steps: up for an opening nobody applied for (an essential one more), down for a queue, the drift back. */
    static final double STEP_UP = 1.0625, STEP_UP_ESSENTIAL = 1.125, STEP_DOWN = 0.97, OVER_DOWN = 0.98, DRIFT = 0.25;
    /** An offer's bounds, as a multiple of the job's worth: the least, a town's most, essential work's, a new firm's. */
    static final double M_LEAST = 0.70, M_MOST = 1.60, M_ESSENTIAL = 2.0, M_NEW_FIRM = 1.30;
    /** The most any wage index or offer goes to (where the living wage is far over what a job is worth, offers start high). */
    static final double M_TOP = 4.0;
    /** An apprentice's share of the offer; below this level a hand is new to the trade; at this one a journeyman. */
    static final double APPRENTICE_SHARE = 0.75;
    static final int APPRENTICE_BELOW = 3, JOURNEYMAN = 5, MASTER_HAND = 10;
    /** What it takes to leave: a tenth more, and the loyalty of service (a week's worth, at most). */
    static final double SWITCH = 0.10, LOYALTY_A_WEEK = 0.03, LOYALTY_MOST = 0.12, LIKE = 0.08;
    /** A morning's teaching for an apprentice beside a skilled hand. */
    static final int TRAIN_XP = 120;
    /** Half a day out of work with no opening it fits, and the town places it (ticks). */
    static final long SEARCH = 12000L;
    /** Poor relief: a share of the living wage a morning, while the purse and savings are under so many days' living. */
    static final double RELIEF_SHARE = 0.5;
    static final int RELIEF_MEANS_DAYS = 4;
    /** Openings an employer posts for a trade at once (essential work one more). */
    static final int MOST_OPENINGS = 2;
    /** Contracts of folk not seen for so many mornings are let go (gone from the town). */
    static final long GONE_DAYS = 5;

    /** The posts the town fills by its own appointing: no openings posted for them, and nobody leaves them for a wage. */
    static final Set<StationTask> APPOINTED = EnumSet.of(StationTask.BANK, StationTask.CAVE, StationTask.FIREWORKS, StationTask.CARTOGRAPHER,
        StationTask.DIVER, StationTask.NETHER, StationTask.REDSTONE, StationTask.HEAL, StationTask.FERRY, StationTask.EMERALD, StationTask.GOLEMS,
        StationTask.FLETCHER, StationTask.SCOUT);

    private static volatile boolean enabled = true;
    /** Tests: decide every application on paper, whatever the town's interviews. */
    private static volatile boolean onPaper;
    /** A short-lived memory of how each folk feels about its work: {day, kind} (kind: 1 new job, 2 laid off, 3 a rise, 4 apprenticeship done). */
    private static final Map<UUID, long[]> FELT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LabourBook.of().clear();
        FELT.clear();
        enabled = true;
        onPaper = false;
    }

    /** Tests: the labour market off (the elder's share-out, as before) or on. */
    public static void enabledForTests(boolean on) {
        enabled = on;
    }

    /** Tests: every application decided on paper (no interview). */
    public static void onPaperForTests(boolean on) {
        onPaper = on;
    }

    /** Is the labour market at work in this town (its market open)? */
    public static boolean live(@Nullable UUID village) {
        return enabled && village != null && MarketMaker.live(village);
    }

    // ------------------------------------------------------------------ employers

    /**
     * Who a folk works for: "firm:ent-3", "market", "watch", "town:FARM"; "owner:ent-3" for a founder at its own firm and
     * "own:farm:..." for a producer on its own homestead (self-employed); "" for none (no trade, or a child).
     */
    static String employerOf(UUID village, VillageFolkEntity f) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE) return "";
        Business firm = Enterprise.employerOf(village, f);
        if (firm != null) return (firm.ownedBy(f.getUUID()) ? "owner:" : "firm:") + firm.id;
        Business own = Privatisation.firmOf(village, f);
        if (own != null && own.open && !own.town() && own.ownedBy(f.getUUID())) return "own:" + own.id;
        return employerFor(f.stationTask());
    }

    /** The town's own employer for a trade: the market's, the watch's, or the town's. */
    static String employerFor(StationTask t) {
        if (t == StationTask.STORE || t == StationTask.HAUL) return "market";
        if (t == StationTask.GUARD) return "watch";
        return "town:" + t.name();
    }

    /** Does this employer take hands on terms (not one's own master)? */
    static boolean onTerms(String employer) {
        return employer.startsWith("firm:") || employer.equals("market") || employer.equals("watch") || employer.startsWith("town:");
    }

    static boolean selfEmployed(String employer) {
        return employer.startsWith("own:") || employer.startsWith("owner:");
    }

    @Nullable
    static Business firmOf(UUID village, String employer) {
        if (!employer.startsWith("firm:") && !employer.startsWith("owner:")) return null;
        return Businesses.of(village, employer.substring(employer.indexOf(':') + 1));
    }

    /** "Bram's Tools", "the market", "the watch", "the mines". */
    static String nameOf(UUID village, String employer) {
        if (employer.isEmpty()) return "nobody";
        Business b = firmOf(village, employer);
        if (b != null) return b.name;
        if (employer.startsWith("own:")) return "its own business";
        if (employer.equals("market")) return "the market";
        if (employer.equals("watch")) return "the watch";
        if (employer.startsWith("town:")) {
            StationTask t = JobMarket.named(employer.substring(5));
            return t == null ? "the town" : JobWorth.at(t);
        }
        return employer;
    }

    // ------------------------------------------------------------------ wages

    /** Today's living wage here, coins. */
    static int livingWage(UUID village) {
        return JobWorth.scale(null, village).floor;
    }

    /** The least a hand is paid here: the living wage, or the council's minimum wage if higher (0 where the market is shut). */
    public static int minimumWage(@Nullable UUID village) {
        if (!live(village)) return 0;
        LabourBook.Town tw = LabourBook.peek(village);
        return Math.max(livingWage(village), tw == null ? 0 : tw.minimumWage);
    }

    /** The council's own minimum wage (0 for none). */
    public static int councilMinimum(UUID village) {
        LabourBook.Town tw = LabourBook.peek(village);
        return tw == null ? 0 : tw.minimumWage;
    }

    /** What a hand of this level (or this folk, at its own) would be paid at this trade here at this offer, coins a day. */
    static int wageFor(UUID village, StationTask t, double m, @Nullable VillageFolkEntity f, int level, boolean apprentice) {
        JobWorth.Scale s = JobWorth.scale(null, village);
        JobWorth.Post p = JobWorth.wantedPost(t, village);
        JobWorth.Worth w = s.worth(p.key);
        double base = s.payLevel * JobWorth.UNIT * (w == null ? p.difficulty() : w.value() * w.difficulty());
        double k = f == null ? JobWorth.skillCurve(level) : JobWorth.skill(f, p, level);
        double raw = base * m * k * (apprentice ? APPRENTICE_SHARE : 1.0);
        return Math.max(minimumWage(village), JobWorth.bound(s, raw));
    }

    /** A journeyman's day at this trade here at this offer. */
    static int journeyman(UUID village, StationTask t, double m) {
        return wageFor(village, t, m, null, JOURNEYMAN, false);
    }

    /**
     * [labour] Its job's worth factor as its own terms have it (JobWorth.payOf): the town's going rate at its trade (the
     * index the scale carries) replaced by the multiple it was taken on at, an apprentice's three quarters.
     */
    static double factor(VillageFolkEntity f, @Nullable JobWorth.Worth w, double factor) {
        if (w == null || !live(f.ownerId())) return factor;
        Contract c = contract(f);
        if (c == null || !c.trade.equals(f.stationTask().name())) return factor;
        double m = c.m * (c.apprentice ? APPRENTICE_SHARE : 1.0);
        return factor / Math.max(0.05, w.scarcity()) * m;
    }

    @Nullable
    static Contract contract(VillageFolkEntity f) {
        if (f.ownerId() == null) return null;
        LabourBook.Town tw = LabourBook.peek(f.ownerId());
        return tw == null ? null : tw.contracts.get(f.getUUID());
    }

    /**
     * [labour] The town's wage index for a trade (JobWorth's scale, in place of its scarcity): the average multiple the
     * town's hands at it are paid on, smoothed; the town's own offer for a trade nobody is on terms at; one before any.
     */
    static double index(UUID village, StationTask t) {
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw == null || t == StationTask.NONE) return 1.0;
        if (!tw.days.isEmpty()) {
            Double x = tw.days.get(tw.days.size() - 1).index.get(t.name());
            if (x != null) return clamp(x, M_LEAST, M_TOP);
        }
        Offer o = tw.offers.get(employerFor(t) + "|" + t.name());
        return o == null ? 1.0 : clamp(o.m, M_LEAST, M_TOP);
    }

    /** The most this employer can offer for this trade, as a multiple of the job's worth. */
    static double cap(ServerLevel level, UUID village, String employer, StationTask t, boolean essential) {
        Business b = firmOf(village, employer);
        if (b != null) {
            long day = Businesses.today(level);
            if (day - b.opened < 7) return M_NEW_FIRM * Math.max(1.0, atFloor(village, t));
            Business.Day w = b.last(day, 7);
            double margin = (w.revenue - w.cogs - w.other - w.rent - w.taxes - w.interest) / 7.0 / 100.0;
            int hands = Math.max(1, Enterprise.hands(village, b).size());
            double base = journeyman(village, t, 1.0);
            return clamp(margin / hands / Math.max(1.0, base), M_LEAST, M_MOST);
        }
        // The town's bounds are over what it pays at the least: where the living wage is above what a job is worth, an
        // offer is reckoned from the living wage (it pays that whatever the job), and goes up from there.
        double floor = Math.max(1.0, atFloor(village, t));
        if (essential) return M_ESSENTIAL * floor;
        int pressure = TownBudget.pressure(village);
        return (pressure >= 2 ? 1.15 : pressure == 1 ? 1.35 : M_MOST) * floor;
    }

    /**
     * The offer at which a journeyman at this trade is paid just the least wage here (the living wage, or the council's
     * minimum): an employer raising its offer raises it from what it actually pays, not from a figure under the floor.
     */
    static double atFloor(UUID village, StationTask t) {
        JobWorth.Scale s = JobWorth.scale(null, village);
        JobWorth.Post p = JobWorth.wantedPost(t, village);
        JobWorth.Worth w = s.worth(p.key);
        double base = s.payLevel * JobWorth.UNIT * (w == null ? p.difficulty() : w.value() * w.difficulty()) * JobWorth.skillCurve(JOURNEYMAN);
        int least = Math.max(1, minimumWage(village));
        return base <= 0 ? 1.0 : least / base;
    }

    static Offer offer(LabourBook.Town tw, UUID village, String employer, StationTask t) {
        String key = employer + "|" + t.name();
        Offer o = tw.offers.get(key);
        if (o == null) {
            o = new Offer();
            o.key = key;
            o.m = startingOffer(village, t);
            tw.offers.put(key, o);
        }
        return o;
    }

    /** Where an offer starts: the going rate (the town's index), or before any, JobWorth's scarcity for the trade. */
    static double startingOffer(UUID village, StationTask t) {
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw != null && !tw.days.isEmpty()) return index(village, t);
        return clamp(JobWorth.state(village).scar(t), M_LEAST, M_MOST);
    }

    // ------------------------------------------------------------------ the morning

    /** Once a day, each town where the market is open (Market.tick, after the day's pay scale). */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!live(id)) return;
        LabourBook.Town tw = LabourBook.town(id);
        if (tw.tallyDay == day) return;
        tw.tallyDay = day;
        tw.hiredToday = tw.quitToday = tw.laidOffToday = tw.appliedToday = tw.interviewsToday = 0;
        tw.reliefToday = 0;
        try {
            List<VillageFolkEntity> folk = grown(id);
            boolean full = Villages.loadedCount(id) * 5 >= Villages.headcount(id) * 4;
            tidy(level, v, tw, folk, day, full);
            if (full) {
                review(level, v, tw, folk, day);
                layoffs(level, v, tw, folk, day);
                post(level, v, tw, folk, day);
                applyAll(level, v, tw, grown(id), day);
                decide(level, v, tw, day);
                essentials(level, v, tw, day);
            }
            train(level, v, tw, grown(id), day);
            relief(level, v, tw, grown(id), day);
            council(level, v, tw, day);
            record(level, v, tw, grown(id), day);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-LABOUR] {}: the morning's labour market failed: {}", Villages.name(id), e.toString());
        }
        LabourBook.changed();
    }

    /** Tests and the operator: the morning's labour market now, as on this day. */
    public static void morningNow(ServerLevel level, Villages.Village v) {
        LabourBook.Town tw = LabourBook.town(v.id());
        tw.tallyDay = -1;
        morning(level, v, level.getDayTime() / 24000L);
    }

    static List<VillageFolkEntity> grown(UUID id) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && !f.isShowcase() && id.equals(f.ownerId())) out.add(f);
        }
        out.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        return out;
    }

    /** Can this folk be had for work at all just now (not hired by a player, not away, not laid up, not a visitor)? */
    static boolean about(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isShowcase() && !f.isHired() && f.trip() == null && f.expedition() == null
            && !JobSeekers.travelling(f) && !Visitors.is(f) && !Interviews.busy(f);
    }

    /** Is it out of work: grown, of the town, about, at no trade? */
    static boolean outOfWork(VillageFolkEntity f) {
        return about(f) && f.stationTask() == StationTask.NONE && !Health.laidUp(f);
    }

    // ------------------------------------------------------------------ tidy: terms for every hand, notices served

    static void tidy(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day, boolean full) {
        UUID id = v.id();
        Set<UUID> here = new HashSet<>();
        for (VillageFolkEntity f : folk) {
            here.add(f.getUUID());
            String emp = employerOf(id, f);
            Contract c = tw.contracts.get(f.getUUID());
            if (c != null) c.seen = day;
            if (emp.isEmpty()) {
                if (c != null && c.leaving < 0) tw.contracts.remove(f.getUUID());
                if (c != null && c.leaving >= 0) { serve(level, v, tw, f, c, day); continue; }
                if (!tw.outSince.containsKey(f.getUUID()) && about(f)) {
                    tw.outSince.put(f.getUUID(), level.getGameTime());
                    tw.outWhy.putIfAbsent(f.getUUID(), "between trades");
                }
                continue;
            }
            tw.outSince.remove(f.getUUID());
            tw.outWhy.remove(f.getUUID());
            if (selfEmployed(emp)) {
                if (c != null && c.leaving < 0) tw.contracts.remove(f.getUUID());
                if (c != null && c.leaving >= 0) serve(level, v, tw, f, c, day);
                continue;
            }
            StationTask t = f.stationTask();
            if (c == null) {
                c = terms(tw, id, f, emp, t, offer(tw, id, emp, t).m, day);
                continue;
            }
            if (!c.employer.equals(emp) || !c.trade.equals(t.name())) {
                // Its work changed under it by another hand (the elder's order, an appointment, its firm closing).
                Business was = firmOf(id, c.employer);
                if (was != null && !was.open && emp.startsWith("town:") && full && Villages.overStaffed(id, t)) {
                    layOff(level, v, tw, f, c.employer, was.name + " closed", day);
                    continue;
                }
                if (was != null && !was.open) note(tw, day, f.displayNameCap() + " lost its place at " + was.name + " when it closed; "
                    + nameOf(id, emp) + " kept it on");
                c.employer = emp;
                c.trade = t.name();
                c.m = offer(tw, id, emp, t).m;
                c.since = day;
                c.reviewed = day;
                c.apprentice = f.tradeLevel(t) < APPRENTICE_BELOW;
            }
            if (c.leaving >= 0 && c.leaving <= day) serve(level, v, tw, f, c, day);
            else if (full && emp.startsWith("town:") && !Villages.craftReady(id, t)) {
                // Its workplace gone (the shop pulled down, the storehouse burnt): nothing to work at.
                layOff(level, v, tw, f, emp, "there is no " + JobWorth.at(t).replace("the ", "") + " to work at any more", day);
            }
        }
        // The terms of folk long gone from the town.
        tw.contracts.values().removeIf(c -> !here.contains(c.folk) && day - c.seen > GONE_DAYS);
        tw.outSince.keySet().removeIf(u -> !here.contains(u));
        // Openings of firms that closed, and interviews that came to nothing.
        for (Vacancy vac : tw.vacancies) {
            if (!vac.open()) continue;
            Business b = firmOf(id, vac.employer);
            if (vac.employer.startsWith("firm:") && (b == null || !b.open)) close(tw, vac, day, State.WITHDRAWN, "the firm closed");
            if (vac.state == State.INTERVIEW) {
                InterviewBook.Interview iv = Interviews.find(id, vac.interview);
                if (iv == null || !iv.stage.open()) {
                    vac.state = State.OPEN;
                    vac.interview = -1;
                    for (Application a : vac.apps) if (a.verdict == Verdict.SHORTLISTED) a.verdict = Verdict.WAITING;
                }
            }
        }
    }

    /** New terms for a hand: at this employer and trade, at this offer, an apprentice while new to the trade. */
    static Contract terms(LabourBook.Town tw, UUID id, VillageFolkEntity f, String emp, StationTask t, double m, long day) {
        Contract c = new Contract();
        c.folk = f.getUUID();
        c.name = f.displayNameCap();
        c.employer = emp;
        c.trade = t.name();
        c.m = m;
        c.since = day;
        c.reviewed = day;
        c.seen = day;
        c.apprentice = f.tradeLevel(t) < APPRENTICE_BELOW;
        tw.contracts.put(f.getUUID(), c);
        return c;
    }

    /** A notice served: it leaves for where it is going, this morning. */
    static void serve(ServerLevel level, Villages.Village v, LabourBook.Town tw, VillageFolkEntity f, Contract c, long day) {
        String[] g = c.goingTo.split("\\|", -1);
        c.leaving = -1;
        c.goingTo = "";
        if (g.length < 5) return;
        StationTask t = JobMarket.named(g[1]);
        if (t == null) return;
        double m;
        try {
            m = Double.parseDouble(g[2]);
        } catch (NumberFormatException e) {
            m = 1.0;
        }
        if (!move(level, v, tw, f, g[0], t, m, Boolean.parseBoolean(g[3]), day, "")) {
            note(tw, day, f.displayNameCap() + " could not take up its place at " + nameOf(v.id(), g[0]) + ": no ground for the work; it stays");
        }
    }

    // ------------------------------------------------------------------ moving a hand

    /**
     * A hand taken on: out of its old place (a firm's books), into the new (a firm's hands, or the town's trade on new
     * ground), on its new terms. Returns whether it moved.
     */
    static boolean move(ServerLevel level, Villages.Village v, LabourBook.Town tw, VillageFolkEntity f, String emp, StationTask t, double m,
                        boolean apprentice, long day, String how) {
        UUID id = v.id();
        String oldEmp = employerOf(id, f);
        String oldName = nameOf(id, oldEmp);
        int oldWage = oldEmp.isEmpty() ? 0 : JobWorth.payOf(f).worthPart();
        Business newFirm = firmOf(id, emp);
        if (newFirm != null) {
            CompoundTag r = Enterprise.rec(id, newFirm.id);
            BusinessType bt = Enterprise.kindOf(id, newFirm);
            if (r == null || bt == null || !newFirm.open) return false;
            pending.put(f.getUUID(), new Object[]{ m, apprentice, how });
            try {
                if (Enterprise.hireThis(level, v, newFirm, r, bt, f) == null) return false;
            } finally {
                pending.remove(f.getUUID());
            }
        } else {
            Business oldFirm = Enterprise.employerOf(id, f);
            if (f.stationTask() != t && !f.takeUpTrade(t)) return false;
            if (oldFirm != null) leftFirm(id, tw, oldFirm, f);
            Contract c = terms(tw, id, f, emp, t, m, day);
            c.apprentice = apprentice;
        }
        tw.outSince.remove(f.getUUID());
        tw.outWhy.remove(f.getUUID());
        tw.hiredToday++;
        Contract c = tw.contracts.get(f.getUUID());
        int wage = c == null ? 0 : wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice);
        String newName = nameOf(id, emp);
        String line = oldEmp.isEmpty()
            ? f.displayNameCap() + " was taken on at " + newName + " as " + aNoun(t) + (apprentice ? " (an apprentice)" : "") + ", " + wage + " a day"
            : f.displayNameCap() + " left " + oldName + " for " + newName + ": " + wage + " a day against " + oldWage;
        if (!how.isEmpty()) line += " (" + how + ")";
        note(tw, day, line);
        if (newFirm == null || !oldEmp.isEmpty()) Villages.tell(id, day, line);   // (a firm's hiring tells it itself)
        f.persona().remember(day, oldEmp.isEmpty() ? "I was taken on at " + newName : "I left " + oldName + " for " + newName + ": better pay", 4);
        felt(f, day, 1);
        FolkTalk.speak(f, oldEmp.isEmpty()
            ? FolkTalk.pick(f.getRandom(), "Work at last! " + capital(newName) + " took me on.", "Taken on at " + newName + ". " + wage + " a day — that'll do nicely.")
            : FolkTalk.pick(f.getRandom(), "My first day at " + newName + ". Better pay, too.", "On to " + newName + "! " + wage + " a day."));
        f.refreshMood();
        return true;
    }

    /** Terms waiting for a hand being taken on by a firm (Enterprise.hireThis calls back with them): {m, apprentice, how}. */
    private static final Map<UUID, Object[]> pending = new ConcurrentHashMap<>();

    /** A hand left a firm's books: off its staff, and the firm wants another in its place. */
    static void leftFirm(UUID id, LabourBook.Town tw, Business firm, VillageFolkEntity f) {
        CompoundTag r = Enterprise.rec(id, firm.id);
        if (r != null) Enterprise.removeStaff(id, r, f.getUUID());
        if (firm.open && !firm.ownedBy(f.getUUID())) tw.firmWants.merge(firm.id, 1, Integer::sum);
    }

    /**
     * [labour] Enterprise.hireThis, before the hand goes on the firm's books: off any other firm's first (a hand poached
     * from another firm leaves its books, and that firm wants another).
     */
    static void beforeTakenOn(UUID village, Business b, VillageFolkEntity f) {
        if (!live(village)) return;
        Business was = Enterprise.employerOf(village, f);
        if (was != null && was != b) leftFirm(village, LabourBook.town(village), was, f);
    }

    /**
     * [labour] Enterprise.hireThis, a hand taken on at a firm: its terms (the firm's offer; to take a hand from another
     * employer it outbids what that one pays, by a tenth and a half, as far as it can afford), and the opening it filled.
     */
    static void takenOn(ServerLevel level, Villages.Village v, Business b, VillageFolkEntity f) {
        UUID id = v.id();
        if (!live(id) || b.ownedBy(f.getUUID())) return;
        LabourBook.Town tw = LabourBook.town(id);
        StationTask t = f.stationTask();
        long day = Businesses.today(level);
        String emp = "firm:" + b.id;
        Object[] p = pending.get(f.getUUID());
        double m;
        boolean apprentice;
        if (p != null) {
            m = (Double) p[0];
            apprentice = (Boolean) p[1];
        } else {
            Offer o = offer(tw, id, emp, t);
            m = o.m;
            apprentice = f.tradeLevel(t) < APPRENTICE_BELOW;
            Contract had = tw.contracts.get(f.getUUID());
            if (had != null && onTerms(had.employer) && !had.employer.equals(emp)) {
                // Outbid: what its old employer pays it, and a tenth and a half on top, as far as the firm can afford.
                double now = had.m * (had.apprentice ? APPRENTICE_SHARE : 1.0);
                double bid = Math.min(cap(level, id, emp, t, false), Math.max(m, now * 1.15));
                if (bid > m) {
                    note(tw, day, b.name + " outbid " + nameOf(id, had.employer) + " for " + f.displayNameCap() + ": "
                        + wageFor(id, t, bid, f, f.tradeLevel(t), false) + " a day against " + JobWorth.payOf(f).worthPart());
                    m = bid;
                    apprentice = false;
                }
            }
        }
        Contract c = terms(tw, id, f, emp, t, m, day);
        c.apprentice = apprentice;
        int left = tw.firmWants.getOrDefault(b.id, 0) - 1;
        if (left > 0) tw.firmWants.put(b.id, left);
        else tw.firmWants.remove(b.id);
        LabourBook.changed();
    }

    /** [labour] Enterprise.hire found nobody: the firm posts the opening, and the market fills it when it can. */
    static void firmShort(UUID village, Business b) {
        if (!live(village)) return;
        LabourBook.Town tw = LabourBook.town(village);
        tw.firmWants.merge(b.id, 1, (a, c) -> Math.min(3, a + c));
        LabourBook.changed();
    }

    /**
     * [labour] A founder without the skill (Enterprise.skilledHand found none free): the most skilled hand at the trade the
     * whole town has, at work for another firm or the town (not an owner, not the elder, not the last hand at essential
     * work, not an appointed post), whom the new firm will outbid its employer for. Null if none.
     */
    @Nullable
    static VillageFolkEntity outbid(UUID village, StationTask work, VillageFolkEntity founder) {
        if (!live(village)) return null;
        VillageFolkEntity best = null;
        for (VillageFolkEntity f : grown(village)) {
            if (f == founder || !about(f) || f.isElder() || APPOINTED.contains(f.stationTask())) continue;
            String emp = employerOf(village, f);
            if (!onTerms(emp) || lastAtEssential(village, f)) continue;
            if (!Enterprise.skilled(village, f, work)) continue;
            if (best == null || f.tradeLevel(work) > best.tradeLevel(work)) best = f;
        }
        return best;
    }

    // ------------------------------------------------------------------ pay reviews

    /**
     * The weekly review: a hand's terms raised to its employer's going offer if that is more (a rise, told), a firm losing
     * money for a week cutting its hands' pay a tenth toward its offer. Apprentices finished are on the full offer.
     */
    static void review(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        for (VillageFolkEntity f : folk) {
            Contract c = tw.contracts.get(f.getUUID());
            if (c == null || c.leaving >= 0 || day - c.reviewed < 7) continue;
            StationTask t = c.task();
            if (t == null) continue;
            c.reviewed = day;
            Offer o = tw.offers.get(c.employer + "|" + c.trade);
            if (o == null) continue;
            Business b = firmOf(id, c.employer);
            if (o.m > c.m + 0.02) {
                int was = wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice);
                c.m = o.m;
                int now = wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice);
                if (now > was) {
                    note(tw, day, f.displayNameCap() + " had a rise at " + nameOf(id, c.employer) + ": " + was + " to " + now + " a day");
                    felt(f, day, 3);
                }
            } else if (b != null && b.last(day, 7).net() < 0 && o.m < c.m - 0.02) {
                int was = wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice);
                c.m = Math.max(o.m, c.m * 0.9);
                note(tw, day, b.name + " cut " + f.displayNameCap() + "'s pay: " + was + " to " + wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice)
                    + " a day; it is losing money");
            }
        }
    }

    // ------------------------------------------------------------------ lay-offs

    static void layoffs(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        // A firm short of takings lets its newest hand go: one every three days.
        for (Business b : Enterprise.open(id)) {
            CompoundTag r = Enterprise.rec(id, b.id);
            if (r == null || !"struggling".equals(r.getString("status"))) continue;
            if (day - tw.firmLayoff.getOrDefault(b.id, -10L) < 3) continue;
            VillageFolkEntity newest = null;
            long since = Long.MIN_VALUE;
            for (VillageFolkEntity f : Enterprise.hands(id, b)) {
                if (b.ownedBy(f.getUUID())) continue;
                Contract c = tw.contracts.get(f.getUUID());
                long s = c == null ? 0 : c.since;
                if (newest == null || s > since) { newest = f; since = s; }
            }
            if (newest == null) continue;
            tw.firmLayoff.put(b.id, day);
            tw.firmWants.remove(b.id);
            layOff(level, v, tw, newest, "firm:" + b.id, b.name + " is losing money and has more hands than work", day);
        }
        // A town trade two hands over its share two mornings running lets its newest go: one a day for the town.
        Map<StationTask, List<VillageFolkEntity>> at = new EnumMap<>(StationTask.class);
        for (VillageFolkEntity f : folk) {
            Contract c = tw.contracts.get(f.getUUID());
            if (c == null || c.leaving >= 0 || !c.employer.startsWith("town:") && !c.employer.equals("market") && !c.employer.equals("watch")) continue;
            at.computeIfAbsent(f.stationTask(), k -> new ArrayList<>()).add(f);
        }
        boolean laid = day <= tw.lastLayoff;
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE || APPOINTED.contains(t)) continue;
            double over = Villages.share(id, t);
            String k = t.name();
            if (over >= 2.0) tw.overDays.merge(k, 1, Integer::sum);
            else tw.overDays.remove(k);
            if (laid || tw.overDays.getOrDefault(k, 0) < 2) continue;
            if (essentialNow(id, t)) continue;
            List<VillageFolkEntity> hands = at.get(t);
            if (hands == null || hands.size() <= 1) continue;
            VillageFolkEntity newest = null;
            long since = Long.MIN_VALUE;
            for (VillageFolkEntity f : hands) {
                if (f.isElder() || Interviews.busy(f)) continue;
                long s = tw.contracts.get(f.getUUID()).since;
                if (newest == null || s > since) { newest = f; since = s; }
            }
            if (newest == null) continue;
            layOff(level, v, tw, newest, employerFor(t), capital(JobWorth.at(t)) + " have more hands than the town needs", day);
            tw.overDays.remove(k);
            laid = true;
        }
    }

    /**
     * Laid off: off its employer's books and its trade, its ground let go, out of work (relief, and it looks for work). The
     * chronicle has it, it remembers it, and it feels it.
     */
    static void layOff(ServerLevel level, Villages.Village v, LabourBook.Town tw, VillageFolkEntity f, String emp, String why, long day) {
        UUID id = v.id();
        Business firm = firmOf(id, emp);
        if (firm != null) {
            CompoundTag r = Enterprise.rec(id, firm.id);
            if (r != null) Enterprise.removeStaff(id, r, f.getUUID());
        }
        String name = nameOf(id, emp);
        StationTask was = f.stationTask();
        f.setJob(StationTask.NONE);
        f.setWorkZone(null);
        tw.contracts.remove(f.getUUID());
        tw.outSince.put(f.getUUID(), level.getGameTime());
        tw.outWhy.put(f.getUUID(), "laid off by " + name);
        tw.laidOffToday++;
        if (firm == null) tw.lastLayoff = day;
        String line = f.displayNameCap() + " was laid off by " + name + ": " + why;
        note(tw, day, line);
        Villages.tell(id, day, line);
        f.persona().remember(day, "I was laid off by " + name + ": " + why, -4);
        felt(f, day, 2);
        f.brain("laid off by " + name + " (" + was.label + "): " + why);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Laid off. " + capital(name) + " can't keep me on.", "No more work for me at " + name + ". I'll find something.",
            "Let go, just like that. I'd best look for work."));
        f.refreshMood();
    }

    // ------------------------------------------------------------------ openings and offers

    /** The town's essential work just now: food in a famine (or nobody at it), the watch in a raid (or nobody on it). */
    static boolean essentialNow(UUID id, StationTask t) {
        if (t == StationTask.FARM || t == StationTask.FISH) return Market.hungry(id);
        if (t == StationTask.GUARD) return Raids.underAlarm(id);
        return false;
    }

    static boolean essentialOpening(UUID id, StationTask t, int have) {
        if (essentialNow(id, t)) return true;
        return have == 0 && (t == StationTask.FARM || t == StationTask.GUARD) && Villages.wants(id, t);
    }

    static void post(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        Map<StationTask, Integer> have = new EnumMap<>(StationTask.class);
        for (VillageFolkEntity f : folk) if (f.stationTask() != StationTask.NONE) have.merge(f.stationTask(), 1, Integer::sum);
        // Hands already taken on and starting tomorrow count against the opening.
        Map<String, Integer> coming = new HashMap<>();
        for (Contract c : tw.contracts.values()) {
            if (c.leaving < 0) continue;
            String[] g = c.goingTo.split("\\|", -1);
            if (g.length >= 2) coming.merge(g[0] + "|" + g[1], 1, Integer::sum);
        }
        Map<String, Integer> want = new LinkedHashMap<>();
        Set<String> essential = new HashSet<>();
        for (Business b : Enterprise.open(id)) {
            int n = tw.firmWants.getOrDefault(b.id, 0);
            BusinessType bt = Enterprise.kindOf(id, b);
            if (n > 0 && bt != null) want.put("firm:" + b.id + "|" + bt.work().name(), n);
        }
        tw.firmWants.keySet().removeIf(k -> {
            Business b = Businesses.of(id, k);
            return b == null || !b.open;
        });
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE || APPOINTED.contains(t)) continue;
            int n = have.getOrDefault(t, 0);
            boolean ready = Villages.wants(id, t) && Villages.craftReady(id, t);
            if (!ready) continue;
            double shortBy = -Villages.share(id, t);
            int open = shortBy >= 0.75 ? (int) Math.round(shortBy) : 0;
            if (open == 0 && n == 0 && (t.isCraft() || t == StationTask.FARM || t == StationTask.GUARD)) open = 1;
            boolean ess = essentialOpening(id, t, n);
            open = Math.min(open, MOST_OPENINGS + (ess ? 1 : 0));
            if (open <= 0) continue;
            String key = employerFor(t) + "|" + t.name();
            want.put(key, open);
            if (ess) essential.add(key);
        }
        for (Map.Entry<String, Integer> e : coming.entrySet()) {
            Integer n = want.get(e.getKey());
            if (n == null) continue;
            if (n - e.getValue() <= 0) want.remove(e.getKey());
            else want.put(e.getKey(), n - e.getValue());
        }
        // Yesterday's openings: offers up where nobody applied, down where a queue formed; withdrawn where no longer wanted.
        Set<String> seen = new HashSet<>();
        for (Vacancy vac : tw.vacancies) {
            if (!vac.open()) continue;
            String key = vac.employer + "|" + vac.trade;
            StationTask t = vac.task();
            if (t == null || !want.containsKey(key) || seen.contains(key)) {
                if (vac.state == State.OPEN) close(tw, vac, day, State.WITHDRAWN, "no longer wanted");
                continue;
            }
            seen.add(key);
            Offer o = offer(tw, id, vac.employer, t);
            int applied = 0;
            for (Application a : vac.apps) if (a.verdict != Verdict.WITHDRAWN) applied++;
            boolean ess = essential.contains(key);
            o.m = clamp(o.m, M_LEAST, cap(level, id, vac.employer, t, ess));
            vac.openings = want.get(key);
            vac.essential = ess;
            vac.m = o.m;
            vac.wage = journeyman(id, t, o.m);
        }
        // New openings.
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            String[] k = e.getKey().split("\\|");
            StationTask t = JobMarket.named(k[1]);
            if (t == null) continue;
            boolean ess = essential.contains(e.getKey());
            Offer o = offer(tw, id, k[0], t);
            o.m = clamp(o.m, M_LEAST, cap(level, id, k[0], t, ess));
            Vacancy vac = new Vacancy();
            vac.id = ++tw.seq;
            vac.employer = k[0];
            vac.employerName = nameOf(id, k[0]);
            vac.trade = t.name();
            vac.openings = e.getValue();
            vac.minLevel = Math.max(0, JobMarket.wantsLevel(t, id));
            vac.apprentices = t != StationTask.GUARD;
            vac.essential = ess;
            vac.hours = t == StationTask.GUARD ? "the night watch and the day's rounds" : "dawn to dusk";
            vac.m = o.m;
            vac.wage = journeyman(id, t, o.m);
            vac.posted = day;
            tw.vacancies.add(vac);
        }
        // Offers with no opening: back toward the going rate; down for a trade with hands over its share.
        for (Offer o : tw.offers.values()) {
            if (want.containsKey(o.key)) continue;
            String[] k = o.key.split("\\|");
            StationTask t = k.length < 2 ? null : JobMarket.named(k[1]);
            if (t == null) continue;
            o.unfilled = 0;
            if (Villages.share(id, t) >= 1.0 && !k[0].startsWith("firm:")) o.m = Math.max(M_LEAST, o.m * OVER_DOWN);
            else o.m += DRIFT * (index(id, t) - o.m);
        }
        trimClosed(tw);
    }

    static void close(LabourBook.Town tw, Vacancy vac, long day, State how, String note) {
        vac.state = how;
        vac.closed = day;
        if (!note.isEmpty()) vac.note = note;
        for (Application a : vac.apps) {
            if (a.verdict == Verdict.WAITING || a.verdict == Verdict.SHORTLISTED) {
                a.verdict = how == State.FILLED ? Verdict.NOT_TAKEN : Verdict.WITHDRAWN;
                if (a.why.isEmpty()) a.why = how == State.FILLED ? "the place went to another" : "the opening came down: " + note;
            }
        }
    }

    static void trimClosed(LabourBook.Town tw) {
        int closed = 0;
        for (Vacancy vac : tw.vacancies) if (!vac.open()) closed++;
        while (closed > LabourBook.KEEP_CLOSED) {
            Vacancy old = null;
            for (Vacancy vac : tw.vacancies) if (!vac.open() && (old == null || vac.id < old.id)) old = vac;
            if (old == null) break;
            tw.vacancies.remove(old);
            closed--;
        }
    }

    // ------------------------------------------------------------------ the workers choose

    /** How much it likes a trade, nought to about one and a half: it knows the work, its nature, its quirks. */
    static double like(VillageFolkEntity f, StationTask t) {
        double l = 0;
        if (f.tradeLevel(t) > 0) l += 0.5;
        Values.Value v = Values.taughtBy(t);
        if (v != null && Values.top(f) == v) l += 0.5;
        l += 0.1 * Math.max(-5, Math.min(5, Quirks.pull(f, t)));
        return l;
    }

    /** Its loyalty to its employer: three in the hundred a week of service, at most twelve. */
    static double loyalty(@Nullable Contract c, long day) {
        if (c == null) return 0;
        return Math.min(LOYALTY_MOST, Math.max(0, day - c.since) / 7.0 * LOYALTY_A_WEEK);
    }

    /** The employer's name for good work: a firm's reputation (0.95 to 1.05), the town's plain 1. */
    static double reputation(UUID village, String employer) {
        Business b = firmOf(village, employer);
        if (b == null) return 1.0;
        CompoundTag r = Enterprise.rec(village, b.id);
        double rep = r == null || !r.contains("rep") ? 0.5 : r.getDouble("rep");
        return 0.95 + 0.1 * rep;
    }

    /** The walk to the work from home: a hundredth of the wage every sixteen blocks, a tenth at most. */
    static double walk(UUID village, VillageFolkEntity f, String employer) {
        BlockPos home = f.getHome();
        if (home == null) return 0;
        Business b = firmOf(village, employer);
        BlockPos at = b != null && b.at != null ? b.at : Villages.get(village) == null ? null : Villages.get(village).centre();
        if (at == null) return 0;
        return Math.min(0.10, Math.sqrt(home.distSqr(at)) / 16.0 * 0.01);
    }

    /** What the employer makes of an applicant: its level at the trade (ten points to a level), its knacks, its years of work. */
    static double score(VillageFolkEntity f, StationTask t) {
        return 10.0 * f.tradeLevel(t) + 4.0 * JobMarket.knacks(f, t) + Math.min(10, f.veteranLevel() / 2.0);
    }

    static int handsAt(UUID village, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t && !a.isBaby()) n++;
        return n;
    }

    /** The last hand at the town's essential work, who may not be taken from it. */
    static boolean lastAtEssential(UUID village, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        if (t != StationTask.FARM && t != StationTask.GUARD && t != StationTask.FISH) return false;
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t && !a.isBaby()) n++;
        return n <= 1;
    }

    /** May it weigh the openings (and leave its place for one)? */
    static boolean mayApply(UUID village, VillageFolkEntity f, @Nullable Contract c) {
        if (!about(f) || Health.laidUp(f)) return false;
        if (c != null && c.leaving >= 0) return false;
        if (f.stationTask() == StationTask.NONE) return true;
        if (APPOINTED.contains(f.stationTask()) || f.isElder()) return false;
        String emp = employerOf(village, f);
        // One on its own homestead may give it up for a wage where its trade has more hands than the town needs.
        if (emp.startsWith("own:")) return Villages.overStaffed(village, f.stationTask()) && !lastAtEssential(village, f);
        if (!onTerms(emp)) return false;
        if (Enterprise.keepsTrade(f) && !emp.startsWith("firm:")) return false;
        if (Hospitality.keepsTrade(f)) return false;
        return !lastAtEssential(village, f);
    }

    /** The best opening for it and what it would be paid there, or null: {vacancy, wage, utility}. */
    @Nullable
    static Object[] bestFor(UUID village, LabourBook.Town tw, VillageFolkEntity f, boolean outOnly) {
        String emp = employerOf(village, f);
        Contract c = tw.contracts.get(f.getUUID());
        boolean out = f.stationTask() == StationTask.NONE;
        int now = out ? 0 : JobWorth.payOf(f).worthPart();
        // A hand at a trade with more hands than the town needs (its place not safe) takes an equal wage (or a little less) where it is wanted;
        // one in a safe place wants a tenth more and its loyalty.
        boolean surplus = !out && Villages.overStaffed(village, f.stationTask());
        double u0 = out ? 0 : surplus ? now * 0.9 : now * (1 + loyalty(c, Accounts.dayNow()) + SWITCH);
        Vacancy best = null;
        double bu = 0;
        int bw = 0;
        boolean bAppr = false;
        for (Vacancy vac : tw.vacancies) {
            if (vac.state != State.OPEN) continue;
            StationTask t = vac.task();
            if (t == null || vac.employer.equals(emp) && t == f.stationTask()) continue;
            if (Fears.shuns(f, t) || Quirks.instead(f, t) != t) continue;
            if (t == StationTask.GUARD && (f.ageYears() > VillageFolkEntity.OLD_AT)) continue;
            int lv = f.tradeLevel(t);
            boolean appr = lv < APPRENTICE_BELOW && vac.apprentices;
            if (lv < vac.minLevel && !vac.apprentices) continue;
            int w = wageFor(village, t, vac.m, f, lv, appr);
            double u = w * (1 + LIKE * like(f, t)) * reputation(village, vac.employer) - w * walk(village, f, vac.employer);
            if (u > bu) { bu = u; best = vac; bw = w; bAppr = appr; }
        }
        if (best == null || bu <= u0) return null;
        return new Object[]{ best, bw, bu, bAppr, now };
    }

    static void applyAll(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        Set<UUID> waiting = new HashSet<>();
        for (Vacancy vac : tw.vacancies) {
            if (!vac.open()) continue;
            for (Application a : vac.apps) if (a.verdict == Verdict.WAITING || a.verdict == Verdict.SHORTLISTED) waiting.add(a.folk);
        }
        for (VillageFolkEntity f : folk) {
            if (waiting.contains(f.getUUID())) continue;
            Contract c = tw.contracts.get(f.getUUID());
            if (!mayApply(id, f, c)) continue;
            Object[] b = bestFor(id, tw, f, false);
            if (b == null) continue;
            Vacancy vac = (Vacancy) b[0];
            StationTask t = vac.task();
            Application a = new Application();
            a.folk = f.getUUID();
            a.name = f.displayNameCap();
            a.from = f.stationTask() == StationTask.NONE ? "out of work" : nameOf(id, employerOf(id, f));
            a.level = f.tradeLevel(t);
            a.wage = (Integer) b[1];
            a.now = (Integer) b[4];
            a.score = score(f, t);
            a.day = day;
            vac.apps.add(a);
            tw.appliedToday++;
            f.brain("applied to " + vac.employerName + " as " + aNoun(t) + ": " + a.wage + " a day" + (a.now > 0 ? " against " + a.now : ""));
        }
    }

    // ------------------------------------------------------------------ the employers pick

    static void decide(ServerLevel level, Villages.Village v, LabourBook.Town tw, long day) {
        UUID id = v.id();
        for (Vacancy vac : new ArrayList<>(tw.vacancies)) {
            if (vac.state != State.OPEN) continue;
            StationTask t = vac.task();
            if (t == null) continue;
            List<Application> apps = new ArrayList<>();
            for (Application a : vac.apps) if (a.verdict == Verdict.WAITING) apps.add(a);
            if (apps.isEmpty()) {
                // Nobody came for it since it went up: the employer offers more from now on (an eighth for essential work).
                boolean any = false;
                for (Application a : vac.apps) any |= a.verdict != Verdict.WITHDRAWN;
                Offer o = offer(tw, id, vac.employer, t);
                if (!any && day > vac.posted && o.moved < day) {
                    o.moved = day;
                    o.unfilled++;
                    o.queued = 0;
                    o.m = clamp(Math.max(o.m, atFloor(id, t)) * (vac.essential ? STEP_UP_ESSENTIAL : STEP_UP), M_LEAST,
                        Math.max(o.m, cap(level, id, vac.employer, t, vac.essential)));
                    vac.m = o.m;
                    vac.wage = journeyman(id, t, o.m);
                }
                continue;
            }
            apps.sort(Comparator.comparingDouble((Application a) -> -a.score).thenComparing(a -> a.folk));
            // A queue at the door: more than twice the openings applied, and the employer offers less from now on (the
            // places it fills today go at the wage it posted).
            Offer o = offer(tw, id, vac.employer, t);
            if (apps.size() >= 2 * vac.openings && o.moved < day) {
                o.moved = day;
                o.queued++;
                o.unfilled = 0;
                o.m = Math.max(M_LEAST, o.m * STEP_DOWN);
                note(tw, day, vac.employerName + " had " + apps.size() + " applicants for " + JobMarket.words(vac.openings) + " place"
                    + (vac.openings == 1 ? "" : "s") + ": its offer for " + JobMarket.noun(t) + "s eased to " + journeyman(id, t, o.m) + " a day");
            }
            if (apps.size() >= 2 && interviewFor(level, v, tw, vac, apps, day)) continue;
            int slots = vac.openings;
            List<String> taken = new ArrayList<>();
            for (Application a : apps) {
                if (slots <= 0) {
                    a.verdict = Verdict.NOT_TAKEN;
                    a.why = "another was taken: " + String.join(", ", taken);
                    continue;
                }
                VillageFolkEntity f = Civics.find(level, a.folk);
                if (f == null || !f.isAlive()) {
                    a.verdict = Verdict.WITHDRAWN;
                    a.why = "not to be found";
                    continue;
                }
                if (take(level, v, tw, vac, f, a, day, "")) {
                    slots--;
                    taken.add(a.name + " (level " + a.level + ")");
                }
            }
            vac.openings = Math.max(0, slots);
            if (slots <= 0) close(tw, vac, day, State.FILLED, "");
        }
    }

    /**
     * Two or more for a skilled place, and the town holding interviews: the best three shortlisted for an interview at the
     * hall tomorrow (Interviews, the post "job:N"), the rest told no. One interview a day for the town's openings.
     */
    static boolean interviewFor(ServerLevel level, Villages.Village v, LabourBook.Town tw, Vacancy vac, List<Application> apps, long day) {
        if (onPaper || !Interviews.live() || tw.interviewsToday > 0) return false;
        UUID id = v.id();
        StationTask t = vac.task();
        boolean skilled = vac.employer.startsWith("firm:") || vac.minLevel > 0 || t != null && t.isCraft();
        if (!skilled || !Interviews.coming(id).isEmpty() || Interviews.running(id) != null) return false;
        List<VillageFolkEntity> own = new ArrayList<>();
        for (Application a : apps) {
            if (own.size() >= 3) break;
            VillageFolkEntity f = Civics.find(level, a.folk);
            if (f != null && f.isAlive() && !Interviews.busy(f)) own.add(f);
        }
        if (own.size() < 2) return false;
        InterviewPosts.Post p = InterviewPosts.Post.of("job:" + vac.id);
        if (p == null) return false;
        InterviewBook.Interview iv = Interviews.schedule(level, v, p, own, List.of(), day, false);
        vac.state = State.INTERVIEW;
        vac.interview = iv.id;
        tw.interviewsToday++;
        Set<UUID> listed = new HashSet<>();
        for (VillageFolkEntity f : own) listed.add(f.getUUID());
        for (Application a : apps) {
            if (listed.contains(a.folk)) a.verdict = Verdict.SHORTLISTED;
            else {
                a.verdict = Verdict.NOT_TAKEN;
                a.why = "not shortlisted: " + JobMarket.words(own.size()) + " with more to show for it were";
            }
        }
        note(tw, day, vac.employerName + " set interviews for its " + JobMarket.noun(vac.trade) + "'s place: " + own.size() + " shortlisted");
        return true;
    }

    /**
     * Taken: one out of work starts at once; one in work gives a day's notice (unless its employer keeps it with a
     * counter-offer). Returns whether it was taken.
     */
    static boolean take(ServerLevel level, Villages.Village v, LabourBook.Town tw, Vacancy vac, VillageFolkEntity f, Application a, long day, String how) {
        UUID id = v.id();
        StationTask t = vac.task();
        if (t == null) return false;
        boolean appr = f.tradeLevel(t) < APPRENTICE_BELOW && vac.apprentices;
        Contract c = tw.contracts.get(f.getUUID());
        String emp = employerOf(id, f);
        if (f.stationTask() == StationTask.NONE || c == null || !onTerms(emp)) {
            if (!move(level, v, tw, f, vac.employer, t, vac.m, appr, day, how)) {
                a.verdict = Verdict.NOT_TAKEN;
                a.why = "no ground to be had for the work";
                return false;
            }
            a.verdict = Verdict.TAKEN;
            a.why = how.isEmpty() ? "taken on" : how;
            vac.by = join(vac.by, a.name);
            return true;
        }
        if (counter(level, v, tw, f, c, a, day)) {
            a.verdict = Verdict.WITHDRAWN;
            a.why = "its employer kept it on at a rise";
            return false;
        }
        c.leaving = day + 1;
        c.goingTo = vac.employer + "|" + t.name() + "|" + vac.m + "|" + appr + "|" + vac.id;
        a.verdict = Verdict.TAKEN;
        a.why = (how.isEmpty() ? "taken on" : how) + "; starts tomorrow, its notice served";
        vac.by = join(vac.by, a.name + " (from tomorrow)");
        tw.quitToday++;
        String old = nameOf(id, emp);
        String line = f.displayNameCap() + " gave notice at " + old + ": " + vac.employerName + " offered " + a.wage + " a day against " + a.now;
        note(tw, day, line);
        Villages.tell(id, day, line);
        f.persona().remember(day, "I gave my notice at " + old + ": " + vac.employerName + " pays better", 3);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I've given my notice. " + capital(vac.employerName) + " pays " + a.wage + " a day!",
            "Tomorrow I start at " + vac.employerName + ". Better money."));
        return true;
    }

    /**
     * A counter-offer: an employer keeps a skilled hand (level ten) or its last hand at essential work by matching the
     * offer and a little, if it can afford to. True if it was kept.
     */
    static boolean counter(ServerLevel level, Villages.Village v, LabourBook.Town tw, VillageFolkEntity f, Contract c, Application a, long day) {
        UUID id = v.id();
        StationTask t = c.task();
        if (t == null || f.stationTask() != t) return false;
        boolean skilled = f.tradeLevel(t) >= MASTER_HAND;
        if (!skilled && !lastAtEssential(id, f)) return false;
        int now = Math.max(1, a.now);
        double want = c.m * (a.wage * 1.05 / now);
        double most = cap(level, id, c.employer, t, essentialNow(id, t));
        if (want > most) return false;
        int was = wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice);
        c.m = want;
        c.apprentice = false;
        int got = wageFor(id, t, c.m, f, f.tradeLevel(t), false);
        note(tw, day, nameOf(id, c.employer) + " kept " + f.displayNameCap() + " with a counter-offer: " + was + " to " + got + " a day");
        felt(f, day, 3);
        return true;
    }

    /** [labour] The interview for an opening is done (InterviewPosts.give, the post "job:N"): the panel's choice taken. */
    static String interviewed(ServerLevel level, Villages.Village v, int vacancy, VillageFolkEntity winner) {
        LabourBook.Town tw = LabourBook.town(v.id());
        Vacancy vac = vacancy(tw, vacancy);
        if (vac == null || !vac.open()) return "";
        long day = level.getDayTime() / 24000L;
        Application won = null;
        for (Application a : vac.apps) if (a.folk.equals(winner.getUUID())) won = a;
        if (won == null) {
            won = new Application();
            won.folk = winner.getUUID();
            won.name = winner.displayNameCap();
            won.level = vac.task() == null ? 0 : winner.tradeLevel(vac.task());
            won.day = day;
            vac.apps.add(won);
        }
        vac.state = State.OPEN;
        boolean ok = take(level, v, tw, vac, winner, won, day, "after an interview");
        for (Application a : vac.apps) {
            if (a != won && a.verdict == Verdict.SHORTLISTED) {
                a.verdict = Verdict.NOT_TAKEN;
                a.why = "the panel chose " + winner.displayNameCap();
            }
        }
        if (ok) {
            vac.openings = Math.max(0, vac.openings - 1);
            if (vac.openings <= 0) close(tw, vac, day, State.FILLED, "after an interview");
        }
        LabourBook.changed();
        return ok ? "takes up its place at " + vac.employerName : "";
    }

    @Nullable
    static Vacancy vacancy(LabourBook.Town tw, int id) {
        for (Vacancy vac : tw.vacancies) if (vac.id == id) return vac;
        return null;
    }

    /** The opening an interview is for (InterviewPosts: the post "job:N"). */
    @Nullable
    static Vacancy vacancy(UUID village, int id) {
        LabourBook.Town tw = LabourBook.peek(village);
        return tw == null ? null : vacancy(tw, id);
    }

    /** Its title for the interview: "blacksmith at Bram's Tools". */
    static String title(UUID village, int id) {
        Vacancy vac = vacancy(village, id);
        return vac == null ? "hand" : JobMarket.noun(vac.trade) + " at " + vac.employerName;
    }

    @Nullable
    static StationTask tradeOf(UUID village, int id) {
        Vacancy vac = vacancy(village, id);
        return vac == null ? null : vac.task();
    }

    static boolean openFor(UUID village, int id) {
        Vacancy vac = vacancy(village, id);
        return vac != null && vac.open();
    }

    // ------------------------------------------------------------------ essentials

    /**
     * Essential work never starves: an essential opening nobody has taken by the next morning, and the elder calls a hand
     * to it (one out of work first, else one from a trade over its share), at the opening's offer, out of the budget.
     */
    static void essentials(ServerLevel level, Villages.Village v, LabourBook.Town tw, long day) {
        UUID id = v.id();
        for (Vacancy vac : new ArrayList<>(tw.vacancies)) {
            if (vac.state != State.OPEN || !vac.essential || day <= vac.posted) continue;
            StationTask t = vac.task();
            if (t == null) continue;
            // Those out of work first, then hands from the trades furthest over their share.
            List<VillageFolkEntity> picks = new ArrayList<>();
            for (VillageFolkEntity f : grown(id)) if (outOfWork(f) && !Fears.shuns(f, t)) picks.add(f);
            List<VillageFolkEntity> spare = new ArrayList<>();
            Map<StationTask, Double> over = new EnumMap<>(StationTask.class);
            for (VillageFolkEntity f : grown(id)) {
                if (!about(f) || f.isElder() || f.stationTask() == t || f.stationTask() == StationTask.NONE) continue;
                String emp = employerOf(id, f);
                if (!emp.startsWith("town:") && !emp.equals("market") && !emp.equals("watch")) continue;
                if (essentialOpening(id, f.stationTask(), 2) || Fears.shuns(f, t) || lastAtEssential(id, f)) continue;
                // A trade the town has no call for yet at its size counts every hand at it as spare.
                double o = over.computeIfAbsent(f.stationTask(), k -> Villages.wants(id, k) ? Villages.share(id, k) : handsAt(id, k));
                if (o >= 1.0) spare.add(f);
            }
            spare.sort(Comparator.comparingDouble((VillageFolkEntity f) -> -over.get(f.stationTask())).thenComparing(VillageFolkEntity::getUUID));
            picks.addAll(spare);
            int tries = 0;
            for (VillageFolkEntity pick : picks) {
                if (vac.openings <= 0 || tries++ >= 4) break;
                if (move(level, v, tw, pick, vac.employer, t, vac.m, pick.tradeLevel(t) < APPRENTICE_BELOW, day, "the elder's call")) {
                    vac.by = join(vac.by, pick.displayNameCap() + " (the elder's call)");
                    vac.openings--;
                    if (vac.openings <= 0) close(tw, vac, day, State.FILLED, "the elder's call");
                    break;                                                   // one a morning for each opening
                }
                LOG.info("[MCA-LABOUR] {}: the elder's call for {} could not take {}: no ground for the work", Villages.name(id), t.name(), pick.displayNameCap());
            }
            if (picks.isEmpty()) LOG.info("[MCA-LABOUR] {}: the elder's call for {}: nobody to spare", Villages.name(id), t.name());
        }
    }

    // ------------------------------------------------------------------ the day's round for those out of work

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 600 != 137) return;
        com.jrpetty.mcassistant.Guard.run("labour", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !live(v.id())) continue;
                    LabourBook.Town tw = LabourBook.peek(v.id());
                    if (tw == null || tw.tallyDay < 0) continue;
                    round(level, v, tw);
                }
            }
        });
    }

    /**
     * Through the day: one out of work is taken on at once by any opening it fits (the best for it); with none for half a
     * day, the town places it where it is shortest of hands (VillageFolkEntity.takeUpATrade, the old way).
     */
    static int round(ServerLevel level, Villages.Village v, LabourBook.Town tw) {
        long dt = level.getDayTime() % 24000L;
        if (dt < 1000L || dt > 12000L) return 0;
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int took = 0;
        for (VillageFolkEntity f : grown(id)) {
            if (!outOfWork(f)) continue;
            tw.outSince.putIfAbsent(f.getUUID(), level.getGameTime());
            Object[] b = bestFor(id, tw, f, true);
            if (b == null) continue;
            Vacancy vac = (Vacancy) b[0];
            Application a = new Application();
            a.folk = f.getUUID();
            a.name = f.displayNameCap();
            a.from = "out of work";
            a.level = vac.task() == null ? 0 : f.tradeLevel(vac.task());
            a.wage = (Integer) b[1];
            a.score = vac.task() == null ? 0 : score(f, vac.task());
            a.day = day;
            vac.apps.add(a);
            tw.appliedToday++;
            if (take(level, v, tw, vac, f, a, day, "out of work, taken on at once")) {
                took++;
                vac.openings--;
                if (vac.openings <= 0) close(tw, vac, day, State.FILLED, "");
            }
        }
        if (took > 0) LabourBook.changed();
        return took;
    }

    /** Tests: the day's round for those out of work, now. */
    public static int roundForTests(ServerLevel level, Villages.Village v) {
        return round(level, v, LabourBook.town(v.id()));
    }

    /**
     * [labour] VillageFolkEntity.takeUpATrade: one out of work in a town with a labour market looks for work (the openings,
     * the day's round) for half a day before the town places it where it is shortest of hands.
     */
    public static boolean lookingForWork(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.stationTask() != StationTask.NONE || !live(id)) return false;
        if (!(f.level() instanceof ServerLevel sl)) return false;
        LabourBook.Town tw = LabourBook.town(id);
        Long since = tw.outSince.get(f.getUUID());
        long now = sl.getGameTime();
        if (since == null) {
            tw.outSince.put(f.getUUID(), now);
            tw.outWhy.putIfAbsent(f.getUUID(), "looking for work");
            return true;
        }
        return now - since >= 0 && now - since < SEARCH;
    }

    /** [labour] VillageFolkEntity.changedTrade: where the labour market moves the hands, the town's own sums do not. */
    public static boolean movesHands(@Nullable UUID village) {
        return live(village);
    }

    // ------------------------------------------------------------------ training

    static void train(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        Map<String, Integer> best = new HashMap<>();
        for (VillageFolkEntity f : folk) {
            Contract c = tw.contracts.get(f.getUUID());
            String emp = c != null ? c.employer : employerOf(id, f);
            if (emp.startsWith("owner:")) emp = "firm:" + emp.substring(6);
            if (f.stationTask() == StationTask.NONE) continue;
            best.merge(emp + "|" + f.stationTask().name(), f.tradeLevel(f.stationTask()), Math::max);
        }
        for (VillageFolkEntity f : folk) {
            Contract c = tw.contracts.get(f.getUUID());
            if (c == null || !c.apprentice || !c.trade.equals(f.stationTask().name())) continue;
            StationTask t = f.stationTask();
            int master = best.getOrDefault(c.employer + "|" + c.trade, 0);
            if (master >= MASTER_HAND && master > f.tradeLevel(t)) f.awardXp(TRAIN_XP);
            if (f.tradeLevel(t) >= JOURNEYMAN) {
                c.apprentice = false;
                Offer o = tw.offers.get(c.employer + "|" + c.trade);
                if (o != null) c.m = Math.max(c.m, o.m);
                int now = wageFor(id, t, c.m, f, f.tradeLevel(t), false);
                String line = f.displayNameCap() + " finished its apprenticeship at " + nameOf(id, c.employer) + ": a journeyman " + JobMarket.noun(t)
                    + " now, " + now + " a day";
                note(tw, day, line);
                Villages.tell(id, day, line);
                f.persona().remember(day, "I finished my apprenticeship: a journeyman " + JobMarket.noun(t) + " now", 5);
                felt(f, day, 4);
            }
        }
    }

    // ------------------------------------------------------------------ relief

    /** Poor relief for those out of work, out of the treasury: half the living wage a morning, means-tested. */
    static void relief(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        int living = livingWage(id);
        int each = Math.max(1, (int) Math.round(living * RELIEF_SHARE));
        for (VillageFolkEntity f : folk) {
            if (!outOfWork(f) || f.isElder()) continue;
            if (f.purse() + Bank.balance(f) >= RELIEF_MEANS_DAYS * living) continue;
            int got = Ledger.takeCoins(id, each);
            if (got <= 0) break;
            f.earn(got);
            tw.reliefToday += got * 100L;
            TownBudget.spent(id, Accounts.Dept.RELIEF, got * 100L, 0);
            f.brain("drew " + got + " of poor relief: out of work");
        }
    }

    // ------------------------------------------------------------------ the council's minimum wage

    /**
     * Once a week the council looks at the minimum wage: with work for nearly everybody (out of work under one in twenty),
     * the budget easy and its lowest paid near the living wage, it sets one a fifth over the living wage; with many out
     * of work (one in seven) it lowers what it set, a coin.
     */
    static void council(ServerLevel level, Villages.Village v, LabourBook.Town tw, long day) {
        if (tw.minimumLooked >= 0 && day - tw.minimumLooked < 7) return;
        if (tw.minimumLooked < 0) { tw.minimumLooked = day; return; }
        tw.minimumLooked = day;
        UUID id = v.id();
        int[] n = counts(id, tw);
        int all = n[0] + n[1] + n[2];
        if (all < 6) return;
        double rate = n[2] / (double) all;
        int living = livingWage(id);
        int want = (int) Math.ceil(living * 1.2);
        if (rate < 0.05 && TownBudget.pressure(id) == 0 && tw.minimumWage < want) {
            tw.minimumWage = want;
            String line = "the council set a minimum wage of " + want + " a day: there is work for all, and the least paid should live well";
            note(tw, day, line);
            Villages.tell(id, day, line);
        } else if (rate >= 1.0 / 7.0 && tw.minimumWage > 0) {
            tw.minimumWage = tw.minimumWage - 1 <= living ? 0 : tw.minimumWage - 1;
            String line = tw.minimumWage == 0 ? "the council dropped its minimum wage: too many are out of work"
                : "the council lowered the minimum wage to " + tw.minimumWage + " a day: too many are out of work";
            note(tw, day, line);
            Villages.tell(id, day, line);
        }
    }

    /** A player leader (or an operator) sets the minimum wage: so many coins a day (0 for none). */
    public static String setMinimum(UUID village, int coins, String by) {
        LabourBook.Town tw = LabourBook.town(village);
        tw.minimumWage = Math.max(0, Math.min(200, coins));
        long day = Accounts.dayNow();
        String line = tw.minimumWage == 0 ? by + " dropped the minimum wage" : by + " set a minimum wage of " + tw.minimumWage + " a day";
        note(tw, day, line);
        Villages.tell(village, day, line);
        LabourBook.changed();
        return capital(line) + " (the living wage is " + livingWage(village) + ").";
    }

    // ------------------------------------------------------------------ the day's record

    /** {on terms, self-employed, out of work} among the grown folk loaded. */
    static int[] counts(UUID id, LabourBook.Town tw) {
        int[] n = new int[3];
        for (VillageFolkEntity f : grown(id)) {
            if (f.isHired()) continue;
            String emp = employerOf(id, f);
            if (emp.isEmpty()) { if (outOfWork(f)) n[2]++; }
            else if (selfEmployed(emp)) n[1]++;
            else n[0]++;
        }
        return n;
    }

    static void record(ServerLevel level, Villages.Village v, LabourBook.Town tw, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        DayRecord d = new DayRecord();
        d.day = day;
        int[] n = counts(id, tw);
        d.employed = n[0];
        d.selfEmployed = n[1];
        d.unemployed = n[2];
        for (Vacancy vac : tw.vacancies) if (vac.open()) d.openings += vac.openings;
        d.hired = tw.hiredToday;
        d.quit = tw.quitToday;
        d.laidOff = tw.laidOffToday;
        d.applied = tw.appliedToday;
        d.relief = tw.reliefToday;
        d.minimum = minimumWage(id);
        Map<String, double[]> by = new LinkedHashMap<>();
        for (VillageFolkEntity f : folk) {
            if (f.stationTask() == StationTask.NONE) continue;
            String k = f.stationTask().name();
            double[] a = by.computeIfAbsent(k, x -> new double[4]);
            a[0] += JobWorth.payOf(f).worthPart();
            a[1]++;
            Contract c = tw.contracts.get(f.getUUID());
            if (c != null && c.trade.equals(k)) { a[2] += c.m; a[3]++; }
        }
        DayRecord last = tw.days.isEmpty() ? null : tw.days.get(tw.days.size() - 1);
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            String k = t.name();
            double[] a = by.get(k);
            if (a != null && a[1] > 0) d.wages.put(k, (int) Math.round(a[0] / a[1]));
            Double was = last == null ? null : last.index.get(k);
            double raw;
            if (a != null && a[3] > 0) raw = a[2] / a[3];
            else {
                Offer o = tw.offers.get(employerFor(t) + "|" + k);
                if (o == null && was == null) continue;
                raw = o != null ? o.m : was;
            }
            double x = was == null ? raw : was + 0.5 * (raw - was);
            d.index.put(k, Math.round(clamp(x, M_LEAST, M_TOP) * 1000.0) / 1000.0);
        }
        tw.days.add(d);
        while (tw.days.size() > LabourBook.KEEP_DAYS) tw.days.remove(0);
        LOG.info("[MCA-LABOUR] {} day {}: {} on terms, {} self-employed, {} out of work; {} openings; {} hired, {} gave notice, {} laid off, {} applied; relief {}; minimum {}",
            Villages.name(id), day + 1, d.employed, d.selfEmployed, d.unemployed, d.openings, d.hired, d.quit, d.laidOff, d.applied,
            Businesses.pence(d.relief), d.minimum);
    }

    // ------------------------------------------------------------------ the folk: card, mood

    static void felt(VillageFolkEntity f, long day, int kind) {
        FELT.put(f.getUUID(), new long[]{ day, kind });
    }

    /** [labour] Its spirits (VillageFolkEntity.refreshMood): a new place or a rise, glad; laid off, or long out of work, down. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        long[] a = FELT.get(f.getUUID());
        if (a != null && day - a[0] <= 2) {
            int k = (int) a[1];
            if (k == 2) { why.add(new Object[]{ "laidoff", 6 }); return m - 6; }
            int up = k == 1 ? 5 : k == 4 ? 6 : 3;
            why.add(new Object[]{ k == 1 ? "newjob" : k == 4 ? "journeyman" : "termsrise", up });
            return m + up;
        }
        if (f.stationTask() == StationTask.NONE && live(f.ownerId()) && f.level() instanceof ServerLevel sl) {
            LabourBook.Town tw = LabourBook.peek(f.ownerId());
            Long since = tw == null ? null : tw.outSince.get(f.getUUID());
            if (since != null && sl.getGameTime() - since > 24000L) {
                why.add(new Object[]{ "outofwork", 4 });
                return m - 4;
            }
        }
        return m;
    }

    /** How it puts it, asked how it is. */
    public static String moodWords(VillageFolkEntity f, String why) {
        return switch (why) {
            case "laidoff" -> FolkTalk.pick(f.getRandom(), "I was laid off. It stings.", "No work since they let me go.");
            case "newjob" -> FolkTalk.pick(f.getRandom(), "I've a new place, and better pay!", "New work, and I like it.");
            case "journeyman" -> FolkTalk.pick(f.getRandom(), "My apprenticeship's done: a journeyman now!", "Full pay at last — I've learned the trade.");
            case "termsrise" -> FolkTalk.pick(f.getRandom(), "I had a rise!", "More pay this week. Somebody noticed.");
            case "outofwork" -> FolkTalk.pick(f.getRandom(), "Still no work. The relief keeps me fed, just.", "Out of work a day and more. I'll take what comes.");
            default -> "";
        };
    }

    /** [labour] Its card's line: who it works for and on what terms, or since when it has been out of work. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !live(id)) return null;
        LabourBook.Town tw = LabourBook.peek(id);
        if (tw == null) return null;
        String emp = employerOf(id, f);
        if (emp.isEmpty()) {
            Long since = tw.outSince.get(f.getUUID());
            if (since == null) return null;
            String why = tw.outWhy.getOrDefault(f.getUUID(), "");
            return "Out of work" + (why.isEmpty() ? "" : " (" + why + ")") + "; on poor relief and looking for work";
        }
        if (emp.startsWith("own:")) return "Self-employed: works its own " + JobMarket.noun(f.stationTask()) + "'s business";
        if (emp.startsWith("owner:")) return "Its own master: works its own firm, " + nameOf(id, emp);
        Contract c = tw.contracts.get(f.getUUID());
        if (c == null) return "Works for " + nameOf(id, emp);
        StationTask t = f.stationTask();
        String s = "Works for " + nameOf(id, c.employer) + " as " + aNoun(t) + (c.apprentice ? " (an apprentice)" : "") + " since day " + (c.since + 1)
            + ", " + wageFor(id, t, c.m, f, f.tradeLevel(t), c.apprentice) + " a day on its terms";
        if (c.leaving >= 0) {
            String[] g = c.goingTo.split("\\|", -1);
            s += "; has given notice: to " + nameOf(id, g[0]) + " on day " + (c.leaving + 1);
        }
        return s;
    }

    // ------------------------------------------------------------------ reports

    static void note(LabourBook.Town tw, long day, String line) {
        tw.news.add("Day " + (day + 1) + ": " + line);
        while (tw.news.size() > 30) tw.news.remove(0);
        LOG.info("[MCA-LABOUR] {}", line);
    }

    /** The Jobs page's labour market view (client/LabourPage), for the city books (Annals.snapshot). */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        out.putBoolean("live", live(village));
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw == null) return out;
        int[] n = counts(village, tw);
        out.putInt("employed", n[0]);
        out.putInt("self", n[1]);
        out.putInt("out", n[2]);
        out.putInt("living", livingWage(village));
        out.putInt("minimum", tw.minimumWage);
        long day = level.getDayTime() / 24000L;
        ListTag vs = new ListTag();
        int open = 0;
        List<Vacancy> sorted = new ArrayList<>(tw.vacancies);
        sorted.sort(Comparator.comparing((Vacancy x) -> !x.open()).thenComparing(x -> -x.id));
        for (Vacancy vac : sorted) {
            if (vac.open()) open += vac.openings;
            CompoundTag t = new CompoundTag();
            StationTask task = vac.task();
            t.putInt("id", vac.id);
            t.putString("employer", vac.employerName);
            t.putString("title", JobMarket.noun(vac.trade));
            t.putInt("ordinal", task == null ? 0 : task.ordinal());
            t.putInt("openings", vac.openings);
            t.putInt("wage", vac.wage);
            t.putInt("level", vac.minLevel);
            t.putBoolean("apprentices", vac.apprentices);
            t.putBoolean("essential", vac.essential);
            t.putString("hours", vac.hours);
            t.putLong("posted", vac.posted);
            t.putString("state", vac.state.name().toLowerCase(Locale.ROOT));
            t.putString("by", vac.by);
            t.putString("note", vac.note);
            ListTag as = new ListTag();
            for (Application a : vac.apps) {
                CompoundTag at = new CompoundTag();
                at.putString("name", a.name);
                at.putString("from", a.from);
                at.putInt("level", a.level);
                at.putInt("wage", a.wage);
                at.putInt("now", a.now);
                at.putString("verdict", a.verdict.name().toLowerCase(Locale.ROOT));
                at.putString("why", a.why);
                as.add(at);
            }
            t.put("apps", as);
            vs.add(t);
        }
        out.put("vacancies", vs);
        out.putInt("open", open);
        // The wage index: today's journeyman's day by trade, against a week ago.
        ListTag idx = new ListTag();
        DayRecord now = tw.days.isEmpty() ? null : tw.days.get(tw.days.size() - 1);
        DayRecord week = null;
        for (DayRecord d : tw.days) if (d.day <= day - 7) week = d;
        if (now != null) {
            for (Map.Entry<String, Double> e : now.index.entrySet()) {
                StationTask t = JobMarket.named(e.getKey());
                if (t == null) continue;
                CompoundTag r = new CompoundTag();
                r.putString("title", JobMarket.noun(t));
                r.putInt("ordinal", t.ordinal());
                r.putDouble("index", e.getValue());
                r.putInt("wage", journeyman(village, t, e.getValue()));
                r.putInt("paid", now.wages.getOrDefault(e.getKey(), 0));
                Double w = week == null ? null : week.index.get(e.getKey());
                r.putDouble("was", w == null ? -1 : w);
                idx.add(r);
            }
        }
        out.put("index", idx);
        ListTag days = new ListTag();
        for (DayRecord d : tw.days) {
            CompoundTag t = new CompoundTag();
            t.putLong("day", d.day);
            t.putIntArray("n", new int[]{ d.employed, d.selfEmployed, d.unemployed, d.openings, d.hired, d.quit, d.laidOff, d.applied });
            t.putLong("relief", d.relief);
            days.add(t);
        }
        out.put("days", days);
        // The departments.
        ListTag depts = new ListTag();
        Map<String, int[]> heads = new LinkedHashMap<>();
        for (Contract c : tw.contracts.values()) {
            String k = c.employer.startsWith("firm:") ? "firms" : c.employer.startsWith("town:") ? "town" : c.employer;
            heads.computeIfAbsent(k, x -> new int[1])[0]++;
        }
        int crews = 0;
        for (VillageFolkEntity f : grown(village)) if (TownJobs.busy(f)) crews++;
        depts.add(StringTag.valueOf("The watch: " + heads.getOrDefault("watch", new int[1])[0] + " on its books, paid by the treasury"));
        depts.add(StringTag.valueOf("The market: " + heads.getOrDefault("market", new int[1])[0] + " keepers and couriers, paid out of its own cash"));
        depts.add(StringTag.valueOf("The town's own trades: " + heads.getOrDefault("town", new int[1])[0] + " hands, paid by the treasury"));
        depts.add(StringTag.valueOf("The firms: " + heads.getOrDefault("firms", new int[1])[0] + " hands, paid out of their tills"));
        depts.add(StringTag.valueOf("Public works: " + crews + " on the town's works now, drawn from the trades for a turn"));
        depts.add(StringTag.valueOf("Poor relief: " + n[2] + " out of work, " + Businesses.pence(tw.reliefToday) + " paid this morning"));
        depts.add(StringTag.valueOf("The mint: struck at the market's counter by its keepers"));
        out.put("depts", depts);
        ListTag news = new ListTag();
        for (int i = tw.news.size() - 1; i >= 0 && news.size() < 12; i--) news.add(StringTag.valueOf(tw.news.get(i)));
        out.put("news", news);
        // Who is out of work, and why.
        ListTag outs = new ListTag();
        for (VillageFolkEntity f : grown(village)) {
            if (!outOfWork(f)) continue;
            outs.add(StringTag.valueOf(f.displayNameCap() + ": " + tw.outWhy.getOrDefault(f.getUUID(), "looking for work")));
        }
        out.put("outs", outs);
        return out;
    }

    /** The labour market in lines (/village labour). */
    public static List<String> status(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        String town = Villages.name(village);
        if (!live(village)) {
            out.add(town + " has no labour market yet: its market is not open, and the elder shares the work out.");
            return out;
        }
        LabourBook.Town tw = LabourBook.town(village);
        int[] n = counts(village, tw);
        out.add(town + ": " + n[0] + " on terms, " + n[1] + " their own masters, " + n[2] + " out of work; living wage " + livingWage(village)
            + (tw.minimumWage > 0 ? ", minimum wage " + tw.minimumWage : "") + " a day.");
        for (Vacancy vac : tw.vacancies) {
            if (!vac.open()) continue;
            int applied = 0;
            for (Application a : vac.apps) if (a.verdict == Verdict.WAITING || a.verdict == Verdict.SHORTLISTED) applied++;
            out.add("  #" + vac.id + " " + vac.employerName + " wants " + (vac.openings == 1 ? "a " : vac.openings + " ") + JobMarket.noun(vac.trade)
                + (vac.openings == 1 ? "" : "s") + ": " + vac.wage + " a day" + (vac.essential ? " (essential)" : "")
                + (vac.minLevel > 0 ? ", level " + vac.minLevel : "") + (vac.apprentices ? ", apprentices taken" : "") + "; " + applied + " applied"
                + (vac.state == State.INTERVIEW ? ", interviewing" : ""));
        }
        DayRecord d = tw.days.isEmpty() ? null : tw.days.get(tw.days.size() - 1);
        if (d != null) {
            List<String> w = new ArrayList<>();
            for (Map.Entry<String, Double> e : d.index.entrySet()) {
                StationTask t = JobMarket.named(e.getKey());
                if (t != null && d.wages.containsKey(e.getKey())) w.add(JobMarket.noun(t) + " " + journeyman(village, t, e.getValue())
                    + String.format(Locale.ROOT, " (x%.2f)", e.getValue()));
            }
            if (!w.isEmpty()) out.add("Wage index (a journeyman's day): " + String.join(", ", w));
        }
        for (int i = Math.max(0, tw.news.size() - 6); i < tw.news.size(); i++) out.add("  " + tw.news.get(i));
        return out;
    }

    /** One line of the town's labour market, for the long tests' logs. */
    public static String line(UUID village) {
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw == null || tw.days.isEmpty()) return "no labour market yet";
        DayRecord d = tw.days.get(tw.days.size() - 1);
        StringBuilder w = new StringBuilder();
        for (Map.Entry<String, Integer> e : d.wages.entrySet()) {
            Double x = d.index.get(e.getKey());
            w.append(e.getKey().toLowerCase(Locale.ROOT)).append(' ').append(e.getValue()).append(x == null ? "" : String.format(Locale.ROOT, "@%.2f", x)).append(", ");
        }
        int open = 0;
        for (Vacancy vac : tw.vacancies) if (vac.open()) open += vac.openings;
        return String.format(Locale.ROOT, "day %d: %d on terms, %d self-employed, %d out of work; %d openings (%d now); %d hired, %d gave notice, %d laid off, %d applied; relief %s; minimum %d; wages %s",
            d.day + 1, d.employed, d.selfEmployed, d.unemployed, d.openings, open, d.hired, d.quit, d.laidOff, d.applied, Businesses.pence(d.relief), d.minimum, w);
    }

    // ------------------------------------------------------------------ player firms

    /** A player's firm posts an opening at so many coins a day (0: its going offer). Returns what was done, in words. */
    public static String post(ServerLevel level, UUID village, Business b, int coins) {
        if (!live(village)) return "The town has no labour market yet: its market is not open.";
        BusinessType bt = Enterprise.kindOf(village, b);
        if (bt == null || !b.open) return b.name + " is not trading.";
        LabourBook.Town tw = LabourBook.town(village);
        StationTask t = bt.work();
        Offer o = offer(tw, village, "firm:" + b.id, t);
        if (coins > 0) {
            double base = Math.max(1, journeyman(village, t, 1.0));
            o.m = clamp(coins / base, M_LEAST, M_TOP);
        }
        tw.firmWants.merge(b.id, 1, (a, c) -> Math.min(3, a + c));
        long day = level.getDayTime() / 24000L;
        post(level, Villages.get(village), tw, grown(village), day);
        applyAll(level, Villages.get(village), tw, grown(village), day);
        LabourBook.changed();
        Vacancy vac = null;
        for (Vacancy x : tw.vacancies) if (x.open() && x.employer.equals("firm:" + b.id)) vac = x;
        if (vac == null) return "No opening posted.";
        int applied = 0;
        for (Application a : vac.apps) if (a.verdict == Verdict.WAITING) applied++;
        return b.name + " posted an opening for " + aNoun(t) + " at " + vac.wage + " a day (#" + vac.id + "); " + applied + " applied so far."
            + " Take the best with /village labour hire " + b.id + ".";
    }

    /** A player's firm takes on the best who applied to its opening, now. */
    public static String hireBest(ServerLevel level, UUID village, Business b) {
        LabourBook.Town tw = LabourBook.town(village);
        Vacancy vac = null;
        for (Vacancy x : tw.vacancies) if (x.state == State.OPEN && x.employer.equals("firm:" + b.id)) vac = x;
        if (vac == null) return b.name + " has no opening up: post one with /village labour post " + b.id + " [wage].";
        Application best = null;
        for (Application a : vac.apps) if (a.verdict == Verdict.WAITING && (best == null || a.score > best.score)) best = a;
        if (best == null) return "Nobody has applied to " + b.name + "'s opening yet. A better wage brings more.";
        VillageFolkEntity f = Civics.find(level, best.folk);
        if (f == null) return best.name + " is not to be found.";
        long day = level.getDayTime() / 24000L;
        boolean ok = take(level, Villages.get(village), tw, vac, f, best, day, "chosen by its owner");
        if (ok) {
            vac.openings--;
            if (vac.openings <= 0) close(tw, vac, day, State.FILLED, "");
        }
        LabourBook.changed();
        return ok ? best.name + " " + (best.why.contains("tomorrow") ? "gave notice and starts at " + b.name + " tomorrow" : "is " + b.name + "'s hand now")
            + ", at " + best.wage + " a day." : best.name + " would not come: " + best.why + ".";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: this folk's terms {employer, trade, m, apprentice, since, leaving} or null. */
    @Nullable
    public static Object[] termsForTests(VillageFolkEntity f) {
        Contract c = contract(f);
        return c == null ? null : new Object[]{ c.employer, c.trade, c.m, c.apprentice, c.since, c.leaving };
    }

    /** Tests: the offer for an employer and trade, as a multiple of the job's worth. */
    public static double offerForTests(UUID village, String employer, StationTask t) {
        LabourBook.Town tw = LabourBook.town(village);
        return offer(tw, village, employer, t).m;
    }

    public static void setOfferForTests(UUID village, String employer, StationTask t, double m) {
        LabourBook.Town tw = LabourBook.town(village);
        offer(tw, village, employer, t).m = m;
    }

    /** Tests: the open openings, as {id, employer, trade, openings, wage, applicants waiting, state, essential}. */
    public static List<Object[]> openingsForTests(UUID village) {
        List<Object[]> out = new ArrayList<>();
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw == null) return out;
        for (Vacancy vac : tw.vacancies) {
            int waiting = 0;
            for (Application a : vac.apps) if (a.verdict == Verdict.WAITING || a.verdict == Verdict.SHORTLISTED) waiting++;
            out.add(new Object[]{ vac.id, vac.employer, vac.trade, vac.openings, vac.wage, waiting, vac.state.name(), vac.essential, vac.by });
        }
        return out;
    }

    /** Tests: a folk's wage at its terms now, coins. */
    public static int wageForTests(VillageFolkEntity f) {
        return JobWorth.payOf(f).worthPart();
    }

    public static String employerForTests(VillageFolkEntity f) {
        return f.ownerId() == null ? "" : employerOf(f.ownerId(), f);
    }

    /** Tests: put this folk on terms at an employer now (as a hiring would). */
    public static void termsForTests(VillageFolkEntity f, double m, boolean apprentice, long since) {
        LabourBook.Town tw = LabourBook.town(f.ownerId());
        Contract c = terms(tw, f.ownerId(), f, employerOf(f.ownerId(), f), f.stationTask(), m, since);
        c.apprentice = apprentice;
        c.reviewed = since;
    }

    public static int[] countsForTests(UUID village) {
        return counts(village, LabourBook.town(village));
    }

    public static boolean outForTests(VillageFolkEntity f) {
        return outOfWork(f);
    }

    /** Tests: the last day's record {employed, self, out, openings, hired, quit, laidOff, applied} and its index by trade. */
    @Nullable
    public static Map<String, Double> indexForTests(UUID village) {
        LabourBook.Town tw = LabourBook.peek(village);
        return tw == null || tw.days.isEmpty() ? null : tw.days.get(tw.days.size() - 1).index;
    }

    public static List<String> newsForTests(UUID village) {
        LabourBook.Town tw = LabourBook.peek(village);
        return tw == null ? List.of() : new ArrayList<>(tw.news);
    }

    /** Tests: the posts of the interviews set in the town ("job:7"...). */
    public static List<String> interviewsForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (InterviewBook.Interview iv : Interviews.coming(village)) out.add(iv.post);
        return out;
    }

    /** Tests: the panel's choice for an opening, as the interview's end gives it. */
    public static String interviewedForTests(ServerLevel level, Villages.Village v, int vacancy, VillageFolkEntity winner) {
        return interviewed(level, v, vacancy, winner);
    }

    /** Tests: a firm wants a hand it could not find (Enterprise.hire found nobody). */
    public static void firmShortForTests(UUID village, Business b) {
        firmShort(village, b);
    }

    /** Tests: the book written out and read back, as the world's save would (save/load). */
    public static void reloadForTests(net.minecraft.core.HolderLookup.Provider registries) {
        LabourBook b = LabourBook.of();
        CompoundTag saved = b.save(new CompoundTag(), registries);
        LabourBook back = LabourBook.load(saved, registries);
        b.towns.clear();
        b.towns.putAll(back.towns);
    }

    // ------------------------------------------------------------------ words

    static String aNoun(StationTask t) {
        String n = JobMarket.noun(t);
        return (n.isEmpty() ? "a " : "aeiou".indexOf(Character.toLowerCase(n.charAt(0))) >= 0 ? "an " : "a ") + n;
    }

    static String join(String a, String b) {
        return a.isEmpty() ? b : a + ", " + b;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
