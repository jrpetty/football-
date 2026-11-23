package com.jrpetty.mcassistant.pa;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [pa33b] Other players talking to someone's assistant (right-clicking it opens a little chat box; on servers with chat,
 * a line naming it). It answers them by their in-game name and in kind: friendly to the polite, a roast back to a roast
 * of itself, a defence of its player (and a roast) to a roast of them. It never starts it, takes no orders from them, and
 * gets bored of anyone who keeps on. The pure parts: what they said, the rate limit, and which lines. Plain Java.
 */
public final class PaStrangers {

    private PaStrangers() {}

    public enum Said { ORDER, POLITE, PRAISE, ROAST_ME, ROAST_OWNER, HIT, NEUTRAL }

    /** Owner's setting for roasting others back: 0 On, 1 only when they insult it, 2 Off. */
    public static final String[] ROAST_BACK = {"On", "Only when insulted", "Off"};

    private static final String[] OWNER_WORDS = {"your owner", "your master", "your boss", "your player", "your human", "ur owner", "ur master",
        "your mate", "your keeper", "whoever owns you", "your dad", "your mum"};

    /** What a stranger's words are. {@code order}: the reader found an order in them. */
    public static Said said(String text, String owner, String self, boolean order) {
        if (text == null || text.isBlank()) return Said.NEUTRAL;
        PaTone.Read r = PaTone.read(text);
        PaQuips.Comeback cb = PaQuips.comeback(text);
        String low = " " + text.toLowerCase(Locale.ROOT).replace("'s ", " ").replace("’s ", " ").replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ") + " ";
        boolean aboutOwner = false;
        for (String w : OWNER_WORDS) if (low.contains(" " + w + " ")) aboutOwner = true;
        String o = owner == null ? "" : owner.toLowerCase(Locale.ROOT);
        if (!o.isEmpty() && low.contains(" " + o + " ") && !(self != null && o.equals(self.toLowerCase(Locale.ROOT)))) aboutOwner = true;
        boolean rude = r.tone() == PaTone.Tone.RUDE || cb == PaQuips.Comeback.INSULT;
        if (rude && aboutOwner) return Said.ROAST_OWNER;
        if (rude) return Said.ROAST_ME;
        if (order) return Said.ORDER;
        if (cb == PaQuips.Comeback.PRAISE || cb == PaQuips.Comeback.LOVE) return Said.PRAISE;
        if (r.polite() || r.thanks() || low.matches(".*\\b(hello|hi|hey|hiya|morning|evening|afternoon|alright|howdy|nice)\\b.*")) return Said.POLITE;
        return Said.NEUTRAL;
    }

    /** Their lines: null to say nothing. level: the lower of its player's Language and the server cap. */
    public static String key(Said s, int roastBack, boolean defend, boolean allowedOnServer, int level, int mute) {
        if (mute >= 2) return null;                                       // quiet: not a word to anyone
        String lang = level >= 2 ? "unfiltered" : level == 1 ? "cheeky" : "clean";
        boolean roasts = allowedOnServer && mute == 0 && roastBack < 2;
        return switch (s) {
            case ORDER -> "stranger.order";
            case POLITE, PRAISE -> mute > 0 ? "stranger.plain" : "stranger.polite";
            case HIT -> roasts ? "stranger.hit" : "stranger.plain";
            case ROAST_ME -> roasts ? "stranger.roast." + lang : "stranger.plain";
            case ROAST_OWNER -> !defend ? "stranger.plain" : roasts && roastBack == 0 ? "stranger.defend." + lang : "stranger.plain";
            case NEUTRAL -> mute > 0 ? null : "stranger.polite";
        };
    }

    /**
     * One reply every eight seconds at most to the same player, five a minute; after that it says it's bored, once,
     * and then nothing to them for a minute. Per player, by the server's clock in ticks.
     */
    public static final class Limit {
        private final Map<UUID, Deque<Long>> said = new HashMap<>();
        private final Map<UUID, Long> boredUntil = new HashMap<>();
        private final Map<UUID, PaTone.Heat> heat = new HashMap<>();

        public enum Verdict { OK, BORED, SILENT }

        public Verdict check(UUID who, long now) {
            Long until = boredUntil.get(who);
            if (until != null && now < until) return Verdict.SILENT;
            Deque<Long> d = said.computeIfAbsent(who, k -> new ArrayDeque<>());
            while (!d.isEmpty() && now - d.peekFirst() > 1200L) d.pollFirst();
            if (!d.isEmpty() && now - d.peekLast() < 160L) return Verdict.SILENT;
            if (d.size() >= 5) {
                boredUntil.put(who, now + 1200L);
                d.clear();
                return Verdict.BORED;
            }
            d.addLast(now);
            return Verdict.OK;
        }

        /** The run of rudeness from this player (a little sharper each time, cooling over minutes). */
        public PaTone.Heat heat(UUID who) {
            return heat.computeIfAbsent(who, k -> new PaTone.Heat());
        }
    }
}
