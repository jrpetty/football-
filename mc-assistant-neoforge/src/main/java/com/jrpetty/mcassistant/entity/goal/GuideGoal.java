package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Guide;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Showing a player the way (Guide): it walks ahead at a walking pace, looking back now and
 * then; it stops and waits when the player falls behind, and goes back for them if they
 * wander off; and when they get there it turns and says a word about the place. A way it
 * cannot find, it says so rather than standing there.
 */
public class GuideGoal extends Goal {

    private final VillageFolkEntity folk;
    private int repath;
    private int waited;
    private int stalled;
    private double nearest = Double.MAX_VALUE;

    public GuideGoal(VillageFolkEntity folk) {
        this.folk = folk;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return folk.talkPartner() == null && folk.guidePlayer() != null && folk.guideTo() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repath = 0;
        waited = 0;
        stalled = 0;
        nearest = Double.MAX_VALUE;
    }

    @Override
    public void tick() {
        Player p = folk.guidePlayer();
        BlockPos to = folk.guideTo();
        if (p == null || to == null) return;
        double dx = folk.getX() - (to.getX() + 0.5), dz = folk.getZ() - (to.getZ() + 0.5);
        double there = dx * dx + dz * dz;
        if (there < 2.5 * 2.5 && Math.abs(folk.getY() - to.getY()) < 3) {
            folk.getNavigation().stop();
            folk.getLookControl().setLookAt(p, 30.0F, 30.0F);
            FolkTalk.speak(folk, Guide.arrived(folk.guideWhat()));
            folk.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            folk.stopGuiding();
            return;
        }
        double behind = folk.distanceToSqr(p);
        if (behind > 24.0 * 24.0) {
            // Lost them: back to fetch them.
            if (--repath <= 0) {
                repath = 20;
                folk.getNavigation().moveTo(p, 1.1D);
            }
            return;
        }
        if (behind > 9.0 * 9.0) {
            // Ahead of them: wait, and wave them on now and then.
            folk.getNavigation().stop();
            folk.getLookControl().setLookAt(p, 30.0F, 30.0F);
            if (++waited % 140 == 1) {
                FolkTalk.speak(folk, FolkTalk.pick(folk.getRandom(), "This way!", "Keep up!", "Over here!", "Nearly there."));
                folk.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return;
        }
        // Walking on: towards the place, a little slower than a hurried walk so they can keep up.
        if (there < nearest - 1.0) {
            nearest = there;
            stalled = 0;
        } else if (++stalled > 300) {
            FolkTalk.speak(folk, "Hm. I can't find a way through from here — it's " + Guide.direction(folk.blockPosition(), to) + ".");
            folk.stopGuiding();
            return;
        }
        if (--repath <= 0 || folk.getNavigation().isDone()) {
            repath = 20;
            folk.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.85D);
        }
        if (folk.tickCount % 60 == 0) folk.getLookControl().setLookAt(p, 20.0F, 20.0F);
    }

    @Override
    public void stop() {
        folk.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
