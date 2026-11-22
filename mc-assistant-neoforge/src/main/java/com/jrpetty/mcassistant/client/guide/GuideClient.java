package com.jrpetty.mcassistant.client.guide;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.UiConfig;
import com.jrpetty.mcassistant.client.CinemaCam;
import com.jrpetty.mcassistant.client.UiStyle;
import com.jrpetty.mcassistant.net.VillageAskPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * [guide31] The ways into the Guide, and what it knows of the player: the Guide key (H; never while the camera is
 * filming, where H is the camera's own), a book button on the inventory screens beside the finances' coin (it moves with
 * the panel as the recipe book opens and shuts, and stands beside the creative inventory's corner), and the buttons on
 * the U page and the welcome card ({@link #open}). The book is read once from the resources; the few facts about the
 * player (GuideFacts on the server: the town stood in, what they are to it, a town of their own) are asked for each time
 * the Guide opens, on the village journal's own packet.
 */
public final class GuideClient {

    private GuideClient() {}

    public static final KeyMapping OPEN = new KeyMapping("key.mc_assistant.guide", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H,
        "key.categories.mc_assistant");

    @Nullable private static GuideSearch search;
    @Nullable private static String loadError;
    /** The player's facts as the server last said ("town" -> "Oakford"...); empty until it answers. */
    private static final Map<String, String> FACTS = new HashMap<>();
    private static boolean factsKnown;

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
            if (mc.player == null) return;
            while (OPEN.consumeClick()) if (mc.screen == null && !CinemaCam.active()) open(null);
        }

        /** The book button on the inventory screens. */
        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Screen s = event.getScreen();
            if (!(s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen)) return;
            BookButton b = new BookButton((AbstractContainerScreen<?>) s);
            b.place();
            event.addListener(b);
        }

        /** The inventory panel moves when the recipe book opens or closes: the button follows it each frame. */
        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Pre event) {
            for (var c : event.getScreen().children()) if (c instanceof BookButton b) b.place();
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            FACTS.clear();
            factsKnown = false;
        }
    }

    /** Open the Guide over {@code parent} (Esc at its first page goes back there). */
    public static void open(@Nullable Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        askFacts();
        mc.setScreen(new GuideScreen(parent));
    }

    /** Open the Guide straight at an article (the welcome card's own, say). */
    public static void openAt(@Nullable Screen parent, String article) {
        Minecraft mc = Minecraft.getInstance();
        askFacts();
        GuideScreen s = new GuideScreen(parent);
        s.showArticle(article);
        mc.setScreen(s);
    }

    private static void askFacts() {
        if (Minecraft.getInstance().getConnection() == null) return;
        try {
            PacketDistributor.sendToServer(new VillageAskPayload(GUIDE_ASK));
        } catch (RuntimeException ignored) {
            // an older server: no facts, and the Guide answers everybody alike
        }
    }

    /** The journal packet's page that asks for the Guide's facts (GuideFacts.ASK on the server). */
    static final int GUIDE_ASK = 5;
    static final String GUIDE_TITLE = "#guide";

    /** From the village journal's packet: the Guide's facts (true, and the journal is not opened), or anything else. */
    public static boolean heard(String title, String text) {
        if (!GUIDE_TITLE.equals(title)) return false;
        FACTS.clear();
        for (String pair : text.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) FACTS.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        factsKnown = true;
        if (Minecraft.getInstance().screen instanceof GuideScreen g) g.factsArrived();
        return true;
    }

    /** What the Guide knows of the player, for its answers. */
    public static PlayerFacts facts() {
        if (!factsKnown) return PlayerFacts.UNKNOWN;
        return new PlayerFacts(true, FACTS.getOrDefault("town", ""), FACTS.getOrDefault("title", ""), "1".equals(FACTS.get("citizen")),
            "1".equals(FACTS.get("own")), "1".equals(FACTS.get("shop")), parse(FACTS.get("firms")), "1".equals(FACTS.get("leader")));
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Is the player an operator, as far as the client can tell (the operators' articles are shown to them)? */
    static boolean operator() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.hasPermissions(2) || mc.hasSingleplayerServer());
    }

    /** The book and its search, read once from the resources; null (with {@link #loadError}) if it could not be read. */
    @Nullable
    static GuideSearch search() {
        if (search != null || loadError != null) return search;
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            GuideBook book = GuideBook.read(name -> {
                var res = rm.getResource(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "guide/" + name));
                if (res.isEmpty()) return null;
                try (InputStream in = res.get().open()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("guide: " + name + ": " + e.getMessage(), e);
                }
            });
            search = new GuideSearch(book);
        } catch (RuntimeException e) {
            loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            com.mojang.logging.LogUtils.getLogger().warn("[guide31] the Guide's book could not be read", e);
        }
        return search;
    }

    @Nullable
    static String loadError() {
        return loadError;
    }

    // ------------------------------------------------------------------ the inventory's book button

    /** The Guide's button on the inventory screens: a book with a question mark, beside the finances' coin. */
    static final class BookButton extends AbstractWidget {
        private static final ItemStack BOOK = new ItemStack(Items.WRITTEN_BOOK);
        private final AbstractContainerScreen<?> screen;

        BookButton(AbstractContainerScreen<?> screen) {
            super(0, 0, 18, 18, Component.literal("Guide"));
            this.screen = screen;
            setTooltip(Tooltip.create(Component.literal("Guide: ask anything about the towns and the folk (" + OPEN.getTranslatedKeyMessage().getString() + ")")));
        }

        /**
         * In the inventory, in the row of the recipe book's button, after the finances' coin (in its place when the coin
         * is hidden), inside the panel's right edge, so it moves with the panel and stays clear of other mods' buttons
         * round the panel's outside; in the creative inventory, above the coin beside the panel's bottom right corner, or
         * left of it under the bottom tabs where there is no room beside the panel.
         */
        void place() {
            boolean coin = UiConfig.financeButton();
            int x, y;
            if (screen instanceof CreativeModeInventoryScreen) {
                x = screen.getGuiLeft() + screen.getXSize() + 3;
                y = screen.getGuiTop() + screen.getYSize() - height - 2 - (coin ? height + 3 : 0);
                if (x + width > screen.width - 1) {
                    x = screen.getGuiLeft() + screen.getXSize() - width - 1 - (coin ? width + 3 : 0);
                    y = Math.min(screen.getGuiTop() + screen.getYSize() + 30, screen.height - height - 1);
                }
            } else {
                x = screen.getGuiLeft() + 128 + (coin ? 22 : 0);
                y = screen.height / 2 - 22;
            }
            setX(x);
            setY(y);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            UiStyle.tile(g, getX(), getY(), width, height, isHoveredOrFocused() ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
            g.renderItem(BOOK, getX() + 1, getY() + 1);
            // a small question mark at the corner, so it reads as the help it is
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.fill(getX() + width - 8, getY() + height - 9, getX() + width - 1, getY() + height - 1, 0xFF2C6410);
            g.drawString(Minecraft.getInstance().font, "?", getX() + width - 6, getY() + height - 9, 0xFFFFFFFF, false);
            g.pose().popPose();
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
}
