package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.BuildPicker;
import com.jrpetty.mcassistant.entity.LandPlots;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.Premises;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.item.PropertyDeeds;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * [wpc] Land in the plan's own sizes (LandPlots), and the builder that builds on it (BuildPicker): the sizes match the plan's
 * lots; buying gives a title, a deed, corner posts and the lots held off the town's builders; the limits hold; a title is
 * what WP-B's farms check; rates double on empty land; plots merge (not under housing pressure); and the builder shows a
 * bought lot on its map and the building's blocks on it. Each on its own ground, x 12,200,000 to 12,270,000, z 91,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LandPlotGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    /** The sizes: a lot is the plan's eleven square; two side by side; a long lot; a whole block of four. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "lp01_sizes")
    public static void lp01_sizes(GameTestHelper helper) {
        Kit.Expect ok = new Kit.Expect();
        TownPlan.Lot first = null;
        for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT && TownPlan.ringOf(l) == 1 && l.use().equals("home")
                && Math.abs(l.x()) >= TownPlan.RING + TownPlan.STREET && Math.abs(l.z()) >= TownPlan.RING + TownPlan.STREET) { first = l; break; }
        ok.that(first != null, "a house lot in the second ring");
        if (first != null) {
            int[] lot = LandPlots.parcelForTests("LOT", first.x(), first.z());
            int[] dbl = LandPlots.parcelForTests("DOUBLE", first.x(), first.z());
            int[] blk = LandPlots.parcelForTests("BLOCK", first.x(), first.z());
            Kit.log("[VT] lp01 lot " + java.util.Arrays.toString(lot) + " double " + java.util.Arrays.toString(dbl) + " block " + java.util.Arrays.toString(blk));
            ok.that(lot != null && lot[2] - lot[0] + 1 == TownPlan.LOT && lot[3] - lot[1] + 1 == TownPlan.LOT, "a lot is eleven square");
            ok.that(dbl != null && (dbl[2] - dbl[0] + 1) * (dbl[3] - dbl[1] + 1) == 2 * TownPlan.LOT * TownPlan.LOT, "a double frontage is two lots");
            ok.that(blk != null && blk[2] - blk[0] + 1 == 2 * TownPlan.LOT && blk[3] - blk[1] + 1 == 2 * TownPlan.LOT, "a block is the four lots between the streets");
            if (blk != null) {
                boolean street = false;
                for (int x = blk[0]; x <= blk[2]; x++) for (int z = blk[1]; z <= blk[3]; z++) if (TownPlan.isStreet(x, z)) street = true;
                ok.that(!street, "no street runs through a block");
            }
        }
        int[] longLot = null;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LONG || !l.use().equals("long")) continue;
            TownPlan.Lot part = null;
            for (TownPlan.Lot x : TownPlan.lots()) if (x.kind() == TownPlan.Kind.LOT && x.key() == l.cells()[0]) part = x;
            if (part != null) { longLot = LandPlots.parcelForTests("LONG", part.x(), part.z()); break; }
        }
        ok.that(longLot != null && Math.max(longLot[2] - longLot[0], longLot[3] - longLot[1]) + 1 >= 2 * TownPlan.LOT - 1, "a long lot is two lots end to end: "
            + java.util.Arrays.toString(longLot));
        PropertyKit.finish(helper, ok);
    }

    /** Buying a lot: the price paid, a title, the deed, posts with lanterns at the corners, the lots held off the builders. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lp02_buy")
    public static void lp02_buy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12200000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart().offset(2, 0, 2), 300);
            String early = LandPlots.buyForTests(p, "LOT", 0, 0);
            ok.that(early.startsWith("!"), "a stranger is refused: " + early);
            PropertyKit.friend(t, p);
            int[] lot = LandPlots.freeLotForTests(t.id(), "LOT");
            ok.that(lot != null, "a lot a player may buy");
            if (lot != null) {
                int before = PropertyKit.coins(p);
                String said = LandPlots.buyForTests(p, "LOT", lot[0], lot[1]);
                Kit.log("[VT] lp02 buy: " + said);
                List<LandPlots.Title> mine = LandPlots.of(t.id(), p.getUUID());
                ok.that(!said.startsWith("!") && mine.size() == 1, "bought: " + said);
                ok.that(PropertyKit.coins(p) < before, "paid for: " + before + " -> " + PropertyKit.coins(p));
                ok.that(p.getInventory().countItem(PropertyDeeds.DEED.get()) == 1, "the deed in the pack");
                if (!mine.isEmpty()) {
                    int[] b = mine.get(0).box();
                    BlockPos corner = new BlockPos(b[0], t.heart().getY(), b[1]);
                    ok.that(level.getBlockState(corner).is(Blocks.OAK_FENCE) && level.getBlockState(corner.above()).is(Blocks.LANTERN), "a post with a lantern at its corner: "
                        + level.getBlockState(corner));
                    ok.that(LandPlots.owns(t.id(), p.getUUID(), new BlockPos((b[0] + b[2]) / 2, 0, (b[1] + b[3]) / 2)), "the player owns the ground");
                    TownPlan.Lot l = null;
                    for (TownPlan.Lot q : TownPlan.lots()) if (q.kind() == TownPlan.Kind.LOT && q.x() == lot[0] && q.z() == lot[1]) l = q;
                    ok.that(l != null && Villages.lotTaken(t.id(), l), "held off the town's builders");
                    ok.that(!LandPlots.lotProblemForTests(t.id(), lot[0], lot[1]).isEmpty(), "and nobody else may buy it");
                }
            }
            PropertyKit.finish(helper, ok);
        });
    }

    /** The limits: a friend holds one lot; the town's civic lots and the growth ring are never for sale. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lp03_limits")
    public static void lp03_limits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12210000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 600);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            String one = a == null ? "!" : LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            ok.that(!one.startsWith("!"), "the first lot: " + one);
            int[] b = LandPlots.freeLotForTests(t.id(), "LOT");
            String two = b == null ? "!none" : LandPlots.buyForTests(p, "LOT", b[0], b[1]);
            ok.that(two.startsWith("!") && two.contains("citizen"), "a friend of the town holds one lot: " + two);
            TownPlan.Lot civic = null, outer = null;
            for (TownPlan.Lot l : TownPlan.lots()) {
                if (l.kind() == TownPlan.Kind.LOT && l.use().equals("civic") && civic == null) civic = l;
                if (l.kind() == TownPlan.Kind.LOT && l.use().equals("home") && TownPlan.ringOf(l) == 2 && outer == null) outer = l;
            }
            String c = civic == null ? "" : LandPlots.lotProblemForTests(t.id(), civic.x(), civic.z());
            String o = outer == null ? "" : LandPlots.lotProblemForTests(t.id(), outer.x(), outer.z());
            Kit.log("[VT] lp03 civic: " + c + " | outer: " + o);
            ok.that(c.contains("kept for the town"), "the trades' lots on the square are kept: " + c);
            ok.that(!o.isEmpty(), "the ring kept clear for the town to grow into is not for sale: " + o);
            PropertyKit.finish(helper, ok);
        });
    }

    /** A title is what a farm on bought land checks (WP-B's Premises.holdsTitle): inside yes, over its edge no. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lp04_title")
    public static void lp04_title(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12220000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            LandPlots.Title title = LandPlots.of(t.id(), p.getUUID()).get(0);
            int[] b = title.box();
            ok.that(Premises.holdsTitle(level, t.id(), p.getUUID(), b[0] + 1, b[1] + 1, b[2] - 1, b[3] - 1), "a field inside the title is the player's");
            ok.that(!Premises.holdsTitle(level, t.id(), p.getUUID(), b[0] - 3, b[1], b[2], b[3]), "a field over its edge is not");
            ok.that(!Premises.holdsTitle(level, t.id(), UUID.randomUUID(), b[0] + 1, b[1] + 1, b[2] - 1, b[3] - 1), "nor anybody else's");
            PropertyKit.finish(helper, ok);
        });
    }

    /** Rates: two in the hundred of the price a week; doubled once the land has stood empty fourteen days; taken on the morning. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lp05_rates")
    public static void lp05_rates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12230000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            LandPlots.Title title = LandPlots.of(t.id(), p.getUUID()).get(0);
            int fresh = LandPlots.ratesForTests(level, t.id(), title.id());
            LandPlots.ageForTests(t.id(), title.id(), 15);
            int empty = LandPlots.ratesForTests(level, t.id(), title.id());
            Kit.log("[VT] lp05 rates fresh " + fresh + " empty " + empty + " (price " + title.price() + ")");
            ok.that(fresh >= 1 && empty >= fresh * 2 - 1, "doubled once empty a fortnight: " + fresh + " -> " + empty);
            int carried = PropertyKit.coins(p);
            com.jrpetty.mcassistant.entity.PropertyDay.morning(level, t.v(), level.getDayTime() / 24000L);
            int after = PropertyKit.coins(p);
            boolean owes = !com.jrpetty.mcassistant.entity.Debts.of(t.id(), p.getUUID()).isEmpty();
            ok.that(after < carried || owes, "the rates taken by standing order, or on the debts: " + carried + " -> " + after + ", owes " + owes);
            PropertyKit.finish(helper, ok);
        });
    }

    /** Two plots side by side merge into one title (a citizen's); not while households wait for a roof. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lp06_merge")
    public static void lp06_merge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12240000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 900);
            PropertyKit.citizen(t, p);
            int[] d = LandPlots.freeLotForTests(t.id(), "DOUBLE");
            ok.that(d != null, "a double frontage free");
            if (d != null) {
                int[] box = LandPlots.parcelForTests("DOUBLE", d[0], d[1]);
                // the two lots of the double bought one at a time
                String a = LandPlots.buyForTests(p, "LOT", d[0], d[1]);
                int ox = box[0] + TownPlan.LOT / 2 == d[0] ? box[2] - TownPlan.LOT / 2 : box[0] + TownPlan.LOT / 2;
                int oz = box[1] + TownPlan.LOT / 2 == d[1] ? box[3] - TownPlan.LOT / 2 : box[1] + TownPlan.LOT / 2;
                if (box[2] - box[0] + 1 == TownPlan.LOT) ox = d[0];
                if (box[3] - box[1] + 1 == TownPlan.LOT) oz = d[1];
                String b = LandPlots.buyForTests(p, "LOT", ox, oz);
                Kit.log("[VT] lp06 " + a + " | " + b);
                List<LandPlots.Title> mine = LandPlots.of(t.id(), p.getUUID());
                ok.that(mine.size() == 2, "two titles: " + mine.size());
                if (mine.size() == 2) {
                    PlayerLife.open(p, "land", LifePage.args("tab", "mine"));
                    LandPlots.PRESSURE_FOR_TESTS = true;
                    PlayerLife.act(p, "land", "merge", LifePage.args("a", mine.get(0).id(), "b", mine.get(1).id()));
                    String no = PlayerLife.messageForTests(p);
                    ok.that(no.startsWith("!") && LandPlots.of(t.id(), p.getUUID()).size() == 2, "not under housing pressure: " + no);
                    LandPlots.PRESSURE_FOR_TESTS = false;
                    PlayerLife.act(p, "land", "merge", LifePage.args("a", mine.get(0).id(), "b", mine.get(1).id()));
                    String yes = PlayerLife.messageForTests(p);
                    List<LandPlots.Title> now = LandPlots.of(t.id(), p.getUUID());
                    ok.that(now.size() == 1 && now.get(0).cells().length == 2, "merged into one title of two lots: " + yes);
                }
            }
            PropertyKit.finish(helper, ok);
        });
    }

    /** The builder: its map shows the bought lot as the player's, the gallery fits it, and step 3 sends the building's blocks. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lp07_builder")
    public static void lp07_builder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 12250000;
        PropertyKit.T t = PropertyKit.town(helper, level, x, Z, 4, 52);
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = PropertyKit.player(helper, t.heart(), 300);
            PropertyKit.friend(t, p);
            int[] a = LandPlots.freeLotForTests(t.id(), "LOT");
            LandPlots.buyForTests(p, "LOT", a[0], a[1]);
            LandPlots.Title title = LandPlots.of(t.id(), p.getUUID()).get(0);
            PlayerLife.open(p, "build", LifePage.args("for", "home"));
            CompoundTag page = PlayerLife.pageForTests(p);
            ok.that(PropertyKit.blocks(page, "lotmap") == 1, "the plot step is a map of lots");
            int yours = 0;
            for (var e : page.getList("blocks", 10)) {
                CompoundTag b = (CompoundTag) e;
                if (!b.getString("t").equals("lotmap")) continue;
                for (var l : b.getList("lots", 10)) if (((CompoundTag) l).getInt("s") == 4) yours++;
            }
            ok.that(yours == 1, "the bought lot shows as the player's: " + yours);
            PlayerLife.act(p, "build", "site", LifePage.args("site", title.id()));
            String chose = PlayerLife.messageForTests(p);
            ok.that(chose.startsWith("Chosen"), "the lot chosen: " + chose);
            PlayerLife.act(p, "build", "bp", LifePage.args("bp", "house"));
            PlayerLife.act(p, "build", "step", LifePage.args("s", 3));
            CompoundTag see = PlayerLife.pageForTests(p);
            int[] cells = see.getCompound("bghost").getIntArray("cells");
            Kit.log("[VT] lp07 " + chose + " | ghost cells " + cells.length / 5 + " | lot " + java.util.Arrays.toString(see.getCompound("bghost").getIntArray("lot")));
            ok.that(cells.length / 5 > 50, "the building's blocks sent to be drawn where it would stand: " + cells.length / 5);
            ok.that(see.getCompound("bghost").getIntArray("lot").length == 5, "and its lot outlined");
            int[] direct = BuildPicker.cellsForTests(level, "house", t.heart().offset(a[0], 0, a[1]), Direction.NORTH);
            ok.that(direct.length > 0 && direct.length % 5 == 0, "five numbers a block");
            PropertyKit.finish(helper, ok);
        });
    }
}
