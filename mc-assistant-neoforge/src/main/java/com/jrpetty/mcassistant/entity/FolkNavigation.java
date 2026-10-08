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

    @Nullable
    @Override
    protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
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
