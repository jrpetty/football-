package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The job market between towns: the towns' side of it (the folk's side is {@link JobSeekers}).
 *
 * <p><b>Openings.</b> A town short of hands at a trade (a whole hand under its share, or nobody at
 * all at a trade it wants), or with a new workplace standing empty (a smithy with no smith, a café
 * with no cook; a school, a bank or a stable too, once the town has them), puts a notice up on its
 * village board: the trade, the wage it pays (what the job is worth there, the more for being short of
 * hands, at the leader's rate, the same coin it will really pay out of the treasury: JobWorth), and what it wants — some
 * years at the trade, and an age where it matters (the watch able-bodied, the mines strong backs, a
 * teacher an older, wiser head). Idle hands at home take up the first of the town's wants
 * themselves; a town that cannot pay posts nothing; three notices at most; a notice nobody answers
 * comes down after six days, and one the town filled from its own folk comes down at once.
 *
 * <p><b>Who sees them.</b> Word travels by the roads and the caravans and between the elders: a
 * colony and its mother see each other's notices the day they go up (the more so once the road
 * between them is laid), and so do towns with a trade pact or an alliance; neighbours who know
 * each other hear of them a day later; rivals hear too, but it takes more to make a folk go over
 * to them, and an elder who mistrusts the place will not take its folk on at all.
 *
 * <p><b>The leader decides.</b> A while after the first application comes in (or as soon as three
 * have), the town's elder looks the applicants over: their level at the trade and the knacks of
 * it they chose, their years (too old for the mines, too young to teach; an old head valued where
 * wisdom is wanted), how they are in themselves, family already in the town, and how the two
 * places stand. The best gets the place; the others are told no, and why. A leader that thinks it
 * could do better holds out for a day or two for a hand with the years the notice asked for. The
 * hired folk's own town must be able to spare it: a town keeps four grown folk at least, its last
 * farmer, its last guard behind a wall, and no more than one in eight of its folk leave in a week.
 *
 * <p><b>Refugees.</b> A village raided badly — houses lost and folk without a bed left in the place,
 * or so few left that it gives itself up — sends its homeless to the nearest friendly town with
 * room, which takes them in: a bed found, work as they fit. Both chronicles tell it.
 *
 * <p>Everything is kept in the village's notes in the Ledger (so it outlasts a restart): the
 * notices, the applications and their verdicts, and who came and went and why. The board shows the
 * notices ("Wanted: a miner…"), the city books' Jobs page shows all of it (client/JobMarketPage),
 * and a folk's card says what it applied for or where it came from.
 */
public final class JobMarket {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private JobMarket() {}

    /** A town keeps at least this many grown folk, whatever the other towns offer. */
    static final int FLOOR = 4;
    /** Notices up on one board at once. */
    static final int MOST_OPEN = 3;
    /** A notice nobody answers comes down after so many days. */
    static final long LAPSE_DAYS = 6;
    /** How long the books keep what was done, in days. */
    static final long KEEP_DAYS = 14;
    /** A place offered and never come for is let go after so many days. */
    static final long OFFER_DAYS = 2;
    /** A village with so few grown folk left after a raid gives the place up. */
    static final int FALLEN = 3;
    /** How often the market looks over each town (ticks). */
    private static final int EVERY = 200;
    /**
     * How long the first application to a notice waits for others before the leader decides (ticks),
     * unless three have come in by then.
     */
    static long decideAfter = 3000L;
    /** Tests: the market held still: the leaders decide only when the test says (considerNow), however many apply, and no notice comes down by itself. */
    static boolean holdDecisions;

    private static final String REC = "~", FLD = "|";

    /** When each town last looked over its notices, and last did its day's books. */
    private static final Map<UUID, Long> POSTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DAILY = new ConcurrentHashMap<>();
    /** Who had a bed when the raiders came, by village: a bed gone by the morning is a home lost. */
    private static final Map<UUID, Map<UUID, BlockPos>> BEDS_AT_RAID = new ConcurrentHashMap<>();

    public static void resetForTests() {
        POSTED.clear();
        DAILY.clear();
        BEDS_AT_RAID.clear();
        decideAfter = 3000L;
        holdDecisions = false;
        JobSeekers.resetForTests();
    }

    /** Tests: how long the first application waits before the leader decides. */
    public static void holdDecisionsForTests(boolean hold) {
        holdDecisions = hold;
    }

    /** Tests: how long the first application waits before the leader decides. */
    public static void decideAfterForTests(long ticks) {
        decideAfter = Math.max(0L, ticks);
    }

    // ------------------------------------------------------------------ the notices

    /** How a notice stands. */
    public enum State { OPEN, FILLED, WITHDRAWN, LAPSED }

    /** What became of an application. */
    public enum Verdict { WAITING, HIRED, REFUSED, WITHDRAWN }

    /** A notice on the board: the trade, the wage, what it wants, and how it went. */
    public static final class Opening {
        int id;
        /** The trade, by its name (StationTask), so a trade another part of the mod adds is kept as it is. */
        String trade = "";
        int wage, minLevel, ageLo, ageHi;
        long posted, closed = -1;
        State state = State.OPEN;
        /** Why it went up ("short of hands", "the new smithy wants a blacksmith"); who filled it, from where; a note. */
        String why = "", by = "", from = "", note = "";

        public int id() { return id; }
        public State state() { return state; }
        public int wage() { return wage; }
        public int minLevel() { return minLevel; }
        public String by() { return by; }
        public String why() { return why; }

        @Nullable public StationTask task() { return named(trade); }

        /** "miner", "blacksmith". */
        public String title() { return noun(trade); }

        /** What it wants, in words: "level 2 or better (a learner, if none comes); 18 to 60, strong enough for the work". */
        public String wants() {
            String lv = minLevel <= 0 ? "any willing hand" : "level " + minLevel + " or better (a learner, if none comes)";
            return lv + (ageWords().isEmpty() ? "" : "; " + ageWords());
        }

        /** "18 to 60, strong enough for the work", or nothing where age does not matter. */
        public String ageWords() {
            boolean low = ageLo > 18, high = ageHi < 200;
            if (!low && !high) return "";
            String span = low && high ? ageLo + " to " + ageHi : low ? ageLo + " or older" : "no older than " + ageHi;
            String note = ageNote(task());
            return span + (note.isEmpty() ? "" : ", " + note);
        }

        String encode() {
            return id + FLD + trade + FLD + wage + FLD + minLevel + FLD + ageLo + FLD + ageHi + FLD + posted + FLD + closed + FLD
                + state.name() + FLD + clean(why) + FLD + clean(by) + FLD + clean(from) + FLD + clean(note);
        }

