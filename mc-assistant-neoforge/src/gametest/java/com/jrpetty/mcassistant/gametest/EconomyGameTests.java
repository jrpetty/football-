package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Fuel;
import com.jrpetty.mcassistant.entity.Larder;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Meals;
import com.jrpetty.mcassistant.entity.PackedLunch;
import com.jrpetty.mcassistant.entity.PutAway;
import com.jrpetty.mcassistant.entity.Fields;
import com.jrpetty.mcassistant.entity.Mishap;
import com.jrpetty.mcassistant.entity.Couriers;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.AABB;
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
 *     counted against it; and with the stores' coal under the floor the watch makes no torches of it. A
 *     lead's building stays in its hands; a lapsed lead (a farmer made a woodcutter, far out) lets go of
 *     its cobblestone and stairs, and of its own logs, for the stores.</li>
 * <li><b>ec04</b>: a farmer with a grown field keeps thirty-two of each crop it plants and banks the rest
 *     (seventy-eight potatoes, sixty carrots and fifty-six seeds were all kept as seed); out at a far
 *     field at the midday meal with nothing but its seed, it eats a carrot rather than go without.</li>
 * <li><b>ec05</b>: a farmer setting out for a field seventy-five blocks from the stores takes a day's meals
 *     out of them first; out there at a mealtime with nothing to eat, it sends for food (walks in, with no
 *     couriers), once a meal.</li>
 * <li><b>ec06</b>: a miner at its plot with a pack of cobblestone, coal and iron at nightfall puts the day's
 *     work into its work chest before home and bed ("Putting the day's work away" on its card), and keeps
 *     its pickaxe, its torches and its rations.</li>
 * <li><b>ec07</b>: a farmer at its field at midday puts its harvest into its work chest down to its seed.</li>
 * <li><b>ec08</b>: a courier on a run at midday does not stop to bank: the run is seen through.</li>
 * <li><b>ec09</b>: two nine-by-nine wheat fields sown the same tick, one a farmer's (tended), one wild:
 *     the tended one grows about twice as fast, at a handful of growth ticks a second.</li>
 * <li><b>ec10</b>: a farmer on a kept nine-by-nine field (crops at every age) brings in at least fifteen
 *     meals in a day of growth at the game's own tick speed (the game's own pace: about eleven).</li>
 * <li><b>ec11</b>: a fisher by a pond lands fish at about a player's rate.</li>
 * <li><b>ec12</b>: a hunter brings meat home, and leaves the last pair of a kind.</li>
 * <li><b>ec13</b>: the watch grows with the town, and by half again once monsters have killed; the books say
 *     what took them; a raider left over from a raid that is over goes at dawn.</li>
 * <li><b>ec14</b>: a worker whose plot's edge towards the town is under a bluff ten blocks high sets its work
 *     chest down on its own level, not on the top of the bluff where it could never get to it.</li>
 * <li><b>ec15</b>: a town of eight with its one hand for the town's work at the levelling still gets a hand to
 *     make up the beds; a third piece of work waits.</li>
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

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Is the day's work being put away (its queue)? */
    private static boolean puttingAway(VillageFolkEntity f) {
        for (Job j : f.queuedJobs()) if (j.putAway()) return true;
        return false;
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
        // A lead keeps what it drew for its building: nothing of it is a stray.
        cutter.drewForBuildForTests();
        boolean leadSent = Strays.tend(cutter);
        int leadHeld = Strays.carried(cutter);
        // A lapsed lead (a farmer made a woodcutter, its building now another's) lets go of all of it.
        VillageFolkEntity lapsed = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3, Z + 3), 0.0F);
        helper.assertTrue(lapsed != null && village.equals(lapsed.ownerId()), "a third folk of the village");
        lapsed.setJob(StationTask.WOOD);
        lapsed.getInventoryItems().clear();
        lapsed.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        lapsed.insertItem(new ItemStack(Items.JUNGLE_STAIRS, 8));
        lapsed.insertItem(new ItemStack(Items.ACACIA_LOG, 20));
        lapsed.drewForBuildForTests();
        int heldBack = Strays.carried(lapsed), logsHeldBack = lapsed.countStashable(s -> s.is(Items.ACACIA_LOG));
        boolean lapsedSent = Strays.tend(lapsed);
        int letGo = Strays.carried(lapsed), logsLetGo = lapsed.countStashable(s -> s.is(Items.ACACIA_LOG));
        Kit.log("ec03 the lead: sent " + leadSent + ", strays " + leadHeld + "; the lapsed lead: strays " + heldBack + " and logs "
            + logsHeldBack + " held as the building's, then " + letGo + " and " + logsLetGo + ", sent " + lapsedSent);
        helper.assertTrue(!leadSent && leadHeld == 0, "the lead keeps its building's stock: " + leadHeld);
        helper.assertTrue(heldBack == 0 && logsHeldBack == 0, "a lapsed lead's stock was held as the building's: " + heldBack);
        helper.assertTrue(letGo == 72 && logsLetGo == 20 && lapsedSent,
            "the lapsed lead lets go of its cobblestone and stairs, and its own logs, and takes them in: " + letGo + ", " + logsLetGo);
        lapsed.clearQueue();                                            // (its walk is the guard's, below)
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

    // ============================================================ ec04: the farmer's seed

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "ec04_farmer_seed_cap")
    public static void ec04_farmer_seed_cap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 3 + 4000);
        final int x = 364500;
        Kit.hold(level, x, Z, 80);
        Kit.prepare(level, x, Z, 80);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID village = f.ownerId();
        emptyStores(level, village);
        chestAt(level, Kit.surface(level, x + 3, Z + 3));
        Villages.forgetStock();
        // A grown field (twenty-seven across) a little way out, and a farmer back from it with its harvest.
        BlockPos field = Kit.surface(level, x + 24, Z);
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(field, 13, WorkZone.DEFAULT_DEPTH), "Home Fields");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_HOE));
        f.insertItem(new ItemStack(Items.POTATO, 64));
        f.insertItem(new ItemStack(Items.POTATO, 14));
        f.insertItem(new ItemStack(Items.CARROT, 60));
        f.insertItem(new ItemStack(Items.WHEAT_SEEDS, 56));
        int keepPotato = f.depositReserve(new ItemStack(Items.POTATO)), keepSeed = f.depositReserve(new ItemStack(Items.WHEAT_SEEDS));
        int spareP = f.countStashable(s -> s.is(Items.POTATO)), spareC = f.countStashable(s -> s.is(Items.CARROT)),
            spareS = f.countStashable(s -> s.is(Items.WHEAT_SEEDS));
        Kit.log("ec04 a farmer with a field " + (2 * f.workZone().radius() + 1) + " across keeps " + keepPotato + " potatoes and "
            + keepSeed + " seeds; of 78 potatoes, 60 carrots and 56 seeds the stores' are " + spareP + ", " + spareC + ", " + spareS);
        helper.assertTrue(keepPotato == AssistantEntity.SEED_MOST && keepSeed == AssistantEntity.SEED_MOST,
            "thirty-two of each crop at most is seed: " + keepPotato + ", " + keepSeed);
        helper.assertTrue(spareP == 46 && spareC == 28 && spareS == 24, "the rest is the stores': " + spareP + ", " + spareC + ", " + spareS);
        Predicate<ItemStack> crop = s -> s.is(Items.POTATO) || s.is(Items.CARROT) || s.is(Items.WHEAT_SEEDS);
        int before = inChests(level, x, Z, 70, crop);
        var job = f.villageDepositJob();
        helper.assertTrue(job != null, "a village hand banks at the village's chests");
        f.clearQueue();
        f.enqueue(job);
        final boolean[] fed = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(24000L * 3 + 4000);
            if (t % 10 != 0 || fed[0]) return;
            int p = f.countCarried(s -> s.is(Items.POTATO)), c = f.countCarried(s -> s.is(Items.CARROT)),
                sd = f.countCarried(s -> s.is(Items.WHEAT_SEEDS));
            int banked = inChests(level, x, Z, 70, crop) - before;
            if (t % 200 == 0) Kit.log("ec04 @" + t + ": carrying " + p + " potatoes, " + c + " carrots, " + sd + " seeds; banked " + banked
                + " — " + f.debugLine());
            if (p <= 32 && c <= 32 && sd <= 32 && banked >= 90) {
                Kit.log("ec04 banked " + banked + " at " + t + ", keeping " + p + ", " + c + ", " + sd);
                fed[0] = true;
                // Out at the far edge of its field, past the stores' reach, at the midday meal, nothing but its seed.
                BlockPos out = Kit.surface(level, x + 72, Z);
                f.clearQueue();
                f.moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0.0F, 0.0F);
                f.getInventoryItems().clear();
                f.insertItem(new ItemStack(Items.STONE_HOE));
                f.insertItem(new ItemStack(Items.CARROT, 20));
                level.setDayTime(24000L * 9 + 5700);
                Meals.tick(f);
                int left = f.countCarried(s -> s.is(Items.CARROT));
                Kit.log("ec04 the midday meal out at the field: " + Meals.line(f) + "; carrots " + left);
                helper.assertTrue(left == 19 && f.meals().missedInRow() == 0 && f.meals().eatenToday() >= 1,
                    "with nothing but its seed, it eats a carrot rather than miss its meal: " + Meals.line(f));
                helper.succeed();
            } else if (t >= 3800) {
                helper.fail("the farmer did not bank past its seed: carrying " + p + ", " + c + ", " + sd + "; banked " + banked
                    + " — " + f.debugLine());
            }
        });
    }

    // ============================================================ ec05: a packed lunch

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "ec05_packed_lunch")
    public static void ec05_packed_lunch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long morning = 24000L * 3 + 4000;
        level.setDayTime(morning);
        final int x = 365300;
        Kit.hold(level, x, Z, 90);
        Kit.prepare(level, x, Z, 90);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID village = f.ownerId();
        emptyStores(level, village);
        chestAt(level, Kit.surface(level, x + 3, Z + 3), new ItemStack(Items.BREAD, 32));
        Villages.forgetStock();
        Predicate<ItemStack> bread = s -> s.is(Items.BREAD);
        // A field seventy-five blocks out: past where the stores feed a hand at mealtimes.
        BlockPos field = Kit.surface(level, x + 75, Z);
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(field, 4, WorkZone.DEFAULT_DEPTH), "Far Fields");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_HOE));
        f.insertItem(new ItemStack(Items.WHEAT_SEEDS, 16));

        // Out there first, at the midday meal, with nothing to eat: it sends for food, once.
        BlockPos out = Kit.surface(level, x + 75, Z + 2);
        f.clearQueue();
        f.moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0.0F, 0.0F);
        level.setDayTime(24000L * 9 + 5700);
        Meals.tick(f);
        int queued = 0;
        for (var j : f.queuedJobs()) if (j.type() == com.jrpetty.mcassistant.entity.Job.Type.WITHDRAW && j.arg().startsWith("ration@")) queued++;
        boolean again = PackedLunch.sendFor(f, 9L * 4);
        Kit.log("ec05 the midday meal out at the field with nothing: " + Meals.line(f) + "; going in for food: " + queued + "; again " + again
            + " — " + f.debugLine());
        helper.assertTrue(queued == 1 && !again, "with nothing to eat in reach it goes in to the stores for food, once a meal: " + queued);
        helper.assertTrue(f.meals().missedInRow() == 0, "and the meal is not yet missed: the mealtime lasts");

        // Back in the town in the morning, setting out: a packed lunch.
        f.clearQueue();
        f.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
        level.setDayTime(morning);
        PackedLunch.resetForTests();
        final int stored = inChests(level, x, Z, 40, bread);
        final boolean[] set = { PackedLunch.take(f) };
        Kit.log("ec05 setting out with " + PackedLunch.meals(f) + " meals: a packed lunch " + set[0] + " — " + f.debugLine());
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 9000 || level.getDayTime() % 24000 < 3000) level.setDayTime(morning);
            if (t % 10 != 0) return;
            int meals = PackedLunch.meals(f);
            boolean going = false;
            for (var j : f.queuedJobs()) if (j.type() == com.jrpetty.mcassistant.entity.Job.Type.WITHDRAW) going = true;
            if (!set[0] && !going && meals < PackedLunch.DAY) {
                // (Not yet on shift, or it wandered: back to the heart and look again.)
                if (f.blockPosition().distSqr(heart) > 20 * 20) {
                    f.clearQueue();
                    f.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
                }
                set[0] = PackedLunch.take(f);
            }
            int taken = stored - inChests(level, x, Z, 40, bread);
            if (t % 200 == 0) Kit.log("ec05 @" + t + ": " + meals + " meals carried, " + taken + " bread out of the stores — " + f.debugLine());
            if (meals >= PackedLunch.DAY) {
                int carried = f.countCarried(bread);
                Kit.log("ec05 a packed lunch at " + t + ": " + meals + " meals, " + carried + " bread, " + taken + " out of the stores");
                helper.assertTrue(carried <= taken, "nothing from nothing: every loaf came out of the stores (" + carried + " of " + taken + ")");
                helper.assertTrue(taken <= 12, "a day's meals, not the larder: " + taken);
                helper.succeed();
            } else if (t >= 3800) {
                helper.fail("no packed lunch: " + meals + " meals carried — " + f.debugLine());
            }
        });
    }

    // ============================================================ ec06: the miner at nightfall

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "ec06_miner_banks_at_dusk")
    public static void ec06_miner_banks_at_dusk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long day = 24000L * 5;
        level.setDayTime(day + 4000);
        final int x = 361250;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        BlockPos mine = Kit.surface(level, x + 16, Z);
        f.setJob(StationTask.MINE);
        f.assignPlot(WorkZone.around(mine, 8, WorkZone.DEFAULT_DEPTH), "The Pit");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.CHEST));
        BlockPos chest = f.productionChestForTests();
        helper.assertTrue(chest != null && level.getBlockEntity(chest) instanceof Container, "the miner's work chest at its plot");
        Container box = (Container) level.getBlockEntity(chest);
        f.moveTo(chest.getX() + 2.5, chest.getY(), chest.getZ() + 0.5, 0.0F, 0.0F);
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_PICKAXE));
        f.insertItem(new ItemStack(Items.TORCH, 16));
        f.insertItem(new ItemStack(Items.BREAD, 6));
        f.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        f.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        f.insertItem(new ItemStack(Items.COAL, 20));
        f.insertItem(new ItemStack(Items.RAW_IRON, 12));
        int keepCobble = f.depositReserve(new ItemStack(Items.COBBLESTONE));
        f.noteStashed();                                                // (it banked a moment ago: no full-pack run of its own)
        Kit.log("ec06 a miner at its plot, its work chest at " + chest.toShortString() + "; it keeps " + keepCobble
            + " cobblestone; a pack of 128 cobblestone, 20 coal, 12 raw iron at nightfall — " + f.debugLine());
        // Nightfall: the shift is over. From here the folk acts on its own.
        final long night = day + 13600;
        level.setDayTime(night);
        final String[] card = { null };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod > 15000 || tod < 13000) level.setDayTime(night);
            if (card[0] == null && puttingAway(f)) {
                card[0] = FolkTalk.nowDoing(f);
                Kit.log("ec06 at " + t + " the card reads: " + card[0]);
            }
            if (t % 10 != 0) return;
            int cobble = count(box, st -> st.is(Items.COBBLESTONE)), coal = count(box, st -> st.is(Items.COAL)),
                iron = count(box, st -> st.is(Items.RAW_IRON));
            if (t % 200 == 0) Kit.log("ec06 @" + t + ": the work chest holds " + cobble + " cobblestone, " + coal + " coal, " + iron
                + " raw iron — " + f.debugLine());
            if (cobble >= 128 - keepCobble && coal >= 20 && iron >= 12) {
                int pick = f.countCarried(st -> st.is(Items.STONE_PICKAXE)), torches = f.countCarried(st -> st.is(Items.TORCH)),
                    bread = f.countCarried(st -> st.is(Items.BREAD));
                boolean done = PutAway.doneForTests(f, PutAway.When.DUSK);
                Kit.log("ec06 put away at " + t + ": " + cobble + ", " + coal + ", " + iron + "; kept pickaxe " + pick + ", torches "
                    + torches + ", bread " + bread + "; booked " + done + "; " + PutAway.line(f.ownerId(), level.getDayTime() / 24000L));
                helper.assertTrue("Putting the day's work away".equals(card[0]), "the card says what it is about: " + card[0]);
                helper.assertTrue(pick == 1 && torches >= 16 && bread >= 4, "it keeps its pickaxe, its torches and its rations: "
                    + pick + ", " + torches + ", " + bread);
                helper.assertTrue(f.stashable() == 0, "nothing past its keep left in its pack: " + f.stashable());
                helper.assertTrue(done, "the put-away is booked for the town's books");
                helper.succeed();
            } else if (t >= 2800) {
                helper.fail("the day's work was not put away: the chest holds " + cobble + ", " + coal + ", " + iron + " — " + f.debugLine());
            }
        });
    }

    // ============================================================ ec07: the farmer at noon

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "ec07_farmer_banks_at_noon")
    public static void ec07_farmer_banks_at_noon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long day = 24000L * 6;
        level.setDayTime(day + 3000);
        final int x = 362750;
        Kit.hold(level, x, Z, 50);
        Kit.prepare(level, x, Z, 50);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        BlockPos field = Kit.surface(level, x + 26, Z);
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(field, 13, WorkZone.DEFAULT_DEPTH), "Home Fields");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.CHEST));
        BlockPos chest = f.productionChestForTests();
        helper.assertTrue(chest != null && level.getBlockEntity(chest) instanceof Container, "the farmer's work chest at its field");
        Container box = (Container) level.getBlockEntity(chest);
        f.moveTo(chest.getX() + 1.5, chest.getY(), chest.getZ() + 1.5, 0.0F, 0.0F);
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_HOE));
        f.insertItem(new ItemStack(Items.BREAD, 6));
        // A morning's harvest past its seed: twenty, under what it would make a trip for on its own (a load of
        // twenty-four), and it banked a moment ago. The point of the midday put-away is the pack that is not full.
        f.insertItem(new ItemStack(Items.POTATO, AssistantEntity.SEED_MOST + 10));
        f.insertItem(new ItemStack(Items.CARROT, AssistantEntity.SEED_MOST + 5));
        f.insertItem(new ItemStack(Items.WHEAT_SEEDS, AssistantEntity.SEED_MOST + 5));
        f.noteStashed();
        Predicate<ItemStack> harvest = st -> st.is(Items.POTATO) || st.is(Items.CARROT) || st.is(Items.WHEAT_SEEDS);
        int spare = f.countStashable(harvest);
        Kit.log("ec07 a farmer at its field with " + spare + " of the harvest past its seed; its work chest at "
            + chest.toShortString() + " — " + f.debugLine());
        helper.assertTrue(spare == 20, "twenty past its seed: " + spare);
        // Midday: from here the folk acts on its own.
        final long noon = day + 6000;
        level.setDayTime(noon);
        final boolean[] seen = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod > 8500 || tod < 5600) level.setDayTime(noon);
            if (!seen[0] && puttingAway(f)) {
                seen[0] = true;
                Kit.log("ec07 at " + t + " the card reads: " + FolkTalk.nowDoing(f));
            }
            if (t % 10 != 0) return;
            int banked = count(box, harvest);
            int p = f.countCarried(st -> st.is(Items.POTATO)), c = f.countCarried(st -> st.is(Items.CARROT)),
                sd = f.countCarried(st -> st.is(Items.WHEAT_SEEDS));
            if (t % 300 == 0) Kit.log("ec07 @" + t + ": the work chest holds " + banked + " of the harvest; carrying " + p + " potatoes, "
                + c + " carrots, " + sd + " seeds — " + f.debugLine());
            // (It may plant some of its seed meanwhile: the chest gets what is past the seed, at most twenty.)
            if (seen[0] && banked >= 1 && p <= AssistantEntity.SEED_MOST && c <= AssistantEntity.SEED_MOST
                    && sd <= AssistantEntity.SEED_MOST) {
                Kit.log("ec07 the morning's harvest put away at " + t + ": " + banked + " in the chest; kept " + p + ", " + c + ", " + sd
                    + "; " + PutAway.line(f.ownerId(), level.getDayTime() / 24000L));
                helper.assertTrue(f.countCarried(st -> st.is(Items.STONE_HOE)) == 1, "it keeps its hoe");
                helper.succeed();
            } else if (t >= 5800) {
                helper.fail("the harvest was not put away at noon: the chest holds " + banked + "; carrying " + p + ", " + c + ", " + sd
                    + " (put-away seen " + seen[0] + ") — " + f.debugLine());
            }
        });
    }

    // ============================================================ ec08: a courier on its run

    /** A storehouse (twenty-seven units) beside the heart; its door. */
    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int i = 0; i < 3; i++) for (int k = 0; k < 3; k++) {
                level.setBlock(origin.offset(i, y, k), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity sb && sb.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    @GameTest(template = EMPTY, timeoutTicks = 5000, batch = "ec08_courier_mid_run")
    public static void ec08_courier_mid_run(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long day = 24000L * 7;
        final long noon = day + 6000;
        level.setDayTime(day + 3000);
        final int x = 364000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(farmer != null && farmer.ownerId() != null, "a village");
        UUID village = farmer.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        // The founders' chest is an old chest to clear; the test is of a run out to a work chest.
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
        BlockPos site = Kit.surface(level, heart.getX() + 40, heart.getZ());
        farmer.setJob(StationTask.FARM);
        farmer.assignPlot(WorkZone.around(site, 4, WorkZone.DEFAULT_DEPTH), "Farm");
        farmer.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.0F, 0.0F);
        farmer.insertItem(new ItemStack(Items.CHEST));
        BlockPos chest = farmer.productionChestForTests();
        helper.assertTrue(chest != null, "the farmer's work chest");
        Container box = (Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.WHEAT, 40));
        box.setItem(5, new ItemStack(Items.WHEAT, 40));
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, heart.south(3), 0.0F);
        helper.assertTrue(courier != null && village.equals(courier.ownerId()), "a courier for the village");
        courier.setJob(StationTask.HAUL);
        courier.getInventoryItems().clear();
        courier.insertItem(new ItemStack(Items.BREAD, 4));
        helper.assertTrue(Couriers.employed(courier), "the courier is the storehouse's");
        java.util.List<String> runs = Couriers.refreshForTests(level, village);
        Kit.log("ec08 the run list: " + runs);
        int before = count(store, st -> st.is(Items.WHEAT));
        level.setDayTime(noon);
        final boolean[] onRun = { false }, carrying = { false }, looked = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod > 8500 || tod < 5600) level.setDayTime(noon);
            Villages.noteAttempt(village, level.getGameTime());      // (no building calls the courier away)
            // Its rounds, as its station brain has them whenever its queue is empty (on its break or not).
            if (t % 20 == 5 && courier.peekJob() == null) Couriers.work(courier, level);
            if (Couriers.onARun(courier)) onRun[0] = true;
            // Midday, on its run with the wheat on its back: it does not stop to bank (the folk looks for itself
            // every few seconds; the test looks too, on the real path).
            if (Couriers.onARun(courier) && courier.countCarried(st -> st.is(Items.WHEAT)) > 0) {
                carrying[0] = true;
                if (!looked[0]) {
                    looked[0] = true;
                    boolean set = PutAway.look(courier);
                    Kit.log("ec08 at " + t + " on its run with " + courier.countCarried(st -> st.is(Items.WHEAT)) + " wheat at midday: put away "
                        + set + " (" + PutAway.excused(courier) + ")");
                    helper.assertTrue(!set && PutAway.excused(courier) != null, "a courier on its run is let be");
                }
                if (puttingAway(courier)) {
                    helper.fail("a courier on its run stopped to bank — " + courier.debugLine());
                    return;
                }
            }
            if (t % 20 != 0) return;
            int stored = count(store, st -> st.is(Items.WHEAT)) - before;
            if (t % 400 == 0) Kit.log("ec08 @" + t + ": run " + Couriers.runOfForTests(courier) + ", carrying "
                + courier.countCarried(st -> st.is(Items.WHEAT)) + " wheat, " + stored + " in the storehouse — " + courier.debugLine());
            if (carrying[0] && stored >= 60) {
                Kit.log("ec08 the run seen through at " + t + ": " + stored + " wheat in the storehouse");
                helper.succeed();
            } else if (t >= 4800) {
                helper.fail("the run was not seen through: on a run " + onRun[0] + ", carried " + carrying[0] + ", " + stored
                    + " in the storehouse — " + courier.debugLine());
            }
        });
    }

    // ============================================================ ec09: tended fields grow faster

    /** A nine-by-nine field round a water hole at this spot, sown with wheat of this age (-1: every age, by turns); its middle. */
    private static BlockPos field(ServerLevel level, int x, int z, int age) {
        int n = 0;
        BlockPos mid = Kit.surface(level, x, z);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos g = mid.offset(dx, -1, dz);
                for (int up = 0; up <= 3; up++) level.setBlock(g.above(1 + up), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(g.below(), Blocks.DIRT.defaultBlockState(), 3);
                if (dx == 0 && dz == 0) {
                    level.setBlock(g, Blocks.WATER.defaultBlockState(), 3);
                    continue;
                }
                level.setBlock(g, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7), 3);
                level.setBlock(g.above(), Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE,
                    age >= 0 ? age : n++ % 8), 3);
            }
        }
        return mid;
    }

    /** The wheat's ages added up over a field. */
    private static int ages(ServerLevel level, BlockPos mid) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(mid.offset(-4, 0, -4), mid.offset(4, 0, 4))) {
            var st = level.getBlockState(p);
            if (st.is(Blocks.WHEAT)) n += st.getValue(net.minecraft.world.level.block.CropBlock.AGE);
        }
        return n;
    }

    @GameTest(template = EMPTY, timeoutTicks = 5200, batch = "ec09_tended_fields_grow")
    public static void ec09_tended_fields_grow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long noon = 24000L * 8 + 6000;
        level.setDayTime(noon);
        final int x = 360150;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        BlockPos tended = field(level, x + 16, Z, 0), wild = field(level, x - 16, Z, 0);
        // The farmer's field is tended; it stands still (no hands on the crops: the test is of the growing).
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(tended, 4, WorkZone.DEFAULT_DEPTH), "Home Fields");
        f.setNoAi(true);
        // The game's random tick speed at a known figure (another test may have left it at fifteen, and a
        // field of ripe wheat grows no further): six, so both fields are well short of ripe in the time.
        var rule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        final int was = rule.get();
        rule.set(6, level.getServer());
        int speed = rule.get();
        Kit.log("ec09 two fields of eighty wheat, sown at once; random tick speed " + speed + "; the town's fields grow at "
            + com.jrpetty.mcassistant.AssistantConfig.villageCropGrowth() + "x");
        final int[] most = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod > 9000 || tod < 3000) level.setDayTime(noon);
            most[0] = Math.max(most[0], Fields.picksForTests(f));
            if (t % 500 == 0) Kit.log("ec09 @" + t + ": tended " + ages(level, tended) + ", wild " + ages(level, wild) + " (growth picks "
                + Fields.picksForTests(f) + " a second; x" + Fields.multiplier(f) + ")");
            if (t >= 5000) {
                rule.set(was, level.getServer());
                int a = ages(level, tended), w = ages(level, wild);
                double ratio = a / (double) Math.max(1, w);
                Kit.log("ec09 after 5000 ticks: tended " + a + ", wild " + w + " — " + String.format("%.2f", ratio)
                    + "x; at most " + most[0] + " growth picks a second; " + Fields.word(f.ownerId()));
                helper.assertTrue(w > 0, "the wild field grows at its own pace: " + w);
                helper.assertTrue(ratio >= 1.5 && ratio <= 3.2, "the tended field grows about twice as fast: " + String.format("%.2f", ratio));
                helper.assertTrue(most[0] <= Fields.PICKS_MOST && most[0] >= 1, "at a handful of growth ticks a second: " + most[0]);
                helper.succeed();
            }
        });
    }

    // ============================================================ ec10: a farmer's half day

    /** Meals in these goods: food, and wheat a third of one. */
    private static double meals(Iterable<ItemStack> goods) {
        double n = 0;
        for (ItemStack st : goods) {
            if (st.isEmpty()) continue;
            if (st.is(Items.WHEAT)) n += st.getCount() / 3.0;
            else if (st.get(DataComponents.FOOD) != null) n += st.getCount();
        }
        return n;
    }

    private static java.util.List<ItemStack> contents(Container c) {
        java.util.List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) out.add(c.getItem(i));
        return out;
    }

    @GameTest(template = EMPTY, timeoutTicks = 24600, batch = "ec10_farmer_day")
    public static void ec10_farmer_day(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        // The game's own random tick speed (three), whatever another test left it at.
        var rule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        final int was = rule.get();
        rule.set(3, level.getServer());
        final long morning = 24000L * 9 + 1500;
        level.setDayTime(morning);
        final int x = 361600;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID village = f.ownerId();
        BlockPos mid = field(level, x + 16, Z, -1);                       // a field of eighty at every age, as a kept field is
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(mid, 4, WorkZone.DEFAULT_DEPTH), "Home Fields");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.CHEST));
        BlockPos chest = f.productionChestForTests();
        helper.assertTrue(chest != null, "its work chest by the field");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_HOE));
        f.insertItem(new ItemStack(Items.BREAD, 8));
        f.insertItem(new ItemStack(Items.WHEAT_SEEDS, 16));
        f.moveTo(mid.getX() + 5.5, mid.getY(), mid.getZ() + 0.5, 0.0F, 0.0F);
        // Wheat brought in, in the pack and every chest about (its work chest, the stores), bread as three.
        Predicate<ItemStack> wheat = st -> st.is(Items.WHEAT), bread = st -> st.is(Items.BREAD);
        java.util.function.IntSupplier grain = () -> f.countCarried(wheat) + inChests(level, x, Z, 40, wheat)
            + 3 * (f.countCarried(bread) + inChests(level, x, Z, 40, bread));
        final int start = grain.getAsInt();
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            // On its shift the day through: the field grows a whole day (day and night alike, under the open
            // sky), and the farmer is there to take what ripens.
            if (tod > 10500 || tod < 1000) level.setDayTime(morning);
            Villages.noteAttempt(village, level.getGameTime());          // (no building calls the farmer away)
            if (t % 50 != 0) return;
            double meals = (grain.getAsInt() - start) / 3.0, booked = Larder.todayIn(village)[0];
            if (t % 2000 == 0) Kit.log("ec10 @" + t + ": " + String.format("%.1f", meals) + " meals in, "
                + String.format("%.1f", booked) + " booked to the fields; the field " + ages(level, mid) + " — " + f.debugLine());
            if (t >= 24000) {
                rule.set(was, level.getServer());
                Kit.log("ec10 a day: " + String.format("%.1f", meals) + " meals brought in by one farmer on a field of eighty ("
                    + String.format("%.1f", booked) + " booked to the fields; the game's own pace would give about 11); "
                    + Fields.word(village) + "; " + Fields.careLine(f));
                // Twice the game's pace, less the walking: fifteen is enough for seven folk on two meals a day.
                helper.assertTrue(meals >= 15, "a farmer on a nine-by-nine brings in fifteen meals a day: "
                    + String.format("%.1f", meals));
                helper.succeed();
            }
        });
    }

    // ============================================================ ec11: the fisher's catch

    @GameTest(template = EMPTY, timeoutTicks = 6200, batch = "ec11_fisher_catch")
    public static void ec11_fisher_catch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long morning = 24000L * 9 + 2000;
        level.setDayTime(morning);
        final int x = 363130;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity fisher = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(fisher != null && fisher.ownerId() != null, "a village");
        BlockPos pond = Kit.surface(level, x + 12, Z);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(pond.offset(dx, -1, dz), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(pond.offset(dx, -2, dz), Blocks.WATER.defaultBlockState(), 3);
            }
        }
        fisher.assignPlot(WorkZone.around(pond, 6, WorkZone.DEFAULT_DEPTH), "the pond");
        fisher.setJob(StationTask.FISH);
        fisher.getInventoryItems().clear();
        fisher.insertItem(new ItemStack(Items.FISHING_ROD));
        fisher.insertItem(new ItemStack(Items.BREAD, 8));
        fisher.moveTo(pond.getX() + 0.5, pond.getY(), pond.getZ() + 5.5, 0.0F, 0.0F);
        Predicate<ItemStack> fish = st -> st.is(Items.COD) || st.is(Items.SALMON) || st.is(Items.TROPICAL_FISH) || st.is(Items.PUFFERFISH)
            || st.is(Items.COOKED_COD) || st.is(Items.COOKED_SALMON);
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod > 10500 || tod < 1000) level.setDayTime(morning);
            // Its daily break falls at an hour of its own (from its id): the clock goes past it, so the
            // quarter day measured is a working one (a fisher on its break for the first half once landed four).
            if (fisher.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            Villages.noteAttempt(fisher.ownerId(), level.getGameTime());
            if (t % 50 != 0) return;
            int caught = fisher.countCarried(fish) + inChests(level, x, Z, 40, fish);
            if (t % 1000 == 0) Kit.log("ec11 @" + t + ": " + caught + " fish — " + fisher.debugLine());
            if (t >= 6000) {
                Kit.log("ec11 a fisher's quarter day: " + caught + " fish (" + String.format("%.1f", caught * 4.0) + " a working day at this rate)");
                helper.assertTrue(caught >= 8, "a fisher by a pond lands fish at about a player's rate: " + caught + " in 6000 ticks");
                helper.succeed();
            }
        });
    }

    // ============================================================ ec12: the hunter

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "ec12_hunter_meat")
    public static void ec12_hunter_meat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 9 + 2000);
        final int x = 364240;
        Kit.hold(level, x, Z, 50);
        Kit.prepare(level, x, Z, 50);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity hunter = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hunter != null && hunter.ownerId() != null, "a village");
        BlockPos grounds = Kit.surface(level, x + 24, Z);
        for (net.minecraft.world.entity.animal.Animal a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new AABB(grounds).inflate(60, 32, 60))) a.discard();
        hunter.assignPlot(WorkZone.around(grounds, 20, WorkZone.DEFAULT_DEPTH), "Hunting Grounds");
        hunter.setJob(StationTask.HUNT);
        hunter.getInventoryItems().clear();
        hunter.insertItem(new ItemStack(Items.IRON_SWORD));
        hunter.insertItem(new ItemStack(Items.BREAD, 8));
        for (int i = 0; i < 5; i++) {
            net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
            pig.moveTo(grounds.getX() + 0.5 + i, grounds.getY(), grounds.getZ() + 3.5, 0.0F, 0.0F);
            level.addFreshEntity(pig);
        }
        for (int i = 0; i < 2; i++) {
            net.minecraft.world.entity.animal.Sheep sheep = net.minecraft.world.entity.EntityType.SHEEP.create(level);
            sheep.moveTo(grounds.getX() + 0.5 + i, grounds.getY(), grounds.getZ() - 3.5, 0.0F, 0.0F);
            level.addFreshEntity(sheep);
        }
        hunter.moveTo(grounds.getX() + 0.5, grounds.getY(), grounds.getZ() + 0.5, 0.0F, 0.0F);
        Predicate<ItemStack> meat = st -> st.is(Items.PORKCHOP) || st.is(Items.COOKED_PORKCHOP) || st.is(Items.MUTTON) || st.is(Items.COOKED_MUTTON);
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(hunter.ownerId(), level.getGameTime());
            if (t % 20 == 0 && hunter.peekJob() == null) hunter.huntForTests();
            if (t % 50 != 0) return;
            int pigs = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class, new AABB(grounds).inflate(50, 16, 50),
                net.minecraft.world.entity.LivingEntity::isAlive).size();
            int sheep = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Sheep.class, new AABB(grounds).inflate(50, 16, 50),
                net.minecraft.world.entity.LivingEntity::isAlive).size();
            int got = hunter.countCarried(meat) + inChests(level, x, Z, 50, meat);
            if (t % 500 == 0) Kit.log("ec12 @" + t + ": pigs " + pigs + ", sheep " + sheep + ", meat " + got + " — " + hunter.debugLine());
            if (pigs < 2 || sheep < 2) {
                helper.fail("the hunter took one of the last pair: pigs " + pigs + ", sheep " + sheep);
                return;
            }
            if (t >= 3800) {
                Kit.log("ec12 the hunt: " + got + " meat home; pigs " + pigs + " (of five), sheep " + sheep + " (of two)");
                helper.assertTrue(got >= 2, "the hunter brings meat home: " + got);
                helper.succeed();
            }
        });
    }

    // ============================================================ ec13: the watch grows with the town

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ec13_watch_grows")
    public static void ec13_watch_grows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        final int x = 365700;
        Kit.hold(level, x, Z, 24);
        Kit.prepare(level, x, Z, 24);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID village = f.ownerId();
        long now = level.getGameTime();
        double at15 = Mishap.watch(village, 1.0, 15, now), at32 = Mishap.watch(village, 2.0, 32, now), at60 = Mishap.watch(village, 4.0, 60, now);
        Mishap.record(village, now / 24000L, now, "fighting a zombie");
        Mishap.record(village, now / 24000L, now, "fighting a creeper");
        Mishap.record(village, now / 24000L, now, "in a fall");
        double raided = Mishap.watch(village, 1.0, 15, now);
        String line = Mishap.line(village, now / 24000L - 6, "over the last 7 days");
        Kit.log("ec13 the watch wanted: 15 folk " + at15 + ", 32 folk " + at32 + ", 60 folk " + at60 + "; 15 folk with two lost to monsters "
            + raided + "; the books: " + line);
        helper.assertTrue(at15 >= 15 / 8.0 && at32 >= 4.0 && at60 >= 7.5, "a guard to every eight folk: " + at15 + ", " + at32 + ", " + at60);
        helper.assertTrue(raided >= 2.0 && raided >= at15 * 1.5 - 0.01, "half again, and two at least, once monsters have killed: " + raided);
        helper.assertTrue(line != null && line.contains("3 died") && line.contains("fighting a zombie") && line.contains("in a fall"),
            "the books say what took them: " + line);
        // A raider left over from a raid that is over (summoned to stay, never despawning) goes at dawn.
        net.minecraft.world.entity.monster.Zombie straggler = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        straggler.moveTo(heart.getX() + 6.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
        straggler.setPersistenceRequired();
        straggler.addTag("mca_raider");
        level.addFreshEntity(straggler);
        int sent = com.jrpetty.mcassistant.entity.RaidStragglers.sweep(level);
        Kit.log("ec13 raid stragglers sent off at dawn: " + sent);
        helper.assertTrue(sent >= 1 && !straggler.isAlive(), "a raider in no raid goes at dawn: " + sent);
        helper.succeed();
    }

    // ============================================================ ec14: the work chest on the plot's own level

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ec14_chest_not_on_the_cliff")
    public static void ec14_chest_not_on_the_cliff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 5 + 4000);
        final int x = 364900;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        // The plot sixteen blocks east; between it and the town, over the plot's west edge, a bluff ten high.
        BlockPos mine = Kit.surface(level, x + 16, Z);
        for (int bx = x + 5; bx <= x + 13; bx++) {
            for (int bz = Z - 6; bz <= Z + 6; bz++) {
                for (int by = mine.getY(); by < mine.getY() + 10; by++) level.setBlock(new BlockPos(bx, by, bz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        f.setJob(StationTask.MINE);
        f.assignPlot(WorkZone.around(mine, 8, WorkZone.DEFAULT_DEPTH), "The Pit");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.CHEST));
        BlockPos chest = f.productionChestForTests();
        Kit.log("ec14 the plot at " + mine.toShortString() + ", the bluff's top at y " + (mine.getY() + 10) + "; the work chest at "
            + (chest == null ? "none" : chest.toShortString()));
        helper.assertTrue(chest != null && level.getBlockEntity(chest) instanceof Container, "a work chest goes down");
        helper.assertTrue(Math.abs(chest.getY() - mine.getY()) <= 3, "on the plot's own level, not the bluff's top: y " + chest.getY()
            + " against the plot's " + mine.getY());
        helper.succeed();
    }

    // ============================================================ ec15: a hand for the beds

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ec15_a_hand_for_the_beds")
    public static void ec15_a_hand_for_the_beds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        final int x = 360900;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        level.setDayTime(24000L * 4 + 2000);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 8);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null, "a village");
        helper.runAtTickTime(10, () -> {
            level.setDayTime(24000L * 4 + 2000);
            com.jrpetty.mcassistant.entity.TownJobs.atWork(level, v, "levelling", heart.offset(12, 0, 0), "levelling the ground");
            com.jrpetty.mcassistant.entity.TownJobs.atWork(level, v, "beds", heart.offset(0, 0, 12), "making up the beds");
            com.jrpetty.mcassistant.entity.TownJobs.atWork(level, v, "paths", heart.offset(-12, 0, 0), "laying a path");
            java.util.Map<String, Integer> at = new java.util.TreeMap<>();
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f) {
                    String doing = com.jrpetty.mcassistant.entity.TownJobs.doing(f);
                    if (doing != null) at.merge(doing, 1, Integer::sum);
                }
            }
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
            Kit.log("ec15 eight folk, three pieces of the town's work called: " + at);
            helper.assertTrue(at.getOrDefault("levelling the ground", 0) == 1, "one hand at the levelling: " + at);
            helper.assertTrue(at.getOrDefault("making up the beds", 0) == 1, "and one more for the beds, past the one in eight: " + at);
            helper.assertTrue(!at.containsKey("laying a path"), "but not a third for the paths: " + at);
            helper.succeed();
        });
    }
}
