package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.ArmyInns;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Battles;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.FishMarket;
import com.jrpetty.mcassistant.entity.Fleet;
import com.jrpetty.mcassistant.entity.Harbours;
import com.jrpetty.mcassistant.entity.Knights;
import com.jrpetty.mcassistant.entity.KnightManors;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PierFleet;
import com.jrpetty.mcassistant.entity.Prisoners;
import com.jrpetty.mcassistant.entity.RoadWorks;
import com.jrpetty.mcassistant.entity.Roadside;
import com.jrpetty.mcassistant.entity.ShipCalls;
import com.jrpetty.mcassistant.entity.TradeWorld;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.WarRoom;
import com.jrpetty.mcassistant.entity.WarRoomRealm;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.entity.Waterfront;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [realm-polish] The realm's newer works meeting one another: armies resting at the roadside inns, the fishing fleet at
 * the harbour's pier, and the war room's map table showing the lot.
 *
 * <ul>
 * <li><b>rp01</b>: peace made while an army is in the field; marching home after dark it passes its own town's inn and
 *     stops there for the night: its men fed out of the larder, its officers in the inn's beds (asleep), the treasury paying
 *     the keeper at the inn's prices, the inn's books and both war book and chronicle telling it; at dawn it marches on and,
 *     home, pays the inn's rent it carried into the treasury.</li>
 * <li><b>rp02</b>: an army marching on a town that holds its walls passes the enemy's inn and plunders its larder (eaten,
 *     and carried off in its supply), not a coin paid; the inn's town hears of it; the march goes on to the siege camp; and
 *     the keeper bars its door to that town after the peace.</li>
 * <li><b>rp03</b>: the rules: an army at war with either of an inn's towns is not served there (an enemy of the town that
 *     built it plunders); a march home by day stops only for a meal, no rooms, paid for, and goes on.</li>
 * <li><b>rp04</b>: three towns in a row: an army marching to battle at night rests at a neutral town's inn on its way;
 *     the other army, at its ground first, makes camp; at dawn the first marches on, and the battle is set for the
 *     daylight, within the hour.</li>
 * <li><b>rp05</b>: a harbour town's fleet at its jetty moves to its pier once the pier is finished: the old boats hauled up
 *     into the stores and put in at the pier's landward side, the old fish stall taken down into the stores and put up by
 *     the pier; the fishers board from the deck, go out, come home to it and land the catch there, counted by the
 *     harbourmaster on the Harbour page; a town with no pier keeps its jetty; the war room shows the harbour and a ship.</li>
 * <li><b>rp06</b>: the war room's map: the road between two towns by each half's grade, the inn on it, a knight's land, a
 *     wonder, the prisoners held on the town's card; another town's things hidden till the town is met.</li>
 * </ul>
 * Band x 2,320,000 to 2,339,999, z 70,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class RealmPolishGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;
    private static final long DAY = 40L * 24000L;

    // ------------------------------------------------------------------ helpers

    private static void fresh(ServerLevel level, long time) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        TradeWorld.resetForTests();
        Visitors.resetForTests();
        WarAndPeace.resetForTests();
        Campaigns.resetForTests();
        Fleet.resetForTests();
        Waterfront.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
    }

    /** Flat grass round here, clear air above it. */
    private static void flat(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -4; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    private static void sea(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -5; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    net.minecraft.world.level.block.state.BlockState want = dy < -4 ? Blocks.SAND.defaultBlockState()
                        : dy < 0 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (!level.getBlockState(p).equals(want)) level.setBlock(p, want, 2 | 16);
                }
            }
        }
    }

    /** A town of this many folk round x on flat ground (the first is its founder). */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, int y, int folk) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> out = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, new BlockPos(x, y, Z), 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village at " + x);
        out.add(first);
        for (int i = 1; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 2 + (i % 5), y, Z + 2 + i / 5), 0.0F);
            helper.assertTrue(f != null && first.ownerId().equals(f.ownerId()), "folk " + i + " of the village");
            f.setAgeForTests(26 + i);
            out.add(f);
        }
        return out;
    }

    /** Towns at these x's on flat ground with a flat strip between, the ground held. */
    private static List<List<VillageFolkEntity>> towns(GameTestHelper helper, int[] xs, int[] folk) {
        ServerLevel level = helper.getLevel();
        int y = Kit.surface(level, xs[0], Z).getY();
        int last = xs[xs.length - 1];
        for (int cx = xs[0]; cx <= last; cx += 120) {
            Kit.hold(level, cx, Z, 64);
            Kit.prepare(level, cx, Z, 64);
        }
        Kit.hold(level, last, Z, 64);
        Kit.prepare(level, last, Z, 64);
        for (int x : xs) flat(level, x - 44, x + 44, Z - 44, Z + 44, y);
        flat(level, xs[0] + 44, last - 44, Z - 14, Z + 14, y);
        List<List<VillageFolkEntity>> out = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) out.add(town(helper, xs[i], y, folk[i]));
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

    /**
     * The inn halfway along the road between these two, put up by {@code builder} (made the richer, the Stone Age, its
     * half a path) out of a chest of timber and stone in its stores, and opened, its larder stocked with bread.
     */
    private static BlockPos buildInn(GameTestHelper helper, int x0, int x1, UUID builder, UUID other) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = Villages.get(builder);
        int y = Kit.surface(level, v.centre().getX(), Z).getY();
        int mid = (x0 + x1) / 2;
        Kit.hold(level, mid, Z, 64);
        flat(level, mid - 60, mid + 60, Z - 34, Z + 34, y);
        Villages.ageForTests(builder, Villages.Age.STONE);
        RoadWorks.setHalfForTests(builder, other, 1, 0, 6);
        RoadWorks.setHalfForTests(other, builder, 1, 0, 6);
        Ledger.takeCoins(other, Ledger.coins(other));
        Ledger.addCoins(builder, 300);
        int cx = v.centre().getX();
        chestAt(level, new BlockPos(cx - 3, y, Z - 5), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64),
            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STONE_BRICKS, 64),
            new ItemStack(Items.GLASS, 32), new ItemStack(Items.GLASS_PANE, 32), new ItemStack(Items.TORCH, 32),
            new ItemStack(Items.WHITE_WOOL, 16), new ItemStack(Items.CHEST, 4));
        chestAt(level, new BlockPos(cx - 5, y, Z - 5), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 32), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.RED_BED, 4), new ItemStack(Items.BARREL, 2));
        Roadside.considerForTests(level, v);
        for (int i = 0; i < 80 && !Roadside.stateForTests(builder, other).startsWith("open"); i++) Roadside.tickForTests(level, v);
        helper.assertTrue(Roadside.stateForTests(builder, other).startsWith("open") && Roadside.stateForTests(builder, other).endsWith("kept"),
            "the inn open and kept: " + Roadside.stateForTests(builder, other));
        BlockPos inn = Roadside.anchorForTests(builder, other);
        Kit.log("rp inn of " + Villages.name(builder) + " at " + inn.toShortString() + ", larder " + Roadside.larderForTests(level, builder, other));
        return inn;
    }

    /** So many of the town's grown folk (never the elder) set to the watch and armed. */
    private static void guards(UUID v, int n) {
        UUID e = Villages.elder(v);
        int k = 0;
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (k >= n) break;
            if (!(a instanceof VillageFolkEntity f) || f.getUUID().equals(e) || f.isBaby()) continue;
            f.ensurePersona();
            f.setJob(AssistantEntity.StationTask.GUARD);
            f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            f.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            k++;
        }
    }

    private static void war(ServerLevel level, UUID a, UUID b) {
        long day = level.getDayTime() / 24000L;
        Ledger.relate(a, b, -70 - Ledger.relation(a, b));
        WarAndPeace.declare(level, a, b, day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
        WarAndPeace.backdateForTests(a, b, 3);
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

    /** Seconds of the campaign now until army i halts at an inn (at most so many). */
    private static String marchToInn(ServerLevel level, Campaigns.Campaign c, int i, int most) {
        for (int k = 0; k < most && ArmyInns.haltForTests(c, i).isEmpty(); k++) ArmyInns.secondForTests(level, c);
        return ArmyInns.haltForTests(c, i);
    }

    // ============================================================ rp01: the night at its own town's inn, on the way home

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "rp01_inn_night")
    public static void rp01_inn_night(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2320000, far = 440;
        fresh(level, DAY + 1200L);
        List<List<VillageFolkEntity>> ts = towns(helper, new int[]{ x, x + far }, new int[]{ 14, 3 });
        UUID a = ts.get(0).get(0).ownerId(), b = ts.get(1).get(0).ownerId();
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        long[] at = new long[4];
        int[] phase = { 0 }, carried = { 0 };
        helper.runAtTickTime(5, () -> {
            BlockPos inn = buildInn(helper, x, x + far, a, b);
            guards(a, 6);
            war(level, a, b);
            Campaigns.hurryForTests(false);
            Campaigns.watchForTests(false);
            Battles.deathsForTests(0);
            Campaigns.Campaign c = Campaigns.beginForTests(level, va, vb, false, false);
            helper.assertTrue(c != null, "a campaign begun");
            Campaigns.nextForTests(level, c);                                     // the muster
            helper.assertTrue(c.army(0) != null && c.army(0).size() >= 4, "the army raised: " + (c.army(0) == null ? 0 : c.army(0).size()));
            // Peace is made while the army is in the field, and it marches home after dark past its own town's inn.
            Wars.end(a, b);
            Ledger.addCoins(a, 200);
            level.setDayTime(DAY + 14000L);
            ArmyInns.homeFromForTests(level, c, 0, new BlockPos(inn.getX() + 70, inn.getY(), Z));
            double plan = ArmyInns.plannedForTests(c, 0);
            Kit.log("rp01 home from " + (inn.getX() + 70) + ": the inn planned " + (int) plan + " along; way " + ArmyInns.wayForTests(c, 0));
            helper.assertTrue(plan > 20, "the inn on its way home: " + plan);
            int coins0 = Ledger.coins(a), larder0 = Roadside.larderForTests(level, a, b);
            int[] inn0 = ArmyInns.innForTests(level, a, b);
            String halt = marchToInn(level, c, 0, 80);
            int[] hc = ArmyInns.haltCountsForTests(c, 0);
            int[] inn1 = ArmyInns.innForTests(level, a, b);
            int coins1 = Ledger.coins(a), larder1 = Roadside.larderForTests(level, a, b);
            Kit.log("rp01 halt '" + halt + "' at " + (int) ArmyInns.alongForTests(c, 0) + ": rooms " + hc[0] + ", fed " + hc[1] + ", paid " + hc[2]
                + ", rent carried " + hc[3] + "; treasury " + coins0 + " -> " + coins1 + "; larder " + larder0 + " -> " + larder1
                + "; inn takings " + inn0[0] + " -> " + inn1[0] + ", meals " + inn0[1] + " -> " + inn1[1] + ", keeper " + inn0[4] + " -> " + inn1[4]);
            helper.assertTrue(halt.equals("stay night"), "it stops at the inn for the night: '" + halt + "'");
            helper.assertTrue(hc[0] >= 1 && hc[1] >= 4, "officers in its rooms and the men fed: " + hc[0] + ", " + hc[1]);
            helper.assertTrue(hc[2] == hc[1] * Roadside.MEAL + hc[0] * Roadside.ROOM && coins0 - coins1 == hc[2],
                "paid out of the treasury at the inn's prices: " + hc[2] + ", " + (coins0 - coins1));
            helper.assertTrue(larder0 - larder1 == hc[1], "a dish a man out of the larder: " + (larder0 - larder1));
            helper.assertTrue(inn1[0] - inn0[0] == hc[2] && inn1[1] - inn0[1] == hc[1], "the inn's books took it");
            helper.assertTrue(inn1[4] < 0 || inn1[4] - inn0[4] == hc[2] - hc[3], "the coin into the keeper's till, less the rent carried home: "
                + inn0[4] + " -> " + inn1[4]);
            helper.assertTrue(hc[3] > 0, "the inn's rent goes home with the town's own army: " + hc[3]);
            carried[0] = hc[3];
            at[0] = coins1;
            helper.assertTrue(told(a, "for the night"), "its chronicle tells it: " + history(a));
            List<String> book = ArmyInns.bookForTests(a, b);
            helper.assertTrue(!book.isEmpty() && book.get(0).contains(Villages.name(a)) && book.get(0).contains("the night"), "the inn's book has who stayed: " + book);
            helper.assertTrue(Roadside.stateForTests(a, b).startsWith("open"), "the inn still open");
            camp.set(c);
            at[1] = level.getGameTime();
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            Campaigns.Campaign c = camp.get();
            if (c == null || phase[0] == 0) return;
            long now = level.getGameTime();
            if (phase[0] == 1) {
                // The officers go in to their rooms and to bed; the column stays put.
                boolean abed = false;
                for (UUID o : ArmyInns.roomsForTests(c, 0)) {
                    if (level.getEntity(o) instanceof VillageFolkEntity f && ArmyInns.abedForTests(f)) abed = true;
                }
                helper.assertTrue(ArmyInns.haltForTests(c, 0).equals("stay night"), "still resting the night");
                if (!abed) {
                    helper.assertTrue(now - at[1] < 900, "an officer asleep in the inn's bed within the time");
                    return;
                }
                Kit.log("rp01 an officer abed after " + (now - at[1]) + " ticks; the column at " + (int) ArmyInns.alongForTests(c, 0));
                level.setDayTime(DAY + 24000L + 400L);                         // dawn
                phase[0] = 2;
                at[2] = now;
                return;
            }
            ArmyInns.secondForTests(level, c);
            if (phase[0] == 2) {
                if (!ArmyInns.haltForTests(c, 0).isEmpty()) {
                    helper.assertTrue(now - at[2] < 200, "marched on at dawn");
                    return;
                }
                for (UUID o : ArmyInns.roomsForTests(c, 0)) {
                    helper.assertTrue(!(level.getEntity(o) instanceof VillageFolkEntity f && f.isSleeping()), "the officers up");
                }
                helper.assertTrue(told(a, "marched on at dawn"), "the chronicle says it marched on: " + history(a));
                phase[0] = 3;
                at[3] = now;
                return;
            }
            if (c.stage() != Campaigns.Stage.DONE) {
                helper.assertTrue(now - at[3] < 1200, "home in time: " + c.stage() + " at " + (int) ArmyInns.alongForTests(c, 0));
                return;
            }
            int coins = Ledger.coins(a);
            Kit.log("rp01 home: treasury " + at[0] + " -> " + coins + " (rent " + carried[0] + ")");
            helper.assertTrue(told(a, "the army brought home"), "the rent paid in at home: " + history(a));
            helper.assertTrue(coins >= at[0] + carried[0], "the rent in the treasury: " + at[0] + " -> " + coins);
            helper.succeed();
        });
    }

    // ============================================================ rp02: the enemy's inn plundered on the march to a siege

    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "rp02_inn_plunder")
    public static void rp02_inn_plunder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2322000, far = 440;
        fresh(level, DAY + 1200L);
        List<List<VillageFolkEntity>> ts = towns(helper, new int[]{ x, x + far }, new int[]{ 14, 12 });
        UUID a = ts.get(0).get(0).ownerId(), b = ts.get(1).get(0).ownerId();
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        helper.runAtTickTime(5, () -> {
            buildInn(helper, x, x + far, b, a);                                  // the defender's inn
            guards(a, 8);
            war(level, a, b);
            Campaigns.hurryForTests(true);
            Campaigns.watchForTests(false);
            Battles.deathsForTests(0);
            Campaigns.Campaign c = Campaigns.beginForTests(level, va, vb, true, true);
            helper.assertTrue(c != null, "a campaign begun");
            Campaigns.nextForTests(level, c);                                     // the muster
            Campaigns.nextForTests(level, c);                                     // the march on the town
            helper.assertTrue(c.stage() == Campaigns.Stage.SIEGE_MARCH, "marching on the town: " + c.stage());
            double plan = ArmyInns.plannedForTests(c, 0);
            helper.assertTrue(plan > 20, "the enemy's inn on its way: " + plan + " " + ArmyInns.wayForTests(c, 0));
            int ca = Ledger.coins(a), cb = Ledger.coins(b), larder0 = Roadside.larderForTests(level, b, a);
            String halt = marchToInn(level, c, 0, 80);
            int[] hc = ArmyInns.haltCountsForTests(c, 0);
            int food = ArmyInns.foodCarriedForTests(c, 0), larder1 = Roadside.larderForTests(level, b, a);
            Kit.log("rp02 halt '" + halt + "': eaten " + hc[1] + ", carried " + hc[3] + " (supply " + food + "); larder " + larder0 + " -> " + larder1
                + "; coins " + ca + "/" + cb + " -> " + Ledger.coins(a) + "/" + Ledger.coins(b));
            helper.assertTrue(halt.equals("plunder"), "it plunders the enemy's inn: '" + halt + "'");
            helper.assertTrue(larder0 > 0 && larder1 == 0 && hc[1] > 0 && hc[1] + hc[3] == larder0, "the larder emptied: eaten and carried off");
            helper.assertTrue(food == hc[3], "what it carried off is in its supply, to go home to its stores: " + food);
            helper.assertTrue(Ledger.coins(a) == ca && Ledger.coins(b) == cb, "not a coin paid");
            helper.assertTrue(told(b, "plundered our inn"), "the inn's town hears of it: " + history(b));
            helper.assertTrue(told(a, "requisitioned the larder"), "its own chronicle has it: " + history(a));
            helper.assertTrue(ArmyInns.bookForTests(b, a).get(0).contains("plundered"), "the inn's book has it: " + ArmyInns.bookForTests(b, a));
            camp.set(c);
        });
        helper.onEachTick(() -> {
            Campaigns.Campaign c = camp.get();
            if (c == null) return;
            ArmyInns.secondForTests(level, c);
            if (c.stage() == Campaigns.Stage.SIEGE_MARCH) return;
            Kit.log("rp02 on to the siege: " + c.stage());
            helper.assertTrue(c.stage() == Campaigns.Stage.SIEGE_CAMP || c.stage() == Campaigns.Stage.ASSAULT, "camped before the town: " + c.stage());
            Wars.end(a, b);
            helper.assertTrue(ArmyInns.kindForTests(level, a, b, a).isEmpty(), "the keeper bars its door to the town that plundered it, peace or no");
            helper.succeed();
        });
    }

    // ============================================================ rp03: who is served; a meal by day

    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "rp03_inn_meal")
    public static void rp03_inn_meal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2324000, far = 440;
        fresh(level, DAY + 1200L);
        List<List<VillageFolkEntity>> ts = towns(helper, new int[]{ x, x + far }, new int[]{ 14, 7 });
        UUID a = ts.get(0).get(0).ownerId(), b = ts.get(1).get(0).ownerId();
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        long[] at = new long[2];
        helper.runAtTickTime(5, () -> {
            BlockPos inn = buildInn(helper, x, x + far, a, b);
            guards(a, 6);
            guards(b, 4);
            war(level, a, b);
            Campaigns.hurryForTests(true);
            Campaigns.watchForTests(false);
            Battles.deathsForTests(0);
            Campaigns.Campaign c = Campaigns.beginForTests(level, va, vb, false, false);
            helper.assertTrue(c != null, "a campaign begun");
            Campaigns.nextForTests(level, c);
            // At war with the town at the other end of the road: not served at the inn, and not let plunder it either.
            ArmyInns.routeForTests(c, 0, va.centre(), new BlockPos(inn.getX() + 70, inn.getY(), Z));
            helper.assertTrue(ArmyInns.plannedForTests(c, 0) < 0 && ArmyInns.kindForTests(level, a, a, b).isEmpty(),
                "its own inn is shut to it while the war is on its road");
            helper.assertTrue(ArmyInns.kindForTests(level, b, a, b).equals("PLUNDER"), "the enemy of the inn's town would plunder it");
            if (c.army(1) != null) {
                ArmyInns.routeForTests(c, 1, vb.centre(), new BlockPos(inn.getX() - 70, inn.getY(), Z));
                helper.assertTrue(ArmyInns.plannedForTests(c, 1) > 0, "the enemy's march passing it is put by the inn");
            }
            // Peace; the march home by day: a meal, no rooms.
            Wars.end(a, b);
            level.setDayTime(DAY + 3000L);
            ArmyInns.homeFromForTests(level, c, 0, new BlockPos(inn.getX() + 70, inn.getY(), Z));
            int coins0 = Ledger.coins(a);
            String halt = marchToInn(level, c, 0, 80);
            int[] hc = ArmyInns.haltCountsForTests(c, 0);
            Kit.log("rp03 halt '" + halt + "': rooms " + hc[0] + ", fed " + hc[1] + ", paid " + hc[2] + "; treasury " + coins0 + " -> " + Ledger.coins(a)
                + "; book " + ArmyInns.bookForTests(a, b));
            helper.assertTrue(halt.equals("stay meal"), "by day, a meal: '" + halt + "'");
            helper.assertTrue(hc[0] == 0 && hc[1] > 0 && hc[2] == hc[1] * Roadside.MEAL && coins0 - Ledger.coins(a) == hc[2], "the meals paid for, no rooms");
            helper.assertTrue(told(a, "for a hot meal"), "the chronicle: " + history(a));
            helper.assertTrue(ArmyInns.bookForTests(a, b).get(0).contains("a meal"), "the inn's book");
            at[0] = level.getGameTime();
            at[1] = (long) ArmyInns.alongForTests(c, 0);
            camp.set(c);
        });
        helper.onEachTick(() -> {
            Campaigns.Campaign c = camp.get();
            if (c == null) return;
            ArmyInns.secondForTests(level, c);
            long now = level.getGameTime();
            if (!ArmyInns.haltForTests(c, 0).isEmpty()) {
                helper.assertTrue(ArmyInns.alongForTests(c, 0) == at[1], "the column stays at the inn while it eats");
                return;
            }
            if (c.stage() != Campaigns.Stage.DONE) {
                helper.assertTrue(now - at[0] < 1500, "on and home: " + c.stage());
                return;
            }
            Kit.log("rp03 home after " + (now - at[0]) + " ticks");
            helper.succeed();
        });
    }

    // ============================================================ rp04: three towns; a night at a neutral inn on the march to battle

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "rp04_march_night")
    public static void rp04_march_night(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2326000;
        fresh(level, DAY + 1200L);
        List<List<VillageFolkEntity>> ts = towns(helper, new int[]{ x, x + 440, x + 800 }, new int[]{ 14, 3, 9 });
        UUID a = ts.get(0).get(0).ownerId(), b = ts.get(1).get(0).ownerId(), cc = ts.get(2).get(0).ownerId();
        Villages.Village va = Villages.get(a), vc = Villages.get(cc);
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        long[] at = new long[2];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            buildInn(helper, x, x + 440, a, b);
            Ledger.addCoins(a, 200);
            guards(a, 6);
            guards(cc, 5);
            war(level, a, cc);
            Campaigns.hurryForTests(false);
            Campaigns.watchForTests(false);
            Battles.deathsForTests(0);
            Campaigns.Campaign c = Campaigns.beginForTests(level, va, vc, false, false);
            helper.assertTrue(c != null, "a campaign begun between the two ends of the row");
            Campaigns.nextForTests(level, c);                                     // the muster
            level.setDayTime(DAY + 13000L);                                      // a late start: on the road after dark
            Campaigns.nextForTests(level, c);                                     // the march
            helper.assertTrue(c.stage() == Campaigns.Stage.MARCH, "on the march: " + c.stage());
            Kit.log("rp04 ways: " + ArmyInns.wayForTests(c, 0) + " / " + ArmyInns.wayForTests(c, 1) + "; inn planned at " + (int) ArmyInns.plannedForTests(c, 0));
            helper.assertTrue(ArmyInns.plannedForTests(c, 0) > 0, "the neutral town's inn on its way to the field");
            String halt = marchToInn(level, c, 0, 120);
            helper.assertTrue(halt.equals("stay night"), "a night at the inn: '" + halt + "'");
            // The other army gets to its ground and makes camp round its fire, and waits.
            for (int k = 0; k < 300 && !told(cc, "made camp round its fire"); k++) ArmyInns.secondForTests(level, c);
            helper.assertTrue(told(cc, "made camp round its fire"), "the other army camps while it waits: " + history(cc));
            helper.assertTrue(c.stage() == Campaigns.Stage.MARCH, "no battle while one army rests at the inn: " + c.stage());
            level.setDayTime(DAY + 24000L + 400L);                               // dawn
            camp.set(c);
            at[0] = level.getGameTime();
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            Campaigns.Campaign c = camp.get();
            if (c == null || phase[0] == 0) return;
            for (int k = 0; k < 20 && c.stage() == Campaigns.Stage.MARCH; k++) ArmyInns.secondForTests(level, c);
            if (c.stage() == Campaigns.Stage.MARCH) {
                helper.assertTrue(level.getGameTime() - at[0] < 600, "on to the field at dawn: " + (int) ArmyInns.alongForTests(c, 0));
                return;
            }
            long wait = ArmyInns.battleInForTests(level, c), tod = level.getDayTime() % 24000L;
            Kit.log("rp04 camped: " + c.stage() + ", the battle in " + wait + " ticks, at " + tod);
            helper.assertTrue(c.stage() == Campaigns.Stage.CAMP, "both camped on the field: " + c.stage());
            helper.assertTrue(wait <= 1200 && tod + wait < 12000, "the battle by daylight, within the hour: " + wait + " at " + tod);
            helper.succeed();
        });
    }

    // ============================================================ rp05: the fishing fleet at the pier

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "rp05_pier_fleet")
    public static void rp05_pier_fleet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2330000;
        fresh(level, DAY + 2000L);
        Kit.hold(level, x + 40, Z, 112);
        Kit.prepare(level, x + 40, Z, 112);
        int y = Kit.surface(level, x, Z).getY();
        flat(level, x - 44, x + 29, Z - 60, Z + 60, y);
        sea(level, x + 30, x + 150, Z - 60, Z + 60, y);
        List<VillageFolkEntity> folk = town(helper, x, y, 12);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        Fleet.weatherForTests("clear");
        int[] phase = { 0 }, oldBoats = { 0 };
        long[] at = new long[2];
        AtomicReference<Waterfront.Dock> pierRef = new AtomicReference<>();
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(Harbours.surveyForTests(level, v) != null, "a shore found");
            Harbours.officeForTests(level, v);
            // The fleet as it was: a jetty along the shore, its boats and its fish stall.
            Waterfront.Dock jetty = new Waterfront.Dock(new BlockPos(x + 30, y - 1, Z + 45), Direction.EAST, 6);
            Waterfront.build(level, jetty);
            Fleet.quayForTests(v, jetty);
            helper.assertTrue(FishMarket.buildForTests(level, v, jetty), "a fish stall by the jetty");
            BlockPos oldBarrel = FishMarket.stallForTests(id).barrels().get(1);
            oldBoats[0] = Fleet.boatsForTests(level, v);
            helper.assertTrue(oldBoats[0] >= 2, "its boats at the jetty: " + oldBoats[0]);
            helper.assertTrue(!PierFleet.quayForTests(level, v), "no pier yet: the fleet keeps its jetty");
            // The pier laid.
            chestAt(level, new BlockPos(x - 3, y, Z - 5), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.TORCH, 8));
            for (int i = 0; i < 20 && !Harbours.pierDone(id); i++) Harbours.layPierForTests(level, v, 40);
            helper.assertTrue(Harbours.pierDone(id), "the pier finished");
            int boatsInStores0 = Market.stock(level, id, s -> s.is(ItemTags.BOATS)), barrels0 = Market.stock(level, id, s -> s.is(Items.BARREL));
            helper.assertTrue(PierFleet.quayForTests(level, v), "the pier is the fleet's quay now");
            Villages.forgetStock();
            int boatsInStores1 = Market.stock(level, id, s -> s.is(ItemTags.BOATS)), barrels1 = Market.stock(level, id, s -> s.is(Items.BARREL));
            Kit.log("rp05 moved: boats in the stores " + boatsInStores0 + " -> " + boatsInStores1 + ", barrels " + barrels0 + " -> " + barrels1
                + "; old barrel " + level.getBlockState(oldBarrel));
            helper.assertTrue(boatsInStores1 - boatsInStores0 == oldBoats[0], "the old moorings' boats hauled up into the stores");
            helper.assertTrue(level.getBlockState(oldBarrel).isAir() && barrels1 - barrels0 >= 3, "the old stall taken down into the stores");
            helper.assertTrue(told(id, "moved its moorings to the harbour's pier"), "the chronicle: " + history(id));
            // The boats put in again at the pier: along its landward side.
            Waterfront.Dock pier = PierFleet.pierForTests(id);
            pierRef.set(pier);
            Harbours.Shore s = Harbours.shore(id);
            Direction side = s.out().getClockWise();
            List<BlockPos> berths = PierFleet.berthsForTests(level, pier);
            int boats = Fleet.boatsForTests(level, v);
            helper.assertTrue(boats >= 2 && !berths.isEmpty(), "boats put in at the pier: " + boats + ", berths " + berths);
            for (BlockPos p : berths) {
                int lat = (p.getX() - s.water().getX()) * side.getStepX() + (p.getZ() - s.water().getZ()) * side.getStepZ();
                helper.assertTrue(lat < 0, "the berths on the pier's landward side, clear of the ships': " + p.toShortString() + " lat " + lat);
                BlockPos stand = PierFleet.standForTests(pier, p);
                helper.assertTrue(level.getBlockState(stand).isAir() && level.getBlockState(stand.below()).isSolid(), "a hand boards from the deck: " + stand.toShortString());
            }
            for (Boat bt : Fleet.boatListForTests(level, v)) {
                double best = 99;
                for (BlockPos p : berths) best = Math.min(best, Math.sqrt(bt.distanceToSqr(p.getX() + 0.5, bt.getY(), p.getZ() + 0.5)));
                helper.assertTrue(best < 1.7, "each boat at a berth by the pier: " + bt.blockPosition().toShortString());
            }
            // The fish market put up again, by the pier's foot; a catch landed there and counted.
            helper.assertTrue(FishMarket.buildForTests(level, v, pier), "the fish stall put up by the pier");
            BlockPos landing = PierFleet.landingForTests(id);
            helper.assertTrue(landing.distSqr(pier.start()) < 14 * 14, "the catch comes ashore by the pier: " + landing.toShortString());
            VillageFolkEntity f = folk.get(3);
            f.insertItem(new ItemStack(Items.COD, 5));
            int n = PierFleet.landForTests(level, v, f);
            long[] tally = PierFleet.tallyForTests(level, id);
            List<String> page = Harbours.lines(level, v);
            Kit.log("rp05 landed " + n + "; tally " + tally[0] + "/" + tally[1] + " total " + tally[2] + "; page " + String.join(" / ", page));
            helper.assertTrue(n == 5 && tally[0] == 5 && tally[1] == 1 && tally[2] == 5, "five landed and counted");
            helper.assertTrue(String.join(" ", page).contains("moors along the pier") && String.join(" ", page).contains("5 fish today"), "the Harbour page shows it");
            // A pier broken at its end is mended, and the chronicle says so (not that it was finished all over again).
            BlockPos end = Harbours.atForTests(s, 12, 0, 0);
            BlockPos deck = new BlockPos(end.getX(), Harbours.deckForTests(s), end.getZ());
            level.setBlock(deck, Blocks.AIR.defaultBlockState(), 3);
            Ledger.note(id, "tw.pier", "mending");
            for (int i = 0; i < 5 && !Harbours.pierDone(id); i++) Harbours.layPierForTests(level, v, 40);
            int finished = 0;
            for (Chronicle.Entry e : Chronicle.of(id)) if (e.text().contains("pier was finished")) finished++;
            helper.assertTrue(Harbours.pierDone(id) && told(id, "pier was mended") && finished == 1, "mended, told once: " + history(id));
            helper.assertTrue(PierFleet.quayForTests(level, v), "the fleet still at the pier");
            // The war room: the harbour, and a ship sighted.
            ShipCalls.sightNow(level, v, null);
            CompoundTag o = WarRoom.buildForTests(level, id, v.centre(), false);
            int[] counts = WarRoomRealm.countsForTests(o);
            Kit.log("rp05 war room: harbours " + counts[0] + ", ships " + counts[5] + "; " + ShipCalls.stateForTests(id));
            helper.assertTrue(counts[0] == 1 && counts[5] == 1, "the war room shows the harbour and the ship");
            // Out with the fleet: two fishers with rods.
            for (int i = 4; i <= 5; i++) {
                VillageFolkEntity h = folk.get(i);
                h.setJob(AssistantEntity.StationTask.FISH);
                h.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
            }
            level.setDayTime(DAY + 24000L + 300L);
            String today = Fleet.sailForTests(level, v);
            int aboard = Fleet.boardForTests(level, v);
            Kit.log("rp05 sail: '" + today + "', aboard " + aboard);
            helper.assertTrue(aboard >= 1, "fishers aboard at the pier: " + today);
            at[0] = level.getGameTime();
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 0) return;
            long now = level.getGameTime();
            if (phase[0] == 1) {
                boolean fishing = false;
                for (String h : Fleet.handsForTests(id)) if (h.contains("FISHING")) fishing = true;
                if (!fishing) {
                    helper.assertTrue(now - at[0] < 900, "out to the grounds: " + Fleet.handsForTests(id));
                    return;
                }
                Fleet.fishForTests(level, v, 2);
                level.setDayTime(DAY + 24000L + 8001L);                          // the afternoon: home
                phase[0] = 2;
                at[1] = now;
                return;
            }
            if (Fleet.anyOutForTests(id)) {
                helper.assertTrue(now - at[1] < 1500, "home to the pier: " + Fleet.handsForTests(id));
                return;
            }
            long[] tally = PierFleet.tallyForTests(level, id);
            Kit.log("rp05 the fleet in: tally " + tally[0] + " fish, " + tally[1] + " landings");
            helper.assertTrue(tally[1] >= 2, "the boats came in to the pier and their catch was counted: " + tally[1]);
            helper.succeed();
        });
    }

    // ============================================================ rp06: the war room's map of the new realm

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "rp06_war_room")
    public static void rp06_war_room(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2333000, far = 440;
        fresh(level, DAY + 2000L);
        List<List<VillageFolkEntity>> ts = towns(helper, new int[]{ x, x + far }, new int[]{ 14, 6 });
        UUID a = ts.get(0).get(0).ownerId(), b = ts.get(1).get(0).ownerId();
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        helper.runAtTickTime(5, () -> {
            buildInn(helper, x, x + far, a, b);
            RoadWorks.setHalfForTests(a, b, 3, 0, 20);
            RoadWorks.setHalfForTests(b, a, 2, 0, 8);
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.moveTo(x + 2000, 200, Z);
            String dub = Knights.dubNow(level, va, p);
            Kit.log("rp06 dub: " + dub + "; lands " + KnightManors.lands(a).size());
            Ledger.built(a, "minster", va.centre().offset(14, 0, 14), Direction.NORTH);
            Ledger.built(b, "minster", vb.centre().offset(14, 0, 14), Direction.NORTH);
            int took = Prisoners.takeNow(level, va, vb, 1);
            boolean met = Ledger.knowEachOther(a, b);
            CompoundTag o = WarRoom.buildForTests(level, a, va.centre(), false);
            int[] n = WarRoomRealm.countsForTests(o);
            Kit.log("rp06 met " + met + "; harbours " + n[0] + ", highways " + n[1] + ", inns " + n[2] + ", manors " + n[3] + ", wonders " + n[4]
                + "; prisoners taken " + took);
            helper.assertTrue(n[2] == 1, "our own inn on the map: " + n[2]);
            helper.assertTrue(n[3] == KnightManors.lands(a).size() && n[3] >= 1, "the knight's land on the map: " + n[3]);
            CompoundTag us = town(o, a);
            helper.assertTrue(us != null && us.getInt("prisoners") == took && took == 1, "the prisoners held on our card: " + us);
            if (!met) {
                helper.assertTrue(n[1] == 0 && n[4] == 1, "a town not met: its road and its wonder hidden: " + n[1] + ", " + n[4]);
                Ledger.relate(a, b, 5);
                o = WarRoom.buildForTests(level, a, va.centre(), false);
                n = WarRoomRealm.countsForTests(o);
            }
            helper.assertTrue(n[1] == 1 && n[4] == 2, "met: the road and both wonders: " + n[1] + ", " + n[4]);
            CompoundTag hw = o.getList("highways", Tag.TAG_COMPOUND).getCompound(0);
            int[] grade = hw.getIntArray("grade");
            int ga = a.compareTo(b) < 0 ? grade[0] : grade[1], gb = a.compareTo(b) < 0 ? grade[1] : grade[0];
            helper.assertTrue(ga == 3 && gb == 2, "each half by its grade: " + ga + ", " + gb);
            CompoundTag them = town(o, b);
            helper.assertTrue(them != null && them.getInt("wonders") == 1, "their card: a wonder standing: " + them);
            CompoundTag inn = o.getList("inns", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertTrue(inn.getBoolean("open") && inn.getString("label").contains("open"), "the inn open: " + inn);
            int size = o.toString().length();
            Kit.log("rp06 overlay " + size + " characters");
            helper.assertTrue(size < 60000, "the packet kept small: " + size);
            helper.succeed();
        });
    }

    private static CompoundTag town(CompoundTag overlay, UUID id) {
        ListTag ts = overlay.getList("towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < ts.size(); i++) if (ts.getCompound(i).getString("id").equals(id.toString())) return ts.getCompound(i);
        return null;
    }
}
