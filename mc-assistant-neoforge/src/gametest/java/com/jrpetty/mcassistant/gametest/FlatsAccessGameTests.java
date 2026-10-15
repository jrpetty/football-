package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Gallery;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.Flats;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Interiors;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Renovation;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * [flats-access] The way in, up and round, walked by the folk themselves.
 *
 * <p>fa21: the bigger block of flats, built at each of the four turns of its lot, and a folk at each walking it on its own
 * feet and its own way-finding (its navigation, never set down anywhere): from the street in at the front door, up the
 * stair to every landing, through each flat's door, into the flat, to its bed and back out to the street; the stair's
 * steps the right way round with three blocks of air over each, every place on the way room for a player to stand.
 * fa22: every drawing there is built (through the game's own stamp, made over for the Iron Age and furnished as the
 * town furnishes it) at two turns, and from out in front of it the folk's own way-finding is asked for a way to every
 * room on every floor, a place beside every bed, a place at every chest, barrel, stove and bench, and back out; every
 * door one a folk can open, nothing standing in a doorway. What cannot be reached is named; the faults already known
 * (in drawings and in code others look after: scratchpad build-audit-drawings.md) are in KNOWN, so it is green now and
 * goes red the day anything new goes wrong. fa23: a town with blocks of the first, smaller flats keeps them as they are.
 *
 * <p>On ground of its own: x 2420000 to 2459999, z 70000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FlatsAccessGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;
    private static final Direction[] TURNS = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    // ============================================================ the ground

    /** A level green from x0 to x1, z0 to z1 at ground level y, open sky to {@code top} above it. */
    static void ground(ServerLevel level, int x0, int x1, int z0, int z1, int y, int top) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int h = y + top; h >= y - 4; h--) {
                    BlockPos p = new BlockPos(x, h, z);
                    BlockState want = h >= y ? air : h == y - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : h >= y - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    BlockState was = level.getBlockState(p);
                    if (was == want) continue;
                    if (level.getBlockEntity(p) instanceof Container c) c.clearContent();
                    level.setBlock(p, want, 2 | 16);
                }
            }
        }
        AABB box = new AABB(x0, y - 4, z0, x1 + 1, y + top + 1, z1 + 1);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player) && !(e instanceof VillageFolkEntity))) e.discard();
    }

    /** A place in a drawing (across, up, toward the back), in the world, for a building at this anchor with its back this way. */
    static BlockPos cell(BlockPos anchor, Direction facing, int dx, int h, int dz) {
        return anchor.relative(facing.getClockWise(), dx).relative(facing, dz).above(h);
    }

    /** A place in the world, in the drawing: {across, up, toward the back}. */
    static int[] local(BlockPos anchor, Direction facing, BlockPos p) {
        int x = p.getX() - anchor.getX(), z = p.getZ() - anchor.getZ();
        Direction r = facing.getClockWise();
        return new int[]{ x * r.getStepX() + z * r.getStepZ(), p.getY() - anchor.getY(), x * facing.getStepX() + z * facing.getStepZ() };
    }

    /**
     * Can a folk (and a player: the folk is the taller) stand here: something to stand on (a floor, a step, a rug or a
     * low slab in the place itself), nothing in the way of a body 1.95 high, no water. Not on a bed, a fence or a wall.
     */
    static boolean standable(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.getBlock() instanceof BedBlock || st.getBlock() instanceof DoorBlock || !level.getFluidState(p).isEmpty()) return false;
        double floor;
        VoxelShape own = st.getCollisionShape(level, p);
        if (!own.isEmpty()) {
            double top = own.max(Direction.Axis.Y);
            if (top > 0.6) return false;
            floor = p.getY() + top;
        } else {
            BlockPos below = p.below();
            VoxelShape under = level.getBlockState(below).getCollisionShape(level, below);
            if (under.isEmpty()) return false;
            double top = under.max(Direction.Axis.Y);
            if (top > 1.0 || top < 0.5) return false;
            floor = below.getY() + top;
        }
        AABB body = new AABB(p.getX() + 0.2, floor + 0.001, p.getZ() + 0.2, p.getX() + 0.8, floor + 1.95, p.getZ() + 0.8);
        return level.noCollision(body);
    }

    /** Room for a player (0.6 across, 1.8 high) to stand at this place of a path, on whatever it stands on. */
    static boolean playerFits(ServerLevel level, BlockPos p) {
        double floor = WalkNodeEvaluator.getFloorLevel(level, p);
        AABB body = new AABB(p.getX() + 0.2, floor + 0.001, p.getZ() + 0.2, p.getX() + 0.8, floor + 1.8, p.getZ() + 0.8);
        return level.noCollision(body);
    }

    /** The folk's own way from where it stands to this place (its navigation, the game's path finder), or null. */
    static Path wayTo(VillageFolkEntity f, BlockPos to) {
        PathNavigation nav = f.getNavigation();
        nav.stop();
        return nav.createPath(to, 0);
    }

    // ============================================================ fa21: the flats, walked

    /** One stop on a walk: where to stand, what it is, and the door it must go through to get there (or null). */
    record Stop(BlockPos at, String what, BlockPos through) {}

    /**
     * The bigger block of flats, built at each of the four turns (stamped, made over for the Iron Age, its ground floor's
     * doors hung and its balconies railed by Flats), and a folk at each walking it, all four at once: from the street in
     * at the front door, up to every landing, through each flat's door, into the flat to its bed's side, back out onto
     * the landing, and at the end back down and out into the street, every step of it on its own feet: nobody set down
     * anywhere (a folk moving more than a block and a half in a tick fails it). Every place the walks went room for a
     * player; the stair two flights to a storey with a landing between, its steps rising toward the back, three blocks of
     * air over each, a rail at the top landing's edge; every flat door a wooden door.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "fa21_flats_stairs_walked")
    public static void fa21_flats_stairs_walked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2420000;
        Kit.reset(level);
        Flats.resetForTests();
        level.setDayTime(24000L * 2 + 2500);
        Kit.hold(level, x + 70, Z, 120);
        Kit.prepare(level, x + 70, Z, 120);
        int y = Math.max(70, Kit.surface(level, x, Z).getY());
        ground(level, x - 24, x + 175, Z - 40, Z + 40, y, 34);
        BlockPos heart = new BlockPos(x, y, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        List<Ledger.Building> blocks = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            BlockPos anchor = new BlockPos(x + 44 + 36 * t, y, Z);
            BuildGoal.stamp(level, Flats.BLOCK, anchor, TURNS[t], 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Flats.BLOCK, anchor, TURNS[t]);
            Ledger.Building b = new Ledger.Building(Flats.BLOCK, anchor, TURNS[t]);
            Ages.now(level, v, b, Villages.Age.IRON);
            blocks.add(b);
        }
        Flats.fitFreeForTests(level, v);
        Homes.tickForTests(level, v);
        List<String> faults = new ArrayList<>();
        List<List<Stop>> routes = new ArrayList<>();
        for (Ledger.Building b : blocks) {
            stairs(level, b, faults);
            routes.add(route(level, id, b, faults));
        }
        Kit.log("fa21 four blocks up; " + Flats.flatsForTests(id).size() + " flats on the books; the stairs: "
            + (faults.isEmpty() ? "sound" : faults));
        helper.assertTrue(Flats.flatsForTests(id).size() == 24, "six flats a block: " + Flats.flatsForTests(id).size());
        helper.assertTrue(faults.isEmpty(), "the stairs and doors as drawn at every turn: " + faults);
        // The walkers: folk of the town, each set down in the street before its block to start.
        List<VillageFolkEntity> walkers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            VillageFolkEntity w = t == 0 ? first : VillageFolkSpawnerBlock.raise(level, heart.east(2 + t), 0.0F);
            helper.assertTrue(w != null, "a walker");
            BlockPos start = routes.get(t).get(0).at();
            w.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
            w.getNavigation().stop();
            walkers.add(w);
        }
        int[] at = new int[4], ticks = new int[4], refused = new int[4];
        boolean[] through = new boolean[4], done = new boolean[4];
        Vec3[] last = new Vec3[4];
        int[] checked = { 0 }, nodes = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            for (int k = 0; k < 4; k++) {
                if (done[k]) continue;
                VillageFolkEntity w = walkers.get(k);
                // On no work of its own today: no plot to be called back to (a hand half a minute off its plot is put
                // back on it, which is a lift, and not the walk wanted here).
                if (w.workZone() != null) w.setWorkZone(null);
                if (w.breakNowForTests() && level.getDayTime() % 24000 < 9000) level.setDayTime(level.getDayTime() + 200);
                List<Stop> route = routes.get(k);
                String who = TURNS[k] + "'s walker";
                Vec3 pos = w.position();
                if (last[k] != null && pos.distanceTo(last[k]) > 1.5) {
                    helper.fail(who + " was set down from " + last[k] + " to " + pos + " on the way to " + route.get(at[k]).what() + ": a walk, not a lift");
                }
                last[k] = pos;
                Stop stop = route.get(at[k]);
                if (stop.through() != null && w.blockPosition().equals(stop.through())) through[k] = true;
                if (arrived(w, stop.at())) {
                    if (stop.through() != null && !through[k]) helper.fail(who + " got to " + stop.what() + " not through its door " + stop.through().toShortString());
                    if (t % 1 == 0) Kit.log("fa21 @" + t + " " + who + " at " + stop.what() + " (" + stop.at().toShortString() + ") after " + ticks[k] + " ticks");
                    at[k]++;
                    ticks[k] = 0;
                    refused[k] = 0;
                    through[k] = false;
                    w.getNavigation().stop();
                    if (at[k] == route.size()) {
                        done[k] = true;
                        Kit.log("fa21 " + who + " walked the whole block: " + route.size() + " stops");
                    }
                    continue;
                }
                ticks[k]++;
                if (ticks[k] > 700) {
                    helper.fail(who + " never got to " + stop.what() + " (" + stop.at().toShortString() + "): it is at " + w.blockPosition().toShortString()
                        + " — " + w.debugLine());
                }
                PathNavigation nav = w.getNavigation();
                boolean mine = nav.getPath() != null && stop.at().equals(nav.getPath().getTarget());
                if (!mine || nav.isDone() || ticks[k] % 120 == 0) {
                    Path p = wayTo(w, stop.at());
                    if (p == null || !p.canReach()) {
                        if (++refused[k] > 40) {
                            helper.fail(who + " finds no way from " + w.blockPosition().toShortString() + " to " + stop.what() + " (" + stop.at().toShortString()
                                + "): " + (p == null ? "no path" : "the path ends at " + p.getEndNode()));
                        }
                        continue;
                    }
                    for (int i = 0; i < p.getNodeCount(); i++) {
                        Node n = p.getNode(i);
                        BlockPos q = new BlockPos(n.x, n.y, n.z);
                        nodes[0]++;
                        BlockState st = level.getBlockState(q);
                        if (st.getBlock() instanceof DoorBlock) {
                            if (!st.is(BlockTags.WOODEN_DOORS)) helper.fail(who + ": a door on the way a folk cannot open at " + q.toShortString());
                            continue;
                        }
                        if (!playerFits(level, q)) helper.fail(who + ": no room for a player at " + q.toShortString() + " on the way to " + stop.what()
                            + " (" + st + ", over " + level.getBlockState(q.above()) + ", " + level.getBlockState(q.above(2)) + ")");
                    }
                    checked[0]++;
                    nav.moveTo(p, 0.7);
                }
            }
            if (done[0] && done[1] && done[2] && done[3]) {
                Kit.log("fa21 all four walked at tick " + t + ": " + checked[0] + " ways planned, " + nodes[0] + " places on them checked for a player's room");
                helper.succeed();
            }
        });
    }

    private static boolean arrived(VillageFolkEntity w, BlockPos to) {
        return Math.abs(w.getX() - (to.getX() + 0.5)) < 0.75 && Math.abs(w.getZ() - (to.getZ() + 0.5)) < 0.75 && Math.abs(w.getY() - to.getY()) < 0.9;
    }

    /**
     * The block's stair as it stands at its turn: on each storey but the top a flight up the right-hand half of the hall
     * toward the back, two steps, a landing, two steps, each step a stair set rising toward the back (its back to the
     * block's back) the right way up, three blocks of air over every step and the landing; the top landing's rail; each
     * flat's door a wooden door, the front door too.
     */
    static void stairs(ServerLevel level, Ledger.Building b, List<String> faults) {
        String turn = b.facing().toString();
        for (int s = 0; s < 2; s++) {
            int base = 4 * s;
            int[][] steps = { { 4, base, -3 }, { 4, base + 1, -2 }, { 4, base + 2, 0 }, { 4, base + 3, 1 } };
            for (int[] c : steps) {
                BlockPos p = cell(b.anchor(), b.facing(), c[0], c[1], c[2]);
                BlockState st = level.getBlockState(p);
                if (!(st.getBlock() instanceof StairBlock)) { faults.add(turn + " storey " + (s + 1) + ": no step at " + p.toShortString() + " (" + st + ")"); continue; }
                if (st.getValue(StairBlock.FACING) != b.facing() || st.getValue(StairBlock.HALF) != Half.BOTTOM) {
                    faults.add(turn + " storey " + (s + 1) + ": the step at " + p.toShortString() + " is the wrong way round: " + st);
                }
                for (int k = 1; k <= 3; k++) {
                    if (!level.getBlockState(p.above(k)).getCollisionShape(level, p.above(k)).isEmpty()) {
                        faults.add(turn + " storey " + (s + 1) + ": " + k + " up over the step at " + p.toShortString() + " is " + level.getBlockState(p.above(k)));
                    }
                }
            }
            BlockPos landing = cell(b.anchor(), b.facing(), 4, base + 1, -1);
            if (!level.getBlockState(landing).isFaceSturdy(level, landing, Direction.UP)) faults.add(turn + ": no landing at " + landing.toShortString());
            for (int k = 1; k <= 3; k++) {
                if (!level.getBlockState(landing.above(k)).getCollisionShape(level, landing.above(k)).isEmpty()) faults.add(turn + ": no headroom over the landing");
            }
        }
        for (int[] c : new int[][]{ { 4, 8, -3 }, { 4, 9, -2 }, { 4, 9, -1 } }) {
            BlockPos p = cell(b.anchor(), b.facing(), c[0], c[1], c[2]);
            if (!level.getBlockState(p).is(BlockTags.FENCES)) faults.add(turn + ": no rail at the top landing's edge at " + p.toShortString());
        }
        List<BlockPos> doors = new ArrayList<>(List.of(cell(b.anchor(), b.facing(), 3, 0, -8)));
        for (int s = 0; s < 3; s++) for (int side : new int[]{ -1, 1 }) doors.add(cell(b.anchor(), b.facing(), 2, 4 * s, 6 * side));
        for (BlockPos d : doors) {
            BlockState st = level.getBlockState(d);
            if (!st.is(BlockTags.WOODEN_DOORS) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) faults.add(turn + ": no wooden door at " + d.toShortString() + " (" + st + ")");
        }
    }

    /**
     * The walk round a block: the street in front of its door; then, storey by storey, each flat: its door's landing, in
     * through the door, beside its bed, back out onto the landing; then the street again.
     */
    static List<Stop> route(ServerLevel level, UUID id, Ledger.Building b, List<String> faults) {
        BlockPos a = b.anchor();
        Direction f = b.facing();
        List<Stop> out = new ArrayList<>();
        BlockPos street = a.relative(f.getOpposite(), 13);
        out.add(new Stop(street, "the street", null));
        for (int s = 0; s < 3; s++) {
            for (int side : new int[]{ -1, 1 }) {
                String flat = "flat " + (s + 1) + (side < 0 ? "A" : "B");
                BlockPos landing = cell(a, f, 3, 4 * s, 6 * side), door = cell(a, f, 2, 4 * s, 6 * side), in = cell(a, f, 1, 4 * s, 6 * side);
                BlockPos anchor = cell(a, f, 0, 4 * s, 6 * side);
                if (Flats.numberForTests(id, anchor) == null) faults.add(f + ": no " + flat + " on the books at " + anchor.toShortString());
                BlockPos bedSide = null;
                for (BlockPos head : Homes.bedsForTests(level, id, anchor)) {
                    BlockState hs = level.getBlockState(head);
                    BlockPos foot = hs.getBlock() instanceof BedBlock ? head.relative(hs.getValue(BedBlock.FACING).getOpposite()) : head;
                    for (BlockPos part : List.of(foot, head)) {
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            BlockPos q = part.relative(d);
                            if (bedSide == null && Flats.insideForTests(id, anchor, q) && standable(level, q)) bedSide = q;
                        }
                    }
                }
                if (bedSide == null) { faults.add(f + ": nowhere to stand beside " + flat + "'s bed"); bedSide = in; }
                out.add(new Stop(landing, flat + "'s landing", null));
                out.add(new Stop(in, "inside " + flat, door));
                out.add(new Stop(bedSide, flat + "'s bed", null));
                out.add(new Stop(landing, "back out of " + flat, door));
            }
        }
        out.add(new Stop(street, "the street again", cell(a, f, 3, 0, -8)));
        return out;
    }

    // ============================================================ fa22: every building's rooms

    /**
     * The faults known when this was written, each "drawing KIND dx,h,dz" in the drawing's own terms (across, up,
     * toward the back): written up for those who look after those drawings and that code in the scratchpad's
     * build-audit-drawings.md ("for build-looks", "for build-world"). Take each off as it is put right (fa22 says
     * which are right now: "fa22 fixed"); anything not here fails it.
     */
    static final Set<String> KNOWN = Set.of(
        // The storeys up a ladder: the folk's way-finding (the game's ground path finder, FolkNavigation) never climbs one
        // (build-audit-drawings.md, "flats-access: the way in", 1).
        "barn ROOM 0,4,0", "cathedral ROOM -1,12,-8", "cathedral ROOM 0,12,-9", "chapel BARREL -1,9,-6",
        "chapel ROOM -1,9,-8", "clockworkgate ROOM -4,6,0", "grandlibrary LECTERN -4,4,-4", "grandlibrary LECTERN 4,4,-5",
        "grandlibrary ROOM -4,4,3", "grandlibrary ROOM 4,4,2", "grandlibrary ROOM 4,4,3", "house2 BED -2,4,1",
        "house2 BED 2,4,1", "house2 CHEST -1,4,-2", "house2 CHEST 2,4,-1", "house2 ROOM 0,4,0", "knighthall BARREL 3,5,-3",
        "knighthall BED -4,5,3", "knighthall BED 4,5,3", "knighthall CHEST -3,5,4", "knighthall CHEST 3,5,4",
        "knighthall ROOM 0,5,0", "knightmanor BARREL 3,5,-3", "knightmanor BED -3,5,2", "knightmanor BED 3,5,2",
        "knightmanor CHEST -2,5,3", "knightmanor CHEST 2,5,3", "knightmanor ROOM 0,5,0", "library_tall ROOM 0,5,0",
        "lighthouse CAULDRON 0,16,0", "manor BARREL 3,5,-3", "manor BED -3,5,-3", "manor BED -3,5,3", "manor BED -4,5,-3",
        "manor BED -4,5,3", "manor BED 3,5,3", "manor BED 4,5,3", "manor CHEST -2,5,-4", "manor CHEST -2,5,4",
        "manor CHEST 2,5,4", "manor ROOM 0,5,0", "observatory BARREL 2,5,1", "observatory BREWING_STAND -2,5,-1",
        "observatory BREWING_STAND 2,5,-1", "observatory CHEST -2,5,1", "observatory LECTERN -1,13,1",
        "observatory LECTERN 0,5,-2", "observatory LECTERN 0,9,-2", "observatory ROOM 0,13,-1", "observatory ROOM 0,5,0",
        "observatory ROOM 0,9,0", "tavern_tall ROOM 0,5,0", "townhall BARREL -1,13,-8", "townhall BARREL -1,9,-8",
        "townhall BARREL 4,5,3", "townhall BED -1,5,7", "townhall BED -3,5,3", "townhall BED -4,5,3", "townhall BED 0,5,7",
        "townhall CHEST -4,5,0", "townhall CHEST -4,5,8", "townhall CHEST 1,5,7", "townhall CRAFTING_TABLE 4,5,2",
        "townhall LECTERN 0,5,-8", "townhall ROOM 0,13,-7", "townhall ROOM 0,5,-4", "townhall ROOM 0,5,5",
        "townhall ROOM 0,9,-7", "villa BED -2,4,2", "villa BED -3,4,0", "villa BED -3,4,2", "villa BED 2,4,2",
        "villa BED 3,4,0", "villa BED 3,4,2", "villa CHEST -1,4,-1", "villa CHEST -1,4,3", "villa CHEST 1,4,3",
        "villa CHEST 2,4,-1", "villa ROOM 0,4,1", "workshop ROOM 0,4,0", "workshop_tall ROOM 0,4,0",
        "workshop_tall ROOM 0,7,0",
        // Behind a fence gate: the folk open doors, not gates (only the pen's and the stable's own, for the beasts) (the same, 2).
        "apiary CHEST 2,0,4", "apiary CRAFTING_TABLE -2,0,4", "apiary ROOM 0,0,4", "stable CAULDRON 2,0,-3",
        "stable ROOM 0,0,0", "stockade BED -1,0,1", "stockade BED 1,0,1",
        // The diver's shed: its drying fire makes the little room round it a place no folk will walk (the same, 3).
        "divershed BARREL 1,0,1", "divershed CRAFTING_TABLE 0,0,1", "divershed ROOM 0,0,-1", "divershed ROOM 0,0,0",
        "divershed SMOKER -1,0,1",
        // The storage shed: the aisle behind the storehouse's shelves, shut in by them (the same, 4).
        "storage ROOM 0,0,2"
    );

    /**
     * Every drawing there is, built at two turns of its lot (stamped as a town's builders lay it, made over for the Iron
     * Age, furnished as the town furnishes it, a block of flats' doors hung and railings up) on level ground, and from out
     * in front of it the folk's own way-finding asked for a way: to every room on every floor (every stretch of floor a
     * folk can stand on, apart from the rest by walls and doors), to a place beside every bed, to a place at every chest,
     * barrel, stove, bench and table it works at, and back out from the furthest of them. Every door wood, or iron with a
     * button or plate to open it; both sides of every door a place to stand. Every fault named (the drawing, the turn,
     * what, where in the drawing, why); a fault not in KNOWN fails it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "fa22_every_room_reached")
    public static void fa22_every_room_reached(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2440000;
        Kit.reset(level);
        Interiors.resetForTests();
        Ages.resetForTests();
        Flats.resetForTests();
        Kit.hold(level, x, Z, 80);
        Kit.prepare(level, x, Z, 80);
        int y = Math.max(70, Kit.surface(level, x, Z).getY() + 1);
        BlockPos lot = new BlockPos(x + 20, y, Z);
        BlockPos heart = new BlockPos(x - 40, y, Z);
        ground(level, heart.getX() - 12, heart.getX() + 12, Z - 12, Z + 12, y, 8);
        Villages.Village v = Villages.found(level, heart);
        VillageSpawner.supplyChest(level, heart.east(2));
        Villages.ageForTests(v.id(), Villages.Age.IRON);
        VillageFolkEntity walker = McAssistantMod.VILLAGE_FOLK.get().create(level);   // its feet and its way-finding: never in the world
        List<String> drawings = new ArrayList<>(Gallery.drawings(level.getServer()));
        for (String t : Ages.TALL) if (Blueprints.has(t + Blueprints.TALL)) drawings.add(t + Blueprints.TALL);
        Direction[] turns = { Direction.NORTH, Direction.EAST };
        Map<String, Map<String, String>> seen = new TreeMap<>();       // key -> turn -> why
        Map<String, Integer> tally = new TreeMap<>();
        int[] next = { 0 }, cleared = { 12 };
        Kit.log("fa22 " + drawings.size() + " drawings at " + turns.length + " turns: " + drawings);
        helper.onEachTick(() -> {
            int i = next[0]++;
            if (i > drawings.size() * turns.length) return;
            if (i == drawings.size() * turns.length) {
                report(helper, seen, tally);
                return;
            }
            String name = drawings.get(i / turns.length);
            Direction facing = turns[i % turns.length];
            int[] full = Blueprints.fullHalf(name);
            int top = 0;
            for (Blueprints.Cell c : Blueprints.cells(name)) top = Math.max(top, c.h());
            int r = Math.max(full[0], full[1]) + 6;
            int reach = Math.max(r, cleared[0]);
            ground(level, lot.getX() - reach, lot.getX() + reach, lot.getZ() - reach, lot.getZ() + reach, y, top + 8);
            cleared[0] = r;
            if (i % 8 == 0) stock(level, v);
            Ledger.forget(v.id());
            Interiors.resetForTests();
            Ages.resetForTests();
            try {
                BuildGoal.stamp(level, name, lot, facing, 13, Showcase.painter(Showcase.OAK));
                Ledger.built(v.id(), name, lot, facing);
                Ledger.Building b = new Ledger.Building(name, lot, facing);
                Ages.now(level, v, b, Villages.Age.IRON);
                for (int k = 0; k < 12; k++) if (Interiors.work(level, v, 4000) == 0) break;
                if (Flats.isBlock(name)) Flats.fitFreeForTests(level, v);
                one(level, walker, name, lot, facing, seen, tally);
            } catch (RuntimeException e) {
                seen.computeIfAbsent(name + " BUILT -", k -> new TreeMap<>()).put(facing.toString(), e.toString());
                Kit.LOG.error("[VT] fa22 " + name + " " + facing, e);
            }
        });
    }

    /** The stores filled with what the town furnishes a room with. */
    private static void stock(ServerLevel level, Villages.Village v) {
        ItemStack[] stock = { new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.YELLOW_CARPET, 64), new ItemStack(Items.BARREL, 32),
            new ItemStack(Items.BOOKSHELF, 32), new ItemStack(Items.LANTERN, 32), new ItemStack(Items.CANDLE, 32),
            new ItemStack(Items.FLOWER_POT, 32), new ItemStack(Items.POPPY, 16), new ItemStack(Items.DANDELION, 16) };
        for (ItemStack s : stock) Market.intoStoresForTests(level, v.id(), s);
    }

    /** The kinds of thing a folk works at (opens, cooks at, crafts at, reads at). */
    static boolean station(BlockState st) {
        Block b = st.getBlock();
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof AbstractFurnaceBlock || b instanceof BrewingStandBlock
            || b instanceof CraftingTableBlock || b instanceof LecternBlock || b instanceof LoomBlock || b instanceof GrindstoneBlock
            || b instanceof StonecutterBlock || b instanceof SmithingTableBlock || b instanceof CartographyTableBlock
            || b instanceof AnvilBlock || b instanceof EnchantingTableBlock || b instanceof AbstractCauldronBlock
            || b instanceof com.jrpetty.mcassistant.block.StorehouseBlock;
    }

    /** One building at one turn: everything a folk should get to, asked of its way-finding from the street. */
    private static void one(ServerLevel level, VillageFolkEntity walker, String name, BlockPos anchor, Direction facing,
                            Map<String, Map<String, String>> seen, Map<String, Integer> tally) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(name, anchor, facing, 13);
        int[] full = Blueprints.fullHalf(name);
        BlockPos start = anchor.relative(facing.getOpposite(), full[1] + 3);
        String turn = facing.toString();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            BlockPos q = p.pos();
            x0 = Math.min(x0, q.getX()); x1 = Math.max(x1, q.getX());
            y0 = Math.min(y0, q.getY()); y1 = Math.max(y1, q.getY());
            z0 = Math.min(z0, q.getZ()); z1 = Math.max(z1, q.getZ());
        }
        if (x0 == Integer.MAX_VALUE) return;
        tally.merge("buildings", 1, Integer::sum);
        // The targets: what, where (the place it is about), and the places that would do to stand at.
        record Target(String kind, BlockPos about, List<BlockPos> stands) {}
        List<Target> targets = new ArrayList<>();
        // Every room on every floor: each stretch of floor a folk can stand on (over the drawing's floors and footings),
        // apart from the others by walls, furniture and doors; asked for its middle.
        Map<Integer, Set<BlockPos>> floors = new TreeMap<>();
        for (BuildGoal.Placement p : plan) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            if (p.style() != Blueprints.Style.FLOOR && p.style() != Blueprints.Style.FOUNDATION) continue;
            BlockPos q = p.pos().above();
            if (standable(level, q)) floors.computeIfAbsent(q.getY(), k -> new HashSet<>()).add(q);
        }
        for (Set<BlockPos> floor : floors.values()) {
            Set<BlockPos> left = new HashSet<>(floor);
            while (!left.isEmpty()) {
                BlockPos seed = left.iterator().next();
                List<BlockPos> room = new ArrayList<>();
                List<BlockPos> todo = new ArrayList<>(List.of(seed));
                left.remove(seed);
                while (!todo.isEmpty()) {
                    BlockPos q = todo.remove(todo.size() - 1);
                    room.add(q);
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = q.relative(d);
                        if (left.remove(n)) todo.add(n);
                    }
                }
                // A room is a floor somebody is meant to be on (as the drawings' own test has it): something on it or by
                // it (a bed, a chest or a bench, a lamp, a rug, a ladder) or a door onto it. A bare one is the roof space,
                // boarded over and shut up, or the top of a wall.
                if (room.size() < 2 || !lived(level, room)) continue;
                double cx = 0, cz = 0;
                for (BlockPos q : room) { cx += q.getX(); cz += q.getZ(); }
                cx /= room.size();
                cz /= room.size();
                BlockPos mid = room.get(0);
                for (BlockPos q : room) if (sq(q, cx, cz) < sq(mid, cx, cz) || sq(q, cx, cz) == sq(mid, cx, cz) && q.asLong() < mid.asLong()) mid = q;
                targets.add(new Target("ROOM", mid, List.of(mid)));
            }
        }
        // Beside every bed; at every thing a folk works at; both sides of every door.
        List<String> doorFaults = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.FOOT) {
                // Beside it: a place next to its foot or its head, square on or at a corner (the inner bed of a pair is
                // got into from the corner of the other's foot).
                BlockPos foot = q.immutable(), head = foot.relative(st.getValue(BedBlock.FACING));
                List<BlockPos> stands = new ArrayList<>();
                for (BlockPos part : List.of(foot, head)) {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = part.offset(dx, 0, dz);
                        if ((dx != 0 || dz != 0) && !stands.contains(n) && standable(level, n)) stands.add(n);
                    }
                }
                targets.add(new Target("BED", foot, stands));
            } else if (st.getBlock() instanceof com.jrpetty.mcassistant.block.StorehouseBlock) {
                // The Village Storehouse is one store however many units its shelves hold: a place at any of them will do.
                BlockPos at = q.immutable();
                Target whole = null;
                for (Target t : targets) if (t.kind().equals("STOREHOUSE")) whole = t;
                if (whole == null) targets.add(whole = new Target("STOREHOUSE", at, new ArrayList<>()));
                for (BlockPos s : standsAt(level, at)) if (!whole.stands().contains(s)) whole.stands().add(s);
            } else if (station(st)) {
                BlockPos at = q.immutable();
                List<BlockPos> stands = standsAt(level, at);
                String what = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().toUpperCase(java.util.Locale.ROOT);
                targets.add(new Target(what, at, stands));
            } else if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                BlockPos d = q.immutable();
                if (!st.is(BlockTags.WOODEN_DOORS)) {
                    boolean opener = false;
                    for (BlockPos n : BlockPos.betweenClosed(d.offset(-1, 0, -1), d.offset(1, 2, 1))) {
                        BlockState o = level.getBlockState(n);
                        if (o.is(BlockTags.BUTTONS) || o.is(BlockTags.PRESSURE_PLATES) || o.is(Blocks.LEVER)) opener = true;
                    }
                    if (!opener) note(seen, name, turn, "DOOR", anchor, facing, d, "an iron door with nothing to open it");
                }
                Direction way = st.getValue(DoorBlock.FACING);
                for (BlockPos side : List.of(d.relative(way), d.relative(way.getOpposite()))) {
                    if (!standable(level, side) && !level.getBlockState(side).is(BlockTags.DOORS)) {
                        note(seen, name, turn, "DOOR", anchor, facing, d, "nowhere to stand " + (side.equals(d.relative(way)) ? "before" : "behind")
                            + " it: " + level.getBlockState(side) + " / " + level.getBlockState(side.above()));
                    }
                }
                tally.merge("doors", 1, Integer::sum);
            }
        }
        // The way to each, from the street.
        BlockPos furthest = null;
        for (Target t : targets) {
            tally.merge(t.kind().equals("ROOM") || t.kind().equals("BED") ? t.kind() : "STATION", 1, Integer::sum);
            if (t.stands().isEmpty()) {
                note(seen, name, turn, t.kind(), anchor, facing, t.about(), "no place to stand at it (furniture or walls all round)");
                continue;
            }
            BlockPos got = null;
            Path last = null;
            for (BlockPos s : t.stands()) {
                walker.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
                walker.setOnGround(true);
                Path p = wayTo(walker, s);
                last = p;
                if (p != null && p.canReach()) { got = s; break; }
            }
            if (got == null) {
                note(seen, name, turn, t.kind(), anchor, facing, t.about(), "no way to it from the street (" + (last == null ? "no path at all"
                    : "the nearest the way gets is " + last.getEndNode() + ", " + Math.round(Math.sqrt(last.getEndNode() == null ? 0
                    : last.getEndNode().asBlockPos().distSqr(t.stands().get(0)))) + " off") + ")");
                continue;
            }
            tally.merge("reached", 1, Integer::sum);
            if (furthest == null || got.getY() > furthest.getY() || got.getY() == furthest.getY() && got.distSqr(start) > furthest.distSqr(start)) furthest = got;
        }
        // A plan of each floor with a fault on it, for whoever puts it right (at the north turn, the drawing's own way up:
        // the back at the top). '#' in the way, '.' a place to stand, 'D' a door, 'B' a bed, 'C' a chest or a bench,
        // 'H' a ladder, '^' a step, '~' fire, 'o' a gate, 'x' the fault.
        if (facing == Direction.NORTH) {
            Set<Integer> shown = new HashSet<>();
            for (Map.Entry<String, Map<String, String>> e : seen.entrySet()) {
                if (!e.getKey().startsWith(name + " ") || !e.getValue().containsKey(turn)) continue;
                String[] w = e.getKey().split(" ");
                if (w.length < 3) continue;
                String[] d = w[2].split(",");
                if (d.length < 3) continue;
                int h = Integer.parseInt(d[1]), fx = Integer.parseInt(d[0]), fz = Integer.parseInt(d[2]);
                if (!shown.add(h)) continue;
                StringBuilder sb = new StringBuilder();
                for (int z = z0; z <= z1; z++) {
                    if (sb.length() > 0) sb.append('|');
                    for (int x = x0; x <= x1; x++) {
                        BlockPos q = new BlockPos(x, anchor.getY() + h, z);
                        int[] l = local(anchor, facing, q);
                        BlockState st = level.getBlockState(q);
                        char c = l[0] == fx && l[2] == fz ? 'x' : st.getBlock() instanceof DoorBlock ? 'D' : st.getBlock() instanceof BedBlock ? 'B'
                            : station(st) || st.getBlock() instanceof com.jrpetty.mcassistant.block.StorehouseBlock ? 'C' : st.is(Blocks.LADDER) ? 'H'
                            : st.getBlock() instanceof StairBlock ? '^' : st.is(BlockTags.FENCE_GATES) ? 'o' : st.is(BlockTags.FIRE) || st.is(BlockTags.CAMPFIRES) ? '~'
                            : standable(level, q) ? '.' : st.getCollisionShape(level, q).isEmpty() && level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty() ? ' ' : '#';
                        sb.append(c);
                    }
                }
                Kit.log("fa22 plan " + name + " layer " + h + " (back at the top): " + sb);
            }
        }
        // And back out from the furthest of them.
        if (furthest != null) {
            walker.moveTo(furthest.getX() + 0.5, furthest.getY(), furthest.getZ() + 0.5, 0, 0);
            walker.setOnGround(true);
            Path p = wayTo(walker, start);
            if (p == null || !p.canReach()) note(seen, name, turn, "OUT", anchor, facing, furthest, "no way back out to the street from here");
        }
    }

    /** The places a folk could stand to work at something here: beside it or at a corner of it, a step up or down. */
    private static List<BlockPos> standsAt(ServerLevel level, BlockPos at) {
        List<BlockPos> stands = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos s = at.offset(dx, dy, dz);
                    if (standable(level, s)) stands.add(s);
                }
            }
        }
        return stands;
    }

    /** Is this stretch of floor lived on: a bed, a chest, a bench, a lamp, a rug or a ladder on it or beside it, or a door onto it? */
    private static boolean lived(ServerLevel level, List<BlockPos> room) {
        for (BlockPos q : room) {
            for (int dy = 0; dy <= 2; dy++) {
                BlockState here = level.getBlockState(q.above(dy));
                if (here.is(BlockTags.WOOL_CARPETS) || here.getBlock() instanceof net.minecraft.world.level.block.LanternBlock
                    || here.is(Blocks.TORCH) || here.is(Blocks.WALL_TORCH) || here.is(BlockTags.CANDLES)) return true;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockState n = level.getBlockState(q.relative(d).above(dy));
                    if (n.getBlock() instanceof BedBlock || n.getBlock() instanceof DoorBlock || n.is(Blocks.LADDER) || station(n)
                        || n.is(Blocks.BOOKSHELF) || n.getBlock() instanceof com.jrpetty.mcassistant.block.StorehouseBlock) return true;
                }
            }
        }
        return false;
    }

    private static double sq(BlockPos q, double cx, double cz) {
        return (q.getX() - cx) * (q.getX() - cx) + (q.getZ() - cz) * (q.getZ() - cz);
    }

    /** A fault seen: keyed by the drawing, its kind and where in the drawing; with each turn it was seen at and why. */
    private static void note(Map<String, Map<String, String>> seen, String name, String turn, String kind, BlockPos anchor, Direction facing,
                             BlockPos at, String why) {
        int[] d = local(anchor, facing, at);
        seen.computeIfAbsent(name + " " + kind + " " + d[0] + "," + d[1] + "," + d[2], k -> new TreeMap<>()).putIfAbsent(turn, why);
    }

    private static void report(GameTestHelper helper, Map<String, Map<String, String>> seen, Map<String, Integer> tally) {
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : seen.entrySet()) {
            boolean known = KNOWN.contains(e.getKey());
            String line = e.getKey() + " at " + e.getValue().keySet() + ": " + e.getValue().values().iterator().next();
            Kit.log("fa22 " + (known ? "known " : "FAULT ") + line);
            if (!known) fresh.add(line);
        }
        for (String k : new TreeSet<>(KNOWN)) if (!seen.containsKey(k)) Kit.log("fa22 fixed (take it off KNOWN): " + k);
        Kit.log("fa22 " + tally + "; faults " + seen.size() + " (" + fresh.size() + " not known)");
        helper.assertTrue(fresh.isEmpty(), fresh.size() + " places a folk cannot get to or out of, not known before (drawing KIND dx,h,dz): "
            + fresh.subList(0, Math.min(12, fresh.size())));
        helper.succeed();
    }

    // ============================================================ fa23: the first blocks kept

    /**
     * A town that built a block of flats before they were made bigger keeps it as it stands: on the register it becomes
     * "flats_old" (so its repairs and renovation go by the drawing it was built to), its renovation stamp is carried over
     * (not out of date), its six flats are where they were with their nine beds, a single folk takes one, and in the
     * Diamond Age it gets its fourth storey to the old drawing ("flats4_old": eight flats, twelve beds). A block built
     * since, on a long lot, is the bigger one, "flats", with its own six.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fa23_first_blocks_kept")
    public static void fa23_first_blocks_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2450000;
        Kit.reset(level);
        Flats.resetForTests();
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, Z, 90);
        Kit.prepare(level, x, Z, 90);
        VillageFolkEntity wren = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(wren != null, "a village");
        UUID id = wren.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        wren.rentFree(false);
        // The old block, on a house's lot, put up and registered as the game did then.
        TownPlan.Lot lot = null;
        for (TownPlan.Lot l : TownPlan.candidates("house")) if (l.kind() == TownPlan.Kind.LOT) { lot = l; break; }
        BlockPos old = Kit.surface(level, v.centre().getX() + lot.x(), v.centre().getZ() + lot.z());
        Direction back = Villages.direction(lot.back());
        ground(level, old.getX() - 7, old.getX() + 7, old.getZ() - 7, old.getZ() + 7, old.getY(), 24);
        BuildGoal.stamp(level, Flats.OLD, old, back, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, Flats.BLOCK, old, back);
        Flats.firstBlockForTests(id, old);
        Renovation.stampForTests(id, old, new Renovation.Stamp(Blueprints.version(Flats.OLD), Flats.BLOCK, "medieval", "IRON", "-", 1));
        Homes.tickForTests(level, v);
        String was = Flats.structureForTests(id, old);
        Renovation.Stamp stamp = Renovation.stamp(id, old);
        List<BlockPos> flats = Flats.flatsForTests(id);
        int beds = 0;
        for (BlockPos f : flats) beds += Homes.bedsForTests(level, id, f).size();
        BlockPos home = Homes.homeOf(wren);
        Kit.log("fa23 the first block: on the register as " + was + ", stamped " + (stamp == null ? null : stamp.drawing() + " v" + stamp.version())
            + "; " + flats.size() + " flats " + flats + ", " + beds + " beds; " + wren.displayNameCap() + " lives at " + home
            + (home == null ? "" : " (flat " + Flats.numberForTests(id, home) + ")"));
        helper.assertTrue(Flats.OLD.equals(was), "kept as it stands, on the register as " + Flats.OLD + ": " + was);
        helper.assertTrue(stamp != null && Flats.OLD.equals(stamp.drawing()) && stamp.version() == Blueprints.version(Flats.OLD),
            "its renovation stamp carried over, so it is not out of date: " + (stamp == null ? null : stamp.drawing()));
        helper.assertTrue(flats.size() == 6 && flats.contains(cell(old, back, -1, 0, -2)) && flats.contains(cell(old, back, -1, 8, 2)),
            "its six flats where they were: " + flats);
        helper.assertTrue(beds == 9, "nine beds: " + beds);
        helper.assertTrue(home != null && Flats.numberForTests(id, home) != null, "a single folk takes one of its flats: " + home);
        // A block put up since: the bigger one, on a long lot.
        Villages.Site site = Villages.siteFor(level, id, Flats.BLOCK);
        helper.assertTrue(site != null, "a long lot for a new block: " + Villages.lotReport(id));
        BuildGoal.stamp(level, Flats.BLOCK, site.anchor(), site.facing(), 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, Flats.BLOCK, site.anchor(), site.facing());
        Homes.tickForTests(level, v);
        String now = Flats.structureForTests(id, site.anchor());
        int all = Flats.flatsForTests(id).size();
        Kit.log("fa23 a new block at " + site.anchor().toShortString() + ": " + now + ", flats in town " + all);
        helper.assertTrue(Flats.BLOCK.equals(now) && all == 12, "the new block the bigger drawing, six more flats: " + now + ", " + all);
        helper.assertTrue(Flats.numberForTests(id, cell(site.anchor(), site.facing(), 0, 4, 6)) != null, "its flats where the bigger drawing has them");
        // The Diamond Age: the old block's fourth storey, to its own drawing.
        Villages.ageForTests(id, Villages.Age.DIAMOND);
        Flats.raiseForTests(level, v, old);
        Homes.tickForTests(level, v);
        String tall = Flats.structureForTests(id, old);
        int after = Flats.flatsForTests(id).size(), bedsPlanned = Flats.bedsPlannedForTests(id);
        boolean step = level.getBlockState(cell(old, back, 3, 8, 3)).getBlock() instanceof StairBlock;
        Kit.log("fa23 the first block's fourth storey: " + tall + ", flats in town " + after + ", beds planned " + bedsPlanned + ", the stair carried up " + step);
        helper.assertTrue(Flats.OLD_TALLER.equals(tall) && after == 14 && bedsPlanned == 21 && step,
            "its fourth storey to the old drawing: " + tall + ", " + after + " flats, " + bedsPlanned + " beds");
        helper.succeed();
    }
}
