package com.jrpetty.mcassistant.client.guide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [guide31] The Guide's answer engine: offline, deterministic, no network. Every article is indexed as the words of its
 * title, its keywords, the many ways a player might ask for it and its short answer (GuideText reads them all as it reads
 * a question). A question is scored against each article by BM25 over those words (the title and keywords weighing more),
 * plus how closely it matches the article's nearest asked phrasing, word for word and pair by pair, plus a little for
 * intent: a "why won't" question leans to the troubleshooting articles, a "how do I" to the how-tos. A word the book
 * does not know is read as the nearest one it does, within a typo or two (buisness: business).
 *
 * <p>{@link #answer} decides what to say: one confident answer; the best of two close ones with the other offered ("You
 * might also mean"); or, when nothing fits, that it is not sure, with the nearest topics. Never a blank.
 */
public final class GuideSearch {

    /** One article's score for a question. */
    public record Hit(GuideEntry entry, double score) {}

    /** What the Guide says to a question. */
    public enum Mode { CONFIDENT, BLEND, NONE }

    /** The reply: the article (null when none fits), what else it might mean, and the corrections made to the words. */
    public record Answer(Mode mode, GuideEntry entry, List<GuideEntry> also, List<Hit> hits, Map<String, String> corrected) {}

    private static final double K1 = 1.2, B = 0.55;
    private static final double W_TITLE = 3.0, W_KEYWORD = 2.2, W_QUESTION = 1.0, W_ANSWER = 0.35;
    /** How much the nearest phrasing counts, against BM25. */
    private static final double W_PHRASE = 5.0, W_PAIR = 1.6;
    /** Below this, nothing fits. */
    static final double FLOOR = 2.2;

    private final GuideBook book;
    private final List<GuideEntry> entries;
    private final List<Map<String, Double>> tf = new ArrayList<>();
    private final List<Double> len = new ArrayList<>();
    private final List<List<List<String>>> phrasings = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    /** Every word seen, as typed, to its term: what a misspelt word is matched against. */
    private final Map<String, String> words = new HashMap<>();
    private final double avgLen;

    public GuideSearch(GuideBook book) {
        this.book = book;
        this.entries = book.entries();
        double total = 0;
        for (GuideEntry e : entries) {
            Map<String, Double> f = new HashMap<>();
            add(f, e.title(), W_TITLE);
            for (String k : e.keywords()) add(f, k, W_KEYWORD);
            List<List<String>> ps = new ArrayList<>();
            for (String q : e.questions()) {
                add(f, q, W_QUESTION);
                ps.add(GuideText.terms(q));
            }
            ps.add(GuideText.terms(e.title()));
            add(f, e.answer(), W_ANSWER);
            for (String t : f.keySet()) df.merge(t, 1, Integer::sum);
            double l = 0;
            for (double v : f.values()) l += v;
            tf.add(f);
            len.add(l);
            total += l;
            phrasings.add(ps);
            remember(e.title());
            for (String k : e.keywords()) remember(k);
            for (String q : e.questions()) remember(q);
        }
        for (String[] g : GuideText.SYNONYMS) for (String w : g) remember(w);
        this.avgLen = entries.isEmpty() ? 1 : total / entries.size();
    }

    public GuideBook book() {
        return book;
    }

    private static void add(Map<String, Double> f, String text, double w) {
        for (String t : GuideText.terms(text)) f.merge(t, w, Double::sum);
    }

    private void remember(String text) {
        for (String w : GuideText.phrases(GuideText.normalise(text)).split(" ")) {
            if (w.length() < 4 || GuideText.STOP.contains(w)) continue;
            String t = GuideText.term(w);
            if (!t.isEmpty()) words.putIfAbsent(w, t);
        }
    }

    private double idf(String t) {
        int n = df.getOrDefault(t, 0);
        return Math.log(1 + (entries.size() - n + 0.5) / (n + 0.5));
    }

    /** The question's terms, a word the book does not know read as the nearest it does; {@code fixed} gets the corrections. */
    List<String> read(String question, Map<String, String> fixed) {
        List<String> out = new ArrayList<>();
        for (String w : GuideText.phrases(GuideText.normalise(question)).split(" ")) {
            if (w.isEmpty() || GuideText.STOP.contains(w)) continue;
            String t = GuideText.term(w);
            if (t.isEmpty()) continue;
            if (!df.containsKey(t) && w.length() >= 4 && !GuideText.NEGATIVE.contains(w)) {
                String near = nearest(w);
                if (near != null) {
                    fixed.put(w, near);
                    t = words.get(near);
                }
            }
            out.add(t);
        }
        return out;
    }

    /** The known word nearest a misspelt one: one slip in a short word, two in a long one; the commoner on a tie. */
    private String nearest(String w) {
        int most = w.length() >= 7 ? 2 : 1;
        String best = null;
        int bd = most + 1, bdf = -1;
        for (Map.Entry<String, String> e : words.entrySet()) {
            String k = e.getKey();
            if (Math.abs(k.length() - w.length()) > most || k.charAt(0) != w.charAt(0) && most == 1) continue;
            int d = GuideText.distance(w, k, most);
            if (d > most) continue;
            int f = df.getOrDefault(e.getValue(), 0);
            if (d < bd || d == bd && (f > bdf || f == bdf && k.compareTo(best) < 0)) {
                best = k;
                bd = d;
                bdf = f;
            }
        }
        return best;
    }

    /** Every article's score for the question, best first (only those it touches at all). */
    public List<Hit> rank(String question) {
        return rank(question, new LinkedHashMap<>());
    }

    private List<Hit> rank(String question, Map<String, String> fixed) {
        List<String> q = read(question, fixed);
        if (q.isEmpty()) return List.of();
        Set<String> qs = new HashSet<>(q);
        List<String> pairs = pairs(q);
        GuideText.Intent intent = GuideText.intent(question);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Double> f = tf.get(i);
            double s = 0;
            for (String t : qs) {
                Double v = f.get(t);
                if (v == null) continue;
                s += idf(t) * v * (K1 + 1) / (v + K1 * (1 - B + B * len.get(i) / avgLen));
            }
            if (s <= 0) continue;
            double best = 0, bestPair = 0;
            for (List<String> p : phrasings.get(i)) {
                best = Math.max(best, overlap(qs, p));
                bestPair = Math.max(bestPair, pairOverlap(pairs, p));
            }
            s += W_PHRASE * best + W_PAIR * bestPair * Math.min(3, pairs.size());
            GuideEntry e = entries.get(i);
            if (intent == GuideText.Intent.WHY) s *= e.trouble() ? 1.3 : 0.95;
            else if (intent == GuideText.Intent.HOW) s *= e.howto() ? 1.12 : e.trouble() ? 0.9 : 1.0;
            else if (intent == GuideText.Intent.WHAT) s *= GuideEntry.WHAT.equals(e.kind()) ? 1.1 : e.trouble() ? 0.85 : 1.0;
            hits.add(new Hit(e, s));
        }
        hits.sort((a, b) -> a.score() != b.score() ? Double.compare(b.score(), a.score()) : a.entry().id().compareTo(b.entry().id()));
        return hits;
    }

    /** How much of the question and a phrasing are the same words, the rarer words counting more (0 to 1). */
    private double overlap(Set<String> q, List<String> p) {
        if (p.isEmpty()) return 0;
        Set<String> ps = new HashSet<>(p);
        double both = 0, either = 0;
        for (String t : q) {
            double w = idf(t);
            either += w;
            if (ps.contains(t)) both += w;
        }
        for (String t : ps) if (!q.contains(t)) either += idf(t) * 0.6;
        return either <= 0 ? 0 : both / either;
    }

    private static List<String> pairs(List<String> q) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < q.size(); i++) out.add(q.get(i) + " " + q.get(i + 1));
        return out;
    }

    /** The share of the question's word pairs found in the phrasing (in order, or the other way round). */
    private static double pairOverlap(List<String> pairs, List<String> p) {
        if (pairs.isEmpty() || p.size() < 2) return 0;
        Set<String> ps = new HashSet<>();
        for (int i = 0; i + 1 < p.size(); i++) {
            ps.add(p.get(i) + " " + p.get(i + 1));
            ps.add(p.get(i + 1) + " " + p.get(i));
        }
        int n = 0;
        for (String x : pairs) if (ps.contains(x)) n++;
        return n / (double) pairs.size();
    }

    /** What the Guide says to the question (operators' articles kept back from a player who is not one). */
    public Answer answer(String question, boolean operator) {
        Map<String, String> fixed = new LinkedHashMap<>();
        List<Hit> all = rank(question, fixed);
        List<Hit> hits = new ArrayList<>();
        for (Hit h : all) if (operator || !h.entry().op() || mentionsAdmin(question)) hits.add(h);
        if (hits.isEmpty() || hits.get(0).score() < FLOOR) {
            return new Answer(Mode.NONE, null, nearestTopics(hits, all, operator), hits, fixed);
        }
        Hit top = hits.get(0);
        List<GuideEntry> also = new ArrayList<>();
        for (int i = 1; i < hits.size() && also.size() < 3; i++) {
            Hit h = hits.get(i);
            if (h.score() >= top.score() * 0.72) also.add(h.entry());
        }
        Mode mode = !also.isEmpty() && hits.get(1).score() >= top.score() * 0.86 ? Mode.BLEND : Mode.CONFIDENT;
        return new Answer(mode, top.entry(), also, hits, fixed);
    }

    private static boolean mentionsAdmin(String q) {
        List<String> t = GuideText.terms(q);
        return t.contains("admin") || t.contains("setting");
    }

    /** The three nearest topics; with nothing near at all, the first article of each of the first categories. */
    private List<GuideEntry> nearestTopics(List<Hit> hits, List<Hit> all, boolean operator) {
        List<GuideEntry> out = new ArrayList<>();
        for (Hit h : hits.isEmpty() ? all : hits) {
            if (out.size() >= 3) break;
            if (operator || !h.entry().op()) out.add(h.entry());
        }
        for (GuideBook.Category c : book.categories()) {
            if (out.size() >= 3) break;
            List<GuideEntry> in = book.in(c.id());
            if (!in.isEmpty() && !out.contains(in.get(0)) && (operator || !in.get(0).op())) out.add(in.get(0));
        }
        return out;
    }

    /** Titles that begin with, or hold, what has been typed so far: the suggestions under the ask bar. */
    public List<GuideEntry> suggest(String typed, int most, boolean operator) {
        String n = GuideText.normalise(typed);
        if (n.length() < 2) return List.of();
        List<GuideEntry> out = new ArrayList<>();
        for (Hit h : rank(typed)) {
            if (out.size() >= most) break;
            if (!operator && h.entry().op()) continue;
            out.add(h.entry());
        }
        if (out.size() < most) {
            for (GuideEntry e : entries) {
                if (out.size() >= most) break;
                if (!out.contains(e) && (operator || !e.op()) && GuideText.normalise(e.title()).contains(n)) out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
