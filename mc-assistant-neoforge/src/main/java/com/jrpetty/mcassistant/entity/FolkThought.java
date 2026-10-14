package com.jrpetty.mcassistant.entity;

import net.minecraft.util.RandomSource;

import java.util.Locale;

/**
 * [folk-ui] What a folk is thinking, in a line for the top of its profile: read off its state, most pressing first:
 * laid up; asleep; a need it is short of (FolkNeeds); what most moves its mood today (Persona.moodWhy, in its own words:
 * FolkTalk.reason); a dream nearly come true (Dreams.goal); else what its day has it doing (FolkSchedule).
 *
 * <p>The words are picked with a randomness seeded by the folk and the hour, so the line holds steady while the profile
 * refreshes and changes as its day goes on. Worked out only when the profile asks (every few seconds while it is open).
 */
public final class FolkThought {

    private FolkThought() {}

    /** A dream this far on (of 1000) is on its mind. */
    private static final int DREAM_CLOSE = 700;
    /** A mood this far from the middle is worth saying why. */
    private static final int MOOD_LOW = 45, MOOD_HIGH = 70;

    private static final String[][] NEEDS = {
        { "I'm famished. Is it nearly mealtime?", "My stomach's growling. Food first, then the rest." },
        { "I can hardly keep my eyes open. An early night for me.", "So tired. Bed can't come soon enough." },
        { "I could do with some company.", "Nobody to talk to all day. I'll go and find somebody." },
        { "I wish home felt more like home.", "I'd give a lot for a warm, dry corner of my own." },
    };

    /** Its thought now, in its own words. */
    public static String line(VillageFolkEntity f) {
        long dayTime = f.level().getDayTime();
        RandomSource r = RandomSource.create(f.getUUID().getLeastSignificantBits() ^ (dayTime / 1000L) * 31L);
        if (Health.laidUp(f)) return pick(r, "I feel rotten. Bed's the only place for me.", "Ugh. Just let me rest.");
        if (f.isSleeping()) return f.persona().rolled() ? "Zzz… " + f.persona().ambition().hope + "…" : "Zzz…";
        int need = FolkNeeds.pressing(f);
        if (need >= 0) return pick(r, NEEDS[need]);
        Persona me = f.persona();
        if (me.rolled() && (me.mood() < MOOD_LOW || me.mood() > MOOD_HIGH)) {
            for (String why : me.moodWhy()) {
                String said = FolkTalk.reason(f, why, r);
                if (!said.isEmpty()) return said;
            }
        }
        if (me.rolled() && !me.ambitionMet()) {
            Object[] goal = Dreams.goal(f);
            if ((Integer) goal[0] >= DREAM_CLOSE) return "Not long now, and I'll have done it: " + me.ambition().hope + ".";
        }
        return bySchedule(f, r);
    }

    private static String bySchedule(VillageFolkEntity f, RandomSource r) {
        Persona me = f.persona();
        String trade = f.stationTask().title.toLowerCase(Locale.ROOT);
        String hobby = me.rolled() ? me.hobby().word : "a sit down";
        return switch (FolkSchedule.now(f)) {
            case WORK -> f.stationTask() == AssistantEntity.StationTask.NONE ? "I need a trade. I'll ask at the board."
                : pick(r, "Plenty to do. A " + trade + "'s work is never done.", "Head down, back to it.", "Good honest work, this.");
            case BREAK -> pick(r, "Five minutes' peace.", "Feet up for a bit.");
            case FREE -> pick(r, "Maybe some " + hobby + " before bed.", "The day's done. Time for " + hobby + ".");
            case REST_DAY -> pick(r, "A whole day of rest!", "No work today. Lovely.");
            case SCHOOL -> pick(r, "I hope the teacher doesn't ask me.", "I'm learning so much!");
            case PLAY -> pick(r, "Tag! You're it!", "Let's play another game!");
            case WATCH -> pick(r, "Eyes open. Nothing gets past me.", "A quiet night, so far.");
            case SLEEP -> "Time for bed.";
            case LAID_UP -> "I feel rotten.";
        };
    }

    private static String pick(RandomSource r, String... options) {
        return options[r.nextInt(options.length)];
    }
}
