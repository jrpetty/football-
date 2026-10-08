package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Aboard;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.MineSafety;
import com.jrpetty.mcassistant.entity.MineStairs;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [mine-safety] Folk kept out of the boats and out of the mine.
 *
 * <p>The sixty-day plains game lost four folk of seven to the fishers' moored boats: a pair to a boat, at the
 * water's level thirty blocks under the town, for forty and fifty-five days (Aboard). And the town's mine
 * is made no trap for anybody who is not a miner at work in it (MineSafety): a folk below ground in it
 * climbs out by the stairs, never lifted out; its stair heads are fenced round with the head left open
 * and a sign up; a hand with nothing to do is never lent to a miner's face for stone; and a miner's work
 * chest stands at the surface.
 *
 * <p>The test world is flat, three blocks of earth over bedrock, so the mine tests stand a block of rock
 * on it, thirty high, to cut stairs twenty-odd deep into.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MineSafetyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private static void reset(ServerLevel level) {
        Kit.reset(level);
        MineSafety.resetForTests();
        Aboard.resetForTests();
    }

    /** A block of rock on the flat world, `half` out each way and `height` high: the y a folk stands at on top. */
    private static int rock(ServerLevel level, int cx, int cz, int half, int height) {
        int base = Kit.surface(level, cx, cz).getY();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int y = base; y < base + height; y++) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        return base + height;
    }

    /**
     * A miner's stairs as MineGoal cuts them: from the head, one across and one down a step, three high,
     * turning clockwise at the edge of its face (six from the middle): `deep` steps. Head first.
     */
    private static List<BlockPos> stairs(ServerLevel level, BlockPos head, Direction first, int cx, int cz, int deep) {
        List<BlockPos> steps = new ArrayList<>();
        steps.add(head);
        BlockPos cur = head;
        Direction d = first;
        for (int k = 1; k <= deep; k++) {
            BlockPos next = cur.relative(d).below();
            for (int turn = 0; turn < 4 && (Math.abs(next.getX() - cx) > 6 || Math.abs(next.getZ() - cz) > 6); turn++) {
                d = d.getClockWise();
                next = cur.relative(d).below();
            }
            steps.add(next);
            cur = next;
        }
        for (BlockPos s : steps) {
            for (int h = 0; h <= 2; h++) level.setBlock(s.above(h), Blocks.AIR.defaultBlockState(), 2);
        }
        return steps;
    }

    /** A field of earth on the rock, a water hole in the middle: where a farmer works. */
    private static void field(ServerLevel level, BlockPos centre) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlock(centre.offset(dx, -1, dz), (dx == 0 && dz == 0 ? Blocks.WATER : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
            }
        }
    }

    private static VillageFolkEntity farmer(GameTestHelper helper, ServerLevel level, BlockPos heart, BlockPos field) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a folk, and its town");
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(field, 4, WorkZone.DEFAULT_DEPTH), "The test field");
        f.clearQueue();
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_PICKAXE));
        f.insertItem(new ItemStack(Items.STONE_HOE));
        f.insertItem(new ItemStack(Items.COBBLESTONE, 16));
        f.insertItem(new ItemStack(Items.BREAD, 8));
        f.insertItem(new ItemStack(Items.WHEAT_SEEDS, 16));
        return f;
    }

    /** No break, no meal and no bell in the middle of a test of something else. */
    private static void keepAtWork(ServerLevel level, VillageFolkEntity f) {
        if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
    }

    // ================================================================== mf01: the boats

    /**
     * The trap the long game's farmers were in. A boat in the water takes aboard whatever bumps into it (a
     * pig, shown); a folk it bumps into is not taken. A folk already sitting in one, as in a world
     * saved like that: put somewhere else (as every rescue the town had did) it is back in its seat on the
     * next tick; within a second it gets out, and the boat does not take it again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "mf01_boats")
    public static void mf01_boats(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        level.setDayTime(2000);
        int x = 880000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos heart = Kit.surface(level, x, Z);
        int px = x + 12;
        Kit.pond(level, px, Z, 5);
        BlockPos water = Kit.surface(level, px, Z).below();
        helper.assertTrue(level.getFluidState(water).is(net.minecraft.tags.FluidTags.WATER), "the pond: " + water.toShortString());
        Boat[] boats = new Boat[3];
        for (int i = 0; i < 3; i++) {
            Boat b = EntityType.BOAT.create(level);
            helper.assertTrue(b != null, "a boat");
            b.moveTo(px - 3 + 3 * i + 0.5, water.getY() + 0.9, Z + 0.5, 0.0F, 0.0F);
            b.addTag("mca_boat");
            level.addFreshEntity(b);
            boats[i] = b;
        }
        VillageFolkEntity bumped = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity seated = VillageFolkSpawnerBlock.raise(level, heart.offset(2, 0, 0), 0.0F);
        helper.assertTrue(bumped != null && seated != null, "two folk");
        // (A pig, not a villager: a villager put into the world near a town becomes one of its folk, VillagerTakeover.)
        Pig pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "a pig");
        pig.moveTo(boats[2].getX(), boats[2].getY(), boats[2].getZ(), 0.0F, 0.0F);
        level.addFreshEntity(pig);
        final long[] tookPig = { -1 }, out = { -1 }, boarded = { -1 };
        final double[] snapped = { -1 };
        final String[] aboardLine = { null };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            keepAtWork(level, bumped);
            keepAtWork(level, seated);
            if (t == 1) {
                // Bumped into the boat: stood right in it.
                bumped.moveTo(boats[0].getX(), boats[0].getY(), boats[0].getZ(), 0.0F, 0.0F);
                // Sat in one already, as the long game's world was saved.
                helper.assertTrue(seated.startRiding(boats[1], true), "sat in the second boat");
                boarded[0] = t;
                return;
            }
            if (t == 2) {
                aboardLine[0] = seated.debugLine();
                // A rescue puts it on its plot: a creature aboard goes where its boat goes.
                seated.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
                return;
            }
            if (t == 3) {
                snapped[0] = seated.distanceTo(boats[1]);
                double off = Math.sqrt(boats[1].distanceToSqr(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5));
                Kit.log("mf01 sat in a boat and put down at the heart, " + String.format("%.1f", off) + " blocks from it: on the next tick it is "
                    + String.format("%.2f", snapped[0]) + " from the boat, aboard " + seated.isPassenger() + "; its card: " + aboardLine[0]);
            }
            if (tookPig[0] < 0 && pig.isPassenger()) tookPig[0] = t;
            if (boarded[0] < 0) return;
            helper.assertTrue(!bumped.isPassenger(), "a folk the boat bumps into is not taken aboard (tick " + t + ") — " + bumped.debugLine());
            if (out[0] < 0 && t > 3 && !seated.isPassenger()) {
                out[0] = t;
                Kit.log("mf01 the seated folk got out " + (t - boarded[0]) + " ticks after it was sat down, at "
                    + seated.blockPosition().toShortString() + " — " + seated.debugLine());
            }
            if (out[0] >= 0 && t - out[0] >= 60) {
                Kit.log("mf01 the pig was taken aboard at tick " + tookPig[0] + "; the folk stood in the first boat "
                    + (t - 1) + " ticks and was never taken; the seated one stayed out " + (t - out[0]) + " ticks beside its boat ("
                    + String.format("%.1f", seated.distanceTo(boats[1])) + " off); got out of boats: " + Aboard.steppedOut(seated.ownerId()));
                helper.assertTrue(tookPig[0] >= 0, "the boat takes a pig aboard: the trap itself");
                helper.assertTrue(aboardLine[0] != null && aboardLine[0].contains("aboard=boat"), "its card says it is in a boat: " + aboardLine[0]);
                helper.assertTrue(snapped[0] >= 0 && snapped[0] < 2.0, "while it sat there, a rescue could not move it: " + snapped[0]);
                helper.assertTrue(out[0] - boarded[0] <= 40, "out within two seconds: " + (out[0] - boarded[0]));
                helper.assertTrue(!seated.isPassenger(), "and not taken again");
                pig.discard();
                for (Boat b : boats) b.discard();
                helper.succeed();
            }
        });
    }

    // ================================================================== mf02: climbs out

    /**
     * A farmer at the far end of a gallery at the bottom of a worked face's stairs, twenty-four deep, one
     * step's floor taken out from under them (as a hand sent for stone used to), so there is no way up it
     * can walk: it climbs out (the stairs, mended as it goes, or steps of its own) and walks back to its
     * field, inside a fixed time, never once lifted.
     */
    @GameTest(template = EMPTY, timeoutTicks = 5000, batch = "mf02_climbs_out")
    public static void mf02_climbs_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        level.setDayTime(2000);
        int x = 882000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        int top = rock(level, x, Z, 28, 30);
        BlockPos fieldAt = new BlockPos(x - 17, top, Z);
        field(level, fieldAt);
        BlockPos heart = new BlockPos(x - 17, top, Z + 14);
        VillageFolkEntity f = farmer(helper, level, heart, fieldAt);
        UUID v = f.ownerId();
        Ledger.note(v, "mine.site", x + "," + Z);
        List<BlockPos> steps = stairs(level, new BlockPos(x, top, Z), Direction.EAST, x, Z, 24);
        MineStairs.record(level, MineStairs.plotKey(new BlockPos(x, 0, Z)), steps);
        BlockPos bottom = steps.get(steps.size() - 1);
        // The gallery off the foot of the stairs, two high, toward the middle of the face.
        BlockPos end = bottom;
        for (int k = 1; k <= 6; k++) {
            end = bottom.north(k);
            level.setBlock(end, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(end.above(), Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos gone = steps.get(12).below();
        level.setBlock(gone, Blocks.AIR.defaultBlockState(), 2);
        f.moveTo(end.getX() + 0.5, end.getY(), end.getZ() + 0.5, 0.0F, 0.0F);
        WorkZone plot = f.workZone();
        helper.assertTrue(plot != null && plot.containsColumn(fieldAt), "its field is its plot");
        int deep = top - end.getY();
        Kit.log("mf02 a farmer " + deep + " down at the end of a gallery at the foot of the stairs (" + steps.size() + " steps), the floor of step 12 gone; "
            + String.join(" | ", TownMine.report(v, heart)));
        helper.assertTrue(deep >= 20 && deep <= 30, "twenty to thirty down: " + deep);
        final double[] last = { end.getX() + 0.5, end.getZ() + 0.5 }, worst = { 0 };
        final long[] up = { -1 }, sent = { -1 }, climbing = { -1 };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            double dx = f.getX() - last[0], dz = f.getZ() - last[1];
            double moved = Math.sqrt(dx * dx + dz * dz);
            last[0] = f.getX();
            last[1] = f.getZ();
            if (t > 2) worst[0] = Math.max(worst[0], moved);
            helper.assertTrue(t <= 2 || moved < 2.5, "never lifted: it moved " + String.format("%.1f", moved) + " in a tick at " + t + " — " + f.debugLine());
            BlockPos at = f.blockPosition();
            Job j = f.peekJob();
            if (sent[0] < 0 && j != null && j.type() == Job.Type.MINE && MineStairs.OUT.equals(j.arg())) {
                sent[0] = t;
                Kit.log("mf02 sent up at " + t + " from " + at.toShortString() + " — " + f.debugLine());
            }
            // Sent up, the climb itself gets under way: a deposit to stores it could not walk to once held its
            // legs with the way up queued behind it, the whole test long.
            if (climbing[0] < 0 && f.running(com.jrpetty.mcassistant.entity.goal.MineGoal.class)) {
                climbing[0] = t;
                Kit.log("mf02 climbing at " + t + " from " + at.toShortString());
            }
            helper.assertTrue(sent[0] < 0 || climbing[0] >= 0 || t - sent[0] <= 200,
                "sent up at " + sent[0] + ", and still not climbing at " + t + " — " + f.debugLine());
            if (t % 250 == 0) Kit.log("mf02 @" + t + " at " + at.toShortString() + " — " + f.debugLine());
            if (up[0] < 0 && at.getY() >= top - 1 && !MineStairs.underground(level, at)) {
                up[0] = t;
                Kit.log("mf02 out of the mine at " + t + " at " + at.toShortString() + "; the floor of step 12 laid again: "
                    + !level.getBlockState(gone).isAir() + " (" + level.getBlockState(gone).getBlock() + ")");
            }
            if (up[0] >= 0 && plot.containsColumn(at) && at.getY() >= top - 1) {
                List<String> report = TownMine.report(v, heart);
                Kit.log("mf02 back at its field at " + t + " (out at " + up[0] + ", sent up at " + sent[0] + ", climbing from " + climbing[0] + "); the most it moved in a tick "
                    + String.format("%.2f", worst[0]) + " | " + String.join(" | ", report));
                helper.assertTrue(report.stream().anyMatch(l -> l.startsWith("Below ground in the mine: none")), "nobody below ground in the mine now: " + report);
                helper.succeed();
            }
        });
    }

    // ================================================================== mf03: walks round

    /**
     * A farmer whose straight way to its field crosses the open top of a mine's stairs: the town fences the
     * stair head round (out of the stores, the head left open, the sign up), and the farmer walks round it,
     * never once below ground.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "mf03_walks_round")
    public static void mf03_walks_round(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        level.setDayTime(2000);
        int x = 884000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        int top = rock(level, x, Z, 26, 16);
        BlockPos fieldAt = new BlockPos(x + 17, top, Z);
        field(level, fieldAt);
        BlockPos heart = new BlockPos(x - 17, top, Z + 8);
        VillageFolkEntity f = farmer(helper, level, heart, fieldAt);
        UUID v = f.ownerId();
        Villages.Village village = Villages.get(v);
        helper.assertTrue(village != null, "the town");
        Ledger.note(v, "mine.site", x + "," + Z);
        // The stairs head two north of the face's middle and run south, across the farmer's straight way east.
        List<BlockPos> steps = stairs(level, new BlockPos(x, top, Z - 2), Direction.SOUTH, x, Z, 14);
        MineStairs.record(level, MineStairs.plotKey(new BlockPos(x, 0, Z)), steps);
        List<BlockPos> top3 = steps.subList(0, 2);
        int wanted = MineSafety.spotsForTests(level, top3).size();
        // The stores pay for it: planks enough for the fences and the sign.
        int put = MineSafety.fenceForTests(level, village);
        BlockPos sign = MineSafety.signForTests(level, top3);
        String[] words = { "", "" };
        if (sign != null && level.getBlockEntity(sign) instanceof SignBlockEntity s) {
            words[0] = s.getFrontText().getMessage(0, false).getString();
            words[1] = s.getFrontText().getMessage(1, false).getString();
        }
        BlockPos head = steps.get(0), first = steps.get(1);
        boolean headOpen = level.getBlockState(head).canBeReplaced() && level.getBlockState(head.above()).canBeReplaced();
        boolean firstOpen = level.getBlockState(first).canBeReplaced();
        int left = MineSafety.spotsForTests(level, top3).size();
        Kit.log("mf03 the stair head at " + head.toShortString() + ": " + wanted + " spots wanted a fence, " + put + " put up, " + left
            + " left; the head open " + headOpen + ", the first step open " + firstOpen + "; the sign at " + (sign == null ? "none" : sign.toShortString())
            + " reads '" + words[0] + " " + words[1] + "' | " + String.join(" | ", TownMine.report(v, heart)));
        helper.assertTrue(wanted >= 6 && put == wanted && left == 0, "the open top of the stairs fenced round: " + wanted + " wanted, " + put + " put, " + left + " left");
        helper.assertTrue(headOpen && firstOpen, "the head and the first step left open: the way in");
        helper.assertTrue(sign != null && "The mine of".equals(words[0]) && Villages.name(v).equals(words[1]), "the sign: " + words[0] + " " + words[1]);
        helper.assertTrue(level.getBlockState(steps.get(2).above(2)).isAir() && level.getBlockState(steps.get(2).east().above(2)).is(BlockTags.WOODEN_FENCES),
            "a fence beside the open step");
        BlockPos startAt = new BlockPos(x - 14, top, Z);
        f.moveTo(startAt.getX() + 0.5, startAt.getY(), startAt.getZ() + 0.5, -90.0F, 0.0F);
        WorkZone plot = f.workZone();
        final int[] lowest = { top };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            BlockPos at = f.blockPosition();
            if (t == 5) f.getNavigation().moveTo(fieldAt.getX() + 0.5, fieldAt.getY(), fieldAt.getZ() + 0.5, 1.0D);
            lowest[0] = Math.min(lowest[0], at.getY());
            if (t % 100 == 0) Kit.log("mf03 @" + t + " at " + at.toShortString() + " — " + f.debugLine());
            helper.assertTrue(at.getY() >= top - 1 && !MineSafety.below(level, v, at),
                "never below ground: at " + at.toShortString() + " (the head's level " + top + ") at " + t);
            if (plot.containsColumn(at)) {
                Kit.log("mf03 at its field at " + t + "; the lowest it stood: " + lowest[0] + " (the ground " + top + ")");
                helper.succeed();
            }
        });
    }

    // ================================================================== mf04: idle hands

    /**
     * A farmer with nothing to do, a miner on a face of the town's mine with rock all round it, and a
     * woodcutter in a wood: lent out, the farmer is sent for timber to the wood, never for stone to the
     * miner's face; its trade and its field are its own all along; and once the lend is up, its work is on
     * its own field again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "mf04_idle_hands")
    public static void mf04_idle_hands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        level.setDayTime(2000);
        int x = 886000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        BlockPos fieldAt = Kit.surface(level, x - 12, Z);
        VillageFolkEntity f = farmer(helper, level, heart, fieldAt);
        f.getInventoryItems().clear();                       // nothing of its own to carry in: the lend is what is tested
        UUID v = f.ownerId();
        // The miner's face: a hill of rock forty out, the town's mine opened on it.
        int mx = x + 40;
        Kit.hill(level, mx, Z, 10, 8, 7L);
        Ledger.note(v, "mine.site", mx + "," + Z);
        VillageFolkEntity m = VillageFolkSpawnerBlock.raise(level, heart.offset(2, 0, 2), 0.0F);
        helper.assertTrue(m != null, "a miner");
        m.setJob(StationTask.MINE);
        m.assignPlot(WorkZone.around(Kit.surface(level, mx, Z), 8, heart.getY() - 24), "Face 0,0");
        // The woodcutter's wood, forty the other way.
        int wx = x - 40;
        Kit.forest(level, wx, Z, 8, 10, 11L);
        VillageFolkEntity w = VillageFolkSpawnerBlock.raise(level, heart.offset(-2, 0, 2), 0.0F);
        helper.assertTrue(w != null, "a woodcutter");
        w.setJob(StationTask.WOOD);
        w.assignPlot(WorkZone.around(Kit.surface(level, wx, Z), 14, WorkZone.DEFAULT_DEPTH), "The wood");
        f.moveTo(fieldAt.getX() + 0.5, fieldAt.getY(), fieldAt.getZ() + 0.5, 0.0F, 0.0F);
        WorkZone own = f.workZone();
        boolean faceInMine = TownMine.inMine(v, m.workZone().center());
        WorkZone forStone = f.groundForTests(GatherGoal.Kind.STONE), forTimber = f.groundForTests(GatherGoal.Kind.LOGS);
        helper.runAtTickTime(2, () -> {
            WorkZone lent = f.lendOutForTests();
            boolean onTheFace = lent != null && m.workZone().containsColumn(lent.center());
            boolean inTheWood = lent != null && w.workZone().center().equals(lent.center());
            BlockPos woodCell = w.workZone().center(), fieldCell = own.center();
            boolean worksWood = f.inZone(woodCell), worksField = f.inZone(fieldCell);
            Kit.log("mf04 the miner's face at " + m.workZone().center().toShortString() + " (in the town's mine " + faceInMine + "); sent for stone, the farmer's ground "
                + (forStone == null ? "none" : forStone.center().toShortString()) + "; for timber "
                + (forTimber == null ? "none" : forTimber.center().toShortString()) + "; lent out to " + (lent == null ? "nothing" : lent.center().toShortString())
                + " (the face " + onTheFace + ", the wood " + inTheWood + "); while lent its work is in the wood " + worksWood + ", in its field " + worksField
                + " — " + f.debugLine());
            helper.assertTrue(faceInMine, "the miner works a face of the town's mine");
            helper.assertTrue(forStone == null || !m.workZone().containsColumn(forStone.center()), "never the miner's face for stone: " + forStone);
            helper.assertTrue(lent == null || !onTheFace, "lent out, never to the face");
            helper.assertTrue(f.stationTask() == StationTask.FARM && f.workZone() != null && f.workZone().equals(own), "its trade and its field kept while lent");
            f.lendLapsedForTests();
            boolean backField = f.inZone(fieldCell), backWood = f.inZone(woodCell);
            Kit.log("mf04 the lend up: its work in its field " + backField + ", in the wood " + backWood + "; trade " + f.stationTask() + " — " + f.debugLine());
            helper.assertTrue(backField && !backWood, "its work is on its own field again");
            helper.assertTrue(f.stationTask() == StationTask.FARM && own.equals(f.workZone()), "its own trade and field");
            helper.succeed();
        });
    }

    // ================================================================== mf05: the work chest

    /**
     * A miner at the foot of its face's stairs, twenty down, sets down its work chest (the one the
     * couriers fetch from): at the surface of the face, under the sky, not down the mine.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "mf05_chest_at_the_surface")
    public static void mf05_chest_at_the_surface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        level.setDayTime(2000);
        int x = 888000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        int top = rock(level, x, Z, 24, 26);
        BlockPos heart = new BlockPos(x - 16, top, Z + 16);
        VillageFolkEntity m = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(m != null && m.ownerId() != null, "a miner, and its town");
        UUID v = m.ownerId();
        Ledger.note(v, "mine.site", x + "," + Z);
        BlockPos face = new BlockPos(x, top, Z);
        m.setJob(StationTask.MINE);
        m.assignPlot(WorkZone.around(face, 8, top - 24), "Face 0,0");
        List<BlockPos> steps = stairs(level, face, Direction.EAST, x, Z, 20);
        MineStairs.record(level, MineStairs.plotKey(face), steps);
        BlockPos bottom = steps.get(steps.size() - 1);
        m.moveTo(bottom.getX() + 0.5, bottom.getY(), bottom.getZ() + 0.5, 0.0F, 0.0F);
        m.getInventoryItems().clear();
        m.insertItem(new ItemStack(Items.CHEST));
        boolean downThere = MineSafety.below(level, v, m.blockPosition());
        BlockPos chest = m.productionChestForTests();
        boolean standing = chest != null && level.getBlockEntity(chest) instanceof ChestBlockEntity;
        boolean sky = chest != null && MineStairs.open(level, chest);
        boolean below = chest != null && MineSafety.below(level, v, chest);
        boolean onStairs = chest != null && steps.stream().anyMatch(s -> s.getX() == chest.getX() && s.getZ() == chest.getZ() && s.getY() <= chest.getY());
        Kit.log("mf05 the miner " + (top - bottom.getY()) + " down (below ground in the mine " + downThere + "); its work chest at "
            + (chest == null ? "none" : chest.toShortString()) + " (the face's ground " + top + "): standing " + standing + ", under the sky " + sky
            + ", below ground " + below + ", on the stairs " + onStairs + " | " + String.join(" | ", TownMine.report(v, heart)));
        helper.assertTrue(downThere, "the miner is below ground in the mine");
        helper.assertTrue(standing, "its work chest is set down");
        helper.assertTrue(Math.abs(chest.getY() - top) <= 3 && sky && !below, "at the surface: " + chest.toShortString() + ", the ground " + top);
        helper.assertTrue(!onStairs, "not on the stairs");
        helper.succeed();
    }
}
