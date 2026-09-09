package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * The panic dive: a creeper starts hissing nearby -> sprint straight out of
 * blast radius before anything else matters. Highest priority movement.
 */
public class CreeperDodgeGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Creeper threat;
    private int runTicks;

    public CreeperDodgeGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Nullable
    private Creeper findSwelling() {
        Creeper worst = null;
        double best = Double.MAX_VALUE;
        for (Creeper c : assistant.level().getEntitiesOfClass(
                Creeper.class, assistant.getBoundingBox().inflate(7.0),
                cr -> cr.isAlive() && (cr.getSwellDir() > 0 || cr.isIgnited()))) {
            double d = c.distanceToSqr(assistant);
            if (d < best) { best = d; worst = c; }
        }
        return worst;
    }

    @Override
    public boolean canUse() {
        threat = findSwelling();
        return threat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return runTicks > 0;
    }

    @Override
    public void start() {
        this.runTicks = 40;
        flee();
    }

    @Override
    public void tick() {
        runTicks--;
        if (runTicks % 10 == 0) {
            Creeper again = findSwelling();
            if (again != null) {
                threat = again;
                runTicks = 40;
            }
            flee();
        }
    }

    @Override
    public void stop() {
        this.threat = null;
        assistant.getNavigation().stop();
    }

    private void flee() {
        if (threat == null) return;
        Vec3 away = assistant.position().subtract(threat.position());
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        away = away.normalize();
        assistant.getNavigation().moveTo(
            assistant.getX() + away.x * 8, assistant.getY(), assistant.getZ() + away.z * 8, 1.5D);
    }
}
