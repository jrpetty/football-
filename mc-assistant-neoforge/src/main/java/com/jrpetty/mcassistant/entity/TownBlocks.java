package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [integrity] The one gate every block a town's own works set down (or take up) goes through.
 *
 * <p>A town is a dozen systems all putting blocks into the same few acres: the builders, the lamp posts, the
 * window boxes, the signs, the street works, the gardens, the furnishing, the ground work. Each of them used to
 * ask only whether the cell it wanted was free, and a cell inside a building's walls, in its doorway or on the
 * step in front of its door is very often free: a lamp post went up in a porch, a window box on the step, the
 * shop's counter came out of its floor with a field's fence in it. Before any of them sets a block in a town it
 * asks here, saying why it wants the cell ({@link Why}); the answer depends on what the cell is to the town
 * ({@link Zone}):
 * <ul>
 * <li>a building's own cells and the room inside and over it ({@link Zone#BUILDING}, {@link Zone#EAVES}) are its
 *     own builders' and repairers' alone (and the furnishers', inside, in the cells its drawing leaves empty);</li>
 * <li>its doorways and the two cells in front of every door, at the door's height and the one over it
 *     ({@link Zone#DOORWAY}, {@link Zone#APPROACH}), and its stairs and ladders with their headroom
 *     ({@link Zone#STAIRS}), are nobody's to put anything in but the building's own and the door-way works;</li>
 * <li>the streets and avenues of the town plan ({@link Zone#ROAD}) are the street works' (a lamp or a sign only at
 *     the edge); the square's open ground ({@link Zone#SQUARE}) the street works' and the civic things';</li>
 * <li>the fittings the town keeps (its board, its storehouse, and whatever another system registers with
 *     {@link #protect}) are taken up by nobody ({@link Zone#FITTING});</li>
 * <li>the rest is {@link Zone#OPEN}.</li>
 * </ul>
 * Outside every town's reach the gate always says yes. What it knows of a town (where its buildings, doors and
 * stairs are) is worked out from the register of its buildings (Ledger) and the sites going up (Villages.sitesOf),
 * laid out from their drawings (BuildGoal.plan), and kept for a while.
 */
public final class TownBlocks {

    private TownBlocks() {}

    /** What a cell is to the town. */
    public enum Zone {
        /** Nothing in particular: the gate lets most things in. */
        OPEN,
        /** A building's own: one of its drawing's cells, or the room inside its walls and up to its roof. */
        BUILDING,
        /** Round a building's walls, under its eaves or porch (its drawing's reach past its footing). */
        EAVES,
        /** A door's own two cells. */
        DOORWAY,
        /** The two cells in front of a door, at the door's height and the one above (the way in). */
        APPROACH,
        /** A building's stairs and ladders, and the two cells of headroom over each. */
        STAIRS,
        /** On a street or an avenue of the town plan, at or above the ground. */
        ROAD,
        /** On the square's open ground, at or above it. */
        SQUARE,
        /** Something the town keeps where it is: its board, its storehouse, a registered fitting. */
        FITTING
    }

    /**
     * Why a block is to be set (or taken up). Each says which zones it may touch; a builder, a repairer and a
     * renovator touch their own building's cells as well ({@link #mayBuild}). Other systems add their own here
     * (one line each, tagged), choosing the zones it is right for them to touch.
     */
    public enum Why {
        /** A building going up: its own cells (mayBuild with its anchor), and open ground. */
        BUILD(Zone.OPEN),
        /** A building mended to its drawing (Restore): its own cells. */
        REPAIR(Zone.OPEN),
        /** A building rebuilt to a newer drawing or look ([styles]): its own cells. */
        RENOVATE(Zone.OPEN),
        /** Furniture into a building's rooms (beds, rugs, shelves, lamps, the households' things): only the cells
         *  its drawing leaves empty inside it, never a doorway, a way in or a stair. */
        FURNISH(Zone.OPEN, Zone.BUILDING),
        /** The way in at a door: steps cut or built, the doorway cleared (DoorWays, [paths]). */
        DOORWAY(Zone.OPEN, Zone.APPROACH, Zone.ROAD, Zone.SQUARE),
        /** The streets and paths: worn, paved, mended. */
        ROAD(Zone.OPEN, Zone.ROAD, Zone.SQUARE),
        /** Clearing up: litter, stray blocks, scars ([tidy]). Takes up, and lays ground back, anywhere open. */
        TIDY(Zone.OPEN, Zone.ROAD, Zone.SQUARE, Zone.APPROACH),
        /** Things of the town on its square: the board, the bell, the stalls, the feasts, the market. */
        CIVIC(Zone.OPEN, Zone.SQUARE),
        /** A lamp post or a street light (at a road's edge, never in the middle of it). */
        LAMP(Zone.OPEN, Zone.ROAD, Zone.SQUARE),
        /** A sign or a signpost (at a road's edge), a house's name board on its front: never into its walls or doorway. */
        SIGN(Zone.OPEN, Zone.ROAD, Zone.SQUARE, Zone.EAVES, Zone.BUILDING),
        /** A window box, a sill, a planter on a building's face (on its porch, under its eaves): never into its walls,
         *  its doorway or the way in. */
        WINDOW_BOX(Zone.OPEN, Zone.EAVES, Zone.BUILDING),
        /** The town's dressing out of doors: benches, planters, washing lines, scarecrows, banners. */
        DECOR(Zone.OPEN),
        /** Gardens round a house: fences, gates, flower beds. */
        GARDEN(Zone.OPEN),
        /** Fields, pens, orchards, allotments. */
        FIELD(Zone.OPEN),
        /** The ground made level, raised or cut (Terraform, Land, ParkGround). */
        TERRAFORM(Zone.OPEN, Zone.ROAD, Zone.SQUARE),
        /** A levee, a sea wall, flood works. */
        LEVEE(Zone.OPEN),
        /** Ropes, pit props, ladders down a shaft, mine stairs, and the fence round a stair head (which may stand in a
         *  road where the stairs come up in one: a fence is better than an open hole). */
        MINE(Zone.OPEN, Zone.ROAD),
        /** Trees planted or felled, grass and flowers. */
        NATURE(Zone.OPEN),
        /** Anything else a town's works set down. */
        OTHER(Zone.OPEN);

        private final Set<Zone> zones;

        Why(Zone first, Zone... more) {
            this.zones = EnumSet.of(first, more);
        }

        /** May this kind of work touch a cell of this zone at all (its own building aside)? */
        public boolean touches(Zone z) {
            return zones.contains(z);
        }

        /** Is this one of a building's own works (built, mended, renovated)? */
        public boolean own() {
            return this == BUILD || this == REPAIR || this == RENOVATE;
        }
    }

    // ------------------------------------------------------------------ the gate

    /** May this be set into this cell of the town (or taken out of it), for this reason? The village is looked up. */
    public static boolean mayPlace(Level level, BlockPos pos, Why why) {
        return mayPlace(level, townAt(level, pos), pos, why);
    }

    /** May this be set into this cell of this village (null: no town here, so yes), for this reason? */
    public static boolean mayPlace(Level level, @Nullable Villages.Village v, BlockPos pos, Why why) {
        if (v == null || level.isClientSide()) return true;
        Here h = here(level, v, pos);
        boolean ok = allowed(level, v, pos, why, h, null);
        if (!ok) refused(v, pos, why, h, null);
        return ok;
    }

    /** May every one of these cells be set, for this reason (a thing of several blocks goes up whole or not at all)? */
    public static boolean mayAll(Level level, @Nullable Villages.Village v, Why why, BlockPos... cells) {
        for (BlockPos p : cells) if (!mayPlace(level, v, p, why)) return false;
        return true;
    }

    /** As mayAll, for a list. */
    public static boolean mayAll(Level level, @Nullable Villages.Village v, Why why, java.util.Collection<BlockPos> cells) {
        for (BlockPos p : cells) if (!mayPlace(level, v, p, why)) return false;
        return true;
    }

    /** May a building's own work (build, repair, renovate) set this cell? Its own cells, and open ground, yes; another
     *  building's, its doorways, a road or the square (unless they are its own cells), no. {@code ownAnchor}: the
     *  anchor of the building being worked on. */
    public static boolean mayBuild(Level level, @Nullable Villages.Village v, BlockPos pos, BlockPos ownAnchor, Why why) {
        if (v == null || level.isClientSide()) return true;
        Here h = here(level, v, pos);
        boolean ok = allowed(level, v, pos, why, h, ownAnchor);
        if (!ok) refused(v, pos, why, h, ownAnchor);
        return ok;
    }

    /** May this cell's block be taken up, for this reason? (The same zones as setting one, and a fitting never.) */
    public static boolean mayBreak(Level level, @Nullable Villages.Village v, BlockPos pos, Why why) {
        return mayPlace(level, v, pos, why);
    }

    /** Set a block if the gate allows: true if it went down. Flags as Level.setBlock (3: update and tell the clients). */
    public static boolean place(Level level, BlockPos pos, BlockState st, int flags, Why why) {
        Villages.Village v = townAt(level, pos);
        if (!mayPlace(level, v, pos, why)) return false;
        level.setBlock(pos, st, flags);
        traced(v, pos, why);
        return true;
    }

    /** Set a block if the gate allows (updating its neighbours): true if it went down. */
    public static boolean place(Level level, BlockPos pos, BlockState st, Why why) {
        return place(level, pos, st, 3, why);
    }

    /** A building's own work sets a block, if the gate allows: true if it went down. */
    public static boolean build(Level level, @Nullable Villages.Village v, BlockPos pos, BlockState st, int flags, BlockPos ownAnchor, Why why) {
        if (!mayBuild(level, v, pos, ownAnchor, why)) return false;
        level.setBlock(pos, st, flags);
        traced(v, pos, why);
        return true;
    }

    /** Take a block up if the gate allows (dropping it or not): true if it came up. */
    public static boolean clear(Level level, BlockPos pos, boolean drop, Why why) {
        Villages.Village v = townAt(level, pos);
        if (!mayBreak(level, v, pos, why)) return false;
        level.destroyBlock(pos, drop);
        traced(v, pos, why);
        return true;
    }

    /** What this cell is to the town (OPEN outside every town). */
    public static Zone zone(Level level, @Nullable Villages.Village v, BlockPos pos) {
        return v == null ? Zone.OPEN : here(level, v, pos).zone;
    }

    /** The building (built or going up) whose cell, room, doorway or stair this is; null if none. */
    @Nullable
    public static Ledger.Building buildingAt(Level level, @Nullable Villages.Village v, BlockPos pos) {
        if (v == null) return null;
        Here h = here(level, v, pos);
        return h.owner == null ? null : h.owner.building;
    }

    /** The town whose ground this is (its heart within the plan's reach), or null. */
    @Nullable
    public static Villages.Village townAt(Level level, BlockPos pos) {
        Villages.Village best = null;
        long bestD = Long.MAX_VALUE;
        int reach = TownPlan.reach() + 8;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            long dx = Math.abs(pos.getX() - v.centre().getX()), dz = Math.abs(pos.getZ() - v.centre().getZ());
            if (dx > reach || dz > reach) continue;
            long d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = v; }
        }
        return best;
    }

    /** Keep this cell as a fitting (or a way in, a stair...) of the town's: another system's (outdoor steps, a
     *  market stall's place, a statue). Kept until the server stops; register again on load. */
    public static void protect(UUID village, BlockPos pos, Zone zone) {
        EXTRA.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).put(pos.asLong(), zone);
    }

    /** Let a cell registered with protect go. */
    public static void unprotect(UUID village, BlockPos pos) {
        Map<Long, Zone> m = EXTRA.get(village);
        if (m != null) m.remove(pos.asLong());
    }

    /** What is known of a town worked out again at the next look (a building raised, taken down, renovated). */
    public static void invalidate(UUID village) {
        INDEX.remove(village);
    }

    public static void resetForTests() {
        INDEX.clear();
        EXTRA.clear();
        TRACE.clear();
        REFUSED.clear();
        REFUSALS.set(0);
    }

    // ------------------------------------------------------------------ what a design is

    /** The drawing a building of the register stands to now: a house given its second storey is "house2", any
     *  other building raised a storey its "_tall" drawing. */
    public static String designOf(UUID village, Ledger.Building b) {
        String s = b.structure();
        if (s.equals("house") && Ledger.grown(village, b.anchor())) return Themes.design(village, "house2");   // [styles] the theme's own
        if (!s.endsWith(Blueprints.TALL) && Grow.tall(village, b.anchor()) && Blueprints.has(s + Blueprints.TALL)) {
            return Themes.design(village, s + Blueprints.TALL);                                               // [styles]
        }
        return Themes.design(village, s);                                                                     // [styles]
    }

    // ------------------------------------------------------------------ the rules

    /**
     * [defence] The castle wall's ring is kept for the wall: no building, furnishing, dressing, garden, field or works
     * set down on it. Streets, lamps, tidying, the ground's levelling, nature and the mine's fences may still touch it.
     */
    private static boolean onWallRing(Villages.Village v, BlockPos pos, Why why) {
        switch (why) {
            case ROAD, DOORWAY, TIDY, LAMP, SIGN, TERRAFORM, MINE, NATURE -> { return false; }
            default -> { return CastleWall.reserved(v.id(), v.centre(), pos); }
        }
    }

    private static boolean allowed(Level level, Villages.Village v, BlockPos pos, Why why, Here h, @Nullable BlockPos ownAnchor) {
        // A building's own work, on its own building: any of its cells, its doorways and stairs included.
        if (why.own() && ownAnchor != null && h.owner != null && h.owner.building.anchor().equals(ownAnchor)) return true;
        Zone z = h.zone;
        switch (z) {
            case OPEN -> { return !onWallRing(v, pos, why); }
            case FITTING -> { return false; }
            case BUILDING -> {
                if (!why.touches(Zone.BUILDING)) return false;
                // Furniture only into what the drawing leaves empty, inside the walls: never one of its own cells.
                return !h.designCell;
            }
            case EAVES -> { return why.touches(Zone.EAVES) && !h.designCell; }
            case DOORWAY, STAIRS -> { return false; }
            case APPROACH -> { return why.touches(Zone.APPROACH); }
            case ROAD -> {
                if (!why.touches(Zone.ROAD)) return false;
                // A lamp or a sign at the road's edge only: the middle of it is for walking.
                if (why == Why.LAMP || why == Why.SIGN) return roadEdge(v, pos);
                return true;
            }
            case SQUARE -> { return why.touches(Zone.SQUARE); }
            default -> { return true; }
        }
    }

    /** Is this road cell at the edge of its street (a column beside it off the street)? */
    private static boolean roadEdge(Villages.Village v, BlockPos pos) {
        int dx = pos.getX() - v.centre().getX(), dz = pos.getZ() - v.centre().getZ();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = dx + d.getStepX(), z = dz + d.getStepZ();
            if (!TownPlan.isStreet(x, z) && !TownPlan.isSquare(x, z)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ where things are

    /** One building the gate knows: from the register, or a site going up. */
    static final class Owner {
        final Ledger.Building building;
        final String design;
        final boolean pending;
        /** Its footing's rectangle, and everything's (eaves, porches), in world x and z; its heights. */
        int gx0, gx1, gz0, gz1, fx0, fx1, fz0, fz1, y0, y1;

        Owner(Ledger.Building building, String design, boolean pending) {
            this.building = building;
            this.design = design;
            this.pending = pending;
        }

        boolean inFull(int x, int z) { return x >= fx0 && x <= fx1 && z >= fz0 && z <= fz1; }
        boolean inGround(int x, int z) { return x >= gx0 && x <= gx1 && z >= gz0 && z <= gz1; }
    }

    /** What the gate found at a cell: its zone, whose it is, and whether a drawing has a block there. */
    record Here(Zone zone, @Nullable Owner owner, boolean designCell) {}

    /** What the gate knows of one town. */
    static final class Index {
        final long made;
        final int signature;
        final List<Owner> owners = new ArrayList<>();
        /** Every drawing's cell, by BlockPos.asLong, to the index of its owner. */
        final Map<Long, Integer> cells = new HashMap<>();
        /** Doorways, ways in and stairs, by BlockPos.asLong. */
        final Map<Long, Zone> special = new HashMap<>();
        final Map<Long, Integer> specialOwner = new HashMap<>();
        /** The game tick it was last checked against the register. */
        long checked = Long.MIN_VALUE;

        Index(long made, int signature) {
            this.made = made;
            this.signature = signature;
        }
    }

    /** Half the founders' camp at the heart of the square (TownWork does not pave it either). */
    static final int CAMP = 4;
    private static final Map<UUID, Index> INDEX = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Long, Zone>> EXTRA = new ConcurrentHashMap<>();
    /** How long what the gate knows of a town is kept before it is worked out again anyway. */
    private static final long KEEP = 1200L;

    private static Here here(Level level, Villages.Village v, BlockPos pos) {
        Index ix = index(level, v);
        long k = pos.asLong();
        Map<Long, Zone> extra = EXTRA.get(v.id());
        Zone registered = extra == null ? null : extra.get(k);
        Zone special = ix.special.get(k);
        Integer cellOwner = ix.cells.get(k);
        if (special != null) {
            Integer o = ix.specialOwner.get(k);
            return new Here(special, o == null ? null : ix.owners.get(o), cellOwner != null);
        }
        if (registered != null) return new Here(registered, null, false);
        if (cellOwner != null) {
            Owner o = ix.owners.get(cellOwner);
            return new Here(o.inGround(pos.getX(), pos.getZ()) ? Zone.BUILDING : Zone.EAVES, o, true);
        }
        for (Owner o : ix.owners) {
            if (o.design.equals("fortify")) continue;                      // a wall is its own cells, not a rectangle
            if (pos.getY() < o.y0 || pos.getY() > o.y1 || !o.inFull(pos.getX(), pos.getZ())) continue;
            return new Here(o.inGround(pos.getX(), pos.getZ()) ? Zone.BUILDING : Zone.EAVES, o, false);
        }
        BlockState st = level.isLoaded(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
        if (fitting(st)) return new Here(Zone.FITTING, null, false);
        int dx = pos.getX() - v.centre().getX(), dz = pos.getZ() - v.centre().getZ();
        if (Math.max(Math.abs(dx), Math.abs(dz)) <= TownPlan.reach() && atGround(level, pos, st)) {
            // The founders' camp at the very heart is the camp's (its beds, its chests, its fire) until [tidy] takes it up.
            if (TownPlan.isSquare(dx, dz)) return new Here(Math.max(Math.abs(dx), Math.abs(dz)) <= CAMP ? Zone.OPEN : Zone.SQUARE, null, false);
            if (TownPlan.isStreet(dx, dz)) return new Here(Zone.ROAD, null, false);
        }
        return new Here(Zone.OPEN, null, false);
    }

    /** At or above the ground: not a cell of earth or stone with more of it over it (underground is nobody's road). */
    private static boolean atGround(Level level, BlockPos pos, BlockState st) {
        if (st.isAir() || st.canBeReplaced()) return true;
        BlockState over = level.isLoaded(pos.above()) ? level.getBlockState(pos.above()) : Blocks.AIR.defaultBlockState();
        return over.isAir() || over.canBeReplaced() || !over.isSolid();
    }

    /** The town's own fittings, never to be built over or taken up by its works. */
    public static boolean fitting(BlockState st) {
        return st.getBlock() instanceof com.jrpetty.mcassistant.block.VillageBoardBlock
            || st.getBlock() instanceof com.jrpetty.mcassistant.block.StorehouseBlock;
    }

    private static int signature(UUID id) {
        int s = Ledger.buildings(id).size() * 31 + Ledger.grownCount(id);
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(id).entrySet()) {
            s = s * 17 + e.getKey().hashCode() + e.getValue().anchor().hashCode();
        }
        return s;
    }

    static Index index(Level level, Villages.Village v) {
        long now = level.getGameTime();
        Index ix = INDEX.get(v.id());
        // Asked many times in a tick (a festival's patch, a column of ground work): checked against the register once.
        if (ix != null && ix.checked == now) return ix;
        int sig = signature(v.id());
        if (ix != null && ix.signature == sig && now - ix.made < KEEP && now >= ix.made) {
            ix.checked = now;
            return ix;
        }
        ix = build(v, now, sig);
        INDEX.put(v.id(), ix);
        return ix;
    }

    private static Index build(Villages.Village v, long now, int sig) {
        Index ix = new Index(now, sig);
        List<Owner> all = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(v.id())) all.add(new Owner(b, designOf(v.id(), b), false));
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(v.id()).entrySet()) {
            String s = e.getKey();
            if (!BuildGoal.STRUCTURES.contains(s)) continue;
            Villages.Site site = e.getValue();
            boolean dup = false;
            for (Owner o : all) if (o.building.anchor().equals(site.anchor()) && o.building.structure().equals(s)) dup = true;
            if (!dup) all.add(new Owner(new Ledger.Building(s, site.anchor(), site.facing()), s, true));
        }
        for (Owner o : all) {
            List<BuildGoal.Placement> plan;
            try {
                plan = BuildGoal.plan(o.design, o.building.anchor(), o.building.facing(), 13);
            } catch (RuntimeException ex) {
                continue;
            }
            if (plan.isEmpty()) continue;
            int i = ix.owners.size();
            ix.owners.add(o);
            fill(ix, o, i, plan);
        }
        return ix;
    }

    /** Lay a building's drawing into what the gate knows: its cells, its rectangles, its doors and its stairs. */
    private static void fill(Index ix, Owner o, int i, List<BuildGoal.Placement> plan) {
        BlockPos a = o.building.anchor();
        Direction facing = o.building.facing();
        int[] g = BuildGoal.footprint(o.design);
        int[] f = Blueprints.has(o.design) ? Blueprints.fullHalf(o.design) : g;
        boolean turned = facing.getAxis() == Direction.Axis.X;
        int gx = turned ? g[1] : g[0], gz = turned ? g[0] : g[1];
        int fx = turned ? f[1] : f[0], fz = turned ? f[0] : f[1];
        o.gx0 = a.getX() - gx; o.gx1 = a.getX() + gx; o.gz0 = a.getZ() - gz; o.gz1 = a.getZ() + gz;
        o.fx0 = a.getX() - fx; o.fx1 = a.getX() + fx; o.fz0 = a.getZ() - fz; o.fz1 = a.getZ() + fz;
        int top = a.getY();
        int bottom = a.getY();
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            top = Math.max(top, p.pos().getY());
            bottom = Math.min(bottom, p.pos().getY());
            ix.cells.putIfAbsent(p.pos().asLong(), i);
        }
        o.y0 = Math.min(bottom, a.getY() - 1);
        o.y1 = top + 1;
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR) mine.add(p.pos().asLong());
        for (BuildGoal.Placement p : plan) {
            BlockPos at = p.pos();
            if (p.part() == BuildGoal.Part.DOOR) {
                mark(ix, at, Zone.DOORWAY, i);
                mark(ix, at.above(), Zone.DOORWAY, i);
                Direction out = outOf(at, a, mine);
                if (out != null) {
                    for (int k = 1; k <= 2; k++) {
                        BlockPos step = at.relative(out, k);
                        if (mine.contains(step.asLong())) break;              // a porch's own floor: the building's
                        markIfFree(ix, step, Zone.APPROACH, i);
                        markIfFree(ix, step.above(), Zone.APPROACH, i);
                    }
                }
            } else if (p.part() == BuildGoal.Part.LADDER
                    || (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.STONE_STAIR)) {
                mark(ix, at, Zone.STAIRS, i);
                for (int h = 1; h <= 2; h++) {
                    if (!mine.contains(at.above(h).asLong())) markIfFree(ix, at.above(h), Zone.STAIRS, i);
                }
            }
        }
    }

    private static void mark(Index ix, BlockPos p, Zone z, int owner) {
        ix.special.put(p.asLong(), z);
        ix.specialOwner.put(p.asLong(), owner);
    }

    /** Mark a cell unless another building's doorway or stair is there already. */
    private static void markIfFree(Index ix, BlockPos p, Zone z, int owner) {
        Zone was = ix.special.get(p.asLong());
        if (was == Zone.DOORWAY || was == Zone.STAIRS) return;
        mark(ix, p, z, owner);
    }

    /** Which way is out of a door: the side whose cell is no part of the building, the farther from its middle. */
    @Nullable
    static Direction outOf(BlockPos door, BlockPos anchor, java.util.Set<Long> mine) {
        Direction best = null;
        double bestD = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = door.relative(d);
            if (mine.contains(n.asLong())) continue;
            double dx = n.getX() - anchor.getX(), dz = n.getZ() - anchor.getZ();
            double dist = dx * dx + dz * dz;
            if (dist > bestD) { bestD = dist; best = d; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the trace (tests, the audit)

    /** Who set each cell last, through the gate: for the audit's "what put this here". Off but in tests. */
    private static volatile boolean tracing;
    private static final Map<Long, Why> TRACE = new ConcurrentHashMap<>();
    /** The last refusals, for the log and the audit: cell, why, zone. */
    public record Refusal(BlockPos pos, Why why, Zone zone, @Nullable String building) {}
    private static final java.util.Deque<Refusal> REFUSED = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final java.util.concurrent.atomic.AtomicInteger REFUSALS = new java.util.concurrent.atomic.AtomicInteger();
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Tests: keep who set each cell. */
    public static void traceForTests(boolean on) {
        tracing = on;
        if (!on) TRACE.clear();
    }

    /** Who last set this cell through the gate (null: nobody, as far as it knows). */
    @Nullable
    public static Why setBy(BlockPos pos) {
        return TRACE.get(pos.asLong());
    }

    /** Note that a block went down here for this reason (for a caller that set it itself after asking). */
    public static void traced(@Nullable Villages.Village v, BlockPos pos, Why why) {
        if (tracing && v != null) TRACE.put(pos.asLong(), why);
    }

    /** The most recent refusals (newest last), and how many there have been. */
    public static List<Refusal> refusals() {
        return new ArrayList<>(REFUSED);
    }

    public static int refusalCount() {
        return REFUSALS.get();
    }

    private static void refused(Villages.Village v, BlockPos pos, Why why, Here h, @Nullable BlockPos own) {
        int n = REFUSALS.incrementAndGet();
        REFUSED.addLast(new Refusal(pos.immutable(), why, h.zone, h.owner == null ? null : h.owner.design));
        while (REFUSED.size() > 400) REFUSED.pollFirst();
        if (n <= 50 || n % 500 == 0) {
            LOG.info("[MCA-INTEGRITY] refused {} at {} ({}{}){} [{} so far]", why, pos.toShortString(), h.zone,
                h.owner == null ? "" : " of the " + h.owner.design + " at " + h.owner.building.anchor().toShortString(),
                own == null ? "" : " for the building at " + own.toShortString(), n);
        }
    }
}
