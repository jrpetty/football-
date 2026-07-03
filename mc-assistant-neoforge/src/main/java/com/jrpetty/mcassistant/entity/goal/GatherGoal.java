package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * The gather task: find matching blocks within radius, walk over, break them
 * (with a per-block work delay, roughly like a player without the right tool
 * speed), sweep the drops into the assistant's inventory, repeat until the
 * requested amount is collected or nothing is left nearby.
 */
public class GatherGoal extends Goal {

    public enum Kind {
        LOGS("logs"),
        STONE("stone"),
        DIRT("dirt"),
        IRON("iron ore"),
        COAL("coal ore");

        public final String label;
        Kind(String label) { this.label = label; }

        boolean matches(BlockState state) {
            return switch (this) {
                case LOGS -> state.is(BlockTags.LOGS);
                case STONE -> state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);
                case DIRT -> state.is(BlockTags.DIRT);
                case IRON -> state.is(BlockTags.IRON_ORES);
                case COAL -> state.is(BlockTags.COAL_ORES);
            };
        }

        /** Player rules: ores and stone yield nothing without the right pickaxe. */
        public boolean needsProperTool() {
            return this == STONE || this == IRON || this == COAL;
        }

        public String toolHint() {
            return this == IRON ? "a stone pickaxe or better (\"craft a stone pickaxe\")"
                : "a pickaxe (\"craft a wooden pickaxe\")";
        }

        @Nullable
        public static Kind fromWord(String word) {
            String w = word == null ? "" : word.toLowerCase().trim();
            if (w.startsWith("log") || w.startsWith("wood") || w.startsWith("tree")) return LOGS;
            if (w.startsWith("iron")) return IRON;
            if (w.startsWith("coal")) return COAL;
            if (w.startsWith("stone") || w.startsWith("cobble") || w.startsWith("rock")) return STONE;
            if (w.startsWith("dirt")) return DIRT;
            return null;
        }
    }

    /** A one-shot request handed to the goal by commands/chat. */
    public record Request(Kind kind, int amount) {}

    private static final int SEARCH_RADIUS = 16;

    private final AssistantEntity assistant;
    @Nullable private Request request;
    @Nullable private BlockPos targetPos;
    private int collected;
    private int workTicks;
    private int workNeeded = 30; // recomputed per block from the equipped tool
    private int stuckTicks;
    private int myGen;
    private final java.util.Set<BlockPos> unreachable = new java.util.HashSet<>();

    public GatherGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private static boolean isGather(com.jrpetty.mcassistant.entity.Job j) {
        return j != null && j.type() == com.jrpetty.mcassistant.entity.Job.Type.GATHER;
    }

    @Override
    public boolean canUse() {
        return isGather(assistant.peekJob()) && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        // Abort if a newer order/stop came in (taskGen changed) or combat started.
        return request != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        com.jrpetty.mcassistant.entity.Job j = assistant.peekJob(); // peek: only remove when finished
        this.request = new Request(j.kind(), j.amount());
        this.myGen = assistant.taskGen();
        this.collected = 0;
        this.targetPos = null;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.unreachable.clear();
        assistant.say("On it — gathering " + request.amount() + " " + request.kind().label + ".");
    }

    @Override
    public void stop() {
        if (request != null) {
            // Interrupted (combat / stop) — leave the job at the head of the
            // queue so it resumes after the interruption (unless the queue was
            // cleared by !stop, in which case there's nothing to resume).
            assistant.say("Paused gathering (" + collected + " " + request.kind().label + " so far).");
        }
        this.request = null;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    /** A job finished (or gave up) — drop it from the queue and move on. */
    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(collected > 0); // idle initiative backs off when the area is dry
        assistant.pollJob();
        this.request = null;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (request == null) return;

        if (collected >= request.amount()) {
            finish("Done — gathered " + collected + " " + request.kind().label + ".");
            return;
        }

        if (targetPos == null || !request.kind().matches(assistant.level().getBlockState(targetPos))) {
            targetPos = findNearest();
            workTicks = 0;
            stuckTicks = 0;
            if (targetPos == null) {
                finish(collected > 0
                    ? "Got " + collected + " " + request.kind().label + " — that's all there is within " + SEARCH_RADIUS + " blocks."
                    : "Can't do that here — no " + request.kind().label + " within " + SEARCH_RADIUS + " blocks of me.");
                return;
            }
            // Player rules: grab the right tool for this block; work speed
            // follows the tool (bare hands are slow, diamond is fast).
            var state = assistant.level().getBlockState(targetPos);
            assistant.equipBestTool(state);
            if (request.kind().needsProperTool()
                && !assistant.getMainHandItem().isCorrectToolForDrops(state)) {
                finish("I can't harvest " + request.kind().label + " without " + request.kind().toolHint() + ".");
                return;
            }
            workNeeded = assistant.workTicksFor(state);
        }

        // Player-parity reach: measured eye-to-block-center, 4.5 blocks.
        double distSq = assistant.getEyePosition().distanceToSqr(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            // Still walking there.
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 100) { // ~5s without arriving — mark unreachable so
                unreachable.add(targetPos); // findNearest won't pick it again (no infinite crawl)
                targetPos = null;
            }
            return;
        }

        // In range: put in the work, then break the block and sweep the drops.
        if (++workTicks < workNeeded) {
            if (workTicks % 8 == 0) {
                assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return;
        }

        BlockPos pos = targetPos;
        targetPos = null;
        workTicks = 0;
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            collected++;
            assistant.damageHeldTool(); // tools wear like a player's
            sweepDrops(pos);
        }
    }

    private void sweepDrops(BlockPos around) {
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(around).inflate(2.5))) {
            ItemStack leftover = assistant.insertItem(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover);
            }
        }
    }

    @Nullable
    private BlockPos findNearest() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-SEARCH_RADIUS, -6, -SEARCH_RADIUS),
                feet.offset(SEARCH_RADIUS, 8, SEARCH_RADIUS))) {
            if (unreachable.contains(pos)) continue; // don't re-target blocks we couldn't reach
            if (!request.kind().matches(assistant.level().getBlockState(pos))) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
