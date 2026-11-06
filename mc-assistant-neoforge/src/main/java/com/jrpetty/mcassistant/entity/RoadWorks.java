package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [trade-world] The roads between towns, made better as they are used. Every pair of neighbouring towns has a road on the
 * line the caravans walk between their avenues' ends (Roads.bresenham, as Caravans.way), and each town keeps its own half
 * of it, from its end to the middle: it pays for its half out of its own stores, and its own road gang lays it.
 *
 * <ul>
 * <li><b>Grades.</b> A track at first (nothing laid: the grass the caravans tread); a worn earth path once a couple of
 *     caravans or travellers have come along it; gravel from the Stone Age, once it carries some traffic and the town has
 *     the coin for the works; cobbled stone with kerbs of stone slab from the Iron Age, once it is a busy road. A colony's
 *     road (Roads) is a path already. Water is bridged in planks with rails; a stone road's bridge is decked in stone with
 *     stone posts for rails. A road to an enemy is let be while the war lasts.</li>
 * <li><b>The road gang.</b> A hand at the town's works (TownJobs, a road crew's long shift) at the head of the stretch,
 *     a few steps a visit: the gravel or the stone out of the stores a block at a time, what comes up (the earth, the old
 *     gravel) back into them. When the stores run out the gang waits, and the books say what for. It is paid a coin out of
 *     the treasury for every sixteen steps it lays. The ground at the head is kept awake while it works there.</li>
 * <li><b>Along it.</b> On a gravel or stone road, a milestone every hundred blocks (a league) and at its ends, lettered
 *     with both towns and how far by the road (MilestoneBlock); a signpost where it meets another road, the towns each way
 *     in leagues; and, on an Iron Age stone road, a lamp post every twelve blocks for the last fifty into town.</li>
 * <li><b>What it brings.</b> Folk walk faster on a made road (a tenth on a path, a fifth on gravel, three tenths on stone,
 *     by what is underfoot: Caravans.drive, JobSeekers' travel), and a caravan between two towns joined by a good road
 *     carries more (a lot more on gravel, two on stone: Caravans.load). Every caravan, envoy and folk on the move that
 *     comes along a road counts as its traffic.</li>
 * </ul>
 * The Roads page of the town's books has each road: how far, each half's grade, its traffic, what its next grade wants
 * and costs, how far the gang has got, and the roadside inn (Roadside). Everything is in the town's notes (Ledger), so a
 * restart finds every road where it was.
 */
public final class RoadWorks {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private RoadWorks() {}

    /** The grades a road goes through. */
    public static final String[] GRADES = { "a track", "an earth path", "a gravel road", "a stone road" };
    /** Traffic (trips along it) each grade wants, the treasury it wants (the works' wages), and the age. */
    static final int[] TRAFFIC = { 0, 2, 6, 14 };
    static final int[] TREASURY = { 0, 0, 20, 60 };
    static final Villages.Age[] AGE = { Villages.Age.WOOD, Villages.Age.WOOD, Villages.Age.STONE, Villages.Age.IRON };
    /** The longest road worth keeping, and the shortest (towns side by side need none). */
    static final int LONGEST = 900, SHORTEST = 24;
    /** Steps laid a visit, and a coin to the gang every so many steps. */
    static final int STEPS = 4, WAGE_EVERY = 16;
    /** A milestone every so many blocks (a league); a lamp every so many steps, so far into town. */
    public static final int LEAGUE = 100, LAMP_EVERY = 12, LAMPS_FOR = 48;
    /** The walking pace on each grade's surface, against open ground. */
    static final double[] PACE = { 1.0, 1.1, 1.2, 1.3 };

    /** A road: its two towns (the lesser id first, so both see the same road) and its line from the first's end. */
    public record Road(UUID a, UUID b, List<int[]> line) {
        public UUID other(UUID me) { return me.equals(a) ? b : a; }

        public int length() { return line.size(); }

        /** Where my half ends: the middle. */
        int mid() { return line.size() / 2; }

        /** How long my half is. */
        int half(UUID me) { return me.equals(a) ? mid() : line.size() - mid(); }

        /** The line's index of my half's n-th step, out from my end. */
        int index(UUID me, int n) { return me.equals(a) ? n : line.size() - 1 - n; }

        boolean alongX() {
            int[] s = line.get(0), e = line.get(line.size() - 1);
            return Math.abs(e[0] - s[0]) >= Math.abs(e[1] - s[1]);
        }
    }

    private static final Map<String, List<int[]>> LINES = new ConcurrentHashMap<>();
    private static final Map<String, BlockPos> WINDOW = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LINES.clear();
        WINDOW.clear();
        Roadside.resetForTests();
    }

    static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** The road between these two towns (whether or not anything is laid on it), or null if they are not on a road. */
    @Nullable
    public static Road road(@Nullable Villages.Village x, @Nullable Villages.Village y) {
        if (x == null || y == null || x.id().equals(y.id()) || !x.dim().equals(y.dim())) return null;
        Villages.Village a = x.id().compareTo(y.id()) < 0 ? x : y, b = a == x ? y : x;
        double far = Math.sqrt(a.centre().distSqr(b.centre()));
        if (far > LONGEST) return null;
        List<int[]> line = LINES.computeIfAbsent(key(a.id(), b.id()), k -> {
            BlockPos s = Roads.exit(a.centre(), b.centre()), e = Roads.exit(b.centre(), a.centre());
            return Roads.bresenham(s.getX(), s.getZ(), e.getX(), e.getZ());
        });
        if (line.size() < SHORTEST) return null;
        return new Road(a.id(), b.id(), line);
    }

    /** The town's roads: to every neighbour (and its colony, and its mother), nearest first. */
    public static List<Road> roads(UUID village) {
        Villages.Village v = Villages.get(village);
        List<Road> out = new ArrayList<>();
        if (v == null) return out;
        List<Villages.Village> to = new ArrayList<>(Diplomacy.neighboursOf(village));
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            UUID o = link.getKey().equals(village) ? link.getValue() : link.getValue().equals(village) ? link.getKey() : null;
            Villages.Village ov = o == null ? null : Villages.get(o);
            if (ov != null && !to.contains(ov)) to.add(ov);
        }
        for (Villages.Village o : to) {
            Road r = road(v, o);
            if (r != null) out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------ each town's half

    /** My half of a road: its grade, how far the next grade is laid, and the traffic it has carried. */
    static final class Half {
        int grade, laid, traffic;
        /** What it waits on (for the books), or "". */
        String waiting = "";

        String encode() {
            return grade + "," + laid + "," + traffic + "," + waiting.replace(',', ';');
        }

        static Half decode(@Nullable String s) {
            Half h = new Half();
            if (s == null || s.isEmpty()) return h;
            String[] p = s.split(",", 4);
            try {
                h.grade = Integer.parseInt(p[0]);
                h.laid = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                h.traffic = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                h.waiting = p.length > 3 ? p[3] : "";
            } catch (NumberFormatException ignored) { }
            return h;
        }
    }

    static final String HALF = "tw.road.", WORKING = "tw.roadwork";

    static Half half(UUID me, UUID other) {
        Half h = Half.decode(Ledger.note(me, HALF + other));
        // A colony's road, finished by its mother's crew (Roads), is a path from end to end already.
        if (h.grade == 0 && colonyRoadDone(me, other)) h.grade = 1;
        return h;
    }

    static void save(UUID me, UUID other, Half h) {
        Ledger.note(me, HALF + other, h.encode());
    }

    private static boolean colonyRoadDone(UUID x, UUID y) {
        UUID colony = x.equals(Ledger.links().get(y)) ? y : y.equals(Ledger.links().get(x)) ? x : null;
        if (colony == null) return false;
        int[] r = Ledger.road(colony);
        return r != null && r[2] == 1;
    }

    /** The road's grade, as good as its worse half. */
    public static int grade(UUID x, UUID y) {
        return Math.min(half(x, y).grade, half(y, x).grade);
    }

    /** A caravan, an envoy or a folk on the move came along the road between these two: its traffic, both halves. */
    public static void travelled(@Nullable UUID x, @Nullable UUID y) {
        if (x == null || y == null || x.equals(y)) return;
        Villages.Village vx = Villages.get(x), vy = Villages.get(y);
        if (road(vx, vy) == null) return;
        Half hx = half(x, y), hy = half(y, x);
        hx.traffic++;
        hy.traffic++;
        save(x, y, hx);
        save(y, x, hy);
    }

    /** [Caravans.drive, JobSeekers] The pace a folk walks at on the ground underfoot: a made road is quicker going. */
    public static double pace(Entity f) {
        BlockState under = f.level().getBlockState(f.blockPosition().below());
        if (under.is(Blocks.DIRT_PATH)) return PACE[1];
        if (under.is(Blocks.GRAVEL)) return PACE[2];
        if (under.is(Blocks.COBBLESTONE) || under.is(Blocks.STONE_BRICKS) || under.is(Blocks.MOSSY_COBBLESTONE)) return PACE[3];
        return PACE[0];
    }

    /** [Caravans.load] More lots on a caravan between towns joined by a good road: a lot more on gravel, two on stone. */
    public static int extraLots(@Nullable UUID from, @Nullable UUID to) {
        if (from == null || to == null) return 0;
        int g = grade(from, to);
        return g >= 3 ? 2 : g == 2 ? 1 : 0;
    }

    // ------------------------------------------------------------------ the town's works

    /** What the next grade of my half of this road wants that it has not got, in words; "" if it may be laid now. */
    static String wants(ServerLevel level, Villages.Village v, Road r, Half h) {
        int next = h.grade + 1;
        if (next >= GRADES.length) return "it is as good as a road gets";
        UUID id = v.id(), o = r.other(id);
        if (Wars.atWar(id, o)) return "no work on a road to an enemy while the war lasts";
        if (Villages.ageOf(id).ordinal() < AGE[next].ordinal()) return GRADES[next] + " is for the " + AGE[next].label;
        if (h.traffic < TRAFFIC[next]) return "more traffic (" + h.traffic + " of " + TRAFFIC[next] + " trips)";
        if (Ledger.coins(id) < TREASURY[next]) return TREASURY[next] + " coins in the treasury for the works";
        return "";
    }

    /** The town's visit (TownLife.tick, every ten seconds): the road it is working on, a few steps; or the next to work. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) < 6) return;
        Road working = workingOn(v);
        if (working == null) {
            if (level.getGameTime() % 1200L >= 200L) return;              // a look for the next road once a minute
            working = choose(level, v);
            if (working == null) return;
            UUID o = working.other(id);
            Ledger.note(id, WORKING, o.toString());
            Half h = half(id, o);
            Villages.tell(id, level.getDayTime() / 24000L, "the road gang set out to make the road to " + Villages.name(o) + " "
                + GRADES[h.grade + 1] + " as far as the halfway mark");
        }
        work(level, v, working, STEPS);
    }

    /** The road the town's gang is on, if any (and still a road). */
    @Nullable
    static Road workingOn(Villages.Village v) {
        String s = Ledger.note(v.id(), WORKING);
        if (s == null || s.isEmpty()) return null;
        try {
            Villages.Village o = Villages.get(UUID.fromString(s));
            Road r = road(v, o);
            if (r == null) Ledger.forget(v.id(), WORKING);
            return r;
        } catch (IllegalArgumentException e) {
            Ledger.forget(v.id(), WORKING);
            return null;
        }
    }

    /** The busiest of the town's roads whose half may be made better now, or null. */
    @Nullable
    static Road choose(ServerLevel level, Villages.Village v) {
        Road best = null;
        int bestTraffic = -1;
        for (Road r : roads(v.id())) {
            Half h = half(v.id(), r.other(v.id()));
            if (!wants(level, v, r, h).isEmpty()) continue;
            if (h.traffic > bestTraffic) {
                bestTraffic = h.traffic;
                best = r;
            }
        }
        return best;
    }

    /**
     * Lay up to so many steps of the next grade on my half of this road, the gang at its head. Returns how many were
     * laid. Done to the middle: the grade is the half's, the chronicle says so, and the gang is free for the next road.
     */
    static int work(ServerLevel level, Villages.Village v, Road r, int steps) {
        return work(level, v, r, steps, false);
    }

    /** As above; by decree (the admin), no gang is waited for. */
    static int work(ServerLevel level, Villages.Village v, Road r, int steps, boolean decree) {
        UUID id = v.id(), o = r.other(id);
        Half h = half(id, o);
        int next = h.grade + 1;
        if (next >= GRADES.length || Wars.atWar(id, o)) {
            Ledger.forget(id, WORKING);
            release(level, id, o);
            return 0;
        }
        int laid = 0;
        String works = "road/up/" + o;
        while (laid < steps && h.laid < r.half(id)) {
            int i = r.index(id, h.laid);
            int[] p = r.line().get(i);
            BlockPos head = new BlockPos(p[0], v.centre().getY(), p[1]);
            if (!loaded(level, p[0], p[1])) {
                keepAwake(level, id, o, head);
                break;
            }
            if (!decree && !inATown(level, p[0], p[1]) && !TownJobs.atWork(level, v, works, Visitors.surface(level, head),
                    "making the road to " + Villages.name(o) + " " + GRADES[next], AssistantEntity.StationTask.HAUL)) break;
            String unpaid = step(level, v, r, i, next);
            if (unpaid != null) {
                h.waiting = unpaid;
                break;
            }
            h.waiting = "";
            h.laid++;
            laid++;
            if (h.laid % WAGE_EVERY == 0) payGang(level, v, works);
        }
        long day = level.getDayTime() / 24000L;
        if (h.laid >= r.half(id)) {
            h.grade = next;
            h.laid = 0;
            h.waiting = "";
            Ledger.forget(id, WORKING);
            release(level, id, o);
            Villages.tell(id, day, "the road to " + Villages.name(o) + " was made " + GRADES[next] + " as far as the halfway mark");
            if (half(o, id).grade >= next) Villages.tell(o, day, "the road to " + Villages.name(id) + " is " + GRADES[next] + " end to end now");
            LOG.info("[MCA-ROADS] {}: its half of the road to {} is {} now", Villages.name(id), Villages.name(o), GRADES[next]);
        }
        save(id, o, h);
        return laid;
    }

    /** A coin out of the treasury to the hand at the works, for its stretch. */
    private static void payGang(ServerLevel level, Villages.Village v, String works) {
        VillageFolkEntity hand = TownJobs.handAt(level, v.id(), works);
        if (hand == null || Ledger.coins(v.id()) < 1) return;
        int paid = Ledger.takeCoins(v.id(), 1);
        if (paid > 0) {
            hand.earn(paid);
            Economy.spent(v.id(), paid);
        }
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) return false;
            }
        }
        return true;
    }

    private static UUID owner(UUID me, UUID other) {
        return UUID.nameUUIDFromBytes(("mca-roadworks-" + me + "-" + other).getBytes());
    }

    private static void keepAwake(ServerLevel level, UUID me, UUID other, BlockPos head) {
        String k = me + "|" + other;
        BlockPos was = WINDOW.get(k);
        if (was != null && was.distSqr(head) < 24 * 24) return;
        if (was != null) ChunkLoad.setLoaded(level, owner(me, other), was, 1, false);
        ChunkLoad.setLoaded(level, owner(me, other), head, 1, true);
        WINDOW.put(k, head.immutable());
    }

    private static void release(ServerLevel level, UUID me, UUID other) {
        BlockPos was = WINDOW.remove(me + "|" + other);
        if (was != null) ChunkLoad.setLoaded(level, owner(me, other), was, 1, false);
    }

    private static boolean inATown(ServerLevel level, int x, int z) {
        for (Villages.Village t : Villages.every()) {
            if (!t.dim().equals(level.dimension())) continue;
            int reach = Villages.townReach(t.id());
            if (Math.abs(x - t.centre().getX()) <= reach && Math.abs(z - t.centre().getZ()) <= reach) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ a step of the road

    /** Soft ground a road is laid over (and dug out of): earth, grass, a path, sand, mud. */
    static boolean soft(BlockState st) {
        return st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
            || st.is(Blocks.MYCELIUM) || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.SAND)
            || st.is(Blocks.RED_SAND) || st.is(Blocks.MUD) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.CLAY);
    }

    /** What a soft top gives back to the stores when it is dug up for the road. */
    static Item dugAs(BlockState st) {
        if (st.is(Blocks.SAND)) return Items.SAND;
        if (st.is(Blocks.RED_SAND)) return Items.RED_SAND;
        if (st.is(Blocks.GRAVEL)) return Items.GRAVEL;
        if (st.is(Blocks.CLAY)) return Items.CLAY_BALL;
        if (st.is(Blocks.SNOW_BLOCK)) return Items.SNOWBALL;
        return Items.DIRT;
    }

    /**
     * One step of the next grade at the line's index {@code i}: the three-wide surface across the road (or the bridge's
     * deck), its kerbs, and what stands along it here. Returns null when done, or what the stores were short of.
     */
    static String step(ServerLevel level, Villages.Village v, Road r, int i, int grade) {
        int[] p = r.line().get(i);
        if (inATown(level, p[0], p[1]) || VanillaVillages.within(level, p[0], p[1], 4)) return null;
        Roads.Ground g = Roads.ground(level, p[0], p[1]);
        if (g == null) return null;
        boolean alongX = r.alongX();
        if (g.water()) return bridge(level, v, r, i, grade, g.y(), alongX);
        int roadY = g.y();
        for (int c = -1; c <= 1; c++) {
            int x = alongX ? p[0] : p[0] + c, z = alongX ? p[1] + c : p[1];
            Roads.Ground h = Roads.ground(level, x, z);
            if (h == null || h.water()) continue;
            BlockPos top = new BlockPos(x, h.y(), z);
            BlockState st = level.getBlockState(top);
            switch (grade) {
                case 1 -> {
                    if (soft(st) && !st.is(Blocks.DIRT_PATH) && !st.is(Blocks.SAND) && !st.is(Blocks.RED_SAND)) {
                        level.setBlock(top, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                case 2 -> {
                    if (!soft(st)) continue;                                  // rock, or made already: it is a road as it is
                    if (!TownWork.take(level, v, s -> s.is(Items.GRAVEL), 1)) return "gravel in the stores";
                    Crafts.giveBack(level, v, dugAs(st), 1);
                    level.setBlock(top, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                }
                default -> {
                    if (!(soft(st) || st.is(Blocks.GRAVEL))) continue;
                    Block stone;
                    if (TownWork.take(level, v, s -> s.is(Items.STONE_BRICKS), 1)) stone = Blocks.STONE_BRICKS;
                    else if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) stone = Blocks.COBBLESTONE;
                    else return "cobblestone in the stores";
                    Crafts.giveBack(level, v, dugAs(st), 1);
                    level.setBlock(top, stone.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            clearAbove(level, v, top);
        }
        if (grade >= 3) kerbs(level, v, r, i, roadY, alongX);
        if (grade >= 2) {
            milestone(level, v, r, i, alongX);
            signpost(level, v, r, i, alongX);
        }
        if (grade >= 3 && Villages.ageOf(v.id()).ordinal() >= Villages.Age.IRON.ordinal()) lamp(level, v, r, i, roadY, alongX);
        return null;
    }

    /** A bridge's step: planks with rails for a path; for a stone road, the deck in stone and the rails stone posts. */
    private static String bridge(ServerLevel level, Villages.Village v, Road r, int i, int grade, int waterY, boolean alongX) {
        int[] p = r.line().get(i);
        int deck = waterY + 1;
        for (int c = -2; c <= 2; c++) {
            int x = alongX ? p[0] : p[0] + c, z = alongX ? p[1] + c : p[1];
            BlockPos d = new BlockPos(x, deck, z);
            BlockState st = level.getBlockState(d);
            boolean rail = Math.abs(c) == 2;
            if (grade <= 2) {
                if (rail) {
                    BlockPos post = d.above();
                    if (level.getBlockState(d).is(BlockTags.PLANKS) && level.getBlockState(post).isAir()) {
                        if (!Crafts.fence(level, v)) return "planks for the bridge's rails";
                        level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                if (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) {
                    if (!Crafts.usePlanks(level, v, 1)) return "planks for the bridge";
                    level.setBlock(d, Blocks.SPRUCE_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                }
            } else {
                if (st.is(BlockTags.PLANKS) || st.isAir()) {
                    Block stone;
                    if (TownWork.take(level, v, s -> s.is(Items.STONE_BRICKS), 1)) stone = Blocks.STONE_BRICKS;
                    else if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) stone = Blocks.COBBLESTONE;
                    else return "stone for the bridge";
                    if (st.is(BlockTags.PLANKS)) Crafts.giveBack(level, v, st.getBlock().asItem(), 1);
                    level.setBlock(d, stone.defaultBlockState(), Block.UPDATE_ALL);
                }
                BlockPos post = d.above();
                BlockState ps = level.getBlockState(post);
                if (rail && (ps.is(BlockTags.WOODEN_FENCES) || ps.isAir())) {
                    if (!TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return "stone for the bridge's rails";
                    if (ps.is(BlockTags.WOODEN_FENCES)) Crafts.giveBack(level, v, ps.getBlock().asItem(), 1);
                    level.setBlock(post, Blocks.COBBLESTONE_WALL.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        return null;
    }

    /** Plants and snow off the road, a man's height; the logs to the stores. */
    private static void clearAbove(ServerLevel level, Villages.Village v, BlockPos top) {
        for (int h = 1; h <= 2; h++) {
            BlockPos q = top.above(h);
            BlockState st = level.getBlockState(q);
            if (st.isAir()) continue;
            if ((st.canBeReplaced() && st.getFluidState().isEmpty()) || st.is(Blocks.SNOW) || st.is(BlockTags.FLOWERS)) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** A stone road's kerbs: a stone slab either side on level ground, dressed from a cobblestone (two to one) or put by. */
    private static void kerbs(ServerLevel level, Villages.Village v, Road r, int i, int roadY, boolean alongX) {
        int[] p = r.line().get(i);
        for (int c : new int[]{ -2, 2 }) {
            int x = alongX ? p[0] : p[0] + c, z = alongX ? p[1] + c : p[1];
            Roads.Ground h = Roads.ground(level, x, z);
            if (h == null || h.water() || h.y() != roadY) continue;
            BlockPos at = new BlockPos(x, h.y() + 1, z);
            if (!level.getBlockState(at).isAir() && !level.getBlockState(at).canBeReplaced()) continue;
            if (level.getBlockState(at).getBlock() instanceof SlabBlock) continue;
            if (!TownWork.take(level, v, s -> s.is(Items.STONE_BRICK_SLAB) || s.is(Items.COBBLESTONE_SLAB), 1)) {
                if (i % 2 == 0 && !TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) continue;   // a cobble cut makes two
            }
            level.setBlock(at, Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), Block.UPDATE_ALL);
        }
    }

    /** A milestone, every league along the road and at its ends: two blocks off, its face to the road, lettered both ways. */
    private static void milestone(ServerLevel level, Villages.Village v, Road r, int i, boolean alongX) {
        int n = r.length();
        boolean spot = i == 3 || i == n - 4 || (i % LEAGUE == LEAGUE / 2 && i > 6 && i < n - 6);
        if (!spot || !Tiers.allows(level, Villages.ageOf(v.id()), WorkItems.MILESTONE_ITEM.get())) return;
        int[] p = r.line().get(i);
        int sx = alongX ? p[0] : p[0] - 2, sz = alongX ? p[1] - 2 : p[1];
        Roads.Ground g = Roads.ground(level, sx, sz);
        if (g == null || g.water()) return;
        BlockPos at = new BlockPos(sx, g.y() + 1, sz);
        if (level.getBlockState(at.below()).getBlock() instanceof MilestoneBlock || level.getBlockState(at).getBlock() instanceof MilestoneBlock) return;
        if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return;
        if (!Milestones.fromTheStores(level, v)) return;
        Direction face = alongX ? Direction.SOUTH : Direction.EAST;
        level.setBlock(at, WorkItems.MILESTONE.get().defaultBlockState().setValue(MilestoneBlock.FACING, face), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof MilestoneBlockEntity be) be.setWays(ways(r, i));
        level.playSound(null, at, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
        WorkTools.bump(v.id(), "work.milestones");
        int[] c = count(v.id());
        Ledger.note(v.id(), "tw.stones", (c[0] + 1) + "," + c[1]);
    }

    /** What a stone at this step says: each town, which way, and how far by the road. */
    static List<MilestoneBlockEntity.Way> ways(Road r, int i) {
        List<MilestoneBlockEntity.Way> out = new ArrayList<>();
        int[] here = r.line().get(i);
        int[] back = r.line().get(Math.max(0, i - 8)), on = r.line().get(Math.min(r.length() - 1, i + 8));
        Villages.Village a = Villages.get(r.a()), b = Villages.get(r.b());
        if (a != null) out.add(new MilestoneBlockEntity.Way(Villages.name(a.id()), a.id(), a.centre().getX(), a.centre().getZ(),
            Integer.signum(back[0] - here[0]), Integer.signum(back[1] - here[1]), i + Roads.EXIT));
        if (b != null) out.add(new MilestoneBlockEntity.Way(Villages.name(b.id()), b.id(), b.centre().getX(), b.centre().getZ(),
            Integer.signum(on[0] - here[0]), Integer.signum(on[1] - here[1]), r.length() - 1 - i + Roads.EXIT));
        return out;
    }

    /** "3 leagues", "half a league": a distance by the road in the roads' measure. */
    static String leagues(int blocks) {
        double l = blocks / (double) LEAGUE;
        if (l < 0.75) return "half a league";
        int n = (int) Math.round(l);
        return n == 1 ? "a league" : n + " leagues";
    }

    /** Where this road meets another (within two blocks), by this road's step, or -1. */
    static int meets(Road r, Road o) {
        for (int i = 6; i < r.length() - 6; i += 2) {
            int[] p = r.line().get(i);
            for (int j = 6; j < o.length() - 6; j += 2) {
                int[] q = o.line().get(j);
                if (Math.abs(p[0] - q[0]) <= 2 && Math.abs(p[1] - q[1]) <= 2) return i;
            }
        }
        return -1;
    }

    /** A signpost where the road meets another: a post and a sign three blocks off, the four towns each way in leagues. */
    private static void signpost(ServerLevel level, Villages.Village v, Road r, int i, boolean alongX) {
        Road crossing = null;
        for (Villages.Village t : Villages.every()) {
            if (!t.dim().equals(level.dimension())) continue;
            for (Road o : roads(t.id())) {
                if (key(o.a(), o.b()).equals(key(r.a(), r.b()))) continue;
                if (meets(r, o) == i) {
                    crossing = o;
                    break;
                }
            }
            if (crossing != null) break;
        }
        if (crossing == null) return;
        int[] p = r.line().get(i);
        int sx = alongX ? p[0] : p[0] - 3, sz = alongX ? p[1] - 3 : p[1];
        Roads.Ground g = Roads.ground(level, sx, sz);
        if (g == null || g.water()) return;
        BlockPos post = new BlockPos(sx, g.y() + 1, sz);
        if (!level.getBlockState(post).isAir() || !level.getBlockState(post.above()).isAir()) return;
        if (!Crafts.sign(level, v)) return;
        if (!Crafts.fence(level, v)) {
            Crafts.store(level, v, new net.minecraft.world.item.ItemStack(Items.OAK_SIGN));
            return;
        }
        level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos sign = post.above();
        level.setBlock(sign, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION,
            net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(alongX ? Direction.SOUTH : Direction.EAST)), Block.UPDATE_ALL);
        if (level.getBlockEntity(sign) instanceof SignBlockEntity s) {
            String[] lines = new String[4];
            lines[0] = "Crossroads";
            lines[1] = clip(Villages.name(r.a())) + " " + short_(i);
            lines[2] = clip(Villages.name(r.b())) + " " + short_(r.length() - 1 - i);
            lines[3] = clip(Villages.name(crossing.a())) + "/" + clip(Villages.name(crossing.b()));
            SignText text = new SignText();
            for (int k = 0; k < 4; k++) text = text.setMessage(k, Component.literal(lines[k]));
            s.setText(text, true);
            s.setText(text, false);
            s.setWaxed(true);
        }
        int[] c = count(v.id());
        Ledger.note(v.id(), "tw.stones", c[0] + "," + (c[1] + 1));
        Villages.tell(v.id(), level.getDayTime() / 24000L, "the road gang put up a signpost where the road to " + Villages.name(r.other(v.id()))
            + " meets the road between " + Villages.name(crossing.a()) + " and " + Villages.name(crossing.b()));
    }

    private static String clip(String s) {
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String short_(int blocks) {
        double l = blocks / (double) LEAGUE;
        return l < 0.75 ? "1/2 lg" : Math.round(l) + " lg";
    }

    /** Milestones and signposts this town has set: {stones, signposts}. */
    static int[] count(UUID village) {
        String s = Ledger.note(village, "tw.stones");
        if (s == null || s.isEmpty()) return new int[]{ 0, 0 };
        String[] p = s.split(",");
        try {
            return new int[]{ Integer.parseInt(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 0 };
        } catch (NumberFormatException e) {
            return new int[]{ 0, 0 };
        }
    }

    /** A lamp post near town on an Iron Age stone road: two lengths of fence and a lantern, three blocks off the road (past
     *  its kerb). */
    private static void lamp(ServerLevel level, Villages.Village v, Road r, int i, int roadY, boolean alongX) {
        UUID id = v.id();
        int fromMine = id.equals(r.a()) ? i : r.length() - 1 - i;
        if (fromMine > LAMPS_FOR || fromMine % LAMP_EVERY != LAMP_EVERY / 2) return;
        int[] p = r.line().get(i);
        int lx = alongX ? p[0] : p[0] + 3, lz = alongX ? p[1] + 3 : p[1];
        Roads.Ground g = Roads.ground(level, lx, lz);
        if (g == null || g.water() || Math.abs(g.y() - roadY) > 1) return;
        BlockPos post = new BlockPos(lx, g.y() + 1, lz);
        if (!level.getBlockState(post).isAir() || !level.getBlockState(post.above()).isAir() || !level.getBlockState(post.above(2)).isAir()) return;
        Block light = Masonry.light(level, v);
        if (light == null) return;
        if (!Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES), 2) && !Crafts.usePlanks(level, v, 4)) {
            Masonry.unlight(level, v, light);
            return;
        }
        level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(post.above(2), light.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------ the books

    /** The Roads page (Annals: the town's books): rows of "H|heading", "L|line", "M|muted", "W|warning". */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        ListTag rows = new ListTag();
        for (String r : lines(level, v)) rows.add(StringTag.valueOf(r));
        rows.addAll(Migration.rows(level, v));
        out.put("rows", rows);
        return out;
    }

    /** The roads' rows (and /village highways). */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("H|The roads");
        List<Road> all = roads(id);
        if (all.isEmpty()) {
            out.add("M|No roads yet: the town knows no neighbour near enough to make one to.");
            return out;
        }
        Road working = workingOn(v);
        for (Road r : all) {
            UUID o = r.other(id);
            Half mine = half(id, o), theirs = half(o, id);
            int g = Math.min(mine.grade, theirs.grade);
            out.add("L|To " + Villages.name(o) + ": " + leagues(r.length()) + " (" + r.length() + " blocks), " + GRADES[g]
                + (mine.grade != theirs.grade ? " (our half " + GRADES[mine.grade].replaceFirst("^an? ", "") + ", theirs "
                    + GRADES[theirs.grade].replaceFirst("^an? ", "") + ")" : "") + "; " + mine.traffic + " trips along it"
                + (Wars.atWar(id, o) ? "; at war" : ""));
            if (mine.grade + 1 < GRADES.length) {
                int next = mine.grade + 1;
                String want = wants(level, v, r, mine);
                boolean on = working != null && key(working.a(), working.b()).equals(key(r.a(), r.b()));
                String cost = next == 1 ? "labour only" : next == 2 ? "about " + 3 * r.half(id) + " gravel" : "about " + 3 * r.half(id)
                    + " cobblestone and its kerbs";
                out.add((on ? "L|" : "M|") + "  Next: " + GRADES[next] + " (" + cost + ")" + (on ? ": the gang is at it, " + mine.laid + " of "
                    + r.half(id) + " steps laid" + (mine.waiting.isEmpty() ? "" : ", waiting on " + mine.waiting) : want.isEmpty()
                    ? ": ready to start" : ": wants " + want) + ".");
                if (on && !mine.waiting.isEmpty()) out.add("W|  The gang waits on " + mine.waiting + ".");
            }
            String inn = Roadside.line(level, r);
            if (inn != null) out.add("M|  " + inn);
        }
        int[] c = count(id);
        if (c[0] + c[1] > 0) out.add("M|Milestones set: " + c[0] + "; signposts at the crossroads: " + c[1] + ".");
        return out;
    }

    // ------------------------------------------------------------------ tests, the admin and the stage

    /** Tests: this town's half of the road to that one: {grade, laid, traffic}. */
    public static int[] halfForTests(UUID me, UUID other) {
        Half h = half(me, other);
        return new int[]{ h.grade, h.laid, h.traffic };
    }

    /** Tests: set this town's half of the road to that one. */
    public static void setHalfForTests(UUID me, UUID other, int grade, int laid, int traffic) {
        Half h = new Half();
        h.grade = grade;
        h.laid = laid;
        h.traffic = traffic;
        save(me, other, h);
    }

    /** Tests and the admin: the gang at this road now, so many steps (a hand at once in the tests' instant mode). */
    public static int workForTests(ServerLevel level, Villages.Village v, UUID other, int steps) {
        Road r = road(v, Villages.get(other));
        if (r == null) return -1;
        Ledger.note(v.id(), WORKING, other.toString());
        return work(level, v, r, steps);
    }

    /** Tests: the road between two towns' line, from the lesser id's end. */
    @Nullable
    public static List<int[]> lineForTests(UUID x, UUID y) {
        Road r = road(Villages.get(x), Villages.get(y));
        return r == null ? null : r.line();
    }

    /** Tests: which town's end the line starts at. */
    public static UUID firstForTests(UUID x, UUID y) {
        return x.compareTo(y) < 0 ? x : y;
    }

    /** Tests: what the next grade of this town's half wants ("" if ready). */
    public static String wantsForTests(ServerLevel level, Villages.Village v, UUID other) {
        Road r = road(v, Villages.get(other));
        return r == null ? "no road" : wants(level, v, r, half(v.id(), other));
    }

    /**
     * The admin's "roads now": every road of the town made a grade better at once along its whole half, the age and the
     * traffic notwithstanding (out of the stores as ever, so far as they go). Returns what was done, in words.
     */
    public static List<String> upgradeNow(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Road r : roads(v.id())) {
            UUID o = r.other(v.id());
            Half h = half(v.id(), o);
            if (h.grade + 1 >= GRADES.length) continue;
            h.traffic = Math.max(h.traffic, TRAFFIC[h.grade + 1]);
            save(v.id(), o, h);
            Ledger.note(v.id(), WORKING, o.toString());
            int laid = work(level, v, r, r.half(v.id()), true);
            Half after = half(v.id(), o);
            out.add("road to " + Villages.name(o) + ": " + laid + " steps laid, now " + GRADES[after.grade]
                + (after.waiting.isEmpty() ? "" : " (waiting on " + after.waiting + ")"));
        }
        return out;
    }
}
