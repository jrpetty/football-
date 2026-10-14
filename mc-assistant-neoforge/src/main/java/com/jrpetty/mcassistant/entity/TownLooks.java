package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tidy] How a town looks, out of a hundred, and what the town does about it.
 *
 * <ul>
 * <li><b>The measure</b>, taken each time the town has been walked over (Eyesores): tidiness (the eyesores, the grass
 *     inside its buildings, what is lying about), greenery (its open ground in turf), lighting (its streets lit after
 *     dark), flowers, and its buildings finished rather than going up.</li>
 * <li><b>The folk notice</b>: proud of a town that looks a picture, put out by a mess (Contentment), and they say so.</li>
 * <li><b>A clean-up day</b>: when the town falls below tidy enough, its elder calls one of a morning (kept with the
 *     world), and three tidy crews go out instead of one (Tidy).</li>
 * <li><b>Shown</b> on the village board and on the storehouse's page of the town's books.</li>
 * </ul>
 */
public final class TownLooks {

    private TownLooks() {}

    /** The parts' greatest shares of the hundred. */
    static final int TIDINESS = 40, GREENERY = 15, LIGHTING = 15, FLOWERS = 10, FINISHED = 20;
    /** What a part is worth with nothing yet to measure it by (no streets laid, no open ground, nothing built). */
    static final int NOTHING_YET = 10;
    /** The bands of the score: a picture, well kept, tidy enough, untidy; below that, a mess. */
    public static final int PICTURE = 85, PROUD = 70, UNTIDY = 50, MESS = 30;
    /** One eyesore's weight in the tidiness is its badness over this (a spill 5, a scar 1.25). */
    static final double WEIGHT_PER = 20.0;
    /** A cell of grass or earth inside a building, and a heap lying in the street, weigh this much. */
    static final double GRASS_INSIDE_WEIGHT = 0.5, LYING_WEIGHT = 0.3;
    /** The tidiness is gone when the weighed eyesores reach one for every so many columns of the town (and at least ten). */
    static final double COLUMNS_PER_ALLOWED = 200.0, LEAST_ALLOWED = 10.0;
    /** Open ground this green, and streets this lit, count in full (not every cell need be). */
    static final double GREEN_ENOUGH = 0.8, LIT_ENOUGH = 0.6;
    /** Flowers wanted: two a building, at least six. */
    static final int FLOWERS_A_BUILDING = 2, LEAST_FLOWERS = 6;
    /** Days between clean-up days; the hour by which one is called (a morning's); the folk a town wants to call one. */
    static final long CLEANUP_EVERY = 3, CLEANUP_BY = 6000L;
    static final int CLEANUP_FOLK = 6;
    /** How often somebody passing remarks on the look of the place (one walk in so many). */
    static final int REMARK_ONE_IN = 4, CLEANUP_CHEER_ONE_IN = 6;

    private static final String CLEANUP_NOTE = "tidy_cleanup";

    /** The town's looks, out of a hundred, and what they are made of. */
    public record Looks(int score, int tidiness, int greenery, int lighting, int flowers, int finished, int eyesores, int grassInside,
                        String word, String worst) {

        /** The parts, as words for the books. */
        public String parts() {
            return "tidiness " + tidiness + "/" + TIDINESS + ", greenery " + greenery + "/" + GREENERY + ", lighting " + lighting + "/"
                + LIGHTING + ", flowers " + flowers + "/" + FLOWERS + ", finished " + finished + "/" + FINISHED;
        }
    }

    /** A town's last measure and its last clean-up day. */
    private static final class State {
        @Nullable Looks looks;
        long cleanupCalled = Long.MIN_VALUE / 2;
        boolean loaded;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    public static void resetForTests() {
        STATES.clear();
    }

