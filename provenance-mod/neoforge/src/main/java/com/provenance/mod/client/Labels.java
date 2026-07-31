package com.provenance.mod.client;

import java.util.Locale;

/**
 * Turns a statistic id into something readable, e.g. {@code blocks_mined} into
 * "Blocks mined". Shared by the tooltip and the history screen so the same
 * statistic is never named two different ways.
 */
public final class Labels {

    private Labels() {
    }

    public static String of(String statId) {
        if (statId == null || statId.isEmpty()) {
            return "";
        }
        String spaced = statId.replace('_', ' ');
        if (spaced.endsWith(" cm")) {
            spaced = spaced.substring(0, spaced.length() - 3);
        }
        if (spaced.endsWith(" ticks")) {
            spaced = spaced.substring(0, spaced.length() - 6);
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Item registry id to a display name, e.g. {@code minecraft:stone} to "Stone". */
    public static String prettyId(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String spaced = path.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public static String number(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }
}
