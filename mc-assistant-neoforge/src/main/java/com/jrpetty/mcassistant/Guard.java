package com.jrpetty.mcassistant;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A village is a thousand small decisions a second, most of them made by code
 * that has only ever met the situations the tests thought of. One of them
 * throwing an exception must not take the world down with it: an exception
 * out of an entity's tick or an event handler is, in this game, a crash report
 * and a stopped server. So the places where the settlement's code meets the
 * game's loop are wrapped here. A failure is written to the log — with its
 * stack, the first few times and then every hundredth — and that one folk or
 * that one handler misses a beat, instead of everybody losing the world.
 */
public final class Guard {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

    private Guard() {}

    /** Run this; if it throws, say so and carry on. */
    public static void run(String what, Runnable body) {
        try {
            body.run();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;                                   // out of memory is not ours to swallow
        } catch (Throwable t) {
            struck(what, t);
        }
    }

    /** Write a failure down. The first three of each kind with their stack, then one in a hundred. */
    public static void struck(String what, Throwable t) {
        struck(what, "", t);
    }

    /** As above, with where it happened (kept out of the key, so the count is per kind of failure). */
    public static void struck(String what, String detail, Throwable t) {
        int n = COUNTS.computeIfAbsent(what, k -> new AtomicInteger()).incrementAndGet();
        if (n <= 3 || n % 100 == 0) {
            LOG.error("[MCA-GUARD] {} failed (#{}){}: {} — carrying on", what, n,
                detail.isEmpty() ? "" : " [" + detail + "]", t.toString(), t);
        }
    }

    /** Forget the counts. For tests. */
    public static void reset() {
        COUNTS.clear();
    }
}
