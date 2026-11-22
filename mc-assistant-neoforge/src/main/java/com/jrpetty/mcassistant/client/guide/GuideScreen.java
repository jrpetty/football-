package com.jrpetty.mcassistant.client.guide;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [guide31] The Guide: ask anything about the mod and get a clear answer, or browse its book. A full-height panel in the
 * house look (UiStyle): the header band, the ask bar (Enter asks, up and down go through what was asked before, Tab takes
 * the first suggestion, and suggestions show under it while typing), and the page in a recessed well, scrolled smoothly
 * with the wheel or the bar.
 *
 * <p>An answer reads as a reply: the question in its bubble, then the Guide's: the article's title, a line for this
 * player where there is one (GuideReply), the short answer, the numbered steps, the keys and buttons (key-caps and chips,
 * the way to them with arrows), the things involved (their items drawn), tips and warnings, "You might also mean" when two
 * were close, and Related chips to click. It types itself out quickly (a click or a key shows it all). With nothing fitting
 * it says it is not sure, offers the three nearest topics and the categories: never a blank. With nothing asked, the
 * categories as tiles, each opening its list of articles. Everything is laid out once per page and per window size; a
 * frame only draws.
 */
public class GuideScreen extends Screen {

    private static final int PAD = 8, HEAD = 26, ASK_H = 20, SUGG_H = 16, FOOT = 22, GAP = 4;
    /** How fast a reply types itself out, in characters a millisecond: quick enough never to keep a reader waiting. */
    private static final float CHARS_PER_MS = 1.4F;
    /** What was asked before, this session, newest last. */
    private static final List<String> HISTORY = new ArrayList<>();
    /** Answers the player said were (1) or were not (-1) helpful, this session (and in the local log). */
    private static final Map<String, Integer> HELPFUL = new HashMap<>();

    @Nullable private final Screen parent;
    private EditBox box;
    private int left, top, w, h;
    private int historyAt = -1;
    private String lastAsked = "";
    private List<GuideEntry> suggestions = List.of();

    /** Where the player is: the home page, a category, an article (asked, or opened). */
    private record View(String kind, String arg, String question) {}

    private final Deque<View> back = new ArrayDeque<>();
    private View view = new View("home", "", "");
    @Nullable private String pendingArticle;

    // the page, laid out
    private final List<Block> blocks = new ArrayList<>();
    private int laidWidth = -1, contentH;
    private float scroll, scrollTarget;
    private boolean dragBar;
    private long revealStart;
    private int revealTotal;
    private boolean revealing;

    // this frame's clickable places
    private final List<int[]> zones = new ArrayList<>();
    private final List<Runnable> acts = new ArrayList<>();
    private final List<String> zoneTips = new ArrayList<>();
    private String tip = "";

    public GuideScreen(@Nullable Screen parent) {
        super(Component.literal("Guide"));
        this.parent = parent;
    }

    /** Open at an article (before the screen is shown). */
    void showArticle(String id) {
        this.pendingArticle = id;
    }

    // ------------------------------------------------------------------ setting up

    @Override
    protected void init() {
        w = Math.min(540, width - 12);
        h = height - 10;
        left = (width - w) / 2;
        top = 5;
        String typed = box == null ? "" : box.getValue();
        box = new EditBox(font, left + PAD + 6, top + HEAD + 6 + 6, w - PAD * 2 - 70, 12, Component.literal("Ask"));
        box.setBordered(false);
        box.setMaxLength(160);
        box.setTextColor(0xFF1A1A1A);
        box.setValue(typed);
        box.setResponder(this::typed);
        addRenderableWidget(box);
        setInitialFocus(box);
        laidWidth = -1;
        if (pendingArticle != null) {
            String id = pendingArticle;
            pendingArticle = null;
            openArticle(id, false);
        } else if (blocks.isEmpty()) {
            go(view, false);
        }
    }

    private void typed(String text) {
        historyAt = -1;
        GuideSearch s = GuideClient.search();
        suggestions = s == null || text.isBlank() || text.equals(lastAsked) ? List.of() : s.suggest(text, 4, GuideClient.operator());
    }

    /** The server's facts came in: the page is written again for this player (shown whole). */
    void factsArrived() {
        if (view.kind().equals("answer") || view.kind().equals("article")) {
            go(view, false);
            revealing = false;
        }
    }

    // ------------------------------------------------------------------ navigation

    private void go(View v, boolean push) {
        if (push && !v.equals(view)) back.push(view);
        view = v;
        blocks.clear();
        currentId = null;
        scrollTarget = scroll = 0;
        GuideSearch s = GuideClient.search();
        if (s == null) {
            para("The Guide's book could not be read: " + GuideClient.loadError(), UiStyle.DANGER, 0);
        } else {
            switch (v.kind()) {
                case "category" -> buildCategory(s, v.arg());
                case "answer" -> buildAnswer(s, v.question());
                case "article" -> buildArticle(s, s.book().get(v.arg()), "", null);
                default -> buildHome(s);
            }
        }
        laidWidth = -1;
        boolean typeOut = v.kind().equals("answer") || v.kind().equals("article");
        revealing = typeOut;
        revealStart = Util.getMillis();
    }

