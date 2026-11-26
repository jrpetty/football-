package com.jrpetty.mcassistant.entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ51] A folk's character as it tells in business: the marks read off what it already is (its nature, Social.Trait;
 * its quirks, Quirks; what it cares about, Values; its knacks; its dream, Persona.Ambition), and what each is worth to a
 * firm. Every weight is in {@link Mark}, in one table, for whatever else comes to read a folk's character by them.
 *
 * <p><b>Ambition</b> is the main thing: few have it (about one folk in eight, more among those who dream of being rich or
 * of leading the town, or whose heart is in trade or the next age, fewer among homebodies), and it is what makes a folk open
 * a business at all (Enterprise.founderFor). The rest decide how it goes: a hardworking, thrifty or shrewd owner runs it
 * cheaper and keeps it going through a bad week; an easygoing, spendthrift, careless or reckless one pays over the odds
 * and goes to the court sooner; a quarrelsome one cannot keep its hands.
 *
 * <p>The marks are worked out from the folk as it is, never rolled or saved: ambition is fixed by its own id against odds its
 * character sets, so the same folk is ambitious from one day to the next, and stops being so only if its character changes.
 */
public final class Temperament {

    private Temperament() {}

    /**
     * A mark of character and its weights for a firm: {@code drive}, the nerve it adds to opening one (Enterprise's
     * boldness); {@code costs}, its owner's buying at the market against the ask (-0.05: five in the hundred under);
     * {@code output}, more (or fewer) at a turn of the bench; {@code quits}, the chance a day that each hand walks out on
     * its owner; {@code staying}, the days more (or fewer) its creditors give it before the court.
     */
    public enum Mark {
        //            word             drive  costs  output quits  staying
        AMBITIOUS("ambitious",           3,  -0.02,   0,   0.00,    1),
        HARDWORKING("hardworking",       1,  -0.02,   1,   0.00,    1),
        THRIFTY("thrifty",               0,  -0.05,   0,   0.00,    1),
        SHREWD("shrewd",                 1,  -0.04,   0,   0.00,    0),
        CLEVER("clever",                 0,  -0.03,   0,   0.00,    1),
        WELL_LIKED("well liked",         0,   0.00,   0,  -0.02,    0),
        EASYGOING("easygoing",          -1,   0.03,  -1,   0.00,   -1),
        SPENDTHRIFT("spendthrift",       0,   0.06,   0,   0.00,   -1),
        RECKLESS("reckless",             1,   0.03,   0,   0.00,   -1),
        CARELESS("careless",             0,   0.04,   0,   0.00,   -1),
        QUARRELSOME("quarrelsome",       0,   0.02,   0,   0.05,    0),
        TIMID("timid",                  -2,   0.00,   0,   0.00,    0);

        public final String word;
        public final int drive, output, staying;
        public final double costs, quits;

        Mark(String word, int drive, double costs, int output, double quits, int staying) {
            this.word = word;
            this.drive = drive;
            this.costs = costs;
            this.output = output;
            this.quits = quits;
            this.staying = staying;
        }
    }

    /** The odds of ambition: one in sixteen to begin with, and what moves them. */
    static final double AMBITION_BASE = 0.06, AMBITION_DREAM = 0.25, AMBITION_HEART = 0.10, AMBITION_LEADER = 0.05,
        AMBITION_HOMEBODY = -0.05;

    /** The odds this folk is ambitious, by its character (its dream, its heart, its quirks). */
    public static double ambitionOdds(VillageFolkEntity f) {
        double p = AMBITION_BASE;
        if (f.persona().rolled()) {
            Persona.Ambition a = f.persona().ambition();
            if (a == Persona.Ambition.RICH || a == Persona.Ambition.LEAD) p += AMBITION_DREAM;
            Values.Value top = Values.top(f);
            if (top == Values.Value.WEALTH || top == Values.Value.PROGRESS) p += AMBITION_HEART;
        }
        if (Quirks.has(f, Quirks.Quirk.BORN_LEADER)) p += AMBITION_LEADER;
        if (Quirks.has(f, Quirks.Quirk.HOMEBODY)) p += AMBITION_HOMEBODY;
        return Math.max(0, Math.min(1, p));
    }

    /** Is it ambitious: its own id's draw (fixed for good) under the odds its character sets. */
    public static boolean ambitious(VillageFolkEntity f) {
        return draw(f.getUUID()) < ambitionOdds(f);
    }

    /** A folk's own fixed draw in [0, 1), from its id. */
    static double draw(UUID id) {
        long h = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits();
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }

    /** Its marks, read off what it is now. */
    public static EnumSet<Mark> marks(VillageFolkEntity f) {
        EnumSet<Mark> m = EnumSet.noneOf(Mark.class);
        Social.Life life = f.life();
        if (ambitious(f)) m.add(Mark.AMBITIOUS);
        if (life.has(Social.Trait.HARDWORKING)) m.add(Mark.HARDWORKING);
        if (life.has(Social.Trait.EASYGOING)) m.add(Mark.EASYGOING);
        if (life.has(Social.Trait.GRUMPY)) m.add(Mark.QUARRELSOME);
        if (life.has(Social.Trait.CHEERFUL) || life.has(Social.Trait.GENEROUS)) m.add(Mark.WELL_LIKED);
        int thrift = Bank.thrift(f);
        if (thrift >= 2) m.add(Mark.THRIFTY);
        else if (thrift <= -1) m.add(Mark.SPENDTHRIFT);
        if (f.knacks().has(FolkSkills.Knack.HAGGLER) || Quirks.has(f, Quirks.Quirk.SMOOTH_TALKER)) m.add(Mark.SHREWD);
        if (Quirks.has(f, Quirks.Quirk.BOOKWORM) || life.has(Social.Trait.CURIOUS)) m.add(Mark.CLEVER);
        if (Quirks.has(f, Quirks.Quirk.CLUMSY)) m.add(Mark.CARELESS);
        if (Quirks.has(f, Quirks.Quirk.FEARLESS) && thrift <= 0) m.add(Mark.RECKLESS);
        if (Quirks.has(f, Quirks.Quirk.HOMEBODY) || f.persona().rolled() && Values.top(f) == Values.Value.SAFETY) m.add(Mark.TIMID);
        return m;
    }

    /** The nerve its character adds to opening a business (ambition the most of it). */
    public static int drive(VillageFolkEntity f) {
        int n = 0;
        for (Mark k : marks(f)) n += k.drive;
        return n;
    }

    /** Its character in a few words, ambition first, the rest that tell in business: "ambitious, thrifty". */
    public static String words(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Mark k : marks(f)) out.add(k.word);
        return out.isEmpty() ? "steady" : String.join(", ", out);
    }

    // ------------------------------------------------------------------ an owner's marks on its firm

    /** The owners' marks, by folk, kept a while (read on every purchase a firm makes). */
    private static final Map<UUID, Object[]> CACHE = new ConcurrentHashMap<>();

    static EnumSet<Mark> marksOf(@Nullable VillageFolkEntity f) {
        if (f == null) return EnumSet.noneOf(Mark.class);
        long now = f.level().getGameTime();
        Object[] c = CACHE.get(f.getUUID());
        if (c != null && now - (Long) c[0] < 1200 && now >= (Long) c[0]) {
            @SuppressWarnings("unchecked") EnumSet<Mark> m = (EnumSet<Mark>) c[1];
            return m;
        }
        EnumSet<Mark> m = marks(f);
        if (CACHE.size() > 4096) CACHE.clear();
        CACHE.put(f.getUUID(), new Object[]{ now, m });
        return m;
    }

    /** A firm's founder (its owner who runs it), loaded, or null (a player's, the town's, one away). */
    @Nullable
    static VillageFolkEntity owner(UUID village, Business b) {
        net.minecraft.nbt.CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null || !r.hasUUID("founder")) return null;
        return Homes.loaded(village, r.getUUID("founder"));
    }

    /** What its owner pays at the market against the ask (0.95: five in the hundred under), its character's doing. */
    public static double costFactor(UUID village, Business b) {
        double c = 1.0;
        for (Mark k : marksOf(owner(village, b))) c += k.costs;
        return Math.max(0.85, Math.min(1.15, c));
    }

    /** More (or fewer) of a ware at a turn of its bench, by its owner's character. */
    public static int outputShift(UUID village, Business b) {
        int n = 0;
        for (Mark k : marksOf(owner(village, b))) n += k.output;
        return Math.max(-1, Math.min(1, n));
    }

    /** The chance a day a hand walks out on its owner. */
    public static double quitChance(UUID village, Business b) {
        double q = 0;
        for (Mark k : marksOf(owner(village, b))) q += k.quits;
        return Math.max(0, q);
    }

    /** The days more (or fewer) its creditors give it before they go to the court. */
    public static int staying(UUID village, Business b) {
        int n = 0;
        for (Mark k : marksOf(owner(village, b))) n += k.staying;
        return Math.max(-2, Math.min(2, n));
    }

    public static void resetForTests() {
        CACHE.clear();
    }
}
