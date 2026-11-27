package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.TerraformAudit;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [terra10] The founding's terraforming, audited. Towns of eight are founded on grounds built to hold everything
 * the world's own land throws at a founding: a meadow strewn with gravel, ore and andesite at the top, pits, a pond,
 * trees on the edge and a hut somebody built; a steep hillside across the edge with a cave in it holding water and
 * lava; a thick forest with vines; a river, a ravine with a bit of an old mine at its foot, a cavern under a thin
 * crust and sand over a hollow; desert dunes over a cave; a snowy slope with powder snow and spruces. When each
 * levelling is done its ground is looked over (entity/TerraformAudit) and every fault counted.
 *
 * <p>The test world is flat and four deep, so each ground is laid down first, a plateau twenty deep, straight into
 * the chunks' sections (a million blocks a ground: through setBlock it would be the whole of the test's time).
 *
 * <p>x 4,000,000 to 4,100,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TerraformGameTests {

    private static final String EMPTY = "empty";
    private static final int X0 = 4_000_000, Z = 90000, FOLK = 8;
    /** Which grounds (-Dtf.grounds), and whether every count must be nought (-Dtf.strict). */
    private static final String GROUNDS = System.getProperty("tf.grounds", "meadow,hillside,forest,waters,desert,snowy");
    private static final boolean STRICT = Boolean.parseBoolean(System.getProperty("tf.strict", "true"));
    private static final String[] ALL = { "meadow", "hillside", "forest", "waters", "desert", "snowy" };

    /** The counts that must all be nought. */
    static final String[] MUST_BE_NOUGHT = { "offLevel", "holes", "crust", "pits", "oddTop", "growthOnSquare", "floatLeaves",
        "floatLogs", "stumps", "floatVines", "sheer", "rawEdge", "flowing", "lava", "junk", "falling", "items",
        "pillars" };   // [city26] no column left standing, no stub in a street

    // ============================================================ laying the ground down

    /** Blocks written straight into the sections, and the heightmaps primed after. */
    static final class Land {
        final ServerLevel level;
        final Map<Long, LevelChunk> chunks = new HashMap<>();

        Land(ServerLevel level) { this.level = level; }

        void put(int x, int y, int z, BlockState s) {
            if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return;
            LevelChunk ch = chunks.computeIfAbsent(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL), k -> level.getChunk(x >> 4, z >> 4));
            LevelChunkSection sec = ch.getSection(ch.getSectionIndex(y));
            sec.setBlockState(x & 15, y & 15, z & 15, s, false);
        }

        void put(int x, int y, int z, Block b) { put(x, y, z, b.defaultBlockState()); }

        BlockState get(int x, int y, int z) { return level.getBlockState(new BlockPos(x, y, z)); }

        void done() {
            for (LevelChunk ch : chunks.values()) {
                Heightmap.primeHeightmaps(ch, EnumSet.allOf(Heightmap.Types.class));
                ch.setUnsaved(true);
            }
        }
    }

    /** A column from the world's floor to {@code h}: stone, then three of {@code sub}, then {@code top}; air over it. */
    static void column(Land l, int x, int z, int base, int h, BlockState top, BlockState sub, BlockState deep) {
        for (int y = base; y <= h + 40; y++) {
            BlockState s = y > h ? Blocks.AIR.defaultBlockState() : y == h ? top : y >= h - 3 ? sub : deep;
            l.put(x, y, z, s);
        }
    }

    /** A wild tree (its leaves not set by anybody), {@code tall} logs, a crown {@code r} across, vines on it if asked. */
    static void wildTree(Land l, int x, int y, int z, int tall, int r, Block log, Block leaves, boolean vines, boolean wide) {
        BlockState lv = leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
        for (int dy = -r; dy <= 2; dy++) {
            int rr = dy >= 1 ? r - 1 : r;
            for (int dx = -rr; dx <= rr + (wide ? 1 : 0); dx++) {
                for (int dz = -rr; dz <= rr + (wide ? 1 : 0); dz++) {
                    if (Math.abs(dx) == rr && Math.abs(dz) == rr) continue;
                    int yy = y + tall + dy;
                    l.put(x + dx, yy, z + dz, lv);
                }
            }
        }
        for (int i = 0; i < tall + 1; i++) {
            l.put(x, y + i, z, log);
            if (wide) { l.put(x + 1, y + i, z, log); l.put(x, y + i, z + 1, log); l.put(x + 1, y + i, z + 1, log); }
        }
        if (vines) {
            int vx = x - r - 1;
            for (int dy = 0; dy < 4; dy++) {
                l.put(vx, y + tall - dy, z, Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true));
            }
        }
    }

    /** Lay one ground down round {@code cx, cz}; returns the hut somebody built on it, if any (for the check after). */
    static BlockPos lay(ServerLevel level, String kind, int cx, int cz, int half) {
        Land l = new Land(level);
        int base = level.getMinBuildHeight() + 1;
        int b = base + 22;                                            // the plateau's top
        RandomSource rnd = RandomSource.create(kind.hashCode());
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(),
            stone = Blocks.STONE.defaultBlockState(), sand = Blocks.SAND.defaultBlockState(),
            sandstone = Blocks.SANDSTONE.defaultBlockState();
        int[][] hm = new int[2 * half + 1][2 * half + 1];
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int h;
                BlockState top = grass, sub = dirt, deep = stone;
                switch (kind) {
                    case "meadow" -> h = b + (int) Math.round(2 * Math.sin(dx / 9.0) * Math.cos(dz / 11.0));
                    case "hillside" -> {
                        double rise = Math.max(0, Math.min(30, (dx - 18) * 1.6 + 4 * Math.sin(dz / 7.0)));
                        h = b + (int) Math.round(rise) + (int) Math.round(Math.sin(dz / 13.0));
                        if (rise > 8) top = stone;                    // bare rock up the hill
                    }
                    case "forest" -> h = b + (int) Math.round(1.5 * Math.sin(dx / 7.0 + dz / 13.0));
                    case "waters" -> h = b + (int) Math.round(Math.sin(dx / 15.0));
                    case "desert" -> {
                        h = b + (int) Math.round(3 * Math.sin(dx / 8.0) * Math.sin(dz / 10.0));
                        top = sand; sub = sand; deep = sandstone;
                    }
                    default -> {                                      // snowy: a long slope, snow up it
                        h = b - 8 + (int) Math.round((dx + half) / 6.0);
                        top = h > b + 2 ? Blocks.SNOW_BLOCK.defaultBlockState() : grass.setValue(BlockStateProperties.SNOWY, true);
                    }
                }
                hm[dx + half][dz + half] = h;
                column(l, cx + dx, cz + dz, base, h, top, sub, deep);
                if (kind.equals("snowy") && !top.is(Blocks.SNOW_BLOCK)) l.put(cx + dx, h + 1, cz + dz, Blocks.SNOW);
            }
        }
        BlockPos hut = null;
        java.util.function.BiFunction<Integer, Integer, Integer> H = (dx, dz) -> hm[dx + half][dz + half];
        switch (kind) {
            case "meadow" -> {
                for (int dx = 10; dx <= 14; dx++) for (int dz = -5; dz <= -1; dz++) l.put(cx + dx, H.apply(dx, dz), cz + dz, Blocks.GRAVEL);
                for (int dx = -14; dx <= -10; dx++) for (int dz = 12; dz <= 15; dz++) l.put(cx + dx, H.apply(dx, dz), cz + dz, Blocks.ANDESITE);
                for (int[] o : new int[][]{ {3, 3}, {-5, 9}, {20, -12}, {-22, -18} }) l.put(cx + o[0], H.apply(o[0], o[1]), cz + o[1], Blocks.COAL_ORE);
                for (int[] p : new int[][]{ {5, 8}, {-7, 3}, {16, 20} }) {           // one-block pits, two deep
                    int h = H.apply(p[0], p[1]);
                    l.put(cx + p[0], h, cz + p[1], Blocks.AIR);
                    l.put(cx + p[0], h - 1, cz + p[1], Blocks.AIR);
                }
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {   // a pond
                    if (dx * dx + dz * dz > 5) continue;
                    l.put(cx - 16 + dx, H.apply(-16 + dx, -6 + dz), cz - 6 + dz, Blocks.WATER);
                }
                for (int a = 0; a < 360; a += 30) {                                  // trees along the edge
                    int r = 38 + (a / 30) % 4 * 3;
                    int tx = (int) Math.round(Math.cos(Math.toRadians(a)) * r), tz = (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                    wildTree(l, cx + tx, H.apply(tx, tz) + 1, cz + tz, 5, 2, Blocks.OAK_LOG, Blocks.OAK_LEAVES, false, false);
                }
                wildTree(l, cx + 8, H.apply(8, 14) + 1, cz + 14, 5, 2, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, false, false);
                hut = new BlockPos(cx - 24, H.apply(-24, 2) + 1, cz + 2);
            }
            case "hillside" -> {
                // A cave through the hill at the height the cut will come down to, water on its floor and lava at its end.
                for (int dx = 18; dx <= 64; dx++) {
                    int zc = (int) Math.round(6 * Math.sin(dx / 9.0));
                    for (int dz = zc - 2; dz <= zc + 2; dz++) {
                        for (int y = b + 1; y <= b + 4; y++) {
                            if (y > H.apply(dx, dz) - 2) continue;
                            Block fill = y == b + 1 && dx > 30 && dx < 40 ? Blocks.WATER : y == b + 1 && dx > 52 ? Blocks.LAVA : Blocks.AIR;
                            l.put(cx + dx, y, cz + dz, fill);
                        }
                    }
                }
                // And a cave mouth opening in the square, going down.
                for (int i = 0; i < 10; i++) {
                    for (int dz = -1; dz <= 1; dz++) for (int y = 0; y < 3; y++) l.put(cx - 10 + i, H.apply(-10 + i, 18) - i - y, cz + 18 + dz, Blocks.AIR);
                }
                for (int i = 0; i < 8; i++) {
                    int tx = 25 + rnd.nextInt(20), tz = -30 + rnd.nextInt(60);
                    wildTree(l, cx + tx, H.apply(tx, tz) + 1, cz + tz, 6, 2, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, false, false);
                }
            }
            case "forest" -> {
                for (int tx = -half + 6; tx <= half - 6; tx += 7) {
                    for (int tz = -half + 6; tz <= half - 6; tz += 7) {
                        int ox = tx + rnd.nextInt(3) - 1, oz = tz + rnd.nextInt(3) - 1;
                        boolean dark = rnd.nextInt(5) == 0;
                        wildTree(l, cx + ox, H.apply(ox, oz) + 1, cz + oz, dark ? 7 : 4 + rnd.nextInt(3), dark ? 3 : 2,
                            dark ? Blocks.DARK_OAK_LOG : Blocks.OAK_LOG, dark ? Blocks.DARK_OAK_LEAVES : Blocks.OAK_LEAVES,
                            rnd.nextInt(3) == 0, dark);
                    }
                }
            }
            case "waters" -> {
                for (int dz = -half; dz <= half; dz++) {                             // a river, north to south
                    for (int dx = -31; dx <= -26; dx++) {
                        int h = H.apply(-31, dz);
                        for (int y = h - 3; y <= h + 1; y++) l.put(cx + dx, y, cz + dz, y <= h - 1 ? Blocks.WATER : Blocks.AIR);
                        l.put(cx + dx, h - 4, cz + dz, Blocks.SAND);
                    }
                }
                for (int dx = -10; dx <= 72; dx++) {                                 // a ravine, west to east
                    for (int dz = 20; dz <= 22; dz++) {
                        for (int y = b - 18; y <= b + 3; y++) l.put(cx + dx, y, cz + dz, Blocks.AIR);
                    }
                }
                for (int dx = 8; dx <= 12; dx++) {                                   // an old mine's boards at its foot
                    l.put(cx + dx, b - 19, cz + 21, Blocks.OAK_PLANKS);
                    l.put(cx + dx, b - 18, cz + 21, Blocks.RAIL);
                }
                for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {   // a cavern under a thin crust
                    if (dx * dx + dz * dz > 144) continue;
                    for (int y = b - 14; y <= b - 4; y++) l.put(cx + 10 + dx, y, cz - 15 + dz, Blocks.AIR);
                }
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {   // sand over a hollow
                    int h = H.apply(-10 + dx, 10 + dz);
                    l.put(cx - 10 + dx, h, cz + 10 + dz, Blocks.SAND);
                    for (int y = h - 4; y < h; y++) l.put(cx - 10 + dx, y, cz + 10 + dz, Blocks.AIR);
                }
            }
            case "desert" -> {
                for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {   // a cave right under the sand
                    int h = H.apply(8 + dx, -8 + dz);
                    for (int y = h - 6; y < h - 1; y++) l.put(cx + 8 + dx, y, cz - 8 + dz, Blocks.AIR);
                }
                for (int[] c : new int[][]{ {-12, 6}, {15, 15}, {-30, -20}, {44, 3} }) {
                    int h = H.apply(c[0], c[1]);
                    for (int i = 1; i <= 3; i++) l.put(cx + c[0], h + i, cz + c[1], Blocks.CACTUS);
                }
                l.put(cx + 5, H.apply(5, 20) + 1, cz + 20, Blocks.DEAD_BUSH);
            }
            default -> {                                                              // snowy
                for (int dx = -6; dx <= -2; dx++) for (int dz = 6; dz <= 9; dz++) l.put(cx + dx, H.apply(dx, dz), cz + dz, Blocks.POWDER_SNOW);
                for (int i = 0; i < 10; i++) {
                    int tx = -half + 8 + rnd.nextInt(2 * half - 16), tz = -half + 8 + rnd.nextInt(2 * half - 16);
                    wildTree(l, cx + tx, H.apply(tx, tz) + 1, cz + tz, 6, 2, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, false, false);
                }
            }
        }
        l.done();
        if (hut != null) {
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) for (int dy = 0; dy < 3; dy++) {
                boolean wall = dx != 1 || dz != 1 || dy == 2;
                level.setBlock(hut.offset(dx, dy, dz), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
        return hut;
    }

    // ============================================================ the audit

    /**
     * Found a town of eight on each of these grounds at once and, when every levelling is done, look each over: every
     * count of a fault nought (unless -Dtf.strict=false); a hut somebody built still standing; a village there.
     */
    static void audit(GameTestHelper helper, String label, List<String> kinds) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        int radius = FoundingPlan.coreRadius(FOLK), outer = radius + FoundingPlan.BAND_MAX, half = outer + 14;
        com.jrpetty.mcassistant.entity.TownBlocks.traceForTests(true);      // [city26] who set a column off its level, if any
        Map<String, BlockPos> hearts = new LinkedHashMap<>();
        Map<String, BlockPos> huts = new HashMap<>();
        for (String k : kinds) {
            int cx = X0 + 4000 * java.util.Arrays.asList(ALL).indexOf(k), cz = Z;
            Kit.hold(level, cx, cz, half + 16);
            Kit.prepare(level, cx, cz, half + 16);
            long t0 = System.nanoTime();
            BlockPos hut = lay(level, k, cx, cz, half);
            if (hut != null) huts.put(k, hut);
            BlockPos heart = Kit.surface(level, cx, cz);
            Kit.log("[VT] " + label + " " + k + " laid in " + (System.nanoTime() - t0) / 1_000_000 + " ms; the heart at " + heart.toShortString());
            Founding.Outcome o = Founding.foundNow(level, heart, FOLK, 0F, null);
            Kit.log("[VT] " + label + " " + k + " founding: " + o.message());
            hearts.put(k, heart);
        }
        final long[] start = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            for (BlockPos h : hearts.values()) {
                if (Founding.near(level, h, 8)) {
                    if (t % 400 == 0) Kit.log("[VT] " + label + " @" + t + " " + Founding.status(level.getServer()));
                    return;
                }
            }
            if (start[0] < 0) start[0] = t;
            watch(level, label, hearts, t - start[0]);                 // [city26] who touches the levelled ground after it is done
            if (t - start[0] < 40) return;                            // a moment for anything let loose to fall or run
            Kit.Expect expect = new Kit.Expect();
            Map<String, Integer> total = new LinkedHashMap<>();
            for (Map.Entry<String, BlockPos> e : hearts.entrySet()) {
                TerraformAudit.Done d = TerraformAudit.near(e.getValue(), 40);
                expect.that(d != null, e.getKey() + ": the founding finished");
                if (d == null) continue;
                Map<String, Integer> c = TerraformAudit.scan(level, d);
                Kit.log("[VT] " + label + " AUDIT " + e.getKey() + " y=" + d.level() + " " + TerraformAudit.line(c)
                    + (TerraformAudit.FIRST.isEmpty() ? "" : " | first: " + TerraformAudit.FIRST));
                c.forEach((key, v) -> total.merge(key, v, Integer::sum));
                if (TerraformAudit.FIRST.containsKey("offLevel")) {                // [city26]
                    String[] q = TerraformAudit.FIRST.get("offLevel").split("[, ]");
                    BlockPos at = new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
                    StringBuilder tr = new StringBuilder();
                    for (int dy = -2; dy <= 2; dy++) tr.append(' ').append(dy).append(':').append(com.jrpetty.mcassistant.entity.TownBlocks.setBy(at.above(dy)));
                    Kit.log("[VT] " + label + " " + e.getKey() + " who set the first column off its level:" + tr);
                }
                if (STRICT) {
                    for (String key : MUST_BE_NOUGHT) {
                        if (c.getOrDefault(key, 0) != 0) expect.that(false, e.getKey() + " " + key + "=" + c.get(key));
                    }
                }
                BlockPos hut = huts.get(e.getKey());
                if (hut != null) {
                    expect.that(level.getBlockState(hut).is(Blocks.OAK_PLANKS) && level.getBlockState(hut.offset(2, 2, 2)).is(Blocks.OAK_PLANKS),
                        e.getKey() + ": the hut somebody built is untouched");
                }
                expect.that(Villages.nearest(level, e.getValue(), 64) != null, e.getKey() + ": a village stands there");
            }
            if (hearts.size() > 1) Kit.log("[VT] " + label + " AUDIT total " + TerraformAudit.line(total));
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    /** [city26] The top of each worked column of each founding just finished, and anything that changes it in the next
     *  moments logged with whoever was about it and what they were at. */
    private static final Map<Long, net.minecraft.world.level.block.state.BlockState> WATCHED = new HashMap<>();

    static void watch(ServerLevel level, String label, Map<String, BlockPos> hearts, long since) {
        for (BlockPos h : hearts.values()) {
            TerraformAudit.Done d = TerraformAudit.near(h, 40);
            if (d == null || d.ground().target == null) continue;
            FoundingPlan.Ground g = d.ground();
            for (int i = 0; i < g.side * g.side; i++) {
                if (g.terrace == null || g.terrace[i] == com.jrpetty.mcassistant.village.Terraces.NONE) continue;
                BlockPos p = new BlockPos(h.getX() + g.dx(i), g.target[i], h.getZ() + g.dz(i));
                net.minecraft.world.level.block.state.BlockState now = level.getBlockState(p);
                net.minecraft.world.level.block.state.BlockState was = WATCHED.put(p.asLong(), now);
                if (since == 0 || was == null || was == now) continue;
                StringBuilder who = new StringBuilder();
                for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null,
                        new net.minecraft.world.phys.AABB(p).inflate(6), e -> e instanceof net.minecraft.world.entity.Mob)) {
                    who.append(' ').append(e.getName().getString()).append('@').append(e.blockPosition().toShortString());
                    ((net.minecraft.world.entity.Mob) e).goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                        .forEach(gl -> who.append('[').append(gl.getGoal().getClass().getSimpleName()).append(']'));
                    if (e instanceof com.jrpetty.mcassistant.entity.AssistantEntity a) who.append('{').append(a.stationTask()).append('}');
                }
                Kit.log("[VT] " + label + " WATCH +" + since + " " + p.toShortString() + " " + was.getBlock() + " -> " + now.getBlock() + "; about:" + who);
            }
        }
    }

    /** tf01: the meadow, strewn with gravel, ore and andesite at the top, pits, a pond, trees on the edge, a player's hut. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "tf01_meadow")
    public static void tf01_meadow(GameTestHelper helper) {
        audit(helper, "tf01", List.of("meadow"));
    }

    /** tf02: a steep hillside across the edge, a cave through it at the cut's height with water and lava, a cave mouth in the square. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "tf02_hillside")
    public static void tf02_hillside(GameTestHelper helper) {
        audit(helper, "tf02", List.of("hillside"));
    }

    /** tf03: a thick forest, dark oaks among the oaks, vines on them, the edge cutting through their crowns. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "tf03_forest")
    public static void tf03_forest(GameTestHelper helper) {
        audit(helper, "tf03", List.of("forest"));
    }

    /** tf04: a river through, a ravine with an old mine's boards at its foot, a cavern under a thin crust, sand over a hollow. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "tf04_waters")
    public static void tf04_waters(GameTestHelper helper) {
        audit(helper, "tf04", List.of("waters"));
    }

    /** tf05: desert dunes, a cave right under the sand, cacti and a dead bush. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "tf05_desert")
    public static void tf05_desert(GameTestHelper helper) {
        audit(helper, "tf05", List.of("desert"));
    }

    /** tf06: a long snowy slope, snow blocks up it, powder snow, spruces. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "tf06_snowy")
    public static void tf06_snowy(GameTestHelper helper) {
        audit(helper, "tf06", List.of("snowy"));
    }

    /** tf00: every ground at once (-Dtf.grounds to choose), for the audit's numbers; not required, the six above are. */
    @GameTest(template = EMPTY, timeoutTicks = 24000, batch = "tf00_audit", required = false)
    public static void tf00_audit(GameTestHelper helper) {
        List<String> kinds = new ArrayList<>();
        for (String k : ALL) if (("," + GROUNDS + ",").contains("," + k + ",")) kinds.add(k);
        audit(helper, "tf00", kinds);
    }

    /**
     * tf07: /village spawnat founds a village at once for the console (folk there the same tick, as it always was) and
     * levels its ground after, as /village level does: the meadow's pits filled and its trees gone when it is done.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "tf07_spawnat")
    public static void tf07_spawnat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        int cx = X0 + 4000 * ALL.length + 4000, cz = Z;
        int radius = FoundingPlan.coreRadius(FOLK), half = radius + FoundingPlan.BAND_MAX + 14;
        Kit.hold(level, cx, cz, half + 16);
        Kit.prepare(level, cx, cz, half + 16);
        lay(level, "meadow", cx, cz, half);
        List<String> said = Kit.command(level, "village spawnat " + cx + " " + cz + " " + FOLK);
        Villages.Village v = Villages.nearest(level, new BlockPos(cx, 0, cz), 64);
        Kit.log("[VT] tf07 spawnat: " + said + "; village " + (v == null ? "none" : Villages.name(v.id()) + " of " + Villages.headcount(v.id())));
        helper.assertTrue(v != null && Villages.headcount(v.id()) == FOLK, "spawnat founds the village at once, its folk there");
        helper.assertTrue(Founding.near(level, v.centre(), 16), "and its ground is being levelled");
        helper.onEachTick(() -> {
            if (Founding.near(level, v.centre(), 16)) return;
            TerraformAudit.Done d = TerraformAudit.near(v.centre(), 40);
            if (d == null) { helper.fail("the levelling left nothing to audit"); return; }
            Map<String, Integer> c = TerraformAudit.scan(level, d);
            Kit.log("[VT] tf07 AUDIT y=" + d.level() + " " + TerraformAudit.line(c));
            Kit.Expect expect = new Kit.Expect();
            expect.that(c.get("square") > 2000, "the square levelled round the village: " + c.get("square") + " columns");
            for (String key : new String[]{ "offLevel", "holes", "pits", "oddTop", "growthOnSquare", "floatLeaves", "floatLogs" }) {
                expect.that(c.get(key) == 0, key + "=" + c.get(key));
            }
            expect.that(Villages.headcount(v.id()) == FOLK, "nobody new came, nobody lost: " + Villages.headcount(v.id()));
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    /** A pit {@code half} out from its middle and {@code deep} deep, its floor's height returned. */
    private static int pit(ServerLevel level, int x, int z, int half, int deep) {
        int top = Kit.surface(level, x, z).getY() - 1, floor = top - deep;
        for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
            for (int y = floor + 1; y <= top + 16; y++) level.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.AIR.defaultBlockState(), 2);
        }
        return floor;
    }

    /**
     * tf08: never filled over, never dug down to. A player's camp at the foot of a deep pit in the square (a chest, a
     * bed and a door) is left as it is, the pit open over it, and the square levelled to its rim rather than cut down
     * to it; a block of raw copper at the foot of another pit is the world's own, and that pit is filled over it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "tf08_player_pit")
    public static void tf08_player_pit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        int cx = X0 + 4000 * ALL.length + 8000, cz = Z;
        int radius = FoundingPlan.coreRadius(FOLK), half = radius + FoundingPlan.BAND_MAX + 14;
        Kit.hold(level, cx, cz, half + 16);
        Kit.prepare(level, cx, cz, half + 16);
        lay(level, "forest", cx, cz, half);
        int bx = cx + 18, bz = cz - 20, rx = cx - 20, rz = cz + 25;
        int bFloor = pit(level, bx, bz, 3, 14), rFloor = pit(level, rx, rz, 2, 14);
        BlockPos chest = new BlockPos(bx - 2, bFloor + 1, bz), bed = new BlockPos(bx, bFloor + 1, bz + 1), door = new BlockPos(bx + 2, bFloor + 1, bz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(bed, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        level.setBlock(bed.north(), Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState(), 3);
        level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        BlockPos copper = new BlockPos(rx, rFloor + 1, rz);
        level.setBlock(copper, Blocks.RAW_COPPER_BLOCK.defaultBlockState(), 3);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.log("[VT] tf08 the camp's pit floor y=" + bFloor + ", the copper's y=" + rFloor + "; the heart " + heart.toShortString());
        Founding.Outcome o = Founding.foundNow(level, heart, FOLK, 0F, null);
        helper.assertTrue(o.ok(), "founded: " + o.message());
        helper.onEachTick(() -> {
            if (Founding.near(level, heart, 8)) return;
            TerraformAudit.Done d = TerraformAudit.near(heart, 40);
            if (d == null) { helper.fail("the founding left nothing to audit"); return; }
            int lv = d.level();
            Kit.Expect expect = new Kit.Expect();
            expect.that(level.getBlockState(chest).is(Blocks.CHEST) && level.getBlockState(bed).is(Blocks.RED_BED)
                && level.getBlockState(bed.north()).is(Blocks.RED_BED) && level.getBlockState(door).is(Blocks.OAK_DOOR)
                && level.getBlockState(door.above()).is(Blocks.OAK_DOOR), "the camp at the foot of the pit is untouched");
            int open = 0;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                for (int y = bFloor + 3; y <= lv; y++) if (level.getBlockState(new BlockPos(bx + dx, y, bz + dz)).isAir()) open++;
            }
            int cells = 49 * (lv - bFloor - 2);
            Kit.log("[VT] tf08 the camp's pit: " + open + " of " + cells + " cells over it open; the level y=" + lv);
            expect.that(open == cells, "the pit over the camp is left open: " + open + " of " + cells);
            int dug = 0;
            String where = "";
            for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 5) continue;   // the pit and a ring round it
                int x = bx + dx, z = bz + dz;
                if (!FoundingPlan.levelled(x - heart.getX(), z - heart.getZ(), d.radius(), d.seed())) continue;
                int y = com.jrpetty.mcassistant.entity.Terraform.groundY(level, x, z);
                if (y != lv && dug++ == 0) where = x + "," + z + " at y=" + y;
            }
            expect.that(dug == 0, "the square is level to the pit's rim, not dug down to it: " + dug + " off (" + where + ")");
            int cy = com.jrpetty.mcassistant.entity.Terraform.groundY(level, rx, rz);
            Kit.log("[VT] tf08 the copper's pit now at y=" + cy);
            expect.that(cy == lv, "the pit with the world's raw copper in it is filled to the level: y=" + cy);
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }
}
