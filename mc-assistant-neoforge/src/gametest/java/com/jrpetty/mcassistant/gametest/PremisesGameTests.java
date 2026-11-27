package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketDays;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.PlayerStalls;
import com.jrpetty.mcassistant.entity.Premises;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * [wpc] Premises (Premises): the zones and their sums; the town's empty trading buildings listed with the breakdown,
 * leased and rented by standing order; the uses; a firm going under sells what it holds for its creditors, the lien
 * first; MarketDays declares an extra market; a market-day pitch costs less than a week's stall; the browser page.
 * Each on its own ground, x 12,300,000 to 12,360,000, z 91,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PremisesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    /** The zones: the square, the high street, the edge, the smoke. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pm01_zones")
    public static void pm01_zones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12300000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos h = t.heart();
            String sq = Premises.zoneForTests(t.id(), h, h.offset(18, 0, 0));
            String hi = Premises.zoneForTests(t.id(), h, h.offset(8, 0, 50));
            String edge = Premises.zoneForTests(t.id(), h, h.offset(80, 0, 80));
            Ledger.built(t.id(), "smeltery", h.offset(40, 0, 40), Direction.NORTH);
            String smoke = Premises.zoneForTests(t.id(), h, h.offset(45, 0, 40));
            Kit.log("[VT] pm01 " + sq + " | " + hi + " | " + edge + " | " + smoke);
            ok.that(sq.contains("square"), "by the square: " + sq);
            ok.that(hi.contains("high street"), "on an avenue: " + hi);
            ok.that(edge.contains("edge"), "far out: " + edge);
            ok.that(smoke.contains("smoke"), "by the smelter: " + smoke);
            PropertyKit.finish(helper, ok);
        });
    }

    /** An empty shop of the town's is listed with its sum; leased, it is the player's, and its rent comes by standing order. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pm02_lease")
    public static void pm02_lease(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12310000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos at = t.heart().offset(0, 0, -24);
            BuildGoal.stamp(level, "shop", at, Direction.SOUTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "shop", at, Direction.SOUTH);
            List<String> free = Premises.freeForTests(level, t.v());
            Kit.log("[VT] pm02 free " + free);
            ok.that(free.size() == 1 && free.get(0).contains("shop"), "the empty shop is listed");
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 200);
            PropertyKit.friend(t, p);
            PlayerLife.open(p, "premises", null);
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(PropertyKit.button(page, "Lease") != null && PropertyKit.button(page, "Buy") != null && PropertyKit.button(page, "Show me") != null, "Lease, Buy and Show me");
            boolean sum = false;
            for (var e : page.getList("blocks", 10)) if (((CompoundTag) e).getString("s").contains("Rent: 1.5%")) sum = true;
            ok.that(sum, "the listing shows its sum");
            String id = free.isEmpty() ? "" : free.get(0).split("\\|")[0];
            int before = PropertyKit.coins(p);
            String said = Premises.takeForTests(p, id, false, "");
            Kit.log("[VT] pm02 lease: " + said);
            CompoundTag held = Premises.getForTests(t.id(), id);
            ok.that(held != null && p.getUUID().equals(Premises.holder(t.id(), id)), "leased: " + said);
            ok.that(PropertyKit.coins(p) < before, "the first week paid");
            ok.that(Premises.freeForTests(level, t.v()).isEmpty(), "no longer listed as free");
            if (held != null) {
                held.putLong("paidTo", level.getDayTime() / 24000L);
                int c0 = PropertyKit.coins(p);
                Premises.morningForTests(level, t.v(), level.getDayTime() / 24000L);
                ok.that(PropertyKit.coins(p) < c0 || !com.jrpetty.mcassistant.entity.Debts.of(t.id(), p.getUUID()).isEmpty(), "the week's rent by standing order");
            }
            PlayerLife.act(p, "premises", "use", LifePage.args("id", id));
            ok.that(PlayerLife.messageForTests(p).contains("warehouse") || PlayerLife.messageForTests(p).contains("workshop"), "a use with its effect: " + PlayerLife.messageForTests(p));
            PropertyKit.finish(helper, ok);
        });
    }

    /** A firm going under: premises held in its name sold to the town at 85% for its creditors, into its till. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pm03_bankrupt_firm")
    public static void pm03_bankrupt_firm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12320000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos at = t.heart().offset(0, 0, -24);
            BuildGoal.stamp(level, "shop", at, Direction.SOUTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "shop", at, Direction.SOUTH);
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 600);
            PropertyKit.citizen(t, p);
            String id = Premises.freeForTests(level, t.v()).get(0).split("\\|")[0];
            String said = Premises.takeForTests(p, id, true, "");
            ok.that(!said.startsWith("!"), "bought: " + said);
            Business b = new Business("wpc-test-firm", Business.Type.SHOP, "Test Stores");
            CompoundTag held = Premises.getForTests(t.id(), id);
            if (held != null) held.putString("firm", b.id);
            Ledger.addCoins(t.id(), 1000);
            long till = b.till;
            Premises.onBankrupt(level, t.id(), b);
            Kit.log("[VT] pm03 till " + till + " -> " + b.till);
            ok.that(Premises.getForTests(t.id(), id) == null, "the firm's premises are sold");
            ok.that(b.till > till, "their price into the firm's till for its creditors");
            PropertyKit.finish(helper, ok);
        });
    }

    /** MarketDays: an extra market declared is a market day; a market-day pitch is dearer than nothing and cheaper than a week. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pm04_market_days")
    public static void pm04_market_days(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12330000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            long day = level.getDayTime() / 24000L;
            long d = day + 1;
            while (Market.marketDay(t.id(), d)) d++;
            ok.that(!Market.marketDay(t.id(), d), "an ordinary day");
            MarketDays.declare(t.id(), d, MarketDays.Kind.WEEKEND);
            ok.that(Market.marketDay(t.id(), d) && MarketDays.kind(t.id(), d).equals("WEEKEND"), "declared a weekend market: " + MarketDays.kind(t.id(), d));
            int wk = PlayerStalls.licenceFee(t.id(), PlayerStalls.Licence.WEEKLY), mk = PlayerStalls.licenceFee(t.id(), PlayerStalls.Licence.MARKET_DAY);
            ok.that(mk > 0 && mk < wk, "a pitch for the day costs less than a week: " + mk + " < " + wk);
            PropertyKit.finish(helper, ok);
        });
    }

    /** Letting premises to a villager firm: the first week's rent to the landlord, the lease on the register. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pm05_let_to_firm")
    public static void pm05_let_to_firm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12340000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos at = t.heart().offset(0, 0, -24);
            BuildGoal.stamp(level, "shop", at, Direction.SOUTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "shop", at, Direction.SOUTH);
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            String id = Premises.freeForTests(level, t.v()).get(0).split("\\|")[0];
            Premises.takeForTests(p, id, false, "");
            String none = Premises.letForTests(p, t.id(), id, "no-such-firm", 8, 5);
            ok.that(none.startsWith("!"), "only a trading firm: " + none);
            PropertyKit.finish(helper, ok);
        });
    }
}
