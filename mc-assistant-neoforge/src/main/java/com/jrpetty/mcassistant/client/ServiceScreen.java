package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.client.UiStyle.State;
import com.jrpetty.mcassistant.net.ServiceActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [shops-homes] One screen for every service point (entity/ServicePoints): the shell the trades share (the keeper's
 * likeness and name, the hours, open or shut, your standing and your coin, the last word from the counter) and the
 * trade's own panel in the body: the shop's (ShopPanel) or the plain desk's (DeskPanel: the bank, the tavern, the
 * smithy, the tailor's). Laid out to the window at every GUI scale; Esc backs out. Every button goes to the server as
 * a request with a number one higher than the last, and nothing is believed until the server's answer comes back.
 */
public class ServiceScreen extends Screen {

    /** The trade's body: laid out in the room it is given, drawn, and handed the mouse and the keys. */
    interface Panel {
        void setData(CompoundTag screen);

        void layout(int x, int y, int w, int h);

        void render(GuiGraphics g, int mouseX, int mouseY);

        boolean mouseClicked(double mouseX, double mouseY, int button);

        default boolean mouseScrolled(double mouseX, double mouseY, double dy) { return false; }

        default boolean keyPressed(int key, int mods) { return false; }

        /** What to show under the mouse, if anything (set while rendering). */
        @Nullable String tooltip();
    }

    private static final int MAX_W = 440, MAX_H = 270, MIN_W = 300, MIN_H = 200;
    private static final ItemStack COIN = new ItemStack(McAssistantMod.VILLAGE_COIN.get());

    private CompoundTag data;
    private Panel panel;
    private String kind;
    private int px, py, pw, ph, headerH;
    private boolean compact;
    private int nextNonce;
    /** The request the screen is waiting on (a buy): its buttons rest till the answer. */
    private int waitingOn = -1;
    @Nullable private String hover;

    public ServiceScreen(CompoundTag data) {
        super(Component.literal(data.getString("title")));
        this.data = data;
        this.kind = data.getString("kind");
        this.panel = kind.equals("shop") ? new ShopPanel(this) : new DeskPanel(this);
        this.nextNonce = data.getInt("nonce") + 1;
        this.panel.setData(data);
    }

