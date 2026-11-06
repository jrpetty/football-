package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.InventionTree;
import com.jrpetty.mcassistant.village.InventionTree.Branch;
import com.jrpetty.mcassistant.village.InventionTree.Invention;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [tech-tree] A town's inventions: the machines, buildings and arms its scholars work out, one at a time, over days.
 *
 * <h2>Beside the civics</h2>
 * The city's research (CityTree) is the town's way of running itself, and its points come from its headcount every
 * morning. Inventions are things: the windmill's millstones, the watermill, the aqueduct, the clocktower, the treadwheel
 * crane, steel, the pike and the longbow, double-entry ledgers, lenses (the tree is village/InventionTree). They are
 * worked out by the town's <b>scholars</b> (Scholars: the curious, the readers, the teacher, the curator, a few for every
 * town), in their own time, at their desks in the academy (or the library, the school, the hall), and their learning is
 * what pays for them. Both show on the Civic section of the town's books, the civics on Research and these on Inventions.
 *
 * <h2>A morning</h2>
 * With the day's books (Market.tick, straight after CityTree.morning), once a day:
 * <ul>
 * <li>the scholars are seen to (Scholars.appoint): one for a town of six, one more for every fifteen folk, four at most;
 *     the dead and the gone replaced by the next best head in the town;</li>
 * <li>with nothing in hand, the leader chooses what the scholars take up next, by its heart and the town's need ("a
 *     Guardian's choice: the walls want pikes"; a Provider leans to the plough and the mills; a town at war to arms; a
 *     hungry one to the fields), a player's suggestion weighed in, the cheaper first;</li>
 * <li>the working model's makings and the notes' paper, ink and books come out of the stores; till they are there the
 *     work goes no further than half way ("waiting on the stores for 4 string");</li>
 * <li>the day's learning goes in: each scholar's own reading, its evenings at the desk the day before (Scholars.hold),
 *     the library's, the press's and the lenses' share; half as much at war or in a famine, a trickle with no scholar
 *     at all. Word of it from a trading partner who knows it adds a little a day; a spy home from a rival who knows it
 *     brings back their sketches (two fifths of it at a stroke); a vassal's scholars teach the overlord what they know;</li>
 * <li>now and then a <b>eureka</b> (a quarter of it at once, a chime, the scholar beside itself) and, at the harder
 *     ones, a <b>failed experiment</b> (a tenth of it lost, a bang and a cloud of smoke at the academy);</li>
 * <li>the learning paid, the model tried and its days served, it is <b>known</b>: in the chronicle (and so the crier and
 *     the gazette), at the morning assembly, a fanfare for anyone in the town and the leader's word; the first town in
 *     the world to work it out has it said everywhere; its working model goes into the stores for the museum.</li>
 * </ul>
 *
 * <h2>Where it is kept</h2>
 * With the world (this SavedData, "mc_assistant_inventions"): for each town what it knows and the day, what it is
 * working at, its learning so far (hundredths), the day it began, who chose it and why, whether its makings are paid,
 * what the players gave and suggested, its eurekas, failures and notes, the spies' reports already used, and its
 * scholars; and for the world, the first town to work out each invention. A conquered (vassal) town's knowledge goes to
 * its overlord a piece at a time, as its scholars are made to teach.
 *
 * <p>The public face for the other helpers' work: {@link #known(UUID, String)}, {@link #researching(UUID)} and
 * {@link #knownIds(UUID)}.
 */
public final class Inventions extends SavedData {

    private static final String ID = "mc_assistant_inventions";

    /** Everything kept: "towns" (a compound each) and "firsts" (the world's first, by invention). */
    private CompoundTag root = new CompoundTag();
    @Nullable private static Inventions loose;

    public Inventions() {}

    // ------------------------------------------------------------------ the numbers

    /** A scholar's own reading, a day, in hundredths of a point: one and a half, and up to one more for the best heads. */
    static final int READING = 150;
    /** An evening's beat at the desk (Scholars.BEAT), in hundredths; half again in the academy; the most beats a day counted. */
    static final int BEAT = 25, BEATS_MOST = 16;
    /** With no scholar at all, the elder's own tinkering: half a point a day, for a town of this many. */
    static final int TRICKLE = 50, TRICKLE_FROM = 5;
    /** The library's share, the press's (Movable Type), the lenses', in percent more. */
    static final int LIBRARY = 10, PRESS = 20, LENS = 25;
    /** At war, in a famine, a vassal paying tribute: in percent less. */
    static final int WAR = 40, FAMINE = 40, VASSAL = 20;
    /** Word along a trade road from a partner who knows it, a day (hundredths); twice with the press. */
    static final int ROAD = 60;
    /** A spy's sketches: this share of the cost, in percent. A eureka's share; a failure's. */
    static final int STOLEN = 40, EUREKA = 25, FAILED = 10;
    /** The chance in a thousand of a eureka a morning (and a thousandth more a point of the best scholar's head). */
    static final int EUREKA_CHANCE = 50, FAILED_CHANCE = 80;
    /** A coin's worth of sponsorship, in hundredths: five coins a point; at most this share of the cost from players. */
    static final int COIN = 20, SPONSOR_MOST = 50;
    /** A book's worth, a written book's, an enchanted book's (hundredths). */
    static final int BOOK = 200, WRITTEN = 400, ENCHANTED = 600;
    /** A vassal's scholars teach the overlord one thing every so many days. */
    static final int TEACH_EVERY = 3;
    /** The working model is built (its makings taken) once this share of the learning is in, in percent. */
    static final int MODEL_AT = 25;

    // ------------------------------------------------------------------ the record

    static Inventions of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Inventions();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(Inventions::new, Inventions::load, null), ID);
    }

    public static Inventions load(CompoundTag tag, HolderLookup.Provider registries) {
        Inventions i = new Inventions();
        i.root = tag.getCompound("Root");
        return i;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Root", root.copy());
        return tag;
    }

    static CompoundTag town(UUID village) {
        return Civics.sub(Civics.sub(of().root, "towns"), village.toString());
    }

    static CompoundTag firsts() {
        return Civics.sub(of().root, "firsts");
    }

    static void changed(@Nullable UUID village) {
        of().setDirty();
        if (village != null) KNOWN.remove(village);
    }

    /** What each town knows, read once and kept till it changes: asked on every pace sum of every worker. */
    private static final Map<UUID, Set<Invention>> KNOWN = new ConcurrentHashMap<>();

    /** Everything forgotten (the tests share one world). A world opening keeps what its towns know. */
    public static void resetForTests() {
        if (!com.jrpetty.mcassistant.SessionReset.opening()) {
            of().root = new CompoundTag();
            of().setDirty();
        }
        KNOWN.clear();
        rollForTests = null;
        Scholars.resetForTests();
        InventionWorks.resetForTests();
        InventionArms.resetForTests();
    }

    /** Tests: this town's inventions forgotten, its scholars too. */
    public static void clearForTests(UUID village) {
        Civics.sub(of().root, "towns").remove(village.toString());
        changed(village);
    }

    // ------------------------------------------------------------------ the public face

    /** Does this town know this invention (by its id, "windmill", "steel")? False for an id there is no such invention of. */
    public static boolean known(@Nullable UUID town, String id) {
        Invention i = InventionTree.byId(id);
        return i != null && known(town, i);
    }

    public static boolean known(@Nullable UUID town, Invention i) {
        return town != null && knownSet(town).contains(i);
    }

    /** The id of what this town's scholars are working at now, or null. */
    @Nullable
    public static String researching(@Nullable UUID town) {
        Invention i = current(town);
        return i == null ? null : i.id();
    }

    /** Every invention this town knows, by id, in the order it worked them out. */
    public static List<String> knownIds(@Nullable UUID town) {
        List<String> out = new ArrayList<>();
        if (town == null) return out;
        for (Invention i : knownDays(town).keySet()) out.add(i.id());
        return out;
    }

    /** Every invention there is, by id. */
    public static List<String> allIds() {
        List<String> out = new ArrayList<>();
        for (Invention i : Invention.values()) out.add(i.id());
        return out;
    }

    static Set<Invention> knownSet(UUID town) {
        Set<Invention> got = KNOWN.get(town);
        if (got != null) return got;
        EnumSet<Invention> s = EnumSet.noneOf(Invention.class);
        s.addAll(knownDays(town).keySet());
        Set<Invention> kept = Collections.unmodifiableSet(s);
        KNOWN.put(town, kept);
        return kept;
    }

    /** What it knows, with the day each was worked out, in that order. */
    static Map<Invention, Long> knownDays(UUID town) {
        Map<Invention, Long> out = new LinkedHashMap<>();
        CompoundTag k = Civics.sub(town(town), "known");
        List<Object[]> rows = new ArrayList<>();
        for (String key : k.getAllKeys()) {
            Invention i = InventionTree.byId(key);
            if (i != null) rows.add(new Object[]{ i, k.getLong(key) });
        }
        rows.sort((a, b) -> Long.compare((Long) a[1], (Long) b[1]) != 0 ? Long.compare((Long) a[1], (Long) b[1])
            : Integer.compare(((Invention) a[0]).ordinal(), ((Invention) b[0]).ordinal()));
        for (Object[] r : rows) out.put((Invention) r[0], (Long) r[1]);
        return out;
    }

    /** What its scholars are at now, or null. */
    @Nullable
    public static Invention current(@Nullable UUID town) {
        return town == null ? null : InventionTree.byId(town(town).getString("now"));
    }

    /** Its learning toward the one in hand, in hundredths of a point. */
    public static int progress(@Nullable UUID town) {
        return town == null ? 0 : town(town).getInt("prog");
    }

    /** Is this invention open to the town now: its age come, its needs known, not known itself? */
    public static boolean open(UUID town, Invention i) {
        return InventionTree.open(i, knownSet(town), Villages.ageOf(town).ordinal());
    }

    public static List<Invention> openNow(UUID town) {
        List<Invention> out = new ArrayList<>();
        for (Invention i : Invention.values()) if (open(town, i)) out.add(i);
        return out;
    }

    /** Why it is not open, in a few words ("needs the Pike", "waits for the Iron Age"), or "" if it is. */
    public static String lockedWhy(UUID town, Invention i) {
        if (known(town, i)) return "known";
        List<String> want = new ArrayList<>();
        for (Invention n : i.needs()) if (!known(town, n)) want.add(n.title);
        if (!want.isEmpty()) return "needs " + Civics.names(want);
        if (Villages.ageOf(town).ordinal() < i.age) return "waits for " + i.ageWords();
        return "";
    }

    // ------------------------------------------------------------------ the day's learning

    /** A morning's learning (hundredths) and what it comes from, in words. */
    public record Rate(int hundredths, String why) {
        public String perDay() {
            return hundredths % 100 == 0 ? Integer.toString(hundredths / 100) : String.format(java.util.Locale.ROOT, "%.2f", hundredths / 100.0);
        }
    }

    /**
     * What the scholars bring in a day: each one's reading and its evenings at the desk yesterday (Scholars), half again
     * in the academy; the library, the press and the lenses; less at war, in a famine and under a lord.
     */
    public static Rate rate(@Nullable ServerLevel level, UUID village, long day) {
        List<String> why = new ArrayList<>();
        int h = 0;
        List<UUID> scholars = Scholars.appointed(village);
        if (scholars.isEmpty()) {
            if (Villages.headcount(village) >= TRICKLE_FROM) {
                h = TRICKLE;
                why.add("no scholars: the elder's own tinkering 0.5");
            } else {
                why.add("no scholars, and too few folk to spare one");
            }
        } else {
            int reading = 0, desk = 0;
            for (UUID s : scholars) {
                reading += READING + Scholars.aptitude(level, s);
                desk += Math.min(BEATS_MOST, Scholars.beats(s, day - 1)) * BEAT * (Scholars.atAcademy(s, day - 1) ? 3 : 2) / 2;
            }
            h = reading + desk;
            why.add(scholars.size() + (scholars.size() == 1 ? " scholar's" : " scholars'") + " reading " + fmt(reading));
            if (desk > 0) why.add("evenings at the desk " + fmt(desk));
        }
        int pct = 100;
        if (InventionWorks.stands(village, Library.STRUCTURE) || InventionWorks.stands(village, "library")) { pct += LIBRARY; why.add("the library +" + LIBRARY + "%"); }
        if (known(village, Invention.MOVABLE_TYPE)) { pct += PRESS; why.add("the press +" + PRESS + "%"); }
        if (known(village, Invention.LENSES)) { pct += LENS; why.add("lenses +" + LENS + "%"); }
        if (atWar(village)) { pct -= WAR; why.add("at war -" + WAR + "%"); }
        if (Leader.plan(village) == Leader.Plan.FAMINE) { pct -= FAMINE; why.add("famine -" + FAMINE + "%"); }
        if (Vassals.isVassal(village)) { pct -= VASSAL; why.add("tribute to a lord -" + VASSAL + "%"); }
        h = h * Math.max(10, pct) / 100;
        return new Rate(h, String.join(", ", why));
    }

    static boolean atWar(UUID village) {
        return !Wars.enemies(village).isEmpty() || Raids.underAlarm(village);
    }

    static String fmt(int hundredths) {
        return hundredths % 100 == 0 ? Integer.toString(hundredths / 100) : String.format(java.util.Locale.ROOT, "%.2f", hundredths / 100.0);
    }

    /** Days to go on the one in hand at today's rate (and the model's days), or -1 with nothing in hand. */
    public static int daysLeft(@Nullable ServerLevel level, UUID village, long day) {
        Invention i = current(village);
        if (i == null) return -1;
        CompoundTag t = town(village);
        int left = i.cost * 100 - t.getInt("prog");
        int served = (int) Math.max(0, day - t.getLong("since"));
        return InventionTree.daysLeft(left, rate(level, village, day).hundredths(), i.days - served);
    }

    // ------------------------------------------------------------------ the morning

    /** Tests: the next rolls of the dice (a eureka, a failure), in that order, instead of the world's: null as it falls. */
    @Nullable static int[] rollForTests;

    /**
     * The town's morning for its inventions (Market.tick, after CityTree.morning): once a day, whatever calls it. The
     * scholars seen to, the overlord taught, the day's learning in, the makings paid, a eureka or a failure, and done.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.headcount(id) < TRICKLE_FROM && knownSet(id).isEmpty() && current(id) == null) return;   // a hamlet: no inventing yet
        CompoundTag t = town(id);
        if (t.contains("day") && t.getLong("day") >= day) return;
        t.putLong("day", day);
        changed(null);
        Scholars.appoint(level, v, day);
        InventionWorks.fitOut(level, v);                             // the academy's desks, out of the stores
        teachOverlord(level, v, day);
        Invention now = current(id);
        if (now == null || !open(id, now) && !known(id, now)) now = choose(level, id, day);
        if (now == null) return;
        if (known(id, now)) {                                       // granted another way (a lord's, an admin's): on to the next
            clearWork(id);
            now = choose(level, id, day);
            if (now == null) return;
        }
        // The model is built once the idea is a quarter worked out on paper: its makings out of the stores then.
        if (!t.getBoolean("paid") && t.getInt("prog") >= now.cost * MODEL_AT) pay(level, v, now, day);
        Rate r = rate(level, id, day);
        int gain = r.hundredths() + road(id, now, day);
        gain += stolen(level, id, now, day);
        int prog = t.getInt("prog") + gain;
        prog += luck(level, v, now, day, t);
        int cap = t.getBoolean("paid") ? Integer.MAX_VALUE : now.cost * 50;   // half way, till the model's makings come
        if (prog > cap && !t.getBoolean("capped")) {
            t.putBoolean("capped", true);
            Villages.tell(id, day, "the scholars got half way with " + now.title + " and wait on the stores for its working model: "
                + String.join(", ", short_(level, v, now)));
        }
        t.putInt("prog", Math.max(0, Math.min(prog, cap)));
        t.putInt("lastGain", gain);
        changed(null);
        if (!t.getBoolean("paid") && t.getInt("prog") >= now.cost * MODEL_AT) pay(level, v, now, day);
        if (t.getInt("prog") >= now.cost * 100 && t.getBoolean("paid") && day - t.getLong("since") >= now.days) {
            complete(level, id, now, day, "worked out");
            choose(level, id, day);
        }
    }

    /** The one in hand set aside (known by now, or no longer open). */
    static void clearWork(UUID village) {
        CompoundTag t = town(village);
        for (String k : new String[]{ "now", "prog", "since", "by", "why", "paid", "capped", "sponsored", "short" }) t.remove(k);
        changed(null);
    }

    // ------------------------------------------------------------------ choosing

    /** One invention the chooser might take, its score and why. */
    public record Weighed(Invention invention, double score, String why, String said) {}

    /** How much each of the seven things a leader can care about leans to a branch, in the hundred. */
    static int affinity(Branch b, Values.Value v) {
        return switch (b) {
            case AGRICULTURE -> v == Values.Value.FOOD ? 100 : v == Values.Value.TRADITION ? 30 : 0;
            case ENGINEERING -> v == Values.Value.PROGRESS ? 80 : v == Values.Value.HOMES ? 60 : 0;
            case MILITARY -> v == Values.Value.SAFETY ? 100 : v == Values.Value.TRADITION ? 10 : 0;
            case LEARNING -> v == Values.Value.PROGRESS ? 70 : v == Values.Value.WEALTH ? 60 : v == Values.Value.LEISURE ? 20 : 0;
        };
    }

    /** A leader's nature, leaning it to a branch. */
    static int traitPull(Branch b, Social.Trait t) {
        return switch (t) {
            case GRUMPY -> b == Branch.MILITARY ? 10 : 0;
            case HARDWORKING -> b == Branch.ENGINEERING ? 8 : b == Branch.AGRICULTURE ? 4 : 0;
            case CURIOUS -> b == Branch.LEARNING ? 10 : b == Branch.ENGINEERING ? 4 : 0;
            case GENEROUS -> b == Branch.AGRICULTURE ? 8 : 0;
            case SHY -> b == Branch.AGRICULTURE ? 4 : b == Branch.LEARNING ? 3 : 0;
            case SOCIABLE -> b == Branch.LEARNING ? 4 : 0;
            case CHEERFUL -> b == Branch.ENGINEERING ? 3 : 0;
            case EASYGOING -> b == Branch.AGRICULTURE ? 3 : 0;
        };
    }

    /** {chronicle, its own words} for a branch chosen out of the leader's heart. */
    static String[] branchWords(Branch b, Values.Value v) {
        return switch (b) {
            case AGRICULTURE -> new String[]{ "a " + v.type + "'s choice: bread first", "bread first, and the rest after" };
            case ENGINEERING -> new String[]{ "a " + v.type + "'s choice: build it better", "if we can build it better, we should" };
            case MILITARY -> new String[]{ "a " + v.type + "'s choice: the walls want better arms", "the walls want better arms" };
            case LEARNING -> new String[]{ "a " + v.type + "'s choice: knowledge pays", "knowledge pays, in the end" };
        };
    }

    /** Every open invention, weighed as the leader (or the council) would, best first. */
    public static List<Weighed> weigh(ServerLevel level, UUID village) {
        VillageFolkEntity elder = CityTree.chooserFolk(village);
        CityTree.Needs needs = CityTree.needs(level, village);
        boolean war = WarFooting.ready(village) || atWar(village);
        Invention suggested = InventionTree.byId(town(village).getString("suggest"));
        String suggester = town(village).getString("suggestBy");
        List<Weighed> out = new ArrayList<>();
        for (Invention i : openNow(village)) {
            CityTree.Appeal a = new CityTree.Appeal();
            if (elder != null) {
                int[] w = Values.of(elder);
                for (Values.Value v : Values.Value.values()) {
                    int aff = affinity(i.branch, v);
                    if (aff > 0) {
                        String[] words = branchWords(i.branch, v);
                        a.add(w[v.ordinal()] * aff / 200.0, words[0], words[1]);
                    }
                }
                for (Social.Trait tr : elder.life().traits()) {
                    String[] words = CityTree.traitWords(tr);
                    a.add(traitPull(i.branch, tr), words[0], words[1]);
                }
            }
            switch (i.branch) {
                case AGRICULTURE -> a.add(needs.food(), needs.foodWhy(), needs.foodWhy());
                case MILITARY -> {
                    a.add(needs.safety(), "the raiders were at the gate", "the raiders were at the gate");
                    if (war) a.add(30, "the town is at war", "we're at war, and we need better arms");
                }
                case ENGINEERING -> a.add(needs.homes() / 2, needs.homesWhy(), needs.homesWhy());
                case LEARNING -> {
                    if (i == Invention.DOUBLE_ENTRY) a.add(needs.money(), "the treasury's thin", "the treasury's thin");
                    if (i == Invention.MEDICINE) a.add(needs.mood() / 2, needs.moodWhy(), needs.moodWhy());
                }
            }
            if (i.building != null && !"windmill".equals(i.building)) a.add(6, "a fine new building for the town", "it'll give the town a fine new building");
            if (i == suggested) a.add(25, (suggester.isEmpty() ? "a player" : suggester) + " asked for it",
                (suggester.isEmpty() ? "a friend of the town" : suggester) + " asked us to look into it");
            if (InventionArms.ofArms(i) && war) a.add(10, "the army wants it", "the army wants it");
            // Half of whatever pulls hardest further on that needs it, and the cheaper first.
            double path = 0;
            Invention toward = null;
            for (Invention later : Invention.values()) {
                if (!later.needs().contains(i) || known(village, later)) continue;
                double p = 0;
                if (elder != null) {
                    int[] w = Values.of(elder);
                    for (Values.Value v : Values.Value.values()) p += w[v.ordinal()] * affinity(later.branch, v) / 400.0;
                }
                if (later == suggested) p += 12;
                if (p > path) { path = p; toward = later; }
            }
            double score = a.total + path * 0.5 - i.cost / 12.0;
            String why, said;
            if (toward != null && path * 0.5 > a.best) {
                why = "the road to " + toward.title;
                said = "it's the road to " + toward.title;
            } else if (!a.why.isEmpty()) {
                why = a.why;
                said = a.said;
            } else {
                why = elder == null ? "the most useful to hand" : "the next step";
                said = elder == null ? "it's the most useful to hand" : "it's the next step";
            }
            out.add(new Weighed(i, score, why, said));
        }
        out.sort((x, y) -> Double.compare(y.score(), x.score()) != 0 ? Double.compare(y.score(), x.score())
            : Integer.compare(x.invention().ordinal(), y.invention().ordinal()));
        return out;
    }

    /** The leader sets the scholars to the next: in the chronicle, aloud, and to the assembly. Null when nothing is open. */
    @Nullable
    static Invention choose(ServerLevel level, UUID village, long day) {
        clearWork(village);
        if (PlayerLeader.leaderId(village) != null && !town(village).getString("leaderPick").isEmpty()) {
            Invention picked = InventionTree.byId(town(village).getString("leaderPick"));
            town(village).remove("leaderPick");
            if (picked != null && open(village, picked)) {
                begin(level, village, picked, "the town's leader", "its own choice", day);
                return picked;
            }
        }
        List<Weighed> all = weigh(level, village);
        if (all.isEmpty()) return null;
        Weighed w = all.get(0);
        String who = CityTree.chooser(village);
        begin(level, village, w.invention(), who, w.why(), day);
        Market.assemblyNews(village, "Our scholars take up " + w.invention().title + " next — " + w.said() + ". When it's done: "
            + w.invention().unlocks + ".");
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(), "I've set our scholars to " + w.invention().title + ": " + w.said() + ".",
                w.invention().title + " is next for the academy — " + w.said() + "."));
        }
        return w.invention();
    }

    private static void begin(ServerLevel level, UUID village, Invention i, String who, String why, long day) {
        clearWork(village);
        CompoundTag t = town(village);
        t.putString("now", i.id());
        t.putLong("since", day);
        t.putString("by", who);
        t.putString("why", why);
        t.putInt("prog", 0);
        if (i.id().equals(t.getString("suggest"))) { t.remove("suggest"); t.remove("suggestBy"); }
        changed(null);
        Villages.tell(village, day, who + " set the scholars to " + i.title + ": " + why);
    }

    /**
     * A player leader's (or an operator's) own pick: begun at once if nothing is in hand, else next. What was said.
     * Its learning so far stays with the old one only while it is in hand: changing the scholars' work loses it.
     */
    public static String pick(ServerLevel level, UUID village, Invention i, String who) {
        if (known(village, i)) return i.title + " is known already.";
        if (!open(village, i)) return i.title + " is not open yet: it " + lockedWhy(village, i) + ".";
        long day = level.getDayTime() / 24000L;
        Invention now = current(village);
        if (now == i) return "The scholars are at " + i.title + " already.";
        if (now != null && progress(village) > 0) {
            Villages.tell(village, day, who + " set the scholars from " + now.title + " to " + i.title + "; their notes on " + now.title + " are put away");
        }
        begin(level, village, i, who, "by order", day);
        return "The scholars of " + Villages.name(village) + " take up " + i.title + ".";
    }

    // ------------------------------------------------------------------ the makings

    /** Is this stack one of this making ("#planks", "minecraft:string")? */
    static Predicate<ItemStack> matcher(InventionTree.Making m) {
        if (m.kind()) {
            return switch (m.item()) {
                case "#planks" -> s -> s.is(ItemTags.PLANKS);
                case "#logs" -> s -> s.is(ItemTags.LOGS);
                case "#wool" -> s -> s.is(ItemTags.WOOL);
                case "#coals" -> s -> s.is(ItemTags.COALS);
                default -> s -> false;
            };
        }
        ResourceLocation key = ResourceLocation.tryParse(m.item());
        Item it = key == null || !BuiltInRegistries.ITEM.containsKey(key) ? Items.AIR : BuiltInRegistries.ITEM.get(key);
        return s -> !s.isEmpty() && s.is(it);
    }

    /** Everything the invention's notes and model want: the paper, the ink, the books, then the model's makings. */
    static List<Object[]> wants(Invention i) {
        List<Object[]> out = new ArrayList<>();
        if (i.paper > 0) out.add(new Object[]{ (Predicate<ItemStack>) s -> s.is(Items.PAPER), i.paper, i.paper + " paper" });
        if (i.ink > 0) out.add(new Object[]{ (Predicate<ItemStack>) s -> s.is(Items.INK_SAC), i.ink, i.ink + " ink" });
        if (i.books > 0) out.add(new Object[]{ (Predicate<ItemStack>) s -> s.is(Items.BOOK), i.books, i.books + (i.books == 1 ? " book" : " books") });
        for (InventionTree.Making m : i.makings()) out.add(new Object[]{ matcher(m), m.count(), m.words() });
        return out;
    }

    /**
     * What the stores are short of for it, in words ("4 string"); empty when they have it all. Iron the town is still
     * putting by for its age is not the scholars' to take: the model waits for it (the age comes first).
     */
    static List<String> short_(ServerLevel level, Villages.Village v, Invention i) {
        List<String> out = new ArrayList<>();
        boolean saving = Crafts.savingIron(level, v) && !WarFooting.ready(v.id());
        for (InventionTree.Making m : i.makings()) {
            if (saving && m.item().equals("minecraft:iron_ingot")) out.add(m.words() + " (once the age's iron is put by)");
        }
        for (Object[] w : wants(i)) {
            @SuppressWarnings("unchecked") Predicate<ItemStack> p = (Predicate<ItemStack>) w[0];
            int have = Crafts.stock(level, v, p), need = (Integer) w[1];
            if (have < need) out.add((need - have) + " " + ((String) w[2]).substring(((String) w[2]).indexOf(' ') + 1));
        }
        return out;
    }

    /** The notes' paper and the model's makings out of the stores, all or nothing. Whether it is paid now. */
    static boolean pay(ServerLevel level, Villages.Village v, Invention i, long day) {
        CompoundTag t = town(v.id());
        List<String> lack = short_(level, v, i);
        t.putString("short", String.join(", ", lack));
        if (!lack.isEmpty()) {
            changed(null);
            return false;
        }
        for (Object[] w : wants(i)) {
            @SuppressWarnings("unchecked") Predicate<ItemStack> p = (Predicate<ItemStack>) w[0];
            if (!Crafts.take(level, v, p, (Integer) w[1])) {
                t.putString("short", "the stores ran short as the makings were taken");
                changed(null);
                return false;
            }
        }
        t.putBoolean("paid", true);
        t.remove("short");
        changed(null);
        VillageFolkEntity lead = Scholars.lead(level, v.id());
        Villages.tell(v.id(), day, (lead == null ? "the scholars" : lead.displayNameCap()) + " built a working model of " + i.title
            + " at the academy's bench, of " + modelWords(i));
        if (lead != null) FolkTalk.speak(lead, FolkTalk.pick(level.getRandom(), "The model's together. Now to see if it works.",
            "There — a working model of " + i.title + ". Small, but it'll tell us.", "Everything we need is on the bench."));
        return true;
    }

    static String modelWords(Invention i) {
        List<String> w = new ArrayList<>();
        for (InventionTree.Making m : i.makings()) w.add(m.words());
        return w.isEmpty() ? "paper and ink" : Civics.names(w);
    }

    // ------------------------------------------------------------------ luck, roads, spies, lords

    /** A eureka or a failed experiment this morning; what it did to the learning (hundredths), said and seen. */
    static int luck(ServerLevel level, Villages.Village v, Invention i, long day, CompoundTag t) {
        if (Scholars.appointed(v.id()).isEmpty()) return 0;
        int best = 0;
        for (UUID s : Scholars.appointed(v.id())) best = Math.max(best, Scholars.aptitude(level, s));
        int eureka = roll(level, 0), fail = roll(level, 1);
        VillageFolkEntity who = Scholars.lead(level, v.id());
        String name = who == null ? "a scholar" : who.displayNameCap();
        if (eureka < EUREKA_CHANCE + best) {
            t.putInt("eurekas", t.getInt("eurekas") + 1);
            Villages.tell(v.id(), day, name + " had a eureka over " + i.title + ": a quarter of the way at a stroke");
            Scholars.eureka(level, v, who);
            return i.cost * EUREKA;
        }
        if (i.age >= 2 && fail < FAILED_CHANCE - best / 2) {
            t.putInt("fails", t.getInt("fails") + 1);
            Villages.tell(v.id(), day, "an experiment on " + i.title + " went up in smoke at the academy; the scholars lost a tenth of their work");
            Scholars.bang(level, v, who);
            return -i.cost * FAILED;
        }
        return 0;
    }

    /** A roll of the thousand-sided die: the test's, or the world's. */
    static int roll(ServerLevel level, int which) {
        int[] r = rollForTests;
        if (r != null && which < r.length) return r[which];
        return level.getRandom().nextInt(1000);
    }

    /** Word along the trade roads: a day's learning from each partner that knows it (twice with the press). */
    static int road(UUID village, Invention i, long day) {
        int n = 0;
        List<String> from = new ArrayList<>();
        for (Villages.Village w : Villages.every()) {
            if (w.id().equals(village) || !TradeDeals.live(village, w.id()) || !known(w.id(), i)) continue;
            n += ROAD * (known(village, Invention.MOVABLE_TYPE) ? 2 : 1);
            from.add(Villages.name(w.id()));
        }
        CompoundTag t = town(village);
        if (n > 0 && !t.getBoolean("roadTold")) {
            t.putBoolean("roadTold", true);
            Villages.tell(village, day, "word of " + i.title + " came along the road from " + Civics.names(from) + ", and the scholars are the quicker for it");
        }
        return n;
    }

    /** A spy home from a rival that knows it, with their sketches: two fifths of it at once, a report once. */
    static int stolen(ServerLevel level, UUID village, Invention i, long day) {
        CompoundTag used = Civics.sub(town(village), "stolen");
        for (UUID rival : Spying.rivals(village)) {
            Intel.Report r = Intel.latest(village, rival);
            if (r == null || day - r.day() > 1 || !known(rival, i)) continue;
            String key = rival.toString();
            if (used.contains(key) && used.getLong(key) >= r.day()) continue;
            used.putLong(key, r.day());
            changed(null);
            Villages.tell(village, day, "our spy came home from " + Villages.name(rival) + " with sketches of their " + i.title
                + ": a stolen idea, and two fifths of the work done");
            Villages.tell(rival, day, "word is a spy of " + Villages.name(village) + "'s made off with sketches of our " + i.title);
            return i.cost * STOLEN;
        }
        return 0;
    }

    /** A vassal's scholars made to teach its overlord: one thing it knows and the lord does not, every few days. */
    static void teachOverlord(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!Vassals.isVassal(id) || day % TEACH_EVERY != 0) return;
        UUID lord = Vassals.overlord(id);
        if (lord == null || Villages.get(lord) == null) return;
        for (Invention i : knownDays(id).keySet()) {
            if (known(lord, i)) continue;
            Villages.tell(id, day, "our scholars were made to teach " + Villages.name(lord) + " the secret of " + i.title);
            complete(level, lord, i, day, "learnt from its vassal " + Villages.name(id));
            return;
        }
    }

    // ------------------------------------------------------------------ done

    /**
     * It is known: on the books with its day, said in the chronicle (and so by the crier and in the gazette), at the
     * assembly, by the leader, with a fanfare for everybody in the town; the first in the world told everywhere; its
     * working model into the stores; what changes at once changed. {@code how}: "worked out", "granted", "learnt from...".
     */
    static void complete(ServerLevel level, UUID village, Invention i, long day, String how) {
        if (known(village, i)) return;
        CompoundTag t = town(village);
        Civics.sub(t, "known").putLong(i.id(), day);
        boolean studied = i == current(village);
        long since = t.getLong("since");
        if (studied) clearWork(village);
        changed(village);
        String town = Villages.name(village);
        String took = studied && day > since ? " after " + (day - since) + (day - since == 1 ? " day's" : " days'") + " study" : "";
        // Granted by decree (an admin's, an age jumped, the pictures'): one quiet line, no fanfare, nobody's first.
        if (how.startsWith("were granted")) {
            Villages.tell(village, day, "the town came to know " + i.title + " by decree");
            InventionWorks.known(level, village, i, day);
            return;
        }
        Villages.tell(village, day, "the scholars " + how + " " + i.title + took + ": " + i.effect);
        Market.assemblyNews(village, "Our scholars have " + how + " " + i.title + "! " + Civics.cap(i.effect) + ".");
        boolean first = !firsts().contains(i.id());
        if (first) {
            firsts().putString(i.id(), town + "|" + day + "|" + village);
            changed(null);
            Villages.tell(village, day, Villages.name(village) + " is the first town in the world to know " + i.title);
        }
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(), i.title + "! Our scholars have done it.",
                "We know " + i.title + " now. " + Civics.cap(i.unlocks) + " — you'll see."));
            elder.persona().remember(day, "our scholars worked out " + i.title + " while I led the town", 4);
        }
        VillageFolkEntity lead = Scholars.lead(level, village);
        if (lead != null && studied) {
            lead.persona().remember(day, "I worked out " + i.title + " for the town", 7);
            FolkTalk.speak(lead, FolkTalk.pick(level.getRandom(), "It works! " + i.title + " works!", "Eureka — " + i.title + " is ours!"));
        }
        Villages.Village v = Villages.get(village);
        if (v != null) {
            fanfare(level, v, i, first);
            if (studied) workingModel(level, v, i, lead);
        }
        InventionWorks.known(level, village, i, day);
    }

    /** A fanfare for everybody in the town: the bell and a cheer, and the news over the hotbar; the first in the world for all. */
    static void fanfare(ServerLevel level, Villages.Village v, Invention i, boolean first) {
        BlockPos c = v.centre();
        level.playSound(null, c, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 1.0F);
        Component line = Component.literal("[Inventions] " + Villages.name(v.id()) + " has worked out " + i.title + ": " + i.effect + ".")
            .withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) {
            boolean near = p.blockPosition().distSqr(c) < 128 * 128;
            if (near) {
                p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.NEUTRAL, 0.8F, 1.0F);
                p.displayClientMessage(line, false);
            } else if (first) {
                p.displayClientMessage(Component.literal("[Inventions] " + Villages.name(v.id()) + " is the first town in the world to work out "
                    + i.title + ".").withStyle(ChatFormatting.YELLOW), false);
            }
        }
    }

    /** The working model, into the stores, labelled: the museum's to show (the first of them), the town's to keep. */
    static void workingModel(ServerLevel level, Villages.Village v, Invention i, @Nullable VillageFolkEntity by) {
        ItemStack model = com.jrpetty.mcassistant.item.InventionItems.model(i.id(), i.title, Villages.name(v.id()), by == null ? "" : by.displayNameCap(),
            level.getDayTime() / 24000L);
        if (model.isEmpty()) return;
        if (by != null) Museum.found(by, model, 1);
        Crafts.store(level, v, model);
    }

    /**
     * The first of one of the new arms made in the town (InventionArms): the chronicle has the maker and the thing, once;
     * the smith says so.
     */
    static void firstMade(ServerLevel level, Villages.Village v, ItemStack made, VillageFolkEntity maker) {
        String key = BuiltInRegistries.ITEM.getKey(made.getItem()).getPath();
        CompoundTag firstsHere = Civics.sub(town(v.id()), "made");
        if (firstsHere.contains(key)) return;
        long day = level.getDayTime() / 24000L;
        firstsHere.putLong(key, day);
        changed(null);
        String what = made.getItem().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
        Villages.tell(v.id(), day, maker.displayNameCap() + " made the town's first " + what);
        FolkTalk.speak(maker, FolkTalk.pick(level.getRandom(), "The town's first " + what + "! Feel the balance of it.",
            "There — a " + what + ", the first we've made. The scholars' drawings were right."));
    }

    /** By command (the admin's, the tests'): known now, whatever its needs, as granted. What was said. */
    public static String grant(ServerLevel level, UUID village, Invention i) {
        if (known(village, i)) return i.title + " is known already.";
        complete(level, village, i, level.getDayTime() / 24000L, "were granted");
        return Villages.name(village) + " knows " + i.title + " now.";
    }

    /**
     * A town taken by storm (Campaigns.taken): the victors carry off its scholars' papers, and with them one thing it
     * knew that they did not and are ready for (its age come, its needs known). Null if there was nothing to take.
     */
    @Nullable
    public static String spoils(ServerLevel level, UUID victor, UUID taken, long day) {
        for (Invention i : Invention.values()) {
            if (!known(taken, i) || !open(victor, i)) continue;
            complete(level, victor, i, day, "carried off from " + Villages.name(taken) + "'s scholars");
            return i.title;
        }
        return null;
    }

    /** By command: the one in hand finished now (its makings waived). What was said. */
    public static String finishNow(ServerLevel level, UUID village) {
        Invention i = current(village);
        if (i == null) {
            i = choose(level, village, level.getDayTime() / 24000L);
            if (i == null) return "Nothing left the scholars of " + Villages.name(village) + " can take up.";
        }
        complete(level, village, i, level.getDayTime() / 24000L, "worked out");
        choose(level, village, level.getDayTime() / 24000L);
        return Villages.name(village) + " worked out " + i.title + ".";
    }

    /** By command: everything of the town's age and before granted. How many. */
    public static int grantAll(ServerLevel level, UUID village, int age) {
        int n = 0;
        for (int round = 0; round < Invention.values().length; round++) {
            boolean any = false;
            for (Invention i : Invention.values()) {
                if (known(village, i) || i.age > age || !knownSet(village).containsAll(i.needs())) continue;
                complete(level, village, i, level.getDayTime() / 24000L, "were granted");
                n++;
                any = true;
            }
            if (!any) break;
        }
        return n;
    }

    /** By command: everything forgotten (the scholars kept). */
    public static void reset(UUID village) {
        CompoundTag t = town(village);
        ListTag scholars = t.getList("scholars", Tag.TAG_STRING).copy();
        Civics.sub(of().root, "towns").remove(village.toString());
        town(village).put("scholars", scholars);
        changed(village);
    }

    // ------------------------------------------------------------------ the players' part

    /**
     * A player's coin toward the work in hand: into the treasury, and five coins a point of learning, up to half the
     * invention's cost from players altogether. What was said.
     */
    public static String sponsor(ServerLevel level, Villages.Village v, ServerPlayer p, int coins) {
        Invention i = current(v.id());
        if (i == null) return "The scholars of " + Villages.name(v.id()) + " have nothing in hand to sponsor.";
        CompoundTag t = town(v.id());
        int room = i.cost * SPONSOR_MOST - t.getInt("sponsored");
        int most = Math.max(0, room / COIN);
        int n = Math.min(coins, most);
        if (n <= 0) return "The scholars have all the sponsorship " + i.title + " can use.";
        if (!Civics.takeCoins(p, n)) return "You have not got " + n + " coins on you.";
        Ledger.addCoins(v.id(), n);
        t.putInt("sponsored", t.getInt("sponsored") + n * COIN);
        t.putInt("prog", t.getInt("prog") + n * COIN);
        Civics.sub(t, "patrons").putInt(p.getGameProfile().getName(), Civics.sub(t, "patrons").getInt(p.getGameProfile().getName()) + n);
        changed(null);
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, p.getGameProfile().getName() + " sponsored the scholars' work on " + i.title + " with " + n + " coins");
        VillageFolkEntity lead = Scholars.lead(level, v.id());
        if (lead != null) FolkTalk.speak(lead, "Thank you, " + p.getGameProfile().getName() + "! That buys a good deal of candle.");
        return "You sponsored " + i.title + " with " + n + " coins: " + fmt(n * COIN) + " points of learning. " + percent(v.id()) + "% done.";
    }

    /** What a book, paper or the like is worth to the scholars (hundredths), or 0 for anything else. */
    static int bookWorth(ItemStack s) {
        if (s.is(Items.ENCHANTED_BOOK)) return ENCHANTED;
        if (s.is(Items.WRITTEN_BOOK)) return WRITTEN;
        if (s.is(Items.BOOK) || s.is(Items.WRITABLE_BOOK)) return BOOK;
        return 0;
    }

    /**
     * A player's books for the scholars (out of the hand: books, written books, paper, ink): into the stores, where the
     * work's notes come from, and a book's worth of learning toward the work in hand. What was said.
     */
    public static String donate(ServerLevel level, Villages.Village v, ServerPlayer p, ItemStack held) {
        if (held.isEmpty()) return "Hold the books, paper or ink you would give the scholars.";
        boolean paperish = held.is(Items.PAPER) || held.is(Items.INK_SAC) || held.is(Items.FEATHER);
        int each = bookWorth(held);
        if (each == 0 && !paperish) return "The scholars want books, paper and ink, not " + held.getHoverName().getString().toLowerCase() + ".";
        int n = held.getCount();
        ItemStack given = held.copy();
        held.shrink(n);
        Crafts.store(level, v, given);
        Invention i = current(v.id());
        CompoundTag t = town(v.id());
        int worth = 0;
        if (i != null && each > 0) {
            int room = Math.max(0, i.cost * SPONSOR_MOST - t.getInt("sponsored"));
            worth = Math.min(room, each * n);
            t.putInt("sponsored", t.getInt("sponsored") + worth);
            t.putInt("prog", t.getInt("prog") + worth);
        }
        Civics.sub(t, "patrons").putInt(p.getGameProfile().getName(), Civics.sub(t, "patrons").getInt(p.getGameProfile().getName()) + n);
        changed(null);
        Villages.tell(v.id(), level.getDayTime() / 24000L, p.getGameProfile().getName() + " gave the scholars " + n + " "
            + given.getHoverName().getString().toLowerCase());
        return "The scholars thank you for the " + given.getHoverName().getString().toLowerCase() + " (into the stores)"
            + (worth > 0 ? ": " + fmt(worth) + " points toward " + i.title + "." : ".");
    }

    /** A player's suggestion: weighed by the leader at its next choice. What was said. */
    public static String suggest(ServerLevel level, Villages.Village v, String player, Invention i) {
        if (known(v.id(), i)) return Villages.name(v.id()) + " knows " + i.title + " already.";
        if (!open(v.id(), i)) return i.title + " is not open to " + Villages.name(v.id()) + " yet: it " + lockedWhy(v.id(), i) + ".";
        CompoundTag t = town(v.id());
        t.putString("suggest", i.id());
        t.putString("suggestBy", player);
        changed(null);
        Villages.tell(v.id(), level.getDayTime() / 24000L, player + " suggested the scholars look into " + i.title);
        return "You put " + i.title + " to the scholars of " + Villages.name(v.id()) + ". The leader will weigh it at its next choice"
            + (current(v.id()) == null ? " (in the morning)." : ", once " + current(v.id()).title + " is done.");
    }

    // ------------------------------------------------------------------ where the player sees it

    static int percent(UUID village) {
        Invention i = current(village);
        return i == null ? 0 : Math.min(100, progress(village) / Math.max(1, i.cost));
    }

    /** The board's line, in "What we're working towards" (VillageBoards): what the scholars are at, how far, what is known. */
    public static List<String> board(@Nullable ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        String line = line(level, village);
        if (!line.isEmpty()) out.add((current(village) != null ? "FG|" : "FM|") + line);
        return out;
    }

    /** "Inventing: the Pike 40%, about 3 days (Reeve Bramble: the walls want better arms) · 3 of 22 known", or "". */
    static String line(@Nullable ServerLevel level, UUID village) {
        Invention i = current(village);
        int n = knownSet(village).size();
        if (i == null && n == 0 && Scholars.appointed(village).isEmpty()) return "";
        long day = level == null ? 0 : level.getDayTime() / 24000L;
        String known = n + " of " + Invention.values().length + " known";
        if (i == null) return "Inventing: nothing in hand · " + known + ".";
        int left = daysLeft(level, village, day);
        CompoundTag t = town(village);
        return "Inventing: " + i.title + " " + percent(village) + "%" + (left > 0 ? ", about " + left + (left == 1 ? " day" : " days") : "")
            + (t.getString("by").isEmpty() ? "" : " (" + t.getString("by") + ": " + t.getString("why") + ")") + " · " + known + ".";
    }

    /** The News page's lines (Civics.book's neighbour): the work in hand, the scholars, what it knows. */
    public static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        String line = line(level, village);
        if (!line.isEmpty()) out.add(line);
        List<String> names = new ArrayList<>();
        for (UUID s : Scholars.appointed(village)) names.add(Scholars.name(level, s));
        if (!names.isEmpty()) out.add("Scholars: " + Civics.names(names));
        return out;
    }

    /** The scholars' work as a folk mentions it after the elder's orders (CityTree.talk): " The scholars are at ...", or "". */
    public static String talkLine(VillageFolkEntity f) {
        String t = talk(f);
        return t.isEmpty() ? "" : " " + t;
    }

    /** What a folk says asked about the academy, or "". */
    public static String talk(VillageFolkEntity f) {
        UUID v = f.ownerId();
        if (v == null) return "";
        Invention i = current(v);
        if (i == null) return knownSet(v).isEmpty() ? "" : "Our scholars know " + knownSet(v).size() + " inventions now. Clever lot.";
        if (Scholars.is(f)) return FolkTalk.pick(f.getRandom(), "I'm at " + i.title + " of an evening. " + percent(v) + "% there, I'd say.",
            "If I can only get the " + i.title.toLowerCase().replace("the ", "") + " to work...", "The model's on my mind all day.");
        return "The scholars are at " + i.title + ". " + Civics.cap(i.unlocks) + ", if it works.";
    }

    /**
     * The Inventions page of the town's books (client/InventionsPage): every invention with its branch, state (known,
     * researching, open, locked and why), cost, days, makings and what it unlocks; the work in hand with its learning,
     * days left and why; the rate and its parts; the scholars; the firsts; the patrons; the eurekas and failures.
     */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        long day = level.getDayTime() / 24000L;
        CompoundTag t = town(village);
        Invention now = current(village);
        Map<Invention, Long> known = knownDays(village);
        ListTag tree = new ListTag();
        for (Invention i : Invention.values()) {
            CompoundTag n = new CompoundTag();
            n.putString("id", i.id());
            n.putString("title", i.title);
            n.putString("branch", i.branch.title);
            n.putInt("b", i.branch.ordinal());
            n.putInt("depth", i.depth());
            n.putString("age", i.ageWords());
            n.putInt("cost", i.cost);
            n.putInt("days", i.days);
            n.putString("unlocks", i.unlocks);
            n.putString("effect", i.effect);
            n.putString("model", modelWords(i));
            List<String> needs = new ArrayList<>();
            for (Invention x : i.needs()) needs.add(x.title);
            n.putString("needs", String.join(", ", needs));
            String state = known.containsKey(i) ? "known" : i == now ? "researching" : open(village, i) ? "open" : "locked";
            n.putString("state", state);
            if (known.containsKey(i)) n.putLong("knownDay", known.get(i));
            if (state.equals("locked")) n.putString("why", lockedWhy(village, i));
            String first = firsts().getString(i.id());
            if (!first.isEmpty()) n.putString("first", first.split("\\|")[0]);
            if (i.building != null) n.putString("building", i.building);
            tree.add(n);
        }
        out.put("tree", tree);
        Rate r = rate(level, village, day);
        out.putString("rate", r.perDay());
        out.putString("rateWhy", r.why());
        if (now != null) {
            out.putString("now", now.title);
            out.putString("nowId", now.id());
            out.putInt("prog", t.getInt("prog"));
            out.putInt("cost", now.cost * 100);
            out.putInt("daysLeft", daysLeft(level, village, day));
            out.putString("by", t.getString("by"));
            out.putString("why", t.getString("why"));
            out.putBoolean("paid", t.getBoolean("paid"));
            out.putString("short", t.getString("short"));
            out.putLong("since", t.getLong("since"));
        }
        ListTag scholars = new ListTag();
        for (UUID s : Scholars.appointed(village)) {
            CompoundTag c = new CompoundTag();
            c.putString("name", Scholars.name(level, s));
            c.putInt("aptitude", Scholars.aptitude(level, s));
            c.putInt("beats", Scholars.beats(s, day - 1));
            c.putString("why", Scholars.whyChosen(level, s));
            scholars.add(c);
        }
        out.put("scholars", scholars);
        out.putInt("eurekas", t.getInt("eurekas"));
        out.putInt("fails", t.getInt("fails"));
        out.putInt("notes", t.getInt("notes"));
        out.putBoolean("academy", InventionWorks.stands(village, InventionWorks.ACADEMY));
        List<String> patrons = new ArrayList<>();
        CompoundTag p = Civics.sub(t, "patrons");
        for (String k : p.getAllKeys()) patrons.add(k + " (" + p.getInt(k) + ")");
        out.putString("patrons", String.join(", ", patrons));
        String sug = t.getString("suggest");
        if (!sug.isEmpty()) out.putString("suggest", InventionTree.byId(sug) == null ? sug : InventionTree.byId(sug).title + " (" + t.getString("suggestBy") + ")");
        out.putInt("known", known.size());
        out.putInt("all", Invention.values().length);
        return out;
    }

    /** "/village inventions" and "/village admin": the tree in lines. */
    public static List<String> lines(@Nullable ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level == null ? 0 : level.getDayTime() / 24000L;
        out.add(Villages.name(village) + ": " + knownSet(village).size() + " of " + Invention.values().length + " inventions known; learning "
            + rate(level, village, day).perDay() + " a day (" + rate(level, village, day).why() + ")");
        Invention now = current(village);
        if (now != null) {
            CompoundTag t = town(village);
            out.add("Researching " + now.title + ": " + fmt(t.getInt("prog")) + " of " + now.cost + " (" + percent(village) + "%), about "
                + daysLeft(level, village, day) + " days to go; chosen by " + t.getString("by") + " (" + t.getString("why") + ")"
                + (t.getBoolean("paid") ? "; model built" : "; waiting on " + (t.getString("short").isEmpty() ? "the stores" : t.getString("short"))));
        }
        for (Branch b : Branch.values()) {
            List<String> parts = new ArrayList<>();
            for (Invention i : Invention.values()) {
                if (i.branch != b) continue;
                String mark = known(village, i) ? "[x]" : i == now ? "[>]" : open(village, i) ? "[ ]" : "[-]";
                parts.add(mark + " " + i.title);
            }
            out.add(b.title + ": " + String.join("  ", parts));
        }
        List<String> names = new ArrayList<>();
        for (UUID s : Scholars.appointed(village)) names.add(Scholars.name(level, s) + " (" + Scholars.aptitude(level, s) + ")");
        out.add("Scholars: " + (names.isEmpty() ? "none" : String.join(", ", names)));
        return out;
    }

    /** The world's first town to work out each invention: "The Windmill's Millstones: Ashford, day 12". */
    public static List<String> firstsLines() {
        List<String> out = new ArrayList<>();
        for (Invention i : Invention.values()) {
            String f = firsts().getString(i.id());
            if (f.isEmpty()) continue;
            String[] p = f.split("\\|");
            out.add(i.title + ": " + p[0] + (p.length > 1 ? ", day " + p[1] : ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the morning, now. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    /** Tests: the learning in hand set (hundredths). */
    public static void progressForTests(UUID village, int hundredths) {
        town(village).putInt("prog", hundredths);
        changed(null);
    }

    /** Tests: the makings counted as paid (or not). */
    public static void paidForTests(UUID village, boolean paid) {
        town(village).putBoolean("paid", paid);
        changed(null);
    }

    /** Tests: the dice's next rolls (a eureka's, a failure's; 999 for neither), or null for the world's. */
    public static void rollForTests(@Nullable int[] rolls) {
        rollForTests = rolls;
    }

    /** Tests: begin this one now. */
    public static void beginForTests(ServerLevel level, UUID village, Invention i, long day) {
        begin(level, village, i, "the tests", "by order", day);
    }

    /** Tests: the leader's choice now, whatever is in hand. */
    @Nullable
    public static Invention chooseForTests(ServerLevel level, UUID village, long day) {
        return choose(level, village, day);
    }

    /** Tests: the day the morning last ran forgotten, so it runs again. */
    public static void newMorningForTests(UUID village) {
        town(village).remove("day");
        changed(null);
    }

    /** Tests: the record saved as the world saves it and read back, as after a restart (the caches forgotten). */
    public static void reloadForTests(HolderLookup.Provider registries) {
        CompoundTag saved = of().save(new CompoundTag(), registries);
        Inventions back = load(saved, registries);
        of().root = back.root;
        KNOWN.clear();
    }

    /** Tests: the first town recorded for an invention, or "". */
    public static String firstForTests(Invention i) {
        String f = firsts().getString(i.id());
        return f.isEmpty() ? "" : f.split("\\|")[0];
    }

    static EnumMap<Branch, Integer> knownByBranch(UUID village) {
        EnumMap<Branch, Integer> m = new EnumMap<>(Branch.class);
        for (Invention i : knownSet(village)) m.merge(i.branch, 1, Integer::sum);
        return m;
    }

    static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s));
        return l;
    }
}
