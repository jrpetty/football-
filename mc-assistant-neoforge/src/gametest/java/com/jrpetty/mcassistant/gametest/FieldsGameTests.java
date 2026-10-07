package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.FeedTroughBlock;
import com.jrpetty.mcassistant.block.FieldBlockEntity;
import com.jrpetty.mcassistant.block.FishTrapBlock;
import com.jrpetty.mcassistant.block.NestingBoxBlock;
import com.jrpetty.mcassistant.block.RainBarrelBlock;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.Droughts;
import com.jrpetty.mcassistant.entity.FieldTools;
import com.jrpetty.mcassistant.entity.Fleet;
import com.jrpetty.mcassistant.entity.Links;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.item.BeeSmokerItem;
import com.jrpetty.mcassistant.item.FieldItems;
import com.jrpetty.mcassistant.item.SeedSatchelItem;
import com.jrpetty.mcassistant.item.WateringCanItem;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [fields] The tools of the fields and the pens, the bees and the water (item/FieldItems, entity/FieldTools): each made
 * by its maker out of the stores by its real recipe, and put to use by the folk (and a player) with a real effect.
 *
 * <ul>
 * <li><b>fi01</b>: the smith beats a watering can of five of the stores' copper; the farmer takes it, fills it at a rain
 *     barrel, waters its young wheat (the farmland soaked, the crops given their growth); dry, it goes to the pond to
 *     fill it; a player waters with it too; its card says so.</li>
 * <li><b>fi02</b>: in a drought, a farmer with a can carries no bucket, and its watering wets a stunted crop's ground so
 *     it grows at its full pace; a farmer without one carries a bucket, and draws it from the rain barrel first.</li>
 * <li><b>fi03</b>: the tailor stitches a seed satchel of two leather and a string; the farmer packs it at the stores,
 *     sows out of it with no trip back (its pace the quicker, its card counting the trips saved), goes back to fill it
 *     when it runs dry; a player sows a three-by-three out of it.</li>
 * <li><b>fi04</b>: the smith beats a copper sickle (the stores short of copper for the can, it is the sickle); a
 *     farmer cutting one ripe wheat reaps the three-by-three, sows it again and keeps the drops; a player's swing reaps
 *     the ripe crops round the one it breaks.</li>
 * <li><b>fi05</b>: the rancher makes a nesting box of planks and wheat and sets it in its ground; a hen lays into it,
 *     and lays the quicker on its hay; the rancher empties it into the stores, and the cook bakes a pumpkin pie with
 *     the egg.</li>
 * <li><b>fi06</b>: the rancher makes a feed trough of planks and a slab, sets it out and fills it with the stores' wheat
 *     for its cows; a pair breeds off it (two wheat eaten), the herd draws to it, the rancher's own breeding stands
 *     aside, and the pen's limit holds.</li>
 * <li><b>fi07</b>: the fisher weaves a fish trap of sticks, string and a cod and sets it in the pond; it catches up to
 *     six; on a rainy day (the boats in) the fisher goes round and lands the catch, junk and all; passing, it empties
 *     one; a player empties its own.</li>
 * <li><b>fi08</b>: the shop's hand coopers a rain barrel of seven planks and a copper; the town sets it at the edge of
 *     the field; it fills in the rain; a player fills a bucket at it; the bucket chain draws from it with no pond near;
 *     a workshop with no iron for a cauldron of water against fire has a barrel set by it instead.</li>
 * <li><b>fi09</b>: the smith makes a bee smoker of copper, leather and coal; the beekeeper smokes and empties every full
 *     hive on its round, a comb or a bottle more for each, and calms an angry bee; a player's puff calms the bees and
 *     lets it take a full hive's comb without a sting.</li>
 * <li><b>fi10</b>: the eight have their recipes, their ages, their worth and their place on the market; the copper the
 *     miners bring is taken to the smelter's furnace, and in a town with neither smith nor shop the smelter beats the
 *     copper tools cold (and stands aside once there is a smith).</li>
 * </ul>
 *
 * <p>Each on its own ground (x 1,220,000 to 1,238,000, z 66,000), what it checks called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FieldsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 3;

    // ------------------------------------------------------------------ the ground and the town

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 16; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A village's first folk on clean flat ground at x, by day in fine weather, the tools' rounds at any hour. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        FieldTools.resetForTests();
        level.setDayTime(DAY + 5000);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        FieldTools.anyHourForTests(level, true);
        Villages.ageForTests(f.ownerId(), Villages.Age.STONE);
        return f;
    }

    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
        return f;
    }

    /** This folk at this trade, its ground round where it stands. */
    private static VillageFolkEntity trade(VillageFolkEntity f, StationTask t) {
        f.setJob(t);
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

    private static int stores(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    private static int stores(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    /** A clean pack: nothing of the starter kit's seed (so what the tools hand it is counted plainly). */
    private static void noSeed(VillageFolkEntity f) {
        f.removeMatching(AssistantEntity.FARM_SEEDS, 999);
    }

    private static ItemStack carried(VillageFolkEntity f, Item it) {
        if (f.getItemBySlot(EquipmentSlot.OFFHAND).is(it)) return f.getItemBySlot(EquipmentSlot.OFFHAND);
        for (ItemStack s : f.getInventoryItems()) if (s.is(it)) return s;
        return ItemStack.EMPTY;
    }

    /** A patch of farmland (moisture as given) round here, each square sown with wheat of this age. */
    private static List<BlockPos> field(ServerLevel level, BlockPos centre, int r, int moisture, int age) {
        List<BlockPos> crops = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos g = centre.offset(dx, -1, dz);
                level.setBlock(g, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, moisture), 2);
                level.setBlock(g.above(), ((CropBlock) Blocks.WHEAT).getStateForAge(age), 2);
                crops.add(g.above());
            }
        }
        return crops;
    }

    private static int ages(ServerLevel level, List<BlockPos> crops) {
        int n = 0;
        for (BlockPos p : crops) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof CropBlock c) n += c.getAge(st);
        }
        return n;
    }

    private static BlockHitResult hit(BlockPos p) {
        return new BlockHitResult(Vec3.atCenterOf(p).add(0, 0.5, 0), Direction.UP, p, false);
    }

    private static BlockPos barrel(ServerLevel level, BlockPos at, int water) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        level.setBlockAndUpdate(p, FieldItems.RAIN_BARREL.get().defaultBlockState().setValue(RainBarrelBlock.WATER, water));
        return p;
    }

    private static String first(UUID village, String key) {
        return Ledger.note(village, "fields/first/" + key);
    }

    // ============================================================ fi01: the copper watering can

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi01_can")
    public static void fi01_can(GameTestHelper helper) {
        int x = 1220000;
        VillageFolkEntity smith = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = smith.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = another(helper, heart.offset(14, 0, 0), id);
        helper.runAtTickTime(5, () -> {
            trade(smith, StationTask.SMITH);
            trade(farmer, StationTask.FARM);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.COPPER_INGOT, 10), new ItemStack(Items.CRAFTING_TABLE));
            int copper0 = stores(level, id, Items.COPPER_INGOT);
            Map<Item, Integer> want = FieldTools.wantedForTests(level, Villages.get(id));
            String made = FieldTools.craftForTests(level, smith);
            int copper1 = stores(level, id, Items.COPPER_INGOT), cans = stores(level, id, FieldItems.WATERING_CAN.get());
            Kit.log("fi01 the town wants " + want + "; the smith: " + made + "; copper " + copper0 + " -> " + copper1 + "; cans " + cans);
            helper.assertTrue(want.getOrDefault(FieldItems.WATERING_CAN.get(), 0) == 1, "a can wanted for the farmer: " + want);
            helper.assertTrue(made != null && made.contains("watering can") && cans == 1, "the smith made the can: " + made);
            helper.assertTrue(copper1 == copper0 - 5, "of five of the stores' copper: " + copper0 + " -> " + copper1);
            helper.assertTrue(first(id, "made/copper_watering_can") != null, "the chronicle has the town's first can");

            // The farmer's field: young wheat on dry farmland round where it stands, a rain barrel at its edge.
            BlockPos at = farmer.blockPosition();
            List<BlockPos> crops = field(level, at.offset(0, 0, 3), 3, 0, 1);
            BlockPos rain = barrel(level, at.offset(-5, 0, 0), 2);
            FieldTools.roundForTests(level, farmer);
            ItemStack can = carried(farmer, FieldItems.WATERING_CAN.get());
            String errand = FieldTools.errandForTests(farmer);
            Kit.log("fi01 the farmer's round: can " + !can.isEmpty() + " (" + WateringCanItem.water(can) + "); errand " + errand);
            helper.assertTrue(!can.isEmpty() && stores(level, id, FieldItems.WATERING_CAN.get()) == 0, "the farmer took the can out of the stores");
            helper.assertTrue(errand != null && errand.startsWith("FILL") && errand.contains("rain barrel"), "the can dry: off to the barrel: " + errand);
            FieldTools.errandNowForTests(level, farmer);
            can = carried(farmer, FieldItems.WATERING_CAN.get());
            helper.assertTrue(WateringCanItem.water(can) == WateringCanItem.CAPACITY && RainBarrelBlock.water(level, rain) == 1,
                "filled at the barrel, a bucket's worth out of it: " + WateringCanItem.water(can) + ", barrel " + RainBarrelBlock.water(level, rain));

            // Its watering: the farmland soaked, the crops given their growth.
            farmer.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 3.5);
            int age0 = ages(level, crops), poured = 0, soaked = 0, grew = 0;
            for (int i = 0; i < 4; i++) {
                WateringCanItem.Poured p = FieldTools.pourForTests(level, farmer);
                if (p == null) break;
                poured++;
                soaked += p.soaked();
                grew += p.grew();
            }
            int age1 = ages(level, crops);
            can = carried(farmer, FieldItems.WATERING_CAN.get());
            int wet = 0;
            for (BlockPos c : crops) if (level.getBlockState(c.below()).getValue(FarmBlock.MOISTURE) == FarmBlock.MAX_MOISTURE) wet++;
            Kit.log("fi01 watered: " + poured + " patches, " + soaked + " squares soaked (" + wet + " of " + crops.size() + " wet), " + grew
                + " crops grew, ages " + age0 + " -> " + age1 + "; the can " + WateringCanItem.water(can) + "; held " + farmer.getItemBySlot(EquipmentSlot.OFFHAND)
                + "; card: " + FieldTools.cardLine(farmer) + "; doing: " + FieldTools.doing(farmer));
            helper.assertTrue(poured == 4 && soaked >= 20 && wet >= 20, "four patches watered, the ground soaked: " + soaked + ", " + wet);
            helper.assertTrue(age1 > age0 && grew > 0, "and the wheat the further on for it: " + age0 + " -> " + age1);
            helper.assertTrue(WateringCanItem.water(can) == WateringCanItem.CAPACITY - 4, "four waterings out of the can: " + WateringCanItem.water(can));
            helper.assertTrue(farmer.getItemBySlot(EquipmentSlot.OFFHAND).is(FieldItems.WATERING_CAN.get()), "the can in its hand as it waters");
            String card = FieldTools.cardLine(farmer);
            helper.assertTrue(card != null && card.contains("copper watering can") && card.contains("4 patches watered"), "its card: " + card);
            String doing = FieldTools.doing(farmer);
            helper.assertTrue(doing != null && doing.contains("watering"), "what it says it is doing: " + doing);

            // Dry again, and the barrel dry: to the water to fill it.
            WateringCanItem.setWater(can, 0);
            level.setBlockAndUpdate(rain, FieldItems.RAIN_BARREL.get().defaultBlockState());
            BlockPos pond = at.offset(-9, -1, -6);
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) level.setBlockAndUpdate(pond.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
            FieldTools.roundForTests(level, farmer);
            String toPond = FieldTools.errandForTests(farmer);
            FieldTools.errandNowForTests(level, farmer);
            Kit.log("fi01 dry again: " + toPond + "; the can " + WateringCanItem.water(carried(farmer, FieldItems.WATERING_CAN.get())));
            helper.assertTrue(toPond != null && toPond.contains("the water") && WateringCanItem.water(carried(farmer, FieldItems.WATERING_CAN.get())) == 16,
                "filled at the pond: " + toPond);

            // A player waters with it too.
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            ItemStack mine = new ItemStack(FieldItems.WATERING_CAN.get());
            WateringCanItem.fill(mine);
            p.setItemInHand(InteractionHand.MAIN_HAND, mine);
            List<BlockPos> patch = field(level, at.offset(10, 0, -8), 1, 0, 0);
            var r = mine.getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit(patch.get(4).below())));
            int pwet = 0;
            for (BlockPos c : patch) if (level.getBlockState(c.below()).getValue(FarmBlock.MOISTURE) == FarmBlock.MAX_MOISTURE) pwet++;
            Kit.log("fi01 the player's can: " + r + ", " + pwet + " of 9 soaked, " + WateringCanItem.water(p.getMainHandItem()) + " left");
            helper.assertTrue(r.consumesAction() && pwet == 9 && WateringCanItem.water(p.getMainHandItem()) == 15, "the player watered its patch");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi02: the drought

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi02_drought")
    public static void fi02_drought(GameTestHelper helper) {
        int x = 1222000;
        VillageFolkEntity a = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity b = another(helper, heart.offset(-16, 0, 0), id);
        helper.runAtTickTime(5, () -> {
            trade(a, StationTask.FARM);
            trade(b, StationTask.FARM);
            // An hour of the working day when neither is on its break: Droughts carries water in working hours only.
            for (long t = 1500L; t <= 11000L; t += 250L) {
                level.setDayTime(DAY + t);
                level.updateSkyBrightness();
                if (!a.offWorkNow() && !b.offWorkNow()) break;
            }
            Kit.log("fi02 the hour: " + level.getDayTime() % 24000L + "; off work " + a.offWorkNow() + " " + b.offWorkNow());
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.BUCKET, 2));
            // Both fields dry: young wheat on farmland with no water near.
            List<BlockPos> fieldA = field(level, a.blockPosition().offset(0, 0, 2), 2, 0, 2);
            List<BlockPos> fieldB = field(level, b.blockPosition().offset(0, 0, 2), 2, 0, 2);
            ItemStack can = new ItemStack(FieldItems.WATERING_CAN.get());
            WateringCanItem.fill(can);
            a.insertItem(can);
            Droughts.droughtForTests(level, id, true);
            BlockPos crop = fieldA.get(12);
            boolean stunted0 = Droughts.stuntedForTests(level, crop);
            // The farmer with the can: no bucket; its can on the dry ground instead.
            boolean bucketA = FieldTools.droughtCarryForTests(a, level);
            a.moveTo(crop.getX() + 0.5, crop.getY(), crop.getZ() + 0.5);
            WateringCanItem.Poured p = FieldTools.pourForTests(level, a);
            boolean stunted1 = Droughts.stuntedForTests(level, crop);
            Kit.log("fi02 the can farmer: carries a bucket " + bucketA + "; poured " + p + "; stunted " + stunted0 + " -> " + stunted1
                + "; card " + FieldTools.cardLine(a));
            helper.assertTrue(stunted0, "the dry wheat withers in the drought");
            helper.assertTrue(!bucketA && !FieldTools.droughtCarryingForTests(a), "a farmer with a can carries no bucket");
            helper.assertTrue(p != null && p.soaked() >= 9 && !stunted1, "the can wets it, and it grows at its full pace: " + p);
            helper.assertTrue(FieldTools.cardLine(a).contains("in the drought"), "its card counts the drought's watering");
            helper.assertTrue(first(id, "drought-can") != null, "the chronicle has it");
            // The farmer without: a bucket of the stores', drawn at the rain barrel before the pond.
            BlockPos rain = barrel(level, b.blockPosition().offset(3, 0, -3), 3);
            BlockPos pond = b.blockPosition().offset(-12, -1, -12);
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) level.setBlockAndUpdate(pond.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
            boolean bucketB = FieldTools.droughtCarryForTests(b, level);
            b.moveTo(rain.getX() + 1.5, rain.getY(), rain.getZ() + 0.5);
            FieldTools.droughtCarryForTests(b, level);
            int left = RainBarrelBlock.water(level, rain);
            Kit.log("fi02 the bucket farmer: set out " + bucketB + "; the barrel 3 -> " + left + "; carries a water bucket "
                + (b.countCarried(s -> s.is(Items.WATER_BUCKET)) > 0));
            helper.assertTrue(bucketB, "a farmer with no can carries a bucket");
            helper.assertTrue(left == 2 && b.countCarried(s -> s.is(Items.WATER_BUCKET)) == 1, "and fills it at the rain barrel first: " + left);
            Droughts.droughtForTests(level, id, false);
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi03: the seed satchel

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi03_satchel")
    public static void fi03_satchel(GameTestHelper helper) {
        int x = 1224000;
        VillageFolkEntity tailor = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = tailor.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = another(helper, heart.offset(2, 0, 2), id);
        helper.runAtTickTime(5, () -> {
            trade(tailor, StationTask.TAILOR);
            trade(farmer, StationTask.FARM);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.LEATHER, 6), new ItemStack(Items.STRING, 4), new ItemStack(Items.CRAFTING_TABLE),
                new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.WHEAT_SEEDS, 36), new ItemStack(Items.CARROT, 40));
            int leather0 = stores(level, id, Items.LEATHER), string0 = stores(level, id, Items.STRING);
            String made = FieldTools.craftForTests(level, tailor);
            int leather1 = stores(level, id, Items.LEATHER), string1 = stores(level, id, Items.STRING);
            Kit.log("fi03 the tailor: " + made + "; leather " + leather0 + " -> " + leather1 + ", string " + string0 + " -> " + string1);
            helper.assertTrue(made != null && made.contains("seed satchel"), "the tailor made the satchel: " + made);
            helper.assertTrue(leather1 == leather0 - 2 && string1 == string0 - 1, "of two leather and a string");

            // At the stores: it takes the satchel and packs it.
            noSeed(farmer);
            BlockPos spot = farmer.storesSpot(level, id);
            farmer.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            int seeds0 = stores(level, id, Items.WHEAT_SEEDS) + stores(level, id, Items.CARROT);
            FieldTools.roundForTests(level, farmer);
            ItemStack bag = carried(farmer, FieldItems.SEED_SATCHEL.get());
            int inBag = SeedSatchelItem.total(bag), inHand = farmer.countMatching(AssistantEntity.FARM_SEEDS);
            int seeds1 = stores(level, id, Items.WHEAT_SEEDS) + stores(level, id, Items.CARROT);
            Kit.log("fi03 packed at the stores: the satchel " + inBag + ", in hand " + inHand + "; the stores' seed " + seeds0 + " -> " + seeds1);
            helper.assertTrue(!bag.isEmpty() && inBag >= 50, "the satchel packed with the field's seed: " + inBag);
            helper.assertTrue(seeds0 - seeds1 == inBag + inHand, "every seed of it out of the stores: " + (seeds0 - seeds1) + " = " + inBag + " + " + inHand);
            helper.assertTrue(inHand >= 12, "and a handful in its hand to sow with: " + inHand);
            helper.assertTrue(FieldTools.satchelSeed(farmer) == inBag, "its seed at its hip counts as carried");

            // Out in the field it sows its handful; the satchel hands it more, with no walk back.
            farmer.moveTo(heart.getX() + 20.5, heart.getY(), heart.getZ() + 0.5);
            farmer.removeMatching(AssistantEntity.FARM_SEEDS, 999);
            int before = SeedSatchelItem.total(bag);
            FieldTools.roundForTests(level, farmer);
            int handed = farmer.countMatching(AssistantEntity.FARM_SEEDS);
            String tally = FieldTools.tallyForTests(farmer);
            String pace = farmer.paceLine();
            Kit.log("fi03 in the field: handed " + handed + " (the satchel " + before + " -> " + SeedSatchelItem.total(bag) + "); errand "
                + FieldTools.errandForTests(farmer) + "; tally " + tally + "; pace: " + pace + "; card: " + FieldTools.cardLine(farmer));
            helper.assertTrue(handed > 0 && SeedSatchelItem.total(bag) == before - handed, "seed out of the satchel into its hand: " + handed);
            helper.assertTrue(FieldTools.errandForTests(farmer) == null, "no trip to the stores for it");
            helper.assertTrue(FieldTools.workPercent(farmer) > 0 && pace.contains("seed satchel"), "the quicker for it, on its pace line: " + pace);
            String card = FieldTools.cardLine(farmer);
            helper.assertTrue(card != null && card.contains("seed satchel") && card.contains("trip"), "its card counts the trips saved: " + card);

            // Run dry, far out: one trip back to fill it.
            for (int i = 0; i < 8 && SeedSatchelItem.total(bag) > 0; i++) SeedSatchelItem.take(bag, s -> true, 999);   // a kind at a time
            farmer.removeMatching(AssistantEntity.FARM_SEEDS, 999);
            FieldTools.roundForTests(level, farmer);
            String trip = FieldTools.errandForTests(farmer);
            FieldTools.errandNowForTests(level, farmer);
            int refilled = SeedSatchelItem.total(carried(farmer, FieldItems.SEED_SATCHEL.get()));
            Kit.log("fi03 run dry: " + trip + "; refilled " + refilled);
            helper.assertTrue(trip != null && trip.startsWith("PACK") && refilled > 0, "back to the stores to fill it: " + trip + ", " + refilled);

            // A player sows a three-by-three out of it, and the satchel takes only seed.
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            ItemStack mine = new ItemStack(FieldItems.SEED_SATCHEL.get());
            ItemStack dirt = SeedSatchelItem.add(mine, new ItemStack(Items.DIRT, 5));
            SeedSatchelItem.add(mine, new ItemStack(Items.WHEAT_SEEDS, 20));
            p.setItemInHand(InteractionHand.MAIN_HAND, mine);
            BlockPos g = heart.offset(-12, -1, 12);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(g.offset(dx, 0, dz), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
            }
            var r = mine.getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit(g)));
            int sown = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (level.getBlockState(g.offset(dx, 1, dz)).is(Blocks.WHEAT)) sown++;
            Kit.log("fi03 the player: " + r + ", " + sown + " sown, " + SeedSatchelItem.total(mine) + " left; dirt refused " + dirt.getCount());
            helper.assertTrue(dirt.getCount() == 5, "the satchel takes only seed");
            helper.assertTrue(sown == 9 && SeedSatchelItem.total(mine) == 11, "a three-by-three sown out of it: " + sown);
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi04: the copper sickle

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi04_sickle")
    public static void fi04_sickle(GameTestHelper helper) {
        int x = 1226000;
        VillageFolkEntity smith = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = smith.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = another(helper, heart.offset(14, 0, 0), id);
        helper.runAtTickTime(5, () -> {
            trade(smith, StationTask.SMITH);
            trade(farmer, StationTask.FARM);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.COPPER_INGOT, 3), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.CRAFTING_TABLE));
            String made = FieldTools.craftForTests(level, smith);
            Kit.log("fi04 the smith: " + made + "; copper " + stores(level, id, Items.COPPER_INGOT) + "; sickles "
                + stores(level, id, FieldItems.COPPER_SICKLE.get()));
            helper.assertTrue(made != null && made.contains("sickle") && stores(level, id, FieldItems.COPPER_SICKLE.get()) == 1,
                "three copper and a stick: not enough for the can, the sickle: " + made);
            helper.assertTrue(stores(level, id, Items.COPPER_INGOT) == 0, "its three copper used");

            // The farmer cuts one ripe wheat: the three-by-three round it reaped, sown again, the drops in its pack.
            FieldTools.roundForTests(level, farmer);
            helper.assertTrue(!carried(farmer, FieldItems.COPPER_SICKLE.get()).isEmpty(), "the farmer took the sickle");
            noSeed(farmer);
            BlockPos c = farmer.blockPosition().offset(0, 0, 3);
            List<BlockPos> ripe = field(level, c, 1, 7, CropBlock.MAX_AGE);
            int wheat0 = farmer.countMatching(s -> s.is(Items.WHEAT));
            level.destroyBlock(c, false);                                    // the one FarmGoal cut (doHarvest)
            int more = FieldTools.reapForTests(farmer, c);
            int sownAgain = 0;
            for (BlockPos q : ripe) if (!q.equals(c) && level.getBlockState(q).is(Blocks.WHEAT)
                && ((CropBlock) Blocks.WHEAT).getAge(level.getBlockState(q)) == 0) sownAgain++;
            int wheat1 = farmer.countMatching(s -> s.is(Items.WHEAT));
            Kit.log("fi04 the farmer's swing: " + more + " more reaped, " + sownAgain + " sown again; wheat " + wheat0 + " -> " + wheat1
                + "; in hand " + farmer.getItemBySlot(EquipmentSlot.OFFHAND) + "; tally " + FieldTools.tallyForTests(farmer) + "; card " + FieldTools.cardLine(farmer));
            helper.assertTrue(more == 8 && sownAgain == 8, "the eight round it reaped and sown again: " + more + ", " + sownAgain);
            helper.assertTrue(wheat1 - wheat0 == 8, "their wheat into its pack: " + (wheat1 - wheat0));
            helper.assertTrue(farmer.getItemBySlot(EquipmentSlot.OFFHAND).is(FieldItems.COPPER_SICKLE.get()), "the sickle in its hand");
            helper.assertTrue(first(id, "sickle") != null, "and in the chronicle");

            // A player's swing through a ripe crop reaps the ripe ones round it.
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            ItemStack sickle = new ItemStack(FieldItems.COPPER_SICKLE.get());
            p.setItemInHand(InteractionHand.MAIN_HAND, sickle);
            BlockPos pc = heart.offset(-12, 0, -12);
            pc = Kit.surface(level, pc.getX(), pc.getZ());
            List<BlockPos> pr = field(level, pc, 1, 7, CropBlock.MAX_AGE);
            BlockState aimed = level.getBlockState(pc);
            level.destroyBlock(pc, true);
            sickle.getItem().mineBlock(sickle, level, aimed, pc, p);
            int replanted = 0;
            for (BlockPos q : pr) if (!q.equals(pc) && level.getBlockState(q).is(Blocks.WHEAT)
                && ((CropBlock) Blocks.WHEAT).getAge(level.getBlockState(q)) == 0) replanted++;
            int dropped = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pc).inflate(3))) if (e.getItem().is(Items.WHEAT)) dropped += e.getItem().getCount();
            Kit.log("fi04 the player's swing: " + replanted + " round it cut and sown again, " + dropped + " wheat dropped; the sickle " + sickle.getDamageValue());
            helper.assertTrue(replanted == 8 && dropped >= 9, "the ripe ones round it reaped: " + replanted + ", " + dropped);
            helper.assertTrue(sickle.getDamageValue() == 1, "a cut out of the sickle");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi05: the nesting box

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi05_nest")
    public static void fi05_nest(GameTestHelper helper) {
        int x = 1228000;
        VillageFolkEntity rancher = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = rancher.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity cook = another(helper, heart.offset(-6, 0, 6), id);
        helper.runAtTickTime(5, () -> {
            rancher.moveTo(heart.getX() + 14.5, heart.getY(), heart.getZ() + 0.5);
            trade(rancher, StationTask.RANCH);
            trade(cook, StationTask.COOK);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.WHEAT, 24),
                new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.PUMPKIN, 2), new ItemStack(Items.SUGAR, 4));
            BlockPos ground = rancher.workZone().center();
            List<Chicken> hens = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Chicken h = EntityType.CHICKEN.create(level);
                h.moveTo(ground.getX() + 1.5 + i, ground.getY(), ground.getZ() + 1.5, 0, 0);
                level.addFreshEntity(h);
                hens.add(h);
            }
            int planks0 = stores(level, id, s -> s.is(Items.OAK_PLANKS)), wheat0 = stores(level, id, Items.WHEAT);
            FieldTools.roundForTests(level, rancher);                  // none in the stores: it makes one
            int boxes = stores(level, id, FieldItems.NESTING_BOX_ITEM.get());
            int planks1 = stores(level, id, s -> s.is(Items.OAK_PLANKS)), wheat1 = stores(level, id, Items.WHEAT);
            Kit.log("fi05 the rancher made: boxes " + boxes + "; planks " + planks0 + " -> " + planks1 + ", wheat " + wheat0 + " -> " + wheat1);
            helper.assertTrue(boxes == 1 && planks1 == planks0 - 5 && wheat1 == wheat0 - 1, "a box of five planks and a wheat");
            FieldTools.roundForTests(level, rancher);                  // and sets it out
            List<BlockPos> set = FieldBlockEntity.near(level, FieldBlockEntity.Kind.BOX, ground, 8);
            helper.assertTrue(set.size() == 1 && stores(level, id, FieldItems.NESTING_BOX_ITEM.get()) == 0, "set out on its ground: " + set);
            BlockPos box = set.get(0);
            FieldBlockEntity be = (FieldBlockEntity) level.getBlockEntity(box);
            helper.assertTrue(be.hay() > 0 && level.getBlockState(box).getValue(NestingBoxBlock.HAY), "lined with hay");

            // A hen lays: into the box, not onto the ground.
            Chicken hen = hens.get(0);
            hen.moveTo(box.getX() + 1.5, box.getY(), box.getZ() + 0.5, 0, 0);
            hen.spawnAtLocation(Items.EGG);
            int loose = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(box).inflate(8))) if (e.getItem().is(Items.EGG)) loose++;
            Kit.log("fi05 laid: in the box " + be.count() + ", loose " + loose + "; the box shows " + level.getBlockState(box));
            helper.assertTrue(be.count() == 1 && loose == 0 && level.getBlockState(box).getValue(NestingBoxBlock.EGGS) == 1, "laid in the box, and to be seen there");
            // On hay a hen lays the sooner.
            hen.eggTime = 1000;
            FieldTools.boxesForTests(level);
            int withHay = hen.eggTime;
            be.setHay(0);
            hen.eggTime = 1000;
            FieldTools.boxesForTests(level);
            int bare = hen.eggTime;
            Kit.log("fi05 the hen's next egg: on hay 1000 -> " + withHay + ", bare 1000 -> " + bare);
            helper.assertTrue(withHay < 1000 && bare == 1000, "the sooner on hay: " + withHay + ", " + bare);
            for (int i = 0; i < 4; i++) hen.spawnAtLocation(Items.EGG);
            helper.assertTrue(be.count() == 5 && level.getBlockState(box).getValue(NestingBoxBlock.EGGS) == 2, "a clutch: " + be.count());

            // The rancher's round: the eggs into the stores, the box lined afresh.
            rancher.moveTo(box.getX() + 1.5, box.getY(), box.getZ() + 0.5);
            int eggs0 = stores(level, id, Items.EGG);
            FieldTools.roundForTests(level, rancher);
            FieldTools.errandNowForTests(level, rancher);
            int eggs1 = stores(level, id, Items.EGG);
            Kit.log("fi05 emptied: the stores' eggs " + eggs0 + " -> " + eggs1 + "; the box " + be.count() + ", hay " + be.hay() + "; card " + FieldTools.cardLine(rancher));
            helper.assertTrue(eggs1 - eggs0 == 5 && be.count() == 0 && be.hay() > 0, "five eggs to the stores, the hay renewed");
            helper.assertTrue(first(id, "eggs") != null, "the chronicle has it");

            // The cook's pie, with an egg of the box.
            Villages.Village v = Villages.get(id);
            Bench.Hand hand = Bench.handOf(level, v, cook, null);
            Bench.Plan plan = Bench.plan(level, v, Items.PUMPKIN_PIE, 1, hand);
            ItemStack pie = plan.ok() ? Bench.make(level, v, plan, cook, hand) : ItemStack.EMPTY;
            Kit.log("fi05 the cook: " + plan.chain() + " -> " + pie + "; eggs " + stores(level, id, Items.EGG));
            helper.assertTrue(!pie.isEmpty() && stores(level, id, Items.EGG) == eggs1 - 1, "a pumpkin pie of the box's egg");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi06: the feed trough

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi06_trough")
    public static void fi06_trough(GameTestHelper helper) {
        int x = 1230000;
        VillageFolkEntity rancher = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = rancher.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            rancher.moveTo(heart.getX() + 14.5, heart.getY(), heart.getZ() + 0.5);
            trade(rancher, StationTask.RANCH);
            rancher.removeMatching(AssistantEntity.BREEDING_FOOD, 999);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.WHEAT, 40),
                new ItemStack(Items.CRAFTING_TABLE));
            BlockPos ground = rancher.workZone().center();
            List<Cow> cows = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Cow c = EntityType.COW.create(level);
                c.moveTo(ground.getX() + 0.5 + i * 2, ground.getY(), ground.getZ() - 2.5, 0, 0);
                level.addFreshEntity(c);
                cows.add(c);
            }
            int planks0 = stores(level, id, s -> s.is(Items.OAK_PLANKS));
            FieldTools.roundForTests(level, rancher);
            int made = stores(level, id, FieldItems.FEED_TROUGH_ITEM.get()), planks1 = stores(level, id, s -> s.is(Items.OAK_PLANKS));
            int slabs = stores(level, id, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_SLABS));
            Kit.log("fi06 the rancher made: troughs " + made + "; planks " + planks0 + " -> " + planks1 + "; slabs to spare " + slabs);
            // Four planks and a slab; the slab cut from three planks at the bench, the other five slabs kept in the stores.
            helper.assertTrue(made == 1 && planks0 - planks1 == 7 && slabs == 5, "a trough of planks and a slab: " + (planks0 - planks1) + ", " + slabs);
            FieldTools.roundForTests(level, rancher);
            List<BlockPos> set = FieldBlockEntity.near(level, FieldBlockEntity.Kind.TROUGH, ground, 8);
            helper.assertTrue(set.size() == 1, "set out on its ground: " + set);
            BlockPos trough = set.get(0);
            rancher.moveTo(trough.getX() + 1.5, trough.getY(), trough.getZ() + 0.5);
            int wheat0 = stores(level, id, Items.WHEAT);
            FieldTools.roundForTests(level, rancher);
            FieldTools.errandNowForTests(level, rancher);
            int feed = FeedTroughBlock.feedIn(level, trough), wheat1 = stores(level, id, Items.WHEAT);
            Kit.log("fi06 filled: " + FeedTroughBlock.words(level, trough) + "; the stores' wheat " + wheat0 + " -> " + wheat1 + "; shows "
                + level.getBlockState(trough).getValue(FeedTroughBlock.FEED));
            helper.assertTrue(feed == 16 && wheat0 - wheat1 == 16, "sixteen of the stores' wheat for the cows: " + feed);
            helper.assertTrue(level.getBlockState(trough).getValue(FeedTroughBlock.FEED) == 2, "the grain to be seen in it");
            helper.assertTrue(FieldTools.troughFeeds(rancher), "the rancher's own breeding stands aside for the trough");

            // A pair breeds off it, each eating one.
            boolean bred = FieldTools.breedForTests(level, trough);
            Kit.log("fi06 bred: " + bred + "; in love " + cows.get(0).isInLove() + " " + cows.get(1).isInLove() + "; feed " + FeedTroughBlock.feedIn(level, trough));
            helper.assertTrue(bred && cows.get(0).isInLove() && cows.get(1).isInLove() && FeedTroughBlock.feedIn(level, trough) == 14,
                "the pair in love, two wheat eaten");
            helper.assertTrue(!FieldTools.breedForTests(level, trough), "and no more till another pair is ready");
            // A cow off across the ground wanders back to it.
            Cow stray = EntityType.COW.create(level);
            stray.moveTo(trough.getX() + 9.5, trough.getY(), trough.getZ() + 0.5, 0, 0);
            level.addFreshEntity(stray);
            int drawn = FieldTools.pullForTests(level, trough);
            Kit.log("fi06 drawn to it: " + drawn);
            helper.assertTrue(drawn >= 1 && stray.getNavigation().isInProgress(), "the stray makes for the trough");
            // The pen's limit: eight cows near (the stray still beyond its reach), and no pair is bred, ready or not.
            for (Cow c : cows) c.resetLove();
            for (int i = 0; i < 6; i++) {
                Cow c = EntityType.COW.create(level);
                c.moveTo(trough.getX() - 3.5 + i, trough.getY(), trough.getZ() + 2.5, 0, 0);
                level.addFreshEntity(c);
            }
            boolean atLimit = FieldTools.breedForTests(level, trough);
            Kit.log("fi06 at the pen's limit (8 cows): bred " + atLimit + "; card " + FieldTools.cardLine(rancher));
            helper.assertTrue(!atLimit && FeedTroughBlock.feedIn(level, trough) == 14, "the limit holds, the feed kept");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi07: the fish trap

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi07_trap")
    public static void fi07_trap(GameTestHelper helper) {
        int x = 1232000;
        VillageFolkEntity fisher = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = fisher.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            // A pond to the east, the fisher at its edge.
            BlockPos pond = heart.offset(16, -1, 0);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy >= -1; dy--) {
                level.setBlockAndUpdate(pond.offset(dx, dy, dz), Blocks.WATER.defaultBlockState());
            }
            fisher.moveTo(pond.getX() - 4.5, heart.getY(), pond.getZ() + 0.5);
            trade(fisher, StationTask.FISH);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STRING, 8),
                new ItemStack(Items.COD, 4), new ItemStack(Items.CRAFTING_TABLE));
            int string0 = stores(level, id, Items.STRING), cod0 = stores(level, id, Items.COD);
            FieldTools.roundForTests(level, fisher);
            int traps = stores(level, id, FieldItems.FISH_TRAP_ITEM.get());
            Kit.log("fi07 the fisher made: traps " + traps + "; string " + string0 + " -> " + stores(level, id, Items.STRING) + ", cod " + cod0 + " -> "
                + stores(level, id, Items.COD));
            helper.assertTrue(traps == 1 && stores(level, id, Items.STRING) == string0 - 2 && stores(level, id, Items.COD) == cod0 - 1,
                "a trap of sticks, two string and a cod for bait");
            FieldTools.roundForTests(level, fisher);
            String setting = FieldTools.errandForTests(fisher);
            FieldTools.errandNowForTests(level, fisher);
            List<BlockPos> set = FieldBlockEntity.near(level, FieldBlockEntity.Kind.TRAP, pond, 8);
            Kit.log("fi07 set: " + setting + " -> " + set);
            helper.assertTrue(setting != null && setting.startsWith("SET_TRAP") && set.size() == 1, "set in the pond: " + setting);
            BlockPos trap = set.get(0);
            helper.assertTrue(level.getBlockState(trap).getValue(FishTrapBlock.WATERLOGGED) && FishTrapBlock.inOpenWater(level, trap), "in open water");

            // It catches, six at the most.
            int caught = 0;
            for (int i = 0; i < 10; i++) if (!FishTrapBlock.catchOne(level, trap, level.getRandom()).isEmpty()) caught++;
            FieldBlockEntity be = (FieldBlockEntity) level.getBlockEntity(trap);
            Kit.log("fi07 caught: " + caught + "; in it " + be.count() + "; shows " + level.getBlockState(trap).getValue(FishTrapBlock.CATCH));
            helper.assertTrue(caught == FishTrapBlock.MOST && be.count() == FishTrapBlock.MOST, "six at the most: " + caught);
            helper.assertTrue(level.getBlockState(trap).getValue(FishTrapBlock.CATCH) == 2, "a full cage to be seen");

            // A rainy day: the boats in, the fisher goes round its traps and lands the catch.
            Fleet.weatherForTests("rain");
            fisher.moveTo(pond.getX() - 12.5, heart.getY(), pond.getZ() + 0.5);
            int fish0 = stores(level, id, Economy_rawFish()), all0 = stores(level, id, s -> !s.isEmpty());
            FieldTools.roundForTests(level, fisher);
            String round = FieldTools.errandForTests(fisher);
            FieldTools.errandNowForTests(level, fisher);
            int fish1 = stores(level, id, Economy_rawFish()), all1 = stores(level, id, s -> !s.isEmpty());
            Kit.log("fi07 the boats in: " + round + "; the stores' fish " + fish0 + " -> " + fish1 + ", everything " + all0 + " -> " + all1
                + "; the trap " + be.count() + "; card " + FieldTools.cardLine(fisher));
            helper.assertTrue(round != null && round.startsWith("TRAPS"), "round its traps while the boats stay in: " + round);
            helper.assertTrue(be.count() == 0 && all1 - all0 == FishTrapBlock.MOST && fish1 > fish0, "its catch landed, junk and all");
            helper.assertTrue(first(id, "trap-catch") != null, "the chronicle has it");
            Fleet.weatherForTests(null);
            // Passing by, on a fine day, it empties one.
            FishTrapBlock.catchOne(level, trap, level.getRandom());
            FishTrapBlock.catchOne(level, trap, level.getRandom());
            fisher.moveTo(trap.getX() - 2.5, heart.getY(), trap.getZ() + 0.5);
            FieldTools.roundForTests(level, fisher);
            Kit.log("fi07 passing: the trap " + be.count());
            helper.assertTrue(be.count() == 0, "emptied in passing");

            // A player's own trap, emptied by hand.
            BlockPos own = trap.offset(0, 0, 2);
            if (FieldBlockEntity.near(level, FieldBlockEntity.Kind.TRAP, own, 0).isEmpty()) {
                level.setBlockAndUpdate(own, FieldItems.FISH_TRAP.get().defaultBlockState().setValue(FishTrapBlock.WATERLOGGED, true));
            }
            FishTrapBlock.catchOne(level, own, level.getRandom());
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            var r = level.getBlockState(own).useWithoutItem(level, p, hit(own));
            int got = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) got += p.getInventory().getItem(i).getCount();
            Kit.log("fi07 the player's trap: " + r + ", " + got + " taken");
            helper.assertTrue(got == 1 && ((FieldBlockEntity) level.getBlockEntity(own)).count() == 0, "the player empties its own");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    private static Predicate<ItemStack> Economy_rawFish() {
        return s -> s.is(Items.COD) || s.is(Items.SALMON);
    }

    // ============================================================ fi08: the rain barrel

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi08_barrel")
    public static void fi08_barrel(GameTestHelper helper) {
        int x = 1234000;
        VillageFolkEntity hand = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = hand.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = another(helper, heart.offset(14, 0, 8), id);
        helper.runAtTickTime(5, () -> {
            trade(hand, StationTask.SHOP);
            trade(farmer, StationTask.FARM);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.COPPER_INGOT, 2),
                new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BUCKET));
            Villages.Village v = Villages.get(id);
            Map<Item, Integer> want = FieldTools.wantedForTests(level, v);
            int planks0 = stores(level, id, s -> s.is(Items.OAK_PLANKS)), copper0 = stores(level, id, Items.COPPER_INGOT);
            String made = FieldTools.craftForTests(level, hand);
            int planks1 = stores(level, id, s -> s.is(Items.OAK_PLANKS)), copper1 = stores(level, id, Items.COPPER_INGOT);
            Kit.log("fi08 wanted " + want + "; the shop's hand: " + made + "; planks " + planks0 + " -> " + planks1 + ", copper " + copper0 + " -> " + copper1);
            helper.assertTrue(want.getOrDefault(FieldItems.RAIN_BARREL_ITEM.get(), 0) >= 1, "a barrel wanted at the farmer's field: " + want);
            helper.assertTrue(made != null && made.contains("rain barrel") && planks0 - planks1 == 7 && copper0 - copper1 == 1,
                "seven planks and a copper: " + made);
            boolean onBook = false;
            for (var w : Workshop.lookForTests(level, v)) if (w.make() == FieldItems.WATERING_CAN.get()) onBook = true;
            helper.assertTrue(onBook, "the shop's order book keeps the farmer's can");

            // The town sets it at the edge of the field.
            FieldTools.townForTests(level, v);
            List<BlockPos> barrels = FieldBlockEntity.near(level, FieldBlockEntity.Kind.BARREL, farmer.workZone().center(), 12);
            Kit.log("fi08 set: " + barrels + "; the stores' barrels " + stores(level, id, FieldItems.RAIN_BARREL_ITEM.get()));
            helper.assertTrue(barrels.size() == 1 && stores(level, id, FieldItems.RAIN_BARREL_ITEM.get()) == 0, "a barrel by the field");
            BlockPos b = barrels.get(0);
            helper.assertTrue(level.canSeeSky(b.above()), "under the open sky");
            helper.assertTrue(first(id, "barrel") != null, "the chronicle has it");

            // The rain fills it; snow does not.
            BlockState st = level.getBlockState(b);
            for (int i = 0; i < 20; i++) st.getBlock().handlePrecipitation(level.getBlockState(b), level, b, Biome.Precipitation.SNOW);
            int snowed = RainBarrelBlock.water(level, b);
            int calls = 0;
            while (RainBarrelBlock.water(level, b) < RainBarrelBlock.MOST && calls < 400) {
                st.getBlock().handlePrecipitation(level.getBlockState(b), level, b, Biome.Precipitation.RAIN);
                calls++;
            }
            Kit.log("fi08 the rain: snow left it at " + snowed + "; full after " + calls + " showers; shows " + level.getBlockState(b));
            helper.assertTrue(snowed == 0 && RainBarrelBlock.water(level, b) == RainBarrelBlock.MOST, "full of rain, four buckets");
            helper.assertTrue(FieldTools.brigadeWaterForTests(level.getBlockState(b)), "the fire brigade fills its buckets at it");

            // A player fills a bucket at it.
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
            level.getBlockState(b).useItemOn(p.getMainHandItem(), level, p, InteractionHand.MAIN_HAND, hit(b));
            Kit.log("fi08 the player's bucket: " + p.getMainHandItem() + "; the barrel " + RainBarrelBlock.water(level, b));
            helper.assertTrue(p.getMainHandItem().is(Items.WATER_BUCKET) && RainBarrelBlock.water(level, b) == 3, "a bucket of rain water");

            // A fire with no pond near: the bucket chain draws from the barrel, a bucket's worth at a time.
            BlockPos fire = b.offset(10, 0, 4);
            BlockPos chain = FieldTools.chainWaterForTests(level, fire);
            boolean drew = FieldTools.droughtFillForTests(level, b);
            Kit.log("fi08 the chain's water: " + chain + " (the barrel " + b + "); drew " + drew + ", left " + RainBarrelBlock.water(level, b));
            helper.assertTrue(b.equals(chain) && drew && RainBarrelBlock.water(level, b) == 2, "the chain draws from the barrel");

            // A workshop with no iron for its cauldron of water against fire: a barrel by it instead.
            stock(level, id, new ItemStack(FieldItems.RAIN_BARREL_ITEM.get()));
            BlockPos smithy = heart.offset(-16, 0, -12);
            Ledger.built(id, "smithy", smithy, Direction.NORTH);
            int[] rule = FieldTools.cauldronRuleForTests(level, id);
            int byIt = FieldBlockEntity.near(level, FieldBlockEntity.Kind.BARREL, smithy, 12).size();
            Kit.log("fi08 against fire with no iron: set " + rule[0] + ", kept " + rule[1] + "; barrels by the smithy " + byIt);
            helper.assertTrue(rule[0] == 1 && byIt == 1 && stores(level, id, FieldItems.RAIN_BARREL_ITEM.get()) == 0, "a rain barrel by the smithy");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }


    // ============================================================ fi09: the bee smoker

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi09_smoker")
    public static void fi09_smoker(GameTestHelper helper) {
        int x = 1236000;
        VillageFolkEntity smith = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = smith.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity keeper = another(helper, heart.offset(16, 0, 0), id);
        helper.runAtTickTime(5, () -> {
            trade(smith, StationTask.SMITH);
            trade(keeper, StationTask.BEEKEEP);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.COPPER_INGOT, 2), new ItemStack(Items.LEATHER, 4), new ItemStack(Items.COAL, 16),
                new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.SHEARS), new ItemStack(Items.GLASS_BOTTLE, 4));
            String made = FieldTools.craftForTests(level, smith);
            Kit.log("fi09 the smith: " + made + "; copper " + stores(level, id, Items.COPPER_INGOT) + ", leather " + stores(level, id, Items.LEATHER)
                + ", coal " + stores(level, id, Items.COAL));
            helper.assertTrue(made != null && made.contains("bee smoker") && stores(level, id, Items.COPPER_INGOT) == 0
                && stores(level, id, Items.LEATHER) == 3 && stores(level, id, Items.COAL) == 15, "two copper, a leather and a coal");

            // Three full hives on the keeper's meadow, and an angry bee.
            BlockPos c = keeper.workZone().center();
            List<BlockPos> hives = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                BlockPos h = Kit.surface(level, c.getX() - 2 + i * 2, c.getZ() + 3);
                level.setBlockAndUpdate(h, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS));
                hives.add(h);
            }
            Bee bee = EntityType.BEE.create(level);
            bee.moveTo(c.getX() + 0.5, c.getY() + 2, c.getZ() + 2.5, 0, 0);
            level.addFreshEntity(bee);
            bee.setRemainingPersistentAngerTime(400);
            int comb0 = stores(level, id, Items.HONEYCOMB), honey0 = stores(level, id, Items.HONEY_BOTTLE);
            String harvest = FieldTools.smokeForTests(level, keeper);
            int full = 0;
            for (BlockPos h : hives) if (level.getBlockState(h).getValue(BeehiveBlock.HONEY_LEVEL) > 0) full++;
            int comb1 = stores(level, id, Items.HONEYCOMB), honey1 = stores(level, id, Items.HONEY_BOTTLE);
            Kit.log("fi09 the keeper: " + harvest + "; hives still full " + full + "; comb " + comb0 + " -> " + comb1 + ", honey " + honey0 + " -> "
                + honey1 + "; the bee angry " + bee.isAngry() + "; in hand " + keeper.getItemBySlot(EquipmentSlot.OFFHAND) + "; card " + FieldTools.cardLine(keeper));
            helper.assertTrue(harvest != null && full == 0, "every full hive on the one round: " + harvest);
            helper.assertTrue(comb1 - comb0 + (honey1 - honey0) * 3 > 3 * 3, "more from them than three hives give unsmoked: " + (comb1 - comb0) + " comb, "
                + (honey1 - honey0) + " honey");
            helper.assertTrue(!bee.isAngry(), "the bees calmed");
            helper.assertTrue(keeper.getItemBySlot(EquipmentSlot.OFFHAND).is(FieldItems.BEE_SMOKER.get()), "the smoker in its hand");
            helper.assertTrue(first(id, "smoker") != null, "the chronicle has it");

            // A player's puff: the bees calm for half a minute, and a full hive gives its comb without a sting.
            BlockPos hive = hives.get(1);
            level.setBlockAndUpdate(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS));
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            p.moveTo(hive.getX() + 0.5, hive.getY(), hive.getZ() - 1.5);
            ItemStack smoker = new ItemStack(FieldItems.BEE_SMOKER.get());
            p.setItemInHand(InteractionHand.MAIN_HAND, smoker);
            bee.setRemainingPersistentAngerTime(400);
            smoker.getItem().use(level, p, InteractionHand.MAIN_HAND);
            boolean calm = BeeSmokerItem.calm(level, hive);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            PlayerInteractEvent.RightClickBlock e = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, hive, hit(hive));
            FieldTools.onUseBlock(e);
            int comb = 0;
            for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new AABB(hive).inflate(2))) if (it.getItem().is(Items.HONEYCOMB)) comb += it.getItem().getCount();
            Kit.log("fi09 the player's puff: calm " + calm + "; the bee angry " + bee.isAngry() + "; the smoker worn " + smoker.getDamageValue()
                + "; the hive emptied " + (level.getBlockState(hive).getValue(BeehiveBlock.HONEY_LEVEL) == 0) + ", handled " + e.isCanceled() + ", comb " + comb);
            helper.assertTrue(calm && !bee.isAngry() && smoker.getDamageValue() == 1, "the bees calmed by the player's smoke");
            helper.assertTrue(e.isCanceled() && level.getBlockState(hive).getValue(BeehiveBlock.HONEY_LEVEL) == 0 && comb == 3,
                "the comb taken, no bees let out angry");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }

    // ============================================================ fi10: the books, and the copper

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fi10_copper")
    public static void fi10_copper(GameTestHelper helper) {
        int x = 1238000;
        VillageFolkEntity smelter = founder(helper, x);
        ServerLevel level = helper.getLevel();
        UUID id = smelter.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = another(helper, heart.offset(14, 0, 0), id);
        helper.runAtTickTime(5, () -> {
            // The eight: a recipe each, their ages, their worth, their place on the market.
            Item[] things = { FieldItems.WATERING_CAN.get(), FieldItems.SEED_SATCHEL.get(), FieldItems.COPPER_SICKLE.get(), FieldItems.NESTING_BOX_ITEM.get(),
                FieldItems.FEED_TROUGH_ITEM.get(), FieldItems.FISH_TRAP_ITEM.get(), FieldItems.RAIN_BARREL_ITEM.get(), FieldItems.BEE_SMOKER.get() };
            Villages.Age[] ages = { Villages.Age.STONE, Villages.Age.WOOD, Villages.Age.STONE, Villages.Age.WOOD, Villages.Age.WOOD, Villages.Age.WOOD,
                Villages.Age.WOOD, Villages.Age.STONE };
            double[] worth = { 2.6, 1.6, 1.6, 0.6, 0.5, 0.8, 1.1, 2.0 };
            for (int i = 0; i < things.length; i++) {
                List<RecipeBook.Way> ways = RecipeBook.waysFor(level, things[i]);
                Villages.Age age = Tiers.of(level, things[i]);
                double each = Prices.each(things[i]);
                Kit.log("fi10 " + new ItemStack(things[i]).getHoverName().getString() + ": " + ways.size() + " recipe(s); the " + age.label + "; worth " + each
                    + "; market " + (Market.goodFor(new ItemStack(things[i])) != null));
                helper.assertTrue(!ways.isEmpty(), "a real recipe for " + things[i]);
                helper.assertTrue(age == ages[i], things[i] + " belongs to " + ages[i].label + ", not " + age.label);
                helper.assertTrue(Prices.known(things[i]) && Math.abs(each - worth[i]) < 0.001, things[i] + " is worth " + worth[i] + ": " + each);
                helper.assertTrue(Market.goodFor(new ItemStack(things[i])) != null, "the market deals in " + things[i]);
            }

            // The miners' copper to the smelter's furnace.
            trade(smelter, StationTask.SMELT);
            trade(farmer, StationTask.FARM);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.RAW_COPPER, 20));
            String ore = Links.ore(smelter, level);
            int raw = smelter.countCarried(s -> s.is(Items.RAW_COPPER));
            Kit.log("fi10 the smelter's ore: " + ore + "; raw copper in hand " + raw);
            helper.assertTrue(ore != null && raw == 20, "the stores' raw copper taken to the furnace: " + raw);

            // No smith and no shop: the smelter beats the farmer's can cold.
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.COPPER_INGOT, 12), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_PLANKS, 64));
            FieldTools.roundForTests(level, smelter);
            int cans = stores(level, id, FieldItems.WATERING_CAN.get()), copper = stores(level, id, Items.COPPER_INGOT);
            Kit.log("fi10 with no smith: the smelter made cans " + cans + "; copper 12 -> " + copper + "; tally " + FieldTools.tallyForTests(smelter));
            helper.assertTrue(cans == 1 && copper == 7, "the smelter made the can of five copper");
            // A smith comes: the smelter stands aside.
            VillageFolkEntity smith = another(helper, heart.offset(-6, 0, 0), id);
            trade(smith, StationTask.SMITH);
            FieldTools.roundForTests(level, smelter);
            int sickles = stores(level, id, FieldItems.COPPER_SICKLE.get());
            Kit.log("fi10 with a smith: the smelter made sickles " + sickles);
            helper.assertTrue(sickles == 0 && stores(level, id, Items.COPPER_INGOT) == 7, "the copper is the smith's work now");
            List<String> status = FieldTools.status(level, Villages.get(id));
            Kit.log("fi10 /village items fields: " + String.join(" | ", status));
            helper.assertTrue(status.size() >= 10, "the town's books on its tools");
            FieldTools.anyHourForTests(level, false);
            helper.succeed();
        });
    }
}
