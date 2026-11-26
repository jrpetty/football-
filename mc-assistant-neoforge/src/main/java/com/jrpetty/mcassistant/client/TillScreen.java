package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.client.UiStyle.State;
import com.jrpetty.mcassistant.item.TillItems;
import com.jrpetty.mcassistant.net.TillActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [till] The checkout (entity/Tills): as simple as buying gets. Down the left, what's for sale: each line its icon, name,
 * price and how many are left, with a big − and + and the quantity between (shift: ten); sold out, greyed. On the right,
 * the basket: every line and what it comes to, any discount, the total in coins, the coin you carry and what's in the
 * bank, and one Pay button (it says why it can't, when it can't; over fifty coins it asks once more). The shopkeeper's
 * word after paying along the bottom. A second tab sells to them, if they buy. Laid out to the window at every GUI scale
 * (TillLayout); the server prices everything, the screen only asks.
 */
public class TillScreen extends Screen {

    private record Ware(String id, ItemStack stack, String name, String priceWords, int price, int lot, int stock, String note, boolean onKiosk, boolean ownerSees) {}

    private record SellLine(String id, ItemStack stack, String name, int have, int lot, int bid, String why) {}

    private static final ItemStack COIN = new ItemStack(McAssistantMod.VILLAGE_COIN.get());
    private static final ItemStack TILL = new ItemStack(TillItems.TILL_ITEM.get());
    private static final ItemStack KIOSK = new ItemStack(TillItems.KIOSK_ITEM.get());
    private static final int PAY_GREEN = 0xFF3F8F2A, PAY_GREEN_HI = 0xFF5BB343, PAY_AMBER = 0xFFC98A1E, RECEIPT = 0xFFF4F0E4, RECEIPT_EDGE = 0xFFC9C0A8;

    private CompoundTag data;
    private final List<Ware> wares = new ArrayList<>();
    private final List<SellLine> sells = new ArrayList<>();
    private final Map<String, Integer> basket = new LinkedHashMap<>();
    private final Map<String, Integer> sellBasket = new LinkedHashMap<>();
    private final Map<String, int[]> priced = new LinkedHashMap<>();
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final Scroll scroll = new Scroll();
    private TillLayout.Layout lay;
    private int nextNonce, sentAt = -1, waitingOn = -1;
    private long confirmUntil;
    private boolean selling;
    @Nullable private String tip;

    public TillScreen(CompoundTag data) {
        super(Component.literal(data.getString("name")));
        this.data = data;
        this.nextNonce = data.getInt("nonce") + 1;
        this.selling = data.getString("tab").equals("sell");
        read(data);
    }

