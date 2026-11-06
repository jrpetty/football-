package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.StallActionPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A market stall of your own (entity/PlayerStalls), on a page: what is in its barrel, a kind a row, with
 * a price for a lot of each that you type in and the village's going price beside it (and how the folk
 * will take your price: a bargain, fair, dear, too dear); the till, with a button to take it; the week's
 * takings; the last sales, who bought what and for how much; and what the folk turned down as too dear.
 * Its rent, another week, or giving it up. A stall to let shows its rent and a button to take it.
 *
 * <p>Laid out to the room there is: as many rows of wares as fit (a page at a time, with arrows), down to
 * a GUI scale that leaves the screen 240 high.
 */
public class StallScreen extends Screen {

    private static final int PAD = 8, ROW_H = 14, HEAD = 28, TABLE_Y = 84, SALES_LINES = 3;
    /** Where each column of the wares starts, from the panel's inner edge. */
    private static final int C_COUNT = 124, C_LOT = 160, C_PRICE = 188, C_GOING = 236, C_SOLD = 274, C_NOTE = 316;

    private CompoundTag data;
    private int left, top, w, h, rows = 1, page;
    /** Prices typed and not yet sent, by ware. */
    private final Map<String, String> edits = new HashMap<>();
    private boolean confirmGiveUp;

    public StallScreen(CompoundTag data) {
        super(Component.literal("Market stall"));
        this.data = data;
    }

