package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * [anim] What the folk cost the client each frame, measured (AnimDev's "perf"; off, it is one test of a flag): the frame's
 * time, the folk drawn, the time in their model's pose (FolkModel.setupAnim) and in its clip (FolkAnimations), in their
 * effects (FolkEffects), in drawing them whole (FolkRenderer.render, its layers too), and what was allocated while they
 * were drawn and in the frame as a whole. Logged every five seconds.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class AnimPerf {

    private AnimPerf() {}

    public static boolean on;

    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static long setupNs, clipNs, fxNs, renderNs, renderBytes, batchNs;
    static int drawn, posed, clipped;
    private static long last, windowFrom, frameBytesFrom, frameBytes;
    private static final long[] TIMES = new long[4096];
    private static int frames;

    static long now() { return on ? System.nanoTime() : 0L; }

    static long bytes() { return on ? MX.getCurrentThreadAllocatedBytes() : 0L; }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (!on) {
            last = 0L;
            return;
        }
        long t = System.nanoTime(), b = MX.getCurrentThreadAllocatedBytes();
        if (last != 0L) {
            if (frames < TIMES.length) TIMES[frames] = t - last;
            frames++;
            frameBytes += b - frameBytesFrom;
        } else {
            windowFrom = t;
            reset();
        }
        last = t;
        frameBytesFrom = b;
        if (t - windowFrom > 5_000_000_000L && frames > 0) {
            int n = Math.min(frames, TIMES.length);
            long[] s = Arrays.copyOf(TIMES, n);
            Arrays.sort(s);
            double mean = 0;
            for (long v : s) mean += v;
            mean /= n;
            com.mojang.logging.LogUtils.getLogger().info(String.format(java.util.Locale.ROOT,
                "[anim-perf] frames %d fps %.1f frame ms mean %.2f p50 %.2f p95 %.2f p99 %.2f | folk drawn/frame %.1f posed %.1f clipped %.1f"
                    + " | us/frame setupAnim %.1f clip %.1f effects %.1f folk render %.1f batches drawn %.1f | KB/frame folk %.2f all %.1f",
                frames, frames * 1e9 / (t - windowFrom), mean / 1e6, s[n / 2] / 1e6, s[(int) (n * 0.95)] / 1e6, s[(int) (n * 0.99)] / 1e6,
                drawn / (double) frames, posed / (double) frames, clipped / (double) frames,
                setupNs / 1e3 / frames, clipNs / 1e3 / frames, fxNs / 1e3 / frames, renderNs / 1e3 / frames, batchNs / 1e3 / frames,
                renderBytes / 1024.0 / frames, frameBytes / 1024.0 / frames));
            windowFrom = t;
            reset();
        }
    }

    private static void reset() {
        frames = 0;
        setupNs = clipNs = fxNs = renderNs = renderBytes = frameBytes = batchNs = 0L;
        drawn = posed = clipped = 0;
    }
}
