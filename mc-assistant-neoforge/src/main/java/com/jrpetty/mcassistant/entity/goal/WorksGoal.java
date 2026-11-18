package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.MineStairs;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [mine8] A miner's shift in the town's works (MineWorks): down the main shaft to its level, and there the pieces of work
 * the works give it one after another (the shaft sunk a side at a time, a landing cut, a drift driven on, a branch cut),
 * each block broken as a player breaks it, every ore that shows in the walls followed out to the end of its vein, water
 * and lava stopped with cobblestone; then back up the shaft when its pack is full, its pick is done, the day is or it has
 * worked its stint. A mine job "out" taken by a folk down in the works walks it up the same way.
 */
public class WorksGoal extends Goal {

    private enum Phase { NEXT, WALK, CUT, OUT }

    private final AssistantEntity a;
    @Nullable private Job job;
    private int myGen;
    private Phase phase = Phase.NEXT;
    @Nullable private MineWorks.Task task;
    private List<BlockPos> route = List.of();
    private int ri;
    private int ci;
    private final ArrayDeque<BlockPos> blocks = new ArrayDeque<>();
    private final ArrayDeque<BlockPos> vein = new ArrayDeque<>();
    private final Set<Long> veinSeen = new HashSet<>();
    @Nullable private BlockPos digging;
    private int workTicks, workNeeded;
    @Nullable private BlockPos walking;
    private double best;
    private int stuck, tasks, shiftTicks, oreTaken, broke, teleports;
    private boolean outJob;
    private String bye = "";
    private boolean saidSealed;

    /** Ticks a shift may run, and the pieces of work it may take on. */
    private static final int SHIFT_TICKS = 12000, SHIFT_TASKS = 10;
    /** How far it reaches from where it stands. */
    private static final double REACH = 4.6;
    /** How many blocks of one vein it follows. */
    private static final int MOST_VEIN = 32;

