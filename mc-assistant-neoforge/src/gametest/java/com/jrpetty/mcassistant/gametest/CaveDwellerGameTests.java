package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CaveDwellers;
import com.jrpetty.mcassistant.entity.CaveTrips;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.SearchParties;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchKit;
import com.jrpetty.mcassistant.entity.Workshop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [caves] The cave team (CaveDwellers, CaveTrips, CaveCraft). Each test on its own ground (x 920000 to 937000, z 66000),
 * in a batch of its own; the caves are cut into a block of stone set on the flat world, its tunnel level with the
 * ground, sixty-four blocks east of the heart (out of the town's reach: nothing is mined or opened in a town).
 *
 * <ul>
 * <li>cd01: an Iron Age town of twenty-eight wants a team of two, and takes its two best-levelled miner and guard (not
 *     its idle hand of no skill, never a farmer it is short of), the miner leading; a Stone Age town takes nobody.</li>
 * <li>cd02: the kit (the watch's iron, the best pick, a stack of torches out of what the town can spare, never its own
 *     torches, the crafting makings), its hand-back, and a cave dweller as hardy as a guard.</li>
 * <li>cd03: one cave dweller's day: every ore its iron pick allows, the obsidian left, the zombie killed, the spawner noted
 *     and lit up, the old chest looted (never the player's), and the haul into the storehouse.</li>
 * <li>cd04: with a diamond pick the obsidian comes out too.</li>
 * <li>cd05: a team of two on a cave nobody has mapped: a day's plan; together the whole way; the vein hidden two blocks
 *     behind the wall found and mined; the cave's list ends with every vein mined but the obsidian, waiting; home the
 *     same day, the haul in the storehouse.</li>
 * <li>cd06: the plans: an unmapped cave a day, a big listed cave days, the same with little food fewer, a small near
 *     one half a day.</li>
 * <li>cd07: a short trip to a small listed cave: half a day, the town's wanted ore first (though another is nearer),
 *     home the same day.</li>
 * <li>cd08: a two-day trip camps at dusk under the rock, walled in, and breaks camp at first light.</li>
 * <li>cd09: out of torches (and no coal or wood to make more), the team turns back, and says why.</li>
 * <li>cd10: a team a day overdue: the town sends a search party.</li>
 * <li>cd11: a dweller with coal and planks and no torches makes torches in the cave and lights it.</li>
 * <li>cd12: a dweller whose pick is about to break makes another at its crafting table there, and mines on.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CaveDwellerGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** How far east of the heart the cave's tunnel opens. */
    private static final int OUT = 64;

    /** A village of these trades (the founder first), its stores a storehouse and nothing else, in this age. */
    private record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, Villages.Age age, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        CaveDwellers.resetForTests();
        WatchKit.resetForTests();
        Workshop.resetForTests();
        SearchParties.resetForTests();
        level.setDayTime(2000);
        Kit.hold(level, x + 44, Z, 76);
        Kit.prepare(level, x + 44, Z, 76);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        Villages.ageForTests(village, age);
        for (int i = 0; i < trades.length; i++) folk.get(i).setJob(trades[i]);
        return new Town(village, Villages.get(village), heart, store, folk);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(origin.offset(x, y, z), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands: " + level.getBlockState(door));
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** No chest about but the storehouse (the founders' chest goes). */
    private static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /** The storehouse filled with these, in its first slots, and the village's counts made afresh. */
    private static void fill(Town t, ItemStack... goods) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.length; i++) t.store().setItem(i, goods[i]);
        Villages.forgetStock();
        Villages.forgetStores(t.village());
        com.jrpetty.mcassistant.entity.Budget.forget(t.village());
    }

    /** A cave team's kit for so many, and these besides: the watch's iron, swords, shields, iron picks, a stack of
     *  bread each, torches (four stacks), cobblestone. */
    private static ItemStack[] kit(int n, boolean picks, int torchStacks, ItemStack... more) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new ItemStack(Items.IRON_HELMET));
            out.add(new ItemStack(Items.IRON_CHESTPLATE));
            out.add(new ItemStack(Items.IRON_LEGGINGS));
            out.add(new ItemStack(Items.IRON_BOOTS));
            out.add(new ItemStack(Items.IRON_SWORD));
            out.add(new ItemStack(Items.SHIELD));
            if (picks) out.add(new ItemStack(Items.IRON_PICKAXE));
            out.add(new ItemStack(Items.BREAD, 32));
        }
        for (int i = 0; i < torchStacks; i++) out.add(new ItemStack(Items.TORCH, 64));
        out.add(new ItemStack(Items.COBBLESTONE, 64));
        out.add(new ItemStack(Items.COBBLESTONE, 64));
        out.addAll(List.of(more));
        return out.toArray(new ItemStack[0]);
    }

    private static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    /** What the storehouse block itself holds of this (not the town's other stores). */
    private static int inStorehouse(Town t, Item it) {
        int n = 0;
        for (int i = 0; i < t.store().getContainerSize(); i++) if (t.store().getItem(i).is(it)) n += t.store().getItem(i).getCount();
        return n;
    }

    /** Carried at all: in either hand, worn, or in the pack. */
    private static int has(VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) if (what.test(f.getItemBySlot(slot))) n += f.getItemBySlot(slot).getCount();
        for (ItemStack s : f.getInventoryItems()) if (what.test(s)) n += s.getCount();
        return n;
    }

    /** The test's cave, and where its things are. */
    private record Cave(BlockPos mouth, BlockPos chamber, BlockPos lootChest, BlockPos playerChest, BlockPos spawner,
                        List<BlockPos> iron, List<BlockPos> coal, BlockPos diamond, List<BlockPos> obsidian, int x0, int z0, int base) {}

    /**
     * A cave in a block of stone on the flat world: the block twenty-five along, seventeen across and thirteen high; a
     * tunnel two wide and three high in from its west face, level with the ground; a chamber thirteen by eleven and four
     * high. Iron in the east wall (four showing, one behind them), coal and copper in the south wall, a diamond and two
     * of obsidian in the north. With the extras: an old chest by the north wall with the world's dungeon loot still to be
     * rolled (and two diamonds and some rotten flesh in it already), a player's chest beside it with three diamonds and
     * an iron ingot, a zombie spawner by mossy stones in the south wall.
     */
    private static Cave cave(ServerLevel level, int x0, int z0, boolean extras) {
        int base = Kit.surface(level, x0 + 12, z0).getY();
        for (int x = x0; x <= x0 + 24; x++) {
            for (int z = z0 - 8; z <= z0 + 8; z++) {
                for (int y = base; y <= base + 12; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int x = x0; x <= x0 + 6; x++) {
            for (int z = z0 - 1; z <= z0; z++) {
                for (int y = base; y <= base + 2; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int x = x0 + 7; x <= x0 + 19; x++) {
            for (int z = z0 - 5; z <= z0 + 5; z++) {
                for (int y = base; y <= base + 3; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        int e = x0 + 20;
        List<BlockPos> iron = List.of(new BlockPos(e, base + 1, z0 - 1), new BlockPos(e, base + 1, z0), new BlockPos(e, base + 2, z0),
            new BlockPos(e, base + 2, z0 + 1), new BlockPos(e + 1, base + 1, z0));
        for (BlockPos p : iron) level.setBlock(p, Blocks.IRON_ORE.defaultBlockState(), 2);
        List<BlockPos> coal = List.of(new BlockPos(x0 + 9, base + 1, z0 + 6), new BlockPos(x0 + 10, base + 1, z0 + 6), new BlockPos(x0 + 11, base + 1, z0 + 6));
        for (BlockPos p : coal) level.setBlock(p, Blocks.COAL_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 14, base + 2, z0 + 6), Blocks.COPPER_ORE.defaultBlockState(), 2);
        BlockPos diamond = new BlockPos(x0 + 16, base + 1, z0 - 6);
        level.setBlock(diamond, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        List<BlockPos> obsidian = List.of(new BlockPos(x0 + 12, base + 1, z0 - 6), new BlockPos(x0 + 13, base + 1, z0 - 6));
        for (BlockPos p : obsidian) level.setBlock(p, Blocks.OBSIDIAN.defaultBlockState(), 2);
        BlockPos loot = new BlockPos(x0 + 9, base, z0 - 5), mine = new BlockPos(x0 + 11, base, z0 - 5), spawner = new BlockPos(x0 + 17, base, z0 + 5);
        if (extras) {
            level.setBlock(loot, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 3);
            if (level.getBlockEntity(loot) instanceof ChestBlockEntity c) {
                c.setItem(0, new ItemStack(Items.DIAMOND, 2));
                c.setItem(1, new ItemStack(Items.ROTTEN_FLESH, 5));
                c.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 4242L);
            }
            level.setBlock(mine, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 3);
            if (level.getBlockEntity(mine) instanceof ChestBlockEntity c) {
                c.setItem(0, new ItemStack(Items.DIAMOND, 3));
                c.setItem(1, new ItemStack(Items.IRON_INGOT, 1));
            }
            level.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), 3);
            if (level.getBlockEntity(spawner) instanceof SpawnerBlockEntity sp) sp.setEntityId(EntityType.ZOMBIE, level.getRandom());
            level.setBlock(new BlockPos(x0 + 17, base, z0 + 6), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x0 + 18, base + 1, z0 + 6), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
        }
        BlockPos mouth = new BlockPos(x0 - 2, base, z0);
        BlockPos chamber = new BlockPos(x0 + 13, base, z0);
        return new Cave(mouth, chamber, loot, mine, spawner, iron, coal, diamond, obsidian, x0, z0, base);
    }

    private static int torchesIn(ServerLevel level, Cave c) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(c.x0(), c.base(), c.z0() - 6), new BlockPos(c.x0() + 20, c.base() + 4, c.z0() + 6))) {
            if (level.getBlockState(p).is(Blocks.TORCH) || level.getBlockState(p).is(Blocks.WALL_TORCH)) n++;
        }
        return n;
    }

    private static int ironLeft(ServerLevel level, Cave c) {
        int n = 0;
        for (BlockPos p : c.iron()) if (level.getBlockState(p).is(Blocks.IRON_ORE)) n++;
        return n;
    }

    /** Every one of the team stood at the cave's mouth. */
    private static void toTheMouth(Town t, Cave c, VillageFolkEntity... team) {
        for (int i = 0; i < team.length; i++) {
            team[i].moveTo(c.mouth().getX() + 0.5 - i, c.mouth().getY(), c.mouth().getZ() + 0.5, -90.0F, 0.0F);
        }
    }

    /** Is anybody of the team still out? */
    private static boolean out(VillageFolkEntity... team) {
        for (VillageFolkEntity f : team) if (f.expedition() != null) return true;
        return false;
    }

    /** The time the test may skip on: a folk's break taken (VillageFolkEntity.breakNowForTests). */
    private static void pace(ServerLevel level, VillageFolkEntity... team) {
        for (VillageFolkEntity f : team) if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
    }

    // ============================================================ cd01: the team, and who is picked

    /**
     * A town of twenty-eight on the roll: six miners (one at level twelve), three guards (one at level ten), a farmer and
     * an idle hand of no skill. In the Stone Age it wants no cave dweller and takes nobody. In the Iron Age it wants a
     * team of two, and takes the miner at twelve and the guard at ten (in that order, the most skilled first) with a head
     * start at the caves, not the idle hand and not its farmer; a third look takes nobody; the miner leads.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cd01_team")
    public static void cd01_team(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 920000, Villages.Age.STONE, StationTask.MINE, StationTask.MINE, StationTask.MINE, StationTask.MINE,
            StationTask.MINE, StationTask.MINE, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        for (int i = 0; i < 17; i++) Villages.recordBirth(id);
        int heads = Villages.headcount(id);
        VillageFolkEntity ace = null, guard = null, farmer = t.folk().get(9), idle = t.folk().get(10);
        for (int i = 1; i < 6 && ace == null; i++) if (!t.folk().get(i).isElder()) ace = t.folk().get(i);       // never the elder
        for (int i = 6; i < 9 && guard == null; i++) if (!t.folk().get(i).isElder()) guard = t.folk().get(i);
        helper.assertTrue(ace != null && guard != null && !idle.isElder() && !farmer.isElder(), "a miner and a guard who are not the elder");
        for (int i = 0; i < t.folk().size(); i++) {
            VillageFolkEntity f = t.folk().get(i);
            f.setAgeForTests(30);
            StationTask job = f.stationTask();
            if (job != StationTask.NONE) f.tradeXpForTests(job, AssistantEntity.xpForLevel(f == ace ? 12 : f == guard ? 10 : 2 + i % 2));
        }
        long day = level.getDayTime() / 24000L;
        int stoneWanted = CaveDwellers.wanted(id);
        VillageFolkEntity stoneTook = CaveDwellers.appoint(level, t.v(), day);
        Kit.log("cd01 Stone Age, " + heads + " on the roll: wanted " + stoneWanted + ", took " + (stoneTook == null ? "nobody" : stoneTook.displayNameCap()));
        helper.assertTrue(heads >= 25, "a town of twenty-five or more on the roll: " + heads);
        helper.assertTrue(stoneWanted == 0 && stoneTook == null, "no cave team in the Stone Age");
        Villages.ageForTests(id, Villages.Age.IRON);
        int ironWanted = CaveDwellers.wanted(id);
        StringBuilder shares = new StringBuilder();
        for (StationTask s : new StationTask[]{ StationTask.MINE, StationTask.GUARD, StationTask.FARM }) {
            shares.append(' ').append(s.name().toLowerCase()).append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", Villages.share(id, s)));
        }
        VillageFolkEntity first = CaveDwellers.appoint(level, t.v(), day);
        VillageFolkEntity second = CaveDwellers.appoint(level, t.v(), day);
        VillageFolkEntity third = CaveDwellers.appoint(level, t.v(), day);
        VillageFolkEntity leader = CaveDwellers.leaderOf(id);
        Kit.log("cd01 Iron Age: wanted " + ironWanted + "; shares" + shares + "; took " + (first == null ? "nobody" : first.displayNameCap())
            + ", then " + (second == null ? "nobody" : second.displayNameCap()) + ", then " + (third == null ? "nobody" : third.displayNameCap())
            + "; caves levels " + ace.tradeLevel(StationTask.CAVE) + " and " + guard.tradeLevel(StationTask.CAVE) + "; leader "
            + (leader == null ? "none" : leader.displayNameCap()) + "; the idle hand " + idle.stationTask() + ", the farmer " + farmer.stationTask());
        helper.assertTrue(ironWanted == 2, "an Iron Age town of " + heads + " wants a team of two: " + ironWanted);
        helper.assertTrue(CaveDwellers.team(24) == 0 && CaveDwellers.team(25) == 2 && CaveDwellers.team(60) == 3 && CaveDwellers.team(100) == 4
            && CaveDwellers.team(400) == 4, "two from twenty-five, three at sixty, four at a hundred and never more");
        helper.assertTrue(first == ace && second == guard, "the two best-levelled hands, the miner at twelve then the guard at ten");
        helper.assertTrue(third == null && CaveDwellers.dwellers(id).size() == 2, "a team of two, and no more");
        helper.assertTrue(idle.stationTask() == StationTask.NONE, "not the idle hand of no skill");
        helper.assertTrue(farmer.stationTask() == StationTask.FARM, "never the farmer the town is short of");
        helper.assertTrue(ace.tradeLevel(StationTask.CAVE) >= 8 && guard.tradeLevel(StationTask.CAVE) >= 6,
            "a head start at the caves for what they know of the rock and the blade");
        helper.assertTrue(leader == ace, "the most experienced leads");
        helper.succeed();
    }

    // ============================================================ cd02: the kit, and as hardy as the watch

    /**
     * An Iron Age town's cave dweller goes to the stores: it comes away in the watch's iron with an iron sword, a
     * shield, the iron pick, a stack of torches (the stores never left below the town's own), bread, a crafting table, a
     * few planks and sticks, its purse untouched. Taking up the fields, it hands the town's kit back into the stores. A
     * cave dweller has a guard's health: twice a farmer's.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cd02_kit")
    public static void cd02_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 922000, Villages.Age.IRON, StationTask.MINE, StationTask.MINE, StationTask.GUARD, StationTask.CAVE, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(3);
        fill(t, new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS),
            new ItemStack(Items.IRON_BOOTS), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_PICKAXE),
            new ItemStack(Items.TORCH, 64), new ItemStack(Items.TORCH, 64), new ItemStack(Items.TORCH, 32), new ItemStack(Items.BREAD, 32),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.STICK, 8), new ItemStack(Items.IRON_INGOT, 32));
        int purse0 = d.purse();
        int torches0 = stock(level, id, Items.TORCH);
        List<String> got = CaveDwellers.kitUpForTests(level, t.v(), d);
        int torches = has(d, s -> s.is(Items.TORCH)), food = has(d, s -> s.is(Items.BREAD)), left = stock(level, id, Items.TORCH);
        Kit.log("cd02 " + d.displayNameCap() + " fitted out: " + got + "; swords " + has(d, s -> s.is(Items.IRON_SWORD)) + ", picks "
            + has(d, s -> s.is(Items.IRON_PICKAXE)) + ", shields " + has(d, s -> s.is(Items.SHIELD)) + ", torches " + torches + ", bread " + food
            + ", table " + has(d, s -> s.is(Items.CRAFTING_TABLE)) + ", planks " + has(d, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) + ", sticks "
            + has(d, s -> s.is(Items.STICK)) + ", ingots " + has(d, s -> s.is(Items.IRON_INGOT)) + "; stores' torches " + torches0 + " -> " + left
            + "; purse " + purse0 + " -> " + d.purse());
        helper.assertTrue(d.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && d.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
            && d.getItemBySlot(EquipmentSlot.LEGS).is(Items.IRON_LEGGINGS) && d.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS), "in the town's iron armour");
        helper.assertTrue(has(d, s -> s.is(Items.IRON_SWORD)) >= 1 && has(d, s -> s.is(Items.SHIELD)) >= 1, "with an iron sword and a shield");
        helper.assertTrue(has(d, s -> s.is(Items.IRON_PICKAXE)) >= 1 && stock(level, id, Items.IRON_PICKAXE) == 0, "the stores' iron pick");
        helper.assertTrue(torches == CaveDwellers.TORCHES, "a stack of torches, the town's own make: " + torches);
        helper.assertTrue(left >= 32 + 2 * Villages.headcount(id) - 1, "never the town's own torches: " + left + " left in the stores");
        helper.assertTrue(food >= 1, "food for the day: " + food);
        helper.assertTrue(has(d, s -> s.is(Items.CRAFTING_TABLE)) == 1 && has(d, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 1
            && has(d, s -> s.is(Items.STICK)) >= 1, "a crafting table, planks and sticks, to make what it wants down there");
        helper.assertTrue(d.purse() == purse0, "free: the purse untouched");
        // As hardy as the watch.
        VillageFolkEntity h = t.folk().get(4);
        h.setJob(StationTask.FARM);
        float farm = h.getMaxHealth();
        h.setJob(StationTask.GUARD);
        float watch = h.getMaxHealth();
        h.setJob(StationTask.CAVE);
        float caves = h.getMaxHealth();
        h.setJob(StationTask.FARM);
        float back = h.getMaxHealth();
        Kit.log("cd02 max health: a farmer " + farm + ", a guard " + watch + ", a cave dweller " + caves + ", a farmer again " + back);
        helper.assertTrue(Math.abs(caves - watch) < 0.01F, "a cave dweller has a guard's health: " + caves + " / " + watch);
        helper.assertTrue(Math.abs(caves - 2 * farm) < 0.01F && Math.abs(back - farm) < 0.01F, "twice a farmer's, and off the trade as before");
        // The kit back.
        d.setJob(StationTask.FARM);
        Kit.log("cd02 a farmer now: the stores hold " + stock(level, id, Items.IRON_CHESTPLATE) + " iron chestplate, " + stock(level, id, Items.IRON_PICKAXE)
            + " iron pick, " + stock(level, id, Items.IRON_SWORD) + " iron sword; it wears " + d.getItemBySlot(EquipmentSlot.CHEST).getItem());
        helper.assertTrue(stock(level, id, Items.IRON_CHESTPLATE) == 1 && stock(level, id, Items.IRON_PICKAXE) == 1 && stock(level, id, Items.IRON_SWORD) == 1,
            "the town's kit back in the stores when it leaves the caves");
        helper.assertTrue(d.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "and off its back");
        helper.succeed();
    }

    // ============================================================ cd03: a day in the caves

    /**
     * A cave dweller with the town's iron kit, sent into the test's cave: it walks in, lights it, mines the iron (all
     * five, the one behind the others too), the coal, the copper and the diamond, leaves the obsidian (its iron pick
     * won't take it), kills the zombie, notes the dungeon's spawner and lights it up (the spawner stays), opens the old
     * chest and takes its diamonds and whatever else of worth the world's loot put in it (not the rotten flesh), never
     * touches the player's chest beside it, and comes home: the ore and the chest's diamonds are in the storehouse, and
     * the report has the cave, the veins, the dungeon and the chest.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "cd03_delve")
    public static void cd03_delve(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 924000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(0);
        Cave c = cave(level, x + OUT, Z, true);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "a zombie");
        zombie.moveTo(c.x0() + 15.5, c.base(), c.z0() + 2.5, 0.0F, 0.0F);
        zombie.setPersistenceRequired();
        level.addFreshEntity(zombie);
        fill(t, kit(1, true, 2));
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, d);
        int raw0 = inStorehouse(t, Items.RAW_IRON), coal0 = inStorehouse(t, Items.COAL), diamonds0 = inStorehouse(t, Items.DIAMOND);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(d);
        Kit.log("cd03 " + d.displayNameCap() + " sent " + sent + " into the cave at " + c.chamber().toShortString() + "; kit: "
            + d.getItemBySlot(EquipmentSlot.CHEST).getItem() + ", picks " + has(d, CaveDwellers::isPickaxe) + ", torches " + has(d, s -> s.is(Items.TORCH))
            + "; plan: " + (p == null || p.plan() == null ? "none" : p.plan().words()));
        helper.assertTrue(sent && p != null && d.expedition() != null && d.expedition().delve() != null, "out for the day, into the caves");
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (helper.getTick() % 100 == 0) {
                    Kit.log("cd03 tick " + helper.getTick() + ": " + p.phase() + ", mined " + p.mined() + ", chests " + p.opened() + ", slain " + p.slain()
                        + ", torches " + p.torches() + ", spawners lit " + p.spawnersLit() + " at " + d.blockPosition().toShortString() + "; " + d.hobbyNow()
                        + "; zombie " + (zombie.isAlive() ? "alive" : "dead"));
                }
                pace(level, d);
                return;
            }
            done[0] = true;
            int raw = inStorehouse(t, Items.RAW_IRON) - raw0, coal = inStorehouse(t, Items.COAL) - coal0, diamonds = inStorehouse(t, Items.DIAMOND) - diamonds0;
            int copper = stock(level, id, Items.RAW_COPPER);
            int obsidianLeft = 0;
            for (BlockPos q : c.obsidian()) if (level.getBlockState(q).is(Blocks.OBSIDIAN)) obsidianLeft++;
            boolean diamondOre = level.getBlockState(c.diamond()).is(Blocks.DIAMOND_ORE);
            ChestBlockEntity loot = (ChestBlockEntity) level.getBlockEntity(c.lootChest());
            ChestBlockEntity mine = (ChestBlockEntity) level.getBlockEntity(c.playerChest());
            int lootDiamonds = 0, lootFlesh = 0, mineDiamonds = 0, mineIngots = 0;
            for (int i = 0; i < loot.getContainerSize(); i++) {
                if (loot.getItem(i).is(Items.DIAMOND)) lootDiamonds += loot.getItem(i).getCount();
                if (loot.getItem(i).is(Items.ROTTEN_FLESH)) lootFlesh += loot.getItem(i).getCount();
            }
            for (int i = 0; i < mine.getContainerSize(); i++) {
                if (mine.getItem(i).is(Items.DIAMOND)) mineDiamonds += mine.getItem(i).getCount();
                if (mine.getItem(i).is(Items.IRON_INGOT)) mineIngots += mine.getItem(i).getCount();
            }
            int torches = torchesIn(level, c);
            int round = 0;
            for (BlockPos q : BlockPos.betweenClosed(c.spawner().offset(-2, 0, -2), c.spawner().offset(2, 1, 2))) {
                if (level.getBlockState(q).is(Blocks.TORCH) || level.getBlockState(q).is(Blocks.WALL_TORCH)) round++;
            }
            boolean spawner = level.getBlockState(c.spawner()).is(Blocks.SPAWNER);
            String report = CaveDwellers.debug(id);
            Kit.log("cd03 home at tick " + helper.getTick() + " (" + p.why() + "): storehouse +" + raw + " raw iron, +" + coal + " coal, +" + diamonds
                + " diamonds; " + copper + " raw copper in the stores; iron ore left " + ironLeft(level, c) + "/5, obsidian left " + obsidianLeft
                + "/2, diamond ore " + (diamondOre ? "left" : "mined") + "; the old chest: " + lootDiamonds + " diamonds, " + lootFlesh + " rotten flesh; the player's: "
                + mineDiamonds + " diamonds, " + mineIngots + " ingot; torches in the cave " + torches + " (" + round + " round the spawner); spawner "
                + (spawner ? "standing" : "gone") + "; zombie " + (zombie.isAlive() ? "alive" : "dead") + "; " + report);
            for (String line : CaveDwellers.page(level, t.v())) Kit.log("cd03 page: " + line);
            helper.assertTrue(ironLeft(level, c) == 0 && raw >= 5, "the whole iron vein mined, the hidden block too, and into the storehouse: +" + raw);
            helper.assertTrue(coal >= 3 && copper >= 1, "the coal and the copper: +" + coal + ", " + copper);
            helper.assertTrue(!diamondOre, "the diamond ore mined with the iron pick");
            helper.assertTrue(obsidianLeft == 2, "the obsidian left: an iron pick won't take it");
            helper.assertTrue(lootDiamonds == 0 && lootFlesh >= 5 && diamonds >= 3, "the old chest's diamonds taken into the storehouse, its rotten flesh left: +" + diamonds);
            helper.assertTrue(mineDiamonds == 3 && mineIngots == 1, "the player's chest untouched");
            helper.assertTrue(torches >= 1 && torches <= 12, "the cave lit, not carpeted: " + torches + " torches");
            helper.assertTrue(!zombie.isAlive(), "the zombie killed");
            helper.assertTrue(spawner && round >= 1, "the spawner left be, and lit up round it: " + round);
            helper.assertTrue(report.contains("; cave ") && report.contains("; vein iron") && report.contains("; chest ") && report.contains("; dungeon ")
                && report.contains("; vein obsidian"), "the report has the cave, the veins, the dungeon and the chest: " + report);
            helper.succeed();
        });
    }

    // ============================================================ cd04: obsidian, with a diamond pick

    /** The same cave (no chests, no zombie), the stores' pick a diamond one: the obsidian comes out too, and home. */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "cd04_obsidian")
    public static void cd04_obsidian(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 926000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(0);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, kit(1, false, 2, new ItemStack(Items.DIAMOND_PICKAXE)));
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, d);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        Kit.log("cd04 " + d.displayNameCap() + " sent " + sent + " with " + has(d, s -> s.is(Items.DIAMOND_PICKAXE)) + " diamond pick");
        helper.assertTrue(sent && has(d, s -> s.is(Items.DIAMOND_PICKAXE)) == 1, "out, with the stores' diamond pick");
        CaveDwellers.Party p = CaveDwellers.partyOf(d);
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (helper.getTick() % 100 == 0) {
                    Kit.log("cd04 tick " + helper.getTick() + ": " + p.phase() + ", mined " + p.mined() + " at " + d.blockPosition().toShortString() + "; " + d.hobbyNow());
                }
                pace(level, d);
                return;
            }
            done[0] = true;
            int obsidianLeft = 0;
            for (BlockPos q : c.obsidian()) if (level.getBlockState(q).is(Blocks.OBSIDIAN)) obsidianLeft++;
            int obsidian = inStorehouse(t, Items.OBSIDIAN);
            Kit.log("cd04 home at tick " + helper.getTick() + ": obsidian left " + obsidianLeft + "/2, " + obsidian + " in the storehouse; "
                + CaveDwellers.veinsForTests(id, p.caveKey() == null ? c.chamber() : p.caveKey()) + "; " + CaveDwellers.debug(id));
            helper.assertTrue(obsidianLeft == 0 && obsidian >= 2, "the obsidian mined with the diamond pick, and in the storehouse: " + obsidianLeft + " left, " + obsidian);
            helper.succeed();
        });
    }

    // ============================================================ cd05: a team of two, every vein

    /**
     * A team of two (the one at level six leading) into the test's cave, which nobody has mapped (a day's plan), with a
     * second iron vein hidden two blocks behind the east wall. They keep together the whole way (never more than ten
     * blocks apart, out or in); the hidden vein is found and mined; the cave's list ends with every vein mined but the
     * obsidian, which waits for a better pick; home the same day, the ore in the storehouse.
     */
    @GameTest(template = EMPTY, timeoutTicks = 7200, batch = "cd05_team_trip")
    public static void cd05_team_trip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 928000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        a.tradeXpForTests(StationTask.CAVE, AssistantEntity.xpForLevel(6));
        b.tradeXpForTests(StationTask.CAVE, AssistantEntity.xpForLevel(4));
        Cave c = cave(level, x + OUT, Z, false);
        BlockPos hidden = new BlockPos(c.x0() + 22, c.base() + 1, c.z0() + 3);
        level.setBlock(hidden, Blocks.IRON_ORE.defaultBlockState(), 2);
        fill(t, kit(2, true, 4));
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, a, b);
        long day0 = level.getDayTime() / 24000L;
        int raw0 = inStorehouse(t, Items.RAW_IRON);
        boolean sent = CaveDwellers.sendForTests(a, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(a);
        Kit.log("cd05 sent " + sent + ": " + (p == null ? "no party" : p.members().size() + " in the team, " + p.plan().words() + " (" + p.days() + " days)")
            + "; torches " + has(a, s -> s.is(Items.TORCH)) + " and " + has(b, s -> s.is(Items.TORCH)));
        helper.assertTrue(sent && p != null && p.members().size() == 2 && CaveDwellers.partyOf(b) == p, "the whole team out together, one party");
        helper.assertTrue(p.leader().equals(a.getUUID()), "the most experienced leads");
        helper.assertTrue(p.days() == 1.0 && p.plan() != null && !p.plan().mapped(), "a cave nobody has mapped: a day's scouting: " + p.plan().words());
        double[] spread = { 0 };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (out(a, b)) {
                if (a.expedition() != null && b.expedition() != null && !p.phase().equals("store") && !p.phase().equals("camp")) {
                    spread[0] = Math.max(spread[0], Math.sqrt(a.distanceToSqr(b)));
                }
                if (helper.getTick() % 100 == 0) {
                    Kit.log("cd05 tick " + helper.getTick() + ": " + p.phase() + ", mined " + p.mined() + ", torches " + p.torches() + ", apart "
                        + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(a.distanceToSqr(b))) + " (most " + String.format(java.util.Locale.ROOT, "%.1f", spread[0])
                        + "); " + a.displayNameCap() + " " + a.hobbyNow() + "; " + b.displayNameCap() + " " + b.hobbyNow());
                }
                pace(level, a, b);
                return;
            }
            done[0] = true;
            String veins = CaveDwellers.veinsForTests(id, p.caveKey() == null ? c.chamber() : p.caveKey());
            int raw = inStorehouse(t, Items.RAW_IRON) - raw0;
            boolean hiddenGone = !level.getBlockState(hidden).is(Blocks.IRON_ORE);
            long day = level.getDayTime() / 24000L;
            Kit.log("cd05 home at tick " + helper.getTick() + " (" + p.why() + "), day " + (day + 1) + ": the list: " + veins + "; order " + p.order()
                + "; storehouse +" + raw + " raw iron; hidden vein " + (hiddenGone ? "mined" : "left") + "; most apart "
                + String.format(java.util.Locale.ROOT, "%.1f", spread[0]) + " (the party's own count " + String.format(java.util.Locale.ROOT, "%.1f", p.spread())
                + "); torches set " + p.torches() + " of " + p.drawn() + " drawn");
            for (String line : CaveDwellers.page(level, t.v())) Kit.log("cd05 page: " + line);
            helper.assertTrue(spread[0] <= 10.0, "together the whole way: never more than ten blocks apart: " + spread[0]);
            helper.assertTrue(hiddenGone, "the vein hidden two blocks behind the wall found and mined");
            helper.assertTrue(veins.contains("obsidian 2 waiting"), "the obsidian on the list, waiting for a better pick: " + veins);
            boolean allMined = !veins.isEmpty();
            for (String v : veins.split("; ")) if (!v.startsWith("obsidian") && !v.endsWith(" mined")) allMined = false;
            helper.assertTrue(allMined && veins.split("; ").length >= 6, "every other vein on the list mined (six veins): " + veins);
            helper.assertTrue(raw >= 6, "the iron in the storehouse, the hidden vein's too: +" + raw);
            helper.assertTrue(day == day0, "home the same day");
            helper.succeed();
        });
    }

    // ============================================================ cd06: the plans

    /**
     * The plans for a team of two with iron picks: a cave nobody has mapped, a day; a great cave a hundred and fifty
     * blocks out and forty down with sixty iron veins on its list, days; the same with little food in the stores,
     * fewer; a small cave near the town with two coal veins left, half a day.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cd06_plans")
    public static void cd06_plans(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 930000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE, StationTask.MINE, StationTask.GUARD);
        UUID id = t.village();
        List<VillageFolkEntity> team = List.of(t.folk().get(0), t.folk().get(1));
        for (VillageFolkEntity f : team) f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        ItemStack[] plenty = kit(2, true, 10);
        fill(t, plenty);
        // (a) A cave nobody has mapped.
        CaveTrips.Plan unmapped = CaveTrips.plan(level, t.v(), team, t.heart().offset(120, -30, 0));
        // (b) A great cave, a hundred and fifty out and forty down, sixty iron veins listed.
        BlockPos great = t.heart().offset(150, -40, 20);
        CaveDwellers.recordForTests(id, great, "a great cave", 40, 700);
        List<CaveDwellers.Vein> list = new ArrayList<>();
        for (int i = 0; i < 60; i++) list.add(new CaveDwellers.Vein("iron", great.offset(i % 10 - 5, i / 10 - 3, (i * 7) % 11 - 5), 8, i % 3, "todo"));
        CaveDwellers.listForTests(id, great, list);
        CaveTrips.Plan big = CaveTrips.plan(level, t.v(), team, great);
        // (c) The same, with little food in the stores.
        ItemStack[] little = kit(2, true, 10, new ItemStack(Items.BREAD, 3 * Villages.headcount(id) + 8));
        for (int i = 0; i < little.length - 1; i++) if (little[i].is(Items.BREAD)) little[i] = ItemStack.EMPTY;
        fill(t, little);
        CaveTrips.Plan hungry = CaveTrips.plan(level, t.v(), team, great);
        fill(t, plenty);
        // (d) A small cave near the town, two coal veins left on its list.
        BlockPos near = t.heart().offset(40, -8, -10);
        CaveDwellers.recordForTests(id, near, "a cave", 8, 120);
        CaveDwellers.listForTests(id, near, List.of(new CaveDwellers.Vein("coal", near.offset(3, 1, 2), 3, 0, "todo"),
            new CaveDwellers.Vein("coal", near.offset(-4, 0, 1), 3, 0, "todo"), new CaveDwellers.Vein("iron", near.offset(0, 1, 5), 4, 0, "mined")));
        CaveTrips.Plan small = CaveTrips.plan(level, t.v(), team, near);
        for (CaveTrips.Plan pl : List.of(unmapped, big, hungry, small)) {
            Kit.log("cd06 plan: " + pl.days() + " days (" + pl.kind() + "): " + pl.words());
            for (String r : pl.reckoning()) Kit.log("cd06     " + r);
        }
        helper.assertTrue(unmapped.days() == 1.0 && !unmapped.mapped() && unmapped.words().contains("map"), "a cave nobody has mapped: a day: " + unmapped.words());
        helper.assertTrue(big.days() >= 2.0 && big.kind().equals("long") && big.veins() == 60, "a great cave with sixty veins listed: days: " + big.words());
        helper.assertTrue(hungry.days() < big.days() && hungry.words().contains("food"), "with little food to spare, a shorter plan: " + hungry.words());
        helper.assertTrue(small.days() == 0.5 && small.kind().equals("short") && small.words().startsWith("Half a day") && small.words().contains("two coal veins"),
            "a small cave near the town with two coal veins left: half a day: " + small.words());
        helper.succeed();
    }

    // ============================================================ cd07: a short trip, the town's want first

    /**
     * The test's cave already on the town's list, with only its iron (far, by the east wall) and its coal (near the way
     * in) to do: half a day's plan. The town wants iron, so the team goes past the coal to the iron first; home the same
     * day.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "cd07_short_trip")
    public static void cd07_short_trip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 931000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        a.tradeXpForTests(StationTask.CAVE, AssistantEntity.xpForLevel(5));
        Cave c = cave(level, x + OUT, Z, false);
        CaveDwellers.recordForTests(id, c.chamber(), "a cave", 13, 500);
        CaveDwellers.listForTests(id, c.chamber(), List.of(new CaveDwellers.Vein("iron", c.iron().get(1), 5, 0, "todo"),
            new CaveDwellers.Vein("coal", c.coal().get(0), 3, 0, "todo")));
        CaveDwellers.wantedForTests("iron");
        fill(t, kit(2, true, 4));
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, a, b);
        long day0 = level.getDayTime() / 24000L;
        boolean sent = CaveDwellers.sendForTests(a, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(a);
        Kit.log("cd07 sent " + sent + ": " + (p == null || p.plan() == null ? "no plan" : p.plan().words() + " (" + p.days() + " days, turns at " + p.turnAt() + ")"));
        helper.assertTrue(sent && p != null && p.plan() != null, "the team out on a plan");
        helper.assertTrue(p.days() == 0.5 && p.plan().kind().equals("short"), "a small cave with two veins left near the town: half a day: " + p.plan().words());
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (out(a, b)) {
                if (helper.getTick() % 100 == 0) Kit.log("cd07 tick " + helper.getTick() + ": " + p.phase() + ", order " + p.order() + "; " + a.hobbyNow());
                pace(level, a, b);
                return;
            }
            done[0] = true;
            long day = level.getDayTime() / 24000L;
            Kit.log("cd07 home at tick " + helper.getTick() + " (" + p.why() + "), day " + (day + 1) + ": order " + p.order() + "; "
                + CaveDwellers.veinsForTests(id, c.chamber()));
            helper.assertTrue(!p.order().isEmpty() && p.order().get(0).equals("iron"), "the town's want first, past the nearer coal: " + p.order());
            helper.assertTrue(ironLeft(level, c) == 0, "the iron mined");
            helper.assertTrue(day == day0, "home the same day");
            helper.succeed();
        });
    }

    // ============================================================ cd08: a night under the rock

    /**
     * A two-day trip into the test's cave: at dusk the team makes camp in a nook, walls it in with cobblestone a block at
     * a time, and keeps a watch; at first light it eats, takes the walls down, and goes on.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "cd08_camp")
    public static void cd08_camp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 932000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, kit(2, true, 6, new ItemStack(Items.COBBLESTONE, 64)));
        CaveTrips.daysForTests(2.0);
        toTheMouth(t, c, a, b);
        boolean sent = CaveDwellers.sendForTests(a, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(a);
        helper.assertTrue(sent && p != null && p.days() == 2.0 && p.plan().kind().equals("long"), "a two-day trip");
        Kit.log("cd08 sent: " + p.plan().words() + "; cobble " + has(a, s -> s.is(Items.COBBLESTONE)) + " and " + has(b, s -> s.is(Items.COBBLESTONE)));
        long day0 = level.getDayTime() / 24000L;
        int[] stage = { 0 };
        int[] at = { 0 };
        List<BlockPos> walls = new ArrayList<>();
        helper.onEachTick(() -> {
            if (stage[0] < 0) return;
            if (helper.getTick() % 50 == 0) {
                Kit.log("cd08 tick " + helper.getTick() + " stage " + stage[0] + ": " + p.phase() + ", camp " + (p.camp() == null ? "-" : p.camp().toShortString())
                    + ", walls " + p.campWalls().size() + "; " + a.hobbyNow() + "; " + b.hobbyNow());
            }
            switch (stage[0]) {
                case 0 -> {
                    if (p.phase().equals("in")) {
                        level.setDayTime(day0 * 24000L + 11900L);                   // dusk
                        stage[0] = 1;
                    }
                }
                case 1 -> {
                    if (p.phase().equals("camp") && p.campWalls().size() >= 3) {
                        stage[0] = 2;
                        at[0] = (int) helper.getTick();
                    }
                }
                case 2 -> {
                    // A while for the walls to go up and the other to settle in: one asleep, one on watch.
                    if (helper.getTick() - at[0] < 120 || a.isShiftKeyDown() == b.isShiftKeyDown() && helper.getTick() - at[0] < 500) return;
                    walls.addAll(p.campWalls());
                    int cobble = 0;
                    for (BlockPos q : walls) if (level.getBlockState(q).is(Blocks.COBBLESTONE)) cobble++;
                    int lit = p.camp() == null ? 0 : level.getBrightness(LightLayer.BLOCK, p.camp());
                    boolean watch = a.isShiftKeyDown() != b.isShiftKeyDown();
                    Kit.log("cd08 camped at " + p.camp().toShortString() + ": " + cobble + " of " + walls.size() + " wall blocks standing, light " + lit
                        + ", night " + p.nights() + ", on watch " + (a.isShiftKeyDown() ? b.displayNameCap() : a.displayNameCap()) + "; " + p.dayOf(level.getDayTime()));
                    helper.assertTrue(cobble == walls.size() && cobble >= 3, "walled in with cobblestone: " + cobble);
                    helper.assertTrue(p.nights() == 1, "the first night out");
                    helper.assertTrue(watch, "one keeps watch while the other sleeps");
                    level.setDayTime((day0 + 1) * 24000L + 1000L);                  // first light
                    stage[0] = 3;
                }
                case 3 -> {
                    if (p.phase().equals("camp")) return;
                    int still = 0;
                    for (BlockPos q : walls) if (level.getBlockState(q).is(Blocks.COBBLESTONE)) still++;
                    Kit.log("cd08 broke camp at tick " + helper.getTick() + ": " + p.phase() + ", " + still + " wall blocks left standing; "
                        + p.dayOf(level.getDayTime()));
                    helper.assertTrue(still == 0, "the walls taken down");
                    helper.assertTrue(p.dayOf(level.getDayTime()).equals("day 2 of 2"), "day two of two: " + p.dayOf(level.getDayTime()));
                    stage[0] = -1;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ cd09: out of torches

    /**
     * A cave dweller in the test's cave with its torches all but gone and no coal or wood to make more: it turns for
     * home before the work is done, and says why.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "cd09_torches_out")
    public static void cd09_torches_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 933000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        VillageFolkEntity d = t.folk().get(0);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, kit(1, true, 2));
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, d);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(d);
        helper.assertTrue(sent && p != null, "out");
        boolean[] stripped = { false };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (!stripped[0]) {
                if (!p.phase().equals("in")) return;
                int torches = d.countMatching(s -> s.is(Items.TORCH));
                d.removeMatching(s -> s.is(Items.TORCH), Math.max(0, torches - 2));
                d.removeMatching(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.STICK) || s.is(net.minecraft.tags.ItemTags.PLANKS)
                    || s.is(net.minecraft.tags.ItemTags.LOGS), 999);
                stripped[0] = true;
                Kit.log("cd09 in the cave at tick " + helper.getTick() + ": torches down to " + d.countMatching(s -> s.is(Items.TORCH)) + ", no coal, no wood");
                return;
            }
            if (!p.homeward()) return;
            done[0] = true;
            Kit.log("cd09 turned for home at tick " + helper.getTick() + ": " + p.why() + "; mined " + p.mined() + ", iron left " + ironLeft(level, c));
            helper.assertTrue(p.why().contains("torches"), "the reason: the torches: " + p.why());
            helper.assertTrue(ironLeft(level, c) > 0, "early: before the work was done");
            helper.succeed();
        });
    }

    // ============================================================ cd10: overdue

    /** A team out on a trip a day past its plan: the town sends a search party after its leader, and misses it. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cd10_overdue")
    public static void cd10_overdue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 934000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        a.tradeXpForTests(StationTask.CAVE, AssistantEntity.xpForLevel(5));
        for (VillageFolkEntity f : t.folk()) f.setAgeForTests(30);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, kit(2, true, 4));
        toTheMouth(t, c, a, b);
        boolean sent = CaveDwellers.sendForTests(a, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(a);
        helper.assertTrue(sent && p != null && CaveTrips.trip(id) != null, "out, and the town keeps the trip");
        boolean early = CaveTrips.checkOverdue(level, t.v());
        CaveDwellers.overdueForTests(level, a);
        boolean went = CaveTrips.checkOverdue(level, t.v());
        boolean again = CaveTrips.checkOverdue(level, t.v());
        List<UUID> searchers = SearchParties.partyForTests(a.getUUID());
        Kit.log("cd10 on time: search " + early + "; a day overdue: search " + went + " (" + searchers.size() + " out), once more " + again
            + "; missed " + CaveDwellers.missing(a) + "; " + CaveTrips.trip(id));
        helper.assertTrue(!early, "no search while the team is on time");
        helper.assertTrue(went && !searchers.isEmpty(), "a day overdue: a search party goes out after the leader");
        helper.assertTrue(!again && CaveTrips.trip(id).searched(), "once");
        helper.assertTrue(CaveDwellers.missing(a), "the leader missed till found");
        helper.succeed();
    }

    // ============================================================ cd11: torches made on the fly

    /**
     * A cave dweller with sixteen coal and eight planks in its pack and no torches (the stores have none to spare): down in
     * the cave it makes torches of them (a coal and a stick to four), and lights its way with them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "cd11_craft_torches")
    public static void cd11_craft_torches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 935000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        VillageFolkEntity d = t.folk().get(0);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, kit(1, true, 0));
        d.insertItem(new ItemStack(Items.COAL, 16));
        d.insertItem(new ItemStack(Items.OAK_PLANKS, 8));
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, d);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(d);
        int torches0 = d.countMatching(s -> s.is(Items.TORCH));
        Kit.log("cd11 sent " + sent + ": torches " + torches0 + ", coal " + d.countMatching(s -> s.is(Items.COAL)) + ", planks "
            + d.countMatching(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) + "; " + (p == null || p.plan() == null ? "" : p.plan().words()));
        helper.assertTrue(sent && p != null && torches0 == 0, "out with no torches, but coal and wood");
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (helper.getTick() % 100 == 0) Kit.log("cd11 tick " + helper.getTick() + ": " + p.phase() + ", made " + p.torchesMade() + ", set " + p.torches()
                    + ", carrying " + d.countMatching(s -> s.is(Items.TORCH)) + "; " + d.hobbyNow());
                pace(level, d);
                return;
            }
            done[0] = true;
            Kit.log("cd11 home at tick " + helper.getTick() + " (" + p.why() + "): crafted " + p.crafted() + "; " + p.torches() + " torches set, "
                + torchesIn(level, c) + " in the cave");
            helper.assertTrue(p.torchesMade() >= 4 && String.join(" ", p.crafted()).contains("torches from"), "torches made of its coal and sticks: " + p.crafted());
            helper.assertTrue(p.torches() >= 1 && torchesIn(level, c) >= 1, "and the cave lit with them: " + p.torches());
            helper.succeed();
        });
    }

    // ============================================================ cd12: a new pick, made down there

    /**
     * A cave dweller whose stone pick has two uses left, the stores with no pick to give it: down in the cave it sets its
     * crafting table down, makes a new pick of its cobblestone and sticks, picks the table up again, and mines the iron
     * with it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "cd12_craft_pick")
    public static void cd12_craft_pick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 936000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        VillageFolkEntity d = t.folk().get(0);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, kit(1, false, 2, new ItemStack(Items.OAK_PLANKS, 16)));
        ItemStack worn = new ItemStack(Items.STONE_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 2);
        d.insertItem(worn);
        CaveDwellers.quickForTests(true);
        toTheMouth(t, c, d);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(d);
        Kit.log("cd12 sent " + sent + ": picks " + has(d, CaveDwellers::isPickaxe) + ", table " + has(d, s -> s.is(Items.CRAFTING_TABLE)) + ", sticks "
            + has(d, s -> s.is(Items.STICK)) + ", cobble " + has(d, s -> s.is(Items.COBBLESTONE)));
        helper.assertTrue(sent && p != null, "out with a worn pick");
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (helper.getTick() % 100 == 0) Kit.log("cd12 tick " + helper.getTick() + ": " + p.phase() + ", tools made " + p.toolsMade() + ", mined "
                    + p.mined() + "; " + d.hobbyNow());
                pace(level, d);
                return;
            }
            done[0] = true;
            String crafted = String.join("; ", p.crafted());
            Kit.log("cd12 home at tick " + helper.getTick() + " (" + p.why() + "): crafted " + crafted + "; iron left " + ironLeft(level, c)
                + "; table carried " + has(d, s -> s.is(Items.CRAFTING_TABLE)));
            helper.assertTrue(p.toolsMade() >= 1 && (crafted.contains("stone pickaxe") || crafted.contains("iron pickaxe")),
                "a new pick made down there: " + crafted);
            helper.assertTrue(ironLeft(level, c) == 0, "and the iron mined with it");
            helper.assertTrue(has(d, s -> s.is(Items.CRAFTING_TABLE)) == 1, "its crafting table picked up again");
            helper.succeed();
        });
    }
}
