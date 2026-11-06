package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a town knows of another: its scouts' last report, dated. A town knows nothing of a neighbour's
 * strength but what somebody went and saw; the report goes stale as the days pass, and a war council
 * that has none goes on rumour.
 *
 * <p>[war] The shared seam between the scouts and the war council and the fighting. Gathering it (the
 * scouts' missions, the watch catching spies, deception) belongs to the scouting work; everybody else
 * reads it. A report is kept with the world on the town's books (Ledger note "intel/&lt;other&gt;").
 *
 * <p>[war-scouting] What everybody else reads, besides the report itself:
 * <ul>
 * <li><b>The fog of war</b> ({@link #estimate}): what a leader believes of the other town today. A fresh
 *     report is believed as it stands; an older one may be out by more each day it has lain on the table
 *     (six in the hundred a day, never more than sixty), and with no report at all there is only rumour,
 *     out by half. The leader's temper bends the lot: a prickly one makes light of the enemy, a wary one
 *     sees twice the spears there are.</li>
 * <li><b>The strength reckoning</b> ({@link #strength}): our own strength, which we know exactly, against
 *     theirs as the leader believes it: guards, an armoured one worth more, a bowman more; walls and gates
 *     for whoever is behind them; days of food to sit out a standoff; allies. {@link Reckoning#balance} in
 *     one figure, {@link Reckoning#weaker} for which side should sue for peace, {@link #guardsNeeded} for
 *     how many guards a town wants to keep against what it believes of its rivals. The war council (going
 *     to war), the town's preparations (its guards) and the peace talks (who gives way) all go by it.</li>
 * <li><b>Acting on bad intelligence</b> ({@link #acted}, {@link #learned}, {@link #truth}): a town that went
 *     to war (or gave way) on what it believed is told how far out it was when it learns the truth (its
 *     next scout's count, a spy taken and questioned, the peace talks), in the chronicle.</li>
 * <li><b>Enemy folk seen</b> ({@link #sighted}, {@link #lastSighting}): where the pickets last saw folk of
 *     the enemy on the approaches (a scout, a herald, a party under arms), for the war map.</li>
 * </ul>
 */
public final class Intel {

    private Intel() {}

    /** A report this many days old is old: marked so on the war page and the board, and the council discounts it. */
    public static final int STALE = 5;
    /** How far out a report may have drifted for each day it has lain about, as a share of each figure. */
    static final double DRIFT = 0.06;
    /** The most a report drifts, however old: a town does not change out of all knowing in a month. */
    static final double DRIFT_MOST = 0.6;
    /** How far out rumour is. */
    static final double RUMOUR = 0.5;

    /** Tests: a leader's temper as it should be taken, by village. */
    private static final Map<UUID, Envoys.Temper> TEMPER = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TEMPER.clear();
    }

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

    // ------------------------------------------------------------------ [war-scouting] the age of a report

    /** Is the report old: past the days in which a council can still go by it as it stands? */
    public static boolean stale(Report r, long today) {
        return age(r, today) >= STALE;
    }

    /** How old a report is, as a folk says it: "today's", "a day old", "four days old", "old (nine days)". */
    public static String ageWords(Report r, long today) {
        long a = age(r, today);
        if (a == 0) return "today's";
        if (a == 1) return "a day old";
        if (a >= STALE) return "old (" + TownCalendar.inWords((int) Math.min(99, a)) + " days)";
        return TownCalendar.inWords((int) a) + " days old";
    }

    /** A report in a line: "six guards (four in iron, two with bows), a wall with three gates, food for about twenty days". */
    public static String summary(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append(count(r.guards(), "guard", "guards"));
        if (r.guards() > 0 && (r.armoured() > 0 || r.archers() > 0)) {
            sb.append(" (");
            if (r.armoured() > 0) sb.append(TownCalendar.inWords(r.armoured())).append(" in iron");
            if (r.armoured() > 0 && r.archers() > 0) sb.append(", ");
            if (r.archers() > 0) sb.append(TownCalendar.inWords(r.archers())).append(" with bows");
            sb.append(')');
        }
        sb.append(", ");
        if (r.walls() <= 0) sb.append("no wall");
        else sb.append(r.walls() >= 4 ? "a wall all round" : "a wall on " + TownCalendar.inWords(r.walls())
            + (r.walls() == 1 ? " side" : " sides")).append(r.gates() > 0 ? " with " + count(r.gates(), "gate", "gates") : ", no gates hung");
        if (r.foodDays() > 0) sb.append(", food for about ").append(TownCalendar.inWords(Math.min(99, r.foodDays()))).append(" days");
        if (r.folk() > 0) sb.append(", ").append(TownCalendar.inWords(Math.min(99, r.folk()))).append(" grown folk");
        return sb.toString();
    }

    private static String count(int n, String one, String many) {
        return (n == 0 ? "no" : TownCalendar.inWords(n)) + " " + (n == 1 ? one : many);
    }

    // ------------------------------------------------------------------ [war-scouting] the count from the world

    /** Is this folk one of a town's fighters: its watch, or (once there is one) its militia called up? */
    static boolean fighter(VillageFolkEntity f, Set<UUID> militia) {
        return f.stationTask() == AssistantEntity.StationTask.GUARD || militia.contains(f.getUUID());
    }

    /** Everybody who would fight for a town now, by id (WarFooting.militia). */
    static Set<UUID> militia(UUID village) {
        Set<UUID> out = new HashSet<>();
        for (VillageFolkEntity f : WarFooting.militia(village)) out.add(f.getUUID());
        return out;
    }

    /** In iron or better (a helmet or a breastplate that can be seen from afar). */
    public static boolean armoured(VillageFolkEntity f) {
        return ironOrBetter(f.getItemBySlot(EquipmentSlot.CHEST)) || ironOrBetter(f.getItemBySlot(EquipmentSlot.HEAD));
    }

    private static boolean ironOrBetter(ItemStack s) {
        if (s.isEmpty()) return false;
        String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return p.startsWith("iron_") || p.startsWith("diamond_") || p.startsWith("netherite_");
    }

    /** A bow or a crossbow in its hands or on its back. */
    public static boolean archer(VillageFolkEntity f) {
        return f.countCarried(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW)) > 0
            || f.getMainHandItem().is(Items.BOW) || f.getMainHandItem().is(Items.CROSSBOW);
    }

    /**
     * What anybody can see of a town's defences from outside it: {sections of wall, gates}. The wall's
     * four sides, each looked along at four places (a side with stone at two of them stands); its gates
     * as the watch keeps them; and the town's other works of defence (a palisade, a gatehouse, towers)
     * from its list of buildings, each a section more. Without the world to look at, the town's own books.
     */
    public static int[] walls(@Nullable ServerLevel level, UUID village) {
        int sides = 0, gates = 0;
        BlockPos a = Watch.wall(village);
        if (a != null) {
            if (level == null || !level.isLoaded(a)) {
                sides = 4;
            } else {
                for (Direction side : Watch.SIDES) {
                    int stone = 0;
                    for (int along : new int[]{ -10, -5, 5, 10 }) {
                        BlockPos c = Watch.cell(a, side, along);
                        if (level.isLoaded(c) && Watch.wallTop(level, c.getX(), c.getZ(), a.getY()) != null) stone++;
                    }
                    if (stone >= 2) sides++;
                }
                gates = Watch.gates(level, village).size();
            }
        }
        // (The watchtowers on the square's corners are the watch's lookouts, not a wall: they stand in towns
        // with none, and are not counted.)
        for (String s : Villages.builtList(village)) {
            if (s.equals("palisade") || s.equals("gatehouse") || s.endsWith("_tower")) sides++;
        }
        return new int[]{ sides, gates };
    }

    /** A town's own count of itself, from the world: what its own leader knows exactly. */
    public static Report exact(@Nullable ServerLevel level, UUID village, long day) {
        Set<UUID> militia = militia(village);
        int folk = 0, guards = 0, armoured = 0, archers = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isShowcase()) continue;
            folk++;
            if (!fighter(f, militia)) continue;
            guards++;
            if (armoured(f)) armoured++;
            if (archer(f)) archers++;
        }
        int[] w = walls(level, village);
        Leader.Books b = Leader.books(village);
        int food = b == null ? 0 : (int) Math.round(Math.max(0, Math.min(999, b.days())));
        return new Report(village, day, folk, guards, armoured, archers, w[0], w[1], food, "our own count");
    }

    /**
     * What is said of a town when nobody has been to look: how big it is (the caravans and the envoys
     * carry that), whether it has a wall (any traveller sees one), and a guess at the rest: a guard to
     * every seven folk, half of them in iron once it has iron, a bow to every three, food for twelve days.
     */
    public static Report rumour(UUID them, long today) {
        int folk = Villages.headcount(them);
        int guards = Math.max(1, Math.round(folk / 7.0F));
        boolean iron = Villages.ageOf(them).ordinal() >= Villages.Age.IRON.ordinal();
        int walls = Watch.wall(them) != null ? 4 : 0;
        return new Report(them, today, folk, guards, iron ? guards / 2 : 0, guards / 3, walls, walls > 0 ? 4 : 0, 12, "rumour");
    }

    // ------------------------------------------------------------------ [war-scouting] the fog of war

    /** The temper of a town's leader, as the fog of war takes it. */
    public static Envoys.Temper temper(UUID village) {
        Envoys.Temper t = TEMPER.get(village);
        return t != null ? t : Envoys.temper(village);
    }

    /** Tests: a town's leader taken to be of this temper (null: as it is). */
    public static void temperForTests(UUID village, @Nullable Envoys.Temper t) {
        if (t == null) TEMPER.remove(village);
        else TEMPER.put(village, t);
    }

    /** How far out a report this many days old may be, as a share of each figure. */
    public static double error(long age) {
        return Math.min(DRIFT_MOST, DRIFT * Math.max(0L, age));
    }

    /**
     * How a leader's temper bends what it believes of the enemy's fighters: under one, it makes light of
     * them (a prickly one, overconfident; a kindly one, a little); over one, it sees more than are there
     * (a wary one, timid). A shrewd or a steady one takes the figures as they come.
     */
    public static double bias(Envoys.Temper t) {
        return switch (t) {
            case PRICKLY -> 0.75;
            case WARY -> 1.3;
            case WARM, FRIENDLY, GENEROUS, EASY -> 0.95;
            default -> 1.0;
        };
    }

    /**
     * What this town's leader believes of that one today: the latest report as it has drifted with the
     * days it has lain about, or rumour when there is none, bent by the leader's temper. The same report
     * on the same day always reads the same (the drift is the report's own, and only grows with its age),
     * so the leader does not change its mind every time it is asked. The note says what it is built on.
     */
    public static Report estimate(UUID us, UUID them, long today) {
        Report r = latest(us, them);
        Envoys.Temper t = temper(us);
        if (r == null) {
            Report heard = rumour(them, today);
            return fog(heard, RUMOUR, us, them, today / 7, t, "rumour");
        }
        long a = age(r, today);
        return fog(r, error(a), us, them, r.day(), t, a >= STALE ? "an old report" : a == 0 ? "today's report" : "a report " + a + " days old");
    }

    /** A report as it stands, moved by so much each way (the report's own way, fixed by its day) and bent by a temper. */
    static Report fog(Report r, double err, UUID us, UUID them, long seed, Envoys.Temper t, String basis) {
        Random rng = new Random(us.getMostSignificantBits() * 31L + them.getLeastSignificantBits() * 17L + seed * 7919L);
        double bend = bias(t);
        int guards = drift(r.guards(), err, rng, bend);
        int armoured = Math.min(guards, drift(r.armoured(), err, rng, bend));
        int archers = Math.min(guards, drift(r.archers(), err, rng, bend));
        int folk = Math.max(guards, drift(r.folk(), err * 0.5, rng, 1.0));
        int food = drift(r.foodDays(), Math.min(0.9, err * 1.5), rng, 1.0);
        return new Report(r.about(), r.day(), folk, guards, armoured, archers, r.walls(), r.gates(), food, basis);
    }

    private static int drift(int n, double err, Random rng, double bend) {
        double way = rng.nextBoolean() ? 1.0 : -1.0;
        double k = 0.7 + 0.3 * rng.nextDouble();
        return Math.max(0, (int) Math.round(n * (1.0 + way * err * k) * bend));
    }

    // ------------------------------------------------------------------ [war-scouting] the strength reckoning

    /**
     * A town's strength as reckoned: what it has (exactly, for our own; as believed, for theirs), how
     * sure the reckoning is ({@code confidence}, one for our own or today's report, a third for rumour),
     * and what it was built on ({@code basis}).
     */
    public record Strength(UUID village, int folk, int guards, int armoured, int archers, int walls, int gates, int foodDays,
                           int allies, boolean exact, long age, double confidence, String basis) {

        /** In the field: its fighters (an armoured one worth more, a bowman more), the rest of its folk at a pinch, its allies' help. */
        public double field() {
            return guards + 0.6 * armoured + 0.4 * archers + 0.1 * Math.max(0, folk - guards) + 1.5 * allies;
        }

        /** What its walls and gates are worth to it behind them: up to four-fifths again. */
        public double defence() {
            int w = Math.min(4, Math.max(0, walls));
            return 1.0 + 0.15 * w + (w > 0 ? 0.05 * Math.min(4, Math.max(0, gates)) : 0.0);
        }

        /** Behind its walls: its strength in the field, its walls and gates, and how long its food holds out. */
        public double held() {
            return field() * defence() * Math.max(0.5, Math.min(1.25, foodDays / 14.0));
        }
    }

    /**
     * Our strength against theirs. {@code attack}: ours in the field over theirs behind their walls (over
     * one, we could take them); {@code defend}: ours behind our walls over theirs in the field (over one,
     * we could hold them off). {@code words} for the council, the war page and the leader.
     */
    public record Reckoning(Strength ours, Strength theirs, double attack, double defend, String words) {

        /**
         * The standoff in one figure: the mean (geometric) of what we could do to them and what we could
         * stand from them. Over one, we reckon ourselves the stronger; under one, the weaker.
         */
        public double balance() {
            return Math.sqrt(Math.max(0.0, attack) * Math.max(0.0, defend));
        }

        /** Do we reckon ourselves the weaker (the side that should sue for peace)? Under nine in ten. */
        public boolean weaker() {
            return balance() < 0.9;
        }

        /** Do we reckon ourselves clearly the stronger (enough to go to war on)? A quarter again or more. */
        public boolean stronger() {
            return balance() >= 1.25;
        }

        /** How far the reckoning can be trusted: one for a fresh report, a third for rumour (the council discounts it). */
        public double sure() {
            return theirs.confidence();
        }
    }

    /** Allied towns: they may send help (Envoys.allied). */
    public static int allies(UUID village) {
        int n = 0;
        for (Villages.Village v : Villages.every()) {
            if (!v.id().equals(village) && Envoys.allied(village, v.id()) && !Wars.atWar(village, v.id())) n++;
        }
        return n;
    }

    /** Our strength against theirs, today, as this town's leader would reckon it (see {@link Reckoning}). */
    public static Reckoning strength(ServerLevel level, UUID us, UUID them) {
        return reckon(level, us, them, level.getDayTime() / 24000L);
    }

    /** As {@link #strength(ServerLevel, UUID, UUID)}, without the world to look at (the walls by the books). */
    public static Reckoning strength(UUID us, UUID them, long today) {
        return reckon(null, us, them, today);
    }

    /**
     * How many guards this town wants to keep against that one, as it believes it to be: enough that behind
     * our walls we could hold off what they could bring against us with a fifth to spare (counting our
     * guards as they are kitted now, in iron and with bows in the same share). Never fewer than one; never
     * more than a third of the town's grown folk (a town must still eat), or than it keeps already if that is
     * more: against a much stronger enemy the answer is "all it can", and the rest is for the peace talks.
     */
    public static int guardsNeeded(@Nullable ServerLevel level, UUID us, UUID them, long today) {
        Reckoning r = reckon(level, us, them, today);
        Strength o = r.ours();
        double want = 1.2 * r.theirs().field();
        int most = Math.max(o.guards(), Math.max(1, o.folk() / 3));
        double ironShare = o.guards() == 0 ? 0.0 : o.armoured() / (double) o.guards();
        double bowShare = o.guards() == 0 ? 0.0 : o.archers() / (double) o.guards();
        for (int g = 1; g <= most; g++) {
            Strength t = new Strength(us, Math.max(o.folk(), g), g, (int) Math.round(g * ironShare), (int) Math.round(g * bowShare),
                o.walls(), o.gates(), o.foodDays(), o.allies(), true, 0, 1.0, o.basis());
            if (t.held() >= want) return g;
        }
        return most;
    }

    /** As {@link #guardsNeeded(ServerLevel, UUID, UUID, long)}, against the strongest of all its rivals (one if it has none). */
    public static int guardsNeeded(@Nullable ServerLevel level, UUID us, long today) {
        int n = 1;
        for (UUID r : Spying.rivals(us)) n = Math.max(n, guardsNeeded(level, us, r, today));
        return n;
    }

    static Reckoning reckon(@Nullable ServerLevel level, UUID us, UUID them, long today) {
        Report mine = exact(level, us, today);
        Strength ours = new Strength(us, mine.folk(), mine.guards(), mine.armoured(), mine.archers(), mine.walls(), mine.gates(),
            mine.foodDays(), allies(us), true, 0, 1.0, "our own count");
        Report seen = latest(us, them);
        Report bel = estimate(us, them, today);
        long a = seen == null ? -1 : age(seen, today);
        double sure = seen == null ? 0.3 : Math.max(0.3, 1.0 - error(a));
        Strength theirs = new Strength(them, bel.folk(), bel.guards(), bel.armoured(), bel.archers(), bel.walls(), bel.gates(),
            bel.foodDays(), allies(them), false, a, sure, bel.note());
        double attack = ours.field() / Math.max(0.5, theirs.held());
        double defend = ours.held() / Math.max(0.5, theirs.field());
        return new Reckoning(ours, theirs, attack, defend, words(us, them, ours, theirs, attack, defend, a));
    }

    private static String words(UUID us, UUID them, Strength ours, Strength theirs, double attack, double defend, long age) {
        String name = Villages.name(them);
        String odds = attack >= 1.5 ? "we are much the stronger of " + name
            : attack >= 1.1 ? "we have the better of " + name
            : attack >= 0.9 ? "we and " + name + " are evenly matched"
            : attack >= 0.6 ? name + " is the stronger"
            : name + " is much the stronger";
        String walls = theirs.walls() > 0 && ours.field() >= theirs.field() && attack < 1.1 ? ", behind its walls" : "";
        String home = defend >= 1.2 ? "; behind our own walls we could hold it off" : defend < 0.8 ? "; it could break us if it came" : "";
        String on = age < 0 ? " (on rumour alone)" : age >= STALE ? " (on an old report, " + age + " days)"
            : age == 0 ? " (on today's report)" : " (on a report " + age + (age == 1 ? " day" : " days") + " old)";
        return odds + walls + home + on;
    }

    // ------------------------------------------------------------------ [war-scouting] acting on it, and the truth

    /**
     * A town acted on what it believed of another (went to war with it, sued it for peace, kept so many
     * guards against it): what it believed of the other's guards is kept, so that when the truth is learned
     * the chronicle can say how far out it was. {@code deed} reads after "we": "went to war with", "sued for
     * peace with", "stood off".
     */
    public static void acted(UUID us, UUID them, long day, String deed) {
        if (us == null || them == null) return;
        Report e = estimate(us, them, day);
        Ledger.note(us, "intel.acted/" + them, day + ";" + e.guards() + ";" + deed.replace(";", ","));
    }

    /**
     * The truth about another town's guards, learned at last (a scout's fresh count, a spy questioned, the
     * peace talks). If the town had acted on a belief that was well out (by three or more, and a third or
     * more), its chronicle says so: "we went to war with Brindle thinking it held six guards; it held
     * fourteen". The belief is spent either way. Returns that line, or null if there was nothing to tell.
     */
    @Nullable
    public static String learned(UUID us, UUID them, int trueGuards, long day) {
        if (us == null || them == null) return null;
        String s = Ledger.note(us, "intel.acted/" + them);
        if (s == null || s.isEmpty()) return null;
        Ledger.forget(us, "intel.acted/" + them);
        String[] p = s.split(";", 3);
        if (p.length < 3) return null;
        int believed;
        try {
            believed = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        int off = Math.abs(believed - trueGuards);
        if (off < 3 || off < Math.max(1, trueGuards) / 3.0) return null;
        String line = "we " + p[2] + " " + Villages.name(them) + " thinking it held " + guardsWords(believed)
            + "; it held " + TownCalendar.inWords(Math.min(99, trueGuards));
        Villages.tell(us, day, line);
        return line;
    }

    private static String guardsWords(int n) {
        return (n == 0 ? "no" : TownCalendar.inWords(Math.min(99, n))) + (n == 1 ? " guard" : " guards");
    }

    /**
     * The truth told (the peace talks, where each side's envoys see the other's watch; a spy taken and
     * questioned): their strength as it is, filed as a fresh report with {@code how} for its note, and what
     * we had believed set against it in the chronicle. Returns the chronicle's line, or null.
     */
    @Nullable
    public static String truth(@Nullable ServerLevel level, UUID us, UUID them, long day, String how) {
        Report t = exact(level, them, day);
        file(us, new Report(them, day, t.folk(), t.guards(), t.armoured(), t.archers(), t.walls(), t.gates(), t.foodDays(), how));
        return learned(us, them, t.guards(), day);
    }

    // ------------------------------------------------------------------ [war-scouting] enemy folk seen

    /** Folk of that town seen on our approaches: when, where, how many, what they looked to be ("a scout", "a herald"), by whom. */
    public record Sighting(UUID about, long day, BlockPos at, int size, String what, String by) {
        String encode() {
            return day + ";" + at.getX() + ";" + at.getY() + ";" + at.getZ() + ";" + size + ";" + what.replace(";", ",") + ";" + by.replace(";", ",");
        }

        @Nullable
        static Sighting decode(UUID about, @Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(";", 7);
            if (p.length < 7) return null;
            try {
                return new Sighting(about, Long.parseLong(p[0]), new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                    Integer.parseInt(p[4]), p[5], p[6]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Folk of that town seen on the approaches (a picket's word): the last sighting kept. */
    public static void sighted(UUID us, Sighting s) {
        if (us == null || s == null) return;
        Ledger.note(us, "intel.seen/" + s.about(), s.encode());
    }

    /** Where folk of that town were last seen on the approaches, or null. */
    @Nullable
    public static Sighting lastSighting(UUID us, UUID them) {
        if (us == null || them == null) return null;
        return Sighting.decode(them, Ledger.note(us, "intel.seen/" + them));
    }
}
