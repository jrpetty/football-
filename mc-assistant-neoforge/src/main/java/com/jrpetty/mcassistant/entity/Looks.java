package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [individual] What a folk looks like, and why: the face it was born with, the way it wears its hair, and what its
 * years and its work have done to both.
 *
 * <p>Two parts, as in life:
 * <ul>
 * <li><b>Its genes</b> ({@link Genes}), settled when it is born and saved with it. A founder's are rolled from its own
 *     id, leaning a little to the land it came from (fairer in the snowfields and the pine woods, darker on the
 *     savanna and in the desert, every shade everywhere). A child's come from its parents: its skin a tone between
 *     theirs; an eye colour and a hair colour from each of them, the stronger showing (so a blue-eyed grandparent's
 *     eyes can turn up again in a grandchild of two brown-eyed parents); curls half from each; its face, its nose,
 *     its eyes' shape and its brows from one parent or the other, now and then its own; its height between theirs.
 *     Twins born together are alike in every one of them.</li>
 * <li><b>What shows</b> ({@link #pack}), worked out from the genes once a day and on the day anything changes: the
 *     hair it chooses to wear (a bun in old age for one who wore it long, thinning on top for a man with the gene for
 *     it), the beard it grows, the grey coming in at the temples from its fifties (sooner in a family that greys
 *     early) and white in the end, the lines of age, and its height, its build (a smith or a miner broadens over its
 *     years at the work) and a child's growing. Packed into one number the client draws the face from
 *     (client/FolkFaces); it changes only when the face does.</li>
 * </ul>
 * The marks a life leaves (a scar, an eyepatch, spectacles, soot, sunburn, a stick) are {@link Individual}'s.
 */
public final class Looks {

    private Looks() {}

    // BEGIN GENERATED WORDS
    public static final String[] TONE_WORDS = {"very fair", "fair", "light", "light olive", "olive", "tan", "light brown", "brown", "dark brown", "very dark"};
    public static final String[] HAIR_WORDS = {"black", "dark brown", "brown", "chestnut", "auburn", "red", "ginger", "blonde", "ash", "flaxen"};
    public static final String[] EYE_WORDS = {"brown", "dark", "hazel", "amber", "green", "blue", "grey"};
    public static final String[] FACE_WORDS = {"oval", "round", "square", "long", "heart"};
    public static final String[] NOSE_WORDS = {"straight", "broad", "button", "hooked"};
    public static final String[] EYE_SHAPE_WORDS = {"round", "wide", "narrow", "hooded", "deep", "almond"};
    public static final String[] BROW_WORDS = {"straight", "thick", "arched", "stern", "soft", "bushy", "joined"};
    public static final String[] STYLE_WORDS = {"crop", "short", "long", "braid", "bun", "curls", "shaved", "ponytail", "tied", "balding", "wild", "bob"};
    public static final String[] TEXTURE_WORDS = {"straight", "wavy", "curly"};
    public static final String[] FACIAL_WORDS = {"none", "stubble", "moustache", "beard", "long beard", "sideburns", "goatee"};
    public static final String[] SCAR_WORDS = {"none", "right cheek", "left cheek", "brow"};
    // END GENERATED WORDS

    // The hair styles, by number (tools/folk_looks.py STYLES).
    public static final int CROP = 0, SHORT = 1, LONG = 2, BRAID = 3, BUN = 4, CURLS = 5, SHAVED = 6, PONYTAIL = 7,
        TIED = 8, BALDING = 9, WILD = 10, BOB = 11;
    // The beards (FACIAL).
    public static final int NONE = 0, STUBBLE = 1, MOUSTACHE = 2, BEARD = 3, LONG_BEARD = 4, SIDEBURNS = 5, GOATEE = 6;
    /** The soft brows a child has, whatever brows it grows into. */
    static final int SOFT_BROWS = 4;

    /** Which hair colour shows when a folk carries two: the darker, mostly; red and ginger only with each other. */
    private static final int[] HAIR_RANK = {9, 8, 7, 6, 5, 1, 0, 3, 4, 2};
    /** Which eye colour shows: brown over hazel and amber, over green, over grey and blue. */
    private static final int[] EYE_RANK = {5, 6, 4, 3, 2, 0, 1};

    /** A folk's genes: what it was born with, saved with it, passed on to its children. */
    public static final class Genes {
        public boolean rolled;
        public boolean male;
        /** Its skin, 0 (very fair) to 9 (very dark). */
        public int tone;
        /** Two of each: one from each parent. */
        public int eyeA, eyeB, hairA, hairB, curlA, curlB;
        public boolean freckA, freckB;
        public int face, nose, eyeShape, brows;
        public boolean rosy;
        /** 0 for none, else where its mole sits (1 to 8). */
        public int mole;
        /** How tall it grows, -1 to 1 (its sex adds or takes a little); how broadly built, -1 to 1. */
        public float stature, frame;
        /** The gene for going bald (it shows only in a man of years). */
        public boolean balding;
        /** How early its family greys: 0 late, 1 as most do, 2 early. */
        public int greying;
        public boolean leftHanded;
        /** A little higher or lower a voice than its kind's, -1 to 1. */
        public float voice;
        /** How it likes to wear its hair, and (a man) its beard: its own choice, not its parents'. */
        public int style, facial;

        public void save(CompoundTag t) {
            t.putBoolean("Male", male);
            t.putInt("Tone", tone);
            t.putIntArray("Eyes", new int[]{eyeA, eyeB});
            t.putIntArray("Hair", new int[]{hairA, hairB});
            t.putIntArray("Curl", new int[]{curlA, curlB});
            t.putBoolean("FreckA", freckA);
            t.putBoolean("FreckB", freckB);
            t.putIntArray("Shape", new int[]{face, nose, eyeShape, brows});
            t.putBoolean("Rosy", rosy);
            t.putInt("Mole", mole);
            t.putFloat("Stature", stature);
            t.putFloat("Frame", frame);
            t.putBoolean("Balding", balding);
            t.putInt("Greying", greying);
            t.putBoolean("Left", leftHanded);
            t.putFloat("Voice", voice);
            t.putInt("Style", style);
            t.putInt("Facial", facial);
        }

        public void load(CompoundTag t) {
            if (!t.contains("Tone")) return;
            rolled = true;
            male = t.getBoolean("Male");
            tone = Mth.clamp(t.getInt("Tone"), 0, TONE_WORDS.length - 1);
            int[] e = pair(t.getIntArray("Eyes")), h = pair(t.getIntArray("Hair")), c = pair(t.getIntArray("Curl"));
            eyeA = clamp(e[0], EYE_WORDS);
            eyeB = clamp(e[1], EYE_WORDS);
            hairA = clamp(h[0], HAIR_WORDS);
            hairB = clamp(h[1], HAIR_WORDS);
            curlA = Mth.clamp(c[0], 0, 2);
            curlB = Mth.clamp(c[1], 0, 2);
            freckA = t.getBoolean("FreckA");
            freckB = t.getBoolean("FreckB");
            int[] s = t.getIntArray("Shape");
            if (s.length == 4) {
                face = clamp(s[0], FACE_WORDS);
                nose = clamp(s[1], NOSE_WORDS);
                eyeShape = clamp(s[2], EYE_SHAPE_WORDS);
                brows = clamp(s[3], BROW_WORDS);
            }
            rosy = t.getBoolean("Rosy");
            mole = Mth.clamp(t.getInt("Mole"), 0, 8);
            stature = Mth.clamp(t.getFloat("Stature"), -1, 1);
            frame = Mth.clamp(t.getFloat("Frame"), -1, 1);
            balding = t.getBoolean("Balding");
            greying = Mth.clamp(t.getInt("Greying"), 0, 2);
            leftHanded = t.getBoolean("Left");
            voice = Mth.clamp(t.getFloat("Voice"), -1, 1);
            style = clamp(t.getInt("Style"), STYLE_WORDS);
            facial = clamp(t.getInt("Facial"), FACIAL_WORDS);
        }

        /** A twin's genes: the same in everything. */
        public void copyFrom(Genes o) {
            CompoundTag t = new CompoundTag();
            o.save(t);
            load(t);
        }

        private static int[] pair(int[] a) { return a.length == 2 ? a : new int[]{0, 0}; }

        private static int clamp(int v, String[] of) { return Mth.clamp(v, 0, of.length - 1); }
    }

    // ------------------------------------------------------------------ born

    /**
     * A founder's genes, or one who came from away: rolled from its own id (the same folk is the same face, whenever
     * it is first looked at), leaning a little to the land its town stands on.
     */
    public static void founder(Genes g, UUID id, @Nullable Homeland.Land land) {
        RandomSource r = RandomSource.create(id.getMostSignificantBits() * 31L ^ id.getLeastSignificantBits());
        g.male = r.nextBoolean();
        int lean = land == null ? 0 : switch (land) {
            case SNOW, TAIGA -> -2;
            case MOUNTAIN, MEADOW -> -1;
            case DESERT, SAVANNA, BADLANDS -> 2;
            case JUNGLE, SWAMP -> 1;
            default -> 0;
        };
        g.tone = Mth.clamp(r.nextInt(10) + (r.nextInt(3) == 0 ? 0 : lean), 0, 9);
        boolean dark = g.tone >= 6, fair = g.tone <= 2;
        g.eyeA = eyeAllele(r, dark, fair);
        g.eyeB = eyeAllele(r, dark, fair);
        g.hairA = hairAllele(r, dark, fair);
        g.hairB = hairAllele(r, dark, fair);
        g.curlA = curlAllele(r, dark);
        g.curlB = curlAllele(r, dark);
        g.freckA = fair ? r.nextInt(3) == 0 : r.nextInt(10) == 0;
        g.freckB = fair ? r.nextInt(3) == 0 : r.nextInt(10) == 0;
        g.face = r.nextInt(FACE_WORDS.length);
        g.nose = r.nextInt(NOSE_WORDS.length);
        g.eyeShape = r.nextInt(EYE_SHAPE_WORDS.length);
        g.brows = browsFor(r, g.male);
        g.rosy = g.tone <= 3 && r.nextInt(4) == 0;
        g.mole = r.nextInt(7) == 0 ? 1 + r.nextInt(8) : 0;
        g.stature = Mth.clamp((float) r.nextGaussian() * 0.45F, -1, 1);
        g.frame = Mth.clamp((float) r.nextGaussian() * 0.45F, -1, 1);
        g.balding = r.nextInt(3) == 0;
        g.greying = r.nextInt(4) == 0 ? 2 : r.nextInt(4) == 0 ? 0 : 1;
        g.leftHanded = r.nextInt(11) == 0;
        g.voice = Mth.clamp((float) r.nextGaussian() * 0.4F, -1, 1);
        choose(g, r);
        g.rolled = true;
    }

    /** A child of these two: half of each, and now and then something of its own. */
    public static void child(Genes g, Genes a, Genes b, RandomSource r) {
        g.male = r.nextBoolean();
        int sum = a.tone + b.tone;
        g.tone = sum % 2 == 0 ? sum / 2 : (r.nextBoolean() ? sum / 2 : sum / 2 + 1);
        if (r.nextInt(7) == 0) g.tone = Mth.clamp(g.tone + (r.nextBoolean() ? 1 : -1), 0, 9);
        g.eyeA = r.nextBoolean() ? a.eyeA : a.eyeB;
        g.eyeB = r.nextBoolean() ? b.eyeA : b.eyeB;
        g.hairA = r.nextBoolean() ? a.hairA : a.hairB;
        g.hairB = r.nextBoolean() ? b.hairA : b.hairB;
        g.curlA = r.nextBoolean() ? a.curlA : a.curlB;
        g.curlB = r.nextBoolean() ? b.curlA : b.curlB;
        g.freckA = r.nextBoolean() ? a.freckA : a.freckB;
        g.freckB = r.nextBoolean() ? b.freckA : b.freckB;
        // The family likeness: each feature from one parent or the other, a few of its own.
        g.face = r.nextInt(10) == 0 ? r.nextInt(FACE_WORDS.length) : (r.nextBoolean() ? a : b).face;
        g.nose = r.nextInt(10) == 0 ? r.nextInt(NOSE_WORDS.length) : (r.nextBoolean() ? a : b).nose;
        g.eyeShape = r.nextInt(7) == 0 ? r.nextInt(EYE_SHAPE_WORDS.length) : (r.nextBoolean() ? a : b).eyeShape;
        g.brows = r.nextInt(5) == 0 ? browsFor(r, g.male) : (r.nextBoolean() ? a : b).brows;
        g.rosy = r.nextInt(10) < 7 ? (r.nextBoolean() ? a : b).rosy : g.tone <= 3 && r.nextInt(4) == 0;
        Genes withMole = a.mole > 0 ? a : b.mole > 0 ? b : null;
        g.mole = withMole != null && r.nextInt(10) < 3 ? withMole.mole : r.nextInt(16) == 0 ? 1 + r.nextInt(8) : 0;
        g.stature = Mth.clamp((a.stature + b.stature) / 2F + (float) r.nextGaussian() * 0.25F, -1, 1);
        g.frame = Mth.clamp((a.frame + b.frame) / 2F + (float) r.nextGaussian() * 0.25F, -1, 1);
        g.balding = r.nextBoolean() ? a.balding : b.balding;
        int grey = a.greying + b.greying;
        g.greying = grey % 2 == 0 ? grey / 2 : (r.nextBoolean() ? grey / 2 : grey / 2 + 1);
        int lefties = (a.leftHanded ? 1 : 0) + (b.leftHanded ? 1 : 0);
        g.leftHanded = r.nextInt(100) < 8 + 17 * lefties;
        g.voice = Mth.clamp((a.voice + b.voice) / 4F + (float) r.nextGaussian() * 0.3F, -1, 1);
        choose(g, r);
        g.rolled = true;
    }

    /** The way it likes its hair and its beard: its own choice, leaning to its sex and its curls. */
    static void choose(Genes g, RandomSource r) {
        int curl = texture(g);
        double[] w = g.male
            ? new double[]{3, 3, .7, .1, .2, 1.4 + curl, 1.2, .3, 1, 0, 1, .3}
            : new double[]{.4, .8, 3, 2, 2, 1.4 + curl, .1, 2, 1.2, 0, .6, 1.5};
        g.style = weighted(r, w);
        g.facial = g.male ? weighted(r, new double[]{4, 2, 1.2, 2.4, .6, .8, .8}) : NONE;
    }

    /** As it comes of age (VillageFolkEntity.childhood): a grown-up's choice of hair and beard, its own. */
    public static void comeOfAge(Genes g, RandomSource r) {
        if (r.nextInt(3) != 0) choose(g, r);
        else if (g.male) g.facial = weighted(r, new double[]{4, 2, 1.2, 2.4, .6, .8, .8});
    }

    private static int eyeAllele(RandomSource r, boolean dark, boolean fair) {
        if (dark && r.nextInt(10) < 9) return r.nextInt(3) == 0 ? 1 : r.nextInt(4) == 0 ? 2 : 0;
        double[] w = fair ? new double[]{2, 1, 2, 1, 2.5, 4, 2.5} : new double[]{5, 2, 3, 1, 2, 2, 1.2};
        return weighted(r, w);
    }

    private static int hairAllele(RandomSource r, boolean dark, boolean fair) {
        if (dark && r.nextInt(10) < 9) return r.nextInt(3) == 0 ? 1 : 0;
        double[] w = fair ? new double[]{1, 2, 3, 2, 1.5, 1.2, 1.5, 3, 1.5, 2} : new double[]{4, 5, 4, 2.5, 1.5, .6, .8, 1.5, 1, .6};
        return weighted(r, w);
    }

    private static int curlAllele(RandomSource r, boolean dark) {
        return weighted(r, dark ? new double[]{2, 2, 5} : new double[]{5, 3, 1.5});
    }

    private static int browsFor(RandomSource r, boolean male) {
        return weighted(r, male ? new double[]{4, 2.5, 1, 2.5, 1, 1, .3} : new double[]{4, .8, 3, 1, 3, .3, .2});
    }

    private static int weighted(RandomSource r, double[] w) {
        double sum = 0;
        for (double d : w) sum += d;
        double x = r.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            x -= w[i];
            if (x < 0) return i;
        }
        return w.length - 1;
    }

    // ------------------------------------------------------------------ what shows

    /** The hair colour that shows: the stronger of its two; red with brown makes auburn. */
    public static int hairColour(Genes g) {
        int a = g.hairA, b = g.hairB;
        boolean redA = a == 5 || a == 6, redB = b == 5 || b == 6;
        if (redA != redB) {
            int other = redA ? b : a;
            if (other == 1 || other == 2 || other == 3) return 4;          // red and brown: auburn
            if (other == 7 || other == 9) return 6;                        // red and fair: ginger
        }
        return HAIR_RANK[a] >= HAIR_RANK[b] ? a : b;
    }

    public static int eyeColour(Genes g) {
        return EYE_RANK[g.eyeA] >= EYE_RANK[g.eyeB] ? g.eyeA : g.eyeB;
    }

    /** Straight, wavy or curly: the two halves meet in the middle, curls winning a tie. */
    public static int texture(Genes g) {
        return (g.curlA + g.curlB + 1) / 2;
    }

    public static boolean freckled(Genes g) {
        if (g.freckA && g.freckB) return true;
        int hair = hairColour(g);
        return (g.freckA || g.freckB) && (hair == 4 || hair == 5 || hair == 6 || g.tone <= 1);
    }

    /** How it wears its hair at this age: a man with the gene thins on top from his forties, a long-haired woman
     *  puts it up in a bun in her seventies. */
    public static int styleAt(Genes g, int age, boolean child) {
        int s = g.style;
        if (child) return s == BALDING ? SHORT : s;
        if (g.male && g.balding && age >= 42 - (g.greying == 2 ? 5 : 0)) return BALDING;
        if (!g.male && age >= 70 && (s == LONG || s == WILD || s == BOB || s == PONYTAIL)) return BUN;
        return s;
    }

    /** The beard it wears at this age: none on a child or a woman; an old man's grows long; a woodcutter's is full. */
    public static int facialAt(Genes g, int age, boolean child, @Nullable AssistantEntity.StationTask trade) {
        if (!g.male || child || age < 17) return NONE;
        int f = g.facial;
        if (age >= 66 && f == BEARD) return LONG_BEARD;
        if (trade == AssistantEntity.StationTask.WOOD && (f == NONE || f == STUBBLE)) return BEARD;
        return f;
    }

    /** How grey: nought, then at the temples from its fifties (sooner in an early-greying family), white at the last. */
    public static int greyAt(Genes g, int age) {
        int onset = 54 - 8 * g.greying;
        if (age < onset) return 0;
        return Math.min(5, 1 + (age - onset) / 8);
    }

    /** The lines of age: from its forties, more in its sixties, deep at seventy-five. */
    public static int linesAt(int age) {
        return age < 42 ? 0 : age < 60 ? 1 : age < 75 ? 2 : 3;
    }

    /** Its grown height against a villager's, 0.90 to 1.08: between its parents', a man a little taller. */
    public static float height(Genes g) {
        return Mth.clamp(0.99F + g.stature * 0.055F + (g.male ? 0.022F : -0.022F), 0.90F, 1.08F);
    }

    /** Its height in sixteen steps (for the client). */
    public static int heightStep(Genes g) {
        return Mth.clamp(Math.round((height(g) - 0.90F) / 0.012F), 0, 15);
    }

    public static float heightOfStep(int step) {
        return 0.90F + step * 0.012F;
    }

    /** Slim (0), average (1) or broad (2): its frame, and years at heavy work. */
    public static int build(Genes g, @Nullable AssistantEntity.StationTask trade, int levelAtIt) {
        int b = g.frame < -0.35F ? 0 : g.frame > 0.4F ? 2 : 1;
        boolean heavy = trade == AssistantEntity.StationTask.SMITH || trade == AssistantEntity.StationTask.MINE
            || trade == AssistantEntity.StationTask.WOOD || trade == AssistantEntity.StationTask.CAVE;
        if (heavy && levelAtIt >= 10 && b < 2) b++;
        return b;
    }

    // ------------------------------------------------------------------ packed for the client
    //
    // One number for the face: the client builds its picture from it once and keeps it (FolkFaces).

    static final int B_MALE = 0, B_TONE = 1, B_EYE = 5, B_ESHAPE = 8, B_BROW = 11, B_HAIR = 14, B_STYLE = 18,
        B_TEX = 22, B_FACIAL = 24, B_FACE = 27, B_NOSE = 30, B_FRECK = 32, B_ROSY = 33, B_MOLE = 34, B_HEIGHT = 38,
        B_BUILD = 42, B_GREY = 44, B_LINES = 47;
    /** Set on any packed look: nought is "not known yet". */
    static final long KNOWN = 1L << 62;

    /** Its face as it is today. */
    public static long pack(Genes g, int age, boolean child, @Nullable AssistantEntity.StationTask trade, int levelAtIt) {
        long v = KNOWN;
        v |= (g.male ? 1L : 0L) << B_MALE;
        v |= (long) g.tone << B_TONE;
        v |= (long) eyeColour(g) << B_EYE;
        v |= (long) g.eyeShape << B_ESHAPE;
        v |= (long) (child ? SOFT_BROWS : g.brows) << B_BROW;
        v |= (long) hairColour(g) << B_HAIR;
        v |= (long) styleAt(g, age, child) << B_STYLE;
        v |= (long) texture(g) << B_TEX;
        v |= (long) facialAt(g, age, child, trade) << B_FACIAL;
        v |= (long) g.face << B_FACE;
        v |= (long) g.nose << B_NOSE;
        v |= (freckled(g) ? 1L : 0L) << B_FRECK;
        v |= (g.rosy || child && g.tone <= 4 ? 1L : 0L) << B_ROSY;
        v |= (long) g.mole << B_MOLE;
        v |= (long) heightStep(g) << B_HEIGHT;
        v |= (long) (child ? 1 : build(g, trade, levelAtIt)) << B_BUILD;
        v |= (long) (child ? 0 : greyAt(g, age)) << B_GREY;
        v |= (long) (child ? 0 : linesAt(age)) << B_LINES;
        return v;
    }

    public static boolean known(long v) { return (v & KNOWN) != 0; }
    public static boolean male(long v) { return (v >>> B_MALE & 1) != 0; }
    public static int tone(long v) { return (int) (v >>> B_TONE & 15); }
    public static int eye(long v) { return (int) (v >>> B_EYE & 7); }
    public static int eyeShape(long v) { return (int) (v >>> B_ESHAPE & 7); }
    public static int brows(long v) { return (int) (v >>> B_BROW & 7); }
    public static int hair(long v) { return (int) (v >>> B_HAIR & 15); }
    public static int style(long v) { return (int) (v >>> B_STYLE & 15); }
    public static int texture(long v) { return (int) (v >>> B_TEX & 3); }
    public static int facial(long v) { return (int) (v >>> B_FACIAL & 7); }
    public static int face(long v) { return (int) (v >>> B_FACE & 7); }
    public static int nose(long v) { return (int) (v >>> B_NOSE & 3); }
    public static boolean freckles(long v) { return (v >>> B_FRECK & 1) != 0; }
    public static boolean rosy(long v) { return (v >>> B_ROSY & 1) != 0; }
    public static int mole(long v) { return (int) (v >>> B_MOLE & 15); }
    public static int heightStepOf(long v) { return (int) (v >>> B_HEIGHT & 15); }
    public static int build(long v) { return (int) (v >>> B_BUILD & 3); }
    public static int grey(long v) { return (int) (v >>> B_GREY & 7); }
    public static int lines(long v) { return (int) (v >>> B_LINES & 3); }

    // ------------------------------------------------------------------ in words

    private static final String[] GREY_WORDS = {"", "greying at the temples", "flecked with grey", "gone salt and pepper",
        "mostly grey", "white"};
    private static final String[] STYLE_SAID = {"cropped short", "worn short", "worn long", "in a braid", "in a bun",
        "in curls", "shaved close", "in a ponytail", "tied back", "thinning on top", "a wild mop of it", "in a bob"};
    private static final String[] FACIAL_SAID = {"", "stubble", "a moustache", "a full beard", "a long beard", "sideburns",
        "a goatee"};

    /** "Tall and broad; dark brown skin; curly black hair in a bun, greying at the temples; hazel eyes; a full beard". */
    public static String describe(long v) {
        if (!known(v)) return "";
        List<String> parts = new ArrayList<>();
        float h = heightOfStep(heightStepOf(v));
        String tall = h < 0.935F ? "short" : h < 0.975F ? "on the short side" : h < 1.02F ? "of middling height"
            : h < 1.05F ? "tall" : "very tall";
        int b = build(v);
        parts.add(cap(tall + (b == 0 ? " and slim" : b == 2 ? " and broad" : "")));
        parts.add(TONE_WORDS[tone(v)] + " skin" + (freckles(v) ? ", freckled" : "") + (rosy(v) ? ", rosy-cheeked" : ""));
        int grey = grey(v), s = style(v);
        String colour = grey >= 5 ? "white" : HAIR_WORDS[hair(v)];
        String tex = texture(v) == 2 && s != CURLS ? "curly " : texture(v) == 1 ? "wavy " : "";
        String hair = s == SHAVED ? colour + " hair shaved close" : s == BALDING ? "balding, its " + colour + " hair thinning"
            : s == WILD ? "a wild mop of " + tex + colour + " hair" : tex + colour + " hair " + STYLE_SAID[s];
        if (grey > 0 && grey < 5) hair += ", " + GREY_WORDS[grey];
        parts.add(hair);
        parts.add(EYE_WORDS[eye(v)] + " eyes");
        int f = facial(v);
        if (f > 0) parts.add(FACIAL_SAID[f] + (grey >= 4 && f != STUBBLE ? ", grey" : ""));
        if (mole(v) > 0) parts.add("a mole");
        return String.join("; ", parts);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
