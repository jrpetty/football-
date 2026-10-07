package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What a town knows of another: its scouts' last report, dated. A town knows nothing of a neighbour's
 * strength but what somebody went and saw; the report goes stale as the days pass, and a war council
 * that has none goes on rumour.
 *
 * <p>[war] The shared seam between the scouts and the war council and the fighting. Gathering it (the
 * scouts' missions, the watch catching spies, deception) belongs to the scouting work; everybody else
 * reads it. A report is kept with the world on the town's books (Ledger note "intel/&lt;other&gt;").
 */
public final class Intel {

    private Intel() {}

    /**
     * One report on another town: the day it was made, and what was counted. {@code guards} the watch,
     * {@code armoured} of them in iron or better, {@code archers} with bows, {@code walls} sections of wall
     * standing (0 none), {@code gates} gates, {@code foodDays} days its stores would feed it, {@code folk} its
     * grown folk, {@code note} anything else worth telling (a scout's own words).
     */
    public record Report(UUID about, long day, int folk, int guards, int armoured, int archers, int walls, int gates,
                         int foodDays, String note) {

        String encode() {
            return day + ";" + folk + ";" + guards + ";" + armoured + ";" + archers + ";" + walls + ";" + gates + ";"
                + foodDays + ";" + note.replace(";", ",");
        }

        @Nullable
        static Report decode(UUID about, String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(";", 9);
            if (p.length < 8) return null;
            try {
                return new Report(about, Long.parseLong(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                    Integer.parseInt(p[7]), p.length > 8 ? p[8] : "");
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The latest report this town has on that one, or null if nobody has been. */
    @Nullable
    public static Report latest(UUID us, UUID them) {
        if (us == null || them == null) return null;
        return Report.decode(them, Ledger.note(us, "intel/" + them));
    }

    /** File a report (it replaces the last one on the same town). */
    public static void file(UUID us, Report r) {
        if (us == null || r == null) return;
        Ledger.note(us, "intel/" + r.about(), r.encode());
    }

    /** How many days old a report is. */
    public static long age(Report r, long today) {
        return Math.max(0L, today - r.day());
    }
}
