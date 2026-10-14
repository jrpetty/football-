package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Floors;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Tidy;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * [tidy] The town cares for its looks: no grass inside its buildings (Floors), the tidy crew (Tidy) and the town's
 * eyesores and looks.
 *
 * <p>Each on its own ground (x 1660000 to 1679999, z 66000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TidyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private static String fixes(List<Floors.Fix> all) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size() && i < 8; i++) {
            Floors.Fix f = all.get(i);
            sb.append(f.lay() ? "earth " : "plant ").append(f.pos().toShortString()).append(' ').append(f.style()).append("; ");
        }
        return all.size() + " [" + sb + "]";
    }

    /** Grass and flowers growing over this house's whole lot, as on a meadow. */
    private static void meadow(ServerLevel level, BlockPos at, int half) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos g = Kit.surface(level, at.getX() + dx, at.getZ() + dz).below();
                if (!level.getBlockState(g).is(Blocks.GRASS_BLOCK)) level.setBlockAndUpdate(g, Blocks.GRASS_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(g.above(), ((dx + dz) & 3) == 0 ? Blocks.POPPY.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState());
            }
        }
    }

    // ============================================================ no grass inside

    /**
     * A house raised for real by a builder on a meadow (grass under its whole lot, grass and poppies standing on it):
     * its floor and footing are laid over the grass, so once it stands there is no grass block, earth or plant
     * anywhere its drawing lays a block or on its floor; and none comes back after a day of random ticks (fast).
     */
    @GameTest(template = EMPTY, timeoutTicks = 40000, batch = "tdy01_house_on_grass")
    public static void tdy01_house_on_grass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        int x = 1660000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "a village");
        BlockPos at = Kit.surface(level, x + 14, Z);
        meadow(level, at, 6);
        helper.assertTrue(level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK), "the house's lot is grass: " + level.getBlockState(at.below()));
        for (int i = 0; i < 4; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 3; i++) builder.insertItem(new ItemStack(Items.OAK_PLANKS, 64));
        builder.insertItem(new ItemStack(Items.OAK_LOG, 32));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.insertItem(new ItemStack(Items.OAK_SLAB, 32));
        builder.insertItem(new ItemStack(Items.STONE_BRICKS, 32));
        builder.insertItem(new ItemStack(Items.CHEST, 2));
        builder.insertItem(new ItemStack(Items.FURNACE, 2));
        builder.insertItem(new ItemStack(Items.CRAFTING_TABLE, 2));
        builder.insertItem(new ItemStack(Items.OAK_DOOR, 2));
        builder.insertItem(new ItemStack(Items.GLASS_PANE, 32));
        builder.insertItem(new ItemStack(Items.TORCH, 8));
        builder.insertItem(new ItemStack(Items.RED_BED, 4));
        builder.enqueue(Job.buildAt("house", at, Direction.NORTH, 0));
        Ledger.Building house = new Ledger.Building("house", at, Direction.NORTH);
        long[] built = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (built[0] < 0) {
                if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);      // building is day work
                if (t % 1200 == 0) Kit.log("tdy01 @" + t + " built=" + Villages.builtList(builder.ownerId()) + " — " + builder.debugLine());
                if (Villages.builtList(builder.ownerId()).contains("house")) {
                    built[0] = t;
                    List<Floors.Fix> left = Floors.inside(level, "house", house);
                    Kit.log("tdy01 the house stands at tick " + t + "; inside it: " + fixes(left) + "; under the door "
                        + level.getBlockState(at.below()) + ", the floor at " + at.below().north(2).toShortString() + " "
                        + level.getBlockState(at.below().north(2)));
                    helper.assertTrue(left.isEmpty(), "no grass, earth or plant inside the house once built: " + fixes(left));
                    // A day of random ticks, fast: whatever grass could creep in or grow, would.
                    level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(60, level.getServer());
                    level.getServer().tickRateManager().setTickRate(10000.0F);
                } else if (t >= 15000) {
                    helper.fail("the house was not built in 15000 ticks: " + Villages.builtList(builder.ownerId()) + " — " + builder.debugLine());
                }
                return;
            }
            if (t - built[0] >= 24000) {
                level.getServer().tickRateManager().setTickRate(20.0F);
                level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3, level.getServer());
                List<Floors.Fix> left = Floors.inside(level, "house", house);
                int grass = 0;
                int[] h = Blueprints.groundHalf("house");
                for (BlockPos p : BlockPos.betweenClosed(at.offset(-h[0] + 1, -1, -h[1] + 1), at.offset(h[0] - 1, 1, h[1] - 1))) {
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.DIRT) || BuildGoal.isWildPlant(st)) grass++;
                }
                Kit.log("tdy01 a day of random ticks on: inside " + fixes(left) + "; grass, earth or plants within the walls " + grass);
                helper.assertTrue(left.isEmpty() && grass == 0, "still no grass inside the house after a day: " + fixes(left) + ", " + grass + " within the walls");
                helper.succeed();
            }
        });
    }

    /**
     * An old house, built before the builder laid its floor over the ground: half its floor still the meadow's grass,
     * grass and a poppy standing on it. The tidy crew's round finds it and puts it right: the earth floored with what
     * the rest of the floor is laid in (spruce boards, out of the stores), the plants taken up; nothing left inside.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tdy02_old_house_floored")
    public static void tdy02_old_house_floored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        int x = 1661000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos at = Kit.surface(level, x + 16, Z + 2);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, p -> {
            if (p.part() == BuildGoal.Part.BLOCK) return p.style() == Blueprints.Style.FLOOR ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                : Blocks.COBBLESTONE.defaultBlockState();
            if (p.part() == BuildGoal.Part.WINDOW) return Blocks.GLASS_PANE.defaultBlockState();
            if (p.part() == BuildGoal.Part.DOOR) return Blocks.OAK_DOOR.defaultBlockState();
            return null;
        });
        Ledger.built(village, "house", at, Direction.NORTH);
        // Back as it was before: half the floor's boards the meadow's grass, grass and a poppy on it.
        int turned = 0;
        for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) {
            if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.FLOOR) continue;
            if (((p.pos().getX() + p.pos().getZ()) & 1) != 0) continue;
            level.setBlockAndUpdate(p.pos(), Blocks.GRASS_BLOCK.defaultBlockState());
            if (level.getBlockState(p.pos().above()).isAir()) {
                level.setBlockAndUpdate(p.pos().above(), (turned % 3 == 0 ? Blocks.POPPY : Blocks.SHORT_GRASS).defaultBlockState());
            }
            turned++;
        }
        final int turnedUp = turned;
        List<Floors.Fix> before = Floors.inside(level, village, Ledger.buildings(village).get(0));
        Kit.log("tdy02 the old house: " + turned + " floor cells grass; inside it " + fixes(before));
        helper.assertTrue(turned >= 6 && before.size() >= turned, "the old house has grass inside: " + fixes(before));
        // Boards in the stores to floor it with.
        boolean stocked = false;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).isEmpty()) { c.setItem(i, new ItemStack(Items.SPRUCE_PLANKS, 64)); stocked = true; break; }
                }
            }
            if (stocked) break;
        }
        helper.assertTrue(stocked, "spruce boards in the stores");
        int[] n = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 20 != 0) return;
            Tidy.tickForTests(level, v);
            List<Floors.Fix> left = Floors.inside(level, village, Ledger.buildings(village).get(0));
            if (left.isEmpty()) {
                int spruce = 0, grass = 0;
                for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) {
                    if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.FLOOR) continue;
                    BlockState st = level.getBlockState(p.pos());
                    if (st.is(Blocks.SPRUCE_PLANKS)) spruce++;
                    if (BuildGoal.bareEarth(st)) grass++;
                }
                int[] done = Tidy.floorsForTests(level, village);
                Kit.log("tdy02 floored by tick " + t + ": " + done[0] + " cells floored, " + done[1] + " plants up; the floor " + spruce
                    + " spruce boards, " + grass + " earth");
                helper.assertTrue(grass == 0 && done[0] >= turnedUp, "every grass cell floored: " + done[0] + " of " + turnedUp);
                helper.assertTrue(spruce >= turnedUp * 2 - 2, "in spruce boards, as the rest of the floor: " + spruce);
                helper.succeed();
            } else if (t >= 2200) {
                helper.fail("the old house still has grass inside: " + fixes(left) + "; done " + java.util.Arrays.toString(Tidy.floorsForTests(level, village)));
            }
        });
    }
}
