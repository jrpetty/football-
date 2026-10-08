package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.Envoys;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.TradeBook;
import com.jrpetty.mcassistant.entity.TradeDeals;
import com.jrpetty.mcassistant.entity.TradeTalks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [econ-trade] Trade between towns: two towns, one with stone to spare and short of food, the other the reverse.
 *
 * <ul>
 * <li><b>td01</b>: each town's trade book lists the right surplus and shortage (the stone town: stone over, food
 *     short; the farm town: food over, stone short), with what each holds, keeps and can spare.</li>
 * <li><b>td02</b>: the envoy and the other town's leader strike a deal, and each town gains by its own prices
 *     (reckoned again here from each town's own PriceIndex and needs, not taken from the talks).</li>
 * <li><b>td03</b>: a shrewd host leader gives less ground than a warm one, on the same table, and keeps more of
 *     what there is to gain; on a fixed table as well.</li>
 * <li><b>td04</b>: no deal where there is no gain for both (two stone towns; a table where the buyer values the
 *     goods below the seller), and the relation still moves a little.</li>
 * <li><b>td05</b>: a delivery carries the agreed goods, real items out of one town's stores and into the other's,
 *     and the coin is carried in the carrier's purse (the trip written down survives a restart), not put into
 *     the treasury from afar.</li>
 * <li><b>td06</b>: with bread coming in under a deal, the stone town's farm share leans down (never under two
 *     thirds) and its mine's up as its mountain land leans; when the bread stops, the farms swing back at once.</li>
 * <li><b>td07</b>: an envoy heard before the host's board: the village gathers, the rounds are said, and the deal
 *     is struck when the leader shakes on it.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownTradeGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 64000;
    private static final int APART = 300;

    // ------------------------------------------------------------------ the two towns

    /** Two towns, three hundred blocks apart, on good terms, with their stores as asked and coin to trade with. */
    private record Pair(Villages.Village a, Villages.Village b) {}

    private static Pair towns(GameTestHelper helper, ServerLevel level, int x) {
        Kit.reset(level);
        TradeDeals.resetForTests();
        level.setDayTime(24000L * 6 + 3000);
        Villages.Village[] out = new Villages.Village[2];
        for (int i = 0; i < 2; i++) {
            int cx = x + i * APART;
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
            BlockPos heart = Kit.surface(level, cx, Z);
            int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 5);
            out[i] = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
            helper.assertTrue(stood >= 4 && out[i] != null, "a town of five at " + cx + ": " + stood);
            for (AssistantEntity a : Villages.folkOf(out[i].id())) if (a instanceof VillageFolkEntity f) f.ensurePersona();
            emptyStores(level, out[i].id());
            Ledger.addCoins(out[i].id(), 200);
        }
        helper.assertTrue(!out[0].id().equals(out[1].id()), "two towns, not one");
        long day = level.getDayTime() / 24000L;
        Villages.chooseElder(out[0].id(), day);
        Villages.chooseElder(out[1].id(), day);
        Ledger.relate(out[0].id(), out[1].id(), 25);
        // Both leaders steady, unless a test says otherwise: the bargaining is the tempers', not the dice's.
        TradeTalks.temperForTests(out[0].id(), Envoys.Temper.STEADY);
        TradeTalks.temperForTests(out[1].id(), Envoys.Temper.STEADY);
        return new Pair(out[0], out[1]);
    }

    /** The stone town: two and a half thousand cobblestone, and ten loaves. */
    private static void stoneTown(ServerLevel level, Villages.Village v, int bread) {
        List<ItemStack> goods = new ArrayList<>();
        for (int i = 0; i < 39; i++) goods.add(new ItemStack(Items.COBBLESTONE, 64));
        goods.add(new ItemStack(Items.COBBLESTONE, 4));
        while (bread > 0) { goods.add(new ItemStack(Items.BREAD, Math.min(64, bread))); bread -= 64; }
        stock(level, v, goods);
    }

    /** The farm town: fifteen hundred loaves, and no stone at all. */
    private static void farmTown(ServerLevel level, Villages.Village v) {
        List<ItemStack> goods = new ArrayList<>();
        for (int i = 0; i < 23; i++) goods.add(new ItemStack(Items.BREAD, 64));
        goods.add(new ItemStack(Items.BREAD, 28));
        stock(level, v, goods);
    }

    /** Goods into chests at the town's heart, as many chests as it takes; the books read afresh. */
    private static void stock(ServerLevel level, Villages.Village v, List<ItemStack> goods) {
        int k = 0, n = 0;
        while (n < goods.size()) {
            BlockPos at = Kit.surface(level, v.centre().getX() + 4 + 2 * k, v.centre().getZ() + 5);
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, at);
            Container box = (Container) level.getBlockEntity(at);
            for (int i = 0; i < box.getContainerSize() && n < goods.size(); i++) box.setItem(i, goods.get(n++));
            box.setChanged();
            k++;
        }
        Villages.forgetStock();
        TradeBook.forget(v.id());
    }

    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
        TradeBook.forget(village);
    }

    private static int held(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    private static String book(ServerLevel level, UUID village) {
        StringBuilder sb = new StringBuilder(Villages.name(village)).append(": ");
        TradeBook.Book b = TradeBook.of(level, village);
        for (TradeBook.Ware w : new TradeBook.Ware[]{ TradeBook.Ware.FOOD, TradeBook.Ware.STONE }) sb.append(TradeBook.line(b.get(w))).append(" | ");
        return sb.toString();
    }

    private static String f(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------ td01 the books

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "td01_trade_books")
    public static void td01_trade_books(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 700000);
        stoneTown(level, p.a(), 10);
        farmTown(level, p.b());
        TradeBook.Book a = TradeBook.of(level, p.a().id()), b = TradeBook.of(level, p.b().id());
        Kit.log("td01 " + book(level, p.a().id()));
        Kit.log("td01 " + book(level, p.b().id()));
        Kit.log("td01 summaries: " + TradeBook.summary(a) + " / " + TradeBook.summary(b));
        TradeBook.Entry as = a.get(TradeBook.Ware.STONE), af = a.get(TradeBook.Ware.FOOD);
        TradeBook.Entry bs = b.get(TradeBook.Ware.STONE), bf = b.get(TradeBook.Ware.FOOD);
        helper.assertTrue(as.status() == TradeBook.Status.SURPLUS && as.spare() >= 64 && as.held() >= 2500,
            "the stone town has stone to spare: " + as);
        helper.assertTrue(af.status() == TradeBook.Status.SHORT && af.want() > 0, "and is short of food: " + af);
        helper.assertTrue(bf.status() == TradeBook.Status.SURPLUS && bf.spare() >= 8, "the farm town has food to spare: " + bf);
        helper.assertTrue(bs.status() == TradeBook.Status.SHORT && bs.want() >= 384, "and is short of stone: " + bs);
        helper.assertTrue(bs.worth() > as.worth() && af.worth() > bf.worth(),
            "each values what it is short of above what the other does: stone " + f(as.worth()) + " here, " + f(bs.worth())
                + " there; bread " + f(af.worth()) + " here, " + f(bf.worth()) + " there");
        helper.assertTrue(TradeBook.complementary(level, p.a().id(), p.b().id()), "each has something the other wants");
        helper.succeed();
    }

    // ------------------------------------------------------------------ td02 a deal good for both

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "td02_deal_good_for_both")
    public static void td02_deal_good_for_both(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 702000);
        stoneTown(level, p.a(), 10);
        farmTown(level, p.b());
        UUID a = p.a().id(), b = p.b().id();
        int before = Ledger.relation(a, b);
        TradeTalks.Talk k = TradeTalks.negotiate(level, a, b);
        Kit.log("td02 " + TradeTalks.story(k));
        helper.assertTrue(k.deal(), "a deal is struck: " + k.end() + " — " + k.why());
        TradeTalks.Table t = k.table();
        TradeTalks.Terms tm = k.settled();
        // Each town's gain reckoned again, by its own prices now.
        UUID seller = t.seller(), buyer = t.buyer();
        double sx = TradeBook.worth(level, seller, t.x()), bx = TradeBook.worth(level, buyer, t.x());
        double sy = t.y() == null ? 0 : TradeBook.worth(level, seller, t.y()), by = t.y() == null ? 0 : TradeBook.worth(level, buyer, t.y());
        double gs = tm.qy() * sy + tm.coin() - t.qx() * sx, gb = t.qx() * bx - tm.qy() * by - tm.coin();
        Kit.log("td02 terms: " + TradeTalks.terms(k) + "; the seller (" + Villages.name(seller) + ") gives " + t.qx() + " worth " + f(t.qx() * sx)
            + " to it and gets " + f(tm.qy() * sy + tm.coin()) + " → " + f(gs) + "; the buyer gets " + f(t.qx() * bx) + " and gives "
            + f(tm.qy() * by + tm.coin()) + " → " + f(gb) + "; room " + f(k.lo()) + " to " + f(k.hi()) + ", settled " + f(k.price())
            + " after " + (k.offers().size() / 2 - 1) + " rounds");
        helper.assertTrue(seller.equals(a) && t.x() == Items.COBBLESTONE, "the stone town's envoy sells its stone: " + t);
        helper.assertTrue(t.y() == Items.BREAD && tm.qy() > 0, "for the farm town's bread: " + tm);
        helper.assertTrue(gs > 0 && gb > 0, "both gain by their own prices: " + f(gs) + " and " + f(gb));
        helper.assertTrue(k.price() >= k.lo() && k.price() <= k.hi(), "settled inside the room to agree");
        helper.assertTrue(k.offers().size() >= 4, "they bargained in rounds: " + k.offers().size() + " offers");
        TradeDeals.strikeForTests(level, k);
        Kit.log("td02 struck: " + TradeDeals.words(a, b) + "; pact " + Envoys.pact(a, b) + "; relation " + before + " -> " + Ledger.relation(a, b));
        helper.assertTrue(TradeDeals.live(a, b) && Envoys.pact(a, b), "the deal stands in both books, and the pact with it");
        helper.assertTrue(Ledger.relation(a, b) > before, "and the two towns think the better of each other for it");
        helper.succeed();
    }

    // ------------------------------------------------------------------ td03 shrewd and warm

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "td03_shrewd_gives_less")
    public static void td03_shrewd_gives_less(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 704000);
        stoneTown(level, p.a(), 10);
        farmTown(level, p.b());
        UUID a = p.a().id(), b = p.b().id();
        TradeTalks.temperForTests(b, Envoys.Temper.SHREWD);
        TradeTalks.Talk hard = TradeTalks.negotiate(level, a, b);
        TradeTalks.temperForTests(b, Envoys.Temper.WARM);
        TradeTalks.Talk soft = TradeTalks.negotiate(level, a, b);
        Kit.log("td03 shrewd host: " + TradeTalks.story(hard) + " — gave " + f(hard.hostGave()) + " of the room, gains " + f(hard.gainHost()));
        Kit.log("td03 warm host: " + TradeTalks.story(soft) + " — gave " + f(soft.hostGave()) + " of the room, gains " + f(soft.gainHost()));
        helper.assertTrue(hard.deal() && soft.deal(), "both strike a deal: " + hard.end() + ", " + soft.end());
        helper.assertTrue(hard.hostGave() < soft.hostGave(), "the shrewd leader gives less ground: " + f(hard.hostGave()) + " < " + f(soft.hostGave()));
        helper.assertTrue(hard.gainHost() > soft.gainHost(), "and keeps more of the gain: " + f(hard.gainHost()) + " > " + f(soft.gainHost()));
        // On a fixed table too, whoever sits at it.
        TradeTalks.Table t = new TradeTalks.Table(a, b, Items.COBBLESTONE, 128, Items.BREAD, 80, 0.015, 0.16, 0.77, 0.11, 60);
        TradeTalks.Manner seller = TradeTalks.manner(Envoys.Temper.STEADY, 25, 0);
        TradeTalks.Talk s2 = TradeTalks.bargain(t, a, b, seller, TradeTalks.manner(Envoys.Temper.SHREWD, 25, 0), 3, false);
        TradeTalks.Talk w2 = TradeTalks.bargain(t, a, b, seller, TradeTalks.manner(Envoys.Temper.WARM, 25, 0), 3, false);
        Kit.log("td03 fixed table: shrewd buyer settles " + f(s2.price()) + " (" + TradeTalks.paid(t, s2.settled()) + ", gave " + f(s2.buyerGave())
            + "); warm buyer settles " + f(w2.price()) + " (" + TradeTalks.paid(t, w2.settled()) + ", gave " + f(w2.buyerGave()) + ")");
        helper.assertTrue(s2.deal() && w2.deal() && s2.buyerGave() < w2.buyerGave() && s2.price() < w2.price(),
            "on a fixed table the shrewd buyer gives less and pays less");
        helper.succeed();
    }

    // ------------------------------------------------------------------ td04 no gain, no deal

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "td04_no_gain_no_deal")
    public static void td04_no_gain_no_deal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 706000);
        stoneTown(level, p.a(), 10);
        stoneTown(level, p.b(), 10);
        UUID a = p.a().id(), b = p.b().id();
        int before = Ledger.relation(a, b);
        TradeTalks.Talk k = TradeTalks.negotiate(level, a, b);
        Kit.log("td04 two stone towns: " + TradeTalks.story(k) + " (" + k.end() + ")");
        helper.assertTrue(!k.deal(), "two towns with the same goods and the same wants strike no deal: " + k.end());
        TradeDeals.strikeForTests(level, k);                          // (no deal: written down, nothing struck)
        int after = Ledger.relation(a, b);
        Kit.log("td04 relation " + before + " -> " + after + "; live " + TradeDeals.live(a, b));
        helper.assertTrue(!TradeDeals.live(a, b), "nothing stands between them");
        helper.assertTrue(after != before && Math.abs(after - before) <= 3, "but the talk moves the relation a little: " + before + " -> " + after);
        // A table where the buyer values the stone below what the seller does: no room to agree.
        TradeTalks.Table t = new TradeTalks.Table(a, b, Items.COBBLESTONE, 128, null, 0, 0.10, 0.05, 0, 0, 50);
        TradeTalks.Manner m = TradeTalks.manner(Envoys.Temper.GENEROUS, 80, 1);
        TradeTalks.Talk none = TradeTalks.bargain(t, a, b, m, m, 3, false);
        Kit.log("td04 no room: " + none.end() + " — " + none.why() + "; room " + f(none.lo()) + " to " + f(none.hi()));
        helper.assertTrue(none.end() == TradeTalks.End.NO_ROOM && !none.deal(), "however generous both leaders, no gain for both means no deal");
        helper.succeed();
    }

    // ------------------------------------------------------------------ td05 a delivery

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "td05_delivery_carried")
    public static void td05_delivery_carried(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 708000);
        // The stone town nearly fed: it wants only a little bread each time, so the rest is paid in coin.
        stoneTown(level, p.a(), 250);
        farmTown(level, p.b());
        UUID a = p.a().id(), b = p.b().id();
        TradeBook.ratesForTests(a, TradeBook.Ware.FOOD, 20, 22);
        TradeTalks.Talk k = TradeTalks.negotiate(level, a, b);
        Kit.log("td05 " + TradeTalks.story(k));
        helper.assertTrue(k.deal(), "a deal: " + k.end() + " — " + k.why());
        TradeDeals.strikeForTests(level, k);
        int[] n = TradeDeals.numbersForTests(a, b);
        Item[] goods = TradeDeals.goodsForTests(a, b);
        int qx = n[0], qy = n[1], coin = n[2];
        Kit.log("td05 the deal: " + TradeDeals.words(a, b) + " (qx " + qx + ", qy " + qy + ", coin " + coin + ")");
        helper.assertTrue(goods[0] == Items.COBBLESTONE && qx > 0 && coin > 0, "stone for coin (and bread): " + qx + " " + qy + " " + coin);
        int aStone = held(level, a, Items.COBBLESTONE), bStone = held(level, b, Items.COBBLESTONE);
        int bBread = held(level, b, Items.BREAD);
        VillageFolkEntity carrier = TradeDeals.sendNowForTests(level, a, b);
        helper.assertTrue(carrier != null, "a caravan sets out");
        int aBread = held(level, a, Items.BREAD);                     // (after the carrier's road loaves went with it)
        int carried = carrier.countCarried(s -> s.is(Items.COBBLESTONE));
        int aCoins = Ledger.coins(a), bCoins = Ledger.coins(b);
        int aStoneOut = held(level, a, Items.COBBLESTONE);
        Kit.log("td05 set out: " + carrier.displayNameCap() + " carrying " + carried + " cobblestone; the stone town's stone " + aStone + " -> "
            + aStoneOut + "; purse " + java.util.Arrays.toString(TradeDeals.purseForTests(carrier)));
        helper.assertTrue(carried >= qx && aStone - aStoneOut == qx, "the agreed stone, real items out of the town's own stores: " + (aStone - aStoneOut));
        // At the farm town.
        Caravans.arriveForTests(level, carrier);
        int[] purse = TradeDeals.purseForTests(carrier);
        int bStoneIn = held(level, b, Items.COBBLESTONE), bBreadOut = held(level, b, Items.BREAD);
        int breadOn = carrier.countCarried(s -> s.is(Items.BREAD));
        Kit.log("td05 at " + Villages.name(b) + ": its stone " + bStone + " -> " + bStoneIn + ", its bread " + bBread + " -> " + bBreadOut
            + ", its treasury " + bCoins + " -> " + Ledger.coins(b) + "; the carrier's purse " + java.util.Arrays.toString(purse)
            + ", bread on its back " + breadOn + "; the stone town's treasury " + aCoins + " -> " + Ledger.coins(a));
        helper.assertTrue(bStoneIn - bStone == qx, "the farm town's stores take the agreed stone: " + (bStoneIn - bStone));
        helper.assertTrue(bBread - bBreadOut == qy, "and give the agreed bread: " + (bBread - bBreadOut));
        helper.assertTrue(bCoins - Ledger.coins(b) == coin && purse != null && purse[0] == coin,
            "the farm town pays the coin into the carrier's purse: " + (bCoins - Ledger.coins(b)) + ", purse " + (purse == null ? -1 : purse[0]));
        helper.assertTrue(Ledger.coins(a) == aCoins, "and the stone town's treasury has none of it yet: the coin is on the road");
        // A restart on the road: the trip written down is taken up again, coin and all.
        boolean back = TradeDeals.restartTripForTests(level, carrier);
        int[] after = TradeDeals.purseForTests(carrier);
        Kit.log("td05 after a restart: trip back " + back + ", homeward " + (carrier.trip() != null && carrier.trip().homeward())
            + ", purse " + java.util.Arrays.toString(after));
        helper.assertTrue(back && carrier.trip().homeward() && after != null && after[0] == coin, "the trip outlasts a restart, coin and all");
        // Home.
        Caravans.arriveForTests(level, carrier);
        int[] done = TradeDeals.numbersForTests(a, b);
        Kit.log("td05 home: the stone town's treasury " + aCoins + " -> " + Ledger.coins(a) + ", its bread " + aBread + " -> "
            + held(level, a, Items.BREAD) + "; deliveries " + done[3] + " of " + done[4] + "; gains " + java.util.Arrays.toString(TradeDeals.gainsForTests(a, b)));
        helper.assertTrue(carrier.trip() == null, "the carrier is home");
        helper.assertTrue(Ledger.coins(a) - aCoins == coin, "the coin came home in the purse: " + (Ledger.coins(a) - aCoins));
        helper.assertTrue(held(level, a, Items.BREAD) - aBread >= qy, "and the bread into the stone town's stores");
        helper.assertTrue(done[3] == 1 && done[4] == 1 && done[5] == 0 && done[6] == 0, "one delivery due, one made, nobody short");
        double[] g = TradeDeals.gainsForTests(a, b);
        helper.assertTrue(g[0] > 0 && g[1] > 0, "and both towns the better for it by their own prices: " + f(g[0]) + ", " + f(g[1]));
        helper.succeed();
    }

    // ------------------------------------------------------------------ td06 specialising

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "td06_specialise_and_back")
    public static void td06_specialise_and_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 710000);
        stoneTown(level, p.a(), 10);
        farmTown(level, p.b());
        UUID a = p.a().id(), b = p.b().id();
        Homeland.setForTests(a, Homeland.Land.MOUNTAIN);
        Homeland.setForTests(b, Homeland.Land.RIVER);
        TradeBook.ratesForTests(a, TradeBook.Ware.FOOD, 2, 24);
        TradeBook.ratesForTests(a, TradeBook.Ware.STONE, 10, 5);
        TradeBook.ratesForTests(b, TradeBook.Ware.FOOD, 60, 30);
        TradeTalks.Talk k = TradeTalks.negotiate(level, a, b);
        helper.assertTrue(k.deal() && k.table().y() == Items.BREAD, "a deal, stone for bread: " + k.end() + " " + k.why());
        TradeDeals.strikeForTests(level, k);
        long day = level.getDayTime() / 24000L;
        // Fed steadily by the deal, by the leader's books.
        Leader.booksForTests(a, new Leader.Books(300, 26, 24, 26.0, 24.0, 12.0, Leader.Plan.STEADY, day));
        Leader.booksForTests(b, new Leader.Books(1500, 60, 30, 60.0, 30.0, 50.0, Leader.Plan.PLENTY, day));
        double farmShare0 = Villages.share(a, StationTask.FARM);
        TradeDeals.leanForTests(level, p.a());
        double first = TradeDeals.lean(a, StationTask.FARM);
        Kit.log("td06 before any bread came: farm lean " + f(first));
        helper.assertTrue(first == 1.0, "no leaning on a deal that has delivered nothing yet");
        TradeDeals.deliveredForTests(a, b, 100, 100);
        TradeDeals.deliveredForTests(a, b, 100, 100);
        List<String> trail = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TradeDeals.leanForTests(level, p.a());
            TradeDeals.leanForTests(level, p.b());
            trail.add(f(TradeDeals.lean(a, StationTask.FARM)) + "/" + f(TradeDeals.lean(a, StationTask.MINE)));
        }
        double farm = TradeDeals.lean(a, StationTask.FARM), mine = TradeDeals.lean(a, StationTask.MINE);
        double farmShare1 = Villages.share(a, StationTask.FARM);
        Kit.log("td06 the stone town, morning by morning (farm/mine): " + String.join(", ", trail) + "; the farm town farm "
            + f(TradeDeals.lean(b, StationTask.FARM)) + ", mine " + f(TradeDeals.lean(b, StationTask.MINE))
            + "; the stone town's farmers over (+) or under their share " + f(farmShare0) + " -> " + f(farmShare1));
        helper.assertTrue(farm < 1.0 && farm >= 0.65, "with bread coming in, the stone town's farm share drops, within limits: " + f(farm));
        helper.assertTrue(farm >= 1.0 - 5 * 0.05 - 1e-6, "a step a morning, not all at once: " + f(farm));
        helper.assertTrue(mine > 1.0 && mine <= 1.4, "and its mine's rises, as its mountain land leans: " + f(mine));
        helper.assertTrue(TradeDeals.lean(b, StationTask.FARM) > 1.0, "the farm town farms more for the bread it promised: " + f(TradeDeals.lean(b, StationTask.FARM)));
        helper.assertTrue(farmShare1 > farmShare0, "fewer farmers wanted in the stone town: " + f(farmShare0) + " -> " + f(farmShare1));
        // The bread stops coming.
        for (int i = 0; i < 3; i++) TradeDeals.deliveredForTests(a, b, 100, 0);
        TradeDeals.leanForTests(level, p.a());
        double back = TradeDeals.lean(a, StationTask.FARM);
        Kit.log("td06 the bread stopped: farm lean " + f(back) + "; farmers' share " + f(Villages.share(a, StationTask.FARM)));
        helper.assertTrue(back == 1.0, "and swings straight back when the deliveries stop: " + f(back));
        // And never on short commons, deliveries or not.
        TradeDeals.deliveredForTests(a, b, 100, 100);
        TradeDeals.deliveredForTests(a, b, 100, 100);
        TradeDeals.deliveredForTests(a, b, 100, 100);
        Leader.booksForTests(a, new Leader.Books(40, 5, 24, 5.0, 24.0, 1.6, Leader.Plan.SHORT, day));
        TradeDeals.leanForTests(level, p.a());
        Kit.log("td06 on short commons: farm lean " + f(TradeDeals.lean(a, StationTask.FARM)));
        helper.assertTrue(TradeDeals.lean(a, StationTask.FARM) >= 1.0, "a town on short commons never cuts its farmers for a deal");
        helper.succeed();
    }

    // ------------------------------------------------------------------ td07 the audience

    @GameTest(template = EMPTY, timeoutTicks = 4200, batch = "td07_audience_bargain")
    public static void td07_audience_bargain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pair p = towns(helper, level, 712000);
        stoneTown(level, p.a(), 10);
        farmTown(level, p.b());
        UUID a = p.a().id(), b = p.b().id();
        final VillageFolkEntity[] envoy = { null };
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            boolean sent = Envoys.send(level, p.a(), p.b(), Envoys.Errand.TRADE, day);
            for (AssistantEntity x : Villages.folkOf(a)) {
                if (x instanceof VillageFolkEntity f && f.trip() != null && f.trip().errand() == Envoys.Errand.TRADE) envoy[0] = f;
            }
            helper.assertTrue(sent && envoy[0] != null, "the stone town's envoy sets out");
            Caravans.arriveForTests(level, envoy[0]);
            BlockPos at = p.b().centre().east(3);
            envoy[0].moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        });
        for (int t = 60; t <= 3700; t += 20) helper.runAtTickTime(t, () -> Assemblies.tick(level, p.b()));
        List<String> trail = new ArrayList<>();
        for (int t = 100; t <= 3800; t += 200) {
            final int at = t;
            helper.runAtTickTime(t, () -> trail.add(at + ": " + Assemblies.debug(b)));
        }
        helper.runAtTickTime(3900, () -> {
            Kit.log("td07 the audience: " + String.join(" | ", trail));
            List<String> page = TradeDeals.page(level, p.b());
            Kit.log("td07 the host's trade page: " + String.join(" / ", page));
            helper.assertTrue(trail.stream().anyMatch(x -> x.contains("ENVOY")), "the host town gathers before its board to hear the envoy");
            helper.assertTrue(TradeDeals.live(a, b), "and the deal is struck when the leader shakes on it: " + TradeDeals.words(a, b));
            helper.assertTrue(page.stream().anyMatch(x -> x.contains("settled at")), "every round written down, and how it settled");
            helper.assertTrue(envoy[0].trip() != null && envoy[0].trip().homeward(), "and the envoy turns for home with the deal");
            helper.succeed();
        });
    }
}
