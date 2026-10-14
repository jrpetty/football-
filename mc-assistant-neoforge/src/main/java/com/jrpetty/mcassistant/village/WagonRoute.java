package com.jrpetty.mcassistant.village;

import java.util.Arrays;

/**
 * [wagons] A wagon's way on the ground: a line of points (x, z) and how far along each one lies, so a wagon is
 * wherever its one number, the distance driven, puts it. The same number moves a wagon in sight (TradeWagon, a
 * little each tick) and out of sight (Wagons, a stretch at a time), so it comes back into the world where it would
 * have got to. Each point may be tagged with the step of the caravan's way it stands for (or -1 for the stretch in
 * town between the yard and the road), so the trip's own count of steps keeps up with the wagon.
 *
 * <p>Pure arithmetic over arrays: no allocation once built, nothing of the game in it (unit-tested).
 */
public final class WagonRoute {

    private final double[] x, z, along;
    private final int[] step;

    /** A route through these points; {@code steps} tags each with its step of the trip's way (or -1), may be null. */
    public WagonRoute(double[] xs, double[] zs, int[] steps) {
        if (xs.length != zs.length || xs.length == 0) throw new IllegalArgumentException("points: " + xs.length + " x, " + zs.length + " z");
        this.x = xs.clone();
        this.z = zs.clone();
        this.step = steps == null ? filled(xs.length) : steps.clone();
        this.along = new double[xs.length];
        for (int i = 1; i < x.length; i++) along[i] = along[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
    }

    private static int[] filled(int n) {
        int[] out = new int[n];
        Arrays.fill(out, -1);
        return out;
    }

    public int size() { return x.length; }
    public double length() { return along[along.length - 1]; }
    public double x(int i) { return x[i]; }
    public double z(int i) { return z[i]; }
    public int step(int i) { return step[i]; }
    public double alongAt(int i) { return along[i]; }

    /** The point at or before distance {@code s} (the segment it is on starts there). */
    public int segment(double s) {
        if (s <= 0) return 0;
        if (s >= length()) return x.length - 1;
        int i = Arrays.binarySearch(along, s);
        return i >= 0 ? i : -i - 2;
    }

    /** x at distance s along (clamped to the ends). */
    public double xAt(double s) {
        int i = segment(s);
        if (i >= x.length - 1) return x[x.length - 1];
        return x[i] + (x[i + 1] - x[i]) * frac(i, s);
    }

    /** z at distance s along (clamped to the ends). */
    public double zAt(double s) {
        int i = segment(s);
        if (i >= z.length - 1) return z[z.length - 1];
        return z[i] + (z[i + 1] - z[i]) * frac(i, s);
    }

    private double frac(int i, double s) {
        double len = along[i + 1] - along[i];
        return len <= 1e-9 ? 0 : Math.max(0, Math.min(1, (s - along[i]) / len));
    }

    /**
     * The way it faces at distance s, in radians (atan2 of dz, dx), looking {@code ahead} blocks on so a corner is
     * taken as a curve rather than a snap.
     */
    public double heading(double s, double ahead) {
        double s0 = Math.max(0, Math.min(length(), s - ahead * 0.5));
        double s1 = Math.max(0, Math.min(length(), s + ahead));
        if (s1 - s0 < 1e-6) {
            int n = x.length;
            if (n < 2) return 0;
            return Math.atan2(z[n - 1] - z[n - 2], x[n - 1] - x[n - 2]);
        }
        return Math.atan2(zAt(s1) - zAt(s0), xAt(s1) - xAt(s0));
    }

    /** The highest step of the trip's way passed by distance s, or -1 if none yet. */
    public int stepPassed(double s) {
        int best = -1;
        for (int i = segment(s); i >= 0; i--) {
            if (step[i] >= 0) { best = step[i]; break; }
        }
        return best;
    }

    /** Where (distance along) the point tagged with this step of the way is, or the nearest before it; 0 if none. */
    public double alongOfStep(int wanted) {
        double best = 0;
        for (int i = 0; i < x.length; i++) {
            if (step[i] >= 0 && step[i] <= wanted) best = along[i];
        }
        return best;
    }

    /** Distance along of the point of the route nearest (x, z). */
    public double nearest(double px, double pz) {
        double best = Double.MAX_VALUE, at = 0;
        for (int i = 0; i < x.length - 1; i++) {
            double dx = x[i + 1] - x[i], dz = z[i + 1] - z[i];
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - x[i]) * dx + (pz - z[i]) * dz) / len2));
            double qx = x[i] + dx * t, qz = z[i] + dz * t;
            double d = (px - qx) * (px - qx) + (pz - qz) * (pz - qz);
            if (d < best) { best = d; at = along[i] + Math.sqrt(len2) * t; }
        }
        if (x.length == 1) return 0;
        return at;
    }

    /**
     * This route with the stretch between distances {@code from} and {@code to} replaced by a detour through these
     * points (a way round something in the road). The detour's points are untagged; the rest keep their steps.
     */
    public WagonRoute spliced(double from, double to, double[] dx, double[] dz) {
        int a = segment(from), b = segment(to) + 1;
        int keepTail = Math.max(0, x.length - b);
        int n = (a + 1) + dx.length + keepTail;
        double[] nx = new double[n], nz = new double[n];
        int[] ns = new int[n];
        int k = 0;
        for (int i = 0; i <= a; i++) { nx[k] = x[i]; nz[k] = z[i]; ns[k++] = step[i]; }
        for (int i = 0; i < dx.length; i++) { nx[k] = dx[i]; nz[k] = dz[i]; ns[k++] = -1; }
        for (int i = b; i < x.length; i++) { nx[k] = x[i]; nz[k] = z[i]; ns[k++] = step[i]; }
        return new WagonRoute(nx, nz, ns);
    }

    /** Written down: "x,z,step;x,z,step;..." (whole blocks and tenths). */
    public String encode() {
        StringBuilder sb = new StringBuilder(x.length * 16);
        for (int i = 0; i < x.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(Math.round(x[i] * 10) / 10.0).append(',').append(Math.round(z[i] * 10) / 10.0).append(',').append(step[i]);
        }
        return sb.toString();
    }

    /** Read back from {@link #encode}; null if it will not read. */
    public static WagonRoute decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] parts = s.split(";");
            double[] xs = new double[parts.length], zs = new double[parts.length];
            int[] st = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String[] p = parts[i].split(",");
                xs[i] = Double.parseDouble(p[0]);
                zs[i] = Double.parseDouble(p[1]);
                st[i] = p.length > 2 ? Integer.parseInt(p[2]) : -1;
            }
            return new WagonRoute(xs, zs, st);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
