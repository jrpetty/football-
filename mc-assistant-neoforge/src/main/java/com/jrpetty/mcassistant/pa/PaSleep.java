package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;

/**
 * [pa33d] Bedtime, as a player's: at night it sleeps in its bed when its player sleeps nearby, when told "go to bed",
 * or (with "Shelter at night" on) when there's nothing to do and it's near its player or home. Lying down properly, the
 * odd snore or sleep-talk. It wakes in the morning, when its player gets up, when it's hurt or a monster comes close,
 * or when told "wake up". Only players count for skipping the night, so it never holds anyone's sleep up.
 */
public final class PaSleep {

    private PaSleep() {}

    /** Every half second, from its brain. */
    static void tick(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        PaBrain.Mind m = a.mind;
        ServerPlayer owner = a.getOwnerPlayer() instanceof ServerPlayer sp && sp.level() == a.level() ? sp : null;
        boolean night = night(a.level());
        boolean ownerAsleep = owner != null && owner.isSleeping();
        if (a.isSleeping()) {
            String why = wakeReason(a, m, owner, night, ownerAsleep);
            if (why != null) { wake(a, why); return; }
            if (a.tickCount - m.sleepTalkAt > 500 && a.getRandom().nextInt(4) == 0 && a.mute() < 2) {
                m.sleepTalkAt = a.tickCount;
                PaTalk.reply(a, PaTalk.line(a, "sleep_talk", "owner", owner == null ? "you" : owner.getGameProfile().getName()));
            }
            m.ownerWasAsleep = ownerAsleep;
            return;
        }
        m.ownerWasAsleep = ownerAsleep;
        if (!night) { m.bedtime = false; a.sleepWanted = false; return; }
        boolean nearOwner = owner != null && a.distanceToSqr(owner) < 32 * 32;
        BlockPos home = PaOrders.homeOf(a);
        boolean nearHome = home != null && a.blockPosition().closerThan(home, 20);
        boolean want = m.bedtime || ownerAsleep && a.distanceToSqr(owner) < 48 * 48
            || r.sleepAtNight && a.plan.idle() && (nearOwner || nearHome);
        if (!want || a.getTarget() != null && a.getTarget().isAlive() || danger(a)) { a.sleepWanted = false; return; }
        if (a.getTarget() != null) a.setTarget(null);                       // a target that is gone
        BlockPos bed = bed(a, r);
        if (bed == null) { a.sleepWanted = false; return; }
        a.sleepWanted = true;
        double d = a.blockPosition().distSqr(bed);
        if (d > 4.0) {
            if (a.getNavigation().isDone() || a.tickCount % 40 == 0) a.getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 1.0);
            return;
        }
        a.getNavigation().stop();
        a.clearQueue();
        a.startSleeping(bed);
        m.sleepTalkAt = a.tickCount;
    }

    /** Why it should get up now, or null to sleep on. */
    @Nullable
    static String wakeReason(PlayerAssistantEntity a, PaBrain.Mind m, @Nullable ServerPlayer owner, boolean night, boolean ownerAsleep) {
        if (!night) return "morning";
        if (a.getLastHurtByMob() != null && a.tickCount - a.getLastHurtByMobTimestamp() < 40) return "danger";
        if (danger(a)) return "danger";
        if (m.ownerWasAsleep && !ownerAsleep && owner != null && a.distanceToSqr(owner) < 48 * 48) return "owner";
        if (!a.sleepWanted) return "told";
        return null;
    }

    /** Night by the clock, as beds go (from dusk till dawn: the hours a player may sleep). */
    static boolean night(net.minecraft.world.level.Level level) {
        long t = level.getDayTime() % 24000L;
        return t >= 12542 && t <= 23459 || level.isThundering();
    }

    /** A monster close by, that it can see. */
    static boolean danger(PlayerAssistantEntity a) {
        return !a.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, a.getBoundingBox().inflate(10.0),
            e -> e instanceof Enemy && e.isAlive() && a.hasLineOfSight(e)).isEmpty();
    }

    /** Its bed: the last it slept in, else the one it was given; a whole, free bed only. */
    @Nullable
    static BlockPos bed(PlayerAssistantEntity a, PaData.Rec r) {
        for (BlockPos b : new BlockPos[]{r.lastBed, r.bed}) {
            if (b == null || !a.level().isLoaded(b)) continue;
            BlockState st = a.level().getBlockState(b);
            if (!(st.getBlock() instanceof BedBlock)) continue;
            if (st.getValue(BlockStateProperties.OCCUPIED) && !(a.isSleeping() && a.getSleepingPos().map(b::equals).orElse(false))) continue;
            return b;
        }
        return null;
    }

    static void wake(PlayerAssistantEntity a, String why) {
        a.sleepWanted = false;
        a.mind.bedtime = false;
        a.plan.paused = false;
        if (a.isSleeping()) a.stopSleeping();
        String key = switch (why) { case "danger" -> "woke_danger"; case "morning", "owner" -> "woke_morning"; default -> "woke_told"; };
        if (!why.equals("owner") || a.getRandom().nextInt(2) == 0) PaTalk.reply(a, PaTalk.line(a, key));
    }

    /** "Go to bed": now if it's night (and it has a bed), else at dark. The line to say. */
    static String goToBed(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return "";
        if (bed(a, r) == null) return PaTalk.line(a, "bed_none");
        if (!night(a.level())) { r.sleepAtNight = true; PaData.touch(); return PaTalk.line(a, "bed_day"); }
        a.mind.bedtime = true;
        a.plan.paused = true;
        return PaTalk.line(a, "bed_now");
    }

    /** "Wake up". */
    static String wakeUp(PlayerAssistantEntity a) {
        a.mind.bedtime = false;
        if (!a.isSleeping()) return PaTalk.line(a, "awake_already");
        a.sleepWanted = false;
        a.stopSleeping();
        a.plan.paused = false;
        return PaTalk.line(a, "woke_told");
    }
}
