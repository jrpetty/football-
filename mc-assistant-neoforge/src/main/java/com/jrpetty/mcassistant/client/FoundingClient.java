package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.FoundEasyAskPayload;
import com.jrpetty.mcassistant.net.FoundEasyPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [found23] The client's side of starting a town: the founding screen, the notice ("Start a village of your own") and
 * the welcome card as the server sends them; the asks back; and looking at the spot with the screen put away (a line at
 * the top of the screen says so; U brings it back, as it was).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class FoundingClient {

    private FoundingClient() {}

    @Nullable private static FoundingScreen looking;
    private static long lookUntil;
    private static String lookWhere = "";

    /** From the server (FoundEasyNetwork). */
    public static void heard(int kind, CompoundTag t) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case FoundEasyPayload.OPEN -> FoundingScreen.show(t);
            case FoundEasyPayload.TOAST -> mc.getToasts().addToast(new Notice(t.getString("title"), t.getString("text")));
            case FoundEasyPayload.WELCOME -> mc.setScreen(new WelcomeScreen(t));
            default -> { }
        }
    }

    public static void ask(String action, CompoundTag args) {
        if (Minecraft.getInstance().getConnection() == null) return;
        PacketDistributor.sendToServer(new FoundEasyAskPayload(action, args));
    }

    /** Open the founding screen ("from" says where from). */
    public static void ask(String action, String from) {
        CompoundTag t = new CompoundTag();
        t.putString("from", from);
        ask(action, t);
    }

    // ------------------------------------------------------------------ looking at the spot

    static void look(FoundingScreen s, String where) {
        looking = s;
        lookWhere = where;
        lookUntil = System.currentTimeMillis() + 120_000;
        Minecraft.getInstance().setScreen(null);
    }

    static boolean looking(FoundingScreen s) {
        return looking == s && System.currentTimeMillis() < lookUntil;
    }

    static void forgetLook() {
        looking = null;
    }

    /** U pressed while looking: the screen back as it was. False if there is nothing to go back to. */
    public static boolean resume() {
        FoundingScreen s = looking;
        if (s == null) return false;
        looking = null;
        if (System.currentTimeMillis() > lookUntil) {
            FoundingGhost.fade();
            return false;
        }
        Minecraft.getInstance().setScreen(s);
        return true;
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        FoundingScreen s = looking;
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;                                          // [vision27] F1 hides it, as the game's HUD
        if (System.currentTimeMillis() > lookUntil) {
            looking = null;
            FoundingGhost.fade();
            return;
        }
        if (mc.screen != null) return;
        GuiGraphics g = e.getGuiGraphics();
        String line = lookWhere + ": gold is the square, green the first ring, white the ground made level.";
        String back = "Press U to go back to the founding screen.";
        int w = g.guiWidth();
        int lw = Math.max(mc.font.width(line), mc.font.width(back)) + 12;
        g.fill(w / 2 - lw / 2, 4, w / 2 + lw / 2, 28, 0xA0000000);
        g.drawCenteredString(mc.font, line, w / 2, 7, 0xFFFFFFFF);
        g.drawCenteredString(mc.font, back, w / 2, 17, 0xFFFFD040);
    }

    // ------------------------------------------------------------------ the notice

    /** "Start a village of your own", with the kit, for eight seconds. */
    static final class Notice implements Toast {
        private static final ItemStack KIT = new ItemStack(McAssistantMod.FOUNDING_KIT.get());
        private final String title, text;

        Notice(String title, String text) {
            this.title = title;
            this.text = text;
        }

        @Override
        public int width() {
            return 220;
        }

        @Override
        public int height() {
            return 40;
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long since) {
            Font font = toasts.getMinecraft().font;
            int w = width(), h = height();
            g.fill(0, 0, w, h, 0xFF000000);
            g.fill(1, 1, w - 1, h - 1, UiStyle.PANEL);
            g.fill(1, 1, 3, h - 1, UiStyle.SUCCESS_FILL);
            g.renderItem(KIT, 7, 12);
            g.drawString(font, UiStyle.clip(font, title, w - 34), 28, 5, UiStyle.TEXT, false);
            List<FormattedCharSequence> lines = font.split(Component.literal(text), w - 34);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), 28, 16 + i * 10, UiStyle.TEXT_MUTED, false);
            return since >= 8000L * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
        }
    }
}
