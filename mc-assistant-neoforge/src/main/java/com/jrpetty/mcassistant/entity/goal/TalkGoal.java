package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Stop and talk. While a player is talking with a folk it stands still and looks
 * at them: its work waits, as anybody's would. It ends when the talk does — the
 * player says goodbye, walks off, or simply stops talking for half a minute.
 */
public class TalkGoal extends Goal {

    private final VillageFolkEntity folk;

    public TalkGoal(VillageFolkEntity folk) {
        this.folk = folk;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return folk.talkPartner() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return folk.talkPartner() != null;
    }

    @Override
    public void start() {
        folk.getNavigation().stop();
    }

    @Override
    public void tick() {
        Player p = folk.talkPartner();
        if (p == null) return;
        folk.getNavigation().stop();
        folk.getLookControl().setLookAt(p, 30.0F, 30.0F);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
