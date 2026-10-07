package com.jrpetty.mcassistant.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Pose;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [individual] How a folk carries itself and how it sounds.
 *
 * <ul>
 * <li><b>Its gait</b>: a child skips; the old walk bent, short-stepped, with the stick; the proud (the leader, the
 *     wealthy, a cheerful hard worker) stride, chin up; the shy keep their heads down; the tired drag their feet at the
 *     end of a long day, or with a cold, or hungry.</li>
 * <li><b>Its mood in its posture</b>: a miserable folk's head hangs; a happy one looks about it.</li>
 * <li><b>Its idle moments</b>: a stretch in the morning, a yawn of an evening, a scratch of the head when it is stuck
 *     for something, arms crossed when it is cross, a wave to a friend going by (and the friend waves back), a laugh
 *     at a joke shared, a foot tapped to a tune at the well; the pipe, the book and the whittling of its habits.</li>
 * <li><b>Its voice</b>: its own pitch, by its sex, its years and its size (children high, the old lower, the big
 *     deeper), on every sound it makes, the hurt, the ambient and the little sound it makes when it speaks; and one of a
 *     few kinds of voice for its idle sounds, a hum, a murmur, a grunt, a chirp, as its nature runs.</li>
 * </ul>
 * Worked out on the server every half-second and sent as one number (VillageFolkEntity.showManner), and only when it
 * changes: the client poses the model from it (client/FolkPoses).
 */
public final class Manner {

    private Manner() {}

    public static final int WALK = 0, SKIP = 1, OLD = 2, PROUD = 3, SHY = 4, TIRED = 5;
    public static final int NEUTRAL = 0, SAD = 1, HAPPY = 2;
    public static final int NO_IDLE = 0, STRETCH = 1, YAWN = 2, SCRATCH = 3, CROSSED = 4, WAVE = 5, LAUGH = 6, TAP = 7,
        PIPE = 8, READ = 9, WHITTLE = 10;

    /** A moment, and when it ends. */
    private record Moment(int kind, int until) {}

