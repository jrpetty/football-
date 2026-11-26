package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * [featcivic] How two towns' governments sit with each other, and what comes of it (Diplomacy.daily, once a day a pair).
 *
 * <p>Each form has a place on two plain measures: how much say the folk have (from a democracy's full vote to a general's
 * none) and how much is held in common (from a guild's private trades to a planned state's everything). Two towns close
 * together on both get on a little better each day ("kindred governments"); two far apart a little worse ("they distrust
 * each other's ways"). And far apart, on bad terms already, more happens:
 * <ul>
 * <li><b>Embargo.</b> The two stop trading (Rivalry.embargoed, so no caravans, no deals) until they are on better terms.</li>
 * <li><b>Pamphlets and news.</b> A town without a vote finds a free neighbour's news in its square (its unrest stirred:
 *     Dissent.stir); a democracy finds pamphlets from an unelected neighbour, which do less (folk can vote).</li>
 * <li><b>Emigrants.</b> A household unhappy with how its town is run packs up for a neighbour run more to its liking
 *     (Newcomers, Cause.POLITICS), to ask to be taken in.</li>
 * <li><b>War, rarely.</b> A general whose neighbour is in a feud with it and as far from it as can be may march
 *     (WarAndPeace.declare), one day in sixty at most.</li>
 * </ul>
 * The board's Relations page shows each neighbour, its government, how they stand and why.
 */
public final class Ideology {

    private Ideology() {}

    /** A form's place: {say, common}, each -1 to 1. */
    static double[] place(Government.Form f) {
        return switch (f) {
            case DEMOCRACY -> new double[]{ 1.0, 0.0 };
            case REEVE -> new double[]{ 0.8, 0.0 };
            case COMMUNE -> new double[]{ 0.7, 0.9 };
            case GUILD -> new double[]{ 0.3, -0.7 };
            case COUNCIL -> new double[]{ 0.2, 0.1 };
            case CHAPLAIN -> new double[]{ -0.4, 0.3 };
            case LORD -> new double[]{ -0.7, -0.4 };
            case PLANNED -> new double[]{ -0.6, 1.0 };
            case JUNTA -> new double[]{ -1.0, -0.1 };
        };
    }

