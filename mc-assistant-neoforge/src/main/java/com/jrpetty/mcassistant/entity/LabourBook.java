package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [labour] The labour market's books, kept with the world: every hand's terms of work (who it works for, at what trade,
 * at what wage, since when, an apprentice or not, and any notice it has given), every employer's wage offer for each
 * trade it hires at, the openings posted with their applicants and how each went, who is out of work and since when, and
 * the town's day-by-day record (the wage index by trade, the employed and the unemployed, the openings, the hirings, the
 * quits and the lay-offs). LabourMarket runs the market; this only keeps it.
 */
public final class LabourBook extends SavedData {

    private static final String ID = "mc_assistant_labour";
    /** How many closed openings and days of history each town keeps. */
    static final int KEEP_CLOSED = 24, KEEP_DAYS = 40;

    final Map<UUID, Town> towns = new HashMap<>();

    @Nullable private static LabourBook loose;

    public LabourBook() {}

    static LabourBook of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new LabourBook();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(LabourBook::new, LabourBook::load, null), ID);
    }

    static void changed() {
        of().setDirty();
    }

    static Town town(UUID village) {
        return of().towns.computeIfAbsent(village, k -> new Town());
    }

    @Nullable
    static Town peek(UUID village) {
        return of().towns.get(village);
    }

    /** Tests: an empty book. */
    void clear() {
        towns.clear();
        setDirty();
    }

    // ------------------------------------------------------------------ what is kept

    /** One town's labour market. */
    static final class Town {
        int seq;
        /** Each hand's terms, by the folk. */
        final Map<UUID, Contract> contracts = new LinkedHashMap<>();
        /** Each employer's offer for each trade, by "employer|TRADE". */
        final Map<String, Offer> offers = new LinkedHashMap<>();
        /** The openings, open and lately closed. */
        final List<Vacancy> vacancies = new ArrayList<>();
        /** Out of work since (game time), by the folk; and why ("laid off by the fields", "grown up", "came to town"). */
        final Map<UUID, Long> outSince = new LinkedHashMap<>();
        final Map<UUID, String> outWhy = new HashMap<>();
        /** Hands a firm still wants that nobody took (by firm id). */
        final Map<String, Integer> firmWants = new LinkedHashMap<>();
        /** The town's days, the latest last. */
        final List<DayRecord> days = new ArrayList<>();
        /** The council's minimum wage, coins (0: the living wage is the least). */
        int minimumWage;
        /** The day the council last looked at the minimum wage. */
        long minimumLooked = -1;
        /** Mornings in a row each town trade has been two hands over its share (by trade name): a lay-off after two. */
        final Map<String, Integer> overDays = new HashMap<>();
        /** The last day this town laid a hand off, and the day each firm last did. */
        long lastLayoff = -10;
        final Map<String, Long> firmLayoff = new HashMap<>();
        /** Today's tallies, for the day's record. */
        int hiredToday, quitToday, laidOffToday, appliedToday, interviewsToday;
        long reliefToday;
        long tallyDay = -1;
        /** The last lines worth telling (hirings, quits, lay-offs, the council), the latest last. */
        final List<String> news = new ArrayList<>();
    }

    /** A hand's terms of work. */
    static final class Contract {
        UUID folk;
        String name = "";
        /** "firm:ent-3", "market", "watch", "town:FARM". */
        String employer = "";
        String trade = "";
        /** Its wage, as a multiple of what its job is worth here (JobWorth): the offer it was taken on at, raised at reviews. */
        double m = 1.0;
        long since;
        boolean apprentice;
        /** Notice given: the day it leaves, and where to ("employer|TRADE|m|apprentice"); -1 for none. */
        long leaving = -1;
        String goingTo = "";
        /** The last day its pay was looked at, and the last morning it was seen in the town. */
        long reviewed, seen;

        @Nullable StationTask task() { return JobMarket.named(trade); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Folk", folk);
            t.putString("Name", name);
            t.putString("Employer", employer);
            t.putString("Trade", trade);
            t.putDouble("M", m);
            t.putLong("Since", since);
            t.putBoolean("Apprentice", apprentice);
            t.putLong("Leaving", leaving);
            t.putString("GoingTo", goingTo);
            t.putLong("Reviewed", reviewed);
            t.putLong("Seen", seen);
            return t;
        }

        static Contract load(CompoundTag t) {
            Contract c = new Contract();
            c.folk = t.getUUID("Folk");
            c.name = t.getString("Name");
            c.employer = t.getString("Employer");
            c.trade = t.getString("Trade");
            c.m = t.contains("M") ? t.getDouble("M") : 1.0;
            c.since = t.getLong("Since");
            c.apprentice = t.getBoolean("Apprentice");
            c.leaving = t.contains("Leaving") ? t.getLong("Leaving") : -1;
            c.goingTo = t.getString("GoingTo");
            c.reviewed = t.getLong("Reviewed");
            c.seen = t.contains("Seen") ? t.getLong("Seen") : c.since;
            return c;
        }
    }

    /** An employer's going offer for a trade. */
    static final class Offer {
        String key = "";
        double m = 1.0;
        /** Mornings in a row it had an opening nobody applied for, and with more applicants than openings. */
        int unfilled, queued;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Key", key);
            t.putDouble("M", m);
            t.putInt("Unfilled", unfilled);
            t.putInt("Queued", queued);
            return t;
        }

        static Offer load(CompoundTag t) {
            Offer o = new Offer();
            o.key = t.getString("Key");
            o.m = t.contains("M") ? t.getDouble("M") : 1.0;
            o.unfilled = t.getInt("Unfilled");
            o.queued = t.getInt("Queued");
            return o;
        }
    }

    /** How an opening stands. */
    enum State { OPEN, INTERVIEW, FILLED, WITHDRAWN }

    /** An opening: who is hiring, for what, at what wage, what it wants, who applied and who was taken. */
    static final class Vacancy {
        int id;
        String employer = "", employerName = "", trade = "";
        int openings = 1;
        int minLevel;
        /** A hand new to the trade may be taken on as an apprentice (at a lower wage, trained up). */
        boolean apprentices = true;
        /** The town's essential work (food, the watch in danger, an empty trade it lives by): paid out of the budget if need be. */
        boolean essential;
        String hours = "dawn to dusk";
        /** The offer, as a multiple of the job's worth, and in coin for a journeyman (level five) when posted or last moved. */
        double m = 1.0;
        int wage;
        long posted, closed = -1;
        State state = State.OPEN;
        /** Who filled it, and a note ("the elder's call", "after an interview", "withdrawn: no longer wanted"). */
        String by = "", note = "";
        int interview = -1;
        final List<Application> apps = new ArrayList<>();

        @Nullable StationTask task() { return JobMarket.named(trade); }

        boolean open() { return state == State.OPEN || state == State.INTERVIEW; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putString("Employer", employer);
            t.putString("EmployerName", employerName);
            t.putString("Trade", trade);
            t.putInt("Openings", openings);
            t.putInt("MinLevel", minLevel);
            t.putBoolean("Apprentices", apprentices);
            t.putBoolean("Essential", essential);
            t.putString("Hours", hours);
            t.putDouble("M", m);
            t.putInt("Wage", wage);
            t.putLong("Posted", posted);
            t.putLong("Closed", closed);
            t.putString("State", state.name());
            t.putString("By", by);
            t.putString("Note", note);
            t.putInt("Interview", interview);
            ListTag l = new ListTag();
            for (Application a : apps) l.add(a.save());
            t.put("Apps", l);
            return t;
        }

        static Vacancy load(CompoundTag t) {
            Vacancy v = new Vacancy();
            v.id = t.getInt("Id");
            v.employer = t.getString("Employer");
            v.employerName = t.getString("EmployerName");
            v.trade = t.getString("Trade");
            v.openings = Math.max(1, t.getInt("Openings"));
            v.minLevel = t.getInt("MinLevel");
            v.apprentices = t.getBoolean("Apprentices");
            v.essential = t.getBoolean("Essential");
            v.hours = t.getString("Hours");
            v.m = t.contains("M") ? t.getDouble("M") : 1.0;
            v.wage = t.getInt("Wage");
            v.posted = t.getLong("Posted");
            v.closed = t.getLong("Closed");
            try {
                v.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException e) {
                v.state = State.WITHDRAWN;
            }
            v.by = t.getString("By");
            v.note = t.getString("Note");
            v.interview = t.contains("Interview") ? t.getInt("Interview") : -1;
            ListTag l = t.getList("Apps", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) v.apps.add(Application.load(l.getCompound(i)));
            return v;
        }
    }

    /** What became of an application. */
    enum Verdict { WAITING, SHORTLISTED, TAKEN, NOT_TAKEN, WITHDRAWN }

    /** One folk's application to an opening. */
    static final class Application {
        UUID folk;
        String name = "", from = "";
        int level;
        /** The wage it would be paid there, and what it is paid now (0 out of work). */
        int wage, now;
        double score;
        long day;
        Verdict verdict = Verdict.WAITING;
        String why = "";

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Folk", folk);
            t.putString("Name", name);
            t.putString("From", from);
            t.putInt("Level", level);
            t.putInt("Wage", wage);
            t.putInt("Now", now);
            t.putDouble("Score", score);
            t.putLong("Day", day);
            t.putString("Verdict", verdict.name());
            t.putString("Why", why);
            return t;
        }

        static Application load(CompoundTag t) {
            Application a = new Application();
            a.folk = t.getUUID("Folk");
            a.name = t.getString("Name");
            a.from = t.getString("From");
            a.level = t.getInt("Level");
            a.wage = t.getInt("Wage");
            a.now = t.getInt("Now");
            a.score = t.getDouble("Score");
            a.day = t.getLong("Day");
            try {
                a.verdict = Verdict.valueOf(t.getString("Verdict"));
            } catch (IllegalArgumentException e) {
                a.verdict = Verdict.WITHDRAWN;
            }
            a.why = t.getString("Why");
            return a;
        }
    }

    /** A morning's record of the town's labour market. */
    static final class DayRecord {
        long day;
        int employed, selfEmployed, unemployed, openings, hired, quit, laidOff, applied, minimum;
        long relief;
        /** The wage index, coins a day for a journeyman, by trade name; and the multiple, by trade name. */
        final Map<String, Integer> wages = new LinkedHashMap<>();
        final Map<String, Double> index = new LinkedHashMap<>();

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Day", day);
            t.putIntArray("N", new int[]{ employed, selfEmployed, unemployed, openings, hired, quit, laidOff, applied, minimum });
            t.putLong("Relief", relief);
            CompoundTag w = new CompoundTag();
            for (Map.Entry<String, Integer> e : wages.entrySet()) w.putInt(e.getKey(), e.getValue());
            t.put("Wages", w);
            CompoundTag x = new CompoundTag();
            for (Map.Entry<String, Double> e : index.entrySet()) x.putDouble(e.getKey(), e.getValue());
            t.put("Index", x);
            return t;
        }

        static DayRecord load(CompoundTag t) {
            DayRecord d = new DayRecord();
            d.day = t.getLong("Day");
            int[] n = t.getIntArray("N");
            if (n.length >= 9) {
                d.employed = n[0]; d.selfEmployed = n[1]; d.unemployed = n[2]; d.openings = n[3]; d.hired = n[4]; d.quit = n[5];
                d.laidOff = n[6]; d.applied = n[7]; d.minimum = n[8];
            }
            d.relief = t.getLong("Relief");
            CompoundTag w = t.getCompound("Wages");
            for (String k : w.getAllKeys()) d.wages.put(k, w.getInt(k));
            CompoundTag x = t.getCompound("Index");
            for (String k : x.getAllKeys()) d.index.put(k, x.getDouble(k));
            return d;
        }
    }

    // ------------------------------------------------------------------ the world's save

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<UUID, Town> e : towns.entrySet()) {
            Town t = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putUUID("Village", e.getKey());
            c.putInt("Seq", t.seq);
            ListTag cs = new ListTag();
            for (Contract k : t.contracts.values()) cs.add(k.save());
            c.put("Contracts", cs);
            ListTag os = new ListTag();
            for (Offer o : t.offers.values()) os.add(o.save());
            c.put("Offers", os);
            ListTag vs = new ListTag();
            for (Vacancy v : t.vacancies) vs.add(v.save());
            c.put("Vacancies", vs);
            CompoundTag out = new CompoundTag();
            for (Map.Entry<UUID, Long> o : t.outSince.entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putLong("Since", o.getValue());
                one.putString("Why", t.outWhy.getOrDefault(o.getKey(), ""));
                out.put(o.getKey().toString(), one);
            }
            c.put("Out", out);
            CompoundTag fw = new CompoundTag();
            for (Map.Entry<String, Integer> w : t.firmWants.entrySet()) fw.putInt(w.getKey(), w.getValue());
            c.put("FirmWants", fw);
            ListTag ds = new ListTag();
            for (DayRecord d : t.days) ds.add(d.save());
            c.put("Days", ds);
            c.putInt("MinimumWage", t.minimumWage);
            c.putLong("MinimumLooked", t.minimumLooked);
            CompoundTag over = new CompoundTag();
            for (Map.Entry<String, Integer> o : t.overDays.entrySet()) over.putInt(o.getKey(), o.getValue());
            c.put("Over", over);
            c.putLong("LastLayoff", t.lastLayoff);
            CompoundTag fl = new CompoundTag();
            for (Map.Entry<String, Long> o : t.firmLayoff.entrySet()) fl.putLong(o.getKey(), o.getValue());
            c.put("FirmLayoff", fl);
            ListTag news = new ListTag();
            for (String s : t.news) news.add(net.minecraft.nbt.StringTag.valueOf(s));
            c.put("News", news);
            all.add(c);
        }
        tag.put("Towns", all);
        return tag;
    }

    public static LabourBook load(CompoundTag tag, HolderLookup.Provider registries) {
        LabourBook b = new LabourBook();
        ListTag all = tag.getList("Towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < all.size(); i++) {
            CompoundTag c = all.getCompound(i);
            if (!c.hasUUID("Village")) continue;
            Town t = new Town();
            t.seq = c.getInt("Seq");
            ListTag cs = c.getList("Contracts", Tag.TAG_COMPOUND);
            for (int k = 0; k < cs.size(); k++) {
                Contract ct = Contract.load(cs.getCompound(k));
                t.contracts.put(ct.folk, ct);
            }
            ListTag os = c.getList("Offers", Tag.TAG_COMPOUND);
            for (int k = 0; k < os.size(); k++) {
                Offer o = Offer.load(os.getCompound(k));
                t.offers.put(o.key, o);
            }
            ListTag vs = c.getList("Vacancies", Tag.TAG_COMPOUND);
            for (int k = 0; k < vs.size(); k++) t.vacancies.add(Vacancy.load(vs.getCompound(k)));
            CompoundTag out = c.getCompound("Out");
            for (String k : out.getAllKeys()) {
                try {
                    UUID u = UUID.fromString(k);
                    t.outSince.put(u, out.getCompound(k).getLong("Since"));
                    t.outWhy.put(u, out.getCompound(k).getString("Why"));
                } catch (IllegalArgumentException ignored) { }
            }
            CompoundTag fw = c.getCompound("FirmWants");
            for (String k : fw.getAllKeys()) t.firmWants.put(k, fw.getInt(k));
            ListTag ds = c.getList("Days", Tag.TAG_COMPOUND);
            for (int k = 0; k < ds.size(); k++) t.days.add(DayRecord.load(ds.getCompound(k)));
            t.minimumWage = c.getInt("MinimumWage");
            t.minimumLooked = c.contains("MinimumLooked") ? c.getLong("MinimumLooked") : -1;
            CompoundTag over = c.getCompound("Over");
            for (String k : over.getAllKeys()) t.overDays.put(k, over.getInt(k));
            t.lastLayoff = c.contains("LastLayoff") ? c.getLong("LastLayoff") : -10;
            CompoundTag fl = c.getCompound("FirmLayoff");
            for (String k : fl.getAllKeys()) t.firmLayoff.put(k, fl.getLong(k));
            ListTag news = c.getList("News", Tag.TAG_STRING);
            for (int k = 0; k < news.size(); k++) t.news.add(news.getString(k));
            b.towns.put(c.getUUID("Village"), t);
        }
        return b;
    }
}
