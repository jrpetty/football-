package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * [war] The numbers of a battle between two towns' armies, the same whether it is fought blow by blow in front
 * of a player (entity/Battles) or reckoned on paper with nobody near (entity/Campaigns): what a fighter is worth
 * by its kit, how a side's spirit (its morale) starts and what shakes it, when it breaks and how (an ordered
 * retreat, a rout, a surrender), the tactics a Captain chooses by the ground, the odds and its own temper, and
 * the battle reckoned round by round.
 *
 * <p>Plain Java, no game classes: the unit tests run it as it stands.
 */
public final class BattleModel {

    private BattleModel() {}

    // ------------------------------------------------------------------ who stands where

    /** A fighter's place in the army. */
    public enum Role {
        /** The shield line at the front. */
        LINE,
        /** The bows, behind the line. */
        ARCHER,
        /** Held back, to plug a gap or go round a flank. */
        RESERVE,
        /** On the town's horses, for the flank. */
        RIDER,
        /** The healer with the bandages, at the back. */
        MEDIC
    }

    /** What a Captain does with the ground and the army it has. */
    public enum Tactic {
        STRAIGHT("drew up in two lines and came straight on"),
        SHIELD_WALL("locked shields and stood to receive them"),
        HILL_ARCHERS("put its archers on the rise"),
        FEIGNED_RETREAT("gave ground to draw them on"),
        HIDDEN_RESERVE("kept its reserve out of sight for the flank"),
        CAVALRY("sent its riders round the flank");

        public final String words;

        Tactic(String words) { this.words = words; }

        /** Does the line stand where it is drawn up and let the enemy come? */
        public boolean holds() { return this == SHIELD_WALL || this == HILL_ARCHERS; }

        /** Is there a move round the flank once the lines meet? */
        public boolean flanks() { return this == HIDDEN_RESERVE || this == CAVALRY; }
    }

    /** How a battle went for the side that lost it (the winner's is always HELD). */
    public enum Outcome {
        HELD("held the field"),
        RETREAT("fell back in good order"),
        ROUT("broke and ran"),
        SURRENDER("laid down its arms"),
        DRAWN("drew off at nightfall, neither side the winner");

        public final String words;

        Outcome(String words) { this.words = words; }
    }

    /** A detailed battle's phases, in order. */
    public enum Phase { FORM, PARLEY, SPEECH, CHAMPIONS, VOLLEYS, ADVANCE, CLASH, PURSUIT, AFTERMATH, DONE }

    // ------------------------------------------------------------------ a fighter's worth

    /** What a blade is worth: none, wood or stone, iron, diamond or better. */
    public static final int NO_BLADE = 0, POOR_BLADE = 1, IRON_BLADE = 2, FINE_BLADE = 3;

    /**
     * One fighter's worth in the line: a little for its two hands, more for its blade and its armour (by the armour's
     * points: a full suit of iron is fifteen), a shield, a bow, and a veteran's steadiness.
     */
    public static int power(int blade, int armour, boolean shield, boolean bow, boolean veteran) {
        return 2 + Math.max(0, Math.min(FINE_BLADE, blade)) * 2 + Math.max(0, armour) / 3 + (shield ? 2 : 0) + (bow ? 1 : 0) + (veteran ? 1 : 0);
    }

    // ------------------------------------------------------------------ spirit

    /** Below this a side wavers: its Captain rallies it, its banner dips. */
    public static final int WAVER = 35;
    /** Below this it breaks. */
    public static final int BREAK = 20;
    /** Below this (or with its Captain down) a break is a rout, not a retreat. */
    public static final int ROUT = 8;
    /** The Captain down. */
    public static final int CAPTAIN_DOWN = 20;
    /** The banner lost (its bearer down). */
    public static final int BANNER_LOST = 15;
    /** Taken in the flank. */
    public static final int FLANKED = 12;
    /** The champion beaten (and its winner's side heartened by half as much again). */
    public static final int CHAMPION_LOST = 10, CHAMPION_WON = 8;
    /** A feigned retreat turned on its pursuers: they were drawn on, out of order. */
    public static final int DRAWN_ON = 8;
    /** The Captain's "Hold the line!": once in a while, while it stands. */
    public static final int RALLY = 6;
    /** What the whole side's yielding is worth: a quarter of the army down costs a quarter of this. */
    public static final int LOSSES = 90;

