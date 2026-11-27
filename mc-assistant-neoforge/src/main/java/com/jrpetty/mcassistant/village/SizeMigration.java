package com.jrpetty.mcassistant.village;

/**
 * [grow54] The one-time move of a config file's village sizes from the old defaults (eight to twelve) to the new ones
 * (thirty to forty). A file written by an older build keeps whatever it was written with, so a server that never
 * touched these two numbers would go on founding villages of eight for ever; an admin's own choice is left alone.
 * Only the arithmetic here (no game classes), so it is checked by a plain unit test; AssistantConfig applies it.
 */
public final class SizeMigration {

    private SizeMigration() {}

    /** The old defaults, and the new. */
    public static final int OLD_MIN = 8, OLD_MAX = 12, NEW_MIN = 30, NEW_MAX = 40;

    /** What the file should hold after: the sizes, the marker, and whether they were moved. */
    public record Result(int min, int max, boolean done, boolean migrated) {}

    /**
     * Given the file's sizes and whether the move has been made already: the old defaults exactly, not yet moved,
     * become the new ones; anything else stays as it is. Either way the marker is set, so it is decided once.
     */
    public static Result decide(int min, int max, boolean done) {
        if (!done && min == OLD_MIN && max == OLD_MAX) return new Result(NEW_MIN, NEW_MAX, true, true);
        return new Result(min, max, true, false);
    }
}
