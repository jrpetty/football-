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

    // ================================================================== every kind of business, from its start to its close

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl10_toolsmith_start_to_close")
    public static void bl10_toolsmith_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "toolsmith", 6520000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl11_armourer_start_to_close")
    public static void bl11_armourer_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "armourer", 6522500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl12_carpenter_start_to_close")
    public static void bl12_carpenter_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "carpenter", 6525000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl13_clothier_start_to_close")
    public static void bl13_clothier_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "clothier", 6527500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl14_weaver_start_to_close")
    public static void bl14_weaver_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "weaver", 6530000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl15_chandler_start_to_close")
    public static void bl15_chandler_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "chandler", 6532500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl16_potter_start_to_close")
    public static void bl16_potter_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "potter", 6535000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl17_glassworks_start_to_close")
    public static void bl17_glassworks_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "glassworks", 6537500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl18_baker_start_to_close")
    public static void bl18_baker_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "baker", 6540000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl19_butcher_start_to_close")
    public static void bl19_butcher_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "butcher", 6542500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl20_fishmonger_start_to_close")
    public static void bl20_fishmonger_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "fishmonger", 6545000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl21_cafe_start_to_close")
    public static void bl21_cafe_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "cafe", 6547500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl22_tavern_start_to_close")
    public static void bl22_tavern_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "tavern", 6550000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl23_inn_start_to_close")
    public static void bl23_inn_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "inn", 6552500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl24_apothecary_start_to_close")
    public static void bl24_apothecary_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "apothecary", 6555000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl25_greengrocer_start_to_close")
    public static void bl25_greengrocer_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "greengrocer", 6557500);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl26_generalstore_start_to_close")
    public static void bl26_generalstore_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "generalstore", 6560000);
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl27_stationer_start_to_close")
    public static void bl27_stationer_start_to_close(GameTestHelper helper) {
        firmEndToEnd(helper, "stationer", 6562500);
    }

    /** What a town's stores hold for its firms to make their wares of. */
    static final net.minecraft.world.item.Item[] MAKINGS = { Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.STICK, Items.IRON_INGOT, Items.LEATHER,
        Items.WHITE_WOOL, Items.STRING, Items.HONEYCOMB, Items.CLAY_BALL, Items.CLAY, Items.SAND, Items.GLASS, Items.WHEAT, Items.SUGAR, Items.EGG,
        Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.COD, Items.SALMON, Items.POTATO, Items.BROWN_MUSHROOM, Items.RED_MUSHROOM,
        Items.BEETROOT, Items.COCOA_BEANS, Items.PAPER, Items.SUGAR_CANE, Items.FEATHER, Items.INK_SAC, Items.GLASS_BOTTLE, Items.NETHER_WART,
        Items.MELON_SLICE, Items.GOLD_NUGGET, Items.BOWL, Items.PUMPKIN, Items.MILK_BUCKET, Items.COAL, Items.CHARCOAL, Items.HONEY_BOTTLE,
        Items.APPLE, Items.CARROT, Items.SWEET_BERRIES, Items.LEATHER, Items.BOOK, Items.RABBIT, Items.BRICK, Items.FLINT, Items.TORCH,
        Items.LANTERN, Items.BUCKET, Items.SHEARS, Items.FLINT_AND_STEEL, Items.FISHING_ROD, Items.WATER_BUCKET, Items.BLAZE_POWDER };

    /**
     * One kind of business, from the U page to its winding up, as a player runs it: the Start a business flow for the kind
     * (a hello to a folk first, as a newcomer), its premises the town's to let, the charter; a hand taken on from its Hands
     * tab; coin put in from Money; its stock (ordered in from the market for a shop that buys in, made at its bench by its
     * hand for a maker); a folk of the town buying at the counter, the sale in its till, its stock and its books; the books'
     * sums adding up; the next morning its rent and running costs, and at payday its hand paid out of its till; then wound
     * up from its last tab, its till to its owner. Every step says what it did, and a step that cannot be done says why.
     */
    static void firmEndToEnd(GameTestHelper helper, String kind, int x) {
        ServerLevel level = helper.getLevel();
        com.jrpetty.mcassistant.entity.BusinessTypes.BusinessType t = com.jrpetty.mcassistant.entity.BusinessTypes.of(kind);
        helper.assertTrue(t != null, "a kind " + kind);
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.MarketMaker.liveForTests(true);
        com.jrpetty.mcassistant.entity.Enterprise.resetForTests();
        com.jrpetty.mcassistant.entity.Enterprise.autoForTests(false);
        com.jrpetty.mcassistant.entity.PlayerShops.resetForTests();
        level.setDayTime(8 * 24000L + 7000L);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos ground = Kit.surface(level, x, Z);
        int y = ground.getY();
        for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
            level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            for (int h = 0; h <= 8; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
        }
        int n = Math.max(12, t.minFolk() + 3);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 10 + (i % 8) * 2, y, Z - 8 + (i / 8) * 2), 0.0F, 200);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(i < 2 ? t.work() : com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM);
            if (i < 2) Kit.log("[VT] " + kind + " set to " + t.work() + ": " + f.stationTask());
            f.spend(f.purse());
            f.earn(80);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Ledger.addCoins(id, 500);
        com.jrpetty.mcassistant.entity.MarketMaker.openForTests(level, v);
        BlockPos chestAt = new BlockPos(x + 8, y, Z + 8);
        level.setBlockAndUpdate(chestAt, Blocks.CHEST.defaultBlockState());
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chestAt);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chestAt);
        for (int i = 0; i < MAKINGS.length && i < box.getContainerSize(); i++) box.setItem(i, new ItemStack(MAKINGS[i], MAKINGS[i].getDefaultMaxStackSize()));
        box.setChanged();
        BlockPos chest2 = chestAt.east(2);
        level.setBlockAndUpdate(chest2, Blocks.CHEST.defaultBlockState());
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest2);
        net.minecraft.world.Container box2 = (net.minecraft.world.Container) level.getBlockEntity(chest2);
        for (int i = 27; i < MAKINGS.length && i - 27 < box2.getContainerSize(); i++) box2.setItem(i - 27, new ItemStack(MAKINGS[i], MAKINGS[i].getDefaultMaxStackSize()));
        box2.setChanged();
        // The trade's own building, as the town has one by the time a business of it opens: its bench, its furnace, its anvil.
        String works = VillageFolkEntity.buildingForTests(t.work());
        if (works != null && Blueprints.has(works)) {
            BlockPos wa = new BlockPos(x - 14, y, Z + 14);
            BuildGoal.stamp(level, works, wa, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, works, wa, Direction.NORTH);
        }
        Villages.forgetStores(id);
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(folk.get(0).getX() + 1, y, folk.get(0).getZ());
        p.getInventory().clearContent();
        p.getInventory().setItem(0, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 90));
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            List<String> said = new ArrayList<>();
            // 1. Started from the U page.
            com.jrpetty.mcassistant.entity.PlayerLife.open(p, "life", null);
            com.jrpetty.mcassistant.entity.PlayerLife.open(p, "start", argsOf("kind", kind));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "hello", new net.minecraft.nbt.CompoundTag());
            said.add("hello: " + msg(p));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "step", argsOf("s", 2));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "prem", argsOf("p", "town"));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "step", argsOf("s", 3));
            String confirmPage = com.jrpetty.mcassistant.entity.PlayerLife.pageForTests(p).toString();
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "confirm", new net.minecraft.nbt.CompoundTag());
            String chartered = msg(p);
            said.add("confirm: " + chartered);
            com.jrpetty.mcassistant.entity.Business b = com.jrpetty.mcassistant.entity.Businesses.of(id, com.jrpetty.mcassistant.entity.PlayerLife.ctx(p).str("firm"));
            ok.that(b != null && b.ownedBy(p.getUUID()), "1. chartered from the U page: " + chartered + " (step 3 " + (confirmPage.contains("To pay now") ? "says what is paid now" : "?") + ")");
            if (b == null) { Kit.log("[VT] " + kind + " " + said); helper.fail(ok.summary()); return; }
            // 2. Its premises: the town's stall or room.
            com.jrpetty.mcassistant.entity.PlayerLife.open(p, "firm", argsOf("firm", b.id));
            String summary = com.jrpetty.mcassistant.entity.PlayerLife.pageForTests(p).toString();
            ok.that(summary.contains(t.stall() ? "A stall on the square" : "A room the town lets"), "2. its premises named: " + (t.stall() ? "stall" : "room"));
            // 3. A hand taken on.
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "hire", new net.minecraft.nbt.CompoundTag());
            said.add("hire: " + msg(p));
            List<VillageFolkEntity> hands = com.jrpetty.mcassistant.entity.Enterprise.hands(id, b);
            ok.that(!hands.isEmpty(), "3. a hand taken on: " + msg(p));
            // 4. Coin in, and its stock.
            long till0 = b.till;
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "invest", argsOf("f", argsOf("coins", "50")));
            said.add("invest: " + msg(p));
            ok.that(b.till >= till0 + 5000, "4a. coin put in: till " + till0 + " -> " + b.till + " " + msg(p));
            String ware = null;
            for (String k : t.wares()) if (!com.jrpetty.mcassistant.entity.Stockroom.sampleForTests(k).isEmpty()) { ware = k; break; }
            ok.that(ware != null, "a ware to sell");
            long stocked = 0;
            if (ware != null) {
                if (t.resells()) {
                    com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "order", argsOf("k", ware, "f", argsOf("qty", "16")));
                    said.add("order: " + msg(p));
                } else {
                    // Its hand at its bench, a turn at a time, as its work loop takes them (Crafts.now).
                    for (int i = 0; i < 8 && !hands.isEmpty(); i++) {
                        com.jrpetty.mcassistant.entity.Crafts.now(hands.get(0), level, v);
                        long have = 0;
                        for (com.jrpetty.mcassistant.entity.Business.Lot l : b.stock.values()) have += l.qty;
                        if (have > 0) break;
                    }
                    said.add("made: " + b.stock.keySet());
                }
                for (com.jrpetty.mcassistant.entity.Business.Lot l : b.stock.values()) stocked += l.qty;
            }
            ok.that(stocked > 0, "4b. stock in: " + stocked + " (" + said.get(said.size() - 1) + "; " + com.jrpetty.mcassistant.entity.Enterprise.debugLineForTests(level, id, b) + ")");
            // 5. A folk buys at the counter.
            String sold = null;
            long tillBefore = b.till;
            long units0 = 0;
            for (com.jrpetty.mcassistant.entity.Business.Line l : b.allTime.lines.values()) units0 += l.units;
            com.jrpetty.mcassistant.entity.Purchases.openForTests(id, true);
            if (stocked > 0) {
                String key = null;
                for (Map.Entry<String, com.jrpetty.mcassistant.entity.Business.Lot> e : b.stock.entrySet()) if (e.getValue().qty > 0) { key = e.getKey(); break; }
                java.util.function.Predicate<ItemStack> is = com.jrpetty.mcassistant.entity.Stockroom.matcherForTests(key);
                for (int i = 2; i < folk.size() && sold == null; i++) {
                    VillageFolkEntity buyer = folk.get(i);
                    if (hands.contains(buyer)) continue;
                    int got = com.jrpetty.mcassistant.entity.Purchases.get(level, buyer, is, 1, need(t));
                    if (got > 0) sold = buyer.displayNameCap() + " bought " + key;
                }
            }
            com.jrpetty.mcassistant.entity.Purchases.openForTests(id, null);
            long units1 = 0;
            for (com.jrpetty.mcassistant.entity.Business.Line l : b.allTime.lines.values()) units1 += l.units;
            ok.that(sold != null && units1 > units0 && b.till > tillBefore, "5. a folk bought at its counter: " + sold + "; units " + units0 + " -> " + units1
                + ", till " + tillBefore + " -> " + b.till);
            // 6. Its books add up: the till is what came in less what went out.
            String booksPage;
            try {
                com.jrpetty.mcassistant.entity.BusinessBooks.show(p, id, b);
                booksPage = "shown";
            } catch (UnsupportedOperationException e) {
                booksPage = e.getMessage() != null && e.getMessage().contains("may not be sent") ? "shown" : "threw " + e;   // (the test's player has no client)
            } catch (RuntimeException e) {
                booksPage = "threw " + e;
            }
            ok.that(booksPage.equals("shown"), "6a. its books open: " + booksPage);
            ok.that(b.allTime.revenue > 0, "6b. its takings in its books: " + b.allTime.revenue);
            // 7. The next morning and payday: its rent, and its hand paid out of its till.
            level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
            long tillM = b.till;
            int purse0 = hands.isEmpty() ? 0 : hands.get(0).purse();
            com.jrpetty.mcassistant.entity.Businesses.morningForTests(level, v);
            long rent = b.allTime.rent;
            com.jrpetty.mcassistant.entity.Market.payWages(level, v);
            long wages = b.allTime.wages;
            int purse1 = hands.isEmpty() ? 0 : hands.get(0).purse();
            ok.that(rent > 0, "7a. its rent paid in the morning: " + rent);
            ok.that(hands.isEmpty() || wages > 0 && purse1 > purse0, "7b. payday: its hand paid out of its till (wages " + wages + ", purse " + purse0 + " -> " + purse1
                + ", till " + tillM + " -> " + b.till + ")");
            // 8. Wound up from its last tab: its till to its owner.
            int coins0 = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
            com.jrpetty.mcassistant.entity.PlayerLife.open(p, "firm", argsOf("firm", b.id, "tab", "more"));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "windup", argsOf("sure", false));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "windup", argsOf("sure", true));
            said.add("windup: " + msg(p));
            int coins1 = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
            ok.that(!b.open && msg(p).contains("wound up"), "8a. wound up: " + msg(p));
            ok.that(coins1 > coins0, "8b. what was left in its till to its owner: " + coins0 + " -> " + coins1);
            Kit.log("[VT] " + kind + " " + String.join(" | ", said));
            Kit.log("[VT] " + kind + " books: revenue " + b.allTime.revenue + " cogs " + b.allTime.cogs + " wages " + b.allTime.wages + " rent " + b.allTime.rent
                + " other " + b.allTime.other + " capital " + b.allTime.capital + " till " + b.till);
            if (ok.clean()) helper.succeed(); else helper.fail(kind + ": " + ok.summary());
        });
    }

    static com.jrpetty.mcassistant.entity.Purchases.Need need(com.jrpetty.mcassistant.entity.BusinessTypes.BusinessType t) {
        return switch (t.key()) {
            case "toolsmith", "armourer", "generalstore" -> com.jrpetty.mcassistant.entity.Purchases.Need.TOOL;
            case "clothier" -> com.jrpetty.mcassistant.entity.Purchases.Need.CLOTHES;
            case "baker", "butcher", "fishmonger", "cafe", "inn", "greengrocer" -> com.jrpetty.mcassistant.entity.Purchases.Need.FOOD;
            case "tavern", "apothecary" -> com.jrpetty.mcassistant.entity.Purchases.Need.TREAT;
            default -> com.jrpetty.mcassistant.entity.Purchases.Need.HOME;
        };
    }

    static String msg(net.minecraft.server.level.ServerPlayer p) {
        return com.jrpetty.mcassistant.entity.PlayerLife.messageForTests(p);
    }

    static net.minecraft.nbt.CompoundTag argsOf(Object... kv) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] instanceof Integer n) t.putInt((String) kv[i], n);
            else if (kv[i + 1] instanceof Boolean b) t.putBoolean((String) kv[i], b);
            else if (kv[i + 1] instanceof net.minecraft.nbt.CompoundTag c) t.put((String) kv[i], c);
            else t.putString((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return t;
    }
}