    /**
     * A side's spirit at the start, 30 to 95: a steady sixty, a bold Captain's more and a timid one's less, more
     * for fighting for its own home, for the veterans in the ranks and allies at its side.
     */
    public static int startMorale(int boldness, boolean atHome, int veterans, boolean allies) {
        return clamp(60 + boldness * 4 + (atHome ? 5 : 0) + Math.min(10, veterans * 2) + (allies ? 5 : 0), 30, 95);
    }

    /** What so many of a side yielding (of the size it started) costs it in spirit. */
    public static int lossShock(int yielded, int size) {
        if (yielded <= 0) return 0;
        return Math.max(1, Math.round(yielded * (float) LOSSES / Math.max(1, size)));
    }

    /** Spirit lost a second for being outnumbered: one point a second at worse than two to three. */
    public static int outnumbered(int ours, int theirs) {
        return theirs > 0 && ours * 3 < theirs * 2 ? 1 : 0;
    }

    /**
     * How a side breaks: with its Captain on its feet and its spirit not gone, in good order; with its Captain
     * down or its spirit gone, a rout; caught with three in four of it down and the enemy three to one, it gives
     * up. Null while it has not broken.
     */
    public static Outcome breaks(Side s, Side enemy) {
        if (s.morale >= BREAK && s.standing() > 0) return null;
        if (s.standing() * 4 <= s.size && enemy.standing() >= Math.max(1, s.standing()) * 3) return Outcome.SURRENDER;
        if (s.captainDown || s.morale < ROUT) return Outcome.ROUT;
        return Outcome.RETREAT;
    }

    // ------------------------------------------------------------------ a side

    /** One side of a battle as it stands: its size, who of it has yielded, its spirit, its power, what has befallen it. */
    public static final class Side {
        public final String name;
        public final int size;
        public final Tactic tactic;
        public int yielded, dead, morale;
        /** The sum of its standing fighters' worth (and, in the field, of those not drawn in detail). */
        public int power;
        public int archers;
        public boolean captainDown, bannerLost, flanked;

        public Side(String name, int size, int power, int archers, int morale, Tactic tactic) {
            this.name = name;
            this.size = Math.max(0, size);
            this.power = Math.max(0, power);
            this.archers = Math.max(0, archers);
            this.morale = morale;
            this.tactic = tactic;
        }

        public int standing() {
            return Math.max(0, size - yielded);
        }

        /** Spirit lost (or, negative, won), never below nothing nor over a hundred. */
        public void shake(int by) {
            morale = clamp(morale - by, 0, 100);
        }

        /** In words, for the books and the bar: "steady", "wavering"... */
        public String spirit() {
            return morale >= 70 ? "eager" : morale >= 50 ? "steady" : morale >= WAVER ? "shaken" : morale >= BREAK ? "wavering" : "broken";
        }
    }

    // ------------------------------------------------------------------ tactics

    /**
     * The Captain's choice, by the ground, the odds and its temper ({@code boldness} -2 timid to 2 bold, a
     * {@code cunning} one fond of a trick): riders if the town has horses and the odds are fair; the archers on a
     * rise if there is one and archers enough; the shield wall when it is the weaker; a hidden reserve where there
     * is cover; a cunning one's feigned retreat; else straight at them.
     */
    public static Tactic choose(int boldness, boolean cunning, double odds, boolean rise, boolean cover, int riders, int archers, int size,
                                Random rng) {
        if (riders >= 2 && odds >= 0.8 && boldness >= 0) return Tactic.CAVALRY;
        if (rise && archers * 4 >= size && archers >= 2) return Tactic.HILL_ARCHERS;
        if (odds < 0.8 || boldness <= -2) return Tactic.SHIELD_WALL;
        if (cover && size >= 8 && (cunning || rng.nextBoolean())) return Tactic.HIDDEN_RESERVE;
        if (cunning && odds < 1.3 && size >= 6) return Tactic.FEIGNED_RETREAT;
        return boldness < 0 ? Tactic.SHIELD_WALL : Tactic.STRAIGHT;
    }

