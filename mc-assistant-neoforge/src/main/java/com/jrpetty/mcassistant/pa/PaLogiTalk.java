package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa36] The assistant's words for its chest and logistics jobs (lines_logi.txt): a job starting, on its way, done,
 * short, and a chest full, at every corner of its personality. Dry by default; a line written "[daft] ..." is the
 * cheesy kind, said only with a Daft sense of humour (pa-35's humour setting, through PaPersona.corny). Muted ("no more
 * jokes", "shut up") it says the plain Serious line.
 */
public final class PaLogiTalk {

    private PaLogiTalk() {}

    /** Its player's sense of humour (Settings > Personality): Dry unless set. */
    static PaPersona.Humour humour(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        return r == null ? PaPersona.Humour.DRY : PaPersona.Humour.of(r.humour);
    }

    /** Has its player asked for daft humour? */
    static boolean daft(PlayerAssistantEntity a) {
        return humour(a) == PaPersona.Humour.DAFT;
    }

    /** Lines under a key at a corner, the daft ones taken out unless asked for. Pure: the tests check them. */
    public static List<String> lines(String key, String corner, boolean daft) {
        String k = corner == null || corner.isEmpty() || corner.equals("funny") ? key : key + "@" + corner;
        List<String> out = new ArrayList<>();
        out.addAll(PaPersona.allowed(PaLines.get(k), daft ? PaPersona.Humour.DAFT : PaPersona.Humour.DRY));   // the corny ones only for Daft
        return out;
    }

    /** A line for a logistics moment, blanks filled ({item}, {n}, {chest}, {who}, {owner}, ...). */
    public static String line(PlayerAssistantEntity a, String key, String... kv) {
        Map<String, String> fill = new HashMap<>();
        fill.put("owner", a.ownerName().isEmpty() ? "you" : a.ownerName());
        fill.put("name", a.displayNameCap());
        for (int i = 0; i + 1 < kv.length; i += 2) fill.put(kv[i], kv[i + 1]);
        PaData.Rec r = a.rec();
        boolean plain = r != null && r.mute > 0;
        List<String> pool = null;
        if (!plain) {
            List<String> l = PaPersona.linesFor(key, a.tri(), a.getRandom()::nextInt);
            if (l != null) pool = new ArrayList<>(PaPersona.allowed(l, humour(a)));      // [pa35] corny lines (our [daft]) only for Daft
        }
        if (pool == null || pool.isEmpty()) pool = lines(key, "serious", false);
        if (pool.isEmpty()) pool = lines(key, "", false);
        String raw = PaPersona.from(pool, a.getRandom()::nextInt, a.mind.recent);
        if (raw == null) return fill.getOrDefault("what", "");
        return PaTalk.sentences(plurals(PaPersona.fill(raw, fill)));
    }

    private static final java.util.regex.Pattern MAYBE_PLURAL = java.util.regex.Pattern.compile("\\b(\\d+) (\\w+)\\(s\\)");

    /** "1 trip(s)" reads "1 trip", "3 chest(s)" reads "3 chests". */
    static String plurals(String s) {
        if (s == null || !s.contains("(s)")) return s;
        java.util.regex.Matcher m = MAYBE_PLURAL.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(1) + " " + m.group(2) + ("1".equals(m.group(1)) ? "" : "s")));
        m.appendTail(out);
        return out.toString();
    }
}
