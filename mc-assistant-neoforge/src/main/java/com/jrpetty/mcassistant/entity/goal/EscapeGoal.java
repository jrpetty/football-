package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Footholds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Self-rescue. When the assistant is buried/suffocating, boxed into a pocket, or
 * simply wedged against a wall/tall step it's been unable to get past, it frees
 * itself — whichever way out is best:
 *   - buried/boxed: clears the blocks above and rises (pillars up with a carried
 *     block, or carves a foothold in the wall to clamber out);
 *   - wedged against an obstacle ahead: carves a 2-high passage straight through
 *     it in the direction it was trying to travel (dig-out / dig-through-and-climb).
 * Top priority, so being stuck is never permanent. It only fires when GENUINELY
 * trapped (suffocating, or badly stuck for a few seconds AND actually blocked),
 * so it never tears down its own night shelter while it's just standing around.
 */
public class EscapeGoal extends Goal {

    private final AssistantEntity a;
    private int work;
    @Nullable private Direction escapeDir; // direction we were trying to travel when we got stuck

    public EscapeGoal(AssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (a.tickCount < giveUpUntil) return false;   // just tried, gave up
        if (suffocating()) return true;
        // Otherwise: on the ground, stuck for a few seconds, and actually walled
        // in — either on all sides, or by an obstacle in our path of travel.
        if (!a.onGround() || !a.isBadlyStuck()) return false;
        return boxedIn() || blockedAhead(travelDir());
    }

    /** When this attempt started, so it cannot hold priority 0 for ever. */
    private int startedAt;
    private int giveUpUntil;

    @Override
    public boolean canContinueToUse() {
        // Hard stop after ten seconds. This goal sits at priority 0, so while it
        // runs the bot does NOTHING else — and its continue condition is purely
        // physical, so a bot facing a fence it could simply walk around met it
        // for ever: no work, and a hop every six ticks. Being wrong about being
        // trapped has to be survivable.
        if (a.tickCount - startedAt > 200) return false;
        if (suffocating() || boxedIn()) return true;
        if (onItsOwnStep()) return true;               // [mines] free, on top of its steps: off them, so they can come down
        return escapeDir != null && blockedAhead(escapeDir);
    }

