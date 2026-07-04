package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
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
    @Nullable private BlockPos jumpFrom;
    @Nullable private Direction escapeDir; // direction we were trying to travel when we got stuck

    public EscapeGoal(AssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (suffocating()) return true;
        // Otherwise: on the ground, stuck for a few seconds, and actually walled
        // in — either on all sides, or by an obstacle in our path of travel.
        if (!a.onGround() || !a.isBadlyStuck()) return false;
        return boxedIn() || blockedAhead(travelDir());
    }

    @Override
    public boolean canContinueToUse() {
        // Keep going on PHYSICAL state (not the stuck timer, which resets the moment
        // we stop navigating on start) until we've actually broken free.
        if (suffocating() || boxedIn()) return true;
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
        this.work = 0;
        this.jumpFrom = null;
        this.escapeDir = travelDir();
        a.getNavigation().stop();
        a.say("I'm stuck — digging my way out.");
    }

    @Override
    public void stop() {
        this.jumpFrom = null;
        this.escapeDir = null;
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
            // Pillar up if we have a block, else carve a foothold and hop up.
            if (a.countMatching(NightShelterGoal.SHELTER_BLOCK) > 0) {
                if (a.onGround()) {
                    jumpFrom = feet;
                    a.getJumpControl().jump();
                } else if (jumpFrom != null && a.getY() - jumpFrom.getY() > 0.45
                        && a.level().getBlockState(jumpFrom).canBeReplaced()) {
                    placeAt(jumpFrom);
                    jumpFrom = null;
                }
                return;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (solid(head.relative(d)) || solid(aboveHead.relative(d))) {
                    breakBlock(aboveHead.relative(d));
                    breakBlock(head.relative(d));
                    a.getJumpControl().jump();
                    return;
                }
            }
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

    private boolean solid(BlockPos p) {
        return a.level().getBlockState(p).isSolid();
    }

    private void breakBlock(BlockPos p) {
        BlockState s = a.level().getBlockState(p);
        if (!s.isSolid()) return;
        // Don't smash chests/furnaces/spawners and the like to get free.
        if (a.level().getBlockEntity(p) != null) return;
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

    private void placeAt(BlockPos p) {
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty() || !NightShelterGoal.SHELTER_BLOCK.test(st)) continue;
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            a.level().setBlockAndUpdate(p, bi.getBlock().defaultBlockState());
            st.shrink(1);
            if (st.isEmpty()) inv.set(i, ItemStack.EMPTY);
            a.swing(InteractionHand.MAIN_HAND);
            return;
        }
    }
}
