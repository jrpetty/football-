package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import com.jrpetty.mcassistant.village.EarnMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [player15] A job of the player's own, as a folk has one: applied for on the labour market's board, interviewed for,
 * taken on by a firm or the town, worked at its hours, paid on payday, with rises for good work, and left when they like.
 * <ul>
 * <li><b>The board.</b> The Jobs board (the Your life in town page, any folk's "Any jobs for me?", the city books' Jobs
 *     page) lists the town's openings (LabourMarket): the employer, the trade, the wage a day, its hours, how many applied,
 *     and whether a player can do that work (the trades a shift can be worked at: ShiftRole); and the town's day shifts.</li>
 * <li><b>Applying.</b> One click. The employer interviews you as it would a folk: your standing in the town, your skill at
 *     the work (a shift's skill), whether you are a citizen, against the best folk who applied. Taken on, the opening is
 *     filled; turned down, it says why.</li>
 * <li><b>Working.</b> The job is a standing shift at its trade: it starts every day at its hours while you are in town, the
 *     shift's tasks (and HUD) as any shift, counted toward the town's work. A job's tasks are not paid one by one: the wage
 *     is the pay, on payday, for every day you worked (three tasks or more), from the firm's till or the treasury.</li>
 * <li><b>Rises and leaving.</b> A week of good work (three and a half stars and more) brings a rise of a tenth. Three days
 *     missed and you are let go. Quit at any time.</li>
 * </ul>
 * Kept with the player's own data (Earnings' tag), so it outlasts a restart.
 */
public final class PlayerJobs {

    private PlayerJobs() {}

    static final String KEY = "job";
    static final int TASKS_A_DAY = 3, MISSED = 3;

    /** The shift a trade is worked at, or null if a player cannot do that work. */
    @Nullable
    static ShiftRole roleFor(@Nullable AssistantEntity.StationTask t) {
        return t == null ? null : JobCards.shiftFor(t.name());               // [wpb] every trade has its card and its shift
    }

    /** [wpb] The trade the player's job is at, if it is worked at this shift ("" else: a day's shift off the board). */
    static String tradeOf(Player p, ShiftRole r) {
        CompoundTag j = job(p);
        return j.getString("role").equals(r.id) ? j.getString("trade") : "";
    }

    /** The player's job, or an empty tag. */
    static CompoundTag job(Player p) {
        return Earnings.data(p).getCompound(KEY);
    }

    public static boolean employed(Player p) {
        return job(p).contains("role");
    }

