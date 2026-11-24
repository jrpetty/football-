package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Random;

/**
 * [pa38] Snowballs and eggs, from level 3 (the gear ladder's rung for throwing): the real thing, thrown as a player
 * throws one. It aims with its bow's eyes (PaArchery: its level's reaction, judgement of distance and lead, its head
 * turning no faster than it can, a snowball's own drag and drop) and lets go where its arm points, shaken by its hands.
 * A snowball does no harm, but the mob it hits turns on whoever threw it (the game's own rule) and is knocked back a
 * little: so it throws to pull a mob off its player (a creeper creeping up on them, anything after them while they're
 * hurt, or anything it can't reach in time), and to knock a mob at a ledge over the edge. Told "throw snowballs at that",
 * it throws at what you pointed at or pinged.
 */
final class PaThrowGoal extends Goal {

    /** A throw's wind-up (ticks): a hand action, and never quicker than its aim allows. */
    static final int WINDUP = 10;

    private final PlayerAssistantEntity a;
    @Nullable private LivingEntity target;
    @Nullable private PaArchery.Shooter shooter;
    private boolean ledge, ordered;
    private int startAt, lastCheck;
    private Random rnd = new Random();

    PaThrowGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    /** A snowball or an egg it may throw (its level allows it), in hand or pack: the item, or EMPTY. */
    static ItemStack ammo(PlayerAssistantEntity a, String want) {
        for (net.minecraft.world.item.Item it : want.equals("egg") ? new net.minecraft.world.item.Item[]{Items.EGG}
                : want.equals("snowball") ? new net.minecraft.world.item.Item[]{Items.SNOWBALL} : new net.minecraft.world.item.Item[]{Items.SNOWBALL, Items.EGG}) {
            ItemStack probe = new ItemStack(it);
            if (!PaLevels.canUse(a, probe)) continue;
            if (a.getMainHandItem().is(it) || a.getOffhandItem().is(it) || a.countMatching(s -> s.is(it)) > 0) return probe;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse() {
        if (a.level().isClientSide || PaBow.drawing(a) || PaCrossbow.busy(a) || a.mind.trident != null) return false;
        PaField f = a.mind.field;
        if (a.tickCount - f.lastThrow < WINDUP + 4) return false;
        // told to: at what was pointed at, while there are throws left
        if (f.throwAt != null) {
            if (a.tickCount > f.throwUntil || f.throwsLeft <= 0) { f.throwAt = null; return false; }
            Entity e = ((ServerLevel) a.level()).getEntity(f.throwAt);
            if (!(e instanceof LivingEntity le) || !le.isAlive()) { f.throwAt = null; return false; }
            if (ammo(a, f.throwWith).isEmpty()) {
                PaTalk.reply(a, PaLogiTalk.line(a, "field.throw_none"));
                f.throwAt = null;
                return false;
            }
            double d = a.distanceTo(le);
            if (d > 20 || !a.hasLineOfSight(le)) return false;
            target = le;
            ordered = true;
            ledge = false;
            return true;
        }
        if (a.tickCount - lastCheck < 5) return false;
        lastCheck = a.tickCount;
        if (a.veteranLevel() < PaLevels.gearLevel(new ItemStack(Items.SNOWBALL)) || ammo(a, "").isEmpty()) return false;
        LivingEntity pick = pickIncident();
        if (pick == null) return false;
        target = pick;
        ordered = false;
        return true;
    }

    /**
     * Something worth a throw: a mob after its player that a snowball would turn (a creeper near them, anything after
     * them while they're hurt, anything it can't get to in time), or the mob it is fighting standing at a ledge.
     */
    @Nullable
    private LivingEntity pickIncident() {
        PaField f = a.mind.field;
        Player owner = a.getOwnerPlayer();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        boolean ranged = a.hasBow() && a.hasArrows();
        if (owner != null && owner.level() == a.level()) {
            for (Monster m : a.level().getEntitiesOfClass(Monster.class, a.getBoundingBox().inflate(16))) {
                if (!m.isAlive() || m.getTarget() != owner || !a.hasLineOfSight(m)) continue;
                double d = a.distanceTo(m);
                if (d < 3 || d > 16) continue;
                boolean creeper = m instanceof Creeper && m.distanceTo(owner) < 7;
                boolean hurt = owner.getHealth() <= owner.getMaxHealth() * 0.5F;
                boolean farOff = !ranged && d > 5;
                if (!(creeper || hurt || farOff)) continue;
                if (m.getUUID().equals(f.incidentMob) && a.tickCount - f.incidentAt < 200 && f.incidentThrows >= 3) continue;   // three tries, then other means
                double score = d - (creeper ? 10 : 0);
                if (score < bestD) { bestD = score; best = m; }
            }
        }
        if (best != null) { ledge = false; return best; }
        LivingEntity t = a.getTarget();
        if (t != null && t.isAlive() && !(t instanceof Player) && a.hasLineOfSight(t) && a.distanceTo(t) >= 3 && a.distanceTo(t) <= 14 && atLedge(a, t)) {
            ledge = true;
            return t;
        }
        return null;
    }

    /** Standing at an edge, with a drop of four or more just beyond it (away from the assistant): a knock sends it over. */
    static boolean atLedge(LivingEntity from, LivingEntity t) {
        Vec3 d = t.position().subtract(from.position()).multiply(1, 0, 1);
        if (d.lengthSqr() < 1e-4) return false;
        d = d.normalize();
        BlockPos beyond = BlockPos.containing(t.getX() + d.x * 0.9, t.getY() - 0.5, t.getZ() + d.z * 0.9);
        if (beyond.equals(t.blockPosition().below())) return false;
        for (int i = 0; i < 4; i++) {
            BlockPos p = beyond.below(i);
            if (!t.level().getBlockState(p).getCollisionShape(t.level(), p).isEmpty() || !t.level().getFluidState(p).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && shooter != null && a.tickCount - startAt < 40;
    }

    @Override
    public void start() {
        rnd = new Random(a.getRandom().nextLong());
        startAt = a.tickCount;
        LivingEntity t = target;
        if (t == null) return;
        PaField f = a.mind.field;
        if (!ordered) {
            if (!t.getUUID().equals(f.incidentMob) || a.tickCount - f.incidentAt > 200) { f.incidentMob = t.getUUID(); f.incidentThrows = 0; }
            f.incidentAt = a.tickCount;
        }
        shooter = new PaArchery.Shooter(PaArchery.row(a.veteranLevel()), rnd, a.getYHeadRot(), a.getXRot(), a.distanceTo(t), PaArchery.SNOWBALL.speed())
            .flight(PaArchery.SNOWBALL);
    }

    @Override
    public void stop() {
        shooter = null;
        target = null;
    }

    @Override
    public void tick() {
        LivingEntity t = target;
        if (t == null || shooter == null) return;
        int elapsed = a.tickCount - startAt;
        Vec3 eye = a.getEyePosition(), at = PaBow.middle(t);
        shooter.ticks = elapsed + Math.max(0, shooter.plannedDraw - WINDUP);       // a throw settles in a wind-up, not a draw
        shooter.see(eye.x, eye.y, eye.z, at.x, at.y, at.z);
        PaBow.face(a, shooter.yaw, shooter.pitch);
        if (elapsed < WINDUP || !shooter.ready()) return;
        if (!com.jrpetty.mcassistant.entity.FolkCombat.clearShot(a, t)) { if (elapsed > 30) shooter = null; return; }
        throwAt(t, shooter);
        shooter = null;
    }

    /** Let go: one snowball (or egg) from its pack, off where its arm points, at the game's speed for a throw. */
    void throwAt(LivingEntity t, PaArchery.Shooter s) {
        PaField f = a.mind.field;
        ItemStack kind = ammo(a, ordered ? f.throwWith : "");
        if (kind.isEmpty()) return;
        boolean egg = kind.is(Items.EGG);
        if (!a.getMainHandItem().is(kind.getItem()) && !a.getOffhandItem().is(kind.getItem()))
            a.removeMatching(st -> st.is(kind.getItem()), 1);
        else (a.getMainHandItem().is(kind.getItem()) ? a.getMainHandItem() : a.getOffhandItem()).shrink(1);
        ThrowableItemProjectile p = egg ? new ThrownEgg(a.level(), a) : new Snowball(a.level(), a);
        p.setItem(new ItemStack(kind.getItem()));
        double[] v = s.loose(a.getDeltaMovement().horizontalDistance());
        p.setDeltaMovement(v[0], v[1], v[2]);
        p.setYRot((float) (Math.atan2(v[0], v[2]) * 180.0 / Math.PI));
        p.setXRot((float) (Math.atan2(v[1], Math.hypot(v[0], v[2])) * 180.0 / Math.PI));
        p.getPersistentData().putUUID("pa_thrower", a.getUUID());
        a.level().addFreshEntity(p);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, a.getX(), a.getY(), a.getZ(), egg ? SoundEvents.EGG_THROW : SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5F,
            0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
        f.lastThrow = a.tickCount;
        f.throwsDone++;
        if (ordered) {
            if (--f.throwsLeft <= 0) f.throwAt = null;
        } else {
            f.incidentThrows++;
        }
        PaThrows.watch(a, p, t, ledge);
    }
}
