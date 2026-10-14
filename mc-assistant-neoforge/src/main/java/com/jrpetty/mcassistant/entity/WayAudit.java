package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [paths] How the town's ways stand, looked at all at once: the paths helper's checks, of the same shape as the town's
 * audit (TownAudit, [integrity]) so they can be added to it as they are.
 * <ul>
 * <li><b>Doors</b>: every door of every building, and whether a folk can walk to it from the heart and through it
 *     (Footways.reach), and whether a street is in front of it within a few steps;</li>
 * <li><b>Lots</b>: every two buildings' ground, overlapping or touching (no way between them), and every door with a
 *     wall or another building in the two blocks in front of it;</li>
 * <li><b>The square</b>: anything set down on its open ground that it was not laid out for (Footways.squareOpen);</li>
 * <li><b>Stuck</b>: where folk gave up their paths, and who has been stuck more than half a minute (StuckSpots).</li>
 * </ul>
 */
public final class WayAudit {

    private WayAudit() {}

    /** How far out in front of a door a street may start. */
    static final int TO_STREET = 8;

    /** What the look found. Each list says where, so a failing test says what to go and see. */
    public record Report(int doors, int reached, List<String> unreached, int offStreet, List<String> offStreets,
                         int overlaps, int touching, List<String> crowding, int blockedDoors, List<String> blocked,
                         int clutter, List<String> clutterAt, int stuckSpots, List<String> longStuck, int buildings) {

        /** Every door reached, no two buildings overlapping or touching, no door against a wall, the square clear. */
        public boolean clean() {
            return reached == doors && overlaps == 0 && touching == 0 && blockedDoors == 0 && clutter == 0 && longStuck.isEmpty();
        }

        public double reachedShare() {
            return doors == 0 ? 1.0 : reached / (double) doors;
        }

        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(String.format("ways: %d buildings, doors reached from the heart %d/%d (%.0f%%), off a street %d, lots overlapping %d,"
                    + " touching %d, doors against a wall %d, things on the square %d, spots folk gave up at %d, stuck over 30s %d",
                buildings, reached, doors, 100 * reachedShare(), offStreet, overlaps, touching, blockedDoors, clutter, stuckSpots,
                longStuck.size()));
            for (String s : unreached) out.add("  door not reached: " + s);
            for (String s : offStreets) out.add("  door off the streets: " + s);
            for (String s : crowding) out.add("  crowded: " + s);
            for (String s : blocked) out.add("  door against a wall: " + s);
            for (String s : clutterAt) out.add("  on the square: " + s);
            for (String s : longStuck) out.add("  stuck: " + s);
            return out;
        }
    }

    public static Report check(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(v.id())) if (!Footways.skip(b.structure())) all.add(b);
        // Doors.
        int reach = Villages.townReach(v.id()) + 16;
        LongOpenHashSet walk = Footways.reach(level, v.centre(), reach, 32, 2_000_000);
        int doors = 0, reached = 0, offStreet = 0, blockedDoors = 0;
        List<String> unreached = new ArrayList<>(), offStreets = new ArrayList<>(), blocked = new ArrayList<>();
        for (Ledger.Building b : all) {
            for (DoorWays.Door d : DoorWays.doorsOf(level, b)) {
                doors++;
                String where = b.structure() + " " + d.pos().toShortString() + " facing " + d.out().getSerializedName();
                boolean at = walk.contains(d.pos().asLong());
                boolean out = near(walk, d.pos().relative(d.out())), in = near(walk, d.pos().relative(d.out().getOpposite()));
                if (at && out && in) reached++;
                else unreached.add(where + (at ? "" : " (the doorway)") + (out ? "" : " (outside)") + (in ? "" : " (inside)"));
                if (!onStreet(v, d)) {
                    offStreet++;
                    offStreets.add(where);
                }
                String wall = against(level, v, b, d, all);
                if (wall != null) {
                    blockedDoors++;
                    blocked.add(where + ": " + wall);
                }
            }
        }
        // Lots.
        int overlaps = 0, touching = 0;
        List<String> crowding = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            int[] a = Footways.footprint(all.get(i), true);
            for (int j = i + 1; j < all.size(); j++) {
                int[] c = Footways.footprint(all.get(j), true);
                int g = Footways.gap(a, c);
                if (g > 0) continue;
                if (g < 0) overlaps++; else touching++;
                crowding.add(all.get(i).structure() + " " + all.get(i).anchor().toShortString() + " and " + all.get(j).structure()
                    + " " + all.get(j).anchor().toShortString() + (g < 0 ? " overlap" : " touch"));
            }
        }
        // The square.
        Map<String, Integer> kinds = new TreeMap<>();
        List<String> clutterAt = new ArrayList<>();
        int clutter = 0;
        BlockPos c = v.centre();
        for (int dx = -TownPlan.PLAZA + 1; dx < TownPlan.PLAZA; dx++) {
            for (int dz = -TownPlan.PLAZA + 1; dz < TownPlan.PLAZA; dz++) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!Footways.squareOpen(v, x, z)) continue;
                for (int y = c.getY(); y <= c.getY() + 3; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (!litter(level, p, st, y == c.getY())) continue;
                    clutter++;
                    String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    if (kinds.merge(name, 1, Integer::sum) <= 3) clutterAt.add(name + " " + p.toShortString());
                }
            }
        }
        if (!kinds.isEmpty()) clutterAt.add(0, "kinds " + kinds);
        List<String> stuck = StuckSpots.longStuck(v.id());
        return new Report(doors, reached, unreached, offStreet, offStreets, overlaps, touching, crowding, blockedDoors, blocked,
            clutter, clutterAt, StuckSpots.spots(v.id()).size(), stuck, all.size());
    }

    /** Reached at this place, a block up or a block down (a step to or from a doorway). */
    private static boolean near(LongOpenHashSet walk, BlockPos p) {
        return walk.contains(p.asLong()) || walk.contains(p.above().asLong()) || walk.contains(p.below().asLong());
    }

    /** Does a street (or the square) begin within a few steps straight out of this door? */
    static boolean onStreet(Villages.Village v, DoorWays.Door d) {
        for (int k = 1; k <= TO_STREET; k++) {
            BlockPos p = d.pos().relative(d.out(), k);
            if (Footways.street(v, p.getX(), p.getZ()) || Footways.square(v, p.getX(), p.getZ())) return true;
        }
        return false;
    }

    /** What stands in the two blocks in front of a door at the door's height: another building, or a wall; null if neither. */
    static String against(ServerLevel level, Villages.Village v, Ledger.Building own, DoorWays.Door d, List<Ledger.Building> all) {
        for (int k = 1; k <= 2; k++) {
            BlockPos p = d.pos().relative(d.out(), k);
            for (Ledger.Building b : all) {
                if (b == own || b.anchor().equals(own.anchor())) continue;
                if (Footways.inside(Footways.footprint(b, false), p.getX(), p.getZ())) return b.structure() + " " + b.anchor().toShortString();
            }
            BlockState feet = level.getBlockState(p), head = level.getBlockState(p.above());
            boolean shut = !Footways.passable(level, p) && !DoorWays.diggable(level, p, feet);
            boolean shutHead = !Footways.passable(level, p.above()) && !DoorWays.diggable(level, p.above(), head);
            // The first block out may be a step down or up; a step is not a wall, two blocks of something built are.
            if (shut && shutHead) return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(head.getBlock()).getPath()
                + " " + p.toShortString();
        }
        return null;
    }

    /** Something set down on the square that does not belong there: a bed, a chest, a block standing on its floor. */
    static boolean litter(ServerLevel level, BlockPos p, BlockState st, boolean onFloor) {
        if (st.isAir() || st.canBeReplaced()) return false;
        if (st.is(BlockTags.BEDS) || st.hasBlockEntity()) return !(st.getBlock() instanceof net.minecraft.world.level.block.SignBlock);
        // Lamps and their posts, flowers in pots, carpets, banners: the square's own furniture.
        if (st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock
            || st.getBlock() instanceof net.minecraft.world.level.block.TorchBlock || st.is(BlockTags.FLOWER_POTS)
            || st.is(BlockTags.WOOL_CARPETS) || st.is(BlockTags.BANNERS) || st.is(BlockTags.SLABS) || st.is(BlockTags.STAIRS))
            return false;
        // A block on the square's floor, at standing height: a pillar, a heap, a stray.
        return !st.getCollisionShape(level, p).isEmpty();
    }

    /** The checks for the /village paths command and the town's books: its lines. */
    public static List<String> report(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>(check(level, v).lines());
        out.add("stuck: " + StuckSpots.line(v.id()));
        for (StuckSpots.Spot s : StuckSpots.spots(v.id())) {
            if (out.size() > 40) break;
            out.add("  spot: " + s);
        }
        return out;
    }

    /** Which way a building's door faces, in a word. */
    static String facing(Direction d) {
        return d.getSerializedName();
    }
}
