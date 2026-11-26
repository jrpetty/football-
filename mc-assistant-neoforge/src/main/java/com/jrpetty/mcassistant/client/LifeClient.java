package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.LifeNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * [player15] The ways into the player's own pages from the client: the U key (Your life in town), and two buttons in the
 * town's books' header (the village board): "You" and "Start a business".
 */
public final class LifeClient {

    private LifeClient() {}

    public static final KeyMapping OPEN = new KeyMapping("key.mc_assistant.life", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.mc_assistant");

    private static final int[] YOU = new int[4], START = new int[4], FOUND = new int[4];

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {}

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN);
        }
    }

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) return;
            while (OPEN.consumeClick()) if (mc.screen == null && !FoundingClient.resume()) open(BuildGhost.walking() ? "build" : "life");   // [found23] back to the founding screen [wpc] or to the builder
        }
    }

    public static void open(String page) {
        Waiting.start("Opening your page");                               // [ui29]
        LifeNetwork.act(page, "open", new CompoundTag());
    }

    /** The books' header buttons, right to left from {@code right}; returns the new right edge. */
    public static int boardButtons(GuiGraphics g, Font font, int right, int y, int mx, int my) {
        return boardButtons(g, font, right, y, mx, my, false);
    }

    /** [ui29] As above; {@code compact}, the short words (a narrow window, the GUI at 3 or 4), their tooltips saying it all. */
    public static int boardButtons(GuiGraphics g, Font font, int right, int y, int mx, int my, boolean compact) {
        right = one(g, font, compact ? "Found" : "New village", right, y, mx, my, FOUND, "Start a village of your own, somewhere near: the founding screen.");   // [found23]
        right = one(g, font, compact ? "Business" : "Start a business", right, y, mx, my, START, "Open a business of your own: a kind, its premises, the charter.");
        return one(g, font, "You", right, y, mx, my, YOU, "Your life in town: your work, shop, home, standing, and what to do next.");
    }

    private static int one(GuiGraphics g, Font font, String label, int right, int y, int mx, int my, int[] box, String tip) {
        int w = font.width(label) + 10, x = right - UiStyle.S1 - w;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 18;
        UiStyle.button(g, font, label, x, y, w, 18, UiStyle.State.of(true, false, over));
        box[0] = x; box[1] = y; box[2] = w; box[3] = 18;
        if (over) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            UiStyle.tooltip(g, font, tip, mx, my, 200);
            g.pose().popPose();
        }
        return x;
    }

    public static boolean boardClick(double mx, double my) {
        if (in(YOU, mx, my)) { UiStyle.click(); open("life"); return true; }
        if (in(START, mx, my)) { UiStyle.click(); open("start"); return true; }
        if (in(FOUND, mx, my)) { UiStyle.click(); FoundingClient.ask("open", "board"); return true; }   // [found23]
        return false;
    }

    private static boolean in(int[] b, double mx, double my) {
        return b[2] > 0 && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }
}
