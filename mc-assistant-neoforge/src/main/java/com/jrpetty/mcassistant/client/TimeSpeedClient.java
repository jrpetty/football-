package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.TimeSpeedAskPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Fast time on the player's side: the keys that ask for it and the corner of the screen
 * that says how fast the world is running (see {@link com.jrpetty.mcassistant.TimeSpeed}).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class TimeSpeedClient {

    private TimeSpeedClient() {}

    public static final KeyMapping FASTER = new KeyMapping(
        "key.mc_assistant.time_faster", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET,
        "key.categories.mc_assistant");
    public static final KeyMapping SLOWER = new KeyMapping(
        "key.mc_assistant.time_slower", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET,
        "key.categories.mc_assistant");
    public static final KeyMapping NORMAL = new KeyMapping(
        "key.mc_assistant.time_normal", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH,
        "key.categories.mc_assistant");

    /** What the server last said: the speed asked for, and the speed it manages (in tenths). */
    private static int factor = 1;
    private static int actualX10 = 10;

    public static int factor() { return factor; }

    public static void heard(int f, int a) {
        factor = Math.max(1, f);
        actualX10 = Math.max(0, a);
    }

    /** Ask for a speed: mode 0 sets {@code f}, 1 is a step faster, 2 a step slower. */
    public static void ask(int mode, int f) {
        PacketDistributor.sendToServer(new TimeSpeedAskPayload(mode, f));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        while (FASTER.consumeClick()) ask(1, 0);
        while (SLOWER.consumeClick()) ask(2, 0);
        while (NORMAL.consumeClick()) ask(0, 1);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        factor = 1;
        actualX10 = 10;
    }

    /** "⏩ 16× time — running 9.4×", top right, while time is fast. */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (factor <= 1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        GuiGraphics g = event.getGuiGraphics();
        String asked = "⏩ " + com.jrpetty.mcassistant.TimeSpeed.label(factor) + " time";
        String real = "running " + (actualX10 / 10) + "." + (actualX10 % 10) + "×";
        boolean behind = factor < com.jrpetty.mcassistant.TimeSpeed.MAX && actualX10 < factor * 9;
        int w = g.guiWidth();
        int y = 4;
        g.drawString(mc.font, asked, w - mc.font.width(asked) - 4, y, 0xFFFFD866, true);
        g.drawString(mc.font, real, w - mc.font.width(real) - 4, y + 10, behind ? 0xFFFF9A6A : 0xFFB8E08B, true);
        if (behind) {
            String why = "(as fast as this machine manages)";
            g.drawString(mc.font, why, w - mc.font.width(why) - 4, y + 20, 0xFF9AA3B2, true);
        }
    }
}
