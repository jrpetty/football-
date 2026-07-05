package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
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
        COAL("coal ore"),
        SAND("sand"),
        GRAVEL("gravel"),
        SUGAR_CANE("sugar cane");

        public final String label;
        Kind(String label) { this.label = label; }

        public boolean matches(BlockState state) {
            return switch (this) {
                case LOGS -> state.is(BlockTags.LOGS);
                case STONE -> state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);
                case DIRT -> state.is(BlockTags.DIRT);
                case IRON -> state.is(BlockTags.IRON_ORES);
                case COAL -> state.is(BlockTags.COAL_ORES);
                case SAND -> state.is(BlockTags.SAND);
                case GRAVEL -> state.is(net.minecraft.world.level.block.Blocks.GRAVEL);
                case SUGAR_CANE -> state.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE);
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
            if (w.startsWith("sand")) return SAND;
            if (w.startsWith("gravel")) return GRAVEL;
            if (w.startsWith("sugar") || w.startsWith("cane") || w.startsWith("reed")) return SUGAR_CANE;
            return null;
        }
    }

    /** A one-shot request handed to the goal by commands/chat. */
    public record Request(Kind kind, int amount) {}

    private static final int SEARCH_RADIUS = 16;

    private final AssistantEntity assistant;
    @Nullable private Request request;
    @Nullable private com.jrpetty.mcassistant.entity.Job myJob;
    @Nullable private BlockPos targetPos;
    private int collected;
    private int workTicks;
    private int workNeeded = 30; // recomputed per block from the equipped tool
    private int stuckTicks;
    private int myGen;
    @Nullable private BlockPos pillarBase; // feet spot we jumped from, to fill while pillaring up
    private int builtBlocks;               // blocks spent building a way to the target (capped)
    private int buildTicks;                // hard time cap on building so it can never loop forever
    private final java.util.Set<BlockPos> unreachable = new java.util.HashSet<>();
    private final java.util.Set<BlockPos> stumps = new java.util.HashSet<>(); // for replanting

    public GatherGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        // JUMP too: so it can pillar up to reach a block it can't walk to.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
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
        // Abort if a newer order/stop came in (taskGen changed), our job was
        // displaced (pack-full deposit interjection), or combat started.
        return request != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == myJob;
    }

    @Override
    public void start() {
        this.myJob = assistant.peekJob(); // peek: only remove when finished
        this.request = new Request(myJob.kind(), myJob.amount());
        this.myGen = assistant.taskGen();
        this.collected = 0;
        this.targetPos = null;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.pillarBase = null;
        this.builtBlocks = 0;
        this.buildTicks = 0;
        this.unreachable.clear();
        this.stumps.clear();
        if (assistant.isPackFull()) {
            finish("Pack's full — I need to deposit before gathering more.");
            return;
        }
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
        this.myJob = null;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    /** A job finished (or gave up) — drop it from the queue and move on. */
    private void finish(String message) {
        replantStumps();
        assistant.say(message);
        assistant.noteJobOutcome(collected > 0); // idle initiative backs off when the area is dry
        assistant.pollJob();
        this.request = null;
        this.myJob = null;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    /** Sustainable forestry: put a sapling back on every stump we made. */
    private void replantStumps() {
        if (stumps.isEmpty()) return;
        int planted = 0;
        for (BlockPos stump : stumps) {
            if (!assistant.level().getBlockState(stump).canBeReplaced()) continue;
            if (!assistant.level().getBlockState(stump.below()).is(BlockTags.DIRT)) continue;
            var inv = assistant.getInventoryItems();
            boolean done = false;
            for (int i = 0; i < inv.size() && !done; i++) {
                ItemStack s = inv.get(i);
                if (s.isEmpty() || !s.is(net.minecraft.tags.ItemTags.SAPLINGS)) continue;
                if (s.getItem() instanceof net.minecraft.world.item.BlockItem sapling) {
                    assistant.level().setBlockAndUpdate(stump, sapling.getBlock().defaultBlockState());
                    s.shrink(1);
                    if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                    planted++;
                    done = true;
                }
            }
        }
        stumps.clear();
        if (planted > 0) {
            assistant.say("Replanted " + planted + " sapling" + (planted == 1 ? "" : "s") + ".");
        }
    }

    /** Pack filled mid-job: stash, then come back for the rest — automatically. */
    private void interjectDeposit() {
        int remaining = request != null ? request.amount() - collected : 0;
        GatherGoal.Kind kind = request != null ? request.kind() : null;
        replantStumps();
        assistant.say("Pack's full — stashing, then I'll get the rest"
            + (remaining > 0 ? " (" + remaining + " to go)" : "") + ".");
        assistant.pollJob(); // remove our job...
        if (remaining > 0 && kind != null) {
            assistant.enqueueFront(com.jrpetty.mcassistant.entity.Job.gather(kind, remaining));
        }
        assistant.enqueueFront(com.jrpetty.mcassistant.entity.Job.deposit());
        this.request = null; // silent stop (no "Paused" message)
        this.myJob = null;
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
            builtBlocks = 0;
            buildTicks = 0;
            pillarBase = null;
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
            // Can't just WALK there after a couple of seconds? Make a way to it:
            // dig through the blocks in the way (e.g. the dirt over the stone),
            // pillar up to a higher block, or bridge a gap — instead of standing
            // there looking at it. Digging needs no blocks; pillar/bridge use
            // carried ones. Capped by time and block-count so it can't run away.
            if (stuckTicks > 40 && buildTicks < 300 && builtBlocks < 32
                && buildToward(targetPos)) {
                buildTicks++;
                return; // actively making a path — don't count this as stuck time
            }
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.1D);
            }
            // Truly can't get there even by building (no blocks, or building maxed
            // out): blacklist it so findNearest picks something else (no infinite crawl).
            if (++stuckTicks > 200) {
                unreachable.add(targetPos);
                targetPos = null;
                pillarBase = null;
                builtBlocks = 0;
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
        // Chopping the bottom log of a tree leaves a stump spot to replant.
        if (request.kind() == Kind.LOGS
            && assistant.level().getBlockState(pos.below()).is(BlockTags.DIRT)) {
            stumps.add(pos.immutable());
        }
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            collected++;
            assistant.damageHeldTool(); // tools wear like a player's
            sweepDrops(pos);
            if (assistant.isPackFull() && collected < request.amount()) {
                interjectDeposit();
            }
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

    /**
     * Make a way to a target we can't walk to:
     *   - solid terrain in the way (e.g. the dirt over the stone) -> dig a 2-high
     *     tunnel straight toward it and step in;
     *   - target above us with the way clear -> pillar up under our feet;
     *   - target level with us across a gap -> bridge one block toward it.
     * Digging costs no blocks; pillaring/bridging spend carried building blocks.
     * Never breaks into lava/water. Returns true if it acted toward the target.
     */
    private boolean buildToward(BlockPos target) {
        BlockPos feet = assistant.feetPos();
        int dy = target.getY() - feet.getY();
        Direction dir = towardDir(feet, target);
        BlockPos ahead = feet.relative(dir);       // block ahead at foot level
        BlockPos aheadHead = ahead.above();        // block ahead at head level

        int hd = Math.max(Math.abs(target.getX() - feet.getX()), Math.abs(target.getZ() - feet.getZ()));

        // Target basically overhead: pillar straight up rather than tunnel sideways.
        if (dy >= 2 && hd <= 1) return pillarUp(feet);

        // 1) Dig through blocks in the way toward it — the "break the dirt over
        //    the stone" case. Clear head then foot level so we can step forward.
        if (solid(aheadHead) && digThrough(aheadHead)) return true;
        if (solid(ahead) && digThrough(ahead)) {
            assistant.getNavigation().moveTo(ahead.getX() + 0.5, ahead.getY(), ahead.getZ() + 0.5, 1.0D);
            return true;
        }
        // Target below and the floor ahead is in the way -> dig down a step.
        if (dy <= -1 && solid(ahead.below()) && digThrough(ahead.below())) return true;

        // 2) Higher than us and the way ahead is now clear: pillar up to gain height.
        if (dy >= 2) return pillarUp(feet);

        // 3) A gap ahead at our level: bridge one block across toward it.
        BlockPos floorAhead = ahead.below();
        if (dy <= 1 && dy >= -2 && !solid(ahead)
            && assistant.countMatching(NightShelterGoal.SHELTER_BLOCK) > 0
            && assistant.level().getBlockState(floorAhead).canBeReplaced()) {
            assistant.getLookControl().setLookAt(
                floorAhead.getX() + 0.5, floorAhead.getY() + 0.5, floorAhead.getZ() + 0.5);
            if (placeBlock(floorAhead)) {
                builtBlocks++;
                assistant.getNavigation().moveTo(ahead.getX() + 0.5, ahead.getY(), ahead.getZ() + 0.5, 1.0D);
                return true;
            }
        }
        return false;
    }

    /** Rise one level under our feet toward a higher target, clearing any ceiling
     *  first. Uses a carried building block; returns true if it acted (false if
     *  it has none to pillar with). */
    private boolean pillarUp(BlockPos feet) {
        if (assistant.countMatching(NightShelterGoal.SHELTER_BLOCK) == 0) return false;
        assistant.getNavigation().stop();
        BlockPos aboveHead = feet.above(2);
        if (solid(aboveHead)) { digThrough(aboveHead); return true; } // clear the ceiling first
        assistant.getLookControl().setLookAt(feet.getX() + 0.5, feet.getY() - 1.0, feet.getZ() + 0.5);
        if (assistant.onGround()) {
            pillarBase = feet;
            assistant.getJumpControl().jump();
            return true;
        }
        if (pillarBase != null && assistant.getY() - pillarBase.getY() > 0.45
            && assistant.level().getBlockState(pillarBase).canBeReplaced()) {
            if (placeBlock(pillarBase)) { pillarBase = null; builtBlocks++; }
        }
        return true;
    }

    /** Break one obstructing block (never next to lava/water), sweep its drop,
     *  and look at it. Returns true if it broke something. */
    private boolean digThrough(BlockPos p) {
        if (nearLiquid(p)) return false; // don't flood ourselves in
        BlockState st = assistant.level().getBlockState(p);
        if (!st.isSolid()) return false;
        if (assistant.level().getBlockEntity(p) != null) return false; // don't smash chests/etc.
        assistant.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        assistant.equipBestTool(st);
        if (assistant.level().destroyBlock(p, true, assistant)) {
            assistant.swing(InteractionHand.MAIN_HAND);
            sweepDrops(p);
            return true;
        }
        return false;
    }

    private boolean nearLiquid(BlockPos p) {
        for (Direction d : Direction.values()) {
            if (!assistant.level().getFluidState(p.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    /** Place one carried building block at p (from the shelter-block pool). */
    private boolean placeBlock(BlockPos p) {
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.get(i);
            if (st.isEmpty() || !NightShelterGoal.SHELTER_BLOCK.test(st)) continue;
            if (!(st.getItem() instanceof BlockItem bi)) continue;
            assistant.level().setBlockAndUpdate(p, bi.getBlock().defaultBlockState());
            assistant.swing(InteractionHand.MAIN_HAND);
            st.shrink(1);
            if (st.isEmpty()) inv.set(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    private boolean solid(BlockPos p) {
        return assistant.level().getBlockState(p).isSolid();
    }

    private static Direction towardDir(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
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
