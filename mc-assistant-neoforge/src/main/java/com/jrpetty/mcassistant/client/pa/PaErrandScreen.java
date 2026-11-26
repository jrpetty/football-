package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * [featpa] Errand to town (Orders > Errand to town): hand it things to sell, write its shopping list, give it coins, send
 * it. Left, your inventory: click a stack to hand it over (it goes into its pack at once); under it, what it carries to
 * sell (click to have it back). Right, the town's everyday goods at today's price for you, a lot at a time, with how
 * many you want; the coins it carries (yours handed over, or back), its spending limit, whether to wait for a shut
 * market and whether to come home by dark; "Show me" marks the market; Send asks twice (it sells your things). While
 * it's out: where it's got to, what it has sold and bought so far, anything that went wrong, and Call it back.
 */
public final class PaErrandScreen extends PaKitBase {

    private int poll, goodsScroll;

    PaErrandScreen() {
        super("Errand to town", "er");
    }

    @Override
    protected void init() {
        size(452, 288);
    }

    @Override
    protected String title() {
        CompoundTag e = PaKitClient.errand;
        String town = e == null ? "" : e.getString("town");
        return town.isEmpty() ? "Errand to town" : "Errand to " + town + (e.getInt("dist") >= 0 ? " (" + e.getInt("dist") + " blocks away)" : "");
    }

    @Override
    protected String idleWords() {
        return "Click your things to hand them over to sell, set the shopping list and coins, then Send. Every click says here what happened.";
    }

    @Override
    public void tick() {
        super.tick();
        if (++poll % 40 == 0) PaKitClient.send("errand_open");
    }

