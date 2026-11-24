package com.jrpetty.mcassistant.pa;

import java.util.Random;

/**
 * [pa33g] Its archery, as a real archer's, in plain Java (so thousands of shots can be tried in a unit test through the
 * very code the game uses). Nothing here is an aimbot: the archer only ever knows where the target WAS (a reaction
 * ago), judges the distance by eye (a little off, more so at low level), leads by what it saw (imperfectly), turns its
 * head no faster than its level allows, and looses where its head is pointing, shaken by its hands. The arrow then
 * flies by the game's own drag and gravity, never steered.
 *
 * <p>Every number that sets how good it is lives in one table, {@link #TABLE}, a row a level.
 */
public final class PaArchery {

    private PaArchery() {}

    /**
     * One level's archery.
     *
     * @param swayDeg      its hands' shake at the loose (one sigma, degrees) once its aim has settled on a full draw
     * @param settleTicks  how quickly its aim settles over the draw (a short draw is shakier: x2.5 at the first tick)
     * @param drawTicks    how long it pulls before loosing (full power at 20, as a player's bow)
     * @param rangeErr     how far off its judged distance is (one sigma, a fraction): sets the lob, so the drop
     * @param leadMean     how much of the true lead it gives a moving target, on average (1 = all of it)
     * @param leadSigma    how much that varies shot to shot
     * @param reactionTicks how stale what it sees is: a change of course goes unnoticed this long
     * @param turnDeg      the most its head turns in a tick (no snapping onto a target)
     * @param trackJitter  extra shake (degrees) for each degree a tick the target sweeps across its view
     * @param moveSway     extra shake (degrees) for each block a tick it is moving itself
     * @param range        the furthest it will shoot at (blocks)
     */
    public record Row(int level, double swayDeg, double settleTicks, int drawTicks, double rangeErr, double leadMean,
                      double leadSigma, int reactionTicks, double turnDeg, double trackJitter, double moveSway, int range) {}

    /** THE table. Tune here; the unit test (PaArcheryTest) holds the shape: better with level, worse with distance and movement. */
    public static final Row[] TABLE = {
        //       lv  sway  settle draw rangeErr leadMean leadSig react  turn  track  move  range
        new Row(1,  2.00, 12.0,  26,  0.120,   0.25,    0.50,   12,   6.0,  0.90,  8.0,  16),
        new Row(2,  1.75, 11.0,  25,  0.100,   0.35,    0.45,   11,   7.0,  0.80,  7.0,  19),
        new Row(3,  1.50, 10.0,  24,  0.090,   0.45,    0.40,   10,   8.0,  0.70,  6.0,  22),
        new Row(4,  1.30,  9.0,  23,  0.080,   0.60,    0.33,    9,   9.5,  0.60,  5.5,  25),
        new Row(5,  1.10,  8.0,  22,  0.065,   0.70,    0.28,    8,  11.0,  0.52,  5.0,  28),
        new Row(6,  0.90,  7.5,  22,  0.055,   0.78,    0.24,    7,  12.5,  0.45,  4.5,  31),
        new Row(7,  0.75,  7.0,  21,  0.045,   0.85,    0.20,    6,  14.0,  0.40,  4.0,  34),
        new Row(8,  0.64,  6.5,  21,  0.040,   0.90,    0.13,    5,  16.0,  0.35,  3.5,  37),
        new Row(9,  0.54,  6.0,  20,  0.035,   0.93,    0.10,    4,  18.0,  0.30,  3.0,  40),
        new Row(10, 0.45,  5.5,  20,  0.030,   0.96,    0.08,    3,  20.0,  0.27,  2.5,  43),
    };

    public static Row row(int level) {
        return TABLE[Math.max(1, Math.min(TABLE.length, level)) - 1];
    }

