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
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerJobs {

    private PlayerJobs() {}

    static final String KEY = "job";
    static final int TASKS_A_DAY = 3, MISSED = 3;

    /** The shift a trade is worked at, or null if a player cannot do that work. */
    @Nullable
    static ShiftRole roleFor(@Nullable AssistantEntity.StationTask t) {
        if (t == null) return null;
        return switch (t) {
            case FARM -> ShiftRole.FARM;
            case WOOD -> ShiftRole.WOOD;
            case RANCH -> ShiftRole.SHEAR;
            case GUARD -> ShiftRole.WATCH;
            case SMELT, SMITH -> ShiftRole.SMELT;
            case STORE, HAUL -> ShiftRole.HAUL;
            case COOK -> ShiftRole.COOK;
            case SHOP -> ShiftRole.COUNTER;
            case HEAL -> ShiftRole.HEALER;
            default -> null;
        };
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
        ShiftRole r = ShiftRole.of(j.getString("role"));
        return (r == null ? "Hand" : r.title) + " at " + j.getString("employerName") + " in " + Villages.name(j.getUUID("town")) + ", "
            + PlayerLife.coins(j.getInt("wage")) + " a day (since day " + j.getLong("since") + ")";
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
        double score = 2.0 + view.title().ordinal() + lv * 1.5 + (Citizens.is(village, p.getUUID()) ? 1.5 : 0);
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
        CompoundTag j = new CompoundTag();
        j.putString("role", role.id);
        j.putUUID("town", village);
        j.putString("employer", vac.employer);
        j.putString("employerName", employerName(vac));
        j.putInt("wage", Math.max(1, vac.wage));
        j.putString("hours", vac.hours);
        j.putLong("since", day);
        j.putLong("lastWorked", day);
        Earnings.data(p).put(KEY, j);
        Shifts.take(p, village, role.id, true);
        Villages.tell(village, day, p.getGameProfile().getName() + " was taken on as " + role.title.toLowerCase(Locale.ROOT) + " at " + employerName(vac));
        return "Interviewed and taken on: " + why + ". You're " + role.title.toLowerCase(Locale.ROOT) + " at " + employerName(vac) + " for "
            + PlayerLife.coins(j.getInt("wage")) + " a day, paid each morning for each day you work. Your shift starts at its hours.";
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
        Earnings.data(p).put(KEY, j);
        return true;
    }

    private static long lastDay = -1;

    /** Payday, once a morning for every player online with a job. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 100 != 37) return;
        ServerLevel level = e.getServer().overworld();
        long day = level.getDayTime() / 24000L;
        if (day == lastDay) return;
        lastDay = day;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) payday(level, p, day);
    }

    /** Yesterday's wage if yesterday was worked; a rise after a good week; let go after three days missed. */
    public static String payday(ServerLevel level, ServerPlayer p, long day) {
        CompoundTag j = job(p);
        if (!j.contains("role") || j.getLong("paidDay") >= day) return "";
        j.putLong("paidDay", day);
        UUID town = j.getUUID("town");
        String said = "";
        boolean worked = j.getLong("lastWorked") >= day - 1 && j.getLong("taskDay") >= day - 1 && j.getInt("tasks") >= TASKS_A_DAY;
        if (worked) {
            int wage = j.getInt("wage");
            int paid = pay(level, p, town, j.getString("employer"), j.getString("employerName"), wage);
            said = paid > 0 ? "Paid " + PlayerLife.coins(paid) + " for yesterday's work at " + j.getString("employerName") + "."
                : j.getString("employerName") + " couldn't pay yesterday's wage.";
            j.putInt("daysPaid", j.getInt("daysPaid") + 1);
            if (j.getInt("daysPaid") % 7 == 0 && j.getInt("starTasks") > 0 && j.getInt("stars") / (double) j.getInt("starTasks") >= 3.5) {
                int rise = Math.max(1, (int) Math.round(wage * 0.1));
                j.putInt("wage", wage + rise);
                said += " A good week's work: a rise of " + PlayerLife.coins(rise) + " a day.";
                j.putInt("stars", 0);
                j.putInt("starTasks", 0);
            }
        } else if (day - j.getLong("lastWorked") > MISSED) {
            Earnings.data(p).remove(KEY);
            Shifts.quit(p, true);
            said = j.getString("employerName") + " has let you go: " + MISSED + " days without you at work.";
            p.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.RED));
            return said;
        }
        j.putInt("tasks", 0);
        Earnings.data(p).put(KEY, j);
        if (!said.isEmpty()) p.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.GOLD));
        return said;
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

    /** The Jobs board: the town's openings, its day shifts, and your job. */
    public static final class Board implements PlayerLife.Page {
        @Override
        public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
            ServerLevel level = p.serverLevel();
            Villages.Village v = PlayerLife.here(p);
            LifePage page = new LifePage("jobs", "The jobs board", v == null ? "No town near" : Villages.name(v.id()) + ": its openings and shifts", p.registryAccess());
            page.heading("Your job");
            if (employed(p)) {
                CompoundTag j = job(p);
                page.row(new ItemStack(Items.CLOCK), line(p), "Hours: " + j.getString("hours") + ". Today " + (j.getLong("taskDay") == level.getDayTime() / 24000L ? j.getInt("tasks") : 0)
                    + " tasks (" + TASKS_A_DAY + " make a day's work); paid each morning.", "").button("Quit", "quit", null);
                page.buttons().button("Start today's shift", "shift", null, !Shifts.onShift(p), "You're on a shift");
            } else {
                page.text("None yet. Apply for an opening below, or take a day's shift.", 4);
            }
            if (v == null) return page;
            page.heading("Openings");
            List<Opening> os = openings(v.id());
            if (os.isEmpty()) page.text(LabourMarket.live(v.id()) ? "No openings posted just now: the board fills in the morning." : "The town has no labour market yet (its market isn't open): its shifts are below.", 4);
            for (Opening o : os) {
                boolean work = o.role() != null && workable(level, v, p, o.role());
                boolean can = work && !employed(p);
                page.row(new ItemStack(o.role() == null ? Items.BARRIER : Items.PAPER), capitalize(JobMarket.noun(o.trade())) + " at " + o.employerName(),
                        PlayerLife.coins(o.wage()) + " a day, " + o.hours() + "; " + o.applied() + " applied" + (o.role() == null ? "; not work a player can do here yet" : ""), "")
                    .button("Apply", "apply", LifePage.args("id", o.id()), can, employed(p) ? "You have a job"
                        : o.role() == null ? "Not work a player can do here yet" : "No such work here yet: it needs " + o.role().needs);
            }
            page.heading("Day shifts (paid by the task)");
            for (Shifts.Offer o : Shifts.offers(level, v, p)) {
                page.row(null, o.title() + " — " + o.role().place + ", " + o.hours(), String.format(Locale.ROOT, "about %.1f a task", o.perTask()) + (o.whyNot().isEmpty() ? "" : "; " + o.whyNot()), "")
                    .button("Take", "take", LifePage.args("r", o.role().id), o.whyNot().isEmpty() && !Shifts.onShift(p), o.whyNot().isEmpty() ? "You're on a shift" : o.whyNot());
            }
            page.buttons().button("The Earnings page", "earnings", null).button("Your life in town", "life", null);
            return page;
        }

        @Override
        public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
            ServerLevel level = p.serverLevel();
            Villages.Village v = PlayerLife.here(p);
            switch (action) {
                case "apply" -> { return v == null ? "!No town here." : apply(level, p, v.id(), args.getInt("id")); }
                case "quit" -> { return quit(p); }
                case "shift" -> {
                    CompoundTag j = job(p);
                    String s = Shifts.take(p, j.getUUID("town"), j.getString("role"), true);
                    return s.isEmpty() ? "Your shift has started." : s;
                }
                case "take" -> {
                    String s = Shifts.take(p, v == null ? null : v.id(), args.getString("r"), false);
                    return s.isEmpty() ? "Your shift has started." : s;
                }
                case "earnings" -> { LifeTown.books(p, "Earnings"); return "#close"; }
                case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
                default -> { return null; }
            }
        }
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

    /** Tests. */
    public static String applyForTests(ServerLevel level, ServerPlayer p, UUID village, int id) {
        return apply(level, p, village, id);
    }

    public static String paydayForTests(ServerLevel level, ServerPlayer p) {
        CompoundTag j = job(p);
        j.putLong("paidDay", -1);
        Earnings.data(p).put(KEY, j);
        return payday(level, p, level.getDayTime() / 24000L);
    }
}
