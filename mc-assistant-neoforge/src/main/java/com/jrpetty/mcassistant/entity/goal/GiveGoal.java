package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Hand items to the owner: "give me 10 torches" — walks over and puts them
 * straight into the owner's inventory (drops at their feet if it's full).
 */
public class GiveGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private int stuckTicks;
    private int myGen;

    public GiveGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.GIVE && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.stuckTicks = 0;
        if (job == null || job.arg() == null) {
            finish("I didn't catch what to hand over.");
            return;
        }
        if (assistant.countMatching(WithdrawGoal.matcherFor(job.arg())) == 0) {
            finish("I don't have any " + job.arg() + " on me.");
        }
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null) return;
        Player owner = assistant.getOwnerPlayer();
        if (!(owner instanceof ServerPlayer sp)) {
            finish("You're not around to take it — I'll hold onto it.");
            return;
        }
        assistant.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        if (assistant.distanceToSqr(owner) > 6.25) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(owner, 1.25D);
            }
            if (++stuckTicks > 300) {
                finish("Couldn't reach you — I'll hold onto it.");
            }
            return;
        }

        Predicate<ItemStack> match = WithdrawGoal.matcherFor(job.arg());
        int want = job.amount();
        int given = 0;
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size() && given < want; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !match.test(s)) continue;
            int take = Math.min(want - given, s.getCount());
            ItemStack handing = s.copyWithCount(take);
            int before = handing.getCount();
            sp.getInventory().add(handing); // mutates: leftover stays in `handing`
            int moved = before - handing.getCount();
            if (!handing.isEmpty()) {
                // Owner's inventory is full — drop the rest at their feet.
                sp.drop(handing, false);
                moved = before;
            }
            s.shrink(moved);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            given += moved;
        }
        finish(given > 0 ? "There you go — " + given + " " + job.arg() + "." : "Couldn't hand that over.");
    }
}
