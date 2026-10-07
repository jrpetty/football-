package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Intel;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Militia;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarFooting;
import com.jrpetty.mcassistant.entity.WarStores;
import com.jrpetty.mcassistant.entity.WarWorks;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A town on a war footing (WarFooting, Militia, WarWorks, WarStores): on its guard against a feuding neighbour it
 * wants more guards (as many as the scouts' report of the enemy says, or a cautious guess without one), out of the
 * trades it can spare and never out of its fields, and calls for volunteers; it enrols a militia that drills and is
 * called up and armed at war; it puts its wall and its armoury at the head of what it builds; its leader keeps more
 * food against a siege; and at peace everybody goes back to their trades and the arms to the stores.
 *
 * <p>Each test has its own batch and its own ground: x 800000 to 819999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WarFootingGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A town of folk at these trades, raised at x, z (the first founds it). */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, StationTask... trades) {
        Kit.reset(level);
        WarFooting.resetForTests();
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos at = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (StationTask t : trades) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null, "a folk raised");
            f.setJob(t);
            folk.add(f);
        }
        return folk;
    }

    /** Another town, far enough off to be its own, to be at odds with. */
    private static Villages.Village rival(ServerLevel level, int x) {
        Kit.prepare(level, x + 400, Z, 8);
        return Villages.found(level, Kit.surface(level, x + 400, Z));
    }

    private static StationTask[] trades(Object... countThenTrade) {
        List<StationTask> out = new ArrayList<>();
        for (int i = 0; i < countThenTrade.length; i += 2) {
            for (int n = 0; n < (Integer) countThenTrade[i]; n++) out.add((StationTask) countThenTrade[i + 1]);
        }
        return out.toArray(new StationTask[0]);
    }

    private static int count(UUID v, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v)) if (a.stationTask() == t) n++;
        return n;
    }

    /** A trade's share of the town, in hands, as the village's own sums have it (Villages.share is have less it). */
    private static double shareOf(UUID v, StationTask t) {
        return count(v, t) - Villages.share(v, t);
    }

    // ------------------------------------------------------------------ wr01: more guards, by the scouts' report

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wr01_guards")
    public static void wr01_guards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 800000;
        List<VillageFolkEntity> folk = town(helper, level, x, trades(7, StationTask.FARM, 3, StationTask.MINE, 5, StationTask.WOOD,
            1, StationTask.SMELT, 1, StationTask.GUARD, 1, StationTask.RANCH, 1, StationTask.FISH, 1, StationTask.HUNT));
        UUID v = folk.get(0).ownerId();
        long day = level.getDayTime() / 24000L;
        double guardsPeace = WarFooting.guardShare(v), farmsPeace = shareOf(v, StationTask.FARM), woodPeace = shareOf(v, StationTask.WOOD);
        int guardsBefore = count(v, StationTask.GUARD);
        Kit.log(String.format("wr01 peace: footing %s, watch share %.2f, farmers' %.2f, woodcutters' %.2f, %d guard(s)",
            WarFooting.footing(v), guardsPeace, farmsPeace, woodPeace, guardsBefore));
        helper.assertTrue(WarFooting.footing(v) == Wars.Footing.PEACE, "at peace to begin with");

        // A feud with a neighbour: on its guard. No scout has been: a cautious guess.
        Villages.Village other = rival(level, x);
        Ledger.relate(v, other.id(), -60);
        WarFooting.resetForTests();
        WarFooting.Reckoning guess = WarFooting.reckon(v, day);
        double guessed = WarFooting.guardShare(v);
        Kit.log(String.format("wr01 no report: strength %.1f (guessed %s: %s), watch share %.2f", guess.strength(), guess.guessed(), guess.how(), guessed));
        helper.assertTrue(WarFooting.footing(v) == Wars.Footing.TENSION, "a feud puts the town on its guard: " + WarFooting.footing(v));
        helper.assertTrue(guess.guessed(), "with no scouts' report the leader guesses");

        // The scouts' report of a strong watch: the town wants more guards for it.
        Intel.file(v, new Intel.Report(other.id(), day, 30, 9, 4, 3, 1, 2, 12, "a strong watch"));
        WarFooting.resetForTests();
        WarFooting.Reckoning strong = WarFooting.reckon(v, day);
        double guardsTension = WarFooting.guardShare(v), farmsTension = shareOf(v, StationTask.FARM), woodTension = shareOf(v, StationTask.WOOD);
        Kit.log(String.format("wr01 on its guard (report of 9 guards, 4 in iron): strength %.1f, watch share %.2f, farmers' %.2f, woodcutters' %.2f",
            strong.strength(), guardsTension, farmsTension, woodTension));
        // A weak one: fewer.
        Intel.file(v, new Intel.Report(other.id(), day, 30, 1, 0, 0, 0, 1, 12, "hardly a watch"));
        WarFooting.resetForTests();
        WarFooting.Reckoning weak = WarFooting.reckon(v, day);
        WarFooting.Shares weakShares = WarFooting.shares(v);
        // And the report gone stale: a guess again.
        Intel.file(v, new Intel.Report(other.id(), day - WarFooting.STALE_DAYS - 3, 30, 9, 4, 3, 1, 2, 12, "long ago"));
        WarFooting.Reckoning stale = WarFooting.reckon(v, day);
        Kit.log(String.format("wr01 weak report: strength %.1f, wants %.2f; stale report: strength %.1f, guessed %s",
            weak.strength(), weakShares == null ? -1 : weakShares.wantGuards(), stale.strength(), stale.guessed()));
        helper.assertTrue(!strong.guessed() && strong.strength() > weak.strength(), "the scouts' report sizes the enemy: "
            + strong.strength() + " against " + weak.strength());
        helper.assertTrue(stale.guessed(), "a stale report makes the leader guess");
        helper.assertTrue(guardsTension > guardsPeace + 0.3, "on its guard the watch's share rises: " + guardsPeace + " to " + guardsTension);
        helper.assertTrue(Math.abs(farmsTension - farmsPeace) < 0.01, "the fields keep every hand: " + farmsPeace + " and " + farmsTension);
        helper.assertTrue(woodTension < woodPeace, "the woods give hands to the watch: " + woodPeace + " to " + woodTension);

        // The strong report again, and the leader's morning: volunteers come forward.
        Intel.file(v, new Intel.Report(other.id(), day, 30, 9, 4, 3, 1, 2, 12, "a strong watch"));
        WarFooting.resetForTests();
        WarFooting.morning(level, Villages.get(v), day);
        int guardsAfter = count(v, StationTask.GUARD);
        List<String> vols = new ArrayList<>();
        for (VillageFolkEntity f : folk) if (WarFooting.volunteeredFrom(f) != null) vols.add(f.displayNameCap() + " (was " + WarFooting.volunteeredFrom(f) + ")");
        Kit.log("wr01 after the morning: " + guardsAfter + " guards (were " + guardsBefore + "); volunteers " + vols
            + "; farmers " + count(v, StationTask.FARM) + "; " + String.join(" | ", WarFooting.page(level, v)));
        helper.assertTrue(guardsAfter > guardsBefore, "more folk are guards than at peace: " + guardsBefore + " to " + guardsAfter);
        helper.assertTrue(!vols.isEmpty(), "the new guards are volunteers, their old trades kept");
        helper.assertTrue(count(v, StationTask.FARM) == 7, "no farmer went to the wall");
        helper.succeed();
    }

    // ------------------------------------------------------------------ wr02: the militia

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wr02_militia")
    public static void wr02_militia(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 802000;
        List<VillageFolkEntity> folk = town(helper, level, x, trades(6, StationTask.FARM, 3, StationTask.MINE, 4, StationTask.WOOD,
            1, StationTask.SMELT, 1, StationTask.GUARD, 1, StationTask.RANCH));
        UUID v = folk.get(0).ownerId();
        Villages.Village vil = Villages.get(v);
        long day = level.getDayTime() / 24000L;
        for (int i = 0; i < 4; i++) WarFooting.storesForTests(level, vil, new ItemStack(Items.IRON_SWORD));
        WarFooting.storesForTests(level, vil, new ItemStack(Items.IRON_CHESTPLATE));
        Villages.Village other = rival(level, x);
        Ledger.relate(v, other.id(), -60);
        WarFooting.morning(level, vil, day);
        Map<UUID, Militia.Member> roll = Militia.members(v);
        Kit.log("wr02 on its guard: " + roll.size() + " enrolled of " + folk.size() + "; " + String.join(" | ", WarFooting.page(level, v)));
        helper.assertTrue(roll.size() >= 2, "the militia is enrolled on a war footing: " + roll.size());
        VillageFolkEntity drilled = null;
        for (VillageFolkEntity f : folk) {
            if (!roll.containsKey(f.getUUID())) continue;
            helper.assertTrue(f.stationTask() != StationTask.GUARD, "the watch is not the militia");
            if (drilled == null) drilled = f;
        }
        helper.assertTrue(drilled != null, "somebody to drill");
        int xpBefore = drilled.xpInTrade(StationTask.GUARD);
        StationTask own = drilled.stationTask();
        helper.assertTrue(Militia.drillForTests(drilled), "it drills");
        Militia.Member after = Militia.member(drilled);
        int xpAfter = drilled.xpInTrade(StationTask.GUARD);
        Kit.log("wr02 drill: " + drilled.displayNameCap() + " (" + own + ") the watch's trade " + xpBefore + " -> " + xpAfter + " xp, drills " + (after == null ? -1 : after.drills()));
        helper.assertTrue(xpAfter > xpBefore, "a turn at the drill teaches the watch's trade: " + xpBefore + " -> " + xpAfter);
        helper.assertTrue(after != null && after.drills() == 1, "the drill is counted");
        helper.assertTrue(drilled.stationTask() == own, "the militia keeps its own trade");

        // War: everybody on the roll called up and armed.
        Wars.begin(v, other.id(), day);
        int guards = count(v, StationTask.GUARD);
        WarFooting.morning(level, vil, day);
        int called = Militia.called(v), fighting = WarFooting.militia(v).size(), armed = 0;
        for (VillageFolkEntity f : folk) if (Militia.calledUp(f) && f.countCarried(s -> s.getItem() instanceof SwordItem) > 0) armed++;
        WarFooting.Cost cost = WarFooting.dailyCost(v);
        int danger = 0;
        for (VillageFolkEntity f : folk) if (f.stationTask() == StationTask.GUARD) danger = Math.max(danger, WarFooting.dangerPay(f));
        Kit.log("wr02 at war: " + called + " called up, " + armed + " armed with a sword; " + fighting + " fight for the town (" + guards
            + " guards); danger money " + danger + "%; a day costs " + cost.dangerPay() + " coin and " + cost.hoursLost() + " hours (" + cost.coins() + " coin)");
        helper.assertTrue(called == Militia.members(v).size() && called >= 2, "at war the militia is called up: " + called);
        helper.assertTrue(fighting > guards, "the militia fights with the watch: " + fighting + " against " + guards + " guards");
        helper.assertTrue(armed >= 1, "armed out of the stores: " + armed);
        helper.assertTrue(danger >= 25, "the watch's pay rises with the danger: " + danger + "%");
        helper.assertTrue(cost.hoursLost() > 0 && cost.coins() > 0, "standing armed costs the town: " + cost);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wr03: peace, and back to their trades

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wr03_peace")
    public static void wr03_peace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 804000;
        List<VillageFolkEntity> folk = town(helper, level, x, trades(6, StationTask.FARM, 3, StationTask.MINE, 5, StationTask.WOOD,
            1, StationTask.SMELT, 1, StationTask.GUARD, 1, StationTask.RANCH, 1, StationTask.FISH));
        UUID v = folk.get(0).ownerId();
        Villages.Village vil = Villages.get(v);
        long day = level.getDayTime() / 24000L;
        for (int i = 0; i < 4; i++) WarFooting.storesForTests(level, vil, new ItemStack(Items.IRON_SWORD));
        int swordsAtPeace = Market.stock(level, v, s -> s.is(Items.IRON_SWORD));
        Map<UUID, StationTask> before = new HashMap<>();
        for (VillageFolkEntity f : folk) before.put(f.getUUID(), f.stationTask());
        Villages.Village other = rival(level, x);
        Ledger.relate(v, other.id(), -60);
        Intel.file(v, new Intel.Report(other.id(), day, 30, 9, 4, 3, 1, 2, 12, "a strong watch"));
        WarFooting.morning(level, vil, day);
        Wars.begin(v, other.id(), day);
        WarFooting.morning(level, vil, day + 1);
        int guardsAtWar = count(v, StationTask.GUARD), called = Militia.called(v);
        int swordsAtWar = Market.stock(level, v, s -> s.is(Items.IRON_SWORD));
        List<VillageFolkEntity> volunteers = new ArrayList<>();
        for (VillageFolkEntity f : folk) if (WarFooting.volunteeredFrom(f) != null) volunteers.add(f);
        Kit.log("wr03 at war: " + guardsAtWar + " guards (" + volunteers.size() + " volunteers), " + called + " called up; swords in the stores "
            + swordsAtPeace + " -> " + swordsAtWar);
        helper.assertTrue(called >= 2 && !volunteers.isEmpty(), "a militia called up and volunteers on the wall: " + called + ", " + volunteers.size());

        // Peace: the war ended, the feud let go.
        Wars.end(v, other.id());
        Ledger.relate(v, other.id(), 60);
        WarFooting.morning(level, vil, day + 2);
        int back = 0, still = 0;
        for (VillageFolkEntity f : folk) {
            if (f.stationTask() == before.get(f.getUUID())) back++;
            if (Militia.enrolled(f) || WarFooting.volunteeredFrom(f) != null) still++;
        }
        int swordsAtPeaceAgain = Market.stock(level, v, s -> s.is(Items.IRON_SWORD));
        Kit.log("wr03 at peace: footing " + WarFooting.footing(v) + "; " + back + " of " + folk.size() + " at their old trades; " + still
            + " still in the militia or on the wall for the war; " + count(v, StationTask.GUARD) + " guards; swords in the stores " + swordsAtPeaceAgain);
        helper.assertTrue(WarFooting.footing(v) == Wars.Footing.PEACE, "at peace");
        helper.assertTrue(Militia.members(v).isEmpty() && still == 0, "the militia stood down and the volunteers released: " + still);
        helper.assertTrue(back == folk.size(), "everybody back at the trade it had before the war: " + back + " of " + folk.size());
        helper.assertTrue(swordsAtPeaceAgain >= swordsAtPeace, "the arms back in the stores: " + swordsAtPeaceAgain + " of " + swordsAtPeace);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wr04: the fortifications

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wr04_fortify")
    public static void wr04_fortify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 806000;
        List<VillageFolkEntity> folk = town(helper, level, x, trades(6, StationTask.FARM, 2, StationTask.MINE, 3, StationTask.WOOD,
            1, StationTask.SMELT, 1, StationTask.RANCH, 1, StationTask.FISH));
        UUID v = folk.get(0).ownerId();
        Villages.Village vil = Villages.get(v);
        // A Wood Age town: on its guard it stands a palisade of logs on the wall's line.
        List<String> peaceList = Villages.projectsWanted(v);
        Villages.Village other = rival(level, x);
        Ledger.relate(v, other.id(), -60);
        List<String> works = WarWorks.worksForTests(v);
        for (int i = 0; i < 4; i++) WarFooting.storesForTests(level, vil, new ItemStack(Items.OAK_LOG, 64));   // a good pile of logs
        int left = WarWorks.workForTests(level, vil, "palisade");
        Kit.log("wr04 wood age, on its guard: works " + works + "; palisade " + left + " logs still to stand; stands " + WarWorks.palisadeStands(v)
            + "; projects at peace " + peaceList);
        helper.assertTrue(works.contains("palisade"), "a town with no wall stands a palisade: " + works);
        helper.assertTrue(WarWorks.palisadeStands(v), "the first logs of the palisade stood");

        // The Stone Age: the wall at the head of what the town builds, the palisade down first.
        Villages.ageForTests(v, Villages.Age.STONE);
        List<String> waiting = Villages.projectsWanted(v);
        helper.assertTrue(!waiting.contains("fortify"), "the stone wall waits for the palisade to come down: " + waiting);
        for (int i = 0; i < 40 && WarWorks.palisadeStands(v); i++) WarWorks.workForTests(level, vil, "down");
        List<String> wanted = Villages.projectsWanted(v);
        String why = Villages.whyBuild(v, "fortify");
        Kit.log("wr04 stone age, on its guard: projects " + wanted + "; why the wall: " + why);
        helper.assertTrue(!WarWorks.palisadeStands(v), "the palisade taken down");
        int at = wanted.indexOf("fortify");
        helper.assertTrue(at >= 0 && at <= 1, "a fortification project is wanted at the head of the list on its guard: " + wanted);
        helper.assertTrue(why != null && why.contains("guard"), "and says why: " + why);
        helper.assertTrue(wanted.contains("trainingyard"), "a training yard is wanted too: " + wanted);

        // At peace again: the wall is no longer the first thing wanted.
        Ledger.relate(v, other.id(), 60);
        List<String> after = Villages.projectsWanted(v);
        Kit.log("wr04 at peace: projects " + after);
        helper.assertTrue(after.indexOf("fortify") >= at, "at peace the wall goes back to its place in the list: " + after);
        helper.assertTrue(!after.contains("trainingyard"), "no training yard wanted at peace: " + after);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wr05: the siege stores

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wr05_siege")
    public static void wr05_siege(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 808000;
        List<VillageFolkEntity> folk = town(helper, level, x, trades(6, StationTask.FARM, 2, StationTask.MINE, 2, StationTask.WOOD,
            1, StationTask.SMELT, 1, StationTask.FISH));
        UUID v = folk.get(0).ownerId();
        Villages.Village vil = Villages.get(v);
        long day = level.getDayTime() / 24000L;
        for (int i = 0; i < 4; i++) WarFooting.storesForTests(level, vil, new ItemStack(Items.BREAD, 64));
        WarFooting.storesForTests(level, vil, new ItemStack(Items.SUGAR_CANE, 12));
        double peace = Leader.reserveDays(v);
        Villages.Village other = rival(level, x);
        Ledger.relate(v, other.id(), -60);
        double tension = Leader.reserveDays(v);
        Wars.begin(v, other.id(), day);
        double war = Leader.reserveDays(v);
        Leader.morning(level, vil, day);
        Leader.Plan plan = Leader.plan(v);
        Leader.Books books = Leader.books(v);
        int keepPeace = Math.max(256, 3 * Villages.larderForBirth(v));
        int keepWar = WarStores.foodKeep(v, keepPeace);
        WarFooting.morning(level, vil, day);
        int paper = Market.stock(level, v, s -> s.is(Items.PAPER));
        Kit.log(String.format("wr05 food kept: %.1f days at peace, %.1f on its guard, %.1f at war; the plan %s (%.1f days' food); "
                + "market day keeps %d -> %d; farmers wanted x%.2f; paper for bandages %d (wants %d)",
            peace, tension, war, plan, books == null ? -1.0 : books.days(), keepPeace, keepWar,
            Leader.foodFactor(v, StationTask.FARM), paper, WarStores.bandagesWanted(v)));
        helper.assertTrue(war > tension && tension > peace, "the leader keeps more food on a war footing: " + peace + ", " + tension + ", " + war);
        helper.assertTrue(plan == Leader.Plan.WAR, "at war the leader's plan is WAR: " + plan);
        helper.assertTrue(keepWar >= keepPeace, "no food sold out of the siege reserve: " + keepPeace + " -> " + keepWar);
        helper.assertTrue(paper >= 3, "paper pressed for bandages: " + paper);
        helper.succeed();
    }

    // ------------------------------------------------------------------ wr06: the armoury and the training yard

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wr06_armoury")
    public static void wr06_armoury(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 810000;
        List<VillageFolkEntity> folk = town(helper, level, x, trades(5, StationTask.FARM, 2, StationTask.MINE, 3, StationTask.WOOD,
            1, StationTask.SMELT, 2, StationTask.GUARD, 1, StationTask.RANCH));
        UUID v = folk.get(0).ownerId();
        Villages.Village vil = Villages.get(v);
        long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + 2000L);
        // The armoury and the yard, put up beside the square.
        BlockPos heart = vil.centre();
        BlockPos armoury = Kit.surface(level, heart.getX() + 22, heart.getZ());
        BlockPos yard = Kit.surface(level, heart.getX() - 22, heart.getZ());
        BuildGoal.stamp(level, "armoury", armoury, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        BuildGoal.stamp(level, "trainingyard", yard, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v, "armoury", armoury, Direction.NORTH);
        Ledger.built(v, "trainingyard", yard, Direction.NORTH);
        for (int i = 0; i < 6; i++) WarFooting.storesForTests(level, vil, new ItemStack(Items.IRON_SWORD));
        for (int i = 0; i < 3; i++) WarFooting.storesForTests(level, vil, new ItemStack(Items.IRON_HELMET));
        Villages.Village other = rival(level, x);
        Ledger.relate(v, other.id(), -60);
        WarFooting.morning(level, vil, day);
        int storesBefore = Market.stock(level, v, s -> s.is(Items.IRON_SWORD));
        int carried = WarWorks.fillForTests(level, vil);
        int racked = WarWorks.inArmoury(level, v, s -> s.is(Items.IRON_SWORD));
        int storesAfter = Market.stock(level, v, s -> s.is(Items.IRON_SWORD));
        Kit.log("wr06 the armoury: " + Militia.members(v).size() + " in the militia; " + carried + " carried; swords in the racks " + racked
            + ", in the stores " + storesBefore + " -> " + storesAfter);
        helper.assertTrue(racked >= 1, "the couriers fill the armoury from the smith's work in the stores: " + racked);
        helper.assertTrue(storesAfter == storesBefore - racked, "the armoury's racks are not the stores: " + storesBefore + " -> " + storesAfter);
        Wars.begin(v, other.id(), day);
        WarFooting.morning(level, vil, day);
        int fromRacks = racked - WarWorks.inArmoury(level, v, s -> s.is(Items.IRON_SWORD));
        Kit.log("wr06 at war: " + Militia.called(v) + " called up, " + fromRacks + " swords issued out of the armoury");
        helper.assertTrue(fromRacks >= 1, "the militia is armed out of the armoury: " + fromRacks);

        // A guard's turn at the training yard: to its place before a dummy, and the watch's trade learned.
        VillageFolkEntity guard = null;
        for (VillageFolkEntity f : folk) if (f.stationTask() == StationTask.GUARD) { guard = f; break; }
        helper.assertTrue(guard != null, "a guard");
        int xp = guard.xpInTrade(StationTask.GUARD);
        helper.assertTrue(Militia.drillForTests(guard), "the guard drills at the yard");
        int xpAfter = guard.xpInTrade(StationTask.GUARD);
        double fromYard = Math.sqrt(guard.blockPosition().distSqr(yard));
        Kit.log("wr06 the yard: " + guard.displayNameCap() + " " + Math.round(fromYard) + " blocks from the yard's middle; the watch's trade " + xp + " -> " + xpAfter);
        helper.assertTrue(fromYard <= 6, "it drills at the yard: " + fromYard);
        helper.assertTrue(xpAfter > xp, "its skill rises: " + xp + " -> " + xpAfter);
        helper.succeed();
    }
}
