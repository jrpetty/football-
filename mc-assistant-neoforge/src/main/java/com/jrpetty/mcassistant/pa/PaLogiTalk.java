package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa36] The assistant's words for its chest and logistics jobs (lines_logi.txt): a job starting, on its way, done,
 * short, and a chest full, at every corner of its personality. Dry by default; a line written "[daft] ..." is the
 * cheesy kind, said only when its player asked for that sort of humour (the humour setting: until it exists every daft
 * line is skipped). Muted ("no more jokes", "shut up") it says the plain Serious line.
 */
public final class PaLogiTalk {

    private PaLogiTalk() {}

    /** Has its player asked for daft humour? (The humour setting; none yet, so never.) */
    static boolean daft(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return false;
        try {
            java.lang.reflect.Field f = PaData.Rec.class.getField("humour");      // pa-35's humour setting, when it lands
            Object v = f.get(r);
            return v != null && (v.toString().equalsIgnoreCase("daft") || "1".equals(v.toString()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Lines under a key at a corner, the daft ones taken out unless asked for. Pure: the tests check them. */
    public static List<String> lines(String key, String corner, boolean daft) {
        String k = corner == null || corner.isEmpty() || corner.equals("funny") ? key : key + "@" + corner;
        List<String> out = new ArrayList<>();
        for (String s : PaLines.get(k)) if (daft || !PaLines.tags(s).contains("daft")) out.add(s);
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
            if (l != null) {
                boolean d = daft(a);
                pool = new ArrayList<>();
                for (String s : l) if (d || !PaLines.tags(s).contains("daft")) pool.add(s);
            }
        }
        if (pool == null || pool.isEmpty()) pool = lines(key, "serious", false);
        if (pool.isEmpty()) pool = lines(key, "", false);
        String raw = PaPersona.from(pool, a.getRandom()::nextInt, a.mind.recent);
        if (raw == null) return fill.getOrDefault("what", "");
        return PaTalk.sentences(PaPersona.fill(raw, fill));
    }
}
