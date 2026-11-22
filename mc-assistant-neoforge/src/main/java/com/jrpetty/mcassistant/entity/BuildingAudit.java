package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [biz30] Every building of a town looked over as a player walking through it would, inside and out, and what spoils it
 * counted by kind (as TerraformAudit counts the founding's ground):
 * <ul>
 * <li><b>grass</b>: grass, earth, flowers or a wild tree's leaves on a floor or in a room (the ground's own showing
 *     through, or what grew there left standing);</li>
 * <li><b>earth_inside</b>: the ground (earth, rock) standing up into a room; <b>fluid_inside</b>: water or lava in one;</li>
 * <li><b>floor_hole</b>: a cell of a floor open to what is under it; <b>earth_floor</b>: a floor of bare earth;</li>
 * <li><b>floating</b>: the footing standing on nothing; <b>stray</b>: a block nobody drew left on its own in or by the
 *     building (a builder's pillar, a lump of earth), not touching anything;</li>
 * <li><b>door_missing</b>, <b>door_extra</b> (a door where the drawing has none), <b>door_blocked</b> (the way out of a
 *     door stopped up), <b>door_no_street</b> (no way walked from a door to the town's streets);</li>
 * <li><b>fixture_as_block</b>: a workstation, a chest or a barrel laid where a wall, floor or roof block goes (a
 *     crafting table for a brick); <b>chest_blocked</b>: a chest that will not open;</li>
 * <li><b>dark</b>: a floor cell inside where it is dark enough for a mob to come (no lamp's light at all);</li>
 * <li><b>no_sign</b>: a business with no sign or board at its front;</li>
 * <li><b>roof_gap</b>: a cell of its roof not laid; <b>scaffolding</b> left standing round it.</li>
 * </ul>
 * Never a block a player set down. Counted per building and for the town (BusinessLookGameTests, BuildingMend and the
 * operator's /village buildcheck).
 */
public final class BuildingAudit {

    private BuildingAudit() {}

    /** The kinds counted, in the report's order. */
    public static final String[] KINDS = { "grass", "earth_inside", "fluid_inside", "floor_hole", "earth_floor", "floating", "stray",
        "door_missing", "door_extra", "door_blocked", "door_no_street", "fixture_as_block", "chest_blocked", "dark", "no_sign",
        "roof_gap", "scaffolding" };

    /** The kinds that must come to nothing in a building the town finished (the test's bar). */
    public static final String[] BAR = { "grass", "earth_inside", "fluid_inside", "floor_hole", "earth_floor", "floating", "stray",
        "door_missing", "door_extra", "door_blocked", "fixture_as_block", "chest_blocked", "roof_gap", "scaffolding" };

    /** The town's businesses: each wants a sign at its front with its name on it. */
    public static final Set<String> BUSINESSES = Set.of("shop", "cafe", "tavern", "bakery", "smithy", "inn", "restaurant", "store",
        "brewery", "market", "auction", "bank", "postoffice", "tradingpost", "workshop", "infirmary", "fletcher", "maproom",
        "apiary", "wheelwright", "divershed", "huntlodge", "harbour", "stable", "smeltery", "guesthouse", "foundry");

    /** What has no rooms to look into (gardens, yards, the pitch, monuments): looked at for its ground and blocks only. */
    public static final Set<String> OPEN = Set.of("wall", "platform", "pen", "fortify", "column", "well", "monument", "fountain",
        "statue", "park", "orchard", "allotments", "pitch", "range", "graveyard", "theatre", "amphitheatre", "trainingyard",
        "stockade", "arena", "colossus", "aqueduct", "court", "gateway", "clockworkgate", "skygarden", "golemyard", "seamark");

    /** What the audit found: counts by kind, and the first few of each with where. */
    public static final class Report {
        public final Map<String, Integer> counts = new TreeMap<>();
        public final Map<String, List<String>> examples = new TreeMap<>();
        /** Per building: its name and its own counts (only what it has). */
        public final Map<String, Map<String, Integer>> each = new TreeMap<>();
        public int buildings;
        /** Every building looked at: its name, where, and which way its back faces. */
        public final List<String> seen = new ArrayList<>();

        public Report() {
            for (String k : KINDS) counts.put(k, 0);
        }

        void add(String building, String kind, int n, String where) {
            if (n <= 0) return;
            counts.merge(kind, n, Integer::sum);
            each.computeIfAbsent(building, k -> new TreeMap<>()).merge(kind, n, Integer::sum);
            List<String> l = examples.computeIfAbsent(kind, k -> new ArrayList<>());
            if (l.size() < 8) l.add(building + " " + where + (n > 1 ? " (x" + n + ")" : ""));
        }

        public int count(String kind) {
            return counts.getOrDefault(kind, 0);
        }

        /** The bar's kinds summed. */
        public int faults() {
            int n = 0;
            for (String k : BAR) n += count(k);
            return n;
        }

        /** One line: the counts that are not nothing. */
        public String line() {
            StringBuilder b = new StringBuilder(buildings + " buildings:");
            for (String k : KINDS) if (count(k) > 0) b.append(' ').append(k).append('=').append(count(k));
            if (faults() == 0 && count("dark") == 0 && count("no_sign") == 0 && count("door_no_street") == 0) b.append(" all clean");
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ the town

    /** Every building of the town's register looked over (those still going up passed by). */
    public static Report town(ServerLevel level, Villages.Village v) {
        Report r = new Report();
        UUID id = v.id();
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (Ledger.raising(id, b.anchor()) || !level.isLoaded(b.anchor())) continue;
            building(level, v, b.structure(), TownBlocks.designOf(id, b), b.anchor(), b.facing(), r);
        }
        return r;
    }

    /** One building looked over, into the report. {@code structure} is what it is (for its kind), {@code design} the drawing it stands to. */
    public static void building(ServerLevel level, @Nullable Villages.Village v, String structure, String design, BlockPos anchor,
                                Direction facing, Report r) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(design, anchor, facing, 13);
        if (plan.isEmpty() || structure.equals("fortify")) return;
        r.buildings++;
        String name = design + "@" + anchor.toShortString();
        r.seen.add(name + " " + facing.getName());
        Map<Long, BuildGoal.Placement> mine = new HashMap<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            mine.put(p.pos().asLong(), p);
            minY = Math.min(minY, p.pos().getY());
            maxY = Math.max(maxY, p.pos().getY());
        }
        if (mine.isEmpty()) return;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BuildGoal.Placement p : mine.values()) {
            if (p.pos().getY() != minY) continue;
            x0 = Math.min(x0, p.pos().getX()); x1 = Math.max(x1, p.pos().getX());
            z0 = Math.min(z0, p.pos().getZ()); z1 = Math.max(z1, p.pos().getZ());
        }
        boolean open = OPEN.contains(structure) || OPEN.contains(design);
        // Each column's ceiling: the lowest drawn block over the floor's first two cells (its roof or the storey over it).
        Map<Long, Integer> ceiling = new HashMap<>();
        for (BuildGoal.Placement p : mine.values()) {
            int y = p.pos().getY();
            if (y < minY + 3 || p.part() != BuildGoal.Part.BLOCK && p.part() != BuildGoal.Part.WINDOW) continue;
            long col = BlockPos.asLong(p.pos().getX(), 0, p.pos().getZ());
            ceiling.merge(col, y, Math::min);
        }

        // ---- the footing: on the ground all along
        int floating = 0;
        BlockPos fWhere = null;
        for (BuildGoal.Placement p : mine.values()) {
            if (p.pos().getY() != minY || p.part() != BuildGoal.Part.BLOCK) continue;
            BlockPos f = p.pos();
            if (!level.isLoaded(f) || !holds(level.getBlockState(f))) continue;
            if (!holds(level.getBlockState(f.below())) && !holds(level.getBlockState(f.below(2)))) {
                floating++;
                if (fWhere == null) fWhere = f;
            }
        }
        r.add(name, "floating", floating, at(fWhere));

        // The footing's own shape (a building is not always its box): a column is inside it where the footing layer is drawn
        // there and on all four sides of it.
        Set<Long> footed = new HashSet<>();
        for (BuildGoal.Placement p : mine.values()) if (p.pos().getY() == minY) footed.add(BlockPos.asLong(p.pos().getX(), 0, p.pos().getZ()));
        // ---- the rooms: what is in the cells the drawing leaves open, inside the walls and under a ceiling
        int grass = 0, earth = 0, fluid = 0, holes = 0, earthFloor = 0, dark = 0, stray = 0;
        BlockPos gW = null, eW = null, wW = null, hW = null, efW = null, dW = null, sW = null;
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                long col = BlockPos.asLong(x, 0, z);
                if (!footed.contains(col) || !footed.contains(BlockPos.asLong(x + 1, 0, z)) || !footed.contains(BlockPos.asLong(x - 1, 0, z))
                        || !footed.contains(BlockPos.asLong(x, 0, z + 1)) || !footed.contains(BlockPos.asLong(x, 0, z - 1))) continue;
                Integer roof = ceiling.get(col);
                boolean indoor = !open && roof != null;
                int top = indoor ? roof : minY + 3;
                // The ground floor: the drawing's floor cell, or the cell the floor stands on.
                BlockPos floor = new BlockPos(x, minY, z);
                if (level.isLoaded(floor) && !playerPut(level, floor)) {
                    BlockState fs = level.getBlockState(floor);
                    BuildGoal.Placement fp = mine.get(floor.asLong());
                    boolean drawnFloor = fp != null && fp.part() == BuildGoal.Part.BLOCK && fp.style() != Blueprints.Style.SOIL;
                    if (!open && (indoor || drawnFloor)) {
                        if (fs.is(Blocks.GRASS_BLOCK) || fs.is(Blocks.MYCELIUM) || fs.is(Blocks.PODZOL)) {
                            grass++;
                            if (gW == null) gW = floor;
                        } else if (drawnFloor && Restore.earthy(fs)) {
                            earthFloor++;
                            if (efW == null) efW = floor;
                        } else if (drawnFloor && !holds(fs) && fs.getFluidState().isEmpty() && mine.get(floor.above().asLong()) == null) {
                            holes++;
                            if (hW == null) hW = floor;
                        }
                    }
                }
                for (int y = minY + 1; y < Math.min(top, minY + 12); y++) {
                    BlockPos c = new BlockPos(x, y, z);
                    if (!level.isLoaded(c)) continue;
                    BuildGoal.Placement p = mine.get(c.asLong());
                    BlockState st = level.getBlockState(c);
                    if (p != null) {
                        // A drawn floor over a storey below, not laid.
                        if (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.FLOOR && y > minY + 1
                                && Restore.judge(st, p) == Restore.Kind.MISSING) {
                            holes++;
                            if (hW == null) hW = c;
                        }
                        if (p.part() == BuildGoal.Part.BLOCK && p.style() != Blueprints.Style.SOIL && !open && st.is(Blocks.GRASS_BLOCK)) {
                            grass++;
                            if (gW == null) gW = c;
                        }
                        continue;
                    }
                    if (playerPut(level, c)) continue;
                    if (!open && (st.is(Blocks.GRASS_BLOCK) || plantish(level, c, st))) {
                        if (indoor || st.is(Blocks.GRASS_BLOCK) || y <= minY + 2) {
                            grass++;
                            if (gW == null) gW = c;
                        }
                    } else if (Terraform.earth(st) && !st.is(Blocks.BEDROCK) && indoor) {
                        earth++;
                        if (eW == null) eW = c;
                    } else if (indoor && (st.getFluidState().is(FluidTags.WATER) || st.getFluidState().is(FluidTags.LAVA))) {
                        fluid++;
                        if (wW == null) wW = c;
                    } else if (indoor && st.isAir() && holds(level.getBlockState(c.below())) && passable(level.getBlockState(c.above()))
                            && level.getBrightness(LightLayer.BLOCK, c) == 0) {
                        dark++;
                        if (dW == null) dW = c;
                    }
                    if (holds(st) && !st.hasBlockEntity() && lone(level, c, mine)) {
                        stray++;
                        if (sW == null) sW = c;
                    }
                }
            }
        }
        r.add(name, "grass", grass, at(gW) + (gW == null ? "" : " " + id(level.getBlockState(gW))));
        r.add(name, "earth_inside", earth, at(eW) + (eW == null ? "" : " " + id(level.getBlockState(eW))));
        r.add(name, "fluid_inside", fluid, at(wW));
        r.add(name, "floor_hole", holes, at(hW));
        r.add(name, "earth_floor", earthFloor, at(efW) + (efW == null ? "" : " " + id(level.getBlockState(efW))));
        // A dark cell or two in a corner behind the stairs is a corner; a room of them is a dark room.
        r.add(name, "dark", dark >= 3 ? dark : 0, at(dW));
        r.add(name, "stray", stray, at(sW) + (sW == null ? "" : " " + id(level.getBlockState(sW))));

        // ---- the drawn cells: fixtures in the walls, roof gaps, a chest that will not open
        int asBlock = 0, roofGap = 0, chests = 0;
        BlockPos aW = null, rW = null, cW = null;
        for (BuildGoal.Placement p : mine.values()) {
            BlockPos c = p.pos();
            if (!level.isLoaded(c)) continue;
            BlockState st = level.getBlockState(c);
            if (p.part() == BuildGoal.Part.BLOCK && fixture(st) && !playerPut(level, c)) {
                asBlock++;
                if (aW == null) aW = c;
            }
            if (p.part() == BuildGoal.Part.BLOCK && BuildGoal.isRoof(p.style()) && Restore.judge(st, p) == Restore.Kind.MISSING) {
                roofGap++;
                if (rW == null) rW = c;
            }
            if (p.part() == BuildGoal.Part.CHEST && st.getBlock() instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, c)) {
                chests++;
                if (cW == null) cW = c;
            }
        }
        r.add(name, "fixture_as_block", asBlock, at(aW) + (aW == null ? "" : " " + id(level.getBlockState(aW))));
        r.add(name, "roof_gap", roofGap, at(rW));
        r.add(name, "chest_blocked", chests, at(cW));

        // ---- the doors
        Set<Long> drawnDoors = new HashSet<>();
        int missing = 0, blocked = 0, noStreet = 0;
        for (BuildGoal.Placement p : mine.values()) {
            if (p.part() != BuildGoal.Part.DOOR) continue;
            BlockPos door = p.pos();
            drawnDoors.add(door.asLong());
            drawnDoors.add(door.above().asLong());
            if (!level.isLoaded(door)) continue;
            if (!(level.getBlockState(door).getBlock() instanceof DoorBlock)) {
                missing++;
                r.add(name, "door_missing", 1, "door " + door.toShortString() + " " + id(level.getBlockState(door)));
                continue;
            }
            if (door.getY() != minY + 1) continue;                     // a door inside, or up a stair: its own room's
            Direction out = outward(door, x0, x1, z0, z1);
            if (out == null) continue;
            BlockPos step = door.relative(out);
            if (mine.containsKey(step.asLong()) && holds(level.getBlockState(step))) continue;   // the drawing's own porch
            if (holds(level.getBlockState(step)) || holds(level.getBlockState(step.above()))) {
                blocked++;
                r.add(name, "door_blocked", 1, "door " + door.toShortString() + " " + id(level.getBlockState(step)));
            } else if (v != null && !reachesStreet(level, v, step, x0, x1, z0, z1)) {
                noStreet++;
                r.add(name, "door_no_street", 1, "door " + door.toShortString());
            }
        }
        int extra = 0;
        BlockPos xW = null;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                for (int y = minY + 1; y <= Math.min(maxY, minY + 14); y++) {
                    BlockPos c = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(c);
                    if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
                    if (drawnDoors.contains(c.asLong()) || playerPut(level, c)) continue;
                    extra++;
                    if (xW == null) xW = c;
                }
            }
        }
        r.add(name, "door_extra", extra, at(xW));

        // ---- its front: a business's sign; scaffolding round it
        if (BUSINESSES.contains(structure) && !signed(level, x0, x1, z0, z1, minY)) r.add(name, "no_sign", 1, "");
        int scaff = 0;
        for (BlockPos q : BlockPos.betweenClosed(x0 - 3, minY - 1, z0 - 3, x1 + 3, maxY + 2, z1 + 3)) {
            if (level.isLoaded(q) && level.getBlockState(q).is(Blocks.SCAFFOLDING)) scaff++;
        }
        r.add(name, "scaffolding", scaff, "");
    }

    // ------------------------------------------------------------------ helpers

    private static String at(@Nullable BlockPos p) {
        return p == null ? "" : "first " + p.toShortString();
    }

    private static String id(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    /** Does this hold a floor up: something solid, not air, water, a plant or snow. */
    public static boolean holds(BlockState st) {
        return st.isSolid() && st.getFluidState().isEmpty() && !st.canBeReplaced();
    }

    private static boolean passable(BlockState st) {
        return !st.blocksMotion();
    }

    private static boolean playerPut(ServerLevel level, BlockPos pos) {
        return PlayerBlocks.placed(level, pos);
    }

    /** Something growing or lying on the ground that has no business in a building's room. */
    public static boolean plantish(ServerLevel level, BlockPos pos, BlockState st) {
        return SiteWork.litter(level, pos, st)
            || (st.is(BlockTags.LEAVES) && !(st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT)));
    }

    /** A workstation, chest, barrel or the like: furniture, never a wall's or a floor's block. */
    public static boolean fixture(BlockState st) {
        Block b = st.getBlock();
        return b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b instanceof ChestBlock || b == Blocks.BARREL || b == Blocks.LECTERN || b == Blocks.LOOM || b == Blocks.CARTOGRAPHY_TABLE
            || b == Blocks.FLETCHING_TABLE || b == Blocks.SMITHING_TABLE || b == Blocks.STONECUTTER || b == Blocks.GRINDSTONE
            || b == Blocks.COMPOSTER || b == Blocks.BREWING_STAND || b == Blocks.ENCHANTING_TABLE || b == Blocks.JUKEBOX
            || b == Blocks.NOTE_BLOCK || b == Blocks.BEEHIVE || b == Blocks.BEE_NEST || b instanceof net.minecraft.world.level.block.AnvilBlock;
    }

    /** A block nobody drew, touching nothing solid on any side (a pillar's last block, a lump hung in the air). */
    private static boolean lone(ServerLevel level, BlockPos c, Map<Long, BuildGoal.Placement> mine) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(c.relative(d));
            if (!n.isAir() && !(n.canBeReplaced() && n.getFluidState().isEmpty())) return false;
        }
        return true;
    }

    /** Which way is out of the building from a door in its walls. */
    @Nullable
    private static Direction outward(BlockPos door, int x0, int x1, int z0, int z1) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = door.relative(d);
            if (n.getX() < x0 || n.getX() > x1 || n.getZ() < z0 || n.getZ() > z1) return d;
        }
        return null;
    }

    /** A sign (or the shop's own sign board, a hanging sign, a banner) at the building's front, within two of its walls. */
    private static boolean signed(ServerLevel level, int x0, int x1, int z0, int z1, int minY) {
        for (BlockPos q : BlockPos.betweenClosed(x0 - 2, minY, z0 - 2, x1 + 2, minY + 6, z1 + 2)) {
            BlockState st = level.getBlockState(q);
            Block b = st.getBlock();
            if (b instanceof SignBlock || b instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
                    || b instanceof net.minecraft.world.level.block.WallHangingSignBlock
                    || b instanceof com.jrpetty.mcassistant.block.ShopSignBlock || st.is(BlockTags.BANNERS)) return true;
        }
        return false;
    }

    /**
     * A way walked from a door's step to the town's streets: over standing cells (a block to stand on, two clear over it,
     * a step of one up or down) out to the first cell whose ground is a road, the square, or the town's paving or a path
     * outside the building. Some three thousand cells at most.
     */
    static boolean reachesStreet(ServerLevel level, Villages.Village v, BlockPos step, int x0, int x1, int z0, int z1) {
        BlockPos start = stand(level, step);
        if (start == null) return false;
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(start);
        seen.add(start.asLong());
        int limit = 3000;
        while (!q.isEmpty() && limit-- > 0) {
            BlockPos c = q.poll();
            boolean inside = c.getX() >= x0 && c.getX() <= x1 && c.getZ() >= z0 && c.getZ() <= z1;
            if (!inside) {
                BlockState under = level.getBlockState(c.below());
                TownBlocks.Zone zone = TownBlocks.zone(level, v, c);
                if (zone == TownBlocks.Zone.ROAD || zone == TownBlocks.Zone.SQUARE || SiteWork.townPaving(level, c.below(), under)) return true;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                if (n.getX() >= x0 && n.getX() <= x1 && n.getZ() >= z0 && n.getZ() <= z1) continue;   // round it, not through it
                if (!level.isLoaded(n)) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos m = n.above(dy);
                    if (seen.contains(m.asLong())) break;
                    if (standable(level, m)) {
                        if (dy == 1 && level.getBlockState(c.above(2)).blocksMotion()) break;   // no head room to step up
                        seen.add(m.asLong());
                        q.add(m);
                        break;
                    }
                    if (!passes(level.getBlockState(m))) break;
                }
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos stand(ServerLevel level, BlockPos at) {
        for (int dy = 0; dy >= -2; dy--) if (standable(level, at.above(dy))) return at.above(dy);
        return null;
    }

    private static boolean standable(ServerLevel level, BlockPos c) {
        BlockState below = level.getBlockState(c.below());
        boolean ground = below.isFaceSturdy(level, c.below(), Direction.UP) || below.is(Blocks.DIRT_PATH) || below.is(Blocks.FARMLAND)
            || below.getBlock() instanceof net.minecraft.world.level.block.SlabBlock || below.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
        return ground && passes(level.getBlockState(c)) && passes(level.getBlockState(c.above()))
            && level.getBlockState(c).getFluidState().isEmpty();
    }

    /** Walked through: open, or a gate or a door anybody opens (a yard's gate is a way out, not a wall). */
    private static boolean passes(BlockState st) {
        return !st.blocksMotion() || st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
            || st.getBlock() instanceof DoorBlock;
    }
}
