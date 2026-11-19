package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.UiConfig;
import com.jrpetty.mcassistant.net.FinanceAskPayload;
import com.jrpetty.mcassistant.net.FinancePayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [purse20] The client's side of a player's finances: the key that opens the screen (unbound till the player binds
 * it; K is the suggestion), the small coin button in the inventory beside the recipe book's (E; beside the creative
 * inventory's panel), the notices
 * (the week's summary and bills due: UiConfig, each can be turned off) and the open call other screens use (the
 * village journal, the city books, the bank counter, the life-in-town page).
 */
public final class FinanceClient {

    private FinanceClient() {}

    public static final KeyMapping OPEN = new KeyMapping("key.mc_assistant.finances", InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(), "key.categories.mc_assistant");

    /** The key we suggest binding (shown on the screen while it is unbound). */
    public static final String SUGGESTED_KEY = "K";

    @Nullable private static Screen openParent;
    private static boolean wantOpen;

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
            while (OPEN.consumeClick()) if (mc.screen == null) open(null);
        }

        /** The coin button on the inventory screens. */
        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Screen s = event.getScreen();
            if (!(s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) || !UiConfig.financeButton()) return;
            CoinButton b = new CoinButton((AbstractContainerScreen<?>) s);
            b.place();
            event.addListener(b);
        }

        /** The inventory panel moves when the recipe book opens or closes: the button follows it each frame. */
        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Pre event) {
            for (var c : event.getScreen().children()) if (c instanceof CoinButton b) b.place();
        }
    }

    /** Open the finances (Esc goes back to {@code parent}). One round trip: the screen opens on the server's answer. */
    public static void open(@Nullable Screen parent) {
        if (Minecraft.getInstance().getConnection() == null) return;
        openParent = parent;
        wantOpen = true;
        CompoundTag args = new CompoundTag();
        args.putBoolean("open", true);
        ask("page", args);
    }

    /** [purse20] The entry point for other pages (player-15's "Your life in town"): opens Your finances over {@code parent}. */
    public static void openFrom(@Nullable Screen parent) {
        UiStyle.click();
        open(parent);
    }

    static void ask(String action, CompoundTag args) {
        if (Minecraft.getInstance().getConnection() == null) return;
        PacketDistributor.sendToServer(new FinanceAskPayload(action, args));
    }

    /** From the server (FinanceNetwork). */
    public static void heard(int kind, CompoundTag t) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case FinancePayload.PAGE -> {
                if (mc.screen instanceof FinanceScreen s) s.receive(t);
                else if (wantOpen || t.getBoolean("open")) {
                    Screen parent = wantOpen ? openParent : mc.screen;      // [join21] opened by the server (Your life in town): back to that page
                    wantOpen = false;
                    FinanceScreen s = new FinanceScreen(parent);
                    s.receive(t);
                    mc.setScreen(s);
                }
            }
            case FinancePayload.TOAST -> {
                boolean week = "week".equals(t.getString("kind"));
                if (week ? UiConfig.financeWeekly() : UiConfig.financeBills()) {
                    mc.getToasts().addToast(new FinanceToast(t.getString("title"), t.getString("text"), t.getInt("dir"), week));
                }
            }
            case FinancePayload.SAID -> {
                if (mc.screen instanceof FinanceScreen s) s.said(t.getString("text"));
                else if (mc.player != null) mc.player.displayClientMessage(Component.literal(t.getString("text")), true);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the city books' button

    /** The books' button's box, where it was last drawn (x, y, w, h; w 0: not drawn). */
    private static final int[] BOARD = new int[4];
    private static final ItemStack BOARD_COIN = new ItemStack(McAssistantMod.VILLAGE_COIN.get());

    /** A small coin tile in the city books' header, left of {@code right}. Returns its left edge. */
    public static int boardButton(GuiGraphics g, Font font, int right, int y, int mx, int my) {
        int w = 18, x = right - UiStyle.S1 - w;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 18;
        UiStyle.tile(g, x, y, w, 18, over ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
        g.renderItem(BOARD_COIN, x + 1, y + 1);
        BOARD[0] = x; BOARD[1] = y; BOARD[2] = w; BOARD[3] = 18;
        if (over) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            UiStyle.tooltip(g, font, "Your finances: what you have, earn, spend and owe, in every town.", mx, my, 200);
            g.pose().popPose();
        }
        return x;
    }

    /** A click on the books' button: your finances, Esc back to the books. */
    public static boolean boardClick(double mx, double my, Screen books) {
        if (BOARD[2] == 0 || mx < BOARD[0] || mx >= BOARD[0] + BOARD[2] || my < BOARD[1] || my >= BOARD[1] + BOARD[3]) return false;
        openFrom(books);
        return true;
    }

    // ------------------------------------------------------------------ the inventory's coin button

    /** A small tile with the village coin on it, just outside the inventory panel's top-right corner. */
    static final class CoinButton extends AbstractWidget {
        private static final ItemStack COIN = new ItemStack(McAssistantMod.VILLAGE_COIN.get());
        private final AbstractContainerScreen<?> screen;

        CoinButton(AbstractContainerScreen<?> screen) {
            super(0, 0, 18, 18, Component.literal("Your finances"));
            this.screen = screen;
            setTooltip(Tooltip.create(Component.literal("Your finances: what you have, earn, spend and owe")));
        }

        /**
         * In the inventory, beside the recipe book's button (a little clear of it, for other mods' buttons there), so it
         * moves with the panel as the recipe book opens and shuts; in the creative inventory, beside the panel's bottom
         * right corner (the effects stand at the top of that side, the page arrows above the panel), or under its bottom
         * tabs where there is no room beside it.
         */
        void place() {
            boolean creative = screen instanceof CreativeModeInventoryScreen;
            int x, y;
            if (creative) {
                x = screen.getGuiLeft() + screen.getXSize() + 3;
                y = screen.getGuiTop() + screen.getYSize() - height - 2;
                if (x + width > screen.width - 1) {                          // no room beside it: under the bottom tabs
                    x = screen.getGuiLeft() + screen.getXSize() - width - 1;
                    y = Math.min(screen.getGuiTop() + screen.getYSize() + 30, screen.height - height - 1);
                }
            } else {
                x = screen.getGuiLeft() + 104 + 20 + 4;
                y = screen.height / 2 - 22;
            }
            setX(x);
            setY(y);
            visible = UiConfig.financeButton();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            UiStyle.tile(g, getX(), getY(), width, height, isHoveredOrFocused() ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
            g.renderItem(COIN, getX() + 1, getY() + 1);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            UiStyle.click();
            open(screen);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    // ------------------------------------------------------------------ the notice

    /** The week's summary or the bills: a coin, the title, the line (wrapped to two), for six seconds. */
    static final class FinanceToast implements Toast {
        private static final ItemStack COIN = new ItemStack(McAssistantMod.VILLAGE_COIN.get());
        private static final ItemStack PAPER = new ItemStack(net.minecraft.world.item.Items.PAPER);
        private final String title, text;
        private final int dir;
        private final boolean week;

        FinanceToast(String title, String text, int dir, boolean week) {
            this.title = title;
            this.text = text;
            this.dir = dir;
            this.week = week;
        }

        @Override
        public int width() {
            return 210;
        }

        @Override
        public int height() {
            return 40;
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long timeSinceLastVisible) {
            Font font = toasts.getMinecraft().font;
            int w = width(), h = height();
            g.fill(0, 0, w, h, 0xFF000000);
            g.fill(1, 1, w - 1, h - 1, UiStyle.PANEL);
            g.fill(1, 1, 3, h - 1, !week ? UiStyle.WARNING_FILL : dir > 0 ? UiStyle.SUCCESS_FILL : dir < 0 ? UiStyle.DANGER_FILL : UiStyle.INFO_FILL);
            g.renderItem(week ? COIN : PAPER, 7, 12);
            g.drawString(font, UiStyle.clip(font, title, w - 34), 28, 5, UiStyle.TEXT, false);
            List<FormattedCharSequence> lines = font.split(Component.literal(text), w - 34);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), 28, 16 + i * 10, UiStyle.TEXT_MUTED, false);
            return timeSinceLastVisible >= 6000L * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
        }
    }
}
