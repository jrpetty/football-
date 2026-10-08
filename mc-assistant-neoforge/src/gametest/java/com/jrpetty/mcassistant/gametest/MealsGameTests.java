package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Meals;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Everybody eats: the midday meal and supper (two meals a day: no breakfast), the children and the old and the folk between
 * trades as well as the hands at their work, out of a real pack, home chest or the stores.
 *
 * <ul>
 * <li><b>me01</b>: a child with nothing in its pack has its two meals out of the village's stores,
 *     one loaf each, and nothing at dawn (there is no breakfast); its card says so.</li>
 * <li><b>me02</b>: with nothing to eat anywhere a folk misses its midday meal and is hungry; food in
 *     the stores by supper, it eats and is hungry no longer.</li>
 * <li><b>me03</b>: a hand that ate a ration at its work while the mealtime was on has had that meal:
 *     nothing more comes out of its pack.</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 340,000 to 344,000, z 50,000) and calls the mealtime look itself
 * with the clock set, so nothing waits on the day going round.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MealsGameTests {

    private static final String EMPTY = "empty";
    private static final long DAY = 24000L * 3;

    /** A village's first folk on clean ground at x, z, its pack and the village's stores emptied of food. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(DAY + 200);
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        noFood(f);
        for (BlockPos p : Villages.storeChests(level, f.ownerId())) {
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).get(DataComponents.FOOD) != null) c.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        return f;
    }

    private static void noFood(VillageFolkEntity f) {
        f.removeMatching(s -> s.get(DataComponents.FOOD) != null, 9999);
    }

    /** Nothing to eat in the village's stores but what is in {@code keep} (the test's larder), nor in this pack:
     *  a folk's kit and the founding stores come in over its first ticks. */
    private static void onlyTheLarder(ServerLevel level, VillageFolkEntity f, Container keep) {
        noFood(f);
        for (BlockPos p : Villages.storeChests(level, f.ownerId())) {
            if (level.getBlockEntity(p) instanceof Container c && c != keep) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).get(DataComponents.FOOD) != null) c.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    /** A chest of the stores beside the heart with so many loaves in it. */
    private static Container larder(ServerLevel level, BlockPos heart, int loaves) {
        BlockPos at = Kit.surface(level, heart.getX() + 3, heart.getZ() + 3);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        // Marked as the village's, as a chest of the stores is (a plain chest by the heart is nobody's).
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        c.setItem(0, new ItemStack(Items.BREAD, loaves));
        c.setChanged();
        return c;
    }

    private static int bread(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.BREAD)) n += c.getItem(i).getCount();
        return n;
    }

    @GameTest(template = EMPTY, timeoutTicks = 1700, batch = "me01_two_meals")
    public static void me01_two_meals(GameTestHelper helper) {
        int x = 340000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity child = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2, z), 0.0F);
        helper.assertTrue(child != null && first.ownerId().equals(child.ownerId()), "a second folk of the village");
        child.setChild(true);
        noFood(child);
        // The first folk eats out of its own pack, so the stores' loaves are the child's alone.
        first.insertGiven(new ItemStack(Items.BREAD, 16));
        Container c = larder(level, heart, 8);
        int[] ate = new int[3];
        String[] said = new String[1];
        // Each meal on a day of its own that the child has not eaten in yet (left to itself over its
        // first ticks it may have had its own meal on the day the test began).
        long[] at = { DAY + 240000 + 300, DAY + 264000 + 6000, DAY + 288000 + 11500 };
        int[] when = { 10, 700, 1400 };
        String[] meal = { "dawn", "the midday meal", "supper" };
        for (int k = 0; k < 3; k++) {
            final int m = k;
            helper.runAtTickTime(when[m], () -> {
                // A child still, by the stores: the clock jumps whole days between the meals, and a child left to
                // itself grew up and went to the town's mine, fifty-odd blocks out of reach of the stores.
                child.setChild(true);
                child.childhoodForTests(0);
                child.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
                onlyTheLarder(level, child, c);
                // Topped up for each meal: the larder is a chest of the stores, and the rest of the village eats out of it too.
                c.setItem(0, new ItemStack(Items.BREAD, 8));
                c.setChanged();
                level.setDayTime(at[m]);
                child.childhoodForTests(0);                      // born today, by the new clock
                int before = bread(c);
                String held = Meals.heldForTests(child);
                Meals.tick(child);
                ate[m] = before - bread(c);
                said[0] = Meals.line(child);
                Kit.log("me01 " + meal[m] + ": " + said[0] + "; a loaf out of the stores? " + ate[m] + " (" + bread(c) + " left)"
                    + (held.isEmpty() ? "" : "; held back: " + held) + "; " + child.debugLine());
            });
        }
        helper.runAtTickTime(1401, () -> {
            String line = said[0];
            helper.assertTrue(ate[0] == 0, "no breakfast: nothing out of the stores at dawn: " + ate[0]);
            helper.assertTrue(ate[1] == 1, "the midday meal is a loaf out of the stores: " + ate[1]);
            helper.assertTrue(ate[2] == 1, "supper another: " + ate[2]);
            helper.assertTrue(child.meals().missedInRow() == 0, "none missed: " + child.meals().missedInRow());
            helper.assertTrue(line.contains("1 meal today") && line.contains("bread") && line.contains("supper"), "its card says so: " + line);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "me02_hungry")
    public static void me02_hungry(GameTestHelper helper) {
        int x = 342000, z = 50000;
        VillageFolkEntity f = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(10, () -> {
            noFood(f);
            level.setDayTime(DAY + 7750);                        // the end of the midday meal, nothing to eat
            Meals.tick(f);
            String line = Meals.line(f);
            Kit.log("me02 no midday meal: " + line);
            helper.assertTrue(f.meals().missedInRow() == 1 && line.startsWith("Hungry"), "it misses its midday meal and is hungry: " + line);
            int[] today = Meals.today(f.ownerId());
            helper.assertTrue(today[1] >= 1, "the town's books count the meal missed: " + today[1]);
        });
        helper.runAtTickTime(700, () -> {
            Container c = larder(level, heart, 4);
            onlyTheLarder(level, f, c);
            level.setDayTime(DAY + 11500);
            Meals.tick(f);
            String line = Meals.line(f);
            Kit.log("me02 supper: " + line + "; bread left " + bread(c));
            helper.assertTrue(f.meals().missedInRow() == 0 && f.meals().eatenToday() == 1, "fed at supper, hungry no longer: " + line);
            helper.assertTrue(bread(c) == 3, "out of the stores: " + bread(c));
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "me03_worked_meal")
    public static void me03_worked_meal(GameTestHelper helper) {
        int x = 344000, z = 50000;
        VillageFolkEntity f = founder(helper, x, z);
        ServerLevel level = helper.getLevel();
        helper.runAtTickTime(10, () -> {
            noFood(f);
            f.insertGiven(new ItemStack(Items.BREAD, 3));
            level.setDayTime(DAY + 6000);
            f.meals().ate(level.getGameTime(), "Bread");           // a ration at its work, the mealtime on
            Meals.tick(f);
            int left = f.countFood();
            Kit.log("me03 after a ration at work: " + Meals.line(f) + "; bread carried " + left);
            helper.assertTrue(f.meals().eatenToday() == 1, "the ration was its midday meal: " + f.meals().eatenToday());
            helper.assertTrue(left == 3, "and nothing more was eaten: " + left);
            helper.succeed();
        });
    }
}
