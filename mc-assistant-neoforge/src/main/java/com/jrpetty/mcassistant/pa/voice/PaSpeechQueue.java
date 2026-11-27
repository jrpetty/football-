package com.jrpetty.mcassistant.pa.voice;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * [voice37] The lines waiting to be said, one at a time: speech never overlaps. A line waits for the one before to
 * finish, except a warning, which cuts in at once (and the queue behind it is cleared). Stale lines go unsaid: a newer
 * line of the same family replaces one still waiting (an old progress report when a new one comes), nothing waits more
 * than a few seconds (a remark about a moment that has passed), and never more than three wait. Pure Java, thread-safe.
 */
public final class PaSpeechQueue<T> {

    /** A line: its payload, family (its phrase-bank key without the corner; null for one of a kind), urgency and when asked. */
    public record Item<T>(T line, String family, boolean urgent, long at) {}

    /** What the speaker should do with the line that is playing. */
    public enum Action { WAIT, INTERRUPT }

    public static final long MAX_WAIT_MS = 8_000, URGENT_WAIT_MS = 4_000;
    public static final int MAX_WAITING = 3;

    private final LinkedList<Item<T>> waiting = new LinkedList<>();
    private int dropped;

    /** Add a line; INTERRUPT means stop the one playing now (a warning). */
    public synchronized Action offer(Item<T> it) {
        if (it.urgent()) {
            int before = waiting.size();
            waiting.removeIf(w -> !w.urgent());                                // a warning clears the chatter
            dropped += before - waiting.size();
            waiting.addFirst(it);
            return Action.INTERRUPT;
        }
        if (it.family() != null) waiting.removeIf(w -> { boolean same = it.family().equals(w.family()) && !w.urgent(); if (same) dropped++; return same; });
        while (waiting.size() >= MAX_WAITING) {
            Iterator<Item<T>> i = waiting.iterator();
            boolean removed = false;
            while (i.hasNext()) { if (!i.next().urgent()) { i.remove(); dropped++; removed = true; break; } }
            if (!removed) break;
        }
        waiting.addLast(it);
        return Action.WAIT;
    }

    /** The next line still worth saying (the stale ones dropped on the way), or null. */
    public synchronized Item<T> next(long now) {
        while (!waiting.isEmpty()) {
            Item<T> it = waiting.removeFirst();
            if (now - it.at() <= (it.urgent() ? URGENT_WAIT_MS : MAX_WAIT_MS)) return it;
            dropped++;
        }
        return null;
    }

    public synchronized void clear() {
        dropped += waiting.size();
        waiting.clear();
    }

    public synchronized int size() { return waiting.size(); }

    /** Lines that went unsaid (replaced, stale, or cleared). */
    public synchronized int dropped() { return dropped; }

    public synchronized List<Item<T>> snapshot() { return new ArrayList<>(waiting); }

    /** The family of a line from its phrase-bank key: "say.step@gloomy" -> "say.step"; null without a key. */
    public static String family(String key) {
        if (key == null || key.isEmpty()) return null;
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }
}
