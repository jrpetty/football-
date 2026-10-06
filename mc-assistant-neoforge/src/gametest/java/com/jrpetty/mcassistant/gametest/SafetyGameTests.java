package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Drover;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Larder;
import com.jrpetty.mcassistant.entity.Storehouses;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.FishGoal;
import com.jrpetty.mcassistant.entity.goal.MineGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Safety and food from every trade: what a four-hundred-day mountain town of seventy-seven showed. Its books
 * read "402 from the fields, 0 fish (1 fisher), 0 from the hunt (2 hunters), 0 from the pen (2 ranchers)",
 * two of its folk died in a fall and one in lava, and one morning its stores read a seventh of what they
 * held the day before and after.
 *
 * <ul>
 * <li><b>sf01</b>: a fisher on a lake under ice, with no open water anywhere the town can walk to, gives up
 *     the trade and the village wants no fisher for now; with open water about the town, a fisher on the ice
 *     finds it and moves there; and its catch goes home (none kept back as rations) and is booked as fish.</li>
 * <li><b>sf02</b>: a hunter with beef and pork in its pack (and its bread) banks every bit of the meat, keeps
 *     its bread, and the meat is booked "from the hunt".</li>
 * <li><b>sf03</b>: a rancher with seven sheep and two cows in its pen culls sheep down to four, never under,
 *     leaves the cows' pair alone, and brings the mutton home, booked "from the pen".</li>
 * <li><b>sf04</b>: a village folk plans no drop that hurts even in a fight, none over three at a building; a
 *     walk down off a four-block ledge goes round by the steps; shoved toward the edge of a tower eight high
 *     it stops at the edge; and a blow near the edge does not throw it off.</li>
 * <li><b>sf05</b>: lava a miner uncovers is sealed with cobblestone, never with its planks; with nothing that
 *     will not burn, it is not sealed with what will; a folk on fire with water three steps off goes to it.</li>
 * <li><b>sf06</b>: a count of the stores over thirty-two blocks does not stand for the next count over the
 *     town's whole reach; a morning's count that has lost three quarters while the storehouse stands is not
 *     believed for the day (a second such morning is); the goods waiting in a storehouse come apart are still
 *     counted.</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 400,000 to 406,000, z 50,000).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SafetyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** Open water: a square pond two deep, its top at the ground. */
    private static void pond(ServerLevel level, BlockPos centre, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos top = Kit.surface(level, centre.getX() + dx, centre.getZ() + dz).below();
                level.setBlock(top, Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(top.below(), Blocks.WATER.defaultBlockState(), 3);
            }
        }
    }

    /** A pond under ice: the ice where the grass was, the water under it. */
    private static void frozenPond(ServerLevel level, BlockPos centre, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos top = Kit.surface(level, centre.getX() + dx, centre.getZ() + dz).below();
                level.setBlock(top.below(), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(top, Blocks.ICE.defaultBlockState(), 3);
            }
        }
    }

    private static void noAnimals(ServerLevel level, BlockPos around, int r) {
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(around).inflate(r, 32, r))) a.discard();
    }

    /** How much of this the chests round here hold (not the furnaces). */
    private static int inChests(ServerLevel level, int cx, int cz, int r, Predicate<ItemStack> what) {
        int n = 0;
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (var be : new ArrayList<>(level.getChunk(chunkX, chunkZ).getBlockEntities().values())) {
                    if (!(be instanceof Container c) || be instanceof AbstractFurnaceBlockEntity) continue;
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack s = c.getItem(i);
                        if (!s.isEmpty() && what.test(s)) n += s.getCount();
                    }
                }
            }
        }
        return n;
    }

    /**
     * Keep the clock in the working afternoon: past the morning's business and the midday put-away (to 9000),
     * before the dusk put-away and home (from 11000), and round the folk's break.
     */
    private static void afternoon(ServerLevel level, VillageFolkEntity f, long base) {
        long tod = level.getDayTime() % 24000L;
        if (tod > 10800 || tod < 9100) level.setDayTime(base);
        if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
    }

    // ============================================================ sf01: the fisher's water

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sf01_fisher_water")
    public static void sf01_fisher_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long base = 24000L * 3 + 9300;
        level.setDayTime(base);
        final int x = 400000;
        Kit.hold(level, x, Z, 112);
        Kit.prepare(level, x, Z, 112);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity fisher = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(fisher != null && fisher.ownerId() != null, "a village");
        final UUID village = fisher.ownerId();
        // Its water: a lake under ice, and nothing else for a hundred blocks.
        BlockPos ice = Kit.surface(level, x + 20, Z);
        frozenPond(level, ice, 2);
        final WorkZone iceZone = WorkZone.around(ice, 6, WorkZone.DEFAULT_DEPTH);
        fisher.assignPlot(iceZone, "the ice");
        fisher.setJob(StationTask.FISH);
        fisher.getInventoryItems().clear();
        fisher.insertItem(new ItemStack(Items.FISHING_ROD));
        fisher.insertItem(new ItemStack(Items.BREAD, 8));
        helper.runAtTickTime(10, () -> {
            // 1. No open water within reach of the town: the fisher gives the trade up, and no fisher is wanted.
            boolean throughIce = FishGoal.waterFor(fisher) != null;
            fisher.fishWorkForTests(false);
            StationTask now = fisher.stationTask();
            boolean dry = Villages.dryForFishers(village);
            double share = Villages.share(village, StationTask.FISH);
            StationTask wanted = Villages.needed(village);
            Kit.log("sf01 on the ice: a line through it " + throughIce + "; now " + now + "; dry " + dry + ", the fishers' share "
                + share + ", the trade wanted next " + wanted + " — " + fisher.debugLine());
            helper.assertTrue(!throughIce, "no line is cast through ice");
            helper.assertTrue(now != StationTask.FISH, "a fisher with no water within reach takes up another trade: " + now);
            helper.assertTrue(dry && share == 0.0 && wanted != StationTask.FISH,
                "and the village wants no fisher for now: dry " + dry + ", share " + share + ", next " + wanted);

            // 2. Open water round the town, one on every bearing: put back on the ice, it finds it.
            for (int b = 0; b < 8; b++) {
                int dx = b % 2 == 0 ? (int) Math.round(Math.cos(b * Math.PI / 4) * 62) : (int) Math.signum(Math.cos(b * Math.PI / 4)) * 55;
                int dz = b % 2 == 0 ? (int) Math.round(Math.sin(b * Math.PI / 4) * 62) : (int) Math.signum(Math.sin(b * Math.PI / 4)) * 55;
                pond(level, Kit.surface(level, x + dx, Z + dz), 2);
            }
            fisher.assignPlot(iceZone, "the ice");
            fisher.setJob(StationTask.FISH);
            fisher.fishWorkForTests(false);
            WorkZone moved = fisher.workZone();
            BlockPos water = FishGoal.waterFor(fisher);
            Kit.log("sf01 with ponds about: " + fisher.stationTask() + " at " + (moved == null ? "nowhere" : moved.center().toShortString())
                + ", open water " + (water == null ? "none" : water.toShortString()) + " — " + fisher.debugLine());
            helper.assertTrue(fisher.stationTask() == StationTask.FISH && moved != null && !moved.center().equals(iceZone.center()),
                "the fisher left the ice for other water: " + fisher.stationTask() + " at " + (moved == null ? null : moved.center()));
            helper.assertTrue(water != null, "and there is open water on its new ground");

            // 3. The catch goes home, and is booked as fish.
            int keepCod = fisher.depositReserve(new ItemStack(Items.COD, 6));
            int keepBread = fisher.depositReserve(new ItemStack(Items.BREAD, 8));
            double before = Larder.todayIn(village)[1];
            Economy.produced(fisher, new ItemStack(Items.COD, 6));
            double fish = Larder.todayIn(village)[1] - before;
            Kit.log("sf01 the catch: keeps " + keepCod + " of six cod and " + keepBread + " of eight loaves; six cod banked booked "
                + fish + " fish; " + Larder.todayLine(village));
            helper.assertTrue(keepCod == 0, "no fish kept back as rations: " + keepCod);
            helper.assertTrue(keepBread > 0, "its bread is its rations: " + keepBread);
            helper.assertTrue(fish >= 6.0, "the catch is booked as fish: " + fish);
            helper.succeed();
        });
    }

    // ============================================================ sf02: the hunter's meat

    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "sf02_hunter_meat")
    public static void sf02_hunter_meat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long base = 24000L * 3 + 9300;
        level.setDayTime(base);
        final int x = 400600;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity hunter = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hunter != null && hunter.ownerId() != null, "a village");
        final UUID village = hunter.ownerId();
        noAnimals(level, heart, 70);
        final BlockPos chest = Kit.surface(level, x + 8, Z);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        hunter.assignPlot(WorkZone.around(Kit.surface(level, x + 24, Z), 20, WorkZone.DEFAULT_DEPTH), "Hunting Grounds");
        hunter.setJob(StationTask.HUNT);
        hunter.getInventoryItems().clear();
        hunter.insertItem(new ItemStack(Items.IRON_SWORD));
        hunter.insertItem(new ItemStack(Items.BREAD, 6));
        hunter.insertItem(new ItemStack(Items.BEEF, 5));
        hunter.insertItem(new ItemStack(Items.PORKCHOP, 3));
        hunter.insertItem(new ItemStack(Items.LEATHER, 2));
        // Whole: one raised two hearts short of its most ate a beef to heal, and seven of the eight reached the chest.
        hunter.setHealth(hunter.getMaxHealth());
        hunter.moveTo(chest.getX() + 2.5, chest.getY(), chest.getZ() + 0.5, 0.0F, 0.0F);
        int keepBeef = hunter.depositReserve(new ItemStack(Items.BEEF, 5));
        int keepPork = hunter.depositReserve(new ItemStack(Items.PORKCHOP, 3));
        int keepBread = hunter.depositReserve(new ItemStack(Items.BREAD, 6));
        Kit.log("sf02 a hunter keeps " + keepBeef + " of five beef, " + keepPork + " of three pork, " + keepBread + " of six loaves");
        helper.assertTrue(keepBeef == 0 && keepPork == 0, "the hunt's meat is not kept back as rations: " + keepBeef + ", " + keepPork);
        helper.assertTrue(keepBread > 0, "its bread is: " + keepBread);
        Predicate<ItemStack> meat = st -> st.is(Items.BEEF) || st.is(Items.PORKCHOP);
        final double[] booked0 = { Larder.todayIn(village)[2] };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            afternoon(level, hunter, base);
            if (t == 20) {
                hunter.clearQueue();
                hunter.enqueue(Job.depositAt(chest));
            }
            if (t < 20 || t % 20 != 0) return;
            int banked = inChests(level, x, Z, 48, meat);
            double booked = Larder.todayIn(village)[2] - booked0[0];
            if (t % 200 == 0) Kit.log("sf02 @" + t + ": " + banked + " meat in the chests, " + booked + " booked from the hunt — " + hunter.debugLine());
            if (banked >= 8) {
                int left = hunter.countCarried(meat), bread = hunter.countCarried(st -> st.is(Items.BREAD));
                Kit.log("sf02 banked at " + t + ": " + banked + " meat; " + booked + " booked from the hunt; left in the pack " + left
                    + " meat, " + bread + " bread; " + Larder.todayLine(village));
                helper.assertTrue(left == 0, "none of the meat stays in the pack: " + left);
                helper.assertTrue(bread >= 4, "its bread does: " + bread);
                helper.assertTrue(booked >= 8.0, "the meat is booked from the hunt: " + booked);
                helper.succeed();
            } else if (t >= 1300) {
                helper.fail("the hunter's meat never reached the stores: " + banked + " banked, " + hunter.countCarried(meat)
                    + " in its pack — " + hunter.debugLine());
            }
        });
    }

    // ============================================================ sf03: the pen's larder

    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "sf03_rancher_culls")
    public static void sf03_rancher_culls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long base = 24000L * 3 + 9300;
        level.setDayTime(base);
        final int x = 401200;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity rancher = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(rancher != null && rancher.ownerId() != null, "a village");
        final UUID village = rancher.ownerId();
        final BlockPos pen = Kit.surface(level, x + 16, Z);
        noAnimals(level, pen, 70);
        // A fence round it, so nothing runs off while it is chased.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 5) continue;
                level.setBlock(Kit.surface(level, pen.getX() + dx, pen.getZ() + dz), Blocks.OAK_FENCE.defaultBlockState(), 3);
            }
        }
        rancher.assignPlot(WorkZone.around(pen, 6, WorkZone.DEFAULT_DEPTH), "the pen");
        rancher.setJob(StationTask.RANCH);
        rancher.getInventoryItems().clear();
        rancher.insertItem(new ItemStack(Items.IRON_SWORD));
        rancher.insertItem(new ItemStack(Items.BREAD, 6));
        rancher.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
        for (int i = 0; i < 9; i++) {
            Animal a = i < 7 ? EntityType.SHEEP.create(level) : EntityType.COW.create(level);
            if (a == null) continue;
            a.moveTo(pen.getX() + 0.5 + (i % 3) * 2 - 2, pen.getY(), pen.getZ() + 0.5 + (i / 3) * 2 - 2, 0.0F, 0.0F);
            a.setPersistenceRequired();
            a.addTag(Drover.HERD);
            level.addFreshEntity(a);
        }
        Predicate<ItemStack> mutton = st -> st.is(Items.MUTTON) || st.is(Items.COOKED_MUTTON);
        final boolean[] counted = { false };
        final int[] least = { Integer.MAX_VALUE };
        final long[] fourSince = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            afternoon(level, rancher, base);
            if (t % 20 != 0) return;
            AABB around = new AABB(pen).inflate(8, 6, 8);
            int sheep = level.getEntitiesOfClass(Sheep.class, around, s -> s.isAlive() && !s.isBaby()).size();
            int cows = level.getEntitiesOfClass(Cow.class, around, c -> c.isAlive() && !c.isBaby()).size();
            if (!counted[0]) {
                if (sheep >= 7 && cows >= 2) counted[0] = true;
                else if (t >= 400) helper.fail("the herd never came into the world: sheep " + sheep + ", cows " + cows);
                if (!counted[0]) return;
            }
            least[0] = Math.min(least[0], sheep);
            if (rancher.peekJob() == null) rancher.cullForTests();
            int carried = rancher.countCarried(mutton) + inChests(level, x, Z, 48, mutton);
            if (t % 200 == 0) Kit.log("sf03 @" + t + ": sheep " + sheep + ", cows " + cows + ", mutton " + carried + " — " + rancher.debugLine());
            if (sheep < 4) {
                helper.fail("the rancher culled below the herd it keeps: " + sheep + " sheep");
                return;
            }
            if (cows != 2) {
                helper.fail("the cows' breeding pair was touched: " + cows + " cows");
                return;
            }
            if (sheep == 4 && rancher.peekJob() == null) {
                if (fourSince[0] < 0) fourSince[0] = t;
                if (t - fourSince[0] < 200) return;
                int keep = rancher.depositReserve(new ItemStack(Items.MUTTON, Math.max(1, carried)));
                double before = Larder.todayIn(village)[3];
                Economy.produced(rancher, new ItemStack(Items.MUTTON, Math.max(1, carried)));
                double pen0 = Larder.todayIn(village)[3] - before;
                Kit.log("sf03 culled to four at " + t + ": sheep " + sheep + " (least " + least[0] + "), cows " + cows + ", mutton " + carried
                    + ", kept back " + keep + ", booked from the pen " + pen0 + "; " + Larder.todayLine(village));
                helper.assertTrue(carried >= 3, "three sheep culled bring mutton home: " + carried);
                helper.assertTrue(keep == 0, "the pen's meat is not kept back as rations: " + keep);
                helper.assertTrue(pen0 >= carried, "the mutton is booked from the pen: " + pen0);
                helper.succeed();
            } else {
                fourSince[0] = -1;
            }
            if (t >= 3100) helper.fail("the rancher never culled down to four: " + sheep + " sheep — " + rancher.debugLine());
        });
    }

    // ============================================================ sf04: falls

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "sf04_falls")
    public static void sf04_falls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long base = 24000L * 3 + 9300;
        level.setDayTime(base);
        final int x = 401800;
        Kit.hold(level, x, Z, 100);
        Kit.prepare(level, x, Z, 100);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null && folk.ownerId() != null, "a village");
        final int y0 = heart.getY();

        // A ledge four high, fourteen long, with steps down at its east end.
        for (int xx = x + 34; xx <= x + 46; xx++) {
            for (int zz = Z - 1; zz <= Z + 1; zz++) {
                for (int yy = y0; yy <= y0 + 3; yy++) level.setBlock(new BlockPos(xx, yy, zz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        for (int s = 0; s < 3; s++) {
            for (int zz = Z - 1; zz <= Z + 1; zz++) {
                for (int yy = y0; yy <= y0 + 2 - s; yy++) level.setBlock(new BlockPos(x + 47 + s, yy, zz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // A tower eight high, five across.
        for (int xx = x + 68; xx <= x + 72; xx++) {
            for (int zz = Z - 2; zz <= Z + 2; zz++) {
                for (int yy = y0; yy <= y0 + 7; yy++) level.setBlock(new BlockPos(xx, yy, zz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        final VillageFolkEntity walker = VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 35, y0 + 4, Z), 0.0F);
        final VillageFolkEntity edge = VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 71, y0 + 8, Z), 0.0F);
        helper.assertTrue(walker != null && edge != null, "two more folk");
        walker.setNoAi(true);
        edge.setNoAi(true);
        walker.moveTo(x + 35.5, y0 + 4, Z + 0.5, 0.0F, 0.0F);
        edge.moveTo(x + 71.5, y0 + 8, Z + 0.5, 0.0F, 0.0F);
        final double top = y0 + 8 - 0.5;

        helper.runAtTickTime(10, () -> {
            // The drops it will plan: five at most even in a fight, three at a building, three first otherwise.
            folk.clearQueue();
            folk.setTarget(null);
            walker.clearQueue();
            int free = folk.getMaxFallDistance();
            boolean careful = folk.plansCarefully();
            net.minecraft.world.entity.monster.Zombie foe = EntityType.ZOMBIE.create(level);
            foe.moveTo(heart.getX() + 4.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
            folk.setTarget(foe);
            int fight = folk.getMaxFallDistance();
            folk.setTarget(null);
            folk.clearQueue();
            folk.enqueue(Job.build("platform"));
            int building = folk.getMaxFallDistance();
            boolean carefulBuilding = folk.plansCarefully();
            folk.clearQueue();
            Kit.log("sf04 the drops planned: free " + free + " (careful first " + careful + "), in a fight " + fight + ", at a building "
                + building + " (careful first " + carefulBuilding + ")");
            helper.assertTrue(free == 5 && careful, "a walk plans five at most, three first: " + free + ", " + careful);
            helper.assertTrue(fight <= 5, "a fight plans no drop that hurts: " + fight);
            helper.assertTrue(building == 3, "a builder plans none over three: " + building);

            // Down off the ledge to the foot of it: round by the steps, not straight over the four-block edge.
            BlockPos foot = new BlockPos(x + 36, y0, Z + 3);
            // Stood on the ledge (a folk with no AI never lands of itself, and the game plans no walk for
            // one in the air).
            walker.setOnGround(true);
            Path p = walker.getNavigation().createPath(foot, 0);
            int worst = 0, nodes = p == null ? 0 : p.getNodeCount();
            if (p != null) for (int i = 1; i < nodes; i++) worst = Math.max(worst, p.getNode(i - 1).y - p.getNode(i).y);
            Kit.log("sf04 off the ledge: " + (p == null ? "no path" : nodes + " steps, reaches " + p.canReach() + ", the deepest drop " + worst
                + ", ends at " + p.getEndNode()));
            helper.assertTrue(p != null && p.canReach(), "a way down to the foot of the ledge");
            helper.assertTrue(worst <= 3 && nodes > 12, "round by the steps, no drop over three: the deepest " + worst + ", " + nodes + " steps");
        });
        // Shoved toward the edge of the tower, as by a crowd: it stops at the edge.
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t >= 20 && t < 40) edge.setDeltaMovement(0.2, edge.getDeltaMovement().y, 0.0);
        });
        helper.runAtTickTime(60, () -> {
            Kit.log("sf04 shoved toward the edge: at " + String.format("%.2f, %.2f", edge.getX(), edge.getY()) + " (the edge at x "
                + (x + 73) + ", the top at " + (y0 + 8) + ")");
            helper.assertTrue(edge.getY() >= top, "shoved to the edge, it stops there: y " + edge.getY());
            edge.setDeltaMovement(0.0, 0.0, 0.0);
            edge.moveTo(x + 71.5, y0 + 8, Z + 0.5, 0.0F, 0.0F);
        });
        // A blow toward the edge from a step and a half off it: braced, not thrown over.
        helper.runAtTickTime(80, () -> edge.knockback(0.4, -1.0, 0.0));
        helper.runAtTickTime(130, () -> {
            Kit.log("sf04 struck toward the edge: at " + String.format("%.2f, %.2f", edge.getX(), edge.getY()));
            helper.assertTrue(edge.getY() >= top, "a blow at the edge does not throw it off: y " + edge.getY());
            helper.succeed();
        });
    }

    // ============================================================ sf05: lava

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "sf05_lava")
    public static void sf05_lava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long base = 24000L * 3 + 9300;
        level.setDayTime(base);
        final int x = 402400;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(miner != null && miner.ownerId() != null, "a village");
        miner.getInventoryItems().clear();
        miner.insertItem(new ItemStack(Items.OAK_PLANKS, 16));
        miner.insertItem(new ItemStack(Items.COBBLESTONE, 8));
        // Lava in a hole in the ground, where a dig might open onto it.
        final BlockPos lava = Kit.surface(level, x + 10, Z).below();
        final BlockPos lava2 = Kit.surface(level, x + 14, Z).below();
        level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 3);
        level.setBlock(lava2, Blocks.LAVA.defaultBlockState(), 3);
        // A folk to set alight, with water three steps off.
        final BlockPos at = Kit.surface(level, x + 30, Z);
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 3; dx <= 4; dx++) level.setBlock(at.offset(dx, -1, dz), Blocks.WATER.defaultBlockState(), 3);
        }
        final VillageFolkEntity burning = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        helper.assertTrue(burning != null, "another folk");
        final long[] out = { -1 };
        helper.runAtTickTime(10, () -> {
            MineGoal dig = new MineGoal(miner);
            boolean sealed = dig.sealForTests(lava);
            int cobble = miner.countCarried(st -> st.is(Items.COBBLESTONE)), planks = miner.countCarried(st -> st.is(Items.OAK_PLANKS));
            Kit.log("sf05 lava uncovered: sealed " + sealed + " with " + level.getBlockState(lava).getBlock() + "; left " + cobble
                + " cobblestone, " + planks + " planks");
            helper.assertTrue(sealed && level.getBlockState(lava).is(Blocks.COBBLESTONE), "the lava sealed with cobblestone: "
                + level.getBlockState(lava));
            helper.assertTrue(cobble == 7 && planks == 16, "out of its pack, and not its planks: " + cobble + ", " + planks);
            miner.removeMatching(st -> st.is(Items.COBBLESTONE), 64);
            boolean withPlanks = dig.sealForTests(lava2);
            Kit.log("sf05 with only planks: sealed " + withPlanks + ", the hole holds " + level.getBlockState(lava2).getBlock());
            helper.assertTrue(!withPlanks && level.getFluidState(lava2).is(net.minecraft.tags.FluidTags.LAVA),
                "lava is never sealed with something that burns");
            level.setBlock(lava2, Blocks.STONE.defaultBlockState(), 3);
            burning.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        });
        helper.runAtTickTime(20, () -> burning.setRemainingFireTicks(300));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            afternoon(level, burning, base);
            if (t <= 20 || out[0] >= 0) return;
            if (!burning.isOnFire() || burning.isInWater()) {
                out[0] = t;
                Kit.log("sf05 the fire out at " + t + " (in the water " + burning.isInWater() + ") at " + burning.blockPosition().toShortString());
                helper.assertTrue(t - 20 <= 100, "a folk on fire with water three steps off is out within five seconds: " + (t - 20));
                helper.succeed();
            } else if (t >= 140) {
                helper.fail("a folk on fire with water three steps off was still burning after six seconds, at "
                    + burning.blockPosition().toShortString() + " — " + burning.debugLine());
            }
        });
    }

    // ============================================================ sf06: the stores read

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sf06_stores_read")
    public static void sf06_stores_read(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 3 + 9300);
        final int x = 403000;
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        final UUID village = f.ownerId();
        final Villages.Village v = Villages.get(village);
        helper.runAtTickTime(5, () -> {
            // Nothing in the stores but what the test puts there.
            for (BlockPos p : Villages.storeChests(level, village)) {
                if (level.getBlockEntity(p) instanceof Container c) c.clearContent();
            }
            int reach = Villages.storesRadius(village);
            // 1. A chest of bread sixty blocks out: a count over thirty-two does not answer for the whole reach.
            BlockPos far = Kit.surface(level, x + 60, Z);
            level.setBlock(far, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, far);
            Container farBox = (Container) level.getBlockEntity(far);
            farBox.setItem(0, new ItemStack(Items.BREAD, 64));
            Villages.forgetStock();
            int near = Villages.stock(level, v.centre(), Villages.Task.FOOD);
            int all = Villages.stock(level, v.centre(), Villages.Task.FOOD, reach);
            Kit.log("sf06 over thirty-two blocks " + near + " food, over the reach (" + reach + ") " + all);
            helper.assertTrue(all >= near + 64, "the whole reach counted, not the last count over thirty-two: " + near + ", " + all);
            farBox.clearContent();

            // 2. The storehouse, with two hundred loaves and thirty-one ingots.
            BlockPos origin = Kit.surface(level, x - 10, Z + 6);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
            BlockPos door = StorehouseBlock.form(level, origin, Direction.EAST);
            helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity, "a storehouse");
            StorehouseBlockEntity store = (StorehouseBlockEntity) level.getBlockEntity(door);
            for (int i = 0; i < 3; i++) store.insert(new ItemStack(Items.BREAD, 64));
            store.insert(new ItemStack(Items.BREAD, 8));
            store.insert(new ItemStack(Items.IRON_INGOT, 31));
            long day = level.getDayTime() / 24000L;
            int good = Villages.steadyStockForTests(level, v, Villages.Task.FOOD, day);
            // A morning that reads it nearly empty while it stands: the last good count is used for the day.
            store.takeAll();
            int doubted = Villages.steadyStockForTests(level, v, Villages.Task.FOOD, day + 1);
            int again = Villages.steadyStockForTests(level, v, Villages.Task.FOOD, day + 1);
            int believed = Villages.steadyStockForTests(level, v, Villages.Task.FOOD, day + 2);
            Kit.log("sf06 the morning's count: " + good + "; read empty with the storehouse standing " + doubted + " (again that day "
                + again + "); a second such morning " + believed);
            helper.assertTrue(good >= 200, "the storehouse counted: " + good);
            helper.assertTrue(doubted == good && again == good, "a count that lost three quarters overnight is not believed for the day: "
                + doubted + ", " + again);
            helper.assertTrue(believed < 50, "a second low morning is: " + believed);

            // 3. A unit taken out of the cube: the goods wait in its door, and are still the village's.
            for (int i = 0; i < 3; i++) store.insert(new ItemStack(Items.BREAD, 64));
            store.insert(new ItemStack(Items.BREAD, 8));
            BlockPos unit = null;
            for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(2, 2, 2))) {
                if (!p.equals(door)) { unit = p.immutable(); break; }
            }
            level.setBlock(unit, Blocks.AIR.defaultBlockState(), 3);
            boolean waiting = level.getBlockEntity(door) instanceof StorehouseBlockEntity held && !held.isStore() && held.hasGoods();
            Villages.forgetStock();
            int counted = Villages.stock(level, v.centre(), Villages.Task.FOOD, reach);
            Kit.log("sf06 a unit out of the cube: goods waiting " + waiting + ", the storehouse standing " + Storehouses.stands(village)
                + "; the stores read " + counted + " food");
            helper.assertTrue(waiting, "the goods wait in the door of the cube come apart");
            helper.assertTrue(counted >= 200, "and are counted with the stores: " + counted);
            helper.succeed();
        });
    }
}
