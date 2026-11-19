package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.goal.WorksGoal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.jrpetty.mcassistant.entity.Grow;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [fixes14] The miners' picks, the miners' walk underground, the mountain town's fields, the hill houses, the roof kept on
 * while a storey goes up, and the lake shore. x band 4,400,000 to 4,500,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FixesGameTests {

    // ============================================================ fx01: the miners' picks

    /** How long fx01 runs (-Dfx.days), in days. */
    private static final double PICK_DAYS = Double.parseDouble(System.getProperty("fx.days", "2"));

    /**
     * A young town in the Stone Age (five miners, a storekeeper, a smith, its stores with stone and timber but not a pick
     * in them) works its mine in a hill of rock out past its houses, on its own, for two days: the picks wear out and are
     * kept up (the rack stocked to the miners' wear, a worn pick changed before it breaks), so the first landing is cut
     * and the works go on without a miner standing for want of a pick.
     */
    @GameTest(template = "empty", timeoutTicks = 120000, required = false, batch = "fx00_mine_town")
    public static void fx00_mine_town(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.MineStairs.resetForTests(level);
        com.jrpetty.mcassistant.entity.TownDigs.resetForTests();
        com.jrpetty.mcassistant.entity.Toolrack.resetForTests();
        level.setDayTime(1000);
        int cx = 4410000, cz = 90000;
        Kit.hold(level, cx, cz, 160);
        Kit.prepare(level, cx, cz, 160);
        BlockPos heart = Kit.surface(level, cx, cz);
        int y0 = heart.getY();
        int sx = cx + 100, sz = cz;
        // The hill of rock: a flat top of thirty over the shaft's ground, its sides stepped down a block a block.
        for (int dx = -46; dx <= 46; dx++) {
            for (int dz = -46; dz <= 46; dz++) {
                int r = Math.max(Math.abs(dx), Math.abs(dz));
                int h = r <= 14 ? 30 : Math.max(0, 30 - (r - 14));
                if (h <= 0) continue;
                for (int y = y0 - 3; y < y0 + h; y++) {
                    BlockState st = y == y0 + h - 1 && r > 14 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    level.setBlock(new BlockPos(sx + dx, y, sz + dz), st, 2 | 16);
                }
            }
        }
        int top = y0 + 30;
        List<VillageFolkEntity> miners = new ArrayList<>();
        com.jrpetty.mcassistant.entity.AssistantEntity.StationTask[] trades = {
            com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.STORE, com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.SMITH };
        VillageFolkEntity first = null;
        for (int i = 0; i < 7; i++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
            helper.assertTrue(f != null, "a folk");
            if (first == null) first = f;
            if (i < 5) { f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE); miners.add(f); }
            else f.setJob(trades[i - 5]);
        }
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        MineWorks.forgetForTests(id);
        Ledger.note(id, "mine.site", sx + "," + sz);
        com.jrpetty.mcassistant.entity.MineGround.reserve(level, id, heart, new BlockPos(sx, 0, sz), false);
        MineWorks.layOutForTests(level, id, top);
        Kit.stockForGrowth(level, v);
        com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new net.minecraft.world.item.ItemStack(Items.STICK, 32));
        for (VillageFolkEntity m : miners) {
            m.removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PICKAXES), 64);
            if (m.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES)) m.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            m.insertItem(new net.minecraft.world.item.ItemStack(Items.STONE_PICKAXE));
            m.insertItem(new net.minecraft.world.item.ItemStack(Items.TORCH, 64));
            m.assignPlot(com.jrpetty.mcassistant.entity.WorkZone.around(new BlockPos(sx, top, sz), 8, 0), "The Pit");
        }
        WorksGoal.resetCountsForTests();
        level.getServer().tickRateManager().setTickRate(10000.0F);
        final int[] tick = { 0 }, without = { 0 }, samples = { 0 };
        final long[] landing = { -1 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] % 24000 == 0) Kit.stockForGrowth(level, v);
            if (tick[0] % 100 == 0) {
                for (VillageFolkEntity m : miners) {
                    samples[0]++;
                    if (m.countMatching(st -> st.is(net.minecraft.tags.ItemTags.PICKAXES) && st.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) == 0
                        && !m.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) without[0]++;
                }
                if (landing[0] < 0 && String.join(" ", MineWorks.report(level, id)).matches(".*[1-5] of [1-5] levels open.*")) landing[0] = tick[0];
            }
            if (tick[0] < PICK_DAYS * 24000) return false;
            int[] c = WorksGoal.countsForTests();
            int[] tally = com.jrpetty.mcassistant.entity.Toolrack.tally(id);
            int stalls = 0;
            for (java.util.Map.Entry<String, Integer> e : WorksGoal.ENDS.entrySet()) if (e.getKey().contains("pick")) stalls += e.getValue();
            List<String> kit = new ArrayList<>();
            for (VillageFolkEntity m : miners) kit.add(m.getMainHandItem().getItem() + " " + com.jrpetty.mcassistant.entity.Toolrack.left(m.getMainHandItem()) + "%");
            Kit.log("[VT] fx00 after " + tick[0] + " ticks: first landing at " + (landing[0] < 0 ? "never" : String.format("%.2f days", landing[0] / 24000.0))
                + "; " + c[0] + " blocks broken, " + WorksGoal.SHIFTS + " shifts, " + stalls + " shifts ended or refused for a pick; miners without a pick "
                + (100 * without[0] / Math.max(1, samples[0])) + "% of the time; picks made " + tally[0] + ", handed out " + tally[1] + "; in hand now " + kit
                + "; ends " + WorksGoal.ENDS + " | " + String.join(" | ", MineWorks.report(level, id)));
            MineSiteGameTests.standDown(level);
            Kit.Expect expect = new Kit.Expect();
            expect.that(landing[0] > 0 && landing[0] < 24000, "the first landing cut on the first day: " + landing[0]);
            expect.that(without[0] * 20 <= samples[0], "no miner long without a pick: " + without[0] + " of " + samples[0]);
            helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
            return true;
        });
    }

    /**
     * The picks' chain: the rack's spares to the miners' count and wear; a smith makes them first while the rack is short,
     * in iron once the stores hold the bars (the Stone Age too), and mends two worn ones into one; a miner setting out with a
     * worn pick changes it for a fresh one and hands the worn one in.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "fx01_picks")
    public static void fx01_picks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Toolrack.resetForTests();
        level.setDayTime(1000);
        int cx = 4405000, cz = 90000;
        Kit.hold(level, cx, cz, 40);
        Kit.prepare(level, cx, cz, 40);
        BlockPos heart = Kit.surface(level, cx, cz);
        List<VillageFolkEntity> miners = new ArrayList<>();
        VillageFolkEntity smith = null;
        for (int i = 0; i < 7; i++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
            helper.assertTrue(f != null, "a folk");
            if (i < 6) { f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE); miners.add(f); }
            else { f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.SMITH); smith = f; }
        }
        UUID id = smith.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        com.jrpetty.mcassistant.entity.Villages.storehouseFirstDoneForTests(id);
        for (int k = 0; k < 4; k++) {
            BlockPos chest = Kit.surface(level, heart.getX() + 4 + k, heart.getZ() - 4);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        }
        for (net.minecraft.world.item.ItemStack st : new net.minecraft.world.item.ItemStack[]{ new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 64),
                new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 64), new net.minecraft.world.item.ItemStack(Items.OAK_LOG, 64),
                new net.minecraft.world.item.ItemStack(Items.STICK, 32), new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE, 1) })
            com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, st);
        com.jrpetty.mcassistant.entity.Toolrack.Tool pick = com.jrpetty.mcassistant.entity.Toolrack.Tool.PICKAXE;
        int wantedAtFirst = com.jrpetty.mcassistant.entity.Toolrack.wanted(id, pick);
        // The smith, the rack short: stone picks (no iron in the stores).
        String made1 = com.jrpetty.mcassistant.entity.Picks.smith(smith, level);
        int stone = com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.STONE_PICKAXE));
        com.jrpetty.mcassistant.entity.Picks.emptyRackForTests(level, v);
        // Iron in the stores: iron picks, in the Stone Age.
        com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new net.minecraft.world.item.ItemStack(Items.IRON_INGOT, 64));
        com.jrpetty.mcassistant.entity.Picks.resetForTests();
        String made2 = com.jrpetty.mcassistant.entity.Picks.smith(smith, level);
        int iron = com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.IRON_PICKAXE));
        // A miner with a worn pick setting out: a fresh one taken, the worn one handed in.
        VillageFolkEntity m = miners.get(0);
        m.removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PICKAXES), 64);
        m.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        net.minecraft.world.item.ItemStack worn = new net.minecraft.world.item.ItemStack(Items.STONE_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() * 3 / 4);
        m.insertItem(worn);
        int wornBefore = com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.STONE_PICKAXE) && st.isDamaged());
        boolean changed = com.jrpetty.mcassistant.entity.Picks.beforeShift(level, m);
        int best = 0;
        for (net.minecraft.world.item.ItemStack st : m.getInventoryItems()) if (st.is(net.minecraft.tags.ItemTags.PICKAXES)) best = Math.max(best, com.jrpetty.mcassistant.entity.Toolrack.left(st));
        int wornAfter = com.jrpetty.mcassistant.entity.Market.stock(level, id, st -> st.is(Items.STONE_PICKAXE) && st.isDamaged());
        // Another worn one handed in: the two mended into one.
        net.minecraft.world.item.ItemStack worn2 = new net.minecraft.world.item.ItemStack(Items.STONE_PICKAXE);
        worn2.setDamageValue(worn2.getMaxDamage() * 2 / 3);
        com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, worn2);
        for (int i = 0; i < 5; i++) com.jrpetty.mcassistant.entity.Picks.issuedForTests(id, level.getGameTime());
        int wantedAfterWear = com.jrpetty.mcassistant.entity.Toolrack.wanted(id, pick);
        com.jrpetty.mcassistant.entity.Picks.resetSmithForTests();
        String made3 = com.jrpetty.mcassistant.entity.Picks.smith(smith, level);
        int mended = com.jrpetty.mcassistant.entity.Picks.mended(id);
        // A miner whose pick broke below, the rack bare: one made at the stores and sent down.
        VillageFolkEntity m2 = miners.get(1);
        m2.removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PICKAXES), 64);
        m2.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        com.jrpetty.mcassistant.entity.Picks.emptyRackForTests(level, v);
        boolean sent = com.jrpetty.mcassistant.entity.Picks.sendDown(level, m2);
        Kit.log("[VT] fx01 a miner with no pick and the rack bare: one sent down " + sent + " (" + m2.getMainHandItem().getItem() + ")");
        Kit.log("[VT] fx01 six miners: the rack keeps " + wantedAtFirst + " picks (" + wantedAfterWear + " after six handed out in a day); the smith made: "
            + made1 + " (" + stone + " stone picks in the stores); with iron: " + made2 + " (" + iron + " iron picks); the miner's worn pick changed "
            + changed + " (its best now " + best + "% left, worn picks in the stores " + wornBefore + " -> " + wornAfter + "); then: " + made3 + ", mended " + mended);
        Kit.Expect expect = new Kit.Expect();
        expect.that(wantedAtFirst >= 3, "a spare for every two miners: " + wantedAtFirst);
        expect.that(wantedAfterWear > wantedAtFirst, "more kept as they wear them out: " + wantedAfterWear);
        expect.that(stone >= 2, "stone picks made by the smith: " + stone);
        expect.that(iron >= 1, "iron picks once there is iron, in the Stone Age: " + iron);
        expect.that(changed && best >= 90 && wornAfter == wornBefore + 1, "the worn pick changed before the shift and handed in");
        expect.that(mended >= 1, "two worn picks mended into one: " + mended);
        expect.that(sent, "a pick sent down to a miner whose pick broke, the rack bare");
        if (expect.clean()) helper.succeed(); else helper.fail(expect.summary());
    }

    // ============================================================ fx02: the miners walk the works

    /**
     * Four miners working a level by shifts from the pithead (down the spiral stair, along the drifts and branches, and
     * back up at a shift's end): they walk it, put on their way when stuck hardly ever (no more than one hop in four
     * shifts).
     */
    @GameTest(template = "empty", timeoutTicks = 40000, batch = "fx02_miners_walk")
    public static void fx02_miners_walk(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 4420000, 4);
        ServerLevel level = r.level();
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(r.v(), MineWorksGameTests.plenty());
        for (VillageFolkEntity m : r.miners()) { MineWorksGameTests.kit(m, Items.IRON_PICKAXE); MineWorksGameTests.zone(r, m, 88); }
        int y = MineWorksGameTests.level(r, MineWorks.COAL, Blocks.COAL_ORE, 120, r.miners().get(0));
        int i = 0;
        for (VillageFolkEntity m : r.miners()) m.teleportTo(r.sx() - 5.5 + (i++ % 2), 100, r.sz() - 1.5);
        WorksGoal.resetCountsForTests();
        level.getServer().tickRateManager().setTickRate(10000.0F);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            for (VillageFolkEntity m : r.miners()) { banked(m); MineWorksGameTests.keepAtIt(r, m); }
            if (tick[0] < 30000) return false;
            int[] c = WorksGoal.countsForTests();
            int shifts = WorksGoal.SHIFTS;
            Kit.log("[VT] fx02 after " + tick[0] + " ticks: " + shifts + " shifts, " + c[0] + " blocks broken, " + c[3] + " hops ("
                + String.format("%.2f", c[3] / (double) Math.max(1, shifts)) + " a shift); ends " + WorksGoal.ENDS + " | level Y" + y + " | " + String.join(" | ", MineWorks.report(level, r.v())));
            List<String> hops = WorksGoal.hopsForTests();
            for (String h : hops) Kit.log("[VT] fx02   hop: " + h);
            helper.assertTrue(shifts >= 8, "the miners worked their shifts: " + shifts);
            helper.assertTrue(c[0] >= 200, "the works dug: " + c[0]);
            helper.assertTrue(c[3] * 4 <= shifts, "put on their way hardly ever: " + c[3] + " hops in " + shifts + " shifts");
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    /** Its haul banked for it (the test's rock stands in the air, with no walk home to the stores): its kit kept. */
    static void banked(VillageFolkEntity m) {
        if (m.tickCount % 40 != 0) return;
        m.removeMatching(st -> !st.is(net.minecraft.tags.ItemTags.PICKAXES) && !st.is(Items.TORCH) && !st.is(Items.BREAD) && !st.is(Items.COBBLESTONE), 64 * 36);
        int torches = m.countMatching(st -> st.is(Items.TORCH));
        if (torches < 32) m.insertItem(new net.minecraft.world.item.ItemStack(Items.TORCH, 64 - torches));
        int cobble = m.countMatching(st -> st.is(Items.COBBLESTONE));
        if (cobble > 64) m.removeMatching(st -> st.is(Items.COBBLESTONE), cobble - 32);
        else if (cobble < 16) m.insertItem(new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 32));
    }

    // ============================================================ fx05: the roof kept on while a storey goes up

    /**
     * A house and a meeting hall in the Iron Age go up a storey, a builders' visit at a time: the rooms are never open to
     * the sky for more than a moment (the new walls and roof laid before the old roof comes off).
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "fx05_roof_kept_on")
    public static void fx05_roof_kept_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int cx = 4450000, cz = 90000;
        Kit.hold(level, cx, cz, 48);
        Kit.prepare(level, cx, cz, 48);
        BlockPos heart = Kit.surface(level, cx, cz);
        VillageFolkEntity hand = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hand != null, "a village");
        UUID id = hand.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Kit.stockForGrowth(level, v);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
        List<Ledger.Building> raised = new ArrayList<>();
        String[][] what = { { "house", "house2" }, { "hall", "hall" + com.jrpetty.mcassistant.entity.goal.Blueprints.TALL } };
        int k = 0;
        for (String[] w : what) {
            BlockPos anchor = BuildAuditGameTests.siteOn(level, w[0], cx - 14 + 28 * k++, cz + 20, Direction.NORTH);
            String said = BuildGoal.buildNowForTests(hand, w[0], anchor, Direction.NORTH, BuildWorldGameTests::stock);
            // Whole, roof and all (the builder's gate keeps it off the plan's street cells by the heart).
            BuildGoal.stampOnly(level, w[0], anchor, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK), p -> p.part() != BuildGoal.Part.CLEAR);
            Ledger.built(id, w[0], anchor, Direction.NORTH);
            raised.add(new Ledger.Building(w[0], anchor, Direction.NORTH));
            Kit.log("[VT] fx05 " + w[0] + " at " + anchor.toShortString() + ": " + said);
        }
        for (int i = 0; i < raised.size(); i++) {
            int open = openToSky(level, raised.get(i), what[i][0], what[i][1]);
            Kit.log("[VT] fx05 before the storey, " + what[i][0] + ": " + open + " open, first " + LAST_OPEN);
        }
        final int[] visits = { 0 }, worst = { 0, 0 }, openVisits = { 0, 0 };
        helper.onEachTick(() -> {
            if (visits[0] < 0 || helper.getTick() % 20 != 0) return;
            visits[0]++;
            Kit.stockForGrowth(level, v);
            boolean all = true;
            for (int i = 0; i < raised.size(); i++) {
                Ledger.Building b = raised.get(i);
                boolean done = i == 0 ? Ledger.grown(id, b.anchor()) : Grow.tall(id, b.anchor());
                if (done) continue;
                all = false;
                if (i == 0) Grow.storey(level, v, b, 12, Grow.palette(id, Villages.Age.IRON), false);
                else Grow.raise(level, v, b, what[i][0], what[i][1], 12, Grow.palette(id, Villages.Age.IRON), false);
                int open = openToSky(level, b, what[i][0], what[i][1]);
                worst[i] = Math.max(worst[i], open);
                if (open > 0) { openVisits[i]++; Kit.log("[VT] fx05   visit " + visits[0] + " " + what[i][0] + ": " + open + " open, first " + LAST_OPEN
                    + ", raising " + Grow.raisingNow(id, b.anchor()) + ", old roof off " + Ledger.raising(id, b.anchor())); }
            }
            if (!all && visits[0] < 250) return;
            visits[0] = -1;
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
            Kit.log("[VT] fx05 house grown " + Ledger.grown(id, raised.get(0).anchor()) + ": most open " + worst[0] + " columns, open at " + openVisits[0]
                + " visits; hall tall " + Grow.tall(id, raised.get(1).anchor()) + ": most open " + worst[1] + ", open at " + openVisits[1] + " visits");
            Kit.Expect expect = new Kit.Expect();
            expect.that(Ledger.grown(id, raised.get(0).anchor()) && Grow.tall(id, raised.get(1).anchor()), "both raised a storey");
            expect.that(openVisits[0] <= 1 && openVisits[1] <= 1, "open to the sky at a visit at most: " + openVisits[0] + ", " + openVisits[1]);
            expect.that(worst[0] <= 4 && worst[1] <= 4, "hardly a column open: " + worst[0] + ", " + worst[1]);
            if (expect.clean()) helper.succeed(); else helper.fail(expect.summary());
        });
    }

    /**
     * The building's inside columns open to the sky: nothing standing in the column as high as the lower of its roofs
     * (the old drawing's or the new's) comes in it.
     */
    static String LAST_OPEN = "";

    static int openToSky(ServerLevel level, Ledger.Building b, String from, String to) {
        java.util.Map<Long, Integer> roofAt = new java.util.HashMap<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (String drawing : new String[]{ from, to }) {
            java.util.Map<Long, Integer> top = new java.util.HashMap<>();
            for (BuildGoal.Placement p : BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                x0 = Math.min(x0, p.pos().getX()); x1 = Math.max(x1, p.pos().getX());
                z0 = Math.min(z0, p.pos().getZ()); z1 = Math.max(z1, p.pos().getZ());
                top.merge(BlockPos.asLong(p.pos().getX(), 0, p.pos().getZ()), p.pos().getY(), Math::max);
            }
            // Only the old building's own columns: the rooms there are.
            if (drawing.equals(from)) roofAt.putAll(top);
            else for (java.util.Map.Entry<Long, Integer> e : top.entrySet()) roofAt.computeIfPresent(e.getKey(), (k, o) -> Math.min(o, e.getValue()));
        }
        int open = 0;
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                Integer roof = roofAt.get(BlockPos.asLong(x, 0, z));
                if (roof == null) continue;
                boolean covered = false;
                for (int y = roof; y <= roof + 12 && !covered; y++) {
                    BlockPos c = new BlockPos(x, y, z);
                    if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) covered = true;
                }
                if (!covered) {
                    open++;
                    if (open == 1) {
                        StringBuilder col = new StringBuilder();
                        for (int y = b.anchor().getY(); y <= roof + 3; y++) col.append(y - b.anchor().getY()).append(':').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath()).append(' ');
                        LAST_OPEN = x + "," + roof + "," + z + " [" + col + "]";
                    }
                }
            }
        }
        return open;
    }

    // ============================================================ fx03: the mountain town's fields

    /**
     * A town on a shelf of a mountain: bare rock round it, a grassy tableland with a tarn on it up a sheer cliff of seven
     * to the east (good ground, flat, watered, and no way up), and a long grassy slope to the west a folk walks up. Its
     * farmers' fields go where they can walk to them, on the slope, and none on the tableland.
     */
    @GameTest(template = "empty", timeoutTicks = 40000, batch = "fx03_mountain_fields")
    public static void fx03_mountain_fields(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Reach.reset();
        level.setDayTime(1000);
        int cx = 4430000, cz = 90000;
        Kit.hold(level, cx, cz, 130);
        Kit.prepare(level, cx, cz, 130);
        int y0 = Kit.surface(level, cx, cz).getY();
        mountain(level, cx, cz, y0);
        BlockPos heart = Kit.surface(level, cx, cz);
        List<VillageFolkEntity> farmers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
            if (f == null) continue;
            f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM);
            farmers.add(f);
        }
        helper.assertTrue(farmers.size() == 3, "the farmers");
        UUID id = farmers.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.stockForGrowth(level, v);
        com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new net.minecraft.world.item.ItemStack(Items.WHEAT_SEEDS, 64));
        // Where the town marks out its farmland, and where the first field goes: how much of its square the farmer walks to.
        int side = farmers.get(0).chooseFieldsSideForTests();
        BlockPos site = farmers.get(0).fieldSiteForTests();
        com.jrpetty.mcassistant.entity.Reach.reset();
        com.jrpetty.mcassistant.entity.Reach walk0 = com.jrpetty.mcassistant.entity.Reach.of(level, id, heart);
        int cols = 0, cut = 0;
        if (site != null && walk0 != null) {
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) { cols++; if (!walk0.reaches(site.getX() + dx, site.getZ() + dz, 0)) cut++; }
        }
        final BlockPos firstSite = site;
        final int firstCut = cut, firstCols = cols;
        Kit.log("[VT] fx03 the farmland marked out to side " + side + " (east " + com.jrpetty.mcassistant.village.TownPlan.EAST + "); the first field at "
            + (site == null ? "none" : site.toShortString() + " (" + (site.getX() - cx) + ", " + (site.getZ() - cz) + "), " + cut + " of " + cols + " of its columns out of the walk"));
        level.getServer().tickRateManager().setTickRate(10000.0F);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] % 24000 == 0) Kit.stockForGrowth(level, v);
            if (tick[0] < 30000) return false;
            int[] f = farmland(level, id, heart, cx, cz);
            List<String> plots = new ArrayList<>();
            for (VillageFolkEntity m : farmers) plots.add(m.workZone() == null ? "none" : m.workZone().center().toShortString());
            Kit.log("[VT] fx03 after " + tick[0] + " ticks: farmland " + f[0] + ", out of the farmers' walk " + f[1] + " (on the tableland " + f[2]
                + ", on the slope " + f[3] + "), terraces level: " + f[4] + " of " + f[5] + " rows; plots " + plots + "; fields side " + Villages.fieldsSide(id));
            MineSiteGameTests.standDown(level);
            Kit.Expect expect = new Kit.Expect();
            expect.that(f[0] >= 40, "fields tilled: " + f[0]);
            expect.that(f[1] == 0 && f[2] == 0, "none out of the farmers' walk: " + f[1] + " (tableland " + f[2] + ")");
            expect.that(firstSite != null && firstCut * 3 <= firstCols, "the first field where the farmer walks: " + firstCut + " of " + firstCols + " columns out of the walk");
            helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
            return true;
        });
    }

    /** The mountain: rock round the heart's pad, the tableland up its cliff to the east, the walkable slope to the west. */
    static void mountain(ServerLevel level, int cx, int cz, int y0) {
        BlockState stone = Blocks.STONE.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState();
        for (int dx = -120; dx <= 120; dx++) {
            for (int dz = -120; dz <= 120; dz++) {
                int x = cx + dx, z = cz + dz;
                boolean pad = Math.abs(dx) <= 24 && Math.abs(dz) <= 24;
                boolean table = dx >= 50 && dx <= 100 && Math.abs(dz) <= 34;
                boolean slope = dx <= -30 && dx >= -115 && Math.abs(dz) <= 34;
                int h = table ? 7 : slope ? (-30 - dx) / 3 : 0;
                for (int y = y0 - 3; y < y0 + 20; y++) {
                    BlockState st;
                    int top = y0 - 1 + h;
                    if (y > top) st = Blocks.AIR.defaultBlockState();
                    else if (pad || table || slope) st = y == top ? grass : y >= top - 2 ? dirt : stone;
                    else st = stone;                                       // the bare mountain
                    level.setBlock(new BlockPos(x, y, z), st, 2 | 16);
                }
            }
        }
        // The tarn on the tableland.
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (dx * dx + dz * dz > 17) continue;
            level.setBlock(new BlockPos(cx + 66 + dx, y0 + 5, cz + dz), Blocks.WATER.defaultBlockState(), 2 | 16);
            level.setBlock(new BlockPos(cx + 66 + dx, y0 + 4, cz + dz), Blocks.WATER.defaultBlockState(), 2 | 16);
        }
    }

    /**
     * The town's farmland: {all, out of the farmers' walk from the heart, on the tableland, on the slope, rows of a field
     * level with the next row or a step down (a terrace), rows looked at}.
     */
    static int[] farmland(ServerLevel level, UUID id, BlockPos heart, int cx, int cz) {
        com.jrpetty.mcassistant.entity.Reach.reset();
        com.jrpetty.mcassistant.entity.Reach walk = com.jrpetty.mcassistant.entity.Reach.of(level, id, heart);
        int all = 0, out = 0, table = 0, slope = 0;
        for (int dx = -125; dx <= 125; dx++) {
            for (int dz = -125; dz <= 125; dz++) {
                int x = cx + dx, z = cz + dz;
                BlockPos top = Kit.surface(level, x, z);
                for (int y = top.getY(); y >= top.getY() - 3; y--) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).is(Blocks.FARMLAND)) continue;
                    all++;
                    if (walk == null || !walk.reaches(x, z, 0)) out++;
                    if (dx >= 50 && dx <= 100 && Math.abs(dz) <= 34) table++;
                    if (dx <= -30) slope++;
                    break;
                }
            }
        }
        return new int[]{ all, out, table, slope, 0, 0 };
    }

    // ============================================================ fx04: a house on a hill lot

    /**
     * Three builders each put up a house by their own goal at their own pace (their packs full): one on the flat, one on
     * a hillside with its door uphill, one with its door down. The hill houses go up about as fast as the flat one.
     */
    @GameTest(template = "empty", timeoutTicks = 60000, batch = "fx04_hill_house")
    public static void fx04_hill_house(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        int cx = 4440000, cz = 90000;
        Kit.hold(level, cx, cz, 96);
        Kit.prepare(level, cx, cz, 96);
        BlockPos heart = Kit.surface(level, cx, cz);
        int hx = cx, hz = cz + 120;
        Kit.hold(level, hx, hz, 48);
        Kit.prepare(level, hx, hz, 48);
        int base = Kit.surface(level, hx, hz).getY();
        for (int dx = -40; dx <= 40; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                int h = Math.max(0, (dz + 24) / 2 + (dx % 7 == 0 ? 1 : 0));
                for (int y = base; y < base + h; y++) {
                    BlockState st = y == base + h - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : y >= base + h - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    level.setBlock(new BlockPos(hx + dx, y, hz + dz), st, 2 | 16);
                }
            }
        }
        List<VillageFolkEntity> builders = new ArrayList<>();
        for (int i = 0; i < 3; i++) builders.add(com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F));
        helper.assertTrue(builders.stream().allMatch(java.util.Objects::nonNull), "the builders");
        UUID id = builders.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.stockForGrowth(level, v);
        BlockPos[] at = {
            BuildAuditGameTests.siteOn(level, "house", cx + 20, cz + 70, Direction.NORTH),
            BuildAuditGameTests.siteOn(level, "house", hx + 18, hz, Direction.NORTH),
            BuildAuditGameTests.siteOn(level, "house", hx - 18, hz, Direction.SOUTH) };
        Direction[] facing = { Direction.NORTH, Direction.NORTH, Direction.SOUTH };
        String[] name = { "flat", "hill, door uphill", "hill, door downhill" };
        long[] took = { -1, -1, -1 };
        int[] idle = new int[3];
        boolean[] begun = new boolean[3];
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity b = builders.get(i);
            packFor(b, "house", at[i], facing[i]);
            b.teleportTo(at[i].getX() + 0.5, Kit.surface(level, at[i].getX(), at[i].getZ() + 9).getY(), at[i].getZ() + 9.5);
            b.clearQueue();
            b.enqueue(com.jrpetty.mcassistant.entity.Job.buildAt("house", at[i], facing[i], 0));
        }
        level.getServer().tickRateManager().setTickRate(10000.0F);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (level.getDayTime() % 24000L > 11000L) level.setDayTime(1000);
            boolean all = true;
            if (tick[0] == 3 || tick[0] == 60) for (int i = 0; i < 3; i++) Kit.log("[VT] fx04 " + name[i] + " at " + tick[0] + ": " + builders.get(i).debugLine());
            for (int i = 0; i < 3; i++) {
                VillageFolkEntity b = builders.get(i);
                if (took[i] >= 0) continue;
                all = false;
                if (tick[0] % 100 != 0) continue;
                packFor(b, "house", at[i], facing[i]);
                // Up when its drawing is all laid, or its builder has stopped building it (whatever is left).
                int miss = missingOf(level, at[i], facing[i]);
                boolean building = b.debugLine().contains("goals=[Build") || b.peekJob() != null && b.peekJob().type() == com.jrpetty.mcassistant.entity.Job.Type.BUILD;
                if (building) begun[i] = true;
                idle[i] = building || !begun[i] ? 0 : idle[i] + 1;
                if (miss <= 3 || idle[i] >= 5 || !begun[i] && tick[0] > 6000) {
                    took[i] = tick[0];
                    Kit.log("[VT] fx04 " + name[i] + " over at " + tick[0] + " (" + miss + " missing): " + b.debugLine());
                }
            }
            if (!all && tick[0] < 50000) return false;
            List<String> out = new ArrayList<>();
            int[] missing = new int[3];
            for (int i = 0; i < 3; i++) {
                missing[i] = missingOf(level, at[i], facing[i]);
                out.add(name[i] + " at " + at[i].toShortString() + ": " + (took[i] < 0 ? "not done" : took[i] + " ticks") + ", " + missing[i] + " cells missing");
            }
            Kit.log("[VT] fx04 " + String.join("; ", out));
            MineSiteGameTests.standDown(level);
            Kit.Expect expect = new Kit.Expect();
            for (int i = 0; i < 3; i++) expect.that(took[i] > 0 && missing[i] <= 3, name[i] + " built: " + took[i] + " ticks, " + missing[i] + " missing");
            expect.that(took[1] <= Math.max(took[0] * 3 / 2, took[0] + 1200) && took[2] <= Math.max(took[0] * 3 / 2, took[0] + 1200),
                "the hill houses about as quick as the flat one: " + took[0] + ", " + took[1] + ", " + took[2]);
            helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
            return true;
        });
    }

    /** The cells of a house's drawing not laid yet (beds aside: they are furnished later). */
    static int missingOf(ServerLevel level, BlockPos anchor, Direction facing) {
        int n = 0;
        for (BuildGoal.Placement p : BuildGoal.plan("house", anchor, facing, 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.BED) continue;
            if (com.jrpetty.mcassistant.entity.Restore.judge(level.getBlockState(p.pos()), p) == com.jrpetty.mcassistant.entity.Restore.Kind.MISSING) n++;
        }
        return n;
    }

    /** The builder's pack filled with what the drawing is made of (a stack of each, as many as it wants of what does not
     *  stack), and earth and stone for the ground. */
    static void packFor(VillageFolkEntity b, String structure, BlockPos anchor, Direction facing) {
        java.util.Map<net.minecraft.world.item.Item, Integer> kinds = new java.util.LinkedHashMap<>();
        kinds.put(Items.COBBLESTONE, 64);
        kinds.put(Items.DIRT, 64);
        for (BuildGoal.Placement p : BuildGoal.plan(structure, anchor, facing, 13)) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            net.minecraft.world.item.Item it = BuildWorldGameTests.kit(p);
            if (it != Items.AIR) kinds.merge(it, 1, Integer::sum);
        }
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> inv = b.getInventoryItems();
        for (int k = 0; k < inv.size(); k++) inv.set(k, net.minecraft.world.item.ItemStack.EMPTY);
        int i = 0;
        for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> e : kinds.entrySet()) {
            int max = e.getKey().getDefaultMaxStackSize();
            int want = max == 1 ? e.getValue() : max;
            while (want > 0 && i < inv.size()) {
                int n = Math.min(want, max);
                inv.set(i++, new net.minecraft.world.item.ItemStack(e.getKey(), n));
                want -= n;
            }
        }
    }

    // ============================================================ fx06: the lake shore

    /** How fast fx06's town is grown: a building every so many ticks (-Dfx.build), for so many days (-Dfx.lakeDays). */
    private static final int LAKE_BUILD = Integer.getInteger("fx.build", 400);
    private static final double LAKE_DAYS = Double.parseDouble(System.getProperty("fx.lakeDays", "3"));

    /**
     * ct01's lake town (forty folk on a meadow cut by a channel of water three wide down the first street east of the
     * square) grown at the admin's speed, a building every twenty seconds: no building stands where its street is not
     * walked from the heart; the town carries its street over the water (a bridge laid) before it builds on the far bank.
     */
    @GameTest(template = "empty", timeoutTicks = 900000, required = false, batch = "fx06_lake_shore")
    public static void fx06_lake_shore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        int cx = 4460000, cz = 90000, folk = 40;
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(folk) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        int r = Math.max(outer + 40, com.jrpetty.mcassistant.village.TownPlan.reach() + 12);
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, r, 9100);
        CityLookGameTests.channel(level, cx, cz, r);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.log("[VT] fx06 found " + folk + ": " + Kit.command(level, "village found " + folk + " " + cx + " " + cz));
        server.tickRateManager().setTickRate(10000.0F);
        final long[] began = { -1 }, next = { 0 };
        final UUID[] town = { null };
        final int[] lastDay = { -1 }, worstOff = { 0 };
        MineSiteGameTests.each(helper, () -> {
            long t = helper.getTick();
            if (began[0] < 0) {
                if (com.jrpetty.mcassistant.entity.Founding.near(level, heart, 8)) return false;
                Villages.Village v = Villages.nearest(level, heart, 96);
                if (v == null) { helper.runAfterDelay(1, () -> helper.fail("no village")); return true; }
                town[0] = v.id();
                began[0] = t;
                Villages.ageForTests(v.id(), Villages.Age.IRON);
                CityLookGameTests.stock(level, v);
                return false;
            }
            Villages.Village v = Villages.get(town[0]);
            long ran = t - began[0];
            int day = (int) (ran / 24000L);
            if (day != lastDay[0]) {
                lastDay[0] = day;
                CityLookGameTests.stock(level, v);
                Kit.log("[VT] fx06 day " + day + ": " + Villages.headcount(v.id()) + " folk, " + Ledger.buildings(v.id()).size() + " buildings; bridges laid "
                    + com.jrpetty.mcassistant.entity.CityCrossings.laid(v.id()) + " cells");
            }
            if (t >= next[0] && ran < (long) (LAKE_DAYS * 24000L) - 4000L) {
                next[0] = t + LAKE_BUILD;
                String p = com.jrpetty.mcassistant.entity.AdminTools.nextProject(v.id());
                if (p != null) com.jrpetty.mcassistant.entity.AdminTools.buildProject(level, v, p, false);
                if (ran % 4800 < LAKE_BUILD) {
                    com.jrpetty.mcassistant.entity.CityPlan.Report rep = com.jrpetty.mcassistant.entity.CityPlan.report(level, v);
                    worstOff[0] = Math.max(worstOff[0], rep.planned() - rep.onStreets());
                }
            }
            if (ran < (long) (LAKE_DAYS * 24000L)) return false;
            java.util.Map<String, Object> a = CityLookGameTests.audit(level, v);
            int across = 0, acrossOn = 0;
            com.jrpetty.mcassistant.entity.CityPlan.Report rep = com.jrpetty.mcassistant.entity.CityPlan.report(level, v);
            for (Ledger.Building b : Ledger.buildings(v.id())) {
                if (b.anchor().getX() - v.centre().getX() >= com.jrpetty.mcassistant.village.TownPlan.RING + com.jrpetty.mcassistant.village.TownPlan.STREET) across++;
            }
            Kit.log("[VT] fx06 AUDIT " + a + "; across the water " + across + " buildings; off the streets now " + (rep.planned() - rep.onStreets())
                + ", at worst " + worstOff[0] + "; bridges laid " + com.jrpetty.mcassistant.entity.CityCrossings.laid(v.id()) + " cells; crossings still to lay "
                + com.jrpetty.mcassistant.entity.CityCrossings.planForTests(level, v));
            MineSiteGameTests.standDown(level);
            int off = rep.planned() - rep.onStreets();
            Kit.Expect expect = new Kit.Expect();
            expect.that(off == 0 && worstOff[0] == 0, "no building off the town's streets: " + off + " now, " + worstOff[0] + " at worst");
            helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
            return true;
        });
    }
}
