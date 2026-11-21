package com.jrpetty.mcassistant.entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a whole village thinks of a player: worked out from what each of its folk
 * thinks of them, so it is earned one person at a time — by talking with them,
 * bringing them what they want, doing what they ask, and keeping the monsters off
 * — and lost the same way.
 *
 * <p>It is the player's title in that village, from outcast to hero, and it
 * matters: a friend of the village finds folk readier to do favours and to come
 * along; an honoured guest is built a house of their own; a hero has their name set
 * on the village's monument; an outcast gets nothing from anyone.
 */
public final class Standing {

    private Standing() {}

    public enum Title {
        OUTCAST("an outcast", "Outcast of "),
        UNWELCOME("unwelcome", "Unwelcome in "),
        STRANGER("a stranger", "Stranger to "),
        VISITOR("a visitor", "Visitor to "),
        FRIEND("a friend of the village", "Friend of "),
        HONOURED("an honoured guest", "Honoured guest of "),
        HERO("the village's hero", "Hero of ");

        public final String words, prefix;

        Title(String words, String prefix) {
            this.words = words;
            this.prefix = prefix;
        }

        public boolean atLeast(Title other) { return ordinal() >= other.ordinal(); }
    }

    /** The village's view of one player: the average warmth of those who know them,
     *  how many do, and the title it comes to. */
    public record View(int score, int knownBy, Title title, String bestFriend, String worstCritic) {}

    private record Cached(View view, long until) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    public static View of(UUID village, UUID player, long gameTime) {
        String key = village + "/" + player;
        Cached c = CACHE.get(key);
        if (c != null && c.until() > gameTime) return c.view();
        View v = compute(village, player);
        CACHE.put(key, new Cached(v, gameTime + 100));
        if (CACHE.size() > 512) CACHE.clear();
        return v;
    }

    /** Forget the cached view, so a change is felt at once. */
    public static void stir(UUID village, UUID player) {
        CACHE.remove(village + "/" + player);
    }

    private static View compute(UUID village, UUID player) {
        int sum = 0, known = 0;
        int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE;
        String bestName = "", worstName = "";
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !f.persona().rolled() || !f.persona().knows(player)) continue;
            int aff = f.persona().affinity(player);
            sum += aff;
            known++;
            if (aff > best) { best = aff; bestName = f.displayNameCap(); }
            if (aff < worst) { worst = aff; worstName = f.displayNameCap(); }
        }
        int score = known == 0 ? 0 : sum / known;
        Title t;
        if (known == 0) t = Title.STRANGER;
        else if (score <= -40) t = Title.OUTCAST;
        else if (score <= -12) t = Title.UNWELCOME;
        else if (score >= 50 && known >= 5) t = Title.HERO;
        else if (score >= 30 && known >= 3) t = Title.HONOURED;
        else if (score >= 12) t = Title.FRIEND;
        else t = Title.VISITOR;
        return new View(score, known, t, best >= 30 ? bestName : "", worst <= -15 ? worstName : "");
    }

    /** "Friend of Oakford". */
    public static String titleIn(UUID village, Title t) {
        return t.prefix + Villages.name(village);
    }

    public static void resetForTests() { CACHE.clear(); }
}
