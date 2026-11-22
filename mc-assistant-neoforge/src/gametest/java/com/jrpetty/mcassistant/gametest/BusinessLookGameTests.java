package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.BuildingAudit;
import com.jrpetty.mcassistant.entity.Interiors;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [biz30] Every business, every civic and work building and the homes, each put up by a town's builder through its own
 * goal (BuildGoal.buildNowForTests: its plan, its ground work, each block out of its pack) on flat meadow, on a hillside
 * and in a wood, every lot strewn with grass, flowers, snow, saplings and the wood's trees standing where the walls go;
 * then furnished and dressed as the town furnishes and dresses them (Interiors, TownLife.dressNow), and looked over
 * (BuildingAudit): no grass, earth or plant on a floor or in a room, no hole in a floor, nothing afloat or stray, every
 * door hung and its way out clear, no workstation or chest laid as a wall's block, no roof left open, no scaffolding.
 * Then the businesses, each kind from its start to its sale (bl1x).
 *
 * <p>x 6,400,000 to 6,600,000, z 64000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BusinessLookGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 64000;
    /** Whether the bar is enforced (-Dbl.strict). */
    private static final boolean STRICT = Boolean.parseBoolean(System.getProperty("bl.strict", "true"));
    /** Only these drawings (-Dbl.only, a comma list), for a quick look. */
    private static final String ONLY = System.getProperty("bl.only", "");

    /** What the town is not asked to look at here: the walls and rings, a bare platform, and the world's wonders. */
    private static final java.util.Set<String> SKIP = java.util.Set.of("wall", "platform", "room", "pen", "fortify", "column",
        "greatforge", "skygarden", "clockworkgate", "colossus", "grandbazaar", "arena", "grandlibrary", "cathedral",
        "greatlighthouse", "observatory");

    enum Ground { FLAT, HILLSIDE, FOREST }

    /** Every building the town raises that has a drawing, in name order. */
    static List<String> buildings() {
        List<String> out = new ArrayList<>();
        for (String s : new java.util.TreeSet<>(BuildGoal.STRUCTURES)) {
            if (SKIP.contains(s) || !Blueprints.has(s)) continue;
            if (!ONLY.isEmpty() && !java.util.Arrays.asList(ONLY.split(",")).contains(s)) continue;
            out.add(s);
        }
        return out;
    }

    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "bl01_every_building_flat")
    public static void bl01_every_building_flat(GameTestHelper helper) {
        everyBuilding(helper, Ground.FLAT, 6400000, "bl01");
    }

    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "bl02_every_building_hillside")
    public static void bl02_every_building_hillside(GameTestHelper helper) {
        everyBuilding(helper, Ground.HILLSIDE, 6440000, "bl02");
    }

    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "bl03_every_building_forest")
    public static void bl03_every_building_forest(GameTestHelper helper) {
        everyBuilding(helper, Ground.FOREST, 6480000, "bl03");
    }

    /** A lot for each building, in rows; built one a tick; then furnished, dressed and looked over. */
    static void everyBuilding(GameTestHelper helper, Ground ground, int x0, String tag) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Interiors.resetForTests();
        level.setDayTime(18000);                                       // night: a room lit only by its own lamps
        List<String> names = buildings();
        // The lots: in rows of eight, each as wide as its building and its reach.
        List<BlockPos> lots = new ArrayList<>();
        int col = 0, rowZ = Z, x = x0, rowDepth = 0;
        for (String n : names) {
            int[] f = Blueprints.fullHalf(n);
            int r = Math.max(f[0], f[1]) + 9;
            if (col == 8) { col = 0; x = x0; rowZ += rowDepth + 4; rowDepth = 0; }
            lots.add(new BlockPos(x + r, 0, rowZ + r));
            x += 2 * r + 4;
            rowDepth = Math.max(rowDepth, 2 * r);
            col++;
        }
        int maxX = x0, maxZ = Z;
        for (BlockPos l : lots) { maxX = Math.max(maxX, l.getX() + 40); maxZ = Math.max(maxZ, l.getZ() + 40); }
        int cx = (x0 + maxX) / 2, cz = (Z + maxZ) / 2, half = Math.max(maxX - x0, maxZ - Z) / 2 + 8;
        Kit.hold(level, cx, cz, half);
        Kit.prepare(level, cx, cz, half);
        // The town: a village out to the west, past its lots.
        BlockPos heart = Kit.surface(level, x0 - 220, cz);
        Kit.hold(level, heart.getX(), heart.getZ(), 24);
        Kit.prepare(level, heart.getX(), heart.getZ(), 24);
        VillageFolkEntity hand = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hand != null, "a village");
        UUID id = hand.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        int base = Kit.surface(level, x0, Z).getY();
        List<Ledger.Building> built = new ArrayList<>();
        Map<String, String> said = new TreeMap<>();
        int[] next = { 0 };
        long[] doneAt = { -1 };
        helper.onEachTick(() -> {
            if (doneAt[0] == -2) return;
            if (next[0] < names.size()) {
                int i = next[0]++;
                String n = names.get(i);
                BlockPos lot = lots.get(i);
                Direction facing = i % 2 == 0 ? Direction.NORTH : Direction.SOUTH;
                try {
                    shape(level, ground, lot, n, base, 500 + i);
                    BlockPos anchor = BuildAuditGameTests.siteOn(level, n, lot.getX(), lot.getZ(), facing);
                    street(level, n, anchor, facing);
                    String words = BuildGoal.buildNowForTests(hand, n, anchor, facing, BuildWorldGameTests::stock);
                    if (!words.startsWith("Done")) said.put(n, words);
                    Ledger.built(id, n, anchor, facing);
                    built.add(new Ledger.Building(n, anchor, facing));
                } catch (RuntimeException e) {
                    said.put(n, "threw " + e);
                    Kit.LOG.error("[VT] " + tag + " " + n, e);
                }
                return;
            }
            if (doneAt[0] == -1) {
                // Furnished and dressed as the town does it, out of its stores.
                ItemStack[] stock = { new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.YELLOW_CARPET, 64),
                    new ItemStack(Items.BARREL, 64), new ItemStack(Items.BOOKSHELF, 64), new ItemStack(Items.LANTERN, 64),
                    new ItemStack(Items.CANDLE, 48), new ItemStack(Items.FLOWER_POT, 48), new ItemStack(Items.POPPY, 32),
                    new ItemStack(Items.DANDELION, 32), new ItemStack(Items.OAK_SIGN, 64), new ItemStack(Items.TORCH, 64) };
                for (ItemStack s : stock) com.jrpetty.mcassistant.entity.Market.intoStoresForTests(level, id, s);
                com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
                try {
                    for (int k = 0; k < 20; k++) if (Interiors.work(level, v, 4000) == 0) break;
                } finally {
                    com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
                }
                com.jrpetty.mcassistant.entity.TownLife.dressNow(level, id, heart, built, List.of(Items.BREAD, Items.APPLE, Items.CARROT));
                doneAt[0] = helper.getTick();
                return;
            }
            if (helper.getTick() - doneAt[0] < 60) return;              // the light worked through
            doneAt[0] = -2;
            BuildingAudit.Report total = new BuildingAudit.Report();
            for (Ledger.Building b : built) {
                BuildingAudit.building(level, v, b.structure(), b.structure(), b.anchor(), b.facing(), total);
            }
            Kit.log("[VT] " + tag + " " + ground + " AUDIT " + total.line());
            for (Map.Entry<String, Map<String, Integer>> e : total.each.entrySet()) Kit.log("[VT] " + tag + "   " + e.getKey() + " " + e.getValue());
            for (Map.Entry<String, List<String>> e : total.examples.entrySet()) for (String s : e.getValue()) Kit.log("[VT] " + tag + "   " + e.getKey() + ": " + s);
            if (!said.isEmpty()) Kit.log("[VT] " + tag + " not finished: " + said);
            Kit.Expect expect = new Kit.Expect();
            expect.that(total.buildings == names.size(), "every building looked at (" + total.buildings + " of " + names.size() + ")");
            if (STRICT) {
                for (String k : BuildingAudit.BAR) expect.that(total.count(k) == 0, k + " = " + total.count(k) + " " + total.examples.getOrDefault(k, List.of()));
                expect.that(said.isEmpty(), "every building finished: " + said);
            }
            if (expect.clean()) helper.succeed(); else helper.fail(expect.summary());
        });
    }

    // ------------------------------------------------------------------ the ground

    /** A lot's ground, as the world makes it on this ground, strewn with what grows. */
    static void shape(ServerLevel level, Ground g, BlockPos lot, String name, int base, long seed) {
        int[] f = Blueprints.fullHalf(name);
        int r = Math.max(f[0], f[1]) + 8;
        int top = 0;
        for (Blueprints.Cell c : Blueprints.cells(name)) top = Math.max(top, c.h());
        RandomSource rnd = RandomSource.create(seed);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int h = switch (g) {
                    case FLAT, FOREST -> 0;
                    case HILLSIDE -> (dz + r) / 2 + (Math.floorMod(dx * 7 + dz * 3, 11) == 0 ? 1 : 0);   // a block up every two to the south
                };
                int x = lot.getX() + dx, z = lot.getZ() + dz;
                for (int y = base - 4; y <= base + h + top + 14; y++) {
                    BlockState st;
                    if (y < base + h - 3) st = Blocks.STONE.defaultBlockState();
                    else if (y < base + h - 1) st = Blocks.DIRT.defaultBlockState();
                    else if (y == base + h - 1) st = Blocks.GRASS_BLOCK.defaultBlockState();
                    else st = Blocks.AIR.defaultBlockState();
                    level.setBlock(new BlockPos(x, y, z), st, 2 | 16);
                }
            }
        }
        // What grows on it.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos t = Kit.surface(level, lot.getX() + dx, lot.getZ() + dz);
                if (!level.getBlockState(t.below()).is(Blocks.GRASS_BLOCK)) continue;
                double roll = rnd.nextDouble();
                BlockState st = null;
                if (roll < 0.14) st = Blocks.SHORT_GRASS.defaultBlockState();
                else if (roll < 0.17) st = rnd.nextBoolean() ? Blocks.POPPY.defaultBlockState() : Blocks.DANDELION.defaultBlockState();
                else if (roll < 0.19) st = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + rnd.nextInt(2));
                else if (roll < 0.20) st = Blocks.OAK_SAPLING.defaultBlockState();
                else if (roll < 0.21) st = Blocks.BROWN_MUSHROOM.defaultBlockState();
                else if (roll < 0.25) {
                    DoublePlantBlock.placeAt(level, Blocks.TALL_GRASS.defaultBlockState(), t, 2 | 16);
                    continue;
                }
                if (st != null) level.setBlock(t, st, 2 | 16);
            }
        }
        if (g == Ground.FOREST) {
            // The wood: a tree every few blocks, inside the walls and round them, their crowns over the lot.
            for (int dx = -r + 2; dx <= r - 2; dx += 5) {
                for (int dz = -r + 2; dz <= r - 2; dz += 5) {
                    int tx = lot.getX() + dx + rnd.nextInt(3) - 1, tz = lot.getZ() + dz + rnd.nextInt(3) - 1;
                    tree(level, tx, tz, 4 + rnd.nextInt(3), rnd.nextBoolean() ? Blocks.OAK_LOG : Blocks.BIRCH_LOG,
                        rnd.nextBoolean() ? Blocks.OAK_LEAVES : Blocks.BIRCH_LEAVES);
                }
            }
        }
    }

    /** A wild tree, its leaves not persistent. */
    static void tree(ServerLevel level, int x, int z, int height, Block log, Block leaves) {
        BlockPos b = Kit.surface(level, x, z);
        if (!level.getBlockState(b).canBeReplaced()) return;
        level.setBlock(b, Blocks.AIR.defaultBlockState(), 2 | 16);
        for (int i = 0; i < height; i++) level.setBlock(b.above(i), log.defaultBlockState(), 2 | 16);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = height - 2; dy <= height; dy++) {
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
            BlockPos p = b.offset(dx, dy, dz);
            if (level.getBlockState(p).isAir()) level.setBlock(p, leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false), 2 | 16);
        }
    }

    /** The street before the building's front: a row of path along it, a few out from its door, as the town plan lays one. */
    static void street(ServerLevel level, String name, BlockPos anchor, Direction facing) {
        int[] f = Blueprints.fullHalf(name);
        Direction front = facing.getOpposite();
        Direction right = facing.getClockWise();
        int depth = (facing.getAxis() == Direction.Axis.Z ? f[1] : f[1]) + 3;
        for (int s = -f[0] - 4; s <= f[0] + 4; s++) {
            BlockPos c = anchor.relative(front, depth).relative(right, s);
            int y = BuildGoal.groundTop(level, c.getX(), c.getZ());
            BlockPos g = new BlockPos(c.getX(), y - 1, c.getZ());
            if (Math.abs(y - anchor.getY()) > 6) continue;
            level.setBlock(g, Blocks.DIRT_PATH.defaultBlockState(), 2 | 16);
            BlockState over = level.getBlockState(g.above());
            if (over.canBeReplaced()) level.setBlock(g.above(), Blocks.AIR.defaultBlockState(), 2 | 16);
        }
    }
}
