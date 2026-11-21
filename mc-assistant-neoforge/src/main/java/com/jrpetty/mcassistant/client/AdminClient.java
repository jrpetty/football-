package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.AdminAskPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * [admin] The admin panel's ways in on the player's side: a key with no default (bind it under Controls), and an
 * "Admin" button on the village board's books for operators. Both only ask; the server decides (AdminPanel).
 */
public final class AdminClient {

    private AdminClient() {}

    public static final KeyMapping PANEL = new KeyMapping("key.mc_assistant.admin_panel", InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(), "key.categories.mc_assistant");

    /** The board button's box, where it was last drawn (x, y, w, h; w 0: not drawn). Kept, not made a frame. */
    private static final int[] BOARD = new int[4];

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {}

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(PANEL);
        }
    }

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
    public static final class Ticks {
        private Ticks() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) return;
            while (PANEL.consumeClick()) open("");
            AdminScreen.tickRefresh();
        }
    }

    /** May this player see the admin ways in (an operator, as far as the client knows)? */
    public static boolean mayAdmin() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    /** Ask the server to open the panel. */
    public static void open(String town) {
        PacketDistributor.sendToServer(new AdminAskPayload("open", town, ""));
    }

    /** The board's "Admin" button, left of {@code right} by a gap: drawn for operators only. Returns its left edge (or right). */
    public static int boardButton(GuiGraphics g, Font font, int right, int y, int mx, int my) {
        if (!mayAdmin()) {
            BOARD[2] = 0;
            return right;
        }
        int w = font.width("Admin") + 10, x = right - UiStyle.S1 - w;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 18;
        UiStyle.button(g, font, "Admin", x, y, w, 18, UiStyle.State.of(true, false, over));
        if (over) UiStyle.tooltip(g, font, "The admin panel (operators only)", mx, my, 160);
        BOARD[0] = x;
        BOARD[1] = y;
        BOARD[2] = w;
        BOARD[3] = 18;
        return x;
    }

    /** A click on the board's button: the panel asked for. */
    public static boolean boardClick(double mx, double my) {
        if (BOARD[2] == 0 || mx < BOARD[0] || mx >= BOARD[0] + BOARD[2] || my < BOARD[1] || my >= BOARD[1] + BOARD[3]) return false;
        UiStyle.click();
        open("");
        return true;
    }
}
