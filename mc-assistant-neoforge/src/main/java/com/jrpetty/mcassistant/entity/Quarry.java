package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] The quarry: the stone supply the town's buildings draw on, well out past the land it will grow into.
 *
 * <p><b>Where.</b> Once a town of ten has its storehouse, the council looks for a site 150 to 250 blocks from the heart,
 * nearest first, ring by ring (the look spread over a minute, the far ground asked for the way the stations ask for theirs,
 * so the world is never stopped to make it): out past the growth line, off the radial roads, the farmland and the mine's
 * ground, on dry ground with no river across the way out, a rock outcrop if there is one (stone at the surface and under it),
 * else the flattest dry ground, dug down. None in the band: a little further, with the reasons in the log.
 *
 * <p><b>The pit, and how it grows.</b> Stage one is nine across, cut in one-block terraces: every cell a block deeper than
 * the one outside it, so a body can always walk in and out. Its outline is never a ruled square: corners rounded off, the
 * edges notched, the terraces following them. Worked out (and wanted: a full yard lets it rest), it grows a stage at a
 * time: six wider (to thirty-three across) while the land allows, then deeper: a shaft five across sunk from the bottom
 * terrace, twelve blocks a stage, down to the bedrock (the bedrock floor left as it is). [quarry2] The shaft is a stair: a
 * one-wide way cut round its ring, a block lower each step, round a pillar of the rock, four clear blocks over every step,
 * its floors laid as stairs (the folk walk it as they walk any stair, up with a load as well as down), its walls and roof
 * made sound as it is cut, a torch on its outer wall every six steps; its mouth on the bottom terrace kept level, its top
 * steps open to the sky with a two-high fence round them but at the stairhead. At the bedrock it keeps on growing, but only away from the town: the pit lengthened six blocks a stage on
 * the side facing away from the heart, terraced as before, never toward the town or its growth line.
 *
 * <p><b>Safe.</b> Water and lava the cutting meets are plugged with stone, never left to run (the mine's way); caves the shaft
 * breaks into walled off; sand and gravel in the shaft's walls made firm. A fence round the rim (moved out with each stage:
 * the new one up before the old comes down), a gate toward the town, lanterns along it, torches on the terraces wherever the
 * dark would let a monster spawn. When the town grows out to it, it closes as before (its terraces left as safe steps).
 *
 * <p><b>Worked.</b> The quarrymen (one, and one more for every twenty folk, three at most; miners first) walk out by day,
 * along the worn track they make, cut the face, carry it to the yard beside the pit, and cart the stores' stone in by the
 * handcart load (bigger the further out the quarry is). While they are there the quarry's ground and the way out stay awake
 * (ChunkLoad). Nobody is ever put anywhere: they walk, and climb out of the pit at the day's end.
 */
public final class Quarry {

    private Quarry() {}

    static final String SITE = "quarry.site", MEN = "quarry.men", CUT = "quarry.cut", OLD_FENCE = "quarry.oldfence";
    public static final int START = 9, WIDEST = 33, WIDER = 6, DEEP_STEP = 12, CORE = 2, LONGER = 6;
    /** The band the quarry is sited in, blocks from the heart. */
    public static final int NEAR = 150, FAR = 250;
    /** Folk a town has before it opens a quarry. */
    static final int FOLK = 10;
    /** A quarryman's load (three times it from the shaft: a barrow up the stair is worth the climb). */
    static final int LOAD = 16;
    /** Ticks between blocks cut (with a stone pick; quicker with better). */
    static final int CUT_EVERY = 24;
    /** Ticks a quarryman tries to get to a spot before it gives that spot up. */
    static final int TRY = 600;
    /** A yard this full lets the quarry rest rather than grow. */
    static final int YARD_FULL = 1500;

    private static volatile boolean standDown;
    private static volatile boolean readyForTests;
    private static volatile int widestForTests = WIDEST;

    public static void standDownForTests(boolean down) {
        standDown = down;
    }

    /** Stood down for the other features' tests (the yard too). */
    static boolean stoodDown() {
        return standDown;
    }

    public static void readyForTests(boolean on) {
        readyForTests = on;
    }

    public static void widestForTests(int w) {
        widestForTests = w;
    }

    public static void resetForTests() {
        MEN_STATE.clear();
        LOOKED.clear();
        CURSOR.clear();
        CORE_Y.clear();
        BOTTOMS.clear();
        FENCES.clear();
        SHAPES.clear();
        UNREACHABLE.clear();
        SEARCH.clear();
        AWAKE.clear();
        QuarryCart.resetForTests();
        readyForTests = false;
        widestForTests = WIDEST;
        StoneYard.resetForTests();
    }

    // ------------------------------------------------------------------ the site

    /**
     * The quarry: its middle (at its rim's level), its terraced pit's size across, its stage, how deep its shaft is to be cut
     * below the rim (0: no shaft yet), how far it has been lengthened away from the town, and which way that is.
     */
    public record Site(BlockPos centre, int size, boolean outcrop, int stage, int deep, int ext, Direction away) {
        public Site(BlockPos centre, int size, boolean outcrop) {
            this(centre, size, outcrop, 1 + Math.max(0, size - START) / WIDER, 0, 0, Direction.NORTH);
        }

        int half() {
            return size / 2;
        }

        /** The pit's bounds {x0, z0, x1, z1}: the square, lengthened on the far side. */
        int[] box() {
            int h = half(), cx = centre.getX(), cz = centre.getZ();
            int x0 = cx - h, x1 = cx + h, z0 = cz - h, z1 = cz + h;
            switch (away) {
                case EAST -> x1 += ext;
                case WEST -> x0 -= ext;
                case SOUTH -> z1 += ext;
                default -> z0 -= ext;
            }
            return new int[]{ x0, z0, x1, z1 };
        }

        int widthX() { int[] b = box(); return b[2] - b[0] + 1; }

        int widthZ() { int[] b = box(); return b[3] - b[1] + 1; }

        /** In the shaft's five-by-five? */
        boolean core(int x, int z) {
            return Math.abs(x - centre.getX()) <= CORE && Math.abs(z - centre.getZ()) <= CORE;
        }

        Site with(int size, int stage, int deep, int ext) {
            return new Site(centre, size, outcrop, stage, deep, ext, away);
        }

        Site facing(Direction away) {
            return new Site(centre, size, outcrop, stage, deep, ext, away);
        }

        String words() {
            return "stage " + stage + ": " + widthX() + "×" + widthZ();
        }
    }

    @Nullable
    public static Site site(UUID town) {
        return parse(Ledger.note(town, SITE));
    }

