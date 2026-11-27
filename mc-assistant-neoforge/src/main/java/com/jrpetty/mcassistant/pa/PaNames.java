package com.jrpetty.mcassistant.pa;

/**
 * [pa36b] A name in an order is taken as it was said. The readers mend typos word by word ("basil" to "basic",
 * "wren" to "when"), which is right for orders and wrong for names: a naming order's name is found again in the words
 * its player typed or said, at the same place from the end of the sentence, trimmed and capped.
 */
public final class PaNames {

    private PaNames() {}

    /**
     * The words at {@code [groupStart, groupEnd)} of the mended text {@code fixed}, taken from {@code raw} instead: the
     * same count of words, the same distance from the end. {@code fallback} when the two don't line up.
     */
    public static String asSaid(String raw, String fixed, int groupStart, int groupEnd, int cap, String fallback) {
        if (raw == null || fixed == null || groupStart < 0 || groupEnd > fixed.length() || groupStart >= groupEnd) return fallback;
        String group = fixed.substring(groupStart, groupEnd).trim();
        String after = fixed.substring(groupEnd).trim();
        int k = group.isEmpty() ? 0 : group.split("\\s+").length;
        int tail = after.isEmpty() ? 0 : after.split("\\s+").length;
        String light = raw.replace('’', '\'').replace('‘', '\'').replaceAll("[“”\"]", " ")
            .replaceAll("[!?.,;:]+\\s*$", "").replaceAll("\\s+", " ").trim();
        if (light.isEmpty() || k == 0) return fallback;
        String[] w = light.split(" ");
        if (w.length < k + tail) return fallback;
        StringBuilder b = new StringBuilder();
        for (int i = w.length - tail - k; i < w.length - tail; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(w[i]);
        }
        String n = b.toString().replaceAll("[^\\p{L}\\p{N}_ '\\-]", "").trim();
        if (n.length() > cap) n = n.substring(0, cap).trim();
        // the words as said are the mended ones give or take a typo; anything else (another clause, a merged "i m") isn't them
        if (n.isEmpty() || edits(n.toLowerCase(java.util.Locale.ROOT), group.toLowerCase(java.util.Locale.ROOT)) > Math.max(2, group.length() / 3)) return fallback;
        return n;
    }

    /** Levenshtein distance. */
    static int edits(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
