package com.jrpetty.mcassistant.entity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [culture-wonders] What the theatre and the town's wonder do to a folk's spirits (VillageFolkEntity.refreshMood),
 * and how it says so when asked (FolkTalk.reason): a good night at the play (a day), seeing itself played (two days:
 * pleased, or sore at a defeat it lived through), and the town's wonder (TownWonders): pride in it while it stands,
 * and more for the week after it was opened, the most for those who built it.
 */
public final class Stagecraft {

    private Stagecraft() {}

    /** The day each folk last saw a play; the day it was played, and whether it liked it. */
    private static final Map<UUID, Long> SAW = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> PLAYED_ME = new ConcurrentHashMap<>();

    static void resetForTests() {
        SAW.clear();
        PLAYED_ME.clear();
    }

    /** It saw a play tonight. */
    static void applause(VillageFolkEntity f, long day) {
        SAW.put(f.getUUID(), day);
        f.refreshMood();
    }

    /** It was played on the stage; {@code pleased}: it liked how. */
    static void portrayed(VillageFolkEntity f, long day, boolean pleased) {
        PLAYED_ME.put(f.getUUID(), new long[]{ day, pleased ? 1 : 0 });
        f.refreshMood();
    }

    /** Its spirits, for the play and the wonder. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long saw = SAW.get(f.getUUID());
        if (saw != null && day - saw <= 1) {
            m += 3;
            why.add(new Object[]{ "play", 3 });
        }
        long[] me = PLAYED_ME.get(f.getUUID());
        if (me != null && day - me[0] <= 2) {
            if (me[1] == 1) {
                m += 4;
                why.add(new Object[]{ "portrayed", 4 });
            } else {
                m -= 2;
                why.add(new Object[]{ "portrayedsore", 2 });
            }
        }
        return TownWonders.mood(f, day, m, why);
    }

    /** How it puts it, asked how it is. */
    public static String words(VillageFolkEntity f, String why) {
        var r = f.getRandom();
        return switch (why) {
            case "play" -> FolkTalk.pick(r, "Did you see the play last night? I'm still humming it.", "What a night at the theatre!");
            case "portrayed" -> FolkTalk.pick(r, "They put me in a play! Me! On the stage!", "Did you see the players do me? I'll never live it down.");
            case "portrayedsore" -> "They played the day we lost. I'd sooner forget it, if they'd let me.";
            default -> TownWonders.moodWords(f, why);
        };
    }
}