    @Nullable
    static Site parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            BlockPos c = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            int size = Integer.parseInt(p[3]);
            Site base = new Site(c, size, "1".equals(p[4]));
            if (p.length < 9) return base;
            return new Site(c, size, base.outcrop(), Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]),
                Direction.from2DDataValue(Integer.parseInt(p[8])));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String text(Site s) {
        return s.centre().getX() + "," + s.centre().getY() + "," + s.centre().getZ() + "," + s.size() + "," + (s.outcrop() ? 1 : 0)
            + "," + s.stage() + "," + s.deep() + "," + s.ext() + "," + s.away().get2DDataValue();
    }

    static void save(UUID town, Site s) {
        Ledger.note(town, SITE, text(s));
    }

    /**
     * The town's growth line, blocks from the heart: the plan's reach a ring past the town as it stands (as econ-51's
     * LandUse.growthReach reckons it, so the quarry keeps off the land the town will want).
     *
     * <p>TODO(featbuild, at the merge with int): econ-51's LandUse is on int but not on this branch; this is a copy of its
     * formula. Replace the body with {@code return LandUse.growthReach(town);} when feat-build is merged.
     */
    public static int growthReach(UUID town) {
        int b = 0;
        for (String s : Villages.builtList(town)) if (!"colony".equals(s)) b++;
        int rings = b <= 14 ? 1 : b <= 44 ? 2 : CityGrowth.rings(town);
        int line = TownPlan.reach(Math.min(TownPlan.MAX_RINGS, rings + 1)) + 2;
        com.jrpetty.mcassistant.village.WallPlan.Sides wall = CastleWall.begun(town) ? CastleWall.sides(town) : null;
        if (wall != null) line = Math.max(line, Math.max(Math.max(wall.n(), wall.e()), Math.max(wall.s(), wall.w())) + com.jrpetty.mcassistant.village.WallPlan.BAND + 4);
        return line;
    }

    /** Is a square this big round this spot clear of what the town needs? */
    static boolean clearOfTown(UUID town, BlockPos heart, int cx, int cz, int half, @Nullable StoneYard.Site yard) {
        return whyNot(town, heart, cx - half, cz - half, cx + half, cz + half, yard) == null;
    }

    /** Why a pit with these bounds would be in the town's way (its growth room, the roads out, its farmland, its mine's
     *  ground, its yard), or null if it is clear. */
    @Nullable
    static String whyNot(UUID town, BlockPos heart, int x0, int z0, int x1, int z1, @Nullable StoneYard.Site yard) {
        int hx = heart.getX(), hz = heart.getZ();
        // The nearest the pit's fence comes to the heart, along either axis (the growth line is a square round it).
        int nearX = x0 > hx ? x0 - hx : x1 < hx ? hx - x1 : 0, nearZ = z0 > hz ? z0 - hz : z1 < hz ? hz - z1 : 0;
        if (Math.max(nearX, nearZ) - 1 <= growthReach(town)) return "inside the growth line";
        // The radial roads (the avenues run out north, east, south and west from the heart).
        if (nearX - 1 <= TownPlan.AVENUE + 6 || nearZ - 1 <= TownPlan.AVENUE + 6) return "on a road out";
        int mx = (x0 + x1) / 2, mz = (z0 + z1) / 2;
        if (Villages.onFarmland(town, mx - hx, mz - hz, (x1 - x0) / 2 + 1, (z1 - z0) / 2 + 1)) return "on the farmland";
        if (MineGround.meets(town, x0 - 2, z0 - 2, x1 + 2, z1 + 2)) return "on the mine's ground";
        if (yard != null) {
            int[] b = StoneYard.box(yard);
            if (x1 + 2 >= b[0] && x0 - 2 <= b[2] && z1 + 2 >= b[1] && z0 - 2 <= b[3]) return "on the yard";
        }
        return null;
    }

    /** The surface (top solid block's y) at a column. */
    static int surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        while (y > level.getMinBuildHeight() + 1) {
            BlockState st = level.getBlockState(p.setY(y));
            if (CastleWall.natural(st) && st.getFluidState().isEmpty()) return y;
            if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;          // water: no quarry here
            y--;
        }
        return Integer.MIN_VALUE;
    }

    static boolean rock(BlockState st) {
        return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.CALCITE);
    }

    /** The lowest course the shaft is cut to: five over the world's floor, the bedrock's own layers left as they are. */
    /** Each site's lowest course, worked out once from its own ground. */
    private static final Map<Long, Integer> BOTTOMS = new ConcurrentHashMap<>();

    /**
     * The lowest course the shaft is cut to: the course over the highest bedrock under the shaft (the bedrock's own layers,
     * and the stone between their bumps, left as they are: a floor with no holes in it). No site: just over the world's floor.
     */
    static int bottom(ServerLevel level, @Nullable Site s) {
        int min = level.getMinBuildHeight();
        if (s == null) return min + 1;
        return BOTTOMS.computeIfAbsent(s.centre().asLong(), k -> {
            int top = min;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dx = -CORE; dx <= CORE; dx++) for (int dz = -CORE; dz <= CORE; dz++) {
                for (int y = min; y <= min + 8; y++) {
                    if (level.getBlockState(p.set(s.centre().getX() + dx, y, s.centre().getZ() + dz)).is(Blocks.BEDROCK)) top = Math.max(top, y);
                }
            }
            return top + 1;
        });
    }

    /** The margin of dry ground kept round the quarry's widest footprint: no water within it at or over the pit's top. */
    public static final int DRY = 12;
    /** How far past its widest the quarry may be lengthened that is looked over for water when it is sited. */
    static final int LONG_LOOK = 4 * LONGER;

    /**
     * Is there surface water (a pond, river, lake or sea), or water at or over the pit's top, within the margin of this
     * footprint {x0, z0, x1, z1}? Water deep underground is not counted (the cutting plugs what it meets).
     */
    static boolean wetNear(ServerLevel level, int x0, int z0, int x1, int z1, int rim) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0 - DRY; x <= x1 + DRY; x++) {
            for (int z = z0 - DRY; z <= z1 + DRY; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (top >= rim - 3 && !level.getFluidState(p.set(x, top, z)).isEmpty()) return true;
                for (int y = rim - 3; y <= Math.min(top, rim + 1); y++) if (!level.getFluidState(p.set(x, y, z)).isEmpty()) return true;
            }
        }
        return false;
    }

    /** The widest a site will ever open, and as far as it is looked over for lengthening: {x0, z0, x1, z1}. */
    static int[] finalFootprint(BlockPos c, Direction away) {
        Site s = new Site(c, WIDEST, true, 5, 0, LONG_LOOK, away);
        return s.box();
    }

    static boolean atBedrock(ServerLevel level, Site s) {
        return s.deep() > 0 && s.centre().getY() - s.deep() + 1 <= bottom(level, s);
    }

    /** Which way is away from the town, from this spot. */
    static Direction awayFrom(BlockPos heart, BlockPos at) {
        int dx = at.getX() - heart.getX(), dz = at.getZ() - heart.getZ();
        return Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    // ------------------------------------------------------------------ the look for a site

    /** A town's look for a site under way: the spots left to look at, nearest first, and the best found. */
    private static final class Search {
        final List<int[]> todo = new ArrayList<>();          // {x, z, ring, tries}
        int at, ring = -1, further;
        @Nullable Site best, earth;
        double bestScore = -1e9, earthScore = -1e9;
        final Map<String, Integer> why = new TreeMap<>();
        final Set<Long> asked = new HashSet<>();
        long started;
    }

    private static final Map<UUID, Search> SEARCH = new ConcurrentHashMap<>();
    /** When a town last gave up looking (none anywhere): a day before it looks again. */
    private static final Map<UUID, Long> GAVE_UP = new ConcurrentHashMap<>();

    static UUID owner(UUID town, String what) {
        return UUID.nameUUIDFromBytes(("mca-quarry-" + what + ":" + town).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Search newSearch(Villages.Village v, int from, int to, int step) {
        Search s = new Search();
        BlockPos heart = v.centre();
        int n = 0;
        for (int d = from; d <= to; d += step, n++) {
            for (int k = 0; k < 16; k++) {
                double a = Math.PI * 2 * k / 16.0 + Math.PI / 16.0 + (n % 2) * Math.PI / 16.0;
                s.todo.add(new int[]{ heart.getX() + (int) Math.round(Math.cos(a) * d), heart.getZ() + (int) Math.round(Math.sin(a) * d), d, 0 });
            }
        }
        return s;
    }

    /** The site, chosen now (the ground generated as it is looked at): the operators' staging and the tests. */
    @Nullable
    static Site choose(ServerLevel level, Villages.Village v) {
        SEARCH.remove(v.id());
        Site got = null;
        for (int i = 0; i < 400 && got == null; i++) {
            got = look(level, v, 64, true);
            if (got == null && !SEARCH.containsKey(v.id())) break;
        }
        return got;
    }

    /**
     * A step of the look: so many spots looked at, each once its ground is loaded (asked for, queued, when it is not; or, now,
     * generated on the spot). Returns the site once the look is done and found one; null while it goes on, or when it found
     * none (then the look is over and logged).
     */
    @Nullable
    static Site look(ServerLevel level, Villages.Village v, int budget, boolean now) {
        UUID id = v.id();
        Search s = SEARCH.computeIfAbsent(id, k -> {
            Search n = newSearch(v, NEAR, FAR, 20);
            n.started = level.getGameTime();
            return n;
        });
        UUID who = owner(id, "look");
        for (int b = 0; b < budget && s.at < s.todo.size(); b++) {
            int[] c = s.todo.get(s.at);
            // A ring looked over with a good site on it: the nearest good site is that ring's best.
            if (c[2] != s.ring) {
                if (s.best != null) break;
                s.ring = c[2];
            }
            int[] fin = finalFootprint(new BlockPos(c[0], 0, c[1]), awayFrom(v.centre(), new BlockPos(c[0], 0, c[1])));
            boolean ready = true;
            for (int x = (fin[0] - DRY) >> 4; x <= (fin[2] + DRY) >> 4; x++) {
                for (int z = (fin[1] - DRY) >> 4; z <= (fin[3] + DRY) >> 4; z++) {
                    if (level.hasChunk(x, z)) continue;
                    if (now || c[3] >= 3) { level.getChunk(x, z); continue; }  // (asked three times and never came: fetched now)
                    ready = false;
                    long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                    if (s.asked.add(key)) ChunkLoad.forceOne(level, who, x, z, true);
                }
            }
            if (!ready) {
                c[3]++;
                s.todo.add(c);                                              // back of the queue till its ground comes
                s.todo.set(s.at, null);
                s.at++;
                continue;
            }
            s.at++;
            judge(level, v, s, c[0], c[1], c[2]);
        }
        // Skip the spent entries (moved to the back).
        while (s.at < s.todo.size() && s.todo.get(s.at) == null) s.at++;
        boolean ringDone = s.at >= s.todo.size() || s.todo.get(s.at)[2] != s.ring;
        boolean done = s.at >= s.todo.size() || ringDone && s.best != null;
        if (!done) return null;
        Site got = s.best != null ? s.best : s.earth;
        long day = level.getDayTime() / 24000L;
        if (got == null && s.further == 0) {
            // None in the band: a little further, and why.
            Search wider = newSearch(v, FAR + 15, FAR + 60, 15);
            wider.further = 1;
            wider.started = s.started;
            wider.asked.addAll(s.asked);
            SEARCH.put(id, wider);
            Villages.tell(id, day, "no quarry site 150 to 250 blocks out (" + reasons(s) + "); the council looks a little further");
            return null;
        }
        for (long k : s.asked) ChunkLoad.forceOne(level, who, net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k), false);
        SEARCH.remove(id);
        if (got == null) {
            GAVE_UP.put(id, level.getGameTime());
            Villages.tell(id, day, "no quarry site anywhere near (" + reasons(s) + "); the council will look again");
        }
        return got;
    }

    private static String reasons(Search s) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : s.why.entrySet()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.getValue()).append(' ').append(e.getKey());
        }
        return b.length() == 0 ? "nothing looked at" : b.toString();
    }

    private static void judge(ServerLevel level, Villages.Village v, Search s, int cx, int cz, int ring) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int half = START / 2;
        String no = whyNot(id, heart, cx - half, cz - half, cx + half, cz + half, null);
        if (no != null) { s.why.merge(no, 1, Integer::sum); return; }
        int rocky = 0, deep = 0, cells = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        List<Integer> heights = new ArrayList<>();
        boolean wet = false, built = false;
        for (int x = cx - half - 1; x <= cx + half + 1 && !wet && !built; x++) {
            for (int z = cz - half - 1; z <= cz + half + 1; z++) {
                int y = surface(level, x, z);
                if (y == Integer.MIN_VALUE) { wet = true; break; }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int up = y + 1; up < top; up++) {
                    BlockPos p = new BlockPos(x, up, z);
                    BlockState st = level.getBlockState(p);
                    if (!st.isAir() && !CastleWall.wild(level, p, st) && !CastleWall.natural(st)) { built = true; break; }
                }
                cells++;
                heights.add(y);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                if (rock(level.getBlockState(new BlockPos(x, y, z)))) rocky++;
                // And the rock under it (a dig down that only ever meets sand gives no stone).
                if (((x + z) & 1) == 0) for (int dd = 1; dd <= 8; dd++) if (rock(level.getBlockState(new BlockPos(x, y - dd, z)))) deep++;
            }
        }
        if (wet) { s.why.merge("by water", 1, Integer::sum); return; }
        if (built) { s.why.merge("built on", 1, Integer::sum); return; }
        if (cells == 0 || hi - lo > 6) { s.why.merge("too steep", 1, Integer::sum); return; }
        heights.sort(Integer::compare);
        int rim = heights.get(heights.size() / 2);
        Direction away0 = awayFrom(heart, new BlockPos(cx, rim, cz));
        int[] fin = finalFootprint(new BlockPos(cx, rim, cz), away0);
        if (waterNear(level, cx, rim, cz, half + 3, 3) || wetNear(level, fin[0], fin[1], fin[2], fin[3], rim)) {
            s.why.merge("too near water", 1, Integer::sum);
            return;
        }
        // The way out to it: no river across it (the quarrymen walk it every day with their loads).
        int wetSteps = 0;
        double len = Math.sqrt((double) (cx - heart.getX()) * (cx - heart.getX()) + (double) (cz - heart.getZ()) * (cz - heart.getZ()));
        for (double t = growthReach(id); t < len - half; t += 4) {
            int x = heart.getX() + (int) Math.round((cx - heart.getX()) * t / len), z = heart.getZ() + (int) Math.round((cz - heart.getZ()) * t / len);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            if (surface(level, x, z) == Integer.MIN_VALUE) wetSteps++;
        }
        if (wetSteps > 3) { s.why.merge("across water", 1, Integer::sum); return; }
        double score = rocky * 3.0 + deep * 0.5 - (hi - lo) * 2.0 - (ring - NEAR) * 0.05;
        Direction away = awayFrom(heart, new BlockPos(cx, rim, cz));
        if (deep * 2 < cells) {
            if (score > s.earthScore) { s.earthScore = score; s.earth = new Site(new BlockPos(cx, rim, cz), START, false).facing(away); }
            return;
        }
        if (score > s.bestScore) {
            s.bestScore = score;
            s.best = new Site(new BlockPos(cx, rim, cz), START, rocky * 4 >= cells).facing(away);
        }
    }

    static boolean waterNear(ServerLevel level, int cx, int y0, int cz, int half, int deep) {
        return waterIn(level, cx - half, cz - half, cx + half, cz + half, y0 - deep, y0 + 1);
    }

    static boolean waterIn(ServerLevel level, int x0, int z0, int x1, int z1, int y0, int y1) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (!level.getFluidState(p.set(x, y, z)).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /** The yard's place: on the town side of the pit, past the fence of the widest it will open, the gate facing the town. */
    static StoneYard.Site yardFor(ServerLevel level, Villages.Village v, Site q) {
        Direction toTown = q.away().getOpposite();
        int off = WIDEST / 2 + 3 + StoneYard.HALF_D;
        BlockPos c = q.centre().relative(toTown, off);
        int y = surface(level, c.getX(), c.getZ());
        if (y == Integer.MIN_VALUE) y = q.centre().getY();
        return new StoneYard.Site(new BlockPos(c.getX(), y + 1, c.getZ()), toTown);
    }

    // ------------------------------------------------------------------ the pit's shape

    /** The pit's ground plan: each cell's depth below the rim (0 outside), its outline ragged as a worked quarry's is. */
    record Shape(int x0, int z0, int w, int d, int[] depth, int max) {
        int at(int x, int z) {
            int i = x - x0, j = z - z0;
            if (i < 0 || j < 0 || i >= w || j >= d) return 0;
            return depth[i * d + j];
        }
    }

    private static final Map<String, Shape> SHAPES = new ConcurrentHashMap<>();

    static int noise(int x, int z, BlockPos c) {
        int h = x * 73856093 ^ z * 19349663 ^ (int) c.asLong() * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    static Shape shape(Site s) {
        String key = s.centre().asLong() + "/" + s.size() + "/" + s.ext() + "/" + s.away();
        Shape kept = SHAPES.get(key);
        if (kept != null) return kept;
        int[] b = s.box();
        int w = b[2] - b[0] + 1, d = b[3] - b[1] + 1;
        boolean[] in = new boolean[w * d];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < d; j++) {
                int ex = Math.min(i, w - 1 - i), ez = Math.min(j, d - 1 - j);
                int n = noise(b[0] + i, b[1] + j, s.centre());
                boolean m = ex + ez >= 2;                                       // the corners worn round
                if (m && Math.min(ex, ez) == 0 && n % 4 == 0) m = false;          // the edge broken here and there
                if (m && ex + ez <= 3 && n % 3 == 0) m = false;                   // and raggeder at the corners
                in[i * d + j] = m;
            }
        }
        // Depth: how many steps in from the nearest ground left standing (a chessboard distance: no step over a block).
        int big = 1 << 20;
        int[] dist = new int[w * d];
        for (int k = 0; k < dist.length; k++) dist[k] = in[k] ? big : 0;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < w; i++) for (int j = 0; j < d; j++) changed |= relax(dist, w, d, i, j);
            for (int i = w - 1; i >= 0; i--) for (int j = d - 1; j >= 0; j--) changed |= relax(dist, w, d, i, j);
        }
        int max = 0;
        for (int v : dist) max = Math.max(max, v);
        Shape out = new Shape(b[0], b[1], w, d, dist, max);
        if (SHAPES.size() > 64) SHAPES.clear();
        SHAPES.put(key, out);
        return out;
    }

    private static boolean relax(int[] dist, int w, int d, int i, int j) {
        int k = i * d + j;
        if (dist[k] == 0) return false;
        int v = dist[k];
        for (int a = -1; a <= 1; a++) {
            for (int c = -1; c <= 1; c++) {
                int ii = i + a, jj = j + c;
                if (ii < 0 || jj < 0 || ii >= w || jj >= d) { v = 1; continue; }
                v = Math.min(v, dist[ii * d + jj] + 1);
            }
        }
        if (v == dist[k]) return false;
        dist[k] = v;
        return true;
    }

    /** How deep this cell is to be cut, below the rim (the shaft's cells to the shaft's depth). */
    static int depthAt(Site s, int x, int z) {
        int d = shape(s).at(x, z);
        if (s.deep() <= 0 || d <= 0) return d;
        // With a shaft: the terraces round its mouth kept level with the stairhead, and no steeper than a block a step out.
        int cheb = Math.max(Math.abs(x - s.centre().getX()), Math.abs(z - s.centre().getZ()));
        return Math.min(d, headDepth(s) + Math.max(0, cheb - CORE - 1));
    }

    /** The deepest the terraces themselves go. */
    static int terraceDepth(Site s) {
        return shape(s).max;
    }

    // ------------------------------------------------------------------ the cutting

    /** Where each town's look for the next cut got to, in the pit's order (course by course, row by row). */
    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    /** The shaft's course a town's cutting has got down to. */
    private static final Map<UUID, Integer> CORE_Y = new ConcurrentHashMap<>();

    /**
     * The next block to cut, top down: the outcrop over the rim, then each terrace's course, then the shaft's courses; null
     * when the stage is cut. The look goes on from where it last found one, and round once more from the top before the pit
     * is called cut (a block held back for a terrace beside it comes free when that is cut).
     */
    @Nullable
    static BlockPos nextCut(ServerLevel level, UUID town, Site s) {
        Shape sh = shape(s);
        int rim = s.centre().getY();
        int low = Math.max(bottom(level, s), rim - sh.max + 1);
        int top = rim + 6, layers = top - low + 1, cells = sh.w * sh.d, all = layers * cells;
        int from = Math.floorMod(CURSOR.getOrDefault(town, 0), Math.max(1, all));
        Map<Long, Long> skipped = UNREACHABLE.getOrDefault(town, Map.of());
        for (int k = 0; k < all; k++) {
            int i = (from + k) % all;
            int y = top - i / cells, rem = i % cells;
            int depth = s.deep() > 0 ? depthAt(s, sh.x0 + rem / sh.d, sh.z0 + rem % sh.d) : sh.depth[rem];
            if (depth <= 0 || y < rim - depth + 1 || y < bottom(level, s)) continue;
            BlockPos p = new BlockPos(sh.x0 + rem / sh.d, y, sh.z0 + rem % sh.d);
            if (skipped.containsKey(p.asLong())) continue;
            if (cuttable(level, p, y == rim - depth + 1) && terraced(level, p, rim, low)) {
                CURSOR.put(town, i);
                return p;
            }
        }
        CURSOR.put(town, 0);
        if (s.deep() <= 0) return null;
        // The shaft's stair: a step at a time down the ring, each step's room cut top down, down to its stage's depth.
        int last = lastStep(level, s);
        int[][] r = ring(s);
        int f = head(s);
        int fromN = Math.max(0, Math.min(last, CORE_Y.getOrDefault(town, 0)));
        for (int pass = 0; pass < 2; pass++) {
            for (int n = pass == 0 ? fromN : 0; n <= last; n++) {
                BlockPos feet = stepFeet(s, r, n);
                for (int y = Math.min(f, feet.getY() + HEAD - 1); y >= feet.getY(); y--) {
                    BlockPos p = new BlockPos(feet.getX(), y, feet.getZ());
                    if (skipped.containsKey(p.asLong())) continue;
                    if (!cuttable(level, p, false)) continue;
                    CORE_Y.put(town, n);
                    return p;
                }
            }
        }
        CORE_Y.put(town, Math.max(0, last));
        return null;
    }

    /** What the quarry cuts: the world's own ground (not its bedrock), and the stone it put in itself to plug or shore. */
    static boolean quarryStone(ServerLevel level, BlockPos p, BlockState st) {
        if (st.is(Blocks.BEDROCK)) return false;
        return CastleWall.natural(st) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || CastleWall.wild(level, p, st) && !st.isAir();
    }

    /** May this block be cut: the quarry's sort of block, nobody's work, not the bedrock? (Water and lava beside it are
     *  plugged as it is cut: cut().) */
    static boolean cuttable(ServerLevel level, BlockPos p, boolean floor) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.hasBlockEntity() || p.getY() <= level.getMinBuildHeight()) return false;
        if (!st.getFluidState().isEmpty()) {
            // Water or lava standing in the cut itself: stopped with stone, cut next time round.
            level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 3);
            return false;
        }
        return quarryStone(level, p, st);
    }

    /**
     * Would cutting this block leave every step into its cell a block at most: the block over it already open (cut top
     * down), and no column beside it standing more than a block over the floor it leaves?
     */
    static boolean terraced(ServerLevel level, BlockPos p, int rim, int low) {
        BlockState over = level.getBlockState(p.above());
        if (!over.isAir() && !over.is(Blocks.TORCH) && !CastleWall.wild(level, p.above(), over)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (top(level, n.getX(), n.getZ(), rim + 8, low - 2) > p.getY()) return false;
        }
        return true;
    }

    /** The highest block of ground (not air, not a plant, not the quarry's own fittings) in a column, between these heights. */
    static int top(ServerLevel level, int x, int z, int from, int to) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, from, z);
        for (int y = from; y >= to; y--) {
            BlockState st = level.getBlockState(m.setY(y));
            if (st.isAir() || CastleWall.wild(level, m, st) && !st.is(BlockTags.LOGS)) continue;
            if (st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH)
                || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LADDER)) continue;
            return y;
        }
        return to;
    }

    /** Cut one block: what it gives (the stone by what a pick gets of it; ore, earth and the rest). Water and lava round it
     *  plugged first; in the shaft, its walls and floor made sound (a cave walled off, sand and gravel made firm). */
    static List<ItemStack> cut(ServerLevel level, BlockPos p, @Nullable VillageFolkEntity by, @Nullable Site s) {
        boolean core = s != null && stairStep(level, s, p) >= 0;
        seal(level, p, core, s);
        BlockState st = level.getBlockState(p);
        ItemStack pick = by != null && by.getMainHandItem().getItem() instanceof PickaxeItem ? by.getMainHandItem() : new ItemStack(Items.STONE_PICKAXE);
        List<ItemStack> drops = Block.getDrops(st, level, p, null, by, pick);
        level.levelEvent(2001, p, Block.getId(st));                       // the dust and the sound of it
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        // Snow, a plant or a torch over the course goes with it.
        BlockState over = level.getBlockState(p.above());
        if (over.is(Blocks.TORCH) || !over.isAir() && CastleWall.wild(level, p.above(), over) && !over.is(BlockTags.LOGS)) {
            level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        if (s != null && core) stair(level, s);
        return drops;
    }

    /** Water and lava next to a cut stopped with stone before it is opened; in the shaft, its walls and floor made sound. */
    static void seal(ServerLevel level, BlockPos p, boolean core, @Nullable Site s) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            BlockState st = level.getBlockState(n);
            if (st.is(Blocks.BEDROCK)) continue;
            if (!st.getFluidState().isEmpty() && !st.is(Blocks.LADDER)) {
                level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), 3);
                continue;
            }
            if (core && s != null) {
                // The stair's room: whatever round it is not the stair (under the stairhead's floor) made sound, its roof too.
                if (n.getY() > head(s) || stairStep(level, s, n) >= 0) continue;
                if (st.is(Blocks.WALL_TORCH) || st.is(Blocks.TORCH) || st.getBlock() instanceof StairBlock) continue;
                if (st.isAir() || st.canBeReplaced() || ShaftStair.falls(st)) {
                    level.setBlock(n, st.is(BlockTags.SAND) ? Blocks.SANDSTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
                continue;
            }
            if (d == Direction.UP) continue;
            boolean wall = d == Direction.DOWN && n.getY() >= bottom(level, s);
            if (!wall) continue;
            if (d == Direction.DOWN && n.getY() < bottom(level, s) - 1) continue;
            if (st.is(Blocks.LADDER) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.TORCH)) continue;
            boolean open = st.isAir() || st.canBeReplaced();
            boolean loose = st.getBlock() instanceof Fallable;
            if (d == Direction.DOWN && !open) continue;                       // the floor: only a hole under it stopped
            if (open || loose) {
                level.setBlock(n, st.is(BlockTags.SAND) ? Blocks.SANDSTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }

    // ------------------------------------------------------------------ the shaft's stair

    /** Clear blocks over the feet of every step of the shaft's stair (the mine's stair has as many: ShaftStair.HEAD). */
    public static final int HEAD = 4;
    /** A torch on the stair's outer wall every so many steps down (a block down a step). */
    public static final int TORCH_EVERY = 6;
    /** The way-finder asked for so many steps of the stair at a time, at most. */
    static final int STAIR_LEG = 12;
    /** The shaft's ring, in order round from the middle of its town side: the stair's cells, a block lower each. */
    private static final int[][] RING = { { 2, -2 }, { 2, -1 }, { 2, 0 }, { 2, 1 }, { 2, 2 }, { 1, 2 }, { 0, 2 }, { -1, 2 },
        { -2, 2 }, { -2, 1 }, { -2, 0 }, { -2, -1 }, { -2, -2 }, { -1, -2 }, { 0, -2 }, { 1, -2 } };

    /** The ring's cells from the stair's head (the middle of the town side) on round, as offsets from the middle. */
    static int[][] ring(Site s) {
        Direction t = s.away().getOpposite();
        int ex = t.getStepX() * CORE, ez = t.getStepZ() * CORE, k0 = 0;
        for (int k = 0; k < RING.length; k++) if (RING[k][0] == ex && RING[k][1] == ez) k0 = k;
        int[][] out = new int[RING.length][];
        for (int i = 0; i < RING.length; i++) out[i] = RING[(k0 + i) % RING.length];
        return out;
    }

    /** The stairhead: the spot on the bottom terrace, outside the shaft on its town side, the stair goes down from. */
    static BlockPos gap(Site s) {
        return s.centre().relative(s.away().getOpposite(), CORE + 1);
    }

    /** How deep the stairhead's floor is below the rim (the terraces' depth there: steady as the pit is lengthened, which
     *  is only ever away from the town). */
    static int headDepth(Site s) {
        BlockPos g = gap(s);
        return Math.max(1, shape(s).at(g.getX(), g.getZ()));
    }

    /** The stairhead's floor: the course the shaft's mouth is kept level at. */
    static int head(Site s) {
        return s.centre().getY() - headDepth(s);
    }

    /** The stair's last step (its lowest, at the shaft's stage depth or the bedrock), or -1 with no shaft. */
    static int lastStep(ServerLevel level, Site s) {
        if (s.deep() <= 0) return -1;
        int floorY = Math.max(bottom(level, s), s.centre().getY() - s.deep() + 1);
        return Math.max(-1, head(s) - floorY);
    }

    /** Where a body stands on step n: on the ring, a block lower each step from the head. */
    static BlockPos stepFeet(Site s, int[][] ring, int n) {
        int[] o = ring[Math.floorMod(n, ring.length)];
        return new BlockPos(s.centre().getX() + o[0], head(s) - n, s.centre().getZ() + o[1]);
    }

    /** The ring's index of this column, or -1 if it is not on the ring. */
    static int ringIndex(Site s, int[][] ring, int x, int z) {
        int dx = x - s.centre().getX(), dz = z - s.centre().getZ();
        if (Math.max(Math.abs(dx), Math.abs(dz)) != CORE) return -1;
        for (int i = 0; i < ring.length; i++) if (ring[i][0] == dx && ring[i][1] == dz) return i;
        return -1;
    }

    /** The step whose room this block is (its feet up to the HEAD over them, never over the stairhead's floor), or -1. */
    static int stairStep(ServerLevel level, Site s, BlockPos p) {
        if (s.deep() <= 0) return -1;
        int[][] r = ring(s);
        int j = ringIndex(s, r, p.getX(), p.getZ());
        if (j < 0) return -1;
        int f = head(s), last = lastStep(level, s);
        if (p.getY() > f) return -1;
        for (int n = f - p.getY(); n <= f - p.getY() + HEAD - 1; n++) {
            if (n >= 0 && n <= last && Math.floorMod(n, r.length) == j) return n;
        }
        return -1;
    }

    /** The step a body standing here is on (its feet anywhere over the step's floor, a stair's half included), or -1. */
    static int stepOf(ServerLevel level, Site s, double x, double y, double z) {
        if (s.deep() <= 0) return -1;
        int[][] r = ring(s);
        int j = ringIndex(s, r, Mth.floor(x), Mth.floor(z));
        if (j < 0) return -1;
        double approx = head(s) - y;
        int n = j + r.length * (int) Math.round((approx - j) / r.length);
        if (n < 0 || n > lastStep(level, s) || Math.abs(n - approx) > 1.6) return -1;
        return n;
    }

    /** The way up the stair from step n: toward the step before it (the stairhead from the first). */
    static Direction upFrom(Site s, int[][] ring, int n) {
        int[] here = ring[Math.floorMod(n, ring.length)];
        int[] prev = n == 0 ? new int[]{ s.away().getOpposite().getStepX() * (CORE + 1), s.away().getOpposite().getStepZ() * (CORE + 1) }
            : ring[Math.floorMod(n - 1, ring.length)];
        return Direction.getNearest(prev[0] - here[0], 0, prev[1] - here[1]);
    }

    /** The ring cell's outer wall: the side away from the middle (for a corner, across the way the stair runs on it). */
    static Direction outward(int[] o) {
        return Math.abs(o[0]) == CORE ? (o[0] > 0 ? Direction.EAST : Direction.WEST) : (o[1] > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** Where a quarryman stands to cut a block of the stair: on the step above it (the stairhead for the first). */
    @Nullable
    static BlockPos stairStand(ServerLevel level, Site s, BlockPos p) {
        int n = stairStep(level, s, p);
        if (n < 0) return null;
        if (n == 0) { BlockPos g = gap(s); return new BlockPos(g.getX(), head(s) + 1, g.getZ()); }
        return stepFeet(s, ring(s), n - 1);
    }

    /**
     * The shaft's stair kept: the shaft's mouth level at the stairhead's floor (the bottom terrace's point under it filled
     * back where it was cut deeper, never under anybody), and every step cut so far given its floor, a stair of cobblestone
     * facing up the way (a half block's rise at a time going up). Called as the stair is cut, and on the quarry's round.
     */
    static void stair(ServerLevel level, Site s) {
        if (s.deep() <= 0) return;
        int[][] r = ring(s);
        int f = head(s), last = lastStep(level, s);
        BlockPos c = s.centre();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -CORE - 1; dx <= CORE + 1; dx++) {
            for (int dz = -CORE - 1; dz <= CORE + 1; dz++) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                int want = Math.max(Math.abs(dx), Math.abs(dz)) <= CORE ? f : f - 1;
                int t = top(level, x, z, f + 1, f - 8);
                if (t >= want) continue;
                if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new net.minecraft.world.phys.AABB(x, t + 1, z, x + 1, want + 3, z + 1)).isEmpty()) continue;   // never under a body
                for (int y = t + 1; y <= want; y++) {
                    m.set(x, y, z);
                    if (stairStep(level, s, m) >= 0) continue;
                    BlockState st = level.getBlockState(m);
                    if (st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty() || st.is(BlockTags.FENCES) || st.is(Blocks.LANTERN)
                        || st.is(Blocks.TORCH)) level.setBlock(m, Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
        }
        for (int n = 0; n <= last; n++) {
            BlockPos feet = stepFeet(s, r, n);
            if (!level.getBlockState(feet).isAir()) continue;             // not cut yet: its turn comes
            BlockPos floor = feet.below();
            BlockState st = level.getBlockState(floor);
            if (st.is(Blocks.BEDROCK) || st.getBlock() instanceof StairBlock) continue;
            level.setBlock(floor, Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, upFrom(s, r, n))
                .setValue(StairBlock.HALF, Half.BOTTOM), 3);
        }
    }

    /** Is the shaft's mouth level at the stairhead's floor (the bottom terrace's point under it filled back)? */
    static boolean headLevel(ServerLevel level, Site s) {
        int f = head(s);
        BlockPos c = s.centre();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -CORE; dx <= CORE; dx++) {
            for (int dz = -CORE; dz <= CORE; dz++) {
                if (stairStep(level, s, m.set(c.getX() + dx, f, c.getZ() + dz)) >= 0) continue;
                if (top(level, c.getX() + dx, c.getZ() + dz, f + 1, f - 8) < f) return false;
            }
        }
        return true;
    }

    /** The stair's torches: one on its outer wall every TORCH_EVERY steps down, and at the step it is cut to. Returns how
     *  many were put up (at most {@code budget}). */
    static int stairLights(ServerLevel level, Villages.Village v, Site s, int budget, boolean free) {
        if (s.deep() <= 0) return 0;
        int[][] r = ring(s);
        int last = lastStep(level, s), done = 0;
        int deepest = -1;
        for (int n = last; n >= 0; n--) if (level.getBlockState(stepFeet(s, r, n)).isAir()) { deepest = n; break; }
        for (int n = 0; n <= deepest && done < budget; n++) {
            if (n % TORCH_EVERY != HEAD - 1 && n != deepest) continue;
            BlockPos at = stepFeet(s, r, n).above();
            Direction out = outward(r[n % r.length]);
            BlockPos w = at.relative(out);
            if (!level.getBlockState(at).isAir() || !level.getBlockState(w).isFaceSturdy(level, w, out.getOpposite())) continue;
            if (v != null && !free) TownWork.take(level, v, it -> it.is(Items.TORCH), 1);
            level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, out.getOpposite()), 3);
            done++;
        }
        return done;
    }

    /**
     * The way between the stair and the terraces, and along the stair, is asked of the way-finder a dozen steps at a time:
     * into the shaft by its stairhead, out of it up to the stairhead, along it a leg at a time. True if it walked the folk
     * this tick (the walk to {@code to} itself waits).
     */
    private static boolean stairWay(ServerLevel level, Site s, VillageFolkEntity f, BlockPos to) {
        if (s.deep() <= 0) return false;
        int[][] r = ring(s);
        int at = stepOf(level, s, f.getX(), f.getY(), f.getZ());
        int want = stepOf(level, s, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (at < 0 && want < 0) return false;
        BlockPos g = gap(s);
        BlockPos head = new BlockPos(g.getX(), head(s) + 1, g.getZ());
        BlockPos next;
        if (at < 0) {
            if (want <= STAIR_LEG) return false;                          // the first steps: the walk itself
            next = f.distanceToSqr(head.getX() + 0.5, head.getY(), head.getZ() + 0.5) > 2.25 ? head : stepFeet(s, r, STAIR_LEG);
        } else if (want < 0) {
            next = at <= STAIR_LEG ? head : stepFeet(s, r, at - STAIR_LEG);
            if (at <= 1 && f.distanceToSqr(head.getX() + 0.5, head.getY(), head.getZ() + 0.5) <= 4.0) return false;
        } else {
            if (Math.abs(want - at) <= STAIR_LEG) return false;
            next = stepFeet(s, r, want > at ? at + STAIR_LEG : at - STAIR_LEG);
        }
        Incidents.walk(f, next, 0.8D);
        return true;
    }

    /** Is it earth (spoil, left on the heap), not stone? */
    static boolean spoil(ItemStack s) {
        Item it = s.getItem();
        return it == Items.DIRT || it == Items.GRAVEL || it == Items.SAND || it == Items.RED_SAND
            || it == Items.SNOWBALL || it == Items.FLINT || it == Items.WHEAT_SEEDS || it == Items.SHORT_GRASS || it == Items.COARSE_DIRT
            || it == Items.ROOTED_DIRT || it == Items.MUD;
    }

    // ------------------------------------------------------------------ the fence and the lights

    /**
     * The fence: posts round the pit's ragged rim (each ground cell beside a cut one), a gate toward the town, a lantern
     * every so often; and once there is a shaft, a fence two high round its mouth on the bottom terrace, lanterns at its
     * corners, a gap where the stair goes down. Each entry {post, gate (or null), light (or null), upper post (or null)}.
     */
    private static final Map<String, List<BlockPos[]>> FENCES = new ConcurrentHashMap<>();

    static List<BlockPos[]> fence(ServerLevel level, Villages.Village v, Site s) {
        return fence(level, s);
    }

    static List<BlockPos[]> fence(ServerLevel level, Site s) {
        String key = text(s) + "/" + (s.deep() > 0);
        List<BlockPos[]> kept = FENCES.get(key);
        if (kept != null) return kept;
        List<BlockPos[]> made = fenceNow(level, s);
        if (s.deep() > 0 && !headLevel(level, s)) return made;          // the stairhead not level yet: looked at again
        if (FENCES.size() > 64) FENCES.clear();
        FENCES.put(key, made);
        return made;
    }

    private static List<BlockPos[]> fenceNow(ServerLevel level, Site s) {
        List<BlockPos[]> out = new ArrayList<>();
        Shape sh = shape(s);
        Direction toTown = s.away().getOpposite();
        BlockPos c = s.centre();
        // The gate: the rim cell nearest the middle of the town-side edge.
        int[] b = s.box();
        int gx = toTown == Direction.EAST ? b[2] + 1 : toTown == Direction.WEST ? b[0] - 1 : c.getX();
        int gz = toTown == Direction.SOUTH ? b[3] + 1 : toTown == Direction.NORTH ? b[1] - 1 : c.getZ();
        List<int[]> ring = new ArrayList<>();
        int[] gate = null;
        double gateD = Double.MAX_VALUE;
        for (int x = b[0] - 1; x <= b[2] + 1; x++) {
            for (int z = b[1] - 1; z <= b[3] + 1; z++) {
                if (sh.at(x, z) > 0) continue;
                boolean edge = false;
                for (int a = -1; a <= 1 && !edge; a++) for (int e = -1; e <= 1 && !edge; e++) if (sh.at(x + a, z + e) > 0) edge = true;
                if (!edge) continue;
                ring.add(new int[]{ x, z });
                // A gate wants a cut cell straight in from it (a way in, not a corner).
                if (sh.at(x + toTown.getOpposite().getStepX(), z + toTown.getOpposite().getStepZ()) > 0) {
                    double d = (double) (x - gx) * (x - gx) + (double) (z - gz) * (z - gz);
                    if (d < gateD) { gateD = d; gate = new int[]{ x, z }; }
                }
            }
        }
        for (int[] r : ring) {
            int y = surface(level, r[0], r[1]);
            if (y == Integer.MIN_VALUE) continue;
            BlockPos post = new BlockPos(r[0], y + 1, r[1]);
            boolean isGate = gate != null && r[0] == gate[0] && r[1] == gate[1];
            boolean light = !isGate && noise(r[0], r[1], c) % 5 == 0;
            out.add(new BlockPos[]{ post, isGate ? post : null, light ? post.above() : null, null });
        }
        if (s.deep() > 0 && headLevel(level, s)) {
            // The stairhead: the stair's top steps open to the sky, a fence two high round them but at the stairhead itself.
            int[][] r = ring(s);
            int f = head(s);
            BlockPos g = gap(s);
            Set<Long> open = new HashSet<>();
            for (int j = 0; j < HEAD; j++) open.add(BlockPos.asLong(c.getX() + r[j][0], 0, c.getZ() + r[j][1]));
            open.add(BlockPos.asLong(g.getX(), 0, g.getZ()));
            Set<Long> done = new HashSet<>();
            List<BlockPos[]> head = new ArrayList<>();
            for (int j = 0; j < HEAD; j++) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int x = c.getX() + r[j][0] + d.getStepX(), z = c.getZ() + r[j][1] + d.getStepZ();
                    long k = BlockPos.asLong(x, 0, z);
                    if (open.contains(k) || !done.add(k)) continue;
                    BlockPos post = new BlockPos(x, top(level, x, z, f + 2, f - 4) + 1, z);
                    head.add(new BlockPos[]{ post, null, null, post.above() });
                }
            }
            // A lantern over the first post and the last (the stairhead's two sides).
            for (int i = 0; i < head.size(); i++) {
                BlockPos[] e = head.get(i);
                out.add(i == 0 || i == head.size() - 1 ? new BlockPos[]{ e[0], null, e[0].above(2), e[3] } : e);
            }
        }
        return out;
    }

    /** The next post or lantern the fence wants, or null when it is up. */
    @Nullable
    static BlockPos[] fenceWanted(ServerLevel level, Villages.Village v, Site s) {
        return fenceWanted(level, v, s, null);
    }

    /** As above, the nearest to this spot first (a quarryman goes round the rim, not back and forth across it). */
    @Nullable
    static BlockPos[] fenceWanted(ServerLevel level, Villages.Village v, Site s, @Nullable BlockPos near) {
        BlockPos[] best = null;
        double bestD = Double.MAX_VALUE;
        Map<Long, Long> skipped = UNREACHABLE.getOrDefault(v.id(), Map.of());
        for (BlockPos[] f : fence(level, s)) {
            if (!wantsWork(level, f)) continue;
            if (near != null && skipped.containsKey(f[0].asLong())) continue;
            if (near == null) return f;
            double d = f[0].distSqr(near);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    private static boolean fenced(BlockState st) {
        return st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES);
    }

    private static boolean wantsWork(ServerLevel level, BlockPos[] f) {
        BlockState st = level.getBlockState(f[0]);
        if (!fenced(st)) return st.isAir() || st.canBeReplaced() || st.is(Blocks.TORCH);
        if (f[3] != null) {
            BlockState up = level.getBlockState(f[3]);
            if (!fenced(up)) return up.isAir() || up.canBeReplaced() || up.is(Blocks.TORCH);
        }
        if (f[2] != null) {
            BlockState l = level.getBlockState(f[2]);
            if (!(l.is(Blocks.LANTERN) || l.is(Blocks.TORCH)) && (l.isAir() || l.canBeReplaced())) return true;
        }
        return false;
    }

    /** Put up a post (its upper post, or its lantern) out of the stores, unless {@code free}. */
    static boolean putFence(ServerLevel level, Villages.Village v, Site s, BlockPos[] f, boolean free) {
        BlockPos at = !fenced(level.getBlockState(f[0])) ? f[0] : f[3] != null && !fenced(level.getBlockState(f[3])) ? f[3] : null;
        if (at != null) {
            // Never a post under somebody's feet (they would be left standing on top of it).
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(at).inflate(0.35, 0.0, 0.35).expandTowards(0, 1.0, 0)).isEmpty()) return false;
            boolean gate = f[1] != null && at == f[0];
            if (!free && !TownWork.take(level, v, x -> x.is(gate ? ItemTags.FENCE_GATES : ItemTags.WOODEN_FENCES), 1)
                    && !TownWork.take(level, v, x -> x.is(ItemTags.PLANKS), gate ? 4 : 2)) return false;
            BlockState put = gate ? Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, s.away().getOpposite().getClockWise())
                : Blocks.OAK_FENCE.defaultBlockState();
            level.setBlock(at, put, 3);
            return true;
        }
        if (f[2] == null) return false;
        boolean lantern = free || TownWork.take(level, v, x -> x.is(Items.LANTERN), 1);
        if (!lantern && !TownWork.take(level, v, x -> x.is(Items.TORCH), 1)) return false;
        level.setBlock(f[2], lantern ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState(), 3);
        return true;
    }

    /** The fence and lanterns of a pit taken down into the stores (closed). */
    static void takeDownFence(ServerLevel level, Villages.Village v, Site s) {
        takeDown(level, v, fence(level, s), Set.of());
    }

    private static void takeDown(ServerLevel level, Villages.Village v, List<BlockPos[]> posts, Set<Long> keep) {
        for (BlockPos[] f : posts) {
            for (BlockPos p : new BlockPos[]{ f[2], f[3], f[0] }) {
                if (p == null || keep.contains(p.asLong())) continue;
                BlockState st = level.getBlockState(p);
                Item back = st.is(Blocks.LANTERN) ? Items.LANTERN : st.is(Blocks.TORCH) ? Items.TORCH : st.is(BlockTags.FENCE_GATES) ? Items.OAK_FENCE_GATE
                    : st.is(BlockTags.FENCES) ? Items.OAK_FENCE : null;
                if (back == null) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                Crafts.store(level, v, new ItemStack(back));
            }
        }
    }

    /** The old stage's fence, once the new one round the grown pit is all up: taken down (never before: no open edge). */
    static void oldFence(ServerLevel level, Villages.Village v, Site s) {
        Site old = parse(Ledger.note(v.id(), OLD_FENCE));
        if (old == null) return;
        if (fenceWanted(level, v, s) != null) return;
        Set<Long> keep = new HashSet<>();
        for (BlockPos[] f : fence(level, s)) for (BlockPos p : f) if (p != null) keep.add(p.asLong());
        takeDown(level, v, fence(level, old), keep);
        Ledger.forget(v.id(), OLD_FENCE);
    }

    /**
     * Lights wherever the pit would be dark enough for a monster: a torch on the terraces every seven blocks each way (no
     * spot in the pit more than a dozen steps from one), and in the shaft a torch on its far wall every six blocks down.
     * At most {@code budget} put up this round. Returns how many were.
     */
    static int lights(ServerLevel level, Villages.Village v, Site s, int budget, boolean free) {
        int done = 0;
        Shape sh = shape(s);
        int rim = s.centre().getY();
        BlockPos c = s.centre();
        for (int i = 0; i < sh.w && done < budget; i++) {
            for (int j = 0; j < sh.d && done < budget; j++) {
                int x = sh.x0 + i, z = sh.z0 + j;
                if (Math.floorMod(x - c.getX(), 7) != 3 || Math.floorMod(z - c.getZ(), 7) != 3) continue;
                int depth = depthAt(s, x, z);
                if (depth <= 0) continue;
                int cheb = Math.max(Math.abs(x - c.getX()), Math.abs(z - c.getZ()));
                if (s.deep() > 0 && cheb <= CORE + 2) continue;               // the shaft, its fence and the walk round it
                int y = top(level, x, z, rim + 6, rim - depth - 1);
                if (y != rim - depth) continue;                               // not cut to its depth yet
                BlockPos at = new BlockPos(x, y + 1, z);
                if (!level.getBlockState(at).isAir() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
                if (!free) TownWork.take(level, v, it -> it.is(Items.TORCH), 1);   // (a light goes up whether or not the stores have one)
                level.setBlock(at, Blocks.TORCH.defaultBlockState(), 3);
                done++;
            }
        }
        done += stairLights(level, v, s, budget - done, free);
        return done;
    }

    /**
     * The look of a worked quarry: rubble and spoil heaps outside the fence, the odd boulder, and grass and moss creeping
     * back over the old upper terraces (a few blocks a round, so it comes on slowly). Nothing in the way of the work: heaps
     * and boulders only outside the fence, the terraces' greening on their floors.
     */
    static void weather(ServerLevel level, Site s, int budget) {
        Shape sh = shape(s);
        BlockPos c = s.centre();
        int rim = c.getY();
        long t = level.getGameTime() / 200L;
        for (int k = 0; k < budget; k++) {
            int n = noise((int) (t * 31 + k), (int) (t * 17 - k), c);
            int i = n % sh.w, j = (n / 97) % sh.d;
            int x = sh.x0 + i, z = sh.z0 + j;
            int depth = sh.depth[i * sh.d + j];
            if (depth <= 0 || depth > 3 || depth >= terraceDepth(s) - 2) continue;   // the old upper terraces only
            int y = top(level, x, z, rim + 4, rim - depth - 1);
            if (y != rim - depth) continue;
            BlockPos floor = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(floor);
            if (!rock(st) && !st.is(Blocks.DIRT)) continue;
            if (!level.getBlockState(floor.above()).isAir()) continue;
            level.setBlock(floor, (n & 1) == 0 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            if ((n & 6) == 0) level.setBlock(floor.above(), (n & 8) == 0 ? Blocks.SHORT_GRASS.defaultBlockState() : Blocks.MOSS_CARPET.defaultBlockState(), 3);
        }
    }

    /** Heaps and a boulder or two outside the fence, once, as the pit opens (natural blocks: a later stage cuts them). */
    static void heaps(ServerLevel level, Site s) {
        BlockPos c = s.centre();
        int[] b = s.box();
        Direction toTown = s.away().getOpposite();
        // Only on the two sides across the way it grows, past the widest it will ever open there (a heap left inside a
        // later stage's rim would hold the terraces up and keep the fence off its ground).
        Direction away = s.away();
        Direction[] sides = { away.getClockWise(), away.getCounterClockWise() };
        int out = WIDEST / 2 + 5;
        for (int k = 0; k < 4; k++) {
            int n = noise(k * 13 + s.stage() * 101, k * 7, c);
            Direction side = sides[n % 2];
            int span = s.size() / 2 + s.ext();
            int along = (n / 7) % Math.max(1, span + 8) - 4;                  // from a little toward the town to the far end
            int x = c.getX() + side.getStepX() * out + away.getStepX() * along;
            int z = c.getZ() + side.getStepZ() * out + away.getStepZ() * along;
            int y = surface(level, x, z);
            if (y == Integer.MIN_VALUE || Math.abs(y - c.getY()) > 3) continue;
            BlockState stone = (n & 3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : (n & 3) == 1 ? Blocks.GRAVEL.defaultBlockState()
                : (n & 3) == 2 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
            // A heap: a mound of two layers (a boulder: three blocks of mossy stone).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 && dz != 0) && (n >> (4 + dx + 1 + (dz + 1) * 3) & 1) == 0) continue;
                    int sy = surface(level, x + dx, z + dz);
                    if (sy == Integer.MIN_VALUE) continue;
                    BlockPos p = new BlockPos(x + dx, sy + 1, z + dz);
                    if ( !level.getBlockState(p).canBeReplaced() || !level.getBlockState(p).getFluidState().isEmpty()) continue;
                    level.setBlock(p, stone, 3);
                    if (dx == 0 && dz == 0 && level.getBlockState(p.above()).canBeReplaced()) level.setBlock(p.above(), stone, 3);
                }
            }
        }
    }

    // ------------------------------------------------------------------ grown, closed, moved

    /**
     * The stage cut out: the next one. Wider (six each way) while the land allows and the pit is short of its widest; else a
     * shaft sunk (or sunk a stage deeper) down to the bedrock; there, lengthened away from the town. Closed when none of
     * these can be done. {@code free}: the new fence up at once (the staging and the tests).
     */
    /** The shaft to the bedrock (its stair walked down and up again with a load: fb08). Off: a worked-out pit at its widest
     *  is lengthened away from the town on its terraces. */
    public static boolean SHAFTS = true;

    static void workedOut(ServerLevel level, Villages.Village v, Site s) {
        workedOut(level, v, s, false);
    }

    static void workedOut(ServerLevel level, Villages.Village v, Site s, boolean free) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        StoneYard.Site yard = StoneYard.site(id);
        BlockPos c = s.centre();
        int rim = c.getY();
        Site next = null;
        String news = null;
        int wider = s.size() + WIDER;
        if (s.deep() == 0 && wider <= Math.min(WIDEST, widestForTests)) {
            int h = wider / 2;
            String no = whyNot(id, v.centre(), c.getX() - h, c.getZ() - h, c.getX() + h, c.getZ() + h, yard);
            if (no == null && !wetNear(level, c.getX() - h, c.getZ() - h, c.getX() + h, c.getZ() + h, rim)) {
                next = s.with(wider, s.stage() + 1, 0, s.ext());
                news = "the quarry was opened wider: " + next.words() + ", " + terraceDepth(next) + " deep";
            }
        }
        if (next == null && SHAFTS && !atBedrock(level, s) && rim - bottom(level, s) + 1 > terraceDepth(s) + 1) {
            int deep = Math.min(rim - bottom(level, s) + 1, (s.deep() == 0 ? terraceDepth(s) : s.deep()) + DEEP_STEP);
            next = s.with(s.size(), s.stage() + 1, deep, s.ext());
            news = s.deep() == 0 ? "the quarry's shaft was begun, a stair cut round its walls: " + next.words() + ", to " + deep + " deep"
                : "the quarry's shaft was sunk deeper: " + next.words() + ", to " + deep + " deep" + (atBedrock(level, next) ? " (the bedrock)" : "");
        }
        if (next == null) {
            // At the bedrock (or no rock to sink into): lengthened, away from the town only.
            Site longer = s.with(s.size(), s.stage() + 1, s.deep(), s.ext() + LONGER);
            int[] b = longer.box();
            String no = whyNot(id, v.centre(), b[0], b[1], b[2], b[3], yard);
            if (no == null && !wetNear(level, b[0], b[1], b[2], b[3], rim)) {
                next = longer;
                news = "the quarry was lengthened away from the town: " + next.words() + ", " + Math.max(next.deep(), terraceDepth(next)) + " deep";
            } else {
                close(level, v, s, no != null ? "worked out: no room to grow (" + no + ")" : "worked out: it would come within " + DRY + " blocks of water");
                return;
            }
        }
        // The fence moves out with it: the new one first, the old one down only when the new one is all up.
        if (Ledger.note(id, OLD_FENCE) == null) Ledger.note(id, OLD_FENCE, text(s));
        save(id, next);
        CURSOR.remove(id);
        CORE_Y.remove(id);
        stair(level, next);
        Villages.tell(id, day, news);
        if (free) {
            for (int i = 0; i < 2000; i++) {
                BlockPos[] f = fenceWanted(level, v, next);
                if (f == null || !putFence(level, v, next, f, true)) break;
            }
            oldFence(level, v, next);
        }
        heaps(level, next);
    }

    static void close(ServerLevel level, Villages.Village v, Site s, String why) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Site old = parse(Ledger.note(id, OLD_FENCE));
        if (old != null) takeDownFence(level, v, old);
        Ledger.forget(id, OLD_FENCE);
        // Its fence stays round the drops of a shaft (a closed pit is still a hole); the terraces' rim fence comes in.
        if (s.deep() <= 0) takeDownFence(level, v, s);
        keepAwake(level, v, s, false);
        Ledger.forget(id, SITE);
        Ledger.note(id, "quarry.old", Ledger.note(id, "quarry.old") == null ? s.centre().toShortString() : Ledger.note(id, "quarry.old") + ";" + s.centre().toShortString());
        Villages.tell(id, day, "the quarry was closed (" + why + "); its terraces are left as safe steps, and a new one will be opened further out");
    }

    // ------------------------------------------------------------------ awake while worked

    /** The chunks a town keeps forced for its quarry while its hands are out there. */
    private static final Map<UUID, Set<Long>> AWAKE = new ConcurrentHashMap<>();

    /** The quarry's ground, its yard and the way out to it kept loaded and ticking (on) or let go (off). */
    static void keepAwake(ServerLevel level, Villages.Village v, Site s, boolean on) {
        UUID id = v.id(), who = owner(id, "work");
        Set<Long> want = new HashSet<>();
        if (on) {
            int[] b = s.box();
            for (int x = (b[0] - 4) >> 4; x <= (b[2] + 4) >> 4; x++) for (int z = (b[1] - 4) >> 4; z <= (b[3] + 4) >> 4; z++) want.add(net.minecraft.world.level.ChunkPos.asLong(x, z));
            StoneYard.Site y = StoneYard.site(id);
            if (y != null) want.add(net.minecraft.world.level.ChunkPos.asLong(y.centre().getX() >> 4, y.centre().getZ() >> 4));
            // The way out, past where the town's own ring of awake ground ends.
            BlockPos heart = v.centre();
            double len = Math.sqrt(s.centre().distSqr(heart));
            for (double t = 96; t < len; t += 8) {
                int x = heart.getX() + (int) Math.round((s.centre().getX() - heart.getX()) * t / len);
                int z = heart.getZ() + (int) Math.round((s.centre().getZ() - heart.getZ()) * t / len);
                want.add(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4));
            }
        }
        Set<Long> had = AWAKE.computeIfAbsent(id, k -> new HashSet<>());
        for (long k : new ArrayList<>(had)) {
            if (want.contains(k)) continue;
            ChunkLoad.forceOne(level, who, net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k), false);
            had.remove(k);
        }
        for (long k : want) {
            if (had.add(k)) ChunkLoad.forceOne(level, who, net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k), true);
        }
    }

    public static int awakeForTests(UUID town) {
        Set<Long> s = AWAKE.get(town);
        return s == null ? 0 : s.size();
    }

    // ------------------------------------------------------------------ the town's round

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Blocks a quarryman could not get to lately, by town: when given up. */
    private static final Map<UUID, Map<Long, Long>> UNREACHABLE = new ConcurrentHashMap<>();

    /** Does the town want a quarry now? */
    static boolean wanted(UUID town) {
        if (standDown) return false;
        if (readyForTests) return true;
        if (Villages.headcount(town) < FOLK) return false;
        return Villages.hasBuilt(town, "storage") || Villages.hasBuilt(town, "storehouse");
    }

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (standDown || !level.isLoaded(v.centre())) return;
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        boolean looking = SEARCH.containsKey(id);
        if (last != null && now - last < (looking ? 20L : 200L) && now >= last) return;
        LOOKED.put(id, now);
        Site s = site(id);
        if (s == null) {
            if (!wanted(id)) return;
            Long gave = GAVE_UP.get(id);
            if (gave != null && now - gave < 24000L && now >= gave) return;
            Site chosen = look(level, v, 4, false);
            if (chosen == null) return;
            open(level, v, chosen);
            return;
        }
        // The town grown out to it: closed, and a new one further out.
        int[] b = s.box();
        if (!readyForTests && "inside the growth line".equals(whyNot(id, v.centre(), b[0], b[1], b[2], b[3], null))) {
            close(level, v, s, "the town has grown out to it");
            return;
        }
        UNREACHABLE.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).values().removeIf(t -> now - t > 2400L || now < t);
        List<VillageFolkEntity> men = men(level, v);
        gate(level, v, s);
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean working = !men.isEmpty() && t >= 900L && t < 12500L && !TownLod.away(id);
        keepAwake(level, v, s, working);
        if (level.isLoaded(s.centre())) QuarryCart.ensure(level, v);
        if (level.isLoaded(s.centre())) {
            oldFence(level, v, s);
            stair(level, s);
            lights(level, v, s, 3, false);
            if (t < 12000L) weather(level, s, 2);
        }
    }

    /** The quarry's gate open by day for its hands and their loads, shut at night (and the yard's with it). */
    static void gate(ServerLevel level, Villages.Village v, Site s) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean open = t >= 900L && t < 12000L && !Raids.underAlarm(v.id());
        List<BlockPos> gates = new ArrayList<>();
        for (BlockPos[] f : fence(level, s)) if (f[1] != null) gates.add(f[0]);
        StoneYard.Site y = StoneYard.site(v.id());
        if (y != null) gates.add(StoneYard.ground(StoneYard.at(y, 0, -StoneYard.HALF_D), y));
        for (BlockPos g : gates) {
            if (!level.isLoaded(g)) continue;
            BlockState st = level.getBlockState(g);
            if (st.getBlock() instanceof FenceGateBlock && st.getValue(FenceGateBlock.OPEN) != open) {
                level.setBlock(g, st.setValue(FenceGateBlock.OPEN, open), 3);
            }
        }
    }

    static void open(ServerLevel level, Villages.Village v, Site s) {
        UUID id = v.id();
        s = s.facing(awayFrom(v.centre(), s.centre()));
        save(id, s);
        Ledger.forget(id, OLD_FENCE);
        CURSOR.remove(id);
        CORE_Y.remove(id);
        Ledger.note(id, CUT, Ledger.note(id, CUT) == null ? "0" : Ledger.note(id, CUT));
        if (StoneYard.site(id) == null) {
            StoneYard.Site y = yardFor(level, v, s);
            StoneYard.place(id, y.centre(), y.gate());
        }
        long day = level.getDayTime() / 24000L;
        int dist = (int) Math.round(Math.sqrt(s.centre().distSqr(v.centre())));
        Villages.tell(id, day, "a quarry was opened " + dist + " blocks out, " + (s.outcrop() ? "at a rock outcrop" : "dug down into the ground")
            + ", with a stone yard beside it");
        heaps(level, s);
        FeatHints.hint(level, id, v.centre(), "quarry", "New: " + Villages.name(id) + " has opened a quarry and a stone yard. The board's Stone page shows the stock and the flow.");
    }

    // ------------------------------------------------------------------ the quarrymen

    /** How many hands the quarry wants. */
    static int handsWanted(UUID town) {
        return Math.max(1, Math.min(3, 1 + Villages.headcount(town) / 20));
    }

    /** The quarrymen, chosen again when one has gone: miners first (unless the age waits on the mine), else a spare hand. */
    static List<VillageFolkEntity> men(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<VillageFolkEntity> out = new ArrayList<>();
        String was = Ledger.note(id, MEN);
        if (was != null && !was.isEmpty()) {
            for (String u : was.split(",")) {
                try {
                    if (level.getEntity(UUID.fromString(u)) instanceof VillageFolkEntity f && f.isAlive() && id.equals(f.ownerId()) && !f.isBaby()) out.add(f);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        int want = handsWanted(id);
        if (out.size() < want) {
            List<VillageFolkEntity> pool = new ArrayList<>();
            boolean mineKept = MineHands.kept(level, id);
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || out.contains(f) || f.isHired()) continue;
                AssistantEntity.StationTask t = f.stationTask();
                if (t == AssistantEntity.StationTask.MINE && !mineKept) pool.add(0, f);
                else if (t == AssistantEntity.StationTask.NONE || t == AssistantEntity.StationTask.HAUL || f.workedOut() && !t.isCraft()
                    && t != AssistantEntity.StationTask.GUARD && t != AssistantEntity.StationTask.STORE) pool.add(f);
            }
            for (VillageFolkEntity f : pool) {
                if (out.size() >= want) break;
                out.add(f);
                f.brain("taken on at the quarry");
                Villages.tell(id, level.getDayTime() / 24000L, f.displayNameCap() + " went to work at the quarry");
            }
        }
        List<String> ids = new ArrayList<>();
        for (VillageFolkEntity f : out) ids.add(f.getStringUUID());
        Ledger.note(id, MEN, String.join(",", ids));
        return out;
    }

    static boolean isMan(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return false;
        String was = Ledger.note(id, MEN);
        return was != null && was.contains(f.getStringUUID());
    }

    enum Job { FENCE, CUT, TO_YARD, TO_STORES, DRIVE }

    private static final class Man {
        Job job = Job.CUT;
        @Nullable BlockPos at;
        @Nullable BlockPos[] fence;
        Map<Item, Integer> load = new LinkedHashMap<>();
        List<ItemStack> extra = new ArrayList<>();
        @Nullable ItemStack cart;
        long sent, lastCut, held;
        double bestDist = Double.MAX_VALUE;
        String doing = "";
        /** Climbing out of the pit before it lets the day go (the day's end, the bell, a rescue asked for). */
        boolean out;
    }

    private static final Map<UUID, Man> MEN_STATE = new ConcurrentHashMap<>();

    /** Is this folk at the quarry's work just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        return m != null && f.level().getGameTime() - m.held <= 8;
    }

    /** What it is doing there, for its card and talk; null when it is not. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        return m == null || !busy(f) ? null : m.doing;
    }

    /** Is a quarryman at the yard just now (the mason works while one is)? */
    static boolean atYard(ServerLevel level, Villages.Village v) {
        StoneYard.Site y = StoneYard.site(v.id());
        if (y == null) return false;
        for (VillageFolkEntity f : men(level, v)) if (f.blockPosition().closerThan(y.centre(), 8)) return true;
        return false;
    }

    /** Is this spot down in the pit (inside its bounds, below its rim)? */
    static boolean inPit(Site s, double x, double y, double z) {
        int[] b = s.box();
        return x >= b[0] && x < b[2] + 1 && z >= b[1] && z < b[3] + 1 && y < s.centre().getY() - 0.5;
    }

    /**
     * [featbuild] Asked to be put somewhere (a rescue, a walk home it could not plan): a quarryman down in the pit climbs out
     * instead, by the terraces and the stair (VillageFolkEntity.rescueToPlot, putBeside). True when it is seen to.
     */
    public static boolean climbInstead(VillageFolkEntity f) {
        if (f.ownerId() == null || f.isPassenger() || !isMan(f)) return false;
        Site s = site(f.ownerId());
        if (s == null) return false;
        if (!inPit(s, f.getX(), f.getY(), f.getZ())) {
            // At the quarry's work (or on its way out there): it walks; the work gives up a spot it cannot reach.
            if (!busy(f)) return false;
            f.brain("at the quarry: walks, never put anywhere");
            return true;
        }
        Man m = MEN_STATE.computeIfAbsent(f.getUUID(), k -> new Man());
        if (!m.out) { m.out = true; m.sent = f.level().getGameTime(); m.bestDist = Double.MAX_VALUE; }
        f.brain("down in the quarry: climbing out");
        return true;
    }

    /** Out of the pit by the gate: the spot outside it, toward the town. */
    static BlockPos outside(ServerLevel level, Site s) {
        for (BlockPos[] f : fence(level, s)) {
            if (f[1] != null) {
                BlockPos o = f[0].relative(s.away().getOpposite(), 2);
                return new BlockPos(o.getX(), surface(level, o.getX(), o.getZ()) + 1, o.getZ());
            }
        }
        BlockPos o = s.centre().relative(s.away().getOpposite(), s.half() + 3);
        return new BlockPos(o.getX(), surface(level, o.getX(), o.getZ()) + 1, o.getZ());
    }

    /**
     * From the folk's tick: a quarryman's day at the quarry. True while it holds the folk (by day, the quarry open, no bell,
     * no town's work calling it; and at the day's end, or the bell, until it has climbed out of the pit).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (standDown || f.isBaby() || f.isSleeping() || f.ownerId() == null || f.isHired()) return release(f);
        if (!isMan(f)) return release(f);
        // [quarry2] Pushing the quarry's handcart: the cart walks it (QuarryCart), whatever the hour, till the trip is done.
        Man dm = MEN_STATE.get(f.getUUID());
        if (dm != null && dm.job == Job.DRIVE) {
            if (QuarryCart.driving(level, f)) {
                dm.held = level.getGameTime();
                String w = QuarryCart.words(f.ownerId());
                dm.doing = "pushing the quarry's handcart" + (w == null ? "" : " (" + w.substring(w.indexOf(':') + 2) + ")");
                f.hobbyNow = dm.doing;
                f.lastLeisureTick = f.tickCount;
                f.brain(dm.doing);
                return true;
            }
            dm.job = Job.CUT;
            dm.at = null;
        }
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        Site s = v == null ? null : site(id);
        StoneYard.Site yard = StoneYard.site(id);
        if (v == null || s == null || yard == null || !level.isLoaded(s.centre())) return release(f);
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean off = t < 1000L || t > 11500L || Raids.underAlarm(id) || TownJobs.busy(f) || Weather.stormy(level)
            || t >= 5900L && t < 6900L                                            // the noon bell: the midday meal with everybody
            || RestDay.now(id, level.getDayTime()) != null;                       // the day of rest
        Man m = MEN_STATE.computeIfAbsent(f.getUUID(), k -> new Man());
        long now = level.getGameTime();
        boolean down = inPit(s, f.getX(), f.getY(), f.getZ());
        if (off || m.out) {
            // Never left down the pit: it climbs out first (an hour at most, then it is the town's again wherever it is).
            if (!down || now - m.sent > 2400L && m.out) { m.out = false; return release(f); }
            if (!m.out) { m.out = true; m.sent = now; m.bestDist = Double.MAX_VALUE; }
            m.held = now;
            BlockPos to = outside(level, s);
            m.doing = "climbing out of the quarry";
            if (!stairWay(level, s, f, to)) walkTo(level, f, m, to, 1.5, now, "climbing out of the quarry");
            return true;
        }
        m.held = now;
        f.hobbyNow = m.doing;
        f.lastLeisureTick = f.tickCount;
        wear(level, v, f);
        // The yard laid out first, the first morning: the quarry's own hands do it out of the stores.
        if (Ledger.note(id, "yard.laid") == null) {
            if (!walkTo(level, f, m, yard.centre(), 3.0, now, "laying out the stone yard")) return true;
            StoneYard.layOut(level, v, yard, false);
            FolkTalk.speak(f, "There: a yard for the stone. Now to fill it.");
            return true;
        }
        switch (m.job) {
            case FENCE, CUT -> {
                // [quarry2] The cart standing out with nobody at its handles: taken on by the first free hand out of the pit.
                TradeWagon lone = down ? null : QuarryCart.wantsDriver(level, id);
                if (lone != null) {
                    QuarryCart.takeUp(lone, f);
                    m.job = Job.DRIVE;
                    FolkTalk.speak(f, "Somebody has to push that cart. I'll take it.");
                    return true;
                }
                ensurePick(level, v, f);
                // The fence and its lanterns before the cutting.
                BlockPos[] fw = fenceWanted(level, v, s, f.blockPosition());
                m.fence = fw;
                if (fw != null) {
                    BlockPos stand;
                    if (fw[3] != null) {
                        // The stairhead's fence: worked from the floor beside it, never from over the drop.
                        stand = headStand(level, s, fw[0]);
                    } else {
                        // The rim's: from the pit's side of it, a step in, never standing on the line itself.
                        Direction in = Direction.getNearest(s.centre().getX() - fw[0].getX(), 0, s.centre().getZ() - fw[0].getZ());
                        stand = fw[0].relative(in, 2);
                    }
                    stand = new BlockPos(stand.getX(), top(level, stand.getX(), stand.getZ(), s.centre().getY() + 8, s.centre().getY() - terraceDepth(s) - 2) + 1, stand.getZ());
                    if (m.job != Job.FENCE || m.at == null || !m.at.equals(fw[0])) { m.job = Job.FENCE; m.at = fw[0]; m.sent = now; }
                    double reach = Math.sqrt(f.distanceToSqr(fw[0].getX() + 0.5, fw[0].getY() + 0.5, fw[0].getZ() + 0.5));
                    boolean inReach = reach <= 4.0 && Math.abs(f.getY() - fw[0].getY()) <= 2.5 && !f.blockPosition().equals(fw[0]) && f.onGround();
                    if (!inReach) {
                        if (f.blockPosition().equals(fw[0])) Incidents.walk(f, stand, 0.8D);
                        else walkTo(level, f, m, stand, 1.5, now, "putting up the quarry's fence");
                        if (now - m.sent > TRY) { skip(id, fw[0], now); m.at = null; m.job = Job.CUT; }   // no way to it now: another first
                        return true;
                    }
                    f.getNavigation().stop();
                    m.doing = "putting up the quarry's fence";
                    f.getLookControl().setLookAt(fw[0].getX() + 0.5, fw[0].getY() + 0.5, fw[0].getZ() + 0.5);
                    if (now - m.lastCut >= CUT_EVERY / 2) {
                        m.lastCut = now;
                        f.swing(InteractionHand.MAIN_HAND);
                        if (!putFence(level, v, s, fw, false)) skip(id, fw[0], now);   // short of it, or somebody in the way: another post first
                        m.sent = now;
                    }
                    return true;
                }
                if (m.job == Job.FENCE) { m.job = Job.CUT; m.at = null; }
                if (loadSize(m) >= loadFor(s, f)) { m.job = Job.TO_YARD; m.bestDist = Double.MAX_VALUE; m.sent = now; return true; }
                BlockPos cutAt = m.at != null && cuttable(level, m.at, false) ? m.at : nextCut(level, id, s);
                if (cutAt == null) {
                    if (loadSize(m) > 0) { m.job = Job.TO_YARD; m.sent = now; m.bestDist = Double.MAX_VALUE; return true; }
                    // Cut out: grown a stage, unless the yard is full (the stone not wanted yet: the quarry rests).
                    if (!readyForTests && StoneYard.total(id) >= YARD_FULL) {
                        m.doing = "the yard is full: the quarry rests";
                        return release(f);
                    }
                    workedOut(level, v, s);
                    return true;
                }
                if (!cutAt.equals(m.at)) { m.at = cutAt; m.sent = now; m.bestDist = Double.MAX_VALUE; }
                double dist = Math.sqrt(f.distanceToSqr(cutAt.getX() + 0.5, cutAt.getY() + 0.5, cutAt.getZ() + 0.5));
                if (dist > 4.2 || Math.abs(f.getY() - cutAt.getY()) > 3.5 || f.onClimbable() && !f.onGround()) {
                    BlockPos stand = stairStand(level, s, cutAt);
                    BlockPos goal = stand != null ? stand : cutAt.above();
                    if (stairWay(level, s, f, goal)) { m.doing = "going down the quarry's stair"; m.sent = now; return true; }
                    if (!walkToward(level, f, m, goal, now, stand != null ? "going down the quarry's stair" : "walking out to the quarry face")) {
                        skip(id, cutAt, now);
                        m.at = null;
                    }
                    return true;
                }
                f.getNavigation().stop();
                f.getLookControl().setLookAt(cutAt.getX() + 0.5, cutAt.getY() + 0.5, cutAt.getZ() + 0.5);
                m.doing = "cutting stone at the quarry";
                f.brain(m.doing);
                if (now - m.lastCut < cutEvery(f)) {
                    if ((now - m.lastCut) % 6 == 0) f.swing(InteractionHand.MAIN_HAND);
                    return true;
                }
                m.lastCut = now;
                f.swing(InteractionHand.MAIN_HAND);
                for (ItemStack d : cut(level, cutAt, f, s)) {
                    if (StoneYard.isYardStone(d.getItem())) m.load.merge(d.getItem(), d.getCount(), Integer::sum);
                    else if (!spoil(d)) m.extra.add(d);
                }
                Ledger.note(id, CUT, Long.toString(cutTotal(id) + 1));
                m.at = null;
                return true;
            }
            case TO_YARD -> {
                // [quarry2] The quarry's handcart at its station: the load tipped into it (the yard takes it while the cart is out).
                TradeWagon cart = QuarryCart.home(level, id);
                BlockPos spot = cart != null ? QuarryCart.loadSpot(level, cart) : StoneYard.ground(StoneYard.at(yard, 0, -1), yard);
                if (down && stairWay(level, s, f, spot)) { m.doing = "climbing the quarry's stair with a load"; return true; }
                if (!walkTo(level, f, m, spot, 2.5, now, down ? "climbing out of the quarry with a load"
                        : cart != null ? "carrying stone to the quarry's handcart" : "carting stone to the yard")) {
                    if (now - m.sent > TRY * 4 && !down) {
                        // Never got there: left at the quarry's pile, for the yard to fetch.
                        StoneYard.receive(level, id, m.load);
                        m.load.clear();
                        m.job = Job.CUT;
                    }
                    return true;
                }
                if (cart != null) {
                    f.swing(InteractionHand.MAIN_HAND);
                    QuarryCart.tipIn(level, cart, m.load);
                    if (!m.load.isEmpty()) StoneYard.receive(level, id, m.load);   // what the cart had no room for
                    m.load.clear();
                    for (ItemStack e : m.extra) Crafts.store(level, v, e);
                    m.extra.clear();
                    if (QuarryCart.ready(level, v, cart) && QuarryCart.setOff(level, v, cart, f)) { m.job = Job.DRIVE; return true; }
                    m.job = Job.CUT;
                    return true;
                }
                level.playSound(null, f.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.NEUTRAL, 0.8F, 0.9F);
                f.swing(InteractionHand.MAIN_HAND);
                StoneYard.receive(level, id, m.load);
                m.load.clear();
                for (ItemStack e : m.extra) Crafts.store(level, v, e);
                m.extra.clear();
                // The stores' stone by the handcart: the further the town, the bigger the load (a trip a day is worth it).
                int far = (int) Math.sqrt(yard.centre().distSqr(v.centre()));
                ItemStack due = QuarryCart.on() && Ledger.note(id, QuarryCart.CART) != null ? null    // [quarry2] the cart keeps the stores
                    : StoneYard.deliveryDue(level, v, cartFor(far));
                if (due != null && StoneYard.load(id, due)) {
                    m.cart = due;
                    m.job = Job.TO_STORES;
                    m.sent = now;
                    m.bestDist = Double.MAX_VALUE;
                    FolkTalk.speak(f, "The stores are low on " + due.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
                        + (due.getCount() > StoneYard.CART ? " — I'll take the handcart in." : " — I'll take a load in."));
                } else {
                    m.job = Job.CUT;
                }
                return true;
            }
            case TO_STORES -> {
                BlockPos stores = storesSpot(level, v);
                if (!walkTo(level, f, m, stores, 3.0, now, m.cart != null && m.cart.getCount() > StoneYard.CART ? "pushing the handcart of stone into town"
                        : "carting stone to the town's stores")) {
                    if (now - m.sent > TRY * 8 && m.cart != null) {
                        StoneYard.deliver(level, v, m.cart);         // handed on to a courier on the way
                        m.cart = null;
                        m.job = Job.CUT;
                    }
                    return true;
                }
                if (m.cart != null) StoneYard.deliver(level, v, m.cart);
                m.cart = null;
                m.job = Job.CUT;
                return true;
            }
        }
        return true;
    }

    /** Where to stand to put up a post of the stairhead's fence: the floor beside it furthest from the stair, clear. */
    static BlockPos headStand(ServerLevel level, Site s, BlockPos post) {
        int f = head(s);
        BlockPos best = gap(s).atY(f + 1);
        double bestD = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = post.getX() + d.getStepX(), z = post.getZ() + d.getStepZ();
            int t = top(level, x, z, f + 3, f - 3);
            if (t < f - 1 || t > f + 1) continue;
            BlockPos at = new BlockPos(x, t + 1, z);
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) continue;
            if (stairStep(level, s, at.below()) >= 0) continue;
            double dd = at.distSqr(s.centre().atY(at.getY()));
            if (dd > bestD) { bestD = dd; best = at; }
        }
        return best;
    }

    /** The handcart's load for a quarry this far from the heart: thirty-two near, up to a hundred and twenty-eight. */
    static int cartFor(int far) {
        return Math.min(128, StoneYard.CART * (1 + Math.max(0, far) / 80));
    }

    /** A load: sixteen at the face, a barrow of forty-eight from down the shaft (a long climb for a small load is waste). */
    private static int loadFor(Site s, VillageFolkEntity f) {
        return s.deep() > 0 && f.getY() < head(s) - 0.5 ? LOAD * 3 : LOAD;
    }

    /** The worn track: grass under a quarryman's feet out past the growth line worn to a path, a little at a time. */
    private static void wear(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (!f.onGround() || f.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4 || f.tickCount % 5 != 0) return;
        BlockPos under = f.blockPosition().below();
        if (!level.getBlockState(under).is(Blocks.GRASS_BLOCK) || !level.getBlockState(under.above()).canBeReplaced()) return;
        int dx = under.getX() - v.centre().getX(), dz = under.getZ() - v.centre().getZ();
        if (Math.max(Math.abs(dx), Math.abs(dz)) <= growthReach(v.id())) return;
        if (Villages.onFarmland(v.id(), dx, dz, 1, 1)) return;
        if ((noise(under.getX(), under.getZ(), v.centre()) & 1) != 0) return;
        if (!level.getBlockState(under.above()).isAir()) level.setBlock(under.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(under, Blocks.DIRT_PATH.defaultBlockState(), 3);
    }

    private static boolean release(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        if (m != null && !m.load.isEmpty() && f.level() instanceof ServerLevel level && f.ownerId() != null) {
            StoneYard.receive(level, f.ownerId(), m.load);                // the day's last load stacked at the yard
            m.load.clear();
        }
        if (m != null && m.cart != null && f.level() instanceof ServerLevel level && f.ownerId() != null) {
            Villages.Village v = Villages.get(f.ownerId());
            if (v != null) StoneYard.deliver(level, v, m.cart);
            m.cart = null;
        }
        return false;
    }

    static long cutTotal(UUID town) {
        try {
            String s = Ledger.note(town, CUT);
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int loadSize(Man m) {
        int n = 0;
        for (int c : m.load.values()) n += c;
        return n;
    }

    private static int cutEvery(VillageFolkEntity f) {
        Item it = f.getMainHandItem().getItem();
        if (it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) return CUT_EVERY / 2;
        if (it == Items.IRON_PICKAXE) return CUT_EVERY * 3 / 4;
        return CUT_EVERY;
    }

    private static void skip(UUID town, BlockPos p, long now) {
        UNREACHABLE.computeIfAbsent(town, k -> new ConcurrentHashMap<>()).put(p.asLong(), now);
    }

    /** A pick in its hand: its own, or one of the stores'. */
    private static void ensurePick(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.getMainHandItem().getItem() instanceof PickaxeItem) return;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.getItem() instanceof PickaxeItem) {
                ItemStack hand = f.getMainHandItem().copy();
                ItemStack pick = s.copyWithCount(1);
                if (f.removeMatching(x -> x.getItem() instanceof PickaxeItem, 1) == 1) {
                    f.setItemSlot(EquipmentSlot.MAINHAND, pick);
                    if (!hand.isEmpty()) { ItemStack left = f.insertItem(hand); if (!left.isEmpty()) Crafts.store(level, v, left); }
                }
                return;
            }
        }
        ItemStack p = Crafts.takeOne(level, v, s -> s.getItem() instanceof PickaxeItem);
        if (p.isEmpty()) return;
        ItemStack hand = f.getMainHandItem().copy();
        f.setItemSlot(EquipmentSlot.MAINHAND, p);
        if (!hand.isEmpty()) { ItemStack left = f.insertItem(hand); if (!left.isEmpty()) Crafts.store(level, v, left); }
    }

    /**
     * Where to walk now on the way to a spot: the spot itself when it is near; on a long walk (the quarry is a couple of
     * hundred blocks out) a stop thirty-two blocks along the straight way, on the ground there, so the way-finder is never
     * asked for more than it can plan.
     */
    static BlockPos leg(ServerLevel level, VillageFolkEntity f, BlockPos to) {
        double dx = to.getX() + 0.5 - f.getX(), dz = to.getZ() + 0.5 - f.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= 48.0) return to;
        int x = (int) Math.floor(f.getX() + dx * 32.0 / d), z = (int) Math.floor(f.getZ() + dz * 32.0 / d);
        if (!level.hasChunk(x >> 4, z >> 4)) return to;
        int y = surface(level, x, z);
        return y == Integer.MIN_VALUE ? to : new BlockPos(x, y + 1, z);
    }

    /** Walk to within so far of a spot; true once there. */
    private static boolean walkTo(ServerLevel level, VillageFolkEntity f, Man m, BlockPos to, double within, long now, String doing) {
        m.doing = doing;
        double d = Math.sqrt(f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5));
        if (d <= within) return true;
        Incidents.walk(f, leg(level, f, to), 0.8D);
        f.brain(doing);
        return false;
    }

    /** Walk toward a spot; false when it has not got nearer in a while (the spot given up). */
    private static boolean walkToward(ServerLevel level, VillageFolkEntity f, Man m, BlockPos to, long now, String doing) {
        m.doing = doing;
        double d = f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d < m.bestDist - 1.0) { m.bestDist = d; m.sent = now; }
        if (now - m.sent > TRY) return false;
        Incidents.walk(f, leg(level, f, to), 0.8D);
        f.brain(doing);
        return true;
    }

    private static BlockPos storesSpot(ServerLevel level, Villages.Village v) {
        List<BlockPos> chests = Villages.storeChests(level, v.id());
        if (!chests.isEmpty()) return chests.get(0);
        BlockPos sh = Villages.builtAt(v.id(), "storehouse");
        if (sh != null) return sh;
        BlockPos st = Villages.builtAt(v.id(), "storage");
        return st != null ? st : v.centre();
    }

    // ------------------------------------------------------------------ the board

    /** How deep the pit is now: its shaft's depth if it has one, else its terraces'. */
    static int depthNow(ServerLevel level, Site s) {
        if (s.deep() <= 0) return terraceDepth(s);
        int[][] r = ring(s);
        for (int n = lastStep(level, s); n >= 0; n--) {
            BlockPos feet = stepFeet(s, r, n);
            if (level.getBlockState(feet).isAir()) return Math.max(terraceDepth(s), s.centre().getY() - feet.getY() + 1);
        }
        return terraceDepth(s);
    }

    /** "Quarry stage 6: 33×33, 70 deep". */
    public static String stageWords(ServerLevel level, Site s) {
        return "Quarry " + s.words() + ", " + depthNow(level, s) + " deep";
    }

    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        UUID id = v.id();
        Site s = site(id);
        c.putBoolean("has", s != null);
        c.putInt("growth", growthReach(id));
        c.putBoolean("wanted", wanted(id));
        c.putInt("folk", Villages.headcount(id));
        c.putBoolean("looking", SEARCH.containsKey(id));
        if (s != null) {
            c.putIntArray("at", new int[]{ s.centre().getX(), s.centre().getY(), s.centre().getZ() });
            c.putInt("size", s.size());
            c.putInt("stage", s.stage());
            c.putInt("wx", s.widthX());
            c.putInt("wz", s.widthZ());
            c.putInt("deep", depthNow(level, s));
            c.putBoolean("shaft", s.deep() > 0);
            c.putBoolean("bedrock", atBedrock(level, s));
            c.putString("stageWords", stageWords(level, s));
            c.putBoolean("outcrop", s.outcrop());
            c.putInt("dist", (int) Math.round(Math.sqrt(s.centre().distSqr(v.centre()))));
            c.putLong("cut", cutTotal(id));
            BlockPos[] fw = fenceWanted(level, v, s);
            c.putBoolean("fenced", fw == null);
            ListTag men = new ListTag();
            for (VillageFolkEntity f : men(level, v)) {
                CompoundTag m = new CompoundTag();
                m.putString("name", f.displayNameCap());
                m.putString("trade", JobMarket.noun(f.stationTask()));
                String d = doing(f);
                long t = Math.floorMod(level.getDayTime(), 24000L);
                m.putString("doing", d != null ? d : t < 1000L || t > 11500L ? "home for the night" : "at something else just now");
                men.add(m);
            }
            c.put("men", men);
            String cart = QuarryCart.words(id);
            if (cart != null) {
                long[] tally = QuarryCart.tally(id);
                c.putString("cart", cart);
                c.putLong("cartTrips", tally[0]);
                c.putLong("cartCarried", tally[1]);
                String last = QuarryCart.lastTrip(id);
                if (last != null) c.putString("cartLast", last);
            }
        }
        String old = Ledger.note(id, "quarry.old");
        if (old != null) c.putString("old", old);
        return c;
    }

    // ------------------------------------------------------------------ the tests

    @Nullable
    public static Site chooseForTests(ServerLevel level, Villages.Village v) {
        return choose(level, v);
    }

    public static void openForTests(ServerLevel level, Villages.Village v, Site s) {
        open(level, v, s);
    }

    /** Tests: so many blocks cut now (no walking), into the yard, the quarry grown as each stage is cut (its fence, ladder
     *  and lights put up as it goes); returns how many were. */
    public static int cutForTests(ServerLevel level, Villages.Village v, int n) {
        Site s = site(v.id());
        if (s == null) return 0;
        Map<Item, Integer> load = new HashMap<>();
        int done = 0;
        fenceForTests(level, v);
        for (int i = 0; i < n; i++) {
            BlockPos p = nextCut(level, v.id(), s);
            if (p == null) {
                workedOut(level, v, s, true);
                s = site(v.id());
                if (s == null) break;
                fenceForTests(level, v);
                continue;
            }
            for (ItemStack d : cut(level, p, null, s)) if (StoneYard.isYardStone(d.getItem())) load.merge(d.getItem(), d.getCount(), Integer::sum);
            done++;
        }
        StoneYard.receive(level, v.id(), load);
        Ledger.note(v.id(), CUT, Long.toString(cutTotal(v.id()) + done));
        s = site(v.id());
        if (s != null) {
            stair(level, s);
            fenceForTests(level, v);
            lights(level, v, s, 10000, true);
        }
        return done;
    }

    /** Tests: cut until the quarry has grown to this stage (or n blocks); returns how many were. */
    public static int cutToStageForTests(ServerLevel level, Villages.Village v, int stage, int n) {
        int done = 0;
        while (done < n) {
            Site s = site(v.id());
            if (s == null || s.stage() >= stage && nextCut(level, v.id(), s) == null) break;
            if (s.stage() > stage) break;
            done += cutForTests(level, v, Math.min(500, n - done));
        }
        return done;
    }

    /** Tests: so many blocks of this stage cut now, the quarry not grown when the stage is done; returns how many were. */
    public static int cutStageForTests(ServerLevel level, Villages.Village v, int n) {
        Site s = site(v.id());
        if (s == null) return 0;
        Map<Item, Integer> load = new HashMap<>();
        int done = 0;
        for (int i = 0; i < n; i++) {
            BlockPos p = nextCut(level, v.id(), s);
            if (p == null) break;
            for (ItemStack d : cut(level, p, null, s)) if (StoneYard.isYardStone(d.getItem())) load.merge(d.getItem(), d.getCount(), Integer::sum);
            done++;
        }
        StoneYard.receive(level, v.id(), load);
        stair(level, s);
        fenceForTests(level, v);
        lights(level, v, s, 10000, true);
        return done;
    }

    /** Tests: is every spot a body could stand on in the pit within a dozen steps of a light (a torch, a lantern)? */
    public static boolean lightsForTests(ServerLevel level, Site s) {
        int[] b = s.box();
        int rim = s.centre().getY();
        List<BlockPos> lights = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = b[0] - 2; x <= b[2] + 2; x++) for (int z = b[1] - 2; z <= b[3] + 2; z++) for (int y = bottom(level, s) - 1; y <= rim + 4; y++) {
            BlockState st = level.getBlockState(p.set(x, y, z));
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LANTERN)) lights.add(p.immutable());
        }
        for (int x = b[0]; x <= b[2]; x++) {
            for (int z = b[1]; z <= b[3]; z++) {
                if (depthAt(s, x, z) <= 0) continue;
                BlockPos at = new BlockPos(x, top(level, x, z, rim + 4, bottom(level, s) - 2) + 1, z);
                boolean lit = false;
                for (BlockPos l : lights) if (l.distManhattan(at) <= 12) { lit = true; break; }
                if (!lit) {
                    com.mojang.logging.LogUtils.getLogger().info("[featbuild] dark in the quarry at {} ({} on {}, depth {}, top {})", at.toShortString(),
                        level.getBlockState(at).getBlock().getName().getString(), level.getBlockState(at.below()).getBlock().getName().getString(),
                        depthAt(s, x, z), top(level, x, z, rim + 4, bottom(level, s) - 2));
                    BlockPos cell = new BlockPos(x, top(level, x, z, rim + 4, bottom(level, s) - 2), z);
                    StringBuilder nb = new StringBuilder();
                    for (Direction d : Direction.Plane.HORIZONTAL) nb.append(d.getName().charAt(0)).append(top(level, x + d.getStepX(), z + d.getStepZ(), rim + 8, bottom(level, s) - 2)).append(' ');
                    com.mojang.logging.LogUtils.getLogger().info("[featbuild]   cuttable {} terraced {} neighbours {} skipped {}", cuttable(level, cell, false),
                        terraced(level, cell, rim, bottom(level, s)), nb, UNREACHABLE.toString().length());
                    return false;
                }
            }
        }
        return true;
    }

    public static void fenceForTests(ServerLevel level, Villages.Village v) {
        Site s = site(v.id());
        if (s == null) return;
        for (int i = 0; i < 4000; i++) {
            BlockPos[] f = fenceWanted(level, v, s);
            if (f == null || !putFence(level, v, s, f, true)) break;
        }
        oldFence(level, v, s);
    }

    public static void saveForTests(UUID town, Site s) {
        save(town, s);
        CURSOR.remove(town);
        CORE_Y.remove(town);
    }

    public static Site withForTests(Site s, int size, int stage, int deep, int ext) {
        return s.with(size, stage, deep, ext);
    }

    public static int depthAtForTests(Site s, int x, int z) {
        return depthAt(s, x, z);
    }

    public static int[] boxForTests(Site s) {
        return s.box();
    }

    public static int bottomForTests(ServerLevel level, Site s) {
        return bottom(level, s);
    }

    /** Tests: would a site here be turned down for water near its widest footprint? */
    public static boolean wetForTests(ServerLevel level, BlockPos centre, Direction away) {
        int[] f = finalFootprint(centre, away);
        return wetNear(level, f[0], f[1], f[2], f[3], centre.getY());
    }

    public static boolean atBedrockForTests(ServerLevel level, Site s) {
        return atBedrock(level, s);
    }

    public static BlockPos outsideForTests(ServerLevel level, Site s) {
        return outside(level, s);
    }

    /** Tests: where a body stands on the stair's lowest step cut so far (null with no stair). */
    @Nullable
    public static BlockPos stairFootForTests(ServerLevel level, Site s) {
        int[][] r = ring(s);
        for (int n = lastStep(level, s); n >= 0; n--) if (level.getBlockState(stepFeet(s, r, n)).isAir()) return stepFeet(s, r, n);
        return null;
    }

    /** Tests: the stair's steps, top to bottom, each as {feet y, 1 if a torch is in its room, 1 if its floor is a stair}. */
    public static List<int[]> stairForTests(ServerLevel level, Site s) {
        List<int[]> out = new ArrayList<>();
        int[][] r = ring(s);
        for (int n = 0; n <= lastStep(level, s); n++) {
            BlockPos feet = stepFeet(s, r, n);
            if (!level.getBlockState(feet).isAir()) break;
            boolean lit = false;
            for (int dy = 0; dy < HEAD; dy++) lit |= level.getBlockState(feet.above(dy)).is(Blocks.WALL_TORCH);
            out.add(new int[]{ feet.getY(), lit ? 1 : 0, level.getBlockState(feet.below()).getBlock() instanceof StairBlock ? 1 : 0 });
        }
        return out;
    }

    /** Tests: the stairhead (where the stair is entered from the bottom terrace), at its floor's level plus one. */
    public static BlockPos stairHeadForTests(Site s) {
        return gap(s).atY(head(s) + 1);
    }

    public static List<VillageFolkEntity> menForTests(ServerLevel level, Villages.Village v) {
        return men(level, v);
    }

    @Nullable
    public static String doingForTests(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        return m == null ? null : m.job + " " + m.doing + (m.fence != null ? " (post " + m.fence[0].toShortString() + ")" : "");
    }

    /** Tests: a quarryman sent off with a full load (to the yard, and back to the face after). */
    public static void loadedForTests(VillageFolkEntity f, int n) {
        Man m = MEN_STATE.computeIfAbsent(f.getUUID(), k -> new Man());
        m.load.put(Items.COBBLESTONE, n);
        m.job = Job.TO_YARD;
        m.sent = f.level().getGameTime();
    }
}
