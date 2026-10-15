package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.MineGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The town's mine (TownMine): one mine for the whole town, opened once fifty to a hundred-odd blocks out
 * and kept; its miners on faces side by side; a face worked out is never handed out again; no miner of
 * the town cuts a block under its houses or its square, nor any block somebody laid (MineGoal.mayDig); and
 * a miner whose plot the town has built over moves out to a face of the mine.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownMineGameTests {

    private static final String EMPTY = "empty";

    private static boolean sameFace(UUID v, BlockPos a, BlockPos b) {
        BlockPos fa = TownMine.faceCentre(v, a), fb = TownMine.faceCentre(v, b);
        return fa != null && fb != null && fa.getX() == fb.getX() && fa.getZ() == fb.getZ();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tm01_town_mine")
    public static void tm01_town_mine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 560000, z = 60000;
        Kit.hold(level, x, z, 150);
        Kit.prepare(level, x, z, 150);
        BlockPos heart = Kit.surface(level, x, z);
        List<VillageFolkEntity> miners = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
            helper.assertTrue(f != null, "a folk");
            f.setJob(StationTask.MINE);
            miners.add(f);
        }
        UUID v = miners.get(0).ownerId();
        helper.assertTrue(v != null && v.equals(miners.get(2).ownerId()), "one town");
        TownMine.forgetForTests(v);

        // The site: opened once, out beyond the town, and kept.
        BlockPos site = TownMine.site(level, v, heart, p -> false);
        helper.assertTrue(site != null, "the town's mine is opened");
        double d = Math.sqrt(site.atY(0).distSqr(heart.atY(0)));
        Kit.log("tm01 the mine opened at " + site.getX() + ", " + site.getZ() + ": " + Math.round(d) + " blocks out (the town reaches "
            + Villages.townReach(v) + ")");
        helper.assertTrue(d >= 50 && d <= 115, "fifty to a hundred-odd blocks from the heart: " + d);
        helper.assertTrue(Villages.outsideTown(v, heart, site, TownMine.PITCH / 2), "the first face clear of the town");
        BlockPos again = TownMine.site(level, v, heart.offset(300, 0, 300), p -> true);
        helper.assertTrue(again != null && again.getX() == site.getX() && again.getZ() == site.getZ(), "the site is kept: " + again);

        // Faces side by side: the first miner on the first face, the second on the next one along.
        VillageFolkEntity a = miners.get(0), b = miners.get(1), c = miners.get(2);
        int depth = heart.getY() - 3;
        BlockPos fa = TownMine.faceFor(level, a, heart, p -> false);
        helper.assertTrue(fa != null, "a face for the first miner");
        a.assignPlot(WorkZone.around(fa, 8, depth), "Face A");
        BlockPos fb = TownMine.faceFor(level, b, heart, p -> false);
        helper.assertTrue(fb != null, "a face for the second miner");
        b.assignPlot(WorkZone.around(fb, 8, depth), "Face B");
        int apart = Math.max(Math.abs(fa.getX() - fb.getX()), Math.abs(fa.getZ() - fb.getZ()));
        Kit.log("tm01 faces: " + fa.toShortString() + " and " + fb.toShortString() + ", " + apart + " apart");
        helper.assertTrue(fa.getX() == site.getX() && fa.getZ() == site.getZ(), "the first face is the site");
        helper.assertTrue(!sameFace(v, fa, fb) && apart == TownMine.PITCH, "the second face is the next one along: " + apart);
        helper.assertTrue(TownMine.inMine(v, fa) && TownMine.inMine(v, fb), "both in the mine");

        // A face worked out is not handed out again; the miner takes a fresh one.
        TownMine.spent(v, fa);
        BlockPos fa2 = TownMine.faceFor(level, a, heart, p -> false);
        helper.assertTrue(fa2 != null && !sameFace(v, fa2, fa) && !sameFace(v, fa2, fb),
            "a worked-out face is not given again, nor a mate's: " + fa2);
        helper.assertTrue(Villages.outsideTown(v, heart, fa2, 8), "the next face is clear of the town too");
        a.assignPlot(WorkZone.around(fa2, 8, depth), "Face A2");

        // What a miner may cut: rock, earth and ore; never cobblestone or planks somebody laid.
        int y = heart.getY() - 2;
        BlockPos cobble = fa2.atY(y).offset(1, 0, 0), stone = fa2.atY(y).offset(2, 0, 0), ore = fa2.atY(y).offset(3, 0, 0);
        BlockPos planks = fa2.atY(y).offset(4, 0, 0), earth = fa2.atY(y).offset(5, 0, 0);
        level.setBlock(cobble, Blocks.COBBLESTONE.defaultBlockState(), 2);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), 2);
        level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        level.setBlock(earth, Blocks.DIRT.defaultBlockState(), 2);
        MineGoal dig = new MineGoal(a);
        boolean mCobble = dig.mayDigForTests(cobble), mStone = dig.mayDigForTests(stone), mOre = dig.mayDigForTests(ore);
        boolean mPlanks = dig.mayDigForTests(planks), mEarth = dig.mayDigForTests(earth);
        Kit.log("tm01 on its face: cobblestone " + mCobble + ", stone " + mStone + ", iron ore " + mOre + ", planks " + mPlanks + ", earth " + mEarth);
        helper.assertTrue(mStone && mOre && mEarth, "stone, ore and earth are the miner's to cut");
        helper.assertTrue(!mCobble && !mPlanks, "cobblestone and planks somebody laid are not");

        // Under the square: a plot staked on the heart (as a plot staked before the town grew out over it).
        c.assignPlot(WorkZone.around(heart, 8, depth), "Old pit");
        BlockPos underSquare = heart.offset(3, -2, 3);
        boolean mSquare = new MineGoal(c).mayDigForTests(underSquare);
        Kit.log("tm01 under the square: may dig " + mSquare + " (" + level.getBlockState(underSquare).getBlock() + ")");
        helper.assertTrue(!mSquare, "nothing under the square is a miner's to cut");

        // A house goes up on the miner's face: nothing under it is dug, and its miner moves on.
        BlockPos house = fa2.offset(2, 0, 2);
        Ledger.built(v, "house", house, Direction.SOUTH);
        TownMine.redraw(v);
        boolean mHouse = dig.mayDigForTests(house.offset(0, -2, 0));
        helper.assertTrue(!mHouse, "nothing under a house is a miner's to cut");

        boolean cMoved = c.seekTheSeamForTests(), aMoved = a.seekTheSeamForTests();
        BlockPos cAt = c.workZone().center(), aAt = a.workZone().center();
        Kit.log("tm01 moved on: the old pit's miner " + cMoved + " to " + cAt.toShortString() + ", the built-over face's " + aMoved
            + " to " + aAt.toShortString() + " | " + String.join(" | ", TownMine.report(v, heart)));
        helper.assertTrue(cMoved && TownMine.inMine(v, cAt) && Villages.outsideTown(v, heart, cAt, 8),
            "the miner under the square moves out to a face of the mine: " + cAt.toShortString());
        helper.assertTrue(aMoved && !sameFace(v, aAt, fa2) && TownMine.inMine(v, aAt), "the miner whose face was built over takes another");
        helper.assertTrue(!sameFace(v, aAt, cAt) && !sameFace(v, aAt, fb) && !sameFace(v, cAt, fb), "every miner on a face of its own");
        helper.succeed();
    }
}
