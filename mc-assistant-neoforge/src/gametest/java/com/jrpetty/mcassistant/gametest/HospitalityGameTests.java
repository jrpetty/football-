package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.OutfitIndex;
import com.jrpetty.mcassistant.client.TalkMenu;
import com.jrpetty.mcassistant.client.TalkMenu.Category;
import com.jrpetty.mcassistant.client.TalkMenu.Context;
import com.jrpetty.mcassistant.client.TalkMenu.Entry;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bakery;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Hospitality;
import com.jrpetty.mcassistant.entity.HospitalityShifts;
import com.jrpetty.mcassistant.entity.HospitalityStage;
import com.jrpetty.mcassistant.entity.HospitalityTalk;
import com.jrpetty.mcassistant.entity.Inn;
import com.jrpetty.mcassistant.entity.JobWorth;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Offices;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.Tavern;
import com.jrpetty.mcassistant.entity.TownLook;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Weave;
import com.jrpetty.mcassistant.entity.Windmill;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [roles-c] Food and hospitality (Hospitality, Bakery, Tavern, Inn, Windmill, HospitalityTalk, HospitalityShifts,
 * HospitalityStage).
 *
 * <ul>
 * <li><b>ho01</b>: each post's talk option sits in Trade's shop group (Trade still at six options or fewer), shows only at
 *     its post, is its suggestion, and its words are heard; the posts' own outfits; every post on the books; the kitchen
 *     hands grow with the town.</li>
 * <li><b>ho02</b>: the baker at its post fetches the mill's wheat on its back and bakes it to bread: every grain counted
 *     (mill, stores, its pack, three to a loaf); the works leave the bakery to it. Worst case: no wheat anywhere, nothing
 *     baked and the café's books count the bread it could not make, once.</li>
 * <li><b>ho03</b>: the barkeep pours a round from the stores, keeps its share, the rest into the till, every bottle back
 *     in the stores; a pint by its talk; empty bottles paid back a coin for three; anybody else sends you to it. Worst
 *     case: the casks dry, nothing sold and the tavern's books count it.</li>
 * <li><b>ho04</b>: a player asks the innkeeper for a room for the night: the room, the keeper's share, the rest into the
 *     till; a traveller's room shares too; anybody else names the keeper. Worst case: short of coin, no room.</li>
 * <li><b>ho05</b>: the miller chosen, paid as an office, dressed for it, and selling a sack of the mill's wheat but never
 *     the bakery's two; the rancher's meat from its butcher's block, and none to sell said so.</li>
 * <li><b>ho06</b>: a baker's-hand shift: a batch into the oven from the stores' wheat, out into the stores, paid; a burnt
 *     batch unpaid.</li>
 * <li><b>ho07</b>: "/village hospitality stage" sets out every view, its posts in their clothes; "/village hospitality"
 *     reads the books.</li>
 * </ul>
 * Band x 2,080,000 to 2,099,999, z 66,000; a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class HospitalityGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 4;
    private static final Predicate<ItemStack> WHEAT = s -> s.is(Items.WHEAT) && s.getComponentsPatch().isEmpty();
    private static final Predicate<ItemStack> BREAD = s -> s.is(Items.BREAD);

    // ------------------------------------------------------------------ helpers

    /** Flat grass round here, clear air above it. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A clean slate and a village of these trades (the first the founder) on flat ground at x, clear weather, at this time. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, long time, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Shifts.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = flat(level, x, Z, 48);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            BlockPos at = i == 0 ? heart : Kit.surface(level, x + 2 + (i % 5), Z + 2 + i / 5);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && (out.isEmpty() || out.get(0).ownerId().equals(f.ownerId())), "folk " + i + " of the village");
            if (i > 0) f.setAgeForTests(30 + i);
            out.add(f);
        }
        return out;
    }

    /** Each folk to its trade, now (in the test's first tick: a trade set at the raising may be traded away before it). */
    private static void jobs(List<VillageFolkEntity> folk, StationTask... trades) {
        for (int i = 0; i < trades.length; i++) if (trades[i] != StationTask.NONE) folk.get(i).setJob(trades[i]);
    }

    /** Nothing in the village's stores: only what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
    }

    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static int carried(ServerPlayer p, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    /** A building stamped on the ground here, its back to the north, and booked as the village's. */
    private static BlockPos building(ServerLevel level, UUID village, String name, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, name, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, name, p, Direction.NORTH);
        return p;
    }

    /** The windmill's first chest. */
    private static Container millChest(ServerLevel level, BlockPos mill) {
        for (BuildGoal.Placement p : BuildGoal.plan(TownLook.WINDMILL, mill, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.CHEST && level.getBlockEntity(p.pos()) instanceof Container c) return c;
        }
        return null;
    }

    private static String say(VillageFolkEntity f, ServerPlayer p, String words) {
        return FolkTalk.answer(f, p, TalkTopic.SAY, words);
    }

    private static int note(UUID village, String key) {
        String s = Ledger.note(village, key);
        return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
    }

    private static Context ctx(String line) {
        return Context.of(line, 0, false, false, false);
    }

    // ============================================================ ho01: the menu, the clothes, the books

    /** Each post's own option, the words that ask it, and the Ask HospitalityTalk hears in them. */
    private static final Map<String, HospitalityTalk.Ask> OWN = Map.of("inn_room", HospitalityTalk.Ask.ROOM, "bar_pint", HospitalityTalk.Ask.PINT,
        "bar_round", HospitalityTalk.Ask.ROUND, "bar_bottles", HospitalityTalk.Ask.BOTTLES, "bakery_bread", HospitalityTalk.Ask.BREAD,
        "mill_sack", HospitalityTalk.Ask.SACK, "ranch_meat", HospitalityTalk.Ask.MEAT);

    @GameTest(template = EMPTY, timeoutTicks = 40, batch = "ho01_menu")
    public static void ho01_menu(GameTestHelper helper) {
        // Trade's shop group holds them all; Trade itself stays at six options or fewer at every post.
        Entry shop = null;
        for (Entry e : Category.TRADE.entries()) if (e.id().equals("shop")) shop = e;
        helper.assertTrue(shop != null, "Trade has its shop group");
        List<String> inShop = new ArrayList<>();
        for (Entry e : shop.children()) inShop.add(e.id());
        helper.assertTrue(inShop.containsAll(OWN.keySet()), "every hospitality option is in the shop group: " + inShop);
        Context barkeep = ctx("v1 trade=COOK post=BARKEEP standing=FRIEND"), baker = ctx("v1 trade=COOK post=BAKER standing=FRIEND");
        Context keeper = ctx("v1 trade=COOK post=INNKEEPER inn standing=FRIEND"), shopInn = ctx("v1 trade=SHOP inn standing=FRIEND");
        Context cook = ctx("v1 trade=COOK standing=FRIEND"), miller = ctx("v1 trade=FARM miller standing=FRIEND");
        Context farmer = ctx("v1 trade=FARM standing=FRIEND"), rancher = ctx("v1 trade=RANCH standing=FRIEND");
        for (Context c : List.of(barkeep, baker, keeper, shopInn, cook, miller, rancher)) {
            int n = TalkMenu.visible(Category.TRADE.entries(), c).size();
            helper.assertTrue(n >= 3 && n <= 6, "Trade has " + n + " options of its own (3 to 6) for " + c);
        }
        // Only at its post.
        List<String> atBar = ids(TalkMenu.visible(shop.children(), barkeep)), atCook = ids(TalkMenu.visible(shop.children(), cook));
        List<String> atFarm = ids(TalkMenu.visible(shop.children(), farmer)), atMill = ids(TalkMenu.visible(shop.children(), miller));
        helper.assertTrue(atBar.containsAll(List.of("bar_pint", "bar_round", "bar_bottles")) && !atBar.contains("bakery_bread")
            && !atBar.contains("inn_room"), "the barkeep's own at the bar, nobody else's: " + atBar);
        helper.assertTrue(java.util.Collections.disjoint(atCook, OWN.keySet()), "a plain cook has none of them: " + atCook);
        helper.assertTrue(!atFarm.contains("mill_sack") && atMill.contains("mill_sack"), "the mill's sack from its miller only");
        helper.assertTrue(ids(TalkMenu.visible(shop.children(), rancher)).contains("ranch_meat"), "the rancher's meat at its block");
        // Its suggestion: the post's own before the trade's.
        helper.assertTrue(last(barkeep).equals("bar_pint") && last(baker).equals("bakery_bread") && last(keeper).equals("inn_room")
            && last(shopInn).equals("inn_room") && last(miller).equals("mill_sack") && last(cook).equals("meal") && last(farmer).equals("farm_growing"),
            "each post's own suggested: " + last(barkeep) + " " + last(baker) + " " + last(keeper) + " " + last(shopInn) + " " + last(miller)
                + " " + last(cook) + " " + last(farmer));
        // Its words are heard; talk of a thing asks nothing.
        List<String> unheard = new ArrayList<>();
        for (Entry e : shop.children()) {
            HospitalityTalk.Ask want = OWN.get(e.id());
            if (want != null && HospitalityTalk.ask(e.text()) != want) unheard.add(e.id() + ": \"" + e.text() + "\"");
        }
        helper.assertTrue(unheard.isEmpty(), "every option's words are heard: " + unheard);
        helper.assertTrue(HospitalityTalk.ask("Could I have 8 bread?") == null && HospitalityTalk.ask("Tell me about the tavern") == null
            && HospitalityTalk.ask("What's your room like?") == null && HospitalityTalk.ask("Could I buy a horse?") == null,
            "the stores', the town's and the rancher's other words are not the hospitality posts'");
        // The posts' own clothes: the front of the house and the baker; the kitchen keeps the cook's whites.
        int[] own = { OutfitIndex.ofPost("BAKER"), OutfitIndex.ofPost("BARKEEP"), OutfitIndex.ofPost("INNKEEPER"), OutfitIndex.ofPost("WAITER") };
        String[] names = { "baker", "barkeep", "innkeeper", "waiter" };
        for (int i = 0; i < own.length; i++) {
            helper.assertTrue(own[i] > 0 && own[i] < OutfitIndex.NAMES.length && OutfitIndex.NAMES[own[i]].equals(names[i]),
                names[i] + " wears its own: " + own[i]);
        }
        helper.assertTrue(OutfitIndex.ofPost("HOST") == OutfitIndex.INNKEEPER && OutfitIndex.ofPost("KITCHEN_HAND") == 0
            && OutfitIndex.ofPost("HEAD_COOK") == 0 && OutfitIndex.ofPost("NONE") == 0, "the host as the innkeeper; the kitchen in whites");
        // Every post on the books; the kitchen hand's below a cook's.
        for (Hospitality.Role r : Hospitality.Role.values()) {
            if (r == Hospitality.Role.NONE || r == Hospitality.Role.LINE_COOK) continue;
            helper.assertTrue(JobWorth.post("COOK/" + r.name()) != null, r.title + " has its post on the books");
        }
        helper.assertTrue(JobWorth.post("COOK/KITCHEN_HAND").difficulty() < JobWorth.post("COOK").difficulty(), "a kitchen hand is less skilled work");
        helper.assertTrue(ShiftRole.of("bake") == ShiftRole.BAKE && ShiftRole.BAKE.bosses.contains(StationTask.COOK), "a baker's-hand shift");
        // The kitchen hands grow with the town, held to what a kitchen has room for.
        int[] sizes = { 20, 30, 70, 110, 150, 500 };
        int[] hands = { 0, 1, 2, 3, 4, 4 };
        for (int i = 0; i < sizes.length; i++) {
            helper.assertTrue(Hospitality.kitchenHands(sizes[i], true) == hands[i],
                "a town of " + sizes[i] + " wants " + hands[i] + " kitchen hands: " + Hospitality.kitchenHands(sizes[i], true));
        }
        helper.assertTrue(Hospitality.kitchenHands(200, false) == 0, "no kitchen, no kitchen hands");
        Kit.log("ho01 shop " + inShop + "; at the bar " + atBar);
        helper.succeed();
    }

    private static List<String> ids(List<Entry> es) {
        List<String> out = new ArrayList<>();
        for (Entry e : es) out.add(e.id());
        return out;
    }

    private static String last(Context c) {
        List<Entry> s = TalkMenu.suggested(c);
        return s.isEmpty() ? "" : s.get(s.size() - 1).id();
    }

    // ============================================================ ho02: the baker, from the mill's wheat to bread

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ho02_baker")
    public static void ho02_baker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2080000;
        StationTask[] trades = { StationTask.FARM, StationTask.COOK, StationTask.COOK, StationTask.FARM };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 1000, trades);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Villages.Village v = Villages.get(id);
            emptyStores(level, id);
            chestAt(level, heart.offset(5, 0, -5), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.COBBLESTONE, 16));
            building(level, id, TownLook.BAKERY, heart.offset(-14, 0, 12));
            BlockPos mill = building(level, id, TownLook.WINDMILL, heart.offset(38, 0, 0));
            Container box = millChest(level, mill);
            helper.assertTrue(box != null, "the mill has its chests");
            box.setItem(0, new ItemStack(Items.WHEAT, 64));
            box.setChanged();
            VillageFolkEntity baker = folk.get(1);
            baker.removeMatching(WHEAT, 999);
            Hospitality.appointForTests(level, v, baker, Hospitality.Role.BAKER);
            int mill0 = Windmill.grainForTests(level, id), wheat0 = stock(level, id, WHEAT), bread0 = stock(level, id, BREAD);
            List<String> beats = Bakery.postRoundsForTests(level, baker, 10);
            int mill1 = Windmill.grainForTests(level, id), wheat1 = stock(level, id, WHEAT), bread1 = stock(level, id, BREAD);
            int carried = baker.countCarried(WHEAT);
            boolean held = Bakery.postHeldForTests(level, id);
            Kit.log("ho02 the baker's round " + beats + "; mill " + mill0 + " -> " + mill1 + ", stores' wheat " + wheat0 + " -> " + wheat1
                + ", bread " + bread0 + " -> " + bread1 + ", carried " + carried + "; post held " + held + "; card '" + TownLook.cardLine(baker) + "'");
            helper.assertTrue(mill0 == 64 && mill1 <= 64 - 27, "a sack of the mill's wheat fetched on its back: " + mill0 + " -> " + mill1);
            helper.assertTrue(bread1 > bread0, "baked into bread, into the stores: " + bread0 + " -> " + bread1);
            helper.assertTrue(mill1 + wheat1 + carried + 3 * (bread1 - bread0) == mill0 + wheat0,
                "every grain counted, three to a loaf: " + mill1 + " + " + wheat1 + " + " + carried + " + 3x" + (bread1 - bread0) + " vs " + (mill0 + wheat0));
            helper.assertTrue(held, "the bakery is its baker's: the works leave it be");
            helper.assertTrue(JobWorth.postOf(baker).key.equals("COOK/BAKER") && "Baker".equals(Hospitality.badge(baker)),
                "the baker's post on the books and its nameplate");
            // Worst case: no wheat in the stores or the mill. Nothing baked, and the café's books count the bread, once a day.
            box.clearContent();
            box.setChanged();
            emptyStores(level, id);
            chestAt(level, heart.offset(5, 0, -5), new ItemStack(Items.CRAFTING_TABLE));
            baker.removeMatching(WHEAT, 999);
            int missed0 = Stockroom.missedTodayForTests(level, id, Stockroom.Seller.CAFE, new ItemStack(Items.BREAD));
            int bread2 = stock(level, id, BREAD);
            List<String> dry = Bakery.postRoundsForTests(level, baker, 3);
            int missed1 = Stockroom.missedTodayForTests(level, id, Stockroom.Seller.CAFE, new ItemStack(Items.BREAD));
            Kit.log("ho02 dry: " + dry + "; bread missed " + missed0 + " -> " + missed1);
            helper.assertTrue(stock(level, id, BREAD) == bread2 && String.join(",", dry).contains("idle"), "nothing baked from nothing: " + dry);
            helper.assertTrue(missed1 >= 1 && missed1 - missed0 <= 1, "the shortage booked, once a day: " + missed0 + " -> " + missed1);
            helper.succeed();
        });
    }

    // ============================================================ ho03: the barkeep, the round, the till, the bottles

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ho03_tavern")
    public static void ho03_tavern(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2083000;
        StationTask[] trades = { StationTask.FARM, StationTask.COOK, StationTask.MINE, StationTask.FISH };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 6000, trades);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Villages.Village v = Villages.get(id);
            emptyStores(level, id);
            ItemStack[] drinks = new ItemStack[8];
            for (int i = 0; i < drinks.length; i++) drinks[i] = Cafe.drink(Cafe.DRINKS.get(i % Cafe.DRINKS.size()));
            chestAt(level, heart.offset(5, 0, -5), drinks);
            BlockPos tav = building(level, id, "tavern", heart.offset(14, 0, -14));
            VillageFolkEntity barkeep = folk.get(1);
            Hospitality.appointForTests(level, v, barkeep, Hospitality.Role.BARKEEP);
            barkeep.moveTo(tav.getX() - 2.5, tav.getY(), tav.getZ() + 0.5, 90.0F, 0.0F);
            for (int i : new int[]{ 0, 2, 3 }) folk.get(i).moveTo(tav.getX() + i - 0.5, tav.getY(), tav.getZ() + 1.5, 0.0F, 0.0F);
            ServerPlayer pl = player(helper, tav.south(2), 30);
            int drinks0 = stock(level, id, Cafe::isDrink), bottles0 = stock(level, id, s -> s.is(Items.GLASS_BOTTLE));
            int till0 = Ledger.coins(id), purse0 = barkeep.purse(), coins0 = Market.coinsHeld(pl);
            String round = Tavern.round(level, v, pl);
            int price = coins0 - Market.coinsHeld(pl), share = barkeep.purse() - purse0, till1 = Ledger.coins(id);
            int drinks1 = stock(level, id, Cafe::isDrink), bottles1 = stock(level, id, s -> s.is(Items.GLASS_BOTTLE));
            Kit.log("ho03 round: '" + round + "' price " + price + ", barkeep's share " + share + ", till " + till0 + " -> " + till1 + "; drinks "
                + drinks0 + " -> " + drinks1 + ", bottles " + bottles0 + " -> " + bottles1 + "; books " + note(id, "tavern.takings") + "/" + note(id, "tavern.rounds"));
            helper.assertTrue(round.startsWith("You bought a round") && round.contains(barkeep.displayNameCap()), "poured by the barkeep: " + round);
            helper.assertTrue(price >= 4 && share == price / 4 && till1 - till0 == price - share,
                "the barkeep's share, the rest into the till: " + price + " / " + share + " / " + (till1 - till0));
            helper.assertTrue(drinks0 - drinks1 == price && bottles1 - bottles0 == price, "a drink a head out of the stores, every bottle back in them");
            helper.assertTrue(note(id, "tavern.rounds") == 1 && note(id, "tavern.takings") == price - share, "the tavern's own books");
            // A pint by its talk.
            int coins1 = Market.coinsHeld(pl);
            String pint = say(barkeep, pl, "A pint, please");
            int drinks2 = stock(level, id, Cafe::isDrink);
            helper.assertTrue(carried(pl, Cafe::isDrink) == 1 && Market.coinsHeld(pl) < coins1 && drinks2 == drinks1 - 1, "a pint poured: " + pint);
            // Empty bottles paid back: a coin for every three, out of the till, into the stores.
            pl.getInventory().add(new ItemStack(Items.GLASS_BOTTLE, 7));
            int coins2 = Market.coinsHeld(pl), till2 = Ledger.coins(id), bottles2 = stock(level, id, s -> s.is(Items.GLASS_BOTTLE));
            String back = say(barkeep, pl, "Here are my empty bottles");
            Kit.log("ho03 pint: '" + pint + "'; bottles back: '" + back + "'");
            helper.assertTrue(Market.coinsHeld(pl) - coins2 == 2 && till2 - Ledger.coins(id) == 2, "two coins for six bottles, out of the till: " + back);
            helper.assertTrue(stock(level, id, s -> s.is(Items.GLASS_BOTTLE)) - bottles2 == 6 && carried(pl, s -> s.is(Items.GLASS_BOTTLE)) == 1,
                "the six into the stores, the odd one kept");
            String elsewhere = say(folk.get(2), pl, "A pint, please");
            helper.assertTrue(elsewhere.contains(barkeep.displayNameCap()), "anybody else sends you to the barkeep: " + elsewhere);
            // Worst case: the casks dry.
            emptyStores(level, id);
            Cafe.Drink usual = Cafe.DRINKS.get(Math.floorMod(pl.getUUID().hashCode(), Cafe.DRINKS.size()));
            int missed0 = Stockroom.missedTodayForTests(level, id, Stockroom.Seller.TAVERN, Cafe.drink(usual)), coins3 = Market.coinsHeld(pl);
            String dry = say(barkeep, pl, "A pint, please");
            int missed1 = Stockroom.missedTodayForTests(level, id, Stockroom.Seller.TAVERN, Cafe.drink(usual));
            Kit.log("ho03 dry: '" + dry + "', missed " + missed0 + " -> " + missed1);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(Market.coinsHeld(pl) == coins3 && dry.contains("dry"), "nothing to pour, nothing paid: " + dry);
            helper.assertTrue(missed1 == missed0 + 1, "the tavern's books count the drink it had not got");
            helper.succeed();
        });
    }

    // ============================================================ ho04: a room for the night

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ho04_inn")
    public static void ho04_inn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2086000;
        StationTask[] trades = { StationTask.FARM, StationTask.COOK, StationTask.MINE };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 6000, trades);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        Kit.hold(level, x + 400, Z, 24);
        Kit.prepare(level, x + 400, Z, 24);
        VillageFolkEntity stranger = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 400, Z), 0.0F);
        helper.assertTrue(stranger != null && !id.equals(stranger.ownerId()), "a folk of another town");
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Villages.Village v = Villages.get(id);
            building(level, id, TownLook.INN, heart.offset(-20, 0, 20));
            VillageFolkEntity keeper = folk.get(1);
            Hospitality.appointForTests(level, v, keeper, Hospitality.Role.INNKEEPER);
            ServerPlayer pl = player(helper, keeper.blockPosition().east(), 10);
            String context = FolkTalk.talkContext(keeper, pl);
            int till0 = Ledger.coins(id), purse0 = keeper.purse();
            String said = say(keeper, pl, "A room for the night, please");
            BlockPos room = Inn.roomForTests(level, id, pl.getUUID());
            int till1 = Ledger.coins(id), purse1 = keeper.purse();
            Kit.log("ho04 '" + said + "' room " + room + "; coins " + Market.coinsHeld(pl) + "; till " + till0 + " -> " + till1 + "; keeper "
                + purse0 + " -> " + purse1 + "; context '" + context + "'");
            helper.assertTrue(context.contains("post=INNKEEPER") && context.contains(" inn"), "the keeper's talk knows its post: " + context);
            helper.assertTrue(room != null && Market.coinsHeld(pl) == 7, "a room for three coins: " + said);
            helper.assertTrue(purse1 - purse0 == 1 && till1 - till0 == 2 && note(id, "inn.share") == 1, "a coin in three the keeper's, the rest the till's");
            String other = say(folk.get(2), pl, "A room for the night, please");
            helper.assertTrue(other.contains(keeper.displayNameCap()), "anybody else names the keeper: " + other);
            // A traveller's room: the keeper's share too.
            stranger.moveTo(heart.getX() - 14.5, heart.getY(), heart.getZ() + 14.5, 0.0F, 0.0F);
            stranger.earn(10);
            BlockPos lodged = Inn.lodgeForTests(level, stranger);
            helper.assertTrue(lodged != null && keeper.purse() - purse1 == 1 && Ledger.coins(id) - till1 == 2,
                "the traveller's room shared the same way: " + lodged + ", " + (keeper.purse() - purse1) + "/" + (Ledger.coins(id) - till1));
            // Worst case: short of coin.
            ServerPlayer poor = player(helper, keeper.blockPosition().west(), 1);
            int purse2 = keeper.purse();
            String no = say(keeper, poor, "A room for the night, please");
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(Inn.roomForTests(level, id, poor.getUUID()) == null && keeper.purse() == purse2 && Market.coinsHeld(poor) == 1,
                "no coin, no room, nothing paid: " + no);
            helper.succeed();
        });
    }

    // ============================================================ ho05: the miller, and the butcher's block

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ho05_miller")
    public static void ho05_miller(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2089000;
        StationTask[] trades = { StationTask.FARM, StationTask.FARM, StationTask.RANCH };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 6000, trades);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Villages.Village v = Villages.get(id);
            emptyStores(level, id);
            BlockPos mill = building(level, id, TownLook.WINDMILL, heart.offset(38, 0, 0));
            Container box = millChest(level, mill);
            box.setItem(0, new ItemStack(Items.WHEAT, 64));
            box.setItem(1, new ItemStack(Items.WHEAT, 36));
            box.setChanged();
            VillageFolkEntity near = folk.get(1), far = folk.get(0), rancher = folk.get(2);
            near.setHome(mill.west(4));
            far.setHome(heart.offset(-30, 0, 0));
            VillageFolkEntity miller = Windmill.millerForTests(level, v);
            Offices.forgetForTests(id);
            ServerPlayer pl = player(helper, near.blockPosition().east(), 60);
            String context = FolkTalk.talkContext(near, pl);
            helper.assertTrue(miller == near && Windmill.isMiller(near) && !Windmill.isMiller(far), "the farmer living nearest the mill keeps it");
            helper.assertTrue(Offices.of(near).contains(Offices.Office.MILLER) && "miller".equals(Weave.role(near)) && context.contains(" miller"),
                "paid and dressed as the miller, and its talk knows it: " + Offices.of(near) + " / " + Weave.role(near) + " / " + context);
            int grain0 = Windmill.grainForTests(level, id), till0 = Ledger.coins(id), coins0 = Market.coinsHeld(pl);
            String sack = say(near, pl, "Could I buy a sack of wheat?");
            int grain1 = Windmill.grainForTests(level, id), spent = coins0 - Market.coinsHeld(pl);
            Kit.log("ho05 the miller's sack: '" + sack + "' mill " + grain0 + " -> " + grain1 + ", spent " + spent + "; till " + till0 + " -> " + Ledger.coins(id));
            helper.assertTrue(carried(pl, WHEAT) == 16 && grain0 - grain1 == 16, "a sack of the mill's own wheat: " + sack);
            helper.assertTrue(spent > 0 && Ledger.coins(id) - till0 == spent, "paid for, into the treasury");
            box.clearContent();
            box.setItem(0, new ItemStack(Items.WHEAT, 40));
            box.setChanged();
            String kept = say(near, pl, "Could I buy a sack of wheat?");
            helper.assertTrue(Windmill.grainForTests(level, id) == 40 && kept.contains("bakery"), "never the bakery's two sacks: " + kept);
            // The rancher's butcher's block: the herd's meat out of the stores; none, said so.
            // A full larder first (bread and cooked meat): the town sells its raw meat only past what it keeps to eat.
            chestAt(level, heart.offset(5, 0, -5), new ItemStack(Items.BEEF, 64), new ItemStack(Items.BEEF, 64), new ItemStack(Items.MUTTON, 64),
                new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.COOKED_BEEF, 64),
                new ItemStack(Items.COOKED_BEEF, 64));
            com.jrpetty.mcassistant.entity.Budget.forget(id);                    // the town's wants counted afresh
            Predicate<ItemStack> meat = s -> s.is(Items.BEEF) || s.is(Items.MUTTON);
            int meat0 = stock(level, id, meat), coins1 = Market.coinsHeld(pl);
            String bought = say(rancher, pl, "Could I buy some meat?");
            int got = carried(pl, meat), meat1 = stock(level, id, meat);
            emptyStores(level, id);
            int coins2 = Market.coinsHeld(pl);
            String none = say(rancher, pl, "Could I buy some meat?");
            Kit.log("ho05 meat: '" + bought + "' (" + meat0 + " -> " + meat1 + ", got " + got + "); none: '" + none + "'");
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(got > 0 && meat0 - meat1 == got && Market.coinsHeld(pl) < coins1, "meat from the block, out of the stores: " + bought);
            helper.assertTrue(Market.coinsHeld(pl) == coins2 && none.contains("can't spare"), "none to sell, said so: " + none);
            helper.succeed();
        });
    }

    // ============================================================ ho06: a baker's-hand shift

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ho06_shift")
    public static void ho06_shift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2092000;
        StationTask[] trades = { StationTask.FARM, StationTask.COOK };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, trades);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Ledger.addCoins(id, 500);
            emptyStores(level, id);
            chestAt(level, heart.offset(5, 0, -5), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.CRAFTING_TABLE));
            BlockPos bakery = building(level, id, TownLook.BAKERY, heart.offset(-12, 0, 10));
            ServerPlayer pl = player(helper, bakery, 0);
            String took = Shifts.take(pl, id, ShiftRole.BAKE.id, false);
            Shifts.secondForTests(level, pl);
            String task = Shifts.taskForTests(pl);
            BlockPos oven = HospitalityShifts.targetForTests(pl);
            int wheat0 = stock(level, id, WHEAT), bread0 = stock(level, id, BREAD);
            boolean in = oven != null && Shifts.useForTests(level, pl, oven);
            int wheat1 = stock(level, id, WHEAT);
            boolean early = oven != null && Shifts.useForTests(level, pl, oven);
            int bread1 = stock(level, id, BREAD);
            HospitalityShifts.readyForTests(pl, false);
            boolean out = oven != null && Shifts.useForTests(level, pl, oven);
            int bread2 = stock(level, id, BREAD);
            int[] st = Shifts.stateForTests(pl);
            Kit.log("ho06 took '" + took + "', task '" + task + "' at " + oven + "; in " + in + ", early " + early + ", out " + out + "; wheat " + wheat0
                + " -> " + wheat1 + ", bread " + bread0 + " -> " + bread1 + " -> " + bread2 + "; state " + java.util.Arrays.toString(st));
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Knead"), "a batch to bake: " + took + " / " + task);
            helper.assertTrue(in && wheat1 < wheat0 && early && bread1 == bread0, "in the oven, its makings out of the stores; not done at once");
            helper.assertTrue(out && bread2 > bread0 && wheat0 - wheat1 == 3 * (bread2 - bread0), "out into the stores, three wheat to a loaf");
            helper.assertTrue(st != null && st[0] >= 1 && st[1] + st[4] > 0, "the task done and paid (a part coin carried to the next): "
                + java.util.Arrays.toString(st));
            // Burnt: unpaid, and nothing out of the oven.
            Shifts.secondForTests(level, pl);
            BlockPos oven2 = HospitalityShifts.targetForTests(pl);
            boolean in2 = oven2 != null && Shifts.useForTests(level, pl, oven2);
            int bread3 = stock(level, id, BREAD);
            HospitalityShifts.readyForTests(pl, true);
            if (oven2 != null) Shifts.useForTests(level, pl, oven2);
            int[] st2 = Shifts.stateForTests(pl);
            Kit.log("ho06 burnt: in " + in2 + ", bread " + bread3 + " -> " + stock(level, id, BREAD) + "; state " + java.util.Arrays.toString(st2));
            Shifts.endForTests(level, pl);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(in2 && stock(level, id, BREAD) == bread3 && st2 != null && st2[3] >= 1 && st2[1] == st[1] && st2[4] == st[4],
                "a burnt batch: nothing out, unpaid");
            helper.succeed();
        });
    }

    // ============================================================ ho07: the stage, and the books

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ho07_stage")
    public static void ho07_stage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2095000;
        StationTask[] trades = { StationTask.FARM, StationTask.COOK };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 6000, trades);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            List<String> views = HospitalityStage.stage(level, heart.offset(0, 0, -6));
            List<VillageFolkEntity> staged = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(heart).inflate(48, 16, 48),
                f -> f.getTags().contains("folk_lineup"));
            String row = "";
            boolean barkeep = false, baker = false, waiter = false, cookInWhites = false, miller = false;
            for (VillageFolkEntity f : staged) {
                Hospitality.Role r = Hospitality.role(f);
                row += r + "=" + f.clientPostOutfit() + " ";
                if (r == Hospitality.Role.BARKEEP && f.clientPostOutfit() == OutfitIndex.BARKEEP) barkeep = true;
                if (r == Hospitality.Role.BAKER && f.clientPostOutfit() == OutfitIndex.BAKER) baker = true;
                if (r == Hospitality.Role.WAITER && f.clientPostOutfit() == OutfitIndex.WAITER) waiter = true;
                if (r == Hospitality.Role.LINE_COOK && f.clientPostOutfit() == 0) cookInWhites = true;
                if (Windmill.isMiller(f)) miller = true;
            }
            List<String> lines = HospitalityStage.lines(level, Villages.get(id));
            Kit.log("ho07 views " + views + "; staged " + row + "; books " + lines);
            for (VillageFolkEntity f : staged) f.discard();
            helper.assertTrue(views.size() >= 5 && String.join(" ", views).contains("VIEW tavern-inside"), "every view set out: " + views);
            helper.assertTrue(barkeep && baker && waiter && cookInWhites && miller, "the posts in their own clothes: " + row);
            helper.assertTrue(!lines.isEmpty() && lines.get(0).startsWith("HOSPITALITY") && String.join("\n", lines).contains("bakery:"),
                "/village hospitality reads the books: " + lines);
            helper.succeed();
        });
    }
}
