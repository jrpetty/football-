package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * How folk find their way: the game's own ground navigation, made to look further. A
 * plot sixty or a hundred blocks out, the walk home to bed, the trip to the storehouse —
 * the game's search stops at its follow range (sixty-four blocks) and hands back half a
 * path that ends at a cliff or a riverbank. This searches out to {@link #REACH} blocks
 * with two and a half times the nodes, so a long walk is planned whole, round the hill
 * rather than up against it. (The follow range itself is left alone: it is also how far a
 * folk notices a foe, and that should not grow.)
 */
public class FolkNavigation extends GroundPathNavigation {

    /** How far a path is searched, in blocks. */
    public static final float REACH = 112.0F;

    public FolkNavigation(Mob mob, Level level) {
        super(mob, level);
        setMaxVisitedNodesMultiplier(2.5F);
    }

    /** The game's own path finder over the game's own walking rules (GroundPathNavigation's), with an evaluator that
     *  works each place out once a search rather than a dozen times (FolkNodeEvaluator): the same paths, cheaper. */
    @Override
    protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new FolkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new net.minecraft.world.level.pathfinder.PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    /** [sf] The last place the careful plan could not reach, and when: planned at full drop straight away for a while. */
    @Nullable private BlockPos looseTo;
    private long looseAt;

    // ------------------------------------------------------------ [scale] the server's budget for path searches

    /** The server tick the budget below is being spent in, and how much of it is spent (ns). */
    private static long budgetTick = Long.MIN_VALUE;
    private static long spentNanos;
    /** Searches put off for want of budget, all told (the perf probes read it). */
    private static long deferredInAll;
    /** A folk put off this many ticks running searches whatever the budget: nobody waits long. */
    private static final int MOST_DEFERRED_TICKS = 2;
    /** A folk this near a player is never put off: what a player watches is as it always was. */
    private static final double WATCHED = 32.0;
    /** The last tick this folk's search was put off, and how many ticks running it has been. */
    private long deferredAt = Long.MIN_VALUE;
    private int deferredRun;

    /**
     * Are the savings for scale in force (FolkLod.scaling)? Everywhere but the game tests' server, where only a test
     * that asks has them: a search put off by a busy machine's clock, or a walk re-planned later because nobody is
     * near (nobody ever is, in a test), would make the other features' tests depend on them.
     */
    private boolean scaling() {
        return FolkLod.scaling(this.level);
    }

    /** How many folk path searches have been put off a tick for want of budget since the server started. */
    public static long deferredInAll() {
        return deferredInAll;
    }

    /**
     * May this folk search for a path now? Yes while the tick's budget (pathBudgetMs, server-wide) lasts, for a folk
     * near a player, for one already put off two ticks running, and for the walk it is on (asked again for the same
     * place, the game hands back the path it has, at no cost). Otherwise it is put off to the next tick: no path, so
     * the folk stands a tick and asks again, as it does whenever a walk ends short. Bounds the ticks when many folk
     * set out at once (the morning, the bell) at a few milliseconds of searching.
     */
    private boolean mayPlan(Set<BlockPos> targets) {
        long tick = this.level.getServer() == null ? 0 : this.level.getServer().getTickCount();
        if (tick != budgetTick) {
            budgetTick = tick;
            spentNanos = 0;
        }
        long budget = com.jrpetty.mcassistant.AssistantConfig.pathBudgetMs() * 1_000_000L;
        if (budget <= 0 || spentNanos < budget) return true;
        if (!scaling()) return true;
        if (this.path != null && !this.path.isDone() && targets.contains(getTargetPos())) return true;
        if (deferredAt == tick - 1 && deferredRun >= MOST_DEFERRED_TICKS) return true;
        if (this.level.getNearestPlayer(this.mob, WATCHED) != null) return true;
        deferredRun = deferredAt == tick - 1 ? deferredRun + 1 : 1;
        deferredAt = tick;
        deferredInAll++;
        return false;
    }

    /** [scale] Out of a player's close sight, a walk is planned again for a block changed by it at most this often
     *  (the game's own is once a second): a farmer's tilling beside a neighbour's path re-planned it every second. */
    private static final long UNWATCHED_RECOMPUTE_TICKS = 60L;

    /**
     * [scale] The game re-plans a walk whenever a block changes beside it, once a second at most. Out of a player's close
     * sight, once in three: the walk goes on as planned meanwhile (as it does for the second the game waits), and a
     * block that truly bars it is met by the walk's own stuck checks as ever.
     */
    @Override
    public void recomputePath() {
        if (this.level.getGameTime() - this.timeLastRecompute < UNWATCHED_RECOMPUTE_TICKS && scaling()
                && this.level.getNearestPlayer(this.mob, WATCHED) == null) return;
        super.recomputePath();
    }

    // ------------------------------------------------------------ [scale] walks out of everybody's sight (FolkLod)

    /** A walk shorter than this is walked: it costs little, and it is mostly a step about a field or a bench. */
    private static final double TRAVEL_FROM = 8.0;
    /** A route is this much longer than the straight line, about (round the houses, along the streets). */
    private static final double DETOUR = 1.2;
    /** How fast a folk's body goes on the flat at a given speed (blocks a tick per speed squared: the game's
     *  ground friction and its mobs' walking, worked through: 0.32 walks 0.30 blocks a tick). */
    private static final double PACE = 2.92;
    /** The slowest and fastest a travelled walk goes, blocks a tick. */
    private static final double SLOWEST = 0.05, FASTEST = 0.6;
    /** How often a walk under way looks whether it has gone out of sight (then the rest may be travelled). */
    private static final int LOOK_EVERY = 20;

    /** Where a travelled walk ends, and the game tick it gets there (-1: not yet reckoned, the speed not yet set). */
    @Nullable private BlockPos travelTo;
    @Nullable private Path travelPath;
    /** Where the walk is going, when the travel is only its part out of sight (then walked on from the edge of it). */
    @Nullable private net.minecraft.world.phys.Vec3 travelGoal;
    /** Where and when the travel set out: a travel sent somewhere else on the way keeps its time on the road. */
    @Nullable private net.minecraft.world.phys.Vec3 travelFrom;
    private long travelStart;
    /** A travel stopped (a goal that stops its walk and asks again): where it was going, and when it stopped. */
    @Nullable private BlockPos stoppedTo;
    private long stoppedAt;
    /** Asked again for the same place this soon after stopping, the travel picks up where it was. */
    private static final long RESUME_TICKS = 20L;
    private long arriveAt = -1;
    /** Walks travelled all told (the perf probes read it). */
    private static long travelledInAll;

    /** Is a walk being travelled out of sight just now (the folk stands where it set out till it is there)? */
    public boolean travelling() {
        return travelTo != null && this.path != null && this.path == travelPath && !this.path.isDone();
    }

    /** How many walks have been travelled out of sight since the server started. */
    public static long travelledInAll() {
        return travelledInAll;
    }

    /** Walk to a place: travelled if nobody can see either end (FolkLod), else walked as ever. */
    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        return travel(x, y, z, speed) || super.moveTo(x, y, z, speed);
    }

    @Override
    public boolean moveTo(double x, double y, double z, int accuracy, double speed) {
        return travel(x, y, z, speed) || super.moveTo(x, y, z, accuracy, speed);
    }

    /**
     * A walk out of everybody's sight, taken as travel: a village folk on its own business (not hired, fighting, riding
     * or on a lead), going further than {@link #TRAVEL_FROM} and no further than a search would reach, from out of
     * sight. Going somewhere out of sight too, it travels all the way (to the target or a block beside it it can stand
     * on); going into sight, it travels as far as the edge of sight and walks the rest from there. True if so.
     */
    private boolean travel(double x, double y, double z, double speed) {
        if (!(this.mob instanceof VillageFolkEntity f) || f.ownerId() == null || f.isHired() || f.getTarget() != null
                || f.isPassenger() || f.isLeashed() || f.isInWaterOrBubble() || !f.onGround()) return false;
        // Asked again for where it is already going (a goal re-issues its walk every tick): the travel goes on.
        if (travelling() && (this.travelGoal != null ? this.travelGoal.distanceToSqr(x, y, z) : this.travelTo.distToCenterSqr(x, y, z)) <= 3.0) {
            this.speedModifier = speed;
            return true;
        }
        double d2 = f.distanceToSqr(x, y, z);
        if (d2 < TRAVEL_FROM * TRAVEL_FROM || d2 > REACH * REACH || !FolkLod.unseen(f)) return false;
        net.minecraft.world.phys.Vec3 goal = new net.minecraft.world.phys.Vec3(x, y, z);
        if (FolkLod.unseenAt(this.level, x, y, z)) {
            BlockPos to = FolkLod.standAt(this.level, BlockPos.containing(x, y, z), f.position());
            return to != null && travelTo(to, null, speed);
        }
        BlockPos edge = FolkLod.edgeOfSight(this.level, f.position(), goal);
        return edge != null && edge.distToCenterSqr(f.position()) >= TRAVEL_FROM * TRAVEL_FROM && travelTo(edge, goal, speed);
    }

    /** Travel to this block, and (goal not null) walk on from it to the goal. */
    private boolean travelTo(BlockPos to, @Nullable net.minecraft.world.phys.Vec3 goal, double speed) {
        boolean onTheWay = travelling();
        net.minecraft.world.level.pathfinder.Node end = new net.minecraft.world.level.pathfinder.Node(to.getX(), to.getY(), to.getZ());
        Path travel = new Path(java.util.List.of(end), to, true);
        if (!super.moveTo(travel, speed)) return false;
        this.travelTo = to;
        this.travelPath = this.path;
        this.travelGoal = goal;
        this.arriveAt = -1;
        // Sent somewhere else on the way (a goal that picks a fresh spot each time it asks): the time already on the
        // road counts, as the steps already taken would have.
        // Or stopped a moment ago on its way to the same place (a goal that stops and asks again each tick): the same.
        long now = this.level.getGameTime();
        boolean resumed = !onTheWay && this.stoppedTo != null && now - this.stoppedAt <= RESUME_TICKS
            && this.stoppedTo.distSqr(to) <= 9 && this.travelFrom != null;
        if (!onTheWay && !resumed) {
            this.travelFrom = this.mob.position();
            this.travelStart = now;
            travelledInAll++;
        }
        this.stoppedTo = null;
        return true;
    }

    /**
     * A walk under way that has gone out of everybody's sight and is going somewhere out of sight: the rest of it is
     * travelled. Looked at once a second.
     */
    private void travelTheRest() {
        if ((this.level.getGameTime() + this.mob.getId()) % LOOK_EVERY != 0) return;
        BlockPos target = getTargetPos();
        if (target == null || this.path == null || this.path.isDone() || !this.path.canReach()) return;
        if (!FolkLod.unseenAt(this.level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5)) return;
        travel(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, this.speedModifier);
    }

    /** A travelled walk counts its time, and at its end the folk is there; one a player comes to see is walked on. */
    @Override
    public void tick() {
        if (this.travelTo != null) {
            if (!travelling()) {
                this.travelTo = null;
            } else {
                travelTick();
                return;
            }
        }
        if (!this.level.isClientSide && FolkLod.scaling(this.level)) travelTheRest();
        if (travelling()) return;
        super.tick();
    }

    private void travelTick() {
        long now = this.level.getGameTime();
        BlockPos to = this.travelTo;
        if (this.arriveAt < 0) {
            double speed = this.speedModifier * this.mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            double pace = Math.max(SLOWEST, Math.min(FASTEST, PACE * speed * speed));
            net.minecraft.world.phys.Vec3 from = this.travelFrom != null ? this.travelFrom : this.mob.position();
            this.arriveAt = this.travelStart + (long) Math.ceil(Math.sqrt(to.distToCenterSqr(from)) * DETOUR / pace);
        }
        if (now >= this.arriveAt) {
            this.travelTo = null;
            net.minecraft.world.phys.Vec3 goal = this.travelGoal;
            this.travelGoal = null;
            if (FolkLod.standable(this.level, to)) {
                this.mob.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                this.mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                this.path.setNextNodeIndex(this.path.getNodeCount());
                // Come to the edge of sight: the rest of the way is walked.
                if (goal != null) {
                    double sp = this.speedModifier;
                    this.path = null;
                    super.moveTo(goal.x, goal.y, goal.z, sp);
                }
            } else {
                // The ground changed while it was on its way: it walks from where it is.
                double sp = this.speedModifier;
                this.path = null;
                super.moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, sp);
            }
            return;
        }
        // Somebody has come into sight of it: the rest of the way is walked, seen (looked at every tick: a player
        // can come over a hill, or out of a door, in any one).
        if (!FolkLod.unseen(this.mob)) {
            net.minecraft.world.phys.Vec3 goal = this.travelGoal != null ? this.travelGoal : net.minecraft.world.phys.Vec3.atBottomCenterOf(to);
            this.travelTo = null;
            this.travelGoal = null;
            double sp = this.speedModifier;
            this.path = null;
            super.moveTo(goal.x, goal.y, goal.z, sp);
        }
    }

    @Override
    public void stop() {
        if (travelling()) {
            this.stoppedTo = this.travelTo;
            this.stoppedAt = this.level.getGameTime();
        }
        this.travelTo = null;
        this.travelGoal = null;
        super.stop();
    }

    @Nullable
    @Override
    protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
        if (this.level.isClientSide || !mayPlan(targets)) return null;
        long t0 = System.nanoTime();
        try {
            return plan(targets, regionOffset, offsetUpward, accuracy);
        } finally {
            spentNanos += System.nanoTime() - t0;
        }
    }

    @Nullable
    private Path plan(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
        float range = Math.max(REACH, (float) this.mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
        if (!(this.mob instanceof AssistantEntity a) || !a.plansCarefully()) {
            Path was = this.path;
            Path p = createPath(targets, regionOffset, offsetUpward, accuracy, range);
            if (p != null && p != was && this.mob instanceof AssistantEntity a2) a2.plannedDrop(this.mob.getMaxFallDistance());
            return p;
        }
        // [sf] A village's folk outside a fight: first a way with no drop over three blocks, the five-block
        // drop it takes without a scratch only where there is no such way. A five-block drop hurts nobody,
        // but the folk who died "in a fall" in the mountain town went down ledges, and a way round a ledge
        // is a way that does not end at the bottom of one. (A plan that could not be made carefully is
        // not tried carefully again for half a minute: the second search is not paid for twice.)
        long now = this.level.getGameTime();
        Path current = this.path;
        boolean skip = looseTo != null && now - looseAt < 600 && targets.contains(looseTo);
        if (!skip) {
            Path careful;
            a.capDrops(AssistantEntity.CAREFUL_DROP);
            try {
                careful = createPath(targets, regionOffset, offsetUpward, accuracy, range);
            } finally {
                a.capDrops(-1);
            }
            if (careful != null && careful == current) return careful;     // the walk under way, as it was planned
            if (careful != null && careful.canReach()) {
                a.plannedDrop(AssistantEntity.CAREFUL_DROP);
                return careful;
            }
            looseTo = targets.isEmpty() ? null : targets.iterator().next();
            looseAt = now;
        }
        Path loose = createPath(targets, regionOffset, offsetUpward, accuracy, range);
        if (loose != null && loose != current) a.plannedDrop(this.mob.getMaxFallDistance());
        return loose;
    }
}
