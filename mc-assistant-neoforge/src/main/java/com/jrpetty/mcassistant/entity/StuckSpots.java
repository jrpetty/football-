package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [paths] Where the town's folk get stuck, why, and the town's own faults mended for good.
 *
 * <p>A folk going nowhere gives its path up after a few seconds (AssistantEntity's stuck detector: a hop, a step aside,
 * then it stops and thinks again). Each time it does, the spot is looked at here: what stood in its way, toward the next
 * step of the path it was on. A block of earth or rubble in a street, a bank two blocks high with no step cut in it, a
 * hole in the road: the town's own ground, the town's own fault, and the folk that met it mends it there and then (the
 * block dug out and taken to the stores, a step cut, the hole filled from the stores). A fence, a wall, water, a drop off
 * the edge of the world: not the town's to mend, so the folk goes round, and the spot is written in the town's books. A
 * spot met again and again is the one to look at.
 *
 * <p>It also keeps, for each folk, how long it has been trying to get somewhere and getting nowhere ({@link #second}), so
 * the town's books (and the tests) can say how many have been stuck for more than half a minute, and where.
 */
public final class StuckSpots {

    private StuckSpots() {}

    /** How near two give-ups are the same spot. */
    private static final int SAME = 2;
    /** A spot mended this often keeps being spoiled again: left alone after that, and only written down. */
    private static final int MOST_MENDS = 6;
    /** How long going nowhere is "stuck" for the books: half a minute. */
    public static final int LONG = 600;
    /** How long a folk goes nowhere before it is helped out of where it is: twenty seconds, so none reaches LONG. */
    static final int RESCUE = 400;
    /** How far off a folk's goal may be for it to be set down beside it; further, it is set down on the nearest street. */
    static final int NEAR_GOAL = 24;
    /** How far round a stuck folk the nearest street is looked for. */
    static final int STREET_LOOK = 16;
    /** How deep a hole in a street is filled. */
    private static final int MOST_FILL = DoorWays.MOST_FILL;

    /** One spot: where, why last time, how often folk gave up there, and how often it was mended. */
    public static final class Spot {
        public final BlockPos at;
        public String cause;
        public int times, mended;
        public long last;

        Spot(BlockPos at, String cause) {
            this.at = at;
            this.cause = cause;
        }

        @Override
        public String toString() {
            return cause + " at " + at.getX() + " " + at.getY() + " " + at.getZ() + " x" + times + (mended > 0 ? " (mended " + mended + ")" : "");
        }
    }

    private static final Map<UUID, List<Spot>> SPOTS = new ConcurrentHashMap<>();

    /** For each folk: where it was when it last got anywhere, since when, and whether it is held there now. */
    private static final class Going {
        Vec3 from = Vec3.ZERO;
        int since;
        int wanting;
        int seconds;
        boolean counted, rescued;
    }

    private static final Map<UUID, Going> GOING = new ConcurrentHashMap<>();
    /** Folk stuck longer than {@link #LONG}, by village: who, where and for what. */
    private static final Map<UUID, List<String>> LONG_STUCK = new ConcurrentHashMap<>();

    /** Tests: help this folk out of where it is, going where its path goes. */
    public static boolean rescueForTests(ServerLevel level, AssistantEntity a) {
        return rescue(level, a, a.getNavigation().getPath());
    }

    public static void resetForTests() {
        SPOTS.clear();
        GOING.clear();
        LONG_STUCK.clear();
    }

    // ------------------------------------------------------------------ going nowhere

    /**
     * Once a second (AssistantEntity's stuck detector): is this folk getting anywhere? A folk that has wanted to be going
     * somewhere for most of the last half minute and is still within a block and a half of where it was is stuck; it is
     * counted once (each time it gets so), with where it was trying to go.
     */
    public static void second(AssistantEntity a, boolean wantsToMove) {
        if (!(a.level() instanceof ServerLevel)) return;
        if (GOING.size() > 4096) GOING.clear();              // the dead and the gone, now and then
        Going g = GOING.computeIfAbsent(a.getUUID(), k -> new Going());
        Vec3 here = a.position();
        boolean held = a.isPassenger() || a.isSleeping();
        if (held || here.distanceToSqr(g.from) > 2.25) {
            g.from = here;
            g.since = a.tickCount;
            g.wanting = 0;
            g.seconds = 0;
            g.counted = false;
            g.rescued = false;
            return;
        }
        g.seconds++;
        Path p = a.getNavigation().getPath();
        if (wantsToMove && p != null && p.getTarget().distSqr(a.blockPosition()) > 4.0) g.wanting++;
        if (!g.rescued && a.tickCount - g.since >= RESCUE && g.wanting * 2 >= g.seconds && a.ownerId() != null) {
            g.rescued = true;
            if (rescue((ServerLevel) a.level(), a, p)) return;      // moved: the count starts again from where it is
        }
        if (!g.counted && a.tickCount - g.since >= LONG && g.wanting * 2 >= g.seconds) {
            g.counted = true;
            UUID village = a.ownerId();
            if (village == null) return;
            BlockPos b = a.blockPosition();
            String to = p == null ? "-" : p.getTarget().toShortString();
            List<String> l = LONG_STUCK.computeIfAbsent(village, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
            if (l.size() >= 200) l.remove(0);
            l.add(a.getAssistantName() + " at " + b.toShortString() + " for " + (a.tickCount - g.since) / 20 + "s, going to " + to
                + " (" + why(a.level(), b, p) + ")");
            Ledger.note(village, "paths.longstuck", Integer.toString(count(village, "paths.longstuck") + 1));
            a.brain("stuck going nowhere for half a minute");
        }
    }

    /**
     * A folk that has gone nowhere for twenty seconds, on its town's ground, is helped out the way the town helps a hand that
     * cannot reach its plot or its chest (AssistantEntity.putBeside): set down beside where it was going if that is near,
     * else on the nearest street, where its path starts afresh. Never out in the wilds (the town's ground only), at most
     * once in a while for a folk (putBeside's own pace), and written in the town's books. True if it was moved.
     */
    static boolean rescue(ServerLevel level, AssistantEntity a, @Nullable Path p) {
        Villages.Village v = Villages.get(a.ownerId());
        if (v == null || !v.dim().equals(level.dimension())) return false;
        BlockPos here = a.blockPosition();
        int reach = Villages.townReach(v.id());
        if (Math.max(Math.abs(here.getX() - v.centre().getX()), Math.abs(here.getZ() - v.centre().getZ())) > reach) return false;
        BlockPos goal = p == null ? null : p.getTarget();
        boolean moved;
        if (goal != null && goal.distManhattan(here) <= NEAR_GOAL) moved = a.putBeside(goal);
        else {
            BlockPos street = nearestStreet(level, v, here);
            moved = street != null && a.putBeside(street);
        }
        if (!moved) return false;
        Ledger.note(v.id(), "paths.rescued", Integer.toString(count(v.id(), "paths.rescued") + 1));
        a.brain("going nowhere: helped out of where it was stuck");
        return true;
    }

    /** The nearest place on a street (or the square) within STREET_LOOK of here where a folk could stand. */
    @Nullable
    static BlockPos nearestStreet(ServerLevel level, Villages.Village v, BlockPos here) {
        for (int r = 1; r <= STREET_LOOK; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = here.getX() + dx, z = here.getZ() + dz;
                    if (!Footways.street(v, x, z) && !Footways.square(v, x, z)) continue;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, top, z);
                    if (Footways.standable(level, feet) && !level.getFluidState(feet).is(FluidTags.WATER)) return feet;
                }
            }
        }
        return null;
    }

    /** Folk of this village stuck for more than half a minute since the books were opened (or the tests began). */
    public static List<String> longStuck(UUID village) {
        List<String> l = LONG_STUCK.get(village);
        return l == null ? List.of() : List.copyOf(l);
    }

    // ------------------------------------------------------------------ giving up

    /**
     * A folk gave its path up here, going nowhere (AssistantEntity: called before it stops). What was in its way is
     * looked at, written down, and mended where it is the town's own ground and the town's own fault.
     */
    public static void gaveUp(AssistantEntity a, BlockPos here) {
        if (!(a.level() instanceof ServerLevel level)) return;
        UUID id = a.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !v.dim().equals(level.dimension())) return;
        int reach = Villages.townReach(id) + 8;
        if (Math.max(Math.abs(here.getX() - v.centre().getX()), Math.abs(here.getZ() - v.centre().getZ())) > reach) return;
        Path p = a.getNavigation().getPath();
        String cause = why(level, here, p);
        Spot s = spot(id, here, cause);
        s.times++;
        s.last = level.getGameTime();
        Ledger.note(id, "paths.stuck", Integer.toString(count(id, "paths.stuck") + 1));
        if (s.mended >= MOST_MENDS || !Footways.live()) return;
        Direction d = toward(here, p);
        if (d == null) return;
        if (mend(level, v, a, here, d, cause)) {
            s.mended++;
            Ledger.note(id, "paths.mended", Integer.toString(count(id, "paths.mended") + 1));
            a.swing(InteractionHand.MAIN_HAND);
            a.brain("mended the way where it was stuck: " + cause);
        }
    }

    private static Spot spot(UUID village, BlockPos here, String cause) {
        List<Spot> all = SPOTS.computeIfAbsent(village, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (all) {
            for (Spot s : all) {
                if (s.at.distManhattan(here) <= SAME) {
                    s.cause = cause;
                    return s;
                }
            }
            Spot s = new Spot(here.immutable(), cause);
            if (all.size() >= 400) all.remove(0);
            all.add(s);
            return s;
        }
    }

    /** Every spot of this village folk gave up at, the most often first. */
    public static List<Spot> spots(UUID village) {
        List<Spot> all = SPOTS.get(village);
        if (all == null) return List.of();
        List<Spot> out;
        synchronized (all) {
            out = new ArrayList<>(all);
        }
        out.sort(Comparator.comparingInt((Spot s) -> -s.times));
        return out;
    }

    /** A running count kept in the town's books. */
    public static int count(UUID village, String key) {
        String n = Ledger.note(village, key);
        if (n == null || n.isEmpty()) return 0;
        try {
            return Integer.parseInt(n.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The way the folk was trying to go from here: toward the next step of its path, or its end. */
    @Nullable
    static Direction toward(BlockPos here, @Nullable Path p) {
        if (p == null) return null;
        BlockPos next = p.isDone() ? p.getTarget() : p.getNextNodePos();
        if (next.getX() == here.getX() && next.getZ() == here.getZ()) {
            next = p.getTarget();
            for (int i = p.getNextNodeIndex() + 1; i < p.getNodeCount(); i++) {
                BlockPos n = p.getNodePos(i);
                if (n.getX() != here.getX() || n.getZ() != here.getZ()) { next = n; break; }
            }
        }
        int dx = next.getX() - here.getX(), dz = next.getZ() - here.getZ();
        if (dx == 0 && dz == 0) return null;
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** What stood in the folk's way, in a word or two. */
    static String why(net.minecraft.world.level.Level level, BlockPos here, @Nullable Path p) {
        Direction d = toward(here, p);
        if (d == null) return p == null ? "no path" : "at its path's end";
        BlockPos ahead = here.relative(d);
        BlockState feet = level.getBlockState(ahead), head = level.getBlockState(ahead.above());
        boolean feetOpen = Footways.passable(level, ahead), headOpen = Footways.passable(level, ahead.above());
        if (feet.is(BlockTags.FENCES) || feet.is(BlockTags.WALLS) || feet.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)
            return "a fence";
        if (feet.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return "a door";
        if (!feetOpen && !headOpen) {
            if (Footways.passable(level, ahead.above(2)) && Footways.passable(level, ahead.above(3))) return "a missing step";
            return "a wall";
        }
        if (!feetOpen) {
            if (!Footways.passable(level, here.above(2))) return "no room to step up";
            return "a block in the way";
        }
        if (!headOpen) return "a low block overhead";
        if (level.getFluidState(ahead).is(FluidTags.WATER) || level.getFluidState(ahead.below()).is(FluidTags.WATER)) return "water";
        int drop = 0;
        while (drop <= 8 && !Footways.floor(level, ahead.below(drop + 1))) drop++;
        if (drop > Footways.DROP) return "a drop";
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(ahead)).isEmpty()) return "somebody in the way";
        return "nothing to see";
    }

    /**
     * Mend the town's own fault in the way here, toward {@code d}: one block's work. Only on the town's own open ground (a
     * street, the square, the way to a door: never in a building, a field or a pen), and only what the town may touch: the
     * ground the world made, a stray block of earth or rubble, a hole in a street. True if something was done.
     */
    static boolean mend(ServerLevel level, Villages.Village v, AssistantEntity a, BlockPos here, Direction d, String cause) {
        BlockPos ahead = here.relative(d);
        if (!open(level, v, ahead)) return false;
        switch (cause) {
            case "a missing step" -> {
                // A bank two blocks high: its top block cut out, a step a folk can jump.
                BlockPos top = ahead.above();
                if (!Footways.stray(level, v, top, level.getBlockState(top))) return false;
                if (!TownBlocks.mayBreak(level, v, top, TownBlocks.Why.TIDY)) return false;
                dig(level, v, top);
                return true;
            }
            case "a block in the way", "a low block overhead", "a wall" -> {
                for (BlockPos c : new BlockPos[]{ ahead.above(), ahead }) {
                    BlockState st = level.getBlockState(c);
                    if (Footways.passable(level, c)) continue;
                    if (!Footways.stray(level, v, c, st)) return false;
                    // A block standing on the street's own level is a stray one; the ground under a bank is not.
                    if (c.equals(ahead) && !cause.equals("a block in the way")) return false;
                    if (!TownBlocks.mayBreak(level, v, c, TownBlocks.Why.TIDY)) return false;
                    dig(level, v, c);
                    return true;
                }
                return false;
            }
            case "a drop" -> {
                // A hole in a street: filled from the bottom, out of the stores, up to the street's level.
                if (!Footways.street(v, ahead.getX(), ahead.getZ()) && !Footways.square(v, ahead.getX(), ahead.getZ())) return false;
                int bottom = here.getY() - 1;
                while (bottom > here.getY() - 1 - MOST_FILL && !Footways.floor(level, ahead.atY(bottom - 1))) bottom--;
                if (!Footways.floor(level, ahead.atY(bottom - 1))) return false;
                BlockPos fill = ahead.atY(bottom);
                BlockState there = level.getBlockState(fill);
                if (!there.canBeReplaced() || there.getFluidState().is(FluidTags.LAVA)) return false;
                if (!TownBlocks.mayPlace(level, v, fill, TownBlocks.Why.ROAD)) return false;
                BlockState lay = DoorWays.takeFill(level, v, true);
                if (lay == null) return false;
                level.setBlockAndUpdate(fill, lay);
                TownBlocks.traced(v, fill, TownBlocks.Why.ROAD);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The town's own open ground: a street, the square or a lot's open ground, not in a building, a field or a pen. */
    static boolean open(ServerLevel level, Villages.Village v, BlockPos p) {
        int dx = p.getX() - v.centre().getX(), dz = p.getZ() - v.centre().getZ();
        if (Math.max(Math.abs(dx), Math.abs(dz)) > Villages.townReach(v.id())) return false;
        if (Villages.onFarmland(v.id(), dx, dz, 0, 0)) return false;
        if (Footways.builtOn(v, p.getX(), p.getZ())) return false;
        BlockState below = level.getBlockState(p.below());
        return !below.is(Blocks.FARMLAND);
    }

    private static void dig(ServerLevel level, Villages.Village v, BlockPos p) {
        BlockState there = level.getBlockState(p);
        for (ItemStack drop : Block.getDrops(there, level, p, null)) TownWork.give(level, v, drop);
        level.destroyBlock(p, false);
        TownBlocks.traced(v, p, TownBlocks.Why.TIDY);
    }

    /** A line for the town's books: how often its folk were stuck, how often the way was mended, the worst spot. */
    public static String line(UUID village) {
        int stuck = count(village, "paths.stuck"), mended = count(village, "paths.mended"), lng = count(village, "paths.longstuck");
        int helped = count(village, "paths.rescued");
        List<Spot> s = spots(village);
        return stuck + " times stuck, " + mended + " mended on the spot, " + helped + " helped out, " + lng + " stuck over half a minute"
            + (s.isEmpty() ? "" : "; worst: " + s.get(0));
    }
}
