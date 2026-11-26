package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Bailiff;
import com.jrpetty.mcassistant.entity.Debts;
import com.jrpetty.mcassistant.entity.Insolvency;
import com.jrpetty.mcassistant.entity.LandPlots;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.PlayerFinance;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.Tenancy;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * [wpc] Personal debt (Debts, Insolvency, Bailiff): the arrears ladder in order; a repayment plan kept; a third of the
 * wages attached; the bailiff walks to the home and takes only what it may; voluntary bankruptcy pays the creditors in order
 * and writes off the rest; a creditor's court makes a debtor bankrupt; a bankrupt is barred till the discharge; a folk goes
 * bankrupt too; the credit score moves with the history. Each on its own ground, x 12,400,000 to 12,490,000, z 91,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DebtGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** The ladder: a reminder, a late fee, a letter of demand (an item), the creditor acting, each in turn. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db01_ladder")
    public static void db01_ladder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12400000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 0);
            long d0 = day(level);
            String id = Debts.owe(level, t.id(), p.getUUID(), "Tester", "rates", "the town", "town", null, 40, 0, 0, "test:1");
            int[] stages = new int[5];
            for (int i = 1; i <= 4; i++) {
                long d = d0 + new int[]{ 0, 1, 3, 6, 10 }[i];
                Debts.morningForTests(level, t.v(), d);
                stages[i] = Debts.stageForTests(t.id(), p.getUUID(), id);
            }
            Kit.log("[VT] db01 stages " + java.util.Arrays.toString(stages) + " owed " + Debts.owedForTests(t.id(), p.getUUID(), id));
            ok.that(stages[1] == 1 && stages[2] == 2 && stages[3] == 3 && stages[4] >= 4, "the rungs in order: " + java.util.Arrays.toString(stages));
            ok.that(Debts.owedForTests(t.id(), p.getUUID(), id) >= 42, "a late fee added");
            ok.that(Debts.status(t.id(), p.getUUID(), d0 + 10).contains("late"), "the status line says so: " + Debts.status(t.id(), p.getUUID(), d0 + 10));
            PlayerLife.open(p, "debts", null);
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(PropertyKit.button(page, "Pay") != null && PropertyKit.button(page, "Ask for a plan") != null && PropertyKit.button(page, "Declare bankruptcy") != null,
                "Pay, Ask for a plan, Declare bankruptcy on the Debts tab");
            CompoundTag pay = PropertyKit.button(page, "Pay");
            ok.that(pay != null && !pay.getBoolean("on") && !pay.getString("why").isEmpty(), "Pay rests with no coin, and says why");
            PropertyKit.finish(helper, ok);
        });
    }

    /** A plan: asked for when late, the week's payment taken by standing order, the ladder held. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db02_plan")
    public static void db02_plan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12410000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 60);
            long d0 = day(level);
            String id = Debts.owe(level, t.id(), p.getUUID(), "Tester", "rent", "the town", "town", null, 30, 0, 0, "test:2");
            String early = Debts.planForTests(p, t.id(), id);
            ok.that(early.startsWith("!"), "nothing to plan before it's late: " + early);
            Debts.morningForTests(level, t.v(), d0 + 1);
            String plan = Debts.planForTests(p, t.id(), id);
            ok.that(plan.contains("a week"), "a plan agreed: " + plan);
            int before = Debts.owedForTests(t.id(), p.getUUID(), id);
            Debts.planFromForTests(t.id(), p.getUUID(), id, d0 + 1);
            Debts.morningForTests(level, t.v(), d0 + 8);
            int after = Debts.owedForTests(t.id(), p.getUUID(), id);
            Kit.log("[VT] db02 " + plan + " | owed " + before + " -> " + after + " stage " + Debts.stageForTests(t.id(), p.getUUID(), id));
            ok.that(after < before, "the week's payment taken: " + before + " -> " + after);
            ok.that(Debts.stageForTests(t.id(), p.getUUID(), id) <= 1, "the ladder held while it's kept");
            PropertyKit.finish(helper, ok);
        });
    }

    /** An attachment: a third of yesterday's wages taken toward a debt the creditor is acting on. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db03_attachment")
    public static void db03_attachment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12420000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 30);
            long d0 = day(level);
            String id = Debts.owe(level, t.id(), p.getUUID(), "Tester", "fine", "the town", "town", null, 50, 0, 0, "test:3");
            for (long d : new long[]{ d0 + 1, d0 + 3, d0 + 6 }) Debts.morningForTests(level, t.v(), d);
            PlayerFinance.internal(p.getUUID(), t.id(), 3000L, PlayerFinance.Cat.WAGES, "a day's wages (test)");
            level.setDayTime(level.getDayTime() + 24000L);                // the next morning: yesterday's wages
            int before = Debts.owedForTests(t.id(), p.getUUID(), id);
            Debts.morningForTests(level, t.v(), d0 + 10);
            int after = Debts.owedForTests(t.id(), p.getUUID(), id);
            Kit.log("[VT] db03 owed " + before + " -> " + after + " stage " + Debts.stageForTests(t.id(), p.getUUID(), id));
            ok.that(after < before && Debts.of(t.id(), p.getUUID()).get(0).attached(), "a share of the wages attached: " + before + " -> " + after);
            PropertyKit.finish(helper, ok);
        });
    }

    /** The bailiff walks to the home and takes goods to the value: never the bed, the tools or a few days' food. */
    @GameTest(template = EMPTY, timeoutTicks = 2600, batch = "db04_bailiff")
    public static void db04_bailiff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12430000, Z, 3, 30);
        BlockPos[] chest = new BlockPos[1];
        ServerPlayer[] who = new ServerPlayer[1];
        String[] debt = new String[1];
        helper.runAtTickTime(10, () -> {
            BlockPos home = PropertyKit.house(level, t.id(), t.heart().offset(0, 0, 14));
            ServerPlayer p = PropertyKit.player(helper, t.heart().offset(20, 0, 20), 0);
            who[0] = p;
            Tenancy.enrolForTests(t.id());
            Tenancy.startForTests(p, t.id(), home, 1);
            chest[0] = home.above().east();
            level.setBlock(chest[0], Blocks.CHEST.defaultBlockState(), 3);
            Container c = (Container) level.getBlockEntity(chest[0]);
            c.setItem(0, new ItemStack(Items.RED_BED));
            c.setItem(1, new ItemStack(Items.IRON_PICKAXE));
            c.setItem(2, new ItemStack(Items.BREAD, 5));
            c.setItem(3, new ItemStack(Items.DIAMOND, 10));
            c.setChanged();
            debt[0] = Debts.owe(level, t.id(), p.getUUID(), "Tester", "tab", "the tavern", "town", null, 20, 0, 0, "test:4");
            boolean sent = Bailiff.sendForTests(level, t.v(), p.getUUID(), debt[0]);
            Kit.log("[VT] db04 bailiff sent " + sent + " to " + home);
            helper.assertTrue(sent, "a bailiff sets off");
        });
        helper.runAfterDelay(20, () -> helper.succeedWhen(() -> {
            helper.assertFalse(Bailiff.busy(who[0].getUUID()), "the bailiff still on the way");
            Container c = (Container) level.getBlockEntity(chest[0]);
            int diamonds = 0, beds = 0, picks = 0, bread = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(Items.DIAMOND)) diamonds += s.getCount();
                if (s.is(Items.RED_BED)) beds += s.getCount();
                if (s.is(Items.IRON_PICKAXE)) picks += s.getCount();
                if (s.is(Items.BREAD)) bread += s.getCount();
            }
            Kit.log("[VT] db04 left: diamonds " + diamonds + " bed " + beds + " pick " + picks + " bread " + bread + " owed " + Debts.owedForTests(t.id(), who[0].getUUID(), debt[0]));
            helper.assertTrue(diamonds < 10, "goods taken to the value");
            helper.assertTrue(beds == 1 && picks == 1 && bread == 5, "the bed, the tools and the food left");
        }));
    }

    /** Voluntary bankruptcy: land sold back, the town's rates paid before a tab, the rest written off, barred till discharge. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db05_voluntary")
    public static void db05_voluntary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12440000, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            p.getInventory().clearContent();
            Debts.owe(level, t.id(), p.getUUID(), "Tester", "rates", "the town", "town", null, 30, 0, 0, "test:5a");
            Debts.owe(level, t.id(), p.getUUID(), "Tester", "tab", "the tavern", "town", null, 900, 0, 0, "test:5b");
            String said = Insolvency.declareForTests(p);
            List<String> st = Insolvency.statementForTests(t.id(), p.getUUID());
            Kit.log("[VT] db05 " + said + " | " + st);
            ok.that(Insolvency.bankrupt(t.id(), p.getUUID()), "bankrupt: " + said);
            ok.that(LandPlots.of(t.id(), p.getUUID()).isEmpty(), "the land sold back to the town");
            ok.that(Debts.of(t.id(), p.getUUID()).isEmpty(), "the rest written off");
            int taxes = -1, rest = -1;
            for (int i = 0; i < st.size(); i++) { if (st.get(i).startsWith("Taxes")) taxes = i; if (st.get(i).startsWith("Everybody")) rest = i; }
            ok.that(taxes >= 0 && (rest < 0 || taxes < rest), "the town's rates paid before everybody else");
            ok.that(Insolvency.barred(t.id(), p.getUUID()), "barred from a business or office");
            String land = LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            ok.that(land.startsWith("!") && land.contains("bankrupt"), "no land while bankrupt: " + land);
            Insolvency.dischargeForTests(level, t.v(), p.getUUID());
            ok.that(!Insolvency.barred(t.id(), p.getUUID()), "discharged");
            PropertyKit.finish(helper, ok);
        });
    }

    /** A creditor's court: the debtor made bankrupt. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db06_forced")
    public static void db06_forced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12450000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 0);
            Debts.owe(level, t.id(), p.getUUID(), "Tester", "loan", "a neighbour", "town", null, 200, 0, 0, "test:6");
            Insolvency.forcedForTests(level, t.v(), p.getUUID(), "a neighbour");
            ok.that(Insolvency.bankrupt(t.id(), p.getUUID()), "made bankrupt by the court");
            ok.that(!Insolvency.statementForTests(t.id(), p.getUUID()).isEmpty(), "the receiver's statement written");
            PropertyKit.finish(helper, ok);
        });
    }

    /** A folk goes bankrupt too: its purse to its creditors, its debts written off. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db08_folk")
    public static void db08_folk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12460000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            VillageFolkEntity f = t.folk().get(1);
            f.earn(10);
            int purse = f.purse();
            Debts.owe(level, t.id(), f.getUUID(), f.displayNameCap(), "tab", "the tavern", "town", null, 300, 0, 0, "test:8");
            int treasury = Ledger.coins(t.id());
            Insolvency.forcedForTests(level, t.v(), f.getUUID(), "the tavern");
            Kit.log("[VT] db08 purse " + purse + " -> " + f.purse() + " treasury " + treasury + " -> " + Ledger.coins(t.id()));
            ok.that(Insolvency.bankrupt(t.id(), f.getUUID()), "the folk is bankrupt");
            ok.that(f.purse() == 0, "its purse to the receiver");
            ok.that(Debts.of(t.id(), f.getUUID()).isEmpty(), "its debts written off");
            PropertyKit.finish(helper, ok);
        });
    }

    /** The credit score: up for a debt paid on time, down for one in arrears. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "db09_score")
    public static void db09_score(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PropertyKit.T t = PropertyKit.town(helper, level, 12470000, Z, 3, 30);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer good = PropertyKit.player(helper, t.heart(), 50), bad = PropertyKit.player(helper, t.heart().east(), 0);
            long d0 = day(level);
            String g = Debts.owe(level, t.id(), good.getUUID(), "Good", "rates", "the town", "town", null, 10, 0, 3, "test:9g");
            Debts.payForTests(good, t.id(), g, 0);
            Debts.owe(level, t.id(), bad.getUUID(), "Bad", "rates", "the town", "town", null, 10, 0, 0, "test:9b");
            for (long d : new long[]{ d0 + 1, d0 + 3, d0 + 6 }) Debts.morningForTests(level, t.v(), d);
            int sg = Debts.score(level, t.id(), good.getUUID()).score(), sb = Debts.score(level, t.id(), bad.getUUID()).score();
            Kit.log("[VT] db09 good " + sg + " bad " + sb + " reasons " + Debts.score(level, t.id(), bad.getUUID()).reasons());
            ok.that(sg > Debts.START_SCORE_FOR_TESTS && sb < Debts.START_SCORE_FOR_TESTS, "paying on time lifts it, arrears drop it: " + sg + " / " + sb);
            PropertyKit.finish(helper, ok);
        });
    }
}
