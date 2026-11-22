package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [mine7] The town's digs kept out of the town.
 *
 * <p>Towns dug their mines in their own streets. The mine was chosen once, out beyond the town as it stood
 * that day (its first ring of lots, forty-one blocks), sixty-odd blocks out with its faces reaching back
 * toward the houses; the town grew to its second and third rings (ninety-one blocks) and stood on the stairs.
 * A miner with no face to be had staked a plot of its own just past the houses of the day, the same way. A
 * miner was only moved off its plot once a house stood on it, never for a street, a lot or the square's
 * ground. And any hand sent for stone, earth, sand or gravel cut it where it stood, in the middle of town.
 *
 * <p>Now the town's ground is the ground it will grow over: its whole plan (the square and three rings of
 * lots) with a margin, its farmland, its buildings and lines wherever they stand, and the frontage of its
 * roads. No village dig is made in it, save on the mine's own ground (MineGround: set aside for the mine for
 * good when it is opened, beyond the plan; an older world's mine set aside as it stands): a hand sent for
 * earth or stone looks for it outside the town; a miner whose plot lies in it moves to a face of the mine,
 * or waits for one. The town fills and turfs the stray stairs it left in its ground off the mine's ground, a
 * staircase at a time (only its own: the stairs its miners cut, marked in MineStairs, or an older world's with a miner's plot or a
 * face of its mine at their key; never a player's digging or a helper's stairs). Out at the mine each staircase gets a timber portal with a lantern, and a
 * path is trodden from the town's edge to it.
 */
public final class TownDigs {

    private TownDigs() {}

