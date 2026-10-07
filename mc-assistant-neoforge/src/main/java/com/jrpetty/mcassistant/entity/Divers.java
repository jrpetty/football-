package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [diver] The kelp farmer and diver: a town by a river, a lake or the sea keeps one from fifteen folk (two at fifty),
 * from the Wood Age on. A town with no water near it keeps none, and its board says why.
 *
 * <ul>
 * <li><b>The waterside</b> ({@link Waterside}): looked for round the town once it is big enough, the nearest open water
 *     at least three deep (not a pond: a river, a lake or the sea), a bank to go in from, a sandy beach for the turtles
 *     if there is one, and a dry spot on the bank for the diver's shed (blueprints/divershed.txt: a smoker, a campfire
 *     for a drying rack, a bench to pack at and a barrel for the gear), which the town builds when the trade opens.</li>
 * <li><b>The kelp beds</b> (KelpBeds): kelp planted on the bed of the water, three deep and more, eight plants to
 *     start and up to twenty-four as the town's fires want more; harvested by cutting it above the lowest piece, so it
 *     grows again by the game's own rule, and the wild kelp round about the same. Dried in the shed's smoker (a kelp
 *     block, once there is one, fuels the next batch) and over its campfire, packed nine to a dried kelp block at its
 *     bench, and the blocks to the stores, where the town's fires burn them before coal (FuelBook). A little dried kelp
 *     is kept loose for a hungry larder.</li>
 * <li><b>Diving for more</b>: clay from the bed while the town is short of it (the masons' bricks), sand for the glass
 *     and gravel for the flint, seagrass for the turtles (with the stores' shears), sea pickles to light the quay under
 *     the water, and, in the Iron Age with two of the watch, the prismarine of an ocean monument's outer walls
 *     (DiverRaids).</li>
 * <li><b>The turtle beach</b> (TurtleBeach): wild turtles fed seagrass to breed, their eggs fenced and lit, the
 *     hatchlings fed till they grow, each dropping a scute as it does (the game's own); five scutes are a turtle helmet,
 *     made by the smith (or the diver, with no smith), worn by the divers, spare ones to the fleet's fishers and one to
 *     the shop.</li>
 * <li><b>The quay</b>: anything lost over the side picked out of the water, a fleet boat drifted off its berth brought
 *     back to it once the boats are in, and anybody of the town in trouble in the water swum out to and pulled ashore
 *     ({@link #rescue}).</li>
 * </ul>
 * It swims for all of it (DiverSwim): straight down, along the bed and up, and up for air before its breath runs
 * low; it never drowns. It works from dawn to dusk, never in a thunderstorm. Everything it brings up goes into the
 * stores, booked as its work; its books, its board line, its card and the trade's book say what it did.
 */
public final class Divers {

    private Divers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The town keeps a diver from this many folk, a second from this many. */
    public static final int FROM = 15, SECOND_AT = 50, MOST = 2;
    /** The diver's shed. */
    public static final String SHED = "divershed";
    /** Water a kelp bed is planted in is this deep at the least; a diver goes no deeper than this under the surface. */
    public static final int DEEP = 3, DEEPEST = 12;
    /** The kelp bed: plants to start, and the most. */
    public static final int BED_LEAST = 8, BED_MOST = 24;
    /** How far past the town's edge the diving water may lie, and how far from its middle the diver works. */
    static final int WATER_REACH = 48, WORK_REACH = 16;
    /** The stores' clay, sand, gravel and seagrass the diver keeps up. */
    public static final int CLAY_WANTED = 64, SAND_WANTED = 32, GRAVEL_WANTED = 16, SEAGRASS_KEPT = 16;
    /** Dried kelp left loose for the larder while the town is hungry, and kelp kept for planting. */
    public static final int LOOSE_KELP = 32, KELP_FOR_BED = 16;
    /** How often a town with no water found looks again, and a dive's longest. */
    static final long SURVEY_EVERY = 2400L, LONGEST_DIVE = 6000L;
    /** How long in the water before a folk is thought in trouble, and how near the diver it must be. */
    static final long WET_FOR = 120L;
    static final int RESCUE_REACH = 40;

    // ------------------------------------------------------------------ the waterside

    /** A town's diving water: where it is, the bank, the beach, the shed's place, the kelp bed, and the books. */
    public static final class Waterside {
        BlockPos middle = BlockPos.ZERO, bank = BlockPos.ZERO;
        int surface;
        String kind = "the water";
        @Nullable BlockPos beach, shedAt;
        Direction shedBack = Direction.NORTH;
        @Nullable BlockPos monument;
        long found = -1, monumentLooked = -1;
        /** The kelp roots it planted, and the wild kelp it found round about (each the lowest piece). */
        final List<BlockPos> bed = new ArrayList<>(), wild = new ArrayList<>();
        /** Everything it has done, all told. */
        public long planted, harvests, kelp, dried, blocks, clay, sand, gravel, seagrass, pickles, lights, scutes, helmets,
            rescued, bred, hatched, prismarine, boats, flotsam, raids, surfaced;

        public BlockPos middle() { return middle; }
        public BlockPos bank() { return bank; }
        public int surface() { return surface; }
        public String kind() { return kind; }
        @Nullable public BlockPos beach() { return beach; }
        @Nullable public BlockPos shedAt() { return shedAt; }
        public Direction shedBack() { return shedBack; }
        @Nullable public BlockPos monument() { return monument; }
        public List<BlockPos> bed() { return List.copyOf(bed); }
        public List<BlockPos> wild() { return List.copyOf(wild); }
    }

    private static final Map<UUID, Waterside> WATER = new ConcurrentHashMap<>();
    /** Towns looked at and found dry: when, and why. */
    private static final Map<UUID, String> DRY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>(), SURVEYED = new ConcurrentHashMap<>();
    /** Each diver's dive in hand. */
    private static final Map<UUID, Dive> DIVES = new ConcurrentHashMap<>();
    /** The folk being pulled out of the water, and by whom. */
    private static final Map<UUID, UUID> TOWED = new ConcurrentHashMap<>();
    /** When and where each folk near the water was first seen out of its depth in it (cleared when it is out): a folk
     *  swimming steadily across is making way, and the count starts again from where it has got to. */
    private static final Map<UUID, double[]> WET = new ConcurrentHashMap<>();
    /** What a player asked a diver to bring up (talk), by the diver. */
    private static final Map<UUID, Order> ORDERS = new ConcurrentHashMap<>();
    /** Each diver's last word on what it was about (its card). */
    private static final Map<UUID, String> LAST_DONE = new ConcurrentHashMap<>();
    /** Tests: the hours a diver keeps (null: dawn to dusk). */
    private static volatile Boolean onDutyForTests;
    /** Tests: false, a diver starts no dive of its own (a test sets each going); null, its own day. */
    private static volatile Boolean ownWorkForTests;

    public static void resetForTests() {
        WATER.clear();
        DRY.clear();
        LAST.clear();
        SURVEYED.clear();
        DIVES.clear();
        TOWED.clear();
        WET.clear();
        ORDERS.clear();
        LAST_DONE.clear();
        onDutyForTests = null;
        ownWorkForTests = null;
        KelpBeds.resetForTests();
        TurtleBeach.resetForTests();
        DiverRaids.resetForTests();
        FuelBook.resetForTests();
    }

    /** Tests: a diver at work whatever the hour (true), never (false), or by the clock (null). */
    public static void onDutyForTests(@Nullable Boolean on) {
        onDutyForTests = on;
    }

    /** Tests: a diver left to choose its own dives (null, true), or only the ones a test sets going (false). Its
     *  rescues are its own either way. */
    public static void ownWorkForTests(@Nullable Boolean on) {
        ownWorkForTests = on;
    }

    // ------------------------------------------------------------------ the books

    /** The town's diving water, or null if it has none (or has not looked yet). */
    @Nullable
    public static Waterside water(@Nullable UUID village) {
        if (village == null) return null;
        Waterside w = WATER.get(village);
        if (w != null) return w;
        w = decode(Ledger.note(village, "diver.water"));
        if (w != null) {
            readBooks(w, Ledger.note(village, "diver.books"));
            WATER.put(village, w);
        }
        return w;
    }

    static void save(UUID village, Waterside w) {
        WATER.put(village, w);
        Ledger.note(village, "diver.water", encode(w));
        Ledger.note(village, "diver.books", books(w));
    }

    private static String p(@Nullable BlockPos b) {
        return b == null ? "-" : b.getX() + "," + b.getY() + "," + b.getZ();
    }

    @Nullable
    private static BlockPos p(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) return null;
        String[] f = s.split(",");
        if (f.length < 3) return null;
        return new BlockPos(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()));
    }

    private static String list(List<BlockPos> ps) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos b : ps) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p(b));
        }
        return sb.toString();
    }

    private static void list(String s, List<BlockPos> into) {
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            BlockPos b = p(part);
            if (b != null) into.add(b);
        }
    }

    static String encode(Waterside w) {
        return String.join("|", "v1", p(w.middle), p(w.bank), Integer.toString(w.surface), w.kind.replace('|', '/'), p(w.beach), p(w.shedAt),
            w.shedBack.getName(), p(w.monument), Long.toString(w.found), Long.toString(w.monumentLooked), list(w.bed), list(w.wild));
    }

    @Nullable
    static Waterside decode(@Nullable String s) {
        if (s == null || !s.startsWith("v1|")) return null;
        try {
            String[] f = s.split("\\|", -1);
            Waterside w = new Waterside();
            w.middle = p(f[1]);
            w.bank = p(f[2]);
            if (w.middle == null || w.bank == null) return null;
            w.surface = Integer.parseInt(f[3]);
            w.kind = f[4];
            w.beach = p(f[5]);
            w.shedAt = p(f[6]);
            Direction d = Direction.byName(f[7]);
            w.shedBack = d == null || d.getAxis().isVertical() ? Direction.NORTH : d;
            w.monument = p(f[8]);
            w.found = Long.parseLong(f[9]);
            w.monumentLooked = Long.parseLong(f[10]);
            list(f[11], w.bed);
            list(f[12], w.wild);
            return w;
        } catch (RuntimeException e) {
            LOG.warn("[MCA-DIVER] a damaged waterside in the books: {}", e.toString());
            return null;
        }
    }

    private static final String[] BOOK_KEYS = { "planted", "harvests", "kelp", "dried", "blocks", "clay", "sand", "gravel", "seagrass",
        "pickles", "lights", "scutes", "helmets", "rescued", "bred", "hatched", "prismarine", "boats", "flotsam", "raids", "surfaced" };

    private static long[] bookValues(Waterside w) {
        return new long[]{ w.planted, w.harvests, w.kelp, w.dried, w.blocks, w.clay, w.sand, w.gravel, w.seagrass, w.pickles, w.lights,
            w.scutes, w.helmets, w.rescued, w.bred, w.hatched, w.prismarine, w.boats, w.flotsam, w.raids, w.surfaced };
    }

    private static String books(Waterside w) {
        long[] v = bookValues(w);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    private static void readBooks(Waterside w, @Nullable String s) {
        if (s == null || s.isEmpty()) return;
        String[] f = s.split(",");
        long[] v = new long[BOOK_KEYS.length];
        for (int i = 0; i < Math.min(f.length, v.length); i++) {
            try { v[i] = Long.parseLong(f[i].trim()); } catch (NumberFormatException ignored) { }
        }
        w.planted = v[0]; w.harvests = v[1]; w.kelp = v[2]; w.dried = v[3]; w.blocks = v[4]; w.clay = v[5]; w.sand = v[6];
        w.gravel = v[7]; w.seagrass = v[8]; w.pickles = v[9]; w.lights = v[10]; w.scutes = v[11]; w.helmets = v[12];
        w.rescued = v[13]; w.bred = v[14]; w.hatched = v[15]; w.prismarine = v[16]; w.boats = v[17]; w.flotsam = v[18];
        w.raids = v[19]; w.surfaced = v[20];
    }

    /** The day's tally for the gazette: so many of a thing today (kelp blocks, clay, a rescue...). */
    static void today(ServerLevel level, UUID village, String what, int n) {
        if (n <= 0) return;
        long day = level.getDayTime() / 24000L;
        String key = "diver/" + day;
        Map<String, Integer> m = parseDay(Ledger.note(village, key));
        m.merge(what, n, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Ledger.note(village, key, sb.toString());
        Ledger.forget(village, "diver/" + (day - 8));
    }

    static Map<String, Integer> parseDay(@Nullable String s) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try { m.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1))); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    // ------------------------------------------------------------------ who keeps a diver

    /** Has the town water to dive in (Villages: the trade has its ground)? */
    public static boolean ready(@Nullable UUID village) {
        return water(village) != null;
    }

    /** How many divers the town wants: none with no water or under fifteen, one, two at fifty. */
    public static int wanted(@Nullable UUID village) {
        if (village == null || !ready(village)) return 0;
        int n = Villages.headcount(village);
        if (n < FROM) return 0;
        return n >= SECOND_AT ? MOST : 1;
    }

    /** The trade's share of the town, for Villages: what it wants, whole. */
    public static double team(@Nullable UUID village) {
        return wanted(village);
    }

    /** The town's divers. */
    public static List<VillageFolkEntity> divers(@Nullable UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.stationTask() == StationTask.DIVER) out.add(f);
        }
        return out;
    }

    /** The diver's shed, once it stands. */
    @Nullable
    public static Ledger.Building shedOf(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(SHED)) return b;
        return null;
    }

    /** Does the town want its diver's shed put up: a diver at work, a place for it on the bank, and none standing? */
    public static boolean shedWanted(@Nullable UUID village) {
        if (village == null || shedOf(village) != null) return false;
        Waterside w = water(village);
        return w != null && w.shedAt != null && wanted(village) > 0 && !divers(village).isEmpty();
    }

    /** Why the shed (the builders' list). */
    public static String shedWhy(@Nullable UUID village) {
        Waterside w = water(village);
        return "a diver's shed on the bank of " + (w == null ? "the water" : w.kind) + ": a smoker and a campfire to dry the kelp, a"
            + " bench to pack it into blocks for the furnaces, and a barrel for the diving gear";
    }

    /** Where the shed goes (Villages.siteFor): the dry spot on the bank, its door to the water. Null if there is none. */
    @Nullable
    public static Villages.Site shedSite(ServerLevel level, UUID village) {
        Waterside w = water(village);
        if (w == null) return null;
        if (w.shedAt == null) {
            shedPlace(level, w, village);
            if (w.shedAt != null) save(village, w);
        }
        return w.shedAt == null ? null : new Villages.Site(w.shedAt, w.shedBack, 0);
    }

    // ------------------------------------------------------------------ the town's day

    /** Every ten seconds or so for each village (from its folk's rounds): the water found, a diver taken on, the kit. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LAST.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        LAST.put(id, now);
        int folk = Villages.headcount(id);
        Waterside w = water(id);
        if (w == null) {
            if (folk < FROM - 3) return;                                  // not near big enough to want one yet
            Long looked = SURVEYED.get(id);
            if (looked != null && now - looked < SURVEY_EVERY && now >= looked) return;
            SURVEYED.put(id, now);
            survey(level, v);
            return;
        }
        long day = level.getDayTime() / 24000L;
        // Every few days, the wild kelp counted again, the beach and the shed's place looked for if there were none.
        if (day - w.found >= 3 && Land.areaLoaded(level, w.middle, WORK_REACH)) {
            w.found = day;
            KelpBeds.findWild(level, w);
            if (w.beach == null) w.beach = beach(level, w);
            if (w.shedAt == null && shedOf(id) == null) shedPlace(level, w, id);
            save(id, w);
        }
        if (divers(id).size() < wanted(id)) appoint(level, v);
        TurtleBeach.issueHelmets(level, v, w);
        DiverRaids.tick(level, v, w);
        KelpBeds.keepShed(level, v);
    }

    // ------------------------------------------------------------------ the water

    /**
     * Look round the town for water to dive in: the nearest open water three deep or more within reach of the town's
     * edge, that is a river, a lake or the sea and not a pond (most of a square twelve across round it is water), with a
     * bank to go in from. Null (and the reason kept for the board) if there is none.
     */
    @Nullable
    static Waterside survey(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int reach = Math.min(112, Villages.townReach(id) + WATER_REACH);
        long day = level.getDayTime() / 24000L;
        List<int[]> wet = new ArrayList<>();
        int loaded = 0, samples = 0;
        for (int dx = -reach; dx <= reach; dx += 4) {
            for (int dz = -reach; dz <= reach; dz += 4) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                samples++;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                loaded++;
                Roads.Ground g = Roads.ground(level, x, z);
                if (g == null || !g.water()) continue;
                wet.add(new int[]{ x, g.y(), z, dx * dx + dz * dz });
            }
        }
        wet.sort((a, b) -> Integer.compare(a[3], b[3]));
        for (int[] c : wet) {
            BlockPos top = new BlockPos(c[0], c[1], c[2]);
            int depth = depth(level, top);
            if (depth < DEEP) continue;
            if (openWater(level, top, 6, 2) < 30) continue;                 // a pond, or the edge of something
            BlockPos bank = bank(level, top);
            if (bank == null) continue;
            Waterside w = new Waterside();
            w.middle = top;
            w.surface = top.getY();
            w.bank = bank;
            w.kind = kindOf(level, top);
            w.found = day;
            w.beach = beach(level, w);
            shedPlace(level, w, id);
            KelpBeds.findWild(level, w);
            save(id, w);
            DRY.remove(id);
            Ledger.forget(id, "diver.dry");
            Villages.tell(id, day, capital(w.kind) + " by the town is " + depth + " deep at " + top.getX() + ", " + top.getZ()
                + ": deep enough for a kelp bed, and a diver to keep it");
            LOG.info("[MCA-DIVER] {}: diving water at {} ({}, {} deep), bank {}, beach {}, shed {}", Villages.name(id), top.toShortString(),
                w.kind, depth, bank.toShortString(), w.beach == null ? "none" : w.beach.toShortString(), w.shedAt == null ? "none" : w.shedAt.toShortString());
            return w;
        }
        // Nothing: why, for the board. A town whose ground has not all come in yet looks again soon.
        String why = loaded < samples / 2 ? "the land round the town is not all known yet"
            : wet.isEmpty() ? "no river, lake or sea within " + reach + " blocks of the square"
            : "the water near the town is too shallow, or only a pond: a kelp bed wants open water three deep";
        DRY.put(id, why);
        Ledger.note(id, "diver.dry", why);
        return null;
    }

    /** How deep the water is under this surface block (the surface counted). */
    static int depth(ServerLevel level, BlockPos top) {
        int n = 0;
        BlockPos q = top;
        while (n < 40 && level.getFluidState(q).is(FluidTags.WATER)) {
            n++;
            q = q.below();
        }
        return n;
    }

    /** How much of a square round here is open water at the surface (samples every {@code step} blocks). */
    static int openWater(ServerLevel level, BlockPos c, int r, int step) {
        int n = 0;
        for (int dx = -r; dx <= r; dx += step) {
            for (int dz = -r; dz <= r; dz += step) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                Roads.Ground g = Roads.ground(level, x, z);
                if (g != null && g.water()) n++;
            }
        }
        return n;
    }

    /**
     * "the sea", "the river", "the lake": what the water is, by the land the game made round it; and where the land
     * does not say, by its shape: a river runs long one way and narrow the other, a lake is about as broad as long.
     */
    static String kindOf(ServerLevel level, BlockPos p) {
        var biome = level.getBiome(p);
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_BEACH)) return "the sea";
        if (biome.is(BiomeTags.IS_RIVER)) return "the river";
        int across = run(level, p, Direction.EAST) + run(level, p, Direction.WEST) + 1;
        int along = run(level, p, Direction.NORTH) + run(level, p, Direction.SOUTH) + 1;
        int narrow = Math.min(across, along), wide = Math.max(across, along);
        return narrow <= 16 && wide >= narrow * 3 ? "the river" : "the lake";
    }

    /** How far the open water runs from here this way, at the surface (forty-eight at the most). */
    private static int run(ServerLevel level, BlockPos p, Direction d) {
        int n = 0;
        for (int k = 1; k <= 48; k++) {
            int x = p.getX() + d.getStepX() * k, z = p.getZ() + d.getStepZ() * k;
            if (!level.hasChunk(x >> 4, z >> 4)) break;
            Roads.Ground g = Roads.ground(level, x, z);
            if (g == null || !g.water() || Math.abs(g.y() - p.getY()) > 1) break;
            n++;
        }
        return n;
    }

    /** The bank nearest a spot in the water (as from the surface over it), or the diving water's own. */
    static BlockPos bankNear(ServerLevel level, BlockPos p, Waterside w) {
        BlockPos top = KelpBeds.surfaceAt(level, p.getX(), p.getZ());
        BlockPos b = bank(level, top != null ? top : p);
        return b != null ? b : w.bank;
    }

    /** Where to go in: the nearest dry ground beside the water to this spot, a block a folk can stand on. */
    @Nullable
    static BlockPos bank(ServerLevel level, BlockPos near) {
        for (int r = 1; r <= 14; r++) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos feet = standable(level, near.getX() + dx, near.getZ() + dz);
                    if (feet == null || feet.getY() > near.getY() + 3 || feet.getY() < near.getY()) continue;
                    if (!besideWater(level, feet)) continue;
                    double d = feet.distSqr(near);
                    if (d < bestD) { bestD = d; best = feet; }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** The feet of somebody stood on the ground in this column: dry, solid under it, room for its head. Or null. */
    @Nullable
    static BlockPos standable(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        int y = com.jrpetty.mcassistant.entity.goal.BuildGoal.groundTop(level, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        BlockState under = level.getBlockState(feet.below());
        if (!under.getFluidState().isEmpty() || !under.isFaceSturdy(level, feet.below(), Direction.UP)) return null;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() || !level.getFluidState(feet).isEmpty()) return null;
        if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return null;
        return feet;
    }

    /** Is there water beside these feet, at them or a block below? */
    static boolean besideWater(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            if (level.getFluidState(n).is(FluidTags.WATER) || level.getFluidState(n.below()).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** The water beside these feet (a block below them as often as not), or null. */
    @Nullable
    static BlockPos waterBeside(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            if (level.getFluidState(n).is(FluidTags.WATER)) return n;
            if (level.getFluidState(n.below()).is(FluidTags.WATER)) return n.below();
        }
        return null;
    }

    /**
     * A sandy beach by the water for the turtles: sand open to the sky, water within three blocks of it, as much sand
     * round it as can be had, within twenty-four blocks of the diving water. Null if there is none.
     */
    @Nullable
    static BlockPos beach(ServerLevel level, Waterside w) {
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int dx = -24; dx <= 24; dx += 2) {
            for (int dz = -24; dz <= 24; dz += 2) {
                BlockPos feet = standable(level, w.middle.getX() + dx, w.middle.getZ() + dz);
                if (feet == null || !level.getBlockState(feet.below()).is(BlockTags.SAND)) continue;
                if (!level.canSeeSky(feet)) continue;
                boolean near = false;
                for (int r = 1; r <= 3 && !near; r++) {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        if (level.getFluidState(feet.relative(d, r).below()).is(FluidTags.WATER)) { near = true; break; }
                    }
                }
                if (!near) continue;
                int sand = 0;
                for (int ax = -2; ax <= 2; ax++) {
                    for (int az = -2; az <= 2; az++) {
                        if (level.getBlockState(feet.offset(ax, -1, az)).is(BlockTags.SAND)) sand++;
                    }
                }
                int score = sand * 4 - (Math.abs(dx) + Math.abs(dz)) / 4 - (feet.distSqr(w.bank) < 25 ? 20 : 0);
                if (score > bestScore) { bestScore = score; best = feet; }
            }
        }
        return best;
    }

    /**
     * The shed's place: dry, level ground (a block of rise at the most) a few blocks back from the bank, with room for
     * the drawing and a block round it, clear of the beach and of anything the town has built; its door toward the water.
     */
    static void shedPlace(ServerLevel level, Waterside w, UUID village) {
        int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(SHED);
        int h = Math.max(half[0], half[1]) + 1;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 2; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = w.bank.getX() + dx, z = w.bank.getZ() + dz;
                    BlockPos c = standable(level, x, z);
                    if (c == null || Math.abs(c.getY() - w.bank.getY()) > 2) continue;
                    if (!levelAround(level, c, h)) continue;
                    if (w.beach != null && c.distSqr(w.beach) < (h + 4) * (h + 4)) continue;
                    if (nearBuilding(village, c, h + 6)) continue;
                    double d = c.distSqr(w.bank);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
            if (best != null) break;
        }
        if (best == null) return;
        w.shedAt = best;
        // Its back away from the water: the door, at the front, looks out over it.
        Direction toWater = Direction.getNearest(w.middle.getX() - best.getX(), 0, w.middle.getZ() - best.getZ());
        w.shedBack = toWater.getAxis().isVertical() ? Direction.NORTH : toWater.getOpposite();
    }

    private static boolean levelAround(ServerLevel level, BlockPos c, int h) {
        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                BlockPos f = standable(level, c.getX() + dx, c.getZ() + dz);
                if (f == null || Math.abs(f.getY() - c.getY()) > 1) return false;
            }
        }
        return true;
    }

    private static boolean nearBuilding(UUID village, BlockPos c, int r) {
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (Math.abs(b.anchor().getX() - c.getX()) <= r && Math.abs(b.anchor().getZ() - c.getZ()) <= r) return true;
        }
        BlockPos board = VillageBoards.boardOf(village);
        return board != null && Math.abs(board.getX() - c.getX()) <= r && Math.abs(board.getZ() - c.getZ()) <= r;
    }

    // ------------------------------------------------------------------ taking one on

    /**
     * A diver taken on: one already at the trade, else a hand that can be spared: one with nothing to do, a fisher (it
     * knows the water) while the fishers are not short, or a hand from a trade over its share. Never a craft's one hand,
     * the watch, the storehouse's staff, the cave team, the scouts, the ferry or the bank.
     *
     * <p>[interviews] When the town comes to interview for its places (Interviews), the diver's place is filled through
     * it here: the hands below are the field it interviews.
     */
    @Nullable
    static VillageFolkEntity appoint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Waterside w = water(id);
        if (w == null) return null;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive()) continue;
            if (f.trip() != null || f.expedition() != null) continue;
            StationTask t = f.stationTask();
            if (t == StationTask.DIVER) continue;
            if (t.isCraft() || t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.HAUL || t == StationTask.CAVE
                || t == StationTask.SCOUT || t == StationTask.FERRY || t == StationTask.BANK) continue;
            boolean spare = t == StationTask.NONE || Villages.share(id, t) >= 0.5
                || t == StationTask.FISH && Villages.share(id, StationTask.FISH) >= 0.0;
            if (!spare) continue;
            int score = f.tradeLevel(StationTask.DIVER) * 6 + f.tradeLevel(StationTask.FISH) * 2
                + (t == StationTask.NONE ? 30 : t == StationTask.FISH ? 20 : 0)
                + (f.life().has(Social.Trait.SHY) ? 4 : 0) + (f.life().has(Social.Trait.HARDWORKING) ? 4 : 0)
                - (f.isOld() ? 25 : 0)
                - (int) Math.sqrt(f.blockPosition().distSqr(w.bank)) / 8;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (best == null) return null;
        take(level, v, w, best);
        return best;
    }

    /** This folk is a diver now: its post the bank (the shed, once it stands), its ground the water round about. */
    static void take(ServerLevel level, Villages.Village v, Waterside w, VillageFolkEntity f) {
        StationTask was = f.stationTask();
        Ledger.Building shed = shedOf(v.id());
        BlockPos post = shed != null ? shed.anchor() : w.bank;
        f.setStation(post, StationTask.DIVER);
        f.assignPlot(WorkZone.around(post, 6, WorkZone.DEFAULT_DEPTH), "The Kelp Beds");
        if (was == StationTask.DIVER) return;
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, f.displayNameCap() + " " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for")
            + " diving: the kelp beds in " + w.kind + ", and anybody in the water");
        f.persona().remember(day, "I became the town's diver", 4);
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "The water's mine, then. Kelp for the furnaces, and I'll keep an eye on the quay.",
            "A diver! I've always held my breath longer than anyone.", "Down to the bed and up again. Somebody's got to farm the kelp."));
        LOG.info("[MCA-DIVER] {} of {} took up diving (was {})", f.displayNameCap(), Villages.name(v.id()), was);
    }

    /** Leaving the trade: its turtle helmet back to the stores, its kelp and its catch with it. */
    public static void handBack(VillageFolkEntity f, String why) {
        DIVES.remove(f.getUUID());
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null) return;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        ItemStack head = f.getItemBySlot(EquipmentSlot.HEAD);
        if (head.is(Items.TURTLE_HELMET)) {
            f.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            Crafts.store(level, v, head.copy());
        }
        f.brain("handed the diving gear back (" + why + ")");
    }

    // ------------------------------------------------------------------ the diver's day

    /** What a dive is for. */
    public enum Job {
        HARVEST("cutting the kelp beds"), PLANT("planting kelp on the bed"), CLAY("digging clay off the bed"),
        SAND("bringing up sand for the glass"), GRAVEL("bringing up gravel for the flint"), SEAGRASS("cutting seagrass for the turtles"),
        PICKLES("gathering sea pickles"), LIGHTS("setting sea pickles round the quay"), RESCUE("pulling somebody out of the water"),
        TOW("bringing a boat back to its berth"), FLOTSAM("picking things out of the water by the quay"), RAID("on the monument's walls"),
        SHED("drying and packing kelp at the shed"), STORES("at the stores"), BEACH("on the turtle beach"), ORDER("diving for a player's order");

        public final String words;

        Job(String words) { this.words = words; }
    }

    enum Stage { WALK, IN, SWIM, WORK, UP, OUT, LAND, DONE }

    /** A dive: what for, the spots under the water to work, the bank in and out, the swim, and how it is going. */
    static final class Dive {
        final Job job;
        final Deque<BlockPos> spots = new ArrayDeque<>();
        BlockPos entry;
        Stage stage = Stage.WALK;
        final DiverSwim.Stroke stroke = new DiverSwim.Stroke();
        final long since;
        long stageSince;
        int worked, done, got;
        @Nullable UUID other;
        @Nullable BlockPos land;
        boolean towing;
        String said = "";
        /** Land work's own steps (the shed's, the beach's). */
        int step;

        Dive(Job job, BlockPos entry, long now) {
            this.job = job;
            this.entry = entry;
            this.since = now;
            this.stageSince = now;
        }

        void stage(Stage s, long now) {
            stage = s;
            stageSince = now;
            worked = 0;
        }
    }

    /** Is it at work in its hours: dawn to dusk, awake, not in a thunderstorm? */
    static boolean onDuty(VillageFolkEntity f, ServerLevel level) {
        Boolean t = onDutyForTests;
        if (t != null) return t;
        if (f.isSleeping() || Weather.stormy(level)) return false;
        long tod = level.getDayTime() % 24000L;
        return tod >= 300L && tod < 12500L;
    }

    /**
     * From the folk's tick, early: a diver on a dive is steered every tick (its swim, its breath, its work); a folk being
     * pulled out of the water is held. True while either holds it. And for anybody in a turtle helmet, its water
     * breathing as the game gives a player.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        // The helmet's breathing renewed every second for anybody wearing one; every tick for a diver on a dive, as the
        // game renews a player's, so it goes under with the whole ten seconds.
        if (f.tickCount % 20 == 0 || DIVES.containsKey(f.getUUID())) DiverSwim.helmet(f);
        if (TOWED.containsKey(f.getUUID())) {
            UUID by = TOWED.get(f.getUUID());
            Dive d = by == null ? null : DIVES.get(by);
            if (d != null && d.job == Job.RESCUE && f.getUUID().equals(d.other)) {
                f.getNavigation().stop();
                return true;
            }
            TOWED.remove(f.getUUID());
        }
        if (f.stationTask() != StationTask.DIVER) {
            if (DIVES.remove(f.getUUID()) != null) f.brain("no longer a diver");
            return f.stationTask() == StationTask.GUARD && DiverRaids.escort(f, level);      // out with the diver at the monument
        }
        Dive d = DIVES.get(f.getUUID());
        // Somebody in trouble in the water: everything else waits.
        if (f.tickCount % 20 == 7 && (d == null || d.job != Job.RESCUE) && onDuty(f, level)) {
            VillageFolkEntity who = inTrouble(level, f);
            if (who != null) {
                Dive r = rescue(level, f, who, d);
                if (r != null) {
                    DIVES.put(f.getUUID(), r);
                    d = r;
                }
            }
        }
        if (d == null) return false;
        boolean going;
        try {
            going = drive(f, level, d);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-DIVER] {}'s dive went wrong: {}", f.displayNameCap(), e.toString());
            going = false;
        }
        if (!going) {
            DIVES.remove(f.getUUID());
            if (d.other != null) TOWED.remove(d.other);
            f.getNavigation().stop();
        }
        return going;
    }

    /** Is this folk under the water on purpose (a diver at work below, its float held off), or being towed? */
    public static boolean underwater(AssistantEntity a) {
        if (TOWED.containsKey(a.getUUID())) return true;
        Dive d = DIVES.get(a.getUUID());
        return d != null && (d.stage == Stage.SWIM || d.stage == Stage.WORK || d.stage == Stage.UP) && !d.stroke.surfacing && !d.towing;
    }

    /** On a dive, or being pulled out: its own day waits (VillageFolkEntity.calledAway). */
    public static boolean busy(VillageFolkEntity f) {
        return DIVES.containsKey(f.getUUID()) || TOWED.containsKey(f.getUUID());
    }

    /** Is this folk in the water on a dive (not to be climbed out of the water by its own instinct)? */
    public static boolean diving(AssistantEntity a) {
        return DIVES.containsKey(a.getUUID()) || TOWED.containsKey(a.getUUID());
    }

    /** The job of the dive in hand, or null (tests, its card). */
    @Nullable
    public static Job jobOf(VillageFolkEntity f) {
        Dive d = DIVES.get(f.getUUID());
        return d == null ? null : d.job;
    }

    /**
     * The diver's work, from its station brain: the next dive, if it has none in hand. True if it is about something.
     * Its hours, and nothing when the town has no water.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        if (DIVES.containsKey(f.getUUID())) return true;
        if (Boolean.FALSE.equals(ownWorkForTests)) return false;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Waterside w = water(id);
        if (v == null || w == null) {
            f.brain("no water to dive in");
            return false;
        }
        if (!onDuty(f, level) || f.offWorkNow()) return false;
        if (!Land.areaLoaded(level, w.middle, WORK_REACH)) return false;
        Dive d = next(level, v, w, f);
        if (d == null) {
            // Nothing wanted under the water: on the bank, watching it.
            if (f.blockPosition().distSqr(w.bank) > 9 && (f.getNavigation().isDone() || f.tickCount % 60 == 0)) f.walkTo(w.bank, 0.9D);
            f.brain("on the bank, watching the water");
            return false;
        }
        DIVES.put(f.getUUID(), d);
        f.brain(d.job.words);
        return true;
    }

    /** What the diver should be about next, the most wanted first; null for nothing. */
    @Nullable
    static Dive next(ServerLevel level, Villages.Village v, Waterside w, VillageFolkEntity f) {
        UUID id = v.id();
        long now = level.getGameTime();
        // Its pack: home with it before it is full: the kelp to the shed to dry while there is room there for it,
        // else (and the rest) to the stores.
        if (KelpBeds.loaded(f)) {
            if (KelpBeds.toDry(level, f) >= 8 && KelpBeds.dryRoom(level, v, f)) return land(Job.SHED, w, f, KelpBeds.shedSpot(level, v, w), now);
            return land(Job.STORES, w, f, storesSpot(level, v, f), now);
        }
        // A player's order, first of the dives.
        Order o = ORDERS.get(f.getUUID());
        if (o != null && o.got < o.count) {
            Dive d = o.item.equals("clay") ? KelpBeds.digDive(level, w, f, Blocks.CLAY, Job.ORDER, now)
                : TurtleBeach.pickleDive(level, w, f, Job.ORDER, now);
            if (d != null) return d;
        }
        // Its kit: a turtle helmet to wear, shears for the seagrass, kelp for the bed, fuel for its smoker.
        if (KelpBeds.wantsFromStores(level, v, w, f)) return land(Job.STORES, w, f, storesSpot(level, v, f), now);
        // The shed: the smoker emptied and filled, the campfire's kelp turned, the dried kelp packed.
        if (KelpBeds.shedDue(level, v, w, f)) return land(Job.SHED, w, f, KelpBeds.shedSpot(level, v, w), now);
        // The kelp beds: cut what has grown, then plant up to the bed the town's fires want.
        Dive d = KelpBeds.harvestDive(level, v, w, f, now);
        if (d != null) return d;
        d = KelpBeds.plantDive(level, v, w, f, now);
        if (d != null) return d;
        // The turtle beach.
        d = TurtleBeach.beachWork(level, v, w, f, now);
        if (d != null) return d;
        d = TurtleBeach.seagrassDive(level, v, w, f, now);
        if (d != null) return d;
        // Clay, sand and gravel off the bed, while the town is short of them.
        if (Market.stock(level, id, s -> s.is(Items.CLAY_BALL)) < CLAY_WANTED) {
            d = KelpBeds.digDive(level, w, f, Blocks.CLAY, Job.CLAY, now);
            if (d != null) return d;
        }
        if (Market.stock(level, id, s -> s.is(Items.SAND)) < SAND_WANTED && Market.stock(level, id, s -> s.is(Items.GLASS)) < 16) {
            d = KelpBeds.digDive(level, w, f, Blocks.SAND, Job.SAND, now);
            if (d != null) return d;
        }
        if (Market.stock(level, id, s -> s.is(Items.GRAVEL)) < GRAVEL_WANTED && Market.stock(level, id, s -> s.is(Items.FLINT)) < 8) {
            d = KelpBeds.digDive(level, w, f, Blocks.GRAVEL, Job.GRAVEL, now);
            if (d != null) return d;
        }
        // The quay: its lights under the water, and the fleet's boats and anything lost over the side.
        d = TurtleBeach.lightsDive(level, v, w, f, now);
        if (d != null) return d;
        d = quayWork(level, v, w, f, now);
        if (d != null) return d;
        // The monument's walls, in the Iron Age, with the watch.
        d = DiverRaids.raidDive(level, v, w, f, now);
        if (d != null) return d;
        return null;
    }

    /** A dive with spots under the water. */
    static Dive dive(Job job, Waterside w, List<BlockPos> spots, long now) {
        Dive d = new Dive(job, w.bank, now);
        d.spots.addAll(spots);
        return d;
    }

    /** Work on land: walked to, and done there. */
    static Dive land(Job job, Waterside w, VillageFolkEntity f, @Nullable BlockPos at, long now) {
        Dive d = new Dive(job, w.bank, now);
        d.land = at != null ? at : w.bank;
        return d;
    }

    /** Where the diver stands to use the stores. */
    static BlockPos storesSpot(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return f.storesSpot(level, v.id());
    }

    // ------------------------------------------------------------------ a dive, tick by tick

    /** Ticks a piece of work under the water takes: a cut of kelp or seagrass, a dig of clay, sand or gravel. */
    static final int CUT_TICKS = 12, DIG_TICKS = 20;

    /** Run a dive a tick. False when it is over. */
    static boolean drive(VillageFolkEntity f, ServerLevel level, Dive d) {
        long now = level.getGameTime();
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Waterside w = water(id);
        if (v == null || w == null || !f.isAlive()) return false;
        if (now - d.since > LONGEST_DIVE && d.stage != Stage.UP && d.stage != Stage.OUT) {
            d.spots.clear();
            d.stage(f.isInWater() ? Stage.UP : Stage.DONE, now);
        }
        // The day over, or a storm: up and home, whatever it was about.
        if (d.job != Job.RESCUE && !onDuty(f, level) && (d.stage == Stage.SWIM || d.stage == Stage.WORK || d.stage == Stage.WALK)) {
            d.spots.clear();
            d.stage(f.isInWater() ? Stage.UP : Stage.DONE, now);
        }
        switch (d.stage) {
            case WALK -> {
                if (d.land == null && f.isInWater()) {
                    d.stage(d.spots.isEmpty() && d.other == null ? Stage.UP : Stage.SWIM, now);
                    return true;
                }
                BlockPos to = d.land != null ? d.land : d.entry;
                double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
                if (dx * dx + dz * dz <= 2.4 && Math.abs(f.getY() - to.getY()) <= 2.5) {
                    d.stage(d.land != null ? Stage.LAND : Stage.IN, now);
                    return true;
                }
                if (f.getNavigation().isDone() || now % 40 == 0) f.walkTo(to, 1.0D);
                if (now - d.stageSince > 1600) {
                    // Cannot get there: up to the bank (the folk's own way), and the next thing.
                    if (d.land == null) f.moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, f.getYRot(), 0.0F);
                    else return false;
                }
                return true;
            }
            case IN -> {
                if (f.isInWater()) {
                    d.stage(d.spots.isEmpty() && d.other == null ? Stage.UP : Stage.SWIM, now);
                    return true;
                }
                BlockPos water = waterBeside(level, d.entry);
                if (water == null) return false;
                f.getNavigation().stop();
                Vec3 at = DiverSwim.in(water);
                Vec3 step = at.subtract(f.position());
                double len = Math.sqrt(step.x * step.x + step.z * step.z);
                if (len > 0.05) f.setDeltaMovement(step.x / len * 0.16, f.getDeltaMovement().y, step.z / len * 0.16);
                if (now - d.stageSince > 60) f.moveTo(at.x, water.getY() + 0.2, at.z, f.getYRot(), 0.0F);   // a high bank: in it goes
                return true;
            }
            case SWIM -> {
                if (d.job == Job.RAID && DiverRaids.retreat(level, f)) {
                    // Hurt, or the elder guardian's curse on it: home, the guards with it.
                    d.spots.clear();
                    DiverRaids.end(level, f);
                    d.stage(Stage.UP, now);
                    return true;
                }
                if (d.job == Job.RESCUE || d.job == Job.TOW || d.job == Job.FLOTSAM && d.other != null) return toward(f, level, v, w, d, now);
                BlockPos s = d.spots.peek();
                if (s == null) {
                    d.stage(Stage.UP, now);
                    return true;
                }
                Vec3 at = workPoint(level, d.job, s);
                DiverSwim.Way way = DiverSwim.steer(f, d.stroke, at, now);
                noteBreath(w, d, way);
                if (way == DiverSwim.Way.ARRIVED) d.stage(Stage.WORK, now);
                else if (way == DiverSwim.Way.MOVING && DiverSwim.stuck(d.stroke, now, 80)) {
                    d.spots.poll();                                        // cannot get at it: the next
                    d.stroke.madeWay = now;
                }
                return true;
            }
            case WORK -> {
                BlockPos s = d.spots.peek();
                if (s == null) {
                    d.stage(Stage.UP, now);
                    return true;
                }
                Vec3 at = workPoint(level, d.job, s);
                DiverSwim.Way way = DiverSwim.hold(f, d.stroke, at, now);
                noteBreath(w, d, way);
                if (way == DiverSwim.Way.SURFACING || way == DiverSwim.Way.BREATHING) return true;   // up for air; back to it after
                f.getLookControl().setLookAt(s.getX() + 0.5, s.getY() + 0.5, s.getZ() + 0.5);
                if (d.worked % 5 == 0) f.swing(InteractionHand.MAIN_HAND);
                if (++d.worked < workTicks(d.job)) return true;
                act(level, v, w, f, d, s);
                d.spots.poll();
                d.worked = 0;
                if (KelpBeds.loaded(f)) d.spots.clear();                 // a full pack: home with it
                d.stage(d.spots.isEmpty() ? Stage.UP : Stage.SWIM, now);
                return true;
            }
            case UP -> {
                if (!f.isInWater() && f.onGround()) {
                    d.stage(Stage.OUT, now);
                    return true;
                }
                BlockPos exit = waterBeside(level, d.entry);
                Vec3 to = exit != null ? DiverSwim.surfaceOver(level, exit) : DiverSwim.surfaceOver(level, f.blockPosition());
                DiverSwim.Way way = DiverSwim.steer(f, d.stroke, to, now);
                noteBreath(w, d, way);
                if (d.towing) tow(level, f, d);
                double hx = f.getX() - (d.entry.getX() + 0.5), hz = f.getZ() - (d.entry.getZ() + 0.5);
                if (way == DiverSwim.Way.ARRIVED || hx * hx + hz * hz < 2.6 || DiverSwim.stuck(d.stroke, now, 160)) d.stage(Stage.OUT, now);
                return true;
            }
            case OUT -> {
                if (d.towing) tow(level, f, d);
                if (!f.isInWater() && f.onGround()) {
                    finish(level, v, w, f, d);
                    return false;
                }
                // Up onto the bank: a pull and a step, as a swimmer climbs out.
                double dx = d.entry.getX() + 0.5 - f.getX(), dz = d.entry.getZ() + 0.5 - f.getZ();
                if (d.worked++ % 10 == 0) f.setDeltaMovement(dx * 0.25, f.isInWater() ? 0.42 : f.getDeltaMovement().y, dz * 0.25);
                if (now - d.stageSince > 100) {
                    // A bank too steep to climb: it hauls itself out where it went in.
                    f.moveTo(d.entry.getX() + 0.5, d.entry.getY(), d.entry.getZ() + 0.5, f.getYRot(), 0.0F);
                    finish(level, v, w, f, d);
                    return false;
                }
                return true;
            }
            case LAND -> {
                boolean more = switch (d.job) {
                    case SHED -> KelpBeds.atShed(level, v, w, f, d);
                    case STORES -> KelpBeds.atStores(level, v, w, f, d);
                    case BEACH -> TurtleBeach.atBeach(level, v, w, f, d);
                    default -> false;
                };
                if (!more) finish(level, v, w, f, d);
                return more;
            }
            case DONE -> {
                finish(level, v, w, f, d);
                return false;
            }
        }
        return false;
    }

    private static void noteBreath(Waterside w, Dive d, DiverSwim.Way way) {
        if (way == DiverSwim.Way.SURFACING && d.stroke.breaths > d.done && d.stroke.breaths > 0) {
            d.done = d.stroke.breaths;
            w.surfaced++;
        }
    }

    static int workTicks(Job job) {
        return switch (job) {
            case CLAY, SAND, GRAVEL, ORDER -> DIG_TICKS;
            case RAID -> DiverRaids.BREAK_TICKS;
            default -> CUT_TICKS;
        };
    }

    /** Where the diver holds itself to work a spot: in the water by it (over a block it digs; in the kelp it cuts). */
    static Vec3 workPoint(ServerLevel level, Job job, BlockPos s) {
        return switch (job) {
            case HARVEST -> DiverSwim.in(s.above());
            case PLANT, LIGHTS -> DiverSwim.in(s.above());
            case SEAGRASS, PICKLES -> DiverSwim.in(s);
            default -> DiverSwim.in(standFor(level, s));
        };
    }

    /** The water a diver works a block from: over it, else beside it. */
    static BlockPos standFor(ServerLevel level, BlockPos b) {
        if (level.getFluidState(b.above()).is(FluidTags.WATER)) return b.above();
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(b.relative(d)).is(FluidTags.WATER)) return b.relative(d);
        return b.above();
    }

    /** The piece of work at a spot, done. */
    static void act(ServerLevel level, Villages.Village v, Waterside w, VillageFolkEntity f, Dive d, BlockPos s) {
        switch (d.job) {
            case HARVEST -> d.got += KelpBeds.harvest(level, w, f, s);
            case PLANT -> d.got += KelpBeds.plant(level, w, f, s) ? 1 : 0;
            case CLAY, SAND, GRAVEL -> d.got += KelpBeds.dig(level, w, f, s);
            case ORDER -> {
                Order o = ORDERS.get(f.getUUID());
                int got = level.getBlockState(s).is(Blocks.SEA_PICKLE) ? TurtleBeach.pick(level, w, f, s) : KelpBeds.dig(level, w, f, s);
                d.got += got;
                if (o != null) o.collect(f);
            }
            case SEAGRASS -> d.got += TurtleBeach.cut(level, w, f, s);
            case PICKLES -> d.got += TurtleBeach.pick(level, w, f, s);
            case LIGHTS -> d.got += TurtleBeach.light(level, v, w, f, s) ? 1 : 0;
            case RAID -> d.got += DiverRaids.breakWall(level, v, w, f, d, s);
            default -> { }
        }
    }

    /** The dive over: its word, its books. */
    static void finish(ServerLevel level, Villages.Village v, Waterside w, VillageFolkEntity f, Dive d) {
        if (d.towing && d.other != null) landed(level, v, w, f, d);
        if (d.other != null) TOWED.remove(d.other);
        if (d.job == Job.RAID) DiverRaids.end(level, f);                       // the guards back to the watch
        String said = switch (d.job) {
            case HARVEST -> d.got > 0 ? "cut " + d.got + " kelp off the beds" : "";
            case PLANT -> d.got > 0 ? "planted " + d.got + " kelp on the bed" : "";
            case CLAY -> d.got > 0 ? "dug " + d.got + " clay off the bed" : "";
            case SAND -> d.got > 0 ? "brought up " + d.got + " sand" : "";
            case GRAVEL -> d.got > 0 ? "brought up " + d.got + " gravel" : "";
            case SEAGRASS -> d.got > 0 ? "cut " + d.got + " seagrass for the turtles" : "";
            case PICKLES -> d.got > 0 ? "gathered " + d.got + " sea pickles" : "";
            case LIGHTS -> d.got > 0 ? "set " + d.got + " clusters of sea pickles glowing round the quay" : "";
            case TOW -> d.got > 0 ? "brought a drifting boat back to its berth" : "";
            case FLOTSAM -> d.got > 0 ? "picked " + d.got + " things out of the water by the quay" : "";
            case RAID -> d.got > 0 ? "brought " + d.got + " prismarine off the monument's walls" : "";
            default -> d.said;
        };
        if (!said.isEmpty()) LAST_DONE.put(f.getUUID(), said);
        save(v.id(), w);
        f.getNavigation().stop();
    }

    // ------------------------------------------------------------------ rescue

    /**
     * Somebody of the town in trouble in the water near the diver: its head under with its breath running out, or in
     * the water a while and getting nowhere. Not a diver at work, not one in a boat, not the fleet's crew.
     */
    @Nullable
    static VillageFolkEntity inTrouble(ServerLevel level, VillageFolkEntity diver) {
        UUID id = diver.ownerId();
        if (id == null) return null;
        long now = level.getGameTime();
        VillageFolkEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f == diver || !f.isAlive()) continue;
            double dist = f.distanceToSqr(diver);
            if (dist > RESCUE_REACH * RESCUE_REACH) {
                WET.remove(f.getUUID());
                continue;
            }
            if (!outOfItsDepth(level, f) || DIVES.containsKey(f.getUUID()) || TOWED.containsKey(f.getUUID())) {
                WET.remove(f.getUUID());
                continue;
            }
            double[] wet = WET.computeIfAbsent(f.getUUID(), k -> new double[]{ now, f.getX(), f.getZ() });
            double moved = (f.getX() - wet[1]) * (f.getX() - wet[1]) + (f.getZ() - wet[2]) * (f.getZ() - wet[2]);
            if (moved > 6.0 * 6.0) {                                         // making way across: not in trouble yet
                wet[0] = now;
                wet[1] = f.getX();
                wet[2] = f.getZ();
            }
            boolean drowning = f.isEyeInFluid(FluidTags.WATER) && f.getAirSupply() < 200 && !DiverSwim.breathes(f);
            boolean stuck = now - (long) wet[0] >= WET_FOR;
            if (!drowning && !stuck) continue;
            for (VillageFolkEntity other : divers(id)) {
                Dive od = DIVES.get(other.getUUID());
                if (other != diver && od != null && od.job == Job.RESCUE && f.getUUID().equals(od.other)) { dist = Double.MAX_VALUE; break; }
            }
            if (dist < bestD) { bestD = dist; best = f; }
        }
        return best;
    }

    /**
     * In the water and out of its depth: its head under (stood on the bottom or not: a folk walking along the bed of a
     * pond is drowning as surely as one sinking), or afloat with water under it; not one wading in the shallows with its
     * head out, nor one in a boat.
     */
    static boolean outOfItsDepth(ServerLevel level, VillageFolkEntity f) {
        if (!f.isInWater() || f.isPassenger()) return false;
        if (f.isEyeInFluid(FluidTags.WATER)) return true;
        if (f.onGround()) return false;
        return level.getFluidState(BlockPos.containing(f.getX(), f.getY() - 0.6, f.getZ())).is(FluidTags.WATER);
    }

    /** A rescue: out to it, whatever the dive in hand was (that is dropped). */
    @Nullable
    static Dive rescue(ServerLevel level, VillageFolkEntity diver, VillageFolkEntity who, @Nullable Dive was) {
        Waterside w = water(diver.ownerId());
        if (w == null) return null;
        BlockPos entry = bankNear(level, who.blockPosition(), w);
        Dive d = new Dive(Job.RESCUE, entry, level.getGameTime());
        d.other = who.getUUID();
        if (diver.isInWater()) d.stage(Stage.SWIM, level.getGameTime());
        FolkTalk.speak(diver, FolkTalk.pick(level.getRandom(), "Hold on, " + who.displayNameCap() + "! I'm coming!",
            "Somebody's in the water! Coming!", "Stay up, " + who.displayNameCap() + " — I've got you!"));
        diver.brain("swimming out to " + who.displayNameCap());
        LOG.info("[MCA-DIVER] {} goes in after {} (air {}, at {})", diver.displayNameCap(), who.displayNameCap(), who.getAirSupply(),
            who.blockPosition().toShortString());
        return d;
    }

    /** Out to whoever it is after (the folk in trouble, the drifting boat, the thing in the water), and back with it. */
    private static boolean toward(VillageFolkEntity f, ServerLevel level, Villages.Village v, Waterside w, Dive d, long now) {
        Entity e = d.other == null ? null : level.getEntity(d.other);
        if (e == null || !e.isAlive()) {
            d.stage(Stage.UP, now);
            return true;
        }
        if (d.job == Job.RESCUE && e instanceof VillageFolkEntity who && !who.isInWater()) {
            WET.remove(who.getUUID());
            d.said = who.displayNameCap() + " got out of the water by itself";
            d.stage(Stage.UP, now);
            return true;
        }
        Vec3 at = d.job == Job.RESCUE ? e.position().add(0, 0.2, 0) : e.position();
        DiverSwim.Way way = DiverSwim.steer(f, d.stroke, at, now);
        noteBreath(w, d, way);
        if (f.position().distanceToSqr(at) < 1.7 * 1.7) {
            switch (d.job) {
                case RESCUE -> {
                    d.towing = true;
                    TOWED.put(e.getUUID(), f.getUUID());
                    // Back the nearest way to dry ground.
                    d.entry = bankNear(level, f.blockPosition(), w);
                    FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Got you. Lie back, I'll pull you in.", "I've got you — kick if you can!"));
                    d.stage(Stage.UP, now);
                }
                case TOW -> {
                    d.towing = true;
                    d.stage(Stage.UP, now);
                }
                case FLOTSAM -> {
                    if (e instanceof ItemEntity item) {
                        ItemStack lot = item.getItem().copy();
                        ItemStack left = f.insertItem(lot.copy());
                        int in = lot.getCount() - left.getCount();
                        if (in > 0) {
                            d.got += in;
                            w.flotsam += in;
                            if (left.isEmpty()) item.discard();
                            else item.setItem(left);
                            f.take(item, in);
                        }
                    }
                    d.other = null;
                    BlockPos nextOne = TurtleBeach.flotsamNear(level, w, f);
                    d.stage(Stage.UP, now);
                    if (nextOne != null) {
                        List<ItemEntity> more = level.getEntitiesOfClass(ItemEntity.class, new AABB(nextOne).inflate(1));
                        if (!more.isEmpty()) {
                            d.other = more.get(0).getUUID();
                            d.stage(Stage.SWIM, now);
                        }
                    }
                }
                default -> d.stage(Stage.UP, now);
            }
            return true;
        }
        if (DiverSwim.stuck(d.stroke, now, 200)) d.stage(Stage.UP, now);
        return true;
    }

    /** What it has hold of comes along with it: the folk it is pulling out (its head kept up), the boat it is towing. */
    static void tow(ServerLevel level, VillageFolkEntity f, Dive d) {
        Entity e = d.other == null ? null : level.getEntity(d.other);
        if (e == null) return;
        Vec3 back = f.getLookAngle().multiply(1, 0, 1);
        if (back.lengthSqr() < 1.0E-4) back = new Vec3(0, 0, 1);
        back = back.normalize().scale(-1.1);
        if (d.job == Job.RESCUE && e instanceof VillageFolkEntity who) {
            double y = Math.max(f.getY(), DiverSwim.surfaceOver(level, f.blockPosition()).y - 0.6);
            who.moveTo(f.getX() + back.x, y, f.getZ() + back.z, who.getYRot(), 0.0F);
            who.setDeltaMovement(Vec3.ZERO);
            who.getNavigation().stop();
        } else if (d.job == Job.TOW && e instanceof Boat b) {
            b.setPos(f.getX() + back.x * 1.4, b.getY(), f.getZ() + back.z * 1.4);
            b.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** Ashore with what it brought in: the folk set down on the bank, safe; the boat tied up at a berth. */
    static void landed(ServerLevel level, Villages.Village v, Waterside w, VillageFolkEntity f, Dive d) {
        Entity e = d.other == null ? null : level.getEntity(d.other);
        d.towing = false;
        if (e == null) return;
        long day = level.getDayTime() / 24000L;
        if (d.job == Job.RESCUE && e instanceof VillageFolkEntity who) {
            BlockPos dry = d.entry;
            who.moveTo(dry.getX() + 0.5, dry.getY(), dry.getZ() + 0.5, who.getYRot(), 0.0F);
            who.setDeltaMovement(Vec3.ZERO);
            WET.remove(who.getUUID());
            TOWED.remove(who.getUUID());
            w.rescued++;
            today(level, v.id(), "rescued", 1);
            d.said = "pulled " + who.displayNameCap() + " out of the water";
            Villages.tell(v.id(), day, f.displayNameCap() + " the diver pulled " + who.displayNameCap() + " out of " + w.kind + ", half drowned");
            who.persona().remember(day, f.displayNameCap() + " pulled me out of the water when I was drowning", 6);
            f.persona().remember(day, "I pulled " + who.displayNameCap() + " out of the water", 4);
            f.note(AssistantEntity.Deed.THINGS_MADE, 1);
            FolkTalk.speak(who, FolkTalk.pick(level.getRandom(), "Thank you, " + f.displayNameCap() + "! I thought I was done for.",
                "I'll never go near that water again. Thank you!", "You saved my life, " + f.displayNameCap() + "."));
            level.playSound(null, dry, SoundEvents.PLAYER_SPLASH, SoundSource.NEUTRAL, 0.8F, 1.0F);
            LOG.info("[MCA-DIVER] {} pulled {} out of the water at {}", f.displayNameCap(), who.displayNameCap(), dry.toShortString());
        } else if (d.job == Job.TOW && e instanceof Boat b) {
            BlockPos berth = TurtleBeach.freeBerth(level, v);
            if (berth != null) b.moveTo(berth.getX() + 0.5, berth.getY() + 0.9, berth.getZ() + 0.5, b.getYRot(), 0.0F);
            w.boats++;
            d.got++;
            today(level, v.id(), "boats", 1);
        }
    }

    // ------------------------------------------------------------------ the quay

    /** The fleet's boats in: one drifted off its berth swum out to and brought back; anything lost over the side picked up. */
    @Nullable
    static Dive quayWork(ServerLevel level, Villages.Village v, Waterside w, VillageFolkEntity f, long now) {
        Boat adrift = TurtleBeach.adrift(level, v);
        if (adrift != null) {
            // In from the bank nearest the berth it goes back to, so the tow ends at the quay.
            BlockPos berth = TurtleBeach.freeBerth(level, v);
            BlockPos in = berth == null ? null : bank(level, berth);
            Dive d = new Dive(Job.TOW, in != null ? in : w.bank, now);
            d.other = adrift.getUUID();
            return d;
        }
        BlockPos thing = TurtleBeach.flotsamNear(level, w, f);
        if (thing != null) {
            List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(thing).inflate(1));
            if (!items.isEmpty()) {
                Dive d = new Dive(Job.FLOTSAM, w.bank, now);
                d.other = items.get(0).getUUID();
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ a player's order

    /** A player's ask: so much clay or so many sea pickles, brought up for a price. */
    static final class Order {
        final UUID player;
        final String name, item;
        final int count, price;
        int got;
        final List<ItemStack> lot = new ArrayList<>();

        Order(UUID player, String name, String item, int count, int price) {
            this.player = player;
            this.name = name;
            this.item = item;
            this.count = count;
            this.price = price;
        }

        /** What it has brought up for this order, out of its pack and set aside. */
        void collect(VillageFolkEntity f) {
            Predicate<ItemStack> what = item.equals("clay") ? s -> s.is(Items.CLAY_BALL) : s -> s.is(Items.SEA_PICKLE);
            int want = count - got;
            if (want <= 0) return;
            int n = Math.min(want, f.countCarried(what));
            if (n <= 0) return;
            f.removeMatching(what, n);
            lot.add(new ItemStack(item.equals("clay") ? Items.CLAY_BALL : Items.SEA_PICKLE, n));
            got += n;
        }
    }

    /** Said to a diver: an ask for clay or pickles, or what it owes a player. */
    public static boolean meant(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean ask = t.contains("bring") || t.contains("dive") || t.contains("fetch") || t.contains("get me") || t.contains("can you get");
        return ask && (t.contains("clay") || t.contains("pickle")) || t.contains("my clay") || t.contains("my pickles")
            || t.contains("my order");
    }

    /** A diver's answer to a player: the order taken, or handed over and paid for. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Order o = ORDERS.get(f.getUUID());
        if (o != null && o.player.equals(p.getUUID())) {
            if (o.got < o.count) return "Still on the bed for your " + (o.item.equals("clay") ? "clay" : "sea pickles") + ": " + o.got + " of "
                + o.count + " so far. Come back in a while.";
            int coins = Market.coinsHeld(p);
            if (coins < o.price) return "Your " + o.item + " is here, " + o.count + " of it, " + o.price + " coins. You've " + coins + ".";
            Market.payOut(p, o.price);
            f.earn(o.price);
            for (ItemStack s : o.lot) if (!p.getInventory().add(s.copy())) p.drop(s.copy(), false);
            ORDERS.remove(f.getUUID());
            return "Here you are: " + o.count + (o.item.equals("clay") ? " clay, fresh off the bed." : " sea pickles. They glow under water.")
                + " That's " + o.price + " coins.";
        }
        if (o != null) return "I'm diving for " + o.name + " just now. Ask me again when that's done.";
        Waterside w = water(f.ownerId());
        if (w == null) return "There's no water here deep enough to dive in.";
        boolean clay = t.contains("clay");
        if (clay && !(f.level() instanceof ServerLevel level && KelpBeds.hasOnBed(level, w, Blocks.CLAY))) {
            return "There's no clay on the bed round here that I've seen. Sand and gravel, mostly.";
        }
        if (!clay && !(f.level() instanceof ServerLevel level2 && TurtleBeach.hasPickles(level2, w))) {
            return "Sea pickles grow in warm seas, and there are none in " + w.kind + " here.";
        }
        int count = clay ? 16 : 4;
        int price = Math.max(2, (int) Math.round(Prices.each(clay ? Items.CLAY_BALL : Items.SEA_PICKLE) * count * 1.5));
        ORDERS.put(f.getUUID(), new Order(p.getUUID(), p.getName().getString(), clay ? "clay" : "pickles", count, price));
        return clay ? "Sixteen clay off the bed? I'll go down for it. " + price + " coins when you come back for it."
            : "Four sea pickles? If there are any down there, I'll bring them up. " + price + " coins.";
    }

    // ------------------------------------------------------------------ what the town sees

    /** What the diver is doing, in its own words. */
    static String doing(VillageFolkEntity f, RandomSource r) {
        Waterside w = water(f.ownerId());
        if (w == null) return "There's no water near enough to dive in. I'll be given another trade, I expect.";
        Dive d = DIVES.get(f.getUUID());
        if (d != null) {
            if (d.job == Job.RESCUE) return "Somebody's in the water! I can't talk now!";
            if (d.stroke.surfacing) return "Just up for air. Back down in a moment.";
            return capital(d.job.words) + (f.isInWater() ? ", in " + w.kind + "." : ".");
        }
        String last = LAST_DONE.get(f.getUUID());
        return FolkTalk.pick(r, "Watching the water from the bank. " + bedWords(w) + ".",
            last != null ? "I've just " + last + ". Back down soon." : "Between dives. The kelp grows while I wait.",
            "Kelp for the furnaces, so the coal can go on the torches. " + w.blocks + " blocks so far.");
    }

    private static String bedWords(Waterside w) {
        return w.bed.isEmpty() ? "No kelp bed yet" : "The bed's " + w.bed.size() + " plants";
    }

    /** Its card: the bed, the blocks, what it pulled out of the water. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.DIVER) return null;
        Waterside w = water(f.ownerId());
        if (w == null) return "No water to dive in.";
        String last = LAST_DONE.get(f.getUUID());
        StringBuilder sb = new StringBuilder(bedWords(w) + " in " + w.kind + "; " + w.blocks + " kelp blocks packed, " + w.clay + " clay up");
        if (w.rescued > 0) sb.append("; ").append(w.rescued).append(w.rescued == 1 ? " rescue" : " rescues");
        sb.append(f.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET) ? "; in its turtle helmet" : "");
        if (last != null) sb.append(". Last: ").append(last);
        return sb.append('.').toString();
    }

    /** The board's jobs line: a town with no water says why it keeps no diver. Null where there is nothing to say. */
    @Nullable
    public static String jobsLine(@Nullable UUID village) {
        if (village == null || Villages.headcount(village) < FROM) return null;
        if (water(village) != null) return null;
        String why = DRY.get(village);
        if (why == null) why = Ledger.note(village, "diver.dry");
        if (why == null || why.isEmpty()) return null;
        return "No diver: " + why + ".";
    }

    /** The board's foot: the kelp beds and what they kept, the clay, the rescues. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Waterside w = water(village);
        if (w == null || divers(village).isEmpty()) return null;
        StringBuilder sb = new StringBuilder("The water: " + bedWords(w).toLowerCase(Locale.ROOT) + " in " + w.kind + ", " + w.blocks
            + " kelp blocks packed for the fires");
        String kept = FuelBook.keptLine(level, village);
        if (kept != null) sb.append("; ").append(kept);
        if (w.clay > 0) sb.append("; ").append(w.clay).append(" clay up from the bed");
        if (w.rescued > 0) sb.append("; ").append(w.rescued).append(w.rescued == 1 ? " folk" : " folk").append(" pulled out of the water");
        if (Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal() && w.monumentLooked >= 0) {
            sb.append(w.monument == null ? "; no ocean monument in reach: the town does without prismarine" : "; an ocean monument "
                + (int) Math.sqrt(w.monument.distSqr(w.middle)) + " blocks out");
        }
        return sb.append('.').toString();
    }

    /** The gazette: yesterday at the waterside. */
    @Nullable
    public static String gazette(UUID village, long day) {
        Map<String, Integer> m = parseDay(Ledger.note(village, "diver/" + (day - 1)));
        if (m.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        if (m.getOrDefault("blocks", 0) > 0) parts.add(m.get("blocks") + " kelp blocks packed for the furnaces (as good as "
            + FuelBook.coalKept(m.get("blocks")) + " coal)");
        if (m.getOrDefault("kelp", 0) > 0) parts.add(m.get("kelp") + " kelp cut off the beds");
        if (m.getOrDefault("clay", 0) > 0) parts.add(m.get("clay") + " clay brought up for the masons");
        if (m.getOrDefault("scutes", 0) > 0) parts.add(m.get("scutes") + " turtle scutes off the beach");
        if (m.getOrDefault("hatched", 0) > 0) parts.add(m.get("hatched") + " turtles hatched on the beach");
        if (m.getOrDefault("prismarine", 0) > 0) parts.add(m.get("prismarine") + " prismarine off the monument");
        if (m.getOrDefault("boats", 0) > 0) parts.add(m.get("boats") + " drifting boat" + (m.get("boats") == 1 ? "" : "s") + " brought in");
        String s = parts.isEmpty() ? "" : capital(String.join("; ", parts)) + ".";
        if (m.getOrDefault("rescued", 0) > 0) s += (s.isEmpty() ? "" : " ") + "The diver pulled " + m.get("rescued")
            + (m.get("rescued") == 1 ? " folk" : " folk") + " out of the water.";
        return s.isEmpty() ? null : "§lThe waterside§r\n" + s;
    }

    /** The trade's book's notes, out of the town's own: the beds, the fuel, the clay, the turtles, the rescues. */
    static List<String> notes(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Waterside w = water(village);
        if (w == null) return out;
        out.add("Our water is " + w.kind + ", " + depth(level, w.middle) + " deep at the middle of the beds. The bed is " + w.bed.size()
            + " plants now; we've cut " + w.kelp + " kelp off it and the wild kelp round about, and packed " + w.blocks + " blocks.");
        String kept = FuelBook.keptLine(level, village);
        if (kept != null) out.add("This month the " + kept + ". Every block on a fire is two and a half coals that go on the torches instead.");
        if (w.clay > 0) out.add("We've dug " + w.clay + " clay off the bed for the masons. Only ever dig the bed, never the bank.");
        if (w.scutes > 0 || w.bred > 0) out.add("The turtles on our beach have been bred " + w.bred + " times and given us " + w.scutes
            + " scutes. Fence the eggs and light them: a zombie will trample a nest.");
        if (w.rescued > 0) out.add("We've pulled " + w.rescued + " folk out of the water. Never let anybody struggle out there alone.");
        out.add("Go up for air before you need it, not when you do. We've come up " + w.surfaced + " times between us, and nobody has drowned.");
        return out;
    }

    /** The town's books (Annals): the waterside's numbers, for the report. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        Waterside w = water(v.id());
        out.putBoolean("water", w != null);
        String why = DRY.get(v.id());
        if (why != null) out.putString("dry", why);
        if (w == null) return out;
        out.putString("kind", w.kind);
        out.putInt("bed", w.bed.size());
        out.putInt("wild", w.wild.size());
        long[] vals = bookValues(w);
        for (int i = 0; i < BOOK_KEYS.length; i++) out.putLong(BOOK_KEYS[i], vals[i]);
        out.putString("board", String.valueOf(boardLine(level, v.id())));
        return out;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests and the stage

    public static Waterside surveyForTests(ServerLevel level, Villages.Village v) {
        return survey(level, v);
    }

    @Nullable
    public static VillageFolkEntity appointForTests(ServerLevel level, Villages.Village v) {
        return appoint(level, v);
    }

    /** Tests: this folk the town's diver, now. */
    public static void takeForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Waterside w = water(v.id());
        if (w != null) take(level, v, w, f);
    }

    /** Tests: the diver's next dive chosen now (as its station brain would), or null. */
    @Nullable
    public static Job nextForTests(VillageFolkEntity f, ServerLevel level) {
        DIVES.remove(f.getUUID());
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Waterside w = water(id);
        if (v == null || w == null) return null;
        Dive d = next(level, v, w, f);
        if (d == null) return null;
        DIVES.put(f.getUUID(), d);
        return d.job;
    }

    /** Tests: this dive set going now. */
    public static boolean startForTests(VillageFolkEntity f, ServerLevel level, Job job) {
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        Waterside w = water(id);
        if (v == null || w == null) return false;
        long now = level.getGameTime();
        Dive d = switch (job) {
            case HARVEST -> {
                KelpBeds.unpaceForTests(f);
                yield KelpBeds.harvestDive(level, v, w, f, now);
            }
            case PLANT -> KelpBeds.plantDive(level, v, w, f, now);
            case CLAY -> KelpBeds.digDive(level, w, f, Blocks.CLAY, Job.CLAY, now);
            case SAND -> KelpBeds.digDive(level, w, f, Blocks.SAND, Job.SAND, now);
            case GRAVEL -> KelpBeds.digDive(level, w, f, Blocks.GRAVEL, Job.GRAVEL, now);
            case SEAGRASS -> TurtleBeach.seagrassDive(level, v, w, f, now);
            case BEACH -> {
                TurtleBeach.unpaceForTests(f);
                yield TurtleBeach.beachWork(level, v, w, f, now);
            }
            case SHED -> land(Job.SHED, w, f, KelpBeds.shedSpot(level, v, w), now);
            case STORES -> land(Job.STORES, w, f, storesSpot(level, v, f), now);
            case RAID -> DiverRaids.raidDive(level, v, w, f, now);
            default -> null;
        };
        if (d == null) return false;
        DIVES.put(f.getUUID(), d);
        return true;
    }

    /** Tests: a dive of these spots under the water, set going now (a long one, to try the diver's breath). */
    public static void spotsForTests(VillageFolkEntity f, ServerLevel level, Job job, List<BlockPos> spots) {
        Waterside w = water(f.ownerId());
        if (w == null) return;
        DIVES.put(f.getUUID(), dive(job, w, spots, level.getGameTime()));
    }

    /** Tests: is this diver on a dive, at what stage, gone up for air how often. */
    public static String stateForTests(VillageFolkEntity f) {
        Dive d = DIVES.get(f.getUUID());
        if (d == null) return "none";
        return d.job + " " + d.stage + " spots " + d.spots.size() + " got " + d.got + " breaths " + d.stroke.breaths
            + (d.stroke.surfacing ? " surfacing" : "") + " air " + f.getAirSupply();
    }

    public static boolean towedForTests(VillageFolkEntity f) {
        return TOWED.containsKey(f.getUUID());
    }

    /** Tests: this spot of the water the town's (no survey). */
    public static Waterside setForTests(UUID village, BlockPos middle, BlockPos bank, @Nullable BlockPos beach, String kind) {
        Waterside w = new Waterside();
        w.middle = middle;
        w.bank = bank;
        w.surface = middle.getY();
        w.beach = beach;
        w.kind = kind;
        w.found = 0;
        save(village, w);
        return w;
    }

    // ------------------------------------------------------------------ /village diver

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("diver")
            .executes(Divers::cmdStatus)
            .then(Commands.literal("survey").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                Waterside w = survey(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(w == null ? "No water: " + DRY.get(v.id())
                    : "Diving water at " + w.middle.toShortString() + " (" + w.kind + "), bank " + w.bank.toShortString()), false);
                return w == null ? 0 : 1;
            }))
            .then(Commands.literal("appoint").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                VillageFolkEntity f = appoint(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(f == null ? "Nobody to spare for diving." : f.displayNameCap() + " is a diver now."), false);
                return f == null ? 0 : 1;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                String said = stage(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(said), false);
                return 1;
            }));
    }

    static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        Waterside w = water(v.id());
        List<String> lines = new ArrayList<>();
        if (w == null) {
            lines.add("No diving water" + (DRY.containsKey(v.id()) ? ": " + DRY.get(v.id()) : " found yet") + ".");
        } else {
            lines.add("Diving water: " + w.kind + " at " + w.middle.toShortString() + ", " + depth(level, w.middle) + " deep; bank "
                + w.bank.toShortString() + "; beach " + (w.beach == null ? "none" : w.beach.toShortString()) + "; shed "
                + (shedOf(v.id()) != null ? "built" : w.shedAt == null ? "no place for one" : "to go at " + w.shedAt.toShortString()));
            lines.add("Kelp bed " + w.bed.size() + " (wanted " + KelpBeds.bedWanted(level, v.id()) + "), wild kelp " + w.wild.size() + "; cut "
                + w.kelp + ", dried " + w.dried + ", packed " + w.blocks + " blocks; clay " + w.clay + ", sand " + w.sand + ", gravel " + w.gravel);
            lines.add("Turtles bred " + w.bred + ", hatched " + w.hatched + ", scutes " + w.scutes + ", helmets " + w.helmets + "; rescued "
                + w.rescued + "; boats brought in " + w.boats + "; up for air " + w.surfaced + " times; prismarine " + w.prismarine);
            for (VillageFolkEntity f : divers(v.id())) lines.add(f.displayNameCap() + ": " + stateForTests(f));
            lines.addAll(FuelBook.lines(level, v.id()));
        }
        for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return 1;
    }

    /**
     * The smoke stage (/village diver stage): a lake cut beside the town if it has no water, the waterside surveyed,
     * the shed stamped on the bank, a kelp bed planted (out of nothing, as a showcase's), a turtle beach with two
     * turtles, and a diver taken on and sent down to cut the bed.
     */
    static String stage(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Waterside w = water(id);
        if (w == null) {
            BlockPos heart = v.centre();
            int x0 = heart.getX() + Villages.townReach(id) + 6, z0 = heart.getZ() - 8;
            int y = com.jrpetty.mcassistant.entity.goal.BuildGoal.groundTop(level, x0, z0);
            for (int dx = 0; dx < 18; dx++) {
                for (int dz = 0; dz < 18; dz++) {
                    for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(x0 + dx, y - dy, z0 + dz), Blocks.WATER.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(x0 + dx, y - 7, z0 + dz), (dx + dz) % 5 == 0 ? Blocks.CLAY.defaultBlockState()
                        : Blocks.SAND.defaultBlockState(), 2);
                    for (int dy = 0; dy <= 3; dy++) level.setBlock(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            for (int dz = 2; dz < 9; dz++) for (int dx = -5; dx < 0; dx++) level.setBlock(new BlockPos(x0 + dx, y - 1, z0 + dz), Blocks.SAND.defaultBlockState(), 2);
            w = survey(level, v);
            if (w == null) return "No water to stage the diver on: " + DRY.get(id);
        }
        if (shedOf(id) == null && w.shedAt != null) {
            com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, SHED, w.shedAt, w.shedBack, 13,
                com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.SPRUCE));
            Ledger.built(id, SHED, w.shedAt, w.shedBack);
            Villages.forgetStores(id);
        }
        int planted = KelpBeds.stageBed(level, w, 10);
        TurtleBeach.stageBeach(level, w);
        VillageFolkEntity f = divers(id).isEmpty() ? appoint(level, v) : divers(id).get(0);
        if (f != null) {
            ItemStack helmet = new ItemStack(Items.TURTLE_HELMET);
            if (f.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) f.setItemSlot(EquipmentSlot.HEAD, helmet);
            f.moveTo(w.bank.getX() + 0.5, w.bank.getY(), w.bank.getZ() + 0.5, 0.0F, 0.0F);
            Dive d = KelpBeds.harvestDive(level, v, w, f, level.getGameTime());
            if (d == null) d = KelpBeds.plantDive(level, v, w, f, level.getGameTime());
            if (d != null) DIVES.put(f.getUUID(), d);
        }
        save(id, w);
        // Where to stand to see it (the smoke run's pictures): the shed from over the water, the bed from the bank, the
        // bed from under the water beside it, and the beach.
        StringBuilder views = new StringBuilder();
        if (w.shedAt != null) {
            BlockPos s = w.shedAt;
            BlockPos eye = new BlockPos((w.middle.getX() + s.getX()) / 2, w.surface + 5, (w.middle.getZ() + s.getZ()) / 2);
            views.append(view("diver-shed", eye, s.above()));
        }
        if (!w.bed.isEmpty()) {
            BlockPos root = w.bed.get(0);
            views.append(view("diver-bed", w.bank.above(4), root.above(2)));
            for (BlockPos eye : new BlockPos[]{ root.offset(3, 1, 3), root.offset(-3, 1, 3), root.offset(3, 1, -3), root.offset(-3, 1, -3) }) {
                if (level.getFluidState(eye).is(FluidTags.WATER) && level.getFluidState(eye.above()).is(FluidTags.WATER)) {
                    views.append(view("diver-under", eye, root.above(1)));
                    break;
                }
            }
        }
        if (w.beach != null) views.append(view("diver-beach", w.beach.offset(4, 4, 4), w.beach));
        return "The diver's stage: " + w.kind + " at " + w.middle.toShortString() + ", bank " + w.bank.toShortString() + ", " + planted
            + " kelp planted, beach " + (w.beach == null ? "none" : w.beach.toShortString()) + ", diver " + (f == null ? "none" : f.displayNameCap())
            + views;
    }

    /** A place to look from and the thing to look at, for the smoke run: "VIEW name x y z ax ay az". */
    private static String view(String name, BlockPos eye, BlockPos at) {
        return "\nVIEW " + name + " " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + at.getX() + " " + at.getY() + " " + at.getZ();
    }

    /** The first sea pickle state with so many pickles, waterlogged: a light under the water. */
    static BlockState pickles(int n) {
        return Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, Math.max(1, Math.min(4, n)))
            .setValue(SeaPickleBlock.WATERLOGGED, true);
    }

    /** A block's drops as a diver breaking it with what it holds, into its pack (the rest left floating there). */
    static int takeDrops(ServerLevel level, VillageFolkEntity f, BlockPos at, BlockState st, ItemStack tool) {
        List<ItemStack> drops = Block.getDrops(st, level, at, level.getBlockEntity(at), f, tool);
        level.destroyBlock(at, false, f);
        int n = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemStack lot = drop.copy();
            ItemStack left = f.insertItem(drop);
            int in = lot.getCount() - left.getCount();
            if (in > 0) {
                Economy.gathered(f, lot, in);
                n += in;
            }
            if (!left.isEmpty()) Block.popResource(level, at, left);
        }
        return n;
    }
}