    private static State state(UUID village) {
        State s = STATES.computeIfAbsent(village, k -> new State());
        if (!s.loaded) {
            s.loaded = true;
            String kept = Ledger.note(village, CLEANUP_NOTE);
            try {
                if (kept != null && !kept.isEmpty()) s.cleanupCalled = Long.parseLong(kept);
            } catch (NumberFormatException ignored) {
                // an older or damaged note: as though none had been called
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ the measure

    /** The town's looks, measured off a whole walk of it. */
    static Looks measure(ServerLevel level, Villages.Village v, Eyesores.Walk w) {
        UUID id = v.id();
        int reach = Villages.townReach(id);
        int columns = (2 * reach + 1) * (2 * reach + 1);
        double weighed = 0;
        Eyesores.Kind worstKind = null;
        int worstBadness = 0;
        for (Map.Entry<Eyesores.Kind, Integer> e : w.counts.entrySet()) {
            weighed += e.getKey().worst / WEIGHT_PER * e.getValue();
            if (e.getValue() * e.getKey().worst > worstBadness) {
                worstBadness = e.getValue() * e.getKey().worst;
                worstKind = e.getKey();
            }
        }
        int grassInside = Floors.known(id);
        weighed += grassInside * GRASS_INSIDE_WEIGHT + Sweepers.lying(level, v) * LYING_WEIGHT;
        double allowed = Math.max(LEAST_ALLOWED, columns / COLUMNS_PER_ALLOWED);
        int tidiness = share(TIDINESS, 1.0 - weighed / allowed);
        int greenery = w.green + w.bare == 0 ? NOTHING_YET : share(GREENERY, w.green / (double) (w.green + w.bare) / GREEN_ENOUGH);
        int lighting = w.streets == 0 ? NOTHING_YET : share(LIGHTING, w.lit / (double) w.streets / LIT_ENOUGH);
        int buildings = Ledger.buildings(id).size();
        int flowers = share(FLOWERS, w.flowers / (double) Math.max(LEAST_FLOWERS, FLOWERS_A_BUILDING * buildings));
        int sites = Villages.sitesOf(id).size();
        int finished = buildings + sites == 0 ? NOTHING_YET : share(FINISHED, buildings / (double) (buildings + sites));
        int score = Math.max(0, Math.min(100, tidiness + greenery + lighting + flowers + finished));
        boolean grassWorst = grassInside > 0 && (worstKind == null || grassInside * Eyesores.Kind.HOLE.worst > worstBadness);
        String worst = grassWorst ? "grass growing inside the buildings"
            : worstKind == null ? "" : worstKind.words + (w.count(worstKind) > 1 ? " (" + w.count(worstKind) + ")" : "");
        return new Looks(score, tidiness, greenery, lighting, flowers, finished, w.total(), grassInside, word(score), worst);
    }

    /** So much of a part's share, for a fraction of it (clamped to nothing and all of it). */
    private static int share(int most, double fraction) {
        return (int) Math.round(most * Math.max(0.0, Math.min(1.0, fraction)));
    }

    /** The score in a word. */
    public static String word(int score) {
        return score >= PICTURE ? "a picture" : score >= PROUD ? "well kept" : score >= UNTIDY ? "tidy enough" : score >= MESS ? "untidy" : "a mess";
    }

    /** A fresh measure of the town's looks: kept as its looks, the folk notice it. */
    static void measured(ServerLevel level, Villages.Village v, Looks l) {
        state(v.id()).looks = l;
        noticed(level, v, l);
    }

    /** The town walked over at once and its looks measured and kept (the audit, the command, the tests). */
    public static Looks measureNow(ServerLevel level, Villages.Village v) {
        Floors.lookOverAll(level, v);
        Looks l = measure(level, v, Eyesores.walkNow(level, v));
        state(v.id()).looks = l;
        return l;
    }

    /** The town's looks as last measured, or null before its first whole walk. */
    @Nullable
    public static Looks looks(UUID village) {
        State s = STATES.get(village);
        return s == null ? null : s.looks;
    }

    // ------------------------------------------------------------------ what the town does about it

    /** Is today the town's clean-up day (called by its elder)? */
    public static boolean cleanupDay(ServerLevel level, UUID village) {
        return state(village).cleanupCalled == level.getDayTime() / 24000L;
    }

    /** The folk notice a new measure: an untidy town has its elder call a clean-up day; somebody passing remarks on it. */
    private static void noticed(ServerLevel level, Villages.Village v, Looks l) {
        State s = state(v.id());
        long day = level.getDayTime() / 24000L;
        boolean morning = level.getDayTime() % 24000L < CLEANUP_BY;
        if (l.score() < UNTIDY && day - s.cleanupCalled >= CLEANUP_EVERY && morning && Villages.headcount(v.id()) >= CLEANUP_FOLK) {
            callCleanup(level, v, l, day);
        }
        if (level.getRandom().nextInt(REMARK_ONE_IN) == 0) remark(level, v, l);
    }

    private static void callCleanup(ServerLevel level, Villages.Village v, Looks l, long day) {
        state(v.id()).cleanupCalled = day;
        Ledger.note(v.id(), CLEANUP_NOTE, Long.toString(day));
        String elder = Villages.elderName(v.id());
        Villages.tell(v.id(), day, (elder.isEmpty() ? "The town" : "Elder " + elder) + " called a clean-up day: the town had become "
            + l.word() + (l.worst().isEmpty() ? "" : " (" + l.worst() + ")"));
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && level.getRandom().nextInt(CLEANUP_CHEER_ONE_IN) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Clean-up day! Grab a broom.", "About time somebody tidied this place.",
                    "Many hands make light work."));
            }
        }
    }

