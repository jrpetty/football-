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
            Ledger.built(id, w[0], anchor, Direction.NORTH);
            raised.add(new Ledger.Building(w[0], anchor, Direction.NORTH));
            Kit.log("[VT] fx05 " + w[0] + " at " + anchor.toShortString() + ": " + said);
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
                if (!covered) { open++; if (open == 1) LAST_OPEN = x + "," + roof + "," + z; }
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
                boolean table = dx >= 44 && dx <= 90 && Math.abs(dz) <= 30;
                boolean slope = dx <= -40 && dx >= -110 && Math.abs(dz) <= 30;
                int h = table ? 7 : slope ? (-40 - dx) / 3 : 0;
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
                    if (dx >= 44 && dx <= 90 && Math.abs(dz) <= 30) table++;
                    if (dx <= -40) slope++;
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
        Kit.hold(level, cx, cz, 48);
        Kit.prepare(level, cx, cz, 48);
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
            BuildAuditGameTests.siteOn(level, "house", cx + 20, cz, Direction.NORTH),
            BuildAuditGameTests.siteOn(level, "house", hx + 18, hz, Direction.NORTH),
            BuildAuditGameTests.siteOn(level, "house", hx - 18, hz, Direction.SOUTH) };
        Direction[] facing = { Direction.NORTH, Direction.NORTH, Direction.SOUTH };
        String[] name = { "flat", "hill, door uphill", "hill, door downhill" };
        long[] took = { -1, -1, -1 };
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
            for (int i = 0; i < 3; i++) {
                VillageFolkEntity b = builders.get(i);
                if (took[i] < 0 && (b.peekJob() == null || b.peekJob().type() != com.jrpetty.mcassistant.entity.Job.Type.BUILD)) took[i] = tick[0];
                if (took[i] < 0) { all = false; if (tick[0] % 200 == 0) packFor(b, "house", at[i], facing[i]); }
            }
            if (!all && tick[0] < 50000) return false;
            List<String> out = new ArrayList<>();
            int[] missing = new int[3];
            for (int i = 0; i < 3; i++) {
                for (BuildGoal.Placement p : BuildGoal.plan("house", at[i], facing[i], 13)) {
                    if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.BED) continue;
                    if (com.jrpetty.mcassistant.entity.Restore.judge(level.getBlockState(p.pos()), p) == com.jrpetty.mcassistant.entity.Restore.Kind.MISSING) missing[i]++;
                }
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

    /** The builder's pack filled with a stack of everything the drawing is made of, and earth and stone for the ground. */
    static void packFor(VillageFolkEntity b, String structure, BlockPos anchor, Direction facing) {
        java.util.Set<net.minecraft.world.item.Item> kinds = new java.util.LinkedHashSet<>();
        kinds.add(Items.COBBLESTONE);
        kinds.add(Items.DIRT);
        for (BuildGoal.Placement p : BuildGoal.plan(structure, anchor, facing, 13)) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            net.minecraft.world.item.Item it = BuildWorldGameTests.kit(p);
            if (it != Items.AIR) kinds.add(it);
        }
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> inv = b.getInventoryItems();
        int i = 0;
        for (net.minecraft.world.item.Item it : kinds) {
            if (i >= inv.size()) break;
            inv.set(i++, new net.minecraft.world.item.ItemStack(it, it.getDefaultMaxStackSize()));
        }
    }
}
