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
 * Self-rescue. When the assistant is buried/suffocating, or boxed into a pocket
 * it's been unable to walk out of, it frees itself — whichever way out is best:
 *   - clears the blocks above so it can rise;
 *   - pillars up with building blocks it carries (jump, place under, climb);
 *   - or, with no blocks, digs a foothold in a wall to clamber out.
 * Top priority, so being stuck is never permanent. It only fires when GENUINELY
 * trapped (suffocating, or badly stuck for a few seconds), so it never tears
 * down its own night shelter.
 */
public class EscapeGoal extends Goal {

    private final AssistantEntity a;
    private int work;
    @Nullable private BlockPos jumpFrom;

    public EscapeGoal(AssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Start only when genuinely trapped: buried, or wedged after failing to
        // move for a few seconds (so we don't dig out of our own night shelter).
        if (suffocating()) return true;
        return a.onGround() && a.isBadlyStuck() && boxedIn();
    }

    @Override
    public boolean canContinueToUse() {
        // Keep going on the PHYSICAL state (not the stuck timer, which resets the
        // moment we stop navigating on start) until we've actually broken free.
        return suffocating() || boxedIn();
    }

    /** No head-level opening in any horizontal direction — can't just walk out. */
    private boolean boxedIn() {
        BlockPos head = a.feetPos().above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!solid(head.relative(d))) return false;
        }
        return true;
    }

    @Override
    public void start() {
        this.work = 0;
        this.jumpFrom = null;
        a.getNavigation().stop();
        a.say("I'm stuck — digging my way out.");
    }

    @Override
    public void stop() {
        this.jumpFrom = null;
        a.getNavigation().stop();
    }

    private boolean suffocating() {
        BlockPos head = a.blockPosition().above();
        return a.level().getBlockState(head).isSuffocating(a.level(), head);
    }

    @Override
    public void tick() {
        BlockPos feet = a.feetPos();
        a.getLookControl().setLookAt(feet.getX() + 0.5, feet.getY() + 3.0, feet.getZ() + 0.5);
        if (++work < 6) return; // pace the actions, like mining
        work = 0;

        BlockPos head = feet.above();
        BlockPos aboveHead = feet.above(2);

        // 1) Open the space above so we can rise.
        if (solid(head)) { breakBlock(head); return; }
        if (solid(aboveHead)) { breakBlock(aboveHead); return; }

        // 2) Rise out. Pillar up if we have a block, else carve a foothold.
        if (a.countMatching(NightShelterGoal.SHELTER_BLOCK) > 0) {
            if (a.onGround()) {
                jumpFrom = feet;
                a.getJumpControl().jump();
            } else if (jumpFrom != null && a.getY() - jumpFrom.getY() > 0.45
                    && a.level().getBlockState(jumpFrom).canBeReplaced()) {
                placeAt(jumpFrom); // fill the gap we jumped from — we land a block higher
                jumpFrom = null;
            }
            return;
        }
        // 3) No blocks: break an upward step in a wall and hop into it.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (solid(head.relative(d)) || solid(aboveHead.relative(d))) {
                breakBlock(aboveHead.relative(d));
                breakBlock(head.relative(d));
                a.getJumpControl().jump();
                return;
            }
        }
    }

    private boolean solid(BlockPos p) {
        return a.level().getBlockState(p).isSolid();
    }

    private void breakBlock(BlockPos p) {
        BlockState s = a.level().getBlockState(p);
        if (!s.isSolid()) return;
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
