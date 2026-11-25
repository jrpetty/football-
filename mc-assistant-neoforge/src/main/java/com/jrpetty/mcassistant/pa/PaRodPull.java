package com.jrpetty.mcassistant.pa;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Random;

/**
 * [pa38] The fishing rod in a fight, from level 3: a cast at a mob (aimed by eye, the hook's own flight, its hands'
 * shake), and once the hook is in, a tug that pulls the mob towards it (the game's own pull: a tenth of the gap, as
 * speed). It does this to drag a mob standing at a ledge over the edge (it stands below, so the pull goes over the
 * drop), to bring in something it can't otherwise reach (it has no bow), or when told "hook that". Then the weapon
 * comes back out.
 */
final class PaRodPull extends Goal {

    private final PlayerAssistantEntity a;
    @Nullable private LivingEntity target;
    @Nullable private PaFishHook hook;
    @Nullable private ItemStack putAway;
    private int putSlot = -1, startAt, castAt, hookedAt;
    private boolean ordered;
    private Random rnd = new Random();

    PaRodPull(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK, Goal.Flag.MOVE));
    }

    static int rodSlot(PlayerAssistantEntity a) {
        if (a.getMainHandItem().is(Items.FISHING_ROD)) return -1;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).is(Items.FISHING_ROD)) return i;
        return -2;
    }

    @Override
    public boolean canUse() {
        if (a.level().isClientSide || PaBow.drawing(a) || PaCrossbow.busy(a) || a.mind.trident != null) return false;
        PaField f = a.mind.field;
        if (rodSlot(a) == -2 || !PaLevels.canUse(a, new ItemStack(Items.FISHING_ROD))) { f.hookOrder = null; return false; }
        if (a.peekJob() != null && a.peekJob().type() == com.jrpetty.mcassistant.entity.Job.Type.FISH) return false;   // fishing, not fighting
        LivingEntity t = null;
        ordered = false;
        if (f.hookOrder != null) {
            if (a.tickCount > f.hookUntil) { f.hookOrder = null; return false; }
            Entity e = ((ServerLevel) a.level()).getEntity(f.hookOrder);
            if (e instanceof LivingEntity le && le.isAlive()) { t = le; ordered = true; }
            else { f.hookOrder = null; return false; }
        } else {
            if (a.tickCount - f.pullAt < 80) return false;
            LivingEntity cur = a.getTarget();
            if (cur == null || !cur.isAlive() || cur instanceof Player) return false;
            double d = a.distanceTo(cur);
            boolean ledge = d >= 3 && d <= 9 && cur.getY() > a.getY() + 2.5 && PaThrowGoal.atLedge(a, cur);   // it stands below: the pull goes over
            boolean reach = d >= 5 && d <= 10 && !(a.hasBow() && a.hasArrows()) && cur.getY() > a.getY() - 1.5;
            if (!ledge && !reach) return false;
            t = cur;
        }
        double d = a.distanceTo(t);
        if (d > 12 || d < 2.5 || !a.hasLineOfSight(t)) return false;
        target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && a.tickCount - startAt < 80;
    }

    @Override
    public void start() {
        rnd = new Random(a.getRandom().nextLong());
        startAt = a.tickCount;
        castAt = -1;
        hookedAt = -1;
        hook = null;
        int s = rodSlot(a);
        putAway = null;
        putSlot = -1;
        if (s >= 0) {                                                        // the rod into its hand for the moment
            putAway = a.getMainHandItem().copy();
            putSlot = s;
            a.setItemSlot(EquipmentSlot.MAINHAND, a.getInventoryItems().get(s).copy());
            a.getInventoryItems().set(s, putAway);
        }
        a.getNavigation().stop();
        if (ordered) a.mind.field.hookOrder = null;
    }

    @Override
    public void stop() {
        if (hook != null && !hook.isRemoved()) hook.discard();
        hook = null;
        // the weapon back in hand, the rod back in the pack where it came from
        if (putSlot >= 0 && a.getMainHandItem().is(Items.FISHING_ROD) && putSlot < a.getInventoryItems().size()) {
            ItemStack rod = a.getMainHandItem().copy();
            ItemStack back = a.getInventoryItems().get(putSlot);
            a.setItemSlot(EquipmentSlot.MAINHAND, back.copy());
            a.getInventoryItems().set(putSlot, rod);
        }
        putSlot = -1;
        putAway = null;
        target = null;
        a.mind.field.pullAt = a.tickCount;
    }

    @Override
    public void tick() {
        LivingEntity t = target;
        if (t == null) return;
        Vec3 eye = a.getEyePosition(), at = PaBow.middle(t);
        double dx = at.x - eye.x, dz = at.z - eye.z;
        double yaw = PaArchery.yawTo(dx, dz), pitch = PaKnack.hookPitch(Math.hypot(dx, dz) + 0.4, at.y - eye.y);
        if (castAt < 0) {
            PaBow.face(a, yaw, pitch);
            if (a.tickCount - startAt < Math.max(8, PaArchery.row(a.veteranLevel()).reactionTicks())) return;   // eyes onto it
            double sway = PaKnack.castSway(a.veteranLevel());
            PaBow.face(a, yaw + rnd.nextGaussian() * sway, pitch + rnd.nextGaussian() * sway);
            a.swing(InteractionHand.MAIN_HAND);
            a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
            hook = PaFishHook.cast(a, 0, 0);
            a.level().addFreshEntity(hook);
            castAt = a.tickCount;
            return;
        }
        if (hook == null || hook.isRemoved()) { target = null; return; }
        a.getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (hook.getHookedIn() == t) {
            if (hookedAt < 0) hookedAt = a.tickCount;
            if (a.tickCount - hookedAt >= 4) {                                 // a beat, then the tug
                PaFishHook.Reel r = hook.reel(a.getMainHandItem());
                hook = null;
                if (r.damage() > 0) a.getMainHandItem().hurtAndBreak(r.damage(), a, EquipmentSlot.MAINHAND);
                a.swing(InteractionHand.MAIN_HAND);
                a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0F, 0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
                a.mind.field.pulls++;
                PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.hook_pull", "item", PaThrows.mob(t)), true);
                target = null;
            }
            return;
        }
        if (hook.getHookedIn() != null || hook.onGround() || hook.bobbing() || a.tickCount - castAt > 30) {   // missed: in, and once more if told
            hook.reel(a.getMainHandItem());
            hook = null;
            target = null;
        }
    }
}
