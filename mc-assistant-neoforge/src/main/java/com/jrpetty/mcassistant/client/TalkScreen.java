package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.net.FolkTalkPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Talking with a folk, face to face: who it is, how it feels and what it thinks of
 * you up top, its likeness beside what it just said, and things to say to it — or
 * type your own. Closing the screen says goodbye.
 */
public class TalkScreen extends Screen {

    private static final int W = 330, H = 236;

    private FolkReplyPayload last;
    private String asked = "";
    private EditBox say;
    private Button gift;
    private Button walk;
    private boolean saidBye;

    public TalkScreen(FolkReplyPayload first) {
        super(Component.literal(first.name()));
        this.last = first;
    }

    public int entityId() { return last.entityId(); }

    public void update(FolkReplyPayload reply) {
        this.last = reply;
        this.asked = reply.asked();
        if (walk != null) walk.setMessage(Component.literal(reply.following() ? "Go back to your day" : "Come with me"));
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        int x = left() + 8, y = top() + 128;
        int bw = (W - 24) / 3, bh = 18, gap = 2;
        TalkTopic[] grid = {
            TalkTopic.HOW, TalkTopic.DOING, TalkTopic.ABOUT,
            TalkTopic.PEOPLE, TalkTopic.VILLAGE, TalkTopic.DREAMS,
            TalkTopic.HOBBY, TalkTopic.JOKE, TalkTopic.FAVOUR,
        };
        for (int i = 0; i < grid.length; i++) {
            TalkTopic t = grid[i];
            addRenderableWidget(Button.builder(Component.literal(t.line), b -> ask(t, ""))
                .bounds(x + (i % 3) * (bw + 4), y + (i / 3) * (bh + gap), bw, bh).build());
        }
        int row = y + 3 * (bh + gap);
        gift = addRenderableWidget(Button.builder(Component.literal("Give…"), b -> ask(TalkTopic.GIFT, ""))
            .bounds(x, row, bw, bh).build());
        walk = addRenderableWidget(Button.builder(Component.literal(last.following() ? "Go back to your day" : "Come with me"),
                b -> ask(last.following() ? TalkTopic.STAY : TalkTopic.FOLLOW, ""))
            .bounds(x + bw + 4, row, bw, bh).build());
        addRenderableWidget(Button.builder(Component.literal("Goodbye"), b -> onClose())
            .bounds(x + 2 * (bw + 4), row, bw, bh).build());
        say = new EditBox(font, x, row + bh + 6, W - 16 - 54, 18, Component.literal("Say something"));
        say.setMaxLength(FolkTalkPayload.MAX_TEXT);
        say.setHint(Component.literal("Say something…"));
        addRenderableWidget(say);
        addRenderableWidget(Button.builder(Component.literal("Say"), b -> sayTyped())
            .bounds(x + W - 16 - 50, row + bh + 6, 50, 18).build());
    }

    private void ask(TalkTopic topic, String text) {
        asked = topic == TalkTopic.SAY ? text : topic.line;
        PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), topic.ordinal(), text));
    }

    private void sayTyped() {
        String text = say.getValue().trim();
        if (text.isEmpty()) return;
        say.setValue("");
        ask(TalkTopic.SAY, text);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (say != null && say.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            sayTyped();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        if (!saidBye) {
            saidBye = true;
            PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), TalkTopic.BYE.ordinal(), ""));
        }
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        Entity e = minecraft == null || minecraft.level == null ? null : minecraft.level.getEntity(last.entityId());
        if (e == null || !e.isAlive() || minecraft.player == null || e.distanceToSqr(minecraft.player) > 10.0 * 10.0) {
            saidBye = true;               // it has gone, or you have: nobody to say goodbye to
            onClose();
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        gift.active = !held.isEmpty();
        gift.setMessage(Component.literal(held.isEmpty() ? "Give (hold it)" : "Give " + held.getHoverName().getString()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left(), y = top();
        // Who, and how things stand.
        g.drawString(font, Component.literal(last.name()), x + 64, y + 8, 0xFFFFE9A8, true);
        int ny = y + 20;
        for (FormattedCharSequence line : font.split(FormattedText.of(last.about()), W - 72)) {
            g.drawString(font, line, x + 64, ny, 0xFFBFC7D5, false);
            ny += 10;
        }
        g.drawString(font, Component.literal("Mood: " + face(last.mood()) + " " + last.moodWord()), x + 64, ny + 2,
            moodColour(last.mood()), false);
        g.drawString(font, Component.literal(hearts(last.affinity()) + "  It " + last.standing() + "."), x + 64, ny + 13,
            last.affinity() < -10 ? 0xFFE07070 : 0xFFF0A0B8, false);
        // What was asked, and what it said.
        int sy = y + 74;
        g.fill(x + 60, sy - 4, x + W - 8, y + 122, 0x60000000);
        if (!asked.isEmpty()) {
            g.drawString(font, Component.literal("You: " + asked), x + 66, sy, 0xFF9AA3B2, false);
            sy += 11;
        }
        List<FormattedCharSequence> lines = font.split(FormattedText.of(last.said()), W - 80);
        for (int i = 0; i < Math.min(lines.size(), asked.isEmpty() ? 4 : 3); i++) {
            g.drawString(font, lines.get(i), x + 66, sy + i * 10, 0xFFFFFFFF, false);
        }
        // Its likeness.
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getEntity(last.entityId()) instanceof LivingEntity folk) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + 6, y + 6, x + 56, y + 120, 34, 0.0625F,
                mouseX, mouseY, folk);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, 0xE0181A20);
        g.fill(x, y, x + W, y + 1, 0xFF8A7A50);
        g.fill(x, y + H - 1, x + W, y + H, 0xFF8A7A50);
        g.fill(x, y, x + 1, y + H, 0xFF8A7A50);
        g.fill(x + W - 1, y, x + W, y + H, 0xFF8A7A50);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String face(int mood) {
        return mood >= 70 ? "☺" : mood >= 40 ? "•" : "☹";
    }

    private static int moodColour(int mood) {
        return mood >= 70 ? 0xFF8BE08B : mood >= 40 ? 0xFFE0D68B : 0xFFE08B8B;
    }

    private static String hearts(int affinity) {
        if (affinity <= -15) return "✗";
        int n = Math.max(0, Math.min(5, (affinity + 9) / 20));
        return "♥".repeat(n) + "♡".repeat(5 - n);
    }
}
