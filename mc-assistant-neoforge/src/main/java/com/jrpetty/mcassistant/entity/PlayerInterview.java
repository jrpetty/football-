package com.jrpetty.mcassistant.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * [wpb] A real interview for a player, when two or more have applied for the same opening (JobAdverts): three questions
 * from the employer, one about the trade and two about the work in general, each with three answers to pick from on the
 * screen. A good answer is worth two marks, a fair one one, a poor one none; the marks go into the employer's weighing
 * beside the player's standing, skill and citizenship (PlayerJobs.judge) against the best folk who applied. The questions
 * and the marks are fixed, so a player who reads the card knows what a good answer is.
 */
public final class PlayerInterview {

    private PlayerInterview() {}

    /** A question, its answers, and each answer's marks (2 good, 1 fair, 0 poor). */
    public record Question(String q, String[] answers, int[] marks) {}

    static final List<Question> GENERAL = List.of(
        new Question("Why do you want this job?", new String[]{ "I want to learn the trade properly and do it well.", "It pays.", "I was bored." }, new int[]{ 2, 1, 0 }),
        new Question("If there's nothing to do on a shift, what do you do?", new String[]{ "Wait at the post and be ready when work comes in.",
            "Ask the boss if there's anything else.", "Go home early." }, new int[]{ 2, 2, 0 }),
        new Question("You've made a mistake that cost us. What then?", new String[]{ "Tell you straight away and put it right.",
            "Fix it quietly.", "Hope nobody notices." }, new int[]{ 2, 1, 0 }),
        new Question("Can you keep the hours?", new String[]{ "Yes. If I can't, I'll book leave ahead.", "Mostly.", "When I feel like it." }, new int[]{ 2, 1, 0 }),
        new Question("Somebody offers you the stores' goods cheap. What do you do?", new String[]{ "Refuse and tell the watch.", "Refuse.",
            "Ask how cheap." }, new int[]{ 2, 1, 0 }));

    static Question trade(ShiftRole r) {
        return switch (r) {
            case FARM -> new Question("You harvest a plot. What comes next?", new String[]{ "Plant it straight back before moving on.",
                "Harvest the rest, then replant.", "Leave it for someone else." }, new int[]{ 2, 1, 0 });
            case WOOD -> new Question("A tree's down. Then what?", new String[]{ "Every log in, then a sapling where it stood.", "The logs in.", "Next tree." }, new int[]{ 2, 1, 0 });
            case MINE -> new Question("You hit ore in the face. What do you do?", new String[]{ "Cut it clean and send it up with the rest.",
                "Keep it for myself.", "Leave it; ore's hard work." }, new int[]{ 2, 0, 0 });
            case FISH -> new Question("Where do you fish on a shift?", new String[]{ "At the marked water, where the catch counts.",
                "Wherever's quiet.", "I'd rather buy fish." }, new int[]{ 2, 1, 0 });
            case CRAFT, SMELT -> new Question("You're given makings for a batch. What comes back?", new String[]{ "The whole batch, and any makings left over.",
                "The batch.", "Whatever I make." }, new int[]{ 2, 1, 0 });
            case COUNTER -> new Question("Three customers are waiting. Who first?", new String[]{ "The one first in line.", "The one with most coin.",
                "Whoever's nicest." }, new int[]{ 2, 0, 0 });
            case COOK, BAKE -> new Question("The order says READY. When do you take it off?", new String[]{ "Straight away, before it burns.",
                "When I've finished the next one.", "When it smells done." }, new int[]{ 2, 0, 1 });
            case WAITER -> new Question("A plate is on the pass. What then?", new String[]{ "To its table, the right one, quickly.", "To any table.",
                "Wait till there are two." }, new int[]{ 2, 0, 1 });
            case HAUL -> new Question("You're short of the load you were sent for. What do you do?", new String[]{ "Bring what there is and say so.",
                "Bring nothing.", "Keep the deposit." }, new int[]{ 2, 1, 0 });
            case WATCH -> new Question("A monster's in the square on your round. What do you do?", new String[]{ "Deal with it, then on to the next post.",
                "Ring the bell and wait.", "Keep walking." }, new int[]{ 2, 1, 0 });
            case BUILD -> new Question("A block won't go where it's marked. What then?", new String[]{ "Check the mark and lay it right.", "Put it nearby.",
                "Leave it." }, new int[]{ 2, 0, 0 });
            case HEALER -> new Question("Two patients: one laid up, one with a scratch. Who first?", new String[]{ "The one laid up.", "The nearer.",
                "The one who pays." }, new int[]{ 2, 1, 0 });
            default -> new Question("What matters most in this trade?", new String[]{ "Doing it right, then doing it quickly.", "Speed.",
                "Getting paid." }, new int[]{ 2, 1, 0 });
        };
    }

    /** The three questions for this opening (the trade's, then two general ones by its number). */
    public static List<Question> questions(ShiftRole r, int seed) {
        List<Question> out = new ArrayList<>();
        out.add(trade(r));
        int a = Math.floorMod(seed, GENERAL.size()), b = Math.floorMod(seed + 2, GENERAL.size());
        out.add(GENERAL.get(a));
        out.add(GENERAL.get(b == a ? (a + 1) % GENERAL.size() : b));
        return out;
    }

    /** The marks as the employer's weighing takes them: up to three points for a perfect interview. */
    public static double weight(int marks) {
        return Math.max(0, Math.min(6, marks)) * 0.5;
    }

    /** The employer's word on how it went. */
    public static String verdict(int marks) {
        if (marks >= 5) return "a strong interview";
        if (marks >= 3) return "a fair interview";
        return "a weak interview";
    }
}
