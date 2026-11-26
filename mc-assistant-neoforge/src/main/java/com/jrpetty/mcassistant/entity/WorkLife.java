package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.EarnPayload;
import com.jrpetty.mcassistant.village.EarnMath;
import com.jrpetty.mcassistant.village.ServiceHours;
import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * [wpb] A player's job day to day, and the boss who judges it.
 * <ul>
 * <li><b>The day's record.</b> Each day the job keeps what was done (output in the card's units, tasks, their stars), the
 *     target it was judged against (the card's, scaled for skill and rank, and lowered for hours with no work and for rain
 *     outdoors: WorkMath.scaled), the day's stars, whether the worker was in town during the hours (offline is never
 *     absence), leave, and whether it is paid.</li>
 * <li><b>Pay.</b> Salaried: the wage each morning for each day worked, including days held while the worker was away
 *     (paid on login). Commissioned: produce at the hand-in (only marked output counts: Provenance), the rest as each task
 *     is done, with a small base for service work. The rate is recomputed each morning from the economy (PayTerms).</li>
 * <li><b>The boss.</b> A real folk (the firm's owner, the town's head of the trade) or the player who hired you. After each
 *     shift it says a word: praise, what to improve, or that nothing was your fault. Each morning it reviews the week.
 *     Salaried: only a pattern (four poor or missed days in seven) brings a talk, then a warning, then dismissal with a
 *     reason and a day's pay in lieu; a good week lifts a stage. Commissioned: no firing for output, only for misconduct
 *     (theft, walking off a shift, days missed in town without leave): a warning, then dismissal. A good run (four days in
 *     seven at three and a half stars or more, the skill for it) brings promotion: hand, senior, head, a sixth more pay a
 *     rank and a tenth on the target.</li>
 * <li><b>Notice, leave and switching.</b> Quitting gives a day's notice (or leave at once, unpaid for the day). Leave can
 *     be booked a day or three ahead. A switch between salary and commission is asked for and takes effect at the next
 *     morning's review. Fourteen real days away and the post is filled, politely, with no black mark.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WorkLife {

    private WorkLife() {}

    static final int KEEP_DAYS = 14, POOR_FOR_TALK = 4, GOOD_FOR_PROMOTION = 4, STAGE_GAP = 2, PROMOTE_GAP = 5, MOST_LEAVE = 6;
    static final double PROMOTE_STARS = 3.6, RANK_PAY = 1.0 / 6;
    /** Real days away before the post is filled. */
    static final long LONG_ABSENCE_MS = 14L * 24 * 3600 * 1000;
    public static volatile long LONG_ABSENCE_FOR_TESTS = -1;

    // ------------------------------------------------------------------ the job, as kept

    static CompoundTag job(Player p) {
        return PlayerJobs.job(p);
    }

    static void save(Player p, CompoundTag j) {
        Earnings.data(p).put(PlayerJobs.KEY, j);
    }

    /** Is the player on commission at the shift they are working now (their job's own)? */
    public static boolean commissioned(Player p) {
        CompoundTag j = job(p);
        if (!"commission".equals(j.getString("pay"))) return false;
        Shifts.Shift s = Shifts.of(p);
        return s == null || s.role.id.equals(j.getString("role"));
    }

    /** Whose work it is: "firm:ent-3", "town:FARM", "farm:pf-1"... ("" with no job). */
    public static String employerKey(Player p) {
        return job(p).getString("employer");
    }

    @Nullable
    static JobCards.Card card(CompoundTag j) {
        JobCards.Card c = JobCards.of(j.getString("trade"));
        if (c != null) return c;
        ShiftRole r = ShiftRole.of(j.getString("role"));
        return r == null ? null : JobCards.forRole(r);
    }

    static long today(Player p) {
        return p.level().getDayTime() / 24000L;
    }

    /** The day's record, made if need be; the last fourteen kept. */
    static CompoundTag day(CompoundTag j, long day) {
        ListTag l = j.getList("days", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getLong("d") == day) return l.getCompound(i);
        CompoundTag r = new CompoundTag();
        r.putLong("d", day);
        l.add(r);
        while (l.size() > KEEP_DAYS) l.remove(0);
        j.put("days", l);
        return r;
    }

    /** The days kept, oldest first. */
    static List<CompoundTag> days(CompoundTag j) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = j.getList("days", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    static boolean onLeave(CompoundTag j, long day) {
        return j.contains("leaveFrom") && day >= j.getLong("leaveFrom") && day <= j.getLong("leaveTo");
    }

    static int rank(CompoundTag j) {
        return Math.max(0, Math.min(2, j.getInt("rank")));
    }

    /** "Farm hand", "Farm foreman", "Head of the fields". */
    public static String rankTitle(CompoundTag j) {
        JobCards.Card c = card(j);
        if (c == null) return "Hand";
        return switch (rank(j)) { case 1 -> c.senior(); case 2 -> c.head(); default -> c.title(); };
    }

    /** Today's target for this worker, before the day's conditions. */
    static int baseTarget(Player p, CompoundTag j) {
        JobCards.Card c = card(j);
        if (c == null) return 1;
        return WorkMath.scaled(c.target(), Shifts.level(p, c.shift()), rank(j), 0, false);
    }

    // ------------------------------------------------------------------ the boss

    /** Who the worker answers to: the firm's owner, the town's head of the trade, the hiring player; else "The foreman". */
    static String boss(@Nullable ServerLevel level, CompoundTag j) {
        String e = j.getString("employer");
        UUID town = j.hasUUID("town") ? j.getUUID("town") : null;
        if (town == null) return "The foreman";
        if (e.startsWith("firm:")) {
            Business b = Businesses.of(town, e.substring(5));
            if (b != null) {
                VillageFolkEntity o = Temperament.owner(town, b);
                if (o != null) return o.displayNameCap();
                for (Business.Stake s : b.owners) if (s.holder() == Business.Holder.PLAYER || s.holder() == Business.Holder.FOLK) return s.name();
            }
        }
        if (e.startsWith("farm:")) {
            PlayerFarms.Farm f = PlayerFarms.byId(e.substring(5));
            if (f != null) return f.ownerName;
        }
        if (e.startsWith("player:")) return j.getString("employerName");
        ShiftRole r = ShiftRole.of(j.getString("role"));
        if (r != null) {
            for (AssistantEntity a : Villages.folkOf(town)) {
                if (a instanceof VillageFolkEntity f && r.bosses.contains(f.stationTask())) return f.displayNameCap();
            }
        }
        return "The foreman";
    }

    static final String[] PRAISE = { "Good shift. Same again tomorrow.", "That's the way. Keep it up.", "Can't fault that.",
        "You'll do. Well done.", "Best shift I've seen this week.", "Fine work. I noticed." };
    static final String[] FAIR = { "That'll do.", "Steady. That's what I want.", "No complaints.", "Right on the mark.",
        "A fair day's work.", "Nothing wrong with that." };
    static final String[] IMPROVE = { "Bit slow today.", "Short of the mark today.", "We'll call that a quiet one.",
        "Not your best.", "Room to improve.", "Tomorrow, a bit more." };
    static final String[] EXCUSED = { "Nothing to be had today. Not your doing.", "Quiet day. Can't blame you for that.",
        "No work came in. Don't worry about it.", "The work wasn't there, not you.", "Slow day for everyone.",
        "Nothing to do but wait. Fair enough." };
    static final String[] TALK = { "A word. Your days have been short of the mark this week. Anything wrong?",
        "Can I have a minute? The numbers are down. Let's get them back up.", "I need more from you. Aim for {target} a shift.",
        "We've had a few poor days. I'm not warning you yet, just saying.", "This week's been thin. Let's see it pick up.",
        "Is the work suiting you? It's been slow." };
    static final String[] WARNING = { "This is a warning: {reason}. Next time, I'll have to let you go.",
        "Formal warning: {reason}. It's written down.", "I'm warning you now: {reason}.", "One warning, and only one: {reason}.",
        "Consider this your warning: {reason}.", "I have to warn you: {reason}. Turn it round." };
    static final String[] DISMISS = { "I'm letting you go: {reason}. You'll have a day's pay.", "That's it, I'm afraid: {reason}.",
        "We're parting ways: {reason}.", "I can't keep you on: {reason}.", "Today's your last day: {reason}.",
        "I've had to give the job to someone else: {reason}." };
    static final String[] PROMOTE = { "You've earned it: you're {rank} from today.", "Promotion: {rank}, with the pay that goes with it.",
        "From tomorrow you're {rank}. Don't let it go to your head.", "I'm making you {rank}. Well deserved.",
        "{rank} it is. Keep this up.", "You run the floor now: {rank}." };

    static String pick(String[] lines, long seed) {
        return lines[(int) Math.floorMod(seed * 31 + 7, lines.length)];
    }

    /** What the boss said, kept on the job (the last six) and told. */
    static void say(ServerPlayer p, CompoundTag j, String line, ChatFormatting colour) {
        String who = boss(p.serverLevel(), j);
        String said = who + ": \"" + line + "\"";
        ListTag l = j.getList("said", Tag.TAG_STRING);
        l.add(0, StringTag.valueOf("Day " + today(p) + ". " + said));
        while (l.size() > 6) l.remove(l.size() - 1);
        j.put("said", l);
        p.sendSystemMessage(Component.literal(said).withStyle(colour));
        toast(p, said);
    }

    static void toast(ServerPlayer p, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        t.putInt("coins", 0);
        Earnings.send(p, EarnPayload.TOAST, t);
    }

    static String hint(@Nullable JobCards.Card c) {
        if (c == null) return "Follow the arrow straight to each task.";
        return switch (c.shift()) {
            case FARM -> "Go straight from one marked plot to the next, and plant before you move on.";
            case MINE -> "Cut the marked blocks and nothing else; ore's worth the most.";
            case FISH -> "Stay at the marked water; a catch away from it doesn't count.";
            case CRAFT, SMELT -> "Bring the whole batch in at once; makings kept back are mistakes.";
            case COUNTER -> "Serve the one first in line, and quickly.";
            case HAUL -> "Load up and go straight to the marked chest.";
            case WATCH -> "Walk the posts in turn without stopping.";
            default -> "Follow the arrow straight to each task, and mind the card.";
        };
    }

    // ------------------------------------------------------------------ during a shift

    /** A task done on a shift: counted toward the day, and on commission (not produce) paid at once. */
    static void taskDone(ServerLevel level, ServerPlayer p, Shifts.Shift s, Shifts.Task t, int stars, double units, String what) {
        CompoundTag j = job(p);
        if (!j.contains("role") || !j.getString("role").equals(s.role.id)) return;
        long d = today(p);
        CompoundTag r = day(j, d);
        r.putDouble("u", r.getDouble("u") + Math.max(0, units));
        r.putInt("n", r.getInt("n") + 1);
        r.putInt("s", r.getInt("s") + stars);
        r.putBoolean("o", true);
        j.putLong("lastWorked", d);
        if ("commission".equals(j.getString("pay")) && !s.role.equals(ShiftRole.FARM) && !produce(s.role)) {
            double owed = j.getDouble("rate") * Math.max(0, units) + j.getDouble("carry");
            int coins = (int) Math.floor(owed + 1e-9);
            int paid = coins <= 0 ? 0 : payFrom(level, p, j, coins, "commission for " + what);
            j.putDouble("carry", Math.min(5, owed - paid));
            r.putInt("c", r.getInt("c") + paid);
            j.putInt("earned", j.getInt("earned") + paid);
        }
        save(p, j);
        WorkWay.refresh(p);
    }

    static boolean produce(ShiftRole r) {
        JobCards.Measure m = JobCards.MEASURES.get(r);
        return m != null && m.produce();
    }

    static void shiftStarted(ServerLevel level, ServerPlayer p, Shifts.Shift s) {
        CompoundTag j = job(p);
        if (j.contains("role") && j.getString("role").equals(s.role.id)) {
            JobCards.Card c = card(j);
            int target = baseTarget(p, j);
            String pay = "commission".equals(j.getString("pay")) ? "on commission" : "on salary";
            toast(p, "Your shift at " + j.getString("employerName") + " starts now. Aim: " + (c == null ? target + "" : c.targetLine(target)) + ", " + pay + ".");
            CompoundTag r = day(j, today(p));
            r.putBoolean("o", true);
            save(p, j);
            Planner.add(p, today(p), (int) ((level.getDayTime() % 24000L) / 1000L + 6) % 24, "shift", rankTitle(j) + " at " + j.getString("employerName"), s.post);
        }
        WorkWay.refresh(p);
    }

    /** The shift over: the day's target set (its conditions), the day's stars, the boss's word. */
    static void shiftEnded(ServerLevel level, ServerPlayer p, Shifts.Shift s, String why) {
        CompoundTag j = job(p);
        WorkShifts.shiftOver(p, s);                                         // the lent rod back
        WorkWay.clearTask(p);
        if (!j.contains("role") || !j.getString("role").equals(s.role.id)) return;
        long d = today(p);
        CompoundTag r = day(j, d);
        long length = Math.max(1, level.getGameTime() - s.started);
        r.putLong("len", r.getLong("len") + length);
        r.putLong("nw", r.getLong("nw") + s.noWork);
        JobCards.Card c = card(j);
        boolean rain = c != null && c.measure().outdoors() && level.isRaining();
        if (rain) r.putBoolean("r", true);
        double noWork = Math.min(1, r.getLong("nw") / (double) Math.max(r.getLong("len"), WorkMath.SHIFT / 2));
        int target = c == null ? 1 : WorkMath.scaled(c.target(), Shifts.level(p, c.shift()), rank(j), noWork, r.getBoolean("r"));
        r.putInt("t", target);
        int stars = WorkMath.dayStars(r.getDouble("u"), target);
        r.putInt("st", stars);
        if (why.startsWith("You left your post")) misconduct(level, p, "walking off a shift", false);
        else {
            long seed = d * 7 + p.getUUID().hashCode();
            if (target == 0) say(p, j, pick(EXCUSED, seed), ChatFormatting.GRAY);
            else if (stars >= 4) say(p, j, pick(PRAISE, seed), ChatFormatting.GREEN);
            else if (stars == 3) say(p, j, pick(FAIR, seed), ChatFormatting.GRAY);
            else say(p, j, pick(IMPROVE, seed) + " " + hint(c), ChatFormatting.YELLOW);
        }
        save(p, j);
    }

    /** Misconduct: a warning, then dismissal (theft from the employer outright). */
    static void misconduct(ServerLevel level, ServerPlayer p, String what, boolean fromEmployer) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return;
        int strikes = j.getInt("strikes") + 1;
        j.putInt("strikes", strikes);
        if (fromEmployer || strikes >= 2) {
            save(p, j);
            dismiss(level, p, what, true);
            return;
        }
        j.putInt("stage", Math.max(j.getInt("stage"), 2));
        j.putLong("stageDay", today(p));
        say(p, j, pick(WARNING, today(p) + strikes).replace("{reason}", what), ChatFormatting.RED);
        save(p, j);
    }

    /** Let go: the reason said, a day's pay in lieu (not for theft), the post reopened, the standing shift ended. */
    static String dismiss(ServerLevel level, ServerPlayer p, String reason, boolean misconduct) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "";
        say(p, j, pick(DISMISS, today(p)).replace("{reason}", reason), ChatFormatting.RED);
        int lieu = 0;
        if (!misconduct && !"commission".equals(j.getString("pay"))) lieu = payFrom(level, p, j, j.getInt("wage"), "a day's pay in lieu of notice");
        UUID town = j.getUUID("town");
        CompoundTag past = new CompoundTag();
        past.putString("line", rankTitle(j) + " at " + j.getString("employerName") + ", let go day " + today(p) + ": " + reason);
        ListTag hist = Earnings.data(p).getList("jobHistory", Tag.TAG_COMPOUND);
        hist.add(0, past);
        while (hist.size() > 8) hist.remove(hist.size() - 1);
        Earnings.data(p).put("jobHistory", hist);
        Earnings.data(p).remove(PlayerJobs.KEY);
        Shifts.quit(p, true);
        Villages.tell(town, today(p), p.getGameProfile().getName() + " was let go from " + j.getString("employerName"));
        WorkWay.refresh(p);
        return "Let go: " + reason + (lieu > 0 ? ". Paid " + lieu + "c in lieu of notice." : ".");
    }

    // ------------------------------------------------------------------ paying

    /** Coin out of the employer's purse: a firm's (or a farm's) till, a player employer's escrow, else the treasury. */
    static int payFrom(ServerLevel level, ServerPlayer p, CompoundTag j, int coins, String what) {
        if (coins <= 0) return 0;
        UUID town = j.getUUID("town");
        String e = j.getString("employer");
        String name = j.getString("employerName");
        if (e.startsWith("firm:") || e.startsWith("farm:")) {
            String id = e.startsWith("farm:") ? PlayerFarms.businessId(e.substring(5)) : e.substring(5);
            Business b = id == null ? null : Businesses.of(town, id);
            if (b == null) return 0;
            long paid = Businesses.payOut(level, town, b, coins * 100L, Businesses.today(level));
            int got = (int) (paid / 100);
            if (got <= 0) return 0;
            Business.Day bd = b.today(Businesses.today(level));
            bd.wages += got * 100L;
            b.allTime.wages += got * 100L;
            bd.staffNames.put(p.getUUID(), p.getGameProfile().getName());
            bd.staffPay.merge(p.getUUID(), got * 100L, Long::sum);
            Businesses.dirty();
            Earnings.giveCoins(p, got);
            CoinCensus.playerOut(town, got, p, PlayerFinance.Cat.WAGES, what + " from " + name);
            Earnings.record(p, town, Earnings.Source.SHIFT, got, name, what);
            return got;
        }
        if (e.startsWith("player:")) return PlayerHiring.pay(level, p, j, coins, what);
        return Earnings.fromTreasury(p, town, coins, Earnings.Source.SHIFT, name, what);
    }

    /** Was this day worked (output, or turned up on a day with nothing to do)? */
    static boolean worked(CompoundTag r) {
        return r.getDouble("u") > 0 || r.getInt("n") > 0 || r.getBoolean("o") && r.contains("t") && r.getInt("t") == 0;
    }

    /**
     * The morning: every day worked and not yet paid (days held while away included), then the boss's review, the notice
     * served, a switch of pay made, the commission rate brought up to date. What it says ("" for nothing).
     */
    public static String morning(ServerLevel level, ServerPlayer p, long today) {
        CompoundTag j = job(p);
        if (!j.contains("role") || j.getLong("reviewed") >= today) return "";
        j.putLong("reviewed", today);
        List<String> said = new ArrayList<>();
        boolean commission = "commission".equals(j.getString("pay"));
        int paidDays = 0, paid = 0;
        for (CompoundTag r : days(j)) {
            if (r.getLong("d") >= today || r.getBoolean("p")) continue;
            r.putBoolean("p", true);
            if (!worked(r)) continue;
            int due = commission ? j.getInt("base") : (int) Math.round(j.getInt("wage") * (1 + RANK_PAY * rank(j)));
            if (due > 0) {
                int got = payFrom(level, p, j, due, commission ? "base pay for day " + r.getLong("d") : "wages for day " + r.getLong("d"));
                r.putInt("c", r.getInt("c") + got);
                paid += got;
            }
            paidDays++;
        }
        save(p, j);
        if (paidDays > 0 && paid > 0) said.add("Paid " + paid + "c for " + (paidDays == 1 ? "a day" : paidDays + " days") + " at " + j.getString("employerName") + ".");
        else if (paidDays > 0 && !commission) said.add(j.getString("employerName") + " couldn't pay you this morning: it's owed.");
        if (!job(p).contains("role")) return String.join(" ", said);
        String rev = review(level, p, today);
        if (!rev.isEmpty()) said.add(rev);
        j = job(p);
        if (!j.contains("role")) return String.join(" ", said);
        if (j.getLong("leaving") > 0 && today >= j.getLong("leaving")) {
            said.add(leaveNow(level, p, "Your notice is served."));
            return String.join(" ", said);
        }
        if (!j.getString("switchTo").isEmpty()) {
            boolean toComm = "commission".equals(j.getString("switchTo"));
            j.putString("pay", toComm ? "commission" : "salary");
            j.remove("switchTo");
            save(p, j);
            said.add("From today you're " + (toComm ? "on commission" : "on salary") + ", as agreed at your review.");
        }
        if ("commission".equals(job(p).getString("pay"))) rerate(level, p);
        return String.join(" ", said);
    }

    /** The commission rate recomputed from today's prices. */
    static void rerate(ServerLevel level, ServerPlayer p) {
        CompoundTag j = job(p);
        JobCards.Card c = card(j);
        if (c == null || !j.hasUUID("town")) return;
        PayTerms.Terms t = PayTerms.commission(level, j.getUUID("town"), c, j.getInt("going") > 0 ? j.getInt("going") : j.getInt("wage"),
            baseTarget(p, j), null, j.getString("employerName"));
        j.putDouble("rate", t.rate());
        j.putDouble("each", t.each());
        j.putInt("base", t.base());
        j.putString("rateWords", t.rateWords());
        j.putString("rateWhy", t.why());
        save(p, j);
    }

    /** The boss's look at the week. What it says. */
    static String review(ServerLevel level, ServerPlayer p, long today) {
        CompoundTag j = job(p);
        boolean commission = "commission".equals(j.getString("pay"));
        int poor = 0, workedDays = 0, missedInTown = 0;
        double starSum = 0;
        for (CompoundTag r : days(j)) {
            long d = r.getLong("d");
            if (d >= today || d < today - 7 || d < j.getLong("since")) continue;
            boolean leave = onLeave(j, d);
            if (worked(r)) {
                workedDays++;
                starSum += r.getInt("st");
                if (r.contains("t") && r.getInt("t") > 0 && r.getInt("st") <= 1) poor++;
            } else if (r.getBoolean("o") && !leave) {
                missedInTown++;
                poor++;
            }
        }
        double avg = workedDays == 0 ? 0 : starSum / workedDays;
        long seed = today + p.getUUID().getMostSignificantBits();
        int stage = j.getInt("stage");
        JobCards.Card c = card(j);
        if (commission) {
            if (missedInTown >= 2 && today - j.getLong("stageDay") >= STAGE_GAP) {
                j.putLong("stageDay", today);
                save(p, j);
                misconduct(level, p, missedInTown + " days in town without turning up or booking leave", false);
                return "";
            }
        } else if (poor >= POOR_FOR_TALK && today - j.getLong("stageDay") >= STAGE_GAP) {
            stage++;
            j.putInt("stage", stage);
            j.putLong("stageDay", today);
            String reason = poor + " poor or missed days in the last week (aim for " + (c == null ? "the target" : c.targetLine(baseTarget(p, j))) + ")";
            save(p, j);
            if (stage == 1) {
                say(p, j, pick(TALK, seed).replace("{target}", c == null ? "the target" : c.targetLine(baseTarget(p, j))), ChatFormatting.YELLOW);
                save(p, j);
                return "Your boss had a word about the last week.";
            }
            if (stage == 2) {
                say(p, j, pick(WARNING, seed).replace("{reason}", reason), ChatFormatting.RED);
                save(p, j);
                return "A formal warning: " + reason + ".";
            }
            return dismiss(level, p, reason, false);
        } else if (stage > 0 && workedDays >= 3 && avg >= 3 && today - j.getLong("stageDay") >= STAGE_GAP) {
            j.putInt("stage", stage - 1);
            j.putLong("stageDay", today);
        }
        // Promotion.
        int good = 0;
        for (CompoundTag r : days(j)) {
            long d = r.getLong("d");
            if (d >= today || d < today - 7) continue;
            if (worked(r) && r.getInt("st") >= 4) good++;
        }
        int lv = c == null ? 0 : Shifts.level(p, c.shift());
        if (rank(j) < 2 && workedDays >= GOOD_FOR_PROMOTION && good >= GOOD_FOR_PROMOTION - 1 && avg >= PROMOTE_STARS && lv >= rank(j) + 1
                && today - Math.max(j.getLong("rankDay"), j.getLong("since")) >= PROMOTE_GAP) {
            j.putInt("rank", rank(j) + 1);
            j.putLong("rankDay", today);
            String title = rankTitle(j);
            say(p, j, pick(PROMOTE, seed).replace("{rank}", title.toLowerCase(Locale.ROOT)), ChatFormatting.AQUA);
            save(p, j);
            Villages.tell(j.getUUID("town"), today, p.getGameProfile().getName() + " was made " + title.toLowerCase(Locale.ROOT) + " at " + j.getString("employerName"));
            return "Promoted to " + title + ": a sixth more pay, and a tenth more asked.";
        }
        save(p, j);
        return "";
    }

    // ------------------------------------------------------------------ leaving, leave, switching

    /** Give notice: the job ends after tomorrow's shift. */
    static String giveNotice(ServerPlayer p) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "!You have no job.";
        if (j.getLong("leaving") > 0) return "!You've given notice already: your last day is day " + (j.getLong("leaving") - 1) + ".";
        j.putLong("leaving", today(p) + 2);
        save(p, j);
        say(p, j, pick(new String[]{ "Sorry to lose you. Work your notice and we'll part friends.", "Right. Tomorrow's your last, then.",
            "Understood. Thanks for telling me straight.", "Pity. You'll have your pay to the end.", "I'll put the advert up. Good luck.",
            "Fair enough. Finish well." }, today(p)), ChatFormatting.GRAY);
        save(p, j);
        return "Notice given: your last day is day " + (today(p) + 1) + ". You're paid for every day you work till then, and you leave with a good word.";
    }

    /** Leave at once (or at the end of notice). */
    static String leaveNow(ServerLevel level, ServerPlayer p, String why) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "!You have no job.";
        boolean noticed = j.getLong("leaving") > 0 && today(p) >= j.getLong("leaving");
        CompoundTag past = new CompoundTag();
        past.putString("line", rankTitle(j) + " at " + j.getString("employerName") + ", left day " + today(p) + (noticed ? " after notice" : " without notice"));
        ListTag hist = Earnings.data(p).getList("jobHistory", Tag.TAG_COMPOUND);
        hist.add(0, past);
        while (hist.size() > 8) hist.remove(hist.size() - 1);
        Earnings.data(p).put("jobHistory", hist);
        Earnings.data(p).remove(PlayerJobs.KEY);
        Shifts.quit(p, true);
        Villages.tell(j.getUUID("town"), today(p), p.getGameProfile().getName() + " left their work at " + j.getString("employerName"));
        WorkWay.refresh(p);
        return why + " You've left " + j.getString("employerName") + (noticed ? " on good terms." : ". Leaving without notice: no pay for today.");
    }

    /** Book leave: so many days from tomorrow. */
    static String bookLeave(ServerPlayer p, int n) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "!You have no job.";
        n = Math.max(1, Math.min(3, n));
        if (j.getInt("leaveTaken") + n > MOST_LEAVE) return "!You've " + (MOST_LEAVE - j.getInt("leaveTaken")) + " days of leave left this month.";
        long from = today(p) + 1;
        j.putLong("leaveFrom", from);
        j.putLong("leaveTo", from + n - 1);
        j.putInt("leaveTaken", j.getInt("leaveTaken") + n);
        save(p, j);
        Planner.add(p, from, 6, "leave", "Leave from " + j.getString("employerName") + " (" + n + (n == 1 ? " day)" : " days)"), null);
        return "Leave booked: day " + from + (n > 1 ? " to day " + (from + n - 1) : "") + ". Days on leave are unpaid and never count against you.";
    }

    /** Ask to change the way of pay at the next review. */
    static String askSwitch(ServerPlayer p) {
        CompoundTag j = job(p);
        if (!j.contains("role")) return "!You have no job.";
        if (j.getBoolean("fixedPay")) return "!" + j.getString("employerName") + " only offers this post " + ("commission".equals(j.getString("pay")) ? "on commission." : "on a salary.");
        String to = "commission".equals(j.getString("pay")) ? "salary" : "commission";
        j.putString("switchTo", to);
        save(p, j);
        return "Asked: you'll be on " + to + " from tomorrow morning's review.";
    }

    // ------------------------------------------------------------------ the hand-in

    /** What a hand-in would count (or did): counted, refused with each reason, pay (coin). */
    public record HandIn(int counted, Map<String, Integer> refused, int pay, double carry, String goods) {}

    /** Count (and, if {@code doIt}, take and pay for) the worker's produce for the job. */
    public static HandIn handIn(ServerLevel level, ServerPlayer p, boolean doIt) {
        CompoundTag j = job(p);
        Map<String, Integer> refused = new LinkedHashMap<>();
        JobCards.Card c = card(j);
        if (!j.contains("role") || c == null || !c.measure().produce()) return new HandIn(0, refused, 0, 0, "");
        String employer = j.getString("employer");
        int counted = 0;
        String goods = "";
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !producedBy(c, st)) continue;
            String why = Provenance.whyNot(st, employer, p.getUUID());
            if (why.isEmpty()) {
                counted += st.getCount();
                slots.add(i);
                if (goods.isEmpty()) goods = Goods.name(st).toLowerCase(Locale.ROOT);
            } else refused.merge(why, st.getCount(), Integer::sum);
        }
        double owed = j.getDouble("each") * counted + j.getDouble("carry");
        int pay = (int) Math.floor(owed + 1e-9);
        double carry = owed - pay;
        if (!doIt || counted == 0) return new HandIn(counted, refused, pay, carry, goods);
        UUID town = j.getUUID("town");
        Business firm = employer.startsWith("firm:") ? Businesses.of(town, employer.substring(5)) : null;
        Business farm = employer.startsWith("farm:") ? PlayerFarms.business(employer.substring(5)) : null;
        for (int i : slots) {
            ItemStack st = Provenance.clean(p.getInventory().getItem(i));
            p.getInventory().setItem(i, ItemStack.EMPTY);
            if (farm != null) PlayerFarms.intoStock(level, farm, st);
            else if (firm == null || !Enterprise.output(level, town, firm, st)) Market.intoStores(level, town, st);
        }
        int paid = pay <= 0 ? 0 : payFrom(level, p, j, pay, "commission on " + counted + " " + goods);
        j.putDouble("carry", Math.max(0, owed - paid));
        j.putInt("handed", j.getInt("handed") + counted);
        j.putInt("earned", j.getInt("earned") + paid);
        CompoundTag r = day(j, today(p));
        r.putInt("c", r.getInt("c") + paid);
        save(p, j);
        return new HandIn(counted, refused, paid, Math.max(0, owed - paid), goods);
    }

    /** Is this the kind of thing the job's output is (wheat for a farm hand, fish for a fisher...)? */
    static boolean producedBy(JobCards.Card c, ItemStack st) {
        return switch (c.shift()) {
            case FARM -> st.is(net.neoforged.neoforge.common.Tags.Items.CROPS) || st.is(net.minecraft.world.item.Items.WHEAT)
                || st.is(net.minecraft.world.item.Items.BEETROOT) || st.is(net.minecraft.world.item.Items.WHEAT_SEEDS);
            case WOOD -> st.is(net.minecraft.tags.ItemTags.LOGS);
            case SHEAR -> st.is(net.minecraft.tags.ItemTags.WOOL);
            case FISH -> st.is(net.minecraft.tags.ItemTags.FISHES);
            case MINE -> st.is(net.neoforged.neoforge.common.Tags.Items.RAW_MATERIALS) || st.is(net.minecraft.world.item.Items.COBBLESTONE)
                || st.is(net.minecraft.world.item.Items.COAL) || st.is(net.neoforged.neoforge.common.Tags.Items.GEMS)
                || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || st.is(net.minecraft.world.item.Items.REDSTONE)
                || st.is(net.minecraft.world.item.Items.LAPIS_LAZULI) || st.is(net.minecraft.world.item.Items.STONE);
            default -> false;
        };
    }

    /** Where the hand-in is taken: the employer's storehouse or post, within so many blocks. */
    @Nullable
    static BlockPos handInAt(ServerLevel level, CompoundTag j) {
        if (!j.hasUUID("town")) return null;
        String e = j.getString("employer");
        if (e.startsWith("farm:")) {
            PlayerFarms.Farm f = PlayerFarms.byId(e.substring(5));
            if (f != null) return f.centre();
        }
        return ShiftTasks.storehouse(level, j.getUUID("town"));
    }

    // ------------------------------------------------------------------ the clock: presence, mornings, logins

    private static long lastDay = -1;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        int tick = e.getServer().getTickCount();
        if (tick % 100 != 41) return;
        ServerLevel level = e.getServer().overworld();
        long day = level.getDayTime() / 24000L, time = level.getDayTime() % 24000L;
        boolean morning = day != lastDay;
        lastDay = day;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            try {
                CompoundTag j = job(p);
                if (j.contains("role")) {
                    present(level, p, j, day, time);
                    if (morning || j.getLong("reviewed") < day) {
                        String said = morning(level, p, day);
                        if (!said.isEmpty()) p.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.GOLD));
                    }
                }
                if (morning) JobAdverts.morningToast(level, p);
                WorkWay.tick(level, p);
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[wpb] a work tick failed for {}", p.getGameProfile().getName(), ex);
            }
        }
        JobAdverts.tick(level);
    }

    /** In town during the job's hours: the day counts as one the worker was here for (offline days never do). */
    static void present(ServerLevel level, ServerPlayer p, CompoundTag j, long day, long time) {
        if (onLeave(j, day)) return;
        ShiftRole r = ShiftRole.of(j.getString("role"));
        if (r == null) return;
        ServiceHours h = r.hours(null);
        if (!h.isOpen(time)) return;
        Villages.Village here = PlayerServices.townAt(level, p.blockPosition());
        if (here == null || !here.id().equals(j.hasUUID("town") ? j.getUUID("town") : null)) return;
        CompoundTag d = day(j, day);
        if (!d.getBoolean("o")) {
            d.putBoolean("o", true);
            save(p, j);
        }
        // Shift-start toast ten minutes before (a sixth of an hour of the day) the hours open, once a day.
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        CompoundTag j = job(p);
        long now = System.currentTimeMillis();
        long last = Earnings.data(p).getLong("lastSeenMs");
        Earnings.data(p).putLong("lastSeenMs", now);
        if (!j.contains("role")) return;
        long limit = LONG_ABSENCE_FOR_TESTS >= 0 ? LONG_ABSENCE_FOR_TESTS : LONG_ABSENCE_MS;
        if (last > 0 && now - last > limit) {
            String said = morning(level, p, today(p));                      // what was earned first
            CompoundTag past = new CompoundTag();
            past.putString("line", rankTitle(j) + " at " + j.getString("employerName") + ": the post was filled while you were away (no black mark)");
            ListTag hist = Earnings.data(p).getList("jobHistory", Tag.TAG_COMPOUND);
            hist.add(0, past);
            Earnings.data(p).put("jobHistory", hist);
            Earnings.data(p).remove(PlayerJobs.KEY);
            Shifts.quit(p, true);
            p.sendSystemMessage(Component.literal("While you were away: " + (said.isEmpty() ? "" : said + " ") + j.getString("employerName")
                + " filled your post after " + ((now - last) / 86400000L) + " days without word. No hard feelings: apply again any time.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        String said = morning(level, p, today(p));
        if (!said.isEmpty()) p.sendSystemMessage(Component.literal("While you were away: " + said).withStyle(ChatFormatting.GOLD));
    }

    /** Tests: misconduct seen (walking off a shift, theft...). */
    public static void misconductForTests(ServerLevel level, ServerPlayer p, String what) {
        misconduct(level, p, what, false);
    }

    /** Tests: a login as the event would have it. */
    public static void loginForTests(ServerPlayer p) {
        onLogin(new PlayerEvent.PlayerLoggedInEvent(p));
    }

    /** Tests: when the player was last seen (real time). */
    public static void lastSeenForTests(Player p, long ms) {
        Earnings.data(p).putLong("lastSeenMs", ms);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        Earnings.data(e.getEntity()).putLong("lastSeenMs", System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ hiring (from the adverts)

    /** Take the player on: the job written with its terms. */
    static void hire(ServerLevel level, ServerPlayer p, UUID town, String employer, String employerName, JobCards.Card c, int wage,
                     String hours, boolean commission, boolean fixedPay) {
        CompoundTag j = new CompoundTag();
        long d = level.getDayTime() / 24000L;
        j.putString("role", c.shift().id);
        j.putString("trade", c.key());
        j.putUUID("town", town);
        j.putString("employer", employer);
        j.putString("employerName", employerName);
        j.putInt("wage", Math.max(1, wage));
        j.putInt("going", Math.max(1, wage));
        j.putString("hours", hours);
        j.putLong("since", d);
        j.putLong("lastWorked", d);
        j.putLong("reviewed", d);
        j.putString("pay", commission ? "commission" : "salary");
        j.putBoolean("fixedPay", fixedPay);
        save(p, j);
        if (commission) rerate(level, p);
        Shifts.take(p, town, c.shift().id, true);
        WorkWay.refresh(p);
        Planner.add(p, d + 1, 6, "shift", rankTitle(j) + " at " + employerName + ": first full day", null);
    }

    /** Tests: a day's record put in as if worked (units, stars), and whether the worker was in town. */
    public static void dayForTests(ServerPlayer p, long day, double units, int target, boolean present) {
        CompoundTag j = job(p);
        CompoundTag r = day(j, day);
        r.putDouble("u", units);
        r.putInt("n", units > 0 ? 1 : 0);
        r.putInt("t", target);
        r.putInt("st", WorkMath.dayStars(units, target));
        r.putBoolean("o", present || units > 0);
        save(p, j);
    }

    /** Tests: the morning now (as if a new day). */
    public static String morningForTests(ServerLevel level, ServerPlayer p, long day) {
        CompoundTag j = job(p);
        j.putLong("reviewed", day - 1);
        save(p, j);
        return morning(level, p, day);
    }

    /** Tests: the job's tag. */
    public static CompoundTag jobForTests(Player p) {
        return job(p).copy();
    }

    public static void hireForTests(ServerLevel level, ServerPlayer p, UUID town, String employer, String name, String trade, int wage, boolean commission) {
        JobCards.Card c = JobCards.of(trade);
        if (c != null) hire(level, p, town, employer, name, c, wage, "dawn to dusk", commission, false);
    }

    public static String handInForTests(ServerLevel level, ServerPlayer p) {
        HandIn h = handIn(level, p, true);
        return h.counted() + "|" + h.pay() + "|" + h.refused();
    }

    static final Random RANDOM = new Random();
}