    /** The game's arrow: drag and gravity a tick, its speed off a full draw, and how far below the eye it leaves. */
    public static final double DRAG = 0.99, GRAVITY = 0.05, FULL_SPEED = 3.0, EYE_DROP = 0.1;
    /** Nearer than this, a quick snap of a draw (half its usual pull: weaker and shakier, as anyone's). */
    public static final double CLOSE = 8.0;
    /** Ticks to nock an arrow before the pull starts. */
    public static final int NOCK = 3;

    /** The game's own bow: power for so many ticks of draw (1 at twenty). */
    public static double power(int ticks) {
        double f = Math.max(0, ticks) / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        return Math.min(1.0, f);
    }

    /** A unit direction from the game's yaw and pitch (yaw 0 faces +z; pitch above 0 looks down). */
    public static double[] dir(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /** The game's yaw that faces along (dx, dz). */
    public static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    public static double wrap(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(hi, v);
    }

    /** About how many ticks an arrow at this speed takes to cover so much ground (fired near level). */
    public static int flightTicks(double speed, double horiz) {
        double x = 0, v = speed;
        for (int t = 1; t < 200; t++) {
            x += v;
            if (x >= horiz) return t;
            v *= DRAG;
        }
        return 200;
    }

    /** The arrow's height when it has come so far out, fired at this elevation (degrees up); -infinity if it never gets there. */
    static double heightAt(double speed, double elevDeg, double horiz) {
        double r = Math.toRadians(elevDeg);
        double vh = Math.cos(r) * speed, vy = Math.sin(r) * speed, x = 0, y = 0;
        for (int t = 0; t < 300; t++) {
            double nx = x + vh, ny = y + vy;
            if (nx >= horiz) return y + (ny - y) * ((horiz - x) / Math.max(1e-9, vh));
            x = nx;
            y = ny;
            vh *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (y < -300) break;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** The pitch (the game's: negative is up) that lands an arrow of this speed so far out and so far up, as the game flies it. */
    public static double solvePitch(double speed, double horiz, double dy) {
        double lo = -60, hi = 42;
        if (horiz < 0.5) return -Math.toDegrees(Math.atan2(dy, Math.max(0.01, horiz)));
        if (heightAt(speed, hi, horiz) < dy) return -hi;                 // out of reach: its best lob
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) * 0.5;
            if (heightAt(speed, mid, horiz) < dy) lo = mid; else hi = mid;
        }
        return -(lo + hi) * 0.5;
    }

    /**
     * One draw, tick by tick: what it has seen of the target, where it means to aim, where its head points, how long
     * it has pulled. Fixed for the draw: how far off its judgement of the distance is and how much lead it will give.
     */
    public static final class Shooter {
        public final Row row;
        private final Random rnd;
        /** Where its head points now, and where it means it to. */
        public double yaw, pitch, wantYaw, wantPitch;
        /** Ticks of draw so far (the bow's own count). */
        public int ticks;
        public final int plannedDraw;
        private final double rangeJudge, leadJudge;
        private final double[][] seen = new double[64][];
        private int n;
        private double angSpeed, lastBearing = Double.NaN;

        public Shooter(int level, Random rnd, double yaw, double pitch, double dist) {
            this.row = row(level);
            this.rnd = rnd;
            this.yaw = yaw;
            this.pitch = pitch;
            this.wantYaw = yaw;
            this.wantPitch = pitch;
            this.plannedDraw = dist < CLOSE ? Math.max(10, row.drawTicks() / 2) : row.drawTicks();
            this.rangeJudge = Math.max(0.6, 1.0 + rnd.nextGaussian() * row.rangeErr());
            this.leadJudge = row.leadMean() + rnd.nextGaussian() * row.leadSigma();
        }

        /** What it saw k ticks ago (k=0: just now). */
        private double[] at(int k) {
            return seen[Math.floorMod(n - 1 - k, seen.length)];
        }

        /** The speed it means to loose at (its planned draw, or more if it is still pulling). */
        public double speed() {
            return FULL_SPEED * power(Math.max(ticks, plannedDraw));
        }

        /**
         * One tick: its eye and the target's middle as they are. It works from what it saw a reaction ago, carried on
         * by the motion it saw then, leads that by the arrow's flight (as much as it judges), lobs for the distance it
         * judges, and turns its head toward all that no faster than it can.
         */
        public void see(double ex, double ey, double ez, double tx, double ty, double tz) {
            seen[Math.floorMod(n, seen.length)] = new double[]{tx, ty, tz};
            n++;
            int a = Math.min(row.reactionTicks(), n - 1), b = Math.min(a + 5, n - 1);
            double[] p = at(a);
            double vx = 0, vy = 0, vz = 0;
            if (b > a) {
                double[] q = at(b);
                vx = (p[0] - q[0]) / (b - a);
                vy = (p[1] - q[1]) / (b - a);
                vz = (p[2] - q[2]) / (b - a);
            }
            double px = p[0] + vx * a, py = p[1] + vy * a, pz = p[2] + vz * a;
            double sp = speed();
            int fly = flightTicks(sp, Math.hypot(px - ex, pz - ez) * rangeJudge);
            px += vx * fly * leadJudge;
            py += vy * fly * leadJudge;
            pz += vz * fly * leadJudge;
            double dx = px - ex, dz = pz - ez;
            wantYaw = yawTo(dx, dz);
            wantPitch = solvePitch(sp, Math.hypot(dx, dz) * rangeJudge, py - (ey - EYE_DROP));
            double bearing = yawTo(tx - ex, tz - ez);
            angSpeed = Double.isNaN(lastBearing) ? 0 : angSpeed * 0.7 + 0.3 * Math.abs(wrap(bearing - lastBearing));
            lastBearing = bearing;
            double turn = row.turnDeg();
            yaw = yaw + clamp(wrap(wantYaw - yaw), -turn, turn);
            pitch = clamp(pitch + clamp(wantPitch - pitch, -turn, turn), -90, 90);
        }

        public boolean onTarget() {
            return Math.abs(wrap(wantYaw - yaw)) < 1.0 && Math.abs(wantPitch - pitch) < 1.0;
        }

        /** Pulled long enough and on its mark (or held so long it lets fly regardless). */
        public boolean ready() {
            return ticks >= plannedDraw && (onTarget() || ticks >= plannedDraw + 30);
        }

        /** Its hands' shake now (one sigma, degrees): its level, how settled the draw is, a long hold, tracking, its own movement. */
        public double sway(double ownSpeed) {
            double settle = 1.0 + 1.5 * Math.exp(-ticks / row.settleTicks());
            double tired = ticks > 80 ? 1.0 + (ticks - 80) / 60.0 : 1.0;
            return row.swayDeg() * settle * tired + row.trackJitter() * angSpeed + row.moveSway() * ownSpeed;
        }

        /** Loosed: the arrow's velocity (blocks a tick), off where its head points, shaken by its hands. */
        public double[] loose(double ownSpeed) {
            double s = sway(ownSpeed);
            double[] d = dir(yaw + rnd.nextGaussian() * s, pitch + rnd.nextGaussian() * s);
            double v = FULL_SPEED * power(ticks);
            return new double[]{d[0] * v, d[1] * v, d[2] * v};
        }
    }

    // ------------------------------------------------------------------ trying it out (the unit test, the guide's table)

    /** A mob-sized target as the game widens it for an arrow (0.6 by 1.8, and 0.3 more all round). */
    static final double HALF_W = 0.6, LOW = -0.3, HIGH = 2.1;
    /** A strafing target's pace (a walking mob or player, blocks a tick) and its archer's when walking. */
    public static final double STRAFE = 0.22, WALK = 0.15;

    /**
     * One shot at a mob-sized target so far off (standing, or strafing with changes of course), by an archer standing
     * or walking, from its first sight of it (its head 45 degrees off to begin with). True for a hit.
     */
    public static boolean trial(int level, double dist, boolean moving, boolean walking, Random rnd) {
        double ex = 0, ey = 1.62, ez = 0;
        double dirX = rnd.nextBoolean() ? 1 : -1, tx = moving ? -dirX * 6 : 0, tz = dist;   // a strafer starts off to one side
        Shooter s = new Shooter(level, rnd, rnd.nextBoolean() ? 45 : -45, 0, dist);
        double[] arrow = null, vel = null;
        for (int t = 0; t < 400; t++) {
            if (moving) {
                if (rnd.nextInt(40) == 0) dirX = -dirX;                  // a change of course, now and then
                tx += dirX * STRAFE;
                if (Math.abs(tx) > 10) dirX = -Math.signum(tx);
            }
            if (walking && arrow == null) ex += WALK;
            if (arrow == null) {
                s.ticks = Math.max(0, t - NOCK);
                s.see(ex, ey, ez, tx, 0.9, tz);
                if (s.ticks > 0 && s.ready()) {
                    vel = s.loose(walking ? WALK : 0);
                    arrow = new double[]{ex, ey - EYE_DROP, ez};
                }
                continue;
            }
            double[] next = {arrow[0] + vel[0], arrow[1] + vel[1], arrow[2] + vel[2]};
            if (segmentHitsBox(arrow, next, tx - HALF_W, LOW, tz - HALF_W, tx + HALF_W, HIGH, tz + HALF_W)) return true;
            if (next[1] < 0 || next[2] > dist + 30) return false;         // in the ground, or long past
            arrow = next;
            vel = new double[]{vel[0] * DRAG, vel[1] * DRAG - GRAVITY, vel[2] * DRAG};
        }
        return false;
    }

    /** The share of n shots that hit (a fixed seed: the same every run). */
    public static double hitRate(int level, double dist, boolean moving, boolean walking, int n, long seed) {
        Random rnd = new Random(seed);
        int hits = 0;
        for (int i = 0; i < n; i++) if (trial(level, dist, moving, walking, rnd)) hits++;
        return hits / (double) n;
    }

    /** Does the segment a-b pass through the box? */
    static boolean segmentHitsBox(double[] a, double[] b, double x0, double y0, double z0, double x1, double y1, double z1) {
        double t0 = 0, t1 = 1;
        double[] lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int k = 0; k < 3; k++) {
            double d = b[k] - a[k];
            if (Math.abs(d) < 1e-12) {
                if (a[k] < lo[k] || a[k] > hi[k]) return false;
                continue;
            }
            double u = (lo[k] - a[k]) / d, w = (hi[k] - a[k]) / d;
            if (u > w) { double s = u; u = w; w = s; }
            t0 = Math.max(t0, u);
            t1 = Math.min(t1, w);
            if (t0 > t1) return false;
        }
        return true;
    }

    private static volatile int[][] cached30;
    private static final java.util.concurrent.atomic.AtomicBoolean WORKING = new java.util.concurrent.atomic.AtomicBoolean();

    /** For the guide and the Stats tab: each level's hit rate (percent) at 30 blocks on a standing mob and a strafing one,
     *  worked out by trying it (the same draws for every level, so the column reads cleanly). Worked out once, off the
     *  game's thread: null until it's ready (a second or so). */
    @javax.annotation.Nullable
    public static int[][] at30() {
        if (cached30 == null && WORKING.compareAndSet(false, true)) {
            Thread t = new Thread(PaArchery::at30Now, "pa-archery-table");
            t.setDaemon(true);
            t.start();
        }
        return cached30;
    }

    /** The same, worked out here and now if need be. */
    public static synchronized int[][] at30Now() {
        if (cached30 == null) {
            int[][] out = new int[TABLE.length][2];
            for (int l = 1; l <= TABLE.length; l++) {
                out[l - 1][0] = (int) Math.round(100 * hitRate(l, 30, false, false, 500, 3030L));
                out[l - 1][1] = (int) Math.round(100 * hitRate(l, 30, true, false, 500, 3031L));
            }
            cached30 = out;
        }
        return cached30;
    }
}
