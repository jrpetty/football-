package com.jrpetty.mcassistant.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * How long the town's folk have (/village lifespans, for ops and the smoke): each one's years, the
 * day it was born by them, the age it will live to and the day that falls on.
 *
 * <p>Grown folk age a year every third day (VillageFolkEntity.DAYS_A_YEAR), children six years to
 * the day as ever. The founders come to the village eighteen to forty-five, so they do not all grow
 * old together, and each lives to between seventy and a hundred (a tenth more with Healers): a
 * founder seventy-five days to two hundred and forty-odd, a child born in the village a hundred and
 * fifty to two hundred and fifty. Nothing here changes anything; it only reads them out.
 */
public final class Lifespans {

    private Lifespans() {}

    /** The day a folk comes to the end of its years (VillageFolkEntity.growingOld): born, and so many days on. */
    public static long endDay(VillageFolkEntity f) {
        if (f.bornDay() == VillageFolkEntity.UNKNOWN) f.ageYears();          // a founder's years, worked out
        return f.bornDay() + VillageFolkEntity.daysOldAt(f.lifespan());
    }

    /** The town's folk, eldest first: a heading, then a line each. */
    public static List<String> lines(UUID village, long today) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isShowcase()) folk.add(f);
        }
        for (VillageFolkEntity f : folk) f.ageYears();
        folk.sort(Comparator.comparingLong(VillageFolkEntity::bornDay).thenComparing(VillageFolkEntity::getUUID));
        List<String> out = new ArrayList<>();
        int old = 0;
        long soonest = Long.MAX_VALUE, latest = Long.MIN_VALUE;
        for (VillageFolkEntity f : folk) {
            if (f.isOld()) old++;
            long end = endDay(f);
            soonest = Math.min(soonest, end);
            latest = Math.max(latest, end);
        }
        out.add("LIFESPANS " + Villages.name(village) + " on day " + (today + 1) + ": " + folk.size() + " folk, " + old
            + " of them old (" + VillageFolkEntity.OLD_AT + " and on); grown folk age a year every " + VillageFolkEntity.DAYS_A_YEAR
            + " days" + (folk.isEmpty() ? "." : "; the first comes to the end of its years on day " + (soonest + 1)
            + ", the last on day " + (latest + 1) + "."));
        for (VillageFolkEntity f : folk) {
            long end = endDay(f);
            out.add("  " + f.displayNameCap() + (f.isBaby() ? " (a child)" : "") + ": " + f.ageYears() + ", born on day "
                + (f.bornDay() + 1) + "; lives to " + f.lifespan() + ", on day " + (end + 1) + " (in " + Math.max(0, end - today) + " days)"
                + (f.isOld() ? "; old" : ""));
        }
        return out;
    }
}
