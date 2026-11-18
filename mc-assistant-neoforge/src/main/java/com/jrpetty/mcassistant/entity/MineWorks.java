package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [mine8] The town's mine as a mine: one main shaft, levels opened off it at the depths the ores lie at, and on each level
 * drifts driven out straight from a landing, branch tunnels off them at a regular spacing, the whole level a tidy grid of
 * corridors with the turns a real mine has in it.
 *
 * <p>Before this each miner cut its own staircase down from the middle of a face of the mine (TownMine) and a gallery off
 * the foot of it, a different way each run, wherever the rock took it: a field of open trenches at the surface, and
 * underground a warren that went nowhere in particular. Now:
 *
 * <ul>
 * <li><b>The shaft</b>: a broad stair two blocks wide winding down round a square pillar of rock (seven blocks across in
 * all), at the middle of the mine's first face. Each side of the square drops three steps and turns on a flat landing at
 * the corner, twelve blocks down a turn, three blocks of headroom all the way, so a folk or a player walks it up and down.
 * The steps are dressed as stairs in the rock they are cut in; a torch at each landing, a timber set across the middle of
 * each side. Only its first few steps are open to the sky, fenced round; the headframe stands over the pillar.</li>
 * <li><b>The levels</b>: off a corner landing at the depths where the ores lie (MineDemand): the coal level as near ninety-six
 * as the ground allows, copper about forty-eight, the iron level about sixteen, the gold level about minus sixteen and the
 * deep level for the diamonds and the redstone as deep as the town's mines go (VillageFolkEntity.deepestMine). A level is
 * opened when the shaft is sunk to it: a landing room five blocks square and three high cut beside the corner, with a
 * chest, a crafting table and a bench under a hanging lantern.</li>
 * <li><b>The drifts</b>: from the middle of each wall of the landing a drift is driven out (two high, one wide) by the miner
 * who starts it, straight, with a timber set every eight blocks and a torch every six. Every fourth block a branch is cut off
 * each side (five to seven long). A drift turns now and then: a short dog-leg after a long straight run (each drift its own
 * lengths), and where it meets the edge of the mine's ground, water, lava or a cave it bends away. A drift that can go no
 * further is closed.</li>
 * <li><b>The miners</b>: each is sent to a level by what the town is short of (MineDemand), and there drives its own drift,
 * cuts the branches of its own, joins a friend's drift to cut its branches, starts a new drift from a free wall of the
 * landing, or takes on a drift left without its miner; never two on the same face. Everything is kept with the world
 * (ledger note mine.works), so the drifts go on across days and loads.</li>
 * </ul>
 */
public final class MineWorks {

    private MineWorks() {}

    /** A mine job's word for a shift in the works (MineGoal leaves these to WorksGoal). */
    public static final String ARG = "works";
    /** The shaft: its square's half-width (the ring of stair two blocks wide round a pillar three across). */
    static final int RING = 3;
    /** No drift or branch comes this near the shaft's middle. */
    static final int KEEP = 5;
    /** The landing room's middle is this far off the shaft's, corner-ways; the room is five across. */
    static final int ROOM_OFF = 6, ROOM_HALF = 2;
    static final int TORCH_EVERY = 6, FRAME_EVERY = 8, BRANCH_EVERY = 4;
    /** Cells of drift cut in one go at the face. */
    static final int FACE_CELLS = 6;
    static final long CLAIM_TICKS = 3600L;

    public enum Kind { SHAFT, ROOM, FACE, BRANCH }

    /** The level ids (kept with the drifts), what each is cut for, and the depth it is cut at as near as the ground allows. */
    public static final int COAL = 0, COPPER = 1, IRON = 2, GOLD = 3, DEEP = 4;
    static final String[] NAMES = { "the coal level", "the copper level", "the iron level", "the gold level", "the deep level" };
    static final int[] AIM = { 95, 48, 16, -16, Integer.MIN_VALUE };

    /** A level: its id, its name, how many sides of the shaft down its corner is, and the height of its floor (feet). */
    public record Lvl(int id, String name, int sides, int y) {
        int corner() { return 4 * sides; }
        int side() { return sides % 4; }
    }

    // ------------------------------------------------------------------ the record

    static final class Drift {
        int id, level;
        @Nullable UUID by;
        int ox, oz;                 // the middle of the landing's wall it starts from
        int heading;                // its way out (2D data value)
        final StringBuilder path = new StringBuilder();   // a letter a cell cut: n e s w
        final List<int[]> branches = new ArrayList<>();   // {anchor, side (1 clockwise, -1), cut, done}
        char closed = '-';          // '-' open; e edge, w water, l lava, c cave, j into another tunnel, r rock, m made
        int bends, turns;
        int parent, pAnchor, pSide;  // a cross-cut: the drift and the branch it was driven on from (0: from the landing)
        boolean blocked;            // its last face met something: it turns next
        char why = 'e';             // what it met

        Direction dir() {
            return path.length() == 0 ? Direction.from2DDataValue(heading) : dirOf(path.charAt(path.length() - 1));
        }

        int straight() {
            int n = 0;
            for (int i = path.length() - 1; i >= 0 && path.charAt(i) == path.charAt(path.length() - 1); i--) n++;
            return n;
        }

        BlockPos cell(int k, int y) {
            int x = ox, z = oz;
            for (int i = 0; i <= k && i < path.length(); i++) {
                Direction d = dirOf(path.charAt(i));
                x += d.getStepX();
                z += d.getStepZ();
            }
            return new BlockPos(x, y, z);
        }

        List<BlockPos> cells(int y) {
            List<BlockPos> out = new ArrayList<>(path.length());
            int x = ox, z = oz;
            for (int i = 0; i < path.length(); i++) {
                Direction d = dirOf(path.charAt(i));
                x += d.getStepX();
                z += d.getStepZ();
                out.add(new BlockPos(x, y, z));
            }
            return out;
        }

        int segLen() { return 16 + Math.floorMod(id * 7, 12); }
        int jogLen() { return 3 + Math.floorMod(id * 3 + turns, 4); }
        int branchLen(int a) { return 5 + Math.floorMod(id + a, 3); }

        @Nullable int[] branch(int a, int side) {
            for (int[] b : branches) if (b[0] == a && b[1] == side) return b;
            return null;
        }
    }

    static final class Works {
        final UUID village;
        int sx, sz, top;
        int sunk;                   // steps of the shaft cut
        int rooms;                  // a bit a level: its landing cut and furnished
        int nextId = 1;
        long week = -1;
        final int[] oreWeek = new int[5], oreAll = new int[5];
        int dug, heap;
        boolean head;
        final List<Drift> drifts = new ArrayList<>();
        String raw;
        int approachY = Integer.MIN_VALUE;   // the ground at the way in, read when the works are read

        Works(UUID village) { this.village = village; }
    }

    private static final Map<UUID, Works> CACHE = new ConcurrentHashMap<>();
    private static final String NOTE = "mine.works";

    static char letter(Direction d) {
        return switch (d) { case NORTH -> 'n'; case EAST -> 'e'; case SOUTH -> 's'; default -> 'w'; };
    }

    static Direction dirOf(char c) {
        return switch (c) { case 'n' -> Direction.NORTH; case 'e' -> Direction.EAST; case 's' -> Direction.SOUTH; default -> Direction.WEST; };
    }

