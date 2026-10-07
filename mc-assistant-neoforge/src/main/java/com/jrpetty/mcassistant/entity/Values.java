package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.UUID;

/**
 * What a folk cares about, and so the kind of person it is.
 *
 * <p>Seven things a village can be run for: a full larder, a home for everybody, the next age,
 * safe streets, good wages and trade, rest and merriment, and the old ways. Each folk weighs them
 * (0 to 100 apiece) and the heaviest names its type: a Provider, a Homemaker, a Visionary, a
 * Guardian, a Merchant, a Free Spirit, a Traditionalist.
 *
 * <p>The weights are not fixed at birth. They start from the folk's two traits (and a little of its
 * own), and then its life moves them: a folk that has gone hungry minds the larder, one that has
 * slept on the ground wants homes built, one that has seen the raiders come wants the watch walking
 * the streets, one paid short wants better wages. Its trade teaches it too, more as it rises in it:
 * a farmer of long standing knows what a larder is for, a master miner what the next age is for.
 * And it listens to the people it loves: day by day a folk leans a little toward what its partner
 * and its best friend care about most, so a village grows its own camps.
 *
 * <p>What it cares about decides how it votes for its leader (Elections) and what it does with an
 * hour to itself (freeTime): a Guardian walks the wall, a Traditionalist sits by the well or the
 * chapel, a Visionary goes to see what is being built.
 */
public final class Values {

    private Values() {}

    public enum Value {
        FOOD("a full larder", "Provider", "the larder"),
        HOMES("a home for every family", "Homemaker", "homes"),
        PROGRESS("the next age", "Visionary", "the next age"),
        SAFETY("safe streets", "Guardian", "safety"),
        WEALTH("good wages and trade", "Merchant", "wages and trade"),
        LEISURE("rest and merriment", "Free Spirit", "rest and merriment"),
        TRADITION("the old ways", "Traditionalist", "the old ways");

        /** What it is a village run for: "a full larder". */
        public final String cares;
        /** The kind of folk it makes, whose heart is in it: "Provider". */
        public final String type;
        /** Short: "the larder". */
        public final String word;

        Value(String cares, String type, String word) {
            this.cares = cares;
            this.type = type;
            this.word = word;
        }
    }

    public static final int N = Value.values().length;
    static final int MOST = 100;

    // ------------------------------------------------------------------ where it starts

    /** What the folk's nature alone makes it care about. */
    static int[] base(Social.Life life, UUID id) {
        int[] v = new int[N];
        Arrays.fill(v, 20);
        for (Social.Trait t : life.traits()) {
            switch (t) {
                case HARDWORKING -> { add(v, Value.PROGRESS, 20); add(v, Value.WEALTH, 10); add(v, Value.LEISURE, -10); }
                case EASYGOING -> { add(v, Value.LEISURE, 20); add(v, Value.TRADITION, 10); add(v, Value.PROGRESS, -10); }
                case GRUMPY -> { add(v, Value.SAFETY, 20); add(v, Value.TRADITION, 10); add(v, Value.LEISURE, -5); }
                case CHEERFUL -> { add(v, Value.LEISURE, 12); add(v, Value.HOMES, 10); add(v, Value.FOOD, 5); }
                case SHY -> { add(v, Value.TRADITION, 20); add(v, Value.SAFETY, 10); add(v, Value.WEALTH, -5); }
                case SOCIABLE -> { add(v, Value.LEISURE, 10); add(v, Value.WEALTH, 10); add(v, Value.HOMES, 6); }
                case CURIOUS -> { add(v, Value.PROGRESS, 20); add(v, Value.WEALTH, 6); add(v, Value.TRADITION, -10); }
                case GENEROUS -> { add(v, Value.FOOD, 20); add(v, Value.HOMES, 14); add(v, Value.WEALTH, -5); }
            }
        }
        // A little of its own: the same folk always gets the same, so a reload changes nothing.
        long h = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        for (int i = 0; i < N; i++) v[i] = clamp(v[i] + (int) ((h >>> (i * 5)) & 7));
        return v;
    }

