package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpb] The two ways a job can be paid, worked out from the town as it is today, and said in plain words.
 * <ul>
 * <li><b>Salaried.</b> "We'll give you the going rate": the town's going daily wage for the trade (the opening's own wage,
 *     which the labour market sets from the job's worth: LabourMarket, JobWorth), paid each morning for a day worked. A
 *     soft goal of the card's target; missing it now and then is not punished.</li>
 * <li><b>Commissioned.</b> Pay per unit of output. For produce (wheat, logs, wool, ore, fish) the rate is the stores' bid
 *     for one (Market.bidEach), less the employer's running costs on it, times the worker's share of what is left (half to
 *     seven tenths, by how thrifty the employer is: Temperament), paid at the hand-in for marked output (Provenance). For
 *     the rest (the counter, the watch, a load) a unit is worth a competent day's value to the employer over a competent
 *     day's units, paid as each task is done, with a small base. Either way the rate is held so that a three-star day pays
 *     nine to thirteen tenths of the going wage (WorkMath.bounded); a capped rate is logged and says "capped".</li>
 * </ul>
 */
public final class PayTerms {

    private PayTerms() {}

    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** One way of pay: commission or not, the rate a unit (coin), the base a day, the wage a day, the target, why. */
    public record Terms(boolean commission, double rate, int base, int wage, int target, String per, String why, boolean capped,
                        double each, int itemsPer) {

        /** "2.4c for every 10 wheat", or "1.2c a customer". */
        public String rateWords() {
            if (each <= 0) return "";
            if (itemsPer > 0 && each < 0.5) return WorkMath.coin(each * 10) + " for every 10 " + per;
            String one = itemsPer > 0 ? singular(per) : per;
            return WorkMath.coin(each) + (one.matches("^[aeiou].*") ? " an " : " a ") + one;
        }

        /** What a three-star day pays, coin. */
        public int dayAt3() {
            return commission ? (int) Math.round(base + rate * target) : wage;
        }
    }

    /** Items handed in a unit of output for the produce roles (a plot gives a wheat and a half, a tree five logs...). */
    static int itemsPer(ShiftRole r) {
        return switch (r) {
            case WOOD -> 5;
            case SHEAR -> 2;
            case FARM -> 2;
            case MINE, FISH -> 1;
            default -> 0;
        };
    }

    static String singular(String plural) {
        return plural.endsWith("ies") ? plural.substring(0, plural.length() - 3) + "y" : plural.endsWith("s") && !plural.endsWith("ss") ? plural.substring(0, plural.length() - 1) : plural;
    }

    /** The going daily wage for this card here: the opening's wage when it has one, else the labour market's, else the shifts'. */
    public static int goingWage(ServerLevel level, UUID village, JobCards.Card c, int offered) {
        if (offered > 0) return offered;
        if (c.task() != null && LabourMarket.live(village)) {
            try {
                return Math.max(1, LabourMarket.journeyman(village, c.task(), 1.0));
            } catch (RuntimeException ignored) { }
        }
        Integer jw = JobWorthWage.of(village);                              // what the town's folk are paid a day
        if (jw != null) return jw;
        double perTask = c.shift().wage * Shifts.payLevel(village);
        return (int) Math.max(3, Math.round(perTask * c.target() / Math.max(1, c.measure().perTask())));
    }

    /** The thing a unit of produce is priced as (the card's good, a workshop's own goods). */
    @Nullable
    static Item goodOf(JobCards.Card c) {
        if (c.shift() == ShiftRole.CRAFT) {
            for (WorkShifts.Recipe r : WorkShifts.RECIPES) if (r.trade().equals(c.key())) return r.out();
            return Items.STICK;
        }
        return c.measure().good();
    }

    /** The worker's share of the margin: the usual, less for a thrifty or shrewd employer, more for a well-liked one. */
    static double share(@Nullable VillageFolkEntity employer) {
        if (employer == null) return WorkMath.SHARE;
        double s = WorkMath.SHARE;
        for (Temperament.Mark m : Temperament.marks(employer)) {
            if (m == Temperament.Mark.THRIFTY || m == Temperament.Mark.SHREWD) s -= 0.05;
            if (m == Temperament.Mark.WELL_LIKED || m == Temperament.Mark.EASYGOING) s += 0.05;
        }
        return Math.max(WorkMath.SHARE_LO, Math.min(WorkMath.SHARE_HI, s));
    }

    /** The salaried terms. */
    public static Terms salary(JobCards.Card c, int wage, int target) {
        return new Terms(false, 0, 0, wage, target, c.measure().many(), "the town's going rate for " + plural(c.title().toLowerCase(Locale.ROOT)), false, 0, 0);
    }

