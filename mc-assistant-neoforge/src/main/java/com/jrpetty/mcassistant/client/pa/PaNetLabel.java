package com.jrpetty.mcassistant.client.pa;

/** [pa33] Small words for the panel: a capital first letter, and a time in seconds said plainly. */
final class PaNetLabel {

    private PaNetLabel() {}

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String time(int seconds) {
        if (seconds < 50) return seconds + "s";
        int m = Math.round(seconds / 60.0F);
        return m <= 1 ? "a minute" : m + " min";
    }
}
