package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.List;

/**
 * [featbuild] How much danger a town stands in, and so how much wall it wants: only arithmetic, so the plain unit tests
 * can check it (entity/TownDanger gathers the figures and keeps the stage it has reached).
 *
 * <p>Four things a council fears, weighed out of a hundred:
 * <ul>
 * <li><b>Raids seen</b> in the last thirty days: twelve each, thirty-six at most; any other alarm (monsters inside the
 *     wall, an enemy on the approach) three each, ten at most;</li>
 * <li><b>Monsters at night</b>: the watch's and the golem's kills over the last week, six for every five, twenty-four at
 *     most;</li>
 * <li><b>War</b>: twenty-five at war, ten on a war footing (a neighbour feuding, an enemy massing), four on uneasy terms;</li>
 * <li><b>Wealth</b>, which draws raiders: nothing under fifty coins in the treasury, then four for every doubling, fifteen at
 *     most; and a little for size, one for every ten folk, six at most.</li>
 * </ul>
 *
 * <p>The stages the wall goes through as the danger rises: <b>0</b> an open town (the watch only, no wall); <b>1</b> a log
 * palisade with its gates (twelve); <b>2</b> a stone wall in the age's stone (thirty); <b>3</b> towers along the walls and
 * gatehouses with a portcullis (fifty-two). A stage once reached is kept: a council does not pull its wall down because
 * a month went quietly.
 */
public final class DangerLevel {

    private DangerLevel() {}

    /** The danger at which each stage is reached: palisade, stone, towers and portcullis. */
    public static final int[] AT = { 12, 30, 52 };
    public static final int OPEN = 0, PALISADE = 1, STONE = 2, TOWERS = 3;

    /** What the town has seen: raid alarms and other alarms in thirty days, kills in a week, the war (0 peace, 1 uneasy,
     *  2 a war footing, 3 at war), the treasury's coins and the folk. */
    public record Inputs(int raids, int alarms, int kills, int war, int coins, int folk) {}

    /** The danger worked out: the score, its parts with their reasons, and the stage it calls for. */
    public record Reading(int score, int stage, List<String> reasons, int raidPart, int monsterPart, int warPart, int wealthPart) {

        /** What the next stage needs, in words ("" at the top). */
        public String next() {
            if (stage >= TOWERS) return "";
            return "danger " + AT[stage] + " for " + stageName(stage + 1);
        }
    }

    public static Reading read(Inputs in) {
        List<String> why = new ArrayList<>();
        int raid = Math.min(36, Math.max(0, in.raids()) * 12) + Math.min(10, Math.max(0, in.alarms()) * 3);
        if (in.raids() > 0) why.add(in.raids() + (in.raids() == 1 ? " raid" : " raids") + " in the last thirty days");
        if (in.alarms() > 0) why.add(in.alarms() + (in.alarms() == 1 ? " other alarm" : " other alarms") + " rung");
        int monsters = Math.min(24, (int) Math.round(Math.max(0, in.kills()) * 1.2));
        if (in.kills() > 0) why.add(in.kills() + (in.kills() == 1 ? " monster" : " monsters") + " killed at night this week");
        int war = switch (Math.max(0, Math.min(3, in.war()))) {
            case 3 -> 25;
            case 2 -> 10;
            case 1 -> 4;
            default -> 0;
        };
        if (in.war() >= 3) why.add("at war");
        else if (in.war() == 2) why.add("on a war footing");
        else if (in.war() == 1) why.add("uneasy neighbours");
        int wealth = in.coins() < 50 ? 0 : Math.min(15, (int) Math.round(Math.log(in.coins() / 50.0) / Math.log(2) * 4));
        if (wealth > 0) why.add("a treasury of " + in.coins() + " coins to tempt raiders");
        int size = Math.min(6, Math.max(0, in.folk()) / 10);
        if (size > 0) why.add(in.folk() + " folk to keep safe");
        int score = Math.min(100, raid + monsters + war + wealth + size);
        if (why.isEmpty()) why.add("nothing yet: no raids, no monsters, no enemies");
        return new Reading(score, stageFor(score), List.copyOf(why), raid, monsters, war, wealth + size);
    }

    /** The stage a score calls for. */
    public static int stageFor(int score) {
        int s = OPEN;
        for (int i = 0; i < AT.length; i++) if (score >= AT[i]) s = i + 1;
        return s;
    }

    /** The stage kept: the higher of what was reached before and what the danger calls for now. */
    public static int kept(int before, int now) {
        return Math.max(Math.max(0, Math.min(TOWERS, before)), Math.max(0, Math.min(TOWERS, now)));
    }

    public static String stageName(int stage) {
        return switch (stage) {
            case PALISADE -> "a log palisade with its gates";
            case STONE -> "a stone wall";
            case TOWERS -> "towers, gatehouses and a portcullis";
            default -> "an open town (the watch only)";
        };
    }

    /** A word for the danger, for the board. */
    public static String word(int score) {
        return score >= 70 ? "grave" : score >= AT[2] ? "high" : score >= AT[1] ? "rising" : score >= AT[0] ? "some" : "low";
    }

    /**
     * The wall's stone for a stage and an age's stone (CastleWall's tiers, 0 the palisade's logs): a palisade below the
     * stone stage whatever the age; from it, the age's stone, and cobblestone at the least.
     */
    public static int tier(int stage, int ageTier) {
        if (stage < STONE) return 0;
        return Math.max(1, ageTier);
    }

    /** Towers between the gatehouses and the corners only once the town has come to the towers' stage. */
    public static boolean midTowers(int stage) {
        return stage >= TOWERS;
    }

    /** A portcullis over each gate at the towers' stage, in stone. */
    public static boolean portcullis(int stage, int tier) {
        return stage >= TOWERS && tier >= 1;
    }
}
