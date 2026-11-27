package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * [featpa] For photographing the Blueprints, Errand and Stance screens in a developer's own client, and nowhere else:
 * nothing runs unless the game was started with {@code -Dmcassistant.kitdev=FILE}. Each time the file changes its lines
 * are carried out once, in order, as the screens' own buttons would send them:
 * <pre>
 *   open blueprints|errand|stance|close     a screen opened (and its state asked for), or closed
 *   act ACTION [k=v ...]                    an assistant action ("act summon")
 *   op OP [k=v ...]                         a screen button's op ("op bp_aim name=Hut", "op st_set k=1")
 *   talk TEXT                               said to it, as typed in its talk box
 *   scale N                                 the GUI scale
 *   hints reset|seen                        the first-time hints shown again, or marked seen
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class PaKitDev {

    private PaKitDev() {}

    @Nullable private static final String FILE = System.getProperty("mcassistant.kitdev");
    private static long stamp = Long.MIN_VALUE;
    private static int every;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        if (FILE == null || ++every % 5 != 0) return;
        try {
            Path p = Path.of(FILE);
            long t = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
            if (t == stamp) return;
            boolean first = stamp == Long.MIN_VALUE;
            stamp = t;
            if (first || t == 0L) return;
            for (String line : Files.readAllLines(p)) run(line.trim());
        } catch (Exception ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[featpa] kitdev: {}", ex.toString());
        }
    }

    private static CompoundTag args(String[] w, int from) {
        CompoundTag t = new CompoundTag();
        for (int i = from; i < w.length; i++) {
            int eq = w[i].indexOf('=');
            if (eq <= 0) continue;
            String k = w[i].substring(0, eq), v = w[i].substring(eq + 1).replace('_', ' ');
            if (v.matches("-?\\d+")) t.putInt(k, Integer.parseInt(v));
            else if (v.equals("true") || v.equals("false")) t.putBoolean(k, Boolean.parseBoolean(v));
            else t.putString(k, v);
        }
        return t;
    }

    private static void run(String line) {
        if (line.isEmpty() || line.startsWith("#")) return;
        String[] w = line.split("\\s+");
        Minecraft mc = Minecraft.getInstance();
        switch (w[0]) {
            case "open" -> {
                switch (w.length > 1 ? w[1] : "") {
                    case "blueprints" -> PaKitClient.openBlueprints();
                    case "errand" -> PaKitClient.openErrand();
                    case "stance" -> PaKitClient.openStance();
                    default -> mc.setScreen(null);
                }
            }
            case "act" -> { if (w.length > 1) PaClient.send(w[1], args(w, 2)); }
            case "op" -> { if (w.length > 1) PaKitClient.send(w[1], args(w, 2)); }
            case "talk" -> PaClient.talk(line.substring(4).trim(), false);
            case "scale" -> {
                if (w.length > 1) {
                    mc.options.guiScale().set(Integer.parseInt(w[1]));
                    mc.resizeDisplay();
                }
            }
            case "hints" -> PaClientConfig.set(PaClientConfig.KITHINTS, w.length > 1 && w[1].equals("seen") ? "bp,er,st" : "");
            default -> { }
        }
    }
}
