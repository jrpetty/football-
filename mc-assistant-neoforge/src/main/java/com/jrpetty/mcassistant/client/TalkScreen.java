package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.net.FolkTalkPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
 * Talking with a folk, face to face.
 *
 * <p>Up top: its likeness, who it is, how it feels, what it thinks of you, what its
 * village thinks of you, and anything it has asked you to do. Below that, what it
 * just said (scroll for more). Then things to say — or type your own. Closing the
 * screen says goodbye.
 */
public class TalkScreen extends Screen {

    private static final int W = 360, H = 316;
    private static final int SPEECH_LINES = 6;

    private FolkReplyPayload last;
    private String asked = "";
    private int scroll;
    private EditBox say;
    private Button gift;
    private Button walk;
    private Button deliver;
    private boolean saidBye;

    public TalkScreen(FolkReplyPayload first) {
        super(Component.literal(first.name()));
        this.last = first;
        this.asked = first.asked();
    }

    public int entityId() { return last.entityId(); }

    public void update(FolkReplyPayload reply) {
        this.last = reply;
        this.asked = reply.asked();
        this.scroll = 0;
        if (walk != null) walk.setMessage(Component.literal(reply.following() ? "Go home" : "Come along"));
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    private record Choice(String label, TalkTopic topic, String text, String tip) {
        Choice(String label, TalkTopic topic) { this(label, topic, "", topic.line); }
    }

    @Override
    protected void init() {
        int x = left() + 8, y = top() + 172;
        int cols = 4, bw = (W - 16 - (cols - 1) * 3) / cols, bh = 18;
        Choice[] grid = {
            new Choice("How are you?", TalkTopic.HOW), new Choice("Your work?", TalkTopic.DOING),
            new Choice("About you", TalkTopic.ABOUT), new Choice("Your family?", TalkTopic.PEOPLE),
            new Choice("Any news?", TalkTopic.VILLAGE), new Choice("Your hopes?", TalkTopic.DREAMS),
            new Choice("Memories?", TalkTopic.MEMORY), new Choice("Pastimes?", TalkTopic.HOBBY),
            new Choice("Can I help?", TalkTopic.HELP), new Choice("A favour?", TalkTopic.FAVOUR),
            new Choice("My standing?", TalkTopic.REPUTE), new Choice("A joke!", TalkTopic.JOKE),
            new Choice("Trade?", TalkTopic.TRADE), new Choice("Gossip?", TalkTopic.GOSSIP),
            new Choice("Residents?", TalkTopic.CENSUS),
            new Choice("I'm sorry", TalkTopic.SAY, "I'm sorry", "Apologise for whatever you did"),
        };
        for (int i = 0; i < grid.length; i++) {
            Choice c = grid[i];
            Button b = Button.builder(Component.literal(c.label()), btn -> ask(c.topic(), c.text()))
                .bounds(x + (i % cols) * (bw + 3), y + (i / cols) * (bh + 2), bw, bh).build();
            b.setTooltip(Tooltip.create(Component.literal(c.tip())));
            addRenderableWidget(b);
        }
        int row = y + (grid.length / cols) * (bh + 2);
        gift = addRenderableWidget(Button.builder(Component.literal("Give…"), b -> ask(TalkTopic.GIFT, ""))
            .bounds(x, row, bw, bh).build());
        deliver = addRenderableWidget(Button.builder(Component.literal("Hand over"), b -> ask(TalkTopic.DELIVER, ""))
            .bounds(x + (bw + 3), row, bw, bh).build());
        deliver.setTooltip(Tooltip.create(Component.literal("Give it what it asked you for, or pay for what it offered")));
        walk = addRenderableWidget(Button.builder(Component.literal(last.following() ? "Go home" : "Come along"),
                b -> ask(last.following() ? TalkTopic.STAY : TalkTopic.FOLLOW, ""))
            .bounds(x + 2 * (bw + 3), row, bw, bh).build());
        Button book = addRenderableWidget(Button.builder(Component.literal("History"), b -> ask(TalkTopic.CHRONICLE, ""))
            .bounds(x + 3 * (bw + 3), row, bw, bh).build());
        book.setTooltip(Tooltip.create(Component.literal("Ask for a copy of the village's chronicle")));
        int sayY = row + bh + 6;
        say = new EditBox(font, x, sayY, W - 16 - 2 * 46 - 6, 18, Component.literal("Say something"));
        say.setMaxLength(FolkTalkPayload.MAX_TEXT);
        say.setHint(Component.literal("Say anything… (try a name)"));
        addRenderableWidget(say);
        addRenderableWidget(Button.builder(Component.literal("Say"), b -> sayTyped())
            .bounds(x + W - 16 - 2 * 46 - 3, sayY, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Bye"), b -> onClose())
            .bounds(x + W - 16 - 46, sayY, 46, 18).build());
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int lines = font.split(FormattedText.of(last.said()), W - 82).size();
        scroll = Math.max(0, Math.min(Math.max(0, lines - SPEECH_LINES), scroll - (int) Math.signum(scrollY)));
        return true;
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
        gift.setMessage(Component.literal(held.isEmpty() ? "Give…" : "Give " + held.getHoverName().getString()));
        deliver.active = last.canDeliver();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left(), y = top(), tx = x + 66, tw = W - 74;
        // Who, and how things stand.
        g.drawString(font, Component.literal(last.name()), tx, y + 8, 0xFFFFE9A8, true);
        int ny = y + 20;
        for (FormattedCharSequence line : font.split(FormattedText.of(last.about()), tw)) {
            g.drawString(font, line, tx, ny, 0xFFBFC7D5, false);
            ny += 10;
        }
        g.drawString(font, Component.literal("Mood: " + face(last.mood()) + " " + last.moodWord()), tx, ny + 2,
            moodColour(last.mood()), false);
        g.drawString(font, Component.literal(hearts(last.affinity()) + "  " + last.name() + " " + last.standing() + "."),
            tx, ny + 13, last.affinity() < -10 ? 0xFFE07070 : 0xFFF0A0B8, false);
        int vy = ny + 24;
        for (FormattedCharSequence line : font.split(FormattedText.of(last.village()), tw)) {
            g.drawString(font, line, tx, vy, 0xFF9FD3E8, false);
            vy += 10;
        }
        if (!last.errand().isEmpty()) {
            for (FormattedCharSequence line : font.split(FormattedText.of(last.errand()), tw)) {
                g.drawString(font, line, tx, vy, 0xFFFFC857, false);
                vy += 10;
            }
        }
        // What was asked, and what it said.
        int sy = y + 106;
        g.fill(x + 8, sy - 4, x + W - 8, y + 168, 0x66000000);
        int line0 = sy;
        if (!asked.isEmpty()) {
            g.drawString(font, Component.literal("You: " + asked), x + 14, line0, 0xFF9AA3B2, false);
            line0 += 11;
        }
        List<FormattedCharSequence> lines = font.split(FormattedText.of(last.said()), W - 30);
        int room = asked.isEmpty() ? SPEECH_LINES : SPEECH_LINES - 1;
        for (int i = 0; i < room && scroll + i < lines.size(); i++) {
            g.drawString(font, lines.get(scroll + i), x + 14, line0 + i * 10, 0xFFFFFFFF, false);
        }
        if (lines.size() > room) {
            g.drawString(font, Component.literal(scroll + room < lines.size() ? "▼" : "▲"), x + W - 18, y + 156, 0xFF9AA3B2, false);
        }
        // Its likeness.
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getEntity(last.entityId()) instanceof LivingEntity folk) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + 8, y + 6, x + 60, y + 90, 30, 0.0625F,
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

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