    /** No head-level opening in any horizontal direction — can't just walk out. */
    private boolean boxedIn() {
        BlockPos head = a.feetPos().above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!solid(head.relative(d))) return false;
        }
        return true;
    }

    /** A wall or tall step in the travel direction, taller than we can step over. */
    private boolean blockedAhead(Direction d) {
        return solid(a.feetPos().relative(d).above());
    }

    /** The direction we're trying to travel — our body's heading, which points
     *  the way we've been trying to walk (toward the owner/target we're chasing). */
    private Direction travelDir() {
        return Direction.fromYRot(a.getYRot());
    }

    @Override
    public void start() {
        this.startedAt = a.tickCount;
        this.work = 0;
        this.stepOffTries = 0;
        this.escapeDir = travelDir();
        a.getNavigation().stop();
        a.say("I'm stuck — digging my way out.");
    }

    @Override
    public void stop() {
        this.escapeDir = null;
        // Back off before trying again, or it re-triggers on the same wall the
        // very next tick with nothing changed — which is how a bot ends up
        // permanently "escaping" from a fence it could have walked around.
        this.giveUpUntil = a.tickCount + 400;
        a.getNavigation().stop();
    }

    private boolean suffocating() {
        BlockPos head = a.blockPosition().above();
        return a.level().getBlockState(head).isSuffocating(a.level(), head);
    }

    @Override
    public void tick() {
        BlockPos feet = a.feetPos();
        if (++work < 6) return; // pace the actions, like mining
        work = 0;

        BlockPos head = feet.above();
        BlockPos aboveHead = feet.above(2);

        // 1) Buried or boxed in: open the space above and rise out.
        if (suffocating() || boxedIn()) {
            a.getLookControl().setLookAt(feet.getX() + 0.5, feet.getY() + 3.0, feet.getZ() + 0.5);
            if (solid(head)) { breakBlock(head); return; }
            if (solid(aboveHead)) { breakBlock(aboveHead); return; }
            // Pillar up if we have a block (booked, to come down once it is free: Footholds), else carve a foothold.
            if (Footholds.rise(a, Footholds.Purpose.ESCAPE)) return;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (solid(head.relative(d)) || solid(aboveHead.relative(d))) {
                    breakBlock(aboveHead.relative(d));
                    breakBlock(head.relative(d));
                    a.tryHop();
                    return;
                }
            }
            return;
        }

        // [mines] Free, standing on the steps it built to get out: off them onto the ground beside, and the steps
        // are taken up from there as it goes (Footholds.passBy).
        if (onItsOwnStep()) {
            BlockPos off = groundBeside(feet);
            if (off != null) a.getNavigation().moveTo(off.getX() + 0.5, off.getY(), off.getZ() + 0.5, 1.0D);
            else stepOffTries = STEP_OFF_TRIES;
            stepOffTries++;
            return;
        }

        // 2) Wedged against an obstacle ahead: carve a 2-high passage forward and
        //    step through it (dig-out, and dig-and-climb a tall step).
        Direction d = escapeDir != null ? escapeDir : Direction.fromYRot(a.getYRot());
        BlockPos feetAhead = feet.relative(d);
        a.getLookControl().setLookAt(feetAhead.getX() + 0.5, feetAhead.getY() + 0.5, feetAhead.getZ() + 0.5);
        if (solid(feetAhead.above())) { breakBlock(feetAhead.above()); return; }
        if (solid(feetAhead)) { breakBlock(feetAhead); return; }
        // Passage is open — walk forward through it; canContinueToUse then lets go
        // and the normal follow/travel navigation takes back over.
        a.getNavigation().moveTo(
            feetAhead.getX() + 0.5, feetAhead.getY(), feetAhead.getZ() + 0.5, 1.0D);
    }

    /** [mines] Tries at stepping off its own steps before it lets them be (a pillar in the open with no ground beside). */
    private static final int STEP_OFF_TRIES = 40;
    private int stepOffTries;

    /** [mines] Standing on a block it laid to get out, and not yet given up stepping off it. */
    private boolean onItsOwnStep() {
        return stepOffTries < STEP_OFF_TRIES && a.onGround() && Footholds.isOwn(a, a.feetPos().below());
    }

    /** [mines] Ground beside its steps it can step onto: a step up (out of the top of a hole), level, or a step down;
     *  firm, with two blocks of room. */
    @Nullable
    private BlockPos groundBeside(BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos c = feet.relative(d).above(dy);
                BlockPos floor = c.below();
                if (Footholds.isOwn(a, floor)) continue;
                if (!solid(c) && !solid(c.above()) && a.level().getBlockState(floor).isFaceSturdy(a.level(), floor, Direction.UP)) return c;
            }
        }
        return null;
    }

    private boolean solid(BlockPos p) {
        return a.level().getBlockState(p).isSolid();
    }

    private void breakBlock(BlockPos p) {
        BlockState s = a.level().getBlockState(p);
        if (!s.isSolid()) return;
        // Don't smash chests/furnaces/spawners and the like to get free.
        if (a.level().getBlockEntity(p) != null) return;
        // [sf] Nor a block with lava behind it: a folk wedged in a cave that carved its way out through the
        // wall of a lava pocket let the lava in on itself. The miner seals what it uncovers (MineGoal); a
        // folk only trying to get free leaves that wall alone and tries another way.
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (a.level().getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return;
        }
        if (a.level().destroyBlock(p, true, a)) {
            for (ItemEntity drop : a.level().getEntitiesOfClass(
                    ItemEntity.class, new AABB(p).inflate(2.0))) {
                ItemStack left = a.insertItem(drop.getItem());
                if (left.isEmpty()) drop.discard();
                else drop.setItem(left);
            }
            a.swing(InteractionHand.MAIN_HAND);
        }
    }
}
