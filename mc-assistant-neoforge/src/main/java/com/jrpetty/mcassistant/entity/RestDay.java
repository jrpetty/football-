package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village's day of rest, once a week (never its market day), once it is past its first
 * week and out of the Wood Age. Nobody but the watch works. The day goes:
 * <ul>
 * <li><b>the morning service</b> (from first light): the bell rings and everybody goes to
 *     the chapel, or gathers round the well if there is none, and the elder gives thanks
 *     for the week — the things in the village's history;</li>
 * <li><b>games on the square</b> (late morning to the afternoon): tag, chasing each other
 *     round the square, the children in it with the grown-ups;</li>
 * <li><b>walking out</b> (the afternoon): couples walk together, and the unattached walk
 *     out with whoever they are sweet on — how sweethearts become partners, and partners
 *     raise children;</li>
 * <li>and the evening as any other.</li>
 * </ul>
 * A village that keeps its day of rest is the happier for it (Contentment).
 */
public final class RestDay {

    private RestDay() {}

    static final long SERVICE_FROM = 1000L, GAMES_FROM = 3600L, WALKING_FROM = 8000L, EVENING = 12000L;

    private static final Map<UUID, Long> KEPT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RUNG = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SERMON = new ConcurrentHashMap<>();

    public static void resetForTests() {
        KEPT.clear();
        RUNG.clear();
        SERMON.clear();
    }

    /** Is today this village's day of rest? */
    public static boolean today(@Nullable UUID village, long day) {
        if (village == null || Math.floorMod(day + village.hashCode() + 3, 7) != 0) return false;
        if (Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return false;
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village);
        return founded < 0 || day - founded >= 7;
    }

    /** Did the village keep a day of rest in the last week? */
    public static boolean keptThisWeek(UUID village, long day) {
        Long k = KEPT.get(village);
        return k != null && day - k < 7;
    }

    /** Was the last day of rest kept yesterday or today? */
    public static boolean justRested(UUID village, long day) {
        Long k = KEPT.get(village);
        return k != null && day - k <= 1;
    }

    /** What the day is doing now, in a few words (talk). */
    @Nullable
    public static String now(UUID village, long dayTime) {
        long day = dayTime / 24000L, t = dayTime % 24000L;
        if (!today(village, day)) return null;
        if (t >= SERVICE_FROM && t < GAMES_FROM) return "the morning service";
        if (t >= GAMES_FROM && t < WALKING_FROM) return "games on the square";
        if (t >= WALKING_FROM && t < EVENING) return "walking out";
        return null;
    }

    /**
     * A folk's day of rest, while it would otherwise be at work. Returns true while it has
     * something to be doing (the service, the games, walking out).
     */
    public static boolean spend(VillageFolkEntity f) {
        UUID village = f.ownerId();
        BlockPos heart = f.villageCentre();
        if (village == null || heart == null || !(f.level() instanceof ServerLevel level)) return false;
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        if (!today(village, day) || t < SERVICE_FROM || t >= EVENING) return false;
        KEPT.put(village, day);
        if (t < GAMES_FROM) return service(f, level, village, heart, day);
        if (t < WALKING_FROM && Militia.drill(f)) return true;      // [war-prep] the militia drills instead of the games
        if (t < WALKING_FROM) return games(f, level, heart);
        return walkingOut(f, level, day);
    }

    // ------------------------------------------------------------------ the service

    static boolean service(VillageFolkEntity f, ServerLevel level, UUID village, BlockPos heart, long day) {
        Villages.Village v = Villages.get(village);
        if (v != null && RUNG.getOrDefault(village, -1L) < day) {
            RUNG.put(village, day);
            Watch.ring(level, v);
        }
        BlockPos chapel = Villages.builtAt(village, "chapel");
        com.jrpetty.mcassistant.village.Ledger.Building b = null;
        if (chapel != null) {
            for (var x : com.jrpetty.mcassistant.village.Ledger.buildings(village)) if (x.structure().equals("chapel")) { b = x; break; }
        }
        BlockPos spot, altar;
        int h = f.getUUID().hashCode();
        if (b != null) {
            // In the nave, in the pews, facing the altar at the back.
            Direction back = b.facing(), right = back.getClockWise();
            spot = b.anchor().relative(right, Math.floorMod(h, 5) - 2).relative(back, Math.floorMod(h >> 4, 6) - 3);
            altar = b.anchor().relative(back, 6);
        } else {
            // Round the well.
            double a = Math.floorMod(h, 360) * Math.PI / 180.0;
            spot = heart.offset((int) Math.round(Math.cos(a) * 4), 0, (int) Math.round(Math.sin(a) * 4));
            altar = heart;
        }
        if (!arrive(f, spot)) return true;
        f.hobbyNow = b != null ? "at the morning service in the chapel" : "at the morning service by the well";
        f.getLookControl().setLookAt(altar.getX() + 0.5, altar.getY() + 1.5, altar.getZ() + 0.5);
        // The elder gives thanks for the week: what happened in it.
        if (f.isElder() && level.getGameTime() - SERMON.getOrDefault(village, -100000L) >= 160L) {
            SERMON.put(village, level.getGameTime());
            FolkTalk.speak(f, sermon(village, day, f.getRandom()));
        }
        return true;
    }

