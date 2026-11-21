package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [identity] A town's character: seven axes, each from -100 to +100 (Identity.Rec.axes), the positive end first.
 *
 * <p><b>Where it comes from.</b> At the founding: the land (a port is mercantile and open, a hill-town martial and
 * practical, a desert town devout and old-fashioned, a meadow town peaceable and worldly) and the founders, what they
 * care for (a party of Guardians makes a martial, closed town; Merchants a mercantile, hierarchical one; Free Spirits
 * a worldly, open one) and their natures (the grumpy close the gates, the generous level the wages). Then, day by
 * day, it drifts toward what its folk care for and, more, toward its leader while in office (its values, and its
 * standing order: a leader who keeps the town on the watch makes it martial). And what happens to it moves it: a raid
 * makes it martial and wary, a war won warlike, a treaty peaceable, a trade deal mercantile, a book learned,
 * newcomers taken in open; who it elects, for what, too. Half of every event's push stays in the town's baseline: a
 * town remembers.
 *
 * <p><b>What each end does</b> (each modest, and scaled by how far the town leans, so two towns are measurably
 * different and neither is broken):
 * <ul>
 * <li><b>Mercantile:</b> a second market day in the week, keeps less back from the traders, keener envoys for trade,
 *     its leader orders the stalls filled. <b>Self-sufficient:</b> keeps up to three fifths more in its stores before it
 *     sells, values outsiders' goods less (it trusts its own makers), more farmers.</li>
 * <li><b>Martial:</b> up to three tenths more on the watch, a golem raised again sooner, a hawk at the council of war,
 *     its leader orders the walls manned. <b>Peaceable:</b> fewer guards, keener envoys and a peace sought in a feud, a
 *     dove at the council, happier while at peace.</li>
 * <li><b>Devout:</b> the chapel wanted from the Stone Age, fewer evenings at the tavern. <b>Worldly:</b> the tavern
 *     wanted at eight folk and the theatre early, three evenings in five at the tavern.</li>
 * <li><b>Learned:</b> the library and the school wanted early, more research a day. <b>Practical:</b> up to five in a
 *     hundred quicker at every trade.</li>
 * <li><b>Open:</b> newcomers voted in, players trusted half again as fast, tourists sooner, the gates open early and
 *     shut late. <b>Closed:</b> newcomers voted down, players trusted slowly, the gates shut at sunset, a little less
 *     crime.</li>
 * <li><b>Traditional:</b> four customs kept, fashion slow to change, the houses made over slowly. <b>Progressive:</b>
 *     more research, fashion quick, the houses made over sooner.</li>
 * <li><b>Egalitarian:</b> wages pulled toward the town's average, a coin more in the poor box from the well-off.
 *     <b>Hierarchical:</b> wages spread wider by rank, the leader's hall wanted early, only householders vote.</li>
 * </ul>
 */
public final class Ethos {

    private Ethos() {}

    /** One of the seven: its positive end, its negative end, and the words for each. */
    public enum Axis {
        TRADE("Mercantile", "Self-sufficient", "mercantile", "self-reliant"),
        WAR("Martial", "Peaceable", "martial", "peaceable"),
        FAITH("Devout", "Worldly", "devout", "worldly"),
        LEARNING("Learned", "Practical", "learned", "practical"),
        DOORS("Open", "Closed", "open", "closed"),
        WAYS("Traditional", "Progressive", "traditional", "forward-looking"),
        RANK("Egalitarian", "Hierarchical", "egalitarian", "hierarchical");

        public final String plus, minus, plusWord, minusWord;

        Axis(String plus, String minus, String plusWord, String minusWord) {
            this.plus = plus;
            this.minus = minus;
            this.plusWord = plusWord;
            this.minusWord = minusWord;
        }

        public String word(int lean) { return lean >= 0 ? plusWord : minusWord; }

        public String pole(int lean) { return lean >= 0 ? plus : minus; }
    }

    /** A town leans this far to be that thing outright (a pole: the laws, the early buildings, the chronicle). */
    public static final int POLE = 35;
    /** And this far to be called it in its character. */
    public static final int NAMED = 25;

    /** Each town's average day's pay (before the spread), worked out each morning, for the spread of its wages. */
    private static final Map<UUID, Double> AVERAGE_PAY = new ConcurrentHashMap<>();

    static void resetForTests() {
        AVERAGE_PAY.clear();
    }

    // ------------------------------------------------------------------ reading it

    /** How far the town leans on this axis, -100 to 100; 0 for a town not yet seeded (or a test's plain town). */
    public static int lean(@Nullable UUID village, Axis a) {
        if (Identity.neutral()) return 0;
        Identity.Rec r = Identity.known(village);
        return r == null ? 0 : r.axes[a.ordinal()];
    }

    /** As a share, -1 to 1. */
    static double s(@Nullable UUID village, Axis a) {
        return lean(village, a) / 100.0;
    }

