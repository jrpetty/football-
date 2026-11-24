package com.jrpetty.mcassistant.pa.voice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [voice37] Words to the expressive voice's text tokens: GPT-2's byte-level BPE, read from the model's own
 * tokenizer.json, in plain Java (no other program). Its added tokens ("[laugh]", "[sigh]", "[chuckle]"...) are matched
 * whole first, the rest split the GPT-2 way and merged pair by pair by rank, and two end-of-text tokens close the line,
 * exactly as the reference tokenizer does (PaBpeTest checks ids against it).
 */
public final class PaBpe {

    /** GPT-2's pre-tokeniser: contractions, words, numbers, other runs, and spaces. */
    static final Pattern SPLIT = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");

    private final Map<String, Integer> vocab;
    private final Map<String, Integer> ranks;
    private final Map<String, Integer> added;
    private final Pattern addedSplit;
    private final int eos;
    private final Map<String, int[]> memo = new HashMap<>();
    private static final char[] BYTE_CHAR = byteChars();

    public PaBpe(Map<String, Integer> vocab, List<String[]> merges, Map<String, Integer> added) {
        this.vocab = vocab;
        this.ranks = new HashMap<>(merges.size() * 2);
        for (int i = 0; i < merges.size(); i++) ranks.put(merges.get(i)[0] + " " + merges.get(i)[1], i);
        this.added = added;
        StringBuilder alt = new StringBuilder();
        for (String a : added.keySet()) { if (alt.length() > 0) alt.append('|'); alt.append(Pattern.quote(a)); }
        this.addedSplit = alt.length() == 0 ? null : Pattern.compile(alt.toString());
        Integer e = added.get("<|endoftext|>");
        this.eos = e == null ? vocab.getOrDefault("<|endoftext|>", 50256) : e;
    }

    /** The tokeniser out of a tokenizer.json (model.vocab, model.merges as pairs or "a b" strings, added_tokens). */
    public static PaBpe read(InputStream in) throws IOException {
        String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Json j = new Json(s);
        Object root = j.value();
        if (!(root instanceof Map<?, ?> top) || !(top.get("model") instanceof Map<?, ?> model)) throw new IOException("not a tokenizer");
        Map<String, Integer> vocab = new HashMap<>();
        if (model.get("vocab") instanceof Map<?, ?> v) for (Map.Entry<?, ?> e : v.entrySet()) vocab.put((String) e.getKey(), ((Number) e.getValue()).intValue());
        List<String[]> merges = new ArrayList<>();
        if (model.get("merges") instanceof List<?> ms) for (Object m : ms) {
            if (m instanceof List<?> pair && pair.size() == 2) merges.add(new String[]{(String) pair.get(0), (String) pair.get(1)});
            else if (m instanceof String str) { int sp = str.indexOf(' '); if (sp > 0) merges.add(new String[]{str.substring(0, sp), str.substring(sp + 1)}); }
        }
        Map<String, Integer> added = new LinkedHashMap<>();
        if (top.get("added_tokens") instanceof List<?> at) for (Object o : at) if (o instanceof Map<?, ?> a) added.put((String) a.get("content"), ((Number) a.get("id")).intValue());
        if (vocab.size() < 256 || merges.isEmpty()) throw new IOException("tokenizer is missing its vocabulary");
        return new PaBpe(vocab, merges, added);
    }

    /** The ids for a line, with the two end-of-text tokens the model expects after it. */
    public long[] encode(String text) {
        List<Integer> out = new ArrayList<>();
        int at = 0;
        if (addedSplit != null) {
            Matcher m = addedSplit.matcher(text);
            while (m.find()) {
                plain(text.substring(at, m.start()), out);
                out.add(added.get(m.group()));
                at = m.end();
            }
        }
        plain(text.substring(at), out);
        out.add(eos);
        out.add(eos);
        long[] ids = new long[out.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = out.get(i);
        return ids;
    }

    /** Is this one of the model's own tags ("[laugh]")? */
    public boolean isTag(String t) {
        return added.containsKey(t) && !t.equals("<|endoftext|>");
    }

    private void plain(String text, List<Integer> out) {
        if (text.isEmpty()) return;
        Matcher m = SPLIT.matcher(text);
        while (m.find()) {
            String piece = m.group();
            int[] ids = memo.get(piece);
            if (ids == null) {
                ids = bpe(bytesToChars(piece));
                if (memo.size() < 20_000) memo.put(piece, ids);
            }
            for (int id : ids) out.add(id);
        }
    }

    private int[] bpe(String word) {
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < word.length(); ) { int cp = word.codePointAt(k); parts.add(new String(Character.toChars(cp))); k += Character.charCount(cp); }
        while (parts.size() > 1) {
            int best = -1, bestRank = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < parts.size(); i++) {
                Integer r = ranks.get(parts.get(i) + " " + parts.get(i + 1));
                if (r != null && r < bestRank) { bestRank = r; best = i; }
            }
            if (best < 0) break;
            String a = parts.get(best), b = parts.get(best + 1);
            List<String> next = new ArrayList<>(parts.size());
            for (int i = 0; i < parts.size(); i++) {
                if (i + 1 < parts.size() && parts.get(i).equals(a) && parts.get(i + 1).equals(b)) { next.add(a + b); i++; }
                else next.add(parts.get(i));
            }
            parts = next;
        }
        int[] ids = new int[parts.size()];
        for (int i = 0; i < ids.length; i++) {
            Integer id = vocab.get(parts.get(i));
            ids[i] = id == null ? eos : id;
        }
        return ids;
    }

    static String bytesToChars(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) sb.append(BYTE_CHAR[x & 0xff]);
        return sb.toString();
    }

    /** GPT-2's bytes_to_unicode: printable bytes stand for themselves, the rest move up past 255. */
    static char[] byteChars() {
        char[] c = new char[256];
        boolean[] keep = new boolean[256];
        for (int i = '!'; i <= '~'; i++) keep[i] = true;
        for (int i = 0xA1; i <= 0xAC; i++) keep[i] = true;
        for (int i = 0xAE; i <= 0xFF; i++) keep[i] = true;
        int n = 0;
        for (int i = 0; i < 256; i++) c[i] = keep[i] ? (char) i : (char) (256 + n++);
        return c;
    }

    /** Just enough JSON for a tokenizer file: objects, arrays, strings, numbers, true/false/null. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object value() throws IOException {
            ws();
            if (i >= s.length()) throw new IOException("unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    if (s.charAt(i++) != ':') throw new IOException("bad JSON object");
                    m.put(k, value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw new IOException("bad JSON object");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw new IOException("bad JSON array");
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IOException("bad JSON value at " + st);
            String num = s.substring(st, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private String string() throws IOException {
            if (s.charAt(i) != '"') throw new IOException("expected a JSON string at " + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }
    }
}
