package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.Muster;
import com.jrpetty.mcassistant.entity.Raids;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [defence] The town wall round the whole town (CastleWall, WallPlan) and the muster at the square in a raid (Muster).
 * A wall planned round a town encloses every lot it has built on and its square, and crosses no building, road, field
 * or water; a gatehouse on each avenue and a postern on a paved cross street; the walkway, the battlements, the towers,
 * their ladders and the steps up; the age's stone, paid out of the stores and refaced as the town comes of age; moved
 * out when the town grows past it, the old line taken down. And when the bell rings, every folk not of the watch
 * gathers in the middle of the square, the guards ring them round facing out (more than the ring wants go up on the
 * wall), the ring holds against what comes at it, and at the all-clear the folk go back to what they were doing.
 *
 * <p>Each test has its own batch and its own ground: x 1740000 to 1759999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DefenceGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    /** Level grass over earth, everything over it cleared, this far round. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -4; dy < 0; dy++) {
                    level.setBlock(p.set(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    if (!level.getBlockState(p.set(x, y + dy, z)).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    private static void ground(ServerLevel level, int cx, int cz, int r) {
        Kit.hold(level, cx, cz, r);
        Kit.prepare(level, cx, cz, r);
    }

    private static int[] box(TownPlan.Lot l) {
        boolean turned = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;
        return new int[]{ l.x(), l.z(), turned ? l.halfDeep() : l.halfAcross(), turned ? l.halfAcross() : l.halfDeep() };
    }

    /** The first ordinary lot of the plan whose box matches. */
    private static TownPlan.Lot lot(java.util.function.Predicate<int[]> where) {
        for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT && where.test(box(l))) return l;
        throw new IllegalStateException("no such lot");
    }

    /** A house on the books on each of these lots (the ground left as it is: only the plan is under test). */
    private static List<int[]> built(UUID village, BlockPos heart, List<TownPlan.Lot> lots) {
        List<int[]> out = new ArrayList<>();
        for (TownPlan.Lot l : lots) {
            Ledger.built(village, "house", heart.offset(l.x(), 0, l.z()), Villages.direction(l.back()).getOpposite());
            out.add(box(l));
        }
        return out;
    }

    /** Lots all round the first block, and these others. */
    private static List<TownPlan.Lot> firstBlock() {
        List<TownPlan.Lot> out = new ArrayList<>();
        out.add(lot(b -> b[1] - b[3] <= -38 + 10 && b[1] - b[3] >= -38 && Math.abs(b[0]) < 30));   // out to the north edge
        out.add(lot(b -> b[0] + b[2] >= 28 && b[0] + b[2] <= 38 && Math.abs(b[1]) < 30));          // the east
        out.add(lot(b -> b[1] + b[3] >= 28 && b[1] + b[3] <= 38 && Math.abs(b[0]) < 30));          // the south
        out.add(lot(b -> b[0] - b[2] <= -28 && b[0] - b[2] >= -38 && Math.abs(b[1]) < 30));        // the west
        out.add(lot(b -> Math.max(Math.abs(b[0]) + b[2], Math.abs(b[1]) + b[3]) <= 27));            // by the square
        return out;
    }

    private static TownPlan.Lot ringOneEast() {
        return lot(b -> b[0] - b[2] >= 53 && b[0] + b[2] <= 63 && Math.abs(b[1]) < 20);
    }

    private static int count(ServerLevel level, BlockPos heart, WallPlan.Sides s, java.util.function.Predicate<BlockState> what) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int[] col : WallPlan.columns(s, WallPlan.towers(s))) {
            for (int dy = -4; dy <= 16; dy++) {
                if (what.test(level.getBlockState(p.set(heart.getX() + col[0], heart.getY() + dy, heart.getZ() + col[1])))) n++;
            }
        }
        return n;
    }

    /** A marked store chest by the heart, filled with these. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        return box;
    }

    private static ItemStack[] stacks(net.minecraft.world.item.Item item, int n) {
        List<ItemStack> out = new ArrayList<>();
        while (n > 0) {
            int k = Math.min(64, n);
            out.add(new ItemStack(item, k));
            n -= k;
        }
        return out.toArray(new ItemStack[0]);
    }

    private static int stock(ServerLevel level, UUID village, net.minecraft.world.item.Item item) {
        return com.jrpetty.mcassistant.entity.Market.stock(level, village, s -> s.is(item));
    }

    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    private static VillageFolkEntity guard(ServerLevel level, BlockPos at) {
        VillageFolkEntity g = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        if (g == null) return null;
        if (!g.takeUpTrade(StationTask.GUARD)) g.setJob(StationTask.GUARD);
        g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        g.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        g.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        g.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        g.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        g.insertItem(new ItemStack(Items.BREAD, 16));
        return g;
    }

    private static double flatDist(net.minecraft.world.entity.Entity e, BlockPos p) {
        double dx = e.getX() - (p.getX() + 0.5), dz = e.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ dw01: the wall planned and built

    /**
     * A Stone Age town with houses on its first block all round and one on the next ring out to the east. Its wall is
     * planned on the plan's lines (42 out, 67 to the east, with the street before it clear) and enclosing every lot
     * built and the square; a lamp post, a field, a pond and a paved cross street on its line are left as they are (a
     * gap at the first three, a postern with doors under an arch at the street); a gatehouse on each avenue; the
     * walkway capped, the battlements, a lantern on the merlons, torches on the inner face; the towers with their
     * ladders and crenels; the steps up from inside; all in cobblestone; the ring kept off the lots.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "dw01_the_wall")
    public static void dw01_the_wall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1740000;
        ground(level, x, Z, 96);
        BlockPos heart = flat(level, x, Z, 84);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null, "a town");
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.STONE);
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        List<TownPlan.Lot> lots = firstBlock();
        lots.add(ringOneEast());
        List<int[]> boxes = built(village, heart, lots);
        // On the wall's line: a lamp post (north), a field (west), a pond (south); a paved street out north.
        BlockPos post = heart.offset(-30, 0, -43);
        level.setBlock(post, Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(post.above(), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), 3);
        BlockPos field = heart.offset(-43, -1, 20);
        level.setBlock(field, Blocks.FARMLAND.defaultBlockState(), 3);
        BlockPos pond = heart.offset(20, -1, 43);
        level.setBlock(pond, Blocks.WATER.defaultBlockState(), 3);
        for (int sx = 14; sx <= 16; sx++) {
            for (int sz = -30; sz >= -60; sz--) level.setBlock(heart.offset(sx, -1, sz), Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
        WallPlan.Sides s = CastleWall.plannedForTests(level, v);
        Kit.log("dw01 the wall planned: " + s);
        helper.assertTrue(s != null, "the wall planned (its ground loaded)");
        helper.assertTrue(s.equals(new WallPlan.Sides(42, 67, 42, 42)), "on the plan's lines, the east out a ring: " + s);
        for (int[] b : boxes) helper.assertTrue(WallPlan.encloses(s, b[0], b[1], b[2], b[3]), "the lot at " + b[0] + "," + b[1] + " inside the wall");
        helper.assertTrue(WallPlan.encloses(s, 0, 0, TownPlan.PLAZA, TownPlan.PLAZA), "the square inside the wall");
        // The ring kept off: the next ring's inward lots on the north side are, the first block's are not.
        TownPlan.Lot onRing = lot(b -> b[1] - b[3] <= -42 && b[1] - b[3] >= -52 && Math.abs(b[0]) < 30);
        helper.assertTrue(CastleWall.keptOff(village, onRing), "a lot on the wall's ring kept off");
        helper.assertTrue(!CastleWall.keptOff(village, lots.get(0)), "a first-block lot not kept off");

        long t0 = System.nanoTime();
        int laid = CastleWall.buildNow(level, v, true);
        int[] counts = CastleWall.countsForTests(village);
        Kit.log("dw01 built " + laid + " blocks in " + (System.nanoTime() - t0) / 1_000_000L + " ms: " + counts[0] + " of " + counts[1]
            + " laid, " + counts[2] + " left out | " + String.join(" | ", CastleWall.lines(level, v)));
        helper.assertTrue(counts[1] > 6000 && counts[0] * 100 >= counts[1] * 97, "the wall laid: " + counts[0] + " of " + counts[1]);

        // Nothing built over: the lamp post, the field and the pond as they were.
        helper.assertTrue(level.getBlockState(post).is(Blocks.OAK_FENCE) && level.getBlockState(post.above(2)).is(Blocks.LANTERN), "the lamp post left be");
        helper.assertTrue(level.getBlockState(field).is(Blocks.FARMLAND) && level.getBlockState(field.above()).isAir(), "the field left be");
        helper.assertTrue(level.getBlockState(pond).is(Blocks.WATER) && level.getBlockState(pond.above()).isAir(), "the pond left be");
        // The street out north: a postern, open over the paving inside, doors in the outer face, the wall over it.
        int topN = CastleWall.topForTests(village, TownPlan.NORTH, 15);
        for (int sx = 14; sx <= 16; sx++) {
            for (int h = 0; h <= 2; h++) {
                helper.assertTrue(level.getBlockState(heart.offset(sx, h, -42)).isAir(), "the postern open at " + sx + " h" + h);
            }
            helper.assertTrue(level.getBlockState(heart.offset(sx, 0, -44)).getBlock() instanceof DoorBlock, "a door in the postern at " + sx);
            helper.assertTrue(level.getBlockState(heart.offset(sx, 3, -42)).is(Blocks.COBBLESTONE), "the wall over the postern at " + sx);
            helper.assertTrue(level.getBlockState(heart.offset(sx, -1, -42)).is(Blocks.COBBLESTONE), "the street's paving under it");
        }
        // A gatehouse on each avenue: five doors in the gate, the flanking towers either side.
        int[] gateDoors = new int[4];
        for (BlockPos d : CastleWall.doorsForTests(village)) {
            int dx = d.getX() - heart.getX(), dz = d.getZ() - heart.getZ();
            for (int side = 0; side < 4; side++) {
                if (WallPlan.out(side, dx, dz) == s.of(side) + 2 && Math.abs(WallPlan.across(side, dx, dz)) <= 2) gateDoors[side]++;
            }
        }
        Kit.log("dw01 gate doors " + java.util.Arrays.toString(gateDoors) + ", all doors " + CastleWall.doorsForTests(village).size());
        for (int side = 0; side < 4; side++) helper.assertTrue(gateDoors[side] == 5, "a gate of five doors on side " + side + ": " + gateDoors[side]);
        for (BlockPos d : CastleWall.doorsForTests(village)) {
            helper.assertTrue(level.getBlockState(d).getBlock() instanceof DoorBlock, "a door hung at " + d.toShortString());
        }
        for (WallPlan.Tower t : WallPlan.towers(s)) {
            if (t.kind() != WallPlan.TowerKind.FLANK) continue;
            int[] m = t.middle();
            helper.assertTrue(level.getBlockState(heart.offset(m[0], 3, m[1])).is(Blocks.COBBLESTONE), "a gatehouse tower at " + m[0] + "," + m[1]);
        }
        // The walkway along the north: capped, clear over it, the battlements on its outer face.
        int top = CastleWall.topForTests(village, TownPlan.NORTH, -20);
        helper.assertTrue(top == heart.getY() + 5, "five high in the Stone Age: " + (top - heart.getY()));
        for (int ax = -21; ax <= -15; ax++) {                      // a plain run of curtain, between two towers
            for (int z = -42; z >= -43; z--) {
                helper.assertTrue(level.getBlockState(new BlockPos(heart.getX() + ax, top - 1, heart.getZ() + z)).is(Blocks.COBBLESTONE), "the walkway's cap at " + ax + "," + z);
                helper.assertTrue(level.getBlockState(new BlockPos(heart.getX() + ax, top, heart.getZ() + z)).isAir()
                    && level.getBlockState(new BlockPos(heart.getX() + ax, top + 1, heart.getZ() + z)).isAir(), "the walkway clear at " + ax + "," + z);
            }
            boolean merlon = Math.floorMod(ax, 2) == 0;
            BlockState b = level.getBlockState(new BlockPos(heart.getX() + ax, top, heart.getZ() - 44));
            helper.assertTrue(merlon ? b.is(Blocks.COBBLESTONE) : b.isAir(), "the battlements at " + ax + ": " + b);
        }
        helper.assertTrue(level.getBlockState(new BlockPos(heart.getX() - 16, top + 1, heart.getZ() - 44)).is(Blocks.LANTERN), "a lantern on a merlon");
        helper.assertTrue(level.getBlockState(new BlockPos(heart.getX() - 20, heart.getY() + 3, heart.getZ() - 41)).is(Blocks.WALL_TORCH), "a torch on the inner face");
        // The towers: a ladder up from the street inside each tower of a side, the corners crenellated.
        int ladders = count(level, heart, s, st -> st.is(Blocks.LADDER));
        int steps = count(level, heart, s, st -> st.is(Blocks.COBBLESTONE_STAIRS));
        Kit.log("dw01 ladders " + ladders + ", steps " + steps + ", towers " + WallPlan.towers(s).size());
        helper.assertTrue(ladders >= 6 * (WallPlan.towers(s).size() - 4), "ladders up the towers: " + ladders);
        helper.assertTrue(steps >= 8 * 5, "eight flights of five steps up: " + steps);
        WallPlan.Tower ne = WallPlan.towers(s).get(0);
        int cornerTop = top + 3;
        boolean crenel = false;
        for (int dy = -2; dy <= 3; dy++) {
            if (level.getBlockState(new BlockPos(heart.getX() + ne.x1(), cornerTop + dy, heart.getZ() + ne.z0())).is(Blocks.COBBLESTONE)
                && level.getBlockState(new BlockPos(heart.getX() + ne.x1(), cornerTop + dy + 1, heart.getZ() + ne.z0())).isAir()) crenel = true;
        }
        helper.assertTrue(crenel, "the north-east tower crenellated at its outer corner");
        // The Stone Age's stone, none of the other ages'.
        int cobble = count(level, heart, s, st -> st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE));
        int other = count(level, heart, s, st -> st.is(Blocks.STONE_BRICKS) || st.is(Blocks.DEEPSLATE_BRICKS) || st.is(BlockTags.LOGS));
        Kit.log("dw01 cobblestone " + cobble + ", other ages' " + other);
        helper.assertTrue(cobble > 5000 && other == 0, "cobblestone, as the Stone Age builds: " + cobble + " (other " + other + ")");
        CastleWall.standDownForTests(true);
        helper.succeed();
    }

    // ------------------------------------------------------------------ dw02: by age, out of the stores

    /**
     * A Wood Age palisade of logs, built for the pictures; then the town comes into the Iron Age with stone bricks and
     * smooth stone in its stores, and the wall is refaced and raised out of them, a block higher: every stone brick laid
     * came out of the stores, the stores kept back what the builders want, and the logs it took down went back in.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "dw02_by_age")
    public static void dw02_by_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1742000;
        ground(level, x, Z, 70);
        BlockPos heart = flat(level, x, Z, 60);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null, "a town");
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.WOOD);
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        built(village, heart, firstBlock());
        WallPlan.Sides s = CastleWall.plannedForTests(level, v);
        helper.assertTrue(WallPlan.first().equals(s), "the first wall: " + s);
        CastleWall.buildNow(level, v, true);
        int logs = count(level, heart, s, st -> st.is(BlockTags.LOGS));
        int planks = count(level, heart, s, st -> st.is(BlockTags.PLANKS));
        int woodTop = CastleWall.topForTests(village, TownPlan.NORTH, -20);
        Kit.log("dw02 the palisade: " + logs + " logs, " + planks + " planks, the walk " + (woodTop - heart.getY()) + " up");
        helper.assertTrue(logs > 4000 && planks > 300, "a log palisade with plank walks: " + logs + " logs, " + planks + " planks");

        // The Iron Age, and the stone for it in the stores: two chests short of the whole wall.
        Villages.ageForTests(village, Villages.Age.IRON);
        emptyStores(level, village);
        for (int i = 0; i < 4; i++) stores(level, heart, -6 + 2 * i, -6, stacks(Items.STONE_BRICKS, 27 * 64));
        stores(level, heart, 4, -6, stacks(Items.SMOOTH_STONE, 27 * 64));
        stores(level, heart, 6, -6);
        stores(level, heart, 8, -6);
        stores(level, heart, 10, -6);
        int bricks0 = stock(level, village, Items.STONE_BRICKS), smooth0 = stock(level, village, Items.SMOOTH_STONE);
        int set = CastleWall.buildNow(level, v, false);
        int bricks1 = stock(level, village, Items.STONE_BRICKS), smooth1 = stock(level, village, Items.SMOOTH_STONE);
        int logsBack = com.jrpetty.mcassistant.entity.Market.stock(level, village, st -> st.is(net.minecraft.tags.ItemTags.LOGS));
        int laidBricks = count(level, heart, s, st -> st.is(Blocks.STONE_BRICKS));
        int laidSmooth = count(level, heart, s, st -> st.is(Blocks.SMOOTH_STONE));
        int stairs = count(level, heart, s, st -> st.is(Blocks.STONE_BRICK_STAIRS));
        int ironTop = CastleWall.topForTests(village, TownPlan.NORTH, -20);
        Kit.log("dw02 the Iron Age: " + set + " set; bricks " + bricks0 + " -> " + bricks1 + " (laid " + laidBricks + " + " + stairs
            + " steps), smooth " + smooth0 + " -> " + smooth1 + " (laid " + laidSmooth + "), logs back " + logsBack
            + ", the walk " + (ironTop - heart.getY()) + " up | " + String.join(" | ", CastleWall.lines(level, v)));
        helper.assertTrue(laidBricks > 1000, "the wall refaced in stone bricks: " + laidBricks);
        helper.assertTrue(bricks0 - bricks1 == laidBricks + stairs, "every brick out of the stores: " + (bricks0 - bricks1) + " taken, "
            + (laidBricks + stairs) + " laid");
        helper.assertTrue(smooth0 - smooth1 == laidSmooth && laidSmooth > 100, "smooth stone caps out of the stores: " + laidSmooth);
        helper.assertTrue(bricks1 >= 48 || smooth1 >= 48, "the stores kept something back for the builders");
        helper.assertTrue(logsBack > 500, "the palisade's logs back into the stores: " + logsBack);
        helper.assertTrue(ironTop == woodTop + 1, "a block higher in the Iron Age: " + (woodTop - heart.getY()) + " -> " + (ironTop - heart.getY()));
        // The north gatehouse (the first section) all of stone now, six high.
        int gateLogs = 0;
        for (int dz = -48; dz <= -42; dz++) {
            for (int dx = -9; dx <= 9; dx++) {
                for (int dy = 0; dy <= 10; dy++) if (level.getBlockState(heart.offset(dx, dy, dz)).is(BlockTags.LOGS)) gateLogs++;
            }
        }
        helper.assertTrue(gateLogs == 0, "no log left in the north gatehouse: " + gateLogs);
        CastleWall.standDownForTests(true);
        helper.succeed();
    }

    // ------------------------------------------------------------------ dw03: moved out

    /**
     * A town with its wall up round its first block takes a lot past it to the east: the east line moves out a ring,
     * the new section goes up, the old east line comes down (its stone into the stores) and the lots on it are offered
     * again; the north and south walls run on out to the new corner.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "dw03_moved_out")
    public static void dw03_moved_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1744000;
        ground(level, x, Z, 96);
        BlockPos heart = flat(level, x, Z, 84);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null, "a town");
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.STONE);
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        built(village, heart, firstBlock());
        CastleWall.buildNow(level, v, true);
        WallPlan.Sides first = CastleWall.sides(village);
        helper.assertTrue(first.equals(WallPlan.first()), "the first wall: " + first);
        BlockPos oldEast = heart.offset(43, 2, 20);
        helper.assertTrue(level.getBlockState(oldEast).is(Blocks.COBBLESTONE), "the east wall up at 43");
        TownPlan.Lot inward = lot(b -> b[0] - b[2] >= 42 && b[0] - b[2] <= 52 && Math.abs(b[1]) < 30);
        helper.assertTrue(CastleWall.keptOff(village, inward), "the lot on the east wall's ring kept off");
        // The town takes a lot past the wall.
        built(village, heart, List.of(ringOneEast()));
        CastleWall.buildNow(level, v, true);
        WallPlan.Sides now = CastleWall.sides(village);
        Kit.log("dw03 the wall moved: " + first + " -> " + now + " | " + String.join(" | ", CastleWall.lines(level, v)));
        helper.assertTrue(now.equals(new WallPlan.Sides(42, 67, 42, 42)), "the east line out a ring: " + now);
        helper.assertTrue(level.getBlockState(heart.offset(68, 2, 20)).is(Blocks.COBBLESTONE), "the new east wall up at 68");
        helper.assertTrue(level.getBlockState(heart.offset(60, 2, -43)).is(Blocks.COBBLESTONE), "the north wall run on out to the new corner");
        int left = 0;
        for (int dz = -30; dz <= 30; dz++) {
            for (int dx = 42; dx <= 48; dx++) {
                for (int dy = 0; dy <= 10; dy++) {
                    BlockState b = level.getBlockState(heart.offset(dx, dy, dz));
                    if (b.is(Blocks.COBBLESTONE) || b.is(Blocks.LADDER) || b.getBlock() instanceof DoorBlock || b.is(Blocks.COBBLESTONE_STAIRS)) left++;
                }
            }
        }
        helper.assertTrue(left == 0, "the old east line taken down: " + left + " blocks of it left");
        helper.assertTrue(!CastleWall.keptOff(village, inward), "its line given back to the lots");
        boolean told = false;
        for (Villages.News n : Villages.news(village)) if (n.text().contains("old wall was taken down")) told = true;
        helper.assertTrue(told, "the town's news has the old wall coming down");
        CastleWall.standDownForTests(true);
        helper.succeed();
    }

    // ------------------------------------------------------------------ dw04: the muster

    /**
     * The bell rings on a town of seven folk (a child among them) and nine guards round a walled square. Every folk not
     * of the watch makes for the square and gathers in the middle of it, the child among the nearest the middle; as many
     * guards as the ring wants stand evenly round them, facing out, and those over go up on the wall with bows. Two
     * zombies come at the square: the ring steps out and kills them, and nobody of the town is hurt; the guards go back
     * to their places. At the all-clear the folk go back to what they were doing, and the history has the muster.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "dw04_muster")
    public static void dw04_muster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        final int x = 1746000;
        ground(level, x, Z, 64);
        BlockPos heart = flat(level, x, Z, 56);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 3000L);
        List<VillageFolkEntity> folk = new ArrayList<>();
        List<VillageFolkEntity> watch = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart.offset(30, 0, 0), 0.0F);
        helper.assertTrue(first != null, "a town");
        folk.add(first);
        UUID village = first.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.STONE);
        // The square's wall, as the builders leave it, and its gates and posts.
        BuildGoal.stamp(level, "fortify", v.centre(), Direction.NORTH, TownPlan.PLAZA,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Villages.noteProject(village, "fortify", level.getGameTime());
        Villages.builtAtForTests(village, "fortify", v.centre());
        com.jrpetty.mcassistant.entity.Watch.keep(level, v, true);
        int[][] out = { { 24, 6 }, { -26, 8 }, { 6, 28 }, { -8, -27 }, { 30, -20 }, { -30, -24 } };
        for (int[] o : out) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, v.centre().offset(o[0], 0, o[1]), 0.0F);
            helper.assertTrue(f != null, "a folk");
            folk.add(f);
        }
        VillageFolkEntity child = folk.get(1);
        child.setChild(true);
        child.bornDaysAgo(1);
        for (int i = 0; i < 9; i++) {
            VillageFolkEntity g = guard(level, v.centre().offset(-6 + (i % 3) * 6, 0, -6 + (i / 3) * 6));
            helper.assertTrue(g != null, "a guard");
            g.insertItem(new ItemStack(Items.BOW));
            g.insertItem(new ItemStack(Items.ARROW, 48));
            watch.add(g);
        }
        for (VillageFolkEntity f : folk) if (!f.isBaby()) f.setJob(StationTask.FARM);
        Muster.forTests(true);
        // The bell, for something on the approach that has not come yet.
        Raids.warAlarm(level, v, "raiders sighted", Direction.EAST, 1400L);
        BlockPos at = Muster.pointForTests(village);
        double[] size = Muster.sizeForTests(village);
        Kit.log("dw04 the muster at " + (at == null ? "nowhere" : at.toShortString()) + " (heart " + v.centre().toShortString()
            + "), crowd " + String.format("%.1f", size[0]) + ", ring " + String.format("%.1f", size[1]) + ", ring wants " + (int) size[2]);
        helper.assertTrue(at != null, "a place on the square");
        helper.assertTrue(Math.max(Math.abs(at.getX() - v.centre().getX()), Math.abs(at.getZ() - v.centre().getZ())) < TownPlan.PLAZA, "on the square");
        final int[] phase = { 0 };
        final long[] since = { 0 };
        final List<Mob> zombies = new ArrayList<>();
        final Map<UUID, Float> health = new HashMap<>();
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 40 == 0) {
                StringBuilder sb = new StringBuilder("dw04 t" + t + " phase " + phase[0] + " alarm " + Raids.underAlarm(village) + ": folk");
                for (VillageFolkEntity f : folk) sb.append(String.format(" %.1f", flatDist(f, at)));
                sb.append("; guards");
                for (VillageFolkEntity g : watch) sb.append(String.format(" %.1f%s", flatDist(g, at), g.holdingAPost() ? "^" : ""));
                Kit.log(sb.toString());
            }
            switch (phase[0]) {
                case 0 -> {
                    // Gathered: every folk in the crowd, every guard of the ring in its place, the two over on the wall.
                    boolean folkIn = true;
                    for (VillageFolkEntity f : folk) if (flatDist(f, at) > size[0] + 1.5) folkIn = false;
                    List<VillageFolkEntity> ring = Muster.ringForTests(level, v);
                    int wants = Math.min(watch.size(), (int) size[2]);
                    boolean ringed = ring.size() == wants;
                    for (VillageFolkEntity g : ring) if (Math.abs(flatDist(g, at) - size[1]) > 1.6) ringed = false;
                    int up = 0;
                    for (VillageFolkEntity g : watch) if (!ring.contains(g) && g.holdingAPost()) up++;
                    if (folkIn && ringed && up >= watch.size() - wants) {
                        Kit.log("dw04 gathered at tick " + t + ": the child " + String.format("%.1f", flatDist(child, at))
                            + " from the middle, " + up + " guards up on the wall");
                        helper.assertTrue(flatDist(child, at) <= 1.8, "the child in the middle: " + flatDist(child, at));
                        // Evenly round: no two of the ring within a quarter of an even share of each other.
                        List<Double> angles = new ArrayList<>();
                        for (VillageFolkEntity g : ring) angles.add(Math.atan2(g.getZ() - at.getZ() - 0.5, g.getX() - at.getX() - 0.5));
                        angles.sort(Double::compare);
                        double least = Double.MAX_VALUE;
                        for (int i = 0; i < angles.size(); i++) {
                            double d = (i + 1 < angles.size() ? angles.get(i + 1) : angles.get(0) + 2 * Math.PI) - angles.get(i);
                            least = Math.min(least, d);
                        }
                        helper.assertTrue(least > 2 * Math.PI / ring.size() * 0.5, "the ring spaced evenly: least gap " + least);
                        for (VillageFolkEntity f : folk) health.put(f.getUUID(), f.getHealth());
                        // Two zombies come at the square from the east.
                        for (int i = 0; i < 2; i++) {
                            Mob m = EntityType.ZOMBIE.create(level);
                            BlockPos zat = Kit.surface(level, at.getX() + 12, at.getZ() - 2 + 4 * i);
                            m.moveTo(zat.getX() + 0.5, zat.getY(), zat.getZ() + 0.5, 90.0F, 0.0F);
                            m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
                            m.setPersistenceRequired();
                            level.addFreshEntity(m);
                            m.setTarget(child);
                            zombies.add(m);
                        }
                        phase[0] = 1;
                        since[0] = t;
                    }
                    if (t > 1400) helper.fail("not gathered by tick 1400: folk in " + folkIn + ", ringed " + ringed + ", up " + up);
                }
                case 1 -> {
                    boolean dead = true;
                    for (Mob m : zombies) if (m.isAlive()) dead = false;
                    if (dead) {
                        for (VillageFolkEntity f : folk) {
                            helper.assertTrue(f.isAlive() && f.getHealth() >= health.get(f.getUUID()) - 0.01F,
                                "nobody of the town hurt: " + f.displayNameCap() + " " + f.getHealth());
                        }
                        Kit.log("dw04 the zombies down at tick " + t + " (" + (t - since[0]) + " ticks)");
                        phase[0] = 2;
                        since[0] = t;
                    } else if (t - since[0] > 600) helper.fail("the zombies still up after 600 ticks");
                }
                case 2 -> {
                    // Back to their places, and the ring holds till the bell stops.
                    if (t - since[0] == 160) {
                        int placed = 0;
                        for (VillageFolkEntity g : Muster.ringForTests(level, v)) if (Math.abs(flatDist(g, at) - size[1]) <= 2.0) placed++;
                        helper.assertTrue(placed >= 6, "the ring back in its places: " + placed);
                    }
                    if (!Raids.underAlarm(village)) {
                        Kit.log("dw04 the all-clear at tick " + t);
                        phase[0] = 3;
                        since[0] = t;
                    } else if (t > 3200) helper.fail("the bell still ringing at tick 3200");
                }
                case 3 -> {
                    if (t - since[0] >= 200) {
                        int away = 0;
                        for (VillageFolkEntity f : folk) if (f.isBaby() || f.onShift() || flatDist(f, at) > size[0] + 2) away++;
                        boolean told = false;
                        for (Villages.News n : Villages.news(village)) if (n.text().contains("gathered on the square")) told = true;
                        Kit.log("dw04 after the all-clear: " + away + " of " + folk.size() + " back to it; told " + told);
                        helper.assertTrue(away == folk.size(), "everybody back to what it was doing: " + away + " of " + folk.size());
                        helper.assertTrue(told, "the muster in the town's history");
                        Muster.forTests(null);
                        helper.succeed();
                    }
                }
                default -> { }
            }
        });
    }
}
