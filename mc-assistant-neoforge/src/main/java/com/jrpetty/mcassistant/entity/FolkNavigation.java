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
        if (this.path != null && !this.path.isDone() && targets.contains(getTargetPos())) return true;
        if (deferredAt == tick - 1 && deferredRun >= MOST_DEFERRED_TICKS) return true;
        if (this.level.getNearestPlayer(this.mob, WATCHED) != null) return true;
        deferredRun = deferredAt == tick - 1 ? deferredRun + 1 : 1;
        deferredAt = tick;
        deferredInAll++;
        return false;
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
