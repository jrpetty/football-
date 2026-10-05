package com.jrpetty.mcassistant.entity;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The larder's reckoning of mouths: whether the village can feed one more, and what it will eat in a
 * day counting everybody it has now.
 *
 * <p>The hundred days' village, eight folk on the plains, had nearly fourteen days' food put by on its
 * twentieth day, a hundred and ten meals grown a day against fifty-seven eaten, and its larder said yes
 * to every child that asked: sixteen were born in a week, twins among them, and by the thirtieth day it
 * grew seventy-one meals a day against a hundred and thirty-seven eaten, the larder down from nine
 * hundred to under two hundred, and still it said yes. A full larder is not a fed village. What is in it
 * was grown for the folk there were, and a child is a mouth for good.
 *
 * <p>So a child is raised only when:
 * <ul>
 * <li>the leader has not got the village on short commons (Leader's plan), and</li>
 * <li>the fields, the waters and the hunt grow at least what the town eats in a day, counting every
 *     mouth it has now and the child's besides; or the gap is small (a fifth of what is eaten) and the
 *     larder could carry it for a fortnight while the new fields come in.</li>
 * </ul>
 * And the leader's forecast counts the mouths born since its books were last made up, so a boom turns
 * the plan to short commons (more hands to the fields, the fields widened) before the larder is gone.
 */
public final class Larder {

    private Larder() {}

    /** The least one folk eats in a day: three meals, child, elder or hand alike (Meals). */
    public static final double MEALS_A_HEAD = 3.0;
    /** The gap between grown and eaten the larder may carry, as a part of what is eaten... */
    public static final double CARRY_GAP = 0.2;
    /** ...and how many days of that gap it must hold to carry it. */
    public static final double CARRY_DAYS = 14.0;

    /** How many heads the leader's books were last made up for (Leader.morning). */
    private static final Map<UUID, Integer> HEADS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HEADS.clear();
    }

    /** The morning's books are made up, for this many heads (Leader.morning). */
    public static void booked(@Nullable UUID village, int heads) {
        if (village != null) HEADS.put(village, Math.max(1, heads));
    }

    /** What one folk eats in a day by the books: the town's day over the heads it had, never under three meals. */
    public static double perHead(UUID village, double useAvg) {
        Integer then = HEADS.get(village);
        int heads = then != null ? then : Math.max(1, Villages.headcount(village));
        return Math.max(MEALS_A_HEAD, useAvg / heads);
    }

    /** What the town will eat in a day, with every mouth it has now and {@code more} besides. */
    public static double eaten(UUID village, double useAvg, int more) {
        return Math.max(useAvg, perHead(village, useAvg) * (Villages.headcount(village) + more));
    }

    /**
     * What the leader's forecast takes as eaten a day (Leader.morning): the books' average, or what
     * every mouth here now eats by them, whichever is more. The books' average is three days long and
     * lags a run of births by as much: eight folk became sixteen in four days while it read fifty eaten.
     */
    public static double forecast(UUID village, double useAvg) {
        return eaten(village, useAvg, 0);
    }

    /** The larder's word on one more mouth: yes or no, and why, in a line a player can read. */
    public record Verdict(boolean yes, String why) {}

    /**
     * May this village raise a child now, as its food goes? {@code stock} is the food in its stores. With
     * no books kept yet (a village's first morning) the larder's stock alone decides, as it always did.
     */
    public static Verdict oneMore(@Nullable UUID village, int stock) {
        Leader.Books b = village == null ? null : Leader.books(village);
        if (b == null) return new Verdict(true, "no books kept yet");
        return judge(stock, b.inAvg(), eaten(village, b.useAvg(), 1), b.plan());
    }

    /** The rule itself, on the numbers: food in the stores, grown a day, eaten a day with the child, the plan. */
    public static Verdict judge(int stock, double grown, double eat, Leader.Plan plan) {
        if (plan == Leader.Plan.FAMINE || plan == Leader.Plan.SHORT) {
            return new Verdict(false, "the village is on short commons, and no child is raised till the fields catch up");
        }
        if (grown >= eat) {
            return new Verdict(true, Math.round(grown) + " meals grown a day against " + Math.round(eat) + " eaten, a child's included");
        }
        double gap = eat - grown;
        if (gap <= eat * CARRY_GAP && stock >= gap * CARRY_DAYS) {
            return new Verdict(true, "the larder can carry the gap (" + Math.round(grown) + " grown a day against "
                + Math.round(eat) + " eaten) for a fortnight");
        }
        return new Verdict(false, Math.round(grown) + " meals grown a day against " + Math.round(eat)
            + " eaten with one more mouth: the fields first");
    }

    /**
     * Has the forecast turned (the leader on short commons, and more eaten than grown by a quarter)? Then a
     * miner or woodcutter whose stores are piled high is wanted in the fields before the larder runs low,
     * not after (VillageFolkEntity.turnedToTheFields).
     */
    public static boolean fieldsWanted(@Nullable UUID village) {
        Leader.Books b = village == null ? null : Leader.books(village);
        if (b == null || (b.plan() != Leader.Plan.SHORT && b.plan() != Leader.Plan.FAMINE)) return false;
        return forecast(village, b.useAvg()) > b.inAvg() * 1.25;
    }

    /** "yes — ..." or "no — ...": the larder's word on one more mouth, for the books (Annals). */
    public static String word(@Nullable UUID village, int stock) {
        Verdict v = oneMore(village, stock);
        return (v.yes() ? "yes" : "no") + " — " + v.why();
    }

    /** For the books and the elder: the forecast in words. */
    public static String line(@Nullable UUID village) {
        Leader.Books b = village == null ? null : Leader.books(village);
        if (b == null) return "";
        return String.format(Locale.ROOT, "%d grown a day against %d eaten by %d mouths",
            Math.round(b.inAvg()), Math.round(forecast(village, b.useAvg())), Villages.headcount(village));
    }
}