    private static void remark(ServerLevel level, Villages.Village v, Looks l) {
        List<AssistantEntity> folk = Villages.folkOf(v.id());
        if (folk.isEmpty() || !(folk.get(level.getRandom().nextInt(folk.size())) instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping()) return;
        var r = level.getRandom();
        String line = l.score() >= PICTURE ? FolkTalk.pick(r, "Doesn't the town look a picture?", "I do like what we've done with the place.")
            : l.score() < MESS ? FolkTalk.pick(r, "The square's a mess. Does nobody sweep round here?", "Look at the state of this town.")
            : l.score() < UNTIDY ? (l.worst().isEmpty() ? "The place could do with a tidy."
                : FolkTalk.pick(r, "The place could do with a tidy.", "Somebody ought to see to " + l.worst() + "."))
            : null;
        if (line != null) FolkTalk.speak(f, line);
    }

    /** Contentment's say: proud of a town that looks a picture, put out by a mess. */
    public static int contentment(@Nullable UUID village, List<String> good, List<String> bad) {
        Looks l = village == null ? null : looks(village);
        if (l == null) return 0;
        if (l.score() >= PICTURE) { good.add("the town looks a picture"); return 3; }
        if (l.score() >= PROUD) { good.add("a well-kept town"); return 1; }
        if (l.score() < MESS) { bad.add("the town's a mess"); return -4; }
        if (l.score() < UNTIDY) { bad.add("the town's untidy"); return -2; }
        return 0;
    }

    // ------------------------------------------------------------------ shown

    /** The board's line ("RG", "RN" or "RW" and the words), or null before the first walk. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Looks l = looks(village);
        if (l == null) return null;
        int done = Tidy.doneToday(level, village);
        String colour = l.score() >= PROUD ? "RG" : l.score() < UNTIDY ? "RW" : "RN";
        return colour + "|Looks: " + l.score() + " (" + l.word() + ")" + (l.worst().isEmpty() ? "" : "; to see to: " + l.worst())
            + (cleanupDay(level, village) ? "; today is clean-up day" : "") + (done > 0 ? "; tidied today: " + done : "") + ".";
    }

    /** Into the storehouse's report (Sweepers.report): the looks and the tidying, for the town's books. */
    static void report(ServerLevel level, UUID village, CompoundTag out) {
        Looks l = looks(village);
        if (l == null) return;
        out.putInt("looks", l.score());
        out.putString("looks_word", l.word());
        out.putString("looks_worst", l.worst());
        out.putString("looks_parts", l.parts());
        out.putInt("eyesores", l.eyesores());
        out.putInt("grass_inside", l.grassInside());
        out.putInt("tidied", Tidy.doneToday(level, village));
    }

    /** Tests: as though the elder had called a clean-up day today (true) or none for days (false). */
    public static void cleanupForTests(ServerLevel level, UUID village, boolean today) {
        state(village).cleanupCalled = today ? level.getDayTime() / 24000L : Long.MIN_VALUE / 2;
    }
}
