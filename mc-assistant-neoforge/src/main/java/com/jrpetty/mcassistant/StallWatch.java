package com.jrpetty.mcassistant;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Says where the game was when it froze.
 *
 * <p>A village does a great deal on the server thread, and most of it touches
 * the world — and touching a chunk that is not loaded makes the server
 * generate it, right there, while everything waits. On flat test ground that
 * costs nothing and nobody notices; on a real world it is seconds. Whoever is
 * playing sees the game hang and has no way to say why.
 *
 * <p>A watcher thread notes when the server last finished a tick. If it has
 * been more than a second and a half, it writes the server thread's stack to
 * the log — once per couple of seconds of stall — and that stack names the
 * exact call that was holding everything up. Costs one sleeping thread.
 */
public final class StallWatch {

    private static final Logger LOG = LogUtils.getLogger();

    private static final long STALL_MS = 1500;
    private static final int FRAMES = 40;

    private static volatile long lastTick;
    private static volatile Thread serverThread;
    private static volatile boolean running;
    private static Thread watcher;

    private StallWatch() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        lastTick = 0;
        running = true;
        if (watcher != null && watcher.isAlive()) return;
        watcher = new Thread(StallWatch::watch, "mc-assistant-stall-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        running = false;
        lastTick = 0;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        serverThread = Thread.currentThread();
        lastTick = System.nanoTime();
    }

    private static void watch() {
        long lastDump = 0;
        int dumps = 0;
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            long last = lastTick;
            Thread t = serverThread;
            if (!running || last == 0 || t == null) {
                dumps = 0;
                continue;
            }
            long stalled = (System.nanoTime() - last) / 1_000_000L;
            if (stalled < STALL_MS) {
                dumps = 0;
                continue;
            }
            long now = System.nanoTime();
            if (dumps > 0 && (now - lastDump) / 1_000_000L < 2000) continue;
            if (dumps >= 6) continue;                    // one stall, six looks, no more
            StackTraceElement[] stack = t.getStackTrace();
            // A server that is waiting for its next tick — or is paused, on a
            // single-player world with the menu open — is not stalled.
            boolean idle = false;
            for (StackTraceElement frame : stack) {
                if (frame.getMethodName().equals("waitUntilNextTick")) { idle = true; break; }
            }
            if (idle) { dumps = 0; continue; }
            lastDump = now;
            dumps++;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(FRAMES, stack.length); i++) {
                sb.append("\n    at ").append(stack[i]);
            }
            LOG.warn("[MCA-STALL] the server has not finished a tick in {} ms; it is in:{}", stalled, sb);
        }
    }
}
