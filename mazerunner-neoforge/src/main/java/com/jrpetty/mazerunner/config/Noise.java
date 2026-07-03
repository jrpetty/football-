package com.jrpetty.mazerunner.config;

/** Deterministic hash + smooth value-noise helpers — pure math, no Minecraft. */
public final class Noise {

    private Noise() {}

    /** 0..1 hash of two ints (position-stable across runs and worlds). */
    public static double hash2(int x, int z, int salt) {
        int h = x * 0x9E3779B1 ^ z * 0x85EBCA6B ^ salt * 0xC2B2AE35;
        h ^= h >>> 13;
        h *= 0x27D4EB2F;
        h ^= h >>> 15;
        return (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }

    /** 0..1 hash of three ints. */
    public static double hash3(int x, int y, int z, int salt) {
        return hash2(x * 31 + y, z * 31 - y, salt);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t); // smoothstep
    }

    /**
     * Smooth value noise in 0..1: bilinear-interpolated lattice hash at the
     * given feature scale (bigger scale = broader features).
     */
    public static double value2(double x, double z, double scale, int salt) {
        double fx = x / scale;
        double fz = z / scale;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0);
        double tz = smooth(fz - z0);
        double a = hash2(x0, z0, salt);
        double b = hash2(x0 + 1, z0, salt);
        double c = hash2(x0, z0 + 1, salt);
        double d = hash2(x0 + 1, z0 + 1, salt);
        double ab = a + (b - a) * tx;
        double cd = c + (d - c) * tx;
        return ab + (cd - ab) * tz;
    }

    /** Two-octave value noise in 0..1. */
    public static double fbm2(double x, double z, double scale, int salt) {
        return (value2(x, z, scale, salt) * 2 + value2(x, z, scale / 2.7, salt ^ 0x51ED270)) / 3;
    }
}
