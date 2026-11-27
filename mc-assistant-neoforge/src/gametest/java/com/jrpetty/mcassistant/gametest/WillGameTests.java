package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Debts;
import com.jrpetty.mcassistant.entity.LandPlots;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.PropertyBook;
import com.jrpetty.mcassistant.entity.StandingOrders;
import com.jrpetty.mcassistant.entity.Wills;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * [wpc] Wills and standing orders (Wills, StandingOrders): a will passes land to its heir; debts are paid out of the estate
 * first; a bill for a player away is met from the account and logged for its return, and a shortfall goes on the debts;
 * a long absence with no will gives the property back to the town and keeps the surplus. Each on its own ground,
 * x 12,500,000 to 12,540,000, z 91,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WillGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    /** The will: land to the named heir, debts paid first out of the account, the rest of the savings to the town. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wl01_will")
    public static void wl01_will(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12500000, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            UUID heir = UUID.randomUUID();
            PropertyBook.putAccountForTests(t.id(), p.getUUID(), 50);
            Debts.owe(level, t.id(), p.getUUID(), "Tester", "tab", "the tavern", "town", null, 20, 0, 0, "test:w1");
            Wills.makeForTests(t.id(), p.getUUID(), "town", "p:" + heir, "town", "town", level.getDayTime() / 24000L);
            ok.that(Wills.has(t.id(), p.getUUID()), "a will, witnessed");
            PlayerLife.open(p, "will", null);
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(PropertyKit.blocks(page, "choice") == 4 && PropertyKit.button(page, "Retire") != null, "the will screen: an heir for each part, and Retire");
            int treasury = Ledger.coins(t.id());
            String said = Wills.executeForTests(level, t.v(), p.getUUID(), "Tester");
            Kit.log("[VT] wl01 " + said + " treasury " + treasury + " -> " + Ledger.coins(t.id()));
            List<LandPlots.Title> theirs = LandPlots.of(t.id(), heir);
            ok.that(theirs.size() == 1 && LandPlots.of(t.id(), p.getUUID()).isEmpty(), "the land passed to the heir");
            ok.that(Debts.of(t.id(), p.getUUID()).isEmpty(), "the debt paid first out of the estate");
            ok.that(PropertyBook.accountForTests(t.id(), p.getUUID()) == 0, "the savings handed on");
            PropertyKit.finish(helper, ok);
        });
    }

    /** A standing order for a player away: met from the account and logged; short, the rest on the debts. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wl02_standing_orders")
    public static void wl02_standing_orders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12510000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            UUID away = UUID.randomUUID();
            PropertyBook.putAccountForTests(t.id(), away, 30);
            int paid = StandingOrders.billForTests(level, t.id(), away, 20, "test:s1");
            ok.that(paid == 20 && PropertyBook.accountForTests(t.id(), away) == 10, "paid from the account while away: " + paid);
            int short_ = StandingOrders.billForTests(level, t.id(), away, 40, "test:s2");
            List<String> log = StandingOrders.logForTests(away);
            Kit.log("[VT] wl02 " + short_ + " | " + log + " | debts " + Debts.total(t.id(), away));
            ok.that(short_ == 10 && Debts.total(t.id(), away) == 30, "what the account couldn't meet went on the debts");
            ok.that(log.size() == 2 && log.get(1).startsWith("Day") && log.get(1).contains("Couldn't"), "both in the summary for the player's return");
            PropertyKit.finish(helper, ok);
        });
    }

    /** A long absence, no will: the land back to the town, the debts paid out of it, the rest kept in the player's account. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wl03_long_absence")
    public static void wl03_long_absence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12520000, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            Debts.owe(level, t.id(), p.getUUID(), "Tester", "rates", "the town", "town", null, 5, 0, 0, "test:a1");
            Ledger.addCoins(t.id(), 500);
            StandingOrders.longAbsenceForTests(level, t.v(), p.getUUID());
            Kit.log("[VT] wl03 log " + StandingOrders.logForTests(p.getUUID()) + " account " + PropertyBook.accountForTests(t.id(), p.getUUID()));
            ok.that(LandPlots.of(t.id(), p.getUUID()).isEmpty(), "the land back to the town");
            ok.that(Debts.of(t.id(), p.getUUID()).isEmpty(), "the debts paid out of it");
            ok.that(PropertyBook.accountForTests(t.id(), p.getUUID()) > 0, "the rest kept for the player");
            PlayerLife.open(p, "away", null);
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(page.getString("title").equals("While you were away"), "the summary page");
            PropertyKit.finish(helper, ok);
        });
    }
}
