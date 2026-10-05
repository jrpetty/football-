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
 * Everybody eats: breakfast, the midday meal and supper, the children and the old and the folk between
 * trades as well as the hands at their work, out of a real pack, home chest or the stores.
 *
 * <ul>
 * <li><b>me01</b>: a child with nothing in its pack has its three meals out of the village's stores,
 *     one loaf each, and its card says so.</li>
 * <li><b>me02</b>: with nothing to eat anywhere a folk misses its breakfast and is hungry; food in the
 *     stores by the midday meal, it eats and is hungry no longer.</li>
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

    /** A chest of the stores beside the heart with so many loaves in it. */
    private static Container larder(ServerLevel level, BlockPos heart, int loaves) {
        BlockPos at = Kit.surface(level, heart.getX() + 3, heart.getZ() + 3);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
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

    @GameTest(template = EMPTY, timeoutTicks = 1700, batch = "me01_three_meals")
    public static void me01_three_meals(GameTestHelper helper) {
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
        int[] seen = new int[3];
        helper.runAtTickTime(10, () -> {
            level.setDayTime(DAY + 300);
            Meals.tick(child);
            seen[0] = bread(c);
            Kit.log("me01 breakfast: " + Meals.line(child) + "; bread in the stores " + seen[0]);
        });
        helper.runAtTickTime(700, () -> {
            level.setDayTime(DAY + 6000);
            Meals.tick(child);
            seen[1] = bread(c);
            Kit.log("me01 the midday meal: " + Meals.line(child) + "; bread in the stores " + seen[1]);
        });
        helper.runAtTickTime(1400, () -> {
            level.setDayTime(DAY + 11500);
            Meals.tick(child);
            seen[2] = bread(c);
            String line = Meals.line(child);
            Kit.log("me01 supper: " + line + "; bread in the stores " + seen[2]);
            helper.assertTrue(seen[0] == 7, "breakfast is a loaf out of the stores: " + seen[0]);
            helper.assertTrue(seen[1] == 6, "the midday meal another: " + seen[1]);
            helper.assertTrue(seen[2] == 5, "supper a third: " + seen[2]);
            helper.assertTrue(child.meals().eatenToday() == 3 && child.meals().missedInRow() == 0,
                "three meals had, none missed: " + child.meals().eatenToday());
            helper.assertTrue(line.contains("3 meals today") && line.contains("bread"), "its card says so: " + line);
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
            level.setDayTime(DAY + 2350);                        // the end of breakfast, nothing to eat
            Meals.tick(f);
            String line = Meals.line(f);
            Kit.log("me02 no breakfast: " + line);
            helper.assertTrue(f.meals().missedInRow() == 1 && line.startsWith("Hungry"), "it misses breakfast and is hungry: " + line);
            int[] today = Meals.today(f.ownerId());
            helper.assertTrue(today[1] >= 1, "the town's books count the meal missed: " + today[1]);
        });
        helper.runAtTickTime(700, () -> {
            Container c = larder(level, heart, 4);
            level.setDayTime(DAY + 6000);
            Meals.tick(f);
            String line = Meals.line(f);
            Kit.log("me02 the midday meal: " + line + "; bread left " + bread(c));
            helper.assertTrue(f.meals().missedInRow() == 0 && f.meals().eatenToday() == 1, "fed at midday, hungry no longer: " + line);
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
