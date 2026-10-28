package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Beds that stood empty while folk had none, and a treasury that ran dry while the purses swelled.
 *
 * <p>A hundred-folk village of the long game had eighty-nine beds made up and sixty-six folk in them:
 * a house was let to one household and every bed in it was that household's, a couple in a cottage
 * leaving two empty and a widower five, while a grown child's family, wed and living at the parents',
 * never counted as waiting for a house at all. And its treasury paid out every coin it held in wages
 * each morning, so it held a few coins while the purses held three thousand.
 *
 * <ul>
 * <li><b>bt01</b>: one house, six folk with nowhere to live. The household takes its own beds; the
 *     rest lodge in the beds it has no need of, and no more; one of the household's own wanting a bed
 *     has a lodger's back; a second house built, everybody has a bed and no two share one.</li>
 * <li><b>bt02</b>: a grown child who married lives at its parents' with its partner and their child:
 *     the three wait for a house of their own (the builders are told), and move into the next one
 *     together, the parents staying put.</li>
 * <li><b>bt03</b>: a tenth of every wage stays in the treasury as the village's tax (the poor pay none,
 *     the odd part of a coin carried); over several paydays on less coming in than goes out the
 *     treasury pays the wages and never goes below nothing, no coin comes from nowhere, the books show
 *     the tax; and a treasury holding a week's wages takes none.</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 232,000 to 235,000, z 52,000), at seven in the morning past the
 * day's payday so the village's own morning does not pay or charge anybody under the test, and
 * everything it checks happens inside one callback.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BedsAndTreasuryGameTests {

    private static final String EMPTY = "empty";

    /** A village's first folk on clean ground at x, z, in broad day past the payday. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null, "a village");
        return f;
    }

    /** Another folk of the same village, standing here. */
    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
        return f;
    }

    /** The village's n-th house, a plain one with its four beds, put up south-west of the heart. */
    private static BlockPos house(ServerLevel level, UUID village, BlockPos heart, int n) {
        BlockPos at = Kit.surface(level, heart.getX() - 24 + 14 * n, heart.getZ() + 22);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", at, Direction.NORTH);
        Villages.recountBeds(village);
        return at;
    }

    private static String who(List<VillageFolkEntity> folk) {
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity f : folk) names.add(f.displayNameCap() + (f.bedPos() == null ? " (no bed)" : " " + f.bedPos().toShortString()));
        return String.join(", ", names);
    }

    // ============================================================ beds

    /**
     * One house of four beds, six folk with nowhere to live. One household moves in and takes its own
     * bed; under the old rule every other bed in the house was "someone else's", and the other five
     * slept on their feet. Now three of them lodge in the three beds it has no need of, and the other
     * two do not crowd the household out. One of those two marries into the house: it has a bed of its
     * own there at once, a lodger giving one back. A second house goes up, a household moves in, and
     * then every one of the six has a bed, no two the same.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bt01_spare_beds")
    public static void bt01_spare_beds(GameTestHelper helper) {
        int x = 232000, z = 52000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        List<VillageFolkEntity> folk = new ArrayList<>(List.of(first));
        int[][] at = { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 }, { 3, 3 } };
        for (int[] d : at) folk.add(another(helper, heart.offset(d[0], 0, d[1]), id));
        helper.runAtTickTime(10, () -> {
            for (VillageFolkEntity f : folk) {
                f.life().widowed();                      // six single folk: one household to a house
                f.forgetBed();
            }
            // The house goes up now, in the callback: the village's own round of its homes cannot have
            // let it already.
            BlockPos one = house(level, id, heart, 0);
            Homes.tickForTests(level, v);
            List<BlockPos> beds = Homes.bedsForTests(level, id, one);
            List<VillageFolkEntity> housed = new ArrayList<>(), homeless = new ArrayList<>();
            for (VillageFolkEntity f : folk) (Homes.homeOf(f) != null ? housed : homeless).add(f);
            Kit.log("bt01 one house, " + beds.size() + " beds; housed " + who(housed) + "; nowhere to live " + who(homeless)
                + "; " + Homes.line(level, id));
            helper.assertTrue(beds.size() == 4, "the house has its four beds made up: " + beds.size());
            helper.assertTrue(housed.size() == 1 && homeless.size() == 5, "one household to the house, five waiting: " + housed.size());
            // The old rule: every bed in a house somebody lives in was that household's, needed or not.
            int theirs = 0;
            for (BlockPos b : beds) if (Homes.someoneElses(id, b, homeless.get(0))) theirs++;
            helper.assertTrue(theirs == beds.size(), "by the old rule all " + beds.size() + " beds were someone else's: " + theirs);

            // The household's own bed first.
            VillageFolkEntity member = housed.get(0);
            helper.assertTrue(Homes.findABedForTests(member) && beds.contains(member.bedPos()),
                "the household sleeps in its own house: " + member.bedPos());
            // Then the folk with nowhere: as many as the house has beds to spare, and no more.
            List<VillageFolkEntity> lodgers = new ArrayList<>(), without = new ArrayList<>();
            for (VillageFolkEntity f : homeless) (Homes.findABedForTests(f) ? lodgers : without).add(f);
            Kit.log("bt01 lodging: " + who(lodgers) + "; still without " + who(without) + "; " + Homes.line(level, id));
            helper.assertTrue(lodgers.size() == beds.size() - 1, "three lodge in the beds the household can spare: " + lodgers.size());
            for (VillageFolkEntity f : lodgers) {
                helper.assertTrue(beds.contains(f.bedPos()) && Homes.lodging(f), f.displayNameCap() + " lodges in the house: " + f.bedPos());
            }
            for (VillageFolkEntity f : without) helper.assertTrue(f.bedPos() == null, "the rest take no bed from the household");
            String says = Homes.talk(lodgers.get(0));
            Kit.log("bt01 a lodger says: " + says);
            helper.assertTrue(says.contains("spare bed"), "and says where it sleeps: " + says);
            helper.assertTrue(Homes.line(level, id).contains("lodging"), "the homes line counts the lodgers: " + Homes.line(level, id));

            // One of those with no bed marries into the house: one of the household now, it has a bed there at once.
            VillageFolkEntity wed = without.get(0);
            member.life().partnerWith(wed.getUUID(), wed.displayNameCap());
            wed.life().partnerWith(member.getUUID(), member.displayNameCap());
            Homes.tickForTests(level, v);
            helper.assertTrue(one.equals(Homes.homeOf(wed)), "a partner moves in: " + Homes.homeOf(wed));
            boolean got = Homes.findABedForTests(wed);
            int stillIn = 0;
            VillageFolkEntity gaveUp = null;
            for (VillageFolkEntity f : lodgers) {
                if (f.bedPos() != null && beds.contains(f.bedPos())) stillIn++;
                else if (f.bedPos() == null) gaveUp = f;
            }
            Kit.log("bt01 " + wed.displayNameCap() + " married in: bed " + wed.bedPos() + " (" + got + "); lodgers still in " + stillIn
                + ", gave one back " + (gaveUp == null ? "nobody" : gaveUp.displayNameCap()));
            helper.assertTrue(got && beds.contains(wed.bedPos()), "the household's own has a bed at home: " + wed.bedPos());
            helper.assertTrue(stillIn == lodgers.size() - 1 && gaveUp != null, "a lodger gave one back: " + stillIn + " still in");
            helper.assertTrue(!Homes.findABedForTests(gaveUp) && gaveUp.bedPos() == null,
                "and with the house full of its own and its lodgers there is none for it there");

            // A second house: a household moves in, and then everybody has a bed.
            BlockPos two = house(level, id, heart, 1);
            Homes.tickForTests(level, v);
            List<VillageFolkEntity> order = new ArrayList<>();
            for (VillageFolkEntity f : folk) if (Homes.homeOf(f) != null) order.add(f);       // the households first
            for (VillageFolkEntity f : folk) if (Homes.homeOf(f) == null) order.add(f);
            for (VillageFolkEntity f : order) if (f.bedPos() == null) Homes.findABedForTests(f);
            List<BlockPos> both = new ArrayList<>(beds);
            both.addAll(Homes.bedsForTests(level, id, two));
            Set<BlockPos> taken = new HashSet<>();
            Kit.log("bt01 two houses: " + who(folk) + "; " + Homes.line(level, id));
            for (VillageFolkEntity f : folk) {
                helper.assertTrue(f.bedPos() != null, "everybody has a bed now: " + who(folk));
                helper.assertTrue(taken.add(f.bedPos()), "no two in one bed: " + who(folk));
                helper.assertTrue(both.contains(f.bedPos()), "every one of them in a house: " + f.bedPos());
                BlockPos home = Homes.homeOf(f);
                if (home != null) helper.assertTrue(Homes.bedsForTests(level, id, home).contains(f.bedPos()),
                    f.displayNameCap() + " sleeps in its own house");
                else helper.assertTrue(Homes.lodging(f), f.displayNameCap() + " lodges till it has a house");
            }
            helper.succeed();
        });
    }

    /**
     * A grown child who married lives at its parents' with its partner and their baby: they wait for a
     * house of their own (only the unwed used to: this family never moved out, and the waiting list said
     * nobody was waiting), the builders are told a house is wanted, and when one stands empty the three
     * move into it together while the parents stay where they are.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bt02_grown_couple")
    public static void bt02_grown_couple(GameTestHelper helper) {
        int x = 233500, z = 52000;
        VillageFolkEntity mother = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        VillageFolkEntity suitor = another(helper, heart.west(2), id);
        helper.runAtTickTime(10, () -> {
            for (VillageFolkEntity f : List.of(mother, father, suitor)) f.life().widowed();
            mother.life().partnerWith(father.getUUID(), father.displayNameCap());
            father.life().partnerWith(mother.getUUID(), mother.displayNameCap());
            // The house goes up now, the couple already together: a couple before a single folk.
            BlockPos one = house(level, id, heart, 0);
            Homes.tickForTests(level, v);
            helper.assertTrue(one.equals(Homes.homeOf(mother)) && one.equals(Homes.homeOf(father)), "the parents' house");
            // Their child, grown up.
            mother.insertItem(new ItemStack(Items.BREAD, 2));
            father.insertItem(new ItemStack(Items.BREAD, 2));
            VillageFolkEntity child = mother.raiseChildWith(father);
            helper.assertTrue(child != null, "a child born");
            Homes.tickForTests(level, v);
            helper.assertTrue(one.equals(Homes.homeOf(child)), "it lives with its parents");
            child.childhoodForTests(VillageFolkEntity.GROW_DAYS + 2);
            helper.assertTrue(!child.isBaby(), "and grows up");
            // It marries; its partner moves in with it, at the parents'.
            child.life().partnerWith(suitor.getUUID(), suitor.displayNameCap());
            suitor.life().partnerWith(child.getUUID(), child.displayNameCap());
            Homes.tickForTests(level, v);
            helper.assertTrue(one.equals(Homes.homeOf(suitor)), "the partner moves in at the parents': " + Homes.homeOf(suitor));
            // And a baby of their own.
            child.insertItem(new ItemStack(Items.BREAD, 2));
            suitor.insertItem(new ItemStack(Items.BREAD, 2));
            VillageFolkEntity baby = child.raiseChildWith(suitor);
            helper.assertTrue(baby != null, "a grandchild born");
            Homes.tickForTests(level, v);
            int[] c = Homes.counts(level, id);
            boolean wanted = Homes.wantsAHouse(level, id);
            Kit.log("bt02 at the parents': " + Homes.membersForTests(id, one).size() + " under one roof; waiting " + c[1]
                + ", a house wanted " + wanted + "; " + Homes.line(level, id));
            helper.assertTrue(one.equals(Homes.homeOf(baby)), "the baby at its grandparents' too");
            helper.assertTrue(c[1] >= 1, "the young family waits for a house of its own: " + c[1] + " waiting");
            helper.assertTrue(wanted, "and the builders are told a house is wanted");
            // A house stands empty: the three move into it together.
            BlockPos two = house(level, id, heart, 1);
            Homes.tickForTests(level, v);
            List<UUID> inTwo = Homes.membersForTests(id, two), inOne = Homes.membersForTests(id, one);
            Kit.log("bt02 the new house " + two.toShortString() + ": " + inTwo.size() + " folk; the parents' " + one.toShortString() + ": "
                + inOne.size() + "; " + child.displayNameCap() + " says: " + Homes.talk(child));
            helper.assertTrue(inTwo.contains(child.getUUID()) && inTwo.contains(suitor.getUUID()) && inTwo.contains(baby.getUUID()),
                "the young family moves out together: " + inTwo);
            helper.assertTrue(inOne.contains(mother.getUUID()) && inOne.contains(father.getUUID())
                && !inOne.contains(child.getUUID()) && !inOne.contains(suitor.getUUID()) && !inOne.contains(baby.getUUID()),
                "and the parents stay where they are: " + inOne);
            helper.succeed();
        });
    }

    // ============================================================ the treasury

    /**
     * The village's tax, and a treasury that pays its wages and is never emptied by them. Eight hands at a
     * smith's rate; the treasury starts with three days' wages and takes in half a day's each morning
     * after (less than goes out). Every payday: every coin that leaves the treasury is in a purse (nothing
     * from nowhere), what stays is at least the day's tax, and the treasury is never below nothing. Over
     * them all, a tenth of the wages (to within the odd part of a coin each hand carries) stayed in the
     * treasury, and the books say so. A poor folk pays none; a treasury with a week's wages takes none.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bt03_tax")
    public static void bt03_tax(GameTestHelper helper) {
        int x = 235000, z = 52000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        List<VillageFolkEntity> hands = new ArrayList<>(List.of(first));
        int[][] at = { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 }, { 3, 3 }, { -3, 3 }, { 3, -3 } };
        for (int[] d : at) hands.add(another(helper, heart.offset(d[0], 0, d[1]), id));
        helper.runAtTickTime(10, () -> {
            for (VillageFolkEntity f : hands) {
                f.setJob(StationTask.SMITH);
                f.spend(f.purse());
                f.earn(20);                                  // getting by: the tax is theirs to pay
            }
            int bill = Market.wageBill(id);
            Ledger.takeCoins(id, Ledger.coins(id));
            Ledger.addCoins(id, 3 * bill);
            int[] start = Economy.moneyTodayForTests(id);
            int coinStart = Ledger.coins(id);
            int pursesStart = 0;
            for (VillageFolkEntity f : hands) pursesStart += f.purse();
            int added = 0;
            StringBuilder days = new StringBuilder();
            for (int d = 0; d < 8; d++) {
                int t0 = Ledger.coins(id), p0 = 0;
                for (VillageFolkEntity f : hands) p0 += f.purse();
                int[] m0 = Economy.moneyTodayForTests(id);
                int paid = Market.payWages(level, v);
                int t1 = Ledger.coins(id), p1 = 0;
                for (VillageFolkEntity f : hands) p1 += f.purse();
                int[] m1 = Economy.moneyTodayForTests(id);
                int wages = m1[0] - m0[0], tax = m1[1] - m0[1];
                days.append(String.format("day %d: treasury %d -> %d, wages %d (tax %d, paid %d, share %d%%), purses %d -> %d; ",
                    d, t0, t1, wages, tax, paid, Market.lastShare(id), p0, p1));
                helper.assertTrue(t1 >= 0, "the treasury never goes below nothing: " + days);
                helper.assertTrue(t0 - t1 == p1 - p0 && paid == p1 - p0, "every coin out of the treasury is in a purse: " + days);
                helper.assertTrue(wages - tax == paid, "the wages are what was paid and the tax on it: " + days);
                helper.assertTrue(t1 >= tax, "the day's tax stays in the treasury: " + days);
                // The morning's takings, short of the wages: half a day's.
                Ledger.addCoins(id, bill / 2);
                added += bill / 2;
            }
            int[] end = Economy.moneyTodayForTests(id);
            int wages = end[0] - start[0], tax = end[1] - start[1];
            int pursesEnd = 0;
            for (VillageFolkEntity f : hands) pursesEnd += f.purse();
            Kit.log("bt03 bill " + bill + "; " + days + "all told: wages " + wages + ", tax " + tax + ", treasury " + coinStart + " -> "
                + Ledger.coins(id) + " (" + added + " taken in), purses " + pursesStart + " -> " + pursesEnd);
            helper.assertTrue(tax > 0, "the tax came in: " + tax);
            helper.assertTrue(tax * 10 <= wages && tax * 10 >= wages - 10 * hands.size(),
                "a tenth of the wages, to within the odd part of a coin a hand: " + tax + " of " + wages);
            helper.assertTrue(coinStart + added + pursesStart == Ledger.coins(id) + pursesEnd, "no coin from nowhere");
            // The books.
            Economy.closeTheDay(level, v, level.getDayTime() / 24000L);
            String page = Economy.page(level, v), line = Economy.line(id);
            Kit.log("bt03 the books: " + line.replace('\n', ' ') + " | " + page.replace('\n', ' '));
            helper.assertTrue(Economy.taxYesterday(id) == end[1] && page.contains("in tax") && line.contains("tax " + end[1]),
                "the Economy page and the status line show the tax");

            // A treasury with a week's wages takes no tax: every coin of the wage goes into the purse.
            Ledger.addCoins(id, (Market.TAX_TILL_DAYS + 4) * bill);       // over a week's, at any rate the leader pays
            int[] m0 = Economy.moneyTodayForTests(id);
            int p0 = 0;
            for (VillageFolkEntity f : hands) p0 += f.purse();
            int paid = Market.payWages(level, v);
            int[] m1 = Economy.moneyTodayForTests(id);
            int p1 = 0;
            for (VillageFolkEntity f : hands) p1 += f.purse();
            Kit.log("bt03 with a week's wages in hand: treasury " + Ledger.coins(id) + ", wages " + (m1[0] - m0[0]) + ", tax " + (m1[1] - m0[1])
                + ", taxing " + Market.taxing(id) + ", purses " + p0 + " -> " + p1);
            helper.assertTrue(!Market.taxing(id) && m1[1] == m0[1] && p1 - p0 == m1[0] - m0[0] && paid == p1 - p0,
                "no tax taken while the treasury holds a week's wages");

            // The poor pay none; the rest a tenth, the odd part of a coin carried to the next payday.
            VillageFolkEntity poor = hands.get(hands.size() - 1), rich = hands.get(0);
            poor.spend(poor.purse());
            for (int i = 0; i < poor.getInventoryItems().size(); i++) poor.getInventoryItems().set(i, ItemStack.EMPTY);
            for (EquipmentSlot slot : EquipmentSlot.values()) poor.setItemSlot(slot, ItemStack.EMPTY);
            int fromPoor = Market.taxOnForTests(poor, 30);
            Market.resetForTests();                            // a fresh carry for the rich one
            int fromRich = Market.taxOnForTests(rich, 30);
            int[] small = new int[4];
            for (int i = 0; i < 4; i++) small[i] = Market.taxOnForTests(rich, 3);
            Kit.log("bt03 tax on 30: the poor one (" + Wealth.tier(poor).label + ") " + fromPoor + ", " + rich.displayNameCap() + " ("
                + Wealth.tier(rich).label + ") " + fromRich + "; on 3 a day four days running: " + java.util.Arrays.toString(small));
            helper.assertTrue(Wealth.tier(poor) == Wealth.Tier.POOR && fromPoor == 0, "the poor pay no tax: " + fromPoor);
            helper.assertTrue(fromRich == 3, "a tenth of thirty is three: " + fromRich);
            helper.assertTrue(small[0] + small[1] + small[2] == 0 && small[3] == 1,
                "three a day pays its coin on the fourth morning: " + java.util.Arrays.toString(small));
            helper.succeed();
        });
    }
}
