package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [fleet] The fishing fleet. A waterside town of twelve or more with a quay (the first fisher's jetty, or one run out
 * for the fleet: Waterfront) fits out a fleet of two to four boats, one to every ten folk, and its fishers crew them.
 *
 * <p><b>The boats</b> are the game's own, made by the town: a boat out of the stores, or five of its planks made into
 * one, put in the water at a berth alongside the quay by a hand on the town's works (TownJobs). Each carries the
 * town's mark, so a fleet knows its own boats wherever they float. A boat left adrift away from the berths is brought
 * in and hauled up into the stores (its boat goes back as an item), and put in again from there.
 *
 * <p><b>The day.</b> At dawn, unless the weather keeps them in (rain, a storm, the ice), it is the day of rest or the
 * bell is ringing, each fisher walks out along the quay to its boat, gets in (the one boat a folk ever boards of its
 * own accord: Aboard) and rows out over open water to a fishing ground of its own, ten to forty blocks out where the
 * water is wide, along a way charted over the water so it never runs aground. There it fishes from the boat: a line,
 * or the fleet's net if the stores have one (a haul of two to four at a time), and the fish bite quicker than they do
 * from the bank. At two in the afternoon, or with a full boat, or the moment a storm blows up at sea, it turns for
 * home, ties up at its berth, steps out onto the quay and lands the catch into the fish market's barrels
 * (FishMarket), the rest of what came up (a boot's worth of junk, a pufferfish, the odd treasure) into the stores.
 *
 * <p>Nothing is made out of nothing: every fish was caught with a rod or a net that wears as it is used, every boat
 * was planks in the stores, and every catch goes into the town's barrels, booked as the fisher's work (Economy).
 *
 * <p>It shows: on the board (where the fleet is, the catch), in the chronicle and the gazette, on a fisher's card and
 * in what it says, on the Prices page and the Auction page of the town's books, and in /village fleet.
 */
public final class Fleet {

    private Fleet() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** A town of so many, with a quay, fits out a fleet. */
    public static final int FROM = 12;
    /** Two boats at the least, four at the most: one to every ten folk. */
    public static final int FEWEST = 2, MOST = 4;
    /** The boats go out between dawn and this hour, or not that day. */
    static final long SAIL_UNTIL = 2500L;
    /** They turn for home at two in the afternoon, and are tied up by three or so. */
    static final long HOME_AT = 8000L;
    /** A hand not aboard in this long after the boats were called gives it up for the day. */
    static final long BOARD_FOR = 1800L;
    /** A hand walking its catch to the market this long lands it where it stands, near enough. */
    static final long LAND_FOR = 1200L;
    /** How far out the grounds may lie from the end of the quay, and how near. */
    static final int REACH = 40, NEAREST = 10;
    /** The boat's pace on open water (blocks a tick); slower coming alongside. */
    static final double PACE = 0.2;
    /** The town's mark on its boats. */
    static final String MARK = "mca_fleet";

    public enum Stage {
        BOARDING("walking out to the boats"), OUT("rowing out to the fishing grounds"), FISHING("fishing at sea"),
        HOME("rowing home"), LANDING("landing the catch"), ASHORE("ashore");

        public final String words;

        Stage(String words) {
            this.words = words;
        }
    }

    /** One hand's day with the fleet. Kept only while it is out: a world stopped mid-trip sees its crew step out of
     *  the boats (Aboard) and wade ashore, the catch in its pack till it banks it. */
    static final class Hand {
        final UUID folk, village;
        final String name;
        UUID boat;
        int berth;
        BlockPos ground;
        List<BlockPos> path = List.of();
        int wp;
        Stage stage = Stage.BOARDING;
        long since;
        /** Fish caught, and everything that came up, by item (what it lands). */
        int caught;
        final Map<Item, Integer> haul = new LinkedHashMap<>();
        long nextBite;
        @Nullable BlockPos bobber;
        int reel;
        double best = Double.MAX_VALUE;
        long bestAt;
        int stuck, backing;
        String why = "";
        /** It carries the fleet's net (out of the stores, and back into them). */
        boolean net;
        double far;

        Hand(UUID folk, UUID village, String name) {
            this.folk = folk;
            this.village = village;
            this.name = name;
        }
    }

    /** A town's fleet: its quay, its berths, the water about it, its crew today and the day's tally. */
    static final class Town {
        final UUID village;
        @Nullable Waterfront.Dock quay;
        long lookedForQuay = -100000L;
        @Nullable Chart chart;
        long chartDay = -1;
        final Map<UUID, Hand> crew = new LinkedHashMap<>();
        long decided = -1, cameIn = -1;
        /** Mornings in a row with no open water beyond the quay. */
        int dry;
        String today = "";
        int caught, out;
        long lastTick = -100000L, lastBoats = -100000L;

        Town(UUID village) {
            this.village = village;
        }
    }

    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();
    /** The hands at sea or on their way, by folk: what hold() looks up every tick, so it is a map of its own. */
    private static final Map<UUID, Hand> HANDS = new ConcurrentHashMap<>();
    @Nullable private static volatile String weatherForTests;
    private static volatile int fromForTests = -1;

    public static void resetForTests() {
        TOWNS.clear();
        HANDS.clear();
        weatherForTests = null;
        fromForTests = -1;
        FishMarket.resetForTests();
    }

    /** Tests: the weather the fleet reads ("rain", "storm", "clear"), or the world's own (null). */
    public static void weatherForTests(@Nullable String w) {
        weatherForTests = w;
    }

    /** Tests: the size of town that fits out a fleet (-1: the usual twelve). */
    public static void fromForTests(int n) {
        fromForTests = n;
    }

    static Town town(UUID village) {
        return TOWNS.computeIfAbsent(village, Town::new);
    }

    static int from() {
        return fromForTests >= 0 ? fromForTests : FROM;
    }

    // ------------------------------------------------------------------ who has a fleet

    /** Is this town big enough for a fleet (whether or not it has found its quay yet)? */
    public static boolean big(@Nullable UUID village) {
        return village != null && Villages.headcount(village) >= from();
    }

    /** Has this town a fleet: big enough, and a quay to sail from? */
    public static boolean has(@Nullable UUID village) {
        if (!big(village)) return false;
        Town t = TOWNS.get(village);
        return t != null && t.quay != null || quayNote(village) != null;
    }

    /** How many boats (and hands) the fleet wants: one to every ten folk, two to four. */
    public static int boatsWanted(UUID village) {
        // [perks] The Shipwrights: a boat more (and a fisher for it), whatever the town's size.
        return Math.max(FEWEST, Math.min(MOST, Villages.headcount(village) / 10)) + CityTree.extraBoats(village);
    }

    /** [fleet] The fishers a town with a fleet wants at the least (Villages.target): a hand for every boat. */
    public static int handsWanted(@Nullable UUID village) {
        return has(village) ? boatsWanted(village) : 0;
    }

    // ------------------------------------------------------------------ the quay

    @Nullable
    private static Waterfront.Dock quayNote(UUID village) {
        String s = Ledger.note(village, "fleet.quay");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            Direction d = Direction.byName(p[3]);
            if (d == null || d.getAxis().isVertical()) return null;
            return new Waterfront.Dock(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), d, Integer.parseInt(p[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void keepQuay(UUID village, Waterfront.Dock d) {
        Ledger.note(village, "fleet.quay", d.start().getX() + "," + d.start().getY() + "," + d.start().getZ() + "," + d.out().getName() + "," + d.length());
    }

    /** The fleet's quay: kept with the town once found; else a fisher's jetty; else one run out for the fleet where
     *  the town meets the water. Null for a town with no water it can sail on. */
    @Nullable
    static Waterfront.Dock quay(ServerLevel level, Villages.Village v, Town t) {
        if (t.quay != null) return t.quay;
        Waterfront.Dock kept = quayNote(v.id());
        if (kept != null) return t.quay = kept;
        long now = level.getGameTime();
        if (now - t.lookedForQuay < 1200L && now >= t.lookedForQuay) return null;
        t.lookedForQuay = now;
        // A fisher's jetty first: the nearest the heart.
        Waterfront.Dock best = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            Waterfront.Dock d = Waterfront.dockOf(v.id(), a);
            if (d == null || !level.getBlockState(d.start()).is(BlockTags.PLANKS)) continue;
            if (best != null && d.start().distSqr(v.centre()) >= best.start().distSqr(v.centre())) continue;
            if (!Land.areaLoaded(level, d.end(), 20) || chart(level, d).grounds(1).isEmpty()) continue;   // a jetty into a pond: no sea
            best = d;
        }
        if (best == null) {
            // None: a site where the town meets the water, looked for about its fishers' water and its heart.
            List<BlockPos> around = new ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a.stationTask() == AssistantEntity.StationTask.FISH && a.workZone() != null) around.add(a.workZone().center());
            }
            around.add(v.centre());
            for (BlockPos c : around) {
                if (!Land.areaLoaded(level, c, 20)) continue;
                Waterfront.Dock d = Waterfront.site(level, c, 16);
                if (d == null || chart(level, d).grounds(1).isEmpty()) continue;
                if (Waterfront.build(level, v, d, false) == 0) continue;            // no wood for it yet
                best = d;
                Villages.tell(v.id(), level.getDayTime() / 24000L, "a quay was run out over the water for the fishing fleet");
                break;
            }
        }
        if (best == null) return null;
        t.quay = best;
        keepQuay(v.id(), best);
        LOG.info("[MCA-FLEET] {} has its quay at {} heading {}", Villages.name(v.id()), best.start().toShortString(), best.out().getName());
        return best;
    }

    /** Tests and the stage: this jetty is the fleet's quay. */
    public static void quayForTests(Villages.Village v, Waterfront.Dock d) {
        Town t = town(v.id());
        t.quay = d;
        t.chart = null;
        keepQuay(v.id(), d);
    }

    /** The water alongside the quay where the boats tie up: either side of it, every other plank from the end, and
     *  off its end. */
    static List<BlockPos> berths(ServerLevel level, Waterfront.Dock q) {
        List<BlockPos> out = new ArrayList<>();
        Direction side = q.out().getClockWise();
        for (int k = q.length() - 1; k >= 1 && out.size() < MOST; k -= 2) {
            for (Direction s : new Direction[]{ side, side.getOpposite() }) {
                BlockPos w = q.start().relative(q.out(), k).relative(s);
                if (out.size() < MOST && Waterfront.water(level, w)) out.add(w.immutable());
            }
        }
        BlockPos off = q.end().relative(q.out(), 1);
        if (out.size() < MOST && Waterfront.water(level, off)) out.add(off.immutable());
        return out;
    }

    /** Where a hand stands on the quay to step into the boat at this berth: the plank beside it. */
    static BlockPos standFor(Waterfront.Dock q, BlockPos berth) {
        BlockPos best = q.start();
        for (int k = 0; k < q.length(); k++) {
            BlockPos p = q.start().relative(q.out(), k);
            if (p.distSqr(berth) < best.distSqr(berth)) best = p;
        }
        return best.above();
    }

    // ------------------------------------------------------------------ the boats

    static String mark(UUID village) {
        return MARK + "_" + village.toString().substring(0, 8);
    }

    /** The town's boats about its quay, by their mark. */
    static List<Boat> boats(ServerLevel level, UUID village, Waterfront.Dock q) {
        AABB box = new AABB(q.end()).inflate(REACH + 16, 10, REACH + 16);
        String m = mark(village);
        List<Boat> out = new ArrayList<>(level.getEntitiesOfClass(Boat.class, box, b -> b.isAlive() && b.getTags().contains(m)));
        out.sort((a, b) -> a.getStringUUID().compareTo(b.getStringUUID()));
        return out;
    }

    /**
     * Every ten seconds by day, while nobody is out in them: a boat adrift away from the berths brought in and hauled up
     * (into the stores, as a boat), and a boat put in at an empty berth till the fleet has its number — one out of the
     * stores, or five planks made into one, by a hand on the town's works.
     */
    static void keepBoats(ServerLevel level, Villages.Village v, Town t, Waterfront.Dock q, boolean free) {
        long tod = level.getDayTime() % 24000L;
        if (!free && tod > 11000L && tod < 23000L) return;
        if (!t.crew.isEmpty()) return;
        List<BlockPos> berths = berths(level, q);
        if (berths.isEmpty()) return;
        List<Boat> mine = boats(level, v.id(), q);
        for (Boat b : mine) {
            if (!b.getPassengers().isEmpty() || nearestBerth(berths, b) <= 3.5) continue;
            Crafts.store(level, v, new ItemStack(b.getDropItem()));
            b.discard();
            LOG.info("[MCA-FLEET] {}: a boat adrift at {} brought in and hauled up into the stores", Villages.name(v.id()), b.blockPosition().toShortString());
        }
        mine = boats(level, v.id(), q);
        int want = Math.min(berths.size(), boatsWanted(v.id()));
        if (mine.size() >= want) return;
        BlockPos free_ = null;
        for (BlockPos b : berths) {
            boolean taken = false;
            for (Boat boat : mine) if (flat(boat.position(), b) < 1.6) { taken = true; break; }
            if (!taken) { free_ = b; break; }
        }
        if (free_ == null) return;
        Boat.Type type = Boat.Type.SPRUCE;
        if (!free) {
            // Nothing to make one of: nobody is called down to the quay for it.
            if (Crafts.stock(level, v, s -> s.is(ItemTags.BOATS) && !s.is(ItemTags.CHEST_BOATS)) == 0
                    && Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) < 5) return;
            if (!TownJobs.atWork(level, v, "fleet", standFor(q, free_), "putting a boat in the water for the fishing fleet",
                    AssistantEntity.StationTask.FISH)) return;
            ItemStack had = Crafts.takeOne(level, v, s -> s.is(ItemTags.BOATS) && !s.is(ItemTags.CHEST_BOATS));
            if (!had.isEmpty()) type = typeOf(had.getItem());
            else if (!Crafts.usePlanks(level, v, 5)) return;
        }
        Boat boat = EntityType.BOAT.create(level);
        if (boat == null) return;
        boat.setVariant(type);
        boat.moveTo(free_.getX() + 0.5, free_.getY() + 0.9, free_.getZ() + 0.5, q.out().toYRot(), 0.0F);
        boat.addTag(MARK);
        boat.addTag(mark(v.id()));
        level.addFreshEntity(boat);
        int n = mine.size() + 1;
        if (!free) {
            Villages.tell(v.id(), level.getDayTime() / 24000L, "a boat was put in the water at the quay for the fishing fleet ("
                + n + (n == 1 ? " boat" : " boats") + " now)");
        }
        LOG.info("[MCA-FLEET] {}: boat {} of {} put in at {}", Villages.name(v.id()), n, want, free_.toShortString());
    }

    private static Boat.Type typeOf(Item it) {
        String path = BuiltInRegistries.ITEM.getKey(it).getPath().replace("_boat", "").replace("_raft", "");
        for (Boat.Type t : Boat.Type.values()) if (t.getName().equals(path)) return t;
        return Boat.Type.SPRUCE;
    }

    private static double nearestBerth(List<BlockPos> berths, Entity e) {
        double best = Double.MAX_VALUE;
        for (BlockPos b : berths) best = Math.min(best, flat(e.position(), b));
        return best;
    }

    static double flat(Vec3 at, BlockPos p) {
        double dx = at.x - (p.getX() + 0.5), dz = at.z - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Tests and the stage: the fleet's boats put in at once, for nothing. */
    public static int boatsForTests(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        Waterfront.Dock q = quay(level, v, t);
        if (q == null) return 0;
        for (int i = 0; i < MOST; i++) keepBoats(level, v, t, q, true);
        return boats(level, v.id(), q).size();
    }

    /** Tests: the town's boats. */
    public static List<Boat> boatListForTests(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        Waterfront.Dock q = quay(level, v, t);
        return q == null ? List.of() : boats(level, v.id(), q);
    }

    // ------------------------------------------------------------------ the water

    /**
     * The open water about the quay, charted once a day: which squares at the water's level a boat can float in, and
     * from each berth the cheapest way to every one of them (Dijkstra: a square with water all round it costs one, a
     * square by a bank or the quay four, so a boat keeps off the shore when it can).
     */
    static final class Chart {
        final int x0, z0, size, y;
        final boolean[] water;
        final boolean[] clear;
        final Map<Integer, int[]> trees = new ConcurrentHashMap<>();
        final List<BlockPos> berths;

        Chart(int x0, int z0, int size, int y, List<BlockPos> berths) {
            this.x0 = x0;
            this.z0 = z0;
            this.size = size;
            this.y = y;
            this.water = new boolean[size * size];
            this.clear = new boolean[size * size];
            this.berths = berths;
        }

        int index(int x, int z) {
            int i = x - x0, k = z - z0;
            return i < 0 || k < 0 || i >= size || k >= size ? -1 : k * size + i;
        }

        BlockPos cell(int idx) {
            return new BlockPos(x0 + idx % size, y, z0 + idx / size);
        }

        /** How much open water round a square, in a box eleven across: the wider the better. */
        int openness(int idx) {
            int cx = idx % size, cz = idx / size, n = 0;
            for (int dz = -5; dz <= 5; dz++) {
                for (int dx = -5; dx <= 5; dx++) {
                    int x = cx + dx, z = cz + dz;
                    if (x >= 0 && z >= 0 && x < size && z < size && water[z * size + x]) n++;
                }
            }
            return n;
        }

        /** From this berth, which way back to it from every square (-1: the berth itself, -2: not reached). */
        int[] tree(int berth) {
            return trees.computeIfAbsent(berth, b -> {
                int[] from = new int[size * size];
                int[] cost = new int[size * size];
                Arrays.fill(from, -2);
                Arrays.fill(cost, Integer.MAX_VALUE);
                if (b < 0 || b >= berths.size()) return from;
                int start = index(berths.get(b).getX(), berths.get(b).getZ());
                if (start < 0) return from;
                PriorityQueue<long[]> q = new PriorityQueue<>((p, r) -> Long.compare(p[0], r[0]));
                cost[start] = 0;
                from[start] = -1;
                q.add(new long[]{ 0, start });
                int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                while (!q.isEmpty()) {
                    long[] top = q.poll();
                    int at = (int) top[1];
                    if (top[0] > cost[at]) continue;
                    int ax = at % size, az = at / size;
                    for (int[] s : steps) {
                        int x = ax + s[0], z = az + s[1];
                        if (x < 0 || z < 0 || x >= size || z >= size) continue;
                        int n = z * size + x;
                        if (!water[n]) continue;
                        int c = cost[at] + (clear[n] ? 1 : 4);
                        if (c >= cost[n]) continue;
                        cost[n] = c;
                        from[n] = at;
                        q.add(new long[]{ c, n });
                    }
                }
                return from;
            });
        }

        /** The way from a square back to this berth, the square first, the berth last; empty if there is none. */
        List<BlockPos> wayHome(int berth, BlockPos from) {
            int[] tree = tree(berth);
            int at = nearestReached(tree, from);
            if (at < 0) return List.of();
            List<BlockPos> out = new ArrayList<>();
            int guard = 0;
            while (at >= 0 && guard++ < size * size) {
                out.add(cell(at));
                at = tree[at];
            }
            return thin(out);
        }

        /** The water square at or nearest this spot that the berth's way reaches. */
        int nearestReached(int[] tree, BlockPos p) {
            for (int r = 0; r <= 3; r++) {
                int best = -1;
                double bestD = Double.MAX_VALUE;
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int i = index(p.getX() + dx, p.getZ() + dz);
                        if (i < 0 || tree[i] == -2) continue;
                        double d = dx * dx + dz * dz;
                        if (d < bestD) { bestD = d; best = i; }
                    }
                }
                if (best >= 0) return best;
            }
            return -1;
        }

        /** Fishing grounds for so many boats: wide open water ten to forty blocks out from the quay that the first
         *  berth's way reaches, the widest and the furthest first, each well apart from the others. */
        List<BlockPos> grounds(int n) {
            List<BlockPos> out = new ArrayList<>();
            if (berths.isEmpty() || n <= 0) return out;
            int[] tree = tree(0);
            BlockPos origin = berths.get(0);
            List<int[]> scored = new ArrayList<>();
            for (int i = 0; i < size * size; i++) {
                if (tree[i] == -2 || !water[i]) continue;
                int cx = i % size, cz = i / size;
                boolean open = true;
                for (int dz = -2; dz <= 2 && open; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int x = cx + dx, z = cz + dz;
                        if (x < 0 || z < 0 || x >= size || z >= size || !water[z * size + x]) { open = false; break; }
                    }
                }
                if (!open) continue;
                BlockPos c = cell(i);
                double d = Math.sqrt(c.distSqr(origin));
                if (d < NEAREST || d > REACH) continue;
                scored.add(new int[]{ i, openness(i) * 2 + (int) Math.min(d, 28) });
            }
            scored.sort((a, b) -> Integer.compare(b[1], a[1]));
            for (int[] s : scored) {
                if (out.size() >= n) break;
                BlockPos c = cell(s[0]);
                boolean apart = true;
                for (BlockPos o : out) if (o.distSqr(c) < 8 * 8) { apart = false; break; }
                if (apart) out.add(c);
            }
            return out;
        }
    }

    /** Every other square of a way, and its ends: a boat steers for each in turn. */
    private static List<BlockPos> thin(List<BlockPos> way) {
        if (way.size() <= 2) return way;
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < way.size(); i++) if (i % 2 == 0 || i == way.size() - 1) out.add(way.get(i));
        return out;
    }

    /** Chart the water about this quay (its level the quay's), fifty blocks round its end. */
    static Chart chart(ServerLevel level, Waterfront.Dock q) {
        int r = REACH + 8;
        BlockPos c = q.end();
        Chart ch = new Chart(c.getX() - r, c.getZ() - r, 2 * r + 1, q.start().getY(), berths(level, q));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < ch.size; k++) {
            for (int i = 0; i < ch.size; i++) {
                p.set(ch.x0 + i, ch.y, ch.z0 + k);
                if (!level.hasChunkAt(p)) continue;
                if (!level.getBlockState(p).is(Blocks.WATER)) continue;
                p.move(Direction.UP);
                boolean air = level.getBlockState(p).isAir();
                if (air) ch.water[k * ch.size + i] = true;
            }
        }
        for (int k = 1; k < ch.size - 1; k++) {
            for (int i = 1; i < ch.size - 1; i++) {
                boolean all = true;
                for (int dz = -1; dz <= 1 && all; dz++) {
                    for (int dx = -1; dx <= 1; dx++) if (!ch.water[(k + dz) * ch.size + i + dx]) { all = false; break; }
                }
                ch.clear[k * ch.size + i] = all;
            }
        }
        return ch;
    }

    // ------------------------------------------------------------------ the weather

    /** What keeps the boats in today, or null: the rain, a storm, the day of rest, the bell. */
    @Nullable
    static String keptIn(ServerLevel level, UUID village, long day) {
        String w = weatherForTests;
        if (w != null) return w.equals("storm") ? "a storm"
            : w.equals("rain") && !CityTree.sailsInRain(village) ? "the rain" : null;   // the tests' weather, and only that ([perks] the Navigators')
        if (Weather.stormy(level)) return "a storm";
        if (level.isRaining() && !CityTree.sailsInRain(village)) return "the rain";   // [perks] the Navigators sail in it
        if (RestDay.today(village, day)) return "the day of rest";
        String faith = Beliefs.keptIn(level, village, day);                 // [culture2] the Sea's day: no boat goes out
        if (faith != null) return faith;
        if (Raids.underAlarm(village)) return "the bell";
        return null;
    }

    /** A storm at sea: the boats out turn for home at once. */
    static boolean stormAtSea(ServerLevel level) {
        String w = weatherForTests;
        return w != null ? w.equals("storm") : Weather.stormy(level);
    }

    // ------------------------------------------------------------------ the town's round

    /** Once a second for each town (VillageFolkEntity.aiStep): the quay, the boats, the dawn's call, the storm and the
     *  hour; and the fish market (FishMarket). */
    public static void tick(ServerLevel level, @Nullable UUID village) {
        if (village == null) return;
        Town t = town(village);
        long now = level.getGameTime();
        if (now - t.lastTick < 20L && now >= t.lastTick) return;    // every folk of the town calls: once a second is enough
        t.lastTick = now;
        Villages.Village v = Villages.get(village);
        if (v == null || !big(village)) return;
        Waterfront.Dock q = quay(level, v, t);
        if (q == null || !level.isLoaded(q.start())) return;
        FishMarket.tick(level, v, q);
        long dt = level.getDayTime(), day = dt / 24000L, tod = dt % 24000L;
        if (now - t.lastBoats >= 200L || now < t.lastBoats) {
            t.lastBoats = now;
            keepBoats(level, v, t, q, false);
        }
        if (t.decided != day && tod < SAIL_UNTIL) decide(level, v, t, q, day);
        if (t.crew.isEmpty()) return;
        boolean storm = stormAtSea(level);
        for (Hand h : new ArrayList<>(t.crew.values())) {
            Entity e = level.getEntity(h.folk);
            if (!(e instanceof VillageFolkEntity f) || !f.isAlive()) {
                if (now - h.since > 2400L) letGo(t, h, "lost");
                continue;
            }
            if ((h.stage == Stage.OUT || h.stage == Stage.FISHING) && (storm || tod >= HOME_AT)) {
                turnHome(level, t, h, f, storm ? "a storm blew up at sea" : "the afternoon");
            }
            // Past dusk, whoever is still about it lets go: out of the boat (Aboard sees to it) and home, its catch
            // banked the way any fisher's is.
            if (tod >= 12500L && tod < 23000L) letGo(t, h, "dusk");
        }
    }

    /**
     * The dawn's call: the weather read, the water charted, the crew picked (the fishers, the most practised first, a
     * boat each), each given its boat, its berth and its own ground, and sent down to the quay.
     */
    static String decide(ServerLevel level, Villages.Village v, Town t, Waterfront.Dock q, long day) {
        t.decided = day;
        UUID id = v.id();
        String why = keptIn(level, id, day);
        if (why != null) {
            t.today = "kept in by " + why;
            book(id, day, 0, 0, t.today);
            if (!why.equals("the day of rest")) Villages.tell(id, day, "the fishing boats stayed in at the quay: " + why);
            LOG.info("[MCA-FLEET] {}: the boats stay in today ({})", Villages.name(id), why);
            return t.today;
        }
        List<Boat> boats = boats(level, id, q);
        if (boats.isEmpty()) {
            t.today = "no boats yet";
            return t.today;
        }
        List<VillageFolkEntity> hands = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.FISH || !fit(f)) continue;
            hands.add(f);
        }
        hands.sort((a, b) -> Integer.compare(b.xpInTrade(AssistantEntity.StationTask.FISH), a.xpInTrade(AssistantEntity.StationTask.FISH)));
        if (hands.isEmpty()) {
            t.today = "no fishers to crew the boats";
            return t.today;
        }
        if (t.chart == null || t.chartDay != day) {
            t.chart = chart(level, q);
            t.chartDay = day;
        }
        int n = Math.min(hands.size(), boats.size());
        List<BlockPos> grounds = t.chart.grounds(n);
        if (grounds.isEmpty()) {
            t.today = "no open water beyond the quay";
            book(id, day, 0, 0, t.today);
            // A quay that has had no open water beyond it three mornings running (the water gone, built over, or never
            // more than a pond) is let go of: another is looked for.
            if (++t.dry >= 3) {
                t.dry = 0;
                t.quay = null;
                t.chart = null;
                Ledger.forget(id, "fleet.quay");
                LOG.info("[MCA-FLEET] {}: no open water beyond the quay three mornings running; looking for another", Villages.name(id));
            }
            return t.today;
        }
        t.dry = 0;
        List<BlockPos> berths = t.chart.berths;
        long now = level.getGameTime();
        int sent = 0;
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = hands.get(i);
            Boat b = boats.get(i);
            int berth = 0;
            double bd = Double.MAX_VALUE;
            for (int k = 0; k < berths.size(); k++) {
                double d = flat(b.position(), berths.get(k));
                if (d < bd) { bd = d; berth = k; }
            }
            BlockPos ground = grounds.get(i % grounds.size());
            List<BlockPos> way = t.chart.wayHome(berth, ground);
            if (way.isEmpty()) continue;
            List<BlockPos> out = new ArrayList<>(way);
            java.util.Collections.reverse(out);
            Hand h = new Hand(f.getUUID(), id, f.displayNameCap());
            h.boat = b.getUUID();
            h.berth = berth;
            h.ground = ground;
            h.path = out;
            h.since = now;
            t.crew.put(f.getUUID(), h);
            HANDS.put(f.getUUID(), h);
            if (f.peekJob() != null) f.clearQueue();
            f.getNavigation().stop();
            f.brain("down to the boats with the fishing fleet");
            if (f.getRandom().nextInt(2) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Down to the boats! The fish won't wait.", "A good morning for it. Out we go.",
                    "Calm water — we'll fill the boats today.", "Off to sea. Keep the market's barrels empty for us!"));
            }
            sent++;
        }
        t.out = sent;
        t.caught = 0;
        t.cameIn = -1;
        t.today = sent == 0 ? "nobody could get out to the boats" : "out at sea";
        if (sent > 0) {
            Villages.tell(id, day, sent + (sent == 1 ? " fishing boat went" : " fishing boats went") + " out at dawn");
            FishMarket.boatsOut(level, id, day, sent);
        }
        LOG.info("[MCA-FLEET] {}: {} boats out at dawn to grounds {}", Villages.name(id), sent, grounds);
        return t.today;
    }

    /** Fit to go out with the fleet: grown, on its feet, at home in the town and not called away elsewhere. */
    static boolean fit(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isHired() && !f.isShowcase() && f.trip() == null && f.expedition() == null
            && !Health.laidUp(f) && !Visitors.is(f) && !Nether.away(f);
    }

    // ------------------------------------------------------------------ a hand's day, tick by tick

    /** Is this folk out with the fleet just now (in a boat, or on its way to or from one)? */
    public static boolean out(VillageFolkEntity f) {
        Hand h = HANDS.get(f.getUUID());
        return h != null && h.stage != Stage.ASHORE;
    }

    /** May this folk be in this boat? Its own boat, on its day with the fleet (Aboard). */
    public static boolean crewing(VillageFolkEntity f, Entity vehicle) {
        Hand h = HANDS.get(f.getUUID());
        return h != null && h.boat != null && h.boat.equals(vehicle.getUUID())
            && (h.stage == Stage.BOARDING || h.stage == Stage.OUT || h.stage == Stage.FISHING || h.stage == Stage.HOME);
    }

    /** What it is about with the fleet, or null. */
    @Nullable
    public static Stage stageOf(VillageFolkEntity f) {
        Hand h = HANDS.get(f.getUUID());
        return h == null ? null : h.stage;
    }

    /**
     * Every tick (VillageFolkEntity.aiStep), before the storm, the gatherings and the rest of its day: a hand with the
     * fleet walks out to its boat, rows, fishes, rows home and lands its catch. True while that is its day.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Hand h = HANDS.get(f.getUUID());
        if (h == null) return false;
        Town t = TOWNS.get(h.village);
        if (t == null || t.quay == null || !h.village.equals(f.ownerId())) {
            HANDS.remove(f.getUUID());
            return false;
        }
        long now = level.getGameTime();
        long tod = level.getDayTime() % 24000L;
        if (tod >= 12500L && tod < 23000L) {
            // Past dusk and still about it (its town out of sight, so nobody called time): it lets go, and its own
            // evening takes it home; a boat it is still in, it steps out of (Aboard).
            letGo(t, h, "dusk");
            return false;
        }
        return switch (h.stage) {
            case BOARDING -> board(f, level, t, h, now);
            case OUT, HOME -> row(f, level, t, h, now);
            case FISHING -> fish(f, level, t, h, now);
            case LANDING -> landing(f, level, t, h, now);
            case ASHORE -> {
                letGo(t, h, "ashore");
                yield false;
            }
        };
    }

    @Nullable
    private static Boat boatOf(ServerLevel level, Hand h) {
        return h.boat != null && level.getEntity(h.boat) instanceof Boat b && b.isAlive() ? b : null;
    }

    /** Down the quay to its boat, a rod in hand (and the fleet's net, if the stores have one), and in. */
    private static boolean board(VillageFolkEntity f, ServerLevel level, Town t, Hand h, long now) {
        if (f.isSleeping()) return false;                              // up with the dawn first
        Boat b = boatOf(level, h);
        if (b == null) {
            letGo(t, h, "no boat");
            return false;
        }
        if (f.getVehicle() == b) {
            setOff(f, level, t, h, now);
            return true;
        }
        if (now - h.since > BOARD_FOR) {
            letGo(t, h, "never got aboard");
            FolkTalk.speak(f, "I couldn't get down to the boat. The fleet's gone without me.");
            return false;
        }
        if (f.distanceToSqr(b) <= 2.6 * 2.6) {
            // A dog or a hen that climbed into the boat while it lay at the quay (a boat takes in whatever bumps it) is
            // shooed out first.
            for (Entity p : new ArrayList<>(b.getPassengers())) if (!(p instanceof VillageFolkEntity)) p.stopRiding();
        }
        if (f.distanceToSqr(b) <= 2.6 * 2.6 && b.getPassengers().isEmpty()) {
            if (!rodInHand(f) && !(f.topUpKit() && rodInHand(f)) && !hasNet(f) && !takeNet(level, f, h)) {
                letGo(t, h, "no rod");
                FolkTalk.speak(f, "No rod and no net — I'll not catch much out there with my hands.");
                return false;
            }
            if (!h.net) takeNet(level, f, h);
            if (f.startRiding(b)) {
                setOff(f, level, t, h, now);
                return true;
            }
        }
        BlockPos stand = standFor(t.quay, t.chart != null && h.berth < t.chart.berths.size() ? t.chart.berths.get(h.berth) : b.blockPosition());
        if (f.getNavigation().isDone() || (now - h.since) % 60L == 0L) f.walkTo(stand, 1.0D);
        return true;
    }

    private static void setOff(VillageFolkEntity f, ServerLevel level, Town t, Hand h, long now) {
        h.stage = Stage.OUT;
        h.since = now;
        h.wp = 0;
        h.best = Double.MAX_VALUE;
        h.bestAt = now;
        f.getNavigation().stop();
        f.brain("rowing out to the fishing grounds");
        level.playSound(null, f.blockPosition(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    /** Rowing out to its ground, or home to its berth, along its charted way. */
    private static boolean row(VillageFolkEntity f, ServerLevel level, Town t, Hand h, long now) {
        Boat b = boatOf(level, h);
        if (b == null || f.getVehicle() != b) {
            // Out of the boat (knocked out, or it broke): ashore it goes, the way anybody would, and lands what it has.
            h.stage = Stage.LANDING;
            h.since = now;
            return true;
        }
        if (h.stage == Stage.OUT && stormAtSea(level)) turnHome(level, t, h, f, "a storm blew up at sea");
        h.far = Math.max(h.far, flat(b.position(), t.quay.end()));
        if (!steer(b, h, now)) return true;
        b.setDeltaMovement(0, b.getDeltaMovement().y, 0);
        if (h.stage == Stage.OUT) {
            h.stage = Stage.FISHING;
            h.since = now;
            h.nextBite = now + 40L;
            f.brain("fishing at sea");
            if (f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "This'll do. Deep and wide.", "Here's the spot. Lines out!",
                    "The fish are out here somewhere."));
            }
            return true;
        }
        // Home: tied up at its berth, out onto the quay.
        f.stopRiding();
        f.getNavigation().stop();
        h.stage = Stage.LANDING;
        h.since = now;
        f.brain("tied up at the quay: landing the catch");
        return true;
    }

    /**
     * Steer the boat along its way: for the next point of it, the boat turned toward it and pushed on at the fleet's pace
     * (slower coming alongside the last). Stuck (a bank, another boat), it backs off a moment and tries the next point;
     * long stuck, it is poled off to the next point. True when the boat is at the end of its way.
     */
    static boolean steer(Boat b, Hand h, long now) {
        if (h.path.isEmpty()) return true;
        while (h.wp < h.path.size() - 1 && flat(b.position(), h.path.get(h.wp)) < 1.8) {
            h.wp++;
            h.best = Double.MAX_VALUE;
            h.bestAt = now;
        }
        BlockPos to = h.path.get(h.wp);
        boolean last = h.wp >= h.path.size() - 1;
        double dx = to.getX() + 0.5 - b.getX(), dz = to.getZ() + 0.5 - b.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (last && d < (h.stage == Stage.HOME ? 1.2 : 1.6)) {
            b.setPaddleState(false, false);
            return true;
        }
        if (h.backing > 0) {
            h.backing--;
            b.setDeltaMovement(-dx / Math.max(0.01, d) * 0.12, b.getDeltaMovement().y, -dz / Math.max(0.01, d) * 0.12);
            b.setPaddleState(true, false);
            return false;
        }
        double pace = last ? Math.min(PACE, 0.05 + d * 0.06) : PACE;
        if (d > 0.01) b.setDeltaMovement(dx / d * pace, b.getDeltaMovement().y, dz / d * pace);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        b.setYRot(Mth.approachDegrees(b.getYRot(), yaw, 9.0F));
        b.setPaddleState(true, true);
        if (now % 30L == 0L) b.level().playSound(null, b.blockPosition(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.NEUTRAL, 0.4F, 1.0F);
        if (d < h.best - 0.25) {
            h.best = d;
            h.bestAt = now;
        } else if (now - h.bestAt > 60L) {
            h.stuck++;
            h.bestAt = now;
            h.best = d;
            h.backing = 10;
            if (h.stuck % 3 == 0 && h.wp < h.path.size() - 1) h.wp++;
            if (h.stuck >= 9) {
                // Long stuck on something: poled off it to the next point, an oar's length or two.
                BlockPos next = h.path.get(Math.min(h.path.size() - 1, h.wp));
                if (flat(b.position(), next) < 3.5) b.moveTo(next.getX() + 0.5, b.getY(), next.getZ() + 0.5, b.getYRot(), 0.0F);
                h.stuck = 0;
            }
        }
        return false;
    }

    /** Out at its ground: a line cast (or the net shot), the wait, the bite, the catch. */
    private static boolean fish(VillageFolkEntity f, ServerLevel level, Town t, Hand h, long now) {
        Boat b = boatOf(level, h);
        if (b == null || f.getVehicle() != b) {
            h.stage = Stage.LANDING;
            h.since = now;
            return true;
        }
        Vec3 m = b.getDeltaMovement();
        b.setDeltaMovement(m.x * 0.5, m.y, m.z * 0.5);
        h.far = Math.max(h.far, flat(b.position(), t.quay.end()));
        long tod = level.getDayTime() % 24000L;
        if (stormAtSea(level)) {
            turnHome(level, t, h, f, "a storm blew up at sea");
            return true;
        }
        if (tod >= HOME_AT && tod < 23000L) {
            turnHome(level, t, h, f, "the afternoon");
            return true;
        }
        if (!h.net && !rodInHand(f)) {
            turnHome(level, t, h, f, "a broken rod");
            return true;
        }
        if (h.bobber == null) {
            Direction side = Direction.from2DDataValue(Math.floorMod(f.getUUID().hashCode() + (int) (now / 400L), 4));
            BlockPos at = b.blockPosition().relative(side, 3);
            h.bobber = level.getBlockState(at).is(Blocks.WATER) ? at.immutable() : b.blockPosition().relative(side.getOpposite(), 3).immutable();
            h.reel = 0;
            // The bite comes quicker out here than from the bank (FishGoal's five to twenty seconds): four to fourteen,
            // a practised hand a little quicker still.
            h.nextBite = now + f.pacedTicks(80 + f.getRandom().nextInt(200), 50);
            f.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, b.blockPosition(), h.net ? SoundEvents.FISHING_BOBBER_SPLASH : SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.NEUTRAL, 0.5F, 0.4F / (f.getRandom().nextFloat() * 0.4F + 0.8F));
            level.sendParticles(ParticleTypes.SPLASH, h.bobber.getX() + 0.5, h.bobber.getY() + 1.0, h.bobber.getZ() + 0.5, 4, 0.1, 0.0, 0.1, 0.1);
            return true;
        }
        f.getLookControl().setLookAt(h.bobber.getX() + 0.5, h.bobber.getY() + 0.8, h.bobber.getZ() + 0.5);
        if (h.reel > 0) {
            if (--h.reel == 0) catchOne(level, f, t, h);
            return true;
        }
        if (now < h.nextBite) {
            if (now % 10L == 0L) {
                level.sendParticles(ParticleTypes.FISHING, h.bobber.getX() + 0.5, h.bobber.getY() + 0.95, h.bobber.getZ() + 0.5, 1, 0.05, 0.0, 0.05, 0.0);
            }
            return true;
        }
        h.reel = 8;
        level.playSound(null, h.bobber, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6F, 1.0F + (f.getRandom().nextFloat() - 0.4F) * 0.4F);
        level.sendParticles(ParticleTypes.SPLASH, h.bobber.getX() + 0.5, h.bobber.getY() + 1.0, h.bobber.getZ() + 0.5, 10, 0.15, 0.1, 0.15, 0.2);
        return true;
    }

    /**
     * What comes up: with the net a haul of two to four fish; with a line one, and one bite in four a second off the same
     * shoal. Mostly cod and salmon (more of them than the bank gives, and less junk), now and then a pufferfish or a
     * tropical fish, a little junk, and one bite in a hundred and fifty the odd treasure off the bottom.
     */
    static List<ItemStack> roll(ServerLevel level, RandomSource r, boolean net) {
        List<ItemStack> out = new ArrayList<>();
        int fish = net ? 2 + r.nextInt(3) : (r.nextInt(4) == 0 ? 2 : 1);
        for (int i = 0; i < fish; i++) {
            int roll = r.nextInt(100);
            Item it = roll < 63 ? Items.COD : roll < 92 ? Items.SALMON : roll < 96 ? Items.PUFFERFISH : Items.TROPICAL_FISH;
            out.add(new ItemStack(it));
        }
        int junk = r.nextInt(net ? 40 : 25);
        if (junk == 0) out.add(new ItemStack(Items.STRING));
        else if (junk == 1) out.add(new ItemStack(Items.BONE));
        else if (junk == 2) out.add(new ItemStack(Items.INK_SAC));
        if (r.nextInt(150) == 0) out.add(Museum.treasure(level, r));
        return out;
    }

    /** [itemaudit] A net's haul, as a boat's comes up: what a player's cast brings in (item/FishingNetItem). */
    public static List<ItemStack> netHaul(ServerLevel level, RandomSource r) {
        return roll(level, r, true);
    }

    /** [itemaudit] Tests: a haul with the net or with a line. */
    public static List<ItemStack> rollForTests(ServerLevel level, RandomSource r, boolean net) {
        return roll(level, r, net);
    }

    /** [itemaudit] Tests: the tailor's turn at the fleet's nets (Crafts.tailor), now. */
    @Nullable
    public static String makeNetForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return makeNet(level, v, f);
    }

    private static void catchOne(ServerLevel level, VillageFolkEntity f, Town t, Hand h) {
        List<ItemStack> haul = roll(level, f.getRandom(), h.net);
        Perks.moreFish(f, haul);                                      // [perks] the Great Lighthouse, a Lucky fisher
        f.swing(InteractionHand.MAIN_HAND);
        if (h.bobber != null) {
            level.playSound(null, h.bobber, SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 0.6F, 1.0F);
            double ax = f.getX() - (h.bobber.getX() + 0.5), az = f.getZ() - (h.bobber.getZ() + 0.5);
            for (ItemStack s : haul) {
                level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, s),
                    h.bobber.getX() + 0.5, h.bobber.getY() + 1.1, h.bobber.getZ() + 0.5, 0, ax * 0.08, 0.25, az * 0.08, 1.0);
            }
        }
        h.bobber = null;
        boolean full = false;
        int fish = 0;
        for (ItemStack s : haul) {
            ItemStack picked = s.copy();
            ItemStack left = f.insertItem(s);
            int got = picked.getCount() - left.getCount();
            if (got > 0) {
                Economy.gathered(f, picked, got);
                h.haul.merge(picked.getItem(), got, Integer::sum);
                if (Economy.RAW_FISH.test(picked)) fish += got;
            }
            if (!left.isEmpty()) {
                full = true;
                // No room aboard for it: back over the side it goes.
            }
        }
        h.caught += fish;
        t.caught += fish;
        if (fish > 0) {
            f.note(AssistantEntity.Deed.FISH_CAUGHT, fish);
            f.landedACatch();
        }
        if (h.net) {
            ItemStack net = netIn(f);
            if (!net.isEmpty()) {
                net.setDamageValue(net.getDamageValue() + 1);
                if (net.getDamageValue() >= net.getMaxDamage()) {
                    net.shrink(1);
                    h.net = false;
                    FolkTalk.speak(f, "The net's torn right through. That's the last haul it'll bring up.");
                }
            } else {
                h.net = false;
            }
        } else {
            ItemStack rod = f.getMainHandItem();
            if (rod.is(Items.FISHING_ROD)) rod.hurtAndBreak(1, f, EquipmentSlot.MAINHAND);
        }
        if (full || f.isPackFull()) turnHome(level, t, h, f, "a full boat");
    }

    /** Turn for home: the way back to its own berth charted from where the boat is. */
    static void turnHome(ServerLevel level, Town t, Hand h, VillageFolkEntity f, String why) {
        if (h.stage != Stage.OUT && h.stage != Stage.FISHING) return;
        Boat b = boatOf(level, h);
        h.why = why;
        h.bobber = null;
        h.stage = Stage.HOME;
        h.since = level.getGameTime();
        h.wp = 0;
        h.stuck = 0;
        h.best = Double.MAX_VALUE;
        h.bestAt = h.since;
        if (t.chart == null) t.chart = chart(level, t.quay);
        h.path = b == null ? List.of() : t.chart.wayHome(h.berth, b.blockPosition());
        if (h.path.isEmpty() && b != null && h.berth < t.chart.berths.size()) h.path = List.of(t.chart.berths.get(h.berth));
        f.brain("rowing home: " + why);
        if (why.startsWith("a storm")) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Storm coming! Pull for the quay!", "That sky's black — home, now!",
                "Lightning out over the water. We're going in."));
        } else if (f.getRandom().nextInt(2) == 0) {
            FolkTalk.speak(f, h.caught > 0 ? "That's " + h.caught + " in the boat. Home with it!" : "Nothing biting. Home we go.");
        }
    }

    /** Ashore: up the quay to the fish market, and the catch into its barrels. */
    private static boolean landing(VillageFolkEntity f, ServerLevel level, Town t, Hand h, long now) {
        if (f.isPassenger()) f.stopRiding();
        Villages.Village v = Villages.get(h.village);
        if (v == null) {
            letGo(t, h, "no town");
            return false;
        }
        BlockPos to = FishMarket.landingSpot(v.id(), t.quay);
        double d = f.blockPosition().distSqr(to);
        if (d > 2.5 * 2.5 && now - h.since < LAND_FOR) {
            if (f.getNavigation().isDone() || (now - h.since) % 60L == 0L) f.walkTo(to, 1.0D);
            return true;
        }
        if (d > 12 * 12) {
            // Never got up the quay (washed ashore somewhere else): the catch stays with it, banked the way any
            // fisher's is, and the net goes back with the rest of its things.
            letGo(t, h, "ashore elsewhere");
            return false;
        }
        f.getNavigation().stop();
        int landed = FishMarket.land(level, v, f, h.haul);
        h.haul.clear();
        if (h.net) {
            ItemStack net = netIn(f);
            if (!net.isEmpty()) {
                Crafts.store(level, v, net.copy());
                net.setCount(0);
            }
            h.net = false;
        }
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, landed > 0 ? FolkTalk.pick(f.getRandom(), landed + " fish for the market. Not a bad day.",
                "There — " + landed + " in the barrels. Fresh as you like.", "That's the catch in. " + landed + ", if you're counting.")
            : "Nothing to land today. The sea keeps what it likes.");
        f.brain("landed " + landed + " fish at the market");
        h.stage = Stage.ASHORE;
        letGo(t, h, "landed");
        return false;
    }

    private static void letGo(Town t, Hand h, String why) {
        t.crew.remove(h.folk);
        HANDS.remove(h.folk);
        if (t.crew.isEmpty() && t.out > 0 && t.cameIn < 0) {
            t.cameIn = t.decided;
            t.today = "in with " + t.caught + " fish";
            book(t.village, t.decided, t.out, t.caught, "");
            Villages.tell(t.village, t.decided, "the fishing fleet came in with " + t.caught + " fish");
        }
        LOG.info("[MCA-FLEET] {} done with the fleet for the day ({}): {} caught", h.name, why, h.caught);
    }

    // ------------------------------------------------------------------ rods and nets

    private static boolean rodInHand(VillageFolkEntity f) {
        if (f.getMainHandItem().is(Items.FISHING_ROD)) return true;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.FISHING_ROD)) {
                ItemStack old = f.getMainHandItem();
                f.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
                inv.set(i, old);
                return true;
            }
        }
        return false;
    }

    /** Is this a fishing net (the fleet's own: McAssistantMod.FISHING_NET)? */
    public static boolean isNet(ItemStack s) {
        return !s.isEmpty() && s.is(McAssistantMod.FISHING_NET.get());
    }

    private static boolean hasNet(VillageFolkEntity f) {
        return f.countMatching(Fleet::isNet) > 0;
    }

    private static ItemStack netIn(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) if (isNet(s)) return s;
        return ItemStack.EMPTY;
    }

    /** The fleet's net out of the stores into the boat (the hand's pack), if there is one. */
    private static boolean takeNet(ServerLevel level, VillageFolkEntity f, Hand h) {
        if (hasNet(f)) return h.net = true;
        Villages.Village v = Villages.get(h.village);
        if (v == null) return false;
        ItemStack net = Crafts.takeOne(level, v, Fleet::isNet);
        if (net.isEmpty()) return false;
        ItemStack left = f.insertGiven(net);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return false;
        }
        return h.net = true;
    }

    /** How many nets the fleet wants in the stores: one to a boat. */
    public static int netsWanted(@Nullable UUID village) {
        return has(village) ? boatsWanted(village) : 0;
    }

    /**
     * [fleet] The tailor's turn at the fleet's nets (Crafts.tailor): while the stores hold fewer nets than the fleet has
     * boats, one knotted out of five of the stores' string, as the recipe has it. Returns what it made, or null.
     */
    @Nullable
    static String makeNet(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        int want = netsWanted(v.id());
        if (want <= 0) return null;
        int have = Crafts.stock(level, v, Fleet::isNet);
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity o) have += o.countMatching(Fleet::isNet);
        if (have >= want || Crafts.stock(level, v, s -> s.is(Items.STRING)) < 5) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.STRING), 5)) return null;
        Crafts.store(level, v, new ItemStack(McAssistantMod.FISHING_NET.get()));
        return "a fishing net for the fleet";
    }

    // ------------------------------------------------------------------ the books

    /** The fleet's days, kept with the town (the last fortnight): "day|boats|fish|why". */
    private static void book(UUID village, long day, int boats, int fish, String why) {
        String s = Ledger.note(village, "fleet.log");
        List<String> lines = new ArrayList<>();
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        lines.removeIf(l -> l.startsWith(day + "|"));
        lines.add(day + "|" + boats + "|" + fish + "|" + why.replace('|', '/').replace('\n', ' '));
        while (lines.size() > 14) lines.remove(0);
        Ledger.note(village, "fleet.log", String.join("\n", lines));
    }

    /** The fleet's days, newest first: {day, boats, fish, why}. */
    static List<String[]> log(UUID village) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(village, "fleet.log");
        if (s == null || s.isEmpty()) return out;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split("\\|", -1);
            if (p.length >= 4) out.add(p);
        }
        return out;
    }

    /** What the fleet is doing today, in a few words. */
    public static String today(ServerLevel level, UUID village) {
        Town t = TOWNS.get(village);
        if (t == null || t.quay == null) return has(village) ? "fitting out" : "";
        long day = level.getDayTime() / 24000L;
        if (t.decided != day) return "in at the quay";
        if (!t.crew.isEmpty()) {
            int sea = 0;
            for (Hand h : t.crew.values()) if (h.stage == Stage.OUT || h.stage == Stage.FISHING || h.stage == Stage.HOME) sea++;
            return sea > 0 ? sea + (sea == 1 ? " boat" : " boats") + " out at sea, " + t.caught + " fish so far" : "landing the catch";
        }
        return t.today;
    }

    /** The board: where the fleet is, and its catch. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        if (!has(village)) return null;
        Town t = TOWNS.get(village);
        int boats = t == null || t.quay == null ? 0 : boats(level, village, t.quay).size();
        return "The fishing fleet: " + boats + (boats == 1 ? " boat" : " boats") + ", " + today(level, village) + ".";
    }

    /** The gazette: yesterday at sea. */
    @Nullable
    public static String gazette(UUID village, long day) {
        for (String[] p : log(village)) {
            long d;
            try {
                d = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (d != day - 1) continue;
            String sea = !p[3].isEmpty() ? "The boats stayed in: " + p[3].replace("kept in by ", "") + "."
                : p[1] + (p[1].equals("1") ? " boat" : " boats") + " went out at dawn and came in with " + p[2] + " fish.";
            String market = FishMarket.gazette(village, day - 1);
            return "§lThe quay§r\n" + sea + (market == null ? "" : "\n" + market);
        }
        return null;
    }

    /** Its card: out with the fleet today, and what it caught. */
    public static String cardLine(VillageFolkEntity f) {
        Hand h = HANDS.get(f.getUUID());
        if (h != null) return capital(h.stage.words) + (h.caught > 0 ? ", " + h.caught + " fish in the boat" : "") + (h.net ? " (with the net)" : "");
        if (f.stationTask() != AssistantEntity.StationTask.FISH || !has(f.ownerId())) return "";
        return "crews a boat in the fleet: out at dawn, home by the afternoon";
    }

    /** What a fisher says it is doing, with the fleet. */
    public static String doing(VillageFolkEntity f, RandomSource r, String otherwise) {
        Hand h = HANDS.get(f.getUUID());
        if (h == null) return otherwise;
        return switch (h.stage) {
            case BOARDING -> "Off down to the boats. The fleet goes out at dawn.";
            case OUT -> FolkTalk.pick(r, "Rowing out to the grounds. Mind the oar!", "Out to the deep water — that's where the cod are.");
            case FISHING -> h.caught > 0 ? "Fishing out at sea: " + h.caught + " in the boat already." : "Fishing out at sea. Nothing yet — patience.";
            case HOME -> "Rowing home" + (h.why.startsWith("a storm") ? " ahead of the storm!" : " with the catch.");
            case LANDING -> "Landing the catch at the fish market.";
            case ASHORE -> otherwise;
        };
    }

    /** The fleet for the town's books (the Auction page's panel): boats, crew, today, the fortnight. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        out.putBoolean("has", has(village));
        out.putInt("from", from());
        Town t = TOWNS.get(village);
        int boats = t == null || t.quay == null ? 0 : boats(level, village, t.quay).size();
        out.putInt("boats", boats);
        out.putInt("wanted", has(village) ? boatsWanted(village) : 0);
        out.putString("today", has(village) ? today(level, village) : "");
        ListTag crew = new ListTag();
        if (t != null) {
            for (Hand h : t.crew.values()) crew.add(StringTag.valueOf(h.name + ": " + h.stage.words + (h.caught > 0 ? ", " + h.caught + " fish" : "")));
        }
        out.put("crew", crew);
        ListTag days = new ListTag();
        for (String[] p : log(village)) {
            String line;
            try {
                long d = Long.parseLong(p[0]);
                line = "Day " + (d + 1) + ": " + (!p[3].isEmpty() ? p[3] : p[1] + " boats out, " + p[2] + " fish");
            } catch (NumberFormatException e) {
                continue;
            }
            days.add(StringTag.valueOf(line));
        }
        out.put("days", days);
        if (has(village)) out.put("market", FishMarket.report(level, village));
        return out;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the dawn's call now, whatever the hour. Returns what the fleet is doing. */
    public static String sailForTests(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        Waterfront.Dock q = quay(level, v, t);
        if (q == null) return "no quay";
        t.chart = null;
        return decide(level, v, t, q, level.getDayTime() / 24000L);
    }

    /** Tests: every hand on its way to its boat put in it now (as if it had walked down the quay). */
    public static int boardForTests(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        int n = 0;
        for (Hand h : new ArrayList<>(t.crew.values())) {
            if (h.stage != Stage.BOARDING) continue;
            if (!(level.getEntity(h.folk) instanceof VillageFolkEntity f)) continue;
            Boat b = boatOf(level, h);
            if (b == null) continue;
            BlockPos stand = standFor(t.quay, t.chart.berths.get(Math.min(h.berth, t.chart.berths.size() - 1)));
            f.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            for (Entity p : new ArrayList<>(b.getPassengers())) if (!(p instanceof VillageFolkEntity)) p.stopRiding();
            rodInHand(f);
            takeNet(level, f, h);
            if (f.startRiding(b)) {
                setOff(f, level, t, h, level.getGameTime());
                n++;
            }
        }
        return n;
    }

    /** Tests: so many bites landed now by every hand at its ground. Returns the fish caught. */
    public static int fishForTests(ServerLevel level, Villages.Village v, int bites) {
        Town t = town(v.id());
        int n = 0;
        for (Hand h : new ArrayList<>(t.crew.values())) {
            if (h.stage != Stage.FISHING || !(level.getEntity(h.folk) instanceof VillageFolkEntity f)) continue;
            int before = h.caught;
            for (int i = 0; i < bites && h.stage == Stage.FISHING; i++) catchOne(level, f, t, h);
            n += h.caught - before;
        }
        return n;
    }

    /** Tests: the hands, "name stage caught". */
    public static List<String> handsForTests(UUID village) {
        Town t = TOWNS.get(village);
        List<String> out = new ArrayList<>();
        if (t != null) for (Hand h : t.crew.values()) out.add(h.name + " " + h.stage + " " + h.caught + " far " + String.format(Locale.ROOT, "%.1f", h.far));
        return out;
    }

    /** Tests: how far out the furthest hand got, and the fish caught today. */
    public static double farForTests(UUID village) {
        Town t = TOWNS.get(village);
        double far = 0;
        if (t != null) for (Hand h : t.crew.values()) far = Math.max(far, h.far);
        return far;
    }

    public static int caughtForTests(UUID village) {
        Town t = TOWNS.get(village);
        return t == null ? 0 : t.caught;
    }

    public static boolean anyOutForTests(UUID village) {
        Town t = TOWNS.get(village);
        return t != null && !t.crew.isEmpty();
    }

    // ------------------------------------------------------------------ /village fleet

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("fleet")
            .executes(Fleet::cmdStatus)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                Town t = town(v.id());
                Waterfront.Dock q = quay(level, v, t);
                if (q == null) {
                    ctx.getSource().sendFailure(Component.literal("FLEET no quay: the town has no jetty over open water yet."));
                    return 0;
                }
                for (int i = 0; i < MOST; i++) keepBoats(level, v, t, q, false);
                t.chart = null;
                String said = decide(level, v, t, q, level.getDayTime() / 24000L);
                ctx.getSource().sendSuccess(() -> Component.literal("FLEET " + said + "; crew " + handsForTests(v.id())), false);
                return 1;
            }))
            .then(Commands.literal("home").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                Town t = town(v.id());
                int n = 0;
                for (Hand h : new ArrayList<>(t.crew.values())) {
                    if (level.getEntity(h.folk) instanceof VillageFolkEntity f) {
                        turnHome(level, t, h, f, "called home");
                        n++;
                    }
                }
                int sent = n;
                ctx.getSource().sendSuccess(() -> Component.literal("FLEET " + sent + " called home"), false);
                return n;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> out = FishMarket.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    @Nullable
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
        List<String> lines = new ArrayList<>();
        UUID id = v.id();
        if (!big(id)) {
            lines.add(Villages.name(id) + " has no fishing fleet: a town of " + from() + " with a quay fits one out (it has " + Villages.headcount(id) + " folk).");
        } else {
            Town t = town(id);
            Waterfront.Dock q = quay(level, v, t);
            if (q == null) {
                lines.add(Villages.name(id) + "'s fleet has no quay: no jetty over open water within reach yet.");
            } else {
                lines.add("The fishing fleet of " + Villages.name(id) + ": quay at " + q.start().toShortString() + ", " + boats(level, id, q).size()
                    + " boats of " + boatsWanted(id) + "; " + today(level, id) + ".");
                for (String h : handsForTests(id)) lines.add("  " + h);
                for (String[] p : log(id)) {
                    if (lines.size() > 12) break;
                    lines.add("  day " + p[0] + ": " + (!p[3].isEmpty() ? p[3] : p[1] + " boats, " + p[2] + " fish"));
                }
                lines.addAll(FishMarket.status(level, id));
            }
        }
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }
}
