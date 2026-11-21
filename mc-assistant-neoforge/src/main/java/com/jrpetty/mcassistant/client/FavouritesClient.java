package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.UiConfig;
import com.jrpetty.mcassistant.net.FavouritesAskPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [ui] The client's side of the favourites: the key that opens the screen (unbound until the player binds it; the
 * screen also opens from the city books and a folk's profile), the stars' state as the server last said, the toast for
 * a milestone (UiConfig.toasts), and the small tracker for the pinned favourite (UiConfig.tracker, off by default),
 * which asks for the list once every thirty seconds while it is shown and never otherwise.
 */
public final class FavouritesClient {

    private FavouritesClient() {}

    public static final KeyMapping OPEN = new KeyMapping("key.mc_assistant.favourites", InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(), "key.categories.mc_assistant");

    /** Whether each folk is on the list, as the server last said (for the stars). */
    private static final Map<UUID, Boolean> STARS = new HashMap<>();
    /** The last list the server sent (for the tracker), and what the server said to the last star. */
    @Nullable private static CompoundTag lastList;
    private static String starSaid = "";
    private static long starSaidAt;
    /** The screen to go back to when the favourites screen asked for opens, and whether one was asked for. */
    @Nullable private static net.minecraft.client.gui.screens.Screen openParent;
    @Nullable private static UUID openDetail;
    private static boolean wantOpen;
    private static int trackerTicks;

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
            while (OPEN.consumeClick()) if (mc.screen == null) open(null, null);
            if (UiConfig.tracker() && !UiConfig.pinned().isEmpty() && ++trackerTicks % 600 == 1 && mc.screen == null) {
                PacketDistributor.sendToServer(new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 0));
            }
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            STARS.clear();
            lastList = null;
            wantOpen = false;
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            tracker(event.getGuiGraphics());
        }
    }

    /** Open the favourites screen (the list, or one folk's detail when {@code folk} is given); Esc goes to {@code parent}. */
    public static void open(@Nullable UUID folk, @Nullable net.minecraft.client.gui.screens.Screen parent) {
        wantOpen = true;
        openParent = parent;
        openDetail = folk;
        PacketDistributor.sendToServer(folk == null ? new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 1)
            : new FavouritesAskPayload("detail", folk, 1));
    }

    /** Star or unstar a folk (its profile, its talk screen). */
    public static void toggle(UUID folk) {
        UiStyle.click();
        STARS.put(folk, !starred(folk, false));                          // at once, till the server says
        PacketDistributor.sendToServer(new FavouritesAskPayload("toggle", folk, 0));
    }

    /** Is it on the list? {@code otherwise}: what its screen's data said, till a star is pressed. */
    public static boolean starred(UUID folk, boolean otherwise) {
        Boolean b = STARS.get(folk);
        return b == null ? otherwise : b;
    }

    /** What the server said to the last star (for a moment), or "". */
    public static String starSaid() {
        return net.minecraft.Util.getMillis() - starSaidAt < 3000 ? starSaid : "";
    }

    /** From the server (FavouritesNetwork). */
    public static void receive(CompoundTag data, boolean open) {
        Minecraft mc = Minecraft.getInstance();
        String kind = data.getString("kind");
        switch (kind) {
            case "close" -> { if (mc.screen instanceof FavouritesScreen s) s.onClose(); }
            case "star" -> {
                STARS.put(data.getUUID("uuid"), data.getBoolean("fav"));
                starSaid = data.getString("said");
                starSaidAt = net.minecraft.Util.getMillis();
                if (mc.screen instanceof FavouritesScreen s) s.refresh();
            }
            case "toast" -> {
                if (UiConfig.toasts()) mc.getToasts().addToast(new MilestoneToast(data.getString("name"), data.getString("text"), data.getString("event")));
            }
            case "list", "detail" -> {
                if (kind.equals("list")) {
                    lastList = data;
                    ListTag cards = data.getList("cards", Tag.TAG_COMPOUND);
                    for (int i = 0; i < cards.size(); i++) STARS.put(cards.getCompound(i).getUUID("uuid"), true);
                }
                if (mc.screen instanceof FavouritesScreen s) {
                    s.receive(data);
                } else if (open || wantOpen && (kind.equals("list") ? openDetail == null : data.hasUUID("uuid") && data.getUUID("uuid").equals(openDetail))) {
                    wantOpen = false;
                    FavouritesScreen s = new FavouritesScreen(open ? mc.screen : openParent);
                    s.receive(data);
                    mc.setScreen(s);
                }
            }
            default -> { }
        }
    }

    /** The icon for an event of a folk's life. */
    public static ItemStack icon(String kind) {
        return new ItemStack(switch (kind) {
            case "level" -> Items.EXPERIENCE_BOTTLE;
            case "trade" -> Items.CRAFTING_TABLE;
            case "laid_off" -> Items.BARRIER;
            case "raise" -> Items.GOLD_NUGGET;
            case "cut" -> Items.IRON_NUGGET;
            case "married" -> Items.POPPY;
            case "widowed" -> Items.WITHER_ROSE;
            case "child" -> Items.CAKE;
            case "moved" -> Items.OAK_DOOR;
            case "firm" -> Items.EMERALD;
            case "ill" -> Items.SPIDER_EYE;
            case "well" -> Items.GOLDEN_APPLE;
            case "died" -> Items.SKELETON_SKULL;
            case "followed" -> Items.NETHER_STAR;
            default -> Items.WRITABLE_BOOK;
        });
    }

    // ------------------------------------------------------------------ the city books' button

    /** The books' button's box, where it was last drawn (x, y, w, h; w 0: not drawn). */
    private static final int[] BOARD = new int[4];

    /** The books' header button (a star) left of {@code right}; returns the new right edge. Its tooltip is drawn here. */
    public static int boardButton(GuiGraphics g, Font font, int right, int y, int mx, int my) {
        int w = 18, x = right - UiStyle.S1 - w;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 18;
        UiStyle.tile(g, x, y, w, 18, over ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
        g.drawString(font, "\u2605", x + (w - font.width("\u2605")) / 2, y + 5, UiStyle.STAR, true);
        BOARD[0] = x; BOARD[1] = y; BOARD[2] = w; BOARD[3] = 18;
        if (over) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            UiStyle.tooltip(g, font, "Your favourite folk: the ones you follow, and how each is getting on.", mx, my, 200);
            g.pose().popPose();
        }
        return x;
    }

    /** A click on the books' button: the favourites, Esc back to the books. */
    public static boolean boardClick(double mx, double my, net.minecraft.client.gui.screens.Screen books) {
        if (BOARD[2] == 0 || mx < BOARD[0] || mx >= BOARD[0] + BOARD[2] || my < BOARD[1] || my >= BOARD[1] + BOARD[3]) return false;
        UiStyle.click();
        open(null, books);
        return true;
    }

    // ------------------------------------------------------------------ the toast

    /** A milestone of a folk you follow: its event's icon, its name with a star, the line, for five seconds. */
    static final class MilestoneToast implements Toast {
        private final String name, text;
        private final ItemStack icon;

        MilestoneToast(String name, String text, String kind) {
            this.name = name;
            this.text = text;
            this.icon = icon(kind);
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long timeSinceLastVisible) {
            Font font = toasts.getMinecraft().font;
            int w = width(), h = height();
            g.fill(0, 0, w, h, 0xFF000000);
            g.fill(1, 1, w - 1, h - 1, UiStyle.PANEL);
            g.fill(1, 1, 3, h - 1, UiStyle.STAR);
            g.renderItem(icon, 7, 8);
            g.drawString(font, UiStyle.clip(font, "★ " + name, w - 34), 28, 7, UiStyle.TEXT, false);
            g.drawString(font, UiStyle.clip(font, text, w - 34), 28, 18, UiStyle.TEXT_MUTED, false);
            return timeSinceLastVisible >= 5000L * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
        }
    }

    // ------------------------------------------------------------------ the tracker

    /** The pinned favourite in a small card at the right of the screen: name, trade and level, mood, doing. */
    private static void tracker(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!UiConfig.tracker() || mc.options.hideGui || mc.player == null || mc.screen != null || lastList == null) return;
        String pin = UiConfig.pinned();
        if (pin.isEmpty()) return;
        ListTag cards = lastList.getList("cards", Tag.TAG_COMPOUND);
        CompoundTag c = null;
        for (int i = 0; i < cards.size(); i++) if (cards.getCompound(i).getUUID("uuid").toString().equals(pin)) c = cards.getCompound(i);
        if (c == null) return;
        Font font = mc.font;
        int w = 132, h = 44, x = g.guiWidth() - w - 4, y = g.guiHeight() / 2 - h / 2 - 20;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0000000);
        g.fill(x, y, x + w, y + h, 0xA0202024);
        int accent = UiStyle.accentFill(c.getInt("colour"));
        g.fill(x, y, x + 2, y + h, accent);
        g.drawString(font, UiStyle.clip(font, "★ " + c.getString("name"), w - 8), x + 5, y + 3, 0xFFFFE08A, true);
        String sub = c.getBoolean("dead") ? "remembered" : c.getString("trade") + (c.getBoolean("child") ? "" : " · lvl " + c.getInt("level"));
        g.drawString(font, UiStyle.clip(font, sub, w - 8), x + 5, y + 13, UiStyle.TEXT_ON_DARK, false);
        int mood = c.getInt("mood");
        g.drawString(font, "Mood", x + 5, y + 23, 0xFFB0B0B0, false);
        UiStyle.bar(g, x + 32, y + 25, w - 60, 4, mood / 100F, UiStyle.levelFill(mood, 60, 35));
        g.drawString(font, UiStyle.number(Math.max(0, Math.min(999, mood))), x + w - 22, y + 23, UiStyle.TEXT_ON_DARK, false);
        String doing = c.getBoolean("dead") ? "died on day " + c.getLong("died") : c.getBoolean("about") ? c.getString("doing") : "away";
        g.drawString(font, UiStyle.clip(font, doing, w - 8), x + 5, y + 33, 0xFFB8C8D8, false);
    }
}
