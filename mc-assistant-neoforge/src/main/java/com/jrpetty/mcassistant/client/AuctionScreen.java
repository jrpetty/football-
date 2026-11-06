package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.AuctionActionPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * [fleet] The auction's bid screen (entity/Auctions), opened by right-clicking the auctioneer: the lot being called,
 * held up large, where it came from and its reserve; the bid and who has it, going once or twice; the last few bids;
 * your coin. A button bids the next step, another two steps, and a box takes a bid of your own. Down the side, the
 * day's lots and the past sales; along the foot, the thing in your hand to put up for auction. The server sends the
 * screen afresh with every bid, so it follows the auction live.
 */
public class AuctionScreen extends Screen {

    private static final int PAD = 8;

    private CompoundTag data;
    private int left, top, w, h;
    private String typed = "";

    public AuctionScreen(CompoundTag data) {
        super(Component.literal("Auction"));
        this.data = data;
    }

    /** The server's answer: open the screen, or bring the open one up to date. */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof AuctionScreen open) {
            open.data = data;
            open.rebuildWidgets();
            return;
        }
        mc.setScreen(new AuctionScreen(data));
    }

    private CompoundTag lot() {
        return data.getCompound("lot");
    }

    private boolean calling() {
        return !lot().isEmpty();
    }

    @Override
    protected void init() {
        this.w = Math.min(380, this.width - 8);
        this.h = Math.min(236, this.height - 8);
        this.left = (this.width - w) / 2;
        this.top = (this.height - h) / 2;
        int by = top + h - PAD - 18;
        int x = left + PAD;
        CompoundTag lot = lot();
        int coins = data.getInt("coins");
        if (calling()) {
            int next = lot.getInt("next");
            int step = Math.max(1, next - Math.max(lot.getInt("high"), lot.getInt("reserve") - 1));
            int two = next + step;
            boolean mine = lot.getBoolean("mine"), own = lot.getBoolean("own");
            Button a = Button.builder(Component.literal("Bid " + next + "c"), b -> bid(next)).bounds(x, by - 22, 78, 18).build();
            a.active = !mine && !own && coins >= next;
            addRenderableWidget(a);
            Button c = Button.builder(Component.literal("Bid " + two + "c"), b -> bid(two)).bounds(x + 82, by - 22, 78, 18).build();
            c.active = !mine && !own && coins >= two;
            addRenderableWidget(c);
            EditBox box = new EditBox(font, x + 164, by - 21, 44, 16, Component.literal("Your bid"));
            box.setMaxLength(4);
            box.setFilter(s -> s.matches("\\d{0,4}"));
            box.setValue(typed);
            box.setHint(Component.literal(Integer.toString(next)).withStyle(ChatFormatting.DARK_GRAY));
            box.setResponder(s -> typed = s);
            addRenderableWidget(box);
            Button own_ = Button.builder(Component.literal("Bid"), b -> {
                if (!typed.isEmpty()) bid(Integer.parseInt(typed));
            }).bounds(x + 212, by - 22, 40, 18).build();
            own_.active = !mine && !own;
            addRenderableWidget(own_);
        }
        String hand = data.getString("hand");
        Button put = Button.builder(Component.literal(hand.isEmpty() ? "Hold something to put it up" : Ui.clip(font, "Put up: " + hand, 150)),
            b -> send("put", new CompoundTag())).bounds(x, by, 170, 18).build();
        put.active = !hand.isEmpty();
        addRenderableWidget(put);
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + w - PAD - 80, by, 80, 18).build());
    }

    private void bid(int coins) {
        CompoundTag args = new CompoundTag();
        args.putInt("coins", coins);
        typed = "";
        send("bid", args);
    }

    private void send(String action, CompoundTag args) {
        PacketDistributor.sendToServer(new AuctionActionPayload(action, args));
    }

    @Override
    public void onClose() {
        send("close", new CompoundTag());
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, w, h, 22);
        g.drawString(font, "The auction at " + data.getString("town"), left + PAD, top + 7, Ui.INK, false);
        Ui.right(g, font, "Your coin: " + data.getInt("coins") + "c", left + w - PAD, top + 7, Ui.ACCENT);
        int x = left + PAD, y = top + 28;
        int side = left + w * 58 / 100;
        int colW = side - x - 6;
        CompoundTag lot = lot();
        if (calling()) {
            Ui.section(g, font, "Lot " + lot.getInt("number") + " of " + lot.getInt("of"), x, y, colW);
            ItemStack it = Minecraft.getInstance().level == null ? ItemStack.EMPTY
                : ItemStack.parseOptional(Minecraft.getInstance().level.registryAccess(), lot.getCompound("item"));
            g.fill(x, y + 12, x + 36, y + 48, Ui.ROW);
            g.renderOutline(x, y + 12, 36, 36, Ui.EDGE_SOFT);
            if (!it.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(x + 2, y + 14, 0);
                g.pose().scale(2.0F, 2.0F, 1.0F);
                g.renderItem(it, 0, 0);
                g.pose().popPose();
            }
            int tx = x + 42, tw = colW - 42;
            g.drawString(font, Ui.clip(font, lot.getString("name"), tw), tx, y + 13, Ui.INK, false);
            small(g, Ui.clip(font, lot.getString("from"), (int) (tw / 0.75F)), tx, y + 24, Ui.MUTED);
            small(g, "reserve " + lot.getInt("reserve") + "c · worth about " + lot.getInt("worth") + "c", tx, y + 33, Ui.FAINT);
            int high = lot.getInt("high");
            String bidLine = high <= 0 ? "No bids yet — from " + lot.getInt("reserve") + "c"
                : high + "c — " + lot.getString("highName") + (lot.getBoolean("mine") ? " (you)" : "");
            g.drawString(font, Ui.clip(font, bidLine, colW), x, y + 54, high > 0 && lot.getBoolean("mine") ? Ui.GOOD : Ui.INK, false);
            int calls = lot.getInt("calls");
            String going = high <= 0 ? "" : calls == 1 ? "Going once…" : calls >= 2 ? "Going twice…" : "";
            if (!going.isEmpty()) Ui.pill(g, font, going, x, y + 66, Ui.BAD);
            if (lot.getBoolean("own")) small(g, "Your own lot: you can't bid on it.", x, y + 68, Ui.WARN);
            ListTag bids = lot.getList("bids", Tag.TAG_STRING);
            int by = y + 82;
            for (int i = 0; i < bids.size() && by < top + h - 64; i++) {
                small(g, Ui.clip(font, bids.getString(i), (int) (colW / 0.75F)), x + 2, by, i == 0 ? Ui.INK : Ui.MUTED);
                by += 9;
            }
        } else {
            Ui.section(g, font, "No lot under the hammer", x, y, colW);
            String phase = data.getString("phase");
            String state = phase.equals("GATHER") ? "The crowd is gathering — " + data.getString("auctioneer") + " will call the first lot in a moment."
                : phase.equals("LOTS") ? "Today's lots are listed; the auction starts at nine."
                : phase.equals("DONE") ? "Today's auction is over." : "";
            int ty = y + 14;
            for (String line : wrap(state.isEmpty() ? data.getString("when") : state + " " + data.getString("when"), (int) (colW / 0.75F))) {
                small(g, line, x, ty, Ui.MUTED);
                ty += 9;
            }
            small(g, "Put something up: hold it and press the button below.", x, ty + 4, Ui.FAINT);
        }
        // The side: today's lots, the past sales.
        int sx = side, sw = left + w - PAD - side;
        int sy = y;
        Ui.section(g, font, "Today's lots", sx, sy, sw);
        sy += 12;
        ListTag lots = data.getList("lots", Tag.TAG_STRING);
        if (lots.isEmpty()) {
            small(g, "None today.", sx + 2, sy, Ui.FAINT);
            sy += 9;
        }
        for (int i = 0; i < lots.size() && sy < top + h / 2 + 10; i++) {
            String l = lots.getString(i);
            small(g, Ui.clip(font, l, (int) (sw / 0.75F)), sx + 2, sy, l.endsWith("(now)") ? Ui.GOOD : Ui.MUTED);
            sy += 9;
        }
        sy += 4;
        Ui.section(g, font, "Past sales", sx, sy, sw);
        sy += 12;
        ListTag sales = data.getList("sales", Tag.TAG_STRING);
        if (sales.isEmpty()) small(g, "Nothing sold yet.", sx + 2, sy, Ui.FAINT);
        for (int i = 0; i < sales.size() && sy < top + h - PAD - 44; i++) {
            small(g, Ui.clip(font, sales.getString(i), (int) (sw / 0.75F)), sx + 2, sy, Ui.MUTED);
            sy += 9;
        }
        String msg = data.getString("message");
        if (!msg.isEmpty()) small(g, Ui.clip(font, msg, (int) ((w - 2 * PAD) / 0.75F)), x, top + h - PAD - 52, Ui.ACCENT);
    }

    private java.util.List<String> wrap(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) > max && !line.isEmpty()) {
                out.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    private void small(GuiGraphics g, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