    private void ask(String question) {
        String q = question.strip();
        if (q.isEmpty()) return;
        if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equalsIgnoreCase(q)) HISTORY.add(q);
        if (HISTORY.size() > 50) HISTORY.remove(0);
        historyAt = -1;
        lastAsked = q;
        suggestions = List.of();
        UiStyle.click();
        go(new View("answer", "", q), true);
    }

    private void openArticle(String id, boolean push) {
        UiStyle.click();
        go(new View("article", id, ""), push);
    }

    private void openCategory(String id) {
        UiStyle.click();
        go(new View("category", id, ""), true);
    }

    private void home() {
        UiStyle.click();
        back.clear();
        go(new View("home", "", ""), false);
    }

    private boolean goBack() {
        if (back.isEmpty()) return false;
        go(back.pop(), false);
        revealing = false;
        return true;
    }

    // ------------------------------------------------------------------ the pages

    private void buildHome(GuideSearch s) {
        PlayerFacts f = GuideClient.facts();
        String hello = f.inTown() ? "You're in " + f.town() + (f.title().isEmpty() ? "." : ", where you're " + f.title() + ".")
            : f.known() && !f.ownTown() ? "You haven't founded a town yet: start with Getting started, or ask how to found one." : "";
        add(new Block(Kind.GUIDE_HEAD, "Ask me anything about the towns and the folk, or pick a topic."));
        if (!hello.isEmpty()) para(hello, UiStyle.INFO, 0);
        add(new Block(Kind.SECTION, "Popular questions"));
        Block pop = new Block(Kind.CHIPS, "");
        for (String q : new String[]{"How do I make friends with the folk?", "How do I start a business?", "Where do I get coins?",
                "How do I found a town?", "How do I get my own house?", "Why won't my folk work?"}) {
            pop.chips.add(new Chip(q, null, () -> { box.setValue(q); ask(q); }));
        }
        add(pop);
        add(new Block(Kind.SECTION, "Browse by topic"));
        Block tiles = new Block(Kind.TILES, "");
        boolean op = GuideClient.operator();
        for (GuideBook.Category c : s.book().categories()) {
            int n = 0;
            for (GuideEntry e : s.book().in(c.id())) if (op || !e.op()) n++;
            if (n == 0) continue;
            String id = c.id();
            Tile t = new Tile(c.title(), item(c.icon()), n + (n == 1 ? " article" : " articles"), c.blurb(), () -> openCategory(id));
            tiles.tiles.add(t);
        }
        add(tiles);
        note(s.book().entries().size() + " articles. It all runs here on your computer: no chat, no network.");
    }

    private void buildCategory(GuideSearch s, String id) {
        GuideBook.Category c = s.book().category(id);
        if (c == null) { buildHome(s); return; }
        add(new Block(Kind.CRUMB, "Browse › " + c.title()));
        Block title = new Block(Kind.TITLE, c.title());
        title.icon = item(c.icon());
        add(title);
        para(c.blurb(), UiStyle.TEXT_MUTED, 0);
        Block rows = new Block(Kind.ROWS, "");
        boolean op = GuideClient.operator();
        for (GuideEntry e : s.book().in(id)) {
            if (!op && e.op()) continue;
            String eid = e.id();
            rows.rows.add(new Row(e.title() + (e.op() ? "  (operators)" : ""), item(e.icon()),
                GuideReply.fill(e.answer(), GuideClient.facts()), () -> openArticle(eid, true)));
        }
        add(rows);
    }

    private void buildAnswer(GuideSearch s, String question) {
        GuideSearch.Answer a = s.answer(question, GuideClient.operator());
        add(new Block(Kind.YOU_ASKED, question));
        if (a.mode() == GuideSearch.Mode.NONE) {
            add(new Block(Kind.GUIDE_HEAD, "I'm not sure about that one. It may not be part of this mod, or I may not know it by those words."));
            String fixed = GuideReply.corrected(a.corrected());
            if (!fixed.isEmpty()) note(fixed);
            if (!a.also().isEmpty()) {
                add(new Block(Kind.SECTION, "The nearest topics"));
                Block chips = new Block(Kind.CHIPS, "");
                for (GuideEntry e : a.also()) chips.chips.add(new Chip(e.title(), item(e.icon()), () -> openArticle(e.id(), true)));
                add(chips);
            }
            add(new Block(Kind.SECTION, "Or browse a topic"));
            Block cats = new Block(Kind.CHIPS, "");
            for (GuideBook.Category c : s.book().categories()) cats.chips.add(new Chip(c.title(), item(c.icon()), () -> openCategory(c.id())));
            add(cats);
            para("Try other words, or ask about one thing at a time: “how do I open a shop”, “what does the camera do”.", UiStyle.TEXT_FAINT, 0);
            return;
        }
        buildArticle(s, a.entry(), GuideReply.corrected(a.corrected()), a.mode() == GuideSearch.Mode.BLEND || !a.also().isEmpty() ? a.also() : null);
    }

    private void buildArticle(GuideSearch s, @Nullable GuideEntry e, String fixed, @Nullable List<GuideEntry> also) {
        if (e == null) { buildHome(s); return; }
        currentId = e.id();
        PlayerFacts f = GuideClient.facts();
        GuideBook.Category c = s.book().category(e.category());
        if (!view.kind().equals("answer")) add(new Block(Kind.CRUMB, "Browse › " + (c == null ? e.category() : c.title())));
        Block title = new Block(Kind.TITLE, e.title() + (e.op() ? "  (operators)" : ""));
        title.icon = item(e.icon());
        title.bar = 1;                                                     // the Guide's own mark before it: its reply
        add(title);
        if (!fixed.isEmpty()) note(fixed);
        String lead = GuideReply.lead(e, f);
        if (!lead.isEmpty()) para(lead, UiStyle.INFO, UiStyle.INFO_FILL);
        para(GuideReply.fill(e.answer(), f), UiStyle.TEXT, 0);
        if (!e.steps().isEmpty()) {
            add(new Block(Kind.SECTION, e.howto() ? "How to do it" : e.trouble() ? "What to check" : "Step by step"));
            Block steps = new Block(Kind.STEPS, "");
            for (String t : e.steps()) steps.lines.add(GuideReply.fill(t, f));
            add(steps);
        }
        if (!e.controls().isEmpty()) {
            add(new Block(Kind.SECTION, "Keys and buttons"));
            Block ctl = new Block(Kind.CONTROLS, "");
            ctl.lines.addAll(e.controls());
            add(ctl);
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (String id : e.items()) {
            ItemStack st = item(id);
            if (!st.isEmpty()) stacks.add(st);
        }
        if (!stacks.isEmpty()) {
            add(new Block(Kind.SECTION, "Things involved"));
            Block it = new Block(Kind.ITEMS, "");
            it.stacks.addAll(stacks);
            add(it);
        }
        if (!e.tips().isEmpty()) {
            add(new Block(Kind.SECTION, "Tips"));
            Block tips = new Block(Kind.BULLETS, "");
            tips.colour = UiStyle.SUCCESS_FILL;
            for (String t : e.tips()) tips.lines.add(GuideReply.fill(t, f));
            add(tips);
        }
        if (!e.warnings().isEmpty()) {
            add(new Block(Kind.SECTION, "Watch out"));
            Block warn = new Block(Kind.BULLETS, "");
            warn.colour = UiStyle.WARNING_FILL;
            for (String t : e.warnings()) warn.lines.add(GuideReply.fill(t, f));
            add(warn);
        }
        if (also != null && !also.isEmpty()) {
            add(new Block(Kind.SECTION, "You might also mean…"));
            Block chips = new Block(Kind.CHIPS, "");
            for (GuideEntry x : also) chips.chips.add(new Chip(x.title(), item(x.icon()), () -> openArticle(x.id(), true)));
            add(chips);
        }
        List<GuideEntry> related = new ArrayList<>();
        for (String r : e.related()) {
            GuideEntry x = s.book().get(r);
            if (x != null && (GuideClient.operator() || !x.op()) && (also == null || !also.contains(x))) related.add(x);
        }
        if (!related.isEmpty()) {
            add(new Block(Kind.SECTION, "Related"));
            Block chips = new Block(Kind.CHIPS, "");
            for (GuideEntry x : related) chips.chips.add(new Chip(x.title(), item(x.icon()), () -> openArticle(x.id(), true)));
            add(chips);
        }
    }

    private void add(Block b) {
        blocks.add(b);
    }

    private void para(String text, int colour, int bar) {
        Block b = new Block(Kind.PARA, text);
        b.colour = colour;
        b.bar = bar;
        add(b);
    }

    private void note(String text) {
        Block b = new Block(Kind.PARA, text);
        b.colour = UiStyle.TEXT_FAINT;
        add(b);
    }

    private static final Map<String, ItemStack> ITEMS = new HashMap<>();

    /** An item by its id ("mc_assistant:shop_counter"), empty if it is not one. */
    static ItemStack item(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        return ITEMS.computeIfAbsent(id, k -> {
            try {
                var it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(k));
                return it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
            } catch (RuntimeException e) {
                return ItemStack.EMPTY;
            }
        });
    }

    // ------------------------------------------------------------------ the page's pieces

    private enum Kind { GUIDE_HEAD, YOU_ASKED, CRUMB, TITLE, SECTION, PARA, STEPS, CONTROLS, ITEMS, BULLETS, CHIPS, TILES, ROWS }

    private record Chip(String text, @Nullable ItemStack icon, Runnable act) {}

    private record Tile(String title, ItemStack icon, String count, String blurb, Runnable act) {}

    private record Row(String title, ItemStack icon, String sub, Runnable act) {}

    /** One piece of a page: what it holds, and (once laid out for a width) its lines and height. */
    private static final class Block {
        final Kind kind;
        final String text;
        final List<String> lines = new ArrayList<>();
        final List<Chip> chips = new ArrayList<>();
        final List<Tile> tiles = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        final List<ItemStack> stacks = new ArrayList<>();
        ItemStack icon = ItemStack.EMPTY;
        int colour = UiStyle.TEXT, bar;
        // laid out
        int y, h, chars;
        final List<String> wrapped = new ArrayList<>();
        final List<List<String>> wrappedItems = new ArrayList<>();
        final List<int[]> boxes = new ArrayList<>();   // x, y, w, h (relative to the block), for chips, tiles, rows, controls

        Block(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (FormattedText t : font.getSplitter().splitLines(text, Math.max(20, width), Style.EMPTY)) out.add(t.getString());
        if (out.isEmpty()) out.add("");
        return out;
    }

    private int innerX() {
        return left + PAD + 8;
    }

    private int innerW() {
        return w - PAD * 2 - 20;
    }

    private int bodyTop() {
        return top + HEAD + ASK_H + 12 + SUGG_H;
    }

    private int bodyBottom() {
        return top + h - FOOT;
    }

    /** Lay the page out for the body's width: every block's lines, boxes and height, and the total. */
    private void layout() {
        int iw = innerW();
        if (laidWidth == iw) return;
        laidWidth = iw;
        int y = 6;
        revealTotal = 0;
        for (Block b : blocks) {
            b.wrapped.clear();
            b.wrappedItems.clear();
            b.boxes.clear();
            b.chars = 0;
            switch (b.kind) {
                case GUIDE_HEAD -> {
                    b.wrapped.addAll(b.text.isEmpty() ? List.of() : wrap(b.text, iw - 22));
                    b.h = Math.max(16, 4 + b.wrapped.size() * 10) + 2;
                    b.chars = b.text.length();
                }
                case YOU_ASKED -> {
                    int bw = Math.min(iw * 3 / 4, font.width(b.text) + 14);
                    b.wrapped.addAll(wrap(b.text, bw - 12));
                    int maxLine = 0;
                    for (String l : b.wrapped) maxLine = Math.max(maxLine, font.width(l));
                    b.boxes.add(new int[]{ iw - (maxLine + 12), 0, maxLine + 12, b.wrapped.size() * 10 + 8 });
                    b.h = b.wrapped.size() * 10 + 8 + 6;
                }
                case CRUMB -> b.h = 12;
                case TITLE -> {
                    b.wrapped.addAll(wrap(b.text, iw - (b.bar == 1 ? 44 : 24)));
                    b.h = Math.max(18, b.wrapped.size() * 11 + 4) + 2;
                    b.chars = b.text.length();
                }
                case SECTION -> b.h = 16;
                case PARA -> {
                    b.wrapped.addAll(wrap(b.text, iw - (b.bar != 0 ? 10 : 0)));
                    b.h = b.wrapped.size() * 10 + 4 + (b.bar != 0 ? 4 : 0);
                    b.chars = b.text.length();
                }
                case STEPS -> {
                    int hh = 0;
                    for (String s : b.lines) {
                        List<String> wl = wrap(s, iw - 20);
                        b.wrappedItems.add(wl);
                        b.boxes.add(new int[]{ 0, hh, iw, wl.size() * 10 + 4 });
                        hh += wl.size() * 10 + 5;
                        b.chars += s.length();
                    }
                    b.h = hh + 2;
                }
                case BULLETS -> {
                    int hh = 0;
                    for (String s : b.lines) {
                        List<String> wl = wrap(s, iw - 12);
                        b.wrappedItems.add(wl);
                        b.boxes.add(new int[]{ 0, hh, iw, wl.size() * 10 });
                        hh += wl.size() * 10 + 3;
                        b.chars += s.length();
                    }
                    b.h = hh + 2;
                }
                case CONTROLS -> b.h = layoutControls(b, iw);
                case ITEMS -> {
                    int x = 0, yy = 0;
                    for (ItemStack st : b.stacks) {
                        int cw = Math.min(iw, 24 + font.width(UiStyle.clip(font, st.getHoverName().getString(), iw - 26)));
                        if (x > 0 && x + cw > iw) { x = 0; yy += 22; }
                        b.boxes.add(new int[]{ x, yy, cw, 20 });
                        x += cw + 4;
                    }
                    b.h = yy + 24;
                    b.chars = 20;
                }
                case CHIPS -> {
                    int x = 0, yy = 0;
                    for (Chip c : b.chips) {
                        int cw = Math.min(iw, font.width(c.text()) + 12 + (c.icon() != null && !c.icon().isEmpty() ? 14 : 0));
                        if (x > 0 && x + cw > iw) { x = 0; yy += 19; }
                        b.boxes.add(new int[]{ x, yy, cw, 16 });
                        x += cw + 4;
                    }
                    b.h = yy + 20;
                    b.chars = 10;
                }
                case TILES -> {
                    int cols = Math.max(1, (iw + 6) / (150 + 6));
                    int tw = (iw - (cols - 1) * 6) / cols;
                    int th = 54;
                    for (int i = 0; i < b.tiles.size(); i++) {
                        b.boxes.add(new int[]{ (i % cols) * (tw + 6), (i / cols) * (th + 6), tw, th });
                        b.wrappedItems.add(wrap(b.tiles.get(i).blurb(), tw - 10));
                    }
                    int rowsN = (b.tiles.size() + cols - 1) / cols;
                    b.h = rowsN * (th + 6) + 2;
                }
                case ROWS -> {
                    int yy = 0;
                    for (int i = 0; i < b.rows.size(); i++) {
                        List<String> wl = wrap(b.rows.get(i).sub(), iw - 30);
                        if (wl.size() > 2) {
                            wl = new ArrayList<>(wl.subList(0, 2));
                            wl.set(1, UiStyle.clip(font, wl.get(1) + "\u2026\u2026", iw - 30));
                        }
                        b.wrappedItems.add(wl);
                        int rh = 15 + wl.size() * 10 + 3;
                        b.boxes.add(new int[]{ 0, yy, iw, rh });
                        yy += rh + 3;
                    }
                    b.h = yy + 2;
                }
            }
            b.y = y;
            y += b.h + GAP;
            revealTotal += b.chars;
        }
        contentH = y + 4;
    }

    /** The keys and buttons, a line each: its steps as chips and key-caps with arrows between, wrapped to the width. */
    private int layoutControls(Block b, int iw) {
        int yy = 0;
        for (String line : b.lines) {
            String[] segs = line.split(" → ");
            int x = 0;
            for (int i = 0; i < segs.length; i++) {
                int sw = segWidth(segs[i]);
                if (sw > iw) sw = iw;
                if (x > 0 && x + sw > iw) { x = 12; yy += 18; }
                b.boxes.add(new int[]{ x, yy, sw, 15, b.wrappedItems.size(), i });
                x += sw + (i + 1 < segs.length ? 14 : 0);
            }
            b.wrappedItems.add(List.of(segs));
            yy += 19;
            b.chars += line.length();
        }
        return yy + 2;
    }

    /** How wide one step of a control line draws: its key-caps and words. */
    private int segWidth(String seg) {
        int wsum = 0;
        for (Piece p : pieces(seg)) wsum += p.key ? font.width(p.text) + 8 : font.width(p.text);
        boolean lone = seg.startsWith("[") && seg.endsWith("]") && seg.indexOf('[', 1) < 0;
        return lone ? wsum : wsum + 10;
    }

    private record Piece(String text, boolean key) {}

    /** A line cut into its words and its key-caps ("[U]", "[Right-click]"). */
    private static List<Piece> pieces(String s) {
        List<Piece> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('[', i);
            if (open < 0) { out.add(new Piece(s.substring(i), false)); break; }
            int close = s.indexOf(']', open + 1);
            if (close < 0) { out.add(new Piece(s.substring(i), false)); break; }
            if (open > i) out.add(new Piece(s.substring(i, open), false));
            String key = s.substring(open + 1, close);
            if (key.isEmpty()) key = " ";
            out.add(new Piece(key, true));
            i = close + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, UiStyle.BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        layout();
        zones.clear();
        acts.clear();
        zoneTips.clear();
        tip = "";
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        UiStyle.headerBand(g, left, top, w, HEAD);
        g.renderItem(new ItemStack(Items.WRITTEN_BOOK), left + PAD, top + 5);
        UiStyle.title(g, font, "Guide", left + PAD + 20, top + 6);
        int tx = left + PAD + 20 + UiStyle.titleWidth(font, "Guide") + 8;
        int right = left + w - PAD;
        right = headButton(g, "Close", right, mx, my, this::onClose, "Close the Guide (Esc)");
        right = headButton(g, "Browse", right - 4, mx, my, this::home, "All the topics");
        if (!back.isEmpty()) right = headButton(g, "‹ Back", right - 4, mx, my, this::goBack, "Back to the last page (Esc)");
        UiStyle.text(g, font, "Ask anything about the towns and the folk", tx, top + 9, right - tx - 8, UiStyle.TEXT_MUTED);
        // the ask bar
        int ay = top + HEAD + 6;
        int askW = w - PAD * 2 - 62;
        UiStyle.well(g, left + PAD, ay, askW, ASK_H, 0xFFF4F4F4);
        if (box.isFocused()) g.renderOutline(left + PAD, ay, askW, ASK_H, UiStyle.ACCENT);
        box.setX(left + PAD + 6);
        box.setY(ay + 6);
        box.setWidth(askW - 12);
        if (box.getValue().isEmpty()) {                                   // the placeholder, shown while the box waits focused too
            UiStyle.text(g, font, "Ask anything \u2014 e.g. how do I start a business?", left + PAD + 6 + (box.isFocused() ? 6 : 0), ay + 6, askW - 18, 0xFF7A7A7A);
        }
        boolean canAsk = !box.getValue().isBlank();
        boolean overAsk = in(mx, my, left + w - PAD - 56, ay, 56, ASK_H);
        UiStyle.button(g, font, "Ask", left + w - PAD - 56, ay, 56, ASK_H, UiStyle.State.of(canAsk, true, overAsk));
        if (canAsk) zone(left + w - PAD - 56, ay, 56, ASK_H, () -> ask(box.getValue()), "Ask (Enter)");
        // the suggestions, or where you are
        int sy = ay + ASK_H + 4;
        if (!suggestions.isEmpty() && box.isFocused()) {
            int sx = left + PAD;
            UiStyle.text(g, font, "Suggestions:", sx, sy + 4, 70, UiStyle.TEXT_FAINT);
            sx += font.width("Suggestions:") + 6;
            for (int i = 0; i < suggestions.size(); i++) {
                GuideEntry e = suggestions.get(i);
                int cw = Math.min(font.width(e.title()) + 12, left + w - PAD - sx);
                if (cw < 40) break;
                boolean over = in(mx, my, sx, sy, cw, 14);
                chip(g, e.title(), null, sx, sy, cw, 14, over, i == 0);
                String id = e.id();
                zone(sx, sy, cw, 14, () -> openArticle(id, true), i == 0 ? "Open it (Tab)" : "Open it");
                sx += cw + 4;
            }
        } else {
            String where = switch (view.kind()) {
                case "answer" -> "Your question, answered below.";
                case "article", "category" -> "Ask another question above, or go back with Esc.";
                default -> HISTORY.isEmpty() ? "Type a question and press Enter. Typos are fine." : "↑ ↓ bring back what you asked before.";
            };
            UiStyle.text(g, font, where, left + PAD + 2, sy + 4, w - PAD * 2, UiStyle.TEXT_FAINT);
        }
        // the page
        int y0 = bodyTop(), y1 = bodyBottom();
        UiStyle.well(g, left + PAD, y0, w - PAD * 2, y1 - y0, UiStyle.PAGE);
        int room = y1 - y0 - 2;
        float max = Math.max(0, contentH - room);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll += (scrollTarget - scroll) * 0.4F;
        if (Math.abs(scrollTarget - scroll) < 0.5F) scroll = scrollTarget;
        int reveal = revealing ? (int) ((Util.getMillis() - revealStart) * CHARS_PER_MS) : Integer.MAX_VALUE;
        if (revealing && reveal >= revealTotal) revealing = false;
        g.enableScissor(left + PAD + 1, y0 + 1, left + w - PAD - 1, y1 - 1);
        int bx = innerX(), iw = innerW();
        int budget = reveal;
        for (Block b : blocks) {
            int by = y0 + b.y - Math.round(scroll);
            boolean visible = by + b.h >= y0 && by <= y1;
            if (budget <= 0 && revealing) break;
            if (visible) draw(g, b, bx, by, iw, mx, my, y0, y1, budget);
            budget -= b.chars;
        }
        g.disableScissor();
        if (max > 0) {
            int sbx = left + w - PAD - 6;
            UiStyle.scrollBar(g, sbx, y0 + 2, y1 - y0 - 4, Math.round(scroll), (int) max, room);
        }
        // the foot
        int fy = top + h - FOOT + 6;
        if ((view.kind().equals("answer") || view.kind().equals("article")) && currentEntry() != null) {
            String key = currentEntry();
            Integer said = HELPFUL.get(key);
            int fx = left + PAD;
            if (said == null) {
                UiStyle.text(g, font, "Was this helpful?", fx, fy + 2, 100, UiStyle.TEXT_MUTED);
                fx += font.width("Was this helpful?") + 6;
                fx = footButton(g, "Yes", fx, fy - 2, mx, my, () -> helpful(key, 1)) + 3;
                footButton(g, "No", fx, fy - 2, mx, my, () -> helpful(key, -1));
            } else {
                UiStyle.text(g, font, said > 0 ? "Thanks! Glad it helped." : "Thanks: try other words, or a Related topic.", fx, fy + 2, w / 2, UiStyle.SUCCESS);
            }
        }
        int kx = left + w - PAD;
        kx = hint(g, "back", "Esc", kx, fy);
        kx = hint(g, "history", "↑↓", kx - 8, fy);
        hint(g, "ask", "Enter", kx - 8, fy);
        for (var r : renderables) r.render(g, mx, my, pt);
        // the tip under the mouse
        for (int i = zones.size() - 1; i >= 0; i--) {
            int[] z = zones.get(i);
            if (mx >= z[0] && mx < z[2] && my >= z[1] && my < z[3]) {
                if (!zoneTips.get(i).isEmpty()) tip = zoneTips.get(i);
                break;
            }
        }
        if (!tip.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            UiStyle.tooltip(g, font, tip, mx, my, 220);
            g.pose().popPose();
        }
    }

    /** The article the page shows (for "Was this helpful?"), or null. */
    @Nullable
    private String currentEntry() {
        return currentId;
    }

    @Nullable private String currentId;

    private void helpful(String id, int v) {
        HELPFUL.put(id, v);
        UiStyle.click();
        try {
            java.nio.file.Path log = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mc_assistant-guide-feedback.txt");
            java.nio.file.Files.writeString(log, (v > 0 ? "yes " : "no  ") + id + (view.question().isEmpty() ? "" : "  “" + view.question() + "”") + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // local only, and only a convenience
        }
    }

    private int hint(GuiGraphics g, String label, String key, int right, int y) {
        int lw = font.width(label);
        int kw = font.width(key) + 6;
        int x = right - lw;
        g.drawString(font, label, x, y + 2, UiStyle.TEXT_FAINT, false);
        x -= kw + 3;
        keyCap(g, key, x, y, kw, 12);
        return x;
    }

    private int headButton(GuiGraphics g, String label, int right, int mx, int my, Runnable act, String tipText) {
        int bw = font.width(label) + 14;
        int x = right - bw;
        boolean over = in(mx, my, x, top + 5, bw, 16);
        UiStyle.button(g, font, label, x, top + 5, bw, 16, UiStyle.State.of(true, false, over));
        zone(x, top + 5, bw, 16, act, tipText);
        return x;
    }

    private int footButton(GuiGraphics g, String label, int x, int y, int mx, int my, Runnable act) {
        int bw = font.width(label) + 12;
        boolean over = in(mx, my, x, y, bw, 14);
        UiStyle.button(g, font, label, x, y, bw, 14, UiStyle.State.of(true, false, over));
        zone(x, y, bw, 14, act, "");
        return x + bw;
    }

    /** One block, at y, with {@code budget} characters of it to show (the reply typing itself out). */
    private void draw(GuiGraphics g, Block b, int x, int y, int iw, int mx, int my, int y0, int y1, int budget) {
        boolean inBody = my >= y0 && my < y1;
        switch (b.kind) {
            case GUIDE_HEAD -> {
                avatar(g, x, y);
                if (b.wrapped.isEmpty()) {
                    g.drawString(font, Component.literal("Guide").withStyle(s -> s.withBold(true)), x + 22, y + 4, UiStyle.ACCENT, false);
                } else {
                    int left = budget;
                    for (int i = 0; i < b.wrapped.size(); i++) {
                        left -= drawInline(g, b.wrapped.get(i), x + 22, y + 4 + i * 10, UiStyle.TEXT, left);
                        if (left <= 0) break;
                    }
                }
            }
            case YOU_ASKED -> {
                int[] bx = b.boxes.get(0);
                int ox = x + bx[0];
                g.fill(ox, y, ox + bx[2], y + bx[3], 0xFF9DB98A);
                g.fill(ox + 1, y + 1, ox + bx[2] - 1, y + bx[3] - 1, UiStyle.BUBBLE_YOU);
                for (int i = 0; i < b.wrapped.size(); i++) g.drawString(font, b.wrapped.get(i), ox + 6, y + 4 + i * 10, UiStyle.TEXT, false);
                UiStyle.text(g, font, "You asked", ox - font.width("You asked") - 6, y + 4, 80, UiStyle.TEXT_FAINT);
            }
            case CRUMB -> UiStyle.text(g, font, b.text, x, y + 2, iw, UiStyle.TEXT_FAINT);
            case TITLE -> {
                int tx = x;
                if (b.bar == 1) {
                    avatar(g, x, y);
                    tx += 20;
                }
                if (!b.icon.isEmpty()) {
                    g.renderItem(b.icon, tx, y);
                    tx += 20;
                }
                int left = budget;
                for (int i = 0; i < b.wrapped.size(); i++) {
                    String l = b.wrapped.get(i);
                    String shown = left >= l.length() ? l : l.substring(0, Math.max(0, left));
                    g.drawString(font, Component.literal(shown).withStyle(s -> s.withBold(true)), tx, y + 4 + i * 11, UiStyle.TEXT, false);
                    left -= l.length();
                    if (left <= 0) break;
                }
            }
            case SECTION -> UiStyle.heading(g, font, b.text, x, y + 5, iw);
            case PARA -> {
                int tx = x;
                if (b.bar != 0) {
                    g.fill(x, y, x + iw, y + b.h - 2, UiStyle.alpha(b.bar, 0.18F));
                    g.fill(x, y, x + 2, y + b.h - 2, b.bar);
                    tx += 6;
                    y += 2;
                }
                int left = budget;
                for (int i = 0; i < b.wrapped.size(); i++) {
                    left -= drawInline(g, b.wrapped.get(i), tx, y + i * 10, b.colour, left);
                    if (left <= 0) break;
                }
            }
            case STEPS -> {
                int left = budget;
                for (int i = 0; i < b.wrappedItems.size(); i++) {
                    if (left <= 0) break;
                    int[] bx = b.boxes.get(i);
                    int sy = y + bx[1];
                    g.fill(x, sy, x + 12, sy + 11, UiStyle.ACCENT_FILL);
                    String n = String.valueOf(i + 1);
                    g.drawString(font, n, x + 6 - font.width(n) / 2, sy + 2, 0xFFFFFFFF, false);
                    List<String> wl = b.wrappedItems.get(i);
                    for (int k = 0; k < wl.size(); k++) {
                        left -= drawInline(g, wl.get(k), x + 18, sy + 2 + k * 10, UiStyle.TEXT, left);
                        if (left <= 0) break;
                    }
                }
            }
            case BULLETS -> {
                int left = budget;
                for (int i = 0; i < b.wrappedItems.size(); i++) {
                    if (left <= 0) break;
                    int[] bx = b.boxes.get(i);
                    int sy = y + bx[1];
                    g.fill(x + 1, sy + 3, x + 5, sy + 7, b.colour);
                    List<String> wl = b.wrappedItems.get(i);
                    for (int k = 0; k < wl.size(); k++) {
                        left -= drawInline(g, wl.get(k), x + 10, sy + k * 10, UiStyle.TEXT, left);
                        if (left <= 0) break;
                    }
                }
            }
            case CONTROLS -> {
                int left = budget;
                int lastLine = -1;
                for (int[] bx : b.boxes) {
                    if (left <= 0) break;
                    List<String> segs = b.wrappedItems.get(bx[4]);
                    String seg = segs.get(bx[5]);
                    int sx = x + bx[0], sy = y + bx[1];
                    if (bx[5] > 0) g.drawString(font, "›", sx - 9, sy + 4, UiStyle.TEXT_FAINT, false);
                    drawSegment(g, seg, sx, sy, bx[2]);
                    if (bx[4] != lastLine) lastLine = bx[4];
                    left -= seg.length() + 3;
                }
            }
            case ITEMS -> {
                for (int i = 0; i < b.stacks.size(); i++) {
                    int[] bx = b.boxes.get(i);
                    int sx = x + bx[0], sy = y + bx[1];
                    ItemStack st = b.stacks.get(i);
                    boolean over = inBody && in(mx, my, sx, sy, bx[2], bx[3]);
                    UiStyle.tile(g, sx, sy, bx[2], bx[3], over ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
                    g.renderItem(st, sx + 2, sy + 2);
                    UiStyle.text(g, font, st.getHoverName().getString(), sx + 21, sy + 6, bx[2] - 24, UiStyle.TEXT);
                    if (over) tipZone(sx, sy, bx[2], bx[3], st.getHoverName().getString());
                }
            }
            case CHIPS -> {
                for (int i = 0; i < b.chips.size(); i++) {
                    int[] bx = b.boxes.get(i);
                    int sx = x + bx[0], sy = y + bx[1];
                    Chip c = b.chips.get(i);
                    boolean over = inBody && in(mx, my, sx, sy, bx[2], bx[3]);
                    chip(g, c.text(), c.icon(), sx, sy, bx[2], bx[3], over, false);
                    bodyZone(sx, sy, bx[2], bx[3], y0, y1, c.act(), font.width(c.text()) + 12 > bx[2] ? c.text() : "");
                }
            }
            case TILES -> {
                for (int i = 0; i < b.tiles.size(); i++) {
                    int[] bx = b.boxes.get(i);
                    int sx = x + bx[0], sy = y + bx[1];
                    Tile t = b.tiles.get(i);
                    boolean over = inBody && in(mx, my, sx, sy, bx[2], bx[3]);
                    UiStyle.card(g, sx, sy, bx[2], bx[3], over ? UiStyle.ACCENT_FILL : UiStyle.HIGHLIGHT_SPINE);
                    if (over) g.fill(sx + 3, sy + 1, sx + bx[2] - 1, sy + bx[3] - 1, 0x22FFFFFF);
                    g.renderItem(t.icon(), sx + 7, sy + 5);
                    g.drawString(font, Component.literal(UiStyle.clip(font, t.title(), bx[2] - 32)).withStyle(s -> s.withBold(true)), sx + 27, sy + 6, UiStyle.TEXT, false);
                    UiStyle.text(g, font, t.count(), sx + 27, sy + 16, bx[2] - 32, UiStyle.TEXT_FAINT);
                    List<String> wl = b.wrappedItems.get(i);
                    for (int k = 0; k < Math.min(2, wl.size()); k++) {
                        String l = wl.get(k);
                        if (k == 1 && wl.size() > 2) l = UiStyle.clip(font, l + "…", bx[2] - 12);
                        g.drawString(font, l, sx + 7, sy + 29 + k * 10, UiStyle.TEXT_MUTED, false);
                    }
                    bodyZone(sx, sy, bx[2], bx[3], y0, y1, t.act(), wl.size() > 2 ? t.blurb() : "");
                }
            }
            case ROWS -> {
                for (int i = 0; i < b.rows.size(); i++) {
                    int[] bx = b.boxes.get(i);
                    int sy = y + bx[1];
                    if (sy > y1 || sy + bx[3] < y0) continue;
                    Row r = b.rows.get(i);
                    boolean over = inBody && in(mx, my, x, sy, bx[2], bx[3]);
                    UiStyle.tile(g, x, sy, bx[2], bx[3], over ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
                    if (!r.icon().isEmpty()) g.renderItem(r.icon(), x + 4, sy + 3);
                    g.drawString(font, Component.literal(UiStyle.clip(font, r.title(), bx[2] - 32)).withStyle(s -> s.withBold(true)), x + 25, sy + 4, UiStyle.TEXT, false);
                    List<String> wl = b.wrappedItems.get(i);
                    for (int k = 0; k < wl.size(); k++) g.drawString(font, wl.get(k), x + 25, sy + 15 + k * 10, UiStyle.TEXT_MUTED, false);
                    bodyZone(x, sy, bx[2], bx[3], y0, y1, r.act(), "");
                }
            }
        }
    }

    /** The Guide's mark: a green square with a question mark, before what it says. */
    private void avatar(GuiGraphics g, int x, int y) {
        g.fill(x, y + 1, x + 16, y + 15, UiStyle.ACCENT_FILL);
        g.fill(x + 1, y + 2, x + 15, y + 14, 0xFF6FB043);
        g.drawString(font, "?", x + 6, y + 4, 0xFFFFFFFF, false);
    }

    /** A line of text with its key-caps drawn as caps; at most {@code most} characters. Returns how many it drew. */
    private int drawInline(GuiGraphics g, String line, int x, int y, int colour, int most) {
        if (most <= 0) return 0;
        int used = 0;
        int cx = x;
        for (Piece p : pieces(line)) {
            int len = p.key ? p.text.length() + 2 : p.text.length();
            if (used + len > most) {
                if (!p.key) {
                    String part = p.text.substring(0, Math.max(0, most - used));
                    g.drawString(font, part, cx, y, colour, false);
                }
                return most;
            }
            if (p.key) {
                int kw = font.width(p.text) + 4;
                g.fill(cx, y - 1, cx + kw, y + 9, 0xFFE8E8E8);
                g.fill(cx, y + 8, cx + kw, y + 9, 0xFF9A9A9A);
                g.renderOutline(cx, y - 1, kw, 10, 0xFFA8A8A8);
                g.drawString(font, p.text, cx + 2, y, UiStyle.TEXT, false);
                cx += kw + 1;
            } else {
                g.drawString(font, p.text, cx, y, colour, false);
                cx += font.width(p.text);
            }
            used += len;
        }
        return used;
    }

    /** One step of a control line: a lone key as a big cap, else a chip with its words and any caps in it. */
    private void drawSegment(GuiGraphics g, String seg, int x, int y, int sw) {
        List<Piece> ps = pieces(seg);
        boolean lone = ps.size() == 1 && ps.get(0).key;
        if (lone) {
            keyCap(g, ps.get(0).text, x, y, sw, 15);
            return;
        }
        g.fill(x, y, x + sw, y + 15, UiStyle.EDGE_SOFT);
        g.fill(x + 1, y + 1, x + sw - 1, y + 14, 0xFFEFEFE6);
        int cx = x + 5;
        for (Piece p : ps) {
            if (p.key) {
                int kw = font.width(p.text) + 6;
                keyCap(g, p.text, cx, y + 1, kw, 13);
                cx += kw + 2;
            } else {
                String t = UiStyle.clip(font, p.text, x + sw - cx - 3);
                g.drawString(font, t, cx, y + 4, UiStyle.TEXT, false);
                cx += font.width(p.text);
            }
        }
    }

    /** A key as a raised cap: a light face, a darker lip under it, the key's name in it. */
    private void keyCap(GuiGraphics g, String key, int x, int y, int kw, int kh) {
        g.fill(x, y, x + kw, y + kh, 0xFF5A5A5A);
        g.fill(x + 1, y + 1, x + kw - 1, y + kh - 1, 0xFFF7F7F7);
        g.fill(x + 1, y + kh - 3, x + kw - 1, y + kh - 1, 0xFFC4C4C4);
        g.drawString(font, key, x + (kw - font.width(key)) / 2, y + (kh - 9) / 2, UiStyle.TEXT, false);
    }

    /** A clickable chip: a soft tile with an icon and words, lit under the mouse. */
    private void chip(GuiGraphics g, String text, @Nullable ItemStack icon, int x, int y, int cw, int ch, boolean over, boolean first) {
        g.fill(x, y, x + cw, y + ch, over ? UiStyle.ACCENT : UiStyle.EDGE_SOFT);
        g.fill(x + 1, y + 1, x + cw - 1, y + ch - 1, over ? UiStyle.HIGHLIGHT_HOVER : first ? UiStyle.TILE_SELECTED : UiStyle.HIGHLIGHT);
        int tx = x + 6;
        if (icon != null && !icon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 2, y + (ch - 12) / 2.0F, 0);
            g.pose().scale(0.75F, 0.75F, 1F);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
            tx += 12;
        }
        UiStyle.text(g, font, text, tx, y + (ch - 8) / 2, x + cw - tx - 4, UiStyle.TEXT);
    }

    private void zone(int x, int y, int zw, int zh, Runnable act, String tipText) {
        zones.add(new int[]{ x, y, x + zw, y + zh });
        acts.add(act);
        zoneTips.add(tipText == null ? "" : tipText);
    }

    /** A clickable place on the page: only the part of it inside the body counts. */
    private void bodyZone(int x, int y, int zw, int zh, int y0, int y1, Runnable act, String tipText) {
        int a = Math.max(y, y0), b2 = Math.min(y + zh, y1);
        if (b2 <= a) return;
        zone(x, a, zw, b2 - a, act, tipText);
    }

    private void tipZone(int x, int y, int zw, int zh, String tipText) {
        zones.add(new int[]{ x, y, x + zw, y + zh });
        acts.add(() -> {});
        zoneTips.add(tipText);
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (revealing) {
            revealing = false;
            if (my >= bodyTop() && my < bodyBottom()) return true;           // the first click shows the reply whole
        }
        if (button == 0) {
            int sbx = left + w - PAD - 8;
            if (mx >= sbx && mx < sbx + 8 && my >= bodyTop() && my < bodyBottom() && contentH > bodyBottom() - bodyTop()) {
                dragBar = true;
                dragTo(my);
                return true;
            }
            for (int i = zones.size() - 1; i >= 0; i--) {
                int[] z = zones.get(i);
                if (mx >= z[0] && mx < z[2] && my >= z[1] && my < z[3]) {
                    acts.get(i).run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragBar) {
            dragTo(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragBar = false;
        return super.mouseReleased(mx, my, button);
    }

    private void dragTo(double my) {
        int y0 = bodyTop(), y1 = bodyBottom();
        float max = Math.max(0, contentH - (y1 - y0 - 2));
        float f = (float) ((my - y0) / Math.max(1, y1 - y0));
        scrollTarget = scroll = Math.max(0, Math.min(max, f * max));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scrollTarget -= (float) (dy * 28);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        revealing = false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (!goBack()) onClose();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!box.getValue().isBlank()) ask(box.getValue());
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (!HISTORY.isEmpty()) {
                    historyAt = historyAt < 0 ? HISTORY.size() - 1 : Math.max(0, historyAt - 1);
                    setBox(HISTORY.get(historyAt));
                }
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (historyAt >= 0) {
                    historyAt++;
                    if (historyAt >= HISTORY.size()) { historyAt = -1; setBox(""); }
                    else setBox(HISTORY.get(historyAt));
                }
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                if (!suggestions.isEmpty()) openArticle(suggestions.get(0).id(), true);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { scrollTarget += bodyBottom() - bodyTop() - 30; return true; }
            case GLFW.GLFW_KEY_PAGE_UP -> { scrollTarget -= bodyBottom() - bodyTop() - 30; return true; }
            default -> { }
        }
        if (!box.isFocused()) setFocused(box);
        return super.keyPressed(key, scan, mods);
    }

    private void setBox(String text) {
        int keep = historyAt;
        box.setValue(text);
        box.moveCursorToEnd(false);
        historyAt = keep;
        suggestions = List.of();
    }

    @Override
    public boolean charTyped(char c, int mods) {
        revealing = false;
        if (!box.isFocused()) setFocused(box);
        return super.charTyped(c, mods);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
