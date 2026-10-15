package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Decor;
import com.jrpetty.mcassistant.entity.HomeMaking;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Housekeeping;
import com.jrpetty.mcassistant.entity.Interiors;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Needs;
import com.jrpetty.mcassistant.entity.Seasons;
import com.jrpetty.mcassistant.entity.ServicePoints;
import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.ShopStock;
import com.jrpetty.mcassistant.entity.ShoppingTrips;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [home-making] Every folk furnishes its own home, and keeps it stocked (HomeMaking, Housekeeping).
 * <ul>
 * <li><b>hm21</b>: a poor woodcutter and a well-off smith move into new houses and, over a few days, set them up
 *     themselves: the basics for both (the woodcutter's made of its own planks, the smith's bought at the shop out of
 *     its purse, the coin moved and none made or lost), its own things only for the smith; every piece the right way
 *     round (the seat facing the table, the chest's and the stove's fronts to the room, the barrel on its end, the door
 *     and the beds left their way in).</li>
 * <li><b>hm22</b>: a household with nothing (no coin, no trade to make with, no shop) is given the basics by the parish,
 *     out of the town's stores.</li>
 * <li><b>hm23</b>: the town's own furnishing (Interiors, Decor) leaves a lived-in home to its household, and still does
 *     an empty one.</li>
 * <li><b>hm24</b>: a household that moves takes its table and its seat with it, set out again in its new home.</li>
 * <li><b>hm25</b>: a player's present of a barrel is set out as the larder; burnt or broken, it is made good.</li>
 * <li><b>hm31</b>: a household's days at home: breakfast out of the larder, the tool home at night and out in the
 *     morning, a bucket bought for the water and the water fetched from the pond, its stocks on its card.</li>
 * <li><b>hm32</b>: a household with a bare larder borrows of a friend's, and pays it back when its own is full.</li>
 * <li><b>hm33</b>: the autumn puts the winter's larder and fuel on the shopping list; a winter evening with no fuel is a
 *     cold house; with fuel, the stove is fed.</li>
 * <li><b>hm34</b>: raw meat left in the larder goes off; raw food is cooked on the home's stove with its own fuel.</li>
 * </ul>
 *
 * <p>Each on its own ground, x 2,460,000 to 2,499,999 at z 70,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class HomeMakingGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    // ------------------------------------------------------------------ the ground

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, VillageFolkEntity first) {}

    /** A town of one folk (the first, to be given a trade), its stores a storehouse; a shop with its counter if asked. */
    private static Town town(GameTestHelper helper, int x, boolean shop, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(time);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Villages.Village v = Villages.get(id);
        if (shop) {
            first.setJob(StationTask.SHOP);
            fill(id, store, new ItemStack(Items.OAK_PLANKS, 64));
            BlockPos shopAt = Kit.surface(level, x - 40, Z - 20);
            BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "shop", shopAt, Direction.NORTH);
            Villages.builtAtForTests(id, "shop", shopAt);
            ServicePoints.fitOutForTests(level, v);
            List<ServicePoints.Point> points = ServicePoints.of(level, id, ServiceRole.SHOP);
            ServicePoints.Point point = points.isEmpty() ? null : points.get(0);
            if (point != null && point.stand() != null) first.teleportTo(point.stand().getX() + 0.5, point.stand().getY(), point.stand().getZ() + 0.5);
        }
        return new Town(id, v, heart, store, first);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) level.setBlock(origin.offset(x, y, z), unit, 3);
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    private static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    private static void fill(UUID id, StorehouseBlockEntity store, ItemStack... goods) {
        for (ItemStack g : goods) {
            ItemStack left = g.copy();
            for (int i = 0; i < store.getContainerSize() && !left.isEmpty(); i++) {
                if (store.getItem(i).isEmpty()) {
                    store.setItem(i, left.copy());
                    left = ItemStack.EMPTY;
                }
            }
        }
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.Budget.forget(id);
    }

    /** A house stamped down and on the town's books. */
    private static BlockPos house(ServerLevel level, UUID village, BlockPos heart, int dx, int dz) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", at, Direction.NORTH);
        return at;
    }

    private static VillageFolkEntity folk(GameTestHelper helper, ServerLevel level, Town t, int dx, StationTask job) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, t.heart().south(2).east(dx), 0.0F);
        helper.assertTrue(f != null && t.id().equals(f.ownerId()), "a folk of the town");
        f.setJob(job);
        f.getInventoryItems().clear();
        return f;
    }

    /** This many of a thing, its own (a keepsake: brought with it), into its pack. */
    private static void own(VillageFolkEntity f, ItemStack s) {
        Homes.keepsake(s, f, Homes.Source.KIT);
        f.insertItem(s);
    }

    /** The coin there is, to the penny, wherever it is (CoinCensus: the treasury, the purses, the business tills, the poor
     *  box...): a sale moves it from a purse to a till; none is made or lost. */
    private static long money(UUID village) {
        return com.jrpetty.mcassistant.entity.CoinCensus.count(village);
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return ShopStock.count(level, village, what);
    }

    private static List<BlockPos> find(ServerLevel level, BlockPos house, Predicate<BlockState> what) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(house.offset(-4, -1, -4), house.offset(4, 9, 4))) {
            if (what.test(level.getBlockState(p))) out.add(p.immutable());
        }
        return out;
    }

    /** The house's own chest (the drawing's), to put the household's things in. */
    private static Container chestOf(ServerLevel level, BlockPos house) {
        for (BlockPos p : find(level, house, s -> s.getBlock() instanceof ChestBlock)) {
            if (level.getBlockEntity(p) instanceof Container c) return c;
        }
        return null;
    }

    private static void put(Container c, ItemStack s) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) { c.setItem(i, s); c.setChanged(); return; }
        }
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /**
     * Everything the household set out, looked at against the checklist: the table a post with a board on it, each seat
     * a stair the right way up with its back to the table, a chest's and a stove's front to an open cell, the larder on
     * its end, nothing in the doorway, and every bed with a free cell to be got into from. The faults, in words.
     */
    private static List<String> faults(ServerLevel level, UUID id, BlockPos home) {
        List<String> out = new ArrayList<>();
        BlockPos table = HomeMaking.pieceForTests(id, home, "table");
        if (table != null) {
            if (!(level.getBlockState(table).getBlock() instanceof FenceBlock)) out.add("the table's post is " + level.getBlockState(table));
            if (!(level.getBlockState(table.above()).getBlock() instanceof PressurePlateBlock) && !level.getBlockState(table.above()).is(BlockTags.WOOL_CARPETS)) {
                out.add("the table has no top");
            }
        }
        for (int i = 0; i < 4; i++) {
            BlockPos seat = HomeMaking.pieceForTests(id, home, "seat." + i);
            if (seat == null) continue;
            BlockState s = level.getBlockState(seat);
            if (!(s.getBlock() instanceof StairBlock)) { out.add("seat." + i + " is " + s); continue; }
            if (s.getValue(StairBlock.HALF) != Half.BOTTOM) out.add("seat." + i + " upside down");
            if (table == null || !seat.relative(s.getValue(StairBlock.FACING).getOpposite()).equals(table)) {
                out.add("seat." + i + " at " + seat.toShortString() + " faces " + s.getValue(StairBlock.FACING) + ", not the table at " + table);
            }
        }
        for (String key : new String[]{ "chest", "stove", "larder", "cauldron" }) {
            BlockPos p = HomeMaking.pieceForTests(id, home, key);
            if (p == null) continue;
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof BarrelBlock) {
                if (s.getValue(BarrelBlock.FACING) != Direction.UP) out.add(key + " barrel on its side facing " + s.getValue(BarrelBlock.FACING));
                continue;
            }
            if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                BlockPos front = p.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING));
                if (!level.getBlockState(front).getCollisionShape(level, front).isEmpty() && !level.getBlockState(front).is(BlockTags.WOOL_CARPETS)) {
                    out.add(key + " at " + p.toShortString() + " faces into " + level.getBlockState(front));
                }
            }
        }
        // The doorway: the cell inside every door clear.
        for (BlockPos d : find(level, home, s -> s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)) {
            BlockPos in = d.relative(Direction.NORTH);
            if (!level.getBlockState(in).getCollisionShape(level, in).isEmpty()) out.add("the way in at " + in.toShortString() + " is blocked by " + level.getBlockState(in));
        }
        // The beds: each a free cell beside it.
        for (BlockPos b : find(level, home, s -> s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.FOOT)) {
            BlockState s = level.getBlockState(b);
            BlockPos head = b.relative(s.getValue(BedBlock.FACING));
            boolean way = false;
            for (BlockPos half : new BlockPos[]{ b, head }) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = half.relative(d);
                    if (n.equals(b) || n.equals(head)) continue;
                    boolean feet = level.getBlockState(n).getCollisionShape(level, n).isEmpty() || level.getBlockState(n).is(BlockTags.WOOL_CARPETS);
                    if (feet && level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty()
                        && level.getBlockState(n.below()).isFaceSturdy(level, n.below(), Direction.UP)) way = true;
                }
            }
            if (!way) out.add("the bed at " + b.toShortString() + " cannot be got into");
        }
        return out;
    }

    private static void days(ServerLevel level, Town t, int n) {
        long d0 = level.getDayTime() / 24000L;
        for (int i = 0; i < n; i++) {
            level.setDayTime((d0 + i) * 24000L + 12600L);
            HomeMaking.dayForTests(level, t.v());
        }
    }

    // ============================================================ hm21: the poor and the well-off

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "hm21_poor_and_well_off_furnish")
    public static void hm21_poor_and_well_off_furnish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2460000, true, 6000);
        VillageFolkEntity poor = folk(helper, level, t, 0, StationTask.WOOD);
        VillageFolkEntity rich = folk(helper, level, t, 2, StationTask.SMITH);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            DyeColor colour = Decor.colour(rich);
            fill(id, t.store(), new ItemStack(Items.OAK_FENCE, 6), new ItemStack(Items.OAK_PRESSURE_PLATE, 6), new ItemStack(Items.OAK_STAIRS, 12),
                new ItemStack(Items.BARREL, 4), new ItemStack(Items.TORCH, 8), new ItemStack(Items.CAULDRON, 2), new ItemStack(Items.ANVIL, 1),
                new ItemStack(Items.FURNACE, 2), new ItemStack(Items.CHEST, 3),
                new ItemStack(HomeMaking.colouredForTests(colour, "_carpet"), 2), new ItemStack(HomeMaking.colouredForTests(colour, "_banner"), 1));
            house(level, id, t.heart(), -16, 20);
            house(level, id, t.heart(), 0, 20);
            house(level, id, t.heart(), 16, 20);
            Homes.tickForTests(level, t.v());
            BlockPos poorHome = Homes.homeOf(poor), richHome = Homes.homeOf(rich);
            helper.assertTrue(poorHome != null && richHome != null && !poorHome.equals(richHome), "a house each: " + poorHome + ", " + richHome);
            // The woodcutter has no coin, only the planks it brought; the smith has done well.
            poor.spend(poor.purse());
            own(poor, new ItemStack(Items.OAK_PLANKS, 32));
            rich.earn(200);
            helper.assertTrue(Wealth.tier(poor) == Wealth.Tier.POOR, "the woodcutter is poor: " + Wealth.tier(poor) + " worth " + Wealth.worth(poor));
            helper.assertTrue(Wealth.tier(rich).ordinal() >= Wealth.Tier.WELL_OFF.ordinal(), "the smith is well off: " + Wealth.tier(rich));
            long coin = money(id);
            int purse = rich.purse();
            int fences = stock(level, id, s -> s.is(Items.OAK_FENCE)), barrels = stock(level, id, s -> s.is(Items.BARREL));
            int planks = poor.countCarried(s -> s.is(Items.OAK_PLANKS));
            days(level, t, 3);
            List<String> poorPlan = HomeMaking.planForTests(id, poorHome), richPlan = HomeMaking.planForTests(id, richHome);
            Kit.log("hm21 the woodcutter's home: " + poorPlan);
            Kit.log("hm21 the smith's home: " + richPlan);
            Kit.log("hm21 the woodcutter's card: " + HomeMaking.cardLine(poor) + " | the smith's: " + HomeMaking.cardLine(rich));
            int planksAfter = poor.countCarried(s -> s.is(Items.OAK_PLANKS));
            Kit.log("hm21 planks " + planks + " -> " + planksAfter + "; the smith's purse " + purse + " -> " + rich.purse() + "; all the coin " + coin
                + " -> " + money(id) + "; fences in the stores " + fences + " -> " + stock(level, id, s -> s.is(Items.OAK_FENCE)) + ", barrels "
                + barrels + " -> " + stock(level, id, s -> s.is(Items.BARREL)));
            helper.assertTrue(HomeMaking.basicsForTests(id, poorHome), "the woodcutter's basics are all in: " + poorPlan);
            helper.assertTrue(HomeMaking.basicsForTests(id, richHome), "the smith's basics are all in: " + richPlan);
            helper.assertTrue(HomeMaking.howForTests(id, poorHome, "table").equals("made") && HomeMaking.howForTests(id, poorHome, "larder").equals("made"),
                "the woodcutter made its table and larder: " + poorPlan);
            helper.assertTrue(planksAfter < planks, "of its own planks: " + planks + " -> " + planksAfter);
            helper.assertTrue(HomeMaking.howForTests(id, richHome, "table").equals("bought"), "the smith bought its table: " + richPlan);
            helper.assertTrue(rich.purse() < purse, "out of its own purse: " + purse + " -> " + rich.purse());
            helper.assertTrue(money(id) == coin, "the coin moved, none made or lost: " + coin + " -> " + money(id));
            helper.assertTrue(stock(level, id, s -> s.is(Items.BARREL)) < barrels, "the barrels it bought came off the shelf");
            long poorOwn = poorPlan.stream().filter(s -> !s.startsWith("table") && !s.startsWith("seat") && !s.startsWith("larder") && !s.startsWith("chest")
                && !s.startsWith("light") && !s.startsWith("stove") && s.contains("=PLACED")).count();
            long richOwn = richPlan.stream().filter(s -> (s.startsWith("cauldron") || s.startsWith("trade.") || s.startsWith("colour.")) && s.contains("=PLACED")).count();
            helper.assertTrue(poorOwn == 0, "nothing but the basics for the poor: " + poorPlan);
            helper.assertTrue(richOwn >= 1, "its own things for the well-off (a water butt, the smith's anvil, its colours): " + richPlan);
            List<String> bad = new ArrayList<>(faults(level, id, poorHome));
            bad.addAll(faults(level, id, richHome));
            helper.assertTrue(bad.isEmpty(), "every piece the right way round: " + bad);
            helper.assertTrue(HomeMaking.cardLine(poor).contains("basics"), "its card says how its home is coming on: " + HomeMaking.cardLine(poor));
            helper.succeed();
        });
    }

    // ============================================================ hm22: the parish

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "hm22_parish_handout")
    public static void hm22_parish_handout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2462000, false, 6000);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            VillageFolkEntity f = t.first();
            f.setJob(StationTask.FARM);
            f.getInventoryItems().clear();
            f.spend(f.purse());
            fill(id, t.store(), new ItemStack(Items.OAK_FENCE, 2), new ItemStack(Items.OAK_PRESSURE_PLATE, 2), new ItemStack(Items.OAK_STAIRS, 4),
                new ItemStack(Items.BARREL, 2));
            BlockPos home = house(level, id, t.heart(), -16, 20);
            Homes.tickForTests(level, t.v());
            helper.assertTrue(home.equals(Homes.homeOf(f)), "it has the house");
            long coin = money(id);
            int fences = Market.stock(level, id, s -> s.is(Items.OAK_FENCE)), barrels = Market.stock(level, id, s -> s.is(Items.BARREL));
            days(level, t, 2);
            List<String> plan = HomeMaking.planForTests(id, home);
            int[] parish = HomeMaking.parishForTests(id);
            Kit.log("hm22 the plan: " + plan + "; the parish gave " + parish[0] + " worth " + parish[1] + "; fences " + fences + " -> "
                + Market.stock(level, id, s -> s.is(Items.OAK_FENCE)) + ", barrels " + barrels + " -> " + Market.stock(level, id, s -> s.is(Items.BARREL)));
            helper.assertTrue(HomeMaking.basicsForTests(id, home), "its basics are in: " + plan);
            helper.assertTrue(HomeMaking.howForTests(id, home, "table").equals("parish") && HomeMaking.howForTests(id, home, "larder").equals("parish"),
                "given by the parish: " + plan);
            helper.assertTrue(parish[0] >= 3, "the parish's books: " + parish[0]);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.OAK_FENCE)) == fences - 1 && Market.stock(level, id, s -> s.is(Items.BARREL)) == barrels - 1,
                "out of the town's stores");
            helper.assertTrue(money(id) == coin, "no coin moved: " + coin + " -> " + money(id));
            helper.assertTrue(faults(level, id, home).isEmpty(), "the right way round: " + faults(level, id, home));
            helper.succeed();
        });
    }

    // ============================================================ hm23: the town's hands off

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "hm23_town_leaves_lived_in_homes")
    public static void hm23_town_leaves_lived_in_homes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2464000, false, 6000);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            VillageFolkEntity f = t.first();
            f.setJob(StationTask.SMITH);
            Villages.ageForTests(id, Villages.Age.IRON);
            fill(id, t.store(), new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.BARREL, 8), new ItemStack(Items.FLOWER_POT, 8),
                new ItemStack(Items.POPPY, 8), new ItemStack(Items.BOOKSHELF, 8), new ItemStack(Items.LANTERN, 8), new ItemStack(Items.ANVIL, 1));
            BlockPos lived = house(level, id, t.heart(), -16, 20);
            Homes.tickForTests(level, t.v());
            BlockPos empty = house(level, id, t.heart(), 16, 20);
            helper.assertTrue(lived.equals(Homes.homeOf(f)), "the smith lives in the first house");
            Predicate<BlockState> town = s -> s.is(BlockTags.WOOL_CARPETS) || s.is(Blocks.BARREL) || s.is(Blocks.BOOKSHELF) || s.is(BlockTags.FLOWER_POTS)
                || s.is(Blocks.ANVIL);
            int livedBefore = find(level, lived, town).size(), emptyBefore = find(level, empty, town).size();
            int anvils = Market.stock(level, id, s -> s.is(Items.ANVIL));
            TownJobs.instantForTests(true);
            for (int i = 0; i < 6; i++) Interiors.work(level, t.v(), 40);
            for (int i = 0; i < 3; i++) Decor.work(level, t.v(), 40);
            int livedAfter = find(level, lived, town).size(), emptyAfter = find(level, empty, town).size();
            Kit.log("hm23 the lived-in house " + livedBefore + " -> " + livedAfter + "; the empty one " + emptyBefore + " -> " + emptyAfter
                + "; anvils in the stores " + anvils + " -> " + Market.stock(level, id, s -> s.is(Items.ANVIL)));
            helper.assertTrue(livedAfter == livedBefore, "the town left the household's home to it: " + livedBefore + " -> " + livedAfter);
            helper.assertTrue(emptyAfter > emptyBefore, "and furnished the empty one for its next tenants: " + emptyBefore + " -> " + emptyAfter);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.ANVIL)) == anvils, "the smith's anvil is not the town's to set out");
            helper.succeed();
        });
    }

    // ============================================================ hm24: moving takes the furniture

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "hm24_moving_takes_furniture")
    public static void hm24_moving_takes_furniture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2466000, false, 6000);
        UUID id = t.id();
        VillageFolkEntity f = t.first();
        BlockPos[] at = new BlockPos[4];
        helper.runAtTickTime(10, () -> {
            f.setJob(StationTask.WOOD);
            f.getInventoryItems().clear();
            f.spend(f.purse());
            own(f, new ItemStack(Items.OAK_PLANKS, 40));
            at[0] = house(level, id, t.heart(), -16, 20);
            Homes.tickForTests(level, t.v());
            helper.assertTrue(at[0].equals(Homes.homeOf(f)), "it has the first house");
            days(level, t, 1);
            at[1] = HomeMaking.pieceForTests(id, at[0], "table");
            at[2] = HomeMaking.pieceForTests(id, at[0], "seat.0");
            helper.assertTrue(at[1] != null && at[2] != null, "a table and a seat at home: " + HomeMaking.planForTests(id, at[0]));
            at[3] = house(level, id, t.heart(), 16, 20);
            Homes.tickForTests(level, t.v());
        });
        // A tick on (the town's map of its buildings has the new house on it), it moves.
        helper.runAtTickTime(20, () -> {
            BlockPos first = at[0], table = at[1], seat = at[2], second = at[3];
            HomeMaking.moveForTests(level, t.v(), List.of(f), second);
            helper.assertTrue(second.equals(Homes.homeOf(f)), "it has moved: " + Homes.homeOf(f));
            int planks = f.countCarried(s -> s.is(Items.OAK_PLANKS));
            days(level, t, 1);
            List<String> plan = HomeMaking.planForTests(id, second);
            Kit.log("hm24 the rooms: old " + HomeMaking.roomForTests(level, id, first) + "; new " + HomeMaking.roomForTests(level, id, second));
            Kit.log("hm24 the new home: " + plan + "; the old table's spot now " + level.getBlockState(table) + "; planks " + planks + " -> "
                + f.countCarried(s -> s.is(Items.OAK_PLANKS)));
            helper.assertTrue(HomeMaking.howForTests(id, second, "table").equals("brought") && HomeMaking.howForTests(id, second, "seat.0").equals("brought"),
                "the table and its seat brought from the old home: " + plan);
            helper.assertTrue(level.getBlockState(table).isAir() && level.getBlockState(seat).isAir(), "and gone from it");
            helper.assertTrue(HomeMaking.pieceForTests(id, second, "table") != null, "set out in the new one");
            helper.assertTrue(faults(level, id, second).isEmpty(), "the right way round: " + faults(level, id, second));
            helper.succeed();
        });
    }

    // ============================================================ hm25: a present, set out; and made good

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "hm25_gift_set_out_and_made_good")
    public static void hm25_gift_set_out_and_made_good(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2468000, false, 6000);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            VillageFolkEntity f = t.first();
            f.setJob(StationTask.FISH);
            f.getInventoryItems().clear();
            f.spend(f.purse());
            fill(id, t.store(), new ItemStack(Items.OAK_FENCE, 2), new ItemStack(Items.OAK_PRESSURE_PLATE, 2), new ItemStack(Items.OAK_STAIRS, 4),
                new ItemStack(Items.BARREL, 1));
            BlockPos home = house(level, id, t.heart(), -16, 20);
            Homes.tickForTests(level, t.v());
            String said = HomeMaking.giftForTests(f, new ItemStack(Items.BARREL), "Tester");
            int barrels = Market.stock(level, id, s -> s.is(Items.BARREL));
            days(level, t, 1);
            BlockPos larder = HomeMaking.pieceForTests(id, home, "larder");
            Kit.log("hm25 it said: " + said + "; the plan " + HomeMaking.planForTests(id, home));
            helper.assertTrue(said != null && said.toLowerCase(java.util.Locale.ROOT).contains("barrel"), "it knows what the present is for: " + said);
            helper.assertTrue(larder != null && HomeMaking.howForTests(id, home, "larder").equals("gift"), "the present set out as its larder");
            helper.assertTrue(level.getBlockEntity(larder) instanceof net.minecraft.world.Nameable n && n.getCustomName() != null
                && n.getCustomName().getString().equals("Larder"), "and named for it");
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.BARREL)) == barrels, "not the town's barrel");
            // Burnt: the larder gone. The household sees to it.
            level.setBlock(larder, Blocks.AIR.defaultBlockState(), 3);
            days(level, t, 1);
            BlockPos again = HomeMaking.pieceForTests(id, home, "larder");
            Kit.log("hm25 after the fire: " + HomeMaking.planForTests(id, home));
            helper.assertTrue(again != null && level.getBlockState(again).getBlock() instanceof BarrelBlock, "the larder made good: " + again);
            helper.succeed();
        });
    }

    // ============================================================ hm31: days at home

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "hm31_household_keeps_its_stocks")
    public static void hm31_household_keeps_its_stocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2470000, true, 6000);
        VillageFolkEntity f = folk(helper, level, t, 0, StationTask.FARM);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            fill(id, t.store(), new ItemStack(Items.BUCKET, 3), new ItemStack(Items.BREAD, 40));
            BlockPos home = house(level, id, t.heart(), -16, 20);
            house(level, id, t.heart(), 16, 20);
            Homes.tickForTests(level, t.v());
            helper.assertTrue(home.equals(Homes.homeOf(f)) || Homes.homeOf(f) != null, "it has a house");
            BlockPos h = Homes.homeOf(f);
            Kit.pond(level, h.getX() + 9, h.getZ() - 10, 3);
            f.earn(80);
            Container chest = chestOf(level, h);
            helper.assertTrue(chest != null, "the house has its chest");
            ItemStack loaf = new ItemStack(Items.BREAD, 3);
            Homes.keepsake(loaf, f, Homes.Source.BOUGHT);
            put(chest, loaf);
            long coin = money(id);
            // The list: a bucket for the water (it has none).
            List<String> list = Needs.listForTests(level, f);
            Kit.log("hm31 its list: " + list);
            helper.assertTrue(list.stream().anyMatch(s -> s.startsWith("bucket")), "a bucket on its list: " + list);
            List<String> bought = ShoppingTrips.tripNowForTests(level, f);
            Kit.log("hm31 bought " + bought + "; buckets at home " + count(chest, s -> s.is(Items.BUCKET)));
            helper.assertTrue(bought.stream().anyMatch(s -> s.contains("bucket")), "it bought one: " + bought);
            // The evening: the tool home; the morning: breakfast, the tool out.
            own(f, new ItemStack(Items.STONE_HOE));
            long day = level.getDayTime() / 24000L;
            level.setDayTime(day * 24000L + 13000L);
            f.teleportTo(h.getX() + 0.5, h.getY(), h.getZ() + 0.5);
            boolean home1 = Housekeeping.toolsHomeForTests(level, f);
            boolean inChest = count(chest, s -> s.is(Items.STONE_HOE)) == 1 && f.countCarried(s -> s.is(Items.STONE_HOE)) == 0;
            level.setDayTime((day + 1) * 24000L + 500L);
            int bread0 = count(chest, s -> s.is(Items.BREAD));
            Housekeeping.morningForTests(level, f);
            int bread1 = count(chest, s -> s.is(Items.BREAD));
            boolean out = f.countCarried(s -> s.is(Items.STONE_HOE)) == 1;
            Kit.log("hm31 the hoe home " + home1 + " (in the chest " + inChest + "), out again " + out + "; bread at home " + bread0 + " -> " + bread1);
            helper.assertTrue(home1 && inChest, "its hoe put away at home for the night");
            helper.assertTrue(out, "and taken to work in the morning");
            helper.assertTrue(bread1 < bread0, "breakfast out of the larder: " + bread0 + " -> " + bread1);
            // Water: fetched from the pond in the bucket it bought.
            int[] w0 = Housekeeping.stockForTests(level, id, h, Housekeeping.Stock.WATER);
            String chore = Housekeeping.choreForTests(level, f);
            int[] w1 = Housekeeping.stockForTests(level, id, h, Housekeeping.Stock.WATER);
            Kit.log("hm31 the chore: " + chore + "; water " + w0[0] + "/" + w0[1] + " -> " + w1[0] + "/" + w1[1]);
            helper.assertTrue("water".equals(chore) && w1[0] > w0[0], "water fetched home: " + chore + ", " + w0[0] + " -> " + w1[0]);
            // The evening: water drunk.
            level.setDayTime((day + 1) * 24000L + 13000L);
            Housekeeping.eveningForTests(level, t.v(), h);
            int[] w2 = Housekeeping.stockForTests(level, id, h, Housekeeping.Stock.WATER);
            Kit.log("hm31 after the evening: water " + w2[0] + "; the card: " + Housekeeping.cardLine(f));
            helper.assertTrue(w2[0] < w1[0], "drunk and cooked with: " + w1[0] + " -> " + w2[0]);
            helper.assertTrue(money(id) == coin, "the coin moved, none made or lost: " + coin + " -> " + money(id));
            helper.assertTrue(Housekeeping.cardLine(f).contains("water"), "its card tells its stocks: " + Housekeeping.cardLine(f));
            helper.succeed();
        });
    }

    // ============================================================ hm32: a bare larder

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "hm32_bare_larder_borrows")
    public static void hm32_bare_larder_borrows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2472000, false, 6000);
        VillageFolkEntity friend = folk(helper, level, t, 2, StationTask.FARM);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            VillageFolkEntity f = t.first();
            f.setJob(StationTask.FISH);
            f.getInventoryItems().clear();
            f.spend(f.purse());
            house(level, id, t.heart(), -16, 20);
            house(level, id, t.heart(), 16, 20);
            Homes.tickForTests(level, t.v());
            BlockPos mine = Homes.homeOf(f), theirs = Homes.homeOf(friend);
            helper.assertTrue(mine != null && theirs != null && !mine.equals(theirs), "a house each");
            f.life().feel(friend.getUUID(), friend.displayNameCap(), 50);
            friend.life().feel(f.getUUID(), f.displayNameCap(), 50);
            Container full = chestOf(level, theirs);
            ItemStack loaves = new ItemStack(Items.BREAD, 20);
            Homes.keepsake(loaves, friend, Homes.Source.BOUGHT);
            put(full, loaves);
            int theirs0 = count(full, s -> s.is(Items.BREAD));
            String chore = Housekeeping.choreForTests(level, f);
            Container own = chestOf(level, mine);
            int mine1 = count(own, s -> s.is(Items.BREAD)), theirs1 = count(full, s -> s.is(Items.BREAD));
            int owes = Housekeeping.owesForTests(id, mine);
            Kit.log("hm32 the chore " + chore + "; their bread " + theirs0 + " -> " + theirs1 + ", ours 0 -> " + mine1 + "; owes " + owes);
            helper.assertTrue("borrow".equals(chore) && mine1 > 0 && theirs1 == theirs0 - mine1, "borrowed of a friend's larder: " + chore + ", " + mine1);
            helper.assertTrue(owes == mine1, "and owed: " + owes);
            // Its own larder full again: paid back of an evening.
            ItemStack lots = new ItemStack(Items.BREAD, 30);
            Homes.keepsake(lots, f, Homes.Source.BOUGHT);
            put(own, lots);
            Housekeeping.eveningForTests(level, t.v(), mine);
            int theirs2 = count(full, s -> s.is(Items.BREAD));
            Kit.log("hm32 after paying back: theirs " + theirs2 + ", owes " + Housekeeping.owesForTests(id, mine));
            helper.assertTrue(Housekeeping.owesForTests(id, mine) == 0 && theirs2 == theirs0, "paid back: " + theirs2 + " of " + theirs0);
            helper.succeed();
        });
    }

    // ============================================================ hm33: the winter

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "hm33_winter_stocking")
    public static void hm33_winter_stocking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2474000, false, 6000);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            VillageFolkEntity f = t.first();
            f.setJob(StationTask.FARM);
            f.getInventoryItems().clear();
            f.earn(60);
            BlockPos home = house(level, id, t.heart(), -16, 20);
            Homes.tickForTests(level, t.v());
            helper.assertTrue(home.equals(Homes.homeOf(f)), "it has the house");
            try {
                Housekeeping.seasonForTests(Seasons.Season.SUMMER);
                int[] summerFood = Housekeeping.stockForTests(level, id, home, Housekeeping.Stock.FOOD);
                int[] summerFuel = Housekeeping.stockForTests(level, id, home, Housekeeping.Stock.FUEL);
                Housekeeping.seasonForTests(Seasons.Season.AUTUMN);
                int[] autumnFood = Housekeeping.stockForTests(level, id, home, Housekeeping.Stock.FOOD);
                int[] autumnFuel = Housekeeping.stockForTests(level, id, home, Housekeeping.Stock.FUEL);
                List<String> list = Needs.listForTests(level, f);
                Kit.log("hm33 food kept: summer " + summerFood[1] + ", autumn " + autumnFood[1] + "; fuel: summer " + summerFuel[1] + ", autumn " + autumnFuel[1]
                    + "; the autumn list " + list);
                helper.assertTrue(autumnFood[1] > summerFood[1] && autumnFuel[1] > summerFuel[1], "more put by in the autumn");
                helper.assertTrue(list.stream().anyMatch(s -> s.startsWith("charcoal")) && list.stream().anyMatch(s -> s.contains("winter")),
                    "the winter's fuel and larder on its list: " + list);
                Housekeeping.seasonForTests(Seasons.Season.WINTER);
                long day = level.getDayTime() / 24000L;
                level.setDayTime(day * 24000L + 13000L);
                Housekeeping.eveningForTests(level, t.v(), home);
                boolean[] cold = Housekeeping.hardshipForTests(id, home, day);
                // Logs for the stove: the next evening it is fed.
                Container chest = chestOf(level, home);
                ItemStack logs = new ItemStack(Items.OAK_LOG, 8);
                Homes.keepsake(logs, f, Homes.Source.BOUGHT);
                put(chest, logs);
                level.setDayTime((day + 3) * 24000L + 13000L);
                Housekeeping.eveningForTests(level, t.v(), home);
                boolean[] warm = Housekeeping.hardshipForTests(id, home, day + 3);
                int left = count(chest, s -> s.is(Items.OAK_LOG));
                Kit.log("hm33 a winter evening with no fuel: cold " + cold[0] + "; with logs: cold " + warm[0] + ", logs 8 -> " + left);
                helper.assertTrue(cold[0], "a cold house with nothing for the stove");
                helper.assertTrue(!warm[0] && left < 8, "the stove fed out of its own logs");
            } finally {
                Housekeeping.seasonForTests(null);
            }
            helper.succeed();
        });
    }

    // ============================================================ hm34: the larder and the stove

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "hm34_spoils_and_cooks")
    public static void hm34_spoils_and_cooks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 2476000, false, 6000);
        UUID id = t.id();
        VillageFolkEntity f = t.first();
        BlockPos[] home = new BlockPos[1];
        long[] day = new long[1];
        helper.runAtTickTime(10, () -> {
            f.setJob(StationTask.FISH);
            f.getInventoryItems().clear();
            home[0] = house(level, id, t.heart(), -16, 20);
            Homes.tickForTests(level, t.v());
            helper.assertTrue(home[0].equals(Homes.homeOf(f)), "it has the house");
            Container chest = chestOf(level, home[0]);
            ItemStack beef = new ItemStack(Items.BEEF, 2);
            Homes.keepsake(beef, f, Homes.Source.BOUGHT);
            put(chest, beef);
            ItemStack pork = new ItemStack(Items.PORKCHOP, 1);
            Homes.keepsake(pork, f, Homes.Source.BOUGHT);
            put(chest, pork);
            ItemStack coal = new ItemStack(Items.COAL, 2);
            Homes.keepsake(coal, f, Homes.Source.BOUGHT);
            put(chest, coal);
            day[0] = level.getDayTime() / 24000L;
            level.setDayTime(day[0] * 24000L + 14000L);                // after supper: nobody eats the meat before it can go off
            Housekeeping.eveningForTests(level, t.v(), home[0]);
            List<BlockPos> stoves = find(level, home[0], s -> s.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock);
            helper.assertTrue(!stoves.isEmpty(), "the house has its stove");
            Container oven = (Container) level.getBlockEntity(stoves.get(0));
            Kit.log("hm34 on the stove: " + oven.getItem(0) + ", fuel " + oven.getItem(1));
            helper.assertTrue(!oven.getItem(0).isEmpty() && !oven.getItem(1).isEmpty(), "raw food on the stove, and its own fuel under it");
        });
        helper.runAtTickTime(420, () -> {
            Container chest = chestOf(level, home[0]);
            level.setDayTime((day[0] + 4) * 24000L + 14000L);
            Housekeeping.eveningForTests(level, t.v(), home[0]);
            int cooked = count(chest, s -> s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP));
            int rotten = count(chest, s -> s.is(Items.ROTTEN_FLESH));
            int raw = count(chest, s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP));
            Kit.log("hm34 four days on: cooked " + cooked + ", rotten " + rotten + ", raw " + raw);
            helper.assertTrue(cooked >= 1, "what went on the stove came off it cooked, into the larder");
            helper.assertTrue(rotten >= 1, "raw meat left four days went off");
            helper.succeed();
        });
    }
}
