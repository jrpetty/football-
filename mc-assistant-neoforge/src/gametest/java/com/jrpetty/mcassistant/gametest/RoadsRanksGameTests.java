package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.TalkMenu;
import com.jrpetty.mcassistant.client.TalkMenu.Category;
import com.jrpetty.mcassistant.client.TalkMenu.Context;
import com.jrpetty.mcassistant.client.TalkMenu.Entry;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Carters;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.Interviews;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Offices;
import com.jrpetty.mcassistant.entity.Police;
import com.jrpetty.mcassistant.entity.RoadPosts;
import com.jrpetty.mcassistant.entity.RoadsStage;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.TownPlanner;
import com.jrpetty.mcassistant.entity.TradeWagon;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WagonKind;
import com.jrpetty.mcassistant.entity.Wagons;
import com.jrpetty.mcassistant.entity.WatchRanks;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.Weave;
import com.jrpetty.mcassistant.entity.Wheelwrights;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.RoadItems;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [roles-e] Roads, wagons and the watch's ranks (Wheelwrights, Carters, WatchRanks, TownPlanner, PostStints, RoadPosts).
 *
 * <ul>
 * <li><b>re01</b>: a trading town wants a wagon: the wheelwright's work order is opened and its makings set by out of the
 *     stores (exactly); the wheelwright is paid and dressed for the post and stands at interview; it walks to the yard and
 *     works the order. Worst case: it dies mid-build; the order and its makings are kept (read again from the books, as
 *     after a restart); the next wheelwright takes the half-built wagon up and finishes it, and the wagon stands in the
 *     yard.</li>
 * <li><b>re02</b>: a battered wagon home in its yard: with no planks in the stores the wheelwright says so and waits; with
 *     planks and iron it mends it whole at its side, two planks a step and iron every third; then a player's paid shift as
 *     the wheelwright's hand mends it with its own planks and is paid.</li>
 * <li><b>re03</b>: the carter: the courier is reckoned the carter and is the town's first pick to carry; the panel's choice
 *     at interview takes the post; it is paid by the run out of the treasury (an empty treasury: the run counted, nothing
 *     paid; a hand not the carter: nothing); it wears its driving coat and holds its whip made from the stores on the
 *     bench, back into its pack after.</li>
 * <li><b>re04</b>: the watch's ranks and the roster: twelve guards have a captain and two sergeants, the most seasoned,
 *     each paid its step and marked in its kit, and the chronicle has them; the army's sergeants are the peacetime ones
 *     first; eleven guards keep one sergeant, the one with its stripes; in the Iron Age the constable is the captain (paid
 *     as the constable). The roster: a wall for every three, a rest day in a watch of four, every guard on the walls and on
 *     the streets in a week.</li>
 * <li><b>re05</b>: Inquiry: the constable is the most seasoned guard, and takes a reported case; with a case on its hands
 *     already, the next goes to a free guard.</li>
 * <li><b>re06</b>: the town planner of a town of thirteen with a hall: paid and dressed; answers what is being built next;
 *     others point to it; on a morning with a new plan it goes to the hall's table and sets it out in the chronicle, and
 *     the same plan is not set out twice.</li>
 * <li><b>re07</b>: the talk menu: each post's option only at its holder, the Trade and Town tabs still six options, the
 *     post's own suggested first, and every phrase understood.</li>
 * </ul>
 * Band x 2,100,000 to 2,119,999, z 66,000; a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class RoadsRanksGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 4;
    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);

    // ------------------------------------------------------------------ helpers

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A town of this many folk on flat ground at x (the first is its founder), in clear weather, at this time. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, long time, int folk, boolean fresh) {
        ServerLevel level = helper.getLevel();
        if (fresh) {
            Kit.reset(level);
            Kit.noLeftoverPlayers(level);
            Wagons.resetForTests();
            Shifts.resetForTests();
            level.setDayTime(time);
            level.setWeatherParameters(24000, 0, false, false);
        }
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 36);
        List<VillageFolkEntity> out = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village at " + x);
        out.add(first);
        for (int i = 1; i < folk; i++) {
            BlockPos at = Kit.surface(level, x + 2 + (i % 5), Z + 2 + i / 5);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && first.ownerId().equals(f.ownerId()), "folk " + i + " of the village");
            f.setAgeForTests(26 + i);
            f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
            out.add(f);
        }
        return out;
    }

    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    /** Nothing of this left in the town's stores. */
    private static void clearOf(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
        }
        Villages.forgetStock();
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static String history(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(" | ").append(e.text());
        return sb.toString();
    }

    /** By day, held there: past dusk, on to the next morning. */
    private static void keepDay(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        if (t > 10500L) level.setDayTime(level.getDayTime() - t + 24000L + 1500L);
    }

    private static int[] done(String order) {
        // "WAGON 12/300 visits 1 by Ada"
        try {
            String[] p = order.split(" ");
            String[] dn = p[1].split("/");
            return new int[]{ Integer.parseInt(dn[0]), Integer.parseInt(dn[1]), Integer.parseInt(p[3]) };
        } catch (RuntimeException e) {
            return new int[]{ -1, -1, -1 };
        }
    }

    // ============================================================ re01: the wheelwright's work order

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "re01_wheelwright")
    public static void re01_wheelwright(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2100000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 1500, 6, true);
        List<VillageFolkEntity> other = town(helper, x + 300, DAY + 1500, 2, false);
        UUID id = folk.get(0).ownerId(), far = other.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        int[] planks0 = new int[1];
        VillageFolkEntity[] first = new VillageFolkEntity[1], second = new VillageFolkEntity[1];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            Ledger.link(id, far);                                                 // a colony down the road: the town trades
            clearOf(level, id, PLANKS);
            clearOf(level, id, s -> s.is(Items.STICK));
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.STICK, 6));
            planks0[0] = stock(level, id, PLANKS);
            Wagons.buildForTests(level, v);
            String order = Wheelwrights.orderForTests(id);
            int planks1 = stock(level, id, PLANKS);
            Kit.log("re01 order '" + order + "'; planks " + planks0[0] + " -> " + planks1 + "; held " + Wheelwrights.heldForTests(id, Items.OAK_PLANKS));
            // No stable or trading post to shed a drawn wagon: a handcart, ten planks and four sticks.
            helper.assertTrue(order.startsWith("HANDCART 0/150"), "a trading town wants a wagon: the work order opened, not a wagon: " + order);
            helper.assertTrue(Wagons.owned(id) == null, "no wagon out of the air");
            helper.assertTrue(planks0[0] - planks1 == 10 && Wheelwrights.heldForTests(id, Items.OAK_PLANKS) == 10,
                "the makings set by out of the stores, exactly: " + (planks0[0] - planks1) + " planks out, " + Wheelwrights.heldForTests(id, Items.OAK_PLANKS) + " held");
            helper.assertTrue(Wheelwrights.heldForTests(id, Items.STICK) == 4 && stock(level, id, s -> s.is(Items.STICK)) == 2,
                "the sticks held with them");
            VillageFolkEntity w = Wheelwrights.holder(id);
            helper.assertTrue(w != null && !w.isElder(), "the town reckons a wheelwright");
            Offices.forgetForTests(id);
            Kit.log("re01 wheelwright " + w.displayNameCap() + " (" + w.stationTask() + "): " + Wealth.breakdown(w) + "; dresses as '" + Weave.roleForTests(w) + "'");
            helper.assertTrue(Wealth.breakdown(w).contains("as the wheelwright"), "paid for the post: " + Wealth.breakdown(w));
            helper.assertTrue("wheelwright".equals(Weave.roleForTests(w)), "dressed for the post: " + Weave.roleForTests(w));
            for (VillageFolkEntity f : folk) Interviews.keenForTests(f);       // the town's own who want it put themselves forward
            helper.assertTrue(RoadPosts.candidatesForTests(level, v, "wheelwright").size() >= 2, "the post goes to interview with two or more standing");
            first[0] = w;
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 0 || helper.getTick() % 20 != 0) return;
            keepDay(level);
            int[] d = done(Wheelwrights.orderForTests(id));
            if (phase[0] == 1) {
                // The wheelwright walks to the yard (no shed yet) and works the order there, a second at a time.
                if (d[0] < 5) {
                    if (helper.getTick() % 200 == 0) Kit.log("re01 @" + helper.getTick() + ": " + Wheelwrights.orderForTests(id) + "; doing "
                        + first[0].offWorkWhy() + "; status '" + Wheelwrights.statusForTests(id) + "'");
                    return;
                }
                helper.assertTrue(Wheelwrights.orderForTests(id).endsWith("by " + first[0].displayNameCap()) && d[2] >= 1,
                    "the wheelwright itself at the order: " + Wheelwrights.orderForTests(id));
                // Worst case: it dies at the bench. The half-built wagon and its makings are kept, read again from the books.
                first[0].kill();
                Wheelwrights.forgetForTests(id);
                String kept = Wheelwrights.orderForTests(id);
                int[] k = done(kept);
                Kit.log("re01 the wheelwright died at " + d[0] + "s; the books say '" + kept + "'");
                helper.assertTrue(k[0] >= 5 && k[1] == 150 && Wheelwrights.heldForTests(id, Items.OAK_PLANKS) == 10,
                    "the half-built handcart and its makings kept: " + kept + ", " + Wheelwrights.heldForTests(id, Items.OAK_PLANKS) + " planks held");
                second[0] = Wheelwrights.holder(id);
                helper.assertTrue(second[0] != null && second[0] != first[0], "another wheelwright reckoned");
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                if (!Wheelwrights.orderForTests(id).endsWith("by " + second[0].displayNameCap())) return;
                helper.assertTrue(told(id, "took up the half-built handcart"), "the new wheelwright takes it up where it was left: " + history(id));
                // Its work done (the rest of it at once): the wagon in the yard, the order closed.
                Wheelwrights.workForTests(id, 1000, level.getDayTime() / 24000L);
                Wheelwrights.stepForTests(level, v);
                Kit.log("re01 finished: owned " + Wagons.owned(id) + "; order '" + Wheelwrights.orderForTests(id) + "'; planks "
                    + stock(level, id, PLANKS) + "; " + history(id));
                helper.assertTrue(Wagons.owned(id) == WagonKind.HANDCART && Wagons.wagonForTests(level, id) != null, "the handcart stands in the yard");
                helper.assertTrue(Wheelwrights.orderForTests(id).isEmpty(), "the order closed");
                helper.assertTrue(told(id, "built the town its first handcart"), "the chronicle has it: " + history(id));
                helper.succeed();
            }
        });
    }

    // ============================================================ re02: mending, and the wheelwright's hand

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "re02_mending")
    public static void re02_mending(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2102000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 1500, 5, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        TradeWagon[] wagon = new TradeWagon[1];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            clearOf(level, id, s -> s.is(ItemTags.PLANKS) || s.is(Items.IRON_INGOT));
            wagon[0] = Wagons.giveForTests(level, id, WagonKind.WAGON);
            helper.assertTrue(wagon[0] != null, "a wagon in the yard");
            Wheelwrights.batterForTests(wagon[0], 10.0F);
            helper.assertTrue(Wheelwrights.holder(id) != null, "the town with a wagon keeps a wheelwright");
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 0 || helper.getTick() % 20 != 0) return;
            keepDay(level);
            TradeWagon w = wagon[0];
            if (phase[0] == 1) {
                // No planks in the stores: it says so, and the wagon waits.
                String why = Wheelwrights.statusForTests(id);
                if (!why.contains("no planks")) {
                    if (helper.getTick() % 200 == 0) Kit.log("re02 @" + helper.getTick() + " status '" + why + "', hp " + Wheelwrights.hpForTests(w)
                        + "; " + Wheelwrights.debugForTests(level, id));
                    return;
                }
                helper.assertTrue(Wheelwrights.hpForTests(w) == 10.0F, "nothing mended out of nothing: " + Wheelwrights.hpForTests(w));
                chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.SPRUCE_PLANKS, 20), new ItemStack(Items.IRON_INGOT, 2));
                Wheelwrights.restedForTests();                                    // its rest after the stint let go: on with it
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                if (Wheelwrights.hpForTests(w) < w.kind().health) {
                    if (helper.getTick() % 200 == 0) Kit.log("re02 @" + helper.getTick() + " mending: hp " + Wheelwrights.hpForTests(w)
                        + "; " + Wheelwrights.debugForTests(level, id));
                    return;
                }
                int planks = stock(level, id, PLANKS), iron = stock(level, id, s -> s.is(Items.IRON_INGOT));
                Kit.log("re02 mended whole: planks " + planks + ", iron " + iron + "; " + history(id));
                // (The town's other hands may draw planks from the stores meanwhile: the wheelwright's own count is its chronicle line.)
                helper.assertTrue(planks <= 12 && iron <= 1, "the mending's planks and iron came out of the stores: " + planks + " planks, " + iron + " iron left");
                helper.assertTrue(told(id, "mended the town's wagon, with 8 planks and 1 iron"), "two planks a step for four steps, iron at the third: "
                    + history(id));
                // A player's paid shift as the wheelwright's hand: the wagon battered again, mended with the player's own planks.
                Wheelwrights.batterForTests(w, 16.0F);
                Ledger.addCoins(id, 200);
                ServerPlayer pl = helper.makeMockServerPlayerInLevel();
                pl.getAbilities().instabuild = false;
                pl.teleportTo(w.getX() + 2.0, w.getY(), w.getZ());
                String took = Shifts.take(pl, id, ShiftRole.WAGON.id, false);
                Shifts.secondForTests(level, pl);
                String task = Shifts.taskForTests(pl);
                pl.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.OAK_PLANKS, 6));
                boolean a = Wheelwrights.mendByHandForTests(level, w, pl), b = Wheelwrights.mendByHandForTests(level, w, pl);
                int[] st = Shifts.stateForTests(pl);
                Kit.log("re02 shift: took '" + took + "'; task '" + task + "'; " + a + "/" + b + "; hp " + Wheelwrights.hpForTests(w) + "; state "
                    + java.util.Arrays.toString(st) + "; " + Shifts.lastForTests(pl));
                int left = pl.getMainHandItem().getCount();
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(took.isEmpty() && task != null && task.contains("Mend the marked wagon"), "the wheelwright's hand's task: " + took + " / " + task);
                helper.assertTrue(a && b && Wheelwrights.hpForTests(w) == w.kind().health && left == 2, "two steps of two planks, the player's own: " + left + " left");
                helper.assertTrue(st != null && st[0] >= 1 && st[1] > 0, "the task done and paid: " + java.util.Arrays.toString(st));
                helper.succeed();
            }
        });
    }

    // ============================================================ re03: the carter

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "re03_carter")
    public static void re03_carter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2104000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 6, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity courier = folk.get(2);
            courier.setJob(StationTask.HAUL);
            courier.keepTradeForTests();
            helper.assertTrue(!Carters.stands(id) && Carters.holder(id) == null, "no wagon, no carter");
            TradeWagon w = Wagons.giveForTests(level, id, WagonKind.WAGON);
            helper.assertTrue(w != null, "a wagon in the yard");
            Carters.forgetForTests(id);
            helper.assertTrue(Carters.holder(id) == courier, "the courier reckoned the carter: " + (Carters.holder(id) == null ? "nobody" : Carters.holder(id).displayNameCap()));
            // The carter is the town's first pick to carry, though another hand stands nearer the heart.
            courier.moveTo(heart.getX() + 24.5, heart.getY(), heart.getZ() + 0.5);
            helper.assertTrue(Carters.chooseForTests(v) == courier, "the carter carries before anybody");
            // Chosen at interview: the panel's choice takes the post and keeps it.
            for (VillageFolkEntity f : folk) Interviews.keenForTests(f);       // the town's own who want it put themselves forward
            List<VillageFolkEntity> standing = RoadPosts.candidatesForTests(level, v, "carter");
            VillageFolkEntity chosen = null;
            for (VillageFolkEntity f : standing) if (f != courier) { chosen = f; break; }
            helper.assertTrue(standing.size() >= 2 && chosen != null, "two or more stand for the post: " + standing.size());
            String given = RoadPosts.chooseForTests(level, v, "carter", chosen);
            Carters.forgetForTests(id);
            helper.assertTrue(Carters.holder(id) == chosen && given.contains("drives the town's wagon"), "the panel's choice drives: " + given);
            helper.assertTrue("carter".equals(Weave.roleForTests(chosen)), "in its driving coat");
            // Paid by the run, out of the treasury.
            Ledger.addCoins(id, 100);
            int purse = chosen.purse(), coins = Ledger.coins(id);
            Carters.tripHomeForTests(level, chosen, id, id);
            int fee = Carters.feeForTests(com.jrpetty.mcassistant.entity.JobWorth.dayUnit(chosen), 0.0);
            Kit.log("re03 the run paid " + (chosen.purse() - purse) + " (fee " + fee + "); treasury " + coins + " -> " + Ledger.coins(id)
                + "; record " + java.util.Arrays.toString(Carters.record(id)));
            helper.assertTrue(chosen.purse() - purse == fee && coins - Ledger.coins(id) == fee && fee >= 1, "the fee out of the treasury into its purse");
            helper.assertTrue(Carters.record(id)[0] == 1 && Carters.record(id)[1] == fee, "the run in the books");
            int coursePurse = courier.purse();
            Carters.tripHomeForTests(level, courier, id, id);
            helper.assertTrue(courier.purse() == coursePurse && Carters.record(id)[0] == 1, "a hand not the carter is not paid by the run");
            Ledger.takeCoins(id, Ledger.coins(id));
            int before = chosen.purse();
            Carters.tripHomeForTests(level, chosen, id, id);
            helper.assertTrue(chosen.purse() == before && Carters.record(id)[0] == 2, "an empty treasury: the run counted, nothing paid");
            // The whip: made from the stores for the bench, back into its pack after.
            clearOf(level, id, s -> s.is(Items.STICK) || s.is(Items.STRING) || s.is(Items.LEATHER));
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.STICK, 2), new ItemStack(Items.STRING, 2), new ItemStack(Items.LEATHER, 2));
            Carters.drivingForTests(chosen, w, true);
            Carters.whipForTests(level, chosen);
            boolean held = chosen.getItemBySlot(EquipmentSlot.OFFHAND).is(RoadItems.DRIVING_WHIP.get());
            int sticks = stock(level, id, s -> s.is(Items.STICK)), leather = stock(level, id, s -> s.is(Items.LEATHER));
            Carters.drivingForTests(chosen, w, false);
            Carters.whipForTests(level, chosen);
            boolean packed = chosen.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty() && chosen.countCarried(s -> s.is(RoadItems.DRIVING_WHIP.get())) == 1;
            String talk = Carters.talkForTests(chosen);
            Kit.log("re03 whip on the bench " + held + " (stores: " + sticks + " sticks, " + leather + " leather), packed after " + packed + "; talk: " + talk);
            helper.assertTrue(held && sticks == 1 && leather == 1, "its whip in hand on the bench, made of the stores' stick, string and leather");
            helper.assertTrue(packed, "off the bench, the whip back in its pack");
            helper.assertTrue(talk.contains("in the yard") && talk.contains("2 runs"), "it tells where the wagon is and its runs: " + talk);
            helper.succeed();
        });
    }

    // ============================================================ re04: the ranks and the roster

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "re04_ranks")
    public static void re04_ranks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2106000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 14, true);
        Police.standDownForTests(false);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            List<VillageFolkEntity> guards = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                VillageFolkEntity g = folk.get(i);
                g.setJob(StationTask.GUARD);
                g.keepTradeForTests();
                g.setShift(AssistantEntity.Shift.ALWAYS);
                AdminTools.skill(g, 4 + 2 * i);
                guards.add(g);
            }
            VillageFolkEntity top = guards.get(11), second = guards.get(10), third = guards.get(9), low = guards.get(0);
            Map<UUID, String> roster = WatchRanks.drawForTests(level, v);
            Set<UUID> sergeants = WatchRanks.sergeants(id);
            Kit.log("re04 roster " + roster.values() + "; captain " + WatchRanks.captainForTests(id) + " (top " + top.getUUID() + "); sergeants "
                + sergeants + " (" + second.getUUID() + ", " + third.getUUID() + ")");
            helper.assertTrue(roster.size() == 12, "every guard on the roster: " + roster.size());
            helper.assertTrue(top.getUUID().equals(WatchRanks.captainForTests(id)), "the most seasoned guard captains the watch");
            helper.assertTrue(sergeants.size() == 2 && sergeants.contains(second.getUUID()) && sergeants.contains(third.getUUID()),
                "twelve guards: two sergeants, the most seasoned after the captain");
            helper.assertTrue(told(id, second.displayNameCap() + " was made a sergeant of the watch"), "the chronicle has it: " + history(id));
            Offices.forgetForTests(id);
            Kit.log("re04 pay: captain " + Wealth.breakdown(top) + "; sergeant " + Wealth.breakdown(second) + "; guard " + Wealth.breakdown(low));
            helper.assertTrue(Wealth.breakdown(second).contains("as a sergeant of the watch") && Wealth.breakdown(top).contains("as the captain of the watch")
                && !Wealth.breakdown(low).contains("sergeant") && !Wealth.breakdown(low).contains("captain"), "a pay step for each rank");
            int fs = WatchRanks.flagsForTests(level, second), fl = WatchRanks.flagsForTests(level, low), fc = WatchRanks.flagsForTests(level, top);
            helper.assertTrue((fs & (1 << 8)) != 0 && (fl & (1 << 8)) == 0 && (fc & (1 << 5)) != 0, "the kit marks the ranks: " + fs + ", " + fl + ", " + fc);
            List<UUID> field = WatchRanks.fieldOrderForTests(id, List.of(low.getUUID(), third.getUUID(), guards.get(1).getUUID(), second.getUUID()));
            helper.assertTrue(sergeants.contains(field.get(0)) && sergeants.contains(field.get(1)), "the army's sergeants are the watch's first");
            String words = String.join(" / ", RoadPosts.lines(level, v));
            helper.assertTrue(words.contains("sergeants " + second.displayNameCap()) || words.contains(third.displayNameCap()), "the books name them: " + words);
            // Eleven guards: one sergeant, the one that has its stripes and is most seasoned.
            low.setJob(StationTask.FARM);
            WatchRanks.drawForTests(level, v);
            Set<UUID> now = WatchRanks.sergeants(id);
            helper.assertTrue(now.size() == 1 && now.contains(second.getUUID()), "eleven guards keep one sergeant: " + now);
            // The Iron Age: the constable (the most seasoned) is the captain, paid as the constable, not twice.
            Villages.ageForTests(id, Villages.Age.IRON);
            WatchRanks.drawForTests(level, v);
            Offices.forgetForTests(id);
            helper.assertTrue(top == WatchRanks.constableForTests(id) && top.getUUID().equals(WatchRanks.captainForTests(id)), "the constable captains the watch");
            helper.assertTrue(Wealth.breakdown(top).contains("as the constable") && !Wealth.breakdown(top).contains("captain of the watch"),
                "paid as the constable: " + Wealth.breakdown(top));
            // The roster itself: a wall for every three, one resting in a watch of four or more, the turn going round.
            List<String> seven = WatchRanks.dutiesForTests(level, v, 7, 3);
            long walls = seven.stream().filter("WALLS"::equals).count(), rest = seven.stream().filter("REST"::equals).count();
            helper.assertTrue(seven.size() == 7 && walls == 2 && rest == 1, "seven guards: two on the walls, one resting: " + seven);
            List<String> one0 = WatchRanks.dutiesForTests(level, v, 1, 4), one1 = WatchRanks.dutiesForTests(level, v, 1, 5);
            helper.assertTrue(!one0.equals(one1) && one0.size() == 1, "a watch of one: the walls one day, the streets the next: " + one0 + one1);
            Set<UUID> defence = new HashSet<>(), police = new HashSet<>();
            long today = level.getDayTime() / 24000L;
            for (int d = 0; d < 7; d++) {
                for (Map.Entry<UUID, String> e : WatchRanks.rosterOnForTests(level, v, today + d).entrySet()) {
                    if (e.getValue().equals("WALLS")) defence.add(e.getKey());
                    if (Set.of("BEAT", "DESK", "CASES", "ESCORT", "EVENT").contains(e.getValue())) police.add(e.getKey());
                }
            }
            int both = 0;
            for (VillageFolkEntity g : guards) if (g.stationTask() == StationTask.GUARD && defence.contains(g.getUUID()) && police.contains(g.getUUID())) both++;
            Kit.log("re04 a week: " + both + " of 11 on the walls and the streets both");
            helper.assertTrue(both == 11, "in a week every guard stands on the walls and walks the streets: " + both + " of 11");
            Police.standDownForTests(true);
            helper.succeed();
        });
    }

    // ============================================================ re05: Inquiry

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "re05_inquiry")
    public static void re05_inquiry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2108000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 7, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.IRON);
            List<VillageFolkEntity> guards = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                VillageFolkEntity g = folk.get(i);
                g.setJob(StationTask.GUARD);
                g.keepTradeForTests();
                AdminTools.skill(g, 30 - 10 * i);
                guards.add(g);
            }
            VillageFolkEntity constable = WatchRanks.constableForTests(id);
            helper.assertTrue(constable == guards.get(0), "the constable is the most seasoned of the watch");
            VillageFolkEntity culprit = folk.get(5), victim = folk.get(6);
            victim.earn(20);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "a purse picked");
            Crime.reportForTests(level, c);
            UUID took = WatchRanks.assignForTests(level, v, c);
            Kit.log("re05 the first case to " + took + " (constable " + constable.getUUID() + ")");
            helper.assertTrue(constable.getUUID().equals(took), "the constable takes the case");
            victim.earn(20);
            Crime.Case c2 = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c2 != null, "a second purse picked");
            Crime.reportForTests(level, c2);
            UUID took2 = WatchRanks.assignForTests(level, v, c2);
            Kit.log("re05 the second case to " + took2);
            helper.assertTrue(took2 != null && !took2.equals(constable.getUUID())
                && (took2.equals(guards.get(1).getUUID()) || took2.equals(guards.get(2).getUUID())), "with a case on its hands, the next goes to a free guard");
            helper.succeed();
        });
    }

    // ============================================================ re06: the town planner

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "re06_planner")
    public static void re06_planner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2110000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 3000, 13, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity[] planner = new VillageFolkEntity[1];
        long[] morning = new long[1];
        boolean[] atTable = new boolean[1];
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            helper.assertTrue(!TownPlanner.stands(id), "no hall, no planner");
            BlockPos hall = Kit.surface(level, heart.getX(), heart.getZ() - 18);
            BuildGoal.stamp(level, "hall", hall, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "hall", hall, Direction.NORTH);
            TownPlanner.forgetForTests(id);
            VillageFolkEntity p = TownPlanner.holder(id);
            helper.assertTrue(p != null && !p.isElder(), "a town of thirteen with a hall keeps a planner");
            Offices.forgetForTests(id);
            helper.assertTrue(Wealth.breakdown(p).contains("as the town planner") && "planner".equals(Weave.roleForTests(p)),
                "paid and dressed for it: " + Wealth.breakdown(p) + " / " + Weave.roleForTests(p));
            String said = TownPlanner.talkForTests(p);
            VillageFolkEntity other = folk.get(1) == p ? folk.get(2) : folk.get(1);
            String elsewhere = TownPlanner.talkForTests(other);
            Kit.log("re06 the planner " + p.displayNameCap() + ": " + said + " / another: " + elsewhere);
            helper.assertTrue(said.contains("Next is") || said.contains("Nothing new"), "it says what goes up next: " + said);
            helper.assertTrue(elsewhere.contains("Ask " + p.displayNameCap()), "others point to it: " + elsewhere);
            planner[0] = p;
            // The next morning, with the plan new to it: to the hall's table, and set out.
            Ledger.note(id, "planner.next", "");
            level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 1100L);
            morning[0] = level.getDayTime() / 24000L;
        });
        helper.onEachTick(() -> {
            if (planner[0] == null || helper.getTick() % 20 != 0) return;
            String doing = planner[0].offWorkWhy();
            if (doing.contains("post")) atTable[0] = true;
            if (!Long.toString(morning[0]).equals(Ledger.note(id, "planner.day"))) {
                if (helper.getTick() % 200 == 0) Kit.log("re06 @" + helper.getTick() + " " + planner[0].displayNameCap() + ": '" + doing + "'");
                return;
            }
            Kit.log("re06 set out (at its post's work seen: " + atTable[0] + "): " + history(id));
            helper.assertTrue(told(id, planner[0].displayNameCap() + " the town planner set out the plans for"), "the plan in the chronicle: " + history(id));
            helper.assertTrue(atTable[0], "it went to the hall's table for it");
            helper.assertTrue(!TownPlanner.announceForTests(level, v), "the same plan is not set out twice");
            helper.succeed();
        });
    }

    // ============================================================ re07: the talk

    private static boolean shows(Category c, Context x, String id) {
        for (Entry e : TalkMenu.visible(c.entries(), x)) {
            if (e.id().equals(id)) return true;
            if (e.isGroup()) for (Entry k : TalkMenu.visible(e.children(), x)) if (k.id().equals(id)) return true;
        }
        return false;
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "re07_talk")
    public static void re07_talk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Context plain = Context.of("v1 trade=SHOP standing=FRIEND", 0, false, false, false);
        Context wheel = Context.of("v1 trade=SHOP standing=FRIEND wheelwright", 0, false, false, false);
        Context carter = Context.of("v1 trade=HAUL standing=FRIEND carter", 0, false, false, false);
        Context planner = Context.of("v1 trade=FARM standing=FRIEND planner", 0, false, false, false);
        helper.assertTrue(shows(Category.TRADE, wheel, "wagon_work") && !shows(Category.TRADE, plain, "wagon_work"), "the wagon's work: the wheelwright's");
        helper.assertTrue(shows(Category.TRADE, carter, "carter_road") && !shows(Category.TRADE, plain, "carter_road"), "the wagon's road: the carter's");
        helper.assertTrue(shows(Category.TOWN, planner, "planner_next") && !shows(Category.TOWN, plain, "planner_next"), "the plans: the planner's");
        for (Context c : List.of(wheel, carter, planner)) {
            for (Category cat : Category.values()) {
                int n = TalkMenu.visible(cat.entries(), c).size();
                helper.assertTrue(n >= 3 && n <= 6, cat + " has " + n + " options of its own for " + c);
            }
        }
        helper.assertTrue(TalkMenu.suggested(wheel).stream().anyMatch(e -> e.id().equals("wagon_work"))
            && TalkMenu.suggested(carter).stream().anyMatch(e -> e.id().equals("carter_road"))
            && TalkMenu.suggested(planner).stream().anyMatch(e -> e.id().equals("planner_next")), "each post's own suggested");
        // Every phrase is heard, by the holder or by anybody else (who points to the holder).
        List<VillageFolkEntity> folk = town(helper, 2112000, DAY + 2000, 3, true);
        VillageFolkEntity f = folk.get(1);
        for (String phrase : List.of("How's the wagon coming on?", "Where's the wagon bound?", "What's being built next?")) {
            String said = RoadPosts.talk(f, null, phrase);
            Kit.log("re07 '" + phrase + "' -> " + said);
            helper.assertTrue(said != null && !said.isEmpty(), "heard: " + phrase);
        }
        helper.assertTrue(RoadPosts.talk(f, null, "Lovely weather") == null, "and nothing else taken for it");
        // The pictures' stage sets out its six views (the shed, its bench, the mending, the carter, the posts, the ranks).
        BlockPos stage = Kit.surface(level, 2112000 + 120, Z);
        Kit.hold(level, stage.getX(), stage.getZ() + 20, 48);
        List<String> views = RoadsStage.stage(level, stage);
        long n = views.stream().filter(l -> l.startsWith("VIEW ")).count();
        Kit.log("re07 the stage: " + views);
        helper.assertTrue(n == 6, "six views staged: " + views);
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(stage).inflate(64), e -> e.getTags().contains("folk_lineup"))) e.discard();
        helper.succeed();
    }
}
