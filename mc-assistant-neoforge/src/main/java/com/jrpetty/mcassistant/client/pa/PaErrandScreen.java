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
 * many you want. Under them, the coins it carries (yours handed over, or back), its spending limit, whether to wait for
 * a shut market and whether to come home by dark, and Send. While it's out: where it's got to, what it has sold and
 * bought so far, anything that went wrong, and Call it back.
 */
public final class PaErrandScreen extends PaLogiBase {

    private int poll, goodsScroll;

    PaErrandScreen() {
        super("Errand to town");
    }

    @Override
    protected void init() {
        size(440, 270);
    }

    @Override
    protected String title() {
        CompoundTag e = PaKitClient.errand;
        String town = e == null ? "" : e.getString("town");
        return town.isEmpty() ? "Errand to town" : "Errand to " + town + (e.getInt("dist") >= 0 ? " (" + e.getInt("dist") + " blocks)" : "");
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
        if (e == null) { g.drawString(font, "Asking...", left + 10, top + 30, muted(), false); return; }
        if (e.getString("town").isEmpty()) {
            g.drawString(font, "There's no town near enough to run an errand to.", left + 10, top + 32, UiStyle.DANGER, false);
            return;
        }
        int x = left + 8, y = top + 28, colW = (w - 24) / 2;
        CompoundTag run = e.contains("run") ? e.getCompound("run") : null;
        boolean out = run != null && run.getBoolean("active");
        // ---- left: your inventory, and what it carries to sell
        UiStyle.heading(g, font, "Your things: click to hand over to sell", x, y, colW);
        int gy = y + 12;
        var pl = Minecraft.getInstance().player;
        if (pl != null) {
            Inventory inv = pl.getInventory();
            for (int i = 0; i < 36; i++) {
                int slot = i < 27 ? i + 9 : i - 27;                       // the pack first, the hotbar under it, as the inventory screen shows them
                int cx = x + (i % 9) * 19, cy = gy + (i / 9) * 19 + (i >= 27 ? 3 : 0);
                g.fill(cx, cy, cx + 18, cy + 18, 0xFF8B8B8B);
                g.fill(cx + 1, cy + 1, cx + 17, cy + 17, 0xFFC6C6C6);
                ItemStack s = inv.getItem(slot);
                if (s.isEmpty()) continue;
                g.renderItem(s, cx + 1, cy + 1);
                g.renderItemDecorations(font, s, cx + 1, cy + 1);
                if (!out) zone(cx, cy, 18, 18, () -> { CompoundTag t = new CompoundTag(); t.putInt("slot", slot); PaKitClient.send("er_give", t); },
                    s.getHoverName().getString() + ": click to hand it over to sell");
            }
        }
        gy += 4 * 19 + 8;
        UiStyle.heading(g, font, "To sell (click to have it back)", x, gy, colW);
        gy += 12;
        ListTag sell = e.getList("sell", Tag.TAG_COMPOUND);
        if (sell.isEmpty()) g.drawString(font, "Nothing yet.", x + 2, gy + 4, muted(), false);
        for (int i = 0; i < sell.size() && i < 18; i++) {
            CompoundTag c = sell.getCompound(i);
            int cx = x + (i % 9) * 19, cy = gy + (i / 9) * 19;
            String id = c.getString("i");
            icon(g, id, cx + 1, cy + 1, c.getInt("n"));
            if (!out) zone(cx, cy, 18, 18, () -> PaKitClient.send("er_back", itemArg(id)), nameOf(id) + " x" + c.getInt("n") + ": click to have it back");
        }
        // ---- right: the shopping list
        int rx = x + colW + 8;
        UiStyle.heading(g, font, "Shopping list (a lot's price today)", rx, y, colW);
        ListTag goods = e.getList("goods", Tag.TAG_COMPOUND);
        int rows = 8, ry = y + 12;
        goodsScroll = Math.max(0, Math.min(goodsScroll, Math.max(0, goods.size() - rows)));
        for (int i = goodsScroll; i < goodsScroll + rows && i < goods.size(); i++) {
            CompoundTag gd = goods.getCompound(i);
            String id = gd.getString("i");
            int want = gd.getInt("want"), price = gd.getInt("price");
            g.fill(rx, ry, rx + colW, ry + 17, want > 0 ? 0xFFD7EEEA : 0xFFDCE6E4);
            icon(g, id, rx + 1, ry, 1);
            g.drawString(font, UiStyle.clip(font, nameOf(id), colW - 104), rx + 19, ry + 5, UiStyle.TEXT, false);
            g.drawString(font, price > 0 ? price + "c" : "none", rx + colW - 84, ry + 5, price > 0 ? muted() : UiStyle.DANGER, false);
            int step = new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id))).getMaxStackSize() > 1 ? 8 : 1;
            if (!out) {
                button(g, "-", rx + colW - 54, ry + 2, 13, 13, mx, my, want > 0, false, "", () -> { CompoundTag t = itemArg(id); t.putInt("by", -step); PaKitClient.send("er_buy", t); });
                button(g, "+", rx + colW - 15, ry + 2, 13, 13, mx, my, true, false, "Add " + step, () -> { CompoundTag t = itemArg(id); t.putInt("by", step); PaKitClient.send("er_buy", t); });
            }
            String wn = String.valueOf(want);
            g.drawString(font, wn, rx + colW - 34 - font.width(wn) / 2, ry + 5, want > 0 ? UiStyle.TEXT : muted(), false);
            ry += 18;
        }
        if (goods.size() > rows) {
            button(g, "▲", rx + colW - 30, ry, 14, 11, mx, my, goodsScroll > 0, false, "", () -> goodsScroll--);
            button(g, "▼", rx + colW - 14, ry, 14, 11, mx, my, goodsScroll + rows < goods.size(), false, "", () -> goodsScroll++);
        }
        ry += 14;
        // ---- coins, options, send
        int coins = e.getInt("carried"), yours = e.getInt("yours"), limit = e.getInt("limit");
        g.drawString(font, UiStyle.clip(font, "Coins it carries: " + coins + "  (you have " + yours + ")", colW), rx, ry, UiStyle.TEXT, false);
        ry += 11;
        int q = (colW - 12) / 4;
        if (!out) {
            button(g, "+8", rx, ry, q, 13, mx, my, yours > 0, false, "Hand it 8 coins to spend", () -> coinsBy(8));
            button(g, "+32", rx + q + 4, ry, q, 13, mx, my, yours > 0, false, "Hand it 32 coins to spend", () -> coinsBy(32));
            button(g, "-8", rx + 2 * (q + 4), ry, q, 13, mx, my, coins > 0, false, "Take 8 back", () -> coinsBy(-8));
            button(g, "All back", rx + 3 * (q + 4), ry, q, 13, mx, my, coins > 0, false, "Take all its coins back", () -> coinsBy(-100000));
        }
        ry += 16;
        g.drawString(font, UiStyle.clip(font, "Spends at most " + limit + " (Settings > Behaviour)" + (e.getBoolean("openNow") ? "" : " · market shut now"), colW), rx, ry, muted(), false);
        ry += 12;
        boolean wait = e.getBoolean("wait"), dark = e.getBoolean("dark");
        int hw = (colW - 4) / 2;
        if (!out) {
            button(g, (wait ? "✔ " : "") + "Wait if shut", rx, ry, hw, 13, mx, my, true, wait, "If the market's shut when it gets there, it waits for it to open (else it comes home)",
                () -> { CompoundTag t = new CompoundTag(); t.putBoolean("wait", !wait); PaKitClient.send("er_opt", t); });
            button(g, (dark ? "✔ " : "") + "Home by dark", rx + hw + 4, ry, hw, 13, mx, my, true, dark, "Night falling before it gets there and it turns back (unless it's to wait)",
                () -> { CompoundTag t = new CompoundTag(); t.putBoolean("dark", !dark); PaKitClient.send("er_opt", t); });
        }
        ry += 17;
        if (run != null) {
            ry = runBox(g, run, rx, ry, colW, out, mx, my);
        }
        if (!out) {
            boolean any = !sell.isEmpty() || goods.stream().anyMatch(t -> ((CompoundTag) t).getInt("want") > 0);
            String who = PaClient.state.getString("name");
            button(g, "Send " + (who.isEmpty() ? "it" : who) + " to " + e.getString("town"), rx, Math.max(ry, top + h - 24), colW, 18, mx, my, any, true,
                any ? "It walks to the market, sells, buys, and walks back to you" : "Hand it something to sell, or put something on the list", () -> PaKitClient.send("er_go"));
        }
    }

    private void coinsBy(int by) {
        CompoundTag t = new CompoundTag();
        t.putInt("by", by);
        PaKitClient.send("er_coins", t);
    }

    private int runBox(GuiGraphics g, CompoundTag run, int x, int y, int iw, boolean out, int mx, int my) {
        String stage = switch (run.getString("stage")) {
            case "OUT" -> "On the road to " + run.getString("town");
            case "WAIT" -> "Waiting for the market to open";
            case "SELL" -> "Selling at the market";
            case "BUY" -> "Buying at the market";
            case "BACK" -> "On the way back";
            default -> "Back: last errand";
        };
        g.drawString(font, UiStyle.clip(font, stage, iw), x, y, out ? PaScreen.BRAND : muted(), false);
        y += 11;
        String s = "Sold: " + run.getString("sold") + " (+" + run.getInt("earned") + "c)";
        g.drawString(font, UiStyle.clip(font, s, iw), x, y, muted(), false);
        y += 10;
        String b = "Bought: " + run.getString("bought") + " (-" + run.getInt("spent") + "c)";
        g.drawString(font, UiStyle.clip(font, b, iw), x, y, muted(), false);
        y += 10;
        ListTag notes = run.getList("notes", Tag.TAG_STRING);
        if (!notes.isEmpty()) {
            String n = notes.getString(notes.size() - 1);
            for (var line : font.split(Component.literal(n), iw)) { g.drawString(font, line, x, y, UiStyle.WARNING, false); y += 10; if (y > top + h - 40) break; }
        }
        if (out) button(g, "Call it back", x, y + 2, iw, 14, mx, my, true, false, "It stops where it is and walks back to you", () -> PaKitClient.send("er_call"));
        return y + 18;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        goodsScroll = Math.max(0, goodsScroll - (int) Math.signum(dy));
        return true;
    }
}
