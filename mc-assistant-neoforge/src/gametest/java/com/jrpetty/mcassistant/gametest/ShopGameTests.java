package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Craftsmanship;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Services;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The sellers know their trade the whole way (Bench, Stockroom): a shopkeeper with nothing but logs and
 * iron in the stores saws planks, cuts sticks and makes a tool for its shelves; a cook with wheat bakes
 * bread for its counter and stops at the farmers' seed; what sells out is kept more of and what never
 * sells is cut back to one on the shelf; nothing is made of the iron a village is putting by for its
 * age; and the storekeeper makes to order what the stores have not got.
 *
 * <p>Each on its own ground, far from the others, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ShopGameTests {

    private static final String EMPTY = "empty";

    /** A marked store chest beside the heart, filled with these. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        return box;
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

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /** A seller's books out of the town's books report. */
    private static CompoundTag seller(CompoundTag report, String id) {
        ListTag all = report.getList("sellers", Tag.TAG_COMPOUND);
        for (int i = 0; i < all.size(); i++) if (all.getCompound(i).getString("id").equals(id)) return all.getCompound(i);
        return new CompoundTag();
    }

    /** A ware's row in a seller's books. */
    private static CompoundTag row(CompoundTag seller, String id) {
        ListTag all = seller.getList("wares", Tag.TAG_COMPOUND);
        for (int i = 0; i < all.size(); i++) if (all.getCompound(i).getString("id").equals(id)) return all.getCompound(i);
        return new CompoundTag();
    }

    // ============================================================ the shop's bench

    /**
     * A shopkeeper in a Stone Age village (timber and iron to spare; stone and coal put by for the age)
     * with nothing in the stores but oak logs, iron and a crafting table, and no tools on its shelves:
     * it saws the logs to planks, the planks to sticks, and makes a tool for its shelves, with its mark
     * on it. It never saws into the builders' sixteen logs nor beats the smith's last sixteen bars, and
     * the shop's books say what it made and how.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sh01_shop_makes_a_tool")
    public static void sh01_shop_makes_a_tool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 110000, z = 30000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.STONE);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.IRON_INGOT, 40),
                new ItemStack(Items.CRAFTING_TABLE));
            Villages.forgetStock();
            keeper.setJob(StationTask.SHOP);
            Predicate<ItemStack> handled = s -> s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_AXE) || s.is(Items.IRON_SHOVEL)
                || s.is(Items.IRON_HOE) || s.is(Items.STONE_PICKAXE) || s.is(Items.STONE_AXE) || s.is(Items.STONE_SHOVEL)
                || s.is(Items.STONE_HOE) || s.is(Items.STONE_SWORD);
            helper.assertTrue(stock(level, village, handled) == 0 && stock(level, village, s -> s.is(ItemTags.PLANKS)) == 0
                && stock(level, village, s -> s.is(Items.STICK)) == 0, "no tools on the shelves, and no planks or sticks in the stores");
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 16 && stock(level, village, handled) == 0; i++) {
                String m = Cafe.keepShop(level, v, keeper);
                if (m != null) made.add(m);
            }
            ItemStack tool = ItemStack.EMPTY;
            for (BlockPos p : Villages.storeChests(level, village)) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) if (handled.test(c.getItem(i))) tool = c.getItem(i).copy();
            }
            int logs = stock(level, village, s -> s.is(ItemTags.LOGS));
            int iron = stock(level, village, s -> s.is(Items.IRON_INGOT));
            CompoundTag shop = seller(Stockroom.inventoryReport(level, village), "shop");
            String id = tool.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(tool.getItem()).toString();
            CompoundTag r = row(shop, id);
            Kit.log("sh01 the shop's bench: " + made + "; logs left " + logs + ", iron left " + iron + "; the tool " + id
                + " (" + Craftsmanship.gradeOf(tool) + "), made " + r.getInt("madeToday") + " today, how: " + r.getString("how")
                + "; the books: " + shop);
            helper.assertTrue(!tool.isEmpty(), "a tool with a handle made for the shelves: " + made);
            helper.assertTrue(logs < 32 && logs >= 16, "the logs were sawn, and never into the builders' sixteen: " + logs);
            helper.assertTrue(iron < 40 && iron >= 16, "the iron was beaten, and never the smith's last sixteen bars: " + iron);
            helper.assertTrue(r.getInt("madeToday") >= 1 && r.getString("how").contains("planks") && r.getString("how").contains("stick"),
                "the shop's books have it made, the whole way from planks and sticks: " + r.getString("how"));
            helper.assertTrue(Craftsmanship.gradeOf(tool) != null, "and it carries the shopkeeper's mark");
            helper.succeed();
        });
    }

    // ============================================================ the café's kitchen

    /**
     * A cook with nothing in the stores but wheat and a crafting table bakes bread for the café's
     * counter, three wheat a loaf by the game's recipe; and stops at the farmers' last twelve wheat, the
     * seed for the fields, saying in its books what it is short of.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sh02_cafe_bakes_bread")
    public static void sh02_cafe_bakes_bread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 111500, z = 30000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity cook = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(cook != null, "a village");
        UUID village = cook.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.STONE);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.WHEAT, 30), new ItemStack(Items.CRAFTING_TABLE));
            Villages.forgetStock();
            cook.setJob(StationTask.COOK);
            String first = Cafe.cook(level, v, cook);
            int bread = stock(level, village, s -> s.is(Items.BREAD));
            int wheat = stock(level, village, s -> s.is(Items.WHEAT));
            Kit.log("sh02 the cook's first piece of work: " + first + "; bread " + bread + ", wheat " + wheat);
            helper.assertTrue(bread == 1 && wheat == 27, "a loaf baked out of three wheat");
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                String m = Cafe.cook(level, v, cook);
                if (m != null) made.add(m);
            }
            bread = stock(level, village, s -> s.is(Items.BREAD));
            wheat = stock(level, village, s -> s.is(Items.WHEAT));
            CompoundTag cafe = seller(Stockroom.inventoryReport(level, village), "cafe");
            CompoundTag r = row(cafe, "minecraft:bread");
            Kit.log("sh02 then: " + made + "; bread " + bread + ", wheat " + wheat + "; the café's books for bread: " + r);
            helper.assertTrue(bread == 6 && wheat == 12, "it bakes on to the farmers' seed, and no further: bread " + bread + ", wheat " + wheat);
            helper.assertTrue(r.getInt("onHand") == 6 && r.getInt("madeToday") == 6 && r.getInt("target") >= 12,
                "the café's books: six on hand and made today, against the usual twelve");
            helper.assertTrue(r.getString("short").contains("wheat") && r.getString("short").contains("seed"),
                "and short of wheat, the rest being seed for the fields: " + r.getString("short"));
            helper.succeed();
        });
    }

    // ============================================================ what sells

    /**
     * The shop's books over four days: candles sell out every day (five sold, one more asked for), flower
     * pots never sell. What it means to keep of candles goes up past the usual four, of flower pots down
     * to one; with string, comb and bricks in the stores the shop makes candles up to what it now keeps
     * and only one pot. The pots it has too many of are marked down; nothing it sells goes under cost.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sh03_demand_moves_targets")
    public static void sh03_demand_moves_targets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 113000, z = 30000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.STONE);
            emptyStores(level, village);
            Villages.forgetStock();
            keeper.setJob(StationTask.SHOP);
            ItemStack candle = new ItemStack(Items.CANDLE), pot = new ItemStack(Items.FLOWER_POT);
            long d0 = level.getDayTime() / 24000L + 1;
            level.setDayTime(d0 * 24000L + 6000);
            int candleUsual = Stockroom.target(level, village, candle), potUsual = Stockroom.target(level, village, pot);
            for (int d = 0; d < 4; d++) {
                level.setDayTime((d0 + d) * 24000L + 6000);
                for (int i = 0; i < 5; i++) Stockroom.sold(level, village, Stockroom.Seller.SHOP, candle, 1, 1);
                Stockroom.missed(level, village, Stockroom.Seller.SHOP, candle);           // sold out: one more wanted
            }
            level.setDayTime((d0 + 4) * 24000L + 6000);
            int candleNow = Stockroom.target(level, village, candle), potNow = Stockroom.target(level, village, pot);
            Kit.log("sh03 what the shop keeps: candles " + candleUsual + " -> " + candleNow + ", flower pots " + potUsual + " -> " + potNow);
            helper.assertTrue(candleUsual == 4 && potUsual == 2, "while the books are new, the usual: four candles, two pots");
            helper.assertTrue(candleNow > candleUsual, "candles sold out every day: the shop means to keep more of them");
            helper.assertTrue(potNow < potUsual, "flower pots never sold: cut back to one on the shelf");
            // Restocking by what it now keeps: candles past the usual four, and a single pot.
            stores(level, heart, 4, 0, new ItemStack(Items.STRING, 20), new ItemStack(Items.HONEYCOMB, 20),
                new ItemStack(Items.BRICK, 12), new ItemStack(Items.CRAFTING_TABLE));
            Villages.forgetStock();
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String m = Cafe.keepShop(level, v, keeper);
                if (m != null) made.add(m);
            }
            int candles = stock(level, village, s -> s.is(ItemTags.CANDLES));
            int pots = stock(level, village, s -> s.is(Items.FLOWER_POT));
            Kit.log("sh03 the bench: " + made + "; candles " + candles + ", pots " + pots);
            helper.assertTrue(candles == candleNow, "candles made up to what it now keeps: " + candles + " of " + candleNow);
            helper.assertTrue(pots == 1, "and one flower pot, not the usual two: " + pots);
            // Too many pots, unsold for four days: marked down, never under what they cost.
            stores(level, heart, 4, 3, new ItemStack(Items.FLOWER_POT, 4));
            int asked = Stockroom.asked(level, village, pot, 10, 1);
            int ironFloor = Stockroom.asked(level, village, new ItemStack(Items.IRON_PICKAXE), 1, 1);
            CompoundTag shop = seller(Stockroom.inventoryReport(level, village), "shop");
            Kit.log("sh03 a pot asked at " + asked + " of 10 (markdown " + row(shop, "minecraft:flower_pot").getInt("markdown")
                + "), an iron pickaxe at no less than " + ironFloor + "; candles row " + row(shop, "candle"));
            helper.assertTrue(asked < 10 && asked >= 1, "the slow pots are marked down");
            helper.assertTrue(ironFloor > 1, "and an iron pickaxe is never sold under what it cost");
            helper.assertTrue(row(shop, "candle").getInt("sold7") == 20 && row(shop, "candle").getInt("missed7") == 4,
                "the shop's books count the week's sales, and the sales it had not got");
            String page = Stockroom.page(level, v);
            Kit.log("sh03 /village shop:\n" + page);
            helper.assertTrue(page.contains("Candles"), "and /village shop reads them out");
            helper.succeed();
        });
    }

    // ============================================================ the village's own needs first

    /**
     * An Iron Age village putting its iron by for the age: the shopkeeper, with iron and logs in the
     * stores, makes chests and barrels of the timber, and not one bucket, pair of shears, lantern or iron
     * tool. Every bar is still there, and the shop's books say why it made none.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sh04_saving_iron_not_taken")
    public static void sh04_saving_iron_not_taken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 114500, z = 30000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.IRON);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.IRON_INGOT, 40),
                new ItemStack(Items.CRAFTING_TABLE));
            Villages.forgetStock();
            keeper.setJob(StationTask.SHOP);
            boolean saving = false;
            for (Villages.Need n : Villages.needs(level, village)) saving |= n.task() == Villages.Task.IRON;
            helper.assertTrue(saving, "the village is putting iron by for its age");
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String m = Cafe.keepShop(level, v, keeper);
                if (m != null) made.add(m);
            }
            int iron = stock(level, village, s -> s.is(Items.IRON_INGOT));
            int ironWork = stock(level, village, s -> s.is(Items.BUCKET) || s.is(Items.SHEARS) || s.is(Items.LANTERN)
                || s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_AXE) || s.is(Items.IRON_SHOVEL) || s.is(Items.IRON_HOE)
                || s.is(Items.IRON_NUGGET));
            Bench.Plan bucket = Bench.plan(level, v, Items.BUCKET, 1, Bench.handOf(level, v, keeper, "shop"));
            CompoundTag r = row(seller(Stockroom.inventoryReport(level, village), "shop"), "minecraft:bucket");
            Kit.log("sh04 the bench: " + made + "; iron " + iron + ", iron things " + ironWork + "; a bucket: " + bucket.chain()
                + "; the books: " + r);
            helper.assertTrue(stock(level, village, s -> s.is(Items.CHEST)) >= 1, "the timber still goes into chests");
            helper.assertTrue(iron == 40 && ironWork == 0, "and not a bar of the age's iron is touched: " + iron + ", " + ironWork);
            helper.assertTrue(!bucket.ok() && bucket.why.contains("put by for the age"), "a bucket waits on the age: " + bucket.chain());
            helper.assertTrue(r.getString("short").contains("iron") && r.getString("short").contains("put by for the age"),
                "and the shop's books say so: " + r.getString("short"));
            helper.succeed();
        });
    }

    // ============================================================ made to order

    /**
     * A player asks the storekeeper for a chest the stores have not got: it makes one up at the bench out
     * of the stores' logs and sells it. A diamond pickaxe it cannot make, and says why.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sh05_storekeeper_makes_to_order")
    public static void sh05_storekeeper_makes_to_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 116000, z = 30000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.ageForTests(village, Villages.Age.STONE);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.CRAFTING_TABLE));
            Villages.forgetStock();
            keeper.setJob(StationTask.STORE);
            net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            p.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
            p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            String chest = Services.stores(keeper, p, "could I have a chest?");
            String pick = Services.stores(keeper, p, "could I have a diamond pickaxe?");
            int held = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).is(Items.CHEST)) held += p.getInventory().getItem(i).getCount();
            }
            CompoundTag r = row(seller(Stockroom.inventoryReport(level, village), "stores"), "minecraft:chest");
            Kit.log("sh05 a chest: " + chest + " / a diamond pickaxe: " + pick + " / the stores' books: " + r);
            helper.assertTrue(held == 1 && chest.contains("Made up at the bench"), "a chest made to order and sold: " + chest);
            helper.assertTrue(stock(level, village, s -> s.is(ItemTags.LOGS)) < 32, "out of the stores' logs");
            helper.assertTrue(pick.contains("can't make any"), "a diamond pickaxe it cannot make, and says why: " + pick);
            helper.assertTrue(r.getInt("madeToday") == 1 && r.getInt("soldToday") == 1, "the stores' books: made one, sold one");
            helper.succeed();
        });
    }
}
