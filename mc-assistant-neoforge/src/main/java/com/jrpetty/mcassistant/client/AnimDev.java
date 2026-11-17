package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [anim] For working on the folk's animations in a developer's own client, and nowhere else: none of it runs unless the
 * game was started with {@code -Dmcassistant.animdev=FILE}. The file is read again whenever it changes, and what it says
 * is put on at once, so a clip or the way a tool sits in a hand can be tried out on the running game in a second, without
 * a rebuild:
 *
 * <pre>
 *   clip SAW loop 1.0          a clip in place of the act's own, written as FolkAnimations writes its clips
 *   r RA 0 -60 10 0 0.5 -70 10 0 1.0 -60 10 0      (r / rl turn a part, p moves it; HEAD BODY RA LA RL LL)
 *   lean 0 10 1.0 10           (lean, low, twist, hit: as the builder's)
 *   end
 *   grip SAW main 0 0 0 0 0 0 1     the act's tool in the main (or off) hand: moved (pixels), turned (degrees), sized
 *   effects NONE               the effects as if the client's config said so (ALL, SOME, NONE)
 *   perf                       the folk's cost each frame, logged every five seconds (client/AnimPerf)
 *   cape                       a test cape on every player (EmoteCape)
 *   swim AT MID / sack X Y Z TILT SIZE    the swimmer's lie in the water, the sack's hang from the hand
 * </pre>
 *
 * A clip or a grip taken out of the file goes back to its own.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class AnimDev {

    private AnimDev() {}

    @Nullable
    private static final String FILE = System.getProperty("mcassistant.animdev");
    private static long stamp = Long.MIN_VALUE;
    private static int every;

    /** Grips written in the file, by act: {main, off}, each {x, y, z, xRot, yRot, zRot, size} or null. */
    static final float[][][] GRIP = new float[Act.values().length][][];
    @Nullable
    static AnimConfig.Effects effects;
    private static boolean capeByFile;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (FILE == null || ++every % 10 != 0) return;
        try {
            Path p = Path.of(FILE);
            long t = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
            if (t == stamp) return;
            stamp = t;
            read(t == 0L ? List.of() : Files.readAllLines(p));
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("[anim] animdev: {}", e.toString());
        }
    }

    private static void read(List<String> lines) {
        FolkAnimations.restoreAll();
        for (int i = 0; i < GRIP.length; i++) GRIP[i] = null;
        effects = null;
        boolean perf = false, cape = false;
        for (int i = 0; i < lines.size(); i++) {
            String[] w = lines.get(i).trim().split("\\s+");
            if (w.length == 0 || w[0].isEmpty() || w[0].startsWith("#")) continue;
            switch (w[0]) {
                case "clip" -> {
                    Act a = Act.named(w[1]);
                    List<String> body = new ArrayList<>();
                    while (++i < lines.size() && !lines.get(i).trim().equals("end")) body.add(lines.get(i));
                    if (a != null) FolkAnimations.rewrite(a, w[2].equals("loop"), Float.parseFloat(w[3]), body);
                }
                case "grip" -> {
                    Act a = Act.named(w[1]);
                    if (a == null) continue;
                    float[] g = new float[7];
                    for (int k = 0; k < 7; k++) g[k] = Float.parseFloat(w[3 + k]);
                    if (GRIP[a.ordinal()] == null) GRIP[a.ordinal()] = new float[2][];
                    GRIP[a.ordinal()][w[2].equals("off") ? 1 : 0] = g;
                }
                case "effects" -> effects = AnimConfig.Effects.valueOf(w[1].toUpperCase(Locale.ROOT));
                case "perf" -> perf = true;
                case "cape" -> cape = true;
                case "sack" -> { for (int k = 0; k < 5; k++) FolkActionLayer.SACK[k] = Float.parseFloat(w[1 + k]); }
                case "swim" -> { FolkRenderer.SWIM_AT = Float.parseFloat(w[1]); FolkRenderer.SWIM_MID = Float.parseFloat(w[2]); }
                default -> { }
            }
        }
        AnimPerf.on = perf;
        if (cape || capeByFile) EmoteCape.set(cape);
        capeByFile = cape;
        com.mojang.logging.LogUtils.getLogger().info("[anim] animdev: read {} lines", lines.size());
    }
}