    /** The town's whole plan: the square and its three rings of lots, and the street round them. */
    public static final int PLAN_REACH = TownPlan.RING + TownPlan.RINGS * TownPlan.PERIOD + 2;
    /** Kept clear of the plan's edge, the buildings and the farmland. */
    public static final int MARGIN = 6;
    /** Kept clear of the town's roads to its neighbours (their frontage). */
    public static final int ROAD_KEEP = 8;
    /** How often the town looks over its old digs, its portals and its path. */
    static final long EVERY = 600L;
    /** Blocks of earth laid into old stairs on one look. */
    static final int FILL_A_LOOK = 48;
    /** Tests: the same. */
    public static final int FILL_A_LOOK_FOR_TESTS = FILL_A_LOOK;
    /** Blocks of path trodden on one look. */
    static final int PATH_A_LOOK = 32;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, List<int[]>> ROADS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ROADS_AT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
        ROADS.clear();
        ROADS_AT.clear();
    }

    // ------------------------------------------------------------------ the town's ground

    /** Within the town's whole plan (and so much more), or on its farmland: the ground it grows over. */
    public static boolean inPlan(@Nullable UUID village, BlockPos heart, BlockPos p, int margin) {
        int d = Math.max(Math.abs(p.getX() - heart.getX()), Math.abs(p.getZ() - heart.getZ()));
        if (d <= Math.max(PLAN_REACH, CityGrowth.reach(village) + 2) + MARGIN + margin) return true;   // [city26] the rings laid out since
        return village != null && Villages.onFarmland(village, heart, p, MARGIN + margin);
    }

    /**
     * The town's ground: its whole plan with a margin, its farmland, its buildings and lines (wherever they
     * stand, with the margin), the frontage of its roads. A plot this far round the spot (margin) must be clear
     * of all of it.
     */
    public static boolean inTownGround(ServerLevel level, @Nullable UUID village, BlockPos heart, BlockPos p, int margin) {
        if (MineGround.reserved(village, p.getX(), p.getZ())) return false;   // the mine's own ground: its digs are the mine's
        return inTownGroundRaw(level, village, heart, p, margin);
    }

    /** The town's ground, the mine's ground or not (as the mine's ground is set aside: MineGround.reserve). */
    public static boolean inTownGroundRaw(ServerLevel level, @Nullable UUID village, BlockPos heart, BlockPos p, int margin) {
        if (inPlan(village, heart, p, margin)) return true;
        if (village == null) return false;
        if (TownMine.underTheTown(TownMine.builtGround(village, heart, level.getGameTime()), p, MARGIN + margin)) return true;
        return byARoad(level, village, p, ROAD_KEEP + margin);
    }

    /** Within so many blocks of one of the town's roads to its neighbours. */
    static boolean byARoad(ServerLevel level, UUID village, BlockPos p, int keep) {
        long now = level.getGameTime();
        Long at = ROADS_AT.get(village);
        List<int[]> pts = ROADS.get(village);
        if (pts == null || at == null || now - at > 1200L || now < at) {
            pts = new ArrayList<>();
            try {
                for (RoadWorks.Road r : RoadWorks.roads(village)) {
                    List<int[]> line = r.line();
                    for (int i = 0; i < line.size(); i += 4) pts.add(line.get(i));
                    if (!line.isEmpty()) pts.add(line.get(line.size() - 1));
                }
            } catch (RuntimeException e) {
                // no roads to be read: none kept clear of
            }
            ROADS.put(village, pts);
            ROADS_AT.put(village, now);
        }
        int k = keep + 2;
        for (int[] q : pts) if (Math.abs(q[0] - p.getX()) <= k && Math.abs(q[1] - p.getZ()) <= k) return true;
        return false;
    }

    /**
     * May this hand cut this block (a gather of stone, earth, sand, gravel or ore)? A village's folk never in the
     * town's ground, except in the town's mine; timber and cane anywhere, and the player's own helpers as ever.
     */
    public static boolean mayCut(AssistantEntity a, GatherGoal.Kind kind, BlockPos p) {
        if (kind == GatherGoal.Kind.LOGS || kind == GatherGoal.Kind.SUGAR_CANE) return true;
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level)) return true;
        UUID village = f.ownerId();
        BlockPos heart = f.villageCentre();
        if (village == null || heart == null) return true;
        // [mine8] Nor in a mine's ground that has its works: the shaft, the landings and the drifts lie under it, and a hand
        // after stone at the surface there broke into the stair and fell down the works.
        if (MineWorks.keepsOff(level, p)) return false;
        return !inTownGround(level, village, heart, p, 0);
    }

    /** Is this village miner's plot in the town's ground (it must not dig there)? */
    public static boolean plotInTown(AssistantEntity a) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level)) return false;
        WorkZone z = f.workZone();
        UUID village = f.ownerId();
        BlockPos heart = f.villageCentre();
        if (z == null || village == null || heart == null) return false;
        return inTownGround(level, village, heart, z.center(), 0);
    }

    /** May a village miner open (or go down) a mine from here? Never in the town's ground; a way out always. */
    public static boolean mayRunFrom(AssistantEntity a, BlockPos at) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level)) return true;
        UUID village = f.ownerId();
        BlockPos heart = f.villageCentre();
        if (village == null || heart == null) return true;
        return !inTownGround(level, village, heart, at, 0) && !plotInTown(a);
    }

    // ------------------------------------------------------------------ the town's rounds

    /** Every half a minute (TownWork.tick): the old digs in the town filled in, the mine's portals and its path. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        UUID id = v.id();
        if (now - LAST.getOrDefault(id, -100000L) < EVERY && now >= LAST.getOrDefault(id, -100000L)) return;
        LAST.put(id, now);
        try {
            markOurStairs(level, v);                                     // the stairs its miners' plots are cut from: its own
            MineGround.ensure(level, v);                                 // an older world's mine: its ground set aside as it stands
            // [city26] Not while the founding is still working the ground: a path trodden from the town's edge over ground
            // yet to be cut left a column standing (the levelling leaves anything not the world's own), a pillar of earth
            // with the path on top in the middle of a street.
            if (!Founding.settling(level, v.centre(), 16)) {
                int laid = seal(level, v, FILL_A_LOOK);
                hollows(level, v, Math.max(16, FILL_A_LOOK * 2 - laid));
                portals(level, v);
                path(level, v, PATH_A_LOOK);
            }
            MineWorks.tick(level, v);                                    // [mine8] the headframe, the railings, the spoil heap
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[MCA-DIGS] {}: {}", Villages.name(id), e.toString());
        }
    }

    /** Is this staircase the town's own: cut by its folk, or (an older world's, unmarked) in its ground. */
    static boolean ours(ServerLevel level, UUID village, BlockPos heart, long key, BlockPos head) {
        UUID by = MineStairs.owner(level, key);
        if (by != null) return by.equals(village);
        return cutByOurMiner(village, key);
    }

    /**
     * An unmarked staircase (an older world's) is the town's only on positive evidence that a miner of the town cut it:
     * its key is the middle of a plot one of the town's folk holds now, or of a face of the town's mine. Otherwise
     * (a player's own digging, a player's helper's stairs, a way out, kept footholds) it is never the town's to fill.
     */
    static boolean cutByOurMiner(UUID village, long key) {
        if (MineStairs.kindOf(key) != MineStairs.Kind.MAIN) return false;
        int x = BlockPos.getX(key), z = BlockPos.getZ(key);
        for (AssistantEntity a : Villages.folkOf(village)) {
            WorkZone w = a.workZone();
            if (w != null && w.center().getX() == x && w.center().getZ() == z) return true;
        }
        BlockPos face = TownMine.faceCentre(village, new BlockPos(x, 0, z));
        return face != null && face.getX() == x && face.getZ() == z;
    }

    /** Each plot the town's folk hold now: the stairs cut from its middle marked as the town's (the evidence is kept,
     *  so the stairs stay the town's after the plot has moved on). */
    static void markOurStairs(ServerLevel level, Villages.Village v) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            WorkZone w = a.workZone();
            if (w != null) MineStairs.ownIfUnmarked(level, MineStairs.plotKey(w.center()), v.id());
        }
    }

    /**
     * The town's own stairs with their heads in its ground, filled in from the top down to where rock roofs them
     * over, the top turfed, the fence and sign round the head taken up; no more than so many blocks a look, and
     * never with anybody standing in them. Each staircase done is let go (MineStairs.forget). Blocks laid.
     */
    public static int seal(ServerLevel level, Villages.Village v, int most) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int laid = 0;
        for (long key : MineStairs.keys(level)) {
            if (laid >= most) break;
            List<BlockPos> steps = MineStairs.steps(level, key);
            if (steps.isEmpty()) continue;
            BlockPos head = steps.get(0);
            if (!ours(level, id, heart, key, head) || !inTownGround(level, id, heart, head, 0)) continue;
            if (!Land.areaLoaded(level, head, 4)) continue;
            if (inUse(level, id, head, steps)) continue;
            int[] got = fill(level, v, steps, most - laid);
            laid += got[0];
            if (got[1] == 0) break;                                        // not finished: the rest next look
            MineStairs.forget(level, key);
            hollowAt(id, head);                                           // the pits its galleries left round it, next
            Ledger.note(id, "digs.sealed", String.valueOf(sealed(id) + 1));
            Villages.tell(id, level.getDayTime() / 24000L, "the old mine stairs at " + head.getX() + ", " + head.getZ()
                + " were filled in and turfed over: they were in the town's ground");
            com.mojang.logging.LogUtils.getLogger().info("[MCA-DIGS] {}: sealed the old mine stairs at {}", Villages.name(id), head.toShortString());
        }
        return laid;
    }

    /** How far round a sealed staircase the pits its galleries and terraces left open are filled (a plot's half and one). */
    static final int HOLLOW_REACH = 9;
    /** The deepest a pit is filled from the top; under that it is bridged over (what is below is left a cave). */
    static final int HOLLOW_DEEP = 6;

    /** A sealed staircase's head, to fill the pits round it next (kept with the world). */
    static void hollowAt(UUID village, BlockPos head) {
        String s = Ledger.note(village, "digs.hollows");
        String add = head.getX() + "," + head.getY() + "," + head.getZ();
        Ledger.note(village, "digs.hollows", s == null || s.isEmpty() ? add : s + ";" + add);
    }

    /**
     * The pits left round the town's sealed stairs (its galleries and terraces, open to the sky, cut from the same plot):
     * each column within HOLLOW_REACH of the head lower than the ground at the head, filled up to it (no deeper than
     * HOLLOW_DEEP from the top: below that it is bridged over), the top turfed; only over the ground the world made, never
     * water, a building or anything somebody put down, never with anybody there. A head is let go once nothing round it is
     * left to fill. Blocks laid.
     */
    public static int hollows(ServerLevel level, Villages.Village v, int most) {
        UUID id = v.id();
        String s = Ledger.note(id, "digs.hollows");
        if (s == null || s.isEmpty()) return 0;
        List<String> left = new ArrayList<>(List.of(s.split(";")));
        int laid = 0;
        while (!left.isEmpty() && laid < most) {
            BlockPos head;
            try {
                String[] p = left.get(0).split(",");
                head = new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
            } catch (RuntimeException e) {
                left.remove(0);
                continue;
            }
            if (!Land.areaLoaded(level, head, HOLLOW_REACH + 1)) break;
            AABB box = new AABB(head.getX() - HOLLOW_REACH - 2, head.getY() - 24, head.getZ() - HOLLOW_REACH - 2,
                head.getX() + HOLLOW_REACH + 3, head.getY() + 4, head.getZ() + HOLLOW_REACH + 3);
            if (!level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive()).isEmpty()) break;   // somebody there: later
            int ground = head.getY() - 1;
            boolean more = false;
            for (int dx = -HOLLOW_REACH; dx <= HOLLOW_REACH && laid < most; dx++) {
                for (int dz = -HOLLOW_REACH; dz <= HOLLOW_REACH && laid < most; dz++) {
                    int x = head.getX() + dx, z = head.getZ() + dz;
                    if (MineGround.reserved(id, x, z)) continue;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    if (top >= ground) continue;
                    BlockState under = level.getBlockState(new BlockPos(x, top, z));
                    if (!MineStairs.ground(under) && !under.is(Blocks.GRASS_BLOCK) && !under.is(Blocks.DIRT_PATH)) continue;
                    if (!level.getFluidState(new BlockPos(x, top + 1, z)).isEmpty()) continue;
                    for (int y = Math.max(top + 1, ground - HOLLOW_DEEP + 1); y <= ground && laid < most; y++) {
                        BlockPos c = new BlockPos(x, y, z);
                        BlockState st = level.getBlockState(c);
                        if (!fillable(st) || !st.getFluidState().isEmpty()) continue;
                        if (!TownBlocks.mayPlace(level, v, c, TownBlocks.Why.TERRAFORM)) break;
                        level.setBlock(c, (y == ground ? Blocks.GRASS_BLOCK : y >= ground - 3 ? Blocks.DIRT : Blocks.COBBLESTONE).defaultBlockState(), 3);
                        laid++;
                    }
                    if (laid >= most) more = true;
                }
            }
            if (more) break;
            left.remove(0);
            Villages.tell(id, level.getDayTime() / 24000L, "the pits round the old mine stairs at " + head.getX() + ", " + head.getZ() + " were filled and turfed");
        }
        Ledger.note(id, "digs.hollows", String.join(";", left));
        return laid;
    }

    /** How many of its old stairs the town has filled in. */
    public static int sealed(UUID village) {
        String s = Ledger.note(village, "digs.sealed");
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Somebody down the stairs or in the galleries off them (sixteen blocks round the whole staircase), or a miner of the
     *  town still on a plot round them: left for now. */
    private static boolean inUse(ServerLevel level, UUID village, BlockPos head, List<BlockPos> steps) {
        int x0 = head.getX(), x1 = head.getX(), z0 = head.getZ(), z1 = head.getZ(), y0 = head.getY();
        for (BlockPos s : steps) {
            x0 = Math.min(x0, s.getX()); x1 = Math.max(x1, s.getX());
            z0 = Math.min(z0, s.getZ()); z1 = Math.max(z1, s.getZ());
            y0 = Math.min(y0, s.getY());
        }
        AABB box = new AABB(x0 - 16, y0 - 6, z0 - 16, x1 + 17, head.getY() + 4, z1 + 17);
        if (!level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && (e instanceof net.minecraft.world.entity.player.Player || e instanceof AssistantEntity)).isEmpty()) return true;
        AABB tight = new AABB(x0 - 1, y0 - 1, z0 - 1, x1 + 2, head.getY() + 3, z1 + 2);   // a beast in the stairs themselves
        if (!level.getEntitiesOfClass(LivingEntity.class, tight, e -> e.isAlive()).isEmpty()) return true;
        for (AssistantEntity a : Villages.folkOf(village)) {
            WorkZone z = a.workZone();
            if (a.stationTask() == AssistantEntity.StationTask.MINE && z != null && z.containsColumn(head)) return true;
        }
        return false;
    }

    /** May this cell of a staircase be filled: open, a plant, a torch, a ladder, a prop or water. */
    private static boolean fillable(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LADDER)
            || s.is(BlockTags.WOODEN_FENCES) || s.is(Blocks.WATER) || s.is(Blocks.COBWEB);
    }

    /**
     * Fill a staircase from its head down: each step's column from the step up to the ground at the head, till
     * a step with rock over it (two blocks and more of the ground the world made), and two steps past it. The
     * top of each column turfed. Returns {blocks laid, 1 when it is done}.
     */
    static int[] fill(ServerLevel level, Villages.Village v, List<BlockPos> steps, int most) {
        BlockPos head = steps.get(0);
        int ground = head.getY();                                          // the head's feet: the turf goes just under
        int laid = 0;
        // The fence and the sign round the head (MineSafety), and the portal's timbers, taken up first.
        for (int i = 0; i < Math.min(3, steps.size()); i++) {
            BlockPos s = steps.get(i);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 3; dy++) {
                BlockPos c = new BlockPos(s.getX() + dx, ground + dy, s.getZ() + dz);
                BlockState st = level.getBlockState(c);
                if ((st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.ALL_SIGNS) || st.is(Blocks.LANTERN) || portalTimber(level, c, st))
                        && TownBlocks.mayPlace(level, v, c, TownBlocks.Why.TERRAFORM)) {
                    level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        int roofed = -1;
        for (int i = 0; i < steps.size(); i++) {
            BlockPos s = steps.get(i);
            if (roofed >= 0 && i > roofed + 2) return new int[]{ laid, 1 };
            if (s.getY() < ground && roofed < 0) {
                int rock = 0;
                for (int y = s.getY() + 3; y < ground; y++) if (MineStairs.ground(level.getBlockState(new BlockPos(s.getX(), y, s.getZ())))) rock++;
                if (rock >= 2) roofed = i;
            }
            for (int y = s.getY(); y < ground; y++) {
                BlockPos c = new BlockPos(s.getX(), y, s.getZ());
                BlockState st = level.getBlockState(c);
                if (!fillable(st)) continue;
                if (!TownBlocks.mayPlace(level, v, c, TownBlocks.Why.TERRAFORM)) continue;
                if (laid >= most) return new int[]{ laid, 0 };
                boolean top = y == ground - 1;
                level.setBlock(c, (top ? Blocks.GRASS_BLOCK : y >= ground - 4 ? Blocks.DIRT : Blocks.COBBLESTONE).defaultBlockState(), 3);
                laid++;
            }
            // The step's head room over the ground (a step cut into a knoll above the head): opened back up is not
            // wanted, it is only left as it is.
        }
        return new int[]{ laid, 1 };
    }

    /** A timber of a mine portal (TownDigs.portal): a log standing or lying round a stair head. */
    private static boolean portalTimber(ServerLevel level, BlockPos c, BlockState st) {
        return st.is(Blocks.STRIPPED_SPRUCE_LOG) || st.is(Blocks.SPRUCE_FENCE);
    }

    // ------------------------------------------------------------------ the mine's look

    /**
     * Each staircase of the town's mine with a portal over its head: two posts of stripped spruce either side of
     * the first step, a beam across them, a lantern hung under it. Only on open ground the world made (never over
     * anything somebody put there), and only once (a portal standing is left alone). Portals put up.
     */
    public static int portals(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        if (TownMine.siteOf(id) == null) return 0;
        int put = 0;
        for (long key : MineStairs.keys(level)) {
            if (MineStairs.kindOf(key) != MineStairs.Kind.MAIN) continue;
            List<BlockPos> steps = MineStairs.steps(level, key);
            if (steps.size() < 2) continue;
            BlockPos head = steps.get(0);
            if (!MineGround.reserved(id, head.getX(), head.getZ()) || !ours(level, id, heart, key, head)) continue;
            if (!Land.areaLoaded(level, head, 3)) continue;
            if (portal(level, v, steps)) put++;
        }
        return put;
    }

    /** One portal over this staircase's head. Whether one was put up now. */
    static boolean portal(ServerLevel level, @Nullable Villages.Village v, List<BlockPos> steps) {
        BlockPos head = steps.get(0), next = steps.get(1);
        int dx = Integer.signum(next.getX() - head.getX()), dz = Integer.signum(next.getZ() - head.getZ());
        if (dx != 0 && dz != 0) dz = 0;
        if (dx == 0 && dz == 0) return false;
        Direction down = Direction.fromDelta(dx, 0, dz);
        if (down == null) return false;
        Direction side = down.getClockWise();
        BlockPos left = head.relative(side), right = head.relative(side.getOpposite());
        if (level.getBlockState(left).is(Blocks.STRIPPED_SPRUCE_LOG) && level.getBlockState(right).is(Blocks.STRIPPED_SPRUCE_LOG)) return false;
        // Both posts on firm ground, open to the sky, and the head's own room clear over it.
        for (BlockPos post : List.of(left, right)) {
            if (!level.getBlockState(post.below()).isSolid()) return false;
            for (int h = 0; h <= 3; h++) {
                BlockState st = level.getBlockState(post.above(h));
                if (!(st.isAir() || st.canBeReplaced() || st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.LEAVES)
                    || h <= 2 && MineStairs.ground(st))) return false;               // a post may stand in the cutting's side
                if (v != null && !TownBlocks.mayPlace(level, v, post.above(h), TownBlocks.Why.MINE)) return false;
            }
        }
        for (int h = 0; h <= 3; h++) {
            BlockState st = level.getBlockState(head.above(h));
            if (!(st.isAir() || st.canBeReplaced() || h >= 2 && st.is(BlockTags.LEAVES))) return false;
        }
        BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState beam = post.setValue(RotatedPillarBlock.AXIS, side.getAxis());
        for (int h = 0; h <= 2; h++) {
            level.setBlock(left.above(h), post, 3);
            level.setBlock(right.above(h), post, 3);
        }
        level.setBlock(left.above(3), beam, 3);
        level.setBlock(right.above(3), beam, 3);
        level.setBlock(head.above(3), beam, 3);
        level.setBlock(head.above(2), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 3);
        return true;
    }

    /**
     * A path trodden from the town's edge out to its mine: the grass along the way to the mine's first face turned
     * to a path two blocks wide, no more than so many blocks a look; never over a field, water, or anything somebody
     * put down. Blocks trodden.
     */
    public static int path(ServerLevel level, Villages.Village v, int most) {
        UUID id = v.id();
        BlockPos site = TownMine.siteOf(id);
        if (site == null) return 0;
        BlockPos heart = v.centre();
        double far = Math.sqrt(site.atY(0).distSqr(heart.atY(0)));
        if (far < 1) return 0;
        double ux = (site.getX() - heart.getX()) / far, uz = (site.getZ() - heart.getZ()) / far;
        int from = Villages.townReach(id) + 1;
        int x0 = heart.getX() + (int) Math.round(ux * from), z0 = heart.getZ() + (int) Math.round(uz * from);
        int done = 0;
        boolean alongX = Math.abs(ux) >= Math.abs(uz);
        for (int[] c : Roads.bresenham(x0, z0, site.getX(), site.getZ())) {
            if (done >= most) break;
            for (int w = 0; w <= 1 && done < most; w++) {
                int x = c[0] + (alongX ? 0 : w), z = c[1] + (alongX ? w : 0);
                if (!level.hasChunk(x >> 4, z >> 4)) return done;
                if (Villages.onFarmland(id, heart, new BlockPos(x, 0, z), 0)) continue;
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below();
                BlockState st = level.getBlockState(top);
                if (!st.is(Blocks.GRASS_BLOCK) && !st.is(Blocks.DIRT) && !st.is(Blocks.COARSE_DIRT) && !st.is(Blocks.PODZOL)) continue;
                if (MineStairs.isFloor(level, top)) continue;
                BlockState above = level.getBlockState(top.above());
                if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) continue;
                if (!TownBlocks.mayPlace(level, v, top, TownBlocks.Why.ROAD)) continue;
                if (!above.isAir()) level.setBlock(top.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(top, Blocks.DIRT_PATH.defaultBlockState(), 3);
                done++;
            }
        }
        return done;
    }
}
