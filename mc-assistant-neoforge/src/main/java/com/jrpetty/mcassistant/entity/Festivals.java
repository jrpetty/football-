package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [batchB] The year's festivals, each on its own day of the town's year (Seasons):
 * <ul>
 * <li><b>The May dance</b>, on the first rest day of spring (the day of the week the town keeps for its
 *     rest, whether it keeps one yet or not): in the morning a hand puts a maypole up on the square out of
 *     the stores — five fence posts (on a foot of logs if posts are short, or logs alone) and wool of as many
 *     colours as the stores have, up to five, at its head — and at dusk the town gathers in a ring round it,
 *     the elder says a word, and they dance: round and round the pole, a place at a time, to a tune. The
 *     next morning the pole comes down and its posts and wool go back into the stores.</li>
 * <li><b>The midsummer bonfire</b>, on midsummer's day (the fourth of summer): at dusk a hand builds a
 *     campfire on the square out of the stores (three logs, a lump of coal or charcoal and three sticks, as a
 *     player makes one; five of them, a fire as big as a bonfire, if the stores run to it), the town gathers
 *     round it and sings, and at midnight, the singers long in bed, the watch puts it out: the spot is clear
 *     again, and what a campfire leaves (two charcoal) goes into the stores.</li>
 * <li><b>The town fair</b>, late in summer (its sixth day): see Fair.</li>
 * <li><b>The harvest festival</b>, on the last day of autumn: in the afternoon a hand lays two long tables
 *     on the square (the stores' wooden slabs, or planks sawn into them, three to six), and at dusk the town
 *     feasts at them out of its stores, the elder reads out the year's harvest (the meals the fields, the
 *     water, the hunt and the pens brought in since Founding Day) and gives the farmer who brought in the
 *     most a prize out of the treasury. It all goes into the chronicle. The tables are cleared the next
 *     morning, into the stores.</li>
 * <li><b>Midwinter</b>, on midwinter's day (the fourth of winter): see Midwinter.</li>
 * </ul>
 * A festival is a gathering like any other (Assemblies, kind FESTIVAL): the bell, the crowd, the words, and
 * the dance, the song or the meal. It has the evening before the weekly feast, but gives way to a wedding,
 * a vigil or a celebration, and to the rain, for a day: the next evening it is kept come what may. What a
 * festival puts up is written down with the world (here), so a restart never leaves a maypole on the square
 * for good or a lantern out of the stores; so are the year's harvest, farmer by farmer, the fair's entries,
 * and the turn an operator gave the town's calendar.
 *
 * <p>A festival kept is a happier town for a few days (Contentment), and each one who was there remembers it.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Festivals extends SavedData {

    public enum Feast {
        MAYPOLE("the May dance", "maypole"), BONFIRE("the midsummer bonfire", "bonfire"), FAIR("the town fair", "fair"),
        HARVEST("the harvest festival", "harvest"), MIDWINTER("midwinter", "midwinter");

        public final String words;
        public final String key;

        Feast(String words, String key) {
            this.words = words;
            this.key = key;
        }

        @Nullable
        public static Feast byKey(String key) {
            for (Feast f : values()) if (f.key.equalsIgnoreCase(key)) return f;
            return null;
        }
    }

    /** The festivals held as a gathering of the whole town, in the order an evening looks at them. */
    private static final Feast[] GATHERED = { Feast.MAYPOLE, Feast.BONFIRE, Feast.FAIR, Feast.HARVEST };

    /** The days of the town's year the festivals fall on (the May dance's is the rest day's, worked out). */
    public static final int MIDSUMMER = 10, FAIR_DAY = 12, HARVEST_DAY = 20, MIDWINTER_DAY = 24;
    /** A maypole's posts. */
    public static final int POLE = 5;
    /** The harvest's prize to its best farmer, out of the treasury. */
    static final int HARVEST_PRIZE = 10;
    /** A festival kept is the happier town for so many days after (Contentment), and by so much. */
    static final int GLOW_DAYS = 3, GLOW = 3;

    private static final String ID = "mc_assistant_festivals";

    // ------------------------------------------------------------------ kept with the world

    /** A thing a festival put up: where, for which and which day, what block, and what goes back to the stores when it comes down. */
    record Placed(BlockPos pos, String feast, long day, Block block, ItemStack back) {}

    /** A fair entry (or a prize) waiting for a player who was away when it was judged. */
    record Owed(UUID player, String name, ItemStack item) {}

    /** One town's year of festivals. */
    static final class Town {
        /** The days an operator turned the town's calendar by (/village season set). */
        int turned;
        /** Each festival: the day it was due that it was kept for, and the day it was kept on. */
        final Map<String, Long> kept = new HashMap<>(), keptOn = new HashMap<>();
        final List<Placed> placed = new ArrayList<>();
        /** The season last told into the chronicle (year * 4 + season), or -1. */
        int toldSeason = -1;
        /** The year the harvest is counted for; the meals each farmer brought in, and by where they came from. */
        int harvestYear = -1;
        final Map<UUID, Double> farmers = new HashMap<>();
        final Map<UUID, String> farmerNames = new HashMap<>();
        final double[] sources = new double[5];
        /** The year whose prize is paid, and whose harvest is told; and what was said of the last one. */
        int prizeYear = -1, harvestTold = -1;
        String lastHarvest = "", prizeWords = "";
        /** The fair: its entries, the day they are for, the day it was judged, its classes awarded, its results. */
        final List<Fair.Entry> entries = new ArrayList<>();
        long fairDay = -1, fairJudged = -1;
        final Set<String> awarded = new HashSet<>();
        final List<String> fairResults = new ArrayList<>();
        final List<Owed> owed = new ArrayList<>();
        /** Midwinter: the day the presents were planned for, how many were given, and the day it went into the chronicle. */
        long giftsDay = -1, midwinterTold = -1;
        int gifts;
        /** The winter the snowmen are counted for (year * 4 + 3), and how many. */
        int snowmenWinter = -1, snowmen;

        CompoundTag save(HolderLookup.Provider reg) {
            CompoundTag c = new CompoundTag();
            c.putInt("Turned", turned);
            CompoundTag k = new CompoundTag();
            for (Map.Entry<String, Long> e : kept.entrySet()) k.putLong(e.getKey(), e.getValue());
            c.put("Kept", k);
            CompoundTag ko = new CompoundTag();
            for (Map.Entry<String, Long> e : keptOn.entrySet()) ko.putLong(e.getKey(), e.getValue());
            c.put("KeptOn", ko);
            ListTag pl = new ListTag();
            for (Placed p : placed) {
                CompoundTag one = new CompoundTag();
                one.putLong("Pos", p.pos().asLong());
                one.putString("Feast", p.feast());
                one.putLong("Day", p.day());
                one.putString("Block", BuiltInRegistries.BLOCK.getKey(p.block()).toString());
                if (!p.back().isEmpty()) one.put("Back", p.back().save(reg));
                pl.add(one);
            }
            c.put("Placed", pl);
            c.putInt("Told", toldSeason);
            c.putInt("HarvestYear", harvestYear);
            ListTag fm = new ListTag();
            for (Map.Entry<UUID, Double> e : farmers.entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Id", e.getKey());
                one.putDouble("Meals", e.getValue());
                one.putString("Name", farmerNames.getOrDefault(e.getKey(), "?"));
                fm.add(one);
            }
            c.put("Farmers", fm);
            for (int i = 0; i < sources.length; i++) c.putDouble("Source" + i, sources[i]);
            c.putInt("PrizeYear", prizeYear);
            c.putInt("HarvestTold", harvestTold);
            c.putString("LastHarvest", lastHarvest);
            c.putString("PrizeWords", prizeWords);
            ListTag en = new ListTag();
            for (Fair.Entry e : entries) en.add(e.save(reg));
            c.put("Entries", en);
            c.putLong("FairDay", fairDay);
            c.putLong("FairJudged", fairJudged);
            ListTag aw = new ListTag();
            for (String s : awarded) aw.add(StringTag.valueOf(s));
            c.put("Awarded", aw);
            ListTag fr = new ListTag();
            for (String s : fairResults) fr.add(StringTag.valueOf(s));
            c.put("FairResults", fr);
            ListTag ow = new ListTag();
            for (Owed o : owed) {
                if (o.item().isEmpty()) continue;
                CompoundTag one = new CompoundTag();
                one.putUUID("Player", o.player());
                one.putString("Name", o.name());
                one.put("Item", o.item().save(reg));
                ow.add(one);
            }
            c.put("Owed", ow);
            c.putLong("GiftsDay", giftsDay);
            c.putLong("MidwinterTold", midwinterTold);
            c.putInt("Gifts", gifts);
            c.putInt("SnowmenWinter", snowmenWinter);
            c.putInt("Snowmen", snowmen);
            return c;
        }

        static Town load(CompoundTag c, HolderLookup.Provider reg) {
            Town t = new Town();
            t.turned = c.getInt("Turned");
            CompoundTag k = c.getCompound("Kept");
            for (String key : k.getAllKeys()) t.kept.put(key, k.getLong(key));
            CompoundTag ko = c.getCompound("KeptOn");
            for (String key : ko.getAllKeys()) t.keptOn.put(key, ko.getLong(key));
            for (Tag x : c.getList("Placed", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) x;
                ResourceLocation id = ResourceLocation.tryParse(one.getString("Block"));
                Block b = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
                ItemStack back = one.contains("Back") ? ItemStack.parseOptional(reg, one.getCompound("Back")) : ItemStack.EMPTY;
                t.placed.add(new Placed(BlockPos.of(one.getLong("Pos")), one.getString("Feast"), one.getLong("Day"), b, back));
            }
            t.toldSeason = c.contains("Told") ? c.getInt("Told") : -1;
            t.harvestYear = c.contains("HarvestYear") ? c.getInt("HarvestYear") : -1;
            for (Tag x : c.getList("Farmers", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) x;
                if (!one.hasUUID("Id")) continue;
                t.farmers.put(one.getUUID("Id"), one.getDouble("Meals"));
                t.farmerNames.put(one.getUUID("Id"), one.getString("Name"));
            }
            for (int i = 0; i < t.sources.length; i++) t.sources[i] = c.getDouble("Source" + i);
            t.prizeYear = c.contains("PrizeYear") ? c.getInt("PrizeYear") : -1;
            t.harvestTold = c.contains("HarvestTold") ? c.getInt("HarvestTold") : -1;
            t.lastHarvest = c.getString("LastHarvest");
            t.prizeWords = c.getString("PrizeWords");
            for (Tag x : c.getList("Entries", Tag.TAG_COMPOUND)) {
                Fair.Entry e = Fair.Entry.load((CompoundTag) x, reg);
                if (e != null) t.entries.add(e);
            }
            t.fairDay = c.contains("FairDay") ? c.getLong("FairDay") : -1;
            t.fairJudged = c.contains("FairJudged") ? c.getLong("FairJudged") : -1;
            for (Tag x : c.getList("Awarded", Tag.TAG_STRING)) t.awarded.add(x.getAsString());
            for (Tag x : c.getList("FairResults", Tag.TAG_STRING)) t.fairResults.add(x.getAsString());
            for (Tag x : c.getList("Owed", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) x;
                if (!one.hasUUID("Player")) continue;
                ItemStack it = ItemStack.parseOptional(reg, one.getCompound("Item"));
                if (!it.isEmpty()) t.owed.add(new Owed(one.getUUID("Player"), one.getString("Name"), it));
            }
            t.giftsDay = c.contains("GiftsDay") ? c.getLong("GiftsDay") : -1;
            t.midwinterTold = c.contains("MidwinterTold") ? c.getLong("MidwinterTold") : -1;
            t.gifts = c.getInt("Gifts");
            t.snowmenWinter = c.contains("SnowmenWinter") ? c.getInt("SnowmenWinter") : -1;
            t.snowmen = c.getInt("Snowmen");
            return t;
        }
    }

    private final Map<UUID, Town> towns = new HashMap<>();

    public Festivals() {}

    /** No server to keep it with (a unit test): kept in memory. */
    private static final Map<UUID, Town> LOOSE = new ConcurrentHashMap<>();

    @Nullable
    static Festivals of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(Festivals::new, Festivals::load, null), ID);
    }

    static Town town(UUID village) {
        Festivals f = of();
        if (f == null) return LOOSE.computeIfAbsent(village, k -> new Town());
        return f.towns.computeIfAbsent(village, k -> new Town());
    }

    static void dirty() {
        Festivals f = of();
        if (f != null) f.setDirty();
    }

    public static Festivals load(CompoundTag tag, HolderLookup.Provider reg) {
        Festivals f = new Festivals();
        for (Tag t : tag.getList("Towns", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (c.hasUUID("Id")) f.towns.put(c.getUUID("Id"), Town.load(c, reg));
        }
        return f;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag l = new ListTag();
        for (Map.Entry<UUID, Town> e : towns.entrySet()) {
            CompoundTag c = e.getValue().save(reg);
            c.putUUID("Id", e.getKey());
            l.add(c);
        }
        tag.put("Towns", l);
        return tag;
    }

    // ------------------------------------------------------------------ only in memory

    /** Why a festival's preparations wait, for the board: village/feast to the reason. */
    private static final Map<String, String> WHY = new ConcurrentHashMap<>();
    /** The festival called now (tests, /village festival ... now), for Assemblies.startNow. */
    private static final Map<UUID, Feast> CALLED = new ConcurrentHashMap<>();
    /** The last turn of the dance round the maypole, a village at a time (game time). */
    private static final Map<UUID, Long> TURNED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WHY.clear();
        CALLED.clear();
        TURNED.clear();
        LOOSE.clear();
        Midwinter.resetForTests();
        Winter.resetForTests();
    }

    // ------------------------------------------------------------------ the calendar

    /** The days an operator turned this town's calendar by (Seasons counts with them). */
    public static int turned(UUID village) {
        Festivals f = of();
        Town t = f == null ? LOOSE.get(village) : f.towns.get(village);
        return t == null ? 0 : t.turned;
    }

    /** The town's calendar turned so that {@code day} is this day of its year (nought to twenty-seven), in the same year. */
    public static void turnTo(UUID village, long day, int dayOfYear) {
        int now = Seasons.dayOfYear(village, day);
        Town t = town(village);
        t.turned += Math.floorMod(dayOfYear, TownCalendar.YEAR_DAYS) - now;
        dirty();
    }

    /** The day of its year a festival falls on, in the year that holds {@code day}. */
    static int dayOfYear(UUID village, long day, Feast f) {
        return switch (f) {
            case MAYPOLE -> {
                // The first rest day of spring: the day of the week the town keeps for its rest (RestDay).
                long first = Seasons.dayOf(village, day, 0);
                int d = 0;
                for (int i = 0; i < Seasons.DAYS; i++) {
                    if (Math.floorMod(first + i + village.hashCode() + 3, 7) == 0) { d = i; break; }
                }
                yield d;
            }
            case BONFIRE -> MIDSUMMER;
            case FAIR -> FAIR_DAY;
            case HARVEST -> HARVEST_DAY;
            case MIDWINTER -> MIDWINTER_DAY;
        };
    }

    /** The world's day the festival falls on in the year that holds {@code day}. */
    public static long dayThisYear(UUID village, long day, Feast f) {
        return Seasons.dayOf(village, day, dayOfYear(village, day, f));
    }

    /** The next day it falls on from {@code day} (today, if it is today and not yet kept). */
    public static long next(UUID village, long day, Feast f) {
        long d = dayThisYear(village, day, f);
        if (d > day || d == day && keptFor(village, f) != d) return d;
        long later = day + TownCalendar.YEAR_DAYS;
        return dayThisYear(village, later, f);
    }

    /** The festival's day this was kept for (or a long time ago). */
    public static long keptFor(UUID village, Feast f) {
        return town(village).kept.getOrDefault(f.key, Long.MIN_VALUE);
    }

    /** The day it was last kept on (or a long time ago). */
    public static long keptOn(UUID village, Feast f) {
        return town(village).keptOn.getOrDefault(f.key, Long.MIN_VALUE);
    }

    static void kept(UUID village, Feast f, long due, long on) {
        Town t = town(village);
        t.kept.put(f.key, due);
        t.keptOn.put(f.key, on);
        dirty();
    }

    /** Is it still to be kept: today its day, or the day after and not yet kept? */
    static boolean due(UUID village, long day, Feast f) {
        long d = dayThisYear(village, day, f);
        return (day == d || day == d + 1) && keptFor(village, f) != d;
    }

    /** Which festival's day is today, or null. */
    @Nullable
    static Feast today(UUID village, long day) {
        for (Feast f : Feast.values()) if (dayThisYear(village, day, f) == day) return f;
        return null;
    }

    // ------------------------------------------------------------------ the town's part, every second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 9) return;
        com.jrpetty.mcassistant.Guard.run("the year's festivals", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** A second of the town's year: the season told, the day's preparations, what is over taken down. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) <= 0) return;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        Town town = town(id);
        if (t >= 200L) Seasons.turn(level, v, day);
        takeDown(level, v, town, day, t);
        dance(level, v);
        if (Raids.underAlarm(id)) return;
        if (due(id, day, Feast.MAYPOLE) && t >= 1000L && t < 11500L) why(id, Feast.MAYPOLE, raiseMaypole(level, v, dayThisYear(id, day, Feast.MAYPOLE), false));
        if (due(id, day, Feast.BONFIRE) && t >= 11000L && t < 12400L) {
            // Not laid in the rain on its day (the gathering waits for the next evening, and so does the fire).
            boolean wet = level.isRaining() && day == dayThisYear(id, day, Feast.BONFIRE);
            why(id, Feast.BONFIRE, wet ? "put off a day by the rain" : layBonfire(level, v, false));
        }
        if (due(id, day, Feast.HARVEST) && t >= 8000L && t < 12400L) why(id, Feast.HARVEST, layTables(level, v, dayThisYear(id, day, Feast.HARVEST), false));
        Midwinter.tick(level, v, town, day, t);
        Lanterns.tick(level, v, day, t);                             // [leisure] paper lanterns strung across the square on a festival night
        Winter.tick(level, v, town, day, t);
        Fair.tick(level, v, town, day, t);
        harvestMissed(level, v, town, day, t);
    }

    /**
     * A folk's own part, from its tick (VillageFolkEntity.aiStep): a midwinter present bought and taken round
     * (Midwinter), a snowman built by the playground (Winter). True while it is about it.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.isShowcase() || f.ownerId() == null || f.isHired()) return false;
        return Midwinter.hold(f, level) || Winter.hold(f, level);
    }

    static void why(UUID village, Feast f, @Nullable String why) {
        if (why == null) WHY.remove(village + "/" + f.key);
        else WHY.put(village + "/" + f.key, why);
    }

    @Nullable
    static String whyNot(UUID village, Feast f) {
        return WHY.get(village + "/" + f.key);
    }

    // ------------------------------------------------------------------ the ground

    /** The first open block over the ground in this column (past grass, flowers and lying snow), or null. */
    @Nullable
    static BlockPos groundAt(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        for (int i = 0; i < 3 && open(level, p.below()); i++) p = p.below();
        return p;
    }

    /** Air, or what a block put there would push aside (grass, a flower, a layer of snow), and no water. */
    static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /**
     * Clear ground on the square, a few blocks off the heart (the founders' camp and the stores are in the
     * middle of it): a patch {@code 2hx+1} by {@code 2hz+1}, level with the heart give or take two, on sound
     * ground, with {@code height} blocks of open air over it, and nothing of a festival's on it already.
     */
    @Nullable
    static BlockPos clearSpot(ServerLevel level, Villages.Village v, int hx, int hz, int height) {
        BlockPos c = v.centre();
        Set<Long> ours = new HashSet<>();
        for (Placed p : town(v.id()).placed) ours.add(BlockPos.asLong(p.pos().getX(), 0, p.pos().getZ()));
        int edge = com.jrpetty.mcassistant.village.TownPlan.PLAZA - 2;
        for (int r = 5; r <= edge + 4; r++) {
            for (int i = 0; i < 8 * r; i++) {
                int dx, dz;
                if (i < 2 * r + 1) { dx = -r + i; dz = -r; }
                else if (i < 4 * r + 2) { dx = -r + (i - 2 * r - 1); dz = r; }
                else if (i < 6 * r + 1) { dx = -r; dz = -r + 1 + (i - 4 * r - 2); }
                else { dx = r; dz = -r + 1 + (i - 6 * r - 1); }
                // On the square while there is room on it; past its edge only if it has none.
                if (r <= edge && (Math.abs(dx) + hx > edge || Math.abs(dz) + hz > edge)) continue;
                BlockPos spot = groundAt(level, c.getX() + dx, c.getZ() + dz);
                if (spot == null || Math.abs(spot.getY() - c.getY()) > 2) continue;
                if (clear(level, spot, hx, hz, height, ours)) return spot;
            }
        }
        return null;
    }

    private static boolean clear(ServerLevel level, BlockPos spot, int hx, int hz, int height, Set<Long> ours) {
        for (int x = -hx; x <= hx; x++) {
            for (int z = -hz; z <= hz; z++) {
                BlockPos col = spot.offset(x, 0, z);
                if (!level.isLoaded(col) || ours.contains(BlockPos.asLong(col.getX(), 0, col.getZ()))) return false;
                BlockPos floor = col.below();
                BlockState f = level.getBlockState(floor);
                if (!f.isFaceSturdy(level, floor, Direction.UP) || !f.getFluidState().isEmpty()) return false;
                for (int y = 0; y < height; y++) if (!open(level, col.above(y))) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ out of the stores, and back

    /** So many single things that match out of the stores, one at a time (fewer if the stores run short). */
    static List<ItemStack> takeEach(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack one = Crafts.takeOne(level, v, what);
            if (one.isEmpty()) break;
            out.add(one);
        }
        return out;
    }

    static void back(ServerLevel level, Villages.Village v, List<ItemStack> items) {
        for (ItemStack s : items) if (!s.isEmpty()) Crafts.store(level, v, s);
    }

    /** A block set down for a festival, and written down with what goes back to the stores when it comes up. */
    static void put(ServerLevel level, Town town, BlockPos at, BlockState state, String feast, long day, ItemStack back) {
        level.setBlockAndUpdate(at, state);
        town.placed.add(new Placed(at.immutable(), feast, day, state.getBlock(), back));
    }

    @Nullable
    static BlockPos first(Town town, String feast) {
        for (Placed p : town.placed) if (p.feast().equals(feast)) return p.pos();
        return null;
    }

    static boolean has(Town town, String feast) {
        return first(town, feast) != null;
    }

    /** The folk of the town nearest this spot, awake and grown (to say a word as the work is done), or null. */
    @Nullable
    static VillageFolkEntity nearest(Villages.Village v, BlockPos at, double within) {
        VillageFolkEntity best = null;
        double bd = within * within;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || f.isShowcase()) continue;
            double d = f.blockPosition().distSqr(at);
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the maypole

    private static final Direction[] ROUND = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    /**
     * The maypole up on the square, by a hand at the town's works (or at once, {@code now}): five posts, the
     * stores' fence posts as far as they go, on a foot of logs to make up the five (or logs alone: the town's
     * small works take a fence post here and there, and a town with four is not to go without its dance), and
     * at its head a block of wool of each colour the stores have, up to five (two at the least). Null when it
     * is up; else why not yet, in a few words.
     */
    @Nullable
    static String raiseMaypole(ServerLevel level, Villages.Village v, long due, boolean now) {
        UUID id = v.id();
        Town town = town(id);
        if (has(town, "maypole")) return null;
        Predicate<ItemStack> fence = s -> s.is(ItemTags.WOODEN_FENCES) && s.getItem() instanceof BlockItem;
        Predicate<ItemStack> log = s -> s.is(ItemTags.LOGS) && s.getItem() instanceof BlockItem;
        if (Market.stock(level, id, s -> s.is(ItemTags.WOOL)) < 2) return "no wool in the stores for its ribbons";
        int fences = Market.stock(level, id, fence), logs = Market.stock(level, id, log);
        if (fences + logs < POLE) return "no fence posts or logs in the stores for the pole";
        BlockPos spot = clearSpot(level, v, 2, 2, POLE + 2);
        if (spot == null) return "no clear ground on the square for it";
        if (!now && !TownJobs.atWork(level, v, "festival", spot, "putting up the maypole")) return "waiting for a hand";
        // The foot first (the logs, if fence posts fall short), then the posts over it.
        List<ItemStack> posts = new ArrayList<>(takeEach(level, v, log, POLE - Math.min(POLE, fences)));
        posts.addAll(takeEach(level, v, fence, POLE - posts.size()));
        if (posts.size() < POLE) posts.addAll(takeEach(level, v, log, POLE - posts.size()));   // a post gone since the count
        List<ItemStack> wool = ribbons(level, v);
        if (posts.size() < POLE || wool.size() < 2) {
            back(level, v, posts);
            back(level, v, wool);
            return "short of its makings";
        }
        for (int i = 0; i < POLE; i++) {
            Block b = ((BlockItem) posts.get(i).getItem()).getBlock();
            put(level, town, spot.above(i), b.defaultBlockState(), "maypole", due, posts.get(i));
        }
        BlockPos head = spot.above(POLE - 1);
        for (int i = 0; i < wool.size(); i++) {
            BlockPos at = i == 0 ? head.above() : head.relative(ROUND[i - 1]);
            Block b = ((BlockItem) wool.get(i).getItem()).getBlock();
            put(level, town, at, b.defaultBlockState(), "maypole", due, wool.get(i));
        }
        dirty();
        level.playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        VillageFolkEntity by = nearest(v, spot, 12);
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "Up she goes! The maypole's up for tonight.",
            "There — a maypole fit for the dance!", "Ribbons and all. Roll on the evening!"));
        return null;
    }

    /** The maypole's ribbons: a block of wool of every colour the stores have, up to five; more of a colour to make up two. */
    static List<ItemStack> ribbons(ServerLevel level, Villages.Village v) {
        List<ItemStack> out = new ArrayList<>();
        Set<Item> colours = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL) && s.getItem() instanceof BlockItem && !colours.contains(s.getItem()));
            if (one.isEmpty()) break;
            colours.add(one.getItem());
            out.add(one);
        }
        while (out.size() < 2) {
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL) && s.getItem() instanceof BlockItem);
            if (one.isEmpty()) break;
            out.add(one);
        }
        return out;
    }

    // ------------------------------------------------------------------ the bonfire

    /**
     * The midsummer bonfire on the square: a campfire made as a player makes one (three logs, a coal or a
     * charcoal, three sticks — the sticks out of the stores, or sawn from their planks), or five of them in a
     * cross if the stores run to it. Lit as it is set down. Null when it burns; else why not.
     */
    @Nullable
    static String layBonfire(ServerLevel level, Villages.Village v, boolean now) {
        UUID id = v.id();
        Town town = town(id);
        if (has(town, "bonfire")) return null;
        int logs = Market.stock(level, id, s -> s.is(ItemTags.LOGS));
        int coal = Market.stock(level, id, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
        int can = Math.min(5, Math.min(logs / 4, coal));                    // three logs a fire, and a log's worth for its sticks
        if (logs < 4) return "no logs in the stores for the fire";
        if (coal < 1) return "no coal or charcoal in the stores to make the fire";
        BlockPos spot = clearSpot(level, v, 2, 2, 3);
        if (spot == null) return "no clear ground on the square for it";
        if (!now && !TownJobs.atWork(level, v, "festival", spot, "building the midsummer bonfire")) return "waiting for a hand";
        List<BlockPos> at = new ArrayList<>();
        at.add(spot);
        if (can >= 5) for (Direction d : ROUND) at.add(spot.relative(d));
        long day = level.getDayTime() / 24000L;
        int made = 0;
        for (BlockPos p : at) {
            if (!campfire(level, v)) break;
            put(level, town, p, Blocks.CAMPFIRE.defaultBlockState(), "bonfire", day, new ItemStack(Items.CHARCOAL, 2));
            made++;
        }
        dirty();
        if (made == 0) return "short of its makings";
        level.playSound(null, spot, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        VillageFolkEntity by = nearest(v, spot, 12);
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "There! The midsummer fire's lit.", "Look at it go — a proper bonfire!"));
        return null;
    }

    /** The makings of one campfire out of the stores, all or nothing: three logs, a coal or a charcoal, three sticks. */
    static boolean campfire(ServerLevel level, Villages.Village v) {
        if (!TownWork.take(level, v, s -> s.is(ItemTags.LOGS), 3)) return false;
        if (!TownWork.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)) {
            Crafts.store(level, v, new ItemStack(Items.OAK_LOG, 3));
            return false;
        }
        if (TownWork.take(level, v, s -> s.is(Items.STICK), 3)) return true;
        // Two planks make four sticks: three into the fire, one back.
        if (Crafts.usePlanks(level, v, 2)) {
            Crafts.store(level, v, new ItemStack(Items.STICK));
            return true;
        }
        Crafts.store(level, v, new ItemStack(Items.OAK_LOG, 3));
        Crafts.store(level, v, new ItemStack(Items.COAL));
        return false;
    }

    // ------------------------------------------------------------------ the long tables

    /** The harvest's long tables: two rows of nine, side by side, an aisle between. */
    public static final int TABLE = 9;

    /**
     * The long tables laid on the square for the harvest feast, by a hand (or at once): wooden slabs set high,
     * as a table top, out of the stores, or sawn from their planks (three planks make six slabs, of their own
     * wood). Null when they are laid (or there are none to be had: the feast goes on without); else why not.
     */
    @Nullable
    static String layTables(ServerLevel level, Villages.Village v, long due, boolean now) {
        UUID id = v.id();
        Town town = town(id);
        if (has(town, "harvest")) return null;
        int slabs = Market.stock(level, id, s -> s.is(ItemTags.WOODEN_SLABS));
        int planks = Market.stock(level, id, s -> s.is(ItemTags.PLANKS));
        if (slabs + planks * 2 < TABLE) return "no slabs or planks in the stores for the tables";
        BlockPos spot = clearSpot(level, v, 1, TABLE / 2, 2);
        if (spot == null) return "no clear ground on the square for them";
        if (!now && !TownJobs.atWork(level, v, "festival", spot, "laying the long tables")) return "waiting for a hand";
        List<ItemStack> tops = takeEach(level, v, s -> s.is(ItemTags.WOODEN_SLABS) && s.getItem() instanceof BlockItem, 2 * TABLE);
        while (tops.size() < 2 * TABLE) {
            List<ItemStack> sawn = sawSlabs(level, v);
            if (sawn.isEmpty()) break;
            tops.addAll(sawn);
        }
        int k = 0;
        for (int side : new int[]{ -1, 1 }) {
            for (int dz = -TABLE / 2; dz <= TABLE / 2 && k < tops.size(); dz++) {
                BlockPos at = spot.offset(side, 0, dz);
                if (!open(level, at)) continue;
                ItemStack top = tops.get(k++);
                Block b = ((BlockItem) top.getItem()).getBlock();
                BlockState st = b.defaultBlockState();
                if (st.hasProperty(SlabBlock.TYPE)) st = st.setValue(SlabBlock.TYPE, SlabType.TOP);
                put(level, town, at, st, "harvest", due, top);
            }
        }
        back(level, v, tops.subList(k, tops.size()));            // the slabs left over
        dirty();
        if (k == 0) return "short of its makings";
        VillageFolkEntity by = nearest(v, spot, 12);
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "The tables are laid for the harvest feast!", "Long tables for a long feast."));
        return null;
    }

    /** Six slabs sawn from three of the stores' planks of one wood (oak's, if the stores have three of no one wood). */
    static List<ItemStack> sawSlabs(ServerLevel level, Villages.Village v) {
        ItemStack first = Crafts.takeOne(level, v, s -> s.is(ItemTags.PLANKS));
        if (first.isEmpty()) return List.of();
        Item wood = first.getItem();
        Item slab = slabOf(wood);
        if (slab != null && TownWork.take(level, v, s -> s.is(wood), 2)) return single(slab, 6);
        if (!TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 2)) {
            Crafts.store(level, v, first);
            return List.of();
        }
        return single(Items.OAK_SLAB, 6);
    }

    private static List<ItemStack> single(Item item, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ItemStack(item));
        return out;
    }

    /** The slab of a plank's own wood ("spruce_planks" to "spruce_slab"), or null. */
    @Nullable
    static Item slabOf(Item planks) {
        ResourceLocation k = BuiltInRegistries.ITEM.getKey(planks);
        if (!k.getPath().endsWith("_planks")) return null;
        ResourceLocation s = ResourceLocation.fromNamespaceAndPath(k.getNamespace(), k.getPath().replace("_planks", "_slab"));
        Item it = BuiltInRegistries.ITEM.get(s);
        return it == Items.AIR || !(it instanceof BlockItem) ? null : it;
    }

    // ------------------------------------------------------------------ coming down

    /**
     * What a festival put up, taken down when it is over and its makings put back in the stores: the bonfire
     * at midnight (by the watch, or whoever is up: no hand needed for a bucket of ashes), the maypole and the
     * tables the morning after the festival is kept (or after its second evening), by a hand at the town's
     * works; the lanterns the morning after midwinter, one at a time along the avenue; the snowmen with the
     * thaw. Whatever is no longer there (a player took it) gives nothing back.
     */
    static void takeDown(ServerLevel level, Villages.Village v, Town town, long day, long t) {
        if (town.placed.isEmpty()) return;
        UUID id = v.id();
        Map<String, List<Placed>> groups = new LinkedHashMap<>();
        for (Placed p : town.placed) groups.computeIfAbsent(p.feast() + "|" + p.day(), k -> new ArrayList<>()).add(p);
        boolean changed = false;
        for (List<Placed> g : groups.values()) {
            Placed one = g.get(0);
            String feast = one.feast();
            long d = one.day();
            switch (feast) {
                case "bonfire" -> {
                    if (day == d && t < 18000L) continue;
                    for (Placed p : g) changed |= lift(level, v, town, p);
                    VillageFolkEntity by = nearest(v, one.pos(), 32);
                    if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "There — the fire's out. Off to bed, all.",
                        "Out it goes. What a night!"));
                    level.playSound(null, one.pos(), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8F, 1.0F);
                }
                case "snowman" -> {
                    if (Seasons.season(id, day) == Seasons.Season.WINTER) continue;
                    for (Placed p : g) {
                        // The thaw: the snow goes as snow does; a pumpkin head, if one is left, back to the stores.
                        if (level.isLoaded(p.pos()) && level.getBlockState(p.pos()).is(p.block())) level.removeBlock(p.pos(), false);
                        if (!p.back().isEmpty() && level.isLoaded(p.pos())) Crafts.store(level, v, p.back().copy());
                        town.placed.remove(p);
                        changed = true;
                    }
                }
                case "midwinter" -> {
                    if (day <= d || t < 1000L || t >= 12000L) continue;
                    // A lantern at a time, the hand walking the avenue (days on with no hand to spare, whoever passes).
                    if (!TownJobs.atWork(level, v, "festival", one.pos(), "taking in the midwinter lanterns") && day <= d + 3) continue;
                    changed |= lift(level, v, town, one);
                    if (g.size() == 1) Midwinter.told(level, v, town, d, true);
                }
                default -> {
                    Feast f = Feast.byKey(feast);
                    boolean over = f != null && keptFor(id, f) == d && day > keptOn(id, f) || day > d + 1;
                    if (!over || t < 1000L || t >= 12000L) continue;
                    if (!TownJobs.atWork(level, v, "festival", one.pos(), feast.equals("maypole") ? "taking down the maypole"
                        : "clearing away the long tables") && day <= d + 3) continue;      // (days on with no hand to spare, whoever passes)
                    for (Placed p : g) changed |= lift(level, v, town, p);
                }
            }
        }
        if (changed) dirty();
    }

    /** One thing taken up, and its makings back into the stores if it is still there as it was set down. */
    private static boolean lift(ServerLevel level, Villages.Village v, Town town, Placed p) {
        if (!level.isLoaded(p.pos())) return false;
        if (level.getBlockState(p.pos()).is(p.block())) {
            level.setBlockAndUpdate(p.pos(), Blocks.AIR.defaultBlockState());
            if (!p.back().isEmpty()) Crafts.store(level, v, p.back().copy());
        }
        town.placed.remove(p);
        return true;
    }

    // ------------------------------------------------------------------ the gatherings (Assemblies)

    /**
     * Tonight's festival, if one is due and ready (Assemblies.tick, at dusk, after Founding Day and before the
     * rest). A wedding, a vigil, a celebration or an honour has the evening (the festival is kept tomorrow);
     * on its day the weekly feast gives way to it. Rain puts it off a day; on its second evening it is kept.
     */
    @Nullable
    static Assemblies.Assembly evening(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Gatherings.Kind g = Gatherings.tonight(id, day);
        if (g != null && g != Gatherings.Kind.FEAST) return null;
        for (Feast f : GATHERED) {
            long d = dayThisYear(id, day, f);
            if (day != d && day != d + 1 || keptFor(id, f) == d) continue;
            if (level.isRaining() && day == d) continue;
            Assemblies.Assembly a = gathering(level, v, f, d, day);
            if (a != null) return a;
        }
        return null;
    }

    /** The gathering for a festival: round the maypole, round the fire, before the board, at the long tables. */
    @Nullable
    static Assemblies.Assembly gathering(ServerLevel level, Villages.Village v, Feast f, long due, long day) {
        UUID id = v.id();
        Town town = town(id);
        String subject = f.key + "|" + due;
        return switch (f) {
            case MAYPOLE -> {
                BlockPos pole = first(town, "maypole");
                yield pole == null ? null : new Assemblies.Assembly(id, Assemblies.Kind.FESTIVAL, subject, day,
                    pole.relative(Direction.SOUTH), Direction.SOUTH, Assemblies.Layout.RING);
            }
            case BONFIRE -> {
                BlockPos fire = first(town, "bonfire");
                yield fire == null ? null : new Assemblies.Assembly(id, Assemblies.Kind.FESTIVAL, subject, day,
                    fire.relative(Direction.SOUTH, 2), Direction.SOUTH, Assemblies.Layout.RING);
            }
            case FAIR -> {
                BlockPos lectern = VillageBoards.lectern(id);
                Direction facing = VillageBoards.facingOf(id);
                yield new Assemblies.Assembly(id, Assemblies.Kind.FESTIVAL, subject, day, lectern != null ? lectern : v.centre(),
                    facing != null ? facing : Direction.SOUTH, Assemblies.Layout.ARC);
            }
            case HARVEST -> {
                BlockPos head = tableHead(town);
                yield head != null
                    ? new Assemblies.Assembly(id, Assemblies.Kind.FESTIVAL, subject, day, head, Direction.SOUTH, Assemblies.Layout.AISLE)
                    : new Assemblies.Assembly(id, Assemblies.Kind.FESTIVAL, subject, day, v.centre(), Direction.SOUTH, Assemblies.Layout.RING);
            }
            case MIDWINTER -> null;
        };
    }

    /** Where the elder stands at the harvest feast: at the head of the long tables, facing down them. */
    @Nullable
    static BlockPos tableHead(Town town) {
        int n = 0;
        long sx = 0, sz = 0;
        int y = 0, minZ = Integer.MAX_VALUE;
        for (Placed p : town.placed) {
            if (!p.feast().equals("harvest")) continue;
            n++;
            sx += p.pos().getX();
            sz += p.pos().getZ();
            y = p.pos().getY();
            minZ = Math.min(minZ, p.pos().getZ());
        }
        if (n == 0) return null;
        return new BlockPos((int) Math.round((double) sx / n), y, minZ - 2);
    }

    /** The festival a gathering is for, from its subject ("maypole|33"). */
    @Nullable
    static Feast feastOf(String subject) {
        int bar = subject.indexOf('|');
        return Feast.byKey(bar < 0 ? subject : subject.substring(0, bar));
    }

    /** The festival's own day, from the gathering's subject. */
    static long dueOf(String subject) {
        int bar = subject.indexOf('|');
        try {
            return bar < 0 ? -1 : Long.parseLong(subject.substring(bar + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** What it is, in a few words (Assemblies.describe): "the May dance". */
    static String describe(String subject) {
        Feast f = feastOf(subject);
        return f == null ? "a festival" : f.words;
    }

    /** What is said, line by line (Assemblies.script). */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Feast f = feastOf(a.subject);
        if (f == null) return;
        String name = Villages.name(a.village);
        switch (f) {
            case MAYPOLE -> {
                s.add(new Assemblies.Line(null, "Spring is here, " + name + "! The maypole is up.", '!', null));
                s.add(new Assemblies.Line(null, "Everybody take a place round it — the children at the front!", '!', null));
                s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Strike up the tune, and round we go!", "Hands round — and dance!"), '!', null));
            }
            case BONFIRE -> {
                s.add(new Assemblies.Line(null, "Midsummer, friends: the longest day of the year, and the shortest night!", '!', null));
                s.add(new Assemblies.Line(null, "The fire's lit. Gather round it — and sing!", '!', null));
            }
            case FAIR -> Fair.script(level, a, s, r);
            case HARVEST -> harvestScript(level, a, s, r);
            case MIDWINTER -> { }
        }
        FashionShow.script(level, a, f, s, r);          // [fashion] the parade, and the rosette for the best-dressed
    }

    /** Does it go on to the dancing, the singing or the eating (Assemblies.step)? The fair ends with its prizes. */
    static boolean mingles(Assemblies.Assembly a) {
        Feast f = feastOf(a.subject);
        return f == Feast.MAYPOLE || f == Feast.BONFIRE || f == Feast.HARVEST;
    }

    private static final float[] TUNE = { 0.71F, 0.79F, 0.89F, 0.94F, 1.06F, 0.94F, 0.89F, 0.79F, 0.71F, 0.89F, 1.06F, 1.19F };

    private static final String[] SONGS = {
        "♪ Summer is a-coming in, loudly sing cuckoo! ♪", "♪ Over the hills and far away… ♪", "♪ Oh, the long days and the short nights! ♪",
        "♪ Round the fire, round the fire, sing until the stars! ♪", "♪ Hey ho, the summer's here! ♪", "♪ The oak and the ash and the bonny ivy tree… ♪" };

    /**
     * A folk's part once the words are said (Assemblies.mingle): round the maypole a place at a time, to a
     * tune; swaying and singing round the fire; eating at the long tables, out of the stores.
     */
    static boolean mingle(VillageFolkEntity f, ServerLevel level, Assemblies.Assembly a, RandomSource r) {
        Feast feast = feastOf(a.subject);
        if (feast == null) return false;
        long now = level.getGameTime();
        UUID me = f.getUUID();
        switch (feast) {
            case MAYPOLE -> {
                Long last = TURNED.get(a.village);
                if (last == null || now - last >= 40L || now < last) {
                    TURNED.put(a.village, now);
                    turnTheRing(a);
                    int beat = (int) ((now / 40L) % TUNE.length);
                    level.playSound(null, a.focus, SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.RECORDS, 1.2F, TUNE[beat]);
                    level.sendParticles(ParticleTypes.NOTE, a.focus.getX() + 0.5, a.focus.getY() + POLE + 1.0, a.focus.getZ() - 0.5, 0,
                        beat / 24.0, 0.0, 0.0, 1.0);
                }
                BlockPos pole = first(town(a.village), "maypole");
                if (pole != null) f.getLookControl().setLookAt(pole.getX() + 0.5, pole.getY() + 3.0, pole.getZ() + 0.5);
                if (r.nextInt(25) == 0 && f.onGround()) f.getJumpControl().jump();
                if (r.nextInt(30) == 0) f.swing(r.nextBoolean() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
                if (r.nextInt(220) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Round and round!", "Mind my feet!", "Faster, faster!",
                    "I've not danced like this in years!", "Wheee!"));
            }
            case BONFIRE -> {
                BlockPos fire = first(town(a.village), "bonfire");
                if (fire != null) f.getLookControl().setLookAt(fire.getX() + 0.5, fire.getY() + 0.8, fire.getZ() + 0.5);
                if (me.equals(a.host) && (now / 4L) % 10L == 0L) {
                    int beat = (int) ((now / 40L) % TUNE.length);
                    level.playSound(null, a.focus, SoundEvents.NOTE_BLOCK_GUITAR.value(), SoundSource.RECORDS, 1.0F, TUNE[beat]);
                }
                if (r.nextInt(20) == 0) f.swing(r.nextBoolean() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
                if (r.nextInt(140) == 0) {
                    FolkTalk.speak(f, SONGS[r.nextInt(SONGS.length)]);
                    level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getEyeY() + 0.6, f.getZ(), 0, r.nextInt(24) / 24.0, 0.0, 0.0, 1.0);
                }
                if (f.isBaby() && r.nextInt(30) == 0 && f.onGround()) f.getJumpControl().jump();
            }
            case HARVEST -> {
                if (!a.ate.contains(me) && r.nextInt(25) == 0) {
                    a.ate.add(me);
                    Villages.Village v = Villages.get(a.village);
                    ItemStack food = v == null ? ItemStack.EMPTY : Crafts.takeOne(level, v,
                        s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH)
                            && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH));
                    if (!food.isEmpty()) {
                        level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, food),
                            f.getX(), f.getEyeY(), f.getZ(), 6, 0.15, 0.1, 0.15, 0.03);
                        f.playSound(SoundEvents.GENERIC_EAT, 0.6F, 0.9F + r.nextFloat() * 0.2F);
                        f.heal(2.0F);
                    }
                }
                if (r.nextInt(180) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Pass the bread!", "To the harvest!", "To our farmers!",
                    "Best harvest supper I can remember.", "Another slice? Go on then."));
            }
            default -> { }
        }
        // A word with whoever is beside it.
        if (r.nextInt(300) == 0 && !a.chatted.contains(me)) {
            VillageFolkEntity near = null;
            double best = 9.0;
            for (UUID u : a.seated.keySet()) {
                if (u.equals(me) || !(level.getEntity(u) instanceof VillageFolkEntity o)) continue;
                double d = o.distanceToSqr(f);
                if (d < best) { best = d; near = o; }
            }
            if (near != null && Smalltalk.chat(f, near, level)) {
                a.chatted.add(me);
                a.chatted.add(near.getUUID());
            }
        }
        return true;
    }

    /**
     * The May dance, once a second from the town's own tick: the ring turned every two seconds, and every
     * dancer sent on to its place in it, up off a bench if it sat down for the speeches. The ring used to turn
     * only from a dancer's own part in the gathering (mingle), and only when one stood right at its place; the
     * crowd's walk was the gathering's (Assemblies.attend), which a hand that gave up a path a moment before
     * (AssistantEntity: the stuck give-up's ten seconds) or sat on a bench within a step of its place never
     * took. The ring stood still and nobody danced. Now the dance turns, and its dancers walk it, whoever is
     * standing where; a dancer called away by a fire, a thunderstorm or its sickbed is left to that.
     */
    static void dance(ServerLevel level, Villages.Village v) {
        Assemblies.Assembly a = Assemblies.underWay(v.id());
        if (a == null || a.kind != Assemblies.Kind.FESTIVAL || a.phase != Assemblies.Phase.MINGLE || feastOf(a.subject) != Feast.MAYPOLE) return;
        long now = level.getGameTime();
        Long last = TURNED.get(v.id());
        if (last == null || now - last >= 40L || now < last) {
            TURNED.put(v.id(), now);
            turnTheRing(a);
        }
        for (Map.Entry<UUID, Integer> e : a.seated.entrySet()) {
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || !f.isAlive() || f.isSleeping() || !Assemblies.attending(f)) continue;
            // Called away by something more pressing (a fire, a thunderstorm, laid up): that comes first.
            if (FireBrigade.onIt(f) || Weather.sheltering(f) || Health.laidUp(f)) continue;
            int i = e.getValue();
            if (i < 0 || i >= a.seats.size()) continue;
            BlockPos place = a.seats.get(i);
            if (Seats.seated(f)) Seats.stand(f, null);                // a dance is danced on its feet
            double dx = f.getX() - (place.getX() + 0.5), dz = f.getZ() - (place.getZ() + 0.5);
            if (dx * dx + dz * dz <= 0.9 * 0.9) continue;
            if (!f.getNavigation().isInProgress()) f.getNavigation().moveTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5, 0.8D);
            f.hobbyNow = "dancing round the maypole";
        }
    }

    /**
     * The dance: every seated folk moves on to the next place round its ring, all at once, so the ring turns
     * round the pole a place at a time and nobody steps into anybody's place.
     */
    static void turnTheRing(Assemblies.Assembly a) {
        if (a.seats.isEmpty() || a.seated.isEmpty()) return;
        double cx = a.focus.getX() + 0.5, cz = a.focus.getZ() + 0.5;
        int n = a.seats.size();
        double[] rad = new double[n], ang = new double[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = a.seats.get(i);
            double dx = p.getX() + 0.5 - cx, dz = p.getZ() + 0.5 - cz;
            rad[i] = Math.sqrt(dx * dx + dz * dz);
            ang[i] = Math.atan2(dz, dx);
        }
        Map<UUID, Integer> moved = new HashMap<>();
        for (Map.Entry<UUID, Integer> e : a.seated.entrySet()) {
            int i = e.getValue();
            if (i < 0 || i >= n) continue;
            int best = i;
            double step = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i || Math.abs(rad[j] - rad[i]) > 0.9) continue;
                double d = Math.floorMod((long) Math.round((ang[j] - ang[i]) * 1000.0), Math.round(2 * Math.PI * 1000.0)) / 1000.0;
                if (d > 0.0 && d < step) { step = d; best = j; }
            }
            moved.put(e.getKey(), best);
        }
        a.seated.putAll(moved);
    }

    /** At its close (Assemblies.close): kept, into the chronicle, and everybody there the better for it. */
    static void closed(ServerLevel level, Assemblies.Assembly a) {
        Feast f = feastOf(a.subject);
        if (f == null) return;
        UUID id = a.village;
        long due = dueOf(a.subject), day = level.getDayTime() / 24000L;
        int n = 0;
        for (UUID u : a.seated.keySet()) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity folk)) continue;
            folk.persona().feasted(day);
            n++;
        }
        switch (f) {
            case MAYPOLE -> Villages.tell(id, day, "the town danced round the maypole on the square" + (n > 1 ? ", " + n + " of us" : ""));
            case BONFIRE -> Villages.tell(id, day, "the midsummer bonfire was lit on the square, and " + (n > 1 ? n + " of us" : "the town")
                + " sang round it");
            case FAIR -> {
                Villages.Village v = Villages.get(id);
                if (v != null) Fair.finish(level, v, due, true);
            }
            case HARVEST -> harvestKept(level, id, due, true);
            case MIDWINTER -> { }
        }
        kept(id, f, due, day);
    }

    /** For the tests and /village festival ... now: the festival called now, whatever the hour. True if it began. */
    public static boolean callNow(ServerLevel level, Villages.Village v, Feast f) {
        CALLED.put(v.id(), f);
        try {
            return Assemblies.startNow(level, v, Assemblies.Kind.FESTIVAL);
        } finally {
            CALLED.remove(v.id());
        }
    }

    /** Assemblies.startNow's FESTIVAL: the one called now, for its day this year. */
    @Nullable
    static Assemblies.Assembly calledNow(ServerLevel level, Villages.Village v, long day) {
        Feast f = CALLED.get(v.id());
        return f == null ? null : gathering(level, v, f, dayThisYear(v.id(), day, f), day);
    }

    // ------------------------------------------------------------------ the harvest, counted all year

    /** Food a hand brought in (Economy.produced): the year's harvest, by where it came from and, the fields', by whose. */
    public static void broughtIn(VillageFolkEntity f, AssistantEntity.StationTask trade, ItemStack s) {
        UUID id = f.ownerId();
        if (id == null || s.isEmpty()) return;
        double meals = s.get(net.minecraft.core.component.DataComponents.FOOD) != null ? s.getCount()
            : s.is(Items.WHEAT) ? s.getCount() / 3.0 : 0;
        if (meals <= 0) return;
        int year = Seasons.year(id, f.level().getDayTime() / 24000L);
        Town town = town(id);
        if (town.harvestYear != year) {
            town.harvestYear = year;
            town.farmers.clear();
            town.farmerNames.clear();
            java.util.Arrays.fill(town.sources, 0);
        }
        town.sources[Larder.source(trade)] += meals;
        if (trade == AssistantEntity.StationTask.FARM) {
            town.farmers.merge(f.getUUID(), meals, Double::sum);
            town.farmerNames.put(f.getUUID(), f.displayNameCap());
        }
        dirty();
    }

    /** The year's harvest in words: "412 meals from the fields, 80 from the water, 20 from the hunt and 15 from the pens". */
    static String totals(Town town, int year) {
        double[] s = town.harvestYear == year ? town.sources : new double[5];
        return Math.round(s[0]) + " meals from the fields, " + Math.round(s[1]) + " from the water, " + Math.round(s[2])
            + " from the hunt and " + Math.round(s[3]) + " from the pens";
    }

    /** The farmer of this town who brought in the most this year and is with it still: {id, name, meals}, or null. */
    @Nullable
    static Object[] bestFarmer(ServerLevel level, UUID village, Town town, int year) {
        if (town.harvestYear != year) return null;
        UUID best = null;
        double most = 0;
        for (Map.Entry<UUID, Double> e : town.farmers.entrySet()) {
            if (e.getValue() <= most) continue;
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || !f.isAlive() || !village.equals(f.ownerId())) continue;
            best = e.getKey();
            most = e.getValue();
        }
        return best == null ? null : new Object[]{ best, town.farmerNames.getOrDefault(best, "?"), (int) Math.round(most) };
    }

    private static void harvestScript(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        UUID id = a.village;
        long due = dueOf(a.subject);
        Town town = town(id);
        int year = Seasons.year(id, due);
        s.add(new Assemblies.Line(null, "Friends! The year's harvest is in.", '!', null));
        s.add(new Assemblies.Line(null, "This year we brought in " + totals(town, year) + ".", '?', null));
        Object[] best = bestFarmer(level, id, town, year);
        if (best != null) {
            s.add(new Assemblies.Line(null, "The prize for the farmer who brought in the most: " + best[1] + ", with " + best[2]
                + " meals! " + HARVEST_PRIZE + " coins from the treasury.", '!', () -> payPrize(level, id, year)));
        } else {
            s.add(new Assemblies.Line(null, "No farmer's harvest to judge this year. Next year, then!", '~', null));
        }
        s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Now sit down and eat — it's all our own!", "Eat, all of you: the tables are full!"), '!', null));
    }

    /** The harvest's prize, once a year, out of the treasury to its best farmer. Returns what was said, or null. */
    @Nullable
    static String payPrize(ServerLevel level, UUID village, int year) {
        Town town = town(village);
        if (town.prizeYear == year) return null;
        Object[] best = bestFarmer(level, village, town, year);
        if (best == null) return null;
        town.prizeYear = year;
        dirty();
        int coins = Ledger.takeCoins(village, HARVEST_PRIZE);
        if (coins > 0) Economy.spent(village, coins);
        if (level.getEntity((UUID) best[0]) instanceof VillageFolkEntity f) {
            f.earn(coins);
            long day = level.getDayTime() / 24000L;
            f.persona().remember(day, "I won the harvest prize: " + best[2] + " meals brought in this year", 5);
            f.sayLater(coins > 0 ? FolkTalk.pick(f.getRandom(), "Me? Thank you, all of you!", "Ha! All those early mornings!")
                : "The treasury's empty — but the honour's enough for me!", 30);
        }
        town.prizeWords = best[1] + " took the farmer's prize" + (coins > 0 ? " of " + coins + (coins == 1 ? " coin" : " coins") : "")
            + ", for " + best[2] + " meals brought in";
        return town.prizeWords;
    }

    /** The harvest kept (at its feast, or at the board if the feast never came): the year into the chronicle, the prize paid. */
    static void harvestKept(ServerLevel level, UUID village, long due, boolean feast) {
        Town town = town(village);
        int year = Seasons.year(village, due);
        if (town.harvestTold == year) return;
        String prize = payPrize(level, village, year);
        if (prize == null && town.prizeYear == year && !town.prizeWords.isEmpty()) prize = town.prizeWords;   // paid at the feast
        String line = "the harvest festival was kept" + (!feast ? "" : has(town, "harvest") ? " at the long tables" : " with a feast on the square")
            + ": the year's harvest was "
            + totals(town, year) + (prize == null ? "" : "; " + prize);
        town.harvestTold = year;
        town.lastHarvest = line;
        dirty();
        Villages.tell(village, level.getDayTime() / 24000L, line);
    }

    /** The harvest's evenings gone by without its feast: its year told and its prize paid all the same. */
    private static void harvestMissed(ServerLevel level, Villages.Village v, Town town, long day, long t) {
        long d = dayThisYear(v.id(), day, Feast.HARVEST);
        if (day < d + 1 || day == d + 1 && t < 14000L || keptFor(v.id(), Feast.HARVEST) == d) return;
        if (day > d + 3) return;                                     // long ago (a town met after it): nothing to tell
        if (town.harvestTold == Seasons.year(v.id(), d)) return;
        harvestKept(level, v.id(), d, false);
        kept(v.id(), Feast.HARVEST, d, day);
    }

    // ------------------------------------------------------------------ how the town feels

    /** A festival kept in the last few days: the town is the happier (Contentment.compute). */
    static int contentment(UUID village, long day, List<String> good) {
        Festivals f = of();
        Town town = f == null ? LOOSE.get(village) : f.towns.get(village);
        if (town == null) return 0;
        for (Feast k : Feast.values()) {
            Long on = town.keptOn.get(k.key);
            if (on != null && day - on >= 0 && day - on < GLOW_DAYS) {
                good.add(k.words + " kept");
                return GLOW;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ where the player sees it

    /** "the May dance round the maypole on the square this evening". */
    static String todayWords(Feast f) {
        return switch (f) {
            case MAYPOLE -> "the May dance round the maypole on the square, this evening";
            case BONFIRE -> "the midsummer bonfire on the square, at dusk";
            case FAIR -> "the town fair: bring your best bread, wool, fish or honey to the board, judged this evening";
            case HARVEST -> "the harvest festival: a feast at the long tables on the square, this evening";
            case MIDWINTER -> "midwinter: lanterns along the avenue, and presents between friends";
        };
    }

    /** What a festival needs that it has not got, for the board's warning: "No maypole: no wool in the stores for its ribbons". */
    private static String thing(Feast f) {
        return switch (f) {
            case MAYPOLE -> "No maypole";
            case BONFIRE -> "No bonfire";
            case HARVEST -> "No long tables";
            case MIDWINTER -> "No lanterns";
            case FAIR -> "No fair";
        };
    }

    /** The board's lines (Seasons.board): today's festival (or why it waits), the next one, the last fair's ribbons. */
    static List<String> boardLines(ServerLevel level, UUID village, long day) {
        List<String> out = new ArrayList<>();
        Feast today = today(village, day);
        if (today == Feast.FAIR) {
            out.add("RG|Fair day! Bring your best bread, wool, fish or honey: right-click this board with it (or /village fair enter). "
                + "Judged this evening.");
        } else if (today != null) {
            out.add("RG|Today: " + todayWords(today) + ".");
        }
        if (today != null) {
            String why = whyNot(village, today);
            if (why != null && !why.equals("waiting for a hand")) out.add("RW|" + thing(today) + ": " + why + ".");
        }
        Feast next = null;
        long nd = Long.MAX_VALUE;
        for (Feast f : Feast.values()) {
            if (f == today) continue;
            long n = next(village, day, f);
            if (n > day && n < nd) { nd = n; next = f; }
        }
        if (next != null) out.add("RM|Next: " + next.words + " on day " + (nd + 1) + (nd - day == 1 ? " (tomorrow)" : " (in " + (nd - day) + " days)") + ".");
        Town town = town(village);
        if (town.fairJudged >= 0 && day - town.fairJudged <= 3 && !town.fairResults.isEmpty()) {
            out.add("RN|The fair's ribbons: " + String.join("; ", town.fairResults) + ".");
        }
        return out;
    }

    /** The town's books (Seasons.book): the year's festivals and when, the fair's last ribbons, the harvest so far. */
    static List<String> bookLines(ServerLevel level, UUID village, long day) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder("The year's festivals: ");
        boolean firstOne = true;
        for (Feast f : Feast.values()) {
            long d = dayThisYear(village, day, f);
            String when = keptFor(village, f) == d ? "kept" : d == day ? "today" : d < day ? "gone by" : "in " + (d - day) + (d - day == 1 ? " day" : " days");
            sb.append(firstOne ? "" : "; ").append(f.words).append(", day ").append(d + 1).append(" (").append(when).append(')');
            firstOne = false;
        }
        out.add(sb.append('.').toString());
        Town town = town(village);
        if (!town.fairResults.isEmpty()) out.add("The fair's ribbons, day " + (town.fairJudged + 1) + ": " + String.join("; ", town.fairResults) + ".");
        int year = Seasons.year(village, day);
        if (town.harvestYear == year) {
            Object[] best = bestFarmer(level, village, town, year);
            out.add("This year's harvest so far: " + totals(town, year) + "." + (best == null ? "" : " The best farmer so far: "
                + best[1] + ", " + best[2] + " meals."));
        }
        if (!town.lastHarvest.isEmpty()) out.add("Last harvest: " + town.lastHarvest + ".");
        if (town.giftsDay >= 0 && day - town.giftsDay < TownCalendar.YEAR_DAYS) out.add("Midwinter presents given: " + town.gifts + ".");
        if (town.snowmen > 0 && Seasons.season(village, day) == Seasons.Season.WINTER) out.add("Snowmen built this winter: " + town.snowmen + ".");
        return out;
    }

    /** The crier's word on today's festival, or tomorrow's (Seasons.cry); null if neither. */
    @Nullable
    static String cryLine(UUID village, long day) {
        Feast today = today(village, day);
        if (today != null) {
            return switch (today) {
                case MAYPOLE -> "Tonight: the May dance round the maypole — everybody welcome!";
                case BONFIRE -> "Tonight: the midsummer bonfire on the square — come and sing!";
                case FAIR -> "It's fair day! Bring your best bread, wool, fish and honey to the board — the elder judges at dusk!";
                case HARVEST -> "Tonight: the harvest festival, a feast at the long tables!";
                case MIDWINTER -> "Happy midwinter! Lanterns along the avenue, and a present for a friend.";
            };
        }
        Feast tomorrow = today(village, day + 1);
        return tomorrow == null ? null : "Tomorrow: " + tomorrow.words + "!";
    }

    /** A festival today or tomorrow, as folk talk of it ("the May dance tonight"), or null. */
    @Nullable
    static String talkOf(UUID village, long day) {
        Feast today = today(village, day);
        if (today != null && today != Feast.MIDWINTER) return today.words + (today == Feast.FAIR ? " today" : " tonight");
        Feast tomorrow = today(village, day + 1);
        return tomorrow == null || tomorrow == Feast.MIDWINTER ? null : tomorrow.words + " tomorrow";
    }

    // ------------------------------------------------------------------ for the tests and the commands

    /** What this festival has put up just now: {feast, x, y, z} for each block. */
    public static List<int[]> placedForTests(UUID village, Feast f) {
        List<int[]> out = new ArrayList<>();
        for (Placed p : town(village).placed) {
            if (p.feast().equals(f.key)) out.add(new int[]{ p.pos().getX(), p.pos().getY(), p.pos().getZ() });
        }
        return out;
    }

    /** The May dance as it stands: each dancer's place in the ring, and how far off it the dancer is ("Ada 3 0.4"). */
    public static List<String> danceForTests(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Assemblies.Assembly a = Assemblies.underWay(village);
        if (a == null) return out;
        for (Map.Entry<UUID, Integer> e : a.seated.entrySet()) {
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || e.getValue() < 0 || e.getValue() >= a.seats.size()) continue;
            BlockPos p = a.seats.get(e.getValue());
            double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
            out.add(String.format(Locale.ROOT, "%s place %d off %.1f%s%s", f.displayNameCap(), e.getValue(), Math.sqrt(dx * dx + dz * dz),
                Seats.seated(f) ? " sat" : "", Assemblies.attending(f) ? "" : " not-attending"));
        }
        return out;
    }

    /** What the town's snowmen stand on just now (the snow and the heads). */
    public static List<BlockPos> snowmenForTests(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Placed p : town(village).placed) if (p.feast().equals("snowman")) out.add(p.pos());
        return out;
    }

    /** The festival's things put up now, by the nearest hand, whatever the hour: null when done, else why not. */
    @Nullable
    public static String setUpForTests(ServerLevel level, Villages.Village v, Feast f) {
        long day = level.getDayTime() / 24000L;
        return switch (f) {
            case MAYPOLE -> raiseMaypole(level, v, dayThisYear(v.id(), day, f), true);
            case BONFIRE -> layBonfire(level, v, true);
            case HARVEST -> layTables(level, v, dayThisYear(v.id(), day, f), true);
            case MIDWINTER -> Midwinter.hangAll(level, v, dayThisYear(v.id(), day, f), true);
            case FAIR -> null;
        };
    }

    /** One look at what is over and comes down (the tests: as the town's own second does). */
    public static void takeDownForTests(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime();
        takeDown(level, v, town(v.id()), dt / 24000L, dt % 24000L);
    }

    /** One whole second of the town's festivals (the tests). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** The gathering's close as Assemblies holds it, with these folk there (the tests: no waiting for the crowd). */
    public static void closeForTests(ServerLevel level, Villages.Village v, Feast f, List<VillageFolkEntity> there) {
        long day = level.getDayTime() / 24000L;
        Assemblies.Assembly a = gathering(level, v, f, dayThisYear(v.id(), day, f), day);
        if (a == null) {
            a = new Assemblies.Assembly(v.id(), Assemblies.Kind.FESTIVAL, f.key + "|" + dayThisYear(v.id(), day, f), day, v.centre(),
                Direction.SOUTH, Assemblies.Layout.RING);
        }
        for (int i = 0; i < there.size(); i++) a.seated.put(there.get(i).getUUID(), i);
        List<Assemblies.Line> lines = new ArrayList<>();
        script(level, a, lines, level.getRandom());
        for (Assemblies.Line l : lines) if (l.effect() != null) l.effect().run();
        for (VillageFolkEntity folk : there) folk.persona().remember(day, "I was at " + describe(a.subject), 2);   // as Assemblies.close
        closed(level, a);
    }

    /** What a festival kept lately adds to the town's contentment, and the words for it ("the May dance kept"). */
    public static int contentmentForTests(UUID village, long day, List<String> good) {
        return contentment(village, day, good);
    }

    /** Things of the fair's waiting for players who were away. */
    public static int owedForTests(UUID village) {
        return town(village).owed.size();
    }

    /** The script's lines (the tests read them). */
    public static List<String> scriptForTests(ServerLevel level, Villages.Village v, Feast f) {
        long day = level.getDayTime() / 24000L;
        Assemblies.Assembly a = new Assemblies.Assembly(v.id(), Assemblies.Kind.FESTIVAL, f.key + "|" + dayThisYear(v.id(), day, f), day,
            v.centre(), Direction.SOUTH, Assemblies.Layout.RING);
        List<Assemblies.Line> lines = new ArrayList<>();
        script(level, a, lines, level.getRandom());
        List<String> out = new ArrayList<>();
        for (Assemblies.Line l : lines) out.add(l.text());
        return out;
    }

    /** The harvest's tallies this year: {fields, water, hunt, pens, kitchens}. */
    public static double[] harvestForTests(UUID village) {
        Town t = town(village);
        return t.sources.clone();
    }

    /** A line about the town's festivals for /village season and /village festival. */
    public static String status(ServerLevel level, UUID village) {
        long day = level.getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder();
        for (Feast f : Feast.values()) {
            long d = dayThisYear(village, day, f);
            if (sb.length() > 0) sb.append("; ");
            sb.append(f.key.toUpperCase(Locale.ROOT)).append(" day ").append(d + 1).append(keptFor(village, f) == d ? " kept" : "");
            String why = whyNot(village, f);
            if (why != null && (d == day || d + 1 == day)) sb.append(" (").append(why).append(')');
        }
        return sb.toString();
    }
}
