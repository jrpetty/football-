package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-e] A post's holder called away from its own trade for a stint of the post's work, at a spot: the wheelwright
 * to its bench or to the wagon's side, the town planner to the hall's table. A stint lasts a while and then the holder
 * goes back to its trade for a rest before the next, so a long piece of work (a wagon) is done over several visits, by
 * the one who holds the post. The post's own system calls ({@link #call}) once a second while it wants the holder there
 * and counts the work while it stands at the spot; the folk's own tick ({@link #hold}) keeps it walking to the spot and
 * at it. A stint not called again for a few seconds is over (the work done, the day's end, the bell), and the holder is
 * back at its trade at once.
 *
 * <p>Cost: a map look-up in the folk's tick; nothing at all while nobody is called.
 */
final class PostStints {

    private PostStints() {}

    /** How near the spot (flat blocks) the holder must stand to work at it. */
    static final double REACH = 2.5;
    /** A stint not called again for this long (ticks) is over. */
    static final long CALL_HOLD = 60L;

    /** One holder's stint: which post, where, what, since when, and whether it has reached the spot yet. */
    static final class Stint {
        final String post;
        final long started;
        BlockPos spot;
        String what = "";
        long calledAt;
        int walkTick = -1000;
        /** Seconds counted at the spot this stint (the post's own system says when it worked). */
        int worked;
        boolean reached;

        Stint(String post, long started, BlockPos spot) {
            this.post = post;
            this.started = started;
            this.spot = spot;
        }
    }

    private static final Map<UUID, Stint> ON = new ConcurrentHashMap<>();
    /** Holders resting from their post's work after a stint, till when (game time). */
    private static final Map<UUID, Long> RESTING = new ConcurrentHashMap<>();

    static void resetForTests() {
        ON.clear();
        RESTING.clear();
    }

    /**
     * The holder called to this spot for the post's work. A stint begins if it has none (and is not resting from the
     * last, and is free: TownJobs.fit); it ends after {@code stint} ticks, with a rest of {@code rest} ticks to follow.
     * True if it stands at the spot now, at the work.
     */
    static boolean call(VillageFolkEntity f, String post, BlockPos spot, String what, long stint, long rest) {
        long now = f.level().getGameTime();
        Stint s = ON.get(f.getUUID());
        if (s != null && !s.post.equals(post)) return false;                 // at another post's work just now
        if (s == null) {
            Long until = RESTING.get(f.getUUID());
            if (until != null && now < until) return false;
            if (!TownJobs.fit(f, post) || TownJobs.busy(f)) return false;
            s = new Stint(post, now, spot.immutable());
            ON.put(f.getUUID(), s);
            f.clearQueue();
            f.getNavigation().stop();
            f.brain("called to its post: " + what);
        }
        if (now - s.started > stint) {
            end(f, rest);
            return false;
        }
        s.spot = spot.immutable();
        s.what = what;
        s.calledAt = now;
        boolean there = near(f, spot);
        if (there) s.reached = true;
        return there;
    }

    /** A second's work counted at the spot this stint (the post's system calls this when it counts one). */
    static int worked(VillageFolkEntity f) {
        Stint s = ON.get(f.getUUID());
        return s == null ? 0 : ++s.worked;
    }

    /** Called this long (ticks) and still not at the spot: the way there is blocked, or it is too far. */
    static boolean stuck(VillageFolkEntity f, long ticks) {
        Stint s = ON.get(f.getUUID());
        return s != null && !s.reached && f.level().getGameTime() - s.started > ticks;
    }

    /** The stint over now: back to its own trade, and a rest from the post's work of this long before the next. */
    static void end(VillageFolkEntity f, long rest) {
        if (ON.remove(f.getUUID()) == null) return;
        if (rest > 0) RESTING.put(f.getUUID(), f.level().getGameTime() + rest);
        f.getNavigation().stop();
    }

    /** Is this folk at its post's work just now (its own trade waits)? */
    static boolean busy(VillageFolkEntity f) {
        if (ON.isEmpty()) return false;
        Stint s = ON.get(f.getUUID());
        return s != null && f.level().getGameTime() - s.calledAt <= CALL_HOLD;
    }

    /** Is this folk at this post's work just now? */
    static boolean busyAt(VillageFolkEntity f, String post) {
        Stint s = ON.get(f.getUUID());
        return s != null && s.post.equals(post) && busy(f);
    }

    /** What it is doing for its post, or null. */
    @Nullable
    static String doing(VillageFolkEntity f) {
        Stint s = ON.get(f.getUUID());
        return s == null || !busy(f) ? null : s.what;
    }

    /**
     * From the folk's tick: while it is on its post's work, to the spot and at it (a swing now and then); its own trade
     * waits. False (and the stint let go) once it is no longer called, or no longer free for it.
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (ON.isEmpty()) return false;
        Stint s = ON.get(f.getUUID());
        if (s == null) return false;
        if (level.getGameTime() - s.calledAt > CALL_HOLD || !TownJobs.fit(f, s.post)) {
            ON.remove(f.getUUID());
            return false;
        }
        f.hobbyNow = s.what;
        f.lastLeisureTick = f.tickCount;
        BlockPos at = s.spot;
        if (!near(f, at)) {
            if (f.getNavigation().isDone() || f.tickCount - s.walkTick > 60) {
                f.walkTo(at, 1.0D);
                s.walkTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        if (f.tickCount % 24 == 1) f.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean near(VillageFolkEntity f, BlockPos at) {
        double dx = f.getX() - (at.getX() + 0.5), dz = f.getZ() - (at.getZ() + 0.5);
        return dx * dx + dz * dz <= REACH * REACH && Math.abs(f.getY() - at.getY()) <= 3.0;
    }
}
