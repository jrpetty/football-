package com.jrpetty.mazerunner.config;

/**
 * How loud the Maze is about its hunters — pure logic, no Minecraft classes,
 * so the fear curve can be unit-tested.
 *
 * <p>The idea is that you hear a Griever long before you see one, and that what
 * you hear tells you how much trouble you are in. Far off it is just grinding
 * machinery somewhere in the corridors; closer, heavy footfalls you can take a
 * bearing from; and once one has your scent, your own heartbeat.
 */
public final class GrieverAudio {

    /** It has your scent and is closing: heartbeat. */
    public static final int HUNT_RANGE = 14;
    /** Close enough to place by ear: heavy metallic footfalls. */
    public static final int STALK_RANGE = 28;
    /** Somewhere out there: distant grinding. */
    public static final int DISTANT_RANGE = 48;

    public enum Cue { SILENT, DISTANT, STALKING, HUNTING }

    private GrieverAudio() {}

    /**
     * The cue a runner should hear, given the distance to the nearest Griever
     * and whether that Griever is actually hunting them. Being targeted
     * promotes the cue — a Griever that has locked on is heard sooner.
     */
    public static Cue cueFor(double distance, boolean targetingYou) {
        if (distance < 0 || distance > DISTANT_RANGE) return Cue.SILENT;
        if (targetingYou && distance <= STALK_RANGE) return Cue.HUNTING;
        if (distance <= HUNT_RANGE) return Cue.HUNTING;
        if (distance <= STALK_RANGE) return Cue.STALKING;
        return Cue.DISTANT;
    }

    /** How often a cue repeats, in seconds — closer means more insistent. */
    public static int intervalSeconds(Cue cue) {
        return switch (cue) {
            case HUNTING -> 1;
            case STALKING -> 3;
            case DISTANT -> 7;
            case SILENT -> Integer.MAX_VALUE;
        };
    }

    /**
     * Playback volume for a positional cue. Minecraft's audible radius is
     * 16×volume, so distant cues are deliberately loud to carry down the
     * corridors while still sounding far away.
     */
    public static float volumeFor(Cue cue) {
        return switch (cue) {
            // Played at the runner's own ears, so range does not apply.
            case HUNTING -> 1.0F;
            // Positional: volume must be at least range/16 or the cue fires
            // silently for anyone near the outer edge of its band.
            case STALKING -> 1.8F;  // 16 × 1.8 = 28.8 ≥ STALK_RANGE
            case DISTANT -> 3.0F;   // 16 × 3.0 = 48   ≥ DISTANT_RANGE
            case SILENT -> 0.0F;
        };
    }

    /** Everything the Griever makes is pitched well below its vanilla source. */
    public static float pitchFor(Cue cue) {
        return switch (cue) {
            case HUNTING -> 1.0F;
            case STALKING -> 0.55F;
            case DISTANT -> 0.5F;
            case SILENT -> 1.0F;
        };
    }
}
