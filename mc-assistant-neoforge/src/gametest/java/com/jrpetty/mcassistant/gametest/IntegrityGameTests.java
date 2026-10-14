package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Restore;
import com.jrpetty.mcassistant.entity.TownAudit;
import com.jrpetty.mcassistant.entity.TownBlocks;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [integrity] The town's buildings kept whole: the placement gate (TownBlocks), the survey and the repairs (Restore),
 * and the audit everyone's acceptance tests use (TownAudit).
 *
 * <p>Each on its own ground, x 1620000 to 1639999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class IntegrityGameTests {

    private static final int Z = 66000;

    // ------------------------------------------------------------------ the long growth

    /** Days the long growth runs (-Dintegrity.days), folk it is founded with, and whether the repairs are on. */
    private static final double DAYS = Double.parseDouble(System.getProperty("integrity.days", "8"));
    private static final int FOLK = Integer.getInteger("integrity.folk", 30);
    private static final boolean REPAIRS = !Boolean.getBoolean("integrity.norepair");
    private static final boolean STRICT = Boolean.getBoolean("integrity.strict");
    /** The age the town is put in at its founding (-Dintegrity.age), so it wants the whole run of its buildings. */
    private static final String AGE = System.getProperty("integrity.age", "IRON");

    /**
     * A town founded and grown flat out for some in-game days with every feature live (the watch up, the town's
     * identities and perks live, the works done by hand), the audit taken every day and at the end, every building
     * compared block by block with its drawing and every mismatch logged with what put it there where that can be
     * told. Not required (it runs for minutes); -Dintegrity.strict=true makes the acceptance bar fail it.
     */
    @GameTest(template = "empty", timeoutTicks = 700000, required = false, batch = "ti01_long_growth")
    public static void ti01_long_growth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Kit.reset(level);
        Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.Police.standDownForTests(false);
        com.jrpetty.mcassistant.entity.Identity.liveForTests();
        com.jrpetty.mcassistant.entity.Perks.liveForTests(true);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        Restore.standDownForTests(!REPAIRS);
        TownBlocks.traceForTests(true);
        int cx = 1620000, cz = Z;
        int outer = FoundingPlan.coreRadius(FOLK) + FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, cz, outer + 32);
        Kit.prepare(level, cx, cz, outer + 32);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.generousTerrain(level, cx, cz);
        Kit.log("ti01 found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + cz));
        server.tickRateManager().setTickRate(10000.0F);
        Growth g = new Growth(helper, level, heart);
        final boolean[] over = { false };
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            try {
                if (g.step()) over[0] = true;
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                throw ex;
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    /** The long growth's state from tick to tick. */
    private static final class Growth {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos heart;
        UUID id;
        long began = -1;
        int lastDay = -1;
        /** Each building's cells wrong when it was first seen standing (by anchor): what was never built. */
        final Map<Long, Set<Long>> firstSeen = new HashMap<>();

        Growth(GameTestHelper helper, ServerLevel level, BlockPos heart) {
            this.helper = helper;
            this.level = level;
            this.heart = heart;
        }

        boolean step() {
            long t = helper.getTick();
            if (began < 0) {
                if (Founding.near(level, heart, 8)) {
                    if (t % 1000 == 0) Kit.log("ti01 founding @" + t + " " + Founding.status(level.getServer()));
                    return false;
                }
                Villages.Village v = Villages.nearest(level, heart, 64);
                if (v == null) {
                    level.getServer().tickRateManager().setTickRate(20.0F);
                    helper.fail("no village at " + heart);
                    return true;
                }
                id = v.id();
                began = t;
                Villages.ageForTests(id, Villages.Age.valueOf(AGE));
                stock(v);
                Kit.log("ti01 town " + Villages.name(id) + " of " + Villages.headcount(id) + " founded at tick " + t);
                return false;
            }
            long ran = t - began;
            Villages.Village v = Villages.get(id);
            if (v == null) {
                level.getServer().tickRateManager().setTickRate(20.0F);
                helper.fail("the village is gone");
                return true;
            }
            if (ran % 4000 == 0) baseline(v);
            int day = (int) (ran / 24000L);
            if (day != lastDay) {
                lastDay = day;
                stock(v);
                TownAudit.Report r = TownAudit.check(level, v);
                Kit.log("ti01 day " + day + " " + Villages.ageOf(id).label + ", " + Villages.headcount(id) + " folk, "
                    + Ledger.buildings(id).size() + " buildings: " + r.summary());
                Kit.log("ti01 day " + day + " refusals " + TownBlocks.refusalCount() + ", repairs " + java.util.Arrays.toString(Restore.tally(id))
                    + ", going up " + Villages.sitesOf(id).keySet());
            }
            if (ran >= (long) (DAYS * 24000L)) {
                level.getServer().tickRateManager().setTickRate(20.0F);
                finish(v);
                return true;
            }
            return false;
        }

        /**
         * The stores topped up with what building takes, so the town grows at speed (a test of how it builds, not of
         * how it fells and quarries): wood, stone, glass, the fittings, each up to so much.
         */
        void stock(Villages.Village v) {
            Object[][] want = {
                { net.minecraft.world.item.Items.OAK_LOG, 256 }, { net.minecraft.world.item.Items.OAK_PLANKS, 384 },
                { net.minecraft.world.item.Items.COBBLESTONE, 512 }, { net.minecraft.world.item.Items.STONE_BRICKS, 192 },
                { net.minecraft.world.item.Items.OAK_STAIRS, 192 }, { net.minecraft.world.item.Items.OAK_SLAB, 128 },
                { net.minecraft.world.item.Items.STONE_BRICK_STAIRS, 64 }, { net.minecraft.world.item.Items.STONE_BRICK_SLAB, 64 },
                { net.minecraft.world.item.Items.GLASS_PANE, 128 }, { net.minecraft.world.item.Items.GLASS, 32 },
                { net.minecraft.world.item.Items.OAK_DOOR, 16 }, { net.minecraft.world.item.Items.TORCH, 64 },
                { net.minecraft.world.item.Items.LANTERN, 32 }, { net.minecraft.world.item.Items.CHEST, 24 },
                { net.minecraft.world.item.Items.BARREL, 24 }, { net.minecraft.world.item.Items.FURNACE, 8 },
                { net.minecraft.world.item.Items.CRAFTING_TABLE, 8 }, { net.minecraft.world.item.Items.LADDER, 64 },
                { net.minecraft.world.item.Items.OAK_FENCE, 64 }, { net.minecraft.world.item.Items.OAK_FENCE_GATE, 8 },
                { net.minecraft.world.item.Items.WHITE_BED, 16 }, { net.minecraft.world.item.Items.BRICKS, 128 },
                { net.minecraft.world.item.Items.BREAD, 128 }, { net.minecraft.world.item.Items.HAY_BLOCK, 16 },
                { net.minecraft.world.item.Items.BOOKSHELF, 16 }, { net.minecraft.world.item.Items.LECTERN, 4 },
                { net.minecraft.world.item.Items.WHITE_CARPET, 32 }, { net.minecraft.world.item.Items.POPPY, 32 },
                { net.minecraft.world.item.Items.SMOOTH_STONE_SLAB, 64 }, { net.minecraft.world.item.Items.SMOKER, 4 },
            };
            for (Object[] w : want) {
                net.minecraft.world.item.Item it = (net.minecraft.world.item.Item) w[0];
                int have = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(it));
                int need = (Integer) w[1] - have;
                while (need > 0) {
                    int k = Math.min(need, it.getDefaultMaxStackSize());
                    com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new net.minecraft.world.item.ItemStack(it, k));
                    need -= k;
                }
            }
        }

        /** Note what was wrong with each building the first time it is seen in the register. */
        void baseline(Villages.Village v) {
            for (Ledger.Building b : Ledger.buildings(id)) {
                if (firstSeen.containsKey(b.anchor().asLong()) || b.structure().equals("fortify")) continue;
                Set<Long> bad = new HashSet<>();
                for (Restore.Fix f : Restore.diff(level, id, b)) if (f.want() != null) bad.add(f.pos().asLong());
                firstSeen.put(b.anchor().asLong(), bad);
                if (!bad.isEmpty()) Kit.log("ti01 first seen: the " + TownBlocks.designOf(id, b) + " at " + b.anchor().toShortString()
                    + " already " + bad.size() + " cells short of its drawing");
            }
        }

        void finish(Villages.Village v) {
            baseline(v);
            TownAudit.Report r = TownAudit.check(level, v);
            Kit.log("ti01 FINAL " + Villages.ageOf(id).label + ", " + Villages.headcount(id) + " folk: " + r.summary());
            // Every building, its fidelity and its status.
            for (TownAudit.BuildingReport b : r.buildings) {
                Map<String, Integer> kinds = new TreeMap<>();
                for (Restore.Fix f : b.fixes()) kinds.merge(f.kind().toString(), 1, Integer::sum);
                Kit.log(String.format("ti01   %-14s at %s %5.1f%% (%d/%d) %s %s", b.design(), b.building().anchor().toShortString(),
                    b.fidelity() * 100, b.serving(), b.cells(), kinds, b.status()));
            }
            // Every mismatch, with what put it there; and whether it was ever right (built and broken since) or never.
            Map<String, Integer> causes = new TreeMap<>();
            int never = 0, since = 0;
            for (TownAudit.BuildingReport b : r.buildings) {
                Set<Long> first = firstSeen.getOrDefault(b.building().anchor().asLong(), Set.of());
                for (Restore.Fix f : b.fixes()) {
                    boolean wasBad = first.contains(f.pos().asLong());
                    if (f.want() != null) { if (wasBad) never++; else since++; }
                    String cause = TownAudit.causeOf(level, f.pos(), f.found());
                    String part = f.want() == null ? "clear" : f.want().part() + (f.want().part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.BLOCK ? "/" + f.want().style() : "");
                    causes.merge(b.design() + " " + f.kind() + " " + part + " <- " + cause + (f.want() == null ? "" : wasBad ? " (never right)" : " (broken since)"), 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : causes.entrySet()) Kit.log("ti01 cause x" + e.getValue() + ": " + e.getKey());
            Kit.log("ti01 mismatches never right " + never + ", broken since first seen " + since);
            for (String line : r.lines(400)) Kit.log("ti01 finding " + line);
            Map<String, Integer> refused = new TreeMap<>();
            for (TownBlocks.Refusal f : TownBlocks.refusals()) refused.merge(f.why() + " in " + f.zone() + (f.building() == null ? "" : " of " + f.building()), 1, Integer::sum);
            Kit.log("ti01 refusals " + TownBlocks.refusalCount() + " (last 400 by kind: " + refused + ")");
            Kit.log("ti01 repairs " + java.util.Arrays.toString(Restore.tally(id)) + "; on the list: " + Restore.queue(id).size()
                + "; scaffolds " + Restore.scaffolds(id).size());
            Kit.dashboard(level, heart, "ti01 end");
            if (STRICT) {
                Kit.Expect expect = new Kit.Expect();
                int unsound = 0;
                for (TownAudit.BuildingReport b : r.buildings) {
                    if (b.fidelity() < 1.0 && Restore.jobFor(id, b.building().anchor()) == null) unsound++;
                }
                expect.that(unsound == 0, "every building at 100% or on the repair list (" + unsound + " not)");
                expect.that(r.count(TownAudit.FOREIGN) == 0, "nothing foreign in a doorway, a way in or a stair (" + r.count(TownAudit.FOREIGN) + ")");
                expect.that(r.measure("blocks_on_roads") == 0, "nothing on the roads (" + r.measure("blocks_on_roads") + ")");
                expect.that(r.count(TownAudit.HALF_BUILT) == 0, "nothing half built (" + r.count(TownAudit.HALF_BUILT) + ")");
                if (!expect.clean()) { helper.fail(expect.summary()); return; }
            }
            helper.succeed();
        }
    }
}
