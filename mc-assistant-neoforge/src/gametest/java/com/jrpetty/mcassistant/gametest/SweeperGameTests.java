package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Phantoms;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Couriers;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Storehouses;
import com.jrpetty.mcassistant.entity.Storekeeping;
import com.jrpetty.mcassistant.entity.Sweepers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The street sweeper and the phantoms (entity/Sweepers, Phantoms): what lies about a town's streets is
 * swept up and carried into the storehouse, and booked there, while what a player threw is left where
 * it lies; a town of sixteen with a storehouse has one of its couriers take up the broom, never its
 * last; the broom leaves a worker's own drops to it a while, and anything indoors; and no phantom comes
 * over a village of itself, a stray one is seen off, and none ever goes for a folk.
 *
 * <p>Each on its own ground (x 230000 to 237000, z 50000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SweeperGameTests {

    private static final String EMPTY = "empty";

    /** A Village Storehouse (twenty-seven units, the door to the south) this far from the heart. */
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

    /** No old chests about (the founders' chest): a courier with no run has nothing to clear. */
    private static void noOldChests(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /** Set the clock to a time from which this folk is at its work for the next {@code span} ticks. */
    private static boolean atWorkFor(ServerLevel level, VillageFolkEntity f, long span) {
        for (long t = 1500; t + span <= 12000; t += 250) {
            boolean ok = true;
            for (long k = t; k <= t + span && ok; k += 200) {
                level.setDayTime(k);
                ok = !f.offWorkNow();
            }
            if (ok) {
                level.setDayTime(t);
                return true;
            }
        }
        return false;
    }

    /** A heap lying on the ground this far from the heart, at rest; thrown by {@code thrower}, if given. */
    private static ItemEntity lay(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack what, @Nullable Entity thrower) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        ItemEntity e = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5, what, 0.0, 0.0, 0.0);
        if (thrower != null) e.setThrower(thrower);
        level.addFreshEntity(e);
        return e;
    }

    /** As though this heap had lain this long. */
    private static void age(ItemEntity e, int ticks) {
        CompoundTag t = e.saveWithoutId(new CompoundTag());
        t.putShort("Age", (short) ticks);
        e.load(t);
    }

    private static int count(Container c, Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    // ============================================================ the streets swept

    /**
     * A courier with no run, in a town with no sweeper of its own, sweeps between runs: saplings, seed,
     * eggs, wool, cobblestone and bones lying about the streets are swept up, carried into the storehouse
     * and booked there as swept in; the emeralds a player threw are left where they lay, untouched.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "sw_sw01_sweeps_the_streets")
    public static void sw01_sweeps_the_streets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 230000, z = 50000;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(courier != null, "a village");
        UUID village = courier.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        noOldChests(level, village);
        courier.setJob(StationTask.HAUL);
        BlockPos spot = Storehouses.standingSpot(level, village);
        helper.assertTrue(spot != null && Couriers.employed(courier), "the courier is the storehouse's: " + spot);
        courier.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
        helper.assertTrue(atWorkFor(level, courier, 4400), "a time of day the courier is at work");
        helper.assertTrue(Sweepers.wanted(village) == 0 && !Sweepers.appointed(courier), "a town of one keeps no sweeper: its courier sweeps between runs");
        // What lies about the streets (away from the courier's door, so it does not walk over any by chance).
        List<ItemEntity> lying = new ArrayList<>();
        lying.add(lay(level, heart, -6, -3, new ItemStack(Items.OAK_SAPLING, 3), null));
        lying.add(lay(level, heart, -3, -9, new ItemStack(Items.WHEAT_SEEDS, 5), null));
        lying.add(lay(level, heart, 4, -7, new ItemStack(Items.EGG, 2), null));
        lying.add(lay(level, heart, -10, 4, new ItemStack(Items.WHITE_WOOL, 3), null));
        lying.add(lay(level, heart, -12, -12, new ItemStack(Items.COBBLESTONE, 7), null));
        lying.add(lay(level, heart, 2, -13, new ItemStack(Items.BONE, 2), null));
        // And a player's throw.
        Player thrower = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemEntity thrown = lay(level, heart, -5, 6, new ItemStack(Items.EMERALD, 4), thrower);
        helper.assertTrue(Sweepers.playersOwn(thrown), "the emeralds are a player's throw");
        Item[] kinds = { Items.OAK_SAPLING, Items.WHEAT_SEEDS, Items.EGG, Items.WHITE_WOOL, Items.COBBLESTONE, Items.BONE };
        int[] want = { 3, 5, 2, 3, 7, 2 };
        int[] before = new int[kinds.length];
        for (int i = 0; i < kinds.length; i++) before[i] = count(store, kinds[i]);
        helper.onEachTick(() -> {
            long t = helper.getTick();
            // Its daily break (a minute or two, three for an easygoing one) is skipped: this is the sweeping's pace.
            if (courier.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            Villages.noteAttempt(village, level.getGameTime());
            int left = 0;
            for (ItemEntity e : lying) if (e.isAlive()) left++;
            boolean in = true;
            StringBuilder have = new StringBuilder();
            for (int i = 0; i < kinds.length; i++) {
                int n = count(store, kinds[i]) - before[i];
                if (n < want[i]) in = false;
                have.append(kinds[i].getDescription().getString()).append(' ').append(n).append("/").append(want[i]).append("; ");
            }
            if (t % 200 == 0) {
                int[] town = Sweepers.townForTests(level, village);
                Kit.log("sw01 @" + t + " " + left + " heaps still lying; the storehouse: " + have + "town " + java.util.Arrays.toString(town)
                    + "; doing: " + Couriers.doing(courier) + " — " + courier.debugLine());
            }
            if (left == 0 && in) {
                int[] town = Sweepers.townForTests(level, village);
                int[] saplings = Storekeeping.itemForTests(level, village, new ItemStack(Items.OAK_SAPLING));
                Kit.log("sw01 the streets swept by tick " + t + ": " + have + "town " + java.util.Arrays.toString(town)
                    + "; the emeralds " + thrown.getItem() + " alive " + thrown.isAlive() + "; books saplings in " + saplings[0]);
                Kit.log("sw01 the books: " + Storekeeping.page(level, Villages.get(village)).replace('\n', '|'));
                // Left where they lay; or, once left lying two minutes, taken to the Lost and Found (PlayerServices),
                // which the next assertion holds to: never the stores, never the courier's pack.
                helper.assertTrue(thrown.isAlive() ? thrown.getItem().is(Items.EMERALD) && thrown.getItem().getCount() == 4 : t >= 2400,
                    "the player's emeralds left where they lay: " + thrown.getItem() + " alive " + thrown.isAlive() + " at " + t);
                helper.assertTrue(count(store, Items.EMERALD) == 0 && courier.countCarried(s -> s.is(Items.EMERALD)) == 0,
                    "and nobody took them in");
                helper.assertTrue(saplings[0] >= 3, "the saplings are in the storehouse's books: " + saplings[0]);
                helper.assertTrue(town[0] >= 1 && town[1] >= 1, "booked as swept in: " + java.util.Arrays.toString(town));
                helper.succeed();
            } else if (t >= 4600) {
                helper.fail("the streets were not swept: " + left + " heaps still lying, the storehouse " + have + " town "
                    + java.util.Arrays.toString(Sweepers.townForTests(level, village)) + "; doing " + Couriers.doing(courier)
                    + " — " + courier.debugLine());
            }
        });
    }

    // ============================================================ who sweeps

    /**
     * A town of seventeen with a storehouse keeps a sweeper (none before its storehouse stands): one of
     * its two couriers takes up the broom, the Stores page lists it as the storehouse's sweeper and its
     * card says so; and with the other courier gone to the fields, it puts the broom down again — the
     * storehouse never has only a sweeper for its runs.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sw_sw02_takes_up_the_broom")
    public static void sw02_takes_up_the_broom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 232000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID village = first.ownerId();
        helper.assertTrue(Sweepers.wanted(village) == 0, "one folk: no sweeper");
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(first);
        for (int i = 1; i < 17; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 8 + (i % 6) * 2, z - 8 + (i / 6) * 2), 0.0F);
            helper.assertTrue(f != null && village.equals(f.ownerId()), "folk " + i + " of the village");
            folk.add(f);
        }
        helper.assertTrue(Villages.headcount(village) >= 17, "seventeen folk: " + Villages.headcount(village));
        helper.assertTrue(Sweepers.wanted(village) == 0, "seventeen folk, but no storehouse yet: no sweeper");
        storehouse(helper, level, heart, 10, 10);
        noOldChests(level, village);
        helper.assertTrue(Sweepers.wanted(village) == 1, "a storehouse and seventeen folk: one sweeper: " + Sweepers.wanted(village));
        VillageFolkEntity a = folk.get(3), b = folk.get(4);
        for (VillageFolkEntity f : folk) if (f != a && f != b && f.stationTask() == StationTask.HAUL) f.setJob(StationTask.FARM);
        a.setJob(StationTask.HAUL);
        b.setJob(StationTask.HAUL);
        helper.assertTrue(Couriers.employed(a) && Couriers.employed(b), "two couriers of the storehouse");
        Sweepers.appointForTests(level, village);
        int n = (Sweepers.appointed(a) ? 1 : 0) + (Sweepers.appointed(b) ? 1 : 0);
        helper.assertTrue(n == 1, "one of the two couriers takes up the broom: " + n);
        VillageFolkEntity sweeper = Sweepers.appointed(a) ? a : b, other = sweeper == a ? b : a;
        CompoundTag books = Storekeeping.report(level, Villages.get(village));
        ListTag staff = books.getList("staff", Tag.TAG_COMPOUND);
        boolean listed = false;
        for (int i = 0; i < staff.size(); i++) {
            CompoundTag s = staff.getCompound(i);
            if (s.getString("name").equals(sweeper.displayNameCap()) && s.getString("role").equals("sweeper")) listed = true;
        }
        Kit.log("sw02 the sweeper " + sweeper.displayNameCap() + "; the staff " + staff + "; lying " + books.getInt("lying"));
        helper.assertTrue(listed, "the Stores page lists it as the storehouse's sweeper: " + staff);
        helper.assertTrue(books.contains("lying") && books.contains("swept"), "and the books what lies about the town");
        String card = FolkTalk.card(sweeper);
        helper.assertTrue(card.contains("Street sweeper") && card.contains("Sweeps|"), "its card says so: " + card.replace('\n', '|'));
        other.setJob(StationTask.FARM);
        Sweepers.appointForTests(level, village);
        helper.assertTrue(!Sweepers.appointed(sweeper), "with the other courier gone, the broom is put down: the runs come first");
        helper.succeed();
    }

    // ============================================================ what the broom leaves

    /**
     * What the broom may take and what it leaves: a heap in the open that has settled, yes; a sapling on
     * the woodcutter's own ground, not while it is fresh (the woodcutter sweeps up its own), but yes once
     * it has been left lying; a player's throw, never, however long it lies; one just dropped, not yet;
     * one indoors, under a roof, no. And a folk passing by leaves a player's throw for its owner.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sw_sw04_what_the_broom_leaves")
    public static void sw04_what_the_broom_leaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 236000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        UUID village = folk.ownerId();
        VillageFolkEntity woodcutter = VillageFolkSpawnerBlock.raise(level, heart.south(2), 0.0F);
        helper.assertTrue(woodcutter != null && village.equals(woodcutter.ownerId()), "a woodcutter");
        woodcutter.setJob(StationTask.WOOD);
        woodcutter.assignPlot(WorkZone.around(Kit.surface(level, x + 16, z), 4, WorkZone.DEFAULT_DEPTH), "Woods");
        helper.assertTrue(woodcutter.workZone() != null, "the woodcutter's ground inside the town");
        ItemEntity sapling = lay(level, heart, 16, 1, new ItemStack(Items.OAK_SAPLING, 2), null);
        ItemEntity cobble = lay(level, heart, -10, -6, new ItemStack(Items.COBBLESTONE, 4), null);
        ItemEntity thrown = lay(level, heart, -6, 9, new ItemStack(Items.EMERALD, 2), helper.makeMockPlayer(GameType.SURVIVAL));
        // A roof over one: indoors. (Laid on the ground first: laid after, it went on top of the roof.)
        BlockPos under = Kit.surface(level, x - 12, z + 9);
        ItemEntity indoors = lay(level, heart, -12, 9, new ItemStack(Items.BREAD, 3), null);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(under.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), 3);
        for (ItemEntity e : List.of(sapling, cobble, thrown, indoors)) age(e, 200);
        ItemEntity fresh = lay(level, heart, 6, -6, new ItemStack(Items.STRING, 2), null);
        boolean saplingYoung = Sweepers.mayTakeForTests(level, village, sapling);
        boolean cobbleOk = Sweepers.mayTakeForTests(level, village, cobble);
        boolean thrownOk = Sweepers.mayTakeForTests(level, village, thrown);
        boolean indoorsOk = Sweepers.mayTakeForTests(level, village, indoors);
        boolean freshOk = Sweepers.mayTakeForTests(level, village, fresh);
        age(sapling, 2600);
        age(thrown, 5000);
        boolean saplingLeft = Sweepers.mayTakeForTests(level, village, sapling);
        boolean thrownOld = Sweepers.mayTakeForTests(level, village, thrown);
        Kit.log("sw04 sapling young " + saplingYoung + ", left lying " + saplingLeft + "; cobble " + cobbleOk + "; thrown " + thrownOk + "/" + thrownOld
            + "; indoors " + indoorsOk + "; fresh " + freshOk);
        helper.assertTrue(cobbleOk, "a settled heap in the open, nobody's: the broom takes it");
        helper.assertTrue(!saplingYoung, "a fresh sapling on the woodcutter's ground is the woodcutter's to sweep up");
        helper.assertTrue(saplingLeft, "but one left lying there two minutes is the broom's");
        helper.assertTrue(!thrownOk && !thrownOld && Sweepers.playersOwn(thrown), "a player's throw, never, however long it lies");
        helper.assertTrue(!indoorsOk, "nothing indoors, under a roof");
        helper.assertTrue(!freshOk, "nothing just dropped (it may still be rolling, or its maker about to take it up)");
        helper.assertTrue(Sweepers.notForTheMagnet(folk, thrown) && !Sweepers.notForTheMagnet(folk, cobble),
            "a folk passing by leaves a player's throw for its owner, and picks up a stray cobble as ever");
        for (ItemEntity e : List.of(sapling, cobble, thrown, indoors, fresh)) e.discard();
        helper.succeed();
    }

    // ============================================================ no phantoms

    /**
     * No phantom comes over a village of itself: the game's phantom spawner asking for a player there is
     * told no, and one spawned there of itself never comes into the world; a stray that flies in from
     * outside is seen off in a puff of smoke; one a player brought (a spawn egg) is left alone — and it
     * never goes for a folk.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "sw_sw03_no_phantoms")
    public static void sw03_no_phantoms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(18000);                                             // night: no phantom burns in the sun
        int x = 234000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        int fx = x + 400;
        Kit.hold(level, fx, z, 16);
        Kit.prepare(level, fx, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        helper.assertTrue(Phantoms.keptOff(level, heart.getX(), heart.getZ()), "the sky over the village is kept clear");
        helper.assertTrue(!Phantoms.keptOff(level, fx, z), "but not four hundred blocks out");
        // The game's phantom spawner, for a player who has not slept standing in the square, and far out.
        Player sleepless = helper.makeMockPlayer(GameType.SURVIVAL);
        sleepless.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
        PlayerSpawnPhantomsEvent over = NeoForge.EVENT_BUS.post(new PlayerSpawnPhantomsEvent(sleepless, 3));
        sleepless.moveTo(fx + 0.5, heart.getY(), z + 0.5, 0.0F, 0.0F);
        PlayerSpawnPhantomsEvent away = NeoForge.EVENT_BUS.post(new PlayerSpawnPhantomsEvent(sleepless, 3));
        Kit.log("sw03 the phantom spawner over the village: " + over.getResult() + " " + over.getPhantomsToSpawn() + "; far out: "
            + away.getResult() + " " + away.getPhantomsToSpawn());
        helper.assertTrue(over.getResult() == PlayerSpawnPhantomsEvent.Result.DENY && over.getPhantomsToSpawn() == 0,
            "no phantoms sent after a player in the village");
        helper.assertTrue(away.getResult() != PlayerSpawnPhantomsEvent.Result.DENY, "out in the wild, as ever");
        // One spawned over the square of itself (as the phantom spawner makes one): never comes into the world.
        Phantom natural = EntityType.PHANTOM.create(level);
        helper.assertTrue(natural != null, "a phantom");
        natural.moveTo(heart.getX() + 0.5, heart.getY() + 24, heart.getZ() + 0.5, 0.0F, 0.0F);
        natural.finalizeSpawn(level, level.getCurrentDifficultyAt(heart), MobSpawnType.NATURAL, null);
        boolean came = level.addFreshEntity(natural);
        helper.assertTrue(!came && level.getEntity(natural.getUUID()) == null, "a phantom of itself over the village never comes");
        // One a player brought with a spawn egg: let be, and it never goes for a folk.
        Phantom pet = EntityType.PHANTOM.create(level);
        pet.moveTo(heart.getX() + 3.5, heart.getY() + 6, heart.getZ() + 3.5, 0.0F, 0.0F);
        pet.finalizeSpawn(level, level.getCurrentDifficultyAt(heart), MobSpawnType.SPAWN_EGG, null);
        pet.setNoAi(true);
        helper.assertTrue(level.addFreshEntity(pet), "a phantom from a spawn egg comes, over the village or not");
        pet.setTarget(folk);
        helper.assertTrue(pet.getTarget() == null, "and it never goes for a folk");
        // A stray from out in the wild that flies in over the town: seen off.
        Phantom stray = EntityType.PHANTOM.create(level);
        BlockPos out = Kit.surface(level, fx, z);
        stray.moveTo(out.getX() + 0.5, out.getY() + 20, out.getZ() + 0.5, 0.0F, 0.0F);
        stray.finalizeSpawn(level, level.getCurrentDifficultyAt(out), MobSpawnType.NATURAL, null);
        stray.setNoAi(true);
        helper.assertTrue(level.addFreshEntity(stray), "a phantom out in the wild comes as ever");
        stray.teleportTo(heart.getX() + 0.5, heart.getY() + 20, heart.getZ() + 0.5);
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (stray.isRemoved()) {
                Kit.log("sw03 the stray seen off by tick " + t + " (" + stray.getRemovalReason() + "); the spawn-egg phantom alive " + pet.isAlive());
                helper.assertTrue(stray.getRemovalReason() == Entity.RemovalReason.DISCARDED, "seen off, not killed: " + stray.getRemovalReason());
                helper.assertTrue(pet.isAlive() && !pet.isRemoved(), "the spawn-egg phantom left alone");
                pet.discard();
                helper.succeed();
            } else if (t >= 300) {
                pet.discard();
                stray.discard();
                helper.fail("the stray phantom over the village was not seen off");
            }
        });
    }
}
