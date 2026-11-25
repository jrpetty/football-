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

    private enum Phase { NEXT, WALK, CUT, OUT, BREAK }

    private final AssistantEntity a;
    @Nullable private Job job;
    private int myGen;
    private Phase phase = Phase.NEXT;
    @Nullable private MineWorks.Task task;
    private List<BlockPos> route = List.of();
    private int ri;
    /** [econ32c] The walk by the stair, kept for when the cage is busy; and each ride in the walk: {from stop, to stop, the
     *  from stop's index in the stair walk, the to stop's}, by the index in the walk of the stop it rides to. */
    private List<BlockPos> stairs = List.of();
    private final java.util.Map<Integer, int[]> rides = new java.util.HashMap<>();
    private long rideAsked;
    private int rideAsks;
    /** [econ32c] Ticks a folk waits at a stop for the cage before it takes the stair. */
    private static final long LIFT_WAIT = 600L;
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

    /** Ticks a shift may run, and the pieces of work it may take on. [econ32] Thirty pieces, not ten: a piece is a few
     *  blocks of a drift, and a local run's Iron Age town's miners cut twenty-three to twenty-seven blocks a shift of three to
     *  four minutes, then walked the whole stair up (a hundred and forty blocks down to the deep level) and down again for
     *  the next; its iron level brought in one to twenty-eight iron a day with ten miners. The half day and a full pack
     *  still end a shift. */
    private static final int SHIFT_TICKS = 12000, SHIFT_TASKS = 30;
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
        breaks = 0;                                              // [econ32c]
        breakAt = null;
        oreTaken = 0;
        broke = 0;
        saidSealed = false;
        fluidAhead = null;
        resumeAt = 0;
        teleports = 0;
        if (!(a.level() instanceof ServerLevel)) { finish(""); return; }
        if (outJob) { beginOut("Out of the works."); return; }
        // [fixes14] Up top, setting out: a worn pick changed for a fresh one off the rack (a spare taken along).
        if (!MineWorks.below(a) && a instanceof VillageFolkEntity vf && a.level() instanceof ServerLevel sl) com.jrpetty.mcassistant.entity.Picks.beforeShift(sl, vf);
        a.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) a.topUpKit();
        a.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        // [fixes14] Still none: one sent down off the rack (made at the stores if the rack is empty).
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                && a instanceof VillageFolkEntity vf2 && a.level() instanceof ServerLevel sl2) com.jrpetty.mcassistant.entity.Picks.sendDown(sl2, vf2);
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) {
            if (MineWorks.below(a)) { beginOut("Out — I need a pickaxe before I dig any more."); return; }
            finish("I need a pickaxe before I can work the mine.");
            return;
        }
        phase = Phase.NEXT;
    }

    @Override
    public void stop() {
        com.jrpetty.mcassistant.entity.MineLift.cancel(a.getUUID());   // [econ32c] no longer waiting at a stop (a ride under way is finished)
        if (job != null && phase == Phase.OUT && MineWorks.below(a)) {   // [econ32b] a climb cut short below, and by what
            com.jrpetty.mcassistant.entity.Job nj = a.peekJob();
            com.jrpetty.mcassistant.entity.MineWhy.note(a, "climb cut short (" + (a.getTarget() != null ? "a target" : nj == null ? "no job"
                : nj == job ? "gen " + a.taskGen() + "/" + myGen : nj.type() + ":" + nj.arg()) + ")");
        }
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
        {                                                        // [econ32c] where the shift's time goes
            com.jrpetty.mcassistant.entity.MineLift.State ls = com.jrpetty.mcassistant.entity.MineLift.state(a.getUUID());
            if (ls == com.jrpetty.mcassistant.entity.MineLift.State.RIDING || ls == com.jrpetty.mcassistant.entity.MineLift.State.WAITING) T_CAGE++;
            else if (phase == Phase.CUT) T_FACE++;
            else if (phase == Phase.OUT) T_OUT++;
            else T_WALK++;
        }
        if (shiftTicks % 20 == 0) STATE.put(a.getUUID(), phase + " " + (task == null ? "-" : task.kind + " cell " + ci + "/" + task.cells.size())
            + " blocks " + blocks.size() + " vein " + vein.size() + " walking " + (walking == null ? "-" : walking.toShortString())
            + " digging " + (digging == null ? "-" : digging.toShortString()) + " route " + ri + "/" + route.size());
        if (shiftTicks % 1200 == 0) com.jrpetty.mcassistant.entity.MineWhy.note(a, "on a shift: " + STATE.get(a.getUUID())
            + " stuck " + stuck + " out " + outTicks + " tasks " + tasks + " broke " + broke);   // [econ32b] minewhy only
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
            case BREAK -> breather(level);                       // [econ32c] at the landing a while
            case OUT -> {
                long began = CLIMBING.getOrDefault(a.getUUID(), level.getGameTime());
                if (follow()) {
                    // At the end of its way up and still below (the way cut off, a step gone): after a while of trying, at the top.
                    // [econ32b] Whenever it ends there: a stuck walk hops on along the way whether or not the hop was made, and a
                    // climb that "ended" short of the top left it below with no job, sent climbing again by the next look.
                    if (MineWorks.below(a)) putAtTheTop(level);
                    if (!MineWorks.below(a)) CLIMBING.remove(a.getUUID());
                    finish(bye);
                }
                else if (++outTicks > 3000 || level.getGameTime() - began > 3000) {
                    CLIMBING.remove(a.getUUID());
                    // [econ32b] The climb out of a long drift and up the stair ran out of time on the way (stuck at a lip, a
                    // turn of the stair): it is put at the top, as the hops along the way would have got it there. Left
                    // below, its leash walked it for the pithead a hundred blocks through the rock, "lost underground" a
                    // quarter of a minute later, and the climb began again: thirty-seven to a hundred and nineteen climbs a
                    // day in a town of ten miners.
                    putAtTheTop(level);
                    finish(bye);
                }
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
        setRoute(level, MineWorks.route(level, a, task));        // [econ32c] the cage where it saves the stair
        walking = null;
        phase = Phase.WALK;
    }

    /** Why the shift is over, or null: a full pack, no pick, evening, its stint worked, hurt. */
    @Nullable
    private String over(ServerLevel level) {
        if (a.isPackFull() || freeSlots() <= 1) return "Pack's full — up to bank it.";
        if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) {
            a.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            // [fixes14] A fresh one sent down, and the shift goes on.
            if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                    && a instanceof VillageFolkEntity vf && com.jrpetty.mcassistant.entity.Picks.sendDown(level, vf)) return null;
            if (!a.getMainHandItem().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) return "My pick's done — up for another.";
        }
        long t = level.getDayTime() % 24000L;
        if (t >= 12000L && t < 23500L) return "That's the day's shift done.";
        if (tasks >= SHIFT_TASKS || shiftTicks > SHIFT_TICKS) {
            // [econ32c] With the cage and a cache at the landing: a breather there and another stint, not the climb home.
            if (breaks < BREAKS && t < 10000L && a.getHealth() >= a.getMaxHealth() * 0.75F && freeSlots() > 6 && beginBreak(level)) return null;
            return "That's my stint at the works.";
        }
        if (a.getHealth() < a.getMaxHealth() * 0.5F) return "Up out of it — I'm hurt.";
        return null;
    }

    /** [econ32c] Breathers a shift at the landing's cache, and how long each. */
    private static final int BREAKS = 2, BREAK_TICKS = 200;
    private int breaks, breakTicks;
    @Nullable private com.jrpetty.mcassistant.entity.MineLift.Stop breakAt;

    /** [econ32c] Off to its level's landing for a breather, if it has a cache: true if it went. */
    private boolean beginBreak(ServerLevel level) {
        com.jrpetty.mcassistant.entity.MineLift.Stop s = com.jrpetty.mcassistant.entity.MineCache.near(level, a);
        if (s == null) return false;
        List<BlockPos> up = MineWorks.wayUp(level, a);
        List<BlockPos> to = new ArrayList<>();
        if (up != null) for (BlockPos p : up) { to.add(p); if (p.equals(s.board())) break; }
        if (to.isEmpty() || !to.get(to.size() - 1).equals(s.board())) to = new ArrayList<>(List.of(a.blockPosition(), s.board()));
        if (task != null) { report("stopped"); task = null; }
        rides.clear();
        stairs = to;
        route = to;
        ri = 0;
        walking = null;
        breakAt = s;
        breakTicks = 0;
        phase = Phase.BREAK;
        return true;
    }

    private void breather(ServerLevel level) {
        if (breakAt == null) { phase = Phase.NEXT; return; }
        if (!follow()) return;
        if (breakTicks++ == 0) {
            String took = com.jrpetty.mcassistant.entity.MineCache.take(level, a, breakAt);
            a.getNavigation().stop();
            BREATHERS++;
            com.jrpetty.mcassistant.entity.MineWorks.event(a, "a breather at the landing" + (took.isEmpty() ? "" : ": " + took));
        }
        if (breakTicks < BREAK_TICKS) return;
        breaks++;
        tasks = 0;
        shiftTicks = 0;
        breakAt = null;
        phase = Phase.NEXT;
    }

    public static volatile int BREATHERS;

    private int freeSlots() {
        int n = 0;
        for (ItemStack s : a.getInventoryItems()) if (s.isEmpty()) n++;
        return n;
    }

    /** [econ32b] A climb that got nowhere: put at the top of the works (the way in), if it is below and the top is open. */
    private void putAtTheTop(ServerLevel level) {
        BlockPos top = MineWorks.below(a) ? MineWorks.topFor(level, a) : null;
        if (top != null && open(top)) {
            a.getNavigation().stop();
            a.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
            teleports++;
            TELEPORTS++;
        }
    }

    /** [econ32b] When each folk's climb out of the works began (a rescue's fresh way up does not start the clock again). */
    private static final java.util.Map<java.util.UUID, Long> CLIMBING = new java.util.concurrent.ConcurrentHashMap<>();

    private void beginOut(String why) {
        bye = why;
        long now = a.level().getGameTime();
        Long since = CLIMBING.get(a.getUUID());
        if (since == null || now - since > 6000 || now < since) CLIMBING.put(a.getUUID(), now);   // [econ32b]
        if (task != null) { report(why.isEmpty() ? "" : "stopped"); }
        List<BlockPos> up = a.level() instanceof ServerLevel sl ? MineWorks.wayUp(sl, a) : null;
        if (a.level() instanceof ServerLevel sl2) setRoute(sl2, up == null ? List.of() : up);   // [econ32c] up in the cage
        else { route = List.of(); ri = 0; }
        if (phase != Phase.OUT) outTicks = 0;
        // (where it was walking kept: a restart does not forget a walk that is stuck)
        phase = Phase.OUT;
    }

    private void finish(String message) {
        if (MineWorks.below(a)) com.jrpetty.mcassistant.entity.MineWhy.note(a, "shift over below: " + message);   // [econ32b]
        if (!outJob && tasks > 0) SHIFTS++;                      // [fixes14]
        ENDS.merge(message.isEmpty() ? "-" : message, 1, Integer::sum);
        if (!outJob && tasks > 0) { SHIFT_BLOCKS += broke; SHIFT_TICKS_ALL += shiftTicks; }   // [growth22] the books' averages
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
     * (no headway for eight seconds, a hop and the pathfinder tried) it is put on the waypoint, the way being cut and
     * open. True when it is at the end.
     */
    private boolean follow() {
        if (ri >= route.size()) return true;
        if (rides.containsKey(ri)) return ride();                // [econ32c] at a stop of the cage
        int j = ri;
        BlockPos p0 = route.get(ri);
        while (j + 1 < route.size() && j - ri < 6 && !rides.containsKey(j + 1)) {
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

    /** [econ32c] A walk set going, with the cage in it where the town has one. */
    private void setRoute(ServerLevel level, List<BlockPos> r) {
        rides.clear();
        rideAsks = 0;
        stairs = r;
        route = r;
        ri = 0;
        if (a instanceof VillageFolkEntity f && f.ownerId() != null) {
            List<BlockPos> s = com.jrpetty.mcassistant.entity.MineLift.shorten(level, f.ownerId(), r, rides);
            if (s != null) route = s;
        }
    }

    /** [econ32c] At a stop: the cage asked for, waited for, ridden. True when the walk is over (never: the stop it rides to is next). */
    private boolean ride() {
        int[] r = rides.get(ri);
        java.util.UUID id = a.getUUID();
        long now = a.level().getGameTime();
        switch (com.jrpetty.mcassistant.entity.MineLift.state(id)) {
            case NONE -> {
                if (++rideAsks > 3 || !(a instanceof VillageFolkEntity f)
                        || !com.jrpetty.mcassistant.entity.MineLift.ask(a, f.ownerId(), r[0], r[1])) { toStairs(r); return false; }
                rideAsked = now;
                a.getNavigation().stop();
            }
            case WAITING -> {
                if (now - rideAsked > LIFT_WAIT) {                // the cage busy: the stair
                    com.jrpetty.mcassistant.entity.MineLift.cancel(id);
                    LIFT_GAVE_UP++;
                    toStairs(r);
                } else a.getNavigation().stop();
            }
            case RIDING -> { }
            case ARRIVED -> {
                com.jrpetty.mcassistant.entity.MineLift.done(id);
                RIDES++;
                ri++;
                walking = null;
                stuck = 0;
                return ri >= route.size();
            }
        }
        return false;
    }

    private void toStairs(int[] r) {
        route = stairs;
        ri = Math.min(r[2], route.size());
        rides.clear();
        walking = null;
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
        // [fixes14] Along the tunnel in sight of the spot: stepped straight there. The pathfinder counts a spot a block
        // off as reached, and left the miner standing at the edge of the cell before, short of where the works wanted
        // it, until it was put there: ten hops a shift, on every step of the stair and every few blocks of a drift.
        boolean straight = !far && (stuck < 60 || !dest.equals(walking)) && straight(dest);
        if (!dest.equals(walking)) {
            walking = dest;
            best = d;
            stuck = 0;
            if (!straight) go(dest, far);
        } else if (!straight && a.getNavigation().isDone() && a.tickCount - lastGo >= REPLAN) {
            go(dest, far);                                       // [soak40] again, but not every tick (see REPLAN)
        }
        if (straight) {
            if (!a.getNavigation().isDone()) a.getNavigation().stop();
            a.getMoveControl().setWantedPosition(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, PACE);
            a.getLookControl().setLookAt(dest.getX() + 0.5, dest.getY() + 1.5, dest.getZ() + 0.5);
        }
        if (d < best - 0.2) { best = d; stuck = 0; } else stuck++;
        // [fixes14] No headway for three seconds: a hop up (a lip of rock, a stair's edge), and the pathfinder's way.
        if (!far && stuck == 60) {
            if (a.onGround()) a.getJumpControl().jump();
            go(dest, false);
        }
        // A long way off (on the surface, sent off somewhere): its long walk, given its time; then the shift is over.
        if (far) {
            if (stuck > 600) {
                stuck = 0;
                walking = null;
                if (task != null && a instanceof VillageFolkEntity f) MineWorks.release(f.ownerId(), task, a.getUUID());
                task = null;
                com.jrpetty.mcassistant.entity.MineWhy.note(a, "no way to " + dest.toShortString() + " (" + (int) Math.sqrt(d) + " off)");   // [econ32b]
                // [econ32b] On its way out, a long walk that got nowhere ends at the top, not where it stood below.
                if (phase == Phase.OUT && a.level() instanceof ServerLevel sl) { CLIMBING.remove(a.getUUID()); putAtTheTop(sl); finish(bye); return false; }
                // [econ32b] Down in the works on its way to a face it cannot reach: it climbs out, not left standing below.
                if (phase != Phase.OUT && MineWorks.below(a)) { beginOut("Can't find my way to my face — out of the works."); return false; }
                finish("Can't find my way to the pithead.");
            }
            return false;
        }
        if (stuck > 160) {                                       // [fixes14] the last resort, after eight seconds
            stuck = 0;
            if (open(dest)) {
                a.getNavigation().stop();
                hopNote(dest);                                   // [fixes14] why it was stuck, for the tests
                a.teleportTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
                teleports++;
                TELEPORTS++;
            }
            return true;
        }
        return false;
    }

    /**
     * [fixes14] Is this spot a short straight walk from where it stands, every block of the way open two high (at its
     * own level or the spot's: a step of the stair)?
     */
    private boolean straight(BlockPos dest) {
        Vec3 from = a.position(), to = Vec3.atBottomCenterOf(dest);
        double dx = to.x - from.x, dz = to.z - from.z, len = Math.sqrt(dx * dx + dz * dz);
        if (len > 8.0 || Math.abs(to.y - from.y) > 1.6) return false;
        int lo = Math.min(a.blockPosition().getY(), dest.getY()), hi = Math.max(a.blockPosition().getY(), dest.getY());
        int steps = (int) Math.ceil(len / 0.4);
        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            int x = net.minecraft.util.Mth.floor(from.x + dx * f), z = net.minecraft.util.Mth.floor(from.z + dz * f);
            boolean ok = false;
            for (int y = lo; y <= hi && !ok; y++) ok = open(new BlockPos(x, y, z));
            if (!ok) return false;
        }
        return true;
    }

    private void go(BlockPos dest, boolean far) {
        lastGo = a.tickCount;                                    // [soak40]
        if (far) { a.walkTo(dest, 1.1D); return; }
        // [fixes14] To the spot itself (the pathfinder's own reach of a block stopped it a cell short).
        net.minecraft.world.level.pathfinder.Path path = a.getNavigation().createPath(dest, 0);
        if (path != null) a.getNavigation().moveTo(path, PACE);
        else a.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, PACE);
    }

    /**
     * [econ32c] The pace along the works' own tunnels: a jog, as a miner who knows them goes. With the cage in the shaft
     * the walk along the drifts (a hundred blocks and more out from the landing on a worked level) was most of a shift:
     * a quarter at the face, three fifths walking.
     */
    private static final double PACE = 1.5D;

    /**
     * [soak40] The least ticks between one way to the spot and the next while the last has run out short of it. A path
     * that ended short (a partial one, or none) was planned again the very next tick, every tick of the three seconds
     * before the hop and the eight before the last resort (ten for a long walk): six miners in the drifts of a town of
     * seventy spent a fifteenth of the server's whole tick in the pathfinder.
     */
    private static final int REPLAN = 10;
    private int lastGo = -REPLAN;

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
        // [fixes14] (Or out of sight of it down the stair or round a turn: the pathfinder lost its way in the shaft's
        // turns, and the miner was put there.)
        double far = a.distanceToSqr(Vec3.atBottomCenterOf(stand));
        if ((far > 256 || far > 12 && !straight(stand)) && a.level() instanceof ServerLevel sl0) {
            List<BlockPos> back = new ArrayList<>(MineWorks.route(sl0, a, t));
            back.add(stand);
            setRoute(sl0, back);                                // [econ32c]
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
        if (fluidAhead != null) { String why = fluidAhead; fluidAhead = null; report(why); return; }
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
                // Stopped in the drift's own way ahead: it is not tunnelled through; the drift turns round it after this cell.
                if (task != null && (task.kind == MineWorks.Kind.FACE || task.kind == MineWorks.Kind.BRANCH) && aheadInTask(side)) {
                    fluidAhead = lava ? "lava" : "water";
                }
                if (!saidSealed) { saidSealed = true; a.sayRoutine(lava ? "Lava behind the rock — stopped it with cobble." : "Water coming in — walled it off."); }
            } else if (lava) openLava = true;
        }
        // [growth22] And the open air it broke through to (a hill's flank): stopped up again with a block from the pack.
        if (task != null && task.kind != MineWorks.Kind.SHAFT) {
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue;
                BlockPos side = pos.relative(d);
                BlockState ss = level.getBlockState(side);
                if (!(ss.isAir() || ss.canBeReplaced() && ss.getFluidState().isEmpty()) || inTask(side)) continue;
                if (side.getY() < level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, side.getX(), side.getZ())) continue;
                if (seal(side, false)) { SEALED++; DAYLIGHT_SEALED++; }
            }
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

    /** Water or lava stopped here: in a cell of this task still to come, or the one straight on past its last? */
    private boolean aheadInTask(BlockPos p) {
        MineWorks.Task t = task;
        if (t == null) return false;
        List<BlockPos> ahead = new ArrayList<>(t.cells.subList(Math.min(ci + 1, t.cells.size()), t.cells.size()));
        if (!t.cells.isEmpty()) {
            BlockPos last = t.cells.get(t.cells.size() - 1), before = t.cells.size() > 1 ? t.cells.get(t.cells.size() - 2) : t.stand.get(0);
            ahead.add(last.offset(last.getX() - before.getX(), 0, last.getZ() - before.getZ()));
        }
        for (BlockPos c : ahead) {
            if (c.getX() == p.getX() && c.getZ() == p.getZ() && p.getY() >= c.getY() && p.getY() < c.getY() + t.height) return true;
        }
        return false;
    }

    /** What was stopped in the way ahead of the cell being cut ("water", "lava"), or null. */
    @Nullable private String fluidAhead;

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
            if (MineWorks.daylight(level, p, 1)) continue;          // [growth22] ore in the hill's skin stays: taking it opens the drift to the slope
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
        fluidAhead = null;
        blocks.clear();
        digging = null;
        if (t == null) return;
        t.why = why == null ? "" : why;
        if (a instanceof VillageFolkEntity f && a.level() instanceof ServerLevel level) MineWorks.done(level, f, t);
        tasks++;
        if (phase != Phase.OUT) phase = Phase.NEXT;
    }

    // ------------------------------------------------------------------ for the tests

    public static volatile int TELEPORTS, BROKEN, SEALED, LEFT, DAYLIGHT_SEALED;   // [growth22] DAYLIGHT_SEALED
    private static final java.util.Map<java.util.UUID, String> STATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests: what this miner's shift is at. */
    public static String stateForTests(AssistantEntity a) {
        return STATE.getOrDefault(a.getUUID(), "-");
    }

    /** Tests: the counts kept since the last reset. */
    public static int[] countsForTests() {
        return new int[]{ BROKEN, SEALED, LEFT, TELEPORTS };
    }

    /** [fixes14] Shifts worked, and the last few hops with what stood in the way (the tests read them). */
    public static volatile int SHIFTS;
    /** [growth22] Blocks broken and ticks spent over all the shifts counted in SHIFTS. */
    public static volatile long SHIFT_BLOCKS, SHIFT_TICKS_ALL;

    /** [growth22] The shifts in words, for the mine's report: how many, blocks and minutes a shift, why they ended. */
    public static String shiftsLine() {
        if (SHIFTS <= 0) return null;
        java.util.List<java.util.Map.Entry<String, Integer>> e = new java.util.ArrayList<>(ENDS.entrySet());
        e.sort((x, y) -> y.getValue() - x.getValue());
        StringBuilder sb = new StringBuilder("Shifts: " + SHIFTS + ", " + (SHIFT_BLOCKS / SHIFTS) + " blocks and " + (SHIFT_TICKS_ALL / SHIFTS / 1200)
            + " minutes a shift; ended:");
        for (int i = 0; i < Math.min(5, e.size()); i++) sb.append(i == 0 ? " " : ", ").append(e.get(i).getKey()).append(" ").append(e.get(i).getValue());
        sb.append(". ").append(timeLine());                      // [econ32c]
        return sb.toString();
    }
    private static final java.util.concurrent.ConcurrentLinkedDeque<String> HOPS = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public static final java.util.Map<String, Integer> ENDS = new java.util.concurrent.ConcurrentHashMap<>();

    public static List<String> hopsForTests() {
        return new ArrayList<>(HOPS);
    }

    private void hopNote(BlockPos dest) {
        net.minecraft.world.level.pathfinder.Path path = a.getNavigation().getPath();
        BlockPos at = a.blockPosition();
        int folk = a.level().getEntitiesOfClass(AssistantEntity.class, a.getBoundingBox().inflate(1.2), o -> o != a).size();
        HOPS.addLast(phase + (task == null ? "" : " " + task.kind) + " at " + a.position().x + "," + a.position().y + "," + a.position().z
            + " to " + dest.toShortString() + " (" + (dest.getX() - at.getX()) + "," + (dest.getY() - at.getY()) + "," + (dest.getZ() - at.getZ()) + ")"
            + " path " + (path == null ? "none" : (path.isDone() ? "done " : "") + path.getNodeCount() + " nodes to " + path.getTarget().toShortString() + (path.canReach() ? "" : " cannot reach"))
            + " folk by " + folk + " under " + a.level().getBlockState(at.below()).getBlock().getName().getString()
            + " feet " + a.level().getBlockState(at).getBlock().getName().getString() + " route " + ri + "/" + route.size());
        while (HOPS.size() > 60) HOPS.pollFirst();
    }

    /** [econ32c] The time of the town's shifts in the works: at the face, walking to it, in the cage (and waiting for it), climbing out. */
    public static volatile long T_FACE, T_WALK, T_CAGE, T_OUT;
    public static volatile int RIDES, LIFT_GAVE_UP;

    public static String timeLine() {
        long all = Math.max(1, T_FACE + T_WALK + T_CAGE + T_OUT);
        return "Time in the works: at the face " + (100 * T_FACE / all) + "%, walking " + (100 * T_WALK / all) + "%, in the cage "
            + (100 * T_CAGE / all) + "%, on the way out " + (100 * T_OUT / all) + "% (" + RIDES + " rides, " + LIFT_GAVE_UP + " took the stair, "
            + BREATHERS + " breathers at a landing)";
    }

    public static void resetCountsForTests() {
        T_FACE = 0; T_WALK = 0; T_CAGE = 0; T_OUT = 0; RIDES = 0; LIFT_GAVE_UP = 0; BREATHERS = 0;
        SHIFTS = 0;
        SHIFT_BLOCKS = 0;
        SHIFT_TICKS_ALL = 0;
        HOPS.clear();
        ENDS.clear();
        TELEPORTS = 0;
        BROKEN = 0;
        SEALED = 0;
        LEFT = 0;
    }
}
