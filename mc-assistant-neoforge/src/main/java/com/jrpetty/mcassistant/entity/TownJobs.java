package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town's own works, done by hand. Every street worn into a path, every square paved,
 * lamp post put up, garden fenced, house refaced or raised a storey, ground levelled, jetty
 * laid, gate hung, stall set out, sign nailed up and grave dug is paid for out of the
 * stores. With this, it is also done by somebody: a folk from the village walks to the spot,
 * and the work happens there in its hands. Until a folk has got there, the work waits.
 *
 * <p>Each piece of town work (the streets, the gardens, the road...) has a crew of one. The
 * crew is a hand the village can spare: one between trades, a carrier or a storekeeper, one
 * with nothing to work at, or the trade the work belongs to (a fisher for the jetty, a guard
 * for the gate). It is never the watch at night, never the builder leading a build, and
 * never more than one hand in eight at once. A crew hand downs its own tools while on it,
 * and goes back to them once the town's work has not called it for half a minute. Town work
 * is done by day.
 *
 * <p>Usage, in a town system, before it takes the materials and sets the blocks:
 * <pre>    if (!TownJobs.atWork(level, v, "streets", spot, "laying the streets")) return;</pre>
 */
public final class TownJobs {

    private TownJobs() {}

    /** How near the spot a hand must stand to work it. */
    static final int REACH = 6;
    /** How long a hand stays on the town's work after its last call. */
    static final long HOLD = 600;
    /**
     * A hand sticks to the spot it was sent to while it is still being called there: another spot
     * waits its turn. Otherwise every house in a round would pull it its way in turn and it would
     * get to none of them.
     */
    static final long STICKY = 100;

    static final class Crew {
        final UUID folk;
        BlockPos at;
        String what = "";
        long calledAt, spotCalledAt = -100000L;
        int walkTick = -1000;
        int pieces;

        @Nullable BlockPos window;

        Crew(UUID folk) { this.folk = folk; }
    }

    private static final Map<String, Crew> CREW = new ConcurrentHashMap<>();
    private static final Map<UUID, String> ON = new ConcurrentHashMap<>();
    /** The game tests that check what the works build (not who builds it) have them done at once. */
    private static volatile boolean instant;

    public static void resetForTests() {
        CREW.clear();
        ON.clear();
    }

    /** Tests: the works done at once (true), or by hand (false, as in a real game). */
    public static void instantForTests(boolean on) {
        instant = on;
    }

    public static boolean atWork(ServerLevel level, Villages.Village v, String works, BlockPos at, String what) {
        return atWork(level, v, works, at, what, null);
    }