    public WorksGoal(AssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** A shift in the works, or a way out for a folk down in them. */
    public static boolean ours(AssistantEntity a, @Nullable Job j) {
        if (j == null || j.type() != Job.Type.MINE) return false;
        if (MineWorks.ARG.equals(j.arg())) return true;
        return MineStairs.OUT.equals(j.arg()) && MineWorks.below(a) && a.level() instanceof ServerLevel sl && MineWorks.wayUp(sl, a) != null;
    }

    @Override
    public boolean canUse() {
        return a.getTarget() == null && ours(a, a.peekJob());
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && a.getTarget() == null && a.taskGen() == myGen && a.peekJob() == job;
    }

    @Override
    public void start() {
        job = a.peekJob();
        myGen = a.taskGen();
        outJob = job != null && MineStairs.OUT.equals(job.arg());
        task = null;
        digging = null;
        blocks.clear();
        vein.clear();
        veinSeen.clear();
        // (where it was walking kept: a restart does not forget a walk that is stuck)
        tasks = 0;
        shiftTicks = 0;
        oreTaken = 0;
        broke = 0;
        saidSealed = false;
        resumeAt = 0;
        teleports = 0;
        if (!(a.level() instanceof ServerLevel)) { finish(""); return; }
        if (outJob) { beginOut("Out of the works."); return; }
        a.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) a.topUpKit();
        a.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) {
            if (MineWorks.below(a)) { beginOut("Out — I need a pickaxe before I dig any more."); return; }
            finish("I need a pickaxe before I can work the mine.");
            return;
        }
        phase = Phase.NEXT;
    }

    @Override
    public void stop() {
        if (task != null && a instanceof VillageFolkEntity f) MineWorks.release(f.ownerId(), task, a.getUUID());
        task = null;
        job = null;
        digging = null;
        // (where it was walking kept: a restart does not forget a walk that is stuck)
        a.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(a.level() instanceof ServerLevel level) || job == null) return;
        shiftTicks++;
        if (shiftTicks % 20 == 0) STATE.put(a.getUUID(), phase + " " + (task == null ? "-" : task.kind + " cell " + ci + "/" + task.cells.size())
            + " blocks " + blocks.size() + " vein " + vein.size() + " walking " + (walking == null ? "-" : walking.toShortString())
            + " digging " + (digging == null ? "-" : digging.toShortString()) + " route " + ri + "/" + route.size());
        switch (phase) {
            case NEXT -> next(level);
            case WALK -> {
                if (follow()) {
                    phase = Phase.CUT;
                    ci = resumeAt;
                    resumeAt = 0;
                    blocks.clear();
                    if (!prepare(level)) return;
                }
            }
            case CUT -> cut(level);
            case OUT -> {
                if (follow()) finish(bye);
                else if (++outTicks > 3000) finish(bye);    // no way up it can walk: the town's rescues see to it
            }
        }
    }

    // ------------------------------------------------------------------ the shift

    private void next(ServerLevel level) {
        String over = over(level);
        if (over != null) { beginOut(over); return; }
        if (!(a instanceof VillageFolkEntity f)) { finish(""); return; }
        task = MineWorks.next(level, f);
        if (task == null) {
            MineWorks.rest(a, level.getGameTime());
            if (MineWorks.below(a)) beginOut("Nothing more for me to cut down there just now.");
            else finish(tasks == 0 ? "Nothing free to cut in the works just now." : "That's my stint at the works.");
            return;
        }
        route = MineWorks.route(level, a, task);
        ri = 0;
        walking = null;
        phase = Phase.WALK;
    }

    /** Why the shift is over, or null: a full pack, no pick, evening, its stint worked, hurt. */
    @Nullable
    private String over(ServerLevel level) {
        if (a.isPackFull() || freeSlots() <= 1) return "Pack's full — up to bank it.";
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) {
            a.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) return "My pick's done — up for another.";
        }
        long t = level.getDayTime() % 24000L;
        if (t >= 12000L && t < 23500L) return "That's the day's shift done.";
        if (tasks >= SHIFT_TASKS || shiftTicks > SHIFT_TICKS) return "That's my stint at the works.";
        if (a.getHealth() < a.getMaxHealth() * 0.5F) return "Up out of it — I'm hurt.";
        return null;
    }

    private int freeSlots() {
        int n = 0;
        for (ItemStack s : a.getInventoryItems()) if (s.isEmpty()) n++;
        return n;
    }

    private void beginOut(String why) {
        bye = why;
        if (task != null) { report(why.isEmpty() ? "" : "stopped"); }
        List<BlockPos> up = a.level() instanceof ServerLevel sl ? MineWorks.wayUp(sl, a) : null;
        route = up == null ? List.of() : up;
        ri = 0;
        if (phase != Phase.OUT) outTicks = 0;
        // (where it was walking kept: a restart does not forget a walk that is stuck)
        phase = Phase.OUT;
    }

    private void finish(String message) {
        MineWorks.event(a, "shift over: " + message + " (" + tasks + " pieces, " + broke + " blocks, " + teleports + " hops)");
        if (!message.isEmpty()) a.sayRoutine(message);
        if (!outJob) a.noteJobOutcome(oreTaken > 0 || broke > 8);
        a.pollJob();
        outTicks = 0;
        phase = Phase.NEXT;
        job = null;
        task = null;
        a.getNavigation().stop();
    }

    // ------------------------------------------------------------------ walking

    /**
     * One tick along the route: to the furthest waypoint ahead it can make for in a straight line along a tunnel. Stuck
     * (no headway for four seconds) it is put on the waypoint, the way being cut and open. True when it is at the end.
     */
    private boolean follow() {
        if (ri >= route.size()) return true;
        int j = ri;
        BlockPos p0 = route.get(ri);
        while (j + 1 < route.size() && j - ri < 6) {
            BlockPos q = route.get(j + 1);
            if (q.getY() != p0.getY() || (q.getX() != p0.getX() && q.getZ() != p0.getZ())) break;
            j++;
        }
        BlockPos dest = route.get(j);
        if (at(dest)) {
            ri = j + 1;
            walking = null;
            return ri >= route.size();
        }
        if (walk(dest)) { ri = j + 1; walking = null; }
        return ri >= route.size();
    }

    private boolean at(BlockPos p) {
        double dx = a.getX() - (p.getX() + 0.5), dz = a.getZ() - (p.getZ() + 0.5);
        return dx * dx + dz * dz < 0.7 && Math.abs(a.getY() - p.getY()) < 1.1;
    }

    /** Make for this spot; true once there (or put there when stuck). */
    private boolean walk(BlockPos dest) {
        if (at(dest)) return true;
        double d = a.distanceToSqr(Vec3.atBottomCenterOf(dest));
        boolean far = d > 256;
        if (!dest.equals(walking)) {
            walking = dest;
            best = d;
            stuck = 0;
            go(dest, far);
        } else if (a.getNavigation().isDone()) {
            go(dest, far);
        }
        if (d < best - 0.2) { best = d; stuck = 0; } else stuck++;
        // A long way off (on the surface, sent off somewhere): its long walk, given its time; then the shift is over.
        if (far) {
            if (stuck > 600) {
                stuck = 0;
                walking = null;
                if (task != null && a instanceof VillageFolkEntity f) MineWorks.release(f.ownerId(), task, a.getUUID());
                task = null;
                finish("Can't find my way to the pithead.");
            }
            return false;
        }
        if (stuck > 80) {
            stuck = 0;
            if (open(dest)) {
                a.getNavigation().stop();
                a.teleportTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
                teleports++;
                TELEPORTS++;
            }
            return true;
        }
        return false;
    }

    private void go(BlockPos dest, boolean far) {
        if (far) a.walkTo(dest, 1.1D);
        else a.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0D);
    }

    private boolean open(BlockPos p) {
        return a.level().getBlockState(p).getCollisionShape(a.level(), p).isEmpty()
            && a.level().getBlockState(p.above()).getCollisionShape(a.level(), p.above()).isEmpty();
    }

    // ------------------------------------------------------------------ cutting

    /** The next cell readied: what is ahead looked at, the blocks to break listed, top first. False when the task ended. */
    private boolean prepare(ServerLevel level) {
        MineWorks.Task t = task;
        if (t == null) return false;
        if (ci >= t.cells.size()) { report(""); return false; }
        BlockPos cell = t.cells.get(ci);
        String why = MineWorks.ahead(level, t, cell);
        if (why != null) {
            if (t.kind == MineWorks.Kind.BRANCH && "tunnel".equals(why)) why = "tunnel";
            report(why);
            return false;
        }
        blocks.clear();
        for (int h = t.height - 1; h >= 0; h--) {
            BlockPos p = cell.above(h);
            // In the shaft and the landing water and lava are stopped where they stand and cut out with the rock.
            if (!level.getFluidState(p).isEmpty()) seal(p, level.getFluidState(p).is(FluidTags.LAVA));
            blocks.add(p);
        }
        return true;
    }

    private void cut(ServerLevel level) {
        MineWorks.Task t = task;
        if (t == null) { phase = Phase.NEXT; return; }
        if (digging != null) { digTick(level); return; }
        // Not where it stands to work: there first.
        BlockPos stand = t.stand.get(Math.min(ci, t.stand.size() - 1));
        if (!vein.isEmpty()) { followVein(level, t); return; }
        // Sent off somewhere (a rescue, a fright): back by the way down first.
        if (a.distanceToSqr(Vec3.atBottomCenterOf(stand)) > 256 && a.level() instanceof ServerLevel sl0) {
            route = MineWorks.route(sl0, a, t);
            route = new ArrayList<>(route);
            route.add(stand);
            ri = 0;
            walking = null;
            vein.clear();
            resumeAt = ci;
            phase = Phase.WALK;
            return;
        }
        // Where it stands to work, unless what is next to break is in its reach from where it is.
        BlockPos nextBlock = blocks.peek();
        boolean inReach = nextBlock != null && a.getEyePosition().distanceTo(Vec3.atCenterOf(nextBlock)) <= REACH;
        if (!inReach && !at(stand) && a.distanceToSqr(Vec3.atBottomCenterOf(stand)) > 2.2) {
            if (!walk(stand)) return;
        }
        while (!blocks.isEmpty()) {
            BlockPos p = blocks.peek();
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) { blocks.poll(); continue; }
            if (!s.getFluidState().isEmpty() && s.canBeReplaced()) { seal(p, s.getFluidState().is(FluidTags.LAVA)); return; }
            UVillage v = village();
            if (!MineWorks.mayCut(level, v.id, p, s)) { blocks.clear(); report("made"); return; }
            a.equipBestTool(s);
            digging = p;
            workTicks = 0;
            workNeeded = com.jrpetty.mcassistant.entity.WorkTools.propPace(a, a.workTicksFor(s));
            return;
        }
        // The cell is open: its floor made good, dressed, and on to the next.
        BlockPos cell = t.cells.get(ci);
        BlockPos floor = cell.below();
        BlockState fs = level.getBlockState(floor);
        if (!fs.isFaceSturdy(level, floor, Direction.UP) && !(fs.getBlock() instanceof net.minecraft.world.level.block.StairBlock)) {
            if (!fs.getFluidState().isEmpty() || fs.canBeReplaced()) {
                // A drift stops at a hole it cannot bridge; the shaft and the landing go on (the ground falling away on a
                // hillside under a step), the step's floor laid when the miner has the stone for it.
                if (!seal(floor, fs.getFluidState().is(FluidTags.LAVA)) && t.kind != MineWorks.Kind.SHAFT && t.kind != MineWorks.Kind.ROOM) { report("hole"); return; }
            }
        }
        t.cut = ci + 1;
        MineWorks.dress(level, a, t, ci);
        ci++;
        if (ci >= t.cells.size()) { report(""); return; }
        prepare(level);
    }

    private record UVillage(@Nullable java.util.UUID id) {}

    private UVillage village() {
        return new UVillage(a instanceof VillageFolkEntity f ? f.ownerId() : null);
    }

    private void digTick(ServerLevel level) {
        BlockPos pos = digging;
        if (pos == null) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) { digging = null; return; }
        a.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (++workTicks < workNeeded) {
            if (workTicks % 8 == 0) {
                a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                a.workHit(pos);
            }
            return;
        }
        digging = null;
        boolean ore = com.jrpetty.mcassistant.entity.OreSight.isOre(state);
        if (!level.destroyBlock(pos, true, a)) return;
        broke++;
        BROKEN++;
        if (task != null) task.broke++;
        a.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
        if (ore) {
            oreTaken++;
            if (task != null) task.ore++;
            com.jrpetty.mcassistant.entity.OreNotes.taken(level, pos);
            a.note(AssistantEntity.Deed.ORE_FOUND, 1);
            a.noteRichSpot(pos);
            if (a instanceof VillageFolkEntity folk) com.jrpetty.mcassistant.entity.FolkSkills.oreLuck(folk, state, pos);
        }
        a.damageHeldTool();
        // What the break opened onto: water and lava stopped, the fall of gravel over it taken down, ore in the walls followed.
        boolean openLava = false;
        for (Direction d : Direction.values()) {
            BlockPos side = pos.relative(d);
            net.minecraft.world.level.material.FluidState fl = level.getFluidState(side);
            if (fl.isEmpty()) continue;
            boolean lava = fl.is(FluidTags.LAVA);
            if (seal(side, lava)) {
                SEALED++;
                if (!saidSealed) { saidSealed = true; a.sayRoutine(lava ? "Lava behind the rock — stopped it with cobble." : "Water coming in — walled it off."); }
            } else if (lava) openLava = true;
        }
        sweep(pos);
        if (openLava) { beginOut("Lava, and nothing to stop it with — out of here."); return; }
        BlockPos above = pos.above();
        for (int g = 0; g < 6 && level.getBlockState(above).getBlock() instanceof FallingBlock; g++) {
            blocks.addFirst(above);
            above = above.above();
        }
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (veinSeen.contains(n.asLong())) continue;
            BlockState ns = level.getBlockState(n);
            if (com.jrpetty.mcassistant.entity.OreSight.isOre(ns) && !inTask(n)) traceVein(level, n);
        }
    }

    private boolean inTask(BlockPos p) {
        if (task == null) return false;
        for (BlockPos c : task.cells) {
            if (c.getX() == p.getX() && c.getZ() == p.getZ() && p.getY() >= c.getY() && p.getY() < c.getY() + task.height) return true;
        }
        return false;
    }

    /** The whole vein this ore is part of (touching, corners too), as far as it runs in the mine's ground. */
    private void traceVein(ServerLevel level, BlockPos start) {
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        veinSeen.add(start.asLong());
        java.util.UUID v = village().id;
        int n = 0;
        while (!q.isEmpty() && n < MOST_VEIN) {
            BlockPos p = q.poll();
            BlockState s = level.getBlockState(p);
            if (!MineWorks.mayCut(level, v, p, s)) continue;
            vein.add(p);
            n++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos o = p.offset(dx, dy, dz);
                if (veinSeen.contains(o.asLong())) continue;
                if (com.jrpetty.mcassistant.entity.OreSight.isOre(level.getBlockState(o))) {
                    veinSeen.add(o.asLong());
                    q.add(o);
                }
            }
        }
    }

    /** The next block of the vein: broken from where it stands if in reach and bare, else from the nearest spot of its
     *  cut tunnel that reaches it, else left for another day. */
    private void followVein(ServerLevel level, MineWorks.Task t) {
        BlockPos v = vein.peek();
        BlockState s = level.getBlockState(v);
        if (!com.jrpetty.mcassistant.entity.OreSight.isOre(s)) { vein.poll(); return; }
        Vec3 eye = a.getEyePosition();
        if (bare(level, v) && eye.distanceTo(Vec3.atCenterOf(v)) <= REACH) {
            vein.poll();
            a.equipBestTool(s);
            if (!a.getMainHandItem().isCorrectToolForDrops(s)) { LEFT++; return; }   // not for this pick
            digging = v;
            workTicks = 0;
            workNeeded = com.jrpetty.mcassistant.entity.WorkTools.propPace(a, a.workTicksFor(s));
            return;
        }
        // A spot of the tunnel cut so far that reaches it, or an ore hole a folk can stand in.
        BlockPos spot = null;
        double bestD = Double.MAX_VALUE;
        List<BlockPos> spots = new ArrayList<>(t.stand.subList(0, Math.min(ci + 1, t.stand.size())));
        for (int i = 0; i < ci && i < t.cells.size(); i++) spots.add(t.cells.get(i));
        for (BlockPos p : spots) {
            double d = Vec3.atBottomCenterOf(p).add(0, 1.6, 0).distanceTo(Vec3.atCenterOf(v));
            if (d <= REACH - 0.6 && d < bestD && open(p)) { bestD = d; spot = p; }
        }
        if (spot == null || !bare(level, v)) {
            vein.poll();
            LEFT++;                                          // buried behind ore it could not get at, or out of reach
            return;
        }
        // At the spot already and still out of reach (or a walk that went nowhere): left for another day.
        if (at(spot) || ++veinWalks > 200) {
            vein.poll();
            veinWalks = 0;
            LEFT++;
            return;
        }
        if (walk(spot)) veinWalks = 0;
    }

    private int veinWalks;
    private int outTicks;
    /** The cell it goes back to after a walk back by the way down. */
    private int resumeAt;

    private boolean bare(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(p.relative(d));
            if (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** Water or lava stopped here with a block from its pack (cobblestone first; for lava nothing that burns or falls). */
    private boolean seal(BlockPos pos, boolean lava) {
        var inv = a.getInventoryItems();
        int bestI = -1, bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!BuildGoal.isBuildingBlock(s)) continue;
            BlockState st = ((net.minecraft.world.item.BlockItem) s.getItem()).getBlock().defaultBlockState();
            if (st.getBlock() instanceof FallingBlock) continue;
            if (lava && st.ignitedByLava()) continue;
            int rank = s.is(Items.COBBLESTONE) ? 0 : s.is(Items.COBBLED_DEEPSLATE) ? 1 : 2;
            if (rank < bestRank) { bestRank = rank; bestI = i; }
        }
        if (bestI < 0) return false;
        ItemStack s = inv.get(bestI);
        BlockState st = ((net.minecraft.world.item.BlockItem) s.getItem()).getBlock().defaultBlockState();
        s.shrink(1);
        if (s.isEmpty()) inv.set(bestI, ItemStack.EMPTY);
        a.level().setBlockAndUpdate(pos, st);
        a.placeSound(pos);
        return true;
    }

    private void sweep(BlockPos around) {
        for (ItemEntity drop : a.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(2.5))) {
            ItemStack picked = drop.getItem().copy();
            ItemStack leftover = a.insertItem(drop.getItem());
            com.jrpetty.mcassistant.entity.Economy.gathered(a, picked, picked.getCount() - leftover.getCount());
            if (leftover.isEmpty()) drop.discard(); else drop.setItem(leftover);
        }
    }

    /** The task over (why it stopped short, or ""): told to the works, and on to the next. */
    private void report(String why) {
        MineWorks.Task t = task;
        task = null;
        blocks.clear();
        digging = null;
        if (t == null) return;
        t.why = why == null ? "" : why;
        if (a instanceof VillageFolkEntity f && a.level() instanceof ServerLevel level) MineWorks.done(level, f, t);
        tasks++;
        if (phase != Phase.OUT) phase = Phase.NEXT;
    }

    // ------------------------------------------------------------------ for the tests

    public static volatile int TELEPORTS, BROKEN, SEALED, LEFT;
    private static final java.util.Map<java.util.UUID, String> STATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests: what this miner's shift is at. */
    public static String stateForTests(AssistantEntity a) {
        return STATE.getOrDefault(a.getUUID(), "-");
    }

    /** Tests: the counts kept since the last reset. */
    public static int[] countsForTests() {
        return new int[]{ BROKEN, SEALED, LEFT, TELEPORTS };
    }

    public static void resetCountsForTests() {
        TELEPORTS = 0;
        BROKEN = 0;
        SEALED = 0;
        LEFT = 0;
    }
}