    /** The server's answer: open the stall's page, or bring the open one up to date. */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof StallScreen open && open.sameStall(data)) {
            open.data = data;
            open.confirmGiveUp = false;
            open.rebuildWidgets();
            return;
        }
        mc.setScreen(new StallScreen(data));
    }

    private boolean sameStall(CompoundTag d) {
        return d.getInt("x") == data.getInt("x") && d.getInt("y") == data.getInt("y") && d.getInt("z") == data.getInt("z");
    }

    private String mode() {
        return data.getString("mode");
    }

    private List<CompoundTag> wares() {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = data.getList("wares", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private int pages() {
        return Math.max(1, (wares().size() + rows - 1) / rows);
    }

    /** The top of the buttons along the foot. */
    private int buttonsY() {
        return top + h - PAD - 18;
    }

    /** The top of the books (the last sales, the turnings-down), over the buttons. */
    private int booksY() {
        return buttonsY() - 4 - 11 - SALES_LINES * 9;
    }

    @Override
    protected void init() {
        this.w = Math.min(404, this.width - 8);
        this.h = Math.min(268, this.height - 8);
        this.left = (this.width - w) / 2;
        this.top = (this.height - h) / 2;
        this.rows = Math.max(2, (booksY() - 6 - (top + TABLE_Y)) / ROW_H);
        if (page >= pages()) page = 0;
        int x = left + PAD, inner = w - PAD * 2;
        int by = buttonsY();
        String mode = mode();
        int rent = data.getInt("rent");
        if (mode.equals("let")) {
            Button take = Button.builder(Component.literal("Rent it — " + rent + " coins a week"), b -> send("rent", new CompoundTag()))
                .bounds(x, by, inner - 96, 18).build();
            take.active = data.getInt("coins") >= rent;
            addRenderableWidget(take);
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + w - PAD - 90, by, 90, 18).build());
            return;
        }
        boolean own = mode.equals("own");
        // The prices, a box a row on this page.
        if (own) {
            List<CompoundTag> list = wares();
            for (int i = page * rows; i < Math.min(list.size(), (page + 1) * rows); i++) {
                CompoundTag ware = list.get(i);
                String key = ware.getString("key");
                int ry = top + TABLE_Y + (i - page * rows) * ROW_H;
                EditBox box = new EditBox(font, x + C_PRICE, ry, 40, 12, Component.literal("Price for " + ware.getString("name")));
                box.setMaxLength(4);
                box.setFilter(s -> s.matches("\\d{0,4}"));
                box.setValue(edits.containsKey(key) ? edits.get(key) : ware.getBoolean("set") ? Integer.toString(ware.getInt("price")) : "");
                box.setHint(Component.literal(Integer.toString(ware.getInt("going"))).withStyle(ChatFormatting.DARK_GRAY));
                box.setResponder(s -> edits.put(key, s));
                addRenderableWidget(box);
            }
        }
        if (pages() > 1) {
            int px = left + w - PAD - 34;
            addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page = (page + pages() - 1) % pages(); rebuildWidgets(); })
                .bounds(px, top + TABLE_Y - 20, 16, 12).build());
            addRenderableWidget(Button.builder(Component.literal("›"), b -> { page = (page + 1) % pages(); rebuildWidgets(); })
                .bounds(px + 18, top + TABLE_Y - 20, 16, 12).build());
        }
        if (mode.equals("view")) {
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + w - PAD - 90, by, 90, 18).build());
            return;
        }
        // The till.
        int till = data.getInt("till");
        Button tillButton = Button.builder(Component.literal(till > 0 ? "Take the till" : "Till empty"), b -> send("till", new CompoundTag()))
            .bounds(left + w - PAD - 92, top + HEAD + 2, 92, 16).build();
        tillButton.active = till > 0;
        addRenderableWidget(tillButton);
        int bw = (inner - 3 * 4) / 4;
        if (own) {
            addRenderableWidget(Button.builder(Component.literal("Save prices"), b -> savePrices()).bounds(x, by, bw, 18).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Take my goods"), b -> send("goods", new CompoundTag())).bounds(x, by, bw, 18).build());
        }
        Button pay = Button.builder(Component.literal((own ? "Pay a week (" : "Rent again (") + rent + "c)"), b -> send("rent", new CompoundTag()))
            .bounds(x + bw + 4, by, bw, 18).build();
        pay.active = data.getInt("coins") >= rent;
        addRenderableWidget(pay);
        addRenderableWidget(Button.builder(Component.literal(confirmGiveUp ? "Sure? Give it up" : "Give it up"), b -> {
            if (!confirmGiveUp) {
                confirmGiveUp = true;
                rebuildWidgets();
                return;
            }
            send("giveup", new CompoundTag());
        }).bounds(x + 2 * (bw + 4), by, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(x + 3 * (bw + 4), by, bw, 18).build());
    }

    private void savePrices() {
        if (edits.isEmpty()) return;
        CompoundTag prices = new CompoundTag();
        for (Map.Entry<String, String> e : edits.entrySet()) {
            String v = e.getValue().trim();
            prices.putInt(e.getKey(), v.isEmpty() ? -1 : Integer.parseInt(v));
        }
        edits.clear();
        CompoundTag args = new CompoundTag();
        args.put("prices", prices);
        send("prices", args);
    }

    private void send(String action, CompoundTag args) {
        args.putInt("x", data.getInt("x"));
        args.putInt("y", data.getInt("y"));
        args.putInt("z", data.getInt("z"));
        PacketDistributor.sendToServer(new StallActionPayload(action, args));
    }

    @Override
    public void onClose() {
        if (mode().equals("own")) savePrices();                     // what was typed is kept
        super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == 257 || key == 335) && !edits.isEmpty()) {           // Enter: the prices sent
            savePrices();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (pages() > 1 && dy != 0) {
            page = Math.floorMod(page - (int) Math.signum(dy), pages());
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    /** Everything of the screen's own, under the widgets (the menu blur runs in super.renderBackground:
     *  see NameScreen). */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, w, h, HEAD);
        int x = left + PAD, inner = w - PAD * 2;
        String mode = mode();
        String village = data.getString("village");
        if (mode.equals("let")) {
            drawLet(g, x, inner, village);
            return;
        }
        String owner = data.getString("owner");
        g.drawString(font, Ui.clip(font, owner + "'s stall — " + village, inner - 130), x, top + 10, Ui.INK, false);
        String state = data.getString("state");
        long day = data.getLong("day"), paidTill = data.getLong("paidTill");
        String pill = switch (state) {
            case "open" -> paidTill <= day ? "Paid to today" : "Paid to day " + paidTill + " (" + (paidTill - day) + "d)";
            case "due" -> "Shut: rent due";
            case "held" -> "Given back";
            default -> "";
        };
        int pc = state.equals("open") ? Ui.GOOD : state.equals("due") ? Ui.WARN : Ui.BAD;
        if (!pill.isEmpty()) Ui.pill(g, font, pill, left + w - PAD - font.width(pill) - 8, top + 9, pc);
        // The till and the week.
        int till = data.getInt("till");
        g.drawString(font, "Till: " + till + (till == 1 ? " coin" : " coins"), x, top + HEAD + 6, till > 0 ? Ui.GOOD : Ui.MUTED, false);
        int md = data.getInt("marketIn");
        String week = "This week " + data.getInt("sold7") + " sold for " + data.getInt("coin7") + "c (today " + data.getInt("soldToday")
            + " for " + data.getInt("coinToday") + "c) · in all " + data.getInt("takenAll") + "c";
        small(g, Ui.clip(font, week, (int) ((inner - 100) / 0.75)), x, top + HEAD + 18, Ui.MUTED);
        String when = (md == 0 ? "Market day today: the folk are out buying." : "Market day in " + md + (md == 1 ? " day." : " days."))
            + " Rent " + data.getInt("rent") + "c a week; you have " + data.getInt("coins") + "c.";
        if (mode.equals("held")) when = "Given back: your goods are kept in its barrel for you. Take them, or rent it again.";
        small(g, Ui.clip(font, when, (int) ((inner - 100) / 0.75)), x, top + HEAD + 27, mode.equals("held") ? Ui.WARN : Ui.MUTED);
        // The wares.
        boolean own = mode.equals("own");
        Ui.section(g, font, own ? "Your stock, and your price for a lot" : "In the stall", x, top + TABLE_Y - 19, inner - 40);
        if (data.getBoolean("worn")) small(g, "(worn things don't sell)", x + C_SOLD, top + TABLE_Y - 19, Ui.WARN);
        int hy = top + TABLE_Y - 9;
        small(g, "In it", x + C_COUNT, hy, Ui.FAINT);
        small(g, "Lot", x + C_LOT, hy, Ui.FAINT);
        small(g, "Price", x + C_PRICE, hy, Ui.FAINT);
        small(g, "Going", x + C_GOING, hy, Ui.FAINT);
        small(g, "Sold 7d", x + C_SOLD, hy, Ui.FAINT);
        small(g, "The folk", x + C_NOTE, hy, Ui.FAINT);
        List<CompoundTag> list = wares();
        for (int i = page * rows; i < Math.min(list.size(), (page + 1) * rows); i++) {
            CompoundTag ware = list.get(i);
            int ry = top + TABLE_Y + (i - page * rows) * ROW_H;
            boolean over = mouseX >= x && mouseX < x + inner && mouseY >= ry - 1 && mouseY < ry + ROW_H - 1;
            g.fill(x - 2, ry - 1, x + inner, ry + ROW_H - 1, over ? Ui.HI : (i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT));
            ItemStack icon = stackOf(ware.getString("item"), ware.getBoolean("ench"));
            if (!icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(x, ry - 1, 0);
                g.pose().scale(0.8F, 0.8F, 1F);
                g.renderItem(icon, 0, 0);
                g.pose().popPose();
            }
            g.drawString(font, Ui.clip(font, ware.getString("name"), C_COUNT - 20), x + 15, ry + 2, Ui.INK, false);
            int count = ware.getInt("count");
            g.drawString(font, count == 0 ? "out" : Integer.toString(count), x + C_COUNT, ry + 2, count == 0 ? Ui.BAD : Ui.INK, false);
            g.drawString(font, "×" + ware.getInt("lot"), x + C_LOT, ry + 2, Ui.MUTED, false);
            int going = ware.getInt("going");
            int price = price(ware);
            if (!own) g.drawString(font, price > 0 ? price + "c" : "—", x + C_PRICE, ry + 2, Ui.INK, false);
            g.drawString(font, going > 0 ? going + "c" : "—", x + C_GOING, ry + 2, Ui.MUTED, false);
            g.drawString(font, ware.getInt("sold7") > 0 ? Integer.toString(ware.getInt("sold7")) : "—", x + C_SOLD, ry + 2, Ui.INK, false);
            Judged judged = judge(price, going);
            small(g, Ui.clip(font, judged.words(), (int) ((inner - C_NOTE) / 0.75)), x + C_NOTE, ry + 3, judged.colour());
        }
        if (list.isEmpty()) {
            small(g, own ? "Nothing in it yet: right-click the barrel and fill it with what you'd sell." : "Nothing in it.", x, top + TABLE_Y + 2, Ui.MUTED);
        }
        if (pages() > 1) small(g, (page + 1) + "/" + pages(), left + w - PAD - 52, top + TABLE_Y - 17, Ui.FAINT);
        // The books: the last sales, and what was thought too dear.
        int sy = booksY();
        int half = (inner - 8) / 2;
        Ui.section(g, font, "Sold", x, sy, half);
        Ui.section(g, font, "Too dear", x + half + 8, sy, half);
        ListTag sales = data.getList("sales", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(SALES_LINES, sales.size()); i++) {
            CompoundTag s = sales.getCompound(i);
            String line = "Day " + s.getLong("day") + ": " + s.getString("who") + ", " + s.getInt("count") + " " + s.getString("what")
                + ", " + s.getInt("price") + "c";
            small(g, Ui.clip(font, line, (int) (half / 0.75)), x, sy + 11 + i * 9, Ui.INK);
        }
        if (sales.isEmpty()) small(g, "Nothing yet.", x, sy + 11, Ui.MUTED);
        ListTag dear = data.getList("dear", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(SALES_LINES, dear.size()); i++) {
            CompoundTag d = dear.getCompound(i);
            String line = d.getString("who") + ": " + d.getInt("price") + "c for " + d.getString("what") + " (going " + d.getInt("going") + "c)";
            small(g, Ui.clip(font, line, (int) (half / 0.75)), x + half + 8, sy + 11 + i * 9, Ui.WARN);
        }
        if (dear.isEmpty()) small(g, "Nobody's turned anything down.", x + half + 8, sy + 11, Ui.MUTED);
    }

    private void drawLet(GuiGraphics g, int x, int inner, String village) {
        g.drawString(font, Ui.clip(font, "A stall to let — " + village, inner), x, top + 10, Ui.INK, false);
        int rent = data.getInt("rent"), coins = data.getInt("coins"), md = data.getInt("marketIn");
        String[] lines = {
            "A stall of your own on the square: " + rent + " coins a week, paid to the village's treasury. You have " + coins + ".",
            "Fill its barrel with whatever you'd sell, then crouch and right-click it (or its sign) to set a price for a lot of each "
                + "thing, the village's going price beside each.",
            "The folk buy from it with their own coin, into your till: on market day (" + (md == 0 ? "today" : "in " + md + (md == 1 ? " day" : " days"))
                + "), and whenever one wants what you have: the tool of its trade, food when the larder's low, a comfort for its home, "
                + "a treat when it's doing well. The thrifty want it under the going price; the well-off pay a little over; nobody "
                + "half as much again.",
            "When the rent runs out it shuts; three days on it's given back, your goods kept in it for you. Nothing is lost."
        };
        int y = top + HEAD + 6, bottom = buttonsY() - 4;
        for (String line : lines) {
            for (FormattedCharSequence part : TextCache.split(font, line, (int) (inner / 0.85))) {
                if (y > bottom - 8) break;
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(0.85F, 0.85F, 1F);
                g.drawString(font, part, 0, 0, Ui.INK, false);
                g.pose().popPose();
                y += 9;
            }
            y += 4;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (mode().equals("let")) return;
        // The row under the mouse: how the folk will take its price.
        int x = left + PAD;
        List<CompoundTag> list = wares();
        for (int i = page * rows; i < Math.min(list.size(), (page + 1) * rows); i++) {
            int ry = top + TABLE_Y + (i - page * rows) * ROW_H;
            if (mouseX < x || mouseX >= x + C_PRICE || mouseY < ry - 1 || mouseY >= ry + ROW_H - 1) continue;
            CompoundTag ware = list.get(i);
            int going = ware.getInt("going"), price = price(ware), lot = ware.getInt("lot");
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(ware.getString("name") + (lot > 1 ? ", by the " + lot : "")));
            if (going <= 0) {
                tip.add(Component.literal("Nobody here deals in it: the folk won't buy it."));
            } else {
                tip.add(Component.literal("Going price " + going + "c a lot: what the village's own counter asks today."));
                tip.add(Component.literal("The thrifty pay up to " + Math.max(1, (int) Math.floor(going * 0.9 + 1e-9)) + "c; most folk " + going
                    + "c; the comfortable " + Math.max(going, (int) Math.floor(going * 1.1 + 1e-9)) + "c; the well-off "
                    + Math.max(going, (int) Math.floor(going * 1.25 + 1e-9)) + "c; nobody over " + Math.max(going, (int) Math.floor(going * 1.5 + 1e-9)) + "c."));
                tip.add(Component.literal("If the village has it itself, nobody pays more than it asks."));
                tip.add(Component.literal(price <= 0 ? "Kept back: not for sale." : "Yours: " + price + "c" + (ware.getBoolean("set") ? "" : " (the going price)")));
            }
            if (ware.getInt("sold7") > 0) tip.add(Component.literal("Sold " + ware.getInt("sold7") + " this week for " + ware.getInt("coin7") + "c."));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
            break;
        }
    }

    /** The price a row stands at: what is typed, else the stall's (the going price if none was set). */
    private int price(CompoundTag ware) {
        String typed = edits.get(ware.getString("key"));
        if (typed == null) return ware.getInt("price");
        if (typed.isBlank()) return ware.getInt("going");
        try {
            return Integer.parseInt(typed.trim());
        } catch (NumberFormatException e) {
            return ware.getInt("price");
        }
    }

    /** How the folk will take a price, in a word or two, and its colour. */
    private record Judged(String words, int colour) {}

    /** A bargain, fair, a little dear, dear, too dear: as PlayerStalls.willing weighs it. */
    private static Judged judge(int price, int going) {
        if (going <= 0) return new Judged("nobody buys it here", Ui.MUTED);
        if (price <= 0) return new Judged("kept back", Ui.MUTED);
        if (price <= Math.max(1, (int) Math.floor(going * 0.9 + 1e-9)) && price < going) return new Judged("a bargain: all buy", Ui.GOOD);
        if (price <= going) return new Judged("fair", Ui.GOOD);
        if (price <= Math.floor(going * 1.1 + 1e-9)) return new Judged("a little dear", Ui.WARN);
        if (price <= Math.floor(going * 1.25 + 1e-9)) return new Judged("dear: the well-off", Ui.WARN);
        if (price <= Math.floor(going * 1.5 + 1e-9)) return new Judged("dear: the rich only", Ui.WARN);
        return new Judged("too dear: nobody", Ui.BAD);
    }

    private static ItemStack stackOf(String id, boolean glint) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(BuiltInRegistries.ITEM.get(rl));
        if (glint && !s.isEmpty()) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    private void small(GuiGraphics g, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
