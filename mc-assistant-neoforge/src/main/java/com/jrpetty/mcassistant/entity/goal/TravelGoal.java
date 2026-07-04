package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Travel: walk to a named waypoint or home as a QUEUED job that only
 * completes on arrival — so "go to the mine, then gather 32 iron, then go
 * home and deposit" runs strictly in order. Long trips re-path in stages,
 * which carries the assistant well beyond a single pathfind's range.
 */
public class TravelGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos dest;
    private String destName = "";
    private int repathCooldown;
    private int noMoveTicks;
    private double lastDistSq;
    private int myGen;

    public TravelGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && (j.type() == Job.Type.GOTO || j.type() == Job.Type.GO_HOME)
            && assistant.getTarget() == null;
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
        this.repathCooldown = 0;
        this.noMoveTicks = 0;
        this.lastDistSq = Double.MAX_VALUE;
        if (job != null && job.type() == Job.Type.GO_HOME) {
            this.destName = "home";
            this.dest = assistant.getHome();
            if (dest == null) {
                finish("No home set — right-click an Assistant Spawner, or tell me \"set home here\".");
                return;
            }
        } else {
            String arg = job != null && job.arg() != null ? job.arg() : "?";
            BlockPos coords = parseCoords(arg);
            if (coords != null) {
                this.dest = coords;
                this.destName = coords.getX() + " " + coords.getY() + " " + coords.getZ();
            } else {
                this.destName = arg;
                this.dest = "home".equals(arg) ? assistant.getHome() : assistant.getWaypoint(arg);
            }
            if (dest == null) {
                finish("I don't know a place called \"" + destName + "\" — say \"remember this spot as "
                    + destName + "\" while I'm there, or give me coordinates. I know: " + placeList() + ".");
                return;
            }
        }
        assistant.say("Heading to " + destName + ".");
    }

    private String placeList() {
        var names = assistant.waypointNames();
        if (assistant.getHome() != null) names.add("home");
        return names.isEmpty() ? "nowhere yet" : String.join(", ", names);
    }

    /** "120 64 -300" -> BlockPos, else null. Y is clamped to the world. */
    @Nullable
    public static BlockPos parseCoords(@Nullable String arg) {
        if (arg == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)$").matcher(arg.trim());
        if (!m.matches()) return null;
        try {
            return new BlockPos(Integer.parseInt(m.group(1)),
                Math.max(-64, Math.min(320, Integer.parseInt(m.group(2)))),
                Integer.parseInt(m.group(3)));
        } catch (NumberFormatException e) {
            return null;
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
        this.dest = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || dest == null) return;

        double distSq = assistant.distanceToSqr(dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5);
        if (distSq < 7.0) {
            assistant.setMode(AssistantEntity.Mode.STAY); // arrive and hold
            finish("Here — " + destName + ".");
            return;
        }

        if (--repathCooldown <= 0) {
            repathCooldown = 40;
            assistant.getNavigation().moveTo(dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5, 1.2D);
        }

        // Progress watchdog: making no headway for ~15s means we're stuck.
        if (distSq > lastDistSq - 0.5) {
            if (++noMoveTicks > 300) {
                finish("Couldn't get all the way to " + destName + " — stopped where the path ends.");
            }
        } else {
            noMoveTicks = 0;
            lastDistSq = distSq;
        }
    }
}
