package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.FolkCombat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * [pa38] A step aside for a clear shot. With its player (or anyone it mustn't hit) in the line of fire, a throw, a bow
 * or a crossbow is held, never loosed through them (FolkCombat.clearShot, the game's rule for every shooter in the mod).
 * A player in that spot would take a step or two to the side. So does the assistant: a standable spot one to four blocks
 * off the line, out of liquid and away from any drop, still at the range it was fighting at, with a clear line from it.
 * It walks there, then the held shot goes when the line is clear. Never when told to stay or to guard a spot (there it
 * waits, as before). One spot per try, kept till it is reached (no dithering between two), a second try if the first
 * doesn't clear the line, and then it gives up for a while and waits or fights up close, as before.
 *
 * <p>By level (PaKnack): a learner stands in the way a second and a half before it notices, and picks a worse spot (a
 * longer walk, a line that only just clears); a practised one notices in a fifth of a second and picks a better angle.
 */
final class PaSidestep {

    private PaSidestep() {}

    /** A held shot asked within this many ticks counts as still held. */
    static final int FRESH = 4;
    /** A step's whole time (ticks) before it gives up. */
    static final int LIMIT = 70;
    /** After giving up, this long (ticks) before it tries again: it waits, or fights up close, as before. */
    static final int REST = 100;
    /** Between one step and the next. */
    static final int GAP = 20;
    /** Within this of its player it doesn't set off after them (FollowOwnerGoal starts at four blocks): a spot past it is worth less. */
    static final double FOLLOW_NEAR = 3.5;

    /** May it move for a shot at all: never when told to stay, never when guarding a spot or holding a post. */
    static boolean mayMove(PlayerAssistantEntity a) {
        return a.getMode() != AssistantEntity.Mode.STAY && a.mind.post == null && !a.holdingAPost() && !a.onWatch()
            && !a.movementBlocked() && !a.isPassenger() && !a.isRetreating();
    }

    /**
     * The shot's own check, with a word to its feet: true when the line is clear (the game's rule), else false, and the
     * hold noted (since when, at what, the range to keep) for the step aside.
     */
    static boolean clear(PlayerAssistantEntity a, LivingEntity t, double min, double max) {
        PaField f = a.mind.field;
        if (FolkCombat.clearShot(a, t)) {
            f.sideSince = -100000;
            return true;
        }
        if (a.tickCount - f.sideAsked > FRESH || f.sideTarget != t.getId()) f.sideSince = a.tickCount;   // a new hold
        f.sideAsked = a.tickCount;
        f.sideTarget = t.getId();
        f.sideMin = min;
        f.sideMax = max;
        return false;
    }

    /** Is it stepping aside right now (a held throw keeps its aim meanwhile)? */
    static boolean busy(PlayerAssistantEntity a) {
        PaField f = a.mind.field;
        return a.tickCount - f.sideAsked <= FRESH && a.tickCount >= f.sideRestUntil && mayMove(a);
    }

    // ------------------------------------------------------------------ where to stand

    /** Feet here, one up or one down: room for it, solid and dry underfoot, no drop beside it, nothing that hurts. Null if none. */
    @Nullable
    static BlockPos stand(PlayerAssistantEntity a, BlockPos base) {
        Level lv = a.level();
        for (int dy : new int[]{0, 1, -1}) {
            BlockPos feet = base.above(dy), head = feet.above(), below = feet.below();
            if (!lv.getBlockState(feet).getCollisionShape(lv, feet).isEmpty() || !lv.getBlockState(head).getCollisionShape(lv, head).isEmpty()) continue;
            if (!lv.getFluidState(feet).isEmpty() || !lv.getFluidState(head).isEmpty() || !lv.getFluidState(below).isEmpty()) continue;
            if (lv.getBlockState(below).getCollisionShape(lv, below).isEmpty()) continue;
            if (WalkNodeEvaluator.getPathTypeStatic(a, feet) != PathType.WALKABLE) continue;
            boolean drop = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = feet.relative(d);
                if (lv.getBlockState(n).getCollisionShape(lv, n).isEmpty() && lv.getBlockState(n.below()).getCollisionShape(lv, n.below()).isEmpty()
                    && lv.getBlockState(n.below(2)).getCollisionShape(lv, n.below(2)).isEmpty()) { drop = true; break; }
                if (lv.getFluidState(n).is(net.minecraft.tags.FluidTags.LAVA) || lv.getFluidState(n.below()).is(net.minecraft.tags.FluidTags.LAVA)) { drop = true; break; }
            }
            if (!drop) return feet;
        }
        return null;
    }

    /** Anyone a shot must never go through: a player, an assistant, a villager. */
    static boolean spare(LivingEntity e) {
        return e instanceof Player || e instanceof AssistantEntity || e instanceof AbstractVillager;
    }

    /**
     * How much room the line from this eye to the target leaves anyone it mustn't hit (blocks, measured as the game's
     * rule measures it, to the middle of them); -1 when a block is in the way; 9 when nobody is near it.
     */
    static double room(PlayerAssistantEntity a, Vec3 eye, LivingEntity t) {
        Vec3 to = t.position().add(0, t.getBbHeight() * 0.5, 0);
        if (a.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, a)).getType() != HitResult.Type.MISS) return -1;
        Vec3 d = to.subtract(eye);
        double best = 9;
        for (LivingEntity e : a.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, to).inflate(3.0), x -> x != a && x != t && x.isAlive() && spare(x))) {
            Vec3 p = e.position().add(0, e.getBbHeight() * 0.5, 0);
            double k = Math.max(0, Math.min(1, p.subtract(eye).dot(d) / Math.max(1e-6, d.lengthSqr())));
            best = Math.min(best, eye.add(d.scale(k)).distanceTo(p));
        }
        return best;
    }

    record Spot(BlockPos feet, double score) {}

    /**
     * The spot it steps to: one to four blocks either side of the line to the target (a little forward or back too),
     * standable, in the range band, with a clear line and the room its level wants. Scored on the walk and the room the
     * line leaves (a learner's sense of it noisier), then the best one it can actually walk to. Null if none.
     */
    @Nullable
    static BlockPos pick(PlayerAssistantEntity a, LivingEntity t, double min, double max, @Nullable BlockPos avoid, Random rnd) {
        int l = a.veteranLevel();
        Vec3 me = a.position(), at = t.position();
        Vec3 d = new Vec3(at.x - me.x, 0, at.z - me.z);
        if (d.lengthSqr() < 0.01) return null;
        d = d.normalize();
        Vec3 side = new Vec3(-d.z, 0, d.x);
        double need = PaKnack.sideMargin(l), judge = PaKnack.sideJudge(l);
        Player owner = a.getOwnerPlayer();
        List<Spot> spots = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int s : new int[]{1, -1}) for (int off = 1; off <= 4; off++) for (double fwd : new double[]{0, -0.9, 0.9}) {
            Vec3 c = me.add(side.scale(s * off)).add(d.scale(fwd));
            BlockPos feet = stand(a, BlockPos.containing(c.x, me.y + 0.5, c.z));
            if (feet == null || feet.equals(a.blockPosition()) || feet.equals(avoid) || !seen.add(feet)) continue;
            Vec3 foot = Vec3.atBottomCenterOf(feet);
            double dist = foot.distanceTo(at);
            if (dist < min || dist > max) continue;
            double room = room(a, foot.add(0, a.getEyeHeight(), 0), t);
            if (room < need) continue;
            double score = foot.distanceTo(me) + 1.5 * Math.max(0, 2.0 - room) + 0.5 * Math.abs(foot.y - me.y) + rnd.nextGaussian() * judge * 1.5;
            if (owner != null) score += Math.max(0, foot.distanceTo(owner.position()) - FOLLOW_NEAR);   // not so far it walks straight back to you
            spots.add(new Spot(feet, score));
        }
        spots.sort((x, y) -> Double.compare(x.score(), y.score()));
        for (int i = 0; i < Math.min(3, spots.size()); i++) {
            BlockPos feet = spots.get(i).feet();
            Path p = a.getNavigation().createPath(feet, 0);
            if (p != null && p.canReach() && p.getNodeCount() <= 10) return feet;
        }
        return null;
    }

    // ------------------------------------------------------------------ the step

    /** The step itself: a goal of its own (its feet), while the held shot keeps its aim and goes once the line is clear. */
    static final class Goal extends net.minecraft.world.entity.ai.goal.Goal {

        private final PlayerAssistantEntity a;
        private final Random rnd = new Random();
        @Nullable private LivingEntity target;
        @Nullable private BlockPos cell;
        private int startAt, arrivedAt, tries;
        private boolean done;
        @Nullable private Vec3 from;
        private String why = "";

        Goal(PlayerAssistantEntity a) {
            this.a = a;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (a.level().isClientSide) return false;
            PaField f = a.mind.field;
            int now = a.tickCount;
            if (now - f.sideAsked > FRESH || now < f.sideRestUntil || now - f.sideEnded < GAP) return false;
            if (now - f.sideSince < PaKnack.sideNotice(a.veteranLevel())) return false;          // it hasn't noticed yet
            if (!mayMove(a)) return false;
            Entity e = a.level().getEntity(f.sideTarget);
            if (!(e instanceof LivingEntity le) || !le.isAlive()) return false;
            BlockPos c = pick(a, le, f.sideMin, f.sideMax, f.sideLast, rnd);
            if (c == null) {                                                                  // nowhere better: wait, as before
                f.sideRestUntil = now + REST;
                f.sideGiveUps++;
                f.sideEnd = "nowhere";
                return false;
            }
            target = le;
            cell = c;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return !done && cell != null && target != null && target.isAlive() && mayMove(a) && a.tickCount - startAt < LIMIT;
        }

        @Override
        public void start() {
            startAt = a.tickCount;
            from = a.position();
            why = "";
            arrivedAt = -1;
            tries = 1;
            done = false;
            walk();
            PaField f = a.mind.field;
            if (a.tickCount - f.sideQuipAt > 1200 && rnd.nextInt(3) == 0) {
                f.sideQuipAt = a.tickCount;
                PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.sidestep"), true);
            }
        }

        /** To the spot itself (a path to the block, not to within a block of it, which is where a plain walk stops). */
        private void walk() {
            if (cell == null) return;
            Path p = a.getNavigation().createPath(cell, 0);
            if (p != null) a.getNavigation().moveTo(p, 1.0 + 0.03 * a.veteranLevel());
        }

        @Override
        public void tick() {
            LivingEntity t = target;
            BlockPos c = cell;
            if (t == null || c == null) return;
            if (!PaBow.drawing(a) && a.mind.xbow == null) a.getLookControl().setLookAt(t, 30.0F, 30.0F);
            double dh = Math.hypot(a.getX() - (c.getX() + 0.5), a.getZ() - (c.getZ() + 0.5));
            if (arrivedAt < 0) {
                if (a.onGround() && room(a, a.getEyePosition(), t) >= PaKnack.sideMargin(a.veteranLevel()) && FolkCombat.clearShot(a, t)) {
                    a.getNavigation().stop();                                                // clear already, part way: no need to go further
                    done = true;
                    why = "clear on the way";
                    a.mind.field.sideSteps++;
                    return;
                }
                if (dh < 0.45 || dh < 0.9 && a.getNavigation().isDone()) {
                    a.getNavigation().stop();
                    arrivedAt = a.tickCount;
                } else if (a.getNavigation().isDone() || (a.tickCount - startAt) % 10 == 0) {
                    walk();
                }
                return;
            }
            if (FolkCombat.clearShot(a, t)) {                                                // there: the held shot goes now
                done = true;
                why = "clear";
                a.mind.field.sideSteps++;
                return;
            }
            if (a.tickCount - arrivedAt > 10) {                                              // still in the way (it moved?): once more
                BlockPos again = tries < 2 ? pick(a, t, a.mind.field.sideMin, a.mind.field.sideMax, c, rnd) : null;
                if (again == null) {
                    why = "still blocked";
                    cell = null;                                                             // give up (stop() notes it)
                    return;
                }
                cell = again;
                arrivedAt = -1;
                tries++;
                walk();
            }
        }

        @Override
        public void stop() {
            a.getNavigation().stop();
            PaField f = a.mind.field;
            f.sideEnded = a.tickCount;
            if (cell != null) f.sideLast = cell;
            if (!done && target != null && from != null && a.position().distanceTo(from) >= 0.8 && FolkCombat.clearShot(a, target)) {
                done = true;                                                                 // called off, but it got there in the end
                why = "clear";
                f.sideSteps++;
            }
            if (!done) {
                f.sideRestUntil = a.tickCount + REST;
                f.sideGiveUps++;
                if (why.isEmpty()) why = a.tickCount - startAt >= LIMIT ? "too long" : "called off";
            }
            f.sideEnd = why;
            target = null;
            cell = null;
        }
    }
}
