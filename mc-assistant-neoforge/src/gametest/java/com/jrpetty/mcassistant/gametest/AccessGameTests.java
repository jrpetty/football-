package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.Prisoners;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [access-3] The way up a ladder and through a gate, walked by the folk on their own feet.
 *
 * <p>ac01: the cottage grown a second storey (house2), the manor and the villa, each with its upper floor up a ladder,
 * built at different turns, and a folk of the town at each walking from the street in at the door, up the ladder to the
 * side of every bed upstairs, and back down and out into the street: its own way-finding and its own legs, nobody set
 * down anywhere (a folk moving more than a block and a half in a tick fails it), the way it plans going up the rungs.
 * ac02: the allotments, fenced, a gate at the front: a folk walks in through the gate to the cask of tools at the end of
 * the cross path and out again, and the gate is shut behind it; the hunter's lodge, its drying fire by the door: a folk
 * walks past the fire, in, to the chest and out, and is not burnt. ac03: the stockade: a free folk's way-finding goes in
 * and out through its gate; a prisoner of war's never does. ac04: a way planned from halfway up a ladder.
 *
 * <p>On ground of its own: x 2600000 to 2699999, z 70000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class AccessGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    /** One stop on a walk: where to stand (any of these), and what it is. */
    record Stop(List<BlockPos> at, String what) {}

    /** A town at x, its ground levelled from x0 to x1; its first folk. */
    private static VillageFolkEntity town(GameTestHelper helper, ServerLevel level, int x, int x1, int y) {
        Kit.reset(level);
        level.setDayTime(24000L * 2 + 2500);
        Kit.hold(level, (x + x1) / 2, Z, (x1 - x) / 2 + 40);
        Kit.prepare(level, (x + x1) / 2, Z, (x1 - x) / 2 + 40);
        FlatsAccessGameTests.ground(level, x - 16, x1, Z - 30, Z + 30, y, 24);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, new BlockPos(x, y, Z), 0.0F);
        helper.assertTrue(first != null, "a village");
        Villages.ageForTests(first.ownerId(), Villages.Age.IRON);
        return first;
    }

    /** A building of the drawing put up on the town's ground, made over for the Iron Age. */
    private static Ledger.Building put(ServerLevel level, UUID id, String name, BlockPos anchor, Direction facing) {
        BuildGoal.stamp(level, name, anchor, facing, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, name, anchor, facing);
        Ledger.Building b = new Ledger.Building(name, anchor, facing);
        Ages.now(level, Villages.get(id), b, Villages.Age.IRON);
        return b;
    }

    /** The street out in front of a building's door. */
    private static BlockPos street(Ledger.Building b) {
        int[] full = Blueprints.fullHalf(b.structure());
        return b.anchor().relative(b.facing().getOpposite(), full[1] + 3);
    }

    /** The places to stand beside a bed (by its foot or its head, square on or at a corner). */
    private static List<BlockPos> besideBed(ServerLevel level, BlockPos foot) {
        BlockState st = level.getBlockState(foot);
        BlockPos head = foot.relative(st.getValue(BedBlock.FACING));
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos part : List.of(foot, head)) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = part.offset(dx, 0, dz);
                if ((dx != 0 || dz != 0) && !out.contains(n) && FlatsAccessGameTests.standable(level, n)) out.add(n);
            }
        }
        return out;
    }

    /** Every bed's foot in the building at or over this height above its anchor. */
    private static List<BlockPos> beds(ServerLevel level, Ledger.Building b, int from) {
        List<BlockPos> out = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.pos().getY() - b.anchor().getY() < from) continue;
            for (BlockPos q : List.of(p.pos(), p.pos().relative(Direction.NORTH), p.pos().relative(Direction.SOUTH),
                    p.pos().relative(Direction.EAST), p.pos().relative(Direction.WEST))) {
                BlockState st = level.getBlockState(q);
                if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.FOOT && !out.contains(q.immutable())) out.add(q.immutable());
            }
        }
        return out;
    }

    private static boolean arrived(VillageFolkEntity w, BlockPos to) {
        return Math.abs(w.getX() - (to.getX() + 0.5)) < 0.75 && Math.abs(w.getZ() - (to.getZ() + 0.5)) < 0.75 && Math.abs(w.getY() - to.getY()) < 0.9;
    }

    /**
     * Walkers each going round its stops on its own feet, all at once; {@code then} when all are done. Fails at a lift, a
     * stop not got to in {@code most} ticks, or no way found to it. Counts the ways planned up or down a ladder in
     * {@code climbs} (a way with two places one over the other on a ladder).
     */
    private static void walk(GameTestHelper helper, ServerLevel level, List<VillageFolkEntity> walkers, List<List<Stop>> routes,
                             int most, int[] climbs, Runnable then) {
        int n = walkers.size();
        int[] at = new int[n], ticks = new int[n], refused = new int[n];
        boolean[] done = new boolean[n];
        Vec3[] last = new Vec3[n];
        BlockPos[] chosen = new BlockPos[n];
        boolean[] finished = { false };
        helper.onEachTick(() -> {
            if (finished[0]) return;
            for (int k = 0; k < n; k++) {
                if (done[k]) continue;
                VillageFolkEntity w = walkers.get(k);
                if (w.workZone() != null) w.setWorkZone(null);
                if (w.breakNowForTests() && level.getDayTime() % 24000 < 9000) level.setDayTime(level.getDayTime() + 200);
                List<Stop> route = routes.get(k);
                Stop stop = route.get(at[k]);
                String who = "walker " + k;
                Vec3 pos = w.position();
                if (last[k] != null && pos.distanceTo(last[k]) > 1.5) {
                    helper.fail(who + " was set down from " + last[k] + " to " + pos + " on the way to " + stop.what() + ": a walk, not a lift");
                }
                last[k] = pos;
                if (chosen[k] != null && arrived(w, chosen[k])) {
                    Kit.log("ac @" + helper.getTick() + " " + who + " at " + stop.what() + " (" + chosen[k].toShortString() + ") after " + ticks[k] + " ticks");
                    at[k]++;
                    ticks[k] = 0;
                    refused[k] = 0;
                    chosen[k] = null;
                    w.getNavigation().stop();
                    if (at[k] == route.size()) done[k] = true;
                    continue;
                }
                ticks[k]++;
                if (ticks[k] > most) {
                    helper.fail(who + " never got to " + stop.what() + " (" + (chosen[k] == null ? "-" : chosen[k].toShortString()) + "): it is at "
                        + w.blockPosition().toShortString() + " — " + w.debugLine());
                }
                PathNavigation nav = w.getNavigation();
                boolean mine = chosen[k] != null && nav.getPath() != null && chosen[k].equals(nav.getPath().getTarget());
                if (!mine || nav.isDone() || ticks[k] % 160 == 0) {
                    Path p = null;
                    BlockPos to = null;
                    for (BlockPos s : stop.at()) {
                        nav.stop();
                        Path q = nav.createPath(s, 0);
                        if (q != null && q.canReach()) { p = q; to = s; break; }
                    }
                    if (p == null) {
                        if (++refused[k] > 40) helper.fail(who + " finds no way from " + w.blockPosition().toShortString() + " to " + stop.what());
                        continue;
                    }
                    for (int i = 1; i < p.getNodeCount(); i++) {
                        Node a = p.getNode(i - 1), b = p.getNode(i);
                        if (a.x == b.x && a.z == b.z && Math.abs(a.y - b.y) == 1 && level.getBlockState(new BlockPos(b.x, Math.min(a.y, b.y), b.z)).is(BlockTags.CLIMBABLE)) {
                            climbs[0]++;
                            break;
                        }
                    }
                    chosen[k] = to;
                    nav.moveTo(p, 0.7);
                }
            }
            boolean all = true;
            for (boolean d : done) all &= d;
            if (all) {
                finished[0] = true;
                then.run();
            }
        });
    }

    /**
     * Upstairs by the ladder: house2 (its back to the north), the manor (to the east) and the villa (to the south), each
     * walked by a folk of the town from the street to the side of every bed up the ladder and back out to the street.
     */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "ac01_ladders_climbed")
    public static void ac01_ladders_climbed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2600000;
        int y = Math.max(70, Kit.surface(level, x, Z).getY());
        VillageFolkEntity first = town(helper, level, x, x + 130, y);
        UUID id = first.ownerId();
        String[] names = { "house2", "manor", "villa" };
        Direction[] turns = { Direction.NORTH, Direction.EAST, Direction.SOUTH };
        List<VillageFolkEntity> walkers = new ArrayList<>();
        List<List<Stop>> routes = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Ledger.Building b = put(level, id, names[i], new BlockPos(x + 35 + 34 * i, y, Z), turns[i]);
            List<Stop> route = new ArrayList<>();
            BlockPos street = street(b);
            route.add(new Stop(List.of(street), names[i] + "'s street"));
            List<BlockPos> up = beds(level, b, 3);
            helper.assertTrue(!up.isEmpty(), names[i] + ": beds up the ladder");
            for (BlockPos foot : up) {
                List<BlockPos> by = besideBed(level, foot);
                helper.assertTrue(!by.isEmpty(), names[i] + ": somewhere to stand by the bed at " + foot.toShortString());
                route.add(new Stop(by, names[i] + "'s bed upstairs at " + foot.toShortString()));
            }
            route.add(new Stop(List.of(street), names[i] + "'s street again"));
            routes.add(route);
            VillageFolkEntity w = i == 0 ? first : VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 2 + i, y, Z), 0.0F);
            helper.assertTrue(w != null, "a walker");
            w.moveTo(street.getX() + 0.5, street.getY(), street.getZ() + 0.5, 0.0F, 0.0F);
            w.getNavigation().stop();
            walkers.add(w);
            Kit.log("ac01 " + names[i] + " at " + b.anchor().toShortString() + " facing " + turns[i] + ": " + up.size() + " beds upstairs");
        }
        int[] climbs = { 0 };
        walk(helper, level, walkers, routes, 900, climbs, () -> {
            Kit.log("ac01 all three walked at tick " + helper.getTick() + "; ways planned up or down a ladder: " + climbs[0]);
            helper.assertTrue(climbs[0] >= 6, "the ways went up and down the ladders: " + climbs[0]);
            helper.succeed();
        });
    }

    /**
     * [health] Asked to go somewhere halfway up a ladder: a folk holding the manor's ladder, off the ground, plans its way
     * from the rung it holds (the game plans a walker's way only from the ground, so one asked anew on a ladder found no
     * way at all: ac01's walker, its walk planned afresh at the top of the manor's ladder, stood there on CI), and walks
     * it to a bed upstairs and down again to the street.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "ac04_planned_on_the_ladder")
    public static void ac04_planned_on_the_ladder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2660000;
        int y = Math.max(70, Kit.surface(level, x, Z).getY());
        VillageFolkEntity w = town(helper, level, x, x + 60, y);
        UUID id = w.ownerId();
        Ledger.Building b = put(level, id, "manor", new BlockPos(x + 35, y, Z), Direction.EAST);
        // The ladder's shaft: its lowest rung, and a rung two over it.
        BlockPos foot = null;
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (level.getBlockState(p.pos()).is(net.minecraft.world.level.block.Blocks.LADDER) && level.getBlockState(p.pos().above(2))
                    .is(net.minecraft.world.level.block.Blocks.LADDER) && (foot == null || p.pos().getY() < foot.getY())) foot = p.pos();
        }
        helper.assertTrue(foot != null, "the manor's ladder");
        BlockPos rung = foot.above(2);
        List<BlockPos> up = beds(level, b, 3);
        helper.assertTrue(!up.isEmpty(), "beds up the ladder");
        List<BlockPos> by = besideBed(level, up.get(0));
        helper.assertTrue(!by.isEmpty(), "somewhere to stand by the bed");
        w.moveTo(rung.getX() + 0.5, rung.getY() + 0.2, rung.getZ() + 0.5, 0.0F, 0.0F);
        w.setOnGround(false);                                   // in the rungs, off the ground (as it is once it has ticked there)
        w.getNavigation().stop();
        helper.assertTrue(w.onClimbable(), "holding the ladder at " + w.position());
        PathNavigation nav = w.getNavigation();
        Path p = null;
        for (BlockPos s : by) {
            Path q = nav.createPath(s, 0);
            if (q != null && q.canReach()) { p = q; break; }
        }
        Kit.log("ac04 on the rung at " + rung.toShortString() + ": a way to the bed upstairs "
            + (p == null ? "none" : p.getNodeCount() + " places from " + p.getNode(0).asBlockPos().toShortString()));
        helper.assertTrue(p != null, "a way planned from the rung it holds to the bed upstairs");
        helper.assertTrue(p.getNode(0).asBlockPos().equals(rung), "from the rung itself, not the foot of the shaft: " + p.getNode(0).asBlockPos().toShortString());
        nav.stop();
        List<Stop> route = List.of(new Stop(by, "the bed upstairs at " + up.get(0).toShortString()), new Stop(List.of(street(b)), "the street"));
        walk(helper, level, List.of(w), List.of(route), 900, new int[]{ 0 }, () -> {
            Kit.log("ac04 from the rung up to the bed and down to the street by tick " + helper.getTick());
            helper.succeed();
        });
    }

    /**
     * Through a gate and past a fire: the allotments (in at the gate to the cask of tools and out again, the gate shut
     * after) and the hunter's lodge (past its drying fire to the chest and out, unburnt).
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "ac02_gate_and_fire")
    public static void ac02_gate_and_fire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2620000;
        int y = Math.max(70, Kit.surface(level, x, Z).getY());
        VillageFolkEntity first = town(helper, level, x, x + 80, y);
        UUID id = first.ownerId();
        Ledger.Building plots = put(level, id, "allotments", new BlockPos(x + 35, y, Z), Direction.NORTH);
        Ledger.Building lodge = put(level, id, "huntlodge", new BlockPos(x + 65, y, Z), Direction.EAST);
        BlockPos gate = null, cask = null, chest = null;
        for (BuildGoal.Placement p : BuildGoal.plan("allotments", plots.anchor(), plots.facing(), 13)) {
            BlockState st = level.getBlockState(p.pos());
            if (st.getBlock() instanceof FenceGateBlock) gate = p.pos();
            if (st.getBlock() instanceof BarrelBlock) cask = p.pos();
        }
        for (BuildGoal.Placement p : BuildGoal.plan("huntlodge", lodge.anchor(), lodge.facing(), 13)) {
            if (level.getBlockState(p.pos()).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) chest = p.pos();
        }
        helper.assertTrue(gate != null && cask != null && chest != null, "a gate, a cask and a chest: " + gate + " " + cask + " " + chest);
        helper.assertTrue(!level.getBlockState(gate).getValue(FenceGateBlock.OPEN), "the gate shut to begin with");
        List<BlockPos> atCask = new ArrayList<>(), atChest = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (FlatsAccessGameTests.standable(level, cask.relative(d))) atCask.add(cask.relative(d));
            if (FlatsAccessGameTests.standable(level, chest.relative(d))) atChest.add(chest.relative(d));
        }
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 3, y, Z), 0.0F);
        helper.assertTrue(second != null, "a second folk");
        List<VillageFolkEntity> walkers = List.of(first, second);
        List<List<Stop>> routes = List.of(
            List.of(new Stop(List.of(street(plots)), "the allotments' street"), new Stop(atCask, "the cask of tools"),
                new Stop(List.of(street(plots).relative(plots.facing().getOpposite(), 3)), "out in the street again")),
            List.of(new Stop(List.of(street(lodge)), "the lodge's street"), new Stop(atChest, "the hunter's chest"),
                new Stop(List.of(street(lodge)), "the lodge's street again")));
        for (int k = 0; k < 2; k++) {
            BlockPos s = routes.get(k).get(0).at().get(0);
            walkers.get(k).moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.0F, 0.0F);
            walkers.get(k).getNavigation().stop();
        }
        BlockPos g = gate;
        int[] climbs = { 0 };
        walk(helper, level, walkers, routes, 700, climbs, () -> helper.runAfterDelay(30, () -> {
            boolean shut = !level.getBlockState(g).getValue(FenceGateBlock.OPEN);
            Kit.log("ac02 through the gate and back, the gate shut after: " + shut + "; past the fire: " + second.getRemainingFireTicks()
                + " fire ticks, hp " + second.getHealth());
            helper.assertTrue(shut, "the gate shut behind it");
            helper.assertTrue(second.getHealth() >= second.getMaxHealth() - 0.01F, "past the fire unburnt: hp " + second.getHealth());
            helper.succeed();
        }));
    }

    /** The stockade's gate: a free folk's way-finding goes through it, in and out; a prisoner of war's never. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ac03_stockade_kept")
    public static void ac03_stockade_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2640000;
        int y = Math.max(70, Kit.surface(level, x, Z).getY());
        VillageFolkEntity first = town(helper, level, x, x + 60, y);
        UUID id = first.ownerId();
        Ledger.Building pen = put(level, id, Prisoners.STOCKADE, new BlockPos(x + 30, y, Z), Direction.NORTH);
        // The other town, well away: two of its folk, one to be taken.
        Kit.hold(level, x + 260, Z, 24);
        Kit.prepare(level, x + 260, Z, 24);
        BlockPos there = Kit.surface(level, x + 260, Z);
        VillageFolkSpawnerBlock.raiseParty(level, there, 0.0F, 3);
        Villages.Village other = Villages.nearest(level, there, Villages.VILLAGE_RANGE);
        helper.assertTrue(other != null && !id.equals(other.id()), "a second town");
        helper.runAtTickTime(5, () -> {
            int took = Prisoners.takeNow(level, Villages.get(id), other, 1);
            helper.assertTrue(took == 1, "a prisoner taken: " + took);
            VillageFolkEntity pow = null;
            for (com.jrpetty.mcassistant.entity.AssistantEntity a : Villages.folkOf(other.id())) {
                if (a instanceof VillageFolkEntity f && Prisoners.isPow(f.getUUID())) pow = f;
            }
            helper.assertTrue(pow != null, "the prisoner");
            BlockPos inside = cellOf(pen, 0, 0, 1), outside = street(pen);
            // The free folk, in the pen: a way out through the gate; from the street, a way in.
            first.moveTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, 0, 0);
            first.getNavigation().stop();
            Path out = first.getNavigation().createPath(outside, 0);
            first.moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5, 0, 0);
            first.getNavigation().stop();
            Path in = first.getNavigation().createPath(inside, 0);
            // The prisoner, in the pen: no way out.
            pow.moveTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, 0, 0);
            pow.getNavigation().stop();
            Path flight = pow.getNavigation().createPath(outside, 0);
            Kit.log("ac03 a free folk's ways out and in: " + (out != null && out.canReach()) + ", " + (in != null && in.canReach())
                + "; the prisoner's way out: " + (flight != null && flight.canReach()));
            helper.assertTrue(out != null && out.canReach() && in != null && in.canReach(), "a free folk goes in and out of the stockade by its gate");
            helper.assertTrue(flight == null || !flight.canReach(), "a prisoner of war finds no way out of it: " + (flight == null ? null : flight.getEndNode()));
            pow.getNavigation().stop();
            helper.succeed();
        });
    }

    private static BlockPos cellOf(Ledger.Building b, int dx, int h, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz).above(h);
    }
}
