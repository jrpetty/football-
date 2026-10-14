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
                         int clutter, List<String> clutterAt, int campBeds, int stuckSpots, List<String> longStuck, int buildings) {

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
                    + " touching %d, doors against a wall %d, things on the square %d (and the founders' camp beds %d), spots folk gave up at %d,"
                    + " stuck over 30s %d",
                buildings, reached, doors, 100 * reachedShare(), offStreet, overlaps, touching, blockedDoors, clutter, campBeds,
                stuckSpots, longStuck.size()));
            for (String s : unreached) out.add("  door not reached: " + s);
            for (String s : offStreets) out.add("  door off the streets: " + s);
            for (String s : crowding) out.add("  crowded: " + s);
            for (String s : blocked) out.add("  door against a wall: " + s);
            for (String s : clutterAt) out.add("  on the square: " + s);
            for (String s : longStuck) out.add("  stuck: " + s);
            return out;
        }
    }

    /** The town's audit's name for these checks, and its measures. */
    public static final String WAYS = "ways";
    public static final String DOORS = "doors", DOORS_REACHED = "doors_reached", LOT_OVERLAPS = "lot_overlaps",
        LOT_TOUCHING = "lot_touching", DOORS_BLOCKED = "doors_blocked", SQUARE_CLUTTER = "square_clutter", STUCK_LONG = "stuck_long", CAMP_BEDS = "camp_beds";

    /** These checks, added to the town's audit (TownAudit) under {@link #WAYS}: its measures, and a finding for each fault. */
    static void register() {
        TownAudit.register(WAYS, (level, v, r) -> {
            Report w = check(level, v);
            r.measure(DOORS, w.doors());
            r.measure(DOORS_REACHED, w.reachedShare());
            r.measure(LOT_OVERLAPS, w.overlaps());
            r.measure(LOT_TOUCHING, w.touching());
            r.measure(DOORS_BLOCKED, w.blockedDoors());
            r.measure(SQUARE_CLUTTER, w.clutter());
            r.measure(CAMP_BEDS, w.campBeds());
            r.measure(STUCK_LONG, w.longStuck().size());
            for (String s : w.unreached()) r.find(WAYS, v.centre(), "door not reached from the heart: " + s);
            for (String s : w.crowding()) r.find(WAYS, v.centre(), "buildings crowded: " + s);
            for (String s : w.blocked()) r.find(WAYS, v.centre(), "door against a wall: " + s);
            for (String s : w.clutterAt()) r.find(WAYS, v.centre(), "on the square: " + s);
            for (String s : w.longStuck()) r.find(WAYS, v.centre(), "stuck over half a minute: " + s);
        });
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
        int clutter = 0, campBeds = 0;
        BlockPos c = v.centre();
        for (int dx = -TownPlan.PLAZA + 1; dx < TownPlan.PLAZA; dx++) {
            for (int dz = -TownPlan.PLAZA + 1; dz < TownPlan.PLAZA; dz++) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!Footways.squareOpen(v, x, z)) continue;
                for (int y = c.getY(); y <= c.getY() + 3; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (!litter(level, p, st, y == c.getY())) continue;
                    // The founders' beds about the heart are their camp until there are homes ([tidy] takes them up).
                    if (st.is(BlockTags.BEDS)) { campBeds++; continue; }
                    clutter++;
                    String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    if (kinds.merge(name, 1, Integer::sum) <= 3) clutterAt.add(name + " " + p.toShortString());
                }
            }
        }
        if (!kinds.isEmpty()) clutterAt.add(0, "kinds " + kinds);
        List<String> stuck = StuckSpots.longStuck(v.id());
        return new Report(doors, reached, unreached, offStreet, offStreets, overlaps, touching, crowding, blockedDoors, blocked,
            clutter, clutterAt, campBeds, StuckSpots.spots(v.id()).size(), stuck, all.size());
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
        // Lamps and their posts, flowers in pots, carpets, banners, the watch's ladders up the wall: the square's own furniture.
        if (st.is(BlockTags.CLIMBABLE) || st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock
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

    // ------------------------------------------------------------------ /village paths

    /**
     * /village paths: the town's ways in words (the doors, the lots, the square, the stuck spots). {@code views}: where to
     * stand to see the signs by the homes' doors and the paths to the street (VIEW lines, for the pictures). {@code signs}
     * (operators): every home's sign seen to now, out of the stores. {@code now} (operators): a few rounds of the doors'
     * ways and paths, done on the spot.
     */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
        return net.minecraft.commands.Commands.literal("paths")
            .executes(ctx -> {
                Villages.Village v = Quarters.near(ctx);
                if (v == null) {
                    ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("No village yet."));
                    return 0;
                }
                String text = "THE TOWN'S WAYS " + Villages.name(v.id()) + ": " + String.join(" | ", report(ctx.getSource().getLevel(), v));
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
                return 1;
            })
            .then(net.minecraft.commands.Commands.literal("views").executes(ctx -> {
                Villages.Village v = Quarters.near(ctx);
                if (v == null) return 0;
                List<String> views = views(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("PATHS | " + String.join(" | ", views)), false);
                return views.size();
            }))
            .then(net.minecraft.commands.Commands.literal("signs").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = Quarters.near(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                int n = 0;
                TownJobs.instantForTests(true);
                try {
                    for (Ledger.Building b : Ledger.buildings(v.id())) {
                        if (HouseSigns.named(b.structure()) && TownLife.addressSign(level, v.id(), v.centre(), b, v, false)) n++;
                    }
                } finally {
                    TownJobs.instantForTests(false);
                }
                int done = n;
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("PATHS | " + done + " home signs up"), false);
                return n;
            }))
            .then(net.minecraft.commands.Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = Quarters.near(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                int n = 0;
                TownJobs.instantForTests(true);
                try {
                    for (int i = 0; i < 4; i++) {
                        for (Ledger.Building b : Ledger.buildings(v.id())) {
                            if (Footways.skip(b.structure())) continue;
                            for (DoorWays.Door d : DoorWays.doorsOf(level, b)) {
                                if (DoorWays.mend(level, v, d, 16) > 0) n++;
                                else if (DoorWays.approach(level, v, d, 16) > 0) n++;
                            }
                        }
                    }
                } finally {
                    TownJobs.instantForTests(false);
                }
                int done = n;
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("PATHS | " + done + " pieces of work done at the doors"), false);
                return n;
            }));
    }

    /** Where to stand for the pictures: before a few homes' doors (their signs), and along a street of them. */
    public static List<String> views(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        int homes = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!HouseSigns.named(b.structure())) continue;
            BlockPos door = TownLife.fittings(b).door();
            if (door == null) continue;
            Direction front = b.facing().getOpposite();
            BlockPos eye = door.relative(front, 5).relative(front.getClockWise(), 2).above(2);
            out.add("VIEW home-" + (++homes) + " " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " "
                + door.getX() + " " + (door.getY() + 1) + " " + door.getZ());
            if (homes == 1) {
                // Down the street the first home stands on: a row of doors and their signs.
                BlockPos far = door.relative(front, 3).relative(front.getClockWise(), 14).above(4);
                BlockPos aim = door.relative(front, 1).relative(front.getCounterClockWise(), 6).above(1);
                out.add("VIEW street " + far.getX() + " " + far.getY() + " " + far.getZ() + " " + aim.getX() + " " + aim.getY() + " " + aim.getZ());
            }
            if (homes >= 4) break;
        }
        return out;
    }
}
