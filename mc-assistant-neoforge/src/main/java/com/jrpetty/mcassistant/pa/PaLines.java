package com.jrpetty.mcassistant.pa;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [pa33b] The assistant's written lines (assets/mc_assistant/pa/lines_*.txt): its replies and remarks for every corner
 * of its personality triangle, its reactions to its player's deaths, its Joke Machine commentary, its comebacks and
 * jokes, and the lines that come with each look. "== key" starts a list; "key@corner" picks a corner or edge.
 * Pure Java: read once, from the jar (both sides) or the test classpath.
 */
public final class PaLines {

    private PaLines() {}

    public static final String[] FILES = {"lines_say.txt", "lines_death.txt", "lines_quip.txt", "lines_comeback.txt", "lines_look.txt", "lines_tone.txt", "lines_ping.txt"};

    private static volatile Map<String, List<String>> all;
    /** [pa33b] Tags written in front of a line ("[bad] ...": a joke it knows is terrible; "[anim:shrug] ...": done with a gesture). */
    private static final Map<String, Set<String>> TAGS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Map<String, List<String>> all() {
        Map<String, List<String>> m = all;
        if (m == null) {
            synchronized (PaLines.class) {
                if (all == null) all = load();
                m = all;
            }
        }
        return m;
    }

    /** The lines under a key ("say.ok", "say.ok@gloomy", "death.lava", "self.skeleton@on.eat"); empty when none. */
    public static List<String> get(String key) {
        return all().getOrDefault(key, List.of());
    }

    public static boolean has(String key) {
        return !get(key).isEmpty();
    }

    /** [pa33b] A line's tags ("bad", "anim:shrug"); empty when it has none. */
    public static Set<String> tags(String line) {
        all();
        return line == null ? Set.of() : TAGS.getOrDefault(line, Set.of());
    }

    /** [pa33b] One of its deliberately bad jokes (it owns them). */
    public static boolean bad(String line) {
        return tags(line).contains("bad");
    }

    /** [pa33b] Leading "[tag]" marks off a written line: the line, and its tags into the set given. */
    public static String untag(String line, Set<String> into) {
        String t = line;
        while (t.startsWith("[")) {
            int end = t.indexOf(']');
            if (end < 2) break;
            into.add(t.substring(1, end).trim().toLowerCase(java.util.Locale.ROOT));
            t = t.substring(end + 1).trim();
        }
        return t;
    }

    public static Set<String> keys() {
        return all().keySet();
    }

    private static Map<String, List<String>> load() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String f : FILES) {
            try (InputStream in = PaLines.class.getResourceAsStream("/assets/mc_assistant/pa/" + f)) {
                if (in == null) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String key = null, line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (t.startsWith("== ")) {
                        key = t.substring(3).trim();
                        m.computeIfAbsent(key, k -> new ArrayList<>());
                        continue;
                    }
                    if (key != null) {
                        Set<String> tags = new java.util.LinkedHashSet<>();
                        String said = untag(t, tags);
                        if (said.isEmpty()) continue;
                        if (!tags.isEmpty()) TAGS.merge(said, tags, (x, y) -> { Set<String> u = new java.util.LinkedHashSet<>(x); u.addAll(y); return u; });
                        m.get(key).add(said);
                    }
                }
            } catch (Exception ignored) {
                // a missing file only means fewer lines: the plain ones still work
            }
        }
        // "key@more" is more of the same list
        for (String k : new ArrayList<>(m.keySet())) {
            if (k.endsWith("@more")) m.computeIfAbsent(k.substring(0, k.length() - 5), x -> new ArrayList<>()).addAll(m.remove(k));
        }
        m.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(m);
    }
}
