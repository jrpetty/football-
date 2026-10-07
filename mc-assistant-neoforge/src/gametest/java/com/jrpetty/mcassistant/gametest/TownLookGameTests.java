package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Allotments;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Avenues;
import com.jrpetty.mcassistant.entity.Bakery;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Inn;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Orchard;
import com.jrpetty.mcassistant.entity.Quarters;
import com.jrpetty.mcassistant.entity.StreetFurniture;
import com.jrpetty.mcassistant.entity.TownLook;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Windmill;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The town's look [batchE] (entity/TownLook and its parts), each called directly:
 * <ul>
 * <li><b>tl01</b>: saplings out of the stores on the avenues' verges midway between the lamp posts, never on
 *     the road, by a door or against a lamp post; and a grown tree's low leaves over the road trimmed off.</li>
 * <li><b>tl02</b>: benches at the street corners, window boxes under a well-off household's front windows,
 *     and the notice board by the square with the town's news.</li>
 * <li><b>tl03</b>: a household with no garden given a plot at the allotments, which it turns with a hoe, sows
 *     from the stores, and harvests, the vegetables carried home to its chest.</li>
 * <li><b>tl04</b>: four oaks planted in the orchard from the stores' saplings, grown, picked (the crown and
 *     the trunk into the stores) and planted again.</li>
 * <li><b>tl05</b>: the windmill's drawing, its place by the fields, its sails hung of the stores' wool, the
 *     stores' spare wheat carried up to it, and a farmer passing leaving its wheat there.</li>
 * <li><b>tl06</b>: the bakery baking bread, cookies, pumpkin pie and cake by the game's own recipes from the
 *     stores (the cake's buckets back); then fetching its wheat from the mill and baking twice the batch.</li>
 * <li><b>tl07</b>: the inn's sign, a player's room for the night (the coin into the till, the bed theirs and
 *     nobody else's), and a traveller from another town taking a room out of its own purse.</li>
 * </ul>
 *
 * <p>Each on its own ground (x 680,000 to 692,000, z 50,000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownLookGameTests {

    private static final String EMPTY = "empty";
    private static final long DAY = 24000L * 4;

    // ------------------------------------------------------------------ the ground and the town

    /** Flat grass round here, clear air above it. */
    private static void flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    /** A village's first folk on clean flat ground at x, z, by day. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(DAY + 3000);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, z, 56);
        Kit.prepare(level, x, z, 56);
        flat(level, x, z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    /** So many more folk of the village, stood up round here. */
    private static List<VillageFolkEntity> more(GameTestHelper helper, BlockPos at, UUID village, int n) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX() + 2 * i, at.getZ()), 0.0F);
            helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
            out.add(f);
        }
        return out;
    }

    /** Nothing in the village's stores (the founders' chest emptied): only what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    /** A chest of the village's stores this far from the heart, with these in it. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        Villages.forgetStores(Villages.nearest(level, heart, Villages.VILLAGE_RANGE).id());
        return box;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** A building stamped on the ground here, its back to the north, and booked as the village's. */
    private static BlockPos building(ServerLevel level, UUID village, String name, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, name, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, name, p, Direction.NORTH);
        return p;
    }

    private static String text(ServerLevel level, BlockPos sign, int line) {
        return level.getBlockEntity(sign) instanceof SignBlockEntity s ? s.getFrontText().getMessage(line, false).getString() : "";
    }

    // ============================================================ tl01: the avenues

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl01_avenues")
    public static void tl01_avenues(GameTestHelper helper) {
        int x = 680000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        more(helper, heart.offset(6, 0, 6), id, 5);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            emptyStores(level, id);
            Container box = stores(level, heart, 6, -6, new ItemStack(Items.OAK_SAPLING, 64));
            List<BlockPos> spots = Avenues.spotsForTests(level, v);
            helper.assertTrue(spots.size() >= 8, "the verges have their spots: " + spots.size() + " (reach " + Villages.townReach(id) + ")");
            // A door by the first spot, a lamp post against the second: neither gets a tree.
            BlockPos byDoor = spots.get(0), byPost = spots.get(1);
            int[] o = { byDoor.getX() - heart.getX(), byDoor.getZ() - heart.getZ() };
            BlockPos door = Math.abs(o[0]) == TownPlan.AVENUE + 1 ? byDoor.offset(2 * Integer.signum(o[0]), 0, 0) : byDoor.offset(0, 0, 2 * Integer.signum(o[1]));
            BlockState d = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
            level.setBlock(door, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2 | 16);
            level.setBlock(door.above(), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2 | 16);
            BlockPos post = byPost.offset(1, 0, 1);
            level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 2 | 16);
            level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 2 | 16);
            level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), 2 | 16);
            int before = count(box, s -> s.is(Items.OAK_SAPLING));
            int rounds = Avenues.roundsForTests(level, v, 40);
            List<BlockPos> planted = new ArrayList<>();
            for (BlockPos p : spots) if (level.getBlockState(p).getBlock() instanceof SaplingBlock) planted.add(p);
            int after = count(box, s -> s.is(Items.OAK_SAPLING));
            Kit.log("tl01 " + rounds + " rounds: " + planted.size() + " of " + spots.size() + " verge spots planted; saplings "
                + before + " -> " + after + "; by the door " + level.getBlockState(byDoor).getBlock() + ", by the post "
                + level.getBlockState(byPost).getBlock());
            helper.assertTrue(planted.size() >= 6, "trees along the avenues: " + planted.size());
            helper.assertTrue(before - after == planted.size(), "every one of them a sapling out of the stores: " + (before - after) + " for " + planted.size());
            helper.assertFalse(level.getBlockState(byDoor).getBlock() instanceof SaplingBlock, "no tree in front of a door");
            helper.assertFalse(level.getBlockState(byPost).getBlock() instanceof SaplingBlock, "no tree against a lamp post");
            for (BlockPos p : planted) {
                int dx = p.getX() - heart.getX(), dz = p.getZ() - heart.getZ();
                helper.assertFalse(TownPlan.isStreet(dx, dz), "never on the avenue itself: " + dx + ", " + dz);
                helper.assertTrue(Math.min(Math.abs(dx), Math.abs(dz)) == TownPlan.AVENUE + 1, "on the verge, just off the avenue: " + dx + ", " + dz);
            }
            // A grown tree, its crown hanging low over the avenue.
            BlockPos tree = planted.get(planted.size() - 1);
            int dx = tree.getX() - heart.getX(), dz = tree.getZ() - heart.getZ();
            boolean nsAvenue = Math.abs(dx) == TownPlan.AVENUE + 1;
            int sx = nsAvenue ? -Integer.signum(dx) : 0, sz = nsAvenue ? 0 : -Integer.signum(dz);      // toward the road
            for (int h = 0; h < 5; h++) level.setBlock(tree.above(h), Blocks.OAK_LOG.defaultBlockState(), 2 | 16);
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
            List<BlockPos> low = new ArrayList<>();
            for (int step = 1; step <= 2; step++) {
                for (int h = 1; h <= 2; h++) {
                    BlockPos q = tree.offset(sx * step, h, sz * step);
                    level.setBlock(q, leaf, 2 | 16);
                    low.add(q);
                }
            }
            BlockPos high = tree.offset(sx, 4, sz), lot = tree.offset(-sx, 1, -sz);
            level.setBlock(high, leaf, 2 | 16);
            level.setBlock(lot, leaf, 2 | 16);
            Avenues.roundsForTests(level, v, 30);
            int left = 0;
            for (BlockPos q : low) if (level.getBlockState(q).getBlock() instanceof LeavesBlock) left++;
            Kit.log("tl01 trimmed: " + (low.size() - left) + " of " + low.size() + " low leaves over the road; the high one "
                + level.getBlockState(high).getBlock() + ", the one over the lot " + level.getBlockState(lot).getBlock());
            helper.assertTrue(left == 0, "the leaves at head height over the road trimmed off: " + left + " left");
            helper.assertTrue(level.getBlockState(high).getBlock() instanceof LeavesBlock, "the crown above head height left alone");
            helper.assertTrue(level.getBlockState(lot).getBlock() instanceof LeavesBlock, "and what hangs over the lot behind");
            helper.assertTrue(level.getBlockState(tree).is(BlockTags.LOGS), "the tree itself stands");
            helper.succeed();
        });
    }

    // ============================================================ tl02: benches, window boxes, the notice board

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl02_street_furniture")
    public static void tl02_street_furniture(GameTestHelper helper) {
        int x = 682000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        List<VillageFolkEntity> folk = new ArrayList<>(more(helper, heart.offset(6, 0, 6), id, 5));
        folk.add(first);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            // The Stone Age: the Wood Age puts every plank by for itself, and the streets get none of them.
            Villages.ageForTests(id, Villages.Age.STONE);
            long day = level.getDayTime() / 24000L;
            BlockPos house = building(level, id, "house", heart.offset(-24, 0, 24));
            Villages.recountBeds(id);
            Homes.tickForTests(level, v);
            for (VillageFolkEntity f : folk) f.earn(200);                        // well off, all of them
            Villages.tell(id, day, "the well was dug deeper and the water came up sweet");
            emptyStores(level, id);
            Container box = stores(level, heart, 6, -6, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_SIGN, 2), new ItemStack(Items.FLOWER_POT, 2), new ItemStack(Items.POPPY, 6));
            int rounds = StreetFurniture.roundsForTests(level, v, 24);
            int[] counts = StreetFurniture.countsForTests(level, v);
            BlockPos[] notice = StreetFurniture.noticeForTests(level, v);
            List<BlockPos> pots = StreetFurniture.boxSpotsForTests(id, house);
            Kit.log("tl02 " + rounds + " rounds: " + counts[0] + " benches, " + counts[1] + " window boxes (house members "
                + Homes.membersForTests(id, house).size() + ", boxes at " + pots + "); notice " + (notice == null ? "none"
                : text(level, notice[0], 0) + " / " + text(level, notice[1], 0) + " " + text(level, notice[1], 1)) + "; stores: planks "
                + count(box, s -> s.is(ItemTags.PLANKS)) + ", stairs " + count(box, s -> s.is(ItemTags.WOODEN_STAIRS)) + ", pots "
                + count(box, s -> s.is(Items.FLOWER_POT)) + ", poppies " + count(box, s -> s.is(Items.POPPY)));
            helper.assertTrue(counts[0] >= 4, "benches on the street corners: " + counts[0]);
            for (BlockPos p : StreetFurniture.benchSpotsForTests(level, v)) {
                BlockState st = level.getBlockState(p.below());
                if (!(st.getBlock() instanceof StairBlock)) continue;
                helper.assertTrue(st.getValue(StairBlock.HALF) == Half.BOTTOM, "a bench is a stair the right way up: " + st);
                int dx = p.getX() - heart.getX(), dz = p.getZ() - heart.getZ();
                helper.assertFalse(TownPlan.isStreet(dx, dz), "a bench is on the corner, not in the street: " + dx + ", " + dz);
            }
            // Every stair and trapdoor made of the stores' planks by the game's recipes (six planks, four stairs; six,
            // two trapdoors), the ones not put out back in the stores; and never the builders' forty-eight planks.
            int stairsLeft = Market.stock(level, id, s -> s.is(ItemTags.WOODEN_STAIRS));
            int trapsLeft = Market.stock(level, id, s -> s.is(ItemTags.WOODEN_TRAPDOORS));
            int planksLeft = Market.stock(level, id, s -> s.is(ItemTags.PLANKS));
            int stairBatches = (stairsLeft + counts[0]) / 4, trapBatches = (trapsLeft + counts[1]) / 2;
            Kit.log("tl02 stairs left " + stairsLeft + ", trapdoors left " + trapsLeft + ", planks left " + planksLeft);
            helper.assertTrue((stairsLeft + counts[0]) % 4 == 0 && (trapsLeft + counts[1]) % 2 == 0
                    && 128 - planksLeft == 6 * (stairBatches + trapBatches),
                "the benches and the ledges made of the stores' planks, the rest put by: " + stairsLeft + " stairs, " + trapsLeft
                    + " trapdoors, " + planksLeft + " planks left");
            helper.assertTrue(planksLeft >= 48, "the builders' timber kept: " + planksLeft);
            helper.assertTrue(counts[1] >= 1 && !pots.isEmpty(), "a window box under the well-off household's window: " + counts[1]);
            for (BlockPos pot : pots) {
                BlockState st = level.getBlockState(pot);
                if (!(st.getBlock() instanceof FlowerPotBlock)) continue;
                helper.assertTrue(st.getBlock() != Blocks.FLOWER_POT, "a flower in the pot: " + st);
                BlockState ledge = level.getBlockState(pot.below());
                helper.assertTrue(ledge.getBlock() instanceof TrapDoorBlock && ledge.getValue(TrapDoorBlock.HALF) == Half.TOP,
                    "on a ledge under the window: " + ledge);
            }
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.FLOWER_POT)) == 2 - counts[1]
                    && Market.stock(level, id, s -> s.is(Items.POPPY)) == 6 - counts[1],
                "the pots and the flowers out of the stores, one each a box");
            helper.assertTrue(notice != null && level.getBlockState(notice[0]).getBlock() instanceof StandingSignBlock
                && level.getBlockState(notice[1]).getBlock() instanceof StandingSignBlock, "the notice board's two signs stand by the square");
            helper.assertTrue("Town News".equals(text(level, notice[0], 0)), "headed with the town's news: " + text(level, notice[0], 0));
            helper.assertTrue(!text(level, notice[1], 0).isEmpty(), "and the latest of it on the second sign");
            helper.assertTrue(count(box, s -> s.is(Items.OAK_SIGN)) == 0, "both signs out of the stores");
            helper.succeed();
        });
    }

    // ============================================================ tl03: the allotments

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl03_allotments")
    public static void tl03_allotments(GameTestHelper helper) {
        int x = 684000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        List<VillageFolkEntity> folk = new ArrayList<>(more(helper, heart.offset(6, 0, 6), id, 1));
        folk.add(first);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            BlockPos house = building(level, id, "house", heart.offset(-24, 0, 24));
            Villages.recountBeds(id);
            Homes.tickForTests(level, v);
            VillageFolkEntity member = null;
            for (VillageFolkEntity f : folk) if (house.equals(Homes.homeOf(f))) member = f;
            helper.assertTrue(member != null, "a household in the house");
            BlockPos plots = building(level, id, TownLook.ALLOTMENTS, heart.offset(28, 0, -28));
            emptyStores(level, id);
            Container box = stores(level, heart, 6, -6, new ItemStack(Items.CARROT, 20), new ItemStack(Items.POTATO, 20),
                new ItemStack(Items.STONE_HOE));
            int let = Allotments.letForTests(id);
            helper.assertTrue(let >= 1 && Allotments.hasPlotForTests(member), "the household with no garden has a plot: " + let);
            List<BlockPos> soil = Allotments.plotForTests(member);
            member.removeMatching(s -> s.getItem() instanceof net.minecraft.world.item.HoeItem, 64);   // the stores' hoe, then
            int seedBefore = count(box, s -> s.is(Items.CARROT) || s.is(Items.POTATO));
            int[] sown = Allotments.tendForTests(level, member, 40);
            int furrows = 0, crops = 0;
            for (BlockPos p : soil) {
                if (level.getBlockState(p).is(Blocks.FARMLAND)) furrows++;
                if (level.getBlockState(p.above()).getBlock() instanceof CropBlock) crops++;
            }
            ItemStack hoe = ItemStack.EMPTY;
            for (BlockPos at : Villages.storeChests(level, id)) {
                if (!(level.getBlockEntity(at) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.STONE_HOE)) hoe = c.getItem(i);
            }
            int seedAfter = count(box, s -> s.is(Items.CARROT) || s.is(Items.POTATO));
            Kit.log("tl03 the first evening: " + sown[0] + " things done; " + furrows + " furrows, " + crops + " sown; seed "
                + seedBefore + " -> " + seedAfter + "; the hoe " + (hoe.isEmpty() ? "gone" : hoe.getDamageValue() + " worn") + "; plots at " + plots);
            helper.assertTrue(furrows == soil.size() && crops == soil.size(), "the plot turned and sown: " + furrows + ", " + crops);
            helper.assertTrue(seedBefore - seedAfter == soil.size(), "the seed out of the stores, one a furrow: " + (seedBefore - seedAfter));
            helper.assertTrue(!hoe.isEmpty() && hoe.getDamageValue() == soil.size(), "the stores' hoe borrowed, put back the worse for it");
            // A few weeks on: everything ripe.
            for (BlockPos p : soil) {
                BlockState st = level.getBlockState(p.above());
                if (st.getBlock() instanceof CropBlock c) level.setBlock(p.above(), c.getStateForAge(c.getMaxAge()), 2 | 16);
            }
            BlockPos chestAt = null;
            for (BuildGoal.Placement p : BuildGoal.plan("house", house, Direction.NORTH, 13)) if (p.part() == BuildGoal.Part.CHEST) chestAt = p.pos();
            Container home = chestAt != null && level.getBlockEntity(chestAt) instanceof Container c ? c : null;
            helper.assertTrue(home != null, "the house has its chest");
            Predicate<ItemStack> veg = s -> s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.POISONOUS_POTATO);
            int[] picked = Allotments.tendForTests(level, member, 40);
            int again = 0;
            for (BlockPos p : soil) {
                BlockState st = level.getBlockState(p.above());
                if (st.getBlock() instanceof CropBlock c && !c.isMaxAge(st)) again++;
            }
            Kit.log("tl03 the harvest: " + picked[0] + " pulled up, " + picked[1] + " carried home; the chest has " + count(home, veg)
                + "; " + again + " put back in the ground");
            helper.assertTrue(picked[0] == soil.size(), "every ripe crop pulled up: " + picked[0]);
            helper.assertTrue(picked[1] >= 1 && count(home, veg) == picked[1], "the vegetables carried home to the household's chest: " + picked[1]);
            helper.assertTrue(again == soil.size(), "and one of each put back in the ground for the next crop: " + again);
            helper.assertTrue(TownLook.cardLine(member) != null && TownLook.cardLine(member).contains("allotment"),
                "its card says so: " + TownLook.cardLine(member));
            helper.succeed();
        });
    }

    // ============================================================ tl04: the orchard

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl04_orchard")
    public static void tl04_orchard(GameTestHelper helper) {
        int x = 686000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            building(level, id, TownLook.ORCHARD, heart.offset(28, 0, 28));
            emptyStores(level, id);
            Container box = stores(level, heart, 6, -6, new ItemStack(Items.OAK_SAPLING, 6), new ItemStack(Items.BIRCH_SAPLING, 6));
            Orchard.roundsForTests(level, v, 8);
            List<BlockPos> trees = Orchard.treesForTests(id);
            int saplings = 0;
            for (BlockPos p : trees) if (level.getBlockState(p).is(Blocks.OAK_SAPLING)) saplings++;
            Kit.log("tl04 planted " + saplings + " of " + trees.size() + "; oak saplings left " + count(box, s -> s.is(Items.OAK_SAPLING))
                + ", birch " + count(box, s -> s.is(Items.BIRCH_SAPLING)));
            helper.assertTrue(saplings == 4, "four oaks in the orchard: " + saplings);
            helper.assertTrue(count(box, s -> s.is(Items.OAK_SAPLING)) == 2 && count(box, s -> s.is(Items.BIRCH_SAPLING)) == 6,
                "oak saplings out of the stores (the apple tree), and no birch");
            int grown = Orchard.growForTests(level, id);
            helper.assertTrue(grown >= 2, "the oaks grow as the game grows them: " + grown);
            int logsBefore = Market.stock(level, id, s -> s.is(ItemTags.LOGS));
            int[] got = Orchard.pickForTests(level, v);
            int replanted = 0;
            for (BlockPos p : trees) if (level.getBlockState(p).is(Blocks.OAK_SAPLING)) replanted++;
            int leavesLeft = 0;
            for (BlockPos p : trees) {
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 0, -2), p.offset(2, 8, 2))) {
                    if (level.getBlockState(q).getBlock() instanceof LeavesBlock) leavesLeft++;
                }
            }
            int logsInStores = Market.stock(level, id, s -> s.is(ItemTags.LOGS)) - logsBefore;
            Kit.log("tl04 picked " + grown + " trees: " + got[0] + " leaves down, " + got[1] + " apples, " + got[2] + " logs ("
                + logsInStores + " into the stores), " + got[3] + " replanted; " + leavesLeft + " leaves left; apples in the stores "
                + Market.stock(level, id, s -> s.is(Items.APPLE)));
            helper.assertTrue(got[0] >= 20 * grown, "each crown taken down leaf by leaf: " + got[0]);
            helper.assertTrue(got[2] >= 4 * grown && logsInStores == got[2], "the trunks felled into the stores: " + got[2] + ", " + logsInStores);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.APPLE)) == got[1], "whatever apples fell, into the stores: " + got[1]);
            helper.assertTrue(got[3] == grown && replanted == 4, "an oak back in the ground where each stood: " + got[3] + ", " + replanted);
            helper.succeed();
        });
    }

    // ============================================================ tl05: the windmill

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl05_windmill")
    public static void tl05_windmill(GameTestHelper helper) {
        int x = 688000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            helper.assertTrue(BuildGoal.STRUCTURES.contains(TownLook.WINDMILL) && Blueprints.has(TownLook.WINDMILL), "the builders can build a windmill");
            int top = 0;
            for (Blueprints.Cell c : Blueprints.cells(TownLook.WINDMILL)) top = Math.max(top, c.h());
            helper.assertTrue(top >= 10, "a tall tower: " + top);
            // Its place: by the farm gate, once the village has its fields.
            Villages.setFieldsSide(id, TownPlan.EAST);
            List<TownPlan.Lot> lots = Quarters.candidates(id, TownLook.WINDMILL);
            TownPlan.Lot lot = lots.get(0);
            Kit.log("tl05 the windmill's first lot: " + lot.x() + ", " + lot.z() + " (" + lot.use() + ")");
            helper.assertTrue(lot.x() > 0 && Math.abs(lot.z()) < lot.x(), "the mill's first lot is out toward the fields: " + lot.x() + ", " + lot.z());
            helper.assertTrue(TownPlan.placeFor(TownLook.WINDMILL).equals("fields"), "a place of the farmland's");
            // A farming town of sixteen wants one.
            List<String> extras = new ArrayList<>();
            first.setJob(StationTask.FARM);
            TownLook.wanted(id, 16, Villages.Age.STONE, extras, s -> true);
            helper.assertTrue(extras.contains(TownLook.WINDMILL) && !extras.contains(TownLook.INN), "a farming town of sixteen in the Stone Age wants a mill: " + extras);
            first.setJob(StationTask.NONE);
            BlockPos mill = building(level, id, TownLook.WINDMILL, heart.offset(0, 0, -36));
            emptyStores(level, id);
            Container box = stores(level, heart, 6, -6, new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64),
                new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 8), new ItemStack(Items.WHITE_WOOL, 16));
            int rounds = Windmill.roundsForTests(level, v, 12);
            int hung = 0;
            for (BlockPos p : Windmill.clothForTests(id)) if (level.getBlockState(p).is(BlockTags.WOOL)) hung++;
            int grain = Windmill.grainForTests(level, id), left = count(box, s -> s.is(Items.WHEAT));
            Kit.log("tl05 " + rounds + " rounds: " + hung + " pieces of sail hung (wool left " + count(box, s -> s.is(Items.WHITE_WOOL))
                + "); " + grain + " wheat up at the mill, " + left + " left in the stores");
            helper.assertTrue(hung == 12 && count(box, s -> s.is(Items.WHITE_WOOL)) == 4, "the sails hung, of the stores' wool: " + hung);
            helper.assertTrue(grain + left == 200 && left >= 64 && grain >= 100,
                "the stores' spare wheat carried up to the mill, the larder's kept back: " + grain + " up, " + left + " kept");
            // A farmer passing with a load leaves it there.
            first.setJob(StationTask.FARM);
            BlockPos door = Windmill.doorForTests(id);
            first.moveTo(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, 0.0F, 0.0F);
            first.insertItem(new ItemStack(Items.WHEAT, 20));
            int carried = first.countCarried(s -> s.is(Items.WHEAT));
            Windmill.roundsForTests(level, v, 1);
            Kit.log("tl05 the farmer passing: carried " + carried + ", now " + first.countCarried(s -> s.is(Items.WHEAT)) + "; the mill "
                + Windmill.grainForTests(level, id) + " (" + mill + ")");
            helper.assertTrue(first.countCarried(s -> s.is(Items.WHEAT)) == 0 && Windmill.grainForTests(level, id) == grain + carried,
                "a farmer coming past leaves its wheat at the mill");
            helper.succeed();
        });
    }

    // ============================================================ tl06: the bakery

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl06_bakery")
    public static void tl06_bakery(GameTestHelper helper) {
        int x = 690000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            building(level, id, TownLook.BAKERY, heart.offset(-16, 0, 16));
            emptyStores(level, id);
            Container box = stores(level, heart, 6, -6, new ItemStack(Items.WHEAT, 40), new ItemStack(Items.COCOA_BEANS, 4),
                new ItemStack(Items.PUMPKIN, 2), new ItemStack(Items.SUGAR_CANE, 6), new ItemStack(Items.EGG, 6),
                new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET));
            List<ItemStack> made = Bakery.bakeForTests(level, v, 8);
            List<Item> kinds = new ArrayList<>();
            for (ItemStack s : made) if (!kinds.contains(s.getItem())) kinds.add(s.getItem());
            int wheat = Market.stock(level, id, s -> s.is(Items.WHEAT)), buckets = Market.stock(level, id, s -> s.is(Items.BUCKET));
            Kit.log("tl06 baked " + made + "; the stores: wheat " + wheat + ", cocoa " + Market.stock(level, id, s -> s.is(Items.COCOA_BEANS))
                + ", pumpkins " + Market.stock(level, id, s -> s.is(Items.PUMPKIN)) + ", cane " + Market.stock(level, id, s -> s.is(Items.SUGAR_CANE))
                + ", eggs " + Market.stock(level, id, s -> s.is(Items.EGG)) + ", milk " + Market.stock(level, id, s -> s.is(Items.MILK_BUCKET))
                + ", empty buckets " + buckets);
            for (Item it : new Item[]{ Items.BREAD, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE }) {
                helper.assertTrue(kinds.contains(it), "the bakery baked " + it + ": " + made);
                helper.assertTrue(Market.stock(level, id, s -> s.is(it)) >= 1, "and it is in the stores for the café and the feasts: " + it);
            }
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.COCOA_BEANS)) < 4 && Market.stock(level, id, s -> s.is(Items.PUMPKIN)) < 2
                && Market.stock(level, id, s -> s.is(Items.SUGAR_CANE)) < 6 && Market.stock(level, id, s -> s.is(Items.EGG)) < 6,
                "every bake out of the stores' makings: cocoa, pumpkins, cane (for sugar), eggs");
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.MILK_BUCKET)) == 0 && buckets == 3, "the cake's three milk buckets, the buckets back");
            helper.assertTrue(wheat >= 12, "never the farmers' last twelve wheat: " + wheat);
            // With a windmill: the baker fetches its wheat from the mill, and bakes twice the batch.
            BlockPos mill = building(level, id, TownLook.WINDMILL, heart.offset(0, 0, -36));
            emptyStores(level, id);
            Container millChest = null;
            for (BuildGoal.Placement p : BuildGoal.plan(TownLook.WINDMILL, mill, Direction.NORTH, 13)) {
                if (p.part() == BuildGoal.Part.CHEST && level.getBlockEntity(p.pos()) instanceof Container c) { millChest = c; break; }
            }
            helper.assertTrue(millChest != null, "the mill has its chests");
            millChest.setItem(0, new ItemStack(Items.WHEAT, 64));
            millChest.setChanged();
            List<ItemStack> milled = Bakery.bakeForTests(level, v, 3);              // a sack, another, and the batch
            int grain = Windmill.grainForTests(level, id);
            Kit.log("tl06 with the mill: baked " + milled + "; the mill has " + grain + " wheat left");
            helper.assertTrue(!milled.isEmpty() && milled.get(0).is(Items.BREAD) && milled.get(0).getCount() == 6,
                "twice the batch of bread, of the mill's wheat: " + milled);
            helper.assertTrue(grain == 64 - 54, "two sacks fetched from the mill: " + grain);
            helper.succeed();
        });
    }

    // ============================================================ tl07: the inn

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl07_inn")
    public static void tl07_inn(GameTestHelper helper) {
        int x = 692000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        Kit.hold(level, x + 400, z, 24);
        Kit.prepare(level, x + 400, z, 24);
        VillageFolkEntity stranger = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 400, z), 0.0F);
        helper.assertTrue(stranger != null && !id.equals(stranger.ownerId()), "a folk of another town");
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            BlockPos inn = building(level, id, TownLook.INN, heart.offset(-20, 0, 20));
            first.setJob(StationTask.COOK);
            emptyStores(level, id);
            stores(level, heart, 6, -6, new ItemStack(Items.OAK_SIGN, 2));
            Inn.tickForTests(level, v);
            Object[] sk = Inn.signAndKeeperForTests(id);
            BlockPos sign = (BlockPos) sk[0];
            helper.assertTrue(sk[1] == first, "the café's cook keeps the inn");
            helper.assertTrue(level.getBlockState(sign).getBlock() instanceof WallSignBlock && "The Inn".equals(text(level, sign, 0)),
                "the inn's sign up by its door: " + level.getBlockState(sign) + " '" + text(level, sign, 0) + "'");
            List<BlockPos> beds = Inn.bedsForTests(level, id);
            helper.assertTrue(beds.size() == 4, "four beds in its rooms: " + beds.size());
            helper.assertTrue(Inn.isInnBed(id, beds.get(0)), "not a bed for the town's own folk");
            // A player takes a room.
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
            int till = Ledger.coins(id);
            String said = Inn.rent(level, v, p);
            BlockPos room = Inn.roomForTests(level, id, p.getUUID());
            Kit.log("tl07 the player: \"" + said + "\"; room " + room + "; coins " + Market.coinsHeld(p) + "; till " + till + " -> " + Ledger.coins(id));
            helper.assertTrue(room != null && Market.coinsHeld(p) == 10 - 3 && Ledger.coins(id) == till + 3,
                "a room for three coins, into the till: " + said);
            helper.assertTrue(Inn.maySleepForTests(level, room, p.getUUID()), "the player may sleep in its bed");
            Player other = helper.makeMockPlayer(GameType.SURVIVAL);
            helper.assertFalse(Inn.maySleepForTests(level, room, other.getUUID()), "and nobody else may");
            helper.assertTrue(Inn.rent(level, v, p).contains("already"), "one room a night is enough");
            // A traveller from another town takes a room out of its own purse.
            stranger.moveTo(heart.getX() - 14.5, heart.getY(), heart.getZ() + 14.5, 0.0F, 0.0F);
            stranger.earn(10);
            int purse = stranger.purse(), before = Ledger.coins(id);
            BlockPos lodged = Inn.lodgeForTests(level, stranger);
            Kit.log("tl07 the traveller from " + Villages.name(stranger.ownerId()) + ": bed " + lodged + ", purse " + purse + " -> "
                + stranger.purse() + ", till " + before + " -> " + Ledger.coins(id) + "; the inn: " + TownLook.boardLine(level, id)
                + " (" + inn + ")");
            helper.assertTrue(lodged != null && !lodged.equals(room), "a bed of its own at the inn, not the player's: " + lodged);
            helper.assertTrue(stranger.purse() == purse - 3 && Ledger.coins(id) == before + 3, "paid for out of its own purse, into the till");
            helper.assertTrue(Inn.lodged(stranger), "lodging there tonight");
            helper.succeed();
        });
    }
}
