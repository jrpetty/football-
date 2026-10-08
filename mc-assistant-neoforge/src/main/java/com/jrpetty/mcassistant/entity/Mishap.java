package com.jrpetty.mcassistant.entity;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/**
 * What a folk died of, in words. Every death that was not old age or a raid went down as "by
 * misfortune", in the chronicle and in the books' causes, and the hundred days lost three folk in a week
 * with nothing to say what took them: the water, a fall down a shaft, a creeper in the dark. Now the
 * books and the chronicle say.
 */
public final class Mishap {

    private Mishap() {}

    /** "by drowning", "in a fall", "fighting a zombie"...; "by misfortune" for anything else. Read after "died". */
    public static String how(DamageSource cause) {
        if (cause == null) return "by misfortune";
        Entity by = cause.getEntity();
        if (by != null) {
            String what = by.getType().getDescription().getString().toLowerCase(Locale.ROOT);
            if (by instanceof net.minecraft.world.entity.player.Player) return "fighting a player";
            if (by instanceof AssistantEntity) return "in a fight with another folk";
            return "fighting " + (startsWithVowel(what) ? "an " : "a ") + what;
        }
        if (cause.is(DamageTypeTags.IS_DROWNING)) return "by drowning";
        if (cause.is(DamageTypeTags.IS_FALL)) return "in a fall";
        if (cause.is(DamageTypes.LAVA)) return "in lava";
        if (cause.is(DamageTypeTags.IS_FIRE)) return "in a fire";
        if (cause.is(DamageTypeTags.IS_EXPLOSION)) return "in an explosion";
        if (cause.is(DamageTypes.IN_WALL)) return "trapped in the ground";
        if (cause.is(DamageTypes.STARVE)) return "of hunger";
        if (cause.is(DamageTypeTags.IS_FREEZING)) return "of the cold";
        if (cause.is(DamageTypes.CACTUS) || cause.is(DamageTypes.SWEET_BERRY_BUSH)) return "among the thorns";
        if (cause.is(DamageTypes.FELL_OUT_OF_WORLD)) return "out of the world";
        return "by misfortune";
    }

    private static boolean startsWithVowel(String s) {
        return !s.isEmpty() && "aeiou".indexOf(s.charAt(0)) >= 0;
    }

    // ------------------------------------------------------------------ the deaths, for the books and the watch

    /** A death: the day, and how. */
    record Death(long day, long gameTime, String how) {}

    private static final java.util.Map<java.util.UUID, java.util.Deque<Death>> DEATHS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void resetForTests() {
        DEATHS.clear();
    }

    /** A folk of this village died today, so (VillageFolkEntity.die). The last sixty are kept. */
    public static void record(@javax.annotation.Nullable java.util.UUID village, long day, long gameTime, String how) {
        if (village == null) return;
        java.util.Deque<Death> d = DEATHS.computeIfAbsent(village, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        d.addLast(new Death(day, gameTime, how == null || how.isEmpty() ? "by misfortune" : how));
        while (d.size() > 60) d.pollFirst();
    }

    /** How many died since {@code from} (a day), by how, most first. */
    public static java.util.Map<String, Integer> since(java.util.UUID village, long from) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        java.util.Deque<Death> d = DEATHS.get(village);
        if (d == null) return out;
        java.util.Map<String, Integer> n = new java.util.HashMap<>();
        for (Death x : d) if (x.day() >= from) n.merge(x.how(), 1, Integer::sum);
        n.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** "6 died: 3 fighting a zombie, 2 in a fall, 1 by drowning", or null with none. */
    @javax.annotation.Nullable
    public static String line(java.util.UUID village, long from, String span) {
        java.util.Map<String, Integer> by = since(village, from);
        int all = 0;
        for (int v : by.values()) all += v;
        if (all == 0) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var e : by.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        return "-" + all + " died " + span + ": " + String.join(", ", parts) + ".";
    }

    /** Folk lost to monsters or raiders since this game time: what the watch is sized against (Villages.target). */
    public static int toMonsters(java.util.UUID village, long fromGameTime) {
        int n = 0;
        java.util.Deque<Death> d = DEATHS.get(village);
        if (d == null) return 0;
        for (Death x : d) {
            if (x.gameTime() < fromGameTime) continue;
            String h = x.how();
            if (WatchClears.toMonsters(h)) n++;          // [watch-clears] "fighting a vindicator when the raiders came" and the rest
        }
        return n;
    }

    /**
     * The watch a town wants (Villages.target): one guard to every eight folk from eleven, never fewer than
     * the share it had; half again, and two at least, when it has lost folk to monsters in the last five
     * days. The hundred days' town of fifteen kept one guard against nightly raids of thirteen to thirty
     * monsters, and lost six folk in a week.
     */
    public static double watch(@javax.annotation.Nullable java.util.UUID village, double share, int total, long gameTime) {
        double t = Math.max(share, total / 8.0);
        if (village != null && toMonsters(village, gameTime - 5 * 24000L) > 0) t = Math.max(2.0, t * 1.5);
        return t;
    }
}