    /** The server's answer: open the checkout, bring it up to date, or close it when the player has walked off. */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (data.getBoolean("gone")) {
            if (mc.screen instanceof TillScreen) mc.setScreen(null);
            return;
        }
        if (mc.screen instanceof TillScreen open && open.data.getString("name").equals(data.getString("name"))) {
            open.update(data);
            return;
        }
        mc.setScreen(new TillScreen(data));
    }

    private void update(CompoundTag d) {
        boolean hadTabs = buys();
        String oldVoice = data.getString("voice");
        this.data = d;
        nextNonce = Math.max(nextNonce, d.getInt("nonce") + 1);
        if (waitingOn >= 0 && d.getInt("nonce") >= waitingOn) {
            waitingOn = -1;
            if (d.getBoolean("ok") && !d.getString("voice").equals(oldVoice) && minecraft != null && minecraft.player != null) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 0.6F, 1.9F);
            }
        }
        read(d);
        if (hadTabs != buys()) init();
    }

    private void read(CompoundTag d) {
        wares.clear();
        ListTag items = d.getList("items", Tag.TAG_COMPOUND);
        boolean owner = d.getBoolean("owner");
        for (int i = 0; i < items.size(); i++) {
            CompoundTag e = items.getCompound(i);
            ItemStack s = icons.computeIfAbsent(e.getString("id"), k -> stack(e, "item"));
            wares.add(new Ware(e.getString("id"), s, e.getString("name"), e.getString("priceWords"), e.getInt("price"), Math.max(1, e.getInt("lot")),
                e.getInt("stock"), e.getString("note"), e.getBoolean("onKiosk"), owner && e.contains("onKiosk")));
        }
        sells.clear();
        ListTag sl = d.getList("sell", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) {
            CompoundTag e = sl.getCompound(i);
            ItemStack s = icons.computeIfAbsent("sell:" + e.getString("id"), k -> stack(e, "item"));
            sells.add(new SellLine(e.getString("id"), s, e.getString("name"), e.getInt("have"), Math.max(1, e.getInt("lot")), e.getInt("bid"), e.getString("why")));
        }
        priced.clear();
        ListTag ql = d.getCompound("quote").getList("lines", Tag.TAG_COMPOUND);
        for (int i = 0; i < ql.size(); i++) priced.put(ql.getCompound(i).getString("id"), new int[]{ ql.getCompound(i).getInt("n"), ql.getCompound(i).getInt("price") });
        if (d.getInt("nonce") >= sentAt) {                                  // the server's basket, once it has answered ours
            basket.clear();
            for (Map.Entry<String, int[]> e : priced.entrySet()) basket.put(e.getKey(), e.getValue()[0]);
            sellBasket.clear();
            CompoundTag sb = d.getCompound("sellBasket");
            for (String k : sb.getAllKeys()) sellBasket.put(k, sb.getInt(k));
        }
        if (!buys()) selling = false;
    }

    private static ItemStack stack(CompoundTag t, String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !t.contains(key)) return ItemStack.EMPTY;
        return ItemStack.parseOptional(mc.level.registryAccess(), t.getCompound(key));
    }

    private boolean buys() {
        return data.getBoolean("buys");
    }

    // ------------------------------------------------------------------ talking to the server

    private void send(String action, CompoundTag args) {
        int n = nextNonce++;
        if (action.equals("set") || action.equals("sset") || action.equals("clear")) sentAt = n;
        if (action.equals("pay") || action.equals("sell")) waitingOn = n;
        PacketDistributor.sendToServer(new TillActionPayload(action, args, n));
    }

    private void setQty(String id, int n, boolean sell) {
        CompoundTag a = new CompoundTag();
        a.putString("id", id);
        a.putInt("n", Math.max(0, n));
        (sell ? sellBasket : basket).put(id, Math.max(0, n));
        send(sell ? "sset" : "set", a);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        lay = TillLayout.of(width, height, buys());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        tip = null;
        TillLayout.Box p = lay.panel();
        shadow(g, p.x(), p.y(), p.w(), p.h());
        panel(g, p.x(), p.y(), p.w(), p.h());
        header(g, mouseX, mouseY);
        if (buys()) tabs(g, mouseX, mouseY);
        if (selling) sellList(g, mouseX, mouseY); else list(g, mouseX, mouseY);
        if (selling) sellBasket(g, mouseX, mouseY); else basket(g, mouseX, mouseY);
        footer(g);
        if (tip != null && !tip.isEmpty()) tooltip(g, font, tip, mouseX, mouseY, 220);
    }

    private void header(GuiGraphics g, int mx, int my) {
        TillLayout.Box h = lay.header();
        headerBand(g, h.x(), h.y(), h.w(), h.h());
        int box = h.h() - 6;
        int bx = h.x() + 3, by = h.y() + 3;
        well(g, bx, by, box, box, WELL);
        if (minecraft != null && minecraft.level != null && data.getInt("keeperId") >= 0
                && minecraft.level.getEntity(data.getInt("keeperId")) instanceof LivingEntity keeper) {
            int scale = Math.round(box * 0.85F);
            int centre = by + 2 + Math.round(scale * keeper.getBbHeight() / 2F);
            g.enableScissor(bx + 1, by + 1, bx + box - 1, by + box - 1);
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, bx, by + 1, bx + box, 2 * centre - by - 1, scale, 0.0625F, mx, my, keeper);
            g.disableScissor();
        } else {
            g.renderItem(data.getBoolean("kiosk") ? KIOSK : TILL, bx + (box - ICON) / 2, by + (box - ICON) / 2);
        }
        int tx = bx + box + S2;
        // right: close, the coin you carry (and the bank's)
        int closeX = h.right() - 4 - 12;
        boolean overClose = in(mx, my, closeX, h.y() + 4, 12, 12);
        tile(g, closeX, h.y() + 4, 12, 12, State.of(true, false, overClose));
        g.drawString(font, "x", closeX + 3, h.y() + 6, TEXT, false);
        if (overClose) tip = "Close (Esc)";
        String coins = String.valueOf(data.getInt("coins"));
        int cw = ICON + 2 + font.width(coins);
        int cx = closeX - S2 - cw;
        g.renderItem(COIN, cx, h.y() + 3);
        g.drawString(font, coins, cx + ICON + 2, h.y() + 7, TEXT, false);
        int bank = data.getInt("bank");
        if (bank > 0) {
            String bw = "bank " + bank;
            g.drawString(font, bw, cx + cw - font.width(bw), h.y() + 19, TEXT_FAINT, false);
        }
        if (in(mx, my, cx, h.y() + 2, cw, 26)) tip = "You carry " + coins + "c" + (bank > 0 ? "; " + bank + "c in the bank here." : ".");
        int room = cx - S2 - tx;
        // left: the business, then who's at the till
        title(g, font, clip(font, data.getString("name"), room), tx, h.y() + 5);
        boolean open = data.getBoolean("open"), staffed = data.getBoolean("staffed"), self = data.getBoolean("self");
        int ink = !open ? DANGER : staffed ? SUCCESS : self ? INFO : WARNING;
        int dot = !open ? DANGER_FILL : staffed ? SUCCESS_FILL : self ? INFO_FILL : WARNING_FILL;
        g.fill(tx, h.y() + 19, tx + 5, h.y() + 24, dot);
        String status = data.getString("status");
        int talkW = data.getString("keeper").isEmpty() ? 0 : font.width("Talk") + 8;
        text(g, font, status, tx + 8, h.y() + 18, room - 8 - (talkW > 0 ? talkW + 4 : 0), ink);
        if (talkW > 0) {
            int lx = tx + room - talkW;
            boolean over = in(mx, my, lx, h.y() + 16, talkW, 12);
            tile(g, lx, h.y() + 16, talkW, 12, State.of(true, false, over));
            g.drawString(font, "Talk", lx + 4, h.y() + 18, TEXT, false);
            if (over) tip = "Talk to " + data.getString("keeper") + " instead.";
            talkBox = new int[]{ lx, h.y() + 16, talkW, 12 };
        } else talkBox = null;
    }

    @Nullable private int[] talkBox;

    private void tabs(GuiGraphics g, int mx, int my) {
        TillLayout.Box t = lay.tabs();
        String[] labels = { "Buy", "Sell to them" };
        int x = t.x();
        for (int i = 0; i < 2; i++) {
            int w = font.width(labels[i]) + 16;
            boolean on = (i == 1) == selling;
            boolean over = in(mx, my, x, t.y(), w, t.h());
            topTab(g, x, t.y(), w, t.h(), State.of(true, on, over));
            g.drawString(font, labels[i], x + 8, t.y() + 4, on ? TEXT : TEXT_MUTED, false);
            x += w + 2;
        }
        g.fill(t.x(), t.bottom() - 1, t.right(), t.bottom(), EDGE_SOFT);
        counterBox = null;
        if (data.getBoolean("counter")) {
            String s = "Work & orders ›";
            int w = font.width(s) + 8, cx = t.right() - w;
            boolean over = in(mx, my, cx, t.y() + 1, w, t.h() - 3);
            if (over) g.fill(cx, t.y() + 1, cx + w, t.bottom() - 2, TILE_HOVER);
            g.drawString(font, s, cx + 4, t.y() + 4, over ? INFO : TEXT_FAINT, false);
            if (over) tip = "The rest of the counter's business: shifts, the wanted board, orders.";
            counterBox = new int[]{ cx, t.y(), w, t.h() };
        }
    }

    @Nullable private int[] counterBox;

    // ------------------------------------------------------------------ the wares

    private void list(GuiGraphics g, int mx, int my) {
        TillLayout.Box l = lay.list();
        well(g, l.x(), l.y(), l.w(), l.h(), WELL);
        if (wares.isEmpty()) {
            empty(g, font, "Nothing for sale", "The shelves are bare. Come back later.", l.x(), l.y(), l.w(), l.h());
            return;
        }
        int content = wares.size() * TillLayout.ROW;
        scroll.fit(content, l.h() - 2);
        g.enableScissor(l.x() + 1, l.y() + 1, l.right() - 1, l.bottom() - 1);
        int rowW = l.w() - 2 - (scroll.max() > 0 ? 5 : 0);
        for (int i = 0; i < wares.size(); i++) {
            int ry = l.y() + 1 + i * TillLayout.ROW - scroll.offset();
            if (ry + TillLayout.ROW < l.y() || ry > l.bottom()) continue;
            row(g, wares.get(i), l.x() + 1, ry, rowW, mx, my, in(mx, my, l.x(), l.y(), l.w(), l.h()));
        }
        g.disableScissor();
        if (scroll.max() > 0) scrollBar(g, l.right() - 5, l.y() + 1, l.h() - 2, scroll.offset(), scroll.max(), l.h() - 2);
        if (data.getBoolean("first")) hint(g, l);
    }

    private void hint(GuiGraphics g, TillLayout.Box l) {
        String s = "+ and − for how many (shift: ten). Then Pay.";
        int w = Math.min(l.w() - 8, font.width(s) + 10);
        int x = l.x() + (l.w() - w) / 2, y = l.bottom() - 16;
        g.fill(x, y, x + w, y + 13, alpha(HIGHLIGHT, 0.95F));
        g.fill(x, y, x + 2, y + 13, HIGHLIGHT_SPINE);
        text(g, font, s, x + 6, y + 3, w - 8, TEXT);
    }

    private void row(GuiGraphics g, Ware w, int x, int y, int rw, int mx, int my, boolean inList) {
        boolean out = w.stock() <= 0;
        int n = basket.getOrDefault(w.id(), 0);
        boolean overRow = inList && in(mx, my, x, y, rw, TillLayout.ROW - 1);
        g.fill(x, y, x + rw, y + TillLayout.ROW - 1, out ? 0xFFB9B9B9 : n > 0 ? HIGHLIGHT : overRow ? TILE_HOVER : TILE);
        g.fill(x, y + TillLayout.ROW - 1, x + rw, y + TillLayout.ROW, EDGE_SOFT);
        if (n > 0) g.fill(x, y, x + 2, y + TillLayout.ROW - 1, HIGHLIGHT_SPINE);
        g.renderItem(w.stack(), x + 4, y + 3);
        if (out) g.fill(x + 4, y + 3, x + 20, y + 19, 0x88B9B9B9);
        int[] st = TillLayout.stepper(x + rw);
        int textW = st[0] - 4 - (x + 24);
        text(g, font, w.name(), x + 24, y + 3, textW, out ? TEXT_DISABLED : TEXT);
        String sub = out ? "Sold out" : w.priceWords() + "  ·  " + w.stock() + " left" + (w.note().isEmpty() ? "" : "  ·  " + w.note());
        text(g, font, sub, x + 24, y + 13, textW, out ? DANGER : TEXT_FAINT);
        if (overRow && in(mx, my, x + 2, y, st[0] - x - 4, TillLayout.ROW)) {
            tip = w.name() + ": " + (out ? "sold out." + (w.note().isEmpty() ? "" : " " + w.note() + ".") : w.priceWords() + ", " + w.stock() + " left.");
        }
        if (w.ownerSees()) {                                                 // the owner, at a kiosk: what it sells
            int kx = st[0] - 14;
            boolean overK = in(mx, my, kx, y + 6, 10, 10);
            tile(g, kx, y + 6, 10, 10, State.of(true, w.onKiosk(), overK));
            if (w.onKiosk()) g.drawString(font, "✔", kx + 2, y + 7, SUCCESS, false);
            if (overK) tip = w.onKiosk() ? "Sold at this kiosk. Click to take it off." : "Not sold at this kiosk. Click to put it on.";
        }
        if (out) {
            int cw = badge(g, font, "Sold out", st[0], y + 6, DANGER_FILL);
            if (cw < 0) return;
            return;
        }
        boolean canMinus = n > 0, canPlus = n < w.stock();
        stepButton(g, "−", st[0], y + 4, canMinus, mx, my);
        String q = String.valueOf(n);
        g.fill(st[1], y + 4, st[1] + 22, y + 4 + TillLayout.BTN, n > 0 ? 0xFFFFFFFF : WELL_LIGHT);
        g.renderOutline(st[1], y + 4, 22, TillLayout.BTN, EDGE_SOFT);
        g.drawString(font, q, st[1] + 11 - font.width(q) / 2, y + 8, n > 0 ? TEXT : TEXT_FAINT, false);
        stepButton(g, "+", st[2], y + 4, canPlus, mx, my);
        if (in(mx, my, st[2], y + 4, TillLayout.BTN, TillLayout.BTN)) tip = canPlus ? "One more (shift: ten more)" : "That's all they have.";
        if (in(mx, my, st[0], y + 4, TillLayout.BTN, TillLayout.BTN)) tip = canMinus ? "One fewer (shift: ten fewer)" : null;
    }

    private void stepButton(GuiGraphics g, String label, int x, int y, boolean on, int mx, int my) {
        boolean over = on && in(mx, my, x, y, TillLayout.BTN, TillLayout.BTN);
        State s = State.of(on, false, over);
        tile(g, x, y, TillLayout.BTN, TillLayout.BTN, s);
        g.drawString(font, label, x + (TillLayout.BTN - font.width(label)) / 2 + 1, y + 4, on ? TEXT : TEXT_DISABLED, false);
    }

    // ------------------------------------------------------------------ the basket

    private void basket(GuiGraphics g, int mx, int my) {
        TillLayout.Box b = lay.basket();
        receipt(g, b);
        int x = b.x() + 6, w = b.w() - 12, y = b.y() + 5;
        heading(g, font, "Your basket", x, y, w);
        y += 13;
        // what applies today: an offer, the keeper's manner
        List<String> notes = new ArrayList<>();
        ListTag offers = data.getList("offers", Tag.TAG_STRING);
        for (int i = 0; i < offers.size(); i++) notes.add(offers.getString(i));
        if (!data.getString("trait").isEmpty()) notes.add(data.getString("trait"));
        int payTop = lay.pay().y() - (extraButtons() * 17) - 30;
        if (priced.isEmpty()) {
            text(g, font, "Empty. Press + on something.", x, y + 2, w, TEXT_FAINT);
            y += 14;
        } else {
            for (Map.Entry<String, int[]> e : priced.entrySet()) {
                if (y > payTop - 10) { text(g, font, "…and more", x, y, w, TEXT_FAINT); y += 10; break; }
                Ware wa = ware(e.getKey());
                String name = wa == null ? e.getKey() : wa.name();
                String amt = e.getValue()[1] + "c";
                String left = e.getValue()[0] + "× " + name;
                int aw = font.width(amt);
                boolean clipped = text(g, font, left, x, y, w - aw - 6, TEXT);
                g.drawString(font, amt, x + w - aw, y, TEXT, false);
                if (clipped && in(mx, my, x, y, w - aw, 9)) tip = left;
                y += 10;
            }
            ListTag adj = data.getCompound("quote").getList("adjust", Tag.TAG_COMPOUND);
            for (int i = 0; i < adj.size() && y < payTop; i++) {
                CompoundTag a = adj.getCompound(i);
                int amount = a.getInt("amount");
                String amt = amount == 0 ? "" : (amount > 0 ? "+" : "−") + Math.abs(amount) + "c";
                int aw = font.width(amt);
                boolean clipped = text(g, font, a.getString("label"), x, y, w - aw - 6, amount <= 0 ? SUCCESS : DANGER);
                if (clipped && in(mx, my, x, y, w - aw, 9)) tip = a.getString("label");
                g.drawString(font, amt, x + w - aw, y, amount <= 0 ? SUCCESS : DANGER, false);
                y += 10;
            }
        }
        for (String n : notes) {
            if (y > payTop - 2) break;
            boolean clipped = text(g, font, n, x, y, w, INFO);
            if (clipped && in(mx, my, x, y, w, 9)) tip = n;
            y += 10;
        }
        // the total, big
        int total = data.getCompound("quote").getInt("total");
        int ty = lay.pay().y() - (extraButtons() * 17) - 28;
        g.fill(x, ty - 3, x + w, ty - 2, RECEIPT_EDGE);
        g.drawString(font, "Total", x, ty + 3, TEXT_MUTED, false);
        String tot = String.valueOf(total);
        g.pose().pushPose();
        g.pose().translate(x + w - ICON - 2 - font.width(tot) * 2, ty, 0);
        g.pose().scale(2F, 2F, 1F);
        g.drawString(font, tot, 0, 0, TEXT, false);
        g.pose().popPose();
        g.renderItem(COIN, x + w - ICON, ty);
        int held = data.getInt("coins");
        boolean short_ = total > held;
        String carry = "You carry " + held + "c";
        text(g, font, carry, x, ty + 17, w, short_ && total > 0 ? DANGER : TEXT_FAINT);
        // the buttons over Pay: serve yourself, haggle, the owner's switch
        int by = lay.pay().y() - extraButtons() * 17;
        buttons.clear();
        if (data.getBoolean("offerSelf")) { addButton(g, "Serve yourself", by, mx, my, "Nobody's in. The owner lets you ring it up yourself.", "self"); by += 17; }
        if (data.getBoolean("haggles") && !priced.isEmpty()) { addButton(g, "Can you do it cheaper?", by, mx, my, "Haggle. They may knock a bit off. Or not.", "haggle"); by += 17; }
        if (data.getBoolean("owner") && !data.getBoolean("kiosk")) {
            addButton(g, data.getBoolean("selfAllowed") ? "Self-service: on" : "Self-service: off", by, mx, my,
                "Your till: may customers serve themselves when nobody's at it?", "ownerSelf");
        }
        pay(g, mx, my, total);
    }

    private final List<Object[]> buttons = new ArrayList<>();

    private int extraButtons() {
        int n = 0;
        if (data.getBoolean("offerSelf")) n++;
        if (data.getBoolean("haggles") && !priced.isEmpty()) n++;
        if (data.getBoolean("owner") && !data.getBoolean("kiosk")) n++;
        return n;
    }

    private void addButton(GuiGraphics g, String label, int y, int mx, int my, String hover, String action) {
        TillLayout.Box p = lay.pay();
        boolean over = in(mx, my, p.x(), y - 1, p.w(), 15);
        button(g, font, label, p.x(), y - 1, p.w(), 15, State.of(true, false, over));
        if (over) tip = hover;
        buttons.add(new Object[]{ p.x(), y - 1, p.w(), 15, action });
    }

    private void pay(GuiGraphics g, int mx, int my, int total) {
        TillLayout.Box p = lay.pay();
        String why = data.getString("why");
        boolean on = why.isEmpty() && waitingOn < 0;
        boolean confirming = System.currentTimeMillis() < confirmUntil;
        boolean over = on && in(mx, my, p.x(), p.y(), p.w(), p.h());
        if (on) {
            int fill = confirming ? PAY_AMBER : over ? PAY_GREEN_HI : PAY_GREEN;
            g.fill(p.x(), p.y(), p.right(), p.bottom(), EDGE);
            g.fill(p.x() + 1, p.y() + 1, p.right() - 1, p.bottom() - 1, fill);
            g.fill(p.x() + 1, p.y() + 1, p.right() - 1, p.y() + 2, alpha(0xFFFFFFFF, 0.35F));
            String label = confirming ? "Sure? Pay " + total + "c" : "Pay " + total + "c";
            g.drawString(font, label, p.x() + (p.w() - font.width(label)) / 2, p.y() + 6, TEXT_ON_DARK, true);
        } else {
            String label = waitingOn >= 0 ? "At the till…" : why.isEmpty() || font.width(why) > p.w() - 8 || why.startsWith("Add") ? "Pay" : why;
            button(g, font, label, p.x(), p.y(), p.w(), p.h(), State.DISABLED);
            if (label.equals(why)) why = "";                                // said on the button itself
        }
        if (!why.isEmpty()) {
            boolean clipped = text(g, font, why, p.x(), p.bottom() + 3, p.w(), why.startsWith("Add") ? TEXT_FAINT : DANGER);
            if (clipped && in(mx, my, p.x(), p.bottom() + 2, p.w(), 10)) tip = why;
        } else if (on && total > data.getInt("confirmOver")) {
            text(g, font, "Over " + data.getInt("confirmOver") + "c: asks once more.", p.x(), p.bottom() + 3, p.w(), TEXT_FAINT);
        }
    }

    private void receipt(GuiGraphics g, TillLayout.Box b) {
        g.fill(b.x(), b.y(), b.right(), b.bottom(), RECEIPT_EDGE);
        g.fill(b.x() + 1, b.y() + 1, b.right() - 1, b.bottom() - 1, RECEIPT);
        for (int x = b.x() + 2; x < b.right() - 2; x += 6) g.fill(x, b.bottom() - 2, x + 3, b.bottom() - 1, RECEIPT_EDGE);   // a torn edge
    }

    @Nullable
    private Ware ware(String id) {
        for (Ware w : wares) if (w.id().equals(id)) return w;
        return null;
    }

    // ------------------------------------------------------------------ selling

    private void sellList(GuiGraphics g, int mx, int my) {
        TillLayout.Box l = lay.list();
        well(g, l.x(), l.y(), l.w(), l.h(), WELL);
        if (sells.isEmpty()) {
            empty(g, font, "Nothing to sell", "You aren't carrying anything they'd buy.", l.x(), l.y(), l.w(), l.h());
            return;
        }
        scroll.fit(sells.size() * TillLayout.ROW, l.h() - 2);
        g.enableScissor(l.x() + 1, l.y() + 1, l.right() - 1, l.bottom() - 1);
        int rw = l.w() - 2 - (scroll.max() > 0 ? 5 : 0);
        for (int i = 0; i < sells.size(); i++) {
            SellLine s = sells.get(i);
            int x = l.x() + 1, y = l.y() + 1 + i * TillLayout.ROW - scroll.offset();
            if (y + TillLayout.ROW < l.y() || y > l.bottom()) continue;
            boolean no = s.bid() <= 0;
            int n = sellBasket.getOrDefault(s.id(), 0);
            g.fill(x, y, x + rw, y + TillLayout.ROW - 1, no ? 0xFFB9B9B9 : n > 0 ? HIGHLIGHT : TILE);
            g.fill(x, y + TillLayout.ROW - 1, x + rw, y + TillLayout.ROW, EDGE_SOFT);
            g.renderItem(s.stack(), x + 4, y + 3);
            int[] st = TillLayout.stepper(x + rw);
            int tw = st[0] - 4 - (x + 24);
            text(g, font, s.name(), x + 24, y + 3, tw, no ? TEXT_DISABLED : TEXT);
            String sub = no ? s.why() : (s.lot() > 1 ? s.lot() + " for " : "") + s.bid() + "c  ·  you have " + s.have();
            if (text(g, font, sub, x + 24, y + 13, tw, no ? TEXT_FAINT : TEXT_FAINT) && in(mx, my, x + 24, y + 12, tw, 10)) tip = sub;
            if (no) continue;
            int most = s.have() / s.lot();
            stepButton(g, "−", st[0], y + 4, n > 0, mx, my);
            String q = String.valueOf(n);
            g.fill(st[1], y + 4, st[1] + 22, y + 4 + TillLayout.BTN, n > 0 ? 0xFFFFFFFF : WELL_LIGHT);
            g.renderOutline(st[1], y + 4, 22, TillLayout.BTN, EDGE_SOFT);
            g.drawString(font, q, st[1] + 11 - font.width(q) / 2, y + 8, TEXT, false);
            stepButton(g, "+", st[2], y + 4, n < most, mx, my);
            if (in(mx, my, st[1], y + 4, 22, TillLayout.BTN)) tip = s.lot() > 1 ? "Lots of " + s.lot() : null;
        }
        g.disableScissor();
        if (scroll.max() > 0) scrollBar(g, l.right() - 5, l.y() + 1, l.h() - 2, scroll.offset(), scroll.max(), l.h() - 2);
    }

    private void sellBasket(GuiGraphics g, int mx, int my) {
        TillLayout.Box b = lay.basket();
        receipt(g, b);
        int x = b.x() + 6, w = b.w() - 12, y = b.y() + 5;
        heading(g, font, "They pay you", x, y, w);
        y += 14;
        for (SellLine s : sells) {
            int n = sellBasket.getOrDefault(s.id(), 0);
            if (n <= 0) continue;
            String amt = n * s.bid() + "c";
            text(g, font, n * s.lot() + "× " + s.name(), x, y, w - font.width(amt) - 6, TEXT);
            g.drawString(font, amt, x + w - font.width(amt), y, TEXT, false);
            y += 10;
            if (y > lay.pay().y() - 40) break;
        }
        int total = data.getInt("sellTotal");
        int ty = lay.pay().y() - 28;
        g.fill(x, ty - 3, x + w, ty - 2, RECEIPT_EDGE);
        g.drawString(font, "You get", x, ty + 3, TEXT_MUTED, false);
        String tot = String.valueOf(total);
        g.pose().pushPose();
        g.pose().translate(x + w - ICON - 2 - font.width(tot) * 2, ty, 0);
        g.pose().scale(2F, 2F, 1F);
        g.drawString(font, tot, 0, 0, TEXT, false);
        g.pose().popPose();
        g.renderItem(COIN, x + w - ICON, ty);
        buttons.clear();
        TillLayout.Box p = lay.pay();
        boolean on = total > 0 && data.getBoolean("staffed") && waitingOn < 0;
        boolean over = on && in(mx, my, p.x(), p.y(), p.w(), p.h());
        if (on) {
            g.fill(p.x(), p.y(), p.right(), p.bottom(), EDGE);
            g.fill(p.x() + 1, p.y() + 1, p.right() - 1, p.bottom() - 1, over ? PAY_GREEN_HI : PAY_GREEN);
            String label = "Sell for " + total + "c";
            g.drawString(font, label, p.x() + (p.w() - font.width(label)) / 2, p.y() + 6, TEXT_ON_DARK, true);
        } else {
            button(g, font, "Sell", p.x(), p.y(), p.w(), p.h(), State.DISABLED);
            String why = !data.getBoolean("staffed") ? "Nobody at the till to buy it." : "Press + on what to sell.";
            text(g, font, why, p.x(), p.bottom() + 3, p.w(), TEXT_FAINT);
        }
    }

    // ------------------------------------------------------------------ the bottom line

    private void footer(GuiGraphics g) {
        TillLayout.Box f = lay.footer();
        g.fill(lay.panel().x() + 1, f.y() - 2, lay.panel().right() - 1, f.y() - 1, EDGE_SOFT);
        int keys = keyCap(g, font, "Esc", f.right() - font.width("Esc") - 4, f.y());
        String msg = data.getString("message"), voice = data.getString("voice");
        int room = f.w() - keys - S2;
        if (waitingOn >= 0) text(g, font, "Ringing it up…", f.x(), f.y() + 1, room, TEXT_FAINT);
        else if (!voice.isEmpty() && data.getBoolean("ok")) text(g, font, voice + (msg.isEmpty() ? "" : "  " + msg), f.x(), f.y() + 1, room, SUCCESS);
        else if (!msg.isEmpty()) text(g, font, msg, f.x(), f.y() + 1, room, data.getBoolean("ok") ? SUCCESS : DANGER);
        else if (!voice.isEmpty()) text(g, font, voice, f.x(), f.y() + 1, room, TEXT_MUTED);
        else text(g, font, data.getString("town") + (data.getBoolean("kiosk") ? "  ·  self-service" : ""), f.x(), f.y() + 1, room, TEXT_FAINT);
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        TillLayout.Box h = lay.header();
        if (in(mx, my, h.right() - 16, h.y() + 4, 12, 12)) { click(); onClose(); return true; }
        if (talkBox != null && in(mx, my, talkBox[0], talkBox[1], talkBox[2], talkBox[3])) { click(); send("talk", new CompoundTag()); return true; }
        if (counterBox != null && in(mx, my, counterBox[0], counterBox[1], counterBox[2], counterBox[3])) { click(); send("counter", new CompoundTag()); return true; }
        if (buys()) {
            TillLayout.Box t = lay.tabs();
            int x = t.x();
            String[] labels = { "Buy", "Sell to them" };
            for (int i = 0; i < 2; i++) {
                int w = font.width(labels[i]) + 16;
                if (in(mx, my, x, t.y(), w, t.h())) {
                    click();
                    selling = i == 1;
                    scroll.reset();
                    CompoundTag a = new CompoundTag();
                    a.putString("tab", selling ? "sell" : "buy");
                    send("tab", a);
                    return true;
                }
                x += w + 2;
            }
        }
        boolean shift = hasShiftDown();
        TillLayout.Box l = lay.list();
        if (in(mx, my, l.x(), l.y(), l.w(), l.h())) {
            int i = (int) ((my - l.y() - 1 + scroll.offset()) / TillLayout.ROW);
            int rw = l.w() - 2 - (scroll.max() > 0 ? 5 : 0);
            int[] st = TillLayout.stepper(l.x() + 1 + rw);
            int ry = l.y() + 1 + i * TillLayout.ROW - scroll.offset();
            if (!selling && i >= 0 && i < wares.size()) {
                Ware w = wares.get(i);
                int n = basket.getOrDefault(w.id(), 0);
                int step = shift ? 10 : 1;
                if (w.ownerSees() && in(mx, my, st[0] - 14, ry + 6, 10, 10)) {
                    click();
                    CompoundTag a = new CompoundTag();
                    a.putString("id", w.id());
                    send("kioskWare", a);
                    return true;
                }
                if (w.stock() > 0 && in(mx, my, st[2], ry + 4, TillLayout.BTN, TillLayout.BTN) && n < w.stock()) { click(); setQty(w.id(), Math.min(w.stock(), n + step), false); return true; }
                if (in(mx, my, st[0], ry + 4, TillLayout.BTN, TillLayout.BTN) && n > 0) { click(); setQty(w.id(), Math.max(0, n - step), false); return true; }
                if (w.stock() > 0 && in(mx, my, l.x(), ry, st[0] - l.x() - 16, TillLayout.ROW) && n < w.stock()) { click(); setQty(w.id(), Math.min(w.stock(), n + step), false); return true; }
            } else if (selling && i >= 0 && i < sells.size()) {
                SellLine s = sells.get(i);
                if (s.bid() <= 0) return true;
                int n = sellBasket.getOrDefault(s.id(), 0), most = s.have() / s.lot();
                int step = shift ? 10 : 1;
                if (in(mx, my, st[2], ry + 4, TillLayout.BTN, TillLayout.BTN) && n < most) { click(); setQty(s.id(), Math.min(most, n + step), true); return true; }
                if (in(mx, my, st[0], ry + 4, TillLayout.BTN, TillLayout.BTN) && n > 0) { click(); setQty(s.id(), Math.max(0, n - step), true); return true; }
            }
            return true;
        }
        for (Object[] b : buttons) {
            if (in(mx, my, (int) b[0], (int) b[1], (int) b[2], (int) b[3])) { click(); send((String) b[4], new CompoundTag()); return true; }
        }
        TillLayout.Box p = lay.pay();
        if (in(mx, my, p.x(), p.y(), p.w(), p.h())) { pressPay(); return true; }
        return super.mouseClicked(mx, my, button);
    }

    private void pressPay() {
        if (waitingOn >= 0) return;
        if (selling) {
            if (data.getInt("sellTotal") > 0 && data.getBoolean("staffed")) { click(); send("sell", new CompoundTag()); }
            return;
        }
        if (!data.getString("why").isEmpty()) return;
        int total = data.getCompound("quote").getInt("total");
        if (total > data.getInt("confirmOver") && System.currentTimeMillis() >= confirmUntil) {
            click();
            confirmUntil = System.currentTimeMillis() + 4000;
            return;
        }
        confirmUntil = 0;
        click();
        send("pay", new CompoundTag());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (in(mx, my, lay.list().x(), lay.list().y(), lay.list().w(), lay.list().h())) {
            scroll.wheel(dy, TillLayout.ROW);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { pressPay(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new TillActionPayload("close", new CompoundTag(), nextNonce++));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
