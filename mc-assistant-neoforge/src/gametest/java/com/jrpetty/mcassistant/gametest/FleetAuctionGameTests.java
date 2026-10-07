package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Auctions;
import com.jrpetty.mcassistant.entity.FishMarket;
import com.jrpetty.mcassistant.entity.Fleet;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Waterfront;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [fleet] The fishing fleet, its fish market and the auction (Fleet, FishMarket, Auctions): two boats go out at dawn
 * from a quay over a test sea, row out to their grounds, fish, row home and land the catch into the market's barrels;
 * folk buy at the market out of their purses, and the price falls with a big catch; the rain keeps the boats in, and a
 * storm at sea sends them home early; a diamond from the stores goes under the hammer, the folk bid within their purses,
 * the keenest takes it, the coin goes from it to the treasury and the diamond to it; and a player outbids a folk and
 * takes the lot home.
 *
 * <p>Each on its own ground (x 1040000 to 1048000, z 66000), in a batch of its own. The sea is cut into the flat world
 * east of the heart: fifty blocks by forty-nine of water two deep, the quay run out into it from its west bank.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FleetAuctionGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A waterside town: its folk (the founder first), its quay, its heart. */
    private record Bay(UUID village, Villages.Village v, BlockPos heart, Waterfront.Dock quay, List<VillageFolkEntity> folk) {}

    /** A town of these trades by a sea of its own, with a quay, the fish market on the bank and the fleet's boats in. */
    private static Bay bay(GameTestHelper helper, int x, long dayTime, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Fleet.resetForTests();
        Auctions.resetForTests();
        Fleet.fromForTests(0);
        Auctions.fromForTests(0);
        Fleet.weatherForTests("clear");
        level.setDayTime(dayTime);
        Kit.hold(level, x + 30, Z, 72);
        Kit.prepare(level, x + 30, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        int y = heart.getY() - 1;
        for (int sx = x + 14; sx < x + 64; sx++) {
            for (int sz = Z - 24; sz <= Z + 24; sz++) {
                level.setBlock(new BlockPos(sx, y, sz), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(sx, y - 1, sz), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        // Bare stores (the founders' chest emptied): nothing for a fisher to build a jetty of into the test's sea.
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) c.clearContent();
        }
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = folk.get(i);
            f.setJob(trades[i]);
            f.ensurePersona();
            if (trades[i] == StationTask.FISH) f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
        }
        Waterfront.Dock quay = new Waterfront.Dock(new BlockPos(x + 14, y, Z), Direction.EAST, 6);
        Waterfront.build(level, quay);
        Fleet.quayForTests(v, quay);
        helper.assertTrue(FishMarket.buildForTests(level, v, quay), "the fish market put up on the bank by the quay");
        int boats = Fleet.boatsForTests(level, v);
        Kit.log("fa bay at " + x + ": " + trades.length + " folk, quay at " + quay.start().toShortString() + ", stall at "
            + FishMarket.stallForTests(village).stand().toShortString() + ", " + boats + " boats");
        return new Bay(village, v, heart, quay, folk);
    }

    private static int count(VillageFolkEntity f, Item it) {
        int n = f.getMainHandItem().is(it) ? f.getMainHandItem().getCount() : 0;
        for (ItemStack s : f.getInventoryItems()) if (s.is(it)) n += s.getCount();
        return n;
    }

    private static int count(Player p, Item it) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(it)) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    // ============================================================ fa01: out at dawn, home with the catch

    /**
     * At dawn the two fishers of a town with a fleet are sent down to the boats; aboard, they row out over the sea to
     * grounds of their own (well out from the quay), fish, and in the afternoon row home, tie up, step out onto the quay
     * and land their catch: the cod and salmon are in the fish market's barrels, the market is open, and the boats are
     * back alongside the quay with nobody in them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "fa01_sail")
    public static void fa01_sail(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Bay b = bay(helper, 1040000, 100L, StationTask.FARM, StationTask.FISH, StationTask.FISH, StationTask.FARM);
        String sailed = Fleet.sailForTests(level, b.v());
        int aboard = Fleet.boardForTests(level, b.v());
        Kit.log("fa01 at dawn: " + sailed + "; " + aboard + " aboard; crew " + Fleet.handsForTests(b.village()));
        helper.assertTrue(sailed.equals("out at sea") && aboard == 2, "two boats out at dawn: " + sailed + ", " + aboard + " aboard");
        int[] phase = { 0 };
        double[] far = { 0 };
        int[] caught = { 0 };
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            List<String> hands = Fleet.handsForTests(b.village());
            if (tick % 50 == 0) Kit.log("fa01 tick " + tick + " phase " + phase[0] + ": " + hands + "; market " + FishMarket.dayForTests(level, b.village()));
            if (phase[0] == 0) {
                boolean allFishing = hands.size() == 2;
                for (String h : hands) if (!h.contains(" FISHING ")) allFishing = false;
                if (!allFishing) return;
                far[0] = Fleet.farForTests(b.village());
                caught[0] = Fleet.fishForTests(level, b.v(), 6);
                Kit.log("fa01 at the grounds at tick " + tick + ": furthest out " + String.format("%.1f", far[0]) + " blocks; caught " + caught[0]);
                helper.assertTrue(far[0] >= 8.0, "out over open water, well off the quay: " + far[0]);
                helper.assertTrue(caught[0] >= 12, "a catch at sea, at least a fish a bite: " + caught[0]);
                level.setDayTime(8000L);                               // the afternoon: home
                phase[0] = 1;
                return;
            }
            if (Fleet.anyOutForTests(b.village())) return;
            int inBarrels = FishMarket.inBarrelsForTests(level, b.village());
            int riding = 0, home = 0;
            List<Boat> boats = Fleet.boatListForTests(level, b.v());
            for (Boat boat : boats) {
                if (!boat.getPassengers().isEmpty()) riding++;
                if (boat.blockPosition().distSqr(b.quay().end()) <= 5 * 5) home++;
            }
            String day = FishMarket.dayForTests(level, b.village());
            Kit.log("fa01 all ashore at tick " + tick + ": " + inBarrels + " cod and salmon in the market's barrels; market " + day + "; boats "
                + boats.size() + ", " + home + " alongside, " + riding + " with somebody in them");
            helper.assertTrue(inBarrels > 0, "the catch landed into the fish market's barrels: " + inBarrels);
            helper.assertTrue(day.contains(" true "), "the market open with the boats in: " + day);
            helper.assertTrue(riding == 0, "nobody left sitting in a boat");
            helper.assertTrue(home == boats.size() && boats.size() == 2, "the boats back alongside the quay: " + home + " of " + boats.size());
            helper.succeed();
        });
    }

    // ============================================================ fa02: the fish market

    /**
     * A fisher lands six fish: the market opens, and a folk buys there, paying out of its own purse into the treasury
     * as the fish leave the barrels for its pack. The same day the boats land ninety: the price falls to under two thirds
     * of what it was, and a folk buys again, at least as much. A player buys a lot of four at the counter for coin.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa02_market")
    public static void fa02_market(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Bay b = bay(helper, 1042000, 9000L, StationTask.FARM, StationTask.FISH, StationTask.FARM, StationTask.FARM);
        UUID id = b.village();
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity fisher = b.folk().get(1), buyer = b.folk().get(2), second = b.folk().get(3);
        FishMarket.newDayForTests(b.v(), day, 1);
        fisher.insertItem(new ItemStack(Items.COD, 6));
        int landed = FishMarket.landForTests(level, b.v(), fisher);
        ItemStack cod = new ItemStack(Items.COD);
        double small = FishMarket.priceEach(level, b.v(), cod, null);
        Kit.log("fa02 a small catch: landed " + landed + "; market " + FishMarket.dayForTests(level, id) + "; cod at " + String.format("%.3f", small));
        helper.assertTrue(landed == 6 && FishMarket.inBarrelsForTests(level, id) == 6, "six fish into the barrels: " + landed);
        buyer.earn(10);
        int purse0 = buyer.purse(), treasury0 = Ledger.coins(id), barrels0 = FishMarket.inBarrelsForTests(level, id), carried0 = count(buyer, Items.COD);
        String bought = FishMarket.serveForTests(level, b.v(), buyer);
        int paid = purse0 - buyer.purse(), took = Ledger.coins(id) - treasury0, sold = barrels0 - FishMarket.inBarrelsForTests(level, id);
        int got = count(buyer, Items.COD) - carried0;
        Kit.log("fa02 " + buyer.displayNameCap() + " bought " + bought + ": paid " + paid + " (purse " + purse0 + " -> " + buyer.purse()
            + "), the treasury took " + took + ", " + sold + " out of the barrels, " + got + " in its pack");
        helper.assertTrue(bought != null && got >= 1 && got == sold, "fish from the barrels into its pack: " + got + ", " + sold);
        helper.assertTrue(paid >= 1 && paid == took, "paid out of its purse into the treasury: " + paid + ", " + took);
        // The same day, a big catch: ninety more.
        FishMarket.newDayForTests(b.v(), day, 1);
        fisher.insertItem(new ItemStack(Items.COD, 64));
        fisher.insertItem(new ItemStack(Items.COD, 26));
        int big = FishMarket.landForTests(level, b.v(), fisher);
        double cheap = FishMarket.priceEach(level, b.v(), cod, null);
        Kit.log("fa02 a big catch: landed " + big + "; market " + FishMarket.dayForTests(level, id) + "; cod at " + String.format("%.3f", cheap)
            + " (was " + String.format("%.3f", small) + ")");
        helper.assertTrue(big == 90, "ninety more landed: " + big);
        helper.assertTrue(cheap < small * 0.66, "the price falls with a big catch: " + cheap + " against " + small);
        second.earn(10);
        int carried1 = count(second, Items.COD), purse1 = second.purse();
        String again = FishMarket.serveForTests(level, b.v(), second);
        int got2 = count(second, Items.COD) - carried1;
        Kit.log("fa02 " + second.displayNameCap() + " bought " + again + " (" + got2 + "; purse " + purse1 + " -> " + second.purse() + ")");
        helper.assertTrue(again != null && got2 >= got, "a folk buys at least as much when it is cheap: " + got2 + " against " + got);
        // A player at the counter.
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.moveTo(b.heart().getX() + 10.5, b.heart().getY(), b.heart().getZ() + 0.5);
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
        int coins0 = Market.coinsHeld(you), treasury1 = Ledger.coins(id);
        String said = FishMarket.playerBuys(level, b.v(), you);
        Kit.log("fa02 the player: \"" + said + "\"; coins " + coins0 + " -> " + Market.coinsHeld(you) + ", cod " + count(you, Items.COD)
            + ", the treasury +" + (Ledger.coins(id) - treasury1));
        helper.assertTrue(count(you, Items.COD) == FishMarket.LOT_FOR_TESTS && coins0 - Market.coinsHeld(you) == Ledger.coins(id) - treasury1
            && Ledger.coins(id) > treasury1, "the player bought a lot of four for coin, into the treasury: " + said);
        helper.succeed();
    }

    // ============================================================ fa03: the weather

    /**
     * Rain at dawn: the boats stay in, nobody goes down to them, and the books say why. The next dawn is clear and the
     * boats go out; a storm blows up while they are at sea, and they come home at once, long before the afternoon.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "fa03_weather")
    public static void fa03_weather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Bay b = bay(helper, 1044000, 100L, StationTask.FARM, StationTask.FISH, StationTask.FISH, StationTask.FARM);
        Fleet.weatherForTests("rain");
        String rain = Fleet.sailForTests(level, b.v());
        Kit.log("fa03 rain at dawn: " + rain + "; crew " + Fleet.handsForTests(b.village()) + "; board " + Fleet.boardLine(level, b.village()));
        helper.assertTrue(rain.contains("kept in by the rain") && !Fleet.anyOutForTests(b.village()), "the rain keeps the boats in: " + rain);
        Fleet.weatherForTests("clear");
        String clear = Fleet.sailForTests(level, b.v());
        int aboard = Fleet.boardForTests(level, b.v());
        Kit.log("fa03 clear: " + clear + ", " + aboard + " aboard");
        helper.assertTrue(clear.equals("out at sea") && aboard == 2, "out when it clears: " + clear + ", " + aboard);
        boolean[] stormed = { false };
        long[] stormAt = { -1 };
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            List<String> hands = Fleet.handsForTests(b.village());
            if (tick % 50 == 0) Kit.log("fa03 tick " + tick + ": " + hands);
            if (!stormed[0]) {
                boolean out = !hands.isEmpty();
                for (String h : hands) if (!h.contains(" OUT ") && !h.contains(" FISHING ")) out = false;
                if (!out || Fleet.farForTests(b.village()) < 6.0) return;
                Fleet.weatherForTests("storm");
                stormed[0] = true;
                stormAt[0] = tick;
                Kit.log("fa03 a storm at sea at tick " + tick + ", " + String.format("%.1f", Fleet.farForTests(b.village())) + " blocks out");
                return;
            }
            if (Fleet.anyOutForTests(b.village())) return;
            long tod = level.getDayTime() % 24000L;
            Kit.log("fa03 all home " + (tick - stormAt[0]) + " ticks after the storm, at " + tod + " o'clock-ticks");
            helper.assertTrue(tod < 8000L, "home early, before the afternoon: " + tod);
            int riding = 0;
            for (Boat boat : Fleet.boatListForTests(level, b.v())) if (!boat.getPassengers().isEmpty()) riding++;
            helper.assertTrue(riding == 0, "nobody left out in a boat in the storm");
            helper.succeed();
        });
    }

    // ============================================================ fa04: a diamond under the hammer

    /**
     * A Stone Age town with seven diamonds in its stores (it keeps five) puts one up at the auction. The folk come who can
     * afford it (the one with six coins does not); they bid, each within its own purse and never past what the diamond
     * is worth to it; the one to whom it is worth the most takes it, its coin goes to the treasury, and the diamond out of
     * the stores into its pack, its own.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa04_auction")
    public static void fa04_auction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Bay b = bay(helper, 1046000, 9000L, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = b.village();
        Villages.ageForTests(id, Villages.Age.STONE);
        Auctions.manualForTests(true);
        BlockPos at = Kit.surface(level, b.heart().getX() + 6, b.heart().getZ() + 6);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        ((Container) level.getBlockEntity(at)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        VillageFolkEntity rich = b.folk().get(1), middling = b.folk().get(2), poor = b.folk().get(3);
        rich.earn(100);
        middling.earn(30);
        poor.earn(6);
        List<String> lots = Auctions.openForTests(level, b.v());
        Kit.log("fa04 lots: " + lots);
        helper.assertTrue(!lots.isEmpty() && lots.get(0).startsWith("diamond"), "a diamond up for auction: " + lots);
        Auctions.onlyFirstLotForTests(id);
        int crowd = Auctions.startForTests(level, b.v());
        Map<UUID, Integer> most = Auctions.mostForTests(id);
        Map<UUID, Integer> purse0 = new java.util.HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (VillageFolkEntity f : b.folk()) {
            purse0.put(f.getUUID(), f.purse());
            sb.append(' ').append(f.displayNameCap()).append(" purse ").append(f.purse()).append(" most ").append(most.getOrDefault(f.getUUID(), -1)).append(';');
        }
        Kit.log("fa04 a crowd of " + crowd + ":" + sb);
        helper.assertTrue(crowd >= 2, "the folk who can afford it come: " + crowd);
        helper.assertTrue(!most.containsKey(poor.getUUID()), "the one with six coins stays away");
        int treasury0 = Ledger.coins(id), diamonds0 = Market.stock(level, id, s -> s.is(Items.DIAMOND));
        for (int i = 0; i < 300 && !Auctions.doneForTests(id); i++) Auctions.roundForTests(level, b.v());
        List<String> bids = Auctions.bidsForTests(id);
        List<String> results = Auctions.resultsForTests(id);
        Kit.log("fa04 bids " + bids + "; results " + results);
        helper.assertTrue(Auctions.doneForTests(id) && results.size() == 1 && results.get(0).contains("sold to "), "the diamond sold: " + results);
        UUID best = null;
        for (Map.Entry<UUID, Integer> e : most.entrySet()) if (best == null || e.getValue() > most.get(best)) best = e.getKey();
        VillageFolkEntity winner = null;
        for (VillageFolkEntity f : b.folk()) if (results.get(0).contains("sold to " + f.displayNameCap() + " for")) winner = f;
        helper.assertTrue(winner != null, "a folk won it: " + results);
        int price = purse0.get(winner.getUUID()) - winner.purse();
        Kit.log("fa04 " + winner.displayNameCap() + " won it for " + price + " (it would go to " + most.get(winner.getUUID()) + "); the treasury "
            + treasury0 + " -> " + Ledger.coins(id) + "; diamonds in the stores " + diamonds0 + " -> " + Market.stock(level, id, s -> s.is(Items.DIAMOND))
            + "; it carries " + count(winner, Items.DIAMOND));
        helper.assertTrue(most.get(winner.getUUID()).equals(most.get(best)), "the one to whom it was worth the most took it");
        helper.assertTrue(price >= 1 && price <= most.get(winner.getUUID()), "within what it would pay: " + price);
        helper.assertTrue(Ledger.coins(id) - treasury0 == price, "the coin from its purse to the treasury: " + (Ledger.coins(id) - treasury0) + " of " + price);
        helper.assertTrue(Market.stock(level, id, s -> s.is(Items.DIAMOND)) == diamonds0 - 1 && count(winner, Items.DIAMOND) == 1,
            "the diamond out of the stores and into its pack");
        for (String bid : bids) {
            for (VillageFolkEntity f : b.folk()) {
                if (!bid.startsWith(f.displayNameCap() + " ")) continue;
                int coins = Integer.parseInt(bid.substring(f.displayNameCap().length() + 1).trim());
                helper.assertTrue(coins <= purse0.get(f.getUUID()) && coins <= most.getOrDefault(f.getUUID(), 0),
                    f.displayNameCap() + " bid within its purse and its mind: " + coins);
            }
        }
        helper.succeed();
    }

    // ============================================================ fa05: a player outbids a folk

    /**
     * The same diamond, one folk with a hundred coins keen on it, and a player with a pocketful: they bid against each
     * other, the player always a step over, and the player takes it. The player's coin (only the winning bid's: the bids
     * beaten along the way came back) goes to the treasury, the diamond into the player's pack, and the folk keeps its
     * purse.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa05_player")
    public static void fa05_player(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Bay b = bay(helper, 1048000, 9000L, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = b.village();
        Villages.ageForTests(id, Villages.Age.STONE);
        Auctions.manualForTests(true);
        BlockPos at = Kit.surface(level, b.heart().getX() + 6, b.heart().getZ() + 6);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        ((Container) level.getBlockEntity(at)).setItem(0, new ItemStack(Items.DIAMOND, 6));
        VillageFolkEntity rival = b.folk().get(1);
        rival.earn(100);
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.moveTo(b.heart().getX() + 2.5, b.heart().getY(), b.heart().getZ() + 2.5);
        for (int i = 0; i < 3; i++) you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        List<String> lots = Auctions.openForTests(level, b.v());
        Auctions.onlyFirstLotForTests(id);
        int crowd = Auctions.startForTests(level, b.v());
        Map<UUID, Integer> most = Auctions.mostForTests(id);
        int coins0 = Market.coinsHeld(you), purse0 = rival.purse(), treasury0 = Ledger.coins(id);
        Kit.log("fa05 lots " + lots + "; a crowd of " + crowd + "; " + rival.displayNameCap() + " would go to " + most.get(rival.getUUID())
            + "; the player has " + coins0);
        helper.assertTrue(!lots.isEmpty() && lots.get(0).startsWith("diamond") && most.getOrDefault(rival.getUUID(), 0) >= 12,
            "a diamond up, and the folk keen on it: " + lots + ", " + most);
        List<String> said = new ArrayList<>();
        for (int i = 0; i < 300 && !Auctions.doneForTests(id); i++) {
            UUID high = Auctions.highBidderForTests(id);
            if (high == null || !high.equals(you.getUUID())) {
                int next = Auctions.nextBidForTests(id);
                if (next > 0) said.add(Auctions.bid(level, b.v(), you, next));
            }
            Auctions.roundForTests(level, b.v());
        }
        List<String> bids = Auctions.bidsForTests(id);
        List<String> results = Auctions.resultsForTests(id);
        int price = coins0 - Market.coinsHeld(you);
        Kit.log("fa05 bids " + bids + "; results " + results + "; the player paid " + price + " and has " + count(you, Items.DIAMOND)
            + " diamond; " + rival.displayNameCap() + "'s purse " + purse0 + " -> " + rival.purse() + "; the treasury +" + (Ledger.coins(id) - treasury0)
            + "; last said: " + (said.isEmpty() ? "" : said.get(said.size() - 1)));
        boolean folkBid = false;
        for (String bid : bids) if (bid.startsWith(rival.displayNameCap() + " ")) folkBid = true;
        helper.assertTrue(folkBid, "the folk bid against the player: " + bids);
        helper.assertTrue(results.size() == 1 && results.get(0).contains("sold to " + you.getName().getString()), "the player took the lot: " + results);
        helper.assertTrue(count(you, Items.DIAMOND) == 1, "the diamond in the player's pack");
        int rivalBest = 0;
        for (String bid : bids) {
            if (bid.startsWith(rival.displayNameCap() + " ")) rivalBest = Math.max(rivalBest, Integer.parseInt(bid.substring(rival.displayNameCap().length() + 1).trim()));
        }
        helper.assertTrue(price > rivalBest && Ledger.coins(id) - treasury0 == price,
            "the player's coin, over the folk's best bid of " + rivalBest + ", to the treasury: " + price);
        helper.assertTrue(rival.purse() == purse0, "the folk outbid keeps its purse: " + rival.purse());
        helper.succeed();
    }
}
