package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.VillageCommands;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.Busywork;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.IdleCensus;
import com.jrpetty.mcassistant.entity.SquareMend;
import com.jrpetty.mcassistant.entity.Terraform;
import com.jrpetty.mcassistant.entity.TerraformAudit;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.TownLife;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [life28] A town that looks alive on film: folk always doing something by day, the square level, the chimneys right.
 *
 * <ul>
 * <li><b>lf01</b>: a grown town (thirty founders, its wanted buildings put up, its stores stocked) through a working day,
 *     counted every twenty seconds (IdleCensus): under fifteen in a hundred of its grown folk stand idle by day, on
 *     average, and none for more than a minute at a time. <b>lf00</b> (not required) counts the same town with the
 *     minutes between things left empty (Busywork off), as it was, for the comparison.</li>
 * <li><b>lf02</b>: a village founded at once on uneven ground ({@code /village spawnat}, its camp pitched and its ground
 *     levelled after): its own camp is not taken for anybody's building (no square column held off for it), the square is
 *     level (TerraformAudit: offLevel 0), and the camp is pitched again on the level ground with its stores.</li>
 * <li><b>lf03</b>: a town whose square came out a pit with its wall laid on the broken ground (Ironmouth's): made good
 *     once (SquareMend): the pit filled to the square's level, each column of the wall set back on the level ground with
 *     its courses as they were, the level kept for the town's levelling by hand.</li>
 * <li><b>lf04</b>: a house's chimney is an upright stack out of its roof (two or three over where it comes out, no more,
 *     nothing of it outside the walls), and so is every hearth's in the drawings; its fire laid on top, and an empty
 *     house's hearth cold.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LifeFilmGameTests {

    private static final String EMPTY = "empty";
    /** This class's x band (6.2M), two thousand apart and more. */
    private static final int X = 6_200_000, Z = 40_000;
    /** The town's founders. */
    private static final int FOLK = 30;
    /** By day (07:00 to 17:00), counted every so many ticks. */
    private static final long FROM = 1000L, TO = 11000L, EVERY = 400L;

    // ============================================================ lf01 / lf00: the idle share of a grown town by day

    @GameTest(template = EMPTY, timeoutTicks = 26000, batch = "lf01_idle_share")
    public static void lf01_idle_share(GameTestHelper helper) {
        day(helper, "lf01", X, true);
    }

    @GameTest(template = EMPTY, timeoutTicks = 26000, batch = "lf00_idle_before", required = false)
    public static void lf00_idle_before(GameTestHelper helper) {
        day(helper, "lf00", X + 4000, false);
    }

    private static void day(GameTestHelper helper, String label, int cx, boolean busy) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        Busywork.resetForTests();
        IdleCensus.resetForTests();
        TownJobs.instantForTests(false);                      // the town's works done by hand, as in a game
        Busywork.ENABLED = busy;
        level.setDayTime(22000L);
        int cz = Z;
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, TownPlan.reach() + 16, 2801);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.log("[VT] " + label + " found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + cz));
        UUID[] id = { null };
        long[] began = { -1 };
        List<Double> shares = new ArrayList<>();
        long[] longest = { 0 };
        String[] longWho = { "" };
        long[] nextCount = { -1 };
        Map<String, Integer> idleWhy = new java.util.TreeMap<>();
        long[] morning = { -1, -1, 0 };                         // its bell, its end, its lines
        int[] waiting = { 0 };                                  // counts with the town's work waiting on a hand while hands stood idle
        helper.onEachTick(() -> {
            if (id[0] != null && began[0] >= 0) {
                String dbg = com.jrpetty.mcassistant.entity.Assemblies.debug(id[0]);
                if (morning[0] < 0 && dbg.startsWith("MORNING")) morning[0] = level.getGameTime();
                if (dbg.startsWith("MORNING")) { int[] p = com.jrpetty.mcassistant.entity.Assemblies.progress(id[0]); if (p != null) morning[2] = p[4]; }
                if (morning[0] >= 0 && morning[1] < 0 && !dbg.startsWith("MORNING")) {
                    morning[1] = level.getGameTime();
                    Kit.log("[VT] " + label + " the morning assembly: " + (morning[1] - morning[0]) / 20 + "s from its bell to its end, " + morning[2] + " lines");
                }
            }
            if (id[0] == null) {
                if (Founding.near(level, heart, 8)) return;
                Villages.Village v = Villages.nearest(level, heart, 96);
                if (v == null) { finish(helper, busy, "no village at " + heart); return; }
                id[0] = v.id();
                Villages.ageForTests(v.id(), Villages.Age.IRON);
                Kit.stockForGrowth(level, v);
                int built = 0;
                for (int i = 0; i < 24; i++) {
                    String p = AdminTools.nextProject(v.id());
                    if (p == null) break;
                    if (AdminTools.buildProject(level, v, p, false).ok()) built++;
                }
                Kit.stockForGrowth(level, v);
                Kit.log("[VT] " + label + " founded " + Villages.name(v.id()) + " with " + Villages.headcount(v.id()) + " folk; " + built
                    + " buildings put up: " + Ledger.buildings(v.id()).size() + " in its register");
                level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L);           // the next morning, six o'clock
                began[0] = level.getGameTime();
                return;
            }
            Villages.Village v = Villages.get(id[0]);
            if (v == null) { finish(helper, busy, "the village is gone"); return; }
            long t = level.getDayTime() % 24000L;
            if (t < FROM) return;
            if (t >= TO) {
                double mean = shares.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
                double worst = shares.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
                Kit.log("[VT] " + label + " (pastimes " + (busy ? "on" : "off") + ") idle by day: mean " + Math.round(mean * 1000) / 10.0
                    + "%, worst " + Math.round(worst * 1000) / 10.0 + "% over " + shares.size() + " counts; longest stood still "
                    + longest[0] / 20 + "s (" + longWho[0] + "); idle by why " + idleWhy + "; pastimes begun " + Busywork.begunForTests());
                if (!busy) { finish(helper, false, null); return; }
                Kit.Expect expect = new Kit.Expect();
                expect.that(shares.size() >= 20, "counted through the day: " + shares.size());
                expect.that(mean < 0.15, "under 15% stood idle by day on average: " + Math.round(mean * 1000) / 10.0 + "%");
                expect.that(longest[0] <= 1200, "nobody stood still over a minute: " + longest[0] / 20 + "s (" + longWho[0] + ")");
                expect.that(waiting[0] <= 3, "no town work left waiting on a hand while hands stood idle, but for a passing count or three as a hand walks to it: " + waiting[0]);
                expect.that(morning[0] < 0 || morning[1] > 0 && morning[1] - morning[0] <= 1200 && morning[2] <= 5,
                    "the morning assembly short: " + (morning[1] - morning[0]) / 20 + "s, " + morning[2] + " lines");
                finish(helper, true, expect.clean() ? null : expect.summary());
                return;
            }
            if (level.getGameTime() < nextCount[0]) return;
            nextCount[0] = level.getGameTime() + EVERY;
            IdleCensus.Count c = IdleCensus.count(level, v.id());
            if (c.folk() < 10) return;
            shares.add(c.share());
            if (c.longestStill() > longest[0]) { longest[0] = c.longestStill(); longWho[0] = c.longestWho(); }
            for (Map.Entry<String, Integer> e : c.idleWhy().entrySet()) idleWhy.merge(e.getKey(), e.getValue(), Integer::sum);
            Kit.log("[VT] " + label + " " + String.format("%02d:%02d", (t / 1000 + 6) % 24, t % 1000 * 60 / 1000) + " " + c.line());
            Kit.log("[VT] " + label + "   " + c.table());
            if (!c.unstaffed().isEmpty() && c.spareIdle() > 0) waiting[0]++;
            for (String l : c.idleLines()) Kit.log("[VT] " + label + "   idle: " + (l.length() > 240 ? l.substring(0, 240) : l));
        });
    }

    private static void finish(GameTestHelper helper, boolean busy, String failure) {
        Busywork.ENABLED = true;
        TownJobs.instantForTests(true);
        if (failure == null) helper.succeed();
        else helper.fail(failure);
    }

    // ============================================================ lf06: the town's work never waits on a hand while one stands idle

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "lf06_idle_hand_called")
    public static void lf06_idle_hand_called(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        Busywork.resetForTests();
        TownJobs.instantForTests(false);
        level.setDayTime(2000);
        int cx = X + 24000, cz = Z;
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, 40, 2806);
        boolean was = VillageCommands.SPAWNAT_LEVELS;
        VillageCommands.SPAWNAT_LEVELS = false;
        Kit.command(level, "village spawnat " + cx + " " + cz + " 16");
        VillageCommands.SPAWNAT_LEVELS = was;
        Villages.Village v = Villages.nearest(level, new BlockPos(cx, 0, cz), 96);
        helper.assertTrue(v != null && Villages.headcount(v.id()) == 16, "a town of sixteen");
        helper.runAtTickTime(40, () -> {
            BlockPos spot = Kit.surface(level, cx + 6, cz + 6);
            // The town's share of hands for its works taken: four works, a hand each (one in four of sixteen).
            int taken = 0;
            for (int i = 0; i < 6; i++) {
                TownJobs.atWork(level, v, "lf06_" + i, spot.offset(i, 0, 0), "testing " + i);
                if (TownJobs.handAt(level, v.id(), "lf06_" + i) != null) taken++;
            }
            Kit.log("[VT] lf06 works given a hand: " + taken + " of 6");
            // One more folk, with nothing at all to do, and more of the town's work posted.
            com.jrpetty.mcassistant.entity.VillageFolkEntity idle = null;
            for (com.jrpetty.mcassistant.entity.AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity f && !TownJobs.busy(f) && f.stationTask() != com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.GUARD) { idle = f; break; }
            }
            helper.assertTrue(idle != null, "a folk not on the town's work");
            Busywork.idleForTests(idle);
            TownJobs.atWork(level, v, "lf06_more", spot.offset(0, 0, 4), "testing more");
            com.jrpetty.mcassistant.entity.VillageFolkEntity got = TownJobs.handAt(level, v.id(), "lf06_more");
            Kit.log("[VT] lf06 the work posted past the town's share: " + (got == null ? "no hand" : got.clientName()) + "; the idle one " + idle.clientName()
                + "; waiting " + TownJobs.unstaffed(v.id(), level.getGameTime()));
            Kit.Expect expect = new Kit.Expect();
            expect.that(got != null, "the work given a hand though the town's share was taken");
            expect.that(got == idle, "the hand stood idle, first: " + (got == null ? "none" : got.clientName()));
            expect.that(!TownJobs.unstaffed(v.id(), level.getGameTime()).contains("lf06_more"), "not left waiting on a hand");
            TownJobs.instantForTests(true);
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    // ============================================================ lf05: the morning assembly, bell to back at work

    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "lf05_morning")
    public static void lf05_morning(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(500);
        int cx = X + 20000, cz = Z;
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, TownPlan.reach() + 8, 2805);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.command(level, "village found " + FOLK + " " + cx + " " + cz);
        long[] t = { -1, -1, 0 };
        Villages.Village[] town = { null };
        helper.onEachTick(() -> {
            if (town[0] != null || Founding.near(level, heart, 8)) return;
            Villages.Village v = Villages.nearest(level, heart, 96);
            if (v == null) { helper.fail("no town"); return; }
            town[0] = v;
            Villages.ageForTests(v.id(), Villages.Age.IRON);
            Kit.stockForGrowth(level, v);
            for (int i = 0; i < 16; i++) {
                String p = AdminTools.nextProject(v.id());
                if (p == null) break;
                AdminTools.buildProject(level, v, p, false);
            }
            level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 300);
            boolean on = com.jrpetty.mcassistant.entity.Assemblies.morningForTests(level, v);
            t[0] = level.getGameTime();
            Kit.log("[VT] lf05 the morning bell in a town of " + Villages.headcount(v.id()) + " with " + Ledger.buildings(v.id()).size()
                + " buildings: " + on + " " + com.jrpetty.mcassistant.entity.Assemblies.debug(v.id()));
        });
        helper.onEachTick(() -> {
            if (t[0] < 0) return;
            Villages.Village v = town[0];
            String dbg = com.jrpetty.mcassistant.entity.Assemblies.debug(v.id());
            int[] p = com.jrpetty.mcassistant.entity.Assemblies.progress(v.id());
            if (p != null) t[2] = Math.max(t[2], p[4]);
            if ((level.getGameTime() - t[0]) % 100 == 0) Kit.log("[VT] lf05 +" + (level.getGameTime() - t[0]) + " " + dbg);
            if (dbg.startsWith("MORNING")) {
                if (level.getGameTime() - t[0] > 1400) helper.fail("the morning assembly still going after 70s: " + dbg);
                return;
            }
            long took = level.getGameTime() - t[0];
            // Back at work: none of them still held by it.
            int held = 0;
            for (com.jrpetty.mcassistant.entity.AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity f && com.jrpetty.mcassistant.entity.Assemblies.attending(f)) held++;
            }
            Kit.log("[VT] lf05 the morning assembly: " + took / 20 + "s from the bell to its end, " + t[2] + " lines; still held after: " + held);
            Kit.Expect expect = new Kit.Expect();
            expect.that(t[2] >= 3 && t[2] <= 5, "a short script, three to five lines: " + t[2]);
            expect.that(took <= 1200, "over within a minute of its bell: " + took / 20 + "s");
            expect.that(held == 0, "nobody lingers after it: " + held);
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    // ============================================================ lf02: spawnat's square, its camp no building

    @GameTest(template = EMPTY, timeoutTicks = 8000, batch = "lf02_spawnat_square")
    public static void lf02_spawnat_square(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        int cx = X + 8000, cz = Z;
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.HILLS, cx, cz, 90, 2802);
        boolean was = VillageCommands.SPAWNAT_LEVELS;
        VillageCommands.SPAWNAT_LEVELS = true;
        List<String> said = Kit.command(level, "village spawnat " + cx + " " + cz + " 12");
        Villages.Village v = Villages.nearest(level, new BlockPos(cx, 0, cz), 96);
        Kit.log("[VT] lf02 spawnat: " + said + "; " + (v == null ? "no village" : Villages.name(v.id()) + " of " + Villages.headcount(v.id())
            + " at " + v.centre().toShortString()));
        helper.assertTrue(v != null && Founding.near(level, v.centre(), 16), "spawnat founds the village and levels its ground");
        BlockPos heart = v.centre();
        helper.onEachTick(() -> {
            if (Founding.near(level, heart, 16)) return;
            VillageCommands.SPAWNAT_LEVELS = was;
            TerraformAudit.Done d = TerraformAudit.near(heart, 40);
            if (d == null) { helper.fail("the levelling left nothing to audit"); return; }
            Map<String, Integer> c = TerraformAudit.scan(level, d);
            Kit.log("[VT] lf02 AUDIT y=" + d.level() + " " + TerraformAudit.line(c) + (TerraformAudit.FIRST.isEmpty() ? "" : " | first: " + TerraformAudit.FIRST));
            Kit.Expect expect = new Kit.Expect();
            expect.that(c.get("square") > 1500, "the square levelled: " + c.get("square") + " columns");
            expect.that(c.get("heldOff") == 0, "no column of the square held off for the town's own camp: " + c.get("heldOff"));
            expect.that(c.get("offLevel") == 0, "the square level: offLevel " + c.get("offLevel"));
            // The camp pitched again on the level ground, round the heart, with its stores.
            int beds = 0, onLevel = 0;
            int reach = com.jrpetty.mcassistant.VillageSpawner.CAMP_REACH + 1;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) for (int y = d.level() - 6; y <= d.level() + 6; y++) {
                BlockState s = level.getBlockState(m.set(heart.getX() + dx, y, heart.getZ() + dz));
                if (!(s.getBlock() instanceof BedBlock) || s.getValue(BedBlock.PART) != BedPart.FOOT) continue;
                beds++;
                if (y == d.level() + 1) onLevel++;
            }
            expect.that(beds >= 8, "the camp's beds round the heart: " + beds);
            expect.that(onLevel == beds, "every camp bed on the level ground: " + onLevel + " of " + beds);
            boolean stores = false;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int y = d.level() - 1; y <= d.level() + 2; y++) {
                BlockPos p = new BlockPos(heart.getX() + dx, y, heart.getZ() + dz);
                if (level.getBlockEntity(p) instanceof Container ch && ZoneChests.isVillageStore(level.getBlockEntity(p))) {
                    for (int i = 0; i < ch.getContainerSize(); i++) if (ch.getItem(i).is(com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get())) stores = true;
                }
            }
            expect.that(stores, "the founding stores (the storehouse in pieces) back in a chest by the heart");
            expect.that(Villages.headcount(v.id()) == 12, "nobody lost: " + Villages.headcount(v.id()));
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    // ============================================================ lf03: a pit of a square and its wall, made good

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "lf03_square_mend")
    public static void lf03_square_mend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        SquareMend.resetForTests();
        level.setDayTime(1000);
        int cx = X + 12000, cz = Z;
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, 40, 2803);
        // The square flat at one height, a stretch of it each side of the heart.
        BlockPos h0 = Kit.surface(level, cx, cz);
        int lv = h0.getY() + 9;                                  // up off a flat world's floor, room for a pit under it
        for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) column(level, cx + dx, cz + dz, lv);
        UUID town = UUID.randomUUID();                          // a town of its own, with no board put up on the square
        Villages.restore(level, town, new BlockPos(cx, lv + 1, cz), Villages.Age.STONE, List.of("fortify"), 12);
        Villages.Village v = Villages.get(town);
        // Ironmouth's pit: the north-west quarter of the square from two to six below, its wall included.
        for (int dx = -14; dx <= -2; dx++) for (int dz = -14; dz <= -2; dz++) {
            int deep = 2 + Math.floorMod(dx * 7 + dz * 3, 5);
            column(level, cx + dx, cz + dz, lv - deep);
        }
        // A torch stood in a hole of it, as the town lit its pit.
        BlockPos hole = new BlockPos(cx - 6, Kit.surface(level, cx - 6, cz - 6).getY(), cz - 6);
        level.setBlock(hole, Blocks.TORCH.defaultBlockState(), 3);
        // The wall round the square laid on the ground as it was: three courses of cobble on each column of its ring.
        int r = TownPlan.PLAZA;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int g = Terraform.groundY(level, cx + dx, cz + dz);
            for (int k = 1; k <= 3; k++) level.setBlock(new BlockPos(cx + dx, g + k, cz + dz), Blocks.COBBLESTONE.defaultBlockState(), 2 | 16);
        }
        // Loose things on it: two crafting tables stacked by a chest, a stray block, a tree seeded in it.
        com.jrpetty.mcassistant.village.Ledger.built(town, "storehouse", new BlockPos(cx + 40, lv + 1, cz), Direction.NORTH);
        level.setBlock(new BlockPos(cx + 5, lv + 1, cz + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(cx + 5, lv + 2, cz + 2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(cx + 6, lv + 1, cz + 2), Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(new BlockPos(cx + 3, lv + 1, cz - 4), Blocks.COBBLESTONE.defaultBlockState(), 3);
        for (int k = 1; k <= 4; k++) level.setBlock(new BlockPos(cx + 4, lv + k, cz + 6), Blocks.OAK_LOG.defaultBlockState(), 3);
        level.setBlock(new BlockPos(cx + 4, lv + 5, cz + 6), Blocks.OAK_LEAVES.defaultBlockState(), 3);
        // A tall stray spire on the wall, and a red brick in it.
        for (int k = 4; k <= 8; k++) level.setBlock(new BlockPos(cx + 13, Terraform.groundY(level, cx + 13, cz + 6) + k, cz + 6), Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(cx - 13, Terraform.groundY(level, cx - 13, cz + 5) + 2, cz + 5), Blocks.BRICKS.defaultBlockState(), 3);
        String done = SquareMend.nowForTests(level, v);
        Kit.log("[VT] lf03 the mend: " + done + "; the level noted y=" + SquareMend.level(v));
        Map<String, Integer> a = com.jrpetty.mcassistant.entity.SquareDress.audit(level, v, lv);
        Kit.log("[VT] lf03 AUDIT " + a);
        Kit.Expect expect = new Kit.Expect();
        int floating = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int y = lv - 8; y <= lv + 12; y++) {
                BlockState s = level.getBlockState(new BlockPos(cx + dx, y, cz + dz));
                if (s.is(Blocks.COBBLESTONE) || s.is(Blocks.BRICKS)) floating++;
            }
        }
        expect.that(done != null && done.startsWith("mended"), "the square made good: " + done);
        expect.that(a.get("columns") > 500, "the square's columns audited: " + a.get("columns"));
        expect.that(a.get("holes") == 0, "no holes in its floor: " + a.get("holes"));
        expect.that(a.get("notPaving") == 0, "every column paved (no turf, no earth): " + a.get("notPaving"));
        expect.that(a.get("loose") == 0, "no table, chest or stray block loose on it: " + a.get("loose"));
        expect.that(level.getBlockState(new BlockPos(cx + 4, lv + 2, cz + 6)).isAir(), "the tree seeded in it gone");
        expect.that(a.get("wallGaps") == 0, "the wall whole round it, no gaps: " + a.get("wallGaps"));
        expect.that(a.get("wallTops") <= 3, "the wall to its one drawing (coping and towers, nothing else): " + a.get("wallTops") + " heights");
        expect.that(floating == 0, "none of the old wall (its cobble, its brick, its spire) left: " + floating);
        expect.that(level.getBlockState(new BlockPos(cx - 6, lv + 1, cz - 6)).is(Blocks.TORCH) || level.getBlockState(new BlockPos(cx - 6, lv, cz - 6)).isSolid(), "the hole's torch stood again, or the floor solid there");
        expect.that(SquareMend.level(v) == lv, "the square's level kept for the town's levelling by hand: " + SquareMend.level(v));
        expect.that(SquareMend.nowForTests(level, v).startsWith("mended 0"), "and a second look finds nothing to mend");
        if (expect.clean()) helper.succeed();
        else helper.fail(expect.summary());
    }

    /** A column of meadow with its top at {@code top}: earth under, grass on top, air over. */
    private static void column(ServerLevel level, int x, int z, int top) {
        for (int y = top - 16; y < top; y++) level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
        level.setBlock(new BlockPos(x, top, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
        for (int y = top + 1; y <= top + 20; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
    }

    // ============================================================ lf04: the chimneys

    /** The hearths' drawings, every one a stack out of its roof. */
    private static final String[] HEARTHS = { "house", "house2", "villa", "bakery", "cafe", "brewery", "tavern", "restaurant" };

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "lf04_chimney")
    public static void lf04_chimney(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.Expect expect = new Kit.Expect();
        // Every hearth's drawing: one stack, upright, two or three over the roof where it comes out, nothing outside the walls.
        for (String name : HEARTHS) expect.that(stack(name).isEmpty(), name + ": " + (stack(name).isEmpty() ? "an upright stack out of the roof" : stack(name)));
        // A house put up: its chimney's top found, its fire laid on it, and an empty house's hearth cold.
        int cx = X + 16000, cz = Z;
        Kit.hold(level, cx, cz, 24);
        Kit.prepare(level, cx, cz, 24);
        BlockPos anchor = Kit.surface(level, cx, cz);
        BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.Building b = new Ledger.Building("house", anchor, Direction.NORTH);
        List<BlockPos> tops = TownLife.fittings(b).chimneys();
        expect.that(tops.size() == 1, "one chimney top: " + tops);
        if (tops.size() == 1) {
            BlockPos top = tops.get(0);
            int under = 0;
            for (BlockPos p = top; level.getBlockState(p).isSolid() && under < 20; p = p.below()) under++;
            expect.that(under >= 8, "a stack straight down from its top to the hearth: " + under + " deep");
            expect.that(Math.abs(top.getX() - anchor.getX()) <= 3 && Math.abs(top.getZ() - anchor.getZ()) <= 3, "inside the house's walls");
            TownLife.dressNow(level, UUID.randomUUID(), anchor, List.of(b), List.of());
            BlockState fire = level.getBlockState(top.above());
            expect.that(fire.is(Blocks.CAMPFIRE) && fire.getValue(CampfireBlock.LIT), "a fire laid on its top, smoking: " + fire);
            UUID town = UUID.randomUUID();
            Villages.restore(level, town, anchor.offset(30, 0, 0), Villages.Age.STONE, List.of("house"), 4);
            Villages.Village v = Villages.get(town);
            Ledger.built(v.id(), "house", anchor, Direction.NORTH);
            TownJobs.instantForTests(true);
            TownLife.hearthForTests(level, b, v);
            fire = level.getBlockState(top.above());
            expect.that(fire.is(Blocks.CAMPFIRE) && !fire.getValue(CampfireBlock.LIT), "nobody lives there: its hearth cold, the fire out (its cap): " + fire);
        }
        if (expect.clean()) helper.succeed();
        else helper.fail(expect.summary());
    }

    /** What is wrong with a drawing's chimney, or "" if it is an upright stack out of the roof. */
    static String stack(String name) {
        List<Blueprints.Cell> cells = Blueprints.cells(name);
        if (cells.isEmpty()) return "no drawing";
        Map<Long, Blueprints.Cell> at = new java.util.HashMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Blueprints.Cell c : cells) {
            at.put(key(c.dx(), c.h(), c.dz()), c);
            boolean wall = c.key().part() == BuildGoal.Part.BLOCK && (c.key().style() == Blueprints.Style.POST || c.key().style() == Blueprints.Style.WALL_LOW);
            if (wall) { minX = Math.min(minX, c.dx()); maxX = Math.max(maxX, c.dx()); minZ = Math.min(minZ, c.dz()); maxZ = Math.max(maxZ, c.dz()); }
        }
        // The chimney's top: masonry with nothing over it, four up or more, with masonry under it.
        Blueprints.Cell top = null;
        for (Blueprints.Cell c : cells) {
            if (!masonry(c) || c.h() < 4 || at.containsKey(key(c.dx(), c.h() + 1, c.dz()))) continue;
            Blueprints.Cell b1 = at.get(key(c.dx(), c.h() - 1, c.dz())), b2 = at.get(key(c.dx(), c.h() - 2, c.dz()));
            if (b1 == null || b2 == null || !masonry(b1) || !masonry(b2)) continue;
            if (top == null || c.h() > top.h()) top = c;
        }
        if (top == null) return "no chimney";
        if (top.dx() < minX || top.dx() > maxX || top.dz() < minZ || top.dz() > maxZ) return "its stack stands outside the walls at " + top.dx() + "," + top.dz();
        // Where it comes out: the roof round it, the highest roof cell beside its column.
        int roof = Integer.MIN_VALUE;
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            if (ox == 0 && oz == 0) continue;
            for (int h = 0; h <= top.h(); h++) {
                Blueprints.Cell c = at.get(key(top.dx() + ox, h, top.dz() + oz));
                if (c != null && c.key().part() == BuildGoal.Part.BLOCK && BuildGoal.isRoof(c.key().style())) roof = Math.max(roof, h);
            }
        }
        if (roof == Integer.MIN_VALUE) return "no roof round its top";
        int over = top.h() - roof;
        if (over < 1 || over > 3) return "its top " + over + " over the roof beside it";
        // Upright: the whole stack one column, masonry from its top down to the roof and under it.
        for (int h = top.h(); h > roof - 2; h--) {
            Blueprints.Cell c = at.get(key(top.dx(), h, top.dz()));
            if (c == null || !masonry(c)) return "a gap in its stack at h" + h;
        }
        // Nothing of a chimney left outside the walls (an old stack up the gable).
        Set<Long> outside = new HashSet<>();
        for (Blueprints.Cell c : cells) {
            if (!masonry(c) || c.h() < 1) continue;
            if (c.dx() < minX || c.dx() > maxX || c.dz() < minZ || c.dz() > maxZ) outside.add(key(c.dx(), 0, c.dz()));
        }
        return outside.isEmpty() ? "" : "masonry outside its walls in " + outside.size() + " columns";
    }

    private static boolean masonry(Blueprints.Cell c) {
        return c.key().part() == BuildGoal.Part.BLOCK && (c.key().style() == Blueprints.Style.MASONRY || c.key().style() == Blueprints.Style.BRICK);
    }

    private static long key(int dx, int h, int dz) {
        return ((long) (dx + 512) << 40) | ((long) (h + 512) << 20) | (dz + 512);
    }
}
