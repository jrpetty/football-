package com.jrpetty.mcassistant.admin;

import net.minecraft.Util;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [admin] Each town's own rounds timed (the town-wide work its folk run in their tick: elections, homes, families,
 * the streets, the market's morning...), only while somebody is looking: the panel open, /village admin debug cost or
 * inspect town. Unwatched it costs one volatile read a folk tick and nothing else. What it reports is the town's
 * share of a second, in milliseconds a second, over the last measured window.
 */
public final class AdminProbe {

    private AdminProbe() {}

    /** How long a look keeps the probe on, in milliseconds. */
    private static final long WATCH_MS = 15_000L;
    /** The window each figure is measured over. */
    private static final long WINDOW_MS = 2_000L;

    private static volatile long watchUntil;
    private static final Map<UUID, long[]> NANOS = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> LAST = new ConcurrentHashMap<>();
    private static long windowFrom;

    /** Somebody looked: keep timing for a while. */
    public static void watch() {
        long now = Util.getMillis();
        if (watchUntil < now) {
            windowFrom = now;
            NANOS.clear();
        }
        watchUntil = now + WATCH_MS;
    }

    /** The start of a timed stretch: 0 (nothing timed) unless watched. */
    public static long start() {
        return watchUntil == 0L ? 0L : System.nanoTime();
    }

    public static void stop(@Nullable UUID town, long t0) {
        if (t0 == 0L || town == null) return;
        long spent = System.nanoTime() - t0;
        NANOS.computeIfAbsent(town, k -> new long[1])[0] += spent;
        long now = Util.getMillis();
        if (now - windowFrom >= WINDOW_MS) roll(now);
    }

    private static synchronized void roll(long now) {
        long span = Math.max(1L, now - windowFrom);
        for (Map.Entry<UUID, long[]> e : NANOS.entrySet()) {
            LAST.put(e.getKey(), e.getValue()[0] / 1e6 * 1000.0 / span);
            e.getValue()[0] = 0L;
        }
        windowFrom = now;
        if (now > watchUntil) watchUntil = 0L;            // nobody looking any more: off until the next look
    }

    /** The town's rounds in milliseconds a second, or -1 when not measured yet. */
    public static double msPerSecond(UUID town) {
        Double d = LAST.get(town);
        return d == null ? -1.0 : d;
    }
}
