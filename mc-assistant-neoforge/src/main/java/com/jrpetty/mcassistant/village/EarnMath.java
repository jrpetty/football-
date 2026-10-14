package com.jrpetty.mcassistant.village;

/**
 * [earning] The arithmetic of a player's earnings, with no Minecraft in it (so the plain tests check it): a task's
 * stars from how fast and how well it was done, the job skill's levels and what they add to pay, what a task pays,
 * and how a buyer's offer falls as its want fills (the glut).
 */
public final class EarnMath {

    private EarnMath() {}

    // ------------------------------------------------------------------ a task's stars

    /** Speed against par for each star (5 at or under par; 4 within a third over; 3 within double; 2 within triple). */
    static final double[] SPEED_FOR = { 1.0, 1.34, 2.0, 3.0 };

    /**
     * The stars (1 to 5) a task earns: speed against its par time (took <= par is five), less a star for each mistake
     * (the wrong table, the wrong customer, a block in the wrong place), less the quality lost (a burnt dish, a plot not
     * replanted: given in whole stars). Never under one for a task that was finished.
     */
    public static int stars(long parTicks, long tookTicks, int mistakes, int qualityLost) {
        double ratio = parTicks <= 0 ? 1.0 : Math.max(0, tookTicks) / (double) parTicks;
        int s = 1;
        for (int i = SPEED_FOR.length - 1; i >= 0; i--) {
            if (ratio <= SPEED_FOR[i]) s = 5 - i;
        }
        s -= Math.max(0, mistakes) + Math.max(0, qualityLost);
        return Math.max(1, Math.min(5, s));
    }

    /** "★★★★☆" */
    public static String starWord(int stars) {
        int s = Math.max(0, Math.min(5, stars));
        return "★".repeat(s) + "☆".repeat(5 - s);
    }

    /** What each star pays of the task's wage: a poor task pays less, a fine one a little more. */
    static final double[] STAR_PAY = { 0.4, 0.6, 0.8, 1.0, 1.15 };

    public static double starPay(int stars) {
        return STAR_PAY[Math.max(1, Math.min(5, stars)) - 1];
    }

    // ------------------------------------------------------------------ job skill

    /** The skill's levels and the experience each starts at. */
    public static final String[] LEVELS = { "Novice", "Apprentice", "Skilled", "Expert", "Master" };
    public static final int[] LEVEL_XP = { 0, 40, 120, 280, 560 };
    /** What each level adds to pay, a tenth a level. */
    public static final double PAY_PER_LEVEL = 0.10;
    /** The level a senior post (head cook, foreman) asks for, and what it adds to the wage. */
    public static final int SENIOR_LEVEL = 2;
    public static final double SENIOR_PAY = 1.25;

    /** The level (0 Novice .. 4 Master) for so much experience. */
    public static int level(int xp) {
        int l = 0;
        for (int i = 0; i < LEVEL_XP.length; i++) if (xp >= LEVEL_XP[i]) l = i;
        return l;
    }

    /** How far through its level so much experience is, 0..1 (1 at Master). */
    public static double levelProgress(int xp) {
        int l = level(xp);
        if (l >= LEVEL_XP.length - 1) return 1.0;
        return (xp - LEVEL_XP[l]) / (double) (LEVEL_XP[l + 1] - LEVEL_XP[l]);
    }

    /** The experience a task of so many stars is worth: two a star, a mistake-ridden one still teaches a little. */
    public static int xpFor(int stars) {
        return 2 * Math.max(1, Math.min(5, stars));
    }

    /** The skill's pay multiplier. */
    public static double skillPay(int level) {
        return 1.0 + PAY_PER_LEVEL * Math.max(0, Math.min(LEVELS.length - 1, level));
    }

    /** A task's pay in coin (to the hundredth): its wage, the stars, the skill, a senior post's share. */
    public static double taskPay(double wage, int stars, int level, boolean senior) {
        return Math.max(0, wage) * starPay(stars) * skillPay(level) * (senior ? SENIOR_PAY : 1.0);
    }

    /** The chance in a hundred that a customer tips for a task of so many stars, more for a better hand. */
    public static int tipChance(int stars, int level) {
        if (stars < 4) return 0;
        return (stars == 5 ? 45 : 20) + 5 * Math.max(0, level);
    }

    /** A shift is poor when its tasks averaged under this many stars (in tenths), or nothing was done in it. */
    public static final int POOR_TENTHS = 25;
    /** Poor shifts in a row before a warning, and before a standing job is lost. */
    public static final int WARN_AFTER = 2, LET_GO_AFTER = 3;

    public static boolean poorShift(int tasks, int starTotal) {
        return tasks <= 0 || starTotal * 10 < POOR_TENTHS * tasks;
    }

    // ------------------------------------------------------------------ a buyer's want and the glut

    /**
     * What a buyer offers for one more of a thing against its own price {@code bid}: the whole bid while it still
     * wants more than half of what it keeps, falling to a quarter of it as its stock nears what it keeps, and nothing
     * once it has it ({@code have >= keep}).
     */
    public static double glutted(double bid, int have, int keep) {
        if (keep <= 0 || have >= keep) return 0;
        double room = (keep - Math.max(0, have)) / (double) keep;          // 1 empty .. 0 full
        if (room >= 0.5) return bid;
        return bid * (0.25 + 1.5 * room);                                    // 1.0 at half full .. 0.25 nearly full
    }

    /** How many more a buyer wants (0 when it has what it keeps). */
    public static int wants(int have, int keep) {
        return Math.max(0, keep - Math.max(0, have));
    }
}
