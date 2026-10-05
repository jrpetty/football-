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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Face to face with a folk.
 *
 * <p>Along the top: its likeness, who it is, how it feels, what it thinks of you, and what
 * it is doing this minute. Then the tabs — <b>Talk</b> (how it is, its family, its news,
 * a joke, a kind word), <b>Ask</b> (help, favours, what it is good at, what it earns, the
 * way somewhere, the stores, the elder's orders, the quest board, the ledger, the history),
 * <b>Village</b> (who lives here, the council, the neighbours, your standing, citizenship,
 * the board), <b>Deal</b> (gifts, errands, trade, hiring, a house, a walk together) and
 * <b>About</b> (its card: trade and levels, how its nature suits its work, what it is
 * worth, its home, family, friends, hopes, needs and latest memory) — and its Pack and its
 * Work record a click away. The conversation runs down the middle and is kept while you
 * are in the world, so you can pick up where you left off; anything can be typed at the foot.
 */
public class TalkScreen extends Screen {

    private static final int PAD = 8, HEADER = 56, TAB_H = 16, ROW = 16, LINE = 10;

    private static final int BG = 0xF0181B22, BAND = 0xFF20242E, EDGE = 0xFF8A7A50, SOFT = 0xFF3A3F4C,
        GOLD = 0xFFE8C46A, INK = 0xFFEDEDED, MUTED = 0xFF9AA3B2, YOU = 0xFF9EC3EA, LOG = 0x80000000,
        TAB = 0xFF2A2F3A, TAB_ON = 0xFF51441F, NAV = 0xFF22303A, ERRAND = 0xFFFFC857, PINK = 0xFFF0A0B8;

    private enum Tab {
        TALK("Talk"), ASK("Ask"), VILLAGE("Village"), DEAL("Deal"), ABOUT("About");
        final String label;
        Tab(String label) { this.label = label; }
    }

    /** One thing said, by you or by it. */
    private record Line(boolean you, String text) {}

    /** What has been said with each folk this session, so a conversation picks up again. */
    private static final Map<Integer, List<Line>> HISTORY = new HashMap<>();
    private static Tab lastTab = Tab.TALK;

    private FolkReplyPayload last;
    private Tab tab = lastTab;
    private boolean places;
    private int scroll, aboutScroll;
    private EditBox say;
    private String draft = "";
    private Button gift, deliver;
    private boolean saidBye;
    private int w, h, left, top;
    private final List<int[]> tabRects = new ArrayList<>();     // x, y, w, h, index (5 = pack, 6 = work)

    public TalkScreen(FolkReplyPayload first) {
        super(Component.literal(first.name()));
        this.last = first;
        remember(first);
    }

    public int entityId() { return last.entityId(); }

    public void update(FolkReplyPayload reply) {
        this.last = reply;
        remember(reply);
        this.scroll = 0;
        if (say != null) draft = say.getValue();
        rebuildWidgets();
    }

    private void remember(FolkReplyPayload r) {
        List<Line> log = HISTORY.computeIfAbsent(r.entityId(), k -> new ArrayList<>());
        if (!r.asked().isEmpty()) log.add(new Line(true, r.asked()));
        if (!r.said().isEmpty()) log.add(new Line(false, r.said()));
        while (log.size() > 80) log.remove(0);
        if (HISTORY.size() > 64) HISTORY.keySet().removeIf(id -> id != r.entityId() && HISTORY.size() > 48);
    }

    private record Choice(String label, TalkTopic topic, String text, String tip) {
        static Choice of(String label, TalkTopic topic) { return new Choice(label, topic, "", topic.line); }
        static Choice of(String label, TalkTopic topic, String tip) { return new Choice(label, topic, "", tip); }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        w = Math.min(384, width - 12);
        h = Math.min(280, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        tabRects.clear();
        gift = null;
        deliver = null;

        int inputY = top + h - PAD - 18;
        say = new EditBox(font, left + PAD, inputY, w - 2 * PAD - 2 * 46 - 6, 18, Component.literal("Say something"));
        say.setMaxLength(FolkTalkPayload.MAX_TEXT);
        say.setHint(Component.literal("Say anything… (\"show me the café\", \"8 bread, please?\")"));
        say.setValue(draft);
        addRenderableWidget(say);
        addRenderableWidget(Button.builder(Component.literal("Say"), b -> sayTyped())
            .bounds(left + w - PAD - 2 * 46 - 3, inputY, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Bye"), b -> onClose())
            .bounds(left + w - PAD - 46, inputY, 46, 18).build());

        if (tab == Tab.ABOUT) return;
        List<Choice> choices = places ? placeChoices() : choices(tab);
        int cols = w >= 340 ? 4 : 3;
        int bw = (w - 2 * PAD - (cols - 1) * 3) / cols;
        int rows = (choices.size() + cols - 1) / cols;
        int y0 = inputY - 6 - rows * ROW;
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            int x = left + PAD + (i % cols) * (bw + 3), y = y0 + (i / cols) * ROW;
            Button b = Button.builder(Component.literal(c.label()), btn -> choose(c)).bounds(x, y, bw, ROW - 2).build();
            if (!c.tip().isEmpty()) b.setTooltip(Tooltip.create(Component.literal(c.tip())));
            addRenderableWidget(b);
            if (c.topic() == TalkTopic.GIFT) gift = b;
            if (c.topic() == TalkTopic.DELIVER) deliver = b;
        }
    }

    private int logBottom() {
        int inputY = top + h - PAD - 18;
        if (tab == Tab.ABOUT) return inputY - 6;
        int cols = w >= 340 ? 4 : 3;
        int n = places ? placeChoices().size() : choices(tab).size();
        int rows = (n + cols - 1) / cols;
        return inputY - 10 - rows * ROW;
    }

    private int bodyTop() {
        return top + HEADER + 4 + TAB_H + 4;
    }

    private List<Choice> choices(Tab t) {
        List<Choice> out = new ArrayList<>();
        switch (t) {
            case TALK -> {
                out.add(Choice.of("How are you?", TalkTopic.HOW));
                out.add(Choice.of("Your work?", TalkTopic.DOING));
                out.add(Choice.of("About you", TalkTopic.ABOUT));
                out.add(Choice.of("Family?", TalkTopic.PEOPLE));
                out.add(Choice.of("Any news?", TalkTopic.VILLAGE));
                out.add(Choice.of("Your hopes?", TalkTopic.DREAMS));
                out.add(Choice.of("Memories?", TalkTopic.MEMORY));
                out.add(Choice.of("Pastimes?", TalkTopic.HOBBY));
                out.add(Choice.of("Gossip?", TalkTopic.GOSSIP));
                out.add(Choice.of("A joke!", TalkTopic.JOKE));
                out.add(Choice.of("Well done!", TalkTopic.PRAISE, "Tell it it's doing a fine job — it will warm to you"));
                out.add(new Choice("I'm sorry", TalkTopic.SAY, "I'm sorry", "Apologise for whatever you did"));
            }
            case ASK -> {
                out.add(Choice.of("Can I help?", TalkTopic.HELP, "Ask if there's an errand you could run for it"));
                out.add(Choice.of("A favour?", TalkTopic.FAVOUR));
                out.add(Choice.of("What's short?", TalkTopic.SHORT, "What the village is short of, and how you could help"));
                out.add(Choice.of("Your trade?", TalkTopic.WORKINGS, "How its work goes: tools, where it works, what it needs, where its work goes"));
                out.add(Choice.of("Good at?", TalkTopic.KNACK, "Its level in every trade it has worked, and how its nature suits its work"));
                out.add(Choice.of("Money?", TalkTopic.WORTH, "What it earns and what it is worth"));
                out.add(new Choice("Show me…", TalkTopic.GUIDE, "", "Ask it to walk you somewhere: the stores, the board, the elder, any building"));
                out.add(new Choice("Ask the stores", TalkTopic.STORES, "", "Ask the storekeeper for something: type what and how many"));
                out.add(Choice.of("Elder's orders", TalkTopic.ORDERS, "What the elder wants the village to put its back into"));
                out.add(Choice.of("Quest board", TalkTopic.QUESTS, "What the village wants done, and what it pays"));
                out.add(Choice.of("Town ledger", TalkTopic.LEDGER, "A book of the village's affairs"));
                out.add(Choice.of("History", TalkTopic.CHRONICLE, "Ask for a copy of the village's chronicle"));
            }
            case VILLAGE -> {
                out.add(Choice.of("Residents", TalkTopic.CENSUS));
                out.add(Choice.of("The council", TalkTopic.COUNCIL, "Who sits on the council, and what it voted. Say \"you should build a tavern\" to put it to the vote"));
                out.add(Choice.of("Neighbours", TalkTopic.RIVALS, "What this village thinks of the villages round about, who leads them, and who trades with whom"));
                out.add(Choice.of("Carry a letter", TalkTopic.LETTER, "Take a letter from the elder to the nearest neighbour's: both villages will think the better of you, and of each other"));
                out.add(Choice.of("Out there", TalkTopic.ATLAS, "What the village's scouts have found: towns, ruins, peaks, ore — and which way"));
                out.add(Choice.of("My standing", TalkTopic.REPUTE));
                out.add(Choice.of("Live here?", TalkTopic.CITIZEN, "Ask to become a citizen: a vote on the council and a house of your own"));
                out.add(Choice.of("Pay a fine", TalkTopic.FINE, "Pay what you owe the village"));
                out.add(new Choice("The board", TalkTopic.OPEN, "", "Read the village board: what it is doing, how it is getting on, what it is working towards"));
                out.add(new Choice("Suggest a build", TalkTopic.BUILD, "", "Type what you think the village should build next"));
            }
            case DEAL -> {
                out.add(new Choice("Give…", TalkTopic.GIFT, "", "Give it what you are holding"));
                out.add(new Choice("Hand over", TalkTopic.DELIVER, "", "Give it what it asked you for, or pay for what it offered"));
                out.add(Choice.of("Trade?", TalkTopic.TRADE));
                out.add(Choice.of("For sale?", TalkTopic.FOR_SALE, "What the village can spare, and at what price: it sees to itself first"));
                out.add(Choice.of("Hire you?", TalkTopic.HIRE, "Four coins a day: it goes with you, fights for you, carries for you"));
                out.add(Choice.of("Build my house", TalkTopic.COMMISSION, "Bring 64 planks, 32 cobblestone and 8 glass, and the builders put up a house for you"));
                out.add(last.following() ? Choice.of("Go home", TalkTopic.STAY) : Choice.of("Come along", TalkTopic.FOLLOW));
                out.add(new Choice("Show me…", TalkTopic.GUIDE, "", "Ask it to walk you somewhere"));
            }
            default -> { }
        }
        return out;
    }

    private List<Choice> placeChoices() {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice("‹ Back", TalkTopic.BYE, "back", ""));
        if (!last.places().isEmpty()) {
            for (String pair : last.places().split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                out.add(new Choice(pair.substring(eq + 1), TalkTopic.GUIDE, pair.substring(0, eq), "Walk there together"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ doing

    private void choose(Choice c) {
        if (c.topic() == TalkTopic.BYE && "back".equals(c.text())) {
            places = false;
            rebuildWidgets();
            return;
        }
        if (c.topic() == TalkTopic.GUIDE && c.text().isEmpty()) {
            places = true;
            rebuildWidgets();
            return;
        }
        if (c.topic() == TalkTopic.STORES) {                  // a question you finish yourself
            say.setValue("Could I have 8 bread?");
            setFocused(say);
            return;
        }
        if (c.topic() == TalkTopic.BUILD) {
            say.setValue("You should build a ");
            setFocused(say);
            return;
        }
        if (tab == Tab.VILLAGE && c.topic() == TalkTopic.OPEN) {     // the board: the village journal
            PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.VillageAskPayload(1));
            return;
        }
        if (c.topic() == TalkTopic.GUIDE) {
            places = false;
            ask(TalkTopic.GUIDE, c.text(), "Could you show me the way to " + c.label().toLowerCase(java.util.Locale.ROOT) + "?");
            return;
        }
        ask(c.topic(), c.text(), c.topic() == TalkTopic.SAY ? c.text() : c.topic().line);
    }

    private void ask(TalkTopic topic, String text, String shown) {
        PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), topic.ordinal(), text));
    }

    private void sayTyped() {
        String text = say.getValue().trim();
        if (text.isEmpty()) return;
        say.setValue("");
        draft = "";
        ask(TalkTopic.SAY, text, text);
    }

    private void openPack() {
        saidBye = true;                       // not a goodbye: the pack screen takes over
        PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), TalkTopic.PACK.ordinal(), ""));
    }

    private void openWork() {
        if (minecraft == null || minecraft.level == null) return;
        if (minecraft.level.getEntity(last.entityId()) instanceof com.jrpetty.mcassistant.entity.AssistantEntity a) {
            saidBye = true;
            minecraft.setScreen(new RecordScreen(a));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int[] r : tabRects) {
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (r[4] == 5) { openPack(); return true; }
                if (r[4] == 6) { openWork(); return true; }
                Tab t = Tab.values()[r[4]];
                if (t != tab || places) {
                    tab = t;
                    lastTab = t;
                    places = false;
                    if (say != null) draft = say.getValue();
                    rebuildWidgets();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (say != null && say.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            sayTyped();
            return true;
        }
        if (say != null && !say.isFocused() && key == GLFW.GLFW_KEY_TAB) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            lastTab = tab;
            places = false;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.ABOUT) aboutScroll = Math.max(0, aboutScroll - (int) Math.signum(scrollY));
        else scroll = Math.max(0, scroll + (int) Math.signum(scrollY));
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
        if (gift != null) {
            ItemStack held = minecraft.player.getMainHandItem();
            gift.active = !held.isEmpty();
            gift.setMessage(Component.literal(held.isEmpty() ? "Give…" : "Give " + held.getHoverName().getString()));
        }
        if (deliver != null) deliver.active = last.canDeliver();
    }

    // ------------------------------------------------------------------ painting

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + w, top + h, BG);
        g.fill(left, top, left + w, top + HEADER, BAND);
        g.fill(left, top, left + w, top + 1, EDGE);
        g.fill(left, top + h - 1, left + w, top + h, EDGE);
        g.fill(left, top, left + 1, top + h, EDGE);
        g.fill(left + w - 1, top, left + w, top + h, EDGE);
        g.fill(left + 1, top + HEADER, left + w - 1, top + HEADER + 1, SOFT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        header(g, mouseX, mouseY);
        tabs(g, mouseX, mouseY);
        if (tab == Tab.ABOUT) about(g);
        else conversation(g);
    }

    private void header(GuiGraphics g, int mouseX, int mouseY) {
        int px = left + PAD, py = top + 4;
        g.fill(px - 1, py - 1, px + 45, py + HEADER - 7, SOFT);
        g.fill(px, py, px + 44, py + HEADER - 8, 0xFF141821);
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getEntity(last.entityId()) instanceof LivingEntity folk) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, px, py, px + 44, py + HEADER - 8, 20, 0.0625F,
                mouseX, mouseY, folk);
        }
        int tx = px + 52, tw = left + w - PAD - tx;
        g.drawString(font, last.name(), tx, top + 6, GOLD, true);
        String where = Ui.clip(font, last.village(), Math.max(40, tw - font.width(last.name()) - 10));
        g.drawString(font, where, left + w - PAD - font.width(where), top + 6, MUTED, false);
        g.drawString(font, Ui.clip(font, last.about(), tw), tx, top + 17, MUTED, false);
        // Mood, as a word and a bar; what it thinks of you, as hearts.
        String mood = face(last.mood()) + " " + last.moodWord();
        g.drawString(font, mood, tx, top + 28, moodColour(last.mood()), false);
        int bx = tx + font.width(mood) + 6;
        g.fill(bx, top + 31, bx + 40, top + 34, 0x40FFFFFF);
        g.fill(bx, top + 31, bx + Math.max(1, 40 * Math.max(0, Math.min(100, last.mood())) / 100), top + 34, moodColour(last.mood()));
        String heart = hearts(last.affinity()) + " " + last.standing();
        g.drawString(font, Ui.clip(font, heart, Math.max(30, left + w - PAD - (bx + 48))), bx + 48, top + 28,
            last.affinity() < -10 ? 0xFFE07070 : PINK, false);
        String now = last.doing().isEmpty() ? "" : "Now: " + last.doing();
        g.drawString(font, Ui.clip(font, now, tw), tx, top + 40, INK, false);
    }

    private void tabs(GuiGraphics g, int mouseX, int mouseY) {
        tabRects.clear();
        int y = top + HEADER + 4;
        int x = left + PAD;
        for (Tab t : Tab.values()) {
            int tw = font.width(t.label) + 14;
            boolean on = t == tab && !places;
            boolean hover = mouseX >= x && mouseX < x + tw && mouseY >= y && mouseY < y + TAB_H;
            g.fill(x, y, x + tw, y + TAB_H, on ? TAB_ON : hover ? 0xFF353B48 : TAB);
            if (on) g.fill(x, y + TAB_H - 2, x + tw, y + TAB_H, GOLD);
            g.drawString(font, t.label, x + 7, y + 4, on ? GOLD : INK, false);
            tabRects.add(new int[]{ x, y, tw, TAB_H, t.ordinal() });
            x += tw + 2;
        }
        // The pack and the work record, on the right.
        String[] nav = { "Work done ›", "Pack ›" };
        int rx = left + w - PAD;
        for (int i = 0; i < nav.length; i++) {
            int tw = font.width(nav[i]) + 12;
            rx -= tw;
            boolean hover = mouseX >= rx && mouseX < rx + tw && mouseY >= y && mouseY < y + TAB_H;
            g.fill(rx, y, rx + tw, y + TAB_H, hover ? 0xFF2F4350 : NAV);
            g.drawString(font, nav[i], rx + 6, y + 4, MUTED, false);
            tabRects.add(new int[]{ rx, y, tw, TAB_H, i == 0 ? 6 : 5 });
            rx -= 2;
        }
    }

    private void conversation(GuiGraphics g) {
        int x = left + PAD, top0 = bodyTop(), bottom = logBottom();
        g.fill(x, top0, left + w - PAD, bottom, LOG);
        int y = top0 + 4;
        if (!last.errand().isEmpty()) {
            String e = Ui.clip(font, "★ " + last.errand(), w - 2 * PAD - 8);
            g.drawString(font, e, x + 4, y, ERRAND, false);
            y += LINE + 2;
        }
        if (places) {
            g.drawString(font, "Where would you like to go? It will walk you there.", x + 4, y, MUTED, false);
            y += LINE + 2;
        }
        // The conversation, newest at the bottom.
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        int width = w - 2 * PAD - 10;
        for (Line l : HISTORY.getOrDefault(last.entityId(), List.of())) {
            String text = l.you() ? "You: " + l.text() : l.text();
            for (FormattedCharSequence s : font.split(FormattedText.of(text), width)) {
                lines.add(s);
                colours.add(l.you() ? YOU : INK);
            }
        }
        int room = Math.max(1, (bottom - y - 3) / LINE);
        int maxScroll = Math.max(0, lines.size() - room);
        if (scroll > maxScroll) scroll = maxScroll;
        int start = Math.max(0, lines.size() - room - scroll);
        for (int i = 0; i < room && start + i < lines.size(); i++) {
            g.drawString(font, lines.get(start + i), x + 5, y + i * LINE, colours.get(start + i), false);
        }
        if (maxScroll > 0) {
            g.drawString(font, scroll < maxScroll ? "▲" : " ", left + w - PAD - 9, top0 + 2, MUTED, false);
            g.drawString(font, scroll > 0 ? "▼" : " ", left + w - PAD - 9, bottom - 10, MUTED, false);
        }
    }

    private void about(GuiGraphics g) {
        int x = left + PAD, top0 = bodyTop(), bottom = logBottom();
        g.fill(x, top0, left + w - PAD, bottom, LOG);
        int labelW = 66;
        int width = w - 2 * PAD - labelW - 12;
        List<String[]> rows = new ArrayList<>();
        for (String l : last.card().split("\n")) {
            int bar = l.indexOf('|');
            if (bar <= 0) continue;
            rows.add(new String[]{ l.substring(0, bar), l.substring(bar + 1) });
        }
        // Lay it out, then scroll it.
        List<Object[]> out = new ArrayList<>();
        for (String[] r : rows) {
            List<FormattedCharSequence> wrapped = font.split(FormattedText.of(r[1]), width);
            for (int i = 0; i < wrapped.size(); i++) out.add(new Object[]{ i == 0 ? r[0] : "", wrapped.get(i) });
            out.add(new Object[]{ "", null });
        }
        int room = Math.max(1, (bottom - top0 - 6) / LINE);
        aboutScroll = Math.min(aboutScroll, Math.max(0, out.size() - room));
        int y = top0 + 4;
        for (int i = 0; i < room && aboutScroll + i < out.size(); i++) {
            Object[] o = out.get(aboutScroll + i);
            String label = (String) o[0];
            if (!label.isEmpty()) g.drawString(font, label, x + 5, y, GOLD, false);
            if (o[1] != null) g.drawString(font, (FormattedCharSequence) o[1], x + 5 + labelW, y, INK, false);
            y += o[1] == null ? 3 : LINE;
        }
        if (rows.isEmpty()) g.drawString(font, "It has not said much about itself yet.", x + 5, top0 + 4, MUTED, false);
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
