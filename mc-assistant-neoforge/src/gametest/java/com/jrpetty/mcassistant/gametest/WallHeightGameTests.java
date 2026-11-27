package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [wallheight] The town wall keeps to the ground (CastleWall: ground, shape, dips, trims): a sky build over its line
 * never lifts it, a cliff ends it, a ravine is bridged on arches at the walkway's level, an old save's wall raised
 * over the caps comes down into the stores; and nowhere does a block of it stand more than ABOVE_GROUND_MOST over its
 * natural ground or ABOVE_HEART_MOST over the heart (overCaps, asserted in the DefenceGameTests too).
 *
 * <p>Each test has its own batch and its own ground: x 2060000 to 2079999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WallHeightGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** The caps the wall is held to (CastleWall.ABOVE_GROUND_MOST, ABOVE_HEART_MOST). */
    private static final int ABOVE_GROUND_MOST = 12, ABOVE_HEART_MOST = 24;
    /** The kinds of a wall column the tests look for (CastleWall: SPAN, CLIFF). */
    private static final int SPAN = 5, CLIFF = 6;
    /** The Stone Age's curtain over its ground. */
    private static final int STONE_HEIGHT = 5;

    // ------------------------------------------------------------------ the caps, for every wall test

    /**
     * Every column of the wall (its line, its towers, its jogs) where a block of it stands more than ABOVE_GROUND_MOST
     * over the natural ground under it or more than ABOVE_HEART_MOST over the heart; a span over a dip and water are
     * measured by their own tests; the blocks in {@code others} are not the wall's (a sky build of the same stone).
     * Empty when the wall keeps to the ground everywhere.
     */
    static List<String> overCaps(ServerLevel level, UUID village, BlockPos heart, Set<BlockPos> others) {
        WallPlan.Sides s = CastleWall.sides(village);
        List<WallPlan.Tower> towers = WallPlan.towers(s);
        Set<Long> seen = new HashSet<>();
        List<int[]> cols = new ArrayList<>();
        for (int[] c : WallPlan.columns(s, towers)) cols.add(new int[]{ heart.getX() + c[0], heart.getZ() + c[1] });
        for (int[] e : CastleWall.ringForTests(village)) for (int u = 0; u < WallPlan.THICK; u++) cols.add(new int[]{ e[2 * u], e[2 * u + 1] });
        List<String> out = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int[] c : cols) {
            if (!seen.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL))) continue;
            WallPlan.Cell cell = WallPlan.cell(s, towers, c[0] - heart.getX(), c[1] - heart.getZ());
            if (cell != null && CastleWall.kindForTests(village, cell.side(), cell.across()) == SPAN) continue;
            int high = Integer.MIN_VALUE;
            for (int y = heart.getY() + 80; y >= heart.getY() - 40 && high == Integer.MIN_VALUE; y--) {
                if (CastleWall.wallBlockForTests(level.getBlockState(p.set(c[0], y, c[1]))) && !others.contains(p)) high = y;
            }
            if (high == Integer.MIN_VALUE) continue;
            int ground = CastleWall.naturalForTests(level, c[0], c[1], heart.getY());
            if (ground == Integer.MIN_VALUE) continue;                                   // over water: piers and arches
            if (high - ground > ABOVE_GROUND_MOST || high - heart.getY() > ABOVE_HEART_MOST) {
                out.add((c[0] - heart.getX()) + "," + (c[1] - heart.getZ()) + " up to " + (high - ground) + " over its ground, "
                    + (high - heart.getY()) + " over the heart");
            }
        }
        return out;
    }

    /** Assert the caps, naming the first few columns over them. */
    static void assertCaps(GameTestHelper helper, ServerLevel level, UUID village, BlockPos heart, String test) {
        assertCaps(helper, level, village, heart, test, Set.of());
    }

    static void assertCaps(GameTestHelper helper, ServerLevel level, UUID village, BlockPos heart, String test, Set<BlockPos> others) {
        List<String> over = overCaps(level, village, heart, others);
        Kit.log(test + " columns over the caps: " + over.size() + " " + over.subList(0, Math.min(6, over.size())));
        helper.assertTrue(over.isEmpty(), "no block of the wall more than " + ABOVE_GROUND_MOST + " over its natural ground or "
            + ABOVE_HEART_MOST + " over the heart: " + over.size() + " columns, the first " + over.subList(0, Math.min(4, over.size())));
    }

    // ------------------------------------------------------------------ helpers

    /** Level grass over earth on stone, {@code lift} over the world's surface (room to dig a ravine under it), everything
     *  over it cleared, this far round. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r, int lift) {
        Kit.hold(level, cx, cz, r + 12);
        Kit.prepare(level, cx, cz, r + 12);
        int y0 = Kit.surface(level, cx, cz).getY(), y = y0 + lift;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int yy = Math.max(level.getMinBuildHeight() + 1, Math.min(y0, y) - 4); yy < y; yy++) {
                    int dy = yy - y;
                    BlockState b = dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : dy >= -4 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    level.setBlock(p.set(x, yy, z), b, 2 | 16);
                }
                for (int dy = 0; dy < 32; dy++) {
                    if (!level.getBlockState(p.set(x, y + dy, z)).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A Stone Age town at the heart, its first block of houses on the books, its wall ready to be built. */
    private static Villages.Village town(GameTestHelper helper, ServerLevel level, BlockPos heart) {
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null, "a town");
        UUID village = a.ownerId();
        Villages.ageForTests(village, Villages.Age.STONE);
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        DefenceGameTests.built(village, heart, DefenceGameTests.firstBlock());
        return Villages.get(village);
    }

    private static void box(ServerLevel level, BlockPos from, BlockPos to, BlockState b) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) level.setBlock(p, b, 2 | 16);
    }

    // ------------------------------------------------------------------ wh01: a sky build over the line

    /**
     * A town with builds floating high over its north line: a showcase house on a slab of smooth stone thirty over
     * the ground, and a thick island of earth forty over it. The wall runs on under both at its own height (five over
     * the ground in the Stone Age), never touching them, and keeps within the caps everywhere.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wh01_sky_build")
    public static void wh01_sky_build(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        BlockPos heart = flat(level, 2060000, Z, 84, 0);
        Villages.Village v = town(helper, level, heart);
        UUID village = v.id();
        int line = WallPlan.first().of(TownPlan.NORTH);
        // The showcase: a slab of smooth stone, a little plank house on it, over the line west of the gate.
        BlockPos slab = heart.offset(-26, 30, -line - 6);
        box(level, slab, slab.offset(10, 0, 10), Blocks.SMOOTH_STONE.defaultBlockState());
        box(level, slab.offset(3, 1, 3), slab.offset(7, 3, 7), Blocks.OAK_PLANKS.defaultBlockState());
        // The island: three of earth under grass, over the line east of the gate.
        BlockPos island = heart.offset(12, 40, -line - 6);
        box(level, island, island.offset(10, 1, 10), Blocks.DIRT.defaultBlockState());
        box(level, island.offset(0, 2, 0), island.offset(10, 2, 10), Blocks.GRASS_BLOCK.defaultBlockState());
        Map<BlockPos, BlockState> sky = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(slab, slab.offset(10, 3, 10))) sky.put(p.immutable(), level.getBlockState(p));
        for (BlockPos p : BlockPos.betweenClosed(island, island.offset(10, 2, 10))) sky.put(p.immutable(), level.getBlockState(p));
        helper.assertTrue(WallPlan.first().equals(CastleWall.plannedForTests(level, v)), "the first wall planned");
        CastleWall.buildNow(level, v, true);
        Kit.log("wh01 " + String.join(" | ", CastleWall.lines(level, v)));
        for (int a : new int[]{ -21, -16, 15, 18 }) {
            int top = CastleWall.topForTests(village, TownPlan.NORTH, a);
            helper.assertTrue(top == heart.getY() + STONE_HEIGHT, "the walkway at its own height under the sky build at " + a + ": " + (top - heart.getY()));
            BlockState body = level.getBlockState(heart.offset(a, 2, -line - 1));
            helper.assertTrue(CastleWall.wallBlockForTests(body), "the wall stands under the sky build at " + a + ": " + body);
        }
        int touched = 0;
        for (Map.Entry<BlockPos, BlockState> e : sky.entrySet()) if (!level.getBlockState(e.getKey()).equals(e.getValue())) touched++;
        helper.assertTrue(touched == 0, "the sky builds untouched: " + touched + " blocks changed");
        helper.assertTrue(!String.join(" ", CastleWall.lines(level, v)).contains("round an unknown build"), "nothing gone round: the builds are out of the wall's reach");
        assertCaps(helper, level, village, heart, "wh01", sky.keySet());
        CastleWall.standDownForTests(true);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wh02: a cliff on the line

    /**
     * A town whose east line runs into a cliff thirty high: the wall ends against the cliff at its own height (no
     * ramp up to it), nothing of it on the cliff top, and goes on beyond; the town's book says the rise is the barrier.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wh02_cliff")
    public static void wh02_cliff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        BlockPos heart = flat(level, 2064000, Z, 84, 0);
        Villages.Village v = town(helper, level, heart);
        UUID village = v.id();
        int line = WallPlan.first().of(TownPlan.EAST);
        // The cliff: stone thirty high under grass, across the east line from 12 to 28 south of the heart.
        box(level, heart.offset(line - 8, 0, 12), heart.offset(line + 20, 29, 28), Blocks.STONE.defaultBlockState());
        box(level, heart.offset(line - 8, 30, 12), heart.offset(line + 20, 30, 28), Blocks.GRASS_BLOCK.defaultBlockState());
        CastleWall.plannedForTests(level, v);
        CastleWall.buildNow(level, v, true);
        String said = String.join(" | ", CastleWall.lines(level, v));
        Kit.log("wh02 " + said);
        helper.assertTrue(CastleWall.kindForTests(village, TownPlan.EAST, 20) == CLIFF, "the cliff left as the barrier: " + CastleWall.kindForTests(village, TownPlan.EAST, 20));
        helper.assertTrue(said.contains("Against high ground"), "the book says the wall ends against the rise");
        for (int a : new int[]{ 6, 9, 11 }) {
            int top = CastleWall.topForTests(village, TownPlan.EAST, a);
            helper.assertTrue(top == heart.getY() + STONE_HEIGHT, "the walkway at its own height up to the cliff at " + a + ": " + (top - heart.getY()));
        }
        int onTop = 0;
        for (int dz = 12; dz <= 28; dz++) {
            for (int u = 0; u < WallPlan.THICK; u++) {
                for (int dy = 31; dy <= 50; dy++) if (CastleWall.wallBlockForTests(level.getBlockState(heart.offset(line + u, dy, dz)))) onTop++;
            }
        }
        helper.assertTrue(onTop == 0, "nothing of the wall climbs onto the cliff: " + onTop);
        helper.assertTrue(CastleWall.wallBlockForTests(level.getBlockState(heart.offset(line + 1, 2, 9))), "the wall stands up to the cliff");
        assertCaps(helper, level, village, heart, "wh02");
        CastleWall.standDownForTests(true);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wh03: a ravine on the line

    /**
     * A ravine fourteen deep and eight wide across the south line: the wall bridges it at the walkway's level, the
     * deck on arches with a pier every few columns standing on the ravine's floor (none below it, none between the
     * piers under the arches); the ravine still open under it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wh03_ravine")
    public static void wh03_ravine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        BlockPos heart = flat(level, 2068000, Z, 84, 20);
        Villages.Village v = town(helper, level, heart);
        UUID village = v.id();
        int line = WallPlan.first().of(TownPlan.SOUTH), depth = 14, west = 10, east = 17;
        box(level, heart.offset(west, -depth, line - 12), heart.offset(east, -1, line + 16), Blocks.AIR.defaultBlockState());
        box(level, heart.offset(west, -depth - 2, line - 12), heart.offset(east, -depth - 1, line + 16), Blocks.STONE.defaultBlockState());
        CastleWall.plannedForTests(level, v);
        CastleWall.buildNow(level, v, true);
        Kit.log("wh03 " + String.join(" | ", CastleWall.lines(level, v)));
        int floor = heart.getY() - depth;
        List<String> wrong = new ArrayList<>();
        int piers = 0;
        for (int a = west; a <= east; a++) {
            int kind = CastleWall.kindForTests(village, TownPlan.SOUTH, a), top = CastleWall.topForTests(village, TownPlan.SOUTH, a);
            if (kind != SPAN) wrong.add(a + " kind " + kind);
            if (top != heart.getY() + STONE_HEIGHT) wrong.add(a + " walkway " + (top - heart.getY()));
            for (int u = 0; u < WallPlan.THICK; u++) {
                BlockPos col = heart.offset(a, 0, line + u);
                if (!CastleWall.wallBlockForTests(level.getBlockState(col.atY(top - 2)))) wrong.add(a + "," + u + " no deck");
                int lowest = Integer.MAX_VALUE;
                for (int y = top - 2; y >= floor - 4; y--) if (CastleWall.wallBlockForTests(level.getBlockState(col.atY(y)))) lowest = y;
                boolean pier = Math.floorMod(a, 4) == 0;
                if (pier && u == 1) {
                    piers++;
                    if (lowest != floor) wrong.add(a + " pier from " + (lowest - heart.getY()) + ", not the floor");
                }
                if (!pier && lowest < top - 2 - 2) wrong.add(a + "," + u + " stone under the arch down to " + (lowest - heart.getY()));
            }
            if (!level.getBlockState(heart.offset(a, -depth + 2, line - 4)).isAir()) wrong.add(a + " the ravine filled inside");
        }
        helper.assertTrue(wrong.isEmpty(), "the ravine bridged on arches at the walkway's level: " + wrong.size() + " wrong, the first "
            + wrong.subList(0, Math.min(6, wrong.size())));
        helper.assertTrue(piers >= 2, "piers on the ravine's floor: " + piers);
        assertCaps(helper, level, village, heart, "wh03");
        CastleWall.standDownForTests(true);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wh04: an old save's wall over the caps

    /**
     * An old save's wall, fitted before the caps and built thirty higher over part of its north side: fitted again
     * (its new key), the stone over the caps comes down into the town's stores, and the wall keeps within them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wh04_old_save")
    public static void wh04_old_save(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        BlockPos heart = flat(level, 2072000, Z, 84, 0);
        Villages.Village v = town(helper, level, heart);
        UUID village = v.id();
        BlockPos chest = heart.offset(4, 0, 4);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        CastleWall.plannedForTests(level, v);
        CastleWall.buildNow(level, v, true);
        CastleWall.uncapForTests(level, v, TownPlan.NORTH, -24, -14, 30);
        helper.assertTrue(!overCaps(level, village, heart, Set.of()).isEmpty(), "the old wall stands over the caps to begin with");
        int before = com.jrpetty.mcassistant.entity.Market.stock(level, village, st -> st.is(Items.COBBLESTONE) || st.is(Items.MOSSY_COBBLESTONE));
        CastleWall.buildNow(level, v, false);
        int after = com.jrpetty.mcassistant.entity.Market.stock(level, village, st -> st.is(Items.COBBLESTONE) || st.is(Items.MOSSY_COBBLESTONE));
        Kit.log("wh04 stores' cobblestone " + before + " -> " + after + " | " + String.join(" | ", CastleWall.lines(level, v)));
        assertCaps(helper, level, village, heart, "wh04");
        helper.assertTrue(after - before >= 3 * 11 * 25, "the stone taken down went into the stores: " + (after - before));
        int top = CastleWall.topForTests(village, TownPlan.NORTH, -20);
        helper.assertTrue(top == heart.getY() + STONE_HEIGHT, "the walkway back at its height: " + (top - heart.getY()));
        CastleWall.standDownForTests(true);
        helper.succeed();
    }
}
