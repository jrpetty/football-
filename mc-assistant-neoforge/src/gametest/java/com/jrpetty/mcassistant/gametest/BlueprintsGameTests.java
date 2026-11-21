package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * [blueprints] The designs stood up in the world, to see that what BlueprintSoundnessTest proves of the drawings is
 * true of the blocks: the shop, the café, the store, the workshop, the granary, the stable, the post office and the
 * storehouse shed, each stamped as the showcase stamps it, and then every chest in it opened (the game's own test:
 * nothing on its lid), every ladder and hanging lantern holding where it is, every barrel there, and every door
 * standing with the ground clear in front of it, two places out.
 *
 * <p>On ground of their own: x 1640000 to 1640100, z 66000 to 66030.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BlueprintsGameTests {

    private static final String EMPTY = "empty";
    private static final int X = 1640000, Z = 66000;

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bp01_the_designs_stand_true")
    public static void bp01_the_designs_stand_true(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        List<String> designs = List.of("shop", "cafe", "store", "workshop", "granary", "stable", "postoffice", "storage");
        Kit.hold(level, X + 45, Z + 15, 70);
        Kit.prepare(level, X + 45, Z + 15, 70);
        List<String> faults = new ArrayList<>();
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < designs.size(); i++) {
            String name = designs.get(i);
            BlockPos at = Kit.surface(level, X + 30 * (i % 4), Z + 30 * (i / 4));
            BuildGoal.stamp(level, name, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            java.util.Set<BlockPos> planned = new java.util.HashSet<>();
            for (BuildGoal.Placement p : BuildGoal.plan(name, at, Direction.NORTH, 13)) planned.add(p.pos());
            int chests = 0, ladders = 0, lamps = 0, casks = 0, doors = 0;
            for (BuildGoal.Placement p : BuildGoal.plan(name, at, Direction.NORTH, 13)) {
                BlockPos pos = p.pos();
                BlockState st = level.getBlockState(pos);
                String here = name + " " + p.part() + " at " + pos.subtract(at).toShortString();
                switch (p.part()) {
                    case CHEST -> {
                        chests++;
                        if (!st.is(Blocks.CHEST)) faults.add(here + ": no chest (" + st + ")");
                        else if (ChestBlock.isChestBlockedAt(level, pos)) faults.add(here + ": the chest will not open");
                    }
                    case LADDER -> {
                        ladders++;
                        if (!st.is(Blocks.LADDER) || !st.canSurvive(level, pos)) faults.add(here + ": the ladder does not hold (" + st + ")");
                    }
                    case LANTERN -> {
                        lamps++;
                        if (!st.is(Blocks.LANTERN) || !st.canSurvive(level, pos)) faults.add(here + ": the lantern does not hold (" + st + ")");
                    }
                    case BARREL -> {
                        casks++;
                        if (!st.is(Blocks.BARREL)) faults.add(here + ": no barrel (" + st + ")");
                    }
                    case DOOR -> {
                        doors++;
                        // The way out in front of a street door (toward the drawing's front, south here): clear, on
                        // the drawing's own doorstep and beyond it where the drawing reaches (the rest is the lot's
                        // ground, which the builders level and the paths keep).
                        if (p.way() == com.jrpetty.mcassistant.entity.goal.Blueprints.Way.BACK) {
                            for (int out = 1; out <= 2; out++) {
                                BlockPos f = pos.south(out);
                                if (out > 1 && !planned.contains(f) && !planned.contains(f.above())) continue;
                                if (level.getBlockState(f).blocksMotion() || level.getBlockState(f.above()).blocksMotion()) {
                                    faults.add(here + ": the way out is blocked " + out + " out");
                                }
                            }
                        }
                    }
                    default -> { }
                }
            }
            seen.append(name).append(": ").append(chests).append(" chests, ").append(casks).append(" casks, ").append(ladders)
                .append(" ladder rungs, ").append(lamps).append(" lanterns, ").append(doors).append(" doors; ");
        }
        Kit.log("bp01 " + seen + "faults " + faults);
        helper.assertTrue(faults.isEmpty(), "the designs stand true: " + faults);
        helper.succeed();
    }
}
