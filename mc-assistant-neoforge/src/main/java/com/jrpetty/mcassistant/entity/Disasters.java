package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [disasters] Fire, flood and drought, and what a town does about them.
 *
 * <p>A town of timber and thatch by a river, under the sky, has always had three enemies: the spark
 * from its own forge, the river in a wet spring, and the summer that will not rain. Each comes rarely,
 * makes a stir while it lasts, costs the town something, and is answered, so that the next one costs
 * less:
 * <ul>
 * <li><b>Fire</b> (FireBrigade, BucketChain, Rebuilding, FireSafety). A lit forge throws a spark into the
 *     timber or wool beside it now and then, the more in dry weather; lightning still strikes. The bell
 *     rings, the nearest hands run with buckets, and a big fire gets a bucket chain from the nearest water.
 *     What burned is put back from the stores to the building's own drawing, and the household it housed
 *     sleeps at a neighbour's or the inn till it is. After a fire the town takes care: stone round its
 *     forges, a cauldron of water by each workshop, a fire watch on dry nights, and in the Iron Age a fire
 *     station with the town's fire buckets in it.</li>
 * <li><b>Flood</b> (Floods). Rain on three days of seven in a wet season brings the river up over the low
 *     ground beside it: real water, set by the mod in empty cells only, every one written down and every one
 *     taken up again when the rain stops. Low houses are left for higher ground, the low fields' crops are
 *     spoiled and a flooded storehouse loses some of its grain. Afterwards the town raises a levee along the
 *     bank where the river came over, and builds no more on that low ground.</li>
 * <li><b>Drought</b> (Droughts). Six summer days without rain and the fields with no water near them wither
 *     to a quarter of their pace; the farmers carry water to them, the leader puts the town on short rations
 *     if its larder runs down, and the town digs irrigation channels through the dry fields, so the next
 *     drought passes them by.</li>
 * </ul>
 * None is ruinous: a fire takes a building or two at most, a flood drowns nobody, a drought starves no town
 * with stores. All of it can be turned off (villageDisasters), and the sparks and floods made rarer.
 *
 * <p>This class keeps the weather each town has had (dry days running, the wet days of the last week), what
 * has befallen it and what it built after, with the world (SavedData); and says it where the player sees it:
 * the board, the town's books, the crier and the gazette, a folk's card and its talk.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Disasters extends SavedData {

    private static final String ID = "mc_assistant_disasters";

    /** How many lines the town's record of its disasters keeps. */
    static final int LOG_KEPT = 16;
    /** The weather is looked at so often (ticks): forty looks a day. */
    static final long SAMPLE = 600L;
    /** A day with rain at this many of its looks (a fifth of them) was a wet one. */
    static final int WET_LOOKS = 8;

    // ------------------------------------------------------------------ a town's record

    /** A burnt building being put back: what stood where, out of what the fire found standing. */
    static final class Rebuild {
        final String structure;
        final BlockPos anchor;
        final String where;
        final long day;
        final String cause;
        /** The blocks to put back, by place, in the order they go back (the lowest first). */
        final LinkedHashMap<Long, BlockState> cells = new LinkedHashMap<>();
        int total;
        int put;
        /** What the work waits for, if it waits: "planks", "wool". */
        String waits = "";

        Rebuild(String structure, BlockPos anchor, String where, long day, String cause) {
            this.structure = structure;
            this.anchor = anchor.immutable();
            this.where = where;
            this.day = day;
            this.cause = cause;
        }
    }

    /** A flood standing on the town's low ground (Floods): the river's level, how far it rose, the water set. */
    static final class Flood {
        int w;
        int rise;
        long since;
        long dryAt = -1;
        String river = "the river";
        /** The cells to fill, in the order they fill (the river's edge first). */
        final List<Long> pending = new ArrayList<>();
        /** The cells the mod has filled, in order: exactly these are taken up again. */
        final LinkedHashSet<Long> cells = new LinkedHashSet<>();
        /** The town's houses with water on their floors (their anchors). */
        final Set<Long> homes = new LinkedHashSet<>();
        /** The crops under the flood, spoiled when it has risen. */
        final List<Long> crops = new ArrayList<>();
        int spoiled;
        int soaked;
        boolean risen;
        boolean draining;
        /** How many of the cells, from the last filled back, have been taken up again. */
        int drained;
        int most;
    }

    /** One town's weather and what has befallen it. */
    static final class Town {
        // The weather: the day being counted, its looks and its wet looks; dry days running; the last week.
        long sampleDay = -1;
        long lastSample = -100000L;
        int samples, wetSamples;
        int dryDays;
        /** The last seven days, a bit each, bit 0 yesterday: set if it was wet. */
        int wetWeek;
        // Drought.
        boolean drought;
        long droughtFrom = -1;
        int droughts;
        boolean rationing;
        /** The dry fields of the drought (centre, radius), to be irrigated. */
        final List<long[]> dryFields = new ArrayList<>();
        int irrigated;
        int fieldsIrrigated;
        long irrigatedOn = -1;
        boolean irrigationBarred;
        // Flood.
        int floods;
        int floodSeason = -1;
        @Nullable Flood flood;
        /** The levee to raise (cells, in order), and how much of it is up. */
        final List<Long> levee = new ArrayList<>();
        int leveeBuilt;
        long leveeOn = -1;
        String leveeWaits = "";
        /** The low ground the river came over: the height the water reached, and the columns (x>>2, z>>2). */
        int lowY = Integer.MIN_VALUE;
        final Set<Long> lowGround = new LinkedHashSet<>();
        int cellsFlooded;
        int spoiled;
        int soaked;
        // Fire.
        int burnt;
        int rebuilt;
        int sparks;
        int stamped;
        int keptFrom;
        long lastFireDay = -1;
        long watchUntil = -1;
        boolean stoneForges;
        int stoneLaid;
        boolean cauldrons;
        final List<Long> cauldronsAt = new ArrayList<>();
        boolean station;
        final List<Rebuild> rebuilds = new ArrayList<>();
        /** The town's record of its disasters and what it built after, a line each, oldest first. */
        final List<String> log = new ArrayList<>();

        /** Was it wet on so many of the last seven days (today, if it has been, counted as one)? */
        int wetDays(boolean today) {
            int n = Integer.bitCount(wetWeek & 0x3F);
            return n + (today ? 1 : 0);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("SampleDay", sampleDay);
            t.putInt("Samples", samples);
            t.putInt("WetSamples", wetSamples);
            t.putInt("DryDays", dryDays);
            t.putInt("WetWeek", wetWeek);
            t.putBoolean("Drought", drought);
            t.putLong("DroughtFrom", droughtFrom);
            t.putInt("Droughts", droughts);
            t.putBoolean("Rationing", rationing);
            ListTag fields = new ListTag();
            for (long[] f : dryFields) fields.add(new LongArrayTag(f));
            t.put("DryFields", fields);
            t.putInt("Irrigated", irrigated);
            t.putInt("FieldsIrrigated", fieldsIrrigated);
            t.putLong("IrrigatedOn", irrigatedOn);
            t.putInt("Floods", floods);
            t.putInt("FloodSeason", floodSeason);
            if (flood != null) {
                CompoundTag f = new CompoundTag();
                f.putInt("W", flood.w);
                f.putInt("Rise", flood.rise);
                f.putLong("Since", flood.since);
                f.putLong("DryAt", flood.dryAt);
                f.putString("River", flood.river);
                f.putLongArray("Pending", flood.pending.stream().mapToLong(Long::longValue).toArray());
                f.putLongArray("Cells", flood.cells.stream().mapToLong(Long::longValue).toArray());
                f.putLongArray("Homes", flood.homes.stream().mapToLong(Long::longValue).toArray());
                f.putLongArray("Crops", flood.crops.stream().mapToLong(Long::longValue).toArray());
                f.putInt("Spoiled", flood.spoiled);
                f.putInt("Soaked", flood.soaked);
                f.putBoolean("Risen", flood.risen);
                f.putBoolean("Draining", flood.draining);
                f.putInt("Most", flood.most);
                f.putInt("Drained", flood.drained);
                t.put("Flood", f);
            }
            t.putLongArray("Levee", levee.stream().mapToLong(Long::longValue).toArray());
            t.putInt("LeveeBuilt", leveeBuilt);
            t.putLong("LeveeOn", leveeOn);
            t.putInt("LowY", lowY);
            t.putLongArray("LowGround", lowGround.stream().mapToLong(Long::longValue).toArray());
            t.putInt("CellsFlooded", cellsFlooded);
            t.putInt("Spoiled", spoiled);
            t.putInt("Soaked", soaked);
            t.putInt("Burnt", burnt);
            t.putInt("Rebuilt", rebuilt);
            t.putInt("Sparks", sparks);
            t.putInt("Stamped", stamped);
            t.putInt("KeptFrom", keptFrom);
            t.putLong("LastFireDay", lastFireDay);
            t.putLong("WatchUntil", watchUntil);
            t.putBoolean("StoneForges", stoneForges);
            t.putInt("StoneLaid", stoneLaid);
            t.putBoolean("Cauldrons", cauldrons);
            t.putLongArray("CauldronsAt", cauldronsAt.stream().mapToLong(Long::longValue).toArray());
            t.putBoolean("Station", station);
            ListTag rb = new ListTag();
            for (Rebuild r : rebuilds) {
                CompoundTag one = new CompoundTag();
                one.putString("Structure", r.structure);
                one.putLong("Anchor", r.anchor.asLong());
                one.putString("Where", r.where);
                one.putLong("Day", r.day);
                one.putString("Cause", r.cause);
                one.putInt("Total", r.total);
                one.putInt("Put", r.put);
                ListTag cells = new ListTag();
                for (Map.Entry<Long, BlockState> e : r.cells.entrySet()) {
                    CompoundTag c = new CompoundTag();
                    c.putLong("P", e.getKey());
                    c.put("S", NbtUtils.writeBlockState(e.getValue()));
                    cells.add(c);
                }
                one.put("Cells", cells);
                rb.add(one);
            }
            t.put("Rebuilds", rb);
            ListTag log = new ListTag();
            for (String s : this.log) log.add(StringTag.valueOf(s));
            t.put("Log", log);
            return t;
        }

        static Town load(CompoundTag t, @Nullable HolderGetter<Block> blocks) {
            Town n = new Town();
            n.sampleDay = t.getLong("SampleDay");
            n.samples = t.getInt("Samples");
            n.wetSamples = t.getInt("WetSamples");
            n.dryDays = t.getInt("DryDays");
            n.wetWeek = t.getInt("WetWeek");
            n.drought = t.getBoolean("Drought");
            n.droughtFrom = t.getLong("DroughtFrom");
            n.droughts = t.getInt("Droughts");
            n.rationing = t.getBoolean("Rationing");
            for (Tag f : t.getList("DryFields", Tag.TAG_LONG_ARRAY)) {
                long[] a = ((LongArrayTag) f).getAsLongArray();
                if (a.length == 2) n.dryFields.add(a);
            }
            n.irrigated = t.getInt("Irrigated");
            n.fieldsIrrigated = t.getInt("FieldsIrrigated");
            n.irrigatedOn = t.getLong("IrrigatedOn");
            n.floods = t.getInt("Floods");
            n.floodSeason = t.getInt("FloodSeason");
            if (t.contains("Flood")) {
                CompoundTag f = t.getCompound("Flood");
                Flood fl = new Flood();
                fl.w = f.getInt("W");
                fl.rise = f.getInt("Rise");
                fl.since = f.getLong("Since");
                fl.dryAt = f.getLong("DryAt");
                fl.river = f.getString("River");
                for (long l : f.getLongArray("Pending")) fl.pending.add(l);
                for (long l : f.getLongArray("Cells")) fl.cells.add(l);
                for (long l : f.getLongArray("Homes")) fl.homes.add(l);
                for (long l : f.getLongArray("Crops")) fl.crops.add(l);
                fl.spoiled = f.getInt("Spoiled");
                fl.soaked = f.getInt("Soaked");
                fl.risen = f.getBoolean("Risen");
                fl.draining = f.getBoolean("Draining");
                fl.most = f.getInt("Most");
                fl.drained = f.getInt("Drained");
                n.flood = fl;
            }
            for (long l : t.getLongArray("Levee")) n.levee.add(l);
            n.leveeBuilt = t.getInt("LeveeBuilt");
            n.leveeOn = t.getLong("LeveeOn");
            n.lowY = t.contains("LowY") ? t.getInt("LowY") : Integer.MIN_VALUE;
            for (long l : t.getLongArray("LowGround")) n.lowGround.add(l);
            n.cellsFlooded = t.getInt("CellsFlooded");
            n.spoiled = t.getInt("Spoiled");
            n.soaked = t.getInt("Soaked");
            n.burnt = t.getInt("Burnt");
            n.rebuilt = t.getInt("Rebuilt");
            n.sparks = t.getInt("Sparks");
            n.stamped = t.getInt("Stamped");
            n.keptFrom = t.getInt("KeptFrom");
            n.lastFireDay = t.getLong("LastFireDay");
            n.watchUntil = t.getLong("WatchUntil");
            n.stoneForges = t.getBoolean("StoneForges");
            n.stoneLaid = t.getInt("StoneLaid");
            n.cauldrons = t.getBoolean("Cauldrons");
            for (long l : t.getLongArray("CauldronsAt")) n.cauldronsAt.add(l);
            n.station = t.getBoolean("Station");
            for (Tag x : t.getList("Rebuilds", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) x;
                Rebuild r = new Rebuild(one.getString("Structure"), BlockPos.of(one.getLong("Anchor")), one.getString("Where"),
                    one.getLong("Day"), one.getString("Cause"));
                r.total = one.getInt("Total");
                r.put = one.getInt("Put");
                if (blocks != null) {
                    for (Tag c : one.getList("Cells", Tag.TAG_COMPOUND)) {
                        CompoundTag cc = (CompoundTag) c;
                        r.cells.put(cc.getLong("P"), NbtUtils.readBlockState(blocks, cc.getCompound("S")));
                    }
                }
                if (!r.cells.isEmpty()) n.rebuilds.add(r);
            }
            for (Tag s : t.getList("Log", Tag.TAG_STRING)) n.log.add(s.getAsString());
            return n;
        }
    }

    private final Map<UUID, Town> towns = new HashMap<>();

    public Disasters() {}

    /** Kept with the world: in the overworld's data, as the calendar is. */
    @Nullable
    static Disasters of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        // Looked up on every folk's tick: kept to hand while the world's data is the same (a new world, a new look).
        var storage = server.overworld().getDataStorage();
        Disasters have = cached;
        if (have != null && cachedFrom == storage) return have;
        have = storage.computeIfAbsent(new SavedData.Factory<>(Disasters::new, Disasters::load, null), ID);
        cached = have;
        cachedFrom = storage;
        return have;
    }

    private static volatile Disasters cached;
    private static volatile Object cachedFrom;

    /** A town's record (a throwaway one with no world to keep it in). */
    static Town town(UUID village) {
        Disasters d = of();
        if (d == null) return new Town();
        return d.towns.computeIfAbsent(village, k -> new Town());
    }

    /** A town's record if it has one yet, else null (for reading only). */
    @Nullable
    static Town known(UUID village) {
        Disasters d = of();
        return d == null ? null : d.towns.get(village);
    }

    static void dirty() {
        Disasters d = of();
        if (d != null) d.setDirty();
    }

    public static Disasters load(CompoundTag tag, HolderLookup.Provider registries) {
        Disasters d = new Disasters();
        HolderGetter<Block> blocks = registries == null ? null : registries.lookupOrThrow(Registries.BLOCK);
        for (Tag t : tag.getList("Towns", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (one.hasUUID("Id")) d.towns.put(one.getUUID("Id"), Town.load(one.getCompound("Town"), blocks));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Town> e : towns.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.put("Town", e.getValue().save());
            list.add(one);
        }
        tag.put("Towns", list);
        return tag;
    }

    /** Written into the town's record of its disasters, and its chronicle. */
    static void record(UUID village, long day, String line) {
        Town t = town(village);
        t.log.add("Day " + (day + 1) + ": " + line.replace(';', ','));
        while (t.log.size() > LOG_KEPT) t.log.remove(0);
        dirty();
        Villages.tell(village, day, line);
    }

    // ------------------------------------------------------------------ the switch

    /** Fire from the forges, floods and droughts: on unless the player has turned them off. */
    public static boolean on() {
        Boolean t = onForTests;
        if (t != null) return t;
        if (GAME_TESTS) return false;               // a game test's town meets one only when its test brings it on
        return AssistantConfig.villageDisasters();
    }

    /**
     * Running the game tests (the test server names the namespaces whose tests it runs): a spark or a drought
     * of chance in the middle of another test's town would be that test's undoing, so none comes by chance.
     */
    static final boolean GAME_TESTS = System.getProperty("neoforge.enabledGameTestNamespaces") != null;

    private static volatile Boolean onForTests;

    /** Tests: disasters on (true), off (false), or as the config has them (null). */
    public static void onForTests(@Nullable Boolean on) {
        onForTests = on;
    }

    // ------------------------------------------------------------------ the weather

    /** Tests: rain on the towns (true), none (false), or the world's own (null). */
    private static volatile Boolean rainForTests;

    public static void rainForTests(@Nullable Boolean on) {
        rainForTests = on;
    }

    /** Is it raining on this town (not snowing, and not a desert under a rainy sky)? */
    static boolean raining(ServerLevel level, Villages.Village v) {
        Boolean t = rainForTests;
        if (t != null) return t;
        if (!level.isRaining()) return false;
        BlockPos c = v.centre();
        return level.getBiome(c).value().getPrecipitationAt(c) == Biome.Precipitation.RAIN;
    }

    /** The town's look at the sky, every half a minute: the day's wet looks counted, and the day closed when it turns. */
    static void weather(ServerLevel level, Villages.Village v, Town t) {
        long now = level.getGameTime();
        if (now - t.lastSample < SAMPLE && now >= t.lastSample) return;
        t.lastSample = now;
        long day = level.getDayTime() / 24000L;
        if (t.sampleDay != day) {
            if (t.sampleDay >= 0 && t.samples > 0) closeTheDay(level, v, t, day);
            t.sampleDay = day;
            t.samples = 0;
            t.wetSamples = 0;
        }
        t.samples++;
        if (raining(level, v)) {
            t.wetSamples++;
            if (t.dryDays > 0) t.dryDays = 0;                   // the dry spell broken
        }
        dirty();
    }

    /** Yesterday into the week: wet or dry, the dry days counted on or begun again. */
    private static void closeTheDay(ServerLevel level, Villages.Village v, Town t, long day) {
        boolean wet = t.wetSamples >= Math.min(WET_LOOKS, Math.max(1, t.samples / 5));
        long gone = Math.max(1, Math.min(7, day - t.sampleDay));
        for (int i = 0; i < gone; i++) t.wetWeek = (t.wetWeek << 1) & 0x7F;
        if (wet) t.wetWeek |= 1 << (gone - 1);
        if (wet) t.dryDays = 0;
        else t.dryDays += (int) gone;
        Droughts.daily(level, v, t, day);
    }

    /** Dry for so many days running (the town's own count). */
    public static int dryDays(UUID village) {
        Town t = known(village);
        return t == null ? 0 : t.dryDays;
    }

    /** Is it dry enough that the town keeps a fire watch at night: three dry days, a drought, or the nights after a fire? */
    static boolean dryWeather(UUID village, long day) {
        Town t = known(village);
        return t != null && (t.dryDays >= 3 || t.drought || day <= t.watchUntil);
    }

    // ------------------------------------------------------------------ the server's tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        com.jrpetty.mcassistant.Guard.run("fire, flood and drought", () -> tick(event.getServer()));
    }

    static void tick(MinecraftServer server) {
        int tc = server.getTickCount();
        for (Villages.Village v : Villages.every()) {
            ServerLevel level = server.getLevel(v.dim());
            if (level == null) continue;
            Town t = known(v.id());
            // Every tick a flood stands: its water held where the mod set it (Floods.hold).
            if (t != null && t.flood != null) Floods.sweep(level, v, t);
            if (tc % 5 == 2 && t != null && t.flood != null) Floods.step(level, v, t);
            if (tc % 20 != 11) continue;
            if (t == null) t = town(v.id());
            weather(level, v, t);
            Floods.tick(level, v, t);
            Droughts.tick(level, v, t);
            if (tc % 40 == 11) Rebuilding.tick(level, v, t);
            FireSafety.tick(level, v, t);
        }
    }

    // ------------------------------------------------------------------ the folk's part

    /**
     * From the folk's tick (VillageFolkEntity.aiStep, after the fire brigade): out of a flood, to a bed at a
     * neighbour's while its home is burnt or under water, the fire watch's round, a farmer's water carried to
     * its field in a drought. True while it is about one of them (its own day waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.ownerId() == null || f.isShowcase()) return false;
        if (Floods.evacuate(f, level)) return true;
        if (Rebuilding.lodging(f, level)) return true;
        if (f.tickCount % 2 == 0 && FireSafety.round(f, level)) return true;
        return f.tickCount % 2 == 1 && Droughts.carry(f, level);
    }

    /** Is the folk about one of them just now (VillageFolkEntity.calledAway)? */
    public static boolean busy(VillageFolkEntity f) {
        return Floods.evacuating(f) || Rebuilding.lodged(f) || FireSafety.onRound(f) || Droughts.carrying(f)
            || BucketChain.inChain(f);
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The weather's danger, in a few words: "dry for 9 days: a fire watch tonight". Null in ordinary weather. */
    @Nullable
    static String danger(UUID village, long day) {
        Town t = known(village);
        if (t == null) return null;
        if (t.flood != null) return t.flood.river + " is in flood" + (t.flood.homes.isEmpty() ? "" : ": the low houses are under water");
        if (t.drought) return "a drought: dry for " + t.dryDays + (t.dryDays == 1 ? " day" : " days")
            + ", the dry fields withering" + (t.rationing ? ", the town on short rations" : "") + "; a fire watch tonight";
        if (t.dryDays >= 3) return "dry for " + t.dryDays + " days: a fire watch tonight";
        if (day <= t.watchUntil) return "a fire watch tonight, after the fire";
        if (t.wetDays(t.wetSamples > 0) >= Math.max(2, AssistantConfig.villageFloodRainDays() - 1) && t.lowY != Integer.MIN_VALUE) {
            return "rain on " + t.wetDays(t.wetSamples > 0) + " of the last seven days: the river is high";
        }
        return null;
    }

    /** The board's lines (the right-hand column): a fire burning, the flood, the drought, a rebuilding. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        String fire = FireBrigade.burningNow(village);
        if (fire != null) out.add("RB|FIRE " + fire.toUpperCase(java.util.Locale.ROOT) + "! The bell is ringing.");
        long day = level.getDayTime() / 24000L;
        String danger = danger(village, day);
        if (danger != null) out.add("RW|" + capital(danger) + ".");
        Town t = known(village);
        if (t == null) return out;
        for (Rebuild r : t.rebuilds) {
            out.add("RN|Rebuilding " + r.where + " after the fire: " + r.put + " of " + r.total + " blocks back"
                + (r.waits.isEmpty() ? "." : ", waiting for " + r.waits + "."));
            break;
        }
        if (!t.levee.isEmpty()) out.add("RN|Raising a levee along the bank: " + t.leveeBuilt + " blocks up"
            + (t.leveeWaits.isEmpty() ? "." : ", waiting for " + t.leveeWaits + "."));
        if (!t.dryFields.isEmpty() && !t.irrigationBarred) out.add("RN|Digging irrigation through " + t.dryFields.size()
            + (t.dryFields.size() == 1 ? " dry field." : " dry fields."));
        return out;
    }

    /** The town's books (the News page's panel): the weather, the fires, floods and droughts, and what it built after. */
    public static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Town t = known(village);
        long day = level.getDayTime() / 24000L;
        if (!on()) out.add("Disasters are off in this world (villageDisasters): no sparks, floods or droughts.");
        if (t == null) {
            out.add("Nothing has befallen the town yet.");
            return out;
        }
        String danger = danger(village, day);
        out.add("The weather: " + (t.dryDays > 0 ? "dry for " + t.dryDays + (t.dryDays == 1 ? " day" : " days") : "rain lately")
            + "; wet on " + t.wetDays(t.wetSamples > 0) + " of the last seven days" + (danger == null ? "." : " — " + danger + "."));
        int fires = Annals.fires(village);
        out.add("Fires: " + fires + (fires == 1 ? " in all" : " in all") + "; " + t.sparks + (t.sparks == 1 ? " spark" : " sparks")
            + " from the forges" + (t.stamped > 0 ? " (" + t.stamped + " stamped out by the fire watch)" : "") + "; "
            + t.burnt + " blocks burnt, " + t.rebuilt + " put back from the stores"
            + (t.keptFrom > 0 ? "; " + t.keptFrom + " flames beaten off a third building" : "") + ".");
        for (Rebuild r : t.rebuilds) {
            out.add("  Rebuilding " + r.where + " (burnt day " + (r.day + 1) + "): " + r.put + " of " + r.total + " blocks back"
                + (r.waits.isEmpty() ? "" : ", waiting for " + r.waits) + ".");
        }
        List<String> care = new ArrayList<>();
        if (t.stoneForges) care.add("stone round the forges (" + t.stoneLaid + " blocks)");
        if (t.cauldrons) care.add(t.cauldronsAt.size() + (t.cauldronsAt.size() == 1 ? " cauldron" : " cauldrons") + " of water by the workshops");
        if (t.station) care.add(Villages.builtStructure(village, FireSafety.STATION) != null ? "the fire station" : "a fire station wanted");
        String watcher = FireSafety.watcherName(village);
        if (watcher != null) care.add("the fire watch tonight: " + watcher);
        out.add("Fire safety: " + (care.isEmpty() ? "none yet (it takes a fire to teach a town)" : String.join("; ", care)) + ".");
        out.add("Floods: " + t.floods + "; " + t.cellsFlooded + " cells under water in all, " + t.spoiled + " crops spoiled, "
            + t.soaked + " of the stores' goods soaked" + (t.leveeBuilt > 0 ? "; a levee of " + t.leveeBuilt + " blocks along the bank" : "")
            + (t.lowY != Integer.MIN_VALUE ? "; nothing built on the low ground up to y " + t.lowY : "") + ".");
        if (t.flood != null) {
            out.add("  In flood now: " + t.flood.river + " up " + t.flood.rise + (t.flood.rise == 1 ? " block" : " blocks") + ", "
                + t.flood.cells.size() + " cells under water, " + t.flood.homes.size() + (t.flood.homes.size() == 1 ? " house" : " houses") + " flooded.");
        }
        out.add("Droughts: " + t.droughts + (t.drought ? " (one now, since day " + (t.droughtFrom + 1) + ")" : "") + "; "
            + t.fieldsIrrigated + (t.fieldsIrrigated == 1 ? " field" : " fields") + " irrigated after, " + t.irrigated + " blocks of channel dug"
            + (t.irrigationBarred ? " (the land is not to be reshaped in this world: no channels cut)" : "") + ".");
        if (!t.log.isEmpty()) {
            out.add("The record, latest first:");
            for (int i = t.log.size() - 1; i >= 0 && i >= t.log.size() - 8; i--) out.add("  " + t.log.get(i));
        }
        return out;
    }

    /** The crier's word on the weather's danger (Crier.script), or null. */
    @Nullable
    public static String cry(UUID village, long day) {
        String danger = danger(village, day);
        if (danger == null) return null;
        Town t = known(village);
        if (t != null && t.flood != null) return capital(danger) + "! Keep to the high ground till the water goes down.";
        if (t != null && t.drought) return capital(danger) + ". Mind your fires, and spare the water!";
        return capital(danger) + ". Mind your fires!";
    }

    /** The gazette's page: the weather's danger and yesterday's fires, floods and droughts. Null for nothing to say. */
    @Nullable
    public static String gazette(ServerLevel level, Villages.Village v, long day) {
        Town t = known(v.id());
        List<String> lines = new ArrayList<>();
        String danger = danger(v.id(), day);
        if (danger != null) lines.add(capital(danger) + ".");
        if (t != null) {
            String yesterday = "Day " + day + ": ";
            for (String s : t.log) if (s.startsWith(yesterday)) lines.add(capital(s.substring(yesterday.length())) + ".");
        }
        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("§lFire, flood and drought§r");
        for (int i = 0; i < Math.min(5, lines.size()); i++) sb.append('\n').append(lines.get(i));
        return sb.toString();
    }

    /** A line for a folk's card: at a bucket chain, out of the flood, lodging while its home is rebuilt, on the fire watch. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        List<String> parts = new ArrayList<>();
        String chain = BucketChain.cardPart(f);
        if (chain != null) parts.add(chain);
        String flood = Floods.cardPart(f);
        if (flood != null) parts.add(flood);
        String lodge = Rebuilding.cardPart(f);
        if (lodge != null) parts.add(lodge);
        String watch = FireSafety.cardPart(f);
        if (watch != null) parts.add(watch);
        String water = Droughts.cardPart(f);
        if (water != null) parts.add(water);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /** Talk of fire, flood and drought (Smalltalk.topic), each as {open, answer, last word}; empty as often as not. */
    public static List<String[]> talk(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level, RandomSource r) {
        List<String[]> out = new ArrayList<>();
        UUID village = a.ownerId();
        Town t = village == null ? null : known(village);
        if (t == null) return out;
        long day = level.getDayTime() / 24000L;
        if (t.flood != null) {
            out.add(new String[]{ FolkTalk.pick(r, "Have you seen the river? It's right up over the low street.", "The water's in the low houses again."),
                FolkTalk.pick(r, "Keep the children away from it.", "I had to wade to work this morning!", "It'll go down when the rain stops."),
                FolkTalk.pick(r, "Let's hope so.", "Mm.", "") });
        } else if (t.drought) {
            out.add(new String[]{ FolkTalk.pick(r, "Dry as a bone, these fields.", "Not a drop of rain in " + t.dryDays + " days."),
                FolkTalk.pick(r, "The wheat's barely coming on.", "The farmers are carrying water by the bucket.", "One spark and the whole street goes up."),
                FolkTalk.pick(r, "Mind your fire tonight.", "We could do with a storm.", "") });
        } else if (t.lastFireDay >= 0 && day - t.lastFireDay <= 2) {
            String where = t.rebuilds.isEmpty() ? "the fire" : "the fire " + t.rebuilds.get(0).where;
            out.add(new String[]{ "Were you there for " + where + "?",
                FolkTalk.pick(r, "I was in the bucket chain! My arms still ache.", "I heard the bell and came running.", "Saw the smoke from my field."),
                FolkTalk.pick(r, "Could have been a lot worse.", "Thank goodness for the bell.", "") });
        } else if (t.leveeBuilt > 0 && t.leveeOn >= 0 && day - t.leveeOn <= 3) {
            out.add(new String[]{ "That new levee looks solid.", FolkTalk.pick(r, "Let the river try it now!", "Took some earth, mind."), "" });
        } else if (t.irrigatedOn >= 0 && day - t.irrigatedOn <= 3) {
            out.add(new String[]{ "The new channels are working a treat.", FolkTalk.pick(r, "Green again, the whole field.", "No more carrying buckets."), "" });
        }
        if (!out.isEmpty() && r.nextInt(3) == 0) out.clear();             // now and then, not every time
        return out;
    }

    static String capital(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Everything held in memory forgotten (the tests share one JVM). The record kept with the world stays. */
    public static void resetForTests() {
        onForTests = null;
        rainForTests = null;
        Floods.resetForTests();
        Droughts.resetForTests();
        Rebuilding.resetForTests();
        FireSafety.resetForTests();
        BucketChain.resetForTests();
    }

    /** Tests: the town's weather set: dry for so many days, wet on so many of the last seven. */
    public static void weatherForTests(UUID village, int dryDays, int wetDays) {
        Town t = town(village);
        t.dryDays = dryDays;
        t.wetWeek = 0;
        for (int i = 0; i < Math.min(7, wetDays); i++) t.wetWeek |= 1 << i;
        dirty();
    }

    /** Tests: the town's record of its disasters, a line each. */
    public static List<String> logForTests(UUID village) {
        Town t = known(village);
        return t == null ? List.of() : List.copyOf(t.log);
    }

    /** Tests: {burnt, rebuilt, sparks, floods, cells flooded, levee blocks, droughts, irrigated blocks, fields irrigated}. */
    public static int[] countsForTests(UUID village) {
        Town t = known(village);
        if (t == null) return new int[9];
        return new int[]{ t.burnt, t.rebuilt, t.sparks, t.floods, t.cellsFlooded, t.leveeBuilt, t.droughts, t.irrigated, t.fieldsIrrigated };
    }
}
