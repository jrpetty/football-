package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Health;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Infirmary;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Neighbourly;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.PoorBox;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Health and care (entity/Health, Infirmary, Neighbourly, PoorBox): colds, the infirmary, the healer, the old
 * looked after, the poor box, housewarmings and the welcome committee.
 *
 * <ul>
 * <li><b>hc01</b>: two minutes out in the rain and a cold is caught (the odds made sure); its work at half pace,
 *     its card and its words say so, it is saved with it; worn out at its work, another; it passes to the one it
 *     lives with; three ill and the chronicle hears of an outbreak (and not before); no more than four a week;
 *     over it, the pace is its own again and nobody is the worse for it.</li>
 * <li><b>hc02</b>: an infirmary is wanted by a Stone Age town of twenty with its hall (not nineteen, not without
 *     the hall); stamped, it has four beds, a cauldron and a brewing stand. A folk down to three tenths of
 *     its health walks there and lies in a bed, mending half a heart more every eight seconds; one with a cold
 *     lies in another, its cold running out twice as fast; the hurt one gets up once it is nine tenths whole.</li>
 * <li><b>hc03</b>: the healer: a folk with a cold laid up at home is visited and given the stores' honey bottle
 *     (the bottle goes back); one in the infirmary is given the golden carrot; with nothing left in the stores,
 *     rest and company still do a little good. The patient remembers who looked after it.</li>
 * <li><b>hc04</b>: an old folk of eighty-five: its son fetches it a loaf from the stores, carries it over, hands
 *     it over and sits with it; both remember it and are the fonder of each other.</li>
 * <li><b>hc05</b>: a wealthy folk walks to the poor box in the meeting hall and puts two coins in out of its
 *     purse; a poor folk with nothing to eat walks for a loaf the box pays for (the coin into the treasury, the
 *     loaf out of the stores); on payday a household short of its rent has the rest paid out of the box.</li>
 * <li><b>hc06</b>: a household moves into a house: that evening a friend calls at the door with a poppy out of
 *     its own pack, the household comes out to meet it, and everybody remembers the housewarming.</li>
 * <li><b>hc07</b>: a folk stood up in a town two days old is to be welcomed; a newcomer is greeted, walked to the
 *     heart and the stores and given a basket out of them (a loaf, a torch, a flower), and both remember it.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 600,000 to 612,000, z 50,000, in a batch of its own, calling the
 * town's logic directly where it can and logging what it sees. Whatever a test turns on for itself (colds in any
 * town, the odds made sure) it turns off again before it is done.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class HealthCareGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;
    private static final long DAY = 24000L * 4;

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A clean slate, and a village's first folk on flat ground at x, in clear weather, at this time. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Health.resetForTests();
        Neighbourly.resetForTests();
        Infirmary.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 32);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
        return f;
    }

    /** A plain house of four beds, its back to the north, here. */
    private static BlockPos house(ServerLevel level, UUID village, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, "house", p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", p, Direction.NORTH);
        Villages.recountBeds(village);
        return p;
    }

    /** The infirmary, its back to the north, here. */
    private static BlockPos infirmary(ServerLevel level, UUID village, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, Infirmary.STRUCTURE, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, Infirmary.STRUCTURE, p, Direction.NORTH);
        return p;
    }

    /** A marked store chest here, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    /**
     * What the town's stores hold of this, every chest of them together: the founding stores at the heart (bread,
     * carrots, torches) are the stores as much as a chest the test puts down, and are as often what is taken first.
     */
    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** What it has of this: in its pack, or in its hands (a torch is held out of doors after dark: NightLight). */
    private static int carried(VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        for (ItemStack s : List.of(f.getMainHandItem(), f.getOffhandItem())) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    private static boolean remembers(VillageFolkEntity f, String words) {
        for (Persona.Memory m : f.persona().memories()) if (m.text().contains(words)) return true;
        return false;
    }

    private static boolean said(List<String> lines, String words) {
        for (String s : lines) if (s.contains(words)) return true;
        return false;
    }

    private static boolean newsHas(UUID village, String words) {
        for (Villages.News n : Villages.news(village)) if (n.text().contains(words)) return true;
        return false;
    }

    private static void moveTo(VillageFolkEntity f, BlockPos p) {
        f.getNavigation().stop();
        f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
    }

    /** Keep the clock between these hours of the day (its own work waits for nobody in a test). */
    private static void hold(ServerLevel level, long from, long to) {
        long tod = level.getDayTime() % 24000L;
        if (tod < from || tod > to) {
            level.setDayTime(level.getDayTime() / 24000L * 24000L + from);
            level.updateSkyBrightness();                     // (isNight follows the clock only once the sky is worked out)
        }
    }

    /**
     * Sociable and cheerful: up till well after dark (a hard worker is in bed an hour before the evening's errands
     * are done), and nobody generous in charge (Homes.generous would let a short rent off and leave the box out of it).
     */
    private static void evening(VillageFolkEntity... folk) {
        for (VillageFolkEntity f : folk) f.life().setTraitsForTests(Social.Trait.SOCIABLE, Social.Trait.CHEERFUL);
    }

    /**
     * Two days' rations each, of something the stores do not hold: nobody walks to the stores of an evening to
     * take its rations on (VillageFolkEntity.restockRations) out of what the test counts there.
     */
    private static void fed(VillageFolkEntity... folk) {
        for (VillageFolkEntity f : folk) f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
    }

    /** After supper (so nobody eats what it is given before it is counted), before bed. */
    private static final long AFTER_SUPPER = 13400L, LATE = 13800L;

    // ============================================================ hc01: colds

    /**
     * A town of five, colds made possible in it and the odds made sure. Out in the rain two minutes, and the luck
     * tried: a cold, caught out in the rain, laid up for the first of it, its work at half pace (a block, an action,
     * a block laid), its card and a cough in its words; saved and read back the same. Worn out at its work, another.
     * Under one roof with its partner, the partner catches it. Two ill: nothing in the chronicle; three: an outbreak.
     * Four caught this week and a fifth, two minutes in the rain, does not catch one. Over it: up and about at its own
     * pace, alive and whole.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "hc01_colds")
    public static void hc01_colds(GameTestHelper helper) {
        int x = 600000;
        VillageFolkEntity a = founder(helper, x, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity b = another(helper, heart.east(2), id);
        VillageFolkEntity c = another(helper, heart.east(4), id);
        VillageFolkEntity d = another(helper, heart.west(2), id);
        VillageFolkEntity e = another(helper, heart.west(4), id);
        helper.runAtTickTime(5, () -> {
            try {
                Health.coldsForTests(true);
                Health.oddsForTests(1);
                long day = level.getDayTime() / 24000L;
                // a and b: a couple, under one roof.
                a.life().widowed();
                b.life().widowed();
                a.life().partnerWith(b.getUUID(), b.displayNameCap());
                b.life().partnerWith(a.getUUID(), a.displayNameCap());
                BlockPos home = house(level, id, heart.offset(-14, 0, 14));
                Homes.tickForTests(level, Villages.get(id));
                helper.assertTrue(home.equals(Homes.homeOf(a)) && home.equals(Homes.homeOf(b)), "the couple's house: " + Homes.homeOf(a));
                a.setJob(StationTask.MINE);
                d.setJob(StationTask.FARM);
                BlockState stone = Blocks.OBSIDIAN.defaultBlockState();          // hard going: no clock at its floor
                int work0 = a.workTicksFor(stone), act0 = a.actionPaceTicks(), build0 = a.buildPaceHundredths();

                // Out in the rain two minutes: a cold.
                Health.wetTicksForTests(a, 2400);
                Health.luckForTests(a);
                Kit.log("hc01 a cold: " + Health.cardLine(a) + "; laid up " + Health.laidUp(a) + "; said " + Health.saidForTests());
                helper.assertTrue(a.health().ill() && Health.cardLine(a).contains("caught out in the rain"), "two minutes in the rain, and a cold: " + Health.cardLine(a));
                helper.assertTrue(Health.laidUp(a) && !a.onShift(), "laid up for the first of it, and off its work");
                int work1 = a.workTicksFor(stone), act1 = a.actionPaceTicks(), build1 = a.buildPaceHundredths();
                Kit.log("hc01 pace: a block " + work0 + " -> " + work1 + " ticks, an action " + act0 + " -> " + act1 + ", a block laid " + build0 + " -> " + build1
                    + " hundredths; " + a.paceLine());
                helper.assertTrue(work1 >= work0 * 2 - 2 && act1 >= act0 * 2 - 2 && build1 >= Math.min(build0 * 2 - 2, 200 * 2),
                    "half pace with a cold: " + work0 + "->" + work1 + ", " + act0 + "->" + act1 + ", " + build0 + "->" + build1);
                helper.assertTrue(a.paceLine().contains("with a cold"), "its card's pace says so: " + a.paceLine());
                String card = FolkTalk.card(a);
                helper.assertTrue(card.contains("Health|Down with a cold"), "its card has a Health line: " + card);
                String coughed = Health.coughForTests(a, "Good morning.");
                helper.assertTrue(!coughed.equals("Good morning.") && (coughed.contains("cough") || coughed.contains("sniff") || coughed.contains("ahem")),
                    "a cough in what it says: " + coughed);
                int[] rt = Health.roundTripForTests(a);
                helper.assertTrue(rt[0] == a.health().coldLeft() && rt[1] == (int) day && rt[2] == 1 && rt[3] == 1,
                    "saved and read back the same: " + java.util.Arrays.toString(rt));
                helper.assertFalse(newsHas(id, "going round"), "one cold is no news");

                // Worn out at its work: another.
                Health.workedForTests(d, 10000);
                Health.luckForTests(d);
                helper.assertTrue(d.health().ill() && Health.cardLine(d).contains("worn out"), "worn out at its work, a cold: " + Health.cardLine(d));
                Health.outbreakForTests(level, id);
                helper.assertFalse(newsHas(id, "going round"), "two ill: not yet an outbreak");

                // Passed round the house: the partner catches it.
                Health.caughtDayForTests(a, day - 1);
                int spread = Health.spreadForTests(level, id, 1);
                Kit.log("hc01 spread: " + spread + "; b " + Health.cardLine(b));
                helper.assertTrue(spread == 1 && b.health().ill() && Health.cardLine(b).contains("from " + a.displayNameCap()),
                    "the one it lives with catches it: " + Health.cardLine(b));
                helper.assertFalse(c.health().ill() || e.health().ill(), "nobody outside the house");
                Health.outbreakForTests(level, id);
                Kit.log("hc01 news: " + Villages.news(id));
                helper.assertTrue(newsHas(id, "a cold is going round: 3"), "three ill: the chronicle hears of an outbreak: " + Villages.news(id));

                // No more than four a week.
                Health.catchForTests(c, "caught out in the rain");
                Health.wetTicksForTests(e, 2400);
                Health.luckForTests(e);
                int week = Health.caughtThisWeekForTests(id, day);
                Kit.log("hc01 this week: " + week + "; e " + Health.cardLine(e));
                helper.assertTrue(week == 4 && !e.health().ill(), "four this week and no fifth: " + week + ", e ill " + e.health().ill());

                // Over it.
                float hp = a.getHealth();
                Health.passForTests(a, 100000);
                int work2 = a.workTicksFor(stone);
                Kit.log("hc01 over it: " + Health.cardLine(a) + "; a block " + work2 + " ticks; health " + hp + " -> " + a.getHealth());
                helper.assertTrue(!a.health().ill() && !Health.laidUp(a) && !a.paceLine().contains("with a cold") && work2 * 10 <= work1 * 6,
                    "over it, up and at its own pace: " + work0 + " -> " + work1 + " -> " + work2);
                helper.assertTrue(a.isAlive() && a.getHealth() >= hp, "a cold never hurt anybody");
                helper.assertTrue(remembers(a, "got over my cold"), "and it remembers getting over it");
            } finally {
                Health.coldsForTests(null);
                Health.oddsForTests(0);
                Health.wetForTests(null);
            }
            helper.succeed();
        });
    }

    // ============================================================ hc02: the infirmary

    /**
     * Wanted: a Stone Age town of twenty with its meeting hall wants an infirmary; nineteen, or no hall, does not.
     * Stamped: four beds, a cauldron, a brewing stand, its beds nobody's own. A folk at six of its twenty walks
     * there and lies in a bed, mending half a heart more every eight seconds; one with a cold lies in another, its
     * cold going twice as fast; the hurt one gets up once it is nine tenths whole.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "hc02_infirmary")
    public static void hc02_infirmary(GameTestHelper helper) {
        int x = 602000;
        VillageFolkEntity a = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity hurt = another(helper, heart.east(3), id);
        VillageFolkEntity sick = another(helper, heart.west(3), id);
        long[] mark = { 0, 0, 0, 0 };                   // phase, tick both lying, the infirmary's mending then, the cold then
        float[] hp0 = { 0 };
        helper.runAtTickTime(5, () -> {
            List<String> built = List.of("storage", "storehouse", "shelter", "house", "house", "house", "well", "fortify", "smeltery", "hall");
            UUID town = UUID.randomUUID(), small = UUID.randomUUID(), noHall = UUID.randomUUID();
            Villages.restore(level, town, heart.offset(900, 0, 0), Villages.Age.STONE, built, 20);
            Villages.restore(level, small, heart.offset(1200, 0, 0), Villages.Age.STONE, built, 19);
            Villages.restore(level, noHall, heart.offset(1500, 0, 0), Villages.Age.STONE, built.subList(0, built.size() - 1), 24);
            List<String> wanted = Villages.projectsWanted(town);
            Kit.log("hc02 a town of twenty with its hall wants " + wanted + "; why: " + Villages.whyBuild(town, "infirmary"));
            helper.assertTrue(wanted.contains("infirmary"), "a Stone Age town of twenty with its hall wants an infirmary: " + wanted);
            helper.assertFalse(Villages.projectsWanted(small).contains("infirmary"), "nineteen do not: " + Villages.projectsWanted(small));
            helper.assertFalse(Villages.projectsWanted(noHall).contains("infirmary"), "nor a town without its hall");
            helper.assertTrue(Villages.whyBuild(town, "infirmary").contains("beds for the sick"), "and says why: " + Villages.whyBuild(town, "infirmary"));
            helper.assertTrue(BuildGoal.STRUCTURES.contains("infirmary") && "civic".equals(TownPlan.placeFor("infirmary")),
                "a building the builders know, on a civic lot");
            BlockPos at = infirmary(level, id, heart.offset(0, 0, -14));
            Ledger.Building b = Infirmary.of(id);
            List<BlockPos> beds = Infirmary.beds(level, b);
            int cauldrons = 0, stands = 0;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, 0, -5), at.offset(4, 3, 5))) {
                if (level.getBlockState(p).is(Blocks.CAULDRON)) cauldrons++;
                if (level.getBlockState(p).is(Blocks.BREWING_STAND)) stands++;
            }
            Kit.log("hc02 the infirmary at " + at.toShortString() + ": beds " + beds + ", " + cauldrons + " cauldron, " + stands + " brewing stand");
            helper.assertTrue(beds.size() == 4 && cauldrons == 1 && stands == 1, "four beds, a cauldron and a brewing stand: " + beds.size() + "/" + cauldrons + "/" + stands);
            helper.assertTrue(Infirmary.isInfirmaryBed(id, beds.get(0)), "its beds are the infirmary's, nobody's own");
            // The hurt one: six of its twenty, nothing to eat in its pack (so it mends as the infirmary has it).
            hurt.removeMatching(s -> s.get(DataComponents.FOOD) != null, 9999);
            hurt.setHealth(hurt.getMaxHealth() * 0.3F);
            Health.catchForTests(sick, "caught out in the rain");
            mark[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            hold(level, 2000L, 4500L);
            if (mark[0] == 0 || t % 20 != 0) return;
            boolean hurtIn = Health.lyingForTests(hurt) && Health.inInfirmaryForTests(hurt);
            boolean sickIn = Health.lyingForTests(sick) && Health.inInfirmaryForTests(sick);
            if (t % 200 == 0) Kit.log("hc02 @" + t + " hurt " + hurt.getHealth() + "/" + hurt.getMaxHealth() + " " + Health.cardLine(hurt) + " at "
                + hurt.blockPosition().toShortString() + "; sick " + Health.cardLine(sick) + " at " + sick.blockPosition().toShortString());
            if (mark[0] == 1) {
                if (hurtIn && sickIn) {
                    helper.assertTrue(!Health.bedForTests(hurt).equals(Health.bedForTests(sick)), "a bed each");
                    mark[0] = 2;
                    mark[1] = t;
                    hp0[0] = hurt.getHealth();
                    mark[2] = Health.mendedForTests(hurt);
                    mark[3] = sick.health().coldLeft();
                    Kit.log("hc02 both in bed at " + t + ": hurt at " + hp0[0] + ", the cold " + mark[3] + " to run");
                } else if (t > 1400) {
                    helper.fail("not both in the infirmary's beds: hurt " + Health.cardLine(hurt) + " at " + hurt.blockPosition().toShortString()
                        + "; sick " + Health.cardLine(sick) + " at " + sick.blockPosition().toShortString());
                }
                return;
            }
            if (mark[0] == 2 && t - mark[1] >= 640) {
                int mended = Health.mendedForTests(hurt) - (int) mark[2];
                long coldGone = mark[3] - sick.health().coldLeft();
                Kit.log("hc02 in 640 ticks: hurt " + hp0[0] + " -> " + hurt.getHealth() + " (" + mended + " half-hearts the infirmary's); the cold "
                    + coldGone + " ticks gone");
                helper.assertTrue(mended >= 3, "half a heart more every eight seconds in the infirmary's bed: " + mended);
                helper.assertTrue(hurt.getHealth() - hp0[0] >= mended, "and mending: " + hp0[0] + " -> " + hurt.getHealth());
                helper.assertTrue(coldGone >= 1100, "a cold goes twice as fast in its beds: " + coldGone + " ticks of it in 640");
                mark[0] = 3;
                return;
            }
            if (mark[0] == 3) {
                boolean up = !Health.lyingForTests(hurt) && !Health.laidUp(hurt);
                if (up && hurt.getHealth() >= hurt.getMaxHealth() * 0.9F - 0.01F) {
                    Kit.log("hc02 the hurt one is up at " + t + " at " + hurt.getHealth() + "/" + hurt.getMaxHealth());
                    helper.succeed();
                } else if (t > 3100) {
                    helper.fail("the hurt one never got up: " + hurt.getHealth() + "/" + hurt.getMaxHealth() + " " + Health.cardLine(hurt));
                }
            }
        });
    }

    // ============================================================ hc03: the healer

    /**
     * The healer's round. A folk with a cold, laid up in its own bed at home, its partner at the bedside: it is
     * visited and given the stores' honey bottle (the bottle back into the stores), a quarter-day off its cold. Then
     * the infirmary: a folk with a cold in one of its beds is given the golden carrot; with nothing left in the stores,
     * rest and company take a little off all the same. The patient remembers who looked after it, and likes it the
     * better.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "hc03_healer")
    public static void hc03_healer(GameTestHelper helper) {
        int x = 604000;
        VillageFolkEntity a = founder(helper, x, DAY + 2000);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity k = another(helper, heart.east(3), id);
        VillageFolkEntity c = another(helper, heart.west(3), id);
        BlockPos[] homes = new BlockPos[2];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            Health.roundsOffForTests(true);                          // only the visits the test makes (put back when it is done)
            fed(a, k, c);
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.HONEY_BOTTLE), new ItemStack(Items.GOLDEN_CARROT));
            k.life().widowed();
            c.life().widowed();
            k.life().partnerWith(c.getUUID(), c.displayNameCap());
            c.life().partnerWith(k.getUUID(), k.displayNameCap());
            homes[0] = house(level, id, heart.offset(-14, 0, 14));
            Homes.tickForTests(level, Villages.get(id));
            helper.assertTrue(homes[0].equals(Homes.homeOf(k)), "k's house: " + Homes.homeOf(k));
            Health.catchForTests(k, "caught out in the rain");
            moveTo(a, heart.offset(20, 0, -20));
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            hold(level, 2000L, 4500L);
            if (phase[0] == 0 || t % 20 != 0) return;
            if (phase[0] == 1) {
                if (Health.lyingForTests(k) && !Health.inInfirmaryForTests(k)) {
                    // Its partner at the bedside; the town's care calls.
                    moveTo(c, homes[0].offset(0, 0, 5));                 // just outside its door
                    moveTo(a, heart.offset(20, 0, -20));
                    int before = k.health().coldLeft(), liked = k.life().affinity(c.getUUID());
                    int bottles = stock(level, id, s -> s.is(Items.GLASS_BOTTLE));
                    VillageFolkEntity seen = Health.careForTests(level, id);
                    int after = k.health().coldLeft();
                    Kit.log("hc03 at home: seen " + (seen == null ? "nobody" : seen.displayNameCap()) + "; " + Health.tendedForTests(k) + "; cold "
                        + before + " -> " + after + "; honey " + stock(level, id, s -> s.is(Items.HONEY_BOTTLE)) + ", bottles in the stores "
                        + bottles + " -> " + stock(level, id, s -> s.is(Items.GLASS_BOTTLE)) + "; said " + Health.saidForTests());
                    helper.assertTrue(seen == k, "the one laid up at home is visited");
                    helper.assertTrue(Health.tendedForTests(k).startsWith(c.displayNameCap()) && Health.tendedForTests(k).contains("honey"),
                        "by the one at its bedside, with the stores' honey: " + Health.tendedForTests(k));
                    helper.assertTrue(stock(level, id, s -> s.is(Items.HONEY_BOTTLE)) == 0 && stock(level, id, s -> s.is(Items.GLASS_BOTTLE)) == bottles + 1,
                        "the honey out of the stores, the bottle back into them");
                    helper.assertTrue(before - after == 6000, "a quarter-day off its cold: " + (before - after));
                    helper.assertTrue(remembers(k, "looked after me") && k.life().affinity(c.getUUID()) > liked, "it remembers, and likes it the better");
                    // Over it; then the infirmary, and somebody with a cold in it.
                    Health.passForTests(k, 100000);
                    homes[1] = infirmary(level, id, heart.offset(0, 0, -14));
                    Health.catchForTests(a, "worn out at its work");
                    phase[0] = 2;
                } else if (t > 1000) {
                    Health.roundsOffForTests(false);
                    helper.fail("never laid up at home: " + Health.cardLine(k) + " at " + k.blockPosition().toShortString());
                }
                return;
            }
            if (phase[0] == 2) {
                if (Health.lyingForTests(a) && Health.inInfirmaryForTests(a)) {
                    moveTo(c, homes[1].offset(0, 0, 6));                 // just outside the infirmary's door
                    int before = a.health().coldLeft();
                    VillageFolkEntity seen = Health.careForTests(level, id);
                    int mid = a.health().coldLeft();
                    String first = Health.tendedForTests(a);
                    VillageFolkEntity again = Health.careForTests(level, id);
                    int after = a.health().coldLeft();
                    Kit.log("hc03 at the infirmary: " + first + " (" + before + " -> " + mid + "), then " + Health.tendedForTests(a) + " (" + mid + " -> " + after
                        + "); golden carrots left " + stock(level, id, s -> s.is(Items.GOLDEN_CARROT)));
                    helper.assertTrue(seen == a && again == a, "the patient at the infirmary is seen to");
                    helper.assertTrue(first.contains("golden carrot") && before - mid == 6000 && stock(level, id, s -> s.is(Items.GOLDEN_CARROT)) == 0,
                        "with the stores' golden carrot: " + first);
                    helper.assertTrue(Health.tendedForTests(a).contains("rest and company") && mid - after == 1500,
                        "nothing left in the stores: rest and company, a little off all the same: " + Health.tendedForTests(a) + ", " + (mid - after));
                    Health.roundsOffForTests(false);
                    helper.succeed();
                } else if (t > 2200) {
                    Health.roundsOffForTests(false);
                    helper.fail("never laid up at the infirmary: " + Health.cardLine(a) + " at " + a.blockPosition().toShortString());
                }
            }
        });
    }

    // ============================================================ hc04: the old looked after

    /**
     * An old folk of eighty-five and its son, in the evening, a loaf in the stores. The son is sent to look in on
     * it: to the stores for its supper, over to it, the loaf handed over, and the two of them sat down a while.
     * Both remember it, and are the fonder of each other.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "hc04_the_old")
    public static void hc04_the_old(GameTestHelper helper) {
        int x = 606000;
        VillageFolkEntity old = founder(helper, x, DAY + AFTER_SUPPER);
        ServerLevel level = helper.getLevel();
        UUID id = old.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity son = another(helper, heart.east(6), id);
        VillageFolkEntity other = another(helper, heart.west(6), id);
        int[] state = { 0, 0, 0, 0 };                    // phase, bread before, son's feeling before, sat seen
        helper.runAtTickTime(5, () -> {
            evening(old, son, other);
            old.setAgeForTests(85);
            son.setAgeForTests(50);
            other.setAgeForTests(30);
            son.parentIds().add(old.getUUID());
            chestAt(level, heart.offset(4, 0, -4), new ItemStack(Items.BREAD, 4));
            for (VillageFolkEntity f : List.of(old, son, other)) {
                f.removeMatching(s -> s.is(Items.BREAD), 9999);
                f.clearQueue();
            }
            fed(old, son, other);
            moveTo(old, heart.offset(-8, 0, 8));
            helper.assertTrue(Neighbourly.frail(old) && !Neighbourly.frail(son), "eighty-five is very old; fifty is not");
            int sent = Neighbourly.visitsForTests(level, id);
            Kit.log("hc04 visits sent: " + sent + "; son " + Neighbourly.errandForTests(son) + ", other " + Neighbourly.errandForTests(other));
            helper.assertTrue(sent == 1 && Neighbourly.errandForTests(son) != null && Neighbourly.errandForTests(son).startsWith("visit"),
                "one of the family is sent to look in on it");
            state[1] = stock(level, id, s -> s.is(Items.BREAD));
            state[2] = old.life().affinity(son.getUUID());
            state[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            hold(level, AFTER_SUPPER, LATE);
            if (state[0] == 0) return;
            if (Neighbourly.seated(son) && Neighbourly.seated(old)) state[3] = 1;
            if (t % 20 != 0) return;
            if (t % 200 == 0) Kit.log("hc04 @" + t + " son: " + Neighbourly.errandForTests(son) + " at " + son.blockPosition().toShortString() + ", doing "
                + Neighbourly.doing(son) + "; old: " + Neighbourly.companyForTests(old) + " at " + old.blockPosition().toShortString());
            boolean done = Neighbourly.errandForTests(son) == null && Neighbourly.visitsTodayForTests(id, level.getDayTime() / 24000L) == 1;
            if (done) {
                int bread = stock(level, id, s -> s.is(Items.BREAD)), got = carried(old, s -> s.is(Items.BREAD));
                Kit.log("hc04 the visit done at " + t + ": stores' bread " + state[1] + " -> " + bread + ", the old one carries " + got
                    + "; sat together " + (state[3] == 1) + "; liking " + state[2] + " -> " + old.life().affinity(son.getUUID()) + "; said " + Neighbourly.saidForTests());
                // (Its own bread was taken off it, and the son's: a loaf in its hands is the one the son brought.)
                helper.assertTrue(bread <= state[1] - 1 && got >= 1 && said(Neighbourly.saidForTests(), "bread"),
                    "a loaf out of the stores, into the old one's hands: " + Neighbourly.saidForTests());
                helper.assertTrue(state[3] == 1, "they sat down together a while");
                helper.assertTrue(remembers(old, "came round") && remembers(son, "sat with"), "both remember it");
                helper.assertTrue(old.life().affinity(son.getUUID()) >= state[2] + 5, "and the old one is the fonder of its son");
                helper.succeed();
            } else if (t > 2300) {
                helper.fail("the visit never came off: " + Neighbourly.errandForTests(son) + "; " + Neighbourly.saidForTests());
            }
        });
    }

    // ============================================================ hc05: the poor box

    /**
     * A meeting hall, a wealthy folk, a poor folk with nothing to eat, bread in the stores and three coins already in
     * the box. The wealthy one walks to the box in the hall and puts two coins in out of its own purse. The poor one
     * walks for a loaf the box pays for: the loaf out of the stores into its pack, its price out of the box and into
     * the treasury. On payday, a household short of its rent has the rest paid out of the box.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "hc05_poor_box")
    public static void hc05_poor_box(GameTestHelper helper) {
        int x = 608000;
        VillageFolkEntity rich = founder(helper, x, DAY + AFTER_SUPPER);
        ServerLevel level = helper.getLevel();
        UUID id = rich.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity poor = another(helper, heart.east(5), id);
        // phase, the rich one's purse before; and when the poor one set out: its bread, the stores', the box, the treasury
        int[] state = { 0, 0, -1, 0, 0, 0 };
        helper.runAtTickTime(5, () -> {
            evening(rich, poor);
            fed(rich);
            BlockPos hall = Kit.surface(level, x + 12, Z - 12);
            Ledger.built(id, "hall", hall, Direction.NORTH);
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.BREAD, 5));
            rich.earn(400);
            poor.getInventoryItems().clear();
            poor.spend(poor.purse());
            poor.clearQueue();
            rich.clearQueue();
            Kit.log("hc05 rich " + Wealth.tier(rich) + " (" + rich.purse() + "c), poor " + Wealth.tier(poor) + " (" + poor.purse() + "c)");
            helper.assertTrue(Wealth.tier(rich) == Wealth.Tier.WEALTHY && Wealth.tier(poor) == Wealth.Tier.POOR, "one wealthy, one poor");
            state[1] = rich.purse();
            PoorBox.coinsForTests(id, 3);
            int hungry = PoorBox.breadForTests(level, id);
            helper.assertTrue(hungry == 1 && Neighbourly.errandForTests(poor) != null && Neighbourly.errandForTests(poor).startsWith("bread"),
                "the hungry poor one is sent for a loaf: " + Neighbourly.errandForTests(poor));
            int sent = PoorBox.giversForTests(level, id);
            helper.assertTrue(sent == 1 && Neighbourly.errandForTests(rich) != null && Neighbourly.errandForTests(rich).startsWith("poorbox"),
                "the wealthy one is sent with its coins: " + Neighbourly.errandForTests(rich));
            state[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            hold(level, AFTER_SUPPER, LATE);
            if (state[0] == 0) return;
            String going = Neighbourly.errandForTests(poor);
            if (state[2] < 0 && going != null && going.contains("begun")) {
                // Set out: from here its errand is all it does (nothing else of its evening takes a loaf meanwhile).
                state[2] = carried(poor, s -> s.is(Items.BREAD));
                state[3] = stock(level, id, s -> s.is(Items.BREAD));
                state[4] = PoorBox.coins(id);
                state[5] = Ledger.coins(id);
            }
            if (t % 20 != 0) return;
            if (t % 200 == 0) Kit.log("hc05 @" + t + " rich " + Neighbourly.errandForTests(rich) + " at " + rich.blockPosition().toShortString()
                + "; poor " + going + " at " + poor.blockPosition().toShortString() + "; box " + PoorBox.coins(id));
            if (Neighbourly.errandForTests(rich) != null || going != null) {
                if (t > 2200) helper.fail("not done: rich " + Neighbourly.errandForTests(rich) + ", poor " + going + "; " + Neighbourly.saidForTests());
                return;
            }
            int box = PoorBox.coins(id), treasury = Ledger.coins(id);
            int bread = carried(poor, s -> s.is(Items.BREAD)), left = stock(level, id, s -> s.is(Items.BREAD));
            int[] week = PoorBox.weekForTests(id, level.getDayTime() / 24000L);
            Kit.log("hc05 both done: the box " + box + "c (week " + java.util.Arrays.toString(week) + "); the rich one's purse " + state[1] + " -> "
                + rich.purse() + "; the poor one's bread " + state[2] + " -> " + bread + ", the stores' " + state[3] + " -> " + left + "; treasury "
                + state[5] + " -> " + treasury + "; " + PoorBox.line(id, level.getDayTime() / 24000L));
            helper.assertTrue(state[2] >= 0, "the poor one set out");
            helper.assertTrue(rich.purse() == state[1] - 2 && week[1] == 2 && week[2] == 1, "two coins out of the rich one's own purse into the box");
            helper.assertTrue(remembers(rich, "poor box"), "and it remembers it");
            helper.assertTrue(bread == state[2] + 1 && left <= state[3] - 1 && week[4] == 1, "a loaf out of the stores into the poor one's pack");
            helper.assertTrue(week[5] >= 1 && treasury - state[5] == week[5] && box == 3 + 2 - week[5],
                "paid for out of the box (" + week[5] + "c), into the treasury");
            // Payday: a household of one, renting, short of its rent: the box pays the rest. (A house each, so
            // the poor one has one of its own; the rich one, a founder, lives rent-free.)
            PoorBox.coinsForTests(id, 10);
            poor.rentFree(false);
            poor.setJob(StationTask.FARM);
            BlockPos home = house(level, id, heart.offset(-14, 0, 14));
            house(level, id, heart.offset(14, 0, 14));
            Homes.tickForTests(level, Villages.get(id));
            int rentBox = PoorBox.weekForTests(id, level.getDayTime() / 24000L)[3];
            poor.spend(poor.purse());
            Homes.paydayForTests(level, Villages.get(id));
            int paid = PoorBox.weekForTests(id, level.getDayTime() / 24000L)[3] - rentBox;
            Kit.log("hc05 payday: " + Homes.homeOf(poor) + " (house " + home + "); the box paid " + paid + " toward rent; " + PoorBox.line(id, level.getDayTime() / 24000L));
            helper.assertTrue(Homes.homeOf(poor) != null, "the poor one has a house of its own: " + Homes.homeOf(poor));
            helper.assertTrue(paid >= 1 && PoorBox.coins(id) == 10 - paid, "the box pays what it is short of: " + paid);
            helper.succeed();
        });
    }

    // ============================================================ hc06: a housewarming

    /**
     * A couple moves into a house: that evening their friend calls at the door with a poppy out of its own pack,
     * they come out to meet it, the poppy is theirs, and everybody remembers the housewarming.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "hc06_housewarming")
    public static void hc06_housewarming(GameTestHelper helper) {
        int x = 610000;
        VillageFolkEntity h1 = founder(helper, x, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = h1.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity h2 = another(helper, heart.east(3), id);
        VillageFolkEntity friend = another(helper, heart.west(5), id);
        BlockPos[] home = new BlockPos[1];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            h1.life().widowed();
            h2.life().widowed();
            h1.life().partnerWith(h2.getUUID(), h2.displayNameCap());
            h2.life().partnerWith(h1.getUUID(), h1.displayNameCap());
            h1.rentFree(false);
            h2.rentFree(false);
            evening(h1, h2, friend);
            friend.life().feel(h1.getUUID(), h1.displayNameCap(), 40);
            friend.removeMatching(s -> s.is(ItemTags.SMALL_FLOWERS), 99);
            friend.insertItem(new ItemStack(Items.POPPY));
            home[0] = house(level, id, heart.offset(-14, 0, 14));
            Homes.tickForTests(level, Villages.get(id));
            int[] w = Neighbourly.warmingForTests(id, home[0]);
            Kit.log("hc06 moved in: " + Homes.homeOf(h1) + "; housewarming " + java.util.Arrays.toString(w));
            helper.assertTrue(home[0].equals(Homes.homeOf(h1)) && w[0] == 1 && w[1] == 0, "they move in, and a housewarming is planned for the evening");
            level.setDayTime(level.getDayTime() / 24000L * 24000L + AFTER_SUPPER);
            level.updateSkyBrightness();
            for (VillageFolkEntity f : List.of(h1, h2, friend)) f.clearQueue();
            Neighbourly.housewarmingsForTests(level, id);
            w = Neighbourly.warmingForTests(id, home[0]);
            Kit.log("hc06 the evening: " + java.util.Arrays.toString(w) + "; friend " + Neighbourly.errandForTests(friend) + "; hosts "
                + Neighbourly.companyForTests(h1) + "/" + Neighbourly.companyForTests(h2));
            helper.assertTrue(w[1] == 1 && w[3] == 1 && Neighbourly.errandForTests(friend) != null, "the friend is sent to call");
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            hold(level, AFTER_SUPPER, 13750L);
            if (phase[0] == 0 || t % 20 != 0) return;
            Neighbourly.housewarmingsForTests(level, id);
            int[] w = Neighbourly.warmingForTests(id, home[0]);
            if (t % 200 == 0) Kit.log("hc06 @" + t + " " + java.util.Arrays.toString(w) + "; friend " + Neighbourly.errandForTests(friend) + " at "
                + friend.blockPosition().toShortString() + "; hosts " + Neighbourly.companyForTests(h1) + "/" + Neighbourly.companyForTests(h2));
            if (w[2] == 1 || w[0] == 0) {
                Container chest = (Container) level.getBlockEntity(com.jrpetty.mcassistant.entity.Families.homeChestForTests(level, h1));
                int poppies = carried(h1, s -> s.is(Items.POPPY)) + carried(h2, s -> s.is(Items.POPPY)) + (chest == null ? 0 : count(chest, s -> s.is(Items.POPPY)));
                int still = carried(friend, s -> s.is(Items.POPPY));
                Kit.log("hc06 over at " + t + ": " + java.util.Arrays.toString(w) + "; the household's poppies " + poppies + ", the friend's " + still
                    + "; said " + Neighbourly.saidForTests());
                helper.assertTrue(still == 0 && poppies == 1, "the poppy out of the friend's pack, the household's now (in hand, or its chest)");
                helper.assertTrue(remembers(h1, "housewarming") && remembers(h2, "housewarming") && remembers(friend, "housewarming"),
                    "everybody remembers the housewarming");
                helper.succeed();
            } else if (t > 2300) {
                helper.fail("the housewarming never came off: " + java.util.Arrays.toString(w) + "; " + Neighbourly.errandForTests(friend));
            }
        });
    }

    // ============================================================ hc07: the welcome committee

    /**
     * A folk stood up in a town two days old is to be welcomed (one stood up the day it was founded is not). A
     * newcomer, in the evening: somebody is sent, greets it, walks it to the heart and the stores, and hands it a
     * welcome basket out of them: a loaf, a torch and a flower. Both remember it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "hc07_welcome")
    public static void hc07_welcome(GameTestHelper helper) {
        int x = 612000;
        VillageFolkEntity e = founder(helper, x, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = e.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity same = another(helper, heart.east(3), id);
        VillageFolkEntity[] who = new VillageFolkEntity[2];
        int[] phase = { 0 };
        int[] had = new int[3];                           // the stores' bread, torches and flowers before
        helper.runAtTickTime(5, () -> {
            evening(e, same);
            helper.assertTrue(Neighbourly.welcomeForTests(same.getUUID())[0] == 0, "one stood up the day the town was founded is a founder");
            level.setDayTime(level.getDayTime() + 48000L);
            VillageFolkEntity n = another(helper, heart.west(10), id);
            Kit.log("hc07 stood up two days on: " + n.displayNameCap() + " " + java.util.Arrays.toString(Neighbourly.welcomeForTests(n.getUUID())));
            helper.assertTrue(Neighbourly.welcomeForTests(n.getUUID())[0] == 1, "one stood up in a town two days old is to be welcomed");
            chestAt(level, heart.offset(5, 0, 5), new ItemStack(Items.BREAD, 3), new ItemStack(Items.TORCH, 4), new ItemStack(Items.POPPY, 2));
            evening(n);
            n.removeMatching(s -> s.is(Items.BREAD) || s.is(Items.TORCH) || s.is(ItemTags.SMALL_FLOWERS), 999);
            fed(e, same, n);
            level.setDayTime(level.getDayTime() / 24000L * 24000L + AFTER_SUPPER);
            level.updateSkyBrightness();
            for (VillageFolkEntity f : List.of(e, same, n)) f.clearQueue();
            had[0] = stock(level, id, s -> s.is(Items.BREAD));
            had[1] = stock(level, id, s -> s.is(Items.TORCH));
            had[2] = stock(level, id, s -> s.is(ItemTags.SMALL_FLOWERS));
            Neighbourly.welcomesForTests(level, id);
            VillageFolkEntity g = null;
            for (VillageFolkEntity f : List.of(e, same)) if (Neighbourly.errandForTests(f) != null) g = f;
            Kit.log("hc07 the greeter: " + (g == null ? "nobody" : g.displayNameCap() + " " + Neighbourly.errandForTests(g)));
            helper.assertTrue(g != null && Neighbourly.errandForTests(g).startsWith("welcome"), "somebody is sent to welcome it");
            who[0] = g;
            who[1] = n;
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            hold(level, AFTER_SUPPER, LATE);
            if (phase[0] == 0 || t % 20 != 0) return;
            VillageFolkEntity g = who[0], n = who[1];
            if (t % 200 == 0) Kit.log("hc07 @" + t + " " + g.displayNameCap() + ": " + Neighbourly.errandForTests(g) + " at " + g.blockPosition().toShortString()
                + ", " + n.displayNameCap() + ": " + Neighbourly.companyForTests(n) + " at " + n.blockPosition().toShortString());
            if (Neighbourly.welcomeForTests(n.getUUID())[2] == 1 && Neighbourly.errandForTests(g) == null && remembers(n, "welcomed me")) {
                List<String> said = Neighbourly.saidForTests();
                Kit.log("hc07 welcomed at " + t + "; carries bread " + carried(n, s -> s.is(Items.BREAD)) + ", torch " + carried(n, s -> s.is(Items.TORCH))
                    + ", flower " + carried(n, s -> s.is(ItemTags.SMALL_FLOWERS)) + "; said " + said);
                helper.assertTrue(carried(n, s -> s.is(Items.BREAD)) == 1 && carried(n, s -> s.is(Items.TORCH)) == 1
                    && carried(n, s -> s.is(ItemTags.SMALL_FLOWERS)) == 1, "a welcome basket: a loaf, a torch and a flower");
                int bread = stock(level, id, s -> s.is(Items.BREAD)), torches = stock(level, id, s -> s.is(Items.TORCH)),
                    flowers = stock(level, id, s -> s.is(ItemTags.SMALL_FLOWERS));
                Kit.log("hc07 the stores: bread " + had[0] + " -> " + bread + ", torches " + had[1] + " -> " + torches + ", flowers " + had[2] + " -> " + flowers);
                helper.assertTrue(bread <= had[0] - 1 && torches <= had[1] - 1 && flowers <= had[2] - 1, "out of the stores");
                helper.assertTrue(said(said, "heart of the town") || said(said, "the square"), "shown the heart of the town: " + said);
                helper.assertTrue(remembers(n, "welcomed me") && remembers(g, "welcomed"), "both remember it");
                helper.succeed();
            } else if (t > 2900) {
                helper.fail("never welcomed: " + Neighbourly.errandForTests(g) + "; " + java.util.Arrays.toString(Neighbourly.welcomeForTests(n.getUUID()))
                    + "; " + Neighbourly.saidForTests());
            }
        });
    }
}