        @Nullable
        static Opening decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 13) return null;
            try {
                Opening o = new Opening();
                o.id = Integer.parseInt(p[0]);
                o.trade = p[1];
                o.wage = Integer.parseInt(p[2]);
                o.minLevel = Integer.parseInt(p[3]);
                o.ageLo = Integer.parseInt(p[4]);
                o.ageHi = Integer.parseInt(p[5]);
                o.posted = Long.parseLong(p[6]);
                o.closed = Long.parseLong(p[7]);
                o.state = State.valueOf(p[8]);
                o.why = p[9];
                o.by = p[10];
                o.from = p[11];
                o.note = p[12];
                return o;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** An application to a notice: who, from where, what it brings, why it came, and the verdict. */
    public static final class Application {
        int opening;
        UUID folk;
        String name = "";
        UUID from;
        String fromName = "", trade = "";
        int level, age, knacks;
        /** Its reasons in words ("out of work; better pay"), and the one it would give ("for the wages"). */
        String reasons = "", because = "";
        long day, tick;
        Verdict verdict = Verdict.WAITING;
        String why = "";

        public UUID folk() { return folk; }
        public String name() { return name; }
        public Verdict verdict() { return verdict; }
        public String why() { return why; }
        public int level() { return level; }
        public int age() { return age; }
        public int opening() { return opening; }

        String encode() {
            return opening + FLD + folk + FLD + clean(name) + FLD + from + FLD + clean(fromName) + FLD + trade + FLD + level + FLD + age
                + FLD + knacks + FLD + clean(reasons) + FLD + clean(because) + FLD + day + FLD + tick + FLD + verdict.name() + FLD + clean(why);
        }

        @Nullable
        static Application decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 15) return null;
            try {
                Application a = new Application();
                a.opening = Integer.parseInt(p[0]);
                a.folk = UUID.fromString(p[1]);
                a.name = p[2];
                a.from = UUID.fromString(p[3]);
                a.fromName = p[4];
                a.trade = p[5];
                a.level = Integer.parseInt(p[6]);
                a.age = Integer.parseInt(p[7]);
                a.knacks = Integer.parseInt(p[8]);
                a.reasons = p[9];
                a.because = p[10];
                a.day = Long.parseLong(p[11]);
                a.tick = Long.parseLong(p[12]);
                a.verdict = Verdict.valueOf(p[13]);
                a.why = p[14];
                return a;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Somebody who came or went: the day, who, which way, the other town, the trade, and why. */
    public record Move(long day, String name, boolean in, String other, String trade, String why) {
        String encode() {
            return day + FLD + clean(name) + FLD + (in ? 1 : 0) + FLD + clean(other) + FLD + trade + FLD + clean(why);
        }

        @Nullable
        static Move decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 6) return null;
            try {
                return new Move(Long.parseLong(p[0]), p[1], "1".equals(p[2]), p[3], p[4], p[5]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static String clean(@Nullable String s) {
        return s == null ? "" : s.replace(REC, "-").replace(FLD, "/");
    }

    // ------------------------------------------------------------------ the books (the Ledger's notes)

    public static List<Opening> openings(UUID village) {
        List<Opening> out = new ArrayList<>();
        String s = Ledger.note(village, "jm.open");
        if (s == null || s.isEmpty()) return out;
        for (String r : s.split(REC)) {
            Opening o = Opening.decode(r);
            if (o != null) out.add(o);
        }
        return out;
    }

    static void openings(UUID village, List<Opening> all) {
        StringBuilder sb = new StringBuilder();
        for (Opening o : all) sb.append(sb.length() == 0 ? "" : REC).append(o.encode());
        Ledger.note(village, "jm.open", sb.toString());
    }

    /** The town's open notices. */
    public static List<Opening> open(UUID village) {
        List<Opening> out = new ArrayList<>();
        for (Opening o : openings(village)) if (o.state == State.OPEN) out.add(o);
        return out;
    }

    @Nullable
    static Opening opening(UUID village, int id) {
        for (Opening o : openings(village)) if (o.id == id) return o;
        return null;
    }

    public static List<Application> applications(UUID village) {
        List<Application> out = new ArrayList<>();
        String s = Ledger.note(village, "jm.apps");
        if (s == null || s.isEmpty()) return out;
        for (String r : s.split(REC)) {
            Application a = Application.decode(r);
            if (a != null) out.add(a);
        }
        return out;
    }

    static void applications(UUID village, List<Application> all) {
        StringBuilder sb = new StringBuilder();
        for (Application a : all) sb.append(sb.length() == 0 ? "" : REC).append(a.encode());
        Ledger.note(village, "jm.apps", sb.toString());
    }

    public static List<Move> moves(UUID village) {
        List<Move> out = new ArrayList<>();
        String s = Ledger.note(village, "jm.moves");
        if (s == null || s.isEmpty()) return out;
        for (String r : s.split(REC)) {
            Move m = Move.decode(r);
            if (m != null) out.add(m);
        }
        return out;
    }

    static void moved(UUID village, Move m) {
        List<Move> all = moves(village);
        all.add(m);
        while (all.size() > 40) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Move x : all) sb.append(sb.length() == 0 ? "" : REC).append(x.encode());
        Ledger.note(village, "jm.moves", sb.toString());
    }

    private static int nextId(UUID village) {
        int n = 1;
        try {
            String s = Ledger.note(village, "jm.seq");
            if (s != null && !s.isEmpty()) n = Integer.parseInt(s);
        } catch (NumberFormatException ignored) { }
        Ledger.note(village, "jm.seq", Integer.toString(n + 1));
        return n;
    }

    /** What a folk's card says of it and the market (kept in the notes of the town it lives in). */
    static void story(UUID village, UUID folk, long day, @Nullable String text) {
        if (text == null) Ledger.forget(village, "jm.story/" + folk);
        else Ledger.note(village, "jm.story/" + folk, day + FLD + clean(text));
    }

    // ------------------------------------------------------------------ the trades: names, wages, what is wanted

    /** A trade by its name, or null (a trade this build does not have). */
    @Nullable
    public static StationTask named(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return StationTask.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "miner", "blacksmith", "courier": the trade as folk would name one who works it. */
    public static String noun(@Nullable String trade) {
        StationTask t = named(trade);
        if (t == null) return trade == null ? "hand" : trade.toLowerCase(Locale.ROOT);
        return noun(t);
    }

    public static String noun(StationTask t) {
        return switch (t) {
            case HAUL -> "courier";
            case FIREWORKS -> "fireworks maker";        // [fireworks]
            case NONE -> "hand";
            default -> t.title.toLowerCase(Locale.ROOT);
        };
    }

    /** "a miner", "an enchanter". */
    public static String a(String noun) {
        return noun.isEmpty() ? noun : ("aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an " : "a ") + noun;
    }

    /** One to twelve in words, as folk say it; more in figures. */
    public static String words(int n) {
        String[] w = { "nothing", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve" };
        return n >= 0 && n < w.length ? w[n] : Integer.toString(n);
    }

    /** The years a trade wants, where they matter: {youngest, oldest} (0 and 200 where they do not). */
    static int[] ages(@Nullable StationTask t) {
        if (t == null) return new int[]{ 0, 200 };
        if (t.name().contains("TEACH")) return new int[]{ 35, 200 };
        return switch (t) {
            case GUARD, SCOUT, HUNT, CAVE -> new int[]{ 18, 50 };          // [caves]
            case MINE, WOOD -> new int[]{ 18, 60 };
            default -> new int[]{ 0, 200 };
        };
    }

    /** Why the years matter to this trade, in a few words. */
    static String ageNote(@Nullable StationTask t) {
        if (t == null) return "";
        if (t.name().contains("TEACH")) return "an older, wiser head";
        return switch (t) {
            case GUARD -> "able-bodied, for the watch";
            case SCOUT, HUNT -> "fit for long days out";
            case CAVE -> "fit and able to fight, for a day underground";      // [caves]
            case MINE, WOOD -> "strong enough for the work";
            default -> "";
        };
    }

    /** A trade where an old head is valued: the teaching and the learned trades, the stores. */
    static boolean wise(@Nullable StationTask t) {
        return t != null && (t.name().contains("TEACH") || t == StationTask.ENCHANT || t == StationTask.STORE || t == StationTask.BREW);
    }

    /** Heavy work, for younger backs. */
    static boolean heavy(@Nullable StationTask t) {
        return t == StationTask.MINE || t == StationTask.WOOD || t == StationTask.GUARD || t == StationTask.HUNT
            || t == StationTask.SCOUT || t == StationTask.HAUL || t == StationTask.CAVE;            // [caves]
    }

    /** "the mines", "the watch": the work, for a refusal. */
    static String workWords(@Nullable StationTask t) {
        if (t == null) return "the work";
        return switch (t) {
            case MINE -> "the mines";
            case WOOD -> "the woods";
            case GUARD -> "the watch";
            case HUNT -> "the hunt";
            case SCOUT -> "the scouting";
            case CAVE -> "the caves";                    // [caves]
            case FIREWORKS -> "the powder hut";          // [fireworks]
            case HAUL -> "the carrying";
            default -> "the work";
        };
    }

    /** The level a notice for this trade asks for (a bigger place asks a little more of its crafts). */
    static int wantsLevel(@Nullable StationTask t, UUID town) {
        if (t == null) return 0;
        int lv = switch (t.name()) {
            case "MINE", "GUARD", "SMELT", "STORE", "COOK", "BEEKEEP", "SHOP" -> 2;
            case "RANCH", "HUNT" -> 1;
            case "SCOUT", "TAILOR" -> 3;
            case "FIREWORKS" -> 2;                                               // [fireworks]
            case "BREW" -> 4;
            case "SMITH", "ENCHANT" -> 5;
            default -> 0;
        };
        if (lv > 0 && t.isCraft()) lv += Villages.rank(town).ordinal() / 2;
        return lv;
    }

    /**
     * What a place pays a hand of this trade a day: what the job is worth there for a hand of the years at it the
     * notice asks for, at the leader's rate (JobWorth.offer). A trade the town is short of is worth more there, so
     * its notice offers more. [econ-wages]
     */
    public static int offer(@Nullable StationTask t, UUID town) {
        return JobWorth.offer(t, town);
    }

    /** What a hand can count on there: the wage, at what the place's last payday really paid. */
    static int likely(int wage, UUID town) {
        int share = Market.lastShare(town);
        return share < 0 ? wage : (int) Math.floor(wage * Math.min(100, share) / 100.0);
    }

    /**
     * What this folk is paid at home now: its own wage there (its job's worth and its own hand at it), at the
     * leader's rate and at what the place really pays. A skilled hand weighs a notice against what it really
     * earns, not against what a beginner at its trade would. [econ-wages]
     */
    static int paidNow(VillageFolkEntity f) {
        UUID home = f.ownerId();
        if (home == null || f.stationTask() == StationTask.NONE) return 0;
        return likely(JobWorth.atLeadersRate(home, Wealth.wage(f)), home);
    }

    /** Can the town pay the wage? A town with no treasury yet pays from its first payday; one that pays short posts nothing. */
    static boolean canPay(UUID town, int wage) {
        if (!Ledger.hasTreasury(town)) return true;
        int share = Market.lastShare(town);
        return Ledger.coins(town) >= wage * 3 || share < 0 || share >= 50;
    }

    /** The knacks it chose that belong to this trade. */
    static int knacks(VillageFolkEntity f, @Nullable StationTask t) {
        if (t == null) return 0;
        int n = 0;
        for (FolkSkills.Chosen c : f.knacks().chosen()) if (c.knack().trades.contains(t)) n++;
        return n;
    }

    // ------------------------------------------------------------------ how two towns stand

    /** How word passes between two towns, and how easily a folk goes from the one to the other. */
    public enum Link {
        ROAD("the road between them", 8, 0, 2), KIN("kin", 6, 0, 2), ALLIES("allies", 6, 0, 2), PACT("a trade pact", 5, 0, 2),
        FRIENDS("on good terms", 3, 1, 2), NEIGHBOURS("word of mouth", 0, 1, 2), RIVALS("rivals", -15, 1, 4),
        FEUD("in a feud", -30, 2, 5), NONE("strangers", 0, 99, 99);

        public final String words;
        /** How the hiring town weighs a folk from there. */
        final int ease;
        /** How many days word of a notice takes to come. */
        final int delay;
        /** How strong a folk's reasons must be to go over there. */
        final int need;

        Link(String words, int ease, int delay, int need) {
            this.words = words;
            this.ease = ease;
            this.delay = delay;
            this.need = need;
        }
    }

    public static Link link(UUID a, UUID b) {
        if (a.equals(b)) return Link.NONE;
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        if (va == null || vb == null || !va.dim().equals(vb.dim())) return Link.NONE;
        UUID colony = null;
        for (Map.Entry<UUID, UUID> l : Ledger.links().entrySet()) {
            if (l.getKey().equals(a) && l.getValue().equals(b) || l.getKey().equals(b) && l.getValue().equals(a)) { colony = l.getKey(); break; }
        }
        if (colony != null) {
            int[] road = Ledger.road(colony);
            return road != null && road.length > 2 && road[2] == 1 ? Link.ROAD : Link.KIN;
        }
        if (Envoys.allied(a, b)) return Link.ALLIES;
        if (Envoys.pact(a, b)) return Link.PACT;
        if (!Diplomacy.neighbours(va, vb) || !Ledger.knowEachOther(a, b)) return Link.NONE;
        int r = Ledger.relation(a, b);
        return r <= Diplomacy.FEUD ? Link.FEUD : r <= Diplomacy.UNEASY ? Link.RIVALS : r >= Diplomacy.FRIENDLY ? Link.FRIENDS : Link.NEIGHBOURS;
    }

    /** A notice up in another town, as it is known here. */
    public record Seen(UUID town, Opening opening, Link link, int distance) {}

    /** The other towns' open notices this town has heard of, the nearest and best connected first. */
    public static List<Seen> seen(UUID viewer, long day) {
        List<Seen> out = new ArrayList<>();
        Villages.Village here = Villages.get(viewer);
        if (here == null) return out;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(viewer) || !o.dim().equals(here.dim())) continue;
            List<Opening> open = open(o.id());
            if (open.isEmpty()) continue;
            Link link = link(viewer, o.id());
            if (link == Link.NONE) continue;
            int dist = (int) Math.sqrt(o.centre().distSqr(here.centre()));
            for (Opening op : open) if (day >= op.posted + link.delay) out.add(new Seen(o.id(), op, link, dist));
        }
        out.sort((x, y) -> x.link().ease != y.link().ease ? Integer.compare(y.link().ease, x.link().ease) : Integer.compare(x.distance(), y.distance()));
        return out;
    }

    // ------------------------------------------------------------------ the town's day

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 113) return;
        Guard.run("job market", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) tick(level);
        });
    }

    static void tick(ServerLevel level) {
        long now = level.getGameTime(), day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        boolean byDay = t >= 1000L && t < 12500L;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            UUID id = v.id();
            if (Villages.folkOf(id).isEmpty()) continue;                 // nobody here (or nobody awake): its books wait
            watchTheRaid(v);
            if (DAILY.getOrDefault(id, -1L) < day) {
                DAILY.put(id, day);
                daily(level, v, day);
            }
            if (!byDay) continue;                                         // the board's business is done by day
            long last = POSTED.getOrDefault(id, -100000L);
            if (now - last >= 1200L || now < last) {
                POSTED.put(id, now);
                post(level, v);
            }
            decide(level, v, false);
        }
        JobSeekers.sweep(level);
    }

    /** Once a day for each town: old books let go, places never come for given up, the homeless after a raid looked to again. */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Opening> all = openings(id);
        boolean changed = all.removeIf(o -> o.state != State.OPEN && o.closed >= 0 && day - o.closed > KEEP_DAYS);
        if (changed) openings(id, all);
        List<Application> apps = applications(id);
        if (apps.removeIf(a -> a.verdict != Verdict.WAITING && day - a.day > KEEP_DAYS)) applications(id, apps);
        for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) {
            String k = e.getKey();
            if (k.startsWith("jm.story/") || k.startsWith("jm.refused/")) {
                long d = leadingDay(e.getValue());
                if (d >= 0 && day - d > 30) Ledger.forget(id, k);
            } else if (k.startsWith("jm.offer/")) {
                String[] p = e.getValue().split("\\|", -1);
                long d = p.length > 3 ? parseLong(p[3]) : -1;
                if (d >= 0 && day - d > OFFER_DAYS) {
                    Ledger.forget(id, k);
                    neverCame(level, id, UUID.fromString(k.substring("jm.offer/".length())), p, day);
                }
            } else if (k.startsWith("jm.incoming/")) {
                String[] p = e.getValue().split("\\|", -1);
                long d = p.length > 5 ? parseLong(p[5]) : -1;
                if (d >= 0 && day - d > 4) Ledger.forget(id, k);                        // never came: dead on the road
            } else if (k.startsWith("jm.applied/")) {
                String[] p = e.getValue().split("\\|", -1);
                long d = p.length > 2 ? parseLong(p[2]) : -1;
                if (d >= 0 && day - d > LAPSE_DAYS + 1) Ledger.forget(id, k);       // a notice that came down unanswered
            }
        }
        // The homeless of a raid with nowhere to go yet: looked to again for three days.
        String raid = Ledger.note(id, "jm.raid");
        if (raid != null && !raid.isEmpty()) {
            String[] p = raid.split("\\|", -1);
            long d = parseLong(p[0]);
            if (d >= 0 && day - d <= 3 && day > d) raided(level, v, 0, p.length > 1 && "1".equals(p[1]), true);
            else if (d >= 0 && day - d > 3) Ledger.forget(id, "jm.raid");
        }
    }

    /** A place offered and never come for: the notice goes up again. */
    private static void neverCame(ServerLevel level, UUID home, UUID folk, String[] offer, long day) {
        if (offer.length < 2) return;
        UUID town;
        int opening;
        try {
            town = UUID.fromString(offer[0]);
            opening = Integer.parseInt(offer[1]);
        } catch (IllegalArgumentException e) {
            return;
        }
        List<Opening> all = openings(town);
        for (Opening o : all) {
            if (o.id != opening || o.state != State.FILLED) continue;
            o.state = State.OPEN;
            o.note = o.by + " never came";
            o.by = "";
            o.from = "";
            o.closed = -1;
        }
        openings(town, all);
        List<Application> apps = applications(town);
        for (Application a : apps) {
            if (a.opening == opening && a.folk.equals(folk) && a.verdict == Verdict.HIRED) {
                a.verdict = Verdict.WITHDRAWN;
                a.why = "never came";
            }
        }
        applications(town, apps);
        story(home, folk, day, null);
    }

    private static long leadingDay(String s) {
        int bar = s.indexOf('|');
        return parseLong(bar < 0 ? s : s.substring(0, bar));
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ posting

    /** One post the town wants filled: the trade, why, how short it is, and whether it is a new workplace standing empty. */
    record Want(StationTask trade, String why, double shortBy, boolean workplace) {}

    /** The workplaces that want a hand of their own: the building, and the trade (by name: some trades come with other parts). */
    private static final String[][] WORKPLACES = {
        { "smithy", "SMITH" }, { "cafe", "COOK" }, { "shop", "SHOP" }, { "brewery", "BREW" }, { "library", "ENCHANT" },
        { "workshop", "TAILOR" }, { "school", "TEACHER" }, { "school", "TEACH" }, { "bank", "BANKER" }, { "bank", "BANK" },
        { "stable", "GROOM" }, { "stables", "GROOM" }, { "stable", "STABLEHAND" },
        { "powderhut", "FIREWORKS" },                                                       // [fireworks]
    };

    /** How many hands short the town is at a trade, as its shape has it (the trade's share, less who works it). */
    static double shortOf(UUID town, StationTask t) {
        if (t == StationTask.CAVE) return 0.0;              // [caves] the team is chosen from the town's own (CaveDwellers.appoint)
        if (t == StationTask.FIREWORKS) return 0.0;         // [fireworks] the maker is chosen from the town's own (FireworksMaker.appoint)
        return -Villages.share(town, t);
    }

    /** What the town wants hands for, most wanted first. */
    static List<Want> wanted(Villages.Village v) {
        UUID id = v.id();
        Map<StationTask, Integer> have = new EnumMap<>(StationTask.class);
        int idle = 0, grown = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isBaby()) continue;
            grown++;
            if (JobSeekers.travelling(f)) continue;               // on its way here: counted with the coming
            if (f.stationTask() == StationTask.NONE) idle++;
            else have.merge(f.stationTask(), 1, Integer::sum);
        }
        if (grown < 3) return List.of();                           // a camp of two posts nothing
        Map<StationTask, Integer> coming = JobSeekers.comingTo(id);
        List<Want> out = new ArrayList<>();
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            int on = coming.getOrDefault(t, 0);
            double s = shortOf(id, t) - on;
            int n = have.getOrDefault(t, 0);
            if (s >= 1.0 || n == 0 && on == 0 && s >= 0.75) out.add(new Want(t, n == 0 ? "nobody at it" : "short of hands", s, false));
        }
        out.sort((x, y) -> Double.compare(y.shortBy(), x.shortBy()));
        // Idle hands at home take up the most wanted trades themselves (Villages.needed).
        for (int i = 0; i < idle && !out.isEmpty(); i++) out.remove(0);
        // A new workplace standing with nobody at it: its trade first.
        Set<StationTask> done = new LinkedHashSet<>();
        for (String[] w : WORKPLACES) {
            StationTask t = named(w[1]);
            if (t == null || done.contains(t)) continue;
            if (have.getOrDefault(t, 0) > 0 || coming.getOrDefault(t, 0) > 0) continue;
            if (!Villages.hasBuilt(id, w[0]) && Villages.builtAt(id, w[0]) == null) continue;
            done.add(t);
            out.removeIf(x -> x.trade() == t);
            out.add(0, new Want(t, Villages.spoken(w[0]).replaceFirst("^the ", "the new ") + " wants " + a(noun(t)), 1.0, true));
        }
        return out;
    }

    /**
     * The town looks over its notices: what is no longer wanted (filled from its own folk) comes down,
     * what nobody answered in six days lapses, and a notice goes up for each trade it is short of.
     * Returns the notices put up.
     */
    public static List<Opening> post(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        // Only on a full view: the shape is judged from who is loaded (see VillageFolkEntity.changedTrade).
        if (Villages.loadedCount(id) * 5 < Villages.headcount(id) * 4) return List.of();
        List<Opening> all = openings(id);
        List<Want> wants = wanted(v);
        List<Application> apps = null;
        boolean changed = false;
        for (Opening o : all) {
            if (o.state != State.OPEN) continue;
            StationTask t = o.task();
            boolean still = false;
            for (Want w : wants) if (w.trade().name().equals(o.trade)) { still = true; break; }
            // Hysteresis: a notice stays up till the town is within half a hand of its share.
            if (!still && t != null && shortOf(id, t) - JobSeekers.comingTo(id).getOrDefault(t, 0) >= 0.5) still = true;
            if (o.why.equals(BY_HAND)) still = true;                      // put up by an operator: up till it lapses
            String why = null;
            if (!still) why = "the place was filled at home";
            else if (day - o.posted >= LAPSE_DAYS) why = "nobody suitable came";
            if (why == null || holdDecisions) continue;                    // (tests: the notice stays up)
            o.state = still ? State.LAPSED : State.WITHDRAWN;
            o.closed = day;
            o.note = why;
            if (apps == null) apps = applications(id);
            for (Application a : apps) {
                if (a.opening != o.id || a.verdict != Verdict.WAITING) continue;
                a.verdict = Verdict.REFUSED;
                a.why = why;
                Ledger.forget(a.from, "jm.applied/" + a.folk);
                JobSeekers.told(a.folk, FolkTalk.pick(level.getRandom(), "The place in " + Villages.name(id) + " came to nothing. Ah well.",
                    Villages.name(id) + " took their notice down. Never mind."));
            }
            changed = true;
        }
        List<Opening> posted = new ArrayList<>();
        int open = 0;
        for (Opening o : all) if (o.state == State.OPEN) open++;
        for (Want w : wants) {
            if (open >= MOST_OPEN) break;
            boolean up = false;
            for (Opening o : all) if (o.state == State.OPEN && o.trade.equals(w.trade().name())) { up = true; break; }
            if (up) continue;
            int wage = offer(w.trade(), id);
            if (!canPay(id, wage)) continue;
            Opening o = new Opening();
            o.id = nextId(id);
            o.trade = w.trade().name();
            o.wage = wage;
            o.minLevel = wantsLevel(w.trade(), id);
            int[] ages = ages(w.trade());
            o.ageLo = ages[0];
            o.ageHi = ages[1];
            o.posted = day;
            o.why = w.why();
            all.add(o);
            posted.add(o);
            open++;
            changed = true;
            Villages.tell(id, day, "a notice went up on the board: wanted, " + a(o.title()) + ", " + wage + (wage == 1 ? " coin" : " coins")
                + " a day (" + w.why() + ")");
            Market.assemblyNews(id, "We want " + a(o.title()) + " — the notice is on the board: " + words(wage) + " a day.");
            LOG.info("[MCA-JOBS] {} posts: wanted, {}, {} a day, {} ({})", Villages.name(id), o.title(), wage, o.wants(), w.why());
        }
        if (changed) {
            openings(id, all);
            if (apps != null) applications(id, apps);
        }
        return posted;
    }

    // ------------------------------------------------------------------ applying

    /** Why a notice put up by hand (/village jobs want) is up. */
    static final String BY_HAND = "the elder wants another hand at it";

    /**
     * A notice for this trade put up now, whatever the town's shortages (/village jobs want, for
     * operators and for setting a scene): at the trade's real wage, wanting what such a notice
     * always wants. Returns the notice (the one already up, if there is one).
     */
    public static Opening postFor(ServerLevel level, Villages.Village v, StationTask t) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<Opening> all = openings(id);
        for (Opening o : all) if (o.state == State.OPEN && o.trade.equals(t.name())) return o;
        Opening o = new Opening();
        o.id = nextId(id);
        o.trade = t.name();
        o.wage = offer(t, id);
        o.minLevel = wantsLevel(t, id);
        int[] ages = ages(t);
        o.ageLo = ages[0];
        o.ageHi = ages[1];
        o.posted = day;
        o.why = BY_HAND;
        all.add(o);
        openings(id, all);
        Villages.tell(id, day, "a notice went up on the board: wanted, " + a(o.title()) + ", " + o.wage + (o.wage == 1 ? " coin" : " coins")
            + " a day (" + BY_HAND + ")");
        return o;
    }

    /** Book an application from this folk to the town's notice. */
    static Application apply(ServerLevel level, VillageFolkEntity f, UUID town, Opening o, JobSeekers.Reasons why) {
        UUID home = f.ownerId();
        long day = level.getDayTime() / 24000L;
        StationTask t = o.task();
        Application a = new Application();
        a.opening = o.id;
        a.folk = f.getUUID();
        a.name = f.displayNameCap();
        a.from = home;
        a.fromName = Villages.name(home);
        a.trade = o.trade;
        a.level = t == null ? 0 : f.tradeLevel(t);
        a.age = f.ageYears();
        a.knacks = knacks(f, t);
        a.reasons = why.words();
        a.because = why.because();
        a.day = day;
        a.tick = level.getGameTime();
        List<Application> all = applications(town);
        all.removeIf(x -> x.folk.equals(a.folk) && x.opening == a.opening && x.verdict == Verdict.WAITING);
        all.add(a);
        applications(town, all);
        Ledger.note(home, "jm.applied/" + f.getUUID(), town + FLD + o.id + FLD + day);
        story(home, f.getUUID(), day, "applied to " + Villages.name(town) + " as " + a(o.title()));
        f.persona().remember(day, "I applied to " + Villages.name(town) + " to work as " + a(o.title()), 3);
        LOG.info("[MCA-JOBS] {} of {} applies to {} as {} (level {}, {} years; {})", a.name, a.fromName, Villages.name(town),
            o.title(), a.level, a.age, a.reasons);
        return a;
    }

    /** Is this folk waiting to hear from a town it applied to? */
    static boolean waiting(UUID home, UUID folk) {
        String s = Ledger.note(home, "jm.applied/" + folk);
        return s != null && !s.isEmpty();
    }

    // ------------------------------------------------------------------ who can be spared

    static int grownOf(UUID town) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(town)) if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) n++;
        return Math.max(n, Villages.headcount(town) - kidsOf(town));
    }

    private static int kidsOf(UUID town) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(town)) if (a.isBaby()) n++;
        return n;
    }

    static int at(UUID town, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(town)) if (!a.isBaby() && a.stationTask() == t) n++;
        return n;
    }

    /** How many left this town for work elsewhere in the last seven days. */
    static int leftThisWeek(UUID town, long day) {
        int n = 0;
        for (Move m : moves(town)) if (!m.in() && day - m.day() < 7 && !m.why().contains("raid")) n++;
        return n;
    }

    /** No more than one in eight of a town's grown folk go in a week (one, in a small place). */
    static int weeklyCap(int grown) {
        return Math.max(1, grown / 8);
    }

    /**
     * Can its town spare this folk, with {@code going} grown folk leaving with it (itself included)?
     * Null if it can; else why not, in words.
     */
    @Nullable
    static String cannotSpare(ServerLevel level, UUID home, VillageFolkEntity f, int going) {
        String name = Villages.name(home);
        if (f.getUUID().equals(Villages.elder(home))) return "the elder stays with " + name;
        if (Villages.holdsTheLead(home, f.getUUID(), level.getGameTime())) return "it is leading a build in " + name;
        UUID partner = f.life().partner();
        if (partner != null && partner.equals(Villages.elder(home))) return "its partner is " + name + "'s elder";
        int grown = grownOf(home);
        if (grown - Math.max(1, going) < FLOOR) return name + " has too few hands to spare one";
        if (leftThisWeek(home, level.getDayTime() / 24000L) >= weeklyCap(grown)) return "enough have left " + name + " this week";
        // (Its trade only keeps it if it can work at it: a farmer with no field to be had is no farmer to keep.)
        StationTask t = JobSeekers.outOfWork(f) ? StationTask.NONE : f.stationTask();
        if (t == StationTask.FARM && at(home, StationTask.FARM) <= 1 && at(home, StationTask.FISH) == 0) return "it is " + name + "'s last farmer";
        if (t == StationTask.GUARD && at(home, StationTask.GUARD) <= 1 && Villages.hasBuilt(home, "fortify")) return "it is the last of " + name + "'s watch";
        return null;
    }

    /** Does its own town need this folk at its trade (short of it, or it is the only one at a trade the town wants)? */
    static boolean neededAtHome(VillageFolkEntity f) {
        UUID home = f.ownerId();
        StationTask t = f.stationTask();
        if (home == null || t == StationTask.NONE) return false;
        if (JobSeekers.outOfWork(f)) return false;               // a trade with no ground to work it at is no use to anybody
        return shortOf(home, t) >= 1.0 || at(home, t) == 1 && Villages.wants(home, t);
    }

    /** Room for more in the town: its beds, less its folk (the hired on their way here are on its roll already). */
    static int room(ServerLevel level, UUID town) {
        Villages.bedsMadeUp(level, town);                 // the beds counted afresh, at most once a minute
        return Villages.housing(town) - Villages.headcount(town);
    }

    // ------------------------------------------------------------------ the leader decides

    /** The town's leader looks over the applications now, whether or not more might come (tests, the command). */
    public static int considerNow(ServerLevel level, Villages.Village v) {
        return decide(level, v, true);
    }

    /** Every open notice with applications waiting long enough (or three of them): decided. Returns how many were taken on. */
    static int decide(ServerLevel level, Villages.Village v, boolean now) {
        if (holdDecisions && !now) return 0;
        UUID id = v.id();
        long tick = level.getGameTime(), day = level.getDayTime() / 24000L;
        List<Application> apps = applications(id);
        boolean any = false;
        for (Application a : apps) if (a.verdict == Verdict.WAITING) { any = true; break; }
        if (!any) return 0;
        List<Opening> all = openings(id);
        int hired = 0;
        boolean changed = false;
        for (Opening o : all) {
            if (o.state != State.OPEN) continue;
            List<Application> waiting = new ArrayList<>();
            long oldest = Long.MAX_VALUE;
            for (Application a : apps) {
                if (a.opening != o.id || a.verdict != Verdict.WAITING) continue;
                waiting.add(a);
                oldest = Math.min(oldest, a.tick);
            }
            if (waiting.isEmpty()) continue;
            if (!now && waiting.size() < 3 && tick >= oldest && tick - oldest < decideAfter) continue;
            int took = judge(level, v, o, waiting, day, now);
            if (took < 0) continue;                                       // holding out for a better hand
            changed = true;
            hired += took;
        }
        if (changed) {
            openings(id, all);
            applications(id, apps);
        }
        return hired;
    }

    /** One applicant, weighed: its score, and what rules it out (null if nothing does). */
    private record Weighed(Application a, @Nullable VillageFolkEntity f, int score, @Nullable String fault, boolean withdrawn,
                           List<String> good) {}

    /**
     * The leader weighs the applicants to one notice and takes the best on. Returns 1 if one was taken
     * on, 0 if none would do, -1 if the leader is holding out for a better hand.
     */
    private static int judge(ServerLevel level, Villages.Village v, Opening o, List<Application> waiting, long day, boolean now) {
        UUID id = v.id();
        String town = Villages.name(id);
        StationTask t = o.task();
        Envoys.Temper temper = Envoys.temper(id);
        VillageFolkEntity elder = Orders.elderOf(id);
        String judge = elder != null ? Leader.leaderName(id, elder) : "the village";
        int room = room(level, id);
        List<Weighed> all = new ArrayList<>();
        for (Application a : waiting) {
            VillageFolkEntity f = JobSeekers.live(level, a.folk);
            boolean withdrawn = false;
            String fault = null;
            if (f != null && !a.from.equals(f.ownerId())) {
                fault = "it had left " + a.fromName;
                withdrawn = true;
            }
            if (f != null && !withdrawn) {
                a.level = t == null ? a.level : f.tradeLevel(t);
                a.age = f.ageYears();
                a.knacks = knacks(f, t);
            }
            int going = f == null ? 1 : JobSeekers.household(f).size();
            int grownGoing = f == null ? 1 : JobSeekers.grownIn(JobSeekers.household(f));
            if (fault == null && a.age > o.ageHi) fault = heavy(t) ? "too old for " + workWords(t) : "older than " + town + " wanted";
            if (fault == null && a.age < o.ageLo) fault = wise(t) ? town + " wanted an older, wiser head" : "too young for " + workWords(t);
            Link link = link(id, a.from);
            if (fault == null && (link == Link.FEUD || link == Link.RIVALS && (temper == Envoys.Temper.WARY || temper == Envoys.Temper.PRICKLY))) {
                fault = judge + " won't take on folk from " + a.fromName;
            }
            if (fault == null && f != null) {
                String no = cannotSpare(level, a.from, f, grownGoing);
                if (no != null) {
                    fault = no;
                    withdrawn = true;
                }
            }
            if (fault == null && going > Math.max(0, room)) {
                fault = "there's no room in " + town + (going > 1 ? " for a household of " + going : "") + " just now";
            }
            List<String> good = new ArrayList<>();
            int score = score(a, o, t, link, temper, f, id, good);
            all.add(new Weighed(a, f, score, fault, withdrawn, good));
        }
        Weighed best = null;
        for (Weighed w : all) if (w.fault() == null && (best == null || w.score() > best.score())) best = w;
        // Holding out: nobody with the years the notice asked for, the notice young, and the town not desperate.
        if (best != null && !now && best.a().level < o.minLevel && day - o.posted < 2 && t != null && at(id, t) > 0) return -1;
        for (Weighed w : all) {
            if (w == best) continue;
            String why = w.fault() != null ? w.fault()
                : best != null && best.a().level > w.a().level ? best.a().name + " has more years at it"
                : best != null ? best.a().name + " suited " + town + " better" : "nobody would do";
            refuse(level, id, o, w.a(), w.withdrawn(), why, best == null ? null : best.a().name, day);
        }
        if (best == null) {
            Villages.tell(id, day, judge + " looked over " + waiting.size() + (waiting.size() == 1 ? " application" : " applications")
                + " for the " + o.title() + "'s place, and took nobody on");
            return 0;
        }
        hire(level, v, o, best.a(), best.f(), best.good(), waiting.size(), judge, elder, day);
        return 1;
    }

    /** How the leader weighs an applicant: its years at the trade first, then everything else. */
    static int score(Application a, Opening o, @Nullable StationTask t, Link link, Envoys.Temper temper, @Nullable VillageFolkEntity f,
                     UUID town, List<String> good) {
        int s = a.level * (temper == Envoys.Temper.SHREWD ? 13 : 10) + a.knacks * 8;
        if (a.level > 0) good.add("level " + a.level + " at " + (t == null ? "the trade" : t.label));
        if (a.knacks > 0) good.add(a.knacks + (a.knacks == 1 ? " knack" : " knacks") + " of the trade");
        if (wise(t)) {
            int extra = Math.max(0, Math.min(20, (a.age - 30) / 2));
            s += extra;
            if (extra >= 6) good.add("an older, wiser head");
        }
        if (heavy(t) && a.age < 40) s += 5;
        if (a.level >= o.minLevel) s += 5;
        s += link.ease;
        if (link.ease >= 5) good.add("from " + a.fromName + " (" + link.words + ")");
        if (f != null) {
            int mood = f.persona().mood();
            if (mood >= 65) {
                s += 4;
                good.add("a cheerful sort");
            } else if (mood < 30) {
                s -= 6;
            }
            if (JobSeekers.kinIn(f, town) != null) {
                s += 12;
                good.add("has family here");
            }
        }
        if ((temper == Envoys.Temper.WARM || temper == Envoys.Temper.GENEROUS) && a.age <= 24) s += 5;      // gives the young a start
        if (temper == Envoys.Temper.WARY && link.ordinal() >= Link.FRIENDS.ordinal()) s -= 10;             // a stranger's face
        return s;
    }

    private static void hire(ServerLevel level, Villages.Village v, Opening o, Application a, @Nullable VillageFolkEntity f,
                             List<String> good, int of, String judge, @Nullable VillageFolkEntity elder, long day) {
        UUID id = v.id();
        String town = Villages.name(id);
        a.verdict = Verdict.HIRED;
        a.why = "taken on" + (good.isEmpty() ? "" : ": " + String.join(", ", good)) + "; " + a.age + " years old";
        o.state = State.FILLED;
        o.by = a.name;
        o.from = a.fromName;
        o.closed = day;
        Ledger.forget(a.from, "jm.applied/" + a.folk);
        Ledger.note(a.from, "jm.offer/" + a.folk, id + FLD + o.id + FLD + o.trade + FLD + day + FLD + clean(a.because));
        story(a.from, a.folk, day, "taken on by " + town + " as " + a(o.title()) + "; leaving soon");
        Villages.tell(id, day, judge + " took on " + a.name + " of " + a.fromName + " as our " + o.title() + " (level " + a.level
            + ", " + a.age + " years old), " + (of == 1 ? "the only one who applied" : "of " + of + " who applied"));
        Villages.tell(a.from, day, a.name + " was taken on by " + town + " as " + a(o.title()));
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(),
                a.name + " of " + a.fromName + " — " + (a.level > 0 ? words(Math.min(a.level, 99)) + " levels at it" : "willing") + ". We'll have " + a.name + ".",
                "I've read them all. " + a.name + " it is: our new " + o.title() + "."));
        }
        JobSeekers.hired(level, a.folk, f, id, o, a.because);
        LOG.info("[MCA-JOBS] {}: {} took on {} of {} as {} (score of {} applicants: {})", town, judge, a.name, a.fromName, o.title(), of, a.why);
    }

    private static void refuse(ServerLevel level, UUID id, Opening o, Application a, boolean withdrawn, String why,
                               @Nullable String took, long day) {
        String town = Villages.name(id);
        a.verdict = withdrawn ? Verdict.WITHDRAWN : Verdict.REFUSED;
        a.why = why;
        Ledger.forget(a.from, "jm.applied/" + a.folk);
        Ledger.note(a.from, "jm.refused/" + a.folk, Long.toString(day));
        story(a.from, a.folk, day, "applied to " + town + " as " + a(o.title()) + "; " + (took != null && !withdrawn ? "they took " + took : why));
        String line;
        if (why.startsWith("too old")) {
            line = FolkTalk.pick(level.getRandom(), "Too old for " + workWords(o.task()) + ", " + town + " says. Hmph. I've years in me yet.",
                town + " wants younger backs. Well, I know my trade.");
        } else if (why.contains("older, wiser")) {
            line = town + " wanted an older head. My time will come.";
        } else if (why.startsWith("too young")) {
            line = "Too young, they say. I'll show them, one day.";
        } else if (withdrawn) {
            line = FolkTalk.pick(level.getRandom(), "I can't go to " + town + " after all: " + why + ".", "I'm staying put — " + why + ".");
        } else if (took != null) {
            line = FolkTalk.pick(level.getRandom(), town + " took " + took + " for the " + o.title() + "'s place. Ah well.",
                "No luck with " + town + " — " + took + " has more years at it.", "They gave it to " + took + ". Never mind, I'll keep at it here.");
        } else {
            line = "Nothing doing in " + town + ". Ah well.";
        }
        JobSeekers.told(a.folk, line);
        VillageFolkEntity f = JobSeekers.live(level, a.folk);
        if (f != null) f.persona().remember(day, town + " turned me down for the " + o.title() + "'s place: " + why, 3);
    }

    // ------------------------------------------------------------------ leaving and coming (JobSeekers calls these)

    /** A hired folk (and its household) set off: the old town's books, the new town's road money. */
    static void departed(ServerLevel level, Villages.Village from, Villages.Village to, List<String> names, @Nullable StationTask trade,
                         String because, int roadMoneyTo, long day) {
        String who = join(names);
        String title = trade == null || trade == StationTask.NONE ? "hand" : noun(trade);
        Villages.tell(from.id(), day, who + " left for " + Villages.name(to.id()) + " to work there as " + a(title) + ", " + because
            + (roadMoneyTo > 0 ? " (" + Villages.name(to.id()) + " paid " + roadMoneyTo + (roadMoneyTo == 1 ? " coin" : " coins") + " road money)" : ""));
        for (String n : names) moved(from.id(), new Move(day, n, false, Villages.name(to.id()), trade == null ? "" : trade.name(), because));
        Raids.tellNear(level, from.centre(), 96, Component.literal(who + (names.size() == 1 ? " has" : " have") + " left " + Villages.name(from.id())
            + " for " + Villages.name(to.id()) + ", " + because + ".").withStyle(ChatFormatting.GOLD), false);
        Link link = link(from.id(), to.id());
        if (link == Link.RIVALS || link == Link.FEUD) {
            Ledger.relate(from.id(), to.id(), -3);
            Bonds.remember(from.id(), to.id(), day, -3, names.get(0) + " went over to " + Villages.name(to.id()));
        }
    }

    /** A newcomer is here: its card, the books, the morning assembly. */
    static void arrived(ServerLevel level, Villages.Village to, VillageFolkEntity f, UUID from, String fromName, @Nullable StationTask trade,
                        String because, boolean refugee, List<String> party, long day) {
        UUID id = to.id();
        Neighbourly.arrived(level, id, f, refugee ? "taken in after a raid on " + fromName : "came from " + fromName);   // [batchA] a welcome
        String title = trade == null || trade == StationTask.NONE ? "" : noun(trade);
        String story = refugee
            ? "came from " + fromName + ", homeless after the raid, and was taken in" + (title.isEmpty() ? "" : "; works as " + a(title))
            : "came from " + fromName + " " + because + (title.isEmpty() ? "" : ", to work as " + a(title));
        story(id, f.getUUID(), day, story);
        moved(id, new Move(day, f.displayNameCap(), true, fromName, trade == null ? "" : trade.name(), refugee ? "homeless after the raid" : because));
        if (party.isEmpty()) return;                                  // the rest of a household: the head tells it
        String who = join(party);
        if (refugee) {
            Villages.tell(id, day, who + " came from " + fromName + ", homeless after the raid, and " + (party.size() == 1 ? "was" : "were")
                + " taken in" + (title.isEmpty() ? "" : ": a bed found, and work as " + a(title)));
            Market.assemblyNews(id, who + " came to us from " + fromName + " after the raid. Make them welcome.");
        } else {
            Villages.tell(id, day, who + " came from " + fromName + " to work as our " + (title.isEmpty() ? "hand" : title) + ", " + because);
            Market.assemblyNews(id, who + " has come from " + fromName + " to be our " + (title.isEmpty() ? "newest hand" : title) + ". Make them welcome.");
        }
        // A hand sent between friends binds them a little closer.
        if (!refugee && link(id, from).ease > 0) Ledger.relate(id, from, 1);
    }

    /** Names in a list: "Fen", "Fen and Moss", "Fen, Moss and Ash". */
    static String join(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** The trade a newcomer taken in is set to: what the town is short of that it knows best, or what it needs most. */
    static StationTask fitFor(VillageFolkEntity f, UUID town) {
        StationTask best = null;
        double bestScore = 0.0;
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            double s = shortOf(town, t);
            if (s < 0.5) continue;
            double score = s + f.tradeLevel(t) * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best != null ? best : Villages.needed(town);
    }

    // ------------------------------------------------------------------ refugees

    /** While raiders are at the village: who has a bed, so a bed gone by the morning is a home lost. */
    private static void watchTheRaid(Villages.Village v) {
        UUID id = v.id();
        if (!Raids.raided(id)) return;
        Map<UUID, BlockPos> beds = BEDS_AT_RAID.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        for (AssistantEntity a : Villages.folkOf(id)) {
            BlockPos b = a.bedPos();
            if (b != null) beds.putIfAbsent(a.getUUID(), b.immutable());
        }
    }

    /** From Raids.end: the raid is over and the village counts its losses. Returns how many went to another town. */
    public static int raided(ServerLevel level, Villages.Village v, int lost) {
        return raided(level, v, lost, false, false);
    }

    /**
     * A village after a bad raid: any whose homes were lost (the beds gone from under them) and who
     * cannot be found a bed at home, or everybody if so few are left that the village gives itself
     * up, go with their households to the nearest friendly town with room. Returns how many went.
     */
    static int raided(ServerLevel level, Villages.Village v, int lost, boolean fellBefore, boolean again) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Map<UUID, BlockPos> hadBeds = BEDS_AT_RAID.remove(id);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isShowcase() && !f.isHired() && !JobSeekers.travelling(f)) folk.add(f);
        }
        int grown = JobSeekers.grownIn(folk);
        boolean falls = grown > 0 && grown <= FALLEN && (lost > 0 || fellBefore);
        Set<VillageFolkEntity> homeless = new LinkedHashSet<>();
        if (falls) {
            homeless.addAll(folk);
        } else {
            for (VillageFolkEntity f : folk) if (!f.isBaby() && lostItsHome(level, id, f, hadBeds)) homeless.add(f);
            // A bed still to be had at home first: an empty house, or a spare bed at the camp.
            for (java.util.Iterator<VillageFolkEntity> it = homeless.iterator(); it.hasNext(); ) {
                VillageFolkEntity f = it.next();
                f.forgetBed();
                Homes.Home h = Homes.vacancyFor(level, id, List.of(f));
                if (h != null) {
                    Homes.settle(level, v, h, new ArrayList<>(List.of(f)), day);
                    it.remove();
                } else if (f.claimBedNear(v.centre())) {
                    it.remove();
                }
            }
        }
        if (homeless.isEmpty()) {
            if (again) Ledger.forget(id, "jm.raid");
            return 0;
        }
        Ledger.note(id, "jm.raid", day + FLD + (falls ? 1 : 0));
        // Households: each homeless grown folk with its partner and children (theirs go with them, homeless or not).
        List<List<VillageFolkEntity>> households = new ArrayList<>();
        Set<UUID> placed = new java.util.HashSet<>();
        for (VillageFolkEntity f : homeless) {
            if (f.isBaby() || placed.contains(f.getUUID())) continue;
            List<VillageFolkEntity> h = new ArrayList<>();
            for (VillageFolkEntity m : JobSeekers.household(f)) if (placed.add(m.getUUID())) h.add(m);
            households.add(h);
        }
        for (VillageFolkEntity f : homeless) {
            if (placed.add(f.getUUID())) households.add(new ArrayList<>(List.of(f)));     // a child left alone: it goes too
        }
        int sent = 0;
        List<String> stayed = new ArrayList<>();
        for (List<VillageFolkEntity> h : households) {
            // (Each household sent is on the new town's roll at once, so the next is weighed against the room left.)
            Villages.Village to = refuge(level, v, h.size());
            if (to == null) {
                for (VillageFolkEntity m : h) if (!m.isBaby()) stayed.add(m.displayNameCap());
                continue;
            }
            List<String> names = new ArrayList<>();
            for (VillageFolkEntity m : h) if (!m.isBaby()) names.add(m.displayNameCap());
            int kids = h.size() - names.size();
            String who = join(names) + (kids > 0 ? (kids == 1 ? " with a child" : " with " + kids + " children") : "");
            JobSeekers.refuge(level, h, v, to);
            sent += h.size();
            String toName = Villages.name(to.id());
            Villages.tell(id, day, who + ", homeless after the raid, went to " + toName + ", which took them in");
            for (String n : names) moved(id, new Move(day, n, false, toName, "", "homeless after the raid"));
            Bonds.remember(id, to.id(), day, 6, toName + " took in " + Villages.name(id) + "'s homeless after the raid");
            Ledger.relate(id, to.id(), 6);
            Raids.tellNear(level, v.centre(), 160, Component.literal(who + ", homeless after the raid on " + Villages.name(id)
                + ", " + (h.size() == 1 ? "has" : "have") + " gone to " + toName + ", which will take them in.").withStyle(ChatFormatting.GOLD), false);
            LOG.info("[MCA-JOBS] {}: {} homeless after the raid go to {}", Villages.name(id), who, toName);
        }
        if (!stayed.isEmpty() && !again) {
            Villages.tell(id, day, join(stayed) + (stayed.size() == 1 ? " has" : " have") + " nowhere to sleep, and no neighbour with room to take them in yet");
        }
        if (falls && stayed.isEmpty()) {
            giveUp(level, v, homeless, day);
        } else if (stayed.isEmpty()) {
            Ledger.forget(id, "jm.raid");
        }
        return sent;
    }

    /**
     * Everybody has gone from a fallen village: what was in its treasury goes with them, shared out
     * among their purses, and the village is let go as one whose last folk died is (its ground let
     * sleep, its register forgotten); its chronicle keeps what happened.
     */
    private static void giveUp(ServerLevel level, Villages.Village v, Set<VillageFolkEntity> went, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> grown = new ArrayList<>();
        for (VillageFolkEntity f : went) if (!f.isBaby()) grown.add(f);
        int coins = grown.isEmpty() ? 0 : Ledger.takeCoins(id, Ledger.coins(id));
        for (int i = 0; i < grown.size() && coins > 0; i++) {
            int share = coins / (grown.size() - i);
            grown.get(i).earn(share);
            coins -= share;
        }
        Villages.tell(id, day, Villages.name(id) + " was given up after the raid: too few were left to keep it");
        Ledger.forget(id, "jm.raid");
        if (!Villages.folkOf(id).isEmpty() || Villages.recordedPopulation(id) > 0) return;    // somebody is still on the roll
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, id, v.centre(), com.jrpetty.mcassistant.VillageSpawner.MAX_LOADED_RADIUS, false);
        Villages.forget(id);
        LOG.info("[MCA-JOBS] {} was given up after the raid", Villages.name(id));
    }

    /** Has this folk lost its home: its bed gone from under it, or its house left with no bed in it? */
    static boolean lostItsHome(ServerLevel level, UUID village, VillageFolkEntity f, @Nullable Map<UUID, BlockPos> hadBeds) {
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h != null && Ledger.raising(village, h.anchor)) return false;         // its house is going up a storey: its beds come back
        BlockPos bed = f.bedPos();
        if (bed != null && level.isLoaded(bed) && !isBed(level, bed)) return true;
        BlockPos had = hadBeds == null ? null : hadBeds.get(f.getUUID());
        if (had != null && level.isLoaded(had) && !isBed(level, had) && (bed == null || bed.equals(had))) return true;
        return h != null && level.isLoaded(h.anchor) && Homes.bedsIn(level, village, h).isEmpty();
    }

    private static boolean isBed(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    /** The nearest friendly town with room for so many, or null. */
    @Nullable
    static Villages.Village refuge(ServerLevel level, Villages.Village from, int n) {
        Villages.Village best = null;
        double bestScore = Double.MAX_VALUE;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(from.id()) || !o.dim().equals(from.dim())) continue;
            Link link = link(from.id(), o.id());
            if (link == Link.NONE || link == Link.RIVALS || link == Link.FEUD) continue;
            if (Villages.folkOf(o.id()).isEmpty()) continue;
            int room = room(level, o.id());
            if (room < n) continue;
            double score = Math.sqrt(o.centre().distSqr(from.centre())) - link.ease * 20.0;
            if (score < bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ telling

    /**
     * The board's lines, kept short (the left column has much to say): the Wanted notices (two at
     * most, and how many have applied), who is on the road here, who came and went this week, and
     * the best of what other towns want that word of has come here.
     */
    public static List<String> board(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        List<Opening> open = open(id);
        List<Application> apps = open.isEmpty() ? List.of() : applications(id);
        int shown = 0;
        for (Opening o : open) {
            if (shown >= 2) break;
            int n = 0;
            for (Application a : apps) if (a.opening == o.id && a.verdict == Verdict.WAITING) n++;
            boolean ages = o.ageLo > 18 || o.ageHi < 200;
            out.add("LW|Wanted: " + a(o.title()) + ", " + o.wage + (o.wage == 1 ? " coin" : " coins") + " a day"
                + (o.minLevel > 0 ? ", level " + o.minLevel + "+" : "")
                + (ages ? ", " + (o.ageHi >= 200 ? o.ageLo + "+" : (o.ageLo > 18 ? o.ageLo : 18) + "-" + o.ageHi) + " years" : "")
                + (n > 0 ? " — " + n + " applied" : "") + (shown == 1 && open.size() > 2 ? " (and " + (open.size() - 2) + " more)" : "") + ".");
            shown++;
        }
        List<String> road = JobSeekers.onTheRoadTo(id);
        if (!road.isEmpty()) out.add("LG|On the road to us: " + String.join(", ", road) + ".");
        List<String> came = new ArrayList<>(), went = new ArrayList<>();
        for (Move m : moves(id)) {
            if (day - m.day() >= 7) continue;
            (m.in() ? came : went).add(m.name() + (m.in() ? " from " : " to ") + m.other());
        }
        if (!came.isEmpty() || !went.isEmpty()) {
            out.add("LN|This week: " + (came.isEmpty() ? "" : "came " + String.join(", ", tail(came, 2)) + (came.size() > 2 ? " and more" : ""))
                + (!came.isEmpty() && !went.isEmpty() ? "; " : "")
                + (went.isEmpty() ? "" : "left " + String.join(", ", tail(went, 2)) + (went.size() > 2 ? " and more" : "")) + ".");
        }
        List<Seen> seen = seen(id, day);
        if (!seen.isEmpty()) {
            Seen s = seen.get(0);
            out.add("LM|Word from " + Villages.name(s.town()) + ": wanted, " + a(s.opening().title()) + ", " + s.opening().wage + " a day"
                + (seen.size() > 1 ? " (" + (seen.size() - 1) + " more elsewhere)" : "") + ".");
        }
        return out;
    }

    private static List<String> tail(List<String> l, int n) {
        return l.size() <= n ? l : l.subList(l.size() - n, l.size());
    }

    /**
     * The market for the city books' Jobs page (client/JobMarketPage): the notices and how each went,
     * the applications with each applicant's years and level and the verdict, who came and went this
     * week and why, those on the road here, and what is wanted elsewhere that word of has come here.
     */
    public static CompoundTag report(ServerLevel level, UUID id) {
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        ListTag ops = new ListTag();
        Map<Integer, String> titles = new HashMap<>();
        List<Opening> all = openings(id);
        all.sort((x, y) -> x.state != y.state ? Integer.compare(x.state.ordinal(), y.state.ordinal()) : Long.compare(y.posted, x.posted));
        List<Application> apps = applications(id);
        int openNow = 0;
        for (Opening o : all) {
            titles.put(o.id, o.title());
            if (o.state == State.OPEN) openNow++;
            CompoundTag c = new CompoundTag();
            c.putInt("id", o.id);
            c.putString("title", o.title());
            StationTask t = o.task();
            c.putInt("ordinal", t == null ? 0 : t.ordinal());
            c.putInt("wage", o.wage);
            c.putString("wants", o.wants());
            c.putString("why", o.why);
            c.putLong("posted", o.posted);
            c.putString("state", o.state.name().toLowerCase(Locale.ROOT));
            c.putString("by", o.by);
            c.putString("from", o.from);
            c.putString("note", o.note);
            int n = 0;
            for (Application a : apps) if (a.opening == o.id) n++;
            c.putInt("applied", n);
            ops.add(c);
        }
        out.put("openings", ops);
        ListTag ap = new ListTag();
        apps.sort((x, y) -> x.verdict == Verdict.WAITING && y.verdict != Verdict.WAITING ? -1
            : y.verdict == Verdict.WAITING && x.verdict != Verdict.WAITING ? 1 : Long.compare(y.tick, x.tick));
        for (Application a : apps) {
            CompoundTag c = new CompoundTag();
            c.putString("name", a.name);
            c.putString("from", a.fromName);
            c.putString("title", titles.getOrDefault(a.opening, noun(a.trade)));
            c.putInt("level", a.level);
            c.putInt("age", a.age);
            c.putInt("knacks", a.knacks);
            c.putString("reasons", a.reasons);
            c.putLong("day", a.day);
            c.putString("verdict", a.verdict.name().toLowerCase(Locale.ROOT));
            c.putString("why", a.why);
            ap.add(c);
        }
        out.put("applications", ap);
        ListTag mv = new ListTag();
        int came = 0, went = 0;
        List<Move> moves = moves(id);
        for (int i = moves.size() - 1; i >= 0; i--) {
            Move m = moves.get(i);
            if (day - m.day() >= 7) continue;
            if (m.in()) came++;
            else went++;
            CompoundTag c = new CompoundTag();
            c.putLong("day", m.day());
            c.putString("name", m.name());
            c.putBoolean("in", m.in());
            c.putString("other", m.other());
            c.putString("title", m.trade().isEmpty() ? "" : noun(m.trade()));
            c.putString("why", m.why());
            mv.add(c);
        }
        out.put("moves", mv);
        ListTag road = new ListTag();
        for (String s : JobSeekers.onTheRoadTo(id)) road.add(StringTag.valueOf(s));
        out.put("road", road);
        ListTag seen = new ListTag();
        for (Seen s : seen(id, day)) {
            if (seen.size() >= 8) break;
            seen.add(StringTag.valueOf(Villages.name(s.town()) + " (" + s.link().words + ", " + s.distance() + " blocks): wanted, "
                + a(s.opening().title()) + ", " + s.opening().wage + " a day; " + s.opening().wants()));
        }
        out.put("seen", seen);
        int waiting = 0;
        for (Application a : apps) if (a.verdict == Verdict.WAITING) waiting++;
        out.putInt("open", openNow);
        out.putInt("waiting", waiting);
        out.putInt("came", came);
        out.putInt("went", went);
        return out;
    }

    /** What a folk's card says of it and the market: on the road, applied, taken on, or where it came from. */
    public static String cardLine(VillageFolkEntity f) {
        String road = JobSeekers.cardLine(f);
        if (road != null) return road;
        UUID home = f.ownerId();
        if (home == null) return "";
        String s = Ledger.note(home, "jm.story/" + f.getUUID());
        if (s == null || s.isEmpty()) return "";
        int bar = s.indexOf('|');
        long d = leadingDay(s);
        String text = bar < 0 ? s : s.substring(bar + 1);
        return capital(text) + (d >= 0 ? " (day " + (d + 1) + ")" : "");
    }

    /** The market in a few lines, for /village jobs. */
    public static List<String> status(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        out.add("JOBS " + Villages.name(id) + " (day " + (day + 1) + ")");
        BlockPos board = VillageBoards.boardOf(id);
        net.minecraft.core.Direction facing = VillageBoards.facingOf(id);
        if (board != null && facing != null) {
            out.add("  Board: " + board.getX() + " " + board.getY() + " " + board.getZ() + " facing " + facing.getName());
        }
        List<Opening> all = openings(id);
        List<Application> apps = applications(id);
        if (all.isEmpty()) out.add("  No notices have gone up.");
        for (Opening o : all) {
            out.add("  #" + o.id + " " + o.state.name().toLowerCase(Locale.ROOT) + ": " + a(o.title()) + ", " + o.wage + " a day; " + o.wants()
                + " — " + o.why + (o.by.isEmpty() ? "" : "; filled by " + o.by + " of " + o.from) + (o.note.isEmpty() ? "" : " (" + o.note + ")"));
            for (Application a : apps) {
                if (a.opening != o.id) continue;
                out.add("     " + a.name + " of " + a.fromName + ", level " + a.level + ", " + a.age + " years, " + a.knacks + " knacks ("
                    + a.reasons + "): " + a.verdict.name().toLowerCase(Locale.ROOT) + (a.why.isEmpty() ? "" : " — " + a.why));
            }
        }
        for (String s : JobSeekers.onTheRoadTo(id)) out.add("  On the road here: " + s);
        for (Seen s : seen(id, day)) {
            out.add("  Heard from " + Villages.name(s.town()) + " (" + s.link().words + "): wanted, " + a(s.opening().title()) + ", "
                + s.opening().wage + " a day");
        }
        for (Move m : moves(id)) {
            if (day - m.day() >= 7) continue;
            out.add("  Day " + (m.day() + 1) + ": " + m.name() + (m.in() ? " came from " : " left for ") + m.other()
                + (m.trade().isEmpty() ? "" : " (" + noun(m.trade()) + ")") + ", " + m.why());
        }
        return out;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
