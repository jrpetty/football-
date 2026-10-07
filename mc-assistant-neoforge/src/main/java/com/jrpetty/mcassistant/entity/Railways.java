package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [transport] The town's railways. From the Iron Age a town whose mine lies far out lays a line of rails to it, and
 * later one to a neighbour it has a trade pact with.
 * <ul>
 * <li><b>The way.</b> The line leaves from a station on the avenue that points toward where it goes, just out past the
 *     ring street (the storehouse's lots face the square there), runs down the avenue to the edge of the town and then
 *     across the land in two or three long straight runs, the turns flat. Every way the land allows is weighed (along
 *     the avenue's line first, or across first, or turning half way) and the one with the least digging and banking
 *     taken. Off the avenue it keeps off the fields, the pens, the woods and the town's buildings, out of other towns,
 *     out of lava, and a couple of blocks clear of any other line.</li>
 * <li><b>The rise and fall.</b> The rail keeps as near the ground as a cart can take it, a block up or down a step at
 *     most and level through every turn and along the platforms: a bank of the stores' cobblestone over a dip, a
 *     trestle on posts over a deep one, a deck of planks over water, and a cutting through a bump, the earth and stone
 *     dug out going home to the stores. Too deep a cutting, too high a trestle or too wide a water, and that way is
 *     not taken.</li>
 * <li><b>The rails.</b> Plain rails, a pair of powered rails every ten blocks on the flat and on every slope, each run
 *     lit by a redstone torch beside it; at each station the four rails by the buffer are powered rails worked by a
 *     lever on the buffer, which brake a cart coming in and set it off going out. The rails, the powered rails, the
 *     torches and the levers are all the smith's work (Crafts.smith asks here), made at the bench from the stores' own
 *     iron, gold, redstone and sticks by the game's recipes (Bench), and laid by a hand at the town's works
 *     (TownJobs), a few rails a visit, as far as the stores run.</li>
 * <li><b>The stations.</b> A platform, a roof on posts, a lantern hung under it and a signpost (the station drawing,
 *     blueprints/station.txt), with the buffer stop at the end of the track; built out of the stores like the rest.</li>
 * <li><b>Upkeep.</b> An open line is walked a stretch at a time, and a rail, a torch or a bank gone missing is put back
 *     by the town's works, out of the stores.</li>
 * </ul>
 * The ore carts and the riders are RailCarts'. The ground under a line is kept off the builders' lots
 * (Villages.lotKeptOff) and the miners' faces (TownMine.builtGround).
 */
public final class Railways {

    private Railways() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ------------------------------------------------------------------ the numbers

    /** The station's track: seven rails along its platform, the buffer stop beyond the last. */
    static final int PLATFORM = 7;
    /**
     * The rails by each buffer that are the lever's powered rails: unpowered they brake a cart coming in (to a stand in a
     * block or two, so one coming in behind a cart already standing there stops before it runs into it); powered they
     * set it off going out.
     */
    static final int BRAKES = 4;
    /** Where the town's station starts along its avenue: just out past the ring street, by the trades' lots. */
    static final int STATION_OUT = TownPlan.RING + TownPlan.STREET + 1;
    /** The rail runs a block to the right of the avenue's middle; its platform takes the middle and the block left of it. */
    static final int RAIL_SIDE = 1;
    /** A mine nearer the town's station than this is walked to. */
    static final int FAR = 64;
    /** The fewest folk a town lays a railway for. */
    static final int LEAST_TOWN = 10;
    /** The mine's station stands this far short of the mine's first face, on the town's side of it. */
    static final int SHORT_OF_THE_MINE = 22;
    /** A pair of powered rails every so many on the flat. */
    static final int BOOST_EVERY = 10;
    /** The deepest cutting, the highest bank or trestle, and the widest water a line takes. */
    static final int MOST_CUT = 7, MOST_FILL = 12, MOST_WATER = 24;
    /** A bank of cobblestone this high at most; higher, a trestle on posts. */
    static final int BANK = 3;
    /** The longest line a town lays to a neighbour. */
    static final int LONGEST_LINK = 420;
    /** Rails laid a visit, and a visit every so often (game ticks). */
    static final int STEPS = 8;
    static final long EVERY = 200L;
    /** How far apart two lines keep. */
    static final int APART = 2;
    /** Spare rails the stores keep for mending, and powered rails. */
    static final int SPARE = 4, SPARE_POWERED = 2;

    public enum Kind { MINE, LINK }

    public enum State {
        PLANNED("planned"), LAYING("being laid"), OPEN("open");

        public final String words;

        State(String words) { this.words = words; }
    }

    /** What a rail is: plain, powered (the first of a run wants a torch), or a station's lever-worked rail. */
    static final char PLAIN = 'r', BOOST = 'p', BOOSTED = 'q', STATION = 's';

    /**
     * One line, as this town has it: its rails in order from this town's station (rail 0 lies against this station's
     * buffer stop), what each is, and how far this town has laid it. A line to a neighbour is the same rails the
     * other way round in the neighbour's books; each town lays its own half, from its own station out.
     */
    public static final class Line {
        final String key;
        final Kind kind;
        @Nullable final UUID other;
        final List<BlockPos> rails;
        final char[] kinds;
        /** This town lays [0, split); a mine line all of it. */
        final int split;
        int laid;
        State state = State.PLANNED;
        /** Bit 1: this town's station built; bit 2: the far station (the mine's) built. */
        int stations;
        long plannedDay = -1, openedDay = -1;
        /** What the laying is held up on just now, in words; empty when it is going. */
        String waiting = "";
        int mendCursor;

        Line(String key, Kind kind, @Nullable UUID other, List<BlockPos> rails, char[] kinds, int split) {
            this.key = key;
            this.kind = kind;
            this.other = other;
            this.rails = rails;
            this.kinds = kinds;
            this.split = split;
        }

        public int length() { return rails.size(); }
        public State state() { return state; }
        public Kind kind() { return kind; }
        @Nullable public UUID other() { return other; }
        public String key() { return key; }
        public BlockPos rail(int i) { return rails.get(Math.max(0, Math.min(rails.size() - 1, i))); }
        public List<BlockPos> rails() { return rails; }

        /** The way out from rail i toward the far end, flat. */
        Direction outAt(int i) {
            int j = Math.min(rails.size() - 1, i + 1), k = j == i ? i - 1 : i;
            BlockPos a = rails.get(k), b = rails.get(j);
            return Direction.getNearest(b.getX() - a.getX(), 0, b.getZ() - a.getZ());
        }

        /** Toward the buffer at this end (0: this town's station, 1: the far one). */
        Direction facing(int end) {
            return end == 0 ? outAt(0).getOpposite() : outAt(rails.size() - 2);
        }

        /** The buffer stop at this end: the block beyond the last rail. */
        BlockPos buffer(int end) {
            return end == 0 ? rails.get(0).relative(facing(0)) : rails.get(rails.size() - 1).relative(facing(1));
        }

        /** The middle rail of the platform at this end: the station drawing's anchor. */
        BlockPos stationAnchor(int end) {
            return end == 0 ? rails.get(PLATFORM / 2) : rails.get(rails.size() - 1 - PLATFORM / 2);
        }

        /** Where a folk stands on the platform at this end to get into a cart by the buffer. */
        public BlockPos platform(int end) {
            BlockPos r = end == 0 ? rails.get(1) : rails.get(rails.size() - 2);
            return r.relative(facing(end).getClockWise());
        }

        /** The rails this town lays: all of a mine line, its own half of a line to a neighbour. */
        int own() { return kind == Kind.MINE ? rails.size() : split; }

        boolean laidAll() { return laid >= own(); }

        /** Is this town's part of the line down, and its station (and for a mine line, the mine's) up? */
        boolean ownDone() { return laidAll() && (stations & 1) != 0 && (kind == Kind.LINK || (stations & 2) != 0); }

        public String name() {
            return kind == Kind.MINE ? "the line to the mine" : "the line to " + (other == null ? "the neighbours" : Villages.name(other));
        }
    }

    /** Each village's lines, by key ("mine", "link/<town>"), read once out of the ledger. */
    private static final Map<UUID, Map<String, Line>> LINES = new ConcurrentHashMap<>();
    /** When each village last had its lines looked over. */
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** A plan waiting on the ground to be loaded: tries so far, by village and key. */
    private static final Map<String, Integer> TRIES = new ConcurrentHashMap<>();
    /** The ground kept awake at the laying head of each line, by village and key. */
    private static final Map<String, BlockPos> WINDOW = new ConcurrentHashMap<>();
    /** The laying waits on the smith, as the smith last found the stores (for the books). */
    private static final Map<String, String> SHORT = new ConcurrentHashMap<>();
    /** Tests: the lines planned even with the iron short. */
    private static volatile boolean planAnyway;

    public static void resetForTests() {
        LINES.clear();
        LAST.clear();
        TRIES.clear();
        WINDOW.clear();
        SHORT.clear();
        planAnyway = false;
    }

    /** Tests: plan a line whatever the stores hold (the test fills them after). */
    public static void planAnywayForTests(boolean on) {
        planAnyway = on;
    }

    // ------------------------------------------------------------------ the books

    /** This village's lines, read out of the ledger the first time they are asked for. */
    public static Map<String, Line> lines(UUID village) {
        return LINES.computeIfAbsent(village, Railways::load);
    }

    @Nullable
    public static Line line(UUID village, String key) {
        return lines(village).get(key);
    }

    @Nullable
    public static Line mineLine(UUID village) {
        return line(village, "mine");
    }

    /** The line between these two towns, as the first has it; null if there is none. */
    @Nullable
    public static Line linkTo(UUID village, UUID other) {
        return line(village, "link/" + other);
    }

    /** Is the line between these two open both ways (both halves laid, both stations up)? */
    public static boolean linkOpen(UUID a, UUID b) {
        Line la = linkTo(a, b), lb = linkTo(b, a);
        return la != null && lb != null && la.state == State.OPEN && lb.state == State.OPEN;
    }

    private static Map<String, Line> load(UUID village) {
        Map<String, Line> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith("rail/line/")) continue;
            Line l = decode(e.getKey().substring("rail/line/".length()), e.getValue());
            if (l != null) out.put(l.key, l);
        }
        return new ConcurrentHashMap<>(out);
    }

    static void save(UUID village, Line l) {
        Ledger.note(village, "rail/line/" + l.key, encode(l));
    }

    static void forget(UUID village, String key) {
        lines(village).remove(key);
        Ledger.forget(village, "rail/line/" + key);
    }

    /** "v1|kind|other|state|laid|split|stations|planned|opened|x,y,z,k;x,y,z,k;..." */
    static String encode(Line l) {
        StringBuilder sb = new StringBuilder("v1|").append(l.kind.name()).append('|').append(l.other == null ? "-" : l.other.toString())
            .append('|').append(l.state.name()).append('|').append(l.laid).append('|').append(l.split).append('|').append(l.stations)
            .append('|').append(l.plannedDay).append('|').append(l.openedDay).append('|');
        for (int i = 0; i < l.rails.size(); i++) {
            BlockPos p = l.rails.get(i);
            if (i > 0) sb.append(';');
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(',').append(l.kinds[i]);
        }
        return sb.toString();
    }

    @Nullable
    static Line decode(String key, @Nullable String s) {
        if (s == null || !s.startsWith("v1|")) return null;
        try {
            String[] f = s.split("\\|", -1);
            Kind kind = Kind.valueOf(f[1]);
            UUID other = "-".equals(f[2]) ? null : UUID.fromString(f[2]);
            String[] cells = f[9].isEmpty() ? new String[0] : f[9].split(";");
            List<BlockPos> rails = new ArrayList<>(cells.length);
            char[] kinds = new char[cells.length];
            for (int i = 0; i < cells.length; i++) {
                String[] c = cells[i].split(",");
                rails.add(new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
                kinds[i] = c[3].charAt(0);
            }
            if (rails.size() < PLATFORM * 2 + 2) return null;
            Line l = new Line(key, kind, other, rails, kinds, Integer.parseInt(f[5]));
            l.state = State.valueOf(f[3]);
            l.laid = Integer.parseInt(f[4]);
            l.stations = Integer.parseInt(f[6]);
            l.plannedDay = Long.parseLong(f[7]);
            l.openedDay = Long.parseLong(f[8]);
            return l;
        } catch (RuntimeException e) {
            LOG.warn("[MCA-RAIL] a damaged line in the books ({}): {}", key, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ the town's day

    /** Every ten seconds for each village (Transport): its lines planned, laid, opened and kept up. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LAST.getOrDefault(id, -100000L) < EVERY && now >= LAST.getOrDefault(id, -100000L)) return;
        LAST.put(id, now);
        work(level, v);
    }

    /** One visit's work on a village's lines, now. */
    static void work(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) return;
        long day = level.getDayTime() / 24000L;
        if (mineLine(id) == null && !triedToday(id, "mine", day) && wantsMineLine(level, v) && planMine(level, v, day) == null) {
            tried(id, "mine", day);
        }
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(id) || !o.dim().equals(v.dim())) continue;
            String key = "link/" + o.id();
            if (linkTo(id, o.id()) == null && !triedToday(id, key, day) && wantsLink(level, v, o) && planLink(level, v, o, day) == null) {
                tried(id, key, day);
            }
        }
        for (Line l : List.copyOf(lines(id).values())) {
            switch (l.state) {
                case PLANNED, LAYING -> {
                    if (l.state == State.PLANNED) {
                        l.state = State.LAYING;
                        save(id, l);
                    }
                    lay(level, v, l, STEPS);
                }
                case OPEN -> mend(level, v, l, 24);
            }
        }
    }

    /** A plan that would not do is not looked for again the same day (one waiting on the ground to load is). */
    private static boolean triedToday(UUID village, String key, long day) {
        return Long.toString(day).equals(Ledger.note(village, "rail/tried/" + key));
    }

    private static void tried(UUID village, String key, long day) {
        if (!TRIES.containsKey(village + "/" + key)) Ledger.note(village, "rail/tried/" + key, Long.toString(day));
    }

    /** Does the town want a line to its mine: the Iron Age, a mine far out, and the iron for the rails? */
    static boolean wantsMineLine(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos site = TownMine.siteOf(id);
        if (site == null) return false;
        if (!planAnyway && Villages.folkOf(id).size() < LEAST_TOWN) return false;     // a hamlet walks to its mine
        Direction out = avenueToward(v.centre(), site);
        BlockPos station = townStation(v.centre(), out);
        if (flat(station, site) < (double) FAR * FAR) return false;
        if (planAnyway) return true;
        // Never while the town is still putting iron by for its age: the smith would not spare it for rails.
        if (Crafts.savingIron(level, v)) return false;
        // The iron for the plain rails at least (six bars a sixteen), over what the smith keeps back.
        int rails = (int) Math.sqrt(flat(station, site)) + 24;
        int iron = (rails + 15) / 16 * 6;
        return Market.stock(level, id, s -> s.is(Items.IRON_INGOT)) >= iron + Crafts.IRON_KEPT + 8
            || Market.stock(level, id, s -> s.is(Items.RAIL)) >= rails / 2;
    }

    /** A line to a neighbour: both towns in the Iron Age with a trade pact, the mine's line open, and near enough. */
    static boolean wantsLink(ServerLevel level, Villages.Village v, Villages.Village o) {
        UUID a = v.id(), b = o.id();
        if (a.toString().compareTo(b.toString()) > 0) return false;      // the one plans it, for both
        if (Villages.ageOf(b).ordinal() < Villages.Age.IRON.ordinal()) return false;
        if (!Envoys.pact(a, b) || !Diplomacy.neighbours(v, o)) return false;
        if (Math.sqrt(flat(v.centre(), o.centre())) > LONGEST_LINK) return false;
        if (planAnyway) return true;
        Line mine = mineLine(a);
        if (mine == null || mine.state != State.OPEN) return false;      // "later": the mine's line first
        int rails = (int) Math.sqrt(flat(v.centre(), o.centre()));
        int iron = (rails / 2 + 15) / 16 * 6;
        return Market.stock(level, a, s -> s.is(Items.IRON_INGOT)) >= iron + Crafts.IRON_KEPT + 8
            && Market.stock(level, b, s -> s.is(Items.IRON_INGOT)) >= iron + Crafts.IRON_KEPT + 8;
    }

    // ------------------------------------------------------------------ where it runs

    /** The avenue (its way out from the heart) that points most nearly at this spot. */
    static Direction avenueToward(BlockPos heart, BlockPos to) {
        return Direction.getNearest(to.getX() - heart.getX(), 0, to.getZ() - heart.getZ());
    }

    /** The town's station's first rail on that avenue: the one by the buffer stop, y left at the heart's. */
    static BlockPos townStation(BlockPos heart, Direction out) {
        return heart.relative(out, STATION_OUT).relative(out.getClockWise(), RAIL_SIDE);
    }

    /** The rail on this avenue's line at so far out from the heart. */
    static BlockPos onAvenue(BlockPos heart, Direction out, int along) {
        return heart.relative(out, along).relative(out.getClockWise(), RAIL_SIDE);
    }

    private static double flat(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** The avenues this town's lines leave by already. */
    private static Set<Direction> avenuesInUse(UUID village, BlockPos heart) {
        Set<Direction> used = new HashSet<>();
        for (Line l : lines(village).values()) used.add(avenueToward(heart, l.rails.get(PLATFORM)));
        return used;
    }

    /** The best avenue toward a spot that no other line of the town leaves by; null if all are taken. */
    @Nullable
    private static Direction freeAvenue(UUID village, BlockPos heart, BlockPos to) {
        Set<Direction> used = avenuesInUse(village, heart);
        Direction best = null;
        double bestDot = -Double.MAX_VALUE;
        double dx = to.getX() - heart.getX(), dz = to.getZ() - heart.getZ();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (used.contains(d)) continue;
            double dot = d.getStepX() * dx + d.getStepZ() * dz;
            if (dot > bestDot) { bestDot = dot; best = d; }
        }
        return best;
    }

    /** One way a line could go: its cells (x, z), and where it turns. */
    record Way(List<int[]> cells, Set<Integer> corners) {}

    /** Cells from a to b in a straight line along an axis, a excluded, b included. */
    private static void run(List<int[]> out, int ax, int az, int bx, int bz) {
        int sx = Integer.signum(bx - ax), sz = Integer.signum(bz - az);
        int x = ax, z = az;
        while (x != bx || z != bz) {
            x += sx;
            z += sz;
            out.add(new int[]{ x, z });
        }
    }

    /**
     * The ways from s (heading ds) to t (arriving heading dt): straight, an L with one turn, or a Z with two (turning a
     * quarter, half and three quarters of the way, and as soon and as late as it may). Each leg is at least three long,
     * the last at least the station's platform and two. Cells after s, up to and including t.
     */
    static List<Way> ways(int[] s, Direction ds, int[] t, Direction dt, int lastLeg) {
        List<Way> out = new ArrayList<>();
        int fx = ds.getStepX(), fz = ds.getStepZ();
        int forward = (t[0] - s[0]) * fx + (t[1] - s[1]) * fz;
        if (ds == dt) {
            Direction lat = ds.getClockWise();
            int lateral = (t[0] - s[0]) * lat.getStepX() + (t[1] - s[1]) * lat.getStepZ();
            if (forward < lastLeg) return out;
            if (lateral == 0) {
                List<int[]> c = new ArrayList<>();
                run(c, s[0], s[1], t[0], t[1]);
                out.add(new Way(c, Set.of()));
                return out;
            }
            Set<Integer> pivots = new java.util.TreeSet<>();
            int most = forward - lastLeg;
            for (int p : new int[]{ 3, forward / 4, forward / 2, forward * 3 / 4, most }) if (p >= 3 && p <= most) pivots.add(p);
            for (int p : pivots) {
                List<int[]> c = new ArrayList<>();
                int ax = s[0] + fx * p, az = s[1] + fz * p;
                run(c, s[0], s[1], ax, az);
                int c1 = c.size() - 1;
                int bx = ax + lat.getStepX() * lateral, bz = az + lat.getStepZ() * lateral;
                run(c, ax, az, bx, bz);
                int c2 = c.size() - 1;
                run(c, bx, bz, t[0], t[1]);
                out.add(new Way(c, Set.of(c1, c2)));
            }
            return out;
        }
        if (ds.getAxis() == dt.getAxis()) return out;                        // a U: no line turns back on itself
        int along = (t[0] - s[0]) * dt.getStepX() + (t[1] - s[1]) * dt.getStepZ();
        if (forward < 3 || along < lastLeg) return out;
        List<int[]> c = new ArrayList<>();
        int ax = s[0] + fx * forward, az = s[1] + fz * forward;
        run(c, s[0], s[1], ax, az);
        int c1 = c.size() - 1;
        run(c, ax, az, t[0], t[1]);
        out.add(new Way(c, Set.of(c1)));
        return out;
    }

    // ------------------------------------------------------------------ the plan

    /** A line worked out on the ground: the rails, what each is, and what it costs to make the way. */
    record Plan(List<BlockPos> rails, char[] kinds, double cost, String note) {}

    /** The mine's line: from the town's station out along the avenue toward the mine, to a station short of the mine. */
    @Nullable
    static Line planMine(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        BlockPos site = TownMine.siteOf(id);
        if (site == null) return null;
        Direction out = freeAvenue(id, heart, site);
        if (out == null) return null;
        int exit = Villages.townReach(id) + 3;
        // The mine's station: short of the mine's first face, on the town's side, and out of the town.
        double dx = heart.getX() - site.getX(), dz = heart.getZ() - site.getZ();
        double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        List<Way> all = new ArrayList<>();
        List<int[]> starts = new ArrayList<>();
        List<Direction> facings = new ArrayList<>();
        for (int back = SHORT_OF_THE_MINE; back >= 12; back -= 5) {
            int mx = site.getX() + (int) Math.round(dx / len * back), mz = site.getZ() + (int) Math.round(dz / len * back);
            if (Math.max(Math.abs(mx - heart.getX()), Math.abs(mz - heart.getZ())) <= exit + 4) continue;
            for (Direction facing : towardFacings(mx, mz, site)) {
                // The station's track: its buffer end toward the mine; the line comes in at its other end.
                int ex = mx - facing.getStepX() * (PLATFORM / 2), ez = mz - facing.getStepZ() * (PLATFORM / 2);
                int bx = mx + facing.getStepX() * (PLATFORM / 2), bz = mz + facing.getStepZ() * (PLATFORM / 2);
                starts.add(new int[]{ ex, ez, bx, bz });
                facings.add(facing);
            }
        }
        BlockPos s0 = townStation(heart, out);
        BlockPos sx = onAvenue(heart, out, exit);
        List<int[]> head = new ArrayList<>();
        head.add(new int[]{ s0.getX(), s0.getZ() });
        run(head, s0.getX(), s0.getZ(), sx.getX(), sx.getZ());
        List<List<int[]>> routes = new ArrayList<>();
        List<Set<Integer>> turns = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int[] st = starts.get(i);
            for (Way w : ways(new int[]{ sx.getX(), sx.getZ() }, out, new int[]{ st[2], st[3] }, facings.get(i), PLATFORM + 3)) {
                List<int[]> cells = new ArrayList<>(head);
                cells.addAll(w.cells());
                Set<Integer> c = new HashSet<>();
                for (int k : w.corners()) c.add(k + head.size());
                routes.add(cells);
                turns.add(c);
            }
        }
        Plan best = choose(level, v, null, out, null, routes, turns, "mine");
        if (best == null) return null;
        Line l = new Line("mine", Kind.MINE, null, best.rails(), best.kinds(), best.rails().size());
        l.plannedDay = day;
        lines(id).put(l.key, l);
        save(id, l);
        Villages.tell(id, day, "the council resolved to lay a railway to the mine: " + l.length() + " blocks of rails from a station on the "
            + out.getName() + " avenue" + (best.note().isEmpty() ? "" : ", " + best.note()));
        LOG.info("[MCA-RAIL] {} planned the line to its mine: {} rails, {}", Villages.name(id), l.length(), best.note());
        return l;
    }

    /** The ways a station's track may lie to bring a line in toward this spot: along x or along z, toward it. */
    private static List<Direction> towardFacings(int x, int z, BlockPos to) {
        List<Direction> out = new ArrayList<>();
        int dx = to.getX() - x, dz = to.getZ() - z;
        if (dx != 0) out.add(dx > 0 ? Direction.EAST : Direction.WEST);
        if (dz != 0) out.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (Math.abs(dz) > Math.abs(dx) && out.size() == 2) java.util.Collections.reverse(out);
        return out;
    }

    /** The line between two towns: from a's station along its avenue, across, and in along b's avenue to b's station. */
    @Nullable
    static Line planLink(ServerLevel level, Villages.Village a, Villages.Village b, long day) {
        Direction outA = freeAvenue(a.id(), a.centre(), b.centre());
        Direction outB = freeAvenue(b.id(), b.centre(), a.centre());
        if (outA == null || outB == null) return null;
        int exitA = Villages.townReach(a.id()) + 3, exitB = Villages.townReach(b.id()) + 3;
        BlockPos s0 = townStation(a.centre(), outA), sx = onAvenue(a.centre(), outA, exitA);
        BlockPos t0 = townStation(b.centre(), outB), tx = onAvenue(b.centre(), outB, exitB);
        List<int[]> head = new ArrayList<>();
        head.add(new int[]{ s0.getX(), s0.getZ() });
        run(head, s0.getX(), s0.getZ(), sx.getX(), sx.getZ());
        List<int[]> tail = new ArrayList<>();
        run(tail, tx.getX(), tx.getZ(), t0.getX(), t0.getZ());
        List<List<int[]>> routes = new ArrayList<>();
        List<Set<Integer>> turns = new ArrayList<>();
        for (Way w : ways(new int[]{ sx.getX(), sx.getZ() }, outA, new int[]{ tx.getX(), tx.getZ() }, outB.getOpposite(), 3)) {
            List<int[]> cells = new ArrayList<>(head);
            cells.addAll(w.cells());
            cells.addAll(tail);
            Set<Integer> c = new HashSet<>();
            for (int k : w.corners()) c.add(k + head.size());
            routes.add(cells);
            turns.add(c);
        }
        Plan best = choose(level, a, b, outA, outB, routes, turns, "link/" + b.id());
        if (best == null) return null;
        int n = best.rails().size();
        Line la = new Line("link/" + b.id(), Kind.LINK, b.id(), best.rails(), best.kinds(), n / 2);
        List<BlockPos> back = new ArrayList<>(best.rails());
        java.util.Collections.reverse(back);
        char[] backKinds = new char[n];
        for (int i = 0; i < n; i++) backKinds[i] = best.kinds()[n - 1 - i];
        Line lb = new Line("link/" + a.id(), Kind.LINK, a.id(), back, backKinds, n - n / 2);
        la.plannedDay = lb.plannedDay = day;
        lines(a.id()).put(la.key, la);
        lines(b.id()).put(lb.key, lb);
        save(a.id(), la);
        save(b.id(), lb);
        Villages.tell(a.id(), day, "a railway to " + Villages.name(b.id()) + " was agreed with our trading partners: " + n
            + " blocks of rails, each town to lay its own half");
        Villages.tell(b.id(), day, "a railway to " + Villages.name(a.id()) + " was agreed with our trading partners: " + n
            + " blocks of rails, each town to lay its own half");
        LOG.info("[MCA-RAIL] {} and {} planned a line between them: {} rails, {}", Villages.name(a.id()), Villages.name(b.id()), n, best.note());
        return la;
    }

    /**
     * Of these ways, the one cheapest to make on the ground, its heights worked out; null if the ground under them is not
     * all known yet (it is asked for, and the plan made on a later visit) or none will do.
     */
    @Nullable
    private static Plan choose(ServerLevel level, Villages.Village v, @Nullable Villages.Village to, Direction outA,
                               @Nullable Direction outB, List<List<int[]>> routes, List<Set<Integer>> turns, String key) {
        if (routes.isEmpty()) return null;
        String tries = v.id() + "/" + key;
        UUID owner = UUID.nameUUIDFromBytes(("mca-rail-plan-" + tries).getBytes());
        // The ground under every way known first: kept awake while the plan is made.
        Set<Long> want = new HashSet<>();
        for (List<int[]> r : routes) for (int[] c : r) want.add(net.minecraft.world.level.ChunkPos.asLong(c[0] >> 4, c[1] >> 4));
        List<Long> missing = new ArrayList<>();
        for (long c : want) if (!level.hasChunk(net.minecraft.world.level.ChunkPos.getX(c), net.minecraft.world.level.ChunkPos.getZ(c))) missing.add(c);
        if (!missing.isEmpty()) {
            int n = TRIES.merge(tries, 1, Integer::sum);
            if (n > 30) {
                TRIES.remove(tries);
                for (long c : want) ChunkLoad.setLoaded(level, owner, new BlockPos(net.minecraft.world.level.ChunkPos.getX(c) << 4, 64,
                    net.minecraft.world.level.ChunkPos.getZ(c) << 4), 0, false);
                return null;
            }
            for (long c : missing) ChunkLoad.setLoaded(level, owner, new BlockPos((net.minecraft.world.level.ChunkPos.getX(c) << 4) + 8, 64,
                (net.minecraft.world.level.ChunkPos.getZ(c) << 4) + 8), 0, true);
            return null;
        }
        Plan best = null;
        String why = "";
        for (int i = 0; i < routes.size(); i++) {
            Plan p = profile(level, v, to, outA, outB, routes.get(i), turns.get(i));
            if (p == null) continue;
            if (p.cost() < 0) { why = p.note(); continue; }
            if (best == null || p.cost() < best.cost()) best = p;
        }
        TRIES.remove(tries);
        for (long c : want) ChunkLoad.setLoaded(level, owner, new BlockPos((net.minecraft.world.level.ChunkPos.getX(c) << 4) + 8, 64,
            (net.minecraft.world.level.ChunkPos.getZ(c) << 4) + 8), 0, false);
        if (best == null) {
            long day = level.getDayTime() / 24000L;
            String said = Ledger.note(v.id(), "rail/noway/" + key);
            if (said == null || !said.equals(Long.toString(day / 7))) {
                Ledger.note(v.id(), "rail/noway/" + key, Long.toString(day / 7));
                LOG.info("[MCA-RAIL] {}: no way for {} ({} ways looked at{})", Villages.name(v.id()), key, routes.size(), why.isEmpty() ? "" : ": " + why);
            }
        }
        return best;
    }

    /**
     * One way, measured on the ground: where it may not go, the height of each rail (as near the ground as a cart can
     * take, level through the turns and along the platforms, above any water), what is dug and banked, and what each
     * rail is. A negative cost, with why, if the way will not do.
     */
    @Nullable
    static Plan profile(ServerLevel level, Villages.Village v, @Nullable Villages.Village to, Direction outA,
                        @Nullable Direction outB, List<int[]> cells, Set<Integer> corners) {
        int n = cells.size();
        if (n < PLATFORM * 2 + 4) return new Plan(List.of(), new char[0], -1, "too short");
        UUID id = v.id();
        BlockPos heart = v.centre();
        long now = level.getGameTime();
        int[][] built = TownMine.builtGround(id, heart, now);
        int[][] builtThere = to == null ? null : TownMine.builtGround(to.id(), to.centre(), now);
        Set<Long> others = otherLines(level);
        int[] ground = new int[n];
        boolean[] water = new boolean[n];
        int waterRun = 0;
        for (int i = 0; i < n; i++) {
            int x = cells.get(i)[0], z = cells.get(i)[1];
            Roads.Ground g = Roads.ground(level, x, z);
            if (g == null) return new Plan(List.of(), new char[0], -1, "lava at " + x + ", " + z);
            ground[i] = g.y();
            water[i] = g.water();
            waterRun = water[i] ? waterRun + 1 : 0;
            if (waterRun > MOST_WATER) return new Plan(List.of(), new char[0], -1, "water too wide at " + x + ", " + z);
            String no = forbidden(level, v, to, outA, outB, x, z, built, builtThere, others);
            if (no != null) return new Plan(List.of(), new char[0], -1, no + " at " + x + ", " + z);
        }
        // Which rails must lie level with the one before: through every turn, and along each platform with the rail before it.
        boolean[] levelWithLast = new boolean[n];
        for (int c : corners) {
            if (c > 0) levelWithLast[c] = true;
            if (c + 1 < n) levelWithLast[c + 1] = true;
        }
        for (int i = 1; i <= PLATFORM + 1 && i < n; i++) levelWithLast[i] = true;
        for (int i = n - PLATFORM - 1; i < n; i++) if (i > 0) levelWithLast[i] = true;
        // The groups that lie level: their first and last cell, what they would like, and the least they may be.
        List<int[]> groups = new ArrayList<>();                        // {first, last}
        for (int i = 0; i < n; i++) {
            if (i > 0 && levelWithLast[i]) groups.get(groups.size() - 1)[1] = i;
            else groups.add(new int[]{ i, i });
        }
        int m = groups.size();
        double[] want = new double[m];
        int[] least = new int[m];
        for (int k = 0; k < m; k++) {
            int[] gr = groups.get(k);
            List<Integer> d = new ArrayList<>();
            int lo = Integer.MIN_VALUE;
            for (int i = gr[0]; i <= gr[1]; i++) {
                int rail = water[i] ? ground[i] + 2 : ground[i] + 1;
                d.add(rail);
                if (water[i]) lo = Math.max(lo, ground[i] + 2);
            }
            java.util.Collections.sort(d);
            want[k] = d.get(d.size() / 2);
            least[k] = lo;
        }
        // Rise and fall no more than a block a step: halfway between the highest it may be without a cutting anywhere
        // and the lowest without a bank anywhere (each a block a step), then lifted over the water.
        int[] at = new int[m];
        for (int k = 0; k < m; k++) {
            double lowAll = -Double.MAX_VALUE, highAll = Double.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                int dist = gap(groups, j, k);
                lowAll = Math.max(lowAll, want[j] - dist);
                highAll = Math.min(highAll, want[j] + dist);
            }
            at[k] = (int) Math.floor((lowAll + highAll) / 2.0);
        }
        for (int k = 0; k < m; k++) if (least[k] != Integer.MIN_VALUE) at[k] = Math.max(at[k], least[k]);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 1; k < m; k++) at[k] = Math.max(at[k], at[k - 1] - gap(groups, k - 1, k));
            for (int k = m - 2; k >= 0; k--) at[k] = Math.max(at[k], at[k + 1] - gap(groups, k, k + 1));
        }
        List<BlockPos> rails = new ArrayList<>(n);
        int[] y = new int[n];
        for (int k = 0; k < m; k++) for (int i = groups.get(k)[0]; i <= groups.get(k)[1]; i++) y[i] = at[k];
        double cost = 0;
        int dug = 0, banked = 0, bridged = 0, trestles = 0, slopes = 0;
        for (int i = 0; i < n; i++) {
            rails.add(new BlockPos(cells.get(i)[0], y[i], cells.get(i)[1]));
            int cut = water[i] ? 0 : Math.max(0, ground[i] + 1 - y[i]);
            int fill = Math.max(0, y[i] - 1 - ground[i]);
            if (cut > MOST_CUT) return new Plan(List.of(), new char[0], -1, "a cutting " + cut + " deep at " + cells.get(i)[0] + ", " + cells.get(i)[1]);
            if (fill > MOST_FILL) return new Plan(List.of(), new char[0], -1, "a trestle " + fill + " high at " + cells.get(i)[0] + ", " + cells.get(i)[1]);
            dug += cut;
            if (water[i]) bridged++;
            else if (fill > BANK) trestles++;
            else banked += fill;
            if (i > 0 && y[i] != y[i - 1]) slopes++;
            cost += 0.2 + cut * 1.0 + fill * 1.5 + (water[i] ? 3.0 : 0.0);
        }
        cost += corners.size() * 6.0;
        char[] kinds = kinds(rails, corners);
        List<String> note = new ArrayList<>();
        if (slopes > 0) note.add(slopes + " slopes");
        if (dug > 0) note.add("cuttings of " + dug + " blocks");
        if (banked > 0) note.add("banks of " + banked);
        if (trestles > 0) note.add(trestles + " on trestles");
        if (bridged > 0) note.add("a bridge of " + bridged + " over water");
        note.add(corners.size() == 0 ? "dead straight" : corners.size() + (corners.size() == 1 ? " turn" : " turns"));
        return new Plan(rails, kinds, cost, String.join(", ", note));
    }

    /** How far apart two level groups are, in steps. */
    private static int gap(List<int[]> groups, int a, int b) {
        if (a == b) return 0;
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return groups.get(hi)[0] - groups.get(lo)[1];
    }

    /** Why a line may not run over this column (null if it may): another town's ground, ours off the avenue, a field. */
    @Nullable
    private static String forbidden(ServerLevel level, Villages.Village v, @Nullable Villages.Village to, Direction outA,
                                    @Nullable Direction outB, int x, int z, int[][] built, @Nullable int[][] builtThere, Set<Long> others) {
        BlockPos p = new BlockPos(x, 0, z);
        if (others.contains(BlockPos.asLong(x, 0, z))) return "another line";
        for (Villages.Village w : Villages.every()) {
            if (!w.dim().equals(v.dim())) continue;
            int dx = x - w.centre().getX(), dz = z - w.centre().getZ();
            int reach = Villages.townReach(w.id());
            if (Math.max(Math.abs(dx), Math.abs(dz)) > reach + 2) continue;
            // In a town, only down the avenue its own line leaves by.
            Direction out = w.id().equals(v.id()) ? outA : to != null && w.id().equals(to.id()) ? outB : null;
            if (out == null) return "the town of " + Villages.name(w.id());
            Direction side = out.getClockWise();
            int along = dx * out.getStepX() + dz * out.getStepZ(), across = dx * side.getStepX() + dz * side.getStepZ();
            if (across != RAIL_SIDE || along < STATION_OUT - 1) return "the town's own ground";
            return null;                                     // the avenue is a street, for good: nothing is built on it
        }
        if (Villages.onFarmland(v.id(), v.centre(), p, 1)) return "the fields";
        if (TownMine.underTheTown(built, p, 1)) return "a building";
        if (builtThere != null && TownMine.underTheTown(builtThere, p, 1)) return "a building";
        for (Villages.Village w : new Villages.Village[]{ v, to }) {
            if (w == null) continue;
            for (AssistantEntity a : Villages.folkOf(w.id())) {
                AssistantEntity.StationTask t = a.stationTask();
                if (t != AssistantEntity.StationTask.FARM && t != AssistantEntity.StationTask.RANCH && t != AssistantEntity.StationTask.WOOD
                    && t != AssistantEntity.StationTask.BEEKEEP) continue;
                WorkZone wz = a.workZone();
                if (wz != null && Math.abs(wz.center().getX() - x) <= wz.radius() + 1 && Math.abs(wz.center().getZ() - z) <= wz.radius() + 1) {
                    return "a " + t.label + " plot";
                }
            }
        }
        return null;
    }

    /** Every column any town's lines run over, and the columns round them (the lines keep apart). */
    private static Set<Long> otherLines(ServerLevel level) {
        Set<Long> out = new HashSet<>();
        for (Villages.Village w : Villages.every()) {
            if (!w.dim().equals(level.dimension())) continue;
            for (Line l : lines(w.id()).values()) {
                for (BlockPos r : l.rails) {
                    for (int dx = -APART; dx <= APART; dx++) for (int dz = -APART; dz <= APART; dz++) out.add(BlockPos.asLong(r.getX() + dx, 0, r.getZ() + dz));
                }
            }
        }
        return out;
    }

    /**
     * What each rail is: at each end the four by the buffer the station's lever-worked powered rails; a pair of powered
     * rails every ten on the flat, and every rail of a slope and the one before it (a run of them lit by a torch every
     * eight); never on a turn, which no powered rail can take.
     */
    static char[] kinds(List<BlockPos> rails, Set<Integer> corners) {
        int n = rails.size();
        char[] k = new char[n];
        java.util.Arrays.fill(k, PLAIN);
        boolean[] powered = new boolean[n];
        for (int i = 1; i < n; i++) {
            if (rails.get(i).getY() != rails.get(i - 1).getY()) {
                powered[i] = true;
                powered[i - 1] = true;
            }
        }
        int sinceBoost = 0;
        for (int i = PLATFORM + 2; i < n - PLATFORM - 2; i++) {
            sinceBoost = powered[i] ? 0 : sinceBoost + 1;
            if (sinceBoost >= BOOST_EVERY && i + 1 < n - PLATFORM - 2) {
                powered[i] = true;
                powered[i + 1] = true;
                sinceBoost = 0;
            }
        }
        for (int c : corners) {
            powered[c] = false;
        }
        int run = 0;
        for (int i = 0; i < n; i++) {
            if (!powered[i] || i < 2 || i > n - 3) { run = 0; continue; }
            k[i] = run % 8 == 0 ? BOOST : BOOSTED;
            run++;
        }
        for (int i = 0; i < BRAKES; i++) {
            k[i] = STATION;
            k[n - 1 - i] = STATION;
        }
        return k;
    }

    // ------------------------------------------------------------------ the rails' shapes

    /** The shape of rail i, joining the rail before it to the one after it (the ends run on straight). */
    static RailShape shapeAt(List<BlockPos> rails, int i) {
        BlockPos c = rails.get(i);
        BlockPos a = i > 0 ? rails.get(i - 1) : null, b = i + 1 < rails.size() ? rails.get(i + 1) : null;
        if (a == null) a = c.offset(c.getX() - b.getX(), 0, c.getZ() - b.getZ());
        if (b == null) b = c.offset(c.getX() - a.getX(), 0, c.getZ() - a.getZ());
        Direction da = Direction.getNearest(a.getX() - c.getX(), 0, a.getZ() - c.getZ());
        Direction db = Direction.getNearest(b.getX() - c.getX(), 0, b.getZ() - c.getZ());
        if (b.getY() > c.getY()) return ascending(db);
        if (a.getY() > c.getY()) return ascending(da);
        if (da.getAxis() == db.getAxis()) return da.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        boolean n = da == Direction.NORTH || db == Direction.NORTH, s = da == Direction.SOUTH || db == Direction.SOUTH;
        boolean e = da == Direction.EAST || db == Direction.EAST;
        if (n) return e ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
        if (s) return e ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST;
        return RailShape.EAST_WEST;
    }

    private static RailShape ascending(Direction d) {
        return switch (d) {
            case NORTH -> RailShape.ASCENDING_NORTH;
            case SOUTH -> RailShape.ASCENDING_SOUTH;
            case EAST -> RailShape.ASCENDING_EAST;
            default -> RailShape.ASCENDING_WEST;
        };
    }

    /** The rail block this rail wants, shaped. */
    static BlockState railState(Line l, int i) {
        Block b = l.kinds[i] == PLAIN ? Blocks.RAIL : Blocks.POWERED_RAIL;
        RailShape shape = shapeAt(l.rails, i);
        BlockState st = b.defaultBlockState();
        if (st.getBlock() instanceof BaseRailBlock rail) st = st.setValue(rail.getShapeProperty(), shape);
        return st;
    }

    /** Is the rail here what the plan has (the right kind, the right way round)? */
    static boolean railRight(ServerLevel level, Line l, int i) {
        BlockState st = level.getBlockState(l.rails.get(i));
        if (!(st.getBlock() instanceof BaseRailBlock rail)) return false;
        boolean powered = l.kinds[i] != PLAIN;
        if (powered != st.is(Blocks.POWERED_RAIL)) return false;
        return st.getValue(rail.getShapeProperty()) == shapeAt(l.rails, i);
    }

    /** Set the rail's shape to the plan's (a same-block change: the game does not shape it afresh). */
    private static void shape(ServerLevel level, Line l, int i) {
        if (i < 0 || i >= l.rails.size()) return;
        BlockPos p = l.rails.get(i);
        BlockState st = level.getBlockState(p);
        if (!(st.getBlock() instanceof BaseRailBlock rail)) return;
        RailShape want = shapeAt(l.rails, i);
        if (st.getValue(rail.getShapeProperty()) == want) return;
        try {
            level.setBlock(p, st.setValue(rail.getShapeProperty(), want), 2);
        } catch (IllegalArgumentException e) {
            LOG.warn("[MCA-RAIL] rail {} of {} cannot take the shape {}", i, l.key, want);
        }
    }

    // ------------------------------------------------------------------ laying it

    /** What laying a rail came to: laid, already there, waiting on the stores, blocked, or a hand still on its way. */
    enum Laid { LAID, THERE, UNPAID, BLOCKED, WAITING }

    /** Lay up to so many more of this town's rails, building the stations as their ends are reached. Returns rails laid. */
    static int lay(ServerLevel level, Villages.Village v, Line l, int steps) {
        UUID id = v.id();
        int done = 0;
        boolean changed = false;
        // This town's station once the rails along its platform are down (the rails go on being laid meanwhile).
        if (l.laid > PLATFORM + 1 && (l.stations & 1) == 0 && station(level, v, l, 0, false)) {
            l.stations |= 1;
            changed = true;
            Villages.tell(id, level.getDayTime() / 24000L, "the town's station on " + l.name() + " was built: a platform, a roof and a sign");
        }
        while (done < steps && !l.laidAll()) {
            BlockPos at = l.rails.get(l.laid);
            keepAwake(level, v, l, at);
            if (!level.isLoaded(at)) break;
            Laid r = layCell(level, v, l, l.laid, false);
            if (r == Laid.UNPAID || r == Laid.WAITING || r == Laid.BLOCKED) {
                String why = switch (r) {
                    case UNPAID -> SHORT.getOrDefault(id + "/" + l.key, "the rails (the smith makes them out of the stores' iron)");
                    case BLOCKED -> "something in the way at " + at.getX() + ", " + at.getZ();
                    default -> "";
                };
                if (!why.equals(l.waiting)) { l.waiting = why; changed = true; }
                break;
            }
            l.laid++;
            done++;
            changed = true;
            if (!l.waiting.isEmpty()) l.waiting = "";
        }
        if (l.laidAll() && l.kind == Kind.MINE && (l.stations & 2) == 0) {
            if (station(level, v, l, 1, false)) {
                l.stations |= 2;
                changed = true;
                Villages.tell(id, level.getDayTime() / 24000L, "the mine's station on the line to the mine was built");
            }
        }
        if (l.laidAll() && (l.stations & 1) == 0 && station(level, v, l, 0, false)) {
            l.stations |= 1;
            changed = true;
        }
        if (l.ownDone()) {
            release(level, v, l);
            if (open(level, v, l)) changed = true;
        }
        if (changed) save(id, l);
        return done;
    }

    /** A line both of whose halves (or the mine's whole) are down and whose stations stand: open, and the town told. */
    private static boolean open(ServerLevel level, Villages.Village v, Line l) {
        if (l.state == State.OPEN) return false;
        if (l.kind == Kind.LINK) {
            Line back = l.other == null ? null : linkTo(l.other, v.id());
            if (back == null || !back.ownDone()) return false;
        }
        long day = level.getDayTime() / 24000L;
        l.state = State.OPEN;
        l.openedDay = day;
        l.waiting = "";
        if (l.kind == Kind.LINK && l.other != null) {
            Line back = linkTo(l.other, v.id());
            if (back != null && back.state != State.OPEN) {
                back.state = State.OPEN;
                back.openedDay = day;
                save(l.other, back);
                Villages.tell(l.other, day, "the railway to " + Villages.name(v.id()) + " was opened: " + l.length()
                    + " blocks of rails between the two towns' stations");
            }
            Villages.tell(v.id(), day, "the railway to " + Villages.name(l.other) + " was opened: " + l.length()
                + " blocks of rails between the two towns' stations");
        } else {
            Villages.tell(v.id(), day, "the railway to the mine was opened: " + l.length() + " blocks of rails, "
                + count(l, BOOST) + " torches lighting its powered rails, a station at each end");
        }
        cheer(level, v, l);
        LOG.info("[MCA-RAIL] {} opened {} ({} rails)", Villages.name(v.id()), l.key, l.length());
        return true;
    }

    /** The opening, as a folk by the station says it. */
    private static void cheer(ServerLevel level, Villages.Village v, Line l) {
        List<AssistantEntity> folk = Villages.folkOf(v.id());
        for (AssistantEntity a : folk) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.blockPosition().distSqr(l.rails.get(0)) < 48 * 48) {
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "The railway's open! No more walking to the mine with a sack on my back.",
                    "Hear that? The first cart's on the line. Iron rails, every one of them our own.",
                    "A railway. In our town. My grandmother wouldn't have believed it."));
                break;
            }
        }
    }

    private static int count(Line l, char kind) {
        int n = 0;
        for (char c : l.kinds) if (c == kind) n++;
        return n;
    }

    /**
     * One rail: the way cleared over it (the earth, stone and timber into the stores), something under it (a bank of
     * the stores' cobblestone, a trestle of planks on posts, a deck over water), the rail itself out of the stores and
     * shaped to the plan, and a torch beside the first of a run of powered rails. Done by a hand at the town's works
     * unless {@code free} (the showcase).
     */
    static Laid layCell(ServerLevel level, Villages.Village v, Line l, int i, boolean free) {
        BlockPos at = l.rails.get(i);
        if (railRight(level, l, i) && supported(level, at) && (l.kinds[i] != BOOST || torchBeside(level, l, i))) return Laid.THERE;
        // Waits on the rail being to hand: no hand is sent for nothing.
        boolean powered = l.kinds[i] != PLAIN;
        Item railItem = powered ? Items.POWERED_RAIL : Items.RAIL;
        boolean haveRail = level.getBlockState(at).is(powered ? Blocks.POWERED_RAIL : Blocks.RAIL);
        if (!free && !haveRail && Market.stock(level, v.id(), s -> s.is(railItem)) == 0) return Laid.UNPAID;
        if (!free && !TownJobs.atWork(level, v, "railway", at, "laying " + l.name(), AssistantEntity.StationTask.HAUL)) return Laid.WAITING;
        // The way cleared: the rail's place and two above it, and in a cutting all the earth and rock up to the top of it
        // (a cutting open to the sky, not a tunnel).
        Roads.Ground g = Roads.ground(level, at.getX(), at.getZ());
        int top = g == null || g.water() ? 2 : Math.max(2, Math.min(MOST_CUT + 1, g.y() - at.getY()));
        for (int h = 0; h <= top; h++) {
            BlockPos p = at.above(h);
            BlockState st = level.getBlockState(p);
            if (st.isAir()) continue;
            if (h == 0 && st.getBlock() instanceof BaseRailBlock) continue;
            if (h > 2 && !natural(st)) break;                 // over the cart's head: a station's roof, a bridge, left be
            if (!clear(level, v, p, st, free)) return Laid.BLOCKED;
        }
        // Something to lie on.
        if (!supported(level, at)) {
            Laid s = support(level, v, l, i, free);
            if (s != Laid.LAID) return s;
        }
        // The rail.
        BlockState want = railState(l, i);
        BlockState here = level.getBlockState(at);
        if (!here.is(want.getBlock())) {
            if (here.getBlock() instanceof BaseRailBlock) {
                Crafts.giveBack(level, v, here.getBlock().asItem(), 1);        // the wrong kind, back to the stores
            }
            if (!free && !Crafts.take(level, v, s -> s.is(railItem), 1)) return Laid.UNPAID;
            level.setBlock(at, want, 3);
        }
        shape(level, l, i);
        shape(level, l, i - 1);
        shape(level, l, i + 1);
        // A torch by the first of a run of powered rails.
        if (l.kinds[i] == BOOST && !torchBeside(level, l, i)) torch(level, v, l, i, free);
        return Laid.LAID;
    }

    /** Is there something solid under this rail to hold it? */
    static boolean supported(ServerLevel level, BlockPos rail) {
        BlockPos below = rail.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** Ground that may be dug out of a cutting (and home it goes): earth, sand, gravel, rock, snow, and what grows. */
    static boolean natural(BlockState st) {
        if (st.hasBlockEntity()) return false;
        return st.is(BlockTags.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.FARMLAND) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL)
            || st.is(Blocks.CLAY) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.SNOW) || st.is(Blocks.ICE)
            || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS)
            || st.is(BlockTags.COAL_ORES) || st.is(BlockTags.IRON_ORES) || st.is(BlockTags.COPPER_ORES)
            || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE) || st.is(BlockTags.TERRACOTTA) || st.is(Blocks.MOSS_BLOCK)
            || st.is(Blocks.CACTUS) || st.is(Blocks.SUGAR_CANE) || st.is(Blocks.BAMBOO) || st.is(Blocks.PUMPKIN) || st.is(Blocks.MELON)
            || st.is(Blocks.VINE) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(BlockTags.REPLACEABLE)
            || (st.canBeReplaced() && st.getFluidState().isEmpty());
    }

    /** The town's own paving, which its own line may take up where it runs down an avenue (back into the stores). */
    static boolean paving(BlockState st) {
        return st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.STONE_BRICKS) || st.is(Blocks.GRAVEL);
    }

    /** Is this spot within a town's streets (where the line runs down the avenue)? */
    static boolean inATown(ServerLevel level, BlockPos p) {
        for (Villages.Village w : Villages.every()) {
            if (!w.dim().equals(level.dimension())) continue;
            if (Math.max(Math.abs(p.getX() - w.centre().getX()), Math.abs(p.getZ() - w.centre().getZ())) <= Villages.townReach(w.id()) + 2) return true;
        }
        return false;
    }

    /** Clear one block out of the line's way: what it was home to the stores (stone as cobblestone). False if it is not ours to dig. */
    private static boolean clear(ServerLevel level, Villages.Village v, BlockPos p, BlockState st, boolean free) {
        if (!st.getFluidState().isEmpty() && st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            return true;
        }
        if (!natural(st) && !(paving(st) && inATown(level, p))) return false;
        if (VanillaVillages.within(level, p.getX(), p.getZ(), 2)) return false;   // [emerald] a village of villagers' ground is theirs
        if (!free) {
            Item back = st.is(Blocks.STONE) || st.is(Blocks.DEEPSLATE) ? Items.COBBLESTONE
                : st.is(BlockTags.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.FARMLAND) ? Items.DIRT
                : st.is(BlockTags.LEAVES) || st.canBeReplaced() || st.is(BlockTags.FLOWERS) ? Items.AIR
                : st.getBlock().asItem();
            if (back != Items.AIR) Crafts.giveBack(level, v, back, 1);
        }
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        return true;
    }

    /**
     * Something under a rail: over water a deck of planks with a post down to the bed every third rail; over a dip of a
     * few blocks a bank of the stores' cobblestone; deeper, a trestle, a beam of planks with a post of fence down to the
     * ground every other rail. Soft ground under it (a path, a field, mud) is made good with cobblestone.
     */
    private static Laid support(ServerLevel level, Villages.Village v, Line l, int i, boolean free) {
        BlockPos at = l.rails.get(i);
        BlockPos under = at.below();
        BlockState st = level.getBlockState(under);
        boolean overWater = !st.getFluidState().isEmpty();
        if (!st.isAir() && !overWater && !st.canBeReplaced()) {
            // Ground the rail cannot lie on (a path, a field): made good.
            if (!natural(st)) return Laid.BLOCKED;
            if (!free && !TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Laid.UNPAID;
            if (!free && (st.is(BlockTags.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.FARMLAND))) Crafts.giveBack(level, v, Items.DIRT, 1);
            level.setBlock(under, Blocks.COBBLESTONE.defaultBlockState(), 3);
            return Laid.LAID;
        }
        // How far down the ground is, and whether it is water that lies under the rail.
        int depth = 0;
        BlockPos q = under;
        while (depth < MOST_FILL + 2) {
            BlockState s = level.getBlockState(q);
            if (!s.getFluidState().isEmpty() && !overWater) overWater = true;
            if (!s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()) break;
            depth++;
            q = q.below();
        }
        if (overWater) {
            if (!free && !Crafts.usePlanks(level, v, 1)) return Laid.UNPAID;
            level.setBlock(under, Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            if (i % 3 == 0) post(level, v, under.below(), free, true);
            return Laid.LAID;
        }
        if (depth <= BANK) {
            if (!free && !TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), depth)) return Laid.UNPAID;
            for (int d = 0; d < depth; d++) level.setBlock(under.below(d), Blocks.COBBLESTONE.defaultBlockState(), 3);
            return Laid.LAID;
        }
        if (!free && !Crafts.usePlanks(level, v, 1)) return Laid.UNPAID;
        level.setBlock(under, Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
        if (i % 2 == 0) post(level, v, under.below(), free, false);
        return Laid.LAID;
    }

    /** A post of spruce fence down from here to the ground or the bed, as far as the stores' planks run. */
    private static void post(ServerLevel level, Villages.Village v, BlockPos top, boolean free, boolean wet) {
        BlockPos p = top;
        for (int d = 0; d < MOST_FILL + 8; d++) {
            BlockState s = level.getBlockState(p);
            boolean water = !s.getFluidState().isEmpty();
            if (!s.isAir() && !water && !s.canBeReplaced()) return;
            if (!free && !Crafts.fence(level, v)) return;
            BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
            if (water) fence = fence.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
            level.setBlock(p, fence, 3);
            p = p.below();
        }
    }

    /** The two places beside rail i a torch may stand, the side away from the platform first. */
    private static BlockPos[] besides(Line l, int i) {
        Direction out = l.outAt(i);
        return new BlockPos[]{ l.rails.get(i).relative(out.getCounterClockWise()), l.rails.get(i).relative(out.getClockWise()) };
    }

    static boolean torchBeside(ServerLevel level, Line l, int i) {
        for (BlockPos p : besides(l, i)) if (level.getBlockState(p).is(Blocks.REDSTONE_TORCH)) return true;
        return false;
    }

    /** A redstone torch out of the stores beside a powered rail, on something it can stand on (a block put under it if need be). */
    private static boolean torch(ServerLevel level, Villages.Village v, Line l, int i, boolean free) {
        for (BlockPos p : besides(l, i)) {
            if (l.rails.contains(p) || l.rails.contains(p.below()) || l.rails.contains(p.above())) continue;   // never on the line itself
            BlockState st = level.getBlockState(p);
            if (!st.isAir() && !(natural(st) && !st.is(BlockTags.LOGS))) continue;
            BlockPos under = p.below();
            BlockState u = level.getBlockState(under);
            boolean stands = u.isFaceSturdy(level, under, Direction.UP);
            if (!stands && !(u.isAir() || u.canBeReplaced() || !u.getFluidState().isEmpty())) continue;
            if (!free && Market.stock(level, v.id(), s -> s.is(Items.REDSTONE_TORCH)) == 0) {
                SHORT.put(v.id() + "/" + l.key, "redstone torches for the powered rails");
                return false;
            }
            if (!st.isAir() && !clear(level, v, p, st, free)) continue;
            if (!stands) {
                if (!free && !TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return false;
                level.setBlock(under, Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
            if (!free && !Crafts.take(level, v, s -> s.is(Items.REDSTONE_TORCH), 1)) return false;
            level.setBlock(p, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    /** The ground round the laying head kept awake while it is out beyond the town, a window that moves along with it. */
    private static void keepAwake(ServerLevel level, Villages.Village v, Line l, BlockPos head) {
        String k = v.id() + "/" + l.key;
        BlockPos was = WINDOW.get(k);
        if (was != null && flat(was, head) < 24 * 24) return;
        UUID owner = UUID.nameUUIDFromBytes(("mca-rail-" + k).getBytes());
        if (was != null) ChunkLoad.setLoaded(level, owner, was, 1, false);
        ChunkLoad.setLoaded(level, owner, head, 1, true);
        WINDOW.put(k, head.immutable());
    }

    private static void release(ServerLevel level, Villages.Village v, Line l) {
        String k = v.id() + "/" + l.key;
        BlockPos was = WINDOW.remove(k);
        if (was != null) ChunkLoad.setLoaded(level, UUID.nameUUIDFromBytes(("mca-rail-" + k).getBytes()), was, 1, false);
    }

    // ------------------------------------------------------------------ the stations

    /**
     * The station at one end of the line: the station drawing (a platform of slabs beside the track, two posts, a roof
     * and a lantern hung under it) set down along the platform's rails, the buffer stop at the end of the track with
     * its lever, and a signpost at the platform's end. Every block out of the stores, by a hand at the works, unless
     * {@code free}. True once it all stands.
     */
    static boolean station(ServerLevel level, Villages.Village v, Line l, int end, boolean free) {
        BlockPos anchor = l.stationAnchor(end);
        Direction facing = l.facing(end);
        if (!level.isLoaded(anchor)) return false;
        List<com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement> plan =
            com.jrpetty.mcassistant.entity.goal.BuildGoal.plan("station", anchor, facing, 4);
        List<com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement> todo = new ArrayList<>();
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : plan) if (!placed(level, p)) todo.add(p);
        BlockPos buffer = l.buffer(end);
        BlockPos lever = buffer.above();
        boolean leverUp = level.getBlockState(lever).is(Blocks.LEVER);
        BlockPos[] sign = signSpot(l, end);
        boolean signUp = level.getBlockState(sign[1]).getBlock() instanceof net.minecraft.world.level.block.SignBlock;
        if (todo.isEmpty() && leverUp && signUp) return true;
        if (!free && !TownJobs.atWork(level, v, "railway", anchor, "building the station on " + l.name(), AssistantEntity.StationTask.HAUL)) return false;
        // The ground of the platform cleared of what grows on it.
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : todo) {
            BlockState st = level.getBlockState(p.pos());
            if (!st.isAir() && !placed(level, p) && natural(st)) clear(level, v, p.pos(), st, free);
        }
        int[] spareSlab = { 0 };
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stampOnly(level, "station", anchor, facing, 4,
            p -> paid(level, v, p, free, spareSlab), p -> !placed(level, p));
        // The buffer's lever, and the signpost.
        if (!leverUp && level.getBlockState(buffer).isFaceSturdy(level, buffer, Direction.UP) && level.getBlockState(lever).isAir()) {
            if (free || takeOrMake(level, v, Items.LEVER)) {
                level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                    .setValue(LeverBlock.FACING, facing).setValue(LeverBlock.POWERED, false), 3);
            }
        }
        if (!signUp) signpost(level, v, l, end, free);
        // Built once the drawing stands; the lantern, the lever and the sign go up when the stores have them (mend looks again).
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : plan) {
            if (p.part() != com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.LANTERN && !placed(level, p)) return false;
        }
        return true;
    }

    /** Is this block of the station there already (anything of its kind)? */
    private static boolean placed(ServerLevel level, com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p) {
        BlockState st = level.getBlockState(p.pos());
        return switch (p.part()) {
            case FENCE -> st.is(BlockTags.FENCES);
            case LANTERN -> st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock;
            default -> switch (p.style()) {
                case STONE_SLAB, ROOF_SLAB, ROOF_SLAB_TOP -> st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock;
                default -> !st.isAir() && st.getFluidState().isEmpty() && !st.canBeReplaced();
            };
        };
    }

    /**
     * What a block of the station is made of, paid for out of the stores (null if they cannot run to it, and it waits):
     * a cobblestone footing, a platform of stone brick (or cobblestone) slabs, posts of fence, a roof of spruce slabs,
     * the buffer stop of the masons' stone, a lantern if the smith has made one.
     */
    @Nullable
    private static BlockState paid(ServerLevel level, Villages.Village v, com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p,
                                   boolean free, int[] spare) {
        switch (p.part()) {
            case FENCE -> {
                return free || Crafts.fence(level, v) ? Blocks.SPRUCE_FENCE.defaultBlockState() : null;
            }
            case LANTERN -> {
                if (free) return Blocks.LANTERN.defaultBlockState();
                Block light = Masonry.light(level, v);
                if (light == null) return null;
                if (!(light instanceof net.minecraft.world.level.block.LanternBlock)) {
                    Masonry.unlight(level, v, light);
                    return null;
                }
                return light.defaultBlockState();
            }
            default -> { }
        }
        switch (p.style()) {
            case FOUNDATION -> {
                return free || TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1) ? Blocks.COBBLESTONE.defaultBlockState() : null;
            }
            case STONE_SLAB -> {
                if (free) return Blocks.STONE_BRICK_SLAB.defaultBlockState();
                if (Crafts.take(level, v, s -> s.is(Items.STONE_BRICK_SLAB), 1)) return Blocks.STONE_BRICK_SLAB.defaultBlockState();
                if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE_SLAB), 1)) return Blocks.COBBLESTONE_SLAB.defaultBlockState();
                // A block makes two slabs: the other goes into the stores for the next.
                if (TownWork.take(level, v, s -> s.is(Items.STONE_BRICKS), 1)) {
                    Crafts.store(level, v, new ItemStack(Items.STONE_BRICK_SLAB));
                    return Blocks.STONE_BRICK_SLAB.defaultBlockState();
                }
                if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) {
                    Crafts.store(level, v, new ItemStack(Items.COBBLESTONE_SLAB));
                    return Blocks.COBBLESTONE_SLAB.defaultBlockState();
                }
                return null;
            }
            case ROOF_SLAB, ROOF_SLAB_TOP -> {
                if (free) return Blocks.SPRUCE_SLAB.defaultBlockState();
                if (Crafts.take(level, v, s -> s.is(Items.SPRUCE_SLAB), 1)) return Blocks.SPRUCE_SLAB.defaultBlockState();
                if (!Crafts.usePlanks(level, v, 1)) return null;
                Crafts.store(level, v, new ItemStack(Items.SPRUCE_SLAB));
                return Blocks.SPRUCE_SLAB.defaultBlockState();
            }
            case MASONRY -> {
                if (free) return Blocks.STONE_BRICKS.defaultBlockState();
                Block b = Crafts.masonry(level, v);
                return b == null ? null : b.defaultBlockState();
            }
            default -> {
                return free || TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1) ? Blocks.COBBLESTONE.defaultBlockState() : null;
            }
        }
    }

    /** The signpost at the platform's far end from the buffer: the post, and the sign on it. */
    private static BlockPos[] signSpot(Line l, int end) {
        Direction facing = l.facing(end);
        BlockPos post = l.stationAnchor(end).relative(facing.getOpposite(), PLATFORM / 2 + 1).relative(facing.getClockWise(), 2);
        return new BlockPos[]{ post, post.above() };
    }

    private static void signpost(ServerLevel level, Villages.Village v, Line l, int end, boolean free) {
        BlockPos[] at = signSpot(l, end);
        BlockState ground = level.getBlockState(at[0].below());
        if (!ground.isFaceSturdy(level, at[0].below(), Direction.UP)) return;
        for (BlockPos p : at) {
            BlockState st = level.getBlockState(p);
            if (!st.isAir() && !(natural(st) && st.canBeReplaced())) return;
        }
        if (!free) {
            if (!Crafts.sign(level, v)) return;
            if (!Crafts.fence(level, v)) {
                Crafts.store(level, v, new ItemStack(Items.OAK_SIGN));
                return;
            }
        }
        level.setBlock(at[0], Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        Direction facing = l.facing(end);
        level.setBlock(at[1], Blocks.SPRUCE_SIGN.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION,
                net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(facing.getOpposite())), 3);
        String here = end == 0 ? Villages.name(v.id()) : l.kind == Kind.MINE ? "The Mine" : Villages.name(l.other);
        String there = end == 0 ? (l.kind == Kind.MINE ? "the mine" : Villages.name(l.other)) : Villages.name(v.id());
        if (level.getBlockEntity(at[1]) instanceof SignBlockEntity s) {
            SignText text = new SignText()
                .setMessage(0, Component.literal(here))
                .setMessage(1, Component.literal("Station"))
                .setMessage(2, Component.literal("Trains to " + there))
                .setMessage(3, Component.literal(l.length() + " blocks"));
            s.setText(text, true);
            s.setText(text, false);
            s.setWaxed(true);
        }
    }

    /** One of these out of the stores, or made there and then at the bench from what they hold (Bench). */
    static boolean takeOrMake(ServerLevel level, Villages.Village v, Item it) {
        if (Crafts.take(level, v, s -> s.is(it), 1)) return true;
        if (!Tiers.allows(level, Villages.ageOf(v.id()), it)) return false;      // not this age's work yet
        Bench.Hand hand = Bench.handOf(level, v, null, null);
        Bench.Plan p = Bench.plan(level, v, it, 1, hand);
        if (!p.ok() || Bench.make(level, v, p, null, hand).isEmpty()) return false;
        return Crafts.take(level, v, s -> s.is(it), 1);
    }

    // ------------------------------------------------------------------ upkeep

    /**
     * An open line walked a stretch at a time: a rail gone (a creeper, a player, a miner's stairs under it), the ground
     * under one dug away, a torch knocked off, put back by the town's works out of the stores. Returns how many mended.
     */
    static int mend(ServerLevel level, Villages.Village v, Line l, int stretch) {
        int own = l.own();
        if (own <= 0) return 0;
        int mended = 0;
        for (int k = 0; k < stretch; k++) {
            int i = Math.floorMod(l.mendCursor, own);
            BlockPos at = l.rails.get(i);
            if (!level.isLoaded(at)) { l.mendCursor++; continue; }
            boolean ok = railRight(level, l, i) && supported(level, at) && (l.kinds[i] != BOOST || torchBeside(level, l, i));
            if (ok) { l.mendCursor++; continue; }
            Laid r = layCell(level, v, l, i, false);
            if (r == Laid.LAID) {
                mended++;
                RailCarts.statsOf(v.id(), l).mended++;
                RailCarts.saveStatsOf(v.id(), l);
                long day = level.getDayTime() / 24000L;
                String said = Ledger.note(v.id(), "rail/mended/" + l.key);
                if (said == null || !said.equals(Long.toString(day))) {
                    Ledger.note(v.id(), "rail/mended/" + l.key, Long.toString(day));
                    Villages.tell(v.id(), day, "a broken rail on " + l.name() + " at " + at.getX() + ", " + at.getZ() + " was mended by the town's works");
                }
                l.mendCursor++;
                continue;
            }
            if (r == Laid.BLOCKED || r == Laid.THERE) {
                l.mendCursor++;                      // somebody's block on the line: past it, and looked at again next round
                continue;
            }
            break;                                   // a hand on its way, or the stores short: it waits there
        }
        for (int end = 0; end <= (l.kind == Kind.MINE ? 1 : 0); end++) {
            BlockPos lever = l.buffer(end).above();
            if (level.isLoaded(lever) && !level.getBlockState(lever).is(Blocks.LEVER)) station(level, v, l, end, false);
        }
        return mended;
    }

    // ------------------------------------------------------------------ the smith's part

    /**
     * The smith's railway work (Crafts.smith, after the tools and the watch's kit): what the lines being laid still want
     * and the stores do not hold, made at the bench by the game's recipes out of the stores' iron, gold, redstone, sticks
     * and cobblestone, a batch at a time: rails (six iron and a stick make sixteen), powered rails (six gold, a stick and
     * a redstone make six), redstone torches and levers; for an open line its carts (a minecart of five iron, a chest
     * minecart of a minecart and a chest), and a few spare rails for mending. Returns what was made, in words, or null.
     */
    @Nullable
    public static String smith(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f) {
        UUID id = v.id();
        Map<String, Line> mine = lines(id);
        if (mine.isEmpty()) return null;
        int rails = 0, powered = 0, torches = 0, levers = 0;
        boolean open = false;
        String forLine = "";
        for (Line l : mine.values()) {
            // The torches for every run of powered rails still dark, laid or not (a rail is laid before its torch is to hand).
            for (int i = 0; i < l.own(); i++) {
                if (l.kinds[i] == BOOST && !(level.isLoaded(l.rails.get(i)) && torchBeside(level, l, i))) torches++;
            }
            if (l.state == State.OPEN) { open = true; continue; }
            for (int i = l.laid; i < l.own(); i++) {
                if (level.isLoaded(l.rails.get(i)) && railRight(level, l, i)) continue;
                if (l.kinds[i] == PLAIN) rails++;
                else powered++;
            }
            if ((l.stations & 1) == 0) levers++;
            if (l.kind == Kind.MINE && (l.stations & 2) == 0) levers++;
            if (forLine.isEmpty()) forLine = l.name();
        }
        if (open) {
            rails = Math.max(rails, SPARE);
            powered = Math.max(powered, SPARE_POWERED);
            if (forLine.isEmpty()) forLine = "mending the lines";
        }
        Bench.Hand hand = Bench.handOf(level, v, f, f == null ? null : VillageFolkEntity.buildingFor(f.stationTask()));
        String made = batch(level, v, f, hand, Items.RAIL, rails, 16, forLine);
        if (made == null) made = batch(level, v, f, hand, Items.POWERED_RAIL, powered, 6, forLine);
        if (made == null) made = batch(level, v, f, hand, Items.REDSTONE_TORCH, torches, 1, forLine);
        if (made == null) made = batch(level, v, f, hand, Items.LEVER, levers, 1, forLine);
        if (made == null && open) {
            int[] carts = RailCarts.cartsWanted(level, v);
            if (carts[0] > 0) made = batch(level, v, f, hand, Items.CHEST_MINECART, carts[0], 1, "the ore carts");
            if (made == null && carts[1] > 0) made = batch(level, v, f, hand, Items.MINECART, carts[1], 1, "the riders' carts");
        }
        return made;
    }

    /** One batch of a thing the lines want, if the stores hold fewer than wanted: made, or why not noted for the books. */
    @Nullable
    private static String batch(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Bench.Hand hand, Item it, int wanted,
                                int per, String forLine) {
        if (wanted <= 0) return null;
        int have = Market.stock(level, v.id(), s -> s.is(it));
        if (have >= wanted) return null;
        int make = Math.min(Math.max(per, wanted - have), per * 2);
        Bench.Plan p = Bench.plan(level, v, it, make, hand);
        String key = v.id() + "/";
        if (!p.ok()) {
            String why = p.shortOf == null ? "the makings" : p.shortOf + (p.why.isEmpty() ? "" : " (" + p.why + ")");
            for (Line l : lines(v.id()).values()) if (l.state != State.OPEN) SHORT.put(key + l.key, Bench.plural(new ItemStack(it).getHoverName().getString()
                .toLowerCase(Locale.ROOT)) + ": the smith is short of " + why);
            return null;
        }
        ItemStack out = Bench.make(level, v, p, f, hand);
        if (out.isEmpty()) return null;
        for (Line l : lines(v.id()).values()) SHORT.remove(key + l.key);
        return Bench.words(it, out.getCount()) + " for " + forLine;
    }

    /** Tests: the smith's railway work now, with this smith (or none). */
    @Nullable
    public static String smithForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f) {
        return smith(level, v, f);
    }

    // ------------------------------------------------------------------ for the rest of the town

    /** Do any of this town's lines run over this column, or the block round it? (The builders keep off: Villages.lotKeptOff.) */
    public static boolean crosses(UUID village, int x0, int z0, int x1, int z1) {
        for (Villages.Village w : Villages.every()) {
            for (Line l : lines(w.id()).values()) {
                for (BlockPos r : l.rails) {
                    if (r.getX() >= x0 - 1 && r.getX() <= x1 + 1 && r.getZ() >= z0 - 1 && r.getZ() <= z1 + 1) return true;
                }
            }
        }
        return false;
    }

    /**
     * The ground a town's lines and stations stand on, as the mine's built ground (TownMine.builtGround): a square over
     * each station, and each straight run of rails a strip, so no miner's face is given out under the line and no miner
     * cuts a block under it.
     */
    public static void ground(UUID village, List<int[]> out) {
        for (Line l : lines(village).values()) {
            int n = l.rails.size();
            int start = 0;
            for (int i = 1; i <= n; i++) {
                boolean turn = i < n && i > 1 && (l.rails.get(i).getX() - l.rails.get(i - 1).getX() != l.rails.get(i - 1).getX() - l.rails.get(i - 2).getX()
                    || l.rails.get(i).getZ() - l.rails.get(i - 1).getZ() != l.rails.get(i - 1).getZ() - l.rails.get(i - 2).getZ());
                if (i == n || turn) {
                    BlockPos a = l.rails.get(start), b = l.rails.get(i - 1);
                    out.add(new int[]{ Math.min(a.getX(), b.getX()) - 1, Math.min(a.getZ(), b.getZ()) - 1,
                        Math.max(a.getX(), b.getX()) + 1, Math.max(a.getZ(), b.getZ()) + 1 });
                    start = i - 1;
                }
            }
            for (int end = 0; end <= 1; end++) {
                BlockPos s = l.stationAnchor(end);
                out.add(new int[]{ s.getX() - 5, s.getZ() - 5, s.getX() + 5, s.getZ() + 5 });
            }
        }
    }

    /** The lines in a few words each, for the books, the board and /village transport. */
    public static List<String> report(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Line l : lines(village).values()) {
            RailCarts.Stats s = RailCarts.statsOf(village, l);
            StringBuilder sb = new StringBuilder(cap(l.name())).append(": ").append(l.length()).append(" blocks, ").append(l.state.words);
            if (l.state != State.OPEN) {
                int own = l.own();
                sb.append(" (").append(l.laid).append(" of ").append(own).append(l.kind == Kind.LINK ? " of our half" : "").append(" laid");
                if (!l.waiting.isEmpty()) sb.append("; waiting on ").append(l.waiting);
                sb.append(")");
            } else {
                sb.append(" since day ").append(l.openedDay);
            }
            sb.append(". ").append(count(l, BOOST) + count(l, BOOSTED) + count(l, STATION)).append(" powered rails, ")
                .append(count(l, BOOST)).append(" torches");
            if (s.carts > 0) sb.append("; ").append(s.carts).append(s.carts == 1 ? " cart of ore" : " carts of ore").append(" (")
                .append(s.goods).append(" goods) brought in");
            if (s.riders > 0) sb.append("; ").append(s.riders).append(s.riders == 1 ? " ride" : " rides");
            if (s.mended > 0) sb.append("; ").append(s.mended).append(" rails mended");
            out.add(sb.append('.').toString());
        }
        return out;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests and the showcase

    /** Tests: plan the mine's line now (the ground must be loaded). */
    @Nullable
    public static Line planMineForTests(ServerLevel level, Villages.Village v) {
        Line l = mineLine(v.id());
        if (l != null) return l;
        for (int i = 0; i < 3 && l == null; i++) l = planMine(level, v, level.getDayTime() / 24000L);
        return l;
    }

    /** Tests: plan the line between two towns now. */
    @Nullable
    public static Line planLinkForTests(ServerLevel level, Villages.Village a, Villages.Village b) {
        Line l = linkTo(a.id(), b.id());
        for (int i = 0; i < 3 && l == null; i++) l = planLink(level, a, b, level.getDayTime() / 24000L);
        return l;
    }

    /** Tests: lay as much of the line as the stores run to (the works done at once: TownJobs.instantForTests). */
    public static int layForTests(ServerLevel level, Villages.Village v, Line l, int steps) {
        if (l.state == State.PLANNED) l.state = State.LAYING;
        return lay(level, v, l, steps);
    }

    /** Tests: walk the open line and mend what is broken. */
    public static int mendForTests(ServerLevel level, Villages.Village v, Line l) {
        l.mendCursor = 0;
        return mend(level, v, l, l.own());
    }

    /** Tests: is each rail there, shaped to join the ones either side, with something under it? Null if so, else where not. */
    @Nullable
    public static String brokenAtForTests(ServerLevel level, Line l) {
        for (int i = 0; i < l.rails.size(); i++) {
            BlockPos p = l.rails.get(i);
            if (!railRight(level, l, i)) return "rail " + i + " at " + p.toShortString() + " is " + level.getBlockState(p);
            if (!supported(level, p)) return "rail " + i + " at " + p.toShortString() + " has nothing under it";
            if (i > 0) {
                BlockPos q = l.rails.get(i - 1);
                if (Math.abs(p.getX() - q.getX()) + Math.abs(p.getZ() - q.getZ()) != 1 || Math.abs(p.getY() - q.getY()) > 1) {
                    return "rails " + (i - 1) + " and " + i + " do not meet: " + q.toShortString() + " / " + p.toShortString();
                }
            }
        }
        return null;
    }

    /** Tests: the kinds of rail, in a word ("rrrppqrr..."). */
    public static String kindsForTests(Line l) {
        return new String(l.kinds);
    }

    /** Tests: the rails' shapes as the plan has them, by index. */
    public static RailShape shapeForTests(Line l, int i) {
        return shapeAt(l.rails, i);
    }

    /** Tests: is this station built (0: the town's, 1: the far end's)? */
    public static boolean stationForTests(Line l, int end) {
        return (l.stations & (end == 0 ? 1 : 2)) != 0;
    }

    /** Tests: {rails laid by this town, rails it lays}. */
    public static int[] laidForTests(Line l) {
        return new int[]{ l.laid, l.own() };
    }

    /** Tests: {runs of powered rails wanting a torch, of them with their torch, powered rails, of them powered now}. */
    public static int[] powerForTests(ServerLevel level, Line l) {
        int runs = 0, lit = 0, powered = 0, live = 0;
        for (int i = 0; i < l.rails.size(); i++) {
            if (l.kinds[i] == BOOST) {
                runs++;
                if (torchBeside(level, l, i)) lit++;
            }
            if (l.kinds[i] == BOOST || l.kinds[i] == BOOSTED) {
                powered++;
                BlockState st = level.getBlockState(l.rails.get(i));
                if (st.is(Blocks.POWERED_RAIL) && st.getValue(net.minecraft.world.level.block.PoweredRailBlock.POWERED)) live++;
            }
        }
        return new int[]{ runs, lit, powered, live };
    }

    /** Tests: the buffer stop at an end. */
    public static BlockPos bufferForTests(Line l, int end) {
        return l.buffer(end);
    }

    /** The showcase (/village transport stage): a line laid at once, for nothing, its stations and all. */
    public static void layFreeForStage(ServerLevel level, Villages.Village v, Line l) {
        for (int i = 0; i < l.rails.size(); i++) layCell(level, v, l, i, true);
        station(level, v, l, 0, true);
        if (l.kind == Kind.MINE) station(level, v, l, 1, true);
        l.laid = l.own();
        l.stations = 3;
        open(level, v, l);
        save(v.id(), l);
    }
}