    /** "Counter hand at Wren's Store, 6 a day (since day 12)", or "". */
    public static String line(Player p) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "";
        boolean comm = "commission".equals(j.getString("pay"));             // [wpb] the rank, and the way of pay
        return WorkLife.rankTitle(j) + " at " + j.getString("employerName") + " in " + Villages.name(j.getUUID("town")) + ", "
            + (comm ? "on commission (" + j.getString("rateWords") + ")" : PlayerLife.coins(j.getInt("wage")) + " a day") + " (since day " + j.getLong("since") + ")";
    }

    /** An opening a player might apply for. */
    record Opening(int id, String employer, String employerName, String trade, int wage, String hours, int applied, @Nullable ShiftRole role) {}

    static List<Opening> openings(UUID village) {
        List<Opening> out = new ArrayList<>();
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw == null) return out;
        for (LabourBook.Vacancy v : tw.vacancies) {
            if (!v.open() || v.openings <= 0) continue;
            int waiting = 0;
            for (LabourBook.Application a : v.apps) if (a.verdict == LabourBook.Verdict.WAITING || a.verdict == LabourBook.Verdict.SHORTLISTED) waiting++;
            out.add(new Opening(v.id, v.employer, v.employerName.isEmpty() ? v.employer : v.employerName, v.trade, v.wage, v.hours, waiting, roleFor(v.task())));
        }
        return out;
    }

    /**
     * Apply for an opening: interviewed now, as the employer would interview a folk. Returns what was said ("!" refused).
     */
    static String apply(ServerLevel level, ServerPlayer p, UUID village, int id) {
        return apply(level, p, village, id, false, 0);
    }

    /** [wpb] As above, on the pay chosen, with the interview's own marks (0 for none: the old way, one click). */
    static String apply(ServerLevel level, ServerPlayer p, UUID village, int id, boolean commission, double interview) {
        if (employed(p)) return "!You have a job already (" + line(p) + "): quit it first.";
        LabourBook.Town tw = LabourBook.peek(village);
        LabourBook.Vacancy vac = null;
        if (tw != null) for (LabourBook.Vacancy v : tw.vacancies) if (v.id == id && v.open() && v.openings > 0) vac = v;
        if (vac == null) return "!That opening has been filled or withdrawn.";
        ShiftRole role = roleFor(vac.task());
        if (role == null) return "!That's work a player can't take on here yet (" + JobMarket.noun(vac.trade) + "): try a shift instead.";
        Villages.Village town = Villages.get(village);
        if (town != null && !workable(level, town, p, role)) {
            return "!" + Villages.name(village) + " has no " + role.title.toLowerCase(Locale.ROOT) + " work a player can do yet: it needs " + role.needs + ".";
        }
        Standing.View view = Standing.of(village, p.getUUID(), level.getGameTime());
        long day = level.getDayTime() / 24000L;
        if (Laws.banished(village, p.getUUID(), day) || !view.title().atLeast(Standing.Title.VISITOR)) {
            return "!" + employerName(vac) + " won't take on somebody the town doesn't know: help the folk, trade with them, and come back.";
        }
        // The interview: what the employer weighs, against the best folk who applied.
        int lv = EarnMath.level(Shifts.xp(p, role));
        double score = 2.0 + view.title().ordinal() + lv * 1.5 + (Citizens.is(village, p.getUUID()) ? 1.5 : 0) + interview;
        double best = 0;
        String bestName = "";
        for (LabourBook.Application a : vac.apps) {
            if (a.verdict != LabourBook.Verdict.WAITING && a.verdict != LabourBook.Verdict.SHORTLISTED) continue;
            double s = 3.0 + a.level * 0.6;
            if (s > best) { best = s; bestName = a.name; }
        }
        String why = "your standing (" + view.title().words + "), your skill as " + role.title.toLowerCase(Locale.ROOT) + " ("
            + EarnMath.LEVELS[lv] + ")" + (Citizens.is(village, p.getUUID()) ? ", a citizen" : "");
        if (best > score) {
            return "!" + employerName(vac) + " interviewed you, and took " + bestName + " instead: " + bestName + " knows the trade better. "
                + "Work a few shifts as " + role.title.toLowerCase(Locale.ROOT) + " to learn it, and try again.";
        }
        vac.openings--;
        vac.by = "taken: " + p.getGameProfile().getName() + ", a player";
        vac.note = "after an interview";
        if (vac.openings <= 0) {
            vac.state = LabourBook.State.FILLED;
            vac.closed = day;
        }
        LabourBook.changed();
        JobCards.Card card = JobCards.of(vac.trade);                         // [wpb] the job written with its card and its terms
        if (card == null) card = JobCards.forRole(role);
        WorkLife.hire(level, p, village, vac.employer, employerName(vac), card, Math.max(1, vac.wage), vac.hours, commission, false);
        CompoundTag j = job(p);
        Villages.tell(village, day, p.getGameProfile().getName() + " was taken on as " + role.title.toLowerCase(Locale.ROOT) + " at " + employerName(vac));
        return "Interviewed and taken on: " + why + ". You're " + role.title.toLowerCase(Locale.ROOT) + " at " + employerName(vac) + " for "
            + PlayerLife.coins(j.getInt("wage")) + " a day, paid each morning for each day you work. Your shift starts at its hours.";
    }

    /**
     * [wpb] The employer's weighing, as in {@link #apply}, with the interview's marks: null if the player is the one it
     * takes, else why not.
     */
    @Nullable
    static String judge(ServerLevel level, ServerPlayer p, UUID village, LabourBook.Vacancy vac, double interview) {
        ShiftRole role = roleFor(vac.task());
        if (role == null) role = JobCards.shiftFor(vac.trade);
        if (role == null) return "it isn't work a player can take on";
        Standing.View view = Standing.of(village, p.getUUID(), level.getGameTime());
        int lv = EarnMath.level(Shifts.xp(p, role));
        double score = 2.0 + view.title().ordinal() + lv * 1.5 + (Citizens.is(village, p.getUUID()) ? 1.5 : 0) + interview;
        double best = 0;
        String bestName = "";
        for (LabourBook.Application a : vac.apps) {
            if (a.verdict != LabourBook.Verdict.WAITING && a.verdict != LabourBook.Verdict.SHORTLISTED) continue;
            double s = 3.0 + a.level * 0.6;
            if (s > best) { best = s; bestName = a.name; }
        }
        if (best > score) return "they took " + bestName + ", who knows the trade better. Work a few day shifts at it, and apply again";
        return null;
    }

    /** [wpb] The opening filled by the player (its count down, closed when full, who took it written). */
    static void fill(ServerLevel level, ServerPlayer p, LabourBook.Vacancy vac) {
        long day = level.getDayTime() / 24000L;
        vac.openings--;
        vac.by = "taken: " + p.getGameProfile().getName() + ", a player";
        vac.note = "after an interview";
        if (vac.openings <= 0) {
            vac.state = LabourBook.State.FILLED;
            vac.closed = day;
        }
        LabourBook.changed();
    }

    /** Can this work be done here as a shift (the town has its place: grown trees for the woods, a pen for shearing...)? */
    /** Tests: any work counts as workable here (a test town has no ripe fields). */
    public static volatile boolean ANY_WORK_FOR_TESTS = false;

    static boolean workable(ServerLevel level, Villages.Village v, Player p, ShiftRole role) {
        if (ANY_WORK_FOR_TESTS) return true;
        for (Shifts.Offer o : Shifts.offers(level, v, p)) if (o.role() == role) return true;
        return false;
    }

    static String employerName(LabourBook.Vacancy v) {
        return v.employerName.isEmpty() ? v.employer : v.employerName;
    }

    /** Leave the job (and its standing shift). */
    static String quit(ServerPlayer p) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "!You have no job.";
        String was = line(p);
        Earnings.data(p).remove(KEY);
        Shifts.quit(p, true);
        Villages.tell(j.getUUID("town"), p.level().getDayTime() / 24000L, p.getGameProfile().getName() + " left their work at " + j.getString("employerName"));
        return "You left: " + was + ".";
    }

    /** From Shifts: a task done on a job's shift is counted toward the day's wage, not paid by itself. True if it is. */
    public static boolean onTheBooks(Player p, ShiftRole r, int stars) {
        CompoundTag j = job(p);
        if (!j.contains("role") || !j.getString("role").equals(r.id)) return false;
        long day = p.level().getDayTime() / 24000L;
        if (j.getLong("taskDay") != day) {
            j.putLong("taskDay", day);
            j.putInt("tasks", 0);
        }
        j.putInt("tasks", j.getInt("tasks") + 1);
        j.putInt("stars", j.getInt("stars") + stars);
        j.putInt("starTasks", j.getInt("starTasks") + 1);
        if (j.getInt("tasks") >= TASKS_A_DAY) j.putLong("lastWorked", day);
        CompoundTag rec = WorkLife.day(j, day);                             // [wpb] the boss's tally (a shift's task adds its units in WorkLife.taskDone)
        if (Shifts.of(p) == null) { rec.putDouble("u", rec.getDouble("u") + 1); rec.putInt("n", rec.getInt("n") + 1); rec.putInt("s", rec.getInt("s") + stars); rec.putBoolean("o", true); }
        Earnings.data(p).put(KEY, j);
        return true;
    }

    /** [wpb] Payday and the boss's review are WorkLife's now (held pay, offline never absence, warnings before dismissal). */
    public static String payday(ServerLevel level, ServerPlayer p, long day) {
        return WorkLife.morning(level, p, day);
    }

    /** The wage out of the employer's purse: a firm's till, else the treasury. Returns coins paid. */
    static int pay(ServerLevel level, ServerPlayer p, UUID town, String employer, String employerName, int wage) {
        if (employer.startsWith("firm:")) {
            Business b = Businesses.of(town, employer.substring(5));
            if (b == null) return 0;
            long paid = Businesses.payOut(level, town, b, wage * 100L, level.getDayTime() / 24000L);
            int coins = (int) (paid / 100);
            if (coins <= 0) return 0;
            Business.Day bd = b.today(level.getDayTime() / 24000L);
            bd.wages += coins * 100L;
            b.allTime.wages += coins * 100L;
            Businesses.dirty();
            Dealings.giveCoins(p, coins);
            CoinCensus.playerOut(town, coins, p, PlayerFinance.Cat.WAGES, "wages from " + employerName);   // [join21]
            Earnings.record(p, town, Earnings.Source.SHIFT, coins, employerName, "in wages");
            return coins;
        }
        return Earnings.fromTreasury(p, town, wage, Earnings.Source.SHIFT, employerName, "in wages");
    }

    // ------------------------------------------------------------------ the board

    /** The Jobs board: [wpb] the adverts, your job, your applications, your week and the hand-in (JobAdverts). */
    public static final class Board extends JobAdverts {
    }

    static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Tests: an opening posted on the board now. Returns its number. */
    public static int openingForTests(UUID village, String employer, String employerName, String trade, int wage, long day) {
        LabourBook.Town tw = LabourBook.town(village);
        LabourBook.Vacancy v = new LabourBook.Vacancy();
        int id = 1;
        for (LabourBook.Vacancy x : tw.vacancies) id = Math.max(id, x.id + 1);
        v.id = id;
        v.employer = employer;
        v.employerName = employerName;
        v.trade = trade;
        v.wage = wage;
        v.posted = day;
        tw.vacancies.add(v);
        LabourBook.changed();
        return id;
    }

    /** [wpb] Tests: a folk applicant on an opening (its level), as the labour market would add one. */
    public static void rivalForTests(UUID village, int id, VillageFolkEntity f, int level, long day) {
        LabourBook.Town tw = LabourBook.town(village);
        for (LabourBook.Vacancy v : tw.vacancies) {
            if (v.id != id) continue;
            LabourBook.Application a = new LabourBook.Application();
            a.folk = f.getUUID();
            a.name = f.displayNameCap();
            a.level = level;
            a.day = day;
            v.apps.add(a);
        }
        LabourBook.changed();
    }

    /** [wpb] Tests: the opening's state and who took it. */
    public static String vacancyForTests(UUID village, int id) {
        LabourBook.Town tw = LabourBook.peek(village);
        if (tw != null) for (LabourBook.Vacancy v : tw.vacancies) if (v.id == id) return v.state + "|" + v.by;
        return "";
    }

    /** Tests. */
    public static String applyForTests(ServerLevel level, ServerPlayer p, UUID village, int id) {
        return apply(level, p, village, id);
    }

    public static String paydayForTests(ServerLevel level, ServerPlayer p) {
        return WorkLife.morningForTests(level, p, level.getDayTime() / 24000L);
    }
}
