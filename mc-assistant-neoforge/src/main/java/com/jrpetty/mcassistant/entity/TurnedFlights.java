package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [growth22] A door high over the ground before it (a house on a terrace, its floor three and more above the slope) had its
 * way in run straight out from the door, a flight of three, four, five steps sticking out into the street like a jetty. A
 * real house's long flight turns: a landing at the door, level with the floor, and the steps running down along the wall
 * to the ground. Where the straight way would want {@link #LEAST} steps or more down, and there is room along the wall to
 * one side (open ground, nothing built, no other door, no bank in the way), the way in is made that way instead (DoorWays:
 * the landing and each step built up out of a hollow, cleared under a bank) and dressed that way (StepKit: each step a
 * stair facing up the flight, the landing left flat, filled under in the flight's stone, a cheek on its open side).
 */
public final class TurnedFlights {

    private TurnedFlights() {}

    /** The steps down a straight way would want before it is turned along the wall. */
    public static final int LEAST = 3;

    /** The side each door's flight was turned to (kept, so a flight half built is finished the same way). */
    private static final Map<Long, Direction> SIDE = new ConcurrentHashMap<>();

    /** The way along the wall this door's flight runs from its landing, or null: the way in goes straight out. */
    @Nullable
    public static Direction side(Level level, DoorWays.Door d) {
        Direction known = SIDE.get(d.pos().asLong());
        if (known != null && steps(level, d, known) >= 0) return known;
        if (straightDrop(level, d) < LEAST) return null;
        Direction best = null;
        int bestN = Integer.MAX_VALUE;
        for (Direction side : new Direction[]{ d.out().getClockWise(), d.out().getCounterClockWise() }) {
            int n = steps(level, d, side);
            if (n < 0) continue;
            // One begun already (its first step laid): that one.
            if (ourTop(level.getBlockState(d.pos().relative(d.out()).relative(side).atY(d.pos().getY() - 1)))) { best = side; break; }
            if (n < bestN) { bestN = n; best = side; }
        }
        if (best != null) SIDE.put(d.pos().asLong(), best);
        return best;
    }

    /**
     * How far the ground falls straight out from the door before a straight way meets it (MOST_STEPS and more: it never
     * does); 0 when the way out goes up, or a straight flight was laid there already (it stays).
     */
    static int straightDrop(Level level, DoorWays.Door d) {
        BlockPos door = d.pos();
        int y = door.getY(), walk = y;
        BlockPos first = door.relative(d.out(), 2);
        if (ourTop(level.getBlockState(first.atY(y - 2))) || ourTop(level.getBlockState(first.atY(y - 1)))) return 0;
        for (int k = 2; k <= DoorWays.MOST_STEPS; k++) {
            BlockPos col = door.relative(d.out(), k);
            if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) return 0;
            int g = DoorWays.standNear(level, col, walk);
            if (g > walk + 1) return 0;
            if (g >= walk - 1) return y - Math.min(g, walk);
            walk--;
        }
        return DoorWays.MOST_STEPS;
    }

    /** The top of a step the town laid (the cobbles, a stair, the flight's stone), not the world's own ground. */
    static boolean ourTop(BlockState st) {
        return st.is(Blocks.COBBLESTONE) || st.getBlock() instanceof StairBlock || st.is(Blocks.COBBLED_DEEPSLATE) || st.is(Blocks.STONE_BRICKS)
            || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.BRICKS) || st.is(Blocks.POLISHED_ANDESITE);
    }

    /** A step's place along the wall: the column, and the height of its feet. */
    record Step(BlockPos col, int feet) {}

    /**
     * The steps of a flight turned this way along the wall, the landing first (level with the door) and the ground it meets
     * last; null when there is no room for it there (something built or another door in the way, a bank higher than the
     * flight, a hollow too deep, the ground not met within MOST_STEPS).
     */
    @Nullable
    static List<Step> flight(Level level, DoorWays.Door d, Direction side) {
        BlockPos door = d.pos();
        int y = door.getY();
        BlockPos landing = door.relative(d.out());
        List<Step> out = new ArrayList<>();
        out.add(new Step(landing, y));
        Villages.Village v = TownBlocks.townAt(level, door);
        for (int k = 1; k <= DoorWays.MOST_STEPS; k++) {
            BlockPos col = landing.relative(side, k);
            if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) return no("unloaded");
            int want = y - k + 1;
            BlockPos wall = col.relative(d.out().getOpposite());
            for (int h = -1; h <= 1; h++) if (level.getBlockState(wall.atY(y + h)).getBlock() instanceof DoorBlock) return no("door at " + k);   // another door
            int g = DoorWays.standNear(level, col, want);
            if (g > want + 1) return no("bank at " + k + " (" + g + ")");                // a bank along the wall: no room
            boolean laid = g >= want - 1 && ourTop(level.getBlockState(col.atY(g - 1)));
            if (g >= want - 1 && !laid) {                                       // the ground: met
                out.add(new Step(col, g));
                return out;
            }
            if (laid) {                                                         // a step of this flight laid already
                out.add(new Step(col, g));
                continue;
            }
            if (want - g > DoorWays.MOST_FILL + 1) return no("deep at " + k);           // a hollow too deep to build up
            if (DoorWays.roofed(level, col, want)) return no("roofed at " + k);
            for (int h = 0; h <= 2; h++) {
                BlockPos c = col.atY(want + h);
                if (v != null && !wayZone(TownBlocks.zone(level, v, c))) return no(TownBlocks.zone(level, v, c) + " at " + k + "/" + h);
                if (!DoorWays.passable(level, c) && !DoorWays.diggable(level, c, level.getBlockState(c))) return no("built at " + k + "/" + h);
            }
            out.add(new Step(col, want));
        }
        return no("not met");
    }

    /** Why the last flight looked at had no room (for the tests' words). */
    private static volatile String lastNo = "";

    @Nullable
    private static List<Step> no(String why) {
        lastNo = why;
        return null;
    }

    /** Ground a door's flight may run over: open ground, or the way in at a door (its approach, its steps); never a building,
     *  its eaves or fittings, a road or the square. */
    private static boolean wayZone(TownBlocks.Zone z) {
        return z == TownBlocks.Zone.OPEN || z == TownBlocks.Zone.APPROACH || z == TownBlocks.Zone.DOORWAY || z == TownBlocks.Zone.STAIRS;
    }

    /** The steps the flight this way wants (its ground at the end not counted), or -1 when there is no room for it. */
    static int steps(Level level, DoorWays.Door d, Direction side) {
        List<Step> f = flight(level, d, side);
        return f == null ? -1 : f.size() - 2;
    }

    /** [DoorWays.next] The first thing to do to make the turned way in walkable: null when it is; BLOCKED when it cannot be. */
    @Nullable
    static DoorWays.Fix next(Level level, DoorWays.Door d, Direction side) {
        List<Step> f = flight(level, d, side);
        if (f == null) return DoorWays.BLOCKED;
        for (int i = 0; i < f.size() - 1; i++) {                               // the ground at the end is the world's
            Step s = f.get(i);
            BlockPos feet = s.col().atY(s.feet());
            boolean roofed = DoorWays.roofed(level, s.col(), s.feet());
            for (BlockPos cell : new BlockPos[]{ feet.above(), feet }) {
                DoorWays.Fix x = DoorWays.clear(level, cell, roofed && i > 0);
                if (x != null) return x;
            }
            if (!DoorWays.solid(level, feet.below())) {
                if (roofed && i > 0) return DoorWays.BLOCKED;
                int bottom = feet.getY() - 1;
                while (bottom > feet.getY() - 1 - DoorWays.MOST_FILL && !DoorWays.solid(level, feet.atY(bottom - 1))) bottom--;
                if (!DoorWays.solid(level, feet.atY(bottom - 1))) return DoorWays.BLOCKED;
                BlockState there = level.getBlockState(feet.atY(bottom));
                if (!there.canBeReplaced() || there.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return DoorWays.BLOCKED;
                return new DoorWays.Fix(feet.atY(bottom), false, bottom == feet.getY() - 1);
            }
        }
        return null;
    }

    /**
     * [StepKit.door] The turned flight dressed: the landing left flat; each step a block above the next a stair facing up
     * the flight (back toward the landing); under every step down to the ground the flight's stone; a cheek on the open
     * side of a step standing two and more over the ground beside it. Returns the stairs made (or there already).
     */
    static int dress(ServerLevel level, Villages.Village v, DoorWays.Door d, Direction side, Block stair) {
        List<Step> f = flight(level, d, side);
        if (f == null) return 0;
        int made = 0;
        Direction up = side.getOpposite();
        int ground = f.get(f.size() - 1).feet() - 1;
        BlockState stone = Block.byItem(StepKit.cutFrom(stair)).defaultBlockState();
        for (int i = 1; i < f.size() - 1; i++) {
            Step s = f.get(i), next = f.get(i + 1);
            int floor = s.feet() - 1;
            // Under the step: the flight's stone to the ground.
            for (int y = floor - 1, n = 0; y > ground - 4 && n < 8; y--, n++) {
                BlockPos at = s.col().atY(y);
                BlockState st = level.getBlockState(at);
                if (y <= ground && !(st.isAir() || st.canBeReplaced())) break;
                if (!StepKit.fill(st)) { if (y <= ground) break; continue; }
                if (!StepKit.put(level, v, at, stone)) return made;
            }
            boolean stepDown = next.feet() == s.feet() - 1;
            if (stepDown) {
                if (StepKit.set(level, v, s.col().atY(floor), up, stair, false)) made++;
            } else {
                BlockState top = level.getBlockState(s.col().atY(floor));
                if (!(top.getBlock() instanceof StairBlock) && StepKit.fill(top) && !StepKit.put(level, v, s.col().atY(floor), stone)) return made;
            }
            // The cheek on its open side (the wall is the other): where the ground beside it is two or more below.
            if (s.feet() - (ground + 1) < 2) continue;
            BlockPos cheek = s.col().relative(d.out());
            if (!StepKit.fill(level.getBlockState(cheek.atY(floor))) || !Footways.passable(level, cheek.atY(floor + 1))) continue;
            for (int y = floor - 1, n = 0; n < 8; y--, n++) {
                BlockState st = level.getBlockState(cheek.atY(y));
                if (y <= ground && !(st.isAir() || st.canBeReplaced())) break;
                if (!StepKit.fill(st)) break;
                if (!StepKit.put(level, v, cheek.atY(y), stone)) return made;
            }
            if (!StepKit.put(level, v, cheek.atY(floor), stepDown ? BridgeKit.stair(stair, up, false) : stone)) return made;
        }
        return made;
    }

    /** Tests: the steps of the flight at this door (landing first, the ground last), or null when it goes straight out. */
    @Nullable
    public static List<BlockPos> flightForTests(Level level, DoorWays.Door d) {
        Direction side = side(level, d);
        if (side == null) return null;
        List<Step> f = flight(level, d, side);
        if (f == null) return null;
        List<BlockPos> out = new ArrayList<>();
        for (Step s : f) out.add(s.col().atY(s.feet()));
        return out;
    }

    /** Tests: the straight way's drop and the steps each side would want (-1: no room), in words. */
    public static String whyForTests(Level level, DoorWays.Door d) {
        int a = steps(level, d, d.out().getClockWise());
        String whyA = lastNo;
        int b = steps(level, d, d.out().getCounterClockWise());
        return "drop " + straightDrop(level, d) + ", " + d.out().getClockWise().getName() + " " + a + (a < 0 ? " (" + whyA + ")" : "")
            + ", " + d.out().getCounterClockWise().getName() + " " + b + (b < 0 ? " (" + lastNo + ")" : "");
    }

    public static void resetForTests() {
        SIDE.clear();
    }
}
