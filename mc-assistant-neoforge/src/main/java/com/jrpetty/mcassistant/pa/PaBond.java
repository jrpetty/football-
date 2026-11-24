package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [pa35] The bond between an assistant and its player: a friendship that grows from time together and being looked
 * after (fed, healed, kitted out, slept near, saved in a fight, praised, its wants met), and slips, gently, when it's hit,
 * left starving, let die, insulted over and over, or left alone for days. Five steps: Stranger, Mate, Good Mate, Best
 * Mate, Ride or Die. A higher bond makes it warmer (dry, never syrupy), more willing to help unasked, braver, and now
 * and then it gives something small. It also has a mood: an occasional want (a bed of its own, a chest, a cooked
 * salmon), at most one at a time, rare, and silent while it's told to be quiet. And it keeps "our story": the moments it
 * noted itself, by day and place. Pure Java: the rules and their numbers (PaBondAct carries them out in game).
 */
public final class PaBond {

    private PaBond() {}

    public static final String[] TIERS = {"Stranger", "Mate", "Good Mate", "Best Mate", "Ride or Die"};
    static final double[] AT = {0, 15, 35, 60, 85};

    /** The step a bond (0..100) is at: 0 Stranger .. 4 Ride or Die. */
    public static int tier(double bond) {
        int t = 0;
        for (int i = 0; i < AT.length; i++) if (bond >= AT[i]) t = i;
        return t;
    }

    /** How far to the next step (0..1), 1 at the top. */
    public static double progress(double bond) {
        int t = tier(bond);
        if (t >= AT.length - 1) return 1.0;
        return (bond - AT[t]) / (AT[t + 1] - AT[t]);
    }

    /** What moves the bond, and by how much (most capped a day so it can't be farmed). */
    public enum Event {
        TOGETHER(0.5, 6), FED(1.5, 6), GIFT(3.0, 9), FAVOURITE_GIFT(5.0, 10), HEALED(2.0, 6), KITTED(2.0, 6), SLEPT_NEAR(1.0, 1),
        SAVED(2.0, 6), PRAISED(0.5, 2), WANT_MET(3.0, 6), PROMISE_KEPT(1.0, 3),
        HIT(-1.0, -4), STARVING(-0.5, -3), DIED(-3.0, -6), INSULTED(-0.5, -2), WANT_IGNORED(-0.5, -1), ABANDONED(-2.0, -10);

        public final double amount, dailyCap;

        Event(double amount, double dailyCap) {
            this.amount = amount;
            this.dailyCap = dailyCap;
        }
    }

    /** One assistant's bond: the number, today's tally per event (for the caps), its own favourites, its want, its story. */
    public static final class State {
        public double bond = 5;
        public long day = -1;
        public final Map<Event, Double> today = new HashMap<>();
        /** Its own favourite things (it was "born" with them: by its identity), remembered in Memory. */
        public final List<String> favourites = new ArrayList<>();
        /** The want it has now ("" none), when it began, when it may want again. */
        public String want = "";
        public long wantSince = -1, wantAgainAt = 0;
        /** Our story: day, where, what. */
        public final List<String[]> story = new ArrayList<>();
        public final Set<String> firsts = new LinkedHashSet<>();
        /** When its player was last seen (wall clock, ms), for "left alone for days". */
        public long lastSeen;

        /** Apply an event on a game day; the day's cap for it holds. Returns the change actually made. */
        public double apply(Event e, long gameDay) {
            if (gameDay != day) { day = gameDay; today.clear(); }
            double so = today.getOrDefault(e, 0.0);
            double room = e.dailyCap >= 0 ? Math.max(0, e.dailyCap - so) : Math.min(0, e.dailyCap - so);
            double delta = e.amount >= 0 ? Math.min(e.amount, room) : Math.max(e.amount, room);
            if (delta == 0) return 0;
            today.put(e, so + delta);
            double was = bond;
            bond = Math.max(0, Math.min(100, bond + delta));
            return bond - was;
        }

        public int tier() { return PaBond.tier(bond); }

        /** Note a moment in our story (a first only once). */
        public boolean note(String first, long day, String where, String text) {
            if (first != null && !firsts.add(first)) return false;
            story.add(new String[]{Long.toString(day), where, text});
            while (story.size() > 60) story.remove(0);
            return true;
        }
    }

    // ------------------------------------------------------------------ what a higher bond changes