    private static CompoundTag itemArg(String id) {
        CompoundTag t = new CompoundTag();
        t.putString("item", id);
        return t;
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        CompoundTag e = PaKitClient.errand;
        int x = left + 8, y = top + 28, iw = w - 16;
        int footH = 34, foot = top + h - footH - 6;
        feedback(g, x, foot, iw, footH);
        if (hint(g, x, y, iw, "Sending it to market", new String[]{
                "Click things in your inventory (left) to hand them over to sell. Click them under To sell to have them back.",
                "Use + and - on the shopping list (right). Prices are a lot's price in the town today.",
                "Give it coins with +8 or +32 if it's buying. It never spends past its limit (Settings, Behaviour).",
                "Press Send. It walks there and back on foot, then hands you the goods and the change and tells you how it went."}, mx, my)) return;
        if (e == null) { g.drawString(font, "Asking it...", x, y + 2, UiStyle.TEXT_MUTED, false); return; }
        if (e.getString("town").isEmpty()) {
            para(g, "There's no town near enough to run an errand to. Errands go to the nearest town's market, up to about 1200 blocks away.", x, y + 2, iw, UiStyle.DANGER);
            return;
        }
        int colW = (iw - 8) / 2;
        CompoundTag run = e.contains("run") ? e.getCompound("run") : null;
        boolean out = run != null && run.getBoolean("active");
        // ---- left: your inventory, and what it carries to sell
        UiStyle.heading(g, font, out ? "Your things (it's out: wait for it)" : "Your things: click to hand over to sell", x, y, colW);
        int gy = y + 12;
        var pl = Minecraft.getInstance().player;
        if (pl != null) {
            Inventory inv = pl.getInventory();
            for (int i = 0; i < 36; i++) {
                int slot = i < 27 ? i + 9 : i - 27;                       // the pack first, the hotbar under it, as the inventory screen shows them
                int cx = x + (i % 9) * 19, cy = gy + (i / 9) * 19 + (i >= 27 ? 3 : 0);
                UiStyle.well(g, cx, cy, 18, 18, UiStyle.WELL_LIGHT);
                ItemStack s = inv.getItem(slot);
                if (s.isEmpty()) continue;
                g.renderItem(s, cx + 1, cy + 1);
                g.renderItemDecorations(font, s, cx + 1, cy + 1);
                if (!out) zone(cx, cy, 18, 18, () -> { CompoundTag t = new CompoundTag(); t.putInt("slot", slot); PaKitClient.send("er_give", t); },
                    s.getHoverName().getString() + ": click to hand it over to sell");
                else if (in(mx, my, cx, cy, 18, 18)) tipNow = "It's on its errand: hand things over when it's back";
            }
        }
        gy += 4 * 19 + 8;
        UiStyle.heading(g, font, "To sell (click to have it back)", x, gy, colW);
        gy += 12;
        ListTag sell = e.getList("sell", Tag.TAG_COMPOUND);
        if (sell.isEmpty()) g.drawString(font, "Nothing yet.", x + 2, gy + 4, UiStyle.TEXT_MUTED, false);
        for (int i = 0; i < sell.size() && i < 18; i++) {
            CompoundTag c = sell.getCompound(i);
            int cx = x + (i % 9) * 19, cy = gy + (i / 9) * 19;
            String id = c.getString("i");
            UiStyle.well(g, cx, cy, 18, 18, UiStyle.TILE_SELECTED);
            icon(g, id, cx + 1, cy + 1, c.getInt("n"));
            if (!out) zone(cx, cy, 18, 18, () -> PaKitClient.send("er_back", itemArg(id)), nameOf(id) + " x" + c.getInt("n") + ": click to have it back");
        }
        // ---- right: the shopping list
        int rx = x + colW + 8;
        UiStyle.heading(g, font, "Shopping list (a lot's price today)", rx, y, colW);
        ListTag goods = e.getList("goods", Tag.TAG_COMPOUND);
        int rows = 7, ry = y + 12;
        goodsScroll = Math.max(0, Math.min(goodsScroll, Math.max(0, goods.size() - rows)));
        for (int i = goodsScroll; i < goodsScroll + rows && i < goods.size(); i++) {
            CompoundTag gd = goods.getCompound(i);
            String id = gd.getString("i");
            int want = gd.getInt("want"), price = gd.getInt("price");
            tile(g, rx, ry, colW, 17, want > 0, mx, my);
            icon(g, id, rx + 3, ry, 1);
            g.drawString(font, UiStyle.clip(font, nameOf(id), colW - 104), rx + 21, ry + 5, UiStyle.TEXT, false);
            g.drawString(font, price > 0 ? price + "c" : "none", rx + colW - 84, ry + 5, price > 0 ? UiStyle.TEXT_MUTED : UiStyle.DANGER, false);
            int step = new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id))).getMaxStackSize() > 1 ? 8 : 1;
            boolean can = !out;
            button(g, "-", rx + colW - 54, ry + 2, 13, 13, mx, my, can && want > 0, false, out ? "it's out on an errand" : want > 0 ? step + " fewer" : "none on the list to take off", () -> {
                CompoundTag t = itemArg(id); t.putInt("by", -step); PaKitClient.send("er_buy", t); });
            button(g, "+", rx + colW - 15, ry + 2, 13, 13, mx, my, can, false, out ? "it's out on an errand" : step + " more" + (price <= 0 ? " (the town has none just now: it'll try)" : ""), () -> {
                CompoundTag t = itemArg(id); t.putInt("by", step); PaKitClient.send("er_buy", t); });
            String wn = String.valueOf(want);
            g.drawString(font, wn, rx + colW - 34 - font.width(wn) / 2, ry + 5, want > 0 ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
            ry += 18;
        }
        if (goods.size() > rows) {
            g.drawString(font, "scroll for more", rx, ry + 2, UiStyle.TEXT_FAINT, false);
            button(g, "▲", rx + colW - 30, ry, 14, 11, mx, my, goodsScroll > 0, false, goodsScroll > 0 ? "Up the list" : "already at the top", () -> goodsScroll--);
            button(g, "▼", rx + colW - 14, ry, 14, 11, mx, my, goodsScroll + rows < goods.size(), false, goodsScroll + rows < goods.size() ? "Down the list" : "already at the bottom", () -> goodsScroll++);
        }
        ry += 15;
        // ---- coins, options, send
        int coins = e.getInt("carried"), yours = e.getInt("yours"), limit = e.getInt("limit");
        g.drawString(font, UiStyle.clip(font, "Coins it carries: " + coins + "   (you have " + yours + ")", colW), rx, ry, UiStyle.TEXT, false);
        ry += 11;
        int q = (colW - 12) / 4;
        String why = out ? "it's out on an errand" : "";
        button(g, "+8", rx, ry, q, 13, mx, my, !out && yours > 0, false, out ? why : yours > 0 ? "Hand it 8 coins to spend" : "you've no coins to give it", () -> coinsBy(8));
        button(g, "+32", rx + q + 4, ry, q, 13, mx, my, !out && yours > 0, false, out ? why : yours > 0 ? "Hand it 32 coins to spend" : "you've no coins to give it", () -> coinsBy(32));
        button(g, "-8", rx + 2 * (q + 4), ry, q, 13, mx, my, !out && coins > 0, false, out ? why : coins > 0 ? "Take 8 back" : "it carries no coins", () -> coinsBy(-8));
        button(g, "All back", rx + 3 * (q + 4), ry, q, 13, mx, my, !out && coins > 0, false, out ? why : coins > 0 ? "Take all its coins back" : "it carries no coins", () -> coinsBy(-100000));
        ry += 16;
        g.drawString(font, UiStyle.clip(font, "Spends at most " + limit + " (Settings, Behaviour)" + (e.getBoolean("openNow") ? "" : " · market shut now"), colW), rx, ry, UiStyle.TEXT_MUTED, false);
        ry += 12;
        boolean wait = e.getBoolean("wait"), dark = e.getBoolean("dark");
        int hw = (colW - 4) / 2;
        button(g, (wait ? "✔ " : "") + "Wait if shut", rx, ry, hw, 13, mx, my, !out, false, out ? why : "If the market's shut when it gets there it waits for it to open (off: it comes home)",
            () -> { CompoundTag t = new CompoundTag(); t.putBoolean("wait", !wait); PaKitClient.send("er_opt", t); });
        button(g, (dark ? "✔ " : "") + "Home by dark", rx + hw + 4, ry, hw, 13, mx, my, !out, false, out ? why : "Night falling while it's on the road and it turns back",
            () -> { CompoundTag t = new CompoundTag(); t.putBoolean("dark", !dark); PaKitClient.send("er_opt", t); });
        ry += 17;
        if (run != null) ry = runBox(g, run, rx, ry, colW, out, foot - 2, mx, my);
        if (!out) {
            boolean any = !sell.isEmpty() || goods.stream().anyMatch(t -> ((CompoundTag) t).getInt("want") > 0);
            String who = PaClient.state.getString("name");
            int by = foot - 22;
            button(g, "Show me", rx, by, 54, 18, mx, my, true, false, "Mark the market it would go to, with how far it is", () -> PaKitClient.send("er_show"));
            confirmButton(g, "send", "Send " + (who.isEmpty() ? "it" : who), "Sure? Send", rx + 58, by, colW - 58, 18, mx, my, any, true,
                any ? "It walks to " + e.getString("town") + ", sells what you handed it, buys the list (up to " + limit + " coins) and walks back"
                    : "hand it something to sell, or put something on the list first", () -> PaKitClient.send("er_go"));
        }
    }

    private void coinsBy(int by) {
        CompoundTag t = new CompoundTag();
        t.putInt("by", by);
        PaKitClient.send("er_coins", t);
    }

    private int runBox(GuiGraphics g, CompoundTag run, int x, int y, int iw, boolean out, int bottom, int mx, int my) {
        String stage = switch (run.getString("stage")) {
            case "OUT" -> "On the road to " + run.getString("town");
            case "WAIT" -> "Waiting for the market to open";
            case "SELL" -> "Selling at the market";
            case "BUY" -> "Buying at the market";
            case "BACK" -> "On the way back";
            default -> "Last errand: done";
        };
        UiStyle.title(g, font, UiStyle.clip(font, stage, iw - 56), x, y);
        if (out) button(g, "Show me", x + iw - 52, y - 2, 52, 12, mx, my, true, false, "Mark where it is now", () -> PaKitClient.send("er_show"));
        y += 12;
        g.drawString(font, UiStyle.clip(font, "Sold: " + run.getString("sold") + " (+" + run.getInt("earned") + "c)", iw), x, y, UiStyle.TEXT_MUTED, false);
        y += 10;
        g.drawString(font, UiStyle.clip(font, "Bought: " + run.getString("bought") + " (-" + run.getInt("spent") + "c)", iw), x, y, UiStyle.TEXT_MUTED, false);
        y += 10;
        ListTag notes = run.getList("notes", Tag.TAG_STRING);
        if (!notes.isEmpty()) {
            String n = notes.getString(notes.size() - 1);
            for (var line : font.split(Component.literal(n), iw)) { if (y > bottom - 30) break; g.drawString(font, line, x, y, UiStyle.WARNING, false); y += 10; }
        }
        if (out) button(g, "Call it back", x, Math.min(y + 2, bottom - 16), iw, 14, mx, my, true, false, "It stops where it is and walks back to you with what it has", () -> PaKitClient.send("er_call"));
        return y + 18;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        goodsScroll = Math.max(0, goodsScroll - (int) Math.signum(dy));
        return true;
    }
}