    private static final Map<UUID, Moment> NOW = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STRETCHED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> WAVED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SPOKE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        NOW.clear();
        STRETCHED.clear();
        WAVED.clear();
        SPOKE.clear();
    }

    public static int pack(int gait, int posture, int idle, boolean bare) {
        return gait & 7 | (posture & 3) << 3 | (idle & 15) << 5 | (bare ? 1 << 9 : 0);
    }

    /** Its work hat off: of an evening, on the day of rest, out of the rain (the client leaves the trade's hat off). */
    public static boolean bareOf(int m) { return (m >>> 9 & 1) != 0; }

    public static int gaitOf(int m) { return m & 7; }
    public static int postureOf(int m) { return m >>> 3 & 3; }
    public static int idleOf(int m) { return m >>> 5 & 15; }

    /** A moment for so many ticks (NO_IDLE: over now), sent at once. */
    public static void idle(VillageFolkEntity f, int kind, int ticks) {
        if (f.level().isClientSide) return;
        if (kind == NO_IDLE) NOW.remove(f.getUUID());
        else NOW.put(f.getUUID(), new Moment(kind, f.tickCount + ticks));
        send(f);
    }

    /** A laugh at something shared (Smalltalk): its head back, and a little burst of good cheer over it. */
    public static void laugh(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return;
        idle(f, LAUGH, 40);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + f.getBbHeight() + 0.3, f.getZ(), 4, 0.25, 0.15, 0.25, 0.0);
        f.playSound(SoundEvents.VILLAGER_YES, 0.5F, pitch(f) * 1.15F);
    }

    /** Every ten ticks (Individual.tick): its gait, its posture, the moment it is having. */
    static void tick(VillageFolkEntity f, ServerLevel level) {
        Moment m = NOW.get(f.getUUID());
        if (m != null && f.tickCount >= m.until()) NOW.remove(f.getUUID());
        if (!NOW.containsKey(f.getUUID()) && !f.isSleeping()) moment(f, level);
        send(f);
    }

    private static void send(VillageFolkEntity f) {
        Moment m = NOW.get(f.getUUID());
        f.showManner(pack(gait(f), posture(f), m == null ? NO_IDLE : m.kind(), bareheaded(f)));
    }

    /** Folk stood up to be looked at with their hats off (/village individual stage). */
    private static final java.util.Set<UUID> BARE = ConcurrentHashMap.newKeySet();

    public static void bareForStage(VillageFolkEntity f) {
        BARE.add(f.getUUID());
        send(f);
    }

    /** Off work of an evening or on the day of rest, and not out in the rain: its work hat off, its own hair to see. */
    public static boolean bareheaded(VillageFolkEntity f) {
        if (BARE.contains(f.getUUID())) return true;
        if (f.isBaby() || f.isSleeping() || f.isShowcase()) return false;
        if (f.level().isRaining() && f.level().canSeeSky(f.blockPosition())) return false;
        if (!f.offWorkNow() || f.stationTask() == AssistantEntity.StationTask.GUARD && f.onShift()) return false;
        long dt = f.level().getDayTime(), tod = dt % 24000L;
        return tod >= 11800L || RestDay.today(f.ownerId(), dt / 24000L) || Habits.busy(f);
    }

    /** Its gait, now. */
    public static int gait(VillageFolkEntity f) {
        if (f.isBaby()) return SKIP;
        if (oldWalker(f)) return OLD;
        long tod = f.level().getDayTime() % 24000L;
        if (f.health().ill() || f.meals().missedInRow() >= 2 || tod >= 11000L && tod < 14000L && heavyTrade(f)) return TIRED;
        Social.Life life = f.life();
        if (f.isElder() || Wealth.tier(f) == Wealth.Tier.WEALTHY || life.has(Social.Trait.HARDWORKING) && life.has(Social.Trait.CHEERFUL)) return PROUD;
        if (life.has(Social.Trait.SHY)) return SHY;
        return WALK;
    }

    private static boolean heavyTrade(VillageFolkEntity f) {
        var t = f.stationTask();
        return t == AssistantEntity.StationTask.MINE || t == AssistantEntity.StationTask.WOOD || t == AssistantEntity.StationTask.SMITH
            || t == AssistantEntity.StationTask.HAUL || t == AssistantEntity.StationTask.FARM || t == AssistantEntity.StationTask.CAVE;
    }

    public static int posture(VillageFolkEntity f) {
        if (!f.persona().rolled()) return NEUTRAL;
        int mood = f.persona().mood();
        return mood < 35 ? SAD : mood > 78 ? HAPPY : NEUTRAL;
    }

    /** The old, with their stick: a shorter step, and a little slower on their own errands. */
    public static boolean oldWalker(VillageFolkEntity f) {
        return !f.isBaby() && (f.ageYears() >= 75 || Keepsakes.hasStick(f));
    }

    /** The idle moments that come of themselves: only standing still, now and then. */
    private static void moment(VillageFolkEntity f, ServerLevel level) {
        if (!f.getNavigation().isDone() || f.getPose() == Pose.SITTING || f.getTarget() != null) {
            if (f.tickCount % 40 == 0) waveToAFriend(f, level);
            return;
        }
        var r = f.getRandom();
        long tod = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (tod < 1600L && !f.isBaby() && STRETCHED.getOrDefault(f.getUUID(), -1L) != day) {
            STRETCHED.put(f.getUUID(), day);
            NOW.put(f.getUUID(), new Moment(STRETCH, f.tickCount + 50));
            return;
        }
        if (tod >= f.bedtimeTick() - 1600L && tod < f.bedtimeTick() && r.nextInt(30) == 0) {
            NOW.put(f.getUUID(), new Moment(YAWN, f.tickCount + 45));
            if (r.nextBoolean()) f.playSound(SoundEvents.VILLAGER_AMBIENT, 0.35F, pitch(f) * 0.8F);
            return;
        }
        if (f.clientStatus().startsWith("Needs") && r.nextInt(16) == 0) {
            NOW.put(f.getUUID(), new Moment(SCRATCH, f.tickCount + 60));
            return;
        }
        if ((f.quarrelledOn() == day || f.persona().rolled() && f.persona().mood() < 30) && r.nextInt(12) == 0) {
            NOW.put(f.getUUID(), new Moment(CROSSED, f.tickCount + 100));
            return;
        }
        if (f.tickCount % 40 == 0) waveToAFriend(f, level);
    }

    /** A friend or its partner going by within a few steps: a wave, and one back. Once in two minutes a pair. */
    private static void waveToAFriend(VillageFolkEntity f, ServerLevel level) {
        if (f.isSleeping()) return;
        UUID[] who = {f.life().partner(), f.life().bestFriend()};
        for (UUID id : who) {
            if (id == null || !(level.getEntity(id) instanceof VillageFolkEntity o) || o.isSleeping()) continue;
            double d = o.distanceToSqr(f);
            if (d > 10 * 10 || d < 3 * 3) continue;
            String key = f.getUUID().compareTo(id) < 0 ? f.getUUID() + "/" + id : id + "/" + f.getUUID();
            Integer last = WAVED.get(key);
            if (last != null && f.tickCount - last < 2400 && f.tickCount >= last) continue;
            WAVED.put(key, f.tickCount);
            if (WAVED.size() > 4096) WAVED.clear();
            f.getLookControl().setLookAt(o, 30.0F, 30.0F);
            o.getLookControl().setLookAt(f, 30.0F, 30.0F);
            NOW.put(f.getUUID(), new Moment(WAVE, f.tickCount + 40));
            NOW.put(o.getUUID(), new Moment(WAVE, o.tickCount + 40));
            send(o);
            return;
        }
    }

    // ------------------------------------------------------------------ its voice

    public enum Voice {
        HUM(SoundEvents.VILLAGER_AMBIENT), MURMUR(SoundEvents.WANDERING_TRADER_AMBIENT), GRUNT(SoundEvents.VILLAGER_NO),
        CHIRP(SoundEvents.VILLAGER_YES);

        public final SoundEvent sound;

        Voice(SoundEvent sound) { this.sound = sound; }
    }

    /** The kind of voice it has for its idle sounds: the grumpy grunt, the cheerful chirp, the shy murmur, the rest hum. */
    public static Voice voice(VillageFolkEntity f) {
        Social.Life life = f.life();
        if (life.has(Social.Trait.GRUMPY)) return Voice.GRUNT;
        if (life.has(Social.Trait.CHEERFUL)) return Voice.CHIRP;
        if (life.has(Social.Trait.SHY)) return Voice.MURMUR;
        return Math.floorMod(f.getUUID().hashCode(), 3) == 0 ? Voice.MURMUR : Voice.HUM;
    }

    /**
     * Its own pitch, before the little wobble of each sound: a child's high (the youngest highest), a man's lower than
     * a woman's, the old lower again, the tall and the broad deeper, the slight a touch higher, and a little of its own.
     */
    public static float basePitch(VillageFolkEntity f) {
        // From what the client is sent as well as the server (a hurt sound is played on both): its face's number.
        long look = f.clientLook();
        Looks.Genes g = f.individual().genes;
        float own = g.rolled ? g.voice : 0.0F;
        if (f.isBaby()) {
            int growth = Individual.growthOf(f.clientMarks());
            return clamp(1.55F - growth * 0.025F + own * 0.04F);
        }
        if (!Looks.known(look)) return 1.0F;
        float p = Looks.male(look) ? 0.90F : 1.10F;
        int years = Looks.lines(look);                 // the lines of its years: in its sixties, past seventy-five
        if (years >= 3) p *= 0.88F;
        else if (years == 2) p *= 0.94F;
        float h = Looks.heightOfStep(Looks.heightStepOf(look));
        p *= 1.0F - (h - 1.0F) * 1.6F;
        int build = Looks.build(look);
        if (build == 2) p *= 0.95F;
        else if (build == 0) p *= 1.03F;
        p *= 1.0F + own * 0.05F;
        return clamp(p);
    }

    private static float clamp(float p) {
        return Math.max(0.55F, Math.min(1.9F, p));
    }

    /** The pitch of one sound it makes. */
    public static float pitch(VillageFolkEntity f) {
        var r = f.getRandom();
        return basePitch(f) * (1.0F + (r.nextFloat() - r.nextFloat()) * 0.04F);
    }

    /** The little sound of its voice when it says something out loud (FolkTalk.speak): once in a few seconds at most. */
    public static void blip(VillageFolkEntity f) {
        Integer last = SPOKE.get(f.getUUID());
        if (last != null && f.tickCount - last < 60 && f.tickCount >= last) return;
        SPOKE.put(f.getUUID(), f.tickCount);
        if (SPOKE.size() > 4096) SPOKE.clear();
        f.playSound(voice(f).sound, voice(f) == Voice.MURMUR ? 0.3F : 0.4F, pitch(f));
    }
}
