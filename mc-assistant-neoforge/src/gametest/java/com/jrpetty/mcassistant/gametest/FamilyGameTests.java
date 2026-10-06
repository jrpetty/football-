package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Families;
import com.jrpetty.mcassistant.entity.Graves;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Families, pets and gardens (entity/Families): what a household does together.
 *
 * <ul>
 * <li><b>fm01</b>: a household with a child takes in a wild wolf with bones out of its own chest, offered one
 *     at a time as a player would; it is the household's and named; by day it follows the child, at night it
 *     sits at home; and a household with a pet takes in no other.</li>
 * <li><b>fm02</b>: three children of an afternoon play hide-and-seek in the square (the seeker counts to ten
 *     aloud, the rest run off and hide), then tag, running about.</li>
 * <li><b>fm03</b>: at bedtime the children's grandmother, an old folk from another house, comes over, sits down
 *     with them and tells them a story out of the town's chronicle: their parents' wedding, by their names.</li>
 * <li><b>fm04</b>: at supper a child away from home does not eat where it is: it goes home and eats out of the
 *     household's chest; its parents, done with their work, come home and eat a loaf each out of it too.</li>
 * <li><b>fm05</b>: a household with savings buys flowers and a sapling from the stores and plants them by its
 *     house: a flower bed in front, a sapling to one side; once.</li>
 * <li><b>fm06</b>: on the anniversary of a death the widow takes a flower from the stores out to the grave and
 *     lays it there; a couple marks its wedding anniversary, and is the happier for it.</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 430,000 to 436,000, z 50,000), most of what it checks called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FamilyGameTests {

    private static final String EMPTY = "empty";
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

    /** A village's first folk on clean flat ground at x, z, in broad day. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, z, 56);
        Kit.prepare(level, x, z, 56);
        BlockPos heart = flat(level, x, z, 48);
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

    /** The two of them wed, in a house of their own, with a child born to them there. Returns {house, child}. */
    private static Object[] family(GameTestHelper helper, ServerLevel level, VillageFolkEntity mother, VillageFolkEntity father, BlockPos at) {
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        mother.life().widowed();
        father.life().widowed();
        mother.life().partnerWith(father.getUUID(), father.displayNameCap());
        father.life().partnerWith(mother.getUUID(), mother.displayNameCap());
        BlockPos home = house(level, id, at);
        Homes.tickForTests(level, v);
        helper.assertTrue(home.equals(Homes.homeOf(mother)) && home.equals(Homes.homeOf(father)), "the couple's house: " + Homes.homeOf(mother));
        mother.insertItem(new ItemStack(Items.BREAD, 2));
        father.insertItem(new ItemStack(Items.BREAD, 2));
        VillageFolkEntity child = mother.raiseChildWith(father);
        helper.assertTrue(child != null, "a child born");
        Homes.tickForTests(level, v);
        helper.assertTrue(home.equals(Homes.homeOf(child)), "the child lives with its parents: " + Homes.homeOf(child));
        return new Object[]{ home, child };
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static boolean saidContains(String words) {
        for (String s : Families.saidForTests()) if (s.contains(words)) return true;
        return false;
    }

    private static boolean remembers(VillageFolkEntity f, String words) {
        for (Persona.Memory m : f.persona().memories()) if (m.text().contains(words)) return true;
        return false;
    }

    private static Wolf wolf(ServerLevel level, BlockPos at) {
        Wolf w = EntityType.WOLF.create(level);
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        w.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(w);
        return w;
    }

    // ============================================================ fm01: a pet

    /**
     * A household with a child, bones in its chest and wolves about: one of the parents takes bones out of the
     * chest and offers them to a wolf, one at a time, till it takes to them (a chance in three a bone, as a
     * player tames one); what was not offered goes back in the chest. The wolf is the household's, named, and
     * one in the history. By day it follows the child; at night it sits at home. A household with a pet takes
     * in no other.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fm01_pet")
    public static void fm01_pet(GameTestHelper helper) {
        int x = 430000, z = 50000;
        VillageFolkEntity mother = founder(helper, x, z, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        Object[][] fam = new Object[1][];
        Wolf[] wolves = new Wolf[2];
        helper.runAtTickTime(5, () -> {
            fam[0] = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0][0];
            BlockPos chest = Families.homeChestForTests(level, mother);
            helper.assertTrue(chest != null && level.getBlockEntity(chest) instanceof Container, "the household's chest");
            Container c = (Container) level.getBlockEntity(chest);
            c.setItem(0, new ItemStack(Items.BONE, 40));
            c.setChanged();
            wolves[0] = wolf(level, home.offset(10, 0, 0));
            wolves[1] = wolf(level, home.offset(12, 0, 5));
        });
        helper.runAtTickTime(12, () -> {
            BlockPos home = (BlockPos) fam[0][0];
            VillageFolkEntity child = (VillageFolkEntity) fam[0][1];
            Container c = (Container) level.getBlockEntity(Families.homeChestForTests(level, mother));
            int attempts = 0;
            UUID pet = null;
            VillageFolkEntity tamer = null;
            while (attempts < 6 && pet == null) {
                if (Families.errandForTests(mother) == null && Families.errandForTests(father) == null) Families.petsForTests(level, v);
                tamer = Families.errandForTests(mother) != null ? mother : Families.errandForTests(father) != null ? father : null;
                helper.assertTrue(tamer != null, "a grown-up of the household sets out to take in a wolf (attempt " + attempts + ")");
                helper.assertTrue("PET".equals(Families.errandForTests(tamer)), "its errand is a pet: " + Families.errandForTests(tamer));
                Families.errandNowForTests(level, tamer);
                pet = Families.petForTests(id, home);
                attempts++;
                Kit.log("fm01 attempt " + attempts + ": " + (pet == null ? "it would not come" : "tamed") + "; bones in the chest "
                    + count(c, s -> s.is(Items.BONE)) + ", carried " + tamer.countCarried(s -> s.is(Items.BONE)));
            }
            helper.assertTrue(pet != null, "the household has a pet after " + attempts + " tries");
            helper.assertTrue(level.getEntity(pet) instanceof Wolf, "the pet is one of the wolves");
            Wolf w = (Wolf) level.getEntity(pet);
            int left = count(c, s -> s.is(Items.BONE));
            Kit.log("fm01 " + w.getName().getString() + " tamed by " + tamer.displayNameCap() + "; " + (40 - left) + " bones offered; said "
                + Families.saidForTests());
            helper.assertTrue(w.isTame() && w.hasCustomName(), "tame and named: " + w.isTame() + " " + w.getName().getString());
            helper.assertTrue(left < 40 && left >= 40 - 6 * attempts, "bones out of the household's chest, a handful a try: " + left);
            helper.assertTrue(mother.countCarried(s -> s.is(Items.BONE)) == 0 && father.countCarried(s -> s.is(Items.BONE)) == 0,
                "the bones not offered went back in the chest");
            helper.assertTrue(saidContains("named it") || remembers(child, "we took in"), "the child remembers taking it in");
            boolean vanilla = false;
            for (WrappedGoal g : w.goalSelector.getAvailableGoals()) if (g.getGoal() instanceof SitWhenOrderedToGoal) vanilla = true;
            helper.assertFalse(vanilla, "it sits when the household says so, not for good because its keeper is no player");
            // One a household.
            Families.petsForTests(level, v);
            helper.assertTrue(Families.errandForTests(mother) == null && Families.errandForTests(father) == null,
                "a household with a pet takes in no other");
            Wolf other = wolves[0] == w ? wolves[1] : wolves[0];
            helper.assertFalse(other.isTame(), "the other wolf is still wild");
            // By day it follows the child.
            level.setDayTime(DAY + 9000);
            level.updateSkyBrightness();
            String day = Families.walkPetForTests(level, child);
            Kit.log("fm01 by day: " + day + "; the child's card: " + Families.cardLine(child));
            helper.assertTrue(day != null && day.contains(child.displayNameCap()), "by day it follows the child: " + day);
            helper.assertFalse(w.isOrderedToSit(), "and is up and about");
            helper.assertTrue(Families.cardLine(child).contains(w.getName().getString()), "the child's card names it: " + Families.cardLine(child));
            // At night it sits at home.
            level.setDayTime(DAY + 18000);
            level.updateSkyBrightness();
            BlockPos hearth = Families.hearthForTests(level, child);
            w.teleportTo(hearth.getX() + 0.5, hearth.getY(), hearth.getZ() + 0.5);
            String night = Families.walkPetForTests(level, child);
            Kit.log("fm01 at night: " + night + " at " + w.blockPosition().toShortString() + " (home " + hearth.toShortString() + ")");
            helper.assertTrue("sitting at home".equals(night) && w.isOrderedToSit() && w.isInSittingPose(), "at night it sits at home: " + night);
            helper.succeed();
        });
    }

    // ============================================================ fm02: the children's games

    /**
     * Three children of an afternoon, no park: hide-and-seek in the square. The seeker counts to ten out loud at
     * the den, the other two run off and hide; then it goes looking. Then tag: whoever is it runs after the
     * others, and they run from it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "fm02_games")
    public static void fm02_games(GameTestHelper helper) {
        int x = 431000, z = 50000;
        VillageFolkEntity first = founder(helper, x, z, DAY + 8000);
        ServerLevel level = helper.getLevel();
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        List<VillageFolkEntity> kids = new ArrayList<>();
        int[][] at = { { 3, 2 }, { -3, 2 }, { 0, -3 } };
        for (int[] d : at) kids.add(another(helper, heart.offset(d[0], 0, d[1]), id));
        double[][] start = new double[3][];
        int[] phase = { 0 };
        long[] since = { 0 };
        helper.runAtTickTime(5, () -> {
            for (VillageFolkEntity k : kids) {
                k.setChild(true);
                k.bornDaysAgo(1);
                k.clearQueue();
            }
            level.setDayTime(DAY + 8000);
            boolean on = Families.gameForTests(level, id, false);
            String state = Families.gameStateForTests(level, id);
            Kit.log("fm02 hide-and-seek: " + state + " at " + Families.playgroundForTests(id));
            helper.assertTrue(on && state != null && state.startsWith("hide|the square|players=3"), "three children play hide-and-seek in the square: " + state);
            for (int i = 0; i < 3; i++) start[i] = new double[]{ kids.get(i).getX(), kids.get(i).getZ() };
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0) return;
            long tod = level.getDayTime() % 24000L;
            if (tod < 7500L || tod > 10000L) level.setDayTime(DAY + 8000);
            if (t % 20 != 0) return;
            BlockPos ground = Families.playgroundForTests(id);
            String state = Families.gameStateForTests(level, id);
            if (phase[0] == 1) {
                boolean counted = saidContains("Coming, ready or not!");
                double far = 0;
                for (VillageFolkEntity k : kids) far = Math.max(far, Math.sqrt(k.blockPosition().distSqr(ground)));
                if (t % 100 == 0) Kit.log("fm02 @" + t + " " + state + "; furthest from the den " + String.format("%.1f", far));
                if (counted && far >= 4.0 && state != null && (state.contains("seeking=true") || !state.contains("rounds=0"))) {
                    Kit.log("fm02 counted to ten and went seeking at " + t + "; said " + Families.saidForTests());
                    helper.assertTrue(saidContains("One… two…"), "the seeker counted out loud");
                    boolean remembered = false;
                    for (VillageFolkEntity k : kids) if (remembers(k, "hide-and-seek")) remembered = true;
                    helper.assertTrue(remembered, "the children remember the game");
                    // Now tag.
                    boolean on = Families.gameForTests(level, id, true);
                    helper.assertTrue(on, "a game of tag");
                    for (int i = 0; i < 3; i++) start[i] = new double[]{ kids.get(i).getX(), kids.get(i).getZ() };
                    since[0] = t;
                    phase[0] = 2;
                } else if (t > 900) {
                    helper.fail("hide-and-seek never got going: " + state + "; counted " + counted + ", furthest " + far + "; said "
                        + Families.saidForTests());
                }
                return;
            }
            double moved = 0;
            for (int i = 0; i < 3; i++) moved += Math.hypot(kids.get(i).getX() - start[i][0], kids.get(i).getZ() - start[i][1]);
            if (t % 100 == 0) Kit.log("fm02 tag @" + t + " " + state + "; moved " + String.format("%.1f", moved));
            if (t - since[0] >= 200 && moved >= 4.0) {
                int near = 0;
                for (VillageFolkEntity k : kids) if (k.blockPosition().distSqr(ground) <= 16 * 16) near++;
                Kit.log("fm02 tag: " + state + "; the children ran " + String.format("%.1f", moved) + " blocks between them, " + near + " about the square");
                helper.assertTrue(state != null && state.startsWith("tag|"), "it is tag now: " + state);
                helper.assertTrue(near >= 2, "the children play about the square: " + near);
                for (VillageFolkEntity k : kids) {
                    String doing = Families.doing(k);
                    if (doing != null) Kit.log("fm02 " + k.displayNameCap() + ": " + doing);
                }
                helper.succeed();
            } else if (t - since[0] > 600) {
                helper.fail("nobody ran about at tag: moved " + moved + "; " + state);
            }
        });
    }

    // ============================================================ fm03: a story at bedtime

    /**
     * After supper, the children's grandmother — an old folk living in another house, the mother's mother —
     * is free: she is chosen over the parents to tell the story, comes over, sits down with the child and tells
     * it a page of the town's chronicle by the names of the folk in it: its parents' wedding, three days ago.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "fm03_story")
    public static void fm03_story(GameTestHelper helper) {
        int x = 432000, z = 50000;
        VillageFolkEntity father = founder(helper, x, z, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = father.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity mother = another(helper, heart.east(2), id);
        VillageFolkEntity gran = another(helper, heart.west(2), id);
        Object[][] fam = new Object[1][];
        int[] lines = { 0 };
        String[] wed = { "" };
        helper.runAtTickTime(5, () -> {
            gran.life().widowed();
            fam[0] = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0][0];
            VillageFolkEntity child = (VillageFolkEntity) fam[0][1];
            gran.setAgeForTests(64);
            mother.parentIds().add(gran.getUUID());
            BlockPos door = Kit.surface(level, home.getX(), home.getZ() + 8);
            gran.moveTo(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, 0.0F, 0.0F);
            long day = level.getDayTime() / 24000L;
            wed[0] = father.displayNameCap() + " and " + mother.displayNameCap() + " were wed";
            Villages.tell(id, day - 3, wed[0]);
            Villages.tell(id, day - 5, "the well was opened");
            // Bedtime, after supper.
            level.setDayTime(DAY + 13000);
            level.updateSkyBrightness();
            for (VillageFolkEntity f : List.of(father, mother, gran)) f.clearQueue();
            List<String> story = Families.storyForTests(level, child);
            UUID teller = Families.tellerForTests(child);
            Kit.log("fm03 the story, told by " + (teller == null ? "nobody" : teller.equals(gran.getUUID()) ? "the grandmother, " + gran.displayNameCap()
                + " (" + gran.ageYears() + ")" : "a parent") + ": " + story);
            helper.assertTrue(gran.getUUID().equals(teller), "the old grandmother tells it, not the parents: " + teller);
            boolean names = false;
            for (String l : story) if (l.contains(wed[0])) names = true;
            helper.assertTrue(names, "the story is the parents' wedding out of the chronicle, by their names: " + story);
            lines[0] = story.size();
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 6 || fam[0] == null) return;
            long tod = level.getDayTime() % 24000L;
            if (tod > 13600L) level.setDayTime(DAY + 13000);
            if (t % 20 != 0) return;
            VillageFolkEntity child = (VillageFolkEntity) fam[0][1];
            int told = Families.storyToldForTests(child);
            if (t % 100 == 0) Kit.log("fm03 @" + t + ": " + told + " of " + lines[0] + " lines told; the grandmother " + Families.doing(gran)
                + " at " + gran.blockPosition().toShortString() + ", the child " + Families.doing(child) + " at " + child.blockPosition().toShortString());
            if (told >= lines[0] && lines[0] > 0) {
                Kit.log("fm03 said: " + Families.saidForTests());
                boolean aloud = false;
                for (String s : Families.saidForTests()) if (s.startsWith(gran.displayNameCap() + ":") && s.contains(wed[0])) aloud = true;
                helper.assertTrue(aloud, "she tells it out loud, by their names");
                helper.assertTrue(remembers(child, "told us a story"), "the child remembers being told it");
                helper.succeed();
            } else if (t > 1700) {
                helper.fail("the story was not told: " + told + " of " + lines[0] + " lines; the grandmother " + Families.doing(gran) + ", the child "
                    + Families.doing(child));
            }
        });
    }

    // ============================================================ fm04: supper at home

    /**
     * Supper, the family out at the square. The child, with nothing in its pack, does not eat out of the stores
     * where it stands: it goes home and eats out of the household's chest. Its parents, off work at nightfall,
     * come home and eat there too, a loaf each out of the chest.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "fm04_supper")
    public static void fm04_supper(GameTestHelper helper) {
        int x = 433000, z = 50000;
        VillageFolkEntity mother = founder(helper, x, z, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        Object[][] fam = new Object[1][];
        long supper = DAY + 24000L + 11200L;
        int[] phase = { 0 };
        Container[] chest = new Container[1];
        helper.runAtTickTime(5, () -> {
            fam[0] = family(helper, level, mother, father, heart.offset(-14, 0, 14));
            VillageFolkEntity child = (VillageFolkEntity) fam[0][1];
            chest[0] = (Container) level.getBlockEntity(Families.homeChestForTests(level, mother));
            for (int i = 0; i < chest[0].getContainerSize(); i++) chest[0].setItem(i, ItemStack.EMPTY);
            chest[0].setItem(0, new ItemStack(Items.BREAD, 6));
            chest[0].setChanged();
            for (VillageFolkEntity f : List.of(mother, father, child)) {
                f.removeMatching(s -> s.get(DataComponents.FOOD) != null, 9999);
                f.meals().load(new CompoundTag());               // nothing eaten lately: the day's meals all to come
                f.clearQueue();
            }
            child.moveTo(heart.getX() + 3.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
            level.setDayTime(supper);
            level.updateSkyBrightness();
            com.jrpetty.mcassistant.entity.Meals.tick(child);
            Families.Table where = Families.tableForTests(level, child);
            Kit.log("fm04 supper at the square: " + where + "; " + com.jrpetty.mcassistant.entity.Meals.line(child) + "; bread at home "
                + count(chest[0], s -> s.is(Items.BREAD)));
            helper.assertTrue(where == Families.Table.WAIT, "a child of a household, away from home at supper: its supper waits for the table: " + where);
            helper.assertTrue(child.meals().eatenToday() == 0 && count(chest[0], s -> s.is(Items.BREAD)) == 6, "it eats nothing where it is");
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0 || fam[0] == null) return;
            VillageFolkEntity child = (VillageFolkEntity) fam[0][1];
            BlockPos home = (BlockPos) fam[0][0];
            long tod = level.getDayTime() % 24000L;
            if (phase[0] == 1 && tod > 12000L) level.setDayTime(supper);
            if (phase[0] == 2 && (tod > 12750L || tod < 12600L)) {
                level.setDayTime(supper - 11200L + 12600L);
                level.updateSkyBrightness();
            }
            if (t % 20 != 0) return;
            int bread = count(chest[0], s -> s.is(Items.BREAD));
            if (phase[0] == 1) {
                // Nothing on them at their work: their supper is the household's too.
                for (VillageFolkEntity f : List.of(mother, father)) f.removeMatching(s -> s.get(DataComponents.FOOD) != null, 9999);
                if (t % 100 == 0) Kit.log("fm04 @" + t + " the child: " + Families.doing(child) + " at " + child.blockPosition().toShortString()
                    + " (home " + home.toShortString() + "); " + com.jrpetty.mcassistant.entity.Meals.line(child) + "; bread " + bread);
                if (Families.suppedAtHomeForTests(child)) {
                    double d = Math.sqrt(child.blockPosition().distSqr(home));
                    Kit.log("fm04 the child ate at home at " + t + ", " + String.format("%.1f", d) + " from the middle of the house; bread " + bread);
                    helper.assertTrue(d <= 7.0, "it ate at home: " + d);
                    helper.assertTrue(bread <= 5, "a loaf out of the household's chest: " + bread);
                    // The parents, off work at nightfall, a little way from home, their supper still to come; the chest
                    // filled up again, so what they eat out of it is counted on its own.
                    BlockPos out = Kit.surface(level, home.getX() + 2, home.getZ() + 8);
                    for (VillageFolkEntity f : List.of(mother, father)) {
                        f.moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0.0F, 0.0F);
                        f.removeMatching(s -> s.get(DataComponents.FOOD) != null, 9999);
                        f.meals().load(new CompoundTag());
                        f.clearQueue();
                    }
                    for (int i = 0; i < chest[0].getContainerSize(); i++) chest[0].setItem(i, ItemStack.EMPTY);
                    chest[0].setItem(0, new ItemStack(Items.BREAD, 6));
                    chest[0].setChanged();
                    level.setDayTime(supper - 11200L + 12600L);
                    level.updateSkyBrightness();
                    phase[0] = 2;
                } else if (t > 900) {
                    helper.fail("the child never had its supper at home: " + Families.doing(child) + " at " + child.blockPosition().toShortString()
                        + "; " + com.jrpetty.mcassistant.entity.Meals.line(child) + "; bread " + bread);
                }
                return;
            }
            boolean both = Families.suppedAtHomeForTests(mother) && Families.suppedAtHomeForTests(father)
                && mother.meals().eatenToday() >= 1 && father.meals().eatenToday() >= 1;
            if (t % 100 == 0) Kit.log("fm04 @" + t + " the parents: " + Families.doing(mother) + " / " + Families.doing(father) + "; supped at home "
                + Families.suppedAtHomeForTests(mother) + " / " + Families.suppedAtHomeForTests(father) + "; bread " + bread);
            if (both) {
                Kit.log("fm04 the household ate at home: bread left " + bread + "; said " + Families.saidForTests());
                helper.assertTrue(bread == 4, "a loaf each out of the household's chest: " + bread);
                helper.succeed();
            } else if (t > 1700) {
                helper.fail("the parents did not eat at home: " + Families.doing(mother) + " / " + Families.doing(father) + "; "
                    + com.jrpetty.mcassistant.entity.Meals.line(mother) + " / " + com.jrpetty.mcassistant.entity.Meals.line(father));
            }
        });
    }

    // ============================================================ fm05: a garden

    /**
     * A household with eighty coins between them, flowers and a sapling in the stores: the one with the most
     * put by buys them and plants them by the house — a flower bed in front, the sapling to one side, clear of
     * the walls — paying the stores (the coin into the treasury). Once: the house does not get another.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fm05_garden")
    public static void fm05_garden(GameTestHelper helper) {
        int x = 434000, z = 50000;
        VillageFolkEntity mother = founder(helper, x, z, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            mother.earn(40);
            father.earn(40);
            Homes.storeForTests(level, v, new ItemStack(Items.POPPY, 4));
            Homes.storeForTests(level, v, new ItemStack(Items.OAK_SAPLING, 1));
            Predicate<ItemStack> flowers = s -> s.is(ItemTags.SMALL_FLOWERS);
            Predicate<ItemStack> saplings = s -> s.is(ItemTags.SAPLINGS);
            int flowersBefore = Market.stock(level, id, flowers), saplingsBefore = Market.stock(level, id, saplings);
            int purses = mother.purse() + father.purse(), coins = Ledger.coins(id);
            int[] blocksBefore = blocks(level, home);
            helper.assertTrue(Families.planGardenForTests(level, v, home), "a household with savings plans a garden");
            VillageFolkEntity gardener = Families.errandForTests(mother) != null ? mother : father;
            helper.assertTrue("GARDEN".equals(Families.errandForTests(gardener)), "one of them is to plant it: " + Families.errandForTests(gardener));
            helper.assertTrue(Families.errandNowForTests(level, gardener), "and plants it");
            int[] blocksAfter = blocks(level, home);
            int flowersAfter = Market.stock(level, id, flowers), saplingsAfter = Market.stock(level, id, saplings);
            int paid = purses - mother.purse() - father.purse(), took = Ledger.coins(id) - coins;
            Kit.log("fm05 " + gardener.displayNameCap() + " planted " + (blocksAfter[0] - blocksBefore[0]) + " flowers and "
                + (blocksAfter[1] - blocksBefore[1]) + " saplings by the house; the stores " + flowersBefore + "->" + flowersAfter + " flowers, "
                + saplingsBefore + "->" + saplingsAfter + " saplings; paid " + paid + ", the treasury took " + took + "; said " + Families.saidForTests());
            helper.assertTrue(blocksAfter[0] - blocksBefore[0] >= 2, "a flower bed by the house: " + (blocksAfter[0] - blocksBefore[0]));
            helper.assertTrue(blocksAfter[1] - blocksBefore[1] == 1, "and a sapling: " + (blocksAfter[1] - blocksBefore[1]));
            helper.assertTrue(flowersBefore - flowersAfter == blocksAfter[0] - blocksBefore[0] && saplingsBefore - saplingsAfter == 1,
                "every one of them out of the stores");
            helper.assertTrue(paid > 0 && paid == took, "bought out of their purses, the coin into the treasury: paid " + paid + ", took " + took);
            helper.assertTrue(Families.gardenedForTests(id, home), "the house has had its garden");
            helper.assertFalse(Families.planGardenForTests(level, v, home), "once: no second garden");
            helper.succeed();
        });
    }

    /** {small flowers, saplings} standing round this house (its lot and a few blocks beyond). */
    private static int[] blocks(ServerLevel level, BlockPos home) {
        int[] n = new int[2];
        for (BlockPos p : BlockPos.betweenClosed(home.offset(-12, -2, -12), home.offset(12, 3, 12))) {
            if (level.getBlockState(p).is(BlockTags.SMALL_FLOWERS)) n[0]++;
            if (level.getBlockState(p).is(BlockTags.SAPLINGS)) n[1]++;
        }
        return n;
    }

    // ============================================================ fm06: remembrance

    /**
     * Hollis died twenty-eight days ago (a year by the town's calendar), Marrow twenty-seven. On Hollis's
     * anniversary the widow takes a poppy from the stores out to the grave and lays it in front of the stone,
     * and says a word; nobody goes to Marrow's grave today. A couple wed twenty-eight days ago marks its
     * anniversary: a word between them, and each the happier for the day.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fm06_remembrance")
    public static void fm06_remembrance(GameTestHelper helper) {
        int x = 435000, z = 50000;
        // Day forty: a year (twenty-eight days) back from it is a day the town could have buried somebody on.
        // (On day four the grave's year fell on day minus twenty-four, and no anniversary is kept of that.)
        VillageFolkEntity widow = founder(helper, x, z, 24000L * 40 + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = widow.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity a = another(helper, heart.east(2), id);
        VillageFolkEntity b = another(helper, heart.east(3), id);
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            for (VillageFolkEntity f : List.of(widow, a, b)) f.life().widowed();
            a.life().partnerWith(b.getUUID(), b.displayNameCap());
            b.life().partnerWith(a.getUUID(), a.displayNameCap());
            BlockPos yard = Kit.surface(level, x + 16, z - 16);
            BuildGoal.stamp(level, "graveyard", yard, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "graveyard", yard, Direction.NORTH);
            Ledger.buried(id, new Ledger.Grave("Hollis", day - 200, day - 28, "of old age", "", widow.displayNameCap(), "Farmer"));
            Ledger.buried(id, new Ledger.Grave("Marrow", day - 220, day - 27, "of old age", "", a.displayNameCap(), "Miner"));
            widow.removeMatching(s -> s.is(ItemTags.SMALL_FLOWERS), 999);
            for (VillageFolkEntity f : List.of(widow, a, b)) f.ensurePersona();
            Homes.storeForTests(level, v, new ItemStack(Items.POPPY, 2));
            int poppies = Market.stock(level, id, s -> s.is(ItemTags.SMALL_FLOWERS));
            Families.remembranceForTests(level, v);
            Kit.log("fm06 the anniversaries: " + widow.displayNameCap() + " " + Families.errandForTests(widow) + " for " + Families.graveForTests(widow)
                + "; " + a.displayNameCap() + " " + Families.errandForTests(a));
            helper.assertTrue("GRAVE".equals(Families.errandForTests(widow)) && "Hollis".equals(Families.graveForTests(widow)),
                "the widow is to visit Hollis's grave on the anniversary: " + Families.graveForTests(widow));
            helper.assertTrue(Families.errandForTests(a) == null, "Marrow's anniversary is tomorrow: nobody goes today");
            helper.assertTrue(Families.errandNowForTests(level, widow), "she goes");
            BlockPos[] grave = Graves.graveOf(id, 0);
            helper.assertTrue(grave != null, "Hollis's grave in the graveyard");
            int left = Market.stock(level, id, s -> s.is(ItemTags.SMALL_FLOWERS));
            Kit.log("fm06 at the grave " + grave[0].toShortString() + ": " + level.getBlockState(grave[1]) + " in front of it; poppies " + poppies + "->" + left
                + "; said " + Families.saidForTests());
            helper.assertTrue(left == poppies - 1, "a flower out of the stores: " + poppies + " -> " + left);
            helper.assertTrue(level.getBlockState(grave[1]).is(BlockTags.SMALL_FLOWERS), "laid on the grave: " + level.getBlockState(grave[1]));
            helper.assertTrue(remembers(widow, "flower to Hollis's grave"), "she remembers it");
            helper.assertTrue(saidContains("Hollis"), "and says a word to Hollis");
            // A wedding anniversary.
            Families.wed(id, a.getUUID(), b.getUUID(), day - 28);
            Families.anniversariesForTests(level, v);
            Kit.log("fm06 the couple: " + a.persona().moodWhy() + " / " + b.persona().moodWhy() + "; " + Families.cardLine(a));
            helper.assertTrue(a.persona().moodWhy().contains("anniversary") && b.persona().moodWhy().contains("anniversary"),
                "both the happier for their anniversary: " + a.persona().moodWhy() + " / " + b.persona().moodWhy());
            helper.assertTrue(remembers(a, "wedding anniversary") && remembers(b, "wedding anniversary"), "and they remember it");
            helper.assertTrue(saidContains("anniversary"), "a word between them");
            helper.succeed();
        });
    }
}
