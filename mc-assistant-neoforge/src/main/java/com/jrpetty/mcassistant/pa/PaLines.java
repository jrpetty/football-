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

    public static final String[] FILES = {"lines_say.txt", "lines_death.txt", "lines_quip.txt", "lines_comeback.txt", "lines_look.txt"};

    private static volatile Map<String, List<String>> all;

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
                    if (key != null) m.get(key).add(t);
                }
            } catch (Exception ignored) {
                // a missing file only means fewer lines: the plain ones still work
            }
        }
        m.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(m);
    }
}