    /** How far apart two forms are, 0 (the same) to about 2.3. */
    public static double distance(Government.Form a, Government.Form b) {
        double[] p = place(a), q = place(b);
        return Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]));
    }

    /** What their governments do to how two towns get on, a day: +2 kindred to -2 opposed. */
    public static int lean(Government.Form a, Government.Form b) {
        double d = distance(a, b);
        return d < 0.35 ? 2 : d < 0.8 ? 1 : d < 1.3 ? 0 : d < 1.7 ? -1 : -2;
    }

    /** Why, in a few words, for the board. */
    public static String reason(Government.Form a, Government.Form b) {
        double d = distance(a, b);
        if (a == b) return "both are " + plural(a);
        if (FormChoice.democratic(a) && FormChoice.democratic(b)) return "both elect their leaders";
        if (d < 0.8) return "kindred governments";
        if (d < 1.3) return "different ways, no quarrel over them";
        if (place(a)[0] > 0.5 && place(b)[0] < -0.3 || place(b)[0] > 0.5 && place(a)[0] < -0.3) return "one votes, the other is ruled";
        if (Math.abs(place(a)[1] - place(b)[1]) > 1.2) return "the planned against the private";
        return "they distrust each other's ways";
    }

    static String plural(Government.Form f) {
        return switch (f) {
            case DEMOCRACY -> "democracies";
            case REEVE -> "towns that elect a leader";
            case COMMUNE -> "communes";
            case GUILD -> "guild republics";
            case COUNCIL -> "ruled by elders";
            case CHAPLAIN -> "ruled by a chaplain";
            case LORD -> "lordships";
            case PLANNED -> "planned states";
            case JUNTA -> "under military rule";
        };
    }

    /** The day's dealings over how they are ruled (Diplomacy.daily): the lean on their relation, and now and then more. */
    static int daily(ServerLevel level, Villages.Village a, Villages.Village b, long day, int relation) {
        if (Identity.neutral() || Identity.known(a.id()) == null || Identity.known(b.id()) == null) return 0;
        UUID x = a.id(), y = b.id();
        Government.Form fx = Government.of(x), fy = Government.of(y);
        int lean = lean(fx, fy);
        double d = distance(fx, fy);
        java.util.Random rng = new java.util.Random(Objects.hash(Ledger.pair(x, y), day, "ideology"));
        // The embargo: laid far apart and on uneasy terms; lifted when they get on again.
        boolean emb = embargoed(x, y);
        if (!emb && d >= 1.5 && relation <= Diplomacy.UNEASY) {
            Ledger.note(x, "ideology.embargo." + y, Long.toString(day));
            Ledger.note(y, "ideology.embargo." + x, Long.toString(day));
            String line = Villages.name(x) + " and " + Villages.name(y) + " stopped trading with each other: " + reason(fx, fy);
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
            log(x, day, line);
            log(y, day, line);
        } else if (emb && relation > Diplomacy.UNEASY + 15) {
            Ledger.note(x, "ideology.embargo." + y, "");
            Ledger.note(y, "ideology.embargo." + x, "");
            String line = Villages.name(x) + " and " + Villages.name(y) + " opened their markets to each other again";
            Villages.tell(x, day, line);
            Villages.tell(y, day, line);
        }
        // Pamphlets and news, every few days between towns far apart.
        if (d >= 1.3 && rng.nextInt(5) == 0) {
            boolean xFree = place(fx)[0] > 0.5, yFree = place(fy)[0] > 0.5;
            if (xFree != yFree) {
                UUID ruled = xFree ? y : x, free = xFree ? x : y;
                Dissent.stir(ruled, day, 8, Villages.name(free));
                String line = "news of " + Villages.name(free) + "'s elections was talked of in " + Villages.name(ruled) + "'s square";
                Villages.tell(ruled, day, line);
                log(ruled, day, line);
                if (rng.nextInt(3) == 0) {
                    // An agent caught at it: the town that sent it is the worse thought of.
                    String l3 = "the watch in " + Villages.name(ruled) + " caught an agent from " + Villages.name(free) + " handing out news-sheets";
                    Villages.tell(ruled, day, l3);
                    Villages.tell(free, day, l3);
                    log(ruled, day, l3);
                    lean -= 2;
                }
                if (rng.nextInt(2) == 0) {
                    Dissent.stir(free, day, 2, Villages.name(ruled));
                    String l2 = "pamphlets from " + Villages.name(ruled) + " were found in " + Villages.name(free) + "'s square; few read them";
                    Villages.tell(free, day, l2);
                    log(free, day, l2);
                }
            }
        }
        // Emigrants: a household unhappy with its town's government goes to a neighbour run more to its liking.
        com.jrpetty.mcassistant.Guard.run("emigrants", () -> {
            emigrate(level, a, b, day);
            emigrate(level, b, a, day);
        });
        // War, rarely: a general, a neighbour in a feud and as far from it as can be.
        if (WarAndPeace.on() && !Wars.atWar(x, y) && relation <= Diplomacy.FEUD && d >= 1.6 && rng.nextInt(60) == 0) {
            UUID general = fx == Government.Form.JUNTA ? x : fy == Government.Form.JUNTA ? y : null;
            if (general != null) {
                UUID other = general.equals(x) ? y : x;
                if (WarAndPeace.declare(level, general, other, day, WarAndPeace.Goal.REVENGE, 0, "an end to " + Villages.name(other) + "'s meddling")) {
                    String line = "General " + Villages.elderName(general) + " of " + Villages.name(general) + " marched on " + Villages.name(other) + ": " + reason(fx, fy);
                    log(general, day, line);
                    log(other, day, line);
                }
            }
        }
        return lean;
    }

    /** Is trade between these two shut over how they are ruled? */
    public static boolean embargoed(@Nullable UUID x, @Nullable UUID y) {
        if (x == null || y == null || Identity.neutral()) return false;
        String s = Ledger.note(x, "ideology.embargo." + y);
        return s != null && !s.isEmpty();
    }

    /** A discontented household from {@code from} packs up for {@code to}, if to is run more to its liking (once in five days a town). */
    static void emigrate(ServerLevel level, Villages.Village from, Villages.Village to, long day) {
        UUID id = from.id();
        if (Dissent.level(id) < Dissent.PETITION || Villages.headcount(id) <= Contentment.KEEP_AT_LEAST + 2) return;
        if (day - Dissent.nl(id, "ideology.emigrated", -99) < 5 || Wars.atWar(id, to.id())) return;
        Government.Form here = Government.of(id), there = Government.of(to.id());
        if (here == there) return;
        VillageFolkEntity head = null;
        for (VillageFolkEntity f : Dissent.aggrieved(id, 55, 12)) {
            if (f.getUUID().equals(Villages.elder(id)) || f.stationTask() == AssistantEntity.StationTask.GUARD || f.trip() != null) continue;
            int[] b = Dissent.blend(id, f);
            if (FormChoice.fit(there, b) - FormChoice.fit(here, b) >= 10) { head = f; break; }
        }
        if (head == null) return;
        List<VillageFolkEntity> household = new ArrayList<>(JobSeekers.household(head));
        household.removeIf(m -> m.getUUID().equals(Villages.elder(id)) || !m.isBaby() && m.stationTask() == AssistantEntity.StationTask.GUARD);
        if (!household.contains(head)) household.add(0, head);
        while (household.size() > 4) household.remove(household.size() - 1);
        if (Villages.headcount(id) - household.size() < Contentment.KEEP_AT_LEAST) return;
        Dissent.set(id, "ideology.emigrated", day);
        Newcomers.send(level, from, to, household, Newcomers.Cause.POLITICS);
        String line = head.displayNameCap() + "'s household left " + Villages.name(id) + " for " + Villages.name(to.id()) + ", which is " + there.words;
        log(id, day, line);
        log(to.id(), day, line);
    }

    // ------------------------------------------------------------------ the board

    /** Each neighbour: {name, form, terms, relation, reason, embargo?}. */
    public static List<String[]> relations(UUID village) {
        List<String[]> out = new ArrayList<>();
        Government.Form mine = Government.of(village);
        for (Villages.Village o : Diplomacy.neighboursOf(village)) {
            Government.Form theirs = Government.of(o.id());
            int r = Ledger.relation(village, o.id());
            int lean = lean(mine, theirs);
            String terms = Diplomacy.terms(r).words;
            String note = (lean > 0 ? "+" + lean : Integer.toString(lean)) + " a day: " + reason(mine, theirs);
            if (Wars.atWar(village, o.id())) note = "at war; " + note;
            out.add(new String[]{ Villages.name(o.id()), Regimes.shortName(theirs), terms, Integer.toString(r), note, embargoed(village, o.id()) ? "1" : "0",
                theirs.name() });
        }
        return out;
    }

    static void log(UUID village, long day, String line) {
        String s = Ledger.note(village, "ideology.log");
        List<String> h = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(";;")) h.add(x);
        h.add("Day " + (day + 1) + ": " + Regimes.capital(line).replace(";;", ";"));
        while (h.size() > 8) h.remove(0);
        Ledger.note(village, "ideology.log", String.join(";;", h));
    }

    public static List<String> logOf(UUID village) {
        String s = Ledger.note(village, "ideology.log");
        List<String> h = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String x : s.split(";;")) h.add(x);
        return h;
    }

    /** Tests: a day's dealings over how they are ruled, now. Returns the lean. */
    public static int dailyForTests(ServerLevel level, Villages.Village a, Villages.Village b) {
        return daily(level, a, b, level.getDayTime() / 24000L, Ledger.relation(a.id(), b.id()));
    }
}