    /** The positive end's share, 0 to 1 (0 when it leans the other way). */
    static double plus(@Nullable UUID village, Axis a) {
        return Math.max(0, s(village, a));
    }

    /** The negative end's share, 0 to 1. */
    static double minus(@Nullable UUID village, Axis a) {
        return Math.max(0, -s(village, a));
    }

    /** Does the town lean to this end outright (POLE or more)? {@code plus}: the first end. */
    public static boolean is(@Nullable UUID village, Axis a, boolean plusEnd) {
        int l = lean(village, a);
        return plusEnd ? l >= POLE : l <= -POLE;
    }

    // ------------------------------------------------------------------ the founding

    /** What a folk's values make of a town, on each axis (its cares against its average care). */
    static double[] folkLean(VillageFolkEntity f) {
        int[] w = Values.of(f);
        double mean = 0;
        for (int x : w) mean += x;
        mean /= w.length;
        double[] d = new double[w.length];
        for (int i = 0; i < w.length; i++) d[i] = w[i] - mean;
        double food = d[Values.Value.FOOD.ordinal()], homes = d[Values.Value.HOMES.ordinal()], prog = d[Values.Value.PROGRESS.ordinal()],
            safety = d[Values.Value.SAFETY.ordinal()], wealth = d[Values.Value.WEALTH.ordinal()], leisure = d[Values.Value.LEISURE.ordinal()],
            trad = d[Values.Value.TRADITION.ordinal()];
        double[] a = new double[Axis.values().length];
        a[Axis.TRADE.ordinal()] = 0.9 * wealth - 0.6 * food;
        a[Axis.WAR.ordinal()] = 0.9 * safety - 0.6 * leisure;
        a[Axis.FAITH.ordinal()] = 0.8 * trad - 0.5 * leisure;
        a[Axis.LEARNING.ordinal()] = 0.8 * prog - 0.3 * food - 0.3 * homes;
        a[Axis.DOORS.ordinal()] = 0.4 * wealth + 0.5 * leisure - 0.6 * safety - 0.4 * trad;
        a[Axis.WAYS.ordinal()] = 0.9 * trad - 0.8 * prog;
        a[Axis.RANK.ordinal()] = 0.5 * food + 0.6 * homes - 0.7 * wealth;
        return a;
    }

    /** What a folk's nature makes of a town. */
    static void natureLean(VillageFolkEntity f, double[] a, double by) {
        for (Social.Trait t : f.life().traits()) {
            switch (t) {
                case GRUMPY -> { a[Axis.WAR.ordinal()] += 4 * by; a[Axis.DOORS.ordinal()] -= 4 * by; }
                case GENEROUS -> { a[Axis.RANK.ordinal()] += 5 * by; a[Axis.DOORS.ordinal()] += 3 * by; }
                case CURIOUS -> { a[Axis.LEARNING.ordinal()] += 5 * by; a[Axis.WAYS.ordinal()] -= 4 * by; }
                case SHY -> { a[Axis.DOORS.ordinal()] -= 5 * by; a[Axis.FAITH.ordinal()] += 2 * by; }
                case SOCIABLE -> { a[Axis.DOORS.ordinal()] += 5 * by; a[Axis.FAITH.ordinal()] -= 3 * by; }
                case HARDWORKING -> { a[Axis.LEARNING.ordinal()] -= 4 * by; a[Axis.TRADE.ordinal()] += 2 * by; }
                case EASYGOING -> { a[Axis.WAR.ordinal()] -= 4 * by; a[Axis.FAITH.ordinal()] -= 2 * by; }
                case CHEERFUL -> { a[Axis.FAITH.ordinal()] -= 2 * by; a[Axis.DOORS.ordinal()] += 3 * by; }
            }
        }
    }

    /** What the land makes of a town that founds itself on it. */
    static void landLean(Homeland.Land l, double[] a) {
        int t = Axis.TRADE.ordinal(), w = Axis.WAR.ordinal(), f = Axis.FAITH.ordinal(), le = Axis.LEARNING.ordinal(),
            d = Axis.DOORS.ordinal(), y = Axis.WAYS.ordinal(), r = Axis.RANK.ordinal();
        switch (l) {
            case COAST -> { a[t] += 30; a[d] += 25; a[y] -= 10; }
            case RIVER -> { a[t] += 20; a[d] += 10; a[r] += 5; }
            case FOREST -> { a[t] -= 20; a[d] -= 15; a[y] += 10; }
            case TAIGA -> { a[t] -= 25; a[w] += 10; a[d] -= 10; }
            case SNOW -> { a[t] -= 30; a[d] -= 20; a[y] += 15; a[r] += 10; }
            case MOUNTAIN -> { a[w] += 20; a[le] -= 25; a[r] -= 15; a[d] -= 15; }
            case DESERT -> { a[f] += 25; a[d] -= 10; a[y] += 20; a[r] -= 10; }
            case SAVANNA -> { a[r] += 15; a[d] += 15; a[f] -= 10; a[w] += 5; }
            case JUNGLE -> { a[f] += 15; a[y] += 15; a[t] -= 10; }
            case SWAMP -> { a[d] -= 25; a[t] -= 15; a[f] += 10; }
            case BADLANDS -> { a[w] += 20; a[le] -= 15; a[r] -= 10; }
            case MEADOW -> { a[w] -= 25; a[f] -= 15; a[d] += 15; a[r] += 10; }
            case PLAINS -> { a[t] += 5; a[r] += 5; a[le] += 5; }
        }
    }

