package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Commerce;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PlayerStalls;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * A market stall of the player's own (PlayerStalls): rented for a week, the rent into the treasury, a
 * booth put up on the square out of the stores (or the player's own makings), never out of nothing;
 * stocked with bread at a fair price, a folk out for food buys it out of its own purse into the till and
 * carries it off, and at the market's own stalls on market day it weighs the player's stall as any other
 * seller; bread far over the going price doesn't sell, and the folk say so; the till pays out to the
 * player; a stall whose rent runs out shuts, is given back with the goods kept in it, and is let again;
 * and a folk on market day goes to the stall itself and buys.
 *
 * <p>Each on flat ground of its own, along x 290000-297000 at z 50000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class StallGameTests {

    private static final String EMPTY = "empty";

    /** Flat grass round the heart, clear above: a square a booth can stand on. */
    private static BlockPos flat(ServerLevel level, int x, int z) {
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos ground = Kit.surface(level, x, z);
        int y = ground.getY();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 8; h++) level.setBlock(new BlockPos(x + dx, y + h, z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        return new BlockPos(x, y, z);
    }

    /** Nothing in the village's stores but what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    /** A marked store chest beside the heart, filled with these. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos chest = heart.offset(dx, 0, dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        return box;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static int carried(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** A player with coin, and makings of their own in case the stores won't spare theirs. */
    private static Player renter(GameTestHelper helper, BlockPos heart, int coins) {
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
        p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        p.getInventory().setItem(31, new ItemStack(Items.BARREL));
        p.getInventory().setItem(32, new ItemStack(Items.OAK_SIGN));
        p.getInventory().setItem(33, new ItemStack(Items.OAK_FENCE, 4));
        p.getInventory().setItem(34, new ItemStack(Items.BLUE_WOOL, 3));
        return p;
    }

    /** A village founded at the heart, and its first folk. */
    private static VillageFolkEntity village(ServerLevel level, BlockPos heart) {
        return VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
    }

    /** In the Stone Age, its stores holding timber and wool and no food. */
    private static void ready(ServerLevel level, BlockPos heart, UUID village) {
        Villages.ageForTests(village, Villages.Age.STONE);
        emptyStores(level, village);
        stores(level, heart, 3, 3, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64),
            new ItemStack(Items.RED_WOOL, 8));
        Villages.forgetStock();
    }

    /** The stall rented, its booth standing, the rent in the treasury. Returns the booth. */
    private static PlayerStalls.Booth rentIt(GameTestHelper helper, ServerLevel level, Villages.Village v, Player p, BlockPos heart, String tag) {
        int treasury = Ledger.coins(v.id());
        int coins = Market.coinsHeld(p);
        int rent = PlayerStalls.rent(v.id());
        Container store = (Container) level.getBlockEntity(heart.offset(3, 0, 3));
        int planks = count(store, s -> s.is(ItemTags.PLANKS)) + 4 * count(store, s -> s.is(ItemTags.LOGS));
        String said = PlayerStalls.rent(level, v, p, null);
        PlayerStalls.Booth b = PlayerStalls.boothOf(v.id(), p.getUUID());
        int planksAfter = count(store, s -> s.is(ItemTags.PLANKS)) + 4 * count(store, s -> s.is(ItemTags.LOGS));
        Kit.log(tag + " rent " + rent + ": " + said + " — booth " + b + "; treasury " + treasury + " -> " + Ledger.coins(v.id())
            + "; the stores' timber " + planks + " -> " + planksAfter + "; the player's barrel " + carried(p, s -> s.is(Items.BARREL))
            + ", sign " + carried(p, s -> s.is(ItemTags.SIGNS)));
        helper.assertTrue(b != null, "a stall rented: " + said);
        helper.assertTrue(level.getBlockState(b.at()).is(Blocks.BARREL) || level.getBlockState(b.at()).is(Blocks.CHEST),
            "its barrel stands on the square");
        helper.assertTrue(level.getBlockState(b.sign()).getBlock() instanceof SignBlock, "with a sign on its lid");
        helper.assertTrue(Ledger.coins(v.id()) == treasury + rent, "the rent went into the treasury: " + treasury + " + " + rent
            + " -> " + Ledger.coins(v.id()));
        helper.assertTrue(Market.coinsHeld(p) == coins - rent, "out of the player's coin");
        helper.assertTrue(planksAfter < planks || carried(p, s -> s.is(Items.BARREL)) == 0,
            "the booth is made of the stores' timber or the player's own barrel, not out of nothing");
        String[] lines = signLines(level, b);
        helper.assertTrue(String.join(" ", lines).contains(p.getName().getString().length() <= 8 ? p.getName().getString() : p.getName().getString().substring(0, 8)),
            "the player's name on its sign: " + String.join(" | ", lines));
        return b;
    }

    private static String[] signLines(ServerLevel level, PlayerStalls.Booth b) {
        String[] out = { "", "", "", "" };
        if (level.getBlockEntity(b.sign()) instanceof SignBlockEntity sign) {
            for (int i = 0; i < 4; i++) out[i] = sign.getFrontText().getMessage(i, false).getString();
        }
        return out;
    }

    /** Bread at a price every folk thinks fair, the thrifty too: a little under the going price. */
    private static int fairBread(ServerLevel level, UUID village) {
        int going = PlayerStalls.going(level, village, new ItemStack(Items.BREAD));
        return Math.max(1, (int) Math.floor(going * 0.85));
    }

    // ============================================================ renting, and a fair sale

    /**
     * A player rents a stall (the rent into the treasury, a booth on the square of the stores' timber or
     * the player's own), fills it with sixteen bread at a fair price, and a folk out for food buys a lot of
     * it: its purse down by the price, the till up by it, eight bread out of the barrel and into its pack.
     * At the market's own stalls a second folk weighs the player's stall as any other seller, with no
     * bread in the stores, and buys the rest there. The stall's screen has it all.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "st01_stall_rent_and_sell")
    public static void st01_stall_rent_and_sell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerStalls.resetForTests();
        level.setDayTime(6000);
        int x = 290000, z = 50000;
        BlockPos heart = flat(level, x, z);
        VillageFolkEntity buyer = village(level, heart);
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(buyer != null && second != null && buyer.ownerId() != null && buyer.ownerId().equals(second.ownerId()), "a village of two");
        UUID village = buyer.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            ready(level, heart, village);
            Player p = renter(helper, heart, 20);
            PlayerStalls.Booth b = rentIt(helper, level, v, p, heart, "st01");
            helper.assertTrue(PlayerStalls.stateOf(level, village, p.getUUID()) == PlayerStalls.State.OPEN, "the stall is open");
            Container barrel = (Container) level.getBlockEntity(b.at());
            barrel.setItem(0, new ItemStack(Items.BREAD, 16));
            int going = PlayerStalls.going(level, village, new ItemStack(Items.BREAD));
            int price = fairBread(level, village);
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.BREAD), price);
            buyer.earn(30);
            int purse = buyer.purse();
            int had = buyer.countCarried(s -> s.is(Items.BREAD));
            String got = PlayerStalls.browse(level, v, buyer, p.getUUID(), true);
            int till = PlayerStalls.till(village, p.getUUID());
            int left = count(barrel, s -> s.is(Items.BREAD));
            int carries = buyer.countCarried(s -> s.is(Items.BREAD));
            Kit.log("st01 bread at " + price + " for " + PlayerStalls.lot(new ItemStack(Items.BREAD)) + " (going " + going + "): " + got
                + "; purse " + purse + " -> " + buyer.purse() + ", till " + till + ", the barrel " + left + ", carried " + had + " -> " + carries);
            helper.assertTrue(got != null && got.contains("bread"), "a folk out for food buys bread at the stall: " + got);
            helper.assertTrue(purse - buyer.purse() == price, "out of its own purse: " + purse + " -> " + buyer.purse());
            helper.assertTrue(till == price, "into the stall's till: " + till);
            helper.assertTrue(left == 8 && carries - had == 8, "a lot of eight bread out of the barrel and into its pack: " + left + " left, carried "
                + had + " -> " + carries);
            // At the market's stalls on market day: the player's stall weighed as any other seller.
            second.earn(30);
            int purse2 = second.purse();
            String treat = Market.folkBuys(level, v, second);
            int till2 = PlayerStalls.till(village, p.getUUID());
            Kit.log("st01 at the market's stalls: " + treat + "; purse " + purse2 + " -> " + second.purse() + ", till " + till2
                + ", the barrel " + count(barrel, s -> s.is(Items.BREAD)));
            helper.assertTrue(treat != null && treat.contains("stall"), "with no bread in the stores, a folk buys its treat at the player's stall: " + treat);
            helper.assertTrue(till2 == 2 * price && purse2 - second.purse() == price && count(barrel, s -> s.is(Items.BREAD)) == 0,
                "the second lot sold the same way: till " + till2);
            // The stall's screen: its rent, the till, the bread against the going price, and both sales.
            CompoundTag screen = PlayerStalls.screen(level, v, b, p);
            ListTag wares = screen.getList("wares", Tag.TAG_COMPOUND);
            ListTag sales = screen.getList("sales", Tag.TAG_COMPOUND);
            Kit.log("st01 the screen: " + screen);
            helper.assertTrue(screen.getString("mode").equals("own") && screen.getInt("till") == 2 * price, "the screen shows the till");
            helper.assertTrue(wares.size() == 1 && wares.getCompound(0).getString("key").equals("minecraft:bread")
                && wares.getCompound(0).getInt("price") == price && wares.getCompound(0).getInt("going") > 0
                && wares.getCompound(0).getInt("sold7") == 16, "bread on the screen: its price, the going price, sixteen sold this week");
            helper.assertTrue(sales.size() == 2 && sales.getCompound(0).getInt("price") == price
                && !sales.getCompound(0).getString("who").isEmpty(), "every sale booked: who, what, for how much");
            helper.succeed();
        });
    }

    // ============================================================ too dear

    /**
     * Bread at thirty coins a lot, the going price five or so: nobody buys it, at the stall or weighing it
     * from the market's own; the folk keep their coin, the bread stays in the barrel, the till stays empty,
     * and the stall's books say who thought it too dear.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "st02_stall_too_dear")
    public static void st02_stall_too_dear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerStalls.resetForTests();
        level.setDayTime(6000);
        int x = 291500, z = 50000;
        BlockPos heart = flat(level, x, z);
        VillageFolkEntity buyer = village(level, heart);
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(buyer != null && second != null, "a village of two");
        UUID village = buyer.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            ready(level, heart, village);
            Player p = renter(helper, heart, 20);
            PlayerStalls.Booth b = rentIt(helper, level, v, p, heart, "st02");
            Container barrel = (Container) level.getBlockEntity(b.at());
            barrel.setItem(0, new ItemStack(Items.BREAD, 16));
            int going = PlayerStalls.going(level, village, new ItemStack(Items.BREAD));
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.BREAD), 30);
            helper.assertTrue(30 > going * 1.5, "thirty is far over the going price of " + going);
            buyer.earn(60);
            second.earn(60);
            int purse = buyer.purse(), purse2 = second.purse();
            String got = PlayerStalls.browse(level, v, buyer, p.getUUID(), true);
            String treat = Market.folkBuys(level, v, second);
            Kit.log("st02 bread at 30 (going " + going + "): at the stall " + got + "; at the market " + treat + "; purses " + purse + " -> "
                + buyer.purse() + ", " + purse2 + " -> " + second.purse() + "; till " + PlayerStalls.till(village, p.getUUID())
                + "; turned down " + PlayerStalls.turnedDown(village, p.getUUID()));
            helper.assertTrue(got == null && (treat == null || !treat.contains("stall")), "nobody buys bread at thirty: " + got + " / " + treat);
            helper.assertTrue(buyer.purse() == purse && second.purse() == purse2, "the folk keep their coin");
            helper.assertTrue(count(barrel, s -> s.is(Items.BREAD)) == 16 && PlayerStalls.till(village, p.getUUID()) == 0,
                "the bread stays in the barrel and the till empty");
            helper.assertTrue(PlayerStalls.turnedDown(village, p.getUUID()) >= 1, "the stall's books say it was thought too dear");
            ListTag dear = PlayerStalls.screen(level, v, b, p).getList("dear", Tag.TAG_COMPOUND);
            helper.assertTrue(dear.size() >= 1 && dear.getCompound(0).getInt("price") == 30, "and the screen shows it, with the going price");
            // Brought down to a fair price, it sells.
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.BREAD), fairBread(level, village));
            String now = PlayerStalls.browse(level, v, buyer, p.getUUID(), true);
            Kit.log("st02 at a fair price: " + now);
            helper.assertTrue(now != null, "at a fair price the same folk buys");
            helper.succeed();
        });
    }

    // ============================================================ the till

    /**
     * The till pays out: after a sale, "take the till" (to any folk) puts the coin in the player's pack and
     * empties it; the Shops page's report has the stall, its rent, its stock and prices, the week's sales
     * and takings.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "st03_stall_till_pays_out")
    public static void st03_stall_till_pays_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerStalls.resetForTests();
        level.setDayTime(6000);
        int x = 293000, z = 50000;
        BlockPos heart = flat(level, x, z);
        VillageFolkEntity buyer = village(level, heart);
        helper.assertTrue(buyer != null, "a village");
        UUID village = buyer.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            ready(level, heart, village);
            Player p = renter(helper, heart, 20);
            PlayerStalls.Booth b = rentIt(helper, level, v, p, heart, "st03");
            Container barrel = (Container) level.getBlockEntity(b.at());
            barrel.setItem(0, new ItemStack(Items.BREAD, 8));
            barrel.setItem(1, new ItemStack(Items.APPLE, 8));
            int price = fairBread(level, village);
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.BREAD), price);
            int apples = PlayerStalls.going(level, village, new ItemStack(Items.APPLE));
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.APPLE), Math.max(1, (int) Math.floor(apples * 0.85)));
            buyer.earn(30);
            String got = PlayerStalls.browse(level, v, buyer, p.getUUID(), true);
            int till = PlayerStalls.till(village, p.getUUID());
            helper.assertTrue(got != null && till > 0, "a sale, and coin in the till: " + got + ", " + till);
            ListTag report = PlayerStalls.report(level, village);
            CompoundTag mine = report.getCompound(0);
            Kit.log("st03 the Shops page's report: " + report);
            helper.assertTrue(mine.getString("owner").equals(p.getName().getString()) && mine.getString("state").equals("open")
                && mine.getLong("paidTill") >= level.getDayTime() / 24000L && mine.getInt("coin7") == till
                && mine.getList("wares", Tag.TAG_COMPOUND).size() == 2, "the town's books list the stall: rent, stock, prices, the week");
            int coins = Market.coinsHeld(p);
            String said = Commerce.stall(buyer, p, "could I take the till?");
            Kit.log("st03 the till: " + said + "; coin " + coins + " -> " + Market.coinsHeld(p));
            helper.assertTrue(Market.coinsHeld(p) == coins + till, "the till pays out to the player: " + coins + " + " + till + " -> " + Market.coinsHeld(p));
            helper.assertTrue(PlayerStalls.till(village, p.getUUID()) == 0, "and is empty");
            helper.succeed();
        });
    }

    // ============================================================ the rent runs out

    /**
     * The rent runs out: the stall shuts (nobody buys from it), then three days on it is given back with
     * the player's bread kept in its barrel; the player gives it up and has every loaf back, and the
     * booth stands to let, and another player takes it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "st04_stall_given_back")
    public static void st04_stall_given_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerStalls.resetForTests();
        level.setDayTime(6000);
        int x = 294500, z = 50000;
        BlockPos heart = flat(level, x, z);
        VillageFolkEntity buyer = village(level, heart);
        helper.assertTrue(buyer != null, "a village");
        UUID village = buyer.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            ready(level, heart, village);
            Player p = renter(helper, heart, 20);
            PlayerStalls.Booth b = rentIt(helper, level, v, p, heart, "st04");
            Container barrel = (Container) level.getBlockEntity(b.at());
            barrel.setItem(0, new ItemStack(Items.BREAD, 16));
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.BREAD), fairBread(level, village));
            buyer.earn(30);
            PlayerStalls.lapseForTests(level, v, p.getUUID(), 2);
            String shut = PlayerStalls.browse(level, v, buyer, p.getUUID(), true);
            Kit.log("st04 two days unpaid: " + PlayerStalls.stateOf(level, village, p.getUUID()) + ", a folk at it: " + shut
                + "; the sign " + String.join(" | ", signLines(level, b)));
            helper.assertTrue(PlayerStalls.stateOf(level, village, p.getUUID()) == PlayerStalls.State.DUE && shut == null,
                "its rent run out, the stall is shut: nobody buys");
            PlayerStalls.lapseForTests(level, v, p.getUUID(), PlayerStalls.GRACE + 2);
            Kit.log("st04 given back: " + PlayerStalls.stateOf(level, village, p.getUUID()) + "; the barrel "
                + count(barrel, s -> s.is(Items.BREAD)) + " bread; the sign " + String.join(" | ", signLines(level, b)));
            helper.assertTrue(PlayerStalls.stateOf(level, village, p.getUUID()) == PlayerStalls.State.HELD
                && count(barrel, s -> s.is(Items.BREAD)) == 16, "given back, the bread kept in its barrel");
            int bread = carried(p, s -> s.is(Items.BREAD));
            String said = Commerce.stall(buyer, p, "I'll give up my stall");
            Kit.log("st04 given up: " + said + "; bread carried " + bread + " -> " + carried(p, s -> s.is(Items.BREAD))
                + "; the sign " + String.join(" | ", signLines(level, b)));
            helper.assertTrue(carried(p, s -> s.is(Items.BREAD)) == bread + 16 && count(barrel, s -> s.is(Items.BREAD)) == 0,
                "every loaf back to the player: nothing lost");
            helper.assertTrue(PlayerStalls.stateOf(level, village, p.getUUID()) == PlayerStalls.State.NONE
                && signLines(level, b)[0].contains("to let"), "the stall is to let again");
            Player other = renter(helper, heart, 20);
            String again = PlayerStalls.rent(level, v, other, null);
            PlayerStalls.Booth theirs = PlayerStalls.boothOf(village, other.getUUID());
            Kit.log("st04 let again: " + again);
            helper.assertTrue(theirs != null && theirs.at().equals(b.at()), "another player takes the same stall");
            helper.succeed();
        });
    }

    // ============================================================ to the stall on market day

    /**
     * Market day: a folk with coin near the square goes to the player's stall of its own accord (the
     * errand its time off runs), looks it over and buys a lot of apples, which the stores haven't got.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "st05_stall_market_day_errand")
    public static void st05_stall_market_day_errand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerStalls.resetForTests();
        level.setDayTime(6000);
        int x = 296000, z = 50000;
        BlockPos heart = flat(level, x, z);
        VillageFolkEntity buyer = village(level, heart);
        helper.assertTrue(buyer != null, "a village");
        UUID village = buyer.ownerId();
        final PlayerStalls.Booth[] booth = new PlayerStalls.Booth[1];
        final Player[] owner = new Player[1];
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            while (!Market.marketDay(village, day)) day++;
            level.setDayTime(day * 24000L + 6000L);
            Villages.Village v = Villages.get(village);
            ready(level, heart, village);
            Player p = renter(helper, heart, 20);
            booth[0] = rentIt(helper, level, v, p, heart, "st05");
            owner[0] = p;
            Container barrel = (Container) level.getBlockEntity(booth[0].at());
            barrel.setItem(0, new ItemStack(Items.APPLE, 16));
            int going = PlayerStalls.going(level, village, new ItemStack(Items.APPLE));
            PlayerStalls.priceForTests(level, v, p.getUUID(), new ItemStack(Items.APPLE), Math.max(1, (int) Math.floor(going * 0.85)));
            buyer.earn(40);
            // A step or two from the stall's front.
            BlockPos near = booth[0].stand().relative(booth[0].front().getClockWise(), 1);
            buyer.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t <= 10 || booth[0] == null) return;
            if (level.getDayTime() % 24000L > 11000L) level.setDayTime(level.getDayTime() - 4000L);
            boolean busy = PlayerStalls.errand(buyer, level);
            int till = PlayerStalls.till(village, owner[0].getUUID());
            if (t % 50 == 0) Kit.log("st05 @" + t + ": errand " + busy + ", till " + till + " — " + buyer.debugLine());
            if (till > 0) {
                Container barrel = (Container) level.getBlockEntity(booth[0].at());
                Kit.log("st05 bought at the stall on market day: till " + till + ", apples left " + count(barrel, s -> s.is(Items.APPLE)));
                helper.assertTrue(count(barrel, s -> s.is(Items.APPLE)) == 8, "a lot of eight apples sold");
                helper.succeed();
            } else if (t >= 1100) {
                helper.fail("no folk came to buy on market day: till " + till + " — " + buyer.debugLine());
            }
        });
    }
}