    /** The town's works, read from the ledger (laid out on the first look once the mine has a site and its ground is loaded). */
    @Nullable
    static Works works(ServerLevel level, @Nullable UUID village) {
        if (village == null) return null;
        String raw = Ledger.note(village, NOTE);
        Works w = CACHE.get(village);
        if (w != null && raw != null && raw.equals(w.raw)) return w;
        if (raw != null && !raw.isEmpty()) {
            w = parse(village, raw);
            if (w != null) {
                groundAtTheWayIn(level, w);
                CACHE.put(village, w);
                return w;
            }
        }
        BlockPos site = TownMine.siteOf(village);
        if (site == null || !level.hasChunk(site.getX() >> 4, site.getZ() >> 4)) return null;
        w = new Works(village);
        w.sx = site.getX();
        w.sz = site.getZ();
        // The first landing at the lowest of the ground under it: a landing is never left hanging over a slope.
        w.top = Integer.MAX_VALUE;
        for (int dx = 2; dx <= 3; dx++) for (int dz = 2; dz <= 3; dz++)
            w.top = Math.min(w.top, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, w.sx - dx, w.sz - dz));
        groundAtTheWayIn(level, w);
        save(w);
        Villages.tell(village, level.getDayTime() / 24000L, "the town's miners began the main shaft of the mine, at " + w.sx + ", " + w.sz);
        return w;
    }

    /** The way in stands on the ground there (a slope may stand over the first landing). */
    private static void groundAtTheWayIn(ServerLevel level, Works w) {
        if (level.hasChunk((w.sx - 4) >> 4, (w.sz - 2) >> 4)) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, w.sx - 4, w.sz - 2);
            w.approachY = y <= w.top + 3 ? y : w.top;      // never on something built up there
        }
    }

    static void save(Works w) {
        StringBuilder sb = new StringBuilder();
        sb.append(w.sx).append(',').append(w.sz).append(',').append(w.top).append(',').append(w.sunk).append(',').append(w.rooms)
            .append(',').append(w.nextId).append(',').append(w.week).append(',').append(w.dug).append(',').append(w.heap)
            .append(',').append(w.head ? 1 : 0);
        for (int i = 0; i < 5; i++) sb.append(',').append(w.oreWeek[i]);
        for (int i = 0; i < 5; i++) sb.append(',').append(w.oreAll[i]);
        sb.append('|');
        for (int i = 0; i < w.drifts.size(); i++) {
            Drift d = w.drifts.get(i);
            if (i > 0) sb.append(';');
            sb.append(d.id).append(':').append(d.level).append(':').append(d.by == null ? "-" : d.by.toString()).append(':').append(d.ox).append(':')
                .append(d.oz).append(':').append(d.heading).append(':').append(d.path.length() == 0 ? "." : d.path).append(':').append(d.closed)
                .append(':').append(d.bends).append(':').append(d.turns).append(':').append(d.blocked ? 1 : 0).append(':');
            for (int j = 0; j < d.branches.size(); j++) {
                int[] b = d.branches.get(j);
                if (j > 0) sb.append('+');
                sb.append(b[0]).append('/').append(b[1]).append('/').append(b[2]).append('/').append(b[3]);
            }
            sb.append(':').append(d.parent).append('/').append(d.pAnchor).append('/').append(d.pSide);
        }
        w.raw = sb.toString();
        Ledger.note(w.village, NOTE, w.raw);
        CACHE.put(w.village, w);
    }

    @Nullable
    static Works parse(UUID village, String raw) {
        try {
            Works w = new Works(village);
            String[] parts = raw.split("\\|", -1);
            String[] h = parts[0].split(",");
            w.sx = Integer.parseInt(h[0]);
            w.sz = Integer.parseInt(h[1]);
            w.top = Integer.parseInt(h[2]);
            w.sunk = Integer.parseInt(h[3]);
            w.rooms = Integer.parseInt(h[4]);
            w.nextId = Integer.parseInt(h[5]);
            w.week = Long.parseLong(h[6]);
            w.dug = Integer.parseInt(h[7]);
            w.heap = Integer.parseInt(h[8]);
            w.head = "1".equals(h[9]);
            for (int i = 0; i < 5; i++) w.oreWeek[i] = Integer.parseInt(h[10 + i]);
            for (int i = 0; i < 5; i++) w.oreAll[i] = Integer.parseInt(h[15 + i]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                for (String s : parts[1].split(";")) {
                    String[] f = s.split(":", -1);
                    Drift d = new Drift();
                    d.id = Integer.parseInt(f[0]);
                    d.level = Integer.parseInt(f[1]);
                    d.by = "-".equals(f[2]) ? null : UUID.fromString(f[2]);
                    d.ox = Integer.parseInt(f[3]);
                    d.oz = Integer.parseInt(f[4]);
                    d.heading = Integer.parseInt(f[5]);
                    if (!".".equals(f[6])) d.path.append(f[6]);
                    d.closed = f[7].charAt(0);
                    d.bends = Integer.parseInt(f[8]);
                    d.turns = Integer.parseInt(f[9]);
                    d.blocked = "1".equals(f[10]);
                    if (f.length > 11 && !f[11].isEmpty()) {
                        for (String b : f[11].split("\\+")) {
                            String[] n = b.split("/");
                            d.branches.add(new int[]{ Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]), Integer.parseInt(n[3]) });
                        }
                    }
                    if (f.length > 12 && !f[12].isEmpty()) {
                        String[] n = f[12].split("/");
                        d.parent = Integer.parseInt(n[0]);
                        d.pAnchor = Integer.parseInt(n[1]);
                        d.pSide = Integer.parseInt(n[2]);
                    }
                    w.drifts.add(d);
                }
            }
            w.raw = raw;
            return w;
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[MCA-WORKS] unreadable works note: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ the shaft

    /** How far down step i's floor is from the top: three a side, the corner landings flat. */
    static int drop(int i) { return 3 * (i / 4) + (i % 4); }

    /** The way each side of the square is walked down: east along the north side, then south, west, north. */
    static Direction walk(int side) {
        return switch (side) { case 0 -> Direction.EAST; case 1 -> Direction.SOUTH; case 2 -> Direction.WEST; default -> Direction.NORTH; };
    }

    /** The corner a side starts from, as signs: north-west, north-east, south-east, south-west. */
    static int[] diag(int side) {
        return switch (side) { case 0 -> new int[]{ -1, -1 }; case 1 -> new int[]{ 1, -1 }; case 2 -> new int[]{ 1, 1 }; default -> new int[]{ -1, 1 }; };
    }

    /** The columns of step i (two across a side, four on a corner) at its feet. */
    static List<BlockPos> stepCells(Works w, int i) {
        int s = (i / 4) % 4, k = i % 4, y = w.top - drop(i);
        List<BlockPos> out = new ArrayList<>(4);
        if (k == 0) {
            int[] o = diag(s);
            out.add(new BlockPos(w.sx + 2 * o[0], y, w.sz + 2 * o[1]));
            out.add(new BlockPos(w.sx + 3 * o[0], y, w.sz + 2 * o[1]));
            out.add(new BlockPos(w.sx + 2 * o[0], y, w.sz + 3 * o[1]));
            out.add(new BlockPos(w.sx + 3 * o[0], y, w.sz + 3 * o[1]));
            return out;
        }
        int a = k - 2;
        switch (s) {
            case 0 -> { out.add(new BlockPos(w.sx + a, y, w.sz - 2)); out.add(new BlockPos(w.sx + a, y, w.sz - 3)); }
            case 1 -> { out.add(new BlockPos(w.sx + 2, y, w.sz + a)); out.add(new BlockPos(w.sx + 3, y, w.sz + a)); }
            case 2 -> { out.add(new BlockPos(w.sx - a, y, w.sz + 2)); out.add(new BlockPos(w.sx - a, y, w.sz + 3)); }
            default -> { out.add(new BlockPos(w.sx - 2, y, w.sz - a)); out.add(new BlockPos(w.sx - 3, y, w.sz - a)); }
        }
        return out;
    }

    /** Where a folk walks on step i: its inner column. */
    static BlockPos stepPoint(Works w, int i) {
        return stepCells(w, i).get(0);
    }

    /** The way in from the surface: beside the first landing, to the west. */
    static BlockPos approach(Works w) {
        return new BlockPos(w.sx - 4, Math.max(w.top, w.approachY), w.sz - 2);
    }

    // ------------------------------------------------------------------ the levels

    /** The levels the ground has room for here, top first: each at least four sides of the shaft below the last. */
    static List<Lvl> plan(ServerLevel level, Works w) {
        List<Lvl> out = new ArrayList<>();
        int floor = level.getMinBuildHeight() + 14;          // VillageFolkEntity.deepestMine: no village mine goes deeper
        int prev = -100;
        for (int id = 0; id < NAMES.length; id++) {
            int n = id == DEEP ? (w.top - floor) / 3 : Math.round((w.top - AIM[id]) / 3.0f);
            n = Math.max(n, 4);
            int y = w.top - 3 * n;
            if (y < floor || n < prev + 4) continue;
            out.add(new Lvl(id, NAMES[id], n, y));
            prev = n;
        }
        // Ground too low for any level over the deepest a mine goes (a flat world's few blocks of rock): one level as deep as
        // the rock goes, a few blocks over the bottom of the world.
        if (out.isEmpty()) {
            int n = Math.max(1, (w.top - Math.max(w.top - 12, level.getMinBuildHeight() + 4)) / 3);
            out.add(new Lvl(COAL, NAMES[COAL], n, w.top - 3 * n));
        }
        return out;
    }

    @Nullable
    static Lvl lvl(List<Lvl> plan, int id) {
        for (Lvl l : plan) if (l.id() == id) return l;
        return null;
    }

    static BlockPos roomMiddle(Works w, Lvl l) {
        int[] o = diag(l.side());
        return new BlockPos(w.sx + ROOM_OFF * o[0], l.y(), w.sz + ROOM_OFF * o[1]);
    }

    /** The landing's cells in the order they are cut: the mouth from the corner first, then outward. */
    static List<BlockPos> roomCells(Works w, Lvl l) {
        int[] o = diag(l.side());
        int y = l.y();
        List<BlockPos> out = new ArrayList<>();
        out.add(new BlockPos(w.sx + 3 * o[0], y, w.sz + 4 * o[1]));
        out.add(new BlockPos(w.sx + 2 * o[0], y, w.sz + 4 * o[1]));
        out.add(new BlockPos(w.sx + 4 * o[0], y, w.sz + 3 * o[1]));
        out.add(new BlockPos(w.sx + 4 * o[0], y, w.sz + 2 * o[1]));
        for (int r = 0; r <= 4; r++) {                       // rows out from the shaft, corner-ways
            for (int a = 0; a <= r; a++) {
                int b = r - a;
                out.add(new BlockPos(w.sx + (4 + a) * o[0], y, w.sz + (4 + b) * o[1]));
            }
        }
        for (int r = 5; r <= 8; r++) {
            for (int a = r - 4; a <= 4; a++) {
                int b = r - a;
                out.add(new BlockPos(w.sx + (4 + a) * o[0], y, w.sz + (4 + b) * o[1]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ where it may cut

    /** Inside the mine's own ground (its reserved faces, two blocks in from their edge), clear of the town and other mines. */
    static boolean inGround(ServerLevel level, Works w, int x, int z) {
        BlockPos site = new BlockPos(w.sx, 0, w.sz);
        for (int[] c : new int[][]{ { x, z }, { x - 2, z - 2 }, { x + 2, z - 2 }, { x - 2, z + 2 }, { x + 2, z + 2 } }) {
            int[] f = TownMine.faceIndex(site, new BlockPos(c[0], 0, c[1]));
            if (f == null || !MineGround.faceReserved(w.village, f[0], f[1])) return false;
        }
        if (MineGround.otherMeets(w.village, x - 2, z - 2, x + 2, z + 2)) return false;
        Villages.Village v = Villages.get(w.village);
        return v == null || !TownDigs.inTownGroundRaw(level, w.village, v.centre(), new BlockPos(x, 0, z), 2);
    }

    /** May a drift or a branch be cut at this column: in the mine's ground and clear of the shaft. */
    static boolean fits(ServerLevel level, Works w, int x, int z) {
        if (Math.max(Math.abs(x - w.sx), Math.abs(z - w.sz)) <= KEEP) return false;
        return inGround(level, w, x, z);
    }

    /** May a miner of the works break this block (a vein it follows, a cell of its task): the world's own, in the mine's ground. */
    public static boolean mayCut(ServerLevel level, @Nullable UUID village, BlockPos pos, BlockState s) {
        Works w = village == null ? null : works(level, village);
        if (w == null || pos.getY() >= w.top - 1 && !inShaftTop(w, pos)) return false;
        if (s.getDestroySpeed(level, pos) < 0 || s.hasBlockEntity()) return false;
        // In the works' own ground anything the rock holds is cut through (a cave's tuff and calcite, an old mineshaft's
        // timbers, its own cobblestone seals); what holds something (a chest, a spawner) never.
        // The shaft and its landings (the works' own core, on the mine's first face) always; the drifts in the mine's ground.
        return Math.max(Math.abs(pos.getX() - w.sx), Math.abs(pos.getZ() - w.sz)) <= ROOM_OFF + ROOM_HALF + 1 || inGround(level, w, pos.getX(), pos.getZ());
    }

    private static boolean inShaftTop(Works w, BlockPos pos) {
        return Math.max(Math.abs(pos.getX() - w.sx), Math.abs(pos.getZ() - w.sz)) <= RING && pos.getY() <= w.top + 3;
    }

    // ------------------------------------------------------------------ tasks

    /** A piece of work in the works: the cells to cut (feet, in order), where it stands for each, and the way there. */
    public static final class Task {
        public final Kind kind;
        public final int level, drift, anchor, side, height;
        public final List<BlockPos> cells = new ArrayList<>();
        public final List<BlockPos> stand = new ArrayList<>();
        public List<BlockPos> route = List.of();
        final String claim;
        /** Filled in as it is worked: cells cut, ore taken, blocks broken, and why it stopped short ("" when it did not). */
        public int cut, ore, broke;
        public String why = "";

        Task(Kind kind, int level, int drift, int anchor, int side, int height, String claim) {
            this.kind = kind;
            this.level = level;
            this.drift = drift;
            this.anchor = anchor;
            this.side = side;
            this.height = height;
            this.claim = claim;
        }

        public String words() {
            return switch (kind) {
                case SHAFT -> "sinking the main shaft";
                case ROOM -> "cutting the landing of " + NAMES[level];
                case FACE -> "driving a drift on " + NAMES[level];
                case BRANCH -> "cutting a branch on " + NAMES[level];
            };
        }
    }

    private record Claim(UUID by, long until, int level, String words) {}
    private static final Map<UUID, Map<String, Claim>> CLAIMS = new ConcurrentHashMap<>();
    /** Miners with nothing free to cut: they wait at the pithead till then. */
    private static final Map<UUID, Long> REST = new ConcurrentHashMap<>();
    /** Each miner's way down as it stands (its last task's), for the walk to its next. */
    private static final Map<UUID, Task> LAST = new ConcurrentHashMap<>();

    private static boolean claim(Works w, String key, VillageFolkEntity f, long now, int level, String words) {
        Map<String, Claim> m = CLAIMS.computeIfAbsent(w.village, k -> new ConcurrentHashMap<>());
        Claim c = m.get(key);
        if (c != null && c.until() > now && !c.by().equals(f.getUUID()) && onShift(w.village, c.by())) return false;
        m.values().removeIf(x -> x.by().equals(f.getUUID()) || x.until() <= now);
        m.put(key, new Claim(f.getUUID(), now + CLAIM_TICKS, level, words));
        return true;
    }

    private static boolean claimed(Works w, String key, VillageFolkEntity f, long now) {
        Map<String, Claim> m = CLAIMS.get(w.village);
        Claim c = m == null ? null : m.get(key);
        return c != null && c.until() > now && !c.by().equals(f.getUUID()) && onShift(w.village, c.by());
    }

    /** Is this miner on a shift in the works now? A claim held by one called away to other work is let go. */
    private static boolean onShift(UUID village, UUID by) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.getUUID().equals(by)) return a.running(com.jrpetty.mcassistant.entity.goal.WorksGoal.class);
        }
        return false;
    }

    /** Its claim let go (its shift over, or broken off). */
    public static void release(@Nullable UUID village, @Nullable Task t, UUID by) {
        if (village == null || t == null) return;
        Map<String, Claim> m = CLAIMS.get(village);
        Claim c = m == null ? null : m.get(t.claim);
        if (c != null && c.by().equals(by)) m.remove(t.claim);
    }

    /** This miner's next piece of work in the works, claimed for it; null when nothing is free for it now. */
    @Nullable
    public static Task next(ServerLevel level, VillageFolkEntity f) {
        Works w = works(level, f.ownerId());
        if (w == null) return null;
        List<Lvl> plan = plan(level, w);
        if (plan.isEmpty()) return null;
        long now = level.getGameTime();
        newWeek(level, w);
        Lvl want = MineDemand.levelFor(level, w, plan, f);
        Task t = workOn(level, w, plan, want, f, now);
        if (t != null) return t;
        // Nothing free on its own level: the nearest other it may work.
        List<Lvl> others = new ArrayList<>(plan);
        others.remove(want);
        others.sort(java.util.Comparator.comparingInt(l -> Math.abs(l.y() - want.y())));
        int tier = f.pickTierCarried();
        for (Lvl l : others) {
            if (!MineDemand.mayWork(plan, l, tier)) continue;
            t = workOn(level, w, plan, l, f, now);
            if (t != null) return t;
        }
        return null;
    }

    @Nullable
    private static Task workOn(ServerLevel level, Works w, List<Lvl> plan, Lvl want, VillageFolkEntity f, long now) {
        if (w.sunk <= want.corner()) {
            // The shaft is not down to it yet: sink it (one miner at a time), or work the deepest level open above it.
            if (claim(w, "shaft", f, now, -1, "sinking the main shaft")) {
                Task t = shaftTask(w, plan);
                if (t != null) return t;
                release(w.village, new Task(Kind.SHAFT, -1, 0, 0, 0, 3, "shaft"), f.getUUID());
            }
            return null;
        }
        if ((w.rooms & (1 << want.id())) == 0) {
            if (!claim(w, "room:" + want.id(), f, now, want.id(), "cutting the landing of " + want.name())) return null;
            return roomTask(w, want);
        }
        return driftTask(level, w, want, f, now);
    }

    @Nullable
    private static Task shaftTask(Works w, List<Lvl> plan) {
        int bottom = plan.get(plan.size() - 1).corner();
        if (w.sunk > bottom) return null;
        Task t = new Task(Kind.SHAFT, -1, 0, w.sunk, 0, 3, "shaft");
        for (int i = w.sunk; i < Math.min(w.sunk + 4, bottom + 1); i++) {
            BlockPos from = i == 0 ? approach(w) : stepPoint(w, i - 1);
            for (BlockPos c : stepCells(w, i)) {
                t.cells.add(c);
                t.stand.add(from);
            }
        }
        t.route = shaftUp(w, w.sunk - 1);
        java.util.Collections.reverse(t.route);
        return t;
    }

    private static Task roomTask(Works w, Lvl l) {
        Task t = new Task(Kind.ROOM, l.id(), 0, 0, 0, 3, "room:" + l.id());
        List<BlockPos> cells = roomCells(w, l);
        Set<Long> open = new HashSet<>();
        for (BlockPos c : stepCells(w, l.corner())) open.add(c.asLong());
        for (BlockPos c : cells) {
            BlockPos from = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (open.contains(c.relative(d).asLong())) { from = c.relative(d); break; }
            }
            if (from == null) from = stepPoint(w, l.corner());
            t.cells.add(c);
            t.stand.add(from);
            open.add(c.asLong());
        }
        t.route = new ArrayList<>(shaftUp(w, l.corner()));
        java.util.Collections.reverse(t.route);
        return t;
    }

    /** The drift work on a level for this miner: its own, a friend's, a new one, anybody's left. */
    @Nullable
    private static Task driftTask(ServerLevel level, Works w, Lvl l, VillageFolkEntity f, long now) {
        UUID me = f.getUUID();
        List<Drift> here = new ArrayList<>();
        for (Drift d : w.drifts) if (d.level == l.id()) here.add(d);
        // Its own drift: the branches behind the face first while two or more wait, then the face.
        for (Drift d : here) {
            if (!me.equals(d.by)) continue;
            Task t = pendingBranches(level, w, d, l) >= 2 ? branchTask(level, w, l, d, f, now) : null;
            if (t == null && d.closed == '-' && !claimed(w, "face:" + d.id, f, now)) t = faceTask(level, w, l, d, f, now);
            if (t == null) t = branchTask(level, w, l, d, f, now);
            if (t != null) return t;
        }
        // Company: a friend's drift (or, for a sociable one, anybody's) to cut the branches of, working the other side of it.
        if (wantsCompany(f, level)) {
            Drift best = null;
            int bestAff = Integer.MIN_VALUE;
            for (Drift d : here) {
                if (d.by == null || me.equals(d.by) || pendingBranches(level, w, d, l) == 0) continue;
                int aff = f.life().affinity(d.by);
                if (aff < Social.FRIEND && !f.life().has(Social.Trait.SOCIABLE)) continue;
                if (aff > bestAff) { bestAff = aff; best = d; }
            }
            if (best != null) {
                Task t = branchTask(level, w, l, best, f, now);
                if (t != null) return t;
            }
        }
        // A drift of its own from a wall of the landing nobody has started from.
        BlockPos r = roomMiddle(w, l);
        List<Direction> ways = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        java.util.Collections.rotate(ways, Math.floorMod(me.hashCode(), 4));          // each its own way first
        for (Direction d : ways) {
            int ox = r.getX() + 2 * d.getStepX(), oz = r.getZ() + 2 * d.getStepZ();
            boolean taken = false;
            for (Drift o : here) if (o.ox == ox && o.oz == oz) { taken = true; break; }
            if (taken || !fits(level, w, ox + d.getStepX(), oz + d.getStepZ())) continue;
            Drift n = new Drift();
            n.id = w.nextId++;
            n.level = l.id();
            n.by = me;
            n.ox = ox;
            n.oz = oz;
            n.heading = d.get2DDataValue();
            w.drifts.add(n);
            save(w);
            Task t = faceTask(level, w, l, n, f, now);
            if (t != null) {
                Villages.tell(w.village, level.getDayTime() / 24000L, f.displayNameCap() + " started a drift " + d.getName() + " on " + l.name() + " of the mine");
                return t;
            }
        }
        // The landing's walls all taken: a cross-cut driven on from the end of a branch, every fourth branch along a drift,
        // so the level is laid out in panels (parallel drifts sixteen apart, their branches meeting between them).
        for (Drift p : here) {
            for (int[] b : p.branches) {
                if (b[3] == 0 || b[2] < p.branchLen(b[0]) || b[0] % (4 * BRANCH_EVERY) != 0 || b[0] >= p.path.length()) continue;
                Direction along = dirOf(p.path.charAt(b[0]));
                Direction o = b[1] > 0 ? along.getClockWise() : along.getCounterClockWise();
                BlockPos end = p.cell(b[0], l.y()).relative(o, b[2]);
                boolean taken = false;
                for (Drift x : here) if (x.ox == end.getX() && x.oz == end.getZ()) { taken = true; break; }
                if (taken || !fitsAhead(level, w, end, o, 3)) continue;
                Drift n = new Drift();
                n.id = w.nextId++;
                n.level = l.id();
                n.by = me;
                n.ox = end.getX();
                n.oz = end.getZ();
                n.heading = o.get2DDataValue();
                n.parent = p.id;
                n.pAnchor = b[0];
                n.pSide = b[1];
                w.drifts.add(n);
                save(w);
                Task t = faceTask(level, w, l, n, f, now);
                if (t != null) return t;
            }
        }
        // Anybody's branches; then a drift whose miner is not at its face.
        for (Drift d : here) {
            Task t = branchTask(level, w, l, d, f, now);
            if (t != null) return t;
        }
        for (Drift d : here) {
            if (d.closed != '-' || claimed(w, "face:" + d.id, f, now)) continue;
            Task t = faceTask(level, w, l, d, f, now);
            if (t != null) return t;
        }
        return null;
    }

    /** A sociable one always likes company; a shy one never; the rest one day in three. */
    static boolean wantsCompany(VillageFolkEntity f, ServerLevel level) {
        if (f.life().has(Social.Trait.SOCIABLE)) return true;
        if (f.life().has(Social.Trait.SHY)) return false;
        return Math.floorMod(f.getUUID().hashCode() + level.getDayTime() / 24000L, 3) == 0;
    }

    static int pendingBranches(ServerLevel level, Works w, Drift d, Lvl l) {
        int n = 0;
        for (int a = BRANCH_EVERY; a < d.path.length() - 1; a += BRANCH_EVERY) {
            if (!anchor(d, a)) continue;
            for (int side : new int[]{ 1, -1 }) {
                int[] b = d.branch(a, side);
                if (b == null || b[3] == 0) n++;
            }
        }
        return n;
    }

    /** A branch may be cut here: the drift runs straight through it (not by a turn). */
    static boolean anchor(Drift d, int a) {
        if (a < 1 || a + 1 >= d.path.length()) return false;
        char c = d.path.charAt(a);
        return d.path.charAt(a - 1) == c && d.path.charAt(a + 1) == c;
    }

    @Nullable
    private static Task branchTask(ServerLevel level, Works w, Lvl l, Drift d, VillageFolkEntity f, long now) {
        for (int a = BRANCH_EVERY; a < d.path.length() - 1; a += BRANCH_EVERY) {
            if (!anchor(d, a)) continue;
            for (int side : new int[]{ 1, -1 }) {
                int[] b = d.branch(a, side);
                if (b != null && b[3] != 0) continue;
                String key = "branch:" + d.id + ":" + a + ":" + side;
                if (claimed(w, key, f, now)) continue;
                Direction along = dirOf(d.path.charAt(a));
                Direction out = side > 0 ? along.getClockWise() : along.getCounterClockWise();
                BlockPos root = d.cell(a, l.y());
                int done = b == null ? 0 : b[2];
                int len = d.branchLen(a);
                Task t = new Task(Kind.BRANCH, l.id(), d.id, a, side, 2, key);
                for (int j = done + 1; j <= len; j++) {
                    BlockPos c = root.relative(out, j);
                    if (!fits(level, w, c.getX(), c.getZ())) break;
                    t.cells.add(c);
                    t.stand.add(root.relative(out, j - 1));
                }
                if (t.cells.isEmpty()) {                     // nothing to cut there: done with it
                    if (b == null) d.branches.add(new int[]{ a, side, done, 1 }); else b[3] = 1;
                    save(w);
                    continue;
                }
                if (!claim(w, key, f, now, l.id(), "cutting a branch of " + who(w, d) + " drift on " + l.name())) continue;
                t.route = new ArrayList<>(driftUp(w, l, d, a));
                if (done > 0) for (int j = done; j >= 1; j--) t.route.add(0, root.relative(out, j));
                java.util.Collections.reverse(t.route);
                return t;
            }
        }
        return null;
    }

    private static String who(Works w, Drift d) {
        AssistantEntity a = null;
        if (d.by != null) for (AssistantEntity x : Villages.folkOf(w.village)) if (x.getUUID().equals(d.by)) a = x;
        return a != null ? a.displayNameCap() + "'s" : "a";
    }

    /** The face of a drift: its next few cells, turning first where it is due to turn or must. */
    @Nullable
    private static Task faceTask(ServerLevel level, Works w, Lvl l, Drift d, VillageFolkEntity f, long now) {
        if (d.closed != '-') return null;
        Direction dir = d.dir();
        BlockPos tip = d.path.length() == 0 ? new BlockPos(d.ox, l.y(), d.oz) : d.cell(d.path.length() - 1, l.y());
        Direction head = Direction.from2DDataValue(d.heading);
        boolean onHeading = dir == head;
        int straight = d.path.length() == 0 ? 0 : d.straight();
        Direction way = dir;
        boolean edge = !fitsAhead(level, w, tip, dir, 1);
        boolean blocked = d.blocked || edge;
        if (blocked) {
            way = turn(level, w, d, tip, dir, head, true);
        } else if (d.path.length() > 2 && (onHeading ? straight >= d.segLen() : straight >= d.jogLen())) {
            Direction t = turn(level, w, d, tip, dir, head, false);
            if (t != null) way = t;
        }
        if (way == null) {
            d.closed = d.blocked ? d.why : 'e';
            d.blocked = false;
            save(w);
            return null;
        }
        if (way != dir) {
            d.turns++;
            if (edge && onHeading && way != head) {                     // bent away for good at the edge; round water, lava or a cave, a dog-leg
                d.heading = way.get2DDataValue();
                d.bends++;
            }
        }
        d.blocked = false;
        if (!claim(w, "face:" + d.id, f, now, l.id(), "driving " + who(w, d) + " drift " + Direction.from2DDataValue(d.heading).getName() + " on " + l.name())) {
            save(w);
            return null;
        }
        Task t = new Task(Kind.FACE, l.id(), d.id, d.path.length(), 0, 2, "face:" + d.id);
        BlockPos at = tip;
        int run = way == dir ? straight : 0;
        boolean wayIsHead = way == Direction.from2DDataValue(d.heading);
        for (int k = 0; k < FACE_CELLS; k++) {
            BlockPos c = at.relative(way);
            if (!fits(level, w, c.getX(), c.getZ())) break;
            t.cells.add(c);
            t.stand.add(at);
            at = c;
            run++;
            if (d.path.length() + k + 1 > 2 && run >= (wayIsHead ? d.segLen() : d.jogLen())) break;   // a turn is due here
        }
        save(w);
        if (t.cells.isEmpty()) {
            release(w.village, t, f.getUUID());
            return null;
        }
        t.route = new ArrayList<>(driftUp(w, l, d, d.path.length() - 1));
        java.util.Collections.reverse(t.route);
        return t;
    }

    private static boolean fitsAhead(ServerLevel level, Works w, BlockPos from, Direction d, int n) {
        for (int i = 1; i <= n; i++) {
            BlockPos c = from.relative(d, i);
            if (!fits(level, w, c.getX(), c.getZ())) return false;
        }
        return true;
    }

    /**
     * Which way a drift turns. Due a turn on a long straight run: a dog-leg to one side (each drift its own way first, the
     * next the other), and back on its heading after a few blocks. Blocked: to whichever side has room for three blocks
     * (two bends at most, then it is closed); on a dog-leg, back to its heading. Null when it can go no further.
     */
    @Nullable
    private static Direction turn(ServerLevel level, Works w, Drift d, BlockPos tip, Direction cur, Direction head, boolean blocked) {
        if (cur != head) {
            if (fitsAhead(level, w, tip, head, 3)) return head;
            return blocked ? null : cur;
        }
        if (blocked && d.bends >= 2) return null;
        Direction a = Math.floorMod(d.id + d.turns, 2) == 0 ? head.getClockWise() : head.getCounterClockWise();
        for (Direction side : new Direction[]{ a, a.getOpposite() }) {
            if (fitsAhead(level, w, tip, side, 3) && !runsBack(d, side)) return side;
        }
        return null;
    }

    /** Would it run back along its own way (the way it came in by on its last bend)? */
    private static boolean runsBack(Drift d, Direction side) {
        for (int i = 0; i < d.path.length(); i++) if (dirOf(d.path.charAt(i)) == side.getOpposite()) return true;
        return false;
    }

    // ------------------------------------------------------------------ ways

    /** From step i up the shaft to the way in. */
    static List<BlockPos> shaftUp(Works w, int i) {
        List<BlockPos> out = new ArrayList<>();
        for (int k = i; k >= 0; k--) out.add(stepPoint(w, k));
        out.add(approach(w));
        return out;
    }

    static List<BlockPos> roomUp(Works w, Lvl l) {
        int[] o = diag(l.side());
        List<BlockPos> out = new ArrayList<>();
        out.add(roomMiddle(w, l));
        out.add(new BlockPos(w.sx + 4 * o[0], l.y(), w.sz + 4 * o[1]));
        out.add(new BlockPos(w.sx + 3 * o[0], l.y(), w.sz + 4 * o[1]));
        out.add(new BlockPos(w.sx + 3 * o[0], l.y(), w.sz + 3 * o[1]));
        out.addAll(shaftUp(w, l.corner()));
        return out;
    }

    static List<BlockPos> driftUp(Works w, Lvl l, Drift d, int k) {
        List<BlockPos> out = new ArrayList<>();
        List<BlockPos> cells = d.cells(l.y());
        for (int i = Math.min(k, cells.size() - 1); i >= 0; i--) out.add(cells.get(i));
        out.add(new BlockPos(d.ox, l.y(), d.oz));
        Drift p = d.parent == 0 ? null : drift(w, d.parent);
        if (p != null && p != d && d.pAnchor < p.path.length()) {
            // A cross-cut: back along the branch it was driven on from, to its parent drift, and that drift's way up.
            BlockPos root = p.cell(d.pAnchor, l.y());
            Direction along = dirOf(p.path.charAt(d.pAnchor));
            Direction o = d.pSide > 0 ? along.getClockWise() : along.getCounterClockWise();
            int len = Math.max(Math.abs(d.ox - root.getX()), Math.abs(d.oz - root.getZ()));
            for (int j = len - 1; j >= 1; j--) out.add(root.relative(o, j));
            out.addAll(driftUp(w, l, p, d.pAnchor));
            return out;
        }
        out.addAll(roomUp(w, l));
        return out;
    }

    /** The way from where this folk stands to the stand of its task's first cell. */
    public static List<BlockPos> route(ServerLevel level, AssistantEntity a, Task t) {
        List<BlockPos> up = wayUp(level, a);
        List<BlockPos> down = t.route;                       // surface first
        if (up == null || up.isEmpty()) return new ArrayList<>(down);
        Map<Long, Integer> at = new HashMap<>();
        for (int i = 0; i < down.size(); i++) at.put(down.get(i).asLong(), i);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : up) {
            out.add(p);
            Integer i = at.get(p.asLong());
            if (i != null) {
                out.addAll(down.subList(i + 1, down.size()));
                return out;
            }
        }
        out.addAll(down);
        return out;
    }

    /** The way up to the surface from where this folk stands, by the works' tunnels and the shaft; null on the surface. */
    @Nullable
    public static List<BlockPos> wayUp(ServerLevel level, AssistantEntity a) {
        Works w = works(level, villageOf(a));
        if (w == null) return null;
        BlockPos p = a.blockPosition();
        if (p.getY() >= w.top - 1 && !inShaftTop(w, p)) return null;
        List<Lvl> plan = plan(level, w);
        List<BlockPos> best = null;
        double bestD = Double.MAX_VALUE;
        Task last = LAST.get(a.getUUID());
        for (Lvl l : plan) {
            if (Math.abs(p.getY() - l.y()) > 2) continue;
            if ((w.rooms & (1 << l.id())) != 0) {
                BlockPos r = roomMiddle(w, l);
                if (Math.max(Math.abs(p.getX() - r.getX()), Math.abs(p.getZ() - r.getZ())) <= ROOM_HALF + 1) {
                    double dd = r.distSqr(p);
                    if (dd < bestD) { bestD = dd; best = roomUp(w, l); }
                }
            }
            for (Drift d : w.drifts) {
                if (d.level != l.id()) continue;
                List<BlockPos> cells = d.cells(l.y());
                for (int i = 0; i < cells.size(); i++) {
                    double dd = cells.get(i).distSqr(p);
                    if (dd < bestD) { bestD = dd; best = driftUp(w, l, d, i); }
                }
                for (int[] b : d.branches) {
                    if (b[0] >= cells.size()) continue;
                    Direction along = dirOf(d.path.charAt(b[0]));
                    Direction out = b[1] > 0 ? along.getClockWise() : along.getCounterClockWise();
                    for (int j = 1; j <= b[2]; j++) {
                        BlockPos c = cells.get(b[0]).relative(out, j);
                        double dd = c.distSqr(p);
                        if (dd < bestD) {
                            bestD = dd;
                            best = new ArrayList<>();
                            for (int k = j; k >= 1; k--) best.add(cells.get(b[0]).relative(out, k));
                            best.addAll(driftUp(w, l, d, b[0]));
                        }
                    }
                }
            }
            if (last != null && last.level == l.id()) {
                for (int i = 0; i < last.cut && i < last.cells.size(); i++) {
                    double dd = last.cells.get(i).distSqr(p);
                    if (dd < bestD - 0.5 && last.kind == Kind.ROOM) { bestD = dd; best = roomUp(w, l); }
                }
            }
        }
        for (int i = 0; i < w.sunk; i++) {
            double dd = stepPoint(w, i).distSqr(p);
            if (dd < bestD) { bestD = dd; best = shaftUp(w, i); }
        }
        if (best == null) return null;
        List<BlockPos> out = new ArrayList<>();
        out.add(p);
        out.addAll(best);
        return out;
    }

    @Nullable
    static UUID villageOf(AssistantEntity a) {
        return a instanceof VillageFolkEntity f ? f.ownerId() : null;
    }

    /** Below ground in the works: under the top of the shaft, within the mine's faces. */
    public static boolean below(AssistantEntity a) {
        if (!(a.level() instanceof ServerLevel level)) return false;
        UUID v = villageOf(a);
        Works w = v == null ? null : works(level, v);
        if (w == null) return false;
        BlockPos p = a.blockPosition();
        return p.getY() < w.top - 2 && (TownMine.inMine(v, p) || Math.max(Math.abs(p.getX() - w.sx), Math.abs(p.getZ() - w.sz)) <= 80);
    }

    /** [mine8] Is this column in the ground of a town's mine whose works are laid out (no hand cuts stone or earth there)? */
    public static boolean keepsOff(ServerLevel level, BlockPos p) {
        if (!MineGround.anyMeets(p.getX(), p.getZ(), p.getX(), p.getZ())) return false;
        Villages.Village v = MineGround.villageAt(level, p);
        return v == null || Ledger.note(v.id(), NOTE) != null;
    }

    /** [mine-safety] Is this spot down in a town's works (MineSafety.dug)? */
    static boolean dug(ServerLevel level, UUID village, BlockPos p) {
        Works w = works(level, village);
        return w != null && w.sunk > 4 && p.getY() < w.top - 2 && TownMine.inMine(village, p);
    }

    // ------------------------------------------------------------------ the work reported

    /** The last few things done in the works, any town's (the tests and the debug line read them). */
    private static final java.util.concurrent.ConcurrentLinkedDeque<String> EVENTS = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public static void event(AssistantEntity a, String what) {
        UUID v = villageOf(a);
        EVENTS.addLast((v == null ? "-" : v.toString()) + "|" + a.level().getGameTime() + " " + a.displayNameCap() + ": " + what);
        while (EVENTS.size() > 60) EVENTS.pollFirst();
    }

    /** The last few things done in this town's works, latest last. */
    static List<String> lately(UUID village, int most) {
        List<String> out = new ArrayList<>();
        String key = village.toString() + "|";
        for (String e : EVENTS) if (e.startsWith(key)) out.add(e.substring(key.length()));
        return out.subList(Math.max(0, out.size() - most), out.size());
    }

    public static List<String> eventsForTests() {
        List<String> out = new ArrayList<>();
        for (String e : EVENTS) out.add(e.substring(e.indexOf('|') + 1));
        return out;
    }

    /** The task worked: the record brought up to date, the claim let go. */
    public static void done(ServerLevel level, VillageFolkEntity f, Task t) {
        event(f, t.words() + ": " + t.cut + " of " + t.cells.size() + " cells" + (t.why.isEmpty() ? "" : ", stopped: " + t.why));
        Works w = works(level, f.ownerId());
        LAST.put(f.getUUID(), t);
        if (w == null) return;
        newWeek(level, w);
        w.dug += t.broke;
        if (t.level >= 0 && t.level < 5) {
            w.oreWeek[t.level] += t.ore;
            w.oreAll[t.level] += t.ore;
        }
        List<Lvl> plan = plan(level, w);
        switch (t.kind) {
            case SHAFT -> {
                int steps = 0, k = 0;
                for (int i = t.anchor; k < t.cells.size(); i++) {
                    int n = stepCells(w, i).size();
                    if (k + n <= t.cut) { steps++; k += n; } else break;
                }
                int before = w.sunk;
                w.sunk = Math.max(w.sunk, t.anchor + steps);
                for (Lvl l : plan) {
                    if (before <= l.corner() && w.sunk > l.corner()) {
                        Villages.tell(w.village, level.getDayTime() / 24000L, "the mine's main shaft was sunk to " + l.name() + " (Y" + l.y() + ")");
                    }
                }
            }
            case ROOM -> {
                if (t.cut >= t.cells.size()) {
                    w.rooms |= 1 << t.level;
                    Lvl l = lvl(plan, t.level);
                    if (l != null) furnish(level, w, l);
                    Villages.tell(w.village, level.getDayTime() / 24000L, "the landing of " + NAMES[t.level] + " of the mine was cut, and its drifts begun");
                }
            }
            case FACE -> {
                Drift d = drift(w, t.drift);
                if (d != null) {
                    for (int i = 0; i < t.cut && i < t.cells.size(); i++) {
                        BlockPos from = t.stand.get(i), to = t.cells.get(i);
                        d.path.append(letter(Direction.fromDelta(to.getX() - from.getX(), 0, to.getZ() - from.getZ())));
                    }
                    if (!t.why.isEmpty()) {
                        if (t.why.startsWith("tunnel")) d.closed = 'j';
                        else {
                            d.blocked = true;
                            d.why = switch (t.why) { case "water" -> 'w'; case "lava" -> 'l'; case "cave" -> 'c'; case "rock" -> 'r'; case "made" -> 'm'; default -> 'e'; };
                        }
                    }
                }
            }
            case BRANCH -> {
                Drift d = drift(w, t.drift);
                if (d != null && t.cut > 0 && d.by != null && !d.by.equals(f.getUUID())) JOINS++;   // working the other side of a mate's drift
                if (d != null) {
                    int[] b = d.branch(t.anchor, t.side);
                    if (b == null) { b = new int[]{ t.anchor, t.side, 0, 0 }; d.branches.add(b); }
                    b[2] += t.cut;
                    if (!t.why.isEmpty() || t.cut >= t.cells.size()) b[3] = 1;
                }
            }
        }
        save(w);
        release(w.village, t, f.getUUID());
    }

    @Nullable
    static Drift drift(Works w, int id) {
        for (Drift d : w.drifts) if (d.id == id) return d;
        return null;
    }

    private static void newWeek(ServerLevel level, Works w) {
        long week = level.getDayTime() / 24000L / 7;
        if (week != w.week) {
            w.week = week;
            java.util.Arrays.fill(w.oreWeek, 0);
        }
    }

    // ------------------------------------------------------------------ the dressing of the works

    /** Is there something wrong ahead in this cell (the goal asks before cutting it): why, or null. Water and lava in a
     *  drift's way are left behind its wall; in the shaft and the landing they are sealed and cut through. */
    @Nullable
    public static String ahead(ServerLevel level, Task t, BlockPos cell) {
        boolean tunnel = t.kind == Kind.FACE || t.kind == Kind.BRANCH;
        int open = 0;
        for (int h = 0; h < t.height; h++) {
            BlockPos p = cell.above(h);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) {
                if (tunnel) return s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water";
                continue;
            }
            if (s.isAir() || s.canBeReplaced()) { open++; continue; }
            if (s.getDestroySpeed(level, p) < 0) return "rock";
            if (s.hasBlockEntity()) return "made";
        }
        if (tunnel && open >= t.height) {
            // Open already: one of the works' own tunnels (it has met it), or a cave.
            return ourTunnel(level, t, cell) ? "tunnel" : "cave";
        }
        if (tunnel) {
            for (Direction d : Direction.values()) {
                for (int h = 0; h < t.height; h++) {
                    if (level.getFluidState(cell.above(h).relative(d)).is(net.minecraft.tags.FluidTags.LAVA) && d != Direction.DOWN) return "lava";
                }
            }
        }
        return null;
    }

    private static boolean ourTunnel(ServerLevel level, Task t, BlockPos cell) {
        UUID v = null;
        for (Map.Entry<UUID, Works> e : CACHE.entrySet()) {
            Works w = e.getValue();
            if (Math.abs(cell.getX() - w.sx) < 100 && Math.abs(cell.getZ() - w.sz) < 100) v = e.getKey();
        }
        if (v == null) return false;
        Works w = CACHE.get(v);
        for (Drift d : w.drifts) {
            if (d.level != t.level) continue;
            for (BlockPos c : d.cells(cell.getY())) if (c.equals(cell)) return true;
        }
        return false;
    }

    /** A cell cut: what goes in it (the stair of a step, a torch, a timber set), as the works are kept. */
    public static void dress(ServerLevel level, AssistantEntity a, Task t, int i) {
        Works w = works(level, villageOf(a));
        if (w == null) return;
        BlockPos c = t.cells.get(i);
        switch (t.kind) {
            case SHAFT -> {
                // The step's index and whether this is its last cell.
                int k = 0, step = t.anchor;
                while (true) {
                    int n = stepCells(w, step).size();
                    if (i < k + n) {
                        if (i == k + n - 1) dressStep(level, w, a, step);
                        break;
                    }
                    k += n;
                    step++;
                }
            }
            case FACE -> {
                int idx = t.anchor + i;
                Direction along = Direction.fromDelta(c.getX() - t.stand.get(i).getX(), 0, c.getZ() - t.stand.get(i).getZ());
                if (along == null) return;
                if (idx % FRAME_EVERY == 0) frame(level, w, c, along, 2);
                else if (idx % TORCH_EVERY == 3) torch(level, a, c.above(), Math.floorMod(idx / TORCH_EVERY, 2) == 0 ? along.getClockWise() : along.getCounterClockWise());
            }
            case BRANCH -> {
                if (i == 3 || t.anchor % 8 == 0 && i == 1) {
                    Direction along = Direction.fromDelta(c.getX() - t.stand.get(i).getX(), 0, c.getZ() - t.stand.get(i).getZ());
                    if (along != null && i == 3) torch(level, a, c.above(), along.getClockWise());
                }
            }
            case ROOM -> { }
        }
    }

    /** A step finished: its floor laid as stairs of the rock it is cut in; a torch on each landing; a set across mid-side. */
    private static void dressStep(ServerLevel level, Works w, AssistantEntity a, int step) {
        int side = (step / 4) % 4, k = step % 4;
        List<BlockPos> cells = stepCells(w, step);
        if (k == 0) {
            BlockPos outer = cells.get(3);
            int[] o = diag(side);
            torch(level, a, outer.above(), o[0] < 0 ? Direction.WEST : Direction.EAST);
            return;
        }
        Direction down = walk(side);
        for (BlockPos c : cells) {
            BlockPos floor = c.below();
            BlockState was = level.getBlockState(floor);
            Block stair = stairFor(was);
            BlockState st = stair.defaultBlockState().setValue(StairBlock.FACING, down.getOpposite());
            if (was.isAir() || !was.getFluidState().isEmpty()) {
                if (a.removeMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), 1) == 0) continue;
            } else if (!com.jrpetty.mcassistant.entity.goal.MineGoal.wild(level, floor, was)) continue;
            level.setBlock(floor, st, 3);
        }
        if (k == 2) frame(level, w, cells.get(0), down, 3);
    }

    private static Block stairFor(BlockState rock) {
        if (rock.is(Blocks.DEEPSLATE) || rock.is(Blocks.COBBLED_DEEPSLATE) || rock.is(Blocks.TUFF) || rock.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) return Blocks.COBBLED_DEEPSLATE_STAIRS;
        if (rock.is(Blocks.STONE) || rock.is(BlockTags.STONE_ORE_REPLACEABLES)) return Blocks.STONE_STAIRS;
        return Blocks.COBBLESTONE_STAIRS;
    }

    /** A wall torch here on the wall to that side, out of the miner's pack, where it is dark and the wall is rock. */
    static void torch(ServerLevel level, AssistantEntity a, BlockPos at, Direction wallSide) {
        BlockPos wall = at.relative(wallSide);
        if (!level.getBlockState(at).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, wallSide.getOpposite())) return;
        if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at) > 9) return;
        if (a.removeMatching(s -> s.is(Items.TORCH), 1) != 1) return;
        level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, wallSide.getOpposite()), 3);
        a.placeSound(at);
    }

    /** A timber set across the way here: a post in each wall, a cap over them, of the town's wood; in the rock only. */
    static void frame(ServerLevel level, Works w, BlockPos c, Direction along, int high) {
        String wood = wood(level, w);
        Block post = log(wood, true), cap = log(wood, false);
        Direction side = along.getClockWise();
        // A post in each wall: the shaft's two-wide steps have theirs a lane further out.
        BlockPos left = c.relative(side.getOpposite()), right = c.relative(side);
        if (high == 3) {
            BlockPos other = c.relative(side);
            boolean two = level.getBlockState(other).isAir() && level.getBlockState(other.above()).isAir();
            if (two) right = other.relative(side);
            else { left = c.relative(side.getOpposite(), 2); }
        }
        for (int h = 0; h < high; h++) {
            setInRock(level, left.above(h), post.defaultBlockState());
            setInRock(level, right.above(h), post.defaultBlockState());
        }
        Direction.Axis axis = side.getAxis();
        int span = (int) Math.round(Math.sqrt(left.distSqr(right)));
        for (int s = 0; s <= span; s++) {
            BlockPos p = left.relative(side, s).above(high);
            BlockState st = cap.defaultBlockState();
            if (st.hasProperty(RotatedPillarBlock.AXIS)) st = st.setValue(RotatedPillarBlock.AXIS, axis);
            setInRock(level, p, st);
        }
    }

    private static void setInRock(ServerLevel level, BlockPos p, BlockState st) {
        BlockState was = level.getBlockState(p);
        if (!was.isAir() && was.getFluidState().isEmpty() && !com.jrpetty.mcassistant.entity.goal.MineGoal.wild(level, p, was)) return;
        if (was.isAir() || !was.getFluidState().isEmpty()) return;     // into the rock, never hung in the open
        if (com.jrpetty.mcassistant.entity.OreSight.isOre(was)) return; // an ore left for the miner
        level.setBlock(p, st, 3);
    }

    private static final Map<UUID, String> WOOD = new ConcurrentHashMap<>();

    static String wood(ServerLevel level, Works w) {
        String s = WOOD.get(w.village);
        if (s != null) return s;
        Villages.Village v = Villages.get(w.village);
        s = v == null ? "spruce" : Masonry.woodOf(level, v);
        WOOD.put(w.village, s);
        return s;
    }

    static Block log(String wood, boolean stripped) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace((stripped ? "stripped_" : "") + wood + "_log"));
        if (b == Blocks.AIR) b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace((stripped ? "stripped_" : "") + wood + "_stem"));
        return b == Blocks.AIR ? (stripped ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.SPRUCE_LOG) : b;
    }

    /** The landing furnished: a chest, a crafting table and a bench, a lantern hung in the middle, a timber set at the mouth. */
    static void furnish(ServerLevel level, Works w, Lvl l) {
        int[] o = diag(l.side());
        int y = l.y();
        BlockPos chest = new BlockPos(w.sx + 8 * o[0], y, w.sz + 8 * o[1]);
        BlockPos table = new BlockPos(w.sx + 8 * o[0], y, w.sz + 4 * o[1]);
        BlockPos bench = new BlockPos(w.sx + 4 * o[0], y, w.sz + 8 * o[1]);
        BlockPos lamp = roomMiddle(w, l).above(2);
        if (level.getBlockState(chest).isAir()) level.setBlock(chest, Blocks.CHEST.defaultBlockState()
            .setValue(net.minecraft.world.level.block.ChestBlock.FACING, o[0] < 0 ? Direction.EAST : Direction.WEST), 3);
        if (level.getBlockState(table).isAir()) level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        String wood = wood(level, w);
        Block stairs = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_stairs"));
        if (stairs == Blocks.AIR) stairs = Blocks.SPRUCE_STAIRS;
        if (level.getBlockState(bench).isAir()) level.setBlock(bench, stairs.defaultBlockState()
            .setValue(StairBlock.FACING, o[1] < 0 ? Direction.NORTH : Direction.SOUTH), 3);
        if (level.getBlockState(lamp).isAir() && level.getBlockState(lamp.above()).isFaceSturdy(level, lamp.above(), Direction.DOWN)) {
            level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 3);
        }
        // A torch either side of the room, on the walls away from the drifts' mouths.
        BlockPos r = roomMiddle(w, l);
        for (int s = -1; s <= 1; s += 2) {
            BlockPos at = new BlockPos(r.getX() + s, y + 1, r.getZ() + 2 * -o[1]);
            BlockPos wall = at.relative(o[1] < 0 ? Direction.SOUTH : Direction.NORTH);
            if (level.getBlockState(at).isAir() && level.getBlockState(wall).isSolid()) {
                level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, o[1] < 0 ? Direction.NORTH : Direction.SOUTH), 3);
            }
        }
    }

    // ------------------------------------------------------------------ the pithead

    /** Every half minute (TownDigs.tick): the headframe over the shaft, the railings round its open top, the spoil heap. */
    public static void tick(ServerLevel level, Villages.Village v) {
        Works w = works(level, v.id());
        if (w == null || !Land.areaLoaded(level, new BlockPos(w.sx, w.top, w.sz), 12)) return;
        boolean changed = false;
        clearing(level, w, 96);
        if (!w.head && w.sunk >= 4) {
            headframe(level, v, w);
            w.head = true;
            changed = true;
        }
        if (w.sunk >= 2) rail(level, v, w);
        int want = Math.min(60, w.dug / 96);
        for (int n = 0; w.heap < want && n < 8; n++) {
            if (!heap(level, v, w, w.heap)) { w.heap = want; break; }
            w.heap++;
            changed = true;
        }
        if (changed) save(w);
    }

    /**
     * The pithead cleared of the trees over it (the trunks and the crowns standing within seven blocks of the shaft's
     * middle, from the top of the shaft up), no more than so many blocks a look: a pithead in a wood had its headframe
     * lost in the leaves and a tree growing out of the pillar.
     */
    static void clearing(ServerLevel level, Works w, int most) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = w.top + 24; y >= w.top - 1 && n < most; y--) {
            for (int dx = -7; dx <= 7 && n < most; dx++) {
                for (int dz = -7; dz <= 7 && n < most; dz++) {
                    p.set(w.sx + dx, y, w.sz + dz);
                    BlockState s = level.getBlockState(p);
                    boolean leaf = s.is(BlockTags.LEAVES);
                    boolean trunk = s.is(BlockTags.LOGS) && com.jrpetty.mcassistant.entity.goal.BuildGoal.isTreeLog(level, p);
                    if (!leaf && !trunk) continue;
                    boolean frame = Math.abs(dx) <= 1 && Math.abs(dz) <= 1
                        && (Math.abs(dx) == 1 && Math.abs(dz) == 1 && y >= w.top && y <= w.top + 3 || y == w.top + 4);
                    if (trunk && frame && w.head) continue;                                        // the headframe's own timbers
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                    n++;
                }
            }
        }
    }

    /** Over the pillar: four posts, a frame of beams on them, a beam across with the lantern hung from it. */
    static void headframe(ServerLevel level, Villages.Village v, Works w) {
        String wood = wood(level, w);
        Block post = log(wood, true), beam = log(wood, false);
        int y = w.top;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                for (int h = 0; h < 4; h++) put(level, v, new BlockPos(w.sx + dx, y + h, w.sz + dz), post.defaultBlockState());
            }
        }
        for (int a = -1; a <= 1; a++) {
            put(level, v, new BlockPos(w.sx + a, y + 4, w.sz - 1), beam.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            put(level, v, new BlockPos(w.sx + a, y + 4, w.sz + 1), beam.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            put(level, v, new BlockPos(w.sx + a, y + 4, w.sz), beam.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        }
        put(level, v, new BlockPos(w.sx, y + 3, w.sz), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        Block fence = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_fence"));
        if (fence == Blocks.AIR) fence = Blocks.SPRUCE_FENCE;
        put(level, v, new BlockPos(w.sx, y, w.sz - 1), Block.updateFromNeighbourShapes(fence.defaultBlockState(), level, new BlockPos(w.sx, y, w.sz - 1)));
        Villages.tell(w.village, level.getDayTime() / 24000L, "a headframe went up over the mine's main shaft");
    }

    /** A fence round each column of the shaft open to the sky, the way in (the first landing) left open. */
    static void rail(ServerLevel level, Villages.Village v, Works w) {
        Set<Long> open = new HashSet<>();
        Set<Long> shaft = new HashSet<>();
        for (int dx = -RING; dx <= RING; dx++) {
            for (int dz = -RING; dz <= RING; dz++) {
                int x = w.sx + dx, z = w.sz + dz;
                if (Math.max(Math.abs(dx), Math.abs(dz)) >= 2) shaft.add(BlockPos.asLong(x, 0, z));
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) continue;
                if (level.getBlockState(new BlockPos(x, w.top - 1, z)).isAir() && level.getBlockState(new BlockPos(x, w.top - 2, z)).isAir()) {
                    open.add(BlockPos.asLong(x, 0, z));
                }
            }
        }
        if (open.isEmpty()) return;
        Set<Long> way = new HashSet<>();
        for (BlockPos c : stepCells(w, 0)) way.add(BlockPos.asLong(c.getX(), 0, c.getZ()));
        way.add(BlockPos.asLong(w.sx - 4, 0, w.sz - 2));
        way.add(BlockPos.asLong(w.sx - 4, 0, w.sz - 3));
        String wood = wood(level, w);
        Block fence = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_fence"));
        if (fence == Blocks.AIR) fence = Blocks.SPRUCE_FENCE;
        Set<Long> done = new HashSet<>();
        for (long o : open) {
            int ox = BlockPos.getX(o), oz = BlockPos.getZ(o);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long n = BlockPos.asLong(ox + dx, 0, oz + dz);
                    if (open.contains(n) || way.contains(n) || !done.add(n)) continue;
                    // Never on a step of the stair (its first steps are the way down, open at head height): round the
                    // outside of the shaft and on the edge of the pillar only.
                    int ring = Math.max(Math.abs(ox + dx - w.sx), Math.abs(oz + dz - w.sz));
                    if (ring == 2 || ring == 3) continue;
                    BlockPos p = new BlockPos(ox + dx, w.top, oz + dz);
                    if (!level.getBlockState(p).canBeReplaced() || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                    put(level, v, p, Block.updateFromNeighbourShapes(fence.defaultBlockState(), level, p));
                }
            }
        }
    }

    /** One more block on the spoil heap: a low mound beside the shaft on the side away from the town. */
    static boolean heap(ServerLevel level, Villages.Village v, Works w, int n) {
        BlockPos heart = v.centre();
        int ax = w.sx >= heart.getX() ? 1 : -1, az = w.sz >= heart.getZ() ? 1 : -1;
        if (ax < 0 && az < 0) az = 1;                        // never on the way in (north-west)
        int cx = w.sx + 8 * ax, cz = w.sz + 8 * az;
        int k = 0;
        for (int h = 0; h < 4; h++) {
            int r = 3 - h;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (h == 0 && Math.abs(dx) == r && Math.abs(dz) == r) continue;   // round off the corners
                    if (k++ != n) continue;
                    BlockPos g = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(cx + dx, 0, cz + dz));
                    if (!level.getFluidState(g.below()).isEmpty() || g.getY() > w.top + h + 1) return true;
                    BlockState st = Math.floorMod(dx * 31 + dz * 17 + h, 5) < 2 ? Blocks.GRAVEL.defaultBlockState()
                        : g.getY() < 0 || w.top < 8 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
                    put(level, v, g, st);
                    return true;
                }
            }
        }
        return false;
    }

    private static void put(ServerLevel level, Villages.Village v, BlockPos p, BlockState st) {
        if (!level.getBlockState(p).canBeReplaced() && !level.getBlockState(p).isAir()) return;
        // Never on anybody standing there.
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(p)).isEmpty()) return;
        if (!TownBlocks.mayPlace(level, v, p, TownBlocks.Why.MINE)) return;
        level.setBlock(p, st, 3);
    }

    // ------------------------------------------------------------------ the miners' side

    /** Does this miner work the town's works: a miner of a town with a mine, its plot at the pithead. */
    public static boolean handles(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null || f.workZone() == null) return false;
        BlockPos site = TownMine.siteOf(f.ownerId());
        if (site == null) return false;
        BlockPos c = f.workZone().center();
        if (Math.abs(c.getX() - site.getX()) > 1 || Math.abs(c.getZ() - site.getZ()) > 1) return false;
        return works(level, f.ownerId()) != null;
    }

    /** The plot every miner of the works holds: the pithead, at ground level (TownMine.faceFor). */
    @Nullable
    public static BlockPos pithead(ServerLevel level, UUID village, BlockPos site) {
        if (!level.hasChunk(site.getX() >> 4, site.getZ() >> 4)) return null;
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site);
    }

    /** The station's turn for a miner of the works: to the pithead, then down for a shift. Whether it did anything. */
    public static boolean station(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return false;
        Works w = works(level, f.ownerId());
        if (w == null) return false;
        long now = level.getGameTime();
        Long rest = REST.get(f.getUUID());
        if (rest != null && now < rest && now > rest - 2400) {
            f.brain("waiting at the pithead: nothing free to cut in the works just now");
            return false;
        }
        BlockPos at = approach(w);
        if (!below(f) && f.blockPosition().distSqr(at) > 16) {
            int tries = f.getNavigation().isDone() ? WALKS.merge(f.getUUID(), 1, Integer::sum) : WALKS.getOrDefault(f.getUUID(), 0);
            if (tries <= 30) {
                if (f.getNavigation().isDone()) f.walkTo(at, 1.1D);
                f.brain("walking to the pithead");
                return true;
            }
        }
        WALKS.remove(f.getUUID());
        int depth = f.workZone() != null ? f.workZone().depth() : 16;
        f.enqueue(Job.mine(depth, ARG));
        f.brain("down the works for a shift");
        return true;
    }

    private static final Map<UUID, Integer> WALKS = new ConcurrentHashMap<>();

    /** Nothing free for it: it waits a minute before it asks again. */
    public static void rest(AssistantEntity a, long now) {
        REST.put(a.getUUID(), now + 1200L);
    }

    /** Is the site's ground a mountain's, or the badlands' (where emeralds lie, and more gold)? */
    static boolean mountain(ServerLevel level, Works w) {
        return level.getBiome(new BlockPos(w.sx, w.top, w.sz)).is(BiomeTags.IS_MOUNTAIN);
    }

    static boolean badlands(ServerLevel level, Works w) {
        return level.getBiome(new BlockPos(w.sx, w.top, w.sz)).is(BiomeTags.IS_BADLANDS);
    }

    // ------------------------------------------------------------------ the books

    /** The works in a few lines: the shaft, each level and its drifts, the miners on each, the ore this week. */
    public static List<String> report(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Works w = works(level, village);
        if (w == null) return out;
        List<Lvl> plan = plan(level, w);
        int deepest = w.sunk == 0 ? w.top : w.top - drop(w.sunk - 1);
        int open = 0;
        for (Lvl l : plan) if (w.sunk > l.corner()) open++;
        out.add("The works: the main shaft at " + w.sx + ", " + w.sz + ", sunk " + (w.top - deepest) + " blocks to Y" + deepest + " (" + w.sunk
            + " steps); " + open + " of " + plan.size() + " levels open" + (w.head ? "; the headframe over it" : "") + ".");
        Map<String, Claim> claims = CLAIMS.getOrDefault(village, Map.of());
        long now = level.getGameTime();
        for (Lvl l : plan) {
            int drifts = 0, cells = 0, branches = 0;
            for (Drift d : w.drifts) {
                if (d.level != l.id()) continue;
                drifts++;
                cells += d.path.length();
                for (int[] b : d.branches) { cells += b[2]; if (b[2] > 0) branches++; }
            }
            List<String> on = new ArrayList<>();
            for (Claim c : claims.values()) {
                if (c.level() != l.id() || c.until() <= now) continue;
                for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(c.by())) on.add(a.displayNameCap() + " " + c.words());
            }
            boolean reached = w.sunk > l.corner();
            out.add(cap(l.name()) + " (Y" + l.y() + "): " + (!reached ? "the shaft not down to it yet"
                : (w.rooms & (1 << l.id())) == 0 ? "the landing being cut" : drifts + " drifts, " + branches + " branches, " + cells + " blocks of tunnel")
                + "; ore this week " + w.oreWeek[l.id()] + " (" + w.oreAll[l.id()] + " in all)" + (on.isEmpty() ? "" : "; " + String.join("; ", on)) + ".");
            int shown = 0;
            for (Drift d : w.drifts) {
                if (d.level != l.id() || shown++ >= 6) continue;
                out.add("  " + who(w, d) + " drift " + Direction.from2DDataValue(d.heading).getName() + ", " + d.path.length() + " long"
                    + (d.turns > 0 ? ", turned " + d.turns + (d.turns == 1 ? " time" : " times") : "") + ", " + d.branches.stream().filter(b -> b[2] > 0).count()
                    + " branches" + (d.closed == '-' ? "" : ", " + ended(d.closed)));
            }
        }
        for (Claim c : claims.values()) {
            if (c.level() != -1 || c.until() <= now) continue;
            for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(c.by())) out.add(a.displayNameCap() + " is sinking the main shaft.");
        }
        out.addAll(MineDemand.lines(level, w, plan));
        for (String e : lately(village, 4)) out.add("Lately: " + e.substring(e.indexOf(' ') + 1));
        return out;
    }

    private static String ended(char c) {
        return switch (c) {
            case 'w' -> "stopped at water";
            case 'l' -> "stopped at lava";
            case 'c' -> "stopped at a cave";
            case 'j' -> "through into another tunnel";
            case 'r' -> "stopped at bedrock";
            case 'm' -> "stopped at something built";
            default -> "at the edge of the mine's ground";
        };
    }

    /** The status's few words: "the works: shaft to Y.., 3 levels open, 7 drifts". */
    @Nullable
    public static String line(ServerLevel level, UUID village) {
        Works w = works(level, village);
        if (w == null) return null;
        List<Lvl> plan = plan(level, w);
        int open = 0;
        for (Lvl l : plan) if (w.sunk > l.corner()) open++;
        int deepest = w.sunk == 0 ? w.top : w.top - drop(w.sunk - 1);
        return "the works: the shaft to Y" + deepest + ", " + open + " of " + plan.size() + " levels open, " + w.drifts.size() + " drifts";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: branches cut by a miner in another's drift. */
    public static volatile int JOINS;

    public static void forgetForTests(UUID village) {
        CACHE.remove(village);
        CLAIMS.remove(village);
        WOOD.remove(village);
        Ledger.forget(village, NOTE);
        MineDemand.forgetForTests(village);
    }

    /** Tests: forget what was read (a save and a load). */
    public static void forgetCache() {
        CACHE.clear();
        CLAIMS.clear();
        LAST.clear();
        REST.clear();
    }

    /** Tests: lay the works out with its top here (the shaft's first landing's feet). */
    public static void layOutForTests(ServerLevel level, UUID village, int top) {
        BlockPos site = TownMine.siteOf(village);
        if (site == null) return;
        Works w = new Works(village);
        w.sx = site.getX();
        w.sz = site.getZ();
        w.top = top;
        save(w);
    }

    /** Tests: the shaft sunk (cut and dressed, for nothing) down to this many steps. */
    public static void sinkForTests(ServerLevel level, UUID village, int steps, @Nullable AssistantEntity a) {
        Works w = works(level, village);
        if (w == null) return;
        for (int i = w.sunk; i < steps; i++) {
            for (BlockPos c : stepCells(w, i)) {
                for (int h = 0; h < 3; h++) level.setBlock(c.above(h), Blocks.AIR.defaultBlockState(), 2);
                if (!level.getBlockState(c.below()).isSolid()) level.setBlock(c.below(), Blocks.STONE.defaultBlockState(), 2);
            }
            if (a != null) dressStep(level, w, a, i);
        }
        w.sunk = Math.max(w.sunk, steps);
        save(w);
    }

    /** Tests: the landing of this level cut and furnished, for nothing. */
    public static void roomForTests(ServerLevel level, UUID village, int id) {
        Works w = works(level, village);
        Lvl l = w == null ? null : lvl(plan(level, w), id);
        if (l == null) return;
        for (BlockPos c : roomCells(w, l)) for (int h = 0; h < 3; h++) level.setBlock(c.above(h), Blocks.AIR.defaultBlockState(), 2);
        w.rooms |= 1 << id;
        furnish(level, w, l);
        save(w);
    }

    /** Tests: the levels planned, as "id:name:y". */
    public static List<String> planForTests(ServerLevel level, UUID village) {
        Works w = works(level, village);
        List<String> out = new ArrayList<>();
        if (w == null) return out;
        for (Lvl l : plan(level, w)) out.add(l.id() + ":" + l.name() + ":" + l.y());
        return out;
    }

    /** Tests: the level this miner is sent to now (shared out afresh). */
    public static int levelForTests(ServerLevel level, VillageFolkEntity f) {
        Works w = works(level, f.ownerId());
        if (w == null) return -1;
        MineDemand.reshare(w.village);
        return MineDemand.levelFor(level, w, plan(level, w), f).id();
    }

    /** Tests: the y of a level, or Integer.MIN_VALUE. */
    public static int levelY(ServerLevel level, UUID village, int id) {
        Works w = works(level, village);
        Lvl l = w == null ? null : lvl(plan(level, w), id);
        return l == null ? Integer.MIN_VALUE : l.y();
    }

    /** Tests: each drift as {id, level, by-hash, length, turns, bends, branches cut, closed char}, and its cells. */
    public static List<int[]> driftsForTests(ServerLevel level, UUID village) {
        Works w = works(level, village);
        List<int[]> out = new ArrayList<>();
        if (w == null) return out;
        for (Drift d : w.drifts) {
            int b = 0;
            for (int[] x : d.branches) if (x[2] > 0) b++;
            out.add(new int[]{ d.id, d.level, d.by == null ? 0 : d.by.hashCode(), d.path.length(), d.turns, d.bends, b, d.closed });
        }
        return out;
    }

    /** Tests: every cell of tunnel the works has cut on a level (drifts and branches), as positions at its floor height. */
    public static List<BlockPos> cellsForTests(ServerLevel level, UUID village, int id) {
        Works w = works(level, village);
        Lvl l = w == null ? null : lvl(plan(level, w), id);
        List<BlockPos> out = new ArrayList<>();
        if (l == null) return out;
        for (Drift d : w.drifts) {
            if (d.level != id) continue;
            List<BlockPos> cells = d.cells(l.y());
            out.addAll(cells);
            for (int[] b : d.branches) {
                if (b[0] >= cells.size()) continue;
                Direction along = dirOf(d.path.charAt(b[0]));
                Direction o = b[1] > 0 ? along.getClockWise() : along.getCounterClockWise();
                for (int j = 1; j <= b[2]; j++) out.add(cells.get(b[0]).relative(o, j));
            }
        }
        return out;
    }

    /** Tests: the shaft's steps cut, its top, its middle. */
    public static int[] shaftForTests(ServerLevel level, UUID village) {
        Works w = works(level, village);
        return w == null ? new int[]{ -1, 0, 0, 0 } : new int[]{ w.sunk, w.top, w.sx, w.sz, w.head ? 1 : 0, w.rooms };
    }

    /** Tests: a step's cells. */
    public static List<BlockPos> stepForTests(ServerLevel level, UUID village, int i) {
        Works w = works(level, village);
        return w == null ? List.of() : stepCells(w, i);
    }
}
