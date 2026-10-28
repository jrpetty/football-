package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Hands with no trade. A hundred-day town's tallies counted nine to fourteen folk "Unassigned" at every
 * checkpoint, and they looked like grown folk who never took up work: they were its children, whom the
 * folk list called by their trade, and a child has none till it is grown. The list now says "Child".
 * A grown folk that loses its trade (moved in from another town, married into it, gave up a trade there
 * was no ground for) takes up the one the town most needs at its next look round, through its own day,
 * not after the minute or two between its searches for ground.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class IdleHandsGameTests {

    private static final String EMPTY = "empty";

    /** The most a grown folk with no trade should go without one by day: a few looks round, not two minutes. */
    private static final long PROMPT = 600L;

    private static VillageFolkEntity raise(GameTestHelper helper, ServerLevel level, BlockPos at, String who) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        helper.assertTrue(f != null, "raise() gave nobody for " + who);
        return f;
    }

    /**
     * Real ticks. A folk of a new village takes up its first trade through its own agenda (and looks for
     * ground for it, just now); then it loses the trade and its ground the way a folk moving between towns
     * does (JobSeekers.leave, Bonds.marry, Contentment.leaveFor). By day, it takes up a trade again within
     * a few of its looks round: the claim used to wait for the next search for ground, up to two minutes
     * after the last one.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "ua01_takes_up_a_trade")
    public static void ua01_takes_up_a_trade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 600000, z = 60000;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = raise(helper, level, heart, "the first");
        raise(helper, level, Kit.surface(level, x + 3, z + 2), "the second");
        raise(helper, level, Kit.surface(level, x - 3, z - 2), "the third");
        final long[] first = { -1 }, lost = { -1 };
        final StationTask[] had = { StationTask.NONE };
        helper.onEachTick(() -> {
            if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            long t = helper.getTick();
            if (t % 100 == 0) Kit.log("ua01 @" + t + " — " + f.debugLine());
            if (lost[0] < 0) {
                if (f.stationTask() == StationTask.NONE) return;
                // Its first trade, taken up through its own agenda, and its first look for ground just made.
                first[0] = t;
                had[0] = f.stationTask();
                Kit.log("ua01 first trade " + had[0] + " at " + t + "; ground " + (f.workZone() == null ? "none yet" : f.workZone().describe()));
                // Off its trade and its ground, as a folk moving between towns is.
                f.clearQueue();
                f.setWorkZone(null);
                f.setStation(null, StationTask.NONE);
                lost[0] = t;
                String line = f.debugLine();
                Kit.log("ua01 lost it at " + t + " — " + line);
                helper.assertTrue(line.contains(" Unassigned hp="), "a grown folk with no trade reads as Unassigned: " + line);
                return;
            }
            long without = t - lost[0];
            if (f.stationTask() == StationTask.NONE) {
                if (without > PROMPT) {
                    helper.fail("ua01 still no trade " + without + " ticks after it lost its own, by day ("
                        + level.getDayTime() % 24000L + ") — " + f.debugLine());
                }
                return;
            }
            UUID village = f.ownerId();
            Kit.log("ua01 took up " + f.stationTask() + " " + without + " ticks after losing " + had[0] + " (the town wants "
                + (village == null ? "?" : Villages.needed(village)) + " next) — " + f.debugLine());
            helper.assertTrue(without <= PROMPT, "took up a trade within " + PROMPT + " ticks: " + without);
            helper.assertTrue(!f.debugLine().contains(" Unassigned hp="), "its line names its trade: " + f.debugLine());
            helper.succeed();
        });
    }

    /**
     * The folk list (/village folk) and the register (/village people) call a child a child, not
     * "Unassigned"; a grown folk with no trade is still "Unassigned"; and the child, grown, takes up a
     * trade and its line says which.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "ua02_a_child_reads_as_a_child")
    public static void ua02_a_child_reads_as_a_child(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 602000, z = 60000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity grown = raise(helper, level, heart, "the grown folk");
        VillageFolkEntity kid = raise(helper, level, Kit.surface(level, x + 3, z + 2), "the child");
        grown.setStation(null, StationTask.NONE);
        kid.setStation(null, StationTask.NONE);
        kid.setChild(true);
        kid.bornDaysAgo(1);
        String kidLine = kid.debugLine(), grownLine = grown.debugLine();
        Kit.log("ua02 the child: " + kidLine);
        Kit.log("ua02 the grown folk with no trade: " + grownLine);
        helper.assertTrue(kidLine.contains(" Child hp=") && !kidLine.contains("Unassigned"), "the child's line says Child: " + kidLine);
        helper.assertTrue(grownLine.contains(" Unassigned hp="), "a grown folk with no trade is still Unassigned: " + grownLine);
        List<String> people = Kit.command(level, "execute positioned " + x + " " + heart.getY() + " " + z + " run village people");
        String kidPerson = null;
        for (String l : people) if (l.startsWith(kid.displayNameCap() + " — ")) kidPerson = l;
        Kit.log("ua02 the register: " + kidPerson);
        helper.assertTrue(kidPerson != null && kidPerson.startsWith(kid.displayNameCap() + " — child"),
            "the register calls it a child: " + kidPerson + " in " + people);
        kid.childhoodForTests(VillageFolkEntity.GROW_DAYS);
        String grownUp = kid.debugLine();
        Kit.log("ua02 grown up: " + grownUp);
        helper.assertTrue(!kid.isBaby() && kid.stationTask() != StationTask.NONE, "grown, it takes up a trade: " + kid.stationTask());
        helper.assertTrue(grownUp.contains(" " + kid.stationTask().title + " hp="), "and its line names it: " + grownUp);
        helper.succeed();
    }
}
