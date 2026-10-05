package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Fuel;
import com.jrpetty.mcassistant.entity.Larder;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Strays;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the hundred days showed of the village's economy: a birth boom that ate the larder, no coal in
 * the stores, and a guard walking its beat with a builder's stock in its pack.
 *
 * <ul>
 * <li><b>ec01</b>: a village growing twelve meals a day more than it eats, its larder full, raises
 *     children one after another until its mouths would eat more than it grows (with a gap the larder
 *     could carry a fortnight), and then stops, saying why; grown more, it may go on; on short commons,
 *     never; and the leader's forecast counts the new mouths.</li>
 * <li><b>ec02</b>: a Wood Age village with timber enough and no coal at all: its smelter, with no ore,
 *     takes logs the builders can spare out of the stores, burns them into charcoal on logs, and the
 *     charcoal goes into the village's chests (none kept back in its pack while the stores are under the
 *     floor of coal a village keeps). Nothing comes from nothing:
 *     never more charcoal than logs taken.</li>
 * <li><b>ec03</b>: a guard carrying eighty-five of the builders' stock (logs, stairs, glass, doors)
 *     takes it back to the stores and keeps its sword and its bread; a woodcutter's own logs are not
 *     counted against it; and with the stores' coal under the floor the watch makes no torches of it.</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 360,000 to 366,000, z 50,000).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EconomyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** A chest of the stores at this spot with these goods. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    /** Nothing in the village's stores: only what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    /** How much of this the chests round here hold (not the furnaces: what is in the fire is not in the stores). */
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

    // ============================================================ ec01: the boom halts

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ec01_birth_boom_halts")
    public static void ec01_birth_boom_halts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 4 + 1000);
        final int x = 360500;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 6);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(stood == 6 && v != null, "a village of six: " + stood);
        UUID id = v.id();
        List<VillageFolkEntity> grown = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby()) {
                grown.add(f);
                f.insertItem(new ItemStack(Items.BREAD, 64));     // a child costs its parents two meals apiece
            }
        }
        helper.assertTrue(grown.size() >= 2, "grown folk to be parents: " + grown.size());
        // A full larder, and books that say forty grown a day against thirty eaten by six.
        chestAt(level, Kit.surface(level, x + 4, Z + 4), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64),
            new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64),
            new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64));
        Villages.forgetStock();
        long day = level.getDayTime() / 24000L;
        int heads = Villages.headcount(id);
        Leader.booksForTests(id, new Leader.Books(512, 40, 30, 40.0, 30.0, 17.0, Leader.Plan.STEADY, day));
        Larder.booked(id, heads);
        int food = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        Kit.log("ec01 a village of " + heads + ", " + food + " food in the stores; " + Larder.line(id));

        // The boom: a child whenever the larder says yes, two grown folk at a time.
        int births = 0, lastBefore = 0;
        Larder.Verdict last = null;
        for (int i = 0; i < 24; i++) {
            last = Larder.oneMore(id, food);
            Kit.log("ec01 " + Villages.headcount(id) + " folk: " + (last.yes() ? "yes" : "no") + " — " + last.why());
            if (!last.yes()) break;
            VillageFolkEntity mum = grown.get((2 * i) % grown.size()), dad = grown.get((2 * i + 1) % grown.size());
            int was = Villages.headcount(id);
            VillageFolkEntity child = mum.raiseChildWith(dad);
            if (child == null) {
                mum.insertItem(new ItemStack(Items.BREAD, 16));
                dad.insertItem(new ItemStack(Items.BREAD, 16));
                continue;
            }
            births++;
            lastBefore = was;
        }
        int after = Villages.headcount(id);
        double perHead = Larder.perHead(id, 30.0);
        Kit.log("ec01 the boom stopped at " + after + " folk after " + births + " births (five meals a head by the books); "
            + "the board says: growing " + Villages.growthNote(level, id));
        helper.assertTrue(births >= 2, "a village growing more than it eats raises children: " + births);
        helper.assertTrue(last != null && !last.yes() && last.why().contains("grown a day against"),
            "and stops when its mouths would eat more than it grows, saying so: " + (last == null ? "-" : last.why()));
        // Forty grown, and a gap of a fifth the larder could carry a fortnight: no child once the mouths with it
        // would eat past fifty (six folk eating thirty is five a head: the last at nine folk; twins may come of it).
        int most = (int) Math.floor(50.0 / perHead) - 1;
        helper.assertTrue(lastBefore <= most && after <= most + 4, "it stops near where the fields can feed it, not at the larder's bottom: the last birth at "
            + lastBefore + " folk (at most " + most + "), " + after + " now");
        helper.assertTrue(perHead * (after + 1) > 40.0, "the next mouth would eat past what is grown: " + perHead * (after + 1));
        // The leader's forecast counts the mouths born since the books were made up.
        double forecast = Larder.forecast(id, 30.0);
        Kit.log("ec01 the leader's forecast: " + Math.round(forecast) + " eaten a day against the books' 30");
        helper.assertTrue(forecast >= perHead * after - 0.01 && forecast > 30.0, "the forecast counts the new mouths: " + forecast);
        // Grown more (new fields in): children again.
        Leader.booksForTests(id, new Leader.Books(512, 120, 30, 120.0, 30.0, 17.0, Leader.Plan.STEADY, day));
        Larder.Verdict more = Larder.oneMore(id, food);
        helper.assertTrue(more.yes(), "with the fields grown to a hundred and twenty a day, a child again: " + more.why());
        // On short commons, never, however much is grown.
        Leader.booksForTests(id, new Leader.Books(512, 120, 30, 120.0, 30.0, 17.0, Leader.Plan.SHORT, day));
        Larder.Verdict shortCommons = Larder.oneMore(id, food);
        helper.assertTrue(!shortCommons.yes(), "on short commons no child is raised: " + shortCommons.why());
        // And the forecast turned: more eaten than grown by a quarter, short commons: the stone heaps send hands to the fields.
        Leader.booksForTests(id, new Leader.Books(300, 40, 60, 40.0, 60.0, 5.0, Leader.Plan.SHORT, day));
        helper.assertTrue(Larder.fieldsWanted(id), "a turned forecast wants more hands in the fields");
        // The rule on the hundred days' own numbers: day 30, 71 grown against 137 eaten, three and a half days put by.
        Larder.Verdict day30 = Larder.judge(313, 71.0, 137.0 * 22 / 21, Leader.Plan.STEADY);
        Larder.Verdict day20 = Larder.judge(555, 110.0, 57.0 * 10 / 9, Leader.Plan.PLENTY);
        Kit.log("ec01 the hundred days: day 20 " + day20.why() + "; day 30 " + day30.why());
        helper.assertTrue(day20.yes() && !day30.yes(), "day twenty may grow; day thirty may not");
        helper.succeed();
    }

    // ============================================================ ec02: charcoal from logs

    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "ec02_charcoal_from_logs")
    public static void ec02_charcoal_from_logs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        final int x = 362000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID village = f.ownerId();
        helper.assertTrue(Villages.ageOf(village) == Villages.Age.WOOD, "in the Wood Age");
        emptyStores(level, village);
        Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
        // Timber enough for the age (it wants ninety-six), and not a lump of coal anywhere.
        chestAt(level, Kit.surface(level, x + 3, Z + 2), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64),
            new ItemStack(Items.BREAD, 32));
        Villages.forgetStock();
        f.setJob(StationTask.SMELT);
        BlockPos forge = Kit.surface(level, x - 4, Z);
        level.setBlock(forge, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(forge, Blocks.FURNACE.defaultBlockState(), 3);
        ZoneChests.mark(level, forge);
        f.assignPlot(WorkZone.around(forge, 4, WorkZone.DEFAULT_DEPTH), "The Forge");
        f.moveTo(forge.getX() + 1.5, forge.getY(), forge.getZ() + 0.5, 0.0F, 0.0F);
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.BREAD, 8));

        int coalBefore = Fuel.inStores(level, village);
        boolean low = Fuel.low(level, village), wanted = Fuel.charcoalWanted(level, village), saving = f.coalLow();
        int logsBefore = inChests(level, x, Z, 40, logs);
        Kit.log("ec02 the stores: " + coalBefore + " coal (floor " + Fuel.floor(Villages.headcount(village)) + "), " + logsBefore
            + " logs; low " + low + ", charcoal wanted " + wanted + ", the smelter sees the stores low " + saving);
        helper.assertTrue(coalBefore == 0 && low && wanted && saving,
            "no coal in a Wood Age village with timber to spare: the stores are under the floor and charcoal is wanted");
        boolean burning = f.burnCharcoal();
        int drawn = logsBefore - inChests(level, x, Z, 40, logs);
        Kit.log("ec02 the smelter: burning " + burning + ", drew " + drawn + " logs — " + f.debugLine());
        helper.assertTrue(burning && drawn > 0 && drawn <= 16, "the smelter draws logs the builders can spare: " + drawn);

        final int[] nudged = { 0 }, taken = { drawn };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);
            if (t % 20 != 0) return;
            int stored = inChests(level, x, Z, 40, s -> s.is(Items.CHARCOAL));
            int carried = f.countCarried(s -> s.is(Items.CHARCOAL));
            int fired = level.getBlockEntity(forge) instanceof AbstractFurnaceBlockEntity fb && fb.getItem(2).is(Items.CHARCOAL)
                ? fb.getItem(2).getCount() : 0;
            if (t % 400 == 0) Kit.log("ec02 @" + t + ": charcoal in the chests " + stored + ", carried " + carried + ", in the furnace "
                + fired + " — " + f.debugLine());
            // Nothing from nothing: never more charcoal than logs gone out of the stores (it may go back for more).
            taken[0] = Math.max(taken[0], logsBefore - inChests(level, x, Z, 40, logs));
            if (stored + carried + fired > taken[0]) {
                helper.fail("more charcoal than logs taken: " + (stored + carried + fired) + " of " + taken[0]);
                return;
            }
            // Charcoal in hand is the stores': none of it kept back while the stores are under the floor.
            if (carried > 0 && f.countStashable(s -> s.is(Items.CHARCOAL)) != carried) {
                helper.fail("the smelter keeps charcoal back from the stores: " + f.countStashable(s -> s.is(Items.CHARCOAL)) + " of " + carried);
                return;
            }
            // A smelter called away to other work a long while with the charcoal in its pack is sent in with it,
            // once, and the log says so (the test is of the charcoal, not of how the day's work is ordered).
            if (t >= 6000 && carried > 0 && stored == 0 && nudged[0] == 0 && f.peekJob() == null) {
                nudged[0] = 1;
                var job = f.villageDepositJob();
                if (job != null) f.enqueue(job);
                Kit.log("ec02 nudged the smelter in with its charcoal at " + t);
            }
            if (stored > 0) {
                Villages.forgetStock();
                int counted = Fuel.inStores(level, village);
                Kit.log("ec02 charcoal in the stores at " + t + ": " + stored + " (the village counts " + counted + " coal), of "
                    + taken[0] + " logs taken" + (nudged[0] > 0 ? " (nudged)" : ""));
                helper.assertTrue(stored >= 1, "charcoal in the stores");
                helper.succeed();
            } else if (t >= 8800) {
                helper.fail("no charcoal reached the stores: carried " + carried + ", in the furnace " + fired + " — " + f.debugLine());
            }
        });
    }

    // ============================================================ ec03: the builders' stock goes back

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "ec03_strays_home")
    public static void ec03_strays_home(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        final int x = 363500;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity guard = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(guard != null && guard.ownerId() != null, "a village");
        UUID village = guard.ownerId();
        emptyStores(level, village);
        // A chest of the stores with room, three coal in it (under the floor) and logs.
        chestAt(level, Kit.surface(level, x + 3, Z + 3), new ItemStack(Items.COAL, 3), new ItemStack(Items.OAK_LOG, 64),
            new ItemStack(Items.BREAD, 16));
        Villages.forgetStock();
        guard.setJob(StationTask.GUARD);
        guard.getInventoryItems().clear();
        guard.insertItem(new ItemStack(Items.IRON_SWORD));
        guard.insertItem(new ItemStack(Items.BREAD, 8));
        guard.insertItem(new ItemStack(Items.BIRCH_LOG, 40));
        guard.insertItem(new ItemStack(Items.OAK_STAIRS, 32));
        guard.insertItem(new ItemStack(Items.GLASS, 10));
        guard.insertItem(new ItemStack(Items.OAK_DOOR, 3));
        // (Birch logs and oak stairs: the woodcutter below carries oak logs and spruce stairs, its own to bank.)
        Predicate<ItemStack> stock = s -> s.is(Items.BIRCH_LOG) || s.is(Items.OAK_STAIRS) || s.is(Items.GLASS) || s.is(Items.OAK_DOOR);
        int carried = Strays.carried(guard);
        // The watch makes no torches of the stores' last coal (Fuel): the stores are under the floor.
        boolean saving = guard.coalLow();
        Kit.log("ec03 the guard carries " + carried + " of the builders' stock; the stores' coal " + Fuel.inStores(level, village)
            + " under the floor " + Fuel.floor(Villages.headcount(village)) + ": low " + saving + " — " + guard.debugLine());
        helper.assertTrue(carried == 85, "eighty-five of the builders' stock, its sword and bread not counted: " + carried);
        helper.assertTrue(saving, "with three coal in the stores they are under the floor, and the watch makes no torches of it");

        // A woodcutter's own logs are its trade's to bank, not strays; its stairs are.
        VillageFolkEntity cutter = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3, Z - 3), 0.0F);
        helper.assertTrue(cutter != null && village.equals(cutter.ownerId()), "a woodcutter of the village");
        cutter.setJob(StationTask.WOOD);
        cutter.getInventoryItems().clear();
        cutter.insertItem(new ItemStack(Items.OAK_LOG, 40));
        cutter.insertItem(new ItemStack(Items.SPRUCE_STAIRS, 8));
        int cut = Strays.carried(cutter);
        Kit.log("ec03 the woodcutter: " + cut + " strays of 40 logs and 8 stairs");
        helper.assertTrue(cut == 8, "a woodcutter's logs are its own work; only its stairs are strays: " + cut);

        // The woodcutter holds the village's building lead, so the guard is not called off to raise the first
        // building with the very timber it is carrying back (the test is of the carrying back).
        Villages.isLead(village, cutter.getUUID(), level.getGameTime());
        int before = inChests(level, x, Z, 40, stock);
        boolean off = Strays.tend(guard);
        Kit.log("ec03 the guard sets off: " + off + "; the stores hold " + before + " of it — " + guard.debugLine());
        helper.assertTrue(off, "a load of sixteen and more goes back at once");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);
            if (t % 5 != 0) return;
            int left = guard.countCarried(stock);
            int stored = inChests(level, x, Z, 40, stock);
            if (t % 400 == 0) Kit.log("ec03 @" + t + ": the guard still carries " + left + ", the stores hold " + stored + " — " + guard.debugLine());
            if (left == 0 || stored - before >= 85) {
                boolean sword = guard.countCarried(s -> s.is(Items.IRON_SWORD)) == 1;
                boolean bread = guard.countCarried(s -> s.get(DataComponents.FOOD) != null) >= 1;
                Kit.log("ec03 back in the stores at " + t + ": " + (stored - before) + " of 85; sword " + sword + ", bread " + bread);
                helper.assertTrue(stored - before >= 85, "the builders' stock is in the stores: " + (stored - before));
                helper.assertTrue(sword && bread, "and the guard keeps its sword and its bread");
                helper.assertTrue(left == 0, "nothing of the builders' left in its pack: " + left);
                helper.succeed();
            } else if (t >= 5800) {
                helper.fail("the guard still carries " + left + " of the builders' stock — " + guard.debugLine());
            }
        });
    }
}