    /** Its player's food at or under this, and it has food: it brings some unasked (only from Mate up). */
    public static int bringsFoodAt(int tier) {
        return tier <= 0 ? -1 : tier == 1 ? 6 : tier == 2 ? 10 : 14;
    }

    /** It hands over a better tool it carries (Good Mate up). */
    public static boolean handsTools(int tier) { return tier >= 2; }

    /** It guards its player while they stand still a while (Good Mate up), after this many seconds. */
    public static int guardsAfkAfter(int tier) { return tier >= 2 ? (tier >= 4 ? 30 : 60) : -1; }

    /** Extra blocks it notices trouble near its player from (warning earlier). */
    public static int warnsEarlier(int tier) { return 2 * tier; }

    /** It shares what it finds ("Found you some diamonds, kept them separate"): Mate up. */
    public static boolean shares(int tier) { return tier >= 1; }

    /** Health share it fights on to before falling back (braver as the bond grows): 35% a stranger, 20% ride or die. */
    public static double retreatsAt(int tier) { return 0.35 - 0.0375 * tier; }

    /** A small gift now and then (Best Mate up): the chance a game day. */
    public static double giftChance(int tier) { return tier >= 4 ? 0.35 : tier == 3 ? 0.2 : 0; }

    /** What each step does, for the Progress tab. */
    public static List<String> effects(int tier) {
        List<String> out = new ArrayList<>();
        if (tier >= 1) out.add("Brings you food when you're low (food " + bringsFoodAt(tier) + " or under)");
        if (tier >= 1) out.add("Shares what it finds");
        if (tier >= 2) out.add("Hands over better tools it carries");
        if (tier >= 2) out.add("Guards you when you stand still (" + guardsAfkAfter(tier) + " s)");
        if (tier >= 1) out.add("Notices trouble " + warnsEarlier(tier) + " blocks further off");
        if (tier >= 1) out.add("Fights on to " + Math.round(retreatsAt(tier) * 100) + "% health before falling back");
        if (tier >= 3) out.add("Small gifts now and then");
        if (tier >= 1) out.add("Warmer with you, in its own way");
        return out;
    }

    /** What raises it, for the Progress tab. */
    public static final String[] RAISES = {"Time together", "Feeding it", "Gifts (its favourites most)", "Healing it", "Armour and tools",
        "Sleeping near it", "Saving it in a fight", "Praise", "Meeting its wants", "Keeping its rules"};
    public static final String[] LOWERS = {"Hitting it", "Leaving it hungry", "Letting it die", "Constant insults (banter's fine)", "Days away"};

    // ------------------------------------------------------------------ its own favourites and wants

    static final String[] FOODS = {"cooked_salmon", "cooked_cod", "baked_potato", "pumpkin_pie", "cookie", "bread", "cooked_mutton", "cooked_rabbit", "golden_carrot", "sweet_berries"};
    static final String[] THINGS = {"poppy", "cornflower", "dandelion", "amethyst_shard", "feather", "nautilus_shell", "glow_berries", "prismarine_shard", "honeycomb", "lantern"};

    /** Its favourites by its identity: one food and two things, the same every time for the same assistant. */
    public static List<String> favouritesFor(long seed) {
        java.util.Random r = new java.util.Random(seed);
        List<String> out = new ArrayList<>();
        out.add(FOODS[r.nextInt(FOODS.length)]);
        String a = THINGS[r.nextInt(THINGS.length)], b;
        do { b = THINGS[r.nextInt(THINGS.length)]; } while (b.equals(a));
        out.add(a);
        out.add(b);
        return out;
    }

    /** May it voice a want now? Never while hushed, never with one already, rarely (a chance per check), not within the cooldown. */
    public static boolean mayWant(State s, int mute, long now, double roll) {
        return mute == 0 && s.want.isEmpty() && now >= s.wantAgainAt && roll < 0.25;
    }

    /** A want's cooldown after it ends (met or not): about half an hour of play. */
    public static final long WANT_COOLDOWN = 36000, WANT_LASTS = 24000;

    /** An item path as said ("cooked_salmon" -> "a cooked salmon"). */
    public static String said(String path) {
        String w = path.replace('_', ' ');
        boolean plural = w.endsWith("s") && !w.endsWith("ss");
        return plural ? "some " + w : ("aeiou".indexOf(w.charAt(0)) >= 0 ? "an " : "a ") + w;
    }
}