    /** The server's answer: open the trade's screen, or bring the open one up to date (or close it, when gone). */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (data.getBoolean("gone")) {
            if (mc.screen instanceof ServiceScreen) mc.setScreen(null);
            return;
        }
        if (mc.screen instanceof ServiceScreen open && open.sameAs(data)) {
            open.update(data);
            return;
        }
        mc.setScreen(new ServiceScreen(data));
    }

    private boolean sameAs(CompoundTag d) {
        return d.getString("role").equals(data.getString("role")) && d.getString("kind").equals(kind)
            && d.getInt("x") == data.getInt("x") && d.getInt("y") == data.getInt("y") && d.getInt("z") == data.getInt("z");
    }

    private void update(CompoundTag d) {
        boolean wasOpen = open();
        this.data = d;
        this.nextNonce = Math.max(nextNonce, d.getInt("nonce") + 1);
        if (waitingOn >= 0 && d.getInt("nonce") >= waitingOn) waitingOn = -1;
        panel.setData(d);
        if (wasOpen != open()) layoutPanel();                      // the closed banner comes or goes
    }

    // ------------------------------------------------------------------ talking to the server

    /** Send a button's request (the panels call this). */
    void send(String action, CompoundTag args) {
        int n = nextNonce++;
        if (action.equals("buy") || action.equals("sell") || action.equals("order") || action.equals("supply")) waitingOn = n;
        PacketDistributor.sendToServer(new ServiceActionPayload(data.getString("role"), action, args, n));
    }

    /** The number the next request will carry. */
    int peekNonce() {
        return nextNonce;
    }

    /** Is a request that moves goods or coin still unanswered (its button rests meanwhile)? */
    boolean waiting() {
        return waitingOn >= 0;
    }

    boolean open() {
        return data.getBoolean("open");
    }

    String status() {
        return data.getString("status");
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        pw = Math.max(Math.min(MIN_W, width - S2), Math.min(MAX_W, width - S4));
        ph = Math.max(Math.min(MIN_H, height - S2), Math.min(MAX_H, height - S4));
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        compact = ph < 230 || pw < 360;
        headerH = compact ? 26 : 36;
        layoutPanel();
    }

    /** The panel's room: under the header (and the closed banner), over the footer. */
    private void layoutPanel() {
        int bodyTop = py + headerH + (open() ? S1 : S1 + 14);
        int footer = 14;
        panel.layout(px + S2, bodyTop, pw - 2 * S2, py + ph - footer - bodyTop - S1);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        hover = null;
        shadow(g, px, py, pw, ph);
        panel(g, px, py, pw, ph);
        header(g, mouseX, mouseY);
        if (!open()) {
            int by = py + headerH + 2;
            g.fill(px + S2, by, px + pw - S2, by + 12, DANGER_SOFT);
            text(g, font, status(), px + S2 + S1, by + 2, pw - 2 * S2 - S2, DANGER);
        }
        panel.render(g, mouseX, mouseY);
        footer(g, mouseX, mouseY);
        String tip = panel.tooltip() != null ? panel.tooltip() : hover;
        if (tip != null && !tip.isEmpty()) tooltip(g, font, tip, mouseX, mouseY, 220);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKDROP);
    }

    /** The keeper's likeness, the trade and the town, the keeper and hours; the coin, the standing, open or shut. */
    private void header(GuiGraphics g, int mouseX, int mouseY) {
        headerBand(g, px, py, pw, headerH);
        int box = headerH - 2 * S1;
        int bx = px + S1, by = py + S1;
        well(g, bx, by, box, box, WELL);
        if (minecraft != null && minecraft.level != null && data.getInt("keeperId") >= 0
                && minecraft.level.getEntity(data.getInt("keeperId")) instanceof LivingEntity keeper) {
            int scale = Math.round(box * 0.9F);
            int centre = by + 3 + Math.round(scale * keeper.getBbHeight() / 2F);
            g.enableScissor(bx + 1, by + 1, bx + box - 1, by + box - 1);
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, bx, by + 1, bx + box, 2 * centre - by - 1, scale, 0.0625F, mouseX, mouseY, keeper);
            g.disableScissor();
        } else {
            g.renderItem(COIN, bx + (box - ICON) / 2, by + (box - ICON) / 2);
        }
        int tx = bx + box + S2;
        // Right: the coin you carry, then open or shut.
        String coins = String.valueOf(data.getInt("coins"));
        int cw = ICON + 2 + font.width(coins);
        int closeX = px + pw - S1 - 12;
        int rx = closeX - S2 - cw;
        g.renderItem(COIN, rx, py + (compact ? 5 : S1 + 2));
        g.drawString(font, coins, rx + ICON + 2, py + (compact ? 9 : S1 + 6), TEXT, false);
        if (in(mouseX, mouseY, rx, py + S1, cw, ICON)) hover = "The village coin you carry: " + coins + ".";
        String pill = open() ? "OPEN" : "CLOSED";
        int pillW = font.width(pill) + 6;
        int pillX = rx - S2 - pillW, pillY = py + (compact ? 8 : S1 + 5);
        g.fill(pillX, pillY - 1, pillX + pillW, pillY + 9, open() ? SUCCESS_FILL : DANGER_FILL);
        g.drawString(font, pill, pillX + 3, pillY, TEXT_ON_DARK, false);
        if (in(mouseX, mouseY, pillX, pillY - 1, pillW, 10)) hover = status();
        // The close button.
        boolean overClose = in(mouseX, mouseY, closeX, py + S1, 12, 12);
        tile(g, closeX, py + S1, 12, 12, State.of(true, false, overClose));
        g.drawString(font, "x", closeX + 3, py + S1 + 2, TEXT, false);
        if (overClose) hover = "Close (Esc)";
        // Left: the trade, then the keeper and the hours, then your standing.
        int room = pillX - S2 - tx;
        title(g, font, clip(font, data.getString("title"), room), tx, py + (compact ? 4 : S1 + 1));
        String keeper = data.getString("keeper");
        String line2 = (keeper.isEmpty() ? "Nobody behind the counter" : keeper + ", " + data.getString("keeperRole").toLowerCase(java.util.Locale.ROOT))
            + "  ·  " + data.getString("hours");
        text(g, font, line2, tx, py + (compact ? 14 : S1 + 13), room, TEXT_MUTED);
        if (!compact && data.contains("standing")) {
            String standing = "You: " + data.getString("standing") + (data.getBoolean("citizen") ? ", a citizen" : "");
            text(g, font, standing, tx, py + S1 + 23, room, TEXT_FAINT);
        }
    }

    /** The last word from the counter (green when it went through, red when it did not), and the keys. */
    private void footer(GuiGraphics g, int mouseX, int mouseY) {
        int fy = py + ph - 13;
        g.fill(px + 1, fy - 2, px + pw - 1, fy - 1, EDGE_SOFT);
        int keys = keyCap(g, font, "Esc", px + pw - S2 - font.width("Esc") - 4, fy);
        String msg = data.getString("message");
        int room = pw - 2 * S2 - keys - S2;
        if (!msg.isEmpty()) {
            boolean clipped = text(g, font, msg, px + S2, fy + 1, room, data.getBoolean("messageOk") ? SUCCESS : DANGER);
            if (clipped && in(mouseX, mouseY, px + S2, fy, room, 10)) hover = msg;
        } else if (waiting()) {
            text(g, font, "At the counter…", px + S2, fy + 1, room, TEXT_FAINT);
        }
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int closeX = px + pw - S1 - 12;
        if (in(mouseX, mouseY, closeX, py + S1, 12, 12)) {
            click();
            onClose();
            return true;
        }
        if (panel.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        return panel.mouseScrolled(mouseX, mouseY, dy) || super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (panel.keyPressed(key, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new ServiceActionPayload(data.getString("role"), "close", new CompoundTag(), nextNonce++));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The registry for reading item stacks out of the server's tags. */
    static ItemStack stack(CompoundTag t, String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !t.contains(key)) return ItemStack.EMPTY;
        return ItemStack.parseOptional(mc.level.registryAccess(), t.getCompound(key));
    }
}
