package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * [identity] What makes a town itself: one record a town, kept with the world, never wiped when a world opens.
 *
 * <p>A town is shaped by where it is (Homeland), who founded and led it (their values: Values, Persona), what
 * happened to it (the chronicle's real events: floods, raids, fires, wars, weddings and deaths), what it makes most
 * (the town's books: Annals) and what its folk vote for (Elections, Referendums). Out of all that it has:
 * <ul>
 * <li><b>its ethos</b> (Ethos): seven axes, each from -100 to +100 — mercantile or self-sufficient, martial or
 *     peaceable, devout or worldly, learned or practical, open or closed, traditional or progressive, egalitarian or
 *     hierarchical. Seeded at its founding, moved by every leader while in office, by its events and its votes;</li>
 * <li><b>its government</b> (Government): an elected reeve, a council of elders, a hereditary lord and his line, a
 *     guild republic, a commune or the chaplain's rule, which decides who leads, who votes, and how fast;</li>
 * <li><b>its law-book</b> (LawBook): the tithe, tariffs, a curfew, who goes armed, its borders, conscription, the
 *     apprentice age, drink, the day of rest, hunting rights and who may own a house;</li>
 * <li><b>its earned traits</b> (TownTraits): Flood-hardy, Iron-willed, Golden Fields... history made into character;</li>
 * <li><b>its fame and its renown</b> (Fame): what it is known for, what its deeds are worth, and the title they raise
 *     it to.</li>
 * </ul>
 * Every one of them changes something real, modestly: shares of the trades, wages, the pace of work, research, the
 * buildings wanted, prices, the watch, the gates, how newcomers and players are taken. All of it is worked out on a
 * slow clock (the morning's business, Market.tick, and the chronicle's events as they happen), and read from memory
 * the rest of the time.
 *
 * <p>The player sees it on the Identity page of the town's books (client/IdentityPage), in one line on the board,
 * on a folk's card where it applies, when it asks "What's this town like?", in the chronicle and the gazette when
 * something changes, when it walks into the town (the town's character and its laws), and with /village identity.
 *
 * <p>The other two groups of this work (perks; culture and flavour) add their parts through {@link #contribute} (a
 * section of the page) and {@link #contributeTag} (a few words of the board's summary line).
 */
public final class Identity extends SavedData {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private static final String ID = "mc_assistant_identity";

    // ------------------------------------------------------------------ the events it remembers

    /** What happened to a town, as the chronicle tells it (heard) or as it happens (event): the stuff of its traits. */
    public enum Ev {
        FLOOD, RAID_HELD, RAID_LOST, FIRE, REBUILT, DEATH, WEDDING, BIRTH, HARVEST, DIAMOND, MINE_FIND, VISITOR, HERO,
        GUEST, WAR_WON, WAR_LOST, PEACE, DEAL, FESTIVAL, CATCH, BOOK, BIG_WORK, TAKEN_IN, TURNED_AWAY, DROUGHT, FAME_FAIR,
        LAW_BROKEN
    }

    // ------------------------------------------------------------------ the record

    /** One town's identity: everything the town is, and the history of how it came to be so. */
    public static final class Rec {
        public boolean seeded;
        public long seededOn = -1;
        /** The seven axes now, and the town's own baseline (its founding, and what its history has made lasting). */
        public final int[] axes = new int[Ethos.Axis.values().length];
        public final int[] seed = new int[Ethos.Axis.values().length];
        /** The part of a point each axis has moved and not yet counted (a slow drift). */
        final double[] frac = new double[Ethos.Axis.values().length];
        /** The poles it had at the last look (a bit each), so a change is told once. */
        int poles;
        /** Its founders, as the books remember them. */
        public String founders = "";

        public Government.Form gov = Government.Form.REEVE;
        public long govSince = -1;
        /** The house of a lordship, and its line ("day|name|how"). */
        public String house = "";
        public final List<String> line = new ArrayList<>();
        /** A player wed into the ruling house: who, and to whom (the folk). */
        @Nullable public UUID consort;
        public String consortName = "";
        @Nullable public UUID consortOf;
        /** Mornings running the town has been low under its lord (a vote of no confidence after three). */
        int lowDays;
        long lastReform = -100;

        public final int[] laws = LawBook.defaults();
        public final long[] lawSince = new long[LawBook.Law.values().length];
        long nextLawLook = -1;
        /** A change the leader turned down, and till when it will not be put again (law ordinal to day). */
        final Map<Integer, Long> lawRefused = new HashMap<>();

        /** Each kind of event: the days it happened (the last sixteen), and how many in all. */
        final Map<Ev, List<Long>> events = new LinkedHashMap<>();
        final Map<Ev, Integer> counts = new LinkedHashMap<>();

        /** The traits it has earned: since, and the story. */
        final Map<TownTraits.Trait, long[]> traits = new LinkedHashMap<>();
        final Map<TownTraits.Trait, String> stories = new LinkedHashMap<>();
        /** The day each faded trait faded: earned again only by a new cause after it. */
        final Map<TownTraits.Trait, Long> faded = new LinkedHashMap<>();

        /** What it is famous for (up to two), and since. */
        final Map<Fame.Product, Long> fame = new LinkedHashMap<>();
        int deeds;
        final List<String> deedLog = new ArrayList<>();
        long lastFair = -100;
        int chainGiven;
        /** The capital this town is a colony of, as its last morning found it (its laws are the capital's), or null. */
        @Nullable UUID seat;

        /** What changed, a line each, "day|text", newest last (the page's history and the gazette). */
        final List<String> changes = new ArrayList<>();
        /** The summary line as last composed. */
        String summary = "";
        long lastDay = -1;
        long lastLaw = -1;
        String character = "";

        public int count(Ev e) { return counts.getOrDefault(e, 0); }

        List<Long> days(Ev e) { return events.getOrDefault(e, List.of()); }

        boolean has(TownTraits.Trait t) { return traits.containsKey(t); }

        void change(long day, String text) {
            changes.add(day + "|" + text.replace('|', '/'));
            while (changes.size() > 40) changes.remove(0);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putBoolean("seeded", seeded);
            t.putLong("seededOn", seededOn);
            t.putIntArray("axes", axes);
            t.putIntArray("seed", seed);
            t.putInt("poles", poles);
            t.putString("founders", founders);
            t.putString("gov", gov.name());
            t.putLong("govSince", govSince);
            t.putString("house", house);
            t.put("line", strings(line));
            if (consort != null) {
                t.putUUID("consort", consort);
                t.putString("consortName", consortName);
                if (consortOf != null) t.putUUID("consortOf", consortOf);
            }
            t.putInt("lowDays", lowDays);
            t.putLong("lastReform", lastReform);
            t.putIntArray("laws", laws);
            t.put("lawSince", new LongArrayTag(lawSince));
            t.putLong("nextLawLook", nextLawLook);
            CompoundTag refused = new CompoundTag();
            for (Map.Entry<Integer, Long> e : lawRefused.entrySet()) refused.putLong(Integer.toString(e.getKey()), e.getValue());
            t.put("lawRefused", refused);
            CompoundTag ev = new CompoundTag();
            for (Map.Entry<Ev, List<Long>> e : events.entrySet()) {
                long[] d = new long[e.getValue().size()];
                for (int i = 0; i < d.length; i++) d[i] = e.getValue().get(i);
                ev.put(e.getKey().name(), new LongArrayTag(d));
            }
            t.put("events", ev);
            CompoundTag ct = new CompoundTag();
            for (Map.Entry<Ev, Integer> e : counts.entrySet()) ct.putInt(e.getKey().name(), e.getValue());
            t.put("counts", ct);
            CompoundTag tr = new CompoundTag();
            for (Map.Entry<TownTraits.Trait, long[]> e : traits.entrySet()) {
                CompoundTag one = new CompoundTag();
                one.put("at", new LongArrayTag(e.getValue()));
                one.putString("story", stories.getOrDefault(e.getKey(), ""));
                tr.put(e.getKey().name(), one);
            }
            t.put("traits", tr);
            CompoundTag fd = new CompoundTag();
            for (Map.Entry<TownTraits.Trait, Long> e : faded.entrySet()) fd.putLong(e.getKey().name(), e.getValue());
            t.put("faded", fd);
            CompoundTag fm = new CompoundTag();
            for (Map.Entry<Fame.Product, Long> e : fame.entrySet()) fm.putLong(e.getKey().name(), e.getValue());
            t.put("fame", fm);
            t.putInt("deeds", deeds);
            t.put("deedLog", strings(deedLog));
            t.putLong("lastFair", lastFair);
            t.putInt("chainGiven", chainGiven);
            t.put("changes", strings(changes));
            t.putString("summary", summary);
            t.putLong("lastDay", lastDay);
            t.putLong("lastLaw", lastLaw);
            t.putString("character", character);
            return t;
        }

        static Rec load(CompoundTag t) {
            Rec r = new Rec();
            r.seeded = t.getBoolean("seeded");
            r.seededOn = t.getLong("seededOn");
            copy(t.getIntArray("axes"), r.axes);
            copy(t.getIntArray("seed"), r.seed);
            r.poles = t.getInt("poles");
            r.founders = t.getString("founders");
            try { r.gov = Government.Form.valueOf(t.getString("gov")); } catch (IllegalArgumentException e) { r.gov = Government.Form.REEVE; }
            r.govSince = t.getLong("govSince");
            r.house = t.getString("house");
            r.line.addAll(read(t, "line"));
            if (t.hasUUID("consort")) {
                r.consort = t.getUUID("consort");
                r.consortName = t.getString("consortName");
                if (t.hasUUID("consortOf")) r.consortOf = t.getUUID("consortOf");
            }
            r.lowDays = t.getInt("lowDays");
            r.lastReform = t.contains("lastReform") ? t.getLong("lastReform") : -100;
            copy(t.getIntArray("laws"), r.laws);
            long[] since = t.getLongArray("lawSince");
            System.arraycopy(since, 0, r.lawSince, 0, Math.min(since.length, r.lawSince.length));
            r.nextLawLook = t.getLong("nextLawLook");
            CompoundTag refused = t.getCompound("lawRefused");
            for (String k : refused.getAllKeys()) {
                try { r.lawRefused.put(Integer.parseInt(k), refused.getLong(k)); } catch (NumberFormatException ignored) { }
            }
            CompoundTag ev = t.getCompound("events");
            for (String k : ev.getAllKeys()) {
                Ev e = ev(k);
                if (e == null) continue;
                List<Long> days = new ArrayList<>();
                for (long d : ev.getLongArray(k)) days.add(d);
                r.events.put(e, days);
            }
            CompoundTag ct = t.getCompound("counts");
            for (String k : ct.getAllKeys()) {
                Ev e = ev(k);
                if (e != null) r.counts.put(e, ct.getInt(k));
            }
            CompoundTag tr = t.getCompound("traits");
            for (String k : tr.getAllKeys()) {
                TownTraits.Trait x = TownTraits.Trait.named(k);
                if (x == null) continue;
                CompoundTag one = tr.getCompound(k);
                r.traits.put(x, one.getLongArray("at"));
                r.stories.put(x, one.getString("story"));
            }
            CompoundTag fd = t.getCompound("faded");
            for (String k : fd.getAllKeys()) {
                TownTraits.Trait x = TownTraits.Trait.named(k);
                if (x != null) r.faded.put(x, fd.getLong(k));
            }
            CompoundTag fm = t.getCompound("fame");
            for (String k : fm.getAllKeys()) {
                Fame.Product p = Fame.Product.named(k);
                if (p != null) r.fame.put(p, fm.getLong(k));
            }
            r.deeds = t.getInt("deeds");
            r.deedLog.addAll(read(t, "deedLog"));
            r.lastFair = t.contains("lastFair") ? t.getLong("lastFair") : -100;
            r.chainGiven = t.getInt("chainGiven");
            r.changes.addAll(read(t, "changes"));
            r.summary = t.getString("summary");
            r.lastDay = t.getLong("lastDay");
            r.lastLaw = t.getLong("lastLaw");
            r.character = t.getString("character");
            return r;
        }

        private static void copy(int[] from, int[] to) {
            System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
        }
    }

    @Nullable
    static Ev ev(String name) {
        try { return Ev.valueOf(name); } catch (IllegalArgumentException e) { return null; }
    }

    // ------------------------------------------------------------------ kept with the world

    private final Map<UUID, Rec> towns = new HashMap<>();

    public Identity() {}

    /** Without a server (a plain unit test) the records are kept here instead. */
    private static Identity loose;

    /** The store as last looked up, and for which server: it is asked for on every folk's tick, so it is kept to hand. */
    private static volatile Identity cached;
    private static volatile MinecraftServer cachedFor;

    @Nullable
    private static Identity store() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Identity();
            return loose;
        }
        Identity d = cached;
        if (d != null && cachedFor == server) return d;
        d = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Identity::new, Identity::load, null), ID);
        cached = d;
        cachedFor = server;
        return d;
    }

    public static Identity load(CompoundTag tag, HolderLookup.Provider registries) {
        Identity d = new Identity();
        CompoundTag all = tag.getCompound("towns");
        for (String k : all.getAllKeys()) {
            try {
                d.towns.put(UUID.fromString(k), Rec.load(all.getCompound(k)));
            } catch (RuntimeException e) {
                LOG.warn("[MCA-IDENTITY] a town's identity could not be read ({}): {}", k, e.toString());
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, Rec> e : towns.entrySet()) all.put(e.getKey().toString(), e.getValue().save());
        tag.put("towns", all);
        return tag;
    }

    /** A town's record, made (empty, not yet seeded) if it has none. */
    public static Rec rec(UUID village) {
        Identity d = store();
        if (d == null) return new Rec();
        return d.towns.computeIfAbsent(village, k -> new Rec());
    }

    /** A town's record if it has one and it has been seeded (its founding looked at), else null. */
    @Nullable
    public static Rec known(@Nullable UUID village) {
        if (village == null) return null;
        Identity d = store();
        if (d == null) return null;
        Rec r = d.towns.get(village);
        return r == null || !r.seeded ? null : r;
    }

    public static void dirty() {
        Identity d = store();
        if (d != null) d.setDirty();
    }

    // ------------------------------------------------------------------ the tests' plain towns

    /**
     * Every other test in the mod measures something finely (a share of the trades, a wage, a pace, a vote) in a town
     * of no particular character. Kit.reset (through Villages.resetForTests) puts every town back to that plain
     * town, every identity effect neutral, until a test of the town's identity asks for it to be live again
     * (liveForTests). In a world being played this is never set: every town is itself.
     */
    private static volatile boolean neutral;

    public static boolean neutral() {
        return neutral;
    }

    public static void resetForTests() {
        // A world opening or closing runs the tests' resets too (SessionReset): there the towns stay themselves.
        neutral = !com.jrpetty.mcassistant.SessionReset.opening();
        cached = null;
        cachedFor = null;
        GREETED.clear();
        Ethos.resetForTests();
        LawBook.resetForTests();
        Fame.resetForTests();
    }

    /** Tests: this test is about the town's identity: every effect live. */
    public static void liveForTests() {
        neutral = false;
    }

    // ------------------------------------------------------------------ the other groups' parts

    private static final Map<String, Function<UUID, List<String>>> CONTRIBUTED = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Function<UUID, String>> TAGS = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * A section of the Identity page from another part of the mod (a town's cuisine, its speech, its perks): its key
     * is its heading ("Cuisine"), and the function gives the lines for a town (none: the section is left out). Kept
     * in the order they were added; adding the same key again replaces it.
     */
    public static void contribute(String key, Function<UUID, List<String>> lines) {
        CONTRIBUTED.put(key, lines);
    }

    /** A few words for the board's summary line from another part ("famous for its pies" is Fame's; "a sea-faring
     *  people" might be a culture's): null or empty for nothing. */
    public static void contributeTag(String key, Function<UUID, String> words) {
        TAGS.put(key, words);
    }

    static List<String[]> contributed(UUID village) {
        List<String[]> out = new ArrayList<>();
        List<Map.Entry<String, Function<UUID, List<String>>>> all;
        synchronized (CONTRIBUTED) { all = new ArrayList<>(CONTRIBUTED.entrySet()); }
        for (Map.Entry<String, Function<UUID, List<String>>> e : all) {
            List<String> lines;
            try {
                lines = e.getValue().apply(village);
            } catch (RuntimeException ex) {
                LOG.warn("[MCA-IDENTITY] the {} section failed: {}", e.getKey(), ex.toString());
                continue;
            }
            if (lines == null || lines.isEmpty()) continue;
            List<String> one = new ArrayList<>();
            one.add(e.getKey());
            one.addAll(lines);
            out.add(one.toArray(new String[0]));
        }
        return out;
    }

    static List<String> tags(UUID village) {
        List<String> out = new ArrayList<>();
        List<Function<UUID, String>> all;
        synchronized (TAGS) { all = new ArrayList<>(TAGS.values()); }
        for (Function<UUID, String> f : all) {
            try {
                String s = f.apply(village);
                if (s != null && !s.isBlank()) out.add(s.trim());
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ what happens to it

    /**
     * A line of the town's chronicle as it is written (Villages.tell): read for what happened, counted toward its
     * traits, and the town's ethos nudged where the event says something of it. Cheap: a few look-ups a line.
     */
    public static void heard(UUID village, long day, String text) {
        if (text == null || text.isEmpty()) return;
        String low = text.toLowerCase(Locale.ROOT);
        if (low.startsWith("the flood of day") || low.startsWith("the great flood of day")) event(village, Ev.FLOOD, day);
        else if (low.contains("raiders came at the")) event(village, low.contains("of the village fell") ? Ev.RAID_LOST : Ev.RAID_HELD, day);
        else if (low.startsWith("the fire burnt")) event(village, Ev.FIRE, day);
        else if (low.contains("was rebuilt after the fire")) event(village, Ev.REBUILT, day);
        else if (low.contains(" died, aged ") || low.contains(" died peacefully")) event(village, Ev.DEATH, day);
        else if (low.endsWith(" were wed")) event(village, Ev.WEDDING, day);
        else if (low.contains(" had a child, ") || low.contains(" had twins") || low.contains(" had triplets") || low.contains(" had quadruplets")) {
            event(village, Ev.BIRTH, day);
        } else if (low.contains("brought in its record harvest")) event(village, Ev.HARVEST, day);
        else if (low.contains("the first the town has had") || low.contains("the town's first")) {
            if (low.contains("diamond")) event(village, Ev.DIAMOND, day);
            if (low.contains(" mined ") || low.contains(" brought up ") || low.contains(" dug out ")) event(village, Ev.MINE_FIND, day);
        } else if (low.contains(", a travelling bard, came to") || low.contains(", a visitor from afar, came to")
                || low.contains(", a merchant from afar, set up")) event(village, Ev.VISITOR, day);
        else if (low.contains(" was named the hero of ")) event(village, Ev.HERO, day);
        else if (low.contains("resolved to build") && low.contains(" a house") || low.startsWith("a statue of ")) event(village, Ev.GUEST, day);
        else if (low.contains("agreed a truce") || low.startsWith("our elder made peace between")) event(village, Ev.PEACE, day);
        else if (low.contains("festival was kept") || low.contains("round the maypole") || low.contains("midsummer bonfire was lit")
                || low.startsWith("founding day:")) event(village, Ev.FESTIVAL, day);
        else if (low.startsWith("the fishing fleet came in with ")) {
            int n = Fame.number(low.substring("the fishing fleet came in with ".length()));
            if (n >= 20) event(village, Ev.CATCH, day);
        } else if (low.contains("is on the library's shelves") || low.contains("book of best practice, for the library")
                || low.contains(" edition of ")) event(village, Ev.BOOK, day);
        else if (low.contains(" was opened, ") && low.contains("cutting the ribbon")) event(village, Ev.BIG_WORK, day);
        else if (low.startsWith("the town voted ") && low.contains(" taking in ")) {
            event(village, low.contains(" for taking in ") ? Ev.TAKEN_IN : Ev.TURNED_AWAY, day);
        } else if (low.startsWith("a drought:")) event(village, Ev.DROUGHT, day);
        else if (low.contains(" was elected ") && low.contains(" for ")) Ethos.elected(village, low, day);
    }

    /** Something happened to the town, as it happened (or as the chronicle told it). */
    public static void event(@Nullable UUID village, Ev e, long day) {
        if (village == null) return;
        Rec r = rec(village);
        List<Long> days = r.events.computeIfAbsent(e, k -> new ArrayList<>());
        days.add(day);
        while (days.size() > 16) days.remove(0);
        r.counts.merge(e, 1, Integer::sum);
        Ethos.onEvent(r, e, day);
        Fame.onEvent(r, e, day);
        dirty();
    }

    /** [war-peace] A war ended (WarAndPeace.makePeace): the winner's war won, and a peace made on both sides. */
    public static void peace(@Nullable UUID winner, UUID a, UUID b, long day) {
        for (UUID side : new UUID[]{ a, b }) {
            if (winner != null) event(side, side.equals(winner) ? Ev.WAR_WON : Ev.WAR_LOST, day);
            event(side, Ev.PEACE, day);
        }
    }

    // ------------------------------------------------------------------ the slow clock

    /**
     * The morning's look (Market.tick, once a day): seeded at its first morning from its land and its founders, then
     * its ethos drifts with its leader and its folk, its traits and its fame are looked at, its government sees to its
     * leader, and its law-book is reviewed. Cached for the rest of the day.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        if (neutral()) return;                                       // a test's plain town: no lord installed, no fair held
        Rec r = rec(v.id());
        if (r.lastDay == day) return;
        r.lastDay = day;
        try {
            if (!r.seeded) seed(level, v, r, day);
            Ethos.drift(level, v, r, day);
            TownTraits.review(level, v, r, day);
            Fame.review(level, v, r, day);
            Government.morning(level, v, r, day);
            LawBook.morning(level, v, r, day);
            Ethos.tell(level, v, r, day);
            r.summary = summary(v.id());
        } catch (RuntimeException e) {
            LOG.warn("[MCA-IDENTITY] {}: the morning's look failed: {}", Villages.name(v.id()), e.toString());
        }
        dirty();
    }

    /** The founding: the land and the founders give it its ethos; its ethos its government; both its laws. */
    static void seed(ServerLevel level, Villages.Village v, Rec r, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> founders = grown(id);
        int[] a = Ethos.seed(id, founders);
        System.arraycopy(a, 0, r.axes, 0, a.length);
        System.arraycopy(a, 0, r.seed, 0, a.length);
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity f : founders) if (names.size() < 8) names.add(f.displayNameCap() + " (" + Values.type(f) + ")");
        r.founders = String.join(", ", names);
        r.seeded = true;
        r.seededOn = day;
        r.poles = Ethos.poleBits(r.axes);
        r.character = Ethos.character(id, r.axes);
        Government.found(level, v, r, day);
        LawBook.found(v.id(), r, day);
        for (TownTraits.Trait t : TownTraits.founding(id)) TownTraits.award(level, v, r, t, day, TownTraits.foundingStory(id, t), false);
        r.change(day, "the town was founded as " + r.character + " under " + Government.phrase(id, r));
        LOG.info("[MCA-IDENTITY] {}: founded as {} under {}; axes {}", Villages.name(id), r.character, r.gov, java.util.Arrays.toString(r.axes));
    }

    /** The grown folk of the town, as loaded. */
    static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase() && f.isAlive()) out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------------ the one line

    /** "Thornhurst: a martial, closed hill-town under a hereditary lord; famous for its steel; Flood-hardy." */
    public static String summary(UUID village) {
        Rec r = known(village);
        String name = Villages.name(village);
        if (r == null) return name + ": a town still finding its feet.";
        StringBuilder sb = new StringBuilder(name).append(": ").append(Ethos.character(village, r.axes))
            .append(" under ").append(Government.phrase(village, r));
        String fame = Fame.words(village);
        if (!fame.isEmpty()) sb.append("; famous for ").append(fame);
        List<String> traits = TownTraits.names(r, 2);
        if (!traits.isEmpty()) sb.append("; ").append(String.join(", ", traits));
        for (String t : tags(village)) sb.append("; ").append(t);
        return sb.append('.').toString();
    }

    /** The board's lines (VillageBoards.compose): the summary, and its renown, rank and the laws a visitor must mind. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Rec r = known(village);
        if (r == null || neutral()) return out;
        out.add("RG|" + summary(village));
        String laws = LawBook.notice(village, 3);
        out.add("RM|Renown " + Villages.renown(village) + " (" + Villages.rank(village).label + ")" + (laws.isEmpty() ? "" : "; " + laws));
        return out;
    }

    // ------------------------------------------------------------------ the gazette

    /** The gazette's section on yesterday's changes to the town's ways, or null for none. */
    @Nullable
    public static String gazette(UUID village, long day) {
        Rec r = known(village);
        if (r == null || neutral()) return null;
        List<String> items = new ArrayList<>();
        for (String c : r.changes) {
            String[] p = c.split("\\|", 2);
            if (p.length == 2 && Fame.number(p[0]) == day - 1) items.add("• " + capital(p[1]) + ".");
        }
        if (items.isEmpty()) return null;
        return "§lOur ways§r\n" + String.join("\n", items);
    }

    // ------------------------------------------------------------------ a folk's card and its talk

    /** Its card's line on its town: a true son or daughter of it, a grumbler at its ways, of the ruling house. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Rec r = known(village);
        if (r == null || f.isBaby() || neutral()) return "";
        List<String> parts = new ArrayList<>();
        String role = Government.roleOf(village, r, f);
        if (role != null) parts.add(role);
        TownTraits.Trait trade = TownTraits.tradeTrait(r, f);
        int fit = Ethos.fit(f, r.axes);
        if (trade != null) parts.add("a true " + trade.person + " of " + Villages.name(village));
        else if (fit >= 25) parts.add("proud of " + Villages.name(village) + "'s " + Ethos.strongest(r.axes) + " ways");
        else if (fit <= -25) parts.add("chafes at " + Villages.name(village) + "'s " + Ethos.strongest(r.axes) + " ways");
        String law = LawBook.grumble(village, f);
        if (law != null) parts.add(law);
        return String.join("; ", parts);
    }

    /** True if the words ask what the town is like (FolkTalk). */
    static boolean asks(String low) {
        return low.contains("town like") || low.contains("village like") || low.contains("place like") || low.contains("this town")
            && (low.contains("tell me") || low.contains("about")) || low.contains("your laws") || low.contains("the law here")
            || low.contains("laws here") || low.contains("famous for") || low.contains("who rules") || low.contains("who governs")
            || low.contains("your ways") || low.contains("town's ways") || low.contains("what's the town") || low.contains("whats the town");
    }

    /** "What's this town like?" — said to a folk (FolkTalk.answer); null if that is not what was asked. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (text == null || text.isEmpty() || neutral()) return null;
        String low = text.toLowerCase(Locale.ROOT);
        String wed = Government.proposal(f, p, low);                // "Will you marry me?" to the ruling house
        if (wed != null) return wed;
        if (!asks(low)) return null;
        return describe(f);
    }

    /** What a folk says its town is like: its character, its rulers, its fame, its laws, its names, and what it thinks of it. */
    public static String describe(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Rec r = known(village);
        if (village == null) return "This town? I'm not of any town yet.";
        if (r == null) return Villages.name(village) + "? We're new here — ask me again in a day or two, when we know what we are.";
        StringBuilder sb = new StringBuilder();
        String name = Villages.name(village);
        sb.append(capital(name)).append("? ").append(capital(Ethos.character(village, r.axes))).append(", under ")
            .append(Government.phrase(village, r)).append(". ");
        String fame = Fame.words(village);
        if (!fame.isEmpty()) sb.append("Folk know us for ").append(fame).append(". ");
        String laws = LawBook.notice(village, 3);
        if (!laws.isEmpty()) sb.append("Mind the law: ").append(laws).append(". ");
        List<String> traits = TownTraits.names(r, 2);
        if (!traits.isEmpty()) sb.append("They call us ").append(String.join(" and ", traits)).append(" — ")
            .append(TownTraits.story(r, TownTraits.top(r))).append(". ");
        int fit = Ethos.fit(f, r.axes);
        sb.append(fit >= 25 ? "I wouldn't live anywhere else." : fit <= -25 ? "Between you and me, I'd have it " + Ethos.wish(f) + "."
            : "It suits me well enough.");
        String neighbour = Fame.neighbourWord(village);
        if (neighbour != null) sb.append(' ').append(neighbour);
        return sb.toString();
    }

    // ------------------------------------------------------------------ its spirits

    /**
     * What the town's ways do to its contentment (Contentment.compute), eight either way at most: a peaceable town at
     * peace, its laws (a curfew the worldly chafe at, a heavy tithe), its government (a lord over an egalitarian
     * people), its traits (in mourning, lucky, merry), and the week of a fame fair.
     */
    public static int contentment(UUID village, long day, List<String> good, List<String> bad) {
        if (neutral()) return 0;
        Rec r = known(village);
        if (r == null) return 0;
        int n = 0;
        if (Ethos.is(village, Ethos.Axis.WAR, false) && Wars.enemies(village).isEmpty()) {
            n += 3;
            good.add("at peace, as we like it");
        }
        n += LawBook.contentment(village, good, bad);
        n += Government.contentment(village, good, bad);
        n += TownTraits.contentment(village, good, bad);
        if (day - r.lastFair <= 2 && day >= r.lastFair) {
            n += 2;
            good.add("the fair");
        }
        return Math.max(-8, Math.min(8, n));
    }

    // ------------------------------------------------------------------ how it treats a player

    /** The day each player was last told a town's character and laws (player|town to day). */
    private static final Map<String, Long> GREETED = new ConcurrentHashMap<>();

    /**
     * A player walks into the town (FolkEvents, once on the way in): told what the town is and what its law asks of a
     * visitor, once a day; and greeted the town's way — warmly by an open, hospitable town, warily by a closed one,
     * and, where the watch alone goes armed, its weapons looked at by the watch at the gate.
     */
    public static void entered(ServerLevel level, Player p, UUID village) {
        Rec r = known(village);
        if (r == null || neutral()) return;
        long day = level.getDayTime() / 24000L;
        String key = p.getUUID() + "|" + village;
        if (GREETED.getOrDefault(key, -1L) == day) return;
        GREETED.put(key, day);
        if (GREETED.size() > 2048) GREETED.clear();
        String laws = LawBook.notice(village, 4);
        p.sendSystemMessage(Component.literal(summary(village)).withStyle(ChatFormatting.GOLD)
            .append(Component.literal(laws.isEmpty() ? "" : " " + capital(Villages.name(village)) + "'s law: " + laws + ".")
                .withStyle(ChatFormatting.GRAY)));
        Treatment.greet(level, p, village, r);
    }

    /** Every two seconds for a player in a town (FolkEvents): its laws kept by the watch. */
    public static void inTown(ServerLevel level, Player p, UUID village) {
        if (neutral() || known(village) == null) return;
        LawBook.watch(level, p, village);
    }

    // ------------------------------------------------------------------ the page

    /** The Identity page of the town's books (client/IdentityPage). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        Rec r = known(id);
        out.putString("name", Villages.name(id));
        if (r == null) {
            out.putString("summary", Villages.name(id) + " is still finding its feet: its character is settled on its first morning.");
            return out;
        }
        out.putString("summary", summary(id));
        out.putString("character", Ethos.character(id, r.axes));
        out.putString("founders", r.founders);
        out.putLong("seededOn", r.seededOn);
        out.put("axes", Ethos.report(id, r));
        out.put("gov", Government.report(level, id, r));
        out.put("laws", LawBook.report(id, r));
        out.put("traits", TownTraits.report(r));
        out.put("fame", Fame.report(level, id, r));
        out.put("treatment", strings(Treatment.lines(id, r)));
        List<String> history = new ArrayList<>();
        for (int i = r.changes.size() - 1; i >= 0 && history.size() < 14; i--) {
            String[] p = r.changes.get(i).split("\\|", 2);
            if (p.length == 2) history.add("Day " + (Fame.number(p[0]) + 1) + ": " + p[1]);
        }
        out.put("history", strings(history));
        ListTag more = new ListTag();
        for (String[] c : contributed(id)) {
            CompoundTag one = new CompoundTag();
            one.putString("title", c[0]);
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(c).subList(1, c.length));
            one.put("lines", strings(lines));
            more.add(one);
        }
        out.put("more", more);
        return out;
    }

    /** /village identity: everything, in words. */
    public static String status(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Rec r = known(id);
        if (r == null) return "IDENTITY " + Villages.name(id) + ": not yet seeded (its first morning settles it). Land: " + Homeland.line(id) + ".";
        StringBuilder sb = new StringBuilder("IDENTITY ").append(summary(id)).append('\n');
        sb.append("Axes: ").append(Ethos.line(r.axes)).append('\n');
        sb.append("Government: ").append(Government.line(level, id, r)).append('\n');
        sb.append("Laws: ").append(LawBook.line(id)).append('\n');
        sb.append("Traits: ").append(TownTraits.line(r)).append('\n');
        sb.append("Fame: ").append(Fame.line(level, id, r)).append('\n');
        sb.append("Renown ").append(Villages.renown(id)).append(" (").append(Fame.renownWords(id)).append("); ")
            .append(capital(Villages.rank(id).label)).append(". Next: ").append(Villages.nextRankNote(id)).append('\n');
        sb.append("Players: ").append(String.join(" ", Treatment.lines(id, r)));
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s.length() > 400 ? s.substring(0, 400) : s));
        return l;
    }

    static List<String> read(CompoundTag t, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = t.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The town's leader, if a folk of it leads, as loaded. */
    @Nullable
    static VillageFolkEntity leader(UUID village) {
        UUID id = Villages.elder(village);
        if (id == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(id)) return f;
        return null;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: seed the town now (its land and the folk it has), whatever it was. */
    public static Rec seedForTests(ServerLevel level, Villages.Village v) {
        Rec r = rec(v.id());
        r.seeded = false;
        r.lastDay = -1;
        seed(level, v, r, level.getDayTime() / 24000L);
        r.summary = summary(v.id());
        dirty();
        return r;
    }

    /** Tests: the morning's look, now, as on this day. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        rec(v.id()).lastDay = -1;
        morning(level, v, day);
    }

    /** Tests: the town's record (seeded or not). */
    public static Rec recForTests(UUID village) {
        return rec(village);
    }
}
