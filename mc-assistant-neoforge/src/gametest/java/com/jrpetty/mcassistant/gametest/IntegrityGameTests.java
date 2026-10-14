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

    // ------------------------------------------------------------------ the gate, the repairs, the scaffolds

    /** A village of one on flat ground, its stores a storehouse; and a shop stamped on a lot to the north, registered. */
    private record Town(UUID id, Villages.Village v, BlockPos heart, com.jrpetty.mcassistant.entity.VillageFolkEntity hand,
                        Ledger.Building shop) {}

    private static Town town(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        com.jrpetty.mcassistant.entity.VillageFolkEntity hand = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(hand != null, "a village");
        UUID id = hand.ownerId();
        storehouse(helper, level, heart.offset(6, 0, 6));
        // On the first lot north of the square, across the ring street, its door to the south (its back to the north).
        BlockPos at = Kit.surface(level, x + 8, Z - 22);
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "shop", at, net.minecraft.core.Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "shop", at, net.minecraft.core.Direction.NORTH);
        TownBlocks.invalidate(id);
        Ledger.Building shop = null;
        for (Ledger.Building b : Ledger.buildings(id)) if (b.anchor().equals(at)) shop = b;
        return new Town(id, Villages.get(id), heart, hand, shop);
    }

    private static void storehouse(GameTestHelper helper, ServerLevel level, BlockPos near) {
        BlockPos origin = Kit.surface(level, near.getX(), near.getZ());
        net.minecraft.world.level.block.state.BlockState unit = com.jrpetty.mcassistant.block.StorehouseBlock.loose();
        com.jrpetty.mcassistant.block.StorehouseBlock.hintFront(net.minecraft.core.Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(origin.offset(x, y, z), unit, 3);
            }
        } finally {
            com.jrpetty.mcassistant.block.StorehouseBlock.hintFront(null);
        }
    }

    private static void give(ServerLevel level, Town t, net.minecraft.world.item.Item it, int n) {
        while (n > 0) {
            int k = Math.min(n, it.getDefaultMaxStackSize());
            com.jrpetty.mcassistant.entity.Homes.storeForTests(level, t.v(), new net.minecraft.world.item.ItemStack(it, k));
            n -= k;
        }
    }

    /** The door of a building, and its way out. */
    private static BlockPos[] door(Town t) {
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan("shop",
                t.shop().anchor(), t.shop().facing(), 13)) {
            if (p.part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.DOOR) {
                return new BlockPos[]{ p.pos(), p.pos().relative(t.shop().facing().getOpposite()) };
            }
        }
        return null;
    }

    /** The gate's zones round a stamped shop, and what it lets in where. */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "ti02_gate_zones")
    public static void ti02_gate_zones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1622000);
        helper.assertTrue(t.shop() != null, "the shop is registered");
        BlockPos[] d = door(t);
        helper.assertTrue(d != null, "the shop has a door");
        BlockPos door = d[0], step = d[1], step2 = step.relative(t.shop().facing().getOpposite());
        Kit.Expect e = new Kit.Expect();
        e.that(TownBlocks.zone(level, t.v(), door) == TownBlocks.Zone.DOORWAY, "the door's cell is a doorway: " + TownBlocks.zone(level, t.v(), door));
        e.that(TownBlocks.zone(level, t.v(), door.above()) == TownBlocks.Zone.DOORWAY, "and the cell over it");
        e.that(TownBlocks.zone(level, t.v(), step) == TownBlocks.Zone.APPROACH, "the step in front is the way in: " + TownBlocks.zone(level, t.v(), step));
        e.that(TownBlocks.zone(level, t.v(), step2) == TownBlocks.Zone.APPROACH, "and the one in front of that: " + TownBlocks.zone(level, t.v(), step2));
        e.that(!TownBlocks.mayPlace(level, t.v(), step, TownBlocks.Why.LAMP), "no lamp post on the step");
        e.that(!TownBlocks.mayPlace(level, t.v(), step2.above(), TownBlocks.Why.WINDOW_BOX), "no window box over the way in");
        e.that(!TownBlocks.mayPlace(level, t.v(), door, TownBlocks.Why.FURNISH), "nothing put in the doorway");
        e.that(TownBlocks.mayPlace(level, t.v(), step, TownBlocks.Why.DOORWAY), "the door-way works may cut a step there");
        BlockPos mid = t.shop().anchor().above(1);
        e.that(TownBlocks.zone(level, t.v(), mid) == TownBlocks.Zone.BUILDING, "the middle of the shop is the building's: " + TownBlocks.zone(level, t.v(), mid));
        e.that(!TownBlocks.mayPlace(level, t.v(), mid, TownBlocks.Why.LAMP), "no lamp post inside it");
        BlockPos wall = null;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan("shop",
                t.shop().anchor(), t.shop().facing(), 13)) {
            if (p.style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL) { wall = p.pos(); break; }
        }
        e.that(wall != null && !TownBlocks.mayPlace(level, t.v(), wall, TownBlocks.Why.FURNISH), "nothing furnished into a wall");
        e.that(wall != null && TownBlocks.mayBuild(level, t.v(), wall, t.shop().anchor(), TownBlocks.Why.REPAIR), "its own repairer may set its wall");
        e.that(wall != null && !TownBlocks.mayBuild(level, t.v(), wall, t.heart(), TownBlocks.Why.BUILD), "another building's builder may not");
        // A road: the avenue south of the square, in its middle and at its edge.
        BlockPos avenue = Kit.surface(level, t.heart().getX(), t.heart().getZ() + 30);
        e.that(TownBlocks.zone(level, t.v(), avenue) == TownBlocks.Zone.ROAD, "the avenue is road: " + TownBlocks.zone(level, t.v(), avenue));
        e.that(!TownBlocks.mayPlace(level, t.v(), avenue, TownBlocks.Why.LAMP), "no lamp in the middle of the avenue");
        BlockPos kerb = Kit.surface(level, t.heart().getX() + com.jrpetty.mcassistant.village.TownPlan.AVENUE, t.heart().getZ() + 30);
        e.that(TownBlocks.mayPlace(level, t.v(), kerb, TownBlocks.Why.LAMP), "a lamp at its edge");
        e.that(!TownBlocks.mayPlace(level, t.v(), avenue, TownBlocks.Why.FIELD), "no field on it");
        BlockPos open = Kit.surface(level, t.heart().getX() + 30, t.heart().getZ() + 30);
        e.that(TownBlocks.mayPlace(level, t.v(), open, TownBlocks.Why.FIELD), "open ground is open: " + TownBlocks.zone(level, t.v(), open));
        if (!e.clean()) { helper.fail(e.summary()); return; }
        helper.succeed();
    }

    /** A shop knocked about (holes in its walls, a roof stair gone, its door out, earth in its wall, a fence on its
     *  step) is found by the survey, listed, and mended by the town out of its stores to its drawing. */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "ti03_repairs")
    public static void ti03_repairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1624000);
        helper.assertTrue(t.shop() != null, "the shop is registered");
        helper.assertTrue(Restore.fidelity(level, t.id(), t.shop()) >= 0.999, "the shop stands whole as stamped: "
            + Restore.diff(level, t.id(), t.shop()));
        java.util.List<com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement> plan = com.jrpetty.mcassistant.entity.goal.BuildGoal.plan("shop",
            t.shop().anchor(), t.shop().facing(), 13);
        int walls = 0, stairs = 0;
        BlockPos dirtAt = null;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : plan) {
            if (p.style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL && walls < 4) {
                level.setBlock(p.pos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                walls++;
            } else if (p.style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL && dirtAt == null) {
                dirtAt = p.pos();
                level.setBlock(p.pos(), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
            } else if (p.style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_STAIR && stairs < 2) {
                level.setBlock(p.pos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                stairs++;
            }
        }
        BlockPos[] d = door(t);
        level.destroyBlock(d[0], false);
        level.setBlock(d[1], net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), 3);
        java.util.List<Restore.Fix> fixes = Restore.diff(level, t.id(), t.shop());
        Kit.log("ti03 knocked about: " + fixes.size() + " things wrong");
        helper.assertTrue(fixes.size() >= 9, "the diff sees what was done: " + fixes);
        give(level, t, net.minecraft.world.item.Items.OAK_PLANKS, 64);
        give(level, t, net.minecraft.world.item.Items.OAK_STAIRS, 16);
        give(level, t, net.minecraft.world.item.Items.OAK_DOOR, 2);
        Restore.standDownForTests(false);
        final BlockPos dirt = dirtAt;
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick % 20 != 0) return;
            Restore.survey(level, t.v());
            Restore.work(level, t.v(), 8);
            TownAudit.Report r = TownAudit.check(level, t.v(), TownAudit.FIDELITY);
            if (tick % 200 == 0) Kit.log("ti03 @" + tick + " " + r.summary() + " queue " + Restore.queue(t.id()).size());
            if (r.measure("fidelity") >= 0.999 && r.count(TownAudit.FOREIGN) == 0) {
                Kit.Expect e = new Kit.Expect();
                e.that(Restore.status(t.id(), t.shop().anchor(), level.getDayTime() / 24000L).equals("repaired"), "the shop says it was repaired: "
                    + Restore.status(t.id(), t.shop().anchor(), level.getDayTime() / 24000L));
                e.that(level.getBlockState(d[0]).getBlock() instanceof net.minecraft.world.level.block.DoorBlock, "its door is back");
                e.that(level.getBlockState(d[1]).isAir(), "its step is clear");
                e.that(!level.getBlockState(dirt).is(net.minecraft.world.level.block.Blocks.DIRT), "no earth in its wall");
                e.that(Restore.tally(t.id())[0] >= 9, "the town counts what it mended: " + java.util.Arrays.toString(Restore.tally(t.id())));
                Restore.standDownForTests(true);
                if (!e.clean()) { helper.fail(e.summary()); return; }
                helper.succeed();
            } else if (tick > 1900) {
                Restore.standDownForTests(true);
                helper.fail("not mended: " + r.summary() + " " + r.lines(20));
            }
        });
    }

    /** A building given up half built (its lot given up) is taken down, its blocks into the stores. */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "ti04_scaffold_cleared")
    public static void ti04_scaffold_cleared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1626000);
        BlockPos at = Kit.surface(level, t.heart().getX() - 30, t.heart().getZ() - 30);
        int laid = com.jrpetty.mcassistant.entity.goal.BuildGoal.stampOnly(level, "house", at, net.minecraft.core.Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK), p -> p.pos().getY() <= at.getY() + 1);
        helper.assertTrue(laid > 10, "half a house laid: " + laid);
        Restore.abandoned(t.id(), new Ledger.Building("house", at, net.minecraft.core.Direction.NORTH), level.getGameTime());
        int before = com.jrpetty.mcassistant.entity.Market.stock(level, t.id(), s -> true);
        Restore.standDownForTests(false);
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick % 20 != 0) return;
            Restore.tick(level, t.v());
            TownAudit.Report r = TownAudit.check(level, t.v(), TownAudit.HALF_BUILT);
            if (r.count(TownAudit.HALF_BUILT) == 0) {
                int after = com.jrpetty.mcassistant.entity.Market.stock(level, t.id(), s -> true);
                Restore.standDownForTests(true);
                Kit.log("ti04 cleared at tick " + tick + "; stores " + before + " -> " + after);
                if (after <= before) { helper.fail("what came down did not go into the stores: " + before + " -> " + after); return; }
                helper.succeed();
            } else if (tick > 1900) {
                Restore.standDownForTests(true);
                helper.fail("still half built: " + r.lines(10));
            }
        });
    }
}
