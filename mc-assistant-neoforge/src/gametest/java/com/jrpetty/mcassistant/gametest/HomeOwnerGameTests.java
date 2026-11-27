package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.Mortgages;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.PropertyDay;
import com.jrpetty.mcassistant.entity.PropertyMarket;
import com.jrpetty.mcassistant.entity.Debts;
import com.jrpetty.mcassistant.entity.Tenancy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * [wpc] Homes (HomePage, Tenancy, Mortgages): renting a house holds it and takes the rent; notice ends a tenancy; the
 * mortgage's rate follows the deposit and the credit score, and the bank's absence is said plainly; a bought house goes on
 * the property register and pays rates by standing order. Each on its own ground, x 12,100,000 to 12,150,000, z 91,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class HomeOwnerGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    /** Renting: the house held for the player (the town lets it to nobody else), the first day's rent paid, the page says so. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ho01_rent")
    public static void ho01_rent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12100000, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos home = PropertyKit.house(level, t.id(), t.heart().offset(0, 0, 18));
            Tenancy.enrolForTests(t.id());
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 30);
            int before = PropertyKit.coins(p);
            String said = Tenancy.startForTests(p, t.id(), home, 2);
            Kit.log("[VT] ho01 rent: " + said + " coins " + before + " -> " + PropertyKit.coins(p));
            ok.that(said.equals("ok") && Tenancy.held(home), "rented, and held for the player");
            ok.that(PropertyKit.coins(p) == before - 2, "the first day's rent paid");
            ok.that(home.equals(Tenancy.home(t.id(), p.getUUID())), "the player's home now");
            PlayerLife.open(p, "home", null);
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(page.getString("sub").contains("rent"), "the Home page says it: " + page.getString("sub"));
            ok.that(PropertyKit.button(page, "Give notice") != null, "and offers notice");
            PropertyKit.finish(helper, ok);
        });
    }

    /** Notice: three days, then the tenancy ends and the house is free again. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ho02_notice")
    public static void ho02_notice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12110000, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos home = PropertyKit.house(level, t.id(), t.heart().offset(0, 0, 18));
            Tenancy.enrolForTests(t.id());
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 30);
            Tenancy.startForTests(p, t.id(), home, 1);
            PlayerLife.open(p, "home", null);
            PlayerLife.act(p, "home", "leave", new CompoundTag());
            String said = PlayerLife.messageForTests(p);
            long d = level.getDayTime() / 24000L;
            PropertyDay.morning(level, t.v(), d + 1);
            ok.that(Tenancy.held(home), "still held during the notice: " + said);
            PropertyDay.morning(level, t.v(), d + 3);
            ok.that(!Tenancy.held(home) && Tenancy.home(t.id(), p.getUUID()) == null, "ended after three days");
            PropertyKit.finish(helper, ok);
        });
    }

    /** The mortgage's rate: lower with more down, lower with better credit; with no bank, it says so. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ho03_mortgage_terms")
    public static void ho03_mortgage_terms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12120000, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            int r60 = Mortgages.rateForTests(t.id(), 70, 60), r90 = Mortgages.rateForTests(t.id(), 70, 90);
            int good = Mortgages.rateForTests(t.id(), 85, 75), poor = Mortgages.rateForTests(t.id(), 40, 75);
            Kit.log("[VT] ho03 60% " + r60 + " 90% " + r90 + " good " + good + " poor " + poor);
            ok.that(r60 < r90, "more down, a lower rate");
            ok.that(good < poor, "better credit, a lower rate");
            BlockPos home = PropertyKit.house(level, t.id(), t.heart().offset(0, 0, 18));
            Tenancy.enrolForTests(t.id());
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 200);
            PropertyKit.friend(t, p);
            String said = Mortgages.takeForTests(p, home.asLong(), 75, 12);
            ok.that(said.startsWith("!") && said.contains("bank"), "no bank, no mortgage, said plainly: " + said);
            PlayerLife.open(p, "mortgage", null);
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(page.getString("sub").contains("No bank"), "the page says so too: " + page.getString("sub"));
            PropertyKit.finish(helper, ok);
        });
    }

    /** A bought house: on the property register, and rates by standing order each week. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ho04_rates")
    public static void ho04_rates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12130000, Z, 3, 40);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos home = PropertyKit.house(level, t.id(), t.heart().offset(0, 0, 18));
            Tenancy.enrolForTests(t.id());
            ServerPlayer p = PropertyKit.player(helper, home, 640);
            PropertyKit.citizen(t, p);
            String bought = Homes.playerBuys(level, p);
            Kit.log("[VT] ho04 " + bought);
            ok.that(bought.startsWith("It's yours"), "bought: " + bought);
            long d = level.getDayTime() / 24000L;
            PropertyDay.morning(level, t.v(), d);
            ok.that(!PropertyMarket.ownedBy(t.id(), p.getUUID()).isEmpty(), "on the property register");
            int c0 = PropertyKit.coins(p);
            PropertyDay.morning(level, t.v(), d + 7);
            ok.that(PropertyKit.coins(p) < c0 || !Debts.of(t.id(), p.getUUID()).isEmpty(), "a week's rates by standing order: " + c0 + " -> " + PropertyKit.coins(p));
            PlayerLife.open(p, "home", LifePage.args("tab", "mine"));
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(PropertyKit.button(page, "Sell") != null && PropertyKit.button(page, "Let my house") != null, "Sell and Let on the Home page");
            PropertyKit.finish(helper, ok);
        });
    }
}
