package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [shops-homes] The queue at a service point: folk walk up to the counter, wait their turn in a line out in front of
 * it, and are served one at a time, a few seconds each, by the keeper behind it, a word on each side; then they buy
 * through the trade's desk as anybody does (ShopDesk). No ticking of its own: each folk's errand asks for its turn
 * (turn), so a queue costs nothing with nobody in it, and a folk that walks off (or is unloaded) drops out of it.
 */
public final class ServiceQueue {

    private ServiceQueue() {}

    /** How long a folk is served (ticks: three seconds), how long it waits at most before it gives up, and how long it
     *  may go without asking before it is taken out of the line. */
    static final long SERVE = 60L, GIVE_UP = 2400L, FORGOTTEN = 200L;
    /** How many stand in the line; the rest wait about by the last place. */
    static final int LINE = 5;

    /** What a folk at a point is to do now. */
    public enum Turn {
        /** Walking up, waiting in line, or being served: keep at it. */
        BUSY,
        /** Served: buy now. */
        GO,
        /** Shut (out of hours, nobody in), or waited too long: go without today. */
        CLOSED,
        /** The trade has no point yet (no counter fitted): buy as the town always did. */
        NONE
    }

    private static final class Place {
        final UUID folk;
        final long joined;
        long serving = -1, lastAsked;

        Place(UUID folk, long now) {
            this.folk = folk;
            this.joined = now;
            this.lastAsked = now;
        }
    }

    private static final Map<Long, List<Place>> LINES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> IN = new ConcurrentHashMap<>();
    /** Folk served in the last moment, and who served them (for the tests and the card). */
    private static final Map<UUID, String> SERVED_BY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LINES.clear();
        IN.clear();
        SERVED_BY.clear();
    }

    /**
     * This folk's turn at its town's point of this trade (the nearest): walks it up, keeps it in line, has it served,
     * and says GO once it has been; {@code wants} is what it will ask for ("a loaf of bread"), for its word at the
     * counter. CLOSED (give up for today) when the trade is shut or the wait too long; NONE with no point to queue at.
     */
    public static Turn turn(VillageFolkEntity f, ServerLevel level, ServiceRole role, String wants) {
        UUID village = f.ownerId();
        if (village == null) return Turn.NONE;
        ServicePoints.Point point = ServicePoints.nearest(level, village, role, f.blockPosition());
        if (point == null) return Turn.NONE;
        ServicePoints.Status st = ServicePoints.status(level, village, role, point);
        long now = level.getGameTime();
        long key = point.pos().asLong();
        if (!st.open()) {
            leave(f.getUUID());
            return Turn.CLOSED;
        }
        List<Place> line = LINES.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        Long was = IN.get(f.getUUID());
        if (was != null && was != key) leave(f.getUUID());
        Place me = null;
        for (Place p : line) if (p.folk.equals(f.getUUID())) { me = p; break; }
        if (me == null) {
            me = new Place(f.getUUID(), now);
            line.add(me);
            IN.put(f.getUUID(), key);
        }
        me.lastAsked = now;
        line.removeIf(p -> now - p.lastAsked > FORGOTTEN || gone(level, p.folk));
        if (now - me.joined > GIVE_UP) {
            leave(f.getUUID());
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I can't wait all day. I'll come back tomorrow.", "What a queue! Another time."));
            return Turn.CLOSED;
        }
        int i = line.indexOf(me);
        BlockPos spot = point.queueSpot(level, Math.min(i, LINE - 1));
        f.hobbyNow = i == 0 ? "being served at the " + role.title.toLowerCase(java.util.Locale.ROOT) : "queuing at the " + role.title.toLowerCase(java.util.Locale.ROOT)
            + (i > 0 ? " (" + ordinal(i + 1) + " in line)" : "");
        f.lastLeisureTick = f.tickCount;
        double reach = i == 0 ? 1.3 : 2.0;
        if (f.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > reach * reach) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(spot, 0.8D);
            if (i == 0 && me.serving >= 0 && now - me.serving > SERVE * 4) me.serving = -1;    // walked off mid-serve
            return Turn.BUSY;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(point.pos().getX() + 0.5, point.pos().getY() + 1.0, point.pos().getZ() + 0.5);
        if (i != 0) return Turn.BUSY;
        VillageFolkEntity keeper = st.keeper();
        if (me.serving < 0) {
            me.serving = now;
            if (keeper != null) {
                if (point.stand() != null && keeper.distanceToSqr(point.stand().getX() + 0.5, point.stand().getY(), point.stand().getZ() + 0.5) > 2.5 * 2.5) {
                    keeper.walkTo(point.stand(), 1.0D);
                }
                keeper.getLookControl().setLookAt(f, 30.0F, 30.0F);
                FolkTalk.speak(keeper, FolkTalk.pick(level.getRandom(), "Next, please! What can I get you, " + f.displayNameCap() + "?",
                    "Morning, " + f.displayNameCap() + ". What'll it be?", "Who's next? Ah, " + f.displayNameCap() + "."));
            }
            if (!wants.isEmpty()) f.sayLater(FolkTalk.pick(f.getRandom(), wants + ", please.", "I'm after " + wants + ".", "Have you " + wants + "?"), 20);
            return Turn.BUSY;
        }
        if (now - me.serving < SERVE) {
            if (keeper != null) keeper.getLookControl().setLookAt(f, 30.0F, 30.0F);
            return Turn.BUSY;
        }
        if (keeper != null) {
            keeper.swing(InteractionHand.MAIN_HAND);
            SERVED_BY.put(f.getUUID(), keeper.displayNameCap());
        } else {
            SERVED_BY.put(f.getUUID(), "");
        }
        leave(f.getUUID());
        return Turn.GO;
    }

    /** Out of whatever line it is in. */
    public static void leave(UUID folk) {
        Long key = IN.remove(folk);
        if (key == null) return;
        List<Place> line = LINES.get(key);
        if (line == null) return;
        line.removeIf(p -> p.folk.equals(folk));
        if (line.isEmpty()) LINES.remove(key);
    }

    private static boolean gone(ServerLevel level, UUID folk) {
        return !(level.getEntity(folk) instanceof VillageFolkEntity f) || !f.isAlive();
    }

    private static String ordinal(int n) {
        return switch (n) {
            case 2 -> "second";
            case 3 -> "third";
            case 4 -> "fourth";
            case 5 -> "fifth";
            default -> n + "th";
        };
    }

    /** Who is in line at this point, in order (the card, the board, the tests). */
    public static List<UUID> lineAt(BlockPos point) {
        List<Place> line = LINES.get(point.asLong());
        List<UUID> out = new ArrayList<>();
        if (line != null) for (Place p : line) out.add(p.folk);
        return out;
    }

    /** Who served this folk last ("" at a counter nobody kept), or null if it has not been served. */
    @Nullable
    public static String servedBy(UUID folk) {
        return SERVED_BY.get(folk);
    }
}
