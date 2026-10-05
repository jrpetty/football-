package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Decor;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Luxuries;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Homes that show the trade, and luxuries made, bought and used (Decor, Luxuries): a smith's house is given
 * an anvil out of the stores, set against its wall; a well-off folk buys a carpet and a candle at the shop
 * with its own savings (its purse down, the shop's takings up, the stores down, not a coin made or lost),
 * the candle made at the shop's bench, and both end up set out in its house; and the candle is lit at dusk
 * and snuffed when the household goes to bed.
 *
 * <p>Each on its own ground, x 240000 to 247000 on z 50000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DecorGameTests {

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

    /** A house stamped down and on the village's books. */
    private static BlockPos house(ServerLevel level, UUID village, BlockPos heart, int dx, int dz) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", at, Direction.NORTH);
        return at;
    }

    /** Where in a house (its footprint, floor to roof) a block of this sort stands, or null. */
    private static List<BlockPos> find(ServerLevel level, BlockPos house, Predicate<BlockState> what) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(house.offset(-4, 0, -4), house.offset(4, 8, 4))) {
            if (what.test(level.getBlockState(p))) out.add(p.immutable());
        }
        return out;
    }

    /** The coin there is: the treasury and every purse. A sale moves it; none is made or lost. */
    private static int money(UUID village) {
        int n = Ledger.coins(village);
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) n += f.purse();
        return n;
    }

    /** A seller's books out of the town's books report. */
    private static CompoundTag row(ServerLevel level, UUID village, String seller, String ware) {
        ListTag all = Stockroom.inventoryReport(level, village).getList("sellers", Tag.TAG_COMPOUND);
        for (int i = 0; i < all.size(); i++) {
            CompoundTag s = all.getCompound(i);
            if (!s.getString("id").equals(seller)) continue;
            ListTag wares = s.getList("wares", Tag.TAG_COMPOUND);
            for (int k = 0; k < wares.size(); k++) if (wares.getCompound(k).getString("id").equals(ware)) return wares.getCompound(k);
        }
        return new CompoundTag();
    }

    // ============================================================ the smith's anvil

    /**
     * A smith of a Stone Age village, moved into a house, and an anvil in the stores: when the village
     * furnishes its houses, the anvil comes out of the stores and stands in the smith's house, inside its
     * walls on a sound floor and against a wall, with the way in from the door left clear; the stores hold
     * none afterwards. Its colours, which the stores have not got, are waited on (wants on the stores).
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "dc01_smith_house_anvil")
    public static void dc01_smith_house_anvil(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 240000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity smith = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(smith != null, "a village");
        UUID village = smith.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.STONE);
            smith.setJob(StationTask.SMITH);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.ANVIL));
            Villages.forgetStock();
            BlockPos home = house(level, village, heart, -16, 16);
            Homes.tickForTests(level, v);
            List<UUID> in = Homes.membersForTests(village, home);
            helper.assertTrue(in.contains(smith.getUUID()), "the smith has moved into the house: " + in);
            int before = stock(level, village, s -> s.is(ItemTags.ANVIL));
            List<BlockPos> anvilsBefore = find(level, home, st -> st.is(BlockTags.ANVIL));
            TownJobs.instantForTests(true);
            int done = 0;
            for (int i = 0; i < 3; i++) done += Decor.work(level, v, 40);
            int after = stock(level, village, s -> s.is(ItemTags.ANVIL));
            List<BlockPos> anvils = find(level, home, st -> st.is(BlockTags.ANVIL));
            String card = Decor.cardLine(smith);
            List<String> wants = new ArrayList<>();
            for (Decor.Want w : Decor.wants(village)) wants.add(w.item() + " for " + w.forWhat());
            Kit.log("dc01 the smith's house: " + done + " set out; anvils in the stores " + before + " -> " + after + ", in the house "
                + anvilsBefore + " -> " + anvils + "; the card: " + card + "; the houses wait on " + wants + "; " + Decor.lines(level, v));
            helper.assertTrue(before == 1 && after == 0, "the anvil came out of the stores: " + before + " -> " + after);
            helper.assertTrue(anvilsBefore.isEmpty() && anvils.size() == 1, "and stands in the smith's house: " + anvils);
            BlockPos a = anvils.get(0);
            helper.assertTrue(Math.abs(a.getX() - home.getX()) <= 3 && Math.abs(a.getZ() - home.getZ()) <= 3,
                "inside its walls: " + a + " in the house at " + home);
            helper.assertTrue(level.getBlockState(a.below()).isFaceSturdy(level, a.below(), Direction.UP), "on a sound floor");
            boolean byWall = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = a.relative(d);
                if (level.getBlockState(w).isFaceSturdy(level, w, d.getOpposite())) byWall = true;
            }
            helper.assertTrue(byWall, "against a wall: " + a);
            BlockPos door = null;
            for (BuildGoal.Placement p : BuildGoal.plan("house", home, Direction.NORTH, 13)) if (p.part() == BuildGoal.Part.DOOR) door = p.pos();
            helper.assertTrue(door != null && level.getBlockState(door.relative(Direction.NORTH)).isAir()
                && level.getBlockState(door.relative(Direction.NORTH, 2)).isAir(), "and the way in from the door is clear");
            helper.assertTrue(card.contains("anvil"), "its card says so: " + card);
            helper.assertTrue(!wants.isEmpty(), "its colours, not in the stores, are waited on: " + wants);
            helper.succeed();
        });
    }

    // ============================================================ bought, and set out

    /**
     * A well-off farmer in its house, a shopkeeper who makes a candle at its bench of the stores' string and
     * comb, and a carpet in the stores: the farmer buys the carpet and the candle at the shop with its own
     * savings. Its purse goes down, the shop's books take the coin, the stores hold one fewer of each, and no
     * coin is made or lost; the candle shows as made at the shop. Carried home, the carpet goes down on the
     * floor and the candle on a table, and its card tells of them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "dc02_luxury_bought_and_set_out")
    public static void dc02_luxury_bought_and_set_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 243000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity buyer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(buyer != null, "a village");
        UUID village = buyer.ownerId();
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(keeper != null && village.equals(keeper.ownerId()), "a shopkeeper");
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.IRON);
            buyer.setJob(StationTask.FARM);
            keeper.setJob(StationTask.SHOP);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.STRING, 8), new ItemStack(Items.HONEYCOMB, 8),
                new ItemStack(Items.RED_CARPET, 4), new ItemStack(Items.CRAFTING_TABLE));
            Villages.forgetStock();
            // A house each: the farmer's is whichever it is given.
            house(level, village, heart, -16, 16);
            house(level, village, heart, 16, 16);
            Homes.tickForTests(level, v);
            BlockPos home = Homes.homeOf(buyer);
            helper.assertTrue(home != null && Homes.membersForTests(village, home).contains(buyer.getUUID()), "the farmer has a house: " + home);
            // The candle, made at the shop's bench of string and comb (a maker's work, in the books).
            for (int i = 0; i < 8 && stock(level, village, s -> s.is(ItemTags.CANDLES)) == 0; i++) Crafts.now(keeper, level, v);
            int candles = stock(level, village, s -> s.is(ItemTags.CANDLES)), carpets = stock(level, village, s -> s.is(ItemTags.WOOL_CARPETS));
            int madeCandle = Economy.todayForTests(village, "candle")[0];
            Kit.log("dc02 the shop's bench: " + candles + " candles in the stores, " + madeCandle + " made today; " + carpets + " carpets");
            helper.assertTrue(candles >= 1 && madeCandle >= 1, "the shopkeeper made a candle of the stores' string and comb");
            buyer.earn(150);
            helper.assertTrue(Wealth.tier(buyer).ordinal() >= Wealth.Tier.WELL_OFF.ordinal(), "the farmer is well off: " + Wealth.tier(buyer));
            int purse = buyer.purse(), coin = money(village), treasury = Ledger.coins(village);
            String rug = Luxuries.buyForTests(level, buyer, Luxuries.Kind.RUG);
            String candle = Luxuries.buyForTests(level, buyer, Luxuries.Kind.CANDLE);
            int paid = purse - buyer.purse();
            CompoundTag rugBooks = row(level, village, "shop", "rug"), candleBooks = row(level, village, "shop", "candle");
            Kit.log("dc02 bought: " + rug + "; " + candle + "; purse " + purse + " -> " + buyer.purse() + ", treasury " + treasury + " -> "
                + Ledger.coins(village) + ", all the coin " + coin + " -> " + money(village) + "; carpets " + carpets + " -> "
                + stock(level, village, s -> s.is(ItemTags.WOOL_CARPETS)) + ", candles " + candles + " -> "
                + stock(level, village, s -> s.is(ItemTags.CANDLES)) + "; the shop's books: rugs " + rugBooks + ", candles " + candleBooks);
            helper.assertTrue(rug != null && candle != null, "a carpet and a candle bought at the shop: " + rug + "; " + candle);
            helper.assertTrue(paid >= 2, "out of its own purse: " + paid + " paid");
            helper.assertTrue(money(village) == coin, "the coin moved, none made or lost: " + coin + " -> " + money(village));
            helper.assertTrue(rugBooks.getInt("soldToday") >= 1 && candleBooks.getInt("soldToday") >= 1
                && rugBooks.getInt("coin7") + candleBooks.getInt("coin7") >= 2, "the shop's takings: " + rugBooks + ", " + candleBooks);
            helper.assertTrue(candleBooks.getInt("madeToday") >= 1, "and the candle in its books as made there: " + candleBooks);
            helper.assertTrue(stock(level, village, s -> s.is(ItemTags.WOOL_CARPETS)) == carpets - 1
                && stock(level, village, s -> s.is(ItemTags.CANDLES)) == candles - 1, "one fewer of each in the stores");
            List<BlockPos> rugsBefore = find(level, home, st -> st.is(BlockTags.WOOL_CARPETS));
            List<BlockPos> candlesBefore = find(level, home, st -> st.is(BlockTags.CANDLES));
            int set = Luxuries.setOutForTests(level, buyer);
            List<BlockPos> rugsAfter = find(level, home, st -> st.is(BlockTags.WOOL_CARPETS));
            List<BlockPos> candlesAfter = find(level, home, st -> st.is(BlockTags.CANDLES));
            String card = Decor.cardLine(buyer);
            Kit.log("dc02 set out at home: " + set + "; carpets " + rugsBefore + " -> " + rugsAfter + ", candles " + candlesBefore + " -> "
                + candlesAfter + "; comforts " + buyer.comforts() + "; the card: " + card);
            helper.assertTrue(set == 2, "both carried home and set out: " + set);
            helper.assertTrue(rugsAfter.size() == rugsBefore.size() + 1, "the carpet on its floor: " + rugsAfter);
            helper.assertTrue(candlesAfter.size() == candlesBefore.size() + 1, "the candle on a table: " + candlesAfter);
            BlockPos c = candlesAfter.get(0);
            helper.assertTrue(level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP), "standing on something: " + c);
            helper.assertTrue(buyer.comforts() == 2, "two comforts of its own: " + buyer.comforts());
            helper.assertTrue(card.contains("carpet") && card.contains("candle"), "and its card tells of them: " + card);
            helper.succeed();
        });
    }

    // ============================================================ candles at dusk

    /**
     * A candle a folk bought for its house is lit at dusk while the household is up, and snuffed when the
     * last of them has gone to bed; by day it stands unlit.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "dc03_candle_lit_at_dusk")
    public static void dc03_candle_lit_at_dusk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 246000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        UUID village = folk.ownerId();
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(keeper != null && village.equals(keeper.ownerId()), "a shopkeeper");
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.IRON);
            folk.setJob(StationTask.FISH);
            keeper.setJob(StationTask.SHOP);
            emptyStores(level, village);
            stores(level, heart, 4, 0, new ItemStack(Items.CANDLE, 3));
            Villages.forgetStock();
            house(level, village, heart, -16, 16);
            house(level, village, heart, 16, 16);
            Homes.tickForTests(level, v);
            BlockPos home = Homes.homeOf(folk);
            helper.assertTrue(home != null, "the fisher has a house");
            folk.earn(60);
            String bought = Luxuries.buyForTests(level, folk, Luxuries.Kind.CANDLE);
            int set = Luxuries.setOutForTests(level, folk);
            List<BlockPos> lights = find(level, home, st -> st.is(BlockTags.CANDLES));
            helper.assertTrue(bought != null && set == 1 && lights.size() == 1, "a candle bought and set out: " + bought + ", " + lights);
            BlockPos c = lights.get(0);
            long day = level.getDayTime() / 24000L;
            level.setDayTime(day * 24000L + 6000L);
            Luxuries.candles(level, v);
            boolean byDay = AbstractCandleBlock.isLit(level.getBlockState(c));
            level.setDayTime(day * 24000L + 12900L);
            Luxuries.candles(level, v);
            boolean atDusk = AbstractCandleBlock.isLit(level.getBlockState(c));
            String card = Decor.cardLine(folk);
            level.setDayTime(day * 24000L + 18500L);
            Luxuries.candles(level, v);
            boolean atNight = AbstractCandleBlock.isLit(level.getBlockState(c));
            Kit.log("dc03 the candle at " + c + ": by day " + byDay + ", at dusk " + atDusk + ", after bedtime (" + folk.bedtimeTick() + ") "
                + atNight + "; the card at dusk: " + card);
            helper.assertTrue(!byDay, "unlit by day");
            helper.assertTrue(atDusk, "lit at dusk");
            helper.assertTrue(card.contains("lit"), "the card says the candles are lit: " + card);
            helper.assertTrue(!atNight, "snuffed when the household has gone to bed");
            helper.succeed();
        });
    }
}
