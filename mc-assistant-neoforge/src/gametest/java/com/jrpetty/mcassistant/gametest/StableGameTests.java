package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.Couriers;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Riding;
import com.jrpetty.mcassistant.entity.Stables;
import com.jrpetty.mcassistant.entity.Storehouses;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Horses and the stable (entity/Stables, Riding): the rancher coaxes a wild horse home with wheat
 * out of the stores and gentles it until it is tamed, the village's own; a courier with a long run
 * takes the saddled horse from the stable, rides it out and back, and puts it back in its stall; a
 * caravan's donkey carries the load in its chest, and is led home to the stable after.
 *
 * <p>Each on its own ground (x 260000 to 267000, z 50000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class StableGameTests {

    private static final String EMPTY = "empty";

    /** The stable stamped on cleared, level ground this far from the heart, its door to the south, and booked as the village's. */
    private static Stables.Stable stable(GameTestHelper helper, ServerLevel level, UUID village, BlockPos heart, int dx, int dz) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-6, 0, -7), at.offset(6, 12, 9))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-6, -1, -7), at.offset(6, -1, 9))) {
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
        }
        BuildGoal.stamp(level, "stable", at, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(village, "stable", at, Direction.NORTH);
        Stables.Stable st = Stables.stable(village);
        helper.assertTrue(st != null && level.getBlockState(st.door()).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock,
            "the stable stands, gates across its door: " + level.getBlockState(at.south(4)));
        return st;
    }

    /** A chest of the village's stores this far from the heart. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos p = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, p);
        return (Container) level.getBlockEntity(p);
    }

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
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** No old chests about (the founders' chest): a courier with nothing to do has nothing to clear. */
    private static void noOldChests(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    private static int count(Container c, Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    /** A time of day from which this folk is at its work for the next {@code span} ticks (not on its break). */
    private static boolean atWorkFor(ServerLevel level, VillageFolkEntity f, long span) {
        for (long t = 1500; t + span <= 10000; t += 250) {
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

    /** The clock kept where this folk is at its work (its break and the evening skipped), unless it is seeing to a horse. */
    private static void keepAtWork(ServerLevel level, VillageFolkEntity f) {
        if (f.isSleeping() || Stables.busy(f)) return;
        if (level.getDayTime() % 24000L > 9500) level.setDayTime(level.getDayTime() - level.getDayTime() % 24000L + 1500);
        if (!f.offWorkNow()) return;
        long day = level.getDayTime() - level.getDayTime() % 24000L;
        for (long k = level.getDayTime() % 24000L; k <= 9500; k += 100) {
            level.setDayTime(day + k);
            if (!f.offWorkNow()) return;
        }
        level.setDayTime(day + 1500);
    }

    /** A horse (or a donkey) of the village's, tamed, standing in its stall. */
    private static <T extends AbstractHorse> T steed(ServerLevel level, EntityType<T> type, UUID village, Vec3 at, String name) {
        T h = type.create(level);
        h.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.SPAWN_EGG, null);
        h.setTamed(true);
        h.setOwnerUUID(village);
        h.addTag(Stables.STEED);
        h.setPersistenceRequired();
        h.getPersistentData().putString("mca_horse_name", name);
        level.addFreshEntity(h);
        return h;
    }

    // ============================================================ gentling

    /**
     * A wild horse out on the grass. The rancher draws wheat and apples from the stores, holds one out
     * and walks the horse home to the stable with it following the hand; there, a go at a time, it
     * feeds it and gets up on its back — thrown, and the horse a little calmer each time — until the
     * horse stands for it and is tamed: the village its owner, a name of its own, the stores' food
     * spent on it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "hs01_rancher_tames")
    public static void hs01_rancher_tames(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Stables.quickForTests(true);
        level.setDayTime(1500);
        int x = 260000, z = 50000;
        Kit.hold(level, x, z, 96);
        Kit.prepare(level, x, z, 96);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity rancher = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(rancher != null, "a village");
        UUID village = rancher.ownerId();
        rancher.setJob(StationTask.RANCH);
        rancher.assignPlot(WorkZone.around(Kit.surface(level, x - 24, z), 4, WorkZone.DEFAULT_DEPTH), "The Ranch");
        Stables.Stable st = stable(helper, level, village, heart, 16, -2);
        Container box = stores(level, heart, 3, 3);
        box.setItem(0, new ItemStack(Items.WHEAT, 40));
        box.setItem(1, new ItemStack(Items.APPLE, 8));
        int food0 = Market.stock(level, village, s -> s.is(Items.WHEAT) || s.is(Items.APPLE) || s.is(Items.SUGAR) || s.is(Items.GOLDEN_CARROT));
        BlockPos wildAt = Kit.surface(level, x + 44, z + 22);
        Horse wild = EntityType.HORSE.create(level);
        wild.moveTo(wildAt.getX() + 0.5, wildAt.getY(), wildAt.getZ() + 0.5, 0.0F, 0.0F);
        wild.finalizeSpawn(level, level.getCurrentDifficultyAt(wildAt), MobSpawnType.NATURAL, null);
        level.addFreshEntity(wild);
        helper.assertTrue(!wild.isTamed() && wild.getOwnerUUID() == null && Stables.villageOf(wild) == null, "a wild horse, nobody's");
        Kit.log("hs01 the stable at " + st.anchor().toShortString() + ", a wild " + wild.getVariant() + " horse at " + wildAt.toShortString()
            + "; the stores' horse food " + food0);
        final boolean[] caught = { false };
        final int[] goes = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000L > 8000) level.setDayTime(1500);           // a long day of it
            Villages.noteAttempt(village, level.getGameTime());
            String chore = Stables.choreForTests(rancher);
            if (chore == null && !Stables.busy(rancher) && t % 20 == 0) Stables.ranchForTests(rancher, level);
            if (wild.getTags().contains(Stables.CATCH)) caught[0] = true;
            goes[0] = wild.getPersistentData().getInt("mca_goes");
            if (t % 200 == 0) {
                Kit.log("hs01 @" + t + " chore " + chore + "; the horse at " + wild.blockPosition().toShortString() + " ("
                    + Math.round(Math.sqrt(wild.distanceToSqr(Vec3.atCenterOf(st.aisle())))) + " from the aisle), caught " + caught[0]
                    + ", temper " + wild.getTemper() + "/" + wild.getMaxTemper() + " after " + goes[0] + " goes, tamed " + wild.isTamed()
                    + "; " + Stables.doing(rancher) + " — " + rancher.debugLine());
            }
            if (!wild.isAlive()) helper.fail("the horse died");
            if (wild.isTamed()) {
                int food = Market.stock(level, village, s -> s.is(Items.WHEAT) || s.is(Items.APPLE) || s.is(Items.SUGAR) || s.is(Items.GOLDEN_CARROT))
                    + rancher.countCarried(s -> s.is(Items.WHEAT) || s.is(Items.APPLE) || s.is(Items.SUGAR) || s.is(Items.GOLDEN_CARROT));
                String name = Stables.name(wild);
                Kit.log("hs01 tamed by tick " + t + " after " + goes[0] + " goes: " + name + ", owner " + wild.getOwnerUUID()
                    + "; the horse food left " + food + " of " + food0 + "; the card: " + Stables.card(rancher));
                helper.assertTrue(caught[0], "it was brought home as the village's catch first");
                helper.assertTrue(village.equals(wild.getOwnerUUID()) && Stables.villageOf(wild) != null, "the village is its owner");
                helper.assertTrue(!name.startsWith("the "), "and it has a name of its own: " + name);
                helper.assertTrue(food < food0, "the food it was coaxed and gentled with came out of the stores: " + food + " of " + food0);
                helper.assertTrue(st.inside(wild.position()) || Math.sqrt(wild.distanceToSqr(Vec3.atCenterOf(st.aisle()))) < 8,
                    "it is home at the stable");
                helper.assertTrue(goes[0] >= 1, "it took at least one go on its back");
                helper.succeed();
            } else if (t >= 8800) {
                helper.fail("the horse was not tamed: caught " + caught[0] + ", temper " + wild.getTemper() + " after " + goes[0]
                    + " goes — " + rancher.debugLine());
            }
        });
    }

    // ============================================================ a courier on horseback

    /**
     * A farmer's chest seventy-odd blocks out fills up: the courier with the run takes the saddled
     * horse from the stable, rides it out (quicker than it could run), gets down by the chest and
     * empties it, rides back to the storehouse with the harvest, carries it in, and with no other
     * long run to make rides the horse home and puts it back in its stall.
     */
    @GameTest(template = EMPTY, timeoutTicks = 7000, batch = "hs02_courier_rides")
    public static void hs02_courier_rides(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Riding.parkedForTests(100);
        level.setDayTime(2000);
        int x = 262500, z = 50000;
        Kit.hold(level, x, z, 112);
        Kit.prepare(level, x, z, 112);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(farmer != null, "a village");
        UUID village = farmer.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, -10, 8);
        helper.assertTrue(Storehouses.stands(village), "the village knows its storehouse");
        noOldChests(level, village);
        Stables.Stable st = stable(helper, level, village, heart, 14, 0);
        Horse bay = steed(level, EntityType.HORSE, village, st.stall(0), "Bay");
        bay.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.27);
        bay.equipSaddle(new ItemStack(Items.SADDLE), null);
        helper.assertTrue(bay.isSaddled() && Stables.villageOf(bay) != null, "a saddled horse of the village's in its stall");
        BlockPos site = Kit.surface(level, heart.getX() + 78, heart.getZ() + 6);
        farmer.setJob(StationTask.FARM);
        farmer.assignPlot(WorkZone.around(site, 4, WorkZone.DEFAULT_DEPTH), "Farm");
        farmer.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.0F, 0.0F);
        BlockPos chest = farmer.productionChestForTests();
        helper.assertTrue(chest != null, "the farmer's production chest");
        Container box = (Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.WHEAT, 24));
        box.setItem(5, new ItemStack(Items.WHEAT, 24));
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, heart.getX() - 5, heart.getZ() + 15), 0.0F);
        helper.assertTrue(courier != null && village.equals(courier.ownerId()), "a courier for the village");
        courier.setJob(StationTask.HAUL);
        helper.assertTrue(Couriers.employed(courier), "the courier is the storehouse's");
        atWorkFor(level, courier, 3000);                           // (and kept at work below, its break skipped)
        Kit.log("hs02 the storehouse by the heart, the stable at " + st.anchor().toShortString() + ", the farmer's chest at "
            + chest.toShortString() + " (" + Math.round(Math.sqrt(chest.distSqr(heart))) + " out); Bay's pace "
            + String.format(java.util.Locale.ROOT, "%.2f", Riding.quick(bay)) + "× a folk's run");
        String at = chest.toShortString();
        final boolean[] took = { false }, rode = { false }, hitched = { false };
        final double[] ridden = { 0, 0 };                         // blocks ridden, the best pace seen (blocks a second)
        final Vec3[] last = { bay.position() };
        final String[] card = { "" };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            keepAtWork(level, courier);
            String run = Couriers.runOfForTests(courier);
            if (run != null && run.startsWith("CHEST:" + at)) took[0] = true;
            String phase = Riding.phaseForTests(courier);
            if (courier.getVehicle() == bay) {
                rode[0] = true;
                double dx = bay.getX() - last[0].x, dz = bay.getZ() - last[0].z;
                double step = Math.sqrt(dx * dx + dz * dz);
                if (step < 3) {
                    ridden[0] += step;
                    ridden[1] = Math.max(ridden[1], step * 20);
                }
                String doing = Stables.doing(courier);
                if (doing != null && doing.startsWith("Riding Bay to the")) card[0] = doing;
            }
            last[0] = bay.position();
            if ("HITCHED".equals(phase)) hitched[0] = true;
            int wheat = count(store, Items.WHEAT);
            if (t % 200 == 0) {
                Kit.log("hs02 @" + t + " run " + run + "; ride " + phase + ", on Bay " + (courier.getVehicle() == bay) + ", ridden "
                    + Math.round(ridden[0]) + " blocks (best " + String.format(java.util.Locale.ROOT, "%.1f", ridden[1]) + " b/s); Bay at "
                    + bay.blockPosition().toShortString() + "; storehouse wheat " + wheat + "; " + Stables.doing(courier) + " — " + courier.debugLine());
            }
            boolean inStall = !bay.isVehicle() && Math.sqrt(bay.distanceToSqr(st.stall(0))) <= 1.6 && st.inside(bay.position());
            if (took[0] && rode[0] && wheat >= 40 && phase == null && inStall) {
                Kit.log("hs02 done by tick " + t + ": ridden " + Math.round(ridden[0]) + " blocks, best pace "
                    + String.format(java.util.Locale.ROOT, "%.1f", ridden[1]) + " b/s; the card said \"" + card[0] + "\"; wheat in "
                    + wheat + "; Bay back in its stall at " + bay.blockPosition().toShortString());
                helper.assertTrue(ridden[0] >= 60, "the courier rode the long way out and back, not a step or two: " + Math.round(ridden[0]));
                helper.assertTrue(ridden[1] >= 10.0, "and quicker than a folk runs (about nine blocks a second): "
                    + String.format(java.util.Locale.ROOT, "%.1f", ridden[1]));
                helper.assertTrue(hitched[0], "it got down at the far end and did the business on foot");
                helper.assertTrue(card[0].startsWith("Riding Bay to the east fields"), "its card said where it was riding: " + card[0]);
                helper.assertTrue(bay.isSaddled() && Stables.villageOf(bay) != null, "Bay is still the village's, saddle and all");
                helper.succeed();
            } else if (t >= 6800) {
                helper.fail("the courier did not ride the run and put the horse away: took " + took[0] + ", rode " + rode[0] + " ("
                    + Math.round(ridden[0]) + " blocks), wheat " + wheat + ", ride " + phase + ", Bay in its stall " + inStall
                    + " — " + courier.debugLine());
            }
        });
    }

    // ============================================================ a caravan's donkey

    /**
     * A caravan to the colony with the mother village's spare bread: the carrier fetches the donkey
     * with a chest on it from the stable, ties it with a lead from the stores and puts the bread in
     * its chest; the colony buys the bread out of the chest; home again, the carrier leads the donkey
     * back to the stable, lets it off the lead and hangs the lead up with the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "hs03_caravan_donkey")
    public static void hs03_caravan_donkey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int ax = 265000, az = 50000, bx = 265220;
        for (int x = ax - 48; x <= bx + 48; x += 48) {
            Kit.hold(level, x, az, 40);
            Kit.prepare(level, x, az, 40);
        }
        BlockPos a = Kit.surface(level, ax, az), b = Kit.surface(level, bx, az);
        VillageFolkEntity m1 = VillageFolkSpawnerBlock.raise(level, a, 0.0F);
        VillageFolkEntity m2 = VillageFolkSpawnerBlock.raise(level, a.east(), 0.0F);
        VillageFolkEntity c1 = VillageFolkSpawnerBlock.raise(level, b, 0.0F);
        helper.assertTrue(m1 != null && m2 != null && c1 != null, "a village of two and a colony of one");
        Villages.Village mother = Villages.get(m1.ownerId()), colony = Villages.get(c1.ownerId());
        helper.assertTrue(mother != null && colony != null && !mother.id().equals(colony.id()), "two villages");
        Ledger.link(mother.id(), colony.id());
        Container box = stores(level, a, 4, 4);
        box.setItem(0, new ItemStack(Items.BREAD, 64));
        box.setItem(1, new ItemStack(Items.BREAD, 64));
        box.setItem(2, new ItemStack(Items.LEAD, 1));
        Stables.Stable st = stable(helper, level, mother.id(), a, -18, 0);
        Donkey ned = steed(level, EntityType.DONKEY, mother.id(), st.stall(1), "Ned");
        ned.getSlot(499).set(new ItemStack(Items.CHEST));
        helper.assertTrue(ned.hasChest(), "a donkey of the village's with a chest on it, in its stall");
        Ledger.addCoins(colony.id(), 60);
        int colonyBread0 = Market.stock(level, colony.id(), s -> s.is(Items.BREAD));
        boolean out = Caravans.setOut(level, mother, colony);
        VillageFolkEntity carrier = m1.trip() != null ? m1 : m2;
        helper.assertTrue(out && carrier.trip() != null, "a caravan sets out");
        helper.assertTrue(ned.getUUID().equals(Riding.packForTests(carrier)), "with Ned to carry the load");
        Kit.log("hs03 the caravan: " + carrier.displayNameCap() + " with " + carrier.countCarried(s -> s.is(Items.BREAD))
            + " bread on its back, to fetch Ned from the stable at " + st.anchor().toShortString());
        final int[] stage = { 0 };
        final int[] inChest = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(mother.id(), level.getGameTime());
            int bread = 0;
            for (int i = AbstractHorse.INV_BASE_COUNT; i < ned.getInventory().getContainerSize(); i++) {
                if (ned.getInventory().getItem(i).is(Items.BREAD)) bread += ned.getInventory().getItem(i).getCount();
            }
            if (t % 100 == 0) {
                Kit.log("hs03 @" + t + " stage " + stage[0] + "; pack " + Riding.packStageForTests(carrier) + "; Ned at "
                    + ned.blockPosition().toShortString() + " leashed " + ned.isLeashed() + ", bread in its chest " + bread
                    + "; the carrier's bread " + carrier.countCarried(s -> s.is(Items.BREAD)) + " — " + Stables.doing(carrier));
            }
            if (stage[0] == 0) {
                // Tied, loaded, and out of the stable on the road with the caravan.
                boolean onTheRoad = Math.sqrt(ned.distanceToSqr(Vec3.atBottomCenterOf(st.aisle()))) > 12;
                if (ned.isLeashed() && ned.getLeashHolder() == carrier && bread >= 32 && onTheRoad) {
                    inChest[0] = bread;
                    int onBack = carrier.countCarried(s -> s.is(Items.BREAD));
                    Kit.log("hs03 Ned is tied and loaded by tick " + t + ": " + bread + " bread in its chest, " + onBack + " on the carrier");
                    helper.assertTrue(onBack <= 8, "the load is in the donkey's chest, not on the carrier's back: " + onBack);
                    // At the colony: the colony buys the bread out of the chest.
                    Caravans.arriveForTests(level, carrier);
                    int colonyBread = Market.stock(level, colony.id(), s -> s.is(Items.BREAD));
                    int left = 0;
                    for (int i = AbstractHorse.INV_BASE_COUNT; i < ned.getInventory().getContainerSize(); i++) {
                        if (ned.getInventory().getItem(i).is(Items.BREAD)) left += ned.getInventory().getItem(i).getCount();
                    }
                    Kit.log("hs03 at the colony: its bread " + colonyBread0 + " -> " + colonyBread + "; left in Ned's chest " + left);
                    helper.assertTrue(colonyBread >= colonyBread0 + 32, "the colony bought the bread out of the donkey's chest");
                    helper.assertTrue(left < inChest[0], "and the chest is lighter for it: " + left);
                    helper.assertTrue(carrier.trip() != null && carrier.trip().homeward(), "and the caravan turns for home");
                    Caravans.arriveForTests(level, carrier);
                    helper.assertTrue(carrier.trip() == null, "home again");
                    stage[0] = 1;
                } else if (t >= 1600) {
                    helper.fail("the carrier never set off with the donkey tied and loaded: pack " + Riding.packStageForTests(carrier)
                        + ", bread in its chest " + bread + " — " + carrier.debugLine());
                }
                return;
            }
            boolean home = !ned.isLeashed() && Riding.packForTests(carrier) == null
                && Math.sqrt(ned.distanceToSqr(Vec3.atBottomCenterOf(st.aisle()))) < 5;
            if (home) {
                int leads = Market.stock(level, mother.id(), s -> s.is(Items.LEAD));
                Kit.log("hs03 Ned home in the stable by tick " + t + ", off the lead; leads in the stores " + leads);
                helper.assertTrue(leads >= 1, "the lead is hung up with the stores again");
                helper.succeed();
            } else if (t >= 2800) {
                helper.fail("the carrier did not lead the donkey home: pack " + Riding.packStageForTests(carrier) + ", Ned at "
                    + ned.blockPosition().toShortString() + " leashed " + ned.isLeashed() + " — " + carrier.debugLine());
            }
        });
    }
}
