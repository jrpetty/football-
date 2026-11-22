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
                // The ways in at its doors made, as the town's rounds make them (DoorWays).
                for (Ledger.Building b : built) {
                    for (com.jrpetty.mcassistant.entity.DoorWays.Door d : com.jrpetty.mcassistant.entity.DoorWays.doorsOf(level, b)) {
                        com.jrpetty.mcassistant.entity.DoorWays.mendForTests(level, d);
                    }
                }
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
            if (Math.abs(y - anchor.getY()) > 12) continue;                // (the street follows the hill, as the hill's own ground lies)
            level.setBlock(g, Blocks.DIRT_PATH.defaultBlockState(), 2 | 16);
            BlockState over = level.getBlockState(g.above());
            if (over.canBeReplaced()) level.setBlock(g.above(), Blocks.AIR.defaultBlockState(), 2 | 16);
        }
    }

    /**
     * The one-time mend of a town's buildings as they stand (BuildingMend): a shop, a café, a smithy, a bakery and a house
     * of a town that built them before its builders laid their floors and cleared their lots as they do now, and lived in
     * since: grass for a floor, a hole in one, tall grass, a sapling and a wild tree's leaves in the rooms, a lump of earth
     * hung in the air, the hillside's earth in a corner, water lying in a room, a crafting table laid as a block of a
     * wall, scaffolding left by a wall, and a block of dirt a player set down in the shop. After the mend the audit finds
     * nothing in any of them, what came out went into the stores, and the player's block is where it was.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bl04_mend_existing_buildings")
    public static void bl04_mend_existing_buildings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.BuildingMend.resetForTests();
        int x0 = 6510000;
        Kit.hold(level, x0 + 60, Z, 100);
        Kit.prepare(level, x0 + 60, Z, 100);
        BlockPos heart = Kit.surface(level, x0 - 40, Z);
        VillageFolkEntity hand = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hand != null, "a village");
        UUID id = hand.ownerId();
        Villages.Village v = Villages.get(id);
        List<String> names = List.of("shop", "cafe", "smithy", "bakery", "house");
        List<Ledger.Building> built = new ArrayList<>();
        int base = Kit.surface(level, x0, Z).getY();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            BlockPos lot = new BlockPos(x0 + 30 * i, 0, Z);
            shape(level, Ground.FLAT, lot, n, base, 900 + i);
            BlockPos anchor = new BlockPos(lot.getX(), base, lot.getZ());
            BuildGoal.stamp(level, n, anchor, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, n, anchor, Direction.NORTH);
            built.add(new Ledger.Building(n, anchor, Direction.NORTH));
        }
        // The spoiling: in each, the first open room cells found from its middle.
        BlockPos playersDirt = null;
        for (Ledger.Building b : built) {
            List<BuildGoal.Placement> plan = BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13);
            java.util.Set<Long> drawn = new java.util.HashSet<>();
            int minY = Integer.MAX_VALUE;
            for (BuildGoal.Placement p : plan) { drawn.add(p.pos().asLong()); minY = Math.min(minY, p.pos().getY()); }
            List<BlockPos> room = new ArrayList<>();
            for (int r = 0; r <= 3 && room.size() < 6; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                BlockPos c = new BlockPos(b.anchor().getX() + dx, minY + 1, b.anchor().getZ() + dz);
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r || drawn.contains(c.asLong()) || drawn.contains(c.above().asLong())) continue;
                if (!drawn.contains(c.below().asLong()) || !level.getBlockState(c).isAir() || room.contains(c)) continue;
                room.add(c);
            }
            helper.assertTrue(room.size() >= 6, b.structure() + ": room to spoil (" + room.size() + ")");
            level.setBlock(room.get(0).below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);               // grass for a floor
            level.setBlock(room.get(0), Blocks.SHORT_GRASS.defaultBlockState(), 3);
            level.setBlock(room.get(1).below(), Blocks.AIR.defaultBlockState(), 3);                       // a hole in it
            DoublePlantBlock.placeAt(level, Blocks.TALL_GRASS.defaultBlockState(), room.get(2), 3);       // tall grass
            level.setBlock(room.get(3), Blocks.OAK_SAPLING.defaultBlockState(), 3);                       // a sapling
            level.setBlock(room.get(4), Blocks.DIRT.defaultBlockState(), 3);                              // the hillside's earth
            level.setBlock(room.get(5).above(), Blocks.OAK_LEAVES.defaultBlockState(), 3);                // leaves hung in the room
            if (b.structure().equals("cafe")) level.setBlock(room.get(5), Blocks.WATER.defaultBlockState(), 3);
            if (b.structure().equals("shop")) {
                playersDirt = room.get(4).east(0).above(0);
                level.setBlock(playersDirt, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                com.jrpetty.mcassistant.entity.PlayerBlocks.markForTests(level, playersDirt, true);
            }
            // A crafting table laid as a block of its wall.
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.WALL && p.pos().getY() == minY + 2) {
                    level.setBlock(p.pos(), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                    break;
                }
            }
            // Scaffolding by its side.
            int[] f = Blueprints.fullHalf(b.structure());
            BlockPos sc = new BlockPos(b.anchor().getX() + f[0] + 2, minY + 1, b.anchor().getZ());
            level.setBlock(sc, Blocks.SCAFFOLDING.defaultBlockState(), 3);
            // A lump of earth in the air of the room, a block up.
            if (b.structure().equals("smithy")) level.setBlock(room.get(1).above(2), Blocks.DIRT.defaultBlockState(), 3);
        }
        BuildingAudit.Report before = new BuildingAudit.Report();
        for (Ledger.Building b : built) BuildingAudit.building(level, v, b.structure(), b.structure(), b.anchor(), b.facing(), before);
        Map<String, Integer> fixed = com.jrpetty.mcassistant.entity.BuildingMend.nowForTests(level, v);
        BuildingAudit.Report after = new BuildingAudit.Report();
        for (Ledger.Building b : built) BuildingAudit.building(level, v, b.structure(), b.structure(), b.anchor(), b.facing(), after);
        Kit.log("[VT] bl04 before " + before.line() + "; mended " + fixed + "; after " + after.line());
        for (Map.Entry<String, List<String>> e : after.examples.entrySet()) for (String s : e.getValue()) Kit.log("[VT] bl04   after " + e.getKey() + ": " + s);
        Kit.Expect ok = new Kit.Expect();
        ok.that(before.faults() > 0, "the buildings were spoilt: " + before.line());
        for (String k : new String[]{ "grass", "earth_inside", "fluid_inside", "floor_hole", "earth_floor", "stray", "fixture_as_block", "scaffolding" }) {
            int left = after.count(k);                                                // (the player's own coarse dirt is not counted)
            ok.that(left <= 0, k + " left after the mend: " + after.count(k) + " " + after.examples.getOrDefault(k, List.of()));
        }
        ok.that(playersDirt != null && level.getBlockState(playersDirt).is(Blocks.COARSE_DIRT), "the player's block where the player set it");
        ok.that(com.jrpetty.mcassistant.village.Ledger.note(id, com.jrpetty.mcassistant.entity.BuildingMend.DONE) != null, "the town's mend noted, so it is done once");
        if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
    }

    /**
     * A work table left standing where a building goes (the square's work table, and a fountain raised round it at
     * Ironmouth, the table for a block of its basin): the builder takes it up, as it takes up a fence, and lays the block
     * the drawing wants. A fountain and a shop, each over a crafting table, a loom and a grindstone in their cells.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bl05_no_workstation_for_a_brick")
    public static void bl05_no_workstation_for_a_brick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x0 = 6515000;
        Kit.hold(level, x0 + 30, Z, 70);
        Kit.prepare(level, x0 + 30, Z, 70);
        BlockPos heart = Kit.surface(level, x0 - 220, Z);                   // (out past the town's own streets and square)
        Kit.hold(level, heart.getX(), heart.getZ(), 24);
        Kit.prepare(level, heart.getX(), heart.getZ(), 24);
        VillageFolkEntity hand = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hand != null, "a village");
        UUID id = hand.ownerId();
        Villages.Village v = Villages.get(id);
        int base = Kit.surface(level, x0, Z).getY();
        Kit.Expect ok = new Kit.Expect();
        BuildingAudit.Report r = new BuildingAudit.Report();
        int i = 0;
        for (String n : List.of("fountain", "shop")) {
            BlockPos lot = new BlockPos(x0 + 40 * i++, 0, Z);
            shape(level, Ground.FLAT, lot, n, base, 950 + i);
            BlockPos anchor = new BlockPos(lot.getX(), base, lot.getZ());
            Block[] tables = { Blocks.CRAFTING_TABLE, Blocks.LOOM, Blocks.GRINDSTONE };
            int put = 0;
            List<BuildGoal.Placement> at = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(n, anchor, Direction.NORTH, 13)) {
                if (put >= tables.length || p.part() != BuildGoal.Part.BLOCK || p.pos().getY() > anchor.getY() + 1) continue;
                if (p.pos().getY() < anchor.getY() - 1 || (p.pos().getX() + p.pos().getZ()) % 3 != 0) continue;
                level.setBlock(p.pos(), tables[put++].defaultBlockState(), 3);
                at.add(p);
            }
            ok.that(put == tables.length, n + ": the tables set in its cells (" + put + ")");
            String words = BuildGoal.buildNowForTests(hand, n, anchor, Direction.NORTH, BuildWorldGameTests::stock);
            Ledger.built(id, n, anchor, Direction.NORTH);
            BuildingAudit.building(level, v, n, n, anchor, Direction.NORTH, r);
            Kit.log("[VT] bl05 " + n + ": " + words);
            for (BuildGoal.Placement p : at) Kit.log("[VT] bl05   " + p.pos().subtract(anchor).toShortString() + " " + p.style() + " now "
                + level.getBlockState(p.pos()).getBlock() + " wrong " + BuildGoal.wrongInCell(level.getBlockState(p.pos()), p));
        }
        Kit.log("[VT] bl05 " + r.line() + " " + r.examples.getOrDefault("fixture_as_block", List.of()));
        ok.that(r.count("fixture_as_block") == 0, "no workstation left for a block: " + r.examples.getOrDefault("fixture_as_block", List.of()));
        if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
    }

    /**
     * A town whose one-time mend has already run (its ledger says so), with a fountain that went up round the square's work
     * table before its builders knew better (the table stamped into a block of its basin, as Ironmouth's was), and a house
     * its builder now puts up over a crafting table and a loom left on its lot: the day's survey of the town's buildings
     * finds the fountain's table as a fault and a hand puts the basin's block back (the table into the stores); the house's
     * builder takes up the table and the loom as it lays its walls and floor. Neither has a workstation for a block after.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "bl06_tables_mended_after_the_mend")
    public static void bl06_tables_mended_after_the_mend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Restore.resetForTests();
        int x0 = 6518000;
        Kit.hold(level, x0 + 20, Z, 60);
        Kit.prepare(level, x0 + 20, Z, 60);
        BlockPos heart = Kit.surface(level, x0 - 220, Z);
        Kit.hold(level, heart.getX(), heart.getZ(), 24);
        Kit.prepare(level, heart.getX(), heart.getZ(), 24);
        VillageFolkEntity hand = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hand != null, "a village");
        UUID id = hand.ownerId();
        Villages.Village v = Villages.get(id);
        Ledger.note(id, com.jrpetty.mcassistant.entity.BuildingMend.DONE, "mended long ago");
        BlockPos storeAt = heart.east(3);                                    // the town's stores: what the hands mend with
        level.setBlockAndUpdate(storeAt, Blocks.CHEST.defaultBlockState());
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, storeAt);
        net.minecraft.world.Container stores = (net.minecraft.world.Container) level.getBlockEntity(storeAt);
        stores.setItem(0, new ItemStack(Items.STONE_BRICKS, 64));
        stores.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
        stores.setItem(2, new ItemStack(Items.OAK_PLANKS, 64));
        stores.setChanged();
        Villages.forgetStores(id);
        int base = Kit.surface(level, x0, Z).getY();
        // The fountain, stamped whole, and the work table set into a block of its basin after.
        BlockPos lotF = new BlockPos(x0, 0, Z);
        shape(level, Ground.FLAT, lotF, "fountain", base, 960);
        BlockPos fa = new BlockPos(x0, base, Z);
        BuildGoal.stamp(level, "fountain", fa, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "fountain", fa, Direction.NORTH);
        BlockPos table = null;
        for (BuildGoal.Placement p : BuildGoal.plan("fountain", fa, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.BLOCK && p.pos().getY() == fa.getY() && table == null) table = p.pos();
        }
        helper.assertTrue(table != null, "a block of the fountain's basin");
        level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        // The house, put up by its builder over a table and a loom on its lot.
        BlockPos lotH = new BlockPos(x0 + 40, 0, Z);
        shape(level, Ground.FLAT, lotH, "house", base, 961);
        BlockPos ha = new BlockPos(x0 + 40, base, Z);
        int put = 0;
        for (BuildGoal.Placement p : BuildGoal.plan("house", ha, Direction.NORTH, 13)) {
            if (put < 2 && p.part() == BuildGoal.Part.BLOCK && p.pos().getY() <= ha.getY() && (p.pos().getX() + p.pos().getZ()) % 4 == 0) {
                level.setBlock(p.pos(), (put++ == 0 ? Blocks.CRAFTING_TABLE : Blocks.LOOM).defaultBlockState(), 3);
            }
        }
        String words = BuildGoal.buildNowForTests(hand, "house", ha, Direction.NORTH, BuildWorldGameTests::stock);
        Ledger.built(id, "house", ha, Direction.NORTH);
        // The day's survey, and its hands at the list.
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
        int mended = 0;
        try {
            int looked = com.jrpetty.mcassistant.entity.Restore.surveyAll(level, v);
            final BlockPos tcell = table;
            Kit.log("[VT] bl06 surveyed " + looked + "; the table's fix: " + com.jrpetty.mcassistant.entity.Restore.diff(level, id, new Ledger.Building("fountain", fa, Direction.NORTH))
                .stream().filter(f -> f.pos().equals(tcell)).toList() + "; jobs " + com.jrpetty.mcassistant.entity.Restore.queue(id).size());
            for (int i = 0; i < 40; i++) mended += com.jrpetty.mcassistant.entity.Restore.work(level, v, 64);
        } finally {
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        }
        BuildingAudit.Report r = new BuildingAudit.Report();
        BuildingAudit.building(level, v, "fountain", "fountain", fa, Direction.NORTH, r);
        BuildingAudit.building(level, v, "house", "house", ha, Direction.NORTH, r);
        Kit.log("[VT] bl06 house: " + words + "; survey mended " + mended + "; table cell now " + level.getBlockState(table).getBlock() + "; " + r.line());
        Kit.Expect ok = new Kit.Expect();
        ok.that(put == 2, "the house's lot had its table and loom (" + put + ")");
        ok.that(!level.getBlockState(table).is(Blocks.CRAFTING_TABLE), "the fountain's table taken out by the survey's hands: " + level.getBlockState(table).getBlock());
        ok.that(r.count("fixture_as_block") == 0, "no workstation for a block in either: " + r.examples.getOrDefault("fixture_as_block", List.of()));
        if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
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

    // ================================================================== more than one business

    /** A town for the several-businesses tests: so many folk (two at each trade asked for, the rest farmers), the Iron Age,
     *  its market open, its stores full, a flat square. */
    static List<VillageFolkEntity> busyTown(GameTestHelper helper, ServerLevel level, int x, int n) {
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.MarketMaker.liveForTests(true);
        com.jrpetty.mcassistant.entity.Enterprise.resetForTests();
        com.jrpetty.mcassistant.entity.Enterprise.autoForTests(false);
        com.jrpetty.mcassistant.entity.PlayerShops.resetForTests();
        level.setDayTime(8 * 24000L + 7000L);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        int y = Kit.surface(level, x, Z).getY();
        for (int dx = -30; dx <= 30; dx++) for (int dz = -30; dz <= 30; dz++) {
            level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            for (int h = 0; h <= 8; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 12 + (i % 10) * 2, y, Z - 12 + (i / 10) * 2), 0.0F, 200);
            helper.assertTrue(f != null, "folk " + i);
            f.ensurePersona();
            f.setJob(i < 4 ? com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.SHOP : i < 6 ? com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.COOK
                : com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM);
            f.spend(f.purse());
            f.earn(200);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.IRON);
        Ledger.addCoins(id, 500);
        com.jrpetty.mcassistant.entity.MarketMaker.openForTests(level, Villages.get(id));
        for (int k = 0; k < 4; k++) {
            BlockPos c = new BlockPos(x + 14 + 2 * k, y, Z + 14);
            level.setBlockAndUpdate(c, Blocks.CHEST.defaultBlockState());
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, c);
        }
        Villages.forgetStores(id);
        return folk;
    }

    /** A room of the player's own at this spot (planks, a door to the west), a counter in it and a chest of these. */
    static com.jrpetty.mcassistant.entity.PlayerShops.Shop shopRoom(ServerLevel level, net.minecraft.server.level.ServerPlayer p, BlockPos at, ItemStack... goods) {
        int y = at.getY();
        for (int dx = 0; dx <= 6; dx++) for (int dz = -3; dz <= 3; dz++) {
            level.setBlock(new BlockPos(at.getX() + dx, y - 1, at.getZ() + dz), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(at.getX() + dx, y + 3, at.getZ() + dz), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            boolean wall = dx == 0 || dx == 6 || dz == -3 || dz == 3;
            for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(at.getX() + dx, y + h, at.getZ() + dz), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos door = new BlockPos(at.getX(), y, at.getZ());
        level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
        level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        BlockPos counter = at.offset(3, 0, 0), chest = at.offset(5, 0, 2);
        level.setBlockAndUpdate(counter, com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        net.minecraft.world.Container c = (net.minecraft.world.Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        com.jrpetty.mcassistant.entity.PlayerShops.placed(level, counter, p, true);
        return com.jrpetty.mcassistant.entity.PlayerShops.at(level, counter);
    }

    /**
     * A player runs five businesses in one town: three chartered firms of different kinds (a greengrocer's, a general store,
     * a bakery) and two shops of its own in two rooms. All five on Your businesses, each with a button to its books or its
     * screen; each takes a sale of a folk of the town; each firm's hand is paid out of its till at payday; and the second
     * shop's counter opens the second shop, not the first.
     */
    @GameTest(template = EMPTY, timeoutTicks = 500, batch = "bl30_a_player_runs_five")
    public static void bl30_a_player_runs_five(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 6570000;
        List<VillageFolkEntity> folk = busyTown(helper, level, x, 24);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int y = Kit.surface(level, x, Z).getY();
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(folk.get(0).getX() + 1, y, folk.get(0).getZ());
        p.getInventory().clearContent();
        p.getInventory().setItem(0, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 64));
        p.getInventory().setItem(1, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 64));
        p.getInventory().setItem(2, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 64));
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            com.jrpetty.mcassistant.entity.PlayerLife.open(p, "start", argsOf("kind", "greengrocer"));
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "hello", new net.minecraft.nbt.CompoundTag());
            String[] kinds = { "greengrocer", "generalstore", "baker" };
            List<com.jrpetty.mcassistant.entity.Business> firms = new ArrayList<>();
            for (String k : kinds) {
                String said = com.jrpetty.mcassistant.entity.StartBusiness.startForTests(p, k, "town");
                com.jrpetty.mcassistant.entity.Business b = com.jrpetty.mcassistant.entity.Businesses.of(id, com.jrpetty.mcassistant.entity.PlayerLife.ctx(p).str("firm"));
                ok.that(b != null && !said.startsWith("!") && b.ownedBy(p.getUUID()), k + " chartered: " + said);
                if (b != null) firms.add(b);
            }
            // Two shops of its own, in two rooms.
            p.moveTo(x + 8, y, Z);
            com.jrpetty.mcassistant.entity.PlayerShops.Shop s1 = shopRoom(level, p, new BlockPos(x + 9, y, Z - 5), new ItemStack(Items.STONE_HOE, 4));
            com.jrpetty.mcassistant.entity.PlayerShops.Shop s2 = shopRoom(level, p, new BlockPos(x + 9, y, Z + 5), new ItemStack(Items.STONE_HOE, 4));
            ok.that(s1 != null && s2 != null && s1 != s2, "two shops of its own: " + (s1 == null ? null : s1.key()) + ", " + (s2 == null ? null : s2.key()));
            ok.that(com.jrpetty.mcassistant.entity.PlayerShops.ofAll(id, p.getUUID()).size() == 2, "both in the register");
            for (com.jrpetty.mcassistant.entity.PlayerShops.Shop s : new com.jrpetty.mcassistant.entity.PlayerShops.Shop[]{ s1, s2 }) {
                if (s == null) continue;
                com.jrpetty.mcassistant.entity.PlayerShops.choose(p.getUUID(), s);
                com.jrpetty.mcassistant.entity.PlayerLife.open(p, "shop", null);
                com.jrpetty.mcassistant.entity.PlayerLife.act(p, "shop", "setup", argsOf("f", argsOf("name", s == s1 ? "Hoes East" : "Hoes West", "kind", "general", "opens", "0", "closes", "23999", "tell", "no")));
                com.jrpetty.mcassistant.entity.PlayerShops.resurvey(level, s);
            }
            // The second counter opens the second shop.
            if (s2 != null) {
                com.jrpetty.mcassistant.entity.PlayerShops.openAt(p, s2.anchor);
                ok.that(com.jrpetty.mcassistant.entity.PlayerShops.mine(p) == s2, "the second counter opens the second shop");
            }
            // Each firm: a hand, coin, stock, priced to sell.
            com.jrpetty.mcassistant.entity.Purchases.openForTests(id, true);
            String[] wares = { "minecraft:carrot", "minecraft:torch", "minecraft:bread" };
            for (int i = 0; i < firms.size(); i++) {
                com.jrpetty.mcassistant.entity.Business b = firms.get(i);
                com.jrpetty.mcassistant.entity.PlayerLife.open(p, "firm", argsOf("firm", b.id));
                com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "pick", argsOf("firm", b.id));
                com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "hire", new net.minecraft.nbt.CompoundTag());
                com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "invest", argsOf("f", argsOf("coins", "30")));
                ItemStack sample = com.jrpetty.mcassistant.entity.Stockroom.sampleForTests(wares[i]);
                com.jrpetty.mcassistant.entity.Market.intoStoresForTests(level, id, sample.copyWithCount(64));
                com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(true);
                try {
                    com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "order", argsOf("k", wares[i], "f", argsOf("qty", "8")));
                } finally {
                    com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(false);
                }
                for (int k = 0; k < 6; k++) com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "markup", argsOf("d", -5));
                // The town's own counter out of it but the firm's.
                java.util.function.Predicate<ItemStack> is = com.jrpetty.mcassistant.entity.Stockroom.matcherForTests(wares[i]);
                long spare = com.jrpetty.mcassistant.entity.Market.stock(level, id, is) - (b.stock.containsKey(wares[i]) ? b.stock.get(wares[i]).qty : 0);
                for (BlockPos sp : Villages.storeChests(level, id)) {
                    if (!(level.getBlockEntity(sp) instanceof net.minecraft.world.Container c)) continue;
                    for (int j = 0; j < c.getContainerSize() && spare > 0; j++) {
                        ItemStack it = c.getItem(j);
                        if (!is.test(it)) continue;
                        int kk = (int) Math.min(spare, it.getCount());
                        it.shrink(kk);
                        spare -= kk;
                    }
                    c.setChanged();
                }
                Villages.forgetStores(id);
                long units0 = 0;
                for (com.jrpetty.mcassistant.entity.Business.Line l : b.allTime.lines.values()) units0 += l.units;
                boolean sold = false;
                for (int k = 6; k < folk.size() && !sold; k++) {
                    VillageFolkEntity buyer = folk.get(k);
                    if (com.jrpetty.mcassistant.entity.Enterprise.hands(id, b).contains(buyer)) continue;
                    sold = com.jrpetty.mcassistant.entity.Purchases.get(level, buyer, is, 1, i == 1 ? com.jrpetty.mcassistant.entity.Purchases.Need.TOOL
                        : com.jrpetty.mcassistant.entity.Purchases.Need.FOOD) > 0;
                }
                long units1 = 0;
                for (com.jrpetty.mcassistant.entity.Business.Line l : b.allTime.lines.values()) units1 += l.units;
                ok.that(units1 > units0, b.name + " took a sale (" + units0 + " -> " + units1 + "; stock " + b.stock.keySet() + ")");
            }
            com.jrpetty.mcassistant.entity.Purchases.openForTests(id, null);
            // Each shop: a farmer without a hoe buys one.
            int shopSales = 0;
            for (com.jrpetty.mcassistant.entity.PlayerShops.Shop s : new com.jrpetty.mcassistant.entity.PlayerShops.Shop[]{ s1, s2 }) {
                if (s == null) continue;
                for (int k = 0; k < 3; k++) {                                // a farmer new to the town, its hoe lost
                    VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 4, y, Z + (s == s1 ? -5 : 5) + k), 0.0F, 200);
                    if (f == null) continue;
                    f.ensurePersona();
                    f.earn(40);
                    f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM);
                    f.removeMatching(it -> it.is(net.minecraft.tags.ItemTags.HOES), 64);
                    f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    if (com.jrpetty.mcassistant.entity.ShopCustomers.buyForTests(level, f, s) != null) { shopSales++; break; }
                    if (k == 0) Kit.log("[VT] bl30 " + f.displayNameCap() + " " + f.stationTask() + " wants a hoe: "
                        + com.jrpetty.mcassistant.entity.ItemUses.want(f, new ItemStack(Items.STONE_HOE)) + " purse " + f.purse());
                }
            }
            ok.that(shopSales == 2, "each shop sold a hoe (" + shopSales + "; " + (s1 == null ? "" : com.jrpetty.mcassistant.entity.PlayerShops.stateWords(level, s1)) + " / "
                + (s2 == null ? "" : com.jrpetty.mcassistant.entity.PlayerShops.stateWords(level, s2)) + ")");
            // All five on Your businesses.
            com.jrpetty.mcassistant.entity.PlayerLife.open(p, "firm", argsOf("tab", "summary"));
            String page = com.jrpetty.mcassistant.entity.PlayerLife.pageForTests(p).toString();
            for (com.jrpetty.mcassistant.entity.Business b : firms) ok.that(page.contains(b.name), "Your businesses lists " + b.name);
            ok.that(page.contains("Hoes East") && page.contains("Hoes West"), "and both shops");
            ok.that(page.contains("Start another business"), "and Start another business");
            // Payday: each firm's hand paid out of its till.
            level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
            com.jrpetty.mcassistant.entity.Businesses.morningForTests(level, v);
            com.jrpetty.mcassistant.entity.Market.payWages(level, v);
            for (com.jrpetty.mcassistant.entity.Business b : firms) ok.that(b.allTime.wages > 0, b.name + " paid its hand (" + b.allTime.wages + ")");
            if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
        });
    }

    /** A firm's books written as if it had run so many weeks at this daily profit (pennies) to today. */
    static void history(UUID village, com.jrpetty.mcassistant.entity.Business b, long today, int days, long dailyNet) {
        b.opened = today - days;
        b.days.clear();
        net.minecraft.nbt.CompoundTag r = com.jrpetty.mcassistant.entity.Enterprise.recordForTests(village, b);
        if (r != null) r.putInt("goodWeeks", dailyNet > 0 ? days / 7 : 0);   // its good weeks, as its mornings tallied them
        for (long d = today - Math.min(days, 27); d <= today; d++) {
            com.jrpetty.mcassistant.entity.Business.Day day = b.today(d);
            if (dailyNet >= 0) day.revenue += dailyNet; else day.other += -dailyNet;
        }
    }

    /**
     * A folk's bakery has paid its way for eight weeks: it may open another, and the second it opens is its own (a branch:
     * "No. 2"), kept by a hand taken on for it while its owner stays at its first; it says so. A town where it would hold
     * more than its fair share: refused. And a branch that loses money is shut by its owner the next morning.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl31_a_folk_opens_a_branch")
    public static void bl31_a_folk_opens_a_branch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 6574000;
        List<VillageFolkEntity> folk = busyTown(helper, level, x, 30);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity wren = folk.get(4);                            // a cook
            wren.tradeXpForTests(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.COOK, 20000);
            com.jrpetty.mcassistant.entity.Business first = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "baker", wren);
            ok.that(first != null, "its first bakery");
            if (first == null) { helper.fail(ok.summary()); return; }
            history(id, first, day, 56, 400);
            // Others' firms in the town: room for its second within a fair share.
            com.jrpetty.mcassistant.entity.Business other1 = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "greengrocer", folk.get(10));
            com.jrpetty.mcassistant.entity.Business other2 = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "carpenter", folk.get(11));
            ok.that(other1 != null && other2 != null, "two firms of other folk");
            int cap = com.jrpetty.mcassistant.entity.FirmBranches.cap(level, id, wren.getUUID());
            String why = com.jrpetty.mcassistant.entity.FirmBranches.whyNot(level, id, wren);
            Kit.log("[VT] bl31 cap " + cap + " weeks " + com.jrpetty.mcassistant.entity.FirmBranches.profitableWeeks(level, id, wren.getUUID()) + " why " + why);
            ok.that(cap >= 2 && why == null, "eight good weeks: it may open another (cap " + cap + ", " + why + ")");
            List<VillageFolkEntity> handsBefore = com.jrpetty.mcassistant.entity.Enterprise.hands(id, first);
            com.jrpetty.mcassistant.entity.Business second = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "baker", wren);
            ok.that(second != null && second.ownedBy(wren.getUUID()), "its second bakery opened");
            if (second != null) {
                List<VillageFolkEntity> keepers = com.jrpetty.mcassistant.entity.Enterprise.hands(id, second);
                ok.that(second.name.contains("No. 2"), "a branch's name: " + second.name);
                ok.that(!keepers.isEmpty() && !keepers.contains(wren), "kept by a hand of its own, not its owner: " + keepers.size());
                ok.that(com.jrpetty.mcassistant.entity.Enterprise.hands(id, first).contains(wren), "its owner still at its first");
                CompoundTagHolder.last = com.jrpetty.mcassistant.entity.FolkProfile.build(wren, null);
                net.minecraft.nbt.ListTag firms = CompoundTagHolder.last.getCompound("biz").getList("firms", net.minecraft.nbt.Tag.TAG_COMPOUND);
                ok.that(firms.size() == 2, "its profile lists both (" + firms.size() + ")");
            }
            // The fair share: a third would put three of five firms in its hands.
            history(id, second == null ? first : second, day, 56, 400);
            String third = com.jrpetty.mcassistant.entity.FirmBranches.whyNot(level, id, wren);
            ok.that(third != null && third.contains("fair share"), "a third refused: its fair share (" + third + ")");
            // A losing branch shut.
            if (second != null) {
                com.jrpetty.mcassistant.entity.Business.Day bad = second.today(day);
                bad.other += 20000;
                int closed = com.jrpetty.mcassistant.entity.FirmBranches.closeLosers(level, id, day);
                ok.that(closed == 1 && !second.open && first.open, "the losing branch shut, the first kept (" + closed + ")");
            }
            if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
        });
    }

    /** (The last profile built, for the log.) */
    static final class CompoundTagHolder {
        static net.minecraft.nbt.CompoundTag last;
    }

    /**
     * A busy folk's profile, as the screen gets it in one packet: the businesses it owns and the one it works in, each with
     * its role, its week, its till, its hands, its age and how it is doing, and whether the viewer may open its books; its
     * plans; its standing in the town, offices, what it thinks of the viewer and how to raise it; and the rest it always had
     * (its head, family, work, day, things).
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bl32_the_profile_carries_it_all")
    public static void bl32_the_profile_carries_it_all(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 6578000;
        List<VillageFolkEntity> folk = busyTown(helper, level, x, 24);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer p = helper.makeMockServerPlayerInLevel();
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity wren = folk.get(4);
            wren.tradeXpForTests(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.COOK, 20000);
            com.jrpetty.mcassistant.entity.Business a = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "baker", wren);
            if (a != null) history(id, a, day, 30, 300);
            com.jrpetty.mcassistant.entity.Business o1 = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "greengrocer", folk.get(10));
            com.jrpetty.mcassistant.entity.Business o2 = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "carpenter", folk.get(11));
            com.jrpetty.mcassistant.entity.Business b = com.jrpetty.mcassistant.entity.Enterprise.foundForTests(level, v, "cafe", wren);
            ok.that(a != null && b != null, "two businesses of its own");
            net.minecraft.nbt.CompoundTag prof = com.jrpetty.mcassistant.entity.FolkProfile.build(wren, p);
            net.minecraft.nbt.ListTag firms = prof.getCompound("biz").getList("firms", net.minecraft.nbt.Tag.TAG_COMPOUND);
            Kit.log("[VT] bl32 biz " + prof.getCompound("biz") + " town " + prof.getCompound("town"));
            ok.that(firms.size() == 2, "both businesses in its profile (" + firms.size() + ")");
            for (int i = 0; i < firms.size(); i++) {
                net.minecraft.nbt.CompoundTag f = firms.getCompound(i);
                for (String k : new String[]{ "id", "name", "trade", "role", "where", "takings", "profit", "till", "staff", "days", "trend", "books" }) {
                    ok.that(f.contains(k), "a business row carries " + k + ": " + f);
                }
            }
            ok.that(prof.getCompound("biz").contains("plans"), "its plans");
            net.minecraft.nbt.CompoundTag town = prof.getCompound("town");
            for (String k : new String[]{ "town", "status", "offices", "you", "you_word", "raise", "history" }) ok.that(town.contains(k), "its town page carries " + k);
            for (String k : new String[]{ "head", "about", "life", "work", "day", "things", "card", "self" }) ok.that(prof.contains(k), "and its " + k);
            // A hand of another's firm: its profile has the firm it works in, as a hand.
            VillageFolkEntity hand = o1 == null ? null : com.jrpetty.mcassistant.entity.Enterprise.hireForTests(level, v, o1);
            if (hand != null) {
                net.minecraft.nbt.ListTag hf = com.jrpetty.mcassistant.entity.FolkProfile.build(hand, p).getCompound("biz").getList("firms", net.minecraft.nbt.Tag.TAG_COMPOUND);
                ok.that(hf.size() == 1 && hf.getCompound(0).getString("role").equals("a hand"), "a hand's profile: the firm it works in (" + hf + ")");
            }
            if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
        });
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
        Kit.pond(level, x + 14, Z - 14, 3);                               // (water for a fisher's work)
        int n = Math.max(12, t.minFolk() + 3);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 10 + (i % 8) * 2, y, Z - 8 + (i / 8) * 2), 0.0F, 200);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(i < 2 ? t.work() : com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM);
            if (i < 2) f.tradeXpForTests(t.work(), 20000);                // an old hand at it, as a town's own makers are
            if (i < 2) Kit.log("[VT] " + kind + " " + f.displayNameCap() + " at " + f.stationTask() + " busy " + com.jrpetty.mcassistant.entity.TownJobs.busy(f));
            f.spend(f.purse());
            f.earn(200);
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
        for (int k = 1; k <= 2; k++) {                                    // room in the stores for what comes in
            BlockPos more = chest2.east(2 * k);
            level.setBlockAndUpdate(more, Blocks.CHEST.defaultBlockState());
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, more);
        }
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
                    ItemStack sample = com.jrpetty.mcassistant.entity.Stockroom.sampleForTests(ware);
                    for (int i = 0; i < 3; i++) com.jrpetty.mcassistant.entity.Market.intoStoresForTests(level, id, sample.copyWithCount(sample.getMaxStackSize()));
                    com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(true);
                    try {
                        com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "order", argsOf("k", ware, "f", argsOf("qty", "16")));
                    } finally {
                        com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(false);
                    }
                    said.add("order: " + msg(p));
                } else {
                    // Its hand at its bench, a turn at a time, as its work loop takes them (Crafts.now).
                    for (int i = 0; i < 8 && !hands.isEmpty(); i++) {
                        int depth = com.jrpetty.mcassistant.entity.Accounts.enter(hands.get(0));   // (its own tick, as the game runs it)
                        try {
                            com.jrpetty.mcassistant.entity.Crafts.now(hands.get(0), level, v);
                        } finally {
                            com.jrpetty.mcassistant.entity.Accounts.leave(depth);
                        }
                        long have = 0;
                        for (com.jrpetty.mcassistant.entity.Business.Lot l : b.stock.values()) have += l.qty;
                        if (have > 0) break;
                    }
                    said.add("made: " + b.stock.keySet());
                    long have = 0;
                    for (com.jrpetty.mcassistant.entity.Business.Lot l : b.stock.values()) have += l.qty;
                    if (have == 0) {
                        // What its bench cannot make (a catch, a brew, a furnace's work, out of a hand's reach): ordered in from Stock,
                        // out of a market that has plenty of it to spare.
                        ItemStack sample = com.jrpetty.mcassistant.entity.Stockroom.sampleForTests(ware);
                        for (int i = 0; i < 6; i++) com.jrpetty.mcassistant.entity.Market.intoStoresForTests(level, id, sample.copyWithCount(sample.getMaxStackSize()));
                        // (The town's own budget, which sells only a glut while it is short of anything, is Budget's question.)
                        com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(true);
                        try {
                            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "order", argsOf("k", ware, "f", argsOf("qty", "16")));
                        } finally {
                            com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(false);
                        }
                        said.add("order: " + msg(p));
                    }
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
                // Priced to sell from its Prices tab.
                for (int i = 0; i < 6; i++) com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "markup", argsOf("d", -5));
                said.add("markup: " + msg(p));
                // The town's own counter out of it but for the firm's (who wins a sale when both have it is ent03's question): what
                // the stores hold past the firm's own taken away.
                long spare = com.jrpetty.mcassistant.entity.Market.stock(level, id, is) - b.stock.get(key).qty;
                for (BlockPos sp : Villages.storeChests(level, id)) {
                    if (!(level.getBlockEntity(sp) instanceof net.minecraft.world.Container c)) continue;
                    for (int i = 0; i < c.getContainerSize() && spare > 0; i++) {
                        ItemStack it = c.getItem(i);
                        if (!is.test(it)) continue;
                        int k = (int) Math.min(spare, it.getCount());
                        it.shrink(k);
                        spare -= k;
                    }
                    c.setChanged();
                }
                Villages.forgetStores(id);
                said.add("the town's own besides the firm's: " + spare + " left over");
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
            long payday = level.getDayTime() / 24000L;
            com.jrpetty.mcassistant.entity.Businesses.morningForTests(level, v);
            long rent = b.allTime.rent;
            com.jrpetty.mcassistant.entity.Market.payWages(level, v);
            long wages = b.allTime.wages;
            int paid = hands.isEmpty() ? 0 : com.jrpetty.mcassistant.entity.Market.paidOn(hands.get(0).getUUID(), payday);
            ok.that(rent > 0, "7a. its rent paid in the morning: " + rent);
            ok.that(hands.isEmpty() || wages > 0 && paid > 0, "7b. payday: its hand paid out of its till (wages " + wages + ", paid " + paid
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
            case "weaver", "chandler", "potter" -> com.jrpetty.mcassistant.entity.Purchases.Need.LUXURY;
            default -> com.jrpetty.mcassistant.entity.Purchases.Need.HOME;
        };
    }

    static String msg(net.minecraft.server.level.ServerPlayer p) {
        return com.jrpetty.mcassistant.entity.PlayerLife.messageForTests(p);
    }

    // ------------------------------------------------------------------ [biz30] the business data (item 8)

    /** A player's firm (a greengrocer selling carrots) chartered, hired, stocked and priced to sell, in a busy town. */
    static com.jrpetty.mcassistant.entity.Business dataFirm(GameTestHelper helper, ServerLevel level, net.minecraft.server.level.ServerPlayer p, UUID id, Kit.Expect ok) {
        com.jrpetty.mcassistant.entity.PlayerLife.open(p, "start", argsOf("kind", "greengrocer"));
        com.jrpetty.mcassistant.entity.PlayerLife.act(p, "start", "hello", new net.minecraft.nbt.CompoundTag());
        String said = com.jrpetty.mcassistant.entity.StartBusiness.startForTests(p, "greengrocer", "town");
        com.jrpetty.mcassistant.entity.Business b = com.jrpetty.mcassistant.entity.Businesses.of(id, com.jrpetty.mcassistant.entity.PlayerLife.ctx(p).str("firm"));
        ok.that(b != null && b.ownedBy(p.getUUID()), "a greengrocer chartered: " + said);
        if (b == null) return null;
        com.jrpetty.mcassistant.entity.PlayerLife.open(p, "firm", argsOf("firm", b.id));
        com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "pick", argsOf("firm", b.id));
        com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "hire", new net.minecraft.nbt.CompoundTag());
        com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "invest", argsOf("f", argsOf("coins", "40")));
        for (int k = 0; k < 6; k++) com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "markup", argsOf("d", -5));
        return b;
    }

    /** Stock bought in for a firm (out of its till), and the town's own counter emptied of it but the firm's. */
    static void dataStock(ServerLevel level, net.minecraft.server.level.ServerPlayer p, UUID id, com.jrpetty.mcassistant.entity.Business b, String ware, int qty) {
        ItemStack sample = com.jrpetty.mcassistant.entity.Stockroom.sampleForTests(ware);
        com.jrpetty.mcassistant.entity.Market.intoStoresForTests(level, id, sample.copyWithCount(Math.min(64, qty + 8)));
        com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(true);
        try {
            com.jrpetty.mcassistant.entity.PlayerLife.act(p, "firm", "order", argsOf("k", ware, "f", argsOf("qty", String.valueOf(qty))));
        } finally {
            com.jrpetty.mcassistant.entity.Businesses.spareAllForTests(false);
        }
        java.util.function.Predicate<ItemStack> is = com.jrpetty.mcassistant.entity.Stockroom.matcherForTests(ware);
        long spare = com.jrpetty.mcassistant.entity.Market.stock(level, id, is) - (b.stock.containsKey(ware) ? b.stock.get(ware).qty : 0);
        for (BlockPos sp : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(sp) instanceof net.minecraft.world.Container c)) continue;
            for (int j = 0; j < c.getContainerSize() && spare > 0; j++) {
                ItemStack it = c.getItem(j);
                if (!is.test(it)) continue;
                int kk = (int) Math.min(spare, it.getCount());
                it.shrink(kk);
                spare -= kk;
            }
            c.setChanged();
        }
        Villages.forgetStores(id);
    }

    static long cash(com.jrpetty.mcassistant.entity.Business b) {
        return b.till + b.account - b.loan;
    }

    /** What the books say moved the firm's cash: in (takings, its owners' capital), out (stock, wages, rent, running costs, taxes, interest, drawings). */
    static long booked(com.jrpetty.mcassistant.entity.Business.Day d) {
        return d.revenue + d.capital - d.bought - d.wages - d.rent - d.other - d.taxes - d.interest - d.drawings;
    }

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bl33_the_ledger_reconciles")
    public static void bl33_the_ledger_reconciles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 6582000;
        List<VillageFolkEntity> folk = busyTown(helper, level, x, 24);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int y = Kit.surface(level, x, Z).getY();
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(folk.get(0).getX() + 1, y, folk.get(0).getZ());
        p.getInventory().clearContent();
        for (int k = 0; k < 3; k++) p.getInventory().setItem(k, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 64));
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            com.jrpetty.mcassistant.entity.Business b = dataFirm(helper, level, p, id, ok);
            if (b == null) { helper.fail(ok.summary()); return; }
            String ware = "minecraft:carrot";
            java.util.function.Predicate<ItemStack> is = com.jrpetty.mcassistant.entity.Stockroom.matcherForTests(ware);
            com.jrpetty.mcassistant.entity.Purchases.openForTests(id, true);
            long firstDay = com.jrpetty.mcassistant.entity.Businesses.todayForTests(level);
            long unitsAll = 0, salesAll = 0;
            java.util.Set<String> seenBefore = new java.util.HashSet<>();
            for (int day = 0; day < 4; day++) {
                long today = com.jrpetty.mcassistant.entity.Businesses.todayForTests(level);
                long cash0 = cash(b);
                com.jrpetty.mcassistant.entity.Business.Day d0 = copy(b.today(today));
                dataStock(level, p, id, b, ware, 10);
                // the day's buyers: three, two of them yesterday's too
                long units = 0, sales = 0, repeats = 0;
                java.util.Set<String> buyers = new java.util.LinkedHashSet<>();
                for (int k = 6 + day; k < folk.size() && sales < 3; k++) {
                    VillageFolkEntity buyer = folk.get(k);
                    if (com.jrpetty.mcassistant.entity.Enterprise.hands(id, b).contains(buyer)) continue;
                    long before = b.today(today).revenue;
                    int got = com.jrpetty.mcassistant.entity.Purchases.get(level, buyer, is, 1, com.jrpetty.mcassistant.entity.Purchases.Need.FOOD);
                    if (got > 0 && b.today(today).revenue > before) {
                        units += got;
                        sales++;
                        if (buyers.add(buyer.displayNameCap()) && seenBefore.contains(buyer.displayNameCap())) repeats++;
                    }
                }
                seenBefore.addAll(buyers);
                com.jrpetty.mcassistant.entity.Business.Day d1 = b.today(today);
                long lineUnits = d1.lineForTests(ware).units - d0.lineForTests(ware).units;
                ok.that(lineUnits == units, "day " + day + ": the books' carrots sold (" + lineUnits + ") are the carrots the folk bought (" + units + ")");
                ok.that(d1.trans - d0.trans == sales, "day " + day + ": its sales counted (" + (d1.trans - d0.trans) + " of " + sales + ")");
                ok.that(d1.customers - d0.customers == buyers.size(), "day " + day + ": its customers (" + (d1.customers - d0.customers) + " of " + buyers.size() + ")");
                ok.that(d1.repeats - d0.repeats == repeats, "day " + day + ": those who came back (" + (d1.repeats - d0.repeats) + " of " + repeats + ")");
                ok.that(d1.lineForTests(ware).in - d0.lineForTests(ware).in >= 10, "day " + day + ": its stock in (" + (d1.lineForTests(ware).in - d0.lineForTests(ware).in) + ")");
                long moved = cash(b) - cash0, says = booked(d1) - booked(d0);
                ok.that(moved == says, "day " + day + ": its till moved " + moved + " and its books say " + says
                    + " (takings " + (d1.revenue - d0.revenue) + ", stock " + (d1.bought - d0.bought) + ", taxes " + (d1.taxes - d0.taxes) + ", capital " + (d1.capital - d0.capital) + ")");
                ok.that(d1.net() == d1.revenue - d1.cogs - d1.wages - d1.rent - d1.other - d1.taxes - d1.losses - d1.interest, "day " + day + ": profit is takings less costs");
                unitsAll += units;
                salesAll += sales;
                // the morning: wages, rent, running costs, out of its till, onto tomorrow's book
                long cashM = cash(b);
                level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 7000L);
                long tomorrow = com.jrpetty.mcassistant.entity.Businesses.todayForTests(level);
                com.jrpetty.mcassistant.entity.Business.Day dm0 = copy(b.today(tomorrow));
                com.jrpetty.mcassistant.entity.Business.Day dy0 = copy(b.today(today));
                com.jrpetty.mcassistant.entity.Businesses.morningForTests(level, v);
                com.jrpetty.mcassistant.entity.Market.payWages(level, v);
                long movedM = cash(b) - cashM, saysM = booked(b.today(tomorrow)) - booked(dm0) + booked(b.today(today)) - booked(dy0);
                ok.that(movedM == saysM, "morning " + (day + 1) + ": its till moved " + movedM + " and its books say " + saysM
                    + " (wages " + (b.today(tomorrow).wages - dm0.wages + b.today(today).wages - dy0.wages) + ", rent " + (b.today(tomorrow).rent - dm0.rent + b.today(today).rent - dy0.rent) + ")");
            }
            com.jrpetty.mcassistant.entity.Purchases.openForTests(id, null);
            long lastDay = com.jrpetty.mcassistant.entity.Businesses.todayForTests(level);
            // the data the screen gets: its days and its wares, off the same books
            net.minecraft.nbt.CompoundTag t = com.jrpetty.mcassistant.entity.BizData.build(level, id, b, p, 7);
            ok.that(t.getBoolean("full"), "its owner sees it all");
            long dataUnits = 0, dataSales = 0, dataRev = 0, booksRev = 0;
            for (net.minecraft.nbt.Tag e : t.getList("days", net.minecraft.nbt.Tag.TAG_LONG_ARRAY)) {
                long[] r = ((net.minecraft.nbt.LongArrayTag) e).getAsLongArray();
                dataUnits += r[7];
                dataSales += r[8];
                dataRev += r[1];
                ok.that(r[6] == r[1] - r[2] - r[3] - r[4] - r[5], "day " + r[0] + ": the chart's profit is its takings less its costs");
            }
            for (long dd = lastDay - 6; dd <= lastDay; dd++) booksRev += b.last(dd, 1).revenue;
            ok.that(dataUnits == unitsAll, "the chart's units (" + dataUnits + ") are the units sold (" + unitsAll + ")");
            ok.that(dataSales == salesAll, "the chart's sales (" + dataSales + ") are the sales made (" + salesAll + ")");
            ok.that(dataRev == booksRev, "the chart's takings are the books' (" + dataRev + " / " + booksRev + ")");
            boolean carrots = false;
            for (net.minecraft.nbt.Tag e : t.getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag c = (net.minecraft.nbt.CompoundTag) e;
                if (c.getString("k").equals(ware)) carrots = c.getLongArray("v")[0] == unitsAll;
            }
            ok.that(carrots, "its wares table has the carrots, " + unitsAll + " sold");
            ok.that(!t.getString("advice").isEmpty(), "a word of advice: " + t.getString("advice"));
            Kit.log("[VT] bl33 " + (lastDay - firstDay) + " days, " + unitsAll + " carrots in " + salesAll + " sales; till " + b.till + "; advice: " + t.getString("advice"));
            if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
        });
    }

    static com.jrpetty.mcassistant.entity.Business.Day copy(com.jrpetty.mcassistant.entity.Business.Day d) {
        com.jrpetty.mcassistant.entity.Business.Day c = new com.jrpetty.mcassistant.entity.Business.Day();
        c.day = d.day;
        c.addAll(d);
        return c;
    }

    /** A busy day's trade written on a book: six wares sold and bought in, two hands paid, a few dozen buyers. */
    static void tradeFor(com.jrpetty.mcassistant.entity.Business b, long day, java.util.Random r, UUID[] hands) {
        com.jrpetty.mcassistant.entity.Business.Day d = b.today(day);
        String[] wares = { "minecraft:bread", "minecraft:cake", "minecraft:cookie", "minecraft:pumpkin_pie", "minecraft:wheat", "minecraft:sugar" };
        for (String k : wares) {
            com.jrpetty.mcassistant.entity.Business.Line l = d.lineForTests(k), a = b.allTime.lineForTests(k);
            long n = r.nextInt(30), rev = n * (150 + r.nextInt(100));
            l.units += n; l.revenue += rev; l.cogs += n * 90; l.in += r.nextInt(40); l.missed += r.nextInt(3);
            a.units += n; a.revenue += rev; a.cogs += n * 90;
            d.revenue += rev; d.cogs += n * 90;
            b.allTime.revenue += rev; b.allTime.cogs += n * 90;
        }
        for (UUID h : hands) {
            d.staffPay.merge(h, 400L, Long::sum);
            d.staffNames.put(h, "Wren");
            d.wages += 400;
            b.allTime.wages += 400;
        }
        int buyers = 20 + r.nextInt(20);
        for (int i = 0; i < buyers; i++) {
            b.buyer(d, "Folk" + r.nextInt(60), r.nextInt(10) == 0);
            d.hours[6 + r.nextInt(12)]++;
        }
        d.openMin += 720;
        d.keptMin += 600;
    }

    static int nbtBytes(net.minecraft.nbt.CompoundTag t, boolean compressed) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (compressed) net.minecraft.nbt.NbtIo.writeCompressed(t, out);
            else net.minecraft.nbt.NbtIo.write(t, new java.io.DataOutputStream(out));
            return out.size();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl34_the_ledger_keeps_small")
    public static void bl34_the_ledger_keeps_small(GameTestHelper helper) {
        Kit.Expect ok = new Kit.Expect();
        // eight weeks day by day, older days folded into weeks, nothing lost from the totals
        com.jrpetty.mcassistant.entity.Business b = new com.jrpetty.mcassistant.entity.Business("f1", com.jrpetty.mcassistant.entity.Business.Type.ENTERPRISE, "Wren's Bakery");
        java.util.Random r = new java.util.Random(7);
        UUID[] hands = { UUID.randomUUID() };
        for (long day = 0; day < 200; day++) tradeFor(b, day, r, hands);
        ok.that(b.days.size() == com.jrpetty.mcassistant.entity.Business.DAYS_KEPT, "eight weeks day by day (" + b.days.size() + ")");
        ok.that(!b.weeks.isEmpty() && b.weeks.size() <= com.jrpetty.mcassistant.entity.Business.WEEKS_KEPT, "the older days in weeks (" + b.weeks.size() + ")");
        com.jrpetty.mcassistant.entity.Business.Day sum = new com.jrpetty.mcassistant.entity.Business.Day();
        for (com.jrpetty.mcassistant.entity.Business.Day d : b.days) sum.addAll(d);
        for (com.jrpetty.mcassistant.entity.Business.Day w : b.weeks) sum.addAll(w);
        ok.that(sum.revenue == b.allTime.revenue, "takings: the days and the weeks add up to all time (" + sum.revenue + " / " + b.allTime.revenue + ")");
        ok.that(sum.wages == b.allTime.wages && sum.cogs == b.allTime.cogs, "wages and cost of goods add up");
        boolean noNames = true;
        for (com.jrpetty.mcassistant.entity.Business.Day w : b.weeks) noNames &= w.buyers.isEmpty();
        ok.that(noNames, "a week keeps counts, no buyers' names");
        com.jrpetty.mcassistant.entity.Business back = com.jrpetty.mcassistant.entity.Business.loadForTests(b.saveForTests());
        com.jrpetty.mcassistant.entity.Business.Day last = b.days.get(b.days.size() - 1), lb = back.days.get(back.days.size() - 1);
        ok.that(back.weeks.size() == b.weeks.size() && last.trans == lb.trans && last.customers == lb.customers && last.repeats == lb.repeats
            && last.keptMin == lb.keptMin && last.hours[10] == lb.hours[10] && last.lineForTests("minecraft:bread").missed == lb.lineForTests("minecraft:bread").missed
            && last.lineForTests("minecraft:bread").in == lb.lineForTests("minecraft:bread").in, "saved and loaded back the same");
        // a hundred and twenty busy businesses over four hundred days
        net.minecraft.nbt.ListTag town = new net.minecraft.nbt.ListTag();
        int one = 0;
        java.util.Random r2 = new java.util.Random(11);
        for (int i = 0; i < 120; i++) {
            com.jrpetty.mcassistant.entity.Business f = new com.jrpetty.mcassistant.entity.Business("f" + i, com.jrpetty.mcassistant.entity.Business.Type.ENTERPRISE, "Firm " + i);
            UUID[] hs = { UUID.randomUUID(), UUID.randomUUID() };
            for (long day = 0; day < 400; day++) tradeFor(f, day, r2, hs);
            net.minecraft.nbt.CompoundTag t = f.saveForTests();
            if (i == 0) one = nbtBytes(t, false);
            town.add(t);
        }
        net.minecraft.nbt.CompoundTag all = new net.minecraft.nbt.CompoundTag();
        all.put("biz", town);
        int raw = nbtBytes(all, false), packed = nbtBytes(all, true);
        Kit.log("[VT] bl34 LEDGER SIZE: one busy business over 400 days " + one + " bytes; 120 of them " + raw + " bytes raw, " + packed + " compressed");
        ok.that(one > 0 && one < 120_000, "one business over 400 days under 120 kB (" + one + ")");
        ok.that(packed > 0 && packed < 8_000_000, "the town's 120 under 8 MB compressed (" + packed + ")");
        if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bl35_the_data_packet")
    public static void bl35_the_data_packet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 6586000;
        List<VillageFolkEntity> folk = busyTown(helper, level, x, 12);
        UUID id = folk.get(0).ownerId();
        int y = Kit.surface(level, x, Z).getY();
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(folk.get(0).getX() + 1, y, folk.get(0).getZ());
        p.getInventory().clearContent();
        for (int k = 0; k < 3; k++) p.getInventory().setItem(k, new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 64));
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            com.jrpetty.mcassistant.entity.Business b = dataFirm(helper, level, p, id, ok);
            if (b == null) { helper.fail(ok.summary()); return; }
            long today = com.jrpetty.mcassistant.entity.Businesses.todayForTests(level);
            java.util.Random r = new java.util.Random(3);
            for (long d = today - 55; d <= today; d++) tradeFor(b, d, r, new UUID[]{ UUID.randomUUID() });
            for (int range : new int[]{ 7, 28, 56 }) {
                net.minecraft.nbt.CompoundTag t = com.jrpetty.mcassistant.entity.BizData.build(level, id, b, p, range);
                io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
                net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(raw, level.registryAccess());
                com.jrpetty.mcassistant.net.BizDataPayload.STREAM_CODEC.encode(buf, new com.jrpetty.mcassistant.net.BizDataPayload(t));
                int size = buf.readableBytes();
                com.jrpetty.mcassistant.net.BizDataPayload back = com.jrpetty.mcassistant.net.BizDataPayload.STREAM_CODEC.decode(buf);
                ok.that(back.data().equals(t), range + " days: the packet comes back the same");
                ok.that(back.data().getList("days", net.minecraft.nbt.Tag.TAG_LONG_ARRAY).size() == range, range + " days: a row a day ("
                    + back.data().getList("days", net.minecraft.nbt.Tag.TAG_LONG_ARRAY).size() + ")");
                ok.that(size < 64_000, range + " days: under 64 kB (" + size + " bytes)");
                Kit.log("[VT] bl35 " + range + " days: " + size + " bytes, " + back.data().getList("items", 10).size() + " wares");
            }
            // the ask, both ways
            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(raw, level.registryAccess());
            com.jrpetty.mcassistant.net.BizDataAskPayload.STREAM_CODEC.encode(buf, new com.jrpetty.mcassistant.net.BizDataAskPayload(b.id, 28));
            com.jrpetty.mcassistant.net.BizDataAskPayload ask = com.jrpetty.mcassistant.net.BizDataAskPayload.STREAM_CODEC.decode(buf);
            ok.that(ask.id().equals(b.id) && ask.range() == 28, "the ask comes back the same");
            // a stranger: the public summary only
            @SuppressWarnings("removal")
            net.minecraft.server.level.ServerPlayer other = helper.makeMockServerPlayerInLevel();
            net.minecraft.nbt.CompoundTag pub = com.jrpetty.mcassistant.entity.BizData.build(level, id, b, other, 28);
            ok.that(!pub.getBoolean("full") && !pub.contains("days") && !pub.contains("items") && pub.contains("p_week"),
                "a stranger gets the week's summary, not its days or wares");
            // two businesses: the roll-up
            com.jrpetty.mcassistant.entity.Business b2 = dataFirm(helper, level, p, id, ok);
            net.minecraft.nbt.CompoundTag t2 = com.jrpetty.mcassistant.entity.BizData.build(level, id, b, p, 28);
            ok.that(t2.getList("firms", 10).size() >= 2, "an owner of two gets them side by side (" + t2.getList("firms", 10).size() + ")");
            if (ok.clean()) helper.succeed(); else helper.fail(ok.summary());
        });
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
