package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The leader's morning at the leader's hall. Once the hall stands, whoever leads the village walks
 * there after the morning's business at the board and sits a while at the head of the great hall,
 * on the dais under the great window, hearing the village's business: what is short, who has
 * quarrelled, what it promised when it was elected. Then back to its own work. Folk see it go, and
 * its escort (the watch) goes with it.
 */
public final class Court {

    private Court() {}

    /** How long it sits (ticks). */
    static final int SITTING = 1200;

    /** Each leader's morning: the day, and the tick it took its seat (or -1 on the way). */
    private static final Map<UUID, long[]> MORNING = new ConcurrentHashMap<>();

    public static void resetForTests() {
        MORNING.clear();
    }

    /** The leader's hall, if it stands. */
    @Nullable
    static Ledger.Building hall(UUID village) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("townhall")) return b;
        return null;
    }

    /** Where the leader stands to hold court: on the dais, before its seat. */
    public static BlockPos seat(Ledger.Building hall) {
        Direction back = hall.facing();
        return hall.anchor().relative(back, 7).above();
    }

    /** Is this folk at court now (the leader, in its hall, in the morning)? */
    public static boolean sitting(VillageFolkEntity f) {
        long[] m = MORNING.get(f.getUUID());
        return m != null && m[1] >= 0 && f.level().getDayTime() / 24000L == m[0];
    }

    /**
     * The leader's walk to the hall and its spell there, a step at a time (the agenda's). Returns
     * whether it is about it (and so not at its own work).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isHired() || !f.getUUID().equals(Villages.elder(village))) return false;
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (time < 3000L || time > 6000L || Raids.underAlarm(village)) return false;
        Ledger.Building hall = hall(village);
        if (hall == null || Ledger.raising(village, hall.anchor()) || !level.isLoaded(hall.anchor())) return false;
        long[] m = MORNING.get(f.getUUID());
        if (m != null && m[0] == day && m[1] == -2) return false;              // done for the day
        if (m == null || m[0] != day) {
            m = new long[]{ day, -1 };
            MORNING.put(f.getUUID(), m);
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "To the hall. The village's business won't wait.",
                "Off to the hall — come and find me there if you need me.", "Court this morning. Who's first?"));
        }
        BlockPos at = seat(hall);
        if (m[1] == -1) {
            if (f.blockPosition().distSqr(at) > 2.5 * 2.5) {
                if (f.getNavigation().isDone() || f.tickCount % 80 == 0) f.walkTo(at, 0.8D);
                f.hobbyNow = "on the way to the leader's hall";
                f.lastLeisureTick = f.tickCount;
                // Too long on the way (a door shut, the hall cut off): another day.
                if (time > 5200L) { m[1] = -2; return false; }
                return true;
            }
            m[1] = level.getGameTime();
            f.getNavigation().stop();
            f.persona().remember(day, "I held court in the leader's hall", 2);
        }
        if (level.getGameTime() - m[1] > SITTING) {
            m[1] = -2;
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "That's the morning's business done.", "Back to work, all of us.",
                "Enough talk — there's work to do."));
            return false;
        }
        // At the head of the hall, looking down it to the door.
        BlockPos door = hall.anchor().relative(hall.facing().getOpposite(), 9);
        f.getLookControl().setLookAt(door.getX() + 0.5, door.getY() + 1.5, door.getZ() + 0.5);
        f.hobbyNow = "holding court in the leader's hall";
        f.lastLeisureTick = f.tickCount;
        if (level.getRandom().nextInt(400) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Next! Who has business with the hall?",
                "Mandate first: " + (Elections.mandate(village) == null ? "the village's good" : Elections.mandate(village).cares) + ".",
                "Write it in the ledger. We'll see to it.", "Short of anything? Tell me now, not at the board."));
        }
        return true;
    }
}
