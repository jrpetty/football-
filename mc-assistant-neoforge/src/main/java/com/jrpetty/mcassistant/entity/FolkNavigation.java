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

    @Nullable
    @Override
    protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
        float range = Math.max(REACH, (float) this.mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
        return createPath(targets, regionOffset, offsetUpward, accuracy, range);
    }
}
