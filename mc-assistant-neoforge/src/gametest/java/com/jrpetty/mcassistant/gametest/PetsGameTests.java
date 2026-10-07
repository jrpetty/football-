package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.PetBedBlock;
import com.jrpetty.mcassistant.block.PetBowlBlock;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Families;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Pets;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchClears;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [pets] The town's pets (entity/Pets), on top of Families' pet: who has one and how it is named, a dog's day and a
 * cat's, the bowl filled from the stores, the pets' things made by the town's trades from their recipes, litters and the
 * town's cap, a player's stray, a dog's bark that brings out the watch, a lost dog brought home (the quest board's seam),
 * and the pet show at the fair.
 *
 * <ul>
 * <li><b>pe01</b>: a settled household with a child that wants a pet takes in the stray hanging about the town, with a
 *     bone out of its own chest; a child names it, a name nobody in the town has, and the card shows it.</li>
 * <li><b>pe02</b>: by day the dog goes to the household's child, and when the child goes elsewhere the dog follows.</li>
 * <li><b>pe03</b>: at night the household's cat goes to the child's bed and lies on it; on a fine afternoon it is up on
 *     the roof of the house, lying in the sun.</li>
 * <li><b>pe04</b>: a pet bowl from the stores is set out in the house; a child fills it with bones from the stores (three
 *     servings, three bones out); the hungry dog goes to it and eats one.</li>
 * <li><b>pe05</b>: the five new things have recipes, ages and a worth; the tailor makes the dog bed and the collar, the
 *     cook the treats and the shop's hand the bowl, each out of the stores' real materials; the shop's order book has them.</li>
 * <li><b>pe06</b>: a household's dog has a litter (its parents fed first, out of the stores), one of the young goes to a
 *     household that wants a pet, and the town's cap holds through litter after litter.</li>
 * <li><b>pe07</b>: a player takes home a stray with a treat, befriends a household's dog with treats (it follows), and buys
 *     a pup from a litter for three coins paid to the family.</li>
 * <li><b>pe08</b>: at night the dog barks at a zombie near the house and a guard goes after it; the next morning the
 *     household says so.</li>
 * <li><b>pe09</b>: a dog goes missing for the quest board (Pets.lost), out past the town's edge; a player finds it, it
 *     follows the player home, the family thanks them, and the quest board hears of it.</li>
 * <li><b>pe10</b>: the pet show judges the best-kept pet (fed, a collar and a bed of its own) over a hungry one, and the
 *     ribbon (the stores' paper) goes to its household.</li>
 * </ul>
 *
 * <p>Each on its own ground (x 980,000 to 998,000, z 66,000), most of what it checks called directly; the chance events
 * (a pet taken ill, a dog off after a rabbit, a random litter) held off.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PetsGameTests {

    private static final String EMPTY = "empty";
    private static final long DAY = 24000L * 4;
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the ground and the families

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

    /** A village's first folk on clean flat ground at x, z: settled, and the chance events held off. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, long time, int hold) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, hold);
        Kit.prepare(level, x, Z, hold);
        BlockPos heart = flat(level, x, Z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        Pets.calmForTests(true);
        Pets.settledForTests(true);
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

    /** Into the household's chest. */
    private static Container chest(GameTestHelper helper, ServerLevel level, VillageFolkEntity member) {
        BlockPos at = Families.homeChestForTests(level, member);
        helper.assertTrue(at != null && level.getBlockEntity(at) instanceof Container, "the household's chest");
        return (Container) level.getBlockEntity(at);
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** The town's stores emptied: only what a test puts there. */
    private static void emptyStores(GameTestHelper helper, ServerLevel level, UUID village) {
        List<BlockPos> chests = Villages.storeChests(level, village);
        helper.assertTrue(!chests.isEmpty(), "the town has its stores");
        for (BlockPos p : chests) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
    }

    /** These into the stores, as a player would bring them. */
    private static void stock(ServerLevel level, UUID village, ItemStack... goods) {
        int g = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, goods[g++].copy());
            }
            c.setChanged();
        }
        Villages.forgetStock();
    }

    private static int stores(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    private static boolean remembers(VillageFolkEntity f, String words) {
        for (Persona.Memory m : f.persona().memories()) if (m.text().contains(words)) return true;
        return false;
    }

    /** A household with a child and a dog (or a cat): a stray by the house, taken in with a bone (a fish) out of its chest. */
    private static TamableAnimal pet(GameTestHelper helper, ServerLevel level, UUID id, BlockPos home, VillageFolkEntity member, boolean cat) {
        Container c = chest(helper, level, member);
        c.setItem(0, new ItemStack(cat ? Items.COD : Items.BONE, 6));
        c.setChanged();
        Pets.wantForTests(id, home, true);
        TamableAnimal stray = Pets.strayForTests(level, id, cat, Kit.surface(level, home.getX() + 8, home.getZ() + 6));
        helper.assertTrue(stray != null && !stray.isTame(), "a stray about");
        String took = Pets.takeInForTests(level, id, home);
        helper.assertTrue(stray.getUUID().equals(Families.petForTests(id, home)), "the household took in the stray: " + took);
        Pets.wantForTests(id, home, null);
        return stray;
    }

    // ============================================================ pe01: a pet comes home, and is named

    /**
     * A settled household with a child that wants a pet, a stray dog hanging about the town, bones in the household's
     * chest: the town's own round sends a grown-up of the house out to it with a bone (or, with nobody free, the test sees
     * it through), and the dog is the household's: tame, named by the child (a name nobody in the town has), the bone out
     * of the chest and the rest back, the child remembering it, the card saying so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pe01_adopt")
    public static void pe01_adopt(GameTestHelper helper) {
        int x = 980000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            VillageFolkEntity child = (VillageFolkEntity) fam[1];
            Container c = chest(helper, level, mother);
            c.setItem(0, new ItemStack(Items.BONE, 6));
            c.setChanged();
            boolean byNature = Pets.wantsForTests(level, id, home);
            Pets.wantForTests(id, home, true);
            TamableAnimal stray = Pets.strayForTests(level, id, false, Kit.surface(level, home.getX() + 9, home.getZ() + 4));
            helper.assertTrue(stray instanceof Wolf && !stray.isTame(), "a stray dog about the town");
            int[] before = Pets.countForTests(id);
            // The town's own round: a grown-up of the household sent out to it, if one is free; else seen through now.
            Pets.takeInsForTests(level, id);
            VillageFolkEntity sent = Pets.errandForTests(mother) != null ? mother : Pets.errandForTests(father) != null ? father : null;
            String how;
            if (sent != null) {
                how = sent.displayNameCap() + "'s " + Pets.errandForTests(sent) + " errand: " + Pets.errandNowForTests(level, sent);
            } else {
                how = "nobody free; taken in now: " + Pets.takeInForTests(level, id, home);
            }
            UUID pet = Families.petForTests(id, home);
            int bones = count(c, s -> s.is(Items.BONE));
            String name = stray.getName().getString();
            int[] after = Pets.countForTests(id);
            Kit.log("pe01 the household wants a pet by its nature: " + byNature + "; " + how + "; pet " + name + " tame " + stray.isTame()
                + "; bones in the chest 6 -> " + bones + "; town's pets " + before[0] + " -> " + after[0] + " of " + after[1] + "; said "
                + Pets.saidForTests() + "; card: " + Pets.cardLine(child));
            helper.assertTrue(stray.getUUID().equals(pet), "the household has the stray for its pet: " + pet);
            helper.assertTrue(stray.isTame() && stray.hasCustomName() && !name.isEmpty(), "tame and named: " + name);
            for (var a : Villages.folkOf(id)) helper.assertFalse(a.displayNameCap().equals(name), "a name nobody in the town has: " + name);
            helper.assertTrue(bones < 6 && bones >= 4, "a bone or two out of the household's chest, the rest back: " + bones);
            helper.assertTrue(mother.countCarried(s -> s.is(Items.BONE)) == 0 && father.countCarried(s -> s.is(Items.BONE)) == 0,
                "nothing kept back in a pack");
            helper.assertTrue(remembers(child, "we took in " + name), "the child remembers taking it in");
            boolean named = false;
            for (String s : Pets.saidForTests()) if (s.startsWith(child.displayNameCap()) && s.contains(name)) named = true;
            helper.assertTrue(named, "the child named it: " + Pets.saidForTests());
            helper.assertTrue(Pets.cardLine(child).contains(name), "the card: " + Pets.cardLine(child));
            helper.assertTrue(after[0] == before[0] && after[0] <= after[1], "the stray became the household's pet, within the cap: "
                + after[0] + " of " + after[1]);
            helper.succeed();
        });
    }

    // ============================================================ pe02: the dog follows the child

    /**
     * By day, the household's dog twelve blocks from its child goes to it; the child taken twenty blocks off, the dog
     * goes after it there too.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pe02_follows")
    public static void pe02_follows(GameTestHelper helper) {
        int x = 982000;
        VillageFolkEntity mother = founder(helper, x, DAY + 9000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        Object[][] state = new Object[1][];
        int[] phase = { 0 };
        long[] since = { 0 };
        String[] doing = { "" };
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            VillageFolkEntity child = (VillageFolkEntity) fam[1];
            TamableAnimal dog = pet(helper, level, id, home, mother, false);
            BlockPos a = Kit.surface(level, x + 6, Z - 10);
            child.teleportTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            dog.teleportTo(a.getX() + 12.5, a.getY(), a.getZ() + 0.5);
            since[0] = level.getGameTime();
            state[0] = new Object[]{ child, dog };
            Kit.log("pe02 child " + child.displayNameCap() + " at " + a.toShortString() + ", the dog " + dog.getName().getString() + " twelve blocks off");
        });
        helper.onEachTick(() -> {
            if (state[0] == null || level.getGameTime() % 10 != 0) return;
            String d = Families.walkPetForTests(level, (VillageFolkEntity) state[0][0]);
            if (d != null) doing[0] = d;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] != null, "set up");
            VillageFolkEntity child = (VillageFolkEntity) state[0][0];
            TamableAnimal dog = (TamableAnimal) state[0][1];
            double d = dog.distanceTo(child);
            if (phase[0] == 0) {
                helper.assertTrue(d < 4.5, "the dog on its way to the child: " + String.format("%.1f", d) + " (" + doing[0] + ")");
                Kit.log("pe02 at the child's heels after " + (level.getGameTime() - since[0]) + " ticks, " + String.format("%.1f", d) + " off ("
                    + doing[0] + ")");
                helper.assertTrue(doing[0].contains(child.displayNameCap()), "after the child: " + doing[0]);
                BlockPos b = Kit.surface(level, (int) child.getX() - 20, (int) child.getZ() + 4);
                child.teleportTo(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
                phase[0] = 1;
                since[0] = level.getGameTime();
                helper.fail("on to the second spot");
            }
            helper.assertTrue(d < 4.5, "the dog following the child to the second spot: " + String.format("%.1f", d) + " (" + doing[0] + ")");
            Kit.log("pe02 followed the child twenty blocks in " + (level.getGameTime() - since[0]) + " ticks, " + String.format("%.1f", d)
                + " off (" + doing[0] + "); not sitting: " + !dog.isOrderedToSit());
            helper.assertFalse(dog.isOrderedToSit(), "up and about, not sitting");
        });
    }

    // ============================================================ pe03: a cat on a bed and on the roof

    /**
     * The household's cat at the hearth at night goes to the child's bed and lies on it; on a fine afternoon it is up on
     * the roof of the house, lying in the sun.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pe03_cat")
    public static void pe03_cat(GameTestHelper helper) {
        int x = 984000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        Object[][] state = new Object[1][];
        int[] phase = { 0 };
        long[] since = { 0 };
        String[] doing = { "" };
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            VillageFolkEntity child = (VillageFolkEntity) fam[1];
            TamableAnimal cat = pet(helper, level, id, home, mother, true);
            helper.assertTrue(cat instanceof Cat, "a cat");
            boolean bed = child.claimBedNear(home);
            BlockPos hearth = Families.hearthForTests(level, child);
            level.setDayTime(DAY + 18000);
            cat.teleportTo(hearth.getX() + 0.5, hearth.getY(), hearth.getZ() + 0.5);
            since[0] = level.getGameTime();
            phase[0] = bed && child.bedPos() != null ? 0 : 1;
            state[0] = new Object[]{ child, cat };
            Kit.log("pe03 " + cat.getName().getString() + " at the hearth " + hearth.toShortString() + " at night; the child's bed "
                + child.bedPos() + " (claimed " + bed + ")");
            if (phase[0] == 1) level.setDayTime(DAY + 24000 + 6000);
        });
        helper.onEachTick(() -> {
            if (state[0] == null || level.getGameTime() % 10 != 0) return;
            String d = Families.walkPetForTests(level, (VillageFolkEntity) state[0][0]);
            if (d != null) doing[0] = d;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] != null, "set up");
            VillageFolkEntity child = (VillageFolkEntity) state[0][0];
            Cat cat = (Cat) state[0][1];
            if (phase[0] == 0) {
                boolean onBed = level.getBlockState(cat.blockPosition()).getBlock() instanceof BedBlock;
                helper.assertTrue(onBed && cat.isLying(), "the cat on the child's bed, lying down: at " + cat.blockPosition().toShortString()
                    + " on " + level.getBlockState(cat.blockPosition()).getBlock() + " (" + doing[0] + ")");
                Kit.log("pe03 asleep on the bed at " + cat.blockPosition().toShortString() + " after " + (level.getGameTime() - since[0])
                    + " ticks (" + doing[0] + ")");
                level.setDayTime(DAY + 24000 + 6000);
                level.setWeatherParameters(24000, 0, false, false);
                phase[0] = 1;
                since[0] = level.getGameTime();
                helper.fail("on to the afternoon");
            }
            BlockPos roof = Pets.roofForTests(level, child);
            BlockPos hearth = Families.hearthForTests(level, child);
            helper.assertTrue(roof != null && roof.getY() > hearth.getY() + 2, "the house has a roof over its rooms: " + roof);
            helper.assertTrue(cat.isLying() && cat.getY() >= roof.getY() - 0.05 && cat.blockPosition().distManhattan(roof) <= 2,
                "the cat up on the roof in the sun: at " + cat.blockPosition().toShortString() + ", the roof " + roof.toShortString()
                    + " (" + doing[0] + ")");
            Kit.log("pe03 on the roof at " + cat.blockPosition().toShortString() + " (" + (roof.getY() - hearth.getY())
                + " above the hearth) after " + (level.getGameTime() - since[0]) + " ticks (" + doing[0] + "); card: " + Pets.cardLine(child));
            helper.assertTrue(doing[0].contains("roof"), "it says so: " + doing[0]);
        });
    }

    // ============================================================ pe04: the bowl, filled from the stores, and a dinner

    /**
     * A pet bowl in the stores is set out in the household's house; a child fills it from the stores (bones, three
     * servings, three bones out of the stores); the hungry dog goes to it and eats a serving.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "pe04_bowl")
    public static void pe04_bowl(GameTestHelper helper) {
        int x = 986000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        Object[][] state = new Object[1][];
        long[] since = { 0 };
        String[] doing = { "" };
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            VillageFolkEntity child = (VillageFolkEntity) fam[1];
            TamableAnimal dog = pet(helper, level, id, home, mother, false);
            Container c = chest(helper, level, mother);
            for (int i = 0; i < c.getContainerSize(); i++) if (PetBowlBlock.dogFood(c.getItem(i))) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(McAssistantMod.PET_BOWL_ITEM.get()), new ItemStack(Items.BONE, 8));
            // The bowl, out of the stores and set out at home.
            helper.assertTrue(Pets.placeForTests(level, mother, McAssistantMod.PET_BOWL_ITEM.get()), "the bowl set out");
            BlockPos bowl = Pets.bowlForTests(level, mother);
            helper.assertTrue(bowl != null && level.getBlockState(bowl).getBlock() instanceof PetBowlBlock, "a bowl in the house: " + bowl);
            helper.assertTrue(bowl.distManhattan(home) < 14, "at home: " + bowl.toShortString() + " (home " + home.toShortString() + ")");
            int bowls = stores(level, id, s -> s.is(McAssistantMod.PET_BOWL_ITEM.get()));
            // Filled from the stores by the child.
            long day = level.getDayTime() / 24000L;
            Pets.hungryForTests(id, dog.getUUID(), day);
            int bones0 = stores(level, id, s -> s.is(Items.BONE));
            helper.assertTrue(Pets.fillForTests(level, child), "the child fills the bowl");
            int servings = PetBowlBlock.servings(level.getBlockState(bowl));
            int bones1 = stores(level, id, s -> s.is(Items.BONE));
            Kit.log("pe04 the bowl set out at " + bowl.toShortString() + " (bowls left in the stores " + bowls + "); the child filled it: "
                + servings + " servings, the stores' bones " + bones0 + " -> " + bones1 + "; said " + Pets.saidForTests());
            helper.assertTrue(bowls == 0, "the bowl came out of the stores");
            helper.assertTrue(servings == 3 && bones0 - bones1 == 3, "three servings, three bones out of the stores: " + servings + ", "
                + bones0 + " -> " + bones1);
            helper.assertTrue(child.countCarried(PetBowlBlock::dogFood) == 0, "nothing left in the child's pack");
            BlockPos hearth = Families.hearthForTests(level, child);
            dog.teleportTo(hearth.getX() + 0.5, hearth.getY(), hearth.getZ() + 0.5);
            since[0] = level.getGameTime();
            state[0] = new Object[]{ child, dog, bowl, day };
        });
        helper.onEachTick(() -> {
            if (state[0] == null || level.getGameTime() % 10 != 0) return;
            String d = Families.walkPetForTests(level, (VillageFolkEntity) state[0][0]);
            if (d != null) doing[0] = d;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] != null, "set up");
            TamableAnimal dog = (TamableAnimal) state[0][1];
            BlockPos bowl = (BlockPos) state[0][2];
            long day = (Long) state[0][3];
            long fed = Pets.fedDayForTests(id, dog.getUUID());
            int servings = PetBowlBlock.servings(level.getBlockState(bowl));
            helper.assertTrue(fed == day && servings == 2, "the dog has eaten a serving: fed day " + fed + " (today " + day + "), " + servings
                + " servings left (" + doing[0] + ", " + String.format("%.1f", Math.sqrt(dog.distanceToSqr(bowl.getX() + 0.5, bowl.getY(), bowl.getZ() + 0.5)))
                + " from the bowl)");
            Kit.log("pe04 the dog ate from its bowl after " + (level.getGameTime() - since[0]) + " ticks; " + servings + " servings left ("
                + doing[0] + ")");
        });
    }

    // ============================================================ pe05: the things, their recipes, ages, worth and makers

    /**
     * The pet bowl, the dog bed, the cat basket, the collar and the treats each have a real recipe, an age and a worth;
     * with a household's dog wanting its things, the tailor makes the dog bed and the collar, the cook the treats and the
     * shop's hand the bowl, each out of the stores (what went in is gone from them); the shop's order book has them on it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pe05_items")
    public static void pe05_items(GameTestHelper helper) {
        int x = 988000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        VillageFolkEntity tailor = another(helper, heart.south(3), id);
        VillageFolkEntity cook = another(helper, heart.south(5), id);
        VillageFolkEntity shopHand = another(helper, heart.south(7), id);
        helper.runAtTickTime(5, () -> {
            Item bowl = McAssistantMod.PET_BOWL_ITEM.get(), dogBed = McAssistantMod.DOG_BED_ITEM.get(), catBed = McAssistantMod.CAT_BED_ITEM.get();
            Item collar = McAssistantMod.COLLAR.get(), treat = McAssistantMod.PET_TREAT.get();
            Item[] things = { bowl, dogBed, catBed, collar, treat };
            Villages.Age[] ages = { Villages.Age.WOOD, Villages.Age.WOOD, Villages.Age.WOOD, Villages.Age.STONE, Villages.Age.WOOD };
            double[] worth = { 0.8, 1.6, 1.2, 1.4, 0.15 };
            for (int i = 0; i < things.length; i++) {
                List<RecipeBook.Way> ways = RecipeBook.waysFor(level, things[i]);
                Villages.Age age = Tiers.of(level, things[i]);
                double each = Prices.each(things[i]);
                List<String> parts = new ArrayList<>();
                for (RecipeBook.Way w : ways) {
                    List<String> ps = new ArrayList<>();
                    for (RecipeBook.Part p : w.parts()) ps.add(p.count() + " " + p.label());
                    parts.add(w.id() + " -> " + w.yield() + ": " + String.join(", ", ps));
                }
                Kit.log("pe05 " + new ItemStack(things[i]).getHoverName().getString() + ": " + ways.size() + " recipe(s) " + parts + "; the "
                    + age.label + "; worth " + each + "c" + (Prices.known(things[i]) ? "" : " (guessed)"));
                helper.assertTrue(!ways.isEmpty(), "a real recipe for " + things[i]);
                helper.assertTrue(age == ages[i], things[i] + " belongs to " + ages[i].label + ", not " + age.label);
                helper.assertTrue(Prices.known(things[i]) && Math.abs(each - worth[i]) < 0.001, things[i] + " is worth " + worth[i] + ": " + each);
            }
            helper.assertTrue(Market.goodFor(new ItemStack(treat)) != null, "the market deals in pet treats");
            // A household with a child and a dog wants a bowl, a bed, a collar and treats.
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            pet(helper, level, id, home, mother, false);
            Map<Item, Integer> wanted = Pets.wantedForTests(level, id);
            Kit.log("pe05 the pets want: " + wanted);
            helper.assertTrue(wanted.getOrDefault(bowl, 0) == 1 && wanted.getOrDefault(dogBed, 0) == 1 && wanted.getOrDefault(collar, 0) == 1
                && wanted.getOrDefault(treat, 0) == 2 && !wanted.containsKey(catBed), "a bowl, a dog bed, a collar and two treats: " + wanted);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.WHITE_WOOL, 8), new ItemStack(Items.LEATHER, 6), new ItemStack(Items.STRING, 8), new ItemStack(Items.WHEAT, 32),
                new ItemStack(Items.BEEF, 8), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BREAD, 32));
            int planks0 = stores(level, id, s -> s.is(Items.OAK_PLANKS)), wool0 = stores(level, id, s -> s.is(Items.WHITE_WOOL)),
                leather0 = stores(level, id, s -> s.is(Items.LEATHER)), string0 = stores(level, id, s -> s.is(Items.STRING)),
                wheat0 = stores(level, id, s -> s.is(Items.WHEAT)), beef0 = stores(level, id, s -> s.is(Items.BEEF));
            tailor.setJob(StationTask.TAILOR);
            cook.setJob(StationTask.COOK);
            shopHand.setJob(StationTask.SHOP);
            String t1 = Pets.craftForTests(level, tailor), t2 = Pets.craftForTests(level, tailor), t3 = Pets.craftForTests(level, tailor);
            String k1 = Pets.craftForTests(level, cook);
            String s1 = Pets.craftForTests(level, shopHand);
            int beds = stores(level, id, s -> s.is(dogBed)), collars = stores(level, id, s -> s.is(collar)), treats = stores(level, id, s -> s.is(treat)),
                bowls = stores(level, id, s -> s.is(bowl));
            int planks1 = stores(level, id, s -> s.is(Items.OAK_PLANKS)), wool1 = stores(level, id, s -> s.is(Items.WHITE_WOOL)),
                leather1 = stores(level, id, s -> s.is(Items.LEATHER)), string1 = stores(level, id, s -> s.is(Items.STRING)),
                wheat1 = stores(level, id, s -> s.is(Items.WHEAT)), beef1 = stores(level, id, s -> s.is(Items.BEEF));
            Kit.log("pe05 the tailor: " + t1 + " / " + t2 + " / " + t3 + "; the cook: " + k1 + "; the shop's hand: " + s1 + "; in the stores: dog beds "
                + beds + ", collars " + collars + ", treats " + treats + ", bowls " + bowls + "; planks " + planks0 + " -> " + planks1 + ", wool "
                + wool0 + " -> " + wool1 + ", leather " + leather0 + " -> " + leather1 + ", string " + string0 + " -> " + string1 + ", wheat "
                + wheat0 + " -> " + wheat1 + ", beef " + beef0 + " -> " + beef1);
            helper.assertTrue(t1 != null && t1.contains("dog bed") && beds == 1, "the tailor made the dog bed: " + t1);
            helper.assertTrue(t2 != null && t2.contains("collar") && collars == 1, "then the collar: " + t2);
            helper.assertTrue(t3 == null, "and nothing more the pets want of it: " + t3);
            helper.assertTrue(k1 != null && k1.contains("treat") && treats >= 2, "the cook made treats: " + k1);
            helper.assertTrue(s1 != null && s1.contains("bowl") && bowls == 1, "the shop's hand made the bowl: " + s1);
            helper.assertTrue(wool1 == wool0 - 1 && leather1 == leather0 - 1 && string1 == string0 - 2, "the bed's wool, the collar's leather and string");
            helper.assertTrue(wheat1 == wheat0 - 2 && beef1 == beef0 - 1, "the treats' wheat and meat");
            helper.assertTrue(planks1 < planks0, "planks for the bed and the bowl");
            // The shop's order book has the pets' things on it, so the shop keeps them.
            List<Item> book = new ArrayList<>();
            for (Stockroom.Ware w : Workshop.lookForTests(level, v)) if (w.make() != null) book.add(w.make());
            Kit.log("pe05 the shop's order book: " + book.size() + " wares; pets' things on it: " + book.stream().filter(i -> i == bowl || i == dogBed
                || i == collar || i == treat).toList());
            helper.assertTrue(book.contains(treat), "the shop's book keeps treats while the household's child wants them: " + book);
            helper.succeed();
        });
    }

    // ============================================================ pe06: a litter, homes for the young, and the cap

    /**
     * Three households: two with dogs, one that wants a pet. One dog has a litter with the other (its parents fed from the
     * stores first); the young go to the household that wants one; and however many litters are tried, the town never
     * keeps more than its cap.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pe06_litter")
    public static void pe06_litter(GameTestHelper helper) {
        int x = 990000;
        VillageFolkEntity a1 = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = a1.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity a2 = another(helper, heart.east(2), id);
        VillageFolkEntity b1 = another(helper, heart.east(4), id), b2 = another(helper, heart.east(6), id);
        VillageFolkEntity c1 = another(helper, heart.west(2), id), c2 = another(helper, heart.west(4), id);
        helper.runAtTickTime(5, () -> {
            Object[] fa = family(helper, level, a1, a2, heart.offset(-18, 0, 18));
            Object[] fb = family(helper, level, b1, b2, heart.offset(18, 0, 18));
            Object[] fc = family(helper, level, c1, c2, heart.offset(-18, 0, -18));
            BlockPos ha = (BlockPos) fa[0], hb = (BlockPos) fb[0], hc = (BlockPos) fc[0];
            TamableAnimal mother = pet(helper, level, id, ha, a1, false);
            TamableAnimal mate = pet(helper, level, id, hb, b1, false);
            Pets.wantForTests(id, hc, true);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.BONE, 16), new ItemStack(Items.BEEF, 8));
            int[] start = Pets.countForTests(id);
            int bones0 = stores(level, id, PetBowlBlock::dogFood);
            List<TamableAnimal> young = Pets.litterForTests(level, id, ha, mate);
            int[] born = Pets.countForTests(id);
            int bones1 = stores(level, id, PetBowlBlock::dogFood);
            Kit.log("pe06 the town's pets " + start[0] + " of " + start[1] + "; " + mother.getName().getString() + " had " + young.size()
                + " young with " + mate.getName().getString() + "; the parents ate " + (bones0 - bones1) + " out of the stores; now "
                + born[0] + " of " + born[1] + "; " + Pets.othersForTests(id));
            helper.assertTrue(!young.isEmpty() && young.size() <= start[1] - start[0], "a litter, within the town's room: " + young.size()
                + " (room " + (start[1] - start[0]) + ")");
            helper.assertTrue(bones0 - bones1 == 2, "the pair fed first, out of the stores: " + (bones0 - bones1));
            for (TamableAnimal y : young) helper.assertTrue(y.isBaby() && y.isTame(), "the young are babies of the household's");
            helper.assertTrue(born[0] <= born[1], "within the cap: " + born[0] + " of " + born[1]);
            int placed = Pets.homesForTests(level, id);
            UUID cPet = Families.petForTests(id, hc);
            boolean ours = false;
            for (TamableAnimal y : young) if (y.getUUID().equals(cPet)) ours = true;
            TamableAnimal got = null;
            for (TamableAnimal y : young) if (y.getUUID().equals(cPet)) got = y;
            Kit.log("pe06 " + placed + " of the young found homes; the household that wanted one has " + (got == null ? "none"
                : got.getName().getString()) + "; card: " + Pets.cardLine(c1) + "; " + Pets.othersForTests(id));
            helper.assertTrue(placed >= 1 && ours, "one of the young went to the household that wanted a pet");
            helper.assertTrue(got.hasCustomName() && remembers(c1, "we took in " + got.getName().getString()), "named, and taken in");
            // Litter after litter: the town never keeps more than its cap.
            int most = 0;
            List<Integer> sizes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                List<TamableAnimal> more = Pets.litterForTests(level, id, i % 2 == 0 ? hb : ha, i % 2 == 0 ? mother : mate);
                sizes.add(more.size());
                int[] n = Pets.countForTests(id);
                most = Math.max(most, n[0]);
                helper.assertTrue(n[0] <= n[1], "never past the cap: " + n[0] + " of " + n[1]);
            }
            int[] end = Pets.countForTests(id);
            Kit.log("pe06 four more litters tried: " + sizes + "; the most the town had " + most + "; now " + end[0] + " of " + end[1]);
            helper.assertTrue(sizes.get(sizes.size() - 1) == 0 && end[0] == end[1], "at the cap, no more litters: " + sizes);
            helper.succeed();
        });
    }

    // ============================================================ pe07: a player's stray, a friend, and a pup bought

    /**
     * A player with treats: a stray cat about the town takes one and is the player's; the household's dog eats two out of
     * the player's hand and follows; a pup from the dog's litter is the player's for three coins, paid to the family.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pe07_player")
    public static void pe07_player(GameTestHelper helper) {
        int x = 992000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            TamableAnimal dog = pet(helper, level, id, home, mother, false);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(McAssistantMod.PET_TREAT.get(), 3));
            // A stray cat, taken home with a treat.
            TamableAnimal stray = Pets.strayForTests(level, id, true, Kit.surface(level, x + 10, Z + 10));
            helper.assertTrue(stray instanceof Cat, "a stray cat about the town");
            String took = Pets.interactForTests(level, p, stray);
            Kit.log("pe07 the stray: " + took + "; tame " + stray.isTame() + ", owner the player " + p.getUUID().equals(stray.getOwnerUUID())
                + ", named " + stray.getName().getString());
            helper.assertTrue(took != null && stray.isTame() && p.getUUID().equals(stray.getOwnerUUID()) && stray.hasCustomName(),
                "the stray is the player's, and named: " + took);
            helper.assertTrue(p.getMainHandItem().getCount() == 2, "a treat given: " + p.getMainHandItem().getCount());
            for (String o : Pets.othersForTests(id)) helper.assertFalse(o.endsWith(stray.getUUID().toString()), "no longer the town's stray");
            // The household's dog befriended with treats.
            String first = Pets.interactForTests(level, p, dog), second = Pets.interactForTests(level, p, dog);
            Kit.log("pe07 the dog: " + first + " / " + second + "; following " + Pets.followingForTests(dog.getUUID()) + "; card "
                + Pets.cardLine(mother) + "; the mother's feeling for the player " + mother.persona().affinity(p.getUUID()));
            helper.assertTrue(first != null && p.getUUID().equals(Pets.followingForTests(dog.getUUID())), "it eats from the hand and follows");
            helper.assertTrue(second != null && second.contains("shine"), "two treats, and it is the player's friend: " + second);
            helper.assertTrue(dog.getOwnerUUID() != null && !dog.getOwnerUUID().equals(p.getUUID()), "and still the household's");
            // A pup from the dog's litter, bought for three coins paid to the family.
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(Items.BONE, 8));
            TamableAnimal mate = Pets.strayForTests(level, id, false, Kit.surface(level, home.getX() + 6, home.getZ() - 6));
            List<TamableAnimal> young = Pets.litterForTests(level, id, home, mate);
            helper.assertTrue(!young.isEmpty(), "a pup born: " + Pets.othersForTests(id) + ", " + java.util.Arrays.toString(Pets.countForTests(id)));
            TamableAnimal pup = young.get(0);
            int purse0 = mother.purse() + father.purse();
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            String ask = Pets.interactForTests(level, p, pup);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 5));
            String bought = Pets.interactForTests(level, p, pup);
            int coins = Market.coinsHeld(p), purse1 = mother.purse() + father.purse();
            Kit.log("pe07 the pup: \"" + ask + "\"; with coins: \"" + bought + "\"; the player's coins 5 -> " + coins + ", the family's purses " + purse0
                + " -> " + purse1 + "; owner the player " + p.getUUID().equals(pup.getOwnerUUID()));
            helper.assertTrue(ask != null && ask.contains("3 coins"), "the price, asked: " + ask);
            helper.assertTrue(coins == 2 && purse1 - purse0 == 3, "three coins from the player to the family: " + coins + ", " + (purse1 - purse0));
            helper.assertTrue(pup.isTame() && p.getUUID().equals(pup.getOwnerUUID()) && pup.hasCustomName(), "the pup is the player's, named");
            helper.succeed();
        });
    }

    // ============================================================ pe08: a bark in the night

    /**
     * At night a zombie comes near the house: the household's dog barks at it (and does not go for it), a guard of the
     * watch goes after it; next morning the household talks of it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pe08_bark")
    public static void pe08_bark(GameTestHelper helper) {
        int x = 994000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        VillageFolkEntity guard = another(helper, heart.west(3), id);
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            TamableAnimal dog = pet(helper, level, id, home, mother, false);
            guard.setJob(StationTask.GUARD);
            level.setDayTime(DAY + 18000);
            BlockPos hearth = Families.hearthForTests(level, mother);
            dog.teleportTo(hearth.getX() + 0.5, hearth.getY(), hearth.getZ() + 0.5);
            Zombie z = EntityType.ZOMBIE.create(level);
            BlockPos zp = Kit.surface(level, home.getX() + 7, home.getZ() + 9);
            z.moveTo(zp.getX() + 0.5, zp.getY(), zp.getZ() + 0.5, 0.0F, 0.0F);
            z.setPersistenceRequired();
            level.addFreshEntity(z);
            String doing = Pets.barkForTests(level, id, dog);
            boolean hunted = guard.getTarget() == z || WatchClears.hunting(guard);
            Kit.log("pe08 the dog at " + dog.blockPosition().toShortString() + ", the zombie " + String.format("%.1f", dog.distanceTo(z))
                + " off: " + doing + "; the dog's target " + dog.getTarget() + "; the guard " + guard.displayNameCap() + " after it: " + hunted);
            helper.assertTrue(doing != null && doing.contains("zombie"), "the dog barks at the zombie: " + doing);
            helper.assertTrue(dog.getTarget() == null, "a small help, not a fighter: it does not go for it");
            helper.assertTrue(hunted, "the bark brings out the watch: " + guard.getTarget());
            level.setDayTime(DAY + 24000 + 2000);
            String morning = Pets.morningForTests(level, mother);
            Kit.log("pe08 the next morning, " + mother.displayNameCap() + ": " + morning + "; card: " + Pets.cardLine(mother));
            helper.assertTrue(morning != null && morning.contains("zombie") && morning.contains(dog.getName().getString()), "talked of: " + morning);
            helper.assertTrue(Pets.cardLine(mother).contains("barked off 1 monster"), "the card counts it: " + Pets.cardLine(mother));
            z.discard();
            helper.succeed();
        });
    }

    // ============================================================ pe09: lost, and found by a player (the quest board's seam)

    /**
     * The quest board's seam: a dog goes missing (Pets.lost), out past the town's edge, and stays there; a player finds it
     * and clicks it, it follows the player home, the family thanks the player, and whoever listens (the quest board) hears
     * of it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1000, batch = "pe09_lost")
    public static void pe09_lost(GameTestHelper helper) {
        int x = 996000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 112);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        Object[][] state = new Object[1][];
        List<String> heard = new ArrayList<>();
        helper.runAtTickTime(5, () -> {
            Object[] fam = family(helper, level, mother, father, heart.offset(-16, 0, 16));
            BlockPos home = (BlockPos) fam[0];
            TamableAnimal dog = pet(helper, level, id, home, mother, false);
            Pets.onFound((lost, player) -> heard.add(lost.name() + "|" + lost.pet() + "|" + player.getName().getString()));
            Pets.Lost lost = Pets.lost(level, id);
            helper.assertTrue(lost != null && lost.pet().equals(dog.getUUID()) && Pets.isLost(dog.getUUID()), "the dog goes missing: " + lost);
            int out = Math.max(Math.abs(lost.at().getX() - heart.getX()), Math.abs(lost.at().getZ() - heart.getZ()));
            helper.assertTrue(dog.blockPosition().distManhattan(lost.at()) <= 3 && out > Villages.townReach(id),
                "out past the town's edge: " + lost.at().toShortString() + ", " + out + " from the heart (the town reaches " + Villages.townReach(id) + ")");
            Kit.log("pe09 lost: " + lost + "; card: " + Pets.cardLine(mother));
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.teleportTo(level, dog.getX() + 2, dog.getY(), dog.getZ(), Set.of(), 0.0F, 0.0F);
            String found = Pets.interactForTests(level, p, dog);
            helper.assertTrue(found != null && found.contains("follow"), "found: " + found);
            state[0] = new Object[]{ dog, p, found };
        });
        // The player walks home, six blocks every second, and the dog keeps up.
        helper.onEachTick(() -> {
            if (state[0] == null) return;
            ServerPlayer p = (ServerPlayer) state[0][1];
            if (level.getGameTime() % 20 == 0) {
                BlockPos hearth = Families.hearthForTests(level, mother);
                double dx = hearth.getX() + 2.5 - p.getX(), dz = hearth.getZ() + 0.5 - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
                if (d > 1.0) {
                    double step = Math.min(6.0, d);
                    int nx = (int) Math.floor(p.getX() + dx / d * step), nz = (int) Math.floor(p.getZ() + dz / d * step);
                    BlockPos g = Kit.surface(level, nx, nz);
                    p.teleportTo(level, nx + 0.5, g.getY(), nz + 0.5, Set.of(), 0.0F, 0.0F);
                }
            }
            if (level.getGameTime() % 10 == 0) Families.walkPetForTests(level, mother);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] != null, "set up");
            TamableAnimal dog = (TamableAnimal) state[0][0];
            ServerPlayer p = (ServerPlayer) state[0][1];
            helper.assertFalse(Pets.isLost(dog.getUUID()), "brought home: the dog at " + dog.blockPosition().toShortString());
            Kit.log("pe09 \"" + state[0][2] + "\"; home at " + dog.blockPosition().toShortString() + "; the quest board heard " + heard
                + "; the mother's feeling for the player " + mother.persona().affinity(p.getUUID()) + "; said " + Pets.saidForTests());
            helper.assertTrue(heard.size() == 1 && heard.get(0).contains(dog.getUUID().toString()) && heard.get(0).endsWith(p.getName().getString()),
                "the quest board hears who brought it home: " + heard);
            helper.assertTrue(mother.persona().affinity(p.getUUID()) > 0, "the family thinks the better of the player");
        });
    }

    // ============================================================ pe10: the pet show

    /**
     * Two households' dogs at the fair: one fed today, in a new collar and with a bed of its own; the other not fed. The
     * best-kept wins, and its household is given the ribbon (a sheet of the stores' paper, lettered).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pe10_show")
    public static void pe10_show(GameTestHelper helper) {
        int x = 998000;
        VillageFolkEntity a1 = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = a1.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity a2 = another(helper, heart.east(2), id);
        VillageFolkEntity b1 = another(helper, heart.east(4), id), b2 = another(helper, heart.east(6), id);
        helper.runAtTickTime(5, () -> {
            Object[] fa = family(helper, level, a1, a2, heart.offset(-18, 0, 18));
            Object[] fb = family(helper, level, b1, b2, heart.offset(18, 0, 18));
            BlockPos ha = (BlockPos) fa[0], hb = (BlockPos) fb[0];
            TamableAnimal good = pet(helper, level, id, ha, a1, false);
            TamableAnimal hungry = pet(helper, level, id, hb, b1, false);
            emptyStores(helper, level, id);
            stock(level, id, new ItemStack(McAssistantMod.COLLAR.get()), new ItemStack(McAssistantMod.DOG_BED_ITEM.get()), new ItemStack(Items.PAPER, 3));
            helper.assertTrue(Pets.placeForTests(level, a1, McAssistantMod.COLLAR.get()), "a collar put on it");
            helper.assertTrue(Pets.placeForTests(level, a1, McAssistantMod.DOG_BED_ITEM.get()), "a bed set out for it");
            BlockPos bed = Pets.bedForTests(level, a1, false);
            helper.assertTrue(bed != null && level.getBlockState(bed).getBlock() instanceof PetBedBlock, "the dog bed in the house: " + bed);
            long day = level.getDayTime() / 24000L;
            Pets.hungryForTests(id, hungry.getUUID(), day);
            int paper0 = stores(level, id, s -> s.is(Items.PAPER));
            List<String> lines = Pets.showForTests(level, id);
            int paper1 = stores(level, id, s -> s.is(Items.PAPER));
            ItemStack ribbon = ItemStack.EMPTY;
            for (VillageFolkEntity m : List.of(a1, a2)) {
                for (ItemStack s : m.getInventoryItems()) {
                    if (s.is(Items.PAPER) && s.has(DataComponents.CUSTOM_NAME)) ribbon = s;
                }
            }
            Kit.log("pe10 the show: " + lines + "; the winner's collar " + Pets.careForTests(id, good.getUUID()) + "; the stores' paper " + paper0
                + " -> " + paper1 + "; ribbon " + (ribbon.isEmpty() ? "none" : ribbon.getHoverName().getString()) + "; card: " + Pets.cardLine(a1));
            String name = good.getName().getString();
            helper.assertTrue(lines.size() == 3 && lines.get(0).contains("pet show"), "the show: " + lines);
            helper.assertTrue(lines.get(2).contains(name) && lines.get(2).contains("collar"), "the best-kept pet wins, for its keeping: " + lines.get(2));
            helper.assertTrue(paper1 == paper0 - 1 && !ribbon.isEmpty() && ribbon.getHoverName().getString().contains("best-kept pet"),
                "the ribbon, of the stores' paper, to its household");
            helper.assertTrue(Pets.cardLine(a1).contains("a ribbon from the fair"), "the card says so: " + Pets.cardLine(a1));
            helper.succeed();
        });
    }
}
