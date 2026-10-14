package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.client.TalkMenu.Category;
import com.jrpetty.mcassistant.client.TalkMenu.Context;
import com.jrpetty.mcassistant.client.TalkMenu.Entry;
import com.jrpetty.mcassistant.client.TalkMenu.Kind;
import com.jrpetty.mcassistant.client.TalkMenu.Page;
import com.jrpetty.mcassistant.client.UiStyle.State;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.net.FolkTalkPayload;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * Face to face with a folk.
 *
 * <p>The header: its likeness, its name, trade and nature, what it is doing, its mood and what it thinks of you as
 * small bars, the town and your standing there; and four links (its profile card, its skills, its work record, its
 * pack). Down the left, six categories (TalkMenu: Chat, About, Ask, Trade, Town, World). In the middle, the chosen
 * category's few options, the rest a level down in groups, with a Suggested section over them when something is worth
 * doing now (a quest's step, its request of you, a fine, its birthday). On the right, the conversation in speech
 * bubbles, the latest at the foot, kept while you are in the world so you can pick up where you left off; and at the
 * foot a box to say anything at all.
 *
 * <p>Keys: 1-9 pick an option, Up/Down and Enter too; Tab and Shift+Tab change category; Esc or Backspace go up a level
 * (out of a group, off a page) and then close; T or Enter go to the box. Everything fits at every GUI scale: the
 * columns narrow, the tabs drop their words, the lists scroll, and every text that would run over is cut with an
 * ellipsis and shown whole in its tooltip.
 *
 * <p>Nothing is worked out per frame that can be worked out once: the rows, their clipped labels and the
 * conversation's wrapped bubbles are laid out when something changes (a reply, a category, the window, what you hold).
 */
public class TalkScreen extends Screen {

    // ------------------------------------------------------------------ layout constants

    /** The panel's largest size: wide enough for three comfortable columns, short enough to leave the world in view. */
    private static final int MAX_W = 448, MAX_H = 280;
    /** Below this the screen goes compact: a short header and the tabs as icons alone. */
    private static final int COMPACT_W = 380, COMPACT_H = 216;
    /** The header's height, full and compact. */
    private static final int HEADER_FULL = 52, HEADER_COMPACT = 30;
    /** The category tabs' column, with words and as icons alone. */
    private static final int TABS_FULL = 72, TABS_COMPACT = 24;
    /** The options column: its share of the room right of the tabs, and its limits. */
    private static final float OPTIONS_SHARE = 0.44F;
    private static final int OPTIONS_MIN = 104, OPTIONS_MAX = 168;
    /** The box and its two buttons at the foot. */
    private static final int BOX_H = 18, BUTTON_W = 44, BUTTON_W_COMPACT = 34;
    /** The header's link tiles. */
    private static final int LINK = 20, LINK_COMPACT = 18;
    /** A section heading's line in the options list, and the gap under a row. */
    private static final int HEAD_H = 13, ROW_GAP = 2;
    /** Speech bubbles: padding inside, the gap between, and the most of the column one may take. */
    private static final int BUBBLE_PAD = 4, BUBBLE_GAP = 4, BUBBLE_TURN_GAP = 7;
    private static final float BUBBLE_SHARE = 0.86F;

    /** Motion: the panel rising in as it opens, a list sliding in, the newest bubble rising, the flash of a choice. */
    private static final int OPEN_MS = 140, LIST_MS = 110, BUBBLE_MS = 130, FLASH_MS = 260;
    private static final int OPEN_RISE = 8, LIST_SLIDE = 6, BUBBLE_RISE = 6;
    /** How long a disabled option's reason stays up after you click it, and how long a "…" waits for an answer. */
    private static final int NOTICE_MS = 2600, WAIT_MS = 4000;
    /** How many lines a conversation keeps, and how many folk's conversations. */
    private static final int HISTORY_LINES = 80, HISTORY_FOLK = 64;
    /** A pixel scroll a notch of the wheel. */
    private static final int WHEEL_STEP = 20;

    /** Where a mood turns from fair to good, and from poor to fair. */
    private static final int MOOD_GOOD = 70, MOOD_FAIR = 40;
    /** What it thinks of you, as a bar: a warm rose. */
    private static final int AFFINITY_FILL = 0xFFD06A8A;
    /** The portrait's frame, a step darker than a well so the figure stands out; and how big the figure is drawn in it. */
    private static final int PORTRAIT = 0xFF8F8F8F;
    private static final float PORTRAIT_SCALE = 0.8F;
    /** A hairline between a profile's facts. */
    private static final int RULE = 0x1E000000;
    /** A disabled option's icon, greyed: a veil drawn above an item's depth (GuiGraphics.renderItem draws at 150 and up). */
    private static final int GREY_OUT = 0xA0B7B7B7, ICON_DEPTH = 250;
    /** The waiting dots' beat. */
    private static final int DOT_MS = 300;

    // ------------------------------------------------------------------ the conversation, kept

    /** One thing said, by you or by it. */
    private record Line(boolean you, String text) {}

    /** What has been said with each folk this session, so a conversation picks up again. */
    private static final Map<Integer, List<Line>> HISTORY = new HashMap<>();
    /** Bumped whenever any conversation grows, so a laid-out transcript knows it is stale. */
    private static int historyVersion;
    private static Category lastCategory = Category.CHAT;

    // ------------------------------------------------------------------ state

    private FolkReplyPayload last;
    private Context ctx = Context.NONE;
    private Category category = lastCategory;
    /** The group open in the options column, or null for the category's own list. */
    private Entry group;
    /** Choosing a place to be walked to (Guide). */
    private boolean places;
    private Page page = Page.TALK;

    private EditBox say;
    private String draft = "";
    private boolean saidBye;
    /** [teleport] Its name, for a creative player not beside it: Teleport to it (FolkLinks). */
    private final FolkLinks links = new FolkLinks(true);

    private final long openedAt = Util.getMillis();
    private long listAt, bubbleAt, flashAt, noticeAt, waitingSince;
    private String flashFull = "", notice = "";
    private Entry flashEntry;
    /** The number keys' caps, worked out once. */
    private static final String[] KEYS = { "", "1", "2", "3", "4", "5", "6", "7", "8", "9" };
    /** The waiting bubble's dots, one to three. */
    private static final String[] DOTS = { "•", "• •", "• • •" };
    private int focus = -1;
    /** What is held, as it was when the rows were laid out ("Give Bread"). */
    private String heldKey = "";

    // ------------------------------------------------------------------ layout, worked out in init()

    private int px, py, pw, ph, headerH, bodyY, bodyB, footY;
    private boolean compact;
    private int tabX, tabW, tabH, optX, optW, convX, convW, linkX, linkSize, buttonW;

    // ------------------------------------------------------------------ the options column, laid out on a change

    private enum RowType { HEAD, BACK, ITEM, NOTE }

    /** A row of the options list: its kind, its entry, its words (clipped to fit, and whole), its state, its key. */
    private record Row(RowType type, Entry entry, String shown, String full, String tip, boolean enabled, String why,
                       boolean suggested, int key, int y, int h, ItemStack icon) {
        boolean focusable() { return type == RowType.ITEM || type == RowType.BACK; }
    }

    private final List<Row> rows = new ArrayList<>();
    private final UiStyle.Scroll optScroll = new UiStyle.Scroll();

    // ------------------------------------------------------------------ the transcript, laid out on a change

    private record Bubble(boolean you, List<FormattedCharSequence> lines, int w, int h, int y) {}

    private final List<Bubble> bubbles = new ArrayList<>();
    private int bubblesVersion = -1, bubblesWidth = -1, bubblesHeight;
    /** How far the transcript is scrolled up from its foot, in pixels. */
    private final UiStyle.Scroll talkScroll = new UiStyle.Scroll();
    private List<FormattedCharSequence> requestLines = List.of();
    private final UiStyle.Scroll pageScroll = new UiStyle.Scroll();

    // ------------------------------------------------------------------ words and icons, worked out once (init, a reply)

    private ItemStack[] tabIcons = new ItemStack[0], linkIcons = new ItemStack[0];
    private String[] tabLabels = new String[0], tabTips = new String[0], linkTips = new String[0];
    private String sSay = "", sBye = "", sSayTip = "", sSayEmpty = "", sByeTip = "", sBackTip = "", sEmpty = "";
    private String sProfileEmpty = "", sSkillsEmpty = "", sPageBar = "";
    private Page pageBarFor;
    /** The header's lines, clipped to fit, and the tooltip with them whole. */
    private String nameShown = "", aboutShown = "", doingShown = "", whereShown = "", headerTip = "", moodShown = "", standingShown = "";
    private boolean headerCut;
    private int moodInk, moodFill, affInk, affFill, nameW;
    private float moodFrac, affFrac;
    /** The profile card laid out: a label (or "") and a wrapped line (or null, a gap between facts). */
    private final List<String> profileLabels = new ArrayList<>();
    private final List<FormattedCharSequence> profileLines = new ArrayList<>();
    private String profileFor;
    private int profileWidth = -1, profileHeight;

    public TalkScreen(FolkReplyPayload first) {
        super(Component.literal(first.name()));
        this.last = first;
        this.ctx = contextOf(first);
        remember(first);
        follow(first);
    }

    public int entityId() { return last.entityId(); }

    /** A new reply from the folk: what it said goes in, and the screen follows it (a page asked for, a category shown). */
    public void update(FolkReplyPayload reply) {
        this.last = reply;
        this.ctx = contextOf(reply);
        remember(reply);
        follow(reply);
        talkScroll.reset();
        waitingSince = 0;
        bubbleAt = Util.getMillis();
        layoutHeader();
        layoutRows();
        layoutRequest();
    }

    private static Context contextOf(FolkReplyPayload r) {
        return Context.of(r.context(), r.affinity(), r.following(), !r.errand().isEmpty(), r.canDeliver());
    }

    private static void remember(FolkReplyPayload r) {
        List<Line> log = HISTORY.computeIfAbsent(r.entityId(), k -> new ArrayList<>());
        if (!r.asked().isEmpty()) log.add(new Line(true, r.asked()));
        if (!r.said().isEmpty()) log.add(new Line(false, r.said()));
        while (log.size() > HISTORY_LINES) log.remove(0);
        if (HISTORY.size() > HISTORY_FOLK) HISTORY.keySet().removeIf(id -> id != r.entityId() && HISTORY.size() > HISTORY_FOLK * 3 / 4);
        historyVersion++;
    }

    /**
     * Where the screen goes with this reply. Asked about its skills or its knacks: the Skills page; its wage or who it is:
     * its profile. Asked anything else with a page open: back to the talk, where the answer is. And an operator's
     * {@code show=} (the pictures): that category or page.
     */
    private void follow(FolkReplyPayload reply) {
        String asked = reply.asked() == null ? "" : reply.asked().toLowerCase(Locale.ROOT);
        if (asked.contains("skill") || asked.contains("knack")) page = Page.SKILLS;
        else if (asked.contains("your wage") || asked.contains("who you are")) page = Page.PROFILE;
        else if (!reply.open() && !asked.isBlank()) page = Page.TALK;
        String show = ctx.value("show");
        if (!show.isEmpty()) {
            Category c = Category.of(show);
            if (c != null) {
                category = lastCategory = c;
                group = null;
                places = false;
                page = Page.TALK;
            } else if (show.equals("profile")) {
                page = Page.PROFILE;
            } else if (show.equals("skills")) {
                page = Page.SKILLS;
            }
        }
        pageScroll.reset();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        pw = Math.min(MAX_W, width - 2 * S2);
        ph = Math.min(MAX_H, height - 2 * S2);
        if (pw < 160) pw = Math.max(1, width - 2);
        if (ph < 120) ph = Math.max(1, height - 2);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        compact = pw < COMPACT_W || ph < COMPACT_H;
        headerH = compact ? HEADER_COMPACT : HEADER_FULL;
        linkSize = compact ? LINK_COMPACT : LINK;
        linkX = px + pw - S2 - TalkMenu.LINKS.size() * (linkSize + 2) + 2;
        buttonW = compact ? BUTTON_W_COMPACT : BUTTON_W;
        footY = py + ph - S1 - BOX_H - 1;
        bodyY = py + headerH + S1 + 2;
        bodyB = footY - S1;
        tabX = px + S1;
        tabW = compact ? TABS_COMPACT : TABS_FULL;
        tabH = Math.max(14, Math.min(TAB, (bodyB - bodyY) / Category.values().length));
        optX = tabX + tabW + S2;
        int room = px + pw - S2 - optX;
        optW = Math.max(Math.min(OPTIONS_MIN, room / 2), Math.min(OPTIONS_MAX, Math.round((room - S2) * OPTIONS_SHARE)));
        convX = optX + optW + S2;
        convW = Math.max(40, px + pw - S2 - convX);

        say = new EditBox(font, px + S2, footY, pw - 2 * S2 - 2 * buttonW - 2 * S1, BOX_H, Component.literal(t("ui.say")));
        say.setMaxLength(FolkTalkPayload.MAX_TEXT);
        say.setHint(Component.literal(t(compact ? "ui.hint_short" : "ui.hint")));
        say.setValue(draft);
        say.setResponder(v -> draft = v);
        addWidget(say);                      // drawn by render() itself, inside the opening motion
        layoutWords();
        layoutHeader();
        layoutRows();
        layoutRequest();
        bubblesWidth = -1;
    }

    /** The tabs', links' and buttons' words and icons, from the language file. */
    private void layoutWords() {
        Category[] cats = Category.values();
        tabIcons = new ItemStack[cats.length];
        tabLabels = new String[cats.length];
        tabTips = new String[cats.length];
        for (int i = 0; i < cats.length; i++) {
            tabIcons[i] = new ItemStack(cats[i].icon);
            String name = t("cat." + cats[i].id);
            tabLabels[i] = UiStyle.clip(font, name, tabW - ICON - S2 - 2);
            tabTips[i] = name + "\n" + t("cat." + cats[i].id + ".tip");
        }
        int n = TalkMenu.LINKS.size();
        linkIcons = new ItemStack[n];
        linkTips = new String[n];
        for (int i = 0; i < n; i++) {
            Entry e = TalkMenu.LINKS.get(i);
            linkIcons[i] = new ItemStack(e.icon());
            linkTips[i] = label(e) + "\n" + tip(e);
        }
        sSay = UiStyle.clip(font, t("ui.say"), buttonW - S1);
        sBye = UiStyle.clip(font, t("ui.bye"), buttonW - S1);
        sSayTip = t("ui.say_tip");
        sSayEmpty = t("ui.say_empty");
        sByeTip = t("ui.bye_tip");
        sBackTip = t("ui.back_tip");
        sEmpty = t("ui.empty");
        sProfileEmpty = t("ui.profile_empty");
        sSkillsEmpty = t("ui.skills_empty");
        pageBarFor = null;
    }

    /** The header's words, clipped to their room, and its mood and standing as inks and fractions. */
    private void layoutHeader() {
        int box = headerH - 2 * S1 - (compact ? 0 : 2);
        int tx = px + S1 + 2 + box + S2;
        int textW = Math.max(10, linkX - S2 - tx);
        nameShown = UiStyle.clip(font, last.name(), textW);
        nameW = titleWidth(font, nameShown);
        aboutShown = UiStyle.clip(font, last.about(), textW);
        String doing = last.doing().isEmpty() ? "" : I18n.get("talk.mc_assistant.ui.now", last.doing());
        doingShown = UiStyle.clip(font, doing, textW);
        int whereRoom = Math.max(0, px + pw - S2 - (linkX - 40));
        whereShown = UiStyle.clip(font, last.village(), whereRoom);
        headerCut = compact || !aboutShown.equals(last.about()) || !doingShown.equals(doing) || !whereShown.equals(last.village())
            || !nameShown.equals(last.name());
        headerTip = last.name() + "\n" + last.about() + (doing.isEmpty() ? "" : "\n" + doing)
            + (last.village().isEmpty() ? "" : "\n" + last.village());
        int mood = Math.max(0, Math.min(100, last.mood()));
        moodInk = mood >= MOOD_GOOD ? SUCCESS : mood >= MOOD_FAIR ? WARNING : DANGER;
        moodFill = mood >= MOOD_GOOD ? SUCCESS_FILL : mood >= MOOD_FAIR ? WARNING_FILL : DANGER_FILL;
        moodFrac = mood / 100F;
        int aff = last.affinity();
        affInk = aff < -10 ? DANGER : aff >= 30 ? SUCCESS : TEXT_MUTED;
        affFill = aff < -10 ? DANGER_FILL : AFFINITY_FILL;
        affFrac = (aff + 100) / 200F;
        int half = textW / 2, barW = statusBarW();
        moodShown = UiStyle.clip(font, face(mood) + " " + last.moodWord(), Math.max(10, half - barW - S1 - 2));
        standingShown = UiStyle.clip(font, hearts(aff) + " " + last.standing(), Math.max(10, textW - half - barW - S1));
    }

    private int statusBarW() {
        return compact ? 24 : 36;
    }

    /** The options column's rows: Suggested (at the top level), then the category's options or the open group's. */
    private void layoutRows() {
        rows.clear();
        ItemStack held = held();
        heldKey = held.isEmpty() ? "" : held.getHoverName().getString();
        int y = 0, key = 1;
        int w = optW - 5;                                        // room left for the scroll bar
        if (places) {
            y = addBack(y, t("ui.places"));
            List<Entry> where = placeEntries();
            if (where.isEmpty()) y = addNote(y, t("ui.places_empty"));
            for (Entry e : where) {
                rows.add(row(e, y, w, key++, false));
                y += ROW + ROW_GAP;
            }
        } else if (group != null) {
            y = addBack(y, label(group));
            for (Entry e : TalkMenu.visible(group.children(), ctx)) {
                rows.add(row(e, y, w, key++, false));
                y += ROW + ROW_GAP;
            }
        } else {
            List<Entry> sugg = new ArrayList<>(questEntries());
            for (Entry e : TalkMenu.suggested(ctx)) if (!sugg.contains(e)) sugg.add(e);
            if (!sugg.isEmpty()) {
                rows.add(new Row(RowType.HEAD, null, t("ui.suggested"), "", "", true, "", false, 0, y, HEAD_H, ItemStack.EMPTY));
                y += HEAD_H;
                for (Entry e : sugg) {
                    rows.add(row(e, y, w, key > 9 ? 0 : key, true));
                    key++;
                    y += ROW + ROW_GAP;
                }
                y += S1;
            }
            rows.add(new Row(RowType.HEAD, null, t(category.labelKey()), "", "", true, "", false, 0, y, HEAD_H, ItemStack.EMPTY));
            y += HEAD_H;
            List<Entry> list = TalkMenu.visible(category.entries(), ctx);
            if (list.isEmpty()) y = addNote(y, t("ui.empty_category"));
            for (Entry e : list) {
                rows.add(row(e, y, w, key > 9 ? 0 : key, false));
                key++;
                y += ROW + ROW_GAP;
            }
        }
        optScroll.fit(y, bodyB - bodyY);
        if (focus >= rows.size() || focus >= 0 && !rows.get(focus).focusable()) focus = -1;
    }

    private int addBack(int y, String title) {
        rows.add(new Row(RowType.BACK, null, UiStyle.clip(font, "‹ " + title, optW - 5 - S2 - 18), title, t("ui.back_tip"), true, "",
            false, 0, y, ROW, ItemStack.EMPTY));
        return y + ROW + ROW_GAP + 2;
    }

    private int addNote(int y, String text) {
        List<FormattedCharSequence> lines = TextCache.splitPlain(font, text, optW - 5 - S2);
        rows.add(new Row(RowType.NOTE, null, text, text, "", true, "", false, 0, y, lines.size() * LINE + S1, ItemStack.EMPTY));
        return y + lines.size() * LINE + S2;
    }

    /** One option's row: its words cut to fit beside its icon and its key, and whether it can be picked, and why not. */
    private Row row(Entry e, int y, int w, int key, boolean suggested) {
        String full = label(e);
        String tip = tip(e);
        String why = whyNot(e);
        int right = (e.isGroup() ? 8 : 0) + (key > 0 && !compact ? 10 : 0) + S1;
        String shown = UiStyle.clip(font, full, w - ICON - 6 - right);
        return new Row(RowType.ITEM, e, shown, full, tip, why.isEmpty(), why, suggested, key, y, ROW, icon(e));
    }

    private String label(Entry e) {
        if (e.label() != null) return e.label();
        if ((e == TalkMenu.GIFT) && !heldKey.isEmpty()) return I18n.get("talk.mc_assistant.ui.give_held", heldKey);
        return t(e.labelKey().substring("talk.mc_assistant.".length()));
    }

    private String tip(Entry e) {
        if (e.tip() != null) return e.tip();
        String k = e.tipKey();
        return I18n.exists(k) ? I18n.get(k) : "";
    }

    private ItemStack icon(Entry e) {
        if ((e == TalkMenu.GIFT || e.id().equals("birthday")) && minecraft != null && minecraft.player != null
                && !minecraft.player.getMainHandItem().isEmpty()) {
            return minecraft.player.getMainHandItem().copyWithCount(1);
        }
        return new ItemStack(e.icon());
    }

    /** Why this option cannot be picked just now ("" when it can). */
    private String whyNot(Entry e) {
        if (ctx.shunned() && e.topic() != TalkTopic.GIFT && e.topic() != TalkTopic.FINE
                && (e.kind() == Kind.ASK || e.kind() == Kind.SAY || e.kind() == Kind.PLACES)) return t("ui.why.shunned");
        ItemStack held = held();
        if (e.topic() == TalkTopic.GIFT && e.kind() == Kind.ASK && held.isEmpty()) return t("ui.why.gift");
        if (e.topic() == TalkTopic.DELIVER && !ctx.canDeliver()) return t("ui.why.deliver");
        if (e.topic() == TalkTopic.MEAL && held.get(DataComponents.FOOD) == null) return t("ui.why.meal");
        if (e.topic() == TalkTopic.REPAIR && !(held.isDamageableItem() && held.isDamaged())) return t("ui.why.repair");
        if (e.topic() == TalkTopic.TEACH && held.isEmpty()) return t("ui.why.teach");
        return "";
    }

    private ItemStack held() {
        return minecraft == null || minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
    }

    /** [quests] The quest buttons the folk sent with its last answer (QuestClient): "label|TOPIC|text|tip" a line. */
    private List<Entry> questEntries() {
        String raw = QuestClient.choicesFor(last.entityId());
        if (raw.isEmpty()) return List.of();
        List<Entry> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 4) continue;
            try {
                TalkTopic topic = TalkTopic.valueOf(f[1]);
                out.add(new Entry("quest", net.minecraft.world.item.Items.WRITABLE_BOOK, topic == TalkTopic.SAY ? Kind.SAY : Kind.ASK,
                    topic, f[2], c -> true, List.of(), f[0], f[3]));
            } catch (IllegalArgumentException ignored) {
                // a topic from a newer server
            }
        }
        return out;
    }

    /** The places it can walk you to ("key=Label;…"), as options. */
    private List<Entry> placeEntries() {
        List<Entry> out = new ArrayList<>();
        if (last.places().isEmpty()) return out;
        String tip = t("ui.place_tip");
        for (String pair : last.places().split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.add(new Entry("place", net.minecraft.world.item.Items.COMPASS, Kind.ASK, TalkTopic.GUIDE, pair.substring(0, eq),
                c -> true, List.of(), pair.substring(eq + 1), tip));
        }
        return out;
    }

    /** What it asked of you, wrapped for the top of the conversation. */
    private void layoutRequest() {
        requestLines = last.errand().isEmpty() ? List.of()
            : TextCache.splitPlain(font, last.errand(), Math.max(20, convW - 2 * S2 - 10));
        if (requestLines.size() > 2) requestLines = requestLines.subList(0, 2);
    }

    /** The conversation as bubbles, laid out for a column {@code width} wide. */
    private void layoutBubbles(int width) {
        bubbles.clear();
        int max = Math.max(30, Math.round(width * BUBBLE_SHARE)) - 2 * BUBBLE_PAD;
        int y = 0;
        Boolean prev = null;
        for (Line l : HISTORY.getOrDefault(last.entityId(), List.of())) {
            List<FormattedCharSequence> lines = TextCache.splitPlain(font, l.text(), max);
            int w = 0;
            for (FormattedCharSequence s : lines) w = Math.max(w, font.width(s));
            if (prev != null) y += prev == l.you() ? BUBBLE_GAP : BUBBLE_TURN_GAP;
            int h = lines.size() * LINE + 2 * BUBBLE_PAD - 1;
            bubbles.add(new Bubble(l.you(), lines, w + 2 * BUBBLE_PAD, h, y));
            y += h;
            prev = l.you();
        }
        bubblesHeight = y;
        bubblesVersion = historyVersion;
        bubblesWidth = width;
    }

    // ------------------------------------------------------------------ doing

    private void setCategory(Category c) {
        if (c == category && group == null && !places && page == Page.TALK) return;
        category = lastCategory = c;
        group = null;
        places = false;
        page = Page.TALK;
        focus = -1;
        optScroll.reset();
        listAt = Util.getMillis();
        layoutRows();
    }

    /** Up a level: off a page, out of a group or the places; false at the top (the caller then closes). */
    private boolean up() {
        if (page != Page.TALK) {
            page = Page.TALK;
        } else if (group != null || places) {
            group = null;
            places = false;
            focus = -1;
            optScroll.reset();
            listAt = Util.getMillis();
            layoutRows();
        } else {
            return false;
        }
        UiStyle.click();
        return true;
    }

    private void activate(Row r) {
        if (r.type() == RowType.BACK) {
            up();
            return;
        }
        if (r.type() != RowType.ITEM) return;
        Entry e = r.entry();
        if (!r.enabled()) {
            notice = r.why();
            noticeAt = Util.getMillis();
            return;
        }
        UiStyle.click();
        flashEntry = e;
        flashFull = r.full();
        flashAt = Util.getMillis();
        switch (e.kind()) {
            case GROUP -> {
                group = e;
                places = false;
                focus = -1;
                optScroll.reset();
                listAt = Util.getMillis();
                layoutRows();
            }
            case PLACES -> {
                places = true;
                group = null;
                focus = -1;
                optScroll.reset();
                listAt = Util.getMillis();
                layoutRows();
            }
            case PROMPT -> {
                say.setValue(e.text());
                setFocused(say);
                say.setFocused(true);
                say.moveCursorToEnd(false);
            }
            case PAGE -> page = e == TalkMenu.SKILLS ? (page == Page.SKILLS ? Page.TALK : Page.SKILLS)
                : (page == Page.PROFILE ? Page.TALK : Page.PROFILE);
            case RECORD -> openWork();
            case PACK -> openPack();
            case BOARD -> PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.VillageAskPayload(1));
            default -> {
                if (e.topic() == TalkTopic.GUIDE) {
                    places = false;
                    layoutRows();
                }
                send(e.topic(), e.text());
            }
        }
    }

    private void send(TalkTopic topic, String text) {
        waitingSince = Util.getMillis();
        PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), topic.ordinal(), text));
    }

    private void sayTyped() {
        String text = say.getValue().trim();
        if (text.isEmpty()) return;
        UiStyle.click();
        say.setValue("");
        draft = "";
        send(TalkTopic.SAY, text);
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

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (links.click(mx, my, button)) return true;                    // [teleport] its name, or its button
        if (button != 0) return super.mouseClicked(mx, my, button);
        // The header's links.
        for (int i = 0; i < TalkMenu.LINKS.size(); i++) {
            int lx = linkX + i * (linkSize + 2), ly = linkY();
            if (in(mx, my, lx, ly, linkSize, linkSize)) {
                activate(new Row(RowType.ITEM, TalkMenu.LINKS.get(i), "", "", "", true, "", false, 0, 0, 0, ItemStack.EMPTY));
                return true;
            }
        }
        // The tabs.
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            if (in(mx, my, tabX, bodyY + i * tabH, tabW, tabH - 1)) {
                if (cats[i] != category || group != null || places || page != Page.TALK) UiStyle.click();
                setCategory(cats[i]);
                return true;
            }
        }
        // The footer's buttons.
        if (in(mx, my, sayX(), footY, buttonW, BOX_H)) { sayTyped(); return true; }
        if (in(mx, my, byeX(), footY, buttonW, BOX_H)) { UiStyle.click(); onClose(); return true; }
        // A page's back bar.
        if (page != Page.TALK && in(mx, my, optX, bodyY, px + pw - S2 - optX, ROW)) { up(); return true; }
        // The options.
        if (page == Page.TALK) {
            Row r = rowAt(mx, my);
            if (r != null) {
                if (say.isFocused() && say.getValue().isBlank()) say.setFocused(false);
                activate(r);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private Row rowAt(double mx, double my) {
        if (mx < optX || mx >= optX + optW - 5 || my < bodyY || my >= bodyB) return null;
        int cy = (int) my - bodyY + optScroll.offset();
        for (Row r : rows) if (r.focusable() && cy >= r.y() && cy < r.y() + r.h()) return r;
        return null;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = say != null && say.isFocused();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (typing && !say.getValue().isEmpty()) { say.setFocused(false); setFocused(null); return true; }
            if (up()) return true;
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            Category[] cats = Category.values();
            int step = (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? cats.length - 1 : 1;
            UiStyle.click();
            setCategory(cats[(category.ordinal() + step) % cats.length]);
            return true;
        }
        if (typing) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { sayTyped(); return true; }
            return super.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_LEFT) return up() || true;
        if (page == Page.TALK) {
            int n = key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9 ? key - GLFW.GLFW_KEY_0
                : key >= GLFW.GLFW_KEY_KP_1 && key <= GLFW.GLFW_KEY_KP_9 ? key - GLFW.GLFW_KEY_KP_0 : 0;
            if (n > 0) {
                for (Row r : rows) if (r.type() == RowType.ITEM && r.key() == n) { activate(r); return true; }
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) { moveFocus(key == GLFW.GLFW_KEY_DOWN ? 1 : -1); return true; }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE
                    || key == GLFW.GLFW_KEY_RIGHT) && focus >= 0) {
                activate(rows.get(focus));
                return true;
            }
        }
        boolean chat = minecraft != null && minecraft.options.keyChat.matches(key, scan);
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || chat) {
            setFocused(say);
            say.setFocused(true);
            swallowChar = chat;                // the T that brought the box up is not the first letter in it
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        return say != null && say.isFocused() && super.charTyped(c, mods);
    }

    private boolean swallowChar;

    private void moveFocus(int dir) {
        int n = rows.size();
        if (n == 0) return;
        int i = focus;
        for (int k = 0; k < n; k++) {
            i = i < 0 ? (dir > 0 ? 0 : n - 1) : Math.floorMod(i + dir, n);
            if (rows.get(i).focusable()) {
                focus = i;
                Row r = rows.get(i);
                optScroll.reveal(r.y(), r.h(), bodyB - bodyY);
                return;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (page != Page.TALK && mx >= optX) pageScroll.wheel(scrollY, WHEEL_STEP);
        else if (mx >= convX) talkScroll.wheel(-scrollY, WHEEL_STEP);       // up the wheel is back in time
        else if (mx >= optX) optScroll.wheel(scrollY, WHEEL_STEP);
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
        String now = held.isEmpty() ? "" : held.getHoverName().getString();
        if (!now.equals(heldKey) || held.isDamaged() != heldDamaged) {   // what you hold changes "Give …" and what can be done
            heldDamaged = held.isDamaged();
            layoutRows();
        }
    }

    private boolean heldDamaged;

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ painting

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        float open = easeOut(progress(openedAt, OPEN_MS));
        g.pose().pushPose();
        g.pose().translate(0, Math.round((1F - open) * OPEN_RISE), 0);
        shadow(g, px, py, pw, ph);
        panel(g, px, py, pw, ph);
        headerBand(g, px, py, pw, headerH);
        links.begin(mouseX, mouseY);                                      // [teleport]
        String hoverTip = header(g, mouseX, mouseY);
        String t = tabs(g, mouseX, mouseY);
        if (t != null) hoverTip = t;
        if (page == Page.TALK) {
            t = options(g, mouseX, mouseY);
            if (t != null) hoverTip = t;
            conversation(g);
        } else {
            t = pageBar(g, mouseX, mouseY);
            if (t != null) hoverTip = t;
            if (page == Page.PROFILE) profile(g);
            else {
                String s = skills(g, mouseX, mouseY);
                if (s != null) hoverTip = s;
            }
        }
        t = footer(g, mouseX, mouseY, partialTick);
        if (t != null) hoverTip = t;
        links.draw(g, font);                                              // [teleport] its name's button, over everything
        g.pose().popPose();
        if (hoverTip != null && !hoverTip.isEmpty()) UiStyle.tooltip(g, font, hoverTip, mouseX, mouseY, 220);
    }

    private int linkY() {
        return compact ? py + (headerH - linkSize) / 2 + 1 : py + S1 + 1;
    }

    /** The header: likeness, name, trade, doing, mood and standing; the town; the links. Returns a tooltip, if hovered. */
    private String header(GuiGraphics g, int mouseX, int mouseY) {
        String tip = null;
        int box = headerH - 2 * S1 - (compact ? 0 : 2);
        int bx = px + S1 + 2, by = py + S1;
        well(g, bx, by, box, box, PORTRAIT);
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getEntity(last.entityId()) instanceof LivingEntity folk) {
            // A bust: drawn big enough that the head and shoulders fill the frame, the rest cut off below it.
            int scale = Math.round(box * PORTRAIT_SCALE);
            int centre = by + 3 + Math.round(scale * folk.getBbHeight() / 2F);
            g.enableScissor(bx + 1, by + 1, bx + box - 1, by + box - 1);
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, bx, by + 1, bx + box, 2 * centre - by - 1,
                scale, 0.0625F, mouseX, mouseY, folk);
            g.disableScissor();
        }
        int tx = bx + box + S2;
        // The links, right.
        for (int i = 0; i < linkIcons.length; i++) {
            Entry e = TalkMenu.LINKS.get(i);
            int lx = linkX + i * (linkSize + 2), ly = linkY();
            boolean on = e == TalkMenu.PROFILE && page == Page.PROFILE || e == TalkMenu.SKILLS && page == Page.SKILLS;
            boolean hover = in(mouseX, mouseY, lx, ly, linkSize, linkSize);
            tile(g, lx, ly, linkSize, linkSize, State.of(true, on, hover));
            int o = (linkSize - ICON) / 2;
            g.renderItem(linkIcons[i], lx + o, ly + o);
            if (hover) tip = linkTips[i];
        }
        // Name, bold: the one focal line.
        int ny = py + (compact ? 5 : 7);
        title(g, font, nameShown, tx, ny);
        links.addCard(g, last.entityId(), last.name(), tx, ny, nameW, 9, TEXT);   // [teleport]
        if (compact) {
            statusRow(g, tx, py + 17);
        } else {
            g.drawString(font, aboutShown, tx, py + 19, TEXT_MUTED, false);
            g.drawString(font, doingShown, tx, py + 29, TEXT_FAINT, false);
            statusRow(g, tx, py + 40);
            // The town and your standing there, right, under the links.
            g.drawString(font, whereShown, px + pw - S2 - font.width(whereShown), py + 29, TEXT_FAINT, false);
        }
        if (tip == null && headerCut && in(mouseX, mouseY, tx, py + 4, linkX - S2 - tx, headerH - 8)) tip = headerTip;
        return tip;
    }

    /** Its mood as a face, a word and a bar; what it thinks of you as hearts, a word and a bar. */
    private void statusRow(GuiGraphics g, int x, int y) {
        int barW = statusBarW();
        g.drawString(font, moodShown, x, y, moodInk, false);
        bar(g, x + font.width(moodShown) + S1, y + 3, barW, 4, moodFrac, moodFill);
        int textW = linkX - S2 - x;
        int sx = x + textW / 2;
        g.drawString(font, standingShown, sx, y, affInk, false);
        bar(g, sx + font.width(standingShown) + S1, y + 3, barW, 4, affFrac, affFill);
    }

    /** The category tabs down the left. Returns the hovered tab's tooltip. */
    private String tabs(GuiGraphics g, int mouseX, int mouseY) {
        String tip = null;
        Category[] cats = Category.values();
        g.fill(tabX + tabW, bodyY, tabX + tabW + 1, bodyB, EDGE_SOFT);
        for (int i = 0; i < cats.length; i++) {
            int y = bodyY + i * tabH;
            boolean hover = in(mouseX, mouseY, tabX, y, tabW, tabH - 1);
            boolean on = cats[i] == category && page == Page.TALK;
            sideTab(g, tabX, y, tabW, tabH - 1, State.of(true, on, hover));
            int iy = y + (tabH - 1 - ICON) / 2;
            if (compact) {
                g.renderItem(tabIcons[i], tabX + (tabW - ICON) / 2 + 1, iy);
            } else {
                g.renderItem(tabIcons[i], tabX + S1 + 1, iy);
                g.drawString(font, tabLabels[i], tabX + S1 + ICON + 4, y + (tabH - 1 - GLYPH) / 2 + 1, on ? ACCENT : TEXT, false);
            }
            if (hover) tip = tabTips[i];
        }
        return tip;
    }

    /** The options column. Returns the hovered option's tooltip (its whole label when cut, what it does, why not). */
    private String options(GuiGraphics g, int mouseX, int mouseY) {
        String tip = null;
        int top = bodyY, room = bodyB - bodyY;
        float slide = 1F - easeOut(progress(listAt, LIST_MS));
        int dx = Math.round(slide * LIST_SLIDE);
        g.enableScissor(optX, top, optX + optW, top + room);
        Row hovered = page == Page.TALK ? rowAt(mouseX, mouseY) : null;
        long now = Util.getMillis();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = top + r.y() - optScroll.offset();
            if (y + r.h() < top || y > top + room) continue;
            int x = optX + dx, w = optW - 5;
            switch (r.type()) {
                case HEAD -> heading(g, font, r.shown(), x + 1, y + 2, w - 2);
                case NOTE -> {
                    int ly = y + 2;
                    for (FormattedCharSequence l : TextCache.splitPlain(font, r.shown(), w - S2)) {
                        g.drawString(font, l, x + S1, ly, TEXT_FAINT, false);
                        ly += LINE;
                    }
                }
                case BACK -> {
                    boolean hover = r == hovered || focus == i;
                    tile(g, x, y, w, r.h(), State.of(true, false, hover));
                    g.drawString(font, r.shown(), x + S1 + 1, y + 5, TEXT, false);
                    if (!compact) keyCap(g, font, "Esc", x + w - font.width("Esc") - 4 - S1, y + 4);
                    if (hover) tip = r.tip();
                }
                case ITEM -> {
                    boolean hover = r == hovered || focus == i;
                    boolean flash = r.entry() == flashEntry && r.full().equals(flashFull) && now - flashAt < FLASH_MS;
                    State s = State.of(r.enabled(), flash, hover);
                    if (r.suggested() && s != State.DISABLED && s != State.SELECTED) {
                        g.fill(x, y, x + w, y + r.h(), EDGE_SOFT);
                        g.fill(x + 1, y + 1, x + w - 1, y + r.h() - 1, hover ? HIGHLIGHT_HOVER : HIGHLIGHT);
                        g.fill(x + 1, y + 1, x + 3, y + r.h() - 1, HIGHLIGHT_SPINE);
                        if (hover) g.renderOutline(x, y, w, r.h(), EDGE);
                    } else {
                        tile(g, x, y, w, r.h(), s);
                    }
                    g.renderItem(r.icon(), x + 3, y + 1);
                    if (!r.enabled()) {                                   // the icon greyed too: over it, so above its depth
                        g.pose().pushPose();
                        g.pose().translate(0, 0, ICON_DEPTH);
                        g.fill(x + 3, y + 1, x + 3 + ICON, y + 1 + ICON, GREY_OUT);
                        g.pose().popPose();
                    }
                    g.drawString(font, r.shown(), x + ICON + 7, y + 5, s.ink(), false);
                    int rx = x + w - S1;
                    if (r.entry().isGroup()) {
                        rx -= font.width("›");
                        g.drawString(font, "›", rx, y + 5, TEXT_FAINT, false);
                        rx -= 3;
                    }
                    if (r.key() > 0 && !compact) keyCap(g, font, KEYS[r.key()], rx - font.width(KEYS[r.key()]) - 4, y + 4);
                    if (hover) {
                        StringBuilder sb = new StringBuilder();
                        if (!r.shown().equals(r.full()) || r.tip().isEmpty()) sb.append(r.full());
                        if (!r.tip().isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append(r.tip());
                        if (!r.enabled()) sb.append("\n").append(I18n.get("talk.mc_assistant.ui.unavailable", r.why()));
                        tip = sb.toString();
                    }
                }
            }
        }
        g.disableScissor();
        scrollBar(g, optX + optW - 3, top, room, optScroll.offset(), optScroll.max(), room);
        return tip;
    }

    /** The conversation: its request pinned at the top, then the bubbles, the latest at the foot. */
    private void conversation(GuiGraphics g) {
        int x = convX, y0 = bodyY, h = bodyB - bodyY;
        well(g, x, y0, convW, h);
        int top = y0 + 2;
        // What it asked of you, pinned.
        if (!requestLines.isEmpty()) {
            int rh = requestLines.size() * LINE + 6;
            g.fill(x + 2, top, x + convW - 2, top + rh, HIGHLIGHT);
            g.fill(x + 2, top, x + 4, top + rh, HIGHLIGHT_SPINE);
            g.fill(x + 2, top + rh, x + convW - 2, top + rh + 1, EDGE_SOFT);
            g.drawString(font, "★", x + 7, top + 3, WARNING, false);
            int ly = top + 3;
            for (FormattedCharSequence l : requestLines) {
                g.drawString(font, l, x + 17, ly, TEXT, false);
                ly += LINE;
            }
            top += rh + 2;
        }
        // Why an option could not be picked, a moment.
        if (!notice.isEmpty() && Util.getMillis() - noticeAt < NOTICE_MS) {
            List<FormattedCharSequence> nl = TextCache.splitPlain(font, notice, convW - 16);
            int nh = Math.min(2, nl.size()) * LINE + 6;
            g.fill(x + 2, top, x + convW - 2, top + nh, DANGER_SOFT);
            g.fill(x + 2, top, x + 4, top + nh, DANGER_FILL);
            for (int i = 0; i < Math.min(2, nl.size()); i++) g.drawString(font, nl.get(i), x + 8, top + 3 + i * LINE, DANGER, false);
            top += nh + 2;
        }
        int bottom = y0 + h - 3;
        int inner = convW - 2 * S1 - 4;
        if (bubblesVersion != historyVersion || bubblesWidth != inner) layoutBubbles(inner);
        boolean waiting = waitingSince > 0 && Util.getMillis() - waitingSince < WAIT_MS;
        int waitH = waiting ? LINE + 2 * BUBBLE_PAD + BUBBLE_TURN_GAP : 0;
        int view = Math.max(1, bottom - top - S1);
        talkScroll.fit(bubblesHeight + waitH, view);
        if (bubbles.isEmpty() && !waiting) {
            int ty = top + (bottom - top) / 2 - LINE;
            for (FormattedCharSequence l : TextCache.splitPlain(font, sEmpty, inner - S2)) {
                g.drawString(font, l, x + (convW - font.width(l)) / 2, ty, TEXT_MUTED, false);
                ty += LINE;
            }
            return;
        }
        int contentTop = bottom - Math.max(view, bubblesHeight + waitH) + talkScroll.offset();
        if (bubblesHeight + waitH < view) contentTop = bottom - bubblesHeight - waitH;
        g.enableScissor(x + 1, top, x + convW - 1, bottom);
        float rise = 1F - easeOut(progress(bubbleAt, BUBBLE_MS));
        int lastIndex = bubbles.size() - 1;
        for (int i = 0; i <= lastIndex; i++) {
            Bubble b = bubbles.get(i);
            int by = contentTop + b.y() + (i == lastIndex ? Math.round(rise * BUBBLE_RISE) : 0);
            if (by + b.h() < top || by > bottom) continue;
            boolean latest = i >= lastIndex - 1;
            int bx = b.you() ? x + convW - S1 - 2 - b.w() : x + S1 + 2;
            bubble(g, bx, by, b.w(), b.h(), b.you());
            int ink = latest || b.you() ? TEXT : TEXT_MUTED;
            int ly = by + BUBBLE_PAD;
            for (FormattedCharSequence l : b.lines()) {
                g.drawString(font, l, bx + BUBBLE_PAD, ly, ink, false);
                ly += LINE;
            }
        }
        if (waiting) {
            int by = contentTop + bubblesHeight + BUBBLE_TURN_GAP;
            int dots = (int) ((Util.getMillis() / DOT_MS) % DOTS.length);
            int bw = font.width(DOTS[DOTS.length - 1]) + 2 * BUBBLE_PAD;
            bubble(g, x + S1 + 2, by, bw, LINE + 2 * BUBBLE_PAD - 1, false);
            g.drawString(font, DOTS[dots], x + S1 + 2 + BUBBLE_PAD, by + BUBBLE_PAD, TEXT_FAINT, false);
        }
        g.disableScissor();
        // The scroll bar runs the other way: its foot is the newest.
        scrollBar(g, x + convW - 5, top, bottom - top, talkScroll.max() - talkScroll.offset(), talkScroll.max(), view);
    }

    /** A speech bubble: a pale body with a soft edge and a little tail at its speaker's side. */
    private void bubble(GuiGraphics g, int x, int y, int w, int h, boolean you) {
        int fill = you ? BUBBLE_YOU : BUBBLE_THEM;
        g.fill(x + 1, y, x + w - 1, y + h, EDGE_SOFT);
        g.fill(x, y + 1, x + w, y + h - 1, EDGE_SOFT);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        int tx = you ? x + w - 4 : x + 2;
        g.fill(tx, y + h - 1, tx + 2, y + h + 2, EDGE_SOFT);
        g.fill(tx + (you ? 0 : 0), y + h - 1, tx + 2, y + h + 1, fill);
    }

    /** A page's bar along its top: Back, and the page's name. Returns its tooltip when hovered. */
    private String pageBar(GuiGraphics g, int mouseX, int mouseY) {
        int x = optX, w = px + pw - S2 - optX;
        if (pageBarFor != page) {
            pageBarFor = page;
            String name = page == Page.PROFILE ? label(TalkMenu.PROFILE) : label(TalkMenu.SKILLS);
            sPageBar = UiStyle.clip(font, "‹ " + t("ui.back") + "  ·  " + name, w - 40);
        }
        boolean hover = in(mouseX, mouseY, x, bodyY, w, ROW);
        tile(g, x, bodyY, w, ROW, State.of(true, false, hover));
        g.drawString(font, sPageBar, x + S1 + 1, bodyY + 5, TEXT, false);
        if (!compact) keyCap(g, font, "Esc", x + w - font.width("Esc") - 4 - S1, bodyY + 4);
        return hover ? sBackTip : null;
    }

    private int pageTop() { return bodyY + ROW + S1; }

    /** The profile card: one fact a line ("Label|what"), the label on the left, the fact wrapped beside it. */
    private void profile(GuiGraphics g) {
        int x = optX, w = px + pw - S2 - optX, top = pageTop(), bottom = bodyB;
        well(g, x, top, w, bottom - top, PAGE);
        int labelW = compact ? 56 : 72;
        if (!last.card().equals(profileFor) || profileWidth != w) layoutProfile(w - labelW - 2 * S2 - 6, labelW - S1);
        if (profileLines.isEmpty()) {
            g.drawString(font, sProfileEmpty, x + S2, top + S2, TEXT_FAINT, false);
            return;
        }
        pageScroll.fit(profileHeight + S2, bottom - top - 4);
        g.enableScissor(x + 1, top + 1, x + w - 1, bottom - 1);
        int y = top + S1 + 1 - pageScroll.offset();
        for (int i = 0; i < profileLines.size(); i++) {
            FormattedCharSequence line = profileLines.get(i);
            if (line == null) {
                if (y > top && y < bottom) g.fill(x + S2, y + 1, x + w - S2, y + 2, RULE);
                y += 4;
                continue;
            }
            if (y > top - LINE && y < bottom) {
                String label = profileLabels.get(i);
                if (!label.isEmpty()) g.drawString(font, label, x + S2, y, ACCENT, false);
                g.drawString(font, line, x + S2 + labelW, y, TEXT, false);
            }
            y += LINE;
        }
        g.disableScissor();
        scrollBar(g, x + w - 5, top + 2, bottom - top - 4, pageScroll.offset(), pageScroll.max(), bottom - top);
    }

    /** The profile card's lines, wrapped to {@code width} beside labels cut to {@code labelW}. */
    private void layoutProfile(int width, int labelW) {
        profileLabels.clear();
        profileLines.clear();
        profileHeight = 0;
        for (String l : last.card().split("\n")) {
            int bar = l.indexOf('|');
            if (bar <= 0) continue;
            List<FormattedCharSequence> wrapped = TextCache.splitPlain(font, l.substring(bar + 1), Math.max(20, width));
            for (int i = 0; i < wrapped.size(); i++) {
                profileLabels.add(i == 0 ? UiStyle.clip(font, l.substring(0, bar), labelW) : "");
                profileLines.add(wrapped.get(i));
                profileHeight += LINE;
            }
            profileLabels.add("");
            profileLines.add(null);
            profileHeight += 4;
        }
        profileFor = last.card();
        profileWidth = px + pw - S2 - optX;
    }

    /** The box to say anything, and its Say and Bye buttons. */
    private String footer(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        say.render(g, mouseX, mouseY, partialTick);
        boolean canSay = !say.getValue().isBlank();
        boolean hs = in(mouseX, mouseY, sayX(), footY, buttonW, BOX_H), hb = in(mouseX, mouseY, byeX(), footY, buttonW, BOX_H);
        button(g, font, sSay, sayX(), footY, buttonW, BOX_H, State.of(canSay, canSay && !hs, hs));
        button(g, font, sBye, byeX(), footY, buttonW, BOX_H, State.of(true, false, hb));
        if (hs) return canSay ? sSayTip : sSayEmpty;
        if (hb) return sByeTip;
        return null;
    }

    private int sayX() { return px + pw - S2 - 2 * buttonW - S1; }

    private int byeX() { return px + pw - S2 - buttonW; }

    // ------------------------------------------------------------------ the Skills page

    /** The knacks' colours, by family (a trade's amber, a nature's green, a purse's blue), and the greys of what is open. */
    private static final int TRADE_INK = 0xFF7A4A00, NATURE_INK = 0xFF2A6420, PURSE_INK = 0xFF1D4A86,
        OPEN_EDGE = 0xFF9A9A9A, OPEN_NAME = 0xFF333333, OPEN_DIM = 0xFF5A5A5A, OPEN_TEXT = 0xFF505050,
        TRACK = 0xFFA8A8A8, FILL = 0xFFC08A1E, PIP = 0xFFC08A1E, NODE_CHOSEN = 0xFFF1E6CB, NODE_OPEN = 0xFFDADADA;

    /** What the folk sent for its Skills page (FolkSkills.encode), read once a reply. */
    private record Points(int earned, int spent, int free, int nextAt, int pct, String best, int bestLevel, boolean child) {}
    private record TradeRow(String title, int level, boolean now) {}
    private record KnackRow(String key, String title, String family, String effect, String why, String day, boolean active) {}
    private record OpenRow(String key, String title, String family, String effect, int fit) {}
    private record SkillsPage(Points points, List<TradeRow> trades, List<KnackRow> chosen, List<OpenRow> open, int[] nest) {}

    private String skillsRead;
    private SkillsPage skillsPage;

    private SkillsPage skillsPage() {
        String raw = last.skills();
        if (raw == null || raw.isEmpty()) return null;
        if (raw.equals(skillsRead)) return skillsPage;
        Points points = null;
        List<TradeRow> trades = new ArrayList<>();
        List<KnackRow> chosen = new ArrayList<>();
        List<OpenRow> open = new ArrayList<>();
        int[] nest = null;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            try {
                switch (f[0]) {
                    case "P" -> points = new Points(num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5]), f[6], num(f[7]), "1".equals(f[8]));
                    case "T" -> trades.add(new TradeRow(f[1], num(f[2]), "1".equals(f[3])));
                    case "K" -> chosen.add(new KnackRow(f[1], f[2], f[3], f[4], f[5], f[6], "1".equals(f[7])));
                    case "O" -> open.add(new OpenRow(f[1], f[2], f[3], f[4], num(f[5])));
                    case "N" -> nest = new int[]{ num(f[1]), num(f[2]) };
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // a line from a newer or older folk than this screen knows: left out
            }
        }
        skillsRead = raw;
        skillsPage = points == null ? null : new SkillsPage(points, trades, chosen, open, nest);
        return skillsPage;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int familyInk(String family) {
        return switch (family) {
            case "NATURE" -> NATURE_INK;
            case "PURSE" -> PURSE_INK;
            default -> TRADE_INK;
        };
    }

    private static String familyName(String family) {
        return switch (family) {
            case "NATURE" -> "Nature";
            case "PURSE" -> "Purse";
            default -> "Trade";
        };
    }

    /**
     * The folk's knacks, drawn: along the top its knack points (a pip each: filled for one spent, a ring for one to
     * spend, grey for one still to earn) and a bar to its next point; its trades as rulers marked at every fifth level,
     * where the points come; a card for each knack it chose (what it does, why it chose it, the day, and whether it
     * works in the trade it has now); and its tree, three branches (its trade, its nature, its purse) with what it chose
     * lit and what is still open to it greyed, the mouse over any of them for the whole of it. Scrolls with the wheel.
     * Returns the tooltip of what the mouse is over.
     */
    private String skills(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = optX, x1 = px + pw - S2, top0 = pageTop(), bottom = bodyB;
        well(g, x0, top0, x1 - x0, bottom - top0, PAGE);
        SkillsPage page = skillsPage();
        if (page == null) {
            g.drawString(font, sSkillsEmpty, x0 + S2, top0 + S2, TEXT_FAINT, false);
            return null;
        }
        Points pt = page.points();
        int cx = x0 + S2, inner = x1 - x0 - 2 * S2 - 4;
        boolean hovering = mouseX >= x0 && mouseX < x1 && mouseY >= top0 && mouseY < bottom;
        String tip = null;
        g.enableScissor(x0 + 1, top0 + 1, x1 - 1, bottom - 1);
        int y = top0 + 6 - pageScroll.offset();

        // ---- the points: six pips, and what they come to
        g.drawString(font, "Knack points", cx, y, TEXT, false);
        int ppx = cx + font.width("Knack points") + 6;
        for (int i = 0; i < 6; i++) {
            int bx = ppx + i * 10;
            if (i < pt.spent()) {
                g.fill(bx, y, bx + 7, y + 7, PIP);
            } else if (i < pt.earned()) {
                g.fill(bx, y, bx + 7, y + 7, PIP);
                g.fill(bx + 1, y + 1, bx + 6, y + 6, PAGE);
            } else {
                g.fill(bx, y, bx + 7, y + 7, EDGE_SOFT);
                g.fill(bx + 1, y + 1, bx + 6, y + 6, 0xFFB8B8B8);
            }
        }
        String tally = pt.child() ? "none yet: a child" : pt.earned() + " earned · " + pt.spent() + " chosen · " + pt.free() + " to choose";
        g.drawString(font, UiStyle.clip(font, tally, Math.max(20, x1 - 6 - (ppx + 64))), ppx + 64, y, pt.free() > 0 ? TEXT : TEXT_MUTED, false);
        y += 12;

        // ---- how near the next point is
        if (pt.child()) {
            y = wrapped(g, "Its knacks come with a trade: a point at every fifth level of the trade it is best at, six in all.",
                cx, y, inner, TEXT_MUTED);
        } else if (pt.nextAt() <= 0) {
            g.drawString(font, UiStyle.clip(font, "All six points earned" + (pt.best().isEmpty() ? "" : " — " + pt.best().toLowerCase(Locale.ROOT)
                + " " + pt.bestLevel()), inner), cx, y, TEXT_MUTED, false);
            y += LINE + 2;
        } else {
            String label = (pt.best().isEmpty() ? "No trade" : pt.best()) + " " + pt.bestLevel();
            String after = "next point at level " + pt.nextAt();
            int lw = font.width(label) + 6, aw = font.width(after) + 6;
            int bx = cx + lw, bw = Math.max(20, inner - lw - aw);
            g.drawString(font, label, cx, y, TEXT, false);
            bar(g, bx, y + 2, bw, 5, Math.max(0, Math.min(100, pt.pct())) / 100F, FILL);
            g.drawString(font, after, bx + bw + 6, y, TEXT_MUTED, false);
            if (hovering && mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + 9) {
                tip = pt.pct() + "% of the way from level " + (pt.nextAt() - 5) + " to level " + pt.nextAt()
                    + ", by its experience at its best trade.";
            }
            y += LINE + 2;
        }

        // ---- its trades, each a ruler to level thirty marked where the points come
        if (!page.trades().isEmpty()) {
            int nameW = 0;
            for (TradeRow tr : page.trades()) nameW = Math.max(nameW, font.width(tr.title()));
            nameW = Math.min(nameW, inner / 3) + 6;
            int tx = cx + nameW, tw = Math.max(30, inner - nameW - 30);
            for (TradeRow tr : page.trades()) {
                g.drawString(font, UiStyle.clip(font, tr.title(), nameW - 6), cx, y, tr.now() ? TRADE_INK : TEXT, false);
                g.fill(tx, y + 3, tx + tw, y + 6, TRACK);
                int lv = Math.min(30, tr.level());
                g.fill(tx, y + 3, tx + tw * lv / 30, y + 6, tr.now() ? FILL : 0xFFB59A5A);
                for (int n = 5; n <= 30; n += 5) {
                    int nx = tx + tw * n / 30 - 1;
                    g.fill(nx, y + 1, nx + 1, y + 8, tr.level() >= n ? TRADE_INK : EDGE_SOFT);
                }
                g.drawString(font, "lv " + tr.level(), tx + tw + 5, y, tr.now() ? TEXT : TEXT_MUTED, false);
                y += LINE;
            }
            y += 4;
        }

        // ---- what it chose: a card each
        y = skillHeading(g, "Chosen", cx, y, inner);
        if (page.chosen().isEmpty()) {
            y = wrapped(g, pt.child() ? "Nothing yet." : pt.free() > 0
                ? "Nothing yet. It has a point to spend, and chooses for itself at a quiet moment: its break, or the evening."
                : "Nothing yet. Its first point comes at level 5 of its best trade.", cx, y, inner, TEXT_MUTED);
        }
        for (KnackRow k : page.chosen()) {
            List<FormattedCharSequence> effect = TextCache.splitPlain(font, k.effect(), inner - 12);
            List<FormattedCharSequence> why = TextCache.splitPlain(font, "Why: " + k.why(), inner - 12);
            int h = 4 + LINE + effect.size() * LINE + why.size() * LINE + (k.active() ? 0 : LINE) + 3;
            int ink = familyInk(k.family());
            g.fill(cx, y, cx + inner, y + h, CARD);
            g.fill(cx, y, cx + 2, y + h, ink);
            int ty = y + 4;
            String when = familyName(k.family()) + " · day " + k.day();
            g.drawString(font, UiStyle.clip(font, k.title(), inner - font.width(when) - 16), cx + 7, ty, TEXT, false);
            g.drawString(font, when, cx + inner - 4 - font.width(when), ty, ink, false);
            ty += LINE;
            for (FormattedCharSequence l : effect) { g.drawString(font, l, cx + 7, ty, TEXT, false); ty += LINE; }
            for (FormattedCharSequence l : why) { g.drawString(font, l, cx + 7, ty, TEXT_MUTED, false); ty += LINE; }
            if (!k.active()) g.drawString(font, "Resting: it works another trade now", cx + 7, ty, WARNING, false);
            y += h + 4;
        }
        if (page.nest() != null) {
            y = wrapped(g, "Nest Egg: " + page.nest()[0] + " of " + page.nest()[1]
                + " coins paid so far; the treasury pays the rest as it can spare it.", cx, y, inner, PURSE_INK);
        }
        y += 2;

        // ---- the tree: three branches, what it chose lit, what is open to it in grey
        y = skillHeading(g, "Its knacks", cx, y, inner);
        String[] families = { "TRADE", "NATURE", "PURSE" };
        int gap = 6, cw = (inner - 2 * gap) / 3;
        int rootX = cx + inner / 2;
        g.fill(rootX, y, rootX + 1, y + 4, EDGE_SOFT);
        g.fill(cx + cw / 2, y + 4, cx + 2 * (cw + gap) + cw / 2 + 1, y + 5, EDGE_SOFT);
        y += 5;
        int colTop = y, deepest = y;
        for (int c = 0; c < 3; c++) {
            String fam = families[c];
            int colX = cx + c * (cw + gap), ink = familyInk(fam);
            int yy = colTop;
            g.fill(colX + cw / 2, yy, colX + cw / 2 + 1, yy + 3, EDGE_SOFT);
            yy += 3;
            String head = UiStyle.clip(font, familyName(fam), cw - 4);
            g.fill(colX, yy, colX + cw, yy + 12, PANEL_HEADER);
            g.fill(colX, yy + 11, colX + cw, yy + 12, ink);
            g.drawString(font, head, colX + (cw - font.width(head)) / 2, yy + 2, ink, false);
            yy += 15;
            int stem = colX + 3, stemTop = yy;
            List<Object[]> nodes = new ArrayList<>();       // {title, effect, chosen?, row}
            for (KnackRow k : page.chosen()) if (k.family().equals(fam)) nodes.add(new Object[]{ k.title(), k.effect(), true, k });
            for (OpenRow o : page.open()) if (o.family().equals(fam)) nodes.add(new Object[]{ o.title(), o.effect(), false, o });
            if (nodes.isEmpty()) {
                g.drawString(font, UiStyle.clip(font, "nothing open", cw - 10), colX + 9, yy, OPEN_DIM, false);
                yy += LINE + 2;
            }
            int lastMid = yy;
            for (Object[] n : nodes) {
                boolean chosen = (Boolean) n[2];
                String title = (String) n[0];
                List<FormattedCharSequence> body = chosen
                    ? TextCache.splitPlain(font, "✓ day " + ((KnackRow) n[3]).day(), Math.max(10, cw - 14))
                    : TextCache.splitPlain(font, (String) n[1], Math.max(10, cw - 14));
                int nh = 3 + LINE + body.size() * 9 + 2;
                int nx = colX + 9;
                lastMid = yy + 6;
                g.fill(stem, lastMid, nx, lastMid + 1, EDGE_SOFT);           // the twig to the stem
                g.fill(nx, yy, colX + cw, yy + nh, chosen ? FILL : OPEN_EDGE);
                g.fill(nx + 1, yy + 1, colX + cw - 1, yy + nh - 1, chosen ? NODE_CHOSEN : NODE_OPEN);
                int fit = chosen ? 2 : ((OpenRow) n[3]).fit();
                String name = UiStyle.clip(font, title, cw - 16);
                g.drawString(font, name, nx + 3, yy + 3, chosen ? TEXT : fit >= 1 ? OPEN_NAME : OPEN_DIM, false);
                if (!chosen && fit >= 2) g.drawString(font, "•", colX + cw - 7, yy + 3, ink, false);   // it leans this way
                int by = yy + 3 + LINE;
                for (FormattedCharSequence l : body) { g.drawString(font, l, nx + 3, by, chosen ? TEXT_MUTED : OPEN_TEXT, false); by += 9; }
                if (hovering && mouseX >= nx && mouseX < colX + cw && mouseY >= yy && mouseY < yy + nh) {
                    StringBuilder sb = new StringBuilder(title).append(" — ").append((String) n[1]).append('.');
                    if (chosen) {
                        KnackRow k = (KnackRow) n[3];
                        sb.append(" Chosen on day ").append(k.day()).append(": ").append(k.why()).append('.');
                        if (!k.active()) sb.append(" Resting while it works another trade.");
                    } else {
                        sb.append(fit >= 2 ? " Open to it, and it leans this way." : fit == 1 ? " Open to it." : " Open to it, though it hardly wants it.");
                    }
                    tip = sb.toString();
                }
                yy += nh + 3;
            }
            g.fill(stem, stemTop - 3, stem + 1, lastMid + 1, EDGE_SOFT);     // the branch's stem
            deepest = Math.max(deepest, yy);
        }
        y = deepest + 4;
        y = wrapped(g, "It chooses for itself when a point is free, by its trade, its nature and what it cares about. "
            + "A trade's knacks work only in that trade; the rest go with it everywhere.", cx, y, inner, TEXT_MUTED);
        g.disableScissor();
        pageScroll.fit(y + pageScroll.offset() - top0 + 2, bottom - top0);
        scrollBar(g, x1 - 5, top0 + 2, bottom - top0 - 4, pageScroll.offset(), pageScroll.max(), bottom - top0);
        return tip;
    }

    /** A section heading with a rule after it; returns where the section starts. */
    private int skillHeading(GuiGraphics g, String text, int x, int y, int width) {
        heading(g, font, text, x, y, width);
        return y + LINE + 3;
    }

    /** Text wrapped to a width; returns the line after it. */
    private int wrapped(GuiGraphics g, String text, int x, int y, int width, int colour) {
        for (FormattedCharSequence l : TextCache.splitPlain(font, text, width)) {
            g.drawString(font, l, x, y, colour, false);
            y += LINE;
        }
        return y + 2;
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** A string of this screen's from the language file ("ui.say" is talk.mc_assistant.ui.say). */
    private static String t(String key) {
        String k = "talk.mc_assistant." + key;
        return I18n.exists(k) ? I18n.get(k) : key;
    }

    private static String face(int mood) {
        return mood >= MOOD_GOOD ? "☺" : mood >= MOOD_FAIR ? "•" : "☹";
    }

    private static String hearts(int affinity) {
        if (affinity <= -15) return "✗";
        int n = Math.max(0, Math.min(5, (affinity + 9) / 20));
        return "♥".repeat(n) + "♡".repeat(5 - n);
    }
}