    static String sermon(UUID village, long day, RandomSource r) {
        java.util.List<com.jrpetty.mcassistant.village.Chronicle.Entry> week = new java.util.ArrayList<>();
        for (var e : com.jrpetty.mcassistant.village.Chronicle.of(village)) if (day - e.day() <= 7) week.add(e);
        if (week.isEmpty()) return FolkTalk.pick(r, "We give thanks for a quiet week.", "Another week, and all of us still here.");
        String what = week.get(r.nextInt(week.size())).text();
        return FolkTalk.pick(r, "We give thanks: ", "Let us remember: ", "This week ") + what + ".";
    }

    // ------------------------------------------------------------------ the games

    static boolean games(VillageFolkEntity f, ServerLevel level, BlockPos heart) {
        f.hobbyNow = "playing tag on the square";
        if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 80) {
            RandomSource r = f.getRandom();
            BlockPos to = f.surfaceAt(heart.getX() + r.nextInt(17) - 8, heart.getZ() + r.nextInt(17) - 8);
            if (to != null) f.walkTo(to, 1.25D);
            f.hobbyTick = f.tickCount;
            if (r.nextInt(3) == 0) f.getJumpControl().jump();
            if (r.nextInt(8) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(r, "Tag, you're it!", "Can't catch me!", "Ha! Missed!", "No fair, I wasn't ready!"));
                level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.5F, 1.2F);
            }
        }
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    // ------------------------------------------------------------------ walking out

    static boolean walkingOut(VillageFolkEntity f, ServerLevel level, long day) {
        VillageFolkEntity other = sweetheart(f, level);
        if (other == null) return false;
        f.hobbyNow = f.life().partner() != null ? "walking out with " + other.displayNameCap()
            : "walking out with " + other.displayNameCap() + ", who I'm sweet on";
        f.lastLeisureTick = f.tickCount;
        if (f.distanceToSqr(other) > 9.0) {
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 40) {
                f.walkTo(other.blockPosition(), 0.8D);
                f.hobbyTick = f.tickCount;
            }
            return true;
        }
        // Together: a slow stroll, side by side, and fondness grows.
        f.getLookControl().setLookAt(other, 30.0F, 30.0F);
        if (f.tickCount - f.hobbyTick > 120) {
            f.hobbyTick = f.tickCount;
            BlockPos heart = f.villageCentre();
            RandomSource r = f.getRandom();
            if (heart != null) {
                // Round the park's paths, now and then, if the town has one (Park).
                BlockPos to = r.nextBoolean() ? Park.strollSpot(f) : null;
                if (to == null) to = f.surfaceAt(heart.getX() + r.nextInt(41) - 20, heart.getZ() + r.nextInt(41) - 20);
                if (to != null) f.walkTo(to, 0.6D);
            }
            f.life().feel(other.getUUID(), other.displayNameCap(), 3);
            level.sendParticles(ParticleTypes.HEART, f.getX(), f.getEyeY() + 0.4, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
            if (r.nextInt(6) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(r, "Lovely day for it.", "I'm glad we did this.",
                    "Shall we walk down to the fields?", "You look well today."));
            }
        }
        return true;
    }

    /** Its partner, or else the single folk it is fondest of who is fond of it too. */
    @Nullable
    static VillageFolkEntity sweetheart(VillageFolkEntity f, ServerLevel level) {
        if (f.isBaby()) return null;
        UUID partner = f.life().partner();
        if (partner != null) {
            return level.getEntity(partner) instanceof VillageFolkEntity p && p.isAlive() && !p.isSleeping() ? p : null;
        }
        VillageFolkEntity best = null;
        int warmest = Social.FRIEND - 1;
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (!(a instanceof VillageFolkEntity o) || o == f || o.isBaby() || o.life().partner() != null) continue;
            if (f.life().parents().contains(o.displayNameCap()) || o.life().parents().contains(f.displayNameCap())) continue;
            int mine = f.life().affinity(o.getUUID()), theirs = o.life().affinity(f.getUUID());
            int w = Math.min(mine, theirs);
            if (w > warmest) { warmest = w; best = o; }
        }
        return best;
    }

    /** Walk to a spot; true once there. */
    static boolean arrive(VillageFolkEntity f, BlockPos spot) {
        BlockPos ground = f.surfaceAt(spot.getX(), spot.getZ());
        BlockPos to = ground != null && Math.abs(ground.getY() - spot.getY()) <= 2 ? ground : spot;
        if (f.blockPosition().distSqr(to) > 4.0) {
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 100) {
                f.walkTo(to, 0.9D);
                f.hobbyTick = f.tickCount;
            }
            return false;
        }
        f.getNavigation().stop();
        return true;
    }
}