    /** Will this Captain offer single combat before the battle (a bold one, or a proud one now and then)? */
    public static boolean offersChampion(int boldness, Random rng) {
        return boldness >= 2 || boldness == 1 && rng.nextInt(3) == 0;
    }

    /** And will the other take it up (anyone not timid)? */
    public static boolean acceptsChampion(int boldness) {
        return boldness >= 0;
    }

    /** A duel of champions, by their worth: the chance the first wins. */
    public static double duelOdds(int a, int b) {
        return (a + 1.0) / (a + b + 2.0);
    }

    // ------------------------------------------------------------------ the battle reckoned on paper

    /** What a battle came to. {@code winner} 0 or 1, or -1 drawn. */
    public record Result(int winner, Outcome outcome, int rounds, int yieldedA, int yieldedB, int deadA, int deadB,
                         boolean championFought, int championWinner, List<String> story) {

        /** The loser's side, 0 or 1 (-1 drawn). */
        public int loser() {
            return winner < 0 ? -1 : 1 - winner;
        }
    }

    /** At most this many rounds (a round a quarter of a minute of fighting); unbroken by then, it is drawn. */
    public static final int ROUNDS = 40;

    /**
     * The battle reckoned round by round, for a field nobody watches: the champions first if one is offered and
     * taken up, the archers' duel, the lines coming together (the shield wall and the archers on the rise waiting
     * for them), the clash, the move round the flank, each side's spirit shaken by its losses, its Captain or its
     * banner going down, until one side breaks. {@code deathPercent}: the chance in a hundred that one who goes
     * down does not get up. The sides are changed in place; the story is in plain words.
     */
    public static Result resolve(Side a, Side b, int boldA, int boldB, int champA, int champB, int deathPercent, Random rng) {
        List<String> story = new ArrayList<>();
        Side[] s = { a, b };
        story.add(a.name + " " + a.tactic.words + "; " + b.name + " " + b.tactic.words + ".");
        boolean champ = false;
        int champWon = -1;
        if (champA > 0 && champB > 0) {
            int offer = offersChampion(boldA, rng) ? 0 : offersChampion(boldB, rng) ? 1 : -1;
            if (offer >= 0 && acceptsChampion(offer == 0 ? boldB : boldA)) {
                champ = true;
                champWon = rng.nextDouble() < duelOdds(champA, champB) ? 0 : 1;
                Side w = s[champWon], l = s[1 - champWon];
                l.yielded++;
                l.shake(CHAMPION_LOST);
                w.shake(-CHAMPION_WON);
                story.add("Their champions met between the lines, and " + w.name + "'s won.");
            }
        }
        int round = 0;
        Outcome out = null;
        int loser = -1;
        boolean[] flankDone = { false, false };
        for (; round < ROUNDS && out == null; round++) {
            boolean clash = round >= 3;
            int[] hits = new int[2];
            for (int i = 0; i < 2; i++) {
                Side us = s[i], them = s[1 - i];
                if (us.standing() <= 0 || them.standing() <= 0) continue;
                double bite;
                if (!clash) {
                    // The archers' duel: the bows alone, the more on a rise; a shield wall takes it on its shields.
                    bite = us.archers * 0.9 * (us.tactic == Tactic.HILL_ARCHERS ? 1.3 : 1.0) * (them.tactic == Tactic.SHIELD_WALL ? 0.75 : 1.0);
                } else {
                    double per = us.power / (double) Math.max(1, us.size);
                    bite = us.standing() * per * 0.2;
                    if (them.tactic.holds() && round < 5) bite *= 0.85;                  // they come on into a wall that waits for them
                    if (us.tactic == Tactic.FEIGNED_RETREAT && round == 5) bite *= 1.6; // turned on them, drawn on and out of order
                    if (us.tactic.flanks() && round >= 6 && !flankDone[i]) {
                        flankDone[i] = true;
                        them.flanked = true;
                        them.shake(FLANKED);
                        story.add(us.name + " " + (us.tactic == Tactic.CAVALRY ? "rode round" : "came out on") + " " + them.name + "'s flank.");
                    }
                }
                double toughness = 9.0 + (them.power / (double) Math.max(1, them.size));
                double expect = bite / toughness;
                int n = (int) expect + (rng.nextDouble() < expect - (int) expect ? 1 : 0);
                hits[1 - i] = Math.min(them.standing(), n);
            }
            for (int i = 0; i < 2; i++) {
                Side us = s[i];
                int n = hits[i];
                if (n <= 0) continue;
                int before = us.standing();
                us.yielded += n;
                for (int k = 0; k < n; k++) if (rng.nextInt(100) < deathPercent) us.dead++;
                us.power = Math.max(0, us.power - n * Math.max(1, us.power / Math.max(1, before)));
                us.shake(lossShock(n, us.size));
                // Its banner and its Captain stand at the back: struck at last as the line gives way.
                if (clash && !us.bannerLost && us.standing() * 2 < us.size && rng.nextInt(4) == 0) {
                    us.bannerLost = true;
                    us.shake(BANNER_LOST);
                    story.add(us.name + "'s banner went down.");
                }
                if (clash && !us.captainDown && us.standing() * 3 < us.size && rng.nextInt(5) == 0) {
                    us.captainDown = true;
                    us.shake(CAPTAIN_DOWN);
                    story.add(us.name + "'s Captain was struck down.");
                }
            }
            for (int i = 0; i < 2; i++) {
                s[i].shake(outnumbered(s[i].standing(), s[1 - i].standing()) + rng.nextInt(7) - 3);   // and the fortunes of the day
                // A Captain on its feet holds a wavering line together, now and then.
                if (!s[i].captainDown && s[i].morale < WAVER && s[i].morale >= BREAK && round % 4 == 0) s[i].shake(-RALLY / 2);
            }
            Outcome oa = breaks(a, b), ob = breaks(b, a);
            if (oa != null && (ob == null || a.morale < b.morale || a.morale == b.morale && rng.nextBoolean())) { out = oa; loser = 0; }
            else if (ob != null) { out = ob; loser = 1; }
        }
        int winner = loser < 0 ? -1 : 1 - loser;
        Outcome outcome = out == null ? Outcome.DRAWN : out;
        story.add(winner < 0 ? "Neither side would give way, and at nightfall both drew off."
            : s[loser].name + " " + outcome.words + "; " + s[winner].name + " held the field.");
        return new Result(winner, outcome, round, a.yielded, b.yielded, a.dead, b.dead, champ, champWon, story);
    }

    // ------------------------------------------------------------------ the siege reckoned on paper

    /**
     * Does the gate fall? The besiegers' power against the defenders' behind their wall (each sword on the wall
     * worth half as much again), a ram making it a fight at the gate rather than at the wall. Without a ram, the
     * town holds unless its food runs out before the besiegers' patience ({@code patienceDays}).
     */
    public static boolean gateFalls(int besiegers, int defenders, boolean walls, boolean ram, int foodDays, int patienceDays, Random rng) {
        if (!ram) return walls && foodDays < patienceDays || !walls && besiegers > defenders;
        double wall = walls ? 1.5 : 1.0;
        double odds = besiegers / (besiegers + defenders * wall + 1.0);
        return rng.nextDouble() < odds;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