    /**
     * The town's ethos at its founding: its land, and its founders — what they care for, half again (a party of
     * like-minded founders is a strong start), and their natures — and a little of its own (the same town always
     * gets the same). Never more than seventy either way: a town has to earn the far ends.
     */
    static int[] seed(UUID village, List<VillageFolkEntity> founders) {
        double[] a = new double[Axis.values().length];
        landLean(Homeland.of(village), a);
        if (!founders.isEmpty()) {
            double[] sum = new double[a.length];
            double[] nature = new double[a.length];
            for (VillageFolkEntity f : founders) {
                double[] l = folkLean(f);
                for (int i = 0; i < a.length; i++) sum[i] += l[i];
                natureLean(f, nature, 1.0);
            }
            for (int i = 0; i < a.length; i++) a[i] += 1.5 * sum[i] / founders.size() + 3.0 * nature[i] / founders.size();
        }
        long h = village.getMostSignificantBits() ^ (village.getLeastSignificantBits() * 31L);
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            int jitter = (int) Math.floorMod(h >>> (i * 6), 17L) - 8;
            out[i] = clamp((int) Math.round(a[i]) + jitter, 70);
        }
        return out;
    }

    // ------------------------------------------------------------------ the drift

    /**
     * Once a morning: each axis moves toward where its people and its leader would have it — the town's baseline,
     * a third of what its folk care for and two thirds of what its leader cares for (twice what one folk counts for),
     * and its leader's standing order — up to two points a day; and every fifth day the baseline itself comes a point
     * toward the town as it is, so a long reign leaves its mark. A new age makes it a little more forward-looking.
     */
    static void drift(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        if (r.seededOn == day) return;
        UUID id = v.id();
        List<VillageFolkEntity> folk = Identity.grown(id);
        double[] pop = new double[r.axes.length];
        for (VillageFolkEntity f : folk) {
            double[] l = folkLean(f);
            for (int i = 0; i < pop.length; i++) pop[i] += l[i] / folk.size();
        }
        double[] lead = new double[r.axes.length];
        VillageFolkEntity leader = Identity.leader(id);
        if (leader != null && !leader.isBaby()) {
            double[] l = folkLean(leader);
            for (int i = 0; i < lead.length; i++) lead[i] = 2.0 * l[i];
            natureLean(leader, lead, 1.5);
        }
        double[] order = orderLean(Orders.current(id));
        for (int i = 0; i < r.axes.length; i++) {
            double target = r.seed[i] + 0.35 * pop[i] + 0.65 * lead[i] + order[i];
            double step = Math.max(-2.0, Math.min(2.0, (target - r.axes[i]) / 6.0));
            r.frac[i] += step;
            int whole = (int) r.frac[i];
            r.frac[i] -= whole;
            r.axes[i] = clamp(r.axes[i] + whole, 100);
            if (day % 5 == 0 && r.seed[i] != r.axes[i]) r.seed[i] = clamp(r.seed[i] + Integer.signum(r.axes[i] - r.seed[i]), 100);
        }
        if (Villages.agedOn(id) == day - 1) nudge(r, Axis.WAYS, -3);        // a new age yesterday: told once, the next morning
        // The day's pay, for the spread of the wages (Ethos.wage): the town's average before any spreading.
        double paid = 0;
        int n = 0;
        for (VillageFolkEntity f : folk) {
            if (f.stationTask() == StationTask.NONE) continue;
            paid += Wealth.earned(f);
            n++;
        }
        if (n > 0) AVERAGE_PAY.put(id, paid / n);
    }

    /** What the leader's standing order says of the town. */
    static double[] orderLean(@Nullable Orders.Order o) {
        double[] a = new double[Axis.values().length];
        if (o == null) return a;
        switch (o) {
            case WATCH -> a[Axis.WAR.ordinal()] += 15;
            case MARKET -> { a[Axis.TRADE.ordinal()] += 15; a[Axis.DOORS.ordinal()] += 5; }
            case LARDER -> a[Axis.TRADE.ordinal()] -= 8;
            case DIG -> a[Axis.LEARNING.ordinal()] -= 8;
            case STEADY -> a[Axis.WAYS.ordinal()] += 8;
            case HERDS, RIVER -> a[Axis.TRADE.ordinal()] -= 5;
            case TIMBER -> { }
        }
        return a;
    }

    /** A push from what happened: all of it now, half of it for good (the baseline). */
    static void nudge(Identity.Rec r, Axis a, int by) {
        r.axes[a.ordinal()] = clamp(r.axes[a.ordinal()] + by, 100);
        r.seed[a.ordinal()] = clamp(r.seed[a.ordinal()] + by / 2, 100);
    }

    /** What an event says of the town (Identity.event). */
    static void onEvent(Identity.Rec r, Identity.Ev e, long day) {
        if (!r.seeded) return;
        switch (e) {
            case RAID_HELD -> { nudge(r, Axis.WAR, 3); nudge(r, Axis.DOORS, -2); }
            case RAID_LOST -> { nudge(r, Axis.WAR, 4); nudge(r, Axis.DOORS, -4); }
            case WAR_WON -> nudge(r, Axis.WAR, 6);
            case WAR_LOST -> { nudge(r, Axis.WAR, -4); nudge(r, Axis.DOORS, -2); }
            case PEACE -> nudge(r, Axis.WAR, -5);
            case DEAL -> nudge(r, Axis.TRADE, 3);
            case VISITOR -> nudge(r, Axis.DOORS, 1);
            case HERO -> nudge(r, Axis.DOORS, 3);
            case GUEST, TAKEN_IN -> nudge(r, Axis.DOORS, e == Identity.Ev.GUEST ? 2 : 3);
            case TURNED_AWAY -> nudge(r, Axis.DOORS, -3);
            case BOOK -> nudge(r, Axis.LEARNING, 2);
            case FESTIVAL -> nudge(r, Axis.WAYS, 1);
            case DEATH, FIRE -> nudge(r, Axis.FAITH, 1);
            case BIG_WORK -> nudge(r, Axis.WAYS, -3);
            case FAME_FAIR -> nudge(r, Axis.TRADE, 1);
            default -> { }
        }
    }

    /** Elected, for something (the chronicle's "Fen was elected thane for safe streets"): the town leans that way. */
    static void elected(UUID village, String low, long day) {
        Identity.Rec r = Identity.known(village);
        if (r == null) return;
        for (Values.Value v : Values.Value.values()) {
            if (!low.contains(" for " + v.cares)) continue;
            switch (v) {
                case WEALTH -> { nudge(r, Axis.TRADE, 3); nudge(r, Axis.RANK, -2); }
                case SAFETY -> { nudge(r, Axis.WAR, 3); nudge(r, Axis.DOORS, -1); }
                case TRADITION -> { nudge(r, Axis.WAYS, 3); nudge(r, Axis.FAITH, 1); }
                case PROGRESS -> { nudge(r, Axis.WAYS, -3); nudge(r, Axis.LEARNING, 2); }
                case LEISURE -> { nudge(r, Axis.FAITH, -2); nudge(r, Axis.WAR, -1); }
                case FOOD -> { nudge(r, Axis.TRADE, -2); nudge(r, Axis.RANK, 1); }
                case HOMES -> nudge(r, Axis.RANK, 2);
            }
            Identity.dirty();
            return;
        }
    }

    // ------------------------------------------------------------------ the words for it

    /** Its poles, a bit an end (bit 2i: the first end of axis i, 2i+1 the second). */
    static int poleBits(int[] axes) {
        int bits = 0;
        for (int i = 0; i < axes.length; i++) {
            if (axes[i] >= POLE) bits |= 1 << (2 * i);
            if (axes[i] <= -POLE) bits |= 1 << (2 * i + 1);
        }
        return bits;
    }

    /** A pole gained or lost since the last look: into the chronicle (and the gazette), once. */
    static void tell(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        int now = poleBits(r.axes);
        if (now != r.poles && day > r.seededOn) {
            String name = Villages.name(v.id());
            for (int i = 0; i < r.axes.length; i++) {
                Axis a = Axis.values()[i];
                for (int end = 0; end < 2; end++) {
                    int bit = 1 << (2 * i + end);
                    String word = end == 0 ? a.plusWord : a.minusWord;
                    if ((now & bit) != 0 && (r.poles & bit) == 0) {
                        String line = name + " has grown " + word + ": " + effect(a, end == 0);
                        r.change(day, line);
                        Villages.tell(v.id(), day, line);
                    } else if ((now & bit) == 0 && (r.poles & bit) != 0) {
                        String line = name + " is not so " + word + " as it was";
                        r.change(day, line);
                        Villages.tell(v.id(), day, line);
                    }
                }
            }
        }
        r.poles = now;
        r.character = character(v.id(), r.axes);
    }

    /** What being that does, in a few words. */
    static String effect(Axis a, boolean plusEnd) {
        return switch (a) {
            case TRADE -> plusEnd ? "a second market day, keener traders" : "more kept back in the stores, its own makers trusted";
            case WAR -> plusEnd ? "more on the watch, and quicker to war" : "more envoys, slower to war, and glad of the peace";
            case FAITH -> plusEnd ? "the chapel early, fewer evenings at the tavern" : "the tavern and the theatre early, and full of an evening";
            case LEARNING -> plusEnd ? "the library and the school early, and more research" : "quicker at every trade";
            case DOORS -> plusEnd ? "newcomers and players welcomed, the gates open late" : "the gates shut at sunset, strangers kept at arm's length";
            case WAYS -> plusEnd ? "its customs kept, its fashions slow to change" : "quick to research, to fashion and to make the houses over";
            case RANK -> plusEnd ? "the wages drawn closer together, more in the poor box" : "the wages by rank, and the leader's hall early";
        };
    }

    /** "a martial, closed hill-town": its strongest two or three ends and its land; a seafaring town says so. */
    public static String character(UUID village, int[] axes) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < axes.length; i++) if (Math.abs(axes[i]) >= NAMED) order.add(i);
        order.sort((x, y) -> Integer.compare(Math.abs(axes[y]), Math.abs(axes[x])));
        List<String> words = new ArrayList<>();
        for (int i = 0; i < order.size() && words.size() < 3; i++) {
            int k = order.get(i);
            if (words.size() == 2 && Math.abs(axes[k]) < POLE) break;
            words.add(Axis.values()[k].word(axes[k]));
        }
        String noun = noun(Homeland.of(village));
        String adj = TownTraits.adjective(Identity.known(village));
        if (adj != null) noun = adj + " " + noun;
        String body = words.isEmpty() ? "quiet " + noun : String.join(", ", words) + " " + noun;
        return ("aeiou".indexOf(body.charAt(0)) >= 0 ? "an " : "a ") + body;
    }

    static String noun(Homeland.Land l) {
        return switch (l) {
            case COAST -> "port";
            case RIVER -> "river town";
            case FOREST -> "forest town";
            case TAIGA -> "pine-wood town";
            case SNOW -> "snow-town";
            case MOUNTAIN -> "hill-town";
            case DESERT -> "desert town";
            case SAVANNA -> "herding town";
            case JUNGLE -> "jungle town";
            case SWAMP -> "fen-town";
            case BADLANDS -> "mesa town";
            case MEADOW -> "meadow town";
            case PLAINS -> "farming town";
        };
    }

    /** The word for its strongest end: "martial". */
    static String strongest(int[] axes) {
        int best = 0;
        for (int i = 1; i < axes.length; i++) if (Math.abs(axes[i]) > Math.abs(axes[best])) best = i;
        return Axis.values()[best].word(axes[best]);
    }

    /** How well a folk's own leanings sit with its town's, -100ish to 100ish: a true son of it, or a grumbler. */
    static int fit(VillageFolkEntity f, int[] axes) {
        double[] l = folkLean(f);
        double dot = 0, norm = 0;
        for (int i = 0; i < axes.length; i++) {
            dot += l[i] * axes[i];
            norm += Math.abs(axes[i]);
        }
        return norm < 1 ? 0 : (int) Math.round(dot / norm * 2.0);
    }

    /** How a folk would have its town instead: "more open". */
    static String wish(VillageFolkEntity f) {
        double[] l = folkLean(f);
        int best = 0;
        for (int i = 1; i < l.length; i++) if (Math.abs(l[i]) > Math.abs(l[best])) best = i;
        return "more " + Axis.values()[best].word((int) Math.round(l[best]));
    }

    /** "Mercantile 42, Martial -12 (Peaceable 12), ..." for the status line. */
    static String line(int[] axes) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < axes.length; i++) {
            Axis a = Axis.values()[i];
            out.add(a.pole(axes[i]) + " " + Math.abs(axes[i]));
        }
        return String.join(", ", out);
    }

    /** The bars of the Identity page: each axis, its two ends, where the town stands, where it started, and why. */
    static ListTag report(UUID village, Identity.Rec r) {
        ListTag out = new ListTag();
        for (int i = 0; i < r.axes.length; i++) {
            Axis a = Axis.values()[i];
            CompoundTag t = new CompoundTag();
            t.putString("plus", a.plus);
            t.putString("minus", a.minus);
            t.putInt("value", r.axes[i]);
            t.putInt("seed", r.seed[i]);
            t.putString("does", Math.abs(r.axes[i]) >= POLE ? effect(a, r.axes[i] > 0) : Math.abs(r.axes[i]) >= NAMED
                ? "leaning " + a.word(r.axes[i]) + "; at " + POLE + " it is " + a.word(r.axes[i]) + " outright" : "neither one nor the other");
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ what it does: the trades

    /**
     * How much more (or less) of a trade the town's character calls for (Villages.target): a martial town keeps up to
     * three tenths more on the watch (a peaceable one a fifth fewer), a mercantile one a little more behind the counter
     * and at the loom, a self-sufficient one more in the fields; and the hunters by the law on hunting (LawBook).
     */
    public static double shareLean(@Nullable UUID village, StationTask t) {
        if (Identity.neutral() || Identity.known(village) == null) return 1.0;
        double x = 1.0;
        switch (t) {
            case GUARD -> x *= 1.0 + 0.3 * plus(village, Axis.WAR) - 0.2 * minus(village, Axis.WAR);
            case SHOP, TAILOR, BREW -> x *= 1.0 + 0.15 * plus(village, Axis.TRADE);
            case FARM -> x *= 1.0 + 0.1 * minus(village, Axis.TRADE);
            default -> { }
        }
        x *= LawBook.shareLean(village, t);
        x *= TownTraits.shareLean(village, t);
        return x;
    }

    /** Whole hands more of a trade than its share (an Iron-willed town's extra guard: TownTraits). */
    public static double extraHands(@Nullable UUID village, StationTask t) {
        return TownTraits.extraHands(village, t);
    }

    /**
     * What the town's character and history do for the pace of a trade's work (VillageFolkEntity.skillWorkPercent):
     * a practical town up to five in a hundred quicker at everything; and the trades its traits are proud of
     * (Golden Fields, Deep Delvers, Seafarers) five more.
     */
    public static int workPercent(@Nullable UUID village, StationTask t) {
        if (Identity.neutral() || village == null || t == StationTask.NONE) return 0;
        int p = (int) Math.round(5.0 * minus(village, Axis.LEARNING));
        return p + TownTraits.workPercent(village, t);
    }

    /** The day's research points it adds (CityTree.rate): learned up to two, progressive one, bookish one; very practical one less. */
    public static int research(@Nullable UUID village, List<String> why) {
        if (Identity.neutral() || Identity.known(village) == null) return 0;
        int n = 0;
        int l = lean(village, Axis.LEARNING), w = lean(village, Axis.WAYS);
        if (l >= 70) { n += 2; why.add("a learned town 2"); }
        else if (l >= POLE) { n++; why.add("a learned town 1"); }
        else if (l <= -60) { n--; why.add("a practical town, short of books -1"); }
        if (w <= -POLE) { n++; why.add("a forward-looking town 1"); }
        Identity.Rec r = Identity.known(village);
        if (r != null && r.has(TownTraits.Trait.BOOKISH)) { n++; why.add("Bookish 1"); }
        return n;
    }

    // ------------------------------------------------------------------ fashion, customs, the houses

    /** How quickly the season's look goes round (Fashion.pull): half again in a forward-looking town, half in a traditional. */
    public static double fashionPace(@Nullable UUID village) {
        return 1.0 + 0.5 * minus(village, Axis.WAYS) - 0.5 * plus(village, Axis.WAYS);
    }

    /** How many customs the town keeps (Traditions): four if traditional, two if forward-looking, else three. */
    public static int customsKept(@Nullable UUID village, int usual) {
        int w = lean(village, Axis.WAYS);
        return w >= POLE ? usual + 1 : w <= -POLE ? Math.max(1, usual - 1) : usual;
    }

    /** Ticks between the builders' rounds of the houses (Grow.tick): sooner in a forward-looking town, later in a traditional one. */
    public static long growEvery(@Nullable UUID village, long usual) {
        return Math.round(usual * (1.0 - 0.33 * minus(village, Axis.WAYS) + 0.6 * plus(village, Axis.WAYS)));
    }

    /** Blocks a round of making the houses over may lay (Grow.work). */
    public static int growBudget(@Nullable UUID village, int usual) {
        return Math.max(4, (int) Math.round(usual + 8 * minus(village, Axis.WAYS) - 8 * plus(village, Axis.WAYS))
            + TownTraits.growBudget(village));
    }

    // ------------------------------------------------------------------ wages and the poor box

    /**
     * A folk's wage, spread by the town's character (Wealth.wage): an egalitarian town pulls it toward the town's
     * average (half the gap, at the far end), a hierarchical one pushes it out (two fifths again); a commune pays
     * everybody the same, the town's average.
     */
    public static int wage(VillageFolkEntity f, int w) {
        UUID village = f.ownerId();
        if (w <= 0 || Identity.neutral() || Identity.known(village) == null) return w;
        Double avg = AVERAGE_PAY.get(village);
        if (avg == null || avg <= 0) return w;
        return spread(village, w, avg);
    }

    static int spread(UUID village, int w, double avg) {
        if (Government.of(village) == Government.Form.COMMUNE) return Math.max(1, (int) Math.round(avg));
        double e = s(village, Axis.RANK);
        double factor = e >= 0 ? 1.0 - 0.5 * e : 1.0 + 0.4 * -e;
        return Math.max(1, (int) Math.round(avg + (w - avg) * factor));
    }

    /** What a well-off folk puts in the poor box a week (PoorBox.share): a coin more in an egalitarian town. */
    public static int alms(VillageFolkEntity f, int n) {
        if (n <= 0) return n;
        return lean(f.ownerId(), Axis.RANK) >= POLE && f.purse() >= n + 1 + PoorBox.KEEPS ? n + 1 : n;
    }

    // ------------------------------------------------------------------ newcomers, visitors, the gates, crime

    /**
     * What the town's character says to newcomers asking to settle (Newcomers.judge): an open town up to thirty-five
     * points for, a closed one up to thirty-five against; the law on the borders thirty more against when closed;
     * the Hospitable eight for, the Raid-scarred eight against.
     */
    public static double newcomerLean(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 0;
        double x = 0.35 * lean(village, Axis.DOORS);
        if (LawBook.bordersClosed(village)) x -= 30;
        Identity.Rec r = Identity.known(village);
        if (r.has(TownTraits.Trait.HOSPITABLE)) x += 8;
        if (r.has(TownTraits.Trait.RAID_SCARRED)) x -= 8;
        return x;
    }

    /** The reason a folk gives for that. */
    public static String newcomerWhy(@Nullable UUID village, boolean forThem) {
        String name = village == null ? "this town" : Villages.name(village);
        if (forThem) return Identity.known(village) != null && Identity.known(village).has(TownTraits.Trait.HOSPITABLE)
            ? name + " has always kept an open door" : "we're an open town: there's room at the table for anybody";
        if (LawBook.bordersClosed(village)) return "the borders are closed, and that's the law";
        return Identity.known(village) != null && Identity.known(village).has(TownTraits.Trait.RAID_SCARRED)
            ? "the last strangers through the gate were raiders" : name + " keeps to its own";
    }

    /** Days added to (or taken off) the gap between tourists (Tourists.consider): an open town sooner, a closed later. */
    public static int tourGap(@Nullable UUID village) {
        int d = lean(village, Axis.DOORS);
        int gap = d >= POLE ? -1 : d <= -POLE ? 2 : 0;
        Identity.Rec r = Identity.known(village);
        if (r != null && !Identity.neutral() && r.has(TownTraits.Trait.HOSPITABLE)) gap--;
        return gap;
    }

    /** When the gates shut (Raids): at dusk, thirteen thousand; a closed town at sunset, an open one later; a curfew sooner. */
    public static long gatesShut(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 13000L;
        double d = s(village, Axis.DOORS);
        long t = Math.round(13000L + (d >= 0 ? 500.0 * d : 1000.0 * d));
        if (LawBook.curfew(village)) t -= 300L;
        Identity.Rec r = Identity.known(village);
        if (r != null && r.has(TownTraits.Trait.RAID_SCARRED)) t -= 300L;
        return Math.max(11500L, t);
    }

    /** When they open again in the morning: an open town at first light, a closed one later. */
    public static long gatesOpen(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 23000L;
        double d = s(village, Axis.DOORS);
        return Math.round(23000L + (d >= 0 ? -1000.0 * d : 500.0 * -d));
    }

    /** What the town's ways keep crime down by (Mischief.prevention): a curfew a fifth, a closed town up to a sixth. */
    public static double crimeFactor(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 1.0;
        double x = 1.0 - 0.15 * minus(village, Axis.DOORS);
        if (LawBook.curfew(village)) x *= 0.8;
        if (LawBook.watchAlone(village)) x *= 0.95;
        return x;
    }

    // ------------------------------------------------------------------ war and the neighbours

    /** What the town's character adds to its leader's readiness to fight (WarAndPeace.stance). */
    public static int warLean(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 0;
        int w = lean(village, Axis.WAR);
        int h = w >= 60 ? 2 : w >= 30 ? 1 : w <= -60 ? -2 : w <= -30 ? -1 : 0;
        return h + TownTraits.warLean(village);
    }

    /** How much keener (or less keen) its leader is to send an envoy (Envoys.choose). */
    public static int envoyKeen(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 0;
        int k = 0;
        if (lean(village, Axis.WAR) <= -30) k++;
        if (lean(village, Axis.TRADE) >= 30) k++;
        if (lean(village, Axis.DOORS) <= -40) k--;
        if (Identity.known(village).has(TownTraits.Trait.PEACEMAKERS)) k++;
        return k;
    }

    /** Does a feud make this town's leader sue for peace (a peaceable town, Envoys.choose)? */
    public static boolean seeksPeace(@Nullable UUID village) {
        return lean(village, Axis.WAR) <= -30;
    }

    /** What the two towns' characters do to how they get on, a day (Diplomacy.daily). */
    public static int relationLean(UUID a, UUID b) {
        if (Identity.neutral() || Identity.known(a) == null || Identity.known(b) == null) return 0;
        int d = 0;
        int wa = lean(a, Axis.WAR), wb = lean(b, Axis.WAR);
        if (wa <= -POLE && wb <= -POLE) d++;                      // two peaceable towns
        if (wa >= POLE && wb >= POLE) d--;                         // two martial ones bristle
        if (lean(a, Axis.DOORS) <= -POLE || lean(b, Axis.DOORS) <= -POLE) d--;
        if (lean(a, Axis.TRADE) >= POLE && lean(b, Axis.TRADE) >= POLE) d++;
        return d + TownTraits.relationLean(a, b) + Fame.esteem(a, b);
    }

    // ------------------------------------------------------------------ the market

    /** A second market day in the week for a mercantile town (Market.marketDay): its fourth day after its first. */
    public static boolean extraMarket(@Nullable UUID village, long day) {
        return village != null && is(village, Axis.TRADE, true) && Math.floorMod(day + village.hashCode(), 7L) == 3;
    }

    /**
     * What it keeps back from the traders, against the usual (Market.sellSurplus, Market.trade): a self-sufficient
     * town up to three fifths more, a mercantile one a quarter less.
     */
    public static double keepFactor(@Nullable UUID village) {
        return 1.0 + 0.6 * minus(village, Axis.TRADE) - 0.25 * plus(village, Axis.TRADE);
    }

    /** Evenings in five more (or fewer) at the tavern (Tavern.goingTonight): worldly one more, devout one fewer. */
    public static int tavernNights(@Nullable UUID village) {
        int f = lean(village, Axis.FAITH);
        int n = f <= -POLE ? 1 : f >= POLE ? -1 : 0;
        Identity.Rec r = Identity.known(village);
        if (r != null && !Identity.neutral() && r.has(TownTraits.Trait.MOURNING)) n--;
        return n;
    }

    /** Days between iron golems (TownWork.golem): a martial town raises one again the next day, a peaceable one waits. */
    public static int golemGap(@Nullable UUID village, int usual) {
        int w = lean(village, Axis.WAR);
        return w >= POLE ? 1 : w <= -POLE ? usual + 2 : usual;
    }

    /** What the town's character adds to its leader's choice of order (Orders.choose). */
    public static void orders(@Nullable UUID village, Map<Orders.Order, Integer> score) {
        if (Identity.neutral() || Identity.known(village) == null) return;
        score.merge(Orders.Order.WATCH, (int) Math.round(2 * plus(village, Axis.WAR)), Integer::sum);
        score.merge(Orders.Order.MARKET, (int) Math.round(2 * plus(village, Axis.TRADE)), Integer::sum);
        score.merge(Orders.Order.LARDER, (int) Math.round(1 * minus(village, Axis.TRADE)), Integer::sum);
        score.merge(Orders.Order.STEADY, (int) Math.round(2 * plus(village, Axis.WAYS)), Integer::sum);
        Government.orders(village, score);
    }

    // ------------------------------------------------------------------ the buildings it wants

    /**
     * The buildings its character wants early, onto the list of amenities, and its favourites put first
     * (Villages.projectsWantedInOrder, from the Stone Age): a devout town (or the chaplain's) its chapel; a worldly one
     * its tavern at eight and its theatre; a learned one its library at nine and its school; a hierarchical one the
     * leader's hall at twelve.
     */
    public static void extras(@Nullable UUID village, int folk, Villages.Age at, List<String> extras, Predicate<String> unbuilt) {
        if (Identity.neutral() || Identity.known(village) == null) return;
        List<String> favour = new ArrayList<>();
        boolean devout = is(village, Axis.FAITH, true) || Government.of(village) == Government.Form.CHAPLAIN;
        if (devout && folk >= 12 && at.ordinal() < Villages.Age.DIAMOND.ordinal() && unbuilt.test("chapel")) favour.add("chapel");
        if (is(village, Axis.FAITH, false)) {
            if (folk >= 8 && unbuilt.test("tavern")) favour.add("tavern");
            if (at.ordinal() >= Villages.Age.IRON.ordinal() && folk >= 16 && unbuilt.test(Theatre.STRUCTURE)) favour.add(Theatre.STRUCTURE);
        }
        if (is(village, Axis.LEARNING, true)) {
            if (folk >= 9 && unbuilt.test(Library.STRUCTURE)) favour.add(Library.STRUCTURE);
            int children = 0;
            for (AssistantEntity a : Villages.folkOf(village)) if (a.isBaby()) children++;
            if (folk >= 10 && children >= 1 && unbuilt.test("school")) favour.add("school");
        }
        if (is(village, Axis.RANK, false) && folk >= 12 && Villages.hasBuilt(village, "hall") && unbuilt.test("townhall")) favour.add("townhall");
        if (favour.isEmpty()) return;
        extras.removeAll(favour);
        int at0 = !extras.isEmpty() && extras.get(0).equals("graveyard") ? 1 : 0;
        extras.addAll(at0, favour);
    }

    // ------------------------------------------------------------------ helpers

    static int clamp(int x, int most) {
        return Math.max(-most, Math.min(most, x));
    }

    /** Tests: set an axis (and its baseline) outright. */
    public static void setForTests(UUID village, Axis a, int value) {
        Identity.Rec r = Identity.rec(village);
        r.axes[a.ordinal()] = clamp(value, 100);
        r.seed[a.ordinal()] = clamp(value, 100);
        r.seeded = true;
        Identity.dirty();
    }

    /** Tests: the wage this town would pay for w, the town's average being avg. */
    public static int wageForTests(UUID village, int w, double avg) {
        return spread(village, w, avg);
    }

    /** "martial 42" words for an axis, as the tests log them. */
    public static String words(UUID village) {
        Identity.Rec r = Identity.known(village);
        return r == null ? "unseeded" : line(r.axes).toLowerCase(Locale.ROOT);
    }
}