    /** What a trade teaches the ones who work at it, if anything. */
    @Nullable
    public static Value taughtBy(StationTask trade) {
        return switch (trade) {
            case FARM, FISH, HUNT, RANCH, COOK, BEEKEEP -> Value.FOOD;
            case WOOD -> Value.HOMES;
            case MINE, SMELT, SMITH, ENCHANT -> Value.PROGRESS;
            case GUARD, SCOUT -> Value.SAFETY;
            case CAVE -> Value.PROGRESS;                 // [caves] the ore the age wants
            case FLETCHER, GOLEMS -> Value.SAFETY;       // [fletcher] [golems] the town's defence
            case EMERALD -> Value.WEALTH;                // [emerald] a fair bargain, and what the town cannot make
            case SHOP, STORE, HAUL, TAILOR, BREW, BANK -> Value.WEALTH;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ reading it

    /** Its weights, set up from its nature the first time they are asked for. */
    public static int[] of(VillageFolkEntity f) {
        if (!f.valuesSet) {
            int[] b = base(f.life(), f.getUUID());
            System.arraycopy(b, 0, f.values, 0, N);
            f.valuesSet = true;
        }
        return f.values;
    }

    public static int weight(VillageFolkEntity f, Value v) {
        return of(f)[v.ordinal()];
    }

    /** What it cares about most. */
    public static Value top(VillageFolkEntity f) {
        return ranked(of(f))[0];
    }

    /** And second. */
    public static Value second(VillageFolkEntity f) {
        return ranked(of(f))[1];
    }

    static Value[] ranked(int[] w) {
        Value[] all = Value.values().clone();
        Arrays.sort(all, (a, b) -> w[b.ordinal()] != w[a.ordinal()]
            ? Integer.compare(w[b.ordinal()], w[a.ordinal()]) : Integer.compare(a.ordinal(), b.ordinal()));
        return all;
    }

    /** "Provider". */
    public static String type(VillageFolkEntity f) {
        return top(f).type;
    }

    /** "a Provider at heart, with a Guardian's eye for safety". */
    public static String describe(VillageFolkEntity f) {
        Value a = top(f), b = second(f);
        String article = "aeiou".indexOf(Character.toLowerCase(a.type.charAt(0))) >= 0 ? "an " : "a ";
        return article + a.type + " at heart, with a " + b.type + "'s care for " + b.word;
    }

    /** For the debug line: "Provider(food 62, safety 40)". */
    public static String brief(VillageFolkEntity f) {
        int[] w = of(f);
        Value[] r = ranked(w);
        return r[0].type.replace(' ', '-') + "(" + r[0].name().toLowerCase() + " " + w[r[0].ordinal()]
            + "," + r[1].name().toLowerCase() + " " + w[r[1].ordinal()] + ")";
    }

    // ------------------------------------------------------------------ how a life moves it

    /**
     * Once a day: what happened to it moves what it cares about. Hunger, a night on the ground,
     * the raiders, a short wage, a death, a feast; its trade, more as it rises in it; and what its
     * partner and best friend care about. Then all of it drifts a little back toward its nature, so
     * an old fright fades unless it comes again.
     */
    static void daily(ServerLevel level, VillageFolkEntity f, long day) {
        if (f.isBaby() || f.valuesDay == day) return;
        boolean first = f.valuesDay < 0;
        f.valuesDay = day;
        int[] w = of(f);
        if (first) return;                                      // its first day here: nothing has happened to it yet
        UUID village = f.ownerId();
        // What happened to it.
        if (f.countFood() == 0) learn(f, day, Value.FOOD, 3, "went hungry", 2);
        if (f.bedPos() == null) learn(f, day, Value.HOMES, 3, "slept on the ground", 2);
        Persona p = f.persona();
        if (day - p.hurtDay <= 1) learn(f, day, Value.SAFETY, 3, null, 0);
        if (village != null && Raids.underAlarm(village)) learn(f, day, Value.SAFETY, 4, "saw the raiders come", 3);
        if (day - f.shortPaidDay <= 1) learn(f, day, Value.WEALTH, 3, "was paid short", 2);
        if (day - f.griefDay <= 1) { add(w, Value.TRADITION, 2); add(w, Value.SAFETY, 1); }
        if (day - p.feastDay <= 1 || village != null && RestDay.justRested(village, day)) add(w, Value.LEISURE, 1);
        if (village != null && day - Villages.agedOn(village) <= 1) add(w, Value.PROGRESS, 2);
        // What its trade teaches it: a little a day, more for a master of it.
        Value taught = taughtBy(f.stationTask());
        int level_ = f.veteranLevel();
        if (taught != null) add(w, taught, level_ >= 20 ? 3 : level_ >= 8 ? 2 : 1);
        // What the people it loves care about.
        if (village != null) {
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity o) || o == f || o.isBaby()) continue;
                boolean partner = o.getUUID().equals(f.life().partner());
                boolean best = !partner && o.getUUID().equals(f.life().bestFriend());
                if (partner || best) add(w, top(o), partner ? 2 : 1);
            }
        }
        // And back toward its nature (and its trade's lesson), a point a day.
        int[] b = base(f.life(), f.getUUID());
        if (taught != null) b[taught.ordinal()] = clamp(b[taught.ordinal()] + Math.min(30, level_));
        for (int i = 0; i < N; i++) {
            if (w[i] > b[i]) w[i]--;
            else if (w[i] < b[i]) w[i]++;
            w[i] = clamp(w[i]);
        }
    }

    /** A lesson from its own life: weighs on what it cares about, and once in a while it remembers it. */
    static void learn(VillageFolkEntity f, long day, Value v, int by, @Nullable String memory, int weight) {
        add(of(f), v, by);
        if (memory != null && (day + f.getUUID().hashCode()) % 4 == 0) {
            f.persona().remember(day, "I " + memory + " on day " + day + ", and it stays with me", weight);
        }
    }

    /** Tests: set what it cares about. */
    public static void setForTests(VillageFolkEntity f, Value v, int weight) {
        of(f)[v.ordinal()] = clamp(weight);
    }

    // ------------------------------------------------------------------ what it does with an hour

    /**
     * Where its kind of folk goes with an hour to itself, now and then, or null: the wall for a
     * Guardian, the well or the chapel for a Traditionalist, whatever is going up for a Visionary,
     * the market for a Merchant, the tavern for a Free Spirit, the fields for a Provider and its
     * own front door for a Homemaker. Returns the spot and fills in what it is doing there.
     */
    @Nullable
    static BlockPos freeTime(ServerLevel level, VillageFolkEntity f, String[] doing) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        BlockPos c = v.centre();
        long h = f.getUUID().getLeastSignificantBits() ^ (level.getDayTime() / 24000L);
        switch (top(f)) {
            case SAFETY -> {
                // Along the wall: a point on the ring round the square, a different one each day.
                int side = (int) Math.floorMod(h, 4L), along = (int) Math.floorMod(h >> 3, 21L) - 10;
                int r = com.jrpetty.mcassistant.village.TownPlan.PLAZA - 1;
                int x = side == 0 ? r : side == 1 ? -r : along, z = side == 2 ? r : side == 3 ? -r : along;
                doing[0] = "walking the wall, keeping an eye out";
                return new BlockPos(c.getX() + x, c.getY(), c.getZ() + z);
            }
            case TRADITION -> {
                BlockPos at = Villages.builtAt(village, "chapel");
                if (at == null) at = Villages.builtAt(village, "well");
                if (at == null) at = Villages.builtAt(village, "graveyard");
                doing[0] = "sitting quietly where the old folk sit";
                return at;
            }
            case PROGRESS -> {
                for (Villages.Site s : Villages.sitesOf(village).values()) {
                    doing[0] = "watching the new building go up";
                    return s.anchor();
                }
                BlockPos at = Villages.builtAt(village, "smeltery");
                doing[0] = "looking over the works";
                return at;
            }
            case WEALTH -> {
                BlockPos at = Villages.builtAt(village, "market");
                if (at == null) at = Villages.builtAt(village, "shop");
                if (at == null) at = Villages.builtAt(village, "storage");
                doing[0] = "seeing what is for sale";
                return at;
            }
            case LEISURE -> {
                BlockPos at = Villages.builtAt(village, "tavern");
                if (at == null) at = Villages.builtAt(village, "cafe");
                if (at == null) at = Villages.builtAt(village, "fountain");
                doing[0] = "idling the hour away";
                return at;
            }
            case FOOD -> {
                for (AssistantEntity a : Villages.folkOf(village)) {
                    if (a.stationTask() == StationTask.FARM && a.workZone() != null) {
                        doing[0] = "looking over the fields";
                        return a.workZone().center();
                    }
                }
                return null;
            }
            case HOMES -> {
                BlockPos home = f.bedPos();
                doing[0] = "tidying round the house";
                return home;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static void add(int[] v, Value which, int by) {
        v[which.ordinal()] = clamp(v[which.ordinal()] + by);
    }

    static int clamp(int x) {
        return Math.max(0, Math.min(MOST, x));
    }
}
