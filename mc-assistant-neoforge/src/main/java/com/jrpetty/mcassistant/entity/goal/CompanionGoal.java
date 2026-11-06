package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Walking with a player who asked, and whom it agreed to go with: it keeps a few
 * steps behind, catches up when left behind, and looks about as it goes. It heads
 * home by itself when its time is up, at nightfall, or when the player has gone
 * on too far without it (see VillageFolkEntity.companionPlayer).
 */
public class CompanionGoal extends Goal {

    private final VillageFolkEntity folk;
    private int repath;

    public CompanionGoal(VillageFolkEntity folk) {
        this.folk = folk;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return folk.talkPartner() == null && folk.companionPlayer() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        Player p = folk.companionPlayer();
        if (p == null) return;
        folk.getLookControl().setLookAt(p, 20.0F, 20.0F);
        double d = folk.distanceToSqr(p);
        if (d < 3.0 * 3.0) {
            folk.getNavigation().stop();
            return;
        }
        if (--repath > 0) return;
        repath = 10;
        folk.getNavigation().moveTo(p, d > 12.0 * 12.0 ? 1.25D : 1.0D);
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
