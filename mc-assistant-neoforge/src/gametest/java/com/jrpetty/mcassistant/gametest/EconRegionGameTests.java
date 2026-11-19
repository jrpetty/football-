package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Accounts;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CapitalBook;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.CycleModel;
import com.jrpetty.mcassistant.entity.EconCycle;
import com.jrpetty.mcassistant.entity.EconIndex;
import com.jrpetty.mcassistant.entity.EconRegion;
import com.jrpetty.mcassistant.entity.EconReport;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.FolkCredit;
import com.jrpetty.mcassistant.entity.LabourMarket;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.TownBudget;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
 * [econ4] Towns trading on the market, the price index and the trade cycle: two linked towns' price gap narrowed by their
 * merchants against a pair that does not trade, every coin booked both sides (the census exact in each town); the index,
 * inflation and the money supply taken; a boom and a slump emerging in a long harness, the council reacting and the town
 * recovering; a grown town's treasury kept off nought for thirty days; the whole kept with the world.
 *
 * <p>Each on its own ground, x 4100000 to 4199999 (each test 6000 apart; a test's own towns 600 apart, its control pair
 * 3000 off), z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EconRegionGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ the ground

    /** A town at x of so many folk at these trades, every purse at {@code purse}; its treasury so many coins; its stores
     *  chests holding these; the market open. Kit.reset is the caller's. */
    private static UUID town(GameTestHelper helper, ServerLevel level, int x, int purse, int treasury, StationTask[] trades, ItemStack... goods) {
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        UUID id = null;
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 6 + (i % 6) * 2, Z - 4 + (i / 6) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised at " + x);
            f.ensurePersona();
            f.setJob(trades[i]);
            f.keepTradeForTests();
            f.spend(f.purse());
            f.earn(purse);
            id = f.ownerId();
        }
        helper.assertTrue(id != null, "a town at " + x);
        // Two chests of stores: the goods, and room.
        for (int k = 0; k < 2; k++) {
            BlockPos at = Kit.surface(level, x + 6 + k, Z + 6);
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            ZoneChests.mark(level, at);
            Container c = (Container) level.getBlockEntity(at);
            if (k == 0) for (int i = 0; i < goods.length && i < c.getContainerSize(); i++) c.setItem(i, goods[i].copy());
            c.setChanged();
        }
        Villages.forgetStores(id);
        Ledger.addCoins(id, treasury);
        MarketMaker.openForTests(level, Villages.get(id));
        return id;
    }

    private static ItemStack[] lots(Item it, int stacks) {
        ItemStack[] out = new ItemStack[stacks];
        for (int i = 0; i < stacks; i++) out[i] = new ItemStack(it, it.getDefaultMaxStackSize());
        return out;
    }

    private static StationTask[] trades(int n, StationTask... first) {
        StationTask[] out = new StationTask[n];
        for (int i = 0; i < n; i++) out[i] = i < first.length ? first[i] : StationTask.FARM;
        return out;
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private static int stock(ServerLevel level, UUID id, Item it) {
        return Market.stock(level, id, s -> s.is(it));
    }

    // ============================================================ er01 the merchants narrow the gap

    /**
     * Two linked towns six hundred blocks apart, the one with six stacks of wheat cheap (half its usual worth), the other
     * none and dear (twice it); a pair just like them three thousand blocks off that does not trade. Eight days: each morning
     * both towns' prices reckoned from their stocks and their sheets written; the dear town's market buys the cheap town's
     * wheat as its traders know it (a day late), carriage and toll paid, and the loads come in. The traded pair's gap
     * narrows well past the other pair's; wheat moved out of the cheap town and into the dear; every coin booked both sides
     * (each town's coin moving by exactly its booked IMPORT or EXPORT, the two together by nought); their relations warmer;
     * the trade book has the wheat in and out, by ware and by partner; a load lost on the road is refunded, booked both sides.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "er01_arbitrage")
    public static void er01_arbitrage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4100000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        EconRegion.liveForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        UUID a = town(helper, level, x, 10, 600, trades(4), lots(Items.WHEAT, 6));
        UUID b = town(helper, level, x + 600, 10, 600, trades(4), lots(Items.COBBLESTONE, 1));
        UUID c = town(helper, level, x + 3000, 10, 600, trades(4), lots(Items.WHEAT, 6));
        UUID d = town(helper, level, x + 3600, 10, 600, trades(4), lots(Items.COBBLESTONE, 1));
        ItemStack wheat = new ItemStack(Items.WHEAT);
        PriceIndex.setForTests(a, wheat, 0.5);
        PriceIndex.setForTests(c, wheat, 0.5);
        PriceIndex.setForTests(b, wheat, 2.0);
        PriceIndex.setForTests(d, wheat, 2.0);
        Kit.Expect ok = new Kit.Expect();
        ok.that(EconRegion.partners(b).stream().anyMatch(v -> v.id().equals(a)), "the dear town trades with the cheap one: " + EconRegion.partners(b).size());
        ok.that(EconRegion.partners(b).stream().noneMatch(v -> v.id().equals(c) || v.id().equals(d)), "and not with the far pair");
        int rel0 = Ledger.relation(a, b);
        long day0 = day(level);
        UUID[] all = { a, b, c, d };
        for (UUID id : all) EconRegion.publish(level, id, day0);
        double gap0 = MarketMaker.mid(level, b, wheat) / MarketMaker.mid(level, a, wheat);
        double ctl0 = MarketMaker.mid(level, d, wheat) / MarketMaker.mid(level, c, wheat);
        int a0 = stock(level, a, Items.WHEAT), deals = 0, moved = 0;
        double gap = gap0, ctl = ctl0;
        for (int k = 1; k <= 8; k++) {
            level.setDayTime((day0 + k) * 24000L + 7000L);
            long today = day(level);
            for (UUID id : all) PriceIndex.reckonNow(level, Villages.get(id));
            for (UUID id : all) EconRegion.publish(level, id, today);
            long ca = CoinCensus.count(a), cb = CoinCensus.count(b), fa = CoinCensus.netFlowPence(a), fb = CoinCensus.netFlowPence(b);
            List<EconRegion.Deal> done = new ArrayList<>(EconRegion.merchantForTests(level, Villages.get(b)));
            done.addAll(EconRegion.merchantForTests(level, Villages.get(a)));
            int in = EconRegion.deliverAllForTests(level);
            long da = CoinCensus.count(a) - ca, db = CoinCensus.count(b) - cb, ba = CoinCensus.netFlowPence(a) - fa, bb = CoinCensus.netFlowPence(b) - fb;
            ok.that(da == ba && db == bb, "day " + k + ": each town's coin moved by what it booked: " + da + "=" + ba + ", " + db + "=" + bb);
            ok.that(da + db == 0, "day " + k + ": the two towns' coin together the same: " + da + " + " + db);
            for (EconRegion.Deal dl : done) if (dl.item() == Items.WHEAT) { deals++; moved += dl.n(); }
            gap = MarketMaker.mid(level, b, wheat) / MarketMaker.mid(level, a, wheat);
            ctl = MarketMaker.mid(level, d, wheat) / MarketMaker.mid(level, c, wheat);
            Kit.log(String.format(Locale.ROOT, "[VT] er01 day %d: %d deals (%s), %d loads in; wheat cheap town %d, dear town %d; gap %.2f (control %.2f); coin %+d / %+d",
                k, done.size(), done, in, stock(level, a, Items.WHEAT), stock(level, b, Items.WHEAT), gap, ctl, da, db));
        }
        ok.that(deals > 0 && moved > 0, "the merchants bought wheat: " + deals + " deals, " + moved + " wheat");
        ok.that(stock(level, a, Items.WHEAT) == a0 - moved && stock(level, b, Items.WHEAT) == moved, "the wheat went from the one town's stores to the other's: "
            + stock(level, a, Items.WHEAT) + " / " + stock(level, b, Items.WHEAT));
        ok.that(gap < gap0 && gap < ctl * 0.85, String.format(Locale.ROOT, "the traded gap narrowed past the untraded: %.2f -> %.2f against %.2f -> %.2f", gap0, gap, ctl0, ctl));
        ok.that(Ledger.relation(a, b) > rel0, "good trade warmed their relations: " + rel0 + " -> " + Ledger.relation(a, b));
        long[] tb = EconRegion.allForTests(b), ta = EconRegion.allForTests(a);
        ok.that(tb[0] > 0 && ta[1] > 0, "the books: bought in " + tb[0] + " by the dear town, sold out " + ta[1] + " by the cheap one");
        ok.that(EconRegion.week(b, day(level), "in", "Goods").keySet().stream().anyMatch(s -> s.contains("wheat"))
            && EconRegion.week(b, day(level), "in", "Partners").containsKey(Villages.name(a)), "by ware and by partner: " + EconRegion.week(b, day(level), "in", "Partners"));
        // A load lost on the road: refunded, booked both sides.
        Market.intoStoresForTests(level, a, new ItemStack(Items.WHEAT, 64));
        Market.intoStoresForTests(level, a, new ItemStack(Items.WHEAT, 64));
        Market.intoStoresForTests(level, a, new ItemStack(Items.WHEAT, 64));
        PriceIndex.setForTests(b, wheat, 3.0);
        PriceIndex.setForTests(a, wheat, 0.5);
        level.setDayTime(level.getDayTime() + 24000L);
        EconRegion.publish(level, a, day(level) - 1);
        long ca = CoinCensus.count(a), cb = CoinCensus.count(b), fa = CoinCensus.netFlowPence(a), fb = CoinCensus.netFlowPence(b);
        int wheatA = stock(level, a, Items.WHEAT);
        List<EconRegion.Deal> more = EconRegion.merchantForTests(level, Villages.get(b));
        int sent = stock(level, a, Items.WHEAT);
        for (com.jrpetty.mcassistant.entity.Shipments.Shipment s : com.jrpetty.mcassistant.entity.Shipments.inTransit(a)) {
            if (s.contract().startsWith(EconRegion.CONTRACT)) com.jrpetty.mcassistant.entity.Shipments.lost(level, s.id(), s.manifest(), "a river in flood");
        }
        long da = CoinCensus.count(a) - ca, db = CoinCensus.count(b) - cb;
        ok.that(!more.isEmpty() && da == CoinCensus.netFlowPence(a) - fa && db == CoinCensus.netFlowPence(b) - fb && da == 0 && db == 0,
            "a load lost: " + more.size() + " deals, its price paid back, every coin booked: " + da + " / " + db);
        ok.that(sent < wheatA && stock(level, a, Items.WHEAT) == wheatA, "and its wheat back in the seller's stores: " + wheatA + " -> " + sent + " -> " + stock(level, a, Items.WHEAT));
        Kit.log("[VT] er01 " + ok.summary() + "; " + String.join(" | ", EconRegion.lines(level, b)));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ er02 the index

    /**
     * The price index: a hundred the first morning; bread and roots dearer and the index up, by the basket's weights;
     * inflation reckoned against the first morning; the money supply every coin in the town less the club's chest plus its
     * savers' deposits; the dashboard's tag (charts, phase, lines) and the command's words.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er02_index")
    public static void er02_index(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4106000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        EconCycle.liveForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        UUID id = town(helper, level, x, 20, 400, trades(6), lots(Items.BREAD, 2));
        Villages.Village v = Villages.get(id);
        long day0 = day(level);
        Kit.Expect ok = new Kit.Expect();
        ok.that(EconIndex.record(level, v, day0), "the first morning's figures taken");
        ok.that(!EconIndex.record(level, v, day0), "once a day");
        EconCycle.stepForTests(level, v, day0);
        ok.that(EconIndex.cpi(id) == 10000, "a hundred the first morning: " + EconIndex.cpi(id));
        double basket0 = EconIndex.basket(level, id);
        for (Item it : new Item[]{ Items.BREAD, Items.POTATO, Items.CARROT }) PriceIndex.setForTests(id, new ItemStack(it), 1.5 * PriceIndex.factor(id, new ItemStack(it)));
        double basket1 = EconIndex.basket(level, id);
        level.setDayTime((day0 + 1) * 24000L + 7000L);
        EconIndex.record(level, v, day0 + 1);
        EconCycle.stepForTests(level, v, day0 + 1);
        long expect = Math.round(10000 * basket1 / basket0);
        ok.that(EconIndex.cpi(id) > 10000 && Math.abs(EconIndex.cpi(id) - expect) <= 1, "bread and roots dearer, the index up by the basket: " + EconIndex.cpi(id) + " (" + expect + ")");
        ok.that(Math.abs(EconIndex.inflationWeek(id) - (expect / 10000.0 - 1)) < 0.001, "inflation: " + EconIndex.inflationWeek(id));
        long money = (CoinCensus.count(id) - FolkCredit.clubCash(id) + FolkCredit.deposits(id)) / 100;
        ok.that(Math.abs(EconIndex.last(id, "money", -1) - money) <= 1, "the money supply: " + EconIndex.last(id, "money", -1) + " (" + money + ")");
        ok.that(EconIndex.last(id, "pop", 0) == Villages.headcount(id) && EconIndex.series(id, "cpi").length == 2, "population and two mornings kept");
        CompoundTag t = EconReport.report(level, id);
        ok.that(t.getBoolean("live") && t.getList("charts", 10).size() == 12 && !t.getString("phase").isEmpty(), "the dashboard: " + t.getString("times"));
        List<String> said = Kit.command(level, "village economy");
        ok.that(String.join(" ", said).contains("Price index"), "the command: " + said);
        Kit.log("[VT] er02 " + ok.summary() + "; " + String.join(" | ", EconReport.words(level, id, true)));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ er03 a boom, a slump and the recovery

    /**
     * A town's cycle over a hundred and twenty mornings (the harness steps its days): a boom, then slowing, a slump and the
     * recovery emerge in that order, twice over, out of employment, wages and debt moving one another (nothing rolled);
     * the council reacts (its rate raised in the boom and cut in the slump, the lenders dearer in the boom than the slump;
     * its works borrowing more in the slump; in a slump with the treasury sound it moves to ease a tax; a folk wants less
     * nerve to found a firm in a boom); the chronicle has the turns; everything bounded and the town recovering.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "er03_cycle")
    public static void er03_cycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4112000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        EconCycle.liveForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        UUID id = town(helper, level, x, 20, 0, trades(8, StationTask.GUARD, StationTask.GUARD), lots(Items.BREAD, 2));
        Villages.Village v = Villages.get(id);
        int bill = Math.max(1, Market.wageBill(id));
        Ledger.addCoins(id, Math.max(0, bill * 8 - Ledger.coins(id)));      // over five days' wages, under a fortnight's
        TaxBook.setForTests(id, TaxBook.Lever.HEAD, 2);
        long day0 = day(level);
        List<CycleModel.Phase> runs = new ArrayList<>();
        long boomBp = 0, slumpBp = 0;
        int nb = 0, ns = 0;
        String easeMotion = "", boomNerve = "", slumpSpend = "";
        double lowEmp = 1;
        for (int k = 0; k < 120; k++) {
            long today = day0 + k;
            level.setDayTime(today * 24000L + 7000L);
            EconIndex.record(level, v, today);
            EconCycle.stepForTests(level, v, today);
            CycleModel.Phase p = EconCycle.phase(id);
            if (runs.isEmpty() || runs.get(runs.size() - 1) != p) runs.add(p);
            lowEmp = Math.min(lowEmp, EconCycle.stateForTests(id)[0]);
            if (p == CycleModel.Phase.BOOM) { boomBp += FolkCredit.rateBp(id); nb++; if (boomNerve.isEmpty()) boomNerve = "nerve " + EconCycle.nerveShift(id); }
            if (p == CycleModel.Phase.SLUMP) {
                slumpBp += FolkCredit.rateBp(id); ns++;
                if (slumpSpend.isEmpty()) slumpSpend = "spend " + EconCycle.spendFactor(id) + ", limit " + TownBudget.limit(id);
                if (easeMotion.isEmpty()) easeMotion = TaxBook.motionForTests(level, v);
            }
            if (k % 10 == 0) Kit.log(String.format(Locale.ROOT, "[VT] er03 day %d: %s, state %s, club %d bp", k, p, java.util.Arrays.toString(EconCycle.stateForTests(id)), FolkCredit.rateBp(id)));
        }
        int turns = 0;
        for (int i = 0; i + 3 < runs.size(); i++) {
            if (runs.get(i) == CycleModel.Phase.BOOM && runs.get(i + 1) == CycleModel.Phase.SLOWING && runs.get(i + 2) == CycleModel.Phase.SLUMP
                && runs.get(i + 3) == CycleModel.Phase.RECOVERING) turns++;
        }
        long[] council = EconCycle.councilForTests(id);
        Kit.Expect ok = new Kit.Expect();
        ok.that(turns >= 2, "boom, slowing, slump, recovering, twice over: " + runs);
        ok.that(council[0] > 0 && council[1] > 0, "the council raised its rate and cut it: " + council[0] + " / " + council[1]);
        ok.that(nb > 0 && ns > 0 && boomBp / nb > slumpBp / ns, "the lenders dearer in the boom: " + (nb == 0 ? 0 : boomBp / nb) + " bp against " + (ns == 0 ? 0 : slumpBp / ns));
        ok.that(easeMotion.contains("lowered"), "in the slump, the treasury sound, the council moves to ease a tax: '" + easeMotion + "'");
        ok.that(boomNerve.equals("nerve -1") && slumpSpend.startsWith("spend 1.25"), "bolder founders in the boom, more room for the works in the slump: " + boomNerve + "; " + slumpSpend);
        ok.that(lowEmp >= 0.80 && runs.get(runs.size() - 1) != null, "bounded: employment at its lowest " + lowEmp);
        boolean told = false;
        for (com.jrpetty.mcassistant.village.Chronicle.Entry e : com.jrpetty.mcassistant.village.Chronicle.of(id)) told |= e.text().contains("hard times");
        ok.that(told, "the chronicle has the hard times");
        Kit.log("[VT] er03 " + ok.summary() + "; turns " + runs);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ er04 the treasury over thirty days

    /**
     * Two grown towns alike (twenty folk: guards, builders and the rest paid out of the treasury, the producers their own
     * homesteads), thirty days each, every morning the town's whole real morning (Market.tick: wages, rents, the market,
     * the businesses, the cycle); each day its households buy their bread at the market and its works take their stone.
     * The one keeps its reserve (the market's surplus in early, the council called on an empty treasury), the other not.
     * The first's treasury is never at nought and its wages are paid; the census at nought drift.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "er04_treasury")
    public static void er04_treasury(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4118000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        EconCycle.liveForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        StationTask[] ts = trades(20, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.COOK, StationTask.SMITH,
            StationTask.TAILOR, StationTask.FARM, StationTask.FARM, StationTask.MINE, StationTask.MINE, StationTask.WOOD, StationTask.FISH);
        ItemStack[] goods = new ItemStack[20];
        for (int i = 0; i < 20; i++) goods[i] = new ItemStack(i < 10 ? Items.BREAD : Items.COBBLESTONE, 64);
        UUID keep = town(helper, level, x, 8, 120, ts, goods);
        UUID old = town(helper, level, x + 3000, 8, 120, ts, goods);
        long day0 = day(level);
        int[] zeroDays = new int[2], minT = { Integer.MAX_VALUE, Integer.MAX_VALUE };
        long drift = 0;
        StringBuilder trace = new StringBuilder();
        for (int k = 1; k <= 30; k++) {
            long today = day0 + k;
            level.setDayTime(today * 24000L + 1000L);
            for (int w = 0; w < 2; w++) {
                UUID id = w == 0 ? keep : old;
                Villages.Village v = Villages.get(id);
                EconCycle.fiscalForTests(w == 0);
                Market.tick(level, v);
                drift += Math.abs(CoinCensus.lastDrift(id));
                // The households' bread, the works' stone, the stores topped up as the long tests do.
                for (com.jrpetty.mcassistant.entity.AssistantEntity e : Villages.folkOf(id)) {
                    if (!(e instanceof VillageFolkEntity f) || f.isBaby()) continue;
                    MarketMaker.takeForTests(level, v, f, Accounts.Account.folk(f), Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 2);
                }
                MarketMaker.takeForTests(level, v, null, Accounts.Account.dept(Accounts.Dept.WORKS), Accounts.Purpose.BUILD, s -> s.is(Items.COBBLESTONE), 48);
                Kit.stockForGrowth(level, v);
                int t = Ledger.coins(id);
                minT[w] = Math.min(minT[w], t);
                if (t <= 0) zeroDays[w]++;
                if (w == 0 || k % 5 == 0) trace.append(String.format(Locale.ROOT, "%s d%d T%d ious %d share %d; ", w == 0 ? "keep" : "old", k, t,
                    Accounts.debts(id) / 100, Market.lastShare(id)));
            }
        }
        EconCycle.fiscalForTests(true);
        Kit.log("[VT] er04 " + trace);
        Kit.log("[VT] er04 keep: min " + minT[0] + ", days at nought " + zeroDays[0] + "; the old way: min " + minT[1] + ", at nought " + zeroDays[1]
            + "; council " + java.util.Arrays.toString(EconCycle.councilForTests(keep)) + "; drift " + drift);
        Kit.Expect ok = new Kit.Expect();
        ok.that(zeroDays[0] == 0 && minT[0] > 0, "the treasury never at nought over thirty days: its lowest " + minT[0]);
        ok.that(drift == 0, "the census at nought drift every morning: " + drift);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ er05 kept with the world

    /** The cycle's state, the statistics, the trade book and the price sheets saved and loaded with the world's accounts. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er05_saved")
    public static void er05_saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4124000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        EconCycle.liveForTests(true);
        EconRegion.liveForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        UUID id = town(helper, level, x, 20, 400, trades(6), lots(Items.WHEAT, 2));
        Villages.Village v = Villages.get(id);
        long day0 = day(level);
        for (int k = 0; k < 5; k++) {
            level.setDayTime((day0 + k) * 24000L + 7000L);
            EconIndex.record(level, v, day0 + k);
            EconRegion.publish(level, id, day0 + k);
            EconCycle.stepForTests(level, v, day0 + k);
        }
        double[] before = EconCycle.stateForTests(id);
        long[] cpi = EconIndex.series(id, "cpi");
        CycleModel.Phase ph = EconCycle.phase(id);
        CapitalBook.roundTripForTests(id, level.registryAccess());
        Kit.Expect ok = new Kit.Expect();
        ok.that(java.util.Arrays.equals(before, EconCycle.stateForTests(id)), "the cycle's state: " + java.util.Arrays.toString(EconCycle.stateForTests(id)));
        ok.that(java.util.Arrays.equals(cpi, EconIndex.series(id, "cpi")) && cpi.length == 5, "five mornings of the index");
        ok.that(EconCycle.phase(id) == ph && EconRegion.known(id, id, day0 + 5) != null, "its phase and its price sheets: " + ph);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }
}