    /**
     * The commissioned terms, from the economy today: the produce's bid (or a service unit's worth), the costs, the share,
     * held to the bounds.
     */
    public static Terms commission(ServerLevel level, UUID village, JobCards.Card c, int wage, int target, @Nullable VillageFolkEntity employer,
                                   String employerName) {
        double share = share(employer);
        JobCards.Measure m = c.measure();
        int per = m.produce() ? itemsPer(c.shift()) : 0;
        Item good = goodOf(c);
        double rate, each, base = 0;
        String why, unitWords;
        if (m.produce() && good != null) {
            double bid = Market.bidEach(level, village, new ItemStack(good));
            each = WorkMath.commission(bid, m.costShare(), share);
            rate = each * per;
            unitWords = goodWords(good);
            why = "the stores pay " + WorkMath.coin(bid) + " a " + singular(unitWords) + "; less " + Math.round(m.costShare() * 100) + "% for "
                + poss(employerName) + " costs, you get " + Math.round(share * 100) + "% of the rest";
        } else if (!m.produce() && good != null && c.shift() != ShiftRole.SMELT) {
            double bid = Market.bidEach(level, village, new ItemStack(good));
            each = WorkMath.commission(bid, m.costShare(), share) * Math.max(1, m.perTask() / Math.max(1, m.perTask()));
            rate = each;
            unitWords = c.measure().one();
            why = "a " + unitWords + " sells to the stores for " + WorkMath.coin(bid) + "; less the makings, you get " + Math.round(share * 100) + "% of the margin";
        } else {
            base = Math.round(wage * WorkMath.SERVICE_BASE);
            double worth = WorkMath.serviceWorth(wage, c.competent());
            each = worth * share;
            rate = each;
            unitWords = c.measure().one();
            why = "a competent day here is worth about " + WorkMath.coin(wage * WorkMath.SERVICE_MARGIN) + " to " + employerName
                + "; you get " + Math.round(share * 100) + "% of each " + unitWords + "'s part of it, on a base of " + (int) base + "c";
        }
        double[] b = WorkMath.bounded(rate, base, target, wage);
        boolean capped = b[1] != 0;
        if (capped) {
            double scale = rate <= 0 ? 1 : b[0] / rate;
            each = rate <= 0 ? (per > 0 ? b[0] / per : b[0]) : each * scale;
            rate = b[0];
            why += b[1] > 0 ? " (capped: a three-star day pays at most 1.3× the going wage)" : " (raised: a three-star day pays at least 0.9× the going wage)";
            LOG.info("[wpb] commission for {} in {} {}: {} a unit", c.key(), Villages.name(village), b[1] > 0 ? "capped" : "raised", String.format(Locale.ROOT, "%.3f", rate));
        }
        return new Terms(true, rate, (int) base, wage, target, unitWords, why, capped, each, per);
    }

    static String goodWords(Item good) {
        String n = Goods.name(new ItemStack(good)).toLowerCase(Locale.ROOT);
        for (String w : new String[]{ "wheat", "cod", "salmon", "coal", "wool", "bread" }) if (n.endsWith(w)) return n;
        return n.endsWith("s") ? n : n + "s";
    }

    static String plural(String s) {
        if (s.endsWith("man")) return s.substring(0, s.length() - 3) + "men";
        if (s.endsWith("ch") || s.endsWith("sh") || s.endsWith("s") || s.endsWith("x")) return s + "es";
        return s + "s";
    }

    /** "Hartwell Farm's", "the town's fields'". */
    static String poss(String name) {
        return name.endsWith("s") ? name + "'" : name + "'s";
    }

    /** The "How you're paid" box's lines for one way of pay. */
    public static List<String> box(Terms t, JobCards.Card c, String employerName) {
        List<String> out = new ArrayList<>();
        if (t.commission()) {
            out.add("You get " + t.rateWords() + (t.itemsPer() > 0 ? " from " + poss(employerName) + " " + c.where().toLowerCase(Locale.ROOT).replaceFirst("^the ", "") : "")
                + (t.base() > 0 ? ", on a base of " + t.base() + "c a day" : "") + ".");
            out.add(t.itemsPer() > 0 ? "No set target. Paid when you hand your output in; part counts carry over." : "No set target. Paid as each "
                + c.measure().one() + " is done.");
            out.add("A three-star day (" + c.targetLine(t.target()) + ") comes to about " + t.dayAt3() + "c.");
            out.add("Why this rate: " + t.why() + ".");
            out.add("Only your own output counts. No firing for a slow day, only for misconduct.");
        } else {
            out.add(t.wage() + "c a day, paid each morning for a day worked.");
            out.add("Aim for about " + c.targetLine(t.target()) + " a shift. Missing it now and then is fine.");
            out.add("Why this wage: " + t.why() + ".");
            out.add("Only a pattern of poor or missed days leads to a talk, then a warning.");
        }
        return out;
    }

    /** A folk's choice when it takes a job: by its character (Temperament) and its skill. True for commission. */
    public static boolean folkChoice(VillageFolkEntity f, int level) {
        int drive = 0;
        for (Temperament.Mark m : Temperament.marks(f)) {
            if (m == Temperament.Mark.AMBITIOUS || m == Temperament.Mark.RECKLESS) drive += 2;
            if (m == Temperament.Mark.HARDWORKING || m == Temperament.Mark.SHREWD) drive += 1;
            if (m == Temperament.Mark.TIMID) drive -= 2;
            if (m == Temperament.Mark.EASYGOING || m == Temperament.Mark.THRIFTY) drive -= 1;
        }
        double roll = Math.floorMod(f.getUUID().getLeastSignificantBits(), 1000) / 1000.0;
        return WorkMath.folkTakesCommission(drive, Math.min(1, level / 10.0), roll);
    }

    /** A rough going wage from the folk's own wages when the labour market is shut (null if none to go by). */
    static final class JobWorthWage {
        private JobWorthWage() {}

        @Nullable
        static Integer of(UUID village) {
            int n = 0, sum = 0;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a.isBaby()) continue;
                n++;
                sum += Market.wage(a);
                if (n >= 30) break;
            }
            return n == 0 ? null : Math.max(2, Math.round(sum / (float) n));
        }
    }
}
