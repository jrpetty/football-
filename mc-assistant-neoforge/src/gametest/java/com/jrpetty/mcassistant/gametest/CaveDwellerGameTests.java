package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CaveDwellers;
import com.jrpetty.mcassistant.entity.Market;
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
 * [caves] The cave dwellers (CaveDwellers): an Iron Age town of twenty-eight takes one up, from an idle hand and never
 * from a trade it is short of, and a Stone Age town does not; one is kitted out free from the stores (the watch's iron,
 * a sword, a shield, the best pick, torches, food) and hands the kit back when it leaves the trade; one sent into a
 * cave in a block of rock walks in, lights it, mines the iron, coal, copper and diamond its iron pick allows and leaves
 * the obsidian, kills the zombie down there, notes the dungeon's spawner, opens the old chest and takes its valuables
 * (never the player's chest beside it), comes home with it all and puts it in the stores, and the report has the cave,
 * the veins, the dungeon and the chest; with a diamond pick the obsidian comes out too.
 *
 * <p>Each on its own ground (x 920000 to 927000, z 66000), in a batch of its own. The caves are cut into a block of
 * stone set on the flat world, its tunnel level with the ground, sixty-four blocks east of the heart (out of the town's
 * reach: nothing is mined or opened in a town).
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

    private static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
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
     * an iron ingot, a zombie spawner by mossy stones in the south wall, and a zombie.
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
            if (level.getBlockState(p).is(Blocks.TORCH)) n++;
        }
        return n;
    }

    // ============================================================ cd01: who takes it up

    /**
     * Six folk, the roll a town of twenty-eight (two miners, a guard, three farmers): in the Stone Age it wants no cave
     * dweller and takes nobody. In the Iron Age it wants one: with every trade short of hands for a town that size it
     * takes nobody from them; with an idle hand, that hand; and a second look takes nobody more.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cd01_takes_on")
    public static void cd01_takes_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 920000, Villages.Age.STONE, StationTask.MINE, StationTask.MINE, StationTask.GUARD, StationTask.FARM,
            StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        for (int i = 0; i < 22; i++) Villages.recordBirth(id);
        long day = level.getDayTime() / 24000L;
        int heads = Villages.headcount(id);
        int stoneWanted = CaveDwellers.wanted(id);
        boolean stoneWants = Villages.wants(id, StationTask.CAVE);
        VillageFolkEntity stoneTook = CaveDwellers.appoint(level, t.v(), day);
        Kit.log("cd01 Stone Age, " + heads + " folk on the roll: wanted " + stoneWanted + ", the town wants the trade " + stoneWants
            + ", took " + (stoneTook == null ? "nobody" : stoneTook.displayNameCap()));
        helper.assertTrue(heads >= 25, "a town of twenty-five or more on the roll: " + heads);
        helper.assertTrue(stoneWanted == 0 && !stoneWants && stoneTook == null, "no cave dweller in the Stone Age");
        Villages.ageForTests(id, Villages.Age.IRON);
        int ironWanted = CaveDwellers.wanted(id);
        boolean ironWants = Villages.wants(id, StationTask.CAVE);
        StringBuilder shares = new StringBuilder();
        for (StationTask s : new StationTask[]{ StationTask.MINE, StationTask.GUARD, StationTask.FARM }) {
            shares.append(' ').append(s.name().toLowerCase()).append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", Villages.share(id, s)));
        }
        VillageFolkEntity shortTook = CaveDwellers.appoint(level, t.v(), day);
        Kit.log("cd01 Iron Age: wanted " + ironWanted + ", wants the trade " + ironWants + "; shares" + shares + "; with every trade short it took "
            + (shortTook == null ? "nobody" : shortTook.displayNameCap() + " (" + shortTook.stationTask() + ")"));
        helper.assertTrue(ironWanted == 1 && ironWants, "an Iron Age town of " + heads + " wants one cave dweller: " + ironWanted);
        helper.assertTrue(shortTook == null, "never taken from a trade the town is short of: took " + (shortTook == null ? "" : shortTook.displayNameCap()));
        VillageFolkEntity idle = null;
        for (int i = t.folk().size() - 1; i >= 1 && idle == null; i--) if (!t.folk().get(i).isElder()) idle = t.folk().get(i);
        helper.assertTrue(idle != null, "a hand that is not the elder");
        idle.setJob(StationTask.NONE);
        idle.setAgeForTests(30);
        VillageFolkEntity took = CaveDwellers.appoint(level, t.v(), day);
        VillageFolkEntity again = CaveDwellers.appoint(level, t.v(), day);
        int miners = 0, guards = 0;
        for (VillageFolkEntity f : t.folk()) {
            if (f.stationTask() == StationTask.MINE) miners++;
            if (f.stationTask() == StationTask.GUARD) guards++;
        }
        Kit.log("cd01 with " + idle.displayNameCap() + " idle: took " + (took == null ? "nobody" : took.displayNameCap() + ", now " + took.stationTask())
            + "; a second look took " + (again == null ? "nobody" : again.displayNameCap()) + "; miners " + miners + ", guards " + guards
            + "; " + CaveDwellers.dwellers(id).size() + " cave dwellers");
        helper.assertTrue(took == idle && idle.stationTask() == StationTask.CAVE, "the idle hand took up the caves");
        helper.assertTrue(again == null, "one wanted, one taken");
        helper.assertTrue(miners == 2 && guards == 1, "the miners and the guard kept their trades");
        helper.succeed();
    }

    // ============================================================ cd02: the kit

    /**
     * An Iron Age town's cave dweller goes to the stores: it comes away in the watch's iron armour with an iron sword, a
     * shield, the iron pick, a stack of torches and bread, its purse untouched. Taking up the fields, it hands the town's
     * kit back into the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cd02_kit")
    public static void cd02_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 922000, Villages.Age.IRON, StationTask.MINE, StationTask.MINE, StationTask.GUARD, StationTask.CAVE);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(3);
        fill(t, new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS),
            new ItemStack(Items.IRON_BOOTS), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_PICKAXE),
            new ItemStack(Items.TORCH, 64), new ItemStack(Items.BREAD, 16), new ItemStack(Items.COBBLESTONE, 64));
        int purse0 = d.purse();
        int torches0 = stock(level, id, Items.TORCH);
        List<String> got = CaveDwellers.kitUpForTests(level, t.v(), d);
        int torches = has(d, s -> s.is(Items.TORCH)), food = has(d, s -> s.is(Items.BREAD));
        Kit.log("cd02 " + d.displayNameCap() + " fitted out: " + got + "; wears " + d.getItemBySlot(EquipmentSlot.HEAD).getItem() + ", "
            + d.getItemBySlot(EquipmentSlot.CHEST).getItem() + ", " + d.getItemBySlot(EquipmentSlot.LEGS).getItem() + ", " + d.getItemBySlot(EquipmentSlot.FEET).getItem()
            + "; swords " + has(d, s -> s.is(Items.IRON_SWORD)) + ", picks " + has(d, s -> s.is(Items.IRON_PICKAXE)) + ", shields " + has(d, s -> s.is(Items.SHIELD))
            + ", torches " + torches + ", bread " + food + "; stores' torches " + torches0 + " -> " + stock(level, id, Items.TORCH)
            + "; purse " + purse0 + " -> " + d.purse());
        helper.assertTrue(d.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && d.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
            && d.getItemBySlot(EquipmentSlot.LEGS).is(Items.IRON_LEGGINGS) && d.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS), "in the town's iron armour");
        helper.assertTrue(has(d, s -> s.is(Items.IRON_SWORD)) >= 1, "with an iron sword");
        helper.assertTrue(has(d, s -> s.is(Items.SHIELD)) >= 1, "and a shield");
        helper.assertTrue(has(d, s -> s.is(Items.IRON_PICKAXE)) >= 1 && stock(level, id, Items.IRON_PICKAXE) == 0, "the stores' iron pick");
        helper.assertTrue(torches >= 16 && food >= 1, "torches and food for the day: " + torches + ", " + food);
        helper.assertTrue(d.purse() == purse0, "free: the purse untouched");
        d.setJob(StationTask.FARM);
        Kit.log("cd02 a farmer now: the stores hold " + stock(level, id, Items.IRON_CHESTPLATE) + " iron chestplate, " + stock(level, id, Items.IRON_PICKAXE)
            + " iron pick, " + stock(level, id, Items.IRON_SWORD) + " iron sword, " + stock(level, id, Items.SHIELD) + " shield; it wears "
            + d.getItemBySlot(EquipmentSlot.CHEST).getItem());
        helper.assertTrue(stock(level, id, Items.IRON_CHESTPLATE) == 1 && stock(level, id, Items.IRON_PICKAXE) == 1 && stock(level, id, Items.IRON_SWORD) == 1,
            "the town's kit back in the stores when it leaves the caves");
        helper.assertTrue(d.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "and off its back");
        helper.succeed();
    }

    // ============================================================ cd03: a day in the caves

    /**
     * A cave dweller with the town's iron kit, sent into the test's cave: it walks in, lights it, mines the iron (all
     * five, the one behind the others too), the coal, the copper and the diamond, leaves the obsidian (its iron pick
     * won't take it), kills the zombie, notes the dungeon's spawner and leaves it be, opens the old chest and takes its
     * diamonds and whatever else of worth the world's loot put in it (not the rotten flesh), never touches the player's
     * chest beside it, and comes home: the ore and the chest's diamonds are in the stores, and the report has the cave,
     * the veins, the dungeon and the chest.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "cd03_delve")
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
        fill(t, new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS),
            new ItemStack(Items.IRON_BOOTS), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_PICKAXE),
            new ItemStack(Items.TORCH, 64), new ItemStack(Items.BREAD, 16), new ItemStack(Items.COBBLESTONE, 64));
        CaveDwellers.quickForTests(true);
        d.moveTo(c.mouth().getX() + 0.5, c.mouth().getY(), c.mouth().getZ() + 0.5, -90.0F, 0.0F);
        int raw0 = stock(level, id, Items.RAW_IRON), coal0 = stock(level, id, Items.COAL), diamonds0 = stock(level, id, Items.DIAMOND);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        Kit.log("cd03 " + d.displayNameCap() + " sent " + sent + " into the cave at " + c.chamber().toShortString() + " from " + d.blockPosition().toShortString()
            + "; kit: " + d.getItemBySlot(EquipmentSlot.CHEST).getItem() + ", picks " + has(d, CaveDwellers::isPickaxe) + ", torches "
            + has(d, s -> s.is(Items.TORCH)));
        helper.assertTrue(sent && d.expedition() != null && d.expedition().delve() != null, "out for the day, into the caves");
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (helper.getTick() % 100 == 0) {
                    CaveDwellers.Delve dv = d.expedition().delve();
                    Kit.log("cd03 tick " + helper.getTick() + ": " + (dv == null ? "?" : dv.phase() + ", mined " + dv.mined() + ", chests " + dv.opened()
                        + ", slain " + dv.slain() + ", torches " + dv.torches()) + (d.expedition().returning() ? ", homeward" : "") + " at "
                        + d.blockPosition().toShortString() + "; " + d.hobbyNow() + "; zombie " + (zombie.isAlive() ? "alive" : "dead"));
                }
                if (d.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
                return;
            }
            done[0] = true;
            int raw = stock(level, id, Items.RAW_IRON) - raw0, coal = stock(level, id, Items.COAL) - coal0, diamonds = stock(level, id, Items.DIAMOND) - diamonds0;
            int copper = stock(level, id, Items.RAW_COPPER);
            int ironLeft = 0;
            for (BlockPos p : c.iron()) if (level.getBlockState(p).is(Blocks.IRON_ORE)) ironLeft++;
            int obsidianLeft = 0;
            for (BlockPos p : c.obsidian()) if (level.getBlockState(p).is(Blocks.OBSIDIAN)) obsidianLeft++;
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
            boolean spawner = level.getBlockState(c.spawner()).is(Blocks.SPAWNER);
            String report = CaveDwellers.debug(id);
            List<String> page = CaveDwellers.page(level, t.v());
            Kit.log("cd03 home at tick " + helper.getTick() + ": stores +" + raw + " raw iron, +" + coal + " coal, +" + diamonds + " diamonds, " + copper
                + " raw copper; iron ore left " + ironLeft + "/5, obsidian left " + obsidianLeft + "/2, diamond ore " + (diamondOre ? "left" : "mined")
                + "; the old chest: " + lootDiamonds + " diamonds, " + lootFlesh + " rotten flesh left; the player's: " + mineDiamonds + " diamonds, "
                + mineIngots + " ingot; torches in the cave " + torches + "; spawner " + (spawner ? "standing" : "gone") + "; zombie "
                + (zombie.isAlive() ? "alive" : "dead") + "; " + report);
            for (String line : page) Kit.log("cd03 page: " + line);
            helper.assertTrue(ironLeft == 0 && raw >= 5, "the whole iron vein mined, the hidden block too, and home: " + ironLeft + " left, +" + raw);
            helper.assertTrue(coal >= 3 && copper >= 1, "the coal and the copper: +" + coal + ", " + copper);
            helper.assertTrue(!diamondOre, "the diamond ore mined with the iron pick");
            helper.assertTrue(obsidianLeft == 2, "the obsidian left: an iron pick won't take it");
            helper.assertTrue(lootDiamonds == 0 && lootFlesh >= 5 && diamonds >= 3, "the old chest's diamonds taken, its rotten flesh left: +" + diamonds);
            helper.assertTrue(mineDiamonds == 3 && mineIngots == 1, "the player's chest untouched");
            helper.assertTrue(torches >= 1, "the cave lit: " + torches + " torches");
            helper.assertTrue(!zombie.isAlive(), "the zombie killed");
            helper.assertTrue(spawner, "the spawner left be");
            helper.assertTrue(report.contains("; cave ") && report.contains("; vein iron") && report.contains("; chest ") && report.contains("; dungeon ")
                && report.contains("; vein obsidian"), "the report has the cave, the veins, the dungeon and the chest: " + report);
            helper.succeed();
        });
    }

    // ============================================================ cd04: obsidian, with a diamond pick

    /** The same cave (no chests, no zombie), the stores' pick a diamond one: the obsidian comes out too, and home. */
    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "cd04_obsidian")
    public static void cd04_obsidian(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 926000;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(0);
        Cave c = cave(level, x + OUT, Z, false);
        fill(t, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.TORCH, 64),
            new ItemStack(Items.BREAD, 16), new ItemStack(Items.COBBLESTONE, 64));
        CaveDwellers.quickForTests(true);
        d.moveTo(c.mouth().getX() + 0.5, c.mouth().getY(), c.mouth().getZ() + 0.5, -90.0F, 0.0F);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        Kit.log("cd04 " + d.displayNameCap() + " sent " + sent + " with " + has(d, s -> s.is(Items.DIAMOND_PICKAXE)) + " diamond pick");
        helper.assertTrue(sent && has(d, s -> s.is(Items.DIAMOND_PICKAXE)) == 1, "out, with the stores' diamond pick");
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (helper.getTick() % 100 == 0) {
                    CaveDwellers.Delve dv = d.expedition().delve();
                    Kit.log("cd04 tick " + helper.getTick() + ": " + (dv == null ? "?" : dv.phase() + ", mined " + dv.mined()) + " at " + d.blockPosition().toShortString()
                        + "; " + d.hobbyNow());
                }
                if (d.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
                return;
            }
            done[0] = true;
            int obsidianLeft = 0;
            for (BlockPos p : c.obsidian()) if (level.getBlockState(p).is(Blocks.OBSIDIAN)) obsidianLeft++;
            int obsidian = stock(level, id, Items.OBSIDIAN);
            Kit.log("cd04 home at tick " + helper.getTick() + ": obsidian left " + obsidianLeft + "/2, " + obsidian + " in the stores; "
                + CaveDwellers.debug(id));
            helper.assertTrue(obsidianLeft == 0 && obsidian >= 2, "the obsidian mined with the diamond pick, and in the stores: " + obsidianLeft + " left, " + obsidian);
            helper.succeed();
        });
    }
}
