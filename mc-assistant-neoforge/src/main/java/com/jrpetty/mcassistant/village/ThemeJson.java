package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [styles] A small reader for the themes' JSON (data/mc_assistant/themes/*.json): objects come back as ordered maps,
 * arrays as lists, numbers as doubles, and true, false and null as themselves. Plain Java, with nothing of the game in
 * it, so the unit tests read a theme exactly as the game does.
 */
public final class ThemeJson {

    private final String s;
    private int i;

    private ThemeJson(String s) {
        this.s = s;
    }

    /** The value the text holds; IllegalArgumentException, saying where, if it is not JSON. */
    public static Object parse(String text) {
        ThemeJson r = new ThemeJson(text);
        r.space();
        Object v = r.value();
        r.space();
        if (r.i != r.s.length()) throw r.fail("text after the end");
        return v;
    }

    private IllegalArgumentException fail(String what) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
        return new IllegalArgumentException("theme JSON: " + what + " at line " + line);
    }

    private void space() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { i++; continue; }
            // A line comment, which plain JSON has not: the themes are written by hand.
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                continue;
            }
            break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw fail("the text ends early");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': word("true"); return Boolean.TRUE;
            case 'f': word("false"); return Boolean.FALSE;
            case 'n': word("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw fail("unexpected '" + c + "'");
        }
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) throw fail("expected " + w);
        i += w.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == '}') { i++; return out; }
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') throw fail("expected a name in quotes");
            String k = string();
            space();
            if (i >= s.length() || s.charAt(i) != ':') throw fail("expected ':'");
            i++;
            space();
            out.put(k, value());
            space();
            if (i >= s.length()) throw fail("an object left open");
            char c = s.charAt(i++);
            if (c == '}') return out;
            if (c != ',') throw fail("expected ',' or '}'");
            space();
            if (i < s.length() && s.charAt(i) == '}') { i++; return out; }   // a trailing comma forgiven
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
        while (true) {
            space();
            out.add(value());
            space();
            if (i >= s.length()) throw fail("a list left open");
            char c = s.charAt(i++);
            if (c == ']') return out;
            if (c != ',') throw fail("expected ',' or ']'");
            space();
            if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw fail("a string left open");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw fail("a string left open");
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw fail("a short \\u");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
    }

    private Double number() {
        int from = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(from, i));
        } catch (NumberFormatException e) {
            throw fail("a bad number");
        }
    }
}
