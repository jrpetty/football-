package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.CheeseWheelBlock;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Birthdays;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.FoundingDay;
import com.jrpetty.mcassistant.entity.Health;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Kitchen;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Links;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Meals;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchKit;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.BandageItem;
import com.jrpetty.mcassistant.item.KitchenItems;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [kitchen] The kitchen, the cellar and the healer's shelf (entity/Kitchen, item/KitchenItems): each of the eight made by
 * its maker out of the stores by its real recipe, and used, with its effect seen.
 * <ul>
 * <li><b>kt01</b>: the cook packs lunches of the stores' bread, roast and apples for the far hands; a hand picks one up
 *     before it sets out and eats it at its far plot at the midday meal (no walk back), where a hand without one is
 *     sent in to the stores; the town counts it; a player eats one.</li>
 * <li><b>kt02</b>: the rancher milks the more while cheese is wanted; the cook makes a wheel of the stores' milk (the
 *     buckets back), sets one out on the café's table, and folk at their meal there eat it a slice at a time to the
 *     board (the cloth laid back); a player eats a slice; short of food, the stores cut a wheel and a folk eats a slice
 *     of it at its meal.</li>
 * <li><b>kt03</b>: with Founding Day tomorrow, the cook bakes honey cakes of the beekeeper's honey; at the gathering the
 *     guests have a slice and are the happier for it, the card and the chronicle say whose cake it was; the toast is in
 *     mead; a birthday is kept with the other cake; a player eats one.</li>
 * <li><b>kt04</b>: the brewer brews mead for the tavern; at the bar a sensible folk has one and a merry one two, paid
 *     for, a little regeneration, the bottle in its hand and then back in the stores; the wedding is toasted in it; a
 *     player drinks one.</li>
 * <li><b>kt05</b>: in the autumn the brewer brews cider of the stores' apples; the woodcutter takes up apples lying under
 *     its trees; the harvest festival is toasted in cider; the café pours it in the autumn.</li>
 * <li><b>kt06</b>: a glut at the fish market leaves twelve fish to the cook, who bakes twelve fish pies of them; the
 *     gazette says so the next day; a pie is better eating than bread; the café sells it.</li>
 * <li><b>kt07</b>: the healer (the brewer) steeps herbal tea of berries, sugar and a bottle of water; a cup sees a cold
 *     off a day sooner, once a cold; with no brewer the café makes it; a player drinks one.</li>
 * <li><b>kt08</b>: the tailor rolls bandages of paper and string; the watch's kit takes them; a hurt guard binds its own
 *     and mends; the healer binds a wounded patient; a player binds a wound, and waits before the next.</li>
 * <li><b>kt09</b>: the nine have their recipes, ages and worth, the market deals in them, and the shop's workshop makes
 *     what no trade of the town does.</li>
 * <li><b>kt10</b>: the cook's book, the Production page and the card show the packed lunches eaten out; the stage for the
 *     pictures sets out the wall of frames, the four cuts of the cheese, and the folk with the things in hand.</li>
 * </ul>
 * Each on its own ground (x 1,200,000 to 1,218,000, z 66,000), what it checks called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class KitchenGameTests {

    private static final String EMPTY = "empty";
    private static final long DAY = 24000L * 40;             // day forty: a town founded weeks back can be told so (foundedForTests)
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the ground and the town

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 16; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A village's first folk on flat ground at x, at this time of day, the kitchen's memory clean. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kitchen.resetForTests();
        level.setDayTime(time);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = flat(level, x, Z, 44);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
        return f;
    }

    /** The town's stores emptied: only what a test puts there. */
    private static void emptyStores(GameTestHelper helper, ServerLevel level, UUID village) {
        List<BlockPos> chests = Villages.storeChests(level, village);
        helper.assertTrue(!chests.isEmpty(), "the town has its stores");
        for (BlockPos p : chests) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
    }

    /** These into the stores, as a player would bring them. */
    private static void stock(ServerLevel level, UUID village, ItemStack... goods) {
        int g = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, goods[g++].copy());
            }
            c.setChanged();
        }
        Villages.forgetStock();
    }

    private static int stores(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    /** A building of the town, stamped here facing north and on its books. */
    private static Ledger.Building building(ServerLevel level, UUID village, String structure, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, structure, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, structure, p, Direction.NORTH);
        return new Ledger.Building(structure, p, Direction.NORTH);
    }

    private static void moveTo(VillageFolkEntity f, BlockPos at) {
        f.getNavigation().stop();
        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    /** A hand whose plot lies far out: a farmer, its field this far east of the heart. */
    private static VillageFolkEntity farHand(GameTestHelper helper, ServerLevel level, BlockPos heart, UUID village, int east, int south) {
        VillageFolkEntity f = another(helper, heart.offset(3, 0, south), village);
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(Kit.surface(level, heart.getX() + east, heart.getZ() + south), 6, WorkZone.DEFAULT_DEPTH), "the far field");
        return f;
    }

    /** A stand-in player as a survival player is: it eats when hungry, its stacks go down, its bottles come back
     *  (the test's stand-in calls itself creative; its abilities are what the game asks). Kit.noLeftoverPlayers after. */
    private static ServerPlayer survivor(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.getAbilities().invulnerable = false;
        return p;
    }

    private static boolean said(UUID village, String... words) {
        for (Chronicle.Entry e : Chronicle.of(village)) {
            boolean all = true;
            for (String w : words) if (!e.text().contains(w)) all = false;
            if (all) return true;
        }
        return false;
    }

    private static int withdrawsQueued(VillageFolkEntity f) {
        int n = 0;
        for (Job j : f.queuedJobs()) if (j.type() == Job.Type.WITHDRAW && j.arg() != null && j.arg().startsWith("ration@")) n++;
        return n;
    }

    // ============================================================ kt01 the packed lunch

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt01_packed_lunch")
    public static void kt01_packed_lunch(GameTestHelper helper) {
        int x = 1200000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        Kit.hold(level, x + 120, Z, 16);
        Kit.prepare(level, x + 120, Z, 16);
        VillageFolkEntity cook = another(helper, heart.south(4), id);
        VillageFolkEntity hand = farHand(helper, level, heart, id, 120, 0);
        VillageFolkEntity other = farHand(helper, level, heart, id, 118, 4);
        long day = DAY / 24000L;
        helper.runAtTickTime(5, () -> {
            cook.setJob(StationTask.COOK);
            founder.setJob(StationTask.NONE);                            // a founder may be given a far plot at once: kept at home here
            Item lunch = KitchenItems.PACKED_LUNCH.get();
            helper.assertTrue(Kitchen.farForTests(hand) && Kitchen.farForTests(other), "the far field's hands work far off");
            helper.assertTrue(!Kitchen.farForTests(cook) && !Kitchen.farForTests(founder), "the cook and the founder work at home");
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.BREAD, 6), new ItemStack(Items.COOKED_BEEF, 6), new ItemStack(Items.APPLE, 6),
                new ItemStack(Items.CRAFTING_TABLE));
            Map<Item, Integer> want = Kitchen.wantedForTests(level, id);
            helper.assertTrue(want.getOrDefault(lunch, 0) >= 3, "a lunch for each far hand and one over: " + want);
            String made = Kitchen.craftForTests(level, cook);
            int lunches = stores(level, id, lunch), bread = stores(level, id, Items.BREAD), beef = stores(level, id, Items.COOKED_BEEF),
                apples = stores(level, id, Items.APPLE);
            Kit.log("kt01 the cook: " + made + "; lunches " + lunches + ", bread " + bread + ", beef " + beef + ", apples " + apples);
            helper.assertTrue(made != null && made.contains("packed lunch"), "the cook packs lunches: " + made);
            helper.assertTrue(lunches >= 2 && lunches % 2 == 0, "two to a recipe: " + lunches);
            helper.assertTrue(6 - bread == lunches / 2 && 6 - beef == lunches / 2 && 6 - apples == lunches / 2,
                "a loaf, a roast and an apple out of the stores for every two: bread " + bread + ", beef " + beef + ", apples " + apples);
            // Before it sets out: one picked up at the stores.
            helper.assertTrue(Kitchen.packForTests(level, hand), "the far hand picks up a packed lunch");
            helper.assertTrue(hand.countMatching(s -> s.is(lunch)) == 1 && stores(level, id, lunch) == lunches - 1, "one out of the stores, into its pack");
            helper.assertTrue(!Kitchen.packForTests(level, hand), "one a day");
            // The other goes out with nothing to eat.
            other.getInventoryItems().clear();
            // Midday, out at the far field.
            moveTo(hand, Kit.surface(level, x + 120, Z));
            moveTo(other, Kit.surface(level, x + 118, Z + 4));
            level.setDayTime(DAY + 6000);
            level.updateSkyBrightness();
            hand.clearQueue();                                   // only what the midday meal itself sets going is looked at
            other.clearQueue();
            Meals.tick(hand);
            Meals.tick(other);
            int[] books = Kitchen.dayForTests(id, day);
            Kit.log("kt01 at midday: " + Meals.heldForTests(hand) + " (" + hand.meals().lastWhat() + "), offhand "
                + hand.getItemBySlot(EquipmentSlot.OFFHAND) + "; the other: " + withdrawsQueued(other) + " walks back queued; out " + books[0]);
            helper.assertTrue(Meals.heldForTests(hand).equals("taken already") && hand.meals().lastWhat().equals("a packed lunch"),
                "its midday meal had, of its packed lunch: " + Meals.heldForTests(hand) + ", " + hand.meals().lastWhat());
            helper.assertTrue(hand.countMatching(s -> s.is(lunch)) == 0 && hand.getItemBySlot(EquipmentSlot.OFFHAND).is(lunch),
                "eaten out of its pack, in its hand as it eats");
            helper.assertTrue(withdrawsQueued(hand) == 0, "no walk back to the stores for it");
            helper.assertTrue(withdrawsQueued(other) > 0, "while the hand with no lunch is sent in for food");
            helper.assertTrue(books[0] == 1, "the town counts a lunch eaten out: " + books[0]);
            String gazette = Kitchen.gazette(id, day + 1);
            helper.assertTrue(gazette != null && gazette.contains("One packed lunch eaten out"), "the next gazette says so: " + gazette);
            helper.assertTrue(Kitchen.cardLine(hand).contains("packed lunch"), "its card: " + Kitchen.cardLine(hand));
            // A player eats one: a good meal.
            ServerPlayer p = survivor(helper);
            p.getFoodData().setFoodLevel(4);
            ItemStack one = new ItemStack(lunch);
            KitchenItems.PACKED_LUNCH.get().finishUsingItem(one, level, p);
            int food = p.getFoodData().getFoodLevel();
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(food >= 13 && one.isEmpty(), "a player's packed lunch is a full meal: food " + food);
        });
        helper.runAtTickTime(90, () -> {
            Kitchen.secondForTests(hand);
            helper.assertTrue(hand.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty(), "eaten, its hand empty again: " + hand.getItemBySlot(EquipmentSlot.OFFHAND));
            helper.succeed();
        });
    }

    // ============================================================ kt02 the cheese wheel

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt02_cheese_wheel")
    public static void kt02_cheese_wheel(GameTestHelper helper) {
        int x = 1202000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        Villages.ageForTests(id, Villages.Age.STONE);
        VillageFolkEntity cook = another(helper, heart.south(4), id);
        VillageFolkEntity rancher = another(helper, heart.east(5), id);
        VillageFolkEntity eater = another(helper, heart.west(4), id);
        long day = DAY / 24000L;
        helper.runAtTickTime(5, () -> {
            Item wheel = KitchenItems.CHEESE_WHEEL_ITEM.get(), slice = KitchenItems.CHEESE_SLICE.get();
            cook.setJob(StationTask.COOK);
            rancher.setJob(StationTask.RANCH);
            Ledger.Building cafe = building(level, id, "cafe", heart.offset(-16, 0, 14));
            List<BlockPos> tables = Kitchen.tablesForTests(cafe);
            helper.assertTrue(!tables.isEmpty(), "the café has tables");
            BlockPos table = tables.get(0);
            BlockState cloth = level.getBlockState(table);
            helper.assertTrue(level.getBlockState(table.below()).getBlock() instanceof FenceBlock, "a table: a post, " + cloth + " on it");
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.BUCKET, 2), new ItemStack(Items.WHEAT, 16), new ItemStack(Items.EGG), new ItemStack(Items.CRAFTING_TABLE));
            // The rancher milks the more while the cook wants cheese: three in the stores would once have been enough.
            Cow cow = EntityType.COW.create(level);
            helper.assertTrue(cow != null, "a cow");
            cow.moveTo(rancher.getX() + 3, rancher.getY(), rancher.getZ());
            level.addFreshEntity(cow);
            int milkWanted = Kitchen.milkWanted(level, id);
            String milked = Links.milk(rancher, level);
            Kit.log("kt02 cheese wanted: " + Kitchen.wantedForTests(level, id).get(wheel) + "; milk wanted " + milkWanted + "; the rancher: " + milked);
            helper.assertTrue(milkWanted == 3 && milked != null && rancher.countMatching(s -> s.is(Items.MILK_BUCKET)) == 1,
                "the rancher milks a cow into a stores' bucket for the cheese: " + milked);
            // The cook makes a wheel, by its recipe: three buckets of milk, three wheat, an egg; the buckets back.
            int buckets0 = stores(level, id, Items.BUCKET);
            String made = Kitchen.craftForTests(level, cook);
            Kit.log("kt02 the cook: " + made + "; wheels " + stores(level, id, wheel) + ", milk " + stores(level, id, Items.MILK_BUCKET)
                + ", buckets " + buckets0 + " -> " + stores(level, id, Items.BUCKET) + ", wheat " + stores(level, id, Items.WHEAT));
            helper.assertTrue(made != null && made.contains("cheese wheel") && stores(level, id, wheel) == 1, "a cheese wheel made: " + made);
            helper.assertTrue(stores(level, id, Items.MILK_BUCKET) == 0 && stores(level, id, Items.BUCKET) == buckets0 + 3
                && stores(level, id, Items.EGG) == 0 && stores(level, id, Items.WHEAT) == 13, "out of the milk, the wheat and the egg, the buckets back");
            // A second (the reserve keeps one), and the cook sets a wheel out on the café's table.
            stock(level, id, new ItemStack(wheel));
            String set = Kitchen.craftForTests(level, cook);
            helper.assertTrue(set != null && set.contains("café's table") && level.getBlockState(table).getBlock() instanceof CheeseWheelBlock
                && stores(level, id, wheel) == 1, "a wheel out on the café's table, one left put by: " + set);
            // Folk at their meal there eat it a slice at a time.
            moveTo(eater, cafe.anchor());
            for (int i = 1; i <= CheeseWheelBlock.SLICES; i++) {
                helper.assertTrue(Kitchen.mealOutForTests(level, eater, false), "slice " + i + " had at the table");
                helper.assertTrue(eater.meals().lastWhat().equals("a slice of cheese"), "its meal a slice of cheese: " + eater.meals().lastWhat());
                if (i < CheeseWheelBlock.SLICES) {
                    BlockState st = level.getBlockState(table);
                    helper.assertTrue(st.getBlock() instanceof CheeseWheelBlock && st.getValue(CheeseWheelBlock.CUT) == i, "a quarter less: " + st);
                }
            }
            BlockState after = level.getBlockState(table);
            Kit.log("kt02 the wheel eaten to the board; the table: " + after + " (was " + cloth + ")");
            helper.assertTrue(!(after.getBlock() instanceof CheeseWheelBlock), "eaten to the board");
            helper.assertTrue(!cloth.is(BlockTags.WOOL_CARPETS) || after.is(BlockTags.WOOL_CARPETS), "the cloth laid back: " + after);
            helper.assertTrue(!Kitchen.mealOutForTests(level, eater, false), "nothing left to eat at the table");
            // A player eats a slice off a wheel.
            BlockPos mine = heart.offset(5, 0, 5);
            level.setBlock(mine, KitchenItems.CHEESE_WHEEL.get().defaultBlockState(), 3);
            ServerPlayer p = survivor(helper);
            p.getFoodData().setFoodLevel(4);
            level.getBlockState(mine).useWithoutItem(level, p, new BlockHitResult(Vec3.atCenterOf(mine), Direction.UP, mine, false));
            int food = p.getFoodData().getFoodLevel();
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(food == 8 && level.getBlockState(mine).getValue(CheeseWheelBlock.CUT) == 1, "a player's slice: food " + food);
            // Food short: the stores cut a wheel for the meals, and a folk eats a slice of it.
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(wheel));
            Leader.booksForTests(id, new Leader.Books(0, 0, 12, 0, 12, 0, Leader.Plan.SHORT, day));
            String cut = Kitchen.reserveForTests(level, id);
            Kit.log("kt02 short of food: " + cut + "; slices " + stores(level, id, slice));
            helper.assertTrue(cut != null && stores(level, id, wheel) == 0 && stores(level, id, slice) == CheeseWheelBlock.SLICES,
                "a wheel cut into four slices for the stores: " + cut);
            helper.assertTrue(Kitchen.dayForTests(id, day)[2] == 1 && said(id, "cut a cheese wheel"), "the books and the chronicle hear of it");
            founder.getInventoryItems().clear();
            level.setDayTime(DAY + 6000);
            level.updateSkyBrightness();
            Meals.tick(founder);
            Kit.log("kt02 the founder's midday meal: " + founder.meals().lastWhat() + "; slices left " + stores(level, id, slice));
            helper.assertTrue(stores(level, id, slice) == CheeseWheelBlock.SLICES - 1 && founder.meals().lastWhat().contains("Cheese"),
                "a slice of the reserve for its meal: " + founder.meals().lastWhat());
            helper.succeed();
        });
    }

    // ============================================================ kt03 the honey cake

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt03_honey_cake")
    public static void kt03_honey_cake(GameTestHelper helper) {
        int x = 1204000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity cook = another(helper, heart.south(4), id);
        VillageFolkEntity a = another(helper, heart.east(3), id), b = another(helper, heart.east(5), id), c = another(helper, heart.west(3), id);
        long day = DAY / 24000L;
        helper.runAtTickTime(5, () -> {
            Item cake = KitchenItems.HONEY_CAKE.get();
            cook.setJob(StationTask.COOK);
            // Founding Day tomorrow: the cook bakes for it.
            FoundingDay.foundedForTests(id, day - 27);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.HONEY_BOTTLE, 2), new ItemStack(Items.WHEAT, 16), new ItemStack(Items.EGG, 2),
                new ItemStack(Items.SUGAR, 2), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(KitchenItems.MEAD.get()));
            Map<Item, Integer> want = Kitchen.wantedForTests(level, id);
            helper.assertTrue(want.getOrDefault(cake, 0) >= 2, "honey cakes wanted for Founding Day: " + want);
            String made = Kitchen.craftForTests(level, cook);
            Kit.log("kt03 the cook: " + made + "; cakes " + stores(level, id, cake) + ", honey " + stores(level, id, Items.HONEY_BOTTLE)
                + ", bottles " + stores(level, id, Items.GLASS_BOTTLE));
            helper.assertTrue(made != null && made.contains("honey cake") && stores(level, id, cake) == 2, "two honey cakes baked: " + made);
            helper.assertTrue(stores(level, id, Items.HONEY_BOTTLE) == 0 && stores(level, id, Items.GLASS_BOTTLE) == 2 && stores(level, id, Items.EGG) == 0
                && stores(level, id, Items.SUGAR) == 0 && stores(level, id, Items.WHEAT) == 12, "of the honey, the wheat, the eggs and the sugar; the honey's bottles back");
            // At the gathering: a slice each, out of one cake.
            String cookName = cook.displayNameCap();
            for (VillageFolkEntity g : List.of(a, b, c)) {
                g.ensurePersona();
                g.refreshMood();
                int before = g.persona().mood();
                helper.assertTrue(Kitchen.serveForTests(level, g, Assemblies.Kind.FOUNDING, ""), g.displayNameCap() + " has a slice");
                g.refreshMood();
                int after = g.persona().mood();
                Kit.log("kt03 " + g.displayNameCap() + ": " + Kitchen.cardLine(g) + "; mood " + before + " -> " + after + " " + g.persona().moodWhy());
                helper.assertTrue(Kitchen.cardLine(g).contains(cookName + "'s honey cake at Founding Day"), "its card says whose: " + Kitchen.cardLine(g));
                helper.assertTrue(g.persona().moodWhy().contains("cake") && after > before, "the happier for it: " + before + " -> " + after);
            }
            helper.assertTrue(stores(level, id, cake) == 1, "one cake cut for the three");
            helper.assertTrue(said(id, "Founding Day had " + cookName + "'s honey cake"), "the chronicle: whose cake it was");
            // The toast, in mead.
            int bottles = stores(level, id, Items.GLASS_BOTTLE);
            helper.assertTrue(Kitchen.serveForTests(level, a, Assemblies.Kind.FOUNDING, ""), "a raises a glass");
            helper.assertTrue(stores(level, id, KitchenItems.MEAD.get()) == 0 && stores(level, id, Items.GLASS_BOTTLE) == bottles + 1
                && a.hasEffect(MobEffects.REGENERATION), "a bottle of mead poured round, the bottle back");
            // A birthday kept with the other cake.
            Birthdays.celebrate(level, v, b, day);
            helper.assertTrue(stores(level, id, cake) == 0 && said(id, b.displayNameCap() + "'s birthday was kept with " + cookName + "'s honey cake"),
                "the birthday's cake: " + Chronicle.of(id));
            // A player eats a slice of one.
            ServerPlayer p = survivor(helper);
            p.getFoodData().setFoodLevel(4);
            KitchenItems.HONEY_CAKE.get().finishUsingItem(new ItemStack(cake), level, p);
            boolean cheered = p.hasEffect(MobEffects.ABSORPTION);
            int food = p.getFoodData().getFoodLevel();
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(food == 11 && cheered, "a player's slice: food " + food + ", a heart of cheer " + cheered);
            helper.succeed();
        });
    }

    // ============================================================ kt04 mead

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt04_mead")
    public static void kt04_mead(GameTestHelper helper) {
        int x = 1206000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        Villages.ageForTests(id, Villages.Age.STONE);
        VillageFolkEntity brewer = another(helper, heart.south(4), id);
        VillageFolkEntity sensible = another(helper, heart.east(3), id), merry = another(helper, heart.east(5), id), guest = another(helper, heart.west(3), id);
        int[] bottlesAt = new int[1];
        helper.runAtTickTime(5, () -> {
            Item mead = KitchenItems.MEAD.get();
            brewer.setJob(StationTask.BREW);
            building(level, id, "tavern", heart.offset(-16, 0, 14));
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.HONEY_BOTTLE, 2), new ItemStack(Items.SUGAR, 4), new ItemStack(Items.GLASS_BOTTLE, 2));
            Map<Item, Integer> want = Kitchen.wantedForTests(level, id);
            helper.assertTrue(want.getOrDefault(mead, 0) >= 3, "mead for the tavern: " + want);
            String made = Kitchen.craftForTests(level, brewer);
            Kit.log("kt04 the brewer: " + made + "; mead " + stores(level, id, mead) + ", honey " + stores(level, id, Items.HONEY_BOTTLE)
                + ", sugar " + stores(level, id, Items.SUGAR) + ", bottles " + stores(level, id, Items.GLASS_BOTTLE));
            helper.assertTrue(made != null && made.contains("mead") && stores(level, id, mead) == 2, "two meads brewed: " + made);
            helper.assertTrue(stores(level, id, Items.HONEY_BOTTLE) == 0 && stores(level, id, Items.SUGAR) == 2 && stores(level, id, Items.GLASS_BOTTLE) == 2,
                "a honey, a sugar and a bottle each; the honey's own bottles back");
            stock(level, id, new ItemStack(mead));
            // At the bar: a sensible one has one, a merry one two.
            Kitchen.merryForTests(sensible.getUUID(), false);
            Kitchen.merryForTests(merry.getUUID(), true);
            sensible.earn(20);
            merry.earn(20);
            int purse = sensible.purse(), coins = Ledger.coins(id);
            sensible.ensurePersona();
            sensible.refreshMood();
            int before = sensible.persona().mood();
            helper.assertTrue(Kitchen.barForTests(level, sensible), "a mead at the bar");
            sensible.refreshMood();
            int paid = purse - sensible.purse();
            Kit.log("kt04 the sensible one paid " + paid + "; treasury " + coins + " -> " + Ledger.coins(id) + "; mood " + before + " -> "
                + sensible.persona().mood() + " " + sensible.persona().moodWhy() + "; in hand " + sensible.getItemBySlot(EquipmentSlot.OFFHAND));
            helper.assertTrue(paid >= 2 && Ledger.coins(id) == coins + paid, "paid for at the bar, into the treasury: " + paid);
            helper.assertTrue(sensible.hasEffect(MobEffects.REGENERATION) && sensible.persona().moodWhy().contains("drink")
                && sensible.persona().mood() > before, "a little regeneration, and its spirits lifted");
            helper.assertTrue(sensible.getItemBySlot(EquipmentSlot.OFFHAND).is(mead), "the bottle in its hand as it drinks");
            helper.assertTrue(!Kitchen.barForTests(level, sensible), "one is enough for a sensible one");
            helper.assertTrue(Kitchen.barForTests(level, merry) && Kitchen.barForTests(level, merry), "a merry one has two");
            helper.assertTrue(!Kitchen.barForTests(level, merry), "and no more");
            helper.assertTrue(stores(level, id, mead) == 0, "three bottles drunk");
            bottlesAt[0] = stores(level, id, Items.GLASS_BOTTLE);
            // The wedding toasted in it.
            stock(level, id, new ItemStack(mead));
            Kitchen.merryForTests(guest.getUUID(), false);
            helper.assertTrue(Kitchen.serveForTests(level, guest, Assemblies.Kind.WEDDING, ""), "the wedding's toast");
            helper.assertTrue(Kitchen.cardLine(guest).contains("mead in the toast at the wedding"), "its card: " + Kitchen.cardLine(guest));
            // A player drinks one.
            ServerPlayer p = survivor(helper);
            ItemStack back = KitchenItems.MEAD.get().finishUsingItem(new ItemStack(mead), level, p);
            boolean regen = p.hasEffect(MobEffects.REGENERATION);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(regen && back.is(Items.GLASS_BOTTLE), "a player's mead: regeneration, and the bottle back");
        });
        helper.runAtTickTime(80, () -> {
            Kitchen.secondForTests(sensible);
            int bottles = stores(level, id, Items.GLASS_BOTTLE);
            Kit.log("kt04 bottles back after the drinking: " + bottlesAt[0] + " -> " + bottles);
            helper.assertTrue(sensible.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty() && bottles >= bottlesAt[0] + 1,
                "drunk: its hand empty, the bottle back in the stores");
            helper.succeed();
        });
    }

    // ============================================================ kt05 cider

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt05_cider")
    public static void kt05_cider(GameTestHelper helper) {
        int x = 1208000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        Villages.ageForTests(id, Villages.Age.STONE);
        VillageFolkEntity brewer = another(helper, heart.south(4), id);
        VillageFolkEntity woodcutter = another(helper, heart.east(8), id);
        VillageFolkEntity guest = another(helper, heart.west(3), id);
        long day = DAY / 24000L;
        helper.runAtTickTime(5, () -> {
            Item cider = KitchenItems.CIDER.get();
            brewer.setJob(StationTask.BREW);
            woodcutter.setJob(StationTask.WOOD);
            FoundingDay.foundedForTests(id, day - 15);               // the autumn of its year
            building(level, id, "cafe", heart.offset(-16, 0, 14));
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.APPLE, 6), new ItemStack(Items.SUGAR, 2), new ItemStack(Items.GLASS_BOTTLE, 2),
                new ItemStack(Items.CRAFTING_TABLE));
            Map<Item, Integer> want = Kitchen.wantedForTests(level, id);
            helper.assertTrue(want.getOrDefault(cider, 0) >= 3, "cider for the autumn: " + want);
            String made = Kitchen.craftForTests(level, brewer);
            Kit.log("kt05 the brewer: " + made + "; cider " + stores(level, id, cider) + ", apples " + stores(level, id, Items.APPLE));
            helper.assertTrue(made != null && made.contains("cider") && stores(level, id, cider) == 2, "two ciders brewed: " + made);
            helper.assertTrue(stores(level, id, Items.APPLE) == 0 && stores(level, id, Items.SUGAR) == 0 && stores(level, id, Items.GLASS_BOTTLE) == 0,
                "three apples, a sugar and a bottle each");
            // The apples the woodcutter takes up under its trees.
            helper.assertTrue(Kitchen.applesWanted(level, woodcutter), "the brewer short of apples: the woodcutter shakes the crowns for them too");
            int had = woodcutter.countMatching(s -> s.is(Items.APPLE));
            for (int i = 0; i < 3; i++) {
                ItemEntity e = new ItemEntity(level, woodcutter.getX() + 2 + i, woodcutter.getY() + 0.2, woodcutter.getZ() - 2, new ItemStack(Items.APPLE));
                level.addFreshEntity(e);
            }
            int took = Kitchen.applesForTests(level, woodcutter);
            Kit.log("kt05 the woodcutter took up " + took + " apples (" + had + " -> " + woodcutter.countMatching(s -> s.is(Items.APPLE)) + ")");
            helper.assertTrue(took == 3, "the apples under its trees taken up: " + took);
            // The harvest festival toasted in cider.
            Kitchen.merryForTests(guest.getUUID(), false);
            int bottles = stores(level, id, Items.GLASS_BOTTLE);
            helper.assertTrue(Kitchen.serveForTests(level, guest, Assemblies.Kind.FESTIVAL, "harvest"), "the harvest's toast");
            helper.assertTrue(stores(level, id, cider) == 1 && stores(level, id, Items.GLASS_BOTTLE) == bottles + 1
                && Kitchen.cardLine(guest).contains("cider in the toast at the harvest festival"), "a bottle of cider poured round: " + Kitchen.cardLine(guest));
            helper.assertTrue(said(id, "the harvest festival was toasted in the brewer's cider"), "the chronicle hears of it");
            // The café pours it in the autumn.
            helper.assertTrue(Kitchen.onMenu(level, id, new ItemStack(cider)), "cider on the café's counter in the autumn");
            VillageFolkEntity buyer = founder;
            helper.assertTrue(Kitchen.had(level, v, buyer, new ItemStack(cider)) && buyer.hasEffect(MobEffects.ABSORPTION),
                "a cider at the café: a heart of good cheer");
            helper.succeed();
        });
    }

    // ============================================================ kt06 the fish pie

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt06_fish_pie")
    public static void kt06_fish_pie(GameTestHelper helper) {
        int x = 1210000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity cook = another(helper, heart.south(4), id);
        long day = DAY / 24000L;
        helper.runAtTickTime(5, () -> {
            Item pie = KitchenItems.FISH_PIE.get();
            cook.setJob(StationTask.COOK);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.COD, 12), new ItemStack(Items.POTATO, 24), new ItemStack(Items.WHEAT, 24),
                new ItemStack(Items.EGG, 12), new ItemStack(Items.CRAFTING_TABLE));
            // A usual catch leaves the cook nothing to do; a glut leaves it the unsold fish.
            Kitchen.glut(level, v, 1.1, 5);
            helper.assertTrue(Kitchen.glutForTests(id) == 0, "no glut, no pies");
            Kitchen.glut(level, v, 0.5, 12);
            helper.assertTrue(Kitchen.glutForTests(id) == 12 && Kitchen.wantedForTests(level, id).getOrDefault(pie, 0) == 12, "twelve fish for pies");
            for (int i = 0; i < 3; i++) {
                String made = Kitchen.craftForTests(level, cook);
                Kit.log("kt06 the cook: " + made + "; pies " + stores(level, id, pie) + ", cod " + stores(level, id, Items.COD));
                helper.assertTrue(made != null && made.contains("fish pie") && made.contains("catch"), "pies of the catch: " + made);
            }
            helper.assertTrue(stores(level, id, pie) == 12 && stores(level, id, Items.COD) == 0 && stores(level, id, Items.POTATO) == 12
                && stores(level, id, Items.WHEAT) == 12 && stores(level, id, Items.EGG) == 0, "twelve pies of twelve fish, potatoes, wheat and eggs");
            helper.assertTrue(Kitchen.glutForTests(id) == 0 && Kitchen.dayForTests(id, day)[1] == 12, "the glut baked, and booked");
            String gazette = Kitchen.gazette(id, day + 1);
            Kit.log("kt06 the gazette: " + gazette);
            helper.assertTrue(gazette != null && gazette.contains("The cook turned yesterday's catch into twelve fish pies."), "the gazette: " + gazette);
            // A hearty meal, sold at the café and the market.
            helper.assertTrue(AssistantEntity.foodQuality(new ItemStack(pie)) > AssistantEntity.foodQuality(new ItemStack(Items.BREAD)),
                "better eating than bread");
            helper.assertTrue(Kitchen.onMenu(level, id, new ItemStack(pie)) && Market.goodFor(new ItemStack(pie)) != null, "on the café's counter and the market's");
            helper.succeed();
        });
    }

    // ============================================================ kt07 herbal tea

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt07_herbal_tea")
    public static void kt07_herbal_tea(GameTestHelper helper) {
        int x = 1212000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity healer = another(helper, heart.south(4), id);
        VillageFolkEntity patient = another(helper, heart.east(3), id);
        VillageFolkEntity cook = another(helper, heart.west(3), id);
        helper.runAtTickTime(5, () -> {
            Item tea = KitchenItems.HERBAL_TEA.get();
            healer.setJob(StationTask.BREW);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.SWEET_BERRIES, 2), new ItemStack(Items.SUGAR, 2), new ItemStack(Items.GLASS_BOTTLE, 2));
            Health.catchForTests(patient, "caught out in the rain");
            helper.assertTrue(patient.health().ill(), "a cold");
            helper.assertTrue(Kitchen.wantedForTests(level, id).getOrDefault(tea, 0) >= 1, "tea for the sick");
            String made = Kitchen.craftForTests(level, healer);
            Kit.log("kt07 the healer: " + made + "; tea " + stores(level, id, tea) + ", berries " + stores(level, id, Items.SWEET_BERRIES)
                + ", sugar " + stores(level, id, Items.SUGAR) + ", bottles " + stores(level, id, Items.GLASS_BOTTLE));
            helper.assertTrue(made != null && made.contains("herbal tea") && stores(level, id, tea) == 1, "a herbal tea steeped: " + made);
            helper.assertTrue(stores(level, id, Items.SWEET_BERRIES) == 1 && stores(level, id, Items.SUGAR) == 1 && stores(level, id, Items.GLASS_BOTTLE) == 1,
                "of berries, sugar and a bottle filled at the well");
            // A cup at the bedside: a day off the cold.
            int c0 = patient.health().coldLeft();
            String tended = Kitchen.tendForTests(level, patient, healer);
            int c1 = patient.health().coldLeft();
            Kit.log("kt07 at the bedside: " + tended + "; cold " + c0 + " -> " + c1);
            helper.assertTrue(tended.contains("herbal tea"), "the healer's tea: " + tended);
            helper.assertTrue(c0 - c1 == 24000 || c1 <= 0 && c0 <= 24000, "well a day sooner: " + c0 + " -> " + c1);
            helper.assertTrue(stores(level, id, tea) == 0 && stores(level, id, Items.GLASS_BOTTLE) == 2, "the cup out of the stores, the bottle back");
            // Once a cold.
            stock(level, id, new ItemStack(tea));
            String again = Kitchen.tendForTests(level, patient, healer);
            helper.assertTrue(!again.contains("herbal tea") && stores(level, id, tea) == 1, "one cup a cold: " + again);
            // With no brewer, the café steeps it.
            healer.setJob(StationTask.FARM);
            cook.setJob(StationTask.COOK);
            Health.catchForTests(founder, "worn out at its work");
            stock(level, id, new ItemStack(Items.POPPY));                // a flower this time, for the leaf
            String cafe = Kitchen.craftForTests(level, cook);
            Kit.log("kt07 the café's cook: " + cafe);
            helper.assertTrue(cafe != null && cafe.contains("herbal tea") && stores(level, id, tea) == 2, "the café makes it with no healer: " + cafe);
            // A player drinks a cup.
            ServerPlayer p = survivor(helper);
            ItemStack back = KitchenItems.HERBAL_TEA.get().finishUsingItem(new ItemStack(tea), level, p);
            boolean regen = p.hasEffect(MobEffects.REGENERATION);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(regen && back.is(Items.GLASS_BOTTLE), "a player's cup: a short regeneration, the bottle back");
            helper.succeed();
        });
    }

    // ============================================================ kt08 the bandage

    @GameTest(template = EMPTY, timeoutTicks = 260, batch = "kt08_bandage")
    public static void kt08_bandage(GameTestHelper helper) {
        int x = 1214000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity tailor = another(helper, heart.south(4), id);
        VillageFolkEntity guard = another(helper, heart.east(4), id);
        VillageFolkEntity patient = another(helper, heart.west(3), id);
        float[] bound = new float[1];
        helper.runAtTickTime(5, () -> {
            Item bandage = KitchenItems.BANDAGE.get();
            tailor.setJob(StationTask.TAILOR);
            guard.setJob(StationTask.GUARD);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.PAPER, 4), new ItemStack(Items.STRING, 6));
            Map<Item, Integer> want = Kitchen.wantedForTests(level, id);
            helper.assertTrue(want.getOrDefault(bandage, 0) >= 3, "bandages for the watch's kit: " + want);
            String made = Kitchen.craftForTests(level, tailor);
            Kit.log("kt08 the tailor: " + made + "; bandages " + stores(level, id, bandage) + ", paper " + stores(level, id, Items.PAPER)
                + ", string " + stores(level, id, Items.STRING));
            helper.assertTrue(made != null && made.contains("bandage") && stores(level, id, bandage) == 3, "a roll of three: " + made);
            helper.assertTrue(stores(level, id, Items.PAPER) == 2 && stores(level, id, Items.STRING) == 5, "of two paper and a string");
            // The watch's kit takes them.
            List<ItemStack> given = WatchKit.fit(level, v, guard);
            int carried = guard.countCarried(s -> s.is(bandage));
            Kit.log("kt08 the guard fitted out: " + given + "; bandages " + carried);
            helper.assertTrue(carried == 3 && stores(level, id, bandage) == 0, "three bandages in the guard's kit: " + carried);
            helper.assertTrue(WatchKit.cardLine(guard).contains("3 bandages"), "the kit's line has them: " + WatchKit.cardLine(guard));
            // Hurt, out of the fight: it binds its own wound.
            guard.setHealth(8.0F);
            Kitchen.secondForTests(guard);
            helper.assertTrue(guard.countMatching(s -> s.is(bandage)) == 2 && guard.hasEffect(MobEffects.REGENERATION), "a bandage bound on");
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.OFFHAND).is(bandage), "the bandage in its hand as it binds: "
                + guard.getItemBySlot(EquipmentSlot.OFFHAND));
            helper.assertTrue(Kitchen.cardLine(guard).toLowerCase(java.util.Locale.ROOT).contains("bound a wound"), "its card: " + Kitchen.cardLine(guard));
            bound[0] = guard.getHealth();
            // The healer binds a wounded patient on its round.
            Health.layUpForTests(patient, 6000, "wound");
            patient.setHealth(10.0F);
            stock(level, id, new ItemStack(bandage));
            String tended = Kitchen.tendForTests(level, patient, tailor);
            Kit.log("kt08 the wounded: " + tended);
            helper.assertTrue(tended.contains("bandage") && patient.hasEffect(MobEffects.REGENERATION) && stores(level, id, bandage) == 0,
                "the healer binds the wound with the stores' bandage: " + tended);
            // A player binds a wound, and waits before the next.
            ServerPlayer p = survivor(helper);
            p.setHealth(10.0F);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bandage, 2));
            KitchenItems.BANDAGE.get().use(level, p, InteractionHand.MAIN_HAND);
            boolean regen = p.hasEffect(MobEffects.REGENERATION) && p.getEffect(MobEffects.REGENERATION).getAmplifier() == BandageItem.BIND_AMPLIFIER;
            boolean waits = p.getCooldowns().isOnCooldown(bandage);
            int left = p.getItemInHand(InteractionHand.MAIN_HAND).getCount();
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(regen && waits && left == 1, "a player binds a wound: regeneration " + regen + ", a wait " + waits + ", " + left + " left");
        });
        helper.runAtTickTime(125, () -> {
            float h = guard.getHealth();
            Kit.log("kt08 the guard mended: " + bound[0] + " -> " + h);
            helper.assertTrue(h >= bound[0] + 5.0F, "health back over a few seconds: " + bound[0] + " -> " + h);
            helper.succeed();
        });
    }

    // ============================================================ kt09 the books: recipes, ages, worth; the workshop

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt09_recipes_ages_worth")
    public static void kt09_recipes_ages_worth(GameTestHelper helper) {
        int x = 1216000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity shopkeeper = another(helper, heart.south(4), id);
        VillageFolkEntity hand = farHand(helper, level, heart, id, 110, 0);
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.NONE);                            // the founder at home: the far hand the only one out
            Item[] things = { KitchenItems.PACKED_LUNCH.get(), KitchenItems.CHEESE_WHEEL_ITEM.get(), KitchenItems.CHEESE_SLICE.get(),
                KitchenItems.HONEY_CAKE.get(), KitchenItems.MEAD.get(), KitchenItems.CIDER.get(), KitchenItems.FISH_PIE.get(),
                KitchenItems.HERBAL_TEA.get(), KitchenItems.BANDAGE.get() };
            Villages.Age[] ages = { Villages.Age.WOOD, Villages.Age.STONE, Villages.Age.STONE, Villages.Age.WOOD, Villages.Age.STONE,
                Villages.Age.STONE, Villages.Age.WOOD, Villages.Age.WOOD, Villages.Age.WOOD };
            double[] worth = { 0.8, 4.0, 1.0, 2.5, 1.8, 1.5, 1.2, 0.6, 0.3 };
            for (int i = 0; i < things.length; i++) {
                List<RecipeBook.Way> ways = RecipeBook.waysFor(level, things[i]);
                Villages.Age age = Tiers.of(level, things[i]);
                double each = Prices.each(things[i]);
                Kit.log("kt09 " + new ItemStack(things[i]).getHoverName().getString() + ": " + ways.size() + " recipe(s); the " + age.label
                    + "; worth " + each + "c");
                helper.assertTrue(!ways.isEmpty(), "a real recipe for " + things[i]);
                helper.assertTrue(age == ages[i], things[i] + " belongs to " + ages[i].label + ", not " + age.label);
                helper.assertTrue(Prices.known(things[i]) && Math.abs(each - worth[i]) < 0.001, things[i] + " is worth " + worth[i] + ": " + each);
                helper.assertTrue(Market.goodFor(new ItemStack(things[i])) != null, "the market deals in " + things[i]);
            }
            helper.assertTrue(RecipeBook.waysFor(level, KitchenItems.BANDAGE.get()).size() >= 2, "a bandage of paper or of wool");
            // The shop's workshop makes what no trade of the town does: a town with no cook gets its lunches there.
            shopkeeper.setJob(StationTask.SHOP);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.BREAD, 2), new ItemStack(Items.COOKED_COD, 2), new ItemStack(Items.APPLE, 2));
            String made = Kitchen.craftForTests(level, shopkeeper);
            Kit.log("kt09 the shop's workshop: " + made);
            helper.assertTrue(made != null && made.contains("packed lunch") && stores(level, id, KitchenItems.PACKED_LUNCH.get()) == 2,
                "the shop's workshop packs the far hand's lunches: " + made);
            helper.assertTrue(Kitchen.farForTests(hand), "for the far hand");
            helper.succeed();
        });
    }

    // ============================================================ kt10 the cook's book, the Production page, the card, the stage

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kt10_books_and_stage")
    public static void kt10_books_and_stage(GameTestHelper helper) {
        int x = 1218000;
        VillageFolkEntity founder = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        Kit.hold(level, x + 100, Z, 16);
        Kit.prepare(level, x + 100, Z, 16);
        VillageFolkEntity cook = another(helper, heart.south(4), id);
        VillageFolkEntity hand = farHand(helper, level, heart, id, 100, 0);
        long day = DAY / 24000L;
        helper.runAtTickTime(5, () -> {
            Item lunch = KitchenItems.PACKED_LUNCH.get();
            cook.setJob(StationTask.COOK);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(lunch, 2));
            helper.assertTrue(Kitchen.packForTests(level, hand), "a lunch packed");
            moveTo(hand, Kit.surface(level, x + 100, Z));
            level.setDayTime(DAY + 6000);
            level.updateSkyBrightness();
            helper.assertTrue(Kitchen.mealOutForTests(level, hand, true), "eaten out at the far field");
            String key = Stockroom.key(new ItemStack(lunch));
            String status = Kitchen.status(level, id, key);
            helper.assertTrue("1 eaten out this week".equals(status), "the cook's book: " + status);
            // The cook's book, as the town's books show it (the Shops page): the café's row for the packed lunch.
            boolean inBook = false;
            ListTag sellers = Stockroom.inventoryReport(level, id).getList("sellers", Tag.TAG_COMPOUND);
            for (int i = 0; i < sellers.size(); i++) {
                CompoundTag s = sellers.getCompound(i);
                if (!s.getString("id").equals("cafe")) continue;
                ListTag rows = s.getList("wares", Tag.TAG_COMPOUND);
                for (int k = 0; k < rows.size(); k++) {
                    CompoundTag r = rows.getCompound(k);
                    if (r.getString("item").equals("mc_assistant:packed_lunch") && r.getString("status").equals("1 eaten out this week")) inBook = true;
                }
            }
            helper.assertTrue(inBook, "the café's book shows the lunches eaten out");
            // The Production page's reading.
            ListTag reading = Annals.snapshot(level, v).getCompound("production").getList("reading", Tag.TAG_STRING);
            boolean read = false;
            for (int i = 0; i < reading.size(); i++) if (reading.getString(i).startsWith("Packed lunches: 1 eaten out")) read = true;
            helper.assertTrue(read, "the Production page reads it: " + reading);
            // The card.
            String card = FolkTalk.card(hand);
            helper.assertTrue(card.contains("Kitchen|") && card.contains("packed lunch"), "the hand's card: " + card);
            // The stage for the pictures.
            BlockPos at = heart.offset(22, 0, 0);
            List<String> out = Kitchen.stageForTests(level, id, at);
            Kit.log("kt10 the stage: " + out);
            boolean views = out.stream().anyMatch(s -> s.startsWith("VIEW kitchen-1-showcase")) && out.stream().anyMatch(s -> s.startsWith("VIEW kitchen-2-in-hand"));
            helper.assertTrue(views, "the views for the camera");
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(10, 6, 10));
            helper.assertTrue(frames.size() == 9, "nine frames on the wall: " + frames.size());
            int cuts = 0;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-6, -2, -6), at.offset(9, 4, 9))) {
                if (level.getBlockState(p).getBlock() instanceof CheeseWheelBlock) cuts |= 1 << level.getBlockState(p).getValue(CheeseWheelBlock.CUT);
            }
            helper.assertTrue(cuts == 0b1111, "the cheese in all four cuts: " + Integer.toBinaryString(cuts));
            helper.assertTrue(out.stream().anyMatch(s -> s.startsWith("HOLDS")), "folk with the things in hand");
            helper.assertTrue(Kitchen.releaseForTests(level, id) >= 1, "and let go");
            helper.succeed();
        });
    }
}
