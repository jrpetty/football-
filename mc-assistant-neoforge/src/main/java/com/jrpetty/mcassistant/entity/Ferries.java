package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [transport] The ferry. Where a river or a lake lies between the town and its fields, its mine or a neighbour, and no
 * bridge spans it, the town keeps a ferry.
 * <ul>
 * <li><b>The crossing.</b> Looked for along the way from the town out to each farmer's field, the mine and each
 *     neighbour: the first stretch of open water three to thirty-two wide, and near it the narrowest place to cross it
 *     straight, with a low bank each side to land at.</li>
 * <li><b>The landings.</b> A short jetty out from each bank, planks on posts out of the stores, a lantern on a post
 *     by it and the ferry bell (a block of the town's own make: a copper bell on a little frame, FerryBellBlock) beside
 *     it; and the town's boat, out of the stores (or five of its planks).</li>
 * <li><b>The ferryman.</b> A trade of its own (StationTask.FERRY), taken up by one hand, a fisher or one with nothing
 *     to do for choice. By day it sits in the boat at one landing; folk who need to cross (a farmer whose field is over
 *     the water of a morning, home again at the day's end) walk to the landing, ring the bell if the boat is on the far
 *     side, get in behind it and are rowed across. A coin a crossing, out of the passenger's purse into the
 *     ferryman's; one with no coin is carried all the same, and the books say so. A player may ring the bell to call
 *     the ferry over, step into the boat behind the ferryman and be rowed across for a village coin.</li>
 * <li><b>Weather.</b> Nobody goes out on the water in the rain or a storm: the ferryman comes ashore and waits it out,
 *     and folk find their own way round.</li>
 * </ul>
 * The stone bridge that one day replaces it is Bridges'.
 */
public final class Ferries {

    private Ferries() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The narrowest and widest water a ferry crosses. */
    static final int LEAST_WIDE = 3, MOST_WIDE = 32;
    /** A river or a lake, not a pond: the water runs on at least this far along the bank either way of the crossing together. */
    static final int LEAST_LONG = 14;
    /** The town looks for a crossing once it has this many folk (a ferryman to spare). */
    static final int SURVEY_FROM = 6;
    /** The ferry's pace on the water, blocks a tick: a steady pull at the oars. */
    static final double PACE = 0.12;
    /** A crossing's fare, in coin. */
    public static final int FARE = 1;
    /** How long a folk waits at the landing before it finds its own way. */
    static final long WAIT = 1500L, WALK = 900L;
    /** The tag the town's ferry boat wears. */
    public static final String TAG = "mca_ferry";

    public enum State {
        SURVEYED("found, its landings to build"), BUILDING("its landings being built"), RUNNING("running"),
        RETIRED("retired: the bridge carries everybody now");

        public final String words;

        State(String words) { this.words = words; }
    }

    /** The town's crossing: its two banks, the water between, the landings, the boat, the ferryman and the books. */
    public static final class Crossing {
        BlockPos bankA, bankB;
        Direction way;
        int surface, width, jetty;
        String toWhat = "";
        State state = State.SURVEYED;
        int landings;
        @Nullable UUID boat, ferryman;
        /** [itemaudit] The boat itself, kept to hand between ticks (never saved): see boat(). */
        @Nullable transient Boat held;
        int boatAt;
        long startedDay = -1, retiredDay = -1;
        public int crossings, fares, free, players;
        // The bridge that will replace it (Bridges).
        Bridges.Stage bridge = Bridges.Stage.NONE;
        @Nullable BlockPos bridgeA, bridgeB;
        int bridgeSide, bridgeDone;
        long votedDay = -1, bridgeOpenedDay = -1, lastAsked = -1;
        String vote = "";

        public State state() { return state; }
        public Bridges.Stage bridge() { return bridge; }
        public BlockPos bankA() { return bankA; }
        public BlockPos bankB() { return bankB; }
        public Direction way() { return way; }
        public int width() { return width; }
        public int surface() { return surface; }
        @Nullable public UUID ferryman() { return ferryman; }

        /** The first water from a bank: where its jetty starts (0: the town's side, 1: the far side). */
        BlockPos root(int end) {
            return end == 0 ? bankA.relative(way).atY(surface) : bankB.relative(way.getOpposite()).atY(surface);
        }

        /** The jetty's last plank. */
        BlockPos landing(int end) {
            return root(end).relative(end == 0 ? way : way.getOpposite(), jetty - 1);
        }

        /** Where the boat lies at a landing: in the water off the jetty's end. */
        BlockPos boatSpot(int end) {
            return landing(end).relative(end == 0 ? way : way.getOpposite());
        }

        /** Where a passenger stands to step into the boat: on the jetty's last plank. */
        BlockPos stand(int end) {
            return landing(end).above();
        }

        /** The ferry bell, on the bank beside the jetty. */
        BlockPos bell(int end) {
            return (end == 0 ? bankA : bankB).relative(way.getClockWise()).above();
        }

        /** The lantern's post, on the bank the other side of the jetty. */
        BlockPos lamp(int end) {
            return (end == 0 ? bankA : bankB).relative(way.getCounterClockWise()).above();
        }

        /** The middle of the water, between the two boat spots. */
        Vec3 middle() {
            BlockPos a = boatSpot(0), b = boatSpot(1);
            return new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, surface, (a.getZ() + b.getZ()) / 2.0 + 0.5);
        }

        /** Which side of the water a spot is on: 0 the town's, 1 the far one. */
        int side(BlockPos p) {
            Vec3 m = middle();
            double d = (p.getX() + 0.5 - m.x) * way.getStepX() + (p.getZ() + 0.5 - m.z) * way.getStepZ();
            return d < 0 ? 0 : 1;
        }

        /** Is this spot near the crossing at all (along the river a way, not miles off)? */
        boolean near(BlockPos p, int reach) {
            Vec3 m = middle();
            double dx = p.getX() + 0.5 - m.x, dz = p.getZ() + 0.5 - m.z;
            return dx * dx + dz * dz <= (double) reach * reach;
        }

        public String where() {
            Vec3 m = middle();
            return (int) m.x + ", " + (int) m.z;
        }
    }

    private static final Map<UUID, Crossing> CROSSINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** The ferry called to a landing by its bell, till when: {end, until} by village. */
    private static final Map<UUID, long[]> CALLED = new ConcurrentHashMap<>();
    private static final Map<UUID, Passage> PASSAGES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_LOOK = new ConcurrentHashMap<>();
    private static final Map<UUID, Voyage> VOYAGES = new ConcurrentHashMap<>();
    /** Tests: the weather as the ferry sees it (true foul, false fair), or the world's (null). */
    private static volatile Boolean weatherForTests;

    public static void resetForTests() {
        CROSSINGS.clear();
        LAST.clear();
        CALLED.clear();
        PASSAGES.clear();
        NEXT_LOOK.clear();
        VOYAGES.clear();
        weatherForTests = null;
    }

    public static void weatherForTests(@Nullable Boolean foul) {
        weatherForTests = foul;
    }

    /** Rain or a storm: no crossings. */
    public static boolean badWeather(ServerLevel level) {
        Boolean t = weatherForTests;
        return t != null ? t : level.isRaining() || Weather.stormy(level);
    }

    // ------------------------------------------------------------------ the books

    @Nullable
    public static Crossing crossing(UUID village) {
        Crossing c = CROSSINGS.get(village);
        if (c != null) return c;
        c = decode(Ledger.note(village, "ferry"));
        if (c != null) CROSSINGS.put(village, c);
        return c;
    }

    static void save(UUID village, Crossing c) {
        CROSSINGS.put(village, c);
        Ledger.note(village, "ferry", encode(c));
    }

    static String encode(Crossing c) {
        return String.join("|", "v1", pos(c.bankA), pos(c.bankB), c.way.getName(), Integer.toString(c.surface), Integer.toString(c.width),
            Integer.toString(c.jetty), c.state.name(), Integer.toString(c.landings), c.boat == null ? "-" : c.boat.toString(),
            c.ferryman == null ? "-" : c.ferryman.toString(), Integer.toString(c.boatAt), Long.toString(c.startedDay), Long.toString(c.retiredDay),
            c.crossings + "," + c.fares + "," + c.free + "," + c.players, c.toWhat.replace('|', '/'), c.bridge.name(),
            Integer.toString(c.bridgeSide), Integer.toString(c.bridgeDone), Long.toString(c.votedDay), Long.toString(c.bridgeOpenedDay),
            Long.toString(c.lastAsked), c.vote.replace('|', '/'), c.bridgeA == null ? "-" : pos(c.bridgeA), c.bridgeB == null ? "-" : pos(c.bridgeB));
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static BlockPos pos(String s) {
        String[] f = s.split(",");
        return new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
    }

    @Nullable
    static Crossing decode(@Nullable String s) {
        if (s == null || !s.startsWith("v1|")) return null;
        try {
            String[] f = s.split("\\|", -1);
            Crossing c = new Crossing();
            c.bankA = pos(f[1]);
            c.bankB = pos(f[2]);
            c.way = Direction.byName(f[3]);
            if (c.way == null) return null;
            c.surface = Integer.parseInt(f[4]);
            c.width = Integer.parseInt(f[5]);
            c.jetty = Integer.parseInt(f[6]);
            c.state = State.valueOf(f[7]);
            c.landings = Integer.parseInt(f[8]);
            c.boat = "-".equals(f[9]) ? null : UUID.fromString(f[9]);
            c.ferryman = "-".equals(f[10]) ? null : UUID.fromString(f[10]);
            c.boatAt = Integer.parseInt(f[11]);
            c.startedDay = Long.parseLong(f[12]);
            c.retiredDay = Long.parseLong(f[13]);
            String[] n = f[14].split(",");
            c.crossings = Integer.parseInt(n[0]);
            c.fares = Integer.parseInt(n[1]);
            c.free = Integer.parseInt(n[2]);
            c.players = Integer.parseInt(n[3]);
            c.toWhat = f[15];
            c.bridge = Bridges.Stage.valueOf(f[16]);
            c.bridgeSide = Integer.parseInt(f[17]);
            c.bridgeDone = Integer.parseInt(f[18]);
            c.votedDay = Long.parseLong(f[19]);
            c.bridgeOpenedDay = Long.parseLong(f[20]);
            c.lastAsked = Long.parseLong(f[21]);
            c.vote = f[22];
            if (f.length > 24 && !"-".equals(f[23]) && !"-".equals(f[24])) {
                c.bridgeA = pos(f[23]);
                c.bridgeB = pos(f[24]);
            }
            return c;
        } catch (RuntimeException e) {
            LOG.warn("[MCA-FERRY] a damaged crossing in the books: {}", e.toString());
            return null;
        }
    }

    /** Is the ferry running (its landings built and its boat in the water)? The ferryman's trade is wanted only then. */
    public static boolean running(@Nullable UUID village) {
        if (village == null) return false;
        Crossing c = crossing(village);
        return c != null && c.state == State.RUNNING;
    }

    /** The town wants one ferryman while its ferry runs (Villages' share of the trades). */
    public static boolean wanted(@Nullable UUID village) {
        return running(village);
    }

    // ------------------------------------------------------------------ the town's day

    /** Every few seconds for each village (Transport): the crossing found, its landings built, its ferryman taken on. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LAST.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        LAST.put(id, now);
        work(level, v);
    }

    static void work(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Crossing c = crossing(id);
        if (c == null) {
            // Looked for now and then, by a town big enough to keep a ferryman.
            if (level.getGameTime() % 1200L < 200L && Villages.folkOf(id).size() >= SURVEY_FROM) survey(level, v);
            return;
        }
        switch (c.state) {
            case SURVEYED, BUILDING -> build(level, v, c, false);
            case RUNNING -> {
                if (c.landings != 3) build(level, v, c, false);           // a landing knocked about: put right
                if (boat(level, c) == null) moor(level, v, c, false);
                if (ferrymanOf(level, c) == null) appoint(level, v, c);
            }
            case RETIRED -> { }
        }
        Bridges.tick(level, v, c);
    }

    // ------------------------------------------------------------------ the crossing

    /**
     * Look for water between the town and where its folk go: each farmer's field, the mine, each neighbour. The first
     * water three to thirty-two wide on the way out from the town's edge, and near it the narrowest straight crossing
     * with a low bank each side. Null if there is none (the town wants no ferry).
     */
    @Nullable
    static Crossing survey(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        List<BlockPos> to = new ArrayList<>();
        List<String> what = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.stationTask() == AssistantEntity.StationTask.FARM && a.workZone() != null) {
                to.add(a.workZone().center());
                what.add("its fields");
            }
        }
        BlockPos mine = TownMine.siteOf(id);
        if (mine != null) {
            to.add(mine);
            what.add("its mine");
        }
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(id) || !Diplomacy.neighbours(v, o)) continue;
            to.add(o.centre());
            what.add(Villages.name(o.id()));
        }
        int reach = Villages.townReach(id);
        for (int k = 0; k < to.size(); k++) {
            BlockPos d = to.get(k);
            Crossing c = across(level, v, heart, d, reach);
            if (c == null) continue;
            if (Bridges.worksBridgeNear(id, c) != null) continue;          // the town's great-work bridge crosses there
            c.toWhat = what.get(k);
            save(id, c);
            Villages.tell(id, level.getDayTime() / 24000L, "the water between the town and " + c.toWhat + " at " + c.where()
                + " is to have a ferry: " + c.width + " blocks across, landings to be built either side");
            LOG.info("[MCA-FERRY] {}: a crossing to {} at {}, {} wide", Villages.name(id), c.toWhat, c.where(), c.width);
            return c;
        }
        return null;
    }

    /** The crossing on the way from the heart to there, if there is water on it to cross. */
    @Nullable
    static Crossing across(ServerLevel level, Villages.Village v, BlockPos heart, BlockPos to, int reach) {
        List<int[]> line = Roads.bresenham(heart.getX(), heart.getZ(), to.getX(), to.getZ());
        int run = 0, first = -1;
        for (int i = 0; i < line.size(); i++) {
            int x = line.get(i)[0], z = line.get(i)[1];
            if (Math.max(Math.abs(x - heart.getX()), Math.abs(z - heart.getZ())) <= reach) { run = 0; continue; }
            if (!level.hasChunk(x >> 4, z >> 4)) return null;
            Roads.Ground g = Roads.ground(level, x, z);
            if (g != null && g.water() && !Villages.onFarmland(v.id(), heart, new BlockPos(x, 0, z), 0)) {
                if (run == 0) first = i;
                run++;
                continue;
            }
            if (run >= LEAST_WIDE) {
                int[] m = line.get(first + run / 2);
                Crossing c = narrowest(level, v, heart, to, m[0], m[1]);
                // A pond on the way is walked round; a river or a lake is not.
                return c != null && along(level, c) >= LEAST_LONG ? c : null;
            }
            run = 0;
        }
        return null;
    }

    /** How far the water runs along the banks through the middle of the crossing, both ways together (64 at most). */
    static int along(ServerLevel level, Crossing c) {
        BlockPos mid = c.bankA.relative(c.way, (c.width + 1) / 2);
        Direction side = c.way.getClockWise();
        int n = 1;
        for (Direction d : new Direction[]{ side, side.getOpposite() }) {
            for (int k = 1; k <= 32; k++) {
                BlockPos p = mid.relative(d, k);
                if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) break;
                Roads.Ground g = Roads.ground(level, p.getX(), p.getZ());
                if (g == null || !g.water()) break;
                n++;
            }
        }
        return n;
    }

    /** Near this spot in the water, the narrowest straight crossing between two low banks. */
    @Nullable
    private static Crossing narrowest(ServerLevel level, Villages.Village v, BlockPos heart, BlockPos to, int mx, int mz) {
        Direction main = Direction.getNearest(to.getX() - heart.getX(), 0, to.getZ() - heart.getZ());
        Direction other = Direction.getNearest(main.getStepX() == 0 ? to.getX() - heart.getX() : 0, 0,
            main.getStepZ() == 0 ? to.getZ() - heart.getZ() : 0);
        if (other.getAxis() == main.getAxis() || other.getAxis() == Direction.Axis.Y) other = main.getClockWise();
        Crossing best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction way : new Direction[]{ main, other }) {
            Direction side = way.getClockWise();
            for (int o = -12; o <= 12; o++) {
                int cx = mx + side.getStepX() * o, cz = mz + side.getStepZ() * o;
                Crossing c = measure(level, cx, cz, way);
                if (c == null) continue;
                double score = c.width + Math.abs(o) * 0.7 + (way == main ? 0 : 3);
                if (score < bestScore) { bestScore = score; best = c; }
            }
        }
        if (best == null) return null;
        // A is the town's side.
        if (best.bankB.distSqr(heart.atY(best.bankB.getY())) < best.bankA.distSqr(heart.atY(best.bankA.getY()))) {
            BlockPos t = best.bankA;
            best.bankA = best.bankB;
            best.bankB = t;
            best.way = best.way.getOpposite();
        }
        best.jetty = Math.max(1, Math.min(3, (best.width - 3) / 2));
        return best;
    }

    /** The water through this spot along this way: banks either side, all of it one level. Null if it will not do. */
    @Nullable
    static Crossing measure(ServerLevel level, int x, int z, Direction way) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        Roads.Ground g = Roads.ground(level, x, z);
        if (g == null || !g.water()) return null;
        int surface = g.y();
        int back = 0, fwd = 0;
        BlockPos a = null, b = null;
        for (int k = 1; k <= MOST_WIDE + 1; k++) {
            int qx = x - way.getStepX() * k, qz = z - way.getStepZ() * k;
            if (!level.hasChunk(qx >> 4, qz >> 4)) return null;
            Roads.Ground q = Roads.ground(level, qx, qz);
            if (q == null) return null;
            if (q.water()) {
                if (q.y() != surface) return null;
                back++;
                continue;
            }
            if (q.y() < surface - 1 || q.y() > surface + 2) return null;          // a cliff, or a bank under the water
            a = new BlockPos(qx, q.y(), qz);
            break;
        }
        for (int k = 1; k <= MOST_WIDE + 1; k++) {
            int qx = x + way.getStepX() * k, qz = z + way.getStepZ() * k;
            if (!level.hasChunk(qx >> 4, qz >> 4)) return null;
            Roads.Ground q = Roads.ground(level, qx, qz);
            if (q == null) return null;
            if (q.water()) {
                if (q.y() != surface) return null;
                fwd++;
                continue;
            }
            if (q.y() < surface - 1 || q.y() > surface + 2) return null;
            b = new BlockPos(qx, q.y(), qz);
            break;
        }
        int width = back + fwd + 1;
        if (a == null || b == null || width < LEAST_WIDE || width > MOST_WIDE) return null;
        Crossing c = new Crossing();
        c.bankA = a;
        c.bankB = b;
        c.way = way;
        c.surface = surface;
        c.width = width;
        return c;
    }

    // ------------------------------------------------------------------ the landings and the boat

    /**
     * Each landing: a jetty of planks from the bank out over the water, posts down to the bed either side, a lantern on
     * a post on the bank and the ferry bell the other side of it, and then the boat. Out of the stores, by a hand at the
     * town's works (a fisher for choice), unless {@code free}. The ferry runs once both landings stand and the boat is in.
     */
    static void build(ServerLevel level, Villages.Village v, Crossing c, boolean free) {
        UUID id = v.id();
        if (c.state == State.SURVEYED) {
            c.state = State.BUILDING;
            save(id, c);
        }
        for (int end = 0; end <= 1; end++) {
            if (!level.isLoaded(c.root(end))) continue;
            boolean done = landing(level, v, c, end, free);
            int bit = end == 0 ? 1 : 2;
            if (done && (c.landings & bit) == 0) {
                c.landings |= bit;
                save(id, c);
            } else if (!done && (c.landings & bit) != 0 && c.state != State.RUNNING) {
                c.landings &= ~bit;
                save(id, c);
            }
            if (!done) return;                                        // one at a time
        }
        if (c.landings == 3 && c.state == State.BUILDING && moor(level, v, c, free)) {
            c.state = State.RUNNING;
            c.startedDay = level.getDayTime() / 24000L;
            save(id, c);
            Villages.tell(id, c.startedDay, "the ferry across the water to " + c.toWhat + " began to run: a landing each side, the bell "
                + "to call it and the town's boat, a coin a crossing");
            if (!free) appoint(level, v, c);
        }
    }

    /** One landing built (or put right): true once it all stands. */
    private static boolean landing(ServerLevel level, Villages.Village v, Crossing c, int end, boolean free) {
        Direction out = end == 0 ? c.way : c.way.getOpposite();
        Direction side = c.way.getClockWise();
        List<BlockPos> planks = new ArrayList<>();
        for (int k = 0; k < c.jetty; k++) planks.add(c.root(end).relative(out, k));
        boolean missing = false;
        for (BlockPos p : planks) if (!level.getBlockState(p).is(Blocks.SPRUCE_PLANKS)) missing = true;
        BlockPos lampPost = c.lamp(end), bellAt = c.bell(end);
        boolean lampUp = level.getBlockState(lampPost.above()).getBlock() instanceof net.minecraft.world.level.block.LanternBlock;
        boolean bellUp = level.getBlockState(bellAt).is(McAssistantMod.FERRY_BELL.get());
        if (!missing && lampUp && bellUp) return true;
        if (!free && Market.stock(level, v.id(), s -> s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)) == 0) return false;
        if (!free && !TownJobs.atWork(level, v, "ferry", c.root(end), "building the ferry landing", AssistantEntity.StationTask.FISH)) return false;
        for (int k = 0; k < planks.size(); k++) {
            BlockPos at = planks.get(k);
            BlockState here = level.getBlockState(at);
            if (!here.is(Blocks.SPRUCE_PLANKS)) {
                if (!here.isAir() && here.getFluidState().isEmpty() && !here.canBeReplaced()) return false;   // something there now
                if (!free && !Crafts.usePlanks(level, v, 1)) return false;
                level.setBlock(at, Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            }
            if (k % 2 == 1 || k == planks.size() - 1) {
                for (Direction s : new Direction[]{ side, side.getOpposite() }) {
                    BlockPos post = at.relative(s).below();
                    for (int dy = 0; dy < 6; dy++) {
                        BlockPos q = post.below(dy);
                        BlockState st = level.getBlockState(q);
                        if (st.is(Blocks.SPRUCE_FENCE)) continue;
                        if (!st.is(Blocks.WATER)) break;
                        if (!free && !Crafts.fence(level, v)) return false;
                        level.setBlock(q, Blocks.SPRUCE_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), 3);
                    }
                }
            }
        }
        // The lantern on its post, on the bank.
        if (!lampUp && level.getBlockState(lampPost).isAir() && level.getBlockState(lampPost.above()).isAir()
                && level.getBlockState(lampPost.below()).isFaceSturdy(level, lampPost.below(), Direction.UP)) {
            Block light = free ? Blocks.LANTERN : Masonry.light(level, v);
            if (light instanceof net.minecraft.world.level.block.LanternBlock && (free || Crafts.fence(level, v))) {
                level.setBlock(lampPost, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                level.setBlock(lampPost.above(), light.defaultBlockState(), 3);
            } else if (light != null && !free) {
                Masonry.unlight(level, v, light);
            }
        }
        // The ferry bell, made at the bench of the stores' copper and planks if there is none put by.
        if (!bellUp && level.getBlockState(bellAt).canBeReplaced()
                && level.getBlockState(bellAt.below()).isFaceSturdy(level, bellAt.below(), Direction.UP)) {
            if (free || Railways.takeOrMake(level, v, McAssistantMod.FERRY_BELL_ITEM.get())) {
                level.setBlock(bellAt, McAssistantMod.FERRY_BELL.get().defaultBlockState()
                    .setValue(com.jrpetty.mcassistant.block.FerryBellBlock.FACING, c.way.getClockWise()), 3);
            }
        }
        for (BlockPos p : planks) if (!level.getBlockState(p).is(Blocks.SPRUCE_PLANKS)) return false;
        // The lamp and the bell come when the stores have them; the landing serves without.
        return true;
    }

    /** The ferry boat, if it is about. */
    @Nullable
    static Boat boat(ServerLevel level, Crossing c) {
        if (c.boat == null) return null;
        // [itemaudit] The boat is asked after every tick while it rows (Ferries.tick): kept to hand between ticks rather
        // than looked up afresh each time, and looked up again only once it is gone, unloaded, or another boat.
        Boat held = c.held;
        if (held != null && held.isAlive() && !held.isRemoved() && held.level() == level && c.boat.equals(held.getUUID())) return held;
        Entity e = level.getEntity(c.boat);
        c.held = e instanceof Boat b && b.isAlive() ? b : null;
        return c.held;
    }

    /** The town's boat in the water at a landing: one out of the stores, or five of their planks made into one. */
    static boolean moor(ServerLevel level, Villages.Village v, Crossing c, boolean free) {
        Boat have = boat(level, c);
        if (have != null) return true;
        if (c.boat != null) {
            BlockPos last = c.boatSpot(c.boatAt);
            if (!level.isLoaded(last)) return false;                     // its water not loaded: it may be there yet
        }
        BlockPos spot = c.boatSpot(0);
        if (!level.isLoaded(spot)) return false;
        if (!free && !Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.BOATS) && !s.is(net.minecraft.tags.ItemTags.CHEST_BOATS), 1)
                && !Crafts.usePlanks(level, v, 5)) return false;
        Boat boat = EntityType.BOAT.create(level);
        if (boat == null) return false;
        boat.setVariant(Boat.Type.SPRUCE);
        boat.moveTo(spot.getX() + 0.5, spot.getY() + 0.9, spot.getZ() + 0.5, c.way.toYRot(), 0.0F);
        boat.addTag(TAG);
        boat.addTag(TAG + "/" + v.id());
        boat.setCustomName(Component.literal(Villages.name(v.id()) + " ferry"));
        if (!level.addFreshEntity(boat)) return false;
        c.boat = boat.getUUID();
        c.boatAt = 0;
        save(v.id(), c);
        return true;
    }

    // ------------------------------------------------------------------ the ferryman

    @Nullable
    static VillageFolkEntity ferrymanOf(ServerLevel level, Crossing c) {
        if (c.ferryman == null) return null;
        Entity e = level.getEntity(c.ferryman);
        return e instanceof VillageFolkEntity f && f.isAlive() && f.stationTask() == AssistantEntity.StationTask.FERRY ? f : null;
    }

    /**
     * A ferryman taken on: one already at the trade (the town's own share of the trades may have made one), else a
     * fisher (it knows a boat) or a hand with nothing to do, never from a craft, the storehouse, the bank or the watch.
     */
    @Nullable
    static VillageFolkEntity appoint(ServerLevel level, Villages.Village v, Crossing c) {
        UUID id = v.id();
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive()) continue;
            if (f.trip() != null || f.expedition() != null) continue;
            AssistantEntity.StationTask t = f.stationTask();
            if (t == AssistantEntity.StationTask.FERRY) {
                take(level, v, c, f, t);
                return f;
            }
            if (t != AssistantEntity.StationTask.NONE && t != AssistantEntity.StationTask.FISH && Villages.share(id, t) < 0.5) continue;
            if (t.isCraft() || t == AssistantEntity.StationTask.GUARD || t == AssistantEntity.StationTask.STORE
                || t == AssistantEntity.StationTask.HAUL || t == AssistantEntity.StationTask.CAVE || t == AssistantEntity.StationTask.SCOUT) continue;
            int score = f.tradeLevel(AssistantEntity.StationTask.FERRY) * 5 + f.tradeLevel(AssistantEntity.StationTask.FISH) * 2
                + (t == AssistantEntity.StationTask.NONE ? 30 : t == AssistantEntity.StationTask.FISH ? 20 : 0)
                + (f.life().has(Social.Trait.SOCIABLE) ? 8 : 0) + (f.life().has(Social.Trait.EASYGOING) ? 4 : 0)
                - (int) Math.sqrt(f.blockPosition().distSqr(c.bankA)) / 8;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (best == null) return null;
        take(level, v, c, best, best.stationTask());
        return best;
    }

    /** This folk is the ferryman now: its ground the crossing, its post the town's landing. */
    private static void take(ServerLevel level, Villages.Village v, Crossing c, VillageFolkEntity f, AssistantEntity.StationTask was) {
        BlockPos post = c.stand(0);
        Vec3 m = c.middle();
        BlockPos mid = BlockPos.containing(m.x, c.surface + 1, m.z);
        if (was != AssistantEntity.StationTask.FERRY || f.workZone() == null || !f.workZone().containsColumn(mid)) {
            f.setStation(post, AssistantEntity.StationTask.FERRY);
            f.assignPlot(WorkZone.around(mid, c.width / 2 + 6, WorkZone.DEFAULT_DEPTH), "The Ferry");
        }
        boolean news = !f.getUUID().equals(c.ferryman);
        c.ferryman = f.getUUID();
        save(v.id(), c);
        if (news && was != AssistantEntity.StationTask.FERRY) {
            long day = level.getDayTime() / 24000L;
            Villages.tell(v.id(), day, f.displayNameCap() + " " + (was == AssistantEntity.StationTask.NONE ? "took up" : "gave up " + was.label + " for")
                + " the ferry across to " + c.toWhat);
            f.persona().remember(day, "I became the town's ferryman", 4);
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "The ferry's mine, then. A coin a crossing, and mind you sit still.",
                "Ferryman! I've always liked the water.", "I'll row anybody across who needs it. Ring the bell if I'm on the far side."));
            LOG.info("[MCA-FERRY] {} of {} took up the ferry (was {})", f.displayNameCap(), Villages.name(v.id()), was);
        }
    }

    /** Is this the ferryman, on its hours (dawn to dusk)? */
    static boolean onDuty(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != AssistantEntity.StationTask.FERRY || f.isSleeping()) return false;
        long t = level.getDayTime() % 24000L;
        return t >= 500L && t < 12800L;
    }

    /**
     * The ferryman's day (its trade's work, and its hold on the folk's tick): to the boat at whichever landing it lies,
     * into it, and sat there for whoever comes (the crossings themselves are rowed every tick: voyage); ashore in foul
     * weather. True while it is at the ferry.
     */
    public static boolean duty(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null) return false;
        Crossing c = crossing(id);
        if (c == null || c.state != State.RUNNING || !onDuty(f, level)) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        if (!f.getUUID().equals(c.ferryman)) {
            VillageFolkEntity other = ferrymanOf(level, c);
            if (other != null && other != f) return false;               // one ferryman: the other's trade is changed by the town
            take(level, v, c, f, AssistantEntity.StationTask.FERRY);
        }
        Boat boat = boat(level, c);
        if (boat == null) {
            moor(level, v, c, false);
            return false;
        }
        if (VOYAGES.containsKey(id)) return f.getVehicle() == boat;
        if (badWeather(level)) {
            if (f.getVehicle() == boat) {
                f.stopRiding();
                BlockPos s = c.stand(c.boatAt);
                f.moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, f.getYRot(), 0.0F);
            }
            BlockPos shelter = c.stand(0).relative(c.way.getOpposite(), c.jetty + 1);
            if (f.blockPosition().distSqr(shelter) > 9 && (f.getNavigation().isDone() || f.tickCount % 60 == 0)) f.walkTo(shelter, 0.9D);
            if (f.tickCount % 2400 == 600) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "No crossings in this weather.",
                "The river's too rough today. Try again when it clears.", "Not in this. I'm not drowning for a coin."));
            return true;
        }
        if (f.getVehicle() == boat) {
            f.getNavigation().stop();
            return true;
        }
        BlockPos stand = c.stand(c.boatAt);
        if (f.blockPosition().distSqr(stand) <= 3 * 3) {
            // In, first: the oars are its.
            Entity first = boat.getFirstPassenger();
            if (first == null) {
                if (boat.distanceToSqr(Vec3.atCenterOf(c.boatSpot(c.boatAt))) > 4.0) {
                    BlockPos b = c.boatSpot(c.boatAt);
                    boat.moveTo(b.getX() + 0.5, b.getY() + 0.9, b.getZ() + 0.5, boat.getYRot(), 0.0F);
                }
                f.startRiding(boat, true);
                f.brain("sat in the ferry at the " + (c.boatAt == 0 ? "town's" : "far") + " landing");
            } else if (!(first instanceof Player)) {
                first.stopRiding();
            }
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.walkTo(stand, 1.0D);
        f.brain("on its way to the ferry");
        return true;
    }

    /** What the ferryman is doing, in its own words (FolkTalk). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        Crossing c = id == null ? null : crossing(id);
        if (c == null || c.state != State.RUNNING) {
            return "There's no ferry to row just now. I'll be given another trade, I expect.";
        }
        if (f.level() instanceof ServerLevel level && badWeather(level)) {
            return FolkTalk.pick(r, "No crossings in this weather. The ferry waits for the sky to clear.",
                "Too wet to go out. Ring again when the rain stops.");
        }
        Voyage voy = VOYAGES.get(id);
        if (voy != null) return "Rowing across to the " + (voy.to == 0 ? "town's" : "far") + " landing. Sit still!";
        return FolkTalk.pick(r, "Waiting at the landing. A coin a crossing; ring the bell if I'm on the far side.",
            "Minding the ferry. " + c.crossings + " crossings so far" + (c.fares > 0 ? ", " + c.fares + " coins in fares" : "") + ".",
            "Sat in the boat, waiting for somebody who needs to cross.");
    }

    // ------------------------------------------------------------------ crossing the water

    /** A crossing being rowed: from which landing to which, and how far across. */
    static final class Voyage {
        final int from, to;
        double done;
        final double length;
        @Nullable UUID passenger;
        boolean player;

        Voyage(int from, int to, double length) {
            this.from = from;
            this.to = to;
            this.length = length;
        }
    }

    /** Every tick (Transport): each running ferry's boat rowed across, or kept at its landing and set off when it should be. */
    public static void tick(ServerLevel level) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Crossing c = CROSSINGS.get(v.id());
            if (c == null || c.state != State.RUNNING || c.boat == null) continue;
            Boat boat = boat(level, c);
            if (boat == null) continue;
            Voyage voy = VOYAGES.get(v.id());
            if (voy != null) row(level, v, c, boat, voy);
            else if (level.getGameTime() % 5 == 0) idle(level, v, c, boat);
        }
    }

    /** The boat at its landing: kept there, nobody aboard who should not be, and away when there is a fare. */
    private static void idle(ServerLevel level, Villages.Village v, Crossing c, Boat boat) {
        VillageFolkEntity man = ferrymanOf(level, c);
        Entity first = boat.getFirstPassenger();
        boolean manAboard = man != null && first == man;
        for (Entity p : List.copyOf(boat.getPassengers())) {
            if (p instanceof Player || p == man) continue;
            if (p instanceof VillageFolkEntity f && PASSAGES.containsKey(f.getUUID())) continue;
            p.stopRiding();
        }
        if (first instanceof Player) return;                            // a player has the oars: its own crossing
        BlockPos spot = c.boatSpot(c.boatAt);
        if (boat.distanceToSqr(spot.getX() + 0.5, boat.getY(), spot.getZ() + 0.5) > 2.5 * 2.5) {
            boat.setDeltaMovement((spot.getX() + 0.5 - boat.getX()) * 0.2, boat.getDeltaMovement().y, (spot.getZ() + 0.5 - boat.getZ()) * 0.2);
        }
        if (!manAboard || badWeather(level)) return;
        // A passenger aboard behind the ferryman: across.
        for (Entity p : boat.getPassengers()) {
            if (p == man) continue;
            if (p instanceof Player || p instanceof VillageFolkEntity) {
                start(level, v, c, boat, 1 - c.boatAt, p);
                return;
            }
        }
        // Somebody waiting at the other landing, or the bell rung there: over, empty, to fetch them.
        int other = 1 - c.boatAt;
        long[] called = CALLED.get(v.id());
        boolean bell = called != null && called[0] == other && level.getGameTime() < called[1];
        boolean waiting = false;
        for (Map.Entry<UUID, Passage> e : PASSAGES.entrySet()) {
            if (e.getValue().village.equals(v.id()) && e.getValue().from == other) { waiting = true; break; }
        }
        if (bell || waiting) start(level, v, c, boat, other, null);
    }

    private static void start(ServerLevel level, Villages.Village v, Crossing c, Boat boat, int to, @Nullable Entity passenger) {
        double len = Math.sqrt(c.boatSpot(0).distSqr(c.boatSpot(1)));
        Voyage voy = new Voyage(c.boatAt, to, Math.max(1.0, len));
        if (passenger != null) {
            voy.passenger = passenger.getUUID();
            voy.player = passenger instanceof Player;
        }
        VOYAGES.put(v.id(), voy);
        boat.setPaddleState(true, true);
        long[] called = CALLED.get(v.id());
        if (called != null && called[0] == to) CALLED.remove(v.id());
    }

    /** One tick of a crossing: the boat pulled along the straight way across, set right if the water has carried it off. */
    private static void row(ServerLevel level, Villages.Village v, Crossing c, Boat boat, Voyage voy) {
        BlockPos a = c.boatSpot(voy.from), b = c.boatSpot(voy.to);
        voy.done = Math.min(voy.length, voy.done + PACE);
        double t = voy.done / voy.length;
        double x = a.getX() + 0.5 + (b.getX() - a.getX()) * t, z = a.getZ() + 0.5 + (b.getZ() - a.getZ()) * t;
        double dx = x - boat.getX(), dz = z - boat.getZ();
        if (dx * dx + dz * dz > 1.5 * 1.5) {
            boat.setPos(x, boat.getY(), z);
            boat.setDeltaMovement(0.0, boat.getDeltaMovement().y, 0.0);
        } else {
            boat.setDeltaMovement(dx * 0.6, boat.getDeltaMovement().y, dz * 0.6);
        }
        Direction toward = voy.to == 1 ? c.way : c.way.getOpposite();
        boat.setYRot(toward.toYRot());
        boat.setPaddleState(true, true);
        if (voy.done < voy.length) return;
        // Across.
        VOYAGES.remove(v.id());
        boat.setPos(b.getX() + 0.5, boat.getY(), b.getZ() + 0.5);
        boat.setDeltaMovement(Vec3.ZERO);
        boat.setPaddleState(false, false);
        c.boatAt = voy.to;
        c.crossings++;
        VillageFolkEntity man = ferrymanOf(level, c);
        if (voy.passenger != null) {
            Entity p = level.getEntity(voy.passenger);
            if (p != null && p.getVehicle() == boat) land(level, v, c, boat, man, p, voy.to);
        }
        if (man != null && c.crossings % 25 == 0) {
            FolkTalk.speak(man, FolkTalk.pick(level.getRandom(), "That's " + c.crossings + " crossings now. My arms know it.",
                c.crossings + " times across. The river's an old friend."));
        }
        save(v.id(), c);
    }

    /** A passenger set down on the landing's planks, its fare paid (a coin from its purse into the ferryman's). */
    private static void land(ServerLevel level, Villages.Village v, Crossing c, Boat boat, @Nullable VillageFolkEntity man, Entity p, int end) {
        p.stopRiding();
        BlockPos s = c.stand(end);
        p.teleportTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
        if (p instanceof VillageFolkEntity f) {
            Passage passage = PASSAGES.remove(f.getUUID());
            boolean paid = man != null && f.spend(FARE);
            if (paid) {
                man.earn(FARE);
                c.fares += FARE;
            } else {
                c.free++;
            }
            f.brain(paid ? "crossed on the ferry, a coin to the ferryman" : "crossed on the ferry: no coin, carried all the same");
            if (c.crossings <= 1 || level.getRandom().nextInt(6) == 0) {
                FolkTalk.speak(f, paid ? FolkTalk.pick(level.getRandom(), "Thank you kindly. Here's your coin.", "Dry feet! Worth every coin.",
                    "Same time tomorrow, then.") : "I've no coin on me. I'll owe you one.");
            }
            if (passage != null && passage.toWork) f.brain("on the far bank: off to its work");
        } else if (p instanceof Player player) {
            c.players++;
            boolean paid = man != null && payCoin(player);
            if (paid) {
                man.earn(FARE);
                c.fares += FARE;
            } else {
                c.free++;
            }
            if (man != null) FolkTalk.speak(man, paid ? FolkTalk.pick(level.getRandom(), "Here we are. Thank you for the coin.",
                "Mind the step. That's a coin, thank you.") : "Here we are. No coin? Next time, then.");
        }
    }

    /** A village coin out of a player's pockets, for the fare. */
    static boolean payCoin(Player p) {
        net.minecraft.world.entity.player.Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && Market.isCoin(s)) {
                s.shrink(FARE);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the bell

    /**
     * The ferry bell rung (FerryBellBlock, by a player or a folk waiting): the ferry called to that landing, and it
     * comes over if it is on the other side. What the bell's ringer is told.
     */
    public static String rung(ServerLevel level, BlockPos at) {
        level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 0.7F, 1.4F);
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Crossing c = crossing(v.id());
            if (c == null) continue;
            for (int end = 0; end <= 1; end++) {
                if (c.bell(end).distSqr(at) > 9 && c.stand(end).distSqr(at) > 36) continue;
                if (c.state != State.RUNNING) return c.state == State.RETIRED ? "The ferry's retired: use the bridge." : "The ferry isn't running yet.";
                if (badWeather(level)) return "Nobody's rowing in this weather.";
                if (c.boatAt == end && !VOYAGES.containsKey(v.id())) return "The ferry's here: step into the boat behind the ferryman. A coin a crossing.";
                CALLED.put(v.id(), new long[]{ end, level.getGameTime() + 1200L });
                return "The ferry's coming over.";
            }
        }
        return "The bell rings out over the water.";
    }

    // ------------------------------------------------------------------ passengers

    /** A folk's crossing: from which landing, why, and how far it has got. */
    static final class Passage {
        final UUID village;
        final int from;
        final boolean toWork;
        int stage;                      // 0 walking to the landing, 1 waiting on it, 2 aboard
        long since;
        int walkTick = -1000;
        boolean rang;

        Passage(UUID village, int from, boolean toWork) {
            this.village = village;
            this.from = from;
            this.toWork = toWork;
        }
    }

    /** Is this folk sitting in the ferry, its own crossing or its rowing on its hours (its day waits)? */
    public static boolean aboard(VillageFolkEntity f) {
        if (!(f.getVehicle() instanceof Boat b) || !b.getTags().contains(TAG)) return false;
        if (f.stationTask() == AssistantEntity.StationTask.FERRY) {
            return f.level() instanceof ServerLevel level && (onDuty(f, level) || (f.ownerId() != null && VOYAGES.containsKey(f.ownerId())));
        }
        return PASSAGES.containsKey(f.getUUID());
    }

    /** The ferryman's day over (or the ferry gone): out of the boat onto the landing it lies at, the boat left moored. */
    private static void ashore(VillageFolkEntity f, ServerLevel level) {
        if (!(f.getVehicle() instanceof Boat b) || !b.getTags().contains(TAG)) return;
        UUID id = f.ownerId();
        if (id != null && VOYAGES.containsKey(id)) return;                // a crossing is finished first
        Crossing c = id == null ? null : crossing(id);
        f.stopRiding();
        if (c != null) {
            BlockPos s = c.stand(c.boatAt);
            f.moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, f.getYRot(), 0.0F);
        }
    }

    /** May this folk get into this boat: the ferryman into the ferry, a passenger whose crossing it is? (Aboard.) */
    public static boolean mayBoard(VillageFolkEntity f, Entity vehicle) {
        if (!(vehicle instanceof Boat b) || !b.getTags().contains(TAG)) return false;
        if (f.stationTask() == AssistantEntity.StationTask.FERRY) return true;
        return PASSAGES.containsKey(f.getUUID());
    }

    public static boolean busy(VillageFolkEntity f) {
        if (PASSAGES.containsKey(f.getUUID())) return true;
        return f.level() instanceof ServerLevel level && f.stationTask() == AssistantEntity.StationTask.FERRY && onDuty(f, level)
            && f.ownerId() != null && running(f.ownerId());
    }

    /** What a passenger is doing, for its card; null if it has no crossing. */
    @Nullable
    public static String passing(VillageFolkEntity f) {
        Passage p = PASSAGES.get(f.getUUID());
        if (p == null) return null;
        return switch (p.stage) {
            case 0 -> "walking to the ferry";
            case 1 -> "waiting at the landing for the ferry";
            default -> "crossing on the ferry";
        };
    }

    /**
     * From the folk's tick: the ferryman at its ferry; a passenger to the landing, waiting there and into the boat; and
     * now and then, whether this folk needs to cross. True while it is about the ferry.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() == AssistantEntity.StationTask.FERRY) {
            if (duty(f, level)) return true;
            ashore(f, level);
            return false;
        }
        Passage p = PASSAGES.get(f.getUUID());
        if (p == null) {
            if (f.tickCount % 40 != 21) return false;
            consider(f, level);
            p = PASSAGES.get(f.getUUID());
            if (p == null) return false;
        }
        return pass(f, level, p);
    }

    /**
     * Does this folk need the ferry? Of a working morning, one in the town whose work is over the water; at the day's
     * end, one on the far side whose home is the town's side. Not in foul weather (it finds its own way round).
     */
    private static void consider(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.isPassenger() || f.trip() != null || f.expedition() != null) return;
        Crossing c = crossing(id);
        if (c == null || c.state != State.RUNNING || badWeather(level) || ferrymanOf(level, c) == null) return;
        long now = level.getGameTime();
        if (NEXT_LOOK.getOrDefault(f.getUUID(), 0L) > now) return;
        WorkZone z = f.workZone();
        if (z == null) return;
        Villages.Village v = Villages.get(id);
        if (v == null) return;
        BlockPos here = f.blockPosition();
        int sideNow = c.side(here), sideWork = c.side(z.center()), sideHome = c.side(v.centre());
        if (sideWork == sideHome || !c.near(z.center(), 96)) return;       // its work is on the town's side, or nowhere near
        long t = level.getDayTime() % 24000L;
        boolean working = f.onShift() && !f.offWorkNow();
        int reach = Villages.townReach(id) + 48;
        boolean inTown = Math.max(Math.abs(here.getX() - v.centre().getX()), Math.abs(here.getZ() - v.centre().getZ())) <= reach;
        if (working && t >= 1000L && t < 10000L && sideNow == sideHome && (inTown || c.near(here, 32)) && !z.containsColumn(here)) {
            book(f, level, id, sideNow, true);
        } else if ((!f.onShift() || t >= 11000L) && t < 12600L && sideNow == sideWork && (z.containsColumn(here) || c.near(here, 40))) {
            book(f, level, id, sideNow, false);
        }
    }

    private static void book(VillageFolkEntity f, ServerLevel level, UUID village, int from, boolean toWork) {
        Passage p = new Passage(village, from, toWork);
        p.since = level.getGameTime();
        PASSAGES.put(f.getUUID(), p);
        NEXT_LOOK.put(f.getUUID(), level.getGameTime() + 2400L);
        f.clearQueue();
        f.getNavigation().stop();
        f.brain(toWork ? "off to the ferry, its work over the water" : "off to the ferry, home over the water");
    }

    /** One step of a folk's crossing. True while it goes on. */
    private static boolean pass(VillageFolkEntity f, ServerLevel level, Passage p) {
        Crossing c = crossing(p.village);
        if (c == null || c.state != State.RUNNING || !f.isAlive()) {
            PASSAGES.remove(f.getUUID());
            return false;
        }
        Boat boat = boat(level, c);
        long now = level.getGameTime();
        BlockPos stand = c.stand(p.from);
        if (p.stage == 2) {
            if (boat == null || f.getVehicle() != boat) {
                PASSAGES.remove(f.getUUID());
                return false;
            }
            return true;
        }
        if (badWeather(level) && f.getVehicle() == null) {
            PASSAGES.remove(f.getUUID());
            f.brain("no ferry in this weather: found its own way");
            return false;
        }
        if (p.stage == 0) {
            if (f.blockPosition().distSqr(stand) <= 2 * 2 + 1) {
                p.stage = 1;
                p.since = now;
                return true;
            }
            if (now - p.since > WALK) {
                PASSAGES.remove(f.getUUID());
                f.brain("never got to the ferry: found its own way");
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - p.walkTick > 60) {
                f.walkTo(stand, 1.0D);
                p.walkTick = f.tickCount;
            }
            return true;
        }
        // Waiting on the landing.
        f.getNavigation().stop();
        if (boat != null) f.getLookControl().setLookAt(boat);
        VillageFolkEntity man = ferrymanOf(level, c);
        if (boat != null && man != null && c.boatAt == p.from && !VOYAGES.containsKey(p.village) && boat.getFirstPassenger() == man
                && boat.getPassengers().size() < 2 && f.distanceToSqr(boat) < 3.5 * 3.5) {
            if (f.startRiding(boat, true)) {
                p.stage = 2;
                p.since = now;
                return true;
            }
        }
        if (!p.rang && c.boatAt != p.from && now - p.since > 40L) {
            // Rings the bell for it.
            p.rang = true;
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            rung(level, c.bell(p.from));
        }
        if (now - p.since > WAIT) {
            PASSAGES.remove(f.getUUID());
            f.brain("waited for the ferry long enough: found its own way");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the end of the ferry

    /**
     * The bridge is open: the ferry rows its last crossing. The boat goes back into the stores, the ferryman takes up
     * fishing on the same water if the town wants a fisher, or another trade the town gives it; the landings stay, for
     * the fishers and the children. The chronicle has it.
     */
    static void retire(ServerLevel level, Villages.Village v, Crossing c) {
        if (c.state == State.RETIRED) return;
        VOYAGES.remove(v.id());
        Boat boat = boat(level, c);
        VillageFolkEntity man = ferrymanOf(level, c);
        if (boat != null) {
            for (Entity p : List.copyOf(boat.getPassengers())) p.stopRiding();
            boat.discard();
            Crafts.store(level, v, new ItemStack(Items.SPRUCE_BOAT));
        }
        for (UUID u : List.copyOf(PASSAGES.keySet())) if (PASSAGES.get(u).village.equals(v.id())) PASSAGES.remove(u);
        long day = level.getDayTime() / 24000L;
        c.state = State.RETIRED;
        c.retiredDay = day;
        c.boat = null;
        String next = "";
        if (man != null) {
            if (Villages.wants(v.id(), AssistantEntity.StationTask.FISH) && Villages.share(v.id(), AssistantEntity.StationTask.FISH) < 0) {
                man.setStation(c.stand(0), AssistantEntity.StationTask.FISH);
                man.assignPlot(WorkZone.around(c.root(0), 6, WorkZone.DEFAULT_DEPTH), "The Old Ferry");
                next = "fishing off the old landing";
            } else {
                man.setWorkZone(null);
                next = "a new trade, as the town wants it";
            }
            man.persona().remember(day, "I rowed the ferry's last crossing; the bridge carries everybody now", 5);
            FolkTalk.speak(man, FolkTalk.pick(level.getRandom(), "That's the last crossing, then. I'll miss the old boat.",
                "A bridge! Well, my arms won't miss the oars.", c.crossings + " crossings, and now a bridge. Time for something new."));
        }
        c.ferryman = null;
        save(v.id(), c);
        Villages.tell(v.id(), day, "the ferry rowed its last crossing after " + c.crossings + " crossings and " + c.fares + " coins in fares"
            + (man == null ? "" : "; " + man.displayNameCap() + " the ferryman took up " + next));
        LOG.info("[MCA-FERRY] {}: the ferry retired after {} crossings", Villages.name(v.id()), c.crossings);
    }

    // ------------------------------------------------------------------ the books, the tests, the showcase

    /** The ferry in a line or two. */
    public static List<String> report(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Crossing c = crossing(village);
        if (c == null) return out;
        VillageFolkEntity man = ferrymanOf(level, c);
        StringBuilder sb = new StringBuilder("The ferry to ").append(c.toWhat).append(" at ").append(c.where()).append(", ")
            .append(c.width).append(" blocks of water: ").append(c.state.words);
        if (c.state == State.RUNNING) sb.append(man == null ? "; no ferryman yet" : "; " + man.displayNameCap() + " the ferryman")
            .append(level instanceof ServerLevel && badWeather(level) ? "; stopped for the weather" : "");
        sb.append(". ").append(c.crossings).append(" crossings, ").append(c.fares).append(" coins in fares");
        if (c.free > 0) sb.append(", ").append(c.free).append(" carried for nothing");
        if (c.players > 0) sb.append(", ").append(c.players).append(" travellers from away");
        out.add(sb.append('.').toString());
        return out;
    }

    /** Tests: look for the crossing now. */
    @Nullable
    public static Crossing surveyForTests(ServerLevel level, Villages.Village v) {
        Crossing c = crossing(v.id());
        return c != null ? c : survey(level, v);
    }

    /** Tests: build the landings and moor the boat (out of the stores; the works done at once). */
    public static void buildForTests(ServerLevel level, Villages.Village v) {
        Crossing c = crossing(v.id());
        if (c != null) for (int i = 0; i < 4 && c.state != State.RUNNING; i++) build(level, v, c, false);
    }

    /** Tests: this folk the ferryman now. */
    public static void appointForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Crossing c = crossing(v.id());
        if (c != null) take(level, v, c, f, f.stationTask());
    }

    /** Tests: this folk wants to cross, from its side, now. */
    public static void bookForTests(VillageFolkEntity f, ServerLevel level) {
        Crossing c = f.ownerId() == null ? null : crossing(f.ownerId());
        if (c != null) book(f, level, f.ownerId(), c.side(f.blockPosition()), true);
    }

    /** Tests: is this folk on its way across, and at what stage (-1: not)? */
    public static int passageForTests(VillageFolkEntity f) {
        Passage p = PASSAGES.get(f.getUUID());
        return p == null ? -1 : p.stage;
    }

    /** Tests: the boat. */
    @Nullable
    public static Boat boatForTests(ServerLevel level, UUID village) {
        Crossing c = crossing(village);
        return c == null ? null : boat(level, c);
    }

    /** The showcase: the crossing's landings and boat for nothing. */
    public static void buildFreeForStage(ServerLevel level, Villages.Village v, Crossing c) {
        for (int end = 0; end <= 1; end++) landing(level, v, c, end, true);
        c.landings = 3;
        c.state = State.BUILDING;
        moor(level, v, c, true);
        c.state = State.RUNNING;
        c.startedDay = level.getDayTime() / 24000L;
        save(v.id(), c);
    }

    /** Tests and the showcase: a crossing set down where it is wanted. */
    public static Crossing setForTests(UUID village, BlockPos bankA, BlockPos bankB, Direction way, int surface, String toWhat) {
        Crossing c = new Crossing();
        c.bankA = bankA;
        c.bankB = bankB;
        c.way = way;
        c.surface = surface;
        c.width = (int) Math.round(Math.sqrt(bankA.atY(0).distSqr(bankB.atY(0)))) - 1;
        c.jetty = Math.max(1, Math.min(3, (c.width - 3) / 2));
        c.toWhat = toWhat;
        save(village, c);
        return c;
    }
}
