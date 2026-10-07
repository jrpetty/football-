package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The books a town keeps of its wars, written down with the world (Ledger notes, all under "wp."):
 * what it holds against each neighbour, each war's own page (when it began, what it was fought for,
 * what keeping the war footing has cost, how it went), the treaties it signed, the wars it fought
 * before, and those its wars cost. WarAndPeace decides; this only remembers.
 *
 * <p>A note is a line of text, so each list is its entries joined by "~" and each entry its fields
 * joined by "|"; what is written in them is cleaned of both first.
 */
final class WarBooks {

    private WarBooks() {}

    static final String SEP = "~", FIELD = "|";

    /** Words fit to go in a note: no separators. */
    static String clean(String s) {
        return s == null ? "" : s.replace(SEP, "-").replace(FIELD, "/").replace(";", ",");
    }

    // ------------------------------------------------------------------ lists

    /** A list kept in one note, oldest first. */
    static List<String> list(UUID village, String key) {
        String s = Ledger.note(village, key);
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String one : s.split(SEP)) if (!one.isEmpty()) out.add(one);
        return out;
    }

    static void list(UUID village, String key, List<String> entries) {
        Ledger.note(village, key, String.join(SEP, entries));
    }

    /** One more on the end of a list, the oldest let go past {@code keep}. */
    static void push(UUID village, String key, String entry, int keep) {
        List<String> all = list(village, key);
        all.add(entry);
        while (all.size() > keep) all.remove(0);
        list(village, key, all);
    }

    static long num(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    @Nullable
    static UUID id(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the war's own page

    /**
     * One war, as one side keeps it: the day it began, whether it was ours (our herald, our goal), what
     * it is for, how many stood on the watch the day it began, what keeping the war footing has cost a
     * day at a time (the militia's pay and the work lost to it), the last scouts' report written into it,
     * and the white flags sent.
     */
    static final class Book {
        long began;
        boolean aggressor;
        WarAndPeace.Goal goal = WarAndPeace.Goal.DEFENCE;
        int amount;
        String goalText = "";
        int watch;
        int cost;
        long costDay = -1;
        long intelDay = -1;
        int peaceTries;
        long lastPeaceTry = -100;

        String encode() {
            return began + ";" + (aggressor ? 1 : 0) + ";" + goal.name() + ";" + amount + ";" + watch + ";" + cost + ";" + costDay
                + ";" + intelDay + ";" + peaceTries + ";" + lastPeaceTry + ";" + clean(goalText);
        }

        @Nullable
        static Book decode(String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(";", 11);
            if (p.length < 10) return null;
            Book b = new Book();
            b.began = num(p[0], 0);
            b.aggressor = "1".equals(p[1]);
            try {
                b.goal = WarAndPeace.Goal.valueOf(p[2]);
            } catch (IllegalArgumentException e) {
                b.goal = WarAndPeace.Goal.DEFENCE;
            }
            b.amount = (int) num(p[3], 0);
            b.watch = (int) num(p[4], 0);
            b.cost = (int) num(p[5], 0);
            b.costDay = num(p[6], -1);
            b.intelDay = num(p[7], -1);
            b.peaceTries = (int) num(p[8], 0);
            b.lastPeaceTry = num(p[9], -100);
            b.goalText = p.length > 10 ? p[10] : "";
            return b;
        }
    }

    @Nullable
    static Book book(UUID us, UUID them) {
        return us == null || them == null ? null : Book.decode(Ledger.note(us, "wp.book/" + them));
    }

    static void save(UUID us, UUID them, Book b) {
        Ledger.note(us, "wp.book/" + them, b.encode());
    }

    /** The war is over: its page is put away (its summary goes to the town's past wars). */
    static void close(UUID us, UUID them) {
        Ledger.forget(us, "wp.book/" + them);
        Ledger.forget(us, "wp.course/" + them);
    }

    /** How the war went, a line a day something happened: "day|what". */
    static void course(UUID us, UUID them, long day, String what) {
        push(us, "wp.course/" + them, day + FIELD + clean(what), 14);
    }

    static List<String> course(UUID us, UUID them) {
        List<String> out = new ArrayList<>();
        for (String e : list(us, "wp.course/" + them)) {
            String[] p = e.split("\\|", 2);
            out.add(p.length < 2 ? e : "day " + p[0] + ": " + p[1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ grievances

    /** Something a town holds against a neighbour: when, what kind of wrong, and in its own words. */
    record Grievance(long day, WarAndPeace.Wrong kind, String what) {}

    /** Written down (once: the same wrong on the same day is not written twice). Ten kept. */
    static void wrong(UUID us, UUID them, long day, WarAndPeace.Wrong kind, String what) {
        String entry = day + FIELD + kind.name() + FIELD + clean(what);
        List<String> all = list(us, "wp.griev/" + them);
        if (all.contains(entry)) return;
        all.add(entry);
        while (all.size() > 10) all.remove(0);
        list(us, "wp.griev/" + them, all);
    }

    static List<Grievance> wrongs(UUID us, UUID them) {
        List<Grievance> out = new ArrayList<>();
        for (String e : list(us, "wp.griev/" + them)) {
            String[] p = e.split("\\|", 3);
            if (p.length < 3) continue;
            try {
                out.add(new Grievance(num(p[0], 0), WarAndPeace.Wrong.valueOf(p[1]), p[2]));
            } catch (IllegalArgumentException ignored) {
                // a wrong of a kind no longer kept is forgotten
            }
        }
        return out;
    }

    /** Settled: what it held against them is let go (a treaty, or the goal met). */
    static void forgive(UUID us, UUID them) {
        Ledger.forget(us, "wp.griev/" + them);
    }

    // ------------------------------------------------------------------ treaties

    /** A treaty, as a town keeps it: the day, with whom, the peace it keeps until, and its terms. */
    record Treaty(long day, UUID other, long until, String terms) {}

    /** Signed: in force between them until {@code until}, and in both towns' history. */
    static void treaty(UUID a, UUID b, long day, long until, String terms) {
        String t = clean(terms);
        Ledger.forget(a, "wp.broken/" + b);                                  // a new treaty, kept afresh
        Ledger.forget(b, "wp.broken/" + a);
        Ledger.note(a, "wp.treaty/" + b, day + FIELD + until + FIELD + t);
        Ledger.note(b, "wp.treaty/" + a, day + FIELD + until + FIELD + t);
        push(a, "wp.treaties", day + FIELD + b + FIELD + Villages.name(b) + FIELD + t, 8);
        push(b, "wp.treaties", day + FIELD + a + FIELD + Villages.name(a) + FIELD + t, 8);
    }

    /** The treaty between them, if it was signed (whether or not its peace has run out). */
    @Nullable
    static Treaty treaty(UUID a, UUID b) {
        String s = Ledger.note(a, "wp.treaty/" + b);
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", 3);
        if (p.length < 3) return null;
        return new Treaty(num(p[0], 0), b, num(p[1], 0), p[2]);
    }

    /** Is a treaty's peace in force between them today? */
    static boolean inForce(UUID a, UUID b, long day) {
        Treaty t = treaty(a, b);
        return t != null && t.until() >= day && !"broken".equals(Ledger.note(a, "wp.broken/" + b))
            && !"broken".equals(Ledger.note(b, "wp.broken/" + a));
    }

    /** The town's treaties, oldest first: {day, other id, other name, terms}. */
    static List<String[]> treaties(UUID village) {
        List<String[]> out = new ArrayList<>();
        for (String e : list(village, "wp.treaties")) {
            String[] p = e.split("\\|", 4);
            if (p.length == 4) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ the wars before

    /** A war over, in a line, for the town's history of its wars. */
    static void past(UUID village, String line) {
        push(village, "wp.past", clean(line), 8);
    }

    static List<String> past(UUID village) {
        return list(village, "wp.past");
    }

    /** One the war cost the town: the day, its name (and how), and with whom the war was. */
    static void fallen(UUID village, long day, String name, String enemy) {
        push(village, "wp.fallen", day + FIELD + clean(name) + FIELD + clean(enemy), 32);
    }

    /** {day, name, enemy}, oldest first. */
    static List<String[]> fallen(UUID village) {
        List<String[]> out = new ArrayList<>();
        for (String e : list(village, "wp.fallen")) {
            String[] p = e.split("\\|", 3);
            if (p.length == 3) out.add(p);
        }
        return out;
    }
}