    /**
     * Is a hand at this spot to do this piece of the town's work, now? If not, one is sent and
     * the work waits for it. True once a hand stands within reach of the spot: it turns to the
     * work and swings, and the caller then takes the materials and sets the blocks.
     */
    public static boolean atWork(ServerLevel level, Villages.Village v, String works, BlockPos at, String what,
                                 @Nullable AssistantEntity.StationTask prefer) {
        if (instant) return true;
        long t = level.getDayTime() % 24000L;
        if (t >= 12500L && t < 23500L) return false;                 // by day
        UUID id = v.id();
        if (Raids.underAlarm(id)) return false;
        long now = level.getGameTime();
        String key = id + "/" + works;
        Crew c = CREW.get(key);
        VillageFolkEntity f = c == null ? null : live(level, c.folk);
        if (f == null || !fit(f, works)) {
            if (c != null) ON.remove(c.folk);
            f = choose(level, v, works, at, prefer, now);
            if (f == null) return false;
            c = new Crew(f.getUUID());
            CREW.put(key, c);
            ON.put(f.getUUID(), key);
            f.clearQueue();
            f.getNavigation().stop();
            f.brain("called to the town's work: " + what);
        }
        boolean sameArea = c.at != null && c.at.distSqr(at) <= (double) (REACH * 2) * (REACH * 2);
        if (c.at != null && !sameArea && now - c.spotCalledAt < STICKY) return false;   // busy at another spot: this waits its turn
        c.at = at.immutable();
        c.spotCalledAt = now;
        c.what = what;
        c.calledAt = now;
        f.hobbyNow = what;
        if (near(f, at, REACH)) {
            f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (c.pieces++ % 4 == 0) {
                level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.6F, 0.9F + level.getRandom().nextFloat() * 0.2F);
            }
            f.note(AssistantEntity.Deed.BLOCKS_BUILT, 1);
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 60) {
            f.walkTo(at, 1.0D);
            c.walkTick = f.tickCount;
        }
        return false;
    }

    /** Is this folk on the town's work just now (its own trade waits)? */
    public static boolean busy(VillageFolkEntity f) {
        String key = ON.get(f.getUUID());
        if (key == null) return false;
        Crew c = CREW.get(key);
        if (c == null || !c.folk.equals(f.getUUID())) {
            ON.remove(f.getUUID());
            return false;
        }
        return f.level().getGameTime() - c.calledAt <= HOLD;
    }

    /**
     * From the folk's tick: while it is on the town's work, it goes to the spot and stays there,
     * at the work. True while it is busy with it.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        String key = ON.get(f.getUUID());
        if (key == null) return false;
        Crew c = CREW.get(key);
        if (c == null || !c.folk.equals(f.getUUID()) || level.getGameTime() - c.calledAt > HOLD || !fit(f, key)) {
            ON.remove(f.getUUID());
            if (c != null && c.folk.equals(f.getUUID())) {
                CREW.remove(key);
                release(level, f, c);
            }
            return false;
        }
        if (c.at == null) return false;
        keepAwake(level, f, c);
        if (!near(f, c.at, REACH - 1)) {
            if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 60) {
                f.walkTo(c.at, 1.0D);
                c.walkTick = f.tickCount;
            }
        } else {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(c.at.getX() + 0.5, c.at.getY() + 0.5, c.at.getZ() + 0.5);
        }
        f.hobbyNow = c.what;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** What this folk is doing for the town, or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        String key = ON.get(f.getUUID());
        Crew c = key == null ? null : CREW.get(key);
        return c == null || !busy(f) ? null : c.what;
    }

    // ------------------------------------------------------------------ who

    @Nullable
    private static VillageFolkEntity live(ServerLevel level, UUID u) {
        return level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** Free to be put on the town's work? */
    static boolean fit(VillageFolkEntity f, String works) {
        if (!f.isAlive() || f.isBaby() || f.isSleeping() || f.isHired() || f.getTarget() != null) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Drover.busy(f)) return false;
        if (f.talkPartner() != null || f.companionPlayer() != null || f.guidePlayer() != null) return false;
        if (Assemblies.attending(f)) return false;
        if (f.stationTask() == AssistantEntity.StationTask.GUARD && (f.level().isNight() || f.onWatch()) && !works.endsWith("watch")) return false;
        UUID id = f.ownerId();
        return id == null || !Villages.holdsTheLead(id, f.getUUID(), f.level().getGameTime());
    }

    /** The hand the village can best spare for this work, near enough the spot; or null if it has none to spare. */
    @Nullable
    static VillageFolkEntity choose(ServerLevel level, Villages.Village v, String works, BlockPos at,
                                    @Nullable AssistantEntity.StationTask prefer, long now) {
        int adults = 0, busy = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.isBaby()) continue;
            adults++;
            if (a instanceof VillageFolkEntity f && ON.containsKey(f.getUUID()) && busy(f)) busy++;
        }
        if (busy >= Math.max(1, adults / 8)) return null;            // never more than one hand in eight
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !fit(f, works) || ON.containsKey(f.getUUID()) && busy(f)) continue;
            AssistantEntity.StationTask trade = f.stationTask();
            double score = 0;
            if (prefer != null && trade == prefer) score += 60;
            switch (trade) {
                case NONE -> score += 50;
                case HAUL -> score += 35;
                case STORE -> score += 30;
                case GUARD -> score += works.endsWith("watch") ? 50 : -60;
                case SCOUT -> score -= 40;
                default -> { if (trade.isCraft()) score -= 10; }
            }
            if (f.workedOut()) score += 25;                              // nothing to work at in its own trade
            if (!f.missingEssentials().isEmpty()) score += 20;            // short of its kit: its trade is idle anyway
            score -= Math.sqrt(f.blockPosition().distSqr(at)) / 4.0;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        return best;
    }

    private static UUID owner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-townjob-" + f.getUUID()).getBytes());
    }

    /** Out beyond the town (the road's head): the ground round the hand kept awake as it goes. */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Crew c) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        BlockPos here = f.blockPosition();
        boolean out = v != null && Math.max(Math.abs(here.getX() - v.centre().getX()), Math.abs(here.getZ() - v.centre().getZ()))
            > Villages.townReach(id) + 16;
        if (!out) {
            release(level, f, c);
            return;
        }
        if (c.window != null && c.window.distSqr(here) < 16 * 16) return;
        if (c.window != null) com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, owner(f), c.window, 1, false);
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, owner(f), here, 1, true);
        c.window = here.immutable();
    }

    private static void release(ServerLevel level, VillageFolkEntity f, Crew c) {
        if (c.window != null) com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, owner(f), c.window, 1, false);
        c.window = null;
    }

    private static boolean near(VillageFolkEntity f, BlockPos at, int reach) {
        double dx = f.getX() - (at.getX() + 0.5), dz = f.getZ() - (at.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) reach * reach && Math.abs(f.getY() - at.getY()) <= 5;
    }
}
